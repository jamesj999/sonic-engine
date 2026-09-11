package com.openggf.level;

import com.openggf.game.GameServices;
import com.openggf.game.OscillationManager;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TestLevelSeamlessTransitionExecutor {
    private LevelManager levelManager;
    private LevelTransitionCoordinator transitions;
    private LevelSeamlessTransitionExecutor executor;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        levelManager = mock(LevelManager.class);
        transitions = mock(LevelTransitionCoordinator.class);
        executor = new LevelSeamlessTransitionExecutor(levelManager, transitions);
    }

    @Test
    void reloadSameNormalizationCopiesEveryRequestFieldAndReplacesOnlyTarget() {
        SeamlessTransitionResourceHandoffId handoffId =
                new SeamlessTransitionResourceHandoffId(73);
        SeamlessLevelTransitionRequest source = SeamlessLevelTransitionRequest
                .builder(SeamlessLevelTransitionRequest.TransitionType.RELOAD_SAME_LEVEL)
                .targetZoneAct(90, 91)
                .deactivateLevelNow(true)
                .preserveMusic(false)
                .preserveLevelGamestate(true)
                .preserveEndOfLevelState(true)
                .preserveEndOfLevelActive(false)
                .showInLevelTitleCard(true)
                .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER)
                .resetLevelGamestateAtInLevelTitleCardDisplay(true)
                .inLevelTitleCardResetAdditionalDispatches(12)
                .inLevelTitleCardResetPhaseOneDispatchOverlap(6)
                .lockPlayerControlForInLevelTitleCard(true)
                .inLevelTitleCardExitAdditionalDispatches(10)
                .inLevelTitleCardExitPhaseOneDispatchOverlap(5)
                .forceAirOnStaleObjectSupportLoss(true)
                .preserveOffsetCameraPosition(true)
                .postTransitionMinX(101)
                .postTransitionMaxX(102)
                .postTransitionMinY(103)
                .postTransitionMaxY(104)
                .postTransitionMaxYTarget(105)
                .playerOffset(106, 107)
                .cameraOffset(108, 109)
                .mutationKey("copy-all-fields")
                .musicOverrideId(110)
                .resourceHandoff(handoffId)
                .build();

        SeamlessLevelTransitionRequest normalized =
                LevelSeamlessTransitionExecutor.normalizeReloadSameRequest(
                        source, 7, 1);

        assertEquals(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL,
                normalized.type());
        assertEquals(7, normalized.targetZone());
        assertEquals(1, normalized.targetAct());
        assertEquals(source.deactivateLevelNow(), normalized.deactivateLevelNow());
        assertEquals(source.preserveMusic(), normalized.preserveMusic());
        assertEquals(source.preserveLevelGamestate(), normalized.preserveLevelGamestate());
        assertEquals(source.preserveEndOfLevelActive(), normalized.preserveEndOfLevelActive());
        assertEquals(source.preserveEndOfLevelFlag(), normalized.preserveEndOfLevelFlag());
        assertEquals(source.showInLevelTitleCard(), normalized.showInLevelTitleCard());
        assertEquals(source.runtimeArtAdmissionPolicy(), normalized.runtimeArtAdmissionPolicy());
        assertEquals(source.resetLevelGamestateAtInLevelTitleCardDisplay(),
                normalized.resetLevelGamestateAtInLevelTitleCardDisplay());
        assertEquals(source.inLevelTitleCardResetAdditionalDispatches(),
                normalized.inLevelTitleCardResetAdditionalDispatches());
        assertEquals(source.inLevelTitleCardResetPhaseOneDispatchOverlap(),
                normalized.inLevelTitleCardResetPhaseOneDispatchOverlap());
        assertEquals(source.lockPlayerControlForInLevelTitleCard(),
                normalized.lockPlayerControlForInLevelTitleCard());
        assertEquals(source.inLevelTitleCardExitAdditionalDispatches(),
                normalized.inLevelTitleCardExitAdditionalDispatches());
        assertEquals(source.inLevelTitleCardExitPhaseOneDispatchOverlap(),
                normalized.inLevelTitleCardExitPhaseOneDispatchOverlap());
        assertEquals(source.forceAirOnStaleObjectSupportLoss(),
                normalized.forceAirOnStaleObjectSupportLoss());
        assertEquals(source.preserveOffsetCameraPosition(),
                normalized.preserveOffsetCameraPosition());
        assertEquals(source.postTransitionMinX(), normalized.postTransitionMinX());
        assertEquals(source.postTransitionMaxX(), normalized.postTransitionMaxX());
        assertEquals(source.postTransitionMinY(), normalized.postTransitionMinY());
        assertEquals(source.postTransitionMaxY(), normalized.postTransitionMaxY());
        assertEquals(source.postTransitionMaxYTarget(), normalized.postTransitionMaxYTarget());
        assertEquals(source.playerOffsetX(), normalized.playerOffsetX());
        assertEquals(source.playerOffsetY(), normalized.playerOffsetY());
        assertEquals(source.cameraOffsetX(), normalized.cameraOffsetX());
        assertEquals(source.cameraOffsetY(), normalized.cameraOffsetY());
        assertEquals(source.mutationKey(), normalized.mutationKey());
        assertEquals(source.musicOverrideId(), normalized.musicOverrideId());
        assertSame(handoffId, normalized.resourceHandoffId());
    }

    @Test
    void nullRequestHasNoTransitionSideEffects() {
        executor.execute(null);

        verifyNoInteractions(levelManager, transitions);
    }

    @Test
    void mutateOnlyRunsMutationWithoutReloadFrameBridgeAndAlwaysClearsTransitionState()
            throws Exception {
        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(SeamlessLevelTransitionRequest.TransitionType.MUTATE_ONLY)
                .mutationKey("fire-overlay")
                .build();

        executor.execute(request);

        verify(transitions).setSpecialStageReturnLevelReloadRequested(false);
        verify(levelManager).applySeamlessMutation("fire-overlay");
        verify(levelManager, never()).executeActTransition(any());
        verify(levelManager, never()).advanceGlobalOscillation();
        verify(transitions).setLevelInactiveForTransition(false);
    }

    @Test
    void reloadSameDispatchesNormalizedRequestAndRunsReloadFrameBridge() throws Exception {
        levelManager.currentZone = 5;
        levelManager.currentAct = 1;
        levelManager.objectManager = mock(ObjectManager.class);
        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(SeamlessLevelTransitionRequest.TransitionType.RELOAD_SAME_LEVEL)
                .playerOffset(9, -12)
                .build();

        executor.execute(request);

        ArgumentCaptor<SeamlessLevelTransitionRequest> capture =
                ArgumentCaptor.forClass(SeamlessLevelTransitionRequest.class);
        verify(levelManager).executeActTransition(capture.capture());
        verify(levelManager).consumeActTransitionRewindBoundaryDuringFrame();
        assertEquals(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL,
                capture.getValue().type());
        assertEquals(5, capture.getValue().targetZone());
        assertEquals(1, capture.getValue().targetAct());
        assertEquals(9, capture.getValue().playerOffsetX());
        assertEquals(-12, capture.getValue().playerOffsetY());
        verify(levelManager).advanceGlobalOscillationAtLevelLoopTail();
        verify(levelManager.objectManager).advanceVblaCounter();
        verify(levelManager).markSidekickRomVisibleReloadFrameCounterBridge();
        verify(transitions).setLevelInactiveForTransition(false);
    }

    @Test
    void reloadTargetDispatchesOriginalRequestAndRunsReloadFrameBridge() throws Exception {
        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(6, 0)
                .build();

        executor.execute(request);

        verify(levelManager).executeActTransition(request);
        verify(levelManager).consumeActTransitionRewindBoundaryDuringFrame();
        verify(levelManager).advanceGlobalOscillationAtLevelLoopTail();
        verify(transitions).setLevelInactiveForTransition(false);
    }

    @Test
    void checkedReloadFailureIsWrappedAndFinallyClearsTransitionState() throws Exception {
        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(6, 0)
                .build();
        IOException failure = new IOException("broken load");
        doThrow(failure).when(levelManager).executeActTransition(request);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> executor.execute(request));

        assertEquals("Failed to apply seamless transition", thrown.getMessage());
        assertSame(failure, thrown.getCause());
        verify(levelManager, never()).advanceGlobalOscillation();
        verify(transitions).setLevelInactiveForTransition(false);
    }

    @Test
    void runtimeMutationFailurePropagatesAndFinallyClearsTransitionState() {
        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(SeamlessLevelTransitionRequest.TransitionType.MUTATE_ONLY)
                .mutationKey("broken")
                .build();
        IllegalStateException failure = new IllegalStateException("broken mutation");
        doThrow(failure).when(levelManager).applySeamlessMutation("broken");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> executor.execute(request));

        assertSame(failure, thrown);
        verify(transitions).setLevelInactiveForTransition(false);
    }

    @Test
    void reloadFrameBridgeAdvancesStoredLevelSpriteVintAndOscillationClocks() {
        LevelManager realLevelManager = GameServices.level();
        SpriteManager spriteManager = GameServices.sprites();
        realLevelManager.frameCounter = 0x153F;
        spriteManager.setFrameCounter(0x153F);
        ObjectManager objectManager = mock(ObjectManager.class);
        realLevelManager.objectManager = objectManager;
        OscillationManager.reset();
        OscillationManager.update(0x153E);
        int[] oscillationBeforeReload = OscillationManager.valuesForTest();

        new LevelSeamlessTransitionExecutor(
                realLevelManager, realLevelManager.getTransitions())
                .advanceFrameCounterAcrossReload();

        assertEquals(0x1540, realLevelManager.getFrameCounter());
        assertEquals(0x1540, spriteManager.getFrameCounter());
        verify(objectManager).advanceVblaCounter();
        assertTrue(realLevelManager.isSidekickRomVisibleReloadFrameCounterBridgeActive());
        assertFalse(java.util.Arrays.equals(
                oscillationBeforeReload, OscillationManager.valuesForTest()));
    }
}
