package uk.co.jamesj999.sonic.Control;

import org.lwjgl.glfw.GLFW;
import java.util.HashMap;
import java.util.Map;

public class InputHandler {

	private long window;  // Store the GLFW window handle
	private Map<Integer, Boolean> keyStates = new HashMap<>();  // Track key states

	/**
	 * Assigns the InputHandler to a GLFW window
	 *
	 * @param window The GLFW window handle
	 */
	public InputHandler(long window) {
		this.window = window;
	}

	/**
	 * Checks whether a specific key is down
	 *
	 * @param keyCode The GLFW key code to check (e.g., GLFW.GLFW_KEY_* constants)
	 * @return Whether the key is pressed or not
	 */
	public boolean isKeyDown(int keyCode) {
		return GLFW.glfwGetKey(window, keyCode) == GLFW.GLFW_PRESS;
	}

	/**
	 * Checks whether a key was just pressed (i.e., transition from up to down)
	 *
	 * @param keyCode The GLFW key code to check
	 * @return Whether the key was just pressed
	 */
	public boolean isKeyPressed(int keyCode) {
		boolean currentState = isKeyDown(keyCode);
		boolean previousState = keyStates.getOrDefault(keyCode, false);
		keyStates.put(keyCode, currentState);  // Update the state

		// Key is pressed if it was released and now it's down
		return !previousState && currentState;
	}

	/**
	 * Checks whether a key was just released (i.e., transition from down to up)
	 *
	 * @param keyCode The GLFW key code to check
	 * @return Whether the key was just released
	 */
	public boolean isKeyReleased(int keyCode) {
		boolean currentState = isKeyDown(keyCode);
		boolean previousState = keyStates.getOrDefault(keyCode, false);
		keyStates.put(keyCode, currentState);  // Update the state

		// Key is released if it was down and now it's up
		return previousState && !currentState;
	}
}
