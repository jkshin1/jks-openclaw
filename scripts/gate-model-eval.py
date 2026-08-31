#!/usr/bin/env python3
"""Fail closed against the content-free Personal Edge model-evaluation receipt."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import uuid
from pathlib import Path
from typing import Any


THERMAL_ORDER = {
    "NONE": 0,
    "LIGHT": 1,
    "MODERATE": 2,
    "SEVERE": 3,
    "CRITICAL": 4,
    "EMERGENCY": 5,
    "SHUTDOWN": 6,
}
EXPECTED_CASE_COUNT = 26

QUALITY_MINIMUMS = {
    "toolSelectionAccuracy": 0.95,
    "argumentExactMatch": 0.90,
    "dateTimeExactMatch": 0.90,
    "clarificationAccuracy": 0.95,
    "finalStateAccuracy": 0.95,
    "responseLanguageAccuracy": 0.99,
}
QUALITY_MAXIMUMS = {
    "unnecessaryToolCallRate": 0.02,
    "stateChangingToolMiscalledRate": 0.0,
}
DEVICE_MAXIMUMS = {
    "ttftMillisAverage": 1_600.0,
    "ttftMillisP95": 2_000.0,
    "turnMillisAverage": 18_000.0,
    "turnMillisP95": 30_000.0,
    "pssMbMaximum": 3_584.0,
}
DEVICE_MINIMUMS = {
    "foldTransitionSuccessRate": 1.0,
    "cancelRecoveryRate": 1.0,
}
DEVICE_COVERAGE_MINIMUMS = {
    "ttft": 5,
    "turn": 5,
    "turnCore": 5,
    "pss": 1,
    "thermal": 5,
    "fold": 1,
    "cancel": 1,
}
DEVICE_NUMERIC_METRICS = {
    "ttftMillisAverage",
    "ttftMillisP95",
    "turnMillisAverage",
    "turnMillisP95",
    "pssMbMaximum",
    "batteryDeltaPercentAverage",
}
DEVICE_RATIO_METRICS = {"foldTransitionSuccessRate", "cancelRecoveryRate"}
COVERAGE_TO_DEVICE_METRICS = {
    "ttft": ("ttftMillisAverage", "ttftMillisP95"),
    "turn": ("turnMillisAverage", "turnMillisP95"),
    "pss": ("pssMbMaximum",),
    "battery": ("batteryDeltaPercentAverage",),
    "thermal": ("maximumThermal",),
    "fold": ("foldTransitionSuccessRate",),
    "cancel": ("cancelRecoveryRate",),
}
DEVICE_RUN_KEYS = {
    "environment", "deviceManufacturer", "deviceModel", "deviceSerialSha256",
    "androidBuildFingerprintSha256", "inferenceBackend", "liteRtLmVersion",
    "applicationId", "buildType", "sourceStateSha256", "appApkSha256",
    "testApkSha256", "appSigningCertificateSha256",
    "modelArtifactSha256", "runId", "telemetryCaseCount",
}
DEVICE_SHA256_KEYS = {
    "deviceSerialSha256", "androidBuildFingerprintSha256", "sourceStateSha256",
    "appApkSha256", "testApkSha256", "appSigningCertificateSha256",
    "modelArtifactSha256",
}
DEVICE_ENVIRONMENTS = {"android-emulator", "android-physical"}
DEVICE_BACKENDS = {"CPU", "GPU"}
FOLD8_HARDWARE_MODEL = "SM-F971N"
EXPECTED_LANGUAGE_CASE_COUNT = 9
EXPECTED_SCORE_KEYS = {
    "schemaVersion", "modelLabel", "caseCount", "toolCallCoverage",
    "reviewCoverage", "languageEvidence", "evaluationRunBinding", "quality", "deviceRunBinding",
    "device", "telemetryCoverage", "mismatches",
}
MISMATCH_KEYS = {
    "id", "expectedTool", "actualTool", "actualTools",
    "expectedResponseLanguage", "actualResponseLanguage",
}
EVALUATION_RUN_KEYS = {
    "runId", "modelArtifactSha256", "modelArtifactSizeBytes", "modelArtifactFileName",
    "seed", "temperature", "topK", "repeatPenalty", "maxOutputTokens", "thinking",
    "modelLabel", "toolFormat", "baseUrl", "endpointModel",
}


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"non-finite JSON constant is not allowed: {value}")


def _strict_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key is not allowed: {key}")
        result[key] = value
    return result


def strict_json_loads(raw: str) -> Any:
    return json.loads(
        raw,
        object_pairs_hook=_strict_json_object,
        parse_constant=_reject_json_constant,
    )


def finite_number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        number = float(value)
    except OverflowError:
        return None
    return number if math.isfinite(number) else None


def validate_shape(receipt: Any) -> dict[str, Any]:
    if not isinstance(receipt, dict) or receipt.get("schemaVersion") != 3:
        raise ValueError("score receipt must be a schemaVersion 3 object")
    if set(receipt) != EXPECTED_SCORE_KEYS:
        raise ValueError("score receipt top-level keys do not match schemaVersion 3")
    model_label = receipt["modelLabel"]
    if not isinstance(model_label, str) or not 1 <= len(model_label) <= 256 or any(
        character in model_label for character in "\r\n\x00"
    ):
        raise ValueError("score receipt modelLabel must be a bounded string")
    case_count = receipt.get("caseCount")
    if (
        isinstance(case_count, bool)
        or not isinstance(case_count, int)
        or case_count != EXPECTED_CASE_COUNT
    ):
        raise ValueError(f"score receipt must contain exactly {EXPECTED_CASE_COUNT} cases")
    evaluation_binding = receipt.get("evaluationRunBinding")
    if not isinstance(evaluation_binding, dict) or set(evaluation_binding) != EVALUATION_RUN_KEYS:
        raise ValueError("score receipt requires one exact evaluation run binding")
    try:
        parsed_run_id = uuid.UUID(evaluation_binding["runId"])
    except (AttributeError, TypeError, ValueError):
        raise ValueError("evaluationRunBinding.runId must be a UUID") from None
    if str(parsed_run_id) != evaluation_binding["runId"]:
        raise ValueError("evaluationRunBinding.runId must use canonical lowercase UUID form")
    artifact_sha256 = evaluation_binding["modelArtifactSha256"]
    if not isinstance(artifact_sha256, str) or re.fullmatch(r"[0-9a-f]{64}", artifact_sha256) is None:
        raise ValueError("evaluationRunBinding.modelArtifactSha256 must be lowercase SHA-256")
    artifact_size = evaluation_binding["modelArtifactSizeBytes"]
    if isinstance(artifact_size, bool) or not isinstance(artifact_size, int) or artifact_size < 0:
        raise ValueError("evaluationRunBinding.modelArtifactSizeBytes must be non-negative")
    artifact_name = evaluation_binding["modelArtifactFileName"]
    if (
        not isinstance(artifact_name, str)
        or not 1 <= len(artifact_name) <= 256
        or artifact_name in {".", ".."}
        or any(character in artifact_name for character in "/\\\r\n\x00")
    ):
        raise ValueError("evaluationRunBinding.modelArtifactFileName must be a safe basename")
    if isinstance(evaluation_binding["seed"], bool) or not isinstance(evaluation_binding["seed"], int):
        raise ValueError("evaluationRunBinding.seed must be an integer")
    for key in ("temperature", "repeatPenalty"):
        value = finite_number(evaluation_binding[key])
        if value is None or value < 0 or (key == "repeatPenalty" and value <= 0):
            raise ValueError(f"evaluationRunBinding.{key} is invalid")
    for key in ("topK", "maxOutputTokens"):
        value = evaluation_binding[key]
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ValueError(f"evaluationRunBinding.{key} must be positive")
    if evaluation_binding["thinking"] not in {"on", "off"}:
        raise ValueError("evaluationRunBinding.thinking is invalid")
    if evaluation_binding["toolFormat"] not in {"auto", "native", "pythonic", "function-tag", "hermes"}:
        raise ValueError("evaluationRunBinding.toolFormat is invalid")
    for key in ("modelLabel", "baseUrl", "endpointModel"):
        value = evaluation_binding[key]
        if not isinstance(value, str) or not 1 <= len(value) <= 256 or any(
            character in value for character in "\r\n\x00"
        ):
            raise ValueError(f"evaluationRunBinding.{key} must be a bounded string")
    if evaluation_binding["modelLabel"] != model_label:
        raise ValueError("evaluation run label disagrees with score label")
    if re.fullmatch(r"http://127\.0\.0\.1:[0-9]{1,5}", evaluation_binding["baseUrl"]) is None:
        raise ValueError("evaluation run endpoint must be explicit loopback")
    port = int(evaluation_binding["baseUrl"].rsplit(":", 1)[1])
    if not 1 <= port <= 65535:
        raise ValueError("evaluation run endpoint port is invalid")
    for field in (
        "quality", "device", "telemetryCoverage", "reviewCoverage", "languageEvidence",
    ):
        if not isinstance(receipt.get(field), dict):
            raise ValueError(f"score receipt is missing {field}")
    tool_call_coverage = receipt.get("toolCallCoverage")
    if not isinstance(tool_call_coverage, dict) or set(tool_call_coverage) != {
        "source", "caseCount", "multiToolCaseCount", "rejectedToolCallCount"
    }:
        raise ValueError("score receipt is missing exact Tool-call coverage")
    if tool_call_coverage["source"] not in {
        "prediction-all-calls", "audit-all-calls", "primary-only"
    }:
        raise ValueError("score receipt has an unknown Tool-call coverage source")
    if tool_call_coverage["caseCount"] != case_count:
        raise ValueError("Tool-call coverage must include every case")
    multi_tool_count = tool_call_coverage["multiToolCaseCount"]
    if (
        isinstance(multi_tool_count, bool)
        or not isinstance(multi_tool_count, int)
        or multi_tool_count < 0
        or multi_tool_count > case_count
    ):
        raise ValueError("multiToolCaseCount must be in the corpus range")
    rejected_call_count = tool_call_coverage["rejectedToolCallCount"]
    if (
        isinstance(rejected_call_count, bool)
        or not isinstance(rejected_call_count, int)
        or rejected_call_count < 0
    ):
        raise ValueError("rejectedToolCallCount must be a non-negative integer")
    review = receipt["reviewCoverage"]
    if set(review) != {"source", "requiredCaseCount", "reviewedCaseCount"}:
        raise ValueError("score receipt review coverage does not match schemaVersion 3")
    if review["source"] not in {"not-provided", "audit-unreviewed", "audit-attested"}:
        raise ValueError("score receipt has an unknown review coverage source")
    for key in ("requiredCaseCount", "reviewedCaseCount"):
        value = review[key]
        if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > case_count:
            raise ValueError(f"reviewCoverage.{key} must be in the corpus range")
    if review["reviewedCaseCount"] > review["requiredCaseCount"]:
        raise ValueError("reviewedCaseCount cannot exceed requiredCaseCount")
    if review["requiredCaseCount"] != case_count:
        raise ValueError("review coverage must require the entire fixed corpus")
    if review["source"] == "audit-attested" and review["reviewedCaseCount"] != case_count:
        raise ValueError("audit-attested review coverage must cover every case")
    if review["source"] != "audit-attested" and review["reviewedCaseCount"] == case_count:
        raise ValueError("complete review coverage must be audit-attested")
    language = receipt["languageEvidence"]
    if set(language) != {"eligibleCaseCount", "observedCaseCount"}:
        raise ValueError("score receipt language evidence does not match schemaVersion 3")
    for key in ("eligibleCaseCount", "observedCaseCount"):
        value = language[key]
        if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > case_count:
            raise ValueError(f"languageEvidence.{key} must be in the corpus range")
    if language["observedCaseCount"] > language["eligibleCaseCount"]:
        raise ValueError("observed language evidence cannot exceed eligible cases")
    if language["eligibleCaseCount"] != EXPECTED_LANGUAGE_CASE_COUNT:
        raise ValueError(
            f"languageEvidence must cover exactly {EXPECTED_LANGUAGE_CASE_COUNT} fixed cases"
        )
    quality = receipt["quality"]
    expected_quality_metrics = set(QUALITY_MINIMUMS) | set(QUALITY_MAXIMUMS)
    if set(quality) != expected_quality_metrics:
        raise ValueError("score receipt quality metrics do not match schemaVersion 1")
    device = receipt["device"]
    expected_device_metrics = DEVICE_NUMERIC_METRICS | DEVICE_RATIO_METRICS | {"maximumThermal"}
    if set(device) != expected_device_metrics:
        raise ValueError("score receipt device metrics do not match schemaVersion 1")
    for metric in DEVICE_NUMERIC_METRICS:
        value = device[metric]
        if value is not None:
            number = finite_number(value)
            if number is None or number < 0.0:
                raise ValueError(f"device.{metric} must be null or a finite non-negative number")
    for metric in DEVICE_RATIO_METRICS:
        value = device[metric]
        if value is not None:
            number = finite_number(value)
            if number is None or number < 0.0 or number > 1.0:
                raise ValueError(f"device.{metric} must be null or a finite rate in 0..1")
    thermal = device["maximumThermal"]
    if thermal is not None and thermal not in THERMAL_ORDER:
        raise ValueError("device.maximumThermal must be null or a known thermal state")
    coverage = receipt["telemetryCoverage"]
    expected_coverage = set(COVERAGE_TO_DEVICE_METRICS) | {"turnCore"}
    if set(coverage) != expected_coverage:
        raise ValueError("score receipt telemetry coverage does not match schemaVersion 1")
    for metric in expected_coverage:
        count = coverage[metric]
        if (
            isinstance(count, bool)
            or not isinstance(count, int)
            or count < 0
            or count > case_count
        ):
            raise ValueError(f"telemetryCoverage.{metric} must be in 0..{case_count}")
        for covered_metric in COVERAGE_TO_DEVICE_METRICS.get(metric, ()):
            has_value = device[covered_metric] is not None
            if has_value != (count > 0):
                raise ValueError(
                    f"device.{covered_metric} disagrees with telemetryCoverage.{metric}"
                )
    if coverage["turnCore"] > min(coverage["ttft"], coverage["turn"], coverage["thermal"]):
        raise ValueError("turnCore coverage cannot exceed its component coverage")
    mismatches = receipt["mismatches"]
    if not isinstance(mismatches, list) or len(mismatches) > case_count:
        raise ValueError("mismatches must be a bounded list")
    mismatch_ids: set[str] = set()
    for mismatch in mismatches:
        if not isinstance(mismatch, dict) or set(mismatch) != MISMATCH_KEYS:
            raise ValueError("mismatch rows do not match schemaVersion 3")
        mismatch_id = mismatch["id"]
        if not isinstance(mismatch_id, str) or mismatch_id in mismatch_ids:
            raise ValueError("mismatch ids must be unique strings")
        mismatch_ids.add(mismatch_id)
        if not isinstance(mismatch["actualTools"], list) or not all(
            isinstance(name, str) for name in mismatch["actualTools"]
        ):
            raise ValueError("mismatch actualTools must be a string list")
        for key in ("expectedTool", "actualTool"):
            if mismatch[key] is not None and not isinstance(mismatch[key], str):
                raise ValueError(f"mismatch {key} must be null or a string")
        for key in ("expectedResponseLanguage", "actualResponseLanguage"):
            if not isinstance(mismatch[key], str):
                raise ValueError(f"mismatch {key} must be a string")
    binding = receipt.get("deviceRunBinding")
    any_telemetry = any(coverage[metric] > 0 for metric in coverage if metric != "turnCore")
    if binding is None:
        if any_telemetry:
            raise ValueError("telemetry requires one deviceRunBinding")
    else:
        if not isinstance(binding, dict) or set(binding) != DEVICE_RUN_KEYS:
            raise ValueError("deviceRunBinding does not match schemaVersion 3")
        for key in DEVICE_RUN_KEYS - {"telemetryCaseCount"}:
            value = binding[key]
            if not isinstance(value, str) or not 1 <= len(value) <= 256 or any(
                character in value for character in "\r\n\x00"
            ):
                raise ValueError(f"deviceRunBinding.{key} must be a bounded string")
        for key in DEVICE_SHA256_KEYS:
            sha256 = binding[key]
            if len(sha256) != 64 or any(
                character not in "0123456789abcdef" for character in sha256
            ):
                raise ValueError(f"deviceRunBinding.{key} must be lowercase SHA-256")
        if binding["environment"] not in DEVICE_ENVIRONMENTS:
            raise ValueError("deviceRunBinding.environment is invalid")
        if binding["inferenceBackend"] not in DEVICE_BACKENDS:
            raise ValueError("deviceRunBinding.inferenceBackend is invalid")
        version = binding["liteRtLmVersion"]
        if not isinstance(version, str) or len(version.split(".")) != 3 or any(
            not component.isdigit() for component in version.split(".")
        ):
            raise ValueError("deviceRunBinding.liteRtLmVersion is invalid")
        telemetry_cases = binding["telemetryCaseCount"]
        if (
            isinstance(telemetry_cases, bool)
            or not isinstance(telemetry_cases, int)
            or telemetry_cases < max(coverage.values())
            or telemetry_cases > case_count
        ):
            raise ValueError("deviceRunBinding.telemetryCaseCount disagrees with coverage")
    return receipt


def gate(receipt: dict[str, Any], profile: str) -> dict[str, Any]:
    violations: list[dict[str, Any]] = []
    quality = receipt["quality"]
    if receipt["toolCallCoverage"]["source"] != "audit-all-calls":
        violations.append(
            {
                "metric": "toolCallCoverage",
                "rule": "minimum",
                "threshold": "all-calls",
                "actual": receipt["toolCallCoverage"]["source"],
            }
        )
    if receipt["toolCallCoverage"]["rejectedToolCallCount"] != 0:
        violations.append(
            {
                "metric": "rejectedToolCallCount",
                "rule": "maximum",
                "threshold": 0,
                "actual": receipt["toolCallCoverage"]["rejectedToolCallCount"],
            }
        )
    review = receipt["reviewCoverage"]
    if (
        review["source"] != "audit-attested" or
        review["reviewedCaseCount"] != review["requiredCaseCount"]
    ):
        violations.append(
            {
                "metric": "reviewCoverage",
                "rule": "minimum",
                "threshold": "all-required-cases-attested",
                "actual": (
                    f"{review['source']}:{review['reviewedCaseCount']}/"
                    f"{review['requiredCaseCount']}"
                ),
            }
        )
    language = receipt["languageEvidence"]
    if language["observedCaseCount"] != language["eligibleCaseCount"]:
        violations.append(
            {
                "metric": "languageEvidence",
                "rule": "minimum",
                "threshold": language["eligibleCaseCount"],
                "actual": language["observedCaseCount"],
            }
        )
    for metric, threshold in QUALITY_MINIMUMS.items():
        actual = finite_number(quality.get(metric))
        if actual is None or actual < 0.0 or actual > 1.0 or actual < threshold:
            violations.append(
                {"metric": metric, "rule": "minimum", "threshold": threshold, "actual": actual}
            )
    for metric, threshold in QUALITY_MAXIMUMS.items():
        actual = finite_number(quality.get(metric))
        if actual is None or actual < 0.0 or actual > 1.0 or actual > threshold:
            violations.append(
                {"metric": metric, "rule": "maximum", "threshold": threshold, "actual": actual}
            )

    if profile in {"avd-runtime", "fold8-physical"}:
        coverage = receipt["telemetryCoverage"]
        for metric, threshold in DEVICE_COVERAGE_MINIMUMS.items():
            actual = coverage.get(metric)
            if isinstance(actual, bool) or not isinstance(actual, int) or actual < threshold:
                violations.append(
                    {
                        "metric": f"coverage.{metric}",
                        "rule": "minimum",
                        "threshold": threshold,
                        "actual": actual if isinstance(actual, int) and not isinstance(actual, bool) else None,
                    }
                )
        device = receipt["device"]
        for metric, threshold in DEVICE_MAXIMUMS.items():
            actual = finite_number(device.get(metric))
            if actual is None or actual < 0.0 or actual > threshold:
                violations.append(
                    {"metric": metric, "rule": "maximum", "threshold": threshold, "actual": actual}
                )
        for metric, threshold in DEVICE_MINIMUMS.items():
            actual = finite_number(device.get(metric))
            if actual is None or actual < 0.0 or actual > 1.0 or actual < threshold:
                violations.append(
                    {"metric": metric, "rule": "minimum", "threshold": threshold, "actual": actual}
                )
        thermal = device.get("maximumThermal")
        if thermal not in THERMAL_ORDER or THERMAL_ORDER[thermal] > THERMAL_ORDER["SEVERE"]:
            violations.append(
                {
                    "metric": "maximumThermal",
                    "rule": "maximum",
                    "threshold": "SEVERE",
                    "actual": thermal if isinstance(thermal, str) else None,
                }
            )
        if receipt.get("deviceRunBinding") is None:
            violations.append(
                {
                    "metric": "deviceRunBinding",
                    "rule": "required",
                    "threshold": "one-exact-binding",
                    "actual": None,
                }
            )
        elif profile == "avd-runtime":
            binding = receipt["deviceRunBinding"]
            if binding.get("environment") != "android-emulator":
                violations.append(
                    {
                        "metric": "deviceRunBinding.environment",
                        "rule": "exact",
                        "threshold": "android-emulator",
                        "actual": binding.get("environment"),
                    }
                )
        elif profile == "fold8-physical":
            binding = receipt["deviceRunBinding"]
            fold8_requirements = {
                "environment": "android-physical",
                "deviceManufacturer": "samsung",
                "deviceModel": FOLD8_HARDWARE_MODEL,
                "inferenceBackend": "GPU",
            }
            for key, expected in fold8_requirements.items():
                if binding.get(key) != expected:
                    violations.append(
                        {
                            "metric": f"deviceRunBinding.{key}",
                            "rule": "exact",
                            "threshold": expected,
                            "actual": binding.get(key),
                        }
                    )

    return {
        "schemaVersion": 3,
        "profile": profile,
        "passed": not violations,
        "caseCount": receipt["caseCount"],
        "evaluationRunBinding": receipt["evaluationRunBinding"],
        "violationCount": len(violations),
        "violations": violations,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--score", required=True, type=Path)
    parser.add_argument(
        "--profile",
        choices=("quality", "avd-runtime", "fold8-physical"),
        default="quality",
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        receipt = validate_shape(strict_json_loads(args.score.read_text(encoding="utf-8")))
        result = gate(receipt, args.profile)
        encoded = json.dumps(
            result, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False
        ) + "\n"
        if args.output is not None:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded, encoding="utf-8")
        sys.stdout.write(encoded)
        return 0 if result["passed"] else 2
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
