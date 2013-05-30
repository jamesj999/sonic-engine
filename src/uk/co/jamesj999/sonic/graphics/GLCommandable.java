package uk.co.jamesj999.sonic.graphics;

import javax.media.opengl.GL2;

public interface GLCommandable {
	public void execute(GL2 gl, int cameraX, int cameraY);
}
