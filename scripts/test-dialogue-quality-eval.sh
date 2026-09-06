#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_root_raw="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-dialogue-eval.XXXXXX")"
fixture_root="$(cd "$fixture_root_raw" && pwd)"
fixture_parent="$(cd "$(dirname "$fixture_root")" && pwd)"

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ "$fixture_root" == "$fixture_parent"/personal-edge-dialogue-eval.* &&
          -d "$fixture_root" && ! -L "$fixture_root" ]]; then
        rm -rf -- "$fixture_root"
    else
        echo "Refusing unsafe fixture cleanup path: $fixture_root" >&2
        status=1
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
    echo "FAIL $*" >&2
    exit 1
}

expect_failure() {
    local log_path="$1"
    shift
    if "$@" >"$log_path" 2>&1; then
        fail "command unexpectedly succeeded: $*"
    fi
}

corpus="$project_root/models/eval/korean-dialogue-quality-v1.jsonl"
predictions="$fixture_root/predictions.jsonl"
score_one="$fixture_root/score-one.json"
score_two="$fixture_root/score-two.json"
reviewed_predictions="$fixture_root/reviewed-predictions.jsonl"
reviewed_score="$fixture_root/reviewed-score.json"
failed_review_predictions="$fixture_root/failed-review-predictions.jsonl"
failed_review_score="$fixture_root/failed-review-score.json"
invalid_review_predictions="$fixture_root/invalid-review-predictions.jsonl"
degraded="$fixture_root/degraded.jsonl"
degraded_score="$fixture_root/degraded-score.json"
negated="$fixture_root/negated.jsonl"
negated_score="$fixture_root/negated-score.json"
mutated_corpus="$fixture_root/mutated-corpus.jsonl"
duplicate_corpus="$fixture_root/duplicate-corpus.jsonl"
duplicate_predictions="$fixture_root/duplicate-predictions.jsonl"
unexpected_predictions="$fixture_root/unexpected-predictions.jsonl"
unknown_id_predictions="$fixture_root/unknown-id-predictions.jsonl"
nonfinite_predictions="$fixture_root/nonfinite-predictions.jsonl"
missing_predictions="$fixture_root/missing-predictions.jsonl"

python3 "$project_root/scripts/validate-dialogue-quality-corpus.py" "$corpus" >/dev/null

python3 - "$predictions" <<'PY'
import json
import sys

answers = {
    "dialogue-long-salience-01": (
        "ORBIT-7을 선택합니다. 오프라인으로 동작하며 지연이 50ms 이하이기 때문입니다."
    ),
    "dialogue-compound-constraints-01": (
        "강릉으로 기차를 이용하면 20만원 이하 예산으로 반려견 동반 여행을 구성할 수 있습니다."
    ),
    "dialogue-context-reference-01": "두 번째 후보 LUNAR-8의 강점은 배터리 지속 시간입니다.",
    "dialogue-latest-correction-01": "최종 발표는 9월 16일 한강 회의실에서 열립니다.",
    "dialogue-summary-recent-conflict-01": "현재 확정 색상은 청록이고 코드는 TEAL-9입니다.",
    "dialogue-irrelevant-history-01": "문서 제목은 PAPER-6이고 마감은 금요일입니다.",
    "dialogue-clarification-01": "어느 회의를 옮길지 알려 주세요?",
    "dialogue-conversation-isolation-01": "이 대화의 고객 코드는 ALPHA-31이고 선호 색상은 민트입니다.",
}
with open(sys.argv[1], "w", encoding="utf-8", newline="\n") as target:
    for case_id, answer in answers.items():
        target.write(json.dumps({
            "id": case_id,
            "answer": answer,
            "humanReview": None,
        }, ensure_ascii=False, separators=(",", ":")) + "\n")
PY

python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
    --corpus "$corpus" \
    --predictions "$predictions" \
    --model-label synthetic-fixture >"$score_one"
python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
    --corpus "$corpus" \
    --predictions "$predictions" \
    --model-label synthetic-fixture >"$score_two"
cmp -s "$score_one" "$score_two" || fail "dialogue score output is not deterministic"

python3 - "$score_one" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    score = json.load(source)
assert score["schemaVersion"] == 3
assert score["caseCount"] == 8
assert score["lexicalScreenPassed"] is True
assert score["reviewedDialogueSetPassed"] is False
assert score["promotionEligible"] is False
assert score["quality"]["casePassRate"] == 1.0
assert score["quality"]["requiredFactRecall"] == 1.0
assert score["quality"]["requiredAnyOfSatisfaction"] == 1.0
assert score["quality"]["requiredClaimAffirmationRate"] == 1.0
assert score["quality"]["forbiddenFactAvoidance"] == 1.0
assert score["quality"]["maxSentenceCompliance"] == 1.0
assert score["quality"]["nonAnswerAccuracy"] == 1.0
assert score["humanReviewCoverage"]["complete"] is False
assert score["humanReviewCoverage"]["allDimensionsPassed"] is False
assert score["deviceExecution"]["performedByThisScorer"] is False
assert score["mismatches"] == []
PY

