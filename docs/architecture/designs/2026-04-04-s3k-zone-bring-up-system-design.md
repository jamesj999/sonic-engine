# S3K Zone Bring-Up System

Systematic process for analysing and implementing per-zone visual and behavioural features across all Sonic 3 & Knuckles zones using specialized skills and agentic sub-agents.

## Problem

S3K has 13 main zones, each with a unique cocktail of features: parallax scrolling, animated tiles, palette cycling, camera lock events, boss arenas, cutscenes, water systems, act transitions, and zone-specific objects. AIZ is fully implemented. The remaining 12 zones need systematic bring-up with ROM accuracy, but each zone has idiosyncratic behaviour that defies a one-size-fits-all template (e.g., MGZ2 boss destroys the tilemap; SOZ has time-of-day transitions; LBZ has rising water during gameplay).

Manual spec-writing per zone doesn't scale. The agent must autonomously read the disassembly and catalogue features before implementing them.

## Design

### Skill Inventory

7 skills total, organized in three layers:

#### Analysis Layer

| Skill | Purpose | Input | Output |
|-------|---------|-------|--------|
| `s3k-zone-analysis` | Read disassembly, catalogue all zone features | Zone abbreviation | `docs/architecture/research/s3k-zones/<zone>-analysis.md` |

#### Feature Implementation Layer

| Skill | Purpose | Input | Creates/Modifies |
|-------|---------|-------|------------------|
| `s3k-zone-events` | Camera locks, boss arenas, cutscenes, act transitions, palette mutations | Zone + analysis spec | `Sonic3k<Zone>Events.java` |
| `s3k-parallax` *(exists)* | Per-line scroll handlers, water splits, deform routines | Zone + analysis spec | `SwScrl<Zone>.java` |
| `s3k-animated-tiles` | AniPLC script triggers, gating conditions, dynamic art | Zone + analysis spec | Cases in `Sonic3kPatternAnimator.java` |
| `s3k-palette-cycling` | AnPal handlers, counter/step/limit cycling, validation | Zone + analysis spec | Cases in `Sonic3kPaletteCycler.java` |

#### Orchestration & Validation Layer

| Skill | Purpose | Input | Output |
|-------|---------|-------|--------|
| `s3k-zone-bring-up` | Dispatches analysis + feature agents, merges results | Zone abbreviation | Integrated zone implementation |
| `s3k-zone-validate` | Visual comparison via stable-retro + image recognition | Zone abbreviation | Validation report |

### Dual-Format Skills

Each skill exists in two locations with the same content body:
- `.claude/skills/<name>/skill.md` — Claude Code format (frontmatter: name, description, trigger)
- `.agent/skills/<name>/skill.md` — Agent-agnostic format (frontmatter: name, description; no Claude-specific triggers)

The `.agent/skills` versions use the same markdown body but with a header adapted for generic agent consumption (no `$ARGUMENTS` convention, explicit parameter documentation instead).

### Zone Analysis Skill (`s3k-zone-analysis`)

The most critical skill. It must autonomously read 68000 assembly and produce accurate feature catalogues.

#### Process

1. **Locate zone routines** using `s3k-disasm-guide`:
   - `Dynamic_Resize_<zone>` — event handlers (camera locks, boss triggers, act transitions)
   - `SwScrl_<zone>` / `<zone>_Deform` — parallax/scroll routines
   - `AniPLC_<zone>` — animated tile script entries
   - `AnPal_<zone>` — palette cycling routines
   - Object layout data + zone-set object table entries

2. **Read each routine** and extract:
   - What it does (camera boundary changes, palette writes, object spawns, art loads)
   - Trigger conditions (camera X/Y thresholds, routine counters, boss flags, character checks)
   - Dependencies between features (event handler loads art that animated tiles need, parallax references water level)

3. **Flag confidence levels**:
   - **HIGH** — clear routine with standard patterns ("6 parallax bands with scatter-fill")
   - **MEDIUM** — understood but complex ("boss sequence spawns 3 child objects with phased transitions")
   - **LOW** — ambiguous or unusual ("this `bsr` might be loading dynamic art mid-boss")

