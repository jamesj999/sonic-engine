# Tutorial: Implement an Object from Scratch

This tutorial walks through implementing a Sonic 2 game object from the disassembly to
working code. We use the **ArrowShooter** (Obj22) from Aquatic Ruin Zone as our subject --
a real, already-implemented object. The approach is to pretend it does not exist yet and
build it step by step.

The real implementation is available as an answer key at the end. After completing this
tutorial, you should be able to implement any standard game object by following the same
pattern.

**Prerequisites:** You should have completed [Dev Setup](dev-setup.md), read the
[Architecture](architecture.md) page, and be comfortable reading 68000 assembly at the
level covered in the [68000 Primer](../cross-referencing/68000-primer.md).

---

## Step 1: Read the Disassembly

Open `docs/s2disasm/s2.asm` and search for `Obj22`. You will find the object starting
around line 51034.

### The Routine Dispatch Table

```asm
Obj22:
    moveq   #0,d0
    move.b  routine(a0),d0
    move.w  Obj22_Index(pc,d0.w),d1
    jmp     Obj22_Index(pc,d1.w)

Obj22_Index:
    dc.w Obj22_Init - Obj22_Index         ; routine 0
    dc.w Obj22_Main - Obj22_Index         ; routine 2
    dc.w Obj22_ShootArrow - Obj22_Index   ; routine 4
    dc.w Obj22_Arrow_Init - Obj22_Index   ; routine 6
    dc.w Obj22_Arrow - Obj22_Index        ; routine 8
```

Five routines. But this is really **two logical objects** sharing one ID:

- **The shooter** (routines 0, 2, 4): a stationary hazard embedded in a wall.
- **The arrow** (routines 6, 8): a projectile fired by the shooter.

The original game uses a single object ID for both because they share art and mappings.
The shooter spawns the arrow by allocating a new object slot and setting its routine to 6.

### Routine 0: Init

```asm
Obj22_Init:
    addq.b  #2,routine(a0)                           ; advance to routine 2
    move.l  #Obj22_MapUnc_25804,mappings(a0)          ; set mappings pointer
    move.w  #make_art_tile(ArtTile_ArtNem_ArrowAndShooter,0,0),art_tile(a0)
    ori.b   #1<<render_flags.level_fg,render_flags(a0) ; draw on foreground
    move.b  #3,priority(a0)                           ; display priority 3
    move.b  #$10,width_pixels(a0)                     ; 16-pixel display width
    move.b  #1,mapping_frame(a0)                      ; start on frame 1 (idle)
    andi.b  #$F,subtype(a0)                           ; mask subtype to low nibble
```

Key takeaways: priority is 3, display width is $10 (16 pixels), initial frame is 1
(not 0 -- frame 0 is the arrow sprite), subtype is masked.

### Routine 2: Main (Detection)

```asm
Obj22_Main:
    cmpi.b  #2,anim(a0)             ; already in firing animation?
    beq.s   Obj22_Animate           ; yes: just animate, skip detection
    moveq   #0,d2                   ; d2 = detection flag (0 = not detected)
    lea     (MainCharacter).w,a1
    bsr.s   Obj22_DetectPlayer      ; check main character
    lea     (Sidekick).w,a1
    bsr.s   Obj22_DetectPlayer      ; check sidekick too
    tst.b   d2
    bne.s   +                       ; if detected, keep current state
    tst.b   anim(a0)                ; not detected. were we detecting before?
    beq.s   +                       ; no (idle): stay idle
    moveq   #2,d2                   ; yes: switch to firing animation
+
    move.b  d2,anim(a0)             ; update animation

Obj22_Animate:
    lea     (Ani_obj22).l,a1        ; run animation engine
    jsr     AnimateSprite
    jmp     MarkObjGone             ; check if still on screen
```

The detection subroutine:

```asm
Obj22_DetectPlayer:
    move.w  x_pos(a0),d0            ; shooter X
    sub.w   x_pos(a1),d0            ; minus player X
    bcc.s   +                       ; if positive, skip
    neg.w   d0                      ; absolute value
+
    cmpi.w  #$40,d0                 ; within 64 pixels?
    bhs.s   +                       ; no: skip
    moveq   #1,d2                   ; yes: flag detected
+
    rts
```

