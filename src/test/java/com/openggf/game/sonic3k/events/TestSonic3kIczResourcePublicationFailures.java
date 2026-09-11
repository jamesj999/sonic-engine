package com.openggf.game.sonic3k.events;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.rewind.schema.ZoneEventSchemaSidecar;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingJob;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kIczResourcePublicationFailures {

  @Test
  void secondClaimFailureFencesPublicationWithoutConsumingAdmission()
      throws Exception {
    assertTerminalFailure(FailurePoint.SECOND_CLAIM, 1, 0, 0);
  }

  @Test
  void terrainApplyFailureFencesPublicationWithoutConsumingAdmission()
      throws Exception {
    assertTerminalFailure(FailurePoint.TERRAIN_APPLY, 3, 1, 0);
  }

  @Test
  void artApplyFailureFencesPublicationWithoutConsumingAdmission()
      throws Exception {
    assertTerminalFailure(FailurePoint.ART_APPLY, 3, 1, 1);
  }

  @Test
  void alreadyClaimedCarriedHandleFencesInsteadOfWaitingForever()
      throws Exception {
    ReadyOwner owner = readyOwner(FailurePoint.NONE);
    owner.directQueue.claim(owner.chunkHandle);

    assertThrows(IllegalStateException.class, () -> owner.events.update(1, 0));

    var providerState = owner.provider.capture();
    assertFalse(providerState.runtimeArtAdmissionConsumed());
    assertFalse(providerState.kosSubmissionArmed());
    byte[] failedState = ZoneEventSchemaSidecar.capture(owner.events);
    InjectingEvents restored = new InjectingEvents(FailurePoint.NONE);
    restored.init(1);
    ZoneEventSchemaSidecar.restore(restored, failedState);
    restored.discardHardwareWorkFacadesAfterRewind();
    assertThrows(IllegalStateException.class, () -> restored.update(1, 1));
    assertEquals(providerState, owner.provider.capture());
  }

  @Test
  void moduleReadyBeforeDirectKeepsEnemyAdmissionHeld() throws Exception {
    ReadyOwner owner = readyOwner(FailurePoint.NONE);
    HardwareTimingSnapshot allReady = GameServices.hardwareTiming().capture();
    GameServices.hardwareTiming().restore(
        withReady(allReady, owner.blockHandle, false));
    byte[] pendingState = ZoneEventSchemaSidecar.capture(owner.events);
    InjectingEvents restored = new InjectingEvents(FailurePoint.NONE);
    restored.init(1);
    ZoneEventSchemaSidecar.restore(restored, pendingState);
    restored.discardHardwareWorkFacadesAfterRewind();

    restored.update(1, 0);

    assertAdmissionHeld(owner);
    GameServices.hardwareTiming().restore(allReady);
    restored.update(1, 1);
    assertTrue(owner.provider.capture().runtimeArtAdmissionConsumed());
  }

  @Test
  void directReadyBeforeModuleKeepsEnemyAdmissionHeld() throws Exception {
    ReadyOwner owner = readyOwner(FailurePoint.NONE);
    HardwareTimingSnapshot allReady = GameServices.hardwareTiming().capture();
    GameServices.hardwareTiming().restore(
        withReady(allReady, owner.artHandle, false));
    byte[] pendingState = ZoneEventSchemaSidecar.capture(owner.events);
    InjectingEvents restored = new InjectingEvents(FailurePoint.NONE);
    restored.init(1);
    ZoneEventSchemaSidecar.restore(restored, pendingState);
    restored.discardHardwareWorkFacadesAfterRewind();

    restored.update(1, 0);

    assertAdmissionHeld(owner);
    GameServices.hardwareTiming().restore(allReady);
    restored.update(1, 1);
    assertTrue(owner.provider.capture().runtimeArtAdmissionConsumed());
  }

  @Test
  void duplicateAndStaleHandoffAcceptanceFailWithoutOwnerMutation()
      throws Exception {
    ReadyOwner owner = readyOwner(FailurePoint.NONE);
    var providerBefore = owner.provider.capture();

    assertThrows(IllegalStateException.class,
                 ()
                     -> owner.events.acceptTransferredIcz2Resources(
                         owner.directQueue, owner.chunkHandle,
                         owner.blockHandle, owner.moduleQueue, owner.artHandle,
                         owner.lease));
    assertEquals(providerBefore, owner.provider.capture());

    InjectingEvents fresh = new InjectingEvents(FailurePoint.NONE);
    fresh.init(1);
    byte[] before = ZoneEventSchemaSidecar.capture(fresh);
    RuntimeArtAdmissionLease stale = new RuntimeArtAdmissionLease(
        owner.lease.id() + 1, owner.lease.generation(),
        owner.lease.batchFingerprint(),
        RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER);
    assertThrows(IllegalStateException.class,
                 ()
                     -> fresh.acceptTransferredIcz2Resources(
                         owner.directQueue, owner.chunkHandle,
                         owner.blockHandle, owner.moduleQueue, owner.artHandle,
                         stale));
    assertArrayEquals(
        before, ZoneEventSchemaSidecar.capture(fresh),
        "stale acceptance cannot partially capture handles or lease scalars");
    assertEquals(providerBefore, owner.provider.capture());
  }

  private static void assertAdmissionHeld(ReadyOwner owner) {
    var state = owner.provider.capture();
    assertFalse(state.runtimeArtAdmissionConsumed());
    assertFalse(state.kosSubmissionArmed());
    assertEquals(List.of(), state.pendingKosOrdinals());
    assertEquals(owner.claimedBeforePublication,
                 GameServices.hardwareTiming()
                     .capture()
                     .jobs()
                     .stream()
                     .filter(HardwareTimingJob.Snapshot::claimed)
                     .count());
  }

  private static void
  assertTerminalFailure(FailurePoint point, long expectedClaims,
                        int expectedTerrainCalls, int expectedArtCalls)
      throws Exception {
    ReadyOwner owner = readyOwner(point);

    assertThrows(IllegalStateException.class, () -> owner.events.update(1, 0));

    assertEquals(owner.claimedBeforePublication + expectedClaims,
                 GameServices.hardwareTiming()
                     .capture()
                     .jobs()
                     .stream()
                     .filter(HardwareTimingJob.Snapshot::claimed)
                     .count());
    assertEquals(expectedTerrainCalls, owner.events.terrainCalls);
    assertEquals(expectedArtCalls, owner.events.artCalls);
    var providerState = owner.provider.capture();
    assertFalse(providerState.runtimeArtAdmissionConsumed());
    assertFalse(providerState.kosSubmissionArmed());
    assertEquals(java.util.List.of(), providerState.pendingKosOrdinals());

    byte[] failedState = ZoneEventSchemaSidecar.capture(owner.events);
    InjectingEvents restored = new InjectingEvents(point);
    restored.init(1);
    ZoneEventSchemaSidecar.restore(restored, failedState);
    assertThrows(IllegalStateException.class, () -> restored.update(1, 1));
    assertEquals(0, restored.terrainCalls,
                 "a restored terminal fence cannot reapply terrain");
    assertEquals(0, restored.artCalls,
                 "a restored terminal fence cannot reapply art");
    assertEquals(providerState, owner.provider.capture(),
                 "terminal updates cannot consume or replace the exact lease");
  }

  private static ReadyOwner readyOwner(FailurePoint point) throws IOException {
    HeadlessTestFixture.builder()
        .withZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, 1)
        .build();
    var rom = GameServices.rom().getRom();
    int entry = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR +
                11 * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
    int artSource = rom.read32BitAddr(entry + 4) & 0x00FF_FFFF;
    int blockSource = rom.read32BitAddr(entry + 12) & 0x00FF_FFFF;
    int chunkSource = rom.read32BitAddr(entry + 20) & 0x00FF_FFFF;
    S3kKosDecompressionQueue direct =
        S3kRuntimeArtCoordinator.current().directQueue();
    HardwareWorkHandle chunk = direct.queueStandardKos(
        rom, chunkSource, S3kKosRamDestinations.RAM_START + 0x0A00);
    HardwareWorkHandle block = direct.queueStandardKos(
        rom, blockSource, S3kKosRamDestinations.blockTableOffset(0x0408));
    S3kKosModuleQueue modules =
        S3kRuntimeArtCoordinator.current().moduleQueue();
    HardwareWorkHandle art =
        modules.queueForIczSeamlessHandoff(rom, artSource, 0x0122);
    Sonic3kObjectArtProvider provider =
        (Sonic3kObjectArtProvider)GameServices.module().getObjectArtProvider();
    RuntimeArtAdmissionLease lease = provider.prepareRuntimeArtForActTransition(
        Sonic3kZoneIds.ZONE_ICZ,
        RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER);
    InjectingEvents events = new InjectingEvents(point);
    events.init(1);
    events.acceptTransferredIcz2Resources(direct, chunk, block, modules, art,
                                          lease);

    int services = 0;
    while (!(direct.isReady(chunk) && direct.isReady(block) &&
             modules.isReady(art))) {
      HardwareBoundaryPump.service(HardwareServiceBoundary.POST_OBJECTS);
      HardwareBoundaryPump.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
      if (++services > 100_000) {
        throw new AssertionError("ICZ handoff work did not become ready");
      }
    }
    long claimedBeforePublication =
        GameServices.hardwareTiming()
            .capture()
            .jobs()
            .stream()
            .filter(HardwareTimingJob.Snapshot::claimed)
            .count();
    return new ReadyOwner(events, provider, claimedBeforePublication, chunk,
                          block, art, direct, modules, lease);
  }

  private static HardwareTimingSnapshot
  withReady(HardwareTimingSnapshot snapshot, HardwareWorkHandle handle,
            boolean ready) {
    List<HardwareTimingJob.Snapshot> jobs =
        snapshot.jobs()
            .stream()
            .map(job
                 -> job.handle().equals(handle) ? copyWithReady(job, ready)
                                                : job)
            .toList();
    return new HardwareTimingSnapshot(
        snapshot.nextOrdinals(), jobs, snapshot.admissionPolicies(),
        snapshot.recordedAdmissionActive(), snapshot.hasSubmitted(),
        snapshot.lastServicedBoundary());
  }

  private static HardwareTimingJob.Snapshot
  copyWithReady(HardwareTimingJob.Snapshot job, boolean ready) {
    return new HardwareTimingJob.Snapshot(
        job.kind(), job.romSourceAddress(), job.compressedLength(),
        job.destinationAddress(), job.destinationLength(),
        job.compressionVariant(), job.moduleCount(),
        job.exportableAcrossSegment(), job.features(), job.handle(),
        job.preparationSnapshot(), job.preparedPayload(), ready, job.claimed(),
        job.profileActive(), ready, job.assignedServiceFrames(),
        job.remainingServiceFrames(), job.eligibleBoundaries(),
        job.decisionSource(), job.serviceModel());
  }

  private enum FailurePoint { NONE, SECOND_CLAIM, TERRAIN_APPLY, ART_APPLY }

  private static final class InjectingEvents extends Sonic3kICZEvents {
    @RewindTransient(reason = "test-only failure injection")
    private final FailurePoint failurePoint;
    @RewindTransient(reason = "test-only call observation")
    private int terrainCalls;
    @RewindTransient(reason = "test-only call observation")
    private int artCalls;

    private InjectingEvents(FailurePoint failurePoint) {
      this.failurePoint = failurePoint;
    }

    @Override
    protected byte[] claimIcz2BlockPayload() {
      if (failurePoint == FailurePoint.SECOND_CLAIM) {
        throw new IllegalStateException("injected second claim failure");
      }
      return super.claimIcz2BlockPayload();
    }

    @Override
    protected void applyIcz2PreparedTerrain(byte[] chunks128x128,
                                            byte[] blocks16x16) {
      terrainCalls++;
      if (failurePoint == FailurePoint.TERRAIN_APPLY) {
        throw new IllegalStateException("injected terrain failure");
      }
      super.applyIcz2PreparedTerrain(chunks128x128, blocks16x16);
    }

    @Override
    protected void applyIcz2PreparedArt(byte[] tiles8x8) {
      artCalls++;
      if (failurePoint == FailurePoint.ART_APPLY) {
        throw new IllegalStateException("injected art failure");
      }
      super.applyIcz2PreparedArt(tiles8x8);
    }
  }

  private record
      ReadyOwner(InjectingEvents events, Sonic3kObjectArtProvider provider,
                 long claimedBeforePublication, HardwareWorkHandle chunkHandle,
                 HardwareWorkHandle blockHandle, HardwareWorkHandle artHandle,
                 S3kKosDecompressionQueue directQueue,
                 S3kKosModuleQueue moduleQueue,
                 RuntimeArtAdmissionLease lease) {}
}
