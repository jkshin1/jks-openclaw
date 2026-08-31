#!/usr/bin/env python3
"""Drive the fixed Korean tool-use corpus against a local OpenAI-compatible endpoint.

This is a HOST-ONLY model comparison harness. It measures how a candidate model selects
Tools when given the exact production system instruction and the exact production Tool
schemas. It does not run the Kotlin agent loop, so it is not a device, provider, or
agent-loop receipt and it never emits device telemetry.

Prompts, schemas, and the system instruction are extracted from the current tree so a
score can never be produced against a stale copy of the production contract.
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import importlib.util
import json
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "models" / "eval" / "korean-tool-use-v1.jsonl"
POLICY_KT = ROOT / "core/llm/src/main/kotlin/com/personaledge/core/llm/LiteRtConversationPolicy.kt"
REGISTRY_KT = ROOT / "core/agent/src/main/kotlin/com/personaledge/core/agent/ManualToolRegistry.kt"
TOOLS_DIR = ROOT / "core/tools/src/main/kotlin"

_SPEC = importlib.util.spec_from_file_location(
    "personal_edge_eval_validator",
    Path(__file__).with_name("validate-model-eval-corpus.py"),
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("model evaluation validator is unavailable")
_VALIDATOR = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_VALIDATOR)
TOOLS = _VALIDATOR.TOOLS
WRITE_TOOLS = _VALIDATOR.WRITE_TOOLS
TOOL_ARGUMENT_FIELDS = _VALIDATOR.TOOL_ARGUMENT_FIELDS
load_rows = _VALIDATOR.load_rows

# Only multi-tool-01 expects a state that a single completion cannot expose: production
# reaches tool_follow_up after the Kotlin loop dispatches the read and re-enters the model.
# The harness derives "completed" there, so finalStateAccuracy is structurally capped.
STRUCTURALLY_UNDECIDABLE = {"multi-tool-01": "tool_follow_up"}

REFUSAL_MARKERS = (
    "할 수 없", "할수없", "지원하지 않", "지원되지 않", "불가능", "거절",
    "기억하지 않", "저장하지 않", "만들지 않", "보내지 않", "실행하지 않",
    "권한이 없", "제공하지 않", "종료",
)
SUCCESS_MARKERS = (
    "이미 보냈", "이미 전송", "이미 저장", "이미 등록", "이미 생성", "이미 실행",
    "보냈습니다", "전송했습니다", "저장했습니다", "등록했습니다", "생성했습니다",
    "실행했습니다", "완료했습니다",
)
CLARIFICATION_MARKERS = ("?", "？", "알려 주시", "알려주시", "지정해 주", "어느 것", "무엇을 기준")

HANGUL_RANGES = ((0xAC00, 0xD7A3), (0x1100, 0x11FF), (0x3130, 0x318F))


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def kotlin_literals(fragment: str) -> str:
    """Join a Kotlin concatenation of double-quoted literals into one string."""
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', fragment)
    joined = "".join(parts)
    return (
        joined.replace("\\\\", "\x00")
        .replace('\\"', '"')
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\x00", "\\")
    )


def extract_system_instruction() -> str:
    source = POLICY_KT.read_text(encoding="utf-8")
    match = re.search(
        r"SYSTEM_INSTRUCTION\s*:\s*String\s*=(.*?)\n\s*fun\s+createConfig", source, re.S
    )
    if match is None:
        fail(f"could not locate SYSTEM_INSTRUCTION in {POLICY_KT}")
    instruction = kotlin_literals(match.group(1)).strip()
    if len(instruction) < 512:
        fail("extracted system instruction is implausibly short; refusing to score")
    return instruction


def extract_tool_schemas() -> dict[str, dict]:
    """Map each corpus Tool to its exact production JSON schema.

    Schema constants are matched by their required/property signature rather than by name,
    so a renamed constant cannot silently bind the wrong schema. Anything ambiguous or
    missing fails closed.
    """
    source = REGISTRY_KT.read_text(encoding="utf-8")
    constants = dict(re.findall(r'private const val (\w+)\s*=\s*"""(.*?)"""', source, re.S))
    for _ in range(8):
        resolved = {
            key: re.sub(r"\$(\w+)", lambda m: constants.get(m.group(1), m.group(0)), value)
            for key, value in constants.items()
        }
        if resolved == constants:
            break
        constants = resolved
    # A bare `$` is a legal regex anchor inside these schemas; only an identifier
    # reference means a constant failed to resolve.
    if any(
        re.search(r"\$[A-Za-z_]", value)
        for name, value in constants.items()
        if name.endswith("_SCHEMA")
    ):
        fail("unresolved Kotlin string interpolation in a Tool schema constant")

    signatures: dict[str, tuple[frozenset, frozenset, dict]] = {}
    for name, raw in constants.items():
        if not name.endswith("_SCHEMA"):
            continue
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            continue
        signatures[name] = (
            frozenset(parsed.get("required", [])),
            frozenset(parsed.get("properties", {})),
            parsed,
        )

    schemas: dict[str, dict] = {}
    for tool, (required, allowed) in TOOL_ARGUMENT_FIELDS.items():
        matches = [
            name
            for name, (req, props, _) in signatures.items()
            if req == frozenset(required) and props == frozenset(allowed)
        ]
        if len(matches) != 1:
            fail(f"{tool}: expected exactly one matching production schema, found {matches}")
        schemas[tool] = signatures[matches[0]][2]
    if set(schemas) != TOOLS:
        fail("extracted schema set does not equal the corpus Tool set")
    return schemas


def extract_tool_descriptions_from_sources(
    sources: list[tuple[str, str]],
    expected_tools: set[str],
) -> dict[str, str]:
    """Bind each Tool description to the following companion NAME in the same source segment.

    Production Tool classes declare their descriptor before their companion object. Segmenting on
    every NAME declaration prevents a later class's closer description from being paired with the
    previous class. Missing, duplicate, or structurally ambiguous bindings fail closed.
    """
    descriptions: dict[str, str] = {}
    name_pattern = re.compile(r'const val NAME\s*(?::\s*String\s*)?=\s*"([A-Za-z][A-Za-z0-9_]*)"')
    descriptor_pattern = re.compile(r"override\s+val\s+descriptor\s*=\s*ToolDescriptor\s*\(")
    description_pattern = re.compile(
        r'description\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)[,)]'
    )

    for source_label, text in sources:
        segment_start = 0
        for name_anchor in name_pattern.finditer(text):
            tool = name_anchor.group(1)
            segment = text[segment_start:name_anchor.start()]
            segment_start = name_anchor.end()
            if tool not in expected_tools:
                continue

            descriptor_anchors = list(descriptor_pattern.finditer(segment))
            if len(descriptor_anchors) != 1:
                fail(
                    f"{tool}: expected one descriptor before NAME in {source_label}, "
                    f"found {len(descriptor_anchors)}"
                )
            descriptor = segment[descriptor_anchors[0].start():]
            if re.search(r"name\s*=\s*NAME\s*,", descriptor) is None:
                fail(f"{tool}: descriptor in {source_label} is not bound through NAME")
            blocks = list(description_pattern.finditer(descriptor))
            if len(blocks) != 1:
                fail(
                    f"{tool}: expected one literal description in {source_label}, "
                    f"found {len(blocks)}"
                )
            description = kotlin_literals(blocks[0].group(1)).strip()
            if not description:
                fail(f"{tool}: production description is empty in {source_label}")
            if tool in descriptions:
                fail(f"{tool}: duplicate production description binding")
            descriptions[tool] = description

    missing = sorted(expected_tools - set(descriptions))
    unexpected = sorted(set(descriptions) - expected_tools)
    if missing or unexpected:
        fail(f"production Tool description set mismatch; missing={missing}, unexpected={unexpected}")
    return descriptions


def extract_tool_descriptions() -> dict[str, str]:
    sources = [
        (str(path.relative_to(ROOT)), path.read_text(encoding="utf-8"))
        for path in sorted(TOOLS_DIR.rglob("*.kt"))
    ]
    return extract_tool_descriptions_from_sources(sources, TOOLS)


def build_openai_tools(schemas: dict[str, dict], descriptions: dict[str, str]) -> list[dict]:
    return [
        {
            "type": "function",
            "function": {
                "name": tool,
                "description": descriptions[tool],
                "parameters": schemas[tool],
            },
        }
        for tool in sorted(TOOLS)
    ]


def adapt_system_instruction(system: str, tools: list[dict], tool_format: str) -> str:
    """Add only the model-family adapter needed to expose Tool schemas.

    Hermes 3's published function-calling format expects Tool definitions in the
    system message. Its GGUF ChatML template does not consume the OpenAI `tools`
    request field, so sending that field alone silently hides every Tool.
    """
    if tool_format != "hermes":
        return system
    serialized = "\n".join(json.dumps(tool, ensure_ascii=False, separators=(",", ":")) for tool in tools)
    return (
        system
        + "\n\nYou are also a function-calling model. Available function signatures are inside "
        "<tools></tools> tags. Use a function when the request requires one, do not invent "
        "missing argument values, and otherwise answer normally. Return every function call "
        "as a JSON object with `name` and `arguments` inside <tool_call></tool_call> tags.\n"
        + "<tools>\n"
        + serialized
        + "\n</tools>"
    )


def count_script(text: str) -> tuple[int, int, int]:
    hangul = sum(
        1
        for character in text
        if any(low <= ord(character) <= high for low, high in HANGUL_RANGES)
    )
    latin = sum(
        1
        for character in text
        if unicodedata.category(character).startswith("L")
        and "LATIN" in unicodedata.name(character, "")
    )
    other_letters = sum(
        1
        for character in text
        if unicodedata.category(character).startswith("L")
        and not any(low <= ord(character) <= high for low, high in HANGUL_RANGES)
        and "LATIN" not in unicodedata.name(character, "")
    )
    return hangul, latin, other_letters


def derive_response_language(text: str) -> tuple[str, bool]:
    """Mirror the production rule: a reply must be entirely in the requested language."""
    hangul, latin, other_letters = count_script(text)
    if hangul > 0 and latin == 0 and other_letters == 0:
        return "ko", False
    if latin > 0 and hangul == 0 and other_letters == 0:
        return "en", False
    if hangul + latin + other_letters > 0:
        return "mixed", False
    return "ko", True


def parse_pythonic_tool_calls(text: str) -> list[dict]:
    """Parse the LFM2.5 `<|tool_call_start|>[fn(a=1)]<|tool_call_end|>` form."""
    pattern = re.compile(r"<\|tool_call_start\|>(.*?)<\|tool_call_end\|>", re.S)
    blocks = list(pattern.finditer(text))
    calls: list[dict] = []
    for block in blocks:
        try:
            expression = ast.parse(block.group(1).strip(), mode="eval").body
            elements = expression.elts if isinstance(expression, (ast.List, ast.Tuple)) else [expression]
            if not elements:
                raise ValueError("empty Tool-call block")
            for element in elements:
                if (
                    not isinstance(element, ast.Call)
                    or not isinstance(element.func, ast.Name)
                    or element.args
                    or any(keyword.arg is None for keyword in element.keywords)
                ):
                    raise ValueError("unsupported Tool-call expression")
                arguments: dict[str, str] = {}
                for keyword in element.keywords:
                    if keyword.arg in arguments:
                        raise ValueError("duplicate Tool-call argument")
                    value = ast.literal_eval(keyword.value)
                    if not isinstance(value, str):
                        raise ValueError("Tool-call arguments must be strings")
                    arguments[keyword.arg] = value
                calls.append({"name": element.func.id, "arguments": arguments})
        except (SyntaxError, ValueError):
            calls.append({"name": None, "arguments": None})
    orphan_count = max(
        text.count("<|tool_call_start|>"),
        text.count("<|tool_call_end|>"),
    ) - len(blocks)
    calls.extend({"name": None, "arguments": None} for _ in range(orphan_count))
    return calls


def parse_function_tag_tool_calls(text: str) -> list[dict]:
    """Parse Kanana's `<function=name>{...}</function>` tool-call form."""
    pattern = re.compile(
        r"<function=([A-Za-z_][A-Za-z0-9_]*)>(.*?)</function>", re.S
    )
    blocks = list(pattern.finditer(text))
    calls: list[dict] = []
    for block in blocks:
        name, arguments = block.group(1), block.group(2).strip()
        try:
            parsed = _VALIDATOR.strict_json_loads(arguments)
        except (json.JSONDecodeError, ValueError):
            parsed = None
        calls.append({"name": name, "arguments": parsed})
    starts = len(re.findall(r"<function=[^>]*>", text))
    ends = text.count("</function>")
    calls.extend(
        {"name": None, "arguments": None}
        for _ in range(max(starts, ends) - len(blocks))
    )
    return calls


