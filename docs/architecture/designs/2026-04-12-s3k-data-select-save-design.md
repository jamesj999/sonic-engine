# Sonic 3K Data Select And Save System Design

## Summary

Add a dedicated Data Select screen for Sonic 3 & Knuckles game mode, backed by JSON save slots with an integrity hash. The save format does not attempt SRAM parity. Instead, gameplay preserves original save-call semantics by routing save requests through a runtime `SaveSessionContext` that either writes the active slot or no-ops for unsaved sessions.

The design is shared enough to support Sonic 2 and Sonic 1 later with the same screen model, but S3K is the primary parity target. Clear-save restart behavior follows original-style restricted destination lists rather than exposing unrestricted level select. Level Select remains higher priority than Data Select and behaves as an unsaved run using the existing config-based character selection path.

## Goals

- Support a Sonic 3K Data Select screen in `S3K` game mode.
- Support the original S3K slot count: `8` save slots plus `No Save` and `Delete`.
- Bind all save writes for a run to the selected slot.
- Preserve original-style save trigger semantics in S3K.
- Support the original Data Select abilities:
  - start a new game without saving
  - start a new game in an empty slot
  - load an existing save slot
  - choose the player/team for new runs
  - show saved progress details on the screen
  - support clear-save restricted restart behavior
- Reuse the same framework later for S2 and S1.
- Keep gameplay code simple by making save requests unconditional and allowing unsaved sessions to no-op.

## Non-Goals

- SRAM byte layout parity
- binary or BSON storage
- unrestricted "load any act" behavior for clear saves
- replacing existing debug Level Select behavior
- introducing screenshots for S1 slot previews in the first version

## Product Rules

### Data Select And Level Select Priority

- `LEVEL_SELECT` and `DATA_SELECT` cannot be active together.
- Level Select always takes precedence over Data Select.
- If the user starts in Level Select or enters Level Select via debug flow:
  - Data Select is bypassed completely.
  - the session behaves as if `No Save` had been selected
  - gameplay continues to use `MAIN_CHARACTER_CODE` and `SIDEKICK_CHARACTER_CODE` exactly as it does today
- Data Select is only used for the normal game-start flow where save slot selection matters.

### S3K Data Select Behavior

- The screen layout follows the original S3K model:
  - `No Save`
  - `8` save slots
  - `Delete`
- New runs support these built-in team choices:
  - `Sonic alone`
  - `Sonic & Tails`
  - `Knuckles`
- Saved slots show at least:
  - current zone/progress marker
  - selected team
  - lives
  - emerald state
  - clear-state information
- Clear saves use restricted restart lists derived from the original disassembly.
- S3K Knuckles restrictions must be preserved separately from Sonic/Tails restrictions.

### S2 And S1 Follow-Up Behavior

- S2 and S1 reuse the same Data Select framework and slot storage model.
- They each define their own restricted clear-save restart tables.
- S2 preview art uses scaled existing level-select icons.
- S1 preview uses only the zone name for now.
- Future S1 preview support may use bundled `8-bit` PNG images without changing the save model.

### Extra Character Combos

- Add `EXTRA_DATA_SELECT_PLAYER_COMBO` to `config.json`.
- Format:
  - semicolon-separated combos
  - comma-separated character codes within each combo
  - first entry is the main character
  - remaining entries are sidekicks
- Example:

```json
"EXTRA_DATA_SELECT_PLAYER_COMBO": "sonic,knuckles;sonic,tails,tails;knuckles,tails"
```

- Data Select appends these combos to the built-in team list for new runs and no-save starts.
- Rendering uses layered copies of existing player sprites.
- Level Select ignores this option and continues using the existing config keys.

## Architecture

### New Game Mode

Add `GameMode.DATA_SELECT`.

This is a first-class scene like `TITLE_SCREEN` or `LEVEL_SELECT`, not a variation of debug level select.

### New Provider Interface

Add a `DataSelectProvider` interface parallel to `TitleScreenProvider` and `LevelSelectProvider`.

Responsibilities:

- initialize
- update
- draw
- report exit/result state
- expose selected launch action

`GameModule` gains `getDataSelectProvider()` with a default no-op implementation.

### Shared Framework With Per-Game Profiles

Use one shared framework implementation with per-game adapters rather than separate menu implementations per game.

Suggested split:

