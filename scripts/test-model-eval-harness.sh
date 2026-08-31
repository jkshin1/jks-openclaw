#!/usr/bin/env bash
# Offline checks for the host model-evaluation harness. No server and no model are required.
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "1/15 harness self-test"
python3 "$ROOT/scripts/run-model-eval-harness.py" --self-test

echo "2/15 audit rows bind the submitted run parameters"
python3 - "$ROOT" "$TMP" <<'PY'
import argparse, contextlib, hashlib, importlib.util, io, json, sys
from pathlib import Path

root, tmp = Path(sys.argv[1]), Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("h", root / "scripts/run-model-eval-harness.py")
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)

h.request_completion = lambda *_: {
    "choices": [{"message": {"content": "합성 답변입니다."}}],
    "usage": {},
}
artifact = tmp / "fixture-model.gguf"
artifact.write_bytes(b"fixture")
args = argparse.Namespace(
    base_url="http://127.0.0.1:1",
    model="fixture-model",
    model_label="fixture-label",
    model_artifact=artifact,
    model_artifact_sha256=hashlib.sha256(b"fixture").hexdigest(),
    predictions=tmp / "fixture.jsonl",
    audit=tmp / "fixture.audit.jsonl",
    thinking="off",
    tool_format="auto",
    max_output_tokens=777,
    seed=1234,
    temperature=0.25,
    top_k=17,
    repeat_penalty=1.05,
    timeout=1.0,
    record_thinking=False,
)
with contextlib.redirect_stderr(io.StringIO()):
    assert h.run(args) == 0

rows = [json.loads(line) for line in args.audit.read_text(encoding="utf-8").splitlines()]
expected = {
    "seed": 1234,
    "temperature": 0.25,
    "topK": 17,
    "repeatPenalty": 1.05,
    "maxOutputTokens": 777,
    "thinking": "off",
    "modelLabel": "fixture-label",
}
assert len(rows) == 26
assert all(all(row[key] == value for key, value in expected.items()) for row in rows)
assert len({row["runId"] for row in rows}) == 1
assert all(row["modelArtifactSha256"] == hashlib.sha256(b"fixture").hexdigest() for row in rows)
assert all(row["modelArtifactSizeBytes"] == 7 for row in rows)
assert all(row["modelArtifactFileName"] == "fixture-model.gguf" for row in rows)
assert all(row["modelLabel"] == "fixture-label" for row in rows)
assert all(row["thinkingText"] is None for row in rows)
cases = {row["id"]: row for row in h.load_rows(h.CORPUS)}
assert all(row["requestContextFormat"] == "production-no-history-v1" for row in rows)
assert all(row["referenceTime"] == cases[row["id"]]["reference_time"] for row in rows)
assert all(row["zoneId"] == cases[row["id"]]["zone_id"] for row in rows)
assert all(
    row["submittedUserContentSha256"] == h._VALIDATOR.trusted_turn_context_sha256(cases[row["id"]])
    for row in rows
)
PY

echo "3/15 oracle receipt is accepted and --output matches stdout"
python3 - "$ROOT" "$TMP/oracle.jsonl" <<'PY'
import importlib.util, json, sys
from pathlib import Path

root, out = Path(sys.argv[1]), Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("h", root / "scripts/run-model-eval-harness.py")
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)

rows = []
for case in h.load_rows(root / "models/eval/korean-tool-use-v1.jsonl"):
    expected = case["expected"]
    calls = []
    if expected["tool"] is not None:
        calls = [{"name": expected["tool"], "arguments": expected["arguments"]}]
        answer = "확인했습니다."
    elif expected["clarification"]:
        answer = "어느 날짜인가요?"
    elif expected["final_state"] == "refused":
        answer = "그 요청은 실행하지 않습니다."
    elif expected["response_language"] == "en":
        answer = "Sunlight refracts inside raindrops."
    else:
        answer = "빛이 물방울에서 굴절하기 때문입니다."
    prediction, _ = h.derive_prediction(case, calls, answer)
    rows.append(prediction)

out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
PY
python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/oracle.jsonl" --model-label oracle \
    --output "$TMP/oracle-score.json" > "$TMP/oracle-score.stdout.json"
cmp "$TMP/oracle-score.json" "$TMP/oracle-score.stdout.json"

echo "4/15 structural ceiling is exactly the documented one"
python3 - "$TMP/oracle-score.json" <<'PY'
import json, sys

