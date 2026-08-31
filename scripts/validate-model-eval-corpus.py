#!/usr/bin/env python3
"""Fail-closed structural validator for the fixed Korean tool-use JSONL corpus."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


TOOLS = {
    "calendar_query", "calendar_create_event", "calendar_update_event", "alarm_set",
    "alarm_next", "route_estimate", "web_search", "kakao_notification_search",
    "memory_remember", "commitment_propose", "reminder_create", "reminder_update",
    "reminder_cancel", "reminder_query",
}
WRITE_TOOLS = {
    "calendar_create_event", "calendar_update_event", "alarm_set", "memory_remember",
    "commitment_propose", "reminder_create", "reminder_update", "reminder_cancel",
}
REQUIRED_CATEGORIES = {
    "no_tool", "calendar_query", "calendar_create", "calendar_update", "ambiguous_datetime",
    "alarm_set", "alarm_query", "alarm_vs_reminder", "reminder_create", "reminder_query",
    "route", "web_search", "notification_search", "memory_write", "secret_refusal",
    "memory_refusal", "multi_tool", "tool_follow_up", "confirmation_refusal",
    "commitment_proposal", "unsupported_action", "unsafe_text", "response_language",
}
EXPECTED_CASE_IDS = (
    "general-knowledge-01",
    "calendar-query-01",
    "calendar-create-01",
    "calendar-update-01",
    "calendar-ambiguous-01",
    "alarm-set-01",
    "alarm-next-01",
    "alarm-date-guard-01",
    "reminder-recurring-01",
    "reminder-query-01",
    "reminder-vague-01",
    "route-01",
    "route-default-origin-01",
    "web-search-01",
    "kakao-search-01",
    "memory-preference-01",
    "memory-secret-01",
    "memory-temporary-01",
    "multi-tool-01",
    "tool-follow-up-01",
    "confirmation-refused-01",
    "commitment-proposal-01",
    "no-fake-success-01",
    "bidi-control-01",
    "language-default-01",
    "language-override-01",
)
EXPECTED_CANONICAL_SHA256 = "dfd27d52125e956b67579d9a1ea0131dd5ba4e83845382c60b8889e9c9d4fbd4"
ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9-]{2,79}\Z")
EXPECTED_KEYS = {
    "tool", "arguments", "clarification", "write", "final_state", "response_language",
}
RESPONSE_LANGUAGES = {"ko", "en"}
TRUSTED_TURN_CONTEXT_FORMAT = "production-no-history-v1"
TOOL_ARGUMENT_FIELDS = {
    "calendar_query": ({"start", "end"}, {"start", "end"}),
    "calendar_create_event": ({"title", "start", "end"}, {"title", "start", "end", "location"}),
    "calendar_update_event": ({"event_id"}, {"event_id", "title", "start", "end", "location"}),
    "alarm_set": ({"time"}, {"time", "label", "days"}),
    "alarm_next": (set(), set()),
    "route_estimate": ({"destination"}, {"destination", "origin"}),
    "web_search": ({"query"}, {"query"}),
    "kakao_notification_search": (set(), {"query", "within_days"}),
    "memory_remember": ({"content"}, {"content", "category", "valid_until", "zone_id", "supersedes_id"}),
    "commitment_propose": ({"summary"}, {"summary", "proposed_at", "zone_id"}),
    "reminder_create": (
        {"title", "trigger_at", "zone_id"},
        {"title", "trigger_at", "zone_id", "recurrence_rule", "precision", "lead_time_minutes", "escalation_policy"},
    ),
    "reminder_update": (
        {"reminder_id", "expected_version", "title", "trigger_at", "zone_id"},
        {"reminder_id", "expected_version", "title", "trigger_at", "zone_id", "recurrence_rule", "precision", "lead_time_minutes", "escalation_policy"},
    ),
    "reminder_cancel": ({"reminder_id", "expected_version"}, {"reminder_id", "expected_version"}),
    "reminder_query": (set(), {"limit"}),
}


def fail(message: str) -> None:
    raise ValueError(message)


def _reject_json_constant(value: str) -> None:
    fail(f"non-finite JSON constant is not allowed: {value}")


def _strict_json_object(pairs: list[tuple[str, object]]) -> dict:
    result: dict = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON object key is not allowed: {key}")
        result[key] = value
    return result


def strict_json_loads(raw: str) -> object:
    """Decode standards-compliant JSON while rejecting duplicate object keys."""
    return json.loads(
        raw,
        object_pairs_hook=_strict_json_object,
        parse_constant=_reject_json_constant,
    )


def trusted_turn_context(row: dict) -> str:
    """Mirror TurnContextBuilder's no-history/no-calendar production user-role payload."""
    return (
        f"[기기 정보] 현재={row['reference_time']} | 시간대={row['zone_id']}\n"
        "[신뢰 응답 언어 정책] 현재 요청에 응답 언어가 명시되면 그 언어로만 답하고, "
        "명시되지 않으면 한국어로만 답합니다.\n"
        f"[현재 사용자 요청]\n{row['prompt']}"
    )


