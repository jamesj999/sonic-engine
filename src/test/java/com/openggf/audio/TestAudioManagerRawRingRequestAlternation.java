package com.openggf.audio;

import com.openggf.audio.rewind.AudioCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ROM sound drivers alternate the ring speaker on the raw ring request
 * id, not on who asked: every ring collect (level {@code GiveRing}, the S3K
 * special-stage sphere/ring routine at {@code loc_984C}, badniks that award
 * rings) sends the right-side id and the driver toggles it with the left-side
 * id itself ({@code zPlaySound_CheckRing}, {@code skdisasm/Sound/Z80 Sound
 * Driver.asm:1919-1925}; {@code s2disasm/s2.sounddriver.asm:2127-2135};
 * {@code s1disasm/s1.sounddriver.asm:984-991}). The special stage used the
 * raw-id path and so played every ring through the right speaker.
 */
class TestAudioManagerRawRingRequestAlternation {

    private static final int RING_RIGHT_ID = 0x33;
    private static final int RING_LEFT_ID = 0x34;

    private AudioManager audio;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new AudioTestFixtures.RecordingAudioBackend());
        AudioTestFixtures.StubSmpsLoader loader = new AudioTestFixtures.StubSmpsLoader();
        loader.sfxResults.put(RING_RIGHT_ID, new AudioTestFixtures.StubSmpsData("ring-right"));
        loader.sfxResults.put(RING_LEFT_ID, new AudioTestFixtures.StubSmpsData("ring-left"));
        audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(loader));
        audio.setRom(null);
        EnumMap<GameSound, Integer> map = new EnumMap<>(GameSound.class);
        map.put(GameSound.RING_RIGHT, RING_RIGHT_ID);
        map.put(GameSound.RING_LEFT, RING_LEFT_ID);
        audio.setSoundMap(map);
    }

    @AfterEach
    void tearDown() {
        audio.setRequestObserver(null);
        audio.resetState();
    }

    @Test
    void rawRightRingIdAlternatesLikeTheDriverToggle() {
        boolean before = audio.captureLogicalSnapshot().ringLeft();

        assertTrue(audio.playSfx(RING_RIGHT_ID));
        assertEquals(!before, audio.captureLogicalSnapshot().ringLeft(),
                "a raw ring request must consume one side of the alternation");
        assertTrue(audio.playSfx(RING_RIGHT_ID));
        assertEquals(before, audio.captureLogicalSnapshot().ringLeft());

        assertEquals(List.of(RING_LEFT_ID, RING_RIGHT_ID), playedIds(),
                "consecutive raw ring requests must be recorded on alternating sides");
    }

    @Test
    void rawRequestSharesTheToggleWithGameSoundRing() {
        audio.playSfx(GameSound.RING);
        audio.playSfx(RING_RIGHT_ID);
        audio.playSfx(GameSound.RING);

        assertEquals(List.of(RING_LEFT_ID, RING_RIGHT_ID, RING_LEFT_ID), playedIds(),
                "the level and raw-id paths must advance one shared speaker toggle");
    }

    @Test
    void explicitLeftRingIdBypassesTheToggle() {
        boolean before = audio.captureLogicalSnapshot().ringLeft();

        audio.playSfx(RING_LEFT_ID);

        assertEquals(before, audio.captureLogicalSnapshot().ringLeft(),
                "only the raw right-side id toggles the speaker in the driver");
    }

    @Test
    void rawRingRequestIsObservedOnceAsTheRawId() {
        List<String> observations = new java.util.ArrayList<>();
        audio.setRequestObserver((requestClass, rawId) ->
                observations.add(requestClass + ":" + Integer.toHexString(rawId)));

        audio.playSfx(RING_RIGHT_ID);

        assertEquals(List.of("SFX:33"), observations);
    }

    @Test
    void retainedGlobalStopResetsTheManagerOwnedAlternation() {
        assertTrue(audio.playSfx(RING_RIGHT_ID));
        assertFalse(audio.captureLogicalSnapshot().ringLeft());

        audio.retainGlobalStop(0xE2);

        assertTrue(audio.captureLogicalSnapshot().ringLeft(),
                "zStopAllSound clears zRingSpeaker at the logical command boundary");
    }

    private List<Integer> playedIds() {
        List<Integer> ids = new java.util.ArrayList<>();
        for (var entry : audio.commandTimeline().entries()) {
            if (entry.command() instanceof AudioCommand.PlaySfx play) {
                ids.add(play.sfxId());
            }
        }
        return ids;
    }
}
