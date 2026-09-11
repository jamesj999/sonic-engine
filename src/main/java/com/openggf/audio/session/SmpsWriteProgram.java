package com.openggf.audio.session;

import java.util.List;

public record SmpsWriteProgram(List<SmpsChipWrite> writes) {
    public static final SmpsWriteProgram EMPTY =
            new SmpsWriteProgram(List.of());
    public static final SmpsWriteProgram SILENCE_ALL_PSG =
            new SmpsWriteProgram(List.of(
                    new SmpsChipWrite.Psg(0x9F),
                    new SmpsChipWrite.Psg(0xBF),
                    new SmpsChipWrite.Psg(0xDF),
                    new SmpsChipWrite.Psg(0xFF)));

    public SmpsWriteProgram {
        writes = List.copyOf(writes);
    }
}
