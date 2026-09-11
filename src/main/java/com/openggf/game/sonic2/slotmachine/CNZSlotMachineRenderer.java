package com.openggf.game.sonic2.slotmachine;

import com.openggf.game.ZoneFeatureRenderer;
import com.openggf.graphics.RenderContext;
import org.lwjgl.system.MemoryUtil;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.graphics.QuadRenderer;
import com.openggf.graphics.ShaderProgram;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandable;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

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
public class CNZSlotMachineRenderer implements ZoneFeatureRenderer {
    private final int[] viewportScratch = new int[4];
    private final ArrayDeque<SlotRenderCommand> renderCommandPool = new ArrayDeque<>();
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
    private boolean initialized = false;

    // VAO/VBO for fullscreen quad
    private int vaoId = 0;
    private int vboId = 0;
    private int vertexPosLocation = -1;

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
    private int locTotalPaletteLines = -1;
    private int locViewportOffsetX = -1;
    private int locViewportOffsetY = -1;
    private int locViewportWidth = -1;
    private int locViewportHeight = -1;

    private final QuadRenderer quadRenderer = new QuadRenderer();

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
     * @param rom The ROM to load art from
     */
    public void init(Rom rom) {
        if (initialized || rom == null) {
            return;
        }

        // Load and create the slot face texture
        textureId = createSlotTexture(rom);
        if (textureId == 0) {
            LOGGER.warning("Failed to create slot face texture");
            return;
        }

        // Initialize fullscreen quad VAO/VBO
        initQuadVao();

        // Cache uniform locations if shader is set
        if (shader != null) {
            cacheUniformLocations();
        }

        initialized = true;
        LOGGER.info("CNZ Slot Machine Renderer initialized");
    }

    /**
     * Initialize VAO and VBO for the fullscreen quad.
     * Uses GL_TRIANGLE_STRIP with 4 vertices to cover the entire viewport.
     */
    private void initQuadVao() {
        if (vaoId != 0) {
            return;
        }

        // Create and bind VAO
        vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        // Create VBO with fullscreen quad vertices (normalized device coordinates)
        // Order: bottom-left, bottom-right, top-left, top-right (for triangle strip)
        FloatBuffer quadBuffer = MemoryUtil.memAllocFloat(8);
        quadBuffer.put(-1f).put(-1f);  // bottom-left
        quadBuffer.put(1f).put(-1f);   // bottom-right
        quadBuffer.put(-1f).put(1f);   // top-left
        quadBuffer.put(1f).put(1f);    // top-right
        quadBuffer.flip();

        vboId = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, quadBuffer, GL_STATIC_DRAW);

        MemoryUtil.memFree(quadBuffer);

