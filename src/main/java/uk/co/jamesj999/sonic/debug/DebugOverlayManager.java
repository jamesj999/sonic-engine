package uk.co.jamesj999.sonic.debug;

import uk.co.jamesj999.sonic.Control.InputHandler;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public class DebugOverlayManager {
    private static DebugOverlayManager debugOverlayManager;

    private final EnumMap<DebugOverlayToggle, Boolean> states = new EnumMap<>(DebugOverlayToggle.class);

    private DebugOverlayManager() {
        for (DebugOverlayToggle toggle : DebugOverlayToggle.values()) {
            states.put(toggle, toggle.defaultEnabled());
        }
    }

    public static synchronized DebugOverlayManager getInstance() {
        if (debugOverlayManager == null) {
            debugOverlayManager = new DebugOverlayManager();
        }
        return debugOverlayManager;
    }

    public void updateInput(InputHandler handler) {
        if (handler == null) {
            return;
        }
        for (DebugOverlayToggle toggle : DebugOverlayToggle.values()) {
            if (handler.isKeyPressed(toggle.keyCode())) {
                setEnabled(toggle, !isEnabled(toggle));
            }
        }

        // Ctrl+P copies performance stats to clipboard
        if (handler.isKeyDown(KeyEvent.VK_CONTROL) && handler.isKeyPressed(KeyEvent.VK_P)) {
            copyPerformanceStatsToClipboard();
        }
    }

    private void copyPerformanceStatsToClipboard() {
        StringBuilder sb = new StringBuilder();

        // Performance profiler stats
        ProfileSnapshot snapshot = PerformanceProfiler.getInstance().getSnapshot();
        if (snapshot.hasData()) {
            sb.append("=== Performance Stats ===\n");
            sb.append(String.format("Frame Time: %.2fms (%.1f%% of 16.67ms budget)\n",
                    snapshot.totalFrameTimeMs(),
                    (snapshot.totalFrameTimeMs() / 16.67) * 100));
            sb.append(String.format("FPS: %.1f\n\n", snapshot.fps()));

            sb.append("Section Timings:\n");
            for (SectionStats section : snapshot.getSectionsSortedByTime()) {
                sb.append(String.format("  %-12s %6.2fms (%5.1f%%)\n",
                        section.name(), section.timeMs(), section.percentage()));
            }
            sb.append("\n");
        }

        // Memory stats
        MemoryStats.Snapshot memSnapshot = MemoryStats.getInstance().snapshot();
        sb.append("=== Memory Stats ===\n");
        sb.append(String.format("Heap: %.0fMB / %.0fMB (%d%%)\n",
                memSnapshot.heapUsedMB(), memSnapshot.heapMaxMB(), memSnapshot.heapPercentage()));
        sb.append(String.format("GC Collections: %d (total time: %dms)\n",
                memSnapshot.gcCount(), memSnapshot.gcTimeMs()));
        sb.append(String.format("Allocation Rate: %.2fMB/s\n\n", memSnapshot.allocationRateMBPerSec()));

        List<MemoryStats.SectionAllocation> topAllocators = memSnapshot.topAllocators();
        if (!topAllocators.isEmpty()) {
            sb.append("Top Allocators (per frame avg):\n");
            for (MemoryStats.SectionAllocation alloc : topAllocators) {
                sb.append(String.format("  %-12s %8.1fKB\n", alloc.name(), alloc.kbPerFrame()));
            }
        }

        // Copy to clipboard
        StringSelection selection = new StringSelection(sb.toString());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
    }

    public boolean isEnabled(DebugOverlayToggle toggle) {
        return states.getOrDefault(toggle, Boolean.TRUE);
    }

    public void setEnabled(DebugOverlayToggle toggle, boolean enabled) {
        states.put(toggle, enabled);
    }

    public List<String> buildShortcutLines() {
        List<String> lines = new ArrayList<>();
        for (DebugOverlayToggle toggle : DebugOverlayToggle.values()) {
            String state = isEnabled(toggle) ? "On" : "Off";
            lines.add(toggle.shortcutLabel() + " " + toggle.label() + ": " + state);
        }
        return lines;
    }
}
