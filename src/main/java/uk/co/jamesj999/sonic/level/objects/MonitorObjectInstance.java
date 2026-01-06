package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.data.games.Sonic2Constants;

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
                initialFrame);
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
            // Log 10 frames every second to see progression and break aliasing
            return;
        }
        updateIcon();
    }

    @Override
    public void onTouchResponse(AbstractPlayableSprite player, TouchResponseResult result, int frameCounter) {
        if (broken || player == null) {
            return;
        }

        // Hitting from below (Moving Up)
        if (player.getYSpeed() < 0) {
            // Bounce player down, but DO NOT break monitor
            player.setYSpeed((short) -player.getYSpeed());
            return;
        }

        // Hitting from above (Moving Down or Stationary)
        if (!player.getRolling()) {
            return;
        }

        // Break Monitor and Bounce Player Up
        broken = true;
        player.setYSpeed((short) -player.getYSpeed());
        mappingFrame = BROKEN_FRAME;
        iconActive = true;
        iconSubY = spawn.y() << 8;
        iconVelY = ICON_INITIAL_VELOCITY;
        iconWaitFrames = 0;
        effectApplied = false;
        effectTarget = player;

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager != null) {
            LevelManager.getInstance().getObjectManager().addDynamicObject(
                    new ExplosionObjectInstance(0x27, spawn.x(), spawn.y(), renderManager));
        }
        AudioManager.getInstance().playSfx(Sonic2Constants.SFX_EXPLOSION);
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
    public boolean isSolidFor(AbstractPlayableSprite player) {
        if (broken) {
            return false;
        }
        if (player == null) {
            return true;
        }
        return !player.getRolling();
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Solid contact used for standing/edge checks in ROM; no behavior yet.
    }

    private void applyMonitorEffect(AbstractPlayableSprite player) {
        switch (type) {
            case RINGS -> {
                player.addRings(RING_MONITOR_REWARD);
                AudioManager.getInstance().playSfx(GameSound.RING);
            }
            case SHIELD -> {
                player.giveShield();
                AudioManager.getInstance().playSfx(Sonic2Constants.SFX_SHIELD);
            }
            case SHOES -> {
                player.giveSpeedShoes();
                AudioManager.getInstance().playMusic(Sonic2Constants.CMD_SPEED_UP);
            }
            case INVINCIBILITY -> {
                player.giveInvincibility();
                AudioManager.getInstance().playMusic(Sonic2Constants.MUS_INVINCIBILITY);
            }
            case SONIC, TAILS -> {
                AudioManager.getInstance().playMusic(Sonic2Constants.MUS_EXTRA_LIFE);
                // TODO: Increment lives
            }
            default -> {
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