python3 - "$predictions" "$negated" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    rows = [json.loads(line) for line in source]
for row in rows:
    if row["id"] == "dialogue-long-salience-01":
        # This used to pass every automatic check: all required substrings are present, no
        # forbidden candidate appears, and the answer stays within two sentences. Every required
        # claim is nevertheless explicitly denied.
        row["answer"] = (
            "ORBIT-7은 오프라인이 아니며 지연이 50ms 이하도 아닙니다. "
            "ORBIT-7을 선택하지 않겠습니다."
        )
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as target:
    for row in rows:
        target.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
PY
python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
    --corpus "$corpus" \
    --predictions "$negated" \
    --model-label negated-required-fixture >"$negated_score"
python3 - "$negated_score" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    score = json.load(source)
assert score["lexicalScreenPassed"] is False
assert score["quality"]["requiredFactRecall"] == 1.0
assert score["quality"]["requiredAnyOfSatisfaction"] == 1.0
assert score["quality"]["forbiddenFactAvoidance"] == 1.0
assert score["quality"]["maxSentenceCompliance"] == 1.0
assert score["quality"]["nonAnswerAccuracy"] == 1.0
assert score["quality"]["requiredClaimAffirmationRate"] == 0.875
assert len(score["mismatches"]) == 1
case = score["mismatches"][0]
assert case["id"] == "dialogue-long-salience-01"
assert case["checks"]["requiredFacts"] is True
assert case["checks"]["requiredAnyOf"] is True
assert case["checks"]["requiredClaimsAffirmed"] is False
assert set(case["negatedRequiredClaims"]) >= {"ORBIT-7", "오프라인", "50ms 이하", "선택"}
PY

python3 - "$predictions" "$reviewed_predictions" <<'PY'
import json
import sys

dimensions = (
    "intentCaptured",
    "contextHandled",
    "constraintsSatisfied",
    "unsupportedClaimsAbsent",
    "formatFollowed",
    "directAndUseful",
)
with open(sys.argv[1], encoding="utf-8") as source:
    rows = [json.loads(line) for line in source]
for row in rows:
    row["humanReview"] = {dimension: True for dimension in dimensions}
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as target:
    for row in rows:
        target.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
PY
python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
    --corpus "$corpus" \
    --predictions "$reviewed_predictions" \
    --model-label reviewed-fixture >"$reviewed_score"
python3 - "$reviewed_score" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    score = json.load(source)
assert score["lexicalScreenPassed"] is True
assert score["reviewedDialogueSetPassed"] is True
assert score["humanReviewCoverage"]["complete"] is True
assert score["humanReviewCoverage"]["allDimensionsPassed"] is True
assert score["humanReviewCoverage"]["reviewedCaseCount"] == 8
assert score["humanReviewCoverage"]["passedCaseCount"] == 8
assert score["humanReviewCoverage"]["failedCaseIds"] == []
PY

python3 - \
    "$reviewed_predictions" \
    "$failed_review_predictions" \
    "$invalid_review_predictions" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()]
failed = [dict(row) for row in rows]
failed[0]["humanReview"] = dict(failed[0]["humanReview"])
failed[0]["humanReview"]["intentCaptured"] = False
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in failed),
    encoding="utf-8",
    newline="\n",
)
invalid = [dict(row) for row in rows]
invalid[0]["humanReview"] = dict(invalid[0]["humanReview"])
del invalid[0]["humanReview"]["formatFollowed"]
pathlib.Path(sys.argv[3]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in invalid),
    encoding="utf-8",
    newline="\n",
)
PY
python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
    --corpus "$corpus" \
    --predictions "$failed_review_predictions" \
    --model-label failed-review-fixture >"$failed_review_score"
python3 - "$failed_review_score" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    score = json.load(source)
assert score["lexicalScreenPassed"] is True
assert score["reviewedDialogueSetPassed"] is False
assert score["humanReviewCoverage"]["complete"] is True
assert score["humanReviewCoverage"]["allDimensionsPassed"] is False
assert score["humanReviewCoverage"]["passedCaseCount"] == 7
assert score["humanReviewCoverage"]["failedCaseIds"] == ["dialogue-long-salience-01"]
PY
expect_failure "$fixture_root/invalid-review.log" \
    python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
        --corpus "$corpus" \
        --predictions "$invalid_review_predictions" \
        --model-label invalid-review

