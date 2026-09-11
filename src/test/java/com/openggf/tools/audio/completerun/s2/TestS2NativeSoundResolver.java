package com.openggf.tools.audio.completerun.s2;

import com.openggf.game.sonic2.audio.Sonic2Music;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestS2NativeSoundResolver {
    private final S2NativeSoundResolver resolver = S2NativeSoundResolver.rev01();

    @Test
    void emeraldHillNativeAndEngineIdsResolveToOneRomAsset() {
        var nativeSound = resolver.fromNativeId(0x82);
        var engineSound = resolver.fromEngineMusic(Sonic2Music.EMERALD_HILL.id);

        assertEquals("music.rom.0f88c4", nativeSound.contentKey());
        assertEquals(nativeSound.contentKey(), engineSound.contentKey());
        assertEquals(0x82, nativeSound.nativeId());
        assertEquals(0x81, engineSound.engineApiId());
        assertNotEquals(nativeSound.nativeId(), engineSound.engineApiId());
    }

    @Test
    void extraLifeNativeAndEngineIdsResolveToTheUncompressedRomSpan() {
        var nativeSound = resolver.fromNativeId(0x98);
        var engineSound = resolver.fromEngineMusic(Sonic2Music.EXTRA_LIFE.id);

        assertEquals("music.rom.0fd48d", nativeSound.contentKey());
        assertEquals(nativeSound.contentKey(), engineSound.contentKey());
        assertEquals(0x0fd48d, nativeSound.romStart());
        assertEquals(0x0fd57a, nativeSound.romEndExclusive());
        assertEquals(0x98, nativeSound.nativeId());
        // MusID_ExtraLife is 98 (s2disasm/s2.constants.asm:856) and B5 is
        // SndID_Ring / SndID_RingRight (s2.constants.asm:896-897), so the
        // engine id for the 1-up jingle is 98 here as well; the two spaces
        // coincide for this entry.
        assertEquals(0x98, engineSound.engineApiId());
    }

    @Test
    void unknownNativeOrEngineMusicIdFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> resolver.fromNativeId(0x80));
        assertThrows(IllegalArgumentException.class, () -> resolver.fromEngineMusic(0x95));
    }
}
