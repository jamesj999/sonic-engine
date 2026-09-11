package com.openggf.level;

import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.level.resources.DeferredLevelResourceManifest;

/**
 * Session-owned resources carried through one seamless level reload.
 */
public interface SeamlessTransitionResourceHandoff {
    DeferredLevelResourceManifest deferredResources();

    /**
     * Returns the immutable claimed-path value that owns the exact target-art
     * admission lease.
     */
    default SeamlessTransitionResourceHandoff withAdmissionLease(
            RuntimeArtAdmissionLease lease) {
        throw new IllegalStateException(
                "resource handoff does not accept runtime-art admission");
    }

    void transferAfterTargetInit();
}
