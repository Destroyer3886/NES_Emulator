import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
public class NESEmulator extends JFrame implements Runnable {

    private final NESDisplayPanel displayPanel;
    private Thread emulatorThread;

    private volatile boolean running = false;
    private volatile boolean paused = false;

    //Emulator components
    private final CPU cpu;
    private final PPU ppu;
    private final APU apu;
    private final Bus bus;
    private final AudioPlayer audioPlayer;

    //Debug trace window components
    private JDialog traceDialog;
    private JTextArea traceTextArea;
    private JButton traceToggleButton;
    private JButton stepButton;
    private JToggleButton tracePauseButton;
    private volatile boolean tracingEnabled = false;
    private final StringBuilder frameTraceBuffer = new StringBuilder();
    //Accumulates every frame's trace lines while tracing is on, for multi-frame capture to disk.
    private java.io.PrintWriter traceFileWriter;
    //Separate from traceFileWriter: always saves to the Desktop regardless of working dir.
    private JToggleButton logToFileButton;
    private java.io.PrintWriter desktopLogWriter;

    //Memory inspector window components
    private JDialog memoryDialog;
    private JTable memoryTable;
    private MemoryTableModel memoryTableModel;
    private JTextField memoryGotoField;
    //Last-shown value per address, so refreshes only touch changed cells (no flicker/scroll reset).
    private final int[] memorySnapshot = new int[65536];

    //Debug viewer windows
    private NametableViewerDialog nametableViewerDialog;
    private PPUInspectorDialog ppuInspectorDialog;

    //Precise Tracing (Tools menu): replays the TAS Maker's next frame one CPU cycle at a time; while it runs the
    //TAS Maker's own frame advance/seek is locked out so the traced frame lands in the timeline exactly like a Play
    //frame (see PreciseTraceDialog.Host and the preciseTraceActive guards below).
    private PreciseTraceDialog preciseTraceDialog;
    private volatile boolean preciseTraceActive = false;
    private int preciseTraceRow = 0;
    private long preciseTraceStartCycle = 0;

    //TAS Maker window components
    private JDialog tasDialog;
    private JTable tasTable;
    private TasTableModel tasTableModel;
    private JToggleButton tasPlayButton;
    //While TAS Maker is open it owns controller 1 and frame advancement; run()'s loop must not also step.
    private volatile boolean tasActive = false;
    //Guards every mutation of cpu/ppu/bus state: the "NES-MainLoop" thread (run()) and EDT operations (TAS Maker,
    //Power Cycle, Reset, ROM load) share one emulator instance, and tasActive/paused are only rechecked once per frame.
    //Without it the EDT's bus.hardReset() can land mid-updateEmulationFrame() and bleed an extra frame into a "clean" power-on.
    private final Object emulationLock = new Object();
    private boolean tasPlaying = false;
    private javax.swing.Timer tasPlayTimer;
    //Recorded per-frame controller 1 bitmask (LSB-first A/B/Select/Start/Up/Down/Left/Right); frame k polls
    //tasInputs.get(k-1), so frame 0 sees nothing. This list is the movie - seeking replays it from power-on.
    private final List<Integer> tasInputs = new ArrayList<>();
    //Frames already executed against the live state; shown row is tasFrame - 1.
    private int tasFrame = 0;
    //Number of frames (from 0) whose state matches current tasInputs; rows below are colored green/red (tasRowColor),
    //rows at/after are plain. Only editing an input shrinks this - seeking never does.
    private int tasValidFrontier = 0;
    //Parallel to tasInputs: whether controller 1 was strobed that frame (see Bus.consumeStrobedThisFrame).
    //Validated+unstrobed = "lag frame", surfaced by the red/green coloring.
    private final List<Boolean> tasFrameStrobed = new ArrayList<>();
    //Greenzone checkpoints keyed by executed frame count on a grid of tasSavestateInterval (0, N, 2N...) so seekToFrame
    //restores the nearest floorKey entry instead of replaying from power-on. Frame 0 needs no entry - bus.hardReset() covers it.
    private final TreeMap<Integer, TasCheckpoint> tasCheckpoints = new TreeMap<>();
    //Pairs a state checkpoint with the on-screen pixels at that instant - the framebuffer lives in
    //NESDisplayPanel, not the PPU, so it must be captured/restored alongside the state.
    private static final class TasCheckpoint {
        final Bus.EmulatorState state;
        final int[] pixels;
        TasCheckpoint(Bus.EmulatorState state, int[] pixels) {
            this.state = state;
            this.pixels = pixels;
        }
    }
    //"Frames per savestate" (see createTasSettingsDialog). 1 = checkpoint every frame
    //(fastest scrubbing, most memory); higher trades memory for longer backward-seek replay.
    private int tasSavestateInterval = 1;
    private JDialog tasSettingsDialog;

    //Frame index -> marker name ("" if unlabeled). Rows paint yellow (tasRowColor);
    //fast-travel via Markers dialog or "Select Between Markers".
    private final TreeMap<Integer, String> tasMarkers = new TreeMap<>();
    private JDialog tasMarkersDialog;
    //Copy/paste clipboard for the frame timeline: a snapshot of controller bitmasks, not a live view.
    private List<Integer> tasClipboard = null;
    private JPopupMenu tasContextMenu;

    //"Play Movie": real-time playback of a saved .tas without opening the TAS editor, using tasInputs' array semantics
    //(index 0 = power-on row, frame k reads index k-1) inside run()'s loop. Mutually exclusive with r08 playback - starting
    //either clears the other.
    private List<Integer> playMovieInputs = null;
    private int playMovieFrame = 1; //mirrors tasFrame: starts at 1 (row 0 free via hardReset())

    //.r08 replay-device playback: one (P1, P2) byte pair per frame, MSB-first (A=0x80 down to Right=0x01), the reverse of the
    //internal LSB-first order, so bits go through reverseByte(). See applyR08CurrentPair()/onR08ControllerStrobe() for
    //per-frame vs. per-strobe advance.
    private byte[] r08Data = null;
    private int r08PairIndex = 0;
    private long r08FrameCount = 0; //1-indexed count of frames processed since playback started
    //Per-frame mode only: whether the current frame has been strobed yet - gates revealing the
    //current pair (first strobe only) and whether frame-end should advance to the next one.
    private boolean r08StrobedThisFrame = false;
    private boolean r08AceMode = false;
    private boolean r08TransitionEnabled = false;
    private int r08TransitionFrame = 0;
    private JDialog r08Dialog;
    private JDialog r08ConvertDialog;
    private JCheckBox r08AceModeCheckbox;
    private JCheckBox r08TransitionCheckbox;
    private JTextField r08TransitionField;

    private int lastNonLagFrame;
    private StringBuilder inputlog = new StringBuilder();

    private JComboBox<String> formatBox = new JComboBox();
    private boolean formatBizhawk;
    private boolean converting = false;
    public static int currentFrame;
    private static String SHA1 = "null";
    private static String RomMd5Base64 = ""; //FCEUX-style MD5 of PRG+CHR (no header), base64
    private static String RomName = "null";

