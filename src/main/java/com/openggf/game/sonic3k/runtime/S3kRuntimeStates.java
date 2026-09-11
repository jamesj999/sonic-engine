package com.openggf.game.sonic3k.runtime;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.zone.ZoneRuntimeRegistry;

import java.util.Optional;

public final class S3kRuntimeStates {
    private S3kRuntimeStates() {
    }

    public static Optional<AizZoneRuntimeState> currentAiz(ZoneRuntimeRegistry registry) {
        return registry.currentAs(AizZoneRuntimeState.class);
    }

    public static Optional<HczZoneRuntimeState> currentHcz(ZoneRuntimeRegistry registry) {
        return registry.currentAs(HczZoneRuntimeState.class);
    }

    public static Optional<CnzZoneRuntimeState> currentCnz(ZoneRuntimeRegistry registry) {
        return registry.currentAs(CnzZoneRuntimeState.class);
    }

    public static Optional<MgzZoneRuntimeState> currentMgz(ZoneRuntimeRegistry registry) {
        return registry.currentAs(MgzZoneRuntimeState.class);
    }

    public static Optional<IczZoneRuntimeState> currentIcz(ZoneRuntimeRegistry registry) {
        return registry.currentAs(IczZoneRuntimeState.class);
    }

    public static Optional<LbzZoneRuntimeState> currentLbz(ZoneRuntimeRegistry registry) {
        return registry.currentAs(LbzZoneRuntimeState.class);
    }

    public static Optional<MhzZoneRuntimeState> currentMhz(ZoneRuntimeRegistry registry) {
        return registry.currentAs(MhzZoneRuntimeState.class);
    }

    public static PlayerCharacter resolvePlayerCharacter(ZoneRuntimeRegistry registry,
                                                         SonicConfigurationService config) {
        if (registry != null) {
            var current = registry.current();
            if (current instanceof S3kZoneRuntimeState s3kState) {
                return s3kState.playerCharacter();
            }
        }

        return ActiveGameplayTeamResolver.resolvePlayerCharacter(config);
    }
}
