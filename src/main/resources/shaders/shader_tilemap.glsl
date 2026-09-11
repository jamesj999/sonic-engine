#version 410 core

uniform sampler2D TilemapTexture;    // RGBA8 tile descriptors
uniform sampler1D PatternLookup;     // RGBA8: R=tileX, G=tileY
uniform sampler2D AtlasTexture;      // Indexed color atlas (GL_RED)
uniform sampler2D Palette;           // Combined palette texture
uniform sampler2D UnderwaterPalette; // Underwater palette
uniform float TotalPaletteLines;

uniform float TilemapWidth;          // In tiles
uniform float TilemapHeight;         // In tiles
uniform float TilemapRingBaseX;      // Physical X origin for logical BG column zero
uniform float TilemapRingBaseY;      // Physical Y origin for logical BG row zero
uniform float AtlasWidth;            // In pixels
uniform float AtlasHeight;           // In pixels
uniform float LookupSize;            // Pattern lookup width
uniform float WindowWidth;           // Target width in pixels (FBO)
uniform float WindowHeight;          // Target height in pixels (FBO)
uniform float ViewportWidth;         // Actual GL viewport width
uniform float ViewportHeight;        // Actual GL viewport height
uniform float ViewportOffsetX;       // GL viewport X offset
uniform float ViewportOffsetY;       // GL viewport Y offset
uniform float WorldOffsetX;          // World X at left edge
uniform float WorldOffsetY;          // World Y at top edge
uniform int WrapY;                   // 1 to wrap vertically, 0 to clamp
uniform int PriorityPass;            // -1 = all, 0 = low, 1 = high
uniform int MaskOutput;              // 1 = output white mask, 0 = output actual color
uniform int UseUnderwaterPalette;
uniform float WaterlineScreenY;
uniform sampler1D HScrollTexture;    // Per-scanline BG scroll (R32F, 224 entries)
uniform int PerLineScroll;           // 1 = per-scanline HScroll, 0 = uniform WorldOffsetX
uniform sampler1D VScrollColumnTexture; // Per-column VScroll (R32F, ceil(displayWidth / 16) entries)
uniform int PerColumnVScroll;        // 1 = apply per-column VScroll to worldY
uniform float VScrollColumnCount;    // Texel count of VScrollColumnTexture; WindowWidth is the FBO width for BG passes and must not size the lookup
uniform float ScreenHeight;          // Visible scanline count (224.0)
uniform float PerLineScrollSampleYOffsetPx;
uniform float VDPWrapWidth;          // VDP nametable width in tiles (64.0), 0 = use TilemapWidth
uniform float VDPWrapHeight;         // VDP nametable height in tiles, 0 = disabled
uniform float NametableBase;         // Starting tilemap column for VDP-style wrapping
uniform float UpperBandWrapHeightPx; // If >0, rows above this BG-local Y wrap within UpperBandWrapWidthTiles
uniform float UpperBandWrapWidthTiles;
uniform int FrameCounter;            // For shimmer animation
uniform int ShimmerStyle;            // 0 = none, 1 = S1 integer-snapped shimmer

out vec4 FragColor;

// Sonic 1 LZ/SBZ3 foreground underwater scroll table (Lz_Scroll_Data).
// Reference: s1disasm/_inc/DeformLayers.asm (Deform_LZ).
int sampleS1LzForegroundShimmer(int index8)
{
    int idx = index8 & 0xFF;
    bool negativeBand = (idx >= 128 && idx < 144);
    bool positiveBand = (idx < 16) || (idx >= 160 && idx < 176);

    if (!negativeBand && !positiveBand) {
        return 0;
    }

    int local = idx & 0x0F;
    int magnitude = 0;

    if (local < 2) {
        magnitude = 1;
    } else if (local < 4) {
        magnitude = 2;
    } else if (local < 8) {
        magnitude = 3;
    } else if (local < 10) {
        magnitude = 2;
    } else if (local < 12) {
        magnitude = 1;
    }

    return negativeBand ? -magnitude : magnitude;
}

