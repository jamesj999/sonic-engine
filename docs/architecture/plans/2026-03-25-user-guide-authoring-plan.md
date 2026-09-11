# User Guide Plan

This document is the plan for writing the OpenGGF user guide. It defines the audience,
structure, conventions, and content outline for each section. Once agreed, individual
pages will be written against this plan.

## Audiences

The guide serves three groups. They overlap (a researcher may become a contributor,
a player may become curious about accuracy) so the guide uses clear signposting rather
than total separation.

### 1. Players

People who want to run the engine and play the supported Sonic games. They may not have
a programming background. They need to get from "I downloaded this" to "I'm playing
Sonic 2" with minimal friction, and then understand the configuration options available
to them.

### 2. Cross-Referencers

People who know the Sonic disassemblies (s1disasm, s2disasm, skdisasm) and want to
understand how the engine represents the same concepts. They may be ROM hackers,
researchers, or accuracy testers. They likely have basic programming knowledge but
may not know Java or object-oriented conventions. The guide should explain concepts
without drowning them in Java-specific syntax. When code is shown, it should be
annotated in terms of what it *does*, not how the language works.

The disassemblies evolve over time (labels rename, files reorganise, addresses shift
between revisions). Rather than providing a static mapping table that will rot, the
guide teaches the *method* for performing mapping exercises so readers can repeat
them against any disassembly version.

### 3. Contributors

Developers who want to add objects, bosses, zones, audio fixes, or engine improvements.
They need to understand the architecture, the extension points, and the conventions.
This section assumes general programming competence and willingness to read Java, though
it should still explain engine-specific patterns rather than assuming familiarity.

## Conventions

### Tone

Knowledgeable but approachable. The existing README's voice is the baseline: precise about
technical detail, honest about limitations, with occasional personality. Avoid dry
reference-manual style for the narrative sections; save that density for tables and
reference cards.

### Code Examples

- ASM examples are shown as-is from the disassembly, with inline comments explaining
  what each instruction does for readers who are not 68000 experts.
- Java examples are kept minimal and annotated with what they *achieve*, not how the
  language works. Avoid showing boilerplate (imports, class declarations) unless it
  is the point of the section.
- When both ASM and engine code are shown side by side, use a clear visual structure
  (headings, callout boxes, or two-column layout if the format supports it).

### Images

Images are welcome where they clarify data flow or visual concepts. Candidates include:
- ROM data pipeline diagram (ROM bytes to decompressed tiles to GPU atlas to screen)
- Object lifecycle diagram (spawn record to registry lookup to instance to update loop)
- Annotated screenshot showing debug overlays (collision boxes, sensor rays, object labels)
- ArrowShooter sprite frames annotated with mapping piece structure

Images live in `docs/guide/images/` and are referenced with relative paths.

### Cross-References

Each page should be self-contained enough to read on its own, but link generously to
related pages. Use relative markdown links. When linking to source files, use paths
relative to the repository root (e.g., `src/main/java/com/openggf/game/sonic2/objects/`).

## Directory Structure

```
docs/guide/
  (this plan now lives at docs/architecture/plans/2026-03-25-user-guide-authoring-plan.md)
  index.md                              <-- landing page, audience signposting
  playing/
    getting-started.md                  <-- quick start: requirements to running
    configuration.md                    <-- player-facing config (FAQ style)
    controls.md                         <-- full controls reference
    game-status.md                      <-- per-game status and known gaps
    troubleshooting.md                  <-- common problems and fixes
  cross-referencing/
    how-the-engine-reads-roms.md        <-- conceptual: ROM to screen pipeline
    mapping-exercises.md                <-- teaching the method, not just the answers
    architecture-overview.md            <-- package map for non-Java readers
    tooling.md                          <-- RomOffsetFinder and related tools
    per-game-notes.md                   <-- S1/S2/S3K specific quirks
    68000-primer.md                     <-- one-page "just enough ASM" reference
  contributing/
    dev-setup.md                        <-- environment, build, run, test
    architecture.md                     <-- deep dive: providers, services, runtime
    tutorial-implement-object.md        <-- worked example: ArrowShooter from scratch
    adding-bosses.md                    <-- boss-specific patterns
    adding-zones.md                     <-- bringing up a new zone
    audio-system.md                     <-- SMPS, YM2612, PSG for contributors
    testing.md                          <-- HeadlessTestFixture, regression approach
```

