package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class TerrainCollisionManager {
	private static TerrainCollisionManager terrainCollisionManager;

	public short calculateTerrainHeight(AbstractSprite sprite) {
		short output = -1;

		for (SensorLine line : sprite.getTerrainSensorLines()) {
			if (line.getHeight() > output) {
				output = line.getHeight();
			}
		}

		return output;
	}

	public short calculateWallPosition(AbstractSprite sprite) {
		if (sprite.getWallSensorLine() == null) {
			return -1;
		}
		return sprite.getWallSensorLine().getX();
	}

	public synchronized static TerrainCollisionManager getInstance() {
		if (terrainCollisionManager == null) {
			terrainCollisionManager = new TerrainCollisionManager();
		}
		return terrainCollisionManager;
	}
}
