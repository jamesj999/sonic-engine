package com.openggf.level.objects;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt with a no-arg constructor.
 *
 * <p>Use this only when constructor defaults are placeholders and the standard
 * scalar-restore pass reapplies the captured state immediately after recreate.
 * Objects needing spawn coordinates, services, or graph relinking should use a
 * more specific recreate hook.
 */
public interface ZeroArgRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return RewindRecreateConstructors.instantiateExact(
                this,
                "ZeroArgRewindRecreatable",
                "no-arg",
                "zero-arg rewind recreate",
                new Class<?>[] {});
    }
}
