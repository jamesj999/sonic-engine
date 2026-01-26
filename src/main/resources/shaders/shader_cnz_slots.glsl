#version 110

// CNZ Slot Machine Display Shader
// Renders 3 scrolling slot windows with face wrapping

uniform sampler2D SlotFaceTexture;  // All 6 faces stacked vertically (32x192 indexed)
uniform sampler2D Palette;           // Combined palette texture (16x4)

// Per-slot state: face index (0-5) and scroll offset (0.0-1.0)
uniform int SlotFace0;
uniform int SlotFace1;
uniform int SlotFace2;
// Next face in sequence (for scroll wrapping - faces are non-sequential: {3,0,1,4,2,5,4,1})
uniform int SlotNextFace0;
uniform int SlotNextFace1;
uniform int SlotNextFace2;
uniform float SlotOffset0;
uniform float SlotOffset1;
uniform float SlotOffset2;

// Screen positioning
uniform float ScreenX;       // Left edge of slot display (game screen coords)
uniform float ScreenY;       // Top edge of slot display (game screen coords)
uniform float ScreenWidth;   // Game screen width (320)
uniform float ScreenHeight;  // Game screen height (224)

// Actual viewport dimensions (for scaling from window to game coords)
uniform float ViewportWidth;
uniform float ViewportHeight;

// Palette line for CNZ (typically line 1)
uniform float PaletteLine;

// Constants
const float SLOT_WIDTH = 32.0;   // Each slot window is 32 pixels wide
const float SLOT_HEIGHT = 32.0;  // Each slot window is 32 pixels tall
const float SLOT_SPACING = 0.0;  // No gap between slots
const float FACE_HEIGHT = 32.0;  // Each face is 32 pixels tall
const float NUM_FACES = 6.0;     // 6 faces total
const float TEXTURE_HEIGHT = 192.0; // 6 faces × 32 pixels

void main()
{
    // Convert from viewport coordinates to game screen coordinates
    // gl_FragCoord is in viewport pixels (0 to ViewportWidth/Height)
    // We need to scale to game pixels (0 to ScreenWidth/Height)
    float scaleX = ScreenWidth / ViewportWidth;
    float scaleY = ScreenHeight / ViewportHeight;

    // Convert viewport coords to game coords (Y=0 at top in game coords)
    float pixelX = gl_FragCoord.x * scaleX;
    float pixelY = ScreenHeight - (gl_FragCoord.y * scaleY);

    // Check if pixel is within the slot display area (3 slots × 32 wide)
    float totalWidth = SLOT_WIDTH * 3.0 + SLOT_SPACING * 2.0;
    float localX = pixelX - ScreenX;
    float localY = pixelY - ScreenY;

    if (localX < 0.0 || localX >= totalWidth || localY < 0.0 || localY >= SLOT_HEIGHT) {
        discard;
    }

    // Determine which slot this pixel belongs to (0, 1, or 2)
    int slotIndex = int(floor(localX / (SLOT_WIDTH + SLOT_SPACING)));
    if (slotIndex > 2) slotIndex = 2;

    // Get the slot's face, next face (for wrapping), and offset
    int slotFace;
    int slotNextFace;
    float slotOffset;
    if (slotIndex == 0) {
        slotFace = SlotFace0;
        slotNextFace = SlotNextFace0;
        slotOffset = SlotOffset0;
    } else if (slotIndex == 1) {
        slotFace = SlotFace1;
        slotNextFace = SlotNextFace1;
        slotOffset = SlotOffset1;
    } else {
        slotFace = SlotFace2;
        slotNextFace = SlotNextFace2;
        slotOffset = SlotOffset2;
    }

    // Local coordinates within this slot window
    float slotLocalX = localX - float(slotIndex) * (SLOT_WIDTH + SLOT_SPACING);
    float slotLocalY = localY;

    // Calculate texture UV
    // U is simply the X position within the 32-pixel wide texture
    float u = (slotLocalX + 0.5) / SLOT_WIDTH;

    // V calculation with scrolling and face wrapping
    // slotOffset is 0.0-1.0 representing scroll through one face height
    // We need to handle wrapping between faces

    // Base V coordinate within the face (0.0 to 1.0)
    float baseV = slotLocalY / FACE_HEIGHT;

    // Add scroll offset (inverted because scrolling down shows next face)
    float scrolledV = baseV + slotOffset;

    // Determine which face(s) we're sampling from
    // When scrolledV > 1.0, we're seeing the next face in the sequence
    // The next face is passed as a uniform because the sequence is non-sequential
    float faceIndex = float(slotFace);
    if (scrolledV >= 1.0) {
        scrolledV -= 1.0;
        faceIndex = float(slotNextFace);  // Use actual next face from sequence, not faceIndex + 1
    }

    // Convert to texture V coordinate (face index determines which 32-pixel band)
    float textureV = (faceIndex * FACE_HEIGHT + scrolledV * FACE_HEIGHT + 0.5) / TEXTURE_HEIGHT;

    // Sample the indexed texture (single channel, 0-15 color index per pixel)
    float colorIndex = texture2D(SlotFaceTexture, vec2(u, textureV)).r * 255.0;

    // Color index 0 is transparent
    if (colorIndex < 0.5) {
        discard;
    }

    // Palette lookup: X = color index (0-15), Y = palette line
    float paletteX = (colorIndex + 0.5) / 16.0;
    float paletteY = (PaletteLine + 0.5) / 4.0;
    vec4 color = texture2D(Palette, vec2(paletteX, paletteY));

    gl_FragColor = color;
}
