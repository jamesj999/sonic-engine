# Sound-driver RE current-state audit

Date: 2026-08-31
Evidence baseline audited: `develop` commit `165da2cda`. The audit artifacts were developed
on `feature/ai-sound-driver-roadmap-completion`; its later documentation-only commits do not
alter the product code or test baseline assessed here.
Scope: point-in-time audit of Sonic 1, Sonic 2, and Sonic 3&K sound-driver reverse-engineering work. This is not an implementation plan.

## Original six objectives

1. Reverse-engineer each shipped sound driver from primary source material.
2. Document behavior, RAM state, routines, and comparison vocabulary.
3. Compare documented driver behavior with OpenGGF.
4. Catalogue implementation and validation gaps.
5. Implement source-owned gaps.
6. Validate with authenticated oracle tests, unit/parity tests, and human listening where automation cannot certify audibility.

## Baseline evidence considered

- Environment: Maven running on JDK 21.0.11.
- Fresh focused audio/parity baseline at commit `165da2cda`, repeated after the
  audit review:

  ```bash
  LUA_BIN=lua5.4 mvn -Dmse=off \
    '-Dsonic1.rom.path=<absolute S1 REV01 ROM>' \
    '-Dsonic2.rom.path=<absolute S2 REV01 ROM>' \
    '-Ds3k.rom.path=<absolute locked-on S3K ROM>' \
    '-Dtest=com.openggf.audio.**,com.openggf.tools.audio.parity.**' test -B
  ```

  Maven/JUnit aggregate: 1,787 tests, 0 failures, 0 errors, 9 skips; build
  success in 2:13. The post-run Surefire XML suite attributes sum to 1,715,
  so this audit records that report-accounting difference rather than claiming
  the XML sum reproduces the launcher aggregate. The paths are neutralized for
  repository policy; the run used the three SHA-1 identities pinned in root
  `AGENTS.md`.
- Prior merged full-suite evidence, run earlier in this same delivery at commit `165da2cda`:

  ```bash
  mvn -Dmse=off \
    '-Dsonic1.rom.path=<absolute S1 REV01 ROM>' \
    '-Dsonic2.rom.path=<absolute S2 REV01 ROM>' \
    '-Ds3k.rom.path=<absolute locked-on S3K ROM>' \
    test -B
  ```

  Result: 15,931 tests, 0 failures, 0 errors, 34 skips.
- Prior guards evidence, run earlier in this same delivery at commit `165da2cda`:

  ```bash
  mvn -Dmse=off -Pguards \
    '-Dsonic1.rom.path=<absolute S1 REV01 ROM>' \
    '-Dsonic2.rom.path=<absolute S2 REV01 ROM>' \
    '-Ds3k.rom.path=<absolute locked-on S3K ROM>' \
    test -B
  ```

  Result: 565 tests, 0 failures, 0 errors, 0 skips.

These prove the landed audio/parity surface is internally green. They do not prove full
sound-driver equivalence. In addition to bounded coverage, the S2 and S3K comparisons have
fixture-assisted input authority described below.

## Cross-game objective status

