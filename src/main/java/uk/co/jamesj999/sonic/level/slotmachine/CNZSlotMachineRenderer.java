package uk.co.jamesj999.sonic.level.slotmachine;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.QuadRenderer;
import uk.co.jamesj999.sonic.graphics.ShaderProgram;

import uk.co.jamesj999.sonic.graphics.GLCommand;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Renders the CNZ slot machine visual display.
 * <p>
 * Uses a GPU shader to render 3 scrolling slot windows showing the current
 * face and scroll offset from {@link CNZSlotMachineManager}. The shader
 * handles face wrapping and palette lookup.
 * <p>
 * The slot display appears above the cage when a player is captured in a
 * linked PointPokey (subtype 0x01).
 */
public class CNZSlotMachineRenderer {
    private static final Logger LOGGER = Logger.getLogger(CNZSlotMachineRenderer.class.getName());

    // Texture dimensions: 32 wide × 192 tall (6 faces × 32 pixels/face)
    private static final int TEXTURE_WIDTH = 32;
    private static final int TEXTURE_HEIGHT = 192;
    private static final int FACE_HEIGHT = 32;
    private static final int NUM_FACES = 6;
    private static final int TILES_PER_FACE = 16; // 4x4 tiles
    private static final int BYTES_PER_TILE = 32; // 8x8 pixels × 4bpp = 32 bytes

    // Art order in ROM (ArtUnc_CNZSlotPics at 0x4EEFE):
    // Face 0: Sonic (30 rings)
    // Face 1: Tails (25 rings)
    // Face 2: Eggman (bombs)
    // Face 3: Jackpot (150 rings)
    // Face 4: Ring (10 rings)
    // Face 5: Bar (20 rings) - confirmed as Bar at s2.asm:59268
    //
    // Verify by checking art at ROM offset 0x4EEFE (each face is 512 bytes).

    // Display dimensions
    private static final int SLOT_WIDTH = 32;

    // Default offset from cage center to slot display (used when pattern scan fails)
    // These center the 96-pixel wide display and position it below the cage
    public static final int DEFAULT_OFFSET_X = -48; // Center the 96-pixel display
    public static final int DEFAULT_OFFSET_Y = 40;  // Below the cage (was 32, off by 1 pattern)

    // VRAM tile index range for CNZ slot display patterns
    // From s2.constants.asm: ArtTile_ArtUnc_CNZSlotPics_1 = $0550 through _3 = $0570
    // Each slot is 16 tiles (4x4), so range is $0550-$057F
    public static final int SLOT_TILE_MIN = 0x0550;
    public static final int SLOT_TILE_MAX = 0x057F;

    private ShaderProgram shader;
    private int textureId = 0;
    private final QuadRenderer quadRenderer = new QuadRenderer();
    private boolean initialized = false;

    // Cached uniform locations
    private int locSlotFaceTexture = -1;
    private int locPalette = -1;
    private int locSlotFace0 = -1;
    private int locSlotFace1 = -1;
    private int locSlotFace2 = -1;
    private int locSlotNextFace0 = -1;
    private int locSlotNextFace1 = -1;
    private int locSlotNextFace2 = -1;
    private int locSlotOffset0 = -1;
    private int locSlotOffset1 = -1;
    private int locSlotOffset2 = -1;
    private int locScreenX = -1;
    private int locScreenY = -1;
    private int locScreenWidth = -1;
    private int locScreenHeight = -1;
    private int locPaletteLine = -1;
    private int locViewportWidth = -1;
    private int locViewportHeight = -1;

    /**
     * Set the shader program. Called by GraphicsManager during initialization.
     */
    public void setShader(ShaderProgram shader) {
        this.shader = shader;
        resetUniformLocations();
    }

    /**
     * Initialize the renderer by loading slot art from ROM.
     *
     * @param gl  The OpenGL context
     * @param rom The ROM to load art from
     */
    public void init(GL2 gl, Rom rom) {
        if (initialized || gl == null || rom == null) {
            return;
        }

        // Load and create the slot face texture
        textureId = createSlotTexture(gl, rom);
        if (textureId == 0) {
            LOGGER.warning("Failed to create slot face texture");
            return;
        }

        // Initialize quad renderer
        quadRenderer.init(gl);

        // Cache uniform locations if shader is set
        if (shader != null) {
            cacheUniformLocations(gl);
        }

        initialized = true;
        LOGGER.info("CNZ Slot Machine Renderer initialized");
    }

