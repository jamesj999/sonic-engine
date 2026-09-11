# S1 audio oracle: committed, re-runnable, validated

**Date:** 2026-08-30
**Branch/worktree:** `feature/ai-sdre-oracle-s1` (`.worktrees/sdre-oracle-s1`), from
`feature/ai-sound-driver-re` with `feature/ai-sdre-gaps` and `feature/ai-sdre-spec-s1`
merged.
**Kind:** validation record for the oracle phase-A/B deliverables of the sound-driver RE
workflow (gap analysis §2 items 1 and 4, §4.2 phases A/B), S1 lane.
**Source rule:** sources-closed — no SMPSPlay, libvgm, GPGX sound code, the reverted
`feature/ai-smps-transaction-parity` branch, or third-party SMPS write-ups were consulted.
ROM behaviour statements cite `docs/s1disasm` or the S1 routine map.

## What landed

The S1 invocation-level driver oracle — per `UpdateMusic` invocation, the normalized
driver-RAM music-slot state plus the ordered YM/PSG bus write stream — is now a committed,
re-runnable fixture pair with a first-divergence comparator, in the shape the oracle
tooling map documented as the one working path:

1. **Committed reference fixtures** under `src/test/resources/audio/parity/s1/`:
   - `s1-soundtest-ghz-reference.v1.jsonl.gz` — the full 14,690-tick GHZ music capture
     (uncompressed SHA-256 `5941958c…`, 117,646,785 bytes; 2.1 MB gzipped). Byte-identical
     to the 2026-08-09 reference, to the 2026-08-30 tooling-audit capture, and to a fresh
     capture recorded this session with the fixed probe.
   - `s1-soundtest-sfx.bk2` — a new sound-test movie: the pinned GHZ movie's full 989-row
     prefix, then eight normal SFX played over the running music via the Level Select sound
     test (`$A0` jump, `$A4` skid, `$A6` spike hit, `$AA` splash, `$B5` ring, `$C6` ring
     loss, `$CC` spring, `$CF` signpost; Left/Right select, A plays — `LevSelControls` /
     `LevSel_PlaySnd`, `sonic.asm:2196-2260,2447-2530`).
   - `s1-soundtest-sfx-reference.v1.jsonl.gz` — 1,967 ticks (uncompressed SHA-256
     `2a3007c0…`, 15,893,088 bytes), epoch at GHZ music acceptance like the music capture,
     terminated by movie end (no recurrence proof — pinned as `cycle_start 0, period 0`).
     Each tick additionally records `dispatches`: the sound ids the ROM dispatched to
     `Sound_PlaySFX` (`$721C6`) during that invocation. Recorded twice (probe debug and
     production modes); the emitted captures were byte-identical.
   - `fixture-manifest.json` — ROM SHA-1/CRC32, BK2 names + SHA-256s + row counts,
     uncompressed capture SHA-256s/sizes/tick counts, cycle metadata, recorder probe
     paths and the capture command shape. `TestS1AudioParityFixtureContract` verifies all
     of it, and fails on any unpinned `.jsonl.gz` in the fixture directory.

2. **Fixed, consumer-side Lua probes** under `tools/audio/probes/` (the pinned TraceChaser
   checkout stays untouched; a gitlink bump needs its own release):
   - `s1_audio_driver_parity_probe.lua` — copy of the pinned probe with the "System Bus"
     defect fixed: the `pc_manifest` fallback read indirect operands through a memory
     domain this GPGX core does not expose (BizHawk falls back to the current domain
     silently). It now maps 68k bus addresses onto the real `68K RAM` / `MD CART` domains
     and asserts via `memory.getmemorydomainlist()` at startup. The production
     `memory_callback` path is unchanged — proven by the fresh capture's byte-identity
     with the pinned reference.
   - `s1_audio_sfx_parity_probe.lua` — the SFX variant: same record shape plus
     `dispatches`, relaxed sound-test contamination asserts (the movie deliberately moves
     the sound number and queues SFX), movie-end termination, `client.exit()` on finish.

