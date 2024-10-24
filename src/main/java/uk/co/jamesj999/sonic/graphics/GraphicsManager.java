package uk.co.jamesj999.sonic.graphics;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.DebugRenderCommand;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;

import static uk.co.jamesj999.sonic.level.LevelConstants.*;

public class GraphicsManager {
	private static GraphicsManager graphicsManager;
	List<GLCommandable> commands = new ArrayList<>();

	private final Map<String, Integer> patternTextureMap = new HashMap<>(); // Map for pattern textures
	private final Map<String, Integer> paletteTextureMap = new HashMap<>();  // Map for palette textures

	private final Camera camera = Camera.getInstance();

	public enum ShaderPrograms {
		TILE_RENDERER,
		TEXT_RENDERER
	}
	private HashMap<GraphicsManager.ShaderPrograms, ShaderProgram> programs = new HashMap<>();


	public void registerCommand(GLCommandable command) {
		commands.add(command);
	}

	/**
	 * Initialize the GraphicsManager with shader loading.
	 */
	public void init(String textShaderPath, String pixelShaderPath) throws IOException {
		// Load shaders
		//programs.put(GraphicsManager.ShaderPrograms.TEXT_RENDERER,new ShaderProgram(new Shader(Shader.Type.FRAGMENT,textShaderPath)));
		programs.put(GraphicsManager.ShaderPrograms.TILE_RENDERER,new ShaderProgram(new Shader(Shader.Type.FRAGMENT,pixelShaderPath)));
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
			command.execute(cameraX, cameraY, cameraWidth, cameraHeight);
		}

		commands.clear();
	}

	public void renderDebugInfo() {
		DebugRenderCommand command = new DebugRenderCommand();
		registerCommand(command);
	}

	public ShaderProgram getShader(ShaderPrograms programType) {
		return programs.get(programType);
	}

	public void useShader(ShaderPrograms program) {
		programs.get(program).use();
	}
	/**
	 * Cache a pattern texture (contains color indices) in the GPU.
	 */
	public void cachePatternTexture(Pattern pattern, int patternId) {
		int textureId = glGenTexture();

		// Create a buffer to store the color indices (8x8 grid of 1-byte indices)
		ByteBuffer patternBuffer = BufferUtils.createByteBuffer(Pattern.PATTERN_WIDTH * Pattern.PATTERN_HEIGHT);

		// Fill the buffer with the pattern's color indices
		for (int col = 0; col < Pattern.PATTERN_HEIGHT; col++) {
			for (int row = 0; row < Pattern.PATTERN_WIDTH; row++) {
				byte colorIndex = pattern.getPixel(row, col); // Get color index (0-15)
				patternBuffer.put(colorIndex);
			}
		}
		patternBuffer.flip();

		// Upload the pattern buffer to the GPU as a 2D texture
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, 8, 8, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, patternBuffer);

		// Set texture parameters (wrapping and filtering)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

		patternTextureMap.put("pattern_" + patternId, textureId);
	}

	/**
	 * Cache a palette texture (contains RGB color data) in the GPU.
	 */
	public void cachePaletteTexture(Palette palette, int paletteId) {
		int textureId = glGenTexture();

		// Create a buffer to store the palette (16 colors, each RGB component as an unsigned byte)
		ByteBuffer paletteBuffer = BufferUtils.createByteBuffer(COLORS_PER_PALETTE * 3); // 16 colors, each RGB (3 bytes)

		for (int i = 0; i < COLORS_PER_PALETTE; i++) {
			Palette.Color color = palette.getColor(i);
			paletteBuffer.put((byte) Byte.toUnsignedInt(color.r));
			paletteBuffer.put((byte) Byte.toUnsignedInt(color.g));
			paletteBuffer.put((byte) Byte.toUnsignedInt(color.b));
		}
		paletteBuffer.flip();

		// Upload the palette to the GPU as a 1x16 texture
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, 16, 1, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, paletteBuffer);

		// Set texture parameters
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

		paletteTextureMap.put("palette_" + paletteId, textureId);
	}

	/**
	 * Render a pre-cached pattern at the given coordinates using the specified palette.
	 */
	public void renderPattern(PatternDesc desc, int x, int y) {
		Integer patternTextureId = patternTextureMap.get("pattern_" + desc.getPatternIndex());
		Integer paletteTextureId = paletteTextureMap.get("palette_" + desc.getPaletteIndex());

		if (patternTextureId == null || paletteTextureId == null) {
			System.err.println("Pattern or Palette not cached.");
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
			GL11.glDeleteTextures(textureId);
		}
		// Delete palette textures
		for (int textureId : paletteTextureMap.values()) {
			GL11.glDeleteTextures(textureId);
		}

	}

	/**
	 * Generate a new texture ID.
	 */
	private int glGenTexture() {
		return GL11.glGenTextures();
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

}
