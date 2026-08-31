#!/usr/bin/env python3
"""Score content-minimal JSONL model receipts against the fixed corpus."""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import math
import re
import sys
import unicodedata
import uuid
from pathlib import Path

_VALIDATOR_PATH = Path(__file__).with_name("validate-model-eval-corpus.py")
_VALIDATOR_SPEC = importlib.util.spec_from_file_location("personal_edge_eval_validator", _VALIDATOR_PATH)
if _VALIDATOR_SPEC is None or _VALIDATOR_SPEC.loader is None:
    raise RuntimeError("model evaluation validator is unavailable")
_VALIDATOR = importlib.util.module_from_spec(_VALIDATOR_SPEC)
_VALIDATOR_SPEC.loader.exec_module(_VALIDATOR)
TOOLS = _VALIDATOR.TOOLS
WRITE_TOOLS = _VALIDATOR.WRITE_TOOLS
TOOL_ARGUMENT_FIELDS = _VALIDATOR.TOOL_ARGUMENT_FIELDS
load_rows = _VALIDATOR.load_rows
validate = _VALIDATOR.validate


PREDICTION_KEYS = {
    "id", "tool", "arguments", "tool_calls", "rejected_tool_call_count",
    "clarification", "final_state", "response_language", "response_language_observed",
    "ttft_ms", "turn_ms",
    "pss_mb", "battery_delta_percent", "max_thermal", "fold_transition_ok",
    "cancel_recovered", "device_run",
}
THERMAL_ORDER = {"NONE": 0, "LIGHT": 1, "MODERATE": 2, "SEVERE": 3, "CRITICAL": 4, "EMERGENCY": 5, "SHUTDOWN": 6}
NUMERIC_TELEMETRY_KEYS = {
    "ttft_ms", "turn_ms", "pss_mb", "battery_delta_percent",
}
BOOLEAN_TELEMETRY_KEYS = {"fold_transition_ok", "cancel_recovered"}
TELEMETRY_KEYS = NUMERIC_TELEMETRY_KEYS | BOOLEAN_TELEMETRY_KEYS | {"max_thermal"}
DEVICE_RUN_KEYS = {
    "environment", "deviceManufacturer", "deviceModel", "deviceSerialSha256",
    "androidBuildFingerprintSha256", "inferenceBackend", "liteRtLmVersion",
    "applicationId", "buildType", "sourceStateSha256", "appApkSha256",
    "testApkSha256", "appSigningCertificateSha256",
    "modelArtifactSha256", "runId",
}
DEVICE_SHA256_KEYS = {
    "deviceSerialSha256", "androidBuildFingerprintSha256", "sourceStateSha256",
    "appApkSha256", "testApkSha256", "appSigningCertificateSha256",
    "modelArtifactSha256",
}
DEVICE_ENVIRONMENTS = {"android-emulator", "android-physical"}
DEVICE_BACKENDS = {"CPU", "GPU"}
PREDICTION_RESPONSE_LANGUAGES = _VALIDATOR.RESPONSE_LANGUAGES | {"mixed"}
HANGUL_RANGES = ((0xAC00, 0xD7A3), (0x1100, 0x11FF), (0x3130, 0x318F))
AUDIT_TOOL_FORMATS = {"auto", "native", "pythonic", "function-tag", "hermes"}
AUDIT_RUN_PARAMETER_KEYS = {
    "runId", "modelArtifactSha256", "modelArtifactSizeBytes", "modelArtifactFileName",
    "seed", "temperature", "topK",
    "repeatPenalty", "maxOutputTokens", "thinking", "modelLabel", "toolFormat",
    "baseUrl", "endpointModel",
}
AUDIT_CONTEXT_KEYS = {
    "requestContextFormat", "referenceTime", "zoneId", "submittedUserContentSha256",
}


def ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 6) if denominator else None


def average(values: list[float]) -> float | None:
    return round(sum(values) / len(values), 3) if values else None


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile_value
    lower = math.floor(position)
    upper = math.ceil(position)
    value = ordered[lower] if lower == upper else ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)
    return round(value, 3)


