package com.openggf.level.objects;


import com.openggf.camera.Camera;
import com.openggf.game.CollisionModel;
import com.openggf.game.GameStateManager;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.ObjectInteractionRules;
import com.openggf.game.rules.PlayerCapabilityRules;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PlayerStandingState;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.rings.LostRingObjectInstance;
import com.openggf.level.spawn.AbstractPlacementManager;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.game.PlayableEntity;
import com.openggf.game.DamageCause;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.game.GroundMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

final class ObjectTouchResponseController {
    private static final Logger LOGGER = Logger.getLogger(ObjectTouchResponseController.class.getName());
    private final ObjectManager objectManager;
    private final TouchResponseTable table;
    // Double-buffer pattern: swap buffers instead of allocating new sets each frame
    private final Set<ObjectInstance> bufferA = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ObjectInstance> bufferB = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<ObjectInstance> overlapping = bufferA;
    private Set<ObjectInstance> building = bufferB;
    // Per-sidekick overlap tracking (each sidekick needs independent edge detection)
    private static class OverlapBufferPair {
        final Set<ObjectInstance> bufferA = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<ObjectInstance> bufferB = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ObjectInstance> overlapping = bufferA;
        Set<ObjectInstance> building = bufferB;

        void swap() {
            Set<ObjectInstance> temp = overlapping;
            overlapping = building;
            building = temp;
        }

        void reset() {
            bufferA.clear();
            bufferB.clear();
            overlapping = bufferA;
            building = bufferB;
        }
    }

    private final Map<PlayableEntity, OverlapBufferPair> sidekickOverlaps = new IdentityHashMap<>();
    private final Map<PlayableEntity, Integer> lastSpecialTouchFrame = new IdentityHashMap<>();
    private final TouchResponseDebugState debugState = new TouchResponseDebugState();
    private static final int SHIELD_TOUCH_HALF_SIZE = 0x18;
    private static final int SHIELD_TOUCH_SIZE = SHIELD_TOUCH_HALF_SIZE * 2;
    private static final int SHIELD_REACTION_BOUNCE_BIT = 1 << 3;
    /**
     * ROM Touch_ChkValue lost-ring re-collection gate (s2.asm:85196-85219): a spilled ring
     * is only collected when {@code invulnerable_time < 90}.
     */
    private static final int LOST_RING_INVULNERABLE_THRESHOLD = 90;
    private int currentFrameCounter;
    private boolean instaShieldActive;
    private PlayableEntity currentPlayer;

    ObjectTouchResponseController(ObjectManager objectManager, TouchResponseTable table) {
        this.objectManager = objectManager;
        this.table = table;
    }

    void reset() {
        bufferA.clear();
        bufferB.clear();
        overlapping = bufferA;
        building = bufferB;
        sidekickOverlaps.clear();
        lastSpecialTouchFrame.clear();
        currentFrameCounter = 0;
    }

    boolean hadSpecialTouchThisFrame(PlayableEntity player) {
        return player != null
                && lastSpecialTouchFrame.getOrDefault(player, Integer.MIN_VALUE) == currentFrameCounter;
    }

    /**
     * Captures the double-buffer overlap state for rewind. Encodes set
     * content as slot indices (object Java refs change identity on
     * restore) and the buffer-swap parity as a boolean. See
     * {@link com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.TouchResponseOverlapState}
     * for the cross-frame-state rationale.
     */
    com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.TouchResponseOverlapState
            captureRewindState() {
        int[] mainOver = collectSlotIndices(overlapping);
        int[] mainBuild = collectSlotIndices(building);
        boolean mainSwapped = (overlapping == bufferB);
        List<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.SidekickOverlapEntry>
                sidekickEntries = new ArrayList<>(sidekickOverlaps.size());
        for (var entry : sidekickOverlaps.entrySet()) {
            PlayableEntity sk = entry.getKey();
            OverlapBufferPair pair = entry.getValue();
            String code = sk instanceof com.openggf.sprites.Sprite s ? s.getCode() : null;
            if (code == null) continue;
            int[] overlappingSlots = collectSlotIndices(pair.overlapping);
            int[] buildingSlots = collectSlotIndices(pair.building);
            if (overlappingSlots.length == 0 && buildingSlots.length == 0) {
                continue;
            }
            sidekickEntries.add(
                    new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.SidekickOverlapEntry(
                            code,
                            overlappingSlots,
                            buildingSlots,
                            pair.overlapping == pair.bufferB));
        }
        return new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.TouchResponseOverlapState(
                mainOver, mainBuild, mainSwapped, sidekickEntries);
    }

    /**
     * Restores the double-buffer overlap state. Must run AFTER
     * {@code ObjectManager}'s slot/dynamic-object restore so slot lookup
     * resolves to the post-restore live instances.
     */
    void restoreRewindState(
            com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.TouchResponseOverlapState state) {
        bufferA.clear();
        bufferB.clear();
        overlapping = bufferA;
        building = bufferB;
        sidekickOverlaps.clear();
        if (state == null) return;
        populateBuffer(bufferA,
                state.mainParitySwapped() ? state.mainBuildingSlotIndices() : state.mainOverlappingSlotIndices());
        populateBuffer(bufferB,
                state.mainParitySwapped() ? state.mainOverlappingSlotIndices() : state.mainBuildingSlotIndices());
        if (state.mainParitySwapped()) {
            overlapping = bufferB;
            building = bufferA;
        }
        // Resolve sidekick PlayableEntity refs from the injected live SpriteManager.
        com.openggf.sprites.managers.SpriteManager sm = objectManager.services().spriteManager();
        for (var skEntry : state.sidekickEntries()) {
            com.openggf.sprites.Sprite sprite = sm.getSprite(skEntry.sidekickCode());
            if (!(sprite instanceof PlayableEntity pe)) continue;
            OverlapBufferPair pair = new OverlapBufferPair();
            populateBuffer(pair.bufferA,
                    skEntry.paritySwapped() ? skEntry.buildingSlotIndices() : skEntry.overlappingSlotIndices());
            populateBuffer(pair.bufferB,
                    skEntry.paritySwapped() ? skEntry.overlappingSlotIndices() : skEntry.buildingSlotIndices());
            if (skEntry.paritySwapped()) {
                pair.overlapping = pair.bufferB;
                pair.building = pair.bufferA;
            }
            sidekickOverlaps.put(pe, pair);
        }
    }

    private static int[] collectSlotIndices(Set<ObjectInstance> set) {
        int[] result = new int[set.size()];
        int n = 0;
        for (ObjectInstance inst : set) {
            if (inst instanceof AbstractObjectInstance aoi && aoi.getSlotIndex() >= 0) {
                result[n++] = aoi.getSlotIndex();
            }
        }
        return n == result.length ? result : Arrays.copyOf(result, n);
    }

