package com.openggf.game.save;

public record SaveEnvelope(int version, String game, int slot, Object payload, String hash) {
}
