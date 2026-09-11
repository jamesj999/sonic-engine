package com.openggf.util;

import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.render.SpriteMappingFrame;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Lazy-loading holder for static sprite mapping data.
 * Not thread-safe; assumes single-threaded game loop access.
 * Load is attempted only once per instance. If loading fails,
 * subsequent calls return an empty list without retrying. A call made
 * before a ROM-backed game is mounted is not an attempt: it returns
 * empty and leaves the holder unlatched so the real load still happens.
 */
public final class LazyMappingHolder {

    private static final Logger LOG = Logger.getLogger(LazyMappingHolder.class.getName());

    private List<SpriteMappingFrame> mappings;
    private boolean attempted;

    @FunctionalInterface
    public interface MappingLoader {
        List<SpriteMappingFrame> load(RomByteReader reader, int addr) throws IOException;
    }

    public List<SpriteMappingFrame> get(int mappingAddr, MappingLoader loader, String label) {
        if (attempted) {
            return mappings != null ? mappings : Collections.emptyList();
        }
        LevelManager manager = GameServices.levelOrNull();
        if (manager == null || manager.getGame() == null) {
            // No ROM-backed game is mounted yet, so nothing was attempted. Latching
            // here would cache the empty result for the lifetime of the holder and
            // permanently starve every later consumer of its mapping frames.
            return Collections.emptyList();
        }
        attempted = true;

        try {
            var rom = manager.getGame().getRom();
            var reader = RomByteReader.fromRom(rom);
            mappings = loader.load(reader, mappingAddr);
            LOG.fine("Loaded " + mappings.size() + " " + label + " mapping frames");
            return mappings;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to load " + label + " mappings: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
