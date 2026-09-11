package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sonic 1 Object 0x65 - Waterfalls (LZ).
 * <p>
 * ROM reference: docs/s1disasm/_incObj/65 Waterfalls.asm
 */
public class Sonic1WaterfallObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    private static final int ROUTINE_ANIMATE = 2;
    private static final int ROUTINE_CHECK_DELETE = 4;
    private static final int ROUTINE_ON_WATER = 6;
    private static final int ROUTINE_SPECIAL = 8;

    private static final int SPLASH_FRAME_START = 9;
    private static final int SPLASH_FRAME_END = 11;
    private static final int SPLASH_FRAME_DURATION = 5;

    // Layout gap position checked by ROUTINE_SPECIAL (subtype $A9).
    // ROM: cmpi.b #7,(v_lvllayout+$80*2+6).w
    // When chunk == 7, the gap is open and splash renders with high priority.
    private static final int LAYOUT_GAP_X = 6;
    private static final int LAYOUT_GAP_Y = 2;
    private static final int CHUNK_ID_GAP = 7;

    private int routine;
    private int x;
    private int y;
    private int mappingFrame;
    private int animTimer;

    private boolean highPriority;
    private int priorityBucket;

    public Sonic1WaterfallObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Waterfall");
        this.x = spawn.x();
        this.y = spawn.y();
        this.mappingFrame = 0;
        this.animTimer = SPLASH_FRAME_DURATION;
        this.highPriority = false;
        this.priorityBucket = 1;

        initFromSubtype();
    }

    private void initFromSubtype() {
        int subtype = spawn.subtype() & 0xFF;

        // bpl.s .under80 / bset #7,obGfx(a0)
        highPriority = (subtype & 0x80) != 0;

        mappingFrame = subtype & 0x0F;
        routine = ROUTINE_CHECK_DELETE;

        if (mappingFrame == SPLASH_FRAME_START) {
            // Splash pieces render in front.
            priorityBucket = RenderPriority.MIN;
            routine = ROUTINE_ANIMATE;

            // $49 subtype follows water height.
            if ((subtype & 0x40) != 0) {
                routine = ROUTINE_ON_WATER;
            }
            // $A9 subtype has extra gfx bit behavior tied to layout.
            if ((subtype & 0x20) != 0) {
                routine = ROUTINE_SPECIAL;
            }
        }
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(x, y);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (routine) {
            case ROUTINE_ON_WATER -> {
                y = getWaterLevel() - 0x10;
                animateSplash();
            }
            case ROUTINE_SPECIAL -> {
                // ROM (loc_12B36): Toggle obGfx bit 7 based on layout chunk.
                // bclr #7,obGfx(a0)
                // cmpi.b #7,(v_lvllayout+$80*2+6).w
                // bne.s .animate
                // bset #7,obGfx(a0)
                highPriority = isLayoutGapOpen();
                animateSplash();
            }
            case ROUTINE_ANIMATE -> animateSplash();
            case ROUTINE_CHECK_DELETE -> {
                // RememberState persistence handled by object manager.
            }
            default -> {
            }
        }
    }

    private void animateSplash() {
        if (mappingFrame < SPLASH_FRAME_START || mappingFrame > SPLASH_FRAME_END) {
            mappingFrame = SPLASH_FRAME_START;
        }

        animTimer--;
        if (animTimer >= 0) {
            return;
        }

        animTimer = SPLASH_FRAME_DURATION;
        mappingFrame++;
        if (mappingFrame > SPLASH_FRAME_END) {
            mappingFrame = SPLASH_FRAME_START;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // For ROUTINE_SPECIAL: only render when the gap is open (highPriority set).
        // Before the button is pressed, the splash is hidden behind the wall;
        // we skip rendering entirely since the engine can't replicate VDP
        // priority layering that would hide it behind high-priority BG tiles.
        if (routine == ROUTINE_SPECIAL && !highPriority) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.LZ_WATERFALL);
        if (renderer == null) return;

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y, hFlip, vFlip);
    }

    @Override
    public boolean isHighPriority() {
        return highPriority;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(priorityBucket);
    }

    @Override
    public boolean isPersistent() {
        // ROM: every WFall routine ends at RememberState, whose out_of_range macro
        // (Macros.asm) deletes the object once its chunk-aligned X leaves the
        // [camera-128, camera-128 + 0x280] window (docs/s1disasm/_incObj/65 LZ
        // Waterfalls.asm:52 -> sub RememberState.asm:9). isInRangeAt(getX()) is that
        // exact macro; the prior symmetric isOnScreenX(192) kept the object alive
        // ~64px too far to the left.
        return !isDestroyed() && isInRangeAt(getX());
    }

    /**
     * Check if the layout gap at (v_lvllayout+$80*2+6) is chunk 7 (open).
     * ROM: cmpi.b #7,(v_lvllayout+$80*2+6).w
     */
    private boolean isLayoutGapOpen() {
        Level level = services().currentLevel();
        if (level == null) {
            return false;
        }
        Map map = level.getMap();
        if (map == null) {
            return false;
        }
        return (map.getValue(0, LAYOUT_GAP_X, LAYOUT_GAP_Y) & 0xFF) == CHUNK_ID_GAP;
    }

    private int getWaterLevel() {
        if (services().currentLevel() == null) {
            return y;
        }
        var waterSystem = services().waterSystem();
        return waterSystem.getVisualWaterLevelY(services().featureZoneId(), services().featureActId());
    }
}
