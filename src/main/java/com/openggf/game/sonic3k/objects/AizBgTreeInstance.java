package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ZeroScalarArgsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * AIZ2 parallax background tree displayed during the post-bombing transition.
 *
 * <p>ROM: Obj_AIZ2BGTree (sonic3k.asm).
 * A tall tree sprite that scrolls at 3/4 parallax speed, providing visual
 * depth between the bombing area and the boss arena.
 *
 * <p>Each tree is spawned by {@link AizBgTreeSpawnerInstance} with a baseline
 * smooth-scroll X value. Each frame the tree computes:
 * {@code screenX = INITIAL_X_OFFSET - (scrollDelta * 3/4)}
 * where scrollDelta is the current smooth scroll X minus the baseline at spawn.
 *
 * <p>The tree is fixed at screen Y = $69 (VDP $E9 - $80 = 105 pixels from
 * camera top) and deletes itself when the camera passes $4880.
 */
public class AizBgTreeInstance extends AbstractObjectInstance
        implements ZeroScalarArgsRewindRecreatable {

    /** Initial screen-relative X offset. ROM VDP X $1C0 → screen $1C0-$80 = $140 (right edge). */
    private static final int INITIAL_X_OFFSET = 0x1C0 - 0x80; // 320 = right edge of screen

    /** Fixed screen Y position. ROM: VDP Y $E9 -> screen $E9 - $80 = $69. */
    private static final int SCREEN_Y = 0xE9 - 0x80; // 105 pixels from top

    /** Camera X threshold to delete tree. ROM: cmpi.w #$4880,(Camera_X_pos).w. */
    private static final int DELETE_CAMERA_X = 0x4880;

    /** Smooth scroll X value at spawn time (baseline for parallax delta).
     *  Non-final so the generic rewind field capturer reapplies it after a
     *  generic rewind recreate supplies a placeholder. */
    private int spawnSmoothScrollX;

    /** Current screen-relative X position, updated each frame. */
    private int screenX;

    /**
     * @param spawnSmoothScrollX the battleship smooth scroll X at the moment
     *                           this tree was spawned (baseline for parallax)
     */
    public AizBgTreeInstance(int spawnSmoothScrollX) {
        super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "AIZ2BGTree");
        this.spawnSmoothScrollX = spawnSmoothScrollX;
        this.screenX = INITIAL_X_OFFSET; // starts hidden (>= INITIAL_X_OFFSET)
    }

    AizBgTreeInstance(ObjectSpawn spawn) {
        this(0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) return;

        // ROM: Obj_AIZ2BGTreeMove — delete only when camera passes the boss area.
        // Trees persist after auto-scroll ends; the AIZ runtime state's
        // battleshipSmoothScrollX continues to accumulate camera deltas, so
        // trees scroll off naturally.
        if (services().camera().getX() >= DELETE_CAMERA_X) {
            setDestroyed(true);
            return;
        }
        AizZoneRuntimeState state = currentAizState();
        int currentSmooth = state != null ? state.getBattleshipSmoothScrollX() : 0;
        int scrollDelta = currentSmooth - spawnSmoothScrollX;

        // 3/4 parallax: scrollDelta - (scrollDelta >> 2)
        int parallaxDelta = scrollDelta - (scrollDelta >> 2);
        screenX = INITIAL_X_OFFSET - parallaxDelta;
    }

    /**
     * Returns world X = camera X + screen-relative X.
     */
    @Override
    public int getX() {
        try {
            return services().camera().getX() + screenX;
        } catch (Exception e) {
            return screenX;
        }
    }

    /**
     * Returns world Y = camera Y + fixed screen offset.
     */
    @Override
    public int getY() {
        try {
            return services().camera().getY() + SCREEN_Y;
        } catch (Exception e) {
            return SCREEN_Y;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;
        // Tree is off-screen right until it scrolls into view
        if (screenX >= INITIAL_X_OFFSET) return;

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.AIZ2_BG_TREE);
        if (renderer == null) return;

        renderer.drawFrameIndex(0, getX(), getY(), false, false);
    }

    /**
     * Trees render BEHIND the foreground level tiles (seen through gaps in the forest).
     * On the Mega Drive, high-priority BG tiles render in front of high-priority sprites,
     * so the forest FG tiles cover these tree sprites. In the engine, isHighPriority=false
     * places sprites behind the FG tilemap layer.
     */
    @Override
    public int getPriorityBucket() { return 3; }

    @Override
    public boolean isHighPriority() { return false; }

    /**
     * ROM Obj_AIZ2BGTree has an explicit camera delete check and otherwise only
     * calls Draw_Sprite. Keep the engine's generic object-window cull from
     * retiring trees before the ROM's Camera_X_pos >= $4880 predicate.
     */
    @Override
    public boolean isPersistent() { return true; }

    private AizZoneRuntimeState currentAizState() {
        return S3kRuntimeStates.currentAiz(services().zoneRuntimeRegistry()).orElse(null);
    }
}
