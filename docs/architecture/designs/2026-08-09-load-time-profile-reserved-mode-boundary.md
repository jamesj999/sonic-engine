# Load-time reserved-mode boundary hardening

Date: 2026-08-09
Status: historical boundary hardening; FAST superseded on 2026-09-06, REALISTIC remains reserved
Base: `origin/develop` at `9de7ecf7230100626fb7084b3f678daa6a5f478c`

> FAST now resolves to its independent manifest where supplied and is the repository
> default. The fallback table and FAST completion proposals below record the
> August boundary, not current behavior. See the [current status](2026-07-29-profiled-load-time-simulation.md#current-status-2026-09-06).
> S1/S2 native queue ownership and REALISTIC evidence constraints remain relevant.

## Goal

Preserve the unfinished `FAST` and `REALISTIC` load-time modes without assigning
guessed delays, make their current fallback diagnostics name the boundary they
actually control, and replace the over-broad “cross-game profile” blocker with a
per-pipeline completion path.

This change does not claim either reserved mode is finished. It keeps the
effective behavior unchanged:

| configured mode | hardware-admission profile returned |
|---|---|
| `NONE` | `LoadTimeProfile.IMMEDIATE` |
| `PROFILED` | the supplied profile |
| `FAST` | `LoadTimeProfile.IMMEDIATE`, with a warning |
| `REALISTIC` | the supplied profile, with a warning |

## Evidence boundary

`LoadTimeProfileFactory` does not own every art-loading pipeline. Its result is
passed only to `HardwareTimingService`, and a decision is assigned only when a
production owner creates a `HardwareWorkSubmission`. The current kind registry
contains `KOS_MODULE_QUEUE` and `KOS_DECOMPRESSION_QUEUE`; both are S3K
Kosinski owners.

S1 and S2 still pass the selected enum through the default game-module
resolver. Their supplied profile is `LoadTimeProfile.IMMEDIATE`, so all four
configured values currently resolve to that same immediate profile for those
modules (and the two reserved values still warn). That composition call does
not make their PLC or dynamic-art work an admission job: neither game submits
those lifecycles through `HardwareTimingService`.

The other pipelines must remain distinct:

| pipeline | production timing owner | current evidence | consequence for these modes |
|---|---|---|---|
| S1 Nemesis PLC | `Sonic1PlcService` / `NemesisPlcServiceQueue` | ROM-derived FIFO; 3-pattern level and 9-pattern fast service; rewind and varied-route native evidence | Already has native cadence. It neither receives nor needs an S3K admission profile. The pending S1 recorded-arming proposal is trace-only and is not an ordinary-play profile. |
| S2 Nemesis PLC | `Sonic2PlcService` / `NemesisPlcServiceQueue` | ROM-derived FIFO; 3-pattern level and 6-pattern normal service; rewind and varied-route native evidence | Already has native cadence and stays outside `HardwareTimingService`. |
| S1/S2 dynamic player art, including S2 DPLC | `DynamicArtLifecycleService` and game-owned callers | Ordered transfer decisions and comparison diagnostics; no generic `HardwareWorkSubmission` or independently polled hardware-readiness job | Not a PLC timing profile and not eligible for an invented delay. |
| S3K direct Kosinski | `S3kKosDecompressionQueue` plus `HardwareTimingService` | `PROFILED` has 170 exact module-child fingerprints and a held-out-validated command-stream estimator; top-level direct measurement remains frame-end censored | This is the only current cost-bearing `LoadTimeProfile` domain. |
| S3K KosM parent | `S3kKosModuleQueue` plus direct children | The approved service model gives the parent zero additional admission units | Keep the typed zero-cost composite rule; never charge the child cost twice. |
| Trace replay | recorded timing port | exact kind, ordinal, fingerprint, preparation, and service-boundary admission | Bypasses normal-play profiles and must stay isolated from all comparison data. |

Sources: the 2026-07-27 S1/S2 hardware-timing inventories, the 2026-07-28
S1/S2 PLC design and readiness evidence, the 2026-07-29 native load-time
measurement design and S3K validation, and the cross-game hardware-timing
trace contract.

## Considered approaches

### 1. Add a constant or capped generic delay

Rejected. A number chosen for visual comfort would not come from a ROM owner,
and one budget cannot represent S1 PLC, S2 PLC/DPLC, and S3K Kosinski service.
Using a fixture-derived value would also violate the any-BK2 trace rule.

### 2. Declare the current aliases permanent and remove the warnings

Rejected. That would silently discard the preserved intent: `FAST` is meant to
gain an independent short-safety policy, and `REALISTIC` is meant to gain a
higher-confidence model. The aliases remain accepted unfinished features.

### 3. Harden the reserved boundary and publish an executable evidence path

Selected. Keep timing behavior unchanged, make the warning explicitly about
the missing independent *hardware-admission profile*, add exact contract tests,
and update current documentation so no reader infers that the S1/S2 native
queues are missing or governed by the S3K profile.

## Runtime change

`LoadTimeProfileFactory.resolve(...)` retains the same object identities and
warning frequency, but its messages are now:

```text
FAST load-time simulation is reserved; no independent FAST hardware-admission profile exists, using NONE
REALISTIC load-time simulation is reserved; no independent REALISTIC hardware-admission profile exists, using PROFILED
```

The factory Javadoc states that it resolves optional readiness admission
only for jobs submitted through `HardwareTimingService`; game-owned PLC and
dynamic-art services are outside this resolver.

No queue kind, delay, eligible boundary, readiness transition, rewind state,
trace parser, manifest, or configuration default changes.

## Test contract

`TestLoadTimeProfileContract` was changed first to expect the new exact warnings
and one warning per resolution. It failed against the old diagnostic, then
passed after the factory change. Existing tests remain the behavioral proof that:

- `FAST` returns the singleton immediate profile;
- `REALISTIC` returns the supplied profile;
- positive budgets require an eligible boundary;
- profile budgets and their assigned source/model round-trip through rewind;
- a recorded kind never consults the normal-play profile;
- S1/S2 PLC and DPLC owners remain comparison-only with respect to trace timing;
  and
- S3K KosM parents retain zero additional cost.

Focused validation included the factory/module/configuration tests, live
readiness and rewind tests, trace-authority guards, S1/S2 PLC queue tests, the
dynamic-art lifecycle test, and the S3K profile test under JDK 21. Exact command
and result evidence is recorded in the
[remediation validation](../validation/2026-08-08-load-time-profile-remediation.md).

## Exact path to independent semantics

The modes can be finished only after their product semantics and evidence are
separated from pipeline mechanics.

### `FAST`

First approve a non-numeric policy contract: which submitted work is eligible,
what “short safety” guarantees relative to `NONE` and `PROFILED`, whether the
bound is per physical head or per composite parent, and what happens when a
profile record is unavailable. Only then select values from a published,
versioned service-model artifact. No value may be inferred from one route's
observed wait or copied across compression/service kinds.

For the current tree, eligible work can only be S3K direct Kosinski jobs. S1
and S2 PLC budgets are already fixed by their ROM routines; changing those
budgets would be a separate accuracy change, not `FAST`. Dynamic player-art
transfers remain ineligible without a gameplay-visible readiness fence.

### `REALISTIC`

The current S3K measurement schema publishes exact costs only for direct jobs
whose submission is observed at the approved KosM-child callback. Top-level
direct submissions are `frame_end_censored` and excluded from runtime rows and
estimator training. The next evidence step is therefore:

1. approve a measurement-only revision that observes the centralized
   top-level `Queue_Kos` submission boundary with locked-ROM opcode checks;
2. record canonical descriptor identity, within-frame order, physical-head
   activation, every eligible `PRE_MAIN_LOOP` service opportunity, and
   retirement without changing `hardware_timing.jsonl`;
3. rerun the five published movies and additional routes selected from the
   187-call/124-cluster S3K queue audit;
4. publish exact-versus-censored coverage, variance, fixture provenance, and
   byte-reproducible manifests; and
5. separately approve the confidence/context rule that distinguishes
   `REALISTIC` from `PROFILED` before changing the alias.

If ordinary-play `REALISTIC` is intended to cover S1 PLC arming at sub-frame
boundaries, it requires a cycle-position model or a separately designed
ordinary-play policy. The unapproved `NEMESIS_PLC_QUEUE` proposal is a recorded
trace input and cannot supply normal-play timing. S2 PLC needs the same
owner-specific analysis before any extra admission gate; S2 DPLC stays excluded
unless a ROM gameplay consumer polls an actual readiness fence.

### Acceptance when either mode is eventually implemented

For every newly eligible owner, tests must prove physical-head FIFO contention,
prepared-versus-ready behavior at the owner's native boundaries, rewind before
and during admission, session reset, and trace replay bypass. S1/S2
non-applicability is proved as non-interference when those owners remain outside
the mode, not by forcing all three games through one submission type.

## Documentation result

The 2026-08-08 remediation remains a point-in-time validation record and has a
dated follow-up rather than being rewritten as if the earlier investigation had
known this boundary. Current configuration, audit, roadmap/design, changelog,
and README summaries state the narrowed result.
