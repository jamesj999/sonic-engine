package com.openggf.data.compression;

import com.openggf.data.Rom;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoises Kosinski archive inspections per ROM.
 *
 * <p>{@link KosinskiReader#inspectStandard} and {@link KosinskiReader#inspectModuled}
 * learn an archive's compressed and decompressed lengths by decoding it in
 * full. Both results are pure functions of the ROM bytes at the source
 * address, so the S3K decompression queues, which need those lengths on the
 * frame that submits a job, can take them from here instead of decoding on
 * that frame. A miss performs the same read and inspection the queues did
 * before, so cached and uncached submissions carry identical descriptors.
 *
 * <p>Thread-safe: a prepared level build may warm entries on its own thread
 * while the frame thread submits jobs.
 */
public final class KosinskiInspectionCache {
    /** Bounded read for moduled archives, matching the module queue's window. */
    private static final int MODULED_INSPECTION_LIMIT = 0x40000;

    private static final Map<Rom, Map<Long, KosinskiReader.StandardArchiveInfo>> STANDARD =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Rom, Map<Long, KosinskiReader.ModuledArchiveInfo>> MODULED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private KosinskiInspectionCache() {
    }

    /**
     * Returns the standard (plain) Kosinski archive info at {@code source},
     * inspecting the whole remaining ROM on a miss exactly as the direct queue
     * always did.
     */
    public static KosinskiReader.StandardArchiveInfo inspectStandard(Rom rom, int source)
            throws IOException {
        Map<Long, KosinskiReader.StandardArchiveInfo> perRom =
                STANDARD.computeIfAbsent(rom, ignored -> new ConcurrentHashMap<>());
        KosinskiReader.StandardArchiveInfo cached = perRom.get((long) source);
        if (cached != null) {
            return cached;
        }
        long remaining = rom.getSize() - source;
        if (source < 0 || remaining < 2) {
            throw new IOException("Kosinski source is outside ROM: 0x"
                    + Integer.toHexString(source));
        }
        if (remaining > Integer.MAX_VALUE) {
            throw new IOException("Kosinski stream exceeds Java inspection limit");
        }
        byte[] inspection = rom.readBytes(source, (int) remaining);
        KosinskiReader.StandardArchiveInfo info = KosinskiReader.inspectStandard(inspection, 0);
        perRom.putIfAbsent((long) source, info);
        return info;
    }

    /**
     * Returns the Kosinski Moduled archive info at {@code source}, inspecting
     * the same bounded window the module queue always read on a miss.
     */
    public static KosinskiReader.ModuledArchiveInfo inspectModuled(Rom rom, int source)
            throws IOException {
        Map<Long, KosinskiReader.ModuledArchiveInfo> perRom =
                MODULED.computeIfAbsent(rom, ignored -> new ConcurrentHashMap<>());
        KosinskiReader.ModuledArchiveInfo cached = perRom.get((long) source);
        if (cached != null) {
            return cached;
        }
        long remaining = rom.getSize() - source;
        if (source < 0 || remaining < 2) {
            throw new IOException("KosM source is outside ROM: 0x"
                    + Integer.toHexString(source));
        }
        int inspectionLength = (int) Math.min(remaining, MODULED_INSPECTION_LIMIT);
        byte[] inspection = rom.readBytes(source, inspectionLength);
        KosinskiReader.ModuledArchiveInfo info = KosinskiReader.inspectModuled(inspection, 0);
        perRom.putIfAbsent((long) source, info);
        return info;
    }

    /** Test seam: forgets every cached inspection. */
    public static void clear() {
        STANDARD.clear();
        MODULED.clear();
    }
}
