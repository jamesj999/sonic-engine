package com.openggf.data.compression;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Deterministic, descriptor-budgeted decoder for one standard Kosinski module.
 *
 * <p>The budget counts top-level literal/match commands, not host time. A
 * module terminator is a command and is therefore observed at a deterministic
 * preparation boundary.
 */
public final class ResumableKosinskiDecoder {
    private static final int MAX_OUTPUT_SIZE = 0x100000;

    private final byte[] input;
    private final int moduleStart;
    private int readPosition;
    private int descriptor;
    private int descriptorBitsRemaining;
    private byte[] output;
    private int outputSize;
    private boolean complete;

    public ResumableKosinskiDecoder(byte[] input) throws IOException {
        this(input, 0);
    }

    public ResumableKosinskiDecoder(byte[] input, int moduleStart) throws IOException {
        this.input = Objects.requireNonNull(input, "input").clone();
        if (moduleStart < 0 || moduleStart > input.length) {
            throw new IllegalArgumentException("moduleStart is outside input");
        }
        this.moduleStart = moduleStart;
        this.readPosition = moduleStart;
        this.output = new byte[Math.min(0x1000, MAX_OUTPUT_SIZE)];
        readDescriptor();
    }

    private ResumableKosinskiDecoder(DecoderSnapshot snapshot) {
        this.input = snapshot.input();
        this.moduleStart = snapshot.moduleStart();
        this.readPosition = snapshot.readPosition();
        this.descriptor = snapshot.descriptor();
        this.descriptorBitsRemaining = snapshot.descriptorBitsRemaining();
        byte[] capturedOutput = snapshot.output();
        this.output = Arrays.copyOf(capturedOutput,
                Math.max(0x1000, capturedOutput.length));
        this.outputSize = capturedOutput.length;
        this.complete = snapshot.complete();
    }

    public DecoderStepResult step(int descriptorBudget) throws IOException {
        if (descriptorBudget < 0) {
            throw new IllegalArgumentException("descriptorBudget must be non-negative");
        }
        int startOutput = outputSize;
        int processed = 0;
        while (!complete && processed < descriptorBudget) {
            decodeCommand();
            processed++;
        }
        return new DecoderStepResult(
                processed,
                outputSize - startOutput,
                compressedBytesConsumed(),
                complete);
    }

    public boolean complete() {
        return complete;
    }

    public byte[] output() {
        return Arrays.copyOf(output, outputSize);
    }

    public int compressedBytesConsumed() {
        return readPosition - moduleStart;
    }

    public DecoderSnapshot snapshot() {
        return new DecoderSnapshot(
                input,
                moduleStart,
                readPosition,
                descriptor,
                descriptorBitsRemaining,
                output(),
                complete);
    }

    public void restore(DecoderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!Arrays.equals(input, snapshot.input())
                || moduleStart != snapshot.moduleStart()) {
            throw new IllegalArgumentException(
                    "snapshot belongs to a different Kosinski module");
        }
        readPosition = snapshot.readPosition();
        descriptor = snapshot.descriptor();
        descriptorBitsRemaining = snapshot.descriptorBitsRemaining();
        byte[] restoredOutput = snapshot.output();
        output = Arrays.copyOf(restoredOutput,
                Math.max(0x1000, restoredOutput.length));
        outputSize = restoredOutput.length;
        complete = snapshot.complete();
    }

    public static ResumableKosinskiDecoder fromSnapshot(DecoderSnapshot snapshot) {
        return new ResumableKosinskiDecoder(
                Objects.requireNonNull(snapshot, "snapshot"));
    }

    private void decodeCommand() throws IOException {
        if (popDescriptor()) {
            writeByte(readByte());
            return;
        }

        int distance;
        int count;
        if (popDescriptor()) {
            int lowByte = readByte();
            int highByte = readByte();
            distance = ((highByte & 0xF8) << 5) | lowByte;
            distance = ((distance ^ 0x1FFF) + 1) & 0x1FFF;
            count = highByte & 0x07;
            if (count != 0) {
                count += 2;
            } else {
                count = readByte() + 1;
                if (count == 1) {
                    complete = true;
                    return;
                }
                if (count == 2) {
                    return;
                }
            }
        } else {
            count = 2;
            if (popDescriptor()) {
                count += 2;
            }
            if (popDescriptor()) {
                count++;
            }
            distance = (readByte() ^ 0xFF) + 1;
            distance &= 0xFF;
        }

        for (int i = 0; i < count; i++) {
            int source = outputSize - distance;
            if (source < 0) {
                throw new IOException("Kosinski backreference precedes output");
            }
            byte value = output[source];
            writeByte(value & 0xFF);
        }
    }

    private boolean popDescriptor() throws IOException {
        boolean result = (descriptor & 1) != 0;
        descriptor >>>= 1;
        descriptorBitsRemaining--;
        if (descriptorBitsRemaining == 0) {
            readDescriptor();
        }
        return result;
    }

    private void readDescriptor() throws IOException {
        int lowByte = readByte();
        int highByte = readByte();
        descriptor = (highByte << 8) | lowByte;
        descriptorBitsRemaining = 16;
    }

    private int readByte() throws IOException {
        if (readPosition >= input.length) {
            throw new IOException("Unexpected end of Kosinski module");
        }
        return input[readPosition++] & 0xFF;
    }

    private void writeByte(int value) throws IOException {
        if (outputSize >= MAX_OUTPUT_SIZE) {
            throw new IOException(
                    "Kosinski decompression exceeded maximum output size");
        }
        if (outputSize == output.length) {
            output = Arrays.copyOf(output,
                    Math.min(MAX_OUTPUT_SIZE, Math.max(1, output.length * 2)));
        }
        output[outputSize++] = (byte) value;
    }
}
