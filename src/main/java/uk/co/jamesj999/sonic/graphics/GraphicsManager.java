package uk.co.jamesj999.sonic.graphics;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.art.SpriteArt;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;

import static uk.co.jamesj999.sonic.level.LevelConstants.*;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;

public class GraphicsManager {
	private static GraphicsManager graphicsManager;
	List<GLCommandable> commands = new ArrayList<>();

	private final Map<String, Integer> patternTextureMap = new HashMap<>(); // Map for pattern textures
	private final Map<String, Integer> paletteTextureMap = new HashMap<>();  // Map for palette textures

	private final Camera camera = Camera.getInstance();
	private GL2 graphics;
	private ShaderProgram shaderProgram;

	public void registerCommand(GLCommandable command) {
		commands.add(command);
	}

	/**
	 * Initialize the GraphicsManager with shader loading.
	 */
	public void init(GL2 gl, String pixelShaderPath) throws IOException {
		this.graphics = gl;
		this.shaderProgram = new ShaderProgram(gl, pixelShaderPath);  // Load shaders
	}

	/**
	 * Set the current GL2 context (in case it needs resetting).
	 */
	public void setGraphics(GL2 gl) {
		graphics = gl;
	}

	/**
	 * Flush all registered commands.
	 */
	public void flush() {
		short cameraX = camera.getX();
		short cameraY = camera.getY();
		short cameraWidth = camera.getWidth();
		short cameraHeight = camera.getHeight();

		for (GLCommandable command : commands) {
			command.execute(graphics, cameraX, cameraY, cameraWidth, cameraHeight);
		}
		commands.clear();
	}

	/**
	 * Cache a pattern texture (contains color indices) in the GPU.
	 */
	public void cachePatternTexture(Pattern pattern, int patternId) {
		cachePatternTexture(pattern, patternId, null);
	}


	public void cachePaletteTexture(Palette palette, int paletteId) {
		int textureId = glGenTexture();

		// Create a buffer to store the palette (16 colors, each RGBA component as an unsigned byte)
		ByteBuffer paletteBuffer = GLBuffers.newDirectByteBuffer(COLORS_PER_PALETTE * 4); // 16 colors, each RGBA (4 bytes)

		for (int i = 0; i < COLORS_PER_PALETTE; i++) {
			Palette.Color color = palette.getColor(i);
			paletteBuffer.put((byte) Byte.toUnsignedInt(color.r));
			paletteBuffer.put((byte) Byte.toUnsignedInt(color.g));
			paletteBuffer.put((byte) Byte.toUnsignedInt(color.b));
			if (i == 0) {
				paletteBuffer.put((byte) 0);
			} else {
				paletteBuffer.put((byte) 255);
			}
		}
		paletteBuffer.flip();

		// Upload the palette to the GPU as a 1x16 texture using GL_UNSIGNED_BYTE
		graphics.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
		graphics.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RGBA, 16, 1, 0, GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, paletteBuffer);

		// Set texture parameters
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);

		paletteTextureMap.put("palette_" + paletteId, textureId);
	}


	public void cacheSpriteArt(SpriteArt spriteArt) {
		byte[] art = spriteArt.getArt();
		int patternCount = art.length / Pattern.PATTERN_SIZE_IN_ROM;
		for (int i = 0; i < patternCount; i++) {
			byte[] patternBytes = new byte[Pattern.PATTERN_SIZE_IN_ROM];
			System.arraycopy(art, i * Pattern.PATTERN_SIZE_IN_ROM, patternBytes, 0, Pattern.PATTERN_SIZE_IN_ROM);
			Pattern pattern = new Pattern();
			pattern.fromSegaFormat(patternBytes);
			cachePatternTexture(pattern, i, spriteArt.getCode());
		}
	}

	public void cachePatternTexture(Pattern pattern, int patternId, String spriteCode) {
		int textureId = glGenTexture();

		// Create a buffer to store the color indices (8x8 grid of 1-byte indices)
		ByteBuffer patternBuffer = GLBuffers.newDirectByteBuffer(Pattern.PATTERN_WIDTH * Pattern.PATTERN_HEIGHT);

		// Fill the buffer with the pattern's color indices
		for (int col = 0; col < Pattern.PATTERN_HEIGHT; col++) {
			for (int row = 0; row < Pattern.PATTERN_WIDTH; row++) {
				byte colorIndex = pattern.getPixel(row, col); // Get color index (0-15)
				patternBuffer.put(colorIndex);
			}
		}
		patternBuffer.flip();

		// Upload the pattern buffer to the GPU as a 2D texture
		graphics.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
		graphics.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RED, 8, 8, 0, GL2.GL_RED, GL2.GL_UNSIGNED_BYTE, patternBuffer);

		// Set texture parameters (wrapping and filtering)
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP_TO_EDGE);
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);
		String key = (spriteCode == null) ? "pattern_" + patternId : "sprite_" + spriteCode + "_" + patternId;
		patternTextureMap.put(key, textureId);
	}


	/**
	 * Render a pre-cached pattern at the given coordinates using the specified palette.
	 */
	public void renderPattern(PatternDesc desc, int x, int y, String spriteCode) {
		String key = (spriteCode == null) ? "pattern_" + desc.getPatternIndex() : "sprite_" + spriteCode + "_" + desc.getPatternIndex();
		Integer patternTextureId = patternTextureMap.get(key);
		Integer paletteTextureId = paletteTextureMap.get("palette_" + desc.getPaletteIndex());

		if (patternTextureId == null || paletteTextureId == null) {
			System.err.println("Pattern or Palette not cached for key " + key + ".");
			return;
		}

		// Register a PatternRenderCommand instead of directly rendering
		PatternRenderCommand command = new PatternRenderCommand(patternTextureId, paletteTextureId, desc, x, y);
		registerCommand(command);
	}

	/**
	 * Cleanup method to delete textures and release resources.
	 */
	public void cleanup() {
		// Delete pattern textures
		for (int textureId : patternTextureMap.values()) {
			graphics.glDeleteTextures(1, new int[]{textureId}, 0);
		}
		// Delete palette textures
		for (int textureId : paletteTextureMap.values()) {
			graphics.glDeleteTextures(1, new int[]{textureId}, 0);
		}
		// Cleanup shader program
		if (shaderProgram != null) {
			shaderProgram.cleanup(graphics);
		}
	}

	/**
	 * Generate a new texture ID.
	 */
	private int glGenTexture() {
		int[] texture = new int[1];
		graphics.glGenTextures(1, texture, 0);
		return texture[0];
	}

	/**
	 * Singleton access to the GraphicsManager instance.
	 */
	public static synchronized final GraphicsManager getInstance() {
		if (graphicsManager == null) {
			graphicsManager = new GraphicsManager();
		}
		return graphicsManager;
	}

	public ShaderProgram getShaderProgram() {
		return shaderProgram;
	}

	public GL2 getGraphics() {
		return graphics;
	}
}
