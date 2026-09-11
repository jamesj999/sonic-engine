package com.openggf.game.rewind.identity;

public record PlayerRefId(int encoded) {
    public static PlayerRefId nullRef() {
        return new PlayerRefId(0);
    }

    public static PlayerRefId mainPlayer() {
        return new PlayerRefId(1);
    }

    public static PlayerRefId sidekick(int sidekickIndex) {
        if (sidekickIndex < 0) {
            throw new IllegalArgumentException("sidekickIndex must be non-negative");
        }
        return new PlayerRefId(sidekickIndex + 2);
    }

    public boolean isNull() {
        return encoded == 0;
    }
}
