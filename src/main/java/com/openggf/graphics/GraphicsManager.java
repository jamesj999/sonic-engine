package com.openggf.graphics;

import com.openggf.Engine;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.graphics.color.DisplayColorConverter;
import com.openggf.graphics.color.DisplayColorProfile;
import com.openggf.graphics.pipeline.UiRenderPipeline;
import com.openggf.graphics.shaderlib.DisplayShaderPipeline;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.render.BackgroundRenderer;
import com.openggf.level.render.SpritePieceRenderer;

import static com.openggf.level.LevelConstants.*;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;

import java.util.Queue;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GraphicsManager {
	private static final Logger LOGGER = Logger.getLogger(GraphicsManager.class.getName());

	interface UnderwaterPaletteUploadOps {
		int createTexture();

		void configureTexture(int textureId);

		void uploadTexture(int textureId, int totalLines, ByteBuffer rgbaBytes);
	}

	private static final UnderwaterPaletteUploadOps OPEN_GL_UNDERWATER_PALETTE_UPLOAD_OPS =
			new UnderwaterPaletteUploadOps() {
				@Override
				public int createTexture() {
					return glGenTextures();
				}

				@Override
				public void configureTexture(int textureId) {
					glBindTexture(GL_TEXTURE_2D, textureId);
					glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
					glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
					glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
					glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
				}

				@Override
				public void uploadTexture(int textureId, int totalLines, ByteBuffer rgbaBytes) {
					glBindTexture(GL_TEXTURE_2D, textureId);
					glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 16, totalLines, 0,
							GL_RGBA, GL_UNSIGNED_BYTE, rgbaBytes);
				}
			};

	private static GraphicsManager graphicsManager;
	List<GLCommandable> commands = new ArrayList<>();
	private List<GLCommandable> commandCaptureTarget;
	// Pool of reusable capture lists for executeCapturedCommands() (stack so
	// nested captures don't share a list). Lists are cleared before pooling.
	private final java.util.ArrayDeque<List<GLCommandable>> captureListPool = new java.util.ArrayDeque<>(2);
	private final Queue<PendingRenderThreadTask<?>> pendingRenderThreadTasks = new ConcurrentLinkedQueue<>();

	private final Map<String, Integer> paletteTextureMap = new HashMap<>(); // Map for palette textures
	// Last palette handed to cachePaletteTexture per line, so a palette fade change
	// can re-upload the affected lines without asking their owners to write again.
	private PaletteView[] lastCachedPaletteLines = new PaletteView[0];
	private final PaletteFadePresentation paletteFadePresentation = new PaletteFadePresentation();
	private Integer combinedPaletteTextureId;
	private int currentPaletteTextureHeight = 0;
	private DisplayColorProfile displayColorProfile = DisplayColorProfile.RAW_RGB;
	private PatternAtlas patternAtlas;
	private com.openggf.debug.PerformanceProfiler profiler;
	// Lazily allocated to avoid LWJGL native library loading in headless tests
	private ByteBuffer paletteUploadBuffer;
	private ByteBuffer underwaterPaletteUploadBuffer;
	private byte[] underwaterPaletteContentKey;
	private int underwaterPaletteContentKeyLength;
	private int pendingUnderwaterPaletteContentKeyLength;
	private int underwaterPaletteContentKeyWriteIndex;
	private boolean underwaterPaletteContentKeyChanged;
	private boolean underwaterPaletteContentKeyValid;
	private Object[] underwaterPaletteRowSources;
	private byte[] underwaterPaletteRowCases;
	private byte[] underwaterPaletteSourceRgb;
	private byte[][] underwaterDerivedRowKeys;
	private int[][] underwaterDerivedRowRgba;
	private int underwaterDerivedRowRecomputeCount;
	private UnderwaterPaletteUploadOps underwaterPaletteUploadOps = OPEN_GL_UNDERWATER_PALETTE_UPLOAD_OPS;
	private static final byte UNDERWATER_ROW_ABSENT = 0;
	private static final byte UNDERWATER_ROW_DIRECT = 1;
	private static final byte UNDERWATER_ROW_DERIVED = 2;
	private static final byte UNDERWATER_BASE_NORMAL = 3;
	private static final byte UNDERWATER_BASE_SHIFTED = 4;

	private static final int ATLAS_WIDTH = 1024;
	private static final int ATLAS_HEIGHT = 1024;

	// Lazily fetched to avoid initialization chain issues in headless tests
	private Camera camera;
	private Camera bootstrapCamera;
	private boolean glInitialized = false;
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
	private static final String BASIC_VERTEX_SHADER_PATH = "shaders/shader_basic.vert";
	private static final String DEBUG_VERTEX_SHADER_PATH = "shaders/shader_debug_color.vert";
	private static final String INSTANCED_VERTEX_SHADER_PATH = "shaders/shader_instanced.vert";
	private static final String SPRITE_PRIORITY_SHADER_PATH = "shaders/shader_sprite_priority.glsl";

	// Sprite priority rendering for ROM-accurate sprite-to-tile layering
	private SpritePriorityShaderProgram spritePriorityShaderProgram;
	private TilePriorityFBO tilePriorityFBO;

	// Sprite priority shader mode flags
	private boolean useSpritePriorityShader = false;
	private boolean currentSpriteHighPriority = false;
	private int currentSpriteTileOcclusionPaletteMask = 0xF;
	private boolean ghostRenderEffectActive = false;
	private float ghostRenderAlpha = 1.0f;
	private boolean spriteSatCollectionActive = false;
	private boolean spriteMaskRequested = false;
	private final List<SpriteSatEntry> spriteSatEntries = new ArrayList<>();
	// Reusable buffers/scratch for endSpriteSatCollectionAndReplay() — avoids per-frame
	// ArrayList and PatternDesc allocations on the SAT replay hot path.
	private final ArrayList<PatternRenderCommand> reusableReplayCommands = new ArrayList<>();
	private final PatternDesc reusableReplayDesc = new PatternDesc();
	// True while replaySpriteSatEntriesBatched has an instanced batch open.
	private boolean satReplayBatchOpen = false;
	private String currentSpriteSatDebugSource = null;
	private int currentSpriteSatBucket = RenderPriority.MIN;
	// Background renderer for per-scanline parallax scrolling
	private BackgroundRenderer backgroundRenderer;
	private TilemapGpuRenderer tilemapGpuRenderer;
	private InstancedPatternRenderer instancedPatternRenderer;

	// Fade manager for screen transitions
	private FadeManager fadeManager;
	private FadeManager bootstrapFadeManager;

	// Unified UI render pipeline for overlay + fade ordering
	private UiRenderPipeline uiRenderPipeline;
	private DisplayShaderPipeline displayShaderPipeline;

	// Batched rendering support
	private boolean batchingEnabled = true;
	private BatchedPatternRenderer batchedRenderer;
	private boolean instancedBatchingEnabled = true;
	private boolean instancedBatchActive = false;

	// Vertical wrap Y adjustment for sprite/object rendering.
	// When enabled, adjusts world Y coordinates passed to renderPattern/renderPatternWithId
	// to account for vertical level wrapping (LZ3/SBZ2). Emulates VDP modular sprite Y
	// which naturally wraps coordinates, preventing objects from vanishing at wrap boundaries.
	private boolean verticalWrapAdjustEnabled = false;
	private int verticalWrapRange = 0;
	private int verticalWrapCameraY = 0;

	/**
	 * Reference to the Engine for accessing projection matrix.
	 */
	private Engine engine;

	public void setPerformanceProfiler(com.openggf.debug.PerformanceProfiler profiler) {
		this.profiler = profiler;
	}

	/**
	 * Projection matrix buffer for shader-based rendering.
	 * Can be set directly by tests or other code that doesn't have an Engine instance.
	 */
	private float[] projectionMatrixBuffer;

	/** Reusable JOML matrix for safe-area projection computation (avoids per-call allocation). */
	private final org.joml.Matrix4f safeAreaMatrix = new org.joml.Matrix4f();
	/** Reusable float buffer to receive the safe-area matrix for shader upload. */
	private final float[] safeAreaBuffer = new float[16];

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

	// Cached viewport dimensions to avoid glGetIntegerv(GL_VIEWPORT) every batch.
	// Updated when Engine.reshape() is called.
	private int viewportX = 0;
	private int viewportY = 0;
	private int viewportWidth = 320;
	private int viewportHeight = 224;

	// Cached SCREEN_HEIGHT_PIXELS config value used by PatternRenderCommand's
	// per-obtain() display-height resolution. Invalidated on reshape (setViewport)
	// and resetState so config changes are picked up; <= 0 means "not cached".
	private int cachedConfigScreenHeightPx = -1;

	// Projection-space width: the coordinate-space width of the full viewport.
	private int projectionWidth = 320;

	// Water-related state for sprite priority shader underwater palette support.
	// These values are set by LevelManager.updateWaterShaderState() each frame.
	private float waterlineScreenY = 0.0f;
	private float windowHeight = 224.0f;
	private float screenHeight = 224.0f;
	private boolean waterEnabled = false;

	public void registerCommand(GLCommandable command) {
		if (commandCaptureTarget != null) {
			commandCaptureTarget.add(command);
			return;
		}
		commands.add(command);
	}

	public void executeCapturedCommands(Runnable producer, int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
		List<GLCommandable> previousCaptureTarget = commandCaptureTarget;
		// Reuse capture lists via a small pool (a stack, so nested captures each
		// get their own list); returned to the pool cleared in the finally block.
		List<GLCommandable> capturedCommands = captureListPool.pollLast();
		if (capturedCommands == null) {
			capturedCommands = new ArrayList<>();
		}
		commandCaptureTarget = capturedCommands;
		int nextUnexecuted = 0;
		boolean frameStateActive = false;
		try {
			producer.run();
			if (headlessMode || capturedCommands.isEmpty() || !glInitialized) {
				return;
			}
			PatternRenderCommand.resetFrameState();
			frameStateActive = true;
			for (int i = 0, n = capturedCommands.size(); i < n; i++) {
				capturedCommands.get(i).execute(cameraX, cameraY, cameraWidth, cameraHeight);
				nextUnexecuted = i + 1;
			}
		} catch (RuntimeException | Error failure) {
			unwindCommands(capturedCommands, nextUnexecuted,
					cameraX, cameraY, cameraWidth, cameraHeight, failure);
			nextUnexecuted = capturedCommands.size();
			throw failure;
		} finally {
			if (frameStateActive) {
				cleanupPatternFrameState();
			}
			discardCommands(capturedCommands, nextUnexecuted);
			commandCaptureTarget = previousCaptureTarget;
			capturedCommands.clear();
			captureListPool.addLast(capturedCommands);
		}
	}

	void cleanupPatternFrameState() {
		PatternRenderCommand.cleanupFrameState(this);
	}

	public <T> CompletableFuture<T> submitRenderThreadTask(Callable<T> callable) {
		CompletableFuture<T> future = new CompletableFuture<>();
		pendingRenderThreadTasks.add(new PendingRenderThreadTask<>(callable, future));
		return future;
	}

	public void runPendingRenderThreadTasks() {
		PendingRenderThreadTask<?> task;
		while ((task = pendingRenderThreadTasks.poll()) != null) {
			task.run();
		}
	}

	private void clearPendingRenderThreadTasks() {
		PendingRenderThreadTask<?> task;
		while ((task = pendingRenderThreadTasks.poll()) != null) {
			task.cancel();
		}
	}

	public void renderPatternWithIdScaled(int patternId, PatternDesc desc, float x, float y, float width, float height) {
		if (headlessMode) {
			return;
		}

		ensurePatternAtlas();
		PatternAtlas.Entry entry = patternAtlas != null ? patternAtlas.getEntry(patternId) : null;

		Integer paletteTextureId = resolveEffectivePatternPaletteTextureId();

		if (entry == null || paletteTextureId == null) {
			return;
		}
		boolean restartInstanced = instancedBatchActive && instancedPatternRenderer != null;
		boolean restartBatched = !restartInstanced && batchedRenderer != null && batchedRenderer.isBatchActive();
		if (restartInstanced || restartBatched) {
			flushPatternBatch();
		}

		PatternRenderCommand command = PatternRenderCommand.obtain(entry, paletteTextureId, desc, x, y, width, height, this);
		registerCommand(command);
		restartPatternBatch(restartInstanced, restartBatched, 0);
	}

	/**
	 * Initialize the GraphicsManager with shader loading.
	 */
	public void init(String pixelShaderPath) throws IOException {
		if (headlessMode) {
			return;
		}
		this.glInitialized = true;
		this.patternAtlas = new PatternAtlas(ATLAS_WIDTH, ATLAS_HEIGHT, profiler);
		this.patternAtlas.init();
		this.defaultShaderProgram = new ShaderProgram(BASIC_VERTEX_SHADER_PATH, pixelShaderPath); // Load default shader
		this.defaultShaderProgram.cacheUniformLocations();

		this.waterShaderProgram = new WaterShaderProgram(BASIC_VERTEX_SHADER_PATH, WATER_SHADER_PATH); // Load water shader
		this.waterShaderProgram.cacheUniformLocations();

		this.currentShaderProgram = this.defaultShaderProgram; // Start with default
		this.shaderProgram = this.currentShaderProgram; // Compatibility
		this.debugShaderProgram = new ShaderProgram(DEBUG_VERTEX_SHADER_PATH, DEBUG_SHADER_PATH);
		this.fadeShaderProgram = new ShaderProgram(ShaderProgram.FULLSCREEN_VERTEX_SHADER, FADE_SHADER_PATH);
		this.shadowShaderProgram = new ShaderProgram(BASIC_VERTEX_SHADER_PATH, SHADOW_SHADER_PATH);
		this.shadowShaderProgram.cacheUniformLocations();
		SonicConfigurationService cfg = GameServices.configuration();
		this.tilemapGpuRenderer = new TilemapGpuRenderer(cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
		this.tilemapGpuRenderer.init(TILEMAP_SHADER_PATH);
		this.instancedPatternRenderer = new InstancedPatternRenderer(this, cfg);
		this.instancedPatternRenderer.init(INSTANCED_VERTEX_SHADER_PATH, pixelShaderPath, WATER_SHADER_PATH);

		ensureRuntimeManagedReferences();
		this.fadeManager.setFadeShader(this.fadeShaderProgram);

		// Initialize unified UI render pipeline
		this.uiRenderPipeline = new UiRenderPipeline(this);
		this.uiRenderPipeline.setFadeManager(this.fadeManager);
		this.displayShaderPipeline = new DisplayShaderPipeline();

		// Initialize sprite priority rendering system
		this.spritePriorityShaderProgram = new SpritePriorityShaderProgram(SPRITE_PRIORITY_SHADER_PATH);
		this.spritePriorityShaderProgram.cacheUniformLocations();
		this.tilePriorityFBO = new TilePriorityFBO();
		// FBO will be initialized when first needed with actual screen dimensions
	}

	/**
	 * Initialize the GraphicsManager in headless mode (no GL context).
	 * Use this for testing game logic without rendering.
	 */
	public void initHeadless() {
		this.headlessMode = true;
		this.glInitialized = false;
		if (this.patternAtlas == null) {
			this.patternAtlas = new PatternAtlas(ATLAS_WIDTH, ATLAS_HEIGHT, profiler);
		}
		this.tilemapGpuRenderer = null;
		this.instancedPatternRenderer = null;
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
	 * Mark the GL context as initialized.
	 */
	public void setGlInitialized(boolean initialized) {
		this.glInitialized = initialized;
	}

	/**
	 * Lazily get the Camera instance.
	 * This avoids triggering Camera singleton initialization during GraphicsManager construction.
	 */
	private Camera getCamera() {
		ensureRuntimeManagedReferences();
		return camera;
	}

	public void bindRuntimeManagedReferences(Camera runtimeCamera, FadeManager runtimeFadeManager) {
		camera = Objects.requireNonNull(runtimeCamera, "runtimeCamera");
		setActiveFadeManager(Objects.requireNonNull(runtimeFadeManager, "runtimeFadeManager"));
	}

	public void clearRuntimeManagedReferences() {
		if (camera != null || bootstrapCamera != null) {
			camera = getOrCreateBootstrapCamera();
		}
		if (uiRenderPipeline != null) {
			uiRenderPipeline.setHudRenderManager(null);
		}
		if (fadeManager != null || bootstrapFadeManager != null || uiRenderPipeline != null) {
			setActiveFadeManager(getOrCreateBootstrapFadeManager());
		}
	}

	private void ensureRuntimeManagedReferences() {
		if (camera == null) {
			camera = getOrCreateBootstrapCamera();
		}
		if (fadeManager == null) {
			setActiveFadeManager(getOrCreateBootstrapFadeManager());
		}
	}

	private void setActiveFadeManager(FadeManager resolvedFadeManager) {
		if (fadeManager != resolvedFadeManager) {
			fadeManager = resolvedFadeManager;
			if (fadeShaderProgram != null) {
				fadeManager.setFadeShader(fadeShaderProgram);
			}
			if (uiRenderPipeline != null) {
				uiRenderPipeline.setFadeManager(fadeManager);
			}
		}
	}

	private Camera getOrCreateBootstrapCamera() {
		if (bootstrapCamera == null) {
			bootstrapCamera = new Camera(GameServices.configuration());
		}
		return bootstrapCamera;
	}

	private FadeManager getOrCreateBootstrapFadeManager() {
		if (bootstrapFadeManager == null) {
			bootstrapFadeManager = new FadeManager();
		}
		return bootstrapFadeManager;
	}

	/**
	 * Lazily allocate the palette upload buffer.
	 * This avoids triggering LWJGL native library loading during GraphicsManager construction.
	 */
	private ByteBuffer ensurePaletteUploadBuffer() {
		if (paletteUploadBuffer == null) {
			paletteUploadBuffer = MemoryUtil.memAlloc(COLORS_PER_PALETTE * 4);
		}
		return paletteUploadBuffer;
	}

	/**
	 * Lazily allocate the underwater palette upload buffer.
	 * This avoids triggering LWJGL native library loading during GraphicsManager construction.
	 */
	private ByteBuffer ensureUnderwaterPaletteUploadBuffer(int requiredCapacity) {
		if (underwaterPaletteUploadBuffer == null) {
			underwaterPaletteUploadBuffer = MemoryUtil.memAlloc(requiredCapacity);
		} else if (underwaterPaletteUploadBuffer.capacity() < requiredCapacity) {
			underwaterPaletteUploadBuffer = MemoryUtil.memRealloc(underwaterPaletteUploadBuffer, requiredCapacity);
		}
		return underwaterPaletteUploadBuffer;
	}

	public void setDisplayColorProfile(DisplayColorProfile displayColorProfile) {
		this.displayColorProfile = displayColorProfile != null ? displayColorProfile : DisplayColorProfile.RAW_RGB;
	}

	public DisplayColorProfile getDisplayColorProfile() {
		return displayColorProfile;
	}

	void writePaletteColor(ByteBuffer buffer, int r, int g, int b, int colorIndex) {
		DisplayColorConverter.writeRgbBytes(r, g, b, displayColorProfile, buffer);
		buffer.put((byte) (colorIndex == 0 ? 0 : 255));
	}

	int[] paletteUploadRgbaForTest(int r, int g, int b, int colorIndex) {
		ByteBuffer buffer = ByteBuffer.allocate(4);
		writePaletteColor(buffer, r, g, b, colorIndex);
		buffer.flip();
		return new int[] {
				Byte.toUnsignedInt(buffer.get()),
				Byte.toUnsignedInt(buffer.get()),
				Byte.toUnsignedInt(buffer.get()),
				Byte.toUnsignedInt(buffer.get())
		};
	}

	/**
	 * Flush all registered commands.
	 * Uses shake-adjusted camera positions so sprites shake in sync with FG tiles.
	 */
	public void flush() {
		Camera cam = getCamera();
		flushWithCamera(cam.getXWithShake(), cam.getYWithShake(), cam.getWidth(), cam.getHeight());
	}

	/**
	 * Flush all registered commands with a specific camera position.
	 * Use this for screen-space rendering by passing (0, 0) for camera position.
	 */
	public void flushWithCamera(short cameraX, short cameraY, short cameraWidth, short cameraHeight) {
		if (headlessMode || commands.isEmpty() || !glInitialized) {
			discardCommands(commands, 0);
			commands.clear();
			return;
		}

		// Reset pattern render state for new batch of commands
		PatternRenderCommand.resetFrameState();
		int nextUnexecuted = 0;
		try {
			for (int i = 0, n = commands.size(); i < n; i++) {
				commands.get(i).execute(cameraX, cameraY, cameraWidth, cameraHeight);
				nextUnexecuted = i + 1;
			}
		} catch (RuntimeException | Error failure) {
			unwindCommands(commands, nextUnexecuted,
					cameraX, cameraY, cameraWidth, cameraHeight, failure);
			nextUnexecuted = commands.size();
			throw failure;
		} finally {
			// Cleanup pattern render state even if a custom command throws.
			PatternRenderCommand.cleanupFrameState(this);
			discardCommands(commands, nextUnexecuted);
			commands.clear();
		}
	}

	private static void discardCommands(List<? extends GLCommandable> queued, int fromIndex) {
		for (int i = Math.max(0, fromIndex), n = queued.size(); i < n; i++) {
			queued.get(i).discard();
		}
	}

	private static void unwindCommands(List<? extends GLCommandable> queued, int fromIndex,
			int cameraX, int cameraY, int cameraWidth, int cameraHeight, Throwable failure) {
		for (int i = Math.max(0, fromIndex), n = queued.size(); i < n; i++) {
			try {
				queued.get(i).unwindAfterFailure(cameraX, cameraY, cameraWidth, cameraHeight);
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
		}
	}

	/**
	 * Flush all registered commands in screen-space (camera at 0,0).
	 * Used for overlays like title cards and results screens.
	 */
	public void flushScreenSpace() {
		Camera cam = getCamera();
		flushWithCamera((short) 0, (short) 0, cam.getWidth(), cam.getHeight());
	}

	/**
	 * Reset OpenGL state for shader-based rendering.
	 * Call this between different rendering phases to ensure clean state.
	 * Note: Fixed-function calls (glDisable(GL_TEXTURE_2D), glMatrixMode, etc.)
	 * have been removed for OpenGL 4.1 core profile compatibility.
	 */
	public void resetForFixedFunction() {
		if (headlessMode || !glInitialized) {
			return;
		}
		// Ensure no shader is active
		glUseProgram(0);
		// Reset texture state
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * Cache a pattern texture (contains color indices) in the GPU.
	 */
	public void cachePatternTexture(Pattern pattern, int patternId) {
		ensurePatternAtlas();
		if (headlessMode || !glInitialized) {
			patternAtlas.cachePatternHeadless(pattern, patternId);
			return;
		}
		patternAtlas.cachePattern(pattern, patternId);
	}

	public void updatePatternTexture(Pattern pattern, int patternId) {
		ensurePatternAtlas();
		if (headlessMode || !glInitialized) {
			patternAtlas.updatePatternHeadless(pattern, patternId);
			return;
		}
		patternAtlas.updatePattern(pattern, patternId);
	}

	/**
	 * Re-uploads only the patterns whose indices are set in the dirty BitSet.
	 * Used by MutableLevel dirty-region processing to incrementally update
	 * the GPU pattern atlas after editor mutations.
	 *
	 * @param dirtyIndices BitSet of pattern indices that changed
	 * @param level the current level (provides pattern data)
	 */
	public void reuploadDirtyPatterns(java.util.BitSet dirtyIndices,
									  com.openggf.level.Level level) {
		if (headlessMode || !glInitialized) return;
		for (int i = dirtyIndices.nextSetBit(0); i >= 0;
			 i = dirtyIndices.nextSetBit(i + 1)) {
			if (i < level.getPatternCount()) {
				updatePatternTexture(level.getPattern(i), i);
			}
		}
	}

	/**
	 * Begin batching pattern atlas uploads. While active, individual
	 * {@code cachePatternTexture} calls write to a CPU-side buffer only.
	 * Call {@link #endPatternAtlasBatch()} to upload everything in one GL call.
	 */
	public void beginPatternAtlasBatch() {
		if (headlessMode || !glInitialized || patternAtlas == null) {
			return;
		}
		patternAtlas.beginBatch();
	}

	/**
	 * Flush the batched pattern atlas uploads to the GPU.
	 */
	public void endPatternAtlasBatch() {
		if (headlessMode || !glInitialized || patternAtlas == null) {
			return;
		}
		patternAtlas.endBatch();
	}

	/**
	 * Remove a pattern from the atlas cache.
	 * This causes the renderer to skip this pattern (getEntry returns null).
	 * Used by CNZ slot machine to clear the tilemap at VRAM 0x0550-0x057F
	 * so the shader overlay can render slot faces there.
	 *
	 * @param patternId The pattern ID to uncache
	 * @return true if the pattern was uncached, false if it wasn't in the cache
	 */
	public boolean uncachePattern(int patternId) {
		if (patternAtlas == null) {
			return false;
		}
		return patternAtlas.removeEntry(patternId);
	}

	/**
	 * Create an alias so that one pattern ID renders the same as another.
	 * This is useful for making multiple pattern IDs render as transparent
	 * by aliasing them to pattern 0 (which is typically all-transparent).
	 * No additional atlas slots are allocated.
	 *
	 * @param aliasId The pattern ID to create as an alias
	 * @param targetId The existing pattern ID to alias to
	 * @return true if the alias was created, false if target doesn't exist
	 */
	public boolean aliasPattern(int aliasId, int targetId) {
		if (patternAtlas == null) {
			return false;
		}
		return patternAtlas.aliasEntry(aliasId, targetId);
	}

	public void cachePaletteTexture(Palette palette, int paletteId) {
		rememberCachedPaletteLine(palette, paletteId);
		uploadPaletteLine(palette, paletteId);
	}

	private void uploadPaletteLine(PaletteView palette, int paletteId) {
		if (headlessMode) {
			// In headless mode, just record that the palette was cached
			paletteTextureMap.put("palette_" + paletteId, -1);
			return;
		}
		int requiredHeight = RenderContext.getTotalPaletteLines();
		if (combinedPaletteTextureId == null) {
			combinedPaletteTextureId = glGenTextures();
			currentPaletteTextureHeight = requiredHeight;
			ByteBuffer emptyBuffer = MemoryUtil.memAlloc(COLORS_PER_PALETTE * 4 * requiredHeight);
			try {
				glBindTexture(GL_TEXTURE_2D, combinedPaletteTextureId);
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 16, requiredHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, emptyBuffer);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			} finally {
				MemoryUtil.memFree(emptyBuffer);
			}
		} else if (requiredHeight > currentPaletteTextureHeight) {
			// Texture needs to grow to accommodate new contexts.
			// Read back existing palette data before replacing the texture so that
			// level palettes (lines 0-3) are not wiped by the resize.
			int oldBytes = COLORS_PER_PALETTE * 4 * currentPaletteTextureHeight;
			ByteBuffer oldData = MemoryUtil.memAlloc(oldBytes);
			try {
				glBindTexture(GL_TEXTURE_2D, combinedPaletteTextureId);
				glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, oldData);
				currentPaletteTextureHeight = requiredHeight;
				ByteBuffer newBuffer = MemoryUtil.memAlloc(COLORS_PER_PALETTE * 4 * requiredHeight);
				try {
					oldData.rewind();
					newBuffer.put(oldData);
					while (newBuffer.hasRemaining()) {
						newBuffer.put((byte) 0);
					}
					newBuffer.flip();
					glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 16, requiredHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, newBuffer);
				} finally {
					MemoryUtil.memFree(newBuffer);
				}
			} finally {
				MemoryUtil.memFree(oldData);
			}
		}

		ByteBuffer paletteBuffer = ensurePaletteUploadBuffer();
		paletteBuffer.clear();
		// An active palette fade owns what CRAM receives for its lines (PalFadeIn_Alt
		// rebuilds v_palette from v_palette_fading); the owner's palette is untouched.
		boolean fadeLine = paletteFadePresentation.affects(paletteId);
		for (int i = 0; i < COLORS_PER_PALETTE; i++) {
			int r = Byte.toUnsignedInt(palette.red(i));
			int g = Byte.toUnsignedInt(palette.green(i));
			int b = Byte.toUnsignedInt(palette.blue(i));
			if (fadeLine) {
				int faded = paletteFadePresentation.fadeRgb(r, g, b);
				r = (faded >>> 16) & 0xFF;
				g = (faded >>> 8) & 0xFF;
				b = faded & 0xFF;
			}
			writePaletteColor(paletteBuffer, r, g, b, i);
		}
		paletteBuffer.flip();

		glBindTexture(GL_TEXTURE_2D, combinedPaletteTextureId);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, paletteId, 16, 1, GL_RGBA, GL_UNSIGNED_BYTE, paletteBuffer);

		paletteTextureMap.put("palette_" + paletteId, combinedPaletteTextureId);
	}

	private void rememberCachedPaletteLine(PaletteView palette, int paletteId) {
		if (paletteId < 0 || paletteId >= 64) {
			return;
		}
		if (paletteId >= lastCachedPaletteLines.length) {
			lastCachedPaletteLines = java.util.Arrays.copyOf(lastCachedPaletteLines, paletteId + 1);
		}
		lastCachedPaletteLines[paletteId] = palette;
	}

	/** The palette fade currently applied to CRAM uploads; see {@link PaletteFadePresentation}. */
	public PaletteFadePresentation getPaletteFadePresentation() {
		return paletteFadePresentation;
	}

	/**
	 * Applies a Mega Drive palette fade step to every later upload of the masked
	 * lines and immediately re-uploads the lines already cached, so the change is
	 * visible on the frame that requests it regardless of whether their owners
	 * write again. {@code steps} counts the FadeIn_AddColor / FadeOut_DecColor
	 * passes applied so far.
	 */
	public void setPaletteFadePresentation(PaletteFadePresentation.Mode mode, int steps, int lineMask) {
		int previousMask = paletteFadePresentation.lineMask();
		if (paletteFadePresentation.set(mode, steps, lineMask)) {
			reuploadPaletteFadeLines(previousMask | paletteFadePresentation.lineMask());
		}
	}

	/** Ends the palette fade and re-uploads the lines it was transforming from their owners' palettes. */
	public void clearPaletteFadePresentation() {
		int previousMask = paletteFadePresentation.lineMask();
		if (paletteFadePresentation.clear()) {
			reuploadPaletteFadeLines(previousMask);
		}
	}

	private void reuploadPaletteFadeLines(int lineMask) {
		for (int line = 0; line < lastCachedPaletteLines.length; line++) {
			if ((lineMask & (1 << line)) != 0 && lastCachedPaletteLines[line] != null) {
				uploadPaletteLine(lastCachedPaletteLines[line], line);
			}
		}
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

		// Vertical wrap Y adjustment (emulates VDP modular sprite Y coordinates).
		// When enabled, wraps the world Y to the nearest equivalent position within
		// VERTICAL_WRAP_RANGE of the camera, so objects on the "wrong side" of a
		// wrap boundary render at the correct screen position.
		if (verticalWrapAdjustEnabled && verticalWrapRange > 0) {
			int diff = y - verticalWrapCameraY;
			diff = ((diff % verticalWrapRange) + verticalWrapRange) % verticalWrapRange;
			if (diff > verticalWrapRange / 2) {
				diff -= verticalWrapRange;
			}
			y = verticalWrapCameraY + diff;
		}

		ensurePatternAtlas();
		PatternAtlas.Entry entry = patternAtlas != null ? patternAtlas.getEntry(patternId) : null;

		Integer paletteTextureId = resolveEffectivePatternPaletteTextureId();

		if (entry == null) {
			return;
		}
		if (paletteTextureId == null) {
			return;
		}
		// Try batched rendering for better performance
		// Only use batching if enabled, batch is active, and pattern was successfully added
		boolean usedBatch = false;
		boolean restartBatchedAfterFallback = false;
		int paletteIndex = desc.getPaletteIndex();
		if (batchingEnabled && instancedBatchActive && instancedPatternRenderer != null) {
			usedBatch = instancedPatternRenderer.addPattern(entry, paletteIndex, desc, x, y);
			if (!usedBatch) {
				flushPatternBatch();
				restartPatternBatch(true, false, entry.atlasIndex());
				usedBatch = instancedPatternRenderer.addPattern(entry, desc.getPaletteIndex(), desc, x, y);
			}
		} else if (batchingEnabled && batchedRenderer != null && batchedRenderer.isBatchActive()) {
			if (entry.atlasIndex() == 0) {
				usedBatch = batchedRenderer.addPattern(entry, paletteIndex, desc, x, y);
			}
			if (!usedBatch) {
				// A direct fallback must be ordered after already staged page-0 geometry.
				restartBatchedAfterFallback = true;
				flushPatternBatch();
			}
		}

		if (!usedBatch) {
			// Fallback to individual commands (use pooled allocation)
			PatternRenderCommand command = PatternRenderCommand.obtain(entry, paletteTextureId, desc, x, y, this);
			registerCommand(command);
			if (restartBatchedAfterFallback) {
				restartPatternBatch(false, true, 0);
			}
		}
	}

	public void beginGhostRenderEffect(float alpha) {
		this.ghostRenderEffectActive = true;
		this.ghostRenderAlpha = Math.max(0.0f, Math.min(1.0f, alpha));
	}

	public void endGhostRenderEffect() {
		this.ghostRenderEffectActive = false;
		this.ghostRenderAlpha = 1.0f;
	}

	public boolean isGhostRenderEffectActive() {
		return ghostRenderEffectActive;
	}

	public float getGhostRenderAlpha() {
		return ghostRenderAlpha;
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
		Integer paletteTextureId = resolveEffectivePatternPaletteTextureId();
		if (entry == null || paletteTextureId == null) {
			return;
		}

		// Only use batched rendering for strip patterns.
		int paletteIndex = desc.getPaletteIndex();
		boolean restartInstancedAfterDirect = false;
		boolean restartBatchedAfterDirect = false;
		if (batchingEnabled && instancedBatchActive && instancedPatternRenderer != null) {
			boolean added = instancedPatternRenderer.addStripPattern(entry, paletteIndex, desc, x, y, stripIndex);
			if (!added) {
				flushPatternBatch();
				restartPatternBatch(true, false, entry.atlasIndex());
				added = instancedPatternRenderer.addStripPattern(entry, desc.getPaletteIndex(), desc, x, y, stripIndex);
			}
			if (!added) {
				flushPatternBatch();
				restartInstancedAfterDirect = true;
			} else {
				return;
			}
		} else if (batchingEnabled && batchedRenderer != null && batchedRenderer.isBatchActive()) {
			boolean added = entry.atlasIndex() == 0
					&& batchedRenderer.addStripPattern(entry, paletteIndex, desc, x, y, stripIndex);
			if (added) {
				return;
			}
			flushPatternBatch();
			if (entry.atlasIndex() == 0) {
				restartPatternBatch(false, true, 0);
				if (batchedRenderer.addStripPattern(entry, paletteIndex, desc, x, y, stripIndex)) {
					return;
				}
				flushPatternBatch();
			}
			restartBatchedAfterDirect = true;
		}
		PatternRenderCommand command = PatternRenderCommand.obtain(entry, paletteTextureId,
				desc, x, y, 8f, 2f, this);
		command.resolveStripTextureCoordinates(entry, stripIndex);
		registerCommand(command);
		restartPatternBatch(restartInstancedAfterDirect, restartBatchedAfterDirect, entry.atlasIndex());
	}

	private Integer resolveEffectivePatternPaletteTextureId() {
		if (useUnderwaterPaletteForBackground && underwaterPaletteTextureId != null) {
			return underwaterPaletteTextureId;
		}
		return combinedPaletteTextureId;
	}

	private void restartPatternBatch(boolean instanced, boolean batched, int atlasIndex) {
		if (instanced && instancedPatternRenderer != null) {
			instancedPatternRenderer.beginBatch(atlasIndex);
			instancedBatchActive = true;
		} else if (batched && batchedRenderer != null) {
			batchedRenderer.beginBatch();
		}
	}

	/**
	 * Begin a new pattern batch. Call before rendering patterns for a frame/layer.
	 */
	public void beginPatternBatch() {
		if (headlessMode) {
			return;
		}
		if (!batchingEnabled) {
			return;
		}
		if (instancedBatchingEnabled && instancedPatternRenderer != null && instancedPatternRenderer.isSupported()) {
			instancedPatternRenderer.beginBatch();
			instancedBatchActive = true;
			return;
		}
		if (batchedRenderer == null) {
			batchedRenderer = new BatchedPatternRenderer(this, GameServices.configuration());
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
		if (instancedBatchActive && instancedPatternRenderer != null) {
			GLCommandable batchCommand = instancedPatternRenderer.endBatch();
			if (batchCommand != null) {
				registerCommand(batchCommand);
			}
			instancedBatchActive = false;
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
			batchedRenderer = new BatchedPatternRenderer(this, GameServices.configuration());
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
			if (!batchedRenderer.addShadowPattern(entry, desc, x, y)) {
				flushShadowBatch();
				batchedRenderer.beginShadowBatch(entry.atlasIndex());
				if (!batchedRenderer.addShadowPattern(entry, desc, x, y)) {
					throw new IllegalStateException("Unable to add shadow pattern to a fresh batch");
				}
			}
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

	public void setInstancedBatchingEnabled(boolean enabled) {
		this.instancedBatchingEnabled = enabled;
	}

	public boolean isInstancedBatchingEnabled() {
		return instancedBatchingEnabled;
	}

	/**
	 * Enables vertical wrap Y adjustment for sprite/object rendering.
	 * While enabled, Y coordinates passed to renderPattern/renderPatternWithId
	 * are adjusted to the nearest equivalent position modulo the wrap range,
	 * emulating the Mega Drive VDP's modular sprite coordinate system.
	 * <p>
	 * Call this BEFORE rendering sprite/object batches in vertically-wrapping zones,
	 * and call {@link #disableVerticalWrapAdjust()} afterwards to avoid affecting
	 * HUD or other non-wrapping renders.
	 *
	 * @param range   The vertical wrap range in pixels (e.g. 2048)
	 * @param cameraY The current camera Y position
	 */
	public void enableVerticalWrapAdjust(int range, int cameraY) {
		this.verticalWrapAdjustEnabled = true;
		this.verticalWrapRange = range;
		this.verticalWrapCameraY = cameraY;
	}

	/**
	 * Disables vertical wrap Y adjustment.
	 */
	public void disableVerticalWrapAdjust() {
		this.verticalWrapAdjustEnabled = false;
	}

	/**
	 * Get the combined palette texture ID.
	 */
	public Integer getCombinedPaletteTextureId() {
		return combinedPaletteTextureId;
	}

	/**
	 * Get the texture ID for a cached pattern.
	 * @param patternIndex the pattern ID to look up
	 * @return the texture ID, -1 in headless mode when entry exists, or null if not cached
	 */
	public Integer getPatternTextureId(int patternIndex) {
		if (patternAtlas == null) {
			return null;
		}
		PatternAtlas.Entry entry = patternAtlas.getEntry(patternIndex);
		if (entry == null) {
			return null;  // Pattern not cached
		}
		int textureId = patternAtlas.getTextureId(entry.atlasIndex());
		// In headless mode, textureId is 0, return -1 as sentinel
		return textureId == 0 && headlessMode ? -1 : textureId;
	}

	public Integer getPatternAtlasTextureId() {
		return patternAtlas != null ? patternAtlas.getTextureId() : null;
	}

	public Integer getPatternAtlasTextureId(int atlasIndex) {
		return patternAtlas != null ? patternAtlas.getTextureId(atlasIndex) : null;
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

	public PatternAtlas getPatternAtlas() {
		return patternAtlas;
	}

	/**
	 * Update cached viewport dimensions. Call this from Engine.reshape().
	 * Avoids expensive glGetIntegerv(GL_VIEWPORT) calls every batch.
	 */
	public void setViewport(int x, int y, int width, int height) {
		this.viewportX = x;
		this.viewportY = y;
		this.viewportWidth = width;
		this.viewportHeight = height;
		// Reshape may follow a config change; re-resolve the configured screen height lazily.
		this.cachedConfigScreenHeightPx = -1;
	}

	/**
	 * Returns the configured logical screen height (SCREEN_HEIGHT_PIXELS), cached
	 * to avoid a config-service lookup per rendered pattern. The cache is
	 * invalidated by {@link #setViewport} (called from Engine.reshape()) and
	 * {@link #resetState()}.
	 */
	public int getConfiguredScreenHeightPx() {
		int cached = cachedConfigScreenHeightPx;
		if (cached <= 0) {
			cached = GameServices.configuration().getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
			cachedConfigScreenHeightPx = cached;
		}
		return cached;
	}

	public int getViewportX() {
		return viewportX;
	}

	public int getViewportY() {
		return viewportY;
	}

	public int getViewportWidth() {
		return viewportWidth;
	}

	public int getViewportHeight() {
		return viewportHeight;
	}

	public void setProjectionWidth(int width) {
		this.projectionWidth = Math.max(320, width);
	}

	public int getProjectionWidth() {
		return projectionWidth;
	}

	/**
	 * Get the current waterline Y position in screen coordinates (pixels from top).
	 * Returns a negative value if there's no water in the current zone.
	 */
	public float getWaterlineScreenY() {
		return waterlineScreenY;
	}

	/**
	 * Set the waterline Y position for sprite priority shader underwater palette.
	 * Called by LevelManager.updateWaterShaderState() each frame.
	 *
	 * @param y Screen Y position where water starts (negative to disable)
	 */
	public void setWaterlineScreenY(float y) {
		this.waterlineScreenY = y;
	}

	/**
	 * Get the physical window height in pixels.
	 */
	public float getWindowHeight() {
		return windowHeight;
	}

	/**
	 * Set the physical window height for sprite priority shader.
	 * Called by LevelManager.updateWaterShaderState() each frame.
	 */
	public void setWindowHeight(float height) {
		this.windowHeight = height;
	}

	/**
	 * Get the logical screen height (e.g., 224 for Genesis).
	 */
	public float getScreenHeight() {
		return screenHeight;
	}

	/**
	 * Set the logical screen height for sprite priority shader.
	 * Called by LevelManager.updateWaterShaderState() each frame.
	 */
	public void setScreenHeight(float height) {
		this.screenHeight = height;
	}

	/**
	 * Check if water is enabled for the current zone.
	 */
	public boolean isWaterEnabled() {
		return waterEnabled;
	}

	/**
	 * Set whether water is enabled for sprite priority shader.
	 * Called by LevelManager.updateWaterShaderState() each frame.
	 */
	public void setWaterEnabled(boolean enabled) {
		this.waterEnabled = enabled;
	}

	private Integer underwaterPaletteTextureId;

	public Integer getUnderwaterPaletteTextureId() {
		return underwaterPaletteTextureId;
	}

	/**
	 * Drops all cached palette texture state while leaving pattern/shader state intact.
	 * Use this when changing games through non-pattern-rendered screens so the next
	 * game cannot render a frame with another game's stale palette rows.
	 */
	public void clearPaletteTextures() {
		paletteTextureMap.clear();
		if (!headlessMode && glInitialized) {
			if (combinedPaletteTextureId != null) {
				glDeleteTextures(combinedPaletteTextureId);
			}
			if (underwaterPaletteTextureId != null) {
				glDeleteTextures(underwaterPaletteTextureId);
			}
		}
		combinedPaletteTextureId = null;
		currentPaletteTextureHeight = 0;
		underwaterPaletteTextureId = null;
		useUnderwaterPaletteForBackground = false;
		if (paletteUploadBuffer != null) {
			MemoryUtil.memFree(paletteUploadBuffer);
			paletteUploadBuffer = null;
		}
		if (underwaterPaletteUploadBuffer != null) {
			MemoryUtil.memFree(underwaterPaletteUploadBuffer);
			underwaterPaletteUploadBuffer = null;
		}
		underwaterPaletteContentKey = null;
		underwaterPaletteContentKeyLength = 0;
		underwaterPaletteContentKeyValid = false;
		underwaterPaletteRowSources = null;
		underwaterPaletteRowCases = null;
		underwaterPaletteSourceRgb = null;
		underwaterDerivedRowKeys = null;
		underwaterDerivedRowRgba = null;
		underwaterDerivedRowRecomputeCount = 0;
	}

	void setUnderwaterPaletteUploadOps(UnderwaterPaletteUploadOps uploadOps) {
		underwaterPaletteUploadOps = Objects.requireNonNull(uploadOps);
	}

	public Integer cacheUnderwaterPaletteTexture(Palette[] palettes, Palette normalLine0) {
		if (headlessMode && underwaterPaletteUploadOps == OPEN_GL_UNDERWATER_PALETTE_UPLOAD_OPS) {
			return null;
		}
		int totalLines = RenderContext.getTotalPaletteLines();
		Palette underwaterLine0 = (palettes != null && palettes.length > 0) ? palettes[0] : null;
		ensureUnderwaterPaletteSourceScratch(totalLines);
		boolean hasDerivedDonorRow = false;
		for (int row = 0; row < totalLines; row++) {
			PaletteView source = palettes != null && row < palettes.length ? palettes[row] : null;
			byte rowCase = UNDERWATER_ROW_DIRECT;
			if (source == null) {
				rowCase = UNDERWATER_ROW_ABSENT;
				if (normalLine0 != null && underwaterLine0 != null) {
					source = RenderContext.getUnderwaterPaletteForEffectiveLine(row);
					if (source != null) {
						rowCase = UNDERWATER_ROW_DIRECT;
					} else {
						source = getRenderContextPaletteForEffectiveLine(row);
					}
					if (source != null && rowCase != UNDERWATER_ROW_DIRECT) {
						rowCase = UNDERWATER_ROW_DERIVED;
						hasDerivedDonorRow = true;
					}
				}
			}
			underwaterPaletteRowSources[row] = source;
			underwaterPaletteRowCases[row] = rowCase;
		}
		underwaterPaletteRowSources[totalLines] = hasDerivedDonorRow ? normalLine0 : null;
		underwaterPaletteRowSources[totalLines + 1] = hasDerivedDonorRow ? underwaterLine0 : null;

		underwaterPaletteContentKeyWriteIndex = 0;
		underwaterPaletteContentKeyChanged = !underwaterPaletteContentKeyValid;
		writeUnderwaterContentKeyInt(totalLines);
		writeUnderwaterContentKeyInt(displayColorProfile.ordinal());
		int sourceCount = totalLines + (hasDerivedDonorRow ? 2 : 0);
		for (int sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {
			byte sourceTag = sourceIndex < totalLines
					? underwaterPaletteRowCases[sourceIndex]
					: (sourceIndex == totalLines ? UNDERWATER_BASE_NORMAL : UNDERWATER_BASE_SHIFTED);
			writeUnderwaterContentKeyByte(sourceTag);
			PaletteView source = (PaletteView) underwaterPaletteRowSources[sourceIndex];
			if (source == null) {
				continue;
			}
			int rgbOffset = sourceIndex * 16 * 3;
			// Rows below totalLines are effective palette lines; a fade on one of them
			// (FadeIn_FromBlack also walks v_palette_water) transforms the composed
			// bytes, and the content key built from them changes with each fade step.
			boolean fadeRow = sourceIndex < totalLines && paletteFadePresentation.affects(sourceIndex);
			for (int colorIndex = 0; colorIndex < 16; colorIndex++) {
				byte r = source.red(colorIndex);
				byte g = source.green(colorIndex);
				byte b = source.blue(colorIndex);
				if (fadeRow) {
					int faded = paletteFadePresentation.fadeRgb(
							Byte.toUnsignedInt(r), Byte.toUnsignedInt(g), Byte.toUnsignedInt(b));
					r = (byte) (faded >>> 16);
					g = (byte) (faded >>> 8);
					b = (byte) faded;
				}
				underwaterPaletteSourceRgb[rgbOffset++] = r;
				underwaterPaletteSourceRgb[rgbOffset++] = g;
				underwaterPaletteSourceRgb[rgbOffset++] = b;
				writeUnderwaterContentKeyByte(r);
				writeUnderwaterContentKeyByte(g);
				writeUnderwaterContentKeyByte(b);
			}
		}
		pendingUnderwaterPaletteContentKeyLength = underwaterPaletteContentKeyWriteIndex;
		underwaterPaletteContentKeyChanged |= underwaterPaletteContentKeyLength
				!= pendingUnderwaterPaletteContentKeyLength;
		if (!underwaterPaletteContentKeyChanged) {
			return underwaterPaletteTextureId;
		}
		underwaterPaletteContentKeyValid = false;

		int ratioR = 256;
		int ratioG = 256;
		int ratioB = 256;
		if (hasDerivedDonorRow) {
			int normalOffset = totalLines * 16 * 3;
			int shiftedOffset = (totalLines + 1) * 16 * 3;
			long sumNR = 0;
			long sumNG = 0;
			long sumNB = 0;
			long sumUR = 0;
			long sumUG = 0;
			long sumUB = 0;
			int count = 0;
			for (int colorIndex = 1; colorIndex < 16; colorIndex++) {
				int colorOffset = colorIndex * 3;
				int nr = Byte.toUnsignedInt(underwaterPaletteSourceRgb[normalOffset + colorOffset]);
				int ng = Byte.toUnsignedInt(underwaterPaletteSourceRgb[normalOffset + colorOffset + 1]);
				int nb = Byte.toUnsignedInt(underwaterPaletteSourceRgb[normalOffset + colorOffset + 2]);
				if (nr + ng + nb > 0) {
					sumNR += nr;
					sumNG += ng;
					sumNB += nb;
					sumUR += Byte.toUnsignedInt(underwaterPaletteSourceRgb[shiftedOffset + colorOffset]);
					sumUG += Byte.toUnsignedInt(underwaterPaletteSourceRgb[shiftedOffset + colorOffset + 1]);
					sumUB += Byte.toUnsignedInt(underwaterPaletteSourceRgb[shiftedOffset + colorOffset + 2]);
					count++;
				}
			}
			if (count > 0) {
				if (sumNR > 0) ratioR = (int) (sumUR * 256 / sumNR);
				if (sumNG > 0) ratioG = (int) (sumUG * 256 / sumNG);
				if (sumNB > 0) ratioB = (int) (sumUB * 256 / sumNB);
			}
		}

		int bufferSize = 16 * totalLines * 4;
		ByteBuffer paletteBuffer = ensureUnderwaterPaletteUploadBuffer(bufferSize);
		paletteBuffer.clear();
		paletteBuffer.limit(bufferSize);
		for (int row = 0; row < totalLines; row++) {
			byte rowCase = underwaterPaletteRowCases[row];
			int rgbOffset = row * 16 * 3;
			if (rowCase == UNDERWATER_ROW_DERIVED) {
				int normalOffset = totalLines * 16 * 3;
				int shiftedOffset = (totalLines + 1) * 16 * 3;
				if (!underwaterDerivedRowKeyMatches(row, rgbOffset, normalOffset, shiftedOffset)) {
					updateUnderwaterDerivedRow(row, rgbOffset, normalOffset, shiftedOffset,
							ratioR, ratioG, ratioB);
				}
				for (int value : underwaterDerivedRowRgba[row]) {
					paletteBuffer.put((byte) value);
				}
				continue;
			}
			for (int colorIndex = 0; colorIndex < 16; colorIndex++) {
				if (rowCase == UNDERWATER_ROW_ABSENT) {
					paletteBuffer.putInt(0);
					continue;
				}
				int r = Byte.toUnsignedInt(underwaterPaletteSourceRgb[rgbOffset++]);
				int g = Byte.toUnsignedInt(underwaterPaletteSourceRgb[rgbOffset++]);
				int b = Byte.toUnsignedInt(underwaterPaletteSourceRgb[rgbOffset++]);
				writePaletteColor(paletteBuffer, r, g, b, colorIndex);
			}
		}
		paletteBuffer.flip();

		if (underwaterPaletteTextureId == null) {
			underwaterPaletteTextureId = underwaterPaletteUploadOps.createTexture();
			underwaterPaletteUploadOps.configureTexture(underwaterPaletteTextureId);
		}
		underwaterPaletteUploadOps.uploadTexture(underwaterPaletteTextureId, totalLines, paletteBuffer);
		underwaterPaletteContentKeyLength = pendingUnderwaterPaletteContentKeyLength;
		underwaterPaletteContentKeyValid = true;
		return underwaterPaletteTextureId;
	}

	private PaletteView getRenderContextPaletteForEffectiveLine(int effectiveLine) {
		for (RenderContext context : RenderContext.getDonorContexts()) {
			PaletteView palette = contextPaletteForEffectiveLine(context, effectiveLine);
			if (palette != null) return palette;
		}
		for (RenderContext context : RenderContext.getSidekickContexts()) {
			PaletteView palette = contextPaletteForEffectiveLine(context, effectiveLine);
			if (palette != null) return palette;
		}
		return null;
	}

	private PaletteView contextPaletteForEffectiveLine(RenderContext context, int effectiveLine) {
		int logicalLine = effectiveLine - context.getPaletteLineBase();
		return logicalLine >= 0 && logicalLine < RenderContext.LINES_PER_CONTEXT
				? context.getPalette(logicalLine)
				: null;
	}

	private void ensureUnderwaterPaletteSourceScratch(int totalLines) {
		int sourceSlots = totalLines + 2;
		if (underwaterPaletteRowSources == null || underwaterPaletteRowSources.length < sourceSlots) {
			underwaterPaletteRowSources = new Object[sourceSlots];
			underwaterPaletteRowCases = new byte[totalLines];
			underwaterPaletteSourceRgb = new byte[sourceSlots * 16 * 3];
		}
		if (underwaterDerivedRowKeys == null) {
			underwaterDerivedRowKeys = new byte[totalLines][];
			underwaterDerivedRowRgba = new int[totalLines][];
		} else if (underwaterDerivedRowKeys.length < totalLines) {
			underwaterDerivedRowKeys = Arrays.copyOf(underwaterDerivedRowKeys, totalLines);
			underwaterDerivedRowRgba = Arrays.copyOf(underwaterDerivedRowRgba, totalLines);
		}
	}

	private boolean underwaterDerivedRowKeyMatches(int row, int donorOffset,
			int normalOffset, int shiftedOffset) {
		byte[] key = underwaterDerivedRowKeys[row];
		if (key == null || key[0] != (byte) displayColorProfile.ordinal()) {
			return false;
		}
		return underwaterDerivedRowKeyRangeMatches(key, 1, donorOffset)
				&& underwaterDerivedRowKeyRangeMatches(key, 1 + 16 * 3, normalOffset)
				&& underwaterDerivedRowKeyRangeMatches(key, 1 + 2 * 16 * 3, shiftedOffset);
	}

	private boolean underwaterDerivedRowKeyRangeMatches(byte[] key, int keyOffset, int sourceOffset) {
		for (int i = 0; i < 16 * 3; i++) {
			if (key[keyOffset + i] != underwaterPaletteSourceRgb[sourceOffset + i]) {
				return false;
			}
		}
		return true;
	}

	private void updateUnderwaterDerivedRow(int row, int donorOffset,
			int normalOffset, int shiftedOffset, int ratioR, int ratioG, int ratioB) {
		byte[] key = underwaterDerivedRowKeys[row];
		if (key == null) {
			key = new byte[1 + 3 * 16 * 3];
			underwaterDerivedRowKeys[row] = key;
			underwaterDerivedRowRgba[row] = new int[16 * 4];
		}
		key[0] = (byte) displayColorProfile.ordinal();
		System.arraycopy(underwaterPaletteSourceRgb, donorOffset, key, 1, 16 * 3);
		System.arraycopy(underwaterPaletteSourceRgb, normalOffset, key, 1 + 16 * 3, 16 * 3);
		System.arraycopy(underwaterPaletteSourceRgb, shiftedOffset, key, 1 + 2 * 16 * 3, 16 * 3);

		int[] rgba = underwaterDerivedRowRgba[row];
		for (int colorIndex = 0; colorIndex < 16; colorIndex++) {
			int colorOffset = donorOffset + colorIndex * 3;
			int r = Math.min(255, Byte.toUnsignedInt(underwaterPaletteSourceRgb[colorOffset]) * ratioR / 256);
			int g = Math.min(255, Byte.toUnsignedInt(underwaterPaletteSourceRgb[colorOffset + 1]) * ratioG / 256);
			int b = Math.min(255, Byte.toUnsignedInt(underwaterPaletteSourceRgb[colorOffset + 2]) * ratioB / 256);
			int rgbaOffset = colorIndex * 4;
			DisplayColorConverter.writeRgbBytes(r, g, b, displayColorProfile, rgba, rgbaOffset);
			rgba[rgbaOffset + 3] = colorIndex == 0 ? 0 : 255;
		}
		underwaterDerivedRowRecomputeCount++;
	}

	int getUnderwaterDerivedRowRecomputeCount() {
		return underwaterDerivedRowRecomputeCount;
	}

	private void writeUnderwaterContentKeyInt(int value) {
		writeUnderwaterContentKeyByte((byte) (value >>> 24));
		writeUnderwaterContentKeyByte((byte) (value >>> 16));
		writeUnderwaterContentKeyByte((byte) (value >>> 8));
		writeUnderwaterContentKeyByte((byte) value);
	}

	private void writeUnderwaterContentKeyByte(byte value) {
		if (underwaterPaletteContentKey == null) {
			underwaterPaletteContentKey = new byte[256];
		} else if (underwaterPaletteContentKeyWriteIndex == underwaterPaletteContentKey.length) {
			underwaterPaletteContentKey = Arrays.copyOf(underwaterPaletteContentKey,
					underwaterPaletteContentKey.length * 2);
		}
		if (underwaterPaletteContentKeyWriteIndex >= underwaterPaletteContentKeyLength
				|| underwaterPaletteContentKey[underwaterPaletteContentKeyWriteIndex] != value) {
			underwaterPaletteContentKeyChanged = true;
		}
		underwaterPaletteContentKey[underwaterPaletteContentKeyWriteIndex++] = value;
	}

	/**
	 * Release GL resources owned by the level renderers, palette textures, and
	 * native upload buffers. Safe to call in any mode (headless guards internally).
	 */
	private void releasePerLevelResources() {
		paletteTextureMap.clear();
		if (!headlessMode && glInitialized) {
			if (backgroundRenderer != null) {
				backgroundRenderer.cleanup();
			}
			if (tilemapGpuRenderer != null) {
				tilemapGpuRenderer.cleanup();
			}
			if (instancedPatternRenderer != null) {
				instancedPatternRenderer.cleanup();
			}
		}
		backgroundRenderer = null;
		tilemapGpuRenderer = null;
		instancedPatternRenderer = null;
		clearPaletteTextures();
	}

	/**
	 * Resets the pattern atlas and palette textures without destroying shaders
	 * or the GL context.  Use after preview capture to discard all stale pattern
	 * data and palette state so subsequent rendering starts from a clean GPU.
	 */
	public void resetPatternAndPaletteState() {
		if (headlessMode || !glInitialized) {
			return;
		}
		if (patternAtlas != null) {
			patternAtlas.cleanup();
			patternAtlas = new PatternAtlas(ATLAS_WIDTH, ATLAS_HEIGHT, profiler);
			patternAtlas.init();
		}
		// Reset palette textures so they're rebuilt from scratch
		clearPaletteTextures();
		discardCommands(commands, 0);
		commands.clear();
	}

	/**
	 * Cleanup method to delete textures and release resources.
	 */
	public void cleanup() {
		clearPendingRenderThreadTasks();
		discardCommands(commands, 0);
		commands.clear();
		if (headlessMode || !glInitialized) {
			// In headless mode, just clear the tracking maps
			if (patternAtlas != null) {
				patternAtlas.cleanupHeadless();
			}
			if (batchedRenderer != null) {
				batchedRenderer.cleanupHeadless();
			}
			if (instancedPatternRenderer != null) {
				instancedPatternRenderer.cleanupHeadless();
			}
			clearPaletteTextures();
			PatternRenderCommand.cleanupHeadless();
			GLCommand.cleanupHeadless();
			GLCommandGroup.cleanup();
			return;
		}
		// Delete pattern atlas texture
		if (patternAtlas != null) {
			patternAtlas.cleanup();
		}
		// Cleanup shader programs
		if (defaultShaderProgram != null) {
			defaultShaderProgram.cleanup();
		}
		if (waterShaderProgram != null) {
			waterShaderProgram.cleanup();
		}
		if (debugShaderProgram != null) {
			debugShaderProgram.cleanup();
		}
		if (fadeShaderProgram != null) {
			fadeShaderProgram.cleanup();
		}
		if (shadowShaderProgram != null) {
			shadowShaderProgram.cleanup();
		}
		if (batchedRenderer != null) {
			batchedRenderer.cleanup();
		}
		// Sprite priority rendering cleanup
		if (spritePriorityShaderProgram != null) {
			spritePriorityShaderProgram.cleanup();
		}
		if (tilePriorityFBO != null) {
			tilePriorityFBO.cleanup();
		}
		// Reset fade manager
		if (fadeManager != null) {
			fadeManager.cleanup();
			fadeManager.cancel();
			fadeManager = null;
		}
		if (displayShaderPipeline != null) {
			displayShaderPipeline.dispose();
			displayShaderPipeline = null;
		}
		// Release renderers, palette textures, native buffers
		releasePerLevelResources();
		PatternRenderCommand.cleanup();
		GLCommand.cleanup();
		GLCommandGroup.cleanup();
		glInitialized = false;
	}

	private void ensurePatternAtlas() {
		if (patternAtlas == null) {
			patternAtlas = new PatternAtlas(ATLAS_WIDTH, ATLAS_HEIGHT, profiler);
		}
		if (!patternAtlas.isInitialized() && glInitialized) {
			patternAtlas.init();
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
	 * Resets mutable rendering state without destroying the singleton instance.
	 * Preserves headlessMode and glInitialized configuration.
	 * Clears render command queues and palette caches.
	 */
	public void resetState() {
		discardCommands(commands, 0);
		commands.clear();
		clearPendingRenderThreadTasks();
		releasePerLevelResources();
		camera = null;
		bootstrapCamera = null;
		fadeManager = null;
		bootstrapFadeManager = null;
		if (displayShaderPipeline != null) {
			displayShaderPipeline.dispose();
			displayShaderPipeline = null;
		}
		useUnderwaterPaletteForBackground = false;
		useSpritePriorityShader = false;
		currentSpriteHighPriority = false;
		currentSpriteTileOcclusionPaletteMask = 0xF;
		spriteSatCollectionActive = false;
		spriteMaskRequested = false;
		satReplayBatchOpen = false;
		spriteSatEntries.clear();
		currentSpriteSatDebugSource = null;
		waterlineScreenY = 0;
		windowHeight = 224;
		screenHeight = 224;
		waterEnabled = false;
		cachedConfigScreenHeightPx = -1;
		if (patternAtlas != null) {
			if (headlessMode) {
				patternAtlas.cleanupHeadless();
			} else {
				patternAtlas.cleanup();
			}
			patternAtlas = null;
		}
	}

	/**
	 * Destroys the singleton and releases all GL resources (shaders, FBOs, atlas).
	 * The next call to {@link #getInstance()} will create a fresh instance.
	 * <p>
	 * Use this only when full GL re-initialization is required (e.g. GPU tests
	 * that need clean shader/tilemap state). For normal test teardown, prefer
	 * {@link #resetState()} which preserves cached references.
	 */
	public static synchronized void destroyForReinit() {
		if (graphicsManager != null) {
			graphicsManager.cleanup();
			graphicsManager = null;
		}
	}

	public ShaderProgram getShaderProgram() {
		if (useSpritePriorityShader && spritePriorityShaderProgram != null) {
			return spritePriorityShaderProgram;
		}
		return currentShaderProgram;
	}

	/**
	 * Set the Engine reference for accessing projection matrix.
	 */
	public void setEngine(Engine engine) {
		this.engine = engine;
	}

	/**
	 * Get the Engine reference.
	 */
	public Engine getEngine() {
		return engine;
	}

	/**
	 * Set the projection matrix buffer directly.
	 * Use this when testing or when no Engine instance is available.
	 * The buffer should be a 16-element float array in column-major order.
	 */
	public void setProjectionMatrixBuffer(float[] buffer) {
		this.projectionMatrixBuffer = buffer;
	}

	/**
	 * Get the projection matrix buffer for shader-based rendering.
	 * First checks if a local buffer has been set, then falls back to Engine.
	 * @return the projection matrix as a 16-element float array, or null if not available
	 */
	public float[] getProjectionMatrixBuffer() {
		// First try local buffer (set directly by tests or other code)
		if (projectionMatrixBuffer != null) {
			return projectionMatrixBuffer;
		}
		// Fall back to engine reference
		if (engine != null) {
			return engine.getProjectionMatrixBuffer();
		}
		return null;
	}

	/**
	 * Push a centered-320 safe-area projection for the configured viewport width.
	 * At native width (320) the safe-area ortho equals the scene ortho [0, 320] — a no-op.
	 * <p>
	 * Callers MUST pair every call to this method with a call to
	 * {@link #endSafeAreaProjection()} before {@code UiRenderPipeline.renderFadePass()}
	 * so the fade pass runs at the full viewport projection, not the safe-area.
	 *
	 * @param viewportWidth       physical viewport width in pixels
	 * @param viewportHeightPixels physical viewport height in pixels
	 */
	public void beginSafeAreaProjection(int viewportWidth, int viewportHeightPixels) {
		safeAreaMatrix.identity().ortho2D(
				com.openggf.graphics.pipeline.SafeAreaProjection.orthoLeft(viewportWidth),
				com.openggf.graphics.pipeline.SafeAreaProjection.orthoRight(viewportWidth),
				0f, viewportHeightPixels);
		safeAreaMatrix.get(safeAreaBuffer);
		setProjectionMatrixBuffer(safeAreaBuffer);
	}

	/**
	 * Restore the engine's scene projection by clearing the local override.
	 * Must be called after safe-area UI drawing and BEFORE
	 * {@code UiRenderPipeline.renderFadePass()} so the fade runs at the full viewport.
	 */
	public void endSafeAreaProjection() {
		setProjectionMatrixBuffer(null);
	}

	/**
	 * Enable or disable sprite priority shader mode.
	 * When enabled, getShaderProgram() returns the sprite priority shader.
	 */
	public void setUseSpritePriorityShader(boolean use) {
		this.useSpritePriorityShader = use;
	}

	/**
	 * Check if sprite priority shader mode is enabled.
	 */
	public boolean isUseSpritePriorityShader() {
		return useSpritePriorityShader;
	}

	/**
	 * Set whether the current sprite being rendered has high priority.
	 * This is used by the sprite priority shader to determine if the sprite
	 * should appear above or behind high-priority tiles.
	 */
	public void setCurrentSpriteHighPriority(boolean highPriority) {
		this.currentSpriteHighPriority = highPriority;
		this.currentSpriteTileOcclusionPaletteMask = highPriority ? 0 : 0xF;
	}

	/**
	 * Get whether the current sprite being rendered has high priority.
	 */
	public boolean getCurrentSpriteHighPriority() {
		return currentSpriteHighPriority;
	}

	public void setCurrentSpriteTileOcclusionPaletteMask(int mask) {
		currentSpriteTileOcclusionPaletteMask = mask & 0xF;
		currentSpriteHighPriority = currentSpriteTileOcclusionPaletteMask == 0;
	}

	public int getCurrentSpriteTileOcclusionPaletteMask() {
		return currentSpriteTileOcclusionPaletteMask;
	}

	public void beginSpriteSatCollection() {
		spriteSatCollectionActive = true;
		spriteMaskRequested = false;
		spriteSatEntries.clear();
		currentSpriteSatDebugSource = null;
		currentSpriteSatBucket = RenderPriority.MIN;
	}

	public boolean isSpriteSatCollectionActive() {
		return spriteSatCollectionActive;
	}

	public void requestSpriteMask() {
		if (spriteSatCollectionActive) {
			spriteMaskRequested = true;
		}
	}

	public void setCurrentSpriteSatDebugSource(String debugSource) {
		currentSpriteSatDebugSource = debugSource;
	}

	public void setCurrentSpriteSatBucket(int bucket) {
		currentSpriteSatBucket = RenderPriority.clamp(bucket);
	}

	public void submitSpriteSatPiece(SpritePieceRenderer.PreparedPiece piece) {
		if (!spriteSatCollectionActive || piece == null) {
			return;
		}
		SpritePieceRenderer.PreparedPiece taggedPiece = currentSpriteSatDebugSource == null
				? piece
				: piece.withDebugSource(currentSpriteSatDebugSource);
		spriteSatEntries.add(SpriteSatEntry.fromPreparedPiece(taggedPiece, currentSpriteSatBucket));
	}

	public void endSpriteSatCollectionAndReplay() {
		if (!spriteSatCollectionActive) {
			return;
		}

		boolean applyMask = spriteMaskRequested;

		// processReusable(...) never retains the input reference: with masking on it
		// reuses thread-owned scratch; with masking off it returns
		// `spriteSatEntries` itself (no defensive copy). The replay below consumes
		// the processed list synchronously, so the live buffer is cleared in the
		// finally block only after the replay finished with it.
		List<SpriteSatEntry> processedEntries = SpriteSatMaskPostProcessor.processReusable(spriteSatEntries, applyMask);

		spriteSatCollectionActive = false;
		spriteMaskRequested = false;
		currentSpriteSatDebugSource = null;
		currentSpriteSatBucket = RenderPriority.MIN;

		try {
			if (processedEntries.isEmpty()) {
				return;
			}

			// The SAT replay must not re-enter renderPatternWithId(): that could merge the
			// carefully ordered SAT sequence into a still-open batch owned by another layer,
			// flattening or reordering it. Instead the replay owns its emission: a dedicated
			// instanced batch (flushed before any direct command so order is preserved, with
			// per-instance VDP priority carried in the instance data), or — when instanced
			// batching is unavailable — the original direct per-tile commands.
			flushPatternBatch();
			setCurrentSpriteHighPriority(false);
			if (canBatchSpriteSatReplay()) {
				replaySpriteSatEntriesBatched(processedEntries);
				return;
			}
			List<PatternRenderCommand> commands = buildSpriteSatReplayCommands(processedEntries);
			// `commands` is a reused buffer (`reusableReplayCommands`); copy via index iteration
			// without releasing the reference, then clear the buffer once registerCommand has
			// consumed each entry.
			for (int i = 0, n = commands.size(); i < n; i++) {
				registerCommand(commands.get(i));
			}
			reusableReplayCommands.clear();
		} finally {
			spriteSatEntries.clear();
		}
	}

	private boolean canBatchSpriteSatReplay() {
		return batchingEnabled
				&& instancedBatchingEnabled
				&& instancedPatternRenderer != null
				&& instancedPatternRenderer.isSupported()
				&& !instancedBatchActive;
	}

	/**
	 * Replays the processed SAT entries through dedicated instanced batches instead
	 * of one PatternRenderCommand (3 glBufferData + 1 draw) per 8x8 tile. Tiles are
	 * emitted in exactly the same bucket-major order as the direct path; within an
	 * instanced draw, instances render in submission order, and any tile that cannot
	 * join the batch (overflow atlas, batch full) flushes the open batch first so
	 * the final draw order matches the direct path's command order.
	 */
	private void replaySpriteSatEntriesBatched(List<SpriteSatEntry> processedEntries) {
		ensurePatternAtlas();
		Integer paletteTextureId = resolveSpriteSatReplayPaletteTextureId();
		if (paletteTextureId == null) {
			return;
		}
		// Parity with the direct path: its PatternRenderCommands resolve the shader at
		// flush time, when the sprite pass has already cleared useSpritePriorityShader,
		// so the replay renders with the plain shader. endBatch() captures the flag at
		// build time instead, so clear it for the duration of the replay batches.
		boolean savedUseSpritePriorityShader = useSpritePriorityShader;
		useSpritePriorityShader = false;
		try {
			satReplayBatchOpen = false;
			int paletteTexId = paletteTextureId;
			for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
				for (int i = 0, n = processedEntries.size(); i < n; i++) {
					SpriteSatEntry processedEntry = processedEntries.get(i);
					if (processedEntry.priorityBucket() == bucket) {
						appendBatchedReplayCommands(processedEntry, paletteTexId);
					}
				}
			}
			flushSatReplayBatch();
		} finally {
			useSpritePriorityShader = savedUseSpritePriorityShader;
			// An exception mid-replay must not strand an open instanced batch.
			instancedPatternRenderer.cancelBatch();
			satReplayBatchOpen = false;
		}
	}

	private void appendBatchedReplayCommands(SpriteSatEntry entry, int paletteTextureId) {
		SpritePieceRenderer.renderPreparedPiece(entry.toPreparedPiece(),
				(patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
					PatternAtlas.Entry atlasEntry = patternAtlas != null ? patternAtlas.getEntry(patternIndex) : null;
					if (atlasEntry == null) {
						return;
					}
					prepareReplayDesc(entry, patternIndex, pieceHFlip, pieceVFlip, paletteIndex);
					if (addToSatReplayBatch(atlasEntry, paletteIndex, drawX, drawY)) {
						return;
					}
					// Unsupported state: flush first so draw order is preserved, then draw direct.
					flushSatReplayBatch();
					registerCommand(PatternRenderCommand.obtain(atlasEntry, paletteTextureId,
							reusableReplayDesc, drawX, drawY, this));
				});
	}

	private boolean addToSatReplayBatch(PatternAtlas.Entry atlasEntry, int paletteIndex, int drawX, int drawY) {
		if (!satReplayBatchOpen) {
			instancedPatternRenderer.beginBatch(atlasEntry.atlasIndex());
			satReplayBatchOpen = true;
		}
		if (instancedPatternRenderer.addPattern(atlasEntry, paletteIndex, reusableReplayDesc, drawX, drawY)) {
			return true;
		}
		// Batch full: flush and retry once in a fresh batch.
		flushSatReplayBatch();
		instancedPatternRenderer.beginBatch(atlasEntry.atlasIndex());
		satReplayBatchOpen = true;
		return instancedPatternRenderer.addPattern(atlasEntry, paletteIndex, reusableReplayDesc, drawX, drawY);
	}

	private void flushSatReplayBatch() {
		if (!satReplayBatchOpen) {
			return;
		}
		GLCommandable batchCommand = instancedPatternRenderer.endBatch();
		if (batchCommand != null) {
			registerCommand(batchCommand);
		}
		satReplayBatchOpen = false;
	}

	List<PatternRenderCommand> buildSpriteSatReplayCommands(List<SpriteSatEntry> processedEntries) {
		// Reuse a single ArrayList across calls instead of allocating one per replay frame.
		// Callers must consume the contents before the next invocation; the only caller is
		// endSpriteSatCollectionAndReplay() which drains-then-clears in the same statement.
		reusableReplayCommands.clear();
		if (processedEntries == null || processedEntries.isEmpty()) {
			return reusableReplayCommands;
		}
		ensurePatternAtlas();
		Integer paletteTextureId = resolveSpriteSatReplayPaletteTextureId();
		if (paletteTextureId == null) {
			return reusableReplayCommands;
		}
		for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
			for (SpriteSatEntry processedEntry : processedEntries) {
				if (processedEntry.priorityBucket() == bucket) {
					appendDirectReplayCommands(processedEntry, paletteTextureId, reusableReplayCommands);
				}
			}
		}
		return reusableReplayCommands;
	}

	private Integer resolveSpriteSatReplayPaletteTextureId() {
		if (useUnderwaterPaletteForBackground && underwaterPaletteTextureId != null) {
			return underwaterPaletteTextureId;
		}
		if (combinedPaletteTextureId != null) {
			return combinedPaletteTextureId;
		}
		return headlessMode ? -1 : null;
	}

	private void appendDirectReplayCommands(
			SpriteSatEntry entry,
			int paletteTextureId,
			List<PatternRenderCommand> replayCommands) {
		if (entry == null || replayCommands == null) {
			return;
		}
		SpritePieceRenderer.renderPreparedPiece(entry.toPreparedPiece(),
				(patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
					PatternAtlas.Entry atlasEntry = patternAtlas != null ? patternAtlas.getEntry(patternIndex) : null;
					if (atlasEntry == null) {
						return;
					}
					prepareReplayDesc(entry, patternIndex, pieceHFlip, pieceVFlip, paletteIndex);
					replayCommands.add(PatternRenderCommand.obtain(atlasEntry, paletteTextureId, reusableReplayDesc, drawX, drawY, this));
				});
	}

	/**
	 * Loads the shared replay PatternDesc with the tile word for one replayed SAT
	 * tile. Reuses a single PatternDesc across the entire SAT replay: both
	 * PatternRenderCommand.init() and InstancedPatternRenderer.addPattern() copy
	 * all needed fields out of the desc and do not retain a reference, so mutating
	 * it before the next tile is safe. PatternDesc.set() resets every derived
	 * field via updateFields().
	 */
	private void prepareReplayDesc(SpriteSatEntry entry, int patternIndex,
			boolean pieceHFlip, boolean pieceVFlip, int paletteIndex) {
		int descIndex = patternIndex & 0x7FF;
		if (entry.piecePriority() || entry.globalHighPriority()) {
			descIndex |= 0x8000;
		}
		if (pieceHFlip) {
			descIndex |= 0x800;
		}
		if (pieceVFlip) {
			descIndex |= 0x1000;
		}
		descIndex |= (paletteIndex & 0x3) << 13;
		reusableReplayDesc.set(descIndex);
		reusableReplayDesc.setPaletteIndex(paletteIndex);
	}

	public WaterShaderProgram getWaterShaderProgram() {
		return waterShaderProgram;
	}

	public ShaderProgram getInstancedShaderProgram() {
		return instancedPatternRenderer != null ? instancedPatternRenderer.getInstancedShaderProgram() : null;
	}

	public WaterShaderProgram getInstancedWaterShaderProgram() {
		return instancedPatternRenderer != null ? instancedPatternRenderer.getInstancedWaterShaderProgram() : null;
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

	public void applyResolvedDisplayWidth(int pixelWidth) {
		if (tilemapGpuRenderer == null) {
			return;
		}
		tilemapGpuRenderer.applyResolvedDisplayWidth(pixelWidth);
	}

	public ShaderProgram getShadowShaderProgram() {
		return shadowShaderProgram;
	}

	/**
	 * Get the fade manager for screen transitions.
	 */
	public FadeManager getFadeManager() {
		ensureRuntimeManagedReferences();
		return fadeManager;
	}

	/**
	 * Check if GL is initialized.
	 */
	public boolean isGlInitialized() {
		return glInitialized;
	}

	/**
	 * Get the background renderer for shader-based parallax scrolling.
	 * Initializes it lazily on first access.
	 */
	public BackgroundRenderer getBackgroundRenderer() {
		if (headlessMode) {
			return null;
		}
		if (backgroundRenderer == null && glInitialized) {
			try {
				backgroundRenderer = new BackgroundRenderer(this);
				backgroundRenderer.init(PARALLAX_SHADER_PATH);
				LOGGER.info("BackgroundRenderer initialized for shader-based parallax.");
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, "Failed to initialize BackgroundRenderer", e);
			}
		}
		return backgroundRenderer;
	}

	/**
	 * Enqueue OpenGL state for debug line rendering.
	 * Note: Deprecated fixed-function calls (GL_TEXTURE_2D, GL_LIGHTING, GL_COLOR_MATERIAL)
	 * have been removed for OpenGL 4.1 core profile compatibility.
	 */
	public void enqueueDebugLineState() {
		ShaderProgram debugShader = getDebugShaderProgram();
		int programId = debugShader != null ? debugShader.getProgramId() : 0;
		registerCommand(new GLCommand(GLCommand.CommandType.USE_PROGRAM, programId));
		registerCommand(new GLCommand(GLCommand.CommandType.DISABLE, GL_DEPTH_TEST));
	}

	/**
	 * Enqueue OpenGL state for default shader rendering.
	 * Note: glEnable(GL_TEXTURE_2D) has been removed for OpenGL 4.1 core profile compatibility.
	 * Texturing is now controlled entirely through shaders.
	 */
	public void enqueueDefaultShaderState() {
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
		if (headlessMode || !glInitialized)
			return;
		glScissor(x, y, width, height);
		glEnable(GL_SCISSOR_TEST);
	}

	/**
	 * Disables scissor test.
	 */
	public void disableScissor() {
		if (headlessMode || !glInitialized)
			return;
		glDisable(GL_SCISSOR_TEST);
	}

	/**
	 * Get the unified UI render pipeline for overlay + fade ordering.
	 */
	public UiRenderPipeline getUiRenderPipeline() {
		ensureRuntimeManagedReferences();
		return uiRenderPipeline;
	}

	public DisplayShaderPipeline getDisplayShaderPipeline() {
		return displayShaderPipeline;
	}

	// ==================== Sprite Priority Rendering ====================

	/**
	 * Get the sprite priority shader program for ROM-accurate sprite-to-tile layering.
	 * This shader composites sprites with awareness of high-priority foreground tiles.
	 */
	public SpritePriorityShaderProgram getSpritePriorityShaderProgram() {
		return spritePriorityShaderProgram;
	}

	/**
	 * Get the tile priority FBO for rendering high-priority tile information.
	 * Lazily initializes the FBO with specified dimensions if not already done.
	 *
	 * @param width  Screen width in pixels
	 * @param height Screen height in pixels
	 */
	public TilePriorityFBO getTilePriorityFBO(int width, int height) {
		if (headlessMode || !glInitialized) {
			return null;
		}
		if (tilePriorityFBO != null && !tilePriorityFBO.isInitialized()) {
			tilePriorityFBO.init(width, height);
		} else if (tilePriorityFBO != null) {
			tilePriorityFBO.resize(width, height);
		}
		return tilePriorityFBO;
	}

	/**
	 * Get the tile priority FBO without initializing or resizing.
	 * Returns null if not yet initialized.
	 */
	public TilePriorityFBO getTilePriorityFBO() {
		return tilePriorityFBO;
	}

	/**
	 * Begin rendering high-priority tiles to the tile priority FBO.
	 * Call this before rendering the high-priority foreground tile pass.
	 *
	 * @param width  Screen width in pixels
	 * @param height Screen height in pixels
	 */
	public void beginTilePriorityPass(int width, int height) {
		if (headlessMode || !glInitialized) {
			return;
		}
		TilePriorityFBO fbo = getTilePriorityFBO(width, height);
		if (fbo != null) {
			fbo.begin();
		}
	}

	/**
	 * End rendering high-priority tiles to the tile priority FBO.
	 * Call this after rendering the high-priority foreground tile pass.
	 */
	public void endTilePriorityPass() {
		if (headlessMode || !glInitialized || tilePriorityFBO == null) {
			return;
		}
		tilePriorityFBO.end();
	}

	private record PendingRenderThreadTask<T>(Callable<T> callable, CompletableFuture<T> future) {
		void run() {
			try {
				future.complete(callable.call());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		}

		void cancel() {
			future.cancel(false);
		}
	}
}
