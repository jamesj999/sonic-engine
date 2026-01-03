package uk.co.jamesj999.sonic.level.objects;

@FunctionalInterface
public interface ObjectFactory {
    ObjectInstance create(ObjectSpawn spawn, ObjectRegistry registry);
}
