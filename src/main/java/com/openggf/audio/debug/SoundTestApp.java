package com.openggf.audio.debug;

import com.openggf.audio.ChannelType;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.Ym2612Chip;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic1.audio.Sonic1SoundTestCatalog;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2SoundTestCatalog;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kSoundTestCatalog;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.BoxLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight console-driven sound test runner for SMPS tracks.
 * Supports Sonic 1, Sonic 2, and Sonic 3&amp;K ROMs.
 *
 * Controls (stdin):
 * - n / p : next / previous song ID
 * - r : restart current song
 * - hex or decimal number (e.g., 0x8C or 140) to jump to a specific ID
 * - q : quit
 *
 * If no args are provided, a simple interactive window opens:
 * - Up/Down arrows: change song ID
 * - Enter: play/restart current song
 * - Space: stop (silences by restarting with no data)
 * - Esc: quit
 */
public final class SoundTestApp {
    private static final Logger LOGGER = Logger.getLogger(SoundTestApp.class.getName());

    private SoundTestApp() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.fromArgs(args);
        if (options.help) {
            printUsage();
            return;
        }

        Rom rom = new Rom();
        if (!rom.open(options.romPath)) {
            System.err.println("Failed to open ROM at " + options.romPath);
            return;
        }

        GameAudioProfile profile = createProfileForGame(options.gameId);
        SoundTestCatalog catalog = createCatalogForGame(options.gameId);
        SmpsLoader loader = profile.createSmpsLoader(rom);
        DacData dacData = loader.loadDacData();
        SmpsSequencerConfig seqConfig = profile.getSequencerConfig();

        // Probe available SFX IDs
        System.out.println("Probing SFX for " + catalog.getGameName() + "...");
        TreeSet<Integer> validSfx = probeValidSfx(loader, catalog.getSfxIdBase(), catalog.getSfxIdMax());
        System.out.println("Found " + validSfx.size() + " valid SFX.");

        StandaloneAudioPresentationHost host =
                StandaloneAudioPresentationHost.open(
                        options.gameId,
                        SonicConfigurationService.createStandalone(),
                        null, options.nullAudio);
        // Abnormal-exit safety net only; removed on the normal path below so
        // the host's owner executor performs destruction exactly once.
        Thread destroyOnExit = new Thread(host::close);
        Runtime.getRuntime().addShutdownHook(destroyOnExit);

        int startSongId = options.songId >= 0 ? options.songId : catalog.getDefaultSongId();

