package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Bridge Stake / Scenery object (0x1C) - Static decorative posts and edges.
 * <p>
 * From disassembly Obj1C_InitData table:
 * <ul>
 *   <li>Subtype 0: Bolt/rope (Obj1C_MapUnc_11552 frame 0, BoltEnd_Rope art)</li>
 *   <li>Subtype 1: Bolt/rope (Obj1C_MapUnc_11552 frame 1, BoltEnd_Rope art)</li>
 *   <li>Subtype 2: EHZ bridge stake (Obj11_MapUnc_FC70 frame 1, EHZ_Bridge art)</li>
 *   <li>Subtype 3: Bolt/rope (Obj1C_MapUnc_11552 frame 2, BoltEnd_Rope art)</li>
 *   <li>Subtype 4: HTZ left stake (Obj16_MapUnc_21F14 frame 3, HtzZipline art)</li>
 *   <li>Subtype 5: HTZ right stake (Obj16_MapUnc_21F14 frame 4, HtzZipline art)</li>
 *   <li>Subtype 6: HTZ connector (Obj16_MapUnc_21F14 frame 1, HtzZipline art) - spawned by lift</li>
 *   <li>Subtype 7: Ground edge left (Obj1C_MapUnc_113D6 frame 0, LevelArt)</li>
 *   <li>Subtype 8: Ground edge right (Obj1C_MapUnc_113D6 frame 1, LevelArt)</li>
 * </ul>
 * No collision/physics (pure scenery).
 */
public class BridgeStakeObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    public BridgeStakeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    @Override
    public BridgeStakeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new BridgeStakeObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        int subtype = spawn.subtype() & 0xFF;
        PatternSpriteRenderer renderer;
        int frame;

        switch (subtype) {
            case 4 -> {  // HTZ left stake
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.HTZ_LIFT);
                frame = 3;
            }
            case 5 -> {  // HTZ right stake
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.HTZ_LIFT);
                frame = 4;
            }
            case 6 -> {  // HTZ connector (spawned by lift)
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.HTZ_LIFT);
                frame = 1;
            }
            case 7, 8 -> {  // Ground edge level-art stand-in until direct level-art mappings are available
                renderer = renderManager.getBridgeRenderer();
                frame = 1;
            }
            default -> {  // EHZ bridge stake (subtype 2) and others
                renderer = renderManager.getBridgeRenderer();
                frame = 1;
            }
        }

        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(frame, spawn.x(), spawn.y(), hFlip, vFlip);
    }
}
