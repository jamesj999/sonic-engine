package com.openggf.game.sonic2.objects;
import com.openggf.level.objects.BoxObjectInstance;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;

import java.util.List;

/**
 * Invisible solid block (Object 0x74).
 * Provides solid collision without visual representation.
 * Subtype encodes dimensions:
 *   Upper 4 bits: width = ((n >> 4) + 1) * 16 pixels
 *   Lower 4 bits: height = ((n & 0xF) + 1) * 16 pixels
 */
public class InvisibleBlockObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    private static final boolean DEBUG_VIEW_ENABLED = staticDebugViewEnabled();
    private static final DebugOverlayManager OVERLAY_MANAGER = staticDebugOverlay();

    private int halfWidth;
    private int halfHeight;

    public InvisibleBlockObjectInstance(ObjectSpawn spawn, String name) {
        // Gray color for debug rendering
        super(spawn, name, 16, 16, 0.5f, 0.5f, 0.5f, false);

        int subtype = spawn.subtype();
        // Width: ((upper 4 bits) + 1) * 16 / 2 for half-width
        this.halfWidth = (((subtype >> 4) & 0xF) + 1) * 8;
        // Height: ((lower 4 bits) + 1) * 16 / 2 for half-height
        this.halfHeight = ((subtype & 0xF) + 1) * 8;
    }

    private InvisibleBlockObjectInstance(ObjectSpawn spawn) {
        this(spawn, "InvisibleBlock");
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Match disassembly: adds 0x0B (11) to width for collision
        int d1 = halfWidth + 11;
        int d2 = halfHeight;
        int d3 = halfHeight + 1;
        return SolidObjectParams.of(d1, d2, d3);
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // S2 Obj74 calls SolidObject_Always, which checks solidity even when
        // the object or sidekick is offscreen (docs/s2disasm/s2.asm:34863-34873,
        // 46152-46161).
        return true;
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // S2 SolidObject_Always_SingleCharacter clears this object's stale
        // standing bit and returns d4=0 when the player is already airborne,
        // without falling into SolidObject_cont side resolution
        // (docs/s2disasm/s2.asm:34874-34893).
        return true;
    }

    @Override
    public boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(PlayableEntity player) {
        // Obj74 falls through to S2 SolidObject_cont for new contacts.
        // That helper adds the live y_radius(a1) to d2, then doubles d2 for
        // the lower reject bound (docs/s2disasm/s2.asm:35156-35169).
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity,
                               SolidContact contact, int frameCounter) {
        // No special behavior - standard collision handled by ObjectManager
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
        return DEBUG_VIEW_ENABLED && OVERLAY_MANAGER.isEnabled(DebugOverlayToggle.OVERLAY);
    }
}
