package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * Cutscene button for Hydrocity Zone Act 2.
 *
 * <p>ROM reference: Obj_CutsceneButton subtype 2 (loc_65C72 in sonic3k.asm:133972).
 * Spawned as a child of CutsceneKnux_HCZ2. Triggered when cutscene Knuckles
 * walks within proximity. On press:
 * <ul>
 *   <li>Sets {@code Level_trigger_array[8]} via {@link Sonic3kLevelTriggerManager}</li>
 *   <li>The tension bridge (object $6C, subtype $88) reads this flag and collapses</li>
 * </ul>
 *
 * <p>The button checks proximity against the active {@link CutsceneKnucklesHcz2Instance}
 * via the shared static reference (ROM equivalent: _unkFAA4).
 */
public class Hcz2CutsceneButtonInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(Hcz2CutsceneButtonInstance.class.getName());

    private static final int INIT_Y_OFFSET = 4;
    private static final int PRIORITY = 4;

    /** ROM: word_4551E proximity range {-$18, $30, -$18, $30}. */
    private static final int RANGE_LEFT = -0x18;
    private static final int RANGE_WIDTH = 0x30;
    private static final int RANGE_TOP = -0x18;
    private static final int RANGE_HEIGHT = 0x30;

    /** ROM: Level_trigger_array index 8 — read by tension bridge subtype $88. */
    private static final int TRIGGER_INDEX = 8;

    private int x;
    private int y;
    private boolean pressed;

    public Hcz2CutsceneButtonInstance(ObjectSpawn spawn) {
        super(spawn, "CutsceneButtonHCZ2");
        this.x = spawn.x();
        this.y = spawn.y() + INIT_Y_OFFSET;
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
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_GrayButton,0,1) has high priority bit, but
        // in practice the button nestles behind the foreground platform tiles.
        return false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (pressed) {
            return;
        }

        // ROM: Check proximity against Knuckles via _unkFAA4
        CutsceneKnucklesHcz2Instance knuckles = CutsceneKnucklesHcz2Instance.getActiveInstance();
        if (knuckles == null) {
            return;
        }

        int dx = knuckles.getX() - x;
        int dy = knuckles.getY() - y;
        if (dx >= RANGE_LEFT && dx < RANGE_LEFT + RANGE_WIDTH
                && dy >= RANGE_TOP && dy < RANGE_TOP + RANGE_HEIGHT) {
            press();
        }
    }

    /**
     * ROM: loc_65C72 (sonic3k.asm:133972) — CutsceneButton subtype 2 action.
     * Sets Level_trigger_array[8] which the tension bridge monitors.
     */
    private void press() {
        pressed = true;

        // ROM: st (Level_trigger_array+8).w
        Sonic3kLevelTriggerManager.setAll(TRIGGER_INDEX);

        LOG.info("HCZ2 cutscene button pressed: Level_trigger_array[" + TRIGGER_INDEX + "] set");
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM: make_art_tile(ArtTile_GrayButton,0,1) — uses gray button art, palette 0.
        // Palette 0 is unaffected by Pal_CutsceneKnux overwriting palette line 1.
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.BUTTON);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(pressed ? 1 : 0, x, y, false, false);
    }
}
