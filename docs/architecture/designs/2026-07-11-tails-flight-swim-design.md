# Tails Flight, Swimming, and Carry Design

## Requirements

### Goals

- Implement player-controlled Tails flight and swimming with Sonic 3 & Knuckles frame behavior.
- Support solo/main-character Tails and manually controlled sidekick Tails.
- Allow the main character to grab and be carried by a flying or swimming Tails.
- Reuse the existing CPU recovery and CNZ/MGZ scripted-flight behavior instead of creating a competing implementation.
- Make availability follow the active character donor, including when donation removes a capability present in the host game.
- Preserve deterministic rewind and existing S2/S3K trace behavior.

### Capability Matrix

| Host runtime | Active Tails source | Manual flight/swim |
| --- | --- | --- |
| S1 | S2 donor | Disabled |
| S1 | S3K donor | Enabled |
| S2 | Native S2 | Disabled |
| S2 | S3K donor | Enabled |
| S3K | Native S3K | Enabled |
| S3K | S2 donor | Disabled |

The active character source owns character-specific capabilities. Donation replaces the host value for this capability; it does not only add features.

### Non-goals

- Adding manual flight to native Mega Drive/Genesis S2 Tails.
- Letting non-main sidekicks grab Tails or creating nested carry chains.
- Changing the automatic CPU recovery decision-making or inventing new multi-sidekick flight AI.
- Adding a user configuration override for flight availability.
- Hydrating engine state from trace data.

### Constraints

- Per-game behavior must use typed rules or providers, never raw host-game checks in shared movement code.
- Flight must use ROM center-coordinate semantics for `x_pos` and `y_pos`.
- Existing sprite fields `double_jump_flag` and `double_jump_property` remain authoritative for flight phase and remaining time.
- Existing user changes in the worktree must not be overwritten.
- JUnit tests must use JUnit 5.

### Acceptance Criteria

1. Native S3K and S3K-donated Tails can activate manual flight/swimming; native or donated S2 Tails cannot.
2. Activation, timer, flap, gravity, camera clamp, animation, sound, and landing behavior match the cited S3K routines; control-takeover cleanup satisfies the engine integration contract.
3. Only the main character can grab Tails, with ROM contact, cooldown, carried-position, collision, and release behavior.
4. Underwater carrying prevents new flap thrust but does not immediately exhaust Tails; remaining flight time continues to decrement.
5. CPU recovery and CNZ/MGZ scripted carries continue to use their existing decision logic while delegating shared movement/carry mechanics.
6. Rewind restores flight and carry state deterministically.
7. Focused tests and relevant S2/S3K trace suites pass; any moved trace frontier is documented.

### Assumptions

- The current input routing remains authoritative: solo/main Tails reads the main controller, while a manually controlled sidekick reads the existing P2 logical input path.
- The current donor art translation can expose the S3K flight/swim animation scripts after canonical animation vocabulary is expanded.
- The main character is resolved from the gameplay sprite manager each frame, so carry state does not need a persistent raw sprite reference.

### Risks

- Shared movement ordering is trace-sensitive; moving gravity or collision by one phase can regress CPU and scripted routes.
- S3K flight uses animation IDs not currently represented by canonical animation names.
- Carry state currently lives partly in `SidekickCpuController`; migration must not alter CNZ/MGZ sequence ownership.
- Rewind must capture newly centralized carry scalars and avoid uncaptured object references.

## Exploration Synthesis

### Current Engine Behavior

