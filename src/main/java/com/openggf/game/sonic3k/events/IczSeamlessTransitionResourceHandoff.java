package com.openggf.game.sonic3k.events;

import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.Sonic3kDeferredLevelResourceProfile;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.level.SeamlessTransitionResourceHandoff;
import com.openggf.level.resources.DeferredLevelResourceManifest;

/** Exact production resources carried by the ICZ1-to-ICZ2 reload. */
final class IczSeamlessTransitionResourceHandoff
        implements SeamlessTransitionResourceHandoff {
    private static final int ICZ2_LEVEL_LOAD_BLOCK_INDEX = 11;

    private final S3kKosDecompressionQueue directQueue;
    private final HardwareWorkHandle chunkHandle;
    private final HardwareWorkHandle blockHandle;
    private final S3kKosModuleQueue artQueue;
    private final HardwareWorkHandle artHandle;
    private final Sonic3kLevelEventManager eventManager;
    private final DeferredLevelResourceManifest deferredResources;
    private final RuntimeArtAdmissionLease admissionLease;

    IczSeamlessTransitionResourceHandoff(
            S3kKosDecompressionQueue directQueue,
            HardwareWorkHandle chunkHandle,
            HardwareWorkHandle blockHandle,
            S3kKosModuleQueue artQueue,
            HardwareWorkHandle artHandle,
            Sonic3kLevelEventManager eventManager) {
        this(directQueue, chunkHandle, blockHandle, artQueue, artHandle,
                eventManager, null);
    }

    private IczSeamlessTransitionResourceHandoff(
            S3kKosDecompressionQueue directQueue,
            HardwareWorkHandle chunkHandle,
            HardwareWorkHandle blockHandle,
            S3kKosModuleQueue artQueue,
            HardwareWorkHandle artHandle,
            Sonic3kLevelEventManager eventManager,
            RuntimeArtAdmissionLease admissionLease) {
        this.directQueue = directQueue;
        this.chunkHandle = chunkHandle;
        this.blockHandle = blockHandle;
        this.artQueue = artQueue;
        this.artHandle = artHandle;
        this.eventManager = eventManager;
        this.admissionLease = admissionLease;
        Sonic3kDeferredLevelResourceProfile profile =
                Sonic3kDeferredLevelResourceProfile.forLevelLoadBlock(
                        ICZ2_LEVEL_LOAD_BLOCK_INDEX);
        this.deferredResources = profile.manifest(
                artQueue.descriptor(artHandle).sourceAddress(),
                directQueue.descriptor(blockHandle).sourceAddress(),
                directQueue.descriptor(chunkHandle).sourceAddress());
    }

    @Override
    public DeferredLevelResourceManifest deferredResources() {
        return deferredResources;
    }

    boolean directQueueEmpty() {
        return !directQueue.decompressionsPending();
    }

    @Override
    public SeamlessTransitionResourceHandoff withAdmissionLease(
            RuntimeArtAdmissionLease lease) {
        if (admissionLease != null) {
            throw new IllegalStateException(
                    "ICZ seamless handoff already owns an admission lease");
        }
        if (lease == null
                || lease.ownerKind()
                        != RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER) {
            throw new IllegalArgumentException(
                    "ICZ seamless handoff requires a resource-owner lease");
        }
        return new IczSeamlessTransitionResourceHandoff(
                directQueue, chunkHandle, blockHandle,
                artQueue, artHandle, eventManager, lease);
    }

    @Override
    public void transferAfterTargetInit() {
        Sonic3kICZEvents target = eventManager.getIczEvents();
        if (target == null) {
            throw new IllegalStateException(
                    "ICZ seamless transition did not install its Act 2 resource owner");
        }
        target.acceptTransferredIcz2Resources(
                directQueue,
                chunkHandle,
                blockHandle,
                artQueue,
                artHandle,
                admissionLease);
    }
}
