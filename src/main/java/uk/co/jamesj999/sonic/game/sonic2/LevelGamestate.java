package uk.co.jamesj999.sonic.game.sonic2;

/**
 * Manages transient state for a single level execution, such as Rings and Time.
 * Typically reset when a level is loaded or restarted (except checkpoints?).
 * Rings are always reset on level load/respawn (unless specialized checkout
 * logic exists, but normally 0).
 */
public class LevelGamestate {
    private final LevelTimer timer;
    private int rings;

    public LevelGamestate() {
        this.timer = new LevelTimer();
        this.rings = 0;
    }

    public void update() {
        timer.update();
    }

    public LevelTimer getTimer() {
        return timer;
    }

    public int getRings() {
        return rings;
    }

    public void setRings(int rings) {
        this.rings = Math.max(0, rings);
    }

    public void addRings(int amount) {
        if (amount != 0) {
            int previousRings = rings;
            int next = rings + amount;
            this.rings = Math.max(0, next);

            // Ring Bonus Logic: 100 and 200 rings grant an extra life
            if (amount > 0) {
                if (previousRings < 100 && rings >= 100) {
                    uk.co.jamesj999.sonic.game.GameStateManager.getInstance().addLife();
                    uk.co.jamesj999.sonic.audio.AudioManager.getInstance().playMusic(
                            uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants.MUS_EXTRA_LIFE);
                } else if (previousRings < 200 && rings >= 200) {
                    uk.co.jamesj999.sonic.game.GameStateManager.getInstance().addLife();
                    uk.co.jamesj999.sonic.audio.AudioManager.getInstance().playMusic(
                            uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants.MUS_EXTRA_LIFE);
                }
            }
        }
    }
}
