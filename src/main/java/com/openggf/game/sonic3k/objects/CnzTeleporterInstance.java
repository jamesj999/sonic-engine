package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * CNZ Act 2 Knuckles teleporter cutscene owner.
 *
 * <p>ROM anchors: {@code Obj_CNZTeleporter} and {@code Obj_CNZTeleporterMain}.
 *
 * <p>The verified ROM findings for Task 8 are intentionally documented inline
 * because this object is doing cutscene glue rather than normal gameplay:
 * <ul>
 *   <li>Arm once the player reaches {@code x >= $4A38}</li>
 *   <li>If airborne and past {@code $4A40}, clamp back to {@code $4A40}</li>
 *   <li>Zero {@code x_vel} and {@code ground_vel}, lock control, clear logical
 *   input, queue {@code ArtKosM_CNZTeleport}, and patch palette line 2</li>
 *   <li>Wait for the queued art load to drain and for Knuckles to be grounded</li>
 *   <li>Fix both X positions to {@code $4A40}, spawn the shared
 *   {@code Obj_TeleporterBeam} at {@code y=$0A38}, play {@code sfx_Charging},
 *   and watch the beam progress thresholds</li>
 * </ul>
 *
 * <p>The engine's synchronous KosM load path exposes queue completion through
 * the dedicated renderer's readiness state. Palette overrides are composed
 * through {@code PaletteOwnershipRegistry}, preserving the ROM's immediate
 * CRAM patch without bypassing other CNZ palette writers.
 */
