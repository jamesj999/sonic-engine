package com.openggf.tests;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gives each JUnit invocation a private, leak-free output ownership scope. */
public final class SessionInvocationExtension
        implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SessionInvocationExtension.class);
    private static final String STORE_KEY = "scope";
    private static final ThreadLocal<Deque<SessionInvocation>> CURRENT =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final Pattern PARAMETER_INDEX = Pattern.compile("index=(\\d+)");

    @Override
    public void beforeEach(ExtensionContext context) {
        SessionInvocation invocation = SessionInvocation.from(context);
        CURRENT.get().push(invocation);
        context.getStore(NAMESPACE).put(STORE_KEY,
                new Scope(Thread.currentThread(), invocation));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Scope scope = context.getStore(NAMESPACE).remove(STORE_KEY, Scope.class);
        if (scope == null) {
            throw new IllegalStateException(
                    "session invocation scope was not installed for " + context.getDisplayName());
        }
        Deque<SessionInvocation> stack = CURRENT.get();
        boolean removed = false;
        try {
            if (scope.ownerThread() != Thread.currentThread()) {
                throw new IllegalStateException(
                        "session invocation scope changed threads: owner="
                                + scope.ownerThread().getName() + ", current="
                                + Thread.currentThread().getName());
            }
            if (stack.isEmpty() || stack.peek() != scope.invocation()) {
                throw new IllegalStateException(
                        "session invocation scope was not the current nested invocation");
            }
            stack.pop();
            removed = true;
        } finally {
            if (!removed) {
                // Keep a failed lifecycle callback from leaking this invocation
                // into the next test on the same worker thread.
                stack.removeFirstOccurrence(scope.invocation());
            }
            if (CURRENT.get().isEmpty()) {
                CURRENT.remove();
            }
        }
    }

    private record Scope(Thread ownerThread, SessionInvocation invocation) {
    }

    static String invocationIdFor(String uniqueId) {
        return SessionInvocation.invocationId(uniqueId);
    }

    public record SessionInvocation(
            String className,
            String methodName,
            int parameterIndex,
            String invocationId,
            String displayName) {

        public static SessionInvocation current() {
            Deque<SessionInvocation> stack = CURRENT.get();
            if (stack.isEmpty()) {
                CURRENT.remove();
                throw new IllegalStateException(
                        "SessionInvocation.current() is only available inside a "
                                + "SessionInvocationExtension-owned JUnit invocation");
            }
            return stack.peek();
        }

        private static SessionInvocation from(ExtensionContext context) {
            Optional<Class<?>> testClass = context.getTestClass();
            Optional<Method> testMethod = context.getTestMethod();
            String uniqueId = context.getUniqueId();
            Matcher matcher = PARAMETER_INDEX.matcher(uniqueId);
            int parameterIndex = 0;
            while (matcher.find()) {
                parameterIndex = Integer.parseInt(matcher.group(1));
            }
            return new SessionInvocation(
                    testClass.map(Class::getName).orElse("<unknown-class>"),
                    testMethod.map(Method::getName).orElse("<unknown-method>"),
                    parameterIndex,
                    invocationId(uniqueId),
                    context.getDisplayName());
        }

        private static String invocationId(String uniqueId) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(uniqueId.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest, 0, 8);
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError("JDK must provide SHA-256", e);
            }
        }
    }
}
