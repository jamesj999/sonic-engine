package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.SolidContact;
import uk.co.jamesj999.sonic.level.objects.SolidObjectListener;
import uk.co.jamesj999.sonic.level.objects.SolidObjectParams;
import uk.co.jamesj999.sonic.level.objects.SolidObjectProvider;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Invisible solid block (Object 0x74).
 * Provides solid collision without visual representation.
 * Subtype encodes dimensions:
 *   Upper 4 bits: width = ((n >> 4) + 1) * 16 pixels
 *   Lower 4 bits: height = ((n & 0xF) + 1) * 16 pixels
 */
public class InvisibleBlockObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    private final int halfWidth;
    private final int halfHeight;

    public InvisibleBlockObjectInstance(ObjectSpawn spawn, String name) {
        // Gray color for debug rendering
        super(spawn, name, 16, 16, 0.5f, 0.5f, 0.5f, false);

        int subtype = spawn.subtype();
        // Width: ((upper 4 bits) + 1) * 16 / 2 for half-width
        this.halfWidth = (((subtype >> 4) & 0xF) + 1) * 8;
        // Height: ((lower 4 bits) + 1) * 16 / 2 for half-height
        this.halfHeight = ((subtype & 0xF) + 1) * 8;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Match disassembly: adds 0x0B (11) to width for collision
        int d1 = halfWidth + 11;
        int d2 = halfHeight;
        int d3 = halfHeight + 1;
        return new SolidObjectParams(d1, d2, d3);
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player,
                               SolidContact contact, int frameCounter) {
        // No special behavior - standard collision handled by SolidObjectManager
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Only render in debug mode
        if (isDebugViewEnabled()) {
            super.appendRenderCommands(commands);
        }
    }

    @Override
    protected int getHalfWidth() {
        return halfWidth;
    }

    @Override
    protected int getHalfHeight() {
        return halfHeight;
    }

    private boolean isDebugViewEnabled() {
        return SonicConfigurationService.getInstance()
                .getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
    }
}
