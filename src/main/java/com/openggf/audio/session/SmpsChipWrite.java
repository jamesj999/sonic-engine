package com.openggf.audio.session;

public sealed interface SmpsChipWrite {
    record Ym2612(int port, int register, int value)
            implements SmpsChipWrite {
    }

    record Psg(int value) implements SmpsChipWrite {
    }
}
