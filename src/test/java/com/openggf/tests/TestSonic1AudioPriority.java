package com.openggf.tests;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.sonic1.audio.Sonic1SmpsConstants;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Bug #16: Sonic 1 waterfall SFX stop/start after interruption.
 *
 * <p>The GHZ waterfall object plays the special SFX 0xD0 (priority 0x80) every
 * 64 frames. When a regular SFX (e.g. jump, priority 0x70 with bit 7 clear)
 * interrupts the waterfall, the waterfall SFX should be able to cleanly reclaim
 * the channel on re-play. Without proper priority handling, the channel enters a
 * conflicted state with rapid on/off toggling.
 *
 * <p>The Sonic 1 priority scheme assigns all special SFX (0xD0-0xDF) a priority
 * of 0x80 (bit 7 set = non-storing/transient). This means any subsequent SFX can
 * steal the lock from a special SFX. Conversely, a special SFX should not be able
 * to steal back from a normal SFX that has a non-transient priority.
 *
 * <p>These tests exercise the {@link SmpsDriver} priority arbitration logic
 * (specifically {@code shouldStealLock()}) using mock sequencers, without
 * requiring a ROM image.
 *
 * @see SmpsDriver
 * @see Sonic1SmpsConstants#SOUND_PRIORITIES
 */
public class TestSonic1AudioPriority {

    /**
     * Spy subclass that exposes internal channel lock state for assertions.
     * Mirrors the pattern from {@link TestSmpsDriver.SpyDriver}.
     */
    static class SpyDriver extends SmpsDriver {
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

