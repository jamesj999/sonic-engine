package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.Tile;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class TerrainCollisionManager {
	private static TerrainCollisionManager terrainCollisionManager;

	private final LevelManager levelManager = LevelManager.getInstance();

	public byte calculateTerrainHeight(Sprite sprite) {
		if (sprite instanceof AbstractPlayableSprite) {
			TerrainSensorPair sensors = ((AbstractPlayableSprite) sprite)
					.getGroundSensors();
			Sensor left = sensors.getLeft();
			Sensor right = sensors.getRight();
			Tile leftTile = left.getTile();
			Tile rightTile = right.getTile();
			byte leftHeight = leftTile.getHeightAt((byte) (left.getX() % 16));
			byte rightHeight = rightTile
					.getHeightAt((byte) (right.getX() % 16));
			return (leftHeight > rightHeight) ? leftHeight : rightHeight;
		}
		return (byte) 0; // TODO finish
	}

	public synchronized static TerrainCollisionManager getInstance() {
		if (terrainCollisionManager == null) {
			terrainCollisionManager = new TerrainCollisionManager();
		}
		return terrainCollisionManager;
	}
}