void main()
{
    // Pixel-center aligned position in viewport space (0..ViewportWidth/Height),
    // origin at bottom-left.
    float viewportX = gl_FragCoord.x - ViewportOffsetX - 0.5;
    float viewportY = gl_FragCoord.y - ViewportOffsetY - 0.5;

    if (viewportX < 0.0 || viewportY < 0.0 || viewportX >= ViewportWidth || viewportY >= ViewportHeight) {
        discard;
    }

    // Convert physical viewport pixels to logical game pixels, snapping to whole
    // game pixels so scanline/layer sampling stays stable under integer upscaling.
    float pixelX = floor((viewportX * WindowWidth) / ViewportWidth);
    float pixelYFromTop = floor(((ViewportHeight - 1.0 - viewportY) * WindowHeight) / ViewportHeight);

    // Apply underwater shimmer distortion to horizontal position
    float shimmerDistortion = 0.0;
    if (UseUnderwaterPalette == 1 && pixelYFromTop >= WaterlineScreenY && ShimmerStyle > 0) {
        if (ShimmerStyle == 1) {
            // ROM-accurate S1 foreground shimmer:
            // Deform_LZ writes FG underwater HScroll as:
            //   fgScroll = -screenposx + Lz_Scroll_Data[(v_lz_deform + screenposy + line) & 0xFF]
            // with v_lz_deform incrementing by +0x80 each frame.
            // On 68000, move.b from a word address reads the high byte first (big-endian),
            // so the sampled phase advances by +1 every 2 frames (not 0/128 flip).
            int deformPhase = (FrameCounter >> 1) & 0xFF;
            int worldLine = int(floor(WorldOffsetY + pixelYFromTop));
            int tableIndex = (worldLine + deformPhase) & 0xFF;
            int tableOffset = sampleS1LzForegroundShimmer(tableIndex);

            // Convert HScroll delta to world-space sample offset.
            shimmerDistortion = -float(tableOffset);
        }
    }

    float worldX;
    if (PerLineScroll == 1) {
        // Per-scanline horizontal scroll: each scanline has its own BG offset.
        // Matches VDP behavior where HScroll RAM provides per-line offsets.
        float scanline = clamp(pixelYFromTop - PerLineScrollSampleYOffsetPx, 0.0, ScreenHeight - 1.0);
        float scanlineTexCoord = (scanline + 0.5) / ScreenHeight;
        float hScrollThis = texture(HScrollTexture, scanlineTexCoord).r * 32767.0;
        worldX = pixelX - hScrollThis;
    } else {
        worldX = WorldOffsetX + pixelX + shimmerDistortion;
    }
    float columnVScroll = 0.0;
    if (PerColumnVScroll == 1) {
        float vScrollColumnCount = VScrollColumnCount > 0.0
            ? VScrollColumnCount
            : max(1.0, ceil(WindowWidth / 16.0));
        float column = clamp(floor(pixelX / 16.0), 0.0, vScrollColumnCount - 1.0);
        float columnTexCoord = (column + 0.5) / vScrollColumnCount;
        columnVScroll = texture(VScrollColumnTexture, columnTexCoord).r * 32767.0;
    }
    float worldY = WorldOffsetY + pixelYFromTop + columnVScroll;

    float tileXf = floor(worldX / 8.0);
    float tileYf = floor(worldY / 8.0);
    float localBandY = worldY;
    if (WrapY == 1) {
        float wrapHeightPx = TilemapHeight * 8.0;
        localBandY = mod(localBandY, wrapHeightPx);
        if (localBandY < 0.0) localBandY += wrapHeightPx;
    }

    if (UpperBandWrapWidthTiles > 0.0 && UpperBandWrapHeightPx > 0.0 && localBandY < UpperBandWrapHeightPx) {
        tileXf = mod(tileXf, UpperBandWrapWidthTiles);
        if (tileXf < 0.0) tileXf += UpperBandWrapWidthTiles;
    } else if (VDPWrapWidth > 0.0) {
        // VDP nametable simulation for AIZ ocean-to-beach transition.
        // Two modes based on whether the camera has started revealing beach tiles:
        //
        // Transition (NametableBase > 0) with tileXf >= 0:
        //   Read tiles directly from the level layout. The layout already has
        //   ocean at low positions and beach further right, so per-line HScroll
        //   naturally produces the correct mix without any ring-buffer boundary
        //   artifacts (no staircase).
        //
        // Ocean phase (NametableBase == 0) or negative tileXf:
        //   Wrap within VDP width so the 64-tile ocean pattern repeats.
        //   Deep bands with negative worldX always see ocean here, which is
        //   the visually correct result for underwater scroll layers.
        if (NametableBase > 0.0 && tileXf >= 0.0) {
            tileXf = mod(tileXf, TilemapWidth);
            if (tileXf < 0.0) tileXf += TilemapWidth;
        } else {
            float pos = mod(tileXf, VDPWrapWidth);
            if (pos < 0.0) pos += VDPWrapWidth;
            tileXf = pos;
        }
    } else {
        tileXf = mod(tileXf, TilemapWidth);
        if (tileXf < 0.0) tileXf += TilemapWidth;
    }

    if (WrapY == 1) {
        tileYf = mod(tileYf, TilemapHeight);
        if (tileYf < 0.0) tileYf += TilemapHeight;
    } else {
        if (tileYf < 0.0 || tileYf >= TilemapHeight) {
            discard;
        }
    }

    // VDP vertical nametable wrap - maps tall tilemaps back into valid data range.
    // On real hardware the BG nametable is 64x32 cells; tiles beyond row 31 wrap
    // back to row 0.  Zones whose BG data fits within 32 rows (e.g. HTZ) set
    // VDPWrapHeight = 32 so the earthquake scroll (tileY ~98) reads valid tiles.
    // Zones with taller BG data (e.g. MCZ, 85+ rows) leave VDPWrapHeight = 0.
    if (VDPWrapHeight > 0.0) {
        tileYf = mod(tileYf, VDPWrapHeight);
        if (tileYf < 0.0) tileYf += VDPWrapHeight;
    }

    float physicalTileX = mod(tileXf + TilemapRingBaseX, TilemapWidth);
    if (physicalTileX < 0.0) physicalTileX += TilemapWidth;
    float physicalTileY = mod(tileYf + TilemapRingBaseY, TilemapHeight);
    if (physicalTileY < 0.0) physicalTileY += TilemapHeight;
    vec2 tileUv = vec2((physicalTileX + 0.5) / TilemapWidth,
            (physicalTileY + 0.5) / TilemapHeight);
    vec4 desc = texture(TilemapTexture, tileUv);

    if (desc.a < 0.5) {
        discard;
    }

    float r = desc.r * 255.0;
    float g = desc.g * 255.0;

    float patternHigh = mod(g, 8.0);
    float patternIndex = r + patternHigh * 256.0;
    float paletteIndex = mod(floor(g / 8.0), 4.0);
    float hFlip = mod(floor(g / 32.0), 2.0);
    float vFlipBit = mod(floor(g / 64.0), 2.0);
    float priority = mod(floor(g / 128.0), 2.0);

    if (PriorityPass >= 0 && priority != float(PriorityPass)) {
        discard;
    }

    float localX = mod(worldX, 8.0);
    if (localX < 0.0) localX += 8.0;
    float localY = mod(worldY, 8.0);
    if (localY < 0.0) localY += 8.0;
    localX = floor(localX);
    localY = floor(localY);

    if (hFlip > 0.5) {
        localX = 7.0 - localX;
    }
    // Note: VFlip=false means flip (matching PatternDesc behavior)
    if (vFlipBit > 0.5) {
        localY = 7.0 - localY;
    }

    float lookupU = (patternIndex + 0.5) / LookupSize;
    vec4 lookup = texture(PatternLookup, lookupU);
    float atlasTileX = lookup.r * 255.0;
    float atlasTileY = lookup.g * 255.0;

    float atlasPixelX = atlasTileX * 8.0 + localX + 0.5;
    float atlasPixelY = atlasTileY * 8.0 + localY + 0.5;

    vec2 atlasUv = vec2(atlasPixelX / AtlasWidth, atlasPixelY / AtlasHeight);
    float index = texture(AtlasTexture, atlasUv).r * 255.0;

    if (index < 0.1) {
        discard;
    }

    float paletteX = (index + 0.5) / 16.0;
    float paletteY = (paletteIndex + 0.5) / TotalPaletteLines;
    vec4 color;
    if (UseUnderwaterPalette == 1 && pixelYFromTop >= WaterlineScreenY) {
        color = texture(UnderwaterPalette, vec2(paletteX, paletteY));
    } else {
        color = texture(Palette, vec2(paletteX, paletteY));
    }

    // Preserve the palette line in the priority buffer. Some ROM scenes use
    // palette priority to place a sprite between two high-priority tile groups.
    if (MaskOutput == 1) {
        FragColor = vec4(1.0, paletteIndex / 3.0, 0.0, 1.0);
    } else {
        FragColor = color;
    }
}
