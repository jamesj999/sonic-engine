package com.openggf.level.objects;

import com.openggf.graphics.RenderPriority;
import com.openggf.game.DamageCause;
import com.openggf.game.PlayableEntity;

/**
 * Shared base class for S2 and S3K spike objects.
 * <p>
 * Extracts identical retract constants, dimension tables, hurt/solid logic,
 * and oscillation movement. Game-specific subclasses override
 * {@link #moveSpikes(PlayableEntity)} to add behaviors (e.g. S3K push mode)
 * and {@link #playSpikeMoveSfx()} for game-specific audio.
 * <p>
 * Subtype encoding (shared across S2/S3K):
 * <ul>
 *   <li>Upper nibble (bits 7-4): size index (0-3 = upright, 4-7 = sideways)</li>
 *   <li>Lower nibble (bits 3-0): behavior (0=static, 1=vertical, 2=horizontal, ...)</li>
 * </ul>
 */
public abstract class AbstractSpikeObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    protected static final int[] WIDTH_PIXELS = {
            0x10, 0x20, 0x30, 0x40,
            0x10, 0x10, 0x10, 0x10
    };
    protected static final int[] Y_RADIUS = {
            0x10, 0x10, 0x10, 0x10,
            0x10, 0x20, 0x30, 0x40
    };

    protected static final int SPIKE_RETRACT_STEP = 0x800;
    protected static final int SPIKE_RETRACT_MAX = 0x2000;
    protected static final int SPIKE_RETRACT_DELAY = 60;

    protected int baseX;
    protected int baseY;
    protected int currentX;
    protected int currentY;
    protected int retractOffset;
    protected int retractState;
    protected int retractTimer;
    protected AbstractSpikeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.currentX = baseX;
        this.currentY = baseY;
    }

    // ---- Solid object contract ----

    @Override
    public SolidObjectParams getSolidParams() {
        int widthPixels = getEntryValue(WIDTH_PIXELS);
        int yRadius = getEntryValue(Y_RADIUS);
        return SolidObjectParams.of(widthPixels + 0x0B, yRadius, yRadius + 1);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // ROM SolidObject_cont keeps relX == width * 2 in contact; it rejects
        // only relX > width * 2 (sonic3k.asm:41395-41401).
        return true;
    }

    @Override
    public boolean groundedSquashEdgeSideContactSetsPush() {
        // Obj36 / Obj_Spikes call SolidObjectFull. A grounded lower-half
        // overlap within $10 pixels of the padded side edge escapes the squash
        // path through SolidObject_LeftRight / loc_1E042, then sets push in
        // SolidObject_AtEdge / loc_1E06E even when the player is moving away
        // (s2.asm:35336-35402; sonic3k.asm:41564-41568,41473-41495).
        return true;
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // S2 Obj36 and S3K Obj_Spikes call SolidObjectFull. If this object's
        // standing bit is still set while the player is already airborne, the
        // helper clears Status_OnObj / the object bit and returns before
        // SolidObject_cont can create a fresh contact.
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // S2 Obj36 and S3K Obj_Spikes store standing/pushing bits in the live
        // object status byte. Moving/retracting spikes rebuild their dynamic
        // engine spawn as they move, so the solid latch must follow the object
        // slot, not the value-equal spawn position.
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (player == null) {
            return;
        }
        if (!shouldHurt(contact)) {
            return;
        }
        if (player.getDead()) {
            // SolidObject_Squash can call KillCharacter before Obj36 reaches Touch_ChkHurt2;
            // the ROM helper then skips players whose routine is already >= 4.
            return;
        }
        if (player.getInvulnerable()) {
            return;
        }
        rewindPlayerYBeforeHurt(player);
        // ROM: Hurt_Sidekick - CPU Tails only gets knockback, no ring scatter or death
        if (player.isCpuControlled()) {
            player.applyHurt(currentX, DamageCause.SPIKE);
            return;
        }
        boolean hadRings = player.getRingCount() > 0;
        if (hadRings && !player.hasShield()) {
            services().spawnLostRings(player, frameCounter);
        }
        player.applyHurtOrDeath(currentX, true, hadRings);
    }

    // ---- Lifecycle ----

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        moveSpikes(player);
        updateDynamicSpawn(currentX, currentY);
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
    public int getOutOfRangeReferenceX() {
        // S2 Obj36 and S3K Obj_Spikes store the placement X in objoff_30/$30
        // and feed that saved origin to MarkObjGone2/Sprite_OnScreen_Test2
        // after live spike movement (docs/s2disasm/s2.asm:29221-29226;
        // docs/skdisasm/sonic3k.asm:49038-49039,49071-49072,49102-49103).
        return baseX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public int getOnScreenHalfHeight() {
        // ROM Obj36 (S2 s2.asm:29341-29345) sets only
        // render_flags.level_fg in their init -- never render_flags.explicit_height.
        // BuildSprites therefore evaluates the on-screen flag through the
        // approximate Y check (BuildSprites_ApproxYCheck, s2.asm:30606-30619),
        // which assumes a 32px (0x20) Y radius regardless of the spike's actual
        // y_radius: on-screen when (y_pos - Camera_Y) is within [-32, screen_height+32).
        // The shared gate default (16px) clipped the spike off-screen one frame
        // early near the bottom of the viewport, so SolidObject_OnScreenTest
        // (s2.asm:35330-35336) skipped the side push that ROM applies. Use the
        // ROM approximate radius so the inline solid gate matches render_flags
        // bit 7. S3K overrides this because Render_Sprites reads height_pixels(a0)
        // directly.
        return 0x20;
    }

    // ---- Movement ----

    /**
     * Template method: dispatch spike movement by subtype behavior nibble.
     * S2 handles behaviors 0-2; S3K adds behavior 3 (push mode).
     */
    protected void moveSpikes(PlayableEntity player) {
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

    protected void moveSpikesVertical() {
        moveSpikesDelay();
        currentX = baseX;
        currentY = baseY + (retractOffset >> 8);
    }

    protected void moveSpikesHorizontal() {
        moveSpikesDelay();
        currentX = baseX + (retractOffset >> 8);
        currentY = baseY;
    }

    protected void moveSpikesDelay() {
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

    // ---- Hurt direction helpers ----

    protected boolean shouldHurt(SolidContact contact) {
        if (isSideways()) {
            return contact.touchSide();
        }
        if (isUpsideDown()) {
            return contact.touchBottom();
        }
        return contact.standing();
    }

    protected boolean isSideways() {
        return ((spawn.subtype() >> 4) & 0xF) >= 4;
    }

    protected boolean isUpsideDown() {
        return (spawn.renderFlags() & 0x2) != 0;
    }

    protected void rewindPlayerYBeforeHurt(PlayableEntity player) {
        short ySpeed = player.getYSpeed();
        if (ySpeed != 0) {
            // ROM Touch_ChkHurt2/sub_24280 subtract y_vel<<8 from y_pos before
            // HurtCharacter (docs/s2disasm/s2.asm:29297-29312;
            // docs/skdisasm/sonic3k.asm:49211-49220).
            player.move((short) 0, (short) -ySpeed);
        }
    }

    // ---- Dimension lookup ----

    protected int getEntryValue(int[] table) {
        return table[Math.clamp((spawn.subtype() >> 4) & 0xF, 0, table.length - 1)];
    }

    // ---- Internal helpers ----

    /**
     * Play the spike-retract sound effect. Subclasses provide game-specific SFX IDs.
     */
    protected abstract void playSpikeMoveSfx();
}