    private void populateBuffer(Set<ObjectInstance> buffer, int[] slotIndices) {
        if (slotIndices == null || slotIndices.length == 0) return;
        // Build a slot -> instance lookup once, since each Set may have
        // multiple slots to resolve.
        Map<Integer, ObjectInstance> bySlot = new java.util.HashMap<>();
        for (ObjectInstance inst : objectManager.getActiveObjects()) {
            if (inst instanceof AbstractObjectInstance aoi && aoi.getSlotIndex() >= 0) {
                bySlot.put(aoi.getSlotIndex(), inst);
            }
        }
        for (int slot : slotIndices) {
            ObjectInstance inst = bySlot.get(slot);
            if (inst != null) {
                buffer.add(inst);
            }
        }
    }

    void update(PlayableEntity player, int frameCounter) {
        update(player, frameCounter, true);
    }

    void update(PlayableEntity player, int frameCounter, boolean usePreUpdateState) {
        currentFrameCounter = frameCounter;
        if (player == null || objectManager == null || player.getDead() || table == null) {
            overlapping.clear();
            debugState.clear();
            return;
        }

        if (player.isDebugMode()) {
            overlapping.clear();
            debugState.clear();
            return;
        }

        // ROM Sonic_Display (sonic3k.asm:22019-22021) and S2/S1 equivalents
        // skip TouchResponse when object_control's bit 7 (or $A0 in S3K) is
        // set — i.e. flight/CATCH_UP_FLIGHT/FLIGHT_AUTO_RECOVERY/super/debug
        // states where the controlling object owns the sprite. Without this
        // gate, balloons and other touch objects fire false positives
        // against a sprite that ROM never collides during these states.
        // See PlayableEntity#isTouchResponseSuppressedByObjectControl() for
        // the cross-game ROM citations.
        if (player.isTouchResponseSuppressedByObjectControl()) {
            overlapping.clear();
            debugState.clear();
            return;
        }

        int playerX = player.getCentreX() - 8;
        int baseYRadius = Math.max(1, player.getYRadius() - 3);
        // ROM: playerY = y_pos - (y_radius - 3). Do NOT subtract 8 from Y (only X).
        int playerY = player.getCentreY() - baseYRadius;
        int playerHeight = baseYRadius * 2;
        // ASSEMBLY FLAG: fixBugs (docs/s2disasm/s2.asm:27) / FixBugs
        // (docs/s1disasm/sonic.asm:20), both 0 in the shipped ROMs. THE ENGINE
        // IMPLEMENTS THE SHIPPED (UN-FIXED) BRANCH: TouchResponse (s2.asm:85069)
        // and Touch_Boss (s2.asm:85212) test the mapping frame
        // (`cmpi.b #$4D,mapping_frame(a0)`; S1 uses fr_Duck = $39), so the
        // 12px-down / 20px-tall box applies on exactly one frame of the duck
        // animation, and never to Tails or Super Sonic whose duck frames are $5B
        // and $C1. With fixBugs = 1 the test would be the animation id
        // (AniIDSonAni_Duck) and the smaller box would cover the whole duck for
        // every character. S3K removed the adjustment outright
        // (sonic3k.asm:20649-20650). See ObjectInteractionRules#duckTouchBoxMappingFrame.
        ObjectInteractionRules duckRulesMain = objectInteractionRulesOrNull(player);
        boolean crouching = duckRulesMain != null
                && duckRulesMain.isDuckTouchBoxMappingFrame(player.getMappingFrame());
        if (crouching) {
            playerY += ObjectInteractionRules.DUCK_TOUCH_BOX_TOP_SHIFT;
            playerHeight = ObjectInteractionRules.DUCK_TOUCH_BOX_HEIGHT;
        }
        // ROM (sonic3k.asm:20620-20640): Insta-shield expands hitbox to 48x48
        instaShieldActive = false;
        currentPlayer = player;
        int playerWidth = 0x10; // Normal width
        PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull(player);
        if (capabilityRules != null && capabilityRules.instaShieldEnabled()
                && player.getDoubleJumpFlag() == 1
                && player.getShieldType() == null
                && player.getInvincibleFrames() == 0) {
            instaShieldActive = true;
            playerX = player.getCentreX() - 0x18;
            playerY = player.getCentreY() - 0x18;
            playerHeight = 0x30;
            playerWidth = 0x30;
        }
        debugState.setPlayer(playerX, playerY, playerHeight, baseYRadius, crouching);
        debugState.clear();

        // Double-buffer pattern:
        // - 'overlapping' contains last frame's overlapping objects
        // - 'building' will be populated with this frame's overlapping objects
        // Clear building to prepare for this frame's data
        building.clear();

        processCollisionLoop(player, playerX, playerY, playerHeight, playerWidth,
                building, overlapping, false, usePreUpdateState);

        // Swap buffers: building becomes overlapping for next frame
        Set<ObjectInstance> temp = overlapping;
        overlapping = building;
        building = temp;
        instaShieldActive = false;
        currentPlayer = null;
    }

    /**
     * Touch response check for the CPU sidekick (Tails).
     * Uses separate overlap tracking from the main player.
     * ROM: In 1P mode, CPU Tails interacts with objects but doesn't scatter
     * rings when hurt and can never die from enemy contact.
     */
    void updateSidekick(PlayableEntity sidekick, int frameCounter) {
        updateSidekick(sidekick, frameCounter, true);
    }

