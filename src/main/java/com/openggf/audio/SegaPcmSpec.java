package com.openggf.audio;

/**
 * ROM-backed raw PCM sample metadata for the boot SEGA chant.
 *
 * @param address ROM offset of the raw PCM bytes
 * @param length byte length of the PCM blob
 * @param sampleRate source sample rate in Hz
 */
public record SegaPcmSpec(int address, int length, int sampleRate) {
}