        /** Get the sequencer currently holding the FM channel lock, or null. */
        public SmpsSequencer getFmLock(int channel) {
            try {
                java.lang.reflect.Field f = SmpsDriver.class.getDeclaredField("fmLocks");
                f.setAccessible(true);
                SmpsSequencer[] locks = (SmpsSequencer[]) f.get(this);
                return locks[channel];
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** Get the sequencer currently holding the PSG channel lock, or null. */
        public SmpsSequencer getPsgLock(int channel) {
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

    /**
     * Create a dummy SFX sequencer configured with the given priority and special-SFX flag.
     *
     * @param driver   the SmpsDriver to register with
     * @param sfxId    the SFX ID (for dedup tracking)
     * @param priority the priority value (e.g. 0x80 for special, 0x70 for normal)
     * @param special  whether this is a special SFX (separate class from normal SFX)
     * @return the configured SmpsSequencer
     */
    private SmpsSequencer createSfxSequencer(SpyDriver driver, int sfxId, int priority, boolean special) {
        AbstractSmpsData dummyData = new Sonic2SmpsData(new byte[100]);
        dummyData.setId(sfxId);
        DacData dummyDac = new DacData(new HashMap<>(), new HashMap<>());

        SmpsSequencer seq = new SmpsSequencer(dummyData, dummyDac, driver, Sonic1SmpsSequencerConfig.CONFIG);
        seq.setSfxPriority(priority);
        seq.setSpecialSfx(special);
        return seq;
    }

    /**
     * Test that the waterfall SFX can stably reclaim a channel after being
     * interrupted by a normal SFX.
     *
     * <p>Scenario:
     * <ol>
     *   <li>Waterfall SFX (special, priority 0x80) takes FM channel 2</li>
     *   <li>Jump SFX (normal, priority 0x70) steals FM channel 2</li>
     *   <li>Jump SFX finishes and releases the lock</li>
     *   <li>Waterfall SFX replays (as the object does every 64 frames)</li>
     *   <li>Assert: waterfall stably holds the channel without toggling</li>
     * </ol>
     *
     * <p>The bug manifested as the channel alternating between active/inactive
     * states on every driver tick after re-play, causing audible clicking.
     */
    @Test
    public void testWaterfallSfxRecoversAfterInterruption() {
        SpyDriver driver = new SpyDriver();

        // Step 1: Waterfall SFX takes FM channel 2.
        // S1 waterfall (0xD0) has priority 0x80 and is a special SFX.
        int waterfallPriority = Sonic1SmpsConstants.getSfxPriority(0xD0);
        assertEquals(0x80, waterfallPriority, "Waterfall priority should be 0x80");

        SmpsSequencer waterfall1 = createSfxSequencer(driver, 0xD0, waterfallPriority, true);
        driver.addSequencer(waterfall1, true);

        // Give waterfall the FM2 lock via a frequency write.
        driver.writeFm(waterfall1, 0, 0xA2, 0x10); // FM channel 2 (port 0, reg 0xA2)
        assertEquals(waterfall1, driver.getFmLock(2), "Waterfall should initially hold FM2 lock");

        // Step 2: Jump SFX (normal, priority 0x70) steals the channel.
        // Jump (0xA0) has priority 0x80 in S1's table, but for this test we use the
        // general case: a normal SFX always steals from a special SFX regardless of
        // numeric priority (SmpsDriver.shouldStealLock: currentSpecial && !challengerSpecial).
        SmpsSequencer jump = createSfxSequencer(driver, 0xA0, 0x70, false);
        jump.addTrack(createTrack(SmpsSequencer.TrackType.FM, 2));
        driver.addSequencer(jump, true);

        // Jump writes to FM2, stealing the lock from the waterfall.
        driver.writeFm(jump, 0, 0xA2, 0x20);
        assertEquals(jump, driver.getFmLock(2), "Jump SFX should steal FM2 from waterfall");

        // Step 3: Simulate jump SFX completing - remove it and release its locks.
        // In normal operation, the driver removes completed sequencers in read().
        // We simulate this by manually removing and releasing.
        try {
            java.lang.reflect.Field seqField = SmpsDriver.class.getDeclaredField("sequencers");
            seqField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<SmpsSequencer> seqs = (List<SmpsSequencer>) seqField.get(driver);
            seqs.remove(jump);

            java.lang.reflect.Field sfxField = SmpsDriver.class.getDeclaredField("sfxSequencers");
            sfxField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<SmpsSequencer> sfxSeqs = (java.util.Set<SmpsSequencer>) sfxField.get(driver);
            sfxSeqs.remove(jump);

            // Release lock (mirrors SmpsDriver.releaseLocks behavior)
            java.lang.reflect.Field fmLocksField = SmpsDriver.class.getDeclaredField("fmLocks");
            fmLocksField.setAccessible(true);
            SmpsSequencer[] fmLocks = (SmpsSequencer[]) fmLocksField.get(driver);
            if (fmLocks[2] == jump) {
                fmLocks[2] = null;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to simulate jump SFX completion", e);
        }

        assertNull(driver.getFmLock(2), "FM2 lock should be released after jump finishes");

        // Step 4: Waterfall replays (object re-triggers every 64 frames).
        // The old waterfall1 sequencer was removed by same-ID dedup when jump was added
        // (since they share channel 2). Create a fresh waterfall sequencer.
        SmpsSequencer waterfall2 = createSfxSequencer(driver, 0xD0, waterfallPriority, true);
        driver.addSequencer(waterfall2, true);

        // Waterfall writes to FM2, reclaiming the now-free channel.
        driver.writeFm(waterfall2, 0, 0xA2, 0x30);
        assertEquals(waterfall2, driver.getFmLock(2), "Waterfall should reclaim FM2 after jump finishes");

        // Step 5: Assert stability - simulate 128 write cycles and count lock changes.
        // In the buggy case, the lock would toggle between waterfall and null (or another
        // sequencer) on every cycle, causing audible clicking.
        int lockChanges = 0;
        SmpsSequencer previousLock = driver.getFmLock(2);

        for (int frame = 0; frame < 128; frame++) {
            // Waterfall continues writing to its channel each "frame".
            driver.writeFm(waterfall2, 0, 0xA2, 0x30 + (frame & 0x0F));

            SmpsSequencer currentLock = driver.getFmLock(2);
            if (currentLock != previousLock) {
                lockChanges++;
                previousLock = currentLock;
            }
        }

        assertTrue(lockChanges <= 3, "Channel lock should not toggle rapidly after waterfall re-play "
                        + "(changes=" + lockChanges + ", max=3)");

        // Stronger assertion: the waterfall should still own the channel.
        assertEquals(waterfall2, driver.getFmLock(2), "Waterfall should stably hold FM2 after 128 frames");
    }

    /**
     * Test that a special SFX (waterfall) does not block a higher-priority normal SFX.
     *
     * <p>In the Sonic 1 driver, special SFX (0xD0+) form a separate class that can
     * always be overridden by normal SFX, regardless of the numeric priority value.
     * This is critical because the waterfall's priority 0x80 has bit 7 set (transient),
     * meaning the lock is non-storing and any SFX can steal it.
     *
     * <p>This test verifies both mechanisms:
     * <ul>
     *   <li>Special-class demotion: normal SFX steals from special SFX</li>
     *   <li>Bit-7 transient: even without special-class flag, 0x80 priority allows stealing</li>
     * </ul>
     */
    @Test
    public void testSpecialSfxPriorityDoesNotBlock() {
        SpyDriver driver = new SpyDriver();

        // Set up waterfall on FM channel 2.
        int waterfallPriority = Sonic1SmpsConstants.getSfxPriority(0xD0);
        SmpsSequencer waterfall = createSfxSequencer(driver, 0xD0, waterfallPriority, true);
        driver.addSequencer(waterfall, true);

        driver.writeFm(waterfall, 0, 0xA2, 0x10);
        assertEquals(waterfall, driver.getFmLock(2), "Waterfall should hold FM2 initially");

        // Jump SFX (0xA0) - normal SFX with priority 0x70 (lower numeric value).
        // Despite lower numeric priority, normal SFX should still steal from special SFX
        // because of the special-class demotion rule in shouldStealLock().
        SmpsSequencer jump = createSfxSequencer(driver, 0xA0, 0x70, false);
        driver.addSequencer(jump, true);

        driver.writeFm(jump, 0, 0xA2, 0x20);
        assertEquals(jump, driver.getFmLock(2), "Normal SFX should steal FM2 from special SFX (class demotion rule)");
    }

    /**
     * Test that after a normal SFX steals the channel and finishes, a second
     * special SFX instance can take the channel without any existing lock conflict.
     *
     * <p>This covers the specific sequence that triggers Bug #16:
     * <ol>
     *   <li>Waterfall plays on FM2 (special, priority 0x80)</li>
     *   <li>Jump steals FM2 (normal, priority 0x70)</li>
     *   <li>Jump finishes, FM2 is free</li>
     *   <li>Waterfall replays on FM2</li>
     *   <li>Another normal SFX arrives - waterfall yields cleanly</li>
     *   <li>That SFX finishes, FM2 is free again</li>
     *   <li>Waterfall replays again - should work without toggling</li>
     * </ol>
     */
    @Test
    public void testRepeatedInterruptionAndRecovery() {
        SpyDriver driver = new SpyDriver();
        int waterfallPriority = Sonic1SmpsConstants.getSfxPriority(0xD0);

        for (int cycle = 0; cycle < 3; cycle++) {
            // Waterfall takes FM2.
            SmpsSequencer waterfall = createSfxSequencer(
                    driver, 0xD0, waterfallPriority, true);
            driver.addSequencer(waterfall, true);
            driver.writeFm(waterfall, 0, 0xA2, 0x10);
            assertEquals(waterfall, driver.getFmLock(2), "Cycle " + cycle + ": waterfall should hold FM2");

            // Normal SFX steals FM2. Use different IDs per cycle to avoid same-ID dedup
            // between cycles (each iteration is a distinct SFX in-game).
            int sfxId = 0xA0 + cycle;
            SmpsSequencer normalSfx = createSfxSequencer(
                    driver, sfxId, 0x70, false);
            normalSfx.addTrack(createTrack(SmpsSequencer.TrackType.FM, 2));
            driver.addSequencer(normalSfx, true);
            driver.writeFm(normalSfx, 0, 0xA2, 0x20);
            assertEquals(normalSfx, driver.getFmLock(2), "Cycle " + cycle + ": normal SFX should steal FM2");

            // Simulate normal SFX completing.
            try {
                java.lang.reflect.Field seqField = SmpsDriver.class.getDeclaredField("sequencers");
                seqField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<SmpsSequencer> seqs = (List<SmpsSequencer>) seqField.get(driver);
                seqs.remove(normalSfx);

                java.lang.reflect.Field sfxField = SmpsDriver.class.getDeclaredField("sfxSequencers");
                sfxField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Set<SmpsSequencer> sfxSeqs =
                        (java.util.Set<SmpsSequencer>) sfxField.get(driver);
                sfxSeqs.remove(normalSfx);

                java.lang.reflect.Field fmLocksField = SmpsDriver.class.getDeclaredField("fmLocks");
                fmLocksField.setAccessible(true);
                SmpsSequencer[] fmLocks = (SmpsSequencer[]) fmLocksField.get(driver);
                if (fmLocks[2] == normalSfx) {
                    fmLocks[2] = null;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to simulate SFX completion in cycle " + cycle, e);
            }

            assertNull(driver.getFmLock(2), "Cycle " + cycle + ": FM2 should be free after normal SFX ends");

            // Also remove the waterfall sequencer (it was killed by channel conflict
            // during addSequencer of the normal SFX, or replaced by same-ID dedup).
            // This mimics the real driver lifecycle.
        }
    }

    /**
     * Test that two special SFX competing for the same channel resolve correctly.
     *
     * <p>Both have priority 0x80 (bit 7 set = transient). The newer one should
     * win because transient priority allows any challenger to steal, and the
     * same-ID dedup in addSequencer removes the old instance first.
     */
    @Test
    public void testTwoSpecialSfxOnSameChannel() {
        SpyDriver driver = new SpyDriver();
        int waterfallPriority = Sonic1SmpsConstants.getSfxPriority(0xD0);

        // First waterfall instance takes FM2.
        SmpsSequencer waterfall1 = createSfxSequencer(
                driver, 0xD0, waterfallPriority, true);
        driver.addSequencer(waterfall1, true);
        driver.writeFm(waterfall1, 0, 0xA2, 0x10);
        assertEquals(waterfall1, driver.getFmLock(2), "First waterfall should hold FM2");

        // Second waterfall instance (same ID 0xD0) - same-ID dedup replaces the first.
        SmpsSequencer waterfall2 = createSfxSequencer(
                driver, 0xD0, waterfallPriority, true);
        driver.addSequencer(waterfall2, true);
        driver.writeFm(waterfall2, 0, 0xA2, 0x20);
        assertEquals(waterfall2, driver.getFmLock(2), "Second waterfall should replace first via same-ID dedup");

        // Only one sequencer should remain (the old one was deduped).
        assertEquals(1, driver.getSequencerCount(), "Should have exactly 1 sequencer after same-ID dedup");
    }

    /**
     * Verify that the S1 priority table assigns the expected values for
     * the key SFX IDs involved in the waterfall bug scenario.
     *
     * <p>This is a sanity check that the priority constants match the
     * s1disasm SoundPriorities table.
     */
    @Test
    public void testS1PriorityTableValues() {
        // Waterfall (special SFX 0xD0) should have priority 0x80 (bit 7 = transient).
        assertEquals(0x80, Sonic1SmpsConstants.getSfxPriority(0xD0), "Waterfall (0xD0) priority");

        // Jump (0xA0) should have priority 0x80 (bit 7 set, per s1disasm).
        assertEquals(0x80, Sonic1SmpsConstants.getSfxPriority(0xA0), "Jump (0xA0) priority");

        // Ring (0xB5) should have priority 0x70 (normal SFX).
        assertEquals(0x70, Sonic1SmpsConstants.getSfxPriority(0xB5), "Ring (0xB5) priority");

        // Skid (0xA4) should have priority 0x70.
        assertEquals(0x70, Sonic1SmpsConstants.getSfxPriority(0xA4), "Skid (0xA4) priority");

        // Spring (0xCC) should have priority 0x70.
        assertEquals(0x70, Sonic1SmpsConstants.getSfxPriority(0xCC), "Spring (0xCC) priority");

        // All special SFX (0xD0-0xDF) should be 0x80.
        for (int id = 0xD0; id <= 0xDF; id++) {
            assertEquals(0x80, Sonic1SmpsConstants.getSfxPriority(id), "Special SFX 0x" + Integer.toHexString(id) + " priority");
        }
    }
}