        // Unbind
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Create the slot face texture from ROM data.
     * The texture contains all 6 faces stacked vertically (32×192 pixels).
     *
     * @param rom The ROM to load from
     * @return The texture ID, or 0 on failure
     */
    private int createSlotTexture(Rom rom) {
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
        ByteBuffer textureData = MemoryUtil.memAlloc(TEXTURE_WIDTH * TEXTURE_HEIGHT);

        for (int face = 0; face < NUM_FACES; face++) {
            int faceOffset = offset + face * TILES_PER_FACE * BYTES_PER_TILE;
            decodeFaceToTexture(slotData, faceOffset, textureData, face);
        }
        // decodeFaceToTexture uses absolute puts, so position is still 0
        // Set position to end, then flip to prepare for reading
        textureData.position(textureData.capacity());
        textureData.flip();

        // Create OpenGL texture
        int texId = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, texId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT); // Allow vertical wrapping
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, TEXTURE_WIDTH, TEXTURE_HEIGHT,
                0, GL_RED, GL_UNSIGNED_BYTE, textureData);

        glBindTexture(GL_TEXTURE_2D, 0);

        // Free the native memory buffer
        MemoryUtil.memFree(textureData);

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
    public GLCommandable createRenderCommand(CNZSlotMachineManager manager, int cageScreenX, int cageScreenY,
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

        SlotRenderCommand command = renderCommandPool.pollFirst();
        if (command == null) command = new SlotRenderCommand();
        return command.configure(screenX, screenY, paletteTextureId,
                face0, face1, face2, nextFace0, nextFace1, nextFace2,
                offset0, offset1, offset2);
    }

    private final class SlotRenderCommand implements GLCommandable {
        private int screenX, screenY, paletteTextureId;
        private int face0, face1, face2, nextFace0, nextFace1, nextFace2;
        private float offset0, offset1, offset2;
        private boolean leased;

        private SlotRenderCommand configure(int screenX, int screenY, int paletteTextureId,
                int face0, int face1, int face2, int nextFace0, int nextFace1, int nextFace2,
                float offset0, float offset1, float offset2) {
            this.screenX = screenX; this.screenY = screenY; this.paletteTextureId = paletteTextureId;
            this.face0 = face0; this.face1 = face1; this.face2 = face2;
            this.nextFace0 = nextFace0; this.nextFace1 = nextFace1; this.nextFace2 = nextFace2;
            this.offset0 = offset0; this.offset1 = offset1; this.offset2 = offset2;
            leased = true;
            return this;
        }

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            try {
                executeRender(screenX, screenY, paletteTextureId,
                        face0, face1, face2, nextFace0, nextFace1, nextFace2,
                        offset0, offset1, offset2);
            } finally {
                release();
            }
        }

        @Override public void discard() { release(); }

        private void release() {
            if (!leased) return;
            leased = false;
            renderCommandPool.addFirst(this);
        }
    }

    /**
     * Execute the actual slot machine rendering.
     * Called from the queued GLCommand during flush.
     */
    private void executeRender(int screenX, int screenY, int paletteTextureId,
                               int face0, int face1, int face2,
                               int nextFace0, int nextFace1, int nextFace2,
                               float offset0, float offset1, float offset2) {
        // Get viewport dimensions to handle scaling
        glGetIntegerv(GL_VIEWPORT, viewportScratch);
        int viewportWidth = viewportScratch[2];
        int viewportHeight = viewportScratch[3];

        // Save OpenGL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);

        // Set up for shader rendering
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_DEPTH_TEST);

        // Use the slot shader
        shader.use();

        // Cache uniform locations if needed
        if (locSlotFaceTexture < 0) {
            cacheUniformLocations();
        }

        // Bind textures
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glUniform1i(locSlotFaceTexture, 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, paletteTextureId);
        glUniform1i(locPalette, 1);

        // Set slot state uniforms
        glUniform1i(locSlotFace0, face0);
        glUniform1i(locSlotFace1, face1);
        glUniform1i(locSlotFace2, face2);

        // Set next face uniforms (for scroll wrapping - faces are non-sequential in sequence)
        glUniform1i(locSlotNextFace0, nextFace0);
        glUniform1i(locSlotNextFace1, nextFace1);
        glUniform1i(locSlotNextFace2, nextFace2);

        glUniform1f(locSlotOffset0, offset0);
        glUniform1f(locSlotOffset1, offset1);
        glUniform1f(locSlotOffset2, offset2);

        // Set screen position uniforms
        glUniform1f(locScreenX, screenX);
        glUniform1f(locScreenY, screenY);
        glUniform1f(locScreenWidth, 320.0f);
        glUniform1f(locScreenHeight, 224.0f);
        glUniform1f(locPaletteLine, 0.0f); // CNZ slot faces use palette line 0
        if (locTotalPaletteLines >= 0) {
            glUniform1f(locTotalPaletteLines, (float) RenderContext.getTotalPaletteLines());
        }

        // Fragment coordinates include the letterbox/pillarbox origin.
        glUniform1f(locViewportOffsetX, viewportScratch[0]);
        glUniform1f(locViewportOffsetY, viewportScratch[1]);

        // Pass actual viewport dimensions for coordinate conversion
        glUniform1f(locViewportWidth, viewportWidth);
        glUniform1f(locViewportHeight, viewportHeight);

        // Draw fullscreen quad using VAO/VBO (OpenGL 4.1 core compatible)
        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, vboId);

        // Enable vertex attribute for position
        if (vertexPosLocation >= 0) {
            glEnableVertexAttribArray(vertexPosLocation);
            glVertexAttribPointer(vertexPosLocation, 2, GL_FLOAT, false, 0, 0L);
        }

        // Draw the fullscreen quad as a triangle strip
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // Disable vertex attribute
        if (vertexPosLocation >= 0) {
            glDisableVertexAttribArray(vertexPosLocation);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        // Stop using shader
        shader.stop();

        // Reset active texture
        glActiveTexture(GL_TEXTURE0);

        // Restore OpenGL state
        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
    }

    /**
     * Cache shader uniform locations.
     */
    private void cacheUniformLocations() {
        if (shader == null) {
            return;
        }

        int programId = shader.getProgramId();
        locSlotFaceTexture = glGetUniformLocation(programId, "SlotFaceTexture");
        locPalette = glGetUniformLocation(programId, "Palette");
        locSlotFace0 = glGetUniformLocation(programId, "SlotFace0");
        locSlotFace1 = glGetUniformLocation(programId, "SlotFace1");
        locSlotFace2 = glGetUniformLocation(programId, "SlotFace2");
        locSlotNextFace0 = glGetUniformLocation(programId, "SlotNextFace0");
        locSlotNextFace1 = glGetUniformLocation(programId, "SlotNextFace1");
        locSlotNextFace2 = glGetUniformLocation(programId, "SlotNextFace2");
        locSlotOffset0 = glGetUniformLocation(programId, "SlotOffset0");
        locSlotOffset1 = glGetUniformLocation(programId, "SlotOffset1");
        locSlotOffset2 = glGetUniformLocation(programId, "SlotOffset2");
        locScreenX = glGetUniformLocation(programId, "ScreenX");
        locScreenY = glGetUniformLocation(programId, "ScreenY");
        locScreenWidth = glGetUniformLocation(programId, "ScreenWidth");
        locScreenHeight = glGetUniformLocation(programId, "ScreenHeight");
        locPaletteLine = glGetUniformLocation(programId, "PaletteLine");
        locTotalPaletteLines = glGetUniformLocation(programId, "TotalPaletteLines");
        locViewportOffsetX = glGetUniformLocation(programId, "ViewportOffsetX");
        locViewportOffsetY = glGetUniformLocation(programId, "ViewportOffsetY");
        locViewportWidth = glGetUniformLocation(programId, "ViewportWidth");
        locViewportHeight = glGetUniformLocation(programId, "ViewportHeight");

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
        locTotalPaletteLines = -1;
        locViewportOffsetX = -1;
        locViewportOffsetY = -1;
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
    @Override
    public int getDebugFrameCount() {
        return 6;
    }

    @Override
    public GLCommand createDebugRenderCommand(int screenX, int screenY, int paletteTextureId, int selectedFace) {
        if (!initialized || shader == null) {
            return null;
        }

        // Render all 6 faces in two rows of 3
        // For debug view, next face is just sequential since we're not scrolling
        return new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
            // Row 1: Faces 0, 1, 2 (Sonic, Tails, Eggman)
            executeRender(screenX, screenY, paletteTextureId, 0, 1, 2, 1, 2, 3, 0.0f, 0.0f, 0.0f);
            // Row 2: Faces 3, 4, 5 (Jackpot, Ring, Bar)
            executeRender(screenX, screenY + 40, paletteTextureId, 3, 4, 5, 4, 5, 0, 0.0f, 0.0f, 0.0f);
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

    @Override
    public String getDebugFrameName(int frameIndex) {
        return getFaceName(frameIndex);
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

    @Override
    public String getDebugFrameDescription(int frameIndex) {
        return getFaceReward(frameIndex);
    }

    /**
     * Clean up OpenGL resources.
     */
    public void cleanup() {
        if (textureId != 0) {
            glDeleteTextures(textureId);
        }
        textureId = 0;
        quadRenderer.cleanup();
        initialized = false;
    }
}