    void updateSidekick(PlayableEntity sidekick, int frameCounter, boolean usePreUpdateState) {
        currentPlayer = null; // Sidekick doesn't get insta-shield
        currentFrameCounter = frameCounter;
        if (sidekick == null || objectManager == null || sidekick.getDead() || table == null) {
            return;
        }
        OverlapBufferPair buffers = sidekickOverlaps.computeIfAbsent(sidekick, k -> new OverlapBufferPair());

        if (sidekick.isDebugMode()) {
            buffers.overlapping.clear();
            debugState.clear();
            return;
        }

        // ROM Tails_Display (sonic3k.asm:26263-26266) and S2/S1 equivalents
        // skip TouchResponse when object_control's bit 7 (or $A0 in S3K) is
        // set. For S3K this is critical for Tails_CPU_routine 2/4
        // (Tails_Catch_Up_Flying / Tails_FlySwim_Unknown) which ROM enters
        // with object_control=$81 (sonic3k.asm:26511, 26542) — both
        // routines run from Tails_CPU_Control, NOT from Tails_Display, so
        // ROM never reaches the TouchResponse call in those states. Engine
        // must mirror the skip to avoid balloon/spike/etc. false-positive
        // collisions during catch-up flight.
        if (sidekick.isTouchResponseSuppressedByObjectControl()) {
            buffers.overlapping.clear();
            debugState.clear();
            return;
        }

        int playerX = sidekick.getCentreX() - 8;
        int baseYRadius = Math.max(1, sidekick.getYRadius() - 3);
        int playerY = sidekick.getCentreY() - baseYRadius;
        int playerHeight = baseYRadius * 2;
        // ASSEMBLY FLAG: fixBugs / FixBugs, 0 in the shipped ROMs. Same shipped
        // mapping-frame test as the main-player path above; because it is a frame
        // compare rather than an animation-id compare, it structurally never fires
        // for Tails (TailsAni_Duck uses frame $5B, s2.asm:41575) even though the
        // ROM runs the identical instruction for whichever object is in a0. With
        // fixBugs = 1 (`cmpi.b #AniIDSonAni_Duck,anim(a0)`) the sidekick's duck
        // WOULD shrink the box. Kept as the shared predicate so the behaviour
        // follows the ROM data rather than a per-character carve-out.
        ObjectInteractionRules duckRules = objectInteractionRulesOrNull(sidekick);
        boolean crouching = duckRules != null
                && duckRules.isDuckTouchBoxMappingFrame(sidekick.getMappingFrame());
        if (crouching) {
            playerY += ObjectInteractionRules.DUCK_TOUCH_BOX_TOP_SHIFT;
            playerHeight = ObjectInteractionRules.DUCK_TOUCH_BOX_HEIGHT;
        }
        debugState.setPlayer(playerX, playerY, playerHeight, baseYRadius, crouching);
        debugState.clear();

        buffers.building.clear();

        processCollisionLoop(sidekick, playerX, playerY, playerHeight, 0x10,
                buffers.building, buffers.overlapping, true, usePreUpdateState);

        buffers.swap();
    }

