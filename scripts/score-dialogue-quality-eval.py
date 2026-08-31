#!/usr/bin/env python3
"""Score synthetic dialogue answers with bounded deterministic lexical checks."""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import json
import re
import sys
from pathlib import Path


VALIDATOR_PATH = Path(__file__).with_name("validate-dialogue-quality-corpus.py")
VALIDATOR_SPEC = importlib.util.spec_from_file_location(
    "personal_edge_dialogue_quality_validator",
    VALIDATOR_PATH,
)
if VALIDATOR_SPEC is None or VALIDATOR_SPEC.loader is None:
    raise RuntimeError("dialogue-quality validator is unavailable")
VALIDATOR = importlib.util.module_from_spec(VALIDATOR_SPEC)
VALIDATOR_SPEC.loader.exec_module(VALIDATOR)

EXPECTED_PREDICTION_KEYS = {"id", "answer", "humanReview"}
HUMAN_REVIEW_DIMENSIONS = (
    "intentCaptured",
    "contextHandled",
    "constraintsSatisfied",
    "unsupportedClaimsAbsent",
    "formatFollowed",
    "directAndUseful",
)
EXPECTED_HUMAN_REVIEW_KEYS = set(HUMAN_REVIEW_DIMENSIONS)
MAX_ANSWER_BYTES = 16 * 1024
MAX_MODEL_LABEL_BYTES = 256
SENTENCE_BOUNDARY = re.compile(r"[.!?。！？]+|\n+")
GENERIC_NON_ANSWER_MARKERS = tuple(
    VALIDATOR.normalized(marker)
    for marker in (
        "모르겠습니다",
        "알 수 없습니다",
        "답변할 수 없습니다",
        "정보가 부족합니다",
        "정보가 더 필요합니다",
        "요청 처리를 완료했습니다",
        "명확하지 않습니다",
    )
)
CLARIFICATION_SUBJECT_MARKERS = tuple(
    VALIDATOR.normalized(marker)
    for marker in ("어느", "어떤", "무엇", "둘 중", "오로라와 코발트 중")
)
CLARIFICATION_REQUEST_MARKERS = tuple(
    VALIDATOR.normalized(marker)
    for marker in ("알려", "선택", "특정", "확인", "말해")
)


def ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 6) if denominator else None


def sentence_count(answer: str) -> int:
    return sum(bool(part.strip()) for part in SENTENCE_BOUNDARY.split(answer))


def observed_non_answer(answer: str) -> bool:
    compact = VALIDATOR.normalized(answer)
    if not compact:
        return True
    if any(marker in compact for marker in GENERIC_NON_ANSWER_MARKERS):
        return True
    return (
        any(marker in compact for marker in CLARIFICATION_SUBJECT_MARKERS)
        and any(marker in compact for marker in CLARIFICATION_REQUEST_MARKERS)
    )