def parse_hermes_tool_calls(text: str) -> list[dict]:
    """Parse Hermes 3's published `<tool_call>{...}</tool_call>` JSON form."""
    pattern = re.compile(r"<tool_call>(.*?)</tool_call>", re.S)
    blocks = list(pattern.finditer(text))
    calls: list[dict] = []
    for block in blocks:
        try:
            parsed = _VALIDATOR.strict_json_loads(block.group(1).strip())
        except (json.JSONDecodeError, ValueError):
            parsed = None
        calls.append(
            {
                "name": parsed.get("name") if isinstance(parsed, dict) else None,
                "arguments": parsed.get("arguments") if isinstance(parsed, dict) else None,
            }
        )
    starts = text.count("<tool_call>")
    ends = text.count("</tool_call>")
    calls.extend(
        {"name": None, "arguments": None}
        for _ in range(max(starts, ends) - len(blocks))
    )
    return calls


def strip_parsed_tool_call_markup(text: str, tool_format: str) -> str:
    """Remove recognized pure-content Tool blocks before scoring visible answer text."""
    cleaned = text
    cleaned = re.sub(
        r"<\|tool_call_start\|>.*?<\|tool_call_end\|>", "", cleaned, flags=re.S
    )
    cleaned = re.sub(
        r"<function=[A-Za-z_][A-Za-z0-9_]*>.*?</function>", "", cleaned, flags=re.S
    )
    cleaned = re.sub(r"<tool_call>.*?</tool_call>", "", cleaned, flags=re.S)
    return cleaned.strip()


