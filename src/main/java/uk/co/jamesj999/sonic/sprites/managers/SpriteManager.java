package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages collection of available sprites to be provided to renderer and collision manager.
 * 
 * @author james
 * 
 */
public class SpriteManager {
	private final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();

	private static SpriteManager spriteManager;

	private Map<String, Sprite> sprites;

	private static final SensorConfiguration[][] MOVEMENT_MAPPING_ARRAY = createMovementMappingArray();

	private static final int BUCKET_COUNT = RenderPriority.MAX - RenderPriority.MIN + 1;
	@SuppressWarnings("unchecked")
	private final List<Sprite>[] lowPriorityBuckets = new ArrayList[BUCKET_COUNT];
	@SuppressWarnings("unchecked")
	private final List<Sprite>[] highPriorityBuckets = new ArrayList[BUCKET_COUNT];
	private final List<Sprite> nonPlayableSprites = new ArrayList<>();
	private boolean bucketsDirty = true;

	private LevelManager levelManager;

	private int upKey;
	private int downKey;
	private int leftKey;
	private int rightKey;
	private int jumpKey;
	private int testKey;
	private int debugModeKey;
	private int frameCounter;

	private SpriteManager() {
		sprites = new HashMap<String, Sprite>();
		for (int i = 0; i < BUCKET_COUNT; i++) {
			lowPriorityBuckets[i] = new ArrayList<>();
			highPriorityBuckets[i] = new ArrayList<>();
		}
		upKey = configService.getInt(SonicConfiguration.UP);
		downKey = configService.getInt(SonicConfiguration.DOWN);
		leftKey = configService.getInt(SonicConfiguration.LEFT);
		rightKey = configService.getInt(SonicConfiguration.RIGHT);
		jumpKey = configService.getInt(SonicConfiguration.JUMP);
		testKey = configService.getInt(SonicConfiguration.TEST);
		debugModeKey = configService.getInt(SonicConfiguration.DEBUG_MODE_KEY);
	}

	/**
	 * Adds the given sprite to the SpriteManager. Returns true if we have
	 * overwritten a sprite, false if we are creating a new one.
	 *
	 * @param sprite
	 * @return
	 */
	public boolean addSprite(Sprite sprite) {
		bucketsDirty = true;
		return (sprites.put(sprite.getCode(), sprite) != null);
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

	public Collection<Sprite> getAllSprites() {
		return sprites.values();
	}

	public Sprite getSprite(String code) {
		return sprites.get(code);
	}

	/**
	 * Returns the sidekick sprite (Tails in 2-player or AI mode).
	 * Currently returns null as sidekick AI is not implemented.
	 * <p>
	 * In the original Sonic 2, the sidekick is stored at RAM $FFFFB040
	 * (Sidekick) vs the main character at $FFFFB000 (MainCharacter).
	 *
	 * @return the sidekick sprite, or null if no sidekick is active
	 */
	public AbstractPlayableSprite getSidekick() {
		// TODO: Implement sidekick (Tails AI) support
		// When implemented, this should return the secondary playable sprite
		return null;
	}

	public void update(InputHandler handler) {
		frameCounter++;
		bucketsDirty = true; // Mark for re-bucketing since sprites may have changed
		Collection<Sprite> sprites = getAllSprites();
		boolean up = handler.isKeyDown(upKey);
		boolean down = handler.isKeyDown(downKey);
		boolean left = handler.isKeyDown(leftKey);
		boolean right = handler.isKeyDown(rightKey);
		boolean space = handler.isKeyDown(jumpKey);
		boolean testButton = handler.isKeyDown(testKey);
		boolean debugModePressed = handler.isKeyPressed(debugModeKey);

		LevelManager levelManager = getLevelManager();
		for (Sprite sprite : sprites) {
			if (sprite instanceof AbstractPlayableSprite playable) {
				if (debugModePressed) {
					playable.toggleDebugMode();
				}

				boolean controlLocked = playable.isControlLocked();
				boolean effectiveRight = right || playable.isForceInputRight() || controlLocked;
				boolean effectiveLeft = !controlLocked && left && !playable.isForceInputRight();
				boolean effectiveUp = !controlLocked && up;
				boolean effectiveDown = !controlLocked && down;
				boolean effectiveJump = !controlLocked && space;
				boolean effectiveTest = !controlLocked && testButton;

				levelManager.applyPlaneSwitchers(playable);
				playable.getMovementManager().handleMovement(effectiveUp, effectiveDown, effectiveLeft,
						effectiveRight, effectiveJump, effectiveTest);
				playable.getAnimationManager().update(frameCounter);
				playable.tickStatus();
				playable.endOfTick();
			}
		}
	}

	public void updateWithoutInput() {
		frameCounter++;
		Collection<Sprite> sprites = getAllSprites();
		LevelManager levelManager = getLevelManager();

		for (Sprite sprite : sprites) {
			if (sprite instanceof AbstractPlayableSprite playable) {
				levelManager.applyPlaneSwitchers(playable);
				playable.getMovementManager().handleMovement(false, false, false, false, false, false);
				playable.getAnimationManager().update(frameCounter);
				playable.tickStatus();
				playable.endOfTick();
			}
		}
	}

	public void draw() {
		Collection<Sprite> sprites = getAllSprites();
		for (Sprite sprite : sprites) {
			sprite.draw();
		}
	}

	public void drawLowPriority() {
		bucketSprites();
		for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
			int idx = bucket - RenderPriority.MIN;
			for (Sprite sprite : lowPriorityBuckets[idx]) {
				sprite.draw();
			}
		}
	}

