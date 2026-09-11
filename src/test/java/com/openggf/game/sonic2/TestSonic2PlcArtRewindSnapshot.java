package com.openggf.game.sonic2;

import com.openggf.game.session.EngineServices;
import com.openggf.game.rewind.snapshot.PlcProgressSnapshot;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link Sonic2ObjectArtProvider}'s
 * {@link com.openggf.game.rewind.RewindSnapshottable} implementation (Track F.1).
 *
 * <p>Tests verify that the key and epoch capture are stable without requiring
 * a full level load. Production-path PLC restore/replay coverage lives in
 * {@link TestSonic2RuntimePlcRendererRefresh}.
 */
class TestSonic2PlcArtRewindSnapshot {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void keyIsS2PlcArt() {
        Sonic2ObjectArtProvider provider = new Sonic2ObjectArtProvider();
        assertEquals("s2-plc-art", provider.key());
    }

    @Test
    void initialEpochIsZero() {
        Sonic2ObjectArtProvider provider = new Sonic2ObjectArtProvider();
        PlcProgressSnapshot snap = provider.capture();
        assertEquals(0, snap.loadEpoch(),
                "Initial epoch should be 0 before any zone load");
    }

    @Test
    void captureReturnsSameEpochOnSecondCall() {
        Sonic2ObjectArtProvider provider = new Sonic2ObjectArtProvider();
        PlcProgressSnapshot snap1 = provider.capture();
        PlcProgressSnapshot snap2 = provider.capture();
        assertEquals(snap1.loadEpoch(), snap2.loadEpoch(),
                "Epoch must not change between captures without a zone load");
    }

    @Test
    void restoreReinstatesCapturedEpoch() {
        Sonic2ObjectArtProvider provider = new Sonic2ObjectArtProvider();
        provider.restore(new PlcProgressSnapshot(37));

        assertEquals(37, provider.capture().loadEpoch());
    }

    @Test
    void snapshotRecordPreservesEpoch() {
        PlcProgressSnapshot snap = new PlcProgressSnapshot(42);
        assertEquals(42, snap.loadEpoch());
    }
}
