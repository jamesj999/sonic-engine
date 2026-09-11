#version 410 core

/*
 * Parallax Background Shader
 *
 * Applies per-scanline horizontal scrolling to the already-rendered background.
 *
 * The FBO contains the background rendered wider than the screen (e.g., 512 pixels)
 * centered on the expected scroll midpoint. This shader samples the FBO with
 * per-scanline offsets based on hScroll values.
 */

// Background rendered to FBO (RGBA, wider than screen)
uniform sampler2D BackgroundTexture;

// 1D texture containing per-scanline scroll values (224 entries)
uniform sampler1D HScrollTexture;
uniform sampler1D VScrollTexture;
uniform sampler1D VScrollColumnTexture;

// Screen dimensions (actual viewport pixels)
uniform float ScreenHeight;
uniform float ScreenWidth;
uniform float ActiveDisplayWidth;

// Background texture dimensions (e.g., 512x224)
uniform float BGTextureWidth;
uniform float BGTextureHeight;

// Scroll midpoint - the hScroll value that corresponds to FBO center
uniform float ScrollMidpoint;

// Extra buffer pixels on each side of the FBO
uniform float ExtraBuffer;

// Vertical scroll offset (sub-chunk alignment)
uniform float VScroll;

// Viewport offset (for letterboxing/pillarboxing support)
uniform float ViewportOffsetX;
uniform float ViewportOffsetY;
uniform vec3 BackdropColor;
uniform float FillTransparentWithBackdrop;

// FBO allocation width (actual GPU texture size, may be larger than BGTextureWidth)
uniform float FBOAllocationWidth;

// When 1, skip HScroll sampling (per-line scroll already applied in tile pass)
uniform int NoHScroll;
uniform int UsePerLineVScroll;
uniform int UsePerColumnVScroll;

// Shimmer distortion uniforms
uniform int FrameCounter;            // For shimmer animation
uniform int ShimmerStyle;            // 0 = none, 1 = S1 shimmer (larger waves for BG)
uniform float WaterlineScreenY;      // Screen Y where water starts (0 = top, game coords)

out vec4 FragColor;

void main()
{
    // Pixel-center aligned viewport coordinates.
    float viewportX = gl_FragCoord.x - ViewportOffsetX - 0.5;
    float viewportY = gl_FragCoord.y - ViewportOffsetY - 0.5;

    if (viewportX < 0.0 || viewportY < 0.0 || viewportX >= ScreenWidth || viewportY >= ScreenHeight) {
        discard;
    }

    // Map physical viewport pixels to logical game pixels and snap to integer
    // game pixels so per-scanline sampling stays stable under integer upscaling.
    float activeDisplayWidth = ActiveDisplayWidth > 0.0 ? ActiveDisplayWidth : ScreenWidth;
    float gameX = floor((viewportX * activeDisplayWidth) / ScreenWidth);
    float gameY = floor(((ScreenHeight - 1.0 - viewportY) * 224.0) / ScreenHeight);  // Y=0 at top

    // Get the scroll value for this scanline
    float hScrollThis = 0.0;
    float vScrollThis = 0.0;
    float scanline = clamp(gameY, 0.0, 223.0);  // Clamp to valid scanline range
    float scanlineTexCoord = (scanline + 0.5) / 224.0;
    if (NoHScroll == 0) {
        hScrollThis = texture(HScrollTexture, scanlineTexCoord).r * 32767.0;
    }
    if (UsePerLineVScroll != 0) {
        vScrollThis = texture(VScrollTexture, scanlineTexCoord).r * 32767.0;
    }
    if (UsePerColumnVScroll != 0) {
        float vScrollColumnCount = max(1.0, ceil(activeDisplayWidth / 16.0));
        float column = clamp(floor(gameX / 16.0), 0.0, vScrollColumnCount - 1.0);
        float columnTexCoord = (column + 0.5) / vScrollColumnCount;
        vScrollThis += texture(VScrollColumnTexture, columnTexCoord).r * 32767.0;
    }

    // hScroll contains signed scroll values from the zone handler.
    // Convert back to world-space sample coordinate.
    // Apply underwater shimmer distortion to background layer
    // BG uses broader, slower waves than FG for a parallax-like distortion effect
    float bgShimmerDistortion = 0.0;
    if (ShimmerStyle > 0 && gameY >= WaterlineScreenY) {
        float scanlinesBelow = gameY - WaterlineScreenY;
        if (ShimmerStyle == 1) {
            // S1-style BG shimmer: broader wavelength (~200px) and higher amplitude
            // than FG, creating a layered distortion parallax effect
            float angle = (scanlinesBelow * 0.031) + (float(FrameCounter) * 0.025);
            float rawDistortion = sin(angle) * 3.0;
            bgShimmerDistortion = floor(rawDistortion + 0.5);
        }
    }

    float worldX = gameX - hScrollThis + bgShimmerDistortion;

    // Apply vertical scroll offset (sub-chunk alignment)
    float fboY = gameY + VScroll + vScrollThis;

    // Background tile pass may render from a shifted world origin.
    // ScrollMidpoint/ExtraBuffer define that origin for intro paths.
    float fboWorldOffsetX = -ScrollMidpoint - ExtraBuffer;

    // Wrap X within the background period rendered into the FBO.
    float fboX = mod(worldX - fboWorldOffsetX, BGTextureWidth);
    if (fboX < 0.0) fboX += BGTextureWidth;

    // Clamp Y to valid range
    fboY = clamp(fboY, 0.0, BGTextureHeight - 1.0);

    // Sample FBO at texel centers to avoid edge artifacts.
    // Use FBOAllocationWidth for UV mapping (actual texture size) while BGTextureWidth
    // was used above for wrapping (the rendered region may be smaller than the allocation)
    float uvWidth = FBOAllocationWidth > 0.0 ? FBOAllocationWidth : BGTextureWidth;
    float fboU = (fboX + 0.5) / uvWidth;
    fboU = clamp(fboU, 0.5 / uvWidth, 1.0 - 0.5 / uvWidth);  // Stay within texture
    float fboV = 1.0 - ((fboY + 0.5) / BGTextureHeight);  // Add 0.5 for pixel center
    fboV = clamp(fboV, 0.5 / BGTextureHeight, 1.0 - 0.5 / BGTextureHeight);  // Stay within texture

    vec4 color = texture(BackgroundTexture, vec2(fboU, fboV));

    // Alpha test
    if (color.a < 0.1) {
        if (FillTransparentWithBackdrop > 0.5) {
            FragColor = vec4(BackdropColor, 1.0);
            return;
        }
        discard;
    }

    FragColor = color;
}
