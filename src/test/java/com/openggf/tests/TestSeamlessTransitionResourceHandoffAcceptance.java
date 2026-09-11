package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.level.SeamlessTransitionResourceHandoff;
import com.openggf.level.SeamlessTransitionResourceHandoffId;
import com.openggf.level.resources.CompressionType;
import com.openggf.level.resources.DeferredLevelResourceDescriptor;
import com.openggf.level.resources.DeferredLevelResourceManifest;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestSeamlessTransitionResourceHandoffAcceptance {
    private HeadlessTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_HCZ, 0)
                .build();
        GameServices.camera().setFocusedSprite(fixture.sprite());
    }

    @Test
    void reloadSameLevelPreservesAndTransfersOpaqueHandoffExactlyOnce() {
        TestHandoff handoff =
                new TestHandoff(DeferredLevelResourceManifest.EMPTY);
        SeamlessTransitionResourceHandoffId id =
                GameServices.seamlessTransitionResourceHandoffs()
                        .register(handoff);
        CompositeSnapshot beforeClaim =
                fixture.gameplayMode().getRewindRegistry().capture();
        SeamlessLevelTransitionRequest request =
                SeamlessLevelTransitionRequest.builder(
                                SeamlessLevelTransitionRequest.TransitionType
                                        .RELOAD_SAME_LEVEL)
                        .preserveMusic(true)
                        .resourceHandoff(id)
                        .build();

        GameServices.level().applySeamlessTransition(request);

        assertEquals(1, handoff.transferCount.get());
        CompositeSnapshot afterClaim =
                fixture.gameplayMode().getRewindRegistry().capture();
        assertThrows(IllegalStateException.class,
                () -> GameServices.seamlessTransitionResourceHandoffs()
                        .claim(id));

        fixture.gameplayMode().getRewindRegistry().restore(beforeClaim);
        GameServices.level().applySeamlessTransition(request);
        assertEquals(2, handoff.transferCount.get(),
                "rewind before claim must replay one transfer on the new timeline");

        fixture.gameplayMode().getRewindRegistry().restore(afterClaim);
        assertThrows(IllegalStateException.class,
                () -> GameServices.seamlessTransitionResourceHandoffs()
                        .claim(id));
    }

    @Test
    void queuedTransitionConsumesAndTransfersOpaqueHandoff() {
        TestHandoff handoff =
                new TestHandoff(DeferredLevelResourceManifest.EMPTY);
        SeamlessTransitionResourceHandoffId id =
                GameServices.seamlessTransitionResourceHandoffs()
                        .register(handoff);
        GameServices.level().requestSeamlessTransition(
                SeamlessLevelTransitionRequest.builder(
                                SeamlessLevelTransitionRequest.TransitionType
                                        .RELOAD_TARGET_LEVEL)
                        .targetZoneAct(Sonic3kZoneIds.ZONE_HCZ, 1)
                        .preserveMusic(true)
                        .resourceHandoff(id)
                        .build());

        fixture.stepIdleFrames(1);

        assertEquals(1, handoff.transferCount.get());
        assertThrows(IllegalStateException.class,
                () -> GameServices.seamlessTransitionResourceHandoffs()
                        .claim(id));
    }

    @Test
    void failedExecutionConsumesHandoffAndCannotRetryIt() {
        DeferredLevelResourceManifest unsupported =
                new DeferredLevelResourceManifest(List.of(
                        new DeferredLevelResourceDescriptor(
                                DeferredLevelResourceDescriptor.Kind
                                        .PATTERNS_8X8,
                                0x123456,
                                CompressionType.KOSINSKI_MODULED,
                                0x200)));
        TestHandoff handoff = new TestHandoff(unsupported);
        SeamlessTransitionResourceHandoffId id =
                GameServices.seamlessTransitionResourceHandoffs()
                        .register(handoff);
        SeamlessLevelTransitionRequest request =
                SeamlessLevelTransitionRequest.builder(
                                SeamlessLevelTransitionRequest.TransitionType
                                        .RELOAD_TARGET_LEVEL)
                        .targetZoneAct(Sonic3kZoneIds.ZONE_HCZ, 1)
                        .preserveMusic(true)
                        .resourceHandoff(id)
                        .build();

        assertThrows(IllegalStateException.class,
                () -> GameServices.level().applySeamlessTransition(request));
        assertTrue(GameServices.seamlessTransitionResourceHandoffs()
                        .hasFailedTransfer(id),
                "a destructive claim followed by load failure must retain a terminal fence");
        CompositeSnapshot failedTransfer =
                fixture.gameplayMode().getRewindRegistry().capture();
        assertThrows(IllegalStateException.class,
                () -> GameServices.seamlessTransitionResourceHandoffs()
                        .peek(id));
        assertThrows(IllegalStateException.class,
                () -> GameServices.level().applySeamlessTransition(request));
        assertEquals(0, handoff.transferCount.get());

        fixture.gameplayMode().getRewindRegistry().restore(failedTransfer);
        assertTrue(GameServices.seamlessTransitionResourceHandoffs()
                .hasFailedTransfer(id));
        assertThrows(IllegalStateException.class,
                () -> GameServices.level().applySeamlessTransition(request));
        assertEquals(0, handoff.transferCount.get(),
                "restoring a failed-transfer fence cannot retry target publication");
    }

    private static final class TestHandoff
            implements SeamlessTransitionResourceHandoff {
        private final DeferredLevelResourceManifest manifest;
        private final AtomicInteger transferCount = new AtomicInteger();

        private TestHandoff(
                DeferredLevelResourceManifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public DeferredLevelResourceManifest deferredResources() {
            return manifest;
        }

        @Override
        public void transferAfterTargetInit() {
            transferCount.incrementAndGet();
        }
    }
}
