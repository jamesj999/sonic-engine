package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.data.Rom;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.SlotWindowGpuPass;
import com.openggf.graphics.ShaderProgram;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;

public final class S3kSlotMachineRenderer {
    private static final Logger LOGGER = Logger.getLogger(S3kSlotMachineRenderer.class.getName());

    private static final int TEXTURE_WIDTH = 32;
    private static final int TEXTURE_HEIGHT = 256;
    private static final int FACE_HEIGHT = 32;
    private static final int NUM_FACES = 8;
    private static final int TILES_PER_FACE = 16;
    private static final int BYTES_PER_TILE = 32;
    private static final float SLOT_PALETTE_LINE = 0.0f;

    private static final String SHADER_PATH = "shaders/shader_s3k_slots.glsl";

    private ShaderProgram shader;
    private int textureId;
    private boolean initialized;
    private final SlotWindowGpuPass gpuPass = new SlotWindowGpuPass();

    public void init(Rom rom) {
        if (initialized || rom == null) {
            return;
        }
        if (shader == null) {
            try {
                shader = new ShaderProgram(ShaderProgram.FULLSCREEN_VERTEX_SHADER, SHADER_PATH);
            } catch (Exception e) {
                LOGGER.warning("Failed to create S3K slot machine shader: " + e.getMessage());
                return;
            }
        }
        textureId = createSlotTexture(rom);
        if (textureId == 0) {
            LOGGER.warning("Failed to create S3K slot options texture");
            return;
        }
        gpuPass.setShader(shader);
        gpuPass.init();

        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public GLCommand createRenderCommand(S3kSlotMachineDisplayState state, int cameraX, int cameraY, int paletteTextureId) {
        if (!initialized || shader == null || state == null) {
            return null;
        }
        int screenX = computeDisplayScreenX(state.worldX(), cameraX);
        int screenY = computeDisplayScreenY(state.worldY(), cameraY);
        int[] faces = state.faces().clone();
        int[] nextFaces = state.nextFaces().clone();
        float[] offsets = state.offsets().clone();
        return new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) ->
                executeRender(screenX, screenY, paletteTextureId, faces, nextFaces, offsets));
    }

    static int computeDisplayScreenX(int stageAnchorX, int cameraX) {
        return (stageAnchorX - cameraX) + S3kSlotRomData.SLOT_MACHINE_DISPLAY_OFFSET_X;
    }

    static int computeDisplayScreenY(int stageAnchorY, int cameraY) {
        return (stageAnchorY - cameraY) + S3kSlotRomData.SLOT_MACHINE_DISPLAY_OFFSET_Y;
    }

    static float paletteLineForTest() {
        return SLOT_PALETTE_LINE;
    }

    public void cleanup() {
        initialized = false;
        if (textureId != 0) {
            glDeleteTextures(textureId);
            textureId = 0;
        }
        gpuPass.cleanup();
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
    }

    private int createSlotTexture(Rom rom) {
        byte[] slotData;
        try {
            slotData = rom.readBytes(
                    Sonic3kConstants.ART_UNC_SLOT_OPTIONS_ADDR,
                    Sonic3kConstants.ART_UNC_SLOT_OPTIONS_SIZE
            );
        } catch (Exception e) {
            LOGGER.warning("Failed reading S3K slot options art: " + e.getMessage());
            return 0;
        }

        ByteBuffer textureData = MemoryUtil.memAlloc(TEXTURE_WIDTH * TEXTURE_HEIGHT);
        for (int face = 0; face < NUM_FACES; face++) {
            decodeFaceToTexture(slotData, face * TILES_PER_FACE * BYTES_PER_TILE, textureData, face);
        }
        textureData.position(textureData.capacity());
        textureData.flip();

        int texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, TEXTURE_WIDTH, TEXTURE_HEIGHT,
                0, GL_RED, GL_UNSIGNED_BYTE, textureData);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(textureData);
        return texId;
    }

    private void decodeFaceToTexture(byte[] romData, int faceOffset, ByteBuffer textureData, int faceIndex) {
        int baseY = faceIndex * FACE_HEIGHT;
        for (int tileCol = 0; tileCol < 4; tileCol++) {
            for (int tileRow = 0; tileRow < 4; tileRow++) {
                int tileIndex = tileCol * 4 + tileRow;
                int tileOffset = faceOffset + tileIndex * BYTES_PER_TILE;
                for (int y = 0; y < 8; y++) {
                    int rowOffset = tileOffset + y * 4;
                    for (int x = 0; x < 8; x++) {
                        int byteIndex = rowOffset + (x / 2);
                        int nibble = ((x & 1) == 0)
                                ? ((romData[byteIndex] >> 4) & 0x0F)
                                : (romData[byteIndex] & 0x0F);
                        int texX = tileCol * 8 + x;
                        int texY = baseY + tileRow * 8 + y;
                        textureData.put(texY * TEXTURE_WIDTH + texX, (byte) nibble);
                    }
                }
            }
        }
    }

    private void executeRender(int screenX, int screenY, int paletteTextureId,
                               int[] faces, int[] nextFaces, float[] offsets) {
        gpuPass.draw(textureId, paletteTextureId, screenX, screenY, SLOT_PALETTE_LINE,
                faces[0], faces[1], faces[2], nextFaces[0], nextFaces[1], nextFaces[2],
                offsets[0], offsets[1], offsets[2]);
    }

}
