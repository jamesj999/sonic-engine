# Flying Battery Zone compatibility matrix

> **2026-09-12 investigation:** the complete-route and 13-row compatibility
> gates remain pending. The native controller clears the former button/Obj28
> recovery frontier and reaches a later spike/magnetic-platform interaction.
> See [outstanding actions](fbz-outstanding-actions.md) for current measured
> blockers. The focused slices below do not establish complete-route PASS.

This is the extension-compatibility gate for the production FBZ runtime. It
supplements, but never replaces, native disassembly parity, the uninterrupted
FBZ route suites, the complete-run BK2 trace replay, and immutable visual
checkpoint comparison.

## Evidence contract

`TestFbzCompatibilityMatrix` uses fresh production gameplay sessions for every
matrix row. Evidence is deliberately aggregated instead of claiming that one
route visits mutually optional branches. The uninterrupted complete-route
oracle starts at the ROM FBZ2 start and advances only through the ordinary frame
pipeline. It observes the mandatory placed wire-cage grab, elevator, floor
launcher, chain forced movement, viewport-backed nonpersistent spawn/despawn,
the exact `$32B8` arena lock, live boss combat and defeat, the real capsule,
camera releases `$2FDC` and `$3738`, and the forced SOZ Act 0 request.

Four fresh production-pipeline probes cover optional branches for every matrix
row. They select exact FBZ2 placement records and let `ObjectManager` materialize
them through the normal production registry: `$CF` button contact and
destruction graph, active `$E4` hazards plus standing suppression and scalable
solid contact, both `$74` magnetic-platform subtypes, and strict-P1 `$E5`
capture/transport/release while extra sidekicks cannot steal authority or remain
stuck. A separate starpost-6 slice isolates P1 plane-event authority and the
`$45C` boss-load coordinate normalization.

The `$74` probe distinguishes the two authored interactions instead of requiring
every participant to remain attached indefinitely. Subtype `$0E` proves exact
active/inactive carry, per-rider solid ownership, and an input-driven voluntary
exit. Subtype `$0F` first proves nonzero carry for every participant and then
materializes the exact earlier-slot `$6B/$61` crusher at `$1C40,$0718`. P1 must
reach the crusher's native `Kill_Character` outcome: routine 6, airborne,
`x_vel=0`, `g_velocity=0`, and `y_vel=-$700`. Because the later-slot platform
still executes its `SolidObjectFull_Offset` checkpoint in that frame, it must
clear stale `OnObj`, standing-bit, and riding-object ownership immediately.
Eligible CPU sidekicks may instead leave through a previously observed voluntary
input exit or through `FLIGHT_AUTO_RECOVERY` after their direct/effective leader
dies; either outcome must be alive, airborne, and free of platform ownership.

The native-start ordinary-input program was originally tuned while the engine
incorrectly made the stationary cage's `$42` object-control word suppress
forward movement. The disassembly keeps movement active, and the complete-run
BK2 confirms rightward movement through the comparable Act 2 cage-exit approach.
After that parity correction, the old input program became cadence-dependent.
The route-oracle repair changes ordinary controller input only. Its bounded
adaptive gates use exact placed-object identity and live collision geometry; it
does not revert the correct cage behavior, branch on a donor/frame identity, or
hydrate engine state from trace data. Optional interaction requirements remain
mandatory through the exact-placement probes; they are not weakened merely
because the completion path bypasses them.

## Multi-sidekick

| Configuration | Runtime participants | Complete route | Authority/art-bank slice |
|---|---|---:|---:|
| Sonic | none | PENDING | PASS |
| Sonic + Tails | `tails_p2` | PENDING | PASS |
| Sonic + Tails + Knuckles | `tails_p2`, `knuckles_p3` | PENDING | PASS |
| Sonic + Tails + Knuckles + Sonic | `tails_p2`, `knuckles_p3`, `sonic_p4` | PENDING | PASS |
| Sonic + three duplicate Sonics | `sonic_p2`, `sonic_p3`, `sonic_p4` | PENDING | PASS |

The authority slice deliberately places every extra sidekick beyond world X
`$2E80` while P1 remains below it and proves that the production controller does
not advance. Every frame of each complete row accumulates and reasserts the exact
sprite identities, CPU-control ownership, alive state, participant order, and
daisy-chain leaders; endpoint-only survival is not accepted.
Live renderer diagnostics prove that every sidekick owns a non-overlapping DPLC
range in the virtual pattern-ID space, including all three duplicate Sonics.

## Widescreen

| Preset | Width | Complete route | Exact lock/rebase slice |
|---|---:|---:|---:|
| `NATIVE_4_3` | 320 | PENDING | PASS |
| `WIDE_16_9` | 400 | PENDING | PASS |
| explicit native-pixel override | 512 | PENDING | PASS |
| explicit native-pixel override | 640 | PENDING | PASS |
| `SUPER_32_9` | 800 | PENDING | PASS |

