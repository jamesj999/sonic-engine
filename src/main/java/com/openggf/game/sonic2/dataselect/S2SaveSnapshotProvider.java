package com.openggf.game.sonic2.dataselect;

import com.openggf.game.save.RuntimeSaveContext;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SaveSnapshotProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class S2SaveSnapshotProvider implements SaveSnapshotProvider {
    @Override
    public Map<String, Object> capture(SaveReason reason, RuntimeSaveContext context) {
        boolean hasLiveState = context.hasLiveGameplayState();
        if (reason != SaveReason.NEW_SLOT_START && !hasLiveState) {
            throw new IllegalStateException("Save reason " + reason + " requires a live runtime/gameplay mode");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        var save = context.saveSessionContext();
        int zone = !hasLiveState ? save.startZone()
                : context.levelManager().getCurrentZone();
        int act = !hasLiveState ? save.startAct()
                : context.levelManager().getCurrentAct();
        int lives = !hasLiveState ? 3 : context.gameState().getLives();
        List<Integer> chaosEmeralds = !hasLiveState ? List.of()
                : context.gameState().getCollectedChaosEmeraldIndices();
        boolean clear = save.isClear();
        payload.put("zone", zone);
        payload.put("act", act);
        payload.put("mainCharacter", save.selectedTeam().mainCharacter());
        payload.put("sidekicks", save.selectedTeam().sidekicks());
        payload.put("lives", lives);
        payload.put("chaosEmeralds", chaosEmeralds);
        payload.put("clear", clear);
        payload.put("progressCode", zone + 1);
        payload.put("clearState", clear ? 1 : 0);
        return payload;
    }
}
