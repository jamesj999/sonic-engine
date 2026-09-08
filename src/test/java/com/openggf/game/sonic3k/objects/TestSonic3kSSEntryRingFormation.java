package com.openggf.game.sonic3k.objects;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.RespawnState;
import com.openggf.game.ShieldType;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.BigRingReturnState;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that the S3K big ring (Obj_SSEntryRing) is NOT interactable
 * during its initial growing animation, matching the ROM's behaviour.
 * <p>
 * ROM reference: sonic3k.asm lines 128257-128262
 * <pre>
 * SSEntryRing_Main:
 *     jsr (Animate_Raw).l
 *     ...
 *     cmpi.b #8,mapping_frame(a0)
 *     blo.s  locret_61708        ; mapping_frame &lt; 8 â†’ no collision
 * </pre>
 * <p>
 * Formation animation: delay=4, 9 frame bytes {0,0,1,2,3,4,5,6,7}.
 * ROM's Animate_Raw starts with timer=0, so the first call immediately
 * advances (reads the 2nd byte). This gives 8 advances Ã— (delay+1)=5
 * frames each = <b>40 game frames</b> of formation (mapping_frame 0-7).
 * <p>
 * On frame 41 the 9th advance exceeds the array â†’ transition to idle,
 * mapping_frame becomes 10 (first idle frame), collision is enabled.
 */
public class TestSonic3kSSEntryRingFormation {

    /** Ring placed at screen centre â€” well within default camera bounds. */
    private static final int RING_X = 160;
    private static final int RING_Y = 112;

    /** ROM: formation delay=4, Animate_Raw gives delay+1 = 5 frames per anim step. */
    private static final int FRAMES_PER_ANIM_STEP = 5;

    /**
     * ROM: 8 advances before the $F8 command (reads frame bytes 2-9, i.e.
     * FORMATION_FRAMES[1] through FORMATION_FRAMES[8]).
     * The first byte (FORMATION_FRAMES[0]) is the initial frame set before
     * Animate_Raw first runs.
     */
    private static final int FORMATION_ADVANCE_COUNT = 8;

    /** Total formation duration in game frames: 8 advances Ã— 5 frames each = 40. */
    private static final int FORMATION_TOTAL_FRAMES = FORMATION_ADVANCE_COUNT * FRAMES_PER_ANIM_STEP; // 40

    /**
     * ROM {@code Obj_WaitOffscreen}'s release path {@code loc_85B02} is
     * {@code move.l $34(a0),(a0) / rts} (docs/skdisasm/sonic3k.asm:180300-180302):
     * it only writes the saved code pointer back and returns to the object
     * loop, so {@code Obj_SSEntryRing} -- and therefore the first
     * {@code Animate_Raw} -- is not entered until the following frame. The
     * release costs exactly one update before formation timing starts.
     */
    private static final int RELEASE_FRAMES = 1;

    /** First update on which {@code mapping_frame} reaches the collision gate's 8. */
    private static final int FIRST_IDLE_FRAME = RELEASE_FRAMES + FORMATION_TOTAL_FRAMES + 1; // 42

    private GameStateManager gameState;
    private CapturingObjectServices services;
    private Camera camera;

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
        gameState = new GameStateManager();
        gameState.resetSession();
        services = new CapturingObjectServices().withGameState(gameState);
        camera = mock(Camera.class);
        services.withCamera(camera);

