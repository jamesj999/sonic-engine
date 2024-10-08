package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class TerrainCollisionManager {
	private static TerrainCollisionManager terrainCollisionManager;

	// In the future, we may need to expand this method to work with AbstractSprite too for NPCs.
	public short calculateTerrainHeight(AbstractPlayableSprite sprite) {
		// I know, I thought it was closest to 0 too, but apparently not
		Byte lowestDistance = null;
		for (Sensor sensor : sprite.getGroundSensors()) {
			SensorResult result = sensor.scan();
			byte distance = result.distance();
			if (lowestDistance == null || distance < lowestDistance) {
				lowestDistance = distance;
			}
		}
		if(lowestDistance == null) {
			lowestDistance = 0;
		}

		if(lowestDistance < 14) {
			short newX = sprite.getX();
			short newY = sprite.getY();

			switch (sprite.getGroundMode()) {
				case GROUND -> {
					newY += lowestDistance;
				}
				case RIGHTWALL -> {
					newX += lowestDistance;
				}
				case CEILING -> {
					newY -= lowestDistance;
				}
				case LEFTWALL -> {
					newX -= lowestDistance;
				}
			}
			sprite.setX(newX);
			sprite.setY(newY);
		}
		return 0;
	}

	// In the future, we may need to expand this method to work with AbstractSprite too for NPCs.
	public short calculateWallPosition(AbstractPlayableSprite sprite) {
		short output = -1;
		Direction direction = null;

//		for (SensorLine line : sprite.getSensorLinesForDirection(Direction.LEFT, Direction.RIGHT)) {
//			SensorResult result = line.scan();
//			if (result != null && result.distance() > output) {
//				output = result.distance();
//				direction = line.getDirection();
//			}
//		}

		if (output > -1 && direction != null) {
			switch (direction) {
				case UP -> {
					//TODO
				}
				case DOWN -> {
					//TODO
				}
				case LEFT -> {
					output -= (sprite.getCentreX() + (sprite.getWidth() / 2));
				}
				case RIGHT -> {
					output += (sprite.getCentreY() + (sprite.getWidth() / 2));
				}
			}
		}
		return output;
	}

	public synchronized static TerrainCollisionManager getInstance() {
		if (terrainCollisionManager == null) {
			terrainCollisionManager = new TerrainCollisionManager();
		}
		return terrainCollisionManager;
	}
}
