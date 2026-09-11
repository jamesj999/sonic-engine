package com.openggf.debug;
import java.lang.management.ManagementFactory;
public class AllocationProbe {
 static final String[] NAMES = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
 static void frame(PerformanceProfiler p) {
  p.beginFrame();
  for (String name : NAMES) { p.recordSectionTime(name, 1000); p.recordSectionTime(name, 2000); }
  p.endFrame(); p.getSnapshot();
 }
 public static void main(String[] args) {
  PerformanceProfiler p = PerformanceProfiler.getInstance(); p.setAllocationTrackingEnabled(false);
  com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
  for(int i=0;i<30000;i++) frame(p);
  for(int run=0;run<3;run++) {
   long start=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
   for(int i=0;i<10000;i++) frame(p);
   long used=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-start;
   System.out.println(used/10000.0+" bytes/frame");
  }
 }
}
