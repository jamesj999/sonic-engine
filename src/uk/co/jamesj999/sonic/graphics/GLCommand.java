package uk.co.jamesj999.sonic.graphics;

import javax.media.opengl.GL2;

public class GLCommand implements GLCommandable {
	public enum Type {
		RECTI, VERTEX2I;
	}

	private Type type;
	private int drawMethod;
	private int colour1;
	private int colour2;
	private int colour3;
	private int x1;
	private int y1;
	private int x2;
	private int y2;

	/**
	 * A new GLCommand to add to the GraphicsManager's draw queue.
	 * 
	 * @param type
	 * @param drawMethod
	 * @param colour1
	 * @param colour2
	 * @param colour3
	 * @param x1
	 * @param y1
	 * @param x2
	 * @param y2
	 */
	public GLCommand(Type type, int drawMethod, int colour1, int colour2,
			int colour3, int x1, int y1, int x2, int y2) {
		this.type = type;
		this.drawMethod = drawMethod;
		this.colour1 = colour1;
		this.colour2 = colour2;
		this.colour3 = colour3;
		this.x1 = x1;
		this.y1 = y1;
		this.x2 = x2;
		this.y2 = y2;
	}

	@Override
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
		gl.glColor3f(colour1, colour2, colour3);
		if (Type.RECTI.equals(type)) {
			gl.glRecti(x1 - cameraX, y1 - cameraY, x2 - cameraX, y2 - cameraY);
		} else if (Type.VERTEX2I.equals(type)) {
			gl.glVertex2i(x1 - cameraX, y1 - cameraY);
		}
		if (single) {
			gl.glEnd();
		}
	}
}
