package uk.co.jamesj999.sonic;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Transparency;
import java.awt.image.BufferedImage;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.WindowConstants;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

/**
 * Controls the game.
 * 
 * @author james
 * 
 */
public class Engine {
	private final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();

	private GraphicsConfiguration config;
	private JFrame frame;

	// TODO Add Log4J Support

	public Engine() {
		startGame();
	}

	public void startGame() {
		// TODO this bollocks is just to get a window. It'll be made a lot more
		// tidy once I work this shit out.
		int height = configService.getInt(SonicConfiguration.SCREEN_HEIGHT);
		int width = configService.getInt(SonicConfiguration.SCREEN_WIDTH);
		int scale = configService.getInt(SonicConfiguration.SCALE);

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
		frame.getContentPane().add(new JLabel("Poopings"));
		frame.repaint();
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
