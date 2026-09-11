package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ZeroArgRewindRecreatable;

import java.util.List;
import java.util.logging.Logger;

/**
 * Spawner for AIZ2 parallax background trees during the post-bombing transition.
 *
 * <p>ROM: Obj_AIZ2MakeTree (sonic3k.asm).
 * Waits until Camera_X_pos >= $44D0, then reads a script of 17 entries.
 * Each entry is a (scroll_threshold, x_position) pair. When the cumulative
 * scroll distance since activation exceeds the current entry's threshold,
 * a new {@link AizBgTreeInstance} is spawned.
 *
 * <p>The x_position from the script is passed to the tree but has no practical
 * effect because the parallax calculation overwrites the tree's screen X
 * each frame.
 *
 * <p>Spawned by {@link Sonic3kAIZEvents#onBattleshipComplete()}.
 */
public class AizBgTreeSpawnerInstance extends AbstractObjectInstance
        implements ZeroArgRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizBgTreeSpawnerInstance.class.getName());

    /** Camera X threshold to activate the spawner. ROM: cmpi.w #$44D0,(Camera_X_pos).w. */
    private static final int ACTIVATION_CAMERA_X = 0x44D0;

    /**
     * Tree spawn script from ROM (AIZMakeTreeScript).
     * ROM has 17 entries, followed by $FFFF. The last two thresholds ($50C,
     * $557) spawn after Sonic has effectively passed the visible forest mask;
     * hardware priority masking hides them behind opaque foreground tiles.
     * Until the renderer models that per-tile priority mask, only the 15 visible
     * entries are spawned.
     */
    private static final int[][] TREE_SCRIPT = {
            {0x000, 0x280}, {0x032, 0x380}, {0x08E, 0x280}, {0x103, 0x380},
            {0x179, 0x280}, {0x1C6, 0x380}, {0x233, 0x280}, {0x2A0, 0x380},
            {0x30A, 0x280}, {0x37C, 0x380}, {0x3C7, 0x280}, {0x401, 0x380},
            {0x439, 0x280}, {0x46E, 0x380}, {0x4CA, 0x280},
    };

    /** True once camera has reached the activation threshold. */
    private boolean activated;

    /** Smooth scroll X at the moment of activation (baseline for script thresholds). */
    private int activationSmoothScrollX;

    /** Index into TREE_SCRIPT; when >= TREE_SCRIPT.length, all trees have been spawned. */
    private int scriptIndex;

    public AizBgTreeSpawnerInstance() {
        super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "AIZ2MakeTree");
        this.activated = false;
        this.scriptIndex = 0;
    }

    /** Probe constructor for generic restore paths. Delegates to the zero-arg constructor. */
    AizBgTreeSpawnerInstance(ObjectSpawn spawn) {
        this();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) return;

        AizZoneRuntimeState state = currentAizState();
        if (state == null) {
            return;
        }

        // Wait for camera to reach the activation point
        if (!activated) {
            int cameraX = services().camera().getX();
            if (cameraX < ACTIVATION_CAMERA_X) {
                return;
            }
            activated = true;
            activationSmoothScrollX = state.getBattleshipSmoothScrollX();
            LOG.info("AIZ2 tree spawner: activated at cameraX=0x"
                    + Integer.toHexString(cameraX)
                    + ", smoothX=0x" + Integer.toHexString(activationSmoothScrollX));
        }

        // Process script entries
        int currentSmooth = state.getBattleshipSmoothScrollX();
        int scrollDistance = currentSmooth - activationSmoothScrollX;

        while (scriptIndex < TREE_SCRIPT.length) {
            int threshold = TREE_SCRIPT[scriptIndex][0];
            if (scrollDistance < threshold) {
                break;
            }

            // Spawn a tree with the current smooth scroll X as its baseline
            spawnFreeChild(() -> new AizBgTreeInstance(currentSmooth));

            scriptIndex++;
        }

        // Self-destruct once all trees have been spawned
        if (scriptIndex >= TREE_SCRIPT.length) {
            LOG.info("AIZ2 tree spawner: all " + TREE_SCRIPT.length + " trees spawned, removing");
            setDestroyed(true);
        }
    }

    /**
     * The spawner is invisible; it only spawns trees.
     */
    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // No-op: spawner has no visual representation
    }

    /**
     * ROM Obj_AIZ2MakeTree does not tail-call MarkObjGone. It remains alive until
     * AIZMakeTreeScript reaches its terminator, even though its engine spawn anchor
     * is not near the camera during the forest sequence.
     */
    @Override
    public boolean isPersistent() {
        return true;
    }

    private AizZoneRuntimeState currentAizState() {
        return S3kRuntimeStates.currentAiz(services().zoneRuntimeRegistry()).orElse(null);
    }
}
