package uk.co.jamesj999.sonic.graphics;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.pipeline.UiRenderPipeline;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.level.render.BackgroundRenderer;

import static uk.co.jamesj999.sonic.level.LevelConstants.*;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GraphicsManager {
	private static final Logger LOGGER = Logger.getLogger(GraphicsManager.class.getName());

	private static GraphicsManager graphicsManager;
	List<GLCommandable> commands = new ArrayList<>();

	private final Map<String, Integer> paletteTextureMap = new HashMap<>(); // Map for palette textures
	private Integer combinedPaletteTextureId;
	private PatternAtlas patternAtlas;

	private static final int ATLAS_WIDTH = 1024;
	private static final int ATLAS_HEIGHT = 1024;

	private final Camera camera = Camera.getInstance();
	private GL2 graphics;
	private ShaderProgram shaderProgram;
	private ShaderProgram defaultShaderProgram;
	private WaterShaderProgram waterShaderProgram;
	private ShaderProgram currentShaderProgram;

	private ShaderProgram debugShaderProgram;
	private ShaderProgram fadeShaderProgram;
	private ShaderProgram shadowShaderProgram;
	private static final String DEBUG_SHADER_PATH = "shaders/shader_debug_color.glsl";
	private static final String PARALLAX_SHADER_PATH = "shaders/shader_parallax_bg.glsl";
	private static final String FADE_SHADER_PATH = "shaders/shader_fade.glsl";
	private static final String SHADOW_SHADER_PATH = "shaders/shader_shadow.glsl";
	private static final String WATER_SHADER_PATH = "shaders/shader_water.glsl";
	private static final String TILEMAP_SHADER_PATH = "shaders/shader_tilemap.glsl";

	// Background renderer for per-scanline parallax scrolling
	private BackgroundRenderer backgroundRenderer;
	private TilemapGpuRenderer tilemapGpuRenderer;

	// Fade manager for screen transitions
	private FadeManager fadeManager;

	// Unified UI render pipeline for overlay + fade ordering
	private UiRenderPipeline uiRenderPipeline;

	// Batched rendering support
	private boolean batchingEnabled = true;
	private BatchedPatternRenderer batchedRenderer;

	/**
	 * Headless mode flag. When true, GL operations are skipped.
	 * This enables testing game logic without requiring an OpenGL context.
	 */
	private boolean headlessMode = false;

	/**
	 * When true, the batch renderer will use the underwater palette texture
	 * instead of the normal palette texture. Used for background rendering
	 * when Sonic is underwater (original game behavior).
	 */
	private boolean useUnderwaterPaletteForBackground = false;

	public void registerCommand(GLCommandable command) {
		commands.add(command);
	}

	/**
	 * Initialize the GraphicsManager with shader loading.
	 */
	public void init(GL2 gl, String pixelShaderPath) throws IOException {
		if (headlessMode) {
			return;
		}
		this.graphics = gl;
		this.patternAtlas = new PatternAtlas(ATLAS_WIDTH, ATLAS_HEIGHT);
		this.patternAtlas.init(gl);
		this.defaultShaderProgram = new ShaderProgram(gl, pixelShaderPath); // Load default shader
		this.defaultShaderProgram.cacheUniformLocations(gl);

		this.waterShaderProgram = new WaterShaderProgram(gl, WATER_SHADER_PATH); // Load water shader
		this.waterShaderProgram.cacheUniformLocations(gl);

		this.currentShaderProgram = this.defaultShaderProgram; // Start with default
		this.shaderProgram = this.currentShaderProgram; // Compatibility
		this.debugShaderProgram = new ShaderProgram(gl, DEBUG_SHADER_PATH);
		this.fadeShaderProgram = new ShaderProgram(gl, FADE_SHADER_PATH);
		this.shadowShaderProgram = new ShaderProgram(gl, SHADOW_SHADER_PATH);
		this.shadowShaderProgram.cacheUniformLocations(gl);
		this.tilemapGpuRenderer = new TilemapGpuRenderer();
		this.tilemapGpuRenderer.init(gl, TILEMAP_SHADER_PATH);

		// Initialize fade manager with shader
		this.fadeManager = FadeManager.getInstance();
		this.fadeManager.setFadeShader(this.fadeShaderProgram);

		// Initialize unified UI render pipeline
		this.uiRenderPipeline = new UiRenderPipeline(this);
		this.uiRenderPipeline.setFadeManager(this.fadeManager);
	}

	/**
	 * Initialize the GraphicsManager in headless mode (no GL context).
	 * Use this for testing game logic without rendering.
	 */
	public void initHeadless() {
		this.headlessMode = true;
		this.graphics = null;
		if (this.patternAtlas == null) {
			this.patternAtlas = new PatternAtlas(ATLAS_WIDTH, ATLAS_HEIGHT);
		}
		this.tilemapGpuRenderer = null;
	}

	/**
	 * Check if running in headless mode.
	 */
	public boolean isHeadlessMode() {
		return headlessMode;
	}

	/**
	 * Set headless mode. Should be called before init().
	 */
	public void setHeadlessMode(boolean headless) {
		this.headlessMode = headless;
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
		flushWithCamera(camera.getX(), camera.getY(), camera.getWidth(), camera.getHeight());
	}

	/**
	 * Flush all registered commands with a specific camera position.
	 * Use this for screen-space rendering by passing (0, 0) for camera position.
	 */
	public void flushWithCamera(short cameraX, short cameraY, short cameraWidth, short cameraHeight) {
		if (headlessMode || commands.isEmpty() || graphics == null) {
			commands.clear();
			return;
		}

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
	 * Flush all registered commands in screen-space (camera at 0,0).
	 * Used for overlays like title cards and results screens.
	 */
	public void flushScreenSpace() {
		flushWithCamera((short) 0, (short) 0, camera.getWidth(), camera.getHeight());
	}

	/**
	 * Reset OpenGL state for fixed-function rendering.
	 * Call this between shader-based and fixed-function rendering phases.
	 */
	public void resetForFixedFunction() {
		if (headlessMode || graphics == null) {
			return;
		}
		// Ensure no shader is active
		graphics.glUseProgram(0);
		// Reset texture state
		graphics.glActiveTexture(GL2.GL_TEXTURE0);
		graphics.glBindTexture(GL2.GL_TEXTURE_2D, 0);
		graphics.glActiveTexture(GL2.GL_TEXTURE1);
		graphics.glBindTexture(GL2.GL_TEXTURE_2D, 0);
		graphics.glActiveTexture(GL2.GL_TEXTURE0);
		// Disable texturing for solid color drawing
		graphics.glDisable(GL2.GL_TEXTURE_2D);
		// Reset color to white
		graphics.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
		// Reset matrix
		graphics.glMatrixMode(GL2.GL_MODELVIEW);
		graphics.glLoadIdentity();
	}

	/**
	 * Cache a pattern texture (contains color indices) in the GPU.
	 */
	public void cachePatternTexture(Pattern pattern, int patternId) {
		ensurePatternAtlas();
		if (headlessMode || graphics == null) {
			patternAtlas.cachePattern(null, pattern, patternId);
			return;
		}
		patternAtlas.cachePattern(graphics, pattern, patternId);
	}

	public void updatePatternTexture(Pattern pattern, int patternId) {
		ensurePatternAtlas();
		if (headlessMode || graphics == null) {
			patternAtlas.updatePattern(null, pattern, patternId);
			return;
		}
		patternAtlas.updatePattern(graphics, pattern, patternId);
	}

	public void cachePaletteTexture(Palette palette, int paletteId) {
		if (headlessMode) {
			// In headless mode, just record that the palette was cached
			paletteTextureMap.put("palette_" + paletteId, -1);
			return;
		}
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
		renderPatternWithId(desc.getPatternIndex(), desc, x, y);
	}

	/**
	 * Render a pattern using an explicit pattern ID for texture lookup.
	 * This allows using pattern IDs beyond the 11-bit limit of PatternDesc.
	 */
	public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
		if (headlessMode) {
			return;
		}
		ensurePatternAtlas();
		PatternAtlas.Entry entry = patternAtlas != null ? patternAtlas.getEntry(patternId) : null;

		Integer paletteTextureId;
		if (useUnderwaterPaletteForBackground && underwaterPaletteTextureId != null) {
			paletteTextureId = underwaterPaletteTextureId;
		} else {
			paletteTextureId = combinedPaletteTextureId;
		}

		if (entry == null || paletteTextureId == null) {
			System.err.println("Pattern or Palette not cached. Pattern: " + patternId + ", Palette: "
					+ desc.getPaletteIndex());
			return;
		}

		// Try batched rendering for better performance
		// Only use batching if enabled, batch is active, and pattern was successfully
		// added
		boolean usedBatch = false;
		if (batchingEnabled && batchedRenderer != null && batchedRenderer.isBatchActive()) {
			usedBatch = batchedRenderer.addPattern(entry, desc.getPaletteIndex(), desc, x, y);
		}

		if (!usedBatch) {
			// Fallback to individual commands
			PatternRenderCommand command = new PatternRenderCommand(entry, paletteTextureId, desc, x, y);
			registerCommand(command);
		}
	}

	/**
	 * Render a pattern as a 2-scanline strip for special stage track rendering.
	 *
	 * The Sonic 2 special stage uses per-scanline horizontal scroll to create
	 * a pseudo-3D halfpipe effect where each 8x8 tile appears as 4 strips of
	 * 2 scanlines each. This method renders a single strip (8 wide × 2 high).
	 *
	 * @param patternId  The pattern texture ID
	 * @param desc       The pattern descriptor (handles H/V flip and palette)
	 * @param x          Screen X position
	 * @param y          Screen Y position of this strip
	 * @param stripIndex Which strip to render (0-3, where 0 is top of original
	 *                   tile)
	 */
	public void renderStripPatternWithId(int patternId, PatternDesc desc, int x, int y, int stripIndex) {
		if (headlessMode) {
			return;
		}
		ensurePatternAtlas();
		PatternAtlas.Entry entry = patternAtlas != null ? patternAtlas.getEntry(patternId) : null;
		if (entry == null || combinedPaletteTextureId == null) {
			return;
		}

		// Only use batched rendering for strip patterns
		if (batchingEnabled && batchedRenderer != null && batchedRenderer.isBatchActive()) {
			batchedRenderer.addStripPattern(entry, desc.getPaletteIndex(), desc, x, y, stripIndex);
		}
	}

	/**
	 * Begin a new pattern batch. Call before rendering patterns for a frame/layer.
	 */
	public void beginPatternBatch() {
		if (headlessMode) {
			return;
		}
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
		if (headlessMode) {
			return;
		}
		if (batchedRenderer != null) {
			// Always call endBatch to reset batchActive state, even if batch is empty
			GLCommandable batchCommand = batchedRenderer.endBatch();
			if (batchCommand != null) {
				registerCommand(batchCommand);
			}
		}
	}

	/**
	 * Begin a new shadow batch. Shadow batches use VDP shadow/highlight mode
	 * where palette index 14 darkens the background.
	 */
	public void beginShadowBatch() {
		if (headlessMode) {
			return;
		}
		if (batchedRenderer == null) {
			batchedRenderer = BatchedPatternRenderer.getInstance();
		}
		batchedRenderer.beginShadowBatch();
	}

	/**
	 * Add a shadow pattern to the current shadow batch.
	 */
	public void addShadowPattern(int patternIndex, PatternDesc desc, int x, int y) {
		if (headlessMode) {
			return;
		}
		ensurePatternAtlas();
		PatternAtlas.Entry entry = patternAtlas != null ? patternAtlas.getEntry(patternIndex) : null;
		if (entry == null) {
			return;
		}
		if (batchedRenderer != null && batchedRenderer.isShadowBatchActive()) {
			batchedRenderer.addShadowPattern(entry, desc, x, y);
		}
	}

	/**
	 * Flush the current shadow batch. This queues the shadow command for
	 * execution with multiplicative blending.
	 */
	public void flushShadowBatch() {
		if (headlessMode) {
			return;
		}
		if (batchedRenderer != null) {
			GLCommandable batchCommand = batchedRenderer.endShadowBatch();
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
		return patternAtlas != null ? patternAtlas.getTextureId() : null;
	}

	public Integer getPatternAtlasTextureId() {
		return patternAtlas != null ? patternAtlas.getTextureId() : null;
	}

	public int getPatternAtlasWidth() {
		return patternAtlas != null ? patternAtlas.getAtlasWidth() : 0;
	}

	public int getPatternAtlasHeight() {
		return patternAtlas != null ? patternAtlas.getAtlasHeight() : 0;
	}

	public PatternAtlas.Entry getPatternAtlasEntry(int patternId) {
		ensurePatternAtlas();
		return patternAtlas != null ? patternAtlas.getEntry(patternId) : null;
	}

	private Integer underwaterPaletteTextureId;

	public Integer getUnderwaterPaletteTextureId() {
		return underwaterPaletteTextureId;
	}

	public void cacheUnderwaterPaletteTexture(Palette[] palettes) {
		if (headlessMode)
			return;

		if (underwaterPaletteTextureId == null) {
			underwaterPaletteTextureId = glGenTexture();
			graphics.glBindTexture(GL2.GL_TEXTURE_2D, underwaterPaletteTextureId);
			graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
			graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP_TO_EDGE);
			graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
			graphics.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);
		}

		// Upload 64 colors (16x4)
		ByteBuffer paletteBuffer = GLBuffers.newDirectByteBuffer(64 * 4); // 64 cols * 4 bytes (RGBA)

		for (int pIndex = 0; pIndex < 4; pIndex++) {
			Palette p = (palettes != null && pIndex < palettes.length) ? palettes[pIndex] : null;
			for (int i = 0; i < 16; i++) {
				try {
					if (p != null) {
						Palette.Color color = p.getColor(i);
						paletteBuffer.put((byte) Byte.toUnsignedInt(color.r));
						paletteBuffer.put((byte) Byte.toUnsignedInt(color.g));
						paletteBuffer.put((byte) Byte.toUnsignedInt(color.b));
						if (i == 0) {
							paletteBuffer.put((byte) 0);
						} else {
							paletteBuffer.put((byte) 255);
						}
					} else {
						// Empty/Black for missing palette lines
						paletteBuffer.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
					}
				} catch (Exception e) {
					// Fallback
					paletteBuffer.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
				}
			}
		}
		paletteBuffer.flip();

		graphics.glBindTexture(GL2.GL_TEXTURE_2D, underwaterPaletteTextureId);
		graphics.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RGBA, 16, 4, 0, GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE,
				paletteBuffer);
	}

	/**
	 * Cleanup method to delete textures and release resources.
	 */
	public void cleanup() {
		if (headlessMode || graphics == null) {
			// In headless mode, just clear the tracking maps
			if (patternAtlas != null) {
				patternAtlas.cleanup(null);
			}
			BatchedPatternRenderer existingBatch = BatchedPatternRenderer.getInstanceIfInitialized();
			if (existingBatch != null) {
				existingBatch.cleanup(null);
			}
			paletteTextureMap.clear();
			combinedPaletteTextureId = null;
			return;
		}
		// Delete pattern atlas texture
		if (patternAtlas != null) {
			patternAtlas.cleanup(graphics);
		}
		// Delete palette textures
		for (int textureId : new java.util.HashSet<>(paletteTextureMap.values())) {
			graphics.glDeleteTextures(1, new int[] { textureId }, 0);
		}
		// Cleanup shader program
		if (defaultShaderProgram != null) {
			defaultShaderProgram.cleanup(graphics);
		}
		if (waterShaderProgram != null) {
			waterShaderProgram.cleanup(graphics);
		}
		if (debugShaderProgram != null) {
			debugShaderProgram.cleanup(graphics);
		}
		if (fadeShaderProgram != null) {
			fadeShaderProgram.cleanup(graphics);
		}
		if (shadowShaderProgram != null) {
			shadowShaderProgram.cleanup(graphics);
		}
		if (tilemapGpuRenderer != null) {
			tilemapGpuRenderer.cleanup(graphics);
		}
		BatchedPatternRenderer existingBatch = BatchedPatternRenderer.getInstanceIfInitialized();
		if (existingBatch != null) {
			existingBatch.cleanup(graphics);
		}
		// Reset fade manager
		if (fadeManager != null) {
			fadeManager.cleanup(graphics);
			fadeManager.cancel();
			fadeManager = null;
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

	private void ensurePatternAtlas() {
		if (patternAtlas == null) {
			patternAtlas = new PatternAtlas(ATLAS_WIDTH, ATLAS_HEIGHT);
		}
		if (!patternAtlas.isInitialized() && graphics != null) {
			patternAtlas.init(graphics);
		}
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

	/**
	 * Reset the singleton instance. Used for testing.
	 */
	public static synchronized void resetInstance() {
		if (graphicsManager != null) {
			graphicsManager.cleanup();
			graphicsManager = null;
		}
	}

	public ShaderProgram getShaderProgram() {
		return currentShaderProgram;
	}

	public WaterShaderProgram getWaterShaderProgram() {
		return waterShaderProgram;
	}

	public void setUseWaterShader(boolean use) {
		if (use) {
			currentShaderProgram = waterShaderProgram;
		} else {
			currentShaderProgram = defaultShaderProgram;
		}
		this.shaderProgram = currentShaderProgram;
	}

	/**
	 * Sets whether to use the underwater palette for background rendering.
	 * When true, all patterns rendered will use the underwater palette instead of
	 * the normal palette.
	 * This mirrors the original game's behavior where the entire background changes
	 * palette
	 * when Sonic is underwater.
	 */
	public void setUseUnderwaterPaletteForBackground(boolean use) {
		this.useUnderwaterPaletteForBackground = use;
	}

	/**
	 * Returns whether the underwater palette should be used for background
	 * rendering.
	 */
	public boolean isUseUnderwaterPaletteForBackground() {
		return useUnderwaterPaletteForBackground;
	}

	public ShaderProgram getDebugShaderProgram() {
		return debugShaderProgram;
	}

	public ShaderProgram getFadeShaderProgram() {
		return fadeShaderProgram;
	}

	public TilemapGpuRenderer getTilemapGpuRenderer() {
		return tilemapGpuRenderer;
	}

	public ShaderProgram getShadowShaderProgram() {
		return shadowShaderProgram;
	}

	/**
	 * Get the fade manager for screen transitions.
	 */
	public FadeManager getFadeManager() {
		if (fadeManager == null) {
			fadeManager = FadeManager.getInstance();
			if (fadeShaderProgram != null) {
				fadeManager.setFadeShader(fadeShaderProgram);
			}
		}
		return fadeManager;
	}

	public GL2 getGraphics() {
		return graphics;
	}

	/**
	 * Get the background renderer for shader-based parallax scrolling.
	 * Initializes it lazily on first access.
	 */
	public BackgroundRenderer getBackgroundRenderer() {
		if (headlessMode) {
			return null;
		}
		if (backgroundRenderer == null && graphics != null) {
			try {
				backgroundRenderer = new BackgroundRenderer();
				backgroundRenderer.init(graphics, PARALLAX_SHADER_PATH);
				LOGGER.info("BackgroundRenderer initialized for shader-based parallax.");
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, "Failed to initialize BackgroundRenderer", e);
			}
		}
		return backgroundRenderer;
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

	/**
	 * Enables scissor test with the specified rectangle.
	 * Coordinates are in OpenGL screen space (Y=0 at bottom).
	 *
	 * @param x      Left edge of scissor rectangle
	 * @param y      Bottom edge of scissor rectangle
	 * @param width  Width of scissor rectangle
	 * @param height Height of scissor rectangle
	 */
	public void enableScissor(int x, int y, int width, int height) {
		if (headlessMode || graphics == null)
			return;
		graphics.glScissor(x, y, width, height);
		graphics.glEnable(GL2.GL_SCISSOR_TEST);
	}

	/**
	 * Disables scissor test.
	 */
	public void disableScissor() {
		if (headlessMode || graphics == null)
			return;
		graphics.glDisable(GL2.GL_SCISSOR_TEST);
	}

	/**
	 * Get the unified UI render pipeline for overlay + fade ordering.
	 */
	public UiRenderPipeline getUiRenderPipeline() {
		return uiRenderPipeline;
	}
}
