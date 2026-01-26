package uk.co.jamesj999.sonic.physics;

/**
 * Aggregates sensor results for terrain collision detection.
 * Delegates individual scans to Sensor implementations.
 */
public class TerrainCollisionManager {
	private static TerrainCollisionManager instance;

	/**
	 * Execute all sensors and return their results.
	 *
	 * @param sensors Array of sensors to scan
	 * @return Array of results (parallel to input array)
	 */
	public SensorResult[] getSensorResult(Sensor[] sensors) {
		SensorResult[] results = new SensorResult[sensors.length];
		for (int i = 0; i < sensors.length; i++) {
			results[i] = sensors[i].scan();
		}
		return results;
	}

	public static synchronized TerrainCollisionManager getInstance() {
		if (instance == null) {
			instance = new TerrainCollisionManager();
		}
		return instance;
	}
}