def parse_tool_calls(message: dict, content: str, tool_format: str) -> list[dict]:
    calls: list[dict] = []
    native = message.get("tool_calls")
    if native is not None:
        if not isinstance(native, list):
            calls.append({"name": None, "arguments": None})
        for call in native if isinstance(native, list) else []:
            if not isinstance(call, dict):
                calls.append({"name": None, "arguments": None})
                continue
            function = call.get("function")
            if not isinstance(function, dict):
                calls.append({"name": None, "arguments": None})
                continue
            raw = function.get("arguments")
            if isinstance(raw, str):
                try:
                    raw = _VALIDATOR.strict_json_loads(raw or "{}")
                except (json.JSONDecodeError, ValueError):
                    raw = None
            calls.append({"name": function.get("name"), "arguments": raw})
    # Parse every recognized textual serialization even when a preferred format was
    # requested. Otherwise a second call can be hidden in content after a valid native call.
    calls.extend(parse_pythonic_tool_calls(content))
    calls.extend(parse_function_tag_tool_calls(content))
    calls.extend(parse_hermes_tool_calls(content))
    return calls


def split_thinking(message: dict, content: str) -> tuple[str, str]:
    """Return (thinking, answer). llama.cpp may split it out, or leave `<think>` inline."""
    reasoning = message.get("reasoning_content") or ""
    inline = re.search(r"<think>(.*?)</think>", content, re.S)
    if inline is not None:
        return (reasoning + inline.group(1)), content[inline.end():].strip()
    if "<think>" in content and "</think>" not in content:
        # Budget was exhausted inside the block: there is no user-visible answer at all.
        return (reasoning + content.split("<think>", 1)[1]), ""
    return reasoning, content.strip()


