# S1 Donated Data Select Image Generation Design

## Goal

Generate Sonic 1 zone preview images at runtime for the donated S3K Data Select presentation, without shipping ROM-derived assets in the repository or build artifacts.

## Context

Current donated Data Select behavior differs by game:

- S3K uses its native ROM-backed Data Select zone images.
- S2 reuses its level select images and scales them up for the selected slot preview.
- S1 currently falls back to text-only zone labels because it has no equivalent ROM-provided image set.

The new S1 path should generate a small image cache on first donated boot, persist it under user data, and reuse it until invalidated.

## Requirements

- Generate S1 donated Data Select preview images at runtime, not at build time.
- Only generate previews when Sonic 1 is the loaded host game and the donor presentation is S3K.
- Store generated files under `saves/image-cache/s1/`.
- Generate all 7 S1 zone previews as one batch.
- Use the zone spawn position by default.
- Support a code-owned per-zone X/Y override map where override coordinates represent the center of the captured screen.
- Keep the override map empty in the initial implementation so all zones use spawn fallback.
- Generate a manifest containing cache metadata.
- Invalidate the cache when the engine version changes, the generator format version changes, the ROM changes, or the override config forces regeneration.
- Do not expose a generation UI to the user.
- Start generation during the master-title to Sonic 1 bootstrap path when donation is relevant.
- Never trigger generation for normal non-donated Sonic 1 loads.
- If the user reaches donated S1 Data Select before generation finishes, wait for completion.
- Capture raw level imagery only: no HUD, no title card, no debug overlays.
- Make the settle frame count configurable so testing can use `0`.
- Fall back to the existing text-only S1 preview if generation fails.

## Recommended Approach

Use a hybrid async pipeline:

- Run cache orchestration, manifest validation, hashing, downscaling, PNG encoding, and disk writes on a worker thread.
- Run hidden zone load, frame stepping, camera placement, and framebuffer capture on the render/runtime thread through an explicit capture job interface.
- Start the process during Sonic 1 bootstrap after the master title handoff.
- Await the in-flight job only when donated Data Select actually needs the previews.

This approach keeps the user-facing flow silent while respecting the OpenGL/runtime ownership boundaries already present in the engine.

## Alternatives Considered

### 1. Fully synchronous startup generation

Generate all images before gameplay or title flow continues.

Pros:

- Simplest control flow.
- No in-flight wait state to coordinate later.

Cons:

- Always blocks first boot even if the user never opens donated Data Select.
- Needlessly increases visible startup latency.

### 2. Fully isolated offscreen renderer

Build a dedicated offscreen preview renderer or FBO path for S1 previews.

Pros:

- Strongest separation from main runtime flow.
- Long-term reusable for other generated image features.

Cons:

- Significantly more engineering cost.
- High parity risk because it duplicates level bring-up and render assumptions.

### 3. Hybrid async capture pipeline

Recommended.

Pros:

- Silent to the user.
- Avoids shipping ROM-derived assets.
- Fits existing runtime and screenshot capture seams.
- Keeps OpenGL work on the correct thread.

Cons:

- Requires an explicit capture job boundary between worker-thread orchestration and render-thread capture execution.

## Architecture

### 1. `S1DataSelectImageCacheManager`

Owns cache lifecycle and is the public entry point for the feature.

Responsibilities:

- Resolve the cache root `saves/image-cache/s1/`.
- Load and validate `manifest.json`.
- Compare manifest metadata against the current environment.
- Gate the feature to `host=S1` and `donor=S3K`.
- Start generation once.
- Expose `ensureGenerationStarted()`.
- Expose `awaitGenerationIfRunning()`.
- Expose preview loading for donated Data Select.
- Surface a boolean or result object indicating whether image-backed previews are available.

The manager should be idempotent. Multiple callers should share one in-flight generation task rather than starting duplicate work.

Ownership:

- The manager should live under the Sonic 1 donated Data Select feature area, not as a generic engine-wide image cache.
- It should be resolved through existing donated Data Select and host-profile seams so the feature is naturally unavailable for normal S1 gameplay and for non-S3K donation modes.

### 2. `S1DataSelectImageGenerator`

Owns batch generation for the 7 Sonic 1 zones.

Responsibilities:

- Define the set of zones to generate.
- Resolve the capture target per zone.
- Use spawn position as default.
- Consult an empty-for-now code-owned override map first.
- Apply a configurable settle frame count before capture.
- Request capture jobs from the render/runtime thread.
- Downscale the captured framebuffer image to the target preview size.
- Write all PNGs to disk via temp files.
- Write the manifest only after the full batch succeeds.

### 3. `S1DataSelectCaptureSession`

