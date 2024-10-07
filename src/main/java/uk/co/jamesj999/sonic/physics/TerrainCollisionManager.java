package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class TerrainCollisionManager {
	private static TerrainCollisionManager terrainCollisionManager;

	// In the future, we may need to expand this method to work with AbstractSprite too for NPCs.
	public short calculateTerrainHeight(AbstractPlayableSprite sprite) {
		short output = -1;
		Direction direction = null;

		for (SensorLine line : sprite.getSensorLinesForDirection(Direction.DOWN)) {
			SensorResult result = line.scan();
			if (result != null && result.distance() > output) {
				output = result.distance();
				direction = line.getDirection();
				sprite.setAngle(result.angle());
			}
		}

		if (output > -1 && direction != null) {
			switch (direction) {
				case UP -> {
					output += (sprite.getCentreY() + (sprite.getHeight() / 2));
				}
				case DOWN -> {
					output += (sprite.getCentreY() - (sprite.getHeight() / 2));
				}
				case LEFT -> {
					//TODO
				}
				case RIGHT -> {
					//TODO
				}
			}
		}
		return output;
	}

	// In the future, we may need to expand this method to work with AbstractSprite too for NPCs.
	public short calculateWallPosition(AbstractPlayableSprite sprite) {
		short output = -1;
		Direction direction = null;

		for (SensorLine line : sprite.getSensorLinesForDirection(Direction.LEFT, Direction.RIGHT)) {
			SensorResult result = line.scan();
			if (result != null && result.distance() > output) {
				output = result.distance();
				direction = line.getDirection();
			}
		}

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
