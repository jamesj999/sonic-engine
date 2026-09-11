package com.openggf.game.sonic3k.resources;

import com.openggf.game.timing.HardwareWorkFeatures;

import java.io.IOException;
import java.util.Objects;

/** Extracts deterministic decoder-work features from a standard Kosinski stream. */
final class S3kKosinskiWorkFeatureExtractor {
    private S3kKosinskiWorkFeatureExtractor() {
    }

    static HardwareWorkFeatures inspect(
            byte[] archive,
            int offset,
            int moduleCount,
            int coordinationCount) throws IOException {
        Cursor cursor = new Cursor(archive, offset);
        Descriptor descriptor = new Descriptor(cursor);
        int literals = 0;
        int shortCopies = 0;
        int longCopies = 0;
        int copiedBytes = 0;
        int outputLength = 0;

        while (true) {
            if (descriptor.pop()) {
                cursor.read();
                literals++;
                outputLength++;
                continue;
            }

            int distance;
            int count;
            boolean longCopy;
            if (descriptor.pop()) {
                longCopy = true;
                int low = cursor.read();
                int high = cursor.read();
                distance = (((high & 0xF8) << 5) | low);
                distance = ((distance ^ 0x1FFF) + 1) & 0x1FFF;
                count = high & 0x07;
                if (count != 0) {
                    count += 2;
                } else {
                    count = cursor.read() + 1;
                    if (count == 1) {
                        break;
                    }
                    if (count == 2) {
                        continue;
                    }
                }
            } else {
                longCopy = false;
                count = 2;
                if (descriptor.pop()) {
                    count += 2;
                }
                if (descriptor.pop()) {
                    count++;
                }
                distance = (cursor.read() ^ 0xFF) + 1;
                distance &= 0xFF;
            }

            if (distance <= 0 || distance > outputLength) {
                throw new IOException("Kosinski backreference precedes output");
            }
            if (longCopy) {
                longCopies++;
            } else {
                shortCopies++;
            }
            copiedBytes = Math.addExact(copiedBytes, count);
            outputLength = Math.addExact(outputLength, count);
        }

        return new HardwareWorkFeatures(
                literals,
                shortCopies,
                longCopies,
                copiedBytes,
                cursor.position() - offset,
                outputLength,
                moduleCount,
                outputLength,
                coordinationCount);
    }

    private static final class Cursor {
        private final byte[] data;
        private int position;

        private Cursor(byte[] data, int offset) {
            this.data = Objects.requireNonNull(data, "archive");
            if (offset < 0 || offset > data.length) {
                throw new IllegalArgumentException("offset is outside archive");
            }
            position = offset;
        }

        private int read() throws IOException {
            if (position >= data.length) {
                throw new IOException("Unexpected end of Kosinski module");
            }
            return data[position++] & 0xFF;
        }

        private int position() {
            return position;
        }
    }

    private static final class Descriptor {
        private final Cursor cursor;
        private int bits;
        private int remaining;

        private Descriptor(Cursor cursor) throws IOException {
            this.cursor = cursor;
            refill();
        }

        private boolean pop() throws IOException {
            boolean set = (bits & 1) != 0;
            bits >>>= 1;
            remaining--;
            if (remaining == 0) {
                refill();
            }
            return set;
        }

        private void refill() throws IOException {
            bits = cursor.read() | (cursor.read() << 8);
            remaining = 16;
        }
    }
}
