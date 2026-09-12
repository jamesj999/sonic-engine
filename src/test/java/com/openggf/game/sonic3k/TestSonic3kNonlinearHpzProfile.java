package com.openggf.game.sonic3k;

import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.S3kZoneSet;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.LevelDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kNonlinearHpzProfile {
    @Test
    void resultsHubCameraUsesTheRomStageTable() {
        int[] expected = {0x15A0, 0x1540, 0x1600, 0x1500, 0x1640, 0x14B0, 0x1690};
        for (int stage = 0; stage < expected.length; stage++) {
            assertEquals(expected[stage], Sonic3kLevelInitProfile.hpzReturnCameraX(stage));
        }
        assertThrows(IllegalArgumentException.class,
                () -> Sonic3kLevelInitProfile.hpzReturnCameraX(7));
    }

    @Test
    void sanctuaryDescriptorKeepsCanonicalIdentityButSelectsRom1701Resources() {
        Sonic3kZoneRegistry registry = new Sonic3kZoneRegistry();
        LevelDescriptor descriptor = registry.getLevelDataForZone(
                Sonic3kZoneIds.ZONE_HPZ).get(1);

        assertEquals(0xED, descriptor.levelIndex());
        assertEquals("LAVA REEF", registry.getZoneName(Sonic3kZoneIds.ZONE_HPZ));
        assertEquals(0x1640, descriptor.startX());
        assertEquals(0x03AC, descriptor.startY());

        Sonic3kLevelResourceProfile profile =
                Sonic3kLevelResourceProfile.resolve(Sonic3kZoneIds.ZONE_HPZ, 1);
        assertEquals(0x17, profile.romZone());
        assertEquals(1, profile.romAct());
        assertEquals(0x2F, profile.tableIndex());
        assertEquals(Sonic3kConstants.LEVEL_LOAD_BLOCK_HPZ_SANCTUARY_INDEX,
                profile.tableIndex());
        assertEquals(S3kZoneSet.SKL, profile.objectZoneSet());
        assertEquals(0x1701, profile.romEventIdentity());
        assertEquals(Sonic3kLevelResourceProfile.EventKind.HPZ_SPECIAL_STAGE_HUB,
                profile.eventKind());
        var resources = profile.requireCustomResources();
        assertEquals(0x0A7924, resources.layoutAddress());
        assertEquals(0x1BEE58, resources.primaryArtAddress());
        assertEquals(0x1C3F2C, resources.secondaryArtAddress());
        assertEquals(0x1BECF8, resources.primaryBlocksAddress());
        assertEquals(0x1C30FC, resources.secondaryBlocksAddress());
        assertEquals(0x1BFBEA, resources.primaryChunksAddress());
        assertEquals(0x1C71FE, resources.secondaryChunksAddress());
        assertEquals(0x0A9D3C, resources.introPaletteAddress());
        assertEquals(0x0669D2, resources.mainPaletteAddress());
        assertEquals(0x0A9D3C, resources.sanctuaryPaletteAddress(false));
        assertEquals(0x0669D2, resources.sanctuaryPaletteAddress(true));
        assertEquals(0x48, resources.primaryPlc());
        assertEquals(0x48, resources.secondaryPlc());
        assertEquals(0x15A0, resources.cameraX());
        assertEquals(0x0240, resources.cameraY(),
                "SpecialStage_Results writes Camera_Y_pos=$240 before j_LevelSetup");
        assertEquals(0x1500, resources.minX());
        assertEquals(0x1640, resources.maxX());
        assertEquals(0x0320, resources.minY());
        assertEquals(0x0320, resources.maxY());
        assertTrue(resources.suppressTitleCard());
        assertEquals(Sonic3kMusic.LRZ2.id,
                registry.getMusicId(Sonic3kZoneIds.ZONE_HPZ, 1));
    }

    @Test
    void rawZoneTableRetainsLrzBossWithoutAliasingSanctuaryResources() {
        Sonic3kZoneRegistry registry = new Sonic3kZoneRegistry();

        Sonic3kLevelResourceProfile profile =
                Sonic3kLevelResourceProfile.resolve(Sonic3kZoneIds.ZONE_HPZ, 0);
        assertEquals(Sonic3kZoneIds.ZONE_HPZ, profile.romZone());
        assertEquals(0, profile.romAct());
        assertEquals(S3kZoneSet.SKL, profile.objectZoneSet());
        assertEquals(Sonic3kLevelResourceProfile.EventKind.STANDARD,
                profile.eventKind());
        assertTrue(profile.customResources().isEmpty());
        LevelDescriptor descriptor = registry.getLevelDataForZone(
                Sonic3kZoneIds.ZONE_HPZ).get(0);
        assertEquals(0xEC, descriptor.levelIndex());
        assertEquals(0x0040, descriptor.startX());
        assertEquals(0x0070, descriptor.startY());
        assertEquals(Sonic3kMusic.BOSS.id,
                registry.getMusicId(Sonic3kZoneIds.ZONE_HPZ, 0));
    }

    @Test
    void sanctuarySuppressesOrdinaryLevelTitleCard() {
        Sonic3kZoneFeatureProvider features = new Sonic3kZoneFeatureProvider();

        assertTrue(features.shouldSuppressInitialTitleCard(
                Sonic3kZoneIds.ZONE_HPZ, 1));
    }

    @Test
    void eventManagerSelectsHpzsHandlersFromRomIdentityWithoutChangingCanonicalZone() {
        Sonic3kLevelEventManager events = new Sonic3kLevelEventManager();

        events.initLevel(Sonic3kZoneIds.ZONE_HPZ, 1);

        assertEquals(0x1701, events.getActiveRomEventIdentity());
        assertEquals(Sonic3kLevelEventManager.ScreenEventIdentity.HPZ_SPECIAL_STAGE_HUB,
                events.getScreenEventIdentity());

    }

    @Test
    void giantRingDestinationSelectsTheCompleteSanctuaryProfile() {
        var profile = Sonic3kLevelResourceProfile.resolve(
                Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA, 1);
        assertEquals(Sonic3kLevelResourceProfile.resolve(Sonic3kZoneIds.ZONE_HPZ, 1),
                profile);
        Sonic3kLevelEventManager events = new Sonic3kLevelEventManager();
        events.initLevel(Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA, 1);
        assertEquals(Sonic3kLevelEventManager.ScreenEventIdentity.HPZ_SPECIAL_STAGE_HUB,
                events.getScreenEventIdentity());
        assertEquals(Sonic3kLevelResourceProfile.EventKind.STANDARD,
                Sonic3kLevelResourceProfile.resolve(
                        Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA, 0).eventKind(),
                "the paired Death Egg boss act is not the sanctuary");
    }

    @Test
    void ordinaryLevelsRetainLinearRomProfile() {
        Sonic3kLevelResourceProfile profile =
                Sonic3kLevelResourceProfile.resolve(Sonic3kZoneIds.ZONE_MHZ, 0);

        assertEquals(Sonic3kZoneIds.ZONE_MHZ, profile.romZone());
        assertEquals(0, profile.romAct());
        assertEquals(0x0E, profile.tableIndex());
        assertEquals(0x0700, profile.romEventIdentity());
        assertEquals(Sonic3kLevelResourceProfile.EventKind.STANDARD,
                profile.eventKind());
        assertTrue(profile.customResources().isEmpty());
    }
}
