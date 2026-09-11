#version 410 core

uniform sampler2D Palette;
uniform sampler2D IndexedColorTexture;
uniform float PaletteLine;
uniform float TotalPaletteLines;
uniform int GhostMode;
uniform float GhostAlpha;

in vec2 v_texCoord;
in float v_paletteLine;

out vec4 FragColor;

void main()
{
    // Get the color index from the indexed texture
    float index = texture(IndexedColorTexture, v_texCoord).r * 255.0;

    // Mega Drive VDP Rule: Index 0 is transparent.
    // We discard the fragment so it doesn't write to the frame buffer (or depth buffer),
    // allowing the backdrop or previous layers to show through.
    if (index < 0.1) {
        discard;
    }

    // Resolve palette line (uniform or per-vertex attribute)
    float paletteLine = PaletteLine;
    if (paletteLine < 0.0) {
        paletteLine = v_paletteLine;
    }

    // Map the index to palette coordinates (16 colors per line)
    float paletteX = (index + 0.5) / 16.0;
    float paletteY = (paletteLine + 0.5) / TotalPaletteLines;

    // Sample the palette texture to get the actual color
    vec4 indexedColor = texture(Palette, vec2(paletteX, paletteY));

    if (GhostMode == 1) {
        float gray = dot(indexedColor.rgb, vec3(0.299, 0.587, 0.114));
        FragColor = vec4(vec3(gray), indexedColor.a * clamp(GhostAlpha, 0.0, 1.0));
    } else {
        FragColor = indexedColor; // Output the final color
    }
}
