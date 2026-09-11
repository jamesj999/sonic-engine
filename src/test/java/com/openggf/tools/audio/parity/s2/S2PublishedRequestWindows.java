package com.openggf.tools.audio.parity.s2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The committed request-aware S2 candidates: one bounded window each, every
 * one an exact identity the strict reader matches rather than a relaxation.
 * The first entry is the originally published EHZ-reload window; the rest
 * widen coverage along the same complete run and onto a second recording.
 * All of them are comparison-only reference data and hydrate no engine state.
 */
final class S2PublishedRequestWindows {
    private S2PublishedRequestWindows() {
    }

    static final String COMPLETE_RUN_BK2_SHA256 =
            "e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5";
    static final String CPZ_LEVEL_SELECT_BK2_SHA256 =
            "7e28cf822d5dbbe64646965cd857f264bf51d3349075af94db1a818cac7311e4";

    /**
     * One published candidate: where its payload lives, what it must hash to,
     * the exact window it covers, and how many pre-consumption request
     * transfers the extractor observed inside it.
     */
    record Published(String name, String resource, String payloadGzSha256,
            String payloadRawSha256, int requestTransfers,
            S2RequestAwareOracleSchema.Window window) {

        Path expand(Path directory) throws IOException {
            Path expanded = directory.resolve(name + ".oracle-raw-v2.jsonl");
            if (Files.exists(expanded)) {
                return expanded;
            }
            try (InputStream input = new GZIPInputStream(open());
                 OutputStream output = Files.newOutputStream(expanded)) {
                input.transferTo(output);
            }
            return expanded;
        }

        InputStream open() {
            InputStream stream = S2PublishedRequestWindows.class
                    .getResourceAsStream(resource);
            if (stream == null) {
                throw new IllegalStateException(
                        "committed S2 request window is absent: " + resource);
            }
            return stream;
        }
    }

    /**
     * A published window of the complete run.
     *
     * <p>The source scope is the window's own. Every one of these was cut from
     * a capture bounded to the window rather than from one capture of the
     * whole 769-to-259590 run, which is what the two-site recapture used and
     * what the CPZ window has always used. Provenance is therefore per window
     * and a window can be regenerated on its own, in minutes, instead of
     * needing a run-length capture first.
     */
    private static Published complete(String name, String gz, String raw,
            int transfers, int firstRow, int exclusiveEnd) {
        return new Published(name,
                "/audio/parity/s2/" + name + ".raw-v2.jsonl.gz", gz, raw, transfers,
                new S2RequestAwareOracleSchema.Window(name, COMPLETE_RUN_BK2_SHA256,
                        firstRow, exclusiveEnd, firstRow, exclusiveEnd));
    }

    /**
     * The original window, whose reader defaults it also is.
     *
     * <p>Recaptured for the two-site observer. The 25 SFX transfers are
     * unchanged; the two additions come from the music store at
     * {@code loc_10C0} (docs/s2disasm/s2.asm:1302-1304) and each lands on a
     * row this oracle already named from driver state alone. Row 10195 is
     * {@code 82h}, the EHZ music load, which is
     * {@link com.openggf.tools.audio.parity.s2.S2OracleSchema#ANCHOR_ROW}.
     * Row 10791 is {@code FBh}, the speed-shoes command, which is
     * {@link com.openggf.tools.audio.parity.s2.S2OracleSchema#SPEED_UP_ROW}.
     * Both were previously invisible to the request layer.
     */
    static final Published CONTROL = complete("s2-request-window-w10150-10900",
            "1d675c69d46f955eb0b69558b8b24efa3e323ffb278c10b6d936e8a1642f515f",
            "aba57c7e3de464c26c0a9caa2bc0327638c6db58e6967bb39c91982795d1773e",
            27, 10_150, 10_900);

    /**
     * The 750 rows after the original window, same segment and music epoch.
     *
     * <p>Recaptured for the two-site observer, and its count is unchanged at
     * 52: the window sits inside one music epoch and loads no song, so the
     * music store at {@code loc_10C0} carries nothing across it. Only the
     * digests move, because every transfer now names its site.
     */
    static final Published EHZ1_CONTINUATION = complete(
            "s2-request-window-w10900-11650",
            "147b2991cfb9e0ac9cc7d3e2fb975aee1e19f1ffc210b8c107e4cc33e609e540",
            "f8b8b4a1ed0d376bf3c1ef719b29c4fe57b528c39545c5656d18133ef47db4c0",
            52, 10_900, 11_650);