        // Ensure camera bounds include the ring position (default is 0,0,320,224)
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    public void tearDown() {
        clearConstructionContext();
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    public void ringStartsInFormingState() {
        Sonic3kSSEntryRingObjectInstance ring = createRing(0);
        assertTrue(ring.isForming(), "Ring should start in forming state");
        assertTrue(ring.isMainState(), "Ring should be in MAIN state");
        assertEquals(0, ring.getMappingFrame(), "Initial mapping frame should be 0");
    }

    @Test
    void offscreenRetirementReleasesPlacementForLaterCursorReentry() {
        Camera camera = mock(Camera.class);
        services.withCamera(camera);
        when(camera.getX()).thenReturn((short) 0x1000);
        when(camera.getY()).thenReturn((short) 0);
        Sonic3kSSEntryRingObjectInstance ring = createRing(0);

        ring.update(1, null);
        AbstractObjectInstance.updateCameraBounds(0x1000, 0, 320, 224, 0);
        ring.update(2, null);

        assertTrue(ring.isDestroyed());
        assertTrue(ring.isDestroyedRespawnable(),
                "SSEntryRing_Display offscreen loc_6196A clears placement ownership");
    }

    @Test
    public void ringIsNotInteractableDuringEntireFormation() {
        Sonic3kSSEntryRingObjectInstance ring = createRing(0);

        // Step through every frame of the formation animation.
        // The ring must remain in forming state (mapping_frame < 8) throughout.
        for (int frame = 1; frame <= RELEASE_FRAMES + FORMATION_TOTAL_FRAMES; frame++) {
            ring.update(frame, null);
            assertTrue(ring.isForming(), "Ring should be forming at frame " + frame
                            + " (mapping_frame=" + ring.getMappingFrame() + ")");
            assertTrue(ring.getMappingFrame() < 8, "mapping_frame should be < 8 during formation at frame " + frame);
        }
    }

    @Test
    public void ringBecomesInteractableAfterFormation() {
        Sonic3kSSEntryRingObjectInstance ring = createRing(0);

        // Advance through entire formation
        for (int frame = 1; frame <= RELEASE_FRAMES + FORMATION_TOTAL_FRAMES; frame++) {
            ring.update(frame, null);
        }

        // One more frame should transition to idle (mapping_frame >= 8)
        ring.update(FIRST_IDLE_FRAME, null);
        assertFalse(ring.isForming(), "Ring should no longer be forming after formation completes");
        assertTrue(ring.getMappingFrame() >= 8, "mapping_frame should be >= 8 in idle");
    }

    @Test
    public void formationAnimationTimingMatchesRom() {
        Sonic3kSSEntryRingObjectInstance ring = createRing(0);

        // ROM anim 0: delay=4, frame bytes = {0, 0, 1, 2, 3, 4, 5, 6, 7}
        // First byte is the initial frame; Animate_Raw reads bytes 2-9 on advances.
        // Each advance produces a mapping frame displayed for delay+1 = 5 game frames.
        // Expected mapping frames after each 5-frame group:
        int[] expectedFrames = {0, 1, 2, 3, 4, 5, 6, 7};

        // The first update is Obj_WaitOffscreen's loc_85B02 release, which does
        // not reach Animate_Raw (see RELEASE_FRAMES).
        int gameFrame = 0;
        for (int tick = 0; tick < RELEASE_FRAMES; tick++) {
            gameFrame++;
            ring.update(gameFrame, null);
        }
        // Discriminating check: five more updates (one full FRAMES_PER_ANIM_STEP
        // group) must still leave mapping_frame on the animation's first byte.
        // Drop the release frame and the sixth update has already advanced to 1.
        for (int tick = 0; tick < FRAMES_PER_ANIM_STEP; tick++) {
            ring.update(gameFrame + 1 + tick, null);
        }
        assertEquals(0, ring.getMappingFrame(),
                "loc_85B02's release frame must not reach Animate_Raw");
        ring = createRing(0);
        gameFrame = 0;
        for (int tick = 0; tick < RELEASE_FRAMES; tick++) {
            gameFrame++;
            ring.update(gameFrame, null);
        }

        for (int step = 0; step < expectedFrames.length; step++) {
            for (int tick = 0; tick < FRAMES_PER_ANIM_STEP; tick++) {
                gameFrame++;
                ring.update(gameFrame, null);
            }
            assertEquals(expectedFrames[step], ring.getMappingFrame(), "Mapping frame after step " + step + " (game frame " + gameFrame + ")");
        }
        // Verify we've consumed exactly the release frame plus the formation duration
        assertEquals(RELEASE_FRAMES + FORMATION_TOTAL_FRAMES, gameFrame,
                "Should have consumed exactly RELEASE_FRAMES + FORMATION_TOTAL_FRAMES");
    }

    @Test
    public void formationDoesNotAdvanceWhileOffScreen() {
        Sonic3kSSEntryRingObjectInstance ring = createRing(0);

        // Move camera bounds so ring is off-screen
        AbstractObjectInstance.updateCameraBounds(500, 500, 820, 724, 0);

        // Step 20 frames â€” ring should NOT advance because it's off-screen
        for (int frame = 1; frame <= 20; frame++) {
            ring.update(frame, null);
        }
        assertEquals(0, ring.getMappingFrame(), "mapping_frame should not advance while off-screen");
        assertTrue(ring.isForming(), "Ring should still be forming while off-screen");

        // Move camera back so ring is on-screen
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);

        // Now the formation should start advancing
        ring.update(21, null);
        // The first on-screen frame is loc_85B02's release, which does not run
        // Animate_Raw at all, so mapping_frame is untouched.
        assertEquals(0, ring.getMappingFrame(), "First on-screen frame: still on initial mapping frame");
        assertTrue(ring.isForming(), "Ring should still be forming after 1 on-screen frame");
    }

