package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import uk.co.jamesj999.sonic.graphics.GLCommand;

public class SpikeObjectInstance extends BoxObjectInstance implements SolidObjectProvider, SolidObjectListener {
    private static final int[] WIDTH_PIXELS = {
            0x10, 0x20, 0x30, 0x40,
            0x10, 0x10, 0x10, 0x10
    };
    private static final int[] Y_RADIUS = {
            0x10, 0x10, 0x10, 0x10,
            0x10, 0x20, 0x30, 0x40
    };

    public SpikeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 8, 1.0f, 0.2f, 0.2f, false);
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
        if (hadRings) {
            LevelManager.getInstance().spawnLostRings(player, frameCounter);
        }
        player.applyHurtOrDeath(spawn.x(), true, hadRings);
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
        renderer.drawFrameIndex(frameIndex, spawn.x(), spawn.y(), hFlip, vFlip);
    }
}
