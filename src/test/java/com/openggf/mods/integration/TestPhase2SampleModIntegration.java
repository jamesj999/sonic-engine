package com.openggf.mods.integration;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.*;
import com.openggf.game.dataselect.DataSelectSessionController;
import com.openggf.game.patch.*;
import com.openggf.game.save.SaveManager;
import com.openggf.game.session.*;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.dataselect.S2DataSelectProfile;
import com.openggf.game.sonic2.dataselect.S2SavedZone;
import com.openggf.io.ModInputLimits;
import com.openggf.data.RomManager;
import com.openggf.level.objects.*;
import com.openggf.level.Palette;
import com.openggf.mods.*;
import com.openggf.mods.code.*;
import com.openggf.tools.HeadlessGameBoot;
import com.openggf.tools.modsdk.GgfModCli;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.physics.GroundSensor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class TestPhase2SampleModIntegration {
    @TempDir Path temp;

    @BeforeEach void resetState(){TestEnvironment.resetAll();}

    @AfterEach void cleanup(){
        GroundSensor.setLevelManager(null);
        SessionManager.clear();
        GameModuleRegistry.reset();
        TestEnvironment.resetAll();
    }

    @Test void realCreatorSampleBuildsAndRegistersAuthoredResourcesWithoutRom() throws Exception {
        Path jar = buildInitializedSample();
        try (CatalogFixture fixture = load(jar, true)) {
            GameModule base = new Sonic2GameModule();
            GameModule resolved = resolver(base, fixture).resolveForLaunch(base,
                    new GameplayLaunchRequest("s2", "sonic", List.of()),
                    ModuleResolutionService.LaunchPolicy.STANDARD);
            assertEquals(12, resolved.getZoneRegistry().getZoneCount());
            assertEquals(11, resolved.getZoneRegistry().resolveZoneKey(
                    ZoneKey.mod("phase2-sample", "sample-zone")).orElseThrow());
            assertEquals(0x400, resolved.getZoneRegistry().getLevelDataForZone(11)
                    .getFirst().levelIndex());
            assertTrue(AbstractBadnikInstance.class.isAssignableFrom(fixture.runtime().loadOwned(
                    "phase2-sample", "example.phase2sample.SampleBadnik")));
            assertEquals(15, resolved.getObjectArtProvider().getSheet(
                    "phase2-sample:sample-badnik").getPatterns()[0].getPixel(1, 0));
        }
    }

    @Test
    @RequiresRom(SonicGame.SONIC_2)
    void realCreatorSampleLoadsZoneObjectRewindAndKeyedSave() throws Exception {
        assertTrue(Files.isRegularFile(Path.of("src/test/resources/mods/sample-mod-src/build.sh")));
        Path jar=buildInitializedSample();
        try(CatalogFixture fixture=load(jar,true);com.openggf.data.Rom rom=new com.openggf.data.Rom()){
            var romFile = RomTestUtils.ensureSonic2RomAvailable();
            assertNotNull(romFile);
            assertTrue(rom.open(romFile.getAbsolutePath()));
            GameModule base=new Sonic2GameModule();base.createGame(rom);
            ModuleResolutionService resolver=resolver(base,fixture);
            GameModule resolved=resolver.resolveForLaunch(base,new GameplayLaunchRequest("s2","sonic",List.of()),
                    ModuleResolutionService.LaunchPolicy.STANDARD);
            assertEquals(11,base.getZoneRegistry().getZoneCount());
            assertEquals(12,resolved.getZoneRegistry().getZoneCount());
            assertEquals(base.getZoneRegistry().getZoneName(0),resolved.getZoneRegistry().getZoneName(0));
            assertEquals(base.getZoneRegistry().getLevelDataForZone(0),
                    resolved.getZoneRegistry().getLevelDataForZone(0));
            assertNull(resolved.loadLevelOverride(0),"Enabled mod must not intercept a stock level id");
            assertEquals(11,resolved.getZoneRegistry().resolveZoneKey(
                    ZoneKey.mod("phase2-sample","sample-zone")).orElseThrow());
            var level=resolved.loadLevelOverride(0x400);assertNotNull(level);assertEquals(1,level.getObjects().size());
            ObjectSpawn spawn=level.getObjects().getFirst();assertEquals("phase2-sample:sample-badnik",spawn.objectKey());
            assertEquals(new MusicReference.Stock(129),resolved.getLevelMusicReference(11,0));
            var sampleSheet = resolved.getObjectArtProvider().getSheet(
                    "phase2-sample:sample-badnik");
            assertEquals(15, sampleSheet.getPatterns()[0].getPixel(1,0));
            assertEquals(1, sampleSheet.getPaletteIndex());
            assertEquals(1, (sampleSheet.getPaletteIndex()
                    + sampleSheet.getFrame(0).pieces().getFirst().paletteIndex()) & 0x3,
                    "sample badnik must render on creator-owned line 1");
            assertEquals("phase2-sample:sample-badnik",resolved.createObjectRegistry()
                    .editorPreviewArtKey("phase2-sample:sample-badnik").orElseThrow());

            EngineContext previous=EngineServices.current();EngineContext injected=withResolver(
                    previous,resolver,isolatedRomManager());
            try{EngineServices.configure(injected);injected.roms().setRom(rom);
                GameplayModeContext booted=HeadlessGameBoot.openResolvedSessionForBoot(injected,base,
                    ModuleResolutionService.LaunchPolicy.STANDARD);
                GameplaySessionFactory.attachManagers(booted,injected);
                injected.graphics().initHeadless();
                var team=GameplayTeamBootstrap.registerActiveTeam(
                        SessionManager.requireCurrentGameModule(),GameServices.sprites(),injected.configuration());
                GameServices.camera().setFocusedSprite(team.mainSprite());
                GameServices.camera().setFrozen(false);
                GameServices.level().loadZoneAndAct(11,0);
                assertEquals(11,SessionManager.requireCurrentGameModule().getZoneRegistry()
                        .resolveZoneKey(ZoneKey.mod("phase2-sample","sample-zone")).orElseThrow());
                assertEquals(11,GameServices.level().getCurrentZone());
                assertNotNull(GameServices.level().getCurrentLevel());
                assertEquals("phase2-sample:sample-badnik",
                        GameServices.level().getCurrentLevel().getObjects().getFirst().objectKey());
                var playable=GameServices.level().getCurrentLevel();
                assertEquals(1,Byte.toUnsignedInt(playable.getMap().getValue(0,0,1)));
                assertEquals(1,playable.getBlock(1).getChunkDesc(0,0).getChunkIndex());
                assertEquals(1,playable.getChunk(1).getSolidTileIndex());
                assertEquals(1, playable.getChunk(1).getPatternDesc(0, 0).getPaletteIndex());
                assertEquals(16,Byte.toUnsignedInt(playable.getSolidTile(1).getHeightAt((byte) 0)));
                Palette expectedCharacter = new Palette();
                expectedCharacter.fromSegaFormat(rom.readBytes(
                        Sonic2Constants.SONIC_TAILS_PALETTE_ADDR, Palette.PALETTE_SIZE_IN_ROM));
                assertTrue(expectedCharacter.dataEquals(playable.getPalette(0)),
                        "host-owned line 0 must remain the ROM character palette");
                var terrainColor=playable.getPalette(1).getColor(1);
                assertTrue((terrainColor.r&0xFF)>0 && (terrainColor.g&0xFF)>0 && (terrainColor.b&0xFF)>0,
                        "Authored floor pattern must use a visible palette color");
                var objectColor=playable.getPalette(1).getColor(15);
                assertTrue((objectColor.r&0xFF)>0 && (objectColor.g&0xFF)>0 && (objectColor.b&0xFF)>0,
                        "Sample badnik's white pixels must use a visible palette color");
                GroundSensor.setLevelManager(GameServices.level());
                new HeadlessTestRunner(team.mainSprite()).stepIdleFrames(30);
                assertEquals(com.openggf.game.GroundMode.GROUND,team.mainSprite().getGroundMode());
                assertTrue(team.mainSprite().getCentreY()<128,"Player must land on the authored floor");
            }finally{SessionManager.clear();EngineServices.configure(previous);}

            ObjectRegistry registry=resolved.createObjectRegistry();ObjectManager[] holder=new ObjectManager[1];
            StubObjectServices services=new StubObjectServices(){@Override public ObjectManager objectManager(){return holder[0];}};
            holder[0]=new ObjectManager(List.of(),registry,0,null,null,null,null,services);
            holder[0].setRewindClassResolver(new ModClassResolver(fixture.runtime(),getClass().getClassLoader()));
            AbstractBadnikInstance badnik=(AbstractBadnikInstance)holder[0].createDynamicObject(()->registry.create(spawn));
            ClassLoader ownerLoader=fixture.runtime().loadOwned("phase2-sample",
                    "example.phase2sample.SampleBadnik").getClassLoader();
            assertSame(ownerLoader,badnik.getClass().getClassLoader());
            assertNotSame(getClass().getClassLoader(),ownerLoader);
            int before=currentX(badnik);badnik.update(1,null);assertNotEquals(before,currentX(badnik));
            var snapshot=holder[0].rewindSnapshottable().capture();
            badnik.onPlayerAttack(null,new TouchResponseResult(1,8,8,TouchCategory.ENEMY));assertTrue(badnik.isDestroyed());
            holder[0].rewindSnapshottable().restore(snapshot);
            AbstractBadnikInstance restored=holder[0].getActiveObjects().stream()
                    .filter(o->o.getClass().getName().endsWith("SampleBadnik"))
                    .map(AbstractBadnikInstance.class::cast).findFirst().orElseThrow();
            assertNotSame(badnik,restored);assertSame(ownerLoader,restored.getClass().getClassLoader());
            assertFalse(restored.isDestroyed());assertEquals(currentX(badnik),currentX(restored));

            Path saves=temp.resolve("saves");SaveManager save=new SaveManager(saves);Map<String,Object> payload=basePayload();
            S2SavedZone.write(payload,ZoneKey.mod("phase2-sample","sample-zone"));save.writeSlot("s2",1,payload);
            S2DataSelectProfile enabledProfile=(S2DataSelectProfile)resolved.getDataSelectHostProfile();
            var enabledSummary=save.readSlotSummary("s2",1,enabledProfile);
            DataSelectSessionController enabled=new DataSelectSessionController(enabledProfile);
            enabled.loadAvailableTeams(null);enabled.loadSlotSummaries(List.of(enabledSummary));enabled.menuModel().setSelectedRow(1);
            assertEquals(11,enabled.confirmSelection().zone());
            List<com.openggf.game.sonic2.dataselect.S2SaveFinding> findings=new ArrayList<>();
            S2DataSelectProfile disabledProfile=new S2DataSelectProfile(base::getZoneRegistry,findings::add);
            var disabledSummary=save.readSlotSummary("s2",1,disabledProfile);DataSelectSessionController disabled=new DataSelectSessionController(disabledProfile);
            disabled.loadAvailableTeams(null);disabled.loadSlotSummaries(List.of(disabledSummary));disabled.menuModel().setSelectedRow(1);
            assertEquals(0,disabled.confirmSelection().zone());assertTrue(Files.exists(saves.resolve("s2/slot1.json")));
            assertEquals("S2_MOD_ZONE_MISSING",findings.getFirst().code());

            assertSame(base,resolver.resolveForLaunch(base,new GameplayLaunchRequest("s2","sonic",List.of()),
                    ModuleResolutionService.LaunchPolicy.DETERMINISTIC));
        }
    }

    @Test void realCliPackagedDataOnlyReskinOverridesStockKeyAndModsOffIsIdentity() throws Exception {
        Path jar=buildReskin();try(CatalogFixture fixture=load(jar,false)){
            GameModule base=new Sonic2GameModule();var baseProvider=base.getObjectArtProvider();
            ModuleResolutionService resolver=resolver(base,fixture);GameModule resolved=resolver.resolveForLaunch(base,
                    new GameplayLaunchRequest("s2","sonic",List.of()),ModuleResolutionService.LaunchPolicy.STANDARD);
            assertEquals(1,resolved.getObjectArtProvider().getSheet("EndSign").getPatterns()[0].getPixel(0,0));
            assertSame(baseProvider,base.getObjectArtProvider());
            GameModule disabled=resolver.resolveForLaunch(base,new GameplayLaunchRequest("s2","sonic",List.of()),
                    ModuleResolutionService.LaunchPolicy.DETERMINISTIC);
            assertSame(base,disabled);assertSame(baseProvider,disabled.getObjectArtProvider());
        }
    }

    private Path buildInitializedSample() throws Exception{
        Properties sample=new Properties();try(var input=Files.newInputStream(
                Path.of("src/test/resources/mods/sample-mod-src/sample.properties"))){sample.load(input);}
        assertEquals("phase2-sample",sample.getProperty("id"));
        assertEquals("example.phase2sample",sample.getProperty("package"));
        Path project=temp.resolve("sample-project");
        Path regenerated=temp.resolve("regenerated-scaffold");
        assertEquals(0,GgfModCli.run(new String[]{"init",regenerated.toString(),"--id",sample.getProperty("id"),
                "--package",sample.getProperty("package")},new PrintStream(new ByteArrayOutputStream())));
        Path checkedFixture = materializeCheckedFixture();
        assertTreesEqual(checkedFixture, regenerated);
        Path engine=temp.resolve("engine-local.jar"),sdk=temp.resolve("openggf-mod-sdk-local.jar");
        Path sessionClasses = TestSessionOutputPaths.compiledClasses();
        createJar(sessionClasses,engine,entry->!entry.startsWith("com/openggf/tools/modsdk/")
                && !entry.startsWith("META-INF/openggf-mod-sdk/"));
        createJar(sessionClasses,sdk,entry->entry.startsWith("com/openggf/tools/modsdk/")
                || entry.startsWith("META-INF/openggf-mod-sdk/"));
        try(JarFile engineJar=new JarFile(engine.toFile());JarFile sdkJar=new JarFile(sdk.toFile())){
            assertNotNull(engineJar.getEntry("com/openggf/mods/code/ModContext.class"));
            assertNull(engineJar.getEntry("com/openggf/tools/modsdk/GgfModCli.class"));
            assertNotNull(sdkJar.getEntry("com/openggf/tools/modsdk/GgfModCli.class"));
            assertNull(sdkJar.getEntry("com/openggf/mods/code/ModContext.class"));
        }
        Path fixture=Path.of("src/test/resources/mods/sample-mod-src").toAbsolutePath();
        List<String> command=System.getProperty("os.name","").startsWith("Windows")
                ? List.of("powershell.exe","-NoProfile","-File",fixture.resolve("build.ps1").toString(),
                        engine.toAbsolutePath().toString(),sdk.toAbsolutePath().toString(),project.toString())
                : List.of("sh",fixture.resolve("build.sh").toString(),engine.toAbsolutePath().toString(),
                        sdk.toAbsolutePath().toString(),project.toString());
        runProcess(command,Path.of("").toAbsolutePath());
        String mvn=System.getProperty("os.name","").startsWith("Windows")?"mvn.cmd":"mvn";
        runProcess(List.of(mvn,"-q","-f",project.resolve("pom.xml").toString(),"package",
                "-Dopenggf.engine.jar="+engine.toAbsolutePath(),"-Dopenggf.sdk.jar="+sdk.toAbsolutePath()),
                Path.of("").toAbsolutePath());
        Path jar=project.resolve("target/phase2-sample-mod.jar");assertTrue(Files.isRegularFile(jar));return jar;
    }

    private static void runProcess(List<String> command,Path directory)throws Exception{
        Process process=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),output);
    }

    private static void assertTreesEqual(Path expected,Path actual)throws Exception{
        List<Path> expectedFiles;try(var walk=Files.walk(expected)){expectedFiles=walk.filter(Files::isRegularFile)
                .map(expected::relativize).sorted().toList();}
        List<Path> actualFiles;try(var walk=Files.walk(actual)){actualFiles=walk.filter(Files::isRegularFile)
                .map(actual::relativize).sorted().toList();}
        assertEquals(expectedFiles,actualFiles,"Checked sample must match real ggfmod init inventory");
        for(Path relative:expectedFiles)assertEquals(-1,Files.mismatch(expected.resolve(relative),actual.resolve(relative)),
                ()->"Checked sample differs from real ggfmod init at "+relative);
    }

    private Path materializeCheckedFixture() throws Exception {
        Path source = Path.of("src/test/resources/mods/sample-mod-src/project");
        Path materialized = temp.resolve("checked-sample-fixture");
        try (var walk = Files.walk(source)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("binary-assets.properties"))
                    .toList()) {
                Path destination = materialized.resolve(source.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination);
            }
        }
        Path encoded = source.resolve("src/main/mod/level-source/binary-assets.properties");
        Properties assets = new Properties();
        try (var input = Files.newInputStream(encoded)) {
            assets.load(input);
        }
        Path levelRoot = materialized.resolve("src/main/mod/level-source");
        for (String name : assets.stringPropertyNames()) {
            Files.write(levelRoot.resolve(name), Base64.getDecoder().decode(assets.getProperty(name)));
        }
        return materialized;
    }

    private Path buildReskin() throws Exception{
        Path source=Path.of("src/test/resources/mods/sample-reskin-src"),staging=temp.resolve("reskin-stage");
        Files.createDirectories(staging.resolve("META-INF"));Files.copy(source.resolve("META-INF/openggf-mod.yaml"),staging.resolve("META-INF/openggf-mod.yaml"));
        Path png=staging.resolve("reskin.png");Files.write(png,Base64.getDecoder().decode(Files.readString(source.resolve("reskin.png.base64")).trim()));
        assertEquals(0,GgfModCli.run(new String[]{"convert","art","--image",png.toString(),"--sheet",
                source.resolve("reskin-sheet.yaml").toString(),"--out",staging.resolve("art/reskin.ggfs").toString()},System.out));
        Path jar=temp.resolve("phase2-reskin.jar");assertEquals(0,GgfModCli.run(new String[]{"package","--input",staging.toString(),"--out",jar.toString()},System.out));return jar;
    }

    private CatalogFixture load(Path jar,boolean code)throws Exception{
        Path repo=temp.resolve("repo-"+jar.getFileName());Files.createDirectories(repo);Path packed=repo.resolve(jar.getFileName());Files.copy(jar,packed);
        var scanned=new DefaultModRepositoryScanner().scan(repo.toAbsolutePath().normalize());
        var validated=new ModCatalogValidator(repo.toAbsolutePath().normalize(),ModInputLimits.production(),(game,id)->true).validate(scanned);
        ModDescriptor descriptor=(ModDescriptor)validated.entries().getFirst();assertFalse(descriptor.hasErrors(),descriptor.findings()::toString);
        ModState state=new ModState(1,List.of(new ModState.Entry(descriptor.manifest().id(),true,0,code,code?descriptor.sha256():null)));
        ModCatalog catalog=new EffectiveCatalogBuilder().build(validated.entries(),state);assertEquals(1,catalog.effective().orderedEnabled().size());
        ModRuntime runtime=new ModClassLoaderFactory(getClass().getClassLoader()).create(catalog.effective(),code?Set.of(descriptor.manifest().id()):Set.of());
        runtime.installFaultBoundary(new ModFaultBoundary(Map.of(), new ModRuntimeFindingStore(),
                owners -> new ModStateSaveResult.Saved(), owners -> {}));
        return new CatalogFixture(runtime,catalog.effective(),runtime.newRegistrationPlan());
    }

    private ModuleResolutionService resolver(GameModule base,CatalogFixture fixture){
        return new ModuleResolutionService(List.of(),new EffectiveCatalogPatchEnablement(fixture.effective()),
                new LogicalRomResolver(()->null),SonicConfigurationService.createStandalone(),ignored->fixture.plan());
    }

    private static EngineContext withResolver(EngineContext old,ModuleResolutionService resolver,
                                              RomManager roms){
        return new EngineContext(SonicConfigurationService.createStandalone(),old.graphics(),old.audio(),roms,old.profiler(),
                old.debugOverlay(),old.playbackDebug(),old.romDetection(),old.crossGameFeatures(),resolver);
    }
    private static RomManager isolatedRomManager()throws Exception{
        var constructor=RomManager.class.getDeclaredConstructor();constructor.setAccessible(true);return constructor.newInstance();
    }
    private static int currentX(AbstractBadnikInstance value)throws Exception{var field=AbstractBadnikInstance.class.getDeclaredField("currentX");field.setAccessible(true);return field.getInt(value);}
    private static Map<String,Object> basePayload(){Map<String,Object> p=new LinkedHashMap<>();p.put("act",0);p.put("mainCharacter","sonic");p.put("sidekicks",List.of());p.put("lives",3);p.put("chaosEmeralds",List.of());p.put("clear",false);p.put("progressCode",1);p.put("clearState",0);return p;}
    private static void createJar(Path root,Path jar,Predicate<String> include)throws Exception{try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(root)){for(Path file:paths.filter(Files::isRegularFile).sorted().toList()){String entry=root.relativize(file).toString().replace('\\','/');if(!include.test(entry))continue;out.putNextEntry(new JarEntry(entry));Files.copy(file,out);out.closeEntry();}}}
    private record CatalogFixture(ModRuntime runtime,EffectiveModCatalog effective,ModuleResolutionService.PatchPlan plan)implements AutoCloseable{public void close()throws Exception{runtime.close();}}
}