- `Tails#getSecondaryAbility()` already returns `SecondaryAbility.FLY`.
- `PlayableSpriteMovement` detects airborne jump re-presses, but `tryShieldAbility()` deliberately declines `FLY`, so player input cannot activate flight.
- Active `double_jump_flag` already selects the reduced `+0x08` flight-gravity path used by scripted carry behavior.
- `SidekickCpuController.applyFlyingCarryVerticalVelocity()` implements most of `Tails_Move_FlySwim`: alternate-frame timer decrement, flap phases, the `-$100` threshold, `+$08` gravity, and the camera-top clamp.
- `SidekickCpuController` also owns current CNZ/MGZ carry flags, cooldown, velocity latches, and scripted sequencing.
- S2 and S3K player art already contain Tails flight data, while the S3K `$20-$28` animation variants are not all represented canonically.
- Player water state, movement state, input state, and current sprite rewind data already exist in `AbstractPlayableSprite` and its controller stack.

### Disassembly Evidence

- S3K activation: `docs/skdisasm/sonic3k.asm`, `Tails_JumpHeight` and `Tails_Test_For_Flight` (around lines 28592-28658).
- S3K movement: `Tails_FlyingSwimming` and `Tails_Move_FlySwim` (around lines 27570-27649).
- S3K animations/audio: `Tails_Set_Flying_Animation` (around lines 27650-27731).
- S3K carry: `Tails_Carry_Sonic` and `sub_1459E` (around lines 27222-27509).
- S3K landing reset: `Tails_TouchFloor` clears `double_jump_flag` (around line 29168).
- S2 native jump logic: `docs/s2disasm/s2.asm`, `Tails_JumpHeight` (around line 40433) has jump-height limiting but no manual flight activation.

### Disassembly-Validated Semantics

- Activation initializes `double_jump_flag` to `1` and `double_jump_property` to `(8*60)/2`, or `$F0`.
- The timer decrements on alternate level frames, producing eight seconds at 60 Hz.
- A flap begins at state `2`; flap states subtract `$20` from vertical velocity and advance until pre-subtraction velocity is less than `-$100` or state reaches `$20`, then return to state `1`. Flap states bypass the ready-state gravity addition.
- Ready state `1` adds `$08` vertical velocity. All states clamp upward velocity to zero within `$10` of the camera minimum Y.
- Air animations are `$20-$24`; swim animations are `$25-$28`.
- Airborne flight/tired sounds play only while on screen at the ROM 16-frame cadence. Swimming is silent.
- While carrying the main character underwater, new flap activation is blocked. The timer is not cleared, so grabbing Tails underwater does not immediately exhaust him.
- Successful carry contact uses fixed ROM bounds and eligibility gates, then locks and positions the main character on Tails' gravity-relative carry side (below normally, above under reverse gravity). The routine first clears participant velocity, ground velocity, and angle, then seeds participant X/Y velocity from Tails before returning.
- The carried main character releases by pressing jump. Release applies `-$380` vertical velocity and optional `-$200`/`$200` horizontal velocity from held direction.
- `Tails_TouchFloor` clears flight; the subsequent grounded path releases the carried player. Object-control takeover cleanup remains an engine integration requirement rather than a claim sourced from these cited flight/carry ranges.

### Recommendation

Extract one shared flight routine and one per-Tails carry component. Keep flight state in the existing ROM fields. Let CPU recovery and scripted sequences supply context and inputs while retaining ownership of their decisions. Gate manual activation through typed donor capability composition.

## Architecture Decision

### Ownership and Boundaries

`PlayerCapabilityRules` will expose whether the active character source supports manual Tails flight/swimming. Native modules define their source capability, and cross-game composition takes the donor's value when donation is active. Shared movement code checks the typed rule together with `SecondaryAbility.FLY`; it never branches on a game ID.

A focused shared flight component will own:

- activation and roll/radius restoration;
- timer and flap-state progression;
- flight gravity and camera clamp;
- air/swim animation selection; and
- airborne flight sound cadence.

The component will read and write `double_jump_flag` and `double_jump_property` on the Tails sprite rather than duplicating them internally.

A per-Tails carry component will own:

- carrying/not-carrying state;
- re-grab cooldown;
- the velocity latches used to detect external displacement; and
- explicit scripted-carry context required by CNZ/MGZ transitions.

