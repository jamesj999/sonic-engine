package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.Sprite;

public class TerrainSensorPair {
	private final Sensor left;
	private final Sensor right;

	public TerrainSensorPair(Sensor left, Sensor right) {
		this.left = left;
		this.right = right;
	}

	public byte getAngle() {
		short leftY = left.getY();
		short rightY = right.getY();
		return (byte) 0; //TODO finish
	}

	public void updateSensors(Sprite sprite) {
		left.updateX(sprite.getX());
		left.updateY(sprite.getY());

		right.updateX(sprite.getX());
		right.updateY(sprite.getY());
	}
}
