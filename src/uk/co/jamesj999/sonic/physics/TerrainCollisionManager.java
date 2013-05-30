package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.Tile;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class TerrainCollisionManager {
	private static TerrainCollisionManager terrainCollisionManager;

	// private final LevelManager levelManager = LevelManager.getInstance();

	public byte calculateTerrainHeight(Sprite sprite) {
		if (sprite instanceof AbstractPlayableSprite) {
			TerrainSensorPair sensors = ((AbstractPlayableSprite) sprite)
					.getGroundSensors();
			Sensor left = sensors.getLeft();
			Sensor right = sensors.getRight();
			System.out.println("Left X:" + left.getX() + ". Right X: "
					+ right.getX());
			System.out.println("Left Y:" + left.getY() + ". Right Y:"
					+ right.getY());
			Tile leftTile = left.getTile();
			Tile rightTile = right.getTile();
			byte leftHeight = -1;
			byte rightHeight = -1;
			if (leftTile != null) {
				leftHeight = leftTile.getHeightAt((byte) (left.getX() % 16));
			}
			if (rightTile != null) {
				rightHeight = rightTile.getHeightAt((byte) (right.getX() % 16));
			}
			return (leftHeight > rightHeight) ? leftHeight : rightHeight;
		}
		return (byte) -1; // TODO finish
	}

	public synchronized static TerrainCollisionManager getInstance() {
		if (terrainCollisionManager == null) {
			terrainCollisionManager = new TerrainCollisionManager();
		}
		return terrainCollisionManager;
	}
}
