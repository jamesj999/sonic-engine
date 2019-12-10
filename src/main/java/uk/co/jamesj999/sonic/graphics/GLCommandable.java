package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;

public interface GLCommandable {
	public void execute(GL2 gl, int cameraX, int cameraY, int cameraWidth, int cameraHeight);
}