Logic summary:
- If a player is within 64 pixels horizontally, switch to anim 1 (detecting).
- If both players leave range while in detecting mode, switch to anim 2 (firing).
- If idle and players leave range, stay idle.

### Routine 4: Shoot Arrow

```asm
Obj22_ShootArrow:
    jsr     AllocateObject          ; get a free object slot -> a1
    bne.s   +                       ; if none available, skip
    move.b  id(a0),id(a1)          ; copy object ID to new slot
    addq.b  #6,routine(a1)         ; set new object to routine 6 (Arrow_Init)
    move.l  mappings(a0),mappings(a1)  ; share mappings
    move.w  art_tile(a0),art_tile(a1)  ; share art tile
    move.w  x_pos(a0),x_pos(a1)       ; copy position
    move.w  y_pos(a0),y_pos(a1)
    move.b  render_flags(a0),render_flags(a1)  ; copy flip flags
    move.b  status(a0),status(a1)     ; copy status (includes facing)
    move.w  #SndID_PreArrowFiring,d0  ; play pre-firing sound ($DB)
    jsr     PlaySound
+
    subq.b  #2,routine(a0)         ; return shooter to routine 2 (Main)
    lea     (Ani_obj22).l,a1
    jsr     AnimateSprite
    jmp     MarkObjGone
```

This routine is reached via the `$FD` command in the firing animation script (see
below). It spawns the arrow and returns the shooter to its main loop.

### Routine 6: Arrow Init

```asm
Obj22_Arrow_Init:
    addq.b  #2,routine(a0)                 ; advance to routine 8
    move.b  #8,y_radius(a0)                ; 8-pixel Y radius
    move.b  #$10,x_radius(a0)              ; 16-pixel X radius
    move.b  #4,priority(a0)                ; priority 4 (in front of shooter)
    move.b  #$9B,collision_flags(a0)       ; hurts player on touch
    move.b  #8,width_pixels(a0)            ; 8-pixel display width
    move.b  #0,mapping_frame(a0)           ; frame 0 (the arrow graphic)
    move.w  #$400,x_vel(a0)               ; velocity: 4 pixels/frame rightward
    btst    #status.npc.x_flip,status(a0)  ; facing left?
    beq.s   +
    neg.w   x_vel(a0)                      ; yes: negate velocity
+
    move.w  #SndID_ArrowFiring,d0          ; play firing sound ($AE)
    jsr     PlaySound
```

### Routine 8: Arrow Movement

```asm
Obj22_Arrow:
    jsr     ObjectMove                      ; apply velocity to position
    btst    #status.npc.x_flip,status(a0)   ; which direction?
    bne.s   loc_rightward
    moveq   #-8,d3                          ; check 8 pixels ahead (left)
    bsr.w   ObjCheckLeftWallDist
    tst.w   d1                              ; hit wall?
    bmi.w   DeleteObject                    ; yes: delete arrow
    jmp     MarkObjGone                     ; no: check if on screen

loc_rightward:
    moveq   #8,d3                           ; check 8 pixels ahead (right)
    bsr.w   ObjCheckRightWallDist
    tst.w   d1                              ; hit wall?
    bmi.w   DeleteObject                    ; yes: delete arrow
    jmp     MarkObjGone                     ; no: check if on screen
```

### The Animation Script

```asm
Ani_obj22:
    dc.w byte_idle - Ani_obj22       ; anim 0
    dc.w byte_detect - Ani_obj22     ; anim 1
    dc.w byte_fire - Ani_obj22       ; anim 2

byte_idle:   dc.b $1F, 1, $FF                           ; delay 31, frame 1, loop
byte_detect: dc.b $03, 1, 2, $FF                        ; delay 3, frames 1-2, loop
byte_fire:   dc.b $07, 3, 4, $FC, 4, 3, 1, $FD, 0      ; firing sequence
```

The firing animation is the most complex:
- Show frames 3, 4 (mouth opening)
- `$FC` means "change animation" -- but the next byte is not used as a simple jump here;
  in context it replays the rest
- Show frames 4, 3, 1 (mouth closing)
- `$FD` means "increment routine by 2 and change animation" -- this triggers routine 4
  (ShootArrow) and switches to anim 0

### The Sprite Mappings

The file `mappings/sprite/obj22.asm` defines 5 frames:

