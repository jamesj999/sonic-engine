package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.Tile;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class TerrainCollisionManager {
	private static TerrainCollisionManager terrainCollisionManager;

	// private final LevelManager levelManager = LevelManager.getInstance();

	public short calculateTerrainHeight(Sprite sprite) {
		if (sprite instanceof AbstractPlayableSprite
				&& ((AbstractPlayableSprite) sprite).getYSpeed() <= 0) {
			TerrainSensorPair sensors = ((AbstractPlayableSprite) sprite)
					.getGroundSensors();
			Sensor left = sensors.getLeft();
			Sensor right = sensors.getRight();
			// ((AbstractPlayableSprite) sprite).setYSpeed((short) 0);

			Tile leftTile = left.getTile();
			Tile rightTile = right.getTile();
			short leftHeight = -1;
			short rightHeight = -1;
			if (leftTile != null) {
				/*
				 * Ok, so these calculations seem a little weird, so I'll
				 * explain them... We're calculating the 'actual' y value of the
				 * height for this x position of the tile by getting its
				 * remainder from 16 and adding 16 times the left Y over 16. We
				 * multiply/divide the left (or right) sensor's Y value so that
				 * we get it 'floored' to the nearest 16.
				 */
				leftHeight = (short) ((leftTile
						.getHeightAt((byte) (left.getX() % 16))) + 16 * (left
						.getY() / 16));
			}
			if (rightTile != null) {
				rightHeight = (short) ((rightTile.getHeightAt((byte) (right
						.getX() % 16))) + 16 * (right.getY() / 16));
			}

			if (leftHeight > -1 || rightHeight > -1) {
				((AbstractPlayableSprite) sprite).setAir(false);
			} else {
				((AbstractPlayableSprite) sprite).setAir(true);
			}
			if (leftHeight > -1 || rightHeight > -1) {
				if (leftHeight == 16) {
					Tile leftAbove = left.getTileAbove();
					if (leftAbove != null) {
						leftHeight = (short) ((leftAbove
								.getHeightAt((byte) ((left.getX() + 16) % 16))) + 16 * (left
								.getY() / 16));
					}
				}
				if (rightHeight == 16) {
					Tile rightAbove = right.getTileAbove();
					if (rightAbove != null) {
						rightHeight = (short) ((rightAbove
								.getHeightAt((byte) ((right.getX() + 16) % 16))) + 16 * (left
								.getY() / 16));
					}
				}
				if (leftHeight > rightHeight) {
					((AbstractPlayableSprite) sprite).setAngle(leftTile
							.getAngle());
					return leftHeight;
				} else {
					((AbstractPlayableSprite) sprite).setAngle(rightTile
							.getAngle());
					return rightHeight;
				}
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
