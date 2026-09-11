# S1/S2 Donated Data Select Preview Presentation Design

## Summary

This spec covers the remaining donated S3K Data Select presentation work for Sonic 1 and Sonic 2:

- replace the current direct emerald palette donation with a host-aware retinting strategy
- render Sonic 1 emeralds in a six-point ring instead of leaving a visible seventh-slot gap
- replace Sonic 2's donated level-select icon path with runtime-generated cached screenshots, matching the Sonic 1 runtime preview model

The donated S3K Data Select renderer remains the owner of rendering. Host games provide:

- preview screenshots
- host-specific emerald color presentation data
- host-specific emerald layout metadata

This keeps the donated frontend architecture centered on the existing S3K renderer and selected-slot asset seams rather than introducing parallel screen implementations.

## Goals

- Keep donated S3K Data Select visually coherent for S1 and S2 hosts.
- Preserve native S3K emerald shading quality while reflecting host emerald identity.
- Remove the visible S1 seven-emerald layout gap by using a proper six-emerald arrangement.
- Make S2 donated selected-slot previews use runtime-generated screenshots only.
- Keep all runtime-generated images in writable save-owned cache locations, not shipped assets.

## Non-Goals

- Generalizing all screenshot generation into a new cross-game framework in this step.
- Changing native S3K save-card art, mappings, or object rendering ownership.
- Exposing screenshot generation UI or progress feedback to the user.
- Adding user-configurable screenshot override coordinates in `config.json`.

## Current Problems

### Emerald Color Donation

The current host emerald color adaptation copies or derives raw host palette data directly into the S3K save-card palette contract. This produces poor results for S1 and S2 because:

- the S3K save card expects its own highlight and shadow structure
- raw host palette slots do not map cleanly onto S3K's save-card emerald shading
- S1 currently has only six emeralds, but the adaptation path still targets a seven-emerald contract

The result is muddy or near-black emerald presentation on donated cards.

### Sonic 1 Emerald Layout

The donated save-card layout uses the native seven-emerald arrangement. Sonic 1 only has six emeralds, so the card shows a clear missing-space gap rather than a balanced ring.

### Sonic 2 Preview Source

Sonic 2 donated selected-slot previews still use the ROM level-select icon path via `LevelSelectDataLoader`. The user wants S2 to behave like S1 and use runtime-generated screenshots instead.

## Recommended Approach

Use a host-aware presentation layer on top of the donated S3K renderer:

1. `HostEmeraldPresentation`
   - derive a representative host color per emerald
   - retint a native S3K emerald shading ramp toward the host hue/saturation
   - emit save-card-ready emerald palette bytes

2. `HostEmeraldLayoutProfile`
   - return host-specific emerald orbit positions for the save card
   - S1 uses six positions in a hexagon-style ring
   - S2 and S3K keep the existing seven-emerald arrangement

3. `S2DataSelectImageCacheManager` stack
   - mirror the existing S1 runtime screenshot pipeline
   - generate all 11 donated S2 restart-destination screenshots at runtime
   - replace the donated `LevelSelectDataLoader` preview path entirely

This gives S1 and S2 better presentation while keeping rendering ownership and control flow inside the current donated S3K Data Select architecture.

## Architecture

### 1. Host Emerald Presentation

Add a focused presenter layer responsible for producing emerald palette bytes for donated save-card rendering.

Responsibilities:

- identify the current host game
- extract a representative color for each host emerald
- load the native S3K emerald ramp used by the save-card overlay
- retint the native ramp toward the host hue and saturation
- return palette bytes already shaped for the existing S3K save-card overlay contract

This replaces the current strategy of treating host palette data as if it were directly interchangeable with S3K save-card palette slots.

### 2. Host Emerald Layout Profile

Add a small host-layout seam so the renderer can ask for emerald positions by host game.

Responsibilities:

- return the existing seven-position orbit for S2 and S3K
- return a six-position orbit for S1
- ensure the S1 positions form a balanced ring around the portrait rather than leaving a missing seventh slot

The renderer still draws the same emerald mappings. Only the orbit positions change.

### 3. S2 Runtime Screenshot Cache

Introduce an S2 runtime preview cache stack parallel to the S1 one:

- `S2DataSelectImageCacheManager`
- `S2DataSelectImageGenerator`
- `S2SelectedSlotPreviewLoader`
- `S2DataSelectImageManifest`

Responsibilities:

- own a writable runtime cache at `saves/image-cache/s2/`
- generate all 11 donated S2 restart-destination screenshots at runtime
- validate cache contents against engine version, generator format version, ROM SHA-256, and override config
- load cached PNGs into the existing donated selected-slot preview seam

The S3K donated presentation stays the consumer of these preview assets.

## Emerald Color Strategy

### Core Rule

Do not copy raw host palette slots into the S3K save-card palette contract.

Instead:

1. extract one representative host color per emerald
2. convert that color into a hue/saturation target
3. apply that target to the native S3K emerald highlight/shadow ramp
4. preserve brightness ordering from the native S3K ramp

This keeps the save-card emeralds readable because the native S3K highlight and shadow structure stays intact while the overall emerald identity shifts toward the host color.

### Representative Host Color Selection

For each host emerald:

- select the most visually representative non-background color available from host data
- prefer the most saturated vibrant color over a dark or neutral shade
- use a deterministic extraction path so the same ROM always produces the same retint result