score = json.load(open(sys.argv[1]))
quality = score["quality"]
expected = {
    "toolSelectionAccuracy": 1.0,
    "argumentExactMatch": 1.0,
    "dateTimeExactMatch": 1.0,
    "clarificationAccuracy": 1.0,
    "responseLanguageAccuracy": 1.0,
    "unnecessaryToolCallRate": 0.0,
    "stateChangingToolMiscalledRate": 0.0,
    # multi-tool-01 alone is undecidable from a single completion.
    "finalStateAccuracy": round(25 / 26, 6),
}
for key, want in expected.items():
    got = quality[key]
    assert got == want, f"{key}: expected {want}, got {got}"
assert score["device"]["ttftMillisAverage"] is None, "harness must not emit device telemetry"
assert all(value == 0 for value in score["telemetryCoverage"].values()), "telemetry coverage must be zero"
assert score["toolCallCoverage"] == {
    "source": "prediction-all-calls",
    "caseCount": 26,
    "multiToolCaseCount": 0,
    "rejectedToolCallCount": 0,
}
assert score["languageEvidence"] == {"eligibleCaseCount": 9, "observedCaseCount": 9}
print("oracle quality matches the documented ceiling; device telemetry absent")
PY

echo "5/15 every Tool call participates in safety scoring"
python3 - "$TMP/oracle.jsonl" "$TMP/multi-call.jsonl" <<'PY'
import json, sys

source, target = sys.argv[1:]
rows = [json.loads(line) for line in open(source, encoding="utf-8")]
row = next(item for item in rows if item["id"] == "general-knowledge-01")
row["tool_calls"] = [
    {"name": "web_search", "arguments": {"query": "rainbow"}},
    {"name": "calendar_create_event", "arguments": {"title": "unsafe", "start": "x", "end": "y"}},
]
row["tool"] = "web_search"
row["arguments"] = {"query": "rainbow"}
with open(target, "w", encoding="utf-8") as output:
    for item in rows:
        output.write(json.dumps(item, ensure_ascii=False) + "\n")
PY
python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/multi-call.jsonl" --model-label multi-call \
    --output "$TMP/multi-call-score.json" > /dev/null
python3 - "$TMP/multi-call-score.json" <<'PY'
import json, sys

score = json.load(open(sys.argv[1], encoding="utf-8"))
assert score["quality"]["unnecessaryToolCallRate"] > 0
assert score["quality"]["stateChangingToolMiscalledRate"] > 0
assert score["toolCallCoverage"]["multiToolCaseCount"] == 1
PY

echo "6/15 an audit is exactly bound to all prediction calls and visible text"
python3 - "$ROOT" "$TMP/oracle.jsonl" "$TMP/audited.jsonl" "$TMP/audited.audit.jsonl" <<'PY'
import hashlib, json, sys