Represents the hidden preview-generation runtime session.

Responsibilities:

- Load S1 zones in an isolated, non-user-visible path.
- Disable overlays that do not belong in the preview.
- Position camera around the resolved target point.
- Step the configured number of settle frames.
- Capture the framebuffer with the existing screenshot infrastructure.
- Return raw `RgbaImage` data to the worker-thread generator pipeline.

This should be conceptually separate from the player’s live gameplay session. The capture session exists only to generate cache images.

### 4. `S1DataSelectPreviewAssetLoader`

Adapts cached PNGs into the donated S3K Data Select renderer seam.

Responsibilities:

- Load cached PNGs from disk.
- Convert them into patterns, palettes, and selected-slot icon frame data usable by the S3K donated Data Select asset path.
- Provide S1-specific selected-slot imagery through the same `S3kDataSelectAssetSource` hooks already used by S2.

This preserves a single donated Data Select renderer path instead of creating an S1-specific renderer fork.

Integration preference:

- Extend the current donated S3K asset loading path in `S3kDataSelectPresentation` rather than introducing a second donated Data Select renderer or a parallel startup-only subsystem.
- Treat S1 the same way S2 is currently treated: the host profile decides what preview should be shown, and the donated S3K asset source provides the host-specific selected-slot asset when available.

## Capture Target Resolution

Capture position resolution should follow this order:

1. Look up the zone in a code-owned override map.
2. If an override exists, use its `x` and `y` as the center of the captured screen.
3. Otherwise, resolve the normal zone spawn position and use that as the center of the captured screen.

The initial override map is intentionally empty.

This data should live in code, not in user config and not in the manifest. If framing changes later, bump the generator format version so caches regenerate automatically.

Example shape:

```java
private static final Map<Integer, PreviewCapturePoint> CAPTURE_OVERRIDES = Map.of();

private record PreviewCapturePoint(int centreX, int centreY) {
}
```

## Cache Storage

Cache root:

- `saves/image-cache/s1/manifest.json`
- `saves/image-cache/s1/ghz.png`
- `saves/image-cache/s1/mz.png`
- `saves/image-cache/s1/syz.png`
- `saves/image-cache/s1/lz.png`
- `saves/image-cache/s1/slz.png`
- `saves/image-cache/s1/sbz.png`
- `saves/image-cache/s1/fz.png`

The exact filenames can be zone-code based or zone-id based, but they should be deterministic and human-readable.

PNG is the preferred on-disk format because it is easy to inspect, delete, and regenerate, and it does not require shipping any ROM-derived binary cache in the repository.

## Manifest Schema

The manifest should include enough information to decide whether the entire cache is reusable.

Recommended fields:

```json
{
  "engineVersion": "0.6.prerelease",
  "generatorFormatVersion": 1,
  "romSha256": "abcdef...",
  "generatedAt": "2026-04-14T21:00:00Z",
  "settleFrames": 8,
  "zones": [
    {
      "zoneCode": "ghz",
      "zoneId": 0,
      "file": "ghz.png"
    }
  ]
}
```

Notes:

- `engineVersion` should come from the same authoritative engine version source already used elsewhere in the project.
- `generatorFormatVersion` is separate from engine version so capture rules can change even if the engine version string does not.
- `romSha256` should be computed from the active Sonic 1 ROM.
- `settleFrames` should be recorded for diagnostic clarity; changing its effective default should be accompanied by either an engine version change, generator format bump, or forced override during testing.

## Invalidation Rules

Treat the cache as invalid and regenerate all 7 previews when any of the following is true:

- `manifest.json` is missing.
- Any expected PNG is missing.
- Any expected PNG cannot be decoded.
- Engine version differs.
- Generator format version differs.
- ROM SHA-256 differs.
- `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE` is true.

Invalidation is whole-cache only. Do not attempt partial repair in the first version.

## Runtime Flow

### Master Title to S1 Bootstrap

When the user selects Sonic 1 from the master title flow:

1. Engine resets bootstrap state and starts Sonic 1 initialization.
2. If and only if Sonic 1 is the host game, cross-game donation is active, and the donor presentation resolves to S3K, call `ensureGenerationStarted()`.
3. Manifest validation runs immediately on a worker thread.
4. If regeneration is needed, the worker thread requests hidden capture jobs for each S1 zone.

This work should begin after the selected game has enough runtime/ROM state available to resolve zone metadata and capture images safely.

If Sonic 1 is loaded normally without S3K donation, this path must do nothing.

### Donated S1 Data Select Entry

When the engine is about to enter donated Data Select for an S1 host:

1. Ask the cache manager whether generation is still in flight.
2. If yes, await completion.
3. If generation succeeded, load PNG-backed S1 preview assets.
4. If generation failed, continue with the current text-only S1 preview path.

The user should not see a progress UI. A short wait at Data Select entry is acceptable.

## Capture Semantics

The generated image should reflect the raw level scene:

- No HUD.
- No title card.
- No debug overlays.
- No transient generation UI.

The capture session should also avoid obvious transient artifacts where possible by stepping a configurable number of settle frames before capture.

Recommended config:

- `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES`

Behavior:

- Integer frame count.
- Defaults to a small positive number chosen during implementation.
- `0` is explicitly valid and should capture immediately after the camera/scene are positioned.

This value is primarily for tuning and testing image quality without code changes.

## Config Additions

Add the following configuration entries:

- `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE`
  - type: boolean
  - default: `false`
  - behavior: force regeneration on next eligible bootstrap

- `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES`
  - type: integer
  - default: `8`
  - behavior: number of hidden capture-session frames to step before screenshot capture

These belong in normal configuration plumbing so they can be set via `config.json`.

## Integration with Existing Data Select Flow

The existing donated Data Select path already supports host-specific selected-slot imagery through `DataSelectHostProfile` and `S3kDataSelectAssetSource`.

For S1:

- `S1DataSelectProfile` should stop being permanently text-only when cached image previews are available.
- The donated S3K Data Select asset source should add an S1-specific preview loader alongside the existing S2 path.
- The selected-slot preview should use PNG-backed image assets loaded from `saves/image-cache/s1/`.
- The generation start hook should be reached through existing host/donor route resolution, not through a broad “S1 startup always warms cache” rule.

If the cache manager reports that S1 previews are unavailable, `S1DataSelectProfile` should continue to fall back to `TEXT_ONLY`.

## Threading Model

The design must treat OpenGL and runtime stepping as render-thread-owned work.

Worker thread:

- manifest read/write
- ROM hashing
- temp-file management
- PNG encode/write
- downscaling
- success/failure state transitions

Render/runtime thread:

- hidden capture-session creation
- zone load
- camera placement
- frame stepping
- framebuffer capture

Do not perform live GL capture from an arbitrary worker thread.

## Failure Handling

Failure should not block normal gameplay or make Data Select unusable.

Rules:

- If generation fails, log the failure and preserve text-only S1 previews.
- If any single zone capture or write fails, fail the whole batch.
- Write PNGs to temp files first, then atomically move them into place.
- Write `manifest.json` only after all PNGs have been successfully written.
- If generation fails mid-batch, leave no manifest behind for that batch.

This keeps the cache state simple and avoids partial-validity rules.

## Testing Strategy

### Unit Tests

- Manifest validation success case.
- Manifest invalid when engine version changes.
- Manifest invalid when generator format version changes.
- Manifest invalid when ROM SHA changes.
- Manifest invalid when any expected PNG is missing.
- Override flag forces regeneration.
- Capture target resolution uses override when present.
- Capture target resolution falls back to spawn when no override exists.
- Settle frame config accepts `0`.
- S1 preview asset loader decodes cached PNGs and exposes selected-slot assets.

### Integration Tests

- S1 bootstrap with donation enabled starts generation when cache is missing.
- Donated S1 Data Select waits for in-flight generation.
- Successful generation enables image-backed previews for donated S1 Data Select.
- Generation failure keeps text-only fallback alive.
- Valid manifest skips regeneration.

### Manual Verification

- Delete `saves/image-cache/s1/`, boot Sonic 1 with donation enabled, and verify that the cache is created silently.
- Enter donated S1 Data Select and verify that each zone shows an image preview instead of text-only fallback.
- Set settle frames to `0` and inspect whether previews capture any transient artifacts.
- Change the override flag to force regeneration and confirm the cache rewrites.

## Open Implementation Notes

- The exact preview dimensions should match the selected-slot preview shape expected by the donated S3K Data Select renderer.
- If the renderer path prefers pattern/palette decomposition rather than full-image textured quads, the PNG loader must convert decoded images into the expected engine-native structures.
- The generator should use the same zone ordering as `S1DataSelectProfile.CLEAR_RESTARTS` to avoid mismatch between save payload zone IDs and preview file mapping.

## Recommendation Summary

Implement a hidden, runtime-generated S1 preview cache under `saves/image-cache/s1/`, keyed by engine version, generator format version, ROM SHA-256, and the existing override control. Start generation during the master-title to S1 bootstrap path, keep the work user-invisible, and await completion only if donated Data Select needs the images before the batch finishes. Reuse the existing donated S3K Data Select asset seam so S1 gains image-backed previews without creating a separate renderer.