| Objective | Status | Evidence and limits |
|---|---|---|
| 1. Reverse-engineer driver | PARTIAL | S1/S2/S3K routine maps, behavior specs, gap analyses, and TraceChaser native reference-capture capabilities exist. Remaining hardware-edge and request-boundary behavior is not fully observed, especially S2/S3K request transfer before Z80 consumption. Cross-game remaining gaps include ROM-read priority/tempo/request tables, DAC/PCM timing, and analog mix, which is outside this driver-oracle scope. |
| 2. Document behavior/routines | PARTIAL | Documentation exists under `docs/architecture/research/audio/`, `docs/architecture/designs/audio/`, `docs/architecture/audits/audio/`, and validation docs. Currentness is partial: some docs are now stale or contradictory after later fixes. |
| 3. Compare with OpenGGF | PARTIAL | OpenGGF already owns a cross-game `CompleteRunAudio*` schema/store/comparator/profile framework and strict S2/S3K TraceChaser raw adapters, while TraceChaser owns full native S2/S3K captures. Their fixed reference and independent OpenGGF producer bindings remain unavailable, so no complete-run comparison has executed. S1 has bounded driver-core comparisons, but not production-owned input comparisons. The older S2 0-209 and S3K 0-127 prefixes are fixture-assisted projections. |
| 4. Catalogue gaps | PARTIAL | `2026-08-30-sound-driver-re-gap-analysis.md` catalogues major gaps, but several S1 rows are stale after later cadence, PSG takeover, SFX restore, and SFX oracle fixes. |
| 5. Implement gaps | PARTIAL | Source-owned fixes landed for cadence, S1 TIMEOUT track walk, S1 SFX takeover/release ordering, ring request alternation, and selected pointer/voice behavior. Queue admission, true priority state, pause, full 1-up/fade/speed paths, PSG overrun behavior, ROM-read tables, S3K code-byte modulation envelopes and every-frame frequency edge, DAC/PCM timing, S2/S3K pause/1-up/fade, and S2/S3K request-boundary behavior remain incomplete or unverified. |
| 6. Validate | PARTIAL | Automated focused and prior full-suite baselines are green. Synthetic unit tests and fixture-contract tests prove narrow code contracts. S1 live executions prove bounded driver-core behavior but not a production-owned input path; the S1 SFX and current S2/S3K executions are fixture-assisted diagnostic projections and cannot certify independent request scheduling. Human listening remains pending except the S3K ring-panning check. |

## Sonic 1 status matrix

| Claim/gap | Status | Evidence type | Evidence and limits |
|---|---|---|---|
| Driver routine/spec documentation | PARTIAL | Docs | `docs/architecture/research/audio/2026-08-30-s1-sound-driver-routine-map.md` and `docs/architecture/designs/audio/2026-08-30-s1-sound-driver-behaviour-spec.md` map the S1 driver, RAM, FixBugs=0 sites, and expected behavior, but currentness is partial because later fixes and the H-int/load-frame review changed several claims. |
| GHZ music driver-core parity | DONE for covered slice; not production-owned | Live driver-core execution + unit/fixture contracts | `docs/status/audio-frontier-log.md` records S1 GHZ music equality over 14,690 ticks. `S1OpenGgfAudioCapture` starts the source-owned GHZ song but reads reference metadata for cadence and terminal bounds. This proves a bounded driver-core slice, not natural gameplay request generation or all music. |
| Sound-test SFX driver-core parity | DONE as fixture-assisted projection | Live fixture-assisted execution + unit/fixture contracts | `docs/status/audio-frontier-log.md` records equality over 1,967 ticks, but `S1OpenGgfSfxAudioCapture` replays each reference `dispatches` value at its recorded invocation ordinal. Coverage is bounded to that normal-SFX fixture and is not a production-owned request comparison. |
| SFX takeover/release and track order | DONE for covered synthetic/fixture-assisted slice | Code + synthetic unit + fixture-assisted execution | `SmpsSequencer`, `SmpsDriver`, `Sonic1SmpsSequencerConfig`, `TestS1SfxTakeoverOrder`, and the S1 SFX projection cover the landed normal-SFX path. Special-layer behavior remains narrower than the driver as a whole, and natural gameplay request scheduling is not proven by this lane. |
| Special-pointer bug behavior | PARTIAL / UNVERIFIED | Code + synthetic unit | `TestSonic1SfxData` proves zero-address voice normalization. The normal-SFX fixture does not cover special-layer playback, so the special-pointer behavior is not live-oracle-proven for that layer. |
| Live driver cadence | PARTIAL / UNVERIFIED | Code + synthetic unit | `SmpsDriver.serviceOuterFrame`, `SmpsSequencer`, and `TestSmpsSequencerCadence` pin frame-locked service and S1 TIMEOUT behavior. H-int/load-frame cadence remains unresolved/unverified, and there is still no all-driver live oracle. |
| Sound queue admission and global priority state | PARTIAL / NOT DONE | Docs + code inspection | `AudioParitySchema` can carry diagnostic queue/priority fields, but `Sonic1AudioProfile` does not own a true S1 priority admission override; presentation admission still uses `NO_PRIORITY` context. |
| Pause behavior | NOT DONE | Docs + code inspection | S1 spec documents `f_pausemusic`; `AudioManager` pause/resume remains presentation/lifecycle-oriented rather than ROM driver pause state and pause-burst behavior. |
| 1-up, fade, speed shoes, and push paths | PARTIAL / UNVERIFIED | Code + limited unit | Some presentation and fade timing tests exist, but full driver RAM save/restore and route interactions are not authenticated by an S1 oracle. |
| PSG3 overrun / table-edge behavior | NOT DONE | Docs + code inspection | The spec calls out the PSG3 edge behavior; current sequencer code clamps note-index table access rather than proving the ROM overrun path. |
| ROM-read tables and DAC timing | PARTIAL / UNVERIFIED | Code + docs | Some Java tables and DAC plumbing exist, but the audit did not verify all priority/tempo/request tables are ROM-read, nor did it verify cycle-relevant DAC timing. |
| Human listening | NOT DONE | Human-listening checklist | `docs/architecture/validation/audio/2026-08-21-smps-playback-listening-checklist.md` has no completed S1 rows. |