| Frame | Description | Tiles |
|-------|-------------|-------|
| 0 | Arrow projectile | 4x1 horizontal strip |
| 1 | Shooter idle (stone face) | 3x2 + 1x2 (body + eye column) |
| 2 | Shooter with eye animation | 1x1 eye + same body as frame 1 |
| 3 | Shooter open mouth A | 3x2 + 1x2 (different eye column) |
| 4 | Shooter open mouth B | 3x2 + 1x2 (different eye column) |

---

## Step 2: Plan the Implementation

Before writing code, map the disassembly structure to engine patterns.

### Two Classes, Not One

In the disassembly, the shooter and arrow share object ID 0x22 and are distinguished by
routine number. In the engine, they become separate classes:

- `ArrowShooterObjectInstance` -- The stationary shooter (routines 0-4)
- `ArrowProjectileInstance` -- The fired arrow (routines 6-8)

This is a common pattern. The original game reuses object slots to save RAM; the engine
uses typed instances for clarity. Whenever you see a disassembly object where different
routines represent fundamentally different entities (shooter vs. projectile, spawner vs.
spawned, body vs. detached part), plan to split them into separate classes.

### State Mapping

| ASM concept | Engine equivalent |
|-------------|-------------------|
| `routine(a0)` values 0/2/4 | Animation state enum or tracking field in the shooter |
| `routine(a0)` values 6/8 | A separate `ArrowProjectileInstance` class |
| `anim(a0)` values 0/1/2 | `currentAnim` field: IDLE, DETECTING, FIRING |
| `x_vel(a0)` = $400 | `xVelocity` field with fixed-point math |
| `collision_flags(a0)` = $9B | `getCollisionFlags()` returns 0x9B |
| `AllocateObject` + copy | `spawnChild(...)` / `spawnFreeChild(...)`; reserve direct manager allocation for documented bridge code |
| `MarkObjGone` | `isOnScreen()` check |
| `DeleteObject` | `ObjectLifetimeOps` / compatibility deletion helper; legacy code may still use `setDestroyed(true)` |
| `PlaySound` | `services().playSfx(id)` |
| `AnimateSprite` | Manual timer + frame update (or engine animation system) |

---

## Step 3: Register the Object

Two files need changes.

### Name Registration

Open `src/main/java/com/openggf/game/sonic2/objects/Sonic2ObjectRegistryData.java`. Add
an entry mapping the object ID to a human-readable name:

```java
map.put(0x22, List.of("ArrowShooter"));
```

This is used for debug overlays and logging.

### Factory Registration

Open `src/main/java/com/openggf/game/sonic2/objects/Sonic2ObjectRegistry.java`. In the
`registerDefaultFactories()` method, add:

```java
registerFactory(Sonic2ObjectIds.ARROW_SHOOTER,
        (spawn, registry) -> new ArrowShooterObjectInstance(spawn,
                registry.getPrimaryName(spawn.objectId())));
```

This tells the engine: when object ID 0x22 appears in level data, create an
`ArrowShooterObjectInstance`. The `ObjectSpawn` record passed to the constructor contains:

| Field | Source |
|-------|--------|
| `x()` | X position from placement data |
| `y()` | Y position from placement data |
| `objectId()` | 0x22 |
| `subtype()` | Subtype byte from placement data |
| `renderFlags()` | Render flags (bit 0 = H-flip, bit 1 = V-flip) |

---

## Step 4: Implement the Shooter

Create `src/main/java/com/openggf/game/sonic2/objects/ArrowShooterObjectInstance.java`.

### Class Declaration

Extend `AbstractObjectInstance`, which provides the base infrastructure every object needs
(spawn data, services access, screen checks, renderer lookup):

```java
public class ArrowShooterObjectInstance extends AbstractObjectInstance {
```

### Constants

Pull these directly from the disassembly. Every magic number in the ASM should become a
named constant:

```java
private static final int DETECTION_DISTANCE = 0x40;  // cmpi.w #$40,d0
private static final int PRIORITY = 3;               // move.b #3,priority(a0)
private static final int ANIM_IDLE = 0;
private static final int ANIM_DETECTING = 1;
private static final int ANIM_FIRING = 2;
private static final int DELAY_IDLE = 0x1F;           // from Ani_obj22 byte_idle
private static final int DELAY_DETECTING = 0x03;       // from Ani_obj22 byte_detect
private static final int DELAY_FIRING = 0x07;           // from Ani_obj22 byte_fire
```

