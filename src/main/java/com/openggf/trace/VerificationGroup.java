package com.openggf.trace;

/**
 * Independent verification dimensions carried by a trace comparison.
 *
 * <p>Physics includes the pre-existing gameplay, object, camera, and sidekick
 * CPU comparisons. Animation is kept separate so animation parity work can
 * advance without hiding or moving the established physics frontier.
 */
public enum VerificationGroup {
    PHYSICS("physics"),
    ANIMATION("animation");

    private final String id;

    VerificationGroup(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
