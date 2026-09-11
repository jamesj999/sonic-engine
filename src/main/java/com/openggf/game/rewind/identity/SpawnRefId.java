package com.openggf.game.rewind.identity;

import com.openggf.level.objects.ObjectSpawn;

import java.util.Objects;

public record SpawnRefId(int layoutIndex) {
    public static SpawnRefId fromSpawn(ObjectSpawn spawn) {
        Objects.requireNonNull(spawn, "spawn");
        return new SpawnRefId(spawn.layoutIndex());
    }
}
