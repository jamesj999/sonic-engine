package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x2F - StillSprite.
 * <p>
 * Static decorative sprite using level VRAM patterns. Each of the 51 subtypes
 * maps to a specific art tile base, palette, priority, and mapping frame from
 * the ROM data table at word_2B968.
 * <p>
 * ROM reference: sonic3k.asm lines 60199-60372
 */
public class StillSpriteInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final int MAX_SUBTYPE = 50;

    /**
     * Per-subtype configuration from word_2B968: art key, local frame within
     * sheet, render footprint, display priority bucket, and high priority flag.
     */
    private record SubtypeInfo(String artKey, int localFrame, int halfWidth, int halfHeight,
                               int priorityBucket, boolean highPriority) {
    }

    private static final SubtypeInfo[] SUBTYPE_TABLE = buildSubtypeTable();

    private final SubtypeInfo info;
    private PlaceholderObjectInstance placeholder;

    public StillSpriteInstance(ObjectSpawn spawn) {
        super(spawn, "StillSprite");
        int sub = spawn.subtype() & 0xFF;
        if (sub >= 0 && sub < SUBTYPE_TABLE.length && SUBTYPE_TABLE[sub] != null) {
            this.info = SUBTYPE_TABLE[sub];
        } else {
            this.info = null;
        }
    }

    @Override
    public StillSpriteInstance recreateForRewind(RewindRecreateContext ctx) {
        return new StillSpriteInstance(ctx.spawn());
    }

    @Override
    public int getPriorityBucket() {
        return info != null ? info.priorityBucket : 6;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return info != null ? info.halfWidth : super.getOnScreenHalfWidth();
    }

    @Override
    public int getOnScreenHalfHeight() {
        return info != null ? info.halfHeight : super.getOnScreenHalfHeight();
    }

    @Override
    public boolean isHighPriority() {
        return info != null && info.highPriority;
    }

    @Override
    public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
        // ROM Obj_StillSprite loops in Sprite_OnScreen_Test (sonic3k.asm:
        // 37262-37277): delete only when the chunk-aligned X leaves the coarse
        // (Camera_X_pos_coarse_back .. +$280) window, not the exact screen.
        // Placement spawns sprites beyond the screen edge, so an exact-screen
        // check would delete every StillSprite on its first update.
        if (!isInRangeAt(getX())) {
            setDestroyedByOffscreen();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (info != null) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager != null) {
                PatternSpriteRenderer renderer = renderManager.getRenderer(info.artKey);
                if (renderer != null && renderer.isReady()) {
                    boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
                    boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
                    renderer.drawFrameIndexForcedPriority(
                            info.localFrame, getX(), getY(), hFlip, vFlip, -1, info.highPriority);
                    return;
                }
            }
        }

        if (placeholder == null) {
            placeholder = new PlaceholderObjectInstance(spawn, name);
        }
        placeholder.appendRenderCommands(commands);
    }

    @SuppressWarnings("checkstyle:MethodLength")
    private static SubtypeInfo[] buildSubtypeTable() {
        SubtypeInfo[] t = new SubtypeInfo[MAX_SUBTYPE + 1];

        // AIZ (subtypes 0-5)
        // base 0x2E9 (AIZMisc2, pal 2): subtypes 0,1,2,5
        t[0]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_AIZ_MISC2, 0, 16, 16, 6, false);
        t[1]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_AIZ_MISC2, 1, 16, 16, 6, false);
        t[2]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_AIZ_MISC2, 2, 16, 16, 6, false);
        t[5]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_AIZ_MISC2, 3, 16, 16, 6, true);
        // base 0x001, pal 2: subtype 3
        t[3]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_AIZ_001, 0, 16, 16, 6, false);
        // base 0x001, pal 3: subtype 4 (waterfall)
        t[4]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_AIZ_WATERFALL, 0, 16, 16, 6, false);

        // HCZ (subtypes 6-10, 15-19)
        // base 0x001, pal 2: subtypes 6-10 (waterfalls)
        t[6]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_001, 0, 16, 16, 0, true);
        t[7]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_001, 1, 16, 16, 0, true);
        t[8]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_001, 2, 16, 16, 6, false);
        t[9]  = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_001, 3, 16, 16, 0, true);
        t[10] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_001, 4, 16, 16, 0, true);
        // HCZ tubes (each a separate art key, pal 2)
        t[15] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_TUBE1, 0, 16, 16, 0, true);
        t[16] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_TUBE2, 0, 16, 16, 0, true);
        t[17] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_TUBE3, 0, 16, 16, 0, true);
        t[18] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_TUBE4, 0, 16, 16, 0, true);
        // HCZ post
        t[19] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_HCZ_POST, 0, 16, 16, 6, false);

        // MGZ (subtypes 11-14)
        t[11] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MGZ, 0, 16, 16, 6, false);
        t[12] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MGZ, 1, 16, 16, 6, false);
        t[13] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MGZ, 2, 16, 16, 6, false);
        t[14] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MGZ, 3, 16, 16, 6, false);

        // LBZ (subtypes 20-23)
        t[20] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LBZ_POLE, 0, 16, 16, 6, false);
        t[21] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LBZ_GIRDER, 0, 16, 16, 6, false);
        t[22] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LBZ_GIRDER, 1, 16, 16, 6, false);
        t[23] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LBZ_GIRDER, 2, 16, 16, 1, false);

        // MHZ (subtypes 24-30)
        t[24] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MHZ_CLIFF, 0, 4, 16, 1, true);
        t[25] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MHZ_CLIFF, 1, 4, 16, 1, true);
        t[26] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MHZ_CLIFF, 2, 16, 4, 1, true);
        t[27] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MHZ_COLUMN, 0, 16, 8, 1, true);
        t[28] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MHZ_COLUMN, 1, 16, 8, 1, true);
        t[29] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MHZ_VINE, 0, 16, 8, 4, false);
        t[30] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_MHZ_PEDESTAL, 0, 8, 8, 5, false);

        // LRZ (subtypes 31-38)
        // base 0x3A1, pal 2: subtypes 31-33 (horizontal rails)
        t[31] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LRZ_RAIL, 0, 16, 16, 3, true);
        t[32] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LRZ_RAIL, 1, 16, 16, 3, true);
        t[33] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LRZ_RAIL, 2, 16, 16, 3, true);
        // base 0x0D3, pal 2: subtype 34
        t[34] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LRZ_ROCK, 0, 16, 16, 1, true);
        // base 0x3A1, pal 1: subtypes 35-38 (vertical gear rails)
        t[35] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LRZ_GEAR, 0, 16, 16, 3, true);
        t[36] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LRZ_GEAR, 1, 16, 16, 3, true);
        t[37] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LRZ_GEAR, 2, 16, 16, 3, true);
        t[38] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_LRZ_GEAR, 3, 16, 16, 3, true);

        // FBZ (subtypes 39-45)
        t[39] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_FBZ_HANGER, 0, 16, 16, 1, false);
        t[40] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_FBZ_HANGER, 1, 16, 16, 1, false);
        t[41] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_FBZ_HANGER, 2, 16, 16, 1, false);
        t[42] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_FBZ_HANGER, 3, 16, 16, 1, false);
        t[43] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_FBZ_EXTRA, 0, 16, 16, 6, false);
        t[44] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_FBZ_RAIL, 0, 16, 16, 0, false);
        t[45] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_FBZ_RAIL, 1, 16, 16, 5, false);

        // SOZ (subtypes 46-47)
        t[46] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_SOZ_001, 0, 16, 16, 2, true);
        t[47] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_SOZ_CORK, 0, 16, 16, 0, false);

        // DEZ (subtypes 48-50)
        t[48] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_DEZ_BEAM, 0, 16, 16, 5, false);
        t[49] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_DEZ_BEAM, 1, 16, 16, 5, false);
        t[50] = new SubtypeInfo(Sonic3kObjectArtKeys.STILL_DEZ_POST, 0, 16, 16, 1, false);

        return t;
    }
}
