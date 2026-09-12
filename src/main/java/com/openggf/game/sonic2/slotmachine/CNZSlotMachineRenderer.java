package com.openggf.game.sonic2.slotmachine;

import com.openggf.game.ZoneFeatureRenderer;
import org.lwjgl.system.MemoryUtil;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.graphics.SlotWindowGpuPass;
import com.openggf.graphics.ShaderProgram;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandable;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;

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

    // Shared fullscreen draw and quad resource ownership
    private final SlotWindowGpuPass gpuPass = new SlotWindowGpuPass();

    /**
     * Set the shader program. Called by GraphicsManager during initialization.
     */
    public void setShader(ShaderProgram shader) {
        this.shader = shader;
        gpuPass.setShader(shader);
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

        gpuPass.setShader(shader);
        gpuPass.init();

        initialized = true;
        LOGGER.info("CNZ Slot Machine Renderer initialized");
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
        gpuPass.draw(textureId, paletteTextureId, screenX, screenY, 0.0f,
                face0, face1, face2, nextFace0, nextFace1, nextFace2, offset0, offset1, offset2);
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
        gpuPass.cleanup();
        initialized = false;
    }
}
