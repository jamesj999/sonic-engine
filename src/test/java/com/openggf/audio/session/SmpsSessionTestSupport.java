package com.openggf.audio.session;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.session.SmpsChipWrite;

import java.lang.reflect.Field;

/** Shared construction for tests that exercise prepared session activations. */
public final class SmpsSessionTestSupport {
    private SmpsSessionTestSupport() {
    }

    public static SmpsDriverSession installed(double outputSampleRate) {
        SmpsPhysicalDevice.Settings settings =
                new SmpsPhysicalDevice.Settings(
                        outputSampleRate, false);
        SmpsPhysicalPolicy policy =
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE;
        SmpsDriverSession session = new SmpsDriverSession(
                settings, policy, ChipWriteObserver.NONE,
                new SmpsSessionProfileFingerprint(
                        "test", 0, policy.identity(), settings),
                SmpsDriverSessionConfiguration.DEFAULT);
        session.install();
        return session;
    }

    public static SmpsDriver logicalDriver(SmpsDriverSession session) {
        try {
            Field driver = SmpsDriverSession.class.getDeclaredField("driver");
            driver.setAccessible(true);
            return (SmpsDriver) driver.get(session);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    public static void setPhysicalWriteInterceptor(
            SmpsDriverSession session,
            java.util.function.Consumer<SmpsChipWrite> interceptor) {
        session.setPhysicalWriteInterceptorForTesting(interceptor);
    }
}
