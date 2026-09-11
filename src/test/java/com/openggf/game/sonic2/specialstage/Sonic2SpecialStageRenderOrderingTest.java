package com.openggf.game.sonic2.specialstage;

import com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sonic2SpecialStageRenderOrderingTest {
    private static final Sonic2SpecialStageManager PLAYER_OWNER =
            new Sonic2SpecialStageManager(new Sonic2SpecialStageSpriteDebug(), null, null);
    private static volatile Object allocationSink;
    private static volatile Object legacyAllocationSink;

    @Test
    void objectOrderMatchesStableReferenceWithoutMutatingAuthoritativeList() {
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        List<Sonic2SpecialStageObject> authoritative = new ArrayList<>(List.of(
                new TestObject(9), new TestObject(20), new TestObject(20),
                new TestObject(3), new TestObject(9), new TestObject(20)));
        List<Sonic2SpecialStageObject> originalOrder = List.copyOf(authoritative);
        List<Sonic2SpecialStageObject> expected = new ArrayList<>(authoritative);
        expected.sort((left, right) -> Integer.compare(right.getDepth(), left.getDepth()));

        int count = renderer.prepareObjectRenderOrder(authoritative);

        assertEquals(expected.size(), count);
        for (int i = 0; i < count; i++) {
            assertSame(expected.get(i), renderer.objectRenderOrderAt(i));
        }
        assertEquals(originalOrder, authoritative,
                "sorting must not mutate authoritative activeObjects insertion order");
    }

    @Test
    void playerOrderFiltersEligibilityAndIsStableForSyntheticMultiplayerInput() {
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        TestPlayer p3a = new TestPlayer("p3a", 3, true, null);
        TestPlayer p1a = new TestPlayer("p1a", 1, true, null);
        TestPlayer hidden = new TestPlayer("hidden", 0, false, null);
        TestPlayer p2 = new TestPlayer("p2", 2, true, null);
        TestPlayer p1b = new TestPlayer("p1b", 1, true, null);
        TestPlayer p3b = new TestPlayer("p3b", 3, true, null);
        List<Sonic2SpecialStagePlayer> authoritative = new ArrayList<>(
                List.of(p3a, p1a, hidden, p2, p1b, p3b));
        renderer.setPlayers(authoritative);

        int count = renderer.preparePlayerRenderOrder();

        assertEquals(5, count);
        assertEquals(List.of(p1a, p1b, p2, p3a, p3b), playerOrder(renderer, count));
        assertEquals(List.of(p3a, p1a, hidden, p2, p1b, p3b), authoritative,
                "sorting must not mutate the authoritative player source order");
    }

    @Test
    void renderPassesReuseTheSameStableOrderForShadowsThenSprites() {
        List<String> playerEvents = new ArrayList<>();
        NoOpGraphicsManager graphics = new NoOpGraphicsManager();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        TestPlayer p2 = new TestPlayer("p2", 2, true, playerEvents);
        TestPlayer p1a = new TestPlayer("p1a", 1, true, playerEvents);
        TestPlayer p1b = new TestPlayer("p1b", 1, true, playerEvents);
        renderer.setPlayers(List.of(p2, p1a, p1b));
        renderer.setShadowPatternBases(1, 1, 1);

        renderer.renderPlayers();

        // A lower priority(a0) value wins ROM's Sprite_Table ordering (earlier
        // sprites occlude later same-tile-priority ones), so both passes draw the
        // highest priority bucket first and the lowest (p1a/p1b) last, on top.
        assertEquals(List.of(
                "shadow:p2", "shadow:p1a", "shadow:p1b",
                "player:p2", "player:p1a", "player:p1b"), playerEvents);

        List<String> objectEvents = new ArrayList<>();
        List<Sonic2SpecialStageObject> objects = List.of(
                new RecordingObject("near", 3, objectEvents),
                new RecordingObject("far-a", 9, objectEvents),
                new RecordingObject("far-b", 9, objectEvents));
        renderer.setObjectManager(objectManagerWith(objects));

        renderer.renderObjects();

        assertEquals(List.of(
                "far-a", "far-b", "near",
                "far-a", "far-b", "near"), objectEvents,
                "shadow and object passes must traverse the identical stable depth order");
    }

    @Test
    void warmedStableOrderingIsAllocationFree() {
        ThreadMXBean bean = allocationBeanOrSkip();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        List<Sonic2SpecialStageObject> objects = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            objects.add(new TestObject((i * 7) & 15));
        }
        List<Sonic2SpecialStagePlayer> players = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            players.add(new TestPlayer("p" + i, i & 3, true, null));
        }
        renderer.setPlayers(players);

        for (int i = 0; i < 20_000; i++) {
            legacyAllocationSink = legacyObjectOrder(objects);
            legacyAllocationSink = legacyPlayerOrder(players);
            allocationSink = renderer.objectRenderOrderAt(
                    renderer.prepareObjectRenderOrder(objects) - 1);
            allocationSink = renderer.playerRenderOrderAt(
                    renderer.preparePlayerRenderOrder() - 1);
        }

        long[] legacySamples = new long[3];
        long[] optimizedSamples = new long[3];
        for (int repetition = 0; repetition < legacySamples.length; repetition++) {
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int i = 0; i < 100_000; i++) {
                legacyAllocationSink = legacyObjectOrder(objects);
                legacyAllocationSink = legacyPlayerOrder(players);
            }
            legacySamples[repetition] = (bean.getThreadAllocatedBytes(
                    Thread.currentThread().threadId()) - before) / 100_000L;

            before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int i = 0; i < 100_000; i++) {
                allocationSink = renderer.objectRenderOrderAt(
                        renderer.prepareObjectRenderOrder(objects) - 1);
                allocationSink = renderer.playerRenderOrderAt(
                        renderer.preparePlayerRenderOrder() - 1);
            }
            optimizedSamples[repetition] = (bean.getThreadAllocatedBytes(
                    Thread.currentThread().threadId()) - before) / 100_000L;
        }
        Arrays.sort(legacySamples);
        Arrays.sort(optimizedSamples);
        System.out.printf("s2ssStableRenderOrdering legacyAllocatedBytes=%d optimizedAllocatedBytes=%d%n",
                legacySamples[1], optimizedSamples[1]);
        assertTrue(legacySamples[1] > 0L,
                "the escaping legacy ArrayList+stable-sort control must allocate");
        assertEquals(0L, optimizedSamples[1],
                "warmed object+player ordering must be allocation-free");
    }

    @Test
    void replacingSourcesClearsLogicalOrderAndStaleReferences() throws Exception {
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        TestPlayer oldPlayer = new TestPlayer("old", 1, true, null);
        renderer.setPlayers(List.of(oldPlayer));
        renderer.preparePlayerRenderOrder();

        renderer.setPlayers(List.of());

        assertEquals(0, renderer.preparePlayerRenderOrder());
        assertThrows(IndexOutOfBoundsException.class, () -> renderer.playerRenderOrderAt(0));
        assertFalseArrayRetains(renderer, "playerOrderPrimary", oldPlayer);
        assertFalseArrayRetains(renderer, "playerOrderScratch", oldPlayer);

        TestObject oldObject = new TestObject(7);
        renderer.prepareObjectRenderOrder(List.of(oldObject));
        renderer.setObjectManager(objectManagerWith(List.of()));
        renderer.renderObjects();

        assertThrows(IndexOutOfBoundsException.class, () -> renderer.objectRenderOrderAt(0));
        assertFalseArrayRetains(renderer, "objectOrderPrimary", oldObject);
        assertFalseArrayRetains(renderer, "objectOrderScratch", oldObject);
    }

    @Test
    void managerResetDetachesRendererBeforeClearingAuthoritativeSources() throws Exception {
        Sonic2SpecialStageManager manager =
                new Sonic2SpecialStageManager(new Sonic2SpecialStageSpriteDebug(), null, null);
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        TestPlayer stalePlayer = new TestPlayer("stale", 1, true, null);
        TestObject staleObject = new TestObject(8);
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objects =
                objectManagerWith(List.of(staleObject));
        manager.getPlayers().add(stalePlayer);
        setField(manager, "renderer", renderer);
        setField(manager, "objectManager", objects);
        renderer.setPlayers(manager.getPlayers());
        renderer.setObjectManager(objects);
        renderer.preparePlayerRenderOrder();
        renderer.prepareObjectRenderOrder(objects.getActiveObjects());

        manager.reset();

        assertFalseArrayRetains(renderer, "playerOrderPrimary", stalePlayer);
        assertFalseArrayRetains(renderer, "playerOrderScratch", stalePlayer);
        assertFalseArrayRetains(renderer, "objectOrderPrimary", staleObject);
        assertFalseArrayRetains(renderer, "objectOrderScratch", staleObject);
        assertTrue(getField(renderer, "players") != manager.getPlayers(),
                "reset must detach the renderer from the authoritative player list");
        assertEquals(null, getField(renderer, "objectManager"));
    }

    @Test
    void placeholderWithOnlyIneligiblePlayersStillBeginsAndFlushesBatch() {
        BatchRecordingGraphicsManager graphics = new BatchRecordingGraphicsManager();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        renderer.setPlayers(List.of(new TestPlayer("hidden", 1, false, null)));

        renderer.renderPlaceholderPlayers();

        assertEquals(1, graphics.patternBegins);
        assertEquals(1, graphics.patternFlushes);
    }

    @Test
    void failedEligibilityPreparationClearsAllPlayerScratchReferences() throws Exception {
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        TestPlayer previous = new TestPlayer("previous", 1, true, null);
        renderer.setPlayers(List.of(previous));
        renderer.preparePlayerRenderOrder();
        TestPlayer written = new TestPlayer("written", 2, true, null);
        TestPlayer throwing = new ThrowingEligibilityPlayer();
        renderer.setPlayers(List.of(written, throwing));

        assertThrows(IllegalStateException.class, renderer::preparePlayerRenderOrder);

        assertThrows(IndexOutOfBoundsException.class, () -> renderer.playerRenderOrderAt(0));
        assertNoPlayerScratchRetains(renderer, previous, written, throwing);
    }

    @Test
    void failedPriorityPreparationClearsAllPlayerScratchReferences() throws Exception {
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        TestPlayer normal = new TestPlayer("normal", 1, true, null);
        TestPlayer throwing = new ThrowingPriorityPlayer();
        renderer.setPlayers(List.of(normal, throwing));

        assertThrows(IllegalStateException.class, renderer::preparePlayerRenderOrder);

        assertThrows(IndexOutOfBoundsException.class, () -> renderer.playerRenderOrderAt(0));
        assertNoPlayerScratchRetains(renderer, normal, throwing);
    }

    @Test
    void failedDepthPreparationClearsAllObjectScratchReferences() throws Exception {
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        TestObject normal = new TestObject(1);
        TestObject throwing = new ThrowingDepthObject();

        assertThrows(IllegalStateException.class,
                () -> renderer.prepareObjectRenderOrder(List.of(normal, throwing)));

        assertThrows(IndexOutOfBoundsException.class, () -> renderer.objectRenderOrderAt(0));
        assertFalseArrayRetains(renderer, "objectOrderPrimary", normal);
        assertFalseArrayRetains(renderer, "objectOrderPrimary", throwing);
        assertFalseArrayRetains(renderer, "objectOrderScratch", normal);
        assertFalseArrayRetains(renderer, "objectOrderScratch", throwing);
    }

    private static List<Sonic2SpecialStagePlayer> playerOrder(
            Sonic2SpecialStageRenderer renderer, int count) {
        List<Sonic2SpecialStagePlayer> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(renderer.playerRenderOrderAt(i));
        }
        return result;
    }

    private static void assertFalseArrayRetains(
            Sonic2SpecialStageRenderer renderer, String fieldName, Object stale) throws Exception {
        var field = Sonic2SpecialStageRenderer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object[] values = (Object[]) field.get(renderer);
        assertTrue(Arrays.stream(values).noneMatch(value -> value == stale),
                fieldName + " must not retain a source that was replaced");
    }

    private static void assertNoPlayerScratchRetains(
            Sonic2SpecialStageRenderer renderer, Object... stale) throws Exception {
        for (Object value : stale) {
            assertFalseArrayRetains(renderer, "playerOrderPrimary", value);
            assertFalseArrayRetains(renderer, "playerOrderScratch", value);
        }
    }

    private static List<Sonic2SpecialStageObject> legacyObjectOrder(
            List<Sonic2SpecialStageObject> objects) {
        List<Sonic2SpecialStageObject> ordered = new ArrayList<>(objects);
        ordered.sort((left, right) -> Integer.compare(right.getDepth(), left.getDepth()));
        return ordered;
    }

    private static List<Sonic2SpecialStagePlayer> legacyPlayerOrder(
            List<Sonic2SpecialStagePlayer> players) {
        List<Sonic2SpecialStagePlayer> ordered = new ArrayList<>();
        for (Sonic2SpecialStagePlayer player : players) {
            if (player.isSpawned() && player.getRoutine() != Sonic2SpecialStagePlayer.RoutineState.INIT) {
                ordered.add(player);
            }
        }
        ordered.sort(Comparator.comparingInt(Sonic2SpecialStagePlayer::getPriority));
        return ordered;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManagerWith(
            List<Sonic2SpecialStageObject> objects) {
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager manager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        manager.getActiveObjects().addAll(objects);
        return manager;
    }

    private static ThreadMXBean allocationBeanOrSkip() {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(raw instanceof ThreadMXBean,
                "ThreadMXBean allocation accounting unavailable");
        ThreadMXBean bean = (ThreadMXBean) raw;
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "thread allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        Assumptions.assumeTrue(bean.isThreadAllocatedMemoryEnabled(),
                "thread allocation accounting could not be enabled");
        Assumptions.assumeTrue(bean.getThreadAllocatedBytes(
                Thread.currentThread().threadId()) >= 0L);
        return bean;
    }

    private static class TestObject extends Sonic2SpecialStageEmerald {
        private final int testDepth;

        TestObject(int depth) {
            this.testDepth = depth;
            initialize(depth, 0);
        }

        @Override public int getDepth() { return testDepth; }
    }

    private static final class RecordingObject extends TestObject {
        private final String id;
        private final List<String> events;

        RecordingObject(String id, int depth, List<String> events) {
            super(depth);
            this.id = id;
            this.events = events;
        }

        @Override
        public boolean isOnScreen() {
            events.add(id);
            return false;
        }
    }

    private static class TestPlayer extends Sonic2SpecialStagePlayer {
        private final String id;
        private final int testPriority;
        private final boolean eligible;
        private final List<String> events;

        TestPlayer(String id, int priority, boolean eligible, List<String> events) {
            super(PlayerType.SONIC, true, PLAYER_OWNER);
            this.id = id;
            this.testPriority = priority;
            this.eligible = eligible;
            this.events = events;
        }

        @Override public int getPriority() { return testPriority; }
        @Override public boolean isSpawned() { return eligible; }
        // Keep the allocation probe on this fixture's plain getters. A sibling
        // test inline-mocks the production player class, leaving its concrete
        // methods Mockito-instrumented for the remainder of a reused test JVM.
        @Override boolean isMainCharacter() { return true; }
        @Override public RoutineState getRoutine() {
            return eligible ? RoutineState.NORMAL : RoutineState.INIT;
        }
        @Override public int getAngle() {
            if (events != null) events.add("shadow:" + id);
            return 0;
        }
        @Override public int getMappingFrame() {
            if (events != null) events.add("player:" + id);
            return 0;
        }
    }

    private static final class ThrowingEligibilityPlayer extends TestPlayer {
        ThrowingEligibilityPlayer() {
            super("throw-eligibility", 1, true, null);
        }

        @Override public boolean isSpawned() {
            throw new IllegalStateException("eligibility");
        }
    }

    private static final class ThrowingPriorityPlayer extends TestPlayer {
        ThrowingPriorityPlayer() {
            super("throw-priority", 1, true, null);
        }

        @Override public int getPriority() {
            throw new IllegalStateException("priority");
        }
    }

    private static final class ThrowingDepthObject extends TestObject {
        ThrowingDepthObject() {
            super(1);
        }

        @Override public int getDepth() {
            throw new IllegalStateException("depth");
        }
    }

    private static class NoOpGraphicsManager extends GraphicsManager {
        @Override public void beginShadowBatch() { }
        @Override public void flushShadowBatch() { }
        @Override public void beginPatternBatch() { }
        @Override public void flushPatternBatch() { }
        @Override public void addShadowPattern(int id, PatternDesc desc, int x, int y) { }
        @Override public void renderPatternWithId(int id, PatternDesc desc, int x, int y) { }
    }

    private static final class BatchRecordingGraphicsManager extends NoOpGraphicsManager {
        private int patternBegins;
        private int patternFlushes;

        @Override public void beginPatternBatch() { patternBegins++; }
        @Override public void flushPatternBatch() { patternFlushes++; }
    }
}
