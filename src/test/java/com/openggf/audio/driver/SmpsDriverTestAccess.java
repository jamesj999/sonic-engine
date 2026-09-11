package com.openggf.audio.driver;

import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.audio.smps.DacData;

import java.util.Map;
import java.util.WeakHashMap;

/** Test-only owner for legacy driver-level fixtures migrated to sessions. */
public final class SmpsDriverTestAccess {
    private static final Map<SmpsDriver, OwnedSmpsAudioStream> STREAMS =
            new WeakHashMap<>();

    private SmpsDriverTestAccess() {
    }

    public static SmpsDriver create(double sampleRate) {
        return create(sampleRate, ChipWriteObserver.NONE);
    }

    public static synchronized SmpsDriver create(
            double sampleRate, ChipWriteObserver observer) {
        OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                "test", 0,
                new SmpsPhysicalDevice.Settings(
                        sampleRate, false),
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                ChipWriteObserver.NONE);
        stream.setChipWriteObserver(observer);
        SmpsDriver driver = stream.logicalDriver();
        STREAMS.put(driver, stream);
        return driver;
    }

    public static synchronized int read(
            SmpsDriver driver, short[] buffer) {
        return stream(driver).read(buffer);
    }

    public static synchronized int read(
            SmpsDriver driver, short[] buffer, int length) {
        return stream(driver).read(buffer, length);
    }

    public static synchronized void setChipWriteObserver(
            SmpsDriver driver, ChipWriteObserver observer) {
        stream(driver).setChipWriteObserver(observer);
    }

    public static synchronized VirtualSynthesizer.Snapshot
            captureSynthSnapshot(SmpsDriver driver) {
        return stream(driver).captureSynthSnapshotForTesting();
    }

    public static synchronized void restoreSynthSnapshot(
            SmpsDriver driver,
            VirtualSynthesizer.Snapshot snapshot,
            DacData selectedDac) {
        stream(driver).restoreSynthSnapshotForTesting(
                snapshot, selectedDac);
    }

    public static synchronized OwnedSmpsAudioStream stream(
            SmpsDriver driver) {
        OwnedSmpsAudioStream stream = STREAMS.get(driver);
        if (stream == null) {
            throw new IllegalArgumentException(
                    "driver was not created by SmpsDriverTestAccess");
        }
        return stream;
    }

    public static synchronized void close(SmpsDriver driver) {
        OwnedSmpsAudioStream stream = STREAMS.remove(driver);
        if (stream == null) {
            throw new IllegalArgumentException(
                    "driver was not created by SmpsDriverTestAccess");
        }
        stream.close();
    }

    public static synchronized int trackedSessionCount() {
        return STREAMS.size();
    }

    public static synchronized void closeAll() {
        for (OwnedSmpsAudioStream stream : STREAMS.values()) {
            stream.close();
        }
        STREAMS.clear();
    }
}
