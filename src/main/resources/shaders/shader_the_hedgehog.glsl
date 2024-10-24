#version 120

uniform sampler2D Palette;
uniform sampler2D IndexedColorTexture;

void main()
{
    // Get the color index from the indexed texture
    float index = texture2D(IndexedColorTexture, gl_TexCoord[0].st).r * 255.0;

    // Map the index to palette coordinates (assuming 16 colors)
    float paletteX = index / 15.0;

    // Sample the palette texture to get the actual color
    vec4 indexedColor = texture2D(Palette, vec2(paletteX, 0.5));

    gl_FragColor = indexedColor; // Output the final color
}
