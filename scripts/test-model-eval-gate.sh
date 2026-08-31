#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

quality_score() {
  local target="$1"
  local miscalled="$2"
  cat > "$target" <<EOF
{
  "schemaVersion": 3,
  "modelLabel": "gate-fixture",
  "caseCount": 26,
  "toolCallCoverage": {
    "source": "audit-all-calls",
    "caseCount": 26,
    "multiToolCaseCount": 0,
    "rejectedToolCallCount": 0
  },
  "reviewCoverage": {
    "source": "audit-attested",
    "requiredCaseCount": 26,
    "reviewedCaseCount": 26
  },
  "languageEvidence": {
    "eligibleCaseCount": 9,
    "observedCaseCount": 9
  },
  "evaluationRunBinding": {
    "runId": "00000000-0000-4000-8000-000000000001",
    "modelArtifactSha256": "0000000000000000000000000000000000000000000000000000000000000000",
    "modelArtifactSizeBytes": 1,
    "modelArtifactFileName": "fixture.gguf",
    "seed": 42,
    "temperature": 0.1,
    "topK": 50,
    "repeatPenalty": 1.1,
    "maxOutputTokens": 1024,
    "thinking": "off",
    "modelLabel": "gate-fixture",
    "toolFormat": "native",
    "baseUrl": "http://127.0.0.1:1",
    "endpointModel": "fixture"
  },
  "quality": {
    "toolSelectionAccuracy": 1.0,
    "argumentExactMatch": 1.0,
    "dateTimeExactMatch": 1.0,
    "clarificationAccuracy": 1.0,
    "finalStateAccuracy": 1.0,
    "responseLanguageAccuracy": 1.0,
    "unnecessaryToolCallRate": 0.0,
    "stateChangingToolMiscalledRate": $miscalled
  },
  "device": {
    "ttftMillisAverage": null,
    "ttftMillisP95": null,
    "turnMillisAverage": null,
    "turnMillisP95": null,
    "pssMbMaximum": null,
    "batteryDeltaPercentAverage": null,
    "maximumThermal": null,
    "foldTransitionSuccessRate": null,
    "cancelRecoveryRate": null
  },
  "deviceRunBinding": null,
  "telemetryCoverage": {
    "ttft": 0, "turn": 0, "pss": 0, "battery": 0,
    "thermal": 0, "fold": 0, "cancel": 0, "turnCore": 0
  },
  "mismatches": []
}
EOF
}

quality_score "$TMP/pass.json" 0.0
python3 "$ROOT/scripts/gate-model-eval.py" --score "$TMP/pass.json" --profile quality > /dev/null

python3 - "$TMP/pass.json" "$TMP/too-small.json" "$TMP/too-large.json" "$TMP/out-of-range.json" <<'PY'
import json
import sys

source, too_small, too_large, out_of_range = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
receipt["caseCount"] = 1
json.dump(receipt, open(too_small, "w", encoding="utf-8"))
receipt["caseCount"] = 27
json.dump(receipt, open(too_large, "w", encoding="utf-8"))
receipt["caseCount"] = 26
receipt["quality"]["toolSelectionAccuracy"] = 1.2
json.dump(receipt, open(out_of_range, "w", encoding="utf-8"))
PY
if python3 "$ROOT/scripts/gate-model-eval.py" --score "$TMP/too-small.json" --profile quality > /dev/null 2>&1; then
  echo "undersized corpus unexpectedly passed" >&2
  exit 1
fi
if python3 "$ROOT/scripts/gate-model-eval.py" --score "$TMP/too-large.json" --profile quality > /dev/null 2>&1; then
  echo "oversized fixed corpus unexpectedly passed" >&2
  exit 1
fi
if python3 "$ROOT/scripts/gate-model-eval.py" --score "$TMP/out-of-range.json" --profile quality > /dev/null; then
  echo "out-of-range quality rate unexpectedly passed" >&2
  exit 1
fi

quality_score "$TMP/unsafe.json" 0.05
if python3 "$ROOT/scripts/gate-model-eval.py" --score "$TMP/unsafe.json" --profile quality > /dev/null; then
  echo "unsafe state-changing Tool selection unexpectedly passed" >&2
  exit 1
fi

python3 - "$TMP/pass.json" "$TMP/primary-only.json" <<'PY'
import json
import sys

source, target = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
receipt["toolCallCoverage"]["source"] = "primary-only"
json.dump(receipt, open(target, "w", encoding="utf-8"))
PY
primary_status=0
python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/primary-only.json" --profile quality \
    --output "$TMP/primary-only-gate.json" > /dev/null || primary_status=$?
if [[ "$primary_status" -ne 2 ]]; then
  echo "primary-only Tool safety coverage did not produce a quality rejection" >&2
  exit 1
