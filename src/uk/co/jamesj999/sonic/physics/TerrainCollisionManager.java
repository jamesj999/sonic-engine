package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.Tile;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class TerrainCollisionManager {
	private static TerrainCollisionManager terrainCollisionManager;

	// private final LevelManager levelManager = LevelManager.getInstance();

	public short calculateTerrainHeight(Sprite sprite) {
		if (sprite instanceof AbstractPlayableSprite) {
			if (((AbstractPlayableSprite) sprite).getYSpeed() <= 0) {
				TerrainSensorPair sensors = ((AbstractPlayableSprite) sprite)
						.getGroundSensors();
				Sensor left = sensors.getLeft();
				Sensor right = sensors.getRight();
				// System.out.println("Left X:" + left.getX() + ". Right X: "
				// + right.getX());
				// System.out.println("Left Y:" + left.getY() + ". Right Y:"
				// + right.getY());
				Tile leftTile = left.getTile();
				Tile rightTile = right.getTile();
				short leftHeight = -1;
				short rightHeight = -1;
				if (leftTile != null) {
					leftHeight = (short) ((leftTile.getHeightAt((byte) (left
							.getX() % 16))) + 16 * Math.round(sensors
							.getLeftY() / 16));
				}
				if (rightTile != null) {
					rightHeight = (short) ((rightTile.getHeightAt((byte) (right
							.getX() % 16))) + 16 * Math.round(sensors
							.getRightY() / 16));
				}

				if (leftHeight > -1 || rightHeight > -1) {
					((AbstractPlayableSprite) sprite).setAir(false);
				}
				return (leftHeight > rightHeight) ? leftHeight : rightHeight;
			}
		}
		return (byte) -1;
	}

	public synchronized static TerrainCollisionManager getInstance() {
		if (terrainCollisionManager == null) {
			terrainCollisionManager = new TerrainCollisionManager();
		}
		return terrainCollisionManager;
	}
}