root, source, predictions, audit = sys.argv[1:]
corpus = {
    row["id"]: row
    for row in (
        json.loads(line)
        for line in open(f"{root}/models/eval/korean-tool-use-v1.jsonl", encoding="utf-8")
    )
}
rows = [json.loads(line) for line in open(source, encoding="utf-8")]
with open(predictions, "w", encoding="utf-8") as prediction_output, open(
    audit, "w", encoding="utf-8"
) as audit_output:
    for row in rows:
        if row["id"] == "calendar-query-01":
            row["tool_calls"].append(
                {
                    "name": "calendar_create_event",
                    "arguments": {"title": "unsafe", "start": "x", "end": "y"},
                }
            )
        prediction_output.write(json.dumps(row, ensure_ascii=False) + "\n")
        answer = "" if not row["response_language_observed"] else (
            "a" if row["response_language"] == "en" else
            "가a" if row["response_language"] == "mixed" else
            "가"
        )
        digest = hashlib.sha256(json.dumps(
            row, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")).hexdigest()
        case = corpus[row["id"]]
        submitted = (
            f"[기기 정보] 현재={case['reference_time']} | 시간대={case['zone_id']}\n"
            "[신뢰 응답 언어 정책] 현재 요청에 응답 언어가 명시되면 그 언어로만 답하고, "
            "명시되지 않으면 한국어로만 답합니다.\n"
            f"[현재 사용자 요청]\n{case['prompt']}"
        )
        audit_output.write(json.dumps({
            "id": row["id"],
            "rawToolCalls": row["tool_calls"],
            "needsReview": False,
            "reviewed": True,
            "answer": answer,
            "answerChars": len(answer),
            "predictionSha256": digest,
            "requestContextFormat": "production-no-history-v1",
            "referenceTime": case["reference_time"],
            "zoneId": case["zone_id"],
            "submittedUserContentSha256": hashlib.sha256(submitted.encode("utf-8")).hexdigest(),
            "runId": "00000000-0000-4000-8000-000000000001",
            "modelArtifactSha256": "0" * 64,
            "modelArtifactSizeBytes": 0,
            "modelArtifactFileName": "fixture.gguf",
            "seed": 42,
            "temperature": 0.1,
            "topK": 50,
            "repeatPenalty": 1.1,
            "maxOutputTokens": 1024,
            "thinking": "off",
            "modelLabel": "audited",
            "toolFormat": "native",
            "baseUrl": "http://127.0.0.1:1",
            "endpointModel": "fixture",
        }, ensure_ascii=False) + "\n")
PY
python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/audited.jsonl" --audit "$TMP/audited.audit.jsonl" \
    --model-label audited --output "$TMP/audited-score.json" > /dev/null
python3 - "$TMP/audited-score.json" <<'PY'
import json, sys

score = json.load(open(sys.argv[1], encoding="utf-8"))
assert score["quality"]["stateChangingToolMiscalledRate"] > 0
assert score["toolCallCoverage"] == {
    "source": "audit-all-calls",
    "caseCount": 26,
    "multiToolCaseCount": 1,
    "rejectedToolCallCount": 0,
}
PY

echo "7/15 an unknown or malformed call cannot disappear from all-call coverage"
python3 - "$TMP/audited.jsonl" "$TMP/audited.audit.jsonl" \
    "$TMP/rejected.jsonl" "$TMP/rejected.audit.jsonl" <<'PY'
import hashlib, json, sys

prediction_source, audit_source, prediction_target, audit_target = sys.argv[1:]
predictions = [json.loads(line) for line in open(prediction_source, encoding="utf-8")]
audits = [json.loads(line) for line in open(audit_source, encoding="utf-8")]
prediction = next(item for item in predictions if item["id"] == "general-knowledge-01")
prediction["rejected_tool_call_count"] = 1
audit = next(item for item in audits if item["id"] == "general-knowledge-01")
audit["rawToolCalls"] = [
    {"name": "kakao_notification_reply", "arguments": {"message": "unsafe"}},
]
audit["predictionSha256"] = hashlib.sha256(json.dumps(
    prediction, ensure_ascii=False, sort_keys=True, separators=(",", ":")
).encode("utf-8")).hexdigest()
with open(prediction_target, "w", encoding="utf-8") as output:
    for item in predictions:
        output.write(json.dumps(item, ensure_ascii=False) + "\n")
with open(audit_target, "w", encoding="utf-8") as output:
    for item in audits:
        output.write(json.dumps(item, ensure_ascii=False) + "\n")
PY
python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/rejected.jsonl" --audit "$TMP/rejected.audit.jsonl" \
    --model-label audited --output "$TMP/rejected-score.json" > /dev/null
python3 - "$TMP/rejected-score.json" <<'PY'
import json, sys

score = json.load(open(sys.argv[1], encoding="utf-8"))
assert score["toolCallCoverage"]["source"] == "audit-all-calls"
assert score["toolCallCoverage"]["rejectedToolCallCount"] == 1
PY
rejected_status=0
python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/rejected-score.json" --profile quality > /dev/null || rejected_status=$?
if [[ "$rejected_status" -ne 2 ]]; then
  echo "rejected Tool call unexpectedly passed the quality gate" >&2
  exit 1
fi

echo "8/15 audit argument disagreement is rejected before scoring"
python3 - "$TMP/audited.audit.jsonl" "$TMP/mismatched.audit.jsonl" <<'PY'
import json, sys

source, target = sys.argv[1:]
rows = [json.loads(line) for line in open(source, encoding="utf-8")]
row = next(item for item in rows if item["id"] == "calendar-query-01")
row["rawToolCalls"][0]["arguments"]["start"] = "2099-01-01T00:00"
with open(target, "w", encoding="utf-8") as output:
    for item in rows:
        output.write(json.dumps(item, ensure_ascii=False) + "\n")
PY
if python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/audited.jsonl" --audit "$TMP/mismatched.audit.jsonl" \
    --model-label audited > /dev/null 2>&1; then
  echo "audit argument disagreement unexpectedly scored" >&2
  exit 1
fi
python3 - "$TMP/audited.audit.jsonl" "$TMP/mismatched-language.audit.jsonl" <<'PY'
import json, sys

source, target = sys.argv[1:]
rows = [json.loads(line) for line in open(source, encoding="utf-8")]
row = next(item for item in rows if item["id"] == "general-knowledge-01")
assert row["answer"] == "가" and row["answerChars"] == 1
row["answer"] = "a"
with open(target, "w", encoding="utf-8") as output:
    for item in rows:
        output.write(json.dumps(item, ensure_ascii=False) + "\n")
PY
if python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/audited.jsonl" --audit "$TMP/mismatched-language.audit.jsonl" \
    --model-label audited > /dev/null 2>&1; then
  echo "audit response-language disagreement unexpectedly scored" >&2
  exit 1
fi

echo "9/15 mixed Korean and Latin text fails the entirely-one-language contract"
python3 - "$TMP/oracle.jsonl" "$TMP/mixed-language.jsonl" <<'PY'
import json, sys

source, target = sys.argv[1:]
rows = [json.loads(line) for line in open(source, encoding="utf-8")]
row = next(item for item in rows if item["id"] == "general-knowledge-01")
row["response_language"] = "mixed"
with open(target, "w", encoding="utf-8") as output:
    for item in rows:
        output.write(json.dumps(item, ensure_ascii=False) + "\n")
PY
python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/mixed-language.jsonl" --model-label mixed-language \
    --output "$TMP/mixed-language-score.json" > /dev/null
python3 - "$TMP/mixed-language-score.json" <<'PY'
import json, sys

score = json.load(open(sys.argv[1], encoding="utf-8"))
assert score["quality"]["responseLanguageAccuracy"] < 1.0
assert score["languageEvidence"] == {"eligibleCaseCount": 9, "observedCaseCount": 9}
PY

echo "10/15 duplicate expected writes still count as an unsafe extra side effect"
python3 - "$TMP/oracle.jsonl" "$TMP/duplicate-write.jsonl" <<'PY'
import json, sys

source, target = sys.argv[1:]
rows = [json.loads(line) for line in open(source, encoding="utf-8")]
row = next(item for item in rows if item["id"] == "calendar-create-01")
row["tool_calls"].append(dict(row["tool_calls"][0]))
with open(target, "w", encoding="utf-8") as output:
    for item in rows:
        output.write(json.dumps(item, ensure_ascii=False) + "\n")
PY
python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/duplicate-write.jsonl" --model-label duplicate-write \
    --output "$TMP/duplicate-write-score.json" > /dev/null
python3 - "$TMP/duplicate-write-score.json" <<'PY'
import json, sys

score = json.load(open(sys.argv[1], encoding="utf-8"))
assert score["quality"]["stateChangingToolMiscalledRate"] > 0
assert score["toolCallCoverage"]["multiToolCaseCount"] == 1
PY

echo "11/15 later and mixed-format Tool calls cannot hide from safety scoring"
python3 - "$ROOT" "$TMP/oracle.jsonl" "$TMP/hidden-call.jsonl" <<'PY'
import importlib.util, json, sys
from pathlib import Path

root, source, target = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
spec = importlib.util.spec_from_file_location("h", root / "scripts/run-model-eval-harness.py")
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines()]
case = next(row for row in h.load_rows(root / "models/eval/korean-tool-use-v1.jsonl") if row["id"] == "calendar-query-01")
content = (
    "<|tool_call_start|>[calendar_query(start='2026-08-23T10:00', end='2026-08-24T00:00')]<|tool_call_end|>"
    '<tool_call>{"name":"calendar_create_event","arguments":{"title":"unsafe","start":"x","end":"y"}}</tool_call>'
)
calls = h.parse_tool_calls({}, content, "auto")
assert [call["name"] for call in calls] == ["calendar_query", "calendar_create_event"], calls
prediction, _ = h.derive_prediction(case, calls, "")
rows[1] = prediction
target.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
PY
python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/hidden-call.jsonl" --model-label hidden-call \
    --output "$TMP/hidden-call-score.json" > /dev/null
python3 - "$TMP/hidden-call-score.json" <<'PY'
import json, sys
score = json.load(open(sys.argv[1], encoding="utf-8"))
assert score["quality"]["stateChangingToolMiscalledRate"] > 0
assert score["toolCallCoverage"]["multiToolCaseCount"] == 1
PY

echo "12/15 missing required or unknown Tool arguments are rejected"
python3 - "$ROOT" "$TMP/oracle.jsonl" "$TMP/schema-invalid.jsonl" <<'PY'
import importlib.util, json, sys
from pathlib import Path

root, source, target = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
spec = importlib.util.spec_from_file_location("h", root / "scripts/run-model-eval-harness.py")
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines()]
case = next(row for row in h.load_rows(root / "models/eval/korean-tool-use-v1.jsonl") if row["id"] == "web-search-01")
prediction, _ = h.derive_prediction(case, [{"name": "web_search", "arguments": {}}], "")
assert prediction["tool"] is None and prediction["rejected_tool_call_count"] == 1
rows[13] = prediction
target.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
PY
python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/schema-invalid.jsonl" --model-label schema-invalid \
    --output "$TMP/schema-invalid-score.json" > /dev/null