### Constructor (Maps to Routine 0: Init)

The constructor replaces `Obj22_Init`. Extract position and flip from the spawn record:

```java
public ArrowShooterObjectInstance(ObjectSpawn spawn, String name) {
    super(spawn, name);
    this.currentX = spawn.x();
    this.currentY = spawn.y();
    this.currentAnim = ANIM_IDLE;
    this.animFrame = 1;             // move.b #1,mapping_frame(a0)
    this.animTimer = DELAY_IDLE;
    this.hFlip = (spawn.renderFlags() & 0x01) != 0;
}
```

### update() (Maps to Routine 2: Main)

This is called every frame. It replaces the `Obj22_Main` routine:

```java
@Override
public void update(int frameCounter, PlayableEntity playerEntity) {
    AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
    if (currentAnim != ANIM_FIRING) {
        updateDetection(player);
    }
    updateAnimation();
}
```

### Detection (Maps to Obj22_DetectPlayer)

```java
private void updateDetection(AbstractPlayableSprite player) {
    if (player == null) return;

    // Absolute horizontal distance (matches the sub.w + neg.w + cmpi.w pattern)
    int dx = currentX - player.getCentreX();
    if (dx < 0) dx = -dx;

    boolean detected = dx < DETECTION_DISTANCE;

    if (detected) {
        if (currentAnim != ANIM_DETECTING) {
            currentAnim = ANIM_DETECTING;
            animTimer = DELAY_DETECTING;
        }
    } else {
        if (currentAnim == ANIM_DETECTING) {
            // Was detecting, player left range -> fire
            currentAnim = ANIM_FIRING;
            animTimer = DELAY_FIRING;
            firingIndex = 0;
            arrowFired = false;
        }
    }
}
```

Note how this maps directly to the ASM logic: detect within $40 pixels, switch to
detecting; when player leaves during detection, switch to firing.

### Arrow Spawning (Maps to Routine 4: ShootArrow)

```java
private void fireArrow() {
    services().playSfx(Sonic2Sfx.PRE_ARROW_FIRING.id);  // SndID_PreArrowFiring

    spawnChild(() -> new ArrowProjectileInstance(
            spawn, currentX, currentY, hFlip));  // equivalent of AllocateObject
}
```

In the ASM, `AllocateObject` finds a free slot and the code copies properties manually.
In the engine, prefer the `level.objects` child-spawn helpers so construction context,
parentage, and lifecycle policies stay centralized. Direct `addDynamicObject(...)`
calls are compatibility bridges for legacy code or unusual allocation paths that cannot
use the standard helpers.

### Rendering

```java
@Override
public void appendRenderCommands(List<GLCommand> commands) {
    PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.ARROW_SHOOTER);
    if (renderer == null) return;
    renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, false);
}

@Override
public int getPriorityBucket() {
    return RenderPriority.clamp(PRIORITY);  // priority 3 from init
}
```

### Debug Rendering

```java
@Override
public void appendDebugRenderCommands(DebugRenderContext ctx) {
    int halfWidth = 0x10;  // width_pixels from init
    int halfHeight = 0x10;
    ctx.drawLine(currentX - halfWidth, currentY - halfHeight,
                 currentX + halfWidth, currentY - halfHeight, 0.4f, 0.6f, 0.2f);
    // ... (draw remaining three sides of the bounding box)
}
```

---

## Step 5: Implement the Arrow

Create `src/main/java/com/openggf/game/sonic2/objects/ArrowProjectileInstance.java`.

### Class Declaration

The arrow needs two things the shooter does not: it moves, and it hurts the player.
Implement `TouchResponseProvider` to participate in the collision system:

```java
public class ArrowProjectileInstance extends AbstractObjectInstance
        implements TouchResponseProvider {
```

### Constants (From Routine 6: Arrow_Init)

```java
private static final int ARROW_VELOCITY = 0x400;   // move.w #$400,x_vel(a0)
private static final int COLLISION_FLAGS = 0x9B;    // move.b #$9B,collision_flags(a0)
private static final int X_RADIUS = 0x10;           // move.b #$10,x_radius(a0)
private static final int Y_RADIUS = 0x08;           // move.b #8,y_radius(a0)
private static final int PRIORITY = 4;              // move.b #4,priority(a0)
private static final int MAPPING_FRAME = 0;         // move.b #0,mapping_frame(a0)
```

