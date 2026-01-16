package uk.co.jamesj999.sonic.game.sonic2;

/**
 * Tracks the time spent in the current level.
 * Handles frame counting, time string formatting, and Time Over logic.
 */
public class LevelTimer {
    private static final int MAX_MINUTES = 10;
    private static final int FLASH_THRESHOLD_MINUTES = 9;

    // 60 frames per second
    private long totalFrames;
    private boolean paused;

    public LevelTimer() {
        reset();
    }

    public void reset() {
        totalFrames = 0;
        paused = false;
    }

    /**
     * Pauses the timer. Time will not increment until a new level is loaded.
     * This is called when Sonic passes the end-of-stage signpost.
     */
    public void pause() {
        paused = true;
    }

    public boolean isPaused() {
        return paused;
    }

    public void update() {
        if (paused) {
            return;
        }
        // Technically we can keep counting beyond 10 mins if needed,
        // but the display logic clamps it.
        totalFrames++;
    }

    public int getMinutes() {
        return (int) (totalFrames / 3600); // 60 fps * 60 seconds
    }

    public int getSeconds() {
        return (int) ((totalFrames / 60) % 60);
    }

    public int getCentiseconds() {
        // Sonic 2 uses actual frames for the "centiseconds" part in debug/time attack
        // usually,
        // but standard HUD doesn't show centiseconds.
        // If we needed it: (totalFrames % 60) * 100 / 60
        return (int) ((totalFrames % 60) * 100 / 60);
    }

    public boolean isTimeOver() {
        return getMinutes() >= MAX_MINUTES;
    }

    /**
     * returns formatted time string "M:SS".
     * If time is over 9:59, it stays at 9:59 for display purposes until reset
     * (though actual logic triggers death at 10:00).
     */
    public String getDisplayTime() {
        int minutes = getMinutes();
        int seconds = getSeconds();

        if (minutes >= MAX_MINUTES) {
            minutes = 9;
            seconds = 59;
        }

        return String.format("%d:%02d", minutes, seconds);
    }

    /**
     * Determines if the time display should flash red/yellow.
     * Logic: Flash if minutes == 9.
     * Flash rate: Usually toggles every 16 frames or so (approx 4Hz blink).
     * 
     * @return true if the red text should be shown (or alternate color)
     */
    public boolean shouldFlash() {
        if (getMinutes() < FLASH_THRESHOLD_MINUTES) {
            return false;
        }
        return getFlashCycle();
    }

    public boolean getFlashCycle() {
        // Simple blink: ON for 8 frames, OFF for 8 frames (faster blink)
        return (totalFrames / 8) % 2 == 0;
    }
}
