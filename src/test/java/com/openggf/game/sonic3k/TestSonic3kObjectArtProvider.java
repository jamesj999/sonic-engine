package com.openggf.game.sonic3k;

import com.openggf.graphics.GraphicsManager;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kPlcLoader.TileRange;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestSonic3kObjectArtProvider {

    private Sonic3kObjectArtProvider provider;
    private Method registerSheet;

    @BeforeEach
    public void setUp() throws Exception {
        GraphicsManager.getInstance().initHeadless();
        provider = new Sonic3kObjectArtProvider();
        registerSheet = Sonic3kObjectArtProvider.class.getDeclaredMethod(
                "registerSheet", String.class, ObjectSpriteSheet.class);
        registerSheet.setAccessible(true);
    }

    @AfterEach
    public void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    public void registerSheetReplacesExistingRendererOrderInsteadOfAppendingDuplicates() throws Exception {
        ObjectSpriteSheet first = buildSheet(2);
        ObjectSpriteSheet second = buildSheet(3);

        registerSheet.invoke(provider, "slot_test", first);
        registerSheet.invoke(provider, "slot_test", second);

        assertEquals(1, provider.getRendererKeys().size());
        assertEquals("slot_test", provider.getRendererKeys().get(0));
        assertSame(second, provider.getSheet("slot_test"));

        int next = provider.ensurePatternsCached(GraphicsManager.getInstance(), 0x20000);
        assertEquals(0x20000 + second.getPatterns().length, next);
    }

    @Test
    public void standaloneSharedArtSheetSlicesPatternsWhenMappingsStartInsideSourceArt() throws Exception {
        Pattern[] patterns = new Pattern[16];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = new Pattern();
        }
        List<SpriteMappingFrame> frames = List.of(
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(0, 0, 1, 1, 0x08, false, false, 0),
                        new SpriteMappingPiece(8, 0, 1, 1, 0x0A, false, false, 0)))
        );

        Method buildSheet = Sonic3kObjectArtProvider.class.getDeclaredMethod(
                "buildSheetFromPatterns", Pattern[].class, List.class, int.class);
        buildSheet.setAccessible(true);

        ObjectSpriteSheet sheet = (ObjectSpriteSheet) buildSheet.invoke(null, patterns, frames, 0);

        assertSame(patterns[8], sheet.getPatterns()[0], "Mappings that start at source tile $08 should render from pattern $08, not pattern 0");
        assertEquals(0, sheet.getFrame(0).pieces().get(0).tileIndex());
        assertEquals(2, sheet.getFrame(0).pieces().get(1).tileIndex());
    }

    @Test
    public void cnzTraversalSheetsAreRegisteredDuringCnzLoad() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        Sonic3kObjectArtProvider currentProvider =
                (Sonic3kObjectArtProvider) GameModuleRegistry.getCurrent().getObjectArtProvider();

        assertNotNull(currentProvider.getSheet("cnz_balloon"));
        assertNotNull(currentProvider.getSheet("cnz_cannon"));
    }

    @Test
    public void mgz2RegistersScaledEndBossCueAsGeneratedMapScaledArtBank() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MGZ, 1)
                .build();

        Sonic3kObjectArtProvider currentProvider =
                (Sonic3kObjectArtProvider) GameModuleRegistry.getCurrent().getObjectArtProvider();

        ObjectSpriteSheet sheet = currentProvider.getSheet(Sonic3kObjectArtKeys.MGZ_ENDBOSS_SCALED);
        assertNotNull(sheet, "MGZ2 loc_6CFF4 needs a generated ArtTile_MGZEndBossScaled bank");
        assertEquals(0x100, sheet.getPatterns().length,
                "Perform_Art_Scaling can DMA up to the 256-tile Map_ScaledArt frame");
        assertEquals(0x20, sheet.getFrameCount(),
                "loc_6CFF4 draws the generated art through Map_ScaledArt, indexed by $40(a0)");
        assertEquals(4, sheet.getFrame(4).pieces().size(),
                "Initial $40=4 uses Map_ScaledArt frame 4, not Map_MGZEndBoss source pieces");
        assertEquals(0x30, sheet.getFrame(4).pieces().get(3).tileIndex());
    }

    @Test
    public void mgz2RegistersEndBossDrillSheetWithRuntimeFrames() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MGZ, 1)
                .build();

        Sonic3kObjectArtProvider currentProvider =
                (Sonic3kObjectArtProvider) GameModuleRegistry.getCurrent().getObjectArtProvider();

        ObjectSpriteSheet sheet = currentProvider.getSheet(Sonic3kObjectArtKeys.MGZ_ENDBOSS);
        assertNotNull(sheet, "MGZ2 endboss drill craft should register Map_MGZEndBoss without range warnings");
        assertTrue(sheet.getFrameCount() > 0x30,
                "Obj_MGZEndBoss draws drill poses through $25 and defeat debris frames $2E-$30");
        assertFalse(sheet.getFrame(0x25).pieces().isEmpty(),
                "Drill flame pose $25 must remain drawable from the base MGZ endboss bank");
        assertFalse(sheet.getFrame(0x2E).pieces().isEmpty(),
                "Defeat debris frame $2E must remain drawable from Map_MGZEndBoss");
        assertFalse(sheet.getFrame(0x30).pieces().isEmpty(),
                "Defeat debris frame $30 must remain drawable from Map_MGZEndBoss");
    }

    @Test
    public void aiz2EndBossSheetExposesWaterfallMappingFramesFromRomArt() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_AIZ, 1)
                .build();

        Sonic3kObjectArtProvider currentProvider =
                (Sonic3kObjectArtProvider) GameModuleRegistry.getCurrent().getObjectArtProvider();
        ObjectSpriteSheet sheet = currentProvider.getSheet(Sonic3kObjectArtKeys.AIZ_END_BOSS);

        assertNotNull(sheet, "AIZ2 end-boss ROM art must register its standalone mapping sheet");
        assertTrue(sheet.getFrameCount() > 0x31,
                "Map_AIZEndBoss must expose the waterfall/drop frames through $31");
        assertTrue(sheet.getFrame(0x2B).pieces().isEmpty(),
                "AIZEndBossWaterfall frame $2B is the ROM's intentional invisible pose");
        for (int frame : List.of(0x24, 0x2C, 0x31)) {
            assertFalse(sheet.getFrame(frame).pieces().isEmpty(),
                    "AIZEndBossWaterfall frame $" + Integer.toHexString(frame)
                            + " must be present in the production ROM mapping sheet");
        }
    }

    @Test
    public void cnzTraversalSheetsParticipateInLevelArtRefreshTracking() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        Sonic3kObjectArtProvider currentProvider =
                (Sonic3kObjectArtProvider) GameModuleRegistry.getCurrent().getObjectArtProvider();

        Field field = Sonic3kObjectArtProvider.class.getDeclaredField("levelArtTileRanges");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, TileRange> ranges = (Map<String, TileRange>) field.get(currentProvider);

        assertTrue(ranges.containsKey(Sonic3kObjectArtKeys.CNZ_BALLOON));
        assertTrue(ranges.containsKey(Sonic3kObjectArtKeys.CNZ_CANNON));
        assertTrue(ranges.containsKey(Sonic3kObjectArtKeys.CNZ_RISING_PLATFORM));
        assertTrue(ranges.containsKey(Sonic3kObjectArtKeys.CNZ_TRAP_DOOR));
        assertTrue(ranges.containsKey(Sonic3kObjectArtKeys.CNZ_HOVER_FAN));
        assertTrue(ranges.containsKey(Sonic3kObjectArtKeys.CNZ_CYLINDER));
        assertFalse(ranges.containsKey(Sonic3kObjectArtKeys.CNZ_VACUUM_TUBE));
        assertFalse(ranges.containsKey(Sonic3kObjectArtKeys.CNZ_SPIRAL_TUBE));
    }

    @Test
    public void hczMinibossSheetIsRegisteredDuringHczLoad() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_HCZ, 0)
                .build();

        Sonic3kObjectArtProvider currentProvider =
                (Sonic3kObjectArtProvider) GameModuleRegistry.getCurrent().getObjectArtProvider();

        ObjectSpriteSheet sheet = currentProvider.getSheet(Sonic3kObjectArtKeys.HCZ_MINIBOSS);
        assertNotNull(sheet, "HCZ miniboss sheet should be registered after HCZ load");
        assertNotNull(sheet.getPatterns());
        assertTrue(sheet.getPatterns().length > 0, "HCZ miniboss sheet should have non-empty patterns");
        assertTrue(sheet.getFrameCount() > 0, "HCZ miniboss sheet should have non-empty frames");
    }

    @Test
    public void hczEndBossSheetIsRegisteredDuringHczLoad() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_HCZ, 0)
                .build();

        Sonic3kObjectArtProvider currentProvider =
                (Sonic3kObjectArtProvider) GameModuleRegistry.getCurrent().getObjectArtProvider();

        ObjectSpriteSheet sheet = currentProvider.getSheet(Sonic3kObjectArtKeys.HCZ_END_BOSS);
        assertNotNull(sheet, "HCZ end boss sheet should be registered after HCZ load");
        assertNotNull(sheet.getPatterns());
        assertTrue(sheet.getPatterns().length > 0, "HCZ end boss sheet should have non-empty patterns");
        assertTrue(sheet.getFrameCount() > 0, "HCZ end boss sheet should have non-empty frames");
    }

    @Test
    public void hczGeyserCutsceneSheetIsRegisteredDuringHczLoad() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_HCZ, 0)
                .build();

        Sonic3kObjectArtProvider currentProvider =
                (Sonic3kObjectArtProvider) GameModuleRegistry.getCurrent().getObjectArtProvider();

        ObjectSpriteSheet sheet = currentProvider.getSheet(Sonic3kObjectArtKeys.HCZ_GEYSER_CUTSCENE);
        assertNotNull(sheet, "HCZ geyser cutscene (waterwall) sheet should be registered after HCZ load");
        assertNotNull(sheet.getPatterns());
        assertTrue(sheet.getPatterns().length > 0, "HCZ geyser cutscene sheet should have non-empty patterns");
        assertTrue(sheet.getFrameCount() > 0, "HCZ geyser cutscene sheet should have non-empty frames");
    }

    @Test
    public void aiz2SmallRobotnikCraftUsesSourceTile86FromBombershipArt() throws Exception {
        Pattern[] patterns = new Pattern[176];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = new Pattern();
        }
        List<SpriteMappingFrame> frames = List.of(
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-16, -28, 4, 3, 0x86, false, false, 1),
                        new SpriteMappingPiece(-24, -12, 1, 1, 0x92, false, false, 1),
                        new SpriteMappingPiece(16, -12, 1, 1, 0x93, false, false, 1),
                        new SpriteMappingPiece(-32, -4, 4, 3, 0x94, false, false, 1),
                        new SpriteMappingPiece(0, -4, 4, 3, 0xA0, false, false, 1),
                        new SpriteMappingPiece(-16, 20, 4, 1, 0xAC, false, false, 1)))
        );

        ObjectSpriteSheet sheet = buildStandaloneSheet(patterns, frames);

        assertSame(patterns[0x86], sheet.getPatterns()[0], "AIZ2 small Robotnik craft should start at bombership source tile $86");
        assertEquals(0, sheet.getFrame(0).pieces().get(0).tileIndex());
        assertEquals(0x26, sheet.getFrame(0).pieces().get(5).tileIndex());
    }

    @Test
    void sameSheetContent_matchesPixelIdenticalSheetsAndRejectsChangedPixels() {
        ObjectSpriteSheet original = buildSheet(3);
        ObjectSpriteSheet identical = buildSheet(3);
        assertTrue(Sonic3kObjectArtProvider.sameSheetContent(original, identical),
                "distinct Pattern objects with equal pixels are the same GPU content");

        ObjectSpriteSheet changed = buildSheet(3);
        changed.getPatterns()[1].setPixel(2, 3, (byte) 7);
        assertFalse(Sonic3kObjectArtProvider.sameSheetContent(original, changed));

        assertFalse(Sonic3kObjectArtProvider.sameSheetContent(original, buildSheet(4)),
                "different pattern counts are never equivalent");
    }

    private static ObjectSpriteSheet buildSheet(int patternCount) {
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
        }
        List<SpriteMappingFrame> frames = List.of(
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0)))
        );
        return new ObjectSpriteSheet(patterns, frames, 0, 1);
    }

    private static ObjectSpriteSheet buildStandaloneSheet(Pattern[] patterns,
                                                          List<SpriteMappingFrame> frames) throws Exception {
        Method buildSheet = Sonic3kObjectArtProvider.class.getDeclaredMethod(
                "buildSheetFromPatterns", Pattern[].class, List.class, int.class);
        buildSheet.setAccessible(true);
        return (ObjectSpriteSheet) buildSheet.invoke(null, patterns, frames, 0);
    }
}