python3 - "$TMP/schema-invalid-score.json" <<'PY'
import json, sys
score = json.load(open(sys.argv[1], encoding="utf-8"))
assert score["toolCallCoverage"]["rejectedToolCallCount"] == 1
PY

echo "13/15 audit-controlled review flags cannot erase corpus-wide review"
python3 - "$TMP/audited.audit.jsonl" "$TMP/no-review.audit.jsonl" <<'PY'
import json, sys
source, target = sys.argv[1:]
rows = [json.loads(line) for line in open(source, encoding="utf-8")]
for row in rows:
    row["needsReview"] = False
    row["reviewed"] = False
with open(target, "w", encoding="utf-8") as output:
    for row in rows:
        output.write(json.dumps(row, ensure_ascii=False) + "\n")
PY
python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/audited.jsonl" --audit "$TMP/no-review.audit.jsonl" \
    --model-label audited --output "$TMP/no-review-score.json" > /dev/null
python3 - "$TMP/no-review-score.json" <<'PY'
import json, sys
score = json.load(open(sys.argv[1], encoding="utf-8"))
assert score["reviewCoverage"] == {
    "source": "audit-unreviewed",
    "requiredCaseCount": 26,
    "reviewedCaseCount": 0,
}
PY
no_review_status=0
python3 "$ROOT/scripts/gate-model-eval.py" \
    --score "$TMP/no-review-score.json" --profile quality > /dev/null || no_review_status=$?