It resolves the main character through gameplay sprite services and retains no raw participant reference. `SidekickCpuController` retains CPU state, target selection, and scripted sequencing, delegating only shared flight/carry mechanics.

### Lifecycle and Rewind

The components are owned by `PlayableSpriteController`, matching movement, animation, dust, and drowning lifetime. Flight needs no new snapshot fields because its state is already stored on the sprite. Carry scalar state is added to player rewind capture. Carry restoration re-resolves the main character instead of restoring an object reference.

Landing, player reset, death/reset paths, and object-control takeover clear flight/carry through the same component entry points. Scripted transitions can request an explicit carry context but cannot bypass state cleanup.

### Frame Data Flow

1. Jump-height handling observes the routed logical controller input.
2. An eligible airborne jump re-press activates flight when both `SecondaryAbility.FLY` and the typed source capability are present.
3. Active flight runs timer/flap/ready-gravity updates and the camera clamp.
4. Animation and sound resolve immediately from that vertical result, the timer, underwater state, and carry state inherited from the prior frame.
5. Existing horizontal air control and boundary checks run.
6. Movement and Tails terrain collision run.
7. Carry contact/update runs after Tails collision, matching `Tails_FlyingSwimming`. A newly acquired carry therefore changes Tails' carry animation on the following frame, while participant attachment is immediate.

The exact integration point must preserve the current post-gravity ordering used by `PlayableSpriteMovement`; tests will guard against double gravity or a one-frame collision shift.

### Animation and Audio Contracts

Canonical animation vocabulary will represent level/descending flight, ascending flight, carried level/descending flight, carried ascending flight, tired flight, level/descending swim, ascending swim, carried swim, and tired swim. Native/donated profiles map those meanings to game-specific animation scripts.

Audio adds canonical flying and tired-flight sounds mapped by S3K. The flight component requests them only for airborne, on-screen Tails at the disassembly cadence. An enabled flight capability without its required animation contracts is a provider/configuration error, not a silent fallback to an incorrect animation.

### Migration and Rollback

Migration proceeds by first characterizing existing CPU/MGZ behavior with tests, then extracting the shared math without changing call order, then adding manual activation/carry. The old helper is removed only after callers use the shared component. If integration exposes unresolved trace regressions, manual activation can remain gated off while the extracted shared routine is corrected; no save-data migration is involved.

## Feature Design

### Manual Activation

Tails jumps normally. After the jump button has been released, a new press while airborne invokes the S3K flight test. Activation is allowed for solo/main Tails and for a sidekick during the existing manual-control window. It is rejected when the active source capability is disabled, flight is already active, or the corresponding ROM control/state gate rejects it.

If rolling, activation clears roll, restores default radii, and adds the old-minus-default Y-radius delta to center Y. The locked-on ROM checks reverse gravity and executes `neg.w d0`, but the applied delta is held in `d1`; ROM bytes at `s3k.gen:$15182` confirm opcode `44 40` (`neg.w d0`). The implementation therefore preserves the ROM's unchanged `d1` adjustment instead of assuming a reversed delta. Activation then clears rolling-jump state, sets flight state `1`, initializes `$F0` time, and immediately resolves the flight/swim animation.

### Flight and Swimming

The timer decrements on odd ROM-visible level-frame parity while nonzero. State `1` is ready/descending. A valid new jump press with sufficient remaining time and `y_vel >= -$100` moves to state `2`, but that frame still executes the state-1 `$08` addition. On later frames, flap states subtract `$20` and advance without also adding `$08`; a flap state resets to `1` when its pre-subtraction velocity is less than `-$100` or when it increments to `$20`.

Underwater uses the same timer and movement. It changes animation selection and suppresses a new flap only while Tails is carrying the main character. It does not immediately exhaust Tails. Exhaustion occurs only when the timer reaches zero; it prevents new flap activation until floor contact resets flight.

### Carry

