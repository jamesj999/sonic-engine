# Pitfall Catalogue Index (by Bug Class)

This is an **index** of the existing per-game ROM-behaviour pitfall catalogues. It does **not** define new pitfalls. It groups the entries that already live in the source files below by bug class, so an agent can jump straight to the relevant entry when implementing an object, badnik, boss, or trace fix.

## Source Pitfall Files

| Game | Source file (mirrored skill paths) | Entries |
|------|------------------------------------|---------|
| S2 | `.agents/skills/s2-implement-object/rom-pitfalls.md` and `.claude/skills/s2-implement-object/rom-pitfalls.md` | P1–P41 (origin narrative for the whole catalogue) |
| S3K | `.agents/skills/s3k-implement-object/rom-pitfalls.md` and `.claude/skills/s3k-implement-object/rom-pitfalls.md` | P1–P15, P17, P18 |
| S1 | (none yet — no `rom-pitfalls.md` under `s1-implement-object`) | — |

Notes for readers:

- **Cross-game default.** The S3K file states up front that the patterns "were first surfaced during S2 frontier advancement … but are cross-game: each applies to S3K objects unless the entry explicitly says otherwise." So a pattern shared between both files (same P-number) is cross-game; an entry that appears only in the S2 file is still usually cross-game in mechanism, with an S2 ROM citation. Per-entry cross-game notes below call out the exceptions (entries scoped to one game's streaming / event ordering).
- **P-numbers are shared.** The same P-number describes the same bug class in both files (e.g. `P14` is the touch-response polling pitfall in both). Where both files carry an entry, the index lists both paths.
- **Where to read the entry.** Each entry in the source file carries: symptom, root cause, what-to-check, ROM citation, and originating fix commit. Open the source file and search for the `## P<n>` heading.
- **Skill mirror rule.** If you edit any `rom-pitfalls.md`, mirror the identical change in both `.agents/skills/...` and `.claude/skills/...`. This index is a standalone doc under `docs/agent-workflow/` and is the preferred place to grow the cross-cutting index without mutating skill files.
- The S3K file points readers at the trace-replay update loop (`.agents/skills/trace-replay-bug-fixing/SKILL.md`, Phase 5) as the source of new catalogue entries.

---

## Index by Bug Class

Legend for the **Cross-game** column:
- **Yes (shared)** — entry exists in both the S2 and S3K files under the same P-number.
- **Yes (mechanism)** — entry currently lives only in the S2 file but the bug class is game-agnostic; the ROM citation is S2-side. Re-verify against the target game's disassembly.
- **Scoped** — the entry itself scopes a fix to one game's streaming / event-ordering path; read the entry's "what to check" note before reusing.

### Moving solid timing

| Pitfall | Title | In files | Cross-game | Originating test/commit (as cited in the entry) |
|---------|-------|----------|------------|--------------------------------------------------|
| P5 | SolidObject returns non-solid prematurely on state transition | S2, S3K | Yes (shared) | see entry |
| P15 | Object `update()` resolves solid contacts BEFORE refreshing slope / collision state | S2, S3K | Yes (shared) | see entry |
| P32 | Solid checkpoint must run before state-machine position update, not after | S2 | Yes (mechanism) | see entry |
| P38 | `SolidObject` contact mutates velocity before hurt helpers read it | S2 | Yes (mechanism) | see entry |
| P29 | Moving objects may own bespoke `out_of_range` delete bounds | S2 | Yes (mechanism) | see entry |

### Touch response polling

| Pitfall | Title | In files | Cross-game | Originating test/commit |
|---------|-------|----------|------------|-------------------------|
| P14 | Engine edge-triggers ENEMY touch response but ROM polls every frame | S2, S3K | Yes (shared) | see entry |
| P1 | Touch-response directional/state guards diverge from ROM | S2, S3K | Yes (shared) | `c2d998751 fix(s2): CPZ Grabber badnik rolling-kill independent of vertical position` |

### Enemy vs special contact edge behavior

| Pitfall | Title | In files | Cross-game | Originating test/commit |
|---------|-------|----------|------------|-------------------------|
| P14 | ENEMY touch polls every frame; SPECIAL/monitor contacts stay edge-triggered | S2, S3K | Yes (shared) | see entry |
| P28 | SPECIAL touch objects use `Touch_Sizes` radii and object-specific bounce tails | S2 | Yes (mechanism) | see entry |
| P16 | Monitor (and ROM `SolidObject_AtEdge`) push bit set on any grounded side contact, not just movingInto | S2 | Yes (mechanism) | see entry |

### Child object spawning

| Pitfall | Title | In files | Cross-game | Originating test/commit |
|---------|-------|----------|------------|-------------------------|
| P37 | Parent-spawner factory returning `null` re-spawns children every frame | S2 | Yes (mechanism) | see entry |
| P41 | Constructor-modeled child init must not also run main routine on the spawn frame | S2 | Yes (mechanism) | see entry |

### Offscreen lifecycle and remembered spawn state

| Pitfall | Title | In files | Cross-game | Originating test/commit |
|---------|-------|----------|------------|-------------------------|
| P17 | Child object `out_of_range` uses own X instead of parent anchor, causing chunk-boundary unload | S2, S3K | Yes (shared) | see entry |
| P27 | `SolidObject_Always` objects must bypass offscreen full-solid gates | S2 | Yes (mechanism) | see entry |
| P21 | Sonic 2 object streaming is X-window only (no `Camera_Y_pos` spawn eligibility) | S2 | Scoped (S2 placement path) | `<pending>` (trace frontier loop iter 13: CNZ f3830→f3906) |

### Player and sidekick participation

| Pitfall | Title | In files | Cross-game | Originating test/commit |
|---------|-------|----------|------------|-------------------------|
| P3 | Global object state vs ROM per-player object state bytes | S2, S3K | Yes (shared) | `3cb72b6af fix(s2): CNZ Flipper per-player launch cooldown + ROM-accurate y_pos` |
| P10 | Solid object contacts must skip dead / despawning players | S2, S3K | Yes (shared) | see entry |
| P11 | Solid object break/trigger condition leaks main-player state into sidekick contact | S2, S3K | Yes (shared) | see entry |
| P12 | Angle-based player detection ported as simplified bounding-box + facing guard | S2, S3K | Yes (shared) | see entry |
| P35 | Sidekick pass left as "Player 2 deferred" stub | S2 | Yes (mechanism) | see entry |

### Object control bits

| Pitfall | Title | In files | Cross-game | Originating test/commit |
|---------|-------|----------|------------|-------------------------|
| P34 | `Ctrl_1` byte-read is just-pressed edge, not held state | S2 | Yes (mechanism) | see entry |
| P25 | Obj85 preserved roll must suppress stale held jump, not fresh delayed press | S2 | Yes (mechanism) | see entry |
| P26 | Riding solids can own stale logical horizontal input windows | S2 | Yes (mechanism) | see entry |
| P36 | `move.b #2,routine(a1)` clears the Hurt routine; engine must call `setHurt(false)` | S2 | Yes (mechanism) | see entry |
| P39 | Same object ID can dispatch to different objects by subtype/routine | S2 | Yes (mechanism) | Fix S2 MTZ3 Obj06 cylinder mode (MTZ3 frontier f4280→f4656) |

### Center-coordinate vs top-left-coordinate usage

| Pitfall | Title | In files | Cross-game | Originating test/commit |
|---------|-------|----------|------------|-------------------------|
| P7 | Centre Y vs top-left Y for kill / boundary checks | S2, S3K | Yes (shared) | see entry |
| P4 | Character-dependent coordinate adjustments where ROM uses a fixed offset | S2, S3K | Yes (shared) | `3cb72b6af` (secondary) |
| P13 | `SlopedSolidProvider.getSlopeBaseline()` returns halfHeight when ROM slope table encodes absolute offsets | S2, S3K | Yes (shared) | see entry |

### Native playable-sprite position writes

| Pitfall | Title | In files | Cross-game | Originating test/commit |
|---------|-------|----------|------------|-------------------------|
| P40 | Native `x_pos` / `y_pos` writes must preserve the sibling subpixel byte | S2 | Yes (mechanism) | see entry |
| P9 | Integer math drops `y_sub` carry in 16:16 position updates | S2, S3K | Yes (shared) | see entry |
| P4 | Prefer literal `NativePositionOps.addYPosPreserveSubpixel(...)` over a character-aware helper for fixed ROM offsets | S2, S3K | Yes (shared) | `3cb72b6af` (secondary) |

### PLC, art, mappings, DPLC, and virtual pattern IDs

No dedicated PLC/art/mapping/DPLC entry currently exists in either `rom-pitfalls.md`. For art/mapping/PLC/DPLC and virtual pattern ID hazards, use the dedicated skills instead:
- `.agents/skills/plc-system/SKILL.md` (cross-game PLC) and `.agents/skills/s3k-plc-system/SKILL.md` (S3K PLC).
- `.agents/skills/s3k-implement-object/SKILL.md` (S3K art / mapping / DPLC parsing via `Sonic3kObjectArt.buildLevelArtSheetFromRom`).
- Guard tests `TestSonic3kPlcArtRegistry` and `TestPatternSpriteRendererCorruptionGuard` catch art/mapping/PLC and pathological frame geometry.
- For dynamic-art / DPLC divergences in a multi-segment run trace, the recorder-defined `run_gap` field contracts and the S1 `segment_start - 26` load-pair invariant are in `plc-system`; the comparison-side traps (`art=serial` cannot see gap edges, the gap ledger is compared at destination admission) are in `.agents/skills/trace-replay-bug-fixing/SKILL.md`.

(Listed here so the bug class is not silently dropped; add a pitfall entry to the source files — mirrored — if/when a reusable PLC/art pitfall is surfaced by a trace fix.)

### S3K zone-set resolution

No dedicated zone-set entry currently exists in `rom-pitfalls.md`. Zone-set resolution (S3KL zones 0–6 vs SKL zones 7–13, dual object pointer tables, same-ID-different-name objects) is covered by:
- `.agents/skills/s3k-disasm-guide/SKILL.md` and `.agents/skills/s3k-implement-object/SKILL.md`.
- `Sonic3kObjectRegistry.getPrimaryName(id, zoneSet)` / `S3kZoneSet`.

(Add a mirrored pitfall entry if a reusable zone-set bug class is surfaced.)

### S3K S&K-side vs Sonic 3 standalone address confusion

No dedicated entry currently exists in `rom-pitfalls.md`. The S&K-side address rule (prefer `sonic3k.asm`, `< 0x200000`, via `RomOffsetFinder --game s3k`; an `s3.asm` reference is a rare, verified fallback only when an object has no S&K equivalent) is enforced by guidance in:
- `.agents/skills/s3k-disasm-guide/SKILL.md` and the S&K-side note in `CLAUDE.md` / `AGENTS_S3K.md`.

(Add a mirrored pitfall entry if a reusable wrong-half-address bug class is surfaced by a trace fix.)

### Dynamic resize, AniPLC, and palette interactions

No dedicated entry currently exists in `rom-pitfalls.md` (these are zone-feature, not per-object, concerns). They are covered by the S3K zone skills:
- `.agents/skills/s3k-zone-events/SKILL.md` (Dynamic_Resize / palette mutation).
- `.agents/skills/s3k-animated-tiles/SKILL.md` (AniPLC).
- `.agents/skills/s3k-palette-cycling/SKILL.md` (AnPal cycling, kept separate from one-shot mutations).
- One entry touches per-game post-event flow: **P8 — Per-game post-event flow divergence** (S3K immediate vs S2 deferred), present in both files; this is the explicitly per-game entry.

(Add a mirrored pitfall entry to the per-object files only if a reusable per-object resize/AniPLC/palette bug class is surfaced.)

---

## Other catalogued entries (not in the brief's class list, recorded for completeness)

These existing S2 entries do not map cleanly onto the bug classes above but are part of the catalogue:

| Pitfall | Title | In files | Cross-game |
|---------|-------|----------|------------|
| P2 | ROM multi-frame init collapsed into one engine frame | S2, S3K | Yes (shared) |
| P6 | Gravity-before-move vs ROM's move-before-gravity ordering | S2, S3K | Yes (shared) |
| P8 | Per-game post-event flow divergence (S3K immediate vs S2 deferred) | S2, S3K | Yes (shared, explicitly per-game contrast) |
| P18 (S3K) / P19 (S2) | Shared monitor icon rewards use pre-move velocity tests | S2 (P19), S3K (P18) | Yes (mechanism) |
| P18 (S2) | Object bounce routines preserve unwritten velocity / inertia fields | S2 | Yes (mechanism) |
| P20 | Level-event globals may need pre-object update order | S2 | Scoped (S2 CNZ level-event path); `<pending>` (trace frontier loop iter 12: CNZ f1691→f3830) |
| P22 | Object-local capture may need previous-frame status | S2 | Yes (mechanism) |
| P23 | Full-solid bottom overlap may use live rolling `y_radius` | S2 | Yes (mechanism) |
| P24 | Landing radius restore is not always shared across games | S2 | Yes (mechanism) |
| P30 | `bmi` countdown timers fire at -1, not 0 | S2 | Yes (mechanism) |
| P31 | Property table byte-offset mistakenly divided as entry index | S2 | Yes (mechanism) |
| P33 | Per-game rule/profile/provider values must be set to the correct ROM value when gates are added | S2 | Yes (mechanism) |

Note on numbering: the monitor-icon pre-move-velocity pitfall is `P19` in the S2 file but `P18` in the S3K file (the S3K file omits the S2-only bounce-velocity entry, shifting later numbers). Always confirm the heading text, not just the number, when cross-referencing between files.

---

## How to use this index

1. Identify the bug class for the object/behaviour you are porting or the trace divergence you are triaging.
2. Open the relevant `rom-pitfalls.md` (S3K for S3K work, S2 for S1/S2 work and for the origin narrative) and jump to the `## P<n>` heading(s) listed above.
3. Read the entry's symptom / root cause / what-to-check / ROM citation / originating commit.
4. If your fix surfaces a **new** reusable bug class, add the entry to the source `rom-pitfalls.md` files (mirrored in `.agents/` and `.claude/`) per the trace-replay update loop, then add a row here. Do not invent pitfalls in this index file.