## Sonic 2 status matrix

| Claim/gap | Status | Evidence type | Evidence and limits |
|---|---|---|---|
| Driver routine/spec documentation | PARTIAL | Docs | S2 research/spec/gap material exists, and the current frontier log records the active oracle boundary. Documentation is useful but not complete proof of every driver edge. |
| Fixture producer and comparator | DONE as tooling | Code + fixture contracts | S2 authenticated oracle tooling and fixture support landed in `feat(audio): commit the S2 driver oracle - reference fixture and comparator`. Fixture-contract tests protect schema and integrity, but parity still depends on live compare executions. |
| TraceChaser complete-run reference capability | DONE as producer capability | Native observer + two deterministic complete captures | TraceChaser's `S2CompleteAudioCaptureRunner` and raw sink pin the complete-emeralds BK2, capture all `$0000..$1FFF` Z80 RAM plus native services/chip writes for rows `[769,259590)`, and record 169,986,419 events with an empty cutoff frontier. The duplicate-run evidence is recorded in `docs/architecture/research/audio/2026-08-11-complete-run-audio-frontier-checkpoint.md`. This is the reference producer half, not an OpenGGF comparison result. |
| OpenGGF complete-run consumer integration | PARTIAL / NOT EXECUTABLE | Schema/store/comparator/profile/raw adapter | `CompleteRunAudioTrace`, store, comparator, CLI, S2 profile, raw adapter, decoder, normalizer, catalog, and resolver exist. `S2CompleteRunReferenceProducer` and `S2CompleteRunOpenGgfProducer` are absent and both profile bindings are explicitly unavailable. The raw adapter validates TraceChaser staging but does not yet publish a canonical semantic capture. |
| Engine parity against oracle | PARTIAL / FIXTURE-ASSISTED | Diagnostic execution + unit/parity | `docs/status/audio-frontier-log.md` records an S2 EHZ projected prefix through ticks 0-209, with tick 210 as the first SFX override boundary. `S2AudioOracleComparator` derives the engine speed-up transition from fixture row `SPEED_UP_ROW`, so this is not an independent authenticated engine-input timeline. |
| Request capture at boundary | NOT DONE | Missing observation point | The current S2 producer does not observe the M68K-to-Z80 sound request before the Z80 driver consumes it. `S2OracleEngineCapture.DriverRequest` is presently a source-owned synthetic unit seam only; connecting fixture requests to it would violate comparison-only authority. The tick-210 frontier cannot be fixed by inferring or replaying requests from output or fixture coordinates. |
| Implemented driver gaps | PARTIAL | Code + unit | Shared cadence, total-level, note, and sequencer fixes benefit S2 where applicable. S2-specific request/admission behavior and hidden request state are not complete. |
| Pause, 1-up, fade, ROM-read tables, and DAC/PCM timing | PARTIAL / NOT DONE | Docs + code inspection | The gap analysis documents these driver behaviors, but this audit found no authenticated S2 oracle proof for pause bursts, 1-up save/restore, fade lifecycle, full ROM-read tables, or DAC/PCM timing. |
| Validation and listening | PARTIAL | Unit/parity + diagnostic projection + human-listening checklist | Focused audio/parity tests are green, but S2 has no independent authenticated complete-run request/input frontier. Human listening remains pending. |

