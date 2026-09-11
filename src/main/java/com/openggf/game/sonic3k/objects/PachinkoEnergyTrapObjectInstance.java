package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0xE8 - Pachinko energy trap.
 *
 * <p>ROM reference: {@code Obj_PachinkoEnergyTrap}. Spawns the paired column, emits
 * beam particles, captures the player on the beam line, and ends the bonus stage.
 */
public class PachinkoEnergyTrapObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(PachinkoEnergyTrapObjectInstance.class.getName());

    private static final int COLUMN_SPACING = 0x190;
    private static final int BEAM_SPAWN_X_OFFSET = 0x80;
    private static final int BEAM_DELETE_X_ABSOLUTE = 0x178;
    private static final int BEAM_SPAWN_PERIOD = 8;
    private static final int INITIAL_EXIT_DELAY = 60;
    private static final int INITIAL_RISE_DELAY = 4 * 60;
    private static final int TRANSPORTER_PERIOD = 0x40;
    private static final int CAPTURE_TOP = -0x0C;
    private static final int CAPTURE_BOTTOM_EXCLUSIVE = 0x0C;
    private static final int PLAYER_ESCAPED_Y = -0x20;

    private boolean initialized;
    private int beamAngle;
    private int exitDelayFrames = INITIAL_EXIT_DELAY;
    private int riseDelayFrames = INITIAL_RISE_DELAY;
    private boolean exitArmed;
    private boolean exitRequested;
    private AbstractPlayableSprite capturedPlayer;
    private int currentX;
    private int currentY;
    private int updateCount;
    private int lastUpdateVIntRunCount = -1;
    private int renderCount;
    private int beamSpawnCount;

    public PachinkoEnergyTrapObjectInstance(ObjectSpawn spawn) {
        super(spawn, "PachinkoEnergyTrap");
        currentX = spawn.x();
        currentY = spawn.y();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-contained: the constructor only derives position from the captured spawn
     * and does not spawn any children (column/beam children are spawned lazily from
     * {@code update()}), so re-running it on restore is side-effect free. Scalar fields
     * are reapplied by the standard scalar-restore pass; the captured-player back-reference
     * is not wired here (it was not captured by the deleted explicit restore path either).
     * Replaces the former explicit dynamic restore path (Phase-2 codec-deletion batch 2).
     */

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        updateCount++;
        lastUpdateVIntRunCount = vIntRunCount;
        if (!initialized) {
            initialized = true;
            LOGGER.info(() -> "Pachinko trap initialized at x=" + currentX + " y=" + currentY
                    + " slot=" + getSlotIndex());
            spawnChild(() -> new EnergyTrapColumnChild(createColumnSpawn(), this));
        }

        if ((vIntRunCount & (BEAM_SPAWN_PERIOD - 1)) == 0) {
            beamSpawnCount++;
            spawnChild(() -> new EnergyTrapBeamChild(createBeamSpawn(), this, beamAngle));
        }
        beamAngle = (beamAngle + 8) & 0xFF;

        if (!exitArmed) {
            if (riseDelayFrames > 0) {
                riseDelayFrames--;
            } else {
                currentY -= 1;
            }
        }

        updateDynamicSpawn(currentX, currentY);

        maybePlayTransporterSfx(vIntRunCount, playerEntity);
        // ROM loc_49FD6 (docs/skdisasm/sonic3k.asm:96634-96637) runs sub_49FE4 once for
        // Player_1 and once for Player_2, so the sidekick is captured by exactly the same
        // subroutine. Only the tail after `cmpa.w #Player_1,a1` is main-player-only.
        AbstractPlayableSprite mainPlayer =
                playerEntity instanceof AbstractPlayableSprite sprite ? sprite : null;
        for (PlayableEntity participant : captureParticipants(mainPlayer)) {
            if (participant instanceof AbstractPlayableSprite playable) {
                runCaptureSubroutine(playable, playable == mainPlayer, vIntRunCount);
            }
        }
    }

    private List<PlayableEntity> captureParticipants(AbstractPlayableSprite mainPlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        if (mainPlayer == null || participants.contains(mainPlayer)) {
            return participants;
        }
        List<PlayableEntity> withMain = new ArrayList<>(participants.size() + 1);
        withMain.add(mainPlayer);
        withMain.addAll(participants);
        return withMain;
    }

    @Override
    public boolean isPersistent() {
        // ROM parity: this bootstrap-spawned trap never calls out_of_range.
        return true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getRiseDelayFrames() {
        return riseDelayFrames;
    }

    public boolean isExitArmed() {
        return exitArmed;
    }

    public boolean isExitRequested() {
        return exitRequested;
    }

    public boolean hasCapturedPlayer() {
        return capturedPlayer != null;
    }

    public int getUpdateCount() {
        return updateCount;
    }

    public int getLastUpdateFrameCounter() {
        return lastUpdateVIntRunCount;
    }

    public int getRenderCount() {
        return renderCount;
    }

    public int getBeamSpawnCount() {
        return beamSpawnCount;
    }

    /**
     * Port of ROM {@code sub_49FE4} (docs/skdisasm/sonic3k.asm:96640-96678), run once per
     * player from {@code loc_49FD6}.
     *
     * <p>The subroutine writes exactly three player fields — {@code move.w y_pos(a0),y_pos(a1)}
     * (a word write, so {@code y_sub} is untouched), {@code move.b #$81,object_control(a1)}
     * and {@code bset #Status_InAir,status(a1)}. It never clears {@code x_vel}/{@code y_vel}/
     * {@code ground_vel}, never touches {@code Ctrl_N_locked}, and never clears the
     * on-object bit, so a held player keeps the velocities it entered the beam with.
     *
     * <p>There is no capture latch either: a player outside the {@code $18}-tall band simply
     * returns at {@code locret_4A078} with nothing written.
     */
    private void runCaptureSubroutine(AbstractPlayableSprite player, boolean isMainPlayer,
                                      int vIntRunCount) {
        if (player.isDebugMode()) {
            // ROM `tst.w (Debug_placement_mode).w / bne.s locret_4A078` (:96658-96659).
            return;
        }

        int playerCenterY = player.getCentreY();
        if (playerCenterY < PLAYER_ESCAPED_Y) {
            // ROM head (:96641-96648): a player above -$20 skips the band test entirely.
            // Only the main player zeroes the exit countdown before falling through.
            if (isMainPlayer) {
                exitDelayFrames = 0;
            }
        } else {
            int relativeY = playerCenterY - currentY;
            if (relativeY < CAPTURE_TOP || relativeY >= CAPTURE_BOTTOM_EXCLUSIVE) {
                // ROM `addi.w #$C,d0 / cmpi.w #$18,d0 / bhs.s locret_4A078` (:96653-96655).
                return;
            }
            if (isMainPlayer) {
                capturedPlayer = player;
            }
            releaseCompetingMagnetOrbs(player, vIntRunCount);
            NativePositionOps.writeYPosPreserveSubpixel(player, currentY);
            if (!player.isObjectControlled()) {
                // ROM `tst.b $2E(a1) / bne.s loc_4A024` then sfx_Bouncy (:96661-96664):
                // the bounce plays on every frame the player enters with no object control.
                playSfx(Sonic3kSfx.BOUNCY);
            }
        }

        // loc_4A024 (:96665-96667).
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setAir(true);
        if (!isMainPlayer) {
            return;
        }
        // ROM `move.b #1,subtype(a0)` (:96670) latches the rise off permanently, then
        // `subq.b #1,$24(a0) / bcc.s locret_4A078` restarts the level on borrow.
        exitArmed = true;
        if (!exitRequested && --exitDelayFrames < 0) {
            requestExit();
        }
    }

    private void requestExit() {
        if (exitRequested) {
            return;
        }
        exitRequested = true;
        LOGGER.info(() -> "Pachinko trap requested bonus-stage exit at y=" + currentY
                + " captured=" + (capturedPlayer != null));
        services().requestBonusStageExit();
    }

    private void releaseCompetingMagnetOrbs(AbstractPlayableSprite player, int vIntRunCount) {
        if (services().objectManager() == null) {
            return;
        }
        for (ObjectInstance instance : services().objectManager().getActiveObjects()) {
            if (instance instanceof PachinkoMagnetOrbObjectInstance magnetOrb) {
                magnetOrb.forceReleasePlayer(player, vIntRunCount);
            }
        }
    }

    @Override
    public void onUnload() {
        LOGGER.warning(() -> "Pachinko trap unloaded at x=" + getX() + " y=" + getY()
                + " destroyed=" + isDestroyed() + " slot=" + getSlotIndex());
    }

    private void maybePlayTransporterSfx(int vIntRunCount, PlayableEntity playerEntity) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        int relativeY = player.getCentreY() - currentY;
        if (relativeY < -0x180 || relativeY >= 0) {
            return;
        }
        if ((vIntRunCount & (TRANSPORTER_PERIOD - 1)) == 0) {
            playSfx(Sonic3kSfx.TRANSPORTER);
        }
    }

    private void playSfx(Sonic3kSfx sfx) {
        try {
            services().playSfx(sfx.id);
        } catch (Exception e) {
            // Keep gameplay logic independent from audio state.
        }
    }

    private ObjectSpawn createColumnSpawn() {
        return new ObjectSpawn(
                spawn.x() + COLUMN_SPACING,
                spawn.y(),
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags() | 0x1,
                false,
                0,
                spawn.layoutIndex());
    }

    private ObjectSpawn createBeamSpawn() {
        return new ObjectSpawn(
                currentX + BEAM_SPAWN_X_OFFSET,
                currentY,
                spawn.objectId() + 1,
                spawn.subtype(),
                spawn.renderFlags(),
                false,
                0,
                spawn.layoutIndex());
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(0);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(..., ..., 1) sets the VDP priority bit.
        // The separate priority word only controls the sprite-table bucket.
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        renderCount++;
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.PACHINKO_ENERGY_TRAP);
        if (renderer == null) {
            return;
        }
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(0, currentX, currentY, hFlip, vFlip);
    }

    private static final class EnergyTrapColumnChild extends AbstractObjectInstance
            implements RewindRecreatable {
        private PachinkoEnergyTrapObjectInstance parent;
        private int currentX;
        private int currentY;

        private EnergyTrapColumnChild(ObjectSpawn spawn, PachinkoEnergyTrapObjectInstance parent) {
            super(spawn, "PachinkoEnergyTrapColumn");
            this.parent = parent;
            currentX = spawn.x();
            currentY = spawn.y();
        }

        @Override
        public EnergyTrapColumnChild recreateForRewind(RewindRecreateContext ctx) {
            return new EnergyTrapColumnChild(ctx.spawn(), nearestTrap(ctx));
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            currentY = parent.getY();
            updateDynamicSpawn(currentX, currentY);
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(0);
        }

        @Override
        public boolean isHighPriority() {
            return true;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.PACHINKO_ENERGY_TRAP);
            if (renderer == null) {
                return;
            }
            boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
            boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
            renderer.drawFrameIndex(0, currentX, currentY, hFlip, vFlip);
        }
    }

    private static final class EnergyTrapBeamChild extends AbstractObjectInstance
            implements RewindRecreatable {

        private static final int[] ANIMATION = {0, 1, 2, 3, 4, 3, 2, 1};
        private PachinkoEnergyTrapObjectInstance parent;
        private int beamAngle;
        private int currentX;
        private int currentY;

        private EnergyTrapBeamChild(ObjectSpawn spawn, PachinkoEnergyTrapObjectInstance parent, int beamAngle) {
            super(spawn, "PachinkoEnergyBeam");
            this.parent = parent;
            this.beamAngle = beamAngle & 0xFF;
            currentX = spawn.x();
            currentY = spawn.y();
        }

        @Override
        public EnergyTrapBeamChild recreateForRewind(RewindRecreateContext ctx) {
            return new EnergyTrapBeamChild(ctx.spawn(), nearestTrap(ctx), 0);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            currentY = parent.getY() + (TrigLookupTable.sinHex(beamAngle) >> 4);
            currentX += 2;
            beamAngle = (beamAngle + 0x84) & 0xFF;
            updateDynamicSpawn(currentX, currentY);

            if (currentX > BEAM_DELETE_X_ABSOLUTE) {
                setDestroyed(true);
            }
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(1);
        }

        @Override
        public boolean isHighPriority() {
            return true;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.PACHINKO_INVISIBLE_UNKNOWN);
            if (renderer == null) {
                return;
            }
            int frame = ANIMATION[((beamAngle + 0x10) >> 5) & 0x7];
            renderer.drawFrameIndex(frame, currentX, currentY, false, false);
        }
    }

    private static PachinkoEnergyTrapObjectInstance nearestTrap(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, PachinkoEnergyTrapObjectInstance.class);
    }
}
