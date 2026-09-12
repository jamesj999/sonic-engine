package com.openggf.game.sonic3k;

import com.openggf.game.sonic3k.constants.S3kZoneSet;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;

import java.util.Optional;

/**
 * Maps canonical engine identity to S3K ROM resource/event identity.
 */
public record Sonic3kLevelResourceProfile(
        int romZone,
        int romAct,
        S3kZoneSet objectZoneSet,
        EventKind eventKind,
        Optional<CustomLevelResources> customResources) {

    public enum EventKind {
        STANDARD,
        HPZ_SPECIAL_STAGE_HUB
    }

    public Sonic3kLevelResourceProfile {
        if (romZone < 0 || romAct < 0 || romAct > 1 || objectZoneSet == null || eventKind == null
                || customResources == null) {
            throw new IllegalArgumentException("invalid S3K level resource profile");
        }
    }

    public record CustomLevelResources(
            int layoutAddress,
            int primaryArtAddress,
            int secondaryArtAddress,
            int primaryBlocksAddress,
            int secondaryBlocksAddress,
            int primaryChunksAddress,
            int secondaryChunksAddress,
            int introPaletteAddress,
            int mainPaletteAddress,
            int primaryPlc,
            int secondaryPlc,
            int cameraX,
            int cameraY,
            int minX,
            int maxX,
            int minY,
            int maxY,
            boolean suppressTitleCard) {

        public CustomLevelResources {
            if (layoutAddress <= 0 || primaryArtAddress <= 0 || secondaryArtAddress <= 0
                    || primaryBlocksAddress <= 0 || secondaryBlocksAddress <= 0
                    || primaryChunksAddress <= 0 || secondaryChunksAddress <= 0
                    || introPaletteAddress <= 0 || mainPaletteAddress <= 0
                    || primaryPlc <= 0 || secondaryPlc <= 0) {
                throw new IllegalArgumentException("custom S3K resources require verified ROM sources");
            }
        }

        public int sanctuaryPaletteAddress(boolean conversionPresentationComplete) {
            return conversionPresentationComplete ? mainPaletteAddress : introPaletteAddress;
        }
    }

    private static final CustomLevelResources HPZ_RESOURCES =
            new CustomLevelResources(
                    Sonic3kConstants.HPZ_SANCTUARY_LAYOUT_ADDR,
                    Sonic3kConstants.HPZ_PRIMARY_ART_ADDR,
                    Sonic3kConstants.HPZ_SECONDARY_ART_ADDR,
                    Sonic3kConstants.HPZ_PRIMARY_BLOCKS_ADDR,
                    Sonic3kConstants.HPZ_SECONDARY_BLOCKS_ADDR,
                    Sonic3kConstants.HPZ_PRIMARY_CHUNKS_ADDR,
                    Sonic3kConstants.HPZ_SECONDARY_CHUNKS_ADDR,
                    Sonic3kConstants.HPZ_INTRO_PALETTE_ADDR,
                    Sonic3kConstants.HPZ_MAIN_PALETTE_ADDR,
                    Sonic3kConstants.HPZ_LEVEL_PLC,
                    Sonic3kConstants.HPZ_LEVEL_PLC,
                    0x15A0, 0x0240,
                    0x1500, 0x1640, 0x0320, 0x0320,
                    true);

    private static final Sonic3kLevelResourceProfile HPZ_SANCTUARY =
            new Sonic3kLevelResourceProfile(0x17, 1, S3kZoneSet.SKL,
                    EventKind.HPZ_SPECIAL_STAGE_HUB,
                    Optional.of(HPZ_RESOURCES));

    public static Sonic3kLevelResourceProfile resolve(int canonicalZone, int canonicalAct) {
        // SSEntryFlash_GoSS / loc_618AC restarts into $1701. Retain
        // $1601 as the existing engine sanctuary alias. Both must select
        // the same resources and HPZS screen-event dispatch.
        if (canonicalAct == 1 && (canonicalZone == Sonic3kZoneIds.ZONE_HPZ
                || canonicalZone == Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA)) {
            return HPZ_SANCTUARY;
        }
        return new Sonic3kLevelResourceProfile(
                canonicalZone, canonicalAct, S3kZoneSet.forZone(canonicalZone),
                EventKind.STANDARD,
                Optional.empty());
    }

    public int tableIndex() {
        return romZone * 2 + romAct;
    }

    /** ROM Current_zone_and_act identity consumed by S3K screen/event owners. */
    public int romEventIdentity() {
        return (romZone << 8) | romAct;
    }

    public CustomLevelResources requireCustomResources() {
        return customResources.orElseThrow(
                () -> new IllegalStateException("level has no custom resource payload"));
    }
}
