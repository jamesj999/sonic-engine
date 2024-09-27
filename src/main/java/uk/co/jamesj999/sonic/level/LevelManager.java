package uk.co.jamesj999.sonic.level;

public class LevelManager {
	private static LevelManager levelManager;
	private OldLevel level;

	public void setLevel(OldLevel level) {
		this.level = level;
	}

	public OldLevel getLevel() {
		return level;
	}

	public synchronized static LevelManager getInstance() {
		if (levelManager == null) {
			levelManager = new LevelManager();
		}
		return levelManager;
	}
}
