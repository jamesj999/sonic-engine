# S2 driver oracle: reference capture, fixture, and comparator

**Date:** 2026-08-30
**Branch / worktree:** `feature/ai-sdre-oracle-s2` (`.worktrees/sdre-oracle-s2`),
from `feature/ai-sound-driver-re` with `feature/ai-sdre-gaps` and
`feature/ai-sdre-spec-s2` merged.
**Kind:** research record + regeneration procedure for the committed S2
driver-oracle fixture and its comparator.
**Source rule:** sources-closed — the disassembly is the behavioural authority;
no emulator sound source or third-party SMPS documentation was consulted.
Engine code was read only for "engine today".

This is the S2 leg of the oracle phase the gap analysis planned
(`docs/architecture/designs/audio/2026-08-30-sound-driver-re-gap-analysis.md`
§2, §4.2 phase B), in the shape the S1 GHZ oracle proved out: **driver-RAM
track state plus the ordered YM/PSG write stream, per driver invocation**,
with the reference recorded from the shipped ROM running under the emulated
hardware, and the engine driven headlessly through the real
`SmpsDriver`/`SmpsSequencer`.

## 1. What is committed

| Artifact | Path |
|---|---|
| Reference capture (gzip JSONL, 4.85 MB) | `src/test/resources/audio/parity/s2/s2-ehz-reload-w10150-10900.raw.jsonl.gz` |
| Fixture metadata (identities, window, recorder pins, capture command) | `src/test/resources/audio/parity/s2/s2-ehz-reload-w10150-10900.metadata.json` |
| Capability file used by the capture (re-pinned executable field only) | `src/test/resources/audio/parity/s2/capture-capability.json` |
| TraceChaser harness patch that records the window | `docs/architecture/research/audio/2026-08-30-s2-oracle-tracechaser-window-capture.patch` |
| Reader / RAM decoder / engine capture / comparator / CLI | `src/main/java/com/openggf/tools/audio/parity/s2/` |
| Integrity + break-it tests | `src/test/java/com/openggf/tools/audio/parity/s2/` |
| Frontier record | `docs/status/audio-frontier-log.md` |

The fixture covers movie rows **[10150, 10900)** of the pinned
complete-emeralds movie (`sonic-2-sonic-tails-complete-emeralds.bk2`,
SHA-256 `e850798f…`): the EHZ music reload after the first special stage
(anchor row 10195), ≥11.75 s of music from a clean `zBGMLoad`, the speed-up
command at row 10791, and thirteen distinct SFX ids
(`A0 A3 A4 A6 AF B5 BC BE C1 CA CC CE E0` — jump, hurt, skid, spike hurt,
shield, ring right/left, spin-dash release, roll, …, spindash rev), plus lag
frames. Per row it records the **full Z80 RAM image `$0000-$1FFF`** (the S2
driver keeps state in self-modifying code, so nothing less is a faithful
state capture) and every raw observer event, including YM/PSG bus writes
attributed to the driver service that issued them.

## 2. How the reference was captured (regeneration procedure)

The capture runs entirely through the TraceChaser **headless** harness — no
EmuHawk, no display, no Lua — with BizHawk 2.11's GPGX Waterbox core carrying
the pinned patch-0001 audio observer.

1. **Initialise the submodule:** `git submodule update --init --recursive
   tools/tracechaser` (gitlink `9e51ff79e7…`).
2. **Apply the committed harness patch** (adds the windowed capture entry
   point; tests-assembly only, so the production executable hash is
   unchanged):
   `git -C tools/tracechaser apply ../../docs/architecture/research/audio/2026-08-30-s2-oracle-tracechaser-window-capture.patch`.
