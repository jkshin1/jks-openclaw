---
name: hermes-operations-report
description: Use when the owner explicitly asks Hermes to summarize an operations-event JSON file and distinguish task execution from Telegram delivery receipts.
user-invocable: true
---

# Hermes operations report

Use the isolated Hermes worker for the owner's requested operations-event report.
OpenClaw owns the conversation and final delivery. Hermes receives only the supplied
event fields and can read its procedure skill; it cannot run commands, read other
files, send messages, or schedule work.

1. Use the exact JSON file supplied or selected by the owner. It must contain
   `schema_version: 1` and an `events` array. Every event has `event_id`, `task_id`,
   `observed_at` (ISO 8601 with a timezone), `execution_status`
   (`completed`, `running`, `failed`), `delivery_status`
   (`accepted`, `unknown`, `failed`), and optional `message_id`.
   Do not browse the owner's history to invent missing input.
2. Choose a fresh output directory beneath
   `/Users/jk/.local/share/openclaw-hermes-worker/runs/`. Invoke the installed
   wrapper with safely quoted arguments:

   ```text
   python3 /Users/jk/.local/share/openclaw-hermes-worker/bin/hermes-operations-report.py --input-file <absolute-json-path> --output-dir <fresh-absolute-directory>
   ```

3. Wait for this exact process to terminate and inspect its exit status and JSON
   receipt. Do not start it again because a Telegram reply is delayed. A failure
   is a failure; do not silently switch to a different model or provider.
4. On success, read the returned `report.json` and `verification.json`. Explain the
   result in Korean, with execution counts and confirmed/unconfirmed delivery
   counts kept separate. The wrapper checks the answer against an independent
   evaluator. Include the report file when useful through OpenClaw's normal
   attachment path and delivery policy.
5. `telegramDelivered:false` in a worker receipt means Hermes itself did not send
   a message. A later OpenClaw send needs its own real transport receipt.
   Failed delivery is a reason to retry the transport, not to execute the task
   again. Telegram API acceptance does not prove the phone opened the result.

If the worker says `INDEPENDENT_OAUTH_REQUIRED`, explain that its separate ChatGPT
login is needed. Never copy the main Codex/OpenClaw tokens, read an auth file into
the conversation, request an API key, or run a login/installer as a fallback.
Profile memories and the owner's OpenClaw memory are separate. Changes to the
report procedure belong to a deliberate, verified training run; ordinary reports
reuse it without rewriting it.
