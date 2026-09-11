package com.openggf.control;

import com.openggf.InputBindingFactory;

import java.util.Objects;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Handles keyboard input from GLFW.
 * Key codes are GLFW key codes (GLFW_KEY_*).
 */
public class InputHandler {
	// GLFW key codes can range from 0 to GLFW_KEY_LAST (348)
	private static final int MAX_KEYS = 512;
	private static final int MAX_MOUSE_BUTTONS = 16;
	boolean[] keys = new boolean[MAX_KEYS];
	boolean[] previousKeys = new boolean[MAX_KEYS];
	boolean[] mouseButtons = new boolean[MAX_MOUSE_BUTTONS];
	boolean[] previousMouseButtons = new boolean[MAX_MOUSE_BUTTONS];
	private final Supplier<InputBindings> inputBindingsSource;
	private InputBindings inputBindings;
	private final KeyboardInputMapper keyboardInputMapper;
	private final GamepadInputManager gamepadInputManager;
	private LogicalInputSnapshot logicalSnapshot = LogicalInputSnapshot.neutral();
	private LogicalInputSnapshot logicalOverride;
	private double mouseX;
	private double mouseY;
	private boolean mouseInputSeen;

	/**
	 * Creates a new InputHandler.
	 * Key events should be delivered via handleKeyEvent() from GLFW callback.
	 */
	public InputHandler() {
		this(InputBindingFactory.standaloneSupplier());
	}

	public InputHandler(Supplier<InputBindings> inputBindingsSource) {
		this(inputBindingsSource, new GamepadInputManager(GamepadStateSource.noop()));
	}

	/**
	 * Creates an InputHandler backed by a caller-supplied {@link GamepadStateSource},
	 * for tests that need to drive gamepad state from outside {@code com.openggf.control}.
	 */
	public InputHandler(Supplier<InputBindings> inputBindingsSource, GamepadStateSource gamepadStateSource) {
		this(inputBindingsSource, new GamepadInputManager(gamepadStateSource));
	}

	public static InputHandler live(Supplier<InputBindings> inputBindingsSource) {
		return new InputHandler(inputBindingsSource, new GamepadInputManager(new GlfwGamepadStateSource()));
	}

	InputHandler(Supplier<InputBindings> inputBindingsSource, GamepadInputManager gamepadInputManager) {
		this.inputBindingsSource = Objects.requireNonNull(inputBindingsSource, "inputBindingsSource");
		this.gamepadInputManager = Objects.requireNonNull(gamepadInputManager, "gamepadInputManager");
		this.inputBindings = Objects.requireNonNull(inputBindingsSource.get(), "inputBindings");
		this.keyboardInputMapper = new KeyboardInputMapper();
	}

	/**
	 * Handle a key event from GLFW.
	 *
	 * @param key    The GLFW key code
	 * @param action GLFW_PRESS, GLFW_RELEASE, or GLFW_REPEAT
	 */
	public void handleKeyEvent(int key, int action) {
		if (key >= 0 && key < MAX_KEYS) {
			if (action == GLFW_PRESS || action == GLFW_REPEAT) {
				keys[key] = true;
			} else if (action == GLFW_RELEASE) {
				keys[key] = false;
			}
		}
	}

	public void handleMouseMove(double x, double y) {
		mouseX = x;
		mouseY = y;
		mouseInputSeen = true;
	}

	public void handleMouseButton(int button, int action) {
		mouseInputSeen = true;
		if (button >= 0 && button < MAX_MOUSE_BUTTONS) {
			if (action == GLFW_PRESS || action == GLFW_REPEAT) {
				mouseButtons[button] = true;
			} else if (action == GLFW_RELEASE) {
				mouseButtons[button] = false;
			}
		}
	}

	/**
	 * Checks whether a specific key is down.
	 *
	 * @param keyCode The GLFW key code to check, or a negative value for an
	 *        unbound binding, which is never down
	 * @return Whether the key is pressed or not
	 */
	public boolean isKeyDown(int keyCode) {
		// The twin of the guard in isKeyPressed, and load-bearing for the same
		// reason. An unbound binding is -1, and so is rewindKey() when live
		// rewind is unbound, so without this the pad-substitution tail below
		// reports every unbound binding as held for as long as the pad's rewind
		// bumper is -- and rewindHeld is not gated on LIVE_REWIND_ENABLED.
		// P1_B, P1_C, P2_B and P2_C ship unbound and are read here through
		// KeyboardInputMapper, so a held bumper handed both players a phantom
		// B and C with no matching press edge, held disagreeing with pressed.
		if (keyCode < 0) {
			return false;
		}
		if (keyCode < MAX_KEYS && keys[keyCode]) {
			return true;
		}
		return keyCode == inputBindings.rewindKey() && gamepadInputManager.isRewindHeld();
	}

