package uk.co.jamesj999.sonic.graphics;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;

import static uk.co.jamesj999.sonic.level.LevelConstants.*;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;

public class GraphicsManager {
	private static GraphicsManager graphicsManager;
	List<GLCommandable> commands = new ArrayList<>();

	private final Map<String, Integer> patternTextureMap = new HashMap<>(); // Map for pattern textures
	private final Map<String, Integer> paletteTextureMap = new HashMap<>(); // Map for palette textures
	private Integer combinedPaletteTextureId;

	private final Camera camera = Camera.getInstance();
	private GL2 graphics;
	private ShaderProgram shaderProgram;
	private ShaderProgram debugShaderProgram;
	private static final String DEBUG_SHADER_PATH = "shaders/shader_debug_color.glsl";

	// Batched rendering support
	private boolean batchingEnabled = true;
	private BatchedPatternRenderer batchedRenderer;

	public void registerCommand(GLCommandable command) {
		commands.add(command);
	}

	/**
	 * Initialize the GraphicsManager with shader loading.
	 */
	public void init(GL2 gl, String pixelShaderPath) throws IOException {
		this.graphics = gl;
		this.shaderProgram = new ShaderProgram(gl, pixelShaderPath); // Load shaders
		this.shaderProgram.cacheUniformLocations(gl); // Cache uniform locations for fast access
		this.debugShaderProgram = new ShaderProgram(gl, DEBUG_SHADER_PATH);
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
		if (commands.isEmpty() || graphics == null) {
			return;
		}

		short cameraX = camera.getX();
		short cameraY = camera.getY();
		short cameraWidth = camera.getWidth();
		short cameraHeight = camera.getHeight();

		// Reset pattern render state for new batch of commands
		PatternRenderCommand.resetFrameState();

		for (GLCommandable command : commands) {
			command.execute(graphics, cameraX, cameraY, cameraWidth, cameraHeight);
		}

		// Cleanup pattern render state after all commands
		PatternRenderCommand.cleanupFrameState(graphics);

		commands.clear();
	}

	/**
	 * Cache a pattern texture (contains color indices) in the GPU.
	 */
	public void cachePatternTexture(Pattern pattern, int patternId) {
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
		graphics.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RED, 8, 8, 0, GL2.GL_RED, GL2.GL_UNSIGNED_BYTE,
				patternBuffer);

