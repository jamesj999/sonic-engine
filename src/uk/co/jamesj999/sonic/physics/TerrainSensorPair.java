package uk.co.jamesj999.sonic.physics;

import javax.media.opengl.GL2;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.sprites.Sprite;

public class TerrainSensorPair {
	private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
	
	private final Sensor left;
	private final Sensor right;

	public TerrainSensorPair(Sensor left, Sensor right) {
		this.left = left;
		this.right = right;
	}

	public byte getAngle() {
		short leftY = left.getY();
		short rightY = right.getY();
		return (byte) 0; // TODO finish
	}

	public void updateSensors(Sprite sprite) {
		short x = sprite.getCentreX();
		short y = sprite.getCentreY();

		left.updateX(x);
		left.updateY(y);

		right.updateX(x);
		right.updateY(y);
	}

	public void draw() {
		graphicsManager.registerCommand(new GLCommand(GLCommand.Type.RECTI, GL2.GL_2D, 1, 0, 0, left.getX(), left.getY(), right.getX(), right.getY() + 1));
		// if (gl != null) {
		// gl.glBegin(GL2.GL_2D);
		// gl.glColor3f(1.0f, 0, 0);
		// gl.glRecti(left.getX(), left.getY(), right.getX(), right.getY() + 1);
		// gl.glColor3f(1.0f, 1.0f, 1.0f);
		// gl.glEnd();
		// }
	}

	public Sensor getLeft() {
		return left;
	}

	public Sensor getRight() {
		return right;
	}

	public short getLeftX() {
		return left.getX();
	}

	public short getLeftY() {
		return left.getY();
	}

	public short getRightX() {
		return right.getX();
	}

	public short getRightY() {
		return right.getY();
	}
}