3. **Schema/comparator generalisation** in `com.openggf.tools.audio.parity`: SFX capture
   kinds (`s1_soundtest_sfx_driver_reference/openggf`) with movie-bounded metadata
   invariants, per-tick `dispatches` transport, gzip-transparent readers, comparator kind
   pairing and a `DISPATCH_MISMATCH` first-divergence kind, `S1OpenGgfSfxAudioCapture`
   (replays the recorded dispatch sequence through the real `SmpsDriver`/`SmpsSequencer`,
   admitting one SFX sequencer per recorded dispatch before that tick's service — the ROM
   dispatches queued sounds before the same invocation's track walk, routine map §3.2),
   and `SmpsDriver.reapCompletedSequencers()` so a capture host that advances sequencers
   directly releases completed-SFX channel locks exactly as the render loop would.

4. **The wrapper works again** (tooling-map blocker 1): `run_s1_audio_parity.sh` gains
   `--mode music|sfx`, exports `OGGF_INPUT_REPOSITORY_ROOT`/`OGGF_WORKDIR`, uses the
   consumer probes, and requires `--output-root` outside the repository —
   `S1AudioParityTool.resolveSafeOutputRoot` now admits an external run root (TraceChaser's
   `output_policy.py` demands reference captures outside both source trees) while still
   confining in-repo output to `target/audio-parity` and refusing `src/test/resources`.

## Validation evidence

| Check | Result |
|---|---|
| Fixed music probe vs pinned reference | fresh production capture SHA-256 `5941958c…` — byte-identical |
| SFX capture determinism | debug-mode and production-mode runs emitted byte-identical captures; a third run reproduced the same 15,893,088 bytes |
| SFX transport pins | `launch_update_music_invocations` 514 (same 989-row prefix as the music movie); all eight dispatches recorded exactly once at the authored frames |
| Engine music comparison (committed `.gz` fixture) | **`S1 audio parity: MATCH (14690 ticks)`**, exit 0 |
| Break-it-on-purpose, fixture side | run A: tick 5000 `tempoTimeout` 3→4 in a temp copy → `global_state_mismatch, tick 5000, field tempo_timeout, reference 4, openggf 3`, exit 3; run B (concurrent writer's, per 0c1d0580e): tick 5000 DAC `duration` 11→12 → `track_state_mismatch`, exit 3 |
| Break-it-on-purpose, engine side | run A: tick 3001 event 0 `ym2612 p0 reg $A4` 34→35 in a temp copy → `event_value_different, tick 3001, event 0`, exit 3; run B (same provenance): tick 7000 event 0 `ym2612 p0 reg $28` 1→0 → `event_value_different`, exit 3 |
| Engine SFX comparison (first light) | **MISMATCH** at tick 351 — the first SFX dispatch — `event_extra`: engine emits `psg $9F` at SFX admission where the ROM writes nothing (see `docs/status/audio-frontier-log.md`) |
| Unit tests | `com.openggf.tools.audio.parity.*`: green (fixture contract, CLI, comparator, JSONL, normalizer, capture, new SFX schema tests) |

The SFX red is the authorising measurement the gap analysis' phase B asked for: the
engine's channel-steal silence (`SmpsDriver.writePsg` → `silencePsgChannel`) is observable
at the oracle boundary, and S1 `Sound_PlaySFX` performs no such write at admission (routine
map §6). The fix belongs to the override/restore implementation lane (gap §1.2 #6), not
here.

## Provenance note

Midway through this lane, an unidentified concurrent writer (most likely an agent from
another session sharing this checkout; the session coordinator verified no sibling lane in
this session referenced the worktree) applied edits to the same files that implemented the
same schema this lane had designed — the design was fully derivable from this lane's
already-on-disk probe and movie files. All of that code was reviewed line-by-line by this
lane before being kept, duplicate declarations from the interleaved edits were removed, and
every behavioural claim above was re-validated by runs performed by this lane. Files where
kept code includes the concurrent writer's text: `AudioParityJsonl.java`,
`AudioParityMetadata.java` (invariant branch), `AudioParityTick.java`,
`AudioParityComparator.java`, `S1AudioParityTool.java`, `S1OpenGgfAudioCapture.java`
(visibility widenings), `S1OpenGgfSfxAudioCapture.java`, `SmpsDriver.java`
(`reapCompletedSequencers`), the music fixture gzip, and the CHANGELOG entry.

## Known limitations (honest scope)

- The SFX capture's normalized state still covers the ten music slots plus the write
  stream; S1 SFX track RAM (`$F220-$F33F`) and the special-SFX slots are not yet part of
  the compared state vocabulary. SFX behaviour is observable through the write stream and
  the music slots' `overridden` bits.
- The recurrence-proof shape exists only for the music capture; the SFX capture is
  movie-bounded by design.
- `launch_update_music_invocations` for both movies is pinned to 514; a different launch
  prefix needs a schema revision.
- The reference recorder still requires the direct EmuHawk launcher path (a reachable X
  server, hardware GL); captures on this machine were run detached with wall-clock
  timeouts. During this session an external process repeatedly SIGTERMed emulator runs;
  reruns succeeded.
