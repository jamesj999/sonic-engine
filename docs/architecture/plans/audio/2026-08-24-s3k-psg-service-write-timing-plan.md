# S3K PSG Service-Write Timing Implementation Plan

## Status

**Stopped after source-first diagnostics; do not execute Tasks 1-6.** Native
full-prelude capture assigned the large delay to the 68K VBlank Z80 bus hold,
and the relative-only prototype failed its predeclared PCM-improvement gate.
No timing runtime from that prototype is part of the branch. The paired audio
default migration corrected old installed configuration, but a later listening
gate proved it was not the complete Collapse fix. Final-speaker A/B evidence
instead identified the generic GPGX `hq_psg=1` band-limited renderer setting;
that separate correction is implemented and verified outside Tasks 1-6. See
the design status and
`docs/architecture/research/audio/s3k-collapse-final-pcm-hq-psg-v1.json`.

> Execute this plan only on `bugfix/ai-s3k-sfx-tail-fidelity`. Do not merge or
> push until the listening gate is positive.

**Goal:** Preserve source-derived within-VInt placement of covered locked-on
S3K SFX PSG writes without emulating the Z80 or changing YM, S1, or S2 timing.

**Design:**
`docs/architecture/designs/audio/2026-08-24-s3k-semantic-service-timing-design.md`

**Authority:** locked-on S3K ROM SHA-1
`CFBF98C36C776677290A872547AC47C53D2761D6`, `SonicDriverVer=4`,
`fix_sndbugs=0`, `FixMusicAndSFXDataBugs=0`, `FixBugs=0`.

## Global execution rules

- Run every Maven gate under the same verified JDK 21 shell. Record `mvn -v`
  before each task's first Maven command.
- Use `s3k.gen`, `s1.gen`, and `s2.gen` only after verifying their SHA-1s.
- Allocate native build/capture output with `agent-scratch new`; never use
  `/tmp`, the repository, or a user ROM as scratch output.
- Observe strict RED before production changes, then GREEN.
- Production timing constants come only from checked source rows. Native write
  cycles and PCM are comparison oracles.
- Do not introduce sound-ID, zone, movie, frame-number, or captured-amplitude
  runtime selectors.
- Each commit supplies all seven policy trailers explicitly. A production
  commit touching `src/main` stages `CHANGELOG.md` with `Changelog: updated`.
- For Tasks 1, 2, and 5, the remaining trailer decisions are exactly
  `Guide: n/a: no contributor workflow change`,
  `Known-Discrepancies: n/a: no intentional general discrepancy change`,
  `S3K-Known-Discrepancies: n/a: no intentional S3K discrepancy change`,
  `Agent-Docs: n/a: no agent guidance change`,
  `Configuration-Docs: n/a: no configuration change`, and
  `Skills: n/a: no skill change`. Tasks 3 and 4 use the same six decisions plus
  `Changelog: updated`. If Task 6 stages a mapped discrepancy file, its matching
  trailer is `updated` rather than `n/a`.
- If a task reveals a materially different architecture, stop implementation,
  amend the design and this plan, perform a fresh review, then continue.

## Task 1: Freeze the native PSG comparison oracle and RED

**Files**

- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/0002-s3k-audio-parity-events.patch`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/0003-s3k-chip-pcm-events.patch`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/s3k-parity-artifact-lock.json`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/s3k-pcm-artifact-lock.json`
- Modify: `tools/bizhawk-headless/tests/GpgxS3kAudioParityManifestTests.cs`
- Modify: `tools/bizhawk-headless/src/Core/GpgxS3kAudioParityDepartures.cs`
- Add: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/s3k_psg_timing_harness.c`
- Add: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/s3k-psg-timing-run.sh`
- Add: `tools/audio/capture-s3k-psg-service-timing.sh`
- Add: `docs/architecture/research/audio/s3k-collapse-dash-psg-timing-oracle-v1.json`
- Modify: `src/test/java/com/openggf/audio/driver/TestS3kCollapseDashSfxParity.java`

