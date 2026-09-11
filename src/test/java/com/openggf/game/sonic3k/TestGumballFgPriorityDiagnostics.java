package com.openggf.game.sonic3k;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.graphics.SpriteSatEntry;
import com.openggf.graphics.SpriteSatMaskPostProcessor;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectManager;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestGumballFgPriorityDiagnostics {

    private static final int MACHINE_OBJECT_ID = 0x86;
    private static final int MACHINE_Y_OFFSET = -0x100;
    private static final int BODY_FRAME_Y_OFFSET = -0x28;

    private LevelManager levelManager;
    private String mainCharacter;
    private Object oldSkipIntros;
    private Sonic sprite;

    @BeforeEach
    void setUp() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        mainCharacter = config.getString(SonicConfiguration.MAIN_CHARACTER_CODE);

        GraphicsManager.getInstance().initHeadless();
        levelManager = GameServices.level();

        sprite = new Sonic(mainCharacter, (short) 0x100, (short) 0x100);
        GameServices.sprites().addSprite(sprite);
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sprite);
        camera.setFrozen(false);

        levelManager.loadZoneAndAct(Sonic3kZoneIds.ZONE_GUMBALL, 0);
        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);
    }

    @Test
    void dumpForegroundPriorityAroundMachineBody() throws Exception {
        Level level = levelManager.getCurrentLevel();
        assertNotNull(level, "Gumball bonus stage should be loaded");

        ObjectSpawn machineSpawn = level.getObjects().stream()
                .filter(spawn -> spawn.objectId() == MACHINE_OBJECT_ID)
                .findFirst()
                .orElse(null);
        assertNotNull(machineSpawn, "Machine spawn should exist in the gumball stage");

        int bodyOriginX = machineSpawn.x();
        int bodyOriginY = machineSpawn.y() + MACHINE_Y_OFFSET + BODY_FRAME_Y_OFFSET;

        System.out.println("=== Gumball FG Priority Diagnostic ===");
        System.out.printf("Machine spawn=(0x%04X,0x%04X) bodyOrigin=(0x%04X,0x%04X)%n",
                machineSpawn.x(), machineSpawn.y(), bodyOriginX, bodyOriginY);

        int highCount = 0;
        int lowCount = 0;
        int emptyCount = 0;

        for (int row = 0; row < 14; row++) {
            int sampleY = bodyOriginY + (row * 8);
            StringBuilder line = new StringBuilder();
            line.append(String.format("y=0x%04X ", sampleY));
            for (int col = -4; col <= 4; col++) {
                int sampleX = bodyOriginX + (col * 8);
                int desc = levelManager.getForegroundTileDescriptorAtWorld(sampleX, sampleY);
                int pattern = desc & 0x7FF;
                boolean high = (desc & 0x8000) != 0;
                if (pattern == 0) {
                    emptyCount++;
                    line.append(" .   ");
                    continue;
                }
                if (high) {
                    highCount++;
                } else {
                    lowCount++;
                }
                line.append(String.format("%c%03X ", high ? 'H' : 'L', pattern));
            }
            System.out.println(line);
        }

        int lowerBandHigh = 0;
        int lowerBandLow = 0;
        for (int sampleY = bodyOriginY + 0x40; sampleY <= bodyOriginY + 0x68; sampleY += 8) {
            for (int sampleX = bodyOriginX - 24; sampleX <= bodyOriginX + 24; sampleX += 8) {
                int desc = levelManager.getForegroundTileDescriptorAtWorld(sampleX, sampleY);
                int pattern = desc & 0x7FF;
                if (pattern == 0) {
                    continue;
                }
                if ((desc & 0x8000) != 0) {
                    lowerBandHigh++;
                } else {
                    lowerBandLow++;
                }
            }
        }

        System.out.printf("Counts: high=%d low=%d empty=%d%n", highCount, lowCount, emptyCount);
        System.out.printf("Lower band (body underside) counts: high=%d low=%d%n", lowerBandHigh, lowerBandLow);

        System.out.println("Visible object spawns near machine:");
        level.getObjects().stream()
                .filter(spawn -> Math.abs(spawn.x() - bodyOriginX) <= 0x80)
                .filter(spawn -> Math.abs((spawn.y() + MACHINE_Y_OFFSET) - bodyOriginY) <= 0x120
                        || Math.abs(spawn.y() - machineSpawn.y()) <= 0x120)
                .forEach(spawn -> System.out.printf(
                        "  id=0x%02X x=0x%04X y=0x%04X subtype=0x%02X%n",
                        spawn.objectId(), spawn.x(), spawn.y(), spawn.subtype()));

        dumpFrame16Geometry(bodyOriginX, bodyOriginY);
        dumpStartupSatEntries(bodyOriginX, bodyOriginY);

        assertTrue(highCount + lowCount > 0, "Diagnostic sample should hit foreground tiles");
    }

    private void dumpFrame16Geometry(int bodyOriginX, int bodyOriginY) throws Exception {
        RomByteReader reader = RomByteReader.fromRom(GameServices.rom().getRom());
        assertNotNull(reader, "ROM reader should be available");

        SpriteMappingFrame frame16 = S3kSpriteDataLoader
                .loadMappingFrames(reader, Sonic3kConstants.GUMBALL_MAP_ADDR)
                .get(22);

        System.out.println("Frame 0x16 mapping pieces:");
        for (SpriteMappingPiece piece : frame16.pieces()) {
            int minX = bodyOriginX + piece.xOffset();
            int minY = bodyOriginY + piece.yOffset();
            int maxX = minX + (piece.widthTiles() * 8) - 1;
            int maxY = minY + (piece.heightTiles() * 8) - 1;
            System.out.printf(
                    "  pri=%s tile=0x%03X pos=[0x%04X..0x%04X, 0x%04X..0x%04X] size=%dx%d%n",
                    piece.priority() ? "HIGH" : "LOW",
                    piece.tileIndex(),
                    minX, maxX, minY, maxY,
                    piece.widthTiles() * 8,
                    piece.heightTiles() * 8);
        }
    }

    @SuppressWarnings("unchecked")
    private void dumpStartupSatEntries(int bodyOriginX, int bodyOriginY) throws Exception {
        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        SpriteManager spriteManager = GameServices.sprites();
        ObjectManager objectManager = levelManager.getObjectManager();
        assertNotNull(objectManager, "Object manager should exist");

        objectManager.update(GameServices.camera().getX(), sprite, List.of(), 0, false);

        graphicsManager.setUseSpritePriorityShader(true);
        graphicsManager.setCurrentSpriteHighPriority(false);
        graphicsManager.beginPatternBatch();
        graphicsManager.beginSpriteSatCollection();
        for (int bucket = RenderPriority.MIN; bucket <= RenderPriority.MAX; bucket++) {
            spriteManager.drawUnifiedBucketWithPriority(bucket, graphicsManager);
            objectManager.drawUnifiedBucketWithPriority(bucket, graphicsManager);
        }

        Field entriesField = GraphicsManager.class.getDeclaredField("spriteSatEntries");
        entriesField.setAccessible(true);
        List<SpriteSatEntry> collected =
                new ArrayList<>((List<SpriteSatEntry>) entriesField.get(graphicsManager));

        Field maskField = GraphicsManager.class.getDeclaredField("spriteMaskRequested");
        maskField.setAccessible(true);
        boolean maskRequested = maskField.getBoolean(graphicsManager);

        graphicsManager.endSpriteSatCollectionAndReplay();

        List<SpriteSatEntry> processed = SpriteSatMaskPostProcessor.process(collected, maskRequested);
        int minX = bodyOriginX - 0x50;
        int maxX = bodyOriginX + 0x50;
        int minY = bodyOriginY - 0x20;
        int maxY = bodyOriginY + 0x80;

        System.out.printf("SAT diagnostic: collected=%d processed=%d mask=%s%n",
                collected.size(), processed.size(), maskRequested);
        dumpRelevantEntries("Collected", collected, minX, maxX, minY, maxY);
        dumpRelevantEntries("Processed", processed, minX, maxX, minY, maxY);

        assertTrue(maskRequested, "Gumball startup scene should request a sprite mask");
        assertTrue(collected.stream().anyMatch(entry -> entry.rawTileWordLow11() == 0x7C0),
                "Collected SAT entries should include the 0x7C0 mask helper");
    }

    private void dumpRelevantEntries(String label, List<SpriteSatEntry> entries,
            int minX, int maxX, int minY, int maxY) {
        System.out.println(label + " SAT entries near machine:");
        for (int i = 0; i < entries.size(); i++) {
            SpriteSatEntry entry = entries.get(i);
            if (entry.endXExclusive() <= minX || entry.x() >= maxX
                    || entry.endYExclusive() <= minY || entry.y() >= maxY) {
                continue;
            }
            System.out.printf(
                    "  %s[%02d] raw=0x%03X tile=0x%03X pri=%s glob=%s x=[0x%04X..0x%04X] y=[0x%04X..0x%04X] rows=%d+%d src=%s%n",
                    label.charAt(0) == 'C' ? "C" : "P",
                    i,
                    entry.rawTileWordLow11(),
                    entry.firstPatternIndex(),
                    entry.piecePriority(),
                    entry.globalHighPriority(),
                    entry.x(),
                    entry.endXExclusive() - 1,
                    entry.y(),
                    entry.endYExclusive() - 1,
                    entry.startRowTile(),
                    entry.rowCountTiles(),
                    entry.debugSource());
        }
    }
}


