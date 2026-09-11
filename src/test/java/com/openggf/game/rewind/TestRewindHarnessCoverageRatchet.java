package com.openggf.game.rewind;

import com.openggf.game.rewind.coverage.ObjectClasspathScan;
import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage ratchet: the number of classes that PASS the round-trip harness
 * must never drop below {@link #RATCHET_FLOOR}.
 *
 * <h2>Intent</h2>
 * This test makes the uplift work resumable and regression-safe: once a class
 * is made headlessly-probeable (by harness extension, constructor changes, or
 * added codec), it stays green. The floor only ever moves UP.
 *
 * <h2>How to raise the floor</h2>
 * Implement harness extensions or construction strategies until the sweep reports
 * a higher probed count, verify all gate tests green, then raise
 * {@link #RATCHET_FLOOR} to the new value (with a comment recording the date and
 * what changed).
 *
 * <h2>Bucket categorization</h2>
 * For each of the 783 spawnable concrete object classes, the probe result is one of:
 * <ul>
 *   <li>{@code no-codec} – no dynamic rewind codec registered; class is immediately
 *       {@code Unprobed("no dynamic recreate path")}. These objects are correctly
 *       dropped on restore; testing them would produce a spurious count-mismatch.</li>
 *   <li>{@code no-probe-ctor} – has a codec but the harness could not construct the
     *       object headlessly (all supported constructor strategies failed with
 *       {@code NoSuchMethodError} or a thrown exception). Common: child objects
 *       whose only constructors take a parent instance or non-ObjectSpawn arguments.</li>
 *   <li>{@code parent-dependent} – has a codec, constructed successfully, but
 *       {@code recreate()} returned {@code null} in isolation because it re-links a
 *       live parent object not present in the standalone ObjectManager. Distinct from
 *       {@code no-probe-ctor}.</li>
 *   <li>{@code other-failure} – has a codec but construction or the round-trip threw
 *       for a reason other than the above two.</li>
 *   <li>{@code passed} – full round-trip succeeded (counted against the ratchet).</li>
 *   <li>{@code hard-failure} – {@code CountMismatch} or {@code ScalarMismatch}; these
 *       are genuine bugs, surfaced as hard assertion failures by the parametrized gate
 *       in {@code TestEveryObjectRewindRoundTrip}.</li>
 * </ul>
 */
public class TestRewindHarnessCoverageRatchet {

    /**
     * Minimum probed count that must never regress.
     *
     * <p>History:
     * <ul>
     *   <li>2026-06-18: initial baseline = 20 (first sweep, 783 total classes;
     *       547 no-codec, 212 no-probe-ctor, 4 parent-dependent)</li>
     *   <li>2026-06-18: raised to 31 after adding (ObjectSpawn,boolean) strategy (S5)
     *       and (ObjectSpawn,ParentType) parent-construct strategy (S6); S6 promoted
     *       19 from no-probe-ctor: 6 to passed, 13 newly exposed parent-dependent.</li>
     *   <li>2026-06-18: raised to 37 after adding PARENT_SEED_TABLE +
     *       PARENT_SPAWN_OBJECT_IDS + tryRoundTripWithSeededParent for S3K CNZ/Gumball/
     *       SpikedLog/CutsceneKnuckles and S2 Turtloid, Buzzer, ARZBoss, CNZBoss families.
     *       Parents that spawn children in ctor (Balkiry, CPZBoss) excluded as honest
     *       ceiling — they need a live session (6 classes remain parent-dependent).</li>
     *   <li>2026-06-19: raised to 38 after Phase-2 codec-deletion batch 3 moved
     *       SmallMetalPformChild, CnzLightsFlashChild, and HCZWaterDropChild onto
     *       RewindRecreatable generic recreate without losing round-trip coverage.</li>
     *   <li>2026-06-19: raised to 39 after Phase-2 codec-deletion batch 7 moved
     *       HczEndBossInstance onto RewindRecreatable generic recreate and supplied
     *       deterministic harness configuration for its character-dependent constructor.</li>
 *   <li>2026-06-19: raised to 40 after exact-spawn codecs began recreating
 *       inside the restore-time ObjectConstructionContext, allowing AizEndBossInstance
 *       to use constructor-time ObjectServices under the harness.</li>
 *   <li>2026-06-19: raised to 43 after adding (ObjectSpawn,ObjectServices,int)
 *       harness construction with inert render services, then deleting the S1/S2/S3K
 *       points popup dynamic codecs.</li>
 *   <li>2026-06-19: raised to 47 after adding RewindRecreatable-only primitive
 *       constructor probes and deleting four AIZ2 self-contained transient child
 *       codecs.</li>
 *   <li>2026-06-19: raised to 53 after session-level verification deleted six
 *       self-contained S3K transient/effect codecs.</li>
 *   <li>2026-06-19: raised to 56 after session-level verification deleted three
 *       S3K release-sequence/effect dynamic codecs.</li>
 *   <li>2026-06-19: raised to 59 after session-level verification deleted three
 *       S3K self-contained transient/countdown/badnik-child dynamic codecs.</li>
 *   <li>2026-06-19: raised to 63 after session-level verification deleted four
 *       S3K self-contained exact-spawn dynamic codecs.</li>
 *   <li>2026-06-19: raised to 65 after session-level verification deleted two
 *       S3K session-level/no-ref exact-spawn dynamic codecs.</li>
 *   <li>2026-06-19: raised to 68 after session-level verification deleted three
 *       S3K exact-spawn end egg capsule dynamic codecs.</li>
 *   <li>2026-06-19: raised to 76 after session-level verification deleted the
 *       remaining S3K end egg capsule codecs, three private S3K badnik projectile
 *       codecs, and three no-ref S3K exact-spawn dynamic codecs.</li>
 *   <li>2026-06-19: raised to 79 after session-level verification deleted three
 *       S3K no-ref nested transient child dynamic codecs.</li>
 *   <li>2026-06-19: raised to 83 after session-level verification deleted four
 *       S2 self-contained projectile/effect dynamic codecs.</li>
 *   <li>2026-06-19: raised to 86 after deleting three S2 scalar-only
 *       child projectile/hazard dynamic codecs.</li>
 *   <li>2026-06-19: raised to 88 after deleting two S2 scalar-only
 *       boss hazard dynamic codecs.</li>
 *   <li>2026-06-19: raised to 90 after deleting two S2 no-object-ref
 *       dynamic codecs.</li>
 *   <li>2026-06-19: raised to 91 after deleting the S2
 *       BossExplosionObjectInstance dynamic codec.</li>
 *   <li>2026-06-19: raised to 92 after deleting the S2
 *       BadnikProjectileInstance dynamic codec.</li>
 *   <li>2026-06-19: raised to 93 after deleting the S2
 *       CPZBossFallingPart dynamic codec.</li>
 *   <li>2026-06-19: raised to 94 after deleting the S2
 *       SpikyBlockSpikeInstance dynamic codec.</li>
 *   <li>2026-06-19: raised to 95 after deleting the S2
 *       MonitorContentsObjectInstance dynamic codec.</li>
 *   <li>2026-06-19: raised to 96 after deleting the S2
 *       BombPrizeObjectInstance dynamic codec.</li>
 *   <li>2026-06-19: raised to 99 after deleting three S2 scalar/no-reference
 *       session-only codecs for ResultsScreenObjectInstance, RingPrizeObjectInstance,
 *       and MTZBossLaser.</li>
 *   <li>2026-06-19: raised to 102 after deleting three S1 scalar/no-reference
 *       projectile codecs for BombShrapnel, Cannonball, and NewtronMissile.</li>
 *   <li>2026-06-19: raised to 105 after deleting three S1 scalar/no-reference
 *       projectile/effect codecs for BuzzBomberMissile, BuzzBomberMissileDissolve,
 *       and CrabmeatProjectile.</li>
 *   <li>2026-06-19: raised to 106 after deleting the S1
 *       ResultsScreen dynamic codec.</li>
 *   <li>2026-06-19: raised to 107 after deleting the S3K ICZ end-boss
 *       escape-ship dynamic codec.</li>
 *   <li>2026-06-19: raised to 111 after deleting six S1 scalar/no-reference
 *       exact-spawn/session-only codecs; EndingEmeralds, ExplosionItem,
 *       MonitorPowerUp, and Ring are now headlessly probeable, while
 *       CollapsingFloor and SpikedBallChain remain honest no-probe session-tail
 *       classes.</li>
 *   <li>2026-06-19: raised to 112 after deleting the S1 BossExplosionObjectInstance
 *       codec and S3K MhzEndBossPaletteFadeController codec; palette fade is now
 *       headlessly probeable through RewindRecreatable.</li>
 *   <li>2026-06-19: raised to 113 after deleting the S3K CorkeyShotChild
 *       codec; generic recreate uses a placeholder script and compact restore
 *       reapplies the captured int[] script.</li>
 *   <li>2026-06-19: raised to 114 after deleting the S3K
 *       MhzEndBossDefeatFragmentChild codec; compact restore now reapplies the
 *       parent-derived subtype/xVel state.</li>
 *   <li>2026-06-19: raised to 115 after deleting the S3K Madmole
 *       SideDrillChild codec; generic recreate derives facingLeft from the
 *       captured spawn render flag.</li>
 *   <li>2026-06-19: raised to 116 after deleting the S3K
 *       S3kResultsScreenObjectInstance codec; compact restore reapplies
 *       constructor state and required playerRef identity resolution.</li>
 *   <li>2026-06-19: raised to 117 after deleting the S3K
 *       MhzEndBossRobotnikShipFlameInstance codec and extending the harness
 *       parent-seed retry path to cover capture-time required object refs and
 *       single-argument parent constructors.</li>
 *   <li>2026-06-19: raised to 118 after deleting the S3K
 *       Mgz2ResultsScreenObjectInstance codec; generic recreate now preserves
 *       the concrete MGZ2 results subclass.</li>
 *   <li>2026-06-19: raised to 119 after deleting the S2 DEZ
 *       BarrierWall codec; generic recreate now relinks Eggman's structural
 *       barrierWall back-reference.</li>
 *   <li>2026-06-20: raised to 120 after deleting the S3K Buggernaut
 *       baby codec; generic recreate now relinks the transient live
 *       Buggernaut parent.</li>
 *   <li>2026-06-20: raised to 123 after deleting the S3K Orbinaut orb,
 *       Ribot active child, and Star Pointer orbiting point codecs; generic
 *       recreate now relinks their transient live badnik parents.</li>
 *   <li>2026-06-21: raised to 133 after the dynamic codec inventory reached
 *       zero and the parent-dependent bucket was split into graph-covered
 *       families versus explicit session-tail work.</li>
 *   <li>2026-06-21: raised to 144 after seeding the remaining parent-linked
 *       other-failure tail in the round-trip harness, including S2 HTZ boss
 *       children, S3K badnik/cutscene/miniboss children, and the SS-entry
 *       flash child.</li>
 *   <li>2026-06-22: raised to 147 after adding generic recreate coverage for
 *       the S3K invisible block and invisible hurt block family.</li>
 *   <li>2026-06-22: raised to 148 after adding generic recreate coverage for
 *       the S2 invisible block.</li>
 *   <li>2026-06-22: raised to 149 after adding generic recreate coverage for
 *       the S1 invisible barrier.</li>
 *   <li>2026-06-22: raised to 153 after adding generic recreate coverage for
 *       the first S1 badnik head batch.</li>
 *   <li>2026-06-22: raised to 158 after adding generic recreate coverage for
 *       the second S1 badnik head batch.</li>
 *   <li>2026-06-22: raised to 161 after adding generic recreate coverage for
 *       the third S1 badnik head batch.</li>
 *   <li>2026-06-22: raised to 167 after adding generic recreate coverage for
 *       six S1 static/decorative objects.</li>
 *   <li>2026-06-22: raised to 172 after adding generic recreate coverage for
 *       five S1 simple scalar objects.</li>
 *   <li>2026-06-22: raised to 177 after adding generic recreate coverage for
 *       five S1 hazard/platform scalar objects.</li>
 *   <li>2026-06-22: raised to 182 after adding generic recreate coverage for
 *       five S1 hazard/conveyor scalar objects.</li>
 *   <li>2026-06-22: raised to 187 after adding generic recreate coverage for
 *       five S1 utility/hazard scalar objects.</li>
 *   <li>2026-06-22: raised to 192 after adding generic recreate coverage for
 *       five S1 platform/hazard scalar objects.</li>
 *   <li>2026-06-22: raised to 196 after adding generic recreate coverage for
 *       four S1 platform-family scalar objects.</li>
 *   <li>2026-06-22: raised to 200 after adding generic recreate coverage for
 *       four S1 edge/monitor/conveyor scalar objects.</li>
 *   <li>2026-06-22: raised to 204 after adding generic recreate coverage for
 *       four S1 bridge/signpost trap scalar objects.</li>
 *   <li>2026-06-22: raised to 208 after adding generic recreate coverage for
 *       four S1 destructible/spawner parent objects.</li>
 *   <li>2026-06-22: raised to 211 after adding generic recreate coverage for
 *       three S1 stomper/push/ending scalar objects.</li>
 *   <li>2026-06-22: raised to 213 after deleting the shared animal,
 *       explosion, and skid-dust dynamic codecs and adding generic recreate
 *       probe support for (ObjectSpawn,ObjectServices).</li>
 *   <li>2026-06-22: raised to 214 after deleting the shared signpost-sparkle
 *       exact-spawn dynamic codec and adding generic recreate probe support for
 *       (int,int).</li>
 *   <li>2026-06-22: raised to 219 after adding RewindRecreatable-only
 *       (ObjectSpawn,int) probe support, making the S1 ROM-zone constructor
 *       family and AizBattleship headlessly verified.</li>
 *   <li>2026-06-23: raised to 220 after HTZ boss smoke particles moved to
 *       spawn-based generic recreate and joined the HTZ graph restore proof.</li>
 *   <li>2026-06-23: raised to 221 after DEZ Eggman exhaust puffs moved to
 *       spawn-based generic recreate and shed their final scalar baseline.</li>
 *   <li>2026-06-23: raised to 222 after S1 Gargoyle fireballs moved to
 *       spawn-based generic recreate and shed their final scalar baseline.</li>
 *   <li>2026-06-23: raised to 224 after S1 Bumper and Running Disc moved to
 *       spawn-based generic recreate, while Bubbles and Teleporter shed stale
 *       scalar baselines.</li>
 *   <li>2026-06-23: raised to 225 after S1 Circling Platform moved to
 *       spawn-based generic recreate and shed its final scalar baseline.</li>
 *   <li>2026-06-23: raised to 226 after S1 Large Grassy Platform moved to
 *       spawn-based generic recreate and shed its final scalar baseline.</li>
 *   <li>2026-06-23: raised to 227 after S1 Animals moved to spawn-based
 *       generic recreate and shed its final scalar baseline.</li>
 *   <li>2026-06-23: raised to 229 after S2 Arrow Shooter and Barrier moved to
 *       name-preserving generic recreate and shed stale recreate baselines.</li>
 *   <li>2026-06-23: raised to 234 after S2 CNZ bumper/block/cloud/bubble-generator
 *       objects moved to generic recreate and shed stale scalar/recreate baselines.</li>
 *   <li>2026-06-23: raised to 254 after S2 mechanism/platform objects moved to
 *       generic recreate and shed stale scalar/recreate baselines.</li>
 *   <li>2026-06-24: raised to 315 after S1 effect scalar objects moved to
 *       generic recreate and shed stale scalar/recreate baselines.</li>
 *   <li>2026-06-24: raised to 316 after S1 boss-fire scalar restore moved to
 *       generic recreate and shed its stale recreate baseline.</li>
 *   <li>2026-06-24: raised to 317 after S2 Crawl scalar restore moved to
 *       generic recreate and shed its stale recreate baseline.</li>
 *   <li>2026-06-24: raised to 321 after S2 badnik parent graph restores moved
 *       to generic recreate and shed stale recreate baselines.</li>
 *   <li>2026-06-24: raised to 322 after S2 Turtloid parent graph restore moved
 *       to generic recreate and shed its stale recreate baseline.</li>
 *   <li>2026-06-24: raised to 323 after S2 Aquis parent graph restore moved to
 *       generic recreate and its private wing moved under graph-covered parent-dependent restore.</li>
 *   <li>2026-06-24: raised to 325 after S1 Caterkiller and Orbinaut graph
 *       parent restores moved to generic recreate under badnik graph coverage.</li>
 *   <li>2026-06-24: raised to 327 after S1 and S2 seesaw parent restores moved
 *       to generic recreate under seesaw ball graph coverage.</li>
 *   <li>2026-06-24: raised to 328 after S2 Egg Prison parent restore moved
 *       to generic recreate under button graph coverage.</li>
 *   <li>2026-06-24: raised to 330 after S2 ARZ boss and OOZ popping-platform
 *       parents moved to generic recreate under graph coverage.</li>
 *   <li>2026-06-24: raised to 331 after S2 boss graph parent restores moved
 *       to generic recreate and HTZ joined the standalone round-trip pass set.</li>
 *   <li>2026-06-24: raised to 334 after S1 FZ, GHZ, and SLZ boss graph parent
 *       restores moved to generic recreate and joined the standalone pass set.</li>
 *   <li>2026-06-24: raised to 335 after AIZ miniboss parent graph restore moved
 *       to generic recreate and joined the standalone pass set.</li>
 *   <li>2026-06-24: raised to 336 after AIZ intro parent graph restore moved
 *       to generic recreate and joined the standalone pass set.</li>
 *   <li>2026-06-24: raised to 337 after AIZ spiked-log parent graph restore moved
 *       to generic recreate and joined the standalone pass set.</li>
 *   <li>2026-06-24: raised to 338 after AIZ falling-log parent restore moved
 *       to generic recreate and joined the standalone pass set.</li>
 *   <li>2026-06-24: raised to 339 after AIZ disappearing-floor parent restore moved
 *       to generic recreate under border-child graph coverage.</li>
 *   <li>2026-06-24: raised to 341 after AIZ collapsing-log bridge parent and
 *       segment restores moved to generic recreate under graph coverage.</li>
 *   <li>2026-06-24: raised to 342 after AIZ flipping bridge restore moved
 *       to generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 344 after AIZ1 tree and zipline peg static
 *       scenery restores moved to generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 346 after AIZ foreground plant and animated
 *       still sprite restores moved to generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 348 after StillSprite and S3K spike restores
 *       moved to generic recreate under static hazard object-manager coverage.</li>
 *   <li>2026-06-24: raised to 350 after S3K button and path-swap marker restores
 *       moved to generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 353 after S3K hidden monitor, sinking mud, and
 *       SS-entry ring restores moved to generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 357 after S3K CNZ balloon, rising platform,
 *       light bulb, and barber pole restores moved to generic recreate under
 *       object-manager coverage.</li>
 *   <li>2026-06-24: raised to 365 after S3K CNZ giant wheel, hover fan,
 *       spiral tube, teleporter beam, trap door, triangle bumper, vacuum tube,
 *       and water-level button restores moved to generic recreate under
 *       object-manager coverage.</li>
 *   <li>2026-06-24: raised to 369 after S3K ICZ breakable wall,
 *       harmful ice, ice block, and ice cube restores moved to generic
 *       recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 374 after S3K HCZ block, conveyor spike,
 *       large fan, spinning column, and water splash restores moved to
 *       generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 378 after S3K ICZ path-follow platform,
 *       swinging platform, stalagtite, and snow pile restores moved to
 *       generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 382 after S3K automatic tunnel, auto-spin,
 *       bubbler, and door restores moved to generic recreate under
 *       object-manager coverage.</li>
 *   <li>2026-06-24: raised to 388 after S3K floating platform, gumball triangle
 *       bumper, and pachinko standalone restores moved to generic recreate under
 *       object-manager coverage.</li>
 *   <li>2026-06-24: raised to 393 after S3K twisted ramp, updraft,
 *       HCZ/MGZ twisting loop, and MHZ twisted vine controller restores moved
 *       to generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 400 after S2 MTZ nut plus S3K LBZ
 *       barrier/alarm, HCZ water-drop, MHZ pollen, and MGZ post-boss
 *       controller restores moved to generic recreate under object-manager
 *       coverage.</li>
 *   <li>2026-06-24: raised to 401 after the S1 false-floor falling fragment
 *       moved to generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 402 after the S2 Moving Vine restore moved to
 *       generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 403 after the S2 Point Pokey restore moved to
 *       generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 404 after the shared placeholder object fallback
 *       moved to generic recreate under object-manager coverage.</li>
 *   <li>2026-06-24: raised to 405 after the S2 Speed Launcher moved to
 *       spawn/name-based generic recreate.</li>
 *   <li>2026-06-24: raised to 406 after the S2 MTZ Spin Tube moved to
 *       spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 407 after the S2 MTZ Long Platform moved to
 *       spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 408 after the S2 collapsing-platform parent moved
 *       to spawn/name-based generic recreate.</li>
 *   <li>2026-06-24: raised to 409 after the S1 junction display child moved
 *       to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 414 after S3K Knuckles cutscene controllers
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 421 after standalone S3K cutscene controllers
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 424 after S1 false-floor, LZ boss, and MZ boss
 *       controllers moved to generic recreate.</li>
 *   <li>2026-06-24: raised to 428 after S3K transition floor, CNZ capsule,
 *       HCZ2 wall, and ICZ palette controllers moved to generic recreate.</li>
 *   <li>2026-06-24: raised to 430 after S2 CNZ and MCZ boss controllers moved
 *       to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 435 after five S3K standalone badnik parents moved
 *       to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 441 after six more S3K standalone badnik parents
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 449 after eight more S3K standalone badnik parents
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 455 after six S3K LBZ/MGZ object parents moved
 *       to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 463 after eight S3K HCZ/LBZ/MGZ object parents
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 471 after eight S3K AIZ/HCZ/MGZ object parents
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 476 after five S3K ICZ/static object parents
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 481 after five S3K ICZ debris children
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 484 after three S3K dynamic child effects
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 487 after three S3K debris particle children
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 490 after three S3K HCZ water-wall children
 *       moved to spawn-based generic recreate.</li>
 *   <li>2026-06-24: raised to 493 after three S3K particle child effects
 *       moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 496 after three S3K Turbo Spiker particles
 *       moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 497 after the S3K MHZ miniboss tree chip
 *       moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 498 after the S3K MHZ2 leaf particle
 *       moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 499 after the S3K ICZ freezer frost puff
 *       moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 500 after the S3K AIZ draw-bridge falling segment
 *       moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 502 after the S3K Sparkle warning/projectile
 *       children moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 504 after the S3K MHZ horizontal/vertical swing
 *       bars moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 507 after the S3K MHZ curled vine, mushroom cap,
 *       and mushroom platform moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 511 after S3K wire cage, gumball item, MHZ
 *       mushroom catapult, and S3K springs moved to spawn-encoded generic
 *       recreate.</li>
 *   <li>2026-06-24: raised to 513 after the S3K LBZ miniboss box controllers
 *       moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 516 after MHZ tree/ship mechanisms and MGZ
 *       end-boss debris moved to spawn-encoded generic recreate.</li>
 *   <li>2026-06-24: raised to 517 after S1 MZ glass blocks moved to
 *       spawn-based generic recreate with graph-tested reflection relinking.</li>
 *   <li>2026-06-24: raised to 518 after S1 SBZ junction parents moved to
 *       spawn-based generic recreate with graph-tested display-child relinking.</li>
 *   <li>2026-06-24: raised to 519 after S1 MZ lava wall main/trail restore
 *       moved to graph-tested generic recreate.</li>
 *   <li>2026-06-24: raised to 521 after S1 MZ lava geyser maker/head/body/
 *       third-piece restore moved to graph-tested generic recreate.</li>
 *   <li>2026-06-24: raised to 523 after S1 breakable-wall and smash-block
 *       fragments moved to graph-tested generic recreate.</li>
 *   <li>2026-06-24: raised to 524 after S1 spiked-ball chain children moved
 *       to graph-tested generic recreate.</li>
 *   <li>2026-06-24: raised to 526 after S1 collapsing floor/ledge fragments
 *       gained spawn-based generic recreate coverage.</li>
 *   <li>2026-06-24: raised to 527 after S1 Egg Prison buttons gained
 *       graph-tested generic recreate coverage with parent relinking.</li>
 *   <li>2026-06-24: raised to 528 after S2 Breakable Plating gained generic
 *       recreate coverage and removed stale final-scalar/player-cache gaps.</li>
 *   <li>2026-06-24: raised to 529 after S2 Rivets gained generic recreate
 *       coverage and removed their stale player-cache/recreate gaps.</li>
 *   <li>2026-06-24: raised to 530 after S2 MCZ rotating platforms gained
 *       graph-tested generic recreate coverage with child-list relinking.</li>
 *   <li>2026-06-24: raised to 531 after S2 Sideways platform pairs gained
 *       graph-tested generic recreate coverage with sibling relinking.</li>
    *   <li>2026-06-24: raised to 532 after S2 Falling Pillars gained
    *       graph-tested generic recreate coverage with child relinking.</li>
    *   <li>2026-06-24: raised to 533 after S2 Swinging Platforms gained
    *       graph-tested generic recreate coverage with display-child relinking.</li>
    *   <li>2026-06-24: raised to 534 after S2 Cogs gained graph-tested generic
    *       recreate coverage with slot-child relinking.</li>
    *   <li>2026-06-24: raised to 535 after S2 breakable-block fragments gained
    *       graph-tested generic recreate coverage.</li>
    *   <li>2026-06-24: raised to 536 after S2 CPZ spin tubes gained generic
    *       recreate coverage.</li>
    *   <li>2026-06-24: raised to 537 after S2 collapsing-platform fragments
    *       gained graph-tested generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 541 after S3K LBZ tube/cup elevator,
    *       HCZ hand-launcher, and HCZ water-rush graph restores gained
    *       generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 543 after S3K HCZ/CGZ fan parent
    *       and bubble restores gained generic recreate coverage under the
    *       fan graph proof.</li>
    *   <li>2026-06-25: raised to 544 after S3K MGZ pulley parent restore
    *       gained generic recreate coverage under the pulley-chain graph proof.</li>
    *   <li>2026-06-25: raised to 572 after S2 launcher objects gained
    *       graph-tested generic recreate coverage with player-state map relinking.</li>
    *   <li>2026-06-25: raised to 573 after S2 flippers gained graph-tested
    *       generic recreate coverage with player-state map relinking.</li>
    *   <li>2026-06-25: raised to 574 after S2 spirals gained graph-tested
    *       generic recreate coverage with rider/cylinder player-link relinking.</li>
    *   <li>2026-06-25: raised to 575 after the S3K AIZ draw-bridge parent
    *       gained generic spawn recreate coverage.</li>
    *   <li>2026-06-25: raised to 576 after the S3K AIZ/LRZ rock gained
    *       generic spawn recreate coverage.</li>
    *   <li>2026-06-25: raised to 578 after S3K Dragonfly parent and wing
    *       child graph restores gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 580 after S3K Spiker parent and side-launcher
    *       graph restores gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 582 after S3K Turbo Spiker parent and
    *       waterfall-overlay graph restores gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 583 after S3K MegaChopper gained generic
    *       spawn recreate coverage.</li>
    *   <li>2026-06-25: raised to 584 after S3K Cluckoid arrow gained
    *       generic graph recreate coverage.</li>
    *   <li>2026-06-25: raised to 586 after S3K Mushmeanie parent/shell
    *       graph restores gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 588 after S3K Mantis parent/child graph
    *       restores gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 589 after S3K Ribot visual children gained
    *       generic graph recreate coverage.</li>
    *   <li>2026-06-25: raised to 592 after S3K SnaleBlaster parent, cover,
    *       and shooter graph restores gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 593 after S3K Caterkiller Jr head/body graph
    *       restores gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 596 after S3K Tunnelbot parent/arm/debris graph
    *       restores gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 599 after S3K Collapsing Bridge root,
    *       wave fragment, and MGZ stomp debris gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 601 after S3K Tension Bridge root and
    *       falling fragment gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 604 after S3K Breakable Wall, Cork Floor,
    *       and Collapsing Platform fragments gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 609 after S3K Gumball Machine root,
    *       dispenser, ejection effect, exit trigger, and platform children
    *       gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 611 after S3K Pachinko Energy Trap beam
    *       and column children gained generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 614 after S3K ICZ ice-spike root,
    *       tension-platform support, and crushing-column decoration gained
    *       generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 616 after S3K ICZ snowdust particles
    *       gained parent-seeded generic recreate coverage and the ICZ
    *       snowboard intro gained spawn-based recreate coverage.</li>
    *   <li>2026-06-25: raised to 617 after shared BoxObjectInstance
    *       gained exact-class generic recreate coverage.</li>
    *   <li>2026-06-25: raised to 618 after S3K LBZ Trigger Bridge
    *       gained spawn-based generic recreate coverage.</li>
   *   <li>2026-06-25: raised to 619 after S3K CNZ Teleporter
   *       gained graph-backed generic recreate coverage.</li>
   *   <li>2026-06-25: raised to 620 after S3K AIZ Emerald Scatter
   *       gained spawn-based generic recreate coverage.</li>
   *   <li>2026-06-25: raised to 622 after S2 rising-pillar and
   *       smashable-ground debris fragments gained spawn-encoded generic
   *       recreate coverage.</li>
   *   <li>2026-06-25: raised to 624 after shared breathing-bubble and
   *       water-splash effects gained spawn-encoded generic recreate
   *       coverage.</li>
   *   <li>2026-06-25: raised to 627 after S3K MGZ top launcher/platform
   *       and Pachinko item orb objects gained generic recreate coverage.</li>
   *   <li>2026-06-25: raised to 632 after S3K miniboss roots gained
   *       spawn-based generic recreate coverage.</li>
   *   <li>2026-06-25: raised to 635 after MGZ miniboss debris, defeat
   *       fragments, and camera scroll helper gained generic recreate coverage.</li>
   *   <li>2026-06-25: raised to 639 after HCZ vortex bubbles, ICZ defeat
   *       debris, and MHZ end-boss controllers gained graph-backed generic
   *       recreate coverage.</li>
   *   <li>2026-06-25: raised to 642 after the final S1 try-again and S2
   *       Super Sonic Stars recreate baseline gaps moved onto generic recreate.</li>
   *   <li>2026-06-26: raised to 643 after the S2 WFZ boss root moved onto
   *       spawn-construction-context generic recreate.</li>
   *   <li>2026-06-27: raised to 646 after S1 swinging-platform chain-link
   *       children gained spawn-based generic recreate coverage.</li>
 * </ul>
     *
     * <p>Floor only moves UP. When raising: update this comment, run the full
     * gate suite, confirm probed count >= new floor before committing.
     */
    // 2026-06-27: raised to 646 after S1 swing-chain children gained generic recreate.
    static final int RATCHET_FLOOR = 646;

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    /**
     * Sweeps all spawnable object classes, counts the Passed results, and
     * asserts the count is at or above the ratchet floor.
     *
     * <p>Also prints a bucket summary (no-codec / no-probe-ctor / parent-dependent /
     * other / passed) so the honest ceiling is visible without needing a separate
     * analysis run.
     */
    @Test
    void probedCountMeetsRatchetFloor() {
        Path srcMain = ObjectClasspathScan.findSourceRoot();
        if (srcMain == null) {
            System.out.println(
                    "[ratchet] Not in source checkout — skipping coverage ratchet check.");
            return;
        }

        List<ObjectClasspathScan.SourceClass> classes;
        try {
            classes = ObjectClasspathScan.findConcreteObjectInstances(srcMain);
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan object classes", e);
        }

        int total = classes.size();
        int passed = 0;
        int graphCovered = 0;
        int noCodec = 0;
        int noProbeCtor = 0;
        int parentDependent = 0;
        int otherFailure = 0;
        int countMismatch = 0;
        int scalarMismatch = 0;
        List<String> passedNames = new ArrayList<>();
        List<String> noProbCtorNames = new ArrayList<>();
        List<String> parentDependentNames = new ArrayList<>();

        for (ObjectClasspathScan.SourceClass sc : classes) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(sc.fqn());
            switch (result) {
                case RoundTripSweepResult.Passed p -> {
                    passed++;
                    passedNames.add(sc.fqn());
                }
                case RoundTripSweepResult.GraphCovered g -> graphCovered++;
                case RoundTripSweepResult.Unprobed u -> {
                    String reason = u.reason();
                    if (reason.startsWith("no dynamic recreate path")) {
                        noCodec++;
                    } else if (reason.startsWith("parent-dependent")) {
                        parentDependent++;
                        parentDependentNames.add(sc.fqn() + ": " + reason);
                    } else if (reason.contains("NoSuchMethodError")
                            || reason.contains("No probe-compatible constructor")) {
                        noProbeCtor++;
                        noProbCtorNames.add(sc.fqn());
                    } else {
                        otherFailure++;
                    }
                }
                case RoundTripSweepResult.CountMismatch cm -> countMismatch++;
                case RoundTripSweepResult.ScalarMismatch sm -> scalarMismatch++;
            }
        }

        System.out.println("[ratchet] total=" + total
                + " passed=" + passed
                + " graph-covered=" + graphCovered
                + " no-codec=" + noCodec
                + " no-probe-ctor=" + noProbeCtor
                + " parent-dependent=" + parentDependent
                + " other-failure=" + otherFailure
                + " count-mismatch=" + countMismatch
                + " scalar-mismatch=" + scalarMismatch);

        if (!passedNames.isEmpty()) {
            System.out.println("[ratchet] passed classes (" + passedNames.size() + "):");
            passedNames.stream().sorted().forEach(n -> System.out.println("  + " + n));
        }

        if (!noProbCtorNames.isEmpty()) {
            System.out.println("[ratchet] no-probe-ctor classes (sample, first 20):");
            noProbCtorNames.stream().limit(20).forEach(n -> System.out.println("  ? " + n));
        }

        if (!parentDependentNames.isEmpty()) {
            System.out.println("[ratchet] parent-dependent classes (" + parentDependentNames.size() + "):");
            parentDependentNames.forEach(n -> System.out.println("  P " + n));
        }

        assertTrue(passed >= RATCHET_FLOOR,
                "Probed (Passed) count " + passed + " is below ratchet floor " + RATCHET_FLOOR
                        + ". Coverage regressed — check for removed codecs or harness construction"
                        + " strategy changes. Raise RATCHET_FLOOR only by improving coverage, never"
                        + " to paper over a regression. Floor = " + RATCHET_FLOOR + ".");
    }
}
