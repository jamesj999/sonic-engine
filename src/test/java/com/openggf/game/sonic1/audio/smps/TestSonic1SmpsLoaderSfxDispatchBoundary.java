package com.openggf.game.sonic1.audio.smps;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.audio.Sonic1SmpsConstants;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the normal-vs-special SFX pointer table dispatch boundary in
 * {@link Sonic1SmpsLoader#loadSfx(int)}.
 *
 * <p>ROM reference: {@code docs/s1disasm/s1.sounddriver.asm} {@code PlaySoundID}
 * (lines 691-699) dispatches the normal SFX range ({@code sfx__First}-{@code
 * sfx__Last}, {@code docs/s1disasm/_Constants.asm:281,330} = 0xA0-0xCF) and the
 * special SFX range ({@code spec__First}-{@code spec__Last}, {@code
 * _Constants.asm:333,335} = 0xD0-0xD0) as two disjoint ranges -- the normal
 * range's upper bound is checked (and branches to {@code Sound_PlaySFX}) BEFORE
 * the special range is even considered, so 0xD0 (the sole special SFX,
 * {@code sfx_Waterfall}) is only ever reachable through the special path.
 *
 * <p>The loader's original boundary check reused {@link Sonic1Sfx#ID_MAX}
 * (0xD0, "highest SFX id including special") as the normal-table cutoff, so
 * 0xD0 satisfied {@code sfxId <= ID_MAX} and was dispatched through the
 * NORMAL path instead -- it happened not to warn or read garbage only because
 * this ROM's {@code SFX_PTR_TABLE_ADDR + SFX_COUNT*4} is byte-identical to
 * {@code SPECIAL_SFX_PTR_TABLE_ADDR} by coincidence, so the same valid
 * pointer was read either way, just with a less-precise fallback blob-size
 * calculation ({@code calculateSfxDataSize}'s final "no next pointer found"
 * branch caps at 0x800 bytes) than {@code loadSpecialSfx}'s own generous
 * single-entry read (up to {@code MAX_BLOB_SIZE} = 0x4000 bytes, bounded only
 * by ROM end). That length difference is what this test pins.
 */
@RequiresRom(SonicGame.SONIC_1)
class TestSonic1SmpsLoaderSfxDispatchBoundary {
    private Sonic1SmpsLoader loader;

    @BeforeEach
    void setUp() {
        Rom rom = TestEnvironment.currentRom();
        loader = new Sonic1SmpsLoader(rom);
    }

    @Test
    void lastNormalTableIdStillLoadsThroughTheNormalPath() {
        // 0xCF (SIGNPOST) is the last entry in the 48-entry normal SFX
        // pointer table (ID_BASE 0xA0 + SFX_COUNT 48 - 1). Unaffected by the
        // boundary fix -- this pins that the fix didn't shrink the normal
        // range's own upper edge.
        assertEquals(Sonic1Sfx.ID_BASE + Sonic1SmpsConstants.SFX_COUNT - 1, Sonic1Sfx.NORMAL_ID_MAX);
        AbstractSmpsData data = loader.loadSfx(0xCF);
        assertNotNull(data, "0xCF (last normal-table id) must still load");
        assertEquals(0xCF, data.getId());
    }

    @Test
    void specialTableIdDispatchesThroughTheSpecialPathNotTheNormalTable() {
        // 0xD0 (WATERFALL) is the ROM's sole special SFX id. Before the fix
        // this was RED: the normal path's coincidentally-valid-but-imprecise
        // fallback size calculation capped the read at 0x800 bytes; the
        // special path's own generous single-entry read allows up to
        // MAX_BLOB_SIZE (0x4000) bytes bounded only by ROM end, which is
        // strictly larger for this ROM.
        AbstractSmpsData data = loader.loadSfx(0xD0);
        assertNotNull(data, "0xD0 (special SFX id) must load");
        assertEquals(0xD0, data.getId());
        assertTrue(data.getData().length > 0x800,
                "0xD0 must dispatch through loadSpecialSfx's generous read, not the normal "
                        + "path's 0x800-byte-capped last-entry fallback (was " + data.getData().length + " bytes)");
    }
}
