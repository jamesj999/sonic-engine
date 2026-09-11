package com.openggf.level.objects.boss;

import com.openggf.game.PlayableEntity;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestAbstractBossPaletteOwnership {
    private static final String BOSS_FLASH_OWNER = "boss.flash";
    private static final TouchResponseResult BOSS_HIT =
            new TouchResponseResult(0x10, 0, 0, TouchCategory.BOSS);

    @Test
    void baseBossFlashSubmitsPaletteOwnershipClaim() {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        DummyBoss boss = new DummyBoss(level, registry);

        boss.onPlayerAttack(null, BOSS_HIT);

        registry.beginFrame();
        boss.update(0, null);
        registry.resolveInto(level.palettes(), null, null, null);

        assertEquals(BOSS_FLASH_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 1),
                "Base boss flash color should be owned by the shared boss palette writer");
        assertColorWord(level.getPalette(1), 1, 0x0000);
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        int expectedR = (r3 * 255 + 3) / 7;
        int expectedG = (g3 * 255 + 3) / 7;
        int expectedB = (b3 * 255 + 3) / 7;
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF);
    }

    private static final class DummyBoss extends AbstractBossInstance {
        private DummyBoss(StubLevel level, PaletteOwnershipRegistry registry) {
            super(new ObjectSpawn(0x1200, 0x300, 0x91, 0, 0, false, 0), "DummyBoss");
            setServices(new StubObjectServices() {
                @Override
                public Level currentLevel() {
                    return level;
                }

                @Override
                public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
                    return registry;
                }
            });
        }

        @Override
        protected void initializeBossState() {
            state.hitCount = 3;
        }

        @Override
        protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
            // Test stub.
        }

        @Override
        protected int getInitialHitCount() {
            return 3;
        }

        @Override
        protected void onHitTaken(int remainingHits) {
            // Test stub.
        }

        @Override
        protected int getCollisionSizeIndex() {
            return 0x0F;
        }

        @Override
        protected int getBossHitSfxId() {
            return 0;
        }

        @Override
        protected int getBossExplosionSfxId() {
            return 0;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Test stub.
        }
    }

    private static final class StubLevel implements Level {
        private final Palette[] palettes = new Palette[] {
                new Palette(), new Palette(), new Palette(), new Palette()
        };

        Palette[] palettes() {
            return palettes;
        }

        @Override public int getPaletteCount() { return palettes.length; }
        @Override public Palette getPalette(int index) { return palettes[index]; }
        @Override public int getPatternCount() { return 0; }
        @Override public Pattern getPattern(int index) { throw new UnsupportedOperationException(); }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { throw new UnsupportedOperationException(); }
        @Override public int getBlockCount() { return 0; }
        @Override public Block getBlock(int index) { throw new UnsupportedOperationException(); }
        @Override public SolidTile getSolidTile(int index) { throw new UnsupportedOperationException(); }
        @Override public Map getMap() { return null; }
        @Override public List<ObjectSpawn> getObjects() { return List.of(); }
        @Override public List<RingSpawn> getRings() { return List.of(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        @Override public int getZoneIndex() { return 0; }
    }
}
