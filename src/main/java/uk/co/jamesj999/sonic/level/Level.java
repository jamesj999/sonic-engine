package uk.co.jamesj999.sonic.level;


public interface Level {
    public Tile getTileAt(short x, short y);

    public void draw();
}