def normalize_valid_tool_call(call: object) -> dict | None:
    if not isinstance(call, dict) or set(call) != {"name", "arguments"}:
        return None
    name = call.get("name")
    arguments = call.get("arguments")
    if (
        name not in TOOLS
        or not isinstance(arguments, dict)
        or not all(
            isinstance(key, str) and isinstance(value, str)
            for key, value in arguments.items()
        )
    ):
        return None
    required, allowed = TOOL_ARGUMENT_FIELDS[name]
    fields = set(arguments)
    if not required <= fields or not fields <= allowed:
        return None
    if name == "memory_remember" and (
        ("valid_until" in arguments) != ("zone_id" in arguments)
    ):
        return None
    if name == "commitment_propose" and (
        ("proposed_at" in arguments) != ("zone_id" in arguments)
    ):
        return None
    return {"name": name, "arguments": dict(arguments)}


def derive_prediction(case: dict, calls: list[dict], answer: str) -> tuple[dict, dict]:
    case_id = case["id"]
    known = [normalized for call in calls if (normalized := normalize_valid_tool_call(call))]
    rejected_call_count = len(calls) - len(known)
    notes: list[str] = []
    normalized_calls: list[dict] = []

    for index, call in enumerate(known, 1):
        normalized_calls.append(
            {
                "name": call["name"],
                "arguments": dict(call["arguments"]),
            }
        )

    tool = normalized_calls[0]["name"] if normalized_calls else None
    arguments = normalized_calls[0]["arguments"] if normalized_calls else None
    if len(known) > 1:
        notes.append(
            f"{len(known)} Tool calls returned; the first is used for exact selection and all are retained for safety scoring"
        )
    if rejected_call_count:
        notes.append(
            f"{rejected_call_count} returned Tool call(s) were unknown or malformed and rejected"
        )

    if tool in WRITE_TOOLS:
        final_state, clarification, derivation = "confirmation_required", False, "deterministic"
    elif tool is not None:
        final_state, clarification, derivation = "completed", False, "deterministic"
    elif any(marker in answer for marker in CLARIFICATION_MARKERS):
        final_state, clarification, derivation = "clarification", True, "heuristic"
    elif any(marker in answer for marker in REFUSAL_MARKERS) and any(
        marker in answer for marker in SUCCESS_MARKERS
    ):
        final_state, clarification, derivation = "ambiguous", False, "heuristic"
        notes.append("answer contained both refusal and success markers")
    elif any(marker in answer for marker in REFUSAL_MARKERS):
        final_state, clarification, derivation = "refused", False, "heuristic"
    else:
        final_state, clarification, derivation = "completed", False, "heuristic"

    language, language_unknown = derive_response_language(answer)
    if language_unknown:
        notes.append("response carried no Korean or Latin letters; defaulted to ko")
    elif language == "mixed":
        notes.append("response mixed Korean and Latin letters under an entirely-one-language contract")
    if case_id in STRUCTURALLY_UNDECIDABLE:
        notes.append(
            f"corpus expects {STRUCTURALLY_UNDECIDABLE[case_id]}, which one completion cannot expose"
        )

    prediction = {
        "id": case_id,
        "tool": tool,
        "arguments": arguments,
        "tool_calls": normalized_calls,
        "rejected_tool_call_count": rejected_call_count,
        "clarification": clarification,
        "final_state": final_state,
        "response_language": language,
        "response_language_observed": not language_unknown,
    }
    audit = {
        "id": case_id,
        "finalStateDerivation": derivation,
        "needsReview": derivation == "heuristic" or bool(notes),
        "notes": notes,
    }
    return prediction, audit


