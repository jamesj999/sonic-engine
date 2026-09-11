package com.openggf.audio.session;

import com.openggf.audio.driver.SmpsDriverServiceObserver;

public interface SmpsPhysicalPort {
    SmpsDriverServiceObserver.DriverIdentity owner();

    long epoch();

    void writeFm(int port, int register, int value);

    void writePsg(int value);

    /** Applies a PSG-only transient program without changing the output gate. */
    void applyTransientPsgSilence(SmpsWriteProgram program);

    void setInstrument(int channelId, byte[] voice);

    void playDac(int note);

    void stopDac();

    void selectDac(SmpsDacSelection selection);

    void forceSilenceFmChannel(int channelId);

    void setFmMute(int channel, boolean mute);

    void setPsgMute(int channel, boolean mute);

    void silenceOutput();

    AdmissionToken captureAdmissionState(int fmMask, int psgMask);

    void restoreAdmissionState(AdmissionToken token);

    interface AdmissionToken {
    }
}
