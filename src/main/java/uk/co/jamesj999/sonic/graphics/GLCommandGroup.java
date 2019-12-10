package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import java.util.List;

public class GLCommandGroup implements GLCommandable {
	private int drawMethod;
	private List<GLCommand> commands;
	
	public GLCommandGroup(int drawMethod, List<GLCommand> commands) {
		this.drawMethod = drawMethod;
		this.commands = commands;
	}
	
	public void execute(GL2 gl, int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
		gl.glBegin(drawMethod);
		for(GLCommand command : commands) {
			command.execute(gl, cameraX, cameraY, cameraWidth, cameraHeight);
		}
		gl.glEnd();
	}
}
