# Level Editor Design

## Overview

In-engine level editor for OpenGGF. A new `GameMode.LEVEL_EDITOR` mode that reuses the existing rendering pipeline and level data structures. Supports all three games (S1, S2, S3K). Keyboard-driven. In-memory only (no save/load for MVP).

## Entry & Exit

- `Shift+Tab` from gameplay or level select enters editor mode with the current level loaded.
- `Shift+Tab` exits back to the previous mode.
- If no level is loaded, the editor prompts for game/zone/act selection before activating.

## Coordinate Spaces

Two independent coordinate spaces:

| Space | Scaling | Contents |
|-------|---------|----------|
| **Game-space** | Scaled by game pixel multiplier (e.g., 3x native 320×224) | Level tilemap, grid overlay lines, cursor highlight |
| **Screen-space** | 1:1 pixels, DPI-scaled only | Side panel, tooltip bar, all editor chrome/text |

The game viewport is inset to make room for the side panel (not overlaid). At 1920px wide with a 200px sidebar, the game viewport gets 1720px.

## Navigation

- Camera is free-flying (decoupled from player sprite). Arrow keys pan the viewport.
- Camera movement snaps to chunk grid (16px) in chunk mode, block grid (128px) in block mode.
- `Tab` toggles input focus between the level grid and the side panel.

## Grid Overlay

Rendered in game-space, aligned with the tilemap:

- **Fine lines** (1px, subtle gray/white, ~30% opacity) at every 16px — chunk boundaries.
- **Thick lines** (2px, brighter, ~50% opacity) at every 128px — block boundaries.
- **Cursor cell** — semi-transparent color fill + bright outline on the current grid cell. Different color tint when panel is focused vs grid is focused.

## Side Panel

Right side of screen, ~200px wide, rendered in screen-space at native resolution.

### Layout

- **Header** — current mode label ("Chunks" / "Blocks"), zone name, item count.
- **Grid area** — scrollable grid of all available chunks or blocks for the current zone, rendered with actual tile graphics at 1:1 pixel size (DPI-scaled). Selected item has highlight matching cursor style.
- **Info footer** — selected item index, collision solidity flags (for chunks).

### Controls (when panel focused)

| Key | Action |
|-----|--------|
| Arrow keys | Move selection through grid |
| Page Up/Down | Scroll by full page |
| Enter | Confirm selection, return focus to level grid |
| `B` | Switch to Block mode |
| `C` | Switch to Chunk mode |

## Placement

### Controls (when level grid focused)

| Key | Action |
|-----|--------|
| Arrow keys | Move cursor across level grid |
| Space / Enter | Stamp selected chunk/block at cursor position |
| Delete / Backspace | Clear cell (set to index 0) |
| `E` | Eyedropper — pick chunk/block under cursor, select it in panel |
| `B` | Switch to Block mode |
| `C` | Switch to Chunk mode |
| `Tab` | Switch focus to side panel |

### Edit Modes

**Block mode** — cursor snaps to 128px grid. Placement swaps the block index in the level `Map`. Side panel shows all `Block` definitions.

**Chunk mode** — cursor snaps to 16px grid. Placement modifies the `ChunkDesc` within the block at that position. Side panel shows all `Chunk` definitions.

### Copy-on-Write for Chunk Edits

Blocks are shared — multiple map cells can reference the same block definition. When editing a chunk within a block:

1. Clone the block definition.
2. Modify the target `ChunkDesc` in the clone.
3. Update the map cell to reference the new block.
4. Other map cells using the original block remain unchanged.

## Tooltip Bar

Bottom of screen, full width, single line. Rendered in screen-space using the master title screen font.

Semi-transparent dark background strip. Keys rendered in a brighter/highlighted color, descriptions in a softer color.

Content adapts to current context:

| Context | Tooltip |
|---------|---------|
| Level grid focused | `Arrows:Move  Space:Place  Del:Clear  E:Eyedrop  B/C:Block/Chunk  Tab:Panel` |
| Side panel focused | `Arrows:Browse  PgUp/Dn:Scroll  Enter:Select  B/C:Block/Chunk  Tab:Grid` |
| No level loaded | `Select a game and zone to begin editing` |

## Architecture

```
Engine (GameMode.LEVEL_EDITOR)
 │
 ├── LevelEditorManager (singleton coordinator)
 │    ├── EditorInputHandler (keyboard dispatch, focus state)
 │    ├── EditorCamera (free-pan, grid-snap)
 │    ├── LevelGridCursor (position, edit mode, placement/clear/eyedropper)
 │    └── ChunkPanelState (selection index, scroll offset, block/chunk mode)
 │
 ├── Rendering
 │    ├── [Game-space] Existing tilemap renderer (level display)
 │    ├── [Game-space] GridOverlayRenderer (chunk/block boundary lines)
 │    ├── [Game-space] CursorRenderer (highlight fill + outline)
 │    ├── [Screen-space] ChunkPanelRenderer (side panel with tile previews)
 │    └── [Screen-space] TooltipBarRenderer (contextual key hints)
 │
 └── Edit Operations
      ├── PlaceBlock — swap map cell block index
      ├── PlaceChunk — copy-on-write block clone, modify ChunkDesc
      ├── ClearCell — set index to 0
      └── Eyedropper — read cell → update panel selection
```

### Key Design Decisions

- **No new rendering infrastructure.** Reuses `GraphicsManager`, `PatternAtlas`, existing GL command system.
- **LevelEditorManager** is the single coordinator. Called from `Engine.update()` and `Engine.draw()` when mode is `LEVEL_EDITOR`.
- **Screen-space UI** uses its own orthographic projection independent of the game's pixel scaling, ensuring crisp text and previews at any game zoom level.
- **In-memory only.** Edits modify the live `Level` data structures. No persistence for MVP.
