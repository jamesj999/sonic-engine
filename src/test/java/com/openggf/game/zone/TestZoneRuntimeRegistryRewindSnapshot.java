package com.openggf.game.zone;

import com.openggf.game.rewind.snapshot.ZoneRuntimeSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestZoneRuntimeRegistryRewindSnapshot {

    @Test
    void roundTripWithStubState() {
        StubZoneRuntimeState stub = new StubZoneRuntimeState();
        ZoneRuntimeRegistry reg = new ZoneRuntimeRegistry();
        reg.install(stub);
        stub.counter = 42;
        ZoneRuntimeSnapshot snap = reg.capture();
        stub.counter = 0;
        reg.restore(snap);
        assertEquals(42, stub.counter);
    }

    @Test
    void keyIsZoneRuntime() {
        assertEquals("zone-runtime", new ZoneRuntimeRegistry().key());
    }

    @Test
    void noOpStateReturnsEmptyBytes() {
        ZoneRuntimeRegistry reg = new ZoneRuntimeRegistry();
        ZoneRuntimeSnapshot snap = reg.capture();
        assertEquals(0, snap.stateBytes().length);
    }

    @Test
    void restoreRejectsSnapshotFromDifferentRuntimeState() {
        ZoneRuntimeRegistry reg = new ZoneRuntimeRegistry();
        StubZoneRuntimeState source = new StubZoneRuntimeState();
        reg.install(source);
        source.counter = 42;
        ZoneRuntimeSnapshot snap = reg.capture();

        reg.install(new OtherStubZoneRuntimeState());

        assertThrows(IllegalStateException.class, () -> reg.restore(snap));
    }

    @Test
    void snapshotBytesAreDefensivelyCopied() {
        StubZoneRuntimeState stub = new StubZoneRuntimeState();
        ZoneRuntimeRegistry reg = new ZoneRuntimeRegistry();
        reg.install(stub);
        stub.counter = 42;
        ZoneRuntimeSnapshot snap = reg.capture();

        byte[] exposed = snap.stateBytes();
        exposed[1] = 0;
        stub.counter = 0;

        reg.restore(snap);
        assertEquals(42, stub.counter);
    }

    static class StubZoneRuntimeState implements ZoneRuntimeState {
        int counter;

        @Override
        public String gameId() { return "stub"; }

        @Override
        public int zoneIndex() { return 0; }

        @Override
        public int actIndex() { return 0; }

        @Override
        public byte[] captureBytes() {
            return new byte[]{(byte) (counter >> 8), (byte) counter};
        }

        @Override
        public void restoreBytes(byte[] b) {
            counter = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        }
    }

    static class OtherStubZoneRuntimeState extends StubZoneRuntimeState {
    }
}