## Sonic 3&K status matrix

| Claim/gap | Status | Evidence type | Evidence and limits |
|---|---|---|---|
| Driver routine/spec documentation | PARTIAL | Docs | S3K audio oracle, service projection, and architecture notes exist, but the hidden request boundary remains unresolved. |
| Fixture producer and comparator | DONE as tooling | Code + fixture contracts | S3K authenticated oracle tooling and fixture support landed in `feat(audio): add S3K sound-driver oracle (capture, fixture, comparator)`. Fixture-contract tests protect schema and integrity, but parity still depends on live compare executions. |
| TraceChaser complete-run reference capability | DONE as producer capability | Native observer + two deterministic complete captures | TraceChaser's `S3kCompleteAudioCaptureRunner` and raw sink pin the Knuckles/Super-Emeralds BK2, capture `$1C00..$1FFF` driver RAM plus native services/chip writes for rows `[810,434417)`, and record 254,921,281 events with the source-correct one-active/four-pending cutoff frontier. This is distinct from the Sonic/Tails frame-242 fixture. |
| OpenGGF complete-run consumer integration | PARTIAL / NOT EXECUTABLE | Schema/store/comparator/profile/raw adapter/preflight | The S3K profile, raw adapter, publication-inert preflight, decoder, normalizer, catalog, resolver, common store, comparator, and CLI exist. `S3kCompleteRunReferenceProducer` and `S3kCompleteRunOpenGgfProducer` are absent and both bindings remain unavailable, so no canonical reference-vs-OpenGGF run exists. |
| Engine parity against oracle | PARTIAL / FIXTURE-ASSISTED | Diagnostic execution + unit/parity | `docs/status/audio-frontier-log.md` records a fixture-assisted S3K projection through services 0-127. `S3kOpenGgfAudioCapture` dispatches each nonzero reference mailbox value into the standalone host and copies the reference mailbox into the engine result; the comparator does not independently compare an engine-produced mailbox. Service 128 / source frame 242 is the active hidden stop-request and authority frontier. |
| CPU ownership projection | DONE for current comparator | Live oracle + docs | The current S3K fixture retains both CPU streams. The comparator projection excludes 68k writes when authenticating the Z80-owned service window. |
| Request capture at boundary | NOT DONE | Missing observation point + authority risk | The current capture cannot observe the true pre-consumption M68K-to-Z80 mailbox state that explains the hidden stop request. Output bursts are insufficient evidence for request identity or timing, and a newly captured fixture request may be compared but may not be replayed into OpenGGF as a behavior-driving value. |
| Active fade, PSG mute, and SEGA PCM transport | PARTIAL | Source-backed implementation + unit/oracle-host coverage | E3 PSG mute is implemented end to end and emits `9F BF DF FF` without logical state mutation; the engine oracle host uses the same physical-policy program. The authenticated fixture does not encounter E3, so this is source-closed product behavior rather than a moved comparison frontier. Active fade and full SEGA PCM control flow remain unsupported; service 128 remains a producer-input limitation. |
| Code-byte modulation envelopes and every-frame frequency edge | NOT DONE / UNVERIFIED | Docs + code inspection | S3K code-byte modulation envelopes and the every-frame frequency edge remain outside the proven service projection. |
| Pause, 1-up, fade, ROM-read tables, and DAC/PCM timing | PARTIAL / NOT DONE | Docs + code inspection | The gap analysis documents these behaviors, but this audit found no authenticated S3K oracle proof for pause bursts, 1-up save/restore, fade lifecycle, full ROM-read tables, or DAC/PCM timing. |
| Implemented driver gaps | PARTIAL | Code + unit | Shared cadence and service-projection fixes are landed and tested. The current stop-request boundary is evidence of an unresolved observation gap, not an implemented fix. A future observer/probe revision should not be treated as settled while the request probe is missing. |
| Human listening | PARTIAL | Human listening | The only completed listening evidence is S3K ring panning. The rest of S3K listening remains pending in the checklist. |