    /**
     * Shared collision loop for both main player and sidekick touch responses.
     * Iterates active objects, checks touch regions and overlap, dispatches to the
     * appropriate response handler. Behavioral differences are parameterized:
     * - Both actors record debug hits when the trace/debug overlay enables it
     * - Sidekick: uses Hurt_Sidekick handling
     * - Both players exit after the first overlapping object; the ROM's handler
     *   returns from ReactToItem after processing one touch response.
     *
     * @param player         the playable entity to check collisions for
     * @param playerX        hitbox left edge (centreX - 8, or insta-shield adjusted)
     * @param playerY        hitbox top edge (centreY - yRadius adjusted)
     * @param playerHeight   hitbox height
     * @param playerWidth    hitbox width (0x10 normal, 0x30 insta-shield)
     * @param buildingSet    the set being populated with this frame's overlapping objects
     * @param overlappingSet last frame's overlapping objects (for edge-trigger detection)
     * @param isSidekick     true for sidekick (no break-on-hit, sidekick response handler)
     */
    private void processCollisionLoop(PlayableEntity player,
            int playerX, int playerY, int playerHeight, int playerWidth,
            Set<ObjectInstance> buildingSet, Set<ObjectInstance> overlappingSet,
            boolean isSidekick, boolean usePreUpdateState) {
        Collection<ObjectInstance> touchObjects = objectManager.getTouchResponseObjects();
        boolean usePreviousCollisionResponseList =
                usePreUpdateState && objectManager.touchUsesPreviousCollisionResponseList();

        for (ObjectInstance instance : touchObjects) {
            TouchResponseProvider provider = (TouchResponseProvider) instance;
            if (instance.isSkipTouchThisFrame()) {
                continue;
            }

            // Multi-region providers (e.g., spiked pole helix) check each region independently
            TouchResponseProvider.TouchRegion[] regions = provider.getMultiTouchRegions();
            TouchResponseProfile touchProfile = regions != null
                    ? provider.getTouchResponseProfile(true)
                    : provider.getTouchResponseProfile();
            if (!isCandidateForActor(isSidekick, touchProfile)) {
                continue;
            }

            // ROM parity (S1-specific provenance):
            // S1's ReactToItem (docs/s1disasm/_incObj/sub ReactToItem.asm:26-27)
            // gates each iteration on `tst.b obRender(a1) / bpl.s .next`. If
            // obRender bit 7 is clear (object not yet displayed by
            // DisplaySprite), the entire object is skipped. This covers:
            //   (a) First-frame objects whose DisplaySprite hasn't run yet
            //   (b) Objects that were offscreen on the previous frame
            //   (c) Objects created by higher-slot makers that haven't run yet
            //
            // Note: this gate is NOT universal across games. S2's TouchResponse
            // (docs/s2disasm/s2.asm Touch_Loop ~line 84537) iterates objects
            // and only checks `collision_flags(a1)` -- there is no render-flag
            // gate, so an off-screen object with a non-zero collision_flags is
            // still considered for touch. S3K does not iterate at all: it
            // pre-builds Collision_response_list during ExecuteObjects and
            // walks only objects that opted in, so the equivalent of the bit-7
            // check happens at list-add time, not at touch time.
            //
            // The engine's TouchResponseProvider.requiresRenderFlagForTouch()
            // defaults to true for portability with the S1 behaviour (the most
            // restrictive of the three). Per-object opt-out is available if a
            // future S2/S3K-specific object needs to skip the render-flag gate.
            // Use isOnScreenForTouch() as the engine's equivalent of obRender
            // bit 7.
            if (!usePreviousCollisionResponseList
                    && touchProfile.requiresRenderFlagForTouch()
                    && instance instanceof AbstractObjectInstance aoi
                    && !aoi.isOnScreenForTouch()) {
                continue;
            }
            if (regions != null) {
                boolean hit = processMultiRegionTouch(player, playerX, playerY, playerHeight,
                        instance, provider, touchProfile, regions, playerWidth,
                        buildingSet, overlappingSet, isSidekick);
                if (hit) {
                    break;
                }
                continue;
            }
            int flags;
            if (usePreUpdateState && !usePreviousCollisionResponseList) {
                int preFlags = instance.getPreUpdateCollisionFlags();
                flags = (preFlags >= 0) ? preFlags : provider.getCollisionFlags();
            } else {
                flags = provider.getCollisionFlags();
            }
            if (flags == 0) {
                continue; // Skip collision for objects with no collision flags
            }
            int sizeIndex = flags & 0x3F;
            int width = table.getWidthRadius(sizeIndex);
            int height = table.getHeightRadius(sizeIndex);
            TouchCategory category = decodeCategory(flags, touchProfile);

            // ROM parity: ReactToItem/TouchResponse runs in the player slot
            // before dynamic objects update. S3K's previous collision-response
            // list stores object RAM pointers, but those pointers still hold
            // frame-start x/y at this phase.
            // Obj37's engine projection is already the preceding pass's
            // published state here; its generic pre-update cache is one pass
            // older than the live SST pointer consumed by Touch_Loop.
            boolean useCurrentTouchState = usesCurrentTouchState(instance)
                    || (usePreviousCollisionResponseList
                    && instance instanceof LostRingObjectInstance);
            int objX = usePreUpdateState && !useCurrentTouchState
                    ? instance.getPreUpdateCollisionX() : instance.getCollisionX();
            int objY = usePreUpdateState && !useCurrentTouchState
                    ? instance.getPreUpdateCollisionY() : instance.getCollisionY();
            if (category == TouchCategory.HURT
                    && tryShieldDeflect(player, provider, touchProfile, objX, objY, width, height)) {
                continue;
            }
            boolean overlap = isOverlapping(playerX, playerY, playerHeight, objX, objY, width, height, playerWidth);
            int slotIndex = instance instanceof AbstractObjectInstance aoi
                    ? aoi.getSlotIndex()
                    : -1;
            if (debugState.isEnabled()) {
                debugState.addHit(
                        new TouchResponseDebugHit(slotIndex, instance.getSpawn(), objX, objY, flags, sizeIndex,
                                width, height, category, overlap,
                                instance instanceof AbstractObjectInstance aoi ? aoi.traceDebugDetails() : ""));
            }
            if (!overlap) {
                continue;
            }
            buildingSet.add(instance);
            if (category == TouchCategory.HURT) {
                // S3K TouchResponse temporarily sets Status_Invincible during
                // the Insta-Shield 48x48 pass, so Touch_ChkHurt returns before
                // Touch_ChkHurt_Bounce_Projectile clears collision_flags
                // (sonic3k.asm:20620-20640, 21003-21047).
                if (instaShieldActive && player == currentPlayer) {
                    break;
                }
            }

            // Type-keyed lost-ring collectible: ROM Touch_ChkValue ring branch
            // (docs/s2disasm/s2.asm:85196-85219). Evaluated EVERY frame on overlap
            // (NOT edge-triggered) so the ring collects the frame invulnerable_time
            // drops below 90 while the player is still continuously overlapping.
            // Keyed on the LostRingObjectInstance marker, NOT the 0x47 byte shape -
            // so other SPECIAL objects sharing $47 (e.g. S1 placed rings) keep their
            // own listener path. ROM tests MainCharacter+invulnerable_time even
            // on the sidekick pass, then stores the touching player in parent(a1).
            // CollectRing_Tails falls through to the 1P shared counter/sound path
            // outside two-player mode (docs/s2disasm/s2.asm:85201-85219,
            // 25023-25075). This is now the sole lost-ring collection path - the
            // legacy RingManager scan is gone.
            if (instance instanceof LostRingObjectInstance lostRing && lostRing.isLostRingCollectible()) {
                if (player instanceof AbstractPlayableSprite aps) {
                    int invuln = lostRingCollectionInvulnerableFrames(aps, isSidekick);
                    if (invuln < LOST_RING_INVULNERABLE_THRESHOLD && !lostRing.isCollected()) {
                        lostRing.markCollected(currentFrameCounter);
                    }
                }
                break; // ROM: rts — first overlapping object ends the loop (both paths).
            }
            // ROM touch checks run every frame for BOSS/HURT/ENEMY. SPECIAL
            // (collision_flags 0x40-0x7F) objects in ROM are run every
            // frame too, but the object itself typically transitions to a
            // 'touched' routine on first contact and ignores subsequent
            // overlaps — so engine still edge-triggers SPECIAL callbacks
            // to keep tests asserting that the object only responds once
            // per overlap.
            //
            // ENEMY must be continuous so a ROM Touch_Enemy/Touch_KillEnemy
            // path fires when the player transitions from non-attacking to
            // attacking while still overlapping the badnik. E.g. S2 MCZ
            // Crawlton at trace frame 825: Sonic stood in overlap (Hurt
            // path gated by invulnerable_time → Touch_NoHurt), then
            // initiated Spindash. ROM `Touch_Enemy` re-checks anim each
            // frame; once it sees AniIDSonAni_Spindash it calls
            // Touch_KillEnemy and applies the -$100 side-bounce
            // (s2.asm:84807-84890). Without continuous re-check the
            // badnik stays alive and Sonic's y_vel stays at 0, diverging
            // from ROM.
            //
            // ROM citations: s2.asm:84502-84890 (`TouchResponse`,
            // `Touch_Loop`, `Touch_Enemy`, `Touch_KillEnemy`); same
            // every-frame loop in S1 (`docs/s1disasm/_incObj/sub ReactToItem.asm`)
            // and S3K (`docs/skdisasm/sonic3k.asm` `Collision_response_list`
            // dispatcher).
            boolean shouldTrigger = category == TouchCategory.BOSS
                    || category == TouchCategory.HURT
                    || category == TouchCategory.ENEMY
                    || touchProfile.continuousCallbacks()
                    || !overlappingSet.contains(instance);
            if (shouldTrigger) {
                TouchResponseResult result = new TouchResponseResult(
                        sizeIndex, width, height, category, touchProfile.shieldReactionFlags());
                TouchResponseListener listener = instance instanceof TouchResponseListener casted ? casted : null;
                if (isSidekick) {
                    handleTouchResponseSidekick(player, instance, listener, result, touchProfile, objY);
                } else {
                    handleTouchResponse(player, instance, listener, result, touchProfile, objY);
                }
            }
            // ROM parity: ReactToItem ALWAYS exits after the first overlapping
            // object, regardless of category, player slot, or whether a response
            // was triggered. The ROM's handler returns via rts which exits the
            // entire ReactToItem subroutine.
            //
            // This break is also what implements S1's FixBugs = 0 branch at
            // React_ChkHurt (docs/s1disasm/_incObj/"Sonic ReactToItem.asm":341-350).
            // S1 assembles with FixBugs = 0 (docs/s1disasm/sonic.asm:20) and the
            // traces record shipped-ROM behaviour, so the shipped branch is the
            // accurate one: when the player is invincible or still flashing, the
            // damaging object's handler does `moveq #-1,d0 / rts`, which returns
            // from ReactToItem ENTIRELY, so objects later in the RAM scan are never
            // examined that frame. That is the "can't pick up lost rings while
            // standing on Marble Zone lava" bug, and it is exactly this break: the
            // engine has already added the object to buildingSet and dispatched (or
            // suppressed) its response, and then stops scanning. Assembling with
            // FixBugs = 1 would instead `bra.w React_CheckNext` and keep walking the
            // RAM scan, so a lost ring behind the lava would still be collected.
            // Not a per-game divergence: S2's Touch_NoHurt (docs/s2disasm/s2.asm:
            // 85455-85457) and S3K's Touch_ChkHurt_Return
            // (docs/skdisasm/sonic3k.asm:21016-21018) both do the same
            // `moveq #-1,d0 / rts`, so all three games abandon the touch loop on a
            // suppressed damaging contact and no rules record is warranted.
            break;
        }
    }

