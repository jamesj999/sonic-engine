package uk.co.jamesj999.sonic;

import static javax.media.opengl.GL.GL_COLOR_BUFFER_BIT;
import static javax.media.opengl.GL.GL_DEPTH_BUFFER_BIT;
import static javax.media.opengl.fixedfunc.GLLightingFunc.GL_SMOOTH;
import static javax.media.opengl.fixedfunc.GLMatrixFunc.GL_MODELVIEW;
import static javax.media.opengl.fixedfunc.GLMatrixFunc.GL_PROJECTION;

import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.media.opengl.GL2;
import javax.media.opengl.GLAutoDrawable;
import javax.media.opengl.GLEventListener;
import javax.media.opengl.awt.GLCanvas;
import javax.media.opengl.glu.GLU;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.graphics.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.Sonic;

import com.jogamp.opengl.util.FPSAnimator;

/**
 * Controls the game.
 * 
 * @author james
 * 
 */
@SuppressWarnings("serial")
public class Engine extends GLCanvas implements GLEventListener {
	private final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();
	private final SpriteManager spriteManager = SpriteManager.getInstance();
	private InputHandler inputHandler;

	private double realWidth = configService
			.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
	private double realHeight = configService
			.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

	private GLU glu;

	// TODO Add Log4J Support, or some other logging that allows proper
	// debugging etc. Any ideas?

	public Engine() {
		this.addGLEventListener(this);
	}

	public void setInputHandler(InputHandler inputHandler) {
		this.inputHandler = inputHandler;
	}

	@Override
	public void init(GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2(); // get the OpenGL graphics context
		glu = new GLU(); // get GL Utilities
		gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f); // set background (clear) color
		//gl.glClearDepth(1.0f); // set clear depth value to farthest
		//gl.glEnable(GL_DEPTH_TEST); // enables depth testing
		//gl.glDepthFunc(GL_LEQUAL); // the type of depth test to do
		//gl.glHint(GL_PERSPECTIVE_CORRECTION_HINT, GL_NICEST); // best
																// perspective
																// correction
		gl.glShadeModel(GL_SMOOTH); // blends colors nicely, and smoothes out
									// lighting
		Sonic sonic = new Sonic("Sonic", (short) 50, (short) 200);
		spriteManager.addSprite(sonic);
		// ----- Your OpenGL initialization code here -----
	}

	/**
	 * Call-back handler for window re-size event. Also called when the drawable
	 * is first set to visible.
	 */
	@Override
	public void reshape(GLAutoDrawable drawable, int x, int y, int width,
			int height) {
		GL2 gl = drawable.getGL().getGL2(); // get the OpenGL 2 graphics context

		// Set the view port (display area) to cover the entire window
		gl.glViewport(0, 0, width, height);

		// Setup perspective projection, with aspect ratio matches viewport
		gl.glMatrixMode(GL_PROJECTION); // choose projection matrix
		gl.glLoadIdentity(); // reset projection matrix
		glu.gluOrtho2D(0, realWidth, 0, realHeight); // fovy, aspect, zNear,
		// zFar

		// Enable the model-view transform
		gl.glMatrixMode(GL_MODELVIEW);
		gl.glLoadIdentity(); // reset
	}

	public void update() {
		spriteManager.update(inputHandler);
	}

	public void draw(GL2 gl) {
		// Graphics graphics = canvas.getBufferStrategy().getDrawGraphics();
		// graphics.clearRect(0, 0, width, height);
		spriteManager.draw(gl);
		// graphics.dispose();
		// canvas.getBufferStrategy().show();
		// Toolkit.getDefaultToolkit().sync();
	}

	public static void main(String[] args) {
		// Run the GUI codes in the event-dispatching thread for thread safety
		SwingUtilities.invokeLater(new Runnable() {
			@Override
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
				((Engine) canvas).setInputHandler(new InputHandler(frame));
				frame.addWindowListener(new WindowAdapter() {
					@Override
					public void windowClosing(WindowEvent e) {
						// Use a dedicate thread to run the stop() to ensure
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
	@Override
	public void display(GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2(); // get the OpenGL 2 graphics context
		gl.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear color
																// and depth
																// buffers
		gl.glLoadIdentity(); // reset the model-view matrix
		update();
		draw(gl);
	}

	/**
	 * Called back before the OpenGL context is destroyed. Release resource such
	 * as buffers.
	 */
	@Override
	public void dispose(GLAutoDrawable drawable) {
	}
}
