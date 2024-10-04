package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.sprites.interactive.monitors.RingMonitor;

public class TestLevel extends AbstractLevel {

    @Override
    protected void setupTiles() {
        Tile halfFlat = new Tile(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
                8, (byte) 0x00, true);

        Tile lowFlat = new Tile(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                (byte) 0x00, false);
        Tile lowFlatToSlope = new Tile(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3,
                3, 3, 3, (byte) 0xFE, false);
        Tile lowFlatToSlope2 = new Tile(3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 5,
                5, 5, 5, (byte) 0xFC, false);
        Tile lowFlatToSlope3 = new Tile(5, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8,
                8, 8, 8, (byte) 0xFA, false);
        Tile lowFlatToSlope4 = new Tile(9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11,
                12, 12, 12, 12, 13, (byte) 0xF8, false);
        Tile lowFlatToSlope5 = new Tile(13, 13, 14, 14, 14, 15, 15, 15, 16, 16,
                16, 16, 16, 16, 16, 16, (byte) 0xF6, false);
        Tile lowSteepSlope = new Tile(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4,
                4, 4, (byte) 0xF8, false);
        Tile lowSteepSlope2 = new Tile(5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8,
                8, 8, 8, (byte) 0xF8, false);
        Tile lowSteepSlope3 = new Tile(9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11,
                11, 12, 12, 12, 12, (byte) 0xF8, false);
        Tile lowSteepSlope4 = new Tile(13, 13, 13, 13, 14, 14, 14, 14, 15, 15,
                15, 15, 16, 16, 16, 16, (byte) 0xF8, false);
        Tile halfHighPlatform = new Tile(16, 16, 16, 16, 16, 16, 16, 16, 0, 0,
                0, 0, 0, 0, 0, 0, (byte) 0xFF, false);
        Tile lowLessSteep = new Tile(1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3,
                3, 3, (byte) 0xFC, false);
        Tile lowLessSteep2 = new Tile(3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6,
                7, 7, (byte) 0xF8, false);
        Tile highSlopeDown = new Tile(16, 16, 15, 14, 14, 13, 12, 11, 11, 10,
                10, 9, 8, 8, 7, 7, (byte) 0x18, false);
        Tile highSlopeDown2 = new Tile(6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0, 0,
                0, 0, 0, (byte) 0x10, false);
        Tile full = new Tile(16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16,
                16, 16, 16, 16, (byte) 0x00, false);

        Tile famousCurve1 = new Tile(1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2,
                3, 3, (byte) 0xFC, false);
        Tile famousCurve2 = new Tile(3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 7, 7, 8, 8,
                9, 9, (byte) 0xF0, false);
        Tile famousCurve3 = new Tile(10, 10, 11, 12, 12, 13, 14, 14, 15, 16,
                16, 16, 16, 16, 16, 16, (byte) 0xE8, false);
        Tile famousCurve4 = new Tile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4,
                5, 6, (byte) 0xE0, false);
        Tile famousCurve5 = new Tile(7, 8, 10, 11, 13, 14, 16, 16, 16, 16, 16,
                16, 16, 16, 16, 16, (byte) 0xD8, false);
        Tile famousCurve6 = new Tile(0, 0, 0, 0, 0, 0, 0, 2, 4, 6, 8, 11, 14,
                16, 16, 16, (byte) 0xD0, false);
        Tile famousCurve7 = new Tile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2,
                8, 16, (byte) 0xC4, false);

        addTile(lowSteepSlope, 10, 254);
        addTile(lowSteepSlope2, 11, 254);
        addTile(lowSteepSlope3, 12, 254);
        addTile(lowSteepSlope4, 13, 254);

        drawRange(0, 9, 254, 254, lowFlat);

        addTile(lowSteepSlope, 14, 253);
        addTile(lowSteepSlope2, 15, 253);
        addTile(lowSteepSlope3, 16, 253);
        addTile(lowSteepSlope4, 17, 253);

        drawRange(14, 17, 254, 254, full);

        addTile(lowSteepSlope, 18, 252);
        addTile(lowSteepSlope2, 19, 252);
        addTile(lowSteepSlope3, 20, 252);
        addTile(lowSteepSlope4, 21, 252);

        drawRange(18, 21, 253, 254, full);

        addTile(lowSteepSlope, 22, 251);
        addTile(lowSteepSlope2, 23, 251);
        addTile(lowSteepSlope3, 24, 251);
        addTile(lowSteepSlope4, 25, 251);

        drawRange(22, 25, 252, 254, full);

        addTile(lowSteepSlope, 26, 250);
        addTile(lowSteepSlope2, 27, 250);
        addTile(lowSteepSlope3, 28, 250);
        addTile(lowSteepSlope4, 29, 250);

        drawRange(26, 29, 251, 254, full);

        addTile(lowSteepSlope, 30, 249);
        addTile(lowSteepSlope2, 31, 249);
        addTile(lowSteepSlope3, 32, 249);
        addTile(lowSteepSlope4, 33, 249);

        drawRange(30, 33, 250, 254, full);

        addTile(highSlopeDown, 34, 249);
        addTile(highSlopeDown2, 35, 249);

        addTile(highSlopeDown, 36, 250);

        drawRange(37, 40, 254, 254, lowFlat);

        addTile(lowFlatToSlope, 41, 254);
        addTile(lowFlatToSlope2, 42, 254);
        addTile(lowFlatToSlope3, 43, 254);
        addTile(lowFlatToSlope4, 44, 254);
        addTile(lowFlatToSlope5, 45, 254);

        drawRange(46, 50, 254, 254, lowFlat);

        addTile(famousCurve1, 50, 254);
        addTile(famousCurve2, 51, 254);
        addTile(famousCurve3, 52, 254);
        addTile(famousCurve4, 52, 253);
        addTile(famousCurve5, 53, 253);
        addTile(famousCurve6, 53, 252);
        addTile(famousCurve7, 53, 251);

        drawRange(0, 255, 255, 255, full);

        drawRange(54, 70, 254, 254, lowFlat);

        drawRange(72, 85, 254, 254, halfFlat);

        drawRange(87, 92, 254, 254, lowFlat);

        drawRange(93, 99, 253, 253, halfHighPlatform);

        drawRange(101, 110, 252, 252, halfHighPlatform);

        drawRange(110, 128, 254, 254, halfFlat);


        //drawRange(60, 60, 1, 200, full);

        //drawRange(60, 2048, 4,4, full);

        drawRange(6, 25, 247, 247, halfFlat);
        drawRange(1, 9, 244, 244, halfFlat);
        drawRange(14, 29, 241, 241, halfFlat);
        drawRange(26, 38, 236, 236, halfFlat);
        drawRange(45, 48, 232, 232, halfFlat);
        drawRange(20, 40, 234, 234, halfFlat);
        drawRange(49, 52, 229, 229, halfFlat);
        drawRange(49, 52, 225, 225, halfFlat);
        drawRange(49, 52, 221, 221, halfFlat);
        drawRange(49, 52, 217, 217, halfFlat);
        drawRange(49, 52, 214, 214, halfFlat);
        drawRange(49, 52, 210, 210, halfFlat);
        drawRange(49, 52, 206, 206, halfFlat);
        drawRange(49, 52, 202, 202, halfFlat);
        drawRange(49, 52, 198, 198, halfFlat);
        drawRange(49, 52, 194, 194, halfFlat);
        drawRange(49, 52, 189, 189, halfFlat);
        drawRange(49, 52, 185, 185, halfFlat);
        drawRange(49, 52, 180, 180, halfFlat);
        drawRange(49, 52, 175, 175, halfFlat);
        drawRange(49, 52, 170, 170, halfFlat);
        drawRange(49, 52, 165, 165, halfFlat);
    }

    protected void registerSprites() {
        //spriteManager.addSprite(new RingMonitor("001", (short) 533, (short) 364, 10));
        //spriteManager.addSprite(new RingMonitor("002", (short) 560, (short) 36, 10));
    }
}