    private int lostRingCollectionInvulnerableFrames(AbstractPlayableSprite toucher, boolean isSidekick) {
        if (isSidekick) {
            PlayableEntity mainPlayer = objectManager.services().playerQuery().mainPlayerOrNull();
            if (mainPlayer instanceof AbstractPlayableSprite mainPlayable) {
                return mainPlayable.getInvulnerableFrames();
            }
        }
        return toucher.getInvulnerableFrames(); // AbstractPlayableSprite.java:2117
    }

    private static boolean usesCurrentTouchState(ObjectInstance instance) {
        return instance.usesCurrentTouchResponseState();
    }

    /**
     * Unified multi-region touch check for both player and sidekick.
     * Each region is checked separately; if any overlaps, the object is treated as
     * overlapping with the first matching region's collision flags applied.
     * One hit per object per frame is sufficient — returns after first overlapping region.
     */
    private boolean processMultiRegionTouch(PlayableEntity player,
            int playerX, int playerY, int playerHeight,
            ObjectInstance instance, TouchResponseProvider provider, TouchResponseProfile profile,
            TouchResponseProvider.TouchRegion[] regions, int playerWidth,
            Set<ObjectInstance> buildingSet, Set<ObjectInstance> overlappingSet,
            boolean isSidekick) {
        if (!isCandidateForActor(isSidekick, profile)) {
            return false;
        }
        for (TouchResponseProvider.TouchRegion region : regions) {
            int flags = region.collisionFlags();
            if (flags == 0) {
                continue;
            }
            int sizeIndex = flags & 0x3F;
            int width = table.getWidthRadius(sizeIndex);
            int height = table.getHeightRadius(sizeIndex);
            TouchCategory category = decodeCategory(flags, profile);

            boolean overlap = isOverlappingXY(playerX, playerY, playerHeight,
                    region.x(), region.y(), width, height, playerWidth);
            if (debugState.isEnabled()) {
                int slotIndex = instance instanceof AbstractObjectInstance aoi
                        ? aoi.getSlotIndex()
                        : -1;
                debugState.addHit(
                        new TouchResponseDebugHit(slotIndex, instance.getSpawn(), region.x(), region.y(),
                                flags, sizeIndex, width, height, category, overlap,
                                instance instanceof AbstractObjectInstance aoi ? aoi.traceDebugDetails() : ""));
            }
            if (!overlap) {
                continue;
            }

            buildingSet.add(instance);
            // S3K's Insta-Shield pass temporarily sets Status_Invincible before
            // scanning the 48x48 box. Touch_ChkHurt therefore returns without
            // damaging Sonic, and TouchResponse exits without a second normal-
            // sized pass (sonic3k.asm:20620-20640, 21003-21047). Preserve that
            // control flow for child-region composites as well as ordinary
            // single-region objects.
            if (category == TouchCategory.HURT
                    && instaShieldActive
                    && player == currentPlayer) {
                return true;
            }
            // ROM: HURT is continuous (same as BOSS) — see processCollisionLoop comment
            boolean shouldTrigger = category == TouchCategory.BOSS
                    || category == TouchCategory.HURT
                    || category == TouchCategory.ENEMY
                    || profile.continuousCallbacks()
                    || !overlappingSet.contains(instance);
            if (shouldTrigger) {
                // Pass region.x() so applyHurt uses the matched spike's X for bounce direction,
                // matching ROM HurtSonic.checkDirection: cmp.w obX(a2),d0 where a2 = child slot
                // (docs/s1disasm/_incObj/Sonic ReactToItem.asm:402-405).
                TouchResponseResult result = new TouchResponseResult(
                        sizeIndex, width, height, category, region.shieldReactionFlags(), region.x());
                TouchResponseListener listener = instance instanceof TouchResponseListener casted ? casted : null;
                if (isSidekick) {
                    handleTouchResponseSidekick(player, instance, listener, result, profile, region.y());
                } else {
                    handleTouchResponse(player, instance, listener, result, profile, region.y());
                }
            }
            // ROM parity: ReactToItem ALWAYS exits on first overlap, even
            // when the response is edge-trigger suppressed. Match the
            // single-region break-on-first-overlap behaviour.
            //
            // Same FixBugs = 0 branch as the single-region path — see the long
            // note at the corresponding `break` in processCollisionLoop
            // (React_ChkHurt, docs/s1disasm/_incObj/"Sonic ReactToItem.asm":341-350;
            // shipped `moveq #-1,d0 / rts` versus the FixBugs = 1
            // `bra.w React_CheckNext`).
            return true;
        }
        return false; // No region overlapped
    }

    private boolean isCandidateForActor(boolean isSidekick, TouchResponseProfile profile) {
        return !isSidekick
                || profile == null
                || profile.actorContextPolicy() != TouchActorContextPolicy.MAIN_ONLY;
    }

    private boolean tryShieldDeflect(PlayableEntity player,
            TouchResponseProvider provider, TouchResponseProfile profile,
            int objectX, int objectY, int objectWidth, int objectHeight) {
        if (player == null || !canDeflectShieldReactiveProjectile(player)) {
            return false;
        }
        if (profile.shieldDeflectCapability() != TouchShieldDeflectCapability.SHIELD_DEFLECT
                || (profile.shieldReactionFlags() & SHIELD_REACTION_BOUNCE_BIT) == 0) {
            return false;
        }

        int shieldLeft = player.getCentreX() - SHIELD_TOUCH_HALF_SIZE;
        int shieldTop = player.getCentreY() - SHIELD_TOUCH_HALF_SIZE;
        boolean overlap = isRectOverlapping(shieldLeft, shieldTop, SHIELD_TOUCH_SIZE, SHIELD_TOUCH_SIZE,
                objectX, objectY, objectWidth, objectHeight);
        if (!overlap) {
            return false;
        }
        return provider.onShieldDeflect(player);
    }

    private boolean canDeflectShieldReactiveProjectile(PlayableEntity player) {
        if (player.hasShield()) {
            return true;
        }
        return false;
    }

