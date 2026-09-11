package com.openggf.camera;

import com.openggf.configuration.DeadzoneMode;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.rules.CameraRules;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.CameraSnapshot;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.playable.AbstractPlayableSprite;

public class Camera implements RewindSnapshottable<CameraSnapshot> {
	/** Receives invocation-level camera lifecycle events without replacing camera state. */
	public interface UpdateObserver {
		void onUpdatePosition(boolean force);
		void onUpdateBoundaryEasing();
	}

	private UpdateObserver updateObserver;
	private short x = 0;
	private short y = 0;

	// ROM Camera_X/Y_pos_copy: the position published by ScreenEvents for
	// sprite visibility and screen rendering. Zone event code can move the
	// physical camera after this publication without changing the copy.
	private short renderCopyX = 0;
	private short renderCopyY = 0;

	private short minX;
	private short minY;
	private short maxX;
	private short maxY;
	private short maxXBeforeBoundaryEasing;

	// Screen shake offsets (ROM: applied to Camera_X_pos_copy and Camera_Y_pos_copy)
	// These offsets affect both FG tiles and sprites to create unified screen shake.
	private short shakeOffsetX = 0;
	private short shakeOffsetY = 0;

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

	// ROM camera/boundary ordering (S1 DeformLayers (REV01).asm:16-18): ScrollHoriz
	// + ScrollVertical (camera move + clamp to the prior-frame v_limitbtm2) run
	// BEFORE DynamicLevelEvents (zone handler + bottom-boundary easing).
	// LevelFrameStep mirrors this: updatePosition() runs before the zone event
	// handler + updateBoundaryEasing(). So updatePosition() clamps to the maxY left
	// by the PREVIOUS frame's easing, and the airborne +8 boundary acceleration
	// applied by this frame's updateBoundaryEasing() (which reads the POST-scroll
	// camera, ROM v_screenposy) reaches the camera on the NEXT frame — matching ROM
	// without any explicit one-frame deferral state.

	// ROM: Horiz_scroll_delay_val - horizontal scroll delay counter
	// When > 0, horizontal scroll uses position history while vertical scroll continues normally
	private int horizScrollDelayFrames = 0;

	// Full camera freeze (both X and Y) - used for death, cutscenes, etc.
	// This is separate from horizScrollDelayFrames which only affects horizontal scroll.
	private boolean frozen = false;
	private boolean deferHorizontalBoundaryClampOnce = false;

	// ROM: Level_started_flag.
	// Used by HUD/start-state flow and intro/cutscene sequencing.
	// This flag does NOT freeze camera scroll; use `frozen` for camera suppression.
	private boolean levelStarted = true;

	// ROM: Vertical wrapping — coordinates wrap modularly when top boundary is negative.
	// S1 LZ3/SBZ2: range 0x800 (DeformLayers.asm lines 542-580)
	// S3K zones with negative minY: range = level height (e.g. 0x1000 for MGZ1's 32-row map)
	private boolean verticalWrapEnabled = false;
	private int verticalWrapRange = 0x800;     // Default S1 range; overridden per-level
	private int verticalWrapMask = 0x7FF;      // Range - 1
	public static final int VERTICAL_WRAP_RANGE = 0x800;  // S1 default; referenced by LevelManager and GraphicsManager
	private static final int VERTICAL_WRAP_BG_MASK = 0x3FF; // AND mask for BG Y
	// Tracks whether a wrap occurred this frame, and the delta applied
	private boolean lastFrameWrapped = false;
	private short wrapDeltaY = 0;

	private AbstractPlayableSprite focusedSprite;

	private short width;
	private short height;
	private DeadzoneMode deadzoneMode = DeadzoneMode.PROPORTIONAL;

	// ROM: Camera_Y_pos_bias - vertical position target for camera centering
	// Default is (224/2)-16 = 96 (0x60). Used as center point for scroll windows.
	private static final short DEFAULT_Y_BIAS = 96;

	// ROM: Look up target bias: 0xC8 (200) - shifts camera up to show more above Sonic
	private static final short LOOK_UP_BIAS = (short) 0xC8;

	// ROM: Look down target bias: 8 - shifts camera down to show more below Sonic
	private static final short LOOK_DOWN_BIAS = 8;

	// ROM: Camera_Y_pos_bias - dynamic bias that can change during gameplay
	// (looking up/down, spindash, etc). Starts at 96.
	private short yPosBias = DEFAULT_Y_BIAS;

	// ROM: Airborne window is ±0x20 (32) around the bias
	private static final short AIRBORNE_WINDOW_HALF = 32;

	// ROM: Inertia threshold for fast scroll (0x800 = 2048)
	private static final short FAST_SCROLL_INERTIA_THRESHOLD = 0x800;

	// ROM: Maximum per-frame camera step used by the fast vertical paths and by
	// the horizontal catch-up clamp in ScrollHoriz / MoveCameraX.
	// S1/S2: 16 (0x10) pixels/frame.
	// S3K:   24 (0x18) pixels/frame.
	// Set per-game via setFastScrollCap().
	private static final short DEFAULT_FAST_SCROLL_CAP = 16;
	private short fastScrollCap = DEFAULT_FAST_SCROLL_CAP;

	// ROM S1 (FixBugs=0): the leftward horizontal camera move is UNCAPPED — only the
	// rightward move caps at fastScrollCap. The leftward cap is gated behind
	// `if FixBugs` (FixBugs=0 in the shipped ROM), so SH_MoveCameraLeft runs straight
	// to .moveLeft and adds the full (possibly >16px) offset
	// (docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm:59-99). S2 (s2.asm:18102-
	// 18105) and S3K (sonic3k.asm:38403-38406) cap BOTH directions, so this stays
	// false for them. Set per-game from CameraRules.uncappedLeftwardHorizontalScroll.
	private boolean uncappedLeftwardHorizontalScroll = false;

	// ROM: Fast_V_scroll_flag. Moving solids request this for the current frame
	// when the player is standing on them, so grounded vertical follow uses the
	// fast cap even if the player's own ground speed is low.
	private boolean fastVerticalScrollRequested = false;

	// ROM: Scroll_force_positions + Scroll_forced_X_pos/Scroll_forced_Y_pos.
	// Traversal objects (MHZ swing vine, vertical swing bar; AIZ ride vine) set
	// these so the camera position math tracks the supplied forced coordinates
	// instead of Player_1 for that frame, and the horizontal scroll frame offset
	// (H_scroll_frame_offset) is zeroed. Frame-scoped; cleared each update.
	private boolean forcedScrollRequested = false;
	private int forcedScrollX = 0;
	private int forcedScrollY = 0;

	public Camera() {
		this(GameServices.configuration());
	}

