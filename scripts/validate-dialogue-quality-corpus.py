#!/usr/bin/env python3
"""Strictly validate the fixed synthetic Korean dialogue-quality corpus."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import unicodedata
from pathlib import Path


EXPECTED_CASE_IDS = (
    "dialogue-long-salience-01",
    "dialogue-compound-constraints-01",
    "dialogue-context-reference-01",
    "dialogue-latest-correction-01",
    "dialogue-summary-recent-conflict-01",
    "dialogue-irrelevant-history-01",
    "dialogue-clarification-01",
    "dialogue-conversation-isolation-01",
)
REQUIRED_CATEGORIES = {
    "long_prompt_salience",
    "compound_constraints",
    "relevant_context_reference",
    "latest_correction",
    "summary_vs_recent_conflict",
    "irrelevant_history_resistance",
    "clarification",
    "conversation_isolation",
}
EXPECTED_TOP_LEVEL_KEYS = {"id", "category", "context", "prompt", "expected"}
EXPECTED_CONTEXT_KEYS = {"summary", "recentMessages", "excludedMessages"}
EXPECTED_MESSAGE_KEYS = {"role", "text"}
EXPECTED_RESULT_KEYS = {
    "requiredFacts",
    "requiredAnyOf",
    "forbiddenFacts",
    "maxSentences",
    "nonAnswer",
}
MESSAGE_ROLES = {"user", "assistant"}
MAX_FILE_BYTES = 512 * 1024
MAX_LINE_BYTES = 64 * 1024
MAX_PROMPT_BYTES = 16 * 1024
MAX_CONTEXT_TEXT_BYTES = 8 * 1024
MAX_FACT_BYTES = 256
MAX_FACTS_PER_LIST = 16
MAX_ANY_OF_GROUPS = 8
MAX_ANY_OF_ALTERNATIVES = 8
EXPECTED_CANONICAL_SHA256 = "b423bf13b46f1bcb6e843d3df07d17f339b2681bd15ef2964e167654851c7d0b"


class DuplicateKeyError(ValueError):
    pass


def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def reject_non_finite(value: str) -> None:
    raise ValueError(f"non-finite JSON number: {value}")


def load_rows(path: Path) -> list[dict[str, object]]:
    if not path.is_file() or path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("corpus must be a bounded regular file")
    rows: list[dict[str, object]] = []
    with path.open(encoding="utf-8", newline="") as source:
        for line_number, line in enumerate(source, 1):
            if not line.endswith("\n"):
                raise ValueError(f"line {line_number}: final newline is required")
            if len(line.encode("utf-8")) > MAX_LINE_BYTES:
                raise ValueError(f"line {line_number}: JSONL row is too large")
            if not line.strip():
                raise ValueError(f"line {line_number}: blank rows are forbidden")
            try:
                row = json.loads(
                    line,
                    object_pairs_hook=reject_duplicate_keys,
                    parse_constant=reject_non_finite,
                )
            except (DuplicateKeyError, json.JSONDecodeError, ValueError) as error:
                raise ValueError(f"line {line_number}: invalid strict JSON: {error}") from None
            if not isinstance(row, dict):
                raise ValueError(f"line {line_number}: every row must be an object")
            rows.append(row)
    return rows


def safe_text(value: object, label: str, maximum_bytes: int, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a string")
    if not allow_empty and not value.strip():
        raise ValueError(f"{label} must be non-empty")
    if len(value.encode("utf-8")) > maximum_bytes:
        raise ValueError(f"{label} exceeds its UTF-8 bound")
    for character in value:
        category = unicodedata.category(character)
        if category == "Cf" or (category == "Cc" and character not in "\n\t"):
            raise ValueError(f"{label} contains a forbidden control or format character")
    return value


def normalized(value: str) -> str:
    canonical = unicodedata.normalize("NFKC", value).casefold()
    return "".join(character for character in canonical if not character.isspace())


def validate_fact_list(value: object, label: str, *, allow_empty: bool) -> list[str]:
    if not isinstance(value, list) or len(value) > MAX_FACTS_PER_LIST:
        raise ValueError(f"{label} must be a bounded list")
    if not allow_empty and not value:
        raise ValueError(f"{label} must not be empty")
    facts = [
        safe_text(item, f"{label}[{index}]", MAX_FACT_BYTES)
        for index, item in enumerate(value)
    ]
    normalized_facts = [normalized(fact) for fact in facts]
    if len(set(normalized_facts)) != len(normalized_facts):
        raise ValueError(f"{label} contains normalized duplicates")
    return facts


def validate_messages(value: object, label: str) -> list[dict[str, str]]:
    if not isinstance(value, list) or len(value) > 16:
        raise ValueError(f"{label} must be a bounded list")
    messages: list[dict[str, str]] = []
    for index, item in enumerate(value):
        if not isinstance(item, dict) or set(item) != EXPECTED_MESSAGE_KEYS:
            raise ValueError(f"{label}[{index}] keys are invalid")
        role = item["role"]
        if role not in MESSAGE_ROLES:
            raise ValueError(f"{label}[{index}].role is invalid")
        text = safe_text(item["text"], f"{label}[{index}].text", MAX_CONTEXT_TEXT_BYTES)
        messages.append({"role": role, "text": text})
    return messages


def canonical_sha256(rows: list[dict[str, object]]) -> str:
    canonical = "\n".join(
        json.dumps(
            row,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        for row in rows
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def validate(path: Path) -> list[dict[str, object]]:
    rows = load_rows(path)
    if len(rows) != len(EXPECTED_CASE_IDS):
        raise ValueError(f"fixed corpus must contain exactly {len(EXPECTED_CASE_IDS)} rows")

    seen_ids: set[str] = set()
    categories: set[str] = set()
    for row in rows:
        if set(row) != EXPECTED_TOP_LEVEL_KEYS:
            raise ValueError(f"{row.get('id', '?')}: top-level keys are invalid")
        case_id = safe_text(row["id"], "id", 96)
        if case_id in seen_ids:
            raise ValueError(f"duplicate case id: {case_id}")
        seen_ids.add(case_id)
        category = safe_text(row["category"], f"{case_id}.category", 64)
        if category not in REQUIRED_CATEGORIES:
            raise ValueError(f"{case_id}: unknown category")
        categories.add(category)
        prompt = safe_text(row["prompt"], f"{case_id}.prompt", MAX_PROMPT_BYTES)

        context = row["context"]
        if not isinstance(context, dict) or set(context) != EXPECTED_CONTEXT_KEYS:
            raise ValueError(f"{case_id}: context keys are invalid")
        summary_value = context["summary"]
        summary = "" if summary_value is None else safe_text(
            summary_value,
            f"{case_id}.context.summary",
            MAX_CONTEXT_TEXT_BYTES,
        )
        recent = validate_messages(context["recentMessages"], f"{case_id}.context.recentMessages")
        excluded = validate_messages(
            context["excludedMessages"],
            f"{case_id}.context.excludedMessages",
        )

        expected = row["expected"]
        if not isinstance(expected, dict) or set(expected) != EXPECTED_RESULT_KEYS:
            raise ValueError(f"{case_id}: expected keys are invalid")
        required = validate_fact_list(
            expected["requiredFacts"],
            f"{case_id}.expected.requiredFacts",
            allow_empty=True,
        )
        forbidden = validate_fact_list(
            expected["forbiddenFacts"],
            f"{case_id}.expected.forbiddenFacts",
            allow_empty=False,
        )
        any_of_value = expected["requiredAnyOf"]
        if not isinstance(any_of_value, list) or len(any_of_value) > MAX_ANY_OF_GROUPS:
            raise ValueError(f"{case_id}: requiredAnyOf must be a bounded list")
        any_of: list[list[str]] = []
        for group_index, group in enumerate(any_of_value):
            if not isinstance(group, list) or not 2 <= len(group) <= MAX_ANY_OF_ALTERNATIVES:
                raise ValueError(f"{case_id}: requiredAnyOf[{group_index}] must contain 2-8 alternatives")
            alternatives = [
                safe_text(
                    item,
                    f"{case_id}.expected.requiredAnyOf[{group_index}][{item_index}]",
                    MAX_FACT_BYTES,
                )
                for item_index, item in enumerate(group)
            ]
            if len({normalized(item) for item in alternatives}) != len(alternatives):
                raise ValueError(f"{case_id}: requiredAnyOf[{group_index}] has duplicate alternatives")
            any_of.append(alternatives)
        max_sentences = expected["maxSentences"]
        if isinstance(max_sentences, bool) or not isinstance(max_sentences, int) or not 1 <= max_sentences <= 8:
            raise ValueError(f"{case_id}: maxSentences must be an integer from 1 through 8")
        non_answer = expected["nonAnswer"]
        if not isinstance(non_answer, bool):
            raise ValueError(f"{case_id}: nonAnswer must be boolean")
        if not required and not any_of:
            raise ValueError(f"{case_id}: at least one positive lexical requirement is required")

        positive = {normalized(item) for item in required}
        positive.update(normalized(item) for group in any_of for item in group)
        negative = {normalized(item) for item in forbidden}
        if positive & negative:
            raise ValueError(f"{case_id}: positive and forbidden facts overlap")

        allowed_source = normalized(
            "\n".join([summary, *(message["text"] for message in recent), prompt])
        )
        excluded_source = normalized("\n".join(message["text"] for message in excluded))
        if any(normalized(fact) not in allowed_source for fact in required):
            raise ValueError(f"{case_id}: requiredFacts must be grounded in submitted context")
        if category != "clarification" and any(
            not any(normalized(option) in allowed_source for option in group) for group in any_of
        ):
            raise ValueError(f"{case_id}: each requiredAnyOf group needs a grounded alternative")
        if category == "conversation_isolation" and any(
            normalized(fact) not in excluded_source for fact in forbidden
        ):
            raise ValueError(f"{case_id}: isolation decoys must come from excluded messages")
        if category != "clarification" and category != "conversation_isolation" and not any(
            normalized(fact) in allowed_source for fact in forbidden
        ):
            raise ValueError(f"{case_id}: forbiddenFacts must include a grounded synthetic decoy")

        if category == "long_prompt_salience" and len(prompt.encode("utf-8")) < 1_500:
            raise ValueError(f"{case_id}: long-prompt case must be at least 1,500 UTF-8 bytes")
        if category == "conversation_isolation":
            if not excluded:
                raise ValueError(f"{case_id}: isolation case requires excluded conversation rows")
        elif excluded:
            raise ValueError(f"{case_id}: only isolation cases may carry excluded messages")
        if category == "summary_vs_recent_conflict" and (not summary or not recent):
            raise ValueError(f"{case_id}: summary conflict requires summary and recent context")
        if category == "clarification" and not non_answer:
            raise ValueError(f"{case_id}: clarification must expect a non-answer")
        if category != "clarification" and non_answer:
            raise ValueError(f"{case_id}: only clarification may expect a non-answer")

    actual_ids = tuple(row["id"] for row in rows)
    if actual_ids != EXPECTED_CASE_IDS:
        raise ValueError("fixed case ids or ordering changed; create a versioned corpus")
    if categories != REQUIRED_CATEGORIES:
        raise ValueError(f"fixed corpus categories disagree: {sorted(categories)}")
    digest = canonical_sha256(rows)
    if digest != EXPECTED_CANONICAL_SHA256:
        raise ValueError(
            "fixed corpus content changed; create a versioned corpus "
            f"(observed canonical SHA-256 {digest})"
        )
    print(f"OK {len(rows)} dialogue cases · {len(categories)} categories · {digest}")
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "corpus",
        nargs="?",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "models/eval/korean-dialogue-quality-v1.jsonl",
    )
    args = parser.parse_args()
    try:
        validate(args.corpus)
    except (OSError, ValueError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