1. Verify `mvn -v`, ROM SHA-1, native patch apply/reverse cleanliness, and
   current native artifact locks. This capture uses the existing bounded
   `injected_z80_queue` path after neutral gameplay and StopAll; it does not
   depend on a BK2 or hydrate production state from a trace.
2. Add an opt-in native event that retains, for only the authenticated Collapse
   and Dash service groups: VInt-relative master cycle, PSG value, physical owner slot,
   source PC/opcode, service ordinal, DMA-stall count, fault, and overflow.
   Preserve all existing ABI records; use a new diagnostic-only departure if
   widening an existing record would invalidate unrelated locks.
3. Extend the PCM capture to retain the aligned PSG component samples needed for
   attack, repeat, and terminal frames. Cap every stream and publish only after
   zero fault/overflow.
4. Add a standalone native self-test for event layout, owner rejection,
   equal-cycle ordering, cap N/N-1, fault publication, and zero-DMA markers.
5. Implement the tracked capture wrapper with explicit
   `--case collapse|spindash-release --rom --bizhawk-home --output` arguments,
   exact ROM/core/patch/tool hash checks, create-new output semantics, bounded
   disk preflight, and the environment below. Capture create-new A and B for
   both cases in separate clean diagnostic roots with
   `OPENGGF_GPGX_S3K_FIRST_SLICE_CAPTURE=1`,
   the corresponding `OPENGGF_S3K_PARITY_CASE`, the verified `S3K_ROM_PATH`, and unique
   `OPENGGF_S3K_PARITY_OUTPUT` paths. Require byte-identical canonical JSON,
   gzip/integrity checks where applicable, exact patch/core/ROM hashes, and zero
   fault/overflow/DMA.
6. Rebuild both native roots from the pinned source/toolchain/stock inputs,
   assert byte-identical core/install trees, ELF/export/invisible-state bounds,
   and update both diagnostic-only artifact locks with the patch, self-test,
   core, build-ID, and log hashes. Store the compact oracle under
   `docs/architecture/research/audio/`; do not
   store raw PCM or instruction payloads in Git. Record scratch retention paths
   and SHA-256s in the oracle metadata.
7. Add strict Java REDs proving current OpenGGF fails all of:
   - the 107-non-zero-sample native terminal prefix;
   - attack and repeat component digests despite equal effective PSG state;
   - immediate terminal mute;
   - a deliberately whole-VInt delayed terminal mute.
8. Run the new native harness and the focused Java RED. Preserve the exact RED
   assertions in the task report.

**Commit**

Commit retained diagnostic infrastructure/oracle/tests only after native A/B is
reproducible. Use `test(audio): retain S3K PSG service timing oracle` with all
seven trailers; `Changelog: n/a: diagnostic and test authority only`.

## Task 2: Build the source-derived timing artifact and ROM census

**Files**

- Add: `tools/audio/build-s3k-psg-service-timing.py`
- Add: `docs/architecture/research/audio/s3k-psg-service-timing-source-map-v1.json`
- Add: `docs/architecture/research/audio/s3k-psg-service-timing-calculation-v1.json`
- Add: `docs/architecture/research/audio/2026-08-24-s3k-psg-service-timing-calculation.md`
- Add: `src/test/java/com/openggf/audio/smps/TestS3kPsgServiceTimingCalculation.java`
- Add: `src/test/java/com/openggf/audio/smps/TestS3kPsgTimingReachability.java`
- Modify: `src/test/java/com/openggf/tools/audio/s3kparity/TestS3kSmpsFirstSliceInventory.java`

1. Decode the retained executed instruction rows and map every PC to one exact
   checked-out disassembly label/line interval. Reject overlap and unknown PCs.
2. Generate primitive executed rows for:
   - interrupt entry through `zUpdateSFXTracks`;
   - inactive fixed FM3/FM4/FM5/FM6 and PSG1/PSG2/PSG3 slots;
   - reached earlier active FM slot summaries needed by Collapse/Dash;
   - `zUpdatePSGTrack` timer sustain/expiry;
   - reached note/rest/tie and coordination-command paths;
   - reached modulation and PSG volume-envelope branches;
   - frequency/noise/attenuation output;
   - `zRestTrack`, `zSilencePSGChannel`, `cfStopTrack`, and `zStopPSGTrack`.
