package com.openggf.sprites.managers;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;

import com.openggf.audio.GameMusic;
import com.openggf.configuration.KeyChord;
import com.openggf.control.InputHandler;
import com.openggf.game.JoypadPressSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.control.PlayerInputState;
import com.openggf.game.CollisionModel;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.timer.TimerManager;
import com.openggf.game.GameStateManager;
import com.openggf.game.GameplayInputFilter;
import com.openggf.game.session.GameplayInputFilterAccess;
import com.openggf.game.rules.GameRules;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.camera.Camera;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.SpritePriorityLayerHook;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.Direction;
import com.openggf.physics.FrameCollisionPlan;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.CustomPlayablePhysics;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.SensorConfiguration;
import com.openggf.sprites.Sprite;
import com.openggf.game.GroundMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manages collection of available sprites to be provided to renderer and collision manager.
 * 
 * @author james
 * 
 */
@com.openggf.game.ModApi
public class SpriteManager implements PlayableSstDispatcher {
	private final SonicConfigurationService configService;

	private Map<String, Sprite> sprites;

	private final List<AbstractPlayableSprite> sidekicks = new ArrayList<>();
	private final Map<AbstractPlayableSprite, String> sidekickCharacterNames = new IdentityHashMap<>();
	private final Set<AbstractPlayableSprite> temporarySidekicks =
			Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Re-creation factories for temporary sidekicks (e.g. the CNZ1 throwaway
	 * carry-in Tails), keyed by sprite code. A temporary sidekick can be removed
	 * mid-level (it flew off and self-deleted), but a rewind keyframe captured
	 * while it was alive still references it. On restore (or held-rewind scrub)
	 * the snapshot may contain a temporary-sidekick code that is no longer live;
	 * the factory rebuilds the fully-wired sprite so its per-sprite rewind state
	 * can be reapplied. Survives {@link #removeTemporarySidekick}/removal (only a
	 * full {@link #clearAllSprites()} drops it) so rewind can recreate it later.
	 */
	private final Map<String, java.util.function.Supplier<AbstractPlayableSprite>>
			temporarySidekickRecreators = new java.util.HashMap<>();

	private static final SensorConfiguration[][] MOVEMENT_MAPPING_ARRAY = createMovementMappingArray();

	private static final int BUCKET_COUNT = RenderPriority.MAX - RenderPriority.MIN + 1;
	@SuppressWarnings("unchecked")
	private final List<Sprite>[] lowPriorityBuckets = new ArrayList[BUCKET_COUNT];
	@SuppressWarnings("unchecked")
	private final List<Sprite>[] highPriorityBuckets = new ArrayList[BUCKET_COUNT];
	private final List<Sprite> nonPlayableSprites = new ArrayList<>();
	private boolean bucketsDirty = true;
	private boolean lastSidekickSuppressed = false;
	// Render-input snapshot from the last bucket rebuild, used by
	// refreshRenderBucketsIfChanged() to detect priority/membership changes
	// without rebuilding every frame.
	private Sprite[] bucketSnapshotSprites = new Sprite[8];
	private int[] bucketSnapshotKeys = new int[8];
	private int bucketSnapshotCount;

	private LevelManager levelManager;

	private int testKey;
	private int debugModeKey;
	private final KeyChord superSonicDebugKey;
	private final KeyChord giveEmeraldsKey;
	private final KeyChord hyperFormDebugKey;
	private final KeyChord giveSuperEmeraldsKey;
	private int frameCounter;
	private boolean inputSuppressed;
	/**
	 * ROM {@code v_jpadpress1}/{@code v_jpadpress2} for the current level frame,
	 * published before the object pass so screen objects that poll a button
	 * (the GAME OVER card) read the same press edge the players do.
	 */
	private JoypadPressSnapshot joypadPressSnapshot = JoypadPressSnapshot.NONE;
	private boolean playbackInputSuppressed;
	// publishHeldInputForLevelEvents() and update() are two phases of one frame. Retain the
	// already-filtered immutable P1 snapshot so level events and movement cannot observe two
	// callback results for the same raw live/replay input row.
	private InputHandler publishedInputHandler;
	private PlayerInputState publishedRawPlayerOne;
	private PlayerInputState publishedEffectivePlayerOne;
	private GameplayInputFilter publishedGameplayInputFilter;
	private final IdentityHashMap<AbstractPlayableSprite, Integer> playableUpdateOrder = new IdentityHashMap<>();
	// Reused per-frame scratch for buildPlayableUpdateOrderInto(); valid only
	// for the duration of one update()/updateWithoutInput() call.
	private final List<AbstractPlayableSprite> playableOrderScratch = new ArrayList<>();
	private final IdentityHashMap<AbstractPlayableSprite, Boolean> playableScheduledScratch =
			new IdentityHashMap<>();
	private final IdentityHashMap<AbstractPlayableSprite, Boolean> playableAvailableScratch =
			new IdentityHashMap<>();
	private final IdentityHashMap<AbstractPlayableSprite, List<Runnable>> deferredPostTickMutations =
			new IdentityHashMap<>();
	private AbstractPlayableSprite activePlayableUpdate;
	private boolean playableFrameActive;

	public SpriteManager() {
		this(GameServices.configuration());
	}

	public SpriteManager(SonicConfigurationService configService) {
		this.configService = configService;
		sprites = new HashMap<String, Sprite>();
		for (int i = 0; i < BUCKET_COUNT; i++) {
			lowPriorityBuckets[i] = new ArrayList<>();
			highPriorityBuckets[i] = new ArrayList<>();
		}
		testKey = configService.getInt(SonicConfiguration.TEST);
		debugModeKey = configService.getInt(SonicConfiguration.DEBUG_MODE_KEY);
		superSonicDebugKey = configService.getKeyChord(SonicConfiguration.SUPER_SONIC_DEBUG_KEY);
		giveEmeraldsKey = configService.getKeyChord(SonicConfiguration.GIVE_EMERALDS_KEY);
		hyperFormDebugKey = configService.getKeyChord(SonicConfiguration.HYPER_FORM_DEBUG_KEY);
		giveSuperEmeraldsKey = configService.getKeyChord(SonicConfiguration.GIVE_SUPER_EMERALDS_KEY);
	}

	/**
	 * Adds the given sprite to the SpriteManager. Returns true if we have
	 * overwritten a sprite, false if we are creating a new one.
	 * <p>
	 * If {@code sprite} is a CPU-controlled {@link AbstractPlayableSprite} that is
	 * not already tracked, it is added to the sidekick list so that
	 * {@link #getSidekicks()} returns it. Prefer
	 * {@link #addSprite(AbstractPlayableSprite, String)} when the character name is
	 * known.
	 *
	 * @param sprite
	 * @return true if an existing sprite was replaced
	 */
	public boolean addSprite(Sprite sprite) {
		bucketsDirty = true;
		Sprite previous = sprites.put(sprite.getCode(), sprite);
		boolean replaced = previous != null;
		if (previous instanceof AbstractPlayableSprite previousPlayable) {
			sidekicks.remove(previousPlayable);
			sidekickCharacterNames.remove(previousPlayable);
			temporarySidekicks.remove(previousPlayable);
		}
		if (sprite instanceof AbstractPlayableSprite playable && playable.isCpuControlled()) {
			sidekicks.add(playable);
			// characterName is null when registered via the untyped overload
		}
		return replaced;
	}

	/**
	 * Adds a CPU-controlled sidekick sprite with its character name for art loading.
	 * The character name is stored for later use by art loading and VRAM bank allocation.
	 */
	public void addSprite(AbstractPlayableSprite sprite, String characterName) {
		addSprite((Sprite) sprite);
		if (sprite.isCpuControlled()) {
			// addSprite(Sprite) already added the sprite to the sidekicks list on first
			// insertion. Just record the character name.
			sidekickCharacterNames.put(sprite, characterName);
		}
	}

	public void addTemporarySidekick(AbstractPlayableSprite sprite, String characterName) {
		addSprite(sprite, characterName);
		if (sprite.isCpuControlled()) {
			temporarySidekicks.add(sprite);
		}
	}

	/**
	 * Adds a temporary sidekick along with a factory that can rebuild it from
	 * scratch (fully wired: sprite + CPU controller + behavior) keyed by code.
	 * Used by mid-level throwaway sidekicks (e.g. the CNZ1 carry-in Tails) so a
	 * rewind keyframe captured while it was alive can recreate it after it has
	 * flown off and been removed. The factory must return a CPU-controlled sprite
	 * with the same {@code getCode()}; it is invoked during rewind restore and the
	 * snapshot's per-sprite state is reapplied afterward.
	 */
	public void addTemporarySidekick(AbstractPlayableSprite sprite, String characterName,
			java.util.function.Supplier<AbstractPlayableSprite> recreator) {
		addTemporarySidekick(sprite, characterName);
		if (sprite.isCpuControlled() && recreator != null) {
			temporarySidekickRecreators.put(sprite.getCode(), recreator);
		}
	}

	public void removeTemporarySidekicks() {
		if (temporarySidekicks.isEmpty()) {
			return;
		}
		for (AbstractPlayableSprite sidekick : new ArrayList<>(temporarySidekicks)) {
			removeSprite(sidekick);
		}
	}

	/**
	 * Removes a single sidekick only if it was registered as a temporary
	 * sidekick (via {@link #addTemporarySidekick}). No-op for permanently
	 * registered sidekicks. Used by the CNZ1 solo-Sonic carry-in Tails, which
	 * deletes its own throwaway carrier once it flies off-screen (ROM
	 * {@code loc_140AC}, sonic3k.asm:26963-26969).
	 *
	 * @return true if the sprite was a temporary sidekick and was removed
	 */
	public boolean removeTemporarySidekick(AbstractPlayableSprite sprite) {
		if (sprite == null || !temporarySidekicks.contains(sprite)) {
			return false;
		}
		return removeSprite(sprite);
	}

	/**
	 * Removes the Sprite with provided code from the SpriteManager. Returns
	 * true if a Sprite was removed and false if none could be found.
	 * 
	 * @param code
	 * @return
	 */
	public boolean removeSprite(String code) {
		return removeSprite(getSprite(code));
	}

	/**
	 * Removes all sprites from the manager.
	 * Useful for test cleanup to ensure a clean state.
	 */
	public void clearAllSprites() {
		sprites.clear();
		sidekicks.clear();
		sidekickCharacterNames.clear();
		temporarySidekicks.clear();
		temporarySidekickRecreators.clear();
		bucketsDirty = true;
	}

	/**
	 * Resets mutable state without destroying the singleton instance.
	 */
	public void resetState() {
		clearAllSprites();
		levelManager = null;
		frameCounter = 0;
		inputSuppressed = false;
		joypadPressSnapshot = JoypadPressSnapshot.NONE;
		playbackInputSuppressed = false;
		lastSidekickSuppressed = false;
		playableUpdateOrder.clear();
		deferredPostTickMutations.clear();
		playableOrderScratch.clear();
		playableScheduledScratch.clear();
		playableAvailableScratch.clear();
		activePlayableUpdate = null;
		playableFrameActive = false;
		clearPublishedPlayerOne();
	}

	/**
	 * Forces the cached render buckets to rebuild on the next draw.
	 *
	 * Needed when per-frame state like high priority or bucket index changes
	 * outside add/remove paths (for example, plane switchers or zone events).
	 */
	public void invalidateRenderBuckets() {
		bucketsDirty = true;
	}

	/**
	 * Marks the cached render buckets dirty only when their inputs actually
	 * changed since the last rebuild. Playable-sprite priority is mutated from
	 * many gameplay paths (plane switchers, hurt/death, zone events, spin
	 * tubes) without notifying this manager, so there is no central mutation
	 * hook; this cheap once-per-frame scan replaces the previous unconditional
	 * per-frame rebuild while staying immune to untracked priority changes.
	 */
	public void refreshRenderBucketsIfChanged() {
		if (bucketsDirty) {
			return;
		}
		if (renderBucketInputsChanged()) {
			bucketsDirty = true;
		}
	}

	private boolean renderBucketInputsChanged() {
		return SpriteRenderBucketInputGate.inputsChanged(
				sprites, bucketSnapshotSprites, bucketSnapshotKeys, bucketSnapshotCount);
	}

	private void captureRenderBucketSnapshot() {
		int required = sprites.size();
		if (bucketSnapshotSprites.length < required) {
			int newLength = Math.max(required, bucketSnapshotSprites.length * 2);
			bucketSnapshotSprites = new Sprite[newLength];
			bucketSnapshotKeys = new int[newLength];
		}
		int position = 0;
		for (Sprite sprite : getAllSprites()) {
			bucketSnapshotSprites[position] = sprite;
			bucketSnapshotKeys[position] = SpriteRenderBucketInputGate.inputKey(sprite);
			position++;
		}
		// Release stale references beyond the live range so removed sprites
		// are not retained by the snapshot.
		for (int i = position; i < bucketSnapshotCount; i++) {
			bucketSnapshotSprites[i] = null;
		}
		bucketSnapshotCount = position;
	}

	public Collection<Sprite> getAllSprites() {
		return sprites.values();
	}

	public Sprite getSprite(String code) {
		return sprites.get(code);
	}

	/**
	 * Returns all CPU-controlled sidekick sprites in chain order.
	 * Returns empty list when sidekicks are suppressed for the current zone.
	 */
	public List<AbstractPlayableSprite> getSidekicks() {
		if (isCpuSidekickSuppressed()) {
			return List.of();
		}
		return Collections.unmodifiableList(sidekicks);
	}

	/**
	 * Returns registered CPU-controlled sidekicks without applying zone-level
	 * suppression. Trace comparison uses this to observe hidden/suppressed
	 * sidekick state without mutating it.
	 */
	public List<AbstractPlayableSprite> getRegisteredSidekicks() {
		return Collections.unmodifiableList(sidekicks);
	}

	/**
	 * Returns the character name for a sidekick (e.g. "tails", "sonic", "knuckles").
	 */
	public String getSidekickCharacterName(AbstractPlayableSprite sidekick) {
		return sidekickCharacterNames.get(sidekick);
	}


	/**
	 * Returns the current frame counter (gameplay frames since level boot).
	 * Increments at the start of each {@link #update(InputHandler)} call. Skipped
	 * when {@code stepFrame} returns early (e.g. for seamless transitions) and
	 * during {@code skipFrameFromRecording()} lag-frame skips, so this counter is
	 * <em>not</em> guaranteed to track ROM's {@code Level_frame_counter} unless
	 * the bootstrap explicitly aligns it.
	 */
	public int getFrameCounter() {
		return frameCounter;
	}

	/** Returns the active session/configured main playable participant. */
	public AbstractPlayableSprite getMainPlayable() {
		String mainCode = ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
		Sprite namedMain = sprites.get(mainCode);
		if (namedMain instanceof AbstractPlayableSprite playable && !playable.isCpuControlled()) {
			return playable;
		}

		// Compatibility for synthetic tests/tools whose sole playable uses a
		// non-character code. A second human makes the fallback intentionally
		// unresolved rather than dependent on HashMap iteration order.
		AbstractPlayableSprite main = null;
		for (Sprite sprite : sprites.values()) {
			if (!(sprite instanceof AbstractPlayableSprite playable) || playable.isCpuControlled()) {
				continue;
			}
			if (main != null && main != playable) {
				throw new IllegalStateException(
						"Cannot resolve main playable: configured/session participant '"
								+ mainCode + "' is absent and multiple non-CPU fallback candidates exist ('"
								+ main.getCode() + "', '" + playable.getCode() + "')");
			}
			main = playable;
		}
		return main;
	}

	/**
	 * Sets the gameplay frame counter. Used by trace-replay setup to align the
	 * engine's counter with the ROM's {@code Level_frame_counter} at the start
	 * of comparison so AI logic that gates on {@code (Level_frame_counter & MASK)}
	 * fires on the same frames as the ROM.
	 */
	public void setFrameCounter(int value) {
		this.frameCounter = value;
	}

	public void publishHeldInputForLevelEvents(InputHandler handler) {
		if (handler == null) {
			return;
		}
		boolean suppressInput = inputSuppressed
				|| (playbackInputSuppressed && !handler.hasLogicalOverride());
		PlayerInputState raw = handler.logical().player1();
		publishedInputHandler = handler;
		publishedRawPlayerOne = raw;
		publishedGameplayInputFilter = GameplayInputFilterAccess.currentSessionFilter();
		publishedEffectivePlayerOne = null;
		publishedEffectivePlayerOne = filterPlayerOne(raw, publishedGameplayInputFilter);
		PlayerInputState p1 = publishedEffectivePlayerOne;
		PlayerInputState p2 = handler.logical().player2();
		joypadPressSnapshot = suppressInput
				? JoypadPressSnapshot.NONE
				: new JoypadPressSnapshot(
						p1.actionPressedMask(), p1.startPressed(),
						p2.actionPressedMask(), p2.startPressed());
		int p1Held = !suppressInput ? p1.heldMask() : 0;
		boolean up = (p1Held & AbstractPlayableSprite.INPUT_UP) != 0;
		boolean down = (p1Held & AbstractPlayableSprite.INPUT_DOWN) != 0;
		boolean left = (p1Held & AbstractPlayableSprite.INPUT_LEFT) != 0;
		boolean right = (p1Held & AbstractPlayableSprite.INPUT_RIGHT) != 0;
		for (Sprite sprite : getAllSprites()) {
			if (sprite instanceof AbstractPlayableSprite playable && !playable.isCpuControlled()) {
				playable.applyQueuedControlStateForFrameStart();
				playable.setDirectionalInputPressed(up, down, left, right);
			}
		}
	}

	/** This level frame's controller press edges; {@link JoypadPressSnapshot#NONE} while input is suppressed. */
	public JoypadPressSnapshot getJoypadPressSnapshot() {
		return joypadPressSnapshot;
	}

	public void update(InputHandler handler) {
		frameCounter++;
		boolean suppressInput = inputSuppressed
				|| (playbackInputSuppressed && !handler.hasLogicalOverride());
		PlayerInputState p1 = effectivePlayerOneForUpdate(handler);
		int p1Held = !suppressInput ? p1.heldMask() : 0;
		int p1Pressed = !suppressInput ? p1.pressedMask() : 0;
		int p1ActionPressedMask = !suppressInput ? p1.actionPressedMask() : 0;
		// Controller 2 input (Tails CPU manual override / sidekick respawn).
		// ROM convention:
		//   Ctrl_2_held    = bits currently pressed THIS frame
		//   Ctrl_2_logical = bits that just transitioned from up to down ("just-pressed")
		// SidekickCpuController treats them differently: e.g. updateCatchUpFlight
		// uses controller2Logical (edge-detect) to fire the early A/B/C/START
		// trigger, while the manual-control timer uses controller2Held.
		int p2Held = 0;
		int p2Logical = 0;
		if (!suppressInput) {
			PlayerInputState p2 = handler.logical().player2();
			p2Held = p2.heldMask();
			p2Logical = p2.pressedMask();
			if (p2.startPressed()) {
				p2Logical |= 0x20;
			}
		}
		boolean testButton = !suppressInput && handler.isKeyDown(testKey);
		boolean speedUp = isDebugSpeedUpModifierDown(handler);
		boolean slowDown = isDebugSlowDownModifierDown(handler);
		boolean debugShortcutsEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
		boolean debugModePressed = debugShortcutsEnabled && handler.isKeyPressed(debugModeKey);
		boolean superSonicDebugPressed = debugShortcutsEnabled
				&& (isChordPressed(handler, superSonicDebugKey)
				|| isChordPressed(handler, hyperFormDebugKey));
		boolean giveEmeraldsPressed = debugShortcutsEnabled
				&& isChordPressed(handler, giveEmeraldsKey);
		boolean giveSuperEmeraldsPressed = debugShortcutsEnabled
				&& isChordPressed(handler, giveSuperEmeraldsKey);

		// Give all chaos emeralds (debug)
		if (giveEmeraldsPressed) {
			var gsm = currentGameStateManager();
			if (!gsm.hasAllEmeralds()) {
				for (int i = 0; i < gsm.getChaosEmeraldCount(); i++) {
					gsm.markEmeraldCollected(i);
				}
				GameServices.audio().playMusic(GameMusic.EMERALD);
			}
		}
		if (giveSuperEmeraldsPressed) {
			var gsm = currentGameStateManager();
			if (!gsm.hasAllSuperEmeralds()) {
				for (int i = 0; i < gsm.getChaosEmeraldCount(); i++) {
					gsm.markSuperEmeraldCollected(i);
				}
				GameServices.audio().playMusic(GameMusic.EMERALD);
			}
		}

		processPlayableSlots(PlayableDispatchContext.ordinary(
				ProcessSpritesEpoch.ordinary(
						frameCounter,
						currentObjectDispatchOrdinal()),
				new InitialPlayableInput(
						p1Held, p1Pressed, p1ActionPressedMask, p2Held, p2Logical, true),
				testButton,
				speedUp,
				slowDown,
				debugModePressed,
				superSonicDebugPressed));
	}

	private static boolean isChordPressed(InputHandler handler, KeyChord chord) {
		return chord.isBound()
				&& handler.isKeyPressed(chord.keyCode())
				&& chord.matchesModifiers(handler.isShiftDown(), handler.isControlDown(),
						handler.isAltDown(), handler.isSuperDown());
	}

	@Override
	public void processInitialPlayableSlots(
			ProcessSpritesEpoch epoch,
			InitialPlayableInput input) {
		if (epoch == null) {
			throw new IllegalArgumentException("epoch must not be null");
		}
		if (input == null) {
			throw new IllegalArgumentException("input must not be null");
		}
		if (epoch.advanceGameplayCounter()) {
			throw new IllegalArgumentException(
					"initial playable dispatch must not advance the gameplay counter");
		}
		validateInitialProcessSpritesEpoch(epoch);
		processPlayableSlots(PlayableDispatchContext.initial(epoch, input));
	}

	private void validateInitialProcessSpritesEpoch(ProcessSpritesEpoch epoch) {
		LevelManager manager = getLevelManager();
		int levelEpoch = manager != null ? manager.getFrameCounter() : 0;
		ObjectManager objects = manager != null ? manager.getObjectManager() : null;
		int completedObjectOrdinal = currentObjectDispatchOrdinal();
		int setupObjectOrdinal = objects != null
				&& objects.hasActiveInitialProcessSpritesDispatch()
				? completedObjectOrdinal
				: completedObjectOrdinal + 1;
		if (frameCounter != epoch.nativeLevelEpoch()
				|| levelEpoch != epoch.nativeLevelEpoch()
				|| setupObjectOrdinal != epoch.objectDispatchOrdinal()) {
			throw new IllegalArgumentException(
					"initial Process_Sprites epoch does not match production counters: "
							+ "expected level/sprite/object="
							+ levelEpoch + "/" + frameCounter + "/" + setupObjectOrdinal
							+ ", received " + epoch.nativeLevelEpoch() + "/"
							+ epoch.nativeLevelEpoch() + "/" + epoch.objectDispatchOrdinal());
		}
	}

	private void processPlayableSlots(PlayableDispatchContext context) {
		// Note: bucketsDirty is already marked in addSprite()/removeSprite(),
		// no need to unconditionally mark dirty every frame.
		Collection<Sprite> sprites = getAllSprites();
		List<AbstractPlayableSprite> playables = buildPlayableUpdateOrderInto(
				sprites, sidekicks, isCpuSidekickSuppressed(),
				playableOrderScratch, playableScheduledScratch, playableAvailableScratch);
		beginPlayableFrame(playables);

		InitialPlayableInput input = context.input();
		int p1Held = input.p1Held();
		boolean up = (p1Held & AbstractPlayableSprite.INPUT_UP) != 0;
		boolean down = (p1Held & AbstractPlayableSprite.INPUT_DOWN) != 0;
		boolean left = (p1Held & AbstractPlayableSprite.INPUT_LEFT) != 0;
		boolean right = (p1Held & AbstractPlayableSprite.INPUT_RIGHT) != 0;
		boolean jump = (p1Held & AbstractPlayableSprite.INPUT_JUMP) != 0;
		boolean rawJumpPress = (input.p1Pressed() & AbstractPlayableSprite.INPUT_JUMP) != 0;
		LevelManager levelManager = getLevelManager();
		int cadence = context.epoch().nativeLevelEpoch();
		try {
			for (AbstractPlayableSprite playable : playables) {
				activePlayableUpdate = playable;
				try {
					if (context.initialAssemblySlot()) {
						initializeInitialAssemblyPlayableSlot(playable);
					}
					if (input.consumeQueuedObjectControlState()) {
						playable.applyQueuedControlStateForFrameStart();
					}
					if (context.debugModePressed()) {
						playable.toggleDebugMode();
					}
					// Super Sonic debug toggle (only for player 1, not CPU sidekicks)
					if (context.superSonicDebugPressed() && !playable.isCpuControlled()) {
						var superCtrl = playable.getSuperStateController();
						if (superCtrl != null) {
							if (superCtrl.isSuper()) {
								superCtrl.debugDeactivate();
							} else {
								superCtrl.debugActivate();
							}
						}
					}

					if (context.initialAssemblySlot()) {
						publishInitialAssemblyInput(playable, input);
						continue;
					}

					boolean effectiveUp, effectiveDown, effectiveLeft, effectiveRight, effectiveJump, effectiveTest;
					boolean skipCpuPhysicsThisFrame = false;
					SidekickCpuController cpuControllerForDiagnostics = null;

					if (context.runCpuControllers()
							&& playable.isCpuControlled()
							&& playable.getCpuController() != null) {
						// CPU-controlled sprite: run AI to generate virtual input
						var cpuController = playable.getCpuController();
						cpuControllerForDiagnostics = cpuController;
						playable.capturePreCpuControlSnapshot();
						boolean isFirstSidekick = !sidekicks.isEmpty() && sidekicks.getFirst() == playable;
						if (isFirstSidekick) {
							cpuController.setController2Input(input.p2Held(), input.p2Pressed());
						}
						cpuController.update(cadence);
						skipCpuPhysicsThisFrame = cpuController.consumeSkipPhysicsThisFrame();

						boolean aiUp = cpuController.getInputUp();
						boolean aiDown = cpuController.getInputDown();
						boolean aiLeft = cpuController.getInputLeft();
						boolean aiRight = cpuController.getInputRight();
						boolean aiJump = cpuController.getInputJump();
						boolean aiJumpPress = cpuController.getInputJumpPress();

						boolean forcedRight = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_RIGHT)
								|| playable.isForceInputRight();
						boolean forcedLeft = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_LEFT);
						boolean forcedUp = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_UP);
						boolean forcedDown = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_DOWN);
						boolean forcedJump = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP);
						effectiveRight = aiRight || forcedRight;
						effectiveLeft = (aiLeft || forcedLeft) && !forcedRight;
						effectiveUp = aiUp || forcedUp;
						effectiveDown = aiDown || forcedDown;
						effectiveJump = aiJump || forcedJump;
						effectiveTest = false;

						publishInputState(playable,
								aiUp, aiDown, aiLeft, aiRight, aiJump, aiJumpPress,
								effectiveUp, effectiveDown, effectiveLeft, effectiveRight, effectiveJump,
								true, aiJumpPress);

						// If approaching (respawn in progress) and the strategy handles movement
						// directly (Tails fly-in, Knuckles glide), skip normal physics.
						// Strategies that need physics (Sonic walk/spindash) fall through.
						if (skipCpuPhysicsThisFrame) {
							applyScreenYWrapValueAfterControl(playable);
							playable.getAnimationManager().update(cadence);
							tickDisplayPhaseTimers(playable);
							playable.tickStatus();
							playable.endOfTick();
							continue;
						}
						if (cpuController.isApproaching()
								&& !cpuController.getRespawnStrategy().requiresPhysics()) {
							applyScreenYWrapValueAfterControl(playable);
							playable.getAnimationManager().update(cadence);
							tickDisplayPhaseTimers(playable);
							playable.tickStatus();
							playable.endOfTick();
							continue;
						}
						if (aiJumpPress) {
							playable.setForcedJumpPress(true);
						}
					} else {
						// Player-controlled sprite, or the historical
						// updateWithoutInput path where CPU dispatch is intentionally skipped.
						boolean controlLocked = playable.isControlLocked();
						boolean forcedRight = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_RIGHT)
								|| playable.isForceInputRight();
						boolean forcedLeft = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_LEFT);
						boolean forcedUp = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_UP);
						boolean forcedDown = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_DOWN);
						boolean forcedJump = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP);
						boolean recordedLogicalJumpPress = false;
						if (context.useEffectiveRuntimeInput()) {
							effectiveRight = (!controlLocked && right) || forcedRight;
							effectiveLeft = ((!controlLocked && left) || forcedLeft) && !forcedRight;
							effectiveUp = (!controlLocked && up) || forcedUp;
							effectiveDown = (!controlLocked && down) || forcedDown;
							effectiveJump = (!controlLocked && jump) || forcedJump;
							effectiveTest = !controlLocked && context.testButton();
							recordedLogicalJumpPress = !controlLocked
									&& input.p1ActionPressedMask() != 0;
						} else {
							effectiveRight = false;
							effectiveLeft = false;
							effectiveUp = false;
							effectiveDown = false;
							effectiveJump = false;
							effectiveTest = false;
						}

						// Store RAW input state for objects (like flippers) that need to query
						// button state even when control is locked. This matches ROM behavior
						// where obj_control locks movement but objects can still read button state.
						boolean logicalJumpPress = playable.isForcedJumpPress()
								|| recordedLogicalJumpPress;
						publishInputState(playable,
								up, down, left, right, jump, rawJumpPress,
								effectiveUp, effectiveDown, effectiveLeft, effectiveRight, effectiveJump,
								true, logicalJumpPress);
						if (recordedLogicalJumpPress) {
							playable.setForcedJumpPress(true);
						}
					}

					tickPlayablePhysics(playable, effectiveUp, effectiveDown, effectiveLeft,
							effectiveRight, effectiveJump, effectiveTest,
							context.speedUp(), context.slowDown(),
							levelManager, cadence);
					levelManager.updateZoneFeaturesAfterPlayablePhysics(playable);
					if (cpuControllerForDiagnostics != null) {
						cpuControllerForDiagnostics.recordDiagnosticPostPhysics();
					}
				} finally {
					runDeferredPostTickMutations(playable);
					activePlayableUpdate = null;
				}
			}
			if (context.sweepTemporarySidekicks()) {
				sweepFlyoffDespawnedTemporarySidekicks();
			}
		} finally {
			endPlayableFrame();
		}
	}

	private PlayerInputState effectivePlayerOneForUpdate(InputHandler handler) {
		PlayerInputState raw = handler.logical().player1();
		GameplayInputFilter currentFilter = GameplayInputFilterAccess.currentSessionFilter();
		PlayerInputState effective = handler == publishedInputHandler && raw == publishedRawPlayerOne
				&& currentFilter == publishedGameplayInputFilter
				&& publishedEffectivePlayerOne != null
				? publishedEffectivePlayerOne
				: filterPlayerOne(raw, currentFilter);
		clearPublishedPlayerOne();
		return effective;
	}

	private PlayerInputState filterPlayerOne(PlayerInputState raw, GameplayInputFilter filter) {
		return Objects.requireNonNull(filter.filter(raw),
				"gameplay input filter result");
	}

	private void clearPublishedPlayerOne() {
		publishedInputHandler = null;
		publishedRawPlayerOne = null;
		publishedEffectivePlayerOne = null;
		publishedGameplayInputFilter = null;
	}

	private void initializeInitialAssemblyPlayableSlot(AbstractPlayableSprite playable) {
		if (!playable.isCpuControlled()) {
			// Sonic_Init temporarily applies the Player_2 spawn offset, calls
			// Reset_Player_Position_Array, then restores Player_1
			// (sonic3k.asm:21931-21941,22166-22193).
			short originalX = playable.getCentreX();
			short originalY = playable.getCentreY();
			playable.setCentreXPreserveSubpixel((short) (originalX - 0x20));
			playable.setCentreYPreserveSubpixel((short) (originalY + 4));
			playable.resetPositionAndStatTableHistory();
			playable.setCentreXPreserveSubpixel(originalX);
			playable.setCentreYPreserveSubpixel(originalY);
			playable.setObjectRoutineOverride(null);
			playable.getDrowningController().reset();
			playable.setFlipSpeed(4);
			playable.getAnimationManager().publishPreviousAnimationId(0);
			return;
		}

		// Player_2 is the first CPU slot after Player_1. Tails_Init resets the
		// CPU globals and installs the spawn-offset state but returns before the
		// normal delayed-follow CPU routine (sonic3k.asm:26101-26156).
		if (sidekicks.isEmpty() || sidekicks.getFirst() != playable) {
			return;
		}
		AbstractPlayableSprite leader = getMainPlayable();
		SidekickCpuController cpu = playable.getCpuController();
		if (leader == null || cpu == null) {
			return;
		}
		boolean controlLocked = playable.isControlLocked();
		boolean objectControlled = playable.isObjectControlled();
		boolean objectControlAllowsCpu = playable.isObjectControlAllowsCpu();
		boolean objectControlSuppressesMovement = playable.isObjectControlSuppressesMovement();
		cpu.resetForInitialProcessSpritesSlot();
		playable.setObjectControlled(objectControlled);
		playable.setObjectControlAllowsCpu(objectControlAllowsCpu);
		playable.setObjectControlSuppressesMovement(objectControlSuppressesMovement);
		playable.setControlLocked(controlLocked);
		playable.setCentreXPreserveSubpixel((short) (leader.getCentreX() - 0x20));
		playable.setCentreYPreserveSubpixel((short) (leader.getCentreY() + 4));
		playable.setXSpeed((short) 0);
		playable.setYSpeed((short) 0);
		playable.setGSpeed((short) 0);
		playable.setAir(leader.getAir());
		playable.setObjectRoutineOverride(null);
		playable.getDrowningController().reset();
		playable.setFlipSpeed(4);
		playable.getAnimationManager().publishPreviousAnimationId(0);
		cpu.suppressInitialLevelEventPresentationIfNeeded();
	}

	private static void publishInitialAssemblyInput(
			AbstractPlayableSprite playable,
			InitialPlayableInput input) {
		int held = playable.isCpuControlled() ? input.p2Held() : input.p1Held();
		boolean rawUp = (held & AbstractPlayableSprite.INPUT_UP) != 0;
		boolean rawDown = (held & AbstractPlayableSprite.INPUT_DOWN) != 0;
		boolean rawLeft = (held & AbstractPlayableSprite.INPUT_LEFT) != 0;
		boolean rawRight = (held & AbstractPlayableSprite.INPUT_RIGHT) != 0;
		boolean rawJump = (held & AbstractPlayableSprite.INPUT_JUMP) != 0;
		boolean rawJumpPress = ((playable.isCpuControlled() ? input.p2Pressed() : input.p1Pressed())
				& AbstractPlayableSprite.INPUT_JUMP) != 0;
		boolean controlLocked = playable.isControlLocked();
		boolean forcedRight = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_RIGHT)
				|| playable.isForceInputRight();
		boolean forcedLeft = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_LEFT);
		boolean forcedUp = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_UP);
		boolean forcedDown = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_DOWN);
		boolean forcedJump = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP);
		boolean effectiveRight = (!controlLocked && rawRight) || forcedRight;
		boolean effectiveLeft = ((!controlLocked && rawLeft) || forcedLeft) && !forcedRight;
		boolean effectiveUp = (!controlLocked && rawUp) || forcedUp;
		boolean effectiveDown = (!controlLocked && rawDown) || forcedDown;
		boolean effectiveJump = (!controlLocked && rawJump) || forcedJump;
		publishInputState(playable,
				rawUp, rawDown, rawLeft, rawRight, rawJump, rawJumpPress,
				effectiveUp, effectiveDown, effectiveLeft, effectiveRight, effectiveJump,
				false, false);
	}

	private int currentObjectDispatchOrdinal() {
		LevelManager manager = getLevelManager();
		ObjectManager objects = manager != null ? manager.getObjectManager() : null;
		return objects != null ? objects.getFrameCounter() : 0;
	}

	/**
	 * Post-dynamic-object fixed-slot playable pass, in ROM SST order.
	 *
	 * <p>ROM places Tails' tails (Obj05) and the skid/spindash dust in the
	 * fixed-in-level object RAM that begins AFTER the dynamic object RAM:
	 * S2 {@code LevelOnly_Object_RAM: Tails_Tails} follows {@code Object_RAM_End}
	 * / {@code Dynamic_Object_RAM_End} (docs/s2disasm/s2.constants.asm:1144-1152),
	 * and S3K {@code Level_object_RAM: Tails_tails} then {@code Dust} follow
	 * {@code Dynamic_object_RAM_end} (docs/skdisasm/sonic3k.constants.asm:307-317).
	 * Both therefore execute after every dynamic level object, and Tails' tails
	 * executes before the dust.
	 */
	public void advancePlayableFixedSlotsAfterObjectExecution() {
		// These slots are the tail of the SAME ROM object walk that just ran, so
		// they execute only if that walk reached them. A routine that rewrites
		// the loop counter can end the walk inside the dynamic window, and then
		// the ROM never dispatches Tails' tails or the fixed dust at all that
		// frame -- see ObjectManager#objectLoopReachedFixedInLevelSlots.
		if (!objectLoopReachedFixedInLevelSlots()) {
			return;
		}
		advanceTailsTailsAfterObjectExecution();
		advanceFixedSkidDustAfterObjectExecution();
	}

	private boolean objectLoopReachedFixedInLevelSlots() {
		LevelManager levelManager = getLevelManager();
		ObjectManager objects = levelManager != null ? levelManager.getObjectManager() : null;
		return objects == null || objects.objectLoopReachedFixedInLevelSlots();
	}

	/**
	 * ROM Obj05_Main: reads {@code anim(a2)} from its parent at ITS OWN (late)
	 * execution point (docs/s2disasm/s2.asm:41735), then unconditionally runs
	 * {@code Tails_Animate_Part2} and {@code LoadTailsTailsDynPLC}
	 * (docs/s2disasm/s2.asm:41756-41763). S3K's Obj_Tails_Tail_Main is the same
	 * shape (docs/skdisasm/sonic3k.asm:30055-30071). Running this inside the
	 * sidekick's own animation pass read the parent anim one dispatch too early
	 * and minted DPLC edges the ROM never queues.
	 */
	public void advanceTailsTailsAfterObjectExecution() {
		AbstractPlayableSprite main = getMainPlayable();
		if (main != null && main.getTailsTailsController() != null) {
			main.getTailsTailsController().update();
		}
		for (AbstractPlayableSprite sidekick : sidekicks) {
			if (sidekick != null && sidekick.getTailsTailsController() != null) {
				sidekick.getTailsTailsController().update();
			}
		}
	}
	public void advanceFixedSkidDustAfterObjectExecution() {
		Collection<Sprite> sprites = getAllSprites();
		List<AbstractPlayableSprite> playables = buildPlayableUpdateOrderInto(
				sprites, sidekicks, isCpuSidekickSuppressed(),
				playableOrderScratch, playableScheduledScratch, playableAvailableScratch);
		for (AbstractPlayableSprite playable : playables) {
			if (!(playable.getMovementManager() instanceof PlayableSpriteMovement movement)) {
				continue;
			}
			movement.advanceFixedSkidDustWhileStopAnimPersists(frameCounter);
		}
	}

	public void processInitialTailsFixedSlot() {
		// S3K's Level_object_RAM slot 97 is Tails_tails. In a solo Tails
		// session its parent is the main playable; in a Sonic-and-Tails session
		// it is the Tails sidekick. Select the parent that owns Obj05 rather than
		// assuming that the slot always belongs to player two.
		AbstractPlayableSprite owner = getMainPlayable();
		if (owner == null || owner.getTailsTailsController() == null) {
			owner = sidekicks.isEmpty() ? null : sidekicks.getFirst();
		}
		if (owner != null && owner.getTailsTailsController() != null) {
			owner.getTailsTailsController().update();
		}
	}

	public void processInitialDustFixedSlot(int playerIndex) {
		AbstractPlayableSprite owner;
		if (playerIndex == 0) {
			owner = getMainPlayable();
		} else if (playerIndex == 1) {
			owner = sidekicks.isEmpty() ? null : sidekicks.getFirst();
		} else {
			throw new IllegalArgumentException("fixed dust slot player index must be 0 or 1");
		}
		if (owner == null || owner.getSpindashDustController() == null
				|| owner.getSpindashDustController().fixedSlotIndex() < 0) {
			return;
		}
		owner.getSpindashDustController().update();
		if (owner.getMovementManager() instanceof PlayableSpriteMovement movement) {
			movement.advanceFixedSkidDustWhileStopAnimPersists(frameCounter);
		}
	}

	/**
	 * Advances the native playable-slot prefix exposed by an interrupted
	 * {@code Process_Sprites} pass.
	 *
	 * <p>{@code Sonic_RecordPos} runs in the main player's slot before
	 * {@code Animate_*}; a replay row that proves this prefix ran must advance
	 * both the leader history ring and playable animations without running
	 * later dynamic-object slots.
	 */
	public void advancePlayableSlotPrefix() {
		AbstractPlayableSprite leader = getMainPlayable();
		if (leader != null) {
			leader.recordFollowerHistoryForTick();
			leader.clearFollowerHistoryRecordedFlag();
		}
		int animationFrame = getFrameCounter();
		for (Sprite candidate : getAllSprites()) {
			if (candidate instanceof AbstractPlayableSprite playable) {
				playable.getAnimationManager().update(animationFrame);
			}
		}
	}

	/**
	 * Removes throwaway carry-in sidekicks (e.g. the CNZ1 solo-Sonic Tails)
	 * once their CPU controller has flown off-screen and flagged itself for
	 * despawn (ROM {@code loc_140AC}, sonic3k.asm:26963-26969). Run after the
	 * playable update loop so the roster is mutated outside iteration; the
	 * controller only sets the flag and never reaches back into global services
	 * to mutate the roster.
	 */
	private void sweepFlyoffDespawnedTemporarySidekicks() {
		if (temporarySidekicks.isEmpty()) {
			return;
		}
		for (AbstractPlayableSprite sidekick : new ArrayList<>(temporarySidekicks)) {
			SidekickCpuController controller = sidekick.getCpuController();
			if (controller != null && controller.isTransientFlyoffDespawned()) {
				removeTemporarySidekick(sidekick);
			}
		}
	}

	public void updateWithoutInput() {
		frameCounter++;
		processPlayableSlots(PlayableDispatchContext.ordinaryWithoutInput(
				ProcessSpritesEpoch.ordinary(
						frameCounter,
						currentObjectDispatchOrdinal())));
	}

	/**
	 * Runs only the freshly created main playable through the native object
	 * dispatch that precedes the first counted gameplay frame.
	 *
	 * <p>The player slot has just been cleared in the ROM, so status starts on
	 * the ground with angle zero even when no floor exists at the spawn point.
	 * The normal movement code discovers that condition during this pass (for
	 * example S1 SBZ3 at {@code y_pos=0}), while the normal animation code
	 * publishes the corresponding Wait mapping. The gameplay frame counter is
	 * restored because this pass occurs before the ROM clears/starts its level
	 * counter.
	 */
	public void warmUpFreshMainPlayableOnly(int frames,
			LevelManager levelManager,
			AbstractPlayableSprite mainPlayable) {
		if (frames <= 0 || levelManager == null || mainPlayable == null) {
			return;
		}
		int savedFrameCounter = frameCounter;
		try {
			mainPlayable.setAir(false);
			mainPlayable.setAngle((byte) 0);
			mainPlayable.setGroundMode(GroundMode.GROUND);
			mainPlayable.setOnObject(false);
			mainPlayable.setPushing(false);
			// The ROM cleared both anim and prev_anim with the object slot.
			// Reused engine sprite instances must likewise force the first
			// native animation dispatch to initialize its script even when the
			// prior level happened to end on the same animation id.
			mainPlayable.forceAnimationRestart();
			for (int i = 0; i < frames; i++) {
				frameCounter++;
				List<AbstractPlayableSprite> mainOnly = List.of(mainPlayable);
				beginPlayableFrame(mainOnly);
				try {
					activePlayableUpdate = mainPlayable;
					mainPlayable.applyQueuedControlStateForFrameStart();
					publishInputState(mainPlayable,
							false, false, false, false, false, false,
							false, false, false, false, false,
							false, false);
					tickPlayablePhysics(mainPlayable,
							false, false, false, false, false, false, false, false,
							levelManager, frameCounter);
					levelManager.updateZoneFeaturesAfterPlayablePhysics(mainPlayable);
				} finally {
					runDeferredPostTickMutations(mainPlayable);
					activePlayableUpdate = null;
					endPlayableFrame();
				}
			}
		} finally {
			frameCounter = savedFrameCounter;
		}
	}

	/**
	 * Runs the CPU-controlled sidekicks through the short object prelude that
	 * happens while the ROM title card still holds the main player. The main
	 * player and BK2 cursor are intentionally untouched; callers restore
	 * {@link #frameCounter} afterwards because ROM's Level_frame_counter has not
	 * started advancing during this prelude.
	 *
	 * <p>ROM runs Obj01_Control before Obj02_Control inside the title-card
	 * RunObjects loop, so Sonic_RecordPos overwrites the next Pos_table slot
	 * with Sonic's current centre before Tails_CPU_Normal reads its delayed
	 * lookup target. Mirror that ordering by ticking the leader's follower
	 * history once per prelude frame.
	 */
	public void warmUpCpuSidekicksOnly(int frames, LevelManager levelManager) {
		warmUpCpuSidekicksOnly(frames, levelManager, null);
	}

	public void warmUpCpuSidekicksOnly(int frames,
			LevelManager levelManager,
			AbstractPlayableSprite leaderFallback) {
		if (frames <= 0 || levelManager == null) {
			return;
		}
		int savedFrameCounter = frameCounter;
		try {
			for (int i = 0; i < frames; i++) {
				frameCounter++;
				List<AbstractPlayableSprite> activeSidekicks = new ArrayList<>(getSidekicks());
				// ROM Obj01_Control calls Sonic_RecordPos before Obj02_Control
				// runs. Match that ordering so Tails_CPU_Normal's 16-frame
				// delayed lookup sees the freshly written Pos_table entry.
				AbstractPlayableSprite leaderToRecord = null;
				for (AbstractPlayableSprite playable : activeSidekicks) {
					var cpuController = playable.getCpuController();
					if (cpuController != null) {
						leaderToRecord = cpuController.getLeader();
						break;
					}
				}
				if (leaderToRecord == null) {
					leaderToRecord = leaderFallback;
				}
				if (leaderToRecord == null) {
					for (Sprite sprite : getAllSprites()) {
						if (sprite instanceof AbstractPlayableSprite playable
								&& !playable.isCpuControlled()) {
							leaderToRecord = playable;
							break;
						}
					}
				}
				if (leaderToRecord != null) {
					// The prelude does NOT run the leader's full tick (no
					// endOfTick() to clear the per-tick gate), so reset the
					// gate manually after writing so the next prelude frame
					// can record again.
					leaderToRecord.recordFollowerHistoryForTick();
					leaderToRecord.clearFollowerHistoryRecordedFlag();
				}
				beginPlayableFrame(activeSidekicks);
				try {
					for (AbstractPlayableSprite playable : activeSidekicks) {
						if (playable.getCpuController() == null) {
							continue;
						}
						activePlayableUpdate = playable;
						try {
							playable.applyQueuedControlStateForFrameStart();
							var cpuController = playable.getCpuController();
							cpuController.update(frameCounter);
							boolean skipCpuPhysicsThisFrame = cpuController.consumeSkipPhysicsThisFrame();

							boolean aiUp = cpuController.getInputUp();
							boolean aiDown = cpuController.getInputDown();
							boolean aiLeft = cpuController.getInputLeft();
							boolean aiRight = cpuController.getInputRight();
							boolean aiJump = cpuController.getInputJump();
							boolean aiJumpPress = cpuController.getInputJumpPress();
							boolean forcedRight = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_RIGHT)
									|| playable.isForceInputRight();
							boolean forcedLeft = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_LEFT);
							boolean forcedUp = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_UP);
							boolean forcedDown = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_DOWN);
							boolean forcedJump = playable.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP);
							boolean effectiveRight = aiRight || forcedRight;
							boolean effectiveLeft = (aiLeft || forcedLeft) && !forcedRight;
							boolean effectiveUp = aiUp || forcedUp;
							boolean effectiveDown = aiDown || forcedDown;
							boolean effectiveJump = aiJump || forcedJump;

							publishInputState(playable,
									aiUp, aiDown, aiLeft, aiRight, aiJump, aiJumpPress,
									effectiveUp, effectiveDown, effectiveLeft, effectiveRight, effectiveJump,
									true, aiJumpPress);
							if (skipCpuPhysicsThisFrame) {
								playable.getAnimationManager().update(frameCounter);
								tickDisplayPhaseTimers(playable);
								playable.tickStatus();
								playable.endOfTick();
								continue;
							}
							if (cpuController.isApproaching()
									&& !cpuController.getRespawnStrategy().requiresPhysics()) {
								playable.getAnimationManager().update(frameCounter);
								tickDisplayPhaseTimers(playable);
								playable.tickStatus();
								playable.endOfTick();
								continue;
							}
							if (cpuController.getInputJumpPress()) {
								playable.setForcedJumpPress(true);
							}
							tickPlayablePhysics(playable, effectiveUp, effectiveDown,
									effectiveLeft, effectiveRight, effectiveJump, false,
									false, false, levelManager, frameCounter);
							levelManager.updateZoneFeaturesAfterPlayablePhysics(playable);
						} finally {
							runDeferredPostTickMutations(playable);
							activePlayableUpdate = null;
						}
					}
				} finally {
					endPlayableFrame();
				}
			}
		} finally {
			frameCounter = savedFrameCounter;
		}
	}

	/**
	 * Defers a cross-playable mutation until the target sprite has finished its
	 * current physics slot in this frame.
	 * <p>
	 * ROM touch handlers can mutate another character's status during the current
	 * player's ReactToItem slot. When the target player's slot still lies ahead in
	 * the engine's update order, applying that mutation immediately would let the
	 * later player tick consume it one frame too early.
	 */
	public void deferCrossPlayableMutationUntilPostTick(AbstractPlayableSprite target, Runnable mutation) {
		if (target == null || mutation == null) {
			return;
		}
		if (!playableFrameActive) {
			mutation.run();
			return;
		}
		Integer targetOrder = playableUpdateOrder.get(target);
		Integer activeOrder = activePlayableUpdate != null ? playableUpdateOrder.get(activePlayableUpdate) : null;
		if (targetOrder == null || activeOrder == null || targetOrder <= activeOrder) {
			mutation.run();
			return;
		}
		deferredPostTickMutations.computeIfAbsent(target, ignored -> new ArrayList<>()).add(mutation);
	}

	private void beginPlayableFrame(List<AbstractPlayableSprite> playables) {
		playableFrameActive = true;
		playableUpdateOrder.clear();
		deferredPostTickMutations.clear();
		activePlayableUpdate = null;
		for (int i = 0; i < playables.size(); i++) {
			AbstractPlayableSprite playable = playables.get(i);
			playableUpdateOrder.put(playable, i);
			// Snapshot Status_OnObj before any player tick runs so cross-playable
			// reads (e.g. Tails_CPU_Control follow-steering, sonic3k.asm:26688-26700,
			// s2.asm:38933+) see the leader's bit as it stood mid-frame, before
			// Sonic_Jump-driven engine clears (PlayableSpriteMovement.doJump and
			// the air-unseat path in ObjectManager.processInlineObjectForPlayer)
			// have run. ROM only clears Status_OnObj later in solid-object
			// processing (sub_1FF1E sonic3k.asm:44306-44319, loc_1FFC4
			// sonic3k.asm:44369-44381).
			playable.captureOnObjectAtFrameStart();
		}
	}

	private void endPlayableFrame() {
		playableFrameActive = false;
		playableUpdateOrder.clear();
		deferredPostTickMutations.clear();
		activePlayableUpdate = null;
	}

	private void runDeferredPostTickMutations(AbstractPlayableSprite playable) {
		List<Runnable> deferred = deferredPostTickMutations.remove(playable);
		if (deferred == null) {
			return;
		}
		for (Runnable mutation : deferred) {
			mutation.run();
		}
	}

	private static void publishInputState(AbstractPlayableSprite playable,
			boolean rawUp, boolean rawDown, boolean rawLeft, boolean rawRight,
			boolean rawJump, boolean rawJumpPress,
			boolean logicalUp, boolean logicalDown, boolean logicalLeft, boolean logicalRight, boolean logicalJump,
			boolean explicitLogicalJumpPress, boolean logicalJumpPress) {
		// Scripted/demo input is ROM Ctrl_*_logical data, not a side channel.
		// Merge it before publishing the frame's logical word so a late object
		// write survives the next hardware-input sampling pass.
		logicalUp |= playable.isForcedInputActive(AbstractPlayableSprite.INPUT_UP);
		logicalDown |= playable.isForcedInputActive(AbstractPlayableSprite.INPUT_DOWN);
		logicalLeft |= playable.isForcedInputActive(AbstractPlayableSprite.INPUT_LEFT);
		logicalRight |= playable.isForcedInputActive(AbstractPlayableSprite.INPUT_RIGHT);
		logicalJump |= playable.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP);
		logicalJumpPress |= playable.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP);
		playable.setJumpInputPressed(
				rawJump || playable.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP),
				rawJumpPress || playable.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP));
		playable.setDirectionalInputPressed(rawUp, rawDown, rawLeft, rawRight);
		if (explicitLogicalJumpPress) {
			playable.setLogicalInputState(logicalUp, logicalDown, logicalLeft, logicalRight, logicalJump,
					logicalJumpPress);
		} else {
			playable.setLogicalInputState(logicalUp, logicalDown, logicalLeft, logicalRight, logicalJump);
		}
	}

	public void refreshPlayableRenderFlags(Camera camera) {
		if (camera == null) {
			return;
		}
		for (Sprite sprite : getAllSprites()) {
			if (sprite instanceof AbstractPlayableSprite playable) {
				boolean refresh = playable.shouldRefreshRenderFlagThisFrame();
				// The hurt routine's unconditional DisplaySprite owns only the
				// current BuildSprites pass.
				playable.clearHurtRoutineOwnedDisplayLatch();
				if (!refresh) {
					continue;
				}
				playable.setRenderFlagOnScreen(camera.isVisibleForRenderFlag(playable));
			}
		}
	}

	static boolean isDebugSpeedUpModifierDown(InputHandler handler) {
		return handler.isKeyDown(GLFW_KEY_LEFT_SHIFT) || handler.isKeyDown(GLFW_KEY_RIGHT_SHIFT);
	}

	static boolean isDebugSlowDownModifierDown(InputHandler handler) {
		return handler.isKeyDown(GLFW_KEY_LEFT_CONTROL) || handler.isKeyDown(GLFW_KEY_RIGHT_CONTROL);
	}

	public void draw() {
		boolean wrapEnabled = enableVerticalWrapIfNeeded();
		try {
			Collection<Sprite> sprites = getAllSprites();
			for (Sprite sprite : sprites) {
				if (isSuppressedSidekickSprite(sprite)) {
					continue;
				}
				sprite.draw();
			}
		} finally {
			if (wrapEnabled) disableVerticalWrap();
		}
	}

	public void drawLowPriority() {
		boolean wrapEnabled = enableVerticalWrapIfNeeded();
		try {
			bucketSprites();
			for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
				int idx = bucket - RenderPriority.MIN;
				for (Sprite sprite : lowPriorityBuckets[idx]) {
					sprite.draw();
				}
			}
		} finally {
			if (wrapEnabled) disableVerticalWrap();
		}
	}

	public void drawHighPriority() {
		boolean wrapEnabled = enableVerticalWrapIfNeeded();
		try {
			for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
				int idx = bucket - RenderPriority.MIN;
				for (Sprite sprite : highPriorityBuckets[idx]) {
					sprite.draw();
				}
				if (bucket == RenderPriority.MIN) {
					for (Sprite sprite : nonPlayableSprites) {
						sprite.draw();
					}
				}
			}
		} finally {
			if (wrapEnabled) disableVerticalWrap();
		}
	}

	public void drawPriorityBucket(int bucket, boolean highPriority) {
		boolean wrapEnabled = enableVerticalWrapIfNeeded();
		try {
			bucketSprites(); // Ensure sprites are bucketed
			int targetBucket = RenderPriority.clamp(bucket);
			int idx = targetBucket - RenderPriority.MIN;

			List<Sprite>[] buckets = highPriority ? highPriorityBuckets : lowPriorityBuckets;
			for (Sprite sprite : buckets[idx]) {
				sprite.draw();
			}

			// Non-playable sprites are only drawn once at the minimum high-priority bucket
			if (highPriority && targetBucket == RenderPriority.MIN) {
				for (Sprite sprite : nonPlayableSprites) {
					sprite.draw();
				}
			}
		} finally {
			if (wrapEnabled) disableVerticalWrap();
		}
	}

	/**
	 * Draw all sprites in a single unified bucket, regardless of their isHighPriority flag.
	 * Calls the provided callback before drawing each sprite with its high priority status.
	 *
	 * This supports ROM-accurate sprite-to-sprite ordering where bucket number determines
	 * draw order independently of the sprite-to-tile priority (isHighPriority flag).
	 *
	 * @param bucket   The priority bucket to draw (0-7)
	 * @param callback Called before each sprite draw with (sprite, isHighPriority)
	 */
	public void drawUnifiedBucket(int bucket, SpriteDrawCallback callback) {
		boolean wrapEnabled = enableVerticalWrapIfNeeded();
		try {
			bucketSprites(); // Ensure sprites are bucketed
			int targetBucket = RenderPriority.clamp(bucket);
			int idx = targetBucket - RenderPriority.MIN;

			// Draw low-priority sprites first (they appear behind)
			for (Sprite sprite : lowPriorityBuckets[idx]) {
				if (callback != null) {
					callback.beforeDraw(sprite, false);
				}
				sprite.draw();
			}

			// Draw high-priority sprites second (they appear in front)
			for (Sprite sprite : highPriorityBuckets[idx]) {
				if (callback != null) {
					callback.beforeDraw(sprite, true);
				}
				sprite.draw();
			}

			// Non-playable sprites are drawn at the minimum bucket
			if (targetBucket == RenderPriority.MIN) {
				for (Sprite sprite : nonPlayableSprites) {
					// Non-playable sprites default to low priority for tile layering
					if (callback != null) {
						callback.beforeDraw(sprite, false);
					}
					sprite.draw();
				}
			}
		} finally {
			if (wrapEnabled) disableVerticalWrap();
		}
	}

	/**
	 * Callback interface for unified sprite drawing.
	 * Called before each sprite is drawn to allow setting up shader uniforms.
	 */
	@com.openggf.game.ModApi
	public interface SpriteDrawCallback {
		/**
		 * Called before drawing a sprite.
		 *
		 * @param sprite       The sprite about to be drawn
		 * @param highPriority True if sprite should appear above high-priority tiles
		 */
		void beforeDraw(Sprite sprite, boolean highPriority);
	}

	/** Resolves live suppression/priority inputs and prepares all buckets for one render pass. */
	public void prepareRenderBucketsForPass() {
		refreshRenderBucketsIfChanged();
		bucketSprites(resolveCpuSidekickSuppressed());
	}

	/**
	 * Draw all sprites in a single unified bucket with per-instance priority.
	 * Priority is now handled per-instance in the shader, so no batch flushing
	 * is needed when switching between low and high priority sprites.
	 *
	 * @param bucket The priority bucket to draw (0-7)
	 * @param gfx    The graphics manager to use for priority state
	 */
	public void drawUnifiedBucketWithPriority(int bucket, GraphicsManager gfx) {
		prepareRenderBucketsForPass();
		drawPreparedUnifiedBucketWithPriority(bucket, gfx, null);
	}

	/**
	 * Draws one bucket from the most recent {@link #prepareRenderBucketsForPass()} snapshot.
	 * Callers must prepare once immediately before their bucket loop and must not mutate
	 * sprite membership or priority until that loop completes.
	 */
	public void drawPreparedUnifiedBucketWithPriority(int bucket, GraphicsManager gfx,
			SpritePriorityLayerHook hook) {
		boolean wrapEnabled = enableVerticalWrapIfNeeded();
		try {
			int idx = RenderPriority.clamp(bucket) - RenderPriority.MIN;
			if (!lowPriorityBuckets[idx].isEmpty()) {
				gfx.flushPatternBatch();
				gfx.setCurrentSpriteHighPriority(false);
				gfx.beginPatternBatch();
				if (hook != null) {
					hook.beforePriorityLayer(bucket, false);
					gfx.setCurrentSpriteHighPriority(false);
					gfx.beginPatternBatch();
				}
				for (Sprite sprite : lowPriorityBuckets[idx]) sprite.draw();
			}
			if (!highPriorityBuckets[idx].isEmpty()) {
				gfx.flushPatternBatch();
				gfx.setCurrentSpriteHighPriority(true);
				gfx.beginPatternBatch();
				if (hook != null) {
					hook.beforePriorityLayer(bucket, true);
					gfx.setCurrentSpriteHighPriority(true);
					gfx.beginPatternBatch();
				}
				for (Sprite sprite : highPriorityBuckets[idx]) sprite.draw();
			}
			if (bucket == RenderPriority.MIN && !nonPlayableSprites.isEmpty()) {
				gfx.flushPatternBatch();
				gfx.setCurrentSpriteHighPriority(false);
				gfx.beginPatternBatch();
				for (Sprite sprite : nonPlayableSprites) sprite.draw();
			}
		} finally {
			if (wrapEnabled) disableVerticalWrap();
		}
	}

	/**
	 * Enables vertical wrap Y adjustment on GraphicsManager when the camera
	 * has vertical wrapping active. Returns true if wrap was enabled (and
	 * caller must disable it after drawing).
	 */
	private boolean enableVerticalWrapIfNeeded() {
		Camera camera = GameServices.cameraOrNull();
		if (camera != null && camera.isVerticalWrapEnabled()) {
			GameServices.graphics().enableVerticalWrapAdjust(
					camera.getVerticalWrapRange(), camera.getY());
			return true;
		}
		return false;
	}

	private void disableVerticalWrap() {
		GameServices.graphics().disableVerticalWrapAdjust();
	}

	private boolean removeSprite(Sprite sprite) {
		if (sprite == null) {
			return false;
		}
		bucketsDirty = true;
		if (sprite instanceof AbstractPlayableSprite playable) {
			sidekicks.remove(playable);
			sidekickCharacterNames.remove(playable);
			temporarySidekicks.remove(playable);
		}
		return (sprites.remove(sprite.getCode()) != null);
	}

	private void bucketSprites() {
		bucketSprites(resolveCpuSidekickSuppressed());
	}

	private void bucketSprites(boolean currentSuppressed) {
		if (currentSuppressed != lastSidekickSuppressed) {
			bucketsDirty = true;
			lastSidekickSuppressed = currentSuppressed;
		}
		if (!bucketsDirty) {
			return;
		}
		bucketsDirty = false;

		for (int i = 0; i < BUCKET_COUNT; i++) {
			lowPriorityBuckets[i].clear();
			highPriorityBuckets[i].clear();
		}
		nonPlayableSprites.clear();

		// Two-pass bucketing: sidekicks first (drawn behind), then main player
		// (drawn on top). On the VDP, lower sprite indices have higher priority
		// and appear in front. Sonic occupies slot 0 and Tails slot 1, so Sonic
		// must be drawn last in painter's-algorithm order.
		Collection<Sprite> sprites = getAllSprites();
		if (!currentSuppressed) {
			for (AbstractPlayableSprite sidekick : sidekicks) {
				if (sprites.contains(sidekick)) {
					addPlayableToRenderBucket(sidekick);
				}
			}
		}
		for (Sprite sprite : sprites) {
			if (isSuppressedSidekickSprite(sprite, currentSuppressed)) {
				continue;
			}
			if (sprite instanceof AbstractPlayableSprite playable) {
				if (playable.isCpuControlled()) {
					continue; // already added in first pass
				}
				addPlayableToRenderBucket(playable);
			} else {
				nonPlayableSprites.add(sprite);
			}
		}

		captureRenderBucketSnapshot();
	}

	private void addPlayableToRenderBucket(AbstractPlayableSprite playable) {
		int bucket = RenderPriority.clamp(playable.getPriorityBucket());
		int idx = bucket - RenderPriority.MIN;
		if (playable.isHighPriority()) {
			highPriorityBuckets[idx].add(playable);
		} else {
			lowPriorityBuckets[idx].add(playable);
		}
	}

	private LevelManager getLevelManager() {
		LevelManager runtimeLevelManager = GameServices.levelOrNull();
		if (runtimeLevelManager != null && levelManager != runtimeLevelManager) {
			levelManager = runtimeLevelManager;
		}
		return levelManager;
	}

	private GameStateManager currentGameStateManager() {
		return GameServices.gameState();
	}

	/**
	 * Suppresses all player keyboard input (directional + jump + test).
	 * Forced input masks (demo playback) still apply.
	 */
	public void setInputSuppressed(boolean suppressed) {
		this.inputSuppressed = suppressed;
	}

	/**
	 * Suppresses keyboard-driven gameplay input specifically for playback mode.
	 * This is separate from generic suppression used by title cards/credits.
	 */
	public void setPlaybackInputSuppressed(boolean suppressed) {
		this.playbackInputSuppressed = suppressed;
	}

	protected boolean resolveCpuSidekickSuppressed() {
		LevelManager lm = GameServices.levelOrNull();
		GameModule module = null;
		var session = com.openggf.game.session.SessionManager.getCurrentWorldSession();
		if (session != null) {
			module = session.getGameModule();
		}
		if (lm == null) {
			lm = getLevelManager();
		}
		if (lm == null) return false;
		if (module == null) {
			module = lm.getGameModule();
		}
		int currentZone = lm.getCurrentZone();
		if (module != null && module.isSidekickSuppressedForZone(currentZone)) return true;
		return false;
	}

	private boolean isCpuSidekickSuppressed() {
		return resolveCpuSidekickSuppressed();
	}

	private boolean isSuppressedSidekickSprite(Sprite sprite) {
		return isSuppressedSidekickSprite(sprite, resolveCpuSidekickSuppressed());
	}

	private boolean isSuppressedSidekickSprite(Sprite sprite, boolean suppressed) {
		return suppressed
				&& sprite instanceof AbstractPlayableSprite playable
				&& playable.isCpuControlled();
	}

	public void refreshPowerUpObjectsAfterRewindRestore() {
		for (Sprite sprite : sprites.values()) {
			if (sprite instanceof AbstractPlayableSprite playable) {
				playable.refreshPowerUpObjectsAfterRewindRestore();
			}
		}
	}

	/**
	 * Relinks each player's carry-owner reference (MGZ top-platform style
	 * object-controlled carry) after a rewind restore. The reference itself is
	 * not captured in snapshots: the owning object's restored carry state is
	 * authoritative, so this pass points the player at the live restored owner
	 * (or clears a stale reference when no restored object owns the player).
	 */
	public void refreshCarrySolidContactOwnersAfterRewindRestore(ObjectManager objectManager) {
		if (objectManager == null) {
			return;
		}
		Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
		for (Sprite sprite : sprites.values()) {
			if (!(sprite instanceof AbstractPlayableSprite playable)) {
				continue;
			}
			ObjectInstance owner = null;
			for (ObjectInstance object : activeObjects) {
				if (object instanceof com.openggf.level.objects.ObjectControlledSolidContactController controller
						&& com.openggf.level.objects.ObjectCallbackDispatch.call(objectManager, object,
								() -> controller.ownsCarriedPlayerForRewind(playable))) {
					owner = object;
					break;
				}
			}
			playable.setMgzTopPlatformCarrySolidContactObject(owner);
		}
	}

	public void refreshLatchedSolidObjectsAfterRewindRestore(ObjectManager objectManager) {
		if (objectManager == null) {
			return;
		}
		Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
		for (Sprite sprite : sprites.values()) {
			if (sprite instanceof AbstractPlayableSprite playable) {
				int objectId = playable.getLatchedSolidObjectId() & 0xFF;
				if (objectId == 0) {
					playable.setLatchedSolidObjectInstance(null);
					continue;
				}
				ObjectInstance current = playable.getLatchedSolidObjectInstance();
				if (isActiveObjectWithId(objectManager, current, activeObjects, objectId)) {
					continue;
				}
				ObjectInstance restored = activeObjectAtInteractSlot(objectManager, playable, activeObjects, objectId);
				if (restored == null) {
					restored = nearestActiveObjectWithId(objectManager, playable, activeObjects, objectId);
				}
				playable.setLatchedSolidObjectInstance(restored);
			}
		}
	}

	private boolean isActiveObjectWithId(ObjectManager objectManager, ObjectInstance object,
			Collection<ObjectInstance> activeObjects,
			int objectId) {
		if (object == null || !activeObjects.contains(object)) return false;
		ObjectSpawn spawn = com.openggf.level.objects.ObjectCallbackDispatch.call(
				objectManager, object, object::getSpawn);
		return spawn != null && (spawn.objectId() & 0xFF) == objectId;
	}

	private ObjectInstance activeObjectAtInteractSlot(ObjectManager objectManager, AbstractPlayableSprite playable,
			Collection<ObjectInstance> activeObjects,
			int objectId) {
		int slotIndex = playable.getInteractSlotIndex();
		if (slotIndex < 0) {
			return null;
		}
		for (ObjectInstance object : activeObjects) {
			if (!(object instanceof AbstractObjectInstance aoi)) continue;
			int candidateSlot = com.openggf.level.objects.ObjectCallbackDispatch.call(
					objectManager, object, aoi::getSlotIndex);
			ObjectSpawn spawn = com.openggf.level.objects.ObjectCallbackDispatch.call(
					objectManager, object, object::getSpawn);
			if (candidateSlot != slotIndex || spawn == null || (spawn.objectId() & 0xFF) != objectId) {
				continue;
			}
			return object;
		}
		return null;
	}

	private ObjectInstance nearestActiveObjectWithId(ObjectManager objectManager, AbstractPlayableSprite playable,
			Collection<ObjectInstance> activeObjects,
			int objectId) {
		ObjectInstance best = null;
		long bestDistance = Long.MAX_VALUE;
		for (ObjectInstance object : activeObjects) {
			ObjectSpawn spawn = com.openggf.level.objects.ObjectCallbackDispatch.call(
					objectManager, object, object::getSpawn);
			if (spawn == null || (spawn.objectId() & 0xFF) != objectId) {
				continue;
			}
			long dx = (long) spawn.x() - playable.getCentreX();
			long dy = (long) spawn.y() - playable.getCentreY();
			long distance = dx * dx + dy * dy;
			if (distance < bestDistance) {
				best = object;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * Runs the canonical per-sprite physics tick: solid contacts, movement,
	 * post-movement solid pass, plane switchers, animation, and status.
	 * <p>
	 * This is the single source of truth for per-sprite update ordering.
	 * Both {@code SpriteManager.update()} and {@code HeadlessTestRunner}
	 * MUST delegate here rather than duplicating the step sequence.
	 *
	 * @param playable     the playable sprite to tick
	 * @param up           effective up input (after control-lock / forced-input filtering)
	 * @param down         effective down input
	 * @param left         effective left input
	 * @param right        effective right input
	 * @param jump         effective jump input
	 * @param test         effective test button input
	 * @param speedUp      debug speed-up modifier
	 * @param slowDown     debug slow-down modifier
	 * @param levelManager the level manager
	 * @param frameCounter the current frame number
	 */
	public static void tickPlayablePhysics(AbstractPlayableSprite playable,
										   boolean up, boolean down, boolean left, boolean right,
										   boolean jump, boolean test, boolean speedUp, boolean slowDown,
										   LevelManager levelManager, int frameCounter) {
		boolean isUnified = requiresPostMovementSolidPass(playable);
		boolean usesInlineSolidResolution = levelManager != null && levelManager.objectsExecuteAfterPlayerPhysics();
		// Capture the hurt-routine membership BEFORE movement runs, because the
		// landing-from-hurt step clears the hurt flag mid-tick (calculateLanding ->
		// resetOnFloor). The ROM hurt routine owns the whole frame and never calls
		// the water-handling routine, including the frame it transitions back to the
		// normal control routine: S2 Obj02_Hurt / Obj01_Hurt have no Tails_Water /
		// Sonic_Water call (docs/s2disasm/s2.asm:41057 Obj02_Hurt;
		// docs/s2disasm/s2.asm:38158 Obj01_Hurt), S1 Sonic_Hurt only gains the
		// water call under the FixBugs assembly switch ("Fix water not being
		// acknowledged during a hurt state", docs/s1disasm/_incObj/01 Sonic.asm:1814-1817),
		// and S3K Tails hurt loc_156D6 likewise omits it (docs/skdisasm/sonic3k.asm:29194-29210).
		// Only the next, first normal-control frame's Obj02_Control reaches Tails_Water,
		// and it runs AFTER Tails_Move (docs/s2disasm/s2.asm:38973 move, :38981 water),
		// so the underwater acceleration switch (Tails_acceleration $C->$6,
		// docs/s2disasm/s2.asm:39550 vs dry $C at :38902/:39045) is first observed by the
		// frame after that move. Skipping the water update on the hurt-landing frame keeps
		// the engine's waterPhysicsActive flag from being set one frame early.
		boolean hurtAtTickStart = playable.isHurt();
		// For S1 UNIFIED: skip pre-movement solid pass. ROM processes all solid
		// objects AFTER Sonic's movement (his slot runs first in ExecuteObjects),
		// so only the post-movement pass is ROM-accurate. Running both creates
		// double-correction artifacts.
		// For S2/S3K: solid contacts now resolve inline during the object exec loop
		// after movement, so the old pre-movement batched pass must be skipped.
		if (!isUnified && !usesInlineSolidResolution) {
			applySolidContacts(levelManager, playable, false, false);
		}
		// ROM Obj01_Modes dispatches the movement mode BEFORE the move itself
		// runs, and the Super transformation gate is the first thing inside the
		// jump mode: Obj01_MdJump calls Sonic_JumpHeight, which ends
		// `tst.b y_vel(a0) / beq.s Sonic_CheckGoSuper`
		// (docs/s2disasm/s2.asm:36241, :37432-37434). The gate therefore reads
		// the y_vel left by the PREVIOUS frame. Driving it from tickStatus at
		// the end of this tick read the value this frame's move had just
		// produced, firing the same predicate one frame early.
		var superStateBeforeMove = playable.getSuperStateController();
		if (superStateBeforeMove != null) {
			superStateBeforeMove.checkTransformationBeforeMove();
		}
		if (playable instanceof CustomPlayablePhysics customPhysics) {
			customPhysics.tickCustomPhysics(up, down, left, right, jump, test, speedUp, slowDown,
					levelManager, frameCounter);
		} else {
			playable.getMovementManager().handleMovement(up, down, left, right, jump, test, speedUp, slowDown);
		}
		applyScreenYWrapValueAfterControl(playable);
		SidekickCpuController cpuController = playable.getCpuController();
		if (cpuController != null) {
			cpuController.finishCarryAfterCarrierMovement();
		}
		if (cpuController == null
				|| (cpuController.getState() != SidekickCpuController.State.CARRYING
				&& cpuController.getState() != SidekickCpuController.State.CARRY_INIT)) {
			SpriteManager sprites = playable.currentSpriteManagerOrNull();
			AbstractPlayableSprite main = sprites != null ? sprites.getMainPlayable() : null;
			int mainCarryInput = 0;
			if (main != null) {
				if (main.isUpPressed()) mainCarryInput |= AbstractPlayableSprite.INPUT_UP;
				if (main.isDownPressed()) mainCarryInput |= AbstractPlayableSprite.INPUT_DOWN;
				if (main.isLeftPressed()) mainCarryInput |= AbstractPlayableSprite.INPUT_LEFT;
				if (main.isRightPressed()) mainCarryInput |= AbstractPlayableSprite.INPUT_RIGHT;
				if (main.isJumpJustPressed()) mainCarryInput |= AbstractPlayableSprite.INPUT_JUMP;
			}
			playable.getTailsCarryController().updateAfterTailsCollision(mainCarryInput);
		}
		playable.recordFollowerHistoryForTick();
		if (usesInlineSolidResolution && !hurtAtTickStart) {
			// S2/S3K run Sonic_Water/Tails_Water after movement and the
			// position-history write, but before animation and TouchResponse
			// (sonic3k.asm:21995-22022). Object launchers touched on a water-entry
			// frame therefore overwrite the quartered entry velocity, rather than
			// having their launch velocity quartered afterward.
			levelManager.updatePlayableWaterStateForCurrentLevel(playable);
		}
		if (playable.getMovementManager() instanceof PlayableSpriteMovement movement) {
			movement.applyDeferredSpindashAnimationPushClear();
		}
		// ROM Obj01_Control runs Sonic_Display before Sonic_Animate and
		// TouchResponse (S1 01 Sonic.asm:73-90, S2 s2.asm:36243-36258,
		// S3K sonic3k.asm:21995-22022). Sonic_Display decrements
		// invulnerable_time, and spilled-ring touch checks read that decremented
		// value in the same object-interaction pass.
		playable.tickInvulnerabilityDisplayTimerBeforeTouchResponse();
		tickDisplayPhaseTimers(playable);
		// ROM Obj01_Control: movement runs first, then Sonic_Animate, then
		// TouchResponse. Special objects like monitors gate on anim(a0), so
		// ReactToItem must observe the post-movement animation state from the
		// current player slot, not the previous frame's mapping state.
		playable.getAnimationManager().update(frameCounter);
		// S3K/S2/S1 hurt and death routines animate/draw but do not call
		// TouchResponse. In particular, S3K Tails loc_156D6 runs movement,
		// collision, Animate_Tails and Draw_Sprite only; allowing another touch
		// pass lets an already-hurt sidekick trigger SPECIAL objects such as the
		// MGZ Spiker spring while the ROM cannot.
		if (!hurtAtTickStart && !playable.getDead()) {
			levelManager.applyTouchResponses(playable);
		}
		// S1 (UNIFIED): Post-movement solid pass matches ROM timing — solid objects
		// check Sonic's position after he has moved in the ROM's ExecuteObjects loop.
		// postMovement=true disables velocity classification adjustment.
		// Skip this legacy batched pass when the active module resolves solids inline
		// during the object execution loop (currently S1 trace-parity mode), otherwise
		// S1 gets double-resolution (inline + post-movement) and the slot layout drifts.
		if (isUnified && !usesInlineSolidResolution) {
			applySolidContacts(levelManager, playable, true, false);
		}
		levelManager.applyPlaneSwitchers(playable);
		playable.tickStatus();
		playable.endOfTick();
	}

	/**
	 * Runs a character's display-phase countdowns, at the point in the frame the
	 * ROM's {@code Sonic_Display} occupies. All three games call it from the
	 * control routine after the movement modes have been dispatched
	 * (S1 {@code docs/s1disasm/_incObj/01 Sonic.asm:76,80},
	 * S2 {@code docs/s2disasm/s2.asm:36242,36248},
	 * S3K {@code docs/skdisasm/sonic3k.asm:22021,22031}), and its
	 * {@code Sonic_ChkShoes} tail does both consequences of the speed-shoes
	 * countdown reaching zero there in the one frame: the top-speed,
	 * acceleration and deceleration restore, and the slow-down music command.
	 * Driving the countdown here instead of from the level loop's pre-physics
	 * timer pass keeps those two together and puts the queue write ahead of the
	 * same frame's driver service, where the ROM's is.
	 */
	private static void tickDisplayPhaseTimers(AbstractPlayableSprite playable) {
		TimerManager timers = GameServices.timersOrNull();
		if (timers != null) {
			timers.updateDisplayPhaseTimersFor(playable);
		}
	}

	private static void applySolidContacts(LevelManager levelManager, AbstractPlayableSprite playable,
										   boolean postMovement, boolean deferSideToPostMovement) {
		ObjectManager objectManager = levelManager.getObjectManager();
		if (objectManager == null) {
			return;
		}
		CollisionSystem collisionSystem = GameServices.collisionOrNull();
		if (collisionSystem != null) {
			collisionSystem.setObjectManager(objectManager);
			collisionSystem.runSolidObjectResolution(
					FrameCollisionPlan.objectResolutionOnly(), playable, postMovement, deferSideToPostMovement);
			return;
		}
		objectManager.updateSolidContacts(playable, postMovement, deferSideToPostMovement);
	}

	private static void applyScreenYWrapValueAfterControl(AbstractPlayableSprite playable) {
		// ROM Obj02_Dead (docs/s2disasm/s2.asm:40736-40742) runs the dead CPU
		// sidekick's fall through jsr (ObjectMoveAndFall).l directly and never
		// applies the Screen_Y_wrap mask that the live control/hurt paths use, so
		// the falling body's y_pos keeps climbing past the wrap boundary until
		// Obj02_CheckGameOver (s2.asm:40747-40759) crosses Tails_Max_Y_pos+$100 and
		// branches to TailsCPU_Despawn (s2.asm:39043-39052). Mirror that by skipping
		// the wrap for a dead CPU sidekick on the dead-fall path — matching the
		// existing bypass in PlayableSpriteMovement.applyScreenYWrapValueAfterControl().
		// Without this, the engine masked the dead Tails' y_pos at 0x800
		// (0x0807 & 0x07FF = 0x0007), so getCentreY() never crossed the despawn
		// threshold and MTZ3 trace replay diverged at frame 3719 (tails_y).
		SidekickCpuController cpu = playable.getCpuController();
		if (playable.isCpuControlled()
				&& playable.getDead()
				&& cpu != null
				&& cpu.deadFallBypassesScreenYWrapValue()) {
			return;
		}
		Camera camera = GameServices.cameraOrNull();
		if (camera != null) {
			camera.applyScreenYWrapValue(playable);
		}
	}


	static boolean requiresPostMovementSolidPass(AbstractPlayableSprite playable) {
		if (playable == null) {
			return false;
		}
		GameRules rules = playable.getGameRules();
		return rules != null && rules.collision() != null
				&& rules.collision().collisionModel() == CollisionModel.UNIFIED;
	}

	static List<AbstractPlayableSprite> buildPlayableUpdateOrder(Collection<Sprite> sprites,
			List<AbstractPlayableSprite> sidekicks,
			boolean suppressCpuSidekicks) {
		return buildPlayableUpdateOrderInto(sprites, sidekicks, suppressCpuSidekicks,
				new ArrayList<>(), new IdentityHashMap<>(), new IdentityHashMap<>());
	}

	/**
	 * Allocation-free variant of {@link #buildPlayableUpdateOrder}: fills the
	 * caller-supplied collections (cleared first) and returns {@code ordered}.
	 * The per-frame update paths pass reused scratch fields; the returned list
	 * is only valid until the next call with the same scratch.
	 */
	private static List<AbstractPlayableSprite> buildPlayableUpdateOrderInto(
			Collection<Sprite> sprites,
			List<AbstractPlayableSprite> sidekicks,
			boolean suppressCpuSidekicks,
			List<AbstractPlayableSprite> ordered,
			IdentityHashMap<AbstractPlayableSprite, Boolean> scheduled,
			IdentityHashMap<AbstractPlayableSprite, Boolean> available) {
		ordered.clear();
		scheduled.clear();
		available.clear();

		for (Sprite sprite : sprites) {
			if (sprite instanceof AbstractPlayableSprite playable) {
				available.put(playable, Boolean.TRUE);
				if (!playable.isCpuControlled()) {
					ordered.add(playable);
					scheduled.put(playable, Boolean.TRUE);
				}
			}
		}

		if (!suppressCpuSidekicks) {
			for (AbstractPlayableSprite sidekick : sidekicks) {
				if (available.containsKey(sidekick) && !scheduled.containsKey(sidekick)) {
					ordered.add(sidekick);
					scheduled.put(sidekick, Boolean.TRUE);
				}
			}
		}

		for (Sprite sprite : sprites) {
			if (!(sprite instanceof AbstractPlayableSprite playable)) {
				continue;
			}
			if (playable.isCpuControlled() && suppressCpuSidekicks) {
				continue;
			}
			if (!scheduled.containsKey(playable)) {
				ordered.add(playable);
				scheduled.put(playable, Boolean.TRUE);
			}
		}

		return ordered;
	}


	public static SensorConfiguration[][] createMovementMappingArray() {
		SensorConfiguration[][] output = new SensorConfiguration[GroundMode.values().length][Direction.values().length];
		// Initialize the array with all possible GroundMode and Direction combinations
		// Ground Mode
		output[GroundMode.GROUND.ordinal()][Direction.UP.ordinal()] = new SensorConfiguration((byte) 0, (byte) -16, true, Direction.UP);
		output[GroundMode.GROUND.ordinal()][Direction.DOWN.ordinal()] = new SensorConfiguration((byte) 0, (byte) 16, true, Direction.DOWN);
		output[GroundMode.GROUND.ordinal()][Direction.LEFT.ordinal()] = new SensorConfiguration((byte) -16, (byte) 0, false, Direction.LEFT);
		output[GroundMode.GROUND.ordinal()][Direction.RIGHT.ordinal()] = new SensorConfiguration((byte) 16, (byte) 0, false, Direction.RIGHT);

		// Right Wall (0x40 quadrant): Sonic on right wall, surface to his RIGHT
		// ROM's WalkVertR: probes right (a3=+$10), add.w d1,obX
		output[GroundMode.RIGHTWALL.ordinal()][Direction.UP.ordinal()] = new SensorConfiguration((byte) -16, (byte) 0, false, Direction.LEFT);
		output[GroundMode.RIGHTWALL.ordinal()][Direction.DOWN.ordinal()] = new SensorConfiguration((byte) 16, (byte) 0, false, Direction.RIGHT);
		output[GroundMode.RIGHTWALL.ordinal()][Direction.LEFT.ordinal()] = new SensorConfiguration((byte) 0, (byte) 16, true, Direction.DOWN);
		output[GroundMode.RIGHTWALL.ordinal()][Direction.RIGHT.ordinal()] = new SensorConfiguration((byte) 0, (byte) -16, true, Direction.UP);

		// Ceiling
		output[GroundMode.CEILING.ordinal()][Direction.UP.ordinal()] = new SensorConfiguration((byte) 0, (byte) 16, true, Direction.DOWN);
		output[GroundMode.CEILING.ordinal()][Direction.DOWN.ordinal()] = new SensorConfiguration((byte) 0, (byte) -16, true, Direction.UP);
		output[GroundMode.CEILING.ordinal()][Direction.LEFT.ordinal()] = new SensorConfiguration((byte) 16, (byte) 0, false, Direction.RIGHT);
		output[GroundMode.CEILING.ordinal()][Direction.RIGHT.ordinal()] = new SensorConfiguration((byte) -16, (byte) 0, false, Direction.LEFT);

		// Left Wall (0xC0 quadrant): Sonic on left wall, surface to his LEFT
		// ROM's WalkVertL: probes left (a3=-$10), sub.w d1,obX
		output[GroundMode.LEFTWALL.ordinal()][Direction.UP.ordinal()] = new SensorConfiguration((byte) 16, (byte) 0, false, Direction.RIGHT);
		output[GroundMode.LEFTWALL.ordinal()][Direction.DOWN.ordinal()] = new SensorConfiguration((byte) -16, (byte) 0, false, Direction.LEFT);
		output[GroundMode.LEFTWALL.ordinal()][Direction.LEFT.ordinal()] = new SensorConfiguration((byte) 0, (byte) -16, true, Direction.UP);
		output[GroundMode.LEFTWALL.ordinal()][Direction.RIGHT.ordinal()] = new SensorConfiguration((byte) 0, (byte) 16, true, Direction.DOWN);

		return output;
	}

	public static SensorConfiguration getSensorConfigurationForGroundModeAndDirection(GroundMode groundMode, Direction direction) {
		return MOVEMENT_MAPPING_ARRAY[groundMode.ordinal()][direction.ordinal()];
	}

	/**
	 * Returns a {@link com.openggf.game.rewind.RewindSnapshottable} adapter for
	 * all active playable sprites.
	 *
	 * <p><strong>Capture</strong> records the full mutable gameplay surface of
	 * every active playable sprite (main player + sidekicks) keyed by
	 * {@link Sprite#getCode()} via
	 * {@link AbstractPlayableSprite#captureRewindState()}.
	 *
	 * <p><strong>Restore</strong> applies the captured snapshots back to the
	 * currently registered sprites by code.  Sprites not present in the
	 * snapshot (e.g. mid-level sidekick joins) are left untouched; sprites
	 * in the snapshot but not present in the current manager are skipped.
	 */
	public com.openggf.game.rewind.RewindSnapshottable<com.openggf.game.rewind.snapshot.SpriteManagerSnapshot>
			rewindSnapshottable() {
		return new com.openggf.game.rewind.RewindSnapshottable<>() {
			@Override
			public String key() {
				return "sprites";
			}

			@Override
			public com.openggf.game.rewind.snapshot.SpriteManagerSnapshot capture() {
				Set<AbstractPlayableSprite> leadersWithFollowers = currentDirectSidekickLeaders();
				java.util.List<com.openggf.game.rewind.snapshot.SpriteManagerSnapshot.SpriteEntry> snap =
						new java.util.ArrayList<>();
				for (Sprite sprite : sprites.values()) {
					if (sprite instanceof AbstractPlayableSprite aps) {
						boolean includeFollowHistory =
								!aps.isCpuControlled() || leadersWithFollowers.contains(aps);
						snap.add(new com.openggf.game.rewind.snapshot.SpriteManagerSnapshot.SpriteEntry(
								aps.getCode(), aps.captureRewindState(includeFollowHistory)));
					}
				}
				return new com.openggf.game.rewind.snapshot.SpriteManagerSnapshot(
						frameCounter,
						snap.toArray(new com.openggf.game.rewind.snapshot.SpriteManagerSnapshot.SpriteEntry[0]));
			}

			@Override
			public void restore(com.openggf.game.rewind.snapshot.SpriteManagerSnapshot s) {
				frameCounter = s.frameCounter();
				java.util.Set<String> snapshotCodes = new java.util.HashSet<>();
				for (com.openggf.game.rewind.snapshot.SpriteManagerSnapshot.SpriteEntry entry : s.sprites()) {
					snapshotCodes.add(entry.code());
				}
				for (AbstractPlayableSprite sidekick : new ArrayList<>(temporarySidekicks)) {
					if (!snapshotCodes.contains(sidekick.getCode())) {
						removeSprite(sidekick);
					}
				}
				// Recreate any temporary sidekick that the keyframe still contains
				// but that has since been removed (e.g. the CNZ1 carry-in Tails that
				// flew off). Without this, rewinding back into the carry intro would
				// leave the leader object-controlled with no carrier present. The
				// factory fully wires and registers the sprite; its per-sprite state
				// is reapplied by the restore loop below.
				for (com.openggf.game.rewind.snapshot.SpriteManagerSnapshot.SpriteEntry entry : s.sprites()) {
					if (getSprite(entry.code()) == null) {
						java.util.function.Supplier<AbstractPlayableSprite> recreator =
								temporarySidekickRecreators.get(entry.code());
						if (recreator != null) {
							recreator.get();
						}
					}
				}
				for (Sprite sprite : sprites.values()) {
					if (sprite instanceof AbstractPlayableSprite aps) {
						com.openggf.level.objects.PerObjectRewindSnapshot perSprite = null;
						for (com.openggf.game.rewind.snapshot.SpriteManagerSnapshot.SpriteEntry entry : s.sprites()) {
							if (entry.code().equals(aps.getCode())) {
								perSprite = entry.state();
								break;
							}
						}
						if (perSprite != null) {
							aps.restoreRewindState(perSprite);
						}
					}
				}
			}
		};
	}

	private Set<AbstractPlayableSprite> currentDirectSidekickLeaders() {
		Set<AbstractPlayableSprite> leaders = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractPlayableSprite sidekick : sidekicks) {
			SidekickCpuController controller = sidekick.getCpuController();
			if (controller != null && controller.getLeader() != null) {
				leaders.add(controller.getLeader());
			}
		}
		return leaders;
	}

}
