# Which recorded trace fields are compared, and which are not

**Date:** 2026-08-21 · **Measured at:** `49c6e1438` · **Scope:** the 399 `aux_state.jsonl.gz`
fixtures under `src/test/resources/traces/`, plus the comparison paths in `TraceBinder`,
`AbstractTraceReplayTest` and `LiveTraceComparator`.

Point-in-time inventory, prompted by the CNZ slot-machine round: the recorder had been
capturing that subsystem's ROM state on every row of both fixtures since the fixtures were
made, nothing read it, and the resulting blind spot cost three rounds and two rejected
candidates to work around. The obvious question is how many others there are.

**Answer: 31 recorded event kinds, 9 compared, 22 not.** Eight of those 22 are unreferenced
anywhere outside the parser.

## Method, and what the table does not claim

Event kinds were enumerated by scanning every aux fixture. "Compared" means an exact
`TraceEvent.<Record>` reference appears in one of the three comparison files above. "Used"
means an exact reference appears anywhere else in `src/`. Both are token matches on the
record type, not a substring search — an earlier fuzzy pass produced several false positives
and is not the basis for anything here.

The table says nothing about whether an uncompared stream *should* be compared. Some are
plainly diagnostic (`object_appeared` drives the occupancy oracle; `lag_state` classifies
rows). The point is that nobody has asked the question per stream, and at least one answer
was worth three rounds.

## Compared today (9)

| event | games | sampled volume |
|---|---|---|
| `object_near` | s1, s2, s3k | 65,467 |
| `load_queue_state` | s1, s2, s3k | 19,813 |
| `dynamic_art_transfer_state` | s1, s2 | 18,310 |
| `cpu_state` | s2, s3k | 8,673 |
| `cnz_slot_machine_state` | s2 | 518 |
| `object_state_snapshot` | s2, s3k | 142 |
| `cpu_state_snapshot` | s2, s3k | 40 |
| `checkpoint` | s2, s3k | 21 |
| `player_history_snapshot` | s2 | 19 |

`cnz_slot_machine_state` joined this list on 2026-08-21 and is the reason for the audit.

## Recorded, parsed, never compared (22)

**Unreferenced anywhere outside the parser (8)** — captured, stored, and read by nothing:

| event | games | sampled volume | note |
|---|---|---|---|
| `object_state` | s3k | 12,216 | **the largest single item.** `TraceEvent`'s own comment calls it the event that dominates long complete-run aux files "by the hundreds of thousands", and describes the compact array-backed map built to store it economically. Nothing then reads it. |
| `interact_state` | s3k | 5,402 | |
| `game_paused_state` | s3k | 2,544 | |
| `slot_dump` | s1, s2, s3k | 616 | object-slot occupancy, in all three games |
| `s2_tornado_state` | s2 | 567 | subsystem ROM state, same shape as the CNZ slot block that turned out to be an oracle |
| `cursor_state` | s1, s2 | 317 | |
| `control_lock_state` | s3k | 215 | |
| `player_mode_set` | s3k | 21 | |

**Referenced for reporting, formatting or probes, but never compared (14):**
`air_countdown_state`, `camera_boundary`, `lag_state`, `mode_change`, `object_appeared`,
`object_removed`, `oscillation_state`, `routine_change`, `s1_obj64_state`,
`sidekick_interact_object`, `state_snapshot`, `v_objstate`, `v_oscillate`, `zone_act_state`.

These reach `DivergenceReport`, `TraceEventFormatter`, `ObjectOccupancyOracle` or a
`Debug*Probe` — visible to a human reading a report, invisible to the suite.

## What makes a stream an oracle rather than telemetry

The CNZ block qualified on a specific property, and it is the test to apply to the rest: **the
ROM derives the recorded values from state the engine also has, by arithmetic that can be
inverted.** `SlotMachine_Routine3` computes reel speeds from `V_int_run_count` by masking and
adding, so a recorded speed byte pins the exact counter the ROM used — comparison at an event
identified by its content, with no dependence on row alignment or on any model of frame
ordering. That is why it could settle a question two rounds of frame-ordering argument could
not.

By that test the most promising unexamined candidates are `s2_tornado_state` (subsystem ROM
state, directly analogous) and `object_state` (per-object SST fields at enormous volume, in
the game with the most unfinished object work). `slot_dump` is occupancy, which the existing
oracle already covers by another route.

## Suggested order, if anyone acts on this

1. `s2_tornado_state` — smallest, closest analogue to the case that motivated the audit.
2. `object_state` — largest payoff and largest cost; S3K object parity is the active frontier
   and this is per-object ROM state nothing checks.
