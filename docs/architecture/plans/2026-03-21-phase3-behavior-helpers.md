# Phase 3: Movement & Behavior Helpers

## Overview
Extract common movement patterns and game object behaviors into reusable helpers.
These touch game physics and require careful testing against existing behavior.

**Dependencies:** Phase 1 (DestructionEffects), Phase 2 (RenderableObjectBase)

---

## 3A. PatrolMovementHelper

### Problem
25+ badniks independently implement the same horizontal patrol loop:
subpixel velocity → floor check → edge detection → direction reversal.

### Affected Files
**S2:** GrounderBadnikInstance (vel 0x100, yR 0x14), ShellcrackerBadnikInstance (vel 0x40, yR 0x0C),
SlicerBadnikInstance (vel 0x40, yR 0x10), SpikerBadnikInstance (timer-based), CrawlBadnikInstance (vel 0x20, timer),
SpinyBadnikInstance (vel 0x40, timer)

**S1:** Sonic1MotobugBadnikInstance (vel 0x100, yR 0x0E), Sonic1CrabmeatBadnikInstance (vel 0x80, yR 0x10),
Sonic1BurrobotBadnikInstance (vel 0x80, yR 0x13)

**S3K:** RhinobotBadnikInstance (acceleration-based, yR 0x10)

### Variations Found
- **Edge-based reversal** (Grounder, Shellcracker, Slicer, Motobug, Crabmeat) — check floor distance thresholds
- **Timer-based reversal** (Spiker, Crawl, Spiny) — reverse after N frames
- **Acceleration-based** (Rhinobot) — accelerate/decelerate rather than constant speed
- **Forward probe** (Crabmeat, Burrobot) — check floor at X offset ahead of sprite
- **Floor distance thresholds vary:** [-1, 12), [-8, 0xC), custom per badnik

### API Design
```java
package com.openggf.sonic.level.objects;

public class PatrolMovementHelper {
    public record PatrolResult(int newX, int newXSub, int newY, boolean reversed) {}

    /**
     * Apply subpixel velocity and check for floor edge.
     * @param x          current X position
     * @param xSub       subpixel X accumulator (0-255)
     * @param y          current Y position
     * @param velocity   subpixel velocity (e.g. 0x100 = 1px/frame)
     * @param yRadius    collision Y radius for floor check
     * @param minFloor   minimum floor distance to stay on surface (typically -8 or -1)
     * @param maxFloor   maximum floor distance before edge (typically 0x0C or 12)
     * @return PatrolResult with updated position and whether direction reversed
     */
    public static PatrolResult updatePatrol(
            int x, int xSub, int y, int velocity,
            int yRadius, int minFloor, int maxFloor) {
        // Apply subpixel velocity (SpeedToPos equivalent)
        int pos24 = (x << 8) | (xSub & 0xFF);
        pos24 += velocity;
        int newX = pos24 >> 8;
        int newXSub = pos24 & 0xFF;

        // Floor check (ObjFloorDist equivalent)
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(newX, y, yRadius);
        boolean reversed = false;
        int newY = y;

        if (!floor.foundSurface() || floor.distance() < minFloor || floor.distance() >= maxFloor) {
            reversed = true;
        } else {
            newY = y + floor.distance();
        }
        return new PatrolResult(newX, newXSub, newY, reversed);
    }

    /** Apply subpixel velocity without floor check (for timer-based patrol). */
    public static PatrolResult applyVelocity(int x, int xSub, int velocity) {
        int pos24 = (x << 8) | (xSub & 0xFF);
        pos24 += velocity;
        return new PatrolResult(pos24 >> 8, pos24 & 0xFF, 0, false);
    }
}
```

### Migration Example (Grounder)
**Before (~15 lines):**
```java
int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
xPos24 += xVelocity;
currentX = xPos24 >> 8;
xSubpixel = xPos24 & 0xFF;
TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, 0x14);
if (!floor.foundSurface() || floor.distance() < -1 || floor.distance() >= 12) {
    facingLeft = !facingLeft;
    xVelocity = -xVelocity;
} else {
    currentY += floor.distance();
}
```
**After (~5 lines):**
```java
var result = PatrolMovementHelper.updatePatrol(currentX, xSubpixel, currentY, xVelocity, 0x14, -1, 12);
currentX = result.newX();
xSubpixel = result.newXSub();
currentY = result.newY();
if (result.reversed()) { facingLeft = !facingLeft; xVelocity = -xVelocity; }
```

### Line Savings
~10 lines × 15 edge-based badniks = ~150 lines

---

## 3B. SubpixelMotion Promotion

### Problem
S3K has a `SubpixelMotion` utility class for subpixel position tracking. S1/S2 badniks duplicate
this logic inline with `(x << 8) | (xSub & 0xFF)` patterns appearing 30+ times.

### Proposal
Promote `com.openggf.game.sonic3k.objects.SubpixelMotion` to `com.openggf.sonic.physics.SubpixelMotion`
so all games can use it. The PatrolMovementHelper above uses it internally; individual badniks
that don't use patrol (timer-based, acceleration-based) can use SubpixelMotion directly.

### Migration
- Move class from `game.sonic3k.objects` to `sonic.physics`
- Update S3K imports (find all references)
- Gradually adopt in S1/S2 badniks that do inline subpixel math

---

## 3C. MonitorBase

### Problem
S1, S2, and S3K monitor implementations are nearly copy-paste identical for icon-rise
physics, falling physics, and broken-state tracking.

### Shared Constants (identical across all 3)
- `ICON_INITIAL_VELOCITY = -0x300`
- `ICON_RISE_ACCEL = 0x18`
- `ICON_WAIT_FRAMES = 0x1D`
- `FALLING_GRAVITY = 0x38` (S1/S2; S3K uses SubpixelMotion)

