package uk.co.jamesj999.sonic.level.rings;

public class LostRing {
    private static final int COLLECTION_DELAY_FRAMES = 64;
    private int xSubpixel;
    private int ySubpixel;
    private int xVel;
    private int yVel;
    private int lifetime;
    private boolean collected;
    private int sparkleStartFrame = -1;
    private final int id;
    private final int spawnFrame;

    public LostRing(int id, int x, int y, int xVel, int yVel, int lifetime, int spawnFrame) {
        this.id = id;
        this.xSubpixel = x << 8;
        this.ySubpixel = y << 8;
        this.xVel = xVel;
        this.yVel = yVel;
        this.lifetime = lifetime;
        this.spawnFrame = spawnFrame;
    }

    /**
     * Returns true if enough frames have passed since spawning for the ring to be
     * collected.
     * This prevents immediate recollection after being hurt.
     */
    public boolean canBeCollected(int currentFrame) {
        return (currentFrame - spawnFrame) >= COLLECTION_DELAY_FRAMES;
    }

    public int getId() {
        return id;
    }

    public int getX() {
        return xSubpixel >> 8;
    }

    public int getY() {
        return ySubpixel >> 8;
    }

    public int getXSubpixel() {
        return xSubpixel;
    }

    public int getYSubpixel() {
        return ySubpixel;
    }

    public int getXVel() {
        return xVel;
    }

    public int getYVel() {
        return yVel;
    }

    public void setXVel(int xVel) {
        this.xVel = xVel;
    }

    public void setYVel(int yVel) {
        this.yVel = yVel;
    }

    public void addXSubpixel(int delta) {
        xSubpixel += delta;
    }

    public void addYSubpixel(int delta) {
        ySubpixel += delta;
    }

    public void addYVel(int delta) {
        yVel += delta;
    }

    public int getLifetime() {
        return lifetime;
    }

    public void decLifetime() {
        lifetime--;
    }

    public boolean isCollected() {
        return collected;
    }

    public void markCollected(int frameCounter) {
        collected = true;
        sparkleStartFrame = frameCounter;
    }

    public int getSparkleStartFrame() {
        return sparkleStartFrame;
    }
}