### Constructor (Maps to Routine 6)

```java
public ArrowProjectileInstance(ObjectSpawn parentSpawn,
                                int startX, int startY, boolean facingLeft) {
    super(createArrowSpawn(parentSpawn, startX, startY), "Arrow");
    this.currentX = startX;
    this.currentY = startY;
    this.facingLeft = facingLeft;
    // btst #status.npc.x_flip -> beq -> neg.w x_vel
    this.xVelocity = facingLeft ? -ARROW_VELOCITY : ARROW_VELOCITY;
}
```

### update() (Maps to Routine 8: Arrow)

```java
@Override
public void update(int frameCounter, PlayableEntity playerEntity) {
    if (!initialized) {
        services().playSfx(Sonic2Sfx.ARROW_FIRING.id);  // SndID_ArrowFiring
        initialized = true;
    }

    // ObjectMove: apply velocity to position (fixed-point 8.8)
    xSubpixel += xVelocity;
    currentX += (xSubpixel >> 8);
    xSubpixel &= 0xFF;

    // Wall collision check (ObjCheckLeftWallDist / ObjCheckRightWallDist)
    if (checkWallCollision()) {
        ObjectLifetimeOps.deleteNoRespawn(this);  // DeleteObject
        return;
    }

    // MarkObjGone equivalent
    if (!isOnScreen(480)) {
        ObjectLifetimeOps.deleteNoRespawn(this);
    }
}
```

The fixed-point math mirrors the 68000's approach: velocity is in 8.8 format ($0400 =
4.0 pixels), accumulated into a sub-pixel counter, with the integer part added to position
each frame.

### Wall Collision (From Routine 8)

```java
private boolean checkWallCollision() {
    if (facingLeft) {
        // moveq #-8,d3 / bsr.w ObjCheckLeftWallDist
        TerrainCheckResult result = ObjectTerrainUtils.checkLeftWallDist(
                currentX - 8, currentY);
        return result.hasCollision() && result.distance() < 0;  // tst.w d1 / bmi
    } else {
        // moveq #8,d3 / bsr.w ObjCheckRightWallDist
        TerrainCheckResult result = ObjectTerrainUtils.checkRightWallDist(
                currentX + 8, currentY);
        return result.hasCollision() && result.distance() < 0;
    }
}
```

### Touch Response (Collision with Player)

```java
@Override
public int getCollisionFlags() {
    return COLLISION_FLAGS;  // $9B: hurts player, size index $1B
}

@Override
public int getCollisionProperty() {
    return 0;  // no special property (not a multi-hit object)
}
```

The `TouchResponseProvider` interface tells the collision system that this object can
interact with the player. The flags value `$9B` means bit 7 is set (harmful) with
size index $1B defining the collision bounding box dimensions.

---

## Step 6: Art and PLC Wiring

The ArrowShooter's art is Nemesis-compressed at the ROM address labeled
`ArtNem_ArrowAndShooter`. The PLC system loads it when Aquatic Ruin Zone starts.

In the engine, this wiring already exists for ARZ through `Sonic2PlcArtRegistry`. When
you implement a new object in an already-supported zone, the art is likely already loaded
by the zone's PLC entries. You just need to use the correct art key.

In the object code, both the shooter and the arrow reference their art via:

```java
PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.ARROW_SHOOTER);
```

`getRenderer()` (inherited from `AbstractObjectInstance`) looks up the art key in the
current zone's loaded art sets and returns a renderer that knows the mapping frames.

If you are implementing an object for a zone that does not yet have its PLC entries wired,
you will need to add the art to the PLC registry. The pattern is:

1. Use `RomOffsetFinder` to find the art's ROM address.
2. Add the address as a constant in the game's constants file.
3. Register the PLC entry in the game's PLC art registry.
4. Define an art key constant for the object to reference.

---

## Step 7: Test It

### Manual Testing

1. Build: `mvn package`
2. Run the engine and select Sonic 2.
3. Navigate to Aquatic Ruin Zone (use Page Down/Page Up to cycle zones/acts, or set
   `debug.startup.levelSelectOnStartup` to `true` in `config.yaml`).
