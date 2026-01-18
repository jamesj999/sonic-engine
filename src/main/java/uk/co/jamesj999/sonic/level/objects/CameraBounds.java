package uk.co.jamesj999.sonic.level.objects;

/**
 * Pre-computed camera visibility bounds, updated once per frame.
 * ROM equivalent: Objects check against Camera_X_pos/Camera_Y_pos in MarkObjGone.
 * By caching these values, we avoid repeated Camera.getInstance() calls
 * and field reads when checking visibility for many objects.
 */
public record CameraBounds(int left, int top, int right, int bottom) {

    /**
     * Checks if a point is within these bounds.
     */
    public boolean contains(int x, int y) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    /**
     * Checks if a point is within these bounds with a margin.
     */
    public boolean contains(int x, int y, int margin) {
        return x >= left - margin && x <= right + margin
            && y >= top - margin && y <= bottom + margin;
    }
}
