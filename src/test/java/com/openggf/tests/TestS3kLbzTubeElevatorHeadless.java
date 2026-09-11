package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kLbzTubeElevatorHeadless {
    private Object oldSkipIntros;
    private Object oldMainCharacter;
    private Object oldSidekickCharacters;

    @BeforeEach
    void setUpConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacters = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
    }

    @AfterEach
    void restoreConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacters != null ? oldSidekickCharacters : "");
    }

    @Test
    void lbz1TubeElevatorSuppressesClosedDestinationAndReleasesLeft() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 0)
                .startPosition((short) 3916, (short) 1381)
                .build();
        removeLbz1GroundLaunchIntro();

        AbstractPlayableSprite sonic = fixture.sprite();
        boolean sawCapture = false;
        boolean sawRelease = false;
        int releaseFrame = -1;
        int releaseX = -1;
        int maxPostReleaseX = -1;
        ElevatorSnapshot badClosedDestination = null;
        List<ElevatorSnapshot> lastElevators = List.of();

        for (int frame = 0; frame < 720; frame++) {
            boolean holdRightUntilCapture = !sawCapture;
            fixture.stepFrame(false, false, false, holdRightUntilCapture, false);

            List<ElevatorSnapshot> elevators = elevatorSnapshots();
            if (!elevators.isEmpty()) {
                lastElevators = elevators;
            }

            boolean controlled = sonic.isObjectControlled();
            if (controlled) {
                sawCapture = true;
                badClosedDestination = visibleClosedDestinationDuringActiveTransit(elevators);
                if (badClosedDestination != null) {
                    break;
                }
            } else if (sawCapture) {
                if (!sawRelease) {
                    sawRelease = true;
                    releaseFrame = frame;
                    releaseX = sonic.getCentreX() & 0xFFFF;
                    maxPostReleaseX = releaseX;
                } else {
                    maxPostReleaseX = Math.max(maxPostReleaseX, sonic.getCentreX() & 0xFFFF);
                }
                if (frame - releaseFrame >= 30) {
                    break;
                }
            }
        }

        assertTrue(sawCapture, "Sonic should enter the LBZ tube elevator from debug-overlay start 3916,1381");
        assertFalse(badClosedDestination != null,
                "Obj_LBZTubeElevatorClosed should move to x=$7FF0 while Sonic is inside an active tube elevator; "
                        + "observed " + badClosedDestination + " with active elevators " + lastElevators);
        assertTrue(sawRelease, "The elevator should eventually release Sonic at the destination");
        assertNotNull(finalActiveElevator(lastElevators),
                "The test should observe the moving LBZ tube elevator before release");
        assertTrue(maxPostReleaseX <= releaseX + 1,
                "Sonic should not be ejected to the right after a left-facing destination; "
                        + "releaseX=" + releaseX + " maxPostReleaseX=" + maxPostReleaseX
                        + " elevators=" + lastElevators);
        assertEquals(Direction.LEFT, sonic.getDirection(),
                "LBZTubeElevator_CheckPlayer bset #Status_Facing on release, so Sonic should face left");
        assertFalse(sonic.isObjectMappingFrameControl(),
                "Sonic should leave the tube elevator's rotating object-owned mapping frames after release");
    }

    private void removeLbz1GroundLaunchIntro() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        List<ObjectInstance> intros = objectManager.getActiveObjects().stream()
                .filter(object -> "LBZ1GroundLaunchIntro".equals(object.getName()))
                .toList();
        for (ObjectInstance intro : intros) {
            objectManager.removeDynamicObject(intro);
        }
    }

    private ElevatorSnapshot visibleClosedDestinationDuringActiveTransit(List<ElevatorSnapshot> elevators) {
        boolean activeTransitElevatorVisible = elevators.stream()
                .anyMatch(snapshot -> !snapshot.closedOnly && snapshot.x != 0x7FF0);
        if (!activeTransitElevatorVisible) {
            return null;
        }
        return elevators.stream()
                .filter(snapshot -> snapshot.closedOnly && snapshot.x != 0x7FF0)
                .findFirst()
                .orElse(null);
    }

    private ElevatorSnapshot finalActiveElevator(List<ElevatorSnapshot> elevators) {
        return elevators.stream()
                .filter(snapshot -> !snapshot.closedOnly)
                .max(Comparator.comparingInt(snapshot -> snapshot.x))
                .orElse(null);
    }

    private List<ElevatorSnapshot> elevatorSnapshots() {
        List<ElevatorSnapshot> snapshots = new ArrayList<>();
        for (ObjectInstance object : GameServices.level().getObjectManager().getActiveObjects()) {
            if (!"LBZTubeElevator".equals(object.getName())) {
                continue;
            }
            int subtype = object.getSpawn().subtype() & 0xFF;
            snapshots.add(new ElevatorSnapshot(
                    object.getX() & 0xFFFF,
                    object.getY() & 0xFFFF,
                    subtype,
                    (subtype & 0x40) != 0));
        }
        return snapshots;
    }

    private record ElevatorSnapshot(int x, int y, int subtype, boolean closedOnly) {
        @Override
        public String toString() {
            return String.format("x=$%04X y=$%04X subtype=$%02X closed=%s", x, y, subtype, closedOnly);
        }
    }
}
