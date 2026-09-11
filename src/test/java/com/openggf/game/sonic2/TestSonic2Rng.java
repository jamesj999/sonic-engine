package com.openggf.game.sonic2;

import com.openggf.game.GameRng;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestSonic2Rng {
    @Test
    void bubbleBurstChoiceReusesOneRandomNumberForCountAndSequence() {
        GameRng control = s2Rng(0x12345678L);
        int raw;
        do {
            raw = control.nextRaw();
        } while ((raw & 7) >= 6);

        GameRng actual = s2Rng(0x12345678L);
        Sonic2Rng.BubbleBurstChoice choice = Sonic2Rng.nextBubbleBurstChoice(actual);

        assertEquals(raw & 7, choice.bubbleCount());
        assertEquals(raw & 0x0C, choice.sequenceOffset());
        assertEquals(control.getSeed(), actual.getSeed());
    }

    @Test
    void leafSpawnReusesOneRandomNumberForOffsetsFrameAndAngle() {
        GameRng control = s2Rng(0x01020304L);
        int raw = control.nextRaw();
        int offsetY = ((raw >>> 16) & 0x0F) - 8;

        GameRng actual = s2Rng(0x01020304L);
        Sonic2Rng.LeafSpawn leaf = Sonic2Rng.nextLeafSpawn(actual);

        assertEquals((raw & 0x0F) - 8, leaf.offsetX());
        assertEquals(offsetY, leaf.offsetY());
        assertEquals(offsetY & 1, leaf.mappingFrame());
        assertEquals((int) (control.getSeed() & 0xFF), leaf.angle());
        assertEquals(control.getSeed(), actual.getSeed());
    }

    @Test
    void whispAndTornadoReadUpdatedSeedAfterBurningOneRngStep() {
        GameRng control = s2Rng(0x2468ACE0L);
        control.nextRaw();
        int expectedLowSeedBits = (int) control.getSeed();

        GameRng whisp = s2Rng(0x2468ACE0L);
        assertEquals(expectedLowSeedBits & 0x1F, Sonic2Rng.nextWhispPauseTimer(whisp));
        assertEquals(control.getSeed(), whisp.getSeed());

        GameRng tornado = s2Rng(0x2468ACE0L);
        assertEquals(expectedLowSeedBits & 0x1C, Sonic2Rng.nextTornadoSmokeOffset(tornado));
        assertEquals(control.getSeed(), tornado.getSeed());
    }

    @Test
    void cpzGunkDropletUsesOneRandomNumberForXAndYVelocity() {
        GameRng control = s2Rng(0x0BADF00DL);
        int raw = control.nextRaw();
        int expectedX = ((short) raw) >> 6;
        if (expectedX >= 0) {
            expectedX += 0x80;
        }
        expectedX -= 0x80;
        int expectedY = 0x180 - ((raw >>> 16) & 0x3FF);

        GameRng actual = s2Rng(0x0BADF00DL);
        Sonic2Rng.GunkDropletVelocity velocity = Sonic2Rng.nextCpzGunkDropletVelocity(actual, 0x180);

        assertEquals(expectedX, velocity.xVel());
        assertEquals(expectedY, velocity.yVel());
        assertEquals(control.getSeed(), actual.getSeed());
    }

    @Test
    void cpzPipeShardMotionUsesOneRandomNumberForVelocityAndTimer() {
        GameRng control = s2Rng(0x00C0FFEE);
        int raw = control.nextRaw();

        GameRng actual = s2Rng(0x00C0FFEE);
        Sonic2Rng.PipeShardMotion motion = Sonic2Rng.nextPipeShardMotion(actual);

        assertEquals(((short) raw) >> 14, motion.xVel());
        assertEquals((((raw >>> 16) & 0xFFFF) + 0x1E) & 0x7F, motion.timer());
        assertEquals(control.getSeed(), actual.getSeed());
    }

    @Test
    void endingCloudSpawnRotatesSeedRightByOneAndReusesRotatedBits() {
        GameRng rng = s2Rng(0x80000007L);
        int rotated = Integer.rotateRight(0x80000007, 1);

        Sonic2Rng.EndingCloudSpawn spawn = Sonic2Rng.nextEndingCloudSpawn(rng, false);

        assertEquals(rotated & 0x1FF, spawn.x());
        assertEquals(0x100, spawn.y());
        assertEquals(0, spawn.xVel());
        assertEquals(Sonic2Rng.ENDING_CLOUD_Y_VELS[rotated & 3], spawn.yVel());
        assertEquals(Sonic2Rng.ENDING_CLOUD_FRAMES[rotated & 3], spawn.frame());
        assertEquals(rotated & 0xFFFFFFFFL, rng.getSeed());
    }

    @Test
    void endingHorizontalCloudConvertsVerticalVelocityToXVelocity() {
        GameRng rng = s2Rng(0x00000005L);
        int rotated = Integer.rotateRight(0x00000005, 1);

        Sonic2Rng.EndingCloudSpawn spawn = Sonic2Rng.nextEndingCloudSpawn(rng, true);

        assertEquals(0x150, spawn.x());
        assertEquals(rotated & 0xFF, spawn.y());
        assertEquals(Sonic2Rng.ENDING_CLOUD_Y_VELS[rotated & 3], spawn.xVel());
        assertEquals(0, spawn.yVel());
        assertEquals(Sonic2Rng.ENDING_CLOUD_FRAMES[rotated & 3], spawn.frame());
        assertEquals(rotated & 0xFFFFFFFFL, rng.getSeed());
    }

    @Test
    void endingBirdSpawnRotatesSeedRightByThreeThenDerivesBothCoordinates() {
        GameRng rng = s2Rng(0x12345678L);
        int firstRotate = Integer.rotateRight(0x12345678, 3);
        int secondRotate = Integer.rotateRight(firstRotate, 3);
        int lowYBits = secondRotate & 0xFF;

        Sonic2Rng.EndingBirdSpawn spawn = Sonic2Rng.nextEndingBirdSpawn(rng);

        assertEquals(-0xA0 + (firstRotate & 0x7F), spawn.x());
        assertEquals(8 + lowYBits, spawn.y());
        assertEquals(0x100, spawn.xVel());
        assertEquals(lowYBits < 0x20 ? 0x20 : -0x20, spawn.yVel());
        assertEquals(firstRotate & 0xFFFFFFFFL, rng.getSeed());
    }

    @Test
    void sharedAnimalAndBossHelpersUseOneRomRandomNumber() {
        GameRng animalRng = s2Rng(0x13579BDFL);
        GameRng animalControl = s2Rng(0x13579BDFL);
        assertEquals(animalControl.nextRaw() & 1, Sonic2Rng.nextAnimalArtVariant(animalRng));
        assertEquals(animalControl.getSeed(), animalRng.getSeed());

        GameRng bossRng = s2Rng(0xCAFEBABEL);
        GameRng bossControl = s2Rng(0xCAFEBABEL);
        int raw = bossControl.nextWord();
        Sonic2Rng.BossExplosionOffset offset = Sonic2Rng.nextBossExplosionOffset(bossRng);

        assertEquals(((raw & 0xFF) >>> 2) - 0x20, offset.xOffset());
        assertEquals((((raw >>> 8) & 0xFF) >>> 2) - 0x20, offset.yOffset());
        assertEquals(bossControl.getSeed(), bossRng.getSeed());
    }

    @Test
    void auditedSonic2ObjectFilesDoNotUseJvmRandomSources() throws IOException {
        List<Path> auditedFiles = List.of(
                Path.of("src/main/java/com/openggf/game/sonic2/objects/BubbleGeneratorObjectInstance.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/LeavesGeneratorObjectInstance.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/EggPrisonObjectInstance.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/badniks/WhispBadnikInstance.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossContainer.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossGunk.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossFallingPart.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossPipe.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossPipeSegment.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossPump.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2ARZBossInstance.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MCZBossInstance.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java"),
                Path.of("src/main/java/com/openggf/level/objects/AnimalObjectInstance.java"),
                Path.of("src/main/java/com/openggf/level/objects/EggPrisonAnimalInstance.java"),
                Path.of("src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java")
        );

        for (Path path : auditedFiles) {
            String source = Files.readString(path);
            assertFalse(source.contains("java.util.Random"), path + " should use GameRng, not java.util.Random");
            assertFalse(source.contains("ThreadLocalRandom"), path + " should use GameRng, not ThreadLocalRandom");
        }
    }

    private static GameRng s2Rng(long seed) {
        return new GameRng(GameRng.Flavour.S1_S2, seed);
    }
}


