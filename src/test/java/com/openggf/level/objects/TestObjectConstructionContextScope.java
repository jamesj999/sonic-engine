package com.openggf.level.objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestObjectConstructionContextScope {

    @AfterEach
    void clearThreadLocals() {
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.remove();
        AbstractObjectInstance.PRE_ALLOCATED_SLOT.remove();
    }

    @Test
    void nestedScopedContextRestoresOuterServicesAndSlotAfterThrow() {
        ObjectServices outerServices = new TestObjectServices();
        ObjectServices innerServices = new TestObjectServices();

        ObjectConstructionContext.with(outerServices, 12, () -> {
            assertSame(outerServices, AbstractObjectInstance.CONSTRUCTION_CONTEXT.get());
            assertEquals(12, AbstractObjectInstance.PRE_ALLOCATED_SLOT.get());

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> ObjectConstructionContext.with(innerServices, 34, () -> {
                        assertSame(innerServices, AbstractObjectInstance.CONSTRUCTION_CONTEXT.get());
                        assertEquals(34, AbstractObjectInstance.PRE_ALLOCATED_SLOT.get());
                        throw new IllegalStateException("inner failed");
                    }));

            assertEquals("inner failed", thrown.getMessage());
            assertSame(outerServices, AbstractObjectInstance.CONSTRUCTION_CONTEXT.get());
            assertEquals(12, AbstractObjectInstance.PRE_ALLOCATED_SLOT.get());
        });
    }

    @Test
    void scopedContextClearsServicesAndSlotWhenNoPreviousValuesExist() {
        ObjectServices services = new TestObjectServices();

        String result = ObjectConstructionContext.with(services, 7, () -> {
            assertSame(services, AbstractObjectInstance.CONSTRUCTION_CONTEXT.get());
            assertEquals(7, AbstractObjectInstance.PRE_ALLOCATED_SLOT.get());
            return "constructed";
        });

        assertEquals("constructed", result);
        assertNull(AbstractObjectInstance.CONSTRUCTION_CONTEXT.get());
        assertNull(AbstractObjectInstance.PRE_ALLOCATED_SLOT.get());
    }
}
