package uk.co.jamesj999.sonic.level.rings;

/**
 * Immutable ring placement record expanded to individual ring positions.
 */
public record RingSpawn(int x, int y) {

    public RingSpawn {
        x = x & 0xFFFF;
        y = y & 0xFFFF;
    }
}
