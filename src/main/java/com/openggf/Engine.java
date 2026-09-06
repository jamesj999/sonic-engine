package com.openggf;

import com.openggf.architecture.CompositionRoot;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.*;
import com.openggf.graphics.*;
import com.openggf.graphics.pipeline.RenderOrderRecorder;
import com.openggf.version.AppVersion;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import com.openggf.control.InputHandler;
import com.openggf.editor.EditorInputHandler;
import com.openggf.editor.EditorHierarchyDepth;
import com.openggf.editor.LevelEditorController;
import com.openggf.editor.persistence.EditorSaveManager;
import com.openggf.editor.render.EditorOverlayRenderer;
import com.openggf.audio.AudioManager;
import com.openggf.audio.DebugFailAfterFramesAudioHandle;
import com.openggf.audio.LWJGLAudioBackend;
import com.openggf.capture.*;
import com.openggf.camera.Camera;
import com.openggf.configuration.FrameRateResolver;
import com.openggf.configuration.KeyChord;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOption;
import com.openggf.debug.DebugColor;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.debug.DebugRenderer;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.DebugState;
import com.openggf.game.ShieldType;
import com.openggf.level.LevelManager;
import com.openggf.level.Level;
import com.openggf.level.MutableLevel;
import com.openggf.game.session.EditorCursorState;
import com.openggf.game.session.EditorModeContext;
import com.openggf.game.session.EditorPlaytestStash;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.GameplayTeamBootstrap;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.data.RomManager;
import com.openggf.game.save.SaveManager;
import com.openggf.game.save.SaveSlotSummary;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.launch.LaunchProfileStore;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.startup.DonatedDataSelectWarmupTask;
import com.openggf.data.Rom;
import com.openggf.physics.Direction;
import com.openggf.graphics.color.DisplayColorProfileController;
import com.openggf.graphics.shaderlib.DisplayShaderController;
import com.openggf.graphics.shaderlib.DisplayShaderLibrary;
import com.openggf.graphics.shaderlib.DisplayShaderPickerController;
import com.openggf.graphics.shaderlib.DisplayShaderPipeline;
import com.openggf.graphics.shaderlib.DisplayShaderPresetLoader;
import com.openggf.graphics.shaderlib.DisplayShaderPresetRef;
import com.openggf.graphics.shaderlib.DisplayShaderSelectionModel;
import com.openggf.graphics.shaderlib.RewindVhsEffectPass;
import com.openggf.graphics.shaderlib.ShaderPhase;
import com.openggf.render.EngineRenderDispatcher;
import com.openggf.util.RetroArchGlslShaderPackDownloader;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.Set;
import java.util.logging.Logger;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Controls the game.
 *
 * @author james
 */
@CompositionRoot
public class Engine {
	private static final Logger LOGGER = Logger.getLogger(Engine.class.getName());
	public static final String RESOURCES_SHADERS_PIXEL_SHADER_GLSL = "shaders/shader_the_hedgehog.glsl";
	private final SonicConfigurationService configService;
	private SpriteManager spriteManager;
	private final GraphicsManager graphicsManager;
	private final AudioManager audioManager;
	private boolean audioBackendInitialized;
	private boolean configuredHeadlessSession;
	private final RomManager romManager;
	private final RomDetectionService romDetectionService;
	private final CrossGameFeatureProvider crossGameFeatureProvider;
	private final DebugOverlayManager debugOverlayManager;
	private final PlaybackDebugManager playbackDebugManager;

	private Camera camera;
	private DebugRenderer debugRenderer;
	private final PerformanceProfiler profiler;

	private final GameLoop gameLoop;
	private final EngineRenderDispatcher renderDispatcher = new EngineRenderDispatcher();
	private final EngineRenderDispatcher.ClearActions clearActions = new EngineClearActions();
	private final EngineRenderDispatcher.DrawActions drawActions = new EngineDrawActions();
	private final LevelEditorController levelEditorController = new LevelEditorController();
	private EditorSaveManager editorSaveManager = new EditorSaveManager(Path.of("saves"));
	private final EditorInputHandler editorInputHandler;
	private final EditorOverlayRenderer editorOverlayRenderer;
	// Match the rest of the debug overlay — no drop shadow.
	private final PixelFontTextRenderer traceHudTextRenderer =
		new PixelFontTextRenderer(PixelFontVariant.PIXEL_FONT_NO_SHADOW);
	private final PixelFontTextRenderer pauseTextRenderer =
		new PixelFontTextRenderer(PixelFontVariant.PIXEL_FONT_NO_SHADOW);
	private final PixelFontTextRenderer liveCaptureTextRenderer =
		new PixelFontTextRenderer(PixelFontVariant.PIXEL_FONT_NO_SHADOW);
	private static final String USER_PAUSE_INDICATOR_TEXT = "PAUSED";
	private static final int USER_PAUSE_INDICATOR_MARGIN = 8;
	private static final long USER_PAUSE_INDICATOR_HALF_PERIOD_NANOS = 500_000_000L;
	private static final long USER_PAUSE_INDICATOR_PERIOD_NANOS = USER_PAUSE_INDICATOR_HALF_PERIOD_NANOS * 2L;
	private static final long USER_PAUSE_SCREENSHOT_HIDE_NANOS = 1_000_000_000L;
	private DisplayColorProfileController displayColorProfileController;
	private DisplayShaderController displayShaderController;
	private DisplayShaderPickerController displayShaderPickerController;
	private RewindVhsEffectPass rewindVhsEffectPass;
	private int displayShaderFrameCounter;
	private volatile boolean displayShaderPackDownloadInProgress;
	private volatile boolean displayShaderPackRescanRequested;
	private volatile String displayShaderPackStatus;

	private static volatile DebugState debugState = DebugState.NONE;
	private static volatile DebugOption debugOption = DebugOption.A;

	public static DebugState getDebugState() { return debugState; }
	public static DebugOption getDebugOption() { return debugOption; }
	public static void setDebugOption(DebugOption option) { debugOption = option; }

	private double realWidth;
	private double realHeight;

	// Current projection width - can be changed for H32/H40 mode switching
	// H40 mode (normal levels): 320 pixels wide
	// H32 mode (special stages): 256 pixels wide
	private double projectionWidth;

	private boolean debugViewEnabled;

	private LevelManager levelManager;

	// The active session-owned gameplay mode set during initializeGame().
	private GameplayModeContext gameplayMode;

	// Pre-allocated list for results screen rendering
	private final java.util.List<GLCommand> resultsCommands = new java.util.ArrayList<>(64);

	// GLFW window handle
	private long window;
	private boolean glfwInitialized;

	// Window dimensions
	private int windowWidth;
	private int windowHeight;

	record ResolvedDisplayDimensions(int pixelWidth, int pixelHeight, int windowWidth, int windowHeight) {
	}

	record FramebufferDimensions(int width, int height) {
	}

	record PauseIndicatorPlacement(int x, int y) {
	}

	enum ConfiguredStartupBranch {
		LEGAL_DISCLAIMER,
		MASTER_TITLE,
		GAME
	}

	enum LiveCapturePresentationState {
		NORMAL,
		MODAL_SHADER_PICKER,
		PAUSED,
		FRAME_STEP,
		REWIND
	}

	static final class LiveCaptureFailureTransitionReporter {
		private static final Throwable UNKNOWN_FAILURE =
				new IllegalStateException("Live capture entered FAILED without a cause");
		private final Consumer<Throwable> warningSink;
		private final Set<Throwable> reported =
				Collections.newSetFromMap(new IdentityHashMap<>());

		LiveCaptureFailureTransitionReporter(Consumer<Throwable> warningSink) {
			this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
		}

		void beginAttempt() {
			reported.clear();
		}

		void observe(LiveCaptureController controller) {
			Objects.requireNonNull(controller, "controller");
			if (controller.state() != LiveCaptureController.State.FAILED) {
				reported.clear();
				return;
			}
			Throwable failure = controller.lastFailure();
			if (failure == null) {
				failure = UNKNOWN_FAILURE;
			}
			if (reported.add(failure)) {
				warningSink.accept(failure);
			}
		}
	}

	private boolean overlayStateReady = false;

	// Input handler for keyboard input
	private InputHandler inputHandler;

	// Master title screen (game selection before ROM loading)
	private MasterTitleScreen masterTitleScreen;

	// Legal disclaimer screen (pre-ROM disclaimer)
	private LegalDisclaimerScreen legalDisclaimerScreen;

	// Viewport parameters for aspect-ratio-correct rendering
	private int viewportX = 0;
	private int viewportY = 0;
	private int viewportWidth = 0;
	private int viewportHeight = 0;

	// Window snap-to-integer-scale after resize
	private long lastResizeTimeNanos = 0;
	private boolean resizePendingSnap = false;
	private boolean isSnappingWindowSize = false;
	private int lastSnappedScale = 0;

	// JOML matrices for projection - accessible for shader uniforms
	private final org.joml.Matrix4f projectionMatrix = new org.joml.Matrix4f();
	private final float[] matrixBuffer = new float[16];

	// Static instance for singleton access
	private static Engine instance;

	// Frame timing
	private int targetFps;
	private final LiveCaptureChord liveCaptureChord = new LiveCaptureChord();
	private final LiveCaptureController liveCaptureController;
	private final AsyncScreenshotWriter screenshotWriter = new AsyncScreenshotWriter();
	private final LiveCapturePresentationCoordinator liveCapturePresentation;
	private final LiveCaptureIndicatorRenderer liveCaptureIndicator;
	/** How long a recording-interrupted notice stays on screen. */
	private static final long LIVE_CAPTURE_NOTICE_NANOS = 3_000_000_000L;
	private LiveCaptureController.Interruption liveCaptureInterruption;
	private long liveCaptureInterruptionExpiryNanos;
	private final LiveCaptureFailureTransitionReporter liveCaptureFailureReporter =
			new LiveCaptureFailureTransitionReporter(failure ->
					LOGGER.log(java.util.logging.Level.WARNING,
							"Live viewport recording failed", failure));
	private long lastFrameTime;
	private boolean paused = false;
	private long userPauseIndicatorHiddenUntilNanos;

	private DebugRenderer getDebugRenderer() {
		if (debugRenderer == null) {
			debugRenderer = new DebugRenderer();
		}
		return debugRenderer;
	}

	public Engine() {
		this(EngineServices.current());
	}