4. **Flag cross-cutting concerns**:
   - Water system integration
   - Screen shake sources
   - Act transition mechanics (palette swaps, tilemap reloads, camera warps)
   - Character-specific branching (Sonic/Tails vs Knuckles paths)
   - Anything that doesn't fit standard templates

#### Output Format

```markdown
# <Zone Full Name> (<ABBR>) — Zone Analysis

## Summary
One paragraph overview of the zone's character and key features.

## Events (Dynamic_Resize)
### Act 1
- Routine 0: [description] (confidence: HIGH/MEDIUM/LOW)
- Routine 2: [description]
- Character branching: [yes/no, details]
### Act 2
- [same structure]
### Boss Sequences
- Mini-boss: [description, object IDs]
- End boss: [description, object IDs]

## Parallax (Deform)
- Band count: N
- Deform type: [standard/scatter-fill/per-line]
- Water split: [yes/no]
- Act differences: [description]
- Data tables: [labels and locations]

## Animated Tiles (AniPLC)
- Script count: N
- Scripts: [index, VRAM dest, frame count, description]
- Gating conditions: [boss flag, camera position, intro phase]
- Dynamic art overrides: [description if any]

## Palette Cycling (AnPal)
- Channel count: N
- Channels: [palette line, color range, rate, description]
- Already implemented: [yes/no, validation status]

## Objects (Notable)
- [Object ID, name, brief description, zone-set]
- (Not exhaustive — highlights zone-specific objects and bosses only)

## Cross-Cutting Concerns
- [Water system, screen shake, act transitions, unusual mechanics]

## Implementation Notes
- [Anything the analysis agent thinks is important for implementers]
```

### Feature Skill Common Pattern

All 4 feature implementation skills share this structure:

1. **Input:** Zone abbreviation + path to zone analysis spec
2. **Read the spec** — extract only the relevant section
3. **Read the disassembly** — follow references from the spec to actual assembly (the spec points, the assembly is truth)
4. **Check existing infrastructure** — base classes, utilities, registration points
5. **Implement** — write Java class(es)
6. **Register** — add to provider/switch, add ROM constants to `Sonic3kConstants`
7. **Build** — `mvn package` to verify compilation
8. **Cross-reference comment** — every implementation includes the disassembly routine label as a comment at class/method level

#### `s3k-zone-events`

Creates `Sonic3k<Zone>Events extends Sonic3kZoneEvents` in `game/sonic3k/events/`.

Ports `Dynamic_Resize_<zone>` routine counter logic:
- Dual routine support (eventRoutineFg + eventRoutineBg) where applicable
- Character branching via `PlayerCharacter` enum
- Boss spawn coordination (object spawning, camera lock, music change)
- Palette mutations (camera-threshold writes that live in event routines, NOT in AnPal)
- Act transition mechanics (palette swaps, tilemap reloads, art loads via PLC)

Registers in `Sonic3kLevelEventManager` zone dispatch.

#### `s3k-animated-tiles`

Adds zone case to `Sonic3kPatternAnimator.updatePatterns()`.

- Identifies AniPLC script indices from disassembly
- Implements gating conditions (Dynamic_Resize_routine value, boss_flag, camera position)
- Handles dynamic art overrides (zone-specific art loaded outside the AniPLC system)
- References `s3k-plc-system` skill for PLC-driven art interactions

#### `s3k-palette-cycling`

Creates or validates `AnPal_<Zone>` handler in `Sonic3kPaletteCycler`.

- Maps ROM Counter/Step/Limit triples to Java fields
- Verifies palette line targets and color index ranges against ROM data
- For already-implemented zones: validates existing code against disassembly, fixes discrepancies
- Uses `RomOffsetFinder` to verify palette data ROM addresses

#### `s3k-parallax` (existing)

Already well-documented. Updates needed:
- Accept zone analysis spec as input alongside zone abbreviation
- Reference spec's parallax section for band counts and data table locations before reading disassembly

### Orchestrator Skill (`s3k-zone-bring-up`)

#### Process

1. **Run `s3k-zone-analysis`** for the target zone
2. **Present the analysis spec** for human review (optional gate — can be skipped with a flag)
3. **Dispatch feature agents in parallel worktrees:**
   - Agent 1: `s3k-zone-events` (worktree)
   - Agent 2: `s3k-parallax` (worktree)
   - Agent 3: `s3k-animated-tiles` (worktree)
   - Agent 4: `s3k-palette-cycling` (worktree)
