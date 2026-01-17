package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class PointsObjectInstance extends uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance {
    private final PatternSpriteRenderer renderer;
    private final LevelManager levelManager;
    private int currentX;
    private int currentY;
    private int scoreFrame;
    private int timer;

    public PointsObjectInstance(ObjectSpawn spawn, LevelManager levelManager, int points) {
        super(spawn, "Points");
        this.levelManager = levelManager;
        this.renderer = levelManager.getObjectRenderManager().getPointsRenderer();
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.timer = 60; // 1 second
        setScore(points);
    }

    public void setScore(int points) {
        switch (points) {
            case 10:
                this.scoreFrame = 4;
                break;
            case 100:
                this.scoreFrame = 0;
                break;
            case 200:
                this.scoreFrame = 1;
                break;
            case 500:
                this.scoreFrame = 2;
                break;
            case 1000:
                this.scoreFrame = 3;
                break;
            default:
                this.scoreFrame = 0; // Default 100
                break;
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (timer % 2 == 0) {
            currentY--; // Rise 1px every 2 frames
        }

        timer--;
        if (timer <= 0) {
            setDestroyed(true);
            return;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed())
            return;
        renderer.drawFrameIndex(scoreFrame, currentX, currentY, false, false);
    }

    public int getX() {
        return currentX;
    }

    public int getY() {
        return currentY;
    }
}