3. The reporting-only 14 — cheap to re-classify, since they are already parsed and surfaced.
The per-stream verdicts below apply that test to all 22.

## Per-stream verdict for the 22 uncompared streams

**Basis and its limits.** Each verdict below comes from the stream's recorded field shape —
one sampled row per stream across the fixture set — read against the ROM meaning of those
fields. It is a first-pass triage meant to decide what to look at, not a substitute for
looking. Cost is rated on the two things that actually make this work expensive: whether the
engine already exposes the state, and whether the recorded row can be matched to an engine
object without an identity mapping. Volume matters far less than either.

### Oracles — ROM state the engine also computes, comparable byte for byte

| stream | game | why it qualifies | cost |
|---|---|---|---|
| `s2_tornado_state` | s2 | one object's full SST: `x`, `y`, `y_sub`, `y_vel`, `routine`, `routine_secondary`, `status_byte`, `objoff_2E..31`. Single known slot, so no identity mapping. | **low** — the closest analogue to the CNZ block and the obvious first one |
| `v_oscillate` | s1 | the ROM's `Oscillating_Data` table as raw bytes; the engine computes the same table | **low** — one array compare, no identity mapping |
| `oscillation_state` | s3k | same quantity for S3K, plus `level_frame_counter` | **low** |
| `camera_boundary` | s1 | `limitbtm1/2`, `lookshift`, `bgscrollvert` — camera boundary RAM the engine models directly | **low** |
| `control_lock_state` | s3k | `ctrl1/2_locked` and `ctrl1/2_logical`, i.e. `Ctrl_1_Logical` and its lock | **low** |
| `v_objstate` | s1 | the object respawn/state table as raw bytes | **low–medium** — engine equivalent needs locating |
| `s1_obj64_state` | s1 | LZ bubble-maker SST including the `objoff_32..38` fields that gate production timing | **medium** — per-slot, needs the object identified |
| `air_countdown_state` | s3k | the drowning countdown object's SST plus its owner | **medium** — per-slot |
| `interact_state` | s3k | `interact`, `interact_slot`, `status`, `status_secondary`, `object_control` for the player | **medium** — `interact_slot` needs the slot mapping |
| `sidekick_interact_object` | s3k | the same for the sidekick, plus render flags, invulnerability timer and dimensions | **medium** |
| `game_paused_state` | s3k | `game_paused`, one ROM flag | **low**, but thin — an oracle for pause parity only |
| `object_state` | s3k | per-object SST — `object_code`, `routine`, `status`, `subtype`, `x`, `y`, radii | **blocked, not merely expensive — see the scoping section below.** This row's original "high cost" rating was wrong. |

### Already covered by another route

| stream | why not |
|---|---|
| `slot_dump` | slot-to-type occupancy — `ObjectOccupancyOracle` already covers this content |
| `object_appeared`, `object_removed` | occupancy edges, consumed by the same oracle |
| `mode_change` | edge events on fields (`air`, …) that the physics comparison already checks per frame |
| `state_snapshot` | player `routine`, `status_byte`, radii, `anim_id` — largely duplicates `physics.csv` columns already compared |

### Telemetry — describes the recording, not the ROM

| stream | why |
|---|---|
| `lag_state` | `lagged` / `lagcount` are emulator-side facts about the capture; already consumed to classify rows |
| `cursor_state` | carries raw ROM addresses (`fwd_ptr`, `bwd_ptr`) the engine does not model as pointers; not comparable byte for byte |
| `player_mode_set` | a setup value, not per-frame ROM state |
| `zone_act_state` | mixed: `actual_zone_id` vs `engine_zone_id` is explicitly a recorder-to-engine mapping, and the stream already drives replay logic rather than comparison |
| `routine_change` | a derived edge event; its underlying `routine` is compared per frame already |

**Summary: 12 oracles, 5 already covered, 5 telemetry.** Of the 12, four are low-cost with no
identity mapping (`s2_tornado_state`, `v_oscillate`, `oscillation_state`, `camera_boundary`)
and one — `object_state` — is where the real value and the real difficulty both sit.

## What the missing comparison cost, before it existed

Stated plainly, because it is the argument for doing any of the above: **the CNZ slot
comparison would have named the site on its first run, and its absence cost three rounds and
two rejected candidates.** A tick-ownership reorder was built and measured (19,603 and 29,397
errors), a tick-plus-seed pair was built and measured (39 classes newly red across three
games), and a placement move was built and measured (22,879 errors) — all to answer a question
about which frame the ROM's `SlotMachine` reads its counter on. The recording had the answer
on every row the whole time.

