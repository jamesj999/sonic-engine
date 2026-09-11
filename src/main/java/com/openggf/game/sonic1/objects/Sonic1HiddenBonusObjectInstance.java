package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sonic 1 Object 0x7D - Hidden Bonuses (end-of-act hidden point pickups).
 * <p>
 * From docs/s1disasm/_incObj/7D Hidden Bonuses.asm:
 * <ul>
 *   <li>Routine 0 (Bonus_Main): Check proximity — if player within 16px on each
 *       axis and not in debug mode / big ring collected, activate: set mapping
 *       frame from subtype, award points, play SFX, start 119-frame timer.</li>
 *   <li>Routine 2 (Bonus_Display): Decrement timer, display sprite until
 *       timer expires or object goes off-screen, then delete.</li>
 * </ul>
 * <p>
 * Subtype determines mapping frame and point award:
 * <ul>
 *   <li>0 — blank frame, 0 points</li>
 *   <li>1 — "10000" frame, 10000 points</li>
 *   <li>2 — "1000" frame, 1000 points</li>
 *   <li>3 — "100" frame, 100 points</li>
 * </ul>
 * <p>
 * Art: Nem_Bonus at ArtTile_Hidden_Points ($4B6), palette 0, priority bit set.
 */
public class Sonic1HiddenBonusObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // From disassembly: moveq #$10,d2 — detection radius on each axis
    private static final int DETECTION_RADIUS = 0x10;

    // From disassembly: move.w #119,bonus_timelen(a0) — display for 119 frames (~2 seconds)
    private static final int DISPLAY_TIME = 119;

    // From disassembly: .points table — indexed by subtype, values passed to AddPoints
    // ROM stores: dc.w 0, 1000, 100, 1  (internal format, displayed = value * 10)
    // Subtype 3 original ROM bug: dc.w 1 (displays as 10pts); FixBugs corrects to dc.w 10
    // Engine uses displayed point values (same convention as addScore elsewhere).
    private static final int[] BONUS_POINTS = { 0, 10000, 1000, 100 };

    private enum State {
        WAITING,    // Routine 0: checking proximity
        DISPLAYING, // Routine 2: showing bonus and counting down
        DELETED     // Pending removal
    }

    private State state = State.WAITING;
    private int displayTimer;
    private int frameIndex;

    public Sonic1HiddenBonusObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HiddenBonus");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case WAITING -> updateWaiting(player);
            case DISPLAYING -> updateDisplaying();
            case DELETED -> setDestroyed(true);
        }
    }

    /**
     * Bonus_Main (Routine 0): Check if player is within detection range.
     * <p>
     * ROM logic:
     * <pre>
     *   moveq   #$10,d2           ; detection radius
     *   move.w  d2,d3
     *   add.w   d3,d3             ; d3 = $20 (detection diameter)
     *   move.w  obX(a1),d0
     *   sub.w   obX(a0),d0
     *   add.w   d2,d0             ; offset by radius (unsigned comparison)
     *   cmp.w   d3,d0
     *   bhs.s   .chkdel           ; outside X range
     *   [same for Y]
     *   tst.w   (v_debuguse).w    ; skip if debug mode active
     *   tst.b   (f_bigring).w     ; skip if Giant Ring collected
     * </pre>
     */
    private void updateWaiting(AbstractPlayableSprite player) {
        if (player == null) {
            checkOutOfRange();
            return;
        }

        // ROM: tst.w (v_debuguse).w / bne.s .chkdel
        if (player.isDebugMode()) {
            checkOutOfRange();
            return;
        }

        // ROM: tst.b (f_bigring).w / bne.s .chkdel
        if (services().gameState().isBigRingCollected()) {
            checkOutOfRange();
            return;
        }

        // Check proximity: player within ±DETECTION_RADIUS of object center
        int dx = player.getCentreX() - spawn.x();
        int dy = player.getCentreY() - spawn.y();

        // ROM uses unsigned comparison: add radius, compare to diameter
        if (dx + DETECTION_RADIUS < 0 || dx + DETECTION_RADIUS >= DETECTION_RADIUS * 2) {
            checkOutOfRange();
            return;
        }
        if (dy + DETECTION_RADIUS < 0 || dy + DETECTION_RADIUS >= DETECTION_RADIUS * 2) {
            checkOutOfRange();
            return;
        }

        // Activate: transition to display state
        activate();
    }

    /**
     * Activate bonus: set frame, award points, play sound, start timer.
     * <p>
     * ROM: addq.b #2,obRoutine(a0) — advance to routine 2
     *      move.b obSubtype(a0),obFrame(a0) — frame = subtype
     *      move.w #119,bonus_timelen(a0)
     *      move.w #sfx_Bonus,d0 / jsr (QueueSound2).l
     *      moveq #0,d0 / move.b obSubtype(a0),d0 / add.w d0,d0
     *      move.w .points(pc,d0.w),d0 / jsr (AddPoints).l
     */
    private void activate() {
        state = State.DISPLAYING;
        displayTimer = DISPLAY_TIME;

        int subtype = spawn.subtype();
        frameIndex = subtype;

        // Play bonus sound: sfx_Bonus = 0xC9
        try {
            services().playSfx(Sonic1Sfx.HIDDEN_BONUS.id);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }

        // Award points from the .points table
        if (subtype >= 0 && subtype < BONUS_POINTS.length) {
            int points = BONUS_POINTS[subtype];
            if (points > 0) {
                services().gameState().addScore(points);
            }
        }
    }

    /**
     * Bonus_Display (Routine 2): Decrement timer, delete when expired or off-screen.
     * <p>
     * ROM: subq.w #1,bonus_timelen(a0) / bmi.s delete
     *      out_of_range.s delete
     *      jmp (DisplaySprite).l
     */
    private void updateDisplaying() {
        displayTimer--;
        if (displayTimer < 0) {
            state = State.DELETED;
            return;
        }
        if (!isInRange()) {
            state = State.DELETED;
        }
    }

    private void checkOutOfRange() {
        // ROM: out_of_range.s .delete
        if (!isInRange()) {
            state = State.DELETED;
        }
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (state != State.DISPLAYING) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.HIDDEN_BONUS);
        if (renderer == null) return;
        renderer.drawFrameIndex(frameIndex, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.b #0,obPriority(a0) — foreground priority
        return RenderPriority.clamp(0);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (state == State.DELETED) {
            return;
        }
        float r = state == State.WAITING ? 1f : 0f;
        float g = state == State.DISPLAYING ? 1f : 0.5f;
        float b = 0.5f;
        // Detection range box (±16px from center)
        ctx.drawRect(spawn.x(), spawn.y(), DETECTION_RADIUS, DETECTION_RADIUS, r, g, b);
    }
}