4. **Merge worktrees** — reconcile shared file changes (constants, providers, switch statements)
5. **Build verification** — `mvn package` on merged result
6. **Run `s3k-zone-validate`** for visual comparison

#### Shared File Conflict Resolution

Feature agents touch shared files:
- `Sonic3kConstants.java` — ROM addresses (additive, merge-safe)
- `Sonic3kScrollHandlerProvider.java` — switch case (additive)
- `Sonic3kLevelEventManager.java` — zone dispatch (additive)
- `Sonic3kPatternAnimator.java` — switch case (additive)
- `Sonic3kPaletteCycler.java` — switch case (additive)

Since all changes are additive (new switch cases, new constants), merge conflicts are mechanical — the orchestrator can resolve them by combining the additions.

### Validation Skill (`s3k-zone-validate`)

#### Visual Features (parallax, palette cycling, animated tiles)

1. **Capture reference** — run stable-retro for the target zone, capture screenshots at key moments:
   - Level start (initial parallax, palette state)
   - Mid-level (animated tiles active, cycling visible)
   - Act transition (if applicable)
   - Boss arena (camera lock, art changes)
2. **Capture engine output** — run the engine for the same zone, capture at equivalent positions
3. **Compare using image recognition** — agent analyses both images for feature presence:
   - "Are there visible parallax layers moving at different speeds?"
   - "Is palette cycling active (waterfall shimmer, lava glow)?"
   - "Are animated tiles updating (waterfalls, conveyor belts)?"
   - Not pixel-perfect diffing — feature presence and visual correctness

#### Behavioural Features (events, camera locks)

1. **Headless test** — where deterministic assertions are possible (camera boundaries at specific positions, boss spawn at correct coordinates)
2. **Manual spot-check** — for complex sequences that resist automation

#### Confidence Reporting

Validation output uses the same confidence scale:
- **PASS** — feature visually confirmed working
- **LIKELY** — feature appears present but couldn't verify all aspects
- **FAIL** — feature missing or visibly wrong
- **SKIP** — feature not applicable to this zone

### Zone Priority Order

Based on AGENTS_S3K.md recommendations and feature complexity:

| Priority | Zone | Why |
|----------|------|-----|
| 1 | HCZ | Water system integration, common zone, palette cycling exists to validate |
| 2 | LBZ | Complex events (rising water, dual acts), palette cycling exists |
| 3 | LRZ | Lava mechanics, palette cycling exists, visual payoff |
| 4 | CNZ | Barrel physics zone, lighting effects, palette cycling exists |
| 5 | ICZ | Snowboarding intro, palette cycling exists |
| 6 | FBZ | Flying Battery mechanics, palette cycling placeholder |
| 7 | MGZ | Parallax already done, needs events + animated tiles + boss |
| 8 | MHZ | Time-of-season (act color changes), no existing features |
| 9 | SOZ | Time-of-day system, ghosts, complex zone |
| 10 | SSZ | Short zone, unique sky mechanics |
| 11 | DEZ | Death Egg mechanics, complex bosses |
| 12 | DDZ | Doomsday — entirely unique (flight-only boss chase) |

Competition zones (ALZ, BPZ, DPZ, CGZ, EMZ) are lowest priority and can follow the same process later.

### File Structure

```
.claude/skills/
  s3k-zone-analysis/skill.md
  s3k-zone-events/skill.md
  s3k-animated-tiles/skill.md
  s3k-palette-cycling/skill.md
  s3k-zone-bring-up/skill.md
  s3k-zone-validate/skill.md
  s3k-parallax/skill.md          (existing, updated)

.agent/skills/
  s3k-zone-analysis/skill.md
  s3k-zone-events/skill.md
  s3k-animated-tiles/skill.md
  s3k-palette-cycling/skill.md
  s3k-zone-bring-up/skill.md
  s3k-zone-validate/skill.md
  s3k-parallax/skill.md          (mirror of .claude version)

docs/architecture/research/s3k-zones/                  (analysis specs, created per zone)
  hcz-analysis.md
  lbz-analysis.md
  ...
```
