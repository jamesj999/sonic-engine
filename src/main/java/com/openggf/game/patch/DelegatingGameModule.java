package com.openggf.game.patch;

import com.openggf.audio.GameAudioProfile;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.*;
import com.openggf.game.dataselect.DataSelectHostProfile;
import com.openggf.game.dataselect.DataSelectPresentationProvider;
import com.openggf.game.modzone.ModZoneAdapter;
import com.openggf.game.modzone.ModZoneRuntimeContribution;
import com.openggf.game.palette.CustomZonePaletteBridge;
import com.openggf.game.save.SaveSnapshotProvider;
import com.openggf.game.startup.DonatedDataSelectWarmupTask;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.LoadTimeProfile;
import com.openggf.game.timing.LoadTimeSimulationMode;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectPlacementEncoding;
import com.openggf.level.objects.PlaneSwitcherConfig;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCarryTrigger;
import com.openggf.sprites.playable.SuperStateController;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Base decorator for patches. Every {@link GameModule} method is explicitly
 * forwarded so a newly added default method cannot silently bypass the base.
 */
@ModApi
public class DelegatingGameModule implements GameModule {

    private final GameModule base;
    private final String patchId;

    protected DelegatingGameModule(GameModule base, String patchId) {
        this.base = Objects.requireNonNull(base, "base");
        this.patchId = Objects.requireNonNull(patchId, "patchId");
    }

    public final GameModule base() {
        return base;
    }

    public final String patchId() {
        return patchId;
    }

    @Override
    public String getIdentifier() {
        return base.getIdentifier();
    }

    @Override
    public Game createGame(Rom rom) {
        return base.createGame(rom);
    }

    @Override public Game createGame(GameDataSource source) { return base.createGame(source); }

    @Override public RuntimeArtCoordinator createRuntimeArtCoordinator(HardwareTimingService timing) {
        return base.createRuntimeArtCoordinator(timing);
    }

    @Override public List<com.openggf.game.rewind.RewindSnapshottable<?>> rewindAdapters() {
        return base.rewindAdapters();
    }

    @Override public LoadTimeProfile createLoadTimeProfile(
            LoadTimeSimulationMode mode, LoadTimeProfile profiled,
            Consumer<String> warningSink) {
        return base.createLoadTimeProfile(mode, profiled, warningSink);
    }

    @Override public LoadTimeProfile createLoadTimeProfile(
            LoadTimeSimulationMode mode, Consumer<String> warningSink) {
        return base.createLoadTimeProfile(mode, warningSink);
    }

    @Override public int getRomAct(int logicalZone, int act, int levelZoneIndex) {
        return base.getRomAct(logicalZone, act, levelZoneIndex);
    }

    @Override public TouchResponseTable createTouchResponseTable(GameDataSource source)
            throws java.io.IOException { return base.createTouchResponseTable(source); }

    @Override public PlayableCharacterRegistry getPlayableCharacterRegistry() {
        return base.getPlayableCharacterRegistry();
    }

    @Override public ModZoneAdapter getModZoneAdapter() {
        return base.getModZoneAdapter();
    }

    @Override public GameplayPolicyProvider getGameplayPolicyProvider() {
        return base.getGameplayPolicyProvider();
    }

    @Override public CustomZonePaletteBridge createCustomZonePaletteBridge(
            ModZoneRuntimeContribution contribution, com.openggf.level.Level level,
            ObjectArtProvider objectArtProvider) {
        return base.createCustomZonePaletteBridge(contribution, level, objectArtProvider);
    }

    @Override
    public ObjectRegistry createObjectRegistry() {
        return base.createObjectRegistry();
    }

    @Override
    public ObjectPlacementEncoding getObjectPlacementEncoding() {
        return base.getObjectPlacementEncoding();
    }

    @Override
    public GameAudioProfile getAudioProfile() {
        return base.getAudioProfile();
    }

    @Override
    public TouchResponseTable createTouchResponseTable(RomByteReader romReader) {
        return base.createTouchResponseTable(romReader);
    }

