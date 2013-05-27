package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class TerrainCollisionManager {
	private static TerrainCollisionManager terrainCollisionManager;

	public byte calculateAngle(Sprite sprite) {
		if(sprite instanceof AbstractPlayableSprite) {
			TerrainSensorPair sensors = ((AbstractPlayableSprite) sprite).getGroundSensors();
		}
		return (byte) 0; //TODO finish
	}
	public synchronized static TerrainCollisionManager getInstance() {
		if (terrainCollisionManager == null) {
			terrainCollisionManager = new TerrainCollisionManager();
		}
		return terrainCollisionManager;
	}
}
