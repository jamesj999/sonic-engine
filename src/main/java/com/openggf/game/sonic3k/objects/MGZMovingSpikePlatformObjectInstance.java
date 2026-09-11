package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.DamageCause;
import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x56 — {@code Obj_MGZMovingSpikePlatform}.
 *
 * <p>ROM: {@code sonic3k.asm:71029-71114}. A solid platform whose top is a
 * narrow safe cap with a block of downward-facing spikes covering the body.
 * The platform oscillates horizontally 1px/frame across a ±$50 pixel range
 * around its spawn X, and bobs vertically by the byte value at
 * {@code Oscillating_table+$12}. A 4-frame rotation animation cycles every
 * 8 frames. Collision uses {@code SolidObjectFull}; a player in contact
 * whose Y center is at or below {@code platform.Y - $28} receives spike
 * hurt via {@code sub_24280}.
 *
 * <p>Status bit 0 (render flags bit 0) selects the initial movement
 * direction: clear = moving right, set = moving left.
 */
public class MGZMovingSpikePlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_MGZMovingSpikePlatform} is installed from the S3K object pointer table at
     * {@code $000346E2} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:71034).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0003}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }


    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_MOVING_SPIKE_PLATFORM;

    // ROM: move.w #$200,priority(a0)
    private static final int PRIORITY_BUCKET = 4;

    // ROM: move.b #$18,width_pixels(a0) — already a half-width in S3K convention.
    private static final int HALF_WIDTH = 0x18;
    // ROM: move.b #$30,height_pixels(a0) — already a half-height.
    private static final int HALF_HEIGHT = 0x30;
    // ROM: addi.w #$B,d1 — side-collision padding applied only for SolidObjectFull.
    private static final int SIDE_PADDING = 0x0B;
    // ROM: addi.w #$50,d0 / subi.w #$50,d0 — total ±$50 travel either side of baseX.
    private static final int TRAVEL_RANGE = 0x50;

    // Oscillating_table+$12 in ROM. The engine's OscillationManager uses
    // Oscillating_Data-style offsets (no leading control word), so subtract 2.
    private static final int OSC_OFFSET = 0x10;

    // ROM: addi.w #$28,d0; bmi.s skip — skip hurt when
    // (y_pos(player) - y_pos(platform) + $28) < 0.
    private static final int HURT_Y_THRESHOLD = 0x28;

    // ROM: anim_frame_timer reloads to 7 after wrapping past 0.
    private static final int ANIM_TIMER_RELOAD = 7;
    private static final int FRAME_MASK = 0x03;

    private int baseX;
    private int baseY;

    // ROM: $34(a0) direction byte — 0 = moving right, 1 = moving left.
    private int directionLeft;
    private int currentX;
    private int currentY;
    private int animTimer;
    private int mappingFrame;

    public MGZMovingSpikePlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZMovingSpikePlatform");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        // ROM: btst #0,status(a0); beq skip; else move.b #1,$34(a0).
        this.directionLeft = ((spawn.renderFlags() & 0x01) != 0) ? 1 : 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // ROM: addq.w #1 / subq.w #1 on x_pos then compare against baseX ± $50
        // (strict equality — the flip happens exactly at the edge pixel).
        if (directionLeft == 0) {
            currentX++;
            if (currentX == baseX + TRAVEL_RANGE) {
                directionLeft = 1;
            }
        } else {
            currentX--;
            if (currentX == baseX - TRAVEL_RANGE) {
                directionLeft = 0;
            }
        }

        // ROM: move.b (Oscillating_table+$12).w,d0 / add.w $32(a0),d0 / move.w d0,y_pos(a0).
        // This routine only reads the global table. OscillateNumDo runs once at
        // the LevelLoop tail after every object slot (sonic3k.asm:7909,
        // 71069-71072), so an object-local update would double-advance every
        // oscillator while this platform is active.
        int oscByte = OscillationManager.getByte(OSC_OFFSET) & 0xFF;
        currentY = baseY + oscByte;

        // ROM: subq.b #1,anim_frame_timer(a0); bpl skip; reload $07; next frame.
        animTimer--;
        if (animTimer < 0) {
            animTimer = ANIM_TIMER_RELOAD;
            mappingFrame = (mappingFrame + 1) & FRAME_MASK;
        }

        updateDynamicSpawn(currentX, currentY);
    }

    // ===== SolidObjectProvider =====

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: d1 = width_pixels + $B; d2 = height_pixels; d3 = height_pixels + 1.
        return SolidObjectParams.of(HALF_WIDTH + SIDE_PADDING, HALF_HEIGHT, HALF_HEIGHT + 1);
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
        // ROM: top-surface retention uses unpadded width_pixels.
        return HALF_WIDTH;
    }

    @Override
    public int getBalanceWidthPixels() {
        // Sonic_Move/Tails_Move read width_pixels(a1) directly. The platform's
        // full-solid pass uses width+$B, while the shared fallback is only $10;
        // neither represents the native $18 edge-balance surface.
        return HALF_WIDTH;
    }

    @Override
    public boolean airborneRiderUnseatRequiresOwnCheckpoint(PlayableEntity player) {
        // Obj_MGZMovingSpikePlatform consumes SolidObjectFull's d6 result in
        // this slot before running its spike-hurt branch. Preserve the native
        // per-object standing bit until this platform's own checkpoint runs.
        return true;
    }

    // ===== SolidObjectListener =====

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // ROM: after SolidObjectFull, swap d6; andi.w #1|2,d6 — bits 0/1 of d6's high
        // word are set whenever the player interacted with the solid. In the engine,
        // onSolidContact firing is the equivalent signal.
        if (playerEntity == null || playerEntity.getInvulnerable()) {
            return;
        }
        // ROM: move.w y_pos(a1),d0; sub.w y_pos(a0),d0; addi.w #$28,d0; bmi.s skip.
        // Skip hurt when the player's Y center is more than $28 above the platform's Y
        // center (they are on or above the narrow safe cap). Apply hurt otherwise.
        int playerY = playerEntity.getCentreY();
        if (playerY - currentY + HURT_Y_THRESHOLD < 0) {
            return;
        }

        // sub_24280 rewinds the full 16:16 y_pos by the current 8:8 y_vel
        // before calling HurtCharacter. The platform runs after the player
        // slot, so this restores the pre-movement position (including the
        // subpixel fraction) before Player_TouchFloor changes rolling radii.
        if (playerEntity instanceof AbstractPlayableSprite sprite) {
            sprite.move((short) 0, (short) -sprite.getYSpeed());
        }

        int sourceX = currentX;
        if (playerEntity.isCpuControlled()) {
            playerEntity.applyHurt(sourceX);
            return;
        }
        boolean hadRings = playerEntity.getRingCount() > 0;
        if (hadRings && !playerEntity.hasShield()) {
            services().spawnLostRings(playerEntity, frameCounter);
        }
        playerEntity.applyHurtOrDeath(sourceX, DamageCause.NORMAL, hadRings);
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawRect(currentX, currentY, HALF_WIDTH + SIDE_PADDING, HALF_HEIGHT,
                0.9f, 0.3f, 0.2f);
        // Cross marking the hurt threshold line (platform.Y - $28).
        ctx.drawLine(currentX - HALF_WIDTH, currentY - HURT_Y_THRESHOLD,
                currentX + HALF_WIDTH, currentY - HURT_Y_THRESHOLD,
                1.0f, 0.6f, 0.0f);
    }

    // ===== Position =====

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // ROM loc_347F6 reloads the fixed origin from $30(a0) before
        // tail-calling Sprite_OnScreen_Test2. The live platform can be up to
        // $50 pixels away from that origin when the unload decision runs.
        return baseX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }
}
