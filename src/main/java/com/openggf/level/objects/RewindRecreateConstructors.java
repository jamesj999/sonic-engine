package com.openggf.level.objects;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Predicate;

final class RewindRecreateConstructors {

    private RewindRecreateConstructors() {
    }

    static AbstractObjectInstance instantiateExact(
            Object receiver,
            String markerName,
            String constructorDescription,
            String failureDescription,
            Class<?>[] parameterTypes,
            Object... args) {
        Class<? extends AbstractObjectInstance> objectClass = objectClass(receiver);
        try {
            Constructor<?> constructor = objectClass.getDeclaredConstructor(parameterTypes);
            return instantiate(objectClass, constructor, args, failureDescription);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    objectClass.getName() + " implements " + markerName
                            + " but has no " + constructorDescription + " constructor",
                    e);
        }
    }

    static AbstractObjectInstance instantiateSelected(
            Object receiver,
            String markerName,
            String constructorDescription,
            String failureDescription,
            Constructor<?> constructor,
            Object... args) {
        Class<? extends AbstractObjectInstance> objectClass = objectClass(receiver);
        if (constructor == null) {
            throw new IllegalStateException(
                    objectClass.getName() + " implements " + markerName
                            + " but has no " + constructorDescription + " constructor");
        }
        return instantiate(objectClass, constructor, args, failureDescription);
    }

    static Constructor<?> findOnly(
            Class<? extends AbstractObjectInstance> objectClass,
            String constructorDescription,
            Predicate<Constructor<?>> matcher)
            throws NoSuchMethodException {
        Constructor<?> match = null;
        for (Constructor<?> constructor : objectClass.getDeclaredConstructors()) {
            if (!matcher.test(constructor)) {
                continue;
            }
            if (match != null) {
                throw new NoSuchMethodException(
                        objectClass.getName() + " has multiple " + constructorDescription
                                + " constructors");
            }
            match = constructor;
        }
        if (match == null) {
            throw new NoSuchMethodException(
                    objectClass.getName() + ".<init>(" + constructorDescription + ")");
        }
        return match;
    }

    static Constructor<?> findLongest(
            Class<? extends AbstractObjectInstance> objectClass,
            String constructorDescription,
            Predicate<Constructor<?>> matcher,
            boolean failOnSameArity)
            throws NoSuchMethodException {
        Constructor<?> best = null;
        for (Constructor<?> constructor : objectClass.getDeclaredConstructors()) {
            if (!matcher.test(constructor)) {
                continue;
            }
            int parameterCount = constructor.getParameterCount();
            if (best == null || parameterCount > best.getParameterCount()) {
                best = constructor;
            } else if (failOnSameArity && parameterCount == best.getParameterCount()) {
                throw new NoSuchMethodException(
                        objectClass.getName() + " has multiple " + constructorDescription
                                + " constructors");
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(
                    objectClass.getName() + ".<init>(" + constructorDescription + ")");
        }
        return best;
    }

    static Object[] zeroScalarArgs(Constructor<?> constructor) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = parameterTypes[i] == boolean.class ? Boolean.FALSE : 0;
        }
        return args;
    }

    static boolean allZeroScalars(Class<?>[] parameterTypes, int offset) {
        for (int i = offset; i < parameterTypes.length; i++) {
            if (parameterTypes[i] != int.class && parameterTypes[i] != boolean.class) {
                return false;
            }
        }
        return true;
    }

    static Class<? extends AbstractObjectInstance> objectClass(Object receiver) {
        return receiver.getClass().asSubclass(AbstractObjectInstance.class);
    }

    private static AbstractObjectInstance instantiate(
            Class<? extends AbstractObjectInstance> objectClass,
            Constructor<?> constructor,
            Object[] args,
            String failureDescription) {
        try {
            constructor.setAccessible(true);
            return objectClass.cast(constructor.newInstance(args));
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(
                    objectClass.getName() + " failed " + failureDescription, e);
        }
    }
}
