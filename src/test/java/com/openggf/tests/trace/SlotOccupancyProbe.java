package com.openggf.tests.trace;

import com.openggf.tests.TestSessionOutputPaths;

import com.openggf.level.objects.ObjectManager;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Comparison-only ROM-vs-engine SST slot occupancy probe for trace replay.
 *
 * <p>Reads the recorder's {@code slot_dump} aux events (ROM slot -> object id)
 * and diffs them against {@link ObjectManager#occupiedDynamicSlotIdsWithReservations()},
 * which folds parent-held child reservations in as a distinct state so a slot the
 * ROM's {@code FindFreeObj}/{@code FindNextFreeObj} has consumed is not reported
 * missing merely because the engine has not constructed the child instance yet.
 *
 * <p>Two hard constraints, both from the trace-replay invariants:
 * <ul>
 *   <li>It never writes engine state. It only reads a freshly built occupancy map
 *       and appends a text report.</li>
 *   <li>It is off unless {@code OGGF_SLOT_PROBE=1} is set in the environment, so a
 *       committed build never pays for it and no test outcome depends on it.</li>
 * </ul>
 *
 * <p><b>Lag-frame filter.</b> A {@code slot_dump} whose {@code vfc} equals the next
 * dump's {@code vfc} was sampled during a VBlank that interrupted {@code ObjPosLoad}:
 * the object load is still running, so the dump is a torn half-populated view of the
 * SST. Those samples are discarded rather than reported as divergences.
 */
public final class SlotOccupancyProbe {

    private static final String ENABLE_VAR = "OGGF_SLOT_PROBE";
    private static final String OUTPUT_VAR = "OGGF_SLOT_PROBE_OUT";

    private final Map<Integer, Map<Integer, Integer>> romDumpsByFrame;
    private final Writer out;
    private int reportedDivergentFrames;
    private int observedFrames;

    private SlotOccupancyProbe(Map<Integer, Map<Integer, Integer>> romDumpsByFrame, Writer out) {
        this.romDumpsByFrame = romDumpsByFrame;
        this.out = out;
    }

    /** Returns an armed probe, or {@code null} when disabled or when the trace has no usable dumps. */
    public static SlotOccupancyProbe createIfEnabled(TraceData trace, String label) {
        if (!"1".equals(System.getenv(ENABLE_VAR))) {
            return null;
        }
        Map<Integer, Map<Integer, Integer>> dumps = collectRomDumps(trace);
        if (dumps.isEmpty()) {
            return null;
        }
        String dir = System.getenv(OUTPUT_VAR);
        Path outputRoot = dir == null || dir.isBlank()
                ? TestSessionOutputPaths.diagnostics("slot-occupancy")
                : Path.of(dir);
        Path path = outputRoot.resolve("slot-occupancy-" + label + ".txt");
        try {
            Files.createDirectories(path.getParent());
            Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            writer.write("# ROM-vs-engine SST occupancy, " + label
                    + " (" + dumps.size() + " lag-filtered slot_dump samples)\n");
            return new SlotOccupancyProbe(dumps, writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Applies the vfc lag-frame filter and decodes each surviving {@code slot_dump}
     * into a {@code slot -> objectId} map.
     */
    private static Map<Integer, Map<Integer, Integer>> collectRomDumps(TraceData trace) {
        List<TraceEvent.StateSnapshot> dumps = new ArrayList<>();
        List<Integer> frames = new ArrayList<>();
        for (TraceEvent event : trace.getEventsInRange(0, trace.frameCount())) {
            if (event instanceof TraceEvent.StateSnapshot snapshot
                    && "slot_dump".equals(String.valueOf(snapshot.fields().get("event")))) {
                dumps.add(snapshot);
                frames.add(snapshot.frame());
            }
        }
        Map<Integer, Map<Integer, Integer>> result = new HashMap<>();
        for (int i = 0; i < dumps.size(); i++) {
            Long vfc = asLong(dumps.get(i).fields().get("vfc"));
            Long nextVfc = i + 1 < dumps.size() ? asLong(dumps.get(i + 1).fields().get("vfc")) : null;
            // Lag-frame filter: a dump sharing its vfc with the NEXT dump was taken
            // mid-ObjPosLoad, so the SST it shows is half-written. Discard it.
            if (vfc != null && vfc.equals(nextVfc)) {
                continue;
            }
            Map<Integer, Integer> slots = decodeSlots(dumps.get(i).fields().get("slots"));
            if (slots != null) {
                result.put(frames.get(i), slots);
            }
        }
        return result;
    }

    private static final java.util.regex.Pattern SLOT_PAIR = java.util.regex.Pattern.compile(
            "\\[\\s*(\\d+)\\s*,\\s*\"(0[xX][0-9A-Fa-f]+|\\d+)\"\\s*]");

    /**
     * The generic-event parser flattens the {@code slots} array to its raw JSON
     * text, so decode the {@code [slot,"0xID"]} pairs straight out of it.
     */
    private static Map<Integer, Integer> decodeSlots(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<Integer, Integer> slots = new HashMap<>();
        java.util.regex.Matcher m = SLOT_PAIR.matcher(String.valueOf(raw));
        while (m.find()) {
            Integer id = parseId(m.group(2));
            if (id != null) {
                slots.put(Integer.parseInt(m.group(1)), id);
            }
        }
        return slots.isEmpty() ? null : slots;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.decode(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer parseId(Object value) {
        Long id = asLong(value);
        return id == null ? null : (int) (id & 0xFF);
    }

    /** Diffs one frame if the ROM sampled a (non-lag) dump on it. */
    public void observe(int traceFrame, ObjectManager objectManager) {
        Map<Integer, Integer> rom = romDumpsByFrame.get(traceFrame);
        if (rom == null || objectManager == null) {
            return;
        }
        observedFrames++;
        Map<Integer, Integer> engine = objectManager.occupiedDynamicSlotIdsWithReservations();
        Set<Integer> slots = new TreeSet<>();
        slots.addAll(rom.keySet());
        slots.addAll(engine.keySet());
        List<String> diffs = new ArrayList<>();
        for (int slot : slots) {
            Integer romId = rom.get(slot);
            Integer engId = engine.get(slot);
            if (romId == null) {
                diffs.add(String.format("s%d rom=- eng=%s%s", slot, describe(engId),
                        describeOccupant(objectManager, slot)));
            } else if (engId == null) {
                diffs.add(String.format("s%d rom=0x%02X eng=-", slot, romId));
            } else if (engId >= 0 && !romId.equals(engId)) {
                diffs.add(String.format("s%d rom=0x%02X eng=0x%02X", slot, romId, engId));
            }
            // A reserved-child or unattributed engine slot matches ANY ROM occupant:
            // the allocator bit is the comparable quantity, the id is not yet knowable.
        }
        if (diffs.isEmpty()) {
            return;
        }
        reportedDivergentFrames++;
        write("f" + traceFrame + " (" + diffs.size() + " slots): " + String.join(", ", diffs) + "\n");
        if ("1".equals(System.getenv("OGGF_SLOT_PROBE_FULL"))) {
            write("    rom: " + formatMap(rom, null) + "\n    eng: " + formatMap(engine, objectManager) + "\n");
        }
    }

    private static String formatMap(Map<Integer, Integer> map, ObjectManager om) {
        StringBuilder sb = new StringBuilder();
        for (int slot : new TreeSet<>(map.keySet())) {
            sb.append(slot).append('=').append(describe(map.get(slot)));
            if (om != null) {
                sb.append(describeOccupant(om, slot));
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    /** Class + centre position of the engine occupant of {@code slot}, for extra-slot triage. */
    private static String describeOccupant(ObjectManager objectManager, int slot) {
        for (com.openggf.level.objects.ObjectInstance instance : objectManager.getActiveObjects()) {
            if (instance instanceof com.openggf.level.objects.AbstractObjectInstance aoi
                    && aoi.getSlotIndex() == slot && !instance.isDestroyed()) {
                return String.format("(%s @%d,%d spawn=%s persist=%s)",
                        instance.getClass().getSimpleName(),
                        instance.getX(), instance.getY(),
                        instance.getSpawn() == null ? "-"
                                : instance.getSpawn().x() + "," + instance.getSpawn().y(),
                        instance.isPersistent());
            }
        }
        return "";
    }

    private static String describe(Integer engId) {
        if (engId == null) {
            return "-";
        }
        if (engId == ObjectManager.SLOT_STATE_RESERVED_CHILD) {
            return "RESERVED";
        }
        if (engId == ObjectManager.SLOT_STATE_UNATTRIBUTED) {
            return "UNATTRIB";
        }
        return String.format("0x%02X", engId);
    }

    private void write(String text) {
        try {
            out.write(text);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Frames on which ROM and engine occupancy disagreed. */
    public int divergentFrameCount() {
        return reportedDivergentFrames;
    }

    public void close() {
        try {
            out.write("# compared frames: " + observedFrames
                    + " / divergent frames: " + reportedDivergentFrames + "\n");
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Frames the probe will compare, in ascending order (lag-filtered). */
    public Set<Integer> comparedFrames() {
        return new TreeSet<>(new HashSet<>(romDumpsByFrame.keySet()));
    }
}
