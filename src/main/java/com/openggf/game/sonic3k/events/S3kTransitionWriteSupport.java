package com.openggf.game.sonic3k.events;

import com.openggf.game.LevelEventProvider;
import com.openggf.level.objects.ObjectServices;

public final class S3kTransitionWriteSupport {
    private S3kTransitionWriteSupport() {
    }

    public static int resultsCreateGateDispatches(ObjectServices services) {
        Object provider = services.levelEventProvider();
        if (provider instanceof S3kTransitionEventBridge bridge) {
            return bridge.resultsCreateGateDispatches();
        }
        return 9;
    }

    public static void signalActTransition(ObjectServices services) {
        Object provider = services.levelEventProvider();
        if (provider instanceof S3kTransitionEventBridge bridge) {
            bridge.signalActTransition();
        }
    }

    public static void requestHczPostTransitionCutscene(LevelEventProvider provider) {
        if (provider instanceof S3kTransitionEventBridge bridge) {
            bridge.requestHczPostTransitionCutscene();
        }
    }

    /**
     * Completes any event-owned post-results handoff and reports whether that
     * retained native owner still owns publication of the transition-ready
     * flag. The event provider, rather than the results object, decides this
     * from its live transition state.
     */
    public static boolean completePostResultsHandoff(ObjectServices services) {
        Object provider = services.levelEventProvider();
        if (provider instanceof S3kTransitionEventBridge bridge) {
            return bridge.restorePendingPostResultsPlayerControl();
        }
        return false;
    }

    public static void preparePreloadedActTitleCardCompletion(LevelEventProvider provider) {
        if (provider instanceof S3kTransitionEventBridge bridge) {
            bridge.preparePreloadedActTitleCardCompletion();
        }
    }

    public static void preparePreloadedActTitleCardRuntimeArtAdmission(
            LevelEventProvider provider) {
        if (provider instanceof S3kTransitionEventBridge bridge) {
            bridge.preparePreloadedActTitleCardRuntimeArtAdmission();
        }
    }

    public static int preloadedActCameraReleaseAdditionalDispatches(
            ObjectServices services) {
        Object provider = services.levelEventProvider();
        if (provider instanceof S3kTransitionEventBridge bridge) {
            return bridge.preloadedActCameraReleaseAdditionalDispatches();
        }
        return -1;
    }

    public static void requestMgzPostTransitionRelease(LevelEventProvider provider) {
        if (provider instanceof S3kTransitionEventBridge bridge) {
            bridge.requestMgzPostTransitionRelease();
        }
    }

    public static void requestCnzPostTransitionRelease(LevelEventProvider provider) {
        if (provider instanceof S3kTransitionEventBridge bridge) {
            bridge.requestCnzPostTransitionRelease();
        }
    }
}
