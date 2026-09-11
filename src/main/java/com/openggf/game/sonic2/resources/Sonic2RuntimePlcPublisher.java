package com.openggf.game.sonic2.resources;

import com.openggf.game.sonic2.Sonic2ObjectArtProvider;

import java.io.IOException;

/** Commits one ROM PLC's logical FIFO work and eager art from a shared preflight. */
public final class Sonic2RuntimePlcPublisher {
    private Sonic2RuntimePlcPublisher() {
    }

    public static boolean append(Sonic2ObjectArtProvider artProvider, Sonic2PlcService plcService,
                                 Runnable refreshRendererCache, int... plcIds) throws IOException {
        Sonic2PlcService.Operation[] operations = java.util.Arrays.stream(plcIds)
                .mapToObj(Sonic2PlcService::appendOperation)
                .toArray(Sonic2PlcService.Operation[]::new);
        return transact(artProvider, plcService, refreshRendererCache, operations);
    }

    /** Preflights a full native mixed-operation sequence and commits it with eager art. */
    public static boolean transact(Sonic2ObjectArtProvider artProvider, Sonic2PlcService plcService,
                                   Runnable refreshRendererCache,
                                   Sonic2PlcService.Operation... operations) throws IOException {
        int[] artPlcIds = java.util.Arrays.stream(operations)
                .filter(operation -> operation.kind() != Sonic2PlcService.OperationKind.CLEAR)
                .mapToInt(Sonic2PlcService.Operation::plcId)
                .toArray();
        Sonic2ObjectArtProvider.PreparedPlc prepared = artProvider.preparePlcs(artPlcIds);
        artProvider.preflightPreparedPlc(prepared);
        if (!prepared.sheets().isEmpty() && refreshRendererCache == null) {
            throw new IllegalStateException("Runtime PLC renderer publication requires a cache refresh owner");
        }
        plcService.transact(operations);
        boolean published = artProvider.publishPreparedPlc(prepared);
        if (published) {
            refreshRendererCache.run();
        }
        return published;
    }
}
