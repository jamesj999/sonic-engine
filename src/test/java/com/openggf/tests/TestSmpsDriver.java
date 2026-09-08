package com.openggf.tests;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverSessionAccess;
import com.openggf.audio.driver.SmpsDriverTestAccess;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.DacData;
import com.openggf.game.session.SessionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class TestSmpsDriver {

    private record PsgWrite(Object source, int value) { }

    private static final class RecordingAccess implements SmpsDriverSessionAccess {
        final List<PsgWrite> psgWrites = new ArrayList<>();
        SmpsDriver inspectDriver;
        boolean claimsClearAtWrite;

        @Override public void writePsg(Object source, int value) {
            psgWrites.add(new PsgWrite(source, value & 0xFF));
            if (inspectDriver != null) {
                claimsClearAtWrite = psgLock(inspectDriver, 2) == null
                        && psgLock(inspectDriver, 3) == null
                        && psgClaim(inspectDriver, 2) == null;
            }
        }
        @Override public void writeFm(Object source, int port, int reg, int val) { }
        @Override public void setInstrument(Object source, int channel, byte[] voice) { }
        @Override public void playDac(Object source, int note) { }
        @Override public void stopDac(Object source) { }
        @Override public void setDacData(DacData data) { }
        @Override public void setFmMute(int channel, boolean mute) { }
        @Override public void setPsgMute(int channel, boolean mute) { }
        @Override public void setDacInterpolate(boolean interpolate) { }
        @Override public void silenceAll() { }
        @Override public void selectDac(SmpsSourceDescriptor source, DacData data) { }
        @Override public void forceSilenceFmChannel(int channelId) { }
        @Override public boolean completeFadeOut() { return false; }
        @Override public boolean fadeOutCompletesWithGlobalStop() { return false; }
    }

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    // A spy driver that records writes
    static class SpyDriver extends SmpsDriver {
        List<String> log = new ArrayList<>();
        List<Integer> rawPsgWrites = new ArrayList<>();

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            super.writeFm(source, port, reg, val);
        }

        @Override
        protected void writeRawPsg(int val) {
            rawPsgWrites.add(val);
            super.writeRawPsg(val);
        }


        // Helper to check FM lock via reflection
        public Object getFmLock(int channel) {
            try {
                java.lang.reflect.Field f = SmpsDriver.class.getDeclaredField("fmLocks");
                f.setAccessible(true);
                SmpsSequencer[] locks = (SmpsSequencer[]) f.get(this);
                return locks[channel];
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        // Helper to check PSG lock via reflection
        public Object getPsgLock(int channel) {
            try {
                java.lang.reflect.Field f = SmpsDriver.class.getDeclaredField("psgLocks");
                f.setAccessible(true);
                SmpsSequencer[] locks = (SmpsSequencer[]) f.get(this);
                return locks[channel];
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        public int getSequencerCount() {
            try {
                java.lang.reflect.Field f = SmpsDriver.class.getDeclaredField("sequencers");
                f.setAccessible(true);
                return ((List<SmpsSequencer>) f.get(this)).size();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        public int getSfxSequencerCount() {
            try {
                java.lang.reflect.Field f = SmpsDriver.class.getDeclaredField("sfxSequencers");
                f.setAccessible(true);
                return ((Set<SmpsSequencer>) f.get(this)).size();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Create a Track via reflection (constructor is package-private). */
    private static SmpsSequencer.Track createTrack(SmpsSequencer.TrackType type, int channelId) {
        try {
            var ctor = SmpsSequencer.Track.class.getDeclaredConstructor(
                    int.class, SmpsSequencer.TrackType.class, int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(0, type, channelId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testSfxFighting() {
        SpyDriver driver = new SpyDriver();
        AbstractSmpsData dummyData = new Sonic2SmpsData(new byte[100]);
        DacData dummyDac = new DacData(new HashMap<>(), new HashMap<>());

        // Create two sequencers (SFX)
        SmpsSequencer sfx1 = new SmpsSequencer(dummyData, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        SmpsSequencer sfx2 = new SmpsSequencer(dummyData, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);

        driver.addSequencer(sfx1, true);
        driver.addSequencer(sfx2, true);

        // sfx1 writes to FM channel 0 (Reg 0xA4, 0xA0 -> Channel 0)
        // Reg mapping: 0xA0..0xA2 -> Ch 0..2.
        driver.writeFm(sfx1, 0, 0xA0, 0x10);

        // Assert sfx1 has lock
        assertEquals(sfx1, driver.getFmLock(0), "SFX1 should have lock on Ch 0");

        // sfx2 writes to FM channel 0
        driver.writeFm(sfx2, 0, 0xA0, 0x20);

        // Equal priority: newer SFX (sfx2) should steal the lock from sfx1
        assertEquals(sfx2, driver.getFmLock(0), "SFX2 should steal lock on Ch 0 (equal priority, newer wins)");

        // sfx1 writes again
        driver.writeFm(sfx1, 0, 0xA0, 0x11);

        // sfx1 writes again but sfx2 still holds the lock (equal priority, sfx2 is newer)
        assertEquals(sfx2, driver.getFmLock(0), "SFX2 should still hold lock on Ch 0");
    }

    @Test
    public void testNormalSfxStealsFromSpecialSfx() {
        SpyDriver driver = new SpyDriver();
        AbstractSmpsData dummyData = new Sonic2SmpsData(new byte[100]);
        DacData dummyDac = new DacData(new HashMap<>(), new HashMap<>());

        SmpsSequencer special = new SmpsSequencer(dummyData, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        special.setSfxPriority(0x80); // S1-style special/non-storing
        special.setSpecialSfx(true);

        SmpsSequencer normal = new SmpsSequencer(dummyData, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        normal.setSfxPriority(0x70);
        normal.setSpecialSfx(false);

        driver.addSequencer(special, true);
        driver.addSequencer(normal, true);

        driver.writeFm(special, 0, 0xA0, 0x10);
        assertEquals(special, driver.getFmLock(0), "Special SFX should initially own Ch 0");

        driver.writeFm(normal, 0, 0xA0, 0x20);
        assertEquals(normal, driver.getFmLock(0), "Normal SFX should steal Ch 0 from special SFX");
    }

    @Test
    public void testSpecialSfxDoesNotStealFromNormalSfx() {
        SpyDriver driver = new SpyDriver();
        AbstractSmpsData dummyData = new Sonic2SmpsData(new byte[100]);
        DacData dummyDac = new DacData(new HashMap<>(), new HashMap<>());

        SmpsSequencer normal = new SmpsSequencer(dummyData, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        normal.setSfxPriority(0x70);
        normal.setSpecialSfx(false);

        SmpsSequencer special = new SmpsSequencer(dummyData, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        special.setSfxPriority(0x80); // Higher numeric priority, but special class
        special.setSpecialSfx(true);

        driver.addSequencer(normal, true);
        driver.addSequencer(special, true);

        driver.writeFm(normal, 0, 0xA0, 0x10);
        assertEquals(normal, driver.getFmLock(0), "Normal SFX should initially own Ch 0");

        driver.writeFm(special, 0, 0xA0, 0x20);
        assertEquals(normal, driver.getFmLock(0), "Special SFX should not steal Ch 0 from normal SFX");
    }

    @Test
    public void testSfxChannelConflictKillsOldTrack() {
        SpyDriver driver = new SpyDriver();
        AbstractSmpsData dummyDataA = new Sonic2SmpsData(new byte[100]);
        dummyDataA.setId(0xE7); // DrawbridgeMove
        AbstractSmpsData dummyDataB = new Sonic2SmpsData(new byte[100]);
        dummyDataB.setId(0xCD); // BLIP - different ID so same-ID dedup doesn't fire
        DacData dummyDac = new DacData(new HashMap<>(), new HashMap<>());

        // SFX-A uses PSG channel 2 (PSG3)
        SmpsSequencer sfxA = new SmpsSequencer(dummyDataA, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        sfxA.addTrack(createTrack(SmpsSequencer.TrackType.PSG, 2));

        // SFX-B also uses PSG channel 2 (PSG3)
        SmpsSequencer sfxB = new SmpsSequencer(dummyDataB, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        sfxB.addTrack(createTrack(SmpsSequencer.TrackType.PSG, 2));

        // Add SFX-A, give it the PSG2 lock via a write
        driver.addSequencer(sfxA, true);
        driver.writePsg(sfxA, 0x80 | (2 << 5) | 0x00); // latch PSG3
        assertEquals(sfxA, driver.getPsgLock(2), "SFX-A should hold PSG2 lock");
        assertEquals(1, driver.getSequencerCount());

        // Add SFX-B on same channel - should kill SFX-A's track
        driver.addSequencer(sfxB, true);

        // SFX-A's track should be deactivated
        assertFalse(sfxA.getTracks().get(0).active, "SFX-A's PSG2 track should be deactivated");
        // S2 installs the replacement's playback-control ownership while
        // admitting its header, before that track produces a chip write.
        assertEquals(sfxB, driver.getPsgLock(2),
                "SFX-B should own PSG2 immediately after admission");
        // SFX-A should be removed entirely (all tracks inactive)
        assertEquals(1, driver.getSequencerCount(), "SFX-A should be removed (all tracks dead)");
        assertEquals(1, driver.getSfxSequencerCount(), "Only SFX-B in sfxSequencers");
    }

    @Test
    public void testPsg3SfxSilencesNoiseChannel() {
        SpyDriver driver = new SpyDriver();
        AbstractSmpsData dummyDataA = new Sonic2SmpsData(new byte[100]);
        dummyDataA.setId(0xE7); // old SFX on PSG3
        AbstractSmpsData dummyDataB = new Sonic2SmpsData(new byte[100]);
        dummyDataB.setId(0xCD); // new SFX replacing it on PSG3
        DacData dummyDac = new DacData(new HashMap<>(), new HashMap<>());

        // Old SFX on PSG3 (channel 2)
        SmpsSequencer sfxOld = new SmpsSequencer(dummyDataA, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        sfxOld.addTrack(createTrack(SmpsSequencer.TrackType.PSG, 2));
        driver.addSequencer(sfxOld, true);
        // Give it the PSG2 lock
        driver.writePsg(sfxOld, 0x80 | (2 << 5) | 0x00);

        // New SFX also on PSG3 (channel 2) - should trigger noise silencing
        SmpsSequencer sfxNew = new SmpsSequencer(dummyDataB, dummyDac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        sfxNew.addTrack(createTrack(SmpsSequencer.TrackType.PSG, 2));

        driver.rawPsgWrites.clear();
        driver.addSequencer(sfxNew, true);

        // ROM .sfxinitpsg: admitting PSG3 silences both tone2 and noise.
        assertEquals(List.of(0xDF, 0xFF), driver.rawPsgWrites);
    }

    @Test
    public void testPsg3SfxSilencesToneAndNoiseWithoutDisplacedOwner() {
        SpyDriver driver = new SpyDriver();
        AbstractSmpsData dummyData = new Sonic2SmpsData(new byte[100]);
        dummyData.setId(0xCD);
        DacData dummyDac = new DacData(new HashMap<>(), new HashMap<>());
        SmpsSequencer sfx = new SmpsSequencer(
                dummyData, dummyDac, driver,
                Sonic2SmpsSequencerConfig.CONFIG);
        sfx.addTrack(createTrack(SmpsSequencer.TrackType.PSG, 2));

        driver.addSequencer(sfx, true);

        // zPlaySound .sfxinitpsg emits the pair from the admitted C0 header;
        // it does not depend on there being an older PSG3 owner.
        assertEquals(List.of(0xDF, 0xFF), driver.rawPsgWrites);
    }

    @Test
    public void driverSilenceWritesWhileMusicIsOverriddenWithoutChangingOwnership() {
        RecordingAccess physical = new RecordingAccess();
        SmpsDriver driver = SmpsDriver.createSessionDriver(physical);
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());
        AbstractSmpsData ownerData = new Sonic2SmpsData(new byte[100]);
        ownerData.setId(0xCD);
        SmpsSequencer owner = new SmpsSequencer(
                ownerData, dac, driver, Sonic2SmpsSequencerConfig.CONFIG);
        owner.addTrack(createTrack(SmpsSequencer.TrackType.PSG, 2));
        driver.addSequencer(owner, true);
        driver.writePsg(owner, 0xC0);
        physical.psgWrites.clear();

        SmpsSequencer music = new SmpsSequencer(
                new Sonic2SmpsData(new byte[100]), dac, driver,
                Sonic2SmpsSequencerConfig.CONFIG);
        driver.writePsg(music, 0xDF);
        assertTrue(physical.psgWrites.isEmpty(),
                "ordinary overridden music write stays gated at the physical bus");
        driver.writePsgDriverSilence(music, 2, true);

        assertEquals(List.of(new PsgWrite(music, 0xDF),
                new PsgWrite(music, 0xFF), new PsgWrite(music, 0xFF)),
                physical.psgWrites);
        assertSame(owner, psgLock(driver, 2));
        assertEquals(3, music.getPsgLatchChannel());

        physical.psgWrites.clear();
        int latchBefore = music.getPsgLatchChannel();
        assertThrows(IllegalArgumentException.class,
                () -> driver.writePsgDriverSilence(music, 3, false));
        assertTrue(physical.psgWrites.isEmpty());
        assertEquals(latchBefore, music.getPsgLatchChannel());
        assertSame(owner, psgLock(driver, 2));
    }

    @Test
    public void s3kNoiseStopReleasesBothClaimsBeforeExactMusicNoiseRestore() {
        RecordingAccess physical = new RecordingAccess();
        SmpsDriver driver = SmpsDriver.createSessionDriver(physical);
        physical.inspectDriver = driver;
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());

        Sonic3kSmpsData musicData = new Sonic3kSmpsData(new byte[64], 0);
        SmpsSequencer music = new SmpsSequencer(musicData, dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track musicTrack = createTrack(
                SmpsSequencer.TrackType.PSG, 2);
        musicTrack.active = true;
        musicTrack.resting = true;
        musicTrack.noiseMode = true;
        musicTrack.rawPsgNoiseKnown = true;
        musicTrack.rawPsgNoise = 0xE7;
        music.addTrack(musicTrack);
        driver.addSequencer(music, false);

        Sonic3kSmpsData sfxData = new Sonic3kSmpsData(new byte[64], 0);
        sfxData.setId(0x59);
        SmpsSequencer sfx = new SmpsSequencer(sfxData, dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track stopped = createTrack(
                SmpsSequencer.TrackType.PSG, 2);
        stopped.noiseMode = true;
        sfx.addTrack(stopped);
        driver.addSequencer(sfx, true);
        assertSame(sfx, psgClaim(driver, 2),
                "active header admission records the PSG3 claim");
        driver.writePsg(sfx, 0xC0);
        driver.writePsg(sfx, 0xE7);
        physical.psgWrites.clear();

        stopped.active = false;
        driver.writePsgDriverSilence(sfx, 2, true);
        driver.releaseChannelToMusic(sfx, stopped);

        assertEquals(List.of(new PsgWrite(sfx, 0xDF),
                new PsgWrite(sfx, 0xFF), new PsgWrite(sfx, 0xFF),
                new PsgWrite(music, 0xE7)), physical.psgWrites);
        assertNull(psgLock(driver, 2));
        assertNull(psgLock(driver, 3));
        assertTrue(physical.claimsClearAtWrite,
                "tone/noise locks and admission claim clear before E7 callback");
        assertFalse(musicTrack.overridden);
        assertTrue(musicTrack.active);
        assertTrue(musicTrack.resting);
    }

    @Test
    public void s3kMusicNoiseRestoreUsesRawSignedByteWithoutStateNormalization() {
        RecordingAccess physical = new RecordingAccess();
        SmpsDriver driver = SmpsDriver.createSessionDriver(physical);
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());
        SmpsSequencer music = new SmpsSequencer(
                new Sonic3kSmpsData(new byte[64], 0), dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track track = createTrack(SmpsSequencer.TrackType.PSG, 2);
        track.active = false;
        track.resting = false;
        track.noiseMode = true;
        track.rawPsgNoiseKnown = true;
        track.rawPsgNoise = 0x85;
        track.overridden = true;
        music.addTrack(track);

        music.setChannelOverriddenAfterSfxTrackStop(
                SmpsSequencer.TrackType.PSG, 2);
        assertEquals(List.of(new PsgWrite(music, 0x85)), physical.psgWrites);
        assertFalse(track.active);
        assertFalse(track.resting);

        physical.psgWrites.clear();
        track.overridden = true;
        track.rawPsgNoise = 0x7F;
        music.setChannelOverriddenAfterSfxTrackStop(
                SmpsSequencer.TrackType.PSG, 2);
        assertTrue(physical.psgWrites.isEmpty(), "positive raw noise byte is not restored");

        track.overridden = true;
        track.rawPsgNoise = 0x80;
        music.setChannelOverriddenAfterSfxTrackStop(
                SmpsSequencer.TrackType.PSG, 2);
        assertEquals(List.of(new PsgWrite(music, 0x80)), physical.psgWrites,
                "the sign boundary is restored verbatim");

        physical.psgWrites.clear();
        track.overridden = true;
        track.rawPsgNoise = 0xE7;
        track.noiseMode = false;
        music.setChannelOverriddenAfterSfxTrackStop(
                SmpsSequencer.TrackType.PSG, 2);
        assertTrue(physical.psgWrites.isEmpty(), "tone-form PSG3 has no restore write");
    }

    @Test
    public void genericOverrideReleaseDoesNotUseTheF2RawNoiseRestore() {
        RecordingAccess physical = new RecordingAccess();
        SmpsDriver driver = SmpsDriver.createSessionDriver(physical);
        SmpsSequencer music = new SmpsSequencer(
                new Sonic3kSmpsData(new byte[64], 0),
                new DacData(new HashMap<>(), new HashMap<>()), driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track track = createTrack(SmpsSequencer.TrackType.PSG, 2);
        track.active = true;
        track.noiseMode = true;
        track.rawPsgNoiseKnown = true;
        track.rawPsgNoise = 0x85;
        track.overridden = true;
        music.addTrack(track);

        music.setChannelOverridden(SmpsSequencer.TrackType.PSG, 2, false);

        assertFalse(physical.psgWrites.stream().anyMatch(write -> write.value() == 0x85),
                "generic teardown/replacement callbacks cannot use zStopPSGTrack restore");
    }

    @Test
    public void stopAllSfxKeepsLegacyCleanupAndNeverUsesF2RawNoiseRestore() {
        RecordingAccess physical = new RecordingAccess();
        SmpsDriver driver = SmpsDriver.createSessionDriver(physical);
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());
        SmpsSequencer music = new SmpsSequencer(
                new Sonic3kSmpsData(new byte[64], 0), dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track covered = createTrack(SmpsSequencer.TrackType.PSG, 2);
        covered.noiseMode = true;
        covered.rawPsgNoiseKnown = true;
        covered.rawPsgNoise = 0x85;
        music.addTrack(covered);
        driver.addSequencer(music, false);

        Sonic3kSmpsData sfxData = new Sonic3kSmpsData(new byte[64], 0);
        sfxData.setId(0x59);
        SmpsSequencer sfx = new SmpsSequencer(sfxData, dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track noise = createTrack(SmpsSequencer.TrackType.PSG, 2);
        noise.noiseMode = true;
        sfx.addTrack(noise);
        driver.addSequencer(sfx, true);
        driver.writePsg(sfx, 0xC0);
        driver.writePsg(sfx, 0xE7);
        assertTrue(covered.overridden,
                "normal admission must cover music before wholesale teardown");
        physical.psgWrites.clear();

        driver.stopAllSfx();

        assertEquals(List.of(new PsgWrite(sfx, 0xDF),
                new PsgWrite(sfx, 0xFF)), physical.psgWrites,
                "wholesale cleanup retains its established silence and no raw 85 restore");
    }

    @Test
    public void s3kNoiseStopDoesNotReleaseAnotherEffectsNoiseOwnership() {
        RecordingAccess physical = new RecordingAccess();
        SmpsDriver driver = SmpsDriver.createSessionDriver(physical);
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());
        SmpsSequencer music = new SmpsSequencer(
                new Sonic3kSmpsData(new byte[64], 0), dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track covered = createTrack(SmpsSequencer.TrackType.PSG, 2);
        covered.noiseMode = true;
        covered.rawPsgNoiseKnown = true;
        covered.rawPsgNoise = 0xE7;
        music.addTrack(covered);
        driver.addSequencer(music, false);

        Sonic3kSmpsData oldData = new Sonic3kSmpsData(new byte[64], 0);
        oldData.setId(0x59);
        SmpsSequencer old = new SmpsSequencer(oldData, dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track stopped = createTrack(SmpsSequencer.TrackType.PSG, 2);
        stopped.active = false;
        stopped.noiseMode = true;
        old.addTrack(stopped);
        driver.addSequencer(old, true);
        driver.writePsg(old, 0xC0);

        Sonic3kSmpsData otherData = new Sonic3kSmpsData(new byte[64], 0);
        otherData.setId(0xBA);
        SmpsSequencer other = new SmpsSequencer(otherData, dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        driver.addSequencer(other, true);
        driver.writePsg(other, 0xE7);
        physical.psgWrites.clear();

        driver.releaseChannelToMusic(old, stopped);

        assertTrue(physical.psgWrites.isEmpty(),
                "covered noise restore remains gated by the other noise owner");
        assertSame(other, psgLock(driver, 3));

        covered.overridden = true;
        driver.writePsg(other, 0xC0);
        physical.psgWrites.clear();
        driver.releaseChannelToMusic(old, stopped);
        assertTrue(physical.psgWrites.isEmpty(),
                "a non-owner stop cannot trigger a music restore");
        assertTrue(covered.overridden);
        assertSame(other, psgLock(driver, 2));
    }

    @Test
    public void toneStopDoesNotInferNoiseFromStaleInactiveSibling() {
        RecordingAccess physical = new RecordingAccess();
        SmpsDriver driver = SmpsDriver.createSessionDriver(physical);
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());
        Sonic3kSmpsData data = new Sonic3kSmpsData(new byte[64], 0);
        data.setId(0x59);
        SmpsSequencer sfx = new SmpsSequencer(data, dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track endingTone = createTrack(
                SmpsSequencer.TrackType.PSG, 2);
        SmpsSequencer.Track staleNoise = createTrack(
                SmpsSequencer.TrackType.PSG, 2);
        staleNoise.active = false;
        staleNoise.noiseMode = true;
        sfx.addTrack(endingTone);
        sfx.addTrack(staleNoise);
        driver.addSequencer(sfx, true);
        driver.writePsg(sfx, 0xC0);
        driver.writePsg(sfx, 0xE7);
        physical.psgWrites.clear();

        endingTone.active = false;
        driver.releaseChannelToMusic(sfx, endingTone);

        assertNull(psgLock(driver, 2));
        assertSame(sfx, psgLock(driver, 3),
                "the exact tone-ending track cannot release a stale sibling's noise lock");
        assertTrue(physical.psgWrites.isEmpty());
    }

    @Test
    public void duplicateActiveSameChannelTrackFailsReleaseClosed() {
        RecordingAccess physical = new RecordingAccess();
        SmpsDriver driver = SmpsDriver.createSessionDriver(physical);
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());
        Sonic3kSmpsData data = new Sonic3kSmpsData(new byte[64], 0);
        data.setId(0x59);
        SmpsSequencer sfx = new SmpsSequencer(data, dac, driver,
                Sonic3kSmpsSequencerConfig.CONFIG);
        SmpsSequencer.Track ending = createTrack(SmpsSequencer.TrackType.PSG, 2);
        SmpsSequencer.Track duplicate = createTrack(SmpsSequencer.TrackType.PSG, 2);
        sfx.addTrack(ending);
        sfx.addTrack(duplicate);
        driver.addSequencer(sfx, true);
        driver.writePsg(sfx, 0xC0);
        physical.psgWrites.clear();

        ending.active = false;
        driver.releaseChannelToMusic(sfx, ending);

        assertSame(sfx, psgLock(driver, 2));
        assertSame(sfx, psgClaim(driver, 2));
        assertTrue(physical.psgWrites.isEmpty());
    }

    private static SmpsSequencer psgLock(SmpsDriver driver, int channel) {
        try {
            java.lang.reflect.Field field = SmpsDriver.class.getDeclaredField("psgLocks");
            field.setAccessible(true);
            return ((SmpsSequencer[]) field.get(driver))[channel];
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static SmpsSequencer psgClaim(SmpsDriver driver, int channel) {
        try {
            java.lang.reflect.Field field = SmpsDriver.class.getDeclaredField("psgSfxClaims");
            field.setAccessible(true);
            return ((SmpsSequencer[]) field.get(driver))[channel];
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    public void testReadMatchesSingleFrameChunkingForSilentDriver() {
        SmpsDriver bulkDriver = SmpsDriverTestAccess.create(44_100);
        SmpsDriver singleFrameDriver = SmpsDriverTestAccess.create(44_100);

        short[] actual = new short[512];
        short[] expected = new short[512];
        short[] frame = new short[2];

        SmpsDriverTestAccess.read(bulkDriver, actual);
        for (int i = 0; i < expected.length / 2; i++) {
            SmpsDriverTestAccess.read(singleFrameDriver, frame);
            expected[i * 2] = frame[0];
            expected[i * 2 + 1] = frame[1];
        }

        assertArrayEquals(expected, actual,
                "Driver output should not depend on whether audio is read a frame at a time or in one block");
    }
}
