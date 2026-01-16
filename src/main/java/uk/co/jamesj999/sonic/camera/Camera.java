package uk.co.jamesj999.sonic.camera;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class Camera {
	private static Camera camera;
	private short x = 0;
	private short y = 0;

	private short minX;
	private short minY;
	private short maxX;
	private short maxY;

	// Target boundaries for smooth easing (ROM: Camera_Max_Y_pos_target, etc.)
	private short minXTarget;
	private short minYTarget;
	private short maxXTarget;
	private short maxYTarget;

	// ROM uses 2 pixels per frame for boundary easing
	private static final short BOUNDARY_EASE_STEP = 2;

	// Flag indicating boundary is actively changing (ROM: Camera_Max_Y_Pos_Changing)
	// When true, normal vertical scroll rules may be modified
	private boolean maxYChanging = false;

	private int framesBehind = 0;

	private boolean frozen = false;

	private AbstractPlayableSprite focusedSprite;

	private short width;
	private short height;

	private Camera() {
		SonicConfigurationService configService = SonicConfigurationService
				.getInstance();
		width = configService.getShort(SonicConfiguration.SCREEN_WIDTH_PIXELS);
		height = configService.getShort(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
	}

	public void updatePosition() {
		updatePosition(false);
	}

	public void updatePosition(boolean force) {
		if (force) {
			// Position camera so sprite is at the standard target position:
			// X: 152 pixels from left edge (midpoint between 144-160 window)
			// Y: 96 pixels from top edge (standard ground camera position)
			x = (short) (focusedSprite.getCentreX() - 152);
			y = (short) (focusedSprite.getCentreY() - 96);

			// Apply bounds clamping
			if (x < 0) x = 0;
			if (y < 0) y = 0;
			if (x > maxX) x = maxX;
			if (y > maxY) y = maxY;
			return;
		}
		if (frozen) {
			// Clamp framesBehind to prevent exceeding history array length (32)
			if (framesBehind < 31) {
				framesBehind++;
			}
			return;
		}
		short focusedSpriteRealX;
		short focusedSpriteRealY;
		if (framesBehind > 0) {
			// SPG: During spindash lag catchup, use the average of two consecutive
			// historical positions. This smoothly interpolates through the 64 recorded
			// positions (32 during freeze + 32 during catchup) over 32 frames.
			// Position pair: (framesBehind) and (framesBehind - 1)
			int idx1 = framesBehind;
			int idx2 = Math.max(0, framesBehind - 1);
			short x1 = focusedSprite.getCentreX(idx1);
			short x2 = focusedSprite.getCentreX(idx2);
			short y1 = focusedSprite.getCentreY(idx1);
			short y2 = focusedSprite.getCentreY(idx2);
			// Average the two positions (add together and use as the target)
			focusedSpriteRealX = (short) (((x1 + x2) / 2) - x);
			focusedSpriteRealY = (short) (((y1 + y2) / 2) - y);
		} else {
			focusedSpriteRealX = (short) (focusedSprite.getCentreX() - x);
			focusedSpriteRealY = (short) (focusedSprite.getCentreY() - y);
		}

		if (focusedSpriteRealX < 144) {
			short difference = (short) (focusedSpriteRealX - 144);
			if (difference > 16) {
				x -= 16;
			} else {
				x += difference;
			}
		} else if (focusedSpriteRealX > 160) {
			short difference = (short) (focusedSpriteRealX - 160);
			if (difference > 16) {
				x += 16;
			} else {
				x += difference;
			}
		}

		if (focusedSprite.getAir()) {
			if (focusedSpriteRealY < 96) {
				short difference = (short) (focusedSpriteRealY - 96);
				if (difference < -16) {
					y -= 16;
				} else {
					y += difference;
				}
			} else if (focusedSpriteRealY > 160) {
				short difference = (short) (focusedSpriteRealY - 160);
				if (difference > 16) {
					y += 16;
				} else {
					y += difference;
				}
			}
		} else if (focusedSpriteRealY > 96) {
			short ySpeed = (short) (focusedSprite.getYSpeed() / 256);
			short difference = (short) (focusedSpriteRealY - 96);
			byte tolerance;

			if (ySpeed > 6) {
				tolerance = 16;
			} else {
				tolerance = 6;
			}

			if (difference > tolerance) {
				y += tolerance;
			} else {
				y += difference;
			}
		} else if (focusedSpriteRealY < 96) {
			short ySpeed = (short) (focusedSprite.getYSpeed() / 256);
			short difference = (short) (focusedSpriteRealY - 96);
			byte tolerance;

			if (ySpeed < -6) {
				tolerance = -16;
			} else {
				tolerance = -6;
			}

			if (difference < tolerance) {
				y += tolerance;
			} else {
				y += difference;
			}
		}
		if (x < 0) {
			x = 0;
		}
		if (y < 0) {
			y = 0;
		}
		if (x > maxX) {
			x = maxX;
		}
		if (y > maxY) {
			y = maxY;
		}
		if (framesBehind > 0) {
			// SPG: Decrement by 2 each frame since we're processing position pairs
			// This means we catch up through the full 64 positions (32 freeze + 32 catchup)
			// in approximately 32 frames
			framesBehind -= 2;
			if (framesBehind < 0) {
				framesBehind = 0;
			}
		}
	}

	public void setFrozen(boolean frozen) {
		this.frozen = frozen;
		// SPG: When unfreezing, do NOT reset framesBehind!
		// The camera should continue using the position history while framesBehind > 0,
		// gradually catching up to the current player position by processing
		// pairs of positions from history.
	}

	public boolean getFrozen() {
		return frozen;
	}

	/**
	 * Updates boundary easing - call once per frame.
	 * ROM behavior from RunDynamicLevelEvents (s2.asm:20297-20332):
	 * - Eases maxY toward target at 2px/frame (or 8px if accelerated)
	 * - When decreasing: if camera Y > target, snap maxY to camera Y first, then subtract
	 * - When increasing: if camera Y+8 >= maxY AND player airborne, use 4x speed (8px/frame)
	 * - Sets maxYChanging flag while boundary is transitioning
	 */
	public void updateBoundaryEasing() {
		maxYChanging = false;

		// Ease maxY toward target (ROM: s2.asm:20303-20332)
		if (maxY != maxYTarget) {
			short step = BOUNDARY_EASE_STEP; // d1 = 2
			short diff = (short) (maxYTarget - maxY);

			if (diff < 0) {
				// Decreasing max Y (target < current) - ROM lines 20308-20316
				step = (short) -BOUNDARY_EASE_STEP; // neg.w d1

				// If camera Y > target, snap maxY to camera Y first
				if (y > maxYTarget) {
					maxY = (short) (y & 0xFFFE); // Align to even pixels
				}
				// Always add step (subtract 2) after potential snap
				maxY += step;
			} else {
				// Increasing max Y (target > current) - ROM lines 20320-20331
				// Check for acceleration: camera Y + 8 >= maxY AND player airborne
				if (focusedSprite != null && (y + 8) >= maxY && focusedSprite.getAir()) {
					step = (short) (BOUNDARY_EASE_STEP * 4); // 8 pixels/frame
				}
				maxY += step;
			}

			// Clamp to target if we overshot
			if ((diff > 0 && maxY > maxYTarget) || (diff < 0 && maxY < maxYTarget)) {
				maxY = maxYTarget;
			}

			maxYChanging = true;
		}

		// Ease minY toward target (simple 2px/frame, no acceleration)
		if (minY != minYTarget) {
			short diff = (short) (minYTarget - minY);
			if (diff > 0) {
				minY += Math.min(diff, BOUNDARY_EASE_STEP);
			} else {
				minY += Math.max(diff, -BOUNDARY_EASE_STEP);
			}
		}

		// Ease maxX toward target
		if (maxX != maxXTarget) {
			short diff = (short) (maxXTarget - maxX);
			if (diff > 0) {
				maxX += Math.min(diff, BOUNDARY_EASE_STEP);
			} else {
				maxX += Math.max(diff, -BOUNDARY_EASE_STEP);
			}
		}

		// Ease minX toward target
		if (minX != minXTarget) {
			short diff = (short) (minXTarget - minX);
			if (diff > 0) {
				minX += Math.min(diff, BOUNDARY_EASE_STEP);
			} else {
				minX += Math.max(diff, -BOUNDARY_EASE_STEP);
			}
		}
	}

	/**
	 * Returns true if maxY is currently easing toward its target.
	 * ROM: Camera_Max_Y_Pos_Changing flag
	 */
	public boolean isMaxYChanging() {
		return maxYChanging;
	}

	public boolean isOnScreen(Sprite sprite) {
		int xLower = x;
		int yLower = y;
		int xUpper = x + width;
		int yUpper = y + height;
		int spriteX = sprite.getX();
		int spriteY = sprite.getY();
		return spriteX >= xLower && spriteY >= yLower && spriteX <= xUpper
				&& spriteY <= yUpper;
	}

	public void setFocusedSprite(AbstractPlayableSprite sprite) {
		this.focusedSprite = sprite;
		x = sprite.getX();
		y = sprite.getY();
	}

	public AbstractPlayableSprite getFocusedSprite() {
		return focusedSprite;
	}

	public short getX() {
		return x;
	}

	public void setX(short x) {
		this.x = x;
	}

	public short getY() {
		return y;
	}

	public void setY(short y) {
		this.y = y;
	}

	public short getWidth() {
		return width;
	}

	public short getHeight() {
		return height;
	}

	public short getMinX() {
		return minX;
	}

	/**
	 * Sets minX immediately (both current and target).
	 * Use setMinXTarget() for smooth easing.
	 */
	public void setMinX(short minX) {
		this.minX = minX;
		this.minXTarget = minX;
	}

	/**
	 * Sets minX target for smooth easing.
	 * Current minX will ease toward this value at 2px/frame.
	 */
	public void setMinXTarget(short minXTarget) {
		this.minXTarget = minXTarget;
	}

	public short getMinXTarget() {
		return minXTarget;
	}

	public short getMinY() {
		return minY;
	}

	/**
	 * Sets minY immediately (both current and target).
	 * Use setMinYTarget() for smooth easing.
	 */
	public void setMinY(short minY) {
		this.minY = minY;
		this.minYTarget = minY;
	}

	/**
	 * Sets minY target for smooth easing.
	 * Current minY will ease toward this value at 2px/frame.
	 */
	public void setMinYTarget(short minYTarget) {
		this.minYTarget = minYTarget;
	}

	public short getMinYTarget() {
		return minYTarget;
	}

	public short getMaxX() {
		return maxX;
	}

	/**
	 * Sets maxX immediately (both current and target).
	 * Use setMaxXTarget() for smooth easing.
	 */
	public void setMaxX(short maxX) {
		this.maxX = maxX;
		this.maxXTarget = maxX;
	}

	/**
	 * Sets maxX target for smooth easing.
	 * Current maxX will ease toward this value at 2px/frame.
	 */
	public void setMaxXTarget(short maxXTarget) {
		this.maxXTarget = maxXTarget;
	}

	public short getMaxXTarget() {
		return maxXTarget;
	}

	public short getMaxY() {
		return maxY;
	}

	/**
	 * Sets maxY immediately (both current and target).
	 * Use setMaxYTarget() for smooth easing.
	 */
	public void setMaxY(short maxY) {
		this.maxY = maxY;
		this.maxYTarget = maxY;
	}

	/**
	 * Sets maxY target for smooth easing.
	 * Current maxY will ease toward this value at 2px/frame.
	 * ROM: Camera_Max_Y_pos_target
	 */
	public void setMaxYTarget(short maxYTarget) {
		this.maxYTarget = maxYTarget;
	}

	public short getMaxYTarget() {
		return maxYTarget;
	}

	public void incrementX(short amount) {
		x += amount;
	}

	public void incrementY(short amount) {
		y += amount;
	}

	public static synchronized Camera getInstance() {
		if (camera == null) {
			camera = new Camera();
		}
		return camera;
	}
}
