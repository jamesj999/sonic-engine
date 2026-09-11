package com.openggf.audio;

import com.openggf.audio.presentation.DecodedPcm;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestWavDecoderValidation {

    @Test
    void decodesUnsigned8BitMonoToSignedSamples() throws Exception {
        DecodedPcm pcm = WavDecoder.decodePcm("u8", wav(1, 8, 22050,
                new byte[] {0, (byte) 128, (byte) 255}));

        assertEquals(1, pcm.channels());
        assertArrayEquals(new short[] {-32768, 0, 32512}, pcm.copySamples());
    }

    @Test
    void decodesLittleEndian16BitStereo() throws Exception {
        DecodedPcm pcm = WavDecoder.decodePcm("stereo", wav(2, 16, 48_000,
                new byte[] {0x34, 0x12, (byte) 0xFE, (byte) 0xFF}));

        assertEquals(2, pcm.channels());
        assertEquals(48_000, pcm.sampleRate());
        assertEquals(1, pcm.sourceFrames());
        assertArrayEquals(new short[] {0x1234, -2}, pcm.copySamples());
    }

    @Test
    void rejectsUnsupportedChannelsBitsRatesAndTruncation() {
        assertAll(
                () -> assertThrows(IOException.class, () -> decode(wavHeader(3, 16, 48000, 6), 6)),
                () -> assertThrows(IOException.class, () -> decode(wavHeader(1, 24, 48000, 3), 3)),
                () -> assertThrows(IOException.class, () -> decode(wavHeader(1, 16, 0, 2), 2)),
                () -> assertThrows(IOException.class, () -> decode(declaredDataLargerThanFile(), 0)));
    }

    @Test
    void rejectsInconsistentFormatAndChunkBounds() {
        assertAll(
                () -> assertThrows(IOException.class, () -> WavDecoder.decodePcm("bad-align",
                        wavWithFormat(1, 16, 48_000, 1, 96_000, new byte[] {0, 0}))),
                () -> assertThrows(IOException.class, () -> WavDecoder.decodePcm("bad-rate",
                        wavWithFormat(2, 16, 48_000, 4, 48_000, new byte[] {0, 0, 0, 0}))),
                () -> assertThrows(IOException.class, () -> WavDecoder.decodePcm("partial-frame",
                        wav(2, 16, 48_000, new byte[] {0, 0, 0}))),
                () -> assertThrows(IOException.class, () -> WavDecoder.decodePcm("riff-overrun",
                        riffWithDeclaredSize(wav(1, 8, 48_000, new byte[] {0}), 0x7FFF_FFFFL))),
                () -> assertThrows(IOException.class, () -> WavDecoder.decodePcm("chunk-overrun",
                        chunkDeclaresMoreThanRiff())));
    }

    @Test
    void skipsOddSizedChunksUsingRiffPadding() throws Exception {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        chunks.writeBytes(fmtChunk(1, 8, 8_000, 1, 8_000));
        writeChunk(chunks, "JUNK", new byte[] {1, 2, 3});
        writeChunk(chunks, "data", new byte[] {(byte) 128});

        DecodedPcm pcm = WavDecoder.decodePcm("padded", riff(chunks.toByteArray()));

        assertArrayEquals(new short[] {0}, pcm.copySamples());
    }

    private static ByteArrayInputStream wav(int channels, int bits, int rate, byte[] data) {
        int bytesPerSample = bits / Byte.SIZE;
        return wavWithFormat(channels, bits, rate, channels * bytesPerSample,
                rate * channels * bytesPerSample, data);
    }

    private static ByteArrayInputStream wavWithFormat(int channels, int bits, int rate,
                                                       int blockAlign, int byteRate, byte[] data) {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        chunks.writeBytes(fmtChunk(channels, bits, rate, blockAlign, byteRate));
        writeChunk(chunks, "data", data);
        return riff(chunks.toByteArray());
    }

    private static ByteArrayInputStream wavHeader(int channels, int bits, int rate, int dataLength) {
        return wav(channels, bits, rate, new byte[dataLength]);
    }

    private static DecodedPcm decode(ByteArrayInputStream source, int ignoredDataLength) throws IOException {
        return WavDecoder.decodePcm("fixture", source);
    }

    private static ByteArrayInputStream declaredDataLargerThanFile() {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        chunks.writeBytes(fmtChunk(1, 8, 8_000, 1, 8_000));
        writeInt(chunks, "data");
        writeLeInt(chunks, 4);
        chunks.write(0);
        return riff(chunks.toByteArray());
    }

    private static ByteArrayInputStream chunkDeclaresMoreThanRiff() {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        chunks.writeBytes(fmtChunk(1, 8, 8_000, 1, 8_000));
        writeInt(chunks, "JUNK");
        writeLeInt(chunks, 8);
        chunks.write(0);
        return riff(chunks.toByteArray());
    }

    private static ByteArrayInputStream riffWithDeclaredSize(ByteArrayInputStream source, long declaredSize) {
        byte[] bytes = source.readAllBytes();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, (int) declaredSize);
        return new ByteArrayInputStream(bytes);
    }

    private static ByteArrayInputStream riff(byte[] chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, "RIFF");
        writeLeInt(out, chunks.length + 4);
        writeInt(out, "WAVE");
        out.writeBytes(chunks);
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static byte[] fmtChunk(int channels, int bits, int rate, int blockAlign, int byteRate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLeShort(out, 1);
        writeLeShort(out, channels);
        writeLeInt(out, rate);
        writeLeInt(out, byteRate);
        writeLeShort(out, blockAlign);
        writeLeShort(out, bits);
        return chunk("fmt ", out.toByteArray());
    }

    private static byte[] chunk(String name, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, name, data);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String name, byte[] data) {
        writeInt(out, name);
        writeLeInt(out, data.length);
        out.writeBytes(data);
        if ((data.length & 1) != 0) {
            out.write(0);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeLeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