## Content Plan by Page

### index.md

The landing page. Should contain:

- One paragraph explaining what OpenGGF is (not an emulator, reads ROM data, Java-based)
- A "choose your path" section pointing to the three guide parts
- Links to related top-level documents (README, CHANGELOG, ROADMAP, CREDITS)
- A note about the ROM requirement (no assets included, bring your own)

Length: short. Under 100 lines. Its job is to route people, not to teach.

---

### playing/getting-started.md

**Goal:** Zero to playing in under 5 minutes of reading.

Contents:

1. **What you need** — Java 21+, a GPU with OpenGL 4.1, ROM files for the games you
   want to play. Table of expected ROM filenames and revisions (lifted from README but
   presented as a checklist).

2. **Download or build** — Two paths:
   - Release ZIP: download, extract, place ROMs alongside JAR, run.
   - Build from source: clone, install hooks, run `mvn package`,
     use the JAR under the worktree's `target/`, then run.
   Mention `run.cmd` for Windows.

3. **First launch** — What to expect: master title screen, game selection, title screen,
   gameplay. One or two sentences per step. Mention that the engine boots to the master
   title screen by default and you pick a game from there.

4. **Choosing a game** — Brief note that `roms.default` in `config.yaml` controls which game
   boots, and `startup.masterTitleScreen` controls whether you see the picker.

5. **What if it doesn't work?** — Link to troubleshooting.md.

Length: ~80-120 lines.

### playing/configuration.md

**Goal:** Answer common "how do I...?" questions without requiring the reader to
understand the full configuration reference.

Format: FAQ-style, grouped by category. Each question links to the relevant key in the
full CONFIGURATION.md reference for readers who want the details.

Questions to cover:

- How do I change the window size?
- How do I go fullscreen? (if supported, or "not yet supported")
- How do I skip the title screen and go straight to gameplay?
- How do I start on a specific zone?
- How do I play as Tails / add a sidekick?
- How do I enable cross-game features (S2 sprites in S1)?
- How do I change controls?
- How do I mute audio?
- How do I change the game region (NTSC/PAL)?

Each answer: 2-5 lines plus the relevant JSON snippet.

Length: ~120-160 lines.

### playing/controls.md

**Goal:** Complete controls reference that a player can print or bookmark.

Contents:

- Gameplay controls table (arrows, space, enter/pause, Q/frame-step)
- Zone navigation shortcuts (Z/X for cycling)
- Debug overlay keys (F1-F12) with brief description of each overlay
- Debug mode key (D) and what it enables
- Super Sonic / emerald debug keys
- Special stage debug keys
- Note about GLFW key codes for rebinding, with link to configuration.md

Length: ~80-100 lines.

### playing/game-status.md

**Goal:** Honest, up-to-date status of each supported game so players know what to expect.

Format: One section per game, each containing:

- A status summary line ("Broadly playable from start to finish" / "Early support, AIZ only")
- What works well (list)
- Known gaps or incomplete areas (list)
- Notable quirks or differences from the original

Games: Sonic 1, Sonic 2, Sonic 3 & Knuckles.

This page should be updated with each release. Include the date of last update at the top.

Length: ~100-150 lines.

### playing/troubleshooting.md

**Goal:** Fix common problems.

Format: Symptom-based headings.

Sections:

- **Black screen / crash on startup** — Wrong ROM revision, missing ROM, OpenGL version
  too low, Java version too old.
- **No sound** — `audio.enabled` config, system audio driver issues.
- **Wrong colors or garbled graphics** — GPU driver issues, scale factor misconfiguration.
- **Game runs too fast or too slow** — FPS setting, NTSC vs PAL region.
- **"Object registry missing id" warnings** — Normal for incomplete zones; not a bug.
- **Specific ROM revision** — Emphasize that only REV01 (S1/S2) and the combined S3K
  lock-on ROM are tested.

Length: ~80-120 lines.

---

### cross-referencing/how-the-engine-reads-roms.md

**Goal:** Explain the conceptual pipeline from ROM binary to rendered screen, so a
reader who knows the disassembly can understand where to look in the engine for
any given piece of game data.

This is the highest-value page for Audience 2. It should be readable without Java
knowledge.

Sections:

