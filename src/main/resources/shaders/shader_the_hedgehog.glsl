#version 110

uniform sampler2D Palette;
uniform sampler2D IndexedColorTexture;

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

    // Map the index to palette coordinates (assuming 16 colors)
    // We want the center of the texel. 1/16 = 0.0625. Center is 0.5/16 + index/16.
    float paletteX = (index + 0.5) / 16.0;

    // Sample the palette texture to get the actual color
    vec4 indexedColor = texture2D(Palette, vec2(paletteX, 0.5));

    gl_FragColor = indexedColor; // Output the final color
}