    /**
     * Create the slot face texture from ROM data.
     * The texture contains all 6 faces stacked vertically (32×192 pixels).
     *
     * @param gl  The OpenGL context
     * @param rom The ROM to load from
     * @return The texture ID, or 0 on failure
     */
    private int createSlotTexture(GL2 gl, Rom rom) {
        // Read slot pictures from ROM
        byte[] slotData;
        try {
            slotData = rom.readBytes(
                    Sonic2Constants.ART_UNC_CNZ_SLOT_PICS_ADDR,
                    Sonic2Constants.ART_UNC_CNZ_SLOT_PICS_SIZE
            );
        } catch (Exception e) {
            LOGGER.severe("Failed to read slot pictures from ROM: " + e.getMessage());
            return 0;
        }

        int offset = 0;

        // Convert 4bpp tiled data to linear indexed texture
        ByteBuffer textureData = GLBuffers.newDirectByteBuffer(TEXTURE_WIDTH * TEXTURE_HEIGHT);

        for (int face = 0; face < NUM_FACES; face++) {
            int faceOffset = offset + face * TILES_PER_FACE * BYTES_PER_TILE;
            decodeFaceToTexture(slotData, faceOffset, textureData, face);
        }
        // decodeFaceToTexture uses absolute puts, so position is still 0
        // Set position to end, then flip to prepare for reading
        textureData.position(textureData.capacity());
        textureData.flip();

        // Create OpenGL texture
        int[] textures = new int[1];
        gl.glGenTextures(1, textures, 0);
        int texId = textures[0];

        gl.glBindTexture(GL2.GL_TEXTURE_2D, texId);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_REPEAT); // Allow vertical wrapping
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);

        gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RED, TEXTURE_WIDTH, TEXTURE_HEIGHT,
                0, GL2.GL_RED, GL2.GL_UNSIGNED_BYTE, textureData);

        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);

        return texId;
    }

    /**
     * Decode a single face (4×4 tiles) from ROM tiled format to linear texture format.
     * <p>
     * ROM format is 4bpp tiled (8×8 pixel tiles, each row is 4 bytes).
     * Tiles are arranged in column-major order within the 4×4 grid.
     *
     * @param romData     The ROM data
     * @param faceOffset  Offset to the start of this face's tile data
     * @param textureData The output texture buffer
     * @param faceIndex   Which face (0-5) for calculating Y offset in texture
     */
    private void decodeFaceToTexture(byte[] romData, int faceOffset, ByteBuffer textureData, int faceIndex) {
        int baseY = faceIndex * FACE_HEIGHT;

        // 4×4 tiles, column-major order
        for (int tileCol = 0; tileCol < 4; tileCol++) {
            for (int tileRow = 0; tileRow < 4; tileRow++) {
                int tileIndex = tileCol * 4 + tileRow;
                int tileOffset = faceOffset + tileIndex * BYTES_PER_TILE;

                // Decode this 8×8 tile
                for (int y = 0; y < 8; y++) {
                    int rowOffset = tileOffset + y * 4; // 4 bytes per row (4bpp × 8 pixels)

                    for (int x = 0; x < 8; x++) {
                        // 4bpp: 2 pixels per byte, high nibble first
                        int byteIndex = rowOffset + (x / 2);
                        int nibble;
                        if ((x & 1) == 0) {
                            // High nibble (even X)
                            nibble = (romData[byteIndex] >> 4) & 0x0F;
                        } else {
                            // Low nibble (odd X)
                            nibble = romData[byteIndex] & 0x0F;
                        }

                        // Calculate position in texture
                        int texX = tileCol * 8 + x;
                        int texY = baseY + tileRow * 8 + y;
                        int texIndex = texY * TEXTURE_WIDTH + texX;

                        textureData.put(texIndex, (byte) nibble);
                    }
                }
            }
        }
    }

    /**
     * Create a GLCommand to render the slot machine display.
     * The command is queued and executed later during the flush phase,
     * ensuring it renders AFTER the high-priority foreground tiles.
     *
     * @param manager          The slot machine state manager
     * @param cageScreenX      Screen X position of the cage center
     * @param cageScreenY      Screen Y position of the cage center
     * @param paletteTextureId The combined palette texture ID
     * @param displayOffsetX   X offset from cage center to slot display (or null for default)
     * @param displayOffsetY   Y offset from cage center to slot display (or null for default)
     * @return A GLCommand that renders the slot display, or null if not ready
     */
    public GLCommand createRenderCommand(CNZSlotMachineManager manager, int cageScreenX, int cageScreenY,
                                         int paletteTextureId, Integer displayOffsetX, Integer displayOffsetY) {
        if (!initialized || shader == null) {
            return null;
        }

        // Use provided offsets or fall back to defaults
        int offsetX = (displayOffsetX != null) ? displayOffsetX : DEFAULT_OFFSET_X;
        int offsetY = (displayOffsetY != null) ? displayOffsetY : DEFAULT_OFFSET_Y;

        // Calculate slot display position
        int screenX = cageScreenX + offsetX;
        int screenY = cageScreenY + offsetY;

        // Capture slot state at queue time (state may change before execution)
        int face0 = manager.getSlotFace(0);
        int face1 = manager.getSlotFace(1);
        int face2 = manager.getSlotFace(2);
        int nextFace0 = manager.getSlotNextFace(0);
        int nextFace1 = manager.getSlotNextFace(1);
        int nextFace2 = manager.getSlotNextFace(2);
        float offset0 = manager.getSlotOffset(0) / 256.0f;
        float offset1 = manager.getSlotOffset(1) / 256.0f;
        float offset2 = manager.getSlotOffset(2) / 256.0f;

        // Return a custom command that does the actual rendering
        return new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
            executeRender(gl, screenX, screenY, paletteTextureId,
                    face0, face1, face2, nextFace0, nextFace1, nextFace2,
                    offset0, offset1, offset2);
        });
    }

    /**
     * Execute the actual slot machine rendering.
     * Called from the queued GLCommand during flush.
     */
    private void executeRender(GL2 gl, int screenX, int screenY, int paletteTextureId,
                               int face0, int face1, int face2,
                               int nextFace0, int nextFace1, int nextFace2,
                               float offset0, float offset1, float offset2) {
        // Get viewport dimensions to handle scaling
        int[] viewport = new int[4];
        gl.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);
        int viewportWidth = viewport[2];
        int viewportHeight = viewport[3];

        // Save OpenGL state
        gl.glPushAttrib(GL2.GL_ALL_ATTRIB_BITS);

        // Set up for shader rendering
        gl.glEnable(GL2.GL_BLEND);
        gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDisable(GL2.GL_DEPTH_TEST);

        // Use the slot shader
        shader.use(gl);

        // Cache uniform locations if needed
        if (locSlotFaceTexture < 0) {
            cacheUniformLocations(gl);
        }

        // Bind textures
        gl.glActiveTexture(GL2.GL_TEXTURE0);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
        gl.glUniform1i(locSlotFaceTexture, 0);

        gl.glActiveTexture(GL2.GL_TEXTURE1);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, paletteTextureId);
        gl.glUniform1i(locPalette, 1);

        // Set slot state uniforms
        gl.glUniform1i(locSlotFace0, face0);
        gl.glUniform1i(locSlotFace1, face1);
        gl.glUniform1i(locSlotFace2, face2);

        // Set next face uniforms (for scroll wrapping - faces are non-sequential in sequence)
        gl.glUniform1i(locSlotNextFace0, nextFace0);
        gl.glUniform1i(locSlotNextFace1, nextFace1);
        gl.glUniform1i(locSlotNextFace2, nextFace2);

        gl.glUniform1f(locSlotOffset0, offset0);
        gl.glUniform1f(locSlotOffset1, offset1);
        gl.glUniform1f(locSlotOffset2, offset2);

        // Set screen position uniforms
        gl.glUniform1f(locScreenX, screenX);
        gl.glUniform1f(locScreenY, screenY);
        gl.glUniform1f(locScreenWidth, 320.0f);
        gl.glUniform1f(locScreenHeight, 224.0f);
        gl.glUniform1f(locPaletteLine, 0.0f); // CNZ slot faces use palette line 0

        // Pass actual viewport dimensions for coordinate conversion
        gl.glUniform1f(locViewportWidth, viewportWidth);
        gl.glUniform1f(locViewportHeight, viewportHeight);

        // Save and reset matrices for fullscreen quad
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        gl.glOrtho(0, viewportWidth, 0, viewportHeight, -1, 1);

        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        // Draw fullscreen quad using immediate mode (more compatible with fragment-only shader)
        gl.glBegin(GL2.GL_QUADS);
        gl.glVertex2f(0, 0);
        gl.glVertex2f(viewportWidth, 0);
        gl.glVertex2f(viewportWidth, viewportHeight);
        gl.glVertex2f(0, viewportHeight);
        gl.glEnd();

        // Restore matrices
        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_MODELVIEW);

        // Stop using shader
        shader.stop(gl);

        // Reset active texture
        gl.glActiveTexture(GL2.GL_TEXTURE0);

        // Restore OpenGL state
        gl.glPopAttrib();
    }

    /**
     * Cache shader uniform locations.
     */
    private void cacheUniformLocations(GL2 gl) {
        if (shader == null) {
            return;
        }

        int programId = shader.getProgramId();
        locSlotFaceTexture = gl.glGetUniformLocation(programId, "SlotFaceTexture");
        locPalette = gl.glGetUniformLocation(programId, "Palette");
        locSlotFace0 = gl.glGetUniformLocation(programId, "SlotFace0");
        locSlotFace1 = gl.glGetUniformLocation(programId, "SlotFace1");
        locSlotFace2 = gl.glGetUniformLocation(programId, "SlotFace2");
        locSlotNextFace0 = gl.glGetUniformLocation(programId, "SlotNextFace0");
        locSlotNextFace1 = gl.glGetUniformLocation(programId, "SlotNextFace1");
        locSlotNextFace2 = gl.glGetUniformLocation(programId, "SlotNextFace2");
        locSlotOffset0 = gl.glGetUniformLocation(programId, "SlotOffset0");
        locSlotOffset1 = gl.glGetUniformLocation(programId, "SlotOffset1");
        locSlotOffset2 = gl.glGetUniformLocation(programId, "SlotOffset2");
        locScreenX = gl.glGetUniformLocation(programId, "ScreenX");
        locScreenY = gl.glGetUniformLocation(programId, "ScreenY");
        locScreenWidth = gl.glGetUniformLocation(programId, "ScreenWidth");
        locScreenHeight = gl.glGetUniformLocation(programId, "ScreenHeight");
        locPaletteLine = gl.glGetUniformLocation(programId, "PaletteLine");
        locViewportWidth = gl.glGetUniformLocation(programId, "ViewportWidth");
        locViewportHeight = gl.glGetUniformLocation(programId, "ViewportHeight");

        // Check for invalid uniform locations (shader might have failed to compile)
        if (locSlotFaceTexture < 0 || locPalette < 0 || locViewportWidth < 0) {
            LOGGER.warning("Some shader uniforms are invalid - shader may have failed to compile");
        }
    }

    /**
     * Reset cached uniform locations.
     */
    private void resetUniformLocations() {
        locSlotFaceTexture = -1;
        locPalette = -1;
        locSlotFace0 = -1;
        locSlotFace1 = -1;
        locSlotFace2 = -1;
        locSlotNextFace0 = -1;
        locSlotNextFace1 = -1;
        locSlotNextFace2 = -1;
        locSlotOffset0 = -1;
        locSlotOffset1 = -1;
        locSlotOffset2 = -1;
        locScreenX = -1;
        locScreenY = -1;
        locScreenWidth = -1;
        locScreenHeight = -1;
        locPaletteLine = -1;
        locViewportWidth = -1;
        locViewportHeight = -1;
    }

    /**
     * Check if the renderer is initialized and ready.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Create a GLCommand to render all 6 slot faces for debug verification.
     * Shows faces 0-5 in two rows of 3, with face index labels.
     *
     * @param screenX         Screen X position (left edge)
     * @param screenY         Screen Y position (top edge)
     * @param paletteTextureId The combined palette texture ID
     * @param selectedFace    Currently selected face (-1 for none)
     * @return A GLCommand that renders the debug display, or null if not ready
     */
    public GLCommand createDebugRenderCommand(int screenX, int screenY, int paletteTextureId, int selectedFace) {
        if (!initialized || shader == null) {
            return null;
        }

        // Render all 6 faces in two rows of 3
        // For debug view, next face is just sequential since we're not scrolling
        return new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
            // Row 1: Faces 0, 1, 2 (Sonic, Tails, Eggman)
            executeRender(gl, screenX, screenY, paletteTextureId, 0, 1, 2, 1, 2, 3, 0.0f, 0.0f, 0.0f);
            // Row 2: Faces 3, 4, 5 (Jackpot, Ring, Bar)
            executeRender(gl, screenX, screenY + 40, paletteTextureId, 3, 4, 5, 4, 5, 0, 0.0f, 0.0f, 0.0f);
        });
    }

    /**
     * Get the expected face name for a given index (for debug display).
     */
    public static String getFaceName(int faceIndex) {
        return switch (faceIndex) {
            case 0 -> "Sonic";
            case 1 -> "Tails";
            case 2 -> "Eggman";
            case 3 -> "Jackpot";
            case 4 -> "Ring";
            case 5 -> "Bar";
            default -> "???";
        };
    }

    /**
     * Get the expected reward for a given face index (for debug display).
     */
    public static String getFaceReward(int faceIndex) {
        return switch (faceIndex) {
            case 0 -> "30 rings";
            case 1 -> "25 rings";
            case 2 -> "BOMBS";
            case 3 -> "150 rings";
            case 4 -> "10 rings";
            case 5 -> "20 rings";
            default -> "???";
        };
    }

    /**
     * Clean up OpenGL resources.
     */
    public void cleanup(GL2 gl) {
        if (gl != null && textureId != 0) {
            gl.glDeleteTextures(1, new int[]{textureId}, 0);
        }
        textureId = 0;
        quadRenderer.cleanup(gl);
        initialized = false;
    }
}