    /**
     * The next contiguous 750 rows, carrying coverage to movie row 12400. This
     * is where the request oracle first diverges, so it is the frontier window.
     *
     * <p>Recaptured for the two-site observer. The 27 SFX transfers are
     * unchanged, and both additions come from the music store at
     * {@code loc_10C0} (docs/s2disasm/s2.asm:1302-1304), each on a row this
     * lane had already investigated without being able to see the request.
     * Row 11991 is {@code FCh}, the speed-shoes slow-down, which the timer
     * sends through {@code PlayMusic} and which is the exact service where
     * the driver-state oracle's tempo used to diverge. Row 12114 is
     * {@code B5h}, a ring on the same mailbox, which is the row an earlier
     * request-window divergence named.
     */
    static final Published EHZ1_CONTINUATION_TWO = complete(
            "s2-request-window-w11650-12400",
            "b4fb1dbafe14a3eb5407c1421105c5e81df56be605b16c010f56dc7a0a63f6f9",
            "c9d645289eb55a5ce0f92766e0ca0a8b0d0f1ccf722546c421f8cfb39f88f8be",
            29, 11_650, 12_400);

    /**
     * The rows spanning the EHZ1 exit into the second special stage.
     *
     * <p>Recaptured for the two-site observer. The 5 SFX transfers are
     * unchanged; both additions come from the music store at {@code loc_10C0}
     * (docs/s2disasm/s2.asm:1302-1304) and are the transition itself. Row
     * 13712 is {@code F9h}, the fade-out that ends the zone's music, and row
     * 13849 is {@code 92h}, which {@code zMasterPlaylist} names
     * {@code zMusIDPtr_SpecStage} (s2.sounddriver.asm:3823-3841), the special
     * stage's own song. The window was published as a music-epoch boundary
     * and its request stream previously showed neither side of it.
     */
    static final Published SPECIAL_STAGE_TRANSITION = complete(
            "s2-request-window-w13650-14400",
            "392dd15a526ffede1e2e601a4406dcc64d50e1e7f2e0db2bdf152995779d9c2e",
            "5ccfeca0a13ac18b0e555f195d1bf567176839a8392cf74e054f07bccb307364",
            7, 13_650, 14_400);

    /**
     * A different route: the CPZ level load of the CPZ level-select recording.
     *
     * <p>Recaptured for the two-site observer. The 33 SFX transfers are
     * unchanged; the two additions are the music store's, at
     * {@code loc_10C0} (docs/s2disasm/s2.asm:1302-1304). Row 2724 is the CPZ
     * music load itself, {@code 8Eh}, which the driver's {@code zCurSong}
     * takes one service later. Row 3225 is {@code B5h}, the ring the
     * hundred-ring milestone check plays through {@code PlayMusic} rather
     * than {@code PlaySound} (:25913-25914), which is the transfer the old
     * single-site observer could not see.
     */
    static final Published CPZ_LOAD = new Published(
            "s2-request-window-cpz-w2700-3450",
            "/audio/parity/s2/s2-request-window-cpz-w2700-3450.raw-v2.jsonl.gz",
            "ff14cfca0e21007f9070e5d363087186225ab79d202cfd6d21a8331ab72ebb73",
            "fdb8584555be79ce6d23dff7a8f9e8185e77506a5bc2d3f441b1b2c3791363aa",
            35,
            new S2RequestAwareOracleSchema.Window("s2-request-window-cpz-w2700-3450",
                    CPZ_LEVEL_SELECT_BK2_SHA256, 2_700, 3_450, 2_700, 3_450));

    /** Every committed candidate, original window first. */
    static final List<Published> ALL = List.of(CONTROL, EHZ1_CONTINUATION,
            EHZ1_CONTINUATION_TWO, SPECIAL_STAGE_TRANSITION, CPZ_LOAD);

    /**
     * The candidates the engine-side request oracle can drive today: those cut
     * from the committed complete run, which the run-chain harness replays.
     */
    static final List<Published> COMPLETE_RUN_WINDOWS = List.of(CONTROL,
            EHZ1_CONTINUATION, EHZ1_CONTINUATION_TWO, SPECIAL_STAGE_TRANSITION);
}