python3 - "$predictions" "$degraded" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    rows = [json.loads(line) for line in source]
for row in rows:
    if row["id"] == "dialogue-long-salience-01":
        row["answer"] = "요청 처리를 완료했습니다."
    elif row["id"] == "dialogue-latest-correction-01":
        row["answer"] = "발표는 9월 14일 남산 회의실에서 열립니다."
    elif row["id"] == "dialogue-compound-constraints-01":
        row["answer"] = "강릉입니다. 기차입니다. 반려견 동반입니다. 예산은 20만원 이하입니다."
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as target:
    for row in rows:
        target.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
PY
python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
    --corpus "$corpus" \
    --predictions "$degraded" \
    --model-label degraded-fixture >"$degraded_score"
python3 - "$degraded_score" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    score = json.load(source)
assert score["lexicalScreenPassed"] is False
assert score["quality"]["casePassRate"] < 1.0
assert score["quality"]["requiredFactRecall"] < 1.0
assert score["quality"]["forbiddenFactAvoidance"] < 1.0
assert score["quality"]["maxSentenceCompliance"] < 1.0
assert score["quality"]["nonAnswerAccuracy"] < 1.0
assert len(score["mismatches"]) == 3
PY

python3 - "$corpus" "$mutated_corpus" "$duplicate_corpus" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
pathlib.Path(sys.argv[2]).write_text(
    source.replace("ORBIT-7을 선택하고", "ORBIT-7을 반드시 선택하고", 1),
    encoding="utf-8",
    newline="\n",
)
first, rest = source.split("\n", 1)
duplicate = first.replace(
    '{"id":"dialogue-long-salience-01",',
    '{"id":"dialogue-long-salience-01","id":"dialogue-long-salience-01",',
    1,
)
pathlib.Path(sys.argv[3]).write_text(duplicate + "\n" + rest, encoding="utf-8", newline="\n")
PY
expect_failure "$fixture_root/mutated-corpus.log" \
    python3 "$project_root/scripts/validate-dialogue-quality-corpus.py" "$mutated_corpus"
expect_failure "$fixture_root/duplicate-corpus.log" \
    python3 "$project_root/scripts/validate-dialogue-quality-corpus.py" "$duplicate_corpus"

python3 - \
    "$predictions" \
    "$duplicate_predictions" \
    "$unexpected_predictions" \
    "$unknown_id_predictions" \
    "$nonfinite_predictions" \
    "$missing_predictions" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
first, rest = source.split("\n", 1)
duplicate = first.replace(
    '"answer":',
    '"answer":"중복 값","answer":',
    1,
)
pathlib.Path(sys.argv[2]).write_text(duplicate + "\n" + rest, encoding="utf-8", newline="\n")
rows = [json.loads(line) for line in source.splitlines()]
unexpected_rows = [dict(row) for row in rows]
unexpected_rows[0]["unexpected"] = True
pathlib.Path(sys.argv[3]).write_text(
    "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in unexpected_rows),
    encoding="utf-8",
    newline="\n",
)
unknown_id_rows = [dict(row) for row in rows]
unknown_id_rows[0]["id"] = "dialogue-unknown-01"
pathlib.Path(sys.argv[4]).write_text(
    "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in unknown_id_rows),
    encoding="utf-8",
    newline="\n",
)
pathlib.Path(sys.argv[5]).write_text(
    source.replace('"humanReview":null', '"humanReview":NaN', 1),
    encoding="utf-8",
    newline="\n",
)
pathlib.Path(sys.argv[6]).write_text(
    "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows[1:]),
    encoding="utf-8",
    newline="\n",
)
PY
expect_failure "$fixture_root/duplicate-predictions.log" \
    python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
        --corpus "$corpus" --predictions "$duplicate_predictions" --model-label duplicate
expect_failure "$fixture_root/unexpected-predictions.log" \
    python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
        --corpus "$corpus" --predictions "$unexpected_predictions" --model-label unexpected
expect_failure "$fixture_root/unknown-id-predictions.log" \
    python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
        --corpus "$corpus" --predictions "$unknown_id_predictions" --model-label unknown-id
expect_failure "$fixture_root/nonfinite-predictions.log" \
    python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
        --corpus "$corpus" --predictions "$nonfinite_predictions" --model-label nonfinite
expect_failure "$fixture_root/missing-predictions.log" \
    python3 "$project_root/scripts/score-dialogue-quality-eval.py" \
        --corpus "$corpus" --predictions "$missing_predictions" --model-label missing

echo "OK dialogue quality corpus, lexical scorer, claim boundary, and strict fixtures"
