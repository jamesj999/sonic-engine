package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class MonitorObjectInstance extends BoxObjectInstance implements TouchResponseProvider, TouchResponseListener,
        SolidObjectProvider, SolidObjectListener {
    private static final int HALF_RADIUS = 0x0E;
    private static final int CONTENT_RISE_FRAMES = 48;
    private static final int CONTENT_RISE_HEIGHT = 16;
    private static final int CONTENT_HALF_SIZE = 6;
    private static final int RING_MONITOR_REWARD = 10;

    private final MonitorType type;
    private boolean broken;
    private int breakFrame = -1;
    private int lastFrameCounter;

    public MonitorObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, HALF_RADIUS, HALF_RADIUS, 0.4f, 0.9f, 1.0f, false);
        this.type = MonitorType.fromSubtype(spawn.subtype());
        this.broken = this.type == MonitorType.BROKEN;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        this.lastFrameCounter = frameCounter;
    }

    @Override
    public void onTouchResponse(AbstractPlayableSprite player, TouchResponseResult result, int frameCounter) {
        if (broken || player == null) {
            return;
        }
        if (!player.getRolling() && !player.getAir()) {
            return;
        }
        broken = true;
        breakFrame = frameCounter;
        applyMonitorEffect(player);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        int centerX = spawn.x();
        int centerY = spawn.y();

        int left = centerX - getHalfWidth();
        int right = centerX + getHalfWidth();
        int top = centerY - getHalfHeight();
        int bottom = centerY + getHalfHeight();

        float r = broken ? 0.6f : 0.4f;
        float g = broken ? 0.6f : 0.9f;
        float b = broken ? 0.6f : 1.0f;

        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);

        if (!broken) {
            return;
        }

        if (breakFrame < 0) {
            return;
        }

        int elapsed = lastFrameCounter - breakFrame;
        if (elapsed < 0 || elapsed > CONTENT_RISE_FRAMES) {
            return;
        }
        int rise = Math.min(CONTENT_RISE_HEIGHT, elapsed / 2);
        int contentCenterY = centerY - getHalfHeight() - rise;
        int contentLeft = centerX - CONTENT_HALF_SIZE;
        int contentRight = centerX + CONTENT_HALF_SIZE;
        int contentTop = contentCenterY - CONTENT_HALF_SIZE;
        int contentBottom = contentCenterY + CONTENT_HALF_SIZE;

        appendLine(commands, contentLeft, contentTop, contentRight, contentTop, 1.0f, 0.85f, 0.1f);
        appendLine(commands, contentRight, contentTop, contentRight, contentBottom, 1.0f, 0.85f, 0.1f);
        appendLine(commands, contentRight, contentBottom, contentLeft, contentBottom, 1.0f, 0.85f, 0.1f);
        appendLine(commands, contentLeft, contentBottom, contentLeft, contentTop, 1.0f, 0.85f, 0.1f);
    }

    @Override
    protected int getHalfWidth() {
        return HALF_RADIUS;
    }

    @Override
    protected int getHalfHeight() {
        return HALF_RADIUS;
    }

    @Override
    public int getCollisionFlags() {
        return 0x46;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return new SolidObjectParams(0x1A, 0x0F, 0x10);
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Solid contact used for standing/edge checks in ROM; no behavior yet.
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    private void applyMonitorEffect(AbstractPlayableSprite player) {
        switch (type) {
            case RINGS -> player.addRings(RING_MONITOR_REWARD);
            case SHOES, SHIELD, INVINCIBILITY, SONIC, TAILS, EGGMAN, TELEPORT, RANDOM, STATIC, BROKEN -> {
                // TODO: implement remaining monitor effects.
            }
        }
    }

    private enum MonitorType {
        STATIC(0),
        SONIC(1),
        TAILS(2),
        EGGMAN(3),
        RINGS(4),
        SHOES(5),
        SHIELD(6),
        INVINCIBILITY(7),
        TELEPORT(8),
        RANDOM(9),
        BROKEN(10);

        private final int id;

        MonitorType(int id) {
            this.id = id;
        }

        static MonitorType fromSubtype(int subtype) {
            int value = subtype & 0xF;
            for (MonitorType type : values()) {
                if (type.id == value) {
                    return type;
                }
            }
            return STATIC;
        }
    }
}
