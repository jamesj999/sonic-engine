package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.objects.HPZSSEntryControlObjectInstance;
import com.openggf.game.sonic3k.objects.HPZSanctuaryFallingCrystalObjectInstance;
import com.openggf.game.sonic3k.objects.HPZSuperEmeraldObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-lifecycle coverage for the Hidden Palace Super Emerald sanctuary.
 *
 * <p>Unlike the object-local tests, this boots the ROM-backed HPZ mini-level
 * and advances the real placement, object-manager, camera and player pipeline.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kHpzSanctuaryHeadless {

    @ParameterizedTest
    @ValueSource(ints = {0x16, 0x17})
    void sanctuaryPublishesEverySceneSpriteRenderer(int zone) {
        HeadlessTestFixture.builder().withZoneAndAct(zone, 1).build();
        var art = GameServices.module().getObjectArtProvider();
        for (String key : List.of(Sonic3kObjectArtKeys.HPZ_MASTER_EMERALD,
                Sonic3kObjectArtKeys.HPZ_GRAY_EMERALD,
                Sonic3kObjectArtKeys.HPZ_SMALL_EMERALDS,
                Sonic3kObjectArtKeys.HPZ_ENTRY_TELEPORTER)) {
            assertNotNull(art.getRenderer(key), "Missing sanctuary renderer: " + key);
            var sheet = art.getSheet(key);
            assertNotNull(sheet, "Missing sanctuary sheet: " + key);
            assertTrue(sheet.getPatterns().length > 0, "Missing ROM art: " + key);
            assertTrue(sheet.getFrameCount() > 0, "Missing ROM mappings: " + key);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0x16, 0x17})
    void sanctuaryWithoutChaosEmeraldsUnlocksWithoutConversionPan(int zone) {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(zone, 1)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        fixture.stepIdleFrames(180);

        assertFalse(sonic.isObjectControlled(),
                "loc_90A94 goes directly to loc_90C16 when no state-1 emerald exists");
        assertFalse(GameServices.level().getObjectManager().getActiveObjects().stream()
                        .anyMatch(object -> object.getClass().getSimpleName()
                                .contains("SanctuarySmallEmerald")),
                "the seven-small-Emerald ceremony only exists for state-1 conversion");
    }

    @ParameterizedTest
    @ValueSource(ints = {0x16, 0x17})
    void freshSanctuaryStartsItsCeremonyAtTheSpawnCameraAndRestoresSonic(int zone) {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(zone, 1)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();
        ObjectManager objects = GameServices.level().getObjectManager();
        HPZSSEntryControlObjectInstance controller = objects.getActiveObjects().stream()
                .filter(HPZSSEntryControlObjectInstance.class::isInstance)
                .map(HPZSSEntryControlObjectInstance.class::cast)
                .findFirst().orElseThrow();
        GameServices.gameState().restoreS3kEmeraldProgress(
                List.of(1, 1, 1, 1, 1, 1, 1), false);

        assertTrue(objects.getActiveObjects().stream()
                        .anyMatch(HPZSSEntryControlObjectInstance.class::isInstance),
                "the ROM HPZ mini-level controller must be active at the initial camera");

        fixture.stepIdleFrames(40);
        assertTrue(objects.getActiveObjects().stream()
                        .anyMatch(HPZSanctuaryFallingCrystalObjectInstance.class::isInstance),
                "the intro crystal must begin without moving Sonic or the camera");

        fixture.stepIdleFrames(100);
        assertTrue(objects.getActiveObjects().stream()
                        .anyMatch(object -> object.getClass().getSimpleName()
                                .contains("SanctuarySmallEmerald")),
                "the ROM immediately starts the seven-Chaos-Emerald orbit ceremony");

        int completionFrame = -1;
        for (int frame = 140; frame < 2_400; frame++) {
            fixture.stepIdleFrames(1);
            if (!sonic.isObjectControlled()) {
                completionFrame = frame;
                break;
            }
        }
        assertTrue(completionFrame >= 0,
                "the complete fresh ceremony must restore player control within its ROM sequence; "
                        + controller.ceremonyStateForTest());
        assertTrue(completionFrame <= 1_100,
                "loc_90C2A counts the $1F inter-drop timer while the previous "
                        + "crystal remains active; ceremony completed at " + completionFrame);
        assertFalse(sonic.isObjectControlled(),
                "the complete fresh ceremony must restore player control");
        assertFalse(sonic.isHidden(),
                "the complete fresh ceremony must leave Sonic visible");
        NativePositionOps.writeXPosPreserveSubpixel(sonic, 0x1600);
        fixture.stepIdleFrames(4);
        assertFalse(sonic.isObjectControlled(),
                "loc_90C16 permanently advances to loc_90C34; fresh-entry control "
                        + "must not be reapplied after unlock");
        assertFalse(sonic.isObjectMappingFrameControl(),
                "ceremony mapping ownership must remain released");
        assertTrue((sonic.getCentreX() & 0xFFFF) < 0x1640,
                "post-unlock frames must not teleport Sonic back to the sanctuary centre");
        var frameBounds = sonic.getSpriteRenderer().getFrameBounds(
                sonic.getMappingFrame(), sonic.getRenderHFlip(), sonic.getRenderVFlip());
        assertTrue(frameBounds.maxX() >= frameBounds.minX()
                        && frameBounds.maxY() >= frameBounds.minY(),
                "the restored Sonic mapping/DPLC frame must contain drawable pieces");
    }

    @ParameterizedTest
    @ValueSource(ints = {0x16, 0x17})
    void everyPedestalSurvivesAWalkAcrossTheSanctuaryAndBack(int zone) {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(zone, 1)
                .build();
        GameServices.gameState().restoreS3kEmeraldProgress(
                List.of(2, 2, 2, 2, 2, 2, 2), true);
        ObjectManager objects = GameServices.level().getObjectManager();
        fixture.stepIdleFrames(30);

        assertEquals(List.of(0x1550, 0x15A0, 0x15E0, 0x1640, 0x16A0, 0x16E0, 0x1730),
                pedestalPositions(objects),
                "all seven gray Super Emerald pedestals must be placed");

        for (int frame = 0; frame < 400; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }
        assertEquals(0x1640, fixture.camera().getX() & 0xFFFF,
                "walking right must reach the sanctuary's camera maximum");
        for (int frame = 0; frame < 600; frame++) {
            fixture.stepFrame(false, false, true, false, false);
        }

        // The $B4 pedestals are controller-created children with no respawn
        // entry, so a single out_of_range unload removes them for good — the
        // leftmost ($1550) sits more than $80 behind the camera at $1640.
        assertEquals(List.of(0x1550, 0x15A0, 0x15E0, 0x1640, 0x16A0, 0x16E0, 0x1730),
                pedestalPositions(objects),
                "no pedestal may be culled by scrolling the sanctuary");
        assertTrue(objects.getActiveObjects().stream()
                        .filter(HPZSuperEmeraldObjectInstance.class::isInstance)
                        .map(HPZSuperEmeraldObjectInstance.class::cast)
                        .filter(pedestal -> pedestal.getX() == 0x1550)
                        .allMatch(HPZSuperEmeraldObjectInstance::isSelectable),
                "the leftmost pedestal must still offer its Super Emerald stage");
    }

    private static List<Integer> pedestalPositions(ObjectManager objects) {
        return objects.getActiveObjects().stream()
                .filter(HPZSuperEmeraldObjectInstance.class::isInstance)
                .map(object -> ((HPZSuperEmeraldObjectInstance) object).getX())
                .sorted()
                .toList();
    }
}
