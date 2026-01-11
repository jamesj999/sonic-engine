package uk.co.jamesj999.sonic;

import com.jogamp.opengl.util.FPSAnimator;
import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.JOALAudioBackend;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.configuration.OptionsMenu;
import uk.co.jamesj999.sonic.debug.DebugOverlayManager;
import uk.co.jamesj999.sonic.debug.DebugObjectArtViewer;
import uk.co.jamesj999.sonic.debug.DebugOption;
import uk.co.jamesj999.sonic.debug.DebugRenderer;
import uk.co.jamesj999.sonic.debug.DebugState;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.graphics.SpriteRenderManager;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteCollisionManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.Sonic;
import uk.co.jamesj999.sonic.sprites.playable.Tails;
import uk.co.jamesj999.sonic.timer.TimerManager;
import uk.co.jamesj999.sonic.game.GameMode;
import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.game.sonic2.CheckpointState;
import uk.co.jamesj999.sonic.game.sonic2.LevelGamestate;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageManager;

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

	// Current projection width - can be changed for H32/H40 mode switching
	// H40 mode (normal levels): 320 pixels wide
	// H32 mode (special stages): 256 pixels wide
	private double projectionWidth = realWidth;

    private boolean debugViewEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
	private boolean debugModeEnabled = configService.getBoolean(SonicConfiguration.DEBUG_MODE);

	// TODO move this into a manager
	private final LevelManager levelManager = LevelManager.getInstance();
	private final Sonic2SpecialStageManager specialStageManager = Sonic2SpecialStageManager.getInstance();

	private GameMode currentGameMode = GameMode.LEVEL;

	// Saved camera position for returning from special stage
	private short savedCameraX = 0;
	private short savedCameraY = 0;

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
			mainSprite = new Tails(mainCode, (short) 100, (short) 624, debugModeEnabled);
		} else {
			mainSprite = new Sonic(mainCode, (short) 100, (short) 624, debugModeEnabled);
		}
		spriteManager.addSprite(mainSprite);

		// Causes camera to instantiate itself... TODO Probably remove this
		// later since it'll be used in the first update loop anyway
		camera.setFocusedSprite(mainSprite);
		camera.updatePosition(true);

		//levelManager.setLevel(new TestOldLevel());

		try {
			levelManager.loadZoneAndAct(0, 0);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
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

		// Setup perspective projection using current projection width
		// (H40=320 for levels, H32=256 for special stages)
		gl.glMatrixMode(GL_PROJECTION); // choose projection matrix
		gl.glLoadIdentity(); // reset projection matrix
		glu.gluOrtho2D(0, projectionWidth, 0, realHeight);

		// Enable the model-view transform
		gl.glMatrixMode(GL_MODELVIEW);
		gl.glLoadIdentity(); // reset
	}

        public void update() {
                AudioManager.getInstance().update();
                timerManager.update();
                DebugOverlayManager.getInstance().updateInput(inputHandler);
                DebugObjectArtViewer.getInstance().updateInput(inputHandler);

                // Check for Special Stage toggle (HOME by default)
                if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_KEY))) {
                        handleSpecialStageDebugKey();
                }

                if (currentGameMode == GameMode.SPECIAL_STAGE) {
                        updateSpecialStageInput();
                        specialStageManager.update();

                        // Check for special stage completion or failure
                        if (specialStageManager.isFinished()) {
                                var result = specialStageManager.getResultState();
                                boolean completed = (result == Sonic2SpecialStageManager.ResultState.COMPLETED);
                                boolean gotEmerald = completed && specialStageManager.hasEmeraldCollected();
                                exitSpecialStage(completed, gotEmerald);
                        }
                } else {
                        boolean freezeForArtViewer = DebugOverlayManager.getInstance()
                                        .isEnabled(uk.co.jamesj999.sonic.debug.DebugOverlayToggle.OBJECT_ART_VIEWER);
                        if (!freezeForArtViewer) {
                                spriteCollisionManager.update(inputHandler);
                                camera.updatePosition();
                                levelManager.update();

                                // Check if a checkpoint star requested a special stage
                                if (levelManager.consumeSpecialStageRequest()) {
                                        enterSpecialStage();
                                }
                        }

                        if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.NEXT_ACT))) {
                                try {
                                        levelManager.nextAct();
                                } catch (IOException e) {
                                        throw new RuntimeException(e);
                                }
                        }

                        if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.NEXT_ZONE))) {
                                try {
                                        levelManager.nextZone();
                                } catch (IOException e) {
                                        throw new RuntimeException(e);
                                }
                        }
                }

                inputHandler.update();
        }

        /**
         * Handles the special stage debug key (HOME by default).
         * When in level mode, enters the next special stage.
         * When in special stage mode, exits back to level (as failure).
         */
        private void handleSpecialStageDebugKey() {
                if (currentGameMode == GameMode.LEVEL) {
                        enterSpecialStage();
                } else if (currentGameMode == GameMode.SPECIAL_STAGE) {
                        exitSpecialStage(false, false);
                }
        }

        /**
         * Enters the special stage from level mode.
         * Uses GameStateManager to track which stage to enter (cycles 0-6).
         */
        private void enterSpecialStage() {
                if (currentGameMode != GameMode.LEVEL) {
                        return;
                }

                GameStateManager gsm = GameStateManager.getInstance();
                int stageIndex = gsm.consumeCurrentSpecialStageIndexAndAdvance();

                try {
                        // Save camera position for when we return
                        savedCameraX = camera.getX();
                        savedCameraY = camera.getY();

                        specialStageManager.reset();
                        specialStageManager.initialize(stageIndex);
                        currentGameMode = GameMode.SPECIAL_STAGE;

                        // Keep projection at 320 for proper letterboxing
                        // The special stage renderer will center the 256px content within the 320px viewport
                        // This creates black bars on the sides (32px each) for authentic H32 mode look
                        projectionWidth = realWidth;

                        // Set camera to origin for special stage rendering (uses screen coordinates)
                        camera.setX((short) 0);
                        camera.setY((short) 0);

                        AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_SPECIAL_STAGE);

                        java.util.logging.Logger.getLogger(Engine.class.getName())
                                .info("Entered Special Stage " + (stageIndex + 1) + " (H32 mode: 256x224)");
                } catch (IOException e) {
                        throw new RuntimeException("Failed to initialize Special Stage " + (stageIndex + 1), e);
                }
        }

        /**
         * Exits the special stage and returns to the level.
         * @param completed true if the stage was completed (reached end)
         * @param emeraldCollected true if an emerald was collected
         */
        private void exitSpecialStage(boolean completed, boolean emeraldCollected) {
                if (currentGameMode != GameMode.SPECIAL_STAGE) {
                        return;
                }

                if (emeraldCollected) {
                        GameStateManager gsm = GameStateManager.getInstance();
                        int emeraldIndex = specialStageManager.getCurrentStage();
                        gsm.markEmeraldCollected(emeraldIndex);

                        java.util.logging.Logger.getLogger(Engine.class.getName())
                                .info("Collected emerald " + (emeraldIndex + 1) + "! Total: " + gsm.getEmeraldCount());
                }

                specialStageManager.reset();
                currentGameMode = GameMode.LEVEL;

                // Restore H40 mode projection (320 pixels wide)
                projectionWidth = realWidth;

                // Restore level palettes (special stage overwrites them)
                levelManager.reloadLevelPalettes();

                // Restore camera position
                camera.setX(savedCameraX);
                camera.setY(savedCameraY);

                String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
                if (mainCode == null) mainCode = "sonic";
                var sprite = spriteManager.getSprite(mainCode);

                if (sprite instanceof AbstractPlayableSprite playable) {
                        CheckpointState checkpointState = levelManager.getCheckpointState();

                        if (checkpointState != null && checkpointState.isActive()) {
                                checkpointState.restoreToPlayer(playable, camera);
                        }

                        LevelGamestate gamestate = levelManager.getLevelGamestate();
                        if (gamestate != null) {
                                gamestate.setRings(0);
                        }
                }

                java.util.logging.Logger.getLogger(Engine.class.getName())
                        .info("Exited Special Stage, returned to level at checkpoint");
        }

        private void updateSpecialStageInput() {
                int leftKey = configService.getInt(SonicConfiguration.LEFT);
                int rightKey = configService.getInt(SonicConfiguration.RIGHT);
                int jumpKey = configService.getInt(SonicConfiguration.JUMP);

                int heldButtons = 0;
                int pressedButtons = 0;

                if (inputHandler.isKeyDown(leftKey)) {
                        heldButtons |= 0x04;
                }
                if (inputHandler.isKeyDown(rightKey)) {
                        heldButtons |= 0x08;
                }

                if (inputHandler.isKeyPressed(jumpKey)) {
                        pressedButtons |= 0x70;
                }
                if (inputHandler.isKeyDown(jumpKey)) {
                        heldButtons |= 0x70;
                }

                specialStageManager.handleInput(heldButtons, pressedButtons);
        }



        public void draw() {
                if (currentGameMode == GameMode.SPECIAL_STAGE) {
                        specialStageManager.draw();
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

		// Update projection matrix for current mode (H40=320 for levels, H32=256 for special stages)
		// This must be done each frame since we can switch modes at runtime
		gl.glMatrixMode(GL_PROJECTION);
		gl.glLoadIdentity();
		glu.gluOrtho2D(0, projectionWidth, 0, realHeight);
		gl.glMatrixMode(GL_MODELVIEW);

		// Set clear color based on game mode
		if (currentGameMode == GameMode.SPECIAL_STAGE) {
			gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f); // Black for special stage
		} else {
			levelManager.setClearColor(gl);
		}
		gl.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear color
																// and depth
																// buffers
                gl.glLoadIdentity(); // reset the model-view matrix
                gl.glDisable(GL2.GL_LIGHTING);
                gl.glDisable(GL2.GL_COLOR_MATERIAL);
                gl.glColorMask(true, true, true, true);
                update();
		graphicsManager.setGraphics(gl);
		draw();
		graphicsManager.flush();

		// Only show debug overlay in level mode, not during special stage
                if (debugViewEnabled && currentGameMode != GameMode.SPECIAL_STAGE) {
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