    @Test
    public void formationWaitsForRomRenderBoxToReachViewport() {
        Sonic3kSSEntryRingObjectInstance ring = createRing(0);

        // Obj_WaitOffscreen sets width_pixels/height_pixels to $20. An object
        // box whose right edge only touches the viewport is still off-screen.
        AbstractObjectInstance.updateCameraBounds(
                RING_X + 0x20, RING_Y - 112, RING_X + 0x20 + 320, RING_Y + 112, 0);
        ring.update(1, null);
        assertEquals(0, ring.getMappingFrame());

        // One pixel of overlap sets the render flag and starts Animate_Raw.
        AbstractObjectInstance.updateCameraBounds(
                RING_X + 0x1F, RING_Y - 112, RING_X + 0x1F + 320, RING_Y + 112, 0);
        // Frame 2 is the loc_85B02 release; frames 3-8 are the first six
        // Animate_Raw calls (advance to mapping_frame 0, hold, then advance to 1).
        for (int frame = 2; frame <= 2 + RELEASE_FRAMES + FRAMES_PER_ANIM_STEP; frame++) {
            ring.update(frame, null);
        }
        assertEquals(1, ring.getMappingFrame(),
                "formation should begin at the ROM's 32-pixel render boundary");
    }

    /**
     * Core test: a player standing inside the collision box must NOT trigger
     * the ring during the entire 40-frame formation period.
     * <p>
     * This exercises the full collision path (player present, alive, not in
     * debug mode, centre within SSEntry_Range box) and verifies the ROM's
     * {@code cmpi.b #8,mapping_frame(a0) / blo.s locret} gate blocks it.
     */
    @Test
    public void playerInsideCollisionBoxCannotTriggerDuringFormation() {
        Sonic3kSSEntryRingObjectInstance ring = createRing(0);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        // Step through every frame of formation with the player overlapping
        for (int frame = 1; frame <= RELEASE_FRAMES + FORMATION_TOTAL_FRAMES; frame++) {
            ring.update(frame, player);
            assertTrue(ring.isForming(), "Ring should still be forming at frame " + frame
                            + " (mapping_frame=" + ring.getMappingFrame() + ")");
            assertTrue(ring.isMainState(), "Ring should remain in MAIN state at frame " + frame);
            assertFalse(ring.isDestroyed(), "Ring should not be destroyed during formation at frame " + frame);
        }
    }

    /**
     * After the 40-frame formation completes, a player inside the collision
     * box MUST trigger the ring. Uses the "all emeralds collected" path
     * (awards 50 rings and destroys) to avoid needing camera/flash services.
     */
    @Test
    public void playerInsideCollisionBoxTriggerAfterFormation() {
        // Set up all-emeralds path so onTouched() takes the simple destroy route
        gameState.configureSpecialStageProgress(7, 7);
        for (int i = 0; i < 7; i++) {
            gameState.markEmeraldCollected(i);
        }
        assertTrue(gameState.hasAllEmeralds(), "Precondition: all emeralds collected");

        Sonic3kSSEntryRingObjectInstance ring = createRing(0);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        // Advance through entire formation
        for (int frame = 1; frame <= RELEASE_FRAMES + FORMATION_TOTAL_FRAMES; frame++) {
            ring.update(frame, player);
        }
        assertTrue(ring.isForming(), "Ring should still be forming after the release frame plus FORMATION_TOTAL_FRAMES");

        // One more frame transitions to idle (mapping_frame >= 8) â†’ collision fires
        ring.update(FIRST_IDLE_FRAME, player);
        assertFalse(ring.isForming(), "Ring should no longer be forming");
        // With all emeralds, onTouched awards 50 rings and destroys the ring
        assertTrue(ring.isDestroyed(), "Ring should be destroyed after player triggered it");
        assertTrue(ring.isDestroyedRespawnable(),
                "the marked display tail reaches Go_Delete_SpriteSlotted and clears placement ownership");
    }

