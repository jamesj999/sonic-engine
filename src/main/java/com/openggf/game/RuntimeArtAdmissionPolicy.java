package com.openggf.game;

/**
 * Declares which production owner may admit a newly registered runtime-art
 * batch during a seamless level transition.
 */
public enum RuntimeArtAdmissionPolicy {
    IMMEDIATE,
    PRESERVE_CURRENT,
    TITLE_OWNER,
    RESOURCE_HANDOFF_OWNER
}
