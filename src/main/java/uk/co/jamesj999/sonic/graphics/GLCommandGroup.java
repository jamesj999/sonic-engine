package uk.co.jamesj999.sonic.graphics;

import org.lwjgl.opengl.GL11;

import java.util.List;

public class GLCommandGroup implements GLCommandable {
	private int drawMethod;
	private List<GLCommand> commands;
	
	public GLCommandGroup(int drawMethod, List<GLCommand> commands) {
		this.drawMethod = drawMethod;
		this.commands = commands;
	}
	
	public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
		GL11.glBegin(drawMethod);
		for(GLCommand command : commands) {
			command.execute(cameraX, cameraY, cameraWidth, cameraHeight);
		}
		GL11.glEnd();
	}
}
