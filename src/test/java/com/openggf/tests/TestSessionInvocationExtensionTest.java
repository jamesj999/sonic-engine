package com.openggf.tests;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SessionInvocationExtension.class)
class TestSessionInvocationExtensionTest {

    private static final Set<String> INVOCATION_IDS = ConcurrentHashMap.newKeySet();

    @Test
    void currentInvocationExposesStableOwnerFields() {
        SessionInvocationExtension.SessionInvocation invocation =
                SessionInvocationExtension.SessionInvocation.current();

        assertNotNull(invocation.className());
        assertNotNull(invocation.methodName());
        assertNotNull(invocation.displayName());
        assertTrue(invocation.parameterIndex() >= 0);
        assertTrue(invocation.invocationId().matches("[0-9a-f]{16}"));
        assertTrue(INVOCATION_IDS.add(invocation.invocationId()));
    }

    @RepeatedTest(2)
    void repeatedInvocationsHaveDistinctIds() {
        SessionInvocationExtension.SessionInvocation invocation =
                SessionInvocationExtension.SessionInvocation.current();
        assertTrue(INVOCATION_IDS.add(invocation.invocationId()),
                "each repeated invocation must own a distinct output namespace");
    }

    @Test
    void repeatedTemplateAndDynamicUniqueIdsRemainDistinct() {
        String repeated = SessionInvocationExtension.invocationIdFor(
                "[engine:junit-jupiter]/[class:Example]/[test-template-invocation:#1]");
        String template = SessionInvocationExtension.invocationIdFor(
                "[engine:junit-jupiter]/[class:Example]/[test-template-invocation:#2]");
        String dynamic = SessionInvocationExtension.invocationIdFor(
                "[engine:junit-jupiter]/[class:Example]/[dynamic-test:#3]");

        assertFalse(repeated.isBlank());
        assertNotEquals(repeated, template);
        assertNotEquals(repeated, dynamic);
        assertNotEquals(template, dynamic);
    }
}
