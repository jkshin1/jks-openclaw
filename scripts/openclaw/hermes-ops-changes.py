#!/usr/bin/env python3
"""Bounded, advisory review deltas. Full immutable evidence remains authoritative."""

import difflib
import hashlib
import json


MAX_DIFF_BYTES = 32768
MAX_FILE_DIFF_BYTES = 8192
MAX_CHANGES = 128
VOLATILE_KEYS = {"observedAt", "checkedAt", "fetchedAt", "collectedAt", "finishedAt", "startedAt",
                 "lastSuccessAt", "lastAttemptAt", "lastCheckedAt", "ageSeconds", "uptimeSeconds",
                 "updatedAt", "updatedAtMs", "lastSeenAt", "firstSeenAt", "reviewCount"}
HISTORY_KEYS = {"reviewHistory", "appliedChanges", "findingLifecycle", "procedureKnowledge"}


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False)


def stable(value):
    if isinstance(value, dict):
        return {key: stable(item) for key, item in value.items() if key not in VOLATILE_KEYS}
    if isinstance(value, list):
        return [stable(item) for item in value]
    return value


def clip(value, budget):
    data = value.encode("utf-8")
    return data[:budget].decode("utf-8", errors="ignore"), len(data) > budget


def diff(before, after, label, budget):
    # Do not create a megabyte diff only to truncate it. Iterate until the budget is full.
    pieces, size, truncated = [], 0, False
    for line in difflib.unified_diff(before.splitlines(keepends=True), after.splitlines(keepends=True),
                                     fromfile="previous/" + label, tofile="current/" + label, n=2):
        fragment, clipped = clip(line, max(0, budget - size))
        pieces.append(fragment)
        size += len(fragment.encode("utf-8"))
        if clipped or size >= budget:
            truncated = True
            break
    return "".join(pieces), truncated


def context(evidence, upstream, sources, *, previous=None, baseline_status="none", full_review=False,
            scoped=False):
    """Compare only a verified previous snapshot supplied by the controller.

    A scoped (incident) review is narrow by design, so a missing baseline does not widen it into a
    full review; the gap is still visible through baselineStatus.
    """
    old = previous or {}
    old_sources = old.get("sources", {})
    before_evidence = old.get("evidence", {})
    changed_ids = sorted(key for key in evidence if key not in HISTORY_KEYS | VOLATILE_KEYS
                         and stable(evidence[key]) != stable(before_evidence.get(key)))
    changed = sorted(path for path, entry in sources.items()
                     if old_sources.get(path, {}).get("sha256") != entry["sha256"])
    removed = sorted(set(old_sources) - set(sources))
    output = {"schemaVersion": 1, "baselineRunId": old.get("runId"),
              "baselineSnapshotSha256": old.get("snapshotSha256"), "baselineStatus": baseline_status,
              "changedEvidenceIds": changed_ids[:MAX_CHANGES], "changedSourcePaths": changed[:MAX_CHANGES],
              "removedSourcePaths": removed[:MAX_CHANGES], "sourceDiffs": [], "upstreamChanges": [],
              "fullReview": bool(full_review or (baseline_status != "available" and not scoped)),
              "truncated": any(len(items) > MAX_CHANGES for items in (changed_ids, changed, removed))}
    remaining = MAX_DIFF_BYTES
    if old:
        for path in changed:
            if remaining <= 0:
                output["truncated"] = True
                break
            text, truncated = diff(old_sources.get(path, {}).get("content", ""), sources[path]["content"],
                                   path, min(MAX_FILE_DIFF_BYTES, remaining))
            output["sourceDiffs"].append({"path": path, "beforeSha256": old_sources.get(path, {}).get("sha256"),
                                           "afterSha256": sources[path]["sha256"], "patch": text,
                                           "truncated": truncated})
            remaining -= len(text.encode("utf-8"))
            output["truncated"] |= truncated
    old_upstream = {row["name"]: row for row in old.get("upstream", [])}
    for row in upstream:
        before = old_upstream.get(row["name"], {})
        if (row.get("ok"), row.get("tag"), row.get("body")) == (
                before.get("ok"), before.get("tag"), before.get("body")):
            continue
        text, truncated = diff(before.get("body", ""), row.get("body", ""), row["name"],
                               min(MAX_FILE_DIFF_BYTES, remaining))
        output["upstreamChanges"].append({"name": row["name"], "beforeTag": before.get("tag"),
                                            "afterTag": row.get("tag"),
                                            "bodyChanged": row.get("body") != before.get("body"),
                                            "patch": text, "truncated": truncated})
        remaining -= len(text.encode("utf-8"))
        output["truncated"] |= truncated
    return output


def compact_upstream(upstream, previous, full_review=False):
    """Omit unchanged release bodies only when a verified prior review is available."""
    if not previous or full_review:
        return upstream
    old = {row["name"]: row for row in previous.get("upstream", [])}
    result = []
    for row in upstream:
        before = old.get(row["name"], {})
        same = (row.get("ok") is True and before.get("ok") is True
                and row.get("tag") == before.get("tag") and row.get("body") == before.get("body"))
        result.append({**row, "body": "", "bodyOmittedUnchanged": True,
                       "bodySha256": hashlib.sha256(row.get("body", "").encode()).hexdigest()}
                      if same else row)
    return result
