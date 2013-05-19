package uk.co.jamesj999.sonic;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Transparency;
import java.awt.image.BufferedImage;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.graphics.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.Sonic;

/**
 * Controls the game.
 * 
 * @author james
 * 
 */
public class Engine {
	private final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();
	private final SpriteManager spriteManager = SpriteManager.getInstance();
	private InputHandler inputHandler;
	
	private int height = configService.getInt(SonicConfiguration.SCREEN_HEIGHT);
	private int width = configService.getInt(SonicConfiguration.SCREEN_WIDTH);
	private int scale = configService.getInt(SonicConfiguration.SCALE);

	private GraphicsConfiguration config;
	private JFrame frame;

	// TODO Add Log4J Support, or some other logging that allows proper
	// debugging etc. Any ideas?

	public Engine() {
		init();
		tick();
		System.exit(0);
	}

	public void init() {
		// TODO this bollocks is just to get a window. It'll be made a lot more
		// tidy once I work this shit out.


		// TODO change for Log4J
		System.out.println(height + " " + width);

		config = GraphicsEnvironment.getLocalGraphicsEnvironment()
				.getDefaultScreenDevice().getDefaultConfiguration();

		frame = new JFrame();
		// frame.addWindowListener(new FrameClose());
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.setSize(width * scale, height * scale);
		frame.setVisible(true);
		frame.setTitle("Sonic Engine by Jamesj999 "
				+ configService.getString(SonicConfiguration.VERSION));

		inputHandler = new InputHandler(frame);

		Sonic sonic = new Sonic("Sonic", 50, 50);
		spriteManager.addSprite(sonic);
	}

	/**
	 * The game tick. Controls the flow of the game.
	 */
	public void tick() {
		int fps = configService.getInt(SonicConfiguration.FPS);
		while (true) {
			long time = System.currentTimeMillis();

			update();
			draw();

			time = (1000 / fps) - (System.currentTimeMillis() - time);

			if (time > 0) {
				try {
					Thread.sleep(time);
				} catch (InterruptedException e) {
					// No need to worry about this one.
				}
			}
		}
	}

	public void update() {
		frame.getGraphics().clearRect(0, 0, width, height);
		spriteManager.update(inputHandler);
	}

	public void draw() {
		spriteManager.draw(frame);
	}

	public final BufferedImage create(final int width, final int height,
			final boolean alpha) {
		return config.createCompatibleImage(width, height,
				alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE);
	}

	public static void main(String[] args) {
		new Engine();
	}
}