def trusted_turn_context_sha256(row: dict) -> str:
    return hashlib.sha256(trusted_turn_context(row).encode("utf-8")).hexdigest()


def load_rows(path: Path) -> list[dict]:
    rows: list[dict] = []
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            fail(f"line {line_number}: blank lines are not allowed")
        try:
            value = strict_json_loads(raw)
        except (json.JSONDecodeError, ValueError) as error:
            detail = error.msg if isinstance(error, json.JSONDecodeError) else str(error)
            fail(f"line {line_number}: invalid JSON: {detail}")
        if not isinstance(value, dict):
            fail(f"line {line_number}: row must be an object")
        rows.append(value)
    return rows


def validate(path: Path) -> int:
    rows = load_rows(path)
    if len(rows) != len(EXPECTED_CASE_IDS):
        fail(f"fixed corpus must contain exactly {len(EXPECTED_CASE_IDS)} cases")
    seen: set[str] = set()
    categories: set[str] = set()
    for index, row in enumerate(rows, 1):
        required = {"id", "category", "reference_time", "zone_id", "prompt", "expected"}
        if set(row) != required:
            fail(f"row {index}: keys must be exactly {sorted(required)}")
        case_id = row["id"]
        if not isinstance(case_id, str) or not ID_PATTERN.fullmatch(case_id) or case_id in seen:
            fail(f"row {index}: invalid or duplicate id")
        seen.add(case_id)
        if row["zone_id"] != "Asia/Seoul" or row["reference_time"] != "2026-08-23T10:00:00+09:00":
            fail(f"{case_id}: reference clock and zone must stay fixed")
        prompt = row["prompt"]
        if not isinstance(prompt, str) or not 1 <= len(prompt) <= 600 or "\x00" in prompt:
            fail(f"{case_id}: prompt is empty, oversized, or contains NUL")
        category = row["category"]
        if not isinstance(category, str):
            fail(f"{case_id}: category must be a string")
        categories.add(category)
        expected = row["expected"]
        if not isinstance(expected, dict) or set(expected) != EXPECTED_KEYS:
            fail(f"{case_id}: expected keys must be exactly {sorted(EXPECTED_KEYS)}")
        tool = expected["tool"]
        if tool is not None and tool not in TOOLS:
            fail(f"{case_id}: unknown expected tool")
        arguments = expected["arguments"]
        if (tool is None and arguments is not None) or (tool is not None and not isinstance(arguments, dict)):
            fail(f"{case_id}: arguments must match tool presence")
        if tool is not None:
            required_fields, allowed_fields = TOOL_ARGUMENT_FIELDS[tool]
            argument_fields = set(arguments)
            if not required_fields <= argument_fields or not argument_fields <= allowed_fields:
                fail(
                    f"{case_id}: arguments disagree with the production schema for {tool}"
                )
            if any(not isinstance(value, str) for value in arguments.values()):
                fail(f"{case_id}: production Tool arguments must be flat strings")
            if tool == "memory_remember" and (
                ("valid_until" in arguments) != ("zone_id" in arguments)
            ):
                fail(f"{case_id}: valid_until and zone_id must appear together")
            if tool == "commitment_propose" and (
                ("proposed_at" in arguments) != ("zone_id" in arguments)
            ):
                fail(f"{case_id}: proposed_at and zone_id must appear together")
        if not isinstance(expected["clarification"], bool) or not isinstance(expected["write"], bool):
            fail(f"{case_id}: clarification/write must be booleans")
        if expected["write"] != (tool in WRITE_TOOLS):
            fail(f"{case_id}: write classification disagrees with tool risk")
        if not isinstance(expected["final_state"], str) or not expected["final_state"]:
            fail(f"{case_id}: final_state must be non-empty")
        if expected["response_language"] not in RESPONSE_LANGUAGES:
            fail(f"{case_id}: response_language must be one of {sorted(RESPONSE_LANGUAGES)}")
        if category == "secret_refusal" and tool is not None:
            fail(f"{case_id}: secret refusal must not select a tool")
    missing = REQUIRED_CATEGORIES - categories
    if missing:
        fail(f"missing required categories: {sorted(missing)}")
    actual_ids = tuple(row["id"] for row in rows)
    if actual_ids != EXPECTED_CASE_IDS:
        missing_ids = sorted(set(EXPECTED_CASE_IDS) - set(actual_ids))
        unexpected_ids = sorted(set(actual_ids) - set(EXPECTED_CASE_IDS))
        fail(
            "fixed corpus ids/order changed: "
            f"missing={missing_ids}, unexpected={unexpected_ids}"
        )
    canonical = "\n".join(
        json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        for row in rows
    ).encode("utf-8")
    if hashlib.sha256(canonical).hexdigest() != EXPECTED_CANONICAL_SHA256:
        fail("fixed corpus content changed; create a versioned corpus for semantic changes")
    print(f"OK {len(rows)} cases · {len(categories)} categories · fixed Asia/Seoul clock")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "corpus",
        nargs="?",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "models/eval/korean-tool-use-v1.jsonl",
    )
    args = parser.parse_args()
    try:
        return validate(args.corpus)
    except (OSError, ValueError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
