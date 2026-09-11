package com.openggf.audio.rewind;

import com.openggf.audio.smps.SmpsSequencer.TrackType;

import java.util.Arrays;

public record SmpsTrackSnapshot(
        int pos,
        TrackType type,
        int channelId,
        int duration,
        int note,
        boolean active,
        boolean overridden,
        int rawDuration,
        int scaledDuration,
        int fill,
        int fillCounter,
        boolean resting,
        int keyOffset,
        int volumeOffset,
        boolean tieNext,
        int pan,
        int ams,
        int fms,
        byte[] voiceData,
        byte[] voiceScratch,
        int voiceId,
        int baseFnum,
        int baseBlock,
        int[] loopCounters,
        int loopTarget,
        int[] returnStack,
        int returnSp,
        int dividingTiming,
        int modDelay,
        int modDelayInit,
        int modRate,
        int modDelta,
        int modSteps,
        int modStepsFull,
        int modPendingDelayInit,
        int modPendingRate,
        int modPendingDelta,
        int modPendingSteps,
        int modPendingStepsFull,
        int modRateCounter,
        int modStepCounter,
        short modAccumulator,
        int modCurrentDelta,
        boolean modEnabled,
        boolean customModEnabled,
        int detune,
        int modEnvId,
        byte[] modEnvData,
        int modEnvPos,
        int modEnvMult,
        int modEnvCache,
        boolean modEnvHold,
        boolean rawFreqMode,
        int rawFrequency,
        int instrumentId,
        boolean noiseMode,
        int psgNoiseParam,
        int decayOffset,
        int decayTimer,
        byte[] envData,
        int envPos,
        int envValue,
        boolean envHold,
        boolean envAtRest,
        byte[] fmVolEnvData,
        int fmVolEnvPos,
        int fmVolEnvValue,
        boolean fmVolEnvHold,
        int fmVolEnvOpMask,
        boolean forceRefresh,
        int[] ssgEg,
        boolean dacMuted,
        boolean modStepInEffect,
        boolean modStepChanged,
        int modStepDelta,
        boolean modEnvStepInEffect,
        boolean modEnvStepChanged,
        int modEnvStepDelta,
        boolean fm3SpecialMode,
        boolean customSsgEgPresent,
        int[] customSsgEgPayload,
        boolean customSsgEgPayloadKnown,
        int rawPsgNoise,
        boolean rawPsgNoiseKnown) {

    public SmpsTrackSnapshot {
        voiceData = copy(voiceData);
        voiceScratch = copy(voiceScratch);
        loopCounters = copy(loopCounters);
        returnStack = copy(returnStack);
        modEnvData = copy(modEnvData);
        envData = copy(envData);
        fmVolEnvData = copy(fmVolEnvData);
        ssgEg = copy(ssgEg);
        customSsgEgPayload = copy(customSsgEgPayload);
    }

    private static byte[] copy(byte[] values) {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }

    private static int[] copy(int[] values) {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }

    @Override
    public byte[] voiceData() {
        return copy(voiceData);
    }

    @Override
    public byte[] voiceScratch() {
        return copy(voiceScratch);
    }

    @Override
    public int[] loopCounters() {
        return copy(loopCounters);
    }

    @Override
    public int[] returnStack() {
        return copy(returnStack);
    }

    @Override
    public byte[] modEnvData() {
        return copy(modEnvData);
    }

    @Override
    public byte[] envData() {
        return copy(envData);
    }

    @Override
    public byte[] fmVolEnvData() {
        return copy(fmVolEnvData);
    }

    @Override
    public int[] ssgEg() {
        return copy(ssgEg);
    }

    @Override
    public int[] customSsgEgPayload() {
        return copy(customSsgEgPayload);
    }
}