1. **What the engine is not** — Not an emulator. No 68000 CPU, no VDP, no Z80. All
   hardware behavior is reimplemented at a higher abstraction level.

2. **The ROM as a data source** — The engine treats the ROM as a read-only data file.
   It reads bytes at known offsets, decompresses them using the same algorithms
   (Kosinski, Nemesis, Enigma, Saxman), and interprets the resulting data using the
   same format definitions as the original hardware.

3. **How addresses are found** — Two strategies:
   - Hardcoded constants verified against specific ROM revisions (the Constants files).
   - Pattern-matching ROM scanners that locate tables by signature (the RomScanner classes).
   Explain that this means the engine is tied to specific ROM revisions, and why.

4. **Level data pipeline** — ROM offset to level layout directory to level layout to
   chunks to blocks to tiles. Explain how each stage maps to a disassembly concept.
   Candidate for a diagram.

5. **Art pipeline** — ROM offset to compressed art to decompressed tiles to GPU pattern
   atlas. Explain PLC (Pattern Load Cue) system. Mention that the engine pre-loads art
   rather than streaming it like the VDP would.

6. **Object pipeline** — Object placement lists loaded from ROM. Object registry maps
   IDs to instance classes. Each frame, active objects are updated and rendered.
   This is where the 68000 object RAM concept maps to instance fields.

7. **Audio pipeline** — SMPS music/SFX pointers in ROM to parsed sequence data to
   software-emulated YM2612 + SN76489. The Z80 driver is reimplemented, not emulated.

8. **Collision pipeline** — Collision arrays and angle data loaded from ROM. Height
   arrays used for terrain checks. Same lookup logic as original, different
   implementation language.

Length: ~200-250 lines. This is a meaty page.

### cross-referencing/mapping-exercises.md

**Goal:** Teach the reader how to trace any feature between the disassembly and the
engine, using worked examples that build transferable skills.

This is the core document for Audience 2. Rather than a static Rosetta Stone, it
teaches the method. Each exercise follows the same three-step pattern:
1. Find it in the disassembly
2. Find it in the engine
3. Verify the correspondence

#### Exercise 1: Tracing level data

"Where does the engine load EHZ level layout data?"

- **Disassembly side:** Navigate s2.asm to find the level layout directory. Show the
  BINCLUDE directive for EHZ level data. Explain the offset table format.
- **Engine side:** Find `Sonic2Constants.LEVEL_LAYOUT_DIR_ADDR`. Trace it to
  `LevelResourcePlan` and `LevelManager`. Show the loading path without requiring
  the reader to understand Java syntax deeply.
- **Verification:** Use `RomOffsetFinder verify` to confirm the address matches.

#### Exercise 2: Tracing an object (ArrowShooter / Obj22)

"How does the ArrowShooter work in the engine compared to the disassembly?"

- **Disassembly side:** Walk through Obj22 in s2.asm (lines 51034-51168). Explain the
  routine dispatch table, the init/main/shoot/arrow states. Show the detection logic
  and the animation script with its $FF/$FC/$FD command bytes.
- **Engine side:** Show how to find the class — search the object registry for 0x22,
  find `ArrowShooterObjectInstance`. Walk through the init, detection, animation, and
  arrow-spawning logic, explaining how each maps to the ASM routines.
- **Verification:** Compare specific values (detection distance $40, arrow velocity
  $400, collision flags $9B, priority values, mapping frames).

This exercise cross-links with the worked tutorial in the contributing section, but
focuses on *reading* the correspondence rather than *writing* new code.

#### Exercise 3: Tracing art and sprites

"Where does the engine get the ArrowShooter's graphics?"

- **Disassembly side:** Find `ArtNem_ArrowAndShooter` in the disassembly. Show the
  PLC entry that references it. Show the sprite mapping file (obj22.asm) and explain
  the spritePiece format.
- **Engine side:** Trace from `Sonic2PlcArtRegistry` through `Sonic2ObjectArt` to the
  GPU pattern atlas. Show how mapping frames correspond to the ASM definitions.
- **Verification:** Use `RomOffsetFinder search ArrowShooter` and
  `RomOffsetFinder plc` to confirm addresses.

#### Exercise 4: Tracing audio

"Where does the engine find the Pre-Arrow Firing sound effect?"

- **Disassembly side:** Find `SndID_PreArrowFiring` in s2.constants.asm. Trace to
  the sound pointer table.