    public NESEmulator() {
        super("NESEmulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        //Resizable so the maximize button is enabled (the display panel letterboxes any size); maximizing is
        //treated as "go full screen" (see setFullScreen and the window state listener below).
        setMinimumSize(new Dimension(NESDisplayPanel.VIEW_WIDTH, NESDisplayPanel.VIEW_HEIGHT));
        addWindowStateListener(e -> {
            if (!fullScreen && (e.getNewState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                SwingUtilities.invokeLater(() -> setFullScreen(true));
            }
        });

        //Set once here - AWT walks the owner chain, so every owned JDialog inherits this icon.
        ImageIcon appIcon = new ImageIcon("res/logo.png");
        if (appIcon.getIconWidth() > 0) setIconImage(appIcon.getImage());

        ppu = new PPU();
        apu = new APU();
        bus = new Bus(ppu, apu);
        cpu = new CPU();
        audioPlayer = new AudioPlayer();

        cpu.connectBus(bus);
        bus.connectCPU(cpu);
        ppu.connectCPU(cpu);
        apu.connectCPU(cpu);
        apu.connectAudioPlayer(audioPlayer);
        cpu.connectAPU(apu);
        bus.onControllerStrobe = this::onR08ControllerStrobe;

        displayPanel = new NESDisplayPanel();
        ppu.connectDisplay(displayPanel);
        zapper = new Zapper(ppu, displayPanel);
        bus.connectZapper(zapper);
        installZapperMouseHandling();

        nametableViewerDialog = new NametableViewerDialog(this, ppu);
        ppuInspectorDialog = new PPUInspectorDialog(this, ppu);

        setJMenuBar(createMenuBar());
        setLayout(new BorderLayout());
        add(displayPanel, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);

        createTraceWindow();
        createMemoryInspector();
        createTASMakerWindow();
        createControllerSetupDialog();
        setupController();
        preciseTraceDialog = new PreciseTraceDialog(this, cpu, ppu, bus, displayPanel, new PreciseTraceHost());
    }

    //Bridges the Precise Tracing window to the TAS Maker's frame bookkeeping: tasAdvanceOneFrame() split into
    //begin / per-cycle / finish so the trace thread can step and narrate the cycles in between.
    private final class PreciseTraceHost implements PreciseTraceDialog.Host {
        private boolean grew = false;

        @Override public boolean isTasActive() { return tasActive; }
        @Override public Object emulationLock() { return emulationLock; }

        @Override public String beginTracedFrame() {
            setTasPlaying(false);
            audioPlayer.setMuted(true); //never audible: this is a slowed-down seek, not Play
            grew = tasFrame >= tasInputs.size();
            if (grew) tasInputs.add(0);
            int bits = tasInputs.get(tasFrame - 1);
            preciseTraceRow = tasFrame;
            synchronized (emulationLock) {
                applyTasInput(bits);
                frameTraceBuffer.setLength(0);
                preciseTraceStartCycle = cpu.totalCycles;
            }
            preciseTraceActive = true;
            if (grew) tasTableModel.fireTableRowsInserted(preciseTraceRow, preciseTraceRow);
            return "TAS row " + preciseTraceRow;
        }

        @Override public boolean runTracedCycle() {
            //Same ordering as updateEmulationFrame()'s loop body.
            apu.clock();
            cpu.step(frameTraceBuffer, ppu, tracingEnabled);
            ppu.step();
            ppu.step();
            ppu.step();
            return ppu.consumeVBlankStart();
        }

        @Override public void finishTracedFrame() {
            synchronized (emulationLock) {
                afterFrameEmulated("TAS row " + preciseTraceRow + " (Precise Trace)", preciseTraceStartCycle);
                recordFrameStrobe(preciseTraceRow);
                tasFrame++;
                if (tasFrame > tasValidFrontier) tasValidFrontier = tasFrame;
                maybeCaptureTasCheckpoint(tasFrame);
            }
            preciseTraceActive = false;
            renderFrameWithOverlay();
            tasTableModel.fireTableRowsUpdated(preciseTraceRow, preciseTraceRow);
            syncTasSelection();
            tasTable.repaint();
        }
    }

    private void openPreciseTracing() {
        preciseTraceDialog.refreshAvailability();
        preciseTraceDialog.setVisible(true);
        preciseTraceDialog.toFront();
    }

    //True once a ROM is open; without a cartridge the CPU would execute open bus, so every path
    //that steps the machine is gated on this.
    private boolean romLoaded() { return bus.hasCartridge(); }

    //Gate for user-initiated emulation actions: explains why nothing happened.
    private boolean requireRom() {
        if (romLoaded()) return true;
        JOptionPane.showMessageDialog(this, "No ROM loaded. Open a ROM first (Emulation > Open ROM...).",
                "No ROM", JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    //True (with a message) if an operation must wait for the running precise trace.
    private boolean blockedByPreciseTrace() {
        if (!preciseTraceActive) return false;
        JOptionPane.showMessageDialog(this, "A Precise Trace is running. Finish it (or use \"Finish Frame Instantly\") first.",
                "Precise Tracing active", JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    //Bit order matches Bus's shift-out order (LSB first): A,B,Select,Start,Up,Down,Left,Right.
    private volatile int controller1Bits = 0;

    //While paused, a tap (press+release before Step) latches here instead of dropping,
    //so it still reads as held for the one frame Step runs.
    private volatile int queuedInputBits = 0;

    //Controller setup window components
    private static final String[] BUTTON_NAMES = {"A", "B", "Select", "Start", "Up", "Down", "Left", "Right"};
    private static final int[] BUTTON_BITS = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80};
    private static final int[] DEFAULT_KEYS = {
            KeyEvent.VK_UP, KeyEvent.VK_Z, KeyEvent.VK_SPACE, KeyEvent.VK_ENTER,
            KeyEvent.VK_X, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT
    };
    private final Map<String, Integer> keyBindings = new LinkedHashMap<>();
    private final Map<String, JButton> keybindButtons = new LinkedHashMap<>();
    private JDialog controllerDialog;
    //Port device selectors ("Controller" / "Zapper") for ports 1 and 2
    private static final String[] PORT_DEVICES = {"Controller", "Zapper"};
    private JComboBox<String> port1DeviceCombo;
    private JComboBox<String> port2DeviceCombo;
    private Zapper zapper;
    private JCheckBox allowDpadConflictsCheckbox;
    private JCheckBox showInputOverlayCheckbox;
    //Last controller-1 byte applied to the bus (setController1Tracked), fed to the input overlay before each
    //renderFrame() so a frame with several mid-frame strobes (.r08 ACE mode) shows only the last combination pressed.
    private int lastControllerOverlayBits = 0;
    private int lastController2OverlayBits = 0;
    //When false (default), only the most recently pressed key of each opposing D-pad
    //pair takes effect (real controllers can't hold both); see udPressOrder/lrPressOrder.
    private boolean allowDpadConflicts = false;

    //Physical keys currently held, independent of binding - handles rebind-mid-hold and autorepeat.
    private final Set<Integer> rawKeysHeld = new HashSet<>();
    //Press-order of held keys bound to Up/Down and Left/Right. Only the most recent entry
    //of each is "active" when D-pad conflicts are disallowed.
    private final List<Integer> udPressOrder = new ArrayList<>();
    private final List<Integer> lrPressOrder = new ArrayList<>();

    //Full screen: the frame goes undecorated, loses its menu bar and is stretched over its current monitor; the
    //display panel letterboxes the 256x240 picture itself (NESDisplayPanel.getImageBounds).
    private boolean fullScreen = false;
    private JMenuBar windowedMenuBar;
    private Rectangle windowedBounds;

    public boolean isFullScreen() {
        return fullScreen;
    }

    private void toggleFullScreen() {
        setFullScreen(!fullScreen);
    }

    private void setFullScreen(boolean enable) {
        if (enable == fullScreen) return;
        fullScreen = enable;

        //Changing decorations requires the native window to be rebuilt, which dispose()
        //also does to every owned dialog - remember which ones were open and re-show them.
        Rectangle screenBounds = getGraphicsConfiguration().getBounds();
        List<Window> openDialogs = new ArrayList<>();
        for (Window w : getOwnedWindows()) if (w.isVisible()) openDialogs.add(w);

        //Entered via the maximize button: the bounds now are the maximized ones, so keep the
        //last plain-window bounds we saved instead.
        boolean maximized = (getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        if (enable && !maximized) windowedBounds = getBounds();

        dispose();
        setExtendedState(Frame.NORMAL);
        if (enable) {
            windowedMenuBar = getJMenuBar();
            setJMenuBar(null);
            setUndecorated(true);
            setBounds(screenBounds);
        } else {
            setUndecorated(false);
            setJMenuBar(windowedMenuBar);
            if (windowedBounds != null) {
                setBounds(windowedBounds);
            } else {
                pack();
                setLocationRelativeTo(null);
            }
        }
        setVisible(true);
        for (Window w : openDialogs) w.setVisible(true);
        toFront();
        displayPanel.requestFocusInWindow();
    }

    private void setupController() {
        for (int i = 0; i < BUTTON_NAMES.length; i++) keyBindings.put(BUTTON_NAMES[i], DEFAULT_KEYS[i]);

        //F11 toggles full screen; Escape leaves it. Bound on the panel (not as menu accelerators)
        //because the menu bar is removed while full screen.
        InputMap im = displayPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = displayPanel.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "toggleFullScreen");
        am.put("toggleFullScreen", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { toggleFullScreen(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "exitFullScreen");
        am.put("exitFullScreen", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { setFullScreen(false); }
        });

        displayPanel.setFocusable(true);
        displayPanel.requestFocusInWindow();
        displayPanel.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                String button = buttonForKey(e.getKeyCode());
                if (button == null) return;
                if (rawKeysHeld.add(e.getKeyCode())) {
                    if (button.equals("Up") || button.equals("Down")) {
                        udPressOrder.remove(Integer.valueOf(e.getKeyCode()));
                        udPressOrder.add(e.getKeyCode());
                    } else if (button.equals("Left") || button.equals("Right")) {
                        lrPressOrder.remove(Integer.valueOf(e.getKeyCode()));
                        lrPressOrder.add(e.getKeyCode());
                    }
                }
                recomputeControllerBits();
            }
            @Override public void keyReleased(KeyEvent e) {
                rawKeysHeld.remove(e.getKeyCode());
                udPressOrder.remove(Integer.valueOf(e.getKeyCode()));
                lrPressOrder.remove(Integer.valueOf(e.getKeyCode()));
                recomputeControllerBits();
            }
        });
    }

    private String buttonForKey(int keyCode) {
        for (Map.Entry<String, Integer> entry : keyBindings.entrySet()) {
            if (entry.getValue() == keyCode) return entry.getKey();
        }
        return null;
    }

    //Wraps setController1Tracked() so the input overlay always knows the last controller-1 state
    //actually applied, including intermediate mid-frame strobes.
    private void setController1Tracked(int bits) {
        lastControllerOverlayBits = bits;
        bus.setController1(bits);
    }

    //Wraps setController2Tracked() the same way as setController1Tracked(), for the overlay's P2 half.
    private void setController2Tracked(int bits) {
        lastController2OverlayBits = bits;
        bus.setController2(bits);
    }

    //Feeds this frame's final controller states to the display panel's overlay and renders it -
    //use in place of a bare displayPanel.renderFrame() call.
    private void renderFrameWithOverlay() {
        displayPanel.setOverlayBits(lastControllerOverlayBits, lastController2OverlayBits);
        displayPanel.renderFrame();
    }

    private int bitForButton(String name) {
        for (int i = 0; i < BUTTON_NAMES.length; i++) if (BUTTON_NAMES[i].equals(name)) return BUTTON_BITS[i];
        return 0;
    }

    //Recomputes controller1Bits from held keys, applying the D-pad conflict mask unless disabled.
    private void recomputeControllerBits() {
        int newBits = 0;
        for (String name : new String[]{"A", "B", "Select", "Start"}) {
            if (rawKeysHeld.contains(keyBindings.get(name))) newBits |= bitForButton(name);
        }
        if (allowDpadConflicts) {
            for (String name : new String[]{"Up", "Down", "Left", "Right"}) {
                if (rawKeysHeld.contains(keyBindings.get(name))) newBits |= bitForButton(name);
            }
        } else {
            if (!udPressOrder.isEmpty()) newBits |= bitForButton(buttonForKey(udPressOrder.get(udPressOrder.size() - 1)));
            if (!lrPressOrder.isEmpty()) newBits |= bitForButton(buttonForKey(lrPressOrder.get(lrPressOrder.size() - 1)));
        }

        int prevBits = controller1Bits;
        controller1Bits = newBits;
        int pressedNew = newBits & ~prevBits;
        if (paused) queuedInputBits |= pressedNew;
        //Movie/r08 playback owns controller1 while active (applyPlaybackInputsForFrame() sets it
        //each frame) - a keypress here would otherwise stomp on the current frame's playback input.
        if (!tasActive && playMovieInputs == null && r08Data == null) setController1Tracked(controller1Bits | queuedInputBits);
    }

    private void createControllerSetupDialog() {
        controllerDialog = new JDialog(this, "Controller Setup", false);
        controllerDialog.setLayout(new BorderLayout());

        //Port device selection: a Zapper on a port replaces that port's controller; it is aimed with the
        //mouse over the game screen and fired with the left mouse button.
        JPanel portPanel = new JPanel(new GridLayout(2, 2, 8, 6));
        portPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        port1DeviceCombo = new JComboBox<>(PORT_DEVICES);
        port2DeviceCombo = new JComboBox<>(PORT_DEVICES);
        port1DeviceCombo.addActionListener(e -> applyPortDevices());
        port2DeviceCombo.addActionListener(e -> applyPortDevices());
        portPanel.add(new JLabel("Port 1", SwingConstants.RIGHT));
        portPanel.add(port1DeviceCombo);
        portPanel.add(new JLabel("Port 2", SwingConstants.RIGHT));
        portPanel.add(port2DeviceCombo);

        JPanel gridPanel = new JPanel(new GridLayout(BUTTON_NAMES.length, 2, 8, 6));
        gridPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        for (String name : BUTTON_NAMES) {
            gridPanel.add(new JLabel(name, SwingConstants.RIGHT));
            JButton bindButton = new JButton(KeyEvent.getKeyText(DEFAULT_KEYS[indexOfButton(name)]));
            bindButton.addActionListener(e -> startKeyCapture(name, bindButton));
            keybindButtons.put(name, bindButton);
            gridPanel.add(bindButton);
        }

        allowDpadConflictsCheckbox = new JCheckBox("Allow D-Pad Conflicts", false);
        allowDpadConflictsCheckbox.addActionListener(e -> allowDpadConflicts = allowDpadConflictsCheckbox.isSelected());

        showInputOverlayCheckbox = new JCheckBox("Show Input Overlay", false);
        showInputOverlayCheckbox.addActionListener(e -> displayPanel.setShowInputOverlay(showInputOverlayCheckbox.isSelected()));

        JButton restoreDefaultsButton = new JButton("Restore Defaults");
        restoreDefaultsButton.addActionListener(e -> restoreDefaultKeybinds());

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottomPanel.add(allowDpadConflictsCheckbox);
        bottomPanel.add(showInputOverlayCheckbox);
        bottomPanel.add(restoreDefaultsButton);

        controllerDialog.add(portPanel, BorderLayout.NORTH);
        controllerDialog.add(gridPanel, BorderLayout.CENTER);
        controllerDialog.add(bottomPanel, BorderLayout.SOUTH);
        controllerDialog.pack();
        controllerDialog.setLocationRelativeTo(this);
    }

    private boolean zapperAttached() {
        return bus.isZapperOnPort(1) || bus.isZapperOnPort(2);
    }

    //The mouse aims/fires only while no movie owns the Zapper (TAS Maker open or Play Movie running).
    private boolean zapperMouseLive() {
        return zapperAttached() && !tasActive && playMovieInputs == null;
    }

    private void applyPortDevices() {
        bus.setZapperOnPort(1, port1DeviceCombo.getSelectedIndex() == 1);
        bus.setZapperOnPort(2, port2DeviceCombo.getSelectedIndex() == 1);
        boolean attached = zapperAttached();
        if (!attached) {
            zapper.setAim(null);
            zapper.setTrigger(false);
        }
        displayPanel.setCursor(attached ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
        rebuildTasColumns();
    }

    private void setPortDevice(int port, boolean isZapper) {
        JComboBox<String> combo = port == 1 ? port1DeviceCombo : port2DeviceCombo;
        if (combo.getSelectedIndex() != (isZapper ? 1 : 0)) combo.setSelectedIndex(isZapper ? 1 : 0); //fires applyPortDevices
    }

    //Mouse over the game screen aims the Zapper (letterbox-aware); left button is the trigger.
    //Pointing off the game image counts as pointing away from the TV (no light), which games use for reloading.
    private void installZapperMouseHandling() {
        displayPanel.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) { if (zapperMouseLive()) zapper.setAim(displayPanel.toNesPixel(e.getX(), e.getY())); }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) { if (zapperMouseLive()) zapper.setAim(displayPanel.toNesPixel(e.getX(), e.getY())); }
        });
        displayPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (!zapperMouseLive() || e.getButton() != java.awt.event.MouseEvent.BUTTON1) return;
                zapper.setAim(displayPanel.toNesPixel(e.getX(), e.getY()));
                zapper.setTrigger(true);
                displayPanel.requestFocusInWindow();
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.getButton() == java.awt.event.MouseEvent.BUTTON1) zapper.setTrigger(false);
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { zapper.setAim(null); }
        });
    }

    private int indexOfButton(String name) {
        for (int i = 0; i < BUTTON_NAMES.length; i++) if (BUTTON_NAMES[i].equals(name)) return i;
        return -1;
    }

    //Enters "listening" mode and installs a one-shot global dispatcher to capture the next key,
    //so binding works regardless of dialog focus.
    private void startKeyCapture(String buttonName, JButton bindButton) {
        String previousText = bindButton.getText();
        bindButton.setText("Press any key...");
        KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyEventDispatcher[] dispatcherHolder = new KeyEventDispatcher[1];
        dispatcherHolder[0] = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) return false;
            if (e.getKeyCode() != KeyEvent.VK_ESCAPE) {
                keyBindings.put(buttonName, e.getKeyCode());
                bindButton.setText(KeyEvent.getKeyText(e.getKeyCode()));
            } else {
                bindButton.setText(previousText);
            }
            focusManager.removeKeyEventDispatcher(dispatcherHolder[0]);
            return true;
        };
        focusManager.addKeyEventDispatcher(dispatcherHolder[0]);
    }

    private void restoreDefaultKeybinds() {
        for (int i = 0; i < BUTTON_NAMES.length; i++) {
            keyBindings.put(BUTTON_NAMES[i], DEFAULT_KEYS[i]);
            keybindButtons.get(BUTTON_NAMES[i]).setText(KeyEvent.getKeyText(DEFAULT_KEYS[i]));
        }
    }

    //Backs the memory inspector table ($0000-$FFFF, 16 bytes/row, plus address/ASCII columns), reading live from the
    //bus each paint; scanMemoryForChanges fires targeted fireTableCellUpdated() calls to avoid flicker/scroll reset.
    private class MemoryTableModel extends AbstractTableModel {
        static final int ROWS = 4096;
        static final int COLS = 18; //address + 16 bytes + ascii

        @Override public int getRowCount() { return ROWS; }
        @Override public int getColumnCount() { return COLS; }

        @Override
        public String getColumnName(int col) {
            if (col == 0) return "Address";
            if (col == 17) return "ASCII";
            return String.format("%02X", col - 1);
        }

        @Override
        public Object getValueAt(int row, int col) {
            int base = row * 16;
            if (col == 0) return String.format("$%04X", base);
            if (col == 17) {
                StringBuilder ascii = new StringBuilder(16);
                for (int i = 0; i < 16; i++) {
                    int val = bus.debugRead(base + i) & 0xFF;
                    ascii.append((val >= 0x20 && val < 0x7F) ? (char) val : '.');
                }
                return ascii.toString();
            }
            return String.format("%02X", bus.debugRead(base + (col - 1)) & 0xFF);
        }

    }

    private static final Color FROZEN_CELL_COLOR = new Color(0xADD8E6);

    //Colors a memory inspector cell light blue when its RAM address is frozen.
    private class FrozenAwareCellRenderer extends DefaultTableCellRenderer {
        FrozenAwareCellRenderer(int alignment) { setHorizontalAlignment(alignment); }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int address = row * 16 + (column - 1);
            if (!isSelected) {
                c.setBackground(bus.isRamAddressFrozen(address) ? FROZEN_CELL_COLOR : Color.WHITE);
            }
            return c;
        }
    }

    //Column order/letters: A, B, select, start, Up, Down, Left, Right.
    private static final int[] TAS_BUTTON_BITS = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80};
    private static final String[] TAS_BUTTON_NAMES = {"A", "B", "s", "S", "U", "D", "L", "R"};

    //Zapper input is packed into the same per-frame int as the controller-1 bits so every list operation (copy/paste,
    //insert, save/load, blank frame = 0) keeps working unchanged: bits 8-16 = X+1 and bits 17-25 = Y+1 (0 = that
    //coordinate is blank; a blank coordinate means the gun points off screen), bit 26 = trigger pulled.
    //There is one Zapper record per frame; it drives whichever port(s) have "Zapper" selected in Controller Setup.
    private static final int TAS_ZAPPER_X_SHIFT = 8;
    private static final int TAS_ZAPPER_Y_SHIFT = 17;
    private static final int TAS_ZAPPER_COORD_MASK = 0x1FF;
    private static final int TAS_ZAPPER_TRIGGER_BIT = 1 << 26;
    private static int tasZapperX(int v) { return ((v >> TAS_ZAPPER_X_SHIFT) & TAS_ZAPPER_COORD_MASK) - 1; }
    private static int tasZapperY(int v) { return ((v >> TAS_ZAPPER_Y_SHIFT) & TAS_ZAPPER_COORD_MASK) - 1; }
    private static boolean tasZapperTrigger(int v) { return (v & TAS_ZAPPER_TRIGGER_BIT) != 0; }
    //x/y of -1 = blank. Each coordinate is stored independently so typing X before Y doesn't lose the X.
    private static int tasWithZapperAim(int v, int x, int y) {
        x = Math.max(-1, Math.min(x, NESDisplayPanel.VIEW_WIDTH - 1));
        y = Math.max(-1, Math.min(y, NESDisplayPanel.VIEW_HEIGHT - 1));
        v &= ~((TAS_ZAPPER_COORD_MASK << TAS_ZAPPER_X_SHIFT) | (TAS_ZAPPER_COORD_MASK << TAS_ZAPPER_Y_SHIFT));
        return v | ((x + 1) << TAS_ZAPPER_X_SHIFT) | ((y + 1) << TAS_ZAPPER_Y_SHIFT);
    }

    //Timeline columns depend on what is plugged into the ports: a Controller on port 1 shows the eight button
    //columns, a Zapper on either port shows X / Y (typed) and T (trigger toggle). Port 2 controllers aren't
    //recorded by the TAS Maker, so a Controller on port 2 adds nothing.
    private enum TasColumnKind { FRAME, BUTTON, ZAPPER_X, ZAPPER_Y, ZAPPER_TRIGGER }
    private static final class TasColumn {
        final TasColumnKind kind;
        final String name;
        final int bit; //for BUTTON / ZAPPER_TRIGGER
        TasColumn(TasColumnKind kind, String name, int bit) {
            this.kind = kind;
            this.name = name;
            this.bit = bit;
        }
        boolean isToggle() { return kind == TasColumnKind.BUTTON || kind == TasColumnKind.ZAPPER_TRIGGER; }
        boolean isCoord() { return kind == TasColumnKind.ZAPPER_X || kind == TasColumnKind.ZAPPER_Y; }
    }
    private List<TasColumn> tasColumns = new ArrayList<>();

    private List<TasColumn> computeTasColumns() {
        List<TasColumn> cols = new ArrayList<>();
        cols.add(new TasColumn(TasColumnKind.FRAME, "Frame", 0));
        boolean zapper1 = bus != null && bus.isZapperOnPort(1);
        boolean zapper2 = bus != null && bus.isZapperOnPort(2);
        if (!zapper1) {
            for (int i = 0; i < TAS_BUTTON_BITS.length; i++) {
                cols.add(new TasColumn(TasColumnKind.BUTTON, TAS_BUTTON_NAMES[i], TAS_BUTTON_BITS[i]));
            }
        }
        if (zapper1 || zapper2) {
            cols.add(new TasColumn(TasColumnKind.ZAPPER_X, "X", 0));
            cols.add(new TasColumn(TasColumnKind.ZAPPER_Y, "Y", 0));
            cols.add(new TasColumn(TasColumnKind.ZAPPER_TRIGGER, "T", TAS_ZAPPER_TRIGGER_BIT));
        }
        return cols;
    }

    //Recomputes the column set after a port device change; safe before the TAS window exists.
    private void rebuildTasColumns() {
        tasColumns = computeTasColumns();
        if (tasTable == null) return;
        if (tasTable.isEditing()) tasTable.getCellEditor().cancelCellEditing();
        suppressTasSelectionEvents = true;
        try {
            tasTableModel.fireTableStructureChanged();
        } finally {
            suppressTasSelectionEvents = false;
        }
        for (int i = 0; i < tasColumns.size(); i++) tasTable.getColumnModel().getColumn(i).setPreferredWidth(50);
        syncTasSelection();
    }

    //Blank/whitespace = off screen (-1); anything unparsable = null (leave the cell alone).
    private static Integer parseTasCoord(Object value) {
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty() || text.equals("-")) return -1;
        try {
            return Math.max(-1, Integer.parseInt(text));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    //Backs the TAS Maker's frame timeline; tasInputs is the sole backing store, this
    //model just reads/writes it (setFrameValue handles invalidation).
    private class TasTableModel extends AbstractTableModel {
        @Override public int getRowCount() { return tasInputs.size(); }
        @Override public int getColumnCount() { return tasColumns.size(); }

        @Override
        public String getColumnName(int col) {
            return tasColumns.get(col).name;
        }

        @Override
        public Class<?> getColumnClass(int col) {
            switch (tasColumns.get(col).kind) {
                case FRAME: return Integer.class;
                case ZAPPER_X: case ZAPPER_Y: return String.class;
                default: return Boolean.class;
            }
        }

        //Toggle cells (buttons, trigger) are flipped via the mouse listeners in createTASMakerWindow, not JTable's
        //own edit machinery - editability off there prevents a double-toggle. Zapper X/Y are typed, so they do edit.
        @Override
        public boolean isCellEditable(int row, int col) {
            return tasColumns.get(col).isCoord();
        }

        @Override
        public Object getValueAt(int row, int col) {
            TasColumn c = tasColumns.get(col);
            int v = tasInputs.get(row);
            switch (c.kind) {
                case FRAME: return row;
                case ZAPPER_X: { int x = tasZapperX(v); return x < 0 ? "" : Integer.toString(x); }
                case ZAPPER_Y: { int y = tasZapperY(v); return y < 0 ? "" : Integer.toString(y); }
                default: return (v & c.bit) != 0;
            }
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            TasColumn c = tasColumns.get(col);
            if (c.isToggle()) {
                editFrameInput(row, c.bit, Boolean.TRUE.equals(value));
            } else if (c.isCoord()) {
                Integer n = parseTasCoord(value);
                if (n == null) return;
                editFrameZapperCoord(row, c.kind == TasColumnKind.ZAPPER_X, n);
            }
        }
    }

    private static final Color TAS_LAG_FRAME_COLOR = new Color(0xF4A0A0);      //red: emulated, controllers never strobed
    private static final Color TAS_NORMAL_FRAME_COLOR = new Color(0xA8E6A8);   //green: emulated, controllers strobed
    private static final Color TAS_UNEMULATED_COLOR = Color.WHITE;             //past tasValidFrontier: no savestate yet
    private static final Color TAS_MARKER_COLOR = Color.YELLOW;                //marked frame - takes priority over validity coloring

    //Shared by both TAS table renderers: marked frame is yellow, unemulated rows (>= tasValidFrontier) are plain,
    //otherwise colored by whether an input on this row would be read. Row R's input isn't polled until frame R+1 strobes
    //(one-frame latency), so this checks R+1's strobe - the last emulated row reads red until the next frame runs.
    private Color tasRowColor(int row) {
        if (tasMarkers.containsKey(row)) return TAS_MARKER_COLOR;
        if (row >= tasValidFrontier) return TAS_UNEMULATED_COLOR;
        int pollingFrame = row + 1;
        boolean strobed = pollingFrame < tasFrameStrobed.size() && tasFrameStrobed.get(pollingFrame);
        return strobed ? TAS_NORMAL_FRAME_COLOR : TAS_LAG_FRAME_COLOR;
    }

    private class TasFrameNumberRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) c.setBackground(tasRowColor(row));
            return c;
        }
    }

    //One grid cell of the button timeline: colored box showing the button letter when pressed.
    private class TasButtonBox extends JComponent {
        private boolean pressed = false;
        private String letter = "";

        TasButtonBox() {
            setOpaque(true);
        }

        void setPressed(boolean pressed) { this.pressed = pressed; }
        void setLetter(String letter) { this.letter = letter; }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            if (pressed) {
                g.setColor(Color.BLACK);
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                FontMetrics fm = g.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(letter)) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g.drawString(letter, x, y);
            }
        }
    }

    private class TasButtonRenderer extends TasButtonBox implements TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            setPressed(Boolean.TRUE.equals(value));
            setLetter(tasColumns.get(table.convertColumnIndexToModel(column)).name);
            setBackground(isSelected ? table.getSelectionBackground() : tasRowColor(row));
            return this;
        }
    }

    //Zapper X / Y cells: the typed coordinate (blank = off screen) on the row's validity color.
    private class TasCoordRenderer extends DefaultTableCellRenderer {
        TasCoordRenderer() { setHorizontalAlignment(SwingConstants.CENTER); }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            c.setBackground(isSelected ? table.getSelectionBackground() : tasRowColor(row));
            return c;
        }
    }


    private void createMemoryInspector() {
        memoryDialog = new JDialog(this, "Memory Inspector ($0000-$FFFF)", false);
        memoryDialog.setSize(820, 560);
        memoryDialog.setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Go to $:"));
        memoryGotoField = new JTextField(6);
        topPanel.add(memoryGotoField);

        JButton gotoButton = new JButton("Go");
        gotoButton.addActionListener(e -> gotoMemoryAddress());
        memoryGotoField.addActionListener(e -> gotoMemoryAddress());
        topPanel.add(gotoButton);

        memoryTableModel = new MemoryTableModel();
        memoryTable = new JTable(memoryTableModel);
        memoryTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
        memoryTable.setRowHeight(18);
        memoryTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        memoryTable.setCellSelectionEnabled(true);

        DefaultTableCellRenderer centered = new FrozenAwareCellRenderer(SwingConstants.CENTER);
        for (int col = 0; col < MemoryTableModel.COLS; col++) {
            TableColumn column = memoryTable.getColumnModel().getColumn(col);
            if (col == 0) {
                column.setPreferredWidth(70);
            } else if (col == 17) {
                column.setPreferredWidth(140);
                column.setCellRenderer(new DefaultTableCellRenderer());
            } else {
                column.setPreferredWidth(28);
                column.setCellRenderer(centered);
            }
        }

        Arrays.fill(memorySnapshot, -1);

        installMemoryContextMenu();

        memoryDialog.add(topPanel, BorderLayout.NORTH);
        memoryDialog.add(new JScrollPane(memoryTable), BorderLayout.CENTER);
    }

    //Right-click on a byte cell (columns 1-16) opens an Override/Freeze menu for that address.
    private void installMemoryContextMenu() {
        memoryTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybeShowPopup(e); }

            private void maybeShowPopup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = memoryTable.rowAtPoint(e.getPoint());
                int col = memoryTable.columnAtPoint(e.getPoint());
                if (row < 0 || col < 1 || col > 16) return;
                int address = row * 16 + (col - 1);
                if (address > 0x1FFF) return; //only RAM supports Override/Freeze
                memoryTable.setRowSelectionInterval(row, row);
                memoryTable.setColumnSelectionInterval(col, col);
                showMemoryContextMenu(address, e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private void showMemoryContextMenu(int address, Component invoker, int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem overrideItem = new JMenuItem("Override...");
        overrideItem.addActionListener(e -> overrideMemoryAddress(address));
        menu.add(overrideItem);

        boolean frozen = bus.isRamAddressFrozen(address);
        JMenuItem freezeItem = new JMenuItem(frozen ? "Unfreeze" : "Freeze");
        freezeItem.addActionListener(e -> {
            if (bus.isRamAddressFrozen(address)) {
                bus.unfreezeRamAddress(address);
            } else {
                bus.freezeRamAddress(address, bus.debugRead(address));
            }
            memorySnapshot[address] = -1; //force repaint next scan
        });
        menu.add(freezeItem);

        menu.show(invoker, x, y);
    }

    private void overrideMemoryAddress(int address) {
        String current = String.format("%02X", bus.debugRead(address) & 0xFF);
        String input = JOptionPane.showInputDialog(memoryDialog,
                String.format("Enter a hex byte value for $%04X:", address), current);
        if (input == null) return;
        try {
            int value = Integer.parseInt(input.trim().replace("$", "").replace("0x", ""), 16);
            if (value < 0 || value > 0xFF) throw new NumberFormatException();
            bus.overrideRamAddress(address, (byte) value);
            memorySnapshot[address] = -1;
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(memoryDialog, "Enter a valid hex byte (00-FF)", "Invalid Value", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void createTASMakerWindow() {
        tasDialog = new JDialog(this, "TAS Maker", false);
        tasDialog.setSize(520, 600);
        tasDialog.setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        tasPlayButton = new JToggleButton("Play");
        tasPlayButton.addActionListener(e -> setTasPlaying(tasPlayButton.isSelected()));
        topPanel.add(tasPlayButton);

        JButton insertButton = new JButton("Insert Frames...");
        insertButton.addActionListener(e -> insertTasFrames());
        topPanel.add(insertButton);

        JButton markersButton = new JButton("Markers");
        markersButton.addActionListener(e -> openMarkersDialog());
        topPanel.add(markersButton);

        JButton addInputsButton = new JButton("Add Inputs");
        addInputsButton.addActionListener(e -> showAddInputsMenu(addInputsButton));
        topPanel.add(addInputsButton);

        JButton saveButton = new JButton("Save TAS...");
        saveButton.addActionListener(e -> saveTas());
        topPanel.add(saveButton);

        JButton loadButton = new JButton("Load TAS...");
        loadButton.addActionListener(e -> loadTas());
        topPanel.add(loadButton);

        JButton settingsButton = new JButton("Settings...");
        settingsButton.addActionListener(e -> openTasSettingsDialog());
        topPanel.add(settingsButton);

        tasTableModel = new TasTableModel();
        //Only the Frame# column drives selection/playhead; button cells are painted, never selected. This only sees
        //left-button drags (JTable routes right-button separately), tasFrameColumnDragging keeps a drag begun on Frame#
        //scrubbing if the cursor strays off-column, and extend=false keeps it single-row.
        tasTable = new JTable(tasTableModel) {
            @Override
            public void changeSelection(int rowIndex, int columnIndex, boolean toggle, boolean extend) {
                if (columnIndex != 0 && !tasFrameColumnDragging) return;
                super.changeSelection(rowIndex, 0, false, false);
            }
        };
        tasTable.setRowHeight(20);
        tasTable.setDefaultRenderer(Integer.class, new TasFrameNumberRenderer());
        tasTable.setDefaultRenderer(Boolean.class, new TasButtonRenderer());
        tasTable.setDefaultRenderer(String.class, new TasCoordRenderer());
        //Zapper X / Y: a single click opens the cell for typing; Enter or clicking elsewhere commits.
        JTextField coordField = new JTextField();
        coordField.setHorizontalAlignment(SwingConstants.CENTER);
        DefaultCellEditor coordEditor = new DefaultCellEditor(coordField);
        coordEditor.setClickCountToStart(1);
        tasTable.setDefaultEditor(String.class, coordEditor);
        tasTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        tasTable.setSurrendersFocusOnKeystroke(true);
        rebuildTasColumns();
        //Left drag on Frame# scrubs the playhead; right drag range-selects without moving it.
        tasTable.getSelectionModel().setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        //Doesn't skip isAdjusting events - a left-drag scrub must seek on every intermediate row.
        //Right-drag range-selection uses suppressTasSelectionEvents so it never reaches here.
        tasTable.getSelectionModel().addListSelectionListener(e -> {
            if (tasPlaying || suppressTasSelectionEvents) return;
            int[] rows = tasTable.getSelectedRows();
            if (rows.length == 1) seekToFrame(rows[0] + 1);
        });

        tasContextMenu = buildTasContextMenu();
        tasTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { maybeShowTasContextMenu(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybeShowTasContextMenu(e); }
        });

        //Tracks whether the current drag started on the Frame# column (see changeSelection above).
        tasTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                tasFrameColumnDragging = javax.swing.SwingUtilities.isLeftMouseButton(e)
                        && tasTable.columnAtPoint(e.getPoint()) == 0;
            }

            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) tasFrameColumnDragging = false;
            }
        });

        //Right click+drag range-selects instead of scrubbing, handled directly against the
        //selection model since JTable's own handling never sees right-button events.
        tasTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (!javax.swing.SwingUtilities.isRightMouseButton(e)) return;
                int row = tasTable.rowAtPoint(e.getPoint());
                if (row < 0) { tasRightDragAnchor = -1; return; }
                tasRightDragAnchor = row;
                suppressTasSelectionEvents = true;
                try {
                    tasTable.setRowSelectionInterval(row, row);
                } finally {
                    suppressTasSelectionEvents = false;
                }
            }

            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (javax.swing.SwingUtilities.isRightMouseButton(e)) tasRightDragAnchor = -1;
            }
        });
        tasTable.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (tasRightDragAnchor < 0) return;
                int row = tasTable.rowAtPoint(e.getPoint());
                if (row < 0) {
                    Rectangle visible = tasTable.getVisibleRect();
                    row = e.getY() < visible.y ? 0 : tasTableModel.getRowCount() - 1;
                }
                if (row < 0 || row >= tasTableModel.getRowCount()) return;
                suppressTasSelectionEvents = true;
                try {
                    tasTable.setRowSelectionInterval(Math.min(tasRightDragAnchor, row), Math.max(tasRightDragAnchor, row));
                } finally {
                    suppressTasSelectionEvents = false;
                }
                tasTable.repaint();
            }
        });

        //Press-and-hold "painting" (FCEUX/BizHawk-style): press toggles a cell and sets the
        //paint value, dragging stamps that value onto every row passed over in that column.
        tasTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) return;
                int col = tasTable.columnAtPoint(e.getPoint());
                int row = tasTable.rowAtPoint(e.getPoint());
                if (col >= 1 && row >= 0 && row < tasInputs.size()) {
                    TasColumn column = tasColumns.get(tasTable.convertColumnIndexToModel(col));
                    if (!column.isToggle()) return; //X / Y cells are typed, not painted
                    int bit = column.bit;
                    tasPaintColumn = col;
                    tasPaintBit = bit;
                    tasPaintValue = (tasInputs.get(row) & bit) == 0;
                    tasPaintLastRow = row;
                    editFrameInput(row, bit, tasPaintValue);
                }
            }

            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                tasPaintColumn = null;
                tasPaintLastRow = -1;
            }
        });
        tasTable.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (tasPaintColumn == null) return;
                int col = tasTable.columnAtPoint(e.getPoint());
                if (col != tasPaintColumn) return;
                int row = tasTable.rowAtPoint(e.getPoint());
                if (row < 0) return;
                paintTasRow(row);
            }
        });

        JScrollPane tasScrollPane = new JScrollPane(tasTable);
        //Lets scrubbing/painting continue via the scroll wheel while the mouse is held down,
        //instead of only on drag movement. Only applies to whichever gesture was already active.
        tasScrollPane.addMouseWheelListener(e -> {
            if (tasPlaying) return;
            if (!tasFrameColumnDragging && tasPaintColumn == null) return;
            javax.swing.SwingUtilities.invokeLater(() -> {
                Point p = javax.swing.SwingUtilities.convertPoint(tasScrollPane, e.getPoint(), tasTable);
                int row = tasTable.rowAtPoint(p);
                if (row < 0) row = p.y < 0 ? 0 : tasTableModel.getRowCount() - 1;
                if (row < 0 || row >= tasTableModel.getRowCount()) return;
                if (tasFrameColumnDragging) {
                    if (row != tasTable.getSelectedRow()) tasTable.setRowSelectionInterval(row, row);
                } else if (tasTable.columnAtPoint(p) == tasPaintColumn) {
                    paintTasRow(row);
                }
            });
        });

        tasDialog.add(topPanel, BorderLayout.NORTH);
        tasDialog.add(tasScrollPane, BorderLayout.CENTER);

        tasDialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { closeTasMaker(); }
        });

        tasPlayTimer = new javax.swing.Timer(1000 / 60, e -> tasAdvanceOneFrame());
    }

    //Extensible settings dialog: each setting is one row via addIntSetting on a vertical
    //GridBagLayout list. Built lazily once and re-shown.
    private void createTasSettingsDialog() {
        tasSettingsDialog = new JDialog(tasDialog, "TAS Maker Settings", false);
        tasSettingsDialog.setLayout(new BorderLayout());

        JPanel settingsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;

        addIntSetting(settingsPanel, gbc, "Frames per savestate:", 1, 3600,
                () -> tasSavestateInterval,
                v -> tasSavestateInterval = v);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearButton = new JButton("Clear Savestates");
        clearButton.setToolTipText("Frees all cached TAS Maker checkpoints without touching recorded inputs - "
                + "the next seek just replays from power-on again until new checkpoints accumulate.");
        clearButton.addActionListener(e -> tasCheckpoints.clear());
        buttonPanel.add(clearButton);

        tasSettingsDialog.add(settingsPanel, BorderLayout.CENTER);
        tasSettingsDialog.add(buttonPanel, BorderLayout.SOUTH);
        tasSettingsDialog.pack();
        tasSettingsDialog.setLocationRelativeTo(tasDialog);
    }

    //Adds one "label + spinner" row bound to an int field via getter/setter; commits on every change.
    private void addIntSetting(JPanel panel, GridBagConstraints gbc, String label, int min, int max,
                                java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) {
        gbc.gridx = 0;
        panel.add(new JLabel(label), gbc);

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(getter.getAsInt(), min, max, 1));
        spinner.addChangeListener(e -> setter.accept((Integer) spinner.getValue()));
        gbc.gridx = 1;
        panel.add(spinner, gbc);

        gbc.gridy++;
    }

    private void openTasSettingsDialog() {
        if (tasSettingsDialog == null) createTasSettingsDialog();
        tasSettingsDialog.setVisible(true);
    }

    //Drag-painting state for the button-timeline grid; see mouse listeners in createTASMakerWindow.
    private boolean tasFrameColumnDragging = false;
    //Anchor row of an in-progress right-drag-select on Frame#, or -1 if none active.
    private int tasRightDragAnchor = -1;
    private Integer tasPaintColumn = null;
    private int tasPaintBit = 0;
    private boolean tasPaintValue;
    private int tasPaintLastRow = -1;

    //Stamps tasPaintValue onto rows between tasPaintLastRow and row (inclusive), clamped to bounds.
    private void paintTasRow(int row) {
        if (tasPaintColumn == null) return;
        row = Math.max(0, Math.min(row, tasInputs.size() - 1));
        if (row == tasPaintLastRow) return;
        int bit = tasPaintBit;
        int step = row > tasPaintLastRow ? 1 : -1;
        for (int r = tasPaintLastRow + step; ; r += step) {
            editFrameInput(r, bit, tasPaintValue);
            if (r == row) break;
        }
        tasPaintLastRow = row;
    }

    private void openTasMaker() {
        if (!requireRom()) return;
        tasActive = true;
        audioPlayer.setMuted(true);
        //Frame 0 must mean power-on; reset here the first time so pre-TAS-Maker play doesn't
        //shift that. Skip if a movie/playhead already exists (reopening the TAS Maker).
        if (tasFrame == 0 && tasInputs.isEmpty()) {
            synchronized (emulationLock) {
                bus.hardReset();
            }
            //Row 0 is hardware's pre-execution power-on state: it always exists and needs no simulation (bus.hardReset()
            //above IS that state), so tasFrame starts at 1 ("row 0 exists, nothing beyond it yet").
            tasInputs.add(0);
            tasFrame = 1;
            tasValidFrontier = 1;
            tasFrameStrobed.clear();
            tasFrameStrobed.add(false); //row 0 is never polled - nothing has booted yet
            tasCheckpoints.clear();
            renderFrameWithOverlay();
        }
        tasTableModel.fireTableDataChanged();
        syncTasSelection();
        tasDialog.setVisible(true);
        if (preciseTraceDialog != null) preciseTraceDialog.refreshAvailability();
    }

    private void closeTasMaker() {
        if (blockedByPreciseTrace()) return;
        setTasPlaying(false);
        tasActive = false;
        if (preciseTraceDialog != null) preciseTraceDialog.refreshAvailability();
        audioPlayer.setMuted(false);
        tasDialog.setVisible(false);
        //Hand controller 1 back to the keyboard and the Zapper back to the mouse.
        zapper.endScripted();
        setController1Tracked(controller1Bits | queuedInputBits);
    }

    private void setTasPlaying(boolean playing) {
        if (playing && preciseTraceActive) {
            if (tasPlayButton != null) tasPlayButton.setSelected(false);
            blockedByPreciseTrace();
            return;
        }
        tasPlaying = playing;
        if (tasPlayButton != null) {
            tasPlayButton.setSelected(playing);
            tasPlayButton.setText(playing ? "Pause" : "Play");
        }
        if (tasPlayTimer != null) {
            if (playing) tasPlayTimer.start(); else tasPlayTimer.stop();
        }
        //Stopping Play routes subsequent frames through the always-muted seekToFrame path.
        if (!playing) audioPlayer.setMuted(true);
    }

    //Runs one more movie frame, appending a blank frame first if playback runs off the
    //recorded end - lets the timeline grow as you play.
    private void tasAdvanceOneFrame() {
        if (preciseTraceActive) return;
        boolean grew = tasFrame >= tasInputs.size();
        if (grew) tasInputs.add(0);
        //Row 0 is pre-seeded by openTasMaker()/loadTas() with tasFrame starting at 1, so tasInputs.get(tasFrame-1)
        //always has a real row behind it.
        int bits = tasInputs.get(tasFrame - 1);
        //Play is real-time, so always audible.
        audioPlayer.setMuted(false);
        int executedRow = tasFrame;
        synchronized (emulationLock) {
            applyTasInput(bits);
            updateEmulationFrame("TAS row " + executedRow + " (Play)");
            recordFrameStrobe(executedRow);
            tasFrame++;
            if (tasFrame > tasValidFrontier) tasValidFrontier = tasFrame;
            maybeCaptureTasCheckpoint(tasFrame);
        }
        renderFrameWithOverlay();
        if (grew) tasTableModel.fireTableRowsInserted(executedRow, executedRow);
        else tasTableModel.fireTableRowsUpdated(executedRow, executedRow);
        syncTasSelection();
    }

    //Moves the playhead to right after frame targetFrame-1: forward seeks to a still-valid target keep running from
    //the live state, otherwise the nearest greenzone checkpoint at/before targetFrame is restored and the rest replayed
    //(full power-on replay if none). Seeking never shrinks tasValidFrontier - only editFrameInput does.
    private void seekToFrame(int targetFrame) {
        if (targetFrame == tasFrame) return;
        if (preciseTraceActive) { syncTasSelection(); return; }
        //Any seek/scrub is never real-time, so always silent. Only tasAdvanceOneFrame
        //(Play) or normal non-TAS emulation ever unmute.
        audioPlayer.setMuted(true);
        synchronized (emulationLock) {
            //Forward-continuing is only safe while tasFrame <= tasValidFrontier (an edit can leave
            //tasFrame ahead of a shrunk frontier without moving the playhead - see editFrameInput).
            int replayFrom;
            if (targetFrame > tasFrame && tasFrame <= tasValidFrontier) {
                replayFrom = tasFrame;
            } else {
                Integer checkpointKey = tasCheckpoints.floorKey(targetFrame);
                if (checkpointKey != null) {
                    TasCheckpoint checkpoint = tasCheckpoints.get(checkpointKey);
                    bus.restore(checkpoint.state);
                    displayPanel.restorePixels(checkpoint.pixels);
                    replayFrom = checkpointKey;
                } else {
                    bus.hardReset();
                    //Row 0 always exists for free right after hardReset() (see openTasMaker()/loadTas()), so the rebuild
                    //baseline is position 1, not 0; tasInputs[0] is movie content and stays untouched.
                    replayFrom = 1;
                }
            }
            for (int i = replayFrom; i < targetFrame; i++) {
                int bits = i - 1 < tasInputs.size() ? tasInputs.get(i - 1) : 0;
                applyTasInput(bits);
                updateEmulationFrame("TAS row " + i + " (seek to " + targetFrame + ")");
                recordFrameStrobe(i);
                maybeCaptureTasCheckpoint(i + 1);
            }
            tasFrame = targetFrame;
        }
        if (targetFrame > tasValidFrontier) tasValidFrontier = targetFrame;
        renderFrameWithOverlay();
        syncTasSelection();
        tasTable.repaint();
    }

    //Applies one movie frame to the hardware: controller 1 bits plus, when a Zapper is selected on a port, the frame's
    //recorded aim/trigger (which overrides the mouse for as long as the TAS Maker or movie playback is running).
    private void applyTasInput(int v) {
        setController1Tracked(v & 0xFF);
        if (zapperAttached()) zapper.setScriptedState(tasZapperX(v), tasZapperY(v), tasZapperTrigger(v));
    }

    //Captures a greenzone checkpoint for executedCount frames if it's on the grid and not cached. CPU.State needs an
    //instruction boundary and a frame doesn't divide evenly into CPU cycles, so this clocks up to ~7 extra cycles until
    //CPU.isAtInstructionBoundary(); controller bits are NOT touched because those cycles still belong to the finished
    //frame (switching to the next row's input early caused a real desync).
    private void maybeCaptureTasCheckpoint(int executedCount) {
        if (executedCount <= 1) return; //position 1 (row 0 only) is free via bus.hardReset()
        if (tasSavestateInterval <= 0 || executedCount % tasSavestateInterval != 0) return;
        if (tasCheckpoints.containsKey(executedCount)) return;
        while (!cpu.isAtInstructionBoundary()) {
            apu.clock();
            cpu.step(frameTraceBuffer, ppu, tracingEnabled);
            ppu.step();
            ppu.step();
            ppu.step();
        }
        tasCheckpoints.put(executedCount, new TasCheckpoint(bus.snapshot(), displayPanel.snapshotPixels()));
    }

    //Drops checkpoints past newFrontier - same cutoff editFrameInput/insertTasFrames use for
    //tasValidFrontier (a checkpoint at key == newFrontier is still valid).
    private void invalidateTasCheckpointsFrom(int newFrontier) {
        tasCheckpoints.tailMap(newFrontier, false).clear();
    }

    //Captures whether controller 1 was strobed this frame, for lag-frame coloring.
    private void recordFrameStrobe(int frameIndex) {
        boolean strobed = bus.consumeStrobedThisFrame();
        while (tasFrameStrobed.size() <= frameIndex) tasFrameStrobed.add(false);
        tasFrameStrobed.set(frameIndex, strobed);
    }

    private boolean suppressTasSelectionEvents = false;
    private void syncTasSelection() {
        if (tasTable == null) return;
        int row = tasFrame - 1;
        suppressTasSelectionEvents = true;
        try {
            if (row >= 0 && row < tasTableModel.getRowCount()) {
                tasTable.setRowSelectionInterval(row, row);
                tasTable.scrollRectToVisible(tasTable.getCellRect(row, 0, true));
            } else {
                tasTable.clearSelection();
            }
        } finally {
            suppressTasSelectionEvents = false;
        }
    }

    //Toggles a button box. Since a press on row `row` isn't read until frame row+1, this
    //invalidates frame row+1 onward (like FCEUX/BizHawk greenzone invalidation) without
    //deleting any frames or touching the playhead - seekToFrame handles the shrunk frontier.
    private void editFrameInput(int row, int bit, boolean value) {
        int bits = tasInputs.get(row);
        bits = value ? (bits | bit) : (bits & ~bit);
        setFrameValue(row, bits);
    }

    //Types one Zapper coordinate (-1 = blank / off screen) into a row.
    private void editFrameZapperCoord(int row, boolean isX, int n) {
        int v = tasInputs.get(row);
        int x = isX ? n : tasZapperX(v);
        int y = isX ? tasZapperY(v) : n;
        setFrameValue(row, tasWithZapperAim(v, x, y));
    }

    //Replaces a frame's packed input value with the usual greenzone invalidation.
    private void setFrameValue(int row, int bits) {
        if (tasInputs.get(row) == bits) return;
        tasInputs.set(row, bits);
        if (row + 1 < tasValidFrontier) {
            tasValidFrontier = row + 1;
            invalidateTasCheckpointsFrom(tasValidFrontier);
        }
        tasTableModel.fireTableRowsUpdated(row, row);
        tasTable.repaint();
    }

    //Inserts blank frames after the selected row (or at the end), like FCEUX/BizHawk's "Insert Frames".
    private void insertTasFrames() {
        String input = JOptionPane.showInputDialog(tasDialog, "Number of blank frames to insert:", "1");
        if (input == null) return;
        int count;
        try {
            count = Integer.parseInt(input.trim());
        } catch (NumberFormatException ex) {
            return;
        }
        if (count <= 0) return;

        int selected = tasSelectionAnchor();
        int insertPos = (selected >= 0) ? selected + 1 : tasInputs.size();
        insertBlankFramesAt(insertPos, count);
    }

    private void insertOneTasFrame() {
        int selected = tasSelectionAnchor();
        int insertPos = (selected >= 0) ? selected + 1 : tasInputs.size();
        insertBlankFramesAt(insertPos, 1);
    }

    //Shared by "Insert", "Insert Frames...", and paste-insert: splices `count` blank frames in at insertPos,
    //pushing later frames forward, then fixes up validity/checkpoints/markers/playhead. See insertClipboardAt
    //for the copied-frames equivalent.
    private void insertBlankFramesAt(int insertPos, int count) {
        List<Integer> blanks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) blanks.add(0);
        insertFramesAt(insertPos, blanks);
    }

    //Splices `frames` into tasInputs at insertPos, pushing existing frames forward. Shared by
    //blank inserts and paste-insert.
    private void insertFramesAt(int insertPos, List<Integer> frames) {
        if (frames.isEmpty()) return;
        tasInputs.addAll(insertPos, frames);
        shiftMarkersFrom(insertPos, frames.size());
        //Frame indices at/after insertPos now refer to different content, so invalidate from there.
        if (insertPos < tasValidFrontier) {
            tasValidFrontier = insertPos;
            invalidateTasCheckpointsFrom(tasValidFrontier);
        }
        tasTableModel.fireTableDataChanged();

        //If the playhead was already past the insertion point, replay from there.
        if (insertPos < tasFrame) {
            seekToFrame(insertPos);
        } else {
            syncTasSelection();
        }
    }

    //Shifts every marker at/after `pos` by `delta` rows so they track logical frames on splice.
    private void shiftMarkersFrom(int pos, int delta) {
        if (tasMarkers.isEmpty() || delta == 0) return;
        TreeMap<Integer, String> shifted = new TreeMap<>();
        for (Map.Entry<Integer, String> entry : tasMarkers.entrySet()) {
            int key = entry.getKey();
            shifted.put(key >= pos ? key + delta : key, entry.getValue());
        }
        tasMarkers.clear();
        tasMarkers.putAll(shifted);
    }

    //Anchor for row clipboard/insert/marker ops: lowest selected row, or -1 for none/end.
    private int tasSelectionAnchor() {
        int[] rows = tasTable.getSelectedRows();
        if (rows.length == 0) return -1;
        int min = rows[0];
        for (int r : rows) min = Math.min(min, r);
        return min;
    }

    private JPopupMenu buildTasContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> copySelectedTasFrames());
        menu.add(copyItem);

        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.addActionListener(e -> pasteTasFrames(false));
        menu.add(pasteItem);

        JMenuItem pasteInsertItem = new JMenuItem("Paste Insert");
        pasteInsertItem.addActionListener(e -> pasteTasFrames(true));
        menu.add(pasteInsertItem);

        menu.addSeparator();

        JMenuItem insertItem = new JMenuItem("Insert");
        insertItem.addActionListener(e -> insertOneTasFrame());
        menu.add(insertItem);

        JMenuItem insertCountItem = new JMenuItem("Insert # of Frames...");
        insertCountItem.addActionListener(e -> insertTasFrames());
        menu.add(insertCountItem);

        menu.addSeparator();

        JMenuItem addMarkerItem = new JMenuItem("Add Marker");
        addMarkerItem.addActionListener(e -> addTasMarker(false));
        menu.add(addMarkerItem);

        JMenuItem addLabeledMarkerItem = new JMenuItem("Add Labeled Marker");
        addLabeledMarkerItem.addActionListener(e -> addTasMarker(true));
        menu.add(addLabeledMarkerItem);

        menu.addSeparator();

        JMenuItem selectBetweenMarkersItem = new JMenuItem("Select Between Markers");
        selectBetweenMarkersItem.addActionListener(e -> selectBetweenMarkers());
        menu.add(selectBetweenMarkersItem);

        return menu;
    }

    //Right-click selects just that row unless it's already part of a multi-row selection,
    //so a drag-highlighted group can still be right-clicked as a whole.
    private void maybeShowTasContextMenu(java.awt.event.MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = tasTable.rowAtPoint(e.getPoint());
        if (row >= 0) {
            boolean alreadySelected = false;
            for (int r : tasTable.getSelectedRows()) if (r == row) { alreadySelected = true; break; }
            if (!alreadySelected) tasTable.setRowSelectionInterval(row, row);
        }
        tasContextMenu.show(tasTable, e.getX(), e.getY());
    }

    //Snapshots the controller bitmask of every selected frame (ascending row order) into tasClipboard.
    private void copySelectedTasFrames() {
        int[] rows = tasTable.getSelectedRows().clone();
        Arrays.sort(rows);
        if (rows.length == 0) return;
        List<Integer> copied = new ArrayList<>(rows.length);
        for (int r : rows) copied.add(tasInputs.get(r));
        tasClipboard = copied;
    }

    //Paste (overwrite): replaces frames from the anchor with clipboard contents, extending
    //the movie if needed. Paste Insert: splices clipboard frames in at the anchor (like insertFramesAt).
    private void pasteTasFrames(boolean insert) {
        if (tasClipboard == null || tasClipboard.isEmpty()) return;
        int anchor = tasSelectionAnchor();
        int pos = (anchor >= 0) ? anchor : tasInputs.size();

        if (insert) {
            insertFramesAt(pos, new ArrayList<>(tasClipboard));
            return;
        }

        int firstOverwritten = pos;
        for (int i = 0; i < tasClipboard.size(); i++) {
            int idx = pos + i;
            if (idx < tasInputs.size()) tasInputs.set(idx, tasClipboard.get(i));
            else tasInputs.add(tasClipboard.get(i));
        }
        if (firstOverwritten < tasValidFrontier) {
            tasValidFrontier = firstOverwritten;
            invalidateTasCheckpointsFrom(tasValidFrontier);
        }
        tasTableModel.fireTableDataChanged();
        if (firstOverwritten < tasFrame) {
            seekToFrame(firstOverwritten);
        } else {
            syncTasSelection();
        }
    }

    //Marks the selected frame; prompts for a name if `labeled`. Re-marking replaces the label.
    private void addTasMarker(boolean labeled) {
        int row = tasTable.getSelectedRow();
        if (row < 0 || row >= tasInputs.size()) return;
        String name = "";
        if (labeled) {
            String input = JOptionPane.showInputDialog(tasDialog, "Marker name:", "");
            if (input == null) return;
            name = input.trim();
        }
        tasMarkers.put(row, name);
        tasTableModel.fireTableRowsUpdated(row, row);
        tasTable.repaint();
    }

    //Extends selection from the nearest earlier marker (or frame 0) to just before the
    //nearest later marker (or the last frame).
    private void selectBetweenMarkers() {
        int anchor = tasTable.getSelectedRow();
        if (anchor < 0 || tasInputs.isEmpty()) return;

        Integer backMarker = tasMarkers.floorKey(anchor - 1);
        int start = (backMarker != null) ? backMarker : 0;

        Integer forwardMarker = tasMarkers.ceilingKey(anchor + 1);
        int end = (forwardMarker != null) ? forwardMarker - 1 : tasInputs.size() - 1;

        if (end < start) end = start;
        suppressTasSelectionEvents = true;
        try {
            tasTable.setRowSelectionInterval(start, end);
        } finally {
            suppressTasSelectionEvents = false;
        }
        tasTable.repaint();
    }

    //Toggles one button across selected frames: clears if all have it set, else sets all.
    private void toggleInputOnSelection(int bit) {
        int[] rows = tasTable.getSelectedRows();
        if (rows.length == 0) return;
        boolean allSet = true;
        for (int r : rows) {
            if ((tasInputs.get(r) & bit) == 0) { allSet = false; break; }
        }
        boolean newValue = !allSet;
        for (int r : rows) editFrameInput(r, bit, newValue);
    }

    //Sets one Zapper coordinate on every selected frame.
    private void setZapperCoordOnSelection(boolean isX) {
        int[] rows = tasTable.getSelectedRows();
        if (rows.length == 0) return;
        String input = JOptionPane.showInputDialog(tasDialog,
                "Zapper " + (isX ? "X (0-255" : "Y (0-239") + ", blank = off screen) for the selected frames:", "");
        if (input == null) return;
        Integer n = parseTasCoord(input);
        if (n == null) return;
        for (int r : rows) editFrameZapperCoord(r, isX, n);
    }

    private void showAddInputsMenu(Component invoker) {
        JPopupMenu menu = new JPopupMenu();
        for (TasColumn c : tasColumns) {
            if (c.kind == TasColumnKind.FRAME) continue;
            JMenuItem item;
            if (c.isToggle()) {
                int bit = c.bit;
                item = new JMenuItem(c.kind == TasColumnKind.ZAPPER_TRIGGER ? "T (Zapper trigger)" : c.name);
                item.addActionListener(e -> toggleInputOnSelection(bit));
            } else {
                boolean isX = c.kind == TasColumnKind.ZAPPER_X;
                item = new JMenuItem("Set Zapper " + c.name + "...");
                item.addActionListener(e -> setZapperCoordOnSelection(isX));
            }
            menu.add(item);
        }
        menu.show(invoker, 0, invoker.getHeight());
    }

    //Lists every marker; double-click fast-travels the playhead to it.
    private void openMarkersDialog() {
        if (tasMarkersDialog == null) {
            tasMarkersDialog = new JDialog(tasDialog, "Markers", false);
            tasMarkersDialog.setLayout(new BorderLayout());
            tasMarkersDialog.setSize(300, 400);
        } else {
            tasMarkersDialog.getContentPane().removeAll();
        }

        DefaultListModel<String> listModel = new DefaultListModel<>();
        List<Integer> frames = new ArrayList<>(tasMarkers.keySet());
        for (int frame : frames) {
            String name = tasMarkers.get(frame);
            listModel.addElement("Frame " + frame + (name != null && !name.isEmpty() ? "  -  " + name : ""));
        }
        JList<String> list = new JList<>(listModel);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() < 2) return;
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0 || idx >= frames.size()) return;
                int frame = frames.get(idx);
                seekToFrame(frame + 1);
            }
        });

        tasMarkersDialog.add(new JScrollPane(list), BorderLayout.CENTER);
        tasMarkersDialog.add(new JLabel("Double-click a marker to jump to it", SwingConstants.CENTER), BorderLayout.SOUTH);
        tasMarkersDialog.revalidate();
        tasMarkersDialog.setLocationRelativeTo(tasDialog);
        tasMarkersDialog.setVisible(true);
    }

    //Saves just the movie (per-frame controller bitmasks), one decimal value per line.
    //No savestate info is persisted - loading always replays from power-on.
    private void saveTas() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("TAS files (*.tas)", "tas"));
        int option = fileChooser.showSaveDialog(tasDialog);
        if (option != JFileChooser.APPROVE_OPTION) return;

        File selectedFile = fileChooser.getSelectedFile();
        if (!selectedFile.getName().contains(".")) {
            selectedFile = new File(selectedFile.getParentFile(), selectedFile.getName() + ".tas");
        }
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(selectedFile))) {
            //Port devices ride along so a Zapper movie replays with the Zapper on the same port.
            writer.println("#port1=" + (bus.isZapperOnPort(1) ? "Zapper" : "Controller"));
            writer.println("#port2=" + (bus.isZapperOnPort(2) ? "Zapper" : "Controller"));
            for (int bits : tasInputs) writer.println(bits);
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(tasDialog, "Failed to save TAS: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    //Parsed .tas file: one packed input per line, with optional "#port1=Zapper"-style header lines
    //(absent in files saved before Zapper support - those keep whatever ports are currently selected).
    private static final class TasFile {
        final List<Integer> inputs = new ArrayList<>();
        Boolean zapperPort1 = null;
        Boolean zapperPort2 = null;
    }

    private static TasFile readTasFile(File file) throws java.io.IOException, NumberFormatException {
        TasFile result = new TasFile();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    String lower = line.substring(1).trim().toLowerCase();
                    if (lower.startsWith("port1=")) result.zapperPort1 = lower.endsWith("zapper");
                    else if (lower.startsWith("port2=")) result.zapperPort2 = lower.endsWith("zapper");
                    continue;
                }
                result.inputs.add(Integer.parseInt(line));
            }
        }
        return result;
    }

    private void applyTasFilePorts(TasFile file) {
        if (file.zapperPort1 != null) setPortDevice(1, file.zapperPort1);
        if (file.zapperPort2 != null) setPortDevice(2, file.zapperPort2);
    }

    //Loads a movie saved by saveTas, replacing tasInputs and restarting from power-on.
    private void loadTas() {
        if (blockedByPreciseTrace()) return;
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("TAS files (*.tas)", "tas"));
        int option = fileChooser.showOpenDialog(tasDialog);
        if (option != JFileChooser.APPROVE_OPTION) return;

        TasFile file;
        try {
            file = readTasFile(fileChooser.getSelectedFile());
        } catch (java.io.IOException | NumberFormatException ex) {
            JOptionPane.showMessageDialog(tasDialog, "Failed to load TAS: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        List<Integer> loaded = file.inputs;

        setTasPlaying(false);
        applyTasFilePorts(file);
        tasInputs.clear();
        tasInputs.addAll(loaded);
        //Row 0 (hardware's pre-execution power-on state) must always exist - see openTasMaker().
        //Guards a file saved before row 0 was included, or an empty one.
        if (tasInputs.isEmpty()) tasInputs.add(0);
        tasFrameStrobed.clear();
        tasFrameStrobed.add(false); //row 0 is never polled - nothing has booted yet
        tasValidFrontier = 1;
        tasFrame = 1;
        tasCheckpoints.clear();
        tasMarkers.clear();
        synchronized (emulationLock) {
            bus.hardReset();
        }
        renderFrameWithOverlay();
        tasTableModel.fireTableDataChanged();
        syncTasSelection();
    }

    //Emulation menu "Play Movie...": plays a saved .tas file back in real time, as if you'd
    //opened the TAS editor, seeked to frame 0, and hit Play - but without opening the editor.
    private void playMovie() {
        if (blockedByPreciseTrace()) return;
        if (!requireRom()) return;
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("TAS files (*.tas)", "tas"));
        int option = fileChooser.showOpenDialog(this);
        if (option != JFileChooser.APPROVE_OPTION) return;

        TasFile file;
        try {
            file = readTasFile(fileChooser.getSelectedFile());
        } catch (java.io.IOException | NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Failed to load movie: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        List<Integer> loaded = file.inputs;
        if (loaded.isEmpty()) loaded.add(0); //row 0 (hardware's pre-execution power-on state)
        applyTasFilePorts(file);

        r08Data = null; //movie and r08 playback are mutually exclusive
        synchronized (emulationLock) {
            bus.hardReset();
        }
        playMovieInputs = loaded;
        playMovieFrame = 1;
        renderFrameWithOverlay();
    }

    //Emulation menu "Play .r08 File...": opens the options dialog (ACE Mode / Transition), whose
    //"Load .r08 File..." button actually picks the file and starts playback.
    private void openR08Dialog() {
        if (r08Dialog == null) {
            r08Dialog = new JDialog(this, "Play .r08 File", false);
            r08Dialog.setLayout(new BorderLayout());

            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            r08AceModeCheckbox = new JCheckBox("ACE Mode (advance on every controller strobe, not once per frame)");
            panel.add(r08AceModeCheckbox);

            JPanel transitionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            r08TransitionCheckbox = new JCheckBox("Transition to ACE-style advancing at frame:");
            r08TransitionField = new JTextField("0", 6);
            transitionPanel.add(r08TransitionCheckbox);
            transitionPanel.add(r08TransitionField);
            panel.add(transitionPanel);

            JButton loadButton = new JButton("Load .r08 File...");
            loadButton.addActionListener(e -> loadR08File());
            JPanel loadPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 10));
            loadPanel.add(loadButton);
            panel.add(loadPanel);

            r08Dialog.add(panel, BorderLayout.CENTER);
            r08Dialog.pack();
            r08Dialog.setLocationRelativeTo(this);
        }
        r08Dialog.setVisible(true);
    }

    private void loadR08File() {
        if (!requireRom()) return;
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(".r08 replay files (*.r08)", "r08"));
        int option = fileChooser.showOpenDialog(r08Dialog);
        if (option != JFileChooser.APPROVE_OPTION) return;

        byte[] data;
        try {
            data = java.nio.file.Files.readAllBytes(fileChooser.getSelectedFile().toPath());
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(r08Dialog, "Failed to load .r08 file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (data.length < 2) {
            JOptionPane.showMessageDialog(r08Dialog, "File is too short to contain any (P1, P2) input pairs.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        boolean transitionEnabled = r08TransitionCheckbox.isSelected();
        int transitionFrame = 0;
        if (transitionEnabled) {
            try {
                transitionFrame = Integer.parseInt(r08TransitionField.getText().trim());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(r08Dialog, "Transition frame must be a whole number.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        playMovieInputs = null; //movie and r08 playback are mutually exclusive
        synchronized (emulationLock) {
            bus.hardReset();
        }
        r08Data = data;
        r08PairIndex = 0;
        r08FrameCount = 0;
        r08StrobedThisFrame = false;
        converting = false;
        r08AceMode = r08AceModeCheckbox.isSelected();
        r08TransitionEnabled = transitionEnabled;
        r08TransitionFrame = transitionFrame;
        renderFrameWithOverlay();
        r08Dialog.setVisible(false);
    }

    private void openR08ToMovieDialog() {
        if (r08ConvertDialog == null) {
            r08ConvertDialog = new JDialog(this, "Load .r08 File", false);
            r08ConvertDialog.setLayout(new BorderLayout());

            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));


            JLabel dropdownLabel = new JLabel("Format:");
            dropdownLabel.setAlignmentX(Component.LEFT_ALIGNMENT);


            String[] options = {"Bizhawk", "FCEUX"};
            formatBox = new JComboBox<>(options);
            formatBox.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel fceuxWarning = new JLabel("FCEUX is inaccurate, consider using Bizhawk.");
            fceuxWarning.setForeground(new Color(0xB0, 0x30, 0x30));
            fceuxWarning.setAlignmentX(Component.LEFT_ALIGNMENT);
            fceuxWarning.setVisible(false);
            formatBox.addActionListener(e -> {
                fceuxWarning.setVisible("FCEUX".equals(formatBox.getSelectedItem()));
                r08ConvertDialog.pack();
            });


            JButton loadButton = new JButton("Load .r08 File...");
            loadButton.addActionListener(e -> loadR08File2());
            loadButton.setAlignmentX(Component.LEFT_ALIGNMENT);

            panel.add(dropdownLabel);
            panel.add(Box.createVerticalStrut(4));
            panel.add(formatBox);
            panel.add(Box.createVerticalStrut(4));
            panel.add(fceuxWarning);
            panel.add(Box.createVerticalStrut(10));
            panel.add(loadButton);

            r08ConvertDialog.add(panel, BorderLayout.CENTER);
            r08ConvertDialog.pack();
            r08ConvertDialog.setLocationRelativeTo(this);
        }
        r08ConvertDialog.setVisible(true);
    }

    private void loadR08File2() {
        if (!requireRom()) return;
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(".r08 replay files (*.r08)", "r08"));
        int option = fileChooser.showOpenDialog(r08Dialog);
        if (option != JFileChooser.APPROVE_OPTION) return;

        byte[] data;
        try {
            data = java.nio.file.Files.readAllBytes(fileChooser.getSelectedFile().toPath());
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(r08Dialog, "Failed to load .r08 file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (data.length < 2) {
            JOptionPane.showMessageDialog(r08Dialog, "File is too short to contain any (P1, P2) input pairs.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        boolean transitionEnabled = false;
        int transitionFrame = 0;
        if (transitionEnabled) {
            try {
                transitionFrame = Integer.parseInt(r08TransitionField.getText().trim());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(r08Dialog, "Transition frame must be a whole number.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        playMovieInputs = null; //movie and r08 playback are mutually exclusive
        synchronized (emulationLock) {
            bus.hardReset();
        }
        r08Data = data;
        r08PairIndex = 0;
        r08FrameCount = 0;
        r08StrobedThisFrame = false;
        transitionEnabled = false;
        r08AceMode = false;
        Object selection = formatBox.getSelectedItem();

        formatBizhawk = "Bizhawk".equals(selection);
        converting = true;
        lastNonLagFrame = -1; //no frame logged yet (see logInputs())

        r08TransitionEnabled = false;
        renderFrameWithOverlay();
        r08ConvertDialog.setVisible(false);
        inputlog.replace(0, inputlog.length(), "");
        //BizHawk splits the LogKey on '#', so there must be no space before the first '#'.
        if (formatBizhawk) inputlog.append("[Input]\r\nLogKey:#Power|Reset|#P1 Up|P1 Down|P1 Left|P1 Right|P1 Start|P1 Select|P1 B|P1 A|#P2 Up|P2 Down|P2 Left|P2 Right|P2 Start|P2 Select|P2 B|P2 A|\r\n");
    }

    //Bus.onControllerStrobe callback: fires on every $4016 strobe rising edge, mid-frame, on whichever thread is
    //running cpu.step() - always under emulationLock, so no extra synchronization here.
    private void onR08ControllerStrobe() {
        if (r08Data == null) return;
        boolean perStrobeMode = r08AceMode || (r08TransitionEnabled && r08FrameCount >= r08TransitionFrame);
        if (perStrobeMode) {
            advanceR08Pair(); //ACE Mode / past the Transition frame: every strobe moves on
            return;
        }
        //Per-frame mode: nothing is pressed at frame start (applyPlaybackInputsForFrame()); only the FIRST strobe reveals
        //the current pair, which then holds for the rest of the frame. Advancing to the next pair happens at frame end only
        //if this flag ended true (advancePlaybackAfterFrame()) - an unstrobed frame stays on the same pair.
        if (!r08StrobedThisFrame) {
            r08StrobedThisFrame = true;
            if (converting) logInputs();
            applyR08CurrentPair();

        }
    }

    private void logInputs() {
        //Frames between the last logged one and this one had no strobe: emit a lag line for each.
        for (int i = lastNonLagFrame + 1; i < currentFrame; i++) inputlog.append(movieInputLine(0, 0));
        lastNonLagFrame = currentFrame;
        int P1 = r08Data[r08PairIndex * 2] & 0xFF;
        int P2 = r08Data[(r08PairIndex * 2) + 1] & 0xFF;
        inputlog.append(movieInputLine(P1, P2));
    }

    //One movie input line for the selected format. .r08 bit order: R L D U Start Select B A (bit 0 = Right).
    private String movieInputLine(int P1, int P2) {
        StringBuilder line = new StringBuilder();
        if (formatBizhawk) {
            line.append("|..|").append(bizhawkButtons(P1)).append('|').append(bizhawkButtons(P2)).append("|\r\n");
        } else {
            line.append("|0|").append(fceuxButtons(P1)).append('|').append(fceuxButtons(P2)).append("||\r\n");
        }
        return line.toString();
    }

    //BizHawk NesHawk order: Up Down Left Right Start Select B A.
    private static String bizhawkButtons(int bits) {
        return new StringBuilder()
                .append((bits & 0x08) != 0 ? 'U' : '.').append((bits & 0x04) != 0 ? 'D' : '.')
                .append((bits & 0x02) != 0 ? 'L' : '.').append((bits & 0x01) != 0 ? 'R' : '.')
                .append((bits & 0x10) != 0 ? 'S' : '.').append((bits & 0x20) != 0 ? 's' : '.')
                .append((bits & 0x40) != 0 ? 'B' : '.').append((bits & 0x80) != 0 ? 'A' : '.').toString();
    }

    //FCEUX .fm2 order: Right Left Down Up sTart Select B A.
    private static String fceuxButtons(int bits) {
        return new StringBuilder()
                .append((bits & 0x01) != 0 ? 'R' : '.').append((bits & 0x02) != 0 ? 'L' : '.')
                .append((bits & 0x04) != 0 ? 'D' : '.').append((bits & 0x08) != 0 ? 'U' : '.')
                .append((bits & 0x10) != 0 ? 'T' : '.').append((bits & 0x20) != 0 ? 'S' : '.')
                .append((bits & 0x40) != 0 ? 'B' : '.').append((bits & 0x80) != 0 ? 'A' : '.').toString();
    }

    private void generateMovieFile() {
        if (!formatBizhawk) { generateFm2File(); return; }
        String outputFilePath = System.getProperty("user.home") + "/Desktop/output.bk2";

        StringBuilder content = new StringBuilder();

        content.append("MovieVersion BizHawk v2.0\r\n");
        content.append("Author default user\r\n");
        content.append("Core NesHawk\r\n");
        content.append("Platform NES\r\n");
        content.append("emuVersion Version 2.10\r\n");
        content.append("OriginalEmuVersion Version 2.10\r\n");
        content.append("GameName " + RomName + "\r\n");
        content.append("SHA1 " + SHA1 + "\r\n");
        content.append("rerecordCount 0\r\n");
        content.append("\r\n");

        String header = content.toString();

        inputlog.append("[/Input]\r\n");

        String inputs = inputlog.toString();
        converting = false;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFilePath))) {
            //Entry names are case-sensitive in BizHawk's zip reader: "BizState 1.0", not "Bizstate".
            addTextToZip(zos, "2\r\n", "BizState 1.0");
            addTextToZip(zos, "Version 2.10\r\n", "BizVersion.txt");
            addTextToZip(zos, "\r\n", "Comments.txt");
            addTextToZip(zos, header, "Header.txt");
            addTextToZip(zos, inputs, "Input Log.txt");
            addTextToZip(zos, "\r\n", "Subtitles.txt");
            addTextToZip(zos, "{\"o\":{\"$type\":\"BizHawk.Emulation.Cores.Nintendo.NES.NES+NESSyncSettings, BizHawk.Emulation.Cores\",\"BoardProperties\":{},\"RegionOverride\":0,\"Controls\":{\"Famicom\":false,\"NesLeftPort\":\"ControllerNES\",\"NesRightPort\":\"ControllerNES\",\"FamicomExpPort\":\"UnpluggedFam\"},\"VSDipswitches\":{\"Dip_Switch_1\":false,\"Dip_Switch_2\":false,\"Dip_Switch_3\":false,\"Dip_Switch_4\":false,\"Dip_Switch_5\":false,\"Dip_Switch_6\":false,\"Dip_Switch_7\":false,\"Dip_Switch_8\":false},\"InitialWRamStatePattern\":[]}}", "SyncSettings.json");

            final String savedTo = outputFilePath;
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Movie written to:\n" + savedTo, "r08 to movie", JOptionPane.INFORMATION_MESSAGE));
        } catch (IOException e) {
            System.err.println("Error creating archive");
            e.printStackTrace();
            final String msg = e.getMessage();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Failed to write movie: " + msg, "r08 to movie", JOptionPane.ERROR_MESSAGE));
        }
    }

    //FCEUX .fm2: text header then one "|cmd|port0|port1||" line per frame (lag frames included).
    private void generateFm2File() {
        String outputFilePath = System.getProperty("user.home") + "/Desktop/output.fm2";
        converting = false;
        StringBuilder content = new StringBuilder();
        content.append("version 3\r\n");
        content.append("emuVersion 20605\r\n");
        content.append("rerecordCount 0\r\n");
        content.append("palFlag 0\r\n");
        content.append("romFilename ").append(RomName).append("\r\n");
        content.append("romChecksum base64:").append(RomMd5Base64).append("\r\n");
        content.append("guid ").append(java.util.UUID.randomUUID().toString().toUpperCase()).append("\r\n");
        content.append("fourscore 0\r\n");
        content.append("microphone 0\r\n");
        content.append("port0 1\r\n");
        content.append("port1 1\r\n");
        content.append("port2 0\r\n");
        content.append("FDS 0\r\n");
        content.append("NewPPU 0\r\n");
        content.append("RAMInitOption 0\r\n");
        content.append("comment author r08 to movie\r\n");
        content.append("|0|........|........||\n");
        content.append("|0|........|........||\n");
        content.append(inputlog);
        try {
            Files.write(Paths.get(outputFilePath), content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final String savedTo = outputFilePath;
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Movie written to:\n" + savedTo, "r08 to movie", JOptionPane.INFORMATION_MESSAGE));
        } catch (IOException e) {
            final String msg = e.getMessage();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Failed to write movie: " + msg, "r08 to movie", JOptionPane.ERROR_MESSAGE));
        }
    }

    private void addFileToZip(ZipOutputStream zos, Path filePath, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        Files.copy(filePath, zos);
        zos.closeEntry();
    }

    private void addTextToZip(ZipOutputStream zos, String content, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
    }

    //Moves to the next (P1, P2) pair, or ends r08 playback (falling back to normal keyboard
    //control, emulation keeps running) if that was the last one.
    private void advanceR08Pair() {
        r08PairIndex++;
        if ((r08PairIndex + 1) * 2 > r08Data.length) {
            r08Data = null;
            setController1Tracked(controller1Bits | queuedInputBits);
            setController2Tracked(0);
            if (converting) generateMovieFile();
            return;
        }
        applyR08CurrentPair();
    }

    private void applyR08CurrentPair() {
        int p1 = reverseByte(r08Data[r08PairIndex * 2] & 0xFF);
        int p2 = reverseByte(r08Data[r08PairIndex * 2 + 1] & 0xFF);
        setController1Tracked(p1);
        setController2Tracked(p2);
    }

    //.r08's bit order (A=0x80 ... Right=0x01) is this emulator's internal order (A=0x01 ...
    //Right=0x80, see tasInputs' declaration) bit-reversed.
    private static int reverseByte(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) if ((b & (1 << i)) != 0) r |= (1 << (7 - i));
        return r;
    }

    //Called from run()'s real-time loop right before updateEmulationFrame(), whenever movie or
    //r08 playback is active, to set this frame's controller state before it runs.
    private void applyPlaybackInputsForFrame() {
        if (playMovieInputs != null) {
            int idx = playMovieFrame - 1;
            if (idx < playMovieInputs.size()) {
                applyTasInput(playMovieInputs.get(idx));
            } else {
                //Ran out of movie input - stop movie playback, hand control back to the keyboard
                //(and the Zapper to the mouse), but let emulation keep running.
                playMovieInputs = null;
                zapper.endScripted();
                setController1Tracked(controller1Bits | queuedInputBits);
            }
        } else if (r08Data != null) {
            int pairCount = r08Data.length / 2;
            if (r08PairIndex < pairCount) {
                r08FrameCount++; //must be current for the whole frame before it runs - mid-frame
                                  //strobes (onR08ControllerStrobe) check this same counter.
                r08StrobedThisFrame = false;
                boolean perStrobeMode = r08AceMode || (r08TransitionEnabled && r08FrameCount >= r08TransitionFrame);
                if (perStrobeMode) {
                    applyR08CurrentPair();
                } else {
                    //Per-frame mode: hold nothing until the frame's first strobe reveals the
                    //current pair (onR08ControllerStrobe()).
                    setController1Tracked(0);
                    setController2Tracked(0);
                }
            } else {
                r08Data = null;
                setController1Tracked(controller1Bits | queuedInputBits);
                setController2Tracked(0);
            }
        }
    }

    //Called from run()'s loop right after updateEmulationFrame(). Per-frame mode advances ONLY if the frame was strobed
    //at least once (a lag frame stays on the same pair, matching onR08ControllerStrobe() revealing on first strobe);
    //per-strobe mode (ACE Mode, or past the Transition frame) already advanced mid-frame, so this is a no-op for it.
    private void advancePlaybackAfterFrame() {
        if (playMovieInputs != null) {
            playMovieFrame++;
        } else if (r08Data != null) {
            boolean perStrobeMode = r08AceMode || (r08TransitionEnabled && r08FrameCount >= r08TransitionFrame);
            if (!perStrobeMode && r08StrobedThisFrame) advanceR08Pair();
        }
    }

    //Scans for bytes changed since the last scan and fires targeted cell updates.
    //Runs on the emulation thread; updates are marshalled to the EDT.
    private void scanMemoryForChanges() {
        List<Integer> changedAddresses = null;
        for (int addr = 0; addr <= 0xFFFF; addr++) {
            int val = bus.debugRead(addr) & 0xFF;
            if (memorySnapshot[addr] != val) {
                memorySnapshot[addr] = val;
                if (changedAddresses == null) changedAddresses = new ArrayList<>();
                changedAddresses.add(addr);
            }
        }
        if (changedAddresses != null) {
            List<Integer> finalChanged = changedAddresses;
            SwingUtilities.invokeLater(() -> {
                for (int addr : finalChanged) {
                    int row = addr / 16;
                    int col = (addr % 16) + 1;
                    memoryTableModel.fireTableCellUpdated(row, col);
                    memoryTableModel.fireTableCellUpdated(row, 17);
                }
            });
        }
    }

    private void gotoMemoryAddress() {
        try {
            String text = memoryGotoField.getText().trim().replace("$", "").replace("0x", "");
            int address = Integer.parseInt(text, 16) & 0xFFFF;
            int row = address / 16;
            Rectangle cellRect = memoryTable.getCellRect(row, 0, true);
            memoryTable.scrollRectToVisible(cellRect);
            memoryTable.setRowSelectionInterval(row, row);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(memoryDialog, "Enter a valid hex address (0000-FFFF)", "Invalid Address", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void createTraceWindow() {
        traceDialog = new JDialog(this, "Trace Logger", false);
        traceDialog.setSize(750, 450);
        traceDialog.setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        traceToggleButton = new JButton("Start Trace");
        traceToggleButton.addActionListener(e -> {
            tracingEnabled = !tracingEnabled;
            traceToggleButton.setText(tracingEnabled ? "End Trace" : "Start Trace");
            if (tracingEnabled) {
                try {
                    traceFileWriter = new java.io.PrintWriter(new java.io.FileWriter("trace_log.txt", false));
                } catch (java.io.IOException ex) {
                    traceFileWriter = null;
                }
            } else if (traceFileWriter != null) {
                traceFileWriter.close();
                traceFileWriter = null;
            }
        });

        tracePauseButton = new JToggleButton("Pause Emulation");
        tracePauseButton.addActionListener(e -> setPaused(tracePauseButton.isSelected()));

        stepButton = new JButton("Step");
        stepButton.setEnabled(paused);
        stepButton.addActionListener(e -> stepOneFrame());

        logToFileButton = new JToggleButton("Log to File");
        logToFileButton.addActionListener(e -> {
            if (logToFileButton.isSelected()) {
                String desktopPath = System.getProperty("user.home") + java.io.File.separator + "Desktop"
                        + java.io.File.separator + "trace_log.log";
                try {
                    desktopLogWriter = new java.io.PrintWriter(new java.io.FileWriter(desktopPath, false));
                } catch (java.io.IOException ex) {
                    desktopLogWriter = null;
                    logToFileButton.setSelected(false);
                    JOptionPane.showMessageDialog(this, "Failed to open log file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            } else if (desktopLogWriter != null) {
                desktopLogWriter.close();
                desktopLogWriter = null;
            }
        });

        topPanel.add(traceToggleButton);
        topPanel.add(tracePauseButton);
        topPanel.add(stepButton);
        topPanel.add(logToFileButton);

        traceTextArea = new JTextArea();
        traceTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        traceTextArea.setEditable(false);

        traceDialog.add(topPanel, BorderLayout.NORTH);
        traceDialog.add(new JScrollPane(traceTextArea), BorderLayout.CENTER);

        traceDialog.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                updateDebugUI();
            }
        });
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();


        JMenu emulationMenu = new JMenu("Emulation");
        JMenuItem openRomItem = new JMenuItem("Open ROM...");
        openRomItem.addActionListener(e -> openRomChooser());
        emulationMenu.add(openRomItem);

        emulationMenu.addSeparator();

        JMenuItem pauseItem = new JMenuItem("Pause / Resume");
        pauseItem.addActionListener(e -> togglePause());
        emulationMenu.add(pauseItem);

        JMenuItem powerItem = new JMenuItem("Power");
        powerItem.addActionListener(e -> powerCycle());
        emulationMenu.add(powerItem);

        JMenuItem resetItem = new JMenuItem("Reset");
        resetItem.addActionListener(e -> resetEmulation());
        emulationMenu.add(resetItem);

        emulationMenu.addSeparator();

        JMenuItem playMovieItem = new JMenuItem("Play Movie...");
        playMovieItem.addActionListener(e -> playMovie());
        emulationMenu.add(playMovieItem);

        JMenuItem playR08Item = new JMenuItem("Play .r08 File...");
        playR08Item.addActionListener(e -> openR08Dialog());
        emulationMenu.add(playR08Item);

        JMenuItem R08toMovie = new JMenuItem("R08 to Movie...");
        R08toMovie.addActionListener(e -> openR08ToMovieDialog());
        emulationMenu.add(R08toMovie);

        emulationMenu.addSeparator();

        JMenuItem fullScreenItem = new JMenuItem("Full Screen (F11)");
        fullScreenItem.addActionListener(e -> toggleFullScreen());
        emulationMenu.add(fullScreenItem);

        emulationMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        emulationMenu.add(exitItem);


        JMenu toolsMenu = new JMenu("Tools");

        JMenuItem controllerItem = new JMenuItem("Controller Settings...");
        controllerItem.addActionListener(e -> controllerDialog.setVisible(true));
        toolsMenu.add(controllerItem);

        JMenuItem audioItem = new JMenuItem("Audio Settings...");
        audioItem.addActionListener(e -> JOptionPane.showMessageDialog(this, "Audio setup dialog"));
        toolsMenu.add(audioItem);

        JMenuItem tasMakerItem = new JMenuItem("TAS Maker...");
        tasMakerItem.addActionListener(e -> openTasMaker());
        toolsMenu.add(tasMakerItem);

        JMenuItem preciseTracingItem = new JMenuItem("Precise Tracing...");
        preciseTracingItem.addActionListener(e -> openPreciseTracing());
        toolsMenu.add(preciseTracingItem);


        JMenu debugMenu = new JMenu("Debug");

        JMenuItem traceItem = new JMenuItem("Trace");
        traceItem.addActionListener(e -> traceDialog.setVisible(true));
        debugMenu.add(traceItem);

        JCheckBoxMenuItem oamDecayItem = new JCheckBoxMenuItem("Emulate OAM Decay", PPU.oamDecayEnabled);
        oamDecayItem.setToolTipText("OAM is DRAM: with rendering disabled for more than a few ms its contents decay to $00, as on real hardware.");
        oamDecayItem.addActionListener(e -> PPU.oamDecayEnabled = oamDecayItem.isSelected());
        debugMenu.add(oamDecayItem);

        JMenuItem nametableViewer = new JMenuItem("Nametable Viewer");
        nametableViewer.addActionListener(e -> {
            nametableViewerDialog.refresh();
            nametableViewerDialog.setVisible(true);
        });
        debugMenu.add(nametableViewer);

        JMenuItem patternTableViewer = new JMenuItem("PPU Inspector");
        patternTableViewer.addActionListener(e -> {
            ppuInspectorDialog.refresh();
            ppuInspectorDialog.setVisible(true);
        });
        debugMenu.add(patternTableViewer);

        JMenuItem memoryInspector = new JMenuItem("Memory Inspector");
        memoryInspector.addActionListener(e -> memoryDialog.setVisible(true));
        debugMenu.add(memoryInspector);

        //Dumps every live OAM entry (decoded) to stdout and a copyable dialog, for reporting
        //sprite bugs without a debugger.
        JMenuItem dumpOamItem = new JMenuItem("Dump OAM");
        dumpOamItem.addActionListener(e -> {
            String text = ppu.dumpOamText();
            System.out.println("=== OAM dump (frame paused) ===\n" + text);
            JTextArea area = new JTextArea(text, 24, 48);
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JOptionPane.showMessageDialog(this, new JScrollPane(area), "OAM Dump", JOptionPane.PLAIN_MESSAGE);
        });
        debugMenu.add(dumpOamItem);

        //Arms a one-shot click: translates it to NES pixel coords and explains why that
        //pixel is that color (background opacity, sprite priority) for diagnosing rendering bugs.
        JMenuItem explainPixelItem = new JMenuItem("Explain Pixel (click screen)...");
        explainPixelItem.addActionListener(e -> {
            JOptionPane.showMessageDialog(this, "Click anywhere on the game screen to explain that pixel.",
                    "Explain Pixel", JOptionPane.INFORMATION_MESSAGE);
            displayPanel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent ev) {
                    displayPanel.removeMouseListener(this);
                    Point nes = displayPanel.toNesPixel(ev.getX(), ev.getY());
                    if (nes == null) return; //clicked a letterbox bar
                    int nx = nes.x, ny = nes.y;
                    //24x24 ASCII grid centered on the click; imprecise but wide enough to cover it.
                    int gx0 = Math.max(0, nx - 12), gy0 = Math.max(0, ny - 12);
                    String text = String.format("clicked (%d,%d)%n%n", nx, ny) + ppu.explainGrid(gx0, gy0, 24, 24);
                    System.out.println(text);
                    JTextArea area = new JTextArea(text, 28, 40);
                    area.setEditable(false);
                    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                    JOptionPane.showMessageDialog(NESEmulator.this, new JScrollPane(area), "Explain Pixel", JOptionPane.PLAIN_MESSAGE);
                }
            });
        });
        debugMenu.add(explainPixelItem);


        menuBar.add(emulationMenu);
        menuBar.add(toolsMenu);
        menuBar.add(debugMenu);

        return menuBar;
    }
    public synchronized void start() {
        if (running) return;
        running = true;
        audioPlayer.start();
        emulatorThread = new Thread(this,"NES-MainLoop");
        emulatorThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (emulatorThread != null) {
                emulatorThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        audioPlayer.stop();
    }

    //Main emulation loop (~60FPS)
    @Override
    public void run() {
        //Real NTSC frame rate, not 60.0: at 60.0 the emulator produces ~0.17% fewer audio
        //samples than the sound card consumes, so the queue slowly drains into dropouts.
        final double nsPerFrame = 1_000_000_000.0 / 60.0988;
        //Cap on how many frames to run in one go after a stall (window drag, GC pause).
        //Bursting through a big backlog floods the audio ring and drops a chunk of sound.
        final double maxCatchUpFrames = 3;
        long lastTime = System.nanoTime();
        double delta = 0;

        while (running) {
            long now = System.nanoTime();
            delta += (now - lastTime) / nsPerFrame;
            lastTime = now;
            if (delta > maxCatchUpFrames) delta = maxCatchUpFrames;

            while (delta >= 1) {
                if (!paused && !tasActive && romLoaded()) {
                    synchronized (emulationLock) {
                        //Re-check inside the lock: tasActive may have flipped true while we were
                        //waiting to acquire it (e.g. openTasMaker() got there first and is mid
                        //hardReset()) - stepping now would race a whole frame into that reset.
                        if (!paused && !tasActive && romLoaded()) {
                            applyPlaybackInputsForFrame();
                            updateEmulationFrame();
                            currentFrame++;
                            advancePlaybackAfterFrame();
                        }
                    }
                }
                delta--;
            }

            if (!paused && !tasActive && romLoaded()) {
                renderFrameWithOverlay();
            }

            //brief pause to give thread CPU time back to the OS
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void stepOneFrame() {
        if (!paused || tasActive) return;
        if (!requireRom()) return;

        boolean wasTracing = tracingEnabled;
        tracingEnabled = true;

        //Apply queued taps for this frame, then drop them so they don't bleed into later steps.
        synchronized (emulationLock) {
            setController1Tracked(controller1Bits | queuedInputBits);
            queuedInputBits = 0;

            updateEmulationFrame();

            setController1Tracked(controller1Bits);
        }
        renderFrameWithOverlay();

        tracingEnabled = wasTracing;
    }

    private void updateEmulationFrame() {
        updateEmulationFrame(null);
    }

    //rowLabel (TAS Maker's tasAdvanceOneFrame()/seekToFrame() only) stamps the trace with an unambiguous "which row
    //is this" header, to settle from the trace pane whether a reported "row 0 looks like row 1" is a real skipped-frame
    //bug or just a viewing mismatch.
    private void updateEmulationFrame(String rowLabel) {
        frameTraceBuffer.setLength(0);
        long startCycle = cpu.totalCycles;

        //Run until the PPU reports VBlank start rather than a fixed cycle count - a real NTSC
        //frame (89341/89342 dots) doesn't divide evenly, so this keeps NMI/VBlank timing exact
        //and aligns trace slices with the NMI (see PPU.vblankStarted).
        do {
            //APU clocked before CPU: on real hardware DMC RDY and APU IRQ are visible to the CPU's
            //interrupt/DMA-halt check this same cycle - clocking after would delay both by a cycle,
            //breaking AccuracyCoin's Interrupt Flag Latency and NMI Overlap IRQ tests.
            apu.clock();
            cpu.step(frameTraceBuffer, ppu, tracingEnabled);

            //PPU runs 3 cycles per CPU cycle
            ppu.step();
            ppu.step();
            ppu.step();
        } while (!ppu.consumeVBlankStart());

        afterFrameEmulated(rowLabel, startCycle);
    }

    //Everything updateEmulationFrame() does once the frame's cycles have run: trace pane /
    //log file output and debug-window refreshes. Split out so a Precise Trace (which runs
    //the cycles itself, slowly) ends with identical bookkeeping.
    private void afterFrameEmulated(String rowLabel, long startCycle) {
        if (rowLabel != null && frameTraceBuffer.length() > 0) {
            frameTraceBuffer.insert(0, "=== " + rowLabel + " | CPU cycles [" + startCycle + ", " + cpu.totalCycles + ") ===\n");
        }

        //No explicit render call needed - the PPU already wrote pixels into the display
        //buffer scanline-by-scanline as it stepped (see PPU.renderScanline).

        //Update trace UI if active. run()'s background "NES-MainLoop" thread must marshal onto the EDT via invokeLater,
        //but TAS Maker's tasAdvanceOneFrame()/seekToFrame() already run on the EDT - another invokeLater there races the
        //next Play timer tick refilling frameTraceBuffer (pane shows the frame moved ONTO instead of FROM), so update
        //synchronously when already on the EDT.
        if (tracingEnabled && frameTraceBuffer.length() > 0) {
            String traceSnapshot = frameTraceBuffer.toString();
            //setText() doesn't reliably reset the scroll position, so a many-thousand-line trace can be left showing the
            //bottom and look like it starts mid-frame; force the caret (and viewport) to the top.
            Runnable applyTrace = () -> { traceTextArea.setText(traceSnapshot); traceTextArea.setCaretPosition(0); };
            if (SwingUtilities.isEventDispatchThread()) applyTrace.run();
            else SwingUtilities.invokeLater(applyTrace);
            if (traceFileWriter != null) {
                traceFileWriter.print(frameTraceBuffer);
                traceFileWriter.flush();
            }
            if (desktopLogWriter != null) {
                desktopLogWriter.print(frameTraceBuffer);
                desktopLogWriter.flush();
            }
        }

        //Update Memory Inspector if visible - only fires updates for changed cells.
        if (memoryDialog.isVisible()) {
            scanMemoryForChanges();
        }

        if (nametableViewerDialog.isVisible()) {
            nametableViewerDialog.refresh();
        }
        if (ppuInspectorDialog.isVisible()) {
            ppuInspectorDialog.refresh();
        }
    }

    private void openRomChooser() {
        if (blockedByPreciseTrace()) return;
        JFileChooser fileChooser = new JFileChooser();
        int option = fileChooser.showOpenDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            generateSHA1Hash(selectedFile);
            try {
                Cartridge cart = new Cartridge(selectedFile);
                cart.connectCPU(cpu);
                synchronized (emulationLock) {
                    bus.insertCartridge(cart);
                    bus.hardReset(); //inserting a cartridge is a power-on, not a reset-button press
                }
                renderFrameWithOverlay();
                System.out.println("Successfully loaded ROM: " + selectedFile.getName());
                RomName = selectedFile.getName();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to load ROM: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void generateSHA1Hash(File selectedFile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            FileInputStream fis = new FileInputStream(selectedFile);
            byte[] fileBytes = fis.readAllBytes();
            byte[] hashBytes = digest.digest(fileBytes);
            RomMd5Base64 = fceuxRomMd5(fileBytes);

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02X", b));
            }
            this.SHA1 = hexString.toString().toUpperCase();
        } catch (NoSuchAlgorithmException | IOException e) {
            System.err.println("Error generating hash:");
            e.printStackTrace();
        }
    }

    //FCEUX hashes PRG+CHR only: skip the 16-byte iNES header and a 512-byte trainer if flagged.
    private static String fceuxRomMd5(byte[] file) {
        try {
            int start = 16;
            if (file.length >= 16 && (file[6] & 0x04) != 0) start += 512;
            if (start > file.length) start = file.length;
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(file, start, file.length - start);
            return java.util.Base64.getEncoder().encodeToString(md5.digest());
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private void togglePaused() {
        setPaused(!paused);
    }

    //Syncs pause state across threads and updates UI
    private void setPaused(boolean pauseState) {
        this.paused = pauseState;
        System.out.println(paused ? "Emulation Paused" : "Emulation Resumed");
        SwingUtilities.invokeLater(this::updateDebugUI);
    }

    private void togglePause() {
        paused = !paused;
        System.out.println(paused ? "Emulation Paused" : "Emulation Resumed");
    }

    private void updateDebugUI() {
        if (stepButton != null) {
            stepButton.setEnabled(paused);
        }
        if (tracePauseButton != null && tracePauseButton.isSelected() != paused) {
            tracePauseButton.setSelected(paused);
        }
    }

    private void resetEmulation() {
        if (blockedByPreciseTrace()) return;
        if (!requireRom()) return;
        System.out.println("Resetting PPU and CPU...");
        synchronized (emulationLock) {
            bus.reset();
        }
    }

    //Full power-on cycle: also clears RAM (soft reset leaves RAM intact).
    private void powerCycle() {
        if (blockedByPreciseTrace()) return;
        if (!requireRom()) return;
        System.out.println("Power cycling (RAM cleared)...");
        synchronized (emulationLock) {
            bus.hardReset();
        }
        renderFrameWithOverlay();
    }

    public static void main(String[] args) {
        //Swing components must be initialized on the EDT
        SwingUtilities.invokeLater(() -> {
            NESEmulator emulator = new NESEmulator();
            emulator.setVisible(true);

            emulator.start();
        });
    }
}