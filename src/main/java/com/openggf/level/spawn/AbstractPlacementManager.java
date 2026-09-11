package com.openggf.level.spawn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Shared windowing support for spawn placement managers.
 */
public abstract class AbstractPlacementManager<T extends SpawnPoint> {
    private static final Logger LOGGER = Logger.getLogger(AbstractPlacementManager.class.getName());

    protected List<T> spawns;
    private int identityMissCount;

    /**
     * ROM-faithful spawn ordering: by chunk-aligned X ({@code x & 0xFF80}),
     * <em>stable</em> so objects within the same 0x80 chunk keep their source
     * (ROM layout-table) order.
     * <p>
     * ROM ObjPosLoad (S1) / ObjectsManager_Main (S2/S3K) window objects against
     * chunk-aligned spawn-window edges ({@code v_opl_screen & $FF80} ± $80/$280;
     * docs/s1disasm/_inc/ObjPosLoad.asm OPL_Main/OPL_Next, s2.asm:33045-33117),
     * so every object inside a single 0x80 column always loads in the same
     * ObjPosLoad pass, and the forward/backward scans walk the layout table in
     * stored order ({@code a0 += 6} / {@code a0 -= 6}). FindFreeObj then hands out
     * ascending free slots in that order. A strict full-X sort reorders objects
     * that the level data stores slightly out of X order within one chunk (e.g.
     * SBZ2's 0x72@0x1594 stored before 0x15@0x1590 / Bomb@0x1590), which assigns
     * those co-column objects the wrong FindFreeObj slots. Sorting by chunk keeps
     * the within-chunk order equal to the source list's order, matching ROM.
     * <p>
     * For lists already sorted ascending by full X within each chunk (S2/S3K
     * layout tables are authored that way), this is identical to the old full-X
     * sort. The window/lowerBound math uses chunk-aligned edges
     * (see {@link #getWindowStart}/{@link #getWindowEnd}), so within-chunk X
     * wiggle never affects a boundary decision.
     */
    private static final Comparator<SpawnPoint> CHUNK_GRANULAR_ORDER =
            Comparator.comparingInt(p -> p.x() & 0xFF80);
    protected final Set<T> active = new LinkedHashSet<>();
    protected final Collection<T> activeUnmodifiable = Collections.unmodifiableCollection(active);
    protected final Map<T, Integer> spawnIndexMap = new IdentityHashMap<>();
    private final int extraAhead;
    private final int loadAheadFixed; // legacy fixed value when no width supplier is given
    private final java.util.function.IntSupplier widthSupplier; // null => use loadAheadFixed
    private final int unloadBehind;

    /** Legacy fixed-window constructor (used only by NATIVE-equivalent callers / tests). */
    protected AbstractPlacementManager(List<T> spawns, int loadAhead, int unloadBehind) {
        this(spawns, loadAhead, unloadBehind, null, 0);
    }

    /**
     * Width-driven constructor. The load-ahead window is computed at query time
     * as {@code widthSupplier.getAsInt() + extraAhead}, so the spawn window
     * follows the configured viewport width. At native width (320) with the
     * standard extraAhead (320) this equals the legacy 0x280 (640) window.
     */
    protected AbstractPlacementManager(List<T> spawns, int extraAhead, int unloadBehind,
            java.util.function.IntSupplier widthSupplier) {
        this(spawns, 0, unloadBehind, widthSupplier, extraAhead);
    }

    private AbstractPlacementManager(List<T> spawns, int loadAheadFixed, int unloadBehind,
            java.util.function.IntSupplier widthSupplier, int extraAhead) {
        ArrayList<T> sorted = new ArrayList<>(spawns);
        sorted.sort(CHUNK_GRANULAR_ORDER);
        this.spawns = Collections.unmodifiableList(sorted);
        this.loadAheadFixed = loadAheadFixed;
        this.extraAhead = extraAhead;
        this.widthSupplier = widthSupplier;
        this.unloadBehind = unloadBehind;
        for (int i = 0; i < this.spawns.size(); i++) {
            spawnIndexMap.put(this.spawns.get(i), i);
        }
    }

    /**
     * Replaces the internal spawn list with a new one.
     * Clears active set, rebuilds sorted index, and resets the index map.
     * Used by the level editor when spawns are added/removed.
     */
    protected void replaceSpawns(List<T> newSpawns) {
        active.clear();
        spawnIndexMap.clear();
        ArrayList<T> sorted = new ArrayList<>(newSpawns);
        sorted.sort(CHUNK_GRANULAR_ORDER);
        this.spawns = Collections.unmodifiableList(sorted);
        for (int i = 0; i < this.spawns.size(); i++) {
            spawnIndexMap.put(this.spawns.get(i), i);
        }
    }

    public List<T> getAllSpawns() {
        return spawns;
    }

    public Collection<T> getActiveSpawns() {
        return activeUnmodifiable;
    }