def finite_non_negative_number(value: object) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        number = float(value)
    except OverflowError:
        return None
    return number if math.isfinite(number) and number >= 0 else None


def numeric(receipt: dict, key: str) -> float | None:
    return finite_non_negative_number(receipt.get(key))


def prediction_sha256(receipt: dict) -> str:
    encoded = json.dumps(
        receipt,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


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
    return {
        "name": name,
        "arguments": dict(arguments),
    }


def derive_response_language(text: str) -> tuple[str, bool]:
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
    if hangul > 0 and latin == 0 and other_letters == 0:
        return "ko", True
    if latin > 0 and hangul == 0 and other_letters == 0:
        return "en", True
    if hangul + latin + other_letters > 0:
        return "mixed", True
    return "ko", False


def validate_telemetry(case_id: str, receipt: dict) -> None:
    for key in NUMERIC_TELEMETRY_KEYS:
        if key not in receipt:
            continue
        value = receipt[key]
        if finite_non_negative_number(value) is None:
            raise ValueError(f"{case_id}: {key} must be a finite non-negative number")
    if "max_thermal" in receipt and receipt["max_thermal"] not in THERMAL_ORDER:
        raise ValueError(
            f"{case_id}: max_thermal must be one of {sorted(THERMAL_ORDER)}"
        )
    for key in BOOLEAN_TELEMETRY_KEYS:
        if key in receipt and not isinstance(receipt[key], bool):
            raise ValueError(f"{case_id}: {key} must be boolean")


def audit_run_binding(case_id: str, row: dict, model_label: str) -> dict:
    binding = {key: row.get(key) for key in AUDIT_RUN_PARAMETER_KEYS}
    if any(key not in row for key in AUDIT_RUN_PARAMETER_KEYS):
        raise ValueError(f"{case_id}: audit is missing the exact run binding")
    try:
        parsed_run_id = uuid.UUID(binding["runId"])
    except (AttributeError, TypeError, ValueError):
        raise ValueError(f"{case_id}: audit runId must be a UUID") from None
    if str(parsed_run_id) != binding["runId"]:
        raise ValueError(f"{case_id}: audit runId must use canonical lowercase UUID form")
    if not isinstance(binding["modelArtifactSha256"], str) or re.fullmatch(
        r"[0-9a-f]{64}", binding["modelArtifactSha256"]
    ) is None:
        raise ValueError(f"{case_id}: audit modelArtifactSha256 must be lowercase SHA-256")
    artifact_size = binding["modelArtifactSizeBytes"]
    if isinstance(artifact_size, bool) or not isinstance(artifact_size, int) or artifact_size < 0:
        raise ValueError(f"{case_id}: audit modelArtifactSizeBytes must be a non-negative integer")
    artifact_name = binding["modelArtifactFileName"]
    if (
        not isinstance(artifact_name, str)
        or not 1 <= len(artifact_name) <= 256
        or artifact_name in {".", ".."}
        or any(character in artifact_name for character in "/\\\r\n\x00")
    ):
        raise ValueError(f"{case_id}: audit modelArtifactFileName must be a safe basename")
    if isinstance(binding["seed"], bool) or not isinstance(binding["seed"], int):
        raise ValueError(f"{case_id}: audit seed must be an integer")
    for key in ("temperature", "repeatPenalty"):
        value = binding[key]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValueError(f"{case_id}: audit {key} must be finite")
    if binding["temperature"] < 0 or binding["repeatPenalty"] <= 0:
        raise ValueError(f"{case_id}: audit sampling values are out of range")
    for key in ("topK", "maxOutputTokens"):
        value = binding[key]
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ValueError(f"{case_id}: audit {key} must be a positive integer")
    if binding["thinking"] not in {"on", "off"}:
        raise ValueError(f"{case_id}: audit thinking mode is invalid")
    if binding["toolFormat"] not in AUDIT_TOOL_FORMATS:
        raise ValueError(f"{case_id}: audit Tool format is invalid")
    for key in ("modelLabel", "baseUrl", "endpointModel"):
        value = binding[key]
        if not isinstance(value, str) or not 1 <= len(value) <= 256 or any(
            character in value for character in "\r\n\x00"
        ):
            raise ValueError(f"{case_id}: audit {key} must be a bounded string")
    if binding["modelLabel"] != model_label:
        raise ValueError(f"{case_id}: audit modelLabel disagrees with scorer label")
    if re.fullmatch(r"http://127\.0\.0\.1:[0-9]{1,5}", binding["baseUrl"]) is None:
        raise ValueError(f"{case_id}: audit baseUrl must be an explicit loopback endpoint")
    port = int(binding["baseUrl"].rsplit(":", 1)[1])
    if not 1 <= port <= 65535:
        raise ValueError(f"{case_id}: audit baseUrl port is invalid")
    return binding


def validate_audit_context(case_id: str, row: dict, case: dict) -> None:
    if any(key not in row for key in AUDIT_CONTEXT_KEYS):
        raise ValueError(f"{case_id}: audit is missing the submitted trusted turn context")
    if row["requestContextFormat"] != _VALIDATOR.TRUSTED_TURN_CONTEXT_FORMAT:
        raise ValueError(f"{case_id}: audit request context format is invalid")
    if row["referenceTime"] != case["reference_time"]:
        raise ValueError(f"{case_id}: audit reference time disagrees with corpus")
    if row["zoneId"] != case["zone_id"]:
        raise ValueError(f"{case_id}: audit zone id disagrees with corpus")
    digest = row["submittedUserContentSha256"]
    if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise ValueError(f"{case_id}: audit submitted user content digest must be lowercase SHA-256")
    if digest != _VALIDATOR.trusted_turn_context_sha256(case):
        raise ValueError(f"{case_id}: audit submitted user content disagrees with corpus context")


def load_audit_evidence(
    audit_path: Path,
    cases: dict[str, dict],
    model_label: str,
) -> dict[str, dict]:
    rows = load_rows(audit_path)
    result: dict[str, dict] = {}
    shared_run_binding: dict | None = None
    for row in rows:
        case_id = row.get("id")
        if not isinstance(case_id, str) or case_id not in cases or case_id in result:
            raise ValueError("audit ids must be unique corpus ids")
        validate_audit_context(case_id, row, cases[case_id])
        raw_calls = row.get("rawToolCalls")
        if not isinstance(raw_calls, list):
            raise ValueError(f"{case_id}: audit rawToolCalls must be a list")
        calls: list[dict] = []
        rejected_call_count = 0
        for call in raw_calls:
            normalized = normalize_valid_tool_call(call)
            if normalized is not None:
                calls.append(normalized)
            else:
                rejected_call_count += 1
        needs_review = row.get("needsReview")
        if not isinstance(needs_review, bool):
            raise ValueError(f"{case_id}: audit needsReview must be boolean")
        reviewed = row.get("reviewed", False)
        if not isinstance(reviewed, bool):
            raise ValueError(f"{case_id}: audit reviewed must be boolean when present")
        answer_chars = row.get("answerChars")
        if isinstance(answer_chars, bool) or not isinstance(answer_chars, int) or answer_chars < 0:
            raise ValueError(f"{case_id}: audit answerChars must be a non-negative integer")
        answer = row.get("answer")
        if not isinstance(answer, str) or len(answer) != answer_chars:
            raise ValueError(f"{case_id}: audit answer must match answerChars exactly")
        language, language_observed = derive_response_language(answer)
        digest = row.get("predictionSha256")
        if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise ValueError(f"{case_id}: audit predictionSha256 must be lowercase SHA-256")
        run_binding = audit_run_binding(case_id, row, model_label)
        if shared_run_binding is None:
            shared_run_binding = run_binding
        elif run_binding != shared_run_binding:
            raise ValueError("audit rows must share one exact run binding")
        result[case_id] = {
            "calls": calls,
            "rejectedCallCount": rejected_call_count,
            "needsReview": needs_review,
            "reviewed": reviewed,
            "answerObserved": language_observed,
            "responseLanguage": language,
            "predictionSha256": digest,
            "runBinding": run_binding,
        }
    missing = set(cases) - set(result)
    if missing:
        raise ValueError(f"audit is missing cases: {sorted(missing)}")
    return result


def validate_device_run(case_id: str, receipt: dict) -> dict | None:
    has_telemetry = any(key in receipt for key in TELEMETRY_KEYS)
    binding = receipt.get("device_run")
    if not has_telemetry:
        if binding is not None:
            raise ValueError(f"{case_id}: device_run requires telemetry")
        return None
    if not isinstance(binding, dict) or set(binding) != DEVICE_RUN_KEYS:
        raise ValueError(f"{case_id}: telemetry requires an exact device_run binding")
    for key, value in binding.items():
        if not isinstance(value, str) or not 1 <= len(value) <= 256 or any(
            character in value for character in "\r\n\x00"
        ):
            raise ValueError(f"{case_id}: device_run.{key} must be a bounded single-line string")
    for key in DEVICE_SHA256_KEYS:
        if not re.fullmatch(r"[0-9a-f]{64}", binding[key]):
            raise ValueError(f"{case_id}: device_run.{key} must be lowercase SHA-256")
    if binding["environment"] not in DEVICE_ENVIRONMENTS:
        raise ValueError(
            f"{case_id}: device_run.environment must be one of "
            f"{sorted(DEVICE_ENVIRONMENTS)}"
        )
    if binding["inferenceBackend"] not in DEVICE_BACKENDS:
        raise ValueError(
            f"{case_id}: device_run.inferenceBackend must be one of {sorted(DEVICE_BACKENDS)}"
        )
    if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", binding["liteRtLmVersion"]) is None:
        raise ValueError(f"{case_id}: device_run.liteRtLmVersion must be an exact version")
    return binding


def score(
    corpus_path: Path,
    prediction_path: Path,
    model_label: str,
    audit_path: Path | None = None,
) -> dict:
    # The scorer's stdout is a machine-readable JSON artifact; validator status must not prefix it.
    with contextlib.redirect_stdout(io.StringIO()):
        validate(corpus_path)
    cases = {row["id"]: row for row in load_rows(corpus_path)}
    prediction_rows = load_rows(prediction_path)
    predictions: dict[str, dict] = {}
    prediction_tool_names: dict[str, list[str]] = {}
    prediction_rejected_call_counts: dict[str, int] = {}
    prediction_language_observed: dict[str, bool] = {}
    prediction_all_calls_explicit: dict[str, bool] = {}
    device_bindings: list[dict] = []
    explicit_all_calls = True
    for receipt in prediction_rows:
        if set(receipt) - PREDICTION_KEYS:
            raise ValueError(f"{receipt.get('id', '?')}: unknown prediction keys")
        case_id = receipt.get("id")
        if not isinstance(case_id, str) or case_id not in cases or case_id in predictions:
            raise ValueError("prediction ids must be unique corpus ids")
        tool = receipt.get("tool")
        if tool is not None and tool not in TOOLS:
            raise ValueError(f"{case_id}: unknown predicted tool")
        if tool is not None and not isinstance(receipt.get("arguments"), dict):
            raise ValueError(f"{case_id}: selected tools require an arguments object")
        if tool is None and receipt.get("arguments") is not None:
            raise ValueError(f"{case_id}: no-tool predictions require null arguments")
        if tool is not None and normalize_valid_tool_call({
            "name": tool,
            "arguments": receipt.get("arguments"),
        }) is None:
            raise ValueError(f"{case_id}: primary Tool arguments violate the production schema")
        raw_tool_calls = receipt.get("tool_calls")
        rejected_call_count = receipt.get("rejected_tool_call_count")
        rejected_count_explicit = not (
            isinstance(rejected_call_count, bool)
            or not isinstance(rejected_call_count, int)
            or rejected_call_count < 0
        )
        if not rejected_count_explicit:
            explicit_all_calls = False
            rejected_call_count = 0
        if raw_tool_calls is None:
            explicit_all_calls = False
            tool_names = [tool] if tool is not None else []
        else:
            if not isinstance(raw_tool_calls, list):
                raise ValueError(f"{case_id}: tool_calls must be a list")
            tool_names = []
            for call in raw_tool_calls:
                normalized = normalize_valid_tool_call(call)
                if normalized is None:
                    raise ValueError(
                        f"{case_id}: tool_calls entries must satisfy the production Tool schema"
                    )
                tool_names.append(normalized["name"])
            expected_primary = tool_names[0] if tool_names else None
            if tool != expected_primary:
                raise ValueError(f"{case_id}: primary tool disagrees with tool_calls")
            if tool is not None and receipt["arguments"] != raw_tool_calls[0]["arguments"]:
                raise ValueError(f"{case_id}: primary arguments disagree with tool_calls")
        if not isinstance(receipt.get("clarification"), bool):
            raise ValueError(f"{case_id}: clarification must be boolean")
        if not isinstance(receipt.get("final_state"), str):
            raise ValueError(f"{case_id}: final_state must be a string")
        if receipt.get("response_language") not in PREDICTION_RESPONSE_LANGUAGES:
            raise ValueError(
                f"{case_id}: response_language must be one of "
                f"{sorted(PREDICTION_RESPONSE_LANGUAGES)}"
            )
        language_observed = receipt.get("response_language_observed")
        if not isinstance(language_observed, bool):
            language_observed = False
        validate_telemetry(case_id, receipt)
        binding = validate_device_run(case_id, receipt)
        if binding is not None:
            device_bindings.append(binding)
        predictions[case_id] = receipt
        prediction_tool_names[case_id] = tool_names
        prediction_rejected_call_counts[case_id] = rejected_call_count
        prediction_language_observed[case_id] = language_observed
        prediction_all_calls_explicit[case_id] = (
            raw_tool_calls is not None and rejected_count_explicit
        )
    missing = set(cases) - set(predictions)
    if missing:
        raise ValueError(f"missing predictions: {sorted(missing)}")

    if device_bindings and any(binding != device_bindings[0] for binding in device_bindings[1:]):
        raise ValueError("telemetry rows must share one exact device_run binding")

    if audit_path is not None:
        audit_evidence = load_audit_evidence(audit_path, cases, model_label)
        evaluation_run_binding = next(iter(audit_evidence.values()))["runBinding"]
        all_tool_names: dict[str, list[str]] = {}
        for case_id, evidence in audit_evidence.items():
            prediction = predictions[case_id]
            if not prediction_all_calls_explicit[case_id]:
                raise ValueError(f"{case_id}: audited predictions require explicit all-call coverage")
            if evidence["predictionSha256"] != prediction_sha256(prediction):
                raise ValueError(f"{case_id}: prediction digest disagrees with audit")
            audit_calls = evidence["calls"]
            prediction_calls = prediction.get("tool_calls")
            if prediction_calls is not None and prediction_calls != audit_calls:
                raise ValueError(f"{case_id}: prediction Tool calls disagree with audit")
            expected_primary = audit_calls[0] if audit_calls else None
            if prediction["tool"] != (expected_primary or {}).get("name"):
                raise ValueError(f"{case_id}: primary Tool disagrees with audit")
            if prediction["arguments"] != (expected_primary or {}).get("arguments"):
                raise ValueError(f"{case_id}: primary arguments disagree with audit")
            if prediction_rejected_call_counts[case_id] != evidence["rejectedCallCount"]:
                raise ValueError(f"{case_id}: rejected Tool-call count disagrees with audit")
            if prediction["response_language"] != evidence["responseLanguage"]:
                raise ValueError(f"{case_id}: response language disagrees with audit")
            if prediction_language_observed[case_id] != evidence["answerObserved"]:
                raise ValueError(f"{case_id}: response-language coverage disagrees with audit")
            all_tool_names[case_id] = [call["name"] for call in audit_calls]
        rejected_tool_call_count = sum(
            evidence["rejectedCallCount"] for evidence in audit_evidence.values()
        )
        language_observed_by_case = {
            case_id: evidence["answerObserved"]
            for case_id, evidence in audit_evidence.items()
        }
        # Promotion review covers the entire fixed corpus. Trusting an audit-supplied
        # needsReview flag would let a receipt erase its own review obligation.
        review_required = len(cases)
        review_completed = sum(evidence["reviewed"] for evidence in audit_evidence.values())
        review_source = (
            "audit-attested" if review_completed == review_required else "audit-unreviewed"
        )
        tool_call_source = "audit-all-calls"
    else:
        evaluation_run_binding = None
        all_tool_names = prediction_tool_names
        rejected_tool_call_count = sum(prediction_rejected_call_counts.values())
        language_observed_by_case = prediction_language_observed
        review_required = len(cases)
        review_completed = 0
        review_source = "not-provided"
        tool_call_source = "prediction-all-calls" if explicit_all_calls else "primary-only"

    selection_correct = 0
    unnecessary = 0
    expected_no_tool = 0
    state_changing_tool_miscalled = 0
    argument_correct = 0
    argument_total = 0
    datetime_correct = 0
    datetime_total = 0
    clarification_correct = 0
    final_state_correct = 0
    response_language_correct = 0
    response_language_eligible = 0
    response_language_observed_count = 0
    failures: list[dict] = []
    datetime_keys = {"start", "end", "time", "trigger_at", "proposed_at", "zone_id"}

    for case_id, case in cases.items():
        expected = case["expected"]
        actual = predictions[case_id]
        actual_tool_names = all_tool_names[case_id]
        tool_match = actual["tool"] == expected["tool"]
        selection_correct += int(tool_match)
        if expected["tool"] is None:
            expected_no_tool += 1
            unnecessary += int(bool(actual_tool_names))
        actual_write_names = [name for name in actual_tool_names if name in WRITE_TOOLS]
        allowed_write_count = int(expected["tool"] in WRITE_TOOLS)
        state_changing_tool_miscalled += int(
            any(name != expected["tool"] for name in actual_write_names) or
            len(actual_write_names) > allowed_write_count
        )
        if expected["tool"] is not None:
            argument_total += 1
            argument_match = tool_match and actual["arguments"] == expected["arguments"]
            argument_correct += int(argument_match)
            expected_datetime = {
                key: value for key, value in expected["arguments"].items() if key in datetime_keys
            }
            if expected_datetime:
                datetime_total += 1
                actual_arguments = actual["arguments"] if isinstance(actual["arguments"], dict) else {}
                datetime_correct += int(tool_match and all(actual_arguments.get(key) == value for key, value in expected_datetime.items()))
        clarification_match = actual["clarification"] == expected["clarification"]
        final_match = actual["final_state"] == expected["final_state"]
        language_eligible = expected["tool"] is None
        language_observed = language_observed_by_case[case_id]
        language_match = (
            language_eligible and language_observed and
            actual["response_language"] == expected["response_language"]
        )
        clarification_correct += int(clarification_match)
        final_state_correct += int(final_match)
        if language_eligible:
            response_language_eligible += 1
            response_language_observed_count += int(language_observed)
            response_language_correct += int(language_match)
        if not (
            tool_match and clarification_match and final_match and
            (not language_eligible or language_match)
        ):
            failures.append({
                "id": case_id,
                "expectedTool": expected["tool"],
                "actualTool": actual["tool"],
                "actualTools": actual_tool_names,
                "expectedResponseLanguage": expected["response_language"],
                "actualResponseLanguage": actual["response_language"],
            })

    ttft = [value for row in predictions.values() if (value := numeric(row, "ttft_ms")) is not None]
    turn = [value for row in predictions.values() if (value := numeric(row, "turn_ms")) is not None]
    pss = [value for row in predictions.values() if (value := numeric(row, "pss_mb")) is not None]
    battery = [value for row in predictions.values() if (value := numeric(row, "battery_delta_percent")) is not None]
    thermal = [row.get("max_thermal") for row in predictions.values() if row.get("max_thermal") in THERMAL_ORDER]
    fold = [row["fold_transition_ok"] for row in predictions.values() if isinstance(row.get("fold_transition_ok"), bool)]
    cancel = [row["cancel_recovered"] for row in predictions.values() if isinstance(row.get("cancel_recovered"), bool)]
    turn_core = [
        row
        for row in predictions.values()
        if numeric(row, "ttft_ms") is not None and
        numeric(row, "turn_ms") is not None and
        row.get("max_thermal") in THERMAL_ORDER
    ]
    telemetry_case_count = sum(
        any(key in row for key in TELEMETRY_KEYS) for row in predictions.values()
    )
    total = len(cases)
    return {
        "schemaVersion": 3,
        "modelLabel": model_label,
        "caseCount": total,
        "toolCallCoverage": {
            "source": tool_call_source,
            "caseCount": len(all_tool_names),
            "multiToolCaseCount": sum(len(names) > 1 for names in all_tool_names.values()),
            "rejectedToolCallCount": rejected_tool_call_count,
        },
        "reviewCoverage": {
            "source": review_source,
            "requiredCaseCount": review_required,
            "reviewedCaseCount": review_completed,
        },
        "languageEvidence": {
            "eligibleCaseCount": response_language_eligible,
            "observedCaseCount": response_language_observed_count,
        },
        "evaluationRunBinding": evaluation_run_binding,
        "quality": {
            "toolSelectionAccuracy": ratio(selection_correct, total),
            "unnecessaryToolCallRate": ratio(unnecessary, expected_no_tool),
            "stateChangingToolMiscalledRate": ratio(state_changing_tool_miscalled, total),
            "argumentExactMatch": ratio(argument_correct, argument_total),
            "dateTimeExactMatch": ratio(datetime_correct, datetime_total),
            "clarificationAccuracy": ratio(clarification_correct, total),
            "finalStateAccuracy": ratio(final_state_correct, total),
            "responseLanguageAccuracy": ratio(
                response_language_correct,
                response_language_eligible,
            ),
        },
        "deviceRunBinding": (
            {**device_bindings[0], "telemetryCaseCount": telemetry_case_count}
            if device_bindings else None
        ),
        "device": {
            "ttftMillisAverage": average(ttft),
            "ttftMillisP95": percentile(ttft, 0.95),
            "turnMillisAverage": average(turn),
            "turnMillisP95": percentile(turn, 0.95),
            "pssMbMaximum": round(max(pss), 3) if pss else None,
            "batteryDeltaPercentAverage": average(battery),
            "maximumThermal": max(thermal, key=THERMAL_ORDER.get) if thermal else None,
            "foldTransitionSuccessRate": ratio(sum(fold), len(fold)),
            "cancelRecoveryRate": ratio(sum(cancel), len(cancel)),
        },
        "telemetryCoverage": {
            "ttft": len(ttft), "turn": len(turn), "pss": len(pss), "battery": len(battery),
            "thermal": len(thermal), "fold": len(fold), "cancel": len(cancel),
            "turnCore": len(turn_core),
        },
        "mismatches": failures,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, default=Path(__file__).resolve().parents[1] / "models/eval/korean-tool-use-v1.jsonl")
    parser.add_argument("--predictions", required=True, type=Path)
    parser.add_argument("--model-label", required=True)
    parser.add_argument("--audit", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = score(args.corpus, args.predictions, args.model_label, args.audit)
        encoded = json.dumps(
            result, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False
        ) + "\n"
        if args.output is not None:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded, encoding="utf-8")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    sys.stdout.write(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
