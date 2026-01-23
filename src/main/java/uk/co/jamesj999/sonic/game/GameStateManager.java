package uk.co.jamesj999.sonic.game;

/**
 * Manages session-persistent game state such as Score, Lives, and Special Stage progress.
 * <p>
 * Special Stage tracking mirrors ROM variables:
 * - Current_Special_Stage: cycles 0-6, wraps at 7→0, increments on each entry
 * - Emerald_count: number of emeralds collected (0-7)
 * - Got_Emeralds_array: which specific emeralds have been obtained
 */
public class GameStateManager {
    private static final int SPECIAL_STAGE_COUNT = 7;

    private static GameStateManager instance;

    private int score;
    private int lives;

    private int currentSpecialStageIndex;
    private int emeraldCount;
    private final boolean[] gotEmeralds = new boolean[SPECIAL_STAGE_COUNT];

    /**
     * Current boss ID (ROM: Current_Boss_ID).
     * 0 = no boss active, non-zero = boss fight in progress.
     * Used by level boundary logic to remove the +64 right buffer during boss fights.
     */
    private int currentBossId;

    private GameStateManager() {
        resetSession();
    }

    public static synchronized GameStateManager getInstance() {
        if (instance == null) {
            instance = new GameStateManager();
        }
        return instance;
    }

    /**
     * Resets the game session state to defaults (Score: 0, Lives: 3, no emeralds).
     */
    public void resetSession() {
        this.score = 0;
        this.lives = 3;

        this.currentSpecialStageIndex = 0;
        this.emeraldCount = 0;
        for (int i = 0; i < gotEmeralds.length; i++) {
            gotEmeralds[i] = false;
        }

        this.currentBossId = 0;
    }

    public int getScore() {
        return score;
    }

    public void addScore(int amount) {
        if (amount > 0) {
            this.score += amount;
        }
    }

    public int getLives() {
        return lives;
    }

    public void addLife() {
        this.lives++;
    }

    public void loseLife() {
        if (this.lives > 0) {
            this.lives--;
        }
    }

    /**
     * Gets the current special stage index (0-6).
     */
    public int getCurrentSpecialStageIndex() {
        return currentSpecialStageIndex;
    }

    /**
     * ROM behavior: get the current stage index and advance to next.
     * Stage index wraps from 6 back to 0.
     * @return The stage index to use for this entry (before increment)
     */
    public int consumeCurrentSpecialStageIndexAndAdvance() {
        int index = currentSpecialStageIndex;
        currentSpecialStageIndex = (currentSpecialStageIndex + 1) % SPECIAL_STAGE_COUNT;
        return index;
    }

    /**
     * Gets the total number of emeralds collected (0-7).
     */
    public int getEmeraldCount() {
        return emeraldCount;
    }

    /**
     * Checks if a specific emerald has been collected.
     * @param index Emerald index (0-6)
     */
    public boolean hasEmerald(int index) {
        return index >= 0 && index < gotEmeralds.length && gotEmeralds[index];
    }

    /**
     * Marks an emerald as collected.
     * @param index Emerald index (0-6)
     */
    public void markEmeraldCollected(int index) {
        if (index < 0 || index >= gotEmeralds.length) return;
        if (!gotEmeralds[index]) {
            gotEmeralds[index] = true;
            emeraldCount++;
        }
    }

    /**
     * Checks if all 7 emeralds have been collected.
     */
    public boolean hasAllEmeralds() {
        return emeraldCount >= SPECIAL_STAGE_COUNT;
    }

    /**
     * Gets the current boss ID.
     * ROM: Current_Boss_ID - 0 means no boss active.
     */
    public int getCurrentBossId() {
        return currentBossId;
    }

    /**
     * Sets the current boss ID.
     * ROM: Current_Boss_ID - set to non-zero when entering a boss fight,
     * 0 when the boss is defeated. When non-zero, the +64 right boundary
     * buffer is removed to keep the player within the boss arena.
     */
    public void setCurrentBossId(int bossId) {
        this.currentBossId = bossId;
    }

    /**
     * Checks if a boss fight is currently active.
     */
    public boolean isBossFightActive() {
        return currentBossId != 0;
    }
}

