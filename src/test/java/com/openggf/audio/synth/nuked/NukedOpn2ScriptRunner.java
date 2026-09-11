package com.openggf.audio.synth.nuked;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Java twin of the bit-exactness C harness ({@code bitexact_harness.c} in the
 * validation record
 * {@code docs/architecture/validation/2026-08-29-nuked-opn2-port-bit-exactness.md}).
 *
 * <p>Reads the same script grammar, drives {@link NukedOpn2} one internal
 * cycle at a time and folds the raw MOL/MOR pin value of <em>every</em>
 * cycle into an FNV-1a checksum; {@link #main} additionally streams the
 * pairs as little-endian int16 stereo so the two builds can be compared
 * sample-for-sample with {@code cmp}. The grammar:
 *
 * <pre>
 * type &lt;flags&gt;            chip type flags: bit 0 = YM2612, bit 1 = readmode
 * pace &lt;a&gt; &lt;d&gt;            cycles clocked after the address / data strobe of a "reg" line
 * write &lt;port&gt; &lt;data&gt;     raw bus write, port is the 2-bit bus address
 * reg &lt;part&gt; &lt;reg&gt; &lt;val&gt;  address strobe, a cycles, data strobe, d cycles (default 1 / 13)
 * clock &lt;n&gt;               n cycles
 * at &lt;frame&gt;              clock until frame * 24 cycles have run
 * status &lt;port&gt;           status read, recorded as "STATUS &lt;cycle&gt; &lt;byte&gt;"
 * irq                     IRQ pin read, recorded as "IRQ &lt;cycle&gt; &lt;bit&gt;"
 * dump                    selected state fields, recorded for bisecting
 * </pre>
 *
 * <p>Nothing in this runner touches the engine facade: it is the port alone,
 * at the pin level, with no resampler, mixer or write queue in the path.
 */
public final class NukedOpn2ScriptRunner {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int DEFAULT_PACE_ADDRESS = 1;
    private static final int DEFAULT_PACE_DATA = 13;

    /** Outcome of one script: cycle count, per-cycle checksum and the side log lines. */
    public record Result(long cycles, long checksum, List<String> side) {
    }

    private final NukedOpn2 chip = new NukedOpn2();
    private final int[] buffer = new int[2];
    private final OutputStream pcm;
    private final List<String> side = new ArrayList<>();
    private long checksum = FNV_OFFSET;
    private long cycles;
    private int paceAddress = DEFAULT_PACE_ADDRESS;
    private int paceData = DEFAULT_PACE_DATA;

    private NukedOpn2ScriptRunner(OutputStream pcm) {
        this.pcm = pcm;
        chip.setChipType(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
    }

    /** Runs a script from {@code reader}; {@code pcm} may be null to skip the sample stream. */
    public static Result run(BufferedReader reader, OutputStream pcm) throws IOException {
        return run(-1, reader, pcm);
    }

    /**
     * Runs a script body under {@code chipType} (a {@code type} line executed
     * before the body, or none when negative), the way the committed fixtures
     * are expanded once per chip type.
     */
    public static Result run(int chipType, BufferedReader reader, OutputStream pcm) throws IOException {
        NukedOpn2ScriptRunner runner = new NukedOpn2ScriptRunner(pcm);
        if (chipType >= 0) {
            runner.execute("type " + chipType);
        }
        String line;
        while ((line = reader.readLine()) != null) {
            runner.execute(line);
        }
        return new Result(runner.cycles, runner.checksum, runner.side);
    }

    /** Opens a plain or gzip-compressed script file. */
    public static BufferedReader open(Path script) throws IOException {
        if (script.getFileName().toString().endsWith(".gz")) {
            return new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(script)), StandardCharsets.US_ASCII));
        }
        return Files.newBufferedReader(script, StandardCharsets.US_ASCII);
    }

    private void execute(String line) throws IOException {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
            return;
        }
        String[] fields = trimmed.split("\\s+");
        switch (fields[0]) {
            case "type" -> chip.setChipType(Integer.parseInt(fields[1]));
            case "pace" -> {
                paceAddress = Integer.parseInt(fields[1]);
                paceData = Integer.parseInt(fields[2]);
            }
            case "write" -> chip.write(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]));
            case "reg" -> {
                int part = Integer.parseInt(fields[1]);
                chip.write(part * 2, Integer.parseInt(fields[2]));
                clock(paceAddress);
                chip.write(part * 2 + 1, Integer.parseInt(fields[3]));
                clock(paceData);
            }
            case "clock" -> clock(Long.parseLong(fields[1]));
            case "at" -> {
                long target = Long.parseLong(fields[1]) * NukedOpn2.CYCLES_PER_FRAME;
                while (cycles < target) {
                    clock(1);
                }
            }
            case "status" -> side.add("STATUS " + cycles + " " + chip.read(Integer.parseInt(fields[1])));
            case "irq" -> side.add("IRQ " + cycles + " " + chip.readIrqPin());
            case "dump" -> dump();
            default -> throw new IllegalStateException("unknown script line: " + line);
        }
    }

    private void clock(long count) throws IOException {
        for (long i = 0; i < count; i++) {
            chip.clock(buffer);
            int left = buffer[0] & 0xffff;
            int right = buffer[1] & 0xffff;
            if (pcm != null) {
                pcm.write(left & 0xff);
                pcm.write(left >> 8);
                pcm.write(right & 0xff);
                pcm.write(right >> 8);
            }
            checksum ^= left;
            checksum *= FNV_PRIME;
            checksum ^= right;
            checksum *= FNV_PRIME;
            cycles++;
        }
    }

    private void dump() {
        NukedOpn2State s = chip.state();
        side.add("DUMP cycle=" + cycles + " cycles=" + s.cycles + " channel=" + s.channel
                + " mol=" + s.mol + " mor=" + s.mor + " eg_timer=" + s.egTimer + " eg_cycle=" + s.egCycle
                + " lfo_cnt=" + s.lfoCnt + " lfo_am=" + s.lfoAm + " lfo_pm=" + s.lfoPm
                + " timer_a_cnt=" + s.timerACnt + " timer_b_cnt=" + s.timerBCnt
                + " status=" + s.status + " busy=" + s.busy + " dacdata=" + s.dacdata);
        side.add(join("DUMP pg_phase", s.pgPhase));
        side.add(join("DUMP eg_level", s.egLevel));
        side.add(join("DUMP eg_state", s.egState));
        side.add(join("DUMP fm_out", s.fmOut));
        side.add(join("DUMP ch_out", s.chOut));
    }

    private static String join(String label, int[] values) {
        StringBuilder text = new StringBuilder(label);
        for (int value : values) {
            text.append(' ').append(value);
        }
        return text.toString();
    }

    /**
     * {@code <script[.gz]> <out.pcm> <side.txt>} mirrors the C harness command
     * line and stdout line; {@code --batch <script dir> <out dir>} runs every
     * {@code *.txt} / {@code *.txt.gz} script in one JVM, writing
     * {@code <name>.pcm} and {@code <name>.side} and printing one
     * {@code <name> CYCLES <n> CHECKSUM <hex>} line per script.
     */
    public static void main(String[] arguments) throws IOException {
        if (arguments.length == 3 && "--batch".equals(arguments[0])) {
            Path outDir = Path.of(arguments[2]);
            Files.createDirectories(outDir);
            List<Path> scripts = new ArrayList<>();
            try (var listing = Files.list(Path.of(arguments[1]))) {
                listing.filter(p -> p.toString().endsWith(".txt") || p.toString().endsWith(".txt.gz"))
                        .sorted().forEach(scripts::add);
            }
            for (Path script : scripts) {
                String name = script.getFileName().toString().replaceFirst("\\.txt(\\.gz)?$", "");
                Result result = runToFiles(script, outDir.resolve(name + ".pcm"), outDir.resolve(name + ".side"));
                System.out.printf("%s CYCLES %d CHECKSUM %016x%n", name, result.cycles(), result.checksum());
            }
            return;
        }
        if (arguments.length < 3) {
            System.err.println("usage: NukedOpn2ScriptRunner <script> <out.pcm> <side.txt>"
                    + " | --batch <script dir> <out dir>");
            System.exit(2);
        }
        Result result = runToFiles(Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]));
        System.out.printf("CYCLES %d CHECKSUM %016x%n", result.cycles(), result.checksum());
    }

    private static Result runToFiles(Path script, Path pcmPath, Path sidePath) throws IOException {
        Result result;
        try (BufferedReader reader = open(script);
                OutputStream pcm = new BufferedOutputStream(Files.newOutputStream(pcmPath), 1 << 16)) {
            result = run(reader, pcm);
        }
        Files.write(sidePath, result.side(), StandardCharsets.US_ASCII);
        return result;
    }
}
