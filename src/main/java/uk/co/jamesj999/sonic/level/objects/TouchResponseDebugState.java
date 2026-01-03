package uk.co.jamesj999.sonic.level.objects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TouchResponseDebugState {
    private int playerX;
    private int playerY;
    private int playerHeight;
    private int playerYRadius;
    private boolean crouching;
    private final List<TouchResponseDebugHit> hits = new ArrayList<>();

    void setPlayer(int playerX, int playerY, int playerHeight, int playerYRadius, boolean crouching) {
        this.playerX = playerX;
        this.playerY = playerY;
        this.playerHeight = playerHeight;
        this.playerYRadius = playerYRadius;
        this.crouching = crouching;
    }

    void clear() {
        hits.clear();
    }

    void addHit(TouchResponseDebugHit hit) {
        hits.add(hit);
    }

    public int getPlayerX() {
        return playerX;
    }

    public int getPlayerY() {
        return playerY;
    }

    public int getPlayerHeight() {
        return playerHeight;
    }

    public int getPlayerYRadius() {
        return playerYRadius;
    }

    public boolean isCrouching() {
        return crouching;
    }

    public List<TouchResponseDebugHit> getHits() {
        return Collections.unmodifiableList(hits);
    }
}
