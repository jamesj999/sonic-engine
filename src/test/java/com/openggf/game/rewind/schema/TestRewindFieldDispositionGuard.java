package com.openggf.game.rewind.schema;

import com.openggf.tests.TestSessionOutputPaths;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the closed-world rewind field disposition (see
 * {@link RewindFieldDispositionAudit}): every field of every default-capture
 * object class must be captured by the path the class actually uses, be
 * explicitly transient/deferred, or be a structural final. A field with no
 * disposition is state that silently fails to ride rewind keyframes — the MGZ
 * spinning-top grab map was lost across rewind exactly this way.
 *
 * <p>New gap keys fail the guard: give the field a disposition (a central
 * policy entry in {@link DefaultObjectRewindPolicies} is preferred over
 * annotations) or make the class compact-reachable. Only intentionally
 * deferred debt belongs in the baseline. The current audit result is also
 * written to the session diagnostics rewind namespace to make
 * baseline tightening mechanical.
 */
class TestRewindFieldDispositionGuard {
    private static final Path BASELINE =
            Path.of("src/test/resources/rewind/field-disposition-baseline.txt");
    private static final Path CURRENT_REPORT =
            TestSessionOutputPaths.diagnostics("rewind")
                    .resolve("rewind-field-disposition-current.txt");

    @Test
    void noNewSilentlyDroppedRewindFieldsBeyondBaseline() throws Exception {
        List<RewindFieldDispositionAudit.Gap> gaps = RewindFieldDispositionAudit.auditAll();
        Map<String, String> current = new TreeMap<>();
        for (RewindFieldDispositionAudit.Gap gap : gaps) {
            current.put(gap.key(), gap.detail());
        }
        Files.createDirectories(CURRENT_REPORT.getParent());
        Files.write(CURRENT_REPORT, current.entrySet().stream()
                .map(e -> e.getKey() + "  # " + e.getValue())
                .collect(Collectors.toList()));

        Set<String> baseline = new TreeSet<>(Files.readAllLines(BASELINE));
        baseline.removeIf(String::isBlank);
        baseline.removeIf(line -> line.startsWith("#"));

        Set<String> regressions = new TreeSet<>(current.keySet());
        regressions.removeAll(baseline);
        assertTrue(regressions.isEmpty(),
                "Fields with NO rewind disposition introduced (not captured by the class's actual "
                        + "capture path, not transient/deferred, not structural-final):\n  "
                        + regressions.stream()
                                .map(key -> key + "  # " + current.get(key))
                                .collect(Collectors.joining("\n  "))
                        + "\nGive each field a disposition (central policy entry preferred), make the "
                        + "class compact-reachable, or (only if intentional debt) add the key to "
                        + BASELINE + ".");

        Set<String> closed = new TreeSet<>(baseline);
        closed.removeAll(current.keySet());
        if (!closed.isEmpty()) {
            System.out.println("[rewind-field-disposition] gaps closed since baseline (tighten baseline):\n  "
                    + String.join("\n  ", closed));
        }
    }
}
