package com.openggf.data.compression;

/**
 * Observable result of one bounded Kosinski decoder step.
 *
 * @param descriptorsProcessed descriptor-controlled commands consumed
 * @param bytesProduced output bytes produced by this step
 * @param compressedBytesConsumed compressed bytes consumed from the module start
 * @param complete whether the module terminator was consumed
 */
public record DecoderStepResult(
        int descriptorsProcessed,
        int bytesProduced,
        int compressedBytesConsumed,
        boolean complete) {
}
