package com.openggf.game.rewind;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pins an object class onto the destroy/recreate path of the rewind restore,
 * excluding it from in-place instance reuse regardless of what the reflective
 * non-captured-field audit concludes.
 *
 * <p><strong>When to use it:</strong> only for constructors with
 * restore-relevant side effects that neither the field audit nor the runtime
 * construction side-effect latch can observe — e.g. writes to global zone
 * state ({@code HCZWaterRushObjectInstance}'s constructor calls
 * {@code HCZBreakableBarState.setState(3)}) or registrations into external
 * registries. The annotation preserves the destroy/recreate semantics
 * (constructor re-runs on every restore) explicitly rather than incidentally.
 *
 * <p><strong>When NOT to use it:</strong> do not add this annotation for
 * constructors that only spawn child objects or reserve child slots. The
 * restore path's construction side-effect latch observes those automatically
 * (it watches {@code dynamicObjects} and {@code reservedChildSlots} across
 * each in-restore reconstruction) and already keeps such classes on the
 * recreate path without any annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RewindRecreateOnRestore {
    /** Why this class must keep destroy/recreate restore semantics. */
    String reason();
}
