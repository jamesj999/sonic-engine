package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractSpikeObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x08 - Spikes (Sonic 3 &amp; Knuckles).
 * <p>
 * Functionally identical to S2 object 0x36 with one addition: subtype behavior 3
 * (pushing mode). Uses shared SpikesSprings Nemesis art loaded to VDP tile $049C.
 * <p>
 * Subtype encoding:
 * <ul>
 *   <li>Upper nibble (bits 7-4): size index (0-3 = upright, 4-7 = sideways)</li>
 *   <li>Lower nibble (bits 3-0): behavior (0=static, 1=vertical, 2=horizontal, 3=push)</li>
 * </ul>
 */
public class Sonic3kSpikeObjectInstance extends AbstractSpikeObjectInstance
        implements RomObjectCodePointerProvider, RewindRecreatable {
    // Push mode constants (ROM: sub_2438A)
    private static final int PUSH_RATE_PERIOD = 0x10;   // $3A reset value: every 17 frames
    private static final int PUSH_MAX_DISTANCE = 0x20;  // $3C init: 32 pixels total

    // Push mode state (ROM: $3A rate limiter, $3C distance remaining, $3E/$3F prev status)
    private boolean contactPushingActive;   // Set by onSolidContact, consumed by next update()
    private int pushRateTimer;              // $3A: frames until next push allowed
    private int pushDistanceRemaining = PUSH_MAX_DISTANCE; // $3C: remaining 1px pushes
    private boolean mainRoutineReached;
    private boolean suppressSolidThisFrame = true;

    public Sonic3kSpikeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Spikes");
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // Every Obj_Spikes subtype routine used by interact(a0) is in bank $0002.
        return 0x0002;
    }

    @Override
    public Sonic3kSpikeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic3kSpikeObjectInstance(ctx.spawn());
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }
        // Track push contact for push mode (before hurt check, since side contact
        // doesn't trigger shouldHurt for upright spikes but does drive push logic).
        // ROM: status(a0) pushing bits are set by SolidObjectFull, read next frame.
        if (isPushMode() && contact.pushing()) {
            contactPushingActive = true;
        }
        super.onSolidContact(player, contact, frameCounter);
    }

    @Override
    public boolean isWithinSolidContactBounds() {
        // loc_24090/loc_2413E move the spike before calling SolidObjectFull,
        // but loc_1DF88 observes render_flags bit 7 from the preceding
        // Render_Sprites pass (sonic3k.asm:49011-49037,49102-49131,
        // 41390-41392). Test the frame-start position, not the freshly moved
        // one, so a spike entering the viewport cannot become solid early.
        return isPreUpdateWithinRenderSpriteBounds(
                getOnScreenHalfWidth(), getOnScreenHalfHeight());
    }

    @Override
    protected void moveSpikes(PlayableEntity playerEntity) {
        if (!mainRoutineReached) {
            // Obj_Spikes initialization stores loc_2413E/loc_24090/etc. in (a0)
            // and returns before the movement + SolidObjectFull body can run
            // (sonic3k.asm:48925-49012).  The first main-routine frame starts
            // on the next object execution.
            mainRoutineReached = true;
            suppressSolidThisFrame = true;
            currentX = baseX;
            currentY = baseY;
            return;
        }
        suppressSolidThisFrame = false;
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        int behavior = spawn.subtype() & 0xF;
        switch (behavior) {
            case 1 -> moveSpikesVertical();
            case 2 -> moveSpikesHorizontal();
            case 3 -> moveSpikesPush(player);
            default -> {
                currentX = baseX;
                currentY = baseY;
            }
        }
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return !suppressSolidThisFrame;
    }

    @Override
    public boolean allowsObjectControlledSolidContacts() {
        // Obj_Spikes calls SolidObjectFull directly. Its shared
        // SolidObject_cont gate rejects only a signed (bit-7) object_control;
        // positive controller states such as the LBZ cup's $03 still receive
        // ordinary side/top separation.
        return true;
    }

    @Override
    public boolean rejectsBit7ObjectControlNewSolidContact(PlayableEntity player) {
        return true;
    }

    @Override
    public int getOnScreenHalfWidth() {
        // S3K Render_Sprites reads width_pixels(a0) for the on-screen X test,
        // and Obj_Spikes initializes that byte from Spikes_Dimensions --
        // $10/$20/$30/$40 for the upright sizes, $10 for the sideways ones
        // (docs/skdisasm/sonic3k.asm:48926-48934 table, :48937-48939 store).
        // The AbstractObjectInstance default of 16 is only correct for the
        // narrowest entry, so a wide spike strip whose centre sits just left of
        // the camera reads as offscreen, skips SolidObjectFull entirely, and
        // lets a character walk through it.
        return getEntryValue(WIDTH_PIXELS);
    }

    @Override
    public int getOnScreenHalfHeight() {
        // S3K Render_Sprites reads height_pixels(a0) directly. Obj_Spikes
        // initializes that from byte_23F74, which matches the shared y-radius
        // table for the spike subtypes.
        return getEntryValue(Y_RADIUS);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }

        int frameIndex = Math.clamp((spawn.subtype() >> 4) & 0xF, 0, 7);
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.SPIKES);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(frameIndex, currentX, currentY, hFlip, vFlip);
        }
    }

    @Override
    protected void playSpikeMoveSfx() {
        if (!isOnScreen()) {
            return;
        }
        try {
            services().playSfx(Sonic3kSfx.SPIKE_MOVE.id);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic.
        }
    }

    private boolean isPushMode() {
        return (spawn.subtype() & 0xF) == 3;
    }

    /**
     * Behavior 3: player-driven pushing mode (ROM: loc_24356 / sub_2438A).
     * <p>
     * Unlike vertical/horizontal oscillation, push spikes only move when a player
     * actively pushes against them from the left. Movement is rate-limited to 1 pixel
     * every 17 frames, with a maximum total displacement of 32 pixels.
     */
    private void moveSpikesPush(AbstractPlayableSprite player) {
        // contactPushingActive is set by onSolidContact (previous frame's solid resolution).
        // Consume it now; will be re-set by this frame's onSolidContact if still pushing.
        boolean wasPushing = contactPushingActive;
        contactPushingActive = false;

        if (!wasPushing || player == null) {
            return;
        }
        // ROM: btst #5,d0 - player must have been in pushing state (from previous solid resolution)
        if (!player.getPushing()) {
            return;
        }
        // ROM: cmp.w x_pos(a1),d2 / blo.s - spike must be at or to the right of the player
        int playerX = player.getCentreX();
        if (currentX < playerX) {
            return;
        }
        // ROM: subq.w #1,$3A(a0) / bpl.s - rate limiter (first push is immediate, then every 17 frames)
        pushRateTimer--;
        if (pushRateTimer >= 0) {
            return;
        }
        pushRateTimer = PUSH_RATE_PERIOD;
        // ROM: tst.w $3C(a0) / beq.s - check remaining push distance
        if (pushDistanceRemaining <= 0) {
            return;
        }
        // ROM: subq.w #1,$3C(a0) / addq.w #1,x_pos(a0) / addq.w #1,x_pos(a1)
        pushDistanceRemaining--;
        currentX++;
        player.setCentreX((short) (playerX + 1));
    }
}
