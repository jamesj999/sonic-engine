# S3K Results-to-Title Handoff Implementation Plan

## Implementation result

The results publication/title-init separation, complete
`Restore_PlayerControl` writes, live title-manager rewind registration/rebind,
native 90-dispatch wait ownership, and prior-render results-child retirement
are implemented with focused production-path coverage.

The target replay now admits title ordinals 23–26 and stops at the distinct
post-title transition/runtime-art edge: ordinal 27, raw frame 8943,
fingerprint `sha256:65c8c371…f89b3`, the first `PLCKosM_AIZ` request
(`ArtKosM_AIZ_MonkeyDude` at `ArtTile_MonkeyDude`). That later owner is not
part of this implementation plan and must begin with its own
disassembly-backed design and dispatch-level red test.

## 1. Lock the ROM dispatch contract with failing tests

- Extend `TestS3kResultsScreenObjectInstance` with a production-path fixture
  that reaches the final child-retirement boundary through real object-pass slot
  ordering with `Sonic3kTitleCardManager` and `HardwareTimingService`, not
  manual owner calls or a recording-only provider.
- Record dispatch-visible observations for `endOfLevelActive`, native player
  control, title-card initialization, and queued KosM jobs.
- Assert that later-slot `S3kBossDefeatSignpostFlow` restores the complete native
  player state independently in the publication object pass and that title
  initialization happens on the result owner's next dispatch.
- Assert no title job on publication, exactly four on title init, and no
  duplicate submission on later dispatches.
- Assert the first queued job identifies ROM `$0D6F28`, tile `$500`, and the
  stable Red Act fingerprint.
- Add rewind coverage immediately before publication, after publication/before
  title init, and after title init. Prove title-manager lifecycle and restored
  hardware handles rebind without job resubmission.
- Run the new tests red and identify whether the excess dispatch belongs to
  `waitDurationAdjustment`, `carriedResultsRetireDispatches`, or the serialized
  post-control delay.

## 2. Separate the production owners

- Reuse `GameState.endOfLevelActive` as `_unkFAA8`; do not add a parallel signal.
- Change `S3kResultsScreenObjectInstance.updateExitQueue()` so the result owner
  clears that signal at the ROM retirement boundary and retains a captured
  title-init phase without waiting behind control restoration.
- Make `S3kBossDefeatSignpostFlow` consume the cleared signal and perform all
  ROM `Restore_PlayerControl` writes.
- Partition `onExitReady()` effects so publication-side camera/music/apparent
  act/event changes run once, title initialization runs on the next dispatch,
  and neither path repeats or prematurely deletes the retained owner.
- Make `Sonic3kTitleCardManager` implement the live rewind contract, including
  an immutable snapshot of lifecycle phase, timers, elements, art state, handle
  identities and destinations; queue-facade reconstruction without submission;
  full handle-identity validation during rebind; and
  `resetForMissingSnapshot()`.
- Register/deregister the live title provider idempotently in the active
  gameplay/session `RewindRegistry` after the game module is available. Confirm
  `GameplayModeContext` registers it alongside hardware timing so production
  rewind—not only direct unit snapshots—restores it.
- Rebind the four title submissions to handles restored by
  `HardwareTimingService`; never recreate work during restore.
- Retain typed flow configuration for legitimate per-flow dispatch accounting;
  remove only the double-count or serialization proven by the red test.
- After title initialization, assert terminal ownership is handed off exactly
  once and the result object completes/deletes without a retained zombie owner.
  No-title/direct-deletion paths must also leave no retained result owner.
- Keep title art submission in `Sonic3kTitleCardManager`; do not create work
  from trace data or weaken hardware-timing authority.

## 3. Verify focused lifecycle behavior

- Run `TestS3kResultsScreenObjectInstance` and `TestS3kSignpostInstance`.
- Run the affected boss/signpost flow tests if their typed configuration or
  event bridge changes.
- Run SOZ1/DEZ1 no-title paths and Act 2/special direct-deletion paths.
- Run hardware timing authority, Kos structural sequence, and rewind coverage
  guards.
- Run a production `RewindRegistry.capture()/restore()` test proving the
  registered title manager and restored hardware service reconstruct the queue
  facade and rebind all four fully validated handles without resubmission.
- Confirm no constructor, child allocation, or snapshot regression.

## 4. Verify trace movement

- Run `TestS3kAizTraceReplay` with the discovered S3K ROM.
- Confirm ordinal 23 is submitted before its recorded completion and the replay
  produces a fresh report rather than an aborted stale report.
- Run the AIZ complete-run canary and HCZ/MGZ result-transition canaries.
- Run the four required S3K bring-up guards:
  `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
  `TestSonic3kBootstrapResolver`, and `TestSonic3kDecodingUtils`.
- Update `docs/status/trace-frontier-log.md` with command, commit/worktree,
  result, error count, and first error frame/field.

## 5. Independent verification

- Assign a fresh Sol-high Verify lane under `trace-green-fleet`.
- Require structured evidence that the repair is genuine, moves the fresh AIZ
  frontier, introduces no focused regressions, and obeys trace/hardware
  authority guards.
- Before acceptance, the Verify lane stages the design, plan, frontier log,
  changelog, and every other task artifact, supplies repository trailers, and
  creates the single implementation commit. Only Verify may create that commit,
  and only after all blocking findings are resolved and `accepted=true`.

## 6. Integrate

- Fetch and fast-forward `develop`; run and record the full updated integration
  baseline with JDK 21 and all three ROM properties.
- Run that same full suite plus focused tests in the development worktree.
- Start `git merge --no-commit` without a pre-staged README, then edit/stage the
  required `README.md` release note during the pending merge. Complete the merge
  without switching the main workspace branch and rerun focused/full regression
  comparison.
- Push only `develop`.
- Remove the clean merged worktree and local scaffolding branch.
- Start a fresh Terra-low, no-exclusions trace discovery from the pushed commit
  using a printed concrete `*TraceReplay` class allowlist, and update the
  frontier log with the pushed commit's sweep and selected next target. Bank
  that record in the next verified fleet branch (or a dedicated reviewed
  documentation commit) and push it; do not leave the discovery artifact
  uncommitted.
