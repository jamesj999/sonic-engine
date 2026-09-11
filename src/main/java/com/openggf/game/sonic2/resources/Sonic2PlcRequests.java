package com.openggf.game.sonic2.resources;

import com.openggf.level.objects.ObjectServices;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.sonic2.Sonic2ObjectArtProvider;

import java.io.IOException;

/** Object-owner bridge for ROM PLC submissions. */
public final class Sonic2PlcRequests {
    private Sonic2PlcRequests() {
    }

    /**
     * Appends the supplied ROM PLCs in the caller's native order.
     *
     * <p>Object construction tests intentionally omit a game module.  In that
     * presentation-only environment there is no logical hardware queue to own
     * a submission, so this is a no-op; a live session always resolves the
     * game-owned service through injected object services.
     */
    public static boolean append(ObjectServices services, int... plcIds) {
        if (services == null || services.gameModule() == null) {
            return true;
        }
        Sonic2PlcService plcService = services.gameModule().getGameService(Sonic2PlcService.class);
        ObjectArtProvider provider = services.gameModule().getObjectArtProvider();
        if (plcService == null || !(provider instanceof Sonic2ObjectArtProvider sonic2Provider)) {
            return true;
        }
        try {
            if (services.levelManager() == null) return true;
            Sonic2RuntimePlcPublisher.append(sonic2Provider, plcService,
                    services.levelManager()::refreshObjectArtPatterns, plcIds);
            return true;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
