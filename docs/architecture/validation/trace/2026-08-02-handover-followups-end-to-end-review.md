# Handover Follow-ups End-to-End Review

## Review scope

This artifact closes the four-item 2026-08-02 handover against the reviewed design,
implementation plan, ROM/disassembly evidence, hard-rule-4 timing authority boundary, test
evidence, and delivery policy. The independent whole-delivery review result is recorded
below before integration.

## Requirement traceability

| Handover item | Delivered evidence | Disposition |
|---|---|---|
| S2 recorder contract | The committed fixture assertion pins `1.4-s2ss-native`; the Lua source assertion remains `1.4-s2ss`. Git history identifies `bceb299d8` as the native publication. Java and native headless contracts pass 6/6 each. | Complete; no fixture edit |
| Route-led persistence audit | The AIZ/HCZ/MHZ shortlist is published in `docs/architecture/audits/2026-08-02-aiz-hcz-mhz-persistence-audit.md`. AIZ Draw Bridge and MHZ Swing Vine now use ROM-grounded fixed-anchor range tails; focused manager, grabbed-player, child-lifetime, and rewind coverage passes. | Complete for the bounded route shortlist; no 192-file sweep |
| S3K complete-run timing errors | Exact prepared direct `#35` on AIZ's held row is admitted through a current-raw `PRE_MAIN_LOOP` capability; only the production post-hook retires the FIFO head. Parent preparation remains ordinary `POST_OBJECTS` work. HCZ/MHZ still reject missing production work. | Bounded positive plus fail-closed negative |
| `frameCounter` naming defect | Measured at approximately 590 update implementations and documented as an atomic future `vIntRunCount` hierarchy rename. | Explicitly deferred to a quiet tree |

## Architecture and hard-rule review

- Timing records release only matching prepared production work. They do not create,
  prepare, reorder, or select gameplay work.
- The suppressed-row authority accepts only `PRE_MAIN_LOOP`, bypasses only the ordinary
  last-serviced-boundary equality after VInt, and reuses strict head, kind, ordinal,
  fingerprint, prepared, released, cursor, deduplication, and rewind checks.
- The authority call is source-confined to `HardwareTimingReplayPort`; its observer helper
  is source-confined to `TraceSuppressedRowClosure`. Guard coverage includes wrong kind,
  wrong boundary, stale/gap rows, and unauthorized callers.
- The closure runs no gameplay body, producer, coordinator pre-step, or timing service. On
  exact admission it invokes only `RuntimeArtCoordinator.afterTimingService(PRE_MAIN_LOOP)`.
- Object lifetime changes remain in object owners and use the ROM's saved anchor/coarse-X
  range logic. No shared game-name or zone carve-out was introduced.
- Runtime assets remain ROM-backed. No committed fixture or runtime asset payload changed.

## Verification evidence

| Scope | Result |
|---|---:|
| S2 Java recorder contract | 6/6 pass |
| Native S2 standalone special-stage contract | 6/6 pass |
| Persistence/S2 focused batch | 75/75 pass |
| Timing authority/core batch | 118/118 pass |
| S3K owner/keep-green/rewind batch | 151/151 pass |
| S1 results isolation plus lifecycle guard | 12/12 pass |
| Baseline full JDK 21 suite | 14,054 tests; 27 failures; 8 errors; 31 skipped |
| Final candidate full JDK 21 suite | 14,072 tests; 26 failures; 7 errors; 31 skipped |
| Final merged full JDK 21 suite | 14,072 tests; 26 failures; 7 errors; 31 skipped |
| Post-merge signpost isolation plus lifecycle guard | 25/25 pass |

The final candidate failure/error identities are a strict subset of the baseline set. The
candidate removes the stale replay-port ordering error and the MHZ mushroom test's inherited
persistent-vine failure. It introduces no new or worsened failure. The first candidate suite
also exposed a reused-fork S1 result-test isolation gap; the production code was unchanged,
the class passed alone on both revisions, and the reviewed full-reset correction is green in
the repeated suite.

Route replays ran separately:

- AIZ: 60 errors / 6,347 represented rows; direct `#35` and dependent module `#15`
  advance, then exact engine module `#16` is unprepared at raw 6351 and fails closed.
- HCZ: unchanged 28 errors / 3,295 represented rows; direct `#90`, pending `<none>`.
- MHZ: unchanged 865 errors / 7,218 represented rows; direct `#335`, pending `<none>`.

## Unresolved risks and follow-ups

- AIZ module `#16` requires a native recorder observation-row/service-row attribution
  audit. If the stamp is stale, correction and fixture publication require separate
  approval; if valid, a separately reviewed partial-CPU-prefix representation is needed.
- HCZ direct `#90` and MHZ direct `#335` lack matching production submissions. Timing
  authority must not synthesize them; their producer/lifetime owners remain later work.
- The broad `frameCounter` rename remains deliberately out of this conflict-heavy branch.
- The repository full suite is red on the updated baseline. The candidate improves that
  exact set without claiming unrelated baseline failures are resolved.
- The first post-merge suite exposed inherited terrain in the otherwise isolated signpost
  falling contract. The class passes alone; the integration correction is test-scoped and
  passes its focused lifecycle batch and repeated post-merge suite. The renewed review below
  remains the final pre-push gate.

## Independent review result

The pre-merge review reported `NO BLOCKERS`. After the post-merge signpost isolation
correction, a fresh independent reviewer checked the complete merged diff, focused and
repeated full-suite evidence, updated integration state, hard-rule-4 confinement, ROM
lifetime ownership, documentation, policy obligations, unresolved risks, and deferrals.
Renewed result: `NO BLOCKERS`.

## Human integration checklist

- [x] Design review green after blocker amendments.
- [x] Implementation-plan review green after blocker amendments.
- [x] Independent implementation review green after source-confinement and wrong-kind
  coverage fixes.
- [x] Updated `develop` baseline recorded before merge.
- [x] Development full suite compared by exact method identity.
- [x] Renewed independent whole-delivery review green after post-merge correction.
- [x] Generated rewind-gap output discarded with explicit authorization.
- [ ] README staged in the merge into `develop`.
- [x] Post-merge full suite introduces no baseline regression.
- [x] Only `develop` pushed; implementation branches remained local.
- [x] Clean worktree removed and fully merged local branches deleted.
