package uk.co.jamesj999.sonic;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryStack;
import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.debug.DebugOption;
import uk.co.jamesj999.sonic.debug.DebugRenderer;
import uk.co.jamesj999.sonic.debug.DebugState;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.graphics.SpriteRenderManager;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteCollisionManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.Sonic;
import uk.co.jamesj999.sonic.timer.TimerManager;

import java.io.IOException;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBVertexArrayObject.glBindVertexArray;
import static org.lwjgl.opengl.ARBVertexArrayObject.glGenVertexArrays;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public class Engine {

	public static final String RESOURCES_SHADERS_TEXT_SHADER_GLSL = "shaders/text-common.glsl";
	public static final String RESOURCES_SHADERS_PIXEL_SHADER_GLSL = "shaders/shader_the_hedgehog.glsl";
	private final SonicConfigurationService configService = SonicConfigurationService.getInstance();
	private final SpriteManager spriteManager = SpriteManager.getInstance();
	private final SpriteRenderManager spriteRenderManager = SpriteRenderManager.getInstance();
	private final SpriteCollisionManager spriteCollisionManager = SpriteCollisionManager.getInstance();
	private final GraphicsManager graphicsManager = GraphicsManager.getInstance();

	private final Camera camera = Camera.getInstance();
	private final DebugRenderer debugRenderer = DebugRenderer.getInstance();
	private final TimerManager timerManager = TimerManager.getInstance();

	private InputHandler inputHandler;
	public static DebugState debugState = DebugState.NONE;
	public static DebugOption debugOption = DebugOption.A;

	private double realWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
	private double realHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

	private boolean debugViewEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
	private boolean debugModeEnabled = configService.getBoolean(SonicConfiguration.DEBUG_MODE);

	private final LevelManager levelManager = LevelManager.getInstance();

	private long window;

	/**
	 *
	 */
	public void run() {

		initWindow();
		initGL();

		loop();

		cleanup();
	}

	/**
	 *
	 */
	private void initWindow() {

		// Setup an error callback. The default implementation
		// will print the error message in System.err.
		GLFWErrorCallback.createPrint(System.err).set();

		if (!glfwInit()) {
			throw new IllegalStateException("Unable to initialize GLFW");
		}

		glfwDefaultWindowHints(); // optional, the current window hints are already the default
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // Window will stay hidden until ready
		glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
		glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_ANY_PROFILE);

		int width = configService.getInt(SonicConfiguration.SCREEN_WIDTH);
		int height = configService.getInt(SonicConfiguration.SCREEN_HEIGHT);

		window = glfwCreateWindow(width, height, "Sonic Engine", NULL, NULL);

		inputHandler = new InputHandler(window);
		if (window == NULL) {
			throw new RuntimeException("Failed to create the GLFW window");
		}


		// Center the window on the screen
		try (MemoryStack stack = stackPush()) {
			IntBuffer pWidth = stack.mallocInt(1); // int*
			IntBuffer pHeight = stack.mallocInt(1); // int*

			glfwGetWindowSize(window, pWidth, pHeight);

			GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
			if (vidMode != null) {
				glfwSetWindowPos(window, (vidMode.width() - pWidth.get(0)) / 2,
						(vidMode.height() - pHeight.get(0)) / 2);
			}

		}

		glfwMakeContextCurrent(window); // Make the OpenGL context current
		glfwSwapInterval(1); // Enable v-sync

		glfwShowWindow(window); // Make the window visible

	}

	/**
	 *
	 */
	private void initGL() {

		GL.createCapabilities();

		//int vao = glGenVertexArrays();
		//glBindVertexArray(vao);

		GL11.glShadeModel(GL_SMOOTH);

		GLFW.glfwSetFramebufferSizeCallback(window, this::reshape);

		try {
			graphicsManager.init(RESOURCES_SHADERS_TEXT_SHADER_GLSL,RESOURCES_SHADERS_PIXEL_SHADER_GLSL);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		Sonic sonic = new Sonic(
				configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE),
				(short) 100, (short) 624, debugModeEnabled);
		spriteManager.addSprite(sonic);
		camera.setFocusedSprite(sonic);
		camera.updatePosition(true);

		try {
			levelManager.loadLevel(0);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		int width = configService.getInt(SonicConfiguration.SCREEN_WIDTH);
		int height = configService.getInt(SonicConfiguration.SCREEN_HEIGHT);

		// Set the resize callback
		reshape(window, width, height);
	}

	/**
	 * Call-back handler for window re-size event. Also called when the drawable
	 * is first set to visible.
	 */
	public void reshape(long window , int width, int height) {
		// Adjust the OpenGL viewport to the new window size
		GL11.glViewport(0, 0, width, height);

		// Setup perspective projection, with aspect ratio matches viewport
		GL11.glMatrixMode(GL_PROJECTION); // choose projection matrix
		GL11.glLoadIdentity(); // reset projection matrix
		GL11.glOrtho(0, (int) realWidth, 0, (int) realHeight,1,-1);

		// Enable the model-view transform
		GL11.glMatrixMode(GL_MODELVIEW);
		GL11.glLoadIdentity(); // reset
	}

	/**
	 *
	 */
	public void loop() {

		glClearColor(0.0f, 0.0f, 0.0f, 0.0f); // Set background (clear) color

		while (!glfwWindowShouldClose(window)) {
			// This line is critical for LWJGL's interoperation with GLFW's
			// OpenGL context, or any context that is managed externally.
			// LWJGL detects the context that is current in the current thread,
			// creates the GLCapabilities instance and makes the OpenGL
			// bindings available for use.

			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			glfwPollEvents(); // Poll for input events

			update();
			draw();

			glfwSwapBuffers(window); // Swap the color buffers
			graphicsManager.flush();
		}
	}

	private void update() {
		timerManager.update();
		spriteCollisionManager.update(inputHandler);
		camera.updatePosition();
	}

	private void draw() {
		if (!debugViewEnabled) {
			//levelManager.draw();
			spriteRenderManager.draw();
		} else {
			switch (debugState) {
				case PATTERNS_VIEW -> levelManager.drawAllPatterns();
				case CHUNKS_VIEW -> levelManager.drawAllChunks();
				case BLOCKS_VIEW -> levelManager.draw();
				case null, default -> {
					levelManager.draw();
					spriteRenderManager.draw();
				}
			}
			graphicsManager.renderDebugInfo();
		}
	}

	private void cleanup() {
		graphicsManager.cleanup();

		// Free the window callbacks and destroy the window
		glfwFreeCallbacks(window);
		glfwDestroyWindow(window);

		// Terminate GLFW and free the error callback
		glfwTerminate();
		GLFWErrorCallback callback = glfwSetErrorCallback(null);
		if (callback != null) {
			callback.free();
		}
	}

	public static void main(String[] args) {
		new Engine().run();
	}

	public static void nextDebugState() {
		debugState = debugState.next();
		debugOption = DebugOption.A;
	}

	public static void nextDebugOption() {
		debugOption = debugOption.next();
	}
}