	/** Returns raw keyboard state, ignoring any trace/replay logical override. */
	public boolean isPhysicalKeyDown(int keyCode) {
		return keyCode >= 0 && keyCode < MAX_KEYS && keys[keyCode];
	}

	public boolean isPhysicalShiftDown() {
		return isPhysicalKeyDown(GLFW_KEY_LEFT_SHIFT)
				|| isPhysicalKeyDown(GLFW_KEY_RIGHT_SHIFT);
	}

	public boolean isPhysicalControlDown() {
		return isPhysicalKeyDown(GLFW_KEY_LEFT_CONTROL)
				|| isPhysicalKeyDown(GLFW_KEY_RIGHT_CONTROL);
	}

	public boolean isPhysicalAltDown() {
		return isPhysicalKeyDown(GLFW_KEY_LEFT_ALT)
				|| isPhysicalKeyDown(GLFW_KEY_RIGHT_ALT);
	}

	public boolean isPhysicalSuperDown() {
		return isPhysicalKeyDown(GLFW_KEY_LEFT_SUPER)
				|| isPhysicalKeyDown(GLFW_KEY_RIGHT_SUPER);
	}

	/**
	 * Held state for a directional menu-cursor key, keyboard OR gamepad (D-pad/stick),
	 * for callers that drive their own hold-repeat timer (e.g. level-select screens).
	 * {@code directionMask} is one of {@code AbstractPlayableSprite.INPUT_UP/_DOWN/_LEFT/_RIGHT}.
	 * For a single edge-triggered press instead, use {@link #logical()}'s
	 * {@code menuUp()}/{@code menuDown()}/{@code menuLeft()}/{@code menuRight()}.
	 */
	public boolean isDirectionHeld(int keyCode, int directionMask) {
		return isKeyDown(keyCode) || (logical().player1().heldMask() & directionMask) != 0;
	}

	/**
	 * Checks whether a specific key was just pressed this frame.
	 *
	 * @param keyCode The GLFW key code to check, or a negative value for an
	 *        unbound binding, which is never pressed
	 * @return Whether the key was just pressed
	 */
	public boolean isKeyPressed(int keyCode) {
		// An explicitly empty binding resolves to -1, and so do the unbound
		// debugModeKey()/frameStepKey() bindings -- so without this an unbound
		// shortcut matches a pad-substitution branch below and fires from a held
		// gamepad button. Unbinding debug mode fired every other unbound binding
		// with it, including all nine playback keys, which ship unbound.
		if (keyCode < 0) {
			return false;
		}
		if (keyCode == inputBindings.debugModeKey()) {
			if (logicalOverride != null) {
				return logicalOverride.debugModeTogglePressed();
			}
			return isRawKeyPressed(keyCode) || gamepadInputManager.isDebugModeTogglePressed();
		}
		if (keyCode == inputBindings.frameStepKey()) {
			return isRawKeyPressed(keyCode) || gamepadInputManager.isFrameStepTogglePressed();
		}
		return isRawKeyPressed(keyCode);
	}

	/**
	 * Edge-triggered: true only on the frame the gamepad Back/Select/View button on
	 * the primary connected pad transitions to held. Scoped to the main-menu
	 * options-panel Tab toggle ({@code MasterTitleScreen} / {@code LaunchConfigPanel}) —
	 * unlike {@link #isKeyPressed(int)}'s debug-mode/frame-step wiring, this is not a
	 * blanket substitute for every keyboard use of Tab (editor toggle, special stage
	 * entry, art viewer), which are unrelated screens/modes.
	 */
	public boolean isGamepadBackButtonPressed() {
		return logicalOverride == null && gamepadInputManager.isBackButtonPressed();
	}

	private boolean isRawKeyPressed(int keyCode) {
		if (keyCode >= 0 && keyCode < MAX_KEYS) {
			return keys[keyCode] && !previousKeys[keyCode];
		}
		return false;
	}

	/**
	 * Returns true when at least one key transitioned from not-pressed to
	 * pressed during the current frame. Mirrors {@link #isKeyPressed(int)}
	 * but checks all keys at once. Used by full-screen prompts that
	 * accept any input to dismiss.
	 */
	public boolean isAnyKeyJustPressed() {
		for (int i = 0; i < MAX_KEYS; i++) {
			if (keys[i] && !previousKeys[i]) {
				return true;
			}
		}
		return false;
	}

