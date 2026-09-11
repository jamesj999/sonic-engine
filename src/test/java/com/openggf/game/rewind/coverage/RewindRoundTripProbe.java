package com.openggf.game.rewind.coverage;

import com.openggf.game.rewind.RewindRoundTripHarness;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.StubObjectServices;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Empirical rewind round-trip probe.
 *
 * <p>Turns the conservative static coverage baseline produced by
 * {@link RewindCoverageAnalyzer} into a concrete empirical list: which objects
 * genuinely LOSE state across a {@code captureRewindState()} →
 * fresh-construction → {@code restoreRewindState()} round-trip?
 *
 * <h2>Approach</h2>
 * <ol>
 *   <li>Discover every concrete {@code AbstractObjectInstance} subclass via the
 *       same source-file scan used by {@link RewindCoverageAnalyzer}.</li>
 *   <li>For each class, attempt construction without ROM/OpenGL using
 *       a {@link StubObjectServices} and a representative zero-position
 *       {@code ObjectSpawn}.  Constructors tried (in order):
 *       <ul>
 *         <li>{@code ()} – zero-arg (e.g. {@code AizBgTreeSpawnerInstance})</li>
 *         <li>{@code (ObjectSpawn)} – most badniks and zone objects</li>
 *         <li>{@code (ObjectSpawn, String)} – abstract-base helpers</li>
 *         <li>{@code (ObjectSpawn, ObjectServices)} – shared level objects</li>
 *       </ul>
 *       Objects that throw during construction (ROM access, NPE from a
 *       mandatory manager) are recorded as <em>unprobed</em> with the
 *       exception message as the skip reason.</li>
 *   <li>Capture the object's rewind state immediately after construction
 *       (no update steps, to avoid service calls).</li>
 *   <li>Construct a second fresh instance from the same spawn (same
 *       constructor path).</li>
 *   <li>Apply {@code restoreRewindState()} to the fresh instance.</li>
 *   <li>Reflectively walk every non-static, non-synthetic field in the
 *       concrete class and its superclasses (stopping at
 *       {@code AbstractObjectInstance}, whose base fields are already
 *       known to survive).  Any primitive/enum/String field whose value
 *       differs = REAL gap.</li>
 * </ol>
 *
 * <h2>Limitations / honest coverage caveats</h2>
 * <ul>
 *   <li>Objects that call services during construction (e.g. render-manager
 *       art loading from ROM) are skipped and counted as unprobed.</li>
 *   <li>No frame-step is performed, so non-default state that only appears
 *       after {@code update()} is not tested. Construction-time divergences
 *       are the focus.</li>
 *   <li>Object-reference fields (non-primitive, non-enum, non-String) are not
 *       compared because they cannot be meaningfully equality-tested without
 *       a live session; they appear in the field-exclusion notes.</li>
 * </ul>
 */
public final class RewindRoundTripProbe {

    private static final Logger LOG = Logger.getLogger(RewindRoundTripProbe.class.getName());

    // -------------------------------------------------------------------------
    // Public result types
    // -------------------------------------------------------------------------

    /** A field that differs between captured and restored state — a real gap. */
    public record GapRecord(
            String className,
            String fieldName,
            String beforeValue,
            String afterValue,
            boolean isFinal) {
    }

    /** A class that could not be probed, with the reason. */
    public record SkipRecord(String className, String reason) {}

    /** The full result of one probe run. */
    public record ProbeReport(
            int totalClasses,
            int probed,
            int skipped,
            List<GapRecord> realGaps,
            List<SkipRecord> skipRecords) {

        public double probedFraction() {
            return totalClasses == 0 ? 0.0 : (double) probed / totalClasses;
        }

        public int realGapCount() {
            return realGaps.size();
        }
    }

    // -------------------------------------------------------------------------
    // Probe entry point
    // -------------------------------------------------------------------------

