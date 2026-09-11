package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.RespawnState;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * MHZ Act 1 cutscene button paired with {@link Mhz1CutsceneKnucklesInstance}.
 *
 * <p>ROM reference: {@code Obj_MHZ1CutsceneButton}. This owns the cutscene
 * switch and creates both the fixed door child and the later peering Knuckles
 * child used by the {@code _unkFAB8=$0C} release callback.
 */
public final class Mhz1CutsceneButtonInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable,
        RomObjectCodePointerProvider {
    // Obj_MHZ1CutsceneButton installs its main code pointer
    // MHZ1CutsceneButton_Main at 0x00062xxx, so word 0 of the stood-on object
    // SST is high word 0x0006 (docs/skdisasm/sonic3k.asm:130055-130125). S3K
    // sub_13EFC latches this as Tails_CPU_interact while a sidekick stands on
    // the button (sonic3k.asm:26816-26843); a later off-screen landing on a
    // different-word object (e.g. the 0x0003 MHZ curled vine) then despawns.
    private static final int ROM_CODE_POINTER_HIGH_WORD = 0x0006;
    private static final int INIT_Y_OFFSET = 4;
    private static final int PRIORITY = 2;
    private static final int CALLBACK_WAIT = 0x5F;
    // ROM Obj_MHZ1CutsceneButton keeps its solid box on the raw object y_pos
    // (trace slot 11 y = 0x67C). The engine stores this.y = spawn.y() +
    // INIT_Y_OFFSET for the sprite draw, so the solid anchor must shift back by
    // -INIT_Y_OFFSET to sit on the ROM y_pos; otherwise SolidObjectFull's
    // top-slice penetration (d3) is 4px short, misclassifying a falling sidekick
    // graze (MHZ1 trace F951: Tails must stay a SIDE contact, d3 > 4) and
    // under-lifting the rising rolling-jump graze (F966).
    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(0x1B, 4, 5, 0, -INIT_Y_OFFSET);

    private int x;
    private int y;
    private boolean pressed;
    private boolean normalPressed;
    private boolean contactStanding;
    private boolean doorSpawned;
    private Mhz1CutsceneDoorInstance spawnedDoor;
    private boolean peerSpawned;
    private CutsceneKnucklesMhz1Instance spawnedKnuckles;
    private boolean doorSwitchActive;
    private boolean doorLowered;
    private boolean doorMoving;
    private boolean cutsceneDoorLatched;
    private int timer;
    private int cutscenePressedFrames;
    private boolean mainRoutineActive;
    private S3kKosModuleQueue knuxPeerArtQueue;
    private HardwareWorkHandle knuxPeerArtHandle;
    private long knuxPeerArtOrdinal = -1;

    public Mhz1CutsceneButtonInstance(ObjectSpawn spawn) {
        super(spawn, "MHZ1CutsceneButton");
        this.x = spawn.x();
        this.y = spawn.y() + INIT_Y_OFFSET;
    }

    @Override
    public int romObjectCodePointerHighWord() {
        return ROM_CODE_POINTER_HIGH_WORD;
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public boolean isPersistent() {
        // WaitForKnucklesEvent, CheckKnucklesPress, Depress, and Wait_Draw only
        // draw the object. Once ReleaseAfterDelay installs
        // MHZ1CutsceneButton_Main (or init selects Main from checkpoint/Knuckles
        // state), every normal routine ends in Sprite_CheckDelete.
        return !mainRoutineActive;
    }

    @Override
    public void onUnload() {
        if (spawnedDoor != null) {
            spawnedDoor.setDestroyed(true);
            spawnedDoor = null;
        }
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        retireKnucklesPeerArt();
        spawnDoorOnce();
        Mhz1CutsceneKnucklesInstance knuckles =
                Mhz1CutsceneKnucklesInstance.activeInstance(services().objectManager());
        if (usesNormalSwitchPath(knuckles)) {
            mainRoutineActive = true;
            updateNormalSwitchPath();
            return;
        }
        if (knuckles == null) {
            return;
        }
        if (!pressed) {
            if (knuckles.getWorkspaceRoutineForTest() < 0x0A) {
                return;
            }
            spawnMhz1KnucklesOnce();
            if (!spawnedKnucklesInButtonRange()) {
                return;
            }
            pressCutsceneButton();
            return;
        }
        if (timer >= 0) {
            timer--;
            if (cutscenePressedFrames > 0) {
                cutscenePressedFrames--;
            }
            if (timer >= 0) {
                return;
            }
        }
        mainRoutineActive = true;
        knuckles.signalButtonCallback();
    }

    private boolean usesNormalSwitchPath(Mhz1CutsceneKnucklesInstance activeCutscene) {
        if (activeCutscene != null) {
            return false;
        }
        if (lastStarPostHitIsSet()) {
            return true;
        }
        return S3kRuntimeStates.currentMhz(services().zoneRuntimeRegistry())
                .map(state -> state.playerCharacter() == PlayerCharacter.KNUCKLES)
                .orElse(false);
    }

    private boolean lastStarPostHitIsSet() {
        RespawnState checkpointState = services().checkpointState();
        return checkpointState != null && checkpointState.getLastCheckpointIndex() > 0;
    }

    private void updateNormalSwitchPath() {
        // ROM loc_62F0A/loc_62F4C call sub_65DEC (SolidObjectFull) before
        // testing status(a0)&standing_mask.
        boolean standing = hasStandingContact(checkpointAll()) || contactStanding;
        contactStanding = false;

        if (!normalPressed) {
            if (!standing) {
                return;
            }
            normalPressed = true;
            services().playSfx(Sonic3kSfx.SWITCH.id);
            if (!doorMoving) {
                doorSwitchActive = true;
                doorLowered = !doorLowered;
                services().playSfx(Sonic3kSfx.SWITCH.id);
            }
            return;
        }

        doorSwitchActive = false;
        if (!standing) {
            normalPressed = false;
        }
    }

    private void spawnMhz1KnucklesOnce() {
        if (peerSpawned) {
            return;
        }
        peerSpawned = true;
        queueKnucklesPeerArt();
        spawnFreeChild(() -> {
            spawnedKnuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                    0x0374, 0x066C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0), this);
            return spawnedKnuckles;
        });
    }

    /**
     * ROM {@code MHZ1CutsceneButton_LoadKnucklesPeer} submits
     * {@code ArtKosM_MHZKnuxPeer} through {@code Queue_Kos_Module} immediately
     * before {@code CreateChild6_Simple}, and does not wait for the module to
     * finish (sonic3k.asm:130077-130085). The decompressed payload is unused
     * here -- the peer sprite sheet is already registered as standalone art --
     * but the submission itself is ROM-visible hardware work, so it must exist
     * for the module and its direct child to complete on the ROM frames.
     */
    private void queueKnucklesPeerArt() {
        try {
            knuxPeerArtQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
            knuxPeerArtHandle = knuxPeerArtQueue.queue(
                    services().rom(),
                    Sonic3kConstants.ART_KOSM_MHZ_KNUX_PEER_ADDR,
                    Sonic3kConstants.ARTTILE_MHZ_KNUX_PEER);
            knuxPeerArtOrdinal = knuxPeerArtHandle.ordinal();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to queue MHZ1 cutscene Knuckles peer KosM art", e);
        }
    }

    private void retireKnucklesPeerArt() {
        if (knuxPeerArtOrdinal >= 0 && knuxPeerArtQueue == null) {
            knuxPeerArtHandle = services().hardwareTiming().pendingHandle(
                            HardwareWorkKind.KOS_MODULE_QUEUE, knuxPeerArtOrdinal)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing restored MHZ1 Knuckles peer KosM job "
                                    + knuxPeerArtOrdinal));
            knuxPeerArtQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
        }
        if (knuxPeerArtQueue == null || !knuxPeerArtQueue.isReady(knuxPeerArtHandle)) {
            return;
        }
        knuxPeerArtQueue.claim(knuxPeerArtHandle);
        knuxPeerArtHandle = null;
        knuxPeerArtQueue = null;
        knuxPeerArtOrdinal = -1;
    }

    private boolean spawnedKnucklesInButtonRange() {
        if (spawnedKnuckles == null || spawnedKnuckles.isDestroyed()) {
            return false;
        }
        int knucklesX = spawnedKnuckles.getX();
        int knucklesY = spawnedKnuckles.getY();
        return knucklesX >= x - 0x18 && knucklesX < x + 0x18
                && knucklesY >= y - 0x18 && knucklesY < y + 0x18;
    }

    private void pressCutsceneButton() {
        pressed = true;
        cutscenePressedFrames = 2;
        doorSwitchActive = true;
        doorLowered = true;
        cutsceneDoorLatched = true;
        // ROM loc_62ED0 installs Wait_Draw with $2E=$5F, and Obj_Wait
        // branches to loc_62EFC on the same tick that the counter underflows
        // (docs/skdisasm/sonic3k.asm:130101-130117,177944-177952).
        timer = CALLBACK_WAIT - 1;
        services().playSfx(Sonic3kSfx.SWITCH.id);
    }

    private void spawnDoorOnce() {
        if (doorSpawned) {
            return;
        }
        doorSpawned = true;
        spawnedDoor = spawnFreeChild(() -> new Mhz1CutsceneDoorInstance(this));
    }

    boolean isDoorSwitchActive() {
        return doorSwitchActive;
    }

    void clearDoorSwitchActive() {
        doorSwitchActive = false;
    }

    void detachSpawnedKnuckles(CutsceneKnucklesMhz1Instance actor) {
        if (spawnedKnuckles == actor) {
            spawnedKnuckles = null;
        }
    }

    void detachDoor(Mhz1CutsceneDoorInstance door) {
        if (spawnedDoor == door) {
            spawnedDoor = null;
        }
    }

    boolean isDoorLowered() {
        return doorLowered;
    }

    boolean isCutsceneDoorLatched() {
        return cutsceneDoorLatched;
    }

    void setDoorLowered(boolean doorLowered) {
        this.doorLowered = doorLowered;
    }

    void setDoorMoving(boolean doorMoving) {
        this.doorMoving = doorMoving;
    }

    boolean isDoorMovingForTest() {
        return doorMoving;
    }

    int getVisibleMappingFrameForTest() {
        return visibleMappingFrame();
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        // ROM: SolidObjectFull's top-slice clamp (sonic3k.asm loc_1E154:41611)
        // re-reads width_pixels(a0) for the landing X gate. sub_65DEC
        // (sonic3k.asm:134105) passes a hardcoded collision d1 = $1B into
        // SolidObjectFull, but ObjDat_MHZ1CutsceneButton (sonic3k.asm:134853)
        // sets width_pixels = $80. So the landing gate is far WIDER than the
        // $1B side-collision box: any player already inside the side box passes
        // the loc_1E154 X check. Without this the engine's default heuristic
        // (collision d1 - $B = $10) wrongly rejects a rolling-jump graze at the
        // right edge (MHZ1 trace F966: rising player over the button top slice).
        return 0x80;
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // sub_65DEC branches into SolidObject_cont; its right-edge X gate uses
        // cmp/bhi, so relX == width*2 is still a zero-distance side contact.
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (contact.standing()) {
            contactStanding = true;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.BUTTON);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(visibleMappingFrame(), x, y, false, false);
    }

    private int visibleMappingFrame() {
        return (normalPressed || cutscenePressedFrames > 0) ? 1 : 0;
    }
}
