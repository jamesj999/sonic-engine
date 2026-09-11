package com.openggf.level.objects;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt from the captured {@link ObjectSpawn} and the active ROM zone id.
 *
 * <p>Use this only when the concrete class has an
 * {@code (ObjectSpawn, int romZoneId)} constructor and the integer specifically
 * selects zone-dependent construction data. Generic {@code (ObjectSpawn, int)}
 * constructors whose integer is velocity, subtype, offset, or another semantic
 * value should use a more specific recreate hook.
 */
public interface SpawnRomZoneRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return RewindRecreateConstructors.instantiateExact(
                this,
                "SpawnRomZoneRewindRecreatable",
                "(ObjectSpawn, int)",
                "spawn-ROM-zone rewind recreate",
                new Class<?>[] {ObjectSpawn.class, int.class},
                ctx.spawn(),
                romZoneId(ctx));
    }

    private static int romZoneId(RewindRecreateContext ctx) {
        ObjectServices objectServices = ctx.objectServices();
        return objectServices != null ? objectServices.romZoneId() : -1;
    }
}
