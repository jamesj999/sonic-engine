package uk.co.jamesj999.sonic;

import com.jogamp.opengl.util.FPSAnimator;
import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.JOALAudioBackend;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.configuration.OptionsMenu;
import uk.co.jamesj999.sonic.debug.DebugOption;
import uk.co.jamesj999.sonic.debug.DebugRenderer;
import uk.co.jamesj999.sonic.debug.DebugSpecialStageSprites;
import uk.co.jamesj999.sonic.debug.DebugState;
import uk.co.jamesj999.sonic.graphics.FadeManager;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.graphics.SpriteRenderManager;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.Sonic;
import uk.co.jamesj999.sonic.sprites.playable.Tails;
import uk.co.jamesj999.sonic.game.GameMode;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageManager;
import uk.co.jamesj999.sonic.game.sonic2.titlecard.TitleCardManager;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.glu.GLU;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

import static com.jogamp.opengl.GL.GL_COLOR_BUFFER_BIT;
import static com.jogamp.opengl.GL.GL_DEPTH_BUFFER_BIT;
import static com.jogamp.opengl.fixedfunc.GLLightingFunc.GL_SMOOTH;
import static com.jogamp.opengl.fixedfunc.GLMatrixFunc.GL_MODELVIEW;
import static com.jogamp.opengl.fixedfunc.GLMatrixFunc.GL_PROJECTION;

/**
 * Controls the game.
 *
 * @author james
 *
 */
@SuppressWarnings("serial")
public class Engine extends GLCanvas implements GLEventListener {
	public static final String RESOURCES_SHADERS_PIXEL_SHADER_GLSL = "shaders/shader_the_hedgehog.glsl";
	private final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();
	private final SpriteManager spriteManager = SpriteManager.getInstance();
	private final SpriteRenderManager spriteRenderManager = SpriteRenderManager.getInstance();
	private final GraphicsManager graphicsManager = GraphicsManager.getInstance();

	private final Camera camera = Camera.getInstance();
	private final DebugRenderer debugRenderer = DebugRenderer.getInstance();

	private final GameLoop gameLoop = new GameLoop();

	public static DebugState debugState = DebugState.NONE;
	public static DebugOption debugOption = DebugOption.A;

	private double realWidth = configService
			.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
	private double realHeight = configService
			.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

	// Current projection width - can be changed for H32/H40 mode switching
	// H40 mode (normal levels): 320 pixels wide
	// H32 mode (special stages): 256 pixels wide
	private double projectionWidth = realWidth;

