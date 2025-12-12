package uk.co.jamesj999.sonic.level;

public class LevelObject {
    private final int x;
    private final int y;
    private final int id;
    private final int subtype;

    public LevelObject(int x, int y, int id, int subtype) {
        this.x = x;
        this.y = y;
        this.id = id;
        this.subtype = subtype;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getId() {
        return id;
    }

    public int getSubtype() {
        return subtype;
    }
}