public final class CnzTeleporterInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    /**
     * Verified arming threshold from {@code Obj_CNZTeleporter}.
     */
    private static final int ARM_X_THRESHOLD = 0x4A38;

    /**
     * Verified beam/transport X used by both the player and the shared beam.
     */
    private static final int TELEPORT_X = 0x4A40;

    /**
     * Verified beam spawn Y from {@code Obj_CNZTeleporterMain}.
     */
    private static final int TELEPORT_BEAM_Y = 0x0A38;

    /**
     * Verified late-route camera clamp published by the CNZ event script.
     */
    private static final int ROUTE_CAMERA_MIN_X = 0x4750;
    private static final int ROUTE_CAMERA_MAX_X = 0x48E0;

    private static final int[] TELEPORT_PALETTE_INDICES = {1, 2, 8, 10};
    private static final int[] TELEPORT_PALETTE_WORDS = {0x0EEE, 0x00EC, 0x0EA2, 0x0E80};

    private int centreY;

    private boolean armed;
    private boolean paletteLine2Patched;
    private boolean teleportArtQueued;
    private boolean beamSpawned;
    private boolean playerCaptured;
    private boolean playerHidden;
    private CnzTeleporterBeamInstance beam;

    public CnzTeleporterInstance(ObjectSpawn spawn) {
        super(spawn, "CNZTeleporter");
        this.centreY = spawn.y();
    }

    @Override
    public int getX() {
        return TELEPORT_X;
    }

    @Override
    public int getY() {
        return centreY;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }

        boolean routeAlreadyActive = isKnucklesRouteAlreadyActive();
        if (!routeAlreadyActive) {
            return;
        }

        /**
         * The teleporter route must already have been published through CNZ
         * event state. Once that explicit seam is active, the object mirrors the
         * late-route camera clamp locally so the cutscene stays locked to the
         * teleporter lane while the player/beam choreography runs.
         */
        services().camera().setMinX((short) ROUTE_CAMERA_MIN_X);
        services().camera().setMaxX((short) ROUTE_CAMERA_MAX_X);

        if (!armed && (player.getCentreX() >= ARM_X_THRESHOLD || routeAlreadyActive)) {
            armTeleporter(player, routeAlreadyActive);
            // ROM replaces the code pointer with Obj_CNZTeleporterMain and
            // returns; Kos_modules_left is first polled on the following frame.
            return;
        }
        if (!armed) {
            return;
        }

        if (!beamSpawned && isTeleportArtReady() && !player.getAir()) {
            spawnBeamAndCharge(player);
        }
        if (beamSpawned) {
            watchBeamProgress(player);
        }
    }

    /**
     * Verified {@code Obj_CNZTeleporter} arming sequence.
     *
     * <p>The airborne overshoot clamp only applies once the player has already
     * crossed the teleporter beam's X position. The ROM then clears horizontal
     * motion, locks control, zeroes logical input, queues the dedicated KosM
     * art, and patches palette line 2 before handing off to the main routine.
     */
    private void armTeleporter(AbstractPlayableSprite player, boolean routeAlreadyActive) {
        armed = true;

        /**
         * ROM: the arming frame snaps airborne overshoot back to {@code $4A40}.
         *
         * <p>S3K runs player physics before object execution. If the route was
         * already activated through the explicit CNZ event seam, the player may
         * have drifted slightly left of {@code $4A40} by the time this object
         * gets its first frame. The teleporter still owes the same beam-aligned
         * X snap in that case, so route activation widens the clamp condition
         * beyond the raw local {@code x > $4A40} check.
         */
        if (player.getAir() && (player.getCentreX() > TELEPORT_X || routeAlreadyActive)) {
            player.setCentreX((short) TELEPORT_X);
        }

        player.setXSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setControlLocked(true);

        /**
         * The engine does not expose the raw logical-input buffer, so Task 8
         * mirrors the ROM's "clear Ctrl_1_logical" effect by ensuring the
         * player remains control-locked until the beam explicitly takes over.
         */
        teleportArtQueued = true;
        cnzArtProvider().queueCnzTeleporterArt();
        applyTeleportPalettePatch();
        paletteLine2Patched = true;
    }

    /** ROM {@code Kos_modules_left == 0}, represented by provider queue completion. */
    private boolean isTeleportArtReady() {
        return cnzArtProvider().isCnzTeleporterArtComplete();
    }

    private Sonic3kObjectArtProvider cnzArtProvider() {
        if (services().renderManager().getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            return provider;
        }
        throw new IllegalStateException("CNZ teleporter requires the S3K object-art provider");
    }

    private void applyTeleportPalettePatch() {
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.CNZ_TELEPORTER,
                S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                1,
                TELEPORT_PALETTE_INDICES,
                TELEPORT_PALETTE_WORDS);
        S3kPaletteWriteSupport.resolvePendingWritesNow(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager());
    }

    /**
     * Verified {@code Obj_CNZTeleporterMain} handoff once queued art is ready
     * and Knuckles has landed.
     */
    private void spawnBeamAndCharge(AbstractPlayableSprite player) {
        beamSpawned = true;
        player.setCentreX((short) TELEPORT_X);
        beam = spawnChild(() -> new CnzTeleporterBeamInstance(
                new ObjectSpawn(TELEPORT_X, TELEPORT_BEAM_Y, 0, 0, 0, false, 0)));
        services().playSfx(Sonic3kSfx.CHARGING.id);
        com.openggf.game.sonic3k.events.S3kCnzEventWriteSupport.markTeleporterBeamSpawned(services());
    }

    /**
     * Observes the shared beam thresholds exported by {@link CnzTeleporterBeamInstance}.
     *
     * <p>Verified parent reactions:
     * <ul>
     *   <li>{@code counter == 8}: take full object control and force rolling</li>
     *   <li>{@code counter >= $18}: hide the player, play transporter SFX, and
     *   keep the route scroll-locked</li>
     * </ul>
     */
    private void watchBeamProgress(AbstractPlayableSprite player) {
        if (beam == null || beam.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        int beamCounter = beam.getBeamCounter();
        if (!playerCaptured && beamCounter == CnzTeleporterBeamInstance.PLAYER_CAPTURE_COUNTER) {
            playerCaptured = true;
            ObjectControlState.nativeBit7FullControl().applyTo(player);
            player.setControlLocked(true);
            player.setRolling(true);
            player.setCentreX((short) TELEPORT_X);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
        }

        if (!playerHidden && beamCounter >= CnzTeleporterBeamInstance.PLAYER_HIDE_COUNTER) {
            playerHidden = true;
            player.setHidden(true);
            services().playSfx(Sonic3kSfx.TRANSPORTER.id);

            /**
             * The verified ROM note says the route becomes scroll-locked once
             * the beam reaches {@code >= $18}. Task 8 keeps the late-route CNZ
             * clamp active here as the minimal explicit lock without claiming the
             * rest of the later cutscene choreography.
             */
            services().camera().setMinX((short) ROUTE_CAMERA_MIN_X);
            services().camera().setMaxX((short) ROUTE_CAMERA_MAX_X);
        }
    }

    /**
     * Reads the already-published CNZ route state.
     *
     * <p>This is the explicit object/event dependency Task 8 needs. The route
     * can already be active before the teleporter object gets a frame, so the
     * teleporter must respect the CNZ event surface instead of relying only on
     * its local X threshold.
     */
    private boolean isKnucklesRouteAlreadyActive() {
        Object provider = services().levelEventProvider();
        if (provider instanceof Sonic3kLevelEventManager manager) {
            Sonic3kCNZEvents events = manager.getCnzEvents();
            return events != null && events.isKnucklesTeleporterRouteActive();
        }
        return false;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!teleportArtQueued && !paletteLine2Patched) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_TELEPORTER);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(0, TELEPORT_X, centreY, false, false);
    }
}
