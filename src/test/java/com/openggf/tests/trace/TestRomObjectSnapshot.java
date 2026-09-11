package com.openggf.tests.trace;

import com.openggf.trace.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.level.objects.RomObjectSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RomObjectSnapshot}.
 *
 * <p>Covers the two key JSON parse paths (semantic aliases + raw {@code off_XX}),
 * the big-endian word composition from byte entries, and signed word sign-extension.
 */
public class TestRomObjectSnapshot {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void parsesSemanticAliases() throws Exception {
        JsonNode node = mapper.readTree("""
                {
                  "id": "0x9D",
                  "routine": "0x04",
                  "routine_secondary": "0x00",
                  "x_pos": "0x0208",
                  "y_pos": "0x015C",
                  "y_vel": "0xFF00",
                  "status": "0x01",
                  "mapping_frame": "0x01"
                }
                """);
        RomObjectSnapshot snap = RomObjectSnapshot.fromJsonNode(node);

        assertEquals(0x9D, snap.id());
        assertEquals(0x04, snap.routine());
        assertEquals(0x00, snap.routineSecondary());
        assertEquals(0x0208, snap.xPos());
        assertEquals(0x015C, snap.yPos());
        assertEquals(-256, snap.yVel());            // 0xFF00 signed = -256
        assertEquals(0x01, snap.status());
        assertEquals(0x01, snap.mappingFrame());
    }

    @Test
    public void parsesRawByteOffsets() throws Exception {
        // Pack Obj9D per-object state: timer at $2A, climb_table_index word at $2C-$2D.
        JsonNode node = mapper.readTree("""
                {
                  "off_2A": "0x12",
                  "off_2C": "0x00",
                  "off_2D": "0x06",
                  "off_2E": "0x1F"
                }
                """);
        RomObjectSnapshot snap = RomObjectSnapshot.fromJsonNode(node);

        assertEquals(0x12, snap.byteAt(0x2A));
        // Word composition: hi=0x00, lo=0x06 → 0x0006
        assertEquals(0x0006, snap.wordAt(0x2C));
        assertEquals(0x1F, snap.byteAt(0x2E));
    }

    @Test
    public void explicitWordBeatsComposedBytes() throws Exception {
        // When both a semantic x_pos word AND off_08/off_09 bytes are present,
        // the explicit word wins (preserves Lua recorder semantics).
        JsonNode node = mapper.readTree("""
                {
                  "x_pos": "0x1234",
                  "off_08": "0xAA",
                  "off_09": "0xBB"
                }
                """);
        RomObjectSnapshot snap = RomObjectSnapshot.fromJsonNode(node);
        assertEquals(0x1234, snap.xPos());
    }

    @Test
    public void signedWordExtendsNegativeValues() throws Exception {
        JsonNode node = mapper.readTree("""
                { "x_vel": "0xFE00", "y_vel": "0x0100" }
                """);
        RomObjectSnapshot snap = RomObjectSnapshot.fromJsonNode(node);
        assertEquals(-512, snap.xVel());
        assertEquals(0x0100, snap.yVel());
    }

    @Test
    public void unknownKeysAreSilentlyIgnored() throws Exception {
        JsonNode node = mapper.readTree("""
                { "routine": "0x04", "nonsense_field": "0xFF" }
                """);
        RomObjectSnapshot snap = RomObjectSnapshot.fromJsonNode(node);
        assertEquals(0x04, snap.routine());
        // yRadius defaults to 0 (no entry) — regression guard against spurious writes.
        assertEquals(0, snap.yRadius());
    }

    @Test
    public void missingWordFallsBackToZero() {
        RomObjectSnapshot empty = new RomObjectSnapshot(null, null);
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.wordAt(0x08));
        assertEquals(0, empty.byteAt(0x2A));
    }
}