3. Each row records PC, opcode bytes, branch outcome, Z80 T-states, bank-window
   wait role, semantic event, value predicate, and source citation. Use checked
   arithmetic and explicit caps: 65,536 rows, 4,096 semantic events, and 4,096
   PSG writes per service.
4. Generate covered source-path programs without reading native write deltas.
   Then independently compare their calculated write positions against every
   retained native group and the predeclared entry-phase window.
5. Before seeing candidate PCM results, pin the entry-phase set, terminal-sample
   tolerance, and per-phase improvement rule in the calculation document.
6. Run a ROM-backed static reachability census over every S3K SFX stream. Emit
   grammar identities and classify every reachable PSG service path as
   `COVERED` or `UNAVAILABLE(reason)`. Assert Collapse and Dash are covered and
   select one unrelated PSG noise and one PSG tone control by grammar, not ID.
7. Add mutation tests for deletion, reorder, opcode, branch, cost, citation,
   value predicate, selector, cap, arithmetic overflow, and fitted native-delta
   substitution.
8. Regenerate the calculation and census in two fresh processes and compare
   outputs byte-for-byte.

**Focused gate**

```bash
mvn -v
mvn -Dmse=off -Ds3k.rom.path="$PWD/s3k.gen" \
  -Dtest=com.openggf.audio.smps.TestS3kPsgServiceTimingCalculation,com.openggf.audio.smps.TestS3kPsgTimingReachability test
```

**Commit**

Commit generated source authority and tests as
`test(audio): derive S3K PSG service timing`; all seven trailers,
`Changelog: n/a: source calculation and tests only`.

## Task 3: Implement the bounded PSG timeline and exact chip drain

**Files**

- Add: `src/main/java/com/openggf/audio/synth/PsgWriteTimeline.java`
- Modify: `src/main/java/com/openggf/audio/synth/PsgChip.java`
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java` (`Snapshot` record)
- Add: `src/test/java/com/openggf/audio/synth/TestPsgWriteTimeline.java`
- Modify: `src/test/java/com/openggf/audio/synth/TestPsgChipSnapshot.java`
- Modify: `src/test/java/com/openggf/audio/synth/TestS3kChipPcmDiagnosticTap.java`
- Modify: `CHANGELOG.md`

1. Write REDs for bounded commit, nondecreasing due cycles, dense ordinals,
   equal-cycle ordering, byte validation, stale generation, overflow, N/N-1,
   reset-before-due, ordinary-completion retention, and immutable snapshots.
2. Write chip REDs using a small independent GPGX PSG oracle:
   - advance exactly to a non-output-aligned master cycle;
   - round the internal PSG clock to 240 master cycles on write;
   - preserve tone counters, noise LFSR, latch, deltas, and blip state;
   - apply multiple equal-cycle writes in ordinal order;
   - produce byte-identical PCM and deep-equal snapshots under arbitrary output
     buffer partitioning.
3. Implement `PsgWriteTimeline` as a package-private fixed-capacity owner. Entries
   are self-contained and generation-stamped; no live sequencer references.
4. Add package-private `PsgChip` timeline installation and a cycle-segmented
   render path matching pinned GPGX `psg_write(clocks, data)` semantics. Do not
   quantize to an output sample.
5. Keep ordinary `writePsg` immediate when no timeline predecessor exists.
   Add a package-private drain diagnostic carrying due cycle and ordinal; keep
   the public `ChipWriteObserver` unchanged and drain-bound.
6. Capture/restore timeline state through the synthesizer snapshot. Hard reset,
   replacement, and full silence advance generation and discard pending writes
   without callbacks.
7. Run mutation checks that weaken generation, capacity, and exact-cycle drains;
   restore each mutation and rerun GREEN.

**Focused gate**

```bash
mvn -v
mvn -Dmse=off \
  -Dtest=com.openggf.audio.synth.TestPsgWriteTimeline,com.openggf.audio.synth.TestPsgChipSnapshot,com.openggf.audio.synth.TestS3kChipPcmDiagnosticTap test