## Suggested order, if anyone acts on this

1. `s2_tornado_state` — the closest analogue to the case that motivated the audit, one known
   slot, no identity mapping. If the approach is going to be validated anywhere, here.
2. The three other no-mapping oracles — `v_oscillate`, `oscillation_state`, `camera_boundary`.
   Cheap, and they cover S1 and S3K rather than piling more coverage onto S2.
3. `object_state` — **do not commission yet.** Not a cost problem but a precondition problem;
   the scoping section below sets out why.

Wiring one in is not free: switching on the CNZ comparison immediately turned two green
release-scope classes red by exposing 767 pre-existing divergences. That is the expected
outcome of new coverage over untriaged state, and it is a reason to sequence the work, not to
skip it. See `docs/status/known-discrepancies.md` for how that one was landed and what its
promotion condition is.

**And before trusting any of it:** break each new comparison on purpose — corrupt an input,
confirm the field goes red — before believing a green. The CNZ comparison was first wired
into `LiveTraceComparator`, which the replay tests never execute, and reported a perfectly
clean green while never running.

## Scoping `object_state` — commissioned 2026-08-21, and the answer is "not yet"

Asked for before anyone acts on it: what it would compare, how many classes would go red on
the first run, and whether it can be introduced per-field or only wholesale.

**It is blocked, and my own "high cost, largest payoff" rating above was wrong.** The cost is
not high. The precondition is unmet, and the evidence was already in the repository.

### What the recorded field actually is

`object_state.object_code` is **not an object identity**. S3K keeps a 32-bit ROM code pointer
in the first SST long — `Process_Sprites` does `move.l (a0),d0 / movea.l d0,a1 / jsr (a1)`
(`sonic3k.asm:35985-35988`) — and objects overwrite their own dispatch pointer with internal
sub-routine addresses to advance state, at **1,758 `move.l #<label>,(a0)` sites**. The existing
audit
[*S3K trace object identity: the object pointer tables cannot supply it*](2026-08-15-s3k-object-code-pointer-identity.md)
inverted both ROM object pointer tables and found **only 7 of 189 distinct recorded pointers
are table entries at all — 4.26% of entries.** The S3K SST has no id field at any offset. The
recorded value is a live program counter, not a type at any width.

### Therefore

- **Per-field or wholesale?** Per-field in principle — the record decomposes into `routine`,
  `status`, `subtype`, `x`, `y`, radii, keyed by `slot`. But every one of those is *per slot*,
  so a comparison is only meaningful once engine and ROM agree on **which object occupies the
  slot**, and identity cannot supply that.
- **The occupant already disagrees, everywhere.** Engine-vs-ROM occupancy diverges on
  **2387 of 2387 sampled frames**, with 19,519 genuine presence/absence divergences.
  Comparing per-slot scalars across an object graph that disagrees on the occupant produces
  noise, not signal: a `routine` mismatch would mean "different object in this slot", which is
  already known and already documented.
- **Blast radius, if switched on anyway.** 210 of the 272 S3K fixtures carry the event,
  spanning essentially every S3K trace class and every S3K run chain — an order of magnitude
  more than the two classes each of the CNZ and tornado comparisons cost, and all of them
  reporting the same already-known fact.

### What to commission instead

The audit already names the sound comparison S3K can support today: **occupancy alone** —
presence/absence and slot index over `Dynamic_object_RAM` slots 4-90, no identity.

**Corrected 2026-08-21: "sound" means well-defined, not green, and I first read it as
"landable".** Occupancy passes the oracle test including attribution — the slot index *is* the
attribution — but measuring it shows the engine does not pass the comparison: 88 of 94 fixtures
diverge on presence alone, across all three games, not only S3K. It is the precondition for
`object_state` rather than a substitute for it, and it is itself blocked on fixing the
divergences before it can be wired in. See the 2026-08-21 occupancy entry in
`docs/status/trace-frontier-log.md`. Identity would require annotating every ported S3K object with its current ROM routine
address, which is a programme, not a round.

**Sequencing:** occupancy first; `object_state` only once slot occupancy matches, at which
point its per-slot fields become meaningful and can be introduced a field at a time.

### The general lesson for this inventory

The oracle test has a second clause I did not state when I wrote it. It is not enough that the
recorded values be ROM state the engine also holds; **the recorded row must be attributable to
an engine object.** The CNZ block passed because it is global state with no identity question,
and the tornado passed because exactly one instance exists so identity is by content.
`object_state` fails on attribution alone, with every individual field perfectly comparable.
Attribution is the clause worth checking first, because it can block a stream whose every byte
looks ideal.
