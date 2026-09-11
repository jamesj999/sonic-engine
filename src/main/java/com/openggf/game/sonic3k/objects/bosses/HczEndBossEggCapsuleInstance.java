package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.game.sonic3k.objects.S3kBossExplosionController;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.EggPrisonAnimalInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * Standard ground-based egg capsule for HCZ2 (and other S3K zones).
 *
 * <p>ROM reference: Obj_EggCapsule (sonic3k.asm line 181496).
 * The capsule sits at a fixed position on the ground with the button
 * on TOP at offset (0, -0x24). The player must STAND on the button
 * (SolidObjectFull) to open the capsule.
 *
 * <p>On button press:
 * <ol>
 *   <li>Changes to open frame (mapping_frame = 1)</li>
 *   <li>Spawns boss explosions and animals</li>
 *   <li>Sets 64-frame timer</li>
 *   <li>After timer + player on ground: spawns results screen</li>
 * </ol>
 *
 * <p>ROM collision: SolidObjectFull with d1=$2B, d2=$18, d3=$18.
 * Button detection: SolidObjectFull with small hitbox d1=$1B, d2=4, d3=6.
 */
public class HczEndBossEggCapsuleInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SpawnCoordinateRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_EggCapsule} is installed from the S3K object pointer table at
     * {@code $00086540} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:181501).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0008}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0008;
    }

    private static final Logger LOG = Logger.getLogger(HczEndBossEggCapsuleInstance.class.getName());

    private static final int OBJECT_ID = Sonic3kObjectIds.EGG_CAPSULE;
    private static final int PRIORITY = 5;

    // ROM: SolidObjectFull parameters (sonic3k.asm:181502-181506)
    private static final int SOLID_HALF_WIDTH = 0x2B;
    private static final int SOLID_HALF_HEIGHT = 0x18;

    // ROM: Button on TOP at offset (0, -0x24) from capsule centre
    private static final int BUTTON_Y_OFFSET = -0x24;

    // Post-open delay before results (ROM: 64-frame timer)
    private static final int POST_OPEN_DELAY = 64;

    // Animal spawn count (ROM: 14 animals)
    private static final int ANIMAL_COUNT = 14;

    // Fixed position
    private int fixedX;
    private int fixedY;

    // State
    private int mappingFrame;
    private boolean opened;
    private boolean resultsStarted;
    private boolean geyserSpawned;
    private boolean geyserHandoffMappingCaptured;
    private int mainGeyserHandoffMappingFrame;
    private int sidekickGeyserHandoffMappingFrame;
    private int postOpenTimer;
    private boolean buttonSpawned;
    private boolean buttonPressed;
    private boolean tailsEndingPoseApplied;
    private boolean mainEndingPosePending;

    // Explosion controller (spawned when capsule opens)
    private S3kBossExplosionController explosionController;

    public HczEndBossEggCapsuleInstance(int x, int y) {
        super(new ObjectSpawn(x, y, OBJECT_ID, 0, 0, false, 0), "HCZEggCapsule");
        this.fixedX = x;
        this.fixedY = y;
    }

    private HczEndBossEggCapsuleInstance() {
        this(0, 0);
    }

    // ===== Position / lifecycle =====

    @Override
    public int getX() {
        return fixedX;
    }

    @Override
    public int getY() {
        return fixedY;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ===== Solid collision (ROM: SolidObjectFull d1=$2B, d2=$18, d3=$18) =====

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        // ROM: SolidObjectFull is called every frame unconditionally (line 181502-181506),
        // before the routine dispatch. The capsule body remains solid throughout the
        // entire sequence — before, during, and after opening.
        return true;
    }

    // ===== Update =====

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!buttonSpawned) {
            buttonSpawned = true;
            spawnChild(() -> new HczEndBossEggCapsuleButton(
                    this, fixedX, fixedY + BUTTON_Y_OFFSET));
        }
        if (!opened) {
            // The button child publishes its ROM standing-bit result after this
            // parent slot has run. Consume that signal on the next parent entry,
            // matching loc_865D0/sub_865DE (sonic3k.asm:181590-181630).
            if (buttonPressed) {
                openCapsule();
            }
        } else if (!resultsStarted) {
            // Tick explosion controller
            if (explosionController != null && !explosionController.isFinished()) {
                explosionController.tick();
                spawnPendingExplosions();
            }

            // Wait for post-open delay then start results when player is on ground.
            if (postOpenTimer > 0) {
                postOpenTimer--;
            }
            if (postOpenTimer == 0
                    && playerEntity instanceof AbstractPlayableSprite player
                    && !player.getAir()) {
                startResults(player);
            }
        } else if (!geyserSpawned) {
            if (resultsStarted && services().gameState().isEndOfLevelActive()) {
                captureGeyserHandoffMappings();
            }
            if (mainEndingPosePending
                    && playerEntity instanceof AbstractPlayableSprite player) {
                mainEndingPosePending = false;
                lockForResults(player);
            }
            advanceTailsEndingPoseCheck();

            // ROM: loc_6B154 — Lock camera from scrolling left each frame
            // and wait for results to COMPLETE before spawning the geyser.
            var camera = services().camera();
            camera.setMinX((short) camera.getX());

            if (!services().gameState().isEndOfLevelActive()) {
                spawnGeyserCutscene();
            }
        }
    }

    void signalButtonPressed() {
        buttonPressed = true;
    }

    // ===== Capsule opening =====

    /**
     * ROM: sub_865DE equivalent — capsule opens, spawns explosions and animals.
     */
    private void openCapsule() {
        if (opened) return;
        opened = true;
        mappingFrame = 1;  // ROM: move.b #1,mapping_frame(a0) — open lid
        postOpenTimer = POST_OPEN_DELAY;

        // ROM sub_865DE sets Ctrl_2_locked after the capsule's button child
        // signals the parent (sonic3k.asm:181548-181555). The capsule runs
        // after Player_2's slot, so the current CPU step has already happened;
        // the signed lock suppresses Tails_CPU_Control beginning next frame.
        if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick
                && sidekick.getCpuController() != null) {
            sidekick.getCpuController().setController2SignedLocked(true);
        }

        // Play explosion SFX
        try {
            services().playSfx(Sonic3kSfx.EXPLODE.id);
        } catch (Exception e) {
            // Ignore audio errors
        }

        // Spawn boss explosion controller (compact type for capsule, subtype 3)
        explosionController = new S3kBossExplosionController(fixedX, fixedY, 3, services().rng());

        // Spawn animals
        spawnAnimals();
    }

    /**
     * Drains pending explosions from the controller into dynamic children.
     */
    private void spawnPendingExplosions() {
        if (explosionController == null) return;
        var pending = explosionController.drainPendingExplosions();
        for (var entry : pending) {
            if (entry.playSfx()) {
                try {
                    services().playSfx(Sonic3kSfx.EXPLODE.id);
                } catch (Exception e) {
                    // Ignore audio errors
                }
            }
            spawnChild(() -> new S3kBossExplosionChild(entry.x(), entry.y()));
        }
    }

    /**
     * ROM: Spawn animals that burst out of the capsule.
     * Each animal gets a staggered delay so they pop out in sequence.
     *
     * <p>Must use {@code spawnFreeChild()} (not raw {@code addDynamicObject()}):
     * {@link EggPrisonAnimalInstance}'s constructor caches its sprite renderer via
     * {@code getRenderManager()}, which resolves through per-instance services and
     * returns null unless the construction context is set. {@code spawnFreeChild()}
     * sets that context (and keeps the same FindFreeObj/{@code addDynamicObject}
     * slot semantics); a raw {@code addDynamicObject()} leaves the renderer null and
     * the released animals render invisibly.
     */
    private void spawnAnimals() {
        for (int i = 0; i < ANIMAL_COUNT; i++) {
            int animalX = fixedX + (i % 2 == 0 ? -(8 + i * 4) : (8 + i * 4));
            int animalY = fixedY - 16;  // Animals pop up from top of capsule
            final int delay = i * 4;  // Staggered: 0, 4, 8, 12, ...
            final ObjectSpawn spawn = new ObjectSpawn(animalX, animalY, 0x28, 0, 0, false, 0);
            final int artVariant = services().rng().nextBits(1);
            spawnFreeChild(() -> new EggPrisonAnimalInstance(spawn, delay, artVariant));
        }
    }

    // ===== Results =====

    private void startResults(AbstractPlayableSprite player) {
        if (resultsStarted) return;
        resultsStarted = true;
        services().gameState().setEndOfLevelActive(true);
        // sub_868F8 changes the parent to routine 6 after the player slot has
        // already animated. Defer the engine-side pose publication to the next
        // parent entry so that this dispatch retains the player's current
        // mapping; the following player tick then observes the native Victory
        // write and restarts its script (sonic3k.asm:181586-181590,
        // 181900-181918).
        mainEndingPosePending = true;
        // sub_868F8 calls AllocateObject, which scans from the beginning of
        // Dynamic_object_RAM rather than allocating after the capsule. The
        // result owner therefore takes the lowest available SST and reaches
        // Obj_LevelResultsInit on the following Process_Sprites pass.
        spawnFreeChild(
                () -> new HczEndBossResultsScreenObjectInstance(
                        getPlayerCharacter(), services().currentAct()));
    }

    private void advanceTailsEndingPoseCheck() {
        if (tailsEndingPoseApplied) {
            return;
        }
        if (!(services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick)
                || sidekick.isPreventTailsRespawn()
                || sidekick.getAir()
                || sidekick.getDead()) {
            return;
        }

        // Check_TailsEndPose clears Ctrl_2_locked immediately before tail-calling
        // Set_PlayerEndingPose (sonic3k.asm:181919-181940).
        tailsEndingPoseApplied = true;
        if (sidekick.getCpuController() != null) {
            sidekick.getCpuController().queueNativeEndingPoseForNextPlayerSlot();
        }
    }

    private void lockForResults(AbstractPlayableSprite sprite) {
        ObjectControlState.nativeBit7FullControl().applyTo(sprite);
        // Set_PlayerEndingPose does not write Ctrl_1_locked/Ctrl_2_locked.
        sprite.setControlLocked(false);
        sprite.setSpindash(false);
        sprite.setPushing(false);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAnimationId(Sonic3kAnimationIds.VICTORY);
    }

    private PlayerCharacter getPlayerCharacter() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration());
    }

    // ===== Geyser cutscene (delegated from HczEndBossInstance) =====

    /**
     * ROM: loc_6B154 after results complete — spawn geyser cutscene.
     *
     * <p>Originally this logic lived in {@code HczEndBossInstance.updateCapsuleWait()},
     * but the boss flies off-screen during the flee sequence and gets culled by the
     * object manager's out-of-range check before it can poll the results-complete flag.
     * The capsule is persistent and remains on-screen, so it handles this instead.
     *
     * <p>ROM: Geyser X = Player_1 X position; geyser spawn Y = Camera_Y + $130.
     */
    private void spawnGeyserCutscene() {
        geyserSpawned = true;

        // loc_6B154 restores both players after Obj_LevelResults has deleted
        // itself. The next capsule dispatch is the first owner pass after that
        // deletion, so publish the Wait pose and signed locks here; doing it
        // from the result owner's exit would make the trace one player pass
        // early (sonic3k.asm:141054-141072).
        restorePlayersForGeyserHandoff();

        // Reset camera Y min to allow full vertical scrolling
        services().camera().setMinY((short) 0);

        // Resolve player X for geyser column (ROM: move.w Player_1+x_pos,d0)
        var camera = services().camera();
        AbstractPlayableSprite player =
                (camera.getFocusedSprite() instanceof AbstractPlayableSprite aps) ? aps : null;
        int geyserX = (player != null) ? player.getCentreX() : fixedX;
        int geyserY = camera.getY() + 0x130;

        // Spawn geyser cutscene as a dynamic object (ROM: loc_6B7BC)
        spawnChild(() -> HczEndBossGeyserCutscene.createWithQueuedArt(geyserX, geyserY));
        LOG.info("HCZ Egg Capsule: results complete, geyser cutscene spawned at X="
                + geyserX + " Y=" + geyserY);
    }

    private void restorePlayersForGeyserHandoff() {
        if (services().playerQuery().mainPlayerOrNull() instanceof AbstractPlayableSprite main) {
            int mappingFrame = geyserHandoffMappingCaptured
                    ? mainGeyserHandoffMappingFrame : main.getMappingFrame();
            restoreForGeyserHandoff(main, mappingFrame);
            main.setControlLocked(true);
        }
        if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick) {
            int mappingFrame = geyserHandoffMappingCaptured
                    ? sidekickGeyserHandoffMappingFrame : sidekick.getMappingFrame();
            restoreForGeyserHandoff(sidekick, mappingFrame);
            if (sidekick.getCpuController() != null) {
                sidekick.getCpuController().setController2SignedLocked(true);
                sidekick.getCpuController().clearController2LogicalLatch();
            }
        }
    }

    private void captureGeyserHandoffMappings() {
        if (services().playerQuery().mainPlayerOrNull() instanceof AbstractPlayableSprite main) {
            mainGeyserHandoffMappingFrame = main.getMappingFrame();
        }
        if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick) {
            sidekickGeyserHandoffMappingFrame = sidekick.getMappingFrame();
        }
        geyserHandoffMappingCaptured = true;
    }

    private static void restoreForGeyserHandoff(
            AbstractPlayableSprite player, int mappingFrame) {
        ObjectControlState.none().applyTo(player);
        player.setAir(false);
        player.setForcedAnimationId(-1);
        player.setAnimationId(Sonic3kAnimationIds.WAIT);
        player.getAnimationManager().publishPreviousAnimationId(Sonic3kAnimationIds.WAIT.id());
        player.setAnimationFrameIndex(0);
        player.setAnimationTick(0);
        player.setMappingFrame(mappingFrame);
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.EGG_CAPSULE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // ROM: Ground-based capsule — draw body upright (no vFlip)
        renderer.drawFrameIndex(mappingFrame, fixedX, fixedY, false, false);

        // ROM: Button on TOP at offset (0, -0x24). Draw button frame upright.
        int buttonFrame = opened ? 0xC : 0x5;
        int buttonY = fixedY + BUTTON_Y_OFFSET;
        renderer.drawFrameIndex(buttonFrame, fixedX, buttonY, false, false);
    }

    private static final class HczEndBossResultsScreenObjectInstance
            extends S3kResultsScreenObjectInstance {
        private HczEndBossResultsScreenObjectInstance(PlayerCharacter character, int act) {
            super(character, act);
        }

        private HczEndBossResultsScreenObjectInstance() {
            super(true);
        }

        @Override
        protected boolean skipsSameFrameUpdateAfterSpawn() {
            // sub_868F8 uses AllocateObject for Obj_LevelResults. Keep its
            // first Obj_LevelResultsInit dispatch on the following
            // Process_Sprites pass (sonic3k.asm:181900-181918,
            // 182027-182046).
            return true;
        }

        @Override
        protected boolean shouldDeferInitialResultsArtLoadDispatch() {
            // Obj_LevelResultsInit follows the capsule's AllocateObject path
            // across the next hardware service boundary in HCZ2, so the
            // three Queue_Kos_Module calls begin on the following dispatch
            // (sonic3k.asm:182027-182046, 62542-62598).
            return true;
        }

        @Override
        protected void onExitReady() {
            super.onExitReady();

            // The signed controller locks are visible before the capsule's
            // following dispatch. Keep them in the results-owner pass so the
            // player slots cannot take an extra CPU step; the pose itself is
            // published by the capsule on the next pass.
            if (services().playerQuery().mainPlayerOrNull() instanceof AbstractPlayableSprite main) {
                main.setControlLocked(true);
            }
            if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick
                    && sidekick.getCpuController() != null) {
                sidekick.getCpuController().setController2SignedLocked(true);
                sidekick.getCpuController().clearController2LogicalLatch();
            }
        }

        @Override
        protected boolean shouldRestoreCameraBoundsOnExit(int zone, int act) {
            // loc_6B154 retains the boss-arena X lock for the geyser handoff.
            return false;
        }

        @Override
        public HczEndBossResultsScreenObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return ObjectConstructionContext.construct(ctx.objectServices(),
                    HczEndBossResultsScreenObjectInstance::new);
        }
    }
}
