package com.openggf.tests;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kMgzPulleyMapping {

    @Test
    void mgz2ArtRegistryUsesPulleyMappingTableBaseNotFirstFramePayload() {
        var entry = Sonic3kPlcArtRegistry.getPlan(2, 1).levelArt().stream()
                .filter(levelArt -> Sonic3kObjectArtKeys.MGZ_PULLEY.equals(levelArt.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "MGZ2 art plan should include pulley level art"));

        assertEquals(Sonic3kConstants.MAP_MGZ_PULLEY_ADDR, entry.mappingAddr(),
                "Pulley must point at the mapping table base, not the first frame payload");
        assertEquals(0x2340C0, entry.mappingAddr());
        assertTrue(entry.mappingAddr() < 0x2340CE,
                "Pulley mapping address should precede the first frame payload");
    }
}
