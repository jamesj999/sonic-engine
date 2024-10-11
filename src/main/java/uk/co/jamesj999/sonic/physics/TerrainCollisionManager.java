package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class TerrainCollisionManager {
	private static TerrainCollisionManager terrainCollisionManager;

	// In the future, we may need to expand this method to work with AbstractSprite too for NPCs.
	public SensorResult[] getSensorResult(Sensor[] sensors) {
		SensorResult[] results = new SensorResult[sensors.length];

		for (int i = 0; i < sensors.length; i++) {
			results[i] = sensors[i].scan();
		}

		return results;
	}
		// I know, I thought it was closest to 0 too, but apparently not
//		SensorResult lowestResult = null;
//		for (Sensor sensor : sprite.getGroundSensors()) {
//			SensorResult result = sensor.scan();
//			if (result != null) {
//				byte distance = result.distance();
//				if (lowestResult == null || distance < lowestResult.distance()) {
//					lowestResult = result;
//				}
//			}
//		}
//		return lowestResult;
		//}
//		if(lowestResult != null && lowestResult.distance() < 16) {
//			short newX = sprite.getX();
//			short newY = sprite.getY();
//
//			switch (sprite.getGroundMode()) {
//				case GROUND -> {
//					newY += lowestResult.distance();
//				}
//				case RIGHTWALL -> {
//					newX += lowestResult.distance();
//				}
//				case CEILING -> {
//					newY -= lowestResult.distance();
//				}
//				case LEFTWALL -> {
//					newX -= lowestResult.distance();
//				}
//			}
//			if (sprite.getAir()) {
//				sprite.setAir(false);
//			}
//			sprite.setX(newX);
//			sprite.setY(newY);
//			sprite.setAngle(lowestResult.angle());
//
//
//		} else {
//			if(!sprite.getAir()) {
//				sprite.setAir(true);
//			}
//		}
//	}

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