```

**Commit**

Stage `CHANGELOG.md` and commit `feat(audio): schedule PSG writes within driver services`
with `Changelog: updated` and the other six explicit trailers.

## Task 4: Journal and resolve the S3K SFX PSG service

**Files**

- Add: `src/main/java/com/openggf/audio/smps/S3kPsgServiceTimingProfile.java`
- Add: `src/main/java/com/openggf/audio/smps/PsgServiceTimingProfile.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/rewind/SmpsDriverSnapshot.java`
- Modify: `src/main/java/com/openggf/audio/rewind/SmpsSequencerSnapshot.java` only if new live semantic state survives a service boundary
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/audio/presentation/SmpsAssetCatalog.java`
- Add: `src/test/java/com/openggf/audio/driver/TestSmpsDriverPsgWriteTimeline.java`
- Add: `src/test/java/com/openggf/audio/smps/TestSonic3kPsgServiceTimingProfile.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestSmpsAssetCatalog.java`
- Modify: `CHANGELOG.md`

1. Write REDs for the disabled profile identity and catalog-copy semantics.
2. Add private semantic event/value records and the finite profile result
   `COVERED`/`UNAVAILABLE`. Keep all timing controls package-confined; add an
   architecture guard against calls from unrelated production packages.
3. Write REDs proving one VInt batch journals all SFX PSG writes and active-slot
   summaries without chip publication. Assert native PSG1/2/3 order despite
   sequencer insertion order, duplicate-owner rejection, and whole-journal
   immediate fallback when any sibling is unavailable.
4. Emit semantic facts from the existing branch owners in `SmpsSequencer`.
   Do not add a second stream interpreter or a sound-ID classifier.
5. Resolve the complete journal only after all SFX sequencers finish and before
   music service publishes PSG writes. Anchor at the PSG rendered frontier.
   Assert every due cycle is before the next existing driver-service anchor.
6. Commit covered writes atomically to `PsgWriteTimeline`. Fence later same-VInt
   unprofiled PSG writes after the final timed due cycle. Leave their status
   timing-partial.
7. Preflight the aggregate PSG horizon once at the outer batch boundary.
   Add exact N/N-1 tests that prove no sequencer, timeline, chip, observer,
   lock/claim, phase, or ordinal mutation on failure.
8. Add snapshot-before-drain, partial-drain, restore, live-command rollback,
   observer-exception retry-once, full-silence, replacement, and
   `adoptActiveSfxFrom` tests. Pending entries retain stable source descriptors,
   never live sequencer references.
9. Enable the profile only for the authenticated locked-on S3K config. Assert
   S1, S2, standalone-S3, and disabled configs retain immediate identity.
10. Add a production-shape architecture guard for private timing protocol,
    exact profile installation, absence of sound/zone selectors, and absence of
    runtime references to research/oracle artifacts.

**Focused gate**

```bash
mvn -v
mvn -Dmse=off \
  -Dsonic1.rom.path="$PWD/s1.gen" \
  -Dsonic2.rom.path="$PWD/s2.gen" \
  -Ds3k.rom.path="$PWD/s3k.gen" \
  -Dtest=com.openggf.audio.driver.TestSmpsDriverPsgWriteTimeline,com.openggf.audio.smps.TestSonic3kPsgServiceTimingProfile,com.openggf.audio.driver.TestSmpsDriverSnapshot,com.openggf.audio.smps.TestSmpsSequencerSnapshot,com.openggf.audio.TestAudioPresentationArchitectureGuard,com.openggf.audio.driver.TestS3kCollapseDashSfxParity test
```

**Commit**

Stage the updated `CHANGELOG.md` and commit
`fix(audio): preserve S3K PSG service write timing` with all seven trailers and
`Changelog: updated`.

## Task 5: Close native component parity and unsupported controls

**Files**

