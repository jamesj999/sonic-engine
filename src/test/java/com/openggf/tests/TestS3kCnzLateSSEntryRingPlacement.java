package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.Sonic3kSSEntryRingObjectInstance;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzLateSSEntryRingPlacement {
    private static final int RING_X = 0x2DC0;
    private static final int RING_Y = 0x064C;

    @Test
    void lateCnzRingRetainsCursorOwnershipAndQueuesExplosionParentOnce() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(3, 0)
                .startPosition((short) RING_X, (short) RING_Y)
                .startPositionIsCentre()
                .build();
        fixture.sprite().setCentreX((short) RING_X);
        fixture.sprite().setCentreY((short) RING_Y);
        GameServices.camera().setFocusedSprite(fixture.sprite());
        GameServices.camera().setX((short) 0x2D20);
        GameServices.camera().setY((short) 0x05C0);

        ObjectSpawn spawn = GameServices.level().getObjectManager().getAllSpawns().stream()
                .filter(candidate -> candidate.x() == RING_X)
                .filter(candidate -> candidate.y() == RING_Y)
                .filter(candidate -> candidate.objectId() == 0x85)
                .filter(candidate -> candidate.subtype() == 4)
                .findFirst()
                .orElseThrow(() -> new AssertionError("CNZ fixture must contain the late subtype-4 ring"));

        fixture.stepFrame(false, false, false, false, false);
        Sonic3kSSEntryRingObjectInstance ring = GameServices.level().getObjectManager()
                .getActiveObjects().stream()
                .filter(Sonic3kSSEntryRingObjectInstance.class::isInstance)
                .map(Sonic3kSSEntryRingObjectInstance.class::cast)
                .filter(candidate -> candidate.getSpawn() == spawn)
                .findFirst()
                .orElse(null);
        assertNotNull(ring,
                "the two-axis cursor must construct the exact late CNZ ring after reaching its entry");

        Sonic3kObjectArtProvider artProvider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        GameServices.camera().setMinX((short) 0x2D20);
        GameServices.camera().setMaxX((short) 0x2D20);
        GameServices.camera().setMinY((short) 0x05C0);
        GameServices.camera().setMaxY((short) 0x05C0);
        fixture.sprite().setCentreX((short) 0x2C00);
        fixture.sprite().setCentreY((short) 0x0500);
        for (int frame = 0; frame < 10_000
                && (!artProvider.capture().pendingKosModules().isEmpty()
                || !S3kRuntimeArtCoordinator.current().moduleQueue().hasCapacityFor(1)); frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(List.of(), artProvider.capture().pendingKosModules(),
                "pre-existing CNZ art must leave the parent FIFO before retirement");
        long directChildrenBeforeRetirement = TestEnvironment.activeGameplayMode()
                .hardwareTiming().capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                .filter(job -> job.romSourceAddress()
                        == Sonic3kConstants.ART_KOSM_BADNIK_EXPLOSION_ADDR + 2)
                .filter(job -> job.destinationAddress()
                        == S3kKosRamDestinations.KOS_DECOMP_BUFFER)
                .filter(job -> job.handle().submissionFingerprint().equals(
                        "sha256:3c96d8b9573e86f26814cb8a605459c8fef23cc1ca5425db2fd1cc250d408d91"))
                .count();

        // First visible dispatch releases Obj_WaitOffscreen. Moving the camera out
        // of both ROM display bands on the next production dispatch reaches loc_6196A.
        fixture.stepFrame(false, false, false, false, false);
        GameServices.camera().setX((short) 0x0100);
        GameServices.camera().setY((short) 0x0100);
        fixture.stepFrame(false, false, false, false, false);
        assertFalse(GameServices.level().getObjectManager().getActiveObjects().contains(ring),
                "the exact placed ring must retire through its existing display tail");

        var matches = TestEnvironment.activeGameplayMode().hardwareTiming().capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .filter(job -> job.romSourceAddress()
                        == Sonic3kConstants.ART_KOSM_BADNIK_EXPLOSION_ADDR)
                .filter(job -> job.destinationAddress()
                        == Sonic3kConstants.ARTTILE_EXPLOSION * 32)
                .toList();
        assertEquals(1, matches.size(),
                "retireRing submits exact ArtKosM_BadnikExplosion parent once");
        assertEquals("sha256:70da89e553f70fe647a00489dec5f2612854986b444b87a2e8d81ab0f821e431",
                matches.getFirst().handle().submissionFingerprint(),
                "the exact parent must carry its stable hardware fingerprint");

        assertEquals("kosinski_moduled", matches.getFirst().compressionVariant(),
                "0xDB406 is the exact KosM parent whose first direct child starts at 0xDB408");

        for (int frame = 0; frame < 10_000 && TestEnvironment.activeGameplayMode()
                .hardwareTiming().capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE
                        && job.romSourceAddress()
                        == Sonic3kConstants.ART_KOSM_BADNIK_EXPLOSION_ADDR + 2
                        && job.destinationAddress()
                        == S3kKosRamDestinations.KOS_DECOMP_BUFFER
                        && job.handle().submissionFingerprint().equals(
                        "sha256:3c96d8b9573e86f26814cb8a605459c8fef23cc1ca5425db2fd1cc250d408d91"))
                .count() == directChildrenBeforeRetirement; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        var directMatches = TestEnvironment.activeGameplayMode().hardwareTiming().capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                .filter(job -> job.romSourceAddress()
                        == Sonic3kConstants.ART_KOSM_BADNIK_EXPLOSION_ADDR + 2)
                .filter(job -> job.destinationAddress()
                        == S3kKosRamDestinations.KOS_DECOMP_BUFFER)
                .filter(job -> job.handle().submissionFingerprint().equals(
                        "sha256:3c96d8b9573e86f26814cb8a605459c8fef23cc1ca5425db2fd1cc250d408d91"))
                .toList();
        assertEquals(directChildrenBeforeRetirement + 1, directMatches.size(),
                "retirement must materialize exact first child 0xDB408 exactly once");
    }
}
