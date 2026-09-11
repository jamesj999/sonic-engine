package com.openggf.game.rewind;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindObjectStateBlob;
import com.openggf.game.rewind.schema.RewindSchemaRegistry;
import com.openggf.game.rewind.snapshot.GenericObjectSnapshot;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindCaptureMemoizationEquivalence {

    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    enum Phase { IDLE, ACTIVE, DONE }

    record Inner(int amount, String label) {}

    record Outer(int count, Inner inner, Phase phase) {}

    public static final class MemoizedHolder {
        public int alpha;
        public long beta;
        public boolean gamma;
        public String name;
        public Phase phase;
        public int[] samples;
        public double[] weights;
        public Outer outer;
    }

    public static final class GlowState {
        public int level;
        public boolean active;
    }

    public static final class CodecCollectionHolder {
        public int alpha;
        public Outer outer;
        public final List<Integer> bag = new ArrayList<>();
        public final List<GlowState> glows = new ArrayList<>();
    }

    private static GlowState glow(int level, boolean active) {
        GlowState state = new GlowState();
        state.level = level;
        state.active = active;
        return state;
    }

    private static MemoizedHolder populatedHolder() {
        MemoizedHolder holder = new MemoizedHolder();
        holder.alpha = 0x11223344;
        holder.beta = 0x0123456789ABCDEFL;
        holder.gamma = true;
        holder.name = "memoized";
        holder.phase = Phase.ACTIVE;
        holder.samples = new int[] {1, -2, 3, Integer.MIN_VALUE, Integer.MAX_VALUE};
        holder.weights = new double[] {0.5, -1.25, Double.MAX_VALUE};
        holder.outer = new Outer(7, new Inner(42, "inner"), Phase.DONE);
        return holder;
    }

    @Test
    void repeatedGenericCapturesAreEqualAcrossCacheWarmup() {
        MemoizedHolder holder = populatedHolder();

        GenericObjectSnapshot first = GenericFieldCapturer.capture(holder);
        GenericObjectSnapshot second = GenericFieldCapturer.capture(holder);
        GenericObjectSnapshot third = GenericFieldCapturer.capture(holder);

        assertEquals(first, second, "cache-miss capture must equal first cache-hit capture");
        assertEquals(second, third, "consecutive cache-hit captures must be equal");
    }

    @Test
    void repeatedCompactCapturesProduceIdenticalBytes() {
        CodecCollectionHolder holder = new CodecCollectionHolder();
        holder.alpha = 0x55AA55AA;
        holder.outer = new Outer(3, new Inner(9, "compact"), Phase.IDLE);
        holder.bag.add(10);
        holder.bag.add(20);
        holder.bag.add(30);
        holder.glows.add(glow(3, true));
        holder.glows.add(glow(-8, false));
        assertTrue(RewindSchemaRegistry.schemaFor(CodecCollectionHolder.class).unsupportedFields().isEmpty(),
                "holder must be fully supported by the compact capturer for this test to be meaningful");

        RewindObjectStateBlob first = CompactFieldCapturer.capture(holder);
        RewindObjectStateBlob second = CompactFieldCapturer.capture(holder);
        RewindObjectStateBlob third = CompactFieldCapturer.capture(holder);

        assertArrayEquals(first.scalarData(), second.scalarData(),
                "cache-miss capture bytes must equal first cache-hit capture bytes");
        assertArrayEquals(second.scalarData(), third.scalarData());
        assertEquals(first.opaqueValues().length, second.opaqueValues().length);
    }

    @Test
    void warmCacheRestoreRoundTripsCodecCollectionAndRecordFields() {
        CodecCollectionHolder holder = new CodecCollectionHolder();
        holder.alpha = 77;
        holder.outer = new Outer(5, new Inner(6, "roundtrip"), Phase.ACTIVE);
        holder.bag.add(1);
        holder.bag.add(2);
        holder.glows.add(glow(11, true));
        holder.glows.add(glow(22, false));

        GenericObjectSnapshot coldSnapshot = GenericFieldCapturer.capture(holder);
        GenericObjectSnapshot warmSnapshot = GenericFieldCapturer.capture(holder);

        CodecCollectionHolder restoredCold = new CodecCollectionHolder();
        GenericFieldCapturer.restore(restoredCold, coldSnapshot);
        CodecCollectionHolder restoredWarm = new CodecCollectionHolder();
        GenericFieldCapturer.restore(restoredWarm, warmSnapshot);

        for (CodecCollectionHolder restored : List.of(restoredCold, restoredWarm)) {
            assertEquals(holder.alpha, restored.alpha);
            assertEquals(holder.outer, restored.outer);
            assertEquals(holder.bag, restored.bag);
            assertEquals(holder.glows.size(), restored.glows.size());
            for (int i = 0; i < holder.glows.size(); i++) {
                assertEquals(holder.glows.get(i).level, restored.glows.get(i).level);
                assertEquals(holder.glows.get(i).active, restored.glows.get(i).active);
            }
        }
    }

    @Test
    void defaultObjectSubclassCompactCaptureIsByteIdenticalAcrossCaptures() {
        DefaultStateObject object = new DefaultStateObject();
        object.phase = 4;
        object.cooldown = 0x7FFF;
        object.armed = true;

        PerObjectRewindSnapshot first = object.captureRewindState();
        PerObjectRewindSnapshot second = object.captureRewindState();

        assertNotNull(first.compactGenericState(), "default object subclass must take the compact path");
        assertNotNull(second.compactGenericState());
        assertArrayEquals(first.compactGenericState().scalarData(), second.compactGenericState().scalarData(),
                "cache-miss and cache-hit captures must produce byte-identical compact blobs");
    }

    @Test
    void defaultBadnikSubclassCompactCaptureIsByteIdenticalAcrossCaptures() {
        DefaultStateBadnik badnik = new DefaultStateBadnik();
        badnik.aiTimer = 33;
        badnik.charging = true;

        PerObjectRewindSnapshot first = badnik.captureRewindState();
        PerObjectRewindSnapshot second = badnik.captureRewindState();

        assertNotNull(first.compactGenericState(),
                "default badnik subclass must preserve a compact subclass sidecar alongside badnikExtra");
        assertNotNull(second.compactGenericState());
        assertArrayEquals(first.compactGenericState().scalarData(), second.compactGenericState().scalarData(),
                "cache-miss and cache-hit captures must produce byte-identical compact blobs");
        assertEquals(first.badnikExtra(), second.badnikExtra());
    }

    private static final class DefaultStateObject extends AbstractObjectInstance {
        private int phase;
        private int cooldown;
        private boolean armed;

        DefaultStateObject() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "DefaultStateObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class DefaultStateBadnik extends AbstractBadnikInstance {
        private int aiTimer;
        private boolean charging;

        DefaultStateBadnik() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "DefaultStateBadnik");
        }

        @Override
        protected void updateMovement(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        protected int getCollisionSizeIndex() {
            return 0;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
