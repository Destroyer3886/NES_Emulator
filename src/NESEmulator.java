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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
public class NESEmulator extends JFrame implements Runnable {

    private final NESDisplayPanel displayPanel;
    private Thread emulatorThread;

    private volatile boolean running = false;
    private volatile boolean paused = false;

    //Emulator Components
    private final CPU cpu;
    private final PPU ppu;
    private final APU apu;
    private final Bus bus;
    private final AudioPlayer audioPlayer;

    //Debug Trace Window Components
    private JDialog traceDialog;
    private JTextArea traceTextArea;
    private JButton traceToggleButton;
    private JButton stepButton;
    private JToggleButton tracePauseButton;
    private volatile boolean tracingEnabled = false;
    private final StringBuilder frameTraceBuffer = new StringBuilder();
    //When tracing is toggled on, every frame's trace lines are also appended here so a
    //multi-frame window (e.g. an entire AccuracyCoin test) can be captured to disk -
    //the on-screen trace text area only ever shows the most recent frame.
    private java.io.PrintWriter traceFileWriter;
    //Independent of the above - lets the trace be saved to a fixed, easy-to-find
    //location (the user's Desktop) without needing to be co-located with the
    //working directory that trace_log.txt is written to.
    private JToggleButton logToFileButton;
    private java.io.PrintWriter desktopLogWriter;

    //Memory Inspector Window Components
    private JDialog memoryDialog;
    private JTable memoryTable;
    private MemoryTableModel memoryTableModel;
    private JTextField memoryGotoField;
    //Cache of the last value shown for each address, so refreshes only touch the
    //table cells that actually changed instead of resetting the whole document -
    //that's what keeps the view from flickering or losing scroll position.
    private final int[] memorySnapshot = new int[65536];

    //TAS Maker Window Components
    private JDialog tasDialog;
    private JTable tasTable;
    private TasTableModel tasTableModel;
    private JToggleButton tasPlayButton;
    //While the TAS Maker is open, it owns controller 1 entirely (recorded/edited
    //input replaces whatever the keyboard is doing) and it owns frame advancement -
    //the normal real-time loop in run() must not also be stepping frames.
    private volatile boolean tasActive = false;
    private boolean tasPlaying = false;
    private javax.swing.Timer tasPlayTimer;
    //Recorded (or edited) per-frame controller 1 bitmask, one entry per frame, in
    //the same LSB-first A/B/Select/Start/Up/Down/Left/Right bit order keyToBit uses.
    //Index i is frame i. This IS the movie - the emulator has no other notion of a
    //"savestate": seeking to any frame means replaying this list from power-on.
    private final List<Integer> tasInputs = new ArrayList<>();
    //Number of frames already executed against the live cpu/ppu/apu/bus - i.e. the
    //table row currently shown on screen is tasFrame - 1. The *next* frame Play (or
    //a forward seek) will run is tasInputs.get(tasFrame).
    private int tasFrame = 0;
    //How many frames (from frame 0) currently have a valid, up-to-date "savestate" -
    //i.e. have actually been replayed against the CURRENT contents of tasInputs.
    //Frame row < tasValidFrontier is colored green/red (see TasRowColoring); rows at
    //or past it haven't been (re-)computed yet and get the plain default look. Seeking
    //(forward or backward) never shrinks this - only editing an input does, since that's
    //the only thing that actually invalidates any previously-computed state.
    private int tasValidFrontier = 0;
    //Parallel to tasInputs: whether controller 1 was strobed (read) during that
    //frame, captured off Bus.consumeStrobedThisFrame() right after the frame that
    //produced it runs. A validated frame with no strobe is a "lag frame" - the game
    //never polled input that frame - which is what the red/green coloring surfaces.
    private final List<Boolean> tasFrameStrobed = new ArrayList<>();

