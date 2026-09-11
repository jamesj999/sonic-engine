package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 1 Ring Flash effect (Object 0x7C) - spawned when the Giant Ring is collected.
 * <p>
 * From docs/s1disasm/_incObj/7C Ring Flash.asm:
 * <ul>
 *   <li>Routine 0 (Flash_Main): Init - set mappings, priority 0 (foreground), frame $FF</li>
 *   <li>Routine 2 (Flash_ChkDel): Animate via Flash_Collect subroutine</li>
 *   <li>Routine 4 (Flash_Delete): Object deletion</li>
 * </ul>
 * <p>
 * Animation: 8 frames at 2 game frames each (total 16 frames = ~0.267s).
 * At frame 3: deletes parent Giant Ring, sets f_bigring flag.
 * At frame 8: removes flash object.
 * <p>
 * Art: Nem_BigFlash at ArtTile_Giant_Ring_Flash ($462), palette line 1.
 */
public class Sonic1RingFlashObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(Sonic1RingFlashObjectInstance.class.getName());

    // Flash_Collect: move.b #1,obTimeFrame(a0) - 2 game frames per animation frame
    private static final int FRAME_DURATION = 2;

    // Flash_Collect: cmpi.b #8,obFrame(a0) - total animation frames
    private static final int TOTAL_FRAMES = 8;

    // Flash_Collect: cmpi.b #3,obFrame(a0) - trigger frame for parent deletion
    private static final int TRIGGER_FRAME = 3;
    private final transient Sonic1GiantRingObjectInstance parent;
    private final transient int posX;
    private final transient int posY;
    // Un-finaled for rewind: hFlip is NOT spawn-derivable (it encodes the player's
    // approach direction, not packed in ObjectSpawn), so the generic field capturer
    // reapplies the captured value after generic recreate uses placeholder false.
    private boolean hFlip;

    // ROM: obFrame starts at $FF, obTimeFrame starts at 0
    // First tick: timer=0 -> subq makes it $FF (negative) -> advances frame from $FF to $00
    private int frameTimer = 0;
    private int animFrame = -1; // Will advance to 0 on first tick
    private boolean triggerFired = false;
    private boolean finished = false;

    private Sonic1RingFlashObjectInstance(ObjectSpawn spawn) {
        this(null, spawn.x(), spawn.y(), false);
    }

    /**
     * Creates a Ring Flash at the given position.
     *
     * @param parent the parent Giant Ring object
     * @param x      center X position (from parent ring)
     * @param y      center Y position (from parent ring)
     * @param hFlip  true if Sonic approached from the right
     */
    public Sonic1RingFlashObjectInstance(Sonic1GiantRingObjectInstance parent,
                                         int x, int y, boolean hFlip) {
        super(new ObjectSpawn(x, y, 0x7C, 0, 0, false, 0), "RingFlash");
        this.parent = parent;
        this.posX = x;
        this.posY = y;
        this.hFlip = hFlip;
    }

    @Override
    public Sonic1RingFlashObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        Sonic1GiantRingObjectInstance liveParent = findLiveGiantRingParent(ctx);
        return new Sonic1RingFlashObjectInstance(liveParent, spawn.x(), spawn.y(), false);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (finished) {
            setDestroyed(true);
            return;
        }

        // Flash_Collect's id_Null write remains in the player SST until the
        // later word clear deletes that slot. The engine keeps a structural
        // player instance alive, so republish the retained native byte while
        // the flash owns this interval instead of allowing normal animation
        // selection to rewrite it between flash ticks.
        if (triggerFired && player != null) {
            player.setAnimationId(Sonic1AnimationIds.NULL.id());
            player.setForcedAnimationId(Sonic1AnimationIds.NULL.id());
        }

        // Flash_Collect subroutine
        frameTimer--;
        if (frameTimer >= 0) {
            return; // Timer hasn't expired: bpl.s locret_9F76
        }

        // Reset timer: move.b #1,obTimeFrame(a0)
        frameTimer = FRAME_DURATION - 1;

        // Advance frame: addq.b #1,obFrame(a0)
        animFrame++;

        // Check animation end: cmpi.b #8,obFrame(a0) / bhs.s Flash_End
        if (animFrame >= TOTAL_FRAMES) {
            // Flash_End: trigger level ending sequence, then transition to special stage
            triggerLevelEnd(player);
            finished = true;
            return;
        }

        // Check trigger frame: cmpi.b #3,obFrame(a0) / bne.s locret_9F76
        if (animFrame == TRIGGER_FRAME && !triggerFired) {
            triggerFired = true;

            // ROM: move.b #1,(f_bigring).w — block hidden bonuses
            services().gameState().setBigRingCollected(true);

            // ROM: move.b #6,obRoutine(a1) - delete parent Giant Ring
            if (parent != null) {
                parent.onFlashFrame3();
            }

            // ROM: move.b #id_Null,(v_player+obAnim).w - make Sonic invisible
            // ROM: clr.b (v_invinc).w - remove invincibility
            // ROM: clr.b (v_shield).w - remove shield
            if (player != null) {
                player.setHidden(true);
                player.setAnimationId(Sonic1AnimationIds.NULL.id());
                player.setForcedAnimationId(Sonic1AnimationIds.NULL.id());
                player.clearPowerUps();
            }

            // Pause the level timer
            var levelGamestate = services().levelGamestate();
            if (levelGamestate != null) {
                levelGamestate.pauseTimer();
            }
        }
    }

    /**
     * Triggers the level ending sequence (results screen) and queues a special stage transition.
     * Called when the flash animation completes (frame 8).
     */
    private void triggerLevelEnd(AbstractPlayableSprite player) {

        // ROM Flash_Collect deletes the player SST entry here with
        // `move.w #0,(v_player).w`. The engine retains its structural player
        // instance across the results transition, so represent the absent SST
        // slot by suppressing movement and touch processing from this point.
        if (player != null) {
            player.setNativeSlotPresent(false);
            ObjectControlState.nativeBit7FullControl().applyTo(player);
        }

        // v_endcard is a fixed singleton slot in the ROM. A glitched/fast route
        // can cross the signpost walk-off threshold and start the card before
        // entering the giant ring; Got_NextLevel reads f_bigring dynamically
        // when that existing card exits. Reuse it instead of restarting the
        // sequence with a second card.
        Sonic1ResultsScreenObjectInstance existingResults = findExistingResultsScreen();
        if (existingResults != null) {
            existingResults.setSpecialStageAfter(true);
        }
    }

    private Sonic1ResultsScreenObjectInstance findExistingResultsScreen() {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return null;
        }
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (instance instanceof Sonic1ResultsScreenObjectInstance results && !results.isDestroyed()) {
                return results;
            }
        }
        return null;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (finished || animFrame < 0) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.GIANT_RING_FLASH);
        if (renderer == null) return;
        renderer.drawFrameIndex(animFrame, posX, posY, hFlip, false);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.b #0,obPriority(a0) - highest priority (foreground)
        return RenderPriority.clamp(0);
    }

    @Override
    public boolean isPersistent() {
        // Flash must complete its animation even if spawn position goes off-screen
        return true;
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        return true;
    }

    private static Sonic1GiantRingObjectInstance findLiveGiantRingParent(RewindRecreateContext ctx) {
        ObjectServices services = ctx.objectServices();
        if (services == null) {
            return null;
        }
        ObjectManager objectManager = services.objectManager();
        if (objectManager == null) {
            return null;
        }
        for (ObjectInstance inst : objectManager.getActiveObjects()) {
            if (inst instanceof Sonic1GiantRingObjectInstance ring && !ring.isDestroyed()) {
                return ring;
            }
        }
        return null;
    }
}
