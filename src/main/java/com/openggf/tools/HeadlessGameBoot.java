package com.openggf.tools;

import com.openggf.Engine;
import com.openggf.GameLoop;
import com.openggf.audio.HeadlessSmpsAudioBackend;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.control.InputHandler;
import com.openggf.data.Rom;
import com.openggf.game.GameMode;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.GameplayTeamBootstrap;
import com.openggf.game.session.SessionManager;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.graphics.GraphicsManager;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;

import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_MODELVIEW;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_PROJECTION;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glLoadIdentity;
import static org.lwjgl.opengl.GL11.glLoadMatrixf;
import static org.lwjgl.opengl.GL11.glMatrixMode;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Boots a fully wired gameplay session against a hidden offscreen GL context,
 * without going through {@link Engine}, the master-title flow, or the input
 * loop. This is the shared headless entry used by the trace-capture driver
 * tool so it can drive a real {@link GameLoop} frame-by-frame and read the
 * rendered framebuffer back via {@code GlReadPixelsGrabber}.
 *
 * <p>The boot sequence mirrors the live engine path exactly:
 * <ol>
 *   <li>the offscreen GL setup of
 *       {@code VisualReferenceGenerator.initialize}, and</li>
 *   <li>the gameplay-session wiring of
 *       {@code Engine.initializeGameplayRuntime} (session open + manager
 *       attach + module registration + team bootstrap + camera focus).</li>
 * </ol>
 *
 * <p>Callers own the returned {@link GameLoop}'s frame stepping and must
 * {@link #close()} this boot to release the GL context and GLFW.
 */
public final class HeadlessGameBoot implements AutoCloseable {

    private final int width;
    private final int height;

    private long window = NULL;

    // JOML projection state, mirroring VisualReferenceGenerator.
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final float[] matrixBuffer = new float[16];

    private Rom rom;

    /**
     * Creates the hidden GLFW window and initialises the GL context /
     * graphics manager at the given framebuffer dimensions.
     */
    public HeadlessGameBoot(int width, int height) {
        this.width = width;
        this.height = height;
        initGl();
    }

    /**
     * Mirrors {@code VisualReferenceGenerator.initialize} lines 84-167:
     * hidden GLFW window, GL capabilities, graphics-manager shader init,
     * viewport, ortho projection, and alpha blending.
     */
    private void initGl() {
        // Process-wide engine services must be configured before any
        // gameplay session is opened.
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);

        window = glfwCreateWindow(width, height, "Headless Game Boot", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        GraphicsManager graphicsManager = EngineServices.current().graphics();
        try {
            graphicsManager.init(Engine.RESOURCES_SHADERS_PIXEL_SHADER_GLSL);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialise GraphicsManager shader", e);
        }

        glViewport(0, 0, width, height);

        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        projectionMatrix.identity().ortho2D(0, width, 0, height);
        projectionMatrix.get(matrixBuffer);
        glLoadMatrixf(matrixBuffer);

        // There is no live Engine instance in this CLI context, so the
        // projection matrix must be supplied to the GraphicsManager directly
        // for shader-based rendering.
        graphicsManager.setProjectionMatrixBuffer(matrixBuffer.clone());

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        graphicsManager.setViewport(0, 0, width, height);
    }

    /**
     * Opens the ROM, detects the game module, opens a gameplay session,
     * attaches all gameplay managers, builds and wires a {@link GameLoop},
     * loads the requested zone/act, and registers the active team. Returns
     * the fully bound loop ready to be stepped.
     */
    public GameLoop boot(Path romPath, int zone, int act) throws IOException {
        return boot(romPath, zone, act, HardwareReadinessAdmissionPolicy.LIVE);
    }

    public GameLoop boot(Path romPath, int zone, int act,
            HardwareReadinessAdmissionPolicy admissionPolicy) throws IOException {
        // Process-wide services were configured in initGl(); resolve them via
        // the EngineServices locator rather than raw singletons.
        EngineContext services = EngineServices.current();

        // --- ROM + module ------------------------------------------------
        rom = new Rom();
        if (!rom.open(romPath.toString())) {
            throw new IOException("Failed to open ROM file: " + romPath);
        }
        services.roms().setRom(rom);

        Optional<GameModule> detected =
                services.romDetection().detectAndCreateModule(rom);
        GameModule module = detected.orElseThrow(() ->
                new IOException("No game module detected for ROM: " + romPath));

        // --- gameplay session + managers --------------------------------
        GameplayModeContext mode =
                SessionManager.openGameplaySession(module, admissionPolicy);
        GameplaySessionFactory.attachManagers(mode, services);
        if (!mode.isGameplayRuntimeReady()) {
            throw new IllegalStateException(
                    "Gameplay runtime not ready after attachManagers");
        }

        // --- game loop wiring -------------------------------------------
        GameLoop loop = new GameLoop(services);
        loop.setGameplayMode(mode);
        loop.setInputHandler(new InputHandler());
        loop.setGameMode(GameMode.LEVEL);

        GameModuleRegistry.setCurrent(module);

        // --- audio backend (real SMPS synthesis) ------------------------
        // Must precede any music (loadZoneAndAct below) so the presentation
        // producer this backend installs is the one that admits the level's
        // music/SFX voices. The default NullAudioBackend synthesizes nothing,
        // which is what made captured audio silent.
        // Mirrors Engine.initializeGlobalGameplayServices (Engine.java:676);
        // setBackend() falls back to NullAudioBackend if OpenAL init fails.
        SonicConfigurationService audioConfig = services.configuration();
        if (audioConfig.getBoolean(SonicConfiguration.AUDIO_ENABLED)) {
            // Headless backend: it builds the normal presentation producer
            // over a NoDeviceAudioSink (AudioBackend.createPresentationSink),
            // so the same SMPS/WAV/raw-PCM voice registry renders offline
            // without ever opening an audio device. AudioManager's offline
            // capture lease is a non-consuming view of that producer, not a
            // replacement for it.
            services.audio().setBackend(
                    new HeadlessSmpsAudioBackend(audioConfig, services.profiler()));
        }

        // --- per-replay subsystem reset ---------------------------------
        // Clear the per-zone subsystem state (sprites, collision, camera, fade,
        // game state, timers, water, parallax, level events, RNG seed) the same
        // way the headless trace tests do via TestEnvironment.resetPerTest() and
        // the live launcher does via resetLevelSubsystemsForReplay(). Without
        // this, residual state left by the bootstrap EngineContext (title-screen
        // defaults, default level, residual level-event/intro state) leaks into
        // the AIZ intro and slips object/event timing by ~1 vbla frame mid-intro,
        // which cascades into a player-path desync (e.g. AIZ landing at trace
        // ~2170 lands 3px off and snowballs).
        TraceReplaySessionBootstrap.resetLevelSubsystemsForReplay();

        // --- team + level -----------------------------------------------
        // Register the active team BEFORE loadZoneAndAct so the level load's
        // spawnPlayerAtStartPosition finds the main sprite (otherwise the
        // camera's focusedSprite ends up null / the player is never spawned at
        // the start position). This mirrors the trace-replay test fixture and
        // the live TraceReplayDriver bootstrap order.
        SonicConfigurationService configService = GameServices.configuration();
        GameplayTeamBootstrap.BootstrappedTeam team =
                GameplayTeamBootstrap.registerActiveTeam(
                        module, GameServices.sprites(), configService);

        GameServices.level().loadZoneAndAct(zone, act);

        GameServices.camera().setFocusedSprite(team.mainSprite());
        GameServices.camera().updatePosition(true);

        return loop;
    }

    /**
     * Closes the current gameplay session and ROM, then boots a fresh one on the
     * existing GL context.
     *
     * <p>Repeated measured passes need a genuinely fresh session — a second
     * bootstrap over a session whose objects have already spawned and despawned
     * is not the same workload as the first — but they must not pay for GL/GLFW
     * re-initialisation, and they must not accumulate ROM images: a leaked ~4MB
     * image per pass would show up directly in the heap and GC figures the run
     * exists to report.
     */
    public GameLoop reboot(Path romPath, int zone, int act) throws IOException {
        return reboot(
                romPath, zone, act, HardwareReadinessAdmissionPolicy.LIVE);
    }

    public GameLoop reboot(
            Path romPath,
            int zone,
            int act,
            HardwareReadinessAdmissionPolicy admissionPolicy)
            throws IOException {
        try {
            SessionManager.closeGameplaySession();
        } catch (Exception ignored) {
            // best-effort teardown; boot() below re-opens a fresh session
        }
        if (rom != null) {
            try {
                rom.close();
            } catch (Exception ignored) {
                // best-effort teardown
            }
            rom = null;
        }
        return boot(romPath, zone, act, admissionPolicy);
    }

    /**
     * The single headless outer-frame audio boundary. Headless capture drivers
     * (the trace-capture tool and {@link TraceCaptureSession}) call this exactly
     * once for each outer framebuffer frame they treat as presented, then drain
     * that packet exactly once after the framebuffer grab.
     *
     * <p>{@code GameLoop.step()} / {@code stepInternal()} deliberately do not
     * present, so fast-forward simulation steps may enqueue audio commands
     * without multiplying the audio cadence. The mode is always
     * {@link PresentationMode#FORWARD}: a headless capture run has no modal
     * picker, pause, frame-step, or held rewind.
     */
    public static void presentHeadlessOuterAudioFrame() {
        GameServices.audio().presentFrame(PresentationMode.FORWARD);
    }

    @Override
    public void close() {
        if (rom != null) {
            try {
                rom.close();
            } catch (Exception ignored) {
                // best-effort teardown
            }
            rom = null;
        }
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
        glfwTerminate();
    }
}