    /**
     * ROM: In 1P mode, CPU Tails interacts with objects but hurt handling differs:
     * - Can destroy badniks while rolling/invincible (same as Sonic)
     * - Gets knocked back when hurt but does NOT scatter rings or die
     * - Special category objects still interact normally
     */
    private void handleTouchResponseSidekick(PlayableEntity sidekick, ObjectInstance instance,
            TouchResponseListener listener, TouchResponseResult result, TouchResponseProfile profile,
            int resolvedTouchY) {
        if (sidekick == null) {
            return;
        }
        if (profile != null && profile.actorContextPolicy() == TouchActorContextPolicy.MAIN_ONLY) {
            return;
        }
        if (listener != null) {
            listener.onTouchResponse(sidekick, result, currentFrameCounter);
        }
        if (result.category() == TouchCategory.SPECIAL
                && profile.enablesPostSpecialTouchAirborneSideVelocityPreservation()) {
            lastSpecialTouchFrame.put(sidekick, currentFrameCounter);
        }

        switch (result.category()) {
            case HURT -> applySidekickHurt(sidekick, instance, result);
            case ENEMY -> {
                if (isPlayerAttacking(sidekick, instance)) {
                    // ROM: Touch_Enemy_Part2 checks collision_property BEFORE decrementing HP.
                    int hpBeforeHit = 0;
                    if (instance instanceof TouchResponseProvider provider2) {
                        hpBeforeHit = provider2.getCollisionProperty();
                    }
                    // ROM parity (sonic3k.asm:20945-20990): Touch_EnemyNormal sets
                    // status bit 7 on the badnik AND applies +/-$100 bounce to the
                    // attacking player in the SAME function. The skip-when-destroyed
                    // behaviour only applies to a SUBSEQUENT collision pass (e.g. after
                    // P1 destroys a badnik in P1's TouchResponse, P2's pass naturally
                    // skips because the object's (a0) was rewritten to Obj_Explosion).
                    // We mirror that by capturing the pre-attack destroyed state and
                    // gating the bounce only on prior destruction - never on the
                    // sidekick's own kill (which would suppress her +/-$100 bounce).
                    boolean wasAlreadyDestroyed = instance instanceof AbstractObjectInstance preAoi
                            && preAoi.isDestroyed();
                    if (instance instanceof TouchResponseAttackable attackable) {
                        attackable.onPlayerAttack(sidekick, result);
                    }
                    // ROM byte zero-test gate, not signed compare (see player path
                    // above): S2 s2.asm:85282-85290 Touch_Enemy_Part2 tst.b
                    // collision_property/beq; S1 React_Enemy tst.b obColProp/beq;
                    // S3K sonic3k.asm:20911-20922 tst.b boss_hitcount2/beq. A NONZERO
                    // byte (incl. 0xFF/-1 always-bounce) negates both velocities.
                    if ((hpBeforeHit & 0xFF) != 0) {
                        // S3K boss-hit path also negates ground_vel; S1 also halves.
                        applyBossBounce(sidekick);
                    } else if (!wasAlreadyDestroyed) {
                        // Touch_EnemyNormal bounce: fires when this player's pass kills
                        // the instance. Objects that handle their own bounce (like Crawl
                        // shield mode) without self-destructing are excluded.
                        boolean isNowDestroyed = instance instanceof AbstractObjectInstance postAoi
                                && postAoi.isDestroyed();
                        if (isNowDestroyed) {
                            applyEnemyBounce(sidekick, resolvedTouchY);
                        }
                    }
                } else {
                    applySidekickHurt(sidekick, instance, result);
                }
            }
            case SPECIAL -> {
                // Listener handles object-specific logic.
            }
            case BOSS -> {
                if (isPlayerAttacking(sidekick, instance)) {
                    if (instance instanceof TouchResponseAttackable attackable) {
                        attackable.onPlayerAttack(sidekick, result);
                    }
                    applyBossBounce(sidekick);
                } else {
                    applySidekickHurt(sidekick, instance, result);
                }
            }
        }
    }

    /**
     * ROM: Hurt_Sidekick in 1P mode - just knockback, no ring scatter, no death.
     * From s2.asm HurtCharacter: in 1P mode, branches directly to Hurt_Sidekick
     * which applies hurt animation without checking rings.
     */
    private void applySidekickHurt(PlayableEntity sidekick, ObjectInstance instance,
            TouchResponseResult result) {
        int sourceX = (result != null && result.hasRegionX())
                ? result.regionX()
                : instance != null ? instance.getCollisionX() : sidekick.getCentreX();
        // HurtCharacter's common tail always publishes $1A (sonic3k.asm:21321).
        // Where the ROM appears to keep the prior byte it is the solid
        // push-release tail erasing it later in the same frame, which
        // ObjectSolidContactController owns; the touch path has no say in it.
        sidekick.applyHurt(sourceX);
    }

    private boolean isOverlapping(int playerX, int playerY, int playerHeight,
            int objectX, int objectY, int objectWidth, int objectHeight, int playerWidth) {
        int dx = objectX - objectWidth - playerX;
        if (dx < 0) {
            int sum = (dx & 0xFFFF) + ((objectWidth * 2) & 0xFFFF);
            if (sum <= 0xFFFF) {
                return false;
            }
        } else if (dx > playerWidth) {
            return false;
        }

        int dy = objectY - objectHeight - playerY;
        if (dy < 0) {
            int sum = (dy & 0xFFFF) + ((objectHeight * 2) & 0xFFFF);
            if (sum <= 0xFFFF) {
                return false;
            }
        } else if (dy > playerHeight) {
            return false;
        }

        return true;
    }

    private boolean isRectOverlapping(int playerLeft, int playerTop, int playerWidth, int playerHeight,
            int objectX, int objectY, int objectWidth, int objectHeight) {
        int playerRight = playerLeft + playerWidth;
        int playerBottom = playerTop + playerHeight;
        int objectLeft = objectX - objectWidth;
        int objectRight = objectX + objectWidth;
        int objectTop = objectY - objectHeight;
        int objectBottom = objectY + objectHeight;
        return playerRight >= objectLeft
                && playerLeft <= objectRight
                && playerBottom >= objectTop
                && playerTop <= objectBottom;
    }

    /**
     * Overlap check using raw x/y coordinates instead of ObjectSpawn.
     * Used by multi-region touch collision.
     */
    private boolean isOverlappingXY(int playerX, int playerY, int playerHeight,
            int objX, int objY, int objectWidth, int objectHeight, int playerWidth) {
        int dx = objX - objectWidth - playerX;
        if (dx < 0) {
            int sum = (dx & 0xFFFF) + ((objectWidth * 2) & 0xFFFF);
            if (sum <= 0xFFFF) {
                return false;
            }
        } else if (dx > playerWidth) {
            return false;
        }

        int dy = objY - objectHeight - playerY;
        if (dy < 0) {
            int sum = (dy & 0xFFFF) + ((objectHeight * 2) & 0xFFFF);
            if (sum <= 0xFFFF) {
                return false;
            }
        } else if (dy > playerHeight) {
            return false;
        }

        return true;
    }

