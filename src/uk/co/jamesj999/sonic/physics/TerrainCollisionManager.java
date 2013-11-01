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
		short wallPos = sprite.getWallSensorLine().getX();
		// System.out.println(wallPos);
		if (wallPos == -1) {
			return wallPos;
		} else {
			/**
			 * I anticipate that in the future there will be a better way of
			 * working out which way sonic is facing. Since the graphics will be
			 * implemented, the engine will need to know even if the gSpeed is
			 * 0. Therefore, this will need changing at that point. TODO
			 */
			if (sprite.getLeftX() < wallPos) {
				return (short) (wallPos - 11);
			} else if (sprite.getRightX() > wallPos) {
				return (short) (wallPos + 11);
			} else {
				return -1;
			}
		}
	}

	// private final LevelManager levelManager = LevelManager.getInstance();

	// public short calculateTerrainHeight(Sprite sprite) {
	// if (sprite instanceof AbstractPlayableSprite
	// && ((AbstractPlayableSprite) sprite).getYSpeed() <= 0) {
	// TerrainSensorPair sensors = ((AbstractPlayableSprite) sprite)
	// .getGroundSensors();
	// Sensor left = sensors.getLeft();
	// Sensor right = sensors.getRight();
	// // ((AbstractPlayableSprite) sprite).setYSpeed((short) 0);
	//
	// Tile leftTile = left.getTile();
	// Tile rightTile = right.getTile();
	// short leftHeight = -1;
	// short rightHeight = -1;
	// byte realLeftX = (byte) (left.getX() % 16);
	// byte realRightX = (byte) (right.getX() % 16);
	// if (leftTile != null) {
	// leftHeight = (short) leftTile.getHeightAt(realLeftX);
	// }
	// if (rightTile != null) {
	// rightHeight = (short) rightTile.getHeightAt(realRightX);
	// }
	//
	// if (leftHeight > -1 || rightHeight > -1) {
	// if (((AbstractPlayableSprite) sprite).getAir()) {
	// ((AbstractPlayableSprite) sprite).setRolling(false);
	// }
	// ((AbstractPlayableSprite) sprite).setAir(false);
	// } else {
	// ((AbstractPlayableSprite) sprite).setAir(true);
	// }
	// if (leftHeight > -1 || rightHeight > -1) {
	// if (leftHeight == 16) {
	// Tile leftAbove = left.getTileAbove();
	// if (leftAbove != null
	// && leftAbove.getHeightAt(realLeftX) >= 0) {
	// /*
	// * Ok, so these calculations seem a little weird, so
	// * I'll explain them... We're calculating the 'actual' y
	// * value of the height for this x position of the tile
	// * by getting its remainder from 16 and adding 16 times
	// * the left Y over 16. We multiply/divide the left (or
	// * right) sensor's Y value so that we get it 'floored'
	// * to the nearest 16.
	// */
	// leftHeight = (short) (((leftAbove
	// .getHeightAt(realLeftX)) + 16 * (left.getY() / 16)) + 16);
	// leftTile = leftAbove;
	// } else {
	// leftHeight += 16 * (left.getY() / 16);
	// }
	// // TODO Might need to add handling for lower tile here to
	// // fix annoying 'air while running down a slope' bug.
	// } else {
	// leftHeight += 16 * (left.getY() / 16);
	// }
	// if (rightHeight == 16) {
	// Tile rightAbove = right.getTileAbove();
	// if (rightAbove != null
	// && rightAbove.getHeightAt(realRightX) >= 0) {
	// rightHeight = (short) (((rightAbove
	// .getHeightAt(realRightX)) + 16 * (right.getY() / 16)) + 16);
	// rightTile = rightAbove;
	// } else {
	// rightHeight += 16 * (right.getY() / 16);
	// }
	// } else {
	// rightHeight += 16 * (right.getY() / 16);
	// }
	//
	// left.setY(leftHeight);
	// right.setY(rightHeight);
	//
	// if (leftHeight > rightHeight) {
	// ((AbstractPlayableSprite) sprite).setAngle(leftTile
	// .getAngle());
	// return leftHeight;
	// } else {
	// ((AbstractPlayableSprite) sprite).setAngle(rightTile
	// .getAngle());
	// return rightHeight;
	// }
	// }
	// }
	// return (byte) -1;
	// }

	public synchronized static TerrainCollisionManager getInstance() {
		if (terrainCollisionManager == null) {
			terrainCollisionManager = new TerrainCollisionManager();
		}
		return terrainCollisionManager;
	}
}