- **Engine side:** Find `Sonic2Sfx.PRE_ARROW_FIRING`. Trace through `SmpsConstants`
  to the SFX pointer table address. Show how the SMPS loader parses the header.
- **Verification:** Use `RomOffsetFinder search-rom` with the expected pointer bytes.

#### Exercise 5: Investigating a discrepancy

"I think the engine does X differently from the ROM. How do I check?"

- Identify what the disassembly actually does (watch for macro expansion, conditional
  assembly, context from surrounding routines).
- Find the equivalent engine code path.
- Run the engine with debug overlays to observe the behavior.
- Write or run a test to confirm (link to testing.md).
- Where to report: BUGLIST.md, S3K_KNOWN_DISCREPANCIES.md, or a new issue.

#### Method Card

A concise reference table summarizing the approach for common tracing tasks:

| I want to find...  | Disasm starting point         | Engine starting point              | Verification tool                  |
|---------------------|-------------------------------|------------------------------------|------------------------------------|
| Level data          | `Level_` / `Off_Level` labels | `*Constants.java` LEVEL_* fields   | `RomOffsetFinder verify`           |
| Object behavior     | `Obj__:` routine label        | `*ObjectRegistry` class lookup     | Compare constants and state logic  |
| Object art/sprites  | `ArtNem_` / `Nem_` labels     | `*ObjectArt` or PLC registry       | `RomOffsetFinder test <off> nem`   |
| Sprite mappings     | `mappings/sprite/obj__.asm`   | Parsed at load, frames in renderer | Frame count and piece comparison   |
| Collision data      | `collision/` or `collide/`    | `*Constants.COLLISION_*` fields    | Binary compare against disasm .bin |
| Music/SFX           | `sound/` dir, pointer table   | `*SmpsConstants.java`              | `search-rom` for pointer patterns  |
| Palettes            | `Pal_` labels                 | `*Constants.PALETTE_*` fields      | Read 32 bytes at offset, compare   |
| Level events        | `LevEvents_` labels           | `*LevelEvent.java` per-zone class  | Compare trigger thresholds         |
| Animation scripts   | `Ani_obj__` inline data       | Animation loaded from ROM or coded | Compare frame sequences            |

Length: ~300-400 lines. The longest page in the guide, and the most important for
Audience 2.

### cross-referencing/architecture-overview.md

**Goal:** A package-level map of the engine for readers who are not Java developers.

Format: A directory tree with one-line descriptions, followed by brief explanations
of the major components. No code samples — just "what lives where and what it does."

```
com.openggf/
  Engine.java              -- Application entry point, window creation, main loop
  GameLoop.java            -- Per-frame update orchestration
  game/                    -- Game module system: the pluggable layer per game
    GameModule.java        -- Interface that each game implements (S1, S2, S3K)
    GameServices.java      -- Global services (ROM access, graphics, config)
    sonic1/                -- Sonic 1 specific: objects, bosses, events, art, audio
    sonic2/                -- Sonic 2 specific: same structure
    sonic3k/               -- Sonic 3&K specific: same structure
  level/                   -- Level loading, tile layout, chunk/block data
  physics/                 -- Physics engine, collision detection, terrain sensors
  sprites/                 -- Sprite/object instance system, animation, playable characters
  graphics/                -- GPU rendering: shaders, pattern atlas, FBO compositing
  audio/                   -- SMPS sound driver, YM2612/SN76489 emulation, DAC playback
  camera/                  -- Camera position, scroll boundaries, screen shake
  data/                    -- ROM binary reading, decompression algorithms
  debug/                   -- Debug overlays, sensor visualization
  tools/                   -- Offline tools (RomOffsetFinder, ObjectDiscoveryTool)
```

Then a "key concepts" section explaining:
- **GameModule / Provider pattern** — How S1/S2/S3K plug into the engine. Each game
  implements a set of provider interfaces. The engine calls providers without knowing
  which game is active.
- **ObjectInstance** — The engine's equivalent of an object slot in the original's
  object RAM. Each object type is a subclass.
- **ObjectRegistry** — Maps object IDs (the hex numbers from the disassembly) to
  the classes that implement them.
- **Constants files** — Where ROM addresses live. One per game.
- **ResourceLoader** — Decompression. Takes a ROM offset and compression type,
  returns decoded bytes.