	public Engine(EngineContext engineServices) {
		engineServices = Objects.requireNonNull(engineServices, "engineServices");
		EngineServices.configure(engineServices);
		this.configService = engineServices.configuration();
		this.graphicsManager = engineServices.graphics();
		this.audioManager = engineServices.audio();
		this.romManager = engineServices.roms();
		this.romDetectionService = engineServices.romDetection();
		this.crossGameFeatureProvider = engineServices.crossGameFeatures();
		this.debugOverlayManager = engineServices.debugOverlay();
		this.playbackDebugManager = engineServices.playbackDebug();
		this.profiler = engineServices.profiler();
		this.graphicsManager.setPerformanceProfiler(profiler);
		this.editorOverlayRenderer = new EditorOverlayRenderer(levelEditorController, graphicsManager);
		this.gameLoop = new GameLoop(engineServices);
		this.gameLoop.setEditorStateSyncHandler(this::syncEditorState);
		this.gameLoop.setMasterTitleScreenSupplier(() -> masterTitleScreen);
		this.gameLoop.setMasterTitleExitHandler(this::exitMasterTitleScreen);
		this.gameLoop.setLegalDisclaimerScreenSupplier(() -> legalDisclaimerScreen);
		this.gameLoop.setLegalDisclaimerExitHandler(this::exitLegalDisclaimer);
		this.gameLoop.setDataSelectActionHandler(this::launchGameplayFromDataSelect);
		this.gameLoop.setReturnToMasterTitleHandler(this::returnToMasterTitleScreen);
		this.gameLoop.setMasterTitleLaunchFailureHandler(this::rollbackLaunchSessionCachedConfig);
		this.gameLoop.setApplicationExitHandler(this::requestApplicationExit);
		this.realWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
		this.realHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
		this.projectionWidth = realWidth;
		this.debugViewEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
		this.windowWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH);
		this.windowHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT);
		this.targetFps = resolveTargetFps(configService);
		this.liveCaptureController = createLiveCaptureController();
		this.liveCapturePresentation = new LiveCapturePresentationCoordinator(liveCaptureController);
		this.liveCaptureIndicator = new LiveCaptureIndicatorRenderer(
				this::drawLiveCaptureDot,
				(text, x, y, red, green, blue, alpha, fontScale) ->
						liveCaptureTextRenderer.drawShadowedText(
								text, x, y,
								new DebugColor(Math.round(red * 255f),
										Math.round(green * 255f),
										Math.round(blue * 255f),
										Math.round(alpha * 255f)),
								fontScale),
				new LiveCaptureIndicatorRenderer.TextMeasurer() {
					@Override
					public int width(String text, float fontScale) {
						return liveCaptureTextRenderer.measureWidth(text, fontScale);
					}

					@Override
					public int height(float fontScale) {
						return liveCaptureTextRenderer.lineHeight(fontScale);
					}
				});
		this.editorInputHandler = new EditorInputHandler(
				levelEditorController, () -> camera, () -> graphicsManager, this::saveCurrentEditorLevel);

		// Set up game mode change listener to update projection width
		gameLoop.setGameModeChangeListener((oldMode, newMode) -> {
			// Keep projection at 320 for both modes
			projectionWidth = realWidth;
		});
		gameLoop.setEditorInputHandler(editorInputHandler);
		gameLoop.setEditorPlaytestToggleHandler(this::toggleEditorPlaytestMode);
		gameLoop.setEditorFreshStartHandler(this::startGameplayFromBeginning);

		instance = this;
	}

	static int resolveTargetFps(SonicConfigurationService config) {
		return FrameRateResolver.effective(config);
	}

	private LiveCaptureController createLiveCaptureController() {
		String ffmpeg = FfmpegEncoder.findFfmpeg().map(Path::toString).orElse("ffmpeg");
		LiveCaptureRecorderFactory recorderFactory =
				new LiveCaptureRecorderFactory(configService, Clock.systemUTC(), ffmpeg);
		ExecutorService finalizer = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "live-capture-finalizer");
			thread.setDaemon(true);
			return thread;
		});
		return new LiveCaptureController(new LiveCaptureController.Dependencies(
				frameRate -> DebugFailAfterFramesAudioHandle.maybeWrap(
						audioManager.beginLiveCaptureAudio(frameRate),
						resolveLiveCaptureAudioFailAfterFrames()),
				audioManager::outputSampleRate,
				GlPboFrameGrabber::create,
				recorderFactory::create,
				finalizer,
				Duration.ofSeconds(10)));
	}

	static int resolveLiveCaptureAudioFailAfterFrames() {
		String raw = System.getProperty(
				"openggf.debug.liveCaptureAudioFailAfterFrames", "-1");
		try {
			int value = Integer.parseInt(raw);
			if (value >= -1) return value;
		} catch (NumberFormatException ignored) {
			// Warn below using the same safe-disabled behavior.
		}
		LOGGER.warning("Invalid openggf.debug.liveCaptureAudioFailAfterFrames='"
				+ raw + "'; failure injection is disabled");
		return -1;
	}

	static String buildWindowTitle() {
		return "OpenGGF " + AppVersion.get();
	}

	public void setInputHandler(InputHandler inputHandler) {
		this.inputHandler = inputHandler;
		gameLoop.setInputHandler(inputHandler);
	}

	public void run() {
		try {
			init();
			loop();
		} finally {
			cleanup();
		}
	}

	private static boolean isNativeImage() {
		return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
	}

	static boolean shouldEnablePerformanceProfiler(boolean performanceOverlayEnabled, boolean nativeImage) {
		return performanceOverlayEnabled;
	}

	static boolean shouldRenderPerformanceOverlay(GameMode currentMode,
												 boolean performanceOverlayEnabled,
												 boolean userRecordingSceneSuppressed) {
		return performanceOverlayEnabled && !userRecordingSceneSuppressed;
	}

	private void init() {
		// === PHASE 1: Window, GL context, input (always runs) ===
		// Setup an error callback
		GLFWErrorCallback.createPrint(System.err).set();

		// Initialize GLFW
		if (!glfwInit()) {
			throw new IllegalStateException("Unable to initialize GLFW");
		}
		glfwInitialized = true;

		// Configure GLFW
		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
		glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
		glfwWindowHint(GLFW_SCALE_TO_MONITOR, GLFW_TRUE); // DPI-aware window scaling

		// Request OpenGL 4.1 core profile for macOS compatibility
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
		glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
		glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE); // Required for macOS

		// Identify the window to the desktop shell so Wayland/X11 can match its icon
		WindowIconLoader.applyWindowClassHints();

		// Create the window
		window = glfwCreateWindow(windowWidth, windowHeight,
				buildWindowTitle(), NULL, NULL);
		if (window == NULL) {
			throw new RuntimeException("Failed to create the GLFW window");
		}

		// Apply the application icon (ignored on platforms that manage icons themselves)
		WindowIconLoader.apply(window);

		// Setup key callback
		glfwSetKeyCallback(window, (windowHandle, key, scancode, action, mods) -> {
			if (inputHandler != null) {
				inputHandler.handleKeyEvent(key, action);
			}
		});
		glfwSetCursorPosCallback(window, (windowHandle, x, y) -> {
			if (inputHandler != null) {
				inputHandler.handleMouseMove(x, y);
			}
		});
		glfwSetMouseButtonCallback(window, (windowHandle, button, action, mods) -> {
			if (inputHandler != null) {
				inputHandler.handleMouseButton(button, action);
			}
		});

		// Setup window resize callback
		glfwSetFramebufferSizeCallback(window, (windowHandle, width, height) -> {
			liveCaptureController.requestStop(LiveCaptureController.StopReason.VIEWPORT_CHANGED);
			this.windowWidth = width;
			this.windowHeight = height;
			// Only reshape if GL context is initialized (avoids crash during window setup)
			if (graphicsManager.isGlInitialized()) {
				reshape(width, height);
			}
			// Schedule a window snap once the user finishes resizing
			if (!isSnappingWindowSize) {
				resizePendingSnap = true;
				lastResizeTimeNanos = System.nanoTime();
			}
		});

		// Setup window focus callback
		glfwSetWindowFocusCallback(window, (windowHandle, focused) ->
				paused = applyWindowActivation(focused, inputHandler, gameLoop::pause, gameLoop::resume));

		// Setup window iconify callback
		glfwSetWindowIconifyCallback(window, (windowHandle, iconified) ->
				paused = applyWindowActivation(!iconified, inputHandler, gameLoop::pause, gameLoop::resume));

		// Get the thread stack and push a new frame
		try (MemoryStack stack = stackPush()) {
			IntBuffer pWidth = stack.mallocInt(1);
			IntBuffer pHeight = stack.mallocInt(1);

			// Get the window size passed to glfwCreateWindow
			glfwGetWindowSize(window, pWidth, pHeight);

			// Center the window when the desktop video mode is available.
			long primaryMonitor = glfwGetPrimaryMonitor();
			GLFWVidMode vidmode = primaryMonitor != NULL ? glfwGetVideoMode(primaryMonitor) : null;
			if (vidmode != null) {
				glfwSetWindowPos(
						window,
						(vidmode.width() - pWidth.get(0)) / 2,
						(vidmode.height() - pHeight.get(0)) / 2
				);
			}
		}

		// Make the OpenGL context current
		glfwMakeContextCurrent(window);

		// Enable v-sync
		glfwSwapInterval(1);

		// Make the window visible
		glfwShowWindow(window);

		// This line is critical for LWJGL's interoperation with GLFW's
		// OpenGL context, or any context that is managed externally.
		GL.createCapabilities();
		ensureAudioBackend();

		try {
			initializePresentationGraphics();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// Create input handler and set it
		inputHandler = InputHandler.live(InputBindingFactory.supplier(configService));
		setInputHandler(inputHandler);

		// Set window handle for clipboard operations (GLFW-based, no AWT dependency)
		debugOverlayManager.setWindowHandle(window);

		// Initial reshape and snap to integer scale (handles DPI-scaled framebuffer)
		try (MemoryStack stack = stackPush()) {
			IntBuffer pWidth = stack.mallocInt(1);
			IntBuffer pHeight = stack.mallocInt(1);
			glfwGetFramebufferSize(window, pWidth, pHeight);
			this.windowWidth = pWidth.get(0);
			this.windowHeight = pHeight.get(0);
			reshape(windowWidth, windowHeight);
		}
		snapWindowToIntegerScale();

		initializeConfiguredStartup();

		// Eagerly initialize debug renderer resources before the main loop starts.
		if (debugViewEnabled) {
			getDebugRenderer().updateViewport(viewportWidth, viewportHeight);
			getDebugRenderer().eagerInit();
			// Force GL sync and unbind all state
			glFinish();
			glBindTexture(GL_TEXTURE_2D, 0);
			glBindVertexArray(0);
			glBindBuffer(GL_ARRAY_BUFFER, 0);
			glUseProgram(0);
		}

		lastFrameTime = System.nanoTime();
	}

	/**
	 * Initializes the ordinary configured startup graph without creating a
	 * window, GL context, or audio device. The caller owns both supplied
	 * instances; this seam only installs them and executes the same startup
	 * branch used by {@link #init()}. The configured session remains owned by
	 * the caller if startup throws, so {@link #closeConfiguredHeadlessSession()}
	 * must run from the caller's cleanup scope.
	 */
	public void initializeConfiguredHeadlessSession(
			InputHandler input, com.openggf.audio.AudioBackend backend) {
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(backend, "backend");
		if (configuredHeadlessSession) {
			throw new IllegalStateException(
					"a configured headless session is already active");
		}
		setInputHandler(input);
		audioManager.setBackend(backend);
		if (!audioManager.hasInstalledBackend(backend)) {
			setInputHandler(null);
			throw new IllegalStateException(
					"the supplied headless audio backend was not installed");
		}
		audioBackendInitialized = true;
		configuredHeadlessSession = true;
		initializeConfiguredStartup();
	}

	/** Idempotent no-platform teardown for {@link #initializeConfiguredHeadlessSession}. */
	public void closeConfiguredHeadlessSession() {
		if (!configuredHeadlessSession) {
			return;
		}
		Throwable failure = cleanupConfiguredHeadlessResources();
		failure = releaseConfiguredHeadlessInput(failure);
		if (failure != null) {
			rethrowConfiguredHeadlessCleanupFailure(failure);
		}
		audioBackendInitialized = false;
		configuredHeadlessSession = false;
	}

	private Throwable cleanupConfiguredHeadlessResources() {
		Throwable failure = null;
		failure = appendConfiguredHeadlessCleanupFailure(
				failure, screenshotWriter::close);
		failure = appendConfiguredHeadlessCleanupFailure(
				failure, liveCaptureController::close);
		failure = appendConfiguredHeadlessCleanupFailure(
				failure, SessionManager::clear);
		failure = appendConfiguredHeadlessCleanupFailure(
				failure, audioManager::clearDonorAudio);
		failure = appendConfiguredHeadlessCleanupFailure(
				failure, crossGameFeatureProvider::resetState);
		failure = appendConfiguredHeadlessCleanupFailure(
				failure, gameLoop::closePresence);
		failure = appendConfiguredHeadlessCleanupFailure(
				failure, audioManager::resetState);
		failure = appendConfiguredHeadlessCleanupFailure(
				failure, audioManager::destroy);
		return failure;
	}

	private Throwable releaseConfiguredHeadlessInput(Throwable failure) {
		InputHandler installedInput = inputHandler;
		if (installedInput != null) {
			failure = appendConfiguredHeadlessCleanupFailure(
					failure, installedInput::clearLogicalOverride);
		}
		return appendConfiguredHeadlessCleanupFailure(
				failure, () -> setInputHandler(null));
	}

	private static Throwable appendConfiguredHeadlessCleanupFailure(
			Throwable aggregate, Runnable cleanup) {
		try {
			cleanup.run();
		} catch (RuntimeException | Error failure) {
			if (aggregate == null) {
				return failure;
			}
			aggregate.addSuppressed(failure);
		}
		return aggregate;
	}

	private static void rethrowConfiguredHeadlessCleanupFailure(
			Throwable failure) {
		if (failure instanceof RuntimeException runtimeFailure) {
			throw runtimeFailure;
		}
		throw (Error) failure;
	}

	static ConfiguredStartupBranch resolveConfiguredStartupBranch(
			SonicConfigurationService configuration) {
		Objects.requireNonNull(configuration, "configuration");
		if (configuration.getBoolean(
				SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP)) {
			return ConfiguredStartupBranch.LEGAL_DISCLAIMER;
		}
		if (configuration.getBoolean(
				SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP)) {
			return ConfiguredStartupBranch.MASTER_TITLE;
		}
		return ConfiguredStartupBranch.GAME;
	}

	private void initializeConfiguredStartup() {
		switch (resolveConfiguredStartupBranch(configService)) {
			case LEGAL_DISCLAIMER -> {
				legalDisclaimerScreen = new LegalDisclaimerScreen(
						graphicsManager.getFadeManager());
				legalDisclaimerScreen.initialize();
				gameLoop.setGameMode(GameMode.LEGAL_DISCLAIMER);
			}
			case MASTER_TITLE -> {
				masterTitleScreen = createMasterTitleScreen();
				masterTitleScreen.initialize();
				gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
			}
			case GAME -> initializeGame();
		}
	}

	private void refreshDisplayPalettes() {
		LevelManager activeLevelManager = GameServices.levelOrNull();
		if (activeLevelManager != null) {
			activeLevelManager.reloadLevelPalettes();
		}
	}

	private void initializePresentationGraphics() throws IOException {
		graphicsManager.init(RESOURCES_SHADERS_PIXEL_SHADER_GLSL);
		graphicsManager.setEngine(this);
		displayColorProfileController = DisplayColorProfileController.fromConfig(
				configService,
				graphicsManager,
				this::refreshDisplayPalettes);
		initializeDisplayShaders();
	}

	private void initializeDisplayShaders() {
		DisplayShaderLibrary library = scanDisplayShaderLibrary();
		DisplayShaderSelectionModel selectionModel = new DisplayShaderSelectionModel(library);
		DisplayShaderPresetLoader loader = new DisplayShaderPresetLoader();
		ShaderPhase defaultPhase = parseDisplayShaderDefaultPhase();
		displayShaderPickerController = new DisplayShaderPickerController(
				selectionModel,
				configService.getInt(SonicConfiguration.DISPLAY_SHADER_PICKER_KEY));
		displayShaderController = new DisplayShaderController(
				library,
				configService.getString(SonicConfiguration.DISPLAY_SHADER_SELECTION),
				configService.getInt(SonicConfiguration.DISPLAY_SHADER_NEXT_KEY),
				configService.getInt(SonicConfiguration.DISPLAY_SHADER_PREVIOUS_KEY),
				this::persistDisplayShaderSelection,
				ref -> activateDisplayShader(ref, loader, defaultPhase));
		displayShaderController.applySavedSelectionSilently();

		// A launch profile may enable live rewind after graphics have initialized.
		// Prewarm from the presentation preference alone so that session override
		// still has an effect when the global live-rewind default is disabled.
		if (configService.getBoolean(SonicConfiguration.LIVE_REWIND_VHS_EFFECT)) {
			rewindVhsEffectPass = new RewindVhsEffectPass();
			rewindVhsEffectPass.prewarm(
					configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
					configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS),
					Math.max(1, viewportWidth),
					Math.max(1, viewportHeight));
		}
	}

	private void reloadDisplayShaderLibrary() {
		DisplayShaderLibrary library = scanDisplayShaderLibrary();
		DisplayShaderSelectionModel selectionModel = new DisplayShaderSelectionModel(library);
		String savedSelection = configService.getString(SonicConfiguration.DISPLAY_SHADER_SELECTION);
		if (displayShaderController != null) {
			displayShaderController.replaceLibrary(library, savedSelection);
		}
		if (displayShaderPickerController != null) {
			DisplayShaderPresetRef currentRef = displayShaderController != null
					? displayShaderController.currentRef()
					: DisplayShaderPresetRef.OFF;
			displayShaderPickerController.replaceSelectionModel(selectionModel, currentRef);
		}
	}

	private DisplayShaderLibrary scanDisplayShaderLibrary() {
		String root = configService.getString(SonicConfiguration.DISPLAY_SHADER_LIBRARY_ROOT);
		try {
			return DisplayShaderLibrary.scan(Path.of(root));
		} catch (RuntimeException e) {
			LOGGER.warning("Display shader library root is invalid: " + root);
			return DisplayShaderLibrary.scan(null);
		}
	}

	private ShaderPhase parseDisplayShaderDefaultPhase() {
		String raw = configService.getString(SonicConfiguration.DISPLAY_SHADER_DEFAULT_PHASE);
		try {
			return ShaderPhase.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (RuntimeException e) {
			LOGGER.warning("Invalid display shader default phase '" + raw + "', using PRESENTATION");
			return ShaderPhase.PRESENTATION;
		}
	}

	private boolean activateDisplayShader(
			DisplayShaderPresetRef ref,
			DisplayShaderPresetLoader loader,
			ShaderPhase defaultPhase) {
		DisplayShaderPipeline pipeline = graphicsManager.getDisplayShaderPipeline();
		if (pipeline == null) {
			return ref != null && ref.kind() == DisplayShaderPresetRef.Kind.OFF;
		}
		if (ref == null || ref.kind() == DisplayShaderPresetRef.Kind.OFF) {
			return pipeline.activate(null);
		}
		try {
			return pipeline.activate(loader.load(ref, defaultPhase));
		} catch (Exception e) {
			LOGGER.log(java.util.logging.Level.WARNING, "Display shader failed to load: " + ref.label(), e);
			return false;
		}
	}

	private void persistDisplayShaderSelection(String selection) {
		configService.setConfigValue(SonicConfiguration.DISPLAY_SHADER_SELECTION, selection);
		configService.saveConfig();
	}

	private void startDisplayShaderPackDownload() {
		if (displayShaderPackDownloadInProgress) {
			displayShaderPackStatus = "Libretro GLSL: already downloading";
			return;
		}

		final Path shaderRoot;
		try {
			shaderRoot = Path.of(configService.getString(SonicConfiguration.DISPLAY_SHADER_LIBRARY_ROOT));
		} catch (RuntimeException e) {
			displayShaderPackStatus = "Libretro GLSL failed: invalid shader root";
			return;
		}

		displayShaderPackDownloadInProgress = true;
		displayShaderPackStatus = "Libretro GLSL: checking";
		Thread downloaderThread = new Thread(() -> {
			try {
				RetroArchGlslShaderPackDownloader.InstallResult result =
						new RetroArchGlslShaderPackDownloader().downloadIfNewer(
								shaderRoot,
								this::updateDisplayShaderPackProgress);
				displayShaderPackStatus = result.downloaded()
						? "Libretro GLSL: installed"
						: "Libretro GLSL: up to date";
				displayShaderPackRescanRequested = true;
			} catch (RetroArchGlslShaderPackDownloader.ShaderPackDownloadException e) {
				displayShaderPackStatus = "Libretro GLSL failed: " + e.reason();
				LOGGER.log(java.util.logging.Level.WARNING, "Libretro GLSL shader pack download failed", e);
			} finally {
				displayShaderPackDownloadInProgress = false;
			}
		}, "OpenGGF-libretro-glsl-download");
		downloaderThread.setDaemon(true);
		downloaderThread.start();
	}

	private void updateDisplayShaderPackProgress(
			RetroArchGlslShaderPackDownloader.Stage stage,
			long completed,
			long total,
			String detail) {
		displayShaderPackStatus = switch (stage) {
			case CHECKING -> "Libretro GLSL: checking";
			case DOWNLOADING -> total > 0
					? "Libretro GLSL: downloading " + Math.min(100, (completed * 100 / total)) + "%"
					: "Libretro GLSL: downloading";
			case EXTRACTING -> "Libretro GLSL: extracting " + completed;
			case COMPLETE -> detail == null || detail.isBlank()
					? "Libretro GLSL: complete"
					: "Libretro GLSL: " + detail;
		};
	}

	private void processDisplayShaderPackRescan() {
		if (!displayShaderPackRescanRequested || displayShaderPackDownloadInProgress) {
			return;
		}
		displayShaderPackRescanRequested = false;
		reloadDisplayShaderLibrary();
	}

	void refreshLaunchSessionCachedConfig() {
		debugViewEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
	}

	ResolvedDisplayDimensions readResolvedDisplayDimensionsForLaunch() {
		return new ResolvedDisplayDimensions(
				configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
				configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS),
				configService.getInt(SonicConfiguration.SCREEN_WIDTH),
				configService.getInt(SonicConfiguration.SCREEN_HEIGHT));
	}

	void applyResolvedDisplayDimensions() {
		ResolvedDisplayDimensions resolved = readResolvedDisplayDimensionsForLaunch();
		realWidth = resolved.pixelWidth();
		realHeight = resolved.pixelHeight();
		projectionWidth = realWidth;
		graphicsManager.setProjectionWidth((int) projectionWidth);
		graphicsManager.applyResolvedDisplayWidth((int) projectionWidth);

		if (glfwInitialized && window != 0L) {
			FramebufferDimensions framebuffer = readCurrentFramebufferDimensions();
			windowWidth = framebuffer.width();
			windowHeight = framebuffer.height();
			reshape(framebuffer.width(), framebuffer.height());
		}
	}

	private FramebufferDimensions readCurrentFramebufferDimensions() {
		try (MemoryStack stack = stackPush()) {
			IntBuffer pWidth = stack.mallocInt(1);
			IntBuffer pHeight = stack.mallocInt(1);
			glfwGetFramebufferSize(window, pWidth, pHeight);
			return resolveFramebufferDimensionsAfterWindowResize(
					windowWidth, windowHeight, pWidth.get(0), pHeight.get(0));
		}
	}

	static FramebufferDimensions resolveFramebufferDimensionsAfterWindowResize(
			int requestedWindowWidth, int requestedWindowHeight, int framebufferWidth, int framebufferHeight) {
		if (framebufferWidth > 0 && framebufferHeight > 0) {
			return new FramebufferDimensions(framebufferWidth, framebufferHeight);
		}
		return new FramebufferDimensions(requestedWindowWidth, requestedWindowHeight);
	}

	void rollbackLaunchSessionCachedConfig() {
		refreshLaunchSessionCachedConfig();
		applyResolvedDisplayDimensions();
	}

	/**
	 * Phase 2 initialization: loads ROM, creates sprites, initializes audio, loads level.
	 * Called either directly from init() (no master title screen) or from
	 * exitMasterTitleScreen() after game selection.
	 */
	public void initializeGame() {
		GameModule module;
		try {
			Rom rom = romManager.getRom();
			var detectedModule = romDetectionService.detectAndCreateModule(rom);
			if (detectedModule.isEmpty()) {
				showStartupRomError("ROM not recognized or corrupt. OpenGGF requires a supported Sonic 1, Sonic 2, or Sonic 3&K ROM.");
				return;
			}
			module = detectedModule.orElseThrow();
		} catch (IOException e) {
			showStartupRomError("Failed to load ROM during game initialization: " + e.getMessage());
			return;
		}
		GameplayModeContext gameplayMode = SessionManager.openGameplaySession(module);
		initializeGameplayRuntime(gameplayMode, true);
		boolean generationRan = maybeGenerateDonatedDataSelectImagesBeforeStartupMode(module);
		if (generationRan) {
			// Preview capture loaded full levels into the LevelManager and
			// GraphicsManager, corrupting the pattern atlas, palette textures,
			// DPLC banks, sprite art, and other GPU/manager state.
			// Reset GPU state (pattern atlas + palette textures) and rebuild
			// the gameplay mode so everything starts from a clean slate.
			graphicsManager.resetPatternAndPaletteState();
			gameplayMode = SessionManager.openGameplaySession(module);
			initializeGameplayRuntime(gameplayMode, false);
		} else {
			graphicsManager.runPendingRenderThreadTasks();
		}
		enterConfiguredStartupMode();
	}

	private void showStartupRomError(String message) {
		LOGGER.warning(message);
		gameplayMode = null;
		gameLoop.setGameplayMode(null);
		if (masterTitleScreen != null) {
			masterTitleScreen.cleanup();
		}
		masterTitleScreen = createMasterTitleScreen();
		if (graphicsManager.isGlInitialized()) {
			masterTitleScreen.initialize();
		}
		masterTitleScreen.showRomLoadError(configService.getString(SonicConfiguration.DEFAULT_ROM));
		gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
	}

	/**
	 * Gets the master title screen instance (for GameLoop to call update/draw).
	 */
	public MasterTitleScreen getMasterTitleScreen() {
		return masterTitleScreen;
	}

	public LegalDisclaimerScreen getLegalDisclaimerScreen() {
		return legalDisclaimerScreen;
	}

	/**
	 * Called by GameLoop when the user selects a game from the master title screen.
	 * Performs the Phase 2 init for the selected game.
	 */
	public void exitMasterTitleScreen(String gameId) {
		refreshLaunchSessionCachedConfig();
		applyResolvedDisplayDimensions();

		// Set the DEFAULT_ROM config to the selected game
		configService.setConfigValue(SonicConfiguration.DEFAULT_ROM, gameId);

		// Clean up master title screen GL resources
		if (masterTitleScreen != null) {
			masterTitleScreen.cleanup();
			masterTitleScreen = null;
		}

		// Bootstrap title-screen mode runs before gameplay mode/module/ROM state is
		// fully established. Tear down that bootstrap-era state so the selected game
		// starts from a clean gameplay/render/ROM baseline instead of inheriting caches.
		resetForGameplayFromMasterTitle();

		// Phase 2: load ROM, sprites, audio, level
		initializeGame();
	}

	private void requestApplicationExit() {
		if (window != NULL) {
			glfwSetWindowShouldClose(window, true);
		}
	}

	/**
	 * Called by GameLoop when the disclaimer's fade-to-black completes.
	 * Cleans up the disclaimer GL resources, then either builds the
	 * MasterTitleScreen (if MASTER_TITLE_SCREEN_ON_STARTUP is true) or
	 * runs Phase 2 init directly. In both branches the host owns the
	 * post-disclaimer reveal fade-from-black: none of
	 * enterConfiguredStartupMode's three sub-paths (title screen,
	 * level select, default level load) starts its own intro fade.
	 */
	public void exitLegalDisclaimer() {
		if (legalDisclaimerScreen != null) {
			legalDisclaimerScreen.cleanup();
			legalDisclaimerScreen = null;
		}

		boolean masterTitleOnStartup = configService.getBoolean(SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP);
		if (masterTitleOnStartup) {
			masterTitleScreen = createMasterTitleScreen();
			masterTitleScreen.initialize();
			gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
		} else {
			initializeGame();
		}

		graphicsManager.getFadeManager().startFadeFromBlack(null);
	}

	private void resetForGameplayFromMasterTitle() {
		var worldSession = SessionManager.getCurrentWorldSession();
		if (worldSession != null) {
			worldSession.getGameModule().resetModuleScopedState();
		}
		SessionManager.clear();
		romManager.close();
		GameModuleRegistry.reset();
		audioManager.resetState();
		crossGameFeatureProvider.resetState();
		debugOverlayManager.resetState();
		RenderContext.reset();
		graphicsManager.clearPaletteTextures();
		gameLoop.resetModuleScopedProviders();
	}

	/**
	 * Teardown path used by {@link TraceSessionLauncher} after a trace
	 * playback session completes. Resets gameplay mode state (same
	 * cleanup as when gameplay first exits the master title) and
	 * rebuilds the master title screen so the picker can re-enter on
	 * the next frame.
	 */
	void returnToMasterTitleScreen() {
		resetForGameplayFromMasterTitle();
		// resetForGameplayFromMasterTitle destroyed the gameplay mode, but GameLoop
		// still caches the old mode + its FadeManager. Drop the reference
		// so the next launch's fade-to-black runs on the bootstrap manager
		// (which the UI pipeline actually ticks) rather than the dead one.
		gameLoop.setGameplayMode(null);
		this.gameplayMode = null;
		if (masterTitleScreen != null) {
			masterTitleScreen.cleanup();
		}
		refreshLaunchSessionCachedConfig();
		applyResolvedDisplayDimensions();
		masterTitleScreen = createMasterTitleScreen();
		masterTitleScreen.initialize();
		gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
		// Counter the teardown's fade-to-black. Without this the screen
		// stays fully black and the new master title never becomes
		// visible. Use the graphics-owned fade manager - the gameplay mode
		// (and its fade manager) was just destroyed above.
		FadeManager fadeManager = graphicsManager.getFadeManager();
		if (fadeManager != null) {
			fadeManager.startFadeFromBlack(null);
		}
	}

	public void enterEditorFromCurrentPlayer(EditorPlaytestStash stash, int playerX, int playerY) {
		if (!isEditorEnabled()) {
			throw new IllegalStateException("Level editor is disabled by configuration.");
		}
		ensureRuntimeBound();
		prepareMutableEditorLevel();
		primeEditorSelection(playerX, playerY);
		levelEditorController.setWorldCursor(new EditorCursorState(playerX, playerY));
		// Camera bounds are world-derived but stored on the gameplay-mode
		// camera, which gets reset when the gameplay mode is destroyed.
		short savedMinX = camera != null ? camera.getMinX() : 0;
		short savedMaxX = camera != null ? camera.getMaxX() : 0;
		short savedMinY = camera != null ? camera.getMinY() : 0;
		short savedMaxY = camera != null ? camera.getMaxY() : 0;
		Level editorLevel = levelEditorController.currentLevel();
		SessionManager.enterEditorMode(new EditorCursorState(playerX, playerY), stash);
		gameplayMode = null;
		gameLoop.setGameplayMode(null);
		bindEditorLevelView(editorLevel, savedMinX, savedMaxX, savedMinY, savedMaxY);
		syncEditorState();
		gameLoop.setGameMode(GameMode.EDITOR);
	}

	public void resumePlaytestFromEditor() {
		editorInputHandler.finishActiveStroke();
		if (!saveCurrentEditorLevel()) {
			return;
		}
		repairEditorCursorForResume();
		syncEditorState();
		GameplayModeContext gameplay = SessionManager.resumeGameplayFromEditor();
		// Build a fresh gameplay mode over the surviving WorldSession, then
		// rehydrate the loaded level (preserving any MutableLevel mutations
		// made in editor) via restoreInheritedLevel.
		initializeGameplayRuntime(gameplay, false);
		try {
			gameplay.getLevelManager().restoreInheritedLevel();
		} catch (IOException e) {
			throw new RuntimeException("Failed to restore inherited level on editor exit", e);
		}
		// Per the design, editor exit reinitializes gameplay session state as
		// fresh — score/timer/checkpoint must not carry over from before the
		// editor detour. (Counters that initializeGameplayRuntime already set —
		// special-stage progress configuration — stay.)
		gameplay.initializeFreshGameplayState();
		applyResumedPlaytestState(gameplay);
		gameLoop.setGameMode(GameMode.LEVEL);
	}

	private boolean saveCurrentEditorLevel() {
		MutableLevel mutableLevel = levelEditorController.currentLevel();
		if (mutableLevel == null) {
			return true;
		}
		com.openggf.game.session.WorldSession worldSession = SessionManager.getCurrentWorldSession();
		if (worldSession == null) {
			return true;
		}
		GameModule module = worldSession.getGameModule();
		try {
			editorSaveManager.save(module.getGameId(), worldSession.getCurrentZone(), worldSession.getCurrentAct(), mutableLevel);
			return true;
		} catch (IOException e) {
			LOGGER.warning("Failed to save editor edits: " + e.getMessage());
			return false;
		}
	}

	public void startGameplayFromBeginning() {
		GameplayModeContext gameplay = SessionManager.restartGameplayFromBeginning();
		initializeGameplayRuntime(gameplay, false);
		loadDefaultStartingLevel(false);
		gameLoop.setGameMode(GameMode.LEVEL);
	}

	private void initializeGameplayRuntime(GameplayModeContext gameplayMode, boolean initializeGlobalGameplayServices) {
		GameModule module = gameplayMode.getWorldSession().getGameModule();
		GameplaySessionFactory.attachManagers(gameplayMode, EngineServices.current());
		bindGameplayMode(gameplayMode);
		gameplayMode.getGameStateManager().configureSpecialStageProgress(
				module.getSpecialStageCycleCount(),
				module.getChaosEmeraldCount());

		if (initializeGlobalGameplayServices) {
			initializeGlobalGameplayServices();
		}

		GameplayTeamBootstrap.BootstrappedTeam team = GameplayTeamBootstrap.registerActiveTeam(
				module, spriteManager, configService);
		camera.setFocusedSprite(team.mainSprite());
		camera.updatePosition(true);
	}

	private void initializeGlobalGameplayServices() {
		ensureAudioBackend();

		if (configService.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED)) {
			try {
				String donorGame = configService.getString(SonicConfiguration.CROSS_GAME_SOURCE);
				crossGameFeatureProvider.initialize(donorGame);
			} catch (IOException e) {
				LOGGER.severe("Cross-game features enabled but initialization failed. "
						+ "Check that the " + configService.getString(SonicConfiguration.CROSS_GAME_SOURCE)
						+ " ROM is configured and accessible. Error: " + e.getMessage());
			}
		}
	}

	private MasterTitleScreen createMasterTitleScreen() {
		ensureAudioBackend();
		return new MasterTitleScreen(configService,
				new LaunchProfileStore(configService),
				cue -> audioManager.playSfx(cue.sfxName()));
	}

	private void ensureAudioBackend() {
		if (!configService.getBoolean(SonicConfiguration.AUDIO_ENABLED)) {
			return;
		}
		if (!audioBackendInitialized) {
			audioManager.setBackend(new LWJGLAudioBackend(configService, profiler));
			audioBackendInitialized = true;
		} else {
			audioManager.ensurePresentationSink();
		}
	}

	private boolean maybeGenerateDonatedDataSelectImagesBeforeStartupMode(GameModule module) {
		boolean crossGameEnabled = configService.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED);
		String donorCode = configService.getString(SonicConfiguration.CROSS_GAME_SOURCE);
		boolean s3kConfiguredDonor = "s3k".equalsIgnoreCase(donorCode);
		if (module == null || !crossGameEnabled || !s3kConfiguredDonor || !CrossGameFeatureProvider.isS3kDonorActive()) {
			return false;
		}
		Optional<DonatedDataSelectWarmupTask> warmup = module.getDonatedDataSelectWarmupTask();
		if (warmup.isEmpty()) {
			return false;
		}
		DonatedDataSelectWarmupTask task = warmup.orElseThrow();
		task.start();
		pumpRenderThreadTasksUntilSettled(task::isRunning);
		return true;
	}

	private void pumpRenderThreadTasksUntilSettled(java.util.function.BooleanSupplier generationRunning) {
		if (generationRunning == null) {
			return;
		}
		while (generationRunning.getAsBoolean()) {
			graphicsManager.runPendingRenderThreadTasks();
			Thread.onSpinWait();
		}
		graphicsManager.runPendingRenderThreadTasks();
	}

	private void enterConfiguredStartupMode() {
		boolean titleScreenOnStartup = configService.getBoolean(SonicConfiguration.TITLE_SCREEN_ON_STARTUP);
		boolean levelSelectOnStartup = configService.getBoolean(SonicConfiguration.LEVEL_SELECT_ON_STARTUP);
		if (titleScreenOnStartup) {
			gameLoop.initializeTitleScreenMode();
		} else if (levelSelectOnStartup) {
			gameLoop.initializeLevelSelectMode();
		} else {
			loadDefaultStartingLevel(true);
		}
	}

	private void loadDefaultStartingLevel(boolean requireRom) {
		if (!requireRom && !romManager.isRomAvailable()) {
			return;
		}
		try {
			levelManager.loadZoneAndActForFreshRuntime(0, 0);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void launchGameplayFromDataSelect(com.openggf.game.dataselect.DataSelectAction action) {
		GameModule module = SessionManager.requireCurrentGameModule();
		SaveManager saveManager = new SaveManager(Path.of("saves"));
		Map<String, Object> loadedPayload = loadDataSelectPayload(module, action, saveManager);
		com.openggf.game.save.SaveSessionContext saveContext =
				createDataSelectSaveContext(module, action, loadedPayload);

		GameplayModeContext gameplay = SessionManager.openGameplaySession(module, saveContext);
		initializeGameplayRuntime(gameplay, false);
		loadLevelFromDataSelect(action.zone(), action.act());
		restoreGameplayModeFromDataSelectPayload(gameplayMode, loadedPayload);
		gameLoop.setGameMode(GameMode.LEVEL);

		dataSelectLaunchSaveReason(action.type())
				.ifPresent(gameLoop::requestSaveForCurrentSession);
	}

	static Optional<com.openggf.game.save.SaveReason> dataSelectLaunchSaveReason(
			com.openggf.game.dataselect.DataSelectActionType actionType) {
		return switch (actionType) {
			case NEW_SLOT_START -> Optional.of(com.openggf.game.save.SaveReason.NEW_SLOT_START);
			case LOAD_SLOT -> Optional.of(com.openggf.game.save.SaveReason.EXISTING_SLOT_LOAD);
			case CLEAR_RESTART -> Optional.of(com.openggf.game.save.SaveReason.CLEAR_RESTART_COMMIT);
			case NONE, NO_SAVE_START, DELETE_SLOT -> Optional.empty();
		};
	}

	private void loadLevelFromDataSelect(int zone, int act) {
		try {
			levelManager.loadZoneAndActForFreshRuntime(zone, act);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load zone " + zone + " act " + act + " from data select", e);
		}
	}

	static com.openggf.game.save.SaveSessionContext createDataSelectSaveContext(
			GameModule module,
			com.openggf.game.dataselect.DataSelectAction action,
			Map<String, Object> payload) {
		String gameCode = switch (module.getGameId()) {
			case S1 -> "s1";
			case S2 -> "s2";
			case S3K -> "s3k";
		};
		SelectedTeam team = payload == null ? action.team() : teamFromPayload(payload, action.team());
		com.openggf.game.save.SaveSessionContext context =
				action.slot() > 0
						? com.openggf.game.save.SaveSessionContext.forSlot(
								gameCode, action.slot(), team, action.zone(), action.act())
						: com.openggf.game.save.SaveSessionContext.noSave(
								gameCode, team, action.zone(), action.act());
		if (payload != null && Boolean.TRUE.equals(payload.get("clear"))) {
			context.markClear();
		}
		return context;
	}

	static Map<String, Object> loadDataSelectPayload(
			GameModule module,
			com.openggf.game.dataselect.DataSelectAction action,
			SaveManager saveManager) {
		if (action.slot() <= 0) {
			return null;
		}
		return switch (action.type()) {
			case LOAD_SLOT, CLEAR_RESTART -> {
				try {
					String gameCode = switch (module.getGameId()) {
						case S1 -> "s1";
						case S2 -> "s2";
						case S3K -> "s3k";
					};
					SaveSlotSummary summary = saveManager.readSlotSummary(gameCode, action.slot());
					yield summary.isLoadable() ? summary.payload() : null;
				} catch (IOException e) {
					throw new RuntimeException("Failed to read save slot " + action.slot() + " for data select launch", e);
				}
			}
			case NONE, NO_SAVE_START, NEW_SLOT_START, DELETE_SLOT -> null;
		};
	}

	static void restoreGameplayModeFromDataSelectPayload(GameplayModeContext gameplayMode, Map<String, Object> payload) {
		if (gameplayMode == null || payload == null) {
			return;
		}
		int lives = readInt(payload, "lives", gameplayMode.getGameStateManager().getLives());
		int continues = readInt(payload, "continues", gameplayMode.getGameStateManager().getContinues());
		// S3K slot load (docs/skdisasm/sonic3k.asm:16997-17012): a slot whose
		// saved lives are zero, or below three with no continues, restarts with
		// three lives and spends a continue (clamped at zero). This is where a
		// continue banked before a game over is consumed, since SaveGame_LivesContinues
		// wrote the zero life count when the GAME OVER card appeared.
		if (lives == 0 || (lives < 3 && continues == 0)) {
			lives = 3;
			continues = Math.max(0, continues - 1);
		}
		gameplayMode.getGameStateManager().restoreSaveProgress(
				lives,
				continues,
				readIntList(payload.get("chaosEmeralds")),
				readIntList(payload.get("superEmeralds")),
				payload.get("emeraldsConverted") instanceof Boolean converted ? converted : null);
	}

	private static SelectedTeam teamFromPayload(Map<String, Object> payload, SelectedTeam fallback) {
		Object mainRaw = payload.get("mainCharacter");
		if (!(mainRaw instanceof String main)) {
			return fallback;
		}
		Object sidekicksRaw = payload.get("sidekicks");
		List<String> sidekicks = sidekicksRaw instanceof List<?>
				? ((List<?>) sidekicksRaw).stream().map(String::valueOf).toList()
				: List.of();
		return new SelectedTeam(main, sidekicks);
	}

	private static int readInt(Map<String, Object> payload, String key, int fallback) {
		Object value = payload.get(key);
		return value instanceof Number number ? number.intValue() : fallback;
	}

	private static List<Integer> readIntList(Object raw) {
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<Integer> values = new ArrayList<>();
		for (Object value : list) {
			if (value instanceof Number number) {
				values.add(number.intValue());
			}
		}
		return List.copyOf(values);
	}

	public void toggleEditorPlaytestMode() {
		if (getCurrentGameMode() == GameMode.EDITOR) {
			resumePlaytestFromEditor();
			return;
		}
		if (getCurrentGameMode() != GameMode.LEVEL) {
			return;
		}
		if (!isEditorEnabled()) {
			return;
		}
		AbstractPlayableSprite player = resolveMainPlayableSprite();
		if (player == null) {
			return;
		}
		EditorPlaytestStash stash = capturePlaytestStash(player);
		enterEditorFromCurrentPlayer(stash, player.getCentreX(), player.getCentreY());
	}

	private boolean isEditorEnabled() {
		return configService.getBoolean(SonicConfiguration.EDITOR_ENABLED);
	}

	private void bindGameplayMode(GameplayModeContext gameplayMode) {
		this.gameplayMode = gameplayMode;
		this.camera = gameplayMode.getCamera();
		this.spriteManager = gameplayMode.getSpriteManager();
		this.levelManager = gameplayMode.getLevelManager();
		this.levelManager.setEditorSaveManager(editorSaveManager);
		gameLoop.setGameplayMode(gameplayMode);
	}

	private void bindEditorLevelView(Level editorLevel,
	                                 short minX,
	                                 short maxX,
	                                 short minY,
	                                 short maxY) {
		EditorModeContext editorMode = SessionManager.getCurrentEditorMode();
		if (editorMode == null || !editorMode.isEditorRuntimeReady()) {
			throw new IllegalStateException("Editor mode is not ready for level rendering.");
		}
		Camera editorCamera = editorMode.getCamera();
		SpriteManager editorSprites = editorMode.getSpriteManager();
		LevelManager editorLevelManager = editorMode.getLevelManager();
		editorLevelManager.setEditorSaveManager(editorSaveManager);
		editorLevelManager.restoreEditorLevelView(editorLevel);
		editorCamera.setMinX(minX);
		editorCamera.setMaxX(maxX);
		editorCamera.setMinY(minY);
		editorCamera.setMaxY(maxY);
		this.camera = editorCamera;
		this.spriteManager = editorSprites;
		this.levelManager = editorLevelManager;
	}

	private void ensureRuntimeBound() {
		if (gameplayMode != null) {
			return;
		}
		GameplayModeContext currentGameplayMode = SessionManager.getCurrentGameplayMode();
		if (currentGameplayMode == null) {
			throw new IllegalStateException("No active gameplay mode");
		}
		bindGameplayMode(currentGameplayMode);
	}

	private AbstractPlayableSprite resolveMainPlayableSprite() {
		ensureRuntimeBound();
		String mainCode = ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
		var sprite = spriteManager.getSprite(mainCode);
		if (sprite instanceof AbstractPlayableSprite playable) {
			return playable;
		}
		return null;
	}

	private EditorPlaytestStash capturePlaytestStash(AbstractPlayableSprite player) {
		boolean facingRight = player.getDirection() != Direction.LEFT;
		int shieldState = player.getShieldType() == null ? 0 : player.getShieldType().ordinal() + 1;
		return new EditorPlaytestStash(
				player.getCentreX(),
				player.getCentreY(),
				player.getXSpeed(),
				player.getYSpeed(),
				facingRight,
				player.getRingCount(),
				shieldState);
	}

	private void prepareMutableEditorLevel() {
		Level currentLevel = levelManager.getCurrentLevel();
		if (currentLevel == null) {
			return;
		}

		MutableLevel mutableLevel = currentLevel instanceof MutableLevel existing
				? existing
				: MutableLevel.snapshot(currentLevel);
		if (mutableLevel != currentLevel) {
			levelManager.setLevel(mutableLevel);
		}
		levelEditorController.attachLevel(mutableLevel);
	}

	private void primeEditorSelection(int playerX, int playerY) {
		Level currentLevel = levelManager.getCurrentLevel();
		if (currentLevel == null) {
			return;
		}

		int blockIndex = levelManager.getBlockIdAt(playerX, playerY);
		if (blockIndex < 0 || blockIndex >= currentLevel.getBlockCount()) {
			return;
		}

		levelEditorController.selectBlock(blockIndex);
		levelEditorController.selectChunk(0);
	}

	private void synchronizeEditorOverlayDepth() {
		editorOverlayRenderer.setHierarchyDepth(levelEditorController.depth());
	}

	public LevelEditorController getLevelEditorController() {
		return levelEditorController;
	}

	public void syncEditorState() {
		EditorModeContext editorMode = SessionManager.getCurrentEditorMode();
		if (editorMode == null) {
			return;
		}

		EditorCursorState cursor = levelEditorController.worldCursor();
		editorMode.setCursor(cursor);
		synchronizeEditorOverlayDepth();

		if (levelEditorController.depth() == EditorHierarchyDepth.WORLD && camera != null) {
			camera.setX(clampCameraAxisWithWrap(cursor.x() - 152, camera.getMinX(), camera.getMaxX()));
			camera.setY(clampCameraAxisWithWrap(cursor.y() - 96, camera.getMinY(), camera.getMaxY()));
		}
	}

	private void repairEditorCursorForResume() {
		EditorModeContext editorMode = SessionManager.getCurrentEditorMode();
		if (editorMode == null) {
			return;
		}

		EditorCursorState repaired = clampCursorToCurrentLevel(levelEditorController.worldCursor());
		levelEditorController.setWorldCursor(repaired);
		editorMode.setCursor(levelEditorController.worldCursor());
	}

	private EditorCursorState clampCursorToCurrentLevel(EditorCursorState cursor) {
		if (cursor == null || levelManager == null || levelManager.getCurrentLevel() == null) {
			return cursor;
		}

		Level level = levelManager.getCurrentLevel();
		return new EditorCursorState(
				clampToBounds(cursor.x(), level.getMinX(), level.getMaxX()),
				clampToBounds(cursor.y(), level.getMinY(), level.getMaxY()));
	}

	private int clampToBounds(int value, int min, int max) {
		if (max < min) {
			return value < min ? min : value;
		}
		return Math.max(min, Math.min(max, value));
	}

	private short clampCameraAxisWithWrap(int value, short min, short max) {
		if (max < min) {
			return (short) (value < min ? min : value);
		}
		if (value < min) {
			return min;
		}
		if (value > max) {
			return max;
		}
		return (short) value;
	}

	private void applyResumedPlaytestState(GameplayModeContext gameplay) {
		AbstractPlayableSprite player = resolveMainPlayableSprite();
		if (player == null) {
			return;
		}

		player.setCentreX((short) gameplay.getSpawnX());
		player.setCentreY((short) gameplay.getSpawnY());

		if (gameplay.getResumeStash().isPresent()) {
			EditorPlaytestStash stash = gameplay.getResumeStash().orElseThrow();
			player.setXSpeed((short) stash.xVelocity());
			player.setYSpeed((short) stash.yVelocity());
			player.setDirection(stash.facingRight() ? Direction.RIGHT : Direction.LEFT);
			player.setRingCount(stash.rings());
			player.clearPowerUps();
			ShieldType shieldType = decodeShieldState(stash.shieldState());
			if (shieldType != null) {
				player.giveShield(shieldType);
			}
		}

		camera.setFocusedSprite(player);
		camera.updatePosition(true);
	}

	private ShieldType decodeShieldState(int shieldState) {
		if (shieldState <= 0) {
			return null;
		}
		ShieldType[] values = ShieldType.values();
		int index = shieldState - 1;
		if (index < 0 || index >= values.length) {
			return null;
		}
		return values[index];
	}

	private void reshape(int width, int height) {
		int nativeW = (int) realWidth;   // 320
		int nativeH = (int) realHeight;  // 224

		// Find the largest integer scale that fits both dimensions
		int scale = Math.max(1, Math.min(width / nativeW, height / nativeH));
		viewportWidth = scale * nativeW;
		viewportHeight = scale * nativeH;

		// Center the integer-scaled viewport within the framebuffer
		viewportX = (width - viewportWidth) / 2;
		viewportY = (height - viewportHeight) / 2;

		// Set the viewport to the integer-scaled area
		glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

		// Cache viewport dimensions in GraphicsManager
		graphicsManager.setViewport(viewportX, viewportY, viewportWidth, viewportHeight);

		// Setup orthographic projection using JOML - stored for shader access
		projectionMatrix.identity().ortho2D(0, (float) projectionWidth, 0, (float) realHeight);
		projectionMatrix.get(matrixBuffer);
	}

	/**
	 * Snaps the window size to the nearest integer multiple of the native resolution
	 * so every game pixel maps to exactly NxN screen pixels with no fractional scaling.
	 * Rounds UP when the window grew (e.g. DPI increase) and DOWN when it shrank,
	 * preventing progressive shrinking across monitor moves.
	 */
	private void snapWindowToIntegerScale() {
		int nativeW = (int) realWidth;
		int nativeH = (int) realHeight;

		// Get the current monitor's usable resolution as an upper bound
		long monitor = glfwGetWindowMonitor(window);
		if (monitor == NULL) {
			monitor = glfwGetPrimaryMonitor();
		}
		if (monitor == NULL) {
			return;
		}
		GLFWVidMode vidmode = glfwGetVideoMode(monitor);
		Integer maxScale = computeIntegerScaleUpperBound(nativeW, nativeH, vidmode);
		if (maxScale == null) {
			return;
		}

		double currentScale = Math.min((double) windowWidth / nativeW, (double) windowHeight / nativeH);

		int scale;
		if (lastSnappedScale > 0 && currentScale > lastSnappedScale) {
			// Window grew (DPI increase or manual resize up) - round up
			scale = (int) Math.ceil(currentScale);
		} else {
			// Window shrank, unchanged, or initial load - round down
			scale = (int) currentScale;
		}
		scale = Math.max(1, Math.min(scale, maxScale));

		lastSnappedScale = scale;
		int targetW = scale * nativeW;
		int targetH = scale * nativeH;
		if (targetW != windowWidth || targetH != windowHeight) {
			isSnappingWindowSize = true;
			glfwSetWindowSize(window, targetW, targetH);
			isSnappingWindowSize = false;
		}
	}

	static Integer computeIntegerScaleUpperBound(int nativeW, int nativeH, GLFWVidMode vidmode) {
		if (vidmode == null) {
			return null;
		}
		return computeIntegerScaleUpperBound(nativeW, nativeH, vidmode.width(), vidmode.height());
	}

	static Integer computeIntegerScaleUpperBound(int nativeW, int nativeH, int modeW, int modeH) {
		if (nativeW <= 0 || nativeH <= 0 || modeW <= 0 || modeH <= 0) {
			return null;
		}
		return Math.max(1, Math.min(modeW / nativeW, modeH / nativeH));
	}

	private void loop() {
		long frameTimeNanos = 1_000_000_000L / targetFps;
		long accumulator = 0;
		long previousTime = System.nanoTime();

		while (!glfwWindowShouldClose(window)) {
			long currentTime = System.nanoTime();
			long deltaTime = currentTime - previousTime;
			previousTime = currentTime;

			if (!paused) {
				accumulator += deltaTime;

				// Process exactly one frame per target interval
				if (accumulator >= frameTimeNanos) {
					display();
					glfwSwapBuffers(window);
					// Preserve remainder to prevent timing drift
					accumulator -= frameTimeNanos;

					// Clamp accumulator to prevent spiral of death if frames take too long
					if (accumulator > frameTimeNanos) {
						accumulator = frameTimeNanos;
					}
				}
			}

			glfwPollEvents();

			// Snap window to nearest integer scale once resize drag ends (~200ms debounce)
			if (resizePendingSnap && System.nanoTime() - lastResizeTimeNanos > 200_000_000L) {
				resizePendingSnap = false;
				snapWindowToIntegerScale();
			}

			// Note: inputHandler.update() is called at the end of GameLoop.step()
			// to properly handle isKeyPressed() edge detection. Do NOT call update()
			// here or isKeyPressed() will always return false.

			if (paused) {
				// When unfocused/minimized, sleep longer — no need for frame-precise
				// timing. Just poll events ~30 times/sec to stay responsive to focus.
				try {
					Thread.sleep(33);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				// Reset accumulator so we don't process a burst of frames on resume
				accumulator = 0;
				previousTime = System.nanoTime();
				continue;
			}

			// Hybrid sleep: sleep most of the wait time, then spin-wait for precision
			long remainingTime = frameTimeNanos - accumulator;
			if (remainingTime > 2_000_000) {
				// Sleep for most of the remaining time, leaving ~1ms for spin-wait
				try {
					Thread.sleep((remainingTime - 1_000_000) / 1_000_000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}

			// Spin-wait the final portion for sub-millisecond precision
			// Calculate target time for next frame check
			long targetTime = previousTime + (frameTimeNanos - accumulator);
			while (System.nanoTime() < targetTime) {
				Thread.onSpinWait();
			}
		}
	}

	/**
	 * Called each frame to render the game.
	 */
	private void display() {
		boolean performanceOverlayEnabled =
				debugOverlayManager.isEnabled(DebugOverlayToggle.PERFORMANCE);
		profiler.setEnabled(shouldEnablePerformanceProfiler(
				performanceOverlayEnabled,
				isNativeImage()));
		profiler.beginFrame();

		// Clear the entire window to black first (for letterbox/pillarbox bars)
		glViewport(0, 0, windowWidth, windowHeight);
		glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		// Set the viewport to the aspect-ratio-correct area for game rendering
		glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

		// Update projection matrix for current mode - stored for shader access
		projectionMatrix.identity().ortho2D(0, (float) projectionWidth, 0, (float) realHeight);
		projectionMatrix.get(matrixBuffer);

		renderDispatcher.applyClearColor(getCurrentGameMode(), clearActions);
		glScissor(viewportX, viewportY, viewportWidth, viewportHeight);
		glEnable(GL_SCISSOR_TEST);
		glClear(GL_COLOR_BUFFER_BIT);
		glDisable(GL_SCISSOR_TEST);

		glColorMask(true, true, true, true);

		// Update fade via unified UI render pipeline — process fade state first so
		// that callbacks (e.g. credits transition flags) are available to step()
		// in the same frame, preventing 1-frame gaps where the overlay would drop.
		var uiPipeline = graphicsManager.getUiRenderPipeline();
		if (uiPipeline != null
				&& (gameLoop == null || !gameLoop.ownsGameplayFadeLifecycle())) {
			uiPipeline.updateFade();
		}

		profiler.beginSection("update");
		processDisplayShaderPackRescan();
		handleLiveCaptureShortcut();
		liveCaptureFailureReporter.observe(liveCaptureController);
		boolean frameStepPresentation = gameLoop != null && gameLoop.isUserPaused()
				&& inputHandler != null
				&& inputHandler.isKeyPressed(
						configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
		boolean displayShaderPickerHandledInput = updateDisplayShaderInput();
		if (displayColorProfileController != null && !displayShaderPickerHandledInput) {
			displayColorProfileController.update(inputHandler);
		}
		if (displayShaderController != null && !displayShaderPickerHandledInput) {
			displayShaderController.update(inputHandler);
		}
		if (!displayShaderPickerHandledInput) {
			update();
		} else if (inputHandler != null) {
			// Modal picker frames skip GameLoop.step(), so advance key edges here.
			inputHandler.update();
		}
		if (gameLoop != null) {
			presentOuterAudioFrame(gameLoop,
					displayShaderPickerHandledInput, frameStepPresentation);
		}
		profiler.endSection("update");

		boolean userRecordingSceneSuppressed = gameLoop != null
				&& gameLoop.shouldSuppressUserRecordingSceneRendering();
		boolean performanceOverlayVisible = shouldRenderPerformanceOverlay(
				getCurrentGameMode(),
				performanceOverlayEnabled,
				userRecordingSceneSuppressed);

		profiler.beginSection("render");
		graphicsManager.runPendingRenderThreadTasks();
		if (!userRecordingSceneSuppressed) {
			draw();
		}
		graphicsManager.flush();
		if (!userRecordingSceneSuppressed) {
			applyDisplayShaderPhase(ShaderPhase.SCENE);
		}
		profiler.endSection("render");

		// Render screen fade overlay via unified UI render pipeline
		if (uiPipeline != null && !userRecordingSceneSuppressed) {
			uiPipeline.renderFadePass();
		}
		// VHS picture-search effect while a tape transport is active — live
		// rewind, or visual trace rewind/fast-forward. Runs BEFORE the user's
		// PRESENTATION-phase display shader so a CRT preset displays the
		// damaged "signal" (tape artifacts precede the TV in the real chain).
		if (!userRecordingSceneSuppressed && rewindVhsEffectPass != null && gameLoop != null) {
			float rewindEffectIntensity = gameLoop.tapeEffectIntensity();
			if (rewindEffectIntensity > 0.0f) {
				rewindVhsEffectPass.apply(
						rewindEffectIntensity,
						gameLoop.tapeEffectSpeed(),
						gameLoop.tapeEffectScrollDirection(),
						configService.getBoolean(SonicConfiguration.LIVE_REWIND_VHS_TEAR_BANDS),
						configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
						configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS),
						viewportX, viewportY, viewportWidth, viewportHeight);
			}
		}
		if (!userRecordingSceneSuppressed) {
			applyDisplayShaderPhase(ShaderPhase.PRESENTATION);
		}
		RenderOrderRecorder postFadeRecorder = uiPipeline != null ? uiPipeline.getRenderOrderRecorder() : null;

		// Post-fade diagnostic overlays: intentionally outside UiRenderPipeline so
		// trace/debug status remains visible during fade-to-black teardown. Keep
		// this block diagnostic-only unless an explicit render-order exception is
		// documented and guarded.
		if (!userRecordingSceneSuppressed && postFadeRecorder != null) {
			postFadeRecorder.recordPostFadeDiagnostic("DisplayColorProfileNotification");
		}
		if (!userRecordingSceneSuppressed) {
			renderDisplayColorProfileNotification();
		}
		if (!userRecordingSceneSuppressed && postFadeRecorder != null) {
			postFadeRecorder.recordPostFadeDiagnostic("DisplayShaderNotification");
		}
		if (!userRecordingSceneSuppressed) {
			renderDisplayShaderNotification();
		}

		// Trace Test Mode HUD and live rewind HUD: drawn after the fade pass so
		// counters and TRACE COMPLETE remain readable during fade-to-black teardown.
		TraceSessionLauncher traceSession = TraceSessionLauncher.active();
		if (!userRecordingSceneSuppressed && traceSession != null) {
			traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
			if (postFadeRecorder != null) {
				postFadeRecorder.recordPostFadeDiagnostic("TraceHud");
			}
			traceSession.render(traceHudTextRenderer);
		} else if (!userRecordingSceneSuppressed && gameLoop != null) {
			traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
			if (postFadeRecorder != null) {
				postFadeRecorder.recordPostFadeDiagnostic("LiveRewindHud");
			}
			gameLoop.renderLiveRewindHud(traceHudTextRenderer);
		}
		if (gameLoop != null) {
			traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
			if (postFadeRecorder != null) {
				postFadeRecorder.recordPostFadeDiagnostic("UserRecordingHud");
			}
			gameLoop.renderUserRecordingHud(traceHudTextRenderer);
		}
		if (!userRecordingSceneSuppressed) {
			renderEscapeToMasterTitlePrompt(postFadeRecorder);
		}
		if (!userRecordingSceneSuppressed && getCurrentGameMode() == GameMode.CREDITS_DEMO) {
			EndingProvider provider = gameLoop.getEndingProvider();
			if (provider != null && provider.shouldRenderDemoSpritesOverFade()) {
				// Credits demo exception: the original ending presentation keeps
				// demo sprites visible over fade while the diagnostic overlays remain
				// readable. Guard tests keep this the only gameplay sprite pass here.
				// Reset shader/texture state before the post-fade sprite pass: the fade
				// shader binds a program and modifies blend/depth state, and even though
				// FadeManager restores blend on its own, we must not rely on the next pass
				// inheriting whatever shader/texture bindings the fade pass left active.
				graphicsManager.resetForFixedFunction();
				if (postFadeRecorder != null) {
					postFadeRecorder.recordPostFadeDiagnostic("CreditsDemoSprites");
				}
				levelManager.renderSpriteObjectPass(spriteManager, true);
				graphicsManager.flush();
			}
		}

		boolean playbackHud = playbackDebugManager.isHudVisible();
		boolean userPaused = gameLoop != null && gameLoop.isUserPaused();
		long pauseIndicatorNowNanos = System.nanoTime();
		if (isUserPauseScreenshotSuppressionKeyPressed(inputHandler)) {
			userPauseIndicatorHiddenUntilNanos =
					userPauseIndicatorHiddenUntilAfterScreenshotKey(pauseIndicatorNowNanos);
		}
		boolean userPauseIndicatorVisible = shouldRenderUserPauseIndicator(
				userPaused,
				pauseIndicatorNowNanos,
				userPauseIndicatorHiddenUntilNanos);
		boolean needsOverlay = performanceOverlayVisible || (!userRecordingSceneSuppressed && ((getCurrentGameMode() == GameMode.SPECIAL_STAGE) ||
				((debugViewEnabled || playbackHud || userPauseIndicatorVisible)
						&& getCurrentGameMode() != GameMode.SPECIAL_STAGE)));

		if (needsOverlay) {
			prepareOverlayState();
		}

		if (!userRecordingSceneSuppressed && userPauseIndicatorVisible) {
			renderUserPauseIndicator();
		}

		renderDiagnosticOverlays(userRecordingSceneSuppressed, playbackHud,
				performanceOverlayVisible, postFadeRecorder);

		renderDisplayShaderPickerOverlay();
		applyDisplayShaderPhase(ShaderPhase.FINAL);
		// F12 screenshot capture (after all rendering is complete) is the middle
		// callback: capture pixels first, then screenshot, then window-only REC.
		LiveCapturePresentationState presentationState = resolveLiveCapturePresentationState(
				displayShaderPickerHandledInput, userPaused, frameStepPresentation,
				gameLoop != null && gameLoop.liveRewindEffectIntensity() > 0.0f);
		presentLiveCaptureFrame(getCurrentGameMode(), presentationState,
				() -> liveCapturePresentation.present(currentCaptureViewport(),
						this::captureScreenshotIfRequested,
						this::renderLiveCaptureIndicatorIfActive));
		liveCaptureFailureReporter.observe(liveCaptureController);

		profiler.endFrame();
		overlayStateReady = false;
	}

	/**
	 * Executable non-GL seam for the one Engine-owned outer audio callback.
	 * Tests drive the same placement after every simulation/modal branch that
	 * {@link #display()} uses in production.
	 */
	public static void presentOuterAudioFrame(
			GameLoop loop,
			boolean modalPicker,
			boolean frameStepRequested) {
		long audioStartNanos = System.nanoTime();
		try {
			Objects.requireNonNull(loop, "loop").presentOuterFrame(
					modalPicker, frameStepRequested);
			GameServices.audio().update();
		} finally {
			GameServices.profiler().recordSectionTime(
					"audio", System.nanoTime() - audioStartNanos);
		}
	}

	static LiveCapturePresentationState resolveLiveCapturePresentationState(
			boolean modalShaderPicker, boolean paused, boolean frameStep, boolean rewind) {
		if (modalShaderPicker) {
			return LiveCapturePresentationState.MODAL_SHADER_PICKER;
		}
		if (rewind) {
			return LiveCapturePresentationState.REWIND;
		}
		if (frameStep) {
			return LiveCapturePresentationState.FRAME_STEP;
		}
		if (paused) {
			return LiveCapturePresentationState.PAUSED;
		}
		return LiveCapturePresentationState.NORMAL;
	}

	static void presentLiveCaptureFrame(GameMode mode,
									 LiveCapturePresentationState state,
									 Runnable presentationSeam) {
		Objects.requireNonNull(presentationSeam, "presentationSeam");
		boolean renderedMode = switch (Objects.requireNonNull(mode, "mode")) {
			case LEVEL, TITLE_CARD, SPECIAL_STAGE, SPECIAL_STAGE_RESULTS,
					TITLE_SCREEN, CONTINUE_SCREEN, DATA_SELECT, LEVEL_SELECT, EDITOR, CREDITS_TEXT,
					CREDITS_DEMO, MASTER_TITLE_SCREEN, LEGAL_DISCLAIMER, TRY_AGAIN_END,
					ENDING_CUTSCENE, BONUS_STAGE -> true;
		};
		boolean renderedState = switch (Objects.requireNonNull(state, "state")) {
			case NORMAL, MODAL_SHADER_PICKER, PAUSED, FRAME_STEP, REWIND -> true;
		};
		if (!renderedMode || !renderedState) {
			throw new IllegalStateException("Unsupported rendered presentation");
		}
		presentationSeam.run();
	}

	/**
	 * Gates the loop on the window becoming active or inactive, and drops held
	 * key state either way. Focus and iconify deliver the same event through two
	 * callbacks, so they share one body rather than drifting apart.
	 *
	 * <p>The clear runs on both edges. A key is otherwise forgotten only when its
	 * release arrives, and the release for a window-switch modifier goes to the
	 * window that took focus -- so a latched Super would disable every
	 * {@code isKeyPressedWithoutModifiers} shortcut for the rest of the process.
	 * Clearing on the way back in as well means whatever swallowed the release --
	 * a minimise that delivered no focus event, a window-manager grab on a Super
	 * combo -- is recovered from the next time the window is activated, instead
	 * of only when the loss edge happened to fire.
	 *
	 * @return the engine's new paused state
	 */
	static boolean applyWindowActivation(boolean active, InputHandler inputHandler,
			Runnable pause, Runnable resume) {
		if (inputHandler != null) {
			inputHandler.clearKeyState();
		}
		if (active) {
			resume.run();
		} else {
			pause.run();
		}
		return !active;
	}

	static void startLiveCaptureAttempt(
			LiveCaptureController controller,
			LiveCaptureFailureTransitionReporter failureReporter,
			CaptureViewport viewport,
			int frameRate) {
		failureReporter.beginAttempt();
		controller.start(viewport, frameRate);
	}

	/**
	 * True on the frame the configured capture chord becomes satisfied.
	 *
	 * <p>An unbound chord cannot fire, and no longer needs a guard here to say
	 * so: its key code is negative, which
	 * {@link com.openggf.control.InputHandler#isPhysicalKeyDown(int)} reports as not
	 * down, and {@link LiveCaptureChord#update} requires {@code isBound()} of
	 * its own accord. It used to need one, because {@code isKeyDown(-1)} fell
	 * through to the pad-rewind substitution and {@code rewindKey()} is -1 too
	 * when live rewind is unbound; that hole is closed in {@code InputHandler}
	 * for both {@code isKeyDown} and {@code isKeyPressed}, so every call site
	 * gets it rather than this one.
	 */
	static boolean shouldToggleLiveCapture(KeyChord chord, LiveCaptureChord detector,
			InputHandler input) {
		if (chord == null) {
			return false;
		}
		return detector.update(chord,
				input.isPhysicalKeyDown(chord.keyCode()),
				input.isPhysicalShiftDown(), input.isPhysicalControlDown(),
				input.isPhysicalAltDown(), input.isPhysicalSuperDown());
	}

	private void handleLiveCaptureShortcut() {
		if (inputHandler == null) {
			return;
		}
		if (!shouldToggleLiveCapture(
				configService.getKeyChord(SonicConfiguration.CAPTURE_TOGGLE_KEY),
				liveCaptureChord, inputHandler)) {
			return;
		}
		switch (liveCaptureController.state()) {
			case ACTIVE -> liveCaptureController.requestStop(LiveCaptureController.StopReason.USER);
			case INACTIVE, FAILED -> startLiveCaptureAttempt(
					liveCaptureController, liveCaptureFailureReporter,
					currentCaptureViewport(), targetFps);
			case STARTING, STOPPING -> { }
		}
	}

	private CaptureViewport currentCaptureViewport() {
		return new CaptureViewport(viewportX, viewportY, viewportWidth, viewportHeight);
	}

	private void captureScreenshotIfRequested() {
		if (inputHandler == null || !inputHandler.isKeyPressed(GLFW_KEY_F12)) {
			return;
		}
		try {
			String timestamp = java.time.LocalDateTime.now()
					.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
			Path path = Path.of("screenshot_" + timestamp + ".png");
			if (!screenshotWriter.captureAndSubmit(path,
					() -> ScreenshotCapture.captureFramebuffer(viewportWidth, viewportHeight))) {
				LOGGER.warning("Screenshot skipped: writer is busy or closed");
			}
		} catch (Exception e) {
			LOGGER.warning("Screenshot failed: " + e.getMessage());
		}
	}

	private void renderLiveCaptureIndicatorIfActive() {
		liveCaptureController.consumeInterruption().ifPresent(interruption -> {
			liveCaptureInterruption = interruption;
			liveCaptureInterruptionExpiryNanos =
					System.nanoTime() + LIVE_CAPTURE_NOTICE_NANOS;
		});
		boolean recording = liveCaptureController.indicatorVisible();
		if (liveCaptureNoticeFinished(recording, System.nanoTime(),
				liveCaptureInterruptionExpiryNanos)) {
			liveCaptureInterruption = null;
		}
		if (!recording && liveCaptureInterruption == null) {
			return;
		}
		prepareOverlayState();
		liveCaptureTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
		if (recording) {
			liveCaptureIndicator.render((int) projectionWidth, (int) realHeight);
		} else {
			liveCaptureIndicator.renderInterruption(
					liveCaptureNoticeText(liveCaptureInterruption),
					(int) projectionWidth, (int) realHeight);
		}
	}

	/**
	 * Executable non-GL seam: whether a latched recording-interrupted notice
	 * should stop being drawn. A new recording supersedes a stale notice, and
	 * an expired one is dropped so it cannot reappear on a later frame.
	 *
	 * <p>Compares elapsed nanos rather than the instants themselves so a
	 * {@code System.nanoTime()} wrap cannot strand a notice on screen.
	 */
	static boolean liveCaptureNoticeFinished(boolean recording, long nowNanos,
			long expiryNanos) {
		return recording || nowNanos - expiryNanos >= 0;
	}

	/** Display text for a recording that ended without the user asking. */
	static String liveCaptureNoticeText(
			LiveCaptureController.Interruption interruption) {
		return switch (interruption) {
			case WINDOW_RESIZED -> "REC STOPPED: RESIZED";
			case CAPTURE_ERROR -> "REC STOPPED: ERROR";
		};
	}

	private void drawLiveCaptureDot(int x, int y, int diameter,
									float red, float green, float blue, float alpha) {
		int radius = diameter / 2;
		for (int row = 0; row < diameter; row++) {
			double dy = row + 0.5 - radius;
			int halfWidth = (int) Math.floor(Math.sqrt(radius * radius - dy * dy));
			new GLCommand(GLCommand.CommandType.RECTI, GL_TRIANGLE_FAN,
					GLCommand.BlendType.SOLID, red, green, blue, alpha,
					x + radius - halfWidth, y + row,
					x + radius + halfWidth, y + row + 1).execute(0, 0, 0, 0);
		}
	}

	private void applySpecialStageClearColor() {
		SpecialStageProvider ssProviderForClear = gameLoop.getActiveSpecialStageProvider();
		if (ssProviderForClear != null) {
			ssProviderForClear.setClearColor();
		} else {
			applyBlackClearColor();
		}
	}

	private void applySpecialStageResultsClearColor() {
		glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
	}

	private void applyTitleScreenClearColor() {
		TitleScreenProvider titleScreen = gameLoop.getTitleScreenProvider();
		if (titleScreen != null) {
			titleScreen.setClearColor();
		}
	}

	private void applyLevelSelectClearColor() {
		LevelSelectProvider levelSelect = gameLoop.getLevelSelectProvider();
		if (levelSelect != null) {
			levelSelect.setClearColor();
		}
	}

	private void applyDataSelectClearColor() {
		DataSelectProvider dataSelect = gameLoop.getDataSelectProvider();
		if (dataSelect != null) {
			dataSelect.setClearColor();
		} else {
			applyBlackClearColor();
		}
	}

	private void applyEndingClearColor() {
		EndingProvider ending = gameLoop.getEndingProvider();
		if (ending != null) {
			ending.setClearColor();
		} else {
			applyBlackClearColor();
		}
	}

	private void applyBlackClearColor() {
		glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
	}

	private void renderUserPauseIndicator() {
		pauseTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
		PauseIndicatorPlacement placement = userPauseIndicatorPlacement(
				(int) projectionWidth,
				(int) realHeight,
				pauseTextRenderer.measureWidth(USER_PAUSE_INDICATOR_TEXT),
				pauseTextRenderer.lineHeight());
		DebugColor color = new DebugColor(255, 255, 255, userPauseIndicatorAlpha(System.nanoTime()));
		pauseTextRenderer.drawShadowedText(
				USER_PAUSE_INDICATOR_TEXT,
				placement.x(),
				placement.y(),
				color);
	}

	static PauseIndicatorPlacement userPauseIndicatorPlacement(
			int logicalWidth,
			int logicalHeight,
			int textWidth,
			int textHeight) {
		int x = Math.max(0, logicalWidth - textWidth - USER_PAUSE_INDICATOR_MARGIN);
		int y = Math.max(0, logicalHeight - textHeight - USER_PAUSE_INDICATOR_MARGIN);
		return new PauseIndicatorPlacement(x, y);
	}

	static int userPauseIndicatorAlpha(long elapsedNanos) {
		long cycleNanos = Math.floorMod(elapsedNanos, USER_PAUSE_INDICATOR_PERIOD_NANOS);
		double phase = cycleNanos / (double) USER_PAUSE_INDICATOR_PERIOD_NANOS;
		double alpha = (0.5d + 0.5d * Math.cos(phase * Math.PI * 2.0d)) * 255.0d;
		return Math.max(0, Math.min(255, (int) Math.round(alpha)));
	}

	static long userPauseIndicatorHiddenUntilAfterScreenshotKey(long keyPressNanos) {
		return keyPressNanos + USER_PAUSE_SCREENSHOT_HIDE_NANOS;
	}

	static boolean shouldRenderUserPauseIndicator(boolean userPaused, long nowNanos, long hiddenUntilNanos) {
		return userPaused && nowNanos >= hiddenUntilNanos;
	}

	static boolean isUserPauseScreenshotSuppressionKeyPressed(InputHandler inputHandler) {
		return inputHandler != null
				&& (inputHandler.isKeyPressed(GLFW_KEY_F12)
				|| inputHandler.isKeyPressed(GLFW_KEY_PRINT_SCREEN));
	}

	private void applyLevelClearColor() {
		levelManager.setClearColor();
	}

	private final class EngineClearActions implements EngineRenderDispatcher.ClearActions {
		@Override public void specialStage() { applySpecialStageClearColor(); }
		@Override public void specialStageResults() { applySpecialStageResultsClearColor(); }
		@Override public void titleScreen() { applyTitleScreenClearColor(); }
		@Override public void levelSelect() { applyLevelSelectClearColor(); }
		@Override public void dataSelect() { applyDataSelectClearColor(); }
		@Override public void ending() { applyEndingClearColor(); }
		@Override public void black() { applyBlackClearColor(); }
		@Override public void level() { applyLevelClearColor(); }
	}

	private final class EngineDrawActions implements EngineRenderDispatcher.DrawActions {
		@Override public void legalDisclaimer() { drawLegalDisclaimer(); }
		@Override public void masterTitle() { drawMasterTitle(); }
		@Override public void editor() { drawEditor(); }
		@Override public void specialStage() { drawSpecialStage(); }
		@Override public void specialStageResults() { drawSpecialStageResults(); }
		@Override public void titleScreen() { drawTitleScreen(); }
		@Override public void continueScreen() {
			resetCameraForScreenSpace();
			var provider = gameLoop.getContinueScreenProvider();
			if (provider != null) provider.draw();
		}
		@Override public void levelSelect() { drawLevelSelect(); }
		@Override public void dataSelect() { drawDataSelect(); }
		@Override public void endingCutscene() { drawEndingCutscene(); }
		@Override public void creditsText() { drawCreditsText(); }
		@Override public void creditsDemo() { drawCreditsDemo(); }
		@Override public void tryAgainEnd() { drawTryAgainEnd(); }
		@Override public void titleCard() { drawTitleCardMode(); }
		@Override public void debugPatterns() { drawDebugPatterns(); }
		@Override public void debugBlocks() { drawDebugBlocks(); }
		@Override public void level() { drawLevel(); }
	}

	private void renderDisplayColorProfileNotification() {
		if (displayColorProfileController == null) {
			return;
		}
		String text = displayColorProfileController.notificationText();
		if (text == null) {
			return;
		}
		float scale = 1.0f;
		int y = 224 - traceHudTextRenderer.lineHeight(scale) - 4;
		traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
		traceHudTextRenderer.drawShadowedText(text, 4, y, DebugColor.YELLOW, scale);
	}

	private void renderDisplayShaderNotification() {
		if (displayShaderController == null) {
			return;
		}
		String text = displayShaderController.notificationText();
		if (text == null) {
			return;
		}
		float scale = 1.0f;
		int y = 224 - traceHudTextRenderer.lineHeight(scale) * 2 - 4;
		traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
		traceHudTextRenderer.drawShadowedText(text, 4, y, DebugColor.YELLOW, scale);
	}

	private void renderDiagnosticOverlays(boolean userRecordingSceneSuppressed,
			boolean playbackHud,
			boolean performanceOverlayVisible,
			RenderOrderRecorder postFadeRecorder) {
		profiler.beginSection("debug");
		boolean renderedDebugRenderer = false;
		if (!userRecordingSceneSuppressed && getCurrentGameMode() == GameMode.SPECIAL_STAGE) {
			SpecialStageProvider ssProvider = gameLoop.getActiveSpecialStageProvider();
			SpecialStageDebugCapabilities debugCapabilities =
					SpecialStageDebugCapabilities.orNone(ssProvider.debugCapabilities());
			if (debugCapabilities.alignment() && ssProvider.isAlignmentTestMode()) {
				if (postFadeRecorder != null) {
					postFadeRecorder.recordPostFadeDiagnostic("SpecialStageDiagnosticOverlay");
				}
				ssProvider.renderAlignmentOverlay(windowWidth, windowHeight);
			} else if (debugCapabilities.lagCompensation()
					&& ssProvider.isLagCompensationDisplayEnabled()) {
				if (postFadeRecorder != null) {
					postFadeRecorder.recordPostFadeDiagnostic("SpecialStageDiagnosticOverlay");
				}
				ssProvider.renderLagCompensationOverlay(windowWidth, windowHeight);
			}
		} else if (!userRecordingSceneSuppressed && (debugViewEnabled || playbackHud)) {
			getDebugRenderer().updateViewport(viewportWidth, viewportHeight);
			if (postFadeRecorder != null) {
				postFadeRecorder.recordPostFadeDiagnostic("DebugOverlay");
			}
			getDebugRenderer().renderDebugInfo();
			renderedDebugRenderer = true;
			resetDebugRenderState();
		}
		if (performanceOverlayVisible && !renderedDebugRenderer) {
			getDebugRenderer().updateViewport(viewportWidth, viewportHeight);
			if (postFadeRecorder != null) {
				postFadeRecorder.recordPostFadeDiagnostic("PerformanceOverlay");
			}
			getDebugRenderer().renderPerformanceOverlay();
			resetDebugRenderState();
		}
		profiler.endSection("debug");
	}

	private static void resetDebugRenderState() {
		// Clean up GL state after debug rendering to prevent macOS event loop issues.
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glUseProgram(0);
	}

	private void renderEscapeToMasterTitlePrompt(RenderOrderRecorder postFadeRecorder) {
		if (gameLoop == null) {
			return;
		}
		EscapeToMasterTitleController controller = gameLoop.getEscapeToMasterTitleController();
		if (controller == null || !controller.visible()) {
			return;
		}
		if (postFadeRecorder != null) {
			postFadeRecorder.recordPostFadeDiagnostic("EscapeToMasterTitlePrompt");
		}
		float scale = 1.0f;
		int x = 4;
		int y = 4;
		traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
		traceHudTextRenderer.drawShadowedText(controller.message(), x, y, DebugColor.WHITE, scale);
		renderEscapeProgressBar(x, y + traceHudTextRenderer.lineHeight(scale) + 2, controller.progress());
	}

	private void renderEscapeProgressBar(int x, int y, double progress) {
		final int width = 144;
		final int height = 6;
		int fillWidth = (int) Math.round(Math.max(0.0, Math.min(1.0, progress)) * (width - 2));

		drawEscapeProgressRect(x, y, x + width, y + height,
				0.0f, 0.0f, 0.0f, 0.65f, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA);
		drawEscapeProgressRect(x, y, x + width, y + 1,
				1.0f, 1.0f, 1.0f, 1.0f, GLCommand.BlendType.SOLID);
		drawEscapeProgressRect(x, y + height - 1, x + width, y + height,
				1.0f, 1.0f, 1.0f, 1.0f, GLCommand.BlendType.SOLID);
		drawEscapeProgressRect(x, y, x + 1, y + height,
				1.0f, 1.0f, 1.0f, 1.0f, GLCommand.BlendType.SOLID);
		drawEscapeProgressRect(x + width - 1, y, x + width, y + height,
				1.0f, 1.0f, 1.0f, 1.0f, GLCommand.BlendType.SOLID);
		if (fillWidth > 0) {
			drawEscapeProgressRect(x + 1, y + 1, x + 1 + fillWidth, y + height - 1,
					1.0f, 1.0f, 0.0f, 1.0f, GLCommand.BlendType.SOLID);
		}
	}

	private void drawEscapeProgressRect(
			int x1,
			int y1,
			int x2,
			int y2,
			float red,
			float green,
			float blue,
			float alpha,
			GLCommand.BlendType blendType) {
		new GLCommand(
				GLCommand.CommandType.RECTI,
				GL_TRIANGLE_FAN,
				blendType,
				red,
				green,
				blue,
				alpha,
				x1,
				y1,
				x2,
				y2).execute(0, 0, 0, 0);
	}

	private boolean updateDisplayShaderInput() {
		if (displayShaderPickerController == null) {
			return false;
		}
		boolean wasOpen = displayShaderPickerController.isOpen();
		DisplayShaderPresetRef currentRef = displayShaderController != null
				? displayShaderController.currentRef()
				: DisplayShaderPresetRef.OFF;
		DisplayShaderPickerController.Action action =
				displayShaderPickerController.update(inputHandler, currentRef);
		if (action.type() == DisplayShaderPickerController.ActionType.ACTIVATE && displayShaderController != null) {
			displayShaderController.select(action.ref());
		} else if (action.type() == DisplayShaderPickerController.ActionType.DOWNLOAD_LIBRETRO_GLSL) {
			startDisplayShaderPackDownload();
		}
		return wasOpen || displayShaderPickerController.isOpen()
				|| action.type() != DisplayShaderPickerController.ActionType.NONE;
	}

	private void applyDisplayShaderPhase(ShaderPhase phase) {
		DisplayShaderPipeline pipeline = graphicsManager.getDisplayShaderPipeline();
		if (pipeline == null || !pipeline.isActive() || pipeline.phase() != phase) {
			return;
		}
		int sourceWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
		int sourceHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
		pipeline.resize(sourceWidth, sourceHeight, viewportWidth, viewportHeight);
		pipeline.apply(viewportX, viewportY, viewportWidth, viewportHeight, displayShaderFrameCounter++);
	}

	private void renderDisplayShaderPickerOverlay() {
		if (displayShaderPickerController == null || !displayShaderPickerController.isOpen()) {
			return;
		}
		renderDisplayShaderPickerBackdrop();
		float scale = 1.0f;
		int x = 4;
		int y = 8;
		int lineHeight = traceHudTextRenderer.lineHeight(scale);
		int maxWidth = Math.max(48, (int) projectionWidth - x - 4);
		traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
		traceHudTextRenderer.drawShadowedText(
				fitDisplayShaderPickerText(displayShaderPickerCurrentFolderText(), maxWidth, scale),
				x,
				y,
				DebugColor.CYAN,
				scale);
		y += lineHeight;
		traceHudTextRenderer.drawShadowedText(
				fitDisplayShaderPickerText(displayShaderPickerController.query(), maxWidth, scale),
				x,
				y,
				DebugColor.WHITE,
				scale);
		y += lineHeight;
		traceHudTextRenderer.drawShadowedText(
				fitDisplayShaderPickerText(DisplayShaderPickerController.downloadHintText(), maxWidth, scale),
				x,
				y,
				DebugColor.LIGHT_GRAY,
				scale);
		y += lineHeight;
		if (displayShaderPackStatus != null && !displayShaderPackStatus.isBlank()) {
			traceHudTextRenderer.drawShadowedText(
					fitDisplayShaderPickerText(displayShaderPackStatus, maxWidth, scale),
					x,
					y,
					displayShaderPackDownloadInProgress ? DebugColor.CYAN : DebugColor.LIGHT_GRAY,
					scale);
			y += lineHeight;
		}

		List<DisplayShaderSelectionModel.SelectionItem> items = displayShaderPickerController.visibleItems();
		int selectedIndex = items.indexOf(displayShaderPickerController.selectedItem());
		int maxEntries = Math.max(1, (224 - y - 4) / Math.max(1, lineHeight));
		int start = Math.max(0, Math.min(selectedIndex - maxEntries / 2, items.size() - maxEntries));
		int end = Math.min(items.size(), start + maxEntries);
		for (int i = start; i < end; i++) {
			DisplayShaderSelectionModel.SelectionItem item = items.get(i);
			boolean selected = i == selectedIndex;
			String text = (selected ? "> " : "  ") + item.displayPath();
			traceHudTextRenderer.drawShadowedText(
					fitDisplayShaderPickerText(text, maxWidth, scale),
					x,
					y,
					selected ? DebugColor.YELLOW : DebugColor.LIGHT_GRAY,
					scale);
			y += lineHeight;
		}
	}

	private String displayShaderPickerCurrentFolderText() {
		String root = configService.getString(SonicConfiguration.DISPLAY_SHADER_LIBRARY_ROOT);
		String normalizedRoot = root == null || root.isBlank() ? "shaders" : root.trim().replace('\\', '/');
		while (normalizedRoot.endsWith("/")) {
			normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
		}
		String folder = displayShaderPickerController.currentFolder();
		return folder == null || folder.isBlank() ? normalizedRoot : normalizedRoot + "/" + folder;
	}

	private void renderDisplayShaderPickerBackdrop() {
		new GLCommand(
				GLCommand.CommandType.RECTI,
				GL_TRIANGLE_FAN,
				GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
				0.0f,
				0.0f,
				0.0f,
				0.58f,
				0,
				0,
				(int) projectionWidth,
				(int) realHeight).execute(0, 0, 0, 0);
	}

	private String fitDisplayShaderPickerText(String text, int maxWidth, float scale) {
		if (text == null || traceHudTextRenderer.measureWidth(text, scale) <= maxWidth) {
			return text == null ? "" : text;
		}
		String suffix = "...";
		int end = text.length();
		while (end > 0 && traceHudTextRenderer.measureWidth(text.substring(0, end) + suffix, scale) > maxWidth) {
			end--;
		}
		return text.substring(0, end) + suffix;
	}

	/**
	 * Updates the game state by one frame.
	 */
	public void update() {
		gameLoop.step();
	}

	/**
	 * Gets the current game mode from the game loop.
	 */
	public GameMode getCurrentGameMode() {
		return gameLoop.getCurrentGameMode();
	}

	/**
	 * Gets the game loop instance for testing purposes.
	 */
	public GameLoop getGameLoop() {
		return gameLoop;
	}

	/**
	 * Static accessor used by {@link com.openggf.TraceSessionLauncher} (and
	 * other {@code com.openggf.*} classes) to reach the singleton game
	 * loop without going through a tier-1 service.
	 */
	public static GameLoop currentGameLoop() {
		return instance != null ? instance.gameLoop : null;
	}

	/**
	 * Drops the process-global reference behind {@link #currentGameLoop()}.
	 *
	 * <p>The constructor publishes {@code this} into a static, and nothing used
	 * to take it back. That matters because {@code currentGameLoop()} is a
	 * static back door: {@link TraceSessionLauncher#retryPendingTeardown()} and
	 * {@link RewindReleaseRetryCoordinator} call it and then drive
	 * {@code GameLoop.returnToMasterTitle()}, which unconditionally runs
	 * {@code MasterTitleScreen.initialize()} -&gt; {@code glCreateShader}. With no
	 * GL context that is not a catchable exception — LWJGL raises it through
	 * {@code JNIEnv::FatalError}, which calls {@code abort()} and takes the
	 * whole JVM down (exit 134).
	 *
	 * <p>So a headless test that constructs an {@code Engine} would otherwise
	 * poison its surefire fork (forks are reused): any later test in that fork
	 * reaching one of those retry paths crashes the JVM, and which fork a class
	 * lands in is nondeterministic. Shutdown and every such test must therefore
	 * release the reference.
	 *
	 * <p>Unconditional: only one {@code Engine} is ever live in a real process,
	 * and a test that constructed one has no handle on any earlier one.
	 */
	public static void clearGlobalInstance() {
		instance = null;
	}

	public void draw() {
		renderDispatcher.draw(getCurrentGameMode(), debugViewEnabled, debugState, drawActions);
	}

	private void drawLegalDisclaimer() {
		resetCameraForScreenSpaceIfPresent();
		if (legalDisclaimerScreen != null) {
			legalDisclaimerScreen.setProjectionMatrix(getProjectionMatrixBuffer());
			legalDisclaimerScreen.draw();
		}
	}

	private void drawMasterTitle() {
		resetCameraForScreenSpaceIfPresent();
		if (masterTitleScreen != null) {
			masterTitleScreen.setViewportWidth((int) realWidth);
			masterTitleScreen.setProjectionMatrix(getProjectionMatrixBuffer());
			masterTitleScreen.draw();
		}
	}

	private void drawEditor() {
		flushEditorDirtyRegionsForRendering();
		levelManager.drawWithSpritePriority(spriteManager);
		editorOverlayRenderer.renderWorldSpaceOverlay();
		graphicsManager.flush();
		graphicsManager.resetForFixedFunction();
		prepareOverlayState();
		editorOverlayRenderer.renderScreenSpaceOverlay();
		graphicsManager.flushScreenSpace();
	}

	private void drawSpecialStage() {
		SpecialStageProvider ssProvider = gameLoop.getActiveSpecialStageProvider();
		if (SpecialStageDebugCapabilities.orNone(ssProvider.debugCapabilities()).spriteViewer()
				&& ssProvider.isSpriteDebugMode()) {
			SpecialStageDebugProvider debugProvider = ssProvider.getDebugProvider();
			if (debugProvider != null) {
				debugProvider.draw();
			} else {
				ssProvider.draw();
			}
		} else {
			ssProvider.draw();
		}
	}

	private void drawSpecialStageResults() {
		var resultsScreen = gameLoop.getResultsScreen();
		if (resultsScreen == null) {
			return;
		}
		camera.setX((short) 0);
		camera.setY((short) 0);

		graphicsManager.beginPatternBatch();

		resultsCommands.clear();
		resultsScreen.appendRenderCommands(resultsCommands);

		graphicsManager.flushPatternBatch();

		if (!resultsCommands.isEmpty()) {
			graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, resultsCommands));
		}

		graphicsManager.flushScreenSpace();
	}

	private void drawTitleScreen() {
		resetCameraForScreenSpace();
		TitleScreenProvider titleScreen = gameLoop.getTitleScreenProvider();
		if (titleScreen != null) {
			titleScreen.draw();
		}
	}

	private void drawLevelSelect() {
		resetCameraForScreenSpace();
		LevelSelectProvider levelSelect = gameLoop.getLevelSelectProvider();
		if (levelSelect != null) {
			levelSelect.draw();
		}
	}

	private void drawDataSelect() {
		resetCameraForScreenSpace();
		DataSelectProvider dataSelect = gameLoop.getDataSelectProvider();
		if (dataSelect != null) {
			dataSelect.draw();
		}
	}

	private void drawEndingCutscene() {
		resetCameraForScreenSpace();
		EndingProvider provider = gameLoop.getEndingProvider();
		if (provider == null) {
			return;
		}
		if (provider.needsLevelBackground()) {
			levelManager.renderEndingBackground(
					provider.getBackgroundVscroll(),
					provider.getBackdropColorOverride());
			graphicsManager.flush();
		}
		provider.draw();
	}

	private void drawCreditsText() {
		resetCameraForScreenSpace();
		EndingProvider provider = gameLoop.getEndingProvider();
		if (provider != null) {
			provider.draw();
		}
	}

	private void drawCreditsDemo() {
		EndingProvider provider = gameLoop.getEndingProvider();
		boolean includeSprites = provider == null || !provider.shouldRenderDemoSpritesOverFade();
		levelManager.drawWithSpritePriority(spriteManager, includeSprites);
	}

	private void drawTryAgainEnd() {
		resetCameraForScreenSpace();
		EndingProvider provider = gameLoop.getEndingProvider();
		if (provider != null) {
			provider.draw();
		}
	}

	private void drawTitleCardMode() {
		levelManager.drawWithSpritePriority(spriteManager);

		graphicsManager.flush();
		graphicsManager.resetForFixedFunction();

		TitleCardProvider titleCardProvider = gameLoop.getTitleCardProvider();
		if (titleCardProvider != null) {
			titleCardProvider.draw();
			graphicsManager.flushScreenSpace();
		}
	}

	private void drawDebugPatterns() {
		levelManager.drawAllPatterns();
		drawActiveLevelTitleCardOverlay();
	}

	private void drawDebugBlocks() {
		levelManager.draw();
		drawActiveLevelTitleCardOverlay();
	}

	private void drawLevel() {
		levelManager.drawWithSpritePriority(spriteManager);
		drawActiveLevelTitleCardOverlay();
	}

	private void drawActiveLevelTitleCardOverlay() {
		TitleCardProvider titleCardProvider = gameLoop.getTitleCardProvider();
		if (titleCardProvider != null && titleCardProvider.isOverlayActive()) {
			graphicsManager.flush();
			graphicsManager.resetForFixedFunction();
			titleCardProvider.draw();
			graphicsManager.flushScreenSpace();
		}
	}

	private void resetCameraForScreenSpaceIfPresent() {
		if (camera != null) {
			camera.setX((short) 0);
			camera.setY((short) 0);
		}
	}

	private void resetCameraForScreenSpace() {
		camera.setX((short) 0);
		camera.setY((short) 0);
	}

	void flushEditorDirtyRegionsForRendering() {
		levelManager.processDirtyRegions();
	}

	private void prepareOverlayState() {
		if (overlayStateReady) {
			return;
		}
		glActiveTexture(GL_TEXTURE0);
		glUseProgram(0);
		glDisable(GL_DEPTH_TEST);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);

		glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

		// Update projection matrix for overlay - stored for shader access
		projectionMatrix.identity().ortho2D(0, (float) projectionWidth, 0, (float) realHeight);
		projectionMatrix.get(matrixBuffer);

		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

		overlayStateReady = true;
	}

	private void cleanup() {
		cleanupStep("screenshots", screenshotWriter::close);
		cleanupStep("live capture", liveCaptureController::close);
		cleanupStep("pending saves", com.openggf.game.save.SessionSaveRequests::flushPendingSaves);
		cleanupStep("session state", SessionManager::clear);
		cleanupStep("donor audio", audioManager::clearDonorAudio);
		cleanupStep("cross-game features", crossGameFeatureProvider::resetState);
		cleanupStep("render context", RenderContext::reset);
		cleanupStep("master title screen", () -> {
			if (masterTitleScreen != null) {
				masterTitleScreen.cleanup();
				masterTitleScreen = null;
			}
		});
		cleanupStep("trace HUD renderer", traceHudTextRenderer::cleanup);
		cleanupStep("pause text renderer", pauseTextRenderer::cleanup);
		cleanupStep("live capture text renderer", liveCaptureTextRenderer::cleanup);
		cleanupStep("VHS rewind effect", () -> {
			if (rewindVhsEffectPass != null) {
				rewindVhsEffectPass.dispose();
				rewindVhsEffectPass = null;
			}
		});
		cleanupStep("graphics manager", graphicsManager::cleanup);
		cleanupStep("presence", gameLoop::closePresence);
		cleanupStep("audio manager", audioManager::destroy);
		cleanupStep("GLFW window", () -> {
			if (window != NULL) {
				glfwFreeCallbacks(window);
				glfwDestroyWindow(window);
				window = NULL;
			}
		});
		cleanupStep("GLFW termination", () -> {
			if (glfwInitialized) {
				glfwTerminate();
				glfwInitialized = false;
			}
		});
		cleanupStep("GLFW error callback", () -> {
			GLFWErrorCallback callback = glfwSetErrorCallback(null);
			if (callback != null) {
				callback.free();
			}
		});
		cleanupStep("global engine instance", Engine::clearGlobalInstance);
	}

	private void cleanupStep(String description, Runnable cleanup) {
		try {
			cleanup.run();
		} catch (Throwable t) {
			LOGGER.log(java.util.logging.Level.WARNING, "Cleanup failed for " + description, t);
		}
	}

	public static void nextDebugState() {
		debugState = debugState.next();
		debugOption = DebugOption.A;
	}

	public static void nextDebugOption() {
		debugOption = debugOption.next();
	}

	public static void main(String[] args) {
		if (isNativeImage() && System.getProperty("org.lwjgl.librarypath") == null) {
			// In a native image, LWJGL can't extract native libs from JARs.
			// Find the bundled .dylib/.so/.dll files next to the executable.
			String libPath = findNativeLibsDir();
			if (libPath != null) {
				System.setProperty("org.lwjgl.librarypath", libPath);
			}
		}
		EngineContext services = EngineContext.fromLegacySingletonsForBootstrap();
		services.configuration().ensureConfigFileExists();
		new Engine(services).run();
	}

	private static String findNativeLibsDir() {
		return findNativeLibsDirForTesting(
				System.getenv("SONIC_NATIVE_LIBS_DIR"),
				ProcessHandle.current().info().command().orElse(""));
	}

	static String findNativeLibsDirForTesting(String envDir, String command) {
		java.io.File executableDir = executableDir(command);
		if (executableDir == null) {
			return null;
		}

		// SONIC_NATIVE_LIBS_DIR is set by the macOS .app launcher because SIP strips
		// DYLD_LIBRARY_PATH for Finder-launched apps. Treat it as a hint only: it must
		// resolve to the same packaged directory as the native executable.
		if (envDir != null && !envDir.isBlank()) {
			java.io.File hintedDir = new java.io.File(envDir);
			if (isSameCanonicalFile(hintedDir, executableDir) && hasNativeLibs(hintedDir)) {
				return canonicalPath(hintedDir);
			}
		}

		if (hasNativeLibs(executableDir)) {
			return canonicalPath(executableDir);
		}

		return null;
	}

	private static java.io.File executableDir(String command) {
		try {
			if (command != null && !command.isBlank()) {
				return new java.io.File(command).getCanonicalFile().getParentFile();
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private static boolean isSameCanonicalFile(java.io.File first, java.io.File second) {
		try {
			return first.getCanonicalFile().equals(second.getCanonicalFile());
		} catch (Exception ignored) {
			return false;
		}
	}

	private static String canonicalPath(java.io.File dir) {
		try {
			return dir.getCanonicalPath();
		} catch (Exception ignored) {
			return dir.getAbsolutePath();
		}
	}

	private static boolean hasNativeLibs(java.io.File dir) {
		if (!dir.isDirectory()) return false;
		String[] files = dir.list();
		if (files == null) return false;
		for (String f : files) {
			if (f.startsWith("liblwjgl.")) return true;   // .dylib or .so
			if (f.equals("lwjgl.dll")) return true;
		}
		return false;
	}

	// For testing - get window handle
	long getWindowHandle() {
		return window;
	}

	/**
	 * Gets the singleton instance of the Engine.
	 * @return the Engine instance, or null if not yet created
	 */
	public static synchronized Engine getInstance() {
		return instance;
	}

	/**
	 * Gets the current projection matrix for use in shaders.
	 * @return the projection matrix
	 */
	public org.joml.Matrix4f getProjectionMatrix() {
		return projectionMatrix;
	}

	/**
	 * Gets the projection matrix data as a float array for shader uniforms.
	 * @return the projection matrix as a 16-element float array
	 */
	public float[] getProjectionMatrixBuffer() {
		return fboProjectionActive ? fboMatrixBuffer : matrixBuffer;
	}

	// FBO projection support - used when rendering to off-screen framebuffers
	private boolean fboProjectionActive = false;
	private final org.joml.Matrix4f fboProjectionMatrix = new org.joml.Matrix4f();
	private final float[] fboMatrixBuffer = new float[16];
	private int fboWidth = 256;
	private int fboHeight = 256;

	/**
	 * Sets up FBO projection mode for rendering to an off-screen framebuffer.
	 * While active, getProjectionMatrixBuffer() returns the FBO projection.
	 *
	 * @param width  The FBO width in pixels
	 * @param height The FBO height in pixels
	 */
	public void beginFBOProjection(int width, int height) {
		this.fboWidth = width;
		this.fboHeight = height;
		fboProjectionMatrix.identity().ortho2D(0, width, 0, height);
		fboProjectionMatrix.get(fboMatrixBuffer);
		fboProjectionActive = true;
	}

	/**
	 * Restores normal screen projection after FBO rendering.
	 */
	public void endFBOProjection() {
		fboProjectionActive = false;
	}

	/**
	 * Returns the current display height for coordinate calculations.
	 * When FBO projection is active, returns the FBO height.
	 * Otherwise returns the normal screen height.
	 */
	public int getCurrentDisplayHeight() {
		return fboProjectionActive ? fboHeight : (int) realHeight;
	}

	/**
	 * Returns whether FBO projection mode is currently active.
	 */
	public boolean isFBOProjectionActive() {
		return fboProjectionActive;
	}

	/**
	 * Parses a comma-separated sidekick configuration string into a list of
	 * character names. Returns an empty list for null, empty, or blank input.
	 */
	public static List<String> parseSidekickConfig(String value) {
		return ActiveGameplayTeamResolver.parseConfiguredSidekicks(value);
	}
}
