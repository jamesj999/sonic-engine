package com.openggf.game.sonic1.objects;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TestSonic1EndingEmeraldsObjectInstance {

    @Test
    void endingEmeraldsDoNotDeclareStaticInstanceCollections() {
        for (var field : Sonic1EndingEmeraldsObjectInstance.class.getDeclaredFields()) {
            boolean staticField = Modifier.isStatic(field.getModifiers());
            boolean instanceCollection = Collection.class.isAssignableFrom(field.getType())
                    || Map.class.isAssignableFrom(field.getType())
                    || (field.getType().isArray()
                    && Sonic1EndingEmeraldsObjectInstance.class.isAssignableFrom(field.getType().getComponentType()));

            assertFalse(staticField && instanceCollection,
                    "Ending emerald instances must not be retained in process-wide collections: " + field.getName());
        }
    }
}