fi
python3 - "$TMP/primary-only-gate.json" <<'PY'
import json, sys

gate = json.load(open(sys.argv[1], encoding="utf-8"))
assert any(item["metric"] == "toolCallCoverage" for item in gate["violations"])
PY

python3 - "$TMP/pass.json" "$TMP" <<'PY'
import json
import math
import pathlib
import sys

source, target_dir = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
variants = {
    "negative-device.json": ("device", "ttftMillisAverage", -1),
    "nonfinite-device.json": ("device", "turnMillisAverage", math.inf),
    "oversized-device.json": ("device", "pssMbMaximum", 10 ** 1000),
    "invalid-thermal.json": ("device", "maximumThermal", "HOT"),
    "boolean-coverage.json": ("telemetryCoverage", "fold", True),
}
for filename, (section, key, value) in variants.items():
    changed = json.loads(json.dumps(receipt))
    changed[section][key] = value
    with open(pathlib.Path(target_dir) / filename, "w", encoding="utf-8") as target:
        json.dump(changed, target)
PY
for malformed in \
  negative-device.json \
  nonfinite-device.json \
  oversized-device.json \
  invalid-thermal.json \
  boolean-coverage.json; do
  if python3 "$ROOT/scripts/gate-model-eval.py" \
      --score "$TMP/$malformed" --profile quality > /dev/null 2>&1; then
    echo "malformed score telemetry unexpectedly passed: $malformed" >&2
    exit 1
  fi
done

if python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/pass.json" --profile avd-runtime > /dev/null; then
  echo "missing device telemetry unexpectedly passed" >&2
  exit 1
fi

python3 - "$TMP/pass.json" "$TMP/device.json" <<'PY'
import json
import sys

source, target = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
receipt["device"].update({
    "ttftMillisAverage": 1400.0,
    "ttftMillisP95": 1900.0,
    "turnMillisAverage": 16000.0,
    "turnMillisP95": 25000.0,
    "pssMbMaximum": 3400.0,
    "maximumThermal": "SEVERE",
    "foldTransitionSuccessRate": 1.0,
    "cancelRecoveryRate": 1.0,
})
receipt["telemetryCoverage"].update({
    "ttft": 5, "turn": 5, "pss": 1, "thermal": 5, "fold": 1, "cancel": 1,
    "turnCore": 5,
})
receipt["deviceRunBinding"] = {
    "environment": "android-emulator",
    "deviceManufacturer": "Google",
    "deviceModel": "Android SDK built for arm64",
    "deviceSerialSha256": "5" * 64,
    "androidBuildFingerprintSha256": "6" * 64,
    "inferenceBackend": "CPU",
    "liteRtLmVersion": "0.16.1",
    "applicationId": "com.personaledge.agent.fixture",
    "buildType": "debug",
    "sourceStateSha256": "1" * 64,
    "appApkSha256": "2" * 64,
    "testApkSha256": "3" * 64,
    "appSigningCertificateSha256": "4" * 64,
    "modelArtifactSha256": "0" * 64,
    "runId": "fixture-run",
    "telemetryCaseCount": 5,
}
json.dump(receipt, open(target, "w", encoding="utf-8"))
PY
python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/device.json" --profile avd-runtime > /dev/null
if python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/device.json" --profile fold8-physical > /dev/null; then
  echo "emulator telemetry unexpectedly passed the Fold8 profile" >&2
  exit 1
fi

python3 - "$TMP/device.json" "$TMP/fold8.json" <<'PY'
import json
import sys

source, target = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
receipt["deviceRunBinding"].update({
    "environment": "android-physical",
    "deviceManufacturer": "samsung",
    "deviceModel": "SM-F971N",
    "deviceSerialSha256": "7" * 64,
    "androidBuildFingerprintSha256": "8" * 64,
    "inferenceBackend": "GPU",
})
json.dump(receipt, open(target, "w", encoding="utf-8"))
PY
python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/fold8.json" --profile fold8-physical > /dev/null

python3 - "$TMP/fold8.json" "$TMP/wrong-model.json" "$TMP/missing-signer.json" <<'PY'
import json
import sys

source, wrong_model, missing_signer = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
receipt["deviceRunBinding"]["deviceModel"] = "SM-OTHER"
json.dump(receipt, open(wrong_model, "w", encoding="utf-8"))
receipt = json.load(open(source, encoding="utf-8"))
receipt["deviceRunBinding"].pop("appSigningCertificateSha256")
json.dump(receipt, open(missing_signer, "w", encoding="utf-8"))
PY
if python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/wrong-model.json" --profile fold8-physical > /dev/null; then
  echo "wrong physical model unexpectedly passed the Fold8 profile" >&2
  exit 1
fi
if python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/missing-signer.json" --profile quality > /dev/null 2>&1; then
  echo "incomplete runtime binding unexpectedly passed shape validation" >&2
  exit 1