4. Find an ArrowShooter in the level (they are embedded in stone pillars).
5. Verify:
   - The shooter animates when you approach within ~64 pixels.
   - When you leave range during detection, it fires an arrow.
   - The arrow travels horizontally and stops at walls.
   - Sound effects play at the correct moments.
6. Enable the debug overlay (F1) and object labels (F5) to confirm positions and states.

### Automated Testing

For a more rigorous check, write a `HeadlessTestFixture` test:

```java
@Test
void testArrowShooterDetection() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .game(GameId.SONIC_2)
            .zone(ZoneId.ARZ)
            .act(0)
            .build();

    // Place player near an ArrowShooter position
    fixture.teleportPlayer(0x500, 0x300);
    fixture.stepFrames(30);

    // Verify the shooter entered detecting state
    // (specific assertions depend on your object's exposed state)
}
```

See [Testing](testing.md) for more details on the test framework.

---

## Answer Key

The real implementations are at:

- **Shooter:** `src/main/java/com/openggf/game/sonic2/objects/ArrowShooterObjectInstance.java`
- **Arrow:** `src/main/java/com/openggf/game/sonic2/objects/ArrowProjectileInstance.java`
- **Registry entry:** `src/main/java/com/openggf/game/sonic2/objects/Sonic2ObjectRegistry.java`
  (search for `ARROW_SHOOTER`)
- **Name mapping:** `src/main/java/com/openggf/game/sonic2/objects/Sonic2ObjectRegistryData.java`
  (search for `0x22`)
- **ASM source:** `docs/s2disasm/s2.asm` lines 51034-51168
- **Sprite mappings:** `docs/s2disasm/mappings/sprite/obj22.asm`

Compare your work against these files. Pay particular attention to:
- Do your constants match the ASM values?
- Does your detection logic produce the same result for the same player positions?
- Does your arrow velocity and wall collision match?
- Do your SFX IDs match the `SndID_` constants?

---

## Applying This to Other Objects

The ArrowShooter is a good teaching example because it covers many common patterns:
parent/child objects, state machines, detection, projectiles, sound effects, and
collision. Most Sonic 2 objects use a subset of these.

When implementing a new object:

1. **Find and read the ASM.** Identify the routines, understand the state machine, note
   every constant.
2. **Decide on class structure.** One class per logical entity. If the ASM uses different
   routines for a parent and child, split into separate classes.
3. **Choose shared contracts.** Use canonical profiles under
   `com.openggf.game.profiles.*` for solid, touch-response, and lifecycle semantics when
   the object fits an existing family. If an old provider method is still the source of
   truth, go through the `level.objects` compatibility adapter instead of adding another
   game-local profile shape.
4. **Declare player participation.** Use `ObjectPlayerQuery` and
   `ObjectPlayerParticipationPolicy` for main-player, native P1/P2, all-player, nearest,
   or extended-sidekick behavior. Direct first-sidekick or focused-player checks need a
   native-only reason backed by a test or guard baseline.
5. **Register it.** Name in `RegistryData`, factory in `Registry`.
6. **Implement init from the constructor.** Everything in routine 0 becomes constructor
   logic.
7. **Implement the main loop in `update()`.** This is the code that runs every frame.
   Use `ObjectControlState` for object-control intent instead of adding raw
   object-control setter combinations.
8. **Implement rendering.** Use `getRenderer(artKey)` and `drawFrameIndex()`.
9. **Wire collision if needed.** Implement `TouchResponseProvider` for harmful objects,
   or implement `SolidObjectProvider` for solid platforms.
10. **Wire lifetime behaviour.** Use `ObjectLifetimeOps` or the existing
    `level.objects` wrapper for destruction, offscreen expiry, respawn latches, and slot
    transfer. Treat direct `setDestroyed(true)` as legacy unless there is a documented
    compatibility reason.
11. **Test manually, then write automated tests.** If a source guard needs a baseline for
    existing legacy code, ratchet it down when your object migrates instead of growing it.

The [Object Checklists](../../../OBJECT_CHECKLIST.md) show which objects are implemented and
which are still needed.

## Next Steps

- [Adding Bosses](adding-bosses.md) -- Boss-specific patterns beyond simple objects
- [Adding Zones](adding-zones.md) -- Bringing up a new zone
- [Testing](testing.md) -- Writing thorough tests