- `DataSelectProvider`
- `AbstractDataSelectProvider` or equivalent shared implementation
- `DataSelectGameProfile`
- `S3kDataSelectProfile`
- `S2DataSelectProfile`
- `S1DataSelectProfile`

`DataSelectGameProfile` owns game-specific rules:

- slot count
- built-in selectable teams
- extra-combo support
- slot summary extraction
- slot validation rules
- clear-save restart destinations
- character-specific clear restrictions
- preview/icon rendering metadata
- save trigger mapping

## Runtime Session Model

### SaveSessionContext

Introduce `SaveSessionContext` as runtime-owned session state attached to `WorldSession`.

It should contain at least:

- host game id
- active slot id, or none
- selected team/combo
- initial zone and act
- clear-restart selection state when relevant

There is no `saveEnabled` flag exposed to gameplay code. The existence of an active slot is enough.

### Save Call Behavior

Gameplay code always issues save requests at the appropriate original call points.

Gameplay code does not ask whether saving is enabled.

`SaveSessionContext` behavior:

- active slot bound:
  - capture a snapshot and write the slot
- no active slot bound:
  - no-op

This keeps parity-driven gameplay code simple and lets unsaved sessions behave naturally.

### WorldSession Changes

`WorldSession` currently only carries the selected `GameModule`.

Extend it to carry:

- the selected module
- the `SaveSessionContext`
- launch-time team selection
- launch-time zone/act choice when Data Select starts from a clear save

This replaces the idea that config keys are the source of truth for the active run.

### Character Spawning Source Of Truth

Gameplay startup should prefer session launch data:

- Data Select launches:
  - use the session-selected combo
- Level Select launches:
  - use `MAIN_CHARACTER_CODE` and `SIDEKICK_CHARACTER_CODE`

This preserves current debug behavior while allowing slot-specific teams.

## Save Storage

### File Layout

Use per-game folders under a root `saves` directory in the project/runtime root:

- `saves/s3k/slot1.json` ... `slot8.json`
- `saves/s2/slot1.json` ... `slot8.json`
- `saves/s1/slot1.json` ... `slot8.json`

No manifest file is required initially. Data Select scans the expected folder directly.

### File Format

Each slot file is a JSON envelope:

```json
{
  "version": 1,
  "game": "s3k",
  "slot": 1,
  "payload": {
  },
  "hash": "..."
}
```

Rules:

- `payload` is game-specific JSON data generated by that game's snapshot provider
- `hash` is stored alongside the payload
- the hash covers the canonical serialized payload, not the entire outer file

Jackson is already present in the repo and should be reused for serialization.

### Integrity And Corruption Handling

At Data Select scan time:

- if the hash fails but the payload parses and validates:
  - log a warning
  - keep the file
  - still load the slot
- if the file is unreadable, malformed, structurally invalid, or declares the wrong game:
  - rename the file to `slotN.json.corrupt`
  - log why it was quarantined
  - treat the slot as empty/fresh

This intentionally uses soft integrity checks and hard structural validation.

## Save Pipeline

### SaveManager

`SaveManager` owns disk and serialization concerns:

- scan slot folders
- read JSON
- verify hash
- quarantine malformed files
- write updated slot files
- delete or reset slots

It should not contain game-specific save semantics.

### Snapshot Providers

Do not make `SaveSessionContext` a giant game-aware reader.

Instead, add a game-specific snapshot seam:

- `SaveSnapshotProvider.capture(RuntimeSaveContext ctx): SavePayload`

One implementation per host game:

- `S3kSaveSnapshotProvider`
- `S2SaveSnapshotProvider`
- `S1SaveSnapshotProvider`

These providers read from existing runtime state and services:

- `GameServices.gameState()`
- `LevelManager`
- `WorldSession` / `SaveSessionContext`
- any game-specific managers or providers needed for parity fields

Flow:

1. gameplay calls `SaveSessionContext.requestSave(reason)`
2. if no active slot, return immediately
3. `SaveSessionContext` asks the current game's `SaveSnapshotProvider` to capture
4. `SaveManager` writes the envelope and hash to the bound slot path

Benefits:

- gameplay remains simple
- `SaveSessionContext` remains generic
- game-specific capture logic stays modular
- tests can validate each snapshot provider independently

## S3K-Specific Behavior

### Title Flow

Normal S3K title flow changes:

- `1 PLAYER` opens Data Select
- Data Select then decides:
  - no-save run
  - new slot
  - existing slot
  - clear-save restricted restart
  - delete