The September controller checks advance the 640px row from frame 1455 to
25729 and the 800px row from 1291 to 25751. The former now dies near the lower
floating-platform/ceiling geometry; the latter reaches the lower spike at
`$0C00`. Neither row is a complete-route pass. The early recovery commits
preserve the other three viewport diagnostics exactly; the separate late-route
repair advances the native-width row beyond Obj28 to the `$2360` platform.

Every width asserts world-coordinate thresholds, ordinary nonpersistent
placement entering at the right viewport frontier and culling at the left
viewport frontier, the route's authored left player extreme, live hazard
presence, boss containment during combat, exact arena lock, post-boss camera
release, SOZ exit, and no unsafe death. The arena maximum remains the ROM word
`$32B8` for every viewport. P1 is intentionally not required to touch the
physical right edge of a wide viewport inside the boss arena: that would cross
the native world boundary and is precisely the unsafe behavior this gate must
prevent. At widths above 592 pixels P1 can reach the native player boundary
`Camera_max_X + 320 - 24` before a centred widescreen camera can reach its maximum;
the event controller therefore closes `Camera_min_X` at the existing maximum only
after P1 reaches that boundary. It never rewrites `Camera_max_X`. The boss-load
rebase subsequently normalizes both camera words to `$32B8-$45C = $2E5C`.

## Cross-game donation

| Capability profile | Donor ROM | Complete mandatory route | Spindash dependency |
|---|---|---:|---:|
| Off | none | PENDING | no |
| Sonic 1 donation | discovered S1 REV01 ROM | PENDING | must remain absent |
| Sonic 2 donation | discovered S2 REV01 ROM | PENDING | no required workaround found |

Donation is configured before any playable sprite or level is created. The donor
provider is initialized against the S3K host module, then the route starts in a
second fresh production gameplay session. No raw donor-name or zone carve-out is
permitted. A donor-specific semantic workaround is justified only if a mandatory
mechanic remains blocked after the full route is executable.
Donor discovery follows `RomTestUtils`: the game-specific system property, then
environment variable, configuration value, and finally the conventional root
filename. Missing donor images fail their corresponding mandatory parameterized row;
the native-off row always runs.

The S1 row has two explicit run-to-activate capability replacements in
`FbzS1DonationUpperLoopAssist` and `FbzS1DonationLowerLoopAssist`, dispatched by
`Sonic3kFBZEvents`. Each production site is labelled
`S1 donation compatibility:`. The gates are semantic: donation must be active,
the composed player rules must lack Spindash, P1 must be grounded and moving
left, and P1 must occupy the corresponding authored loop approach. Native S3K
and S2 donation retain their ordinary behavior, and neither helper uses a donor
name, route identity, or frame number.

## Native comparison configurations

| Native team | Expected `Player_mode` | Result |
|---|---|---:|
| Sonic | `SONIC_ALONE` | PASS |
| Tails | `TAILS_ALONE` | PASS |
| Sonic + Tails | `SONIC_AND_TAILS` | PASS |
| Knuckles | `KNUCKLES` | PASS |

`TestFbzNativeConfiguration` establishes these fresh comparison fixtures with
donation source `off`, donation/debug/rewind extensions disabled, a 320-pixel
camera, S3K-owned rules and physics, the exact native roster, and native CPU mode
for Tails P2.

## Verification status

Focused authority/lock coverage and all four native configuration rows pass. The
exact `$74` optional probe and its generic later-solid dead-rider cleanup
regression also pass in isolation. The complete-route cells remain `PENDING`
until the post-cage native-start controller oracle is green without reverting
the ROM-accurate stationary-cage behavior. They must not be relabelled PASS from
the narrower slices.

The compatibility matrix contains 13 rows: five multi-sidekick teams, five
viewport widths, and three donation profiles. Every row runs both the complete
mandatory route and all four exact-placement optional probes. Run it separately
from the token-intensive complete-run trace:

```text
mvn "-Dtest=com.openggf.tests.TestFbzCompatibilityMatrix" "-Ds3k.rom.path=<discovered locked-on ROM path>" test
```

The final acceptance command is:

```text
mvn "-Dtest=TestFbzCompatibilityMatrix,TestFbzNativeConfiguration,TestFbzNativeCharacterRoutes,TestFbzAct1RouteHeadless,TestFbzAct2RouteHeadless,TestS3kFbzCompleteRunTraceReplay" "-Ds3k.rom.path=<discovered locked-on ROM path>" test
```

After that command is green, the immutable visual suite must run in
`native-post-compat` mode with extensions disabled and be recorded under the
distinct `Post-compatibility native regression` section of `fbz-validation.md`.
The complete-run trace and visual artifacts are owned by the final native parity
gate; a missing or failing artifact cannot be converted into a compatibility
PASS here.
