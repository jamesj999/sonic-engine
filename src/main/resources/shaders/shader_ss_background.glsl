#version 110

/*
 * Special Stage Background Shader
 *
 * Renders the special stage parallax background with:
 * - Per-scanline horizontal scrolling (emulates VDP H-scroll table)
 * - H32 mode viewport clipping (256 pixels centered on 320-pixel screen)
 * - Vertical scrolling for rise/drop animations
 *
 * The background is pre-rendered to an FBO as a 256x256 tilemap.
 * This shader samples with per-line scroll offsets and clips to the H32 viewport.
 */

// Background tilemap rendered to FBO (256x256, already palette-resolved RGBA)
uniform sampler2D BackgroundTexture;

// 1D texture containing per-scanline horizontal scroll values (224 entries)
// Each value is the background X scroll offset for that scanline
uniform sampler1D HScrollTexture;

// Screen dimensions (actual window pixels)
uniform float ScreenWidth;
uniform float ScreenHeight;

// Background texture dimensions (256x256 for special stage)
uniform float BGTextureWidth;
uniform float BGTextureHeight;

// Vertical scroll offset (for rise/drop parallax effect)
uniform float VScrollBG;

// Viewport offset for letterboxing (accounts for window position)
uniform float ViewportOffsetX;
uniform float ViewportOffsetY;

// H32 mode parameters
const float H32_WIDTH = 256.0;
const float SCREEN_GAME_WIDTH = 320.0;
const float SCREEN_GAME_HEIGHT = 224.0;
const float H32_OFFSET = (SCREEN_GAME_WIDTH - H32_WIDTH) / 2.0;  // 32 pixels

void main()
{
    // Get fragment position in window coordinates, adjusted for viewport offset
    vec2 windowPos = gl_FragCoord.xy - vec2(ViewportOffsetX, ViewportOffsetY);

    // Normalize to 0..1 range based on actual viewport size
    float normX = windowPos.x / ScreenWidth;
    float normY = windowPos.y / ScreenHeight;

    // Map to game coordinates (0..320 for X, 0..224 for Y)
    // OpenGL Y=0 is bottom, Genesis Y=0 is top, so flip Y
    float gameX = normX * SCREEN_GAME_WIDTH;
    float gameY = (1.0 - normY) * SCREEN_GAME_HEIGHT;

    // ========================================
    // H32 VIEWPORT CLIPPING
    // ========================================
    // Discard fragments outside the 256-pixel H32 viewport
    // The H32 viewport is centered on the 320-pixel screen (32 pixels on each side)
    if (gameX < H32_OFFSET || gameX >= H32_OFFSET + H32_WIDTH) {
        discard;
    }

    // Convert to H32-local X coordinate (0..255)
    float localX = gameX - H32_OFFSET;

    // ========================================
    // PER-SCANLINE HORIZONTAL SCROLL
    // ========================================
    // Sample the H-scroll value for this scanline
    float scanline = clamp(gameY, 0.0, SCREEN_GAME_HEIGHT - 1.0);
    float scanlineTexCoord = (scanline + 0.5) / SCREEN_GAME_HEIGHT;

    // H-scroll texture contains signed 16-bit values encoded as normalized floats
    // The value represents pixels to scroll (positive = scroll right, content moves left)
    float hScrollValue = texture1D(HScrollTexture, scanlineTexCoord).r * 32767.0;

    // Apply horizontal scroll to get the source X position in the background
    // Negative scroll = background moves right (content scrolls left into view)
    float bgX = localX - hScrollValue;

    // Wrap horizontally within the background texture (256 pixels wide, seamless)
    bgX = mod(bgX, BGTextureWidth);
    if (bgX < 0.0) bgX += BGTextureWidth;

    // ========================================
    // VERTICAL SCROLL
    // ========================================
    // Apply vertical scroll offset for rise/drop parallax
    float bgY = gameY + VScrollBG;

    // Wrap vertically within the background texture
    bgY = mod(bgY, BGTextureHeight);
    if (bgY < 0.0) bgY += BGTextureHeight;

    // ========================================
    // TEXTURE SAMPLING
    // ========================================
    // Sample at texel centers to avoid edge-wrap artifacts (GL_REPEAT + exact 0/1 coords).
    // Note: The FBO is rendered with OpenGL's Y-up convention, so row 0 of the
    // background (top in Genesis coords) ends up at high FBO Y (high V).
    // We need to flip V so that bgY=0 (top) samples from high V.
    float sampleX = bgX + 0.5;
    if (sampleX >= BGTextureWidth) sampleX -= BGTextureWidth;
    float sampleY = bgY + 0.5;
    if (sampleY >= BGTextureHeight) sampleY -= BGTextureHeight;
    float texU = sampleX / BGTextureWidth;
    float texV = 1.0 - (sampleY / BGTextureHeight);

    // Sample the background texture
    vec4 color = texture2D(BackgroundTexture, vec2(texU, texV));

    // Alpha test - discard transparent pixels
    if (color.a < 0.1) {
        discard;
    }

    gl_FragColor = color;
}
