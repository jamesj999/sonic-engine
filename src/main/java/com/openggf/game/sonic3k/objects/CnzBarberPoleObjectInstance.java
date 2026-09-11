package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.game.CanonicalAnimation;
import com.openggf.sprites.NativePositionOps;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Object 0x4D - CNZ barber pole / curved ride sprite.
 *
 * <p>Ports the S&K-side {@code Obj_CNZBarberPoleSprite} interaction paths at
 * {@code loc_33376} and {@code loc_335A8}. The object is gameplay logic: it
 * latches each player via {@code Status_OnObj}, stores their path offset in the
 * object's per-player work area, then positions them along the pole using
 * {@code GetSineCosine}.
 */
public final class CnzBarberPoleObjectInstance extends AbstractObjectInstance
        implements RomObjectCodePointerProvider, RewindRecreatable {

    private static final int TRACK_LIMIT = 0xA0;
    private static final int TRACK_FRACTION_MASK = 0xFFFF;
    private static final int MIN_GROUND_SPEED_TO_STAY_ATTACHED = 0x118;
    private static final int ROM_CODE_POINTER_HIGH_WORD = 0x0003;

    private boolean mirrored;
    private final Map<AbstractPlayableSprite, RiderState> riders = new IdentityHashMap<>();

    public CnzBarberPoleObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CNZBarberPoleSprite");
        this.mirrored = spawn.subtype() != 0;
    }

    @Override
    public CnzBarberPoleObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzBarberPoleObjectInstance(ctx.spawn());
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // loc_33376 and mirrored loc_335A8 are both in the $0003xxxx bank.
        // S3K sub_13EFC copies word 0 of the stood-on object SST into
        // Tails_CPU_interact (docs/skdisasm/sonic3k.asm:26839-26843,
        // 69350-69353, 69583-69589).
        return ROM_CODE_POINTER_HIGH_WORD;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite updatePlayer = playerEntity instanceof AbstractPlayableSprite player ? player : null;
        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        if (updatePlayer != null && !participants.contains(updatePlayer)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(updatePlayer);
            withUpdatePlayer.addAll(participants);
            participants = withUpdatePlayer;
        }

        for (PlayableEntity participant : participants) {
            if (participant instanceof AbstractPlayableSprite sprite) {
                updatePlayer(sprite);
            }
        }
    }

    private void updatePlayer(AbstractPlayableSprite player) {
        RiderState state = riders.computeIfAbsent(player, ignored -> new RiderState());
        if (state.latched) {
            continueRide(player, state);
        } else if (mirrored) {
            tryLatchMirrored(player, state);
        } else {
            tryLatchNormal(player, state);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // The visible CNZ pole is level art; this object owns only the ride logic.
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        // Delete_Sprite_If_Not_In_Range uses the native unsigned coarse-X
        // window, not the viewport-scaled placement window. A pole just behind
        // Camera_X_pos_coarse_back therefore underflows and deletes immediately
        // (sonic3k.asm:37301-37317,69348-69357).
        int coarseBack = (cameraX - 0x80) & 0xFF80;
        int distance = ((spawn.x() & 0xFF80) - coarseBack) & 0xFFFF;
        return distance > 0x280;
    }

    @Override
    public String traceDebugDetails() {
        if (riders.isEmpty()) {
            return String.format("bp mir=%s riders=0", mirrored);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("bp mir=").append(mirrored).append(" riders=").append(riders.size());
        int emitted = 0;
        for (Map.Entry<AbstractPlayableSprite, RiderState> entry : riders.entrySet()) {
            if (emitted++ >= 2) {
                sb.append(" ...");
                break;
            }
            AbstractPlayableSprite player = entry.getKey();
            RiderState state = entry.getValue();
            sb.append(String.format(
                    " p=%04X,%04X lat=%s tr=%04X.%04X rel=%02X cur=%02X/%02X in=%s out=%04X,%04X %s",
                    player.getCentreX() & 0xFFFF,
                    player.getCentreY() & 0xFFFF,
                    state.latched,
                    state.lastTrackPosition & 0xFFFF,
                    (int) (state.trackFixed & TRACK_FRACTION_MASK),
                    state.lastTrackRel & 0xFF,
                    state.lastCurve & 0xFF,
                    state.lastVisibleCurve & 0xFF,
                    state.innerTrack,
                    state.lastOutputX & 0xFFFF,
                    state.lastOutputY & 0xFFFF,
                    state.lastBranch));
        }
        return sb.toString();
    }

    @Override
    public void onUnload() {
        /*
         * Obj_CNZBarberPoleSprite ends with Delete_Sprite_If_Not_In_Range
         * (docs/skdisasm/sonic3k.asm:69348-69357), which zeros the SST via
         * Delete_Current_Sprite when the pole unloads. Tails then compares
         * the stored interact slot against that cleared SST in sub_13EFC and
         * branches to sub_13ECA's $81/air/$7F00 catch-up marker
         * (docs/skdisasm/sonic3k.asm:26816-26833, 26800-26808). Mark this
         * instance destroyed on unload so stale engine latch references see
         * the same freed-slot transition.
         */
        ObjectLifetimeOps.destroyLatched(this);
    }

    private void tryLatchNormal(AbstractPlayableSprite player, RiderState state) {
        if (player.isOnObject() && !isLatchedToSameOrientationPole(player)) {
            return;
        }

        int d0 = -player.getXRadius() + player.getCentreX() - spawn.x() + (player.isOnObject() ? 0x30 : 0x40);
        if (d0 < 0 || d0 >= (player.isOnObject() ? 0x80 : 0xA0)) {
            return;
        }
        d0 -= player.isOnObject() ? 0x51 : 0x61;

        int d1 = player.getYRadius() + player.getCentreY() - spawn.y() - d0;
        if (d1 < 0 || d1 >= 0x10 || !canLatch(player)) {
            return;
        }

        int track = player.getCentreX() - spawn.x() + 0x40;
        boolean inner = isUnsignedWordBelowAfterSubtract(
                track, player.isOnObject() ? 0x20 : 0x18, player.isOnObject() ? 0x60 : 0x70);
        latch(player, state, track, inner, 0x20);
    }

    private void tryLatchMirrored(AbstractPlayableSprite player, RiderState state) {
        if (player.isOnObject() && !isLatchedToSameOrientationPole(player)) {
            return;
        }

        int d0 = player.getXRadius() + player.getCentreX() - spawn.x() + (player.isOnObject() ? 0x50 : 0x60);
        if (d0 < 0 || d0 >= (player.isOnObject() ? 0x80 : 0xA0)) {
            return;
        }
        d0 -= player.isOnObject() ? 0x2E : 0x3E;

        int d1 = player.getYRadius() + player.getCentreY() - spawn.y() + d0;
        if (d1 < 0 || d1 >= 0x10 || !canLatch(player)) {
            return;
        }

        int track = player.getCentreX() - spawn.x() + 0x60;
        boolean inner = isUnsignedWordBelowAfterSubtract(
                track, player.isOnObject() ? 0x20 : 0x18, player.isOnObject() ? 0x60 : 0x70);
        latch(player, state, track, inner, 0xE0);
    }

    /**
     * ROM {@code loc_33472} / {@code loc_336A0} reach the on-object re-latch
     * store only after {@code movea.w interact(a1),a3; cmpi.l #loc_33376,(a3)}
     * (normal) or {@code #loc_335A8} (mirrored) confirm the currently latched
     * object is a pole sharing this pole's routine — i.e. the same orientation
     * (docs/skdisasm/sonic3k.asm:69438-69440, 69649-69651). At a CNZ2 X
     * crossing the two poles have opposite orientation, so neither steals the
     * other's rider and the player passes through the crossing pole.
     */
    private boolean isLatchedToSameOrientationPole(AbstractPlayableSprite player) {
        return player.getLatchedSolidObjectInstance() instanceof CnzBarberPoleObjectInstance pole
                && pole.mirrored == this.mirrored;
    }

    private boolean canLatch(AbstractPlayableSprite player) {
        if (player.isObjectControlled() || player.isHurt() || player.getDead()) {
            return false;
        }
        if (player.isDebugMode()) {
            /*
             * ROM sub_33392 / sub_335C4 return at tst.w (Debug_placement_mode).w
             * before storing any latch (docs/skdisasm/sonic3k.asm:69385-69386,
             * 69597-69598): poles never grab a player while debug movement mode
             * is active.
             */
            return false;
        }
        return !player.getAir() || player.getYSpeed() >= 0;
    }

    private void latch(AbstractPlayableSprite player, RiderState state, int track, boolean inner, int angle) {
        state.latched = true;
        state.trackFixed = ((long) (track & 0xFFFF) << 16) | (player.getXSubpixelRaw() & TRACK_FRACTION_MASK);
        state.innerTrack = inner;
        state.lastBranch = "latch";
        state.lastTrackPosition = track;
        state.lastTrackRel = 0;
        state.lastCurve = 0;
        state.lastVisibleCurve = 0;
        state.lastOutputX = player.getCentreX();
        state.lastOutputY = player.getCentreY();

        if (player.getAir()) {
            player.setYSpeed((short) 0);
            player.setGSpeed(player.getXSpeed());
            applyNativeTouchFloor(player);
        }
        player.setOnObject(true);
        player.setAir(false);
        player.setLatchedSolidObject(Sonic3kObjectIds.CNZ_BARBER_POLE, this);
        player.setAngle((byte) angle);
        // loc_33418/loc_334A4 select tumble set 2 for the normal pole;
        // loc_33648/loc_336D2 select set 3 for the mirrored orientation.
        player.setFlipType(mirrored ? 3 : 2);
    }

    /** Mirrors the Player_TouchFloor/Tails_TouchFloor call in sub_337D8. */
    private void applyNativeTouchFloor(AbstractPlayableSprite player) {
        int oldYRadius = player.getYRadius();
        int centreY = player.getCentreY();
        boolean wasRolling = player.getRolling();
        player.setRolling(false);
        if (wasRolling) {
            NativePositionOps.writeYPosPreserveSubpixel(
                    player, centreY + oldYRadius - player.getStandYRadius());
        }
        player.setRollingJump(false);
        player.setJumping(false);
        player.setPushing(false);
        player.setFlipAngle(0);
        player.setFlipTurned(false);
        player.setFlipsRemaining(0);
        int walkAnimation = player.resolveAnimationId(CanonicalAnimation.WALK);
        if (walkAnimation >= 0) {
            player.setAnimationId(walkAnimation);
        }
    }

    private void continueRide(AbstractPlayableSprite player, RiderState state) {
        if (player.getLatchedSolidObjectId() != Sonic3kObjectIds.CNZ_BARBER_POLE) {
            state.latched = false;
            return;
        }
        if (player.getLatchedSolidObjectInstance() != this) {
            /*
             * loc_33376 dispatches loc_334B6 only when this object's standing
             * bit is set; sub_337D8 clears the previous object's standing bit
             * before writing the new interact object (docs/skdisasm/
             * sonic3k.asm:69348-69357, 69461, 69775-69782). Engine rider
             * state is per instance, so stale pole state must not keep writing
             * the player's position after another pole became interact(a1).
             */
            state.latched = false;
            state.lastBranch = "stale";
            return;
        }

        if (player.getAir()) {
            if (player.isJumping()) {
                release(player, state);
                return;
            }
            /*
             * ROM sub_337D8 stores this object's address in interact(a1) and
             * sets Status_OnObj/clears Status_InAir as the authoritative latch.
             * The engine's generic terrain pass runs before this object update
             * and can mark the player airborne for a frame because this object
             * is not represented in SolidContacts. If the latch id is still
             * ours and no jump was taken, restore the object-owned status before
             * executing loc_334B6/loc_336E4.
             */
            player.setAir(false);
            player.setOnObject(true);
        }

        if (!state.innerTrack && Math.abs(player.getGSpeed()) < MIN_GROUND_SPEED_TO_STAY_ATTACHED) {
            player.setAir(true);
            player.setAngle((byte) rideAngle());
            release(player, state);
            return;
        }

        // ROM loc_334D2/loc_33700 adds the 8.8 x_vel contribution to the
        // packed 16.16 track coordinate with add.l. Preserve that 32-bit
        // overflow: mirrored curves legitimately cross from $FFFF.xxxx to
        // $0000.xxxx. An unbounded Java long turns the next word range check
        // into $10000 and releases the rider one object pass early.
        state.trackFixed = (state.trackFixed + (long) player.getXSpeed() * 0xC0L)
                & 0xFFFF_FFFFL;

        int trackPosition = (short) (state.trackFixed >> 16);
        state.lastTrackPosition = trackPosition;
        int d0 = mirrored
                ? player.getXRadius() + trackPosition
                : -player.getXRadius() + trackPosition;
        d0 &= 0xFFFF;
        if (d0 < 0 || d0 >= TRACK_LIMIT) {
            player.setAngle((byte) rideAngle());
            release(player, state);
            return;
        }

        if (mirrored) {
            positionMirrored(player, state, d0);
        } else {
            positionNormal(player, state, d0);
        }
    }

    private void positionNormal(AbstractPlayableSprite player, RiderState state, int trackRel) {
        int curve = clampCurveByte(trackRel - 0x10, state);
        int visibleCurve = state.innerTrack ? 0 : curve;
        int angle = (visibleCurve + visibleCurve) & 0xFF;
        player.setFlipAngle(angle);
        player.setHighPriority(curve < 0x34);

        int cos = TrigLookupTable.cosHex(angle);
        int cosShift = cos >> 4;
        int x = spawn.x() + trackRel + cosShift - 0x50 + ((player.getXRadius() * cos) >> 8);
        int y = spawn.y() - cosShift + (trackRel - 0x51) - ((player.getYRadius() * cos) >> 8);
        player.setCentreXPreserveSubpixel((short) x);
        player.setCentreYPreserveSubpixel((short) y);
        player.setAngle((byte) 0x20);
        player.setOnObject(true);
        recordPositionDebug(state, "normal", trackRel, curve, visibleCurve, x, y);
    }

    private void positionMirrored(AbstractPlayableSprite player, RiderState state, int trackRel) {
        int curve = clampCurveByte(trackRel - 0x10, state);
        int visibleCurve = state.innerTrack ? 0 : curve;
        int angle = (-(visibleCurve) + -(visibleCurve)) & 0xFF;
        player.setFlipAngle(angle);
        player.setHighPriority(curve >= 0x4C);

        int cos = TrigLookupTable.cosHex(angle);
        int cosShift = cos >> 4;
        int x = spawn.x() + trackRel - cosShift - 0x50 - ((player.getXRadius() * cos) >> 8);
        int y = spawn.y() - cosShift - (trackRel - 0x4E) - ((player.getYRadius() * cos) >> 8);
        player.setCentreXPreserveSubpixel((short) x);
        player.setCentreYPreserveSubpixel((short) y);
        player.setAngle((byte) 0xE0);
        player.setOnObject(true);
        recordPositionDebug(state, "mirrored", trackRel, curve, visibleCurve, x, y);
    }

    private void recordPositionDebug(
            RiderState state, String branch, int trackRel, int curve, int visibleCurve, int x, int y) {
        state.lastBranch = branch;
        state.lastTrackRel = trackRel;
        state.lastCurve = curve;
        state.lastVisibleCurve = visibleCurve;
        state.lastOutputX = x;
        state.lastOutputY = y;
    }

    private int rideAngle() {
        return mirrored ? 0xE0 : 0x20;
    }

    private int clampCurveByte(int curve, RiderState state) {
        if (curve < 0) {
            state.innerTrack = false;
            return 0;
        }
        if (curve >= 0x80) {
            state.innerTrack = false;
            return 0x80;
        }
        return curve;
    }

    private static boolean isUnsignedWordBelowAfterSubtract(int value, int subtract, int limit) {
        /*
         * ROM loc_333F2/loc_33472 and mirrored loc_33622/loc_336A0 use
         * subi.w + cmpi.w + bhs (docs/skdisasm/sonic3k.asm:69399-69402,
         * 69446-69450, 69610-69614, 69657-69661). Negative results are
         * 16-bit unsigned underflows and must compare as >= the limit.
         */
        return ((value - subtract) & 0xFFFF) < limit;
    }

    private void release(AbstractPlayableSprite player, RiderState state) {
        state.latched = false;
        state.lastBranch = "release";
        player.setOnObject(false);
        player.setFlipsRemaining(0);
        player.setFlipSpeed(4);
        player.setHighPriority(false);
    }

    private static final class RiderState {
        private boolean latched;
        private long trackFixed;
        private boolean innerTrack;
        private String lastBranch = "none";
        private int lastTrackPosition;
        private int lastTrackRel;
        private int lastCurve;
        private int lastVisibleCurve;
        private int lastOutputX;
        private int lastOutputY;
    }
}
