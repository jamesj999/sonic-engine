# Per-Game Rule Placement

Use this guide when adding or moving a Sonic 1, Sonic 2, or Sonic 3&K behavior divergence. The goal is to choose the smallest accurate owner instead of adding another broad rule bag.

This guide describes the ownership model for per-game rule gates. Game-wide shared runtime gates should use the narrowest typed `GameRules` record. Provider, profile, registry, zone, and object-local ownership rules apply when they are the smaller accurate owner. Do not add broad feature-set bags as a parallel rule source.

## Decision Tree

1. If the behavior is data, art, mappings, DPLC, PLC, animation script, palette data, or ROM asset availability, use the existing data loader, art provider, donor capability, or ROM offset provider. Do not add a `GameRules` field.
2. If the behavior is zone-local or event-local, use `ZoneFeatureProvider`, `ZoneRuntimeState`, zone event handlers, or an existing runtime registry. Do not add a game-wide rule unless the same ROM rule applies across the game.
3. If the behavior belongs to one object family, use an object profile, object-local hook, or shared object execution profile. Do not add a game-wide rule for one object family.
4. If the behavior is character ability availability or cross-game donation, use `PlayerCapabilityRules` or current `DonorCapabilities`, as appropriate. Cross-game donation may only donate explicitly listed capability fields.
5. If the behavior is shared runtime logic that differs by game across broad systems, target the narrowest `GameRules` record consumed by that system:
   - `PlayerMovementRules`: movement, roll, slope, jump, control, and boundary movement rules.
   - `CollisionRules`: collision model, terrain probe, wall-push, platform contact, and collision ordering.
   - `PlayerAnimationRules`: animation-state divergences tied to shared player animation logic.
   - `CameraRules`: camera scroll, wrap, visibility, and tracking rules.
   - `RingRules`: placed/lost ring collision, collection, cadence, attraction, and ring object model rules.
   - `ObjectInteractionRules`: solid object, touch response, boss hit, respawn table, and object execution ordering.
   - `SidekickCpuRules`: CPU sidekick follow, panic, despawn, catch-up, and death flow.
   - `PowerUpRules`: timer cadence, fixed power-up object slots, and shield/invincibility/speed-shoes support details.
   - `DrowningBubbleRules`: drowning countdown and mouth-bubble cadence rules.
6. If no existing owner fits, stop and add an architecture note before adding a new rule group.

## Admission Checklist

Every new per-game divergence must document:

- ROM evidence: disassembly location or trace-observed ROM state.
- Scope: game-wide, character-wide, object-family, zone-local, or data/provider-owned.
- Owner: exact rule record, provider, profile, or registry.
- Boundary rationale: why this owner is the smallest accurate owner, and why narrower object/zone/provider owners do or do not apply.
- Cross-game value table: Sonic 1, Sonic 2, and Sonic 3&K values.
- Verification: focused unit test, trace replay, or explicit reason trace coverage is not applicable.

## Review Rules

- Prefer provider/profile ownership when a divergence is not shared runtime behavior.
- Prefer a new narrow rule group over growing an unrelated rule record.
- Do not add raw game-name branches in shared runtime code.
- Do not add broad feature-set bags or compatibility bridges as a parallel rule source.
- Do not raise rule-record component-count guard thresholds without architecture review.
