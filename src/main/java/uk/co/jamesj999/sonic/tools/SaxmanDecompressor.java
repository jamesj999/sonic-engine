package uk.co.jamesj999.sonic.tools;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Decompresses Saxman-compressed data.
 * Based on description and Z80 code.
 */
public class SaxmanDecompressor {

    public byte[] decompress(byte[] compressed) {
        // First 2 bytes are size of compressed data (Little Endian)
        int compressedSize = (compressed[0] & 0xFF) | ((compressed[1] & 0xFF) << 8);

        // Input pointer starts after header
        int inPos = 2;
        // Output buffer. Sonic 2 Z80 has 0x800 buffer?
        // We'll use a dynamic buffer or fixed size. SMPS music fits in < 0x1000 usually.
        // Let's assume 4KB is enough for music tracks.
        byte[] output = new byte[0x2000]; // 8KB
        int outPos = 0;

        int description = 0;
        int bitsLeft = 0;

        // compressedSize is the size of the DATA block (excluding header?).
        // "listing the size of the compressed data".
        // The Z80 code loads BC with size, then loops.
        // It checks if input exhausted.
        // Let's assume input ends when we read compressedSize bytes.

        int inputEnd = 2 + compressedSize;

        while (inPos < compressed.length) { // Safety check, real check inside
            // Check if we need to reload description
            if (bitsLeft == 0) {
                if (inPos >= inputEnd) break;
                description = compressed[inPos++] & 0xFF;
                bitsLeft = 8;
            }

            // Read bit (LSB first)
            boolean isUncompressed = (description & 1) != 0;
            description >>= 1;
            bitsLeft--;

            if (isUncompressed) {
                if (inPos >= inputEnd) break;
                byte b = compressed[inPos++];
                output[outPos++] = b;
            } else {
                if (inPos + 1 >= inputEnd) break;
                int b1 = compressed[inPos++] & 0xFF;
                int b2 = compressed[inPos++] & 0xFF;

                // Format: [LLLL LLLL] [HHHH CCCC]
                // Length = CCCC + 3
                // Offset calculation involves HHHH and LLLL

                int length = (b2 & 0x0F) + 3;
                int h = (b2 & 0xF0) >> 4;
                int l = b1;

                int base = ((h << 8) | l);
                // "base = ((%HHHHLLLLLLLL + $12) & $FFF)"
                base = (base + 0x12) & 0xFFF;

                // "source = ((base - destination) & $FFF) + destination - $1000"
                // Java handles signed integers.
                // We need to treat 'destination' as relative to start of buffer?
                // "destination is the relative address where data will be copied to" (outPos).

                // Z80 buffer is small, so it wraps?
                // "The Saxman/z80 decoder ... does not rebase the address ... unable to handle files longer than $1000".
                // If the Z80 decoder is used for Music, we should follow the Z80 logic?
                // Z80 logic:
                // ld a,c (low byte b1)
                // add a, 12h
                // ... logic to calculate offset ...
                // The Z80 logic:
                // offset = (b2 & 0xF0) << 4 | b1
                // offset += 0x12
                // offset &= 0xFFF

                // Then:
                // output_ptr (HL)
                // offset (BC)
                // source = HL - BC
                // If source < buffer_start (0?), zero fill.
                // Wait, Z80 uses `sbc hl, bc`.
                // HL is output pointer. BC is offset.
                // So it copies from `outPos - offset`.
                // Standard LZSS.
                // But the offset calculation has the `+0x12` and rebasing?

                // Let's follow the pseudo-code:
                // source = ((base - outPos) & 0xFFF) + outPos - 0x1000;
                // If source > outPos (unsigned), zero fill.
                // Else copy from source.

                // Wait, "source > destination in unsigned comparison".
                // In Java: Integer.compareUnsigned(source, outPos) > 0.

                // But wait, the Z80 code:
                // sbc hl, bc (HL=outPos, BC=offset)
                // If Carry (result negative), jump to zero fill.
                // So if outPos < offset, zero fill.
                // This implies BC is the relative back-reference distance.
                // How is BC calculated in Z80?
                // It seems BC is derived from the [LLLL LLLL] [HHHH CCCC].

                // The Z80 code:
                // ld b, (b2 & 0xF0) >> 4
                // ld c, b1
                // bc += 0x12
                // bc &= 0xFFF

                // So `offset = ((b2 & 0xF0) << 4 | b1) + 0x12`. (Masked 0xFFF).

                // So I'll use this offset.

                int offset = ((h << 8) | l) + 0x12;
                offset &= 0xFFF;

                if (outPos < offset) {
                    // Zero fill
                    for(int i=0; i<length; i++) {
                        output[outPos++] = 0;
                    }
                } else {
                    // Copy
                    int src = outPos - offset;
                    for(int i=0; i<length; i++) {
                        output[outPos++] = output[src++];
                    }
                }
            }
        }

        return Arrays.copyOf(output, outPos);
    }
}
