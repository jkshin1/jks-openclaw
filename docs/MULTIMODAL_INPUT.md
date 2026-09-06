# Photo and voice input

Added 2026-09-01. Read [`../AGENTS.md`](../AGENTS.md) before any device work, and
[`PROJECT_STATUS.md`](PROJECT_STATUS.md) for what is actually qualified.

## What this adds

The pinned artifact is multimodal. This delta lets the owner attach one photo or one short voice
clip to a turn, and adds a dictation path that turns speech into an editable composer draft.

| Task | Entry point | Decode ceiling |
|---|---|---|
| 사진 설명 | camera or picker, no typed line | 384 |
| 사진 속 질문 | camera or picker plus a question | 384 |
| 문서·영수증·화이트보드 정리 | typed wording names a document | 1,024 |
| 사진 속 글자 그대로 추출 | typed wording asks for the literal text | 1,024 |
| 사진 속 글자 번역 | typed wording asks for a translation | 1,024 |
| 음성 받아쓰기 | microphone button | 384 |
| 음성 메모 요약 | attached clip plus 요약 wording | 1,024 |
| 음성 질문 | attached clip plus a question | 384 |

The task set is benchmarked against what mainstream assistant apps offer for photo and voice
input — describe a scene, read a document, extract text, translate, ask about the image, dictate,
summarize a voice note, ask by voice — rather than being a generic "attach a file" affordance.
`TurnMediaIntent` names each one, and each earns its own app-authored instruction and ceiling.

## The one rule that shapes everything

**A turn carrying media is given no Tool schema at all.**

A photographed sticky note and a recorded sentence are both untrusted input that can read like an
instruction, and there is no reliable way to separate an observed instruction from the owner's own.
Rather than trying, a media turn is handed `LlmTurnToolScope.none()`. Nothing the attachment "asks
for" has anywhere to execute, so image and audio remain things the model reports on.

This is enforced in three independent places, so no single mistake reopens it:

1. `TurnMediaPlan.toolScope` is always empty, whatever the intent.
2. `ManualToolAgentController.runTurn` forces `LlmTurnToolScope.none()` when media is present,
   disables automatic scoping, skips deterministic read routing, and refuses outright if the
   caller also passed an execution contract, a contextual search request, or a non-empty scope.
3. The ViewModel's media turn runs with `maxSteps = 1`, so the loop aborts on any Tool call in the
   first completed step — before preparation, and therefore before any confirmation sheet.

The app-authored prompt also tells the model that the attachment is an observation and that no
Tool exists this turn. That is defense in depth for the wording of the answer; the empty scope is
the guarantee.

### So how do voice commands work?

Through dictation, in two steps. The microphone button records, the clip is transcribed by the
model, and the transcript lands in the composer as an **editable draft**. The owner reads it,
corrects it, and presses send. That second turn is an ordinary text turn which earns its Tool
scope the ordinary way.

This is deliberate rather than a limitation. It puts a human read between "what the microphone
heard" and "what the agent does", which is exactly the boundary that matters when the action can
create a calendar event or send a message.

## What is retained and what is only staged

No media payload is durably retained by Personal Edge. One camera file necessarily exists briefly
in the app's private cache so an external camera app can return a full-size capture:

- A picked or captured photo is decoded, downscaled, re-encoded, and held in one private ViewModel
  field. It never enters `PersonalEdgeUiState`, so it cannot reach a state snapshot or a
  recomposition trace.
- A recording goes from `AudioRecord` straight to an in-memory WAV. It never touches storage.
- The camera app cannot return bytes in process, so it writes to one file under
  `cacheDir/media-capture`. `MediaCaptureStaging` keeps at most one file there, deletes it as soon
  as it is read, and sweeps the directory on every prepare in case a process died mid-capture.
  Process start also retries deletion for stale files at least one hour old; it deliberately leaves
  a fresh target alone because an external camera may still be returning into a recreated process.
  Deletion is best effort, and the OS cache policy is the final reclamation boundary.

Room schema 11 adds `messages.attachment_summary`: a short app-written code holding a kind, a
source, and for audio a whole-second length (`IMAGE:CAMERA`, `AUDIO:VOICE:12`). That is the entire
durable trace. There are no pixels, samples, dimensions, file names, paths, or URIs in it, and a
value this app did not write renders nothing rather than putting an unrecognized stored string in
front of the owner.

The encrypted transfer archive carries this content-free column, never the media. Archive envelope
version 1 now uses transfer schema `6` and payload version `2`; only the producer-canonical
`IMAGE:{CAMERA,GALLERY}` and `AUDIO:VOICE:<whole seconds>` shapes on USER rows are accepted. Legacy
schema `5`/payload `1` archives still import with the field set to null. Preserving “사진 1장” across
devices records only that the historical user turn had an attachment; it does not claim the new
device can display the absent photo. Attachment-only USER rows feed the same app-authored label to
recent-turn context and long-term summarization, while the raw code and payload remain excluded.

