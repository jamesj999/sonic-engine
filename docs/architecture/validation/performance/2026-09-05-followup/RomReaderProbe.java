import com.openggf.data.*;
import java.lang.management.ManagementFactory;
public class RomReaderProbe {
 static volatile int consume;
 public static void main(String[] args) throws Exception {
  var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
  try (Rom rom = new Rom()) {
   if(!rom.open(args[0]))throw new IllegalStateException("ROM failed to open");
   for(int i=0;i<20;i++)consume=RomByteReader.fromRom(rom).readU16BE(0x100);
   for(int run=0;run<3;run++){
    long start=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    for(int i=0;i<20;i++)consume=RomByteReader.fromRom(rom).readU16BE(0x100);
    long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-start;
    System.out.println(bytes/20.0+" bytes/repeated reader acquisition");
   }
  }
 }
}