- Modify: `src/test/java/com/openggf/audio/driver/TestS3kCollapseDashSfxParity.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestS3kBlueSphereSfxParity.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestS3kFinalPcmParityProbe.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`
- Modify: `docs/architecture/research/audio/2026-08-24-s3k-psg-service-timing-calculation.md`
- Add: `docs/architecture/validation/audio/2026-08-24-s3k-psg-service-write-timing-validation.md`

1. Turn Task 1 REDs GREEN without loosening native values or tolerances.
2. Run the full predeclared entry-phase matrix. Require every phase's aggregate
   component-PCM error to improve versus immediate publication; report all
   phases and reject post-result phase selection.
3. Assert Collapse and Dash exact effective writes and semantic state, improved
   attack/repeat component PCM, and the bounded terminal partial frame.
4. Assert the unrelated PSG noise and tone controls use the same grammar-owned
   profile. Assert at least one unavailable effect is byte-identical to the
   pre-feature immediate write/state behavior.
5. Rerun S3K YM controls: Blue Sphere, Ring Loss, Spike Hit, Spindash Release,
   music restore, invincibility note fill, rewind, and observer suites.
6. Rerun S1/S2 PSG/YM cadence, priority, pause, snapshot, and presentation
   controls with authenticated ROMs.
7. Run both native harness roots and reproduce the retained oracle/artifact
   from clean inputs. Record exact counts and SHA-256s in validation.
8. Perform a fresh self-review pass over source authority, failure atomicity,
   snapshot coverage, public API confinement, and sound-ID/zone carve-outs.
   Any Important issue returns to the owning task with a new RED.

**Commit**

Commit tests and validation as `test(audio): verify S3K PSG service write timing`
with all seven trailers; use `Changelog: n/a: verification for the landed behavior`.

## Task 6: Comparable baseline, full regression, package, and listen handoff

**Files**

- Modify: `docs/architecture/validation/audio/2026-08-24-s3k-psg-service-write-timing-validation.md`
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md` only if the landed behavior closes or narrows an existing entry
- Modify: `README.md` only during later integration into `develop`, as required by merge policy

1. Fetch and fast-forward the branch checked out in the main workspace without
   switching it or disturbing user changes.
2. Create an isolated baseline worktree at the updated integration base. Run the
   exact JDK21/all-three-ROM full suite there and save the full log plus sorted
   failure/error identity ledger under agent scratch. Record counts and SHA-256s.
3. Run the identical full suite on this development branch. Compare identity and
   failure/error status; no base-passing test may become red and no attributable
   baseline red may worsen.
4. Rerun the focused native/source/timeline/Collapse/YM/cross-game gates from the
   earlier tasks, record exact commands/counts/log hashes and baseline/candidate
   ledger hashes in validation, and commit those tracked documentation updates
   with all seven explicit trailers. If `docs/S3K_KNOWN_DISCREPANCIES.md` is
   staged, use `S3K-Known-Discrepancies: updated`; otherwise use the documented
   `n/a` decision. Do not record hashes that depend on the commit being created.
5. Package that exact clean committed HEAD with tests enabled; use
   `-Dmaven.test.failure.ignore=true` only to allow assembly after the recorded
   red baseline. Verify JAR ZIP integrity, embedded commit, dirty=false, size,
   and SHA-256.
6. Hand off the retained branch/worktree/JAR and listening checklist. Do not
   merge or push yet.
7. After the user reports a positive listen, follow the repository integration
   workflow: update `README.md`, merge into the main-workspace branch, rerun the
   identical full suite and identity comparison, push only the main-workspace
   branch, then clean the fully merged worktree/local branch.

## Listening checklist

- Collapse in isolation: attack, repeat texture, and complete tail.
- Collapse over music: both channels, channel restoration, no extra duration.
- Spindash Release.
- Blue Sphere and ring collection, including first/repeat/after-delay cases.
- Invincibility melody's four short note endings.
- One unrelated PSG-noise SFX and one unrelated PSG-tone SFX.

The result is accepted only if it is a clear positive improvement and no listed
control regresses. Otherwise retain the branch, capture the exact scenario with
the diagnostic oracle, and return to the smallest owning grammar path.
