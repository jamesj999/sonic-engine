package uk.co.jamesj999.sonic.graphics;

import java.util.List;

import javax.media.opengl.GL2;

public class GLCommandGroup implements GLCommandable {
	private int drawMethod;
	private List<GLCommand> commands;
	
	public GLCommandGroup(int drawMethod, List<GLCommand> commands) {
		this.drawMethod = drawMethod;
		this.commands = commands;
	}
	
	public void execute(GL2 gl, int cameraX, int cameraY) {
		gl.glBegin(drawMethod);
		for(GLCommand command : commands) {
			command.execute(gl, cameraX, cameraY);
		}
		gl.glEnd();
	}
}
