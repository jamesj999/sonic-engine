package uk.co.jamesj999.sonic.audio.debug;

import uk.co.jamesj999.sonic.audio.AudioBackend;
import uk.co.jamesj999.sonic.audio.JOALAudioBackend;
import uk.co.jamesj999.sonic.audio.NullAudioBackend;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsLoader;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Rom;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.BoxLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

/**
 * Lightweight console-driven sound test runner for SMPS tracks.
 * Controls (stdin):
 *  - n / p : next / previous song ID
 *  - r     : restart current song
 *  - hex or decimal number (e.g., 0x8C or 140) to jump to a specific ID
 *  - q     : quit
 *
 * If no args are provided, a simple interactive window opens:
 *  - Up/Down arrows: change song ID
 *  - Enter: play/restart current song
 *  - Space: stop (silences by restarting with no data)
 *  - Esc: quit
 */
public final class SoundTestApp {

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

        SmpsLoader loader = new SmpsLoader(rom);
        DacData dacData = loader.loadDacData();
        AudioBackend backend = options.nullAudio ? new NullAudioBackend() : new JOALAudioBackend();
        backend.init();
        Runtime.getRuntime().addShutdownHook(new Thread(backend::destroy));

        if (options.interactiveWindow) {
            runInteractiveWindow(options, loader, dacData, backend);
        } else {
            runConsole(options, loader, dacData, backend);
        }
    }

    private static void runInteractiveWindow(Options options, SmpsLoader loader, DacData dacData, AudioBackend backend) throws Exception {
        InteractiveState state = new InteractiveState(options.songId, loader, dacData, backend);
        SwingUtilities.invokeAndWait(() -> state.show(options.nullAudio, options.romPath));
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        exec.scheduleAtFixedRate(backend::update, 0, 16, TimeUnit.MILLISECONDS);
        state.awaitClose();
        exec.shutdownNow();
        backend.destroy();
    }

    private static void runConsole(Options options, SmpsLoader loader, DacData dacData, AudioBackend backend) throws Exception {
        System.out.println("Sound test ready.");
        System.out.println("ROM: " + options.romPath);
        System.out.println("Backend: " + backend.getClass().getSimpleName() + (options.nullAudio ? " (silent)" : ""));
        printControls(options.songId);

        int currentSong = options.songId;
        playSong(loader, dacData, backend, currentSong);

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
                        playSong(loader, dacData, backend, currentSong);
                        break;
                    case "n":
                        currentSong++;
                        playSong(loader, dacData, backend, currentSong);
                        break;
                    case "p":
                        currentSong = Math.max(0, currentSong - 1);
                        playSong(loader, dacData, backend, currentSong);
                        break;
                    default:
                        int parsed = parseSongId(line);
                        if (parsed >= 0) {
                            currentSong = parsed;
                            playSong(loader, dacData, backend, currentSong);
                        } else {
                            System.out.println("Unrecognised command: " + line);
                            printControls(currentSong);
                        }
                        break;
                }
            }
            backend.update();
            Thread.sleep(16L);
        }
        backend.destroy();
        System.out.println("Sound test exited.");
    }

    private static void playSong(SmpsLoader loader, DacData dacData, AudioBackend backend, int songId) {
        int offset = loader.findMusicOffset(songId);
        SmpsData data = loader.loadMusic(songId);
        if (data == null) {
            System.out.println(String.format("Song %s not found (offset %s).", toHex(songId), toHex(offset)));
            return;
        }

        System.out.println("--------------------------------------------------");
        String title = lookupTitle(songId);
        if (title != null) {
            System.out.println(String.format("Playing song %s (%s)", toHex(songId), title));
        } else {
            System.out.println(String.format("Playing song %s", toHex(songId)));
        }
        if (offset >= 0) {
            System.out.println(String.format("ROM offset: %s (Z80 base: %s)", toHex(offset), toHex(data.getZ80StartAddress())));
        }
        System.out.println(String.format("Header: voicePtr=%s dacPtr=%s fm=%d psg=%d tempo=%d divide=%d",
                toHex(data.getVoicePtr()), toHex(data.getDacPointer()),
                data.getChannels(), data.getPsgChannels(),
                data.getTempo(), data.getDividingTiming()));
        backend.playSmps(data, dacData);
    }

    private static void printControls(int currentSong) {
        System.out.println(String.format("Controls: n/p next-prev | r restart | hex/dec number to jump | q quit | current=%s", toHex(currentSong)));
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

    private static class InteractiveState {
        private final SmpsLoader loader;
        private final DacData dacData;
        private final AudioBackend backend;
        private int songId;
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

        InteractiveState(int songId, SmpsLoader loader, DacData dacData, AudioBackend backend) {
            this.songId = songId;
            this.loader = loader;
            this.dacData = dacData;
            this.backend = backend;
        }

        void show(boolean nullAudio, String romPath) {
            frame = new JFrame("Sonic Sound Test");
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
            JLabel info = new JLabel(String.format("ROM: %s | Backend: %s%s | Up/Down change | Enter play | Esc quit",
                    romPath, backend.getClass().getSimpleName(), nullAudio ? " (silent)" : ""), SwingConstants.CENTER);
            info.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            frame.getContentPane().add(info, BorderLayout.SOUTH);
            frame.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_UP:
                            songId++;
                            updateLabel();
                            break;
                        case KeyEvent.VK_DOWN:
                            songId = Math.max(0, songId - 1);
                            updateLabel();
                            break;
                        case KeyEvent.VK_ENTER:
                            backend.stopPlayback();
                            playing = false;
                            playingSongId = null;
                            playCurrent();
                            break;
                        case KeyEvent.VK_SPACE:
                            backend.stopPlayback();
                            playing = false;
                            playingSongId = null;
                            break;
                        case KeyEvent.VK_ESCAPE:
                            close();
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
            playCurrent();
            refreshTimer = new Timer(200, e -> updateDetails());
            refreshTimer.start();
        }

        void awaitClose() throws InterruptedException {
            while (!closed) {
                Thread.sleep(50L);
            }
        }

        private void playCurrent() {
            int offset = loader.findMusicOffset(songId);
            SmpsData data = loader.loadMusic(songId);
            if (data != null) {
                backend.playSmps(data, dacData);
                playing = true;
                playingSongId = songId;
            }
            updateLabel();
        }

        private void updateLabel() {
            int offset = loader.findMusicOffset(songId);
            SmpsData data = loader.loadMusic(songId);
            StringBuilder sb = new StringBuilder();
            sb.append("Song ").append(toHex(songId));
            sb.append(" | Offset ").append(toHex(offset));
            if (data != null) {
                sb.append(" | Tempo ").append(data.getTempo());
                sb.append(" | Div ").append(data.getDividingTiming());
                sb.append(" | FM ").append(data.getChannels());
                sb.append(" | PSG ").append(data.getPsgChannels());
            } else {
                sb.append(" | Not found");
            }
            label.setText(sb.toString());
            String selectedTitle = lookupTitle(songId);
            if (playing && playingSongId != null) {
                String playingTitle = lookupTitle(playingSongId);
                titleLabel.setText(String.format("Playing: '%s' (%s)", playingTitle != null ? playingTitle : "Unknown Track", toHex(playingSongId)));
            } else {
                titleLabel.setText(String.format("Stopped. Selected: '%s' (%s)", selectedTitle != null ? selectedTitle : "Unknown Track", toHex(songId)));
            }
            updateDetails();
        }

        private void updateDetails() {
            if (tracksPanel == null) return;
            if (backend instanceof uk.co.jamesj999.sonic.audio.JOALAudioBackend joal) {
                var dbg = joal.getDebugState();
                Set<String> touched = new HashSet<>();
                if (dbg != null) {
                    heading.setText(String.format("Channels (Tempo %d Div %d)", dbg.tempoWeight, dbg.dividingTiming));
                    for (var t : dbg.tracks) {
                        String key = t.type + "-" + t.channelId;
                        touched.add(key);
                        JLabel l = trackLabels.computeIfAbsent(key, k -> {
                            JLabel nl = new JLabel();
                            nl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                            nl.setAlignmentX(JLabel.LEFT_ALIGNMENT);
                            tracksPanel.add(nl);
                            tracksPanel.revalidate();
                            return nl;
                        });
                        String txt = String.format("%-3s%1d %s note=%s v=%02X dur=%03d vol=%d key=%d pan=%02X mod=%s",
                                t.type, t.channelId,
                                t.active ? "ON " : "off",
                                t.note == 0 ? "--" : toHex(t.note),
                                t.voiceId,
                                t.duration,
                                t.volumeOffset,
                                t.keyOffset,
                                t.pan,
                                t.modEnabled ? "Y" : "N");
                        l.setText(txt);
                    }
                } else {
                    heading.setText("Channels (no SMPS debug)");
                }
                // Mark untouched labels as idle
                for (Map.Entry<String, JLabel> e : trackLabels.entrySet()) {
                    if (!touched.contains(e.getKey())) {
                        e.getValue().setText(e.getKey() + " idle");
                    }
                }
            } else {
                heading.setText("Channels (debug unavailable)");
            }
        }

        private void close() {
            closed = true;
            if (refreshTimer != null) {
                refreshTimer.stop();
            }
            backend.stopPlayback();
            playing = false;
            playingSongId = null;
            if (frame != null) {
                frame.dispose();
            }
        }
    }

    private static void printUsage() {
        System.out.println("Sound test usage:");
        System.out.println("  mvn -Psoundtest exec:java [-Dexec.args=\"--rom <path> --song <hex> --null-audio\"]");
        System.out.println("Args:");
        System.out.println("  --rom <path>      Path to Sonic 2 ROM (defaults to config ROM)");
        System.out.println("  --song <id>       Song ID in hex or decimal (default 0x8C)");
        System.out.println("  --null-audio      Run without JOAL (parsing only)");
        System.out.println("  --help            Show this help");
    }

    private static final class Options {
        final String romPath;
        final int songId;
        final boolean nullAudio;
        final boolean help;
        final boolean interactiveWindow;

        private Options(String romPath, int songId, boolean nullAudio, boolean help) {
            this.romPath = romPath;
            this.songId = songId;
            this.nullAudio = nullAudio;
            this.help = help;
            this.interactiveWindow = false;
        }

        private Options(String romPath, int songId, boolean nullAudio, boolean help, boolean interactiveWindow) {
            this.romPath = romPath;
            this.songId = songId;
            this.nullAudio = nullAudio;
            this.help = help;
            this.interactiveWindow = interactiveWindow;
        }

        static Options fromArgs(String[] args) {
            String defaultRom = SonicConfigurationService.getInstance()
                    .getString(SonicConfiguration.ROM_FILENAME);
            if (defaultRom == null || defaultRom.isEmpty()) {
                defaultRom = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";
            }
            String romPath = defaultRom;
            int songId = 0x8C; // Chemical Plant default for debugging
            boolean nullAudio = false;
            boolean help = false;
            boolean forceConsole = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
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
                        if (arg.startsWith("--rom=")) {
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
            boolean interactive = args.length == 0 || !forceConsole;
            return new Options(romPath, songId, nullAudio, help, interactive);
        }
    }

    private static final Map<Integer, String> TITLE_MAP = buildTitleMap();

    private static Map<Integer, String> buildTitleMap() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(0x00, "Continue");
        m.put(0x80, "Casino Night Zone (2P)");
        m.put(0x81, "Emerald Hill Zone");
        m.put(0x82, "Metropolis Zone");
        m.put(0x83, "Casino Night Zone");
        m.put(0x84, "Mystic Cave Zone");
        m.put(0x85, "Mystic Cave Zone (2P)");
        m.put(0x86, "Aquatic Ruin Zone");
        m.put(0x87, "Death Egg Zone");
        m.put(0x88, "Special Stage");
        m.put(0x89, "Option Screen");
        m.put(0x8A, "Ending");
        m.put(0x8B, "Final Battle");
        m.put(0x8C, "Chemical Plant Zone");
        m.put(0x8D, "Boss");
        m.put(0x8E, "Sky Chase Zone");
        m.put(0x8F, "Oil Ocean Zone");
        m.put(0x90, "Wing Fortress Zone");
        m.put(0x91, "Emerald Hill Zone (2P)");
        m.put(0x92, "2P Results Screen");
        m.put(0x93, "Super Sonic");
        m.put(0x94, "Hill Top Zone");
        m.put(0x96, "Title Screen");
        m.put(0x97, "Stage Clear");
        m.put(0x99, "Invincibility");
        m.put(0x9B, "Hidden Palace Zone");
        m.put(0xB5, "1-Up");
        m.put(0xB8, "Game Over");
        m.put(0xBA, "Got an Emerald");
        m.put(0xBD, "Credits");
        m.put(0xDC, "Underwater Timing");
        return m;
    }

    private static String lookupTitle(int songId) {
        return TITLE_MAP.get(songId);
    }
}