    private TouchCategory decodeCategory(int flags, TouchResponseProfile profile) {
        int categoryBits = flags & 0xC0;
        int sizeIndex = flags & 0x3F;
        if (profile != null && profile.categoryDecodeMode() == TouchCategoryDecodeMode.FORCE_ENEMY) {
            return TouchCategory.ENEMY;
        }
        if (categoryBits == 0xC0 && profile != null
                && profile.categoryDecodeMode() == TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY) {
            // ROM: S3K Touch_ChkValue sends every $C0 collision flag to
            // Touch_Special (sonic3k.asm:20773-20778). Touch_Special only
            // increments collision_property for selected size indices and
            // otherwise returns (sonic3k.asm:21162-21194); it is never the
            // generic boss-bounce path.
            return TouchCategory.SPECIAL;
        }
        if (categoryBits == 0xC0 && profile != null) {
            boolean propertySpecial = switch (profile.categoryDecodeMode()) {
                case S1_SPECIAL_PROPERTY -> isSonic1TouchSpecialPropertyIndex(sizeIndex);
                case S3K_SPECIAL_PROPERTY -> isS3kTouchSpecialPropertyIndex(sizeIndex);
                case SONIC2_SPECIAL_PROPERTY -> isSonic2TouchSpecialPropertyIndex(sizeIndex);
                case NORMAL, FORCE_ENEMY -> false;
            };
            if (propertySpecial) {
                return TouchCategory.SPECIAL;
            }
        }
        return switch (categoryBits) {
            case 0x00 -> TouchCategory.ENEMY;
            case 0x40 -> TouchCategory.SPECIAL;
            case 0x80 -> TouchCategory.HURT;
            default -> TouchCategory.BOSS;
        };
    }

    private boolean isSonic1TouchSpecialPropertyIndex(int sizeIndex) {
        return switch (sizeIndex) {
            // S1 React_Special increments obColProp for $D7 and $E1 only:
            // docs/s1disasm/_incObj/sub ReactToItem.asm:377-427.
            case 0x17, 0x21 -> true;
            default -> false;
        };
    }

    private boolean isS3kTouchSpecialPropertyIndex(int sizeIndex) {
        return switch (sizeIndex) {
            case 0x06, 0x07, 0x0A, 0x0C, 0x15, 0x16, 0x17, 0x18, 0x21 -> true;
            default -> false;
        };
    }

    private boolean isSonic2TouchSpecialPropertyIndex(int sizeIndex) {
        return switch (sizeIndex) {
            case 0x06, 0x07, 0x0A, 0x0B, 0x0C, 0x14, 0x15, 0x16,
                    0x17, 0x18, 0x1A, 0x21 -> true;
            default -> false;
        };
    }

    private void handleTouchResponse(PlayableEntity player, ObjectInstance instance,
            TouchResponseListener listener, TouchResponseResult result, TouchResponseProfile profile,
            int resolvedTouchY) {
        if (player == null) {
            return;
        }
        if (listener != null) {
            listener.onTouchResponse(player, result, currentFrameCounter);
        }
        if (result.category() == TouchCategory.SPECIAL
                && profile.enablesPostSpecialTouchAirborneSideVelocityPreservation()) {
            lastSpecialTouchFrame.put(player, currentFrameCounter);
        }

        switch (result.category()) {
            case HURT -> applyHurt(player, instance, result);
            case ENEMY -> {
                if (isPlayerAttacking(player, instance)) {
                    // ROM: Touch_Enemy_Part2 checks collision_property BEFORE decrementing HP.
                    // Capture HP before onPlayerAttack (which may decrement it).
                    int hpBeforeHit = 0;
                    if (instance instanceof TouchResponseProvider provider2) {
                        hpBeforeHit = provider2.getCollisionProperty();
                    }
                    // ROM parity (s2.asm:84842-84863): Touch_KillEnemy rewrites
                    // the badnik slot to ObjID_Explosion before applying the
                    // bounce at s2.asm:84865-84889. A later touch pass must not
                    // bounce from an engine instance that was already destroyed.
                    boolean wasAlreadyDestroyed = instance instanceof AbstractObjectInstance preAoi
                            && preAoi.isDestroyed();
                    if (instance instanceof TouchResponseAttackable attackable) {
                        attackable.onPlayerAttack(player, result);
                    }
                    // ROM gates the boss-rebound on a BYTE zero-test, not a signed
                    // compare. All three games tst.b the gated byte and beq to the
                    // kill path; a NONZERO byte (incl. the 0xFF "always-bounce" the
                    // S2 DEZ Death Egg Robot head writes, ObjC7_Head rtn 8
                    // s2.asm:83276-83277 move.b #-1,collision_property) runs
                    // neg.w x_vel / neg.w y_vel. References:
                    //   S2  s2.asm:85282-85290 Touch_Enemy_Part2:
                    //       tst.b collision_property(a1); beq Touch_KillEnemy;
                    //       neg.w x_vel(a0); neg.w y_vel(a0)
                    //   S1  s1disasm _incObj/sub ReactToItem.asm:181-191 React_Enemy:
                    //       tst.b obColProp(a1); beq .breakenemy; neg.w obVelX/obVelY
                    //   S3K sonic3k.asm:20911-20922 .checkhurtenemy:
                    //       tst.b boss_hitcount2(a1); beq Touch_EnemyNormal;
                    //       neg.w x_vel; neg.w y_vel; neg.w ground_vel
                    // A signed `> 0` test wrongly rejected 0xFF/-1. For S3K bosses
                    // getCollisionProperty() returns positive HP, so != 0 is
                    // behaviorally identical there; the only changed case is the
                    // 0xFF always-bounce value, treated as nonzero by all 3 ROMs.
                    if ((hpBeforeHit & 0xFF) != 0) {
                        // S3K boss-hit path also negates ground_vel; S1 also halves.
                        applyBossBounce(player);
                    } else if (!wasAlreadyDestroyed) {
                        // Touch_KillEnemy: position-based bounce only when onPlayerAttack
                        // destroyed the instance. Objects like Crawl that handle their own
                        // custom bounce without self-destructing must not get a second bounce.
                        boolean isNowDestroyed = instance instanceof AbstractObjectInstance postAoi
                                && postAoi.isDestroyed();
                        if (isNowDestroyed) {
                            applyEnemyBounce(player, resolvedTouchY);
                        }
                    }
                } else {
                    applyHurt(player, instance, result);
                }
            }
            case SPECIAL -> {
                // Listener handles object-specific logic.
            }
            case BOSS -> {
                if (isPlayerAttacking(player, instance)) {
                    if (instance instanceof TouchResponseAttackable attackable) {
                        attackable.onPlayerAttack(player, result);
                    }
                    applyBossBounce(player);
                } else {
                    applyHurt(player, instance, result);
                }
            }
        }
    }