Diagnostics gain `DiagnosticEvent.MediaAttachment`: a kind, a stage
(`STAGED`/`REJECTED`/`PREFILLED`/`DISCARDED`), a payload byte count, and for audio a whole-second
length. The byte count is what makes a later on-device prefill cost interpretable without
retaining anything of what was photographed or said.

## Privacy work the loader does

Re-encoding a photo is the privacy step, not just a size step. A camera file carries EXIF: GPS
coordinates, device make and model, capture timestamp, sometimes a thumbnail of the original
frame. Decoding to a bitmap and compressing a fresh JPEG drops all of it.

Orientation is the one tag that must be honoured before it is discarded, or a portrait photo
arrives sideways and the model describes it that way.
`ImageAttachmentLoaderTest.locationAndDeviceMetadataNeverReachTheModel` asserts the fixture really
carries GPS and device tags and that the encoded payload carries none.

There is deliberately **no CAMERA permission**. Photos come from the system photo picker
(`PickVisualMedia`, which needs no permission and grants only the chosen item) or from the camera
app through `ACTION_IMAGE_CAPTURE`, which only requires the permission when the app declares it.
`RECORD_AUDIO` is the one new runtime permission, requested on first use of dictation.

## Bounds and why they are what they are

| Bound | Value | Reason |
|---|---|---|
| Attachments per turn | 1 | A second image roughly doubles an already large prefill inside a 4,096-token context |
| Image long edge | 768 px | Largest native input resolution documented for this model family; the encoder resizes internally anyway |
| Image payload | 64 B – 1.5 MB | Base64 across the JNI bridge inflates by ~4/3 |
| Audio | 16 kHz, mono, 16-bit PCM in WAV | The runtime states only mono is supported; the model documents 16 kHz single channel |
| Clip length | 0.4 s – 20 s | Documented ceiling is 30 s; 20 s leaves the request and answer room in the same context |
| Typed line with an attachment | 512 UTF-8 bytes | The app-authored template plus the device preamble share the same 2 KiB turn envelope |

Documented per-modality token costs are recorded in `TurnMediaBudget` so the byte and duration
caps can be read against the 4,096-token context rather than looking arbitrary: 256 tokens for an
image, 25 tokens per second of audio for Gemma 4. Both were published figures when they were
written down; the Fold8 run below has since measured 258 tokens for one image and 24.93 per second
of audio on this exact artifact, so the constants are now confirmed rather than assumed.

## Model capability is a declared fact, not a guess

`models/model-manifest.json` now declares `supportsImageInput` and `supportsAudioInput`, and
`ModelManifest` carries them through to the runtime, which refuses a modality the verified manifest
does not declare. The declaration is a ceiling, not a switch: what the engine actually loads is
what initialization asks for, which is what the owner enabled. The declaration is evidence-backed: the pinned artifact's own header contains
`tf_lite_vision_encoder`, `tf_lite_vision_adapter`, `tf_lite_end_of_vision`,
`tf_lite_audio_encoder_hw`, `tf_lite_audio_adapter`, and `tf_lite_end_of_audio`, and its embedded
jinja template renders an `image` content item as `<|image|>` and an `audio` item as `<|audio|>`.

The `qwen8bLab` manifest declares both false, so that instrumentation-only build can never be
handed media even by a caller that asks.

## Historical Fold8 findings, 2026-09-01

The owner connected the phone and approved a same-certificate release update. Four things came out
of it that no amount of host work would have found.

### The engine never loaded its encoders

`EngineConfig` carries `visionBackend`, `audioBackend`, and `maxNumImages`, and this app was
setting none of them. A media turn therefore failed with `NATIVE_FAILURE`, and the native log shows
why it was so easy to misread:

```
stb_image_preprocessor.cc:88] Resize image from 768x512 to 960x624 which will result in
                              2340 patches to fit the max_num_patches: 2520 limit.
litertlm.cc:1334] OnError: INVALID_ARGUMENT: Vision executor should not be null,
                  please TryLoadingVisionExecutor() first.
```

The image was decoded, resized and patched successfully *before* the failure, so every signal
short of the native log pointed at a bad attachment rather than an unconfigured engine.

### A synthetic photo was observed to work

With the executors configured, the `IMAGE_TEXT_EXTRACT` turn on a synthetic high-contrast card
returned exactly the six rendered digits and nothing else — a six-code-point answer containing
`482913`, with no Tool call. That historical run is strong evidence that the vision encoder ran on
that artifact/device. It is not current-source physical acceptance and it says nothing about an
owner photo or the camera/picker production flow.

### Loading the encoders costs the GPU backend for everything

