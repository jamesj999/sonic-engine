# Sonic 1 and Sonic 2 Emerald Save Design

**Goal**

Make donated Sonic 1 and Sonic 2 saves use explicit emerald identity data as the only emerald persistence format, restore that data into gameplay on load, and display individual emerald progress on the donated S3K save card using colors adapted from the source game's emerald palette.

**Scope**

- Replace `emeraldCount` in Sonic 1 and Sonic 2 save payloads with `chaosEmeralds`.
- Restore Sonic 1 and Sonic 2 emerald progress from `chaosEmeralds` when gameplay launches from Data Select.
- Render individual S1 and S2 emerald progress on the donated S3K save card.
- Use host-game emerald palette data for donated emerald rendering.
- Do not keep backward compatibility for old count-only S1/S2 saves.

**Non-Goals**

- No compatibility shim for unreleased count-only Sonic 1 saves.
- No compatibility shim for unreleased count-only Sonic 2 saves.
- No change to native S3K save payload shape.
- No host-specific emerald layout rearrangement in this task.

## Current Problem

Sonic 1 and Sonic 2 currently write only `emeraldCount` into donated save payloads. That count is visible in simple text summaries, but the gameplay restore path reads only `chaosEmeralds` and `superEmeralds`. As a result, S1 and S2 emerald progress can be written to disk but not restored into runtime state.

The donated S3K save screen also already has a per-slot emerald sprite layer, but S1 and S2 do not populate it because their payloads lack emerald identities and the donated asset path only resolves S3K emerald rendering assets today.

## Decision

Use `chaosEmeralds: List<Integer>` as the single source of truth for Sonic 1 and Sonic 2 emerald save state.

Consequences:

- Sonic 1 and Sonic 2 save capture must export collected emerald indices from live `GameStateManager`.
- Sonic 1 and Sonic 2 gameplay restore must read `chaosEmeralds` from payload.
- Donated save-card presentation must derive the emerald icon layer from `chaosEmeralds`.
- Donated emerald rendering must use the source game's palette data, not S3K's.
- `emeraldCount` must be removed from Sonic 1 and Sonic 2 payload generation and their donated save presentation paths.

## Data Model

### Sonic 1 / Sonic 2 Save Payload

The canonical donated Sonic 1 / Sonic 2 payload after this change is:

```json
{
  "zone": 4,
  "act": 0,
  "mainCharacter": "sonic",
  "sidekicks": ["tails"],
  "lives": 5,
  "chaosEmeralds": [0, 2, 5],
  "clear": false,
  "progressCode": 5,
  "clearState": 0
}
```

Notes:

- `chaosEmeralds` contains the collected emerald indices from `GameStateManager.getCollectedChaosEmeraldIndices()`.
- Sonic 1 and Sonic 2 do not write `superEmeralds`.
- `emeraldCount` is removed entirely.

## Runtime Restore

`Engine.restoreRuntimeFromDataSelectPayload(...)` already restores runtime emerald progress via `GameStateManager.restoreSaveProgress(...)`, but it only reads list fields. After this design:

- Sonic 1 and Sonic 2 payloads will provide `chaosEmeralds`, so no game-specific fallback path is needed.
- `continues` remains absent for Sonic 1 and Sonic 2 and should continue defaulting to the runtime fallback value.

This keeps the restore path uniform across games: gameplay restores from identity lists, not aggregate counts.

## Save Card Presentation

The donated S3K save screen already has a sprite-layer API for per-slot emerald icons via `S3kSaveScreenObjectState.SlotVisualState.emeraldMappingFrames()`.

After this change:

- Donated Sonic 1 and Sonic 2 slot states will populate `emeraldMappingFrames()` from `chaosEmeralds`.
- The donated asset source will provide host-adapted emerald palette bytes.
- The renderer will draw the existing S3K save-card emerald mappings using host-game colors rather than S3K chaos emerald palette colors.

