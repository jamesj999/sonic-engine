#version 110

uniform sampler2D Palette;
uniform sampler2D IndexedColorTexture;

void main()
{
    // Get the normalized color index from the indexed texture
    float normalized_index = texture2D(IndexedColorTexture, gl_TexCoord[0].st).r;

    // De-normalize the index to get a value from 0 to 255
    float index = normalized_index * 255.0;

    // Calculate the precise center of the texel for the given index
    // The palette has 16 colors, so we divide by 16.0 to map the index to the texture width.
    // We add 0.5 to get the center of the texel.
    float paletteX = (index + 0.5) / 16.0;

    // Sample the palette texture to get the actual color
    vec4 indexedColor = texture2D(Palette, vec2(paletteX, 0.5));

    gl_FragColor = indexedColor; // Output the final color
}