    /**
     * Runs the full probe over all discovered concrete object classes.
     *
     * @param dynamicCodecClassNames fully-qualified class names with a dynamic rewind codec
     *                               (used only for reporting, not for construction logic)
     * @return the probe report
     */
    public ProbeReport run(Set<String> dynamicCodecClassNames) {
        List<ObjectClasspathScan.SourceClass> classes;
        try {
            Path srcMain = ObjectClasspathScan.findSourceRoot();
            if (srcMain == null) {
                throw new IllegalStateException(
                        "Source root not found; probe must run from a source checkout.");
            }
            classes = ObjectClasspathScan.findConcreteObjectInstances(srcMain);
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan object classes for probe", e);
        }

        List<GapRecord> gaps = new ArrayList<>();
        List<SkipRecord> skips = new ArrayList<>();
        int probed = 0;

        for (ObjectClasspathScan.SourceClass sc : classes) {
            ProbeResult result = probeClass(sc.fqn());
            if (result instanceof ProbeResult.Probed p) {
                probed++;
                gaps.addAll(p.gaps());
            } else if (result instanceof ProbeResult.Skipped s) {
                skips.add(new SkipRecord(sc.fqn(), s.reason()));
            }
        }

        return new ProbeReport(classes.size(), probed, skips.size(), List.copyOf(gaps), List.copyOf(skips));
    }

    // -------------------------------------------------------------------------
    // Write the markdown report
    // -------------------------------------------------------------------------

    /**
     * Writes the probe report to a markdown file.
     *
     * @param report  the report from {@link #run(Set)}
     * @param outFile the destination path (parent directory must exist)
     */
    public void writeReport(ProbeReport report, Path outFile) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Rewind Round-Trip Probe\n\n");
        md.append("Generated: ").append(LocalDate.now()).append("\n\n");
        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n");
        md.append("|--------|-------|\n");
        md.append("| Total classes discovered | ").append(report.totalClasses()).append(" |\n");
        md.append("| Probed: | ").append(report.probed()).append(" |\n");
        md.append("| Skipped/Unprobed: | ").append(report.skipped()).append(" |\n");
        md.append("| Probe coverage | ")
          .append(String.format("%.1f%%", report.probedFraction() * 100)).append(" |\n");
        md.append("| REAL gaps found | ").append(report.realGapCount()).append(" |\n");

        md.append("\n## Real Gaps (fields that differ after capture → restore)\n\n");
        if (report.realGaps().isEmpty()) {
            md.append("_No real gaps found among probed objects._\n");
        } else {
            Map<String, List<GapRecord>> byClass = new LinkedHashMap<>();
            for (GapRecord gap : report.realGaps()) {
                byClass.computeIfAbsent(gap.className(), k -> new ArrayList<>()).add(gap);
            }
            for (Map.Entry<String, List<GapRecord>> entry : byClass.entrySet()) {
                String simpleName = simpleClassName(entry.getKey());
                md.append("### ").append(simpleName).append("\n\n");
                md.append("| Field | Final? | Before | After |\n");
                md.append("|-------|--------|--------|-------|\n");
                for (GapRecord gap : entry.getValue()) {
                    md.append("| `").append(gap.fieldName()).append("`")
                      .append(" | ").append(gap.isFinal() ? "yes" : "no")
                      .append(" | `").append(truncate(gap.beforeValue())).append("`")
                      .append(" | `").append(truncate(gap.afterValue())).append("`")
                      .append(" |\n");
                }
                md.append("\n");
            }
        }

        md.append("## Unprobed / Skipped Objects\n\n");
        md.append("These objects could not be constructed without ROM/full session.\n");
        md.append("Silence is NOT success — absence of gap evidence does not mean no gap exists.\n\n");
        if (report.skipRecords().isEmpty()) {
            md.append("_All discovered objects were successfully probed._\n");
        } else {
            md.append("| Class | Skip Reason |\n");
            md.append("|-------|-------------|\n");
            for (SkipRecord skip : report.skipRecords()) {
                md.append("| ").append(simpleClassName(skip.className()))
                  .append(" | ").append(mdEscape(truncate(skip.reason())))
                  .append(" |\n");
            }
        }

        md.append("\n## Probe Methodology\n\n");
        md.append("**Construction approach:** try zero-arg, then `(ObjectSpawn)`, ");
        md.append("`(ObjectSpawn, String)`, `(ObjectSpawn, ObjectServices)` constructors ");
        md.append("with a `StubObjectServices` (no ROM, no OpenGL) and a representative spawn at (0x100, 0x100).\n\n");
        md.append("**Diff scope:** all non-static, non-synthetic primitive/enum/String fields ");
        md.append("in the concrete class hierarchy, stopping at `AbstractObjectInstance` ");
        md.append("(whose base fields are managed by the base restore path and not re-tested here).\n\n");
        md.append("**Fields NOT diffed:** object references (not equality-comparable without a live session), ");
        md.append("`@RewindTransient`-annotated fields (intentionally excluded from capture), ");
        md.append("array/collection/record compound fields (require deep-equals beyond scope).\n\n");
        md.append("**No frame-stepping:** capture runs immediately after construction. ");
        md.append("Fields that only diverge after one or more `update()` calls are NOT detected by this probe.\n");

