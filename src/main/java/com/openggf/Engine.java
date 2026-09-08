package com.openggf;

import com.openggf.architecture.CompositionRoot;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.mods.code.ModClassLoaderFactory;
import com.openggf.mods.code.ModClassResolver;
import com.openggf.mods.code.ModRuntime;
import com.openggf.mods.code.ModFaultBoundary;
import com.openggf.mods.code.ModLevelExportStagingValidator;
import com.openggf.mods.code.EffectiveCatalogPatchEnablement;
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
import com.openggf.editor.persistence.FullLevelExporter;
import com.openggf.editor.render.EditorOverlayRenderer;
import com.openggf.audio.AudioManager;
import com.openggf.audio.DebugFailAfterFramesAudioHandle;
import com.openggf.audio.LWJGLAudioBackend;
import com.openggf.io.ModInputLimits;
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
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.patch.GameplayLaunchRequest;
import com.openggf.game.patch.GameplayTeamAvailability;
import com.openggf.game.patch.ModuleResolutionService;
import com.openggf.game.startup.DonatedDataSelectWarmupTask;
import com.openggf.game.timeattack.TimeAttackLaunchRequest;
import com.openggf.game.timeattack.TimeAttackRuntime;
import com.openggf.game.timeattack.DeterminismFingerprint;
import com.openggf.game.timeattack.mp.LiveLevelProfileFactory;
import com.openggf.game.timeattack.mp.MultiplayerRaceCoordinator;
import com.openggf.game.timeattack.mp.RaceTransport;
import com.openggf.game.timeattack.mp.ServerBrowserScreen;
import com.openggf.game.timeattack.mp.VoteTrackPools;
import com.openggf.net.client.ClientRaceSession;
import com.openggf.net.client.MasterClient;
import com.openggf.net.client.RaceClient;
import com.openggf.net.client.RaceConnection;
import com.openggf.net.host.HostMasterLink;
import com.openggf.net.host.RaceHostServer;
import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.hub.TrackValidationProfile;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlMessage;
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
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.Set;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

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
@com.openggf.game.ModApi
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
	private final ModuleResolutionService moduleResolutionService;

	private Camera camera;
	private DebugRenderer debugRenderer;
	private final PerformanceProfiler profiler;

	private final GameLoop gameLoop;
	private final EngineRenderDispatcher renderDispatcher = new EngineRenderDispatcher();
	private final EngineRenderDispatcher.ClearActions clearActions = new EngineClearActions();
	private final EngineRenderDispatcher.DrawActions drawActions = new EngineDrawActions();
	private final LevelEditorController levelEditorController = new LevelEditorController();
	private EditorSaveManager editorSaveManager;
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
	private RaceHostServer raceHostServer;
	private RaceConnection raceConnection;
	private MasterClient masterClient;
	private String masterRoomId;
	private Thread masterHeartbeatThread;
	private int masterAdvertisedZone = -1;
	private int masterAdvertisedAct = -1;
	private MultiplayerRaceCoordinator multiplayerRaceCoordinator;
	private ControlMessage.RoundConfig multiplayerRoundConfig;
	private String multiplayerCharacter;
	private boolean hostingTimeAttackRoom;

	// Legal disclaimer screen (pre-ROM disclaimer)
	private LegalDisclaimerScreen legalDisclaimerScreen;
	private com.openggf.game.NativeModNoticeScreen nativeModNoticeScreen;

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
	private ModRuntime modRuntime = ModRuntime.empty();
	private final boolean compiledModsSupported = !isNativeImage();
	private java.util.function.BiConsumer<com.openggf.game.patch.ResolutionResult.LaunchAborted, Boolean>
			patchLaunchAbortHandler = this::handlePatchLaunchAbort;

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
		this.moduleResolutionService = engineServices.moduleResolutionService();
		this.profiler = engineServices.profiler();
		this.editorSaveManager = new EditorSaveManager(Path.of("saves"), this::effectiveObjectKeyExists,
				finding -> LOGGER.warning(finding.code() + ": " + finding.message()));
		this.graphicsManager.setPerformanceProfiler(profiler);
		this.editorOverlayRenderer = new EditorOverlayRenderer(levelEditorController, graphicsManager);
		this.gameLoop = new GameLoop(engineServices);
		this.gameLoop.setCharacterAvailabilitySupplier(() -> modRuntime.characterAvailability());
		this.gameLoop.setEditorStateSyncHandler(this::syncEditorState);
		this.gameLoop.setMasterTitleScreenSupplier(() -> masterTitleScreen);
		this.gameLoop.setMasterTitleExitHandler(this::exitMasterTitleScreen);
		this.gameLoop.setStandaloneMasterTitleExitHandler(this::exitStandaloneMasterTitle);
		this.gameLoop.setLegalDisclaimerScreenSupplier(() -> legalDisclaimerScreen);
		this.gameLoop.setLegalDisclaimerExitHandler(this::exitLegalDisclaimer);
		this.gameLoop.setNativeModNoticeScreenSupplier(() -> nativeModNoticeScreen);
		this.gameLoop.setNativeModNoticeExitHandler(this::exitNativeModNotice);
		this.gameLoop.setDataSelectActionHandler(this::launchGameplayFromDataSelect);
		this.gameLoop.setTimeAttackLaunchHandler(this::launchTimeAttack);
		this.gameLoop.setTimeAttackNetworkHandler(new com.openggf.game.timeattack.TimeAttackMenu.NetworkStarter() {
			@Override
			public void host(TimeAttackLaunchRequest request, String policy,
							 String lockedCharacter, int windowSeconds) {
				hostTimeAttackRoom(request, policy, lockedCharacter, windowSeconds);
			}

			@Override
			public void join(TimeAttackLaunchRequest request, String address) {
				joinTimeAttackRoom(request, address);
			}

			@Override
			public void browse(TimeAttackLaunchRequest request, String policy,
							   String lockedCharacter, int windowSeconds) {
				browseTimeAttackRooms(request, policy, lockedCharacter, windowSeconds);
			}
		});
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
				levelEditorController, () -> camera, () -> graphicsManager, this::saveCurrentEditorLevel,
				this::exportCurrentEditorLevel);

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
		initializeExternalContentAtBoot();
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
		installEditorTextInputCallback();
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

	private void installEditorTextInputCallback() {
		glfwSetCharCallback(window, (windowHandle, codepoint) -> {
			if (getCurrentGameMode() == GameMode.EDITOR) {
				editorInputHandler.handleTextInputCodepoint(codepoint);
			}
		});
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
				failure, com.openggf.game.save.SessionSaveRequests::flushPendingSaves);
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
			case MASTER_TITLE, GAME -> enterBootModNoticeOrProceed(false);
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
		GameModule rootModule;
		GameDataSource dataSource;
		try {
			Rom rom = romManager.getRom();
			var detectedModule = romDetectionService.detectAndCreateModule(rom);
			if (detectedModule.isEmpty()) {
				showStartupRomError("ROM not recognized or corrupt. OpenGGF requires a supported Sonic 1, Sonic 2, or Sonic 3&K ROM.");
				return;
			}
			rootModule = detectedModule.orElseThrow();
			dataSource = StockGameDataSources.pinned(rom, rootModule);
		} catch (IOException e) {
			showStartupRomError("Failed to load ROM during game initialization: " + e.getMessage());
			return;
		}
		GameModule module = resolveInitialModuleForLaunch(rootModule);
		if (module == null) {
			return;
		}
		if (!preparePresentationForLaunch(module)) {
			return;
		}
		GameplayModeContext gameplayMode = SessionManager.openGameplaySession(
				rootModule, module, dataSource, null);
		initializeGameplayRuntime(gameplayMode, true);
		boolean generationRan = maybeGenerateDonatedDataSelectImagesBeforeStartupMode(module);
		if (generationRan) {
			// Preview capture loaded full levels into the LevelManager and
			// GraphicsManager, corrupting the pattern atlas, palette textures,
			// DPLC banks, sprite art, and other GPU/manager state.
			// Reset GPU state (pattern atlas + palette textures) and rebuild
			// the gameplay mode so everything starts from a clean slate.
			graphicsManager.resetPatternAndPaletteState();
			gameplayMode = SessionManager.openGameplaySession(
					rootModule, module, dataSource, null);
			initializeGameplayRuntime(gameplayMode, false);
		} else {
			graphicsManager.runPendingRenderThreadTasks();
		}
		enterConfiguredStartupMode();
	}

	/** Detection-free boot for one effective standalone descriptor. */
	void initializeStandaloneGame(com.openggf.mods.ModDescriptor descriptor) {
		initializeStandaloneGame(descriptor, com.openggf.game.MasterTitleEntry.Action.NEW_GAME);
	}

	void initializeStandaloneGame(com.openggf.mods.ModDescriptor descriptor,
			com.openggf.game.MasterTitleEntry.Action action) {
		Objects.requireNonNull(descriptor, "descriptor");
		Objects.requireNonNull(action, "action");
		try {
			StandaloneSessionLaunch launch = openStandaloneSession(descriptor, action);
			GameplayModeContext gameplay = launch.gameplay();
			GameModule module = gameplay.getWorldSession().getGameModule();
			pinStandaloneTeam(launch.team());
			if (!preparePresentationForLaunch(module, descriptor.manifest().id())) return;
			initializeGameplayRuntime(gameplay, true);
			loadLevelFromDataSelect(launch.zone(), launch.act());
			restoreGameplayModeFromDataSelectPayload(gameplay, launch.payload());
			gameLoop.setGameMode(GameMode.LEVEL);
			if (action == com.openggf.game.MasterTitleEntry.Action.NEW_GAME) {
				gameLoop.requestSaveForCurrentSession(com.openggf.game.save.SaveReason.NEW_SLOT_START);
			}
		} catch (com.openggf.mods.code.ModFaultBoundary.CallbackAborted aborted) {
			LOGGER.log(java.util.logging.Level.SEVERE,
					"Standalone mod callback aborted launch", aborted);
			showStandaloneLaunchError(descriptor.manifest().id());
		} catch (IOException | RuntimeException failure) {
			LOGGER.log(java.util.logging.Level.SEVERE, "Standalone mod launch failed", failure);
			showStandaloneLaunchError(descriptor.manifest().id());
		}
	}

	/** Package-visible no-render standalone session seam for headless integration tests. */
	GameplayModeContext openStandaloneSession(com.openggf.mods.ModDescriptor descriptor)
			throws IOException {
		StandalonePrepared prepared = prepareStandalone(descriptor);
		return com.openggf.tools.HeadlessGameBoot.openStandaloneSessionForBoot(
				EngineServices.current(), prepared.module(), prepared.source());
	}

	private StandaloneSessionLaunch openStandaloneSession(
			com.openggf.mods.ModDescriptor descriptor,
			com.openggf.game.MasterTitleEntry.Action action) throws IOException {
		StandalonePrepared prepared = prepareStandalone(descriptor);
		GameModule module = prepared.module();
		SelectedTeam fallback = new SelectedTeam(
				standaloneDefaultCharacter(module.getPlayableCharacterRegistry()).persisted(), List.of());
		Map<String, Object> payload = action == com.openggf.game.MasterTitleEntry.Action.CONTINUE
				? requireStandaloneSlotPayload(module) : null;
		StandaloneSavePayload parsed = payload == null ? null : parseStandalonePayload(payload);
		SelectedTeam team = parsed == null ? fallback : parsed.team();
		validateStandaloneTeam(module, team);
		int zone = parsed == null ? 0 : parsed.zone();
		int act = parsed == null ? 0 : parsed.act();
		validateStandaloneLocation(module, zone, act);
		var saveContext = com.openggf.game.save.SaveSessionContext.forSlot(
				module.getGameCode(), 1, team, zone, act);
		GameplayModeContext gameplay = com.openggf.tools.HeadlessGameBoot.openStandaloneSessionForBoot(
				EngineServices.current(), module, prepared.source(), saveContext);
		return new StandaloneSessionLaunch(gameplay, team, zone, act, payload);
	}

	private StandalonePrepared prepareStandalone(com.openggf.mods.ModDescriptor descriptor)
			throws IOException {
		Objects.requireNonNull(descriptor, "descriptor");
		if (descriptor.manifest().type() != com.openggf.mods.ModType.STANDALONE) {
			throw new IllegalArgumentException("Descriptor is not standalone");
		}
		String owner = descriptor.manifest().id();
		com.openggf.mods.ModDescriptor retained = modRuntime.standaloneDescriptor(owner);
		if (!retained.sha256().equals(descriptor.sha256())
				|| !retained.manifest().equals(descriptor.manifest())) {
			throw new IllegalArgumentException("Standalone descriptor does not match runtime snapshot");
		}
		GameModule module = modRuntime.prepareStandaloneModule(owner).orElseThrow(() ->
				new com.openggf.mods.code.ModRegistrationException(owner,
						"Standalone registration did not publish a module"));
		ModSubsystem.current().recordRegistrationFailures(modRuntime.registrationFailures());
		GameDataSource source = new ModAssetDataSource(
				owner, modRuntime.standaloneAssetSnapshot(owner));
		return new StandalonePrepared(module, source);
	}

	private void pinStandaloneTeam(SelectedTeam team) {
		configService.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, team.mainCharacter());
		configService.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
				String.join(",", team.sidekicks()));
	}

	private Map<String, Object> requireStandaloneSlotPayload(GameModule module) throws IOException {
		com.openggf.game.save.SessionSaveRequests.flushPendingSaves();
		SaveSlotSummary summary = new SaveManager(com.openggf.game.save.SavePaths.root())
				.readSlotSummary(module.getGameCode(), 1);
		if (!summary.isLoadable()) throw new IOException("Standalone Continue slot is unavailable");
		Map<String, Object> payload = summary.payload();
		parseStandalonePayload(payload);
		return payload;
	}

	private static void validateStandaloneTeam(GameModule module, SelectedTeam team) throws IOException {
		var definitions = module.getPlayableCharacterRegistry().definitions();
		boolean mainKnown = definitions.keySet().stream()
				.anyMatch(key -> key.persisted().equals(team.mainCharacter()));
		boolean sidekicksKnown = team.sidekicks().stream().allMatch(value -> definitions.keySet().stream()
				.anyMatch(key -> key.persisted().equals(value)));
		if (!mainKnown || !sidekicksKnown) throw new IOException("Standalone Continue slot names unknown characters");
	}

	private static void validateStandaloneLocation(GameModule module, int zone, int act) throws IOException {
		if (zone < 0 || zone >= module.getZoneRegistry().getZoneCount()
				|| act < 0 || act >= module.getZoneRegistry().getActCount(zone)) {
			throw new IOException("Standalone Continue slot names an unknown zone/act");
		}
	}

	private record StandalonePrepared(GameModule module, GameDataSource source) { }
	private record StandaloneSessionLaunch(GameplayModeContext gameplay, SelectedTeam team,
			int zone, int act, Map<String, Object> payload) { }
	private record StandaloneSavePayload(SelectedTeam team, int zone, int act) { }

	private static StandaloneSavePayload parseStandalonePayload(Map<String, Object> payload)
			throws IOException {
		if (!(payload.get("mainCharacter") instanceof String main) || main.isBlank()
				|| !(payload.get("sidekicks") instanceof List<?> rawSidekicks)
				|| rawSidekicks.stream().anyMatch(value -> !(value instanceof String))) {
			throw new IOException("Standalone Continue slot has invalid launch data");
		}
		int zone = exactStandaloneSaveIndex(payload.get("zone"), "zone");
		int act = exactStandaloneSaveIndex(payload.get("act"), "act");
		if (zone < 0 || act < 0) throw new IOException("Standalone Continue location is negative");
		List<String> sidekicks = rawSidekicks.stream().map(String.class::cast).toList();
		return new StandaloneSavePayload(new SelectedTeam(main, sidekicks), zone, act);
	}

	private static int exactStandaloneSaveIndex(Object value, String field) throws IOException {
		if (value instanceof Integer integer) return integer;
		if (value instanceof Long longValue
				&& longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
			return longValue.intValue();
		}
		throw new IOException("Standalone Continue " + field + " must be an exact 32-bit integer");
	}

	static CharacterKey standaloneDefaultCharacter(PlayableCharacterRegistry registry) {
		return Objects.requireNonNull(registry, "registry").definitions().keySet().stream()
				.findFirst().orElseThrow(() ->
						new IllegalStateException("Standalone module has no playable character"));
	}

	GameModule resolveInitialModuleForLaunch(GameModule rootModule) {
		ModuleResolutionService.LaunchPolicy launchPolicy =
				configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)
						? ModuleResolutionService.LaunchPolicy.DETERMINISTIC
						: ModuleResolutionService.LaunchPolicy.STANDARD;
		ModuleResolutionService.PreparedLaunch launch = moduleResolutionService.prepareLaunch(launchPolicy);
		return consumePatchResolution(moduleResolutionService.resolveForLaunchResult(launch, rootModule,
				GameplayLaunchRequest.fromConfig(configService, rootModule.getGameId().code())), false);
	}

	void setPatchLaunchAbortHandlerForTests(
			java.util.function.Consumer<com.openggf.game.patch.ResolutionResult.LaunchAborted> handler) {
		Objects.requireNonNull(handler, "handler");
		patchLaunchAbortHandler = (aborted, activeSession) -> handler.accept(aborted);
	}

	private GameModule consumePatchResolution(com.openggf.game.patch.ResolutionResult result,
			boolean activeSession) {
		if (result instanceof com.openggf.game.patch.ResolutionResult.Resolved resolved) {
			recordResolvedOwnerFailures(resolved.ownerFailures());
			return resolved.module();
		}
		var aborted = (com.openggf.game.patch.ResolutionResult.LaunchAborted) result;
		if (!(aborted.failedOwner() instanceof com.openggf.game.patch.PatchOwner.Mod)) {
			throw new IllegalStateException("Built-in patch launch failed at " + aborted.patchId(),
					aborted.cause());
		}
		patchLaunchAbortHandler.accept(aborted, activeSession);
		return null;
	}

	private void recordResolvedOwnerFailures(
			java.util.Map<com.openggf.game.patch.PatchOwner, Throwable> failures) {
		java.util.IdentityHashMap<Throwable, java.util.Set<String>> grouped =
				new java.util.IdentityHashMap<>();
		for (var entry : failures.entrySet()) {
			if (entry.getKey() instanceof com.openggf.game.patch.PatchOwner.Mod mod) {
				grouped.computeIfAbsent(entry.getValue(), ignored -> new java.util.LinkedHashSet<>())
						.add(mod.modId());
			}
		}
		grouped.forEach((failure, owners) -> {
			modRuntime.disableOwnersForProcess(owners);
			ModSubsystem.current().recordOwnerFailures(
					owners, failure, "MOD_PATCH_METADATA_FAILED");
		});
	}

	private void handlePatchLaunchAbort(
			com.openggf.game.patch.ResolutionResult.LaunchAborted aborted,
			boolean activeSession) {
		LOGGER.log(java.util.logging.Level.SEVERE,
				"Mod patch launch aborted at " + aborted.patchId(), aborted.cause());
		java.util.Set<String> failedMods = aborted.failedOwners().stream()
				.filter(com.openggf.game.patch.PatchOwner.Mod.class::isInstance)
				.map(com.openggf.game.patch.PatchOwner.Mod.class::cast)
				.map(com.openggf.game.patch.PatchOwner.Mod::modId)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		modRuntime.disableOwnersForProcess(failedMods);
		ModSubsystem.current().recordOwnerFailures(failedMods, aborted.cause(),
				"MOD_PATCH_APPLY_FAILED");
		if (activeSession) {
			returnToMasterTitleScreen();
		} else {
			showModLaunchError();
		}
	}

	private void showModLaunchError() {
		showModLaunchError(configService.getString(SonicConfiguration.DEFAULT_ROM));
	}

	private void showModLaunchError(String selectedGameId) {
		gameplayMode = null;
		gameLoop.setGameplayMode(null);
		if (masterTitleScreen != null) {
			masterTitleScreen.cleanup();
		}
		masterTitleScreen = createMasterTitleScreen();
		if (graphicsManager.isGlInitialized()) {
			masterTitleScreen.initialize();
		}
		masterTitleScreen.showModLaunchError(selectedGameId);
		gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
	}

	void showStandaloneLaunchError() {
		showStandaloneLaunchError(configService.getString(SonicConfiguration.DEFAULT_ROM));
	}

	void showStandaloneLaunchError(String owner) {
		SessionManager.closeGameplaySession();
		showModLaunchError(owner);
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

	private void exitStandaloneMasterTitle(com.openggf.game.MasterTitleEntry.Launch launch) {
		if (!(launch.entry() instanceof com.openggf.game.MasterTitleEntry.Standalone standalone)) {
			throw new IllegalArgumentException("Standalone launch requires a standalone entry");
		}
		com.openggf.mods.ModDescriptor descriptor = ModSubsystem.current().processCatalog().effective()
				.orderedEnabled().stream()
				.filter(value -> value.manifest().type() == com.openggf.mods.ModType.STANDALONE
						&& value.manifest().id().equals(standalone.owner()))
				.findFirst().orElseThrow(() -> new IllegalArgumentException(
						"Standalone entry is no longer effective: " + standalone.owner()));
		if (com.openggf.mods.NativeUnsupportedMods.blocksStandalone(
				descriptor, compiledModsSupported)) {
			throw new IllegalStateException(
					"Standalone mod requires the JVM build (code unsupported on native): "
							+ standalone.owner());
		}
		refreshLaunchSessionCachedConfig();
		applyResolvedDisplayDimensions();
		if (masterTitleScreen != null) {
			masterTitleScreen.cleanup();
			masterTitleScreen = null;
		}
		resetForGameplayFromMasterTitle();
		initializeStandaloneGame(descriptor, launch.action());
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

		enterBootModNoticeOrProceed(true);
	}

	private void proceedToMasterTitleOrGame(boolean fadeFromBlack) {
		boolean masterTitleOnStartup = configService.getBoolean(
				SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP);
		if (masterTitleOnStartup) {
			masterTitleScreen = createMasterTitleScreen();
			masterTitleScreen.initialize();
			gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
		} else {
			initializeGame();
		}

		if (fadeFromBlack) {
			graphicsManager.getFadeManager().startFadeFromBlack(null);
		}
	}

	private void enterBootModNoticeOrProceed(boolean fadeFromBlackWhenNoNotice) {
		List<com.openggf.mods.ModDescriptor> unsupported =
				com.openggf.mods.NativeUnsupportedMods.compute(
						ModSubsystem.current().processCatalog().scanned(),
						ModSubsystem.current().startupModState(),
						compiledModsSupported);
		if (unsupported.isEmpty()) {
			proceedToMasterTitleOrGame(fadeFromBlackWhenNoNotice);
			return;
		}
		List<String> names = unsupported.stream()
				.map(descriptor -> descriptor.manifest().name()).toList();
		LOGGER.warning(com.openggf.mods.NativeUnsupportedMods.NOTICE_HEADER + " "
				+ String.join(", ", unsupported.stream()
						.map(descriptor -> descriptor.manifest().id()).toList()));
		nativeModNoticeScreen = new com.openggf.game.NativeModNoticeScreen(
				graphicsManager.getFadeManager(),
				com.openggf.mods.NativeUnsupportedMods.noticeLines(
						names, com.openggf.game.NativeModNoticeScreen.MAX_VISIBLE_MOD_LINES));
		nativeModNoticeScreen.initialize();
		gameLoop.setNativeModNoticeExitHandler(this::exitNativeModNotice);
		gameLoop.setGameMode(GameMode.NATIVE_MOD_NOTICE);
	}

	private void exitNativeModNotice() {
		if (nativeModNoticeScreen != null) {
			nativeModNoticeScreen.cleanup();
			nativeModNoticeScreen = null;
		}
		proceedToMasterTitleOrGame(true);
	}

	private void resetForGameplayFromMasterTitle() {
		var worldSession = SessionManager.getCurrentWorldSession();
		if (worldSession != null) {
			worldSession.getGameModule().resetModuleScopedState();
		}
		SessionManager.clear();
		romManager.close();
		GameModuleRegistry.reset();
		ModSubsystem.current().returnToTitle();
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
		if (multiplayerRaceCoordinator != null) {
			multiplayerRaceCoordinator.detachRuntime();
		}
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
		if (multiplayerRaceCoordinator != null && multiplayerRaceCoordinator.hudState().active()) {
			openRaceLobbyOnCurrentTitle();
		}
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
			EditorSaveManager.SaveResult save = editorSaveManager.save(
					module.getGameId(), module.getObjectPlacementEncoding(),
					worldSession.getCurrentZone(), worldSession.getCurrentAct(), mutableLevel);
			levelEditorController.setPersistenceStatus(save.persistenceStatus());
			return true;
		} catch (IOException e) {
			LOGGER.warning("Failed to save editor edits: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Production editor-export policy: a stable, non-overwriting directory per active game/act,
	 * reserved mod ids derived from the runtime indices, stock registry start/music metadata,
	 * and the effective (already patched) game module.
	 */
	private void exportCurrentEditorLevel() {
		MutableLevel level = levelEditorController.currentLevel();
		com.openggf.game.session.WorldSession session = SessionManager.getCurrentWorldSession();
		if (level == null || session == null) return;
		GameModule module = session.getGameModule();
		int zone = session.getCurrentZone(), act = session.getCurrentAct();
		ZoneRegistry zones = module.getZoneRegistry();
		int[] start = zones.getStartPosition(zone, act);
		MusicReference reference = zones.getMusicReference(zone, act);
		FullLevelExporter.ExportMusic music = reference instanceof MusicReference.Namespaced keyed
				? new FullLevelExporter.ExportMusic.Track(keyed.owner(), keyed.localName())
				: new FullLevelExporter.ExportMusic.Stock(Math.max(0, ((MusicReference.Stock) reference).musicId()));
		Path output = Path.of("exports", "editor", module.getGameId().code(),
				"zone_" + zone + "_act_" + act);
		FullLevelExporter.ExportRequest request = new FullLevelExporter.ExportRequest(output,
				zones.getZoneName(zone) + " EDITOR EXPORT", Math.addExact(0x40, zone),
				Math.addExact(0x400, Math.addExact(Math.multiplyExact(zone, 16), act)),
				start[0], start[1], music);
		try {
			levelEditorController.exportLevel(new FullLevelExporter(new ModLevelExportStagingValidator()), request);
			LOGGER.info("Exported editor level to " + output.toAbsolutePath().normalize());
		} catch (IOException | RuntimeException failure) {
			LOGGER.warning("Failed to export editor level: " + failure.getMessage());
		}
	}

	private boolean effectiveObjectKeyExists(String objectKey) {
		com.openggf.game.session.WorldSession session = SessionManager.getCurrentWorldSession();
		return session != null && session.getGameModule().createObjectRegistry().hasObjectKey(objectKey);
	}

	public void startGameplayFromBeginning() {
		GameplayModeContext gameplay = SessionManager.restartGameplayFromBeginning();
		initializeGameplayRuntime(gameplay, false);
		loadDefaultStartingLevel(false);
		gameLoop.setGameMode(GameMode.LEVEL);
	}

	private void initializeGameplayRuntime(GameplayModeContext gameplayMode, boolean initializeGlobalGameplayServices) {
		GameModule module = gameplayMode.getWorldSession().getGameModule();
		com.openggf.game.session.PatternWindowSessionState.install(
				gameplayMode.getWorldSession(),
				ModSubsystem.current().patternWindowStateForSession());
		GameplaySessionFactory.attachManagers(gameplayMode, EngineServices.current());
		bindGameplayMode(gameplayMode);
		gameplayMode.getGameStateManager().configureSpecialStageProgress(
				module.getSpecialStageCycleCount(),
				module.getChaosEmeraldCount());

		if (initializeGlobalGameplayServices) {
			initializeGlobalGameplayServices();
		}

		GameplayTeamBootstrap.BootstrappedTeam team = GameplayTeamBootstrap.registerActiveTeam(
				module, gameplayMode.getWorldSession().getPlayableCharacterRegistry(),
				modRuntime.characterAvailability(), spriteManager, configService,
				GameplayTeamBootstrap.DEFAULT_MAIN_X, GameplayTeamBootstrap.DEFAULT_MAIN_Y,
				ModCharacterFallbackFindings.sink(ModSubsystem.current().runtimeFindings()));
		camera.setFocusedSprite(team.mainSprite());
		camera.updatePosition(true);
	}

	private void initializeGlobalGameplayServices() {
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

	private void initializeExternalContentAtBoot() {
		ExternalContentPolicy policy = new ExternalContentPolicy(externalContentBootMode(
				configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)));
		ModSubsystem.installAtBoot(policy, ModSubsystem.normalBootLoader(
				() -> Path.of("mods").toAbsolutePath().normalize(),
				ModInputLimits.production(), StockMusicDomains::containsSupported,
				ModSubsystem.SessionAudioBoundary.audioManager(audioManager)),
				compiledModsSupported);
		modRuntime = replaceModRuntime(modRuntime, ModRuntime.empty());
		ModSubsystem.current().installRewindClassResolver(
				com.openggf.level.objects.RewindClassResolver.ENGINE_ONLY);
		try {
			var effectiveMods = ModSubsystem.current().processCatalog().effective();
			ModSubsystem.current().transferDevelopmentSourceOwnership();
			modRuntime = replaceModRuntime(modRuntime,
					new ModClassLoaderFactory(Engine.class.getClassLoader())
							.create(effectiveMods, ModSubsystem.current().trustedCodeOwners(),
									compiledModsSupported));
			modRuntime.installFaultBoundary(ModSubsystem.current().createFaultBoundary(modRuntime));
			modRuntime.installSaveFindingSink((owner, finding) ->
					ModSubsystem.current().runtimeFindings().upsertOwnerFinding(owner,
							new com.openggf.mods.ModFinding(
									com.openggf.mods.ModFindingSeverity.WARNING,
									finding.code(), finding.detail(), null)));
			ModSubsystem.current().installRewindClassResolver(
					new ModClassResolver(modRuntime, Engine.class.getClassLoader()));
			moduleResolutionService.installModPlanSource(
					new EffectiveCatalogPatchEnablement(effectiveMods), enablement -> {
						var plan = modRuntime.newRegistrationPlan();
						ModSubsystem.current().recordRegistrationFailures(
								modRuntime.registrationFailures());
						return plan;
					});
		} catch (IOException error) {
			LOGGER.log(java.util.logging.Level.WARNING, "Compiled-mod runtime initialization failed", error);
		}
	}

	static ExternalContentMode externalContentBootMode(boolean testMode){
		return testMode&&!com.openggf.mods.DevelopmentModSource.isConfigured()
				?ExternalContentMode.STARTUP_DETERMINISTIC:ExternalContentMode.NORMAL;
	}

	private MasterTitleScreen createMasterTitleScreen() {
		ensureMasterTitleAudioBackend();
		ModuleResolutionService.LaunchPolicy policy =
				configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)
						? ModuleResolutionService.LaunchPolicy.DETERMINISTIC
						: ModuleResolutionService.LaunchPolicy.STANDARD;
		ModuleResolutionService.PreparedLaunch preparedLaunch =
				moduleResolutionService.prepareLaunch(policy);
		MasterTitleScreen screen = new MasterTitleScreen(configService,
				new com.openggf.game.launch.LaunchProfileStore(
						configService, moduleResolutionService, preparedLaunch),
				masterTitleEntries(),
				cue -> audioManager.playSfx(cue.sfxName()));
		if (ModSubsystem.current().policy().mayScanAtBoot()) {
			screen.setModManagerScreenFactory(font -> ModSubsystem.current().createManager(font));
		}
		return screen;
	}

	private void ensureMasterTitleAudioBackend() {
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

	private List<com.openggf.game.MasterTitleEntry> masterTitleEntries() {
		// This menu's reader is distinct from the in-game async writer.
		com.openggf.game.save.SessionSaveRequests.flushPendingSaves();
		List<com.openggf.game.MasterTitleEntry> entries = new ArrayList<>();
		entries.add(new com.openggf.game.MasterTitleEntry.Stock(MasterTitleScreen.GameEntry.SONIC_1));
		entries.add(new com.openggf.game.MasterTitleEntry.Stock(MasterTitleScreen.GameEntry.SONIC_2));
		entries.add(new com.openggf.game.MasterTitleEntry.Stock(MasterTitleScreen.GameEntry.SONIC_3K));
		SaveManager saves = new SaveManager(com.openggf.game.save.SavePaths.root());
		for (com.openggf.mods.ModDescriptor descriptor
				: ModSubsystem.current().processCatalog().effective().orderedEnabled()) {
			if (descriptor.manifest().type() != com.openggf.mods.ModType.STANDALONE) continue;
			if (com.openggf.mods.NativeUnsupportedMods.blocksStandalone(
					descriptor, compiledModsSupported)) {
				continue;
			}
			String owner = descriptor.manifest().id();
			boolean canContinue = false;
			try {
				SaveSlotSummary summary = saves.readSlotSummary(owner, 1);
				canContinue = summary.isLoadable()
						&& validStandaloneTitlePayload(owner, summary.payload());
			} catch (IOException failure) {
				LOGGER.log(java.util.logging.Level.WARNING,
						"Failed to inspect standalone Continue slot for " + owner, failure);
			}
			entries.add(new com.openggf.game.MasterTitleEntry.Standalone(
					owner, descriptor.manifest().name(), canContinue));
		}
		return List.copyOf(entries);
	}

	private boolean validStandaloneTitlePayload(String owner, Map<String, Object> payload) {
		try {
			StandaloneSavePayload parsed = parseStandalonePayload(payload);
			GameModule module = modRuntime.prepareStandaloneModule(owner).orElseThrow(() ->
					new IOException("Standalone module is unavailable"));
			validateStandaloneTeam(module, parsed.team());
			validateStandaloneLocation(module, parsed.zone(), parsed.act());
			return true;
		} catch (IOException | RuntimeException invalid) {
			return false;
		}
	}

	private boolean preparePresentationForLaunch(GameModule module) {
		return preparePresentationForLaunch(module, null);
	}

	private boolean preparePresentationForLaunch(GameModule module, String standaloneOwner) {
		try {
			if (configService.getBoolean(SonicConfiguration.AUDIO_ENABLED)) {
				audioManager.setBackendForLaunch(new LWJGLAudioBackend(configService, profiler));
			}
			ModSubsystem subsystem = ModSubsystem.current();
			// Muting speaker output does not remove creator asset identity. Level
			// initialization and object callbacks still route exact namespaced
			// tracks/SFX through the launch-owned port, so prepare that port for
			// every content-enabled session even when no device backend is opened.
			if (subsystem.policy().mayUseInSession()) {
				subsystem.beginNormalSession(audioManager.outputSampleRate(), module.getGameCode());
			}
			return true;
		} catch (RuntimeException error) {
			LOGGER.severe("Failed to prepare external presentation content: " + error.getMessage());
			ModSubsystem.current().returnToTitle();
			audioManager.resetState();
			if (standaloneOwner != null) showStandaloneLaunchError(standaloneOwner);
			else showStartupRomError("Failed to initialize audio or mod presentation content.");
			return false;
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

	void loadDefaultStartingLevel(boolean requireRom) {
		if (requireRom && !romManager.isRomAvailable()) {
			return;
		}
		try {
			levelManager.loadZoneAndActForFreshRuntime(0, 0);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void launchGameplayFromDataSelect(com.openggf.game.dataselect.DataSelectAction action) {
		com.openggf.game.save.SessionSaveRequests.flushPendingSaves();
		GameModule module = SessionManager.requireCurrentGameModule();
		SaveManager saveManager = new SaveManager(com.openggf.game.save.SavePaths.root());
		Map<String, Object> loadedPayload = loadDataSelectPayload(module, action, saveManager);
		com.openggf.game.save.SaveSessionContext saveContext = createDataSelectSaveContext(module, action, saveManager);
		String gameId = SessionManager.getCurrentWorldSession().rootGameModule().getGameId().code();
		ModuleResolutionService.PreparedLaunch preparedLaunch =
				moduleResolutionService.prepareLaunch(ModuleResolutionService.LaunchPolicy.STANDARD);
		List<String> availableCharacters = new ArrayList<>(stockCharacters(gameId));
		availableCharacters.addAll(moduleResolutionService.availableMainCharacters(
				preparedLaunch, gameId));
		if (CrossGameFeatureProvider.isActive()) {
			availableCharacters.addAll(stockCharacters(crossGameFeatureProvider.getDonorGameId()));
		}
		GameplayModeContext gameplay = openDataSelectPatchSession(
				saveContext, availableCharacters, preparedLaunch);
		if (gameplay == null) {
			return;
		}
		initializeGameplayRuntime(gameplay, true);
		loadLevelFromDataSelect(action.zone(), action.act());
		restoreGameplayModeFromDataSelectPayload(gameplayMode, loadedPayload);
		gameLoop.setGameMode(GameMode.LEVEL);

		dataSelectLaunchSaveReason(action.type())
				.ifPresent(gameLoop::requestSaveForCurrentSession);
	}

	/** Package-visible no-render seam for data-select patch relaunch integration tests. */
	GameplayModeContext openDataSelectPatchSession(
			com.openggf.game.save.SaveSessionContext saveContext,
			List<String> availableCharacters,
			ModuleResolutionService.PreparedLaunch preparedLaunch) {
		GameModule rootModule = SessionManager.getCurrentWorldSession().rootGameModule();
		GameDataSource dataSource = SessionManager.getCurrentWorldSession().getDataSource();
		String gameId = rootModule.getGameId().code();
		com.openggf.game.save.SaveSessionContext sanitized =
				GameplayTeamAvailability.sanitizeForLaunch(
						saveContext, gameId, availableCharacters);
		SelectedTeam selectedTeam = sanitized.selectedTeam();
		GameplayLaunchRequest launchRequest =
				new GameplayLaunchRequest(
						gameId, selectedTeam.mainCharacter(), selectedTeam.sidekicks());
		GameModule resolvedModule = consumePatchResolution(
				moduleResolutionService.resolveForLaunchResult(preparedLaunch, rootModule,
						launchRequest),
				true);
		if (resolvedModule == null) {
			return null;
		}
		com.openggf.game.ZoneKey destination = resolvedModule.getZoneRegistry()
				.zoneKey(sanitized.startZone());
		java.util.Optional<com.openggf.game.GameplayLaunchTeam> contributed =
				resolvedModule.getGameplayPolicyProvider().launchTeam(destination);
		com.openggf.game.GameplayLaunchTeam launchTeam = contributed.orElseGet(
				() -> toGameplayLaunchTeam(selectedTeam));
		com.openggf.game.save.SaveSessionContext launchContext;
		try {
			launchContext = GameplayTeamAvailability.requireForLaunch(sanitized, launchTeam,
					resolvedModule.getPlayableCharacterRegistry());
		} catch (GameplayTeamBootstrap.UnavailableRequiredCharacter unavailable) {
			String owner = destination instanceof com.openggf.game.ZoneKey.Mod mod
					? mod.ownerModId()
					: unavailable.key().ownerModId().orElse("engine");
			throw new com.openggf.mods.code.ModRegistrationException(owner,
					"MOD_LAUNCH_CHARACTER_UNAVAILABLE", unavailable.getMessage(), null, unavailable);
		}
		return SessionManager.openGameplaySession(
				rootModule, resolvedModule, dataSource, launchContext);
	}

	/**
	 * Launches a solo Time Attack run directly from the master title screen
	 * (the {@code TimeAttackMenu} sub-mode's GO action). Unlike
	 * {@link #launchGameplayFromDataSelect}, which fires from inside an
	 * already-loaded game's Data Select screen (ROM + world session already
	 * active), Time Attack starts from the master title with no ROM loaded
	 * yet, so this method also performs the ROM-load / module-detection
	 * bootstrap that {@link #initializeGame()} and
	 * {@code GameLoop.restartFromRecordingLaunchContext} perform for the
	 * same "launch a game fresh from master title" situation.
	 */
	private void launchTimeAttack(TimeAttackLaunchRequest request) {
		Objects.requireNonNull(request, "request");

		refreshLaunchSessionCachedConfig();
		applyResolvedDisplayDimensions();
		configService.setConfigValue(SonicConfiguration.DEFAULT_ROM, request.gameId());

		if (masterTitleScreen != null) {
			masterTitleScreen.cleanup();
			masterTitleScreen = null;
		}
		resetForGameplayFromMasterTitle();

		GameModule rootModule;
		GameDataSource dataSource;
		try {
			Rom rom = romManager.getRom();
			var detectedModule = romDetectionService.detectAndCreateModule(rom);
			if (detectedModule.isEmpty()) {
				showStartupRomError("ROM not recognized or corrupt for time attack: " + request.gameId());
				return;
			}
			rootModule = detectedModule.orElseThrow();
			dataSource = StockGameDataSources.pinned(rom, rootModule);
		} catch (IOException e) {
			showStartupRomError("Failed to load ROM for time attack launch: " + e.getMessage());
			return;
		}

		SelectedTeam team = new SelectedTeam(request.character(), List.of());
		com.openggf.game.save.SaveSessionContext saveContext = com.openggf.game.save.SaveSessionContext.noSave(
				request.gameId(), team, request.zone(), request.act());

		GameModule module = resolveTimeAttackModuleForLaunch(rootModule, request);
		ModSubsystem.disableCurrentSessionForDeterminism();
		if (!preparePresentationForLaunch(module)) {
			return;
		}
		GameplayModeContext gameplay = SessionManager.openGameplaySession(
				rootModule, module, dataSource, saveContext);
		initializeGameplayRuntime(gameplay, false);
		loadLevelFromDataSelect(request.zone(), request.act());
		gameLoop.setGameMode(GameMode.LEVEL);

		// loadLevelFromDataSelect -> levelManager.loadZoneAndAct(...) bypasses
		// GameLoop.doZoneAct (the mid-game zone-transition path that already
		// calls onLevelReady() for a time-attack retry), so fire it explicitly
		// here for this fresh launch.
		TimeAttackRuntime timeAttackRuntime = gameLoop.getTimeAttackRuntime();
		timeAttackRuntime.armForLaunch(request);
		if (timeAttackRuntime.isActive()) {
			timeAttackRuntime.onLevelReady();
		}
	}

	GameModule resolveTimeAttackModuleForLaunch(
			GameModule rootModule, TimeAttackLaunchRequest request) {
		return moduleResolutionService.resolveForLaunch(rootModule,
				new GameplayLaunchRequest(request.gameId(), request.character(), List.of()),
				ModuleResolutionService.LaunchPolicy.DETERMINISTIC);
	}

	private static List<String> stockCharacters(String gameId) {
		return switch (gameId) {
			case "s1" -> List.of("sonic");
			case "s2" -> List.of("sonic", "tails");
			case "s3k" -> List.of("sonic", "tails", "knuckles");
			default -> List.of("sonic");
		};
	}

	private static GameplayLaunchTeam toGameplayLaunchTeam(SelectedTeam team) {
		return new GameplayLaunchTeam(parseLaunchCharacterKey(team.mainCharacter()),
				team.sidekicks().stream().map(Engine::parseLaunchCharacterKey).toList());
	}

	private static CharacterKey parseLaunchCharacterKey(String persisted) {
		String canonical = persisted.equalsIgnoreCase("sonic")
				|| persisted.equalsIgnoreCase("tails")
				|| persisted.equalsIgnoreCase("knuckles")
				? persisted.toLowerCase(java.util.Locale.ROOT) : persisted;
		return CharacterKey.parsePersisted(canonical);
	}

	private void hostTimeAttackRoom(TimeAttackLaunchRequest request, String policy,
			String lockedCharacter, int windowSeconds) {
		leaveTimeAttackRoom();
		try {
			PlayerIdentity identity = PlayerIdentity.loadOrCreate(Path.of("identity"));
			String displayName = multiplayerDisplayName(identity);
			String fingerprint = fingerprintForGame(request.gameId());
			multiplayerRoundConfig = new ControlMessage.RoundConfig(request.gameId(),
					request.zone(), request.act(), windowSeconds, policy, lockedCharacter);
			multiplayerCharacter = request.character();
			hostingTimeAttackRoom = true;
			RoomHostConfig room = new RoomHostConfig(displayName + "'s room",
					request.gameId(), request.zone(), request.act(), policy, lockedCharacter,
					8, fingerprint, VoteTrackPools.forGame(request.gameId()));
			raceHostServer = RaceHostServer.start(
					configService.getInt(SonicConfiguration.TIME_ATTACK_NET_HOST_PORT),
					room, identity, TrackValidationProfileSource.none());
			RaceClient client = RaceClient.connect(
					URI.create("ws://127.0.0.1:" + raceHostServer.port() + "/race"),
					identity, displayName, fingerprint)
					.get(RaceClient.JOIN_TIMEOUT_MILLIS + 2000, TimeUnit.MILLISECONDS);
			finishRoomJoin(client);
		} catch (Exception e) {
			LOGGER.warning("Unable to host LAN time attack: " + rootMessage(e));
			leaveTimeAttackRoom();
		}
	}

	private void joinTimeAttackRoom(TimeAttackLaunchRequest request, String address) {
		leaveTimeAttackRoom();
		try {
			PlayerIdentity identity = PlayerIdentity.loadOrCreate(Path.of("identity"));
			String displayName = multiplayerDisplayName(identity);
			String fingerprint = fingerprintForGame(request.gameId());
			URI uri = joinUri(address,
					configService.getInt(SonicConfiguration.TIME_ATTACK_NET_HOST_PORT));
			RaceClient client = RaceClient.connect(uri, identity, displayName, fingerprint)
					.get(RaceClient.JOIN_TIMEOUT_MILLIS + 2000, TimeUnit.MILLISECONDS);
			ControlMessage.RoomDescriptor room = client.joinAccepted().room();
			multiplayerRoundConfig = new ControlMessage.RoundConfig(room.gameId(),
					room.zone(), room.act(), 300, room.characterPolicy(), room.lockedCharacter());
			multiplayerCharacter = room.lockedCharacter() != null
					? room.lockedCharacter() : request.character();
			hostingTimeAttackRoom = false;
			configService.setConfigValue(SonicConfiguration.TIME_ATTACK_NET_LAST_JOIN_ADDRESS,
					address == null ? "" : address.trim());
			configService.saveConfig();
			finishRoomJoin(client);
		} catch (Exception e) {
			LOGGER.warning("Unable to join LAN time attack: " + rootMessage(e));
			leaveTimeAttackRoom();
		}
	}

	private void finishRoomJoin(RaceConnection client) {
		raceConnection = client;
		if (multiplayerCharacter != null) {
			client.sendControl(new ControlMessage.SelectCharacter(multiplayerCharacter));
		}
		ClientRaceSession session = new ClientRaceSession(System::currentTimeMillis);
		session.applyJoin(client.joinAccepted());
		multiplayerRaceCoordinator = new MultiplayerRaceCoordinator(
				RaceTransport.from(client), session, System::currentTimeMillis,
				configService.getString(SonicConfiguration.TIME_ATTACK_NET_MASTER_URL),
				configService.getBoolean(
						SonicConfiguration.TIME_ATTACK_NET_MASTER_TRUST_INSECURE),
				new com.openggf.game.timeattack.GhostStore(Path.of("ghosts")));
		gameLoop.setMultiplayerRaceCoordinator(multiplayerRaceCoordinator);
		openRaceLobbyOnCurrentTitle();
	}

	private void browseTimeAttackRooms(TimeAttackLaunchRequest request, String policy,
			String lockedCharacter, int windowSeconds) {
		leaveTimeAttackRoom();
		String configuredUrl = configService.getString(
				SonicConfiguration.TIME_ATTACK_NET_MASTER_URL);
		if (configuredUrl == null || configuredUrl.isBlank()) {
			LOGGER.warning("Master browsing is disabled: timeAttack.net.masterUrl is blank");
			if (masterTitleScreen != null) {
				masterTitleScreen.tryOpenTimeAttackMenu();
			}
			return;
		}
		try {
			PlayerIdentity identity = PlayerIdentity.loadOrCreate(Path.of("identity"));
			String displayName = multiplayerDisplayName(identity);
			String fingerprint = fingerprintForGame(request.gameId());
			SSLContext ssl = configService.getBoolean(
					SonicConfiguration.TIME_ATTACK_NET_MASTER_TRUST_INSECURE)
					? insecureMasterSslContext() : null;
			masterClient = MasterClient.connect(URI.create(configuredUrl.trim()), identity,
					displayName, fingerprint, ssl)
					.get(MasterClient.MASTER_REPLY_TIMEOUT_MILLIS + 2000,
							TimeUnit.MILLISECONDS);
			multiplayerRoundConfig = new ControlMessage.RoundConfig(request.gameId(),
					request.zone(), request.act(), windowSeconds, policy, lockedCharacter);
			multiplayerCharacter = request.character();
			if (masterTitleScreen != null) {
				masterTitleScreen.openServerBrowser(new ServerBrowserScreen(masterClient,
						request.gameId(), masterTitleScreen.pixelFont(), browserActions(
								identity, displayName, fingerprint)));
			}
		} catch (Exception e) {
			LOGGER.warning("Unable to browse time attack rooms: " + rootMessage(e));
			closeMasterBrowser();
		}
	}

	private ServerBrowserScreen.Actions browserActions(PlayerIdentity identity,
			String displayName, String fingerprint) {
		return new ServerBrowserScreen.Actions() {
			@Override public void join(ControlMessage.RoomSummary room) {
				joinMasterRoom(room, identity, displayName, fingerprint);
			}

			@Override public void create(String routing) {
				createMasterRoom(routing, identity, displayName, fingerprint);
			}

			@Override public void back() {
				closeMasterBrowser();
			}
		};
	}

	private void joinMasterRoom(ControlMessage.RoomSummary room, PlayerIdentity identity,
			String displayName, String fingerprint) {
		try {
			RaceConnection connection = masterClient.joinRoom(room.roomId(), identity,
					displayName, fingerprint)
					.get(MasterClient.MASTER_REPLY_TIMEOUT_MILLIS + RaceClient.JOIN_TIMEOUT_MILLIS,
							TimeUnit.MILLISECONDS);
			masterRoomId = room.roomId();
			ControlMessage.RoomDescriptor joined = connection.joinAccepted().room();
			multiplayerRoundConfig = new ControlMessage.RoundConfig(joined.gameId(), joined.zone(),
					joined.act(), multiplayerRoundConfig.windowSeconds(), joined.characterPolicy(),
					joined.lockedCharacter());
			if (joined.lockedCharacter() != null) {
				multiplayerCharacter = joined.lockedCharacter();
			}
			hostingTimeAttackRoom = false;
			finishRoomJoin(connection);
		} catch (Exception e) {
			LOGGER.warning("Unable to join master room: " + rootMessage(e));
		}
	}

	private void createMasterRoom(String routing, PlayerIdentity identity,
			String displayName, String fingerprint) {
		try {
			boolean direct = "DIRECT".equals(routing);
			if (direct) {
				RoomHostConfig room = new RoomHostConfig(displayName + "'s room",
						multiplayerRoundConfig.gameId(), multiplayerRoundConfig.zone(),
						multiplayerRoundConfig.act(), multiplayerRoundConfig.characterPolicy(),
						multiplayerRoundConfig.lockedCharacter(), 8, fingerprint,
						VoteTrackPools.forGame(multiplayerRoundConfig.gameId()));
				raceHostServer = RaceHostServer.start(
						configService.getInt(SonicConfiguration.TIME_ATTACK_NET_HOST_PORT),
						room, identity, TrackValidationProfileSource.none());
			}
			ControlMessage.RoomDescriptor descriptor = new ControlMessage.RoomDescriptor(
					displayName + "'s room", multiplayerRoundConfig.gameId(),
					multiplayerRoundConfig.zone(), multiplayerRoundConfig.act(),
					multiplayerRoundConfig.characterPolicy(),
					multiplayerRoundConfig.lockedCharacter(), 8, false);
			ControlMessage.RoomCreated created = masterClient.createRoom(descriptor,
					routing, direct ? raceHostServer.port() : 0, fingerprint,
					VoteTrackPools.forGame(multiplayerRoundConfig.gameId()))
					.get(MasterClient.MASTER_REPLY_TIMEOUT_MILLIS + 1000, TimeUnit.MILLISECONDS);
			masterRoomId = created.roomId();
			masterAdvertisedZone = descriptor.zone();
			masterAdvertisedAct = descriptor.act();
			if (direct) {
				masterClient.bindHostLink(HostMasterLink.forServer(raceHostServer,
						new HostMasterLink.MessageSink() {
							@Override public void sendControl(ControlMessage message) {
								masterClient.sendControl(message);
							}
							@Override public void sendBinary(byte[] data) {
								masterClient.sendBinary(data);
							}
						}));
			}
			if (direct) {
				startMasterHeartbeat();
			}
			RaceConnection connection = masterClient.joinRoom(created.roomId(), identity,
					displayName, fingerprint)
					.get(MasterClient.MASTER_REPLY_TIMEOUT_MILLIS + RaceClient.JOIN_TIMEOUT_MILLIS,
							TimeUnit.MILLISECONDS);
			hostingTimeAttackRoom = true;
			finishRoomJoin(connection);
		} catch (Exception e) {
			LOGGER.warning("Unable to create master room: " + rootMessage(e));
			if (raceHostServer != null) {
				raceHostServer.close();
				raceHostServer = null;
			}
		}
	}

	private void startMasterHeartbeat() {
		if (masterHeartbeatThread != null) {
			masterHeartbeatThread.interrupt();
		}
		masterHeartbeatThread = Thread.ofVirtual().name("time-attack-master-heartbeat").start(() -> {
			try {
				while (!Thread.currentThread().isInterrupted() && masterClient != null
						&& masterClient.isOpen() && masterRoomId != null) {
					Thread.sleep(5000);
					if (raceHostServer != null) {
						ControlMessage.RoomDescriptor current = raceHostServer.room().descriptor();
						if (current.zone() != masterAdvertisedZone
								|| current.act() != masterAdvertisedAct) {
							masterClient.sendControl(new ControlMessage.RoomTrackUpdate(
									masterRoomId, current.zone(), current.act()));
							masterAdvertisedZone = current.zone();
							masterAdvertisedAct = current.act();
						}
					}
					int players = raceHostServer == null ? 1 : raceHostServer.room().playerCount();
					masterClient.heartbeat(masterRoomId, players);
				}
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private void closeMasterBrowser() {
		if (masterTitleScreen != null) {
			masterTitleScreen.closeServerBrowser();
		}
		if (masterClient != null) {
			masterClient.close();
			masterClient = null;
		}
		if (masterTitleScreen != null) {
			masterTitleScreen.tryOpenTimeAttackMenu();
		}
	}

	private static SSLContext insecureMasterSslContext() throws Exception {
		LOGGER.warning("TIME ATTACK MASTER TLS CERTIFICATE VERIFICATION IS DISABLED");
		TrustManager[] trustAll = {new X509TrustManager() {
			@Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
			@Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
			@Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
		}};
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, trustAll, new SecureRandom());
		return context;
	}

	private void openRaceLobbyOnCurrentTitle() {
		if (masterTitleScreen == null || multiplayerRaceCoordinator == null
				|| multiplayerRoundConfig == null || multiplayerCharacter == null) {
			return;
		}
		masterTitleScreen.openRaceLobby(multiplayerRaceCoordinator,
				hostingTimeAttackRoom, multiplayerRoundConfig, multiplayerCharacter,
				this::launchMultiplayerRound, this::leaveTimeAttackRoom);
	}

	private void launchMultiplayerRound(TimeAttackLaunchRequest request) {
		launchTimeAttack(request);
		if (multiplayerRaceCoordinator == null || !gameLoop.getTimeAttackRuntime().isActive()) {
			return;
		}
		multiplayerRaceCoordinator.attachRuntime(gameLoop.getTimeAttackRuntime());
		if (raceHostServer != null) {
			TrackValidationProfile profile = LiveLevelProfileFactory.fromLoadedLevelOrNull();
			if (profile != null) {
				raceHostServer.execute(() -> raceHostServer.room()
						.applyTrackValidationProfile(profile));
			}
		}
	}

	private String fingerprintForGame(String gameId) throws IOException {
		Rom rom = romManager.getSecondaryRom(gameId);
		return new DeterminismFingerprint(AppVersion.get(), rom.calculateChecksum()).asString();
	}

	private String multiplayerDisplayName(PlayerIdentity identity) {
		String configured = configService.getString(SonicConfiguration.TIME_ATTACK_NET_DISPLAY_NAME);
		return configured == null || configured.isBlank()
				? identity.fingerprint().substring(0, 8) : configured.trim();
	}

	private static URI joinUri(String address, int defaultPort) {
		String value = address == null ? "" : address.trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("join address is empty");
		}
		if (value.startsWith("ws://") || value.startsWith("wss://")) {
			URI supplied = URI.create(value);
			return supplied.getPath() == null || supplied.getPath().isBlank()
					? URI.create(value + "/race") : supplied;
		}
		String host = value;
		int port = defaultPort;
		int colon = value.lastIndexOf(':');
		if (colon > 0 && value.indexOf(':') == colon) {
			host = value.substring(0, colon);
			port = Integer.parseInt(value.substring(colon + 1));
		}
		return URI.create("ws://" + host + ":" + port + "/race");
	}

	private static String rootMessage(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName()
				: current.getMessage();
	}

	private void leaveTimeAttackRoom() {
		boolean reopenMenu = masterTitleScreen != null && masterTitleScreen.isRaceLobbyOpen();
		if (masterTitleScreen != null) {
			masterTitleScreen.closeRaceLobby();
		}
		if (multiplayerRaceCoordinator != null) {
			multiplayerRaceCoordinator.shutdown();
		}
		multiplayerRaceCoordinator = null;
		raceConnection = null;
		gameLoop.setMultiplayerRaceCoordinator(null);
		if (masterHeartbeatThread != null) {
			masterHeartbeatThread.interrupt();
			masterHeartbeatThread = null;
		}
		if (masterClient != null) {
			if (masterRoomId != null && masterClient.isOpen()) {
				masterClient.leaveRoom(masterRoomId);
			}
			masterClient.close();
			masterClient = null;
		}
		masterRoomId = null;
		masterAdvertisedZone = -1;
		masterAdvertisedAct = -1;
		if (raceHostServer != null) {
			raceHostServer.close();
			raceHostServer = null;
		}
		multiplayerRoundConfig = null;
		multiplayerCharacter = null;
		hostingTimeAttackRoom = false;
		if (reopenMenu && masterTitleScreen != null) {
			masterTitleScreen.tryOpenTimeAttackMenu();
		}
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
			SaveManager saveManager) {
		Map<String, Object> payload = loadDataSelectPayload(module, action, saveManager);
		return createDataSelectSaveContext(module, action, payload);
	}

	static com.openggf.game.save.SaveSessionContext createDataSelectSaveContext(
			GameModule module,
			com.openggf.game.dataselect.DataSelectAction action,
			Map<String, Object> payload) {
		String gameCode = module.getGameCode();
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
					String gameCode = module.getGameCode();
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
		GameStateManager gameState = gameplayMode.getGameStateManager();
		com.openggf.game.save.SaveSnapshotProvider saveProvider =
				gameplayMode.getWorldSession() == null
						? null
						: gameplayMode.getWorldSession().getGameModule().getSaveSnapshotProvider();
		boolean restored = saveProvider != null
				&& saveProvider.restoreProgress(gameState, lives, continues, payload);
		if (!restored) {
			gameState.restoreSaveProgress(
					lives,
					continues,
					readIntList(payload.get("chaosEmeralds")),
					readIntList(payload.get("superEmeralds")),
					payload.get("emeraldsConverted") instanceof Boolean converted ? converted : null);
		}
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
		boolean traceActive = TraceSessionLauncher.active() != null;
		if (!editorEntryAllowed(isEditorEnabled(), traceActive)) {
			if (traceActive) {
				LOGGER.warning("Editor entry is unavailable during an active trace session.");
			}
			return;
		}
		AbstractPlayableSprite player = resolveMainPlayableSprite();
		if (player == null) {
			return;
		}
		EditorPlaytestStash stash = capturePlaytestStash(player);
		enterEditorFromCurrentPlayer(stash, player.getCentreX(), player.getCentreY());
	}

	public static boolean editorEntryAllowed(boolean editorEnabled, boolean traceActive) {
		return editorEnabled && !traceActive;
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
		levelEditorController.setPersistenceStatus(editorSaveManager.lastApplyResult());
		GameModule editorModule = GameServices.module();
		levelEditorController.configureSpawnEditing(
				editorModule.createObjectRegistry(), editorModule.getObjectPlacementEncoding(),
				editorModule.getObjectArtProvider());
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
					displayAndSwap(this::display, () -> glfwSwapBuffers(window));
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
	private boolean display() {
		return runFrameWithModAbort(this::displayFrame,
				graphicsManager::discardQueuedCommands, this::returnToMasterTitleScreen);
	}

	private void displayFrame() {
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

		renderDispatcher.applyClearColor(getCurrentGameMode(), specialStageEntryShowsLevel(),
				clearActions);
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
		// VHS picture-search effect while live rewind is active. Runs BEFORE the
		// user's PRESENTATION-phase display shader so a CRT preset displays the
		// damaged "signal" (tape artifacts precede the TV in the real chain).
		if (!userRecordingSceneSuppressed && rewindVhsEffectPass != null && gameLoop != null) {
			float rewindEffectIntensity = gameLoop.liveRewindEffectIntensity();
			if (rewindEffectIntensity > 0.0f) {
				rewindVhsEffectPass.apply(
						rewindEffectIntensity,
						gameLoop.liveRewindEffectSpeed(),
						-1.0f,
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
			gameLoop.renderLiveRewindHud(traceHudTextRenderer, (int) projectionWidth);
		}
		if (gameLoop != null) {
			traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
			if (postFadeRecorder != null) {
				postFadeRecorder.recordPostFadeDiagnostic("UserRecordingHud");
			}
			gameLoop.renderUserRecordingHud(traceHudTextRenderer);
		}
		if (gameLoop != null) {
			traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
			if (postFadeRecorder != null) {
				postFadeRecorder.recordPostFadeDiagnostic("TimeAttackHud");
			}
			gameLoop.renderTimeAttackHud(traceHudTextRenderer);
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

	static boolean runFrameWithModAbort(Runnable frame, Runnable returnToTitle) {
		return runFrameWithModAbort(frame, () -> {}, returnToTitle);
	}

	static boolean runFrameWithModAbort(Runnable frame, Runnable discardFailedFrame,
			Runnable returnToTitle) {
		Objects.requireNonNull(frame, "frame");
		Objects.requireNonNull(discardFailedFrame, "discardFailedFrame");
		Objects.requireNonNull(returnToTitle, "returnToTitle");
		try {
			frame.run();
			return true;
		} catch (ModFaultBoundary.CallbackAborted aborted) {
			Throwable fatalCleanup = null;
			try {
				discardFailedFrame.run();
			} catch (Throwable failure) {
				if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
					fatalCleanup = failure;
				} else {
					aborted.addSuppressed(failure);
				}
			}
			try {
				returnToTitle.run();
			} catch (Throwable failure) {
				if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
					if (fatalCleanup == null) {
						fatalCleanup = failure;
					} else {
						fatalCleanup.addSuppressed(failure);
					}
				} else {
					aborted.addSuppressed(failure);
				}
			}
			if (fatalCleanup != null) {
				fatalCleanup.addSuppressed(aborted);
				if (fatalCleanup instanceof VirtualMachineError vmFailure) {
					throw vmFailure;
				}
				throw (ThreadDeath) fatalCleanup;
			}
			if (aborted.getSuppressed().length != 0) {
				throw aborted;
			}
			return false;
		}
	}

	static boolean displayAndSwap(java.util.function.BooleanSupplier display, Runnable swap) {
		Objects.requireNonNull(display, "display");
		Objects.requireNonNull(swap, "swap");
		if (!display.getAsBoolean()) {
			return false;
		}
		swap.run();
		return true;
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
					ENDING_CUTSCENE, BONUS_STAGE, NATIVE_MOD_NOTICE -> true;
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
	 * {@link com.openggf.control.InputHandler#isKeyDown(int)} reports as not
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
        return detector.update(chord, input.isPhysicalKeyDown(chord.keyCode()),
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
		@Override public void nativeModNotice() { drawNativeModNotice(); }
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
		traceHudTextRenderer.drawShadowedText(
				text, centeredDiagnosticX((int) projectionWidth, 4), y, DebugColor.YELLOW, scale);
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
		traceHudTextRenderer.drawShadowedText(
				text, centeredDiagnosticX((int) projectionWidth, 4), y, DebugColor.YELLOW, scale);
	}

	private void renderDiagnosticOverlays(boolean userRecordingSceneSuppressed,
			boolean playbackHud,
			boolean performanceOverlayVisible,
			RenderOrderRecorder postFadeRecorder) {
		profiler.beginSection("debug");
		boolean renderedDebugRenderer = false;
		if (!userRecordingSceneSuppressed && getCurrentGameMode() == GameMode.SPECIAL_STAGE) {
			SpecialStageProvider ssProvider = gameLoop.getActiveSpecialStageProvider();
			SpecialStageViewport specialStageViewport = applySpecialStageViewport(ssProvider);
			if (ssProvider.isAlignmentTestMode()) {
				if (postFadeRecorder != null) {
					postFadeRecorder.recordPostFadeDiagnostic("SpecialStageDiagnosticOverlay");
				}
				ssProvider.renderAlignmentOverlay(
						specialStageViewport.logicalWidth(), specialStageViewport.logicalHeight());
			} else if (ssProvider.isLagCompensationDisplayEnabled()) {
				if (postFadeRecorder != null) {
					postFadeRecorder.recordPostFadeDiagnostic("SpecialStageDiagnosticOverlay");
				}
				ssProvider.renderLagCompensationOverlay(
						specialStageViewport.logicalWidth(), specialStageViewport.logicalHeight());
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
		int x = centeredDiagnosticX((int) projectionWidth, 4);
		int y = 4;
		traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
		traceHudTextRenderer.drawShadowedText(controller.message(), x, y, DebugColor.WHITE, scale);
		renderEscapeProgressBar(y + traceHudTextRenderer.lineHeight(scale) + 2, controller.progress());
	}

	private void renderEscapeProgressBar(int y, double progress) {
		for (GLCommand command : escapeProgressRectCommands((int) projectionWidth, y, progress)) {
			command.execute(0, 0, 0, 0);
		}
	}

	static int centeredDiagnosticOrigin(int logicalWidth) {
		return Math.max(0, (logicalWidth - 320) / 2);
	}

	static int centeredDiagnosticX(int logicalWidth, int margin) {
		return centeredDiagnosticOrigin(logicalWidth) + margin;
	}

	static List<GLCommand> escapeProgressRectCommands(int logicalWidth, int y, double progress) {
		final int x = centeredDiagnosticX(logicalWidth, 4);
		final int width = 144;
		final int height = 6;
		int fillWidth = (int) Math.round(Math.max(0.0, Math.min(1.0, progress)) * (width - 2));
		List<GLCommand> commands = new ArrayList<>(6);

		commands.add(escapeProgressRectCommand(x, y, x + width, y + height,
				0.0f, 0.0f, 0.0f, 0.65f, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA));
		commands.add(escapeProgressRectCommand(x, y, x + width, y + 1,
				1.0f, 1.0f, 1.0f, 1.0f, GLCommand.BlendType.SOLID));
		commands.add(escapeProgressRectCommand(x, y + height - 1, x + width, y + height,
				1.0f, 1.0f, 1.0f, 1.0f, GLCommand.BlendType.SOLID));
		commands.add(escapeProgressRectCommand(x, y, x + 1, y + height,
				1.0f, 1.0f, 1.0f, 1.0f, GLCommand.BlendType.SOLID));
		commands.add(escapeProgressRectCommand(x + width - 1, y, x + width, y + height,
				1.0f, 1.0f, 1.0f, 1.0f, GLCommand.BlendType.SOLID));
		if (fillWidth > 0) {
			commands.add(escapeProgressRectCommand(x + 1, y + 1, x + 1 + fillWidth, y + height - 1,
					1.0f, 1.0f, 0.0f, 1.0f, GLCommand.BlendType.SOLID));
		}
		return commands;
	}

	private static GLCommand escapeProgressRectCommand(
			int x1, int y1, int x2, int y2,
			float red, float green, float blue, float alpha,
			GLCommand.BlendType blendType) {
		return new GLCommand(
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
				y2);
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
		renderDispatcher.draw(getCurrentGameMode(), specialStageEntryShowsLevel(), debugViewEnabled, debugState, drawActions);
	}

	/**
	 * True while a special stage is still inside the ROM's entry fade-to-white:
	 * the level's last frame stays on screen under the fade until the stage's
	 * own reveal boundary ({@link SpecialStageProvider#isEntryFadeToWhiteActive}).
	 */
	private boolean specialStageEntryShowsLevel() {
		if (getCurrentGameMode() != GameMode.SPECIAL_STAGE || levelManager == null) {
			// Outside SPECIAL_STAGE the accessor resolves the module's provider
			// through the session, which editor-only draws do not have.
			return false;
		}
		SpecialStageProvider ssProvider = gameLoop.getActiveSpecialStageProvider();
		return ssProvider != null && ssProvider.isEntryFadeToWhiteActive();
	}

	static void applyViewportWidth(LevelSelectProvider provider, int width) {
		if (provider != null) {
			provider.setViewportWidth(width);
		}
	}

	static void applyViewportWidth(DataSelectProvider provider, int width) {
		if (provider != null) {
			provider.setViewportWidth(width);
		}
	}

	static void applyViewportWidth(ResultsScreen provider, int width) {
		if (provider != null) {
			provider.setViewportWidth(width);
		}
	}

	static void applyViewportWidth(EndingProvider provider, int width) {
		if (provider != null) {
			provider.setViewportWidth(width);
		}
	}

	private void drawLegalDisclaimer() {
		resetCameraForScreenSpaceIfPresent();
		if (legalDisclaimerScreen != null) {
			legalDisclaimerScreen.setViewportWidth((int) projectionWidth);
			legalDisclaimerScreen.setProjectionMatrix(getProjectionMatrixBuffer());
			legalDisclaimerScreen.draw();
		}
	}

	private void drawNativeModNotice() {
		resetCameraForScreenSpaceIfPresent();
		if (nativeModNoticeScreen != null) {
			nativeModNoticeScreen.setProjectionMatrix(getProjectionMatrixBuffer());
			nativeModNoticeScreen.draw();
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
		// Special stages draw in screen coordinates. The level camera survives
		// the entry fade-to-white (the level is still on screen then), so the
		// stage re-origins the camera itself once it owns the frame.
		resetCameraForScreenSpace();
		SpecialStageProvider ssProvider = gameLoop.getActiveSpecialStageProvider();
		applySpecialStageViewport(ssProvider);
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
		applyViewportWidth(resultsScreen, (int) projectionWidth);

		graphicsManager.beginPatternBatch();

		resultsCommands.clear();
		resultsScreen.appendRenderCommands(resultsCommands);

		graphicsManager.flushPatternBatch();

		if (!resultsCommands.isEmpty()) {
			graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, resultsCommands));
		}

		graphicsManager.flushScreenSpace();
	}

	private SpecialStageViewport applySpecialStageViewport(SpecialStageProvider provider) {
		SpecialStageViewport viewport = SpecialStageViewport.fromLogicalWidth((int) projectionWidth);
		provider.setSpecialStageViewport(viewport);
		return viewport;
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
			applyViewportWidth(levelSelect, (int) projectionWidth);
			levelSelect.draw();
		}
	}

	private void drawDataSelect() {
		resetCameraForScreenSpace();
		DataSelectProvider dataSelect = gameLoop.getDataSelectProvider();
		if (dataSelect != null) {
			applyViewportWidth(dataSelect, (int) projectionWidth);
			dataSelect.draw();
		}
	}

	private void drawEndingCutscene() {
		resetCameraForScreenSpace();
		EndingProvider provider = gameLoop.getEndingProvider();
		if (provider == null) {
			return;
		}
		applyViewportWidth(provider, (int) projectionWidth);
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
			applyViewportWidth(provider, (int) projectionWidth);
			provider.draw();
		}
	}

	private void drawCreditsDemo() {
		EndingProvider provider = gameLoop.getEndingProvider();
		applyViewportWidth(provider, (int) projectionWidth);
		boolean includeSprites = provider == null || !provider.shouldRenderDemoSpritesOverFade();
		levelManager.drawWithSpritePriority(spriteManager, includeSprites);
	}

	private void drawTryAgainEnd() {
		resetCameraForScreenSpace();
		EndingProvider provider = gameLoop.getEndingProvider();
		if (provider != null) {
			applyViewportWidth(provider, (int) projectionWidth);
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
		cleanupStep("multiplayer time attack", this::leaveTimeAttackRoom);
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
		cleanupStep("compiled mod resolver", () -> ModSubsystem.current().installRewindClassResolver(
				com.openggf.level.objects.RewindClassResolver.ENGINE_ONLY));
		cleanupStep("compiled mod runtime", () -> closeModRuntime(modRuntime));
		cleanupStep("mod subsystem", ModSubsystem::clearProcess);
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

	static ModRuntime replaceModRuntime(ModRuntime current, ModRuntime replacement) {
		Objects.requireNonNull(replacement, "replacement");
		closeModRuntime(Objects.requireNonNull(current, "current"));
		return replacement;
	}

	static void closeModRuntime(ModRuntime runtime) {
		try {
			runtime.close();
		} catch (IOException error) {
			throw new IllegalStateException("Failed to close compiled-mod runtime", error);
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
	org.joml.Matrix4f getProjectionMatrix() {
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
