package com.openggf.level.objects;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of a single ROM object's SST (Sonic Object Status Table)
 * slot fields captured at trace-recording start, before trace frame 0.
 *
 * <p>Consumed by {@link AbstractObjectInstance#hydrateFromRomSnapshot} during
 * trace-replay test setup so the engine's freshly-spawned objects can mirror
 * state-machine progress the ROM made during title-card and level-init
 * iterations before recording began.
 *
 * <p>Provides two access modes:
 * <ul>
 *   <li>Canonical getters ({@link #routine()}, {@link #yPos()}, ...) for
 *       well-known Sonic 2 SST fields at the universal header offsets
 *       ($00-$28, defined in <code>s2.constants.asm</code>).</li>
 *   <li>Generic {@link #byteAt(int)} / {@link #wordAt(int)} for per-object
 *       fields in the $2A-$3F range, where each object type reserves its own
 *       state variables.</li>
 * </ul>
 *
 * <p>The Lua recorder (<code>s2_trace_recorder.lua</code>) emits raw bytes for
 * every offset $00-$3F plus semantic word aliases for the handful of universal
 * word fields. The Java side composes words big-endian from consecutive byte
 * entries when no explicit word alias is present, letting per-object code
 * pull any word from $2A-$3F without requiring new Lua keys.
 *
 * <p>Byte values are stored unsigned (0..0xFF). Word values are stored
 * unsigned (0..0xFFFF); callers that need signed interpretation use
 * {@link #signedWordAt(int)}.
 */
public final class RomObjectSnapshot {

    private final Map<Integer, Integer> byteFields;
    private final Map<Integer, Integer> wordFields;

    public RomObjectSnapshot(Map<Integer, Integer> byteFields,
                             Map<Integer, Integer> wordFields) {
        this.byteFields = byteFields != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(byteFields))
            : Collections.emptyMap();
        this.wordFields = wordFields != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(wordFields))
            : Collections.emptyMap();
    }

    // ---- Canonical SST header fields (s2.constants.asm offsets $00..$28) ----

    public int id()                 { return byteAt(0x00); }
    public int renderFlags()        { return byteAt(0x01); }
    public int artTile()            { return wordAt(0x02); }
    public int xPos()               { return wordAt(0x08); }
    public int xSub()               { return wordAt(0x0A); }
    public int yPos()               { return wordAt(0x0C); }
    public int ySub()               { return wordAt(0x0E); }
    /** Signed 16-bit x velocity (subpixel/frame; &gt;&gt;8 = pixel/frame). */
    public int xVel()               { return signedWordAt(0x10); }
    /** Signed 16-bit y velocity. */
    public int yVel()               { return signedWordAt(0x12); }
    public int yRadius()            { return byteAt(0x16); }
    public int xRadius()            { return byteAt(0x17); }
    public int mappingFrame()       { return byteAt(0x1A); }
    public int animFrame()          { return byteAt(0x1B); }
    public int animId()             { return byteAt(0x1C); }
    public int animPrevId()         { return byteAt(0x1D); }
    public int animFrameTimer()     { return byteAt(0x1E); }
    public int collisionWidth()     { return byteAt(0x1F); }
    public int collisionHeight()    { return byteAt(0x20); }
    public int status()             { return byteAt(0x22); }
    public int respawnIndex()       { return byteAt(0x23); }
    public int routine()            { return byteAt(0x24); }
    public int routineSecondary()   { return byteAt(0x25); }
    public int angle()              { return byteAt(0x26); }
    public int subtype()            { return byteAt(0x28); }

    // ---- Generic accessors for per-object fields ($2A-$3F) ----

    /** Returns the unsigned byte at {@code offset}, or 0 if absent. */
    public int byteAt(int offset) {
        return byteFields.getOrDefault(offset, 0);
    }

    /**
     * Returns the unsigned 16-bit word at {@code offset}.
     *
     * <p>If an explicit word entry was recorded (for semantic fields like
     * {@code x_pos}), that value is used. Otherwise the word is composed
     * big-endian from two consecutive byte entries at {@code offset} and
     * {@code offset + 1} — this matches how the Mega Drive reads words from
     * the SST and lets the Lua recorder emit only raw bytes without losing
     * word access.
     */
    public int wordAt(int offset) {
        Integer explicit = wordFields.get(offset);
        if (explicit != null) {
            return explicit;
        }
        if (byteFields.containsKey(offset) || byteFields.containsKey(offset + 1)) {
            int hi = byteFields.getOrDefault(offset, 0) & 0xFF;
            int lo = byteFields.getOrDefault(offset + 1, 0) & 0xFF;
            return (hi << 8) | lo;
        }
        return 0;
    }

    /** Returns the sign-extended 16-bit word at {@code offset}. */
    public int signedWordAt(int offset) {
        int raw = wordAt(offset);
        return (raw & 0x8000) != 0 ? raw - 0x10000 : raw;
    }

    /** Returns an unmodifiable view of the captured byte fields. */
    public Map<Integer, Integer> byteFields() { return byteFields; }

    /** Returns an unmodifiable view of the captured word fields. */
    public Map<Integer, Integer> wordFields() { return wordFields; }

    /**
     * Returns {@code true} if this snapshot has no recorded data. Used by
     * the trace replay binder to short-circuit hydration when a slot was
     * empty at trace start.
     */
    public boolean isEmpty() {
        return byteFields.isEmpty() && wordFields.isEmpty();
    }

    /**
     * Parses the JSON {@code "fields"} object emitted by the Lua recorder
     * into a snapshot.
     *
     * <p>Each field value is either {@code "0xXX"} (byte, two hex digits) or
     * {@code "0xXXXX"} (word, four hex digits). Field keys come in two forms:
     * <ul>
     *   <li>Semantic names (e.g. {@code "routine"}, {@code "y_pos"}) mapped to
     *       their canonical SST offset via {@link #semanticOffset(String)}.</li>
     *   <li>Raw offset names {@code "off_XX"} for per-object state where the
     *       offset encodes the SST byte position directly.</li>
     * </ul>
     * Unknown keys are silently ignored so new semantic aliases can be added
     * without breaking legacy traces.
     */
    public static RomObjectSnapshot fromJsonNode(JsonNode fieldsNode) {
        Map<Integer, Integer> bytes = new LinkedHashMap<>();
        Map<Integer, Integer> words = new LinkedHashMap<>();
        if (fieldsNode == null || !fieldsNode.isObject()) {
            return new RomObjectSnapshot(bytes, words);
        }

        fieldsNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String valueText = entry.getValue().isNumber()
                ? Integer.toString(entry.getValue().asInt())
                : entry.getValue().asText();
            if (valueText == null || valueText.isEmpty()) {
                return;
            }
            String hex = valueText.startsWith("0x") || valueText.startsWith("0X")
                ? valueText.substring(2)
                : valueText;
            int parsed;
            try {
                parsed = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                return;
            }

            int offset;
            boolean isWord;
            if (key.startsWith("off_")) {
                try {
                    offset = Integer.parseInt(key.substring(4), 16);
                } catch (NumberFormatException e) {
                    return;
                }
                // The Lua recorder emits 4-hex-digit values for words, 2 for bytes.
                isWord = hex.length() > 2;
            } else {
                int semantic = semanticOffset(key);
                if (semantic < 0) {
                    return;
                }
                offset = semantic;
                isWord = isSemanticWordField(key);
            }

            if (isWord) {
                words.put(offset, parsed & 0xFFFF);
            } else {
                bytes.put(offset, parsed & 0xFF);
            }
        });

        return new RomObjectSnapshot(bytes, words);
    }

    /**
     * Maps a semantic field name (as emitted by the Lua recorder) to its SST
     * offset. Returns -1 if the name is unknown.
     */
    private static int semanticOffset(String name) {
        return switch (name) {
            case "id" -> 0x00;
            case "render_flags" -> 0x01;
            case "art_tile" -> 0x02;
            case "x_pos" -> 0x08;
            case "x_sub" -> 0x0A;
            case "y_pos" -> 0x0C;
            case "y_sub" -> 0x0E;
            case "x_vel" -> 0x10;
            case "y_vel" -> 0x12;
            case "y_radius" -> 0x16;
            case "x_radius" -> 0x17;
            case "mapping_frame" -> 0x1A;
            case "anim_frame" -> 0x1B;
            case "anim" -> 0x1C;
            case "anim_prev" -> 0x1D;
            case "anim_frame_timer" -> 0x1E;
            case "collision_width" -> 0x1F;
            case "collision_height" -> 0x20;
            case "status" -> 0x22;
            case "respawn_index" -> 0x23;
            case "routine" -> 0x24;
            case "routine_secondary" -> 0x25;
            case "angle" -> 0x26;
            case "subtype" -> 0x28;
            default -> -1;
        };
    }

    private static boolean isSemanticWordField(String name) {
        return switch (name) {
            case "art_tile", "x_pos", "x_sub", "y_pos", "y_sub",
                 "x_vel", "y_vel" -> true;
            default -> false;
        };
    }
}
