package com.openggf.game.resources;

import java.util.List;

/** Read-only projection of game-owned physical load queues. */
public interface QueueDiagnosticsProvider {
    default List<QueueDiagnosticSnapshot> captureQueueDiagnostics() {
        return List.of();
    }
}
