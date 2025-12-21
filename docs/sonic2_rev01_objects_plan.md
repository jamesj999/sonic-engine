# Sonic 2 (Genesis) Rev01 object placement and object engine notes (implementation plan)

This document is meant to be handed to an AI coding agent (Codex, etc.) so it can add accurate Sonic 2 Rev01 object placement loading to a custom engine. The immediate goal is to load a level and spawn objects at the correct world coordinates. Behaviour and interaction comes later.

## Scope and success criteria

The first milestone is achieved when the engine can, for any supported Zone and Act, read object placement data from a Rev01 ROM and instantiate placeholder objects at the correct positions with the correct object ID and subtype.

The second milestone is achieved when objects are only spawned when they enter the active range, matching the original engine’s “object manager” behaviour.

## Primary reference sources (use these as ground truth)

This plan assumes you will pull constants, offsets, and formats from the Sonic 2 disassembly, and cross-check with existing tooling configurations.

Disassembly (Sonic Retro, mirrored with a plain-text view that is easy for agents to scrape):
https://www.git.fragag.ca/s2disasm.git/tree/?id=f7253e04d0872a78642c4779a5c3bd6e3ce86a8f

The disassembly supports building different revisions via a `gameRevision` build setting (0 for Rev00, 1 for Rev01, 2 for Rev02). Always target Rev01.
https://www.git.fragag.ca/s2disasm.git/diff/s2.asm?id=da9bfb94edc7ccb2f9f78e78512ea126b49e1c8d

Notes about object RAM layout (Object Status Table base address and slot size) are present in the disassembly’s constants, including a reference to `$FFFFB000` as the base and `$40` bytes per slot.
https://www.git.fragag.ca/s2disasm.git/commit/s2.constants.asm?h=classics&id=4df2de90d4fc61474146c328b6340acfdeb3a10a

Existing engine (your Java Sonic engine):
https://github.com/jamesj999/sonic-engine/tree/ai-improvement-test-1

## What to extract from the ROM

The minimal spawn record your engine needs per placed object:

| Field | Type | Notes |
| --- | --- | --- |
| x | int | world X coordinate in pixels |
| y | int | world Y coordinate in pixels |
| objectId | int | 8-bit object ID used by the game’s object index table |
| subtype | int | 8-bit subtype, meaning depends on objectId |
| xFlip | bool | if present in the placement flags |
| yFlip | bool | if present in the placement flags |
| rawFlags | int | any other placement flags that you do not yet implement |

Ring placement is stored in a separate table, not the object list. Rings are grouped into short horizontal or vertical lines.

The minimal ring record your engine needs per placed ring:

| Field | Type | Notes |
| --- | --- | --- |
| x | int | world X coordinate in pixels |
| y | int | world Y coordinate in pixels |
| sourceGroupNibble | int | high nibble from the ring Y word (useful for debugging) |

### Object placement data (to be verified in disassembly)

Sonic 2 stores per-Act object placement as packed records in ROM, and uses a pointer table to locate each Act’s list.

The agent should treat the disassembly as authoritative, and specifically locate the following in `s2.asm` (search by symbol name, then resolve to ROM offsets for Rev01):

| Symbol or concept | What it is used for |
| --- | --- |
| Object placement pointer table (often named `ObjPos_Index` or similar) | maps Zone+Act to a placement list address or offset |
| Object placement lists | sequential placement records ending with a terminator marker |
| Respawn table indexing scheme | determines how placed objects map to respawn bits |
| Any “format” comments near the placement reader | describes record length and flag bits |

Important: do not hard-code any guessed offsets. Instead, hard-code offsets only after verifying them directly in the Rev01 disassembly build, and include a link to the relevant symbol definition in a comment.

### Ring placement data (to be verified in disassembly)

Sonic 2 stores ring placements in a separate pointer table with compact 4-byte records.

Find the ring placement pointer table (often labelled `RingPos_Index` or similar) and the ring list format in `s2.asm`.
For Rev01, a known offset index starts at ROM `0x0E4300`, and the first list begins at `0x0E4344` (EHZ1).

Each ring record uses two words:
- X word: 16-bit X position. `0xFFFF` terminates the list.
- Y/count word: lower 12 bits are Y position. The upper nibble encodes ring count and orientation:
  - 0x0..0x7: horizontal line, `n` extra rings to the right (total = `n+1`)
  - 0x8..0xF: vertical line, `n-8` extra rings downward (total = `n-7`)

Default ring spacing between neighbors is 16 pixels.

## How the original engine handles objects (behavioural model)

Sonic 2 uses an Object Status Table (OST) in RAM with fixed-size slots. The disassembly documents a common convention where an object address can be turned into an index by subtracting `$FFFFB000` and dividing by `$40`. This is a strong indicator that the base of the OST is `$FFFFB000` and each object slot is `$40` bytes.

For your engine, you do not need to match the in-RAM layout byte-for-byte to load placement, but it is useful to mirror the behaviour at a higher level:

| Engine concept | Sonic 2 behaviour to emulate |
| --- | --- |
| “Placed object list” | a sorted stream of placements for the current Act, typically iterated as camera X advances |
| “Active object slots” | a fixed-capacity pool of objects updated every frame |
| “Spawner window” | objects are instantiated only when near the camera, and deleted when far away |
| “Respawn / remember” | many objects use a bitfield table so destroyed or collected objects do not respawn when you backtrack |

