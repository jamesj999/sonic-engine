package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public interface ObjectInstance {
    ObjectSpawn getSpawn();

    default int getX() {
        return getSpawn().x();
    }

    default int getY() {
        return getSpawn().y();
    }

    void update(int frameCounter, AbstractPlayableSprite player);

    void appendRenderCommands(List<GLCommand> commands);

    boolean isHighPriority();

    boolean isDestroyed();
}
