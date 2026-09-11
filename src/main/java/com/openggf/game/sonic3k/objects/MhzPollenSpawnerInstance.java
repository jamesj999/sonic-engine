package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Persistent MHZ pollen / leaf particle spawner.
 *
 * <p>ROM: {@code Obj_MHZ_Pollen_Spawner}, installed in
 * {@code Dynamic_object_RAM+object_size} during MHZ level init.
 */
public class MhzPollenSpawnerInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int TOP_SOLID_REQUIRED = 0x0C;
    private static final int MAX_PARTICLES = 0x10;
    private static final int NORMAL_SPAWN_MIN_X_SPEED = 0x0500;
    private static final int LANDING_BURST_MIN_Y_SPEED = 0x0400;
    private static final int[][] BURST_VELOCITY_TABLE = {
            {0x0080, 0x0100},
            {0x0100, 0x00C0},
            {0x0180, 0x0080},
            {-0x0080, 0x0100},
            {-0x0100, 0x00C0},
            {-0x0180, 0x0080}
    };

    private int playerOneStoredYVelocity;
    private int playerTwoStoredYVelocity;

    public MhzPollenSpawnerInstance(ObjectSpawn spawn) {
        super(spawn, "MHZ Pollen Spawner");
    }

    public MhzPollenSpawnerInstance() {
        this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
    }

    @Override
    public MhzPollenSpawnerInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MhzPollenSpawnerInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        MhzZoneRuntimeState state = currentMhzState();
        if (state == null || state.pollenParticleCount() >= MAX_PARTICLES) {
            return;
        }

        playerOneStoredYVelocity = processPlayer(vIntRunCount, player, playerOneStoredYVelocity, state);
        PlayableEntity nativeP2 = nativeP2(player);
        if (nativeP2 != null && sidekickRenderFlagsOnScreen(nativeP2)) {
            playerTwoStoredYVelocity = processPlayer(vIntRunCount, nativeP2, playerTwoStoredYVelocity, state);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible controller object.
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    public int getPlayerOneStoredYVelocity() {
        return playerOneStoredYVelocity;
    }

    public int getPlayerTwoStoredYVelocity() {
        return playerTwoStoredYVelocity;
    }

    private boolean sidekickRenderFlagsOnScreen(PlayableEntity sidekick) {
        if (!(sidekick instanceof AbstractPlayableSprite sprite) || !sprite.hasRenderFlagOnScreenState()) {
            return true;
        }
        return sprite.isRenderFlagOnScreen();
    }

    private int processPlayer(
            int vIntRunCount,
            PlayableEntity player,
            int storedYVelocity,
            MhzZoneRuntimeState state) {
        if (!(player instanceof AbstractPlayableSprite sprite)) {
            return storedYVelocity;
        }
        if ((sprite.getTopSolidBit() & 0xFF) != TOP_SOLID_REQUIRED) {
            return storedYVelocity;
        }
        if (sprite.getAir()) {
            return sprite.getYSpeed();
        }

        int spawnGateRandom = services().rng().nextRaw();
        if ((spawnGateRandom & 3) != 0) {
            return storedYVelocity;
        }

        int previousYVelocity = storedYVelocity;
        storedYVelocity = 0;
        if ((previousYVelocity & 0xFFFF) >= LANDING_BURST_MIN_Y_SPEED && !sprite.isOnObject()) {
            spawnLandingBurst(sprite, state);
            return storedYVelocity;
        }

        spawnNormalTrail(sprite, state, spawnGateRandom);
        return storedYVelocity;
    }

    private PlayableEntity nativeP2(PlayableEntity updatePlayer) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> updatePlayer, query::sidekicks).nativeP2OrNull();
    }

    private void spawnNormalTrail(AbstractPlayableSprite player, MhzZoneRuntimeState state, int spawnGateRandom) {
        int absXVelocity = Math.abs(player.getXSpeed());
        if (absXVelocity < NORMAL_SPAWN_MIN_X_SPEED) {
            return;
        }

        state.reservePollenParticleAfterSpawnerGate();
        int yVelocity = -(((absXVelocity - NORMAL_SPAWN_MIN_X_SPEED) >> 4) + 0x0200);
        int gravityStep = ((spawnGateRandom >>> 16) & 3) + 2;
        spawnChild(() -> new MhzPollenParticleInstance(
                player.getCentreX() & 0xFFFF,
                (player.getCentreY() + 0x10) & 0xFFFF,
                0,
                yVelocity,
                gravityStep,
                0,
                selectArtMode(state)));
    }

    private void spawnLandingBurst(AbstractPlayableSprite player, MhzZoneRuntimeState state) {
        for (int[] baseVelocity : BURST_VELOCITY_TABLE) {
            state.reservePollenParticleAfterSpawnerGate();
            int raw = services().rng().nextRaw();
            int xVelocity = ((raw & 0x01FF) - 0x0100) + baseVelocity[0];
            int yVelocity = -((((raw >>> 16) & 0x00FF) + 0x0100) - baseVelocity[1]);
            int gravityStep = (xVelocity & 3) + 2;
            // ROM loc_3DAD6 writes the full word `move.w d0,angle(a1)` before
            // masking d0 for x_vel/y_vel; the object's byte-sized angle field
            // is the first (high) byte of that big-endian word write, so the
            // seed angle is the high byte of the low word, not the low byte
            // (docs/skdisasm/sonic3k.asm ~81706).
            int angle = (raw >>> 8) & 0xFF;
            spawnChild(() -> new MhzPollenParticleInstance(
                    player.getCentreX() & 0xFFFF,
                    (player.getCentreY() + 0x18) & 0xFFFF,
                    xVelocity,
                    yVelocity,
                    gravityStep,
                    angle,
                    selectArtMode(state),
                    true));
        }
    }

    private MhzPollenParticleInstance.ArtMode selectArtMode(MhzZoneRuntimeState state) {
        if (!state.isSeasonFlagSet()) {
            return MhzPollenParticleInstance.ArtMode.POLLEN;
        }
        return state.nextPollenParticleUsesBigLeaf()
                ? MhzPollenParticleInstance.ArtMode.BIG_LEAF
                : MhzPollenParticleInstance.ArtMode.POLLEN;
    }

    private MhzZoneRuntimeState currentMhzState() {
        return services().zoneRuntimeState() instanceof MhzZoneRuntimeState state ? state : null;
    }
}
