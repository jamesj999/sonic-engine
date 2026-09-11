package com.openggf.bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-describing record of the runtime a benchmark ran on.
 *
 * <p>Every report carries one of these so a result file is interpretable on its
 * own months later. A benchmark number without the flags that produced it is
 * only a rumour — and the flags are exactly what a JVM comparison varies.
 *
 * @param vmName             e.g. "OpenJDK 64-Bit Server VM"
 * @param vmVendor           e.g. "Eclipse Adoptium"
 * @param vmVersion          the VM's own version string
 * @param javaVersion        the {@code java.version} property
 * @param javaHome           resolved JDK location
 * @param osName             operating system name
 * @param osArch             CPU architecture
 * @param availableProcessors as reported to the JVM (respects cgroup limits)
 * @param maxHeapBytes       {@code Runtime.maxMemory()}
 * @param inputArguments     JVM flags, excluding the classpath
 * @param garbageCollectors  active collector bean names
 */
public record JvmEnvironment(String vmName, String vmVendor, String vmVersion,
                             String javaVersion, String javaHome,
                             String osName, String osArch,
                             int availableProcessors, long maxHeapBytes,
                             List<String> inputArguments, List<String> garbageCollectors) {

    public static JvmEnvironment capture() {
        List<String> gcNames = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcNames.add(gc.getName());
        }
        return new JvmEnvironment(
                System.getProperty("java.vm.name", "unknown"),
                System.getProperty("java.vm.vendor", "unknown"),
                System.getProperty("java.vm.version", "unknown"),
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.home", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory(),
                List.copyOf(ManagementFactory.getRuntimeMXBean().getInputArguments()),
                List.copyOf(gcNames));
    }

    /**
     * Short human label for tables — vendor, Java version, and the collector the
     * flags asked for, which is the axis a JVM matrix usually varies.
     */
    public String shortLabel() {
        String collector = garbageCollectors.isEmpty() ? "?" : garbageCollectors.get(0);
        return vmVendor + " " + javaVersion + " (" + collector + ")";
    }
}