## Stale or contradictory documentation

- `docs/architecture/research/audio/2026-08-30-s1-sound-driver-routine-map.md` still describes an H-int-driven second `UpdateMusic` call in the same frame. `docs/architecture/designs/audio/2026-08-30-s1-sound-driver-behaviour-spec.md` later refutes that as deferral rather than a same-frame second call.
- `docs/architecture/designs/audio/2026-08-30-s1-sound-driver-behaviour-spec.md` still has pre-fix statements that S1 TIMEOUT extension was gated on active/duration state. Current `SmpsSequencer` behavior updates the full track walk on the timeout cadence.
- The same S1 spec predates later S1 SFX restore, takeover-order, PSG takeover, ring alternation, and special-pointer fixes. Its gap table should be read as historical unless a row is supported by current code or oracle evidence.
- Public release/status text reports bounded S1 matches under the legacy driver-core contract. Under the production-owned authority vocabulary adopted after this audit, GHZ music is a bounded driver-core equality result and sound-test SFX is a fixture-assisted projection; neither should be generalized to natural gameplay request production.
- `docs/status/audio-frontier-log.md` calls the S2 0-209 and S3K 0-127 prefixes matches.
  Those entries predate this authority audit. They should be read as fixture-assisted
  diagnostic projections until production OpenGGF inputs independently produce the same
  request/speed transitions.

## Comparison-authority finding

Root `AGENTS.md` permits trace data to compare behavior but not to decide what happens in
OpenGGF. A request id or queue vector is a behavior-driving value, not one of the narrow
hardware-timing scheduling exceptions. The earlier S1 gameplay-audio design likewise rejects
sidecar sound-id replay for authoritative validation.

The current S2 default comparator does not replay fixture SFX requests, but it does derive
the speed-up transition from fixture coordinate `SPEED_UP_ROW`. Its explicit
`DriverRequest` overload is acceptable for source-owned synthetic tests only. The current
S3K comparator is more direct: it drives the standalone host from
`referenceTick.mailbox()`. That makes the existing S3K result useful for diagnostic driver
projection, but not an independent authenticated acceptance gate.

TraceChaser policy separately forbids adding new managed 68k execute/request hooks to the
canonical native harness. One-off Lua hooks may diagnose a frontier but cannot publish an
authoritative fixture. Therefore the previously proposed request-fixture injection route is
not an authorized unblock.

## Audit conclusion

The sound-driver RE roadmap is substantially advanced but not complete. S1 has bounded
driver-core equality for GHZ music, a fixture-assisted normal-SFX projection, and several
source-owned implementation fixes. S2 and S3K likewise have useful fixture-assisted
diagnostic projections, but no game yet has a power-on, production-owned audio frontier
under the stricter authority definition. Their active failures sit where the current
producers lack the M68K-side request state and the current engine hosts do not supply an
independently produced input timeline.

The compliant next validation boundary connects the two existing halves: TraceChaser remains
the emulator/reference producer, while OpenGGF consumes its validated raw output, runs the
same ROM/BK2 independently, and compares two canonical `CompleteRunAudioTrace` stores.
Reference data remains comparison-only and never drives the OpenGGF run. The first integration
should reuse the reviewed TraceChaser complete-run observer/profile suite and OpenGGF's
existing complete-run consumer framework rather than inventing a second reference producer.
Output, service bursts, frame/tick coordinates, and fixture-specific behavior still do not
establish a missing request identity or authorize request replay.
