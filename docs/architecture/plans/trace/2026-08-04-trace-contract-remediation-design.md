# Trace contract remediation design

## Objective

Bring the live trace implementation and its acceptance evidence into agreement
with the approved v5 contract. The remediation must remove retired schema-1
behavior from the default replay path, preserve the narrow hardware-timing
authority boundary, and make historical artifacts explicitly historical rather
than leaving contradictory current-looking instructions.

## Scope

1. Make every v5 hardware-timing schedule use the single complete registry:
   module-queue and direct-Kosinski readiness are both recorded when a timing
   stream is present. An absent timing file remains a live/no-port path; an
   explicitly present empty timing file is still a v5 recorded registry with
   no edges. Neither path silently selects the retired module-only registry.
2. Replace default schema-1 tests with v5 tests. Keep explicit policy-map unit
   tests only where they exercise the generic timing service API rather than
   claiming to describe a live trace format.
3. Reconcile the v5 freeze, candidate report, regeneration plan, known-
   discrepancies entry, framework guide, native-recorder comments, and
   cross-game timing contract with the installed fleet. Record the corrected
   post-install inventory identity and an exact publication deletion/rename
   manifest, while preserving the archived S1 credits predecessors.
4. Audit active parser/catalog/bootstrap fallbacks and old positive fixture
   paths so legacy wording remains only in rejection tests or explicitly
   historical evidence.
5. Add a concise trace-frontier/status entry identifying this as contract and
   evidence closure, not a gameplay frontier movement.

## Invariants

- Timing authority can only release matching, prepared, production-submitted
  ROM work after kind, ordinal, fingerprint, and service-boundary checks.
- No gameplay state, payload bytes, queue preparation, or producer call is
  supplied by a trace.
- No legacy parser compatibility path is added.
- Explicit mixed admission maps remain available only as generic timing-service
  test seams; no live v5 parser or schedule selects one.
- The eight archived S1 credits predecessor directories remain intact.
- Existing unrelated dirty files in the main workspace are not touched.

## Validation

- Focused timing/replay and trace-contract Java tests, including negative
  policy/empty-schedule cases.
- Strict v5 fixture validator and Python tooling suite.
- Native BizHawk no-gate suite.
- Segment handoff tests covering absent and present-empty timing schedules.
- Full Maven suite in the development worktree and again after integration,
  compared with the recorded baseline.
