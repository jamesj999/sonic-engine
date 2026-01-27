package uk.co.jamesj999.sonic.level.bumpers;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Loads CNZ map bumper data from ROM.
 * <p>
 * ROM Offsets (REV01):
 * <ul>
 *   <li>Act 1: 0x1781A (SpecialCNZBumpers_Act1)</li>
 *   <li>Act 2: 0x1795E (SpecialCNZBumpers_Act2)</li>
 * </ul>
 * <p>
 * Format: Array of 6-byte entries sorted by X position.
 * The data does NOT have an explicit terminator - the end is determined by
 * the start of Act 2 data or by reaching invalid coordinates.
 * <p>
 * Disassembly Reference: s2.asm lines 32659-32674
 * <pre>
 * SpecialCNZBumpers_Act1:
 *     BINCLUDE "level/objects/CNZ 1 bumpers.bin"
 * SpecialCNZBumpers_Act2:
 *     BINCLUDE "level/objects/CNZ 2 bumpers.bin"
 * </pre>
 */
public class CNZBumperDataLoader {
    private static final Logger LOGGER = Logger.getLogger(CNZBumperDataLoader.class.getName());

    /**
     * Size of each bumper entry in bytes.
     * <ul>
     *   <li>2 bytes: bumper_id (type)</li>
     *   <li>2 bytes: bumper_x (X position)</li>
     *   <li>2 bytes: bumper_y (Y position)</li>
     * </ul>
     */
    public static final int ENTRY_SIZE = 6;

    /**
     * Maximum number of bumpers to load (safety limit).
     * CNZ 1 has ~54 bumpers, CNZ 2 has ~54 bumpers.
     */
    private static final int MAX_BUMPERS = 100;

    /**
     * Load CNZ bumper data for the specified act.
     *
     * @param rom The ROM to read from
     * @param act The act number (0 for Act 1, 1 for Act 2)
     * @return List of bumper spawns sorted by X position
     * @throws IOException If ROM read fails
     */
    public List<CNZBumperSpawn> load(Rom rom, int act) throws IOException {
        int baseAddr = (act == 0) ? Sonic2Constants.CNZ_BUMPERS_ACT1_ADDR : Sonic2Constants.CNZ_BUMPERS_ACT2_ADDR;
        int endAddr = (act == 0) ? Sonic2Constants.CNZ_BUMPERS_ACT2_ADDR : (Sonic2Constants.CNZ_BUMPERS_ACT2_ADDR + 324); // Approx end

        List<CNZBumperSpawn> bumpers = new ArrayList<>();
        int index = 0;

        LOGGER.fine(() -> String.format("Loading CNZ bumpers for Act %d from 0x%X", act + 1, baseAddr));

        for (int offset = 0; offset < (endAddr - baseAddr) && index < MAX_BUMPERS; offset += ENTRY_SIZE) {
            int addr = baseAddr + offset;
            int bumperId = rom.read16BitAddr(addr);
            int x = rom.read16BitAddr(addr + 2);
            int y = rom.read16BitAddr(addr + 4);

            // Check for end markers or invalid data
            // The ROM uses sorted X positions, so a very low X after high X values indicates end
            if (!bumpers.isEmpty()) {
                int lastX = bumpers.get(bumpers.size() - 1).x();
                // If X suddenly drops significantly, we've hit the next act's data or garbage
                if (x < lastX - 0x1000 && x < 0x100) {
                    final int finalOffset = offset;
                    LOGGER.fine(() -> String.format("End of bumper data detected at offset 0x%X (X wrapped)", finalOffset));
                    break;
                }
            }

            // Skip entries with obviously invalid coordinates
            if (x == 0 && y == 0 && bumperId == 0) {
                continue; // Skip padding/boundary markers
            }
            if (x > 0x7FFF || y > 0x7FFF) {
                final int finalOffset = offset;
                final int finalX = x;
                final int finalY = y;
                LOGGER.fine(() -> String.format("Skipping invalid bumper at offset 0x%X: x=0x%X, y=0x%X",
                        finalOffset, finalX, finalY));
                continue;
            }

            int type = CNZBumperSpawn.extractType(bumperId);
            if (type > 5) {
                final int finalType = type;
                final int finalOffset = offset;
                LOGGER.warning(() -> String.format("Invalid bumper type %d at offset 0x%X, clamping to 5", finalType, finalOffset));
                type = 5;
            }

            bumpers.add(new CNZBumperSpawn(index++, type, x, y));
        }

        // Sort by X for efficient camera windowing (should already be sorted in ROM)
        bumpers.sort(Comparator.comparingInt(CNZBumperSpawn::x));

        LOGGER.info(() -> String.format("Loaded %d CNZ bumpers for Act %d", bumpers.size(), act + 1));

        return bumpers;
    }
}