## Implementation strategy for your Java engine

The safest plan is to implement this in three layers: ROM parsing, level spawn list building, and runtime object management.

### Layer 1: ROM reader utilities

Add a small ROM reading utility that is explicit about endianness.

Required primitives:

| Method | Behaviour |
| --- | --- |
| readU8(addr) | unsigned byte |
| readU16BE(addr) | unsigned 16-bit big-endian |
| readS16BE(addr) | signed 16-bit big-endian |
| slice(addr, len) | return a byte array copy |
| readPointer16(base, index) | helper for word-offset tables when pointers are relative |

Make the ROM reader pure and testable. It should not reference engine state.

### Layer 2: Object placement extractor (offline to gameplay)

Provide an extractor that consumes:
the ROM bytes
the current Zone and Act selection
the level’s world coordinate system (tile sizes and camera origin conventions, already handled in your level loader)

It produces a list of ObjectSpawn records.

Suggested Java shape:

| Type | Responsibility |
| --- | --- |
| ObjectSpawn | immutable record: x, y, objectId, subtype, flags |
| Sonic2ObjectPlacement | parse the ROM data and return List<ObjectSpawn> |
| ZoneAct | canonical mapping of Zone and Act to an index into the placement pointer table |

The extractor should also expose a debug dump format (CSV or JSON) so you can compare results with external tooling.

### Ring placement extractor (parallel to object placements)

Provide a ring extractor that consumes:
the ROM bytes
the current Zone and Act selection

It produces a list of individual ring spawns (expanded from ring groups).

Suggested Java shape:

| Type | Responsibility |
| --- | --- |
| RingSpawn | immutable record: x, y, (optional) group metadata |
| Sonic2RingPlacement | parse the ROM data and return List<RingSpawn> |

### Layer 3: Runtime object manager (camera-window spawning)

Implement an object manager that maintains:
a sorted list of ObjectSpawn records (sorted by x)
a cursor pointing at the next spawn to consider
an active pool of in-game object instances

Behaviour:

| Step | Description |
| --- | --- |
| Level start | reset the cursor to the first spawn whose x is within the initial camera window plus look-ahead |
| Each frame | advance cursor and spawn any objects whose x is now within the load window |
| Each frame | despawn objects that are sufficiently far outside the unload window |
| Backtracking | either support reverse scanning, or keep a bi-directional structure and respawn rules |
| Respawn rules | initially, keep it simple (always respawn when re-entering window). Later, add per-object respawn bits |

Use the original engine as a behavioural guide, but start with “always respawn” so you can validate placement correctness first.

### Ring runtime management (simplified)

Maintain a sorted list of ring spawns and show only those within the camera load window.
Rings do not use the object manager, but should respect the same camera window distances for debug and parity checks.

## Verification plan (accuracy-first)

The agent should not consider this “done” until placement can be proven correct.

Recommended validation steps:

| Validation | How to do it |
| --- | --- |
| Cross-check with disassembly symbol addresses | for every hard-coded ROM address used, include a comment showing the symbol and revision |
| External tool parity | export spawns as CSV and compare against a trusted level editor or config (SonLVL, etc.) |
| Snapshot tests | for a chosen Zone+Act, assert a handful of known objects exist at known coordinates |
| Visual debug | render object placeholders with an ID label in a debug overlay to spot systematic off-by-one or coordinate origin bugs |
| Ring parity | render ring placeholders and confirm obvious ring lines match the ROM |

A good “first act” to validate is one with obvious placed objects (monitors, rings as objects if applicable, springs, etc.) and a stable camera origin.

## What to give Codex as input (prompt framework)

Codex works best when it has:
the target repository and branch
a single clear milestone
references to exact file locations, symbols, and expected tests

Template prompt (edit paths to match your repo layout):

"""
You are implementing Sonic 2 Rev01 object placement loading.

Repo: https://github.com/jamesj999/sonic-engine/tree/ai-improvement-test-1

Goal: when a level is loaded, parse object placements from a Rev01 ROM and spawn debug placeholder entities at correct (x,y) positions.

Constraints:
- Use big-endian reads for ROM words.
- Do not guess ROM addresses. Find them in the Sonic 2 disassembly and add citations in comments.
- Implement unit tests that parse the ROM and validate a small set of known placements.

Primary references:
- Disassembly tree: https://www.git.fragag.ca/s2disasm.git/tree/?id=f7253e04d0872a78642c4779a5c3bd6e3ce86a8f
- Rev selection: https://www.git.fragag.ca/s2disasm.git/diff/s2.asm?id=da9bfb94edc7ccb2f9f78e78512ea126b49e1c8d
- OST base and slot size clue: https://www.git.fragag.ca/s2disasm.git/commit/s2.constants.asm?h=classics&id=4df2de90d4fc61474146c328b6340acfdeb3a10a

Deliverables:
- A ROM reader utility.
- A Sonic2ObjectPlacement loader returning List<ObjectSpawn>.
- Integration in the existing level load path to spawn placeholders.
- A debug export (CSV).
- Unit tests.
"""

## Next steps after placement (later milestones)

Once placement works, the next pieces to mirror from the original engine are:
object update dispatch (objectId to code routine table)
per-object routine state machines (the `routine` byte pattern)
per-object collision and interaction hooks (solid objects, harmful objects, monitors, springs)
respawn bit behaviour for destroyable and collectible objects

These should be implemented incrementally, object-by-object, with tests and visual validation.