if [[ "$no_review_status" -ne 2 ]]; then
  echo "cleared audit review flags unexpectedly passed" >&2
  exit 1
fi

echo "14/15 mixed run parameters are rejected before scoring"
python3 - "$TMP/audited.audit.jsonl" "$TMP/mixed-run.audit.jsonl" <<'PY'
import json, sys
source, target = sys.argv[1:]
rows = [json.loads(line) for line in open(source, encoding="utf-8")]
rows[-1]["seed"] = 99
with open(target, "w", encoding="utf-8") as output:
    for row in rows:
        output.write(json.dumps(row, ensure_ascii=False) + "\n")
PY
if python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/audited.jsonl" --audit "$TMP/mixed-run.audit.jsonl" \
    --model-label audited > /dev/null 2>&1; then
  echo "mixed evaluation runs unexpectedly scored as one run" >&2
  exit 1
fi

echo "15/15 duplicate JSON keys and non-finite constants are rejected"
python3 - "$TMP/oracle.jsonl" "$TMP/duplicate-key.jsonl" <<'PY'
import sys
source, target = sys.argv[1:]
lines = open(source, encoding="utf-8").read().splitlines()
needle = '"response_language": "ko"'
assert needle in lines[0]
lines[0] = lines[0].replace(needle, needle + ', "response_language": "ko"', 1)
open(target, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
if python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/duplicate-key.jsonl" --model-label duplicate-key \
    > /dev/null 2>&1; then
  echo "duplicate JSON key unexpectedly scored" >&2
  exit 1
fi
python3 - "$TMP/oracle.jsonl" "$TMP/nonfinite.jsonl" <<'PY'
import sys
source, target = sys.argv[1:]
lines = open(source, encoding="utf-8").read().splitlines()
lines[0] = lines[0][:-1] + ', "turn_ms": NaN}'
open(target, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
if python3 "$ROOT/scripts/score-model-eval.py" \
    --predictions "$TMP/nonfinite.jsonl" --model-label nonfinite \
    > /dev/null 2>&1; then
  echo "non-finite JSON constant unexpectedly scored" >&2
  exit 1
fi

echo "host model-eval harness checks passed"
