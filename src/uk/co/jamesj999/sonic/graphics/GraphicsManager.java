package uk.co.jamesj999.sonic.graphics;

import java.util.ArrayList;
import java.util.List;

import javax.media.opengl.GL2;

import uk.co.jamesj999.sonic.camera.Camera;

public class GraphicsManager {
	private static GraphicsManager graphicsManager;

	List<GLCommandable> commands = new ArrayList<GLCommandable>();

	Camera camera = Camera.getInstance();
	private GL2 graphics;

	public void registerCommand(GLCommandable command) {
		commands.add(command);
	}

	public void flush() {
		short cameraX = camera.getX();
		short cameraY = camera.getY();
		for (GLCommandable command : commands) {
			command.execute(graphics, cameraX, cameraY);
		}
		commands.clear();
	}

	public GL2 getGraphics() {
		return graphics;
	}

	public void setGraphics(GL2 graphicsgl) {
		graphics = graphicsgl;
	}

	public static synchronized final GraphicsManager getInstance() {
		if (graphicsManager == null) {
			graphicsManager = new GraphicsManager();
		}
		return graphicsManager;
	}
}
