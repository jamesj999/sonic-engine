#version 110

// Fade overlay shader for screen transitions
// Used for fade-to-white and fade-from-white effects when entering/exiting special stages
// Original Sonic 2 behavior: RGB channels increment sequentially over 21 frames

uniform vec3 FadeColor;  // RGB values to add (0.0 to 1.0 per channel)

void main()
{
    // Output the fade color with full opacity
    // Blending mode (GL_ONE, GL_ONE) will add this to the existing frame buffer
    gl_FragColor = vec4(FadeColor, 1.0);
}
