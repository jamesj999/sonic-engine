# Load-time profile remediation validation

Date: 2026-08-08
Worktree: `bugfix/ai-remediate-load-profiles`
Base: `b82056a13`

## Decision

**P2 NARROWED / RESERVED — not resolved.** `FAST` and `REALISTIC` are not
deleted and are not given guessed delays. The existing resolver remains the
coherent unfinished implementation:

| Configured mode | Effective profile | Warning emitted by `LoadTimeProfileFactory.resolve(...)` |
|---|---|---|
| `NONE` | `LoadTimeProfile.IMMEDIATE` | none |
| `PROFILED` | supplied profile | none |
| `FAST` | `LoadTimeProfile.IMMEDIATE` (`NONE`) | `FAST load-time simulation is not implemented; using NONE` |
| `REALISTIC` | supplied profile (`PROFILED`) | `REALISTIC load-time simulation is not implemented; using PROFILED` |

The warning sink is called on each factory resolution; the factory has no
global warning-suppression state. The normal gameplay path usually resolves
once when a context is constructed, but a reconstructed context can resolve
again. `WorldSession` caches the parsed enum, not the resolved profile.

## Evidence and blocker

The call-site and ownership review found no authoritative cross-game model for
either reserved mode:

* `GameplayModeContext` is the only shared composition call. Normal play passes
  the selected mode to the game module; recorded replay uses
  `LoadTimeProfile.IMMEDIATE` and the recorded admission policy instead.
* `GameModule` delegates to `LoadTimeProfileFactory`. The S3K override loads
  `/load-time-profiles/s3k-v1.json` and applies the explicit zero-cost KosM
  parent rule before delegating to the factory.
* `LoadTimeProfile` receives `HardwareWorkSubmission`, whose current
  `HardwareWorkKind` registry contains only
  `KOS_MODULE_QUEUE` and `KOS_DECOMPRESSION_QUEUE`. The checked-in manifest is
  S3K-only: 170 exact direct fingerprints and a validated estimator for the
  `s3k-kos-v1` service model.
* S1 `Sonic1PlcService` and S2 `Sonic2PlcService` own Nemesis PLC FIFOs and
  their game-specific VBlank/preparation boundaries. S2 dynamic-art/DPLC work
  is owned by `DynamicArtLifecycleService`. None submits through
  `HardwareTimingService` or has a `LoadTimeProfile` provider. The queue
  diagnostic projection is comparison-only and is not a normal-play timing
  profile.
* The cross-game hardware-timing contract permits a recorded delay only for
  already-submitted, production-created art work. It does not define an
  arbitrary safety delay, allow a frame/route/game carve-out, or permit trace
  comparison state to choose gameplay timing. Normal-play `LOAD_TIME_SIMULATION`
  is bypassed by trace replay.

`FAST` has no specified service units, queue kinds, or eligible boundaries.
`REALISTIC` has no broader cross-game measurements or context model. Assigning a
constant, borrowing S3K Kosinski costs, or deriving a delay from a trace fixture
would therefore violate the repository's accuracy and trace-authority rules.

## Required evidence before implementation

Revisit the reserved modes only after all of the following exist:

1. A cross-game production submission contract covering S1 PLC, S2 PLC/DPLC,
   and S3K module/direct work, with stable ROM-backed identity and explicit
   service-boundary ownership.
2. Native measurement artifacts that observe physical-head activation, eligible
   service opportunities, preparation, and retirement; completion-only trace
   rows are insufficient for intrinsic cost.
3. Independently validated manifests/estimators for each supported game and
   service model, with coverage and variance published rather than fitted to a
   route or frame index.
4. An explicit semantic design for the difference between `FAST` (including
   its intended safety bound) and `REALISTIC` (including its confidence/context
   requirements), plus lifecycle semantics for warning suppression.
5. Behavior tests proving effective readiness timing, FIFO contention,
   rewind, and trace-replay isolation for every supported queue owner.

## Focused verification

Environment: Maven 3.9.16, Java 21.0.11, Arch Linux. The focused command was
run with one Surefire fork and alphabetical ordering:

```bash
mvn -Dmse=off -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  "-Dtest=com.openggf.configuration.TestBundledConfigResource,com.openggf.configuration.TestConfigCatalog,com.openggf.configuration.TestConfigEnumValidation,com.openggf.configuration.TestConfigServiceYamlRoundTrip,com.openggf.configuration.TestConfigYamlWriter,com.openggf.configuration.TestLoadTimeSimulationConfiguration,com.openggf.game.TestGameModuleLoadTimeProfile,com.openggf.game.session.TestWorldSessionLoadTimeMode,com.openggf.game.sonic3k.TestS3kLoadTimeProfile,com.openggf.game.timing.TestHardwareTimingService,com.openggf.game.timing.TestLoadTimeProfileContract,com.openggf.game.timing.TestProfiledLoadTimeManifest,com.openggf.trace.TestTraceDataHardwareTiming,com.openggf.trace.TestTraceV5LoadingContract,com.openggf.trace.timing.TestHardwareTimingAuthorityGuard" \
  test
```

Result: **87 tests, 87 passed, 0 failures, 0 errors, 0 skipped**. The Maven
git-hook installation emitted the known shared-worktree `.git/config` lock
warning, but the build and tests completed successfully. No production timing
behavior or trace authority was changed.

Historical evidence remains in `1b59cb4b4` (the four-value configuration and
resolver aliases), `aae67bc50` (the S3K-only profile gate), and `420ff09a6`
(the five-fixture S3K manifest publication). The 2026-07-29 profile design
already reserved `FAST` and `REALISTIC`; this remediation makes that status
explicit in the current audit and roadmap rather than deleting the modes.

## Workspace-isolation note

During this load-profile documentation lane, four task-specific unstaged hunks
appeared in the main `develop` worktree at `5922ee722`. They were byte-identical
to, or overlapped, this isolated commit's documentation changes. The root worker
preserved them and never restored or staged them. They are not part of this
worktree's proof; main was not untouched, and any integration must be validated
independently.
