# Level Editor Overlay Design

**Date:** 2026-04-08
**Status:** Design
**Scope:** Consolidated brainstorming plan for the in-engine level editor overlay MVP

## Overview

This design consolidates the existing editor discussions across the earlier February level-editor
docs, the March `MutableLevel` and runtime work, and the April runtime-ownership migration.

The target is a beautiful, live in-engine editor overlay focused on fast block placement and
hierarchical construction editing inside the running engine. It is explicitly an **overlay-first
MVP**, not a pane-heavy desktop editor shell.

The key product principles are:

- the world canvas remains the dominant surface
- the editor follows the real construction hierarchy of the level
- play-test toggling must feel immediate and low-hitch
- chunk and block workflows are primary; pattern editing is contextual and secondary

## Problem

Older editor notes established several useful ideas:

- a world-space grid overlay and cursor
- a side library for chunks/blocks
- contextual help and keyboard-driven editing
- `MutableLevel` as the live editable data source
- runtime/session ownership boundaries for future editor mode switching

However, those notes still flattened `block`, `chunk`, and `pattern` into sibling editing modes.
That is not how the level is actually constructed.

The engine's level hierarchy is:

- map cells place blocks
- blocks are assembled from chunks
- chunks are assembled from patterns

The editor should reflect that hierarchy directly instead of presenting three unrelated global
browsers.

## Goals

### Product Goals

- support live block placement directly in the loaded world
- allow users to derive, edit, and delete blocks using chunks
- allow users to derive, edit, and delete chunks using patterns
- allow seamless switching between editing and play-test from the current location
- keep the overlay visually strong without overwhelming the level view

### Engineering Goals

- build on `WorldSession`, `EditorModeContext`, and `MutableLevel`
- avoid full teardown/rebuild for the normal edit <-> play-test toggle
- use incremental dirty-region processing for responsive updates
- keep the MVP narrow enough to implement without dragging in save/load or full object tooling

## Non-Goals

The following are explicitly deferred from this MVP:

- object editing
- ring editing
- collision authoring as a top-level workflow
- save/load file formats
- a panel-rich desktop-style editor shell
- heavy metadata inspectors or modding-oriented authoring tools

## Editing Hierarchy

The editor uses three nested authoring layers.

### Layer 1: World Placement

The top-level interaction is editing the map by placing blocks into the live world.

Primary actions:

- move editor cursor
- place block
- clear block
- eyedrop block
- duplicate/derive selected block
- descend into selected block for composition editing

This is the hero workflow of the MVP.

### Layer 2: Block Composition

A block is edited as a composition of chunks.

Primary actions:

- navigate chunk cells inside the selected block
- replace chunk references
- clear chunk references where valid
- derive a new block from the current structure
- delete a derived block if no longer needed and safe to remove
- descend into the selected chunk

### Layer 3: Chunk Composition

A chunk is edited as a composition of patterns/pattern descriptors.

Primary actions:

- navigate pattern cells inside the selected chunk
- replace pattern references
- edit descriptor flags needed for composition
- derive a new chunk from the current structure
- delete a derived chunk if no longer needed and safe to remove

Pattern work is real, but it is not a top-level browsing mode. The user reaches it by editing a
chunk.

## Derive-New Editing Model

Shared definitions are common in Sonic level data. The MVP should avoid surprising destructive
global edits.

Default rule:

- edits to a placed block should usually create a new derived block definition and repoint the
  active map cell to that new block
- edits to a chunk should usually create a new derived chunk definition and repoint the active
  parent block cell to that new chunk

This is effectively copy-on-write for level composition data.

Benefits:

- preserves shared definitions unless the user explicitly intends to mutate them
- makes undo/redo behavior easier to reason about
- matches the real structure of the data

## Overlay Architecture

The editor should feel like part of the running game, not a second application.

### World Placement State

In normal editing, the world canvas remains full-strength and dominant.

Visible elements:

- live level canvas
- world-space grid overlay
- cursor highlight
- lightweight top toolbar
- contextual library pane for block browsing
- bottom command strip / help line

### Focused Block/Chunk Editing State

When the user descends into block or chunk editing:

- the world remains visible underneath
- the world fades toward white and becomes contextual rather than primary
- a centered overlay pane becomes the active editing surface
- that pane contains a navigable grid representing the current parent structure

For block editing, the pane shows the block's chunk layout.
For chunk editing, the pane shows the chunk's pattern layout.

This is a better fit for the prototype than a permanently exposed composition pane, because it:

- keeps the world visually present
- makes the current editing depth obvious
- gives sub-editing strong focus
- reduces always-visible chrome

## Navigation Model

The navigation model should map cleanly onto hierarchy depth.

- `Shift+Tab` toggles between editor and play-test
- `Tab` switches between major regions at the current depth
- `Enter` descends one level into the current selection
- `Esc` ascends one level
- arrow keys navigate the active canvas or overlay grid
- `Space` applies the primary action at the current depth
- `E` performs eyedropper at the current depth

The editor should show a breadcrumb such as:

- `World`
- `World > Block 12`
- `World > Block 12 > Chunk 3`

