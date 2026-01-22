#version 110

uniform sampler2D Palette;
uniform sampler2D IndexedColorTexture;
uniform float PaletteLine;

void main()
{
    // Get the color index from the indexed texture
    float index = texture2D(IndexedColorTexture, gl_TexCoord[0].st).r * 255.0;

    // Mega Drive VDP Rule: Index 0 is transparent.
    // We discard the fragment so it doesn't write to the frame buffer (or depth buffer),
    // allowing the backdrop or previous layers to show through.
    if (index < 0.1) {
        discard;
    }

    // Resolve palette line (uniform or per-vertex attribute via texcoord1.s)
    float paletteLine = PaletteLine;
    if (paletteLine < 0.0) {
        paletteLine = gl_TexCoord[1].s;
    }

    // Map the index to palette coordinates (16 colors, 4 lines)
    float paletteX = (index + 0.5) / 16.0;
    float paletteY = (paletteLine + 0.5) / 4.0;

    // Sample the palette texture to get the actual color
    vec4 indexedColor = texture2D(Palette, vec2(paletteX, paletteY));

    gl_FragColor = indexedColor; // Output the final color
}
