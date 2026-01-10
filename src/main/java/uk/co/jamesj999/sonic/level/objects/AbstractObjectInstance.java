package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public abstract class AbstractObjectInstance implements ObjectInstance {
    protected final ObjectSpawn spawn;
    protected final String name;
    private boolean destroyed;

    protected AbstractObjectInstance(ObjectSpawn spawn, String name) {
        this.spawn = spawn;
        this.name = name;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return spawn;
    }

    public String getName() {
        return name;
    }

    protected void setDestroyed(boolean destroyed) {
        this.destroyed = destroyed;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Default no-op.
    }

    @Override
    public abstract void appendRenderCommands(List<GLCommand> commands);
}
