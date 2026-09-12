package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestDefaultArgsRewindRecreatable {

    private static final ObjectSpawn SPAWN = new ObjectSpawn(0x120, 0x90, 0x42, 0x17, 0, false, -1);

    @Test
    void defaultArgumentMarkersPreserveTheirPrefixesAndSupplyPlaceholders() {
        StubObjectServices services = new StubObjectServices();
        RewindRecreateContext context = new RewindRecreateContext(SPAWN, null, services);

        SpawnDefaultProbe spawnDefault = (SpawnDefaultProbe) new SpawnDefaultProbe(
                SPAWN, "live", 12, true).recreateForRewind(context);
        assertSame(SPAWN, spawnDefault.getSpawn());
        assertDefaultArguments(spawnDefault.reference, spawnDefault.integer, spawnDefault.bool);

        SpawnServicesDefaultProbe spawnServicesDefault = (SpawnServicesDefaultProbe)
                new SpawnServicesDefaultProbe(SPAWN, services, "live", 12, true)
                        .recreateForRewind(context);
        assertSame(SPAWN, spawnServicesDefault.getSpawn());
        assertSame(services, spawnServicesDefault.services);
        assertDefaultArguments(
                spawnServicesDefault.reference, spawnServicesDefault.integer, spawnServicesDefault.bool);

        CoordinateDefaultProbe coordinateDefault = (CoordinateDefaultProbe)
                new CoordinateDefaultProbe(1, 2, "live", 12, true).recreateForRewind(context);
        assertEquals(SPAWN.x(), coordinateDefault.getX());
        assertEquals(SPAWN.y(), coordinateDefault.getY());
        assertDefaultArguments(
                coordinateDefault.reference, coordinateDefault.integer, coordinateDefault.bool);

        CoordinateSubtypeDefaultProbe coordinateSubtypeDefault = (CoordinateSubtypeDefaultProbe)
                new CoordinateSubtypeDefaultProbe(1, 2, 3, "live", 12, true).recreateForRewind(context);
        assertEquals(SPAWN.x(), coordinateSubtypeDefault.getX());
        assertEquals(SPAWN.y(), coordinateSubtypeDefault.getY());
        assertEquals(SPAWN.subtype(), coordinateSubtypeDefault.subtype);
        assertDefaultArguments(
                coordinateSubtypeDefault.reference,
                coordinateSubtypeDefault.integer,
                coordinateSubtypeDefault.bool);
    }

    @Test
    void defaultArgumentMarkersRejectUnsupportedPrimitiveConstructorsWithExistingDiagnosticIdentity() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new UnsupportedPrimitiveProbe(SPAWN, 1L).recreateForRewind(
                        new RewindRecreateContext(SPAWN, null, null)));

        assertEquals(UnsupportedPrimitiveProbe.class.getName()
                        + " implements SpawnDefaultArgsRewindRecreatable but has no unique "
                        + "(ObjectSpawn, default args...) constructor",
                failure.getMessage());
        assertEquals(UnsupportedPrimitiveProbe.class.getName()
                        + ".<init>(spawn default-args)",
                failure.getCause().getMessage());
    }

    @Test
    void defaultArgumentMarkersRejectAmbiguousConstructorsWithExistingDiagnosticIdentity() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AmbiguousDefaultProbe(SPAWN, "live").recreateForRewind(
                        new RewindRecreateContext(SPAWN, null, null)));

        assertEquals(AmbiguousDefaultProbe.class.getName()
                        + " implements SpawnDefaultArgsRewindRecreatable but has no unique "
                        + "(ObjectSpawn, default args...) constructor",
                failure.getMessage());
        assertEquals(AmbiguousDefaultProbe.class.getName()
                        + " has multiple spawn default-args constructors",
                failure.getCause().getMessage());
    }

    private static void assertDefaultArguments(Object reference, int integer, boolean bool) {
        assertNull(reference);
        assertEquals(0, integer);
        assertFalse(bool);
    }

    private abstract static class ProbeObject extends AbstractObjectInstance {
        ProbeObject(ObjectSpawn spawn, String name) {
            super(spawn, name);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No rendering is required for constructor recreation probes.
        }
    }

    private static final class SpawnDefaultProbe extends ProbeObject
            implements SpawnDefaultArgsRewindRecreatable {
        private final Object reference;
        private final int integer;
        private final boolean bool;

        SpawnDefaultProbe(ObjectSpawn spawn, Object reference, int integer, boolean bool) {
            super(spawn, "SpawnDefaultProbe");
            this.reference = reference;
            this.integer = integer;
            this.bool = bool;
        }
    }

    private static final class SpawnServicesDefaultProbe extends ProbeObject
            implements SpawnServicesDefaultArgsRewindRecreatable {
        private final ObjectServices services;
        private final Object reference;
        private final int integer;
        private final boolean bool;

        SpawnServicesDefaultProbe(
                ObjectSpawn spawn,
                ObjectServices services,
                Object reference,
                int integer,
                boolean bool) {
            super(spawn, "SpawnServicesDefaultProbe");
            this.services = services;
            this.reference = reference;
            this.integer = integer;
            this.bool = bool;
        }
    }

    private static final class CoordinateDefaultProbe extends ProbeObject
            implements SpawnCoordinateDefaultArgsRewindRecreatable {
        private final Object reference;
        private final int integer;
        private final boolean bool;

        CoordinateDefaultProbe(int x, int y, Object reference, int integer, boolean bool) {
            super(spawnAt(x, y, 0), "CoordinateDefaultProbe");
            this.reference = reference;
            this.integer = integer;
            this.bool = bool;
        }
    }

    private static final class CoordinateSubtypeDefaultProbe extends ProbeObject
            implements SpawnCoordinateSubtypeDefaultArgsRewindRecreatable {
        private final int subtype;
        private final Object reference;
        private final int integer;
        private final boolean bool;

        CoordinateSubtypeDefaultProbe(int x, int y, int subtype, Object reference, int integer, boolean bool) {
            super(spawnAt(x, y, subtype), "CoordinateSubtypeDefaultProbe");
            this.subtype = subtype;
            this.reference = reference;
            this.integer = integer;
            this.bool = bool;
        }
    }

    private static final class UnsupportedPrimitiveProbe extends ProbeObject
            implements SpawnDefaultArgsRewindRecreatable {
        UnsupportedPrimitiveProbe(ObjectSpawn spawn, long ignored) {
            super(spawn, "UnsupportedPrimitiveProbe");
        }
    }

    private static final class AmbiguousDefaultProbe extends ProbeObject
            implements SpawnDefaultArgsRewindRecreatable {
        AmbiguousDefaultProbe(ObjectSpawn spawn, String ignored) {
            super(spawn, "AmbiguousDefaultProbe");
        }

        AmbiguousDefaultProbe(ObjectSpawn spawn, Object ignored) {
            super(spawn, "AmbiguousDefaultProbe");
        }
    }

    private static ObjectSpawn spawnAt(int x, int y, int subtype) {
        return new ObjectSpawn(x, y, 0, subtype, 0, false, -1);
    }
}
