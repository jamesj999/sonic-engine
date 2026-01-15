#version 110

// VDP Shadow/Highlight mode shader for Sonic 2 Special Stage
//
// The Genesis VDP shadow/highlight mode is enabled during the special stage ($8C08).
// Shadow sprites use multiplicative blending to darken whatever is behind them.
//
// The shadow art patterns contain non-zero pixels where darkening should occur.
// Index 0 = transparent (no effect on background)
// Any non-zero index = shadow (darken background by 50%)

uniform sampler2D IndexedColorTexture;

// Shadow darkening factor (Genesis VDP halves RGB values)
const float SHADOW_FACTOR = 0.5;

void main()
{
    // Get the color index from the indexed texture
    float index = texture2D(IndexedColorTexture, gl_TexCoord[0].st).r * 255.0;

    // Index 0 is transparent - discard to leave background unchanged
    if (index < 0.5) {
        discard;
    }

    // Any non-zero pixel outputs the shadow darkening factor
    // With glBlendFunc(GL_ZERO, GL_SRC_COLOR): result = dest * 0.5 = darkened background
    gl_FragColor = vec4(SHADOW_FACTOR, SHADOW_FACTOR, SHADOW_FACTOR, 1.0);
}
