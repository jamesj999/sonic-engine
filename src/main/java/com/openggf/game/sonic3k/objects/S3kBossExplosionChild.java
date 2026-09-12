package com.openggf.game.sonic3k.objects;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.io.IOException;
import java.util.List;

/**
 * S3K boss explosion child (ROM: Obj_BossExplosion1/2).
 * Established callers retain their existing audio choice. Big Arm uses the
 * native-init factory because Obj_BossExplosion1 owns sfx_Explode (0xB4) on
 * the child's first own entry.
 *
 * ROM animation format: Animate_RawNoSSTMultiDelay — (delay, frame) pairs.
 * AniRaw_BossExplosion (sonic3k.asm:176871):
 *   dc.b 0,0, 0,1, 1,1, 2,2, 3,3, 4,4, 5,4, $F4
 * $F4 = end (calls Go_Delete_Sprite via $34 callback).
 */
public class S3kBossExplosionChild extends AbstractObjectInstance implements SpawnCoordinateRewindRecreatable {
    private int rawCursor;
    private int mappingFrame;
    private int rawTimer;
    private boolean nativeInitSfx;
    private boolean nativeInitSfxPlayed;
    private boolean pendingDelete;
    private transient Rom rawRom;
    private transient RomByteReader rawReader;

    S3kBossExplosionChild() {
        this(0, 0);
    }

    public S3kBossExplosionChild(int x, int y) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "S3kBossExplosion");
    }

    public static S3kBossExplosionChild createWithNativeInitSfx(int x, int y) {
        S3kBossExplosionChild child = new S3kBossExplosionChild(x, y);
        child.nativeInitSfx = true;
        return child;
    }

    /** Native word-position writes performed after CreateChild6_Simple succeeds. */
    public void writeNativePositionWords(int x, int y) {
        updateDynamicSpawn(x & 0xFFFF, y & 0xFFFF);
    }

    /** ROM sub_83E90 positions an already allocated explosion before its first dispatch. */
    public void setSpawnPosition(int x, int y) {
        updateDynamicSpawn(x & 0xFFFF, y & 0xFFFF);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (pendingDelete) {
            setDestroyed(true);
            return;
        }
        if (nativeInitSfx && !nativeInitSfxPlayed) {
            nativeInitSfxPlayed = true;
            services().playSfx(Sonic3kSfx.EXPLODE.id);
        }
        rawTimer = (rawTimer - 1) & 0xFF;
        if ((byte) rawTimer >= 0) {
            return;
        }
        rawCursor = (rawCursor + 2) & 0xFF;
        int value = rawByte(rawCursor);
        if ((byte) value < 0) {
            // $F4 selects Go_Delete_Sprite through the native raw callback.
            rawCursor = 0;
            rawTimer = 0;
            pendingDelete = true;
            return;
        }
        mappingFrame = value;
        rawTimer = rawByte(rawCursor + 1);
    }

    private int rawByte(int offset) {
        if (rawRom == null) {
            try {
                rawRom = services().rom();
            } catch (IOException ignored) {
                // Unit harnesses may expose only the immutable ROM reader.
            }
        }
        if (rawRom != null) {
            try {
                return Byte.toUnsignedInt(rawRom.readByte(
                        Sonic3kConstants.ANI_RAW_BOSS_EXPLOSION_ADDR + offset));
            } catch (IOException ex) {
                throw new IllegalStateException("Boss explosion requires the verified S3K ROM", ex);
            }
        }
        if (rawReader == null) {
            try {
                rawReader = services().romReader();
            } catch (IOException ex) {
                throw new IllegalStateException("Boss explosion requires the verified S3K ROM", ex);
            }
            if (rawReader == null) {
                throw new IllegalStateException("Boss explosion requires the verified S3K ROM");
            }
        }
        return rawReader.readU8(Sonic3kConstants.ANI_RAW_BOSS_EXPLOSION_ADDR + offset);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;
        ObjectRenderManager rm = services().renderManager();
        if (rm == null) return;
        // ROM: Obj_BossExplosion1/2 share Map_BossExplosion and AniRaw_BossExplosion.
        PatternSpriteRenderer renderer = rm.getBossExplosionRenderer();
        if (renderer == null || !renderer.isReady()) return;
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: Obj_BossExplosion uses make_art_tile(ArtTile_BossExplosion2,0,1) for AIZ
        // (and make_art_tile(ArtTile_BossExplosion,0,1) for other zones) — priority bit = 1.
        return true;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: ObjDat_BossExplosion dc.w 0 → sprite_priority $0000 → bucket 0
        return 0;
    }

    public int rawCursorForTest() { return rawCursor; }
    public int mappingFrameForTest() { return mappingFrame; }
    public int rawTimerForTest() { return rawTimer; }
    public boolean nativeInitSfxForTest() { return nativeInitSfx; }
    public boolean nativeInitSfxPlayedForTest() { return nativeInitSfxPlayed; }
    public boolean pendingDeleteForTest() { return pendingDelete; }
}
