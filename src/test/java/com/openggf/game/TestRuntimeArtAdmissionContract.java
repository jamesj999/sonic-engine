package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.openggf.level.SeamlessLevelTransitionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class TestRuntimeArtAdmissionContract {

    @Test
    void inLevelTitleCompletionRequiresAnExactAdmissionLease() {
        var completionMethods = Arrays.stream(ObjectArtProvider.class.getMethods())
                .filter(method -> method.getName().equals(
                        "onInLevelTitleCardCompleted"))
                .toList();

        assertEquals(1, completionMethods.size());
        assertEquals(List.of(RuntimeArtAdmissionLease.class),
                List.of(completionMethods.getFirst().getParameterTypes()),
                "an unqualified completion callback could arm a replacement batch");
        assertEquals(void.class, completionMethods.getFirst().getReturnType());
    }

    @Test
    void transitionAdmissionPoliciesNameEveryProductionOwnerShape() {
        Class<?> policy = load("com.openggf.game.RuntimeArtAdmissionPolicy");
        Set<String> names = Arrays.stream(policy.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "IMMEDIATE",
                "PRESERVE_CURRENT",
                "TITLE_OWNER",
                "RESOURCE_HANDOFF_OWNER"), names);
    }

    @Test
    void transitionRequestCarriesPolicyIndependentOfOverlayVisibility() throws Exception {
        Object builder = SeamlessLevelTransitionRequest.builder(
                SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL);
        var setter = Arrays.stream(builder.getClass().getMethods())
                .filter(method -> method.getName().equals("runtimeArtAdmissionPolicy"))
                .findFirst()
                .orElseGet(() -> {
                    fail("transition request builder must carry runtime-art admission policy");
                    return null;
                });
        setter.invoke(builder, RuntimeArtAdmissionPolicy.TITLE_OWNER);
        builder.getClass().getMethod("showInLevelTitleCard", boolean.class)
                .invoke(builder, false);
        Object request = builder.getClass().getMethod("build").invoke(builder);

        assertEquals(RuntimeArtAdmissionPolicy.TITLE_OWNER,
                request.getClass().getMethod("runtimeArtAdmissionPolicy").invoke(request));
        assertEquals(false,
                request.getClass().getMethod("showInLevelTitleCard").invoke(request));
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            fail("missing typed runtime-art admission contract: " + name);
            return null;
        }
    }
}
