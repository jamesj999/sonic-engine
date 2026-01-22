#version 110

// Standard inputs (from ShaderProgram/default shader)
uniform sampler2D Palette;              // Texture Unit 0
uniform sampler2D IndexedColorTexture;  // Texture Unit 1
uniform float PaletteLine;

// Water-specific inputs
uniform sampler2D UnderwaterPalette;    // Texture Unit 2
uniform float WaterlineScreenY;         // Screen Y where water starts (0 = top)
uniform float ScreenHeight;             // Screen height in pixels (e.g. 224)
uniform float ScreenWidth;              // Screen width in pixels (e.g. 320)
uniform int FrameCounter;               // For animation
uniform float DistortionAmplitude;      // Amplitude of ripple in pixels
uniform float WindowHeight;             // Physical window height in pixels

// World-space water uniforms for FBO rendering
uniform float WaterLevelWorldY;         // Water level in world coordinates
uniform float RenderWorldYOffset;       // World Y offset for current render context
uniform int UseWorldSpaceWater;         // 0 = screen space, 1 = world space

void main()
{
    // Determine if we're underwater based on mode
    float pixelYFromTop;
    float waterlineY;
    
    if (UseWorldSpaceWater == 1) {
        // World-space mode (for FBO/background rendering)
        // gl_FragCoord.y in FBO space (0 at bottom of FBO)
        // Convert to world Y: worldY = RenderWorldYOffset + (FBOHeight - gl_FragCoord.y)
        // Since FBOHeight = WindowHeight in FBO mode:
        float worldY = RenderWorldYOffset + (WindowHeight - gl_FragCoord.y);
        pixelYFromTop = worldY;
        waterlineY = WaterLevelWorldY;
    } else {
        // Screen-space mode (for foreground rendering)
        // Normalize to 0..1 (0 at top, 1 at bottom)
        float normalizedY = 1.0 - (gl_FragCoord.y / WindowHeight);
        pixelYFromTop = normalizedY * ScreenHeight;
        waterlineY = WaterlineScreenY;
    }
    
    float distortion = 0.0;
    
    // Check if below waterline
    if (pixelYFromTop >= waterlineY) {
        // Calculate procedural sine wave distortion
        // Frequency: roughly 4 scanlines per cycle looks good for Sonic
        // Speed: FrameCounter moves the phase
        
        float scanlinesBelow = pixelYFromTop - waterlineY;
        
        // Formula similar to Genesis sine table lookups
        // Using FrameCounter to shift phase
        float angle = (scanlinesBelow * 0.15) + (float(FrameCounter) * 0.2);
        
        // Amplitude: typically 1-2 pixels
        distortion = sin(angle) * DistortionAmplitude;
    }
    
    // Apply distortion to U coordinate
    // UV.s is 0..1 representing 0..ScreenWidth
    vec2 uv = gl_TexCoord[0].st;
    float uDistortion = distortion / ScreenWidth;
    uv.s += uDistortion;
    
    // Sample texture index
    float index = texture2D(IndexedColorTexture, uv).r * 255.0;
    
    bool isTransparent = index < 0.1;
    
    // Output Color Lookup
    vec4 color;
    
    if (isTransparent) {
        discard;
    } else {
        // Resolve palette line (uniform or per-vertex attribute via texcoord1.s)
        float paletteLine = PaletteLine;
        if (paletteLine < 0.0) {
            paletteLine = gl_TexCoord[1].s;
        }

        // Standard palette lookup
        float paletteX = (index + 0.5) / 16.0;
        float paletteY = (paletteLine + 0.5) / 4.0;
        
        if (pixelYFromTop >= waterlineY) {
             // Use underwater palette
             color = texture2D(UnderwaterPalette, vec2(paletteX, paletteY));
        } else {
             // Use normal palette
             color = texture2D(Palette, vec2(paletteX, paletteY));
        }
    }
    
    gl_FragColor = color;
}
