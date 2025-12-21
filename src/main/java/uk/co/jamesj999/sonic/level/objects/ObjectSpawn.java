package uk.co.jamesj999.sonic.level.objects;

/**
 * Immutable placement record decoded from the Sonic 2 object layout lists.
 */
public record ObjectSpawn(
        int x,
        int y,
        int objectId,
        int subtype,
        int renderFlags,
        boolean respawnTracked,
        int rawYWord) {

    public ObjectSpawn {
        // Normalise to unsigned 16-bit range
        x = x & 0xFFFF;
        y = y & 0xFFFF;
        objectId = objectId & 0xFF;
        subtype = subtype & 0xFF;
        renderFlags = renderFlags & 0x3;
        rawYWord = rawYWord & 0xFFFF;
    }

    public int rawFlags() {
        return rawYWord & 0xF000;
    }
}
