package com.openggf.audio.driver;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.AudioManager;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.sonic3k.audio.S3kE4Projection;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSfxData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsChannelOwnershipProjection {
    private static final List<S3kE4SlotExpectation> E4_SLOT_LAYOUT = List.of(
            new S3kE4SlotExpectation(S3kE4Projection.S3kE4Slot.FM3, 2, 0x02),
            new S3kE4SlotExpectation(S3kE4Projection.S3kE4Slot.FM4, 3, 0x04),
            new S3kE4SlotExpectation(S3kE4Projection.S3kE4Slot.FM5, 4, 0x05),
            new S3kE4SlotExpectation(S3kE4Projection.S3kE4Slot.FM6, 5, 0x06),
            new S3kE4SlotExpectation(S3kE4Projection.S3kE4Slot.PSG1, 0, 0x80),
            new S3kE4SlotExpectation(S3kE4Projection.S3kE4Slot.PSG2, 1, 0xA0),
            new S3kE4SlotExpectation(S3kE4Projection.S3kE4Slot.PSG3, 2, 0xC0));

    @Test
    void nativeE4SlotTableHasExactChannelAndVoiceControlValues() {
        List<S3kE4Projection.S3kE4Slot> slots = List.of(
                S3kE4Projection.S3kE4Slot.values());

        assertEquals(E4_SLOT_LAYOUT.size(), slots.size());
        for (int index = 0; index < E4_SLOT_LAYOUT.size(); index++) {
            S3kE4SlotExpectation expected = E4_SLOT_LAYOUT.get(index);
            S3kE4Projection.S3kE4Slot actual = slots.get(index);
            assertEquals(expected.slot(), actual);
            assertEquals(expected.channel(), actual.channel());
            assertEquals(expected.rawVoiceControl(), actual.rawVoiceControl());
        }
    }

    @Test
    void activeDeclaredSfxClaimIsProjectedBeforeItAcquiresAnyWriteLock()
            throws Exception {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer music = sequencer(music(0x81), driver);
        music.addTrack(track(SmpsSequencer.TrackType.FM, 2));
        SmpsSequencer sfx = sequencer(sfx(0xA0, 0x02), driver);
        SmpsSequencer.Track declared = sfx.getTracks().getFirst();
        declared.voiceData = new byte[] {1, 2, 3};
        declared.voiceId = 7;
        declared.volumeOffset = 9;
        declared.pan = 0x80;
        declared.ams = 2;
        declared.fms = 5;
        declared.ssgEg[1] = 0x0E;
        declared.customSsgEgPresent = true;
        declared.customSsgEgPayload[1] = 0x0E;
        declared.customSsgEgPayloadKnown = true;
        driver.addSequencer(music, false);
        driver.addSequencer(sfx, true);
        var snapshot = driver.captureSnapshot();
        assertEquals(2, snapshot.sequencers().get(1).snapshot().tracks()
                .getFirst().ams());
        assertEquals(5, snapshot.sequencers().get(1).snapshot().tracks()
                .getFirst().fms());
        driver.restoreSnapshot(snapshot);

        SmpsChannelOwnershipProjection projection =
                driver.captureOwnershipProjection();
        SmpsChannelOwnershipProjection.RoleOwnership role = projection.role(
                new SmpsChannelOwnershipProjection.PhysicalChannel(
                        SmpsChannelOwnershipProjection.Bus.FM, 2)).orElseThrow();

        assertTrue(role.sfxOccupied(),
                "declared SFX occupancy must not wait for a chip-write lock");
        assertEquals(1, role.sfxClaims().size());
        assertEquals(1, role.musicTracks().size());
        assertEquals(1, role.sfxClaims().getFirst().coordinate().sequencerIndex());
        assertEquals(0, role.sfxClaims().getFirst().coordinate().trackIndex());
        assertFalse(role.sfxClaims().getFirst().track().overridden());

        S3kE4Projection e4 = S3kE4Projection.capture(projection);
        assertTrue(e4.complete());
        S3kE4Projection.SlotProjection fm3 = e4.slots().getFirst();
        assertEquals(S3kE4Projection.Availability.AVAILABLE, fm3.availability());
        S3kE4Projection.S3kE4Track view = fm3.sfx();
        assertEquals(0x02, view.canonicalVoiceControl());
        assertFalse(view.noiseOrFm3Special());
        assertFalse(view.rawPlaybackFlags().isPresent(),
                "the engine retains semantic flags, not an invented raw Z80 byte");
        assertEquals(7, view.voiceId());
        assertEquals(9, view.volume());
        assertEquals(0x80, view.pan());
        assertEquals(2, view.ams());
        assertEquals(5, view.fms());
        assertArrayEquals(new int[] {0, 0x0E, 0, 0}, view.customSsgEgPayload());
        assertTrue(view.customSsgEgPresent());
        int[] copiedSsgEgPayload = view.customSsgEgPayload();
        copiedSsgEgPayload[1] = 0;
        assertArrayEquals(new int[] {0, 0x0E, 0, 0}, view.customSsgEgPayload());
        byte[] copied = view.materializedVoice();
        copied[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, view.materializedVoice());
    }

    @Test
    void psgNoiseProjectionRetainsPsgNoiseSemantics() throws Exception {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer sfx = sequencer(sfx(0xA0, 0xC0), driver);
        SmpsSequencer.Track declared = sfx.getTracks().getFirst();
        declared.noiseMode = true;
        declared.psgNoiseParam = 0x07;
        declared.rawPsgNoise = 0xE7;
        declared.rawPsgNoiseKnown = true;
        declared.voiceData = new byte[] {1, 2, 3};
        declared.voiceId = 7;
        declared.volumeOffset = 9;
        declared.pan = 0x80;
        driver.addSequencer(sfx, true);

        S3kE4Projection before = S3kE4Projection.capture(
                driver.captureOwnershipProjection());
        driver.restoreSnapshot(driver.captureSnapshot());
        S3kE4Projection.SlotProjection psg3 = S3kE4Projection.capture(
                driver.captureOwnershipProjection()).slots().getLast();

        assertEquals(S3kE4Projection.Availability.AVAILABLE, psg3.availability());
        assertTrue(psg3.sfx().noiseOrFm3Special());
        assertEquals(0x01, psg3.sfx().canonicalPlaybackFlags() & 0x01);
        assertEquals(0x07, psg3.sfx().psgNoise());
        assertEquals(0xE7, psg3.sfx().rawPsgNoise().orElseThrow());
        assertEquals(before.slots().getLast().sfx().rawPsgNoise(),
                psg3.sfx().rawPsgNoise(),
                "raw PSG state must survive the driver's donor-capable snapshot path");
        assertEquals(0xC0, psg3.sfx().canonicalVoiceControl());
        assertFalse(psg3.sfx().rawPlaybackFlags().isPresent(),
                "the engine retains semantic flags, not an invented raw Z80 byte");
        assertEquals(7, psg3.sfx().voiceId());
        assertEquals(9, psg3.sfx().volume());
        assertEquals(0x80, psg3.sfx().pan());
        byte[] copied = psg3.sfx().materializedVoice();
        copied[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, psg3.sfx().materializedVoice());
    }

    @Test
    void noiseProjectionFailsClosedWhenTheRequiredRawPsgByteIsUnavailable()
            throws Exception {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer sfx = sequencer(sfx(0xA0, 0xC0), driver);
        SmpsSequencer.Track psg3 = sfx.getTracks().getFirst();
        psg3.noiseMode = true;
        psg3.psgNoiseParam = 0x07;
        // A synthetic semantic-only track must not be allowed to invent the
        // distinct zTrack.PSGNoise byte needed by the future E4 restore loop.
        driver.addSequencer(sfx, true);

        S3kE4Projection projection = S3kE4Projection.capture(
                driver.captureOwnershipProjection());

        assertFalse(projection.complete());
        assertEquals(S3kE4Projection.Availability.UNAVAILABLE_AMBIGUOUS_OR_INVALID,
                projection.slots().getLast().availability());
        assertEquals(null, projection.slots().getLast().sfx());
    }

    @Test
    void customSsgEgProjectionFailsClosedWhenItsExactPayloadIsUnavailable()
            throws Exception {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer sfx = sequencer(sfx(0xA0, 0x02), driver);
        SmpsSequencer.Track fm3 = sfx.getTracks().getFirst();
        fm3.customSsgEgPresent = true;
        fm3.ssgEg[0] = 0x11;
        fm3.ssgEg[1] = 0x22;
        fm3.ssgEg[2] = 0x33;
        fm3.ssgEg[3] = 0x44;
        driver.addSequencer(sfx, true);

        S3kE4Projection projection = S3kE4Projection.capture(
                driver.captureOwnershipProjection());

        assertFalse(projection.complete());
        assertEquals(S3kE4Projection.Availability.UNAVAILABLE_AMBIGUOUS_OR_INVALID,
                projection.slots().getFirst().availability());
        assertEquals(null, projection.slots().getFirst().sfx());
    }

    @Test
    void ff05ThenHighBitFf06FailsClosedWithoutExposingTheStalePayload() {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer sfx = sequencer(fm3Stream(
                new byte[] {(byte) 0xFF, 0x05, 0x11, 0x22, 0x33, 0x44,
                        (byte) 0xFF, 0x06, (byte) 0x81, 0x0F, 0x60, 0x7F}), driver);
        driver.addSequencer(sfx, true);
        sfx.advanceSamples(20_000);

        S3kE4Projection projection = S3kE4Projection.capture(
                driver.captureOwnershipProjection());

        assertFalse(projection.complete());
        assertEquals(S3kE4Projection.Availability.UNAVAILABLE_AMBIGUOUS_OR_INVALID,
                projection.slots().getFirst().availability());
        assertEquals(null, projection.slots().getFirst().sfx());
    }

    @Test
    void allZeroFf05SurvivesDriverSnapshotAndProjectsAsAnAvailableCustomPayload() {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer sfx = sequencer(fm3Stream(
                new byte[] {(byte) 0xFF, 0x05, 0x00, 0x00, 0x00, 0x00,
                        0x60, 0x7F}), driver);
        driver.addSequencer(sfx, true);
        sfx.advanceSamples(20_000);

        S3kE4Projection before = S3kE4Projection.capture(
                driver.captureOwnershipProjection());
        driver.restoreSnapshot(driver.captureSnapshot());
        S3kE4Projection restored = S3kE4Projection.capture(
                driver.captureOwnershipProjection());

        assertTrue(before.complete());
        assertTrue(restored.complete());
        assertEquals(S3kE4Projection.Availability.AVAILABLE,
                restored.slots().getFirst().availability());
        S3kE4Projection.S3kE4Track view = restored.slots().getFirst().sfx();
        assertTrue(view.customSsgEgPresent());
        assertArrayEquals(new int[4], view.customSsgEgPayload());
        assertArrayEquals(before.slots().getFirst().sfx().customSsgEgPayload(),
                view.customSsgEgPayload());
    }

    @Test
    void fm3SpecialProjectionSurvivesLogicalSnapshotRestore() throws Exception {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer music = sequencer(music(0x81), driver);
        music.addTrack(track(SmpsSequencer.TrackType.FM, 2));
        SmpsSequencer sfx = sequencer(sfx(0xA0, 0x02), driver);
        sfx.getTracks().getFirst().fm3SpecialMode = true;
        driver.addSequencer(music, false);
        driver.addSequencer(sfx, true);

        S3kE4Projection before = S3kE4Projection.capture(
                driver.captureOwnershipProjection());
        driver.restoreSnapshot(driver.captureSnapshot());
        S3kE4Projection restored = S3kE4Projection.capture(
                driver.captureOwnershipProjection());

        assertTrue(before.complete());
        assertTrue(restored.complete());
        assertTrue(before.slots().getFirst().sfx().noiseOrFm3Special());
        assertTrue(restored.slots().getFirst().sfx().noiseOrFm3Special());
        assertEquals(0x01, restored.slots().getFirst().sfx()
                .canonicalPlaybackFlags() & 0x01);
    }

    @Test
    void captureIsObservationalAndRoundTripsWithTheLogicalSnapshot()
            throws Exception {
        AtomicInteger writes = new AtomicInteger();
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000, new com.openggf.audio.synth.ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                writes.incrementAndGet();
            }
            @Override public void onPsgWrite(int value) {
                writes.incrementAndGet();
            }
        });
        SmpsSequencer music = sequencer(music(0x81), driver);
        music.addTrack(track(SmpsSequencer.TrackType.PSG, 1));
        SmpsSequencer sfx = sequencer(sfx(0xA0, 0xA0), driver);
        driver.addSequencer(music, false);
        driver.addSequencer(sfx, true);
        var before = driver.captureSnapshot();
        writes.set(0);

        SmpsChannelOwnershipProjection first =
                driver.captureOwnershipProjection();
        driver.restoreSnapshot(before);
        SmpsChannelOwnershipProjection restored =
                driver.captureOwnershipProjection();

        assertEquals(0, writes.get(),
                "capturing and restoring logical ownership must not write the chip");
        SmpsChannelOwnershipProjection.RoleOwnership beforeRole =
                first.roles().values().iterator().next();
        SmpsChannelOwnershipProjection.RoleOwnership restoredRole =
                restored.roles().values().iterator().next();
        assertEquals(beforeRole.channel(), restoredRole.channel());
        assertEquals(beforeRole.sfxClaims().getFirst().coordinate(),
                restoredRole.sfxClaims().getFirst().coordinate());
        assertEquals(beforeRole.sfxClaims().getFirst().track().type(),
                restoredRole.sfxClaims().getFirst().track().type());
        assertEquals(beforeRole.sfxClaims().getFirst().track().channelId(),
                restoredRole.sfxClaims().getFirst().track().channelId());
        assertEquals(beforeRole.musicTracks().getFirst().coordinate(),
                restoredRole.musicTracks().getFirst().coordinate());
    }

    @Test
    void unsupportedActiveSfxRoleFailsClosedForS3kE4() throws Exception {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer sfx = sequencer(sfx(0xA0, 0x00), driver);
        driver.addSequencer(sfx, true);

        S3kE4Projection projection = S3kE4Projection.capture(
                driver.captureOwnershipProjection());

        assertFalse(projection.complete());
    }

    private static SmpsSequencer sequencer(AbstractSmpsData source,
            SmpsDriver driver) {
        return new SmpsSequencer(source, AudioTestFixtures.EMPTY_DAC, driver,
                AudioManager.getInstance(), Sonic3kSmpsSequencerConfig.CONFIG);
    }

    private static SmpsSequencer.Track track(SmpsSequencer.TrackType type,
            int channel) throws Exception {
        Constructor<SmpsSequencer.Track> constructor =
                SmpsSequencer.Track.class.getDeclaredConstructor(int.class,
                        SmpsSequencer.TrackType.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(0, type, channel);
    }

    private static AbstractSmpsData music(int id) {
        Sonic3kSmpsData data = new Sonic3kSmpsData(new byte[64], 0);
        data.setId(id);
        return data;
    }

    private static AbstractSmpsData sfx(int id, int channel) {
        byte[] data = new byte[64];
        data[2] = 1; // tick multiplier
        data[3] = 1; // track count
        data[4] = (byte) 0x80; // S3K shipped SFX playbackFlags
        data[5] = (byte) channel;
        data[6] = 0x20;
        data[7] = 0;
        data[0x20] = (byte) 0xF2; // terminal stream at the declared pointer
        Sonic3kSfxData result = new Sonic3kSfxData(data, 0, 0, 0);
        result.setId(id);
        return result;
    }

    private static AbstractSmpsData fm3Stream(byte[] stream) {
        byte[] data = new byte[0x100];
        data[2] = 4; // DAC plus FM1..FM3
        data[4] = 1;
        data[5] = (byte) 0x80;
        data[0x12] = 0x40; // FM3's fourth 4-byte header entry
        System.arraycopy(stream, 0, data, 0x40, stream.length);
        return new Sonic3kSmpsData(data, 0);
    }

    private record S3kE4SlotExpectation(
            S3kE4Projection.S3kE4Slot slot,
            int channel,
            int rawVoiceControl) {
    }
}
