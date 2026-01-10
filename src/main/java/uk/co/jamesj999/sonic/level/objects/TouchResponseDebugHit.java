package uk.co.jamesj999.sonic.level.objects;

public record TouchResponseDebugHit(
        ObjectSpawn spawn,
        int flags,
        int sizeIndex,
        int width,
        int height,
        TouchCategory category,
        boolean overlapping
) {
}