### Visual Rule

- Each collected S1 or S2 emerald renders as an individual icon on the save card.
- Colors must come from host-game emerald palette data.
- The icon order should follow the stored emerald index order after normalizing to ascending index order.

### Palette Rule

- Host emerald palettes are not assumed to be slot-compatible with the S3K save-card palette.
- Host colors must be adapted into the S3K save-card palette contract before rendering.
- The save card keeps S3K geometry and mapping frames in this task.

### Deferred Layout Follow-Up

This task intentionally does not change emerald positioning geometry on the donated save card.

Follow-up work will be needed to make host-specific arrangements correct:

- S1 uses a 6-emerald layout and should not reuse the current evenly spaced 7-slot circle.
- S2 uses a 7-emerald layout.
- Native S3K behavior should remain unchanged.

## Asset Strategy

Reuse the existing donated S3K asset-source architecture instead of introducing a separate ad hoc renderer path.

Required additions:

- Load Sonic 1 and Sonic 2 emerald palette data from the donor ROM.
- Adapt those colors into the S3K save-card overlay format.
- Expose host-specific emerald palette bytes through the donated asset source.
- Keep native S3K emerald rendering unchanged.

The renderer should branch on the asset source capabilities, not on hardcoded game checks scattered through render code.

## Validation Rules

Sonic 1 and Sonic 2 donated payload validation should treat `chaosEmeralds` as the emerald field of record.

Validation requirements:

- `chaosEmeralds` must be a list of integers.
- Each index must be within the host game's emerald range.
- Duplicate indices are invalid and should be normalized away during save capture rather than tolerated downstream.

## Testing

### Required Red-Green Coverage

1. `S1SaveSnapshotProvider` and `S2SaveSnapshotProvider` write `chaosEmeralds` from live runtime state and do not write `emeraldCount`.
2. `Engine.restoreRuntimeFromDataSelectPayload(...)` restores Sonic 1 and Sonic 2 emerald progress from `chaosEmeralds`.
3. Donated Sonic 1 and Sonic 2 save-card presentation produces individual emerald sprite frames from `chaosEmeralds`.
4. Donated Sonic 1 and Sonic 2 emerald rendering uses the correct host-game emerald palette source through the S3K save-card palette contract.

### Regression Protection

- Existing special-stage save trigger tests remain green.
- Existing donated S1 and S2 slot preview tests remain green.
- Existing S3K save-card emerald rendering tests remain green.

## Files Expected To Change

- `src/main/java/com/openggf/game/sonic1/dataselect/S1SaveSnapshotProvider.java`
- `src/main/java/com/openggf/game/sonic2/dataselect/S2SaveSnapshotProvider.java`
- `src/main/java/com/openggf/Engine.java`
- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectProfile.java`
- `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectProfile.java`
- `src/main/java/com/openggf/game/dataselect/SimpleDataSelectManager.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldPaletteBuilder.java`
- Associated S1/S2/S3K save and presentation tests

## Risks

- The biggest risk is mixing S1/S2/S3K emerald frame and palette assumptions inside the renderer. The asset boundary must stay explicit.
- Removing `emeraldCount` will require touching any text-only summary path that still expects it.
- S1 has six emeralds while S2 has seven, so display arrangement must remain explicitly deferred and not partially patched in this task.
- If host emerald colors do not map cleanly onto the S3K save-card highlight/shadow slots, the adaptation layer may still need refinement later.

## Success Criteria

- Starting from a donated Sonic 1 or Sonic 2 save restores the correct emerald progress into gameplay.
- Completing a Sonic 1 or Sonic 2 special stage and saving produces a payload with `chaosEmeralds`, not `emeraldCount`.
- The donated S3K save card shows one emerald icon per collected Sonic 1 or Sonic 2 emerald, using source-game colors adapted to the S3K save-card contract.
- No existing S3K save-card behavior regresses.