    @Override
    public int getPlaneSwitcherObjectId() {
        return base.getPlaneSwitcherObjectId();
    }

    @Override
    public int getCheckpointObjectId() {
        return base.getCheckpointObjectId();
    }

    @Override
    public PlaneSwitcherConfig getPlaneSwitcherConfig() {
        return base.getPlaneSwitcherConfig();
    }

    @Override
    public LevelEventProvider getLevelEventProvider() {
        return base.getLevelEventProvider();
    }

    @Override
    public com.openggf.game.profiles.trace.TracePlaybackProfile getTracePlaybackProfile() {
        return base.getTracePlaybackProfile();
    }

    @Override
    public com.openggf.level.InitialFixedSstDispatcher createInitialFixedSstDispatcher(
            com.openggf.sprites.managers.SpriteManager sprites,
            com.openggf.level.objects.ObjectManager objects,
            com.openggf.game.ZoneFeatureProvider zoneFeatures) {
        return base.createInitialFixedSstDispatcher(sprites, objects, zoneFeatures);
    }

    @Override
    public RespawnState createRespawnState() {
        return base.createRespawnState();
    }

    @Override
    public LevelState createLevelState() {
        return base.createLevelState();
    }

    @Override
    public TitleCardProvider getTitleCardProvider() {
        return base.getTitleCardProvider();
    }

    @Override
    public ZoneRegistry getZoneRegistry() {
        return base.getZoneRegistry();
    }

    @Override
    public MusicReference getLevelMusicReference(int zoneIndex, int actIndex) {
        return base.getLevelMusicReference(zoneIndex, actIndex);
    }

    @Override
    public com.openggf.level.Level loadLevelOverride(int levelIndex) throws java.io.IOException {
        return base.loadLevelOverride(levelIndex);
    }

    @Override
    public com.openggf.level.rings.RingSpriteSheet getAdditiveLevelRingSpriteSheet()
            throws java.io.IOException {
        return base.getAdditiveLevelRingSpriteSheet();
    }

    @Override
    public int[] getBackgroundScrollOverride(int levelIndex, int cameraX, int cameraY) {
        return base.getBackgroundScrollOverride(levelIndex, cameraX, cameraY);
    }

    @Override
    public SpecialStageProvider getSpecialStageProvider() {
        return base.getSpecialStageProvider();
    }

    @Override
    public int getSpecialStageCycleCount() {
        return base.getSpecialStageCycleCount();
    }

    @Override
    public GameRng.Flavour rngFlavour() {
        return base.rngFlavour();
    }

    @Override
    public int getChaosEmeraldCount() {
        return base.getChaosEmeraldCount();
    }

    @Override
    public BonusStageProvider getBonusStageProvider() {
        return base.getBonusStageProvider();
    }

    @Override
    public ScrollHandlerProvider getScrollHandlerProvider() {
        return base.getScrollHandlerProvider();
    }

    @Override
    public ZoneFeatureProvider getZoneFeatureProvider() {
        return base.getZoneFeatureProvider();
    }

    @Override
    public WaterDataProvider getWaterDataProvider() {
        return base.getWaterDataProvider();
    }

    @Override
    public RomOffsetProvider getRomOffsetProvider() {
        return base.getRomOffsetProvider();
    }

    @Override
    public DebugModeProvider getDebugModeProvider() {
        return base.getDebugModeProvider();
    }

    @Override
    public DebugOverlayProvider getDebugOverlayProvider() {
        return base.getDebugOverlayProvider();
    }

    @Override
    public ZoneArtProvider getZoneArtProvider() {
        return base.getZoneArtProvider();
    }

    @Override
    public ObjectArtProvider getObjectArtProvider() {
        return base.getObjectArtProvider();
    }

    @Override
    public ContinueScreenProvider createContinueScreenProvider() {
        return base.createContinueScreenProvider();
    }

    @Override
    public TitleScreenProvider getTitleScreenProvider() {
        return base.getTitleScreenProvider();
    }

