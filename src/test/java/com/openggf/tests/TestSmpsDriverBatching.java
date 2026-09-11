package com.openggf.tests;

import com.openggf.audio.AudioManager;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverTestAccess;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestSmpsDriverBatching {

    @Test
    public void sampleAccurateAndHybridReadModesRenderIdenticallyForSteadyFmSequence() {
        short[] sampleAccurate = renderSteadyFmSequence(SmpsDriver.ReadMode.SAMPLE_ACCURATE);
        short[] hybrid = renderSteadyFmSequence(SmpsDriver.ReadMode.HYBRID);

        assertArrayEquals(sampleAccurate, hybrid);
    }

    @Test
    public void driverDefaultsToHybridReadMode() throws Exception {
        SmpsDriver driver = new SmpsDriver();

        assertEquals(SmpsDriver.ReadMode.HYBRID, readMode(driver));
    }

    @Test
    public void hybridMode_usesChunkRenderingForSteadySequence() {
        SmpsDriver driver = newDriver(SmpsDriver.ReadMode.HYBRID);
        SmpsSequencer seq = newSequencer(driver, steadyScript());

        assertFalse(seq.requiresSampleAccurateFallback());

        driver.addSequencer(seq, false);
        SmpsDriverTestAccess.read(driver, new short[4096]);

        assertTrue(driver.getHybridChunkCountForTesting() > 0);
    }

    @Test
    public void hybridMode_matchesSampleAccurateForFillBoundaryScript() {
        short[] sampleAccurate = renderSequence(SmpsDriver.ReadMode.SAMPLE_ACCURATE, fillScript(), 4096);
        short[] hybrid = renderSequence(SmpsDriver.ReadMode.HYBRID, fillScript(), 4096);

        assertArrayEquals(sampleAccurate, hybrid);
    }

    private static short[] renderSteadyFmSequence(SmpsDriver.ReadMode readMode) {
        return renderSequence(readMode, steadyScript(), 256);
    }

    private static short[] renderSequence(SmpsDriver.ReadMode readMode, byte[] script, int sampleCount) {
        SmpsDriver driver = newDriver(readMode);
        driver.addSequencer(newSequencer(driver, script), false);

        short[] buffer = new short[sampleCount];
        SmpsDriverTestAccess.read(driver, buffer);
        return buffer;
    }

    private static SmpsDriver newDriver(SmpsDriver.ReadMode readMode) {
        SmpsDriver driver = SmpsDriverTestAccess.create(44_100);
        driver.setReadModeForTesting(readMode);
        return driver;
    }

    private static SmpsSequencer newSequencer(SmpsDriver driver, byte[] data) {
        AbstractSmpsData smps = new Sonic2SmpsData(data);
        return new SmpsSequencer(smps, new DacData(new HashMap<>(), new HashMap<>()),
                driver, AudioManager.getInstance(), Sonic2SmpsSequencerConfig.CONFIG);
    }

    private static SmpsDriver.ReadMode readMode(SmpsDriver driver) throws Exception {
        Field field = SmpsDriver.class.getDeclaredField("readMode");
        field.setAccessible(true);
        return (SmpsDriver.ReadMode) field.get(driver);
    }

    private static short[] chunkScratch(SmpsDriver driver) throws Exception {
        Field field = SmpsDriver.class.getDeclaredField("chunkScratch");
        field.setAccessible(true);
        return (short[]) field.get(driver);
    }

    private static void setChunkScratch(SmpsDriver driver, short[] scratch) throws Exception {
        Field field = SmpsDriver.class.getDeclaredField("chunkScratch");
        field.setAccessible(true);
        field.set(driver, scratch);
    }

    private static void invokeRenderChunk(SmpsDriver driver, short[] target, int frameOffset, int frames) throws Exception {
        Method method = SmpsDriver.class.getDeclaredMethod("renderChunk", short[].class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(driver, target, frameOffset, frames);
    }

    private static byte[] steadyScript() {
        return script((byte) 0x81, (byte) 0x10, (byte) 0xF2);
    }

    private static byte[] fillScript() {
        return script((byte) 0xE8, (byte) 0x02, (byte) 0x81, (byte) 0x03, (byte) 0xF2);
    }

    private static byte[] script(byte... trackData) {
        byte[] data = new byte[Math.max(32, 0x14 + trackData.length)];
        data[2] = 2;
        data[4] = 1;
        data[5] = (byte) 0x80;
        data[10] = 0x14;
        data[11] = 0x00;
        System.arraycopy(trackData, 0, data, 0x14, trackData.length);
        return data;
    }
}
