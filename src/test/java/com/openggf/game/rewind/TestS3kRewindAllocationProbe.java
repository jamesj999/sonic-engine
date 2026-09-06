package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.SharedLevel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Opt-in measurement probe, not a regression test: attributes rewind capture and
 * restore allocation per registered adapter in S3K AIZ1, before and after 1800
 * right-walking frames. Run with {@code -Dopenggf.rewind.alloc.measure=true};
 * {@code -Dprobe.skipIntros=false} keeps the intro so the hardware-timing ledger
 * grows the way live play does; {@code -Dprobe.out=} names the report file.
 */
@EnabledIfSystemProperty(named = "openggf.rewind.alloc.measure", matches = "true")
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kRewindAllocationProbe {
    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, Boolean.parseBoolean(System.getProperty("probe.skipIntros", "true")));
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros != null ? oldSkipIntros : false);
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private static void dumpDetails(CompositeSnapshot cs, StringBuilder out) {
        Object hw = cs.get("hardware-timing");
        if (hw instanceof com.openggf.game.timing.HardwareTimingSnapshot h) {
            out.append("hardware-timing jobs=").append(h.jobs().size()).append('\n');
            for (var j : h.jobs()) {
                int payload = j.preparedPayload() == null ? -1 : j.preparedPayload().length;
                String prep = j.preparationSnapshot().getClass().getSimpleName();
                int prepBytes = -1;
                if (j.preparationSnapshot() instanceof com.openggf.game.sonic3k.resources.S3kKosDecompressionSnapshot k) prepBytes = k.compressedBytes().length;
                if (j.preparationSnapshot() instanceof com.openggf.game.sonic3k.resources.S3kKosModuleSnapshot m) prepBytes = m.archive().length + m.output().length;
                out.append(String.format("  job %s#%d payload=%d prep=%s(%d) ready=%b claimed=%b retired=%b%n",
                        j.kind(), j.handle().ordinal(), payload, prep, prepBytes, j.ready(), j.claimed(), j.physicallyRetired()));
            }
        }
        Object om = cs.get("object-manager");
        if (om instanceof com.openggf.game.rewind.snapshot.ObjectManagerSnapshot o) {
            out.append("object-manager: ").append("perSlot=" + o.slots().size() + " dynamic=" + o.dynamicObjects().size()).append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void measure() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        for (int i = 0; i < 300; i++) fixture.stepFrame(false, false, false, true, false);
        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        Field f = RewindRegistry.class.getDeclaredField("entries");
        f.setAccessible(true);
        Map<String, RewindSnapshottable<?>> entries = (Map<String, RewindSnapshottable<?>>) f.get(registry);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        // warm-up
        for (int i = 0; i < 30; i++) registry.restore(registry.capture());

        StringBuilder out = new StringBuilder();
        long totalCap = Long.MAX_VALUE, totalRes = Long.MAX_VALUE;
        CompositeSnapshot full = registry.capture();
        for (int i = 0; i < 20; i++) {
            long a = mx.getCurrentThreadAllocatedBytes();
            CompositeSnapshot s = registry.capture();
            long b = mx.getCurrentThreadAllocatedBytes();
            registry.restore(s);
            long c = mx.getCurrentThreadAllocatedBytes();
            totalCap = Math.min(totalCap, b - a);
            totalRes = Math.min(totalRes, c - b);
        }
        out.append(String.format("TOTAL capture=%d restore=%d adapters=%d%n", totalCap, totalRes, entries.size()));

        List<String[]> rows = new ArrayList<>();
        for (var e : entries.entrySet()) {
            RewindSnapshottable raw = e.getValue();
            long cap = Long.MAX_VALUE, res = Long.MAX_VALUE;
            Object snap = raw.capture();
            String type = snap.getClass().getSimpleName();
            for (int i = 0; i < 20; i++) {
                long a = mx.getCurrentThreadAllocatedBytes();
                Object s = raw.capture();
                long b = mx.getCurrentThreadAllocatedBytes();
                raw.restore(s);
                long c = mx.getCurrentThreadAllocatedBytes();
                cap = Math.min(cap, b - a);
                res = Math.min(res, c - b);
            }
            rows.add(new String[]{e.getKey(), raw.getClass().getSimpleName(), type, Long.toString(cap), Long.toString(res)});
        }
        rows.sort((x, y) -> Long.compare(Long.parseLong(y[3]), Long.parseLong(x[3])));
        out.append(String.format("%-28s %-40s %-32s %10s %10s%n", "key", "adapter", "snapshot", "capB", "restB"));
        for (String[] r : rows) out.append(String.format("%-28s %-40s %-32s %10s %10s%n", r[0], r[1], r[2], r[3], r[4]));
        registry.restore(full);
        dumpDetails(full, out);
        for (int i = 0; i < 1500; i++) fixture.stepFrame(false, false, false, true, false);
        CompositeSnapshot late = registry.capture();
        long lc = Long.MAX_VALUE, lr = Long.MAX_VALUE;
        for (int i = 0; i < 20; i++) {
            long a = mx.getCurrentThreadAllocatedBytes();
            CompositeSnapshot s = registry.capture();
            long b = mx.getCurrentThreadAllocatedBytes();
            registry.restore(s);
            long c = mx.getCurrentThreadAllocatedBytes();
            lc = Math.min(lc, b - a); lr = Math.min(lr, c - b);
        }
        out.append(String.format("%nAFTER 1800 FRAMES: TOTAL capture=%d restore=%d%n", lc, lr));
        for (var e : entries.entrySet()) {
            RewindSnapshottable raw = e.getValue();
            long cap = Long.MAX_VALUE;
            for (int i = 0; i < 10; i++) {
                long a = mx.getCurrentThreadAllocatedBytes();
                Object s = raw.capture();
                long b = mx.getCurrentThreadAllocatedBytes();
                cap = Math.min(cap, b - a);
            }
            if (cap > 1000) out.append(String.format("  %-28s capB=%d%n", e.getKey(), cap));
        }
        dumpDetails(late, out);
        System.out.println(out);
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("probe.out", "target/rewind-alloc-probe.txt")), out.toString());
    }
}