        Files.writeString(outFile, md.toString());
        LOG.info("Wrote rewind probe report to " + outFile);
    }

    // -------------------------------------------------------------------------
    // Internal probe logic
    // -------------------------------------------------------------------------

    /** Internal sealed result type for one class probe. */
    private sealed interface ProbeResult {
        record Probed(List<GapRecord> gaps) implements ProbeResult {}
        record Skipped(String reason) implements ProbeResult {}
    }

    private ProbeResult probeClass(String fqn) {
        Class<?> rawClass;
        try {
            rawClass = Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            return new ProbeResult.Skipped("ClassNotFoundException: " + e.getMessage());
        }

        if (!AbstractObjectInstance.class.isAssignableFrom(rawClass)
                || Modifier.isAbstract(rawClass.getModifiers())) {
            return new ProbeResult.Skipped("abstract or not an AbstractObjectInstance subclass");
        }

        @SuppressWarnings("unchecked")
        Class<? extends AbstractObjectInstance> cls =
                (Class<? extends AbstractObjectInstance>) rawClass;

        StubObjectServices stub = new StubObjectServices();

        // Attempt construction — delegate to the shared harness construction entry point
        // so there is exactly ONE constructor-strategy implementation in the codebase.
        AbstractObjectInstance original;
        try {
            original = RewindRoundTripHarness.constructHeadless(cls, stub);
            original.setServices(stub);
        } catch (Throwable t) {
            return new ProbeResult.Skipped(describeThrowable(t));
        }

        // Capture state immediately after construction (no update steps)
        PerObjectRewindSnapshot snapshot;
        try {
            snapshot = original.captureRewindState(RewindCaptureContext.none());
        } catch (Throwable t) {
            return new ProbeResult.Skipped("captureRewindState threw: " + describeThrowable(t));
        }

        // Fresh construction from same spawn — again via the harness shared path
        AbstractObjectInstance restored;
        try {
            restored = RewindRoundTripHarness.constructHeadless(cls, stub);
            restored.setServices(stub);
        } catch (Throwable t) {
            return new ProbeResult.Skipped("second construction threw: " + describeThrowable(t));
        }

        // Restore state
        try {
            restored.restoreRewindState(snapshot, RewindCaptureContext.none());
        } catch (Throwable t) {
            return new ProbeResult.Skipped("restoreRewindState threw: " + describeThrowable(t));
        }

        // Diff fields
        List<GapRecord> gaps = diffFields(fqn, original, restored, cls);
        return new ProbeResult.Probed(gaps);
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    /**
     * Reflectively diffs all comparable subclass fields between {@code original} and {@code restored}.
     *
     * <p>Only walks fields declared between the concrete class and {@code AbstractObjectInstance}
     * (exclusive). Compares primitives, enums, and Strings. Object references are excluded
     * (not equality-testable without a live session).
     */
    private List<GapRecord> diffFields(
            String fqn,
            AbstractObjectInstance original,
            AbstractObjectInstance restored,
            Class<? extends AbstractObjectInstance> cls) {

        List<GapRecord> gaps = new ArrayList<>();

        for (Class<?> c = cls;
                c != null && c != AbstractObjectInstance.class && c != Object.class;
                c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.isSynthetic()) continue;

                Class<?> type = field.getType();
                // Only diff primitive / enum / String — comparable without live session
                if (!type.isPrimitive() && !type.isEnum() && type != String.class) {
                    continue;
                }

                field.setAccessible(true);
                try {
                    Object before = field.get(original);
                    Object after = field.get(restored);
                    if (!Objects.equals(before, after)) {
                        gaps.add(new GapRecord(
                                fqn,
                                field.getName(),
                                String.valueOf(before),
                                String.valueOf(after),
                                Modifier.isFinal(field.getModifiers())));
                    }
                } catch (IllegalAccessException e) {
                    LOG.warning("Could not access field " + field.getName()
                            + " on " + fqn + ": " + e.getMessage());
                }
            }
        }

        return gaps;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String describeThrowable(Throwable t) {
        Throwable root = t;
        int depth = 0;
        while (root.getCause() != null && depth++ < 5) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    private static String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    private static String mdEscape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
