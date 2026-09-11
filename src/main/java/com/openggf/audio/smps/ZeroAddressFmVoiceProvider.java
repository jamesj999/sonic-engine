package com.openggf.audio.smps;

/** Supplies ROM bytes read when a shipped driver dereferences a zero voice pointer. */
public interface ZeroAddressFmVoiceProvider {
    byte[] getZeroAddressFmVoice(int voiceId);
}
