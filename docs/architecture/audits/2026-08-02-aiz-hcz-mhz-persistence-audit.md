# AIZ, HCZ, and MHZ Persistence Audit

## Scope and method

This is a route-led audit, not a sweep of every `isPersistent()` override. It covers
persistent concrete classes instantiated from the start of the current AIZ, HCZ, and MHZ
complete-run segments through their present frontiers, plus reachable children and
fixed/event-owned controllers. Boss-only objects beyond a frontier, uninstantiated registry
entries, and generic classes not reached by these layouts are excluded.

Placement inventory:

- AIZ: 27 collapsing platforms, 7 collapsing-log bridges, 2 draw bridges, 12 cork floors,
  and 10 breakable walls; background trees are event-spawned.
- HCZ: 64 conveyors, 12 hand launchers, 8 breakable bars, and 16 twisting loops.
- MHZ: 7 swing vines and 19 breakable walls; the pollen controller is fixed-slot spawned.

For every candidate, the engine lifetime was compared with the locked-on S&K-side object
tail. `isPersistent()` is correct only when it preserves a more precise self-managed tail,
models a fixed/event-owned controller, or matches an active-state exemption in the ROM.

## Results

| Object | Engine and ROM lifetime | Verdict |
|---|---|---|
| AIZ Draw Bridge | Engine returned `!isDestroyed()` in every phase with no self-cull. Normal/wait operations compare saved pivot `$30` with `Camera_X_pos_coarse_back`, delete referenced pieces, clear respawn bit 7, and delete the root (`sonic3k.asm:59649-59676`). After the collapse trigger, `loc_2B452` instead counts `$34=$0E` down and self-deletes without a range tail (`59769-59791`). | **Fixed.** Normal/wait phases use the fixed native `$280` pivot predicate after their routine; collapse alone remains persistent until its timed delete. Real S3K-layout manager tests prove unload/recreation and trigger collapse while already out of range, establishing the wait-to-collapse transition before post-routine culling. |
| MHZ Swing Vine | Engine stayed persistent while either player was grabbed. ROM `loc_22824` has no grab exemption: it applies the root coarse-X tail and deletes its child chain/root (`sonic3k.asm:47164-47192`). P1 swinging may request forced camera tracking, but that does not remove the root tail and does not cover every P2/release state. | **Fixed.** It is non-persistent with the fixed anchor `$280` predicate; `onUnload()` retains player-control cleanup. A test proves the tail remains active while the grabbed vine requests forced scroll. |
| AIZ Collapsing Log Bridge and pieces | Parent/pieces are persistent but execute their own exact coarse-range and retained-render-bit deletion (`sonic3k.asm:59304-59402`). | Correct self-managed lifetime; generic pre-cull would be early. |
| Generic S3K Collapsing Platform and fragments | Parent runs `Sprite_OnScreen_Test`; falling pieces consume prior `render_flags` before movement (`sonic3k.asm:44835-44888`). | Correct self-managed lifetime; covered by fragment phase tests. |
| AIZ background-tree spawner/tree | Spawner ends only at its script terminator; trees delete only on the event camera predicate (`sonic3k.asm:105512-105574`). | Correct event-owned lifetime. |
| HCZ Conveyor Belt | Engine self-culls against both stored belt bounds. ROM tests `$3C/$3E`, clears the subtype load-array and respawn entries, then deletes (`sonic3k.asm:66349-66384`). | Correct; a generic single-anchor cull would be wrong for wide belts. |
| HCZ Breakable-Bar debris | Engine uses explicit post-motion render deletion. ROM consumes `render_flags` after movement before clearing respawn/deleting (`sonic3k.asm:42919-42933,42646-42653`). | Correct self-managed fragment lifetime. |
| HCZ Hand Launcher arm | Arm lives with its parent and expires when the parent leaves the manager. ROM parent always reaches a screen tail; the retracted arm has no independent cull and the extended arm has its own (`sonic3k.asm:65824-65875,66015-66030`). | Correct parent-child coupling. |
| HCZ Twisting Loop | Engine persists only while either captured-player phase is active. ROM skips `Delete_Sprite_If_Not_In_Range` only while either phase byte is nonzero (`sonic3k.asm:76440-76505`). | Correct active-state persistence. |
| MHZ pollen spawner | Engine is always persistent. ROM installs it in fixed `Dynamic_object_RAM+object_size`; its routine has no delete/range tail (`sonic3k.asm:7792,81616-81629`). | Correct fixed-slot controller, not a placement-backed RememberState object. |
| Cork-floor and Breakable-Wall fragments | Engine persists until its render-based self-delete. ROM moves each fragment, consumes `render_flags`, then deletes (`sonic3k.asm:58591-58600,45761-45770`). | Correct self-managed fragment lifetime. |

## Verification and residual scope

The test-first red run produced exactly three new failures: the AIZ direct lifetime
assertion, the manager AIZ unload assertion, and the grabbed MHZ Swing Vine lifetime
assertion. Independent disassembly review then caught the Draw Bridge collapse exception;
the implementation and real S3K-layout manager coverage were tightened before acceptance.
The final combined recorder, object, manager, and rewind-guard batch passed 75/75.

This audit does not claim that the remaining repository-wide persistence overrides are
correct. The next expansion should follow route pressure and dynamic-slot evidence, using
the same inclusion rule and recording both clean and actionable classes here or in a
successor audit.
