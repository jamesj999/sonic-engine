package uk.co.jamesj999.sonic.level.objects;

import java.util.List;

public interface ObjectRegistry {
    ObjectInstance create(ObjectSpawn spawn);

    void reportCoverage(List<ObjectSpawn> spawns);

    String getPrimaryName(int objectId);

    default List<String> getAliases(int objectId) {
        return List.of();
    }
}
