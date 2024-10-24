package uk.co.jamesj999.sonic.graphics;

import org.lwjgl.opengl.GL11;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

public class GLCommand implements GLCommandable {

	private final int screenHeight = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_HEIGHT);

	public enum CommandType {
		RECTI, VERTEX2I
	}

	public enum BlendType {
		SOLID, ONE_MINUS_SRC_ALPHA
	}

	private final BlendType defaultBlendMode = BlendType.SOLID;

	private CommandType glCmdCommandType;
	private int drawMethod;
	private BlendType blendMode;
	private float colour1;
	private float colour2;
	private float colour3;
	private int x1;
	private int y1;
	private int x2;
	private int y2;

	/**
	 * A new GLCommand to add to the GraphicsManager's draw queue.
	 */
	public GLCommand(CommandType glCmdCommandType, int drawMethod, float colour1, float colour2,
					 float colour3, int x1, int y1, int x2, int y2) {
		this.glCmdCommandType = glCmdCommandType;
		this.drawMethod = drawMethod;
		this.colour1 = colour1;
		this.colour2 = colour2;
		this.colour3 = colour3;
		this.x1 = x1;
		this.y1 = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS) - y1;
		this.x2 = x2;
		this.y2 = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS) - y2;
		this.blendMode = defaultBlendMode;
	}

	public GLCommand(CommandType glCmdCommandType, int drawMethod, BlendType blendType, float colour1, float colour2,
					 float colour3, int x1, int y1, int x2, int y2) {
		this.glCmdCommandType = glCmdCommandType;
		this.drawMethod = drawMethod;
		this.colour1 = colour1;
		this.colour2 = colour2;
		this.colour3 = colour3;
		this.x1 = x1;
		this.y1 = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS) - y1;
		this.x2 = x2;
		this.y2 = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS) - y2;
		this.blendMode = blendType;
	}

	public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
		boolean single = drawMethod != -1;
		if (single) {
			GL11.glBegin(drawMethod);
		}

		if (blendMode == BlendType.SOLID) {
			GL11.glDisable(GL11.GL_BLEND);
		}
		if (blendMode == BlendType.ONE_MINUS_SRC_ALPHA) {
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		}

		GL11.glColor3f(colour1, colour2, colour3);

		if (CommandType.RECTI.equals(glCmdCommandType)) {
			GL11.glRecti(x1 - cameraX, y1 + cameraY, x2 - cameraX, y2 + cameraY);
		} else if (CommandType.VERTEX2I.equals(glCmdCommandType)) {
			GL11.glVertex2i(x1 - cameraX, y1 + cameraY);
		}

		if (single) {
			GL11.glEnd();
		}
	}
}
