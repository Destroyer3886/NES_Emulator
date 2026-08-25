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
import java.util.Map;
import java.util.TreeMap;
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

    //Debug viewer windows
    private NametableViewerDialog nametableViewerDialog;
    private PPUInspectorDialog ppuInspectorDialog;

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
    //Index i is the row where the button was pressed, but - matching real controller
    //latency - it isn't read by the game until frame i+1: frame k's controller poll
    //sees tasInputs.get(k - 1) (frame 0 sees nothing, since there's no row -1). This
    //IS the movie - the emulator has no other notion of a "savestate": seeking to any
    //frame means replaying this list from power-on.
    private final List<Integer> tasInputs = new ArrayList<>();
    //Number of frames already executed against the live cpu/ppu/apu/bus - i.e. the
    //table row currently shown on screen is tasFrame - 1. The *next* frame Play (or
    //a forward seek) will run reads tasInputs.get(tasFrame - 1).
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
    //Greenzone checkpoints: full emulator snapshots keyed by "executed frame count"
    //(the same units as tasFrame/tasValidFrontier - a key of K means "state right
    //after frames 0..K-1 have executed"), captured on a fixed grid anchored at frame
    //0 (keys 0, N, 2N, ... where N is tasSavestateInterval) so seekToFrame can
    //restore the nearest one at/below a target instead of always replaying from
    //power-on. TreeMap so seekToFrame can cheaply find the largest key <= target via
    //floorKey. Frame 0 itself never needs an entry - bus.hardReset() already gives
    //that state for free. See maybeCaptureTasCheckpoint() for how/when entries are
    //added, and invalidateTasCheckpointsFrom() for how edits/inserts drop stale ones.
    private final TreeMap<Integer, TasCheckpoint> tasCheckpoints = new TreeMap<>();
    //Pairs a machine-state checkpoint with the display pixels that were on screen at
    //that instant - Bus.EmulatorState covers CPU/PPU/APU/Bus/cartridge, but the
    //visible framebuffer lives in NESDisplayPanel, not the PPU, so it has to be
    //captured/restored separately or an exact-checkpoint seek (no replay needed)
    //would leave the old frame on screen.
    private static final class TasCheckpoint {
        final Bus.EmulatorState state;
        final int[] pixels;
        TasCheckpoint(Bus.EmulatorState state, int[] pixels) {
            this.state = state;
            this.pixels = pixels;
        }
    }
    //"Frames per savestate" setting - see createTasSettingsDialog(). Every frame
    //count that's a multiple of this becomes a checkpoint grid point. 1 means every
    //single frame gets a checkpoint (fastest scrubbing, most memory); higher values
    //trade memory for a longer worst-case replay-to-target after a backward seek.
    private int tasSavestateInterval = 1;
    private JDialog tasSettingsDialog;

    //Frame index -> marker name ("" for an unlabeled marker). Markers paint their
    //row yellow (see tasRowColor) and let the user fast-travel via the Markers
    //dialog (openMarkersDialog) or jump the selection boundary via "Select Between
    //Markers" (selectBetweenMarkers).
    private final TreeMap<Integer, String> tasMarkers = new TreeMap<>();
    private JDialog tasMarkersDialog;
    //Copy/paste clipboard for the frame timeline: a snapshot of controller bitmasks
    //(not a live view of tasInputs), populated by copySelectedFrames and consumed by
    //pasteFrames.
    private List<Integer> tasClipboard = null;
    private JPopupMenu tasContextMenu;

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

    //Column order/letters: A, B, select, start, Up, Down, Left, Right.
    private static final int[] TAS_BUTTON_BITS = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80};
    private static final String[] TAS_BUTTON_NAMES = {"A", "B", "s", "S", "U", "D", "L", "R"};

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

        //Button cells are never edited through JTable's own click/edit machinery -
        //toggling and press-and-hold painting are handled entirely by the mouse
        //listeners set up in createTASMakerWindow, which call editFrameInput directly.
        //Editability is off here so a click doesn't ALSO kick off cell editing on top
        //of that (which was double-toggling the value right back to what it started).
        @Override
        public boolean isCellEditable(int row, int col) {
            return false;
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
    private static final Color TAS_MARKER_COLOR = Color.YELLOW;                //marked frame - takes priority over validity coloring

    //Shared by both TAS table cell renderers: a marked frame is always yellow
    //regardless of validity. Otherwise a row past tasValidFrontier has never been
    //(re-)emulated against the movie's current contents, so it gets the plain
    //default look; otherwise it's colored by whether that frame's playthrough ever
    //strobed the controller (see tasFrameStrobed/Bus.consumeStrobedThisFrame) - an
    //emulated-but-unstrobed frame is a "lag frame" the game didn't act on.
    private Color tasRowColor(int row) {
        if (tasMarkers.containsKey(row)) return TAS_MARKER_COLOR;
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

    //Draws one grid cell of the button timeline: a plain colored box that shows the
    //column's letter (U/D/L/R/S/s/B/A, per res/Example.png) when that button is
    //pressed on that frame, and nothing when it isn't - instead of a checkbox.
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
            setLetter(TAS_BUTTON_NAMES[column - 1]);
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
        //Overriding changeSelection to ignore anything outside column 0 is what keeps
        //a click/drag on a button cell from ALSO moving the seek playhead - only the
        //Frame# column drives that now; button cells are purely painted (see the
        //mouse listeners below), never selected. tasFrameColumnDragging is the
        //exception: once a drag STARTS on the Frame# column, it keeps scrubbing even
        //if the mouse's X strays off that column (over the buttons, or out of the
        //table entirely) as long as the button stays held - matching how dragging
        //normally behaves in other apps once you've grabbed a handle.
        //JTable's own mouse handling (BasicTableUI) only ever drives changeSelection
        //for the LEFT button - a right-button press/drag never reaches it at all, so
        //this path is exclusively the left-click/left-drag "follow the cursor" scrub
        //behavior. Forcing extend=false here (regardless of what JTable computed) is
        //what keeps a left-drag from turning into a multi-row range selection - it
        //always just moves the single-row selection (and playhead) to match the
        //cursor. Right-drag range-highlighting is handled separately, directly
        //against the selection model, in the mouse listener below.
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
        tasTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        for (int i = 1; i <= TAS_BUTTON_BITS.length; i++) {
            tasTable.getColumnModel().getColumn(i).setPreferredWidth(50);
        }
        //Left click/drag on the Frame# column scrubs the playhead one row at a time
        //(see changeSelection above), always ending on a single-row selection. Right
        //click+drag instead range-selects (blue highlight) for clipboard/marker/
        //add-inputs operations, without moving the playhead - so the playhead only
        //follows when the selection resolves to exactly one row.
        tasTable.getSelectionModel().setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        //Deliberately does NOT skip e.getValueIsAdjusting() events - a left-drag scrub
        //needs to seek on every intermediate row the cursor passes over, not just once
        //on release. Right-drag range-selection never reaches this at all (it's done
        //with suppressTasSelectionEvents held true), so it's never mistaken for a scrub.
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

        //Tracks whether the current drag started on the Frame# column, so
        //changeSelection above knows to keep following it even once the mouse's X
        //strays off that column.
        tasTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                tasFrameColumnDragging = javax.swing.SwingUtilities.isLeftMouseButton(e)
                        && tasTable.columnAtPoint(e.getPoint()) == 0;
            }

            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) tasFrameColumnDragging = false;
            }
        });

        //Right click+drag range-selects (blue highlight) instead of scrubbing -
        //handled directly against the selection model since JTable's own mouse
        //handling (and therefore changeSelection above) never sees right-button
        //events at all. A right press that never turns into a drag just leaves the
        //single row under the cursor selected, so a plain right-click still acts on
        //one frame via the context menu (see maybeShowTasContextMenu).
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

        //Press-and-hold "painting" of a button across many frames at once, like
        //FCEUX/BizHawk: pressing on a button cell toggles it and remembers that as
        //the paint value, then dragging up/down through other rows (still in that
        //same column) stamps every row the mouse passes over to that same value,
        //instead of needing one click per frame.
        tasTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) return;
                int col = tasTable.columnAtPoint(e.getPoint());
                int row = tasTable.rowAtPoint(e.getPoint());
                if (col >= 1 && row >= 0 && row < tasInputs.size()) {
                    int bit = TAS_BUTTON_BITS[col - 1];
                    tasPaintColumn = col;
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
        //Lets scrubbing (or button painting) continue via the scroll wheel while the
        //mouse is held down (instead of only on drag) - normally the wheel just
        //scrolls the view and the playhead only follows once you move the mouse
        //again, which meant alternating "scroll a bit, nudge the mouse, scroll a bit"
        //to cover any real distance. This lets the held-down mouse keep following (or
        //painting) the row that ends up under it after each scroll tick - but ONLY
        //for whichever gesture was actually started: a drag that began on the Frame#
        //column keeps seeking, a drag that began on a button cell keeps painting that
        //column. Scrolling with the button held elsewhere (or not held at all) does neither.
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

    //Extensible TAS Maker settings dialog: each setting is one row added via
    //addIntSetting (or a similar helper for future setting types) onto a simple
    //vertical GridBagLayout list, so adding another setting later is just another
    //call here rather than a bespoke layout. Built lazily/once and just re-shown -
    //its controls always reflect live field values since there's nothing else that
    //can change tasSavestateInterval out from under it.
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

    //Adds one "label + spinner" settings row bound to an arbitrary int field via a
    //getter/setter pair, advancing gbc.gridy for the next row. The spinner commits on
    //every change (no separate OK/Apply step) since TAS Maker settings are cheap,
    //reversible knobs, not something that needs a confirm step.
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

    //Drag-painting state for the button-timeline grid; see the mouse listeners in
    //createTASMakerWindow.
    //Whether the in-progress mouse drag started on the Frame# column; see the
    //changeSelection override in createTASMakerWindow.
    private boolean tasFrameColumnDragging = false;
    //Anchor row of an in-progress right-button drag-select on the Frame# column, or
    //-1 when no right-drag is active; see the mouse listeners in createTASMakerWindow.
    private int tasRightDragAnchor = -1;
    private Integer tasPaintColumn = null;
    private boolean tasPaintValue;
    private int tasPaintLastRow = -1;

    //Stamps tasPaintValue onto every row between tasPaintLastRow and row (inclusive),
    //clamped to the table's bounds - shared by mouseDragged and the wheel listener so
    //painting works the same whether the mouse moves or the view scrolls under it.
    private void paintTasRow(int row) {
        if (tasPaintColumn == null) return;
        row = Math.max(0, Math.min(row, tasInputs.size() - 1));
        if (row == tasPaintLastRow) return;
        int bit = TAS_BUTTON_BITS[tasPaintColumn - 1];
        int step = row > tasPaintLastRow ? 1 : -1;
        for (int r = tasPaintLastRow + step; ; r += step) {
            editFrameInput(r, bit, tasPaintValue);
            if (r == row) break;
        }
        tasPaintLastRow = row;
    }

    private void openTasMaker() {
        tasActive = true;
        audioPlayer.setMuted(true);
        //Frame 0 must always mean power-on, but normal real-time play (or just sitting
        //at the title screen since ROM load) runs the machine forward before the TAS
        //Maker is ever opened - without this, frame 0 would silently be "whatever the
        //game happened to be doing" instead of power-on, and the power-on-era lag
        //frames (before the game's init routine starts polling input) would be lost.
        //Only do this the first time - if there's already a movie/playhead (the user
        //closed and reopened the TAS Maker), leave it exactly where it was.
        if (tasFrame == 0 && tasInputs.isEmpty()) {
            bus.hardReset();
            tasValidFrontier = 0;
            tasFrameStrobed.clear();
            tasCheckpoints.clear();
            displayPanel.renderFrame();
        }
        tasTableModel.fireTableDataChanged();
        syncTasSelection();
        tasDialog.setVisible(true);
    }

    private void closeTasMaker() {
        setTasPlaying(false);
        tasActive = false;
        //Back to normal real-time emulation - always audible.
        audioPlayer.setMuted(false);
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
        //Stopping Play means every subsequent frame (until Play resumes) goes back
        //through the always-muted seekToFrame path - mute now so nothing left queued
        //from the last played frame keeps draining out afterward.
        if (!playing) audioPlayer.setMuted(true);
    }

    //Runs one more frame of the movie, appending a new blank (no-input) frame first
    //if playback has run off the end of what's been recorded so far - this is what
    //makes the timeline "grow as you play" instead of needing every frame pre-declared.
    private void tasAdvanceOneFrame() {
        boolean grew = tasFrame >= tasInputs.size();
        if (grew) tasInputs.add(0);
        int bits = tasFrame == 0 ? 0 : tasInputs.get(tasFrame - 1);
        bus.setController1(bits);
        //Play is real-time playback, so it's always audible - regardless of whether
        //the frame it's running happens to already be validated.
        audioPlayer.setMuted(false);
        updateEmulationFrame();
        int executedRow = tasFrame;
        recordFrameStrobe(executedRow);
        tasFrame++;
        if (tasFrame > tasValidFrontier) tasValidFrontier = tasFrame;
        maybeCaptureTasCheckpoint(tasFrame);
        displayPanel.renderFrame();
        if (grew) tasTableModel.fireTableRowsInserted(executedRow, executedRow);
        else tasTableModel.fireTableRowsUpdated(executedRow, executedRow);
        syncTasSelection();
    }

    //Moves the live playhead to "right after frame targetFrame-1 has executed".
    //Seeking FORWARD from the current playhead to a still-valid target needs no
    //reset at all - the live cpu/ppu/apu/bus state already reflects every frame up
    //to tasFrame, so we can just keep running from there. That's the common case
    //(dragging/playing forward through the timeline) and skipping any restore for it
    //is what keeps scrubbing responsive. Otherwise (backward, or past an edit that
    //invalidated the frontier), restore the nearest greenzone checkpoint at or before
    //targetFrame (see tasCheckpoints) and replay only the remainder - falling back to
    //a full replay from power-on only if no checkpoint that old exists (e.g. nothing's
    //been played that far yet, or checkpoints were cleared).
    //Seeking never shrinks tasValidFrontier (the "how far has this actually been
    //emulated" marker) - only editFrameInput does, since replaying backward doesn't
    //invalidate anything, it just re-derives frames that are still known-good.
    private void seekToFrame(int targetFrame) {
        if (targetFrame == tasFrame) return;
        //Forward-continuing from the live state is only safe if that state is itself
        //still valid - i.e. every frame it was built from still matches tasInputs.
        //That's true exactly while tasFrame <= tasValidFrontier; an edit can leave
        //tasFrame ahead of a (shrunk) frontier without moving the playhead back (see
        //editFrameInput), so that case has to fall back to a checkpoint/full replay too.
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
                replayFrom = 0;
            }
        }
        //Seeking/scrubbing of any kind - forward, backward, one row or many - is never
        //real-time, so it's always silent (and setMuted(true) flushes anything still
        //queued from before, so a burst of frames here can't leave audio playing back
        //on its own afterward). Only tasAdvanceOneFrame (the Play button, paced by
        //tasPlayTimer at 60Hz) or normal non-TAS emulation ever unmute.
        audioPlayer.setMuted(true);
        for (int i = replayFrom; i < targetFrame; i++) {
            int bits = (i == 0) ? 0 : (i - 1 < tasInputs.size() ? tasInputs.get(i - 1) : 0);
            bus.setController1(bits);
            updateEmulationFrame();
            recordFrameStrobe(i);
            maybeCaptureTasCheckpoint(i + 1);
        }
        tasFrame = targetFrame;
        if (targetFrame > tasValidFrontier) tasValidFrontier = targetFrame;
        displayPanel.renderFrame();
        syncTasSelection();
        tasTable.repaint();
    }

    //Captures a greenzone checkpoint for "executedCount frames have now run" if that
    //count lands on the checkpoint grid (see tasSavestateInterval) and nothing's
    //cached there yet. The live CPU may be mid-instruction at this exact frame
    //boundary (a real NTSC frame - 89341/89342 PPU dots - doesn't divide evenly into
    //CPU instruction boundaries), and CPU.State can only represent a clean boundary
    //(see its comment) - so this keeps clocking cycles past the boundary, using
    //whatever controller bits the NEXT frame will read (matching what the caller's
    //own next loop iteration/tasAdvanceOneFrame call is about to set anyway), until
    //CPU.isAtInstructionBoundary() goes true. That's normally at most ~7 CPU cycles.
    //Any strobe that happens during that small drain is still correctly attributed -
    //it's genuinely part of the next frame's execution, and gets folded into that
    //frame's own recordFrameStrobe() call once the caller's loop reaches it.
    private void maybeCaptureTasCheckpoint(int executedCount) {
        if (executedCount <= 0) return; //frame 0's state is free via bus.hardReset()
        if (tasSavestateInterval <= 0 || executedCount % tasSavestateInterval != 0) return;
        if (tasCheckpoints.containsKey(executedCount)) return;
        int bits = (executedCount - 1 < tasInputs.size()) ? tasInputs.get(executedCount - 1) : 0;
        bus.setController1(bits);
        while (!cpu.isAtInstructionBoundary()) {
            apu.clock();
            cpu.step(frameTraceBuffer, ppu, tracingEnabled);
            ppu.step();
            ppu.step();
            ppu.step();
        }
        tasCheckpoints.put(executedCount, new TasCheckpoint(bus.snapshot(), displayPanel.snapshotPixels()));
    }

    //Drops every checkpoint whose key is past newFrontier - i.e. any checkpoint that
    //reflects at least one frame at/after the edit or insertion point, matching
    //exactly the same cutoff editFrameInput/insertTasFrames already use for
    //tasValidFrontier (a checkpoint at key == newFrontier only reflects frames
    //strictly before the change, so it's still good).
    private void invalidateTasCheckpointsFrom(int newFrontier) {
        tasCheckpoints.tailMap(newFrontier, false).clear();
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

    //Called by TasTableModel when a button box is toggled. Per the tasInputs field
    //comment, a button pressed on row `row` isn't read by the game until frame
    //row+1 - so this leaves frame `row` itself alone and invalidates frame row+1
    //and every one after it (they were all computed against the old input) - exactly
    //like FCEUX/BizHawk invalidating the greenzone from the affected frame onward. It
    //does NOT delete the frame or any frame after it: the recorded/inserted inputs
    //for those frames are left alone, they're just no longer shown as emulated until
    //they're replayed again. This intentionally does NOT seek/replay anything itself -
    //editing a button only edits the movie; the playhead stays exactly where it was
    //until the user explicitly scrubs or plays (see seekToFrame's tasFrame vs.
    //tasValidFrontier check for why that stays safe even though the live emulated
    //state may now sit ahead of the newly-shrunk frontier).
    private void editFrameInput(int row, int bit, boolean value) {
        int bits = tasInputs.get(row);
        bits = value ? (bits | bit) : (bits & ~bit);
        tasInputs.set(row, bits);
        if (row + 1 < tasValidFrontier) {
            tasValidFrontier = row + 1;
            invalidateTasCheckpointsFrom(tasValidFrontier);
        }
        tasTableModel.fireTableRowsUpdated(row, row);
        tasTable.repaint();
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

        int selected = tasSelectionAnchor();
        int insertPos = (selected >= 0) ? selected + 1 : tasInputs.size();
        insertBlankFramesAt(insertPos, count);
    }

    private void insertOneTasFrame() {
        int selected = tasSelectionAnchor();
        int insertPos = (selected >= 0) ? selected + 1 : tasInputs.size();
        insertBlankFramesAt(insertPos, 1);
    }

    //Shared by "Insert", "Insert Frames...", and paste-insert: splices `count` blank
    //(no-input) frames into tasInputs at insertPos, pushing every existing frame at
    //or after insertPos forward, then fixes up validity/checkpoints/markers/playhead
    //to match. See insertClipboardAt for the copied-frames equivalent.
    private void insertBlankFramesAt(int insertPos, int count) {
        List<Integer> blanks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) blanks.add(0);
        insertFramesAt(insertPos, blanks);
    }

    //Splices `frames` into tasInputs at insertPos, pushing every existing frame at or
    //after insertPos forward by frames.size(). Shared by blank inserts and paste-insert.
    private void insertFramesAt(int insertPos, List<Integer> frames) {
        if (frames.isEmpty()) return;
        tasInputs.addAll(insertPos, frames);
        shiftMarkersFrom(insertPos, frames.size());
        //Every frame index at/after insertPos now refers to different content than
        //whatever (if anything) was previously validated there, so nothing from
        //insertPos on can still be trusted as "emulated" - the rows themselves just
        //haven't been replayed against their new contents yet (recordFrameStrobe
        //will naturally overwrite stale entries as replay reaches them again).
        if (insertPos < tasValidFrontier) {
            tasValidFrontier = insertPos;
            invalidateTasCheckpointsFrom(tasValidFrontier);
        }
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

    //Shifts every marker at/after `pos` by `delta` rows - used whenever frames are
    //spliced into (or, in principle, removed from) the timeline so markers keep
    //pointing at the same logical frame instead of the same row index.
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

    //The row clipboard/insert/marker operations anchor on: the lowest selected row,
    //or -1 if nothing is selected (meaning "at the end"/"none").
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

    //Right-clicking a row that isn't already part of the current selection selects
    //just that row first (so right-clicking a single frame acts on that frame), but
    //right-clicking inside an existing multi-row selection leaves it alone - that's
    //what lets you drag-highlight a group, then right-click within it to copy the
    //whole group.
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

    //Snapshots the controller bitmask of every currently selected frame (in ascending
    //row order) into tasClipboard.
    private void copySelectedTasFrames() {
        int[] rows = tasTable.getSelectedRows().clone();
        Arrays.sort(rows);
        if (rows.length == 0) return;
        List<Integer> copied = new ArrayList<>(rows.length);
        for (int r : rows) copied.add(tasInputs.get(r));
        tasClipboard = copied;
    }

    //Paste (overwrite): replaces frames starting at the selection anchor with the
    //clipboard contents in place - extending the movie if the clipboard runs past
    //its current end - without shifting anything else.
    //Paste Insert: splices the clipboard's frames in at the anchor, pushing every
    //existing frame at/after it forward (like insertFramesAt).
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

    //Marks the selected (single) frame with a marker - unlabeled, or prompting for a
    //name when `labeled` is true. Adding a marker to an already-marked frame just
    //replaces its label.
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

    //Extends the selection backward from the anchor frame to (and including) the
    //nearest earlier marker, or frame 0 if there is none, and forward from the
    //anchor to (but excluding) the nearest later marker, or the last frame if there
    //is none.
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

    //Toggles one controller button across every selected frame: if every selected
    //frame already has that button pressed, it's cleared from all of them; otherwise
    //it's set on all of them (matching how DAW/TAS-editor "stamp" tools usually work).
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

    private void showAddInputsMenu(Component invoker) {
        JPopupMenu menu = new JPopupMenu();
        for (int i = 0; i < TAS_BUTTON_NAMES.length; i++) {
            int bit = TAS_BUTTON_BITS[i];
            JMenuItem item = new JMenuItem(TAS_BUTTON_NAMES[i]);
            item.addActionListener(e -> toggleInputOnSelection(bit));
            menu.add(item);
        }
        menu.show(invoker, 0, invoker.getHeight());
    }

    //Lists every marker (frame number + name) and fast-travels the playhead/selection
    //to whichever one is clicked.
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

    //Saves just the movie itself - the per-frame controller 1 bitmask list - as one
    //decimal value per line. Nothing about the current "savestate" (tasFrame,
    //tasValidFrontier, tasFrameStrobed) is persisted: loading a TAS always starts
    //replay from power-on, same as any other seek.
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
            for (int bits : tasInputs) writer.println(bits);
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(tasDialog, "Failed to save TAS: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    //Loads a movie saved by saveTas, replacing tasInputs wholesale and restarting
    //playback from power-on - none of the old savestate (tasFrame/tasValidFrontier/
    //tasFrameStrobed) carries over, since it described the movie that's being replaced.
    private void loadTas() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("TAS files (*.tas)", "tas"));
        int option = fileChooser.showOpenDialog(tasDialog);
        if (option != JFileChooser.APPROVE_OPTION) return;

        List<Integer> loaded = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(fileChooser.getSelectedFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                loaded.add(Integer.parseInt(line));
            }
        } catch (java.io.IOException | NumberFormatException ex) {
            JOptionPane.showMessageDialog(tasDialog, "Failed to load TAS: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        setTasPlaying(false);
        tasInputs.clear();
        tasInputs.addAll(loaded);
        tasFrameStrobed.clear();
        tasValidFrontier = 0;
        tasFrame = 0;
        tasCheckpoints.clear();
        tasMarkers.clear();
        bus.hardReset();
        displayPanel.renderFrame();
        tasTableModel.fireTableDataChanged();
        syncTasSelection();
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

        //Prints every live OAM entry (index/Y/tile/attr/X, decoded priority+flip+
        //palette bits) to stdout and a copyable dialog - meant to be used paused,
        //right when a sprite-rendering bug is visible on screen, so the exact OAM
        //state behind it can be reported without needing a debugger attached.
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

        //Arms a one-shot click on the game display: translates the click into NES
        //pixel coordinates (accounting for the panel's current scale) and shows
        //exactly why that pixel is the color it is - background opacity, every
        //sprite covering that column on that scanline, and which one (if any) won.
        //Meant to be used paused, right on a frame showing a suspicious sprite/
        //priority rendering bug, so it can be diagnosed by clicking on it directly.
        JMenuItem explainPixelItem = new JMenuItem("Explain Pixel (click screen)...");
        explainPixelItem.addActionListener(e -> {
            JOptionPane.showMessageDialog(this, "Click anywhere on the game screen to explain that pixel.",
                    "Explain Pixel", JOptionPane.INFORMATION_MESSAGE);
            displayPanel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent ev) {
                    displayPanel.removeMouseListener(this);
                    int nx = ev.getX() * NESDisplayPanel.VIEW_WIDTH / Math.max(1, displayPanel.getWidth());
                    int ny = ev.getY() * NESDisplayPanel.VIEW_HEIGHT / Math.max(1, displayPanel.getHeight());
                    //Shows a 24x24 ASCII grid centered on the click (see explainGrid's
                    //own comment) - imprecise since a 1:1 click-to-pixel mapping across
                    //a scaled display isn't exact, but the grid is wide enough that the
                    //area of interest lands somewhere in it regardless.
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

        if (nametableViewerDialog.isVisible()) {
            nametableViewerDialog.refresh();
        }
        if (ppuInspectorDialog.isVisible()) {
            ppuInspectorDialog.refresh();
        }
    }

    private void openRomChooser() {
        JFileChooser fileChooser = new JFileChooser();
        int option = fileChooser.showOpenDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                Cartridge cart = new Cartridge(selectedFile);
                cart.connectCPU(cpu);
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