	public void drawHighPriority() {
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
	}

	public void drawPriorityBucket(int bucket, boolean highPriority) {
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
	}

	private boolean removeSprite(Sprite sprite) {
		bucketsDirty = true;
		return (sprites.remove(sprite) != null);
	}

	private void bucketSprites() {
		if (!bucketsDirty) {
			return;
		}
		bucketsDirty = false;

		for (int i = 0; i < BUCKET_COUNT; i++) {
			lowPriorityBuckets[i].clear();
			highPriorityBuckets[i].clear();
		}
		nonPlayableSprites.clear();

		Collection<Sprite> sprites = getAllSprites();
		for (Sprite sprite : sprites) {
			if (sprite instanceof AbstractPlayableSprite playable) {
				int bucket = RenderPriority.clamp(playable.getPriorityBucket());
				int idx = bucket - RenderPriority.MIN;
				if (playable.isHighPriority()) {
					highPriorityBuckets[idx].add(sprite);
				} else {
					lowPriorityBuckets[idx].add(sprite);
				}
			} else {
				nonPlayableSprites.add(sprite);
			}
		}
	}

	private LevelManager getLevelManager() {
		if (levelManager == null) {
			levelManager = LevelManager.getInstance();
		}
		return levelManager;
	}

	public static SensorConfiguration[][] createMovementMappingArray() {
		SensorConfiguration[][] output = new SensorConfiguration[GroundMode.values().length][Direction.values().length];
		// Initialize the array with all possible GroundMode and Direction combinations
		// Ground Mode
		output[GroundMode.GROUND.ordinal()][Direction.UP.ordinal()] = new SensorConfiguration((byte) 0, (byte) -16, true, Direction.UP);
		output[GroundMode.GROUND.ordinal()][Direction.DOWN.ordinal()] = new SensorConfiguration((byte) 0, (byte) 16, true, Direction.DOWN);
		output[GroundMode.GROUND.ordinal()][Direction.LEFT.ordinal()] = new SensorConfiguration((byte) -16, (byte) 0, false, Direction.LEFT);
		output[GroundMode.GROUND.ordinal()][Direction.RIGHT.ordinal()] = new SensorConfiguration((byte) 16, (byte) 0, false, Direction.RIGHT);

		// Right Wall
		output[GroundMode.RIGHTWALL.ordinal()][Direction.UP.ordinal()] = new SensorConfiguration((byte) -16, (byte) 0, false, Direction.LEFT);
		output[GroundMode.RIGHTWALL.ordinal()][Direction.DOWN.ordinal()] = new SensorConfiguration((byte) 16, (byte) 0, false, Direction.RIGHT);
		output[GroundMode.RIGHTWALL.ordinal()][Direction.LEFT.ordinal()] = new SensorConfiguration((byte) 0, (byte) 16, true, Direction.DOWN);
		output[GroundMode.RIGHTWALL.ordinal()][Direction.RIGHT.ordinal()] = new SensorConfiguration((byte) 0, (byte) -16, true, Direction.UP);

		// Ceiling
		output[GroundMode.CEILING.ordinal()][Direction.UP.ordinal()] = new SensorConfiguration((byte) 0, (byte) 16, true, Direction.DOWN);
		output[GroundMode.CEILING.ordinal()][Direction.DOWN.ordinal()] = new SensorConfiguration((byte) 0, (byte) -16, true, Direction.UP);
		output[GroundMode.CEILING.ordinal()][Direction.LEFT.ordinal()] = new SensorConfiguration((byte) 16, (byte) 0, false, Direction.RIGHT);
		output[GroundMode.CEILING.ordinal()][Direction.RIGHT.ordinal()] = new SensorConfiguration((byte) -16, (byte) 0, false, Direction.LEFT);

		// Left Wall
		output[GroundMode.LEFTWALL.ordinal()][Direction.UP.ordinal()] = new SensorConfiguration((byte) 16, (byte) 0, false, Direction.RIGHT);
		output[GroundMode.LEFTWALL.ordinal()][Direction.DOWN.ordinal()] = new SensorConfiguration((byte) -16, (byte) 0, false, Direction.LEFT);
		output[GroundMode.LEFTWALL.ordinal()][Direction.LEFT.ordinal()] = new SensorConfiguration((byte) 0, (byte) -16, true, Direction.UP);
		output[GroundMode.LEFTWALL.ordinal()][Direction.RIGHT.ordinal()] = new SensorConfiguration((byte) 0, (byte) 16, true, Direction.DOWN);

		return output;
	}

	public static SensorConfiguration getSensorConfigurationForGroundModeAndDirection(GroundMode groundMode, Direction direction) {
		return MOVEMENT_MAPPING_ARRAY[groundMode.ordinal()][direction.ordinal()];
	}

	public synchronized static SpriteManager getInstance() {
		if (spriteManager == null) {
			spriteManager = new SpriteManager();
		}
		return spriteManager;
	}
}
