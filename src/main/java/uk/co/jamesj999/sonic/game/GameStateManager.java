package uk.co.jamesj999.sonic.game;

/**
 * Manages session-persistent game state such as Score and Lives.
 */
public class GameStateManager {
    private static GameStateManager instance;

    private int score;
    private int lives;

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
     * Resets the game session state to defaults (Score: 0, Lives: 3).
     */
    public void resetSession() {
        this.score = 0;
        this.lives = 3;
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

    public void addLive() {
        this.lives++;
    }

    public void loseLife() {
        if (this.lives > 0) {
            this.lives--;
        }
    }
}