fi
if python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/device.json" --profile device > /dev/null 2>&1; then
  echo "deprecated ambiguous device profile unexpectedly remained available" >&2
  exit 1
fi

python3 - "$TMP/device.json" "$TMP/hot.json" <<'PY'
import json
import sys

source, target = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
receipt["device"]["maximumThermal"] = "CRITICAL"
json.dump(receipt, open(target, "w", encoding="utf-8"))
PY
if python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/hot.json" --profile avd-runtime > /dev/null; then
  echo "critical thermal evidence unexpectedly passed" >&2
  exit 1
fi

python3 - "$TMP/device.json" "$TMP/disjoint.json" <<'PY'
import json
import sys

source, target = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
receipt["telemetryCoverage"]["turnCore"] = 0
json.dump(receipt, open(target, "w", encoding="utf-8"))
PY
if python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/disjoint.json" --profile avd-runtime > /dev/null; then
  echo "disjoint device telemetry unexpectedly passed" >&2
  exit 1
fi

python3 - "$TMP/pass.json" "$TMP/unreviewed.json" "$TMP/rejected-call.json" <<'PY'
import json
import sys

source, unreviewed, rejected = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
receipt["reviewCoverage"] = {
    "source": "audit-unreviewed",
    "requiredCaseCount": 1,
    "reviewedCaseCount": 0,
}
json.dump(receipt, open(unreviewed, "w", encoding="utf-8"))
receipt = json.load(open(source, encoding="utf-8"))
receipt["toolCallCoverage"]["rejectedToolCallCount"] = 1
json.dump(receipt, open(rejected, "w", encoding="utf-8"))
PY
for unsafe_receipt in unreviewed.json rejected-call.json; do
  if python3 "$ROOT/scripts/gate-model-eval.py" \
      --score "$TMP/$unsafe_receipt" --profile quality > /dev/null; then
    echo "unsafe evaluation evidence unexpectedly passed: $unsafe_receipt" >&2
    exit 1
  fi
done

python3 - "$TMP/pass.json" "$TMP/prediction-only.json" "$TMP/no-language-cases.json" \
    "$TMP/missing-model-label.json" <<'PY'
import json
import sys

source, prediction_only, no_language, missing_label = sys.argv[1:]
receipt = json.load(open(source, encoding="utf-8"))
receipt["toolCallCoverage"]["source"] = "prediction-all-calls"
json.dump(receipt, open(prediction_only, "w", encoding="utf-8"))
receipt = json.load(open(source, encoding="utf-8"))
receipt["languageEvidence"] = {"eligibleCaseCount": 0, "observedCaseCount": 0}
json.dump(receipt, open(no_language, "w", encoding="utf-8"))
receipt = json.load(open(source, encoding="utf-8"))
receipt.pop("modelLabel")
json.dump(receipt, open(missing_label, "w", encoding="utf-8"))
PY
prediction_only_status=0
python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/prediction-only.json" --profile quality > /dev/null || prediction_only_status=$?
if [[ "$prediction_only_status" -ne 2 ]]; then
  echo "prediction-only receipt unexpectedly passed or failed schema validation" >&2
  exit 1
fi
for malformed in no-language-cases.json missing-model-label.json; do
  malformed_status=0
  python3 "$ROOT/scripts/gate-model-eval.py" \
      --score "$TMP/$malformed" --profile quality > /dev/null 2>&1 || malformed_status=$?
  if [[ "$malformed_status" -ne 1 ]]; then
    echo "impossible scorer shape did not fail schema validation: $malformed" >&2
    exit 1
  fi
done

python3 - "$TMP/pass.json" "$TMP/duplicate-score-key.json" "$TMP/nonfinite-score.json" <<'PY'
import sys
source, duplicate, nonfinite = sys.argv[1:]
raw = open(source, encoding="utf-8").read()
needle = '"modelLabel": "gate-fixture"'
assert needle in raw
open(duplicate, "w", encoding="utf-8").write(
    raw.replace(needle, needle + ',\n  "modelLabel": "gate-fixture"', 1)
)
needle = '"toolSelectionAccuracy": 1.0'
assert needle in raw
open(nonfinite, "w", encoding="utf-8").write(raw.replace(needle, '"toolSelectionAccuracy": NaN', 1))
PY
for malformed in duplicate-score-key.json nonfinite-score.json; do
  malformed_status=0
  python3 "$ROOT/scripts/gate-model-eval.py" \
      --score "$TMP/$malformed" --profile quality > /dev/null 2>&1 || malformed_status=$?
  if [[ "$malformed_status" -ne 1 ]]; then
    echo "non-strict score JSON unexpectedly passed: $malformed" >&2
    exit 1
  fi
done

echo "model evaluation gate tests passed"
