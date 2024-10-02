package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.games.Sonic2;

import java.io.IOException;

public class LevelManager {
	private static LevelManager levelManager;
	private Level level;
	private Rom rom;

	public void loadLevel(int levelIndex) throws IOException {
		rom.open(SonicConfigurationService.getInstance().getString(SonicConfiguration.ROM_FILENAME));
		Game game = new Sonic2(rom);
		level = game.loadLevel(levelIndex);
	}

	public void draw() {
		// TODO
	}

	public Level getCurrentLevel() {
		return level;
	}

	public synchronized static LevelManager getInstance() {
		if (levelManager == null) {
			levelManager = new LevelManager();
		}
		return levelManager;
	}
}
