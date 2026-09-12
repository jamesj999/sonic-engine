package com.openggf.net.master;

import com.openggf.net.hub.HostRoundEngine;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real broker/relay transport with an advanceable server clock. */
public final class ControlledMasterServer {
    private final AtomicLong offsetMillis = new AtomicLong();
    private MasterServer server;

    public MasterServer start(MasterConfig config, Path directory) throws Exception {
        server = MasterServer.start(config, directory,
                () -> System.currentTimeMillis() + offsetMillis.get());
        return server;
    }

    /** Drive one normal publication cycle after observing membership on the wire. */
    public void publishPlayerCounts(String roomId) throws Exception {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        server.execute(() -> {
            try {
                RelayRoomManager.RoomAccess access = server.relays().find(roomId).orElseThrow();
                for (int tick = 0; tick < RelayRoomManager.PLAYER_COUNT_INTERVAL_TICKS; tick++) {
                    server.relays().tickAll();
                }
                // Count publication crosses broker -> room -> broker. Wait for
                // both queues before making a single rate-limited browser query.
                access.loop().execute(() -> server.execute(() -> completed.complete(null)));
            } catch (Throwable failure) {
                completed.completeExceptionally(failure);
            }
        });
        completed.get(5, TimeUnit.SECONDS);
    }

    /** Called after observing RoundStart on the wire; barriers retain loop ownership. */
    public void finishCountdown(String roomId) throws Exception {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        server.execute(() -> {
            try {
                offsetMillis.addAndGet(HostRoundEngine.COUNTDOWN_MILLIS);
                server.broker().tick();
                server.relays().tickAll();
                RelayRoomManager.RoomAccess access = server.relays().find(roomId).orElseThrow();
                // tickAll enqueues room work. Complete only after that work runs,
                // not merely after the broker has submitted it.
                access.loop().execute(() -> {
                    try {
                        assertEquals(HostRoundEngine.Phase.RUNNING, access.room().round().phase());
                        completed.complete(null);
                    } catch (Throwable failure) {
                        completed.completeExceptionally(failure);
                    }
                });
            } catch (Throwable failure) {
                completed.completeExceptionally(failure);
            }
        });
        completed.get(5, TimeUnit.SECONDS);
    }
}