Only the main character is a carry participant. Other sidekicks are excluded even if they are Sonic characters. Carry contact uses the ROM routine/control/debug/spindash eligibility, re-grab cooldown, and unsigned relative bounds: X is `-$10..+$0F`; under normal gravity the main-character center is `$20..$2F` below Tails; under reverse gravity it is `$21..$30` above Tails. A successful grab clears participant X/Y/ground velocity and angle, locks control, positions the main character on Tails' gravity-relative carry side using center coordinates, selects the carried animation, mirrors facing, then seeds participant X/Y velocity from Tails and plays the grab sound.

While carried, the main character follows Tails and receives Tails velocity before its collision check. External displacement or invalid participant state releases the carry through the ROM cleanup path. A main-character jump press performs the intentional release: it clears control/carry, applies the directional cooldown rule, sets optional horizontal velocity, applies `-$380` vertical velocity, and restores jumping/rolling state and dimensions per the disassembly.

CNZ/MGZ sequences may pre-latch or force carry through explicit scripted context. They use the same positioning, movement, animation, collision, release, and cleanup mechanics.

### Edge Cases

- Timer expiration during a carry selects the tired animation but does not automatically release the main character.
- Entering water during flight preserves the timer and switches to swim animation.
- Leaving water preserves the active timer and switches back to flight animation.
- Carrying underwater blocks new flap thrust without clearing time.
- `Tails_TouchFloor` clears flight on floor contact; the following grounded path releases a carried main character.
- Object-control takeover clears manual flight and carry as an engine cleanup contract. This is required for safe integration but is not claimed as validated by the cited flight/carry ranges.
- Rewind across grab/release re-resolves only the main character and restores scalar carry state.
- A donor capability mismatch is surfaced during provider/art validation.

## Acceptance Tests

### Capability and Activation

- Native S3K solo Tails activates after an airborne jump release/re-press.
- Manually controlled native S3K sidekick Tails activates.
- S3K-donated Tails activates in S1 and S2.
- Native S2 Tails does not activate.
- S2-donated Tails does not activate in S1 or S3K.
- Activation restores rolling dimensions and initializes flag/property exactly.

### Movement, Animation, and Audio

- `$F0` timer uses alternate-frame decrement and expires after eight seconds.
- Flap states, `-$100` threshold, `$20` impulse without simultaneous gravity, state-1 `$08` gravity, and camera clamp match the ROM.
- Air animation selection covers `$20-$24`; swim selection covers `$25-$28`.
- Air flying/tired SFX follows on-screen 16-frame cadence; swimming emits neither sound.
- Landing and object-control takeover clear flight.

### Carry

- Only the main character passes the carry participant query.
- Exact contact intervals, cooldown, eligibility, attachment offset, velocity seeding/mirroring, facing, and collision match the ROM.
- Main-character jump release produces `-$380` Y velocity and directional X velocity/cooldown.
- Underwater carry blocks flapping, preserves remaining time, and does not immediately select tired swim.
- Other sidekicks cannot initiate or receive this carry interaction.
- Existing CNZ/MGZ scripted carries remain green.

### Integration and Rewind

- Rewind restores activation, flap phase, exhaustion, swimming, carrying, and release cooldown.
- Donated animation/DPLC/tail-appendage selection works for every flight/swim variant.
- Focused CPU recovery, CNZ carry, MGZ boss handoff, playable animation, and rewind suites pass.
- Relevant S2 and S3K trace replay tests pass without trace hydration or route/frame carve-outs.
- Any trace-frontier movement is recorded in `docs/status/trace-frontier-log.md`.

## Human Review Checklist

- Confirm donor replacement semantics, especially S2 donor into S3K disabling flight.
- Confirm only the main character can participate in carry under multi-sidekick mode.
- Confirm underwater grab preserves time and merely blocks flapping while carried.
- Confirm scripted CNZ/MGZ behavior is included without broadening CPU AI scope.
- Confirm no configuration toggle or native S2 manual flight is desired.
