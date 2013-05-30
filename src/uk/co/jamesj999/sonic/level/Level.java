package uk.co.jamesj999.sonic.level;

import javax.media.opengl.GL2;

public interface Level {
	public Tile getTileAt(short x, short y);
	public void draw();
}
