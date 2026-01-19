package uk.co.jamesj999.sonic.game;

import java.util.Collections;
import java.util.List;

/**
 * No-op implementation of {@link DebugOverlayProvider} for games without custom debug overlays.
 * Used as the default implementation to avoid null checks.
 */
public final class NoOpDebugOverlayProvider implements DebugOverlayProvider {
    public static final NoOpDebugOverlayProvider INSTANCE = new NoOpDebugOverlayProvider();

    private NoOpDebugOverlayProvider() {}

    @Override
    public List<ObjectDebugLabel> getObjectLabels() {
        return Collections.emptyList();
    }

    @Override
    public List<ArtViewerTarget> getArtViewerTargets() {
        return Collections.emptyList();
    }

    @Override
    public TouchResponseDebugInfo getTouchResponseInfo() {
        return null;
    }
}