    /**
     * Regression for the time-attack giant-ring softlock: touching the ring
     * while {@code GameStateManager.isTimeAttackActive()} must leave the ring
     * fully inert (no SFX/state change, ring not destroyed, MAIN state kept)
     * instead of hiding/locking the player before GameLoop's chokepoint gate
     * is ever reached.
     */
    @Test
    public void timeAttackActiveSuppressesTouchEvenWithAllEmeralds() {
        // All-emeralds path would normally destroy the ring and award rings —
        // verifies the time-attack gate wins over every branch in onTouched().
        gameState.configureSpecialStageProgress(7, 7);
        for (int i = 0; i < 7; i++) {
            gameState.markEmeraldCollected(i);
        }
        gameState.setTimeAttackActive(true);

        Sonic3kSSEntryRingObjectInstance ring = createRing(0);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        advanceToIdleAndTouch(ring, player);

        assertFalse(ring.isDestroyed(), "Ring must stay inert while a time attack is active");
        assertTrue(ring.isMainState(), "Ring must remain in MAIN state (no Special Stage sequence started)");
        verify(player, never()).addRings(anyInt());
    }

    /**
     * ROM {@code SSEntryRing_Main}'s collision branch
     * (docs/skdisasm/sonic3k.asm:128283-128291) never reads {@code subtype}:
     * with fewer than 7 Chaos Emeralds the {@code bne.s loc_6173A} takes the
     * capture sequence regardless of the ring's subtype bit 7. The
     * negative-subtype test belongs to {@code SSEntryFlash_GoSS}
     * (sonic3k.asm:128393) at the far end of the flash, so a bit-7 ring must
     * lock the player here, not pay out 50 rings, and must not request a zone
     * change on the touch frame.
     */
    @Test
    public void subtypeBitSevenStillEntersTheCaptureSequenceOnTouch() {
        Camera camera = mock(Camera.class);
        services.withCamera(camera);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        Sonic3kSSEntryRingObjectInstance ring = createRing(0x80 | 3);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        advanceToIdleAndTouch(ring, player);

        assertFalse(ring.isDestroyed(), "the capture sequence keeps the ring alive for the flash to retire");
        assertEquals(-1, services.requestedZone, "the touch frame itself requests no zone change");
        assertEquals(-1, services.requestedAct);
        assertFalse(services.deactivateLevelNow, "the touch frame must not freeze level updates for a transition");
        verify(player, never()).addRings(50);
        verify(player).setHidden(true);
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    public void negativeSubtypeAlwaysUsesGlowingSuperEmeraldRingAndRoutesAfterFlash() {
        CapturingRing ring = createCapturingRing(0x80 | 3);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        advanceToIdleAndTouch(ring, player);

        assertTrue(ring.isSuperEmeraldRing(),
                "ROM negative subtypes set the rotating Super Emerald ring palette unconditionally");
        assertFalse(ring.isDestroyed(), "collision starts the flash; it must not transition immediately");
        assertNotNull(ring.flash);
        assertNull(services.savedReturn, "Save_Level_Data2 runs at flash completion, not collision");
        assertEquals(-1, services.requestedZone);

        finishFlash(ring.flash, player);

        assertEquals(Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA, services.requestedZone);
        assertEquals(1, services.requestedAct);
        assertTrue(services.deactivateLevelNow);
        assertNotNull(services.savedReturn);
        assertEquals(RING_X, services.savedReturn.playerX());
        assertEquals(RING_Y, services.savedReturn.playerY());
        assertTrue(gameState.isSpecialRingCollected(3),
                "the negative subtype must still address the masked physical-ring bit");
        verify(player, never()).addRings(anyInt());
        assertEquals(1, services.enterSsSfxCount,
                "SSEntryFlash_GoSS plays EnterSS once on the sanctuary route");
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    public void positiveMhzRingWithAllChaosRoutesToSanctuaryAfterFlash() {
        collectAllChaosEmeralds();
        services.currentZone = Sonic3kZoneIds.ZONE_MHZ;
        CapturingRing ring = createCapturingRing(4);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        advanceToIdleAndTouch(ring, player);

        assertTrue(ring.isSuperEmeraldRing(),
                "locked-on SK-side ring with seven Chaos Emeralds uses the rotating palette");
        assertNotNull(ring.flash);
        assertEquals(-1, services.requestedZone);

        finishFlash(ring.flash, player);

        assertEquals(Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA, services.requestedZone);
        assertEquals(1, services.requestedAct);
        verify(player, never()).addRings(anyInt());
        assertEquals(1, services.enterSsSfxCount);
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    public void superEmeraldPaletteLeadInUsesRomDelayAndColors() {
        Sonic3kSSEntryRingObjectInstance ring = createRing(0x80);

        ring.update(1, null);
        assertEquals(0, ring.getPaletteStepForTest(),
                "Obj_WaitOffscreen release consumes the first update");

        ring.update(2, null);
        assertEquals(1, ring.getPaletteStepForTest());
        assertArrayEquals(new int[] {0xECE, 0xA8A, 0x868},
                ring.getLastAppliedPaletteWordsForTest());

        for (int frame = 3; frame <= 4; frame++) {
            ring.update(frame, null);
        }
        assertEquals(1, ring.getPaletteStepForTest(),
                "palscriptdata 3 holds the first color for three object updates");

        ring.update(5, null);
        assertEquals(2, ring.getPaletteStepForTest());
        assertArrayEquals(new int[] {0xAEE, 0x6EE, 0x0AA},
                ring.getLastAppliedPaletteWordsForTest());
    }

    @Test
    public void s3kProviderDoesNotDoublePlayFlashOwnedEnterSfx() {
        assertEquals(-1, new Sonic3kSpecialStageProvider().getTransitionSfxId());
    }

    @Test
    public void positiveMhzRingWithoutAllChaosStillRequestsOrdinarySpecialStage() {
        services.currentZone = Sonic3kZoneIds.ZONE_MHZ;
        CapturingRing ring = createCapturingRing(5);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        advanceToIdleAndTouch(ring, player);
        assertFalse(ring.isSuperEmeraldRing());
        finishFlash(ring.flash, player);

        assertEquals(-1, services.requestedZone);
        assertEquals(1, services.specialStageRequests);
        assertEquals(1, services.enterSsSfxCount,
                "ordinary entry must not defer a second EnterSS playback to GameLoop");
    }

    @Test
    public void flashCompletionUsesLiveEmeraldAndFlashOwnedSaved2State() {
        services.currentZone = Sonic3kZoneIds.ZONE_MHZ;
        services.currentAct = 1;
        services.apparentAct = 0;
        services.checkpoint = mock(RespawnState.class);
        services.levelState = mock(LevelState.class);
        when(services.levelState.getTimerFrames()).thenReturn(12_345L);
        when(services.levelState.getRingExtraLifeFlags()).thenReturn(0x06);
        when(services.water.captureFullScreenFlag(
                Sonic3kZoneIds.ZONE_MHZ, 1, 0x90)).thenReturn(true);
        CapturingRing ring = createCapturingRing(5);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        advanceToIdleAndTouch(ring, player);
        assertNotNull(ring.flash);
        assertNull(services.savedReturn);

        collectAllChaosEmeralds();
        when(player.getCentreX()).thenReturn((short) 0x222);
        when(player.getCentreY()).thenReturn((short) 0x333);
        when(player.getRingCount()).thenReturn(77);
        when(player.hasShield()).thenReturn(true);
        when(player.getShieldType()).thenReturn(ShieldType.LIGHTNING);
        when(camera.getX()).thenReturn((short) 0x180);
        when(camera.getY()).thenReturn((short) 0x90);

        finishFlash(ring.flash, player);

        assertEquals(Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA, services.requestedZone,
                "GoSS must evaluate the live completion state, not collision-time emerald progress");
        assertNotNull(services.savedReturn);
        assertEquals(RING_X, services.savedReturn.playerX(),
                "Save_Level_Data2 reads the executing flash object's x_pos");
        assertEquals(RING_Y, services.savedReturn.playerY(),
                "Save_Level_Data2 reads the executing flash object's y_pos");
        assertEquals(77, services.savedReturn.rings());
        assertEquals(0x180, services.savedReturn.cameraX());
        assertEquals(0x90, services.savedReturn.cameraY());
        assertEquals(12_345L, services.savedReturn.timerFrames());
        assertEquals(0x06, services.savedReturn.extraLifeFlags());
        assertEquals(1 << 5, services.savedReturn.statusSecondary());
        assertEquals((Sonic3kZoneIds.ZONE_MHZ << 8), services.savedReturn.apparentZoneAndAct());
        assertTrue(services.savedReturn.waterFullScreen());
        verify(services.checkpoint, never()).clear();
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    public void positiveFbzRingUsesSkSideRoutingPredicate() {
        collectAllChaosEmeralds();
        services.currentZone = Sonic3kZoneIds.ZONE_FBZ;
        CapturingRing ring = createCapturingRing(6);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        advanceToIdleAndTouch(ring, player);
        assertTrue(ring.isSuperEmeraldRing(), "FBZ is the ROM predicate's exceptional SK-side zone");
        finishFlash(ring.flash, player);

        assertEquals(Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA, services.requestedZone);
    }

    @Test
    public void positiveLbzRingWithAllChaosAwardsFiftyBecauseItIsS3Side() {
        collectAllChaosEmeralds();
        services.currentZone = Sonic3kZoneIds.ZONE_LBZ;
        Sonic3kSSEntryRingObjectInstance ring = createRing(7);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        advanceToIdleAndTouch(ring, player);

        assertTrue(ring.isDestroyed());
        assertFalse(ring.isSuperEmeraldRing());
        verify(player).addRings(50);
        assertEquals(2, services.bigRingSfxCount,
                "ROM loc_61794 deliberately plays BigRing again for the 50-ring reward");
    }

    @Test
    public void waitingFlashRewindCompletesAfterParentRingHasBeenDeleted() {
        services.currentZone = Sonic3kZoneIds.ZONE_MHZ;
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);
        Sonic3kSSEntryFlashObjectInstance source =
                new Sonic3kSSEntryFlashObjectInstance(null, RING_X, RING_Y, 0x80 | 3);
        source.setServices(services);

        for (int frame = 1; frame <= 20; frame++) {
            source.update(frame, player);
        }
        var snapshot = source.captureRewindState();

        Sonic3kSSEntryFlashObjectInstance restored =
                new Sonic3kSSEntryFlashObjectInstance(null, RING_X, RING_Y, 0x80 | 3);
        restored.setServices(services);
        restored.restoreRewindState(snapshot);
        for (int frame = 21; frame <= 60 && services.requestedZone < 0; frame++) {
            restored.update(frame, player);
        }

        assertEquals(Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA, services.requestedZone);
        assertNotNull(services.savedReturn);
        assertTrue(gameState.isSpecialRingCollected(3));
        assertEquals(1, services.enterSsSfxCount);
    }

    @Test
    public void flashSnapshotCarriesManagedParentIdentityWithoutCapturingJavaParent() {
        ObjectRefId parentId = ObjectRefId.dynamic(4, 0, 1);
        S3kBigRingTransitionIntent intent =
                new S3kBigRingTransitionIntent(1, parentId);
        Sonic3kSSEntryFlashObjectInstance source =
                new Sonic3kSSEntryFlashObjectInstance(null, RING_X, RING_Y, intent);
        source.setServices(services);
        var snapshot = source.captureRewindState();

        Sonic3kSSEntryFlashObjectInstance restored =
                new Sonic3kSSEntryFlashObjectInstance(null, RING_X, RING_Y, 1);
        restored.setServices(services);
        restored.restoreRewindState(snapshot);

        assertEquals(parentId, restored.transitionIntentForTest().parentRingId());
        assertNull(restored.parentRingForTest(),
                "Java parent references are never nearest-position reconstructed");
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    public void allSuperEmeraldsAwardFiftyOnSkSideInsteadOfEnteringSanctuary() {
        collectAllChaosAndSuperEmeralds();
        services.currentZone = Sonic3kZoneIds.ZONE_MHZ;
        services.level = mock(Level.class);
        Palette normalLineTwo = new Palette();
        setPaletteWord(normalLineTwo, 5, 0x246);
        setPaletteWord(normalLineTwo, 6, 0x68A);
        setPaletteWord(normalLineTwo, 15, 0xACE);
        when(services.level.getPaletteCount()).thenReturn(2);
        when(services.level.getPalette(1)).thenReturn(normalLineTwo);
        Sonic3kSSEntryRingObjectInstance ring = createRing(4);
        AbstractPlayableSprite player = createMockPlayerAt(RING_X, RING_Y);

        advanceToIdleAndTouch(ring, player);

        assertTrue(ring.isDestroyed(), "completed emerald state should remove the ring");
        assertEquals(-1, services.requestedAct);
        verify(player).addRings(50);
        assertArrayEquals(new int[] {0x0EE, 0x088, 0x044},
                ring.getLastAppliedPaletteWordsForTest(),
                "loc_6196A restores the instruction-immediate normal ring colors");
    }

    @Test
    public void collectedRingIsDestroyedImmediately() {
        // Mark bit 3 as collected
        gameState.markSpecialRingCollected(3);

        Sonic3kSSEntryRingObjectInstance ring = createRing(3);
        // ensureInitialized() is called lazily on the first update(), not in the constructor.
        // One update call triggers the collected-state check and sets destroyed=true.
        ring.update(1, null);
        assertTrue(ring.isDestroyed(), "Collected ring should be immediately destroyed");
        assertFalse(ring.isDestroyedRespawnable(),
                "the initial collected-bit precheck uses Delete_Current_Sprite, not the slotted display tail");
    }

    @Test
    public void idleAnimationLoops() {
        Sonic3kSSEntryRingObjectInstance ring = createRing(0);

        // Advance through formation (40 frames). On frame 41, the 9th advance
        // triggers the transition to idle: mapping_frame = 10, timer = 6.
        for (int frame = 1; frame <= RELEASE_FRAMES + FORMATION_TOTAL_FRAMES; frame++) {
            ring.update(frame, null);
        }
        // Frame FORMATION_TOTAL_FRAMES+1 triggers the transition advance
        ring.update(FIRST_IDLE_FRAME, null);
        assertEquals(10, ring.getMappingFrame(), "First idle frame should be 10");

        // Now in idle animation. Idle: delay=6, frames={10,9,8,11}, loop.
        // Each idle step lasts delay+1 = 7 game frames.
        // The first idle frame (10) was set by the transition and is held for
        // 7 game frames (timer=6 + 1 underflow frame = ticks 41-47).
        // We're at tick 41 now, so 6 more frames to finish the first idle step.
        int gameFrame = FIRST_IDLE_FRAME;
        for (int tick = 0; tick < 6; tick++) {
            gameFrame++;
            ring.update(gameFrame, null);
        }
        assertEquals(10, ring.getMappingFrame(), "mapping_frame should still be 10 during first idle countdown");

        // Next group: advance to mapping_frame 9
        int[] expectedIdleFrames = {9, 8, 11, 10, 9}; // rest of first loop + start of second
        for (int step = 0; step < expectedIdleFrames.length; step++) {
            for (int tick = 0; tick < 7; tick++) {
                gameFrame++;
                ring.update(gameFrame, null);
            }
            assertEquals(expectedIdleFrames[step], ring.getMappingFrame(), "Idle mapping frame at step " + step);
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Creates a Mockito mock of {@link AbstractPlayableSprite} positioned at
     * the given centre coordinates. The mock is alive (not dead), not in debug
     * mode, and has a no-op {@code addRings} to allow the all-emeralds path.
     */
    private AbstractPlayableSprite createMockPlayerAt(int cx, int cy) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) cx);
        when(player.getCentreY()).thenReturn((short) cy);
        when(player.getDead()).thenReturn(false);
        when(player.isDebugMode()).thenReturn(false);
        // addRings is void â€” Mockito defaults to no-op, no stub needed
        return player;
    }

    private Sonic3kSSEntryRingObjectInstance createRing(int subtype) {
        setConstructionContext(services);
        try {
            ObjectSpawn spawn = new ObjectSpawn(RING_X, RING_Y, 0x85, subtype, 0, false, 0);
            Sonic3kSSEntryRingObjectInstance ring = new Sonic3kSSEntryRingObjectInstance(spawn);
            ring.setServices(services);
            return ring;
        } finally {
            clearConstructionContext();
        }
    }

    private CapturingRing createCapturingRing(int subtype) {
        setConstructionContext(services);
        try {
            CapturingRing ring = new CapturingRing(
                    new ObjectSpawn(RING_X, RING_Y, 0x85, subtype, 0, false, 0));
            ring.setServices(services);
            return ring;
        } finally {
            clearConstructionContext();
        }
    }

    private static void finishFlash(Sonic3kSSEntryFlashObjectInstance flash,
            AbstractPlayableSprite player) {
        assertNotNull(flash);
        // ROM: one routine-0 init dispatch + nine SSEntryFlash_Main dispatches
        // + 33 Obj_Wait dispatches ($20 decremented through -1) reaches GoSS
        // on the flash object's 43rd execution, never on the 42nd.
        for (int frame = 1; frame <= 42; frame++) {
            flash.update(frame, player);
        }
        assertFalse(flash.isDestroyed(), "SSEntryFlash_GoSS must not run before update 43");
        flash.update(43, player);
        assertTrue(flash.isDestroyed(), "SSEntryFlash_GoSS must run on update 43");
    }

    private static void advanceToIdleAndTouch(Sonic3kSSEntryRingObjectInstance ring, AbstractPlayableSprite player) {
        for (int frame = 1; frame <= FIRST_IDLE_FRAME; frame++) {
            ring.update(frame, player);
        }
    }

    private void collectAllChaosAndSuperEmeralds() {
        gameState.configureSpecialStageProgress(7, 7);
        for (int i = 0; i < 7; i++) {
            gameState.markEmeraldCollected(i);
            gameState.markSuperEmeraldCollected(i);
        }
        assertTrue(gameState.hasAllEmeralds(), "Precondition: all chaos emeralds collected");
        assertTrue(gameState.hasAllSuperEmeralds(), "Precondition: all super emeralds collected");
    }

    private void collectAllChaosEmeralds() {
        gameState.configureSpecialStageProgress(7, 7);
        for (int i = 0; i < 7; i++) {
            gameState.markEmeraldCollected(i);
        }
    }

    private static final class CapturingRing extends Sonic3kSSEntryRingObjectInstance {
        private Sonic3kSSEntryFlashObjectInstance flash;

        private CapturingRing(ObjectSpawn spawn) {
            super(spawn);
        }

        @Override
        protected void spawnDynamicObjectLowestFreeSlot(AbstractObjectInstance object) {
            flash = (Sonic3kSSEntryFlashObjectInstance) object;
            flash.setServices(services());
        }
    }

    private static class CapturingObjectServices extends TestObjectServices {
        int requestedZone = -1;
        int requestedAct = -1;
        boolean deactivateLevelNow;
        int currentZone;
        int currentAct;
        int apparentAct;
        int specialStageRequests;
        BigRingReturnState savedReturn;
        int bigRingSfxCount;
        int enterSsSfxCount;
        RespawnState checkpoint;
        LevelState levelState;
        com.openggf.level.WaterSystem water = mock(com.openggf.level.WaterSystem.class);
        Level level;

        @Override
        public CapturingObjectServices withGameState(GameStateManager gameState) {
            super.withGameState(gameState);
            return this;
        }

        @Override
        public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) {
            this.requestedZone = zone;
            this.requestedAct = act;
            this.deactivateLevelNow = deactivateLevelNow;
        }

        @Override
        public int romZoneId() {
            return currentZone;
        }

        @Override
        public int currentAct() {
            return currentAct;
        }

        @Override
        public int apparentAct() {
            return apparentAct;
        }

        @Override
        public RespawnState checkpointState() {
            return checkpoint;
        }

        @Override
        public LevelState levelGamestate() {
            return levelState;
        }

        @Override
        public com.openggf.level.WaterSystem waterSystem() {
            return water;
        }

        @Override
        public Level currentLevel() {
            return level;
        }

        @Override
        public void requestSpecialStageEntry() {
            specialStageRequests++;
        }

        @Override
        public void saveBigRingReturn(BigRingReturnState state) {
            savedReturn = state;
        }

        @Override
        public void playSfx(int soundId) {
            if (soundId == Sonic3kSfx.BIG_RING.id) {
                bigRingSfxCount++;
            } else if (soundId == Sonic3kSfx.ENTER_SS.id) {
                enterSsSfxCount++;
            }
        }
    }

    private static void setPaletteWord(Palette palette, int colorIndex, int segaWord) {
        palette.getColor(colorIndex).fromSegaFormat(new byte[] {
                (byte) (segaWord >>> 8), (byte) segaWord
        }, 0);
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices svc) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(svc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
