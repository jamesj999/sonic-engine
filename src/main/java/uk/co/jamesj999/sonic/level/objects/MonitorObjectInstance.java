package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class MonitorObjectInstance extends BoxObjectInstance implements TouchResponseProvider, TouchResponseListener,
        SolidObjectProvider, SolidObjectListener {
    private static final int HALF_RADIUS = 0x0E;
    private static final int ICON_INITIAL_VELOCITY = -0x300;
    private static final int ICON_RISE_ACCEL = 0x18;
    private static final int ICON_WAIT_FRAMES = 0x1D;
    private static final int BROKEN_FRAME = 0x0B;
    private static final int ICON_FRAME_OFFSET = 1;
    private static final int RING_MONITOR_REWARD = 10;

    private final MonitorType type;
    private final ObjectAnimationState animationState;
    private boolean broken;
    private int mappingFrame;
    private boolean iconActive;
    private int iconSubY;
    private int iconVelY;
    private int iconWaitFrames;
    private boolean effectApplied;
    private AbstractPlayableSprite effectTarget;

    public MonitorObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, HALF_RADIUS, HALF_RADIUS, 0.4f, 0.9f, 1.0f, false);
        this.type = MonitorType.fromSubtype(spawn.subtype());
        this.broken = this.type == MonitorType.BROKEN;
        int initialAnim = type.id;
        int initialFrame = broken ? BROKEN_FRAME : 0;
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getMonitorAnimations() : null,
                initialAnim,
                initialFrame
        );
        this.mappingFrame = initialFrame;
        if (broken) {
            effectApplied = true;
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (!broken) {
            animationState.update();
            mappingFrame = animationState.getMappingFrame();
            return;
        }
        updateIcon();
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
        mappingFrame = BROKEN_FRAME;
        iconActive = true;
        iconSubY = spawn.y() << 8;
        iconVelY = ICON_INITIAL_VELOCITY;
        iconWaitFrames = 0;
        effectApplied = false;
        effectTarget = player;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getMonitorRenderer();
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        int frameIndex = broken ? BROKEN_FRAME : mappingFrame;
        renderer.drawFrameIndex(frameIndex, spawn.x(), spawn.y(), false, false);

        if (iconActive) {
            int iconFrame = resolveIconFrame();
            ObjectSpriteSheet sheet = renderManager.getMonitorSheet();
            if (iconFrame >= 0 && sheet != null && iconFrame < sheet.getFrameCount()) {
                SpriteMappingFrame mappingFrame = sheet.getFrame(iconFrame);
                if (mappingFrame != null && !mappingFrame.pieces().isEmpty()) {
                    SpriteMappingPiece iconPiece = mappingFrame.pieces().get(0);
                    renderer.drawPieces(List.of(iconPiece), spawn.x(), iconSubY >> 8, false, false);
                }
            }
        }
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

    private void applyMonitorEffect(AbstractPlayableSprite player) {
        switch (type) {
            case RINGS -> player.addRings(RING_MONITOR_REWARD);
            case SHOES, SHIELD, INVINCIBILITY, SONIC, TAILS, EGGMAN, TELEPORT, RANDOM, STATIC, BROKEN -> {
                // TODO: implement remaining monitor effects.
            }
        }
    }

    private void updateIcon() {
        if (!iconActive) {
            return;
        }
        if (iconVelY < 0) {
            iconSubY += iconVelY;
            iconVelY += ICON_RISE_ACCEL;
            if (iconVelY >= 0) {
                iconVelY = 0;
                iconWaitFrames = ICON_WAIT_FRAMES;
                if (!effectApplied && effectTarget != null) {
                    applyMonitorEffect(effectTarget);
                    effectApplied = true;
                    effectTarget = null;
                }
            }
            return;
        }

        if (iconWaitFrames > 0) {
            iconWaitFrames--;
            return;
        }
        iconActive = false;
    }

    private int resolveIconFrame() {
        if (type == MonitorType.BROKEN) {
            return -1;
        }
        return type.id + ICON_FRAME_OFFSET;
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
