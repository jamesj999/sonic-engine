package com.openggf.game;

import java.util.List;

/**
 * The executable counterpart of the 40 represented rows in the PLC producer
 * audit.  A binding names the concrete production-owner test case that must
 * remain in the suite; the guard reads that source, so removing a parameter
 * case or its owner assertion makes the audit boundary fail mechanically.
 */
final class PlcProducerRouteRegistry {
    private static final String S1 = "src/test/java/com/openggf/game/sonic1/TestSonic1PlcProducerCoverage.java";
    private static final String S1_EVENTS = "src/test/java/com/openggf/game/sonic1/events/TestSonic1PlcProducerOwnerCoverage.java";
    private static final String S2 = "src/test/java/com/openggf/game/sonic2/TestSonic2PlcProducerCoverage.java";
    private static final String S2_BOSSES = "src/test/java/com/openggf/game/sonic2/objects/bosses/TestSonic2BossPlcProducerCoverage.java";

    record Binding(String key, String testSource, String executableAnchor) { }

    static List<Binding> bindings() {
        return List.of(
                b("S1_TITLE", S1, "titleScreenOwnerPublishesMainBeforePresentationBegins"),
                b("S1_CREDITS", S1, "creditsOwnerPrequeuesEveryTextPageInNativeRomZoneOrder"),
                b("S1_LEVEL", S1, "levelInitOwnerClearsThenPublishesHeaderPrimaryAndMain2BeforeTitleCard"),
                b("S1_GHZ_EVENT", S1_EVENTS, "GHZ3 boss"), b("S1_LZ_EVENT", S1_EVENTS, "LZ3 boss"),
                b("S1_MZ_EVENT", S1_EVENTS, "MZ3 boss"), b("S1_SLZ_EVENT", S1_EVENTS, "SLZ3 boss"),
                b("S1_SYZ_EVENT", S1_EVENTS, "SYZ3 boss"), b("S1_SBZ_EVENT", S1_EVENTS, "SBZ2 false-floor boundary"),
                b("S1_FZ_EVENT", S1_EVENTS, "FZ art boundary"),
                b("S1_RESULTS", S1, "bothNormalEndActOwnersReplaceResultsPlcAtTheirActualHandoff"),
                b("S1_TITLE_CARD", S1, "titleCardOwnerPublishesExplodeThenNativeZoneAnimalAtExitEdge"),
                b("S1_SPECIAL_RESULTS", S1, "specialStageResultsOwnerReplacesMainThenAppendsResultsArt"),
                b("S2_TITLE", S2, "titleScreenOwnerPublishesStd1BeforePresentationBegins"),
                b("S2_LEVEL", S2, "levelInitOwnerClearsThenPublishesHeaderPrimaryStd2AndTailsLifeArt"),
                b("S2_LEVEL_SECONDARY", S2,
                        "initialPresentationOwnerServicesHeaderSecondaryAcrossTheTitleCardLeaveLoop"),
                b("S2_EHZ_EVENT", S2, "EHZ boss arena"), b("S2_MTZ_EVENT", S2, "MTZ boss arena"),
                b("S2_WFZ_BOSS_EVENT", S2, "WFZ boss PLC"), b("S2_WFZ_TORNADO_EVENT", S2, "WFZ Tornado PLC"),
                b("S2_HTZ_EVENT", S2, "HTZ boss arena"), b("S2_OOZ_EVENT", S2, "OOZ boss arena"),
                b("S2_MCZ_EVENT", S2, "MCZ boss arena"), b("S2_CNZ_EVENT", S2, "CNZ boss arena"),
                b("S2_CPZ_EVENT", S2, "CPZ boss arena"), b("S2_DEZ_MECHA_EVENT", S2, "DEZ Mecha Sonic"),
                b("S2_DEZ_ROBOT_EVENT", S2, "DEZ boss"), b("S2_ARZ_EVENT", S2, "ARZ boss arena"),
                b("S2_RESULTS", S2, "bothNormalEndActOwnersReplaceResultsPlcAtTheirActualHandoff"),
                b("S2_TITLE_CARD", S2, "titleCardOwnerPublishesWaterThenZoneAnimalAtTextExit"),
                b("S2_SPECIAL_RESULTS", S2, "specialStageResultsOwnerReplacesStd1BeforeResultsLoop"),
                b("S2_SPECIAL_HANDOFF", S2, "specialStageIntroOwnerPublishesBombsAtWait2OneShotGate"),
                b("S2_EHZ_BOSS", S2_BOSSES, "boss(\"EHZ\""), b("S2_HTZ_BOSS", S2_BOSSES, "boss(\"HTZ\""),
                b("S2_ARZ_BOSS", S2_BOSSES, "boss(\"ARZ\""), b("S2_MCZ_BOSS", S2_BOSSES, "boss(\"MCZ\""),
                b("S2_CNZ_BOSS", S2_BOSSES, "boss(\"CNZ\""), b("S2_CPZ_BOSS", S2_BOSSES, "boss(\"CPZ\""),
                b("S2_MTZ_BOSS", S2_BOSSES, "boss(\"MTZ\""), b("S2_OOZ_BOSS", S2_BOSSES, "boss(\"OOZ\"")
        );
    }

    private static Binding b(String key, String source, String anchor) {
        return new Binding(key, source, anchor);
    }
}
