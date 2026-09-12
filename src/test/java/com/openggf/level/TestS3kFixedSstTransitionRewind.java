package com.openggf.level;

import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kFixedSstTransitionRewind {
    @ParameterizedTest
    @CsvSource({"7,4", "1,5"})
    void carriedFixedOccupantReplacesFreshInitializationOwner(int zone, int slot) throws Exception {
        HeadlessTestFixture.builder()
                .withSharedLevel(SharedLevel.load(SonicGame.SONIC_3K, zone, 0)).build();
        var level = GameServices.level();
        var previous = level.getObjectManager();
        var original = previous.getActiveObjects().stream()
                .filter(o -> o instanceof AbstractObjectInstance a && a.getSlotIndex() == slot)
                .findFirst().orElseThrow();
        var originalId = previous.captureIdentityContext().requireIdentityTable().encodeObject(original);
        level.executeActTransition(SeamlessLevelTransitionRequest.builder(
                        SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(zone, 1)
                .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.TITLE_OWNER)
                .objectSurvivalPolicy(SeamlessLevelTransitionRequest.ObjectSurvivalPolicy.PERSISTENT_EXACT_SST)
                .preserveLevelGamestate(true)
                .build());

        var manager = level.getObjectManager();
        var registry = new RewindRegistry();
        registry.register(manager.rewindSnapshottable());
        var snapshot = assertDoesNotThrow(registry::capture,
                "the seamless boundary must not register fresh and carried owners under the same identity");
        assertEquals(1, manager.getActiveObjects().stream()
                .filter(original.getClass()::isInstance).count());
        assertTrue(manager.getActiveObjects().contains(original));
        assertEquals(slot, ((AbstractObjectInstance) original).getSlotIndex());
        assertSame(original, manager.captureIdentityContext().requireIdentityTable().resolve(originalId));
        assertDoesNotThrow(() -> registry.restore(snapshot));
        assertEquals(1, manager.getActiveObjects().stream()
                .filter(original.getClass()::isInstance).count());
        assertDoesNotThrow(registry::capture);
    }
}