3. **Build:** `cd tools/tracechaser/bizhawk-headless && BIZHAWK_HOME=<stock
   BizHawk 2.11> ./build.sh`. The build is deterministic on this toolchain
   (Mono 6.12, the hash-locked Roslyn); the production exe hash
   `363e27d31d…` must match the `task8_harness_executable_sha256` field of
   `capture-capability.json` — that field is the only difference from the
   pinned `fixtures/gpgx-audio-capability-v1.json`, and it is exactly the
   field the capability's template digest normalises
   (`S2AudioObserverProfile.CapabilityTemplateSha256`).
4. **Assemble the observer BizHawk home.** Copy a verified stock BizHawk 2.11
   Linux x64 distribution, then:
   - replace `dll/gpgx.wbx.zst` with the patch-0001 observer core, SHA-256
     `e65315743a…` (the exact artifact-lock identity in
     `native/gpgx-audio-observer/artifact-lock.json`);
   - create `gpgx-audio-observer-source/` containing the decompressed core
     `gpgx.wbx` (`f57b7a9423…`) and `identity.json`.
   On this machine the lock-matching core pair already existed (a prior
   observer build); `identity.json` was **reconstructed byte-exactly** from
   the artifact lock: `build-core.sh:299` prints it from values that are all
   either pinned in the lock or computed from committed inputs
   (`verified_input_identity_sha256` is the SHA-256 of four fixed lines —
   BizHawk commit, sysroot tree digest, zstd digest, and the SHA-256 of
   `build-recipe.json`). The reconstruction is verified, not trusted: its
   SHA-256 equals the lock's `identity.sha256` (`815bfde02d…`), which is also
   what `S2AudioObserverProfile.VerifyInstallation` re-checks at capture time.
   A from-scratch rebuild via `fetch-source.sh`/`prepare-toolchain.sh`/
   `build-core.sh` remains the fully independent route.
5. **Record the window** (the exact command with this fixture's values is in
   the metadata JSON):

   ```bash
   env -u DISPLAY BIZHAWK_HOME=<observer home> \
     MONO_PATH=$BIZHAWK_HOME/dll LD_LIBRARY_PATH=$BIZHAWK_HOME/dll \
     S2_ROM_PATH=<S2 World REV01> S2_BK2_PATH=<committed movie> \
     OGGF_S2_ORACLE_MANIFEST=<harness>/fixtures/gpgx-audio-service-manifests-v1.json \
     OGGF_S2_ORACLE_CAPABILITY=<fixture dir>/capture-capability.json \
     OGGF_S2_ORACLE_OUTPUT=<scratch>/window.jsonl \
     OGGF_S2_ORACLE_FIRST_ROW=10150 OGGF_S2_ORACLE_END_ROW=10900 \
     OGGF_S2_ORACLE_CAPTURE=1 \
     mono bin/Release/BizHawk.Headless.Gpgx.Tests.exe \
       --name-exact "S2OracleWindowCaptureTests capture a bounded raw oracle window" --jobs 1
   gzip -9 -n window.jsonl
   ```

   The capture replays the movie from power-on with full profile validation
   (`ValidateRom`, `OpenMovie`, `LoadCapability`, `VerifyInstallation`), and
   publication begins at row 10150 as a publication-epoch boundary — chip
   latches are preserved and reported in the baseline record. Observation is
   never seeded from anything but the movie.

Windowed-schema notes (`openggf.s2-oracle-audio-raw.v1`): identical per-record
shapes to the pinned full-run `openggf.s2-complete-run-audio-raw.v1`, with the
window's own `first_row`/`exclusive_end` in the metadata. Chip-event decoding:
raw event kind 3 carries the YM bus address in `subject`
(0/2 = port0/port1 address latch, 1/3 = port0/port1 data), kind 4 is a PSG
data byte — the literal `gpgx_audio_trace_fm_write(address & 3, data)`
contract in patch 0001. The raw stream retains both parent `zVInt` writes
(service kind 3, including the multi-frame song load) and nested
`zUpdateMusic` writes (kind 9). Completed-update oracle ticks compare kind 9
only; the parent load burst remains capture evidence and is specified in
behaviour spec §5.2, but is not folded into a child-service write stream.

