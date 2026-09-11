package com.openggf.game.sonic3k.dataselect;

final class S3kSaveTextCodec {
    private S3kSaveTextCodec() {
    }

    static int encode(char c) {
        return switch (c) {
            case ' ' -> 0;
            case '#' -> 0;
            case '*' -> 26;
            case '@', '\u00A9' -> 27;
            case ':' -> 28;
            case '.' -> 29;
            default -> {
                if (c >= '0' && c <= '9') {
                    yield 16 + (c - '0');
                }
                char upper = Character.toUpperCase(c);
                if (upper >= 'A' && upper <= 'Z') {
                    yield 30 + (upper - 'A');
                }
                throw new IllegalArgumentException("Unsupported save-screen text character: " + c);
            }
        };
    }
}
