package uk.co.jamesj999.sonic;

import com.jogamp.opengl.util.FPSAnimator;
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
	private final SpriteCollisionManager spriteCollisionManager = SpriteCollisionManager.getInstance();
	private final GraphicsManager graphicsManager = GraphicsManager.getInstance();

	private final Camera camera = Camera.getInstance();
	private final DebugRenderer debugRenderer = DebugRenderer.getInstance();
    private final TimerManager timerManager = TimerManager.getInstance();

	private InputHandler inputHandler;
	public static DebugState debugState = DebugState.NONE;
	public static DebugOption debugOption = DebugOption.A;

	private double realWidth = configService
			.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
	private double realHeight = configService
			.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

    private boolean debugViewEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
	private boolean debugModeEnabled = configService.getBoolean(SonicConfiguration.DEBUG_MODE);

	// TODO move this into a manager
	private final LevelManager levelManager = LevelManager.getInstance();

	private GLU glu;

	// TODO Add Log4J Support, or some other logging that allows proper
	// debugging etc. Any ideas?

	public Engine() {
		this.addGLEventListener(this);
	}

	public void setInputHandler(InputHandler inputHandler) {
		this.inputHandler = inputHandler;
	}

	public void init(GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2(); // get the OpenGL graphics context
		glu = new GLU(); // get GL Utilities
		gl.glClearColor(0.2f, 0.4f, 1.0f, 1.0f); // set background (clear) color
		gl.glShadeModel(GL_SMOOTH); // blends colors nicely, and smooths out
									// lighting
        try {
            graphicsManager.init(gl, RESOURCES_SHADERS_PIXEL_SHADER_GLSL);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        graphicsManager.setGraphics(gl);

		Sonic sonic = new Sonic(
				configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE),
				(short) 100, (short) 624, debugModeEnabled);
		spriteManager.addSprite(sonic);

		// Causes camera to instantiate itself... TODO Probably remove this
		// later since it'll be used in the first update loop anyway
		camera.setFocusedSprite(sonic);
		camera.updatePosition(true);

		// Load our ROM and Level
		try {
			levelManager.loadLevel(0);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
        //levelManager.setLevel(new TestOldLevel());
	}

	/**
	 * Call-back handler for window re-size event. Also called when the drawable
	 * is first set to visible.
	 */
	public void reshape(GLAutoDrawable drawable, int x, int y, int width,
			int height) {
		GL2 gl = drawable.getGL().getGL2(); // get the OpenGL 2 graphics context
		graphicsManager.setGraphics(gl);

		// Set the view port (display area) to cover the entire window
		gl.glViewport(0, 0, width, height);

		// Setup perspective projection, with aspect ratio matches viewport
		gl.glMatrixMode(GL_PROJECTION); // choose projection matrix
		gl.glLoadIdentity(); // reset projection matrix
		glu.gluOrtho2D(0, realWidth, 0, realHeight);

		// Enable the model-view transform
		gl.glMatrixMode(GL_MODELVIEW);
		gl.glLoadIdentity(); // reset
	}

	public void update() {
        timerManager.update();
		spriteCollisionManager.update(inputHandler);
		camera.updatePosition();
	}

	public void draw() {
		if (!debugViewEnabled) {
			levelManager.draw();
			spriteRenderManager.draw();
		} else {
			switch (debugState) {
				case PATTERNS_VIEW -> levelManager.drawAllPatterns();
				case CHUNKS_VIEW -> levelManager.drawAllChunks();
				case BLOCKS_VIEW -> levelManager.draw();
				case null, default -> { levelManager.draw(); spriteRenderManager.draw(); }
			}
		}
	}

	public static void main(String[] args) {
		// Run the GUI codes in the event-dispatching thread for thread safety
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				// Create the OpenGL rendering canvas
				GLCanvas canvas = new Engine();
				SonicConfigurationService configService = SonicConfigurationService
						.getInstance();
				int width = configService
						.getInt(SonicConfiguration.SCREEN_WIDTH);
				int height = configService
						.getInt(SonicConfiguration.SCREEN_HEIGHT);
				int fps = configService.getInt(SonicConfiguration.FPS);
				String version = configService
						.getString(SonicConfiguration.VERSION);

				canvas.setPreferredSize(new Dimension(width, height));

				// Create a animator that drives canvas' display() at the
				// specified FPS.
				final FPSAnimator animator = new FPSAnimator(canvas, fps, true);

				// Create the top-level container
				final JFrame frame = new JFrame(); // Swing's JFrame or AWT's
													// Frame
				frame.getContentPane().add(canvas);
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
		gl.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear color
																// and depth
																// buffers
		gl.glLoadIdentity(); // reset the model-view matrix
		update();
		graphicsManager.setGraphics(gl);
		draw();
		graphicsManager.flush();
		if (debugViewEnabled) {
			debugRenderer.renderDebugInfo();
		}
	}

	/**
	 * Called back before the OpenGL context is destroyed. Release resource such
	 * as buffers.
	 */
	public void dispose(GLAutoDrawable drawable) {
		graphicsManager.cleanup();
	}

	public static void nextDebugState() {
		debugState = debugState.next();
		debugOption = DebugOption.A;
	}

	public static void nextDebugOption() {
		debugOption = debugOption.next();
	}
}