	public Camera(SonicConfigurationService configService) {
		width = configService.getShort(SonicConfiguration.SCREEN_WIDTH_PIXELS);
		height = configService.getShort(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
		deadzoneMode = DeadzoneMode.parse(
				configService.getString(SonicConfiguration.WIDESCREEN_DEADZONE_MODE));
	}

	public void updatePosition() {
		updatePosition(false);
	}

	public void updatePosition(boolean force) {
		if (updateObserver != null) {
			updateObserver.onUpdatePosition(force);
		}
		if (force) {
			// Position camera using ROM's level-load formula:
			//   v_screenposx = MainCharacter.x_pos - $A0  (subi.w #160,d1)
			//   v_screenposy = MainCharacter.y_pos - $60  (subi.w  #96,d0)
			// then clamp to the level bounds. References: s1disasm
			// _inc/LevelSizeLoad & BgScrollSpeed.asm:111,124; s2.asm:14787,14798;
			// sonic3k.asm:38241. ROM places the sprite at screen-x=160 (right edge
			// of the 144-160 horizontal scroll deadzone), not the deadzone
			// midpoint at 152.
			x = (short) (focusedSprite.getCentreX() - DeadzoneGeometry.rightEdge(width));
			y = (short) (focusedSprite.getCentreY() - 96);

			// Apply bounds clamping.
			// If max < min, treat the upper bound as wrapped/unbounded for this signed domain.
			// SCZ ObjB2 writes Camera_Max_X_pos = Camera_X_pos - $40, which can transiently
			// produce max < min at low X in this engine representation.
			x = clampAxisWithWrap(x, minX, maxX);
			y = clampAxisWithWrap(y, minY, maxY);
			fastVerticalScrollRequested = false;
			forcedScrollRequested = false;
			return;
		}

		// Full camera freeze (death, cutscenes) - don't update X or Y at all
		if (frozen) {
			fastVerticalScrollRequested = false;
			forcedScrollRequested = false;
			return;
		}

		// ROM loc_1BFB8: a forced-scroll frame zeroes H_scroll_frame_offset before
		// the camera-position math runs (move.w #0,(H_scroll_frame_offset).w). The
		// forced coordinates then feed MoveCameraX/MoveCameraY below in place of the
		// focused sprite's position (see currentFocusCentreX/Y).
		if (forcedScrollRequested) {
			horizScrollDelayFrames = 0;
		}

		// ROM behavior: Horiz_scroll_delay_val only affects horizontal scrolling.
		// Vertical scrolling (ScrollVerti) always uses current position and runs normally.
		// See s2.asm ScrollHoriz (line ~18009) vs ScrollVerti (line ~18112).

		// Horizontal scroll - may use position history if delay is active
		boolean deferHorizontalClampThisFrame = deferHorizontalBoundaryClampOnce;
		deferHorizontalBoundaryClampOnce = false;
		x = computeNextHorizontalCameraX(true, !deferHorizontalClampThisFrame);

		// Vertical scroll - always uses current position (ROM: ScrollVerti has no delay)
		// ROM: d0 = (v_player+obY).w - (v_screenposy).w
		// When vertical wrapping is active, compute using modular arithmetic to handle
		// cases where Sonic and camera are in different wrap periods (e.g. Sonic's Y was
		// updated by ground collision across the wrap boundary between frames).
		short focusedSpriteRealY;
		int focusCentreY = currentFocusCentreY();
		if (verticalWrapEnabled) {
			int diff = focusCentreY - (int) y;
			diff = ((diff % verticalWrapRange) + verticalWrapRange) % verticalWrapRange;
			if (diff > verticalWrapRange / 2) {
				diff -= verticalWrapRange;
			}
			focusedSpriteRealY = (short) diff;
		} else {
			focusedSpriteRealY = (short) (focusCentreY - y);
		}

		short yBeforeVerticalScroll = y;

		// ROM: ScrollVerti rolling height compensation. When the player is rolling
		// their height shrinks, so the Y delta is reduced to stop the camera jolting.
		//
		// fixBugs (s2.asm:27 `fixBugs = 0`, block at s2.asm:18156-18165): the engine
		// implements the SHIPPED (fixBugs=0) branch — a flat `subq.w #5,d0` for whoever
		// the camera is focused on, character-independent. The fixBugs=1 branch adds
		// `cmpi.b #ObjID_Tails,id(a0) / addq.w #4,d0`, i.e. only 1 for Tails, because
		// Tails is four pixels shorter and the flat 5 makes his camera jolt slightly on
		// entering and leaving a roll. The disassembly notes that not even S3K fixed
		// this; S1 (`_inc/ScrollHoriz & ScrollVertical.asm`, subq.w
		// #sonic_height-sonic_roll_height) has no Tails at all, so the flat subtraction
		// is correct for all three games.
		if (focusedSprite.getRolling()) {
			focusedSpriteRealY -= 5;
		}

		// Vertical scroll logic (ROM: ScrollVerti)
		if (focusedSprite.getAir()) {
			// ROM: Airborne uses ±0x20 window around bias
			// Upper bound: bias - 32, Lower bound: bias + 32
			short upperBound = (short) (yPosBias - AIRBORNE_WINDOW_HALF);
			short lowerBound = (short) (yPosBias + AIRBORNE_WINDOW_HALF);
			if (focusedSpriteRealY < upperBound) {
				short difference = (short) (focusedSpriteRealY - upperBound);
				if (difference < -fastScrollCap) {
					y -= fastScrollCap;
				} else {
					y += difference;
				}
			} else if (focusedSpriteRealY >= lowerBound) {
				short difference = (short) (focusedSpriteRealY - lowerBound);
				if (difference > fastScrollCap) {
					y += fastScrollCap;
				} else {
					y += difference;
				}
			}
		} else {
			// ROM: s2.asm:18150-18195 - Grounded vertical scroll
			// Uses bias state and inertia (ground speed), NOT ySpeed
			short difference = (short) (focusedSpriteRealY - yPosBias);

			if (difference != 0) {
				// ROM: .decideScrollType - choose scroll cap based on bias and inertia
				short tolerance;
				if (yPosBias != DEFAULT_Y_BIAS) {
					// ROM: .doScroll_slow - bias is not normal (looking up/down)
					// Use 2px cap
					tolerance = 2;
				} else {
					// Bias is normal (96) - check inertia for medium vs fast
					short absInertia = (short) Math.abs(focusedSprite.getGSpeed());
					if (fastVerticalScrollRequested || absInertia >= FAST_SCROLL_INERTIA_THRESHOLD) {
						// ROM: .doScroll_fast - player moving very fast on ground
						// S2: 16px cap, S3K: 24px cap
						tolerance = fastScrollCap;
					} else {
						// ROM: .doScroll_medium - normal ground movement
						// Use 6px cap
						tolerance = 6;
					}
				}

				// Apply scroll with capping
				if (difference > 0) {
					// Scroll down
					if (difference > tolerance) {
						y += tolerance;
					} else {
						y += difference;
					}
				} else {
					// Scroll up (difference is negative)
					if (difference < -tolerance) {
						y -= tolerance;
					} else {
						y += difference;
					}
				}
			}
			// else: ROM: .doNotScroll - player is at bias, no scroll needed
		}

		// ROM: LZ3/SBZ2 vertical wrapping (DeformLayers.asm lines 542-580)
		// When wrapping is active, coordinate masking replaces normal Y clamping.
		lastFrameWrapped = false;
		wrapDeltaY = 0;
		if (verticalWrapEnabled) {
			// Upward wrap: camera Y at or below -256 (0xFF00 signed)
			// ROM: cmpi.w #-$100,d1 / bgt.s .noupwrap — wraps when d1 <= -$100
			if (y <= -0x100) {
				short oldY = y;
				y = (short) (y & verticalWrapMask);
				wrapFocusedSpriteYPositionWord();
				lastFrameWrapped = true;
				wrapDeltaY = (short) (y - oldY);
			}
			// Downward wrap: camera Y reached bottom boundary
			// ROM: cmp.w (Camera_Max_Y_pos).w,d1 / blt.s .nodownwrap / sub.w d0,y
			else if (y >= verticalWrapRange) {
				short oldY = y;
				y = (short) (y - verticalWrapRange);
				wrapFocusedSpriteYPositionWord();
				lastFrameWrapped = true;
				wrapDeltaY = (short) (y - oldY);
			}
		}

		// Horizontal boundary clamping already happened inside
		// computeNextHorizontalCameraX (ROM MoveScreenHoriz applies the boundary
		// directionally, inside the scroll branch). A symmetric re-clamp here would
		// re-introduce the left-boundary pull on a rightward scroll, so only the
		// vertical clamp remains below.
		// ROM: After a vertical wrap, DeformLayers.asm branches directly to loc_6724
		// (the store), skipping the normal boundary clamp. This is critical because
		// after wrapping from e.g. -260 to 1788, clamping to maxY could force the
		// camera to a different position than Sonic was wrapped to.
		// Normal (non-wrap) frames still clamp, which handles pit death in SBZ2
		// where v_limitbtm2=$510 constrains the camera even though wrapping is active.
		// ROM applies the VERTICAL level boundaries DIRECTIONALLY, mirroring the
		// horizontal MoveScreenHoriz split — NOT as a symmetric [minY, maxY] clamp:
		//   - SV_MoveCameraUp (camera scrolling up) falls through to SV_TopBoundary
		//     (docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm:204-218), which
		//     clamps ONLY against the top boundary (v_limittop2 / engine minY).
		//   - SV_MoveCameraDown (camera scrolling down) falls through to
		//     SV_BottomBoundary (line 248-261), which clamps ONLY against the bottom
		//     boundary (v_limitbtm2 / engine maxY).
		//   - The sweet-spot path consults f_bgscrollvert (lines 148-149, 157-158):
		//     when the bottom level boundary moved on the PREVIOUS frame it branches
		//     to SV_BottomBoundaryMoving (line 210), forcing d0=0 and falling through
		//     SV_SweetSpot -> SV_BottomBoundary (line 259) — a BOTTOM-only clamp,
		//     EVEN when the normal scroll produced no movement. Without that flag it
		//     hits SV_NoUpdate (line 152) and clamps against NOTHING.
		// A symmetric [minY, maxY] clamp wrongly re-clamps an upward scroll against
		// the bottom boundary: when the bottom boundary eases UP into the rising
		// camera (S1 MZ2 f13473, camera air-tracking up to 0x201 while v_limitbtm2
		// just eased to 0x200), the bottom clamp yanked the camera one pixel early to
		// 0x200. The ROM's up path never consults the bottom boundary, so the camera
		// is allowed to sit transiently below it.
		//
		// ROM order (DeformLayers (REV01).asm:16-18): ScrollVertical runs BEFORE
		// DynamicLevelEvents, so it clamps to the v_limitbtm2 left by the PREVIOUS
		// frame's DynamicLevelEvents, and consults the f_bgscrollvert that frame set.
		// LevelFrameStep mirrors this: updatePosition() (ScrollVertical) runs before
		// the zone event handler + updateBoundaryEasing() (DynamicLevelEvents). So at
		// this point maxY already holds the prior-frame boundary and maxYChanging
		// mirrors the prior-frame f_bgscrollvert — both ROM-correct without any extra
		// one-frame deferral. (The airborne +8 boundary acceleration applied by
		// updateBoundaryEasing later this frame therefore reaches the camera on the
		// NEXT frame, matching ROM — S1 MZ1 f2101.) The GHZ2 f3349 rising-boundary
		// case is covered because maxYChanging keeps the bottom clamp live on a
		// sweet-spot frame whose scroll produced no movement.
		//
		// After a vertical wrap, ROM DeformLayers.asm branches directly to loc_6724
		// (the store), skipping the normal boundary clamp, so a wrapped frame clamps
		// nothing here. Non-wrap frames still clamp (handles SBZ2 pit death where
		// v_limitbtm2=$510 constrains the downward camera even though wrapping is
		// active).
		if (!lastFrameWrapped) {
			if (y < yBeforeVerticalScroll) {
				// Camera scrolled up -> SV_TopBoundary (top boundary only).
				y = clampTopBoundary(y);
			} else if (y > yBeforeVerticalScroll) {
				// Camera scrolled down -> SV_BottomBoundary (bottom boundary only).
				y = clampBottomBoundary(y);
			} else if (maxYChanging) {
				// No scroll, but bottom boundary moved last frame
				// (f_bgscrollvert) -> SV_BottomBoundaryMoving -> SV_BottomBoundary.
				y = clampBottomBoundary(y);
			}
			// else: ROM SV_NoUpdate - no scroll, no boundary clamp.
		}
		fastVerticalScrollRequested = false;
		forcedScrollRequested = false;
	}

	/**
	 * Returns the X the camera should track this frame: the forced X when a
	 * forced-scroll request is active (ROM Scroll_forced_X_pos), else the focused
	 * sprite's centre X.
	 */
	private int currentFocusCentreX() {
		return forcedScrollRequested ? forcedScrollX : focusedSprite.getCentreX();
	}

	/**
	 * Returns the Y the camera should track this frame: the forced Y when a
	 * forced-scroll request is active (ROM Scroll_forced_Y_pos), else the focused
	 * sprite's centre Y.
	 */
	private int currentFocusCentreY() {
		return forcedScrollRequested ? forcedScrollY : focusedSprite.getCentreY();
	}

	private void wrapFocusedSpriteYPositionWord() {
		if (focusedSprite == null) {
			return;
		}
		// ROM masks only the y_pos word when Screen_Y_wrap_value is active
		// (sonic3k.asm:21989-21992, 26233-26236; MGZ sets #$FFF at
		// sonic3k.asm:102200). Preserve y_sub just like a 68000 word write.
		focusedSprite.setCentreYPreserveSubpixel((short) (focusedSprite.getCentreY() & verticalWrapMask));
	}

	/**
	 * Predicts the horizontal camera position that {@link #updatePosition()} will
	 * commit on this frame without consuming scroll-delay history.
	 * This lets event scripts reason about end-of-frame camera thresholds while
	 * preserving the actual camera state for the later camera step.
	 */
	public short previewNextX() {
		if (focusedSprite == null || frozen) {
			return x;
		}
		return computeNextHorizontalCameraX(false, true);
	}

	private short computeNextHorizontalCameraX(boolean consumeDelayState, boolean applyBoundaryClamp) {
		short nextX = x;
		short focusedSpriteRealX;
		if (horizScrollDelayFrames > 0) {
			// ROM: MoveCameraX stores the delay count in the high byte of
			// H_scroll_frame_offset and subtracts $100 before sampling Pos_table.
			// Our history buffer is also one frame behind by the time camera scroll
			// runs, so delay N maps to the buffered position from N-1 frames ago.
			int historyIndex = Math.max(0, Math.min(horizScrollDelayFrames - 1, 63));
			focusedSpriteRealX = (short) (focusedSprite.getCentreX(historyIndex) - nextX);
			if (consumeDelayState) {
				horizScrollDelayFrames--;
			}
		} else {
			focusedSpriteRealX = (short) (currentFocusCentreX() - nextX);
		}

		short cameraStepCap = fastScrollCap;

		// Horizontal scroll logic (ROM: ScrollHoriz / MoveScreenHoriz).
		//
		// ROM applies the horizontal level boundaries DIRECTIONALLY, not as a
		// symmetric [min,max] clamp:
		//   - SH_MoveCameraLeft (camera scrolling left) clamps ONLY against
		//     v_limitleft2 (engine minX).
		//   - SH_MoveCameraRight (camera scrolling right) clamps ONLY against
		//     v_limitright2 (engine maxX).
		//   - The sweet-spot path (Sonic within the 144-160 deadzone) performs no
		//     scroll and no boundary clamp at all.
		// (docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm MoveScreenHoriz;
		//  s2.asm / sonic3k.asm share the same direction-split structure.)
		//
		// This matters at the end of an act: the signpost sets
		// v_limitleft2 = v_limitright2 to lock the screen, but while Sonic keeps
		// running right the camera only ever consults v_limitright2, so the raised
		// left boundary never yanks the camera forward. A symmetric clamp here
		// clamped the camera UP to the new minX a few frames early (S1 LZ1 f12463).
		// FLAG: FixBugs (docs/s1disasm/sonic.asm:20 -- 0 in the shipped ROM).
		// MoveScreenHoriz's two deadzone tests are `bcs`/`bcc` (UNSIGNED) under
		// FixBugs = 0 and `blt`/`bge` (SIGNED) under FixBugs = 1
		// (docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm:38-52). The engine's
		// signed int compares below agree with BOTH branches for every reachable
		// value: the first subtraction cannot borrow unless the result is negative,
		// and the second test only runs once the first proved the value non-negative,
		// so the "horizontal wrap" the fix targets is unreachable at S1 level extents.
		// No behavioural choice is being made here.
		int deadzoneLeft = DeadzoneGeometry.leftEdge(width, deadzoneMode);
		int deadzoneRight = DeadzoneGeometry.rightEdge(width);
		if (focusedSpriteRealX < deadzoneLeft) {
			short difference = (short) (focusedSpriteRealX - deadzoneLeft);
			// ROM S1 leaves the leftward move uncapped (FixBugs=0); S2/S3K cap it.
			if (!uncappedLeftwardHorizontalScroll && difference < -cameraStepCap) {
				nextX -= cameraStepCap;
			} else {
				nextX += difference;
			}
			// ROM SH_MoveCameraLeft: clamp only against the left boundary.
			if (applyBoundaryClamp) {
				nextX = clampLeftBoundary(nextX);
			}
		} else if (focusedSpriteRealX >= deadzoneRight) {
			short difference = (short) (focusedSpriteRealX - deadzoneRight);
			if (difference > cameraStepCap) {
				nextX += cameraStepCap;
			} else {
				nextX += difference;
			}
			// ROM SH_MoveCameraRight: clamp only against the right boundary.
			if (applyBoundaryClamp) {
				nextX = clampRightBoundary(nextX);
			}
		}
		// else: ROM sweet spot - no scroll, no boundary clamp.

		return nextX;
	}

	/**
	 * ROM SH_MoveCameraLeft clamp: enforce only the left boundary (v_limitleft2).
	 */
	private short clampLeftBoundary(short value) {
		return value < minX ? minX : value;
	}

	/**
	 * ROM SH_MoveCameraRight clamp: enforce only the right boundary
	 * (v_limitright2), including the transient state where it is below the left
	 * boundary. The 68000 routine compares this word directly and does not
	 * normalize the pair first.
	 */
	private short clampRightBoundary(short value) {
		return value > maxX ? maxX : value;
	}

	/**
	 * Keeps newly written horizontal bounds available to object/player logic while
	 * delaying the visible camera clamp until the following camera step.
	 */
	public void deferHorizontalBoundaryClampOnce() {
		deferHorizontalBoundaryClampOnce = true;
	}

	/**
	 * ROM SV_TopBoundary clamp: enforce only the top boundary (v_limittop2 / minY)
	 * when the camera scrolled up. The bottom boundary is never consulted on the
	 * up path (docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm:204-218).
	 */
	private short clampTopBoundary(short value) {
		return value < minY ? minY : value;
	}

	/**
	 * ROM SV_BottomBoundary clamp: enforce only the bottom boundary (v_limitbtm2 /
	 * maxY) when the camera scrolled down (or the bottom boundary is moving). The
	 * top boundary is never consulted on the down path
	 * (docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm:258-271): the routine
	 * compares d1 against v_limitbtm2 alone and, on the no-wrap branch, writes
	 * v_limitbtm2 straight into d1.
	 * <p>
	 * That holds even when the bottom bound sits ABOVE the top bound. The state is
	 * ordinary rather than degenerate: DynamicLevelEvents' move-up branch snaps
	 * v_limitbtm2 to {@code (v_screenposy & $FFFE) - 2} whenever the camera is
	 * below the new v_limitbtm1 (DynamicLevelEvents.asm:17-28), which drops the
	 * bottom bound below a v_limittop2 the zone handler left in place. The camera
	 * then rides the bottom bound upward past the top bound, two pixels per frame,
	 * and never touches SV_MoveCameraUp's top clamp on the way (S1 MZ1 row 3,220:
	 * v_limittop2 $340, v_limitbtm2 $33E, recorded camera $33E). Consulting minY
	 * here left the camera unclamped for the whole ascent.
	 * <p>
	 * S3K reaches the same clamp by a different route, and it is LIVE — do not gate
	 * it. Its {@code loc_1C202} (docs/skdisasm/sonic3k.asm:38561-38569) compares d1
	 * against Camera_max_Y_pos, then subtracts {@code Screen_Y_wrap_value + 1} and
	 * takes the hard clamp {@code move.w 6(a2),d1} at {@code loc_1C216} only when
	 * that subtraction borrows. {@code Get_LevelSizeStart} writes
	 * {@code Screen_Y_wrap_value = -1} (sonic3k.asm:38093), which would make the
	 * subtraction unable to borrow — but that write survives for exactly one
	 * DeformBgLayer call, because {@code LevelSetup} (sonic3k.asm:102205) then
	 * writes {@code #$FFF} unconditionally for every level before LevelLoop begins
	 * (sonic3k.constants.asm:434 documents the field as "either $7FF or $FFF").
	 * So every gameplay frame borrows and clamps. The non-borrowing arm
	 * ({@code sub.w d3,(a1)}) is the vertical WRAP, reached only where
	 * Camera_max_Y_pos >= the wrap value + 1 — which the ROM's looping levels do
	 * arrange, by writing {@code #$7FF} over the {@code $FFF} default in their
	 * screen-init routines (ICZ1 {@code loc_53648}, sonic3k.asm:110069, commented
	 * "We're in a looping level!"; SOZ2 :114222/:114251; Slots :119055) or by
	 * carrying a LevelSizes yend of {@code $1000}. The engine does model that arm:
	 * {@code LevelManager.initCameraForLevel} calls
	 * {@link #setVerticalWrapEnabled(boolean, int)} with the layout height for
	 * every level whose LevelSizes ystart is negative — exactly the S3K looping
	 * levels — and the recorded ICZ1/SOZ2/MGZ1 wraps replay correctly.
	 * See docs/status/trace-frontier-log.md, 2026-08-15 rounds 3 and 4.
	 */
	private short clampBottomBoundary(short value) {
		return value > maxY ? maxY : value;
	}

	private short clampAxisWithWrap(short value, short min, short max) {
		if (max < min) {
			return value < min ? min : value;
		}
		if (value < min) {
			return min;
		}
		if (value > max) {
			return max;
		}
		return value;
	}

	/**
	 * Sets horizontal scroll delay frames (ROM: Horiz_scroll_delay_val).
	 * When delay > 0, horizontal scroll uses position history while vertical scroll
	 * continues normally. This matches ROM behavior where ScrollHoriz checks
	 * Horiz_scroll_delay_val but ScrollVerti does not.
	 *
	 * @param delayFrames Number of frames to delay horizontal scroll (0 to clear)
	 */
	public void setHorizScrollDelay(int delayFrames) {
		this.horizScrollDelayFrames = delayFrames;
	}

	/**
	 * @return Current horizontal scroll delay frames remaining
	 */
	public int getHorizScrollDelay() {
		return horizScrollDelayFrames;
	}

	/**
	 * Sets full camera freeze (both X and Y).
	 * Use this for death, cutscenes, boss arenas, etc. where the camera should
	 * completely stop following the player.
	 *
	 * For spindash-style horizontal-only delay, use setHorizScrollDelay() instead.
	 *
	 * @param frozen true to freeze camera, false to unfreeze
	 */
	public void setFrozen(boolean frozen) {
		this.frozen = frozen;
		// When unfreezing, also clear any horizontal delay
		if (!frozen) {
			this.horizScrollDelayFrames = 0;
		}
	}

	/**
	 * Applies or releases a ROM {@code Scroll_lock} without touching
	 * {@code H_scroll_frame_offset}. Unlike a full scripted freeze, Scroll_lock
	 * merely skips {@code MoveCameraX}/{@code MoveCameraY}; any pending
	 * position-history delay remains parked until scrolling resumes.
	 */
	public void setScrollLocked(boolean locked) {
		this.frozen = locked;
	}

	/**
	 * @return true if camera is fully frozen (both X and Y)
	 */
	public boolean getFrozen() {
		return frozen;
	}

	/**
	 * Sets the level-started flag (ROM: Level_started_flag).
	 * This flag is used by level/HUD flow and intro state logic; it does not
	 * directly control camera scrolling.
	 *
	 * @param levelStarted true when level is considered started
	 */
	public void setLevelStarted(boolean levelStarted) {
		this.levelStarted = levelStarted;
	}

	/**
	 * @return true if Level_started_flag is set
	 */
	public boolean isLevelStarted() {
		return levelStarted;
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
		if (updateObserver != null) {
			updateObserver.onUpdateBoundaryEasing();
		}
		maxXBeforeBoundaryEasing = maxX;
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
				// Increasing max Y (target > current) - ROM lines 20320-20331.
				// Boundary moving DOWN: check for the airborne acceleration
				// (ROM DynamicLevelEvents.asm:35-49). This reads the camera (y) and
				// the player airborne bit; because LevelFrameStep now runs
				// updateBoundaryEasing() AFTER updatePosition() (matching ROM
				// DynamicLevelEvents running after ScrollVertical), y here is the
				// POST-scroll camera, exactly as ROM reads v_screenposy. The +8 step
				// therefore reaches next frame's camera clamp, not this frame's.
				if (focusedSprite != null && (y + 8) >= maxY && focusedSprite.getAir()) {
					step = (short) (BOUNDARY_EASE_STEP * 4); // 8 pixels/frame
				}
				maxY += step;
			}

			// ROM does NOT clamp the eased boundary to its target on either path
			// (S1 DynamicLevelEvents.asm:5-49; S2 RunDynamicLevelEvents s2.asm:20329-
			// 20364). On the move-up (decreasing) path, the snap deliberately sets
			// Camera_Max_Y_pos = (Camera_Y_pos & $FFFE) - 2, which lands one step
			// BELOW the target, and the ROM leaves it there; the boundary converges
			// the next frame via the move-down (+2) path. From even start to even
			// target the un-snapped 2px steps land on the target exactly, so no
			// clamp is needed there either. An overshoot clamp here would override
			// the deliberate below-target snap and produce a 2px-high bottom-boundary
			// clamp on the following frame's ScrollVertical (S1 MZ3 f15324).

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

	/** Installs an optional observer used by lifecycle diagnostics and tests. */
	public void setUpdateObserver(UpdateObserver updateObserver) {
		this.updateObserver = updateObserver;
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

	/**
	 * Computes the current-frame BuildSprites visibility that feeds
	 * {@code render_flags.on_screen}.
	 * <p>S3K {@code Render_Sprites} (sonic3k.asm:36336) does:
	 * <pre>
	 *   d1 = (y_pos - Camera_Y) + height_pixels  ; height_pixels = 0x18 = 24
	 *   d1 &= Screen_Y_wrap_value                ; default 0xFFFF (no mask)
	 *   if d1 &gt;= 2*height_pixels + 224:           ; threshold = 272
	 *       off-screen
	 * </pre>
	 * With the default {@code Screen_Y_wrap_value = 0xFFFF}, this is equivalent
	 * to {@code relY in [-24, 248)} — i.e., Y margin = {@code height_pixels = 24}
	 * symmetrically, NOT 32.
	 * <p>S1/S2 don't have a {@code Screen_Y_wrap_value} mechanism and the ROM
	 * routines use slightly different margins. Gate the S3K-specific 24-margin
	 * via {@link CameraRules#useScreenYWrapValueForVisibility()}
	 * so existing S1/S2 traces keep their 32-margin behaviour.
	 *
	 * <p><b>Vertical-wrap windowing (the off-screen-flag Y boundary):</b> the ROM
	 * does not mask the raw {@code relY} — it first biases by the low margin (so a
	 * sprite a few pixels above the camera top stays inside the window) and only
	 * then masks into the VDP plane wrap range before a single unsigned-range
	 * compare. Masking the raw signed {@code relY} (as an earlier version did)
	 * mapped a small negative {@code relY} to a huge unsigned value and wrongly
	 * reported off-screen one frame early. The two games:
	 * <ul>
	 *   <li>S2 {@code BuildSprites_ApproxYCheck} (s2.asm:30597-30605):
	 *       {@code d2 = (y_pos - Camera_Y_pos_copy + sprite_top_boundary) & $7FF};
	 *       on-screen iff {@code (sprite_top_boundary-32) <= d2 < (sprite_top_boundary+screen_height+32)}
	 *       with {@code sprite_top_boundary=$80}, {@code screen_height=224}. This is
	 *       algebraically {@code ((relY + 32) & $7FF) < screen_height + 64}. The mask
	 *       is the literal VDP {@code $7FF}, independent of the level's vertical
	 *       wrap range. S1 uses the same routine/margin.</li>
	 *   <li>S3K {@code Render_Sprites} (sonic3k.asm:36356-36364):
	 *       {@code d1 = ((y_pos - Camera_Y_pos_copy) + height_pixels) & Screen_Y_wrap_value};
	 *       off-screen iff {@code d1 >= 2*height_pixels + 224}. That is
	 *       {@code ((relY + margin) & Screen_Y_wrap_value) < screen_height + 2*margin}
	 *       with {@code margin = height_pixels = 24}, masked by the level-configured
	 *       {@code Screen_Y_wrap_value}.</li>
	 * </ul>
	 * Both reduce to {@code ((relY + yMargin) & mask) < height + 2*yMargin}.
	 */
	public boolean isVisibleForRenderFlag(AbstractPlayableSprite sprite) {
		return isVisibleForRenderFlagAtCamera(sprite, getXWithShake(), getYWithShake());
	}

	private boolean isVisibleForRenderFlagAtCamera(
			AbstractPlayableSprite sprite, int cameraXCopy, int cameraYCopy) {
		if (sprite == null) {
			return false;
		}
		int widthPixels = sprite.getRenderFlagWidthPixels();
		int relX = sprite.getRenderCentreX() - cameraXCopy;
		if (relX + widthPixels < 0 || relX - widthPixels >= width) {
			return false;
		}
		int relY = sprite.getRenderCentreY() - cameraYCopy;
		CameraRules rules = cameraRulesFor(sprite);
		boolean useS3kMargin = rules != null && rules.useScreenYWrapValueForVisibility();
		int yMargin = useS3kMargin ? widthPixels : 32;
		if (verticalWrapEnabled) {
			// ROM-accurate wrap window: bias by the low margin BEFORE masking, then
			// one unsigned-range compare. S2/S1 BuildSprites masks with the literal
			// VDP $7FF (s2.asm:30601); S3K masks with Screen_Y_wrap_value
			// (sonic3k.asm:36360, modelled by verticalWrapMask).
			int mask = useS3kMargin ? verticalWrapMask : 0x7FF;
			int wrapped = (relY + yMargin) & mask;
			return wrapped < height + 2 * yMargin;
		}
		return relY >= -yMargin && relY < height + yMargin;
	}

	public void setFocusedSprite(AbstractPlayableSprite sprite) {
		this.focusedSprite = sprite;
		x = sprite.getX();
		y = sprite.getY();
		renderCopyX = x;
		renderCopyY = y;
	}

	public AbstractPlayableSprite getFocusedSprite() {
		return focusedSprite;
	}

	public short getX() {
		return x;
	}

	public void setX(short x) {
		this.x = x;
		this.renderCopyX = x;
	}

	public short getY() {
		return y;
	}

	public void setY(short y) {
		this.y = y;
		this.renderCopyY = y;
	}

	/**
	 * Publishes the current physical camera position as the ROM render copy.
	 * ScreenEvents performs this before zone-specific event handlers run.
	 */
	public void captureRenderCopy() {
		renderCopyX = x;
		renderCopyY = y;
	}

	/**
	 * Updates the physical Y position after the render copy has been published.
	 * This models ROM event routines that move {@code Camera_Y_pos} while
	 * retaining the already-published {@code Camera_Y_pos_copy}.
	 */
	public void setYAfterRenderCopy(short y) {
		this.y = y;
	}

	/**
	 * Sets the screen shake offsets (ROM: applied to Camera_X_pos_copy and Camera_Y_pos_copy).
	 * These offsets are used by the rendering system to shake both foreground tiles and sprites.
	 *
	 * @param x horizontal shake offset in pixels
	 * @param y vertical shake offset in pixels
	 */
	public void setShakeOffsets(int x, int y) {
		this.shakeOffsetX = (short) x;
		this.shakeOffsetY = (short) y;
	}

	/**
	 * @return Current horizontal shake offset in pixels
	 */
	public short getShakeOffsetX() {
		return shakeOffsetX;
	}

	/**
	 * @return Current vertical shake offset in pixels
	 */
	public short getShakeOffsetY() {
		return shakeOffsetY;
	}

	/**
	 * @return Camera X position with shake offset applied (for rendering)
	 */
	public short getXWithShake() {
		return (short) (renderCopyX + shakeOffsetX);
	}

	/**
	 * @return Camera Y position with shake offset applied (for rendering)
	 */
	public short getYWithShake() {
		return (short) (renderCopyY + shakeOffsetY);
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
	 *
	 * ROM: In S1, negative minY (e.g. LZ3 top=0xFF00=-256) indicates vertical
	 * wrapping (DeformLayers.asm lines 542-580). S3K zones can have negative
	 * minY without wrapping (e.g. MGZ1 minY=-$100 for falling intro headroom).
	 * Use {@link #setVerticalWrapEnabled(boolean)} to control wrapping explicitly.
	 */
	public void setMinY(short minY) {
		this.minY = minY;
		this.minYTarget = minY;
	}

	/**
	 * Explicitly enables or disables vertical wrapping.
	 * When enabled, the camera and player Y coordinates wrap at the given range.
	 *
	 * @param enabled whether to enable vertical wrapping
	 * @param range   wrap range in pixels (must be a power of 2; e.g. 0x800 for S1, 0x1000 for S3K 32-row levels)
	 */
	public void setVerticalWrapEnabled(boolean enabled, int range) {
		this.verticalWrapEnabled = enabled;
		if (enabled && range > 0) {
			this.verticalWrapRange = range;
			this.verticalWrapMask = range - 1;
		}
	}

	/**
	 * Convenience overload that uses the default S1 range (0x800).
	 */
	public void setVerticalWrapEnabled(boolean enabled) {
		setVerticalWrapEnabled(enabled, VERTICAL_WRAP_RANGE);
	}

	/**
	 * Applies the active vertical wrap mask to a playable object's ROM
	 * {@code y_pos} equivalent when vertical wrapping is active.
	 * <p>ROM references:
	 * {@code docs/skdisasm/sonic3k.asm:21989-21992} (Sonic),
	 * {@code docs/skdisasm/sonic3k.asm:25708-25711} (Tails/player display path),
	 * {@code docs/skdisasm/sonic3k.asm:26233-26236} (Tails control).
	 *
	 * @return true when the sprite's Y coordinate changed.
	 */
	public boolean applyScreenYWrapValue(AbstractPlayableSprite sprite) {
		if (!verticalWrapEnabled || sprite == null) {
			return false;
		}
		// This is separate from the render visibility wrap margin: S2 control
		// paths apply the $7FF y_pos mask, while S1 LZ3/SBZ2 only masks Sonic on
		// the camera wrap-crossing frame mirrored by updatePosition().
		CameraRules rules = cameraRulesFor(sprite);
		if (rules == null || !rules.playerControlAppliesVerticalWrapMask()) {
			return false;
		}
		short before = sprite.getCentreY();
		short after = (short) (before & verticalWrapMask);
		if (after == before) {
			return false;
		}
		sprite.setCentreYPreserveSubpixel(after);
		return true;
	}

	private static CameraRules cameraRulesFor(AbstractPlayableSprite sprite) {
		if (sprite == null) {
			return null;
		}
		GameRules rules = sprite.getGameRules();
		if (rules != null && rules.camera() != null) {
			return rules.camera();
		}
		return null;
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

	public short getMaxXBeforeBoundaryEasing() {
		return maxXBeforeBoundaryEasing;
	}

	/**
	 * Sets maxX immediately (both current and target).
	 * Use setMaxXTarget() for smooth easing.
	 */
	public void setMaxX(short maxX) {
		this.maxX = maxX;
		this.maxXTarget = maxX;
		this.maxXBeforeBoundaryEasing = maxX;
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

	/**
	 * Gets the current Y position bias (ROM: Camera_Y_pos_bias).
	 * Default is 96. Used as the vertical target position for camera centering.
	 * @return Current Y position bias value
	 */
	public short getYPosBias() {
		return yPosBias;
	}

	/**
	 * Sets the Y position bias (ROM: Camera_Y_pos_bias).
	 * When bias != 96, grounded vertical scroll uses slower 2px/frame cap.
	 * Used by looking up/down mechanics and spindash release.
	 * @param yPosBias New bias value (default is 96)
	 */
	public void setYPosBias(short yPosBias) {
		this.yPosBias = yPosBias;
	}

	/**
	 * Resets Y position bias to the default value (96).
	 * ROM: Called during Obj01_ResetScr equivalents - rolling, spindash release, jumping.
	 * The bias gradually eases back to 96 at 2px/frame (4 toward, 2 back = net 2).
	 */
	public void resetYBias() {
		// ROM: Obj01_ResetScr_Part2 / Obj01_Jump_ResetScr
		// The actual reset is gradual: if bias < 96, add 4 then subtract 2 (net +2)
		// if bias > 96, just subtract 2
		// This method initiates the reset process - actual easing happens in updateYBiasEasing()
		this.yPosBias = DEFAULT_Y_BIAS;
	}

	/**
	 * Gradually increases bias toward the look-up target (0xC8 = 200).
	 * ROM: s2.asm:36406-36408 - adds 2 to bias each frame until reaching 0xC8.
	 * Call this each frame while looking up AND look delay counter has elapsed.
	 */
	public void incrementLookUpBias() {
		if (yPosBias < LOOK_UP_BIAS) {
			yPosBias += 2;
			if (yPosBias > LOOK_UP_BIAS) {
				yPosBias = LOOK_UP_BIAS;
			}
		}
	}

	/**
	 * Gradually decreases bias toward the look-down target (8).
	 * ROM: s2.asm:36420-36422 - subtracts 2 from bias each frame until reaching 8.
	 * Call this each frame while looking down AND look delay counter has elapsed.
	 */
	public void decrementLookDownBias() {
		if (yPosBias > LOOK_DOWN_BIAS) {
			yPosBias -= 2;
			if (yPosBias < LOOK_DOWN_BIAS) {
				yPosBias = LOOK_DOWN_BIAS;
			}
		}
	}

	/**
	 * Gradually eases bias back toward the default value (96).
	 * ROM: s2.asm:36431-36438 (Obj01_ResetScr_Part2)
	 * - If bias < 96: add 4, then subtract 2 (net +2 per frame)
	 * - If bias > 96: subtract 2
	 * Call this each frame when not actively panning.
	 */
	public void easeYBiasToDefault() {
		if (yPosBias < DEFAULT_Y_BIAS) {
			// ROM: addq.w #4, subq.w #2 = net +2, no intermediate clamp
			// (s2.asm:36431-36438, Obj01_ResetScr_Part2)
			yPosBias += 4;
			yPosBias -= 2;
		} else if (yPosBias > DEFAULT_Y_BIAS) {
			// ROM: subq.w #2
			yPosBias -= 2;
			if (yPosBias < DEFAULT_Y_BIAS) {
				yPosBias = DEFAULT_Y_BIAS;
			}
		}
	}

	/**
	 * Gets the default Y bias value.
	 * @return Default Y bias (96)
	 */
	public static short getDefaultYBias() {
		return DEFAULT_Y_BIAS;
	}

	/**
	 * Gets the look up bias target value.
	 * @return Look up bias (200 / 0xC8)
	 */
	public static short getLookUpBias() {
		return LOOK_UP_BIAS;
	}

	/**
	 * Gets the look down bias target value.
	 * @return Look down bias (8)
	 */
	public static short getLookDownBias() {
		return LOOK_DOWN_BIAS;
	}

	/**
	 * @return true if vertical wrapping is active (LZ3/SBZ2 loop sections)
	 */
	public boolean isVerticalWrapEnabled() {
		return verticalWrapEnabled;
	}

	/**
	 * @return true if a vertical wrap occurred during the last updatePosition() call
	 */
	public boolean didWrapLastFrame() {
		return lastFrameWrapped;
	}

	/**
	 * @return the Y delta applied by wrapping last frame (e.g. -0x800 for downward wrap), or 0
	 */
	public short getWrapDeltaY() {
		return wrapDeltaY;
	}

	/**
	 * @return the current vertical wrap range for this camera instance
	 */
	public int getVerticalWrapRange() {
		return verticalWrapRange;
	}

	/**
	 * @return the BG Y mask for vertical wrapping (0x3FF)
	 */
	public static int getVerticalWrapBgMask() {
		return VERTICAL_WRAP_BG_MASK;
	}

	/**
	 * Resets mutable state without destroying the singleton instance.
	 * Preserves width/height (configuration), clears all runtime state.
	 */
	public void resetState() {
		x = 0;
		y = 0;
		renderCopyX = 0;
		renderCopyY = 0;
		minX = 0;
		minY = 0;
		maxX = 0;
		maxXBeforeBoundaryEasing = 0;
		maxY = 0;
		shakeOffsetX = 0;
		shakeOffsetY = 0;
		minXTarget = 0;
		minYTarget = 0;
		maxXTarget = 0;
		maxYTarget = 0;
		maxYChanging = false;
		horizScrollDelayFrames = 0;
		frozen = false;
		deferHorizontalBoundaryClampOnce = false;
		levelStarted = true;
		focusedSprite = null;
		yPosBias = DEFAULT_Y_BIAS;
		fastScrollCap = DEFAULT_FAST_SCROLL_CAP;
		fastVerticalScrollRequested = false;
		forcedScrollRequested = false;
		forcedScrollX = 0;
		forcedScrollY = 0;
		verticalWrapEnabled = false;
		verticalWrapRange = VERTICAL_WRAP_RANGE;
		verticalWrapMask = VERTICAL_WRAP_RANGE - 1;
		lastFrameWrapped = false;
		wrapDeltaY = 0;
	}

	/**
	 * Sets the maximum vertical scroll speed for airborne and fast-ground paths.
	 * ROM: S2 uses 16 (0x10), S3K uses 24 (0x18).
	 * (s2.asm:18189-18190 ".doScroll_fast"; sonic3k.asm:loc_1C1B0)
	 *
	 * @param cap scroll cap in pixels per frame (16 for S1/S2, 24 for S3K)
	 */
	public void setFastScrollCap(int cap) {
		this.fastScrollCap = (short) cap;
	}

	/**
	 * Sets whether leftward horizontal camera scrolling is uncapped (ROM S1
	 * FixBugs=0 behavior). When true, the per-frame cap applies only to rightward
	 * scrolling. Set per-game from
	 * {@link CameraRules#uncappedLeftwardHorizontalScroll()}.
	 */
	public void setUncappedLeftwardScroll(boolean uncapped) {
		this.uncappedLeftwardHorizontalScroll = uncapped;
	}

	/** Returns the current fast vertical scroll cap in pixels/frame. */
	public int getFastScrollCap() {
		return fastScrollCap;
	}

	/**
	 * Requests ROM {@code Fast_V_scroll_flag} behavior for the next camera update.
	 * The request is frame-scoped and is cleared by {@link #updatePosition()}.
	 */
	public void requestFastVerticalScroll() {
		fastVerticalScrollRequested = true;
	}

	/**
	 * Requests ROM {@code Scroll_force_positions} behavior for the next camera
	 * update. Instead of tracking the focused sprite, the camera-position math
	 * (both horizontal and vertical) tracks the supplied forced coordinates, and
	 * the horizontal scroll frame offset ({@code H_scroll_frame_offset}) is
	 * zeroed. The request is frame-scoped and is cleared by
	 * {@link #updatePosition()}. Traversal objects (e.g. MHZ swing vine / vertical
	 * swing bar) call this every frame a player is grabbed.
	 * (sonic3k.asm {@code loc_1BFB8}:38296-38300, setter {@code loc_226F2}:47072-47074.)
	 *
	 * @param forcedXPos world X the camera should track this frame (ROM Scroll_forced_X_pos)
	 * @param forcedYPos world Y the camera should track this frame (ROM Scroll_forced_Y_pos)
	 */
	public void requestForcedScroll(int forcedXPos, int forcedYPos) {
		forcedScrollRequested = true;
		forcedScrollX = forcedXPos;
		forcedScrollY = forcedYPos;
	}

	@Override
	public String key() {
		return "camera";
	}

	@Override
	public CameraSnapshot capture() {
		return new CameraSnapshot(
				x, y, minX, minY, maxX, maxY,
				renderCopyX, renderCopyY,
				shakeOffsetX, shakeOffsetY,
				minXTarget, minYTarget, maxXTarget, maxYTarget, maxXBeforeBoundaryEasing,
				maxYChanging, horizScrollDelayFrames, frozen, deferHorizontalBoundaryClampOnce,
				levelStarted,
				verticalWrapEnabled, verticalWrapRange, verticalWrapMask,
				lastFrameWrapped, wrapDeltaY, yPosBias, fastScrollCap);
	}

	@Override
	public void restore(CameraSnapshot snapshot) {
		x = snapshot.x();
		y = snapshot.y();
		renderCopyX = snapshot.renderCopyX();
		renderCopyY = snapshot.renderCopyY();
		minX = snapshot.minX();
		minY = snapshot.minY();
		maxX = snapshot.maxX();
		maxY = snapshot.maxY();
		maxXBeforeBoundaryEasing = snapshot.maxXBeforeBoundaryEasing();
		shakeOffsetX = snapshot.shakeOffsetX();
		shakeOffsetY = snapshot.shakeOffsetY();
		minXTarget = snapshot.minXTarget();
		minYTarget = snapshot.minYTarget();
		maxXTarget = snapshot.maxXTarget();
		maxYTarget = snapshot.maxYTarget();
		maxYChanging = snapshot.maxYChanging();
		horizScrollDelayFrames = snapshot.horizScrollDelayFrames();
		frozen = snapshot.frozen();
		deferHorizontalBoundaryClampOnce = snapshot.deferHorizontalBoundaryClampOnce();
		levelStarted = snapshot.levelStarted();
		verticalWrapEnabled = snapshot.verticalWrapEnabled();
		verticalWrapRange = snapshot.verticalWrapRange();
		verticalWrapMask = snapshot.verticalWrapMask();
		lastFrameWrapped = snapshot.lastFrameWrapped();
		wrapDeltaY = snapshot.wrapDeltaY();
		yPosBias = snapshot.yPosBias();
		fastScrollCap = snapshot.fastScrollCap();
		// Re-resolve focused sprite via SpriteManager after restore. Object instances
		// are rebuilt during rewind; this ensures Camera tracks the live main player
		// sprite rather than a stale or null reference (Track C / H.1).
		rebindFocusedSprite();
	}

	/**
	 * Re-resolves the focused sprite from the active SpriteManager using the
	 * configured main character code. Called from {@link #restore} to ensure the
	 * camera target is up-to-date after a rewind snapshot restore.
	 */
	private void rebindFocusedSprite() {
		com.openggf.sprites.managers.SpriteManager sm = GameServices.spritesOrNull();
		if (sm == null) {
			return;
		}
		String mainCode = GameServices.configuration()
				.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
		if (mainCode == null || mainCode.isBlank()) {
			mainCode = "sonic";
		}
		Sprite candidate = sm.getSprite(mainCode);
		if (candidate instanceof AbstractPlayableSprite aps) {
			focusedSprite = aps;
		}
	}

}
