# Runbook — S1 complete-run regen with recorder v3.7 (unblocks the gated red frontiers)

**Purpose.** As of develop `e85266286` the S1 complete-run trace frontiers split into two groups:
a handful of *fixed* greens (GHZ1, GHZ2, SYZ2, SBZ3) and 15 reds whose roots are
all individually decoded but **gated on diagnostic data the currently-installed
traces do not contain**. Many S1 complete-run traces in `src/test/resources/traces/s1/`
were recorded with the old lua-3.2 recorder (no `object_near` / `slot_dump` aux),
and none predate recorder **v3.7**, which adds the two aux streams that resolve the
biggest gated cluster. A single v3.7 regen of the complete run makes ~13 of the 15
reds trace-diagnosable in one batch.

This runbook is the turnkey procedure. It requires **BizHawk** (the recorder reads
ROM RAM during BK2 playback) — it cannot be done in a no-hardware session.

## What v3.7 adds (vs the installed traces)

Recorder: `tools/tracechaser/bizhawk/s1_complete_run_recorder.lua` (`metadata.lua_script_version == "3.7"`).
CSV schema is UNCHANGED (player `x_sub`/`y_sub` have been columns 12-13 since v2.0).
New, comparison-only aux events (gated by `aux_schema_extras`):

- **`v_objstate`** — the full 192-byte object respawn-state bit array
  (`$FFFC00..$FFFCC0`) as a compact hex string, every frame.
  `v_objstate[0]`/`[1]` = OPL forward/backward counters; `[2..]` = per-spawn
  remember bits. Resolves the slot-interleave / slot-cadence cluster.
- **`camera_boundary`** — `v_limitbtm1` / `v_limitbtm2` / `v_lookshift` /
  `f_bgscrollvert`, every frame. Resolves the MZ1 camera-boundary frontier.
- Regenerating also installs the CURRENT `object_near` + `slot_dump` aux on the
  zones whose installed traces are stale lua-3.2 (most of them).

All three are **diagnostic context only** — the comparator/report reads them; they
are NEVER written back into engine state (the comparison-only invariant). Honoring
recorded ROM counters/markers for correct movie playback is fine; copying recorded
`x_pos`/object positions into engine state is forbidden.

## Procedure

1. **Inputs** (in the working dir, gitignored):
   - ROM: `s1.gen`
   - BK2: the shared `s1-complete-run.bk2` (the multi-segment recorder plays this
     and auto-detects zone/act, emitting one per-act output subdir).
2. **Run the recorder** (headless EmuHawk, mirrors the s3k complete-run pattern):
   ```
   tools/tracechaser/.dependencies/BizHawk-2.11-win-x64/EmuHawk.exe --chromeless \
     --lua=tools/tracechaser/bizhawk/s1_complete_run_recorder.lua \
     --movie=s1-complete-run.bk2 \
     "s1.gen"
   ```
   (Override the emulator path with `BIZHAWK_EXE` if needed. The v3.6+ recorder
   self-exits at movie end and pre-creates all per-act output subdirs in one
   shell-out — no per-segment cmd-window flashes.)
3. **Output** lands in `tools/tracechaser/bizhawk/trace_output/<zone>_<act>/` (metadata.json +
   physics.csv + aux_state.jsonl per act). Verify each `metadata.json` shows
   `"lua_script_version": "3.7"` and `aux_schema_extras` contains
   `"v_objstate_per_frame"` + `"camera_boundary_per_frame"`.
4. **Install** each act's files into `src/test/resources/traces/s1/<zone>/`
   (overwriting the stale ones). Run `tools/tracechaser/traces/compress-traces.ps1` if the
   repo stores compressed traces.
5. **Re-run the comparator** per zone and read the context window — the new aux now
   renders in `target/trace-reports/s1_<zone>_context.txt`:
   ```
   mvn -q -Dmse=relaxed "-Dtest=TestS1<Zone>CompleteRunTraceReplay" \
     "-Dsurefire.failIfNoSpecifiedTests=false" test
   ```

## Per-frontier: what the regen resolves (develop tip `e85266286`)

| Zone / frontier | Field | What v3.7 data resolves | Frame window |
|---|---|---|---|
| **LZ2 f1068** | obj_s | `v_objstate` — at the backward-OPL reload, is ROM's respawn bit clear (respawn) vs engine's set (skip)? The canonical slot-interleave. | f0 / f192 / f217 |
| **MZ3 f9917** | y_speed | `v_objstate` + `slot_dump` — pin the FIRST slot divergence upstream of the Batbrain slot-81-vs-82 cascade. | f90 onward; div f9917 |
| **SYZ1 f4430** | y_speed | `v_objstate` + `object_near` (trace was lua-3.2, no aux) — ROM slot-0x54 ridden-solid occupancy. | f4400-4430 |
| **SBZ1 f6082** | x_speed | `object_near` — Obj37 lost-ring slot-42 x/y/vel trajectory f581-754 (why ROM's settles+collects, engine's never settles). | f581-754 |
| **MZ1 f2101** | camera_y | `camera_boundary` — ROM `v_limitbtm2` vs engine (~6px high); resolves the +6-vs-+2/clamp ambiguity. | f2085-2110 |
| **LZ1 f5745** | y | `object_near` — Obj63 conveyor obX/Y/sub drift (the 1-2px non-uniform off-screen path drift). | f5671-5745 |
| **LZ3 f8499** | g_speed | `object_near` — Obj64 air-bubble spawn-Y + maker RNG cadence at the grab frame. | f8490-8501 |
| **GHZ3 f8021** | y_speed | `object_near` — GHZ boss Obj3D obBossX/ob2ndRout/timer (boss 1px behind at touch). | f7980-8022 |
| **MZ2 f2823** | x_speed | `object_near` — Lava Geyser head Obj4D obY/routine + maker gmake_timer (coupled cadence). | f2790-2825 |
| **SLZ1 f2872** | y | `object_near` — Staircase Obj5B objoff_34/38/36 (seat counter reaches ctr=1 one frame early). | f2835-2872 |
| **SLZ2 f3353** | y | `object_near` / oscillator byte — disambiguate osc-phase vs ride-exit seat. | div f3353 |
| **SLZ3 f3249** | x_speed | `object_near` — which specific Walking Bomb shrapnel survives + its Y. | div f3249 |
| **FZ f1724** | y | `object_near` — coupled cylinder Obj84 + boss Obj85 timing (2f/cycle short). | f760-1730 |
| **SYZ3 f6358** | x | terrain wall-stop while riding; 1px. May still need raw v_player RAM beyond aux — lowest-yield of the batch. | f6340-6360 |
| **SBZ2 f2323** | x | 1-frame movement-phase dropout; needs real-harness per-frame engine x.x_sub (not aux-resolvable) — lowest-yield. | f2318-2324 |

**Headline:** items 1-13 above become trace-diagnosable from this single v3.7 regen
(`v_objstate`/`camera_boundary` + the refreshed `object_near`/`slot_dump`). SYZ3
f6358 and SBZ2 f2323 are the two that may remain harness/raw-RAM-gated.

## After the regen

Per the trace-replay loop: read the new first-error context (now with the aux
fields visible), find the engine code path that should produce the ROM-correct
value, fix it ROM-faithfully (disasm-cited, no zone/frame carve-outs, per-game
divergences routed through the smallest accurate owner from
`docs/architecture/per-game-rule-placement.md`), keep all greens green, and update
`docs/status/trace-frontier-log.md`. The agents resume against this ground truth the
instant the regenerated traces are installed.