    /**
     * Returns the index of the given spawn in the sorted spawns list.
     * Uses identity-based lookup first (fast path), then falls back to
     * equals-based linear scan if the reference doesn't match.
     * The fallback handles cases where an object instance holds a spawn
     * reference that differs from the canonical reference stored during
     * construction (e.g. if the spawn was reconstructed or deserialized).
     */
    public int getSpawnIndex(T spawn) {
        Integer index = spawnIndexMap.get(spawn);
        if (index != null) {
            return index;
        }
        // Fallback: linear scan using equals() in case the identity reference
        // doesn't match the canonical reference stored in spawnIndexMap.
        for (int i = 0; i < spawns.size(); i++) {
            if (spawns.get(i).equals(spawn)) {
                final int foundIndex = i;
                LOGGER.warning(() -> "getSpawnIndex: identity miss for spawn at ("
                        + spawn.x() + "," + spawn.y() + "), found via equals at index " + foundIndex);
                return i;
            }
        }
        return -1;
    }

    /** Parity baseline viewport width. Load-ahead at this width reproduces the legacy window. */
    static final int NATIVE_VIEWPORT_WIDTH = 320;

    /**
     * Minimum load-ahead beyond the visible screen at widescreen widths (one
     * chunk, 128px). The forward spawn fires on each chunk-boundary crossing
     * (the camera is ~chunk-aligned at that moment), so this is the practical
     * pre-load lead in pixels — about 8 frames at typical scroll speeds, enough
     * to avoid pop-in.
     */
    static final int WIDESCREEN_LOAD_LEAD = 0x80;

    /**
     * Forward load-ahead window width.
     *
     * <p>At native width this is {@code 320 + extraAhead} (the legacy {@code 0x280}
     * window with the standard {@code extraAhead} of 320). At widescreen the window
     * grows only by the <em>minimum</em> needed to pre-load objects before they
     * enter the wider screen ({@code viewportWidth + 128}), NOT by the full
     * {@code extraAhead} margin on top of the wider screen.
     *
     * <p>Rationale: the number of objects held live at once is the window width ×
     * object density, and the per-game object slot pool is a <em>fixed</em>
     * ROM-sized table ({@code ObjectSlotLayout}: S1=96, S2=112, S3K=89). The
     * original {@code viewportWidth + extraAhead} window grew so far past native
     * that dense areas overran the pool, so {@code allocateSlot()} returned -1 and
     * the spawn was silently dropped — objects intermittently failing to load when
     * scrolling right at widescreen (all games). Capping the load-ahead keeps the
     * live count close to native so the pool no longer overruns. The despawn and
     * visibility windows stay full-width, so objects on the wider screen are not
     * culled (the original right-edge despawn bug stays fixed).
     *
     * <p>At native ({@code viewportWidth == 320}) this returns exactly the legacy
     * value — byte-identical.
     */
    static int loadAheadFor(int viewportWidth, int extraAhead) {
        int nativeBaseline = NATIVE_VIEWPORT_WIDTH + extraAhead;
        int widthDriven = viewportWidth + WIDESCREEN_LOAD_LEAD;
        return Math.max(nativeBaseline, widthDriven);
    }

    protected int getLoadAhead() {
        return widthSupplier != null
                ? loadAheadFor(widthSupplier.getAsInt(), extraAhead)
                : loadAheadFixed;
    }

    protected int getUnloadBehind() {
        return unloadBehind;
    }

    /**
     * ROM parity: window boundaries use chunk-aligned camera X.
     * <p>
     * Both S1 (ObjPosLoad/OPL_Next) and S2 (ObjectsManager_Main) calculate
     * spawn boundaries from {@code cameraX & 0xFF80} rather than raw cameraX:
     * <ul>
     *   <li>Backward: {@code (cameraX & 0xFF80) - 0x80}</li>
     *   <li>Forward:  {@code (cameraX & 0xFF80) + 0x280}</li>
     * </ul>
     * The {@code 0x280} forward extent is the native-width baseline; the actual
     * forward window scales with {@link #getLoadAhead()}, which equals
     * {@code 0x280} at native width (320) and widens only by the minimum lead
     * needed for wider viewports (see {@link #loadAheadFor}) so the fixed object
     * slot pool is not overrun.
     * <p>
     * Using raw cameraX shifts the window right by up to 127px, causing
     * objects on the left side to fall outside the spawn range.
     */
    private static final int CHUNK_ALIGN_MASK = 0xFF80;

    protected int getWindowStart(int cameraX) {
        int chunkAligned = cameraX & CHUNK_ALIGN_MASK;
        return Math.max(0, chunkAligned - unloadBehind);
    }

    protected int getWindowEnd(int cameraX) {
        int chunkAligned = cameraX & CHUNK_ALIGN_MASK;
        return chunkAligned + getLoadAhead();
    }

    protected int lowerBound(int value) {
        int low = 0;
        int high = spawns.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (spawns.get(mid).x() < value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    protected int upperBound(int value) {
        int low = 0;
        int high = spawns.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (spawns.get(mid).x() <= value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