    private boolean isPlayerAttacking(PlayableEntity player, ObjectInstance target) {
        if (player == null) {
            return false;
        }
        if (player.isSuperSonic()
                || player.getInvincibleFrames() > 0
                || isSpinAttackAnimation(player)
                || (instaShieldActive && player == currentPlayer)) {
            return true;
        }

        PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull(player);
        if (capabilityRules != null && capabilityRules.elementalShieldsEnabled()) {
            return isS3kAbilityAttack(player, target);
        }

        return false;
    }

    private boolean isSpinAttackAnimation(PlayableEntity player) {
        int animation = player.getAnimationId();
        if (animation == ObjectManager.ANIM_SPINDASH) {
            return true;
        }
        if (animation != ObjectManager.ANIM_ROLL) {
            return false;
        }
        // ROM writes anim=Roll together with the rolling status for real roll/jump
        // attacks. Guarding on the status bit prevents stale engine roll anim bytes
        // from turning a standing CPU sidekick into an attacker for one touch pass.
        return player.getRolling();
    }

    private boolean isS3kAbilityAttack(PlayableEntity player, ObjectInstance target) {
        if (player instanceof Knuckles) {
            int flag = player.getDoubleJumpFlag();
            return flag == 1 || flag == 3;
        }
        if (player instanceof Tails tails) {
            return player.getDoubleJumpFlag() != 0
                    && !tails.isInWater()
                    && isTailsFlightAttackAngle(player, target);
        }
        return false;
    }

    private boolean isTailsFlightAttackAngle(PlayableEntity player, ObjectInstance target) {
        if (target == null) {
            return false;
        }
        int dx = (short) (player.getCentreX() - target.getCollisionX());
        int dy = (short) (player.getCentreY() - target.getCollisionY());
        int angle = segaAngle(dx, dy);
        return ((angle - 0x20) & 0xFF) < 0x40;
    }

    private int segaAngle(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return 0x40;
        }
        double radians = Math.atan2(dy, dx);
        int angle = (int) Math.round(radians * 128.0 / Math.PI);
        return angle & 0xFF;
    }

    private void applyEnemyBounce(PlayableEntity player, int enemyY) {
        // ROM-accurate: React_Enemy (s1.asm) only modifies obVelY, it does NOT
        // set the air flag. Letting the collision system handle air state naturally
        // preserves rolling through enemy bounces (ground roll into badnik).
        // Shared with the objects that call EnemyDefeated themselves off the
        // Touch_Special route — see EnemyDefeatBounce for the ROM listing.
        EnemyDefeatBounce.apply(player, enemyY);
    }

    /**
     * ROM-accurate boss bounce: negate both X and Y velocities.
     * S2 {@code Touch_Enemy_Part2} (s2.asm:85330-85331) and the multi-sprite boss
     * path (s2.asm:85343-85344) only negate x_vel/y_vel.
     * S1 {@code React_BossHit} (docs/s1disasm/_incObj/Sonic ReactToItem.asm:260-263)
     * negates THEN halves both via {@code asr.w} ({@code neg / neg / asr / asr}),
     * gated by {@code bossHitHalvesBounceVelocity}.
     * S3K additionally negates ground velocity ({@code bossHitNegatesGroundSpeed}).
     * Does not set air flag - ROM only modifies velocities here.
     */
    private void applyBossBounce(PlayableEntity player) {
        int negX = -player.getXSpeed();
        int negY = -player.getYSpeed();
        ObjectInteractionRules rules = objectInteractionRulesOrNull(player);
        if (rules != null && rules.bossHitHalvesBounceVelocity()) {
            // ROM asr.w: arithmetic (sign-preserving) shift right by 1.
            negX >>= 1;
            negY >>= 1;
        }
        player.setXSpeed((short) negX);
        player.setYSpeed((short) negY);
        if (rules != null && rules.bossHitNegatesGroundSpeed()) {
            player.setGSpeed((short) -player.getGSpeed());
        }
    }

    private PlayerCapabilityRules playerCapabilityRulesOrNull(PlayableEntity player) {
        if (player == null) {
            return null;
        }
        GameRules rules = player.getGameRules();
        if (rules != null && rules.playerCapability() != null) {
            return rules.playerCapability();
        }
        return null;
    }

    private ObjectInteractionRules objectInteractionRulesOrNull(PlayableEntity player) {
        if (player == null) {
            return null;
        }
        GameRules rules = player.getGameRules();
        if (rules != null && rules.objectInteraction() != null) {
            return rules.objectInteraction();
        }
        return null;
    }

    private void applyHurt(PlayableEntity player, ObjectInstance instance, TouchResponseResult result) {
        if (player.getInvulnerable()) {
            return;
        }

        if (instance != null) {
            String className = instance.getClass().getSimpleName();
            int objectId = instance.getSpawn().objectId();
            LOGGER.fine(() -> "Touch hurt by: " + className + " (ID: 0x" + Integer.toHexString(objectId) + ")");
        }

        // For multi-region providers (e.g., spiked pole helix), use the matched region's X
        // rather than the parent instance X. ROM uses the individual child slot's obX(a2)
        // for the hurt-direction comparison (docs/s1disasm/_incObj/Sonic ReactToItem.asm:402-405).
        int sourceX = (result != null && result.hasRegionX())
                ? result.regionX()
                : instance != null ? instance.getCollisionX() : player.getCentreX();
        boolean spikeHit = instance != null && instance.getSpawn().objectId() == 0x36;

        // S3K shield_reaction bit 4: fire shield blocks fire damage
        boolean fireHit = !spikeHit && result != null
                && (result.shieldReactionFlags() & 0x10) != 0;

        DamageCause cause = spikeHit
                ? DamageCause.SPIKE
                : fireHit ? DamageCause.FIRE
                : DamageCause.NORMAL;

        boolean hadRings = player.getRingCount() > 0;
        boolean suppressLostRingSpawn = player instanceof AbstractPlayableSprite aps
                && aps.suppressesLostRingSpawnOnHurt();
        if (hadRings && !player.hasShield() && !suppressLostRingSpawn) {
            // Requires the concrete playable type, but still goes through ObjectServices.
            if (player instanceof AbstractPlayableSprite aps) {
                // ROM Obj37 allocation becomes visible after the current player slot's
                // hurt handling, but later playable slots do not refresh their CPU
                // interact latch from those new rings until their next object tick.
                objectManager.services().spawnLostRingsAfterCurrentFrame(aps, currentFrameCounter - 1);
            }
        }
        player.applyHurtOrDeath(sourceX, cause, hadRings);
    }

    TouchResponseDebugState getDebugState() {
        return debugState;
    }
}
