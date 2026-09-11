# Rewind v1.6 Unsupported Field Inventory

This inventory records fields that must not be silently skipped by the generic capturer. `@RewindTransient` means the field is structural/runtime-owned/derived and can be restored from the live graph. `@RewindDeferred` means the field is synchronization-relevant or stateful, but generic v1.6 does not yet have the required identity snapshot or value codec.

| Class | Field | Type | Decision |
|---|---|---|---|
| com.openggf.level.objects.AbstractObjectInstance | dynamicSpawn | ObjectSpawn | annotate transient; captured explicitly as dynamicSpawnX/Y |
| com.openggf.level.objects.boss.AbstractBossInstance | dynamicSpawn | ObjectSpawn | defer class on legacy extra until boss dynamic-spawn coordinate snapshot exists |
| com.openggf.level.objects.boss.AbstractBossChild | dynamicSpawn | ObjectSpawn | defer class on legacy extra until boss-child dynamic-spawn coordinate snapshot exists |
| com.openggf.level.objects.AbstractMonitorObjectInstance | effectTarget | PlayableEntity | explicit snapshot field for player identity |
| com.openggf.game.sonic1.objects.bosses.Sonic1BossBlockInstance | grabbingBoss | Sonic1SYZBossInstance | explicit snapshot field for object identity |
| com.openggf.game.sonic1.objects.bosses.Sonic1SYZBossInstance | grabbedBlock | Sonic1BossBlockInstance | explicit snapshot field for object identity |
| com.openggf.game.sonic1.objects.Sonic1EggPrisonObjectInstance | lastPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic1.objects.Sonic1TeleporterObjectInstance | controlledPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic2.objects.badniks.GrabberBadnikInstance | grabbedPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic2.objects.FlipperObjectInstance | lockedPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic2.objects.SpringboardObjectInstance | launchPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.AbstractS3kFloatingEndEggCapsuleInstance | explosionController | S3kBossExplosionController | value codec for explosion controller queued state |
| com.openggf.game.sonic3k.objects.AizEndBossInstance | defeatExplosionController | S3kBossExplosionController | value codec for explosion controller queued state |
| com.openggf.game.sonic3k.objects.AizMinibossCutsceneInstance | explosionController | S3kBossExplosionController | value codec for explosion controller queued state |
| com.openggf.game.sonic3k.objects.AizMinibossInstance | defeatExplosionController | S3kBossExplosionController | value codec for explosion controller queued state |
| com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleInstance | explosionController | S3kBossExplosionController | value codec for explosion controller queued state |
| com.openggf.game.sonic3k.objects.bosses.HczEndBossInstance | defeatExplosionController | S3kBossExplosionController | value codec for explosion controller queued state |
| com.openggf.game.sonic3k.objects.HczMinibossInstance | defeatExplosionController | S3kBossExplosionController | value codec for explosion controller queued state |
| com.openggf.game.sonic3k.objects.MgzDrillingRobotnikInstance | endBossDefeatExplosionController | S3kBossExplosionController | value codec for explosion controller queued state |
| com.openggf.game.sonic3k.objects.MgzMinibossInstance | defeatExplosionController | S3kBossExplosionController | value codec for explosion controller queued state |
| com.openggf.game.sonic3k.objects.badniks.MegaChopperBadnikInstance | pendingMainPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.badniks.MegaChopperBadnikInstance | pendingSidekickPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.badniks.MegaChopperBadnikInstance | capturedPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance | pendingLaunchPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.CnzBumperObjectInstance | pendingPrimaryTouch | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.CnzBumperObjectInstance | pendingSidekickTouch | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.CnzCannonInstance | capturedPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.CorkFloorObjectInstance | rollingBreakPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.MGZPulleyObjectInstance | grabbedPlayers | AbstractPlayableSprite[] | explicit per-slot snapshot fields for player identity |
| com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance | capturedPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.PachinkoFlipperObjectInstance | lockedPlayer | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.PachinkoItemOrbObjectInstance | rewardItem | GumballItemObjectInstance | explicit snapshot field for object identity |
| com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance | playerRef | AbstractPlayableSprite | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.Sonic3kInvincibilityStarsObjectInstance | player | PlayableEntity | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance | p1SolidContact | PlayableEntity | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance | p2SolidContact | PlayableEntity | explicit snapshot field for player identity |
| com.openggf.game.sonic3k.objects.TensionBridgeObjectInstance | playerAtCollapse | PlayableEntity | explicit snapshot field for player identity |
| com.openggf.sprites.playable.AbstractPlayableSprite | mgzTopPlatformCarrySolidContactObject | ObjectInstance | explicit snapshot field for object identity |
| com.openggf.sprites.playable.AbstractPlayableSprite | latchedSolidObjectInstance | ObjectInstance | explicit snapshot field for object identity |

Synthetic compiler fields, such as non-static inner-class outer references, are skipped by the guard.