    public NESEmulator() {
        super("NESEmulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        //Initialize Hardware
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

        displayPanel = new NESDisplayPanel();
        ppu.connectDisplay(displayPanel);

        setJMenuBar(createMenuBar());
        setLayout(new BorderLayout());
        add(displayPanel, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);

        createTraceWindow();
        createMemoryInspector();
        createTASMakerWindow();
        setupController();
    }

    //Player 1 mapping: Enter=Start, Z=B, X=Up(D-pad), Space=Select, Up arrow=A,
    //Left/Down/Right arrows=D-pad Left/Down/Right.
    //Bit order matches Bus's shift-out order (LSB first): A,B,Select,Start,Up,Down,Left,Right.
    private volatile int controller1Bits = 0;

    //While paused, a tap (press+release before the next Step) latches its bit here
    //instead of dropping it, so the queued button still reads as held for the one
    //frame that Step runs - letting you line up an input with the trace logger
    //without having to hold the key down for the whole real-time pause.
    private volatile int queuedInputBits = 0;

    private void setupController() {
        displayPanel.setFocusable(true);
        displayPanel.requestFocusInWindow();
        displayPanel.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int bit = keyToBit(e.getKeyCode());
                controller1Bits |= bit;
                if (paused) queuedInputBits |= bit;
                if (!tasActive) bus.setController1(controller1Bits | queuedInputBits);
            }
            @Override public void keyReleased(KeyEvent e) {
                controller1Bits &= ~keyToBit(e.getKeyCode());
                if (!tasActive) bus.setController1(controller1Bits | queuedInputBits);
            }
        });
    }

    private int keyToBit(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_UP: return 0x01;     //A
            case KeyEvent.VK_Z: return 0x02;      //B
            case KeyEvent.VK_SPACE: return 0x04;  //Select
            case KeyEvent.VK_ENTER: return 0x08;  //Start
            case KeyEvent.VK_X: return 0x10;      //D-pad Up
            case KeyEvent.VK_DOWN: return 0x20;   //D-pad Down
            case KeyEvent.VK_LEFT: return 0x40;   //D-pad Left
            case KeyEvent.VK_RIGHT: return 0x80;  //D-pad Right
            default: return 0x00;
        }
    }

    //Backs the memory inspector table: one independently-addressable cell per byte
    //($0000-$FFFF, 16 bytes/row) plus an address column and an ASCII column. Values
    //are read live from the bus on every paint, so no snapshot of memory is held here -
    //only fireTableCellUpdated() needs to be called (from scanMemoryForChanges) to make
    //a specific cell repaint, which is what lets the view update in place without
    //flickering or disturbing the current scroll position.
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

    //Bit order matches keyToBit/Bus's shift-out order (LSB first).
    private static final int[] TAS_BUTTON_BITS = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80};
    private static final String[] TAS_BUTTON_NAMES = {"A", "B", "Select", "Start", "Up", "Down", "Left", "Right"};

    //Backs the TAS Maker's frame timeline: one row per recorded frame, one checkbox
    //column per controller button. tasInputs (the movie itself) is the only backing
    //store - this model just reads/writes it, and editFrameInput handles the
    //"clear everything after an edited frame" invalidation described on NESEmulator's
    //tasInputs field.
    private class TasTableModel extends AbstractTableModel {
        @Override public int getRowCount() { return tasInputs.size(); }
        @Override public int getColumnCount() { return 1 + TAS_BUTTON_BITS.length; }

        @Override
        public String getColumnName(int col) {
            return col == 0 ? "Frame" : TAS_BUTTON_NAMES[col - 1];
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return col == 0 ? Integer.class : Boolean.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col != 0;
        }

        @Override
        public Object getValueAt(int row, int col) {
            if (col == 0) return row;
            return (tasInputs.get(row) & TAS_BUTTON_BITS[col - 1]) != 0;
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col == 0) return;
            editFrameInput(row, TAS_BUTTON_BITS[col - 1], Boolean.TRUE.equals(value));
        }
    }

    private static final Color TAS_LAG_FRAME_COLOR = new Color(0xF4A0A0);      //red: emulated, controllers never strobed
    private static final Color TAS_NORMAL_FRAME_COLOR = new Color(0xA8E6A8);   //green: emulated, controllers strobed
    private static final Color TAS_UNEMULATED_COLOR = Color.WHITE;             //past tasValidFrontier: no savestate yet

    //Shared by both TAS table cell renderers: a row past tasValidFrontier has never
    //been (re-)emulated against the movie's current contents, so it gets the plain
    //default look; otherwise it's colored by whether that frame's playthrough ever
    //strobed the controller (see tasFrameStrobed/Bus.consumeStrobedThisFrame) - an
    //emulated-but-unstrobed frame is a "lag frame" the game didn't act on.
    private Color tasRowColor(int row) {
        if (row >= tasValidFrontier) return TAS_UNEMULATED_COLOR;
        boolean strobed = row < tasFrameStrobed.size() && tasFrameStrobed.get(row);
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

    private class TasCheckboxRenderer extends JCheckBox implements TableCellRenderer {
        TasCheckboxRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            setSelected(Boolean.TRUE.equals(value));
            setBackground(isSelected ? table.getSelectionBackground() : tasRowColor(row));
            return this;
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

        DefaultTableCellRenderer centered = new DefaultTableCellRenderer();
        centered.setHorizontalAlignment(SwingConstants.CENTER);
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

        memoryDialog.add(topPanel, BorderLayout.NORTH);
        memoryDialog.add(new JScrollPane(memoryTable), BorderLayout.CENTER);
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

        tasTableModel = new TasTableModel();
        tasTable = new JTable(tasTableModel);
        tasTable.setRowHeight(20);
        tasTable.setDefaultRenderer(Integer.class, new TasFrameNumberRenderer());
        tasTable.setDefaultRenderer(Boolean.class, new TasCheckboxRenderer());
        tasTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        for (int i = 1; i <= TAS_BUTTON_BITS.length; i++) {
            tasTable.getColumnModel().getColumn(i).setPreferredWidth(50);
        }
        tasTable.getSelectionModel().setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        //Clicking OR dragging across rows (while paused) scrubs playback live, frame
        //by frame, the way FCEUX/BizHawk's TAS editors let you drag until you spot
        //something specific on screen - so this intentionally does NOT skip
        //e.getValueIsAdjusting() events, only Play (which is moving the selection
        //programmatically every frame already) and the programmatic selection
        //syncTasSelection() itself makes.
        tasTable.getSelectionModel().addListSelectionListener(e -> {
            if (tasPlaying || suppressTasSelectionEvents) return;
            int row = tasTable.getSelectedRow();
            if (row >= 0) seekToFrame(row + 1);
        });

        tasDialog.add(topPanel, BorderLayout.NORTH);
        tasDialog.add(new JScrollPane(tasTable), BorderLayout.CENTER);

        tasDialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { closeTasMaker(); }
        });

        tasPlayTimer = new javax.swing.Timer(1000 / 60, e -> tasAdvanceOneFrame());
    }

    private void openTasMaker() {
        tasActive = true;
        tasTableModel.fireTableDataChanged();
        syncTasSelection();
        tasDialog.setVisible(true);
    }

    private void closeTasMaker() {
        setTasPlaying(false);
        tasActive = false;
        tasDialog.setVisible(false);
        //Hand controller 1 back to the keyboard, reflecting whatever's physically held right now.
        bus.setController1(controller1Bits | queuedInputBits);
    }

    private void setTasPlaying(boolean playing) {
        tasPlaying = playing;
        if (tasPlayButton != null) {
            tasPlayButton.setSelected(playing);
            tasPlayButton.setText(playing ? "Pause" : "Play");
        }
        if (tasPlayTimer != null) {
            if (playing) tasPlayTimer.start(); else tasPlayTimer.stop();
        }
    }

    //Runs one more frame of the movie, appending a new blank (no-input) frame first
    //if playback has run off the end of what's been recorded so far - this is what
    //makes the timeline "grow as you play" instead of needing every frame pre-declared.
    private void tasAdvanceOneFrame() {
        boolean grew = tasFrame >= tasInputs.size();
        if (grew) tasInputs.add(0);
        int bits = tasInputs.get(tasFrame);
        bus.setController1(bits);
        updateEmulationFrame();
        int executedRow = tasFrame;
        recordFrameStrobe(executedRow);
        tasFrame++;
        if (tasFrame > tasValidFrontier) tasValidFrontier = tasFrame;
        displayPanel.renderFrame();
        if (grew) tasTableModel.fireTableRowsInserted(executedRow, executedRow);
        else tasTableModel.fireTableRowsUpdated(executedRow, executedRow);
        syncTasSelection();
    }

    //Moves the live playhead to "right after frame targetFrame-1 has executed" by
    //hard-resetting to power-on and replaying every recorded frame up to there. This
    //IS the TAS Maker's savestate mechanism - the CPU's cycle-stepping core has no
    //serializable mid-instruction state to snapshot, so seeking is always a full,
    //deterministic replay of the recorded input log rather than restoring a snapshot.
    //Seeking never shrinks tasValidFrontier (the "how far has this actually been
    //emulated" marker) - only editFrameInput does, since replaying backward doesn't
    //invalidate anything, it just re-derives frames that are still known-good.
    private void seekToFrame(int targetFrame) {
        if (targetFrame == tasFrame) return;
        bus.hardReset();
        for (int i = 0; i < targetFrame; i++) {
            int bits = i < tasInputs.size() ? tasInputs.get(i) : 0;
            bus.setController1(bits);
            updateEmulationFrame();
            recordFrameStrobe(i);
        }
        tasFrame = targetFrame;
        if (targetFrame > tasValidFrontier) tasValidFrontier = targetFrame;
        displayPanel.renderFrame();
        syncTasSelection();
        tasTable.repaint();
    }

    //Captures whether controller 1 was strobed during the frame that was just
    //executed, for the TAS Maker's lag-frame coloring (see tasFrameStrobed).
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

    //Called by TasTableModel when a checkbox is toggled. Changing a frame's input
    //invalidates that frame's "savestate" and every one after it (they were all
    //computed against the old input) - exactly like FCEUX/BizHawk invalidating the
    //greenzone from an edited frame onward. It does NOT delete the frame or any
    //frame after it: the recorded/inserted inputs for those frames are left alone,
    //they're just no longer shown as emulated until they're replayed again.
    private void editFrameInput(int row, int bit, boolean value) {
        int bits = tasInputs.get(row);
        bits = value ? (bits | bit) : (bits & ~bit);
        tasInputs.set(row, bits);
        if (row < tasValidFrontier) tasValidFrontier = row;
        tasTableModel.fireTableRowsUpdated(row, row);
        seekToFrame(row + 1);
    }

    //Inserts blank frames just after the selected row (or at the end, if nothing is
    //selected), like FCEUX/BizHawk's "Insert Frames" - for hand-placing frames of
    //held/released input without having to play through them in real time first.
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

        int selected = tasTable.getSelectedRow();
        int insertPos = (selected >= 0) ? selected + 1 : tasInputs.size();
        for (int i = 0; i < count; i++) tasInputs.add(insertPos, 0);
        //Every frame index at/after insertPos now refers to different content than
        //whatever (if anything) was previously validated there, so nothing from
        //insertPos on can still be trusted as "emulated" - the rows themselves just
        //haven't been replayed against their new contents yet (recordFrameStrobe
        //will naturally overwrite stale entries as replay reaches them again).
        if (insertPos < tasValidFrontier) tasValidFrontier = insertPos;
        tasTableModel.fireTableDataChanged();

        //Frames at/after the insertion point shifted - if the playhead was already
        //past that point, its state no longer matches the (now different) movie
        //leading up to it, so walk it back and replay. Inserting ahead of the
        //playhead doesn't touch anything already played, so nothing to redo there.
        if (insertPos < tasFrame) {
            seekToFrame(insertPos);
        } else {
            syncTasSelection();
        }
    }

    //Scans the whole address space for bytes that changed since the last scan and
    //fires a targeted update only for those cells. Runs on the emulation thread;
    //the actual fireTableCellUpdated calls are marshalled to the EDT.
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

        //Refresh UI whenever window is focused

        traceDialog.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                updateDebugUI();
            }
        });
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        //Emulation menu

        JMenu emulationMenu = new JMenu("Emulation");
        JMenuItem openRomItem = new JMenuItem("Open ROM...");
        openRomItem.addActionListener(e -> openRomChooser());
        emulationMenu.add(openRomItem);

        emulationMenu.addSeparator();

        JMenuItem pauseItem = new JMenuItem("Pause / Resume");
        pauseItem.addActionListener(e -> togglePause());
        emulationMenu.add(pauseItem);

        JMenuItem resetItem = new JMenuItem("Reset");
        resetItem.addActionListener(e -> resetEmulation());
        emulationMenu.add(resetItem);

        emulationMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        emulationMenu.add(exitItem);

        //Tools Menu

        JMenu toolsMenu = new JMenu("Tools");

        JMenuItem controllerItem = new JMenuItem("Controller Settings...");
        controllerItem.addActionListener(e -> JOptionPane.showMessageDialog(this, "Controller setup dialog"));
        toolsMenu.add(controllerItem);

        JMenuItem audioItem = new JMenuItem("Audio Settings...");
        audioItem.addActionListener(e -> JOptionPane.showMessageDialog(this, "Audio setup dialog"));
        toolsMenu.add(audioItem);

        JMenuItem tasMakerItem = new JMenuItem("TAS Maker...");
        tasMakerItem.addActionListener(e -> openTasMaker());
        toolsMenu.add(tasMakerItem);

        //Debug Menu

        JMenu debugMenu = new JMenu("Debug");

        JMenuItem traceItem = new JMenuItem("Trace");
        traceItem.addActionListener(e -> traceDialog.setVisible(true));
        debugMenu.add(traceItem);

        JMenuItem nametableViewer = new JMenuItem("Nametable Viewer");
        nametableViewer.addActionListener(e -> System.out.println("Open Nametable Debugger"));
        debugMenu.add(nametableViewer);

        JMenuItem patternTableViewer = new JMenuItem("Pattern Table Viewer");
        patternTableViewer.addActionListener(e -> System.out.println("Open Pattern Table Viewer"));
        debugMenu.add(patternTableViewer);

        JMenuItem memoryInspector = new JMenuItem("Memory Inspector");
        memoryInspector.addActionListener(e -> memoryDialog.setVisible(true));
        debugMenu.add(memoryInspector);

        //attach menus to bar

        menuBar.add(emulationMenu);
        menuBar.add(toolsMenu);
        menuBar.add(debugMenu);

        return menuBar;
    }
    //Starts the Emulation thread

    public synchronized void start() {
        if (running) return;
        running = true;
        audioPlayer.start();
        emulatorThread = new Thread(this,"NES-MainLoop");
        emulatorThread.start();
    }

    //stops the emulation thread

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

    //Main emulation (~60FPS)

    @Override
    public void run() {
        final double nsPerFrame = 1_000_000_000.0 / 60.0;
        long lastTime = System.nanoTime();
        double delta = 0;

        while (running) {
            long now = System.nanoTime();
            delta += (now - lastTime) / nsPerFrame;
            lastTime = now;

            //catch up if behind schedule

            while (delta >= 1) {
                if (!paused && !tasActive) {
                    updateEmulationFrame();
                }
                delta--;
            }

            //render current frame

            if (!paused && !tasActive) {
                displayPanel.renderFrame();
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

        boolean wasTracing = tracingEnabled;
        tracingEnabled = true;

        //Apply any queued taps for this one frame, then drop them so they don't
        //bleed into subsequent steps - only keys still physically held remain set.
        bus.setController1(controller1Bits | queuedInputBits);
        queuedInputBits = 0;

        updateEmulationFrame();
        displayPanel.renderFrame();

        bus.setController1(controller1Bits);
        tracingEnabled = wasTracing;
    }

    private void updateEmulationFrame() {
        frameTraceBuffer.setLength(0);

        //Run until the PPU itself reports the start of VBlank rather than a fixed
        //CPU-cycle count - a real NTSC frame is 89341/89342 PPU dots, which a fixed
        //count can't evenly divide, so driving off the PPU's own signal is what keeps
        //NMI/VBlank locked to the same point in every frame. Bounding on VBlank-start
        //(rather than the scanline-262 wrap) also aligns each trace slice with the
        //NMI, matching how games structure their own frame loop - see PPU.vblankStarted.
        do {
            //APU is clocked before the CPU within the same CPU cycle: on real hardware,
            //the DMC's memory-reader request (RDY low) and the APU's IRQ line (frame
            //counter / DMC) are both asserted early enough in the cycle to be visible to
            //the CPU's own interrupt polling and DMA-halt check THIS cycle - clocking the
            //APU after the CPU delayed both by a full extra CPU cycle, which is what
            //AccuracyCoin's "Interrupt Flag Latency" (error 8: DMA IRQ on the wrong CPU
            //cycle) and "NMI Overlap IRQ" tests (which rely on DMC-driven IRQ timing) catch.
            apu.clock();
            cpu.step(frameTraceBuffer, ppu, tracingEnabled);

            //PPU runs 3 clock cycles for every 1 CPU cycle
            ppu.step();
            ppu.step();
            ppu.step();
        } while (!ppu.consumeVBlankStart());

        //No explicit render call needed here - the PPU already wrote every pixel
        //directly into the display panel's buffer scanline-by-scanline as it stepped
        //through the frame above (see PPU.renderScanline), using whatever VRAM/OAM/
        //scroll state genuinely existed at that instant rather than a snapshot taken
        //after the fact.

        //Update Trace UI text area if active
        if (tracingEnabled && frameTraceBuffer.length() > 0) {
            SwingUtilities.invokeLater(() -> traceTextArea.setText(frameTraceBuffer.toString()));
            if (traceFileWriter != null) {
                traceFileWriter.print(frameTraceBuffer);
                traceFileWriter.flush();
            }
            if (desktopLogWriter != null) {
                desktopLogWriter.print(frameTraceBuffer);
                desktopLogWriter.flush();
            }
        }

        //Update Memory Inspector if visible - scans for changed bytes and only
        //fires table updates for those cells, so it never resets scroll position.
        if (memoryDialog.isVisible()) {
            scanMemoryForChanges();
        }
    }

    private void openRomChooser() {
        JFileChooser fileChooser = new JFileChooser();
        int option = fileChooser.showOpenDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                Cartridge cart = new Cartridge(selectedFile);
                bus.insertCartridge(cart);
                bus.reset();
                System.out.println("Successfully loaded ROM: " + selectedFile.getName());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to load ROM: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void togglePaused() {
        setPaused(!paused);
    }

    //Synchronizes pause states across thread boundaries and updates UI elements
    private void setPaused(boolean pauseState) {
        this.paused = pauseState;
        System.out.println(paused ? "Emulation Paused" : "Emulation Resumed");
        SwingUtilities.invokeLater(this::updateDebugUI);
    }

    private void togglePause() {
        paused = !paused;
        System.out.println(paused ? "Emulation Paused" : "Emulation Resumed");
    }

    //Updates debugger
    private void updateDebugUI() {
        if (stepButton != null) {
            stepButton.setEnabled(paused);
        }
        if (tracePauseButton != null && tracePauseButton.isSelected() != paused) {
            tracePauseButton.setSelected(paused);
        }
    }

    private void resetEmulation() {
        System.out.println("Resetting PPU and CPU...");
        bus.reset();
    }

    public static void main(String[] args) {
        //Swing GUI components should always be initialized on the Event Dispatch Thread (EDT)

        SwingUtilities.invokeLater(() -> {
            NESEmulator emulator = new NESEmulator();
            emulator.setVisible(true);

            emulator.start();
        });
    }
}