Competition remains outside this feature.

### Save Slot Count And Structure

The original disassembly shows:

- `No Save`
- `8` save slots
- `Delete`

The engine should keep that structure.

### Clear-Save Restrictions

S3K clear saves must not become unrestricted level select.

Instead:

- implement original-style restricted restart destinations
- use separate restriction tables for:
  - Sonic/Tails
  - Knuckles

The game profile owns these tables and any mapping between displayed restart choice and real `zone/act`.

### S3K Save Call Sites

S3K gameplay should issue save requests at ROM-equivalent conceptual call points.

The exact implementation can be phased, but the design target is:

- when Data Select initializes a new saved run
- when Data Select loads an existing save
- when Data Select commits a clear-save restart choice
- when the original game would persist progress during stage progression or completion

Gameplay code still only calls `requestSave(...)`; slot/no-save behavior stays outside gameplay.

### S3K Payload Fields

The S3K payload should contain at least:

- current zone/act
- selected player/team option
- special stage progress
- emerald state
- lives
- continues if represented
- clear/completion state
- any restricted clear-restart metadata needed by Data Select rendering and choice rules

This is a semantic snapshot, not an SRAM dump.

## S2 And S1 Adaptation

### Shared Model

S2 and S1 should plug into the same framework:

- same `DATA_SELECT` scene model
- same slot-folder structure
- same `SaveSessionContext`
- same snapshot-provider seam
- same corruption and hash behavior

### S2 Rules

- use restricted clear-save restart tables derived from S2 progression
- preview art uses scaled existing level-select icons
- save calls should be retrofitted to ROM-equivalent conceptual save points

### S1 Rules

- use restricted clear-save restart tables derived from S1 progression
- preview uses zone name only for now
- future PNG previews can replace the renderer input later
- save calls should be retrofitted to ROM-equivalent conceptual save points

## Integration Changes

### GameModule

Add:

- `getDataSelectProvider()`
- `getSaveSnapshotProvider()`
- optionally `getDataSelectProfile()` if not folded into the provider construction

### GameLoop

Add support for `GameMode.DATA_SELECT`:

- initialize from title flow
- update
- draw
- exit handling to gameplay
- priority interaction with Level Select

### Engine

The main-player and sidekick spawn path should resolve the active team from `WorldSession` when the session came from Data Select. Existing config-based spawn logic remains the fallback path for Level Select and legacy startup flows.

## Testing

Add tests for:

- slot scan across `saves/<game>`
- malformed or wrong-game files renamed to `.corrupt`
- hash mismatch warning without slot rejection
- no-save sessions making save calls without writes
- active-slot sessions always writing the bound slot
- Level Select bypassing Data Select and preserving existing config-based team selection
- S3K clear-save restricted restart choices
- S3K Knuckles-specific restart restrictions
- S1 and S2 restricted restart tables
- extra combo parsing from `EXTRA_DATA_SELECT_PLAYER_COMBO`
- session-selected combo driving spawned main player and sidekicks

## Documentation Updates

Update `docs/S3K_KNOWN_DISCREPANCIES.md` to record:

- save format uses JSON plus hash rather than SRAM layout
- malformed files are quarantined to `.corrupt`
- hash mismatch is warning-only
- `SaveSessionContext` no-ops save requests when no slot is active
- snapshot-provider architecture replaces direct SRAM-style writes

Do not add a general donated-feature note to `KNOWN_DISCREPANCIES.md` for this design.

## Implementation Order

1. Add `DATA_SELECT` mode and provider interfaces
2. Extend `WorldSession` with `SaveSessionContext`
3. Implement `SaveManager` and file handling
4. Implement S3K snapshot/profile/provider
5. Route S3K title `1 PLAYER` to Data Select
6. Bind gameplay spawn logic to session-selected combo
7. Wire initial S3K save call points
8. Add tests for slot scan, quarantine, no-save no-op, and active-slot writes
9. Add S2 and S1 profiles and snapshot providers
10. Document S3K-specific discrepancies

## Open Choices Already Resolved

- storage is per game, not cross-game shared
- save files live under `saves/<game>/slotN.json`
- malformed files are quarantined to `.corrupt`
- hash mismatch is warning-only
- Level Select takes precedence and behaves like `No Save`
- Level Select continues to use existing config-based character selection
- S1 preview is zone-name-only for now
- clear-save restarts use restricted lists, not unrestricted act selection