	public boolean isShiftDown() {
		if (logicalOverride != null) {
			return logicalOverride.debugShiftDown();
		}
		return isKeyDown(GLFW_KEY_LEFT_SHIFT) || isKeyDown(GLFW_KEY_RIGHT_SHIFT);
	}

	public boolean isControlDown() {
		if (logicalOverride != null) {
			return logicalOverride.debugControlDown();
		}
		return isKeyDown(GLFW_KEY_LEFT_CONTROL) || isKeyDown(GLFW_KEY_RIGHT_CONTROL);
	}

	public boolean isAltDown() {
		if (logicalOverride != null) {
			return logicalOverride.debugAltDown();
		}
		return isKeyDown(GLFW_KEY_LEFT_ALT) || isKeyDown(GLFW_KEY_RIGHT_ALT);
	}

	public boolean isSuperDown() {
		if (logicalOverride != null) {
			return logicalOverride.debugSuperDown();
		}
		return isKeyDown(GLFW_KEY_LEFT_SUPER) || isKeyDown(GLFW_KEY_RIGHT_SUPER);
	}

	public boolean isAnyModifierDown() {
		return isShiftDown() || isControlDown() || isAltDown() || isSuperDown();
	}

	public boolean isKeyPressedWithoutModifiers(int keyCode) {
		return isKeyPressed(keyCode) && !isAnyModifierDown();
	}

	/**
	 * Drops all key state. A key is otherwise cleared only by an observed
	 * GLFW_RELEASE, and the release for a window-switch modifier goes to the
	 * window that took focus, so the modifier would latch for the rest of the
	 * process and disable every shortcut requiring no modifier held.
	 *
	 * <p>{@code previousKeys} is cleared too, or the first press after the clear
	 * is not seen as a rising edge. Mouse state is untouched — a focus change
	 * does not strand a mouse button the way it strands a modifier.
	 */
	public void clearKeyState() {
		java.util.Arrays.fill(keys, false);
		java.util.Arrays.fill(previousKeys, false);
	}

	public double getMouseX() {
		return mouseX;
	}

	public double getMouseY() {
		return mouseY;
	}

	public boolean isMouseButtonDown(int button) {
		if (button >= 0 && button < MAX_MOUSE_BUTTONS) {
			return mouseButtons[button];
		}
		return false;
	}

	public boolean isMouseButtonPressed(int button) {
		if (button >= 0 && button < MAX_MOUSE_BUTTONS) {
			return mouseButtons[button] && !previousMouseButtons[button];
		}
		return false;
	}

	public boolean hasMouseInputSeen() {
		return mouseInputSeen;
	}

	public void setLogicalOverride(LogicalInputSnapshot override) {
		logicalOverride = override != null ? override : LogicalInputSnapshot.neutral();
		logicalSnapshot = logicalOverride;
	}

	public void clearLogicalOverride() {
		logicalOverride = null;
	}

	public boolean hasLogicalOverride() {
		return logicalOverride != null;
	}

	public void refreshLogicalSnapshot() {
		inputBindings = Objects.requireNonNull(inputBindingsSource.get(), "inputBindings");
		if (logicalOverride != null) {
			gamepadInputManager.poll(inputBindings);
			logicalSnapshot = logicalOverride;
			return;
		}
		PlayerInputState keyboardP1 = keyboardInputMapper.mapPlayer1(this, inputBindings);
		PlayerInputState keyboardP2 = keyboardInputMapper.mapPlayer2(this, inputBindings);
		LogicalInputSnapshot gamepadSnapshot = gamepadInputManager.poll(inputBindings);
		PlayerInputState p1 = keyboardP1.merge(gamepadSnapshot.player1());
		PlayerInputState p2 = keyboardP2.merge(gamepadSnapshot.player2());
		logicalSnapshot = LogicalInputSnapshot.ofPlayers(p1, p2);
	}

	public LogicalInputSnapshot logical() {
		return logicalSnapshot;
	}

	public boolean menuAcceptExcludingBackAction() {
		PlayerInputState p1 = logical().player1();
		return (p1.actionPressedMask() & (InputActionMasks.ACTION_A | InputActionMasks.ACTION_B)) != 0
				|| p1.startPressed();
	}

	/**
	 * Updates the input handler state. Should be called at the end of the game loop.
	 */
	public void update() {
		System.arraycopy(keys, 0, previousKeys, 0, MAX_KEYS);
		System.arraycopy(mouseButtons, 0, previousMouseButtons, 0, MAX_MOUSE_BUTTONS);
	}

}
