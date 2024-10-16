package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

public class GLCommand implements GLCommandable {
	private final int screenHeight = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_HEIGHT);
	public enum CommandType {
		RECTI, VERTEX2I;
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
	 * 
	 * @param glCmdCommandType
	 * @param drawMethod
	 * @param colour1
	 * @param colour2
	 * @param colour3
	 * @param x1
	 * @param y1
	 * @param x2
	 * @param y2
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

	public void execute(GL2 gl, int cameraX, int cameraY, int cameraWidth,
			int cameraHeight) {
		// int xLeftBound = cameraX;
		// int xRightBound = cameraX + cameraWidth;
		// int yBottomBound = cameraY;
		// int yTopBound = cameraY + cameraHeight;
		//
		// if ((x1 < xLeftBound && x2 < xLeftBound)
		// || (x1 > xRightBound && x2 > xRightBound)
		// || (y1 < yBottomBound && x2 < yBottomBound)
		// || (y1 > yTopBound && y2 > yTopBound)) {
		// return;
		// }
		boolean single = drawMethod != -1;
		if (single) {
			gl.glBegin(drawMethod);
		}

		if (blendMode == BlendType.SOLID) {
			gl.glDisable(GL2.GL_BLEND);
		}
		if (blendMode == BlendType.ONE_MINUS_SRC_ALPHA) {
			gl.glEnable(GL2.GL_BLEND);
			gl.glBlendFunc(GL2.GL_SRC_ALPHA,GL2.GL_ONE_MINUS_SRC_ALPHA);
		}
		gl.glColor3f(colour1, colour2, colour3);
		if (CommandType.RECTI.equals(glCmdCommandType)) {
			gl.glRecti(x1 - cameraX, y1 + cameraY, x2 - cameraX, y2 + cameraY);
		} else if (CommandType.VERTEX2I.equals(glCmdCommandType)) {
			gl.glVertex2i(x1 - cameraX, y1 + cameraY);
		}
		if (single) {
			gl.glEnd();
		}
	}
}
