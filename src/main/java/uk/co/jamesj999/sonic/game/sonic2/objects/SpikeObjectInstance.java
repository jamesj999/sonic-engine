package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.SolidContact;
import uk.co.jamesj999.sonic.level.objects.SolidObjectListener;
import uk.co.jamesj999.sonic.level.objects.SolidObjectParams;
import uk.co.jamesj999.sonic.level.objects.SolidObjectProvider;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class SpikeObjectInstance extends BoxObjectInstance implements SolidObjectProvider, SolidObjectListener {
    private static final int[] WIDTH_PIXELS = {
            0x10, 0x20, 0x30, 0x40,
            0x10, 0x10, 0x10, 0x10
    };
    private static final int[] Y_RADIUS = {
            0x10, 0x10, 0x10, 0x10,
            0x10, 0x20, 0x30, 0x40
    };
    private static final int SPIKE_RETRACT_STEP = 0x800;
    private static final int SPIKE_RETRACT_MAX = 0x2000;
    private static final int SPIKE_RETRACT_DELAY = 60;

    private final int baseX;
    private final int baseY;
    private int currentX;
    private int currentY;
    private int retractOffset;
    private int retractState;
    private int retractTimer;
    private ObjectSpawn dynamicSpawn;

    public SpikeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 8, 1.0f, 0.2f, 0.2f, false);
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.currentX = baseX;
        this.currentY = baseY;
        this.dynamicSpawn = spawn;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null) {
            return;
        }
        if (!shouldHurt(contact)) {
            return;
        }
        // Check invulnerability BEFORE doing anything - allows walking on spikes when
        // invulnerable
        if (player.getInvulnerable()) {
            return;
        }
        boolean hadRings = player.getRingCount() > 0;
        if (hadRings && !player.hasShield()) {
            LevelManager.getInstance().spawnLostRings(player, frameCounter);
        }
        player.applyHurtOrDeath(currentX, true, hadRings);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        moveSpikes();
        updateDynamicSpawn();
    }

    @Override
    protected int getHalfWidth() {
        return getEntryValue(WIDTH_PIXELS);
    }

    @Override
    protected int getHalfHeight() {
        return getEntryValue(Y_RADIUS);
    }

    private int getEntryValue(int[] table) {
        int entry = (spawn.subtype() >> 4) & 0xF;
        if (entry < 0) {
            entry = 0;
        }
        if (entry >= table.length) {
            entry = table.length - 1;
        }
        return table[entry];
    }

    @Override
    public SolidObjectParams getSolidParams() {
        int widthPixels = getEntryValue(WIDTH_PIXELS);
        int yRadius = getEntryValue(Y_RADIUS);
        int d1 = widthPixels + 0x0B;
        int d2 = yRadius;
        int d3 = yRadius + 1;
        return new SolidObjectParams(d1, d2, d3);
    }

    private boolean shouldHurt(SolidContact contact) {
        if (isSideways()) {
            return contact.touchSide();
        }
        if (isUpsideDown()) {
            return contact.touchBottom();
        }
        return contact.standing();
    }

    private boolean isSideways() {
        int entry = (spawn.subtype() >> 4) & 0xF;
        return entry >= 4;
    }

    private boolean isUpsideDown() {
        return (spawn.renderFlags() & 0x2) != 0;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return dynamicSpawn;
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        int frameIndex = (spawn.subtype() >> 4) & 0xF;
        if (frameIndex < 0) {
            frameIndex = 0;
        }
        if (frameIndex > 7) {
            frameIndex = 7;
        }
        boolean sideways = frameIndex >= 4;
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        PatternSpriteRenderer renderer = renderManager.getSpikeRenderer(sideways);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        renderer.drawFrameIndex(frameIndex, currentX, currentY, hFlip, vFlip);
    }

    private void moveSpikes() {
        int behavior = spawn.subtype() & 0xF;
        switch (behavior) {
            case 1 -> moveSpikesVertical();
            case 2 -> moveSpikesHorizontal();
            default -> {
                currentX = baseX;
                currentY = baseY;
            }
        }
    }

    private void moveSpikesVertical() {
        moveSpikesDelay();
        int offsetPixels = retractOffset >> 8;
        currentX = baseX;
        currentY = baseY + offsetPixels;
    }

    private void moveSpikesHorizontal() {
        moveSpikesDelay();
        int offsetPixels = retractOffset >> 8;
        currentX = baseX + offsetPixels;
        currentY = baseY;
    }

    private void moveSpikesDelay() {
        if (retractTimer > 0) {
            retractTimer--;
            if (retractTimer == 0) {
                playSpikeMoveSfx();
            }
            return;
        }

        if (retractState != 0) {
            retractOffset -= SPIKE_RETRACT_STEP;
            if (retractOffset < 0) {
                retractOffset = 0;
                retractState = 0;
                retractTimer = SPIKE_RETRACT_DELAY;
            }
            return;
        }

        retractOffset += SPIKE_RETRACT_STEP;
        if (retractOffset >= SPIKE_RETRACT_MAX) {
            retractOffset = SPIKE_RETRACT_MAX;
            retractState = 1;
            retractTimer = SPIKE_RETRACT_DELAY;
        }
    }

    private void updateDynamicSpawn() {
        if (dynamicSpawn.x() == currentX && dynamicSpawn.y() == currentY) {
            return;
        }
        dynamicSpawn = new ObjectSpawn(
                currentX,
                currentY,
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                spawn.respawnTracked(),
                spawn.rawYWord());
    }

    private void playSpikeMoveSfx() {
        if (!isOnScreen()) {
            return;
        }
        try {
            AudioManager.getInstance().playSfx(Sonic2AudioConstants.SFX_SPIKES_MOVE);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic.
        }
    }

    private boolean isOnScreen() {
        Camera camera = Camera.getInstance();
        int left = camera.getX();
        int top = camera.getY();
        int right = left + camera.getWidth();
        int bottom = top + camera.getHeight();
        return currentX >= left && currentX <= right && currentY >= top && currentY <= bottom;
    }
}