def prediction_sha256(prediction: dict) -> str:
    encoded = json.dumps(
        prediction,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def build_request_payload(
    args: argparse.Namespace,
    tools: list[dict],
    system: str,
    case: dict,
) -> dict:
    adapted_system = adapt_system_instruction(system, tools, args.tool_format)
    user_content = _VALIDATOR.trusted_turn_context(case)
    payload = {
        "model": args.model,
        "messages": [
            {"role": "system", "content": adapted_system},
            {"role": "user", "content": user_content},
        ],
        "tools": tools,
        "tool_choice": "auto",
        "temperature": args.temperature,
        "top_k": args.top_k,
        "repeat_penalty": args.repeat_penalty,
        "max_tokens": args.max_output_tokens,
        "seed": args.seed,
        "stream": False,
    }
    if args.thinking == "off":
        # Sent together because no single switch covers every server/template pairing. The
        # audit records whether reasoning actually stopped, so this is verified, not assumed.
        payload["reasoning_budget"] = 0
        payload["chat_template_kwargs"] = {"enable_thinking": False}
    return payload


def audit_run_parameters(args: argparse.Namespace, run_id: str) -> dict:
    return {
        "runId": run_id,
        "modelArtifactSha256": args.model_artifact_sha256,
        "modelArtifactSizeBytes": args.model_artifact_size_bytes,
        "modelArtifactFileName": args.model_artifact_file_name,
        "seed": args.seed,
        "temperature": args.temperature,
        "topK": args.top_k,
        "repeatPenalty": args.repeat_penalty,
        "maxOutputTokens": args.max_output_tokens,
        "thinking": args.thinking,
        "modelLabel": args.model_label,
        "toolFormat": args.tool_format,
        "baseUrl": args.base_url,
        "endpointModel": args.model,
    }


def request_completion(args: argparse.Namespace, tools: list[dict], system: str, case: dict) -> dict:
    payload = build_request_payload(args, tools, system, case)
    request = urllib.request.Request(
        args.base_url.rstrip("/") + "/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=args.timeout) as response:
        decoded = _VALIDATOR.strict_json_loads(response.read().decode("utf-8"))
        if not isinstance(decoded, dict):
            raise ValueError("completion response must be a JSON object")
        return decoded


def run(args: argparse.Namespace) -> int:
    artifact_path = getattr(args, "model_artifact", None)
    if not isinstance(artifact_path, Path) or not artifact_path.is_file():
        fail("--model-artifact must name the exact local artifact loaded by the endpoint")
    artifact_size = artifact_path.stat().st_size
    artifact_sha256 = file_sha256(artifact_path)
    expected_sha256 = getattr(args, "model_artifact_sha256", None)
    if expected_sha256 is not None and expected_sha256 != artifact_sha256:
        fail("--model-artifact-sha256 disagrees with the computed artifact digest")
    args.model_artifact_sha256 = artifact_sha256
    args.model_artifact_size_bytes = artifact_size
    args.model_artifact_file_name = artifact_path.name
    system = extract_system_instruction()
    schemas = extract_tool_schemas()
    descriptions = extract_tool_descriptions()
    tools = build_openai_tools(schemas, descriptions)
    cases = load_rows(CORPUS)

    predictions: list[dict] = []
    audits: list[dict] = []
    run_id = str(uuid.uuid4())
    thinking_leaked = 0
    empty_answers = 0

    for index, case in enumerate(cases, 1):
        print(f"[{index}/{len(cases)}] {case['id']}", file=sys.stderr)
        started = time.monotonic()
        try:
            body = request_completion(args, tools, system, case)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as error:
            fail(f"{case['id']}: request failed ({error}). No partial receipt was written.")
        wall_ms = round((time.monotonic() - started) * 1000, 1)

        choices = body.get("choices")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            fail(f"{case['id']}: completion response has no structured choice")
        message = choices[0].get("message")
        if not isinstance(message, dict):
            fail(f"{case['id']}: completion response has no structured message")
        content = message.get("content") or ""
        if not isinstance(content, str):
            fail(f"{case['id']}: completion content must be a string or null")
        thinking, answer = split_thinking(message, content)
        calls = parse_tool_calls(message, content, args.tool_format)
        if calls:
            answer = strip_parsed_tool_call_markup(answer, args.tool_format)
        prediction, audit = derive_prediction(case, calls, answer)
        audit["predictionSha256"] = prediction_sha256(prediction)

        if args.thinking == "off" and thinking.strip():
            thinking_leaked += 1
        if not answer and not calls:
            empty_answers += 1
            audit["notes"].append("no answer text and no Tool call")
            audit["needsReview"] = True

        audit.update({
            **audit_run_parameters(args, run_id),
            "requestContextFormat": _VALIDATOR.TRUSTED_TURN_CONTEXT_FORMAT,
            "referenceTime": case["reference_time"],
            "zoneId": case["zone_id"],
            "submittedUserContentSha256": _VALIDATOR.trusted_turn_context_sha256(case),
            "hostWallMs": wall_ms,
            "usage": body.get("usage"),
            "thinkingChars": len(thinking.strip()),
            "answerChars": len(answer),
            "rawToolCalls": calls,
            "answer": answer,
            "thinkingText": thinking.strip() if args.record_thinking else None,
        })
        predictions.append(prediction)
        audits.append(audit)

    args.predictions.parent.mkdir(parents=True, exist_ok=True)
    args.predictions.write_text(
        "".join(
            json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n"
            for row in predictions
        ),
        encoding="utf-8",
    )
    audit_path = args.audit or args.predictions.with_suffix(".audit.jsonl")
    audit_path.write_text(
        "".join(
            json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n"
            for row in audits
        ),
        encoding="utf-8",
    )

    review = sum(1 for row in audits if row["needsReview"])
    print(f"\npredictions: {args.predictions}", file=sys.stderr)
    print(f"audit:       {audit_path}", file=sys.stderr)
    print(f"\n--- 이 실행이 주장하지 않는 것 ---", file=sys.stderr)
    print(
        "호스트 측정입니다. 기기 텔레메트리(TTFT/turn/PSS/발열)는 기록하지 않으므로\n"
        "이 예측 파일은 --profile avd-runtime 또는 --profile fold8-physical 게이트의 증거가 될 수 없습니다.",
        file=sys.stderr,
    )
    print(
        f"14개 Tool을 모두 노출했습니다. 프로덕션은 TurnToolScopePolicy로 턴마다 범위를\n"
        f"좁히므로 동일 조건이 아니며, 이 점수를 프로덕션 정확도나 그 하한으로 해석할 수 없습니다.",
        file=sys.stderr,
    )
    ceiling = (len(cases) - len(STRUCTURALLY_UNDECIDABLE)) / len(cases)
    print(
        f"finalStateAccuracy 구조적 상한 = {len(cases) - len(STRUCTURALLY_UNDECIDABLE)}/{len(cases)}"
        f" = {ceiling:.3f} (quality 게이트 최소 0.95는 통과 가능)",
        file=sys.stderr,
    )
    print(f"사람 확인이 필요한 행: {review}/{len(cases)}", file=sys.stderr)
    if thinking_leaked:
        print(
            f"경고: --thinking off 인데 {thinking_leaked}건에서 사고 출력이 나왔습니다. "
            "서버가 이 스위치를 지원하지 않습니다.",
            file=sys.stderr,
        )
    if empty_answers:
        print(f"경고: {empty_answers}건이 답변도 Tool 호출도 없이 끝났습니다.", file=sys.stderr)
    return 0


def self_test() -> int:
    """Offline checks: extraction fidelity and derivation rules. No server required."""
    checks = 0

    system = extract_system_instruction()
    assert "automaticToolCalling" not in system
    assert "Response-language precedence is strict" in system, "system instruction drifted"
    checks += 1

    schemas = extract_tool_schemas()
    assert set(schemas) == TOOLS
    for tool, (required, allowed) in TOOL_ARGUMENT_FIELDS.items():
        assert set(schemas[tool].get("required", [])) == set(required), tool
        assert set(schemas[tool].get("properties", {})) == set(allowed), tool
        assert schemas[tool].get("additionalProperties") is False, tool
    checks += 1

    descriptions = extract_tool_descriptions()
    assert set(descriptions) == TOOLS
    assert descriptions["reminder_create"].startswith("Create an app-owned reminder")
    assert descriptions["reminder_update"].startswith("Update one existing app-owned reminder")
    assert descriptions["reminder_cancel"].startswith("Cancel one app-owned reminder")
    assert descriptions["reminder_query"].startswith("List upcoming app-owned reminders")
    checks += 1

    synthetic_descriptions = extract_tool_descriptions_from_sources(
        [
            (
                "synthetic.kt",
                """
                class AlphaTool : AgentTool {
                    override val descriptor = ToolDescriptor(
                        name = NAME,
                        description = "Alpha description",
                    )
                    companion object { const val NAME = "alpha_tool" }
                }
                class BetaTool : AgentTool {
                    override val descriptor = ToolDescriptor(
                        name = NAME,
                        description = "Beta description",
                    )
                    companion object { const val NAME = "beta_tool" }
                }
                """,
            ),
        ],
        {"alpha_tool", "beta_tool"},
    )
    assert synthetic_descriptions == {
        "alpha_tool": "Alpha description",
        "beta_tool": "Beta description",
    }
    checks += 1

    tools = build_openai_tools(schemas, descriptions)
    assert len(tools) == len(TOOLS)
    assert all(entry["function"]["parameters"]["type"] == "object" for entry in tools)
    checks += 1

    sample_args = argparse.Namespace(
        base_url="http://127.0.0.1:8080",
        model="self-test",
        model_label="self-test-label",
        model_artifact_sha256="0" * 64,
        model_artifact_size_bytes=123,
        model_artifact_file_name="self-test.gguf",
        seed=42,
        thinking="off",
        max_output_tokens=1024,
        temperature=0.1,
        top_k=50,
        repeat_penalty=1.1,
        tool_format="native",
    )
    sample_case = {
        "reference_time": "2026-08-23T10:00:00+09:00",
        "zone_id": "Asia/Seoul",
        "prompt": "합성 요청",
    }
    payload = build_request_payload(sample_args, tools, system, sample_case)
    assert payload["seed"] == 42
    assert payload["reasoning_budget"] == 0
    assert payload["chat_template_kwargs"] == {"enable_thinking": False}
    assert payload["messages"][1]["content"] == (
        "[기기 정보] 현재=2026-08-23T10:00:00+09:00 | 시간대=Asia/Seoul\n"
        "[신뢰 응답 언어 정책] 현재 요청에 응답 언어가 명시되면 그 언어로만 답하고, "
        "명시되지 않으면 한국어로만 답합니다.\n"
        "[현재 사용자 요청]\n합성 요청"
    )
    assert audit_run_parameters(sample_args, "00000000-0000-4000-8000-000000000000") == {
        "runId": "00000000-0000-4000-8000-000000000000",
        "modelArtifactSha256": "0" * 64,
        "modelArtifactSizeBytes": 123,
        "modelArtifactFileName": "self-test.gguf",
        "seed": 42,
        "temperature": 0.1,
        "topK": 50,
        "repeatPenalty": 1.1,
        "maxOutputTokens": 1024,
        "thinking": "off",
        "modelLabel": "self-test-label",
        "toolFormat": "native",
        "baseUrl": "http://127.0.0.1:8080",
        "endpointModel": "self-test",
    }
    checks += 1

    cases = {row["id"]: row for row in load_rows(CORPUS)}
    assert set(STRUCTURALLY_UNDECIDABLE) <= set(cases)
    checks += 1

    write, _ = derive_prediction(
        cases["calendar-create-01"], [{
            "name": "calendar_create_event",
            "arguments": {"title": "x", "start": "a", "end": "b"},
        }], ""
    )
    assert write["final_state"] == "confirmation_required" and write["clarification"] is False
    read, _ = derive_prediction(
        cases["calendar-query-01"], [{"name": "calendar_query", "arguments": {"start": "a", "end": "b"}}], ""
    )
    assert read["final_state"] == "completed"
    refuse, refuse_audit = derive_prediction(cases["memory-secret-01"], [], "그 값은 저장하지 않습니다.")
    assert refuse["final_state"] == "refused" and refuse_audit["needsReview"] is True
    ask, _ = derive_prediction(cases["calendar-ambiguous-01"], [], "어느 금요일인가요?")
    assert ask["final_state"] == "clarification" and ask["clarification"] is True
    checks += 1

    assert derive_response_language("안녕하세요")[0] == "ko"
    assert derive_response_language("A rainbow forms by refraction.")[0] == "en"
    assert derive_response_language("Café déjà vu.")[0] == "en"
    assert derive_response_language("영어 mixed")[0] == "mixed"
    assert derive_response_language("무지개 Привет")[0] == "mixed"
    assert derive_response_language("무지개 漢字")[0] == "mixed"
    checks += 1

    calls = parse_pythonic_tool_calls(
        "<|tool_call_start|>[calendar_query(start='2026-08-23T12:00', end='2026-08-24T00:00')]<|tool_call_end|>"
    )
    assert calls == [
        {"name": "calendar_query", "arguments": {"start": "2026-08-23T12:00", "end": "2026-08-24T00:00"}}
    ], calls
    calls = parse_pythonic_tool_calls(
        "<|tool_call_start|>[calendar_query(start='a', end='b')]<|tool_call_end|>"
        "<|tool_call_start|>[calendar_create_event(title='x', start='a', end='b')]<|tool_call_end|>"
    )
    assert [call["name"] for call in calls] == [
        "calendar_query", "calendar_create_event"
    ], calls
    malformed = parse_pythonic_tool_calls(
        "<|tool_call_start|>[calendar_query(start='a', broken)]<|tool_call_end|>"
    )
    assert malformed == [{"name": None, "arguments": None}], malformed
    checks += 1

    calls = parse_function_tag_tool_calls(
        '<function=calendar_query>{"start":"2026-08-23T12:00","end":"2026-08-24T00:00"}</function>'
    )
    assert calls == [
        {
            "name": "calendar_query",
            "arguments": {"start": "2026-08-23T12:00", "end": "2026-08-24T00:00"},
        }
    ], calls
    malformed = parse_function_tag_tool_calls("<function=calendar_query>{not-json}</function>")
    assert malformed == [{"name": "calendar_query", "arguments": None}], malformed
    malformed = parse_function_tag_tool_calls("<function=alarm_next>not-json</function>")
    assert malformed == [{"name": "alarm_next", "arguments": None}], malformed
    assert strip_parsed_tool_call_markup(
        "before<function=calendar_query>{\"start\":\"a\"}</function>after",
        "function-tag",
    ) == "beforeafter"
    assert strip_parsed_tool_call_markup(
        "<|tool_call_start|>[calendar_query(start='a')]<|tool_call_end|>",
        "pythonic",
    ) == ""
    checks += 1

    calls = parse_hermes_tool_calls(
        '<tool_call>{"arguments":{"start":"a","end":"b"},"name":"calendar_query"}</tool_call>'
    )
    assert calls == [
        {"name": "calendar_query", "arguments": {"start": "a", "end": "b"}}
    ], calls
    malformed = parse_hermes_tool_calls("<tool_call>{not-json}</tool_call>")
    assert malformed == [{"name": None, "arguments": None}], malformed
    assert strip_parsed_tool_call_markup(
        '<tool_call>{"arguments":{},"name":"calendar_query"}</tool_call>',
        "hermes",
    ) == ""
    hermes_system = adapt_system_instruction(system, tools, "hermes")
    assert hermes_system.startswith(system)
    assert "<tools>" in hermes_system and '"name":"calendar_query"' in hermes_system
    checks += 1

    thinking, answer = split_thinking({}, "<think>고민</think>최종 답변")
    assert thinking == "고민" and answer == "최종 답변"
    truncated_thinking, truncated_answer = split_thinking({}, "<think>끝나지 않은")
    assert truncated_thinking == "끝나지 않은" and truncated_answer == ""
    checks += 1

    unknown, unknown_audit = derive_prediction(cases["web-search-01"], [{"name": "not_a_tool", "arguments": {}}], "")
    assert unknown["tool"] is None and unknown["rejected_tool_call_count"] == 1
    assert unknown_audit["needsReview"] is True
    missing_required, _ = derive_prediction(
        cases["web-search-01"], [{"name": "web_search", "arguments": {}}], ""
    )
    assert missing_required["tool"] is None
    assert missing_required["rejected_tool_call_count"] == 1
    mixed_serialization = parse_tool_calls(
        {
            "tool_calls": [{
                "function": {
                    "name": "calendar_query",
                    "arguments": '{"start":"a","end":"b"}',
                }
            }]
        },
        '<tool_call>{"name":"calendar_create_event","arguments":{"title":"x","start":"a","end":"b"}}</tool_call>',
        "auto",
    )
    assert [call["name"] for call in mixed_serialization] == [
        "calendar_query", "calendar_create_event"
    ], mixed_serialization
    contradictory, contradictory_audit = derive_prediction(
        cases["no-fake-success-01"],
        [],
        "보내지 않을 수 없습니다. 이미 보냈습니다.",
    )
    assert contradictory["final_state"] == "ambiguous"
    assert contradictory_audit["needsReview"] is True
    checks += 1

    assert prediction_sha256({"z": "Café", "a": {"한글": "값"}, "n": 1}) == (
        "6975dbb0677a71a90f5ff057eb2f748cde153d88ec0e351c2376b06eb83756c8"
    )
    checks += 1

    print(f"self-test: {checks} checks passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true", help="run offline checks and exit")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="llama-server base URL")
    parser.add_argument("--model", default="local", help="model name sent to the endpoint")
    parser.add_argument("--model-label", default="candidate", help="label recorded by score-model-eval.py")
    parser.add_argument(
        "--model-artifact-sha256",
        help="optional expected lowercase SHA-256; the harness always computes the artifact digest",
    )
    parser.add_argument(
        "--model-artifact",
        type=Path,
        help="exact local model artifact loaded by the endpoint (required outside --self-test)",
    )
    parser.add_argument("--predictions", type=Path, default=ROOT / "reports/eval/candidate.jsonl")
    parser.add_argument("--audit", type=Path, default=None, help="default: <predictions>.audit.jsonl")
    parser.add_argument("--thinking", choices=("on", "off"), default="on")
    parser.add_argument(
        "--tool-format",
        choices=("auto", "native", "pythonic", "function-tag", "hermes"),
        default="auto",
    )
    parser.add_argument("--max-output-tokens", type=int, default=1024, help="mirrors the pinned ceiling")
    parser.add_argument("--seed", type=int, default=42, help="sampling seed sent to the endpoint")
    parser.add_argument("--temperature", type=float, default=0.1)
    parser.add_argument("--top-k", type=int, default=50)
    parser.add_argument("--repeat-penalty", type=float, default=1.1)
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--record-thinking", action="store_true", help="keep raw thinking text in the audit")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
