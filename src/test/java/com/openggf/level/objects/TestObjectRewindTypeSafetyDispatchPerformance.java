package com.openggf.level.objects;

import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.graphics.GLCommand;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestObjectRewindTypeSafetyDispatchPerformance {
    private static final int BATCH_SIZE = 10_000;
    private static final int SAMPLE_COUNT = 7;
    private static final ObjectSpawn SPAWN =
            new ObjectSpawn(100, 200, 1, 0, 0, false, 0);
    private static final PerObjectRewindSnapshot BENCHMARK_SNAPSHOT =
            new PerObjectRewindSnapshot(
                    false, false,
                    false, 0, 0,
                    0, 0, false, -1,
                    false, true,
                    -1, -1,
                    null, null, null);
    private static final PerObjectRewindSnapshot[] SNAPSHOT_SINK =
            new PerObjectRewindSnapshot[BATCH_SIZE];

    @Test
    void defaultRoutePreservesStateOnColdAndWarmDispatch() {
        DefaultRouteObject object = new DefaultRouteObject();

        object.setSlotIndex(17);
        PerObjectRewindSnapshot cold = ObjectRewindTypeSafety.capture(
                object, RewindCaptureContext.none());
        object.setSlotIndex(99);
        ObjectRewindTypeSafety.restore(object, cold, RewindCaptureContext.none());
        assertEquals(17, object.getSlotIndex());

        object.setSlotIndex(23);
        PerObjectRewindSnapshot warm = ObjectRewindTypeSafety.capture(
                object, RewindCaptureContext.none());
        object.setSlotIndex(101);
        ObjectRewindTypeSafety.restore(object, warm, RewindCaptureContext.none());
        assertEquals(23, object.getSlotIndex());
    }

    @Test
    void legacyRouteSelectsLegacyOverridesOnColdAndWarmDispatch() {
        LegacyRouteObject object = new LegacyRouteObject();

        PerObjectRewindSnapshot first = ObjectRewindTypeSafety.capture(
                object, context());
        ObjectRewindTypeSafety.restore(object, first, context());
        PerObjectRewindSnapshot second = ObjectRewindTypeSafety.capture(
                object, context());
        ObjectRewindTypeSafety.restore(object, second, context());

        assertEquals(2, object.legacyCaptureCalls);
        assertEquals(2, object.legacyRestoreCalls);
    }

    @Test
    void contextRouteForwardsTheRequestedContextOnColdAndWarmDispatch() {
        ContextRouteObject object = new ContextRouteObject();
        RewindCaptureContext context = context();

        PerObjectRewindSnapshot first = ObjectRewindTypeSafety.capture(object, context);
        ObjectRewindTypeSafety.restore(object, first, context);
        PerObjectRewindSnapshot second = ObjectRewindTypeSafety.capture(object, context);
        ObjectRewindTypeSafety.restore(object, second, context);

        assertEquals(2, object.contextCaptureCalls);
        assertEquals(2, object.contextRestoreCalls);
        assertSame(context, object.lastCaptureContext);
        assertSame(context, object.lastRestoreContext);
    }

    @Test
    void captureAndRestoreRoutesAreResolvedIndependently() {
        MixedRouteObject object = new MixedRouteObject();
        RewindCaptureContext context = context();

        PerObjectRewindSnapshot first = ObjectRewindTypeSafety.capture(object, context);
        ObjectRewindTypeSafety.restore(object, first, context);
        PerObjectRewindSnapshot second = ObjectRewindTypeSafety.capture(object, context);
        ObjectRewindTypeSafety.restore(object, second, context);

        assertEquals(2, object.legacyCaptureCalls);
        assertEquals(2, object.contextRestoreCalls);
        assertSame(context, object.lastRestoreContext);
    }

    @Test
    void inheritedLegacyRouteIsResolvedThroughIntermediateSuperclass() {
        InheritedLegacyRouteObject object = new InheritedLegacyRouteObject();

        PerObjectRewindSnapshot snapshot = ObjectRewindTypeSafety.capture(
                object, context());
        ObjectRewindTypeSafety.restore(object, snapshot, context());

        assertEquals(1, object.legacyCaptureCalls);
        assertEquals(1, object.legacyRestoreCalls);
    }

    @Test
    void warmDispatchReusesResolvedRouteDescriptor() {
        ObjectRewindTypeSafety.DispatchRoute coldRoute =
                ObjectRewindTypeSafety.dispatchRoute(CacheIdentityRouteObject.class);
        ObjectRewindTypeSafety.DispatchRoute warmRoute =
                ObjectRewindTypeSafety.dispatchRoute(CacheIdentityRouteObject.class);

        assertSame(coldRoute, warmRoute);
    }

    @Test
    @EnabledIfSystemProperty(
            named = "openggf.performance.rewindDispatch.measure",
            matches = "true")
    void measureMixedRouteDispatchAllocationAndTime() {
        ThreadMXBean bean = allocationBeanOrSkip();
        AbstractObjectInstance[] objects = mixedRouteObjects();
        RewindCaptureContext context = context();

        measureBatch(bean, objects, context);
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            BatchMeasurement measurement = measureBatch(bean, objects, context);
            System.out.printf(
                    "rewind-dispatch sample=%d operations=%d allocatedBytes=%d timeNs=%d%n",
                    sample + 1, BATCH_SIZE, measurement.allocatedBytes(),
                    measurement.timeNanos());
        }
    }

    private static BatchMeasurement measureBatch(
            ThreadMXBean bean,
            AbstractObjectInstance[] objects,
            RewindCaptureContext context) {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
        long timeBefore = System.nanoTime();
        for (int i = 0; i < objects.length; i++) {
            PerObjectRewindSnapshot snapshot =
                    ObjectRewindTypeSafety.capture(objects[i], context);
            SNAPSHOT_SINK[i] = snapshot;
            ObjectRewindTypeSafety.restore(objects[i], snapshot, context);
        }
        long elapsed = System.nanoTime() - timeBefore;
        long allocated = bean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        return new BatchMeasurement(allocated, elapsed);
    }

    private static AbstractObjectInstance[] mixedRouteObjects() {
        AbstractObjectInstance[] objects = new AbstractObjectInstance[BATCH_SIZE];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = switch (i & 3) {
                case 0 -> new BenchmarkLegacyRouteObject();
                case 1 -> new BenchmarkContextRouteObject();
                case 2 -> new BenchmarkLegacyCaptureContextRestoreObject();
                default -> new BenchmarkContextCaptureLegacyRestoreObject();
            };
        }
        return objects;
    }

    private static ThreadMXBean allocationBeanOrSkip() {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(raw instanceof ThreadMXBean,
                "ThreadMXBean allocation accounting unavailable");
        ThreadMXBean bean = (ThreadMXBean) raw;
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "Thread allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        Assumptions.assumeTrue(
                bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) >= 0L,
                "Thread allocation accounting unavailable for current thread");
        return bean;
    }

    private static RewindCaptureContext context() {
        return RewindCaptureContext.withIdentityTable(new RewindIdentityTable());
    }

    private record BatchMeasurement(long allocatedBytes, long timeNanos) {
    }

    private abstract static class RouteObject extends AbstractObjectInstance {
        RouteObject(String name) {
            super(SPAWN, name);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No rendering is required by the dispatch fixture.
        }
    }

    private static final class DefaultRouteObject extends RouteObject {
        DefaultRouteObject() {
            super("DefaultRouteObject");
        }
    }

    private static final class LegacyRouteObject extends RouteObject {
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        private int legacyCaptureCalls;
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        private int legacyRestoreCalls;

        LegacyRouteObject() {
            super("LegacyRouteObject");
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            legacyCaptureCalls++;
            return super.captureRewindState();
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
            legacyRestoreCalls++;
            super.restoreRewindState(snapshot);
        }
    }

    private static final class ContextRouteObject extends RouteObject {
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        private int contextCaptureCalls;
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        private int contextRestoreCalls;
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        private RewindCaptureContext lastCaptureContext;
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        private RewindCaptureContext lastRestoreContext;

        ContextRouteObject() {
            super("ContextRouteObject");
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState(RewindCaptureContext context) {
            contextCaptureCalls++;
            lastCaptureContext = context;
            return super.captureRewindState(context);
        }

        @Override
        public void restoreRewindState(
                PerObjectRewindSnapshot snapshot,
                RewindCaptureContext context) {
            contextRestoreCalls++;
            lastRestoreContext = context;
            super.restoreRewindState(snapshot, context);
        }
    }

    private static final class CacheIdentityRouteObject extends RouteObject {
        CacheIdentityRouteObject() {
            super("CacheIdentityRouteObject");
        }
    }

    private abstract static class InheritedLegacyRouteParent extends RouteObject {
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        protected int legacyCaptureCalls;
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        protected int legacyRestoreCalls;

        InheritedLegacyRouteParent(String name) {
            super(name);
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            legacyCaptureCalls++;
            return super.captureRewindState();
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
            legacyRestoreCalls++;
            super.restoreRewindState(snapshot);
        }
    }

    private static final class InheritedLegacyRouteObject
            extends InheritedLegacyRouteParent {
        InheritedLegacyRouteObject() {
            super("InheritedLegacyRouteObject");
        }
    }

    private static final class MixedRouteObject extends RouteObject {
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        private int legacyCaptureCalls;
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        private int contextRestoreCalls;
        @com.openggf.game.rewind.RewindDeferred(reason = "dispatch test observation only")
        private RewindCaptureContext lastRestoreContext;

        MixedRouteObject() {
            super("MixedRouteObject");
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            legacyCaptureCalls++;
            return super.captureRewindState();
        }

        @Override
        public void restoreRewindState(
                PerObjectRewindSnapshot snapshot,
                RewindCaptureContext context) {
            contextRestoreCalls++;
            lastRestoreContext = context;
            super.restoreRewindState(snapshot, context);
        }
    }

    private static final class BenchmarkLegacyRouteObject extends RouteObject {
        BenchmarkLegacyRouteObject() {
            super("BenchmarkLegacyRouteObject");
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            return BENCHMARK_SNAPSHOT;
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
            // Dispatch cost only; semantic restore behavior is covered above.
        }
    }

    private static final class BenchmarkContextRouteObject extends RouteObject {
        BenchmarkContextRouteObject() {
            super("BenchmarkContextRouteObject");
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState(RewindCaptureContext context) {
            return BENCHMARK_SNAPSHOT;
        }

        @Override
        public void restoreRewindState(
                PerObjectRewindSnapshot snapshot,
                RewindCaptureContext context) {
            // Dispatch cost only; semantic restore behavior is covered above.
        }
    }

    private static final class BenchmarkLegacyCaptureContextRestoreObject extends RouteObject {
        BenchmarkLegacyCaptureContextRestoreObject() {
            super("BenchmarkLegacyCaptureContextRestoreObject");
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            return BENCHMARK_SNAPSHOT;
        }

        @Override
        public void restoreRewindState(
                PerObjectRewindSnapshot snapshot,
                RewindCaptureContext context) {
            // Dispatch cost only; semantic restore behavior is covered above.
        }
    }

    private static final class BenchmarkContextCaptureLegacyRestoreObject extends RouteObject {
        BenchmarkContextCaptureLegacyRestoreObject() {
            super("BenchmarkContextCaptureLegacyRestoreObject");
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState(RewindCaptureContext context) {
            return BENCHMARK_SNAPSHOT;
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
            // Dispatch cost only; semantic restore behavior is covered above.
        }
    }
}
