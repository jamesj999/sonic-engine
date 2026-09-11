package com.openggf.level.objects;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ObjectInstanceQueries {
    private ObjectInstanceQueries() {
    }

    static <T extends ObjectInstance> List<T> activeObjectsOfType(
            Map<?, ObjectInstance> activeObjects,
            Collection<ObjectInstance> dynamicObjects,
            Class<T> type) {
        List<T> matches = new ArrayList<>();
        Set<ObjectInstance> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collectMatches(activeObjects.values(), type, seen, matches);
        collectMatches(dynamicObjects, type, seen, matches);
        matches.sort((a, b) -> Integer.compare(slotOf(a), slotOf(b)));
        return matches;
    }

    private static <T extends ObjectInstance> void collectMatches(
            Collection<ObjectInstance> objects,
            Class<T> type,
            Set<ObjectInstance> seen,
            List<T> matches) {
        for (ObjectInstance instance : objects) {
            if (type.isInstance(instance) && seen.add(instance)) {
                matches.add(type.cast(instance));
            }
        }
    }

    private static int slotOf(ObjectInstance instance) {
        return instance instanceof AbstractObjectInstance object ? object.getSlotIndex() : Integer.MAX_VALUE;
    }
}