## 3. The tick model: one completed `zUpdateMusic`, not one frame

The raw capture is per-frame; the oracle tick is recovered from the
observer's **service stream** (manifest kind 9 = `UpdateMusic`). The window
itself shows why this recovery is not optional:

- The Saxman EHZ load runs with interrupts masked across movie rows
  **10195-10200** — those frames' RAM images show the half-initialised load
  (`zVar` zeroed, tracks empty) and contain no `zUpdateMusic` service.
- Update 0 **begins** in row 10201 but runs across the frame boundary and
  **completes** in row 10202. The caught-up Z80 misses row 10202's new V-int:
  `TempoTimeout` holds `3Ch` across those two snapshots and the next `+9Eh`
  recurrence starts at 10203.

So the reference's invocation stream is: update 0 begins in row 10201 and
completes in row 10202 (load + first update in one V-int), update 1 completes
inside row 10203, then one per row. `S2AudioOracleComparator.buildTicks`
recovers this from kind-9 service **completion** markers and uses the
completion-frame RAM image; the kind-9 writes since the previous completion
belong to that tick. Using begin-frame RAM had exposed a mid-track-walk image
(DAC/FM1 advanced, FM2 onward stale) as if it were post-update state. No
realignment beyond the service boundary exists: reference tick `n` is
compared against engine update `n`.

## 4. Comparison vocabulary (field registry)

`S2OracleComparison` is the registry. Compared this tier, per music slot
(DAC, FM1-6, PSG1-3): `active`, `dataPointer` (engine `pos + z80StartAddress`),
`durationTimeout`, `savedDuration`, `transpose`, `volume`, `voiceIndex`,
`tempoDivider`, `detune`, `controlBits` (playing/do-not-attack/modulation),
`freq` (FM `block<<11|fnum`; PSG table word), `volFlutter` (PSG cursor);
globals `currentTempo` (speed-shoes-effective) and `tempoTimeout`. The write
stream compares the ordered completed-`zUpdateMusic` writes (service kind 9);
DAC-sample, drum-dispatch and SEGA-PCM writes (kinds 4/6/7) are the PCM tier,
excluded by service attribution rather than by register heuristics. Every
excluded ROM field is listed in `S2OracleComparison.NOT_COMPARED` with its
reason — the registry is a classification, not silent scope loss.

The engine side (`S2OracleEngineCapture`) drives EHZ through the real S2
driver stack, emits the shipped song-load silence burst before tick 0
(spec §5.1-5.2, FixDriverBugs=0) but keeps those parent-service writes outside
the kind-9 stream, applies `setSpeedShoes(true)` at the tick
whose reference row consumed the movie's `FBh` command, and maps each
post-update `SmpsSequencerSnapshot` into the ROM vocabulary (EM §2.3 mapping).
The engine never reads the fixture; its inputs are source-owned requests —
trace data stays comparison-only. The capture host now accepts an explicit,
ordered raw-SFX request stream, resolves the driver's ring-speaker toggle
(`zPlaySound_CheckRing`, `sd:2116-2135`), loads the selected ROM SFX, and
admits it before the target outer-frame service. A first raw `B5h` request
therefore resolves to `CEh` from the shipped initial `zRingSpeaker = 0`, while
the next raw ring request resolves to `B5h`. The unit vector proves raw `B5h`
and explicit `CEh` produce the same first-update writes; it does not derive a
request time or ring side from the oracle's output.

## 5. Measurements and break-it evidence

