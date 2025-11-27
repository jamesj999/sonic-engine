package uk.co.jamesj999.sonic.data;

import uk.co.jamesj999.sonic.level.Level;

import java.io.IOException;
import java.util.List;

public abstract class Game {

    public abstract boolean isCompatible();

    public abstract String getIdentifier();

    public abstract List<String> getTitleCards();

    public abstract Level loadLevel(int levelIdx) throws IOException;

    public abstract int getMusicId(int levelIdx) throws IOException;

    public abstract boolean canRelocateLevels();

    public abstract boolean canSave();

    public abstract boolean relocateLevels(boolean unsafe) throws IOException;

    public abstract boolean save(int levelIdx, Level level);
}