        try {
            if (options.interactiveWindow) {
                runInteractiveWindow(options, loader, dacData, host, catalog, seqConfig, validSfx, startSongId);
            } else {
                runConsole(options, loader, dacData, host, catalog, startSongId);
            }
        } finally {
            boolean hostClosed = false;
            try {
                host.close();
                hostClosed = true;
            } finally {
                if (hostClosed) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(destroyOnExit);
                    } catch (IllegalStateException ignored) {
                        // JVM already shutting down; the hook owns cleanup.
                    }
                }
            }
        }
    }

    static GameAudioProfile createProfileForGame(String gameId) {
        return switch (gameId) {
            case "s1" -> new Sonic1AudioProfile();
            case "s3k" -> new Sonic3kAudioProfile();
            default -> new Sonic2AudioProfile();
        };
    }

    private static SoundTestCatalog createCatalogForGame(String gameId) {
        return switch (gameId) {
            case "s1" -> new Sonic1SoundTestCatalog();
            case "s3k" -> new Sonic3kSoundTestCatalog();
            default -> new Sonic2SoundTestCatalog();
        };
    }

    private static TreeSet<Integer> probeValidSfx(SmpsLoader loader, int sfxBase, int sfxMax) {
        TreeSet<Integer> valid = new TreeSet<>();
        for (int id = sfxBase; id <= sfxMax; id++) {
            if (loader.loadSfx(id) != null) {
                valid.add(id);
            }
        }
        return valid;
    }

    private static void runInteractiveWindow(Options options, SmpsLoader loader, DacData dacData,
            StandaloneAudioPresentationHost host, SoundTestCatalog catalog, SmpsSequencerConfig seqConfig,
            TreeSet<Integer> validSfx, int startSongId) throws Exception {
        // The single command thread serializes the 16ms ticks and Swing
        // handlers' play/stop/mute requests. The host marshals these calls to
        // its producer owner executor, so the EDT never touches audio state.
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        InteractiveState state = new InteractiveState(startSongId, loader, dacData, host,
                catalog, seqConfig, validSfx, exec);
        SwingUtilities.invokeAndWait(() -> state.show(options.nullAudio, options.romPath));
        exec.scheduleAtFixedRate(host::presentFrame, 0, 16, TimeUnit.MILLISECONDS);
        state.awaitClose();
        // Orderly shutdown: queued commands (e.g. the close-time stopPlayback)
        // still run, and no update() can be in flight when destroy() follows.
        exec.shutdown();
        if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
            exec.shutdownNow();
        }
    }

    private static void runConsole(Options options, SmpsLoader loader, DacData dacData,
            StandaloneAudioPresentationHost host,
            SoundTestCatalog catalog, int startSongId) throws Exception {
        System.out.println("Sound test ready. [" + catalog.getGameName() + "]");
        System.out.println("ROM: " + options.romPath);
        System.out.println("Output: unified PCM presentation" + (options.nullAudio ? " (silent)" : ""));
        printControls(startSongId);

        int currentSong = startSongId;
        boolean speedShoes = false;
        playSong(loader, dacData, host, currentSong, catalog);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        boolean running = true;
        while (running) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                switch (line.toLowerCase(Locale.ROOT)) {
                    case "q":
                        running = false;
                        break;
                    case "r":
                        playSong(loader, dacData, host, currentSong, catalog);
                        break;
                    case "s":
                        speedShoes = !speedShoes;
                        host.setSpeedShoes(speedShoes);
                        System.out.println("Speed shoes: " + (speedShoes ? "ON" : "OFF"));
                        break;
                    case "n":
                        currentSong = getNextValidSong(currentSong, catalog);
                        playSong(loader, dacData, host, currentSong, catalog);
                        break;
                    case "p":
                        currentSong = getPreviousValidSong(currentSong, catalog);
                        playSong(loader, dacData, host, currentSong, catalog);
                        break;
                    default:
                        int parsed = parseSongId(line);
                        if (parsed >= 0) {
                            currentSong = parsed;
                            playSong(loader, dacData, host, currentSong, catalog);
                        } else {
                            System.out.println("Unrecognised command: " + line);
                            printControls(currentSong);
                        }
                        break;
                }
            }
            host.presentFrame();
            Thread.sleep(16L);
        }
        System.out.println("Sound test exited.");
    }

    private static void playSong(SmpsLoader loader, DacData dacData,
            StandaloneAudioPresentationHost host,
            int songId, SoundTestCatalog catalog) {
        int offset = loader.findMusicOffset(songId);
        AbstractSmpsData data = loader.loadMusic(songId);
        if (data == null) {
            System.out.println(String.format("Song %s not found.", toHex(songId)));
            return;
        }

        System.out.println("--------------------------------------------------");
        String title = catalog.lookupTitle(songId);
        if (title != null) {
            System.out.println(String.format("Playing song %s (%s)", toHex(songId), title));
        } else {
            System.out.println(String.format("Playing song %s", toHex(songId)));
        }
        if (offset >= 0) {
            System.out.println(
                    String.format("ROM offset: %s (Z80 base: %s)", toHex(offset), toHex(data.getZ80StartAddress())));
        }
        System.out.println(String.format("Header: voicePtr=%s dacPtr=%s fm=%d psg=%d tempo=%d divide=%d",
                toHex(data.getVoicePtr()), toHex(data.getDacPointer()),
                data.getChannels(), data.getPsgChannels(),
                data.getTempo(), data.getDividingTiming()));
        host.playMusic(data, dacData);
    }

    private static void printControls(int currentSong) {
        System.out.println(String.format(
                "Controls: n/p next-prev | r restart | s Speed Shoes | hex/dec number to jump | q quit | current=%s",
                toHex(currentSong)));
    }

    private static int parseSongId(String token) {
        try {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("0x")) {
                return Integer.parseInt(t.substring(2), 16);
            }
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String toHex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase(Locale.ROOT);
    }

    static int renderToWav(
            AbstractSmpsData data,
            DacData dacSamples,
            SmpsSequencerConfig seqConfig,
            File outputFile,
            boolean isSfx,
            double outputRate,
            int maxSamples) throws IOException {
        try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                "sound-test", 0,
                new SmpsPhysicalDevice.Settings(outputRate, true),
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                ChipWriteObserver.NONE)) {
            SmpsDriver driver = stream.logicalDriver();
            driver.setRegion(SmpsSequencer.Region.NTSC);

            SmpsSequencer seq = new SmpsSequencer(
                    data, dacSamples, driver, seqConfig);
            seq.setSampleRate(outputRate);
            seq.setSfxMode(isSfx);
            driver.addSequencer(seq, isSfx);

            int sampleRate = (int) Math.round(outputRate);
            short[] buffer = new short[1024 * 2];
            try (RandomAccessFile raf = new RandomAccessFile(
                    outputFile, "rw")) {
                raf.setLength(0);
                raf.write(new byte[44]);

                int totalSamples = 0;
                while (totalSamples < maxSamples) {
                    int frames = Math.min(1024,
                            maxSamples - totalSamples);
                    int shorts = frames * 2;
                    stream.read(buffer, shorts);
                    for (int i = 0; i < shorts; i++) {
                        raf.writeByte(buffer[i] & 0xFF);
                        raf.writeByte((buffer[i] >> 8) & 0xFF);
                    }
                    totalSamples += frames;
                    if (isSfx && driver.isComplete()) {
                        break;
                    }
                }

                int dataSize = totalSamples * 2 * 2;
                raf.seek(0);
                writeWavHeader(raf, sampleRate, 2, 16, dataSize);
                return totalSamples;
            }
        }
    }

    private static void writeWavHeader(
            RandomAccessFile raf,
            int sampleRate,
            int channels,
            int bitsPerSample,
            int dataSize) throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        raf.writeBytes("RIFF");
        writeIntLE(raf, dataSize + 36);
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeIntLE(raf, 16);
        writeShortLE(raf, (short) 1);
        writeShortLE(raf, (short) channels);
        writeIntLE(raf, sampleRate);
        writeIntLE(raf, byteRate);
        writeShortLE(raf, (short) blockAlign);
        writeShortLE(raf, (short) bitsPerSample);
        raf.writeBytes("data");
        writeIntLE(raf, dataSize);
    }

    private static void writeIntLE(
            RandomAccessFile raf, int value) throws IOException {
        raf.writeByte(value & 0xFF);
        raf.writeByte((value >> 8) & 0xFF);
        raf.writeByte((value >> 16) & 0xFF);
        raf.writeByte((value >> 24) & 0xFF);
    }

    private static void writeShortLE(
            RandomAccessFile raf, short value) throws IOException {
        raf.writeByte(value & 0xFF);
        raf.writeByte((value >> 8) & 0xFF);
    }

    private static class InteractiveState {
        private final SmpsLoader loader;
        private final DacData dacData;
        private final StandaloneAudioPresentationHost host;
        private final SoundTestCatalog catalog;
        private final SmpsSequencerConfig seqConfig;
        private int songId;
        private int sfxId;
        private boolean sfxMode = false;
        private JFrame frame;
        private JLabel label;
        private JLabel titleLabel;
        private JLabel heading;
        private JPanel tracksPanel;
        private final Map<String, JLabel> trackLabels = new HashMap<>();
        private volatile boolean closed;
        private Timer refreshTimer;
        private boolean playing;
        private Integer playingSongId;
        private boolean speedShoes = false;
        private final TreeSet<Integer> validSfx;
        private final Map<Integer, String> sfxNames;
        private final ScheduledExecutorService audioExec;

        InteractiveState(int songId, SmpsLoader loader, DacData dacData,
                StandaloneAudioPresentationHost host,
                SoundTestCatalog catalog, SmpsSequencerConfig seqConfig, TreeSet<Integer> validSfx,
                ScheduledExecutorService audioExec) {
            this.audioExec = audioExec;
            this.songId = songId;
            this.loader = loader;
            this.dacData = dacData;
            this.host = host;
            this.catalog = catalog;
            this.seqConfig = seqConfig;
            this.sfxNames = catalog.getSfxNames();
            this.validSfx = validSfx;
            if (!validSfx.isEmpty()) {
                this.sfxId = validSfx.first();
            } else {
                this.sfxId = catalog.getSfxIdBase();
                System.out.println("No SFX available.");
            }
        }

        void show(boolean nullAudio, String romPath) {
            frame = new JFrame(catalog.getGameName() + " Sound Test");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().setLayout(new BorderLayout());
            JPanel topPanel = new JPanel();
            topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
            titleLabel = new JLabel("", SwingConstants.CENTER);
            titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            titleLabel.setAlignmentX(JLabel.CENTER_ALIGNMENT);
            label = new JLabel("", SwingConstants.CENTER);
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            label.setAlignmentX(JLabel.CENTER_ALIGNMENT);
            topPanel.add(titleLabel);
            topPanel.add(label);
            frame.getContentPane().add(topPanel, BorderLayout.NORTH);
            tracksPanel = new JPanel();
            tracksPanel.setLayout(new BoxLayout(tracksPanel, BoxLayout.Y_AXIS));
            heading = new JLabel("Channels", SwingConstants.LEFT);
            heading.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            heading.setAlignmentX(JLabel.LEFT_ALIGNMENT);
            tracksPanel.add(heading);
            frame.getContentPane().add(tracksPanel, BorderLayout.CENTER);
            JLabel info = new JLabel(String.format(
                    "[%s] ROM: %s | Backend: %s%s | Tab: Music/SFX | Up/Down change | Enter play | S Speed | Ctrl+E export WAV | Esc quit",
                    catalog.getGameName(), romPath, "unified PCM", nullAudio ? " (silent)" : ""),
                    SwingConstants.CENTER);
            info.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            frame.getContentPane().add(info, BorderLayout.SOUTH);
            frame.setFocusTraversalKeysEnabled(false);
            frame.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    boolean shift = e.isShiftDown();
                    int code = e.getKeyCode();

                    // F1-F5: FM0 - FM4
                    if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F5) {
                        int ch = code - KeyEvent.VK_F1;
                        if (shift)
                            onAudioThread(() -> host.toggleSolo(ChannelType.FM, ch));
                        else
                            onAudioThread(() -> host.toggleMute(ChannelType.FM, ch));
                        return;
                    }

                    // 1-4: PSG0 - PSG3
                    if (code >= KeyEvent.VK_1 && code <= KeyEvent.VK_4) {
                        int ch = code - KeyEvent.VK_1;
                        if (shift)
                            onAudioThread(() -> host.toggleSolo(ChannelType.PSG, ch));
                        else
                            onAudioThread(() -> host.toggleMute(ChannelType.PSG, ch));
                        return;
                    }

                    switch (code) {
                        case KeyEvent.VK_TAB:
                            sfxMode = !sfxMode;
                            updateLabel();
                            break;
                        case KeyEvent.VK_UP:
                            if (sfxMode) {
                                sfxId = getNextValidSfx(sfxId);
                            } else {
                                songId = getNextValidSong(songId, catalog);
                            }
                            updateLabel();
                            break;
                        case KeyEvent.VK_DOWN:
                            if (sfxMode) {
                                sfxId = getPreviousValidSfx(sfxId);
                            } else {
                                songId = getPreviousValidSong(songId, catalog);
                            }
                            updateLabel();
                            break;
                        case KeyEvent.VK_ENTER:
                            if (sfxMode) {
                                playCurrentSfx();
                            } else {
                                onAudioThread(host::stopPlayback);
                                playing = false;
                                playingSongId = null;
                                playCurrent();
                            }
                            break;
                        case KeyEvent.VK_SPACE:
                            onAudioThread(host::stopPlayback);
                            playing = false;
                            playingSongId = null;
                            break;
                        case KeyEvent.VK_ESCAPE:
                            close();
                            break;
                        case KeyEvent.VK_D:
                            // DAC (FM5 / Channel 5)
                            if (shift)
                                onAudioThread(() -> host.toggleSolo(ChannelType.DAC, 5));
                            else
                                onAudioThread(() -> host.toggleMute(ChannelType.DAC, 5));
                            break;
                        case KeyEvent.VK_S:
                            speedShoes = !speedShoes;
                            boolean speedShoesNow = speedShoes;
                            onAudioThread(() -> host.setSpeedShoes(speedShoesNow));
                            updateLabel();
                            break;
                        case KeyEvent.VK_E:
                            if (e.isControlDown()) {
                                exportToWav();
                            }
                            break;
                        default:
                            break;
                    }
                }
            });
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    close();
                }

                @Override
                public void windowClosed(WindowEvent e) {
                    close();
                }
            });
            frame.setSize(800, 250);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            updateLabel();
            refreshTimer = new Timer(200, e -> updateDetails());
            refreshTimer.start();
        }

        void awaitClose() throws InterruptedException {
            while (!closed) {
                Thread.sleep(50L);
            }
        }

        /**
         * Runs a backend command on the audio executor that also drives
         * {@code host.presentFrame()}, keeping all presentation mutation on
         * one thread. Commands arriving after shutdown are moot and dropped.
         */
        private void onAudioThread(Runnable command) {
            try {
                audioExec.execute(command);
            } catch (RejectedExecutionException ignored) {
                // Executor already shut down during close.
            }
        }

        private void playCurrent() {
            AbstractSmpsData data = loader.loadMusic(songId);
            if (data != null) {
                onAudioThread(() -> host.playMusic(data, dacData));
                playing = true;
                playingSongId = songId;
            }
            updateLabel();
        }

        private void playCurrentSfx() {
            AbstractSmpsData data = loader.loadSfx(sfxId);
            if (data != null) {
                System.out.println(String.format("Playing SFX %s (Size: %d) | FM: %d PSG: %d Tempo: %d Div: %d",
                        toHex(sfxId), data.getData().length,
                        data.getChannels(), data.getPsgChannels(), data.getTempo(), data.getDividingTiming()));
                onAudioThread(() -> host.playSfx(data, dacData, 1.0f));
            } else {
                System.out.println("Failed to load SFX " + toHex(sfxId));
            }
        }

        private void updateLabel() {
            if (sfxMode) {
                String name = sfxNames.get(sfxId);
                String sfxTxt = String.format("SFX %s (%s)", toHex(sfxId), name != null ? name : "Unknown");
                label.setText(sfxTxt);

                String playingTxt;
                if (playing && playingSongId != null) {
                    String playingTitle = catalog.lookupTitle(playingSongId);
                    playingTxt = String.format("Playing Music: '%s' (%s)",
                            playingTitle != null ? playingTitle : "Unknown", toHex(playingSongId));
                } else {
                    playingTxt = "Music Stopped";
                }
                titleLabel.setText(playingTxt + " | " + sfxTxt);
                updateDetails();
                return;
            }

            int offset = loader.findMusicOffset(songId);
            AbstractSmpsData data = loader.loadMusic(songId);
            StringBuilder sb = new StringBuilder();
            sb.append("Song ").append(toHex(songId));
            if (offset >= 0) {
                sb.append(" | Offset ").append(toHex(offset));
            }
            if (data != null) {
                sb.append(" | Tempo ").append(data.getTempo());
                sb.append(" | Div ").append(data.getDividingTiming());
                sb.append(" | FM ").append(data.getChannels());
                sb.append(" | PSG ").append(data.getPsgChannels());
            } else {
                sb.append(" | Not found");
            }
            label.setText(sb.toString());
            String selectedTitle = catalog.lookupTitle(songId);
            if (playing && playingSongId != null) {
                String playingTitle = catalog.lookupTitle(playingSongId);
                String txt = String.format("Playing: '%s' (%s)", playingTitle != null ? playingTitle : "Unknown Track",
                        toHex(playingSongId));
                if (speedShoes) {
                    txt += " [SPEED SHOES]";
                }
                titleLabel.setText(txt);
            } else {
                String txt = String.format("Stopped. Selected: '%s' (%s)",
                        selectedTitle != null ? selectedTitle : "Unknown Track", toHex(songId));
                if (speedShoes) {
                    txt += " [SPEED SHOES]";
                }
                titleLabel.setText(txt);
            }
            updateDetails();
        }

        private int getNextValidSfx(int current) {
            Integer next = validSfx.higher(current);
            if (next != null) {
                return next;
            }
            return validSfx.isEmpty() ? current : validSfx.first();
        }

        private int getPreviousValidSfx(int current) {
            Integer prev = validSfx.lower(current);
            if (prev != null) {
                return prev;
            }
            return validSfx.isEmpty() ? current : validSfx.last();
        }

        private void updateDetails() {
            if (tracksPanel == null)
                return;
            heading.setText("Channels (unified presentation)");
        }

        private void close() {
            if (refreshTimer != null) {
                refreshTimer.stop();
            }
            // Queue the stop before raising the closed flag: awaitClose() then
            // shuts the executor down with shutdown(), which still drains this.
            onAudioThread(host::stopPlayback);
            closed = true;
            playing = false;
            playingSongId = null;
            if (frame != null) {
                frame.dispose();
            }
        }

        private void exportToWav() {
            // Determine what to export
            int exportId;
            String exportName;
            boolean isSfxExport;
            AbstractSmpsData exportData;

            if (sfxMode) {
                exportId = sfxId;
                String name = sfxNames.get(sfxId);
                exportName = name != null ? name : "SFX_" + toHex(sfxId);
                isSfxExport = true;
                exportData = loader.loadSfx(sfxId);
            } else {
                exportId = songId;
                String title = catalog.lookupTitle(songId);
                exportName = title != null ? title.replace(" ", "_") : "Music_" + toHex(songId);
                isSfxExport = false;
                exportData = loader.loadMusic(songId);
            }

            if (exportData == null) {
                JOptionPane.showMessageDialog(frame,
                    "Failed to load " + (isSfxExport ? "SFX" : "music") + " " + toHex(exportId),
                    "Export Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Show file chooser
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Export WAV");
            fileChooser.setSelectedFile(new File(exportName + ".wav"));
            fileChooser.setFileFilter(new FileNameExtensionFilter("WAV Audio Files", "wav"));

            if (fileChooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
                return;
            }

            File outputFile = fileChooser.getSelectedFile();
            if (!outputFile.getName().toLowerCase().endsWith(".wav")) {
                outputFile = new File(outputFile.getPath() + ".wav");
            }

            // Disable UI during export
            frame.setEnabled(false);
            titleLabel.setText("Exporting " + exportName + "...");

            // Run export in background thread
            final File finalOutputFile = outputFile;
            final boolean finalIsSfx = isSfxExport;
            new Thread(() -> {
                try {
                    int samplesWritten = renderToWav(exportData, dacData, finalOutputFile, finalIsSfx);
                    SwingUtilities.invokeLater(() -> {
                        frame.setEnabled(true);
                        updateLabel();
                        double seconds = samplesWritten / getOutputSampleRate();
                        JOptionPane.showMessageDialog(frame,
                            String.format("Exported %.2f seconds to:\n%s", seconds, finalOutputFile.getAbsolutePath()),
                            "Export Complete", JOptionPane.INFORMATION_MESSAGE);
                    });
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Failed to export audio", ex);
                    SwingUtilities.invokeLater(() -> {
                        frame.setEnabled(true);
                        updateLabel();
                        JOptionPane.showMessageDialog(frame,
                            "Export failed: " + ex.getMessage(),
                            "Export Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();
        }

        /**
         * Renders SMPS data to a WAV file.
         * For SFX, renders until complete.
         * For music, renders for a fixed duration (default 60 seconds).
         */
        private int renderToWav(AbstractSmpsData data, DacData dacSamples,
                File outputFile, boolean isSfx) throws IOException {
            double outputRate = getOutputSampleRate();
            int sampleRate = (int) Math.round(outputRate);
            return SoundTestApp.renderToWav(
                    data, dacSamples, seqConfig, outputFile, isSfx,
                    outputRate,
                    isSfx ? sampleRate * 10 : sampleRate * 60);
        }

        private double getOutputSampleRate() {
            boolean internalRate = GameServices.configuration()
                    .getBoolean(SonicConfiguration.AUDIO_INTERNAL_RATE_OUTPUT);
            return internalRate ? Ym2612Chip.getInternalRate() : Ym2612Chip.getDefaultOutputRate();
        }
    }

    private static void printUsage() {
        System.out.println("Sound test usage:");
        System.out.println("  mvn -Psoundtest exec:java [-Dexec.args=\"--game <s1|s2|s3k> --rom <path> --song <hex> --null-audio\"]");
        System.out.println("Args:");
        System.out.println("  --game <id>       Game: s1 (Sonic 1), s2 (Sonic 2), s3k (Sonic 3&K). Defaults to config DEFAULT_ROM.");
        System.out.println("  --rom <path>      Path to ROM file (defaults to config ROM for selected game)");
        System.out.println("  --song <id>       Song ID in hex or decimal (default varies by game)");
        System.out.println("  --null-audio      Run without JOAL (parsing only)");
        System.out.println("  --help            Show this help");
    }

    private static final class Options {
        final String romPath;
        final String gameId;
        final int songId;
        final boolean nullAudio;
        final boolean help;
        final boolean interactiveWindow;

        private Options(String romPath, String gameId, int songId, boolean nullAudio, boolean help,
                boolean interactiveWindow) {
            this.romPath = romPath;
            this.gameId = gameId;
            this.songId = songId;
            this.nullAudio = nullAudio;
            this.help = help;
            this.interactiveWindow = interactiveWindow;
        }

        static Options fromArgs(String[] args) {
            String configGame = GameServices.configuration()
                    .getString(SonicConfiguration.DEFAULT_ROM);
            String gameId = configGame != null ? configGame.toLowerCase(Locale.ROOT) : "s2";
            String romPath = null;
            int songId = -1; // -1 means "use catalog default"
            boolean nullAudio = false;
            boolean help = false;
            boolean forceConsole = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--game":
                        if (i + 1 < args.length) {
                            gameId = args[++i].toLowerCase(Locale.ROOT);
                        }
                        break;
                    case "--rom":
                        if (i + 1 < args.length) {
                            romPath = args[++i];
                        }
                        break;
                    case "--song":
                        if (i + 1 < args.length) {
                            int parsed = parseSongId(args[++i]);
                            if (parsed >= 0) {
                                songId = parsed;
                            }
                        }
                        break;
                    case "--null-audio":
                        nullAudio = true;
                        break;
                    case "--help":
                    case "-h":
                        help = true;
                        break;
                    case "--console":
                        forceConsole = true;
                        break;
                    default:
                        if (arg.startsWith("--game=")) {
                            gameId = arg.substring("--game=".length()).toLowerCase(Locale.ROOT);
                        } else if (arg.startsWith("--rom=")) {
                            romPath = arg.substring("--rom=".length());
                        } else if (arg.startsWith("--song=")) {
                            int parsed = parseSongId(arg.substring("--song=".length()));
                            if (parsed >= 0) {
                                songId = parsed;
                            }
                        } else if (arg.equals("--no-audio")) {
                            nullAudio = true;
                        } else if (arg.equals("--ui")) {
                            forceConsole = false;
                        }
                        break;
                }
            }

            // Resolve ROM path if not explicitly set
            if (romPath == null) {
                romPath = RomManager.resolveRomForGame(gameId);
            }

            boolean interactive = args.length == 0 || !forceConsole;
            return new Options(romPath, gameId, songId, nullAudio, help, interactive);
        }
    }

    private static int getNextValidSong(int current, SoundTestCatalog catalog) {
        NavigableSet<Integer> valid = catalog.getValidSongs();
        Integer next = valid.higher(current);
        if (next != null) {
            return next;
        }
        return valid.first();
    }

    private static int getPreviousValidSong(int current, SoundTestCatalog catalog) {
        NavigableSet<Integer> valid = catalog.getValidSongs();
        Integer prev = valid.lower(current);
        if (prev != null) {
            return prev;
        }
        return valid.last();
    }
}
