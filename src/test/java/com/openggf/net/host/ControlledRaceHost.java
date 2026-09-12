package com.openggf.net.host;

import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Real transport with room time advanced on the host's owning event loop. */
public final class ControlledRaceHost {
    private final AtomicLong offsetMillis = new AtomicLong();
    private RaceHostServer server;

    public RaceHostServer start(int port, RoomHostConfig config,
                                PlayerIdentity identity,
                                TrackValidationProfileSource profiles) {
        server = RaceHostServer.start(port, config, identity, profiles,
                () -> System.currentTimeMillis() + offsetMillis.get());
        return server;
    }

    public void advance(long millis) throws Exception {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        server.execute(() -> {
            try {
                offsetMillis.addAndGet(millis);
                server.room().tick();
                completed.complete(null);
            } catch (Throwable failure) {
                completed.completeExceptionally(failure);
            }
        });
        completed.get(5, TimeUnit.SECONDS);
    }
}