That breadcrumb should appear in the toolbar so the user always understands where they are in the
construction stack.

## Mode Switching and Play-Test

The default toggle must feel immediate and low-hitch. A full gameplay teardown and rebuild on every
toggle is architecturally safe but the wrong product feel.

### Default Toggle Behavior

`Shift+Tab` should toggle between:

- `Edit`
- `Play-Test From Here`

When entering the editor from gameplay:

1. freeze gameplay simulation
2. stash current play-test state
3. initialize editor cursor from the player position
4. activate editor overlay

When returning to play-test:

1. resume from the stashed play-test state
2. set the player's resume position from the editor cursor
3. selectively resync any systems invalidated by edits
4. resume simulation

By default, the stash should preserve player-centric runtime state such as:

- rings
- shield
- velocity
- facing
- similar lightweight play-test state where safe

### Separate Fresh Start Action

The editor must also provide a separate command:

- `Start From Beginning`

This action discards the current play-test stash and starts gameplay from the canonical level spawn
instead of the editor cursor.

This keeps the fast edit loop for normal use while still supporting a fresh-start verification flow.

## Runtime Shape

The editor should build on the runtime-ownership migration rather than reviving the older
singleton-style `LevelEditorManager` architecture as the ownership root.

### Ownership Split

- `WorldSession` owns the loaded world and durable editable level state
- `EditorModeContext` owns overlay state, selection state, history, and play-test stash
- gameplay state remains logically disposable, but should be parked/resumed when possible for the
  default toggle flow

### Important Distinction

`GameplayModeContext` may remain **logically disposable** without being **physically destroyed on
every toggle**.

For the prototype:

- default toggling should prefer freeze/detach/resume
- heavier rebuild should be reserved for explicit fresh-start flows

## Data Model

`MutableLevel` is the editable backing data source.

Responsibilities retained from earlier mutable-level work:

- live mutation of blocks, chunks, patterns, map cells, and related level data
- reverse lookup support for transitive dirtying
- incremental dirty-region consumption

The editor layer adds:

- selection state
- current depth and breadcrumb
- active library context
- undo/redo history
- play-test stash

### Dirty-Region Expectations

Edits should update only what changed:

- pattern changes reupload changed pattern data
- chunk/block/map changes rebuild only dirty tilemap regions
- other rebuilds happen only when those systems are affected

This is a core performance requirement for the overlay to feel responsive.

## MVP UI Components

### Always-Visible Elements

- top toolbar with zone/act, mode, undo/redo, play-test commands, and breadcrumb
- world canvas
- world-space grid and cursor overlay
- lightweight bottom command strip

### Contextual Elements

- block library pane during world placement
- focused block editor pane when editing a block
- focused chunk editor pane when editing a chunk
- lightweight selection info for the active item

The composition panes should be overlays, not permanent right-side dashboards.

## MVP Feature Set

### In Scope

- live block placement, clear, and eyedrop
- derive new blocks from chunks
- derive new chunks from patterns
- contextual pattern editing within chunk editing
- undo/redo for hierarchical edits
- breadcrumbed hierarchy navigation
- stashed edit <-> play-test toggle
- `Start From Beginning`

### Deferred

- object/ring editing
- persistent level save/load
- advanced metadata workflows
- collision authoring as a first-class layer
- full desktop-style multi-pane shell

## Safety and Recovery Rules

Edits may invalidate the current stashed play-test state. Resume should repair instead of failing.

Examples:

- player resume point is now inside solid terrain
- floor under the player was removed
- edited data invalidates an object/ring projection

Required behavior:

- prefer snapping to the nearest valid standing position near the editor cursor
- if that fails, fall back to a safe cursor-based spawn rule

The editor should never strand the user in an invalid resume state.

## Testing Strategy

The MVP should be verified at three levels.

### 1. Editor State Tests

- hierarchy navigation
- selection depth transitions
- breadcrumb correctness
- stash creation and restore semantics

### 2. Mutable-Level Integration Tests

- block/chunk derive-new behavior updates only intended references
- dirty-region propagation matches the edited hierarchy layer
- undo/redo restores composition correctly

### 3. Play-Test Round-Trip Tests

- toggle into editor preserves stash state
- toggle back resumes play-test from editor cursor
- `Start From Beginning` ignores stash and uses canonical spawn
- invalid resume positions fall back safely

## Risks

### Risk: Flat Mode Creep

If block, chunk, and pattern drift back into sibling top-level modes, the editor will fight the
level model and become harder to use.

### Risk: Over-Building Chrome

If too many panes are made persistent in the MVP, the tool will feel like a generic editor shell
instead of a game-native overlay.

### Risk: Toggle Hitching

If the default edit <-> play-test path rebuilds too much state, the editor will feel clumsy even
if technically correct.

## Recommended Implementation Direction

Build the MVP around four ideas:

1. world-first block placement
2. hierarchy-first drill-down editing
3. focused overlay panes for block/chunk composition
4. stashed low-hitch play-test toggling with a separate fresh-start option

This gives the engine an editor that matches the real structure of Sonic level data, respects the
new runtime/session boundaries, and stays narrow enough to implement cleanly as a first prototype.
