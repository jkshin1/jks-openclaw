#!/usr/bin/env python3
"""Regression tests for review deltas without runtime or private configuration."""

import hashlib
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("changes", Path(__file__).with_name("hermes-ops-changes.py"))
changes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(changes)


def source(text):
    return {"content": text, "sha256": hashlib.sha256(text.encode()).hexdigest()}


class ChangeTests(unittest.TestCase):
    def setUp(self):
        self.sources = {"scripts/openclaw/example.py": source("COUNT = 1\n")}
        self.old = {"runId": "old-run", "snapshotSha256": "a" * 64,
                    "sources": self.sources, "evidence": {"configuration": {"model": "main"},
                    "observedAt": "yesterday"},
                    "upstream": [{"name": "openclaw", "ok": True, "tag": "v1", "body": "old\n"}]}

    def test_first_run_has_no_invented_baseline_or_diff(self):
        result = changes.context({"configuration": {}}, [], self.sources)
        self.assertEqual(result["baselineStatus"], "none")
        self.assertTrue(result["fullReview"])
        self.assertIsNone(result["baselineRunId"])
        self.assertEqual(result["sourceDiffs"], [])

    def test_changed_sources_and_removed_paths_are_separate(self):
        current = {"scripts/openclaw/new.py": source("NEW = True\n")}
        result = changes.context({}, [], current, previous=self.old, baseline_status="available")
        self.assertEqual(result["removedSourcePaths"], ["scripts/openclaw/example.py"])
        self.assertEqual(result["changedSourcePaths"], ["scripts/openclaw/new.py"])
        self.assertIn("+NEW", result["sourceDiffs"][0]["patch"])

    def test_timestamp_and_review_history_do_not_masquerade_as_semantic_changes(self):
        before = {**self.old, "evidence": {"operations": {"ok": True, "observedAt": "before"},
                                           "reviewHistory": {"reviews": []}}}
        result = changes.context({"observedAt": "new-top-timestamp", "operations": {"ok": True, "observedAt": "after"},
                                  "reviewHistory": {"reviews": [1]}}, [], self.sources,
                                 previous=before, baseline_status="available")
        self.assertEqual(result["changedEvidenceIds"], [])

    def test_main_fallback_change_is_visible(self):
        result = changes.context({"configuration": {"model": "main", "fallbacks": ["glm"]}}, [], self.sources,
                                 previous=self.old, baseline_status="available")
        self.assertEqual(result["changedEvidenceIds"], ["configuration"])

    def test_large_unicode_diff_respects_cumulative_budget_and_reports_truncation(self):
        sources = {"scripts/openclaw/" + str(n) + ".py": source("# " + "가" * 20000 + "\n") for n in range(8)}
        result = changes.context({}, [{"name": "hermes", "ok": True, "tag": "v2", "body": "x" * 30000}],
                                 sources, previous=self.old, baseline_status="available")
        total = sum(len(row["patch"].encode()) for row in result["sourceDiffs"] + result["upstreamChanges"])
        self.assertLessEqual(total, changes.MAX_DIFF_BYTES)
        self.assertTrue(result["truncated"])

    def test_only_verified_unchanged_successful_release_bodies_are_omitted(self):
        upstream = self.old["upstream"]
        compact = changes.compact_upstream(upstream, self.old)
        self.assertEqual(compact[0]["body"], "")
        self.assertTrue(compact[0]["bodyOmittedUnchanged"])
        self.assertEqual(changes.compact_upstream(upstream, self.old, True), upstream)
        self.assertEqual(changes.compact_upstream(upstream, None), upstream)
        changed = [{**upstream[0], "body": "changed"}]
        self.assertEqual(changes.compact_upstream(changed, self.old), changed)
        failed = [{**upstream[0], "ok": False}]
        self.assertEqual(changes.compact_upstream(failed, self.old), failed)


if __name__ == "__main__":
    unittest.main()