For S1:

- extract six host emerald colors
- the seventh save-card emerald slot remains unused by S1 layout and should keep a neutral or native fallback value

For S2:

- extract seven host emerald colors

### Retinting Rule

The retint implementation should:

- keep the S3K ramp's relative brightness ordering
- move hue toward the host emerald hue
- move saturation toward the host emerald saturation
- clamp safely into valid Genesis color space

The goal is "host color identity with native S3K readability", not ROM-pure palette transfer.

## Emerald Layout Strategy

### S1

S1 uses six emeralds. The donated card should position them in a balanced six-point ring around the character portrait.

Required behavior:

- no visible empty seventh orbit slot
- roughly hexagonal arrangement
- preserve overall visual scale and surrounding feel from the existing save card

### S2 and S3K

S2 and S3K continue to use the existing seven-emerald arrangement unchanged.

## S2 Runtime Screenshot Strategy

### Trigger

S2 screenshot generation only runs when:

- host game is S2
- donor is S3K

Normal S2 startup must not trigger image generation.

### Generation Window

Generation starts during the hidden bootstrap path between master title and S2 gameplay startup, using the same donated-only warmup pattern already established for S1.

Generation is asynchronous from the orchestration point of view, but:

- capture must run on the render thread via queued render tasks
- file work and manifest work may run off-thread

If donated Data Select is reached before generation completes, the frontend waits for completion before opening selected-slot previews.

### Zones Included

Generate previews only for the 11 donated restart destinations:

- EHZ
- CPZ
- ARZ
- CNZ
- HTZ
- MCZ
- OOZ
- MTZ
- SCZ
- WFZ
- DEZ

This matches the donated S2 restart set exactly.

### Capture Framing

Default capture rule matches S1:

- camera left edge starts at the zone spawn X
- target Y is centered from the spawn position

An override map exists in code for future tuning, but starts empty for S2.

Override semantics:

- override X/Y represent screen center
- generator converts that into the actual camera-left capture target

### Render Content

Preview capture should continue using the hidden preview render mode:

- no HUD
- no player sprite
- no rings
- stage objects may remain visible

### Cache Location

Store generated files at:

- `saves/image-cache/s2/manifest.json`
- `saves/image-cache/s2/<zone>.png`

### Manifest Contract

Persist at least:

- `engineVersion` from `AppVersion.get()`
- `generatorFormatVersion`
- `romSha256`
- `generatedAt`
- per-zone file mapping

### Config

Add a dedicated S2 override flag mirroring the S1 testing hook:

- `CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE`

## Donated Preview Loading

Once the S2 cache is valid:

- `S3kDataSelectPresentation` loads PNG-backed S2 selected-slot previews through `S2SelectedSlotPreviewLoader`
- the current donated `LevelSelectDataLoader` icon path is removed from the S2 host-preview branch

If cache generation fails for a given session:

- donated S2 falls back to text-only `ZONE n` labels for selected-slot preview text
- do not fall back to ROM level-select icons

This keeps the donated preview model consistent with the S1 runtime-generated direction.

## Error Handling

### Emerald Colors

If host emerald extraction or retinting fails:

- return no host emerald palette bytes
- let the renderer use native S3K emerald palette data

This fails safe without corrupting the save-card presentation.

### Emerald Layout

If host layout metadata is missing:

- fall back to the existing seven-emerald arrangement

### S2 Screenshot Cache

Invalidate and regenerate the entire cache if:

- manifest is missing
- any PNG is missing
- any PNG is corrupt or wrong-sized
- engine version mismatches
- generator format version mismatches
- ROM SHA-256 mismatches
- `CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE` is true

Writes must remain atomic:

- temp PNG first
- move into final location
- manifest written last after the full set succeeds

## Testing Strategy

### Unit Tests

Add tests for:

- S1 host emerald adaptation returns six host-derived targets and does not depend on raw slot copying
- S2 host emerald adaptation returns seven host-derived targets
- retinting preserves highlight-to-shadow ordering
- failure paths fall back cleanly
- S1 layout resolves to six balanced positions
- S2/S3K layout resolves to the existing seven positions
- S2 cache validation for version mismatch, SHA mismatch, override flag, missing file, corrupt file
- S2 capture target resolution with default spawn-left-edge behavior
- S2 override map precedence when overrides are later added

### Integration Tests

Add tests for:

- generation starts only for `host=S2` with `donor=S3K`
- S2 donated Data Select awaits in-flight screenshot generation
- PNG-backed S2 selected-slot previews are used instead of `LevelSelectDataLoader`
- S2 failure falls back to text-only `ZONE n` preview text

## Implementation Notes

- Keep S1 and S2 runtime screenshot ownership in their respective host modules.
- Do not move screenshot generation to build time.
- Do not surface screenshot generation UI.
- Keep override maps code-owned and deterministic for now.
- Bump generator format versions whenever framing rules, retint logic inputs, or preview layout assumptions change in a way that invalidates old cache output.

## Expected Outcome

After this work:

- S1 donated emeralds look like S1 emeralds without losing S3K shading readability
- S1 emeralds form a balanced six-point ring
- S2 donated selected-slot previews are runtime-generated screenshots, not ROM level-select icons
- donated preview rendering stays within the existing S3K Data Select frontend architecture