Length: ~120-160 lines.

### cross-referencing/tooling.md

**Goal:** Reference for the RomOffsetFinder and other built-in tools.

Largely restructured from the existing CLAUDE.md tool documentation, but reframed
for Audience 2 rather than for Claude Code. Add context about *why* you would use
each command.

Sections:
- search — "I know a label name, where is it in the ROM?"
- verify — "I have an address, does it match what the disassembly expects?"
- list — "What resources of type X exist?"
- test — "Is this ROM offset actually compressed data?"
- search-rom — "I need to find inline data that has no label"
- plc — "What art does this PLC entry load?"
- Game flag: `--game s1`, `--game s2` (default), `--game s3k`

Length: ~100-140 lines.

### cross-referencing/per-game-notes.md

**Goal:** Game-specific quirks that affect cross-referencing.

Sections:

**Sonic 1:**
- Different label prefixes (`Nem_` vs `ArtNem_`, `Map_` vs `MapUnc_`)
- 5-byte sprite mapping format (vs S2's 6-byte)
- 256x256 metatiles (vs S2's 128x128)
- Object files in `_incObj/` with `HEX Name.asm` naming
- ROM address verification caveat (disasm labels may not match compiled ROM)

**Sonic 2:**
- Inline object code in s2.asm (not separate files)
- PLC system and ArtLoadCues table structure
- HTZ/EHZ resource overlay system
- REV00 vs REV01 differences

**Sonic 3 & Knuckles:**
- Combined 1P+2P mapping/DPLC tables (the 502-entry gotcha)
- S3 vs S&K split (two disassembly sources for some data)
- SolidIndexes pointer format with flag bits
- KosinskiM (Kosinski Moduled) compression
- Z80 sound driver differences (1-byte bank entries, separate data block)

Length: ~150-200 lines.

### cross-referencing/68000-primer.md

**Goal:** One-page reference for reading 68000 assembly in the context of Sonic
disassemblies. Not a full architecture guide — just enough to follow object routines.

Sections:

1. **Registers** — d0-d7 (data), a0-a7 (address). a0 typically points to the current
   object. a1 often points to another object or the player.

2. **Common instructions:**
   - `move.b/w/l src, dst` — copy data (byte/word/long)
   - `add/sub/and/or` — arithmetic and bitwise
   - `cmp + bcc/bne/beq/bhi/bhs/bmi/bpl` — compare and branch
   - `tst` — compare with zero
   - `jmp/jsr/rts` — jump, call subroutine, return
   - `lea` — load effective address (pointer math)
   - `moveq` — quick move small constant to register

3. **Object field access pattern:**
   ```asm
   move.b  routine(a0),d0    ; read the routine field from the current object
   move.w  x_pos(a0),d0      ; read the X position
   addq.b  #2,routine(a0)    ; advance to next routine (state)
   ```

4. **Routine dispatch pattern** — The `moveq/move.b/move.w/jmp` idiom that appears
   at the top of every object. Explain that routine values increment by 2 (not 1)
   because they index into a word-sized offset table.

5. **Animation script bytes** — What $FF (loop), $FE (reset), $FD (callback/routine
   increment), $FC (change animation) mean.

6. **Fixed-point math** — Velocities stored as 8.8 fixed point. `$0400` = 4.0 pixels
   per frame.

Length: ~100-130 lines.

---

### contributing/dev-setup.md

**Goal:** Get a contributor from zero to running tests.

Contents:
1. Prerequisites: Java 21, Maven 3.8+, IntelliJ IDEA recommended (Gradle not used)
2. Clone the repository
3. Place ROM files (same as playing guide, link rather than duplicate)
4. Build: `mvn package`
5. Run: `java -jar target/...jar`
6. Run tests: `mvn test` (note: ROM-dependent tests skip gracefully if ROMs absent)
7. Run a single test: `mvn test -Dtest=TestClassName`
8. GraalVM native image build (optional, for ahead-of-time compilation)
9. Project structure orientation — brief pointer to architecture.md

Length: ~80-100 lines.

### contributing/architecture.md

**Goal:** Explain the engine architecture in enough depth that a contributor can find
where to make changes.

Sections:

1. **GameModule and the provider pattern** — Each supported game (S1, S2, S3K)
   implements `GameModule`, which returns a set of providers: `ObjectRegistry`,
   `ScrollHandlerProvider`, `PhysicsProvider`, `LevelEventProvider`,
   `WaterDataProvider`, etc. The engine core calls these interfaces without knowing
   which game is active. To add behavior for a specific game, you implement or
   extend the relevant provider.

2. **GameServices and ObjectServices** — Two-tier service architecture. `GameServices`
   is global (ROM access, graphics, configuration). `ObjectServices` is contextual
   (level, camera, audio, game state) and available to object instances via
   `services()`. This separation exists to support the planned level editor, where
   multiple level contexts may coexist.

3. **Session-owned gameplay state** — The target is explicit ownership through
   `SessionManager`, `WorldSession`, and `GameplayModeContext`. ObjectServices
   are backed by the active gameplay mode rather than singletons or the retired
   `GameRuntime` facade.

4. **Level initialization** — The `LevelInitProfile` system: a declarative sequence
   of 13 steps that each game defines. Steps include loading layouts, decompressing
   art, setting up collision, registering objects, configuring water, etc. This
   replaced a monolithic `loadLevel()` method.

5. **Object lifecycle** — Object placement data is loaded from the ROM. As the camera
   scrolls, objects within range are spawned via the `ObjectRegistry`. Each spawned
   object is an `ObjectInstance` subclass. Every frame, active objects receive
   `update()` and `appendRenderCommands()` calls. Objects set themselves as destroyed
   when they should be removed. Dynamic child objects (projectiles, debris, boss
   parts) should be spawned through the object's lifecycle helper, usually
   `spawnChild(...)`, so ownership, remembered slots, and parent/child cleanup stay
   consistent.

6. **Rendering pipeline** — GPU-based. Tile patterns are uploaded to a texture atlas.
   Sprites are drawn as instanced quads referencing atlas regions. Background layers
   use a tilemap shader. Priority is handled via FBO compositing. Contributors
   adding objects mostly interact with `PatternSpriteRenderer.drawFrameIndex()` and
   don't need to touch the GPU layer.

7. **Audio pipeline** — `SmpsLoader` parses music/SFX from ROM. `SmpsSequencer` drives
   playback. `YM2612` and `SN76489` are software emulations of the original chips.
   `DacPlayer` handles PCM sample playback. Per-game differences (driver config,
   tempo mode, note mapping) are captured in `SmpsSequencerConfig`.

Length: ~250-300 lines.

### contributing/tutorial-implement-object.md

**Goal:** Worked example implementing a Sonic 2 object from scratch. Uses
ArrowShooter (Obj22) as the subject — a real, already-implemented object that the
reader "pretends" doesn't exist yet. The real implementation is available as the
answer key.

This is the most important page in the contributing section. It should be detailed
enough that someone could follow it to implement a *different* object without further
guidance.

#### Structure:

**Step 1: Read the disassembly**

Open s2.asm and find Obj22 (line 51034). Walk through:
- The routine dispatch table (5 routines: Init, Main, ShootArrow, Arrow_Init, Arrow)
- What each routine does, in plain English
- The animation script (Ani_obj22) and what the command bytes mean
- The sprite mappings (obj22.asm) — 5 frames, what each looks like
- Key constants: detection distance ($40), velocity ($400), collision ($9B)

Include the actual ASM with annotations.

**Step 2: Plan the implementation**

Before writing code, map the disassembly structure to the engine's patterns:
- Obj22 has two logical objects: the shooter and the arrow. In the disassembly these
  share a single Obj22 ID with different routines. In the engine, they become two
  classes: `ArrowShooterObjectInstance` (the shooter) and `ArrowProjectileInstance`
  (the arrow).
- The shooter's states (idle, detecting, firing) map to animation state tracking.
- The arrow is a dynamically spawned child object.

Explain *why* the engine splits them: the original uses object RAM slots with a shared
ID; the engine uses typed instances. This is a fundamental mapping pattern that applies
to any object with child objects.

**Step 3: Register the object**

Show the two places to wire up:
1. `Sonic2ObjectRegistryData` — add the name mapping: `map.put(0x22, List.of("ArrowShooter"))`
2. `Sonic2ObjectRegistry.registerDefaultFactories()` — add the factory that creates
   `ArrowShooterObjectInstance` from an `ObjectSpawn`.

Explain what `ObjectSpawn` contains (x, y, objectId, subtype, renderFlags) and how it
maps to the object placement data in the ROM.

**Step 4: Implement the shooter**

Walk through creating `ArrowShooterObjectInstance`:
- Extend `AbstractObjectInstance`
- Constructor: extract position and flip from `ObjectSpawn`
- `update()`: detection logic (compare player X to shooter X, threshold $40)
- Animation state machine: idle -> detecting -> firing -> idle
- `fireArrow()`: play SFX, create `ArrowProjectileInstance`, and spawn it via
  `spawnChild(...)`
- `appendRenderCommands()`: get renderer by art key, draw current frame
- `appendDebugRenderCommands()`: draw bounding box for debug overlay

Map each piece back to the ASM routine it corresponds to.

**Step 5: Implement the arrow**

Walk through creating `ArrowProjectileInstance`:
- Extend `AbstractObjectInstance`, implement `TouchResponseProvider` (for hurt collision)
- Constructor: set velocity based on direction, play firing SFX
- `update()`: fixed-point position update, wall collision check, off-screen cleanup
- `getCollisionFlags()`: return $9B (the engine uses this for touch response)
- Rendering: same art key as shooter, mapping frame 0

**Step 6: Art and PLC wiring**

Explain how the object gets its graphics:
- The PLC system loads `ArtNem_ArrowAndShooter` when ARZ is loaded
- The engine's `Sonic2PlcArtRegistry` maps PLC entries to art keys
- The object references its art via `Sonic2ObjectArtKeys.ARROW_SHOOTER`
- `getRenderer(artKey)` returns a `PatternSpriteRenderer` with the loaded frames

**Step 7: Test it**

Show how to verify the implementation:
- Run the engine, go to ARZ, find an ArrowShooter in the level
- Enable debug overlay (F1) to see bounding boxes and object labels
- Verify: detection triggers at correct distance, arrow fires in correct direction,
  arrow stops at walls, SFX plays at correct times
- Optional: write a `HeadlessTestFixture` test that spawns the object and verifies
  frame-by-frame behavior

**Answer key**

Note that the real implementations are at:
- `src/main/java/com/openggf/game/sonic2/objects/ArrowShooterObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/ArrowProjectileInstance.java`

The reader can diff their work against these files.

Length: ~400-500 lines. The longest page in the guide.

### contributing/adding-bosses.md

**Goal:** Patterns specific to boss implementation, beyond what the object tutorial
covers.

Sections:

1. **State machine pattern** — Boss routines in the disassembly use `routine(a0)` and
   `routine_secondary(a0)` for major and minor states. In the engine, these map to
   enum-based state machines. Walk through a simple example (e.g., EHZ boss has
   approach, attack, retreat, defeated states).

2. **Hit detection and health** — `collision_property` as hit count. Boss flash on hit.
   Invincibility frames.

3. **Child objects** — Projectiles, debris, body parts. The spawning pattern from the
   tutorial applies, but bosses tend to have more children.

4. **Camera lock and arena setup** — Boss events in the `LevelEventProvider` system.
   Camera boundary changes, music triggers, screen lock/unlock.

5. **Defeat sequence** — Explosion chain, score award, egg prison activation, capsule
   release, screen unlock.

6. **Example: EHZ Boss** — Brief annotated walkthrough of `Sonic2EHZBossInstance` as
   a reference, not a full tutorial.

Length: ~200-250 lines.

### contributing/adding-zones.md

**Goal:** What it takes to bring up a new zone.

Sections:

1. **Level data verification** — Use RomOffsetFinder to confirm layout, chunk, block,
   and collision addresses. Load the level in the engine and check for garbled tiles.

2. **Scroll handler** — Parallax backgrounds. Each zone typically needs a custom
   scroll handler. Explain the `ScrollHandlerProvider` interface and show the pattern
   from an existing zone (e.g., EHZ has a simple two-layer parallax).

3. **Zone events** — Camera boundary changes, dynamic triggers, earthquake effects,
   water level changes. Implement via the `LevelEventProvider` system with a
   zone-specific event class.

4. **Palette and palette cycling** — Zone palette loaded from ROM. Palette cycle
   manager for animated colors (waterfalls, lava, etc.).

5. **Object set** — Which objects appear in the zone. Register them if not already
   implemented. PLC configuration for zone-specific art.

6. **Water** — If the zone has water: `WaterDataProvider` configuration, water height
   table, underwater palette.

7. **Testing** — Load the zone, walk through, verify collision, verify object behavior,
   verify scrolling at zone boundaries and during transitions.

Length: ~150-200 lines.

### contributing/audio-system.md

**Goal:** Enough understanding to fix audio bugs or improve SMPS accuracy.

Sections:

1. **Architecture overview** — SmpsLoader (parsing), SmpsSequencer (playback),
   YM2612 (FM synthesis), SN76489 (PSG), DacPlayer (PCM samples). How they
   connect.

2. **SMPS format** — Music header, channel headers, sequence data. Pointer table
   structure. How the engine parses this from ROM.

3. **Per-game driver differences** — S1, S2, S3K each have different driver
   configurations: tempo mode (OVERFLOW vs OVERFLOW2), base note mapping, PSG
   envelope handling, operator order. These are captured in `SmpsSequencerConfig`.

4. **FM synthesis** — Brief overview of YM2612 operators, algorithms, and how the
   engine's software emulation works. Reference the Genesis Plus GX and libvgm cores.

5. **Comparing against SMPSPlay** — How to use ValleyBell's SMPSPlay as a reference
   for accuracy testing. Channel-by-channel comparison approach.

6. **Common audio bugs** — Tempo issues (overflow direction), missing instruments
   (wrong operator order), DAC sample rate miscalculation, PSG noise register
   behavior.

Length: ~200-250 lines.

### contributing/testing.md

**Goal:** How to write and run tests.

Sections:

1. **Running tests** — `mvn test`, single class,
   parallel execution (8 JVMs); use `target/surefire-reports` and
   `target/trace-reports` for reports.
   ROM-optional behavior.

2. **HeadlessTestFixture** — Builder pattern for setting up a test level. Show a
   simple example: load a zone, place Sonic, step N frames, assert position.

3. **ROM-dependent tests** — How to gate tests on ROM availability. The `@RequiresRom`
   pattern.

4. **Visual regression** — Approach for screenshot comparison tests. How to capture
   and compare rendered frames.

5. **Audio regression** — Approach for audio capture comparison.

6. **Physics integration tests** — Testing slope behavior, collision edge cases,
   object interactions. The HeadlessTestRunner step-frame approach.

7. **Test organization** — Tests grouped by level/zone. Naming conventions. Where
   test files live.

Length: ~120-160 lines.

---

## Writing Order

Suggested order for writing the pages, prioritizing the most impactful content first:

### Phase 1: Core content
1. `index.md` — Landing page (short, establishes structure)
2. `playing/getting-started.md` — Entry point for the largest audience
3. `cross-referencing/how-the-engine-reads-roms.md` — Highest-value page for Audience 2
4. `cross-referencing/68000-primer.md` — Prerequisite for the exercises
5. `cross-referencing/mapping-exercises.md` — Core of the cross-referencing section

### Phase 2: Contributing
6. `contributing/dev-setup.md` — Entry point for contributors
7. `contributing/architecture.md` — Foundation for all contribution work
8. `contributing/tutorial-implement-object.md` — The worked example

### Phase 3: Reference and polish
9. `playing/configuration.md` — FAQ-style config reference
10. `playing/controls.md` — Controls reference
11. `playing/game-status.md` — Per-game status
12. `playing/troubleshooting.md` — Problem solving
13. `cross-referencing/architecture-overview.md` — Package map
14. `cross-referencing/tooling.md` — RomOffsetFinder reference
15. `cross-referencing/per-game-notes.md` — Game-specific quirks
16. `contributing/adding-bosses.md` — Boss patterns
17. `contributing/adding-zones.md` — Zone bring-up
18. `contributing/audio-system.md` — Audio system guide
19. `contributing/testing.md` — Testing guide

## Maintenance

The guide should be updated alongside significant engine changes:

- **game-status.md** — Updated with each release.
- **architecture.md** — Updated when major architectural changes land (e.g.,
  session-ownership migration).
- **tutorial-implement-object.md** — Stable unless the object system fundamentally changes.
- **mapping-exercises.md** — Stable by design (teaches methods, not specific addresses).
- **68000-primer.md** — Effectively permanent.

The guide does NOT need to track every commit. It tracks *understanding*, not *changelog*.