### Shared State Machine (identical across all 3)
1. **Icon rise:** Apply velocity + accel until velocity >= 0
2. **Icon wait:** Decrement wait counter, apply effect when counter hits threshold
3. **Icon dismiss:** Remove icon sprite

### Differences
| Aspect | S1 | S2 | S3K |
|--------|----|----|-----|
| Base class | AbstractObjectInstance | BoxObjectInstance | AbstractObjectInstance |
| Falling | Yes (gravity loop) | Yes (gravity loop) | No (breaks in place) |
| Monitor types | 6 basic | 9 basic | 10+ (elemental shields) |
| SFX IDs | Game-specific | Game-specific | Game-specific |

### API Design
```java
package com.openggf.sonic.level.objects;

public abstract class AbstractMonitorInstance extends AbstractObjectInstance {
    // Shared icon physics constants
    protected static final int ICON_INITIAL_VELOCITY = -0x300;
    protected static final int ICON_RISE_ACCEL = 0x18;
    protected static final int ICON_WAIT_FRAMES = 0x1D;

    // Shared state
    protected boolean broken;
    protected boolean iconActive;
    protected int iconVelY;
    protected int iconSubY;
    protected int iconWaitFrames;
    protected boolean effectApplied;

    /** Update icon rise/wait/dismiss. Call from subclass update(). */
    protected void updateIcon() { /* shared state machine */ }

    /** Apply the monitor's effect. Subclass-specific. */
    protected abstract void applyMonitorEffect(AbstractPlayableSprite player);

    /** Get the mapping frame for the icon sprite. Subclass-specific. */
    protected abstract int getIconFrame();

    /** Check if previously broken (persistence). Shared logic. */
    protected boolean checkPreviouslyBroken(ObjectSpawn spawn) { ... }
}
```

### Risk
**Medium.** S2 extends `BoxObjectInstance` (which provides solid object collision), not
`AbstractObjectInstance`. Either MonitorBase must also extend BoxObjectInstance, or the
solid-object behavior must be composed rather than inherited. Recommend composition approach:
keep MonitorBase as a helper/delegate rather than a base class.

### Alternative: MonitorIconHelper (lower risk)
```java
public class MonitorIconHelper {
    public record IconState(int velY, int subY, int waitFrames, boolean effectApplied, boolean dismissed) {}

    public static IconState updateIcon(IconState state) { /* shared physics */ }
    public static IconState startIconRise() { return new IconState(-0x300, 0, 0x1D, false, false); }
}
```
This avoids inheritance issues entirely. Each monitor keeps its own base class but delegates
icon physics to the helper.

---

## 3D. SpringBase

### Problem
6+ spring implementations share bounce velocity application and direction dispatch
but differ in supported types and animation systems.

### Shared Logic
- Strength lookup: red = `-0x1000`, yellow = `-0x0A00`
- Direction dispatch: UP → set yVel, HORIZONTAL → set xVel, DOWN → set yVel positive
- Air state management: set player airborne after bounce
- SFX: play spring sound
- `SpringHelper.applyCollisionLayerBits()` — already shared

### Differences
| Aspect | S1 | S2 | S3K |
|--------|----|----|-----|
| Diagonal springs | No | Yes | Yes |
| Flip/twirl subtypes | No | Yes | Yes |
| Reverse gravity | No | No | Yes |
| Red down velocity cap | No | No | 0xD00 |
| Horizontal approach zone | No | No | Yes |
| Base class | AbstractObjectInstance | BoxObjectInstance | AbstractObjectInstance |

### API Design
```java
package com.openggf.sonic.level.objects;

public class SpringBounceHelper {
    public static final int STRENGTH_RED = -0x1000;
    public static final int STRENGTH_YELLOW = -0x0A00;

    /** Apply vertical bounce to player. Sets velocity and air state. */
    public static void bounceVertical(AbstractPlayableSprite player, int strength) {
        player.setYVelocity(strength);
        player.setGrounded(false);
    }

    /** Apply horizontal bounce to player. Sets velocity and facing direction. */
    public static void bounceHorizontal(AbstractPlayableSprite player, int strength, boolean faceRight) {
        player.setXVelocity(faceRight ? -strength : strength);
        player.setGroundSpeed(player.getXVelocity());
    }
}
```

### Risk
**Low.** This is a helper that spring implementations call into, not a base class.
No inheritance hierarchy changes needed. Individual spring objects keep their game-specific
logic and delegate the common bounce physics.

---

## Test Strategy

### Existing Coverage
- Headless tests cover player physics but not individual badnik patrol logic
- Monitor tests are manual/visual only
- Spring physics tested indirectly through player movement tests

### New Tests Needed
1. **TestPatrolMovementHelper** — Unit test for edge detection, subpixel accumulation, reversal
2. **TestMonitorIconHelper** — Unit test for icon rise physics state machine
3. **TestSpringBounceHelper** — Unit test for velocity application

### Regression Testing
- Run `mvn test` after each migration
- Visual testing in-game for migrated badniks (patrol path should be pixel-identical)

---

## Risk Assessment

| Component | Risk | Reason |
|-----------|------|--------|
| PatrolMovementHelper | **Medium** | Touches badnik movement — subtle differences in floor thresholds matter |
| SubpixelMotion promotion | **Low** | Just moving a class, no logic change |
| MonitorIconHelper | **Low** | Delegate pattern, no inheritance change |
| SpringBounceHelper | **Low** | Delegate pattern, no inheritance change |

### Mitigation
- Migrate ONE badnik first per helper, test thoroughly, then batch-migrate the rest
- Use composition/delegation over inheritance to avoid base class conflicts (BoxObjectInstance vs AbstractObjectInstance)
- Prefer record return types (immutable) to prevent accidental state mutation