Recorded in `docs/status/audio-frontier-log.md` (2026-08-30 entry): expected
red — DIVERGENCE at tick 0 (row 10201), `global.tempoTimeout` expected `0x3c`
actual `0x0`, 698/698 ticks divergent; the ROM seeds the tempo accumulator at
song load and accumulates on every update including the first
(`s2.sounddriver.asm:545-551`, `1820-1822`), which the engine does not model
(gap analysis §1.2 #1/#2). The comparator was broken on purpose before its
first result was trusted: a corrupted reference byte and a corrupted engine
write each move the report to exactly the corrupted tick and field, and the
untampered self-comparison is `MATCH (698 ticks)`
(`TestS2AudioOracleComparator`).

The 2026-08-31 completion-boundary correction supersedes the begin-frame
frontier above: FM2's apparent `1424h` pointer was the RAM image taken halfway
through update 0, not driver behaviour. With completion-frame state and
kind-9-only writes, the engine matches the music-only prefix through ticks
0-209. The current first divergence is tick 210 (movie row 10412): the
reference has FM4 `PlaybackControl` override bit 2 set by an admitted SFX, so
`zFMUpdateFreq` suppresses its modulation write (`sd:1088-1092`); the
music-only engine capture intentionally injects no SFX and therefore emits
that write. This is the declared next-tier SFX admission boundary, not an EHZ
music divergence.

## 6. Deviations, limitations, open questions

- **TraceChaser changes are a committed patch, not a gitlink bump.** The
  windowed capture entry lives in the tests assembly of the pinned harness;
  OpenGGF cannot push TraceChaser, so the patch file plus this procedure is
  the re-runnable form. Upstreaming it into TraceChaser (and a CLI mode
  rather than an env-gated test) is the follow-up.
- **Capability executable re-pin.** `capture-capability.json` differs from the
  pinned capability fixture only in the template-normalised
  `task8_harness_executable_sha256` field: the committed capability predates
  the TraceChaser extraction and pins an executable this source tree no
  longer produces. All behavioural digests (frontier, terminal Z80, event
  digest) are unchanged.
- **Observer install evidence tree.** The capture verified the full
  `VerifyInstallation` contract (core hashes, managed DLL hashes, identity
  JSON hash) but the machine's install lacks the source-bundle evidence files
  a fresh `install-core.sh` run would carry; the byte-exact identity
  reconstruction above documents why this is equivalent for capture purposes
  and how to do better.
- **Snapshot phase.** The per-row RAM image is sampled at the emulated frame
  boundary. The load-stall finding shows this is *observable*: a long service
  leaves mid-service RAM in the frames it spans. The tick recovery makes the
  comparison immune to it for `zUpdateMusic`, but SEGA-PCM frames (interrupts
  off) would need the same treatment (gap analysis §5 open question, now
  partially answered empirically: the frame-boundary snapshot is **not**
  always post-`zVInt`-settled).
- **NTSC only**, music + speed-up comparison tier; pause, 1-up, and the
  request/admission timeline are the next tiers (gap analysis §4.2 phase C).
  The engine-side SFX stimulus and ROM-backed admission path now exist, but
  the committed v1 window samples Z80 RAM only after `host.Advance`: its queue
  bytes have already been consumed. Observer ABI 4 can snapshot ranges only
  at completion boundaries and its observation marker does not carry the 68k
  request register, so it cannot recover the pre-consumption request stream.
  At tick 210 the live SFX pointer `F839h` maps through the ROM table to the
  `CEh` left-ring program (`F829h-F83Dh`), but that post-admission state is an
  outcome, not authority to inject raw `B5h` at that tick. The leading
  reference `PSG 9Ah` is not a ring identity either: both `B5h` and `CEh` are
  FM5 programs in the disassembly, so attributing that write to the ring would
  be another output-derived inference.
  Moving this frontier requires a producer probe at the 68k-to-Z80 request
  mailbox before consumption; neither post-frame RAM nor comparison writes
  may be used to synthesize that schedule. A historical ABI-5 branch was also
  inspected and rejected as evidence because its locked core/artifact
  identity remains ABI 4.
- **Engine music-id mapping.** Engine `Sonic2Music.EMERALD_HILL` (0x81) plays
  the song the ROM driver calls request id `0x82`; the oracle profile carries
  that mapping explicitly (`Sonic2SmpsLoader.findMusicOffset` documents the
  systematic shift).
