# Design: Debug Overlay Pixel Font Variant

## Problem

The project now has a second pixel font sheet at `src/main/resources/pixel-font-ns.png` with the shadow removed. The debug overlay should use that sheet, but the shared pixel font renderer currently hardcodes `pixel-font.png`.

## Solution

Introduce a typed `PixelFontVariant` enum that maps logical font choices to resource paths. Keep the default renderer behavior on the existing `pixel-font.png`, and allow callers to opt into the no-shadow sheet with `PIXEL_FONT_NO_SHADOW`.

## API Changes

### New enum: `PixelFontVariant`

**Package:** `com.openggf.graphics`

**Variants:**

- `PIXEL_FONT`
  Uses `pixel-font.png`
- `PIXEL_FONT_NO_SHADOW`
  Uses `pixel-font-ns.png`

The enum owns the resource path lookup so callers do not pass raw filenames.

### Updated `PixelFontTextRenderer`

- The no-arg constructor continues to use `PixelFontVariant.PIXEL_FONT`
- Add an overload that accepts a `PixelFontVariant`
- The renderer initializes `PixelFont` using the selected variant's resource path

This preserves existing behavior for editor text and any other current users.

## Debug Overlay Change

`DebugRenderer` should explicitly construct its `performanceTextRenderer` with `PixelFontVariant.PIXEL_FONT_NO_SHADOW`.

No other caller changes are required for this task.

## Testing

Add unit coverage in `TestPixelFontTextRenderer` for:

- Default constructor selecting `pixel-font.png`
- Variant constructor selecting `pixel-font-ns.png`

The test should verify which resource path is passed to `PixelFont.init(...)`, not just that initialization occurs.

## Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/openggf/graphics/PixelFontVariant.java` | New enum for typed font selection |
| `src/main/java/com/openggf/graphics/PixelFontTextRenderer.java` | Add variant-aware construction while preserving default behavior |
| `src/main/java/com/openggf/debug/DebugRenderer.java` | Opt debug overlay into `PIXEL_FONT_NO_SHADOW` |
| `src/test/java/com/openggf/graphics/TestPixelFontTextRenderer.java` | Add regression tests for font variant selection |

## Non-Goals

- No change to glyph metrics, spacing, or draw order
- No global switch of all overlay/editor text to the no-shadow sheet
- No runtime configuration surface for selecting the font variant
