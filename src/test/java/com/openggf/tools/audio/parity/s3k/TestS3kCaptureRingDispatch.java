package com.openggf.tools.audio.parity.s3k;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverSessionAccess;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.DacData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kCaptureRingDispatch {
    @Test
    void selectionOccursInRealPendingRequestConsumptionOrder() {
        SmpsDriver driver = SmpsDriver.createSessionDriver(new NoWrites());
        S3kOpenGgfAudioCapture.RingDispatch rings =
                new S3kOpenGgfAudioCapture.RingDispatch();
        List<Integer> selected = new ArrayList<>();

        driver.submitServiceRequest(() -> selected.add(rings.select(0x33)));
        driver.submitServiceRequest(() -> selected.add(rings.select(0x33)));
        assertTrue(rings.leftNext(), "submission must not consume zRingSpeaker");
        driver.serviceOuterFrame();

        assertEquals(List.of(0x34, 0x33), selected);
        assertTrue(rings.leftNext());
    }

    @Test
    void explicitLeftAndNonRingRequestsDoNotAdvanceAndNewBootResets() {
        S3kOpenGgfAudioCapture.RingDispatch rings =
                new S3kOpenGgfAudioCapture.RingDispatch();

        assertEquals(0x34, rings.select(0x34));
        assertEquals(0x59, rings.select(0x59));
        assertTrue(rings.leftNext());
        assertEquals(0x34, rings.select(0x33));
        assertFalse(rings.leftNext());
        assertTrue(new S3kOpenGgfAudioCapture.RingDispatch().leftNext());
    }

    private static final class NoWrites implements SmpsDriverSessionAccess {
        @Override public void writeFm(Object source, int port, int register, int value) { }
        @Override public void writePsg(Object source, int value) { }
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
}
