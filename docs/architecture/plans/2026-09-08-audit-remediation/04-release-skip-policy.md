# 04 — Classify the opt-in rewind allocation probe

Release blocker; low complexity; estimated 30–60 minutes.

## Problem

The ordinary suite records TestS3kRewindAllocationProbe#measure as skipped unless openggf.rewind.alloc.measure=true, but the exact identity is absent from the release skip policy. The release classifier consequently rejects an otherwise green run.

## Owners and evidence

Owners: `tools/testing/release-skip-policy.json`, `test_classify_surefire_skips.py`, `classify_surefire_skips.py`; verify `src/test/java/com/openggf/game/rewind/TestS3kRewindAllocationProbe.java` and release workflow.

## Implementation steps

1. Run the classifier against the completed baseline reports and confirm the exact missing opt-in identity.
2. Add one accurately categorized opt-in rule with live source evidence and allowed_when_absent=null, consistent with other probes.
3. Preserve fail-closed behavior for unknown skips; do not add wildcards or enable expensive measurements in release.
4. Run policy unit tests and current-report classification. Distinguish unavailable graphics/reference capabilities from classification failure.

## Acceptance

The probe skip is allowed explicitly, all existing unknown-skip rejection behavior remains intact, and current completed ordinary reports classify without this error.

Follow [shared constraints and delivery](README.md). Update the existing matching `CHANGELOG.0.6.md` theme for runtime fixes; mapped documentation and commit trailers must agree. Report focused regressions, full development-tree suite, guards, exact commit, and any limitations to the coordinator. The coordinator must validate this task before acceptance.