    @Override
    public DataSelectProvider getDataSelectProvider() {
        return base.getDataSelectProvider();
    }

    @Override
    public DataSelectPresentationProvider getDataSelectPresentationProvider() {
        return base.getDataSelectPresentationProvider();
    }

    @Override
    public DataSelectHostProfile getDataSelectHostProfile() {
        return base.getDataSelectHostProfile();
    }

    @Override
    public SaveSnapshotProvider getSaveSnapshotProvider() {
        return base.getSaveSnapshotProvider();
    }

    @Override
    public LevelSelectProvider getLevelSelectProvider() {
        return base.getLevelSelectProvider();
    }

    @Override
    public PhysicsProvider getPhysicsProvider() {
        return base.getPhysicsProvider();
    }

    @Override
    public com.openggf.game.rules.GameRules getRules() {
        return base.getRules();
    }

    @Override
    public SuperStateController createSuperStateController(AbstractPlayableSprite player) {
        return base.createSuperStateController(player);
    }

    @Override
    public void onLevelLoad() {
        base.onLevelLoad();
    }

    @Override
    public void resetModuleScopedState() {
        base.resetModuleScopedState();
    }

    @Override
    public SidekickCarryTrigger getSidekickCarryTrigger() {
        return base.getSidekickCarryTrigger();
    }

    @Override
    public void applyPlaneSwitching(AbstractPlayableSprite player) {
        base.applyPlaneSwitching(player);
    }

    @Override
    public int getRemappedFeatureZone(int logicalZone, int act, int levelZoneIndex) {
        return base.getRemappedFeatureZone(logicalZone, act, levelZoneIndex);
    }

    @Override
    public int getRemappedFeatureAct(int logicalZone, int act, int levelZoneIndex) {
        return base.getRemappedFeatureAct(logicalZone, act, levelZoneIndex);
    }

    @Override
    public boolean hasSeparateTailsTailArt() {
        return base.hasSeparateTailsTailArt();
    }

    @Override
    public SpriteArtSet loadTailsTailArt() {
        return base.loadTailsTailArt();
    }

    @Override
    public int getTailsTailVramBase() {
        return base.getTailsTailVramBase();
    }

    @Override
    public EndingProvider getEndingProvider() {
        return base.getEndingProvider();
    }

    @Override
    public boolean hasTrailInvincibilityStars() {
        return base.hasTrailInvincibilityStars();
    }

    @Override
    public Function<PlayableEntity, AbstractObjectInstance> getInvincibilityStarsFactory() {
        return base.getInvincibilityStarsFactory();
    }

    @Override
    public boolean supportsSidekick() {
        return base.supportsSidekick();
    }

    @Override
    public boolean isSidekickSuppressedForZone(int zoneId) {
        return base.isSidekickSuppressedForZone(zoneId);
    }

    @Override
    public LevelInitProfile getLevelInitProfile() {
        return base.getLevelInitProfile();
    }

    @Override
    public DonorCapabilities getDonorCapabilities() {
        return base.getDonorCapabilities();
    }

    @Override
    public CrossGameDonorProvider getCrossGameDonorProvider() {
        return base.getCrossGameDonorProvider();
    }

    @Override
    public void applySeamlessMutation(LevelManager levelManager, String mutationKey) {
        base.applySeamlessMutation(levelManager, mutationKey);
    }

    @Override
    public <T> T getGameService(Class<T> type) {
        return base.getGameService(type);
    }

    @Override
    public Optional<DonatedDataSelectWarmupTask> getDonatedDataSelectWarmupTask() {
        return base.getDonatedDataSelectWarmupTask();
    }

    @Override
    public GameId getGameId() {
        return base.getGameId();
    }

    @Override
    public String getGameCode() {
        return base.getGameCode();
    }

    @Override
    public int explosionInitialAnimDuration() {
        return base.explosionInitialAnimDuration();
    }

    @Override
    public int resolveAnimationId(CanonicalAnimation canonical) {
        return base.resolveAnimationId(canonical);
    }
}