def score(corpus_path: Path, predictions_path: Path, model_label: str) -> dict[str, object]:
    VALIDATOR.safe_text(model_label, "model-label", MAX_MODEL_LABEL_BYTES)
    with contextlib.redirect_stdout(io.StringIO()):
        cases = VALIDATOR.validate(corpus_path)
    prediction_rows = VALIDATOR.load_rows(predictions_path)
    predictions: dict[str, dict[str, object]] = {}
    case_ids = {case["id"] for case in cases}
    for row in prediction_rows:
        case_id = row.get("id") if isinstance(row, dict) else None
        if not isinstance(row, dict) or set(row) != EXPECTED_PREDICTION_KEYS:
            raise ValueError(f"{case_id or '?'}: prediction keys are invalid")
        case_id = VALIDATOR.safe_text(row["id"], "prediction.id", 96)
        if case_id not in case_ids or case_id in predictions:
            raise ValueError("prediction ids must be unique fixed corpus ids")
        answer = VALIDATOR.safe_text(
            row["answer"],
            f"{case_id}.answer",
            MAX_ANSWER_BYTES,
            allow_empty=True,
        )
        review = row["humanReview"]
        if review is not None:
            if not isinstance(review, dict) or set(review) != EXPECTED_HUMAN_REVIEW_KEYS:
                raise ValueError(f"{case_id}.humanReview keys are invalid")
            for dimension in HUMAN_REVIEW_DIMENSIONS:
                if not isinstance(review[dimension], bool):
                    raise ValueError(
                        f"{case_id}.humanReview.{dimension} must be boolean"
                    )
        predictions[case_id] = {
            "answer": answer,
            "humanReview": review,
        }
    missing = case_ids - set(predictions)
    if missing or len(predictions) != len(cases):
        raise ValueError(f"predictions must cover every fixed case exactly once; missing={sorted(missing)}")

    required_fact_matches = 0
    required_fact_total = 0
    any_of_matches = 0
    any_of_total = 0
    forbidden_avoided = 0
    forbidden_total = 0
    sentence_matches = 0
    non_answer_matches = 0
    present_answers = 0
    passed_cases = 0
    reviewed_cases = 0
    human_review_passed_cases = 0
    human_review_failed_case_ids: list[str] = []
    case_results: list[dict[str, object]] = []
    mismatches: list[dict[str, object]] = []

    for case in cases:
        case_id = case["id"]
        expected = case["expected"]
        prediction = predictions[case_id]
        answer = prediction["answer"]
        compact = VALIDATOR.normalized(answer)
        present = bool(answer.strip())
        present_answers += int(present)
        human_review = prediction["humanReview"]
        reviewed = human_review is not None
        human_review_passed = reviewed and all(
            human_review[dimension] for dimension in HUMAN_REVIEW_DIMENSIONS
        )
        reviewed_cases += int(reviewed)
        human_review_passed_cases += int(human_review_passed)
        if reviewed and not human_review_passed:
            human_review_failed_case_ids.append(case_id)

        missing_required = [
            fact for fact in expected["requiredFacts"]
            if VALIDATOR.normalized(fact) not in compact
        ]
        required_fact_total += len(expected["requiredFacts"])
        required_fact_matches += len(expected["requiredFacts"]) - len(missing_required)

        missing_any_of = [
            alternatives for alternatives in expected["requiredAnyOf"]
            if not any(VALIDATOR.normalized(option) in compact for option in alternatives)
        ]
        any_of_total += len(expected["requiredAnyOf"])
        any_of_matches += len(expected["requiredAnyOf"]) - len(missing_any_of)

        present_forbidden = [
            fact for fact in expected["forbiddenFacts"]
            if VALIDATOR.normalized(fact) in compact
        ]
        forbidden_total += len(expected["forbiddenFacts"])
        forbidden_avoided += len(expected["forbiddenFacts"]) - len(present_forbidden)

        sentences = sentence_count(answer)
        sentence_ok = sentences <= expected["maxSentences"]
        sentence_matches += int(sentence_ok)
        non_answer = observed_non_answer(answer)
        non_answer_ok = non_answer == expected["nonAnswer"]
        non_answer_matches += int(non_answer_ok)
        required_ok = not missing_required
        any_of_ok = not missing_any_of
        forbidden_ok = not present_forbidden
        passed = (
            present
            and required_ok
            and any_of_ok
            and forbidden_ok
            and sentence_ok
            and non_answer_ok
        )
        passed_cases += int(passed)
        result = {
            "id": case_id,
            "category": case["category"],
            "passed": passed,
            "checks": {
                "answerPresent": present,
                "requiredFacts": required_ok,
                "requiredAnyOf": any_of_ok,
                "forbiddenFacts": forbidden_ok,
                "maxSentences": sentence_ok,
                "nonAnswer": non_answer_ok,
            },
            "missingRequiredFacts": missing_required,
            "missingRequiredAnyOf": missing_any_of,
            "presentForbiddenFacts": present_forbidden,
            "sentenceCount": sentences,
            "expectedNonAnswer": expected["nonAnswer"],
            "observedNonAnswer": non_answer,
            "humanReview": human_review,
            "humanReviewPassed": human_review_passed if reviewed else None,
        }
        case_results.append(result)
        if not passed:
            mismatches.append(result)

    total = len(cases)
    lexical_screen_passed = passed_cases == total
    complete_human_review = reviewed_cases == total
    all_human_dimensions_passed = (
        complete_human_review and human_review_passed_cases == total
    )
    return {
        "schemaVersion": 2,
        "modelLabel": model_label,
        "corpusCanonicalSha256": VALIDATOR.EXPECTED_CANONICAL_SHA256,
        "caseCount": total,
        "lexicalScreenPassed": lexical_screen_passed,
        "reviewedDialogueSetPassed": (
            lexical_screen_passed and all_human_dimensions_passed
        ),
        "promotionEligible": False,
        "claimBoundary": (
            "Deterministic lexical checks plus the fixed human rubric cover only this synthetic "
            "set. They are not general semantic proof or an Android/Fold8 device receipt."
        ),
        "quality": {
            "casePassRate": ratio(passed_cases, total),
            "answerPresenceRate": ratio(present_answers, total),
            "requiredFactRecall": ratio(required_fact_matches, required_fact_total),
            "requiredAnyOfSatisfaction": ratio(any_of_matches, any_of_total),
            "forbiddenFactAvoidance": ratio(forbidden_avoided, forbidden_total),
            "maxSentenceCompliance": ratio(sentence_matches, total),
            "nonAnswerAccuracy": ratio(non_answer_matches, total),
        },
        "humanReviewCoverage": {
            "dimensions": list(HUMAN_REVIEW_DIMENSIONS),
            "requiredForReviewedSetPass": True,
            "reviewedCaseCount": reviewed_cases,
            "requiredCaseCount": total,
            "passedCaseCount": human_review_passed_cases,
            "complete": complete_human_review,
            "allDimensionsPassed": all_human_dimensions_passed,
            "failedCaseIds": human_review_failed_case_ids,
        },
        "deviceExecution": {
            "requiredForOnDeviceClaim": True,
            "performedByThisScorer": False,
        },
        "cases": case_results,
        "mismatches": mismatches,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--corpus",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "models/eval/korean-dialogue-quality-v1.jsonl",
    )
    parser.add_argument("--predictions", required=True, type=Path)
    parser.add_argument("--model-label", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = score(args.corpus, args.predictions, args.model_label)
        encoded = json.dumps(
            result,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
            allow_nan=False,
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