	private boolean debugViewEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);

	// TODO move this into a manager
	private final LevelManager levelManager = LevelManager.getInstance();
	private final Sonic2SpecialStageManager specialStageManager = Sonic2SpecialStageManager.getInstance();

	private GLU glu;

	// TODO Add Log4J Support, or some other logging that allows proper
	// debugging etc. Any ideas?

	public Engine() {
		this.addGLEventListener(this);

		// Set up game mode change listener to update projection width
		gameLoop.setGameModeChangeListener((oldMode, newMode) -> {
			// Keep projection at 320 for both modes
			// Special stage renderer will center 256px content within 320px viewport
			projectionWidth = realWidth;
		});
	}

	public void setInputHandler(InputHandler inputHandler) {
		gameLoop.setInputHandler(inputHandler);
	}

	public void init(GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2(); // get the OpenGL graphics context
		glu = new GLU(); // get GL Utilities
		gl.glShadeModel(GL_SMOOTH); // blends colors nicely, and smooths out
									// lighting
		try {
			graphicsManager.init(gl, RESOURCES_SHADERS_PIXEL_SHADER_GLSL);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		graphicsManager.setGraphics(gl);

		if (configService.getBoolean(SonicConfiguration.AUDIO_ENABLED)) {
			AudioManager.getInstance().setBackend(new JOALAudioBackend());
		}

		String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
		AbstractPlayableSprite mainSprite;
		if ("tails".equalsIgnoreCase(mainCode)) {
			mainSprite = new Tails(mainCode, (short) 100, (short) 624);
		} else {
			mainSprite = new Sonic(mainCode, (short) 100, (short) 624);
		}
		spriteManager.addSprite(mainSprite);

		// Causes camera to instantiate itself... TODO Probably remove this
		// later since it'll be used in the first update loop anyway
		camera.setFocusedSprite(mainSprite);
		camera.updatePosition(true);

		// levelManager.setLevel(new TestOldLevel());

		try {
			levelManager.loadZoneAndAct(0, 0);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	// Viewport parameters for aspect-ratio-correct rendering
	private int viewportX = 0;
	private int viewportY = 0;
	private int viewportWidth = 0;
	private int viewportHeight = 0;

	/**
	 * Call-back handler for window re-size event. Also called when the drawable
	 * is first set to visible.
	 */
	public void reshape(GLAutoDrawable drawable, int x, int y, int width,
			int height) {
		GL2 gl = drawable.getGL().getGL2(); // get the OpenGL 2 graphics context
		graphicsManager.setGraphics(gl);

		// Calculate aspect-ratio-correct viewport
		double targetAspect = realWidth / realHeight;
		double windowAspect = (double) width / height;

		if (windowAspect > targetAspect) {
			// Window is wider than target - pillarbox (black bars on sides)
			viewportHeight = height;
			viewportWidth = (int) (height * targetAspect);
			viewportX = (width - viewportWidth) / 2;
			viewportY = 0;
		} else {
			// Window is taller than target - letterbox (black bars on top/bottom)
			viewportWidth = width;
			viewportHeight = (int) (width / targetAspect);
			viewportX = 0;
			viewportY = (height - viewportHeight) / 2;
		}

		// Set the viewport to the aspect-ratio-correct area
		gl.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

		// Setup perspective projection using current projection width
		// (H40=320 for levels, H32=256 for special stages)
		gl.glMatrixMode(GL_PROJECTION); // choose projection matrix
		gl.glLoadIdentity(); // reset projection matrix
		glu.gluOrtho2D(0, projectionWidth, 0, realHeight);

		// Enable the model-view transform
		gl.glMatrixMode(GL_MODELVIEW);
		gl.glLoadIdentity(); // reset
	}

	/**
	 * Updates the game state by one frame.
	 * Delegates to the GameLoop for headless-compatible logic.
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

	public void draw() {
		if (getCurrentGameMode() == GameMode.SPECIAL_STAGE) {
			// Check if sprite debug mode is active
			if (specialStageManager.isSpriteDebugMode()) {
				DebugSpecialStageSprites.getInstance().draw();
			} else {
				specialStageManager.draw();
			}
		} else if (getCurrentGameMode() == GameMode.SPECIAL_STAGE_RESULTS) {
			// Render results screen
			var resultsScreen = gameLoop.getResultsScreen();
			if (resultsScreen != null) {
				// Reset camera to (0,0) for screen-space rendering
				// Results screen is a full-screen overlay, not world-relative
				camera.setX((short) 0);
				camera.setY((short) 0);

				// Begin pattern batch for ROM art rendering
				graphicsManager.beginPatternBatch();

				java.util.List<uk.co.jamesj999.sonic.graphics.GLCommand> commands = new java.util.ArrayList<>();
				resultsScreen.appendRenderCommands(commands);

				// Register placeholder commands (for fallback rendering)
				if (!commands.isEmpty()) {
					graphicsManager.registerCommand(new uk.co.jamesj999.sonic.graphics.GLCommandGroup(
							com.jogamp.opengl.GL2.GL_LINES, commands));
				}
			}
		} else if (getCurrentGameMode() == GameMode.TITLE_CARD) {
			// Draw level and sprites behind the title card (Sonic is already placed and
			// frozen)
			levelManager.drawWithSpritePriority(spriteRenderManager);

			// Flush level commands with level camera position before switching to
			// screen-space
			graphicsManager.flush();

			// Reset OpenGL state for fixed-function rendering (RECTI commands for
			// background planes)
			graphicsManager.resetForFixedFunction();

			// Render title card overlay in screen-space (independent of camera)
			TitleCardManager titleCardManager = gameLoop.getTitleCardManager();
			if (titleCardManager != null) {
				titleCardManager.draw();
				// Title card commands will be flushed with screen-space camera
				graphicsManager.flushScreenSpace();
			}
		} else if (!debugViewEnabled) {
			levelManager.drawWithSpritePriority(spriteRenderManager);
		} else {
			switch (debugState) {
				case PATTERNS_VIEW -> levelManager.drawAllPatterns();
				case CHUNKS_VIEW -> levelManager.drawAllChunks();
				case BLOCKS_VIEW -> levelManager.draw();
				case null, default -> levelManager.drawWithSpritePriority(spriteRenderManager);
			}
		}
	}

	public static void main(String[] args) {
		// Run the GUI codes in the event-dispatching thread for thread safety
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				// Create the OpenGL rendering canvas
				GLCanvas canvas = new Engine();
				canvas.setFocusTraversalKeysEnabled(false);
				SonicConfigurationService configService = SonicConfigurationService
						.getInstance();
				int width = configService
						.getInt(SonicConfiguration.SCREEN_WIDTH);
				int height = configService
						.getInt(SonicConfiguration.SCREEN_HEIGHT);
				int fps = configService.getInt(SonicConfiguration.FPS);
				String version = SonicConfigurationService.ENGINE_VERSION;

				canvas.setPreferredSize(new Dimension(width, height));

				// Create a animator that drives canvas' display() at the
				// specified FPS.
				final FPSAnimator animator = new FPSAnimator(canvas, fps, true);

				// Create the top-level container
				final JFrame frame = new JFrame(); // Swing's JFrame or AWT's
													// Frame
				frame.getContentPane().add(canvas);

				JMenuBar menuBar = new JMenuBar();
				JMenu fileMenu = new JMenu("File");
				JMenuItem optionsItem = new JMenuItem("Options");
				optionsItem.addActionListener(e -> {
					new OptionsMenu(frame).setVisible(true);
				});
				fileMenu.add(optionsItem);
				menuBar.add(fileMenu);
				frame.setJMenuBar(menuBar);
				((Engine) canvas).setInputHandler(new InputHandler(canvas));
				frame.addWindowListener(new WindowAdapter() {
					@Override
					public void windowClosing(WindowEvent e) {
						// Use a dedicated thread to run the stop() to ensure
						// that the
						// animator stops before program exits.
						new Thread() {
							@Override
							public void run() {
								if (animator.isStarted())
									animator.stop();
								System.exit(0);
							}
						}.start();
					}
				});

				frame.addWindowFocusListener(new WindowAdapter() {
					@Override
					public void windowGainedFocus(WindowEvent e) {
						canvas.requestFocusInWindow();
						// request focus back to the canvas
					}
				});

				frame.setTitle("Java Sonic Engine by Jamesj999 and Raiscan "
						+ version);
				frame.pack();
				frame.setVisible(true);
				animator.start(); // start the animation loop

			}
		});
	}

	/**
	 * Called back by the animator to perform rendering.
	 */
	public void display(GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2(); // get the OpenGL 2 graphics context

		// Clear the entire window to black first (for letterbox/pillarbox bars)
		gl.glViewport(0, 0, drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
		gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		gl.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		// Set the viewport to the aspect-ratio-correct area for game rendering
		gl.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

		// Update projection matrix for current mode (H40=320 for levels, H32=256 for
		// special stages)
		// This must be done each frame since we can switch modes at runtime
		gl.glMatrixMode(GL_PROJECTION);
		gl.glLoadIdentity();
		glu.gluOrtho2D(0, projectionWidth, 0, realHeight);
		gl.glMatrixMode(GL_MODELVIEW);

		// Set clear color based on game mode and clear the game viewport
		if (getCurrentGameMode() == GameMode.SPECIAL_STAGE) {
			gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f); // Black for special stage
		} else if (getCurrentGameMode() == GameMode.SPECIAL_STAGE_RESULTS) {
			gl.glClearColor(0.85f, 0.9f, 0.95f, 1.0f); // Light blue/white for results
		} else if (getCurrentGameMode() == GameMode.TITLE_CARD) {
			// Use level background color so the stage is visible behind the title card
			levelManager.setClearColor(gl);
		} else {
			levelManager.setClearColor(gl);
		}
		gl.glScissor(viewportX, viewportY, viewportWidth, viewportHeight);
		gl.glEnable(GL2.GL_SCISSOR_TEST);
		gl.glClear(GL_COLOR_BUFFER_BIT);
		gl.glDisable(GL2.GL_SCISSOR_TEST);

		gl.glLoadIdentity(); // reset the model-view matrix
		gl.glDisable(GL2.GL_LIGHTING);
		gl.glDisable(GL2.GL_COLOR_MATERIAL);
		gl.glColorMask(true, true, true, true);
		update();

		// Update fade manager for screen transitions
		FadeManager fadeManager = graphicsManager.getFadeManager();
		if (fadeManager != null) {
			fadeManager.update();
		}

		graphicsManager.setGraphics(gl);
		draw();
		graphicsManager.flush();

		// Render screen fade overlay if active (after all game rendering)
		if (fadeManager != null && fadeManager.isActive()) {
			fadeManager.render(gl);
		}

		if (getCurrentGameMode() == GameMode.SPECIAL_STAGE && specialStageManager.isAlignmentTestMode()) {
			// Reset OpenGL state for JOGL's TextRenderer
			gl.glActiveTexture(GL2.GL_TEXTURE0);
			gl.glUseProgram(0);
			gl.glDisable(GL2.GL_LIGHTING);
			gl.glDisable(GL2.GL_COLOR_MATERIAL);
			gl.glDisable(GL2.GL_DEPTH_TEST);
			gl.glColor4f(1f, 1f, 1f, 1f);
			gl.glEnable(GL2.GL_TEXTURE_2D);
			gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
			gl.glActiveTexture(GL2.GL_TEXTURE1);
			gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
			gl.glActiveTexture(GL2.GL_TEXTURE0);
			// Reset matrices for 2D rendering
			gl.glMatrixMode(GL_PROJECTION);
			gl.glLoadIdentity();
			glu.gluOrtho2D(0, projectionWidth, 0, realHeight);
			gl.glMatrixMode(GL_MODELVIEW);
			gl.glLoadIdentity();

			// Re-enable blending for the TextRenderer
			gl.glEnable(GL2.GL_BLEND);
			gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);

			specialStageManager.renderAlignmentOverlay(drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
		}

		// Only show debug overlay in level mode, not during special stage
		if (debugViewEnabled && getCurrentGameMode() != GameMode.SPECIAL_STAGE) {
			// Reset OpenGL state for JOGL's TextRenderer
			gl.glActiveTexture(GL2.GL_TEXTURE0);
			gl.glUseProgram(0);
			gl.glDisable(GL2.GL_LIGHTING);
			gl.glDisable(GL2.GL_COLOR_MATERIAL);
			gl.glDisable(GL2.GL_DEPTH_TEST);
			gl.glColor4f(1f, 1f, 1f, 1f);
			gl.glEnable(GL2.GL_TEXTURE_2D);
			gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
			gl.glActiveTexture(GL2.GL_TEXTURE1);
			gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
			gl.glActiveTexture(GL2.GL_TEXTURE0);
			// Reset matrices for 2D rendering
			gl.glMatrixMode(GL_PROJECTION);
			gl.glLoadIdentity();
			glu.gluOrtho2D(0, projectionWidth, 0, realHeight);
			gl.glMatrixMode(GL_MODELVIEW);
			gl.glLoadIdentity();

			// Re-enable blending for the TextRenderer
			gl.glEnable(GL2.GL_BLEND);
			gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);

			debugRenderer.updateViewport(drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
			debugRenderer.renderDebugInfo();
		}
	}

	/**
	 * Called back before the OpenGL context is destroyed. Release resource such
	 * as buffers.
	 */
	public void dispose(GLAutoDrawable drawable) {
		graphicsManager.cleanup();
		AudioManager.getInstance().destroy();
	}

	public static void nextDebugState() {
		debugState = debugState.next();
		debugOption = DebugOption.A;
	}

	public static void nextDebugOption() {
		debugOption = debugOption.next();
	}
}