Same session, same prompt, same ceiling:

| | backend | init | text turn | PSS |
|---|---|---|---|---|
| Encoders not configured | GPU | 8,183 ms | 20,640 ms | — |
| Encoders configured | fell back to CPU | 10,755 ms | 31,534 ms | 6,208,525,312 B |

Requesting GPU with the vision and audio executors enabled makes the whole engine fall back to
CPU, so **every text turn** becomes about 53% slower and resident memory grows by roughly 1.4 GB
over the ~4.79 GB previously recorded for text-only CPU runs.

That is why the encoders are no longer loaded because the *manifest* declares them. Initialization
now takes the modalities the owner actually enabled, the runtime fails a turn closed if it carries
a modality the engine did not load, and someone who leaves media switched off pays none of this.
The engine picks its encoders once, exactly like its backend, so enabling media applies from the
next app start — the settings footnote says so and the composer explains it if an attachment is
attempted first.

### Both per-modality costs are now measured

The first version of this receipt compared accumulated token totals in one shared conversation, and
also compared materially different image and audio prompts, so conversation growth and prompt
length could masquerade as encoder cost. The corrected gate gives each control and media
observation a newly initialized runtime and exactly one turn, samples `getTokenCount()` immediately
before and after that turn, and subtracts paired per-turn increments for identical prompts. Both
answers are capped at 32 decode tokens, below either threshold, so answer-length variation alone
cannot satisfy them.

It ran on the Fold8 on 2026-09-01 and passed, 1 case in 82.714 s on CPU:

| observation | init | turn | TTFT | context tokens | answer |
|---|---|---|---|---|---|
| image control | 1,158 ms | 14,056 ms | 12,989 ms | 648 | 23 code points |
| image | 1,583 ms | 23,691 ms | 23,114 ms | 906 | 6 code points |
| audio control | 1,148 ms | 14,137 ms | 13,065 ms | 610 | 21 code points |
| audio, 15 s clip | 1,052 ms | 20,587 ms | 19,822 ms | 984 | 19 code points |

**One image costs 258 context tokens** against the documented 256, and **a 15-second clip costs
374**, or 24.93 per second against the documented 25. All four runtimes loaded the same
`vision_encoder`, `vision_adapter`, `static_audio_encoder`, and `audio_adapter` caches, so the
difference between a control and its paired media turn is the payload and nothing else. The image
turn again returned exactly the six rendered digits, and no turn produced a Tool call.

Media adds roughly 7-10 seconds of prefill on CPU: a control reached its first token in about 13 s,
the image turn in 23.1 s and the audio turn in 19.8 s. The ~220-token image figure from the first
invalid comparison is superseded by the 258 measured here.

## What is verified and what is not

Host coverage exercises the boundary with JVM tests — container signature validation, the
byte and duration envelope, the manifest capability gate, intent resolution, prompt construction
inside the runtime envelope, the empty Tool scope for every intent, the controller's refusal to
combine media with a contract or a caller scope, image decode geometry, the WAV encoder byte for
byte, silence detection, the attachment summary codec, and the composer availability matrix.

Historical emulator coverage exercised `ImageAttachmentLoaderTest`, the then-current
`MediaCaptureStagingTest`, the Room 10→11 migration, and the repository attachment column. The new
stale process-start sweep cases, transfer preservation cases, and attachment-only summary/context
cases have host passes or Android-source compilation only until the scoped AVD suite is rerun.

Historical Fold8, 2026-09-01: a synthetic image prefilled through LiteRT-LM and was read correctly on a
same-certificate release update whose pulled-back APK hash matched the local build, with
`firstInstallTime` preserved and content-free preservation snapshots identical before and after.

**Not currently verified:** the corrected fresh-runtime image/audio delta gate has not executed on
the Fold8. The audio front end was observed historically, but its context contribution was not
validly measured. Transcription accuracy on real Korean speech is untouched. The old per-image
delta is invalid as a current exact cost. The GPU path for media did not exist in the historical
session — the engine fell back to CPU — so there is no GPU media measurement. Per-modality token
figures in `TurnMediaBudget` remain Google's documentation.

## Physical acceptance still owed

1. Dictation accuracy on real Korean speech, including the silence and too-short refusals.
2. Document, receipt, and whiteboard photos against the four image intents.
3. Camera and picker flows through the production ViewModel path, including the permission-free
   capture claim and the transcript's content-free attachment chip.
4. Thermal behaviour across a sustained sequence of media turns. The accepted run was 82.7 s of CPU
   inference and moved the battery 31.0 C to 32.2 C while charging, which says nothing about a long
   session.
5. Fold/DeX layouts for the composer's attachment row and recording bar.

The per-modality context cost, the image path itself, and the empty Tool scope on a media turn are
no longer on this list: they were measured on the phone on 2026-09-01.