		// Set texture parameters (wrapping and filtering)
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP_TO_EDGE);
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
		graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);

		patternTextureMap.put("pattern_" + patternId, textureId);
	}

	public void updatePatternTexture(Pattern pattern, int patternId) {
		if (graphics == null) {
			return;
		}
		Integer textureId = patternTextureMap.get("pattern_" + patternId);
		if (textureId == null) {
			cachePatternTexture(pattern, patternId);
			return;
		}

		ByteBuffer patternBuffer = GLBuffers.newDirectByteBuffer(Pattern.PATTERN_WIDTH * Pattern.PATTERN_HEIGHT);
		for (int col = 0; col < Pattern.PATTERN_HEIGHT; col++) {
			for (int row = 0; row < Pattern.PATTERN_WIDTH; row++) {
				byte colorIndex = pattern.getPixel(row, col);
				patternBuffer.put(colorIndex);
			}
		}
		patternBuffer.flip();

		graphics.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
		graphics.glTexSubImage2D(GL2.GL_TEXTURE_2D, 0, 0, 0, 8, 8, GL2.GL_RED, GL2.GL_UNSIGNED_BYTE, patternBuffer);
	}

	public void cachePaletteTexture(Palette palette, int paletteId) {
		if (combinedPaletteTextureId == null) {
			combinedPaletteTextureId = glGenTexture();
			ByteBuffer emptyBuffer = GLBuffers.newDirectByteBuffer(COLORS_PER_PALETTE * 4 * 4);
			graphics.glBindTexture(GL2.GL_TEXTURE_2D, combinedPaletteTextureId);
			graphics.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RGBA, 16, 4, 0, GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE,
					emptyBuffer);
			graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
			graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP_TO_EDGE);
			graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
			graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);
		}

		ByteBuffer paletteBuffer = GLBuffers.newDirectByteBuffer(COLORS_PER_PALETTE * 4);
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

		graphics.glBindTexture(GL2.GL_TEXTURE_2D, combinedPaletteTextureId);
		graphics.glTexSubImage2D(GL2.GL_TEXTURE_2D, 0, 0, paletteId, 16, 1, GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE,
				paletteBuffer);

		paletteTextureMap.put("palette_" + paletteId, combinedPaletteTextureId);
	}

	/**
	 * Render a pre-cached pattern at the given coordinates using the specified
	 * palette.
	 */
	public void renderPattern(PatternDesc desc, int x, int y) {
		Integer patternTextureId = patternTextureMap.get("pattern_" + desc.getPatternIndex());
		Integer paletteTextureId = paletteTextureMap.get("palette_" + desc.getPaletteIndex());

		if (patternTextureId == null || paletteTextureId == null) {
			System.err.println("Pattern or Palette not cached. Pattern: " + desc.getPatternIndex() + ", Palette: "
					+ desc.getPaletteIndex());
			return;
		}

		// Try batched rendering for better performance
		// Only use batching if enabled, batch is active, and pattern was successfully
		// added
		boolean usedBatch = false;
		if (batchingEnabled && batchedRenderer != null && batchedRenderer.isBatchActive()) {
			usedBatch = batchedRenderer.addPattern(patternTextureId, desc.getPaletteIndex(), desc, x, y);
		}

		if (!usedBatch) {
			// Fallback to individual commands
			PatternRenderCommand command = new PatternRenderCommand(patternTextureId, paletteTextureId, desc, x, y);
			registerCommand(command);
		}
	}

	/**
	 * Begin a new pattern batch. Call before rendering patterns for a frame/layer.
	 */
	public void beginPatternBatch() {
		if (batchedRenderer == null) {
			batchedRenderer = BatchedPatternRenderer.getInstance();
		}
		batchedRenderer.beginBatch();
	}

	/**
	 * Flush the current pattern batch. Call after all patterns for a layer are
	 * submitted. This queues the batch command for execution in the proper order.
	 */
	public void flushPatternBatch() {
		if (batchedRenderer != null) {
			// Always call endBatch to reset batchActive state, even if batch is empty
			GLCommandable batchCommand = batchedRenderer.endBatch();
			if (batchCommand != null) {
				registerCommand(batchCommand);
			}
		}
	}

	/**
	 * Enable or disable pattern batching.
	 */
	public void setBatchingEnabled(boolean enabled) {
		this.batchingEnabled = enabled;
	}

	public boolean isBatchingEnabled() {
		return batchingEnabled;
	}

	/**
	 * Get the combined palette texture ID.
	 */
	public Integer getCombinedPaletteTextureId() {
		return combinedPaletteTextureId;
	}

	/**
	 * Get the texture ID for a cached pattern.
	 */
	public Integer getPatternTextureId(int patternIndex) {
		return patternTextureMap.get("pattern_" + patternIndex);
	}

	/**
	 * Cleanup method to delete textures and release resources.
	 */
	public void cleanup() {
		// Delete pattern textures
		for (int textureId : patternTextureMap.values()) {
			graphics.glDeleteTextures(1, new int[] { textureId }, 0);
		}
		// Delete palette textures
		for (int textureId : new java.util.HashSet<>(paletteTextureMap.values())) {
			graphics.glDeleteTextures(1, new int[] { textureId }, 0);
		}
		// Cleanup shader program
		if (shaderProgram != null) {
			shaderProgram.cleanup(graphics);
		}
		if (debugShaderProgram != null) {
			debugShaderProgram.cleanup(graphics);
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

	public ShaderProgram getDebugShaderProgram() {
		return debugShaderProgram;
	}

	public GL2 getGraphics() {
		return graphics;
	}

	public void enqueueDebugLineState() {
		ShaderProgram debugShader = getDebugShaderProgram();
		int programId = debugShader != null ? debugShader.getProgramId() : 0;
		registerCommand(new GLCommand(GLCommand.CommandType.USE_PROGRAM, programId));
		registerCommand(new GLCommand(GLCommand.CommandType.DISABLE, GL2.GL_TEXTURE_2D));
		registerCommand(new GLCommand(GLCommand.CommandType.DISABLE, GL2.GL_LIGHTING));
		registerCommand(new GLCommand(GLCommand.CommandType.DISABLE, GL2.GL_COLOR_MATERIAL));
		registerCommand(new GLCommand(GLCommand.CommandType.DISABLE, GL2.GL_DEPTH_TEST));
	}

	public void enqueueDefaultShaderState() {
		registerCommand(new GLCommand(GLCommand.CommandType.ENABLE, GL2.GL_TEXTURE_2D));
		ShaderProgram shader = getShaderProgram();
		if (shader != null) {
			int programId = shader.getProgramId();
			if (programId != 0) {
				registerCommand(new GLCommand(GLCommand.CommandType.USE_PROGRAM, programId));
			}
		}
	}
}
