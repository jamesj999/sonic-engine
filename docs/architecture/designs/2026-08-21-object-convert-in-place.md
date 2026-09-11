# Convert-in-place: a shared capability for ROM objects that rewrite `obID(a0)`

**Status:** design note, no implementation. Commissioned 2026-08-21 after the S1 cannonball
round found that the fix could not be modelled inside the object without either duplicating a
shared class or defeating a fidelity invariant.

## The ROM behaviour

A Mega Drive object can become a different object by rewriting its own SST id and resetting its
routine, then falling into the new object's code. The record is never freed; the slot never
changes; only the dispatch target does:

```
_move.b #id_Explosion,obID(a0)      ; the object is now an explosion
move.b  #0,obRoutine(a0)            ; ...starting at its routine 0
bra.w   Explosion
```

**Verified adopters in S1 alone** — self-rewrites of `obID(a0)`, not copies to a child:

| object | becomes | site |
|---|---|---|
| Cannonball | Explosion | `1E, 20 Badnik - Ball Hog and Cannonball.asm:164` |
| **Monitor** | **Invisibarrier** | `26, 2E Monitors and Power-Ups.asm:31` |
| Buzz Bomber missile | UnusedExplosion | `22, 23 Badnik - Buzz Bomber and Missile.asm:253` |
| GHZ boss base / ball | Explosion | `3D, 48 Boss - GHZ Main and Wrecking Ball.asm:561, 575, 598` |
| Walking Bomb | Explosion | `5F Badnik - Walking Bomb.asm:93` |
| SLZ boss spikeball | Explosion | `7A, 7B Boss - SLZ Main and Spike Balls.asm:881` |
| FZ boss | Explosion | `85,84,86 Boss - FZ Main, Cylinders, and Plasma Balls.asm:1007` |

Ten sites, eight objects, in one game. **The monitor is the important row: its target is not an
explosion.** Any capability that special-cases "becomes an explosion" is wrong.

*(Correction to an earlier report: I named the swinging platforms and the SLZ elevators as
adopters. They are not — their `obID(a0)` occurrences are reads and copies to children, not
self-rewrites. The verified list is the table above.)*

## Why the two obvious implementations are both wrong

### Free the slot and re-take it — forbidden by an invariant we depend on

`ObjectManager.createDynamicObjectAtSlot` will place an object in a chosen dynamic slot, but
requires that slot free. `setDestroyed` only sets a flag, and `releaseSlot` **defers** the free
while the object pass is running:

```java
if (updating && isManagedDynamicSlot(slotIndex)) {
    slotsFreedDuringObjectPass.set(slotIndex);
}
```

That deferral exists so a slot freed mid-pass is not reused by a later object in the same pass —
ROM `FindFreeObj` ordering fidelity, and the exact property the occupancy work measures. **A
conversion that defeated it would corrupt the behaviour it is meant to fix.** This is a hard
constraint, not a preference.

### Fold the target's behaviour into the source — duplicates a shared model

`Sonic1SLZBossSpikeball` already does this by hand: it is never destroyed, keeps its slot, and
returns `ROM_ID_EXPLOSION` from `getLiveObjectId()`. That works because its exploding state
borrows no behaviour.

It does not generalise. A cannonball must actually *become* an Explosion, and
`ExplosionObjectInstance` owns a per-game initial `anim_frame_duration` resolved from the
`GameModule`, an id-keyed init fall-through rule, `RELOAD_DURATION`/`FINAL_MAPPING_FRAME`
timing, destruction-child spawning and its own renderer path. Copying that into each of eight
objects duplicates a shared model and drifts from it the first time it is corrected — invisibly,
because the diff that corrects the shared class will not touch the copies.

## What the capability has to answer

### Slot identity

The slot is retained, never released and re-acquired. The allocator bit must not change state at
any point in the conversion, so the deferral above is never consulted and no later object in the
same pass can observe a free slot. This is the defining property; if an implementation cannot
provide it, it is not this capability.

### Object registry and the execution pass

The pass dispatches over the manager's active-object collection. A conversion mutates that
collection mid-iteration, so the capability must define whether the converted object executes
again on the conversion frame. **The ROM answer is explicit and must be matched:** `bra.w
Explosion` falls straight into the target's routine 0 on the same frame, so the target's init
runs on the conversion frame, and the existing `initFallsThroughToAnimate` modelling shows the
engine already reasons about exactly this distinction.

### Rewind recreation — the sharpest constraint

`RewindRecreatable` states: *"The returned instance must be of the same concrete class as the
captured object."* So:

- A **class-preserving** conversion (the spikeball fold) is rewind-safe for free, because the
  captured class never changes.
- A **class-swapping** conversion is not. A slot whose occupant class changes must capture which
  class currently occupies it and recreate that one, and the converted object's identity for
  reference-relinking must survive the change.

**This is the design's central cost and it should not be hidden.** The capability is worth
building only if class-swapping is supported, since class-preserving is what we already have and
is what forces the duplication. The note's recommendation is therefore that the rewind codec
path is in scope for the implementing round, not an afterthought.

### The target's per-game initial state

Answered by construction: the target is built through its **own** normal factory, so it resolves
its own `GameModule` values, its own mappings and its own renderer exactly as it would if spawned
conventionally. The source contributes position and the retained slot, nothing else. **The source
must never set the target's internal state** — that is what re-implementation looks like, and it
is how drift starts.

### The source's own scheduled state

The ROM resets `obRoutine` to 0 and keeps the rest of the SST record — position, velocity,
render flags and the scratch bytes all persist, and the target reads or overwrites them as its
own code dictates. Anything the source scheduled for later (timers, pending child spawns,
deferred touch responses) belongs to the source's dispatch, which no longer runs. The capability
must therefore define the discard explicitly rather than leaving stale callbacks pointed at an
object that is no longer dispatched, and per-object audit is required where a source registered
itself with a subsystem (solid providers, touch responders) — those registrations must be
withdrawn as part of the conversion.

## What it must not become

1. **It must not free and re-take a slot.** Ruled out above by the deferral invariant.
2. **It must not bypass or special-case the deferral.** If a candidate implementation needs the
   deferral suspended, that is a finding to report, not a flag to add.
3. **It must not require the target to know about the source.** The target is an ordinary object
   constructed by its own factory. A `convertedFrom` parameter, or a target constructor variant
   that exists only for conversion, means the abstraction has failed.

None of the three is currently believed unavoidable. Constraint 3 is the one most likely to come
under pressure, from objects whose target needs the source's scratch state; the honest resolution
there is to pass state through the retained SST record as the ROM does, not through the target's
constructor.

## Scope note

The two classes that fold this pattern by hand — `Sonic1SLZBossSpikeball` and the cannonball's
intended fix — are evidence for the shape rather than against it: one folds correctly because its
target is trivial, and the other cannot because its target is not. **The capability's value is
the six sites that are neither.**

Not scoped here: the implementing round, which the design note is a precondition for.
