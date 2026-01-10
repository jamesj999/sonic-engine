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
        if (!commands.isEmpty()) {
            GLCommand.BlendType blendMode = commands.get(0).getBlendMode();
            if (blendMode == GLCommand.BlendType.SOLID) {
                gl.glDisable(GL2.GL_BLEND);
            } else {
                gl.glEnable(GL2.GL_BLEND);
                gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);
            }
        }
        GLCommand.setInGroup(true);
        gl.glBegin(drawMethod);
        for(GLCommand command : commands) {
            command.execute(gl, cameraX, cameraY, cameraWidth, cameraHeight);
        }
        gl.glEnd();
        GLCommand.setInGroup(false);
    }
}
