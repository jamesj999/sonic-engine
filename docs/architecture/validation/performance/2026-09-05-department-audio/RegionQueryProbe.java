import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;

public class RegionQueryProbe {
    private static volatile SmpsSequencer.Region sink;
    public static SmpsSequencer.Region snapshotRegion(SmpsDriver driver) {
        return driver.captureSnapshot().region();
    }
    public static void main(String[] args) throws Throwable {
        MethodType type = MethodType.methodType(SmpsSequencer.Region.class);
        MethodHandle query = args[0].equals("scalar")
                ? MethodHandles.lookup().findVirtual(SmpsDriver.class,"getRegion",type)
                : MethodHandles.lookup().findStatic(RegionQueryProbe.class,"snapshotRegion",type.appendParameterTypes(SmpsDriver.class));
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int n : new int[]{0,1,32}) {
            SmpsDriver driver = new SmpsDriver();
            for (int i=0;i<n;i++) {
                var data = new AudioTestFixtures.StubSmpsData("probe"+i);data.setId(0x81+i);
                driver.addSequencer(new SmpsSequencer(data,AudioTestFixtures.EMPTY_DAC,driver,
                    AudioManager.getInstance(),new SmpsSequencerConfig.Builder().build()),i>0);
            }
            driver.setRegion(SmpsSequencer.Region.PAL);
            for (int i=0;i<5000;i++) sink=(SmpsSequencer.Region)query.invokeExact(driver);
            for (int rep=0;rep<3;rep++) {
                long start=bean.getCurrentThreadAllocatedBytes();
                for (int i=0;i<3000;i++) sink=(SmpsSequencer.Region)query.invokeExact(driver);
                long bytes=bean.getCurrentThreadAllocatedBytes()-start;
                if (sink != SmpsSequencer.Region.PAL) throw new AssertionError("region changed");
                System.out.printf("mode=%s sequencers=%d rep=%d bytesPerQuery=%.3f%n",args[0],n,rep,bytes/3000.0);
            }
        }
    }
}
