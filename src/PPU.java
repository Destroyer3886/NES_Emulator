public class PPU {

    public static boolean DEBUG_SPRITE0 = false;

    //$2002 read: CPU's M2 samples VBlank and sprite flags at different points within
    //the cycle. VBlank latches when M2 rises; sprite 0 hit/overflow read off the
    //flip-flops when M2 falls, ~1.875 dots later. AccuracyCoin "$2002 Flag Timing"
    //test 1 measures this skew. dotsUntilPreRenderFlagClear() below reports dots left
    //until the pre-render clear, so sprite bits see it iff within the falling-edge lookahead.
    private static final int M2_FALL_LOOKAHEAD_DOTS = 2; //sprite flags: sampled at M2's falling edge

    //VBlank/sprite0hit/overflow all clear together on dot 1 of pre-render (scanline 261).
    private int dotsUntilPreRenderFlagClear() {
        if (scanline == 261 && cycle == 0) return 1;
        if (scanline == 260) return (341 - cycle) + 1;
        return Integer.MAX_VALUE;
    }

    //Frame parity for pre-render's odd-frame dot skip - see step().
    private boolean oddFrame = false;

    //Debug-only: records per-pixel bg-opaque/winning-OAM state live as each pixel
    //renders, since MMC3 mid-frame CHR bank swaps (e.g. SMB3 status bar) make
    //re-deriving a paused frame's content afterward use the wrong (last-selected) bank.
    private final boolean[] debugBgOpaque = new boolean[256 * 240];
    private final int[] debugWinnerOam = new int[256 * 240]; //-1 = background/backdrop won

    // Standard NES System Palette (64 Colors)
    //PPUMASK bits 7-6-5 select one of 8 emphasis sets; the DAC tints the whole
    //picture, so each combination is its own real 64-color table, not a shared table
    //plus a multiplier. Row = (ppumask>>5)&0x07, bit0=R/bit1=G/bit2=B - see
    //currentPalette(). Values measured off real NTSC RP2C02 (res/nes_palette_hex.txt).
    public static final int[][] EMPHASIS_PALETTE = {
        { 0x575757, 0x000C8E, 0x0800A6, 0x340096, 0x550061, 0x630015, 0x5A0000, 0x3C0E00, 0x112800, 0x003B00, 0x004200, 0x003A05, 0x002652, 0x000000, 0x000000, 0x000000, 0xA5A5A5, 0x0041D9, 0x2F1EFF, 0x6704F2, 0x9400B4, 0xAA0057, 0xA31800, 0x803900, 0x4B5B00, 0x137600, 0x008100, 0x007923, 0x006288, 0x000000, 0x000000, 0x000000, 0xFFFFFF, 0x4A9FFF, 0x797EFF, 0xAF63FF, 0xDD55FF, 0xF757C2, 0xF76A63, 0xDC8810, 0xAEA900, 0x78C400, 0x4AD211, 0x2FCF64, 0x2FBDC4, 0x414141, 0x000000, 0x000000, 0xFFFFFF, 0xB9DDFF, 0xCAD1FF, 0xDEC6FF, 0xF0C0FF, 0xFCC0EE, 0xFDC6CA, 0xF5D0AA, 0xE4DD95, 0xD0E892, 0xBDEEA2, 0xB2EEC0, 0xB0E8E3, 0xB3B3B3, 0x000000, 0x000000 },
        { 0x593A32, 0x000065, 0x0A007C, 0x33006E, 0x530040, 0x610000, 0x590000, 0x3C0200, 0x131800, 0x002700, 0x002A00, 0x002100, 0x000E31, 0x000000, 0x000000, 0x000000, 0xA67A77, 0x0022A8, 0x3205CB, 0x6700C0, 0x920089, 0xA80035, 0xA10800, 0x7F2700, 0x4C4400, 0x155A00, 0x006000, 0x005707, 0x004061, 0x000000, 0x000000, 0x000000, 0xFDCACE, 0x5175E6, 0x7E58FF, 0xB140FF, 0xDD35E1, 0xF63A93, 0xF44C3A, 0xD96800, 0xAC8600, 0x789D00, 0x4CA800, 0x34A342, 0x36919B, 0x43271E, 0x000000, 0x000000, 0xFDCACE, 0xBBACD5, 0xCBA0E7, 0xDF96E9, 0xF091D9, 0xFB92BC, 0xFB9799, 0xF2A27C, 0xE2AE69, 0xCFB867, 0xBDBD77, 0xB3BC94, 0xB2B7B7, 0xB48785, 0x000000, 0x000000 },
        { 0x2F5127, 0x000869, 0x00007B, 0x150068, 0x310036, 0x3D0000, 0x360000, 0x1D0C00, 0x002600, 0x003900, 0x004000, 0x003700, 0x002336, 0x000000, 0x000000, 0x000000, 0x709B5F, 0x003AA7, 0x0F19C5, 0x4000B3, 0x670079, 0x7A0024, 0x741600, 0x563600, 0x285800, 0x007200, 0x007C00, 0x007406, 0x005C61, 0x000000, 0x000000, 0x000000, 0xC3EFA9, 0x2394D6, 0x4E74FE, 0x7F5AF9, 0xA94ECA, 0xC0517A, 0xBF6423, 0xA68200, 0x7CA200, 0x4BBC00, 0x20C800, 0x09C533, 0x0AB28C, 0x1C3C16, 0x000000, 0x000000, 0xC3EFA9, 0x83CFB5, 0x93C3C7, 0xA6B9C9, 0xB7B4B9, 0xC1B49C, 0xC2BA7A, 0xBAC55C, 0xAAD14A, 0x97DB48, 0x85E058, 0x7BE075, 0x7ADA97, 0x7DA86B, 0x000000, 0x000000 },
        { 0x363820, 0x00005B, 0x00006D, 0x1A005D, 0x34002F, 0x400000, 0x390000, 0x200000, 0x001600, 0x002500, 0x002900, 0x002000, 0x000D2C, 0x000000, 0x000000, 0x000000, 0x78785A, 0x002199, 0x1705B6, 0x4600A6, 0x6C006F, 0x7E001D, 0x770800, 0x592500, 0x2C4200, 0x005700, 0x005F00, 0x005501, 0x003F57, 0x000000, 0x000000, 0x000000, 0xC8C6A6, 0x2E72CD, 0x5855F2, 0x883EED, 0xB134BE, 0xC63972, 0xC34B1E, 0xAA6700, 0x808400, 0x509B00, 0x27A500, 0x12A131, 0x158E85, 0x23250F, 0x000000, 0x000000, 0xC8C6A6, 0x8BA8B1, 0x9B9CC2, 0xAD93C3, 0xBE8EB2, 0xC78F96, 0xC79576, 0xBF9F59, 0xAFAB47, 0x9CB447, 0x8BB957, 0x82B973, 0x82B394, 0x858567, 0x000000, 0x000000 },
        { 0x3F3C6E, 0x000097, 0x0200AE, 0x29009C, 0x450068, 0x4E0020, 0x420000, 0x250000, 0x001100, 0x002200, 0x002900, 0x002417, 0x00145F, 0x000000, 0x000000, 0x000000, 0x7F80BF, 0x002CE7, 0x230DFF, 0x5600FA, 0x7D00BD, 0x8D0063, 0x820304, 0x5F2000, 0x2E3E00, 0x005600, 0x006100, 0x005C3E, 0x00499C, 0x000000, 0x000000, 0x000000, 0xCED4FF, 0x347FFF, 0x6060FF, 0x9247FF, 0xBC3AFF, 0xD23CD1, 0xCE4D76, 0xB2682A, 0x868701, 0x549F04, 0x2BAC35, 0x15AB85, 0x189AE0, 0x2C2856, 0x000000, 0x000000, 0xCED4FF, 0x90B5FF, 0xA0A9FF, 0xB49FFF, 0xC599FF, 0xCF99FF, 0xCF9FE1, 0xC6AAC2, 0xB6B6B0, 0xA3C0AE, 0x92C5BF, 0x88C5DC, 0x87BFFE, 0x8C8ECC, 0x000000, 0x000000 },
        { 0x3E2E42, 0x00006D, 0x010082, 0x260074, 0x410047, 0x4B0007, 0x410000, 0x240000, 0x000C00, 0x001B00, 0x002000, 0x001900, 0x00083A, 0x000000, 0x000000, 0x000000, 0x806B87, 0x001BB3, 0x2300D3, 0x5400C6, 0x790090, 0x8A003E, 0x800000, 0x5E1A00, 0x2E3600, 0x004B00, 0x005300, 0x004C18, 0x00376E, 0x000000, 0x000000, 0x000000, 0xCEB9D9, 0x3768F4, 0x624CFF, 0x9335FF, 0xBB2AE9, 0xD02D9C, 0xCB3F45, 0xB05A00, 0x857700, 0x548D00, 0x2C980A, 0x189556, 0x1C83AD, 0x2B1C2D, 0x000000, 0x000000, 0xCEB9D9, 0x929BE2, 0xA190F2, 0xB586F3, 0xC581E3, 0xCF82C7, 0xCE88A5, 0xC69289, 0xB69E78, 0xA3A777, 0x92AC87, 0x89ACA3, 0x89A5C6, 0x8C7894, 0x000000, 0x000000 },
        { 0x2A393D, 0x000070, 0x000082, 0x15006F, 0x30003D, 0x3A0000, 0x310000, 0x170000, 0x001100, 0x002200, 0x002900, 0x002201, 0x001241, 0x000000, 0x000000, 0x000000, 0x667A7D, 0x0027B4, 0x0F09D0, 0x3F00BE, 0x640084, 0x740032, 0x6B0400, 0x4D2000, 0x1F3E00, 0x005500, 0x005F00, 0x00591D, 0x004472, 0x000000, 0x000000, 0x000000, 0xB2CAC9, 0x2077EE, 0x4B59FF, 0x7A41FF, 0xA136DD, 0xB6398F, 0xB34A3B, 0x996600, 0x6F8400, 0x3F9B00, 0x18A705, 0x03A452, 0x0693A7, 0x18262A, 0x000000, 0x000000, 0xB2CAC9, 0x77ACD5, 0x87A0E5, 0x9A97E6, 0xAA92D6, 0xB392B9, 0xB39898, 0xAAA27B, 0x9AAE6A, 0x88B86A, 0x78BC79, 0x6FBC96, 0x6EB6B8, 0x728789, 0x000000, 0x000000 },
        { 0x2F2F2F, 0x000061, 0x000073, 0x170062, 0x320035, 0x3B0000, 0x320000, 0x190000, 0x000D00, 0x001C00, 0x002000, 0x001900, 0x000833, 0x000000, 0x000000, 0x000000, 0x6C6C6C, 0x001BA2, 0x1400BE, 0x4300AE, 0x670077, 0x760028, 0x6E0000, 0x4F1B00, 0x223700, 0x004C00, 0x005300, 0x004C0E, 0x003762, 0x000000, 0x000000, 0x000000, 0xB8B8B8, 0x2768DB, 0x514CFF, 0x7F35FA, 0xA62ACB, 0xBA2F80, 0xB7412D, 0x9D5B00, 0x737800, 0x448E00, 0x1D9900, 0x099543, 0x0C8397, 0x1D1D1D, 0x000000, 0x000000, 0xB8B8B8, 0x7E9BC3, 0x8E90D4, 0xA086D4, 0xB081C3, 0xB982A7, 0xB98987, 0xB0936A, 0xA09E5A, 0x8EA75A, 0x7EAC6A, 0x75AB86, 0x75A5A7, 0x787878, 0x000000, 0x000000 }
    };

    //Row-0 (no emphasis) table, kept for debug tools that show a fixed reference
    //palette instead of the live emphasis-tinted one.
    public static final int[] NES_PALETTE = EMPHASIS_PALETTE[0];

    //Emphasis-tinted color for a palette index, sampled live from PPUMASK - mid-scanline
    //$2001 writes change tint starting the very next dot, matching the DAC.
    private int currentPaletteRgb(int paletteIndex) {
        int idx = paletteIndex & 0x3F;
        //Greyscale (PPUMASK bit 0) is DAC-level too: forces low 4 bits to 0, collapsing
        //to the grey column, and applies to actual rendering (see cpuRead's
        //"Palette RAM Quirks" comment for the read-side version).
        if ((ppumask & 0x01) != 0) idx &= 0x30;
        return EMPHASIS_PALETTE[(ppumask >> 5) & 0x07][idx];
    }

    //Sized for four-screen (4x1KB nametables) even though real PPU has 2KB VRAM for
    //common mirroring modes - mapNametableAddress() picks how many slots are distinct;
    //four-screen boards use the spare 2KB this array already has.
    private final byte[] nametables = new byte[4096];
    private final byte[] paletteRam = new byte[32];
    private final byte[] oam = new byte[256];       //Object Attribute Memory

    //PPU Control / Status Registers
    public int ppuctrl = 0; //$2000
    public int ppumask = 0; //$2001
    public int ppustatus = 0; //$2002
    public int oamaddr = 0; //$2003
    public int oamdata = 0; //$2004
    public int ppuscroll = 0; //$2005
    public int ppuaddr = 0; //$2006
    public int ppudata = 0; //$2007

    //Internal "loopy" scroll/address latches instead of raw PPUSCROLL bytes. v = current
    //VRAM address / scroll position; t = staging register for $2000/$2005/$2006; x =
    //fine X; w = shared write toggle for $2005/$2006. 15 bits: yyy NN YYYYY XXXXX
    //(fine Y, nametable, coarse Y, coarse X). This is what makes mid-frame scroll
    //splits (SMB status bar) work: v only picks up t's horizontal bits at cycle 257 of
    //the previous scanline, and vertical bits during pre-render - not on every $2005 write.
    private boolean addressLatch = false; //w
    private int v = 0x0000;
    private int t = 0x0000;

    //True A12 line state for CPU-side PPU-bus accesses ($2006/$2007) outside the
    //per-pixel renderer's own approximation. MMC3's IRQ counter clocks on A12's 0->1
    //edge; blargg's mmc3_irq_tests toggle $2006 with rendering off, which the
    //once-per-scanline approximation (step()'s cycle==260 site) can't reproduce.
    private boolean a12 = false;

    //Separate A12 state for the rendering-fetch approximation, so it doesn't fight the
    //bus-access tracker above over one shared level - each infers A12 from a different
    //signal, and conflating them produced spurious clocks (traced via mmc3_irq_tests).
    private boolean renderA12 = false;

    //Clocks the mapper IRQ counter on A12's low-to-high transition, given the actual
    //PPU address bus value (see call sites in cpuWrite/cpuRead).
    private void clockA12(int address) {
        boolean newA12 = (address & 0x1000) != 0;
        if (newA12 && !a12 && bus != null) {
            bus.clockMapperIRQ();
        }
        a12 = newA12;
    }
    private int fineX = 0;
    private byte readBuffer = 0x00;

    //External octal latch holding the PPU address bus's low 8 bits, plus a flag for
    //the dot a CPU $2007 access asserts Read. See aleDot()/readDot().
    private int octalLatch = 0;
    private boolean ppuDataReadThisDot = false;
    private boolean ppuDataReadServiced = false;
    //A $2007 read doesn't fill the buffer on the CPU's issuing dot - the register's
    //state machine runs first and the Read line asserts several dots later, by which
    //time the fetch cadence has moved on. With rendering off this is invisible; with
    //rendering on it's exactly what "ALE + Read" and "$2007 Stress Test" measure.
    //5 dots = 3 remaining in the issuing CPU cycle + 2 for the state machine.
    private static final int PPUDATA_READ_DELAY_DOTS = 5;
    private int ppuDataReadDelay = 0;
    private int ppuDataReadAddr = 0;
    private boolean ppuDataIncPending = false;

    //Floating PPU data bus: every register access latches its value here; reading a
    //write-only register (or $2002's unused low 5 bits) returns this instead of 0.
    private int ppuDataBus = 0x00;
    //Real hardware's bus is a capacitor that decays to 0 within well under a second -
    //AccuracyCoin "PPU Register Open Bus" test 5 checks it decays that fast.
    //~5,369,318 dots/sec on NTSC; any write resets the decay budget.
    private static final int PPU_BUS_DECAY_TICKS = 4_500_000;
    private int ppuDataBusDecay = PPU_BUS_DECAY_TICKS;

    //Raw PPUSCROLL bytes, kept only for DEBUG_SPRITE0 trace printouts.
    private int scrollX = 0;
    private int scrollY = 0;

    public int cycle = 0;
    public int scanline = 0;

    //Power-on warm-up: for ~29658 CPU cycles (~2 frames) after power-on, the PPU's
    //internal circuitry hasn't stabilized and writes to $2000/$2001/$2005/$2006 are
    //ignored outright (not even latched for later). A reset (not a fresh power-on)
    //finds the PPU already warm, so this only applies after hardReset(). Missing this
    //makes register writes during boot take effect a frame earlier than real hardware,
    //which throws off games (e.g. SMB3) whose init code is timed around it.
    private static final int PPU_WARMUP_DOTS = 29658 * 3;
    private int warmupDotsRemaining = 0;

    //Debug-only: whether sprite-0-hit fired during the frame that just ended, captured
    //before ppustatus's hit bit is cleared for the new frame - sampling the live bit
    //right after VBlank starts would already reflect the next frame.
    public boolean lastFrameHadHit = false;
    private boolean hitOccurredThisFrame = false;

    //Set for one tick at VBlank start (scanline 241, cycle 1). The main loop polls this
    //to bound each CPU "frame" instead of a fixed cycle count, since a real NTSC frame
    //(89341/89342 dots) doesn't divide evenly and a fixed count used to desync NMI timing.
    //
    //Bounding on VBlank-start also matches how games structure their own loop (NMI =
    //"start of frame"), so trace logs slice the same way real gameplay does.
    //
    //The real NMI line is level-sensitive AND(VBlank flag, PPUCTRL bit7); the CPU's NMI
    //input is edge-sensitive on that line's 0->1 transition. Tracking only "did VBlank
    //just start AND was NMI already enabled" misses other ways the transition can
    //happen: enabling NMI via $2000 while VBlank is already set (AccuracyCoin "NMI
    //Control" test 3), or disabling/re-enabling within the same VBlank (test 7).
    //nmiLine mirrors the AND's last value; updateNmiLine() re-evaluates it (firing
    //cpu.raiseNMI() on a 0->1 edge) from every place that can change either input.
    private boolean nmiLine = false;
    //Mirrors ppustatus's VBlank bit for NMI purposes, but clears 1 dot earlier than the
    //CPU-visible $2002 bit - matches AccuracyCoin "NMI at VBlank End" timing, which is
    //1 dot earlier than "VBlank End"'s own $2002-based table despite the same target.
    private boolean nmiVblLevel = false;
    private void updateNmiLine() {
        boolean newLine = nmiVblLevel && ((ppuctrl & 0x80) != 0);
        if (newLine && !nmiLine && cpu != null) cpu.raiseNMI();
        nmiLine = newLine;
    }

    //Set when $2002 is read exactly 1 PPU cycle before the VBlank-set tick (scanline
    //241, cycle 0). Per AccuracyCoin "VBlank Beginning" (case A=4) and "NMI
    //Suppression", such a read suppresses VBlank (and thus NMI) for the rest of this frame.
    private boolean suppressVblSet = false;

    private boolean vblankStarted = false;
    public boolean consumeVBlankStart() {
        boolean result = vblankStarted;
        vblankStarted = false;
        return result;
    }

    private Bus bus;
    private CPU cpu;
    private NESDisplayPanel display;

    public void connectBus(Bus bus) { this.bus = bus; }
    public void connectCPU(CPU cpu) { this.cpu = cpu; }
    public void connectDisplay(NESDisplayPanel display) { this.display = display; }

    public void reset() {
        ppuctrl = 0; ppumask = 0; ppustatus = 0;
        oamaddr = 0; ppuaddr = 0; ppudata = 0;
        addressLatch = false; v = 0; t = 0; fineX = 0;
        scrollX = 0; scrollY = 0;
        cycle = 0; scanline = 0;
        nmiLine = false;
    }

    //Full power-on reset: also clears VRAM/OAM/palette RAM and the read buffer/data
    //bus (unlike a real reset button) - needed so the TAS Maker's "replay from
    //power-on" always lands on the same state across repeated replays.
    public void hardReset() {
        reset();
        java.util.Arrays.fill(nametables, (byte) 0);
        java.util.Arrays.fill(paletteRam, (byte) 0);
        java.util.Arrays.fill(oam, (byte) 0);
        readBuffer = 0;
        ppuDataBus = 0;
        ppuDataBusDecay = PPU_BUS_DECAY_TICKS;
        vblankStarted = false;
        lastFrameHadHit = false;
        hitOccurredThisFrame = false;
        warmupDotsRemaining = PPU_WARMUP_DOTS;
    }

    //TAS Maker greenzone checkpoint - see CPU.State for why it's only captured/restored
    //at instruction boundaries. row* fields are transient per-scanline scratch
    //(cheap arrays); snapshotting them anyway avoids relying on that staying true.
    public static final class State {
        byte[] nametables, paletteRam, oam;
        int ppuctrl, ppumask, ppustatus, oamaddr, oamdata, ppuscroll, ppuaddr, ppudata;
        boolean addressLatch;
        int v, t, fineX;
        byte readBuffer;
        int ppuDataBus;
        int ppuDataBusDecay;
        int scrollX, scrollY;
        int cycle, scanline;
        boolean lastFrameHadHit, hitOccurredThisFrame;
        boolean nmiLine, nmiVblLevel, suppressVblSet, vblankStarted, pendingVblNmiEdge, oddFrame;
        int[] sprShiftLo, sprShiftHi, sprAttrLatch, sprCounter, sprPixelOut;
        boolean[] sprHalted, sprIsZero;
        boolean rowShowBackground, rowShowSprites, rowShowLeftBackground, rowShowLeftSprites;
        int rowBackdropPaletteIdx;
        int bgNextTileId, bgNextAttr, bgNextLsb, bgNextMsb;
        int bgShiftPatternLo, bgShiftPatternHi, bgShiftAttrLo, bgShiftAttrHi;
        byte[] secondaryOam;
        int evalAddr, evalCopyRemaining, evalSecCount, evalSpritesScanned, evalLastReadValue, evalTargetScanline, evalStepsExecuted;
        boolean evalActive;
        int octalLatch, ppuDataReadDelay, ppuDataReadAddr, pendingMaskValue, pendingMaskDelay, pendingVValue, pendingVDelay;
        int warmupDotsRemaining;
    }

    public State snapshot() {
        State s = new State();
        s.nametables = nametables.clone();
        s.paletteRam = paletteRam.clone();
        s.oam = oam.clone();
        s.ppuctrl = ppuctrl; s.ppumask = ppumask; s.ppustatus = ppustatus;
        s.oamaddr = oamaddr; s.oamdata = oamdata; s.ppuscroll = ppuscroll;
        s.ppuaddr = ppuaddr; s.ppudata = ppudata;
        s.addressLatch = addressLatch;
        s.v = v; s.t = t; s.fineX = fineX;
        s.readBuffer = readBuffer;
        s.ppuDataBus = ppuDataBus;
        s.ppuDataBusDecay = ppuDataBusDecay;
        s.scrollX = scrollX; s.scrollY = scrollY;
        s.cycle = cycle; s.scanline = scanline;
        s.lastFrameHadHit = lastFrameHadHit; s.hitOccurredThisFrame = hitOccurredThisFrame;
        s.nmiLine = nmiLine; s.nmiVblLevel = nmiVblLevel; s.suppressVblSet = suppressVblSet; s.oddFrame = oddFrame;
        s.vblankStarted = vblankStarted; s.pendingVblNmiEdge = pendingVblNmiEdge;
        s.sprShiftLo = sprShiftLo.clone();
        s.sprShiftHi = sprShiftHi.clone();
        s.sprAttrLatch = sprAttrLatch.clone();
        s.sprCounter = sprCounter.clone();
        s.sprPixelOut = sprPixelOut.clone();
        s.sprHalted = sprHalted.clone();
        s.sprIsZero = sprIsZero.clone();
        s.rowShowBackground = rowShowBackground; s.rowShowSprites = rowShowSprites;
        s.rowShowLeftBackground = rowShowLeftBackground; s.rowShowLeftSprites = rowShowLeftSprites;
        s.rowBackdropPaletteIdx = rowBackdropPaletteIdx;
        s.bgNextTileId = bgNextTileId; s.bgNextAttr = bgNextAttr;
        s.bgNextLsb = bgNextLsb; s.bgNextMsb = bgNextMsb;
        s.bgShiftPatternLo = bgShiftPatternLo; s.bgShiftPatternHi = bgShiftPatternHi;
        s.bgShiftAttrLo = bgShiftAttrLo; s.bgShiftAttrHi = bgShiftAttrHi;
        s.secondaryOam = secondaryOam.clone();
        s.evalAddr = evalAddr; s.evalCopyRemaining = evalCopyRemaining; s.evalSecCount = evalSecCount;
        s.evalSpritesScanned = evalSpritesScanned; s.evalLastReadValue = evalLastReadValue;
        s.evalTargetScanline = evalTargetScanline; s.evalActive = evalActive;
        s.evalStepsExecuted = evalStepsExecuted;
        s.octalLatch = octalLatch; s.ppuDataReadDelay = ppuDataReadDelay; s.ppuDataReadAddr = ppuDataReadAddr;
        s.pendingMaskValue = pendingMaskValue; s.pendingMaskDelay = pendingMaskDelay;
        s.pendingVValue = pendingVValue; s.pendingVDelay = pendingVDelay;
        s.warmupDotsRemaining = warmupDotsRemaining;
        return s;
    }

    public void restore(State s) {
        System.arraycopy(s.nametables, 0, nametables, 0, nametables.length);
        System.arraycopy(s.paletteRam, 0, paletteRam, 0, paletteRam.length);
        System.arraycopy(s.oam, 0, oam, 0, oam.length);
        ppuctrl = s.ppuctrl; ppumask = s.ppumask; ppustatus = s.ppustatus;
        oamaddr = s.oamaddr; oamdata = s.oamdata; ppuscroll = s.ppuscroll;
        ppuaddr = s.ppuaddr; ppudata = s.ppudata;
        addressLatch = s.addressLatch;
        v = s.v; t = s.t; fineX = s.fineX;
        readBuffer = s.readBuffer;
        ppuDataBus = s.ppuDataBus;
        ppuDataBusDecay = s.ppuDataBusDecay;
        scrollX = s.scrollX; scrollY = s.scrollY;
        cycle = s.cycle; scanline = s.scanline;
        lastFrameHadHit = s.lastFrameHadHit; hitOccurredThisFrame = s.hitOccurredThisFrame;
        nmiLine = s.nmiLine; nmiVblLevel = s.nmiVblLevel; suppressVblSet = s.suppressVblSet; oddFrame = s.oddFrame;
        vblankStarted = s.vblankStarted; pendingVblNmiEdge = s.pendingVblNmiEdge;
        System.arraycopy(s.sprShiftLo, 0, sprShiftLo, 0, sprShiftLo.length);
        System.arraycopy(s.sprShiftHi, 0, sprShiftHi, 0, sprShiftHi.length);
        System.arraycopy(s.sprAttrLatch, 0, sprAttrLatch, 0, sprAttrLatch.length);
        System.arraycopy(s.sprCounter, 0, sprCounter, 0, sprCounter.length);
        System.arraycopy(s.sprPixelOut, 0, sprPixelOut, 0, sprPixelOut.length);
        System.arraycopy(s.sprHalted, 0, sprHalted, 0, sprHalted.length);
        System.arraycopy(s.sprIsZero, 0, sprIsZero, 0, sprIsZero.length);
        rowShowBackground = s.rowShowBackground; rowShowSprites = s.rowShowSprites;
        rowShowLeftBackground = s.rowShowLeftBackground; rowShowLeftSprites = s.rowShowLeftSprites;
        rowBackdropPaletteIdx = s.rowBackdropPaletteIdx;
        bgNextTileId = s.bgNextTileId; bgNextAttr = s.bgNextAttr;
        bgNextLsb = s.bgNextLsb; bgNextMsb = s.bgNextMsb;
        bgShiftPatternLo = s.bgShiftPatternLo; bgShiftPatternHi = s.bgShiftPatternHi;
        bgShiftAttrLo = s.bgShiftAttrLo; bgShiftAttrHi = s.bgShiftAttrHi;
        System.arraycopy(s.secondaryOam, 0, secondaryOam, 0, secondaryOam.length);
        evalAddr = s.evalAddr; evalCopyRemaining = s.evalCopyRemaining; evalSecCount = s.evalSecCount;
        evalSpritesScanned = s.evalSpritesScanned; evalLastReadValue = s.evalLastReadValue;
        evalTargetScanline = s.evalTargetScanline; evalActive = s.evalActive;
        evalStepsExecuted = s.evalStepsExecuted;
        octalLatch = s.octalLatch; ppuDataReadDelay = s.ppuDataReadDelay; ppuDataReadAddr = s.ppuDataReadAddr;
        pendingMaskValue = s.pendingMaskValue; pendingMaskDelay = s.pendingMaskDelay;
        pendingVValue = s.pendingVValue; pendingVDelay = s.pendingVDelay;
        warmupDotsRemaining = s.warmupDotsRemaining;
    }

    //World-space (512x480) scroll X from v + fineX: bit 10 = which world half, coarse
    //X = 8px tile steps, fineX = sub-tile offset.
    //Standard loopy Y-increment, run once per rendering scanline at dot 256. Fine Y
    //counts 0-7; on wrap coarse Y advances; coarse Y wrapping past 29 flips the
    //vertical nametable half (bit 11) - values 30-31 are attribute-table rows that
    //wrap without flipping.
    private void incrementY() {
        if ((v & 0x7000) != 0x7000) {
            v += 0x1000; //fine Y += 1
        } else {
            v &= ~0x7000; //fine Y = 0
            int coarseY = (v & 0x03E0) >> 5;
            if (coarseY == 29) {
                coarseY = 0;
                v ^= 0x0800; //flip vertical nametable half
            } else if (coarseY == 31) {
                coarseY = 0; //wrap in attribute space without flipping halves
            } else {
                coarseY++;
            }
            v = (v & ~0x03E0) | (coarseY << 5);
        }
    }

    //Coarse-X companion to incrementY() - runs every 8 dots during the fetch cadence.
    //Wrapping past 31 flips the horizontal nametable half (bit 10).
    private void incrementScrollX() {
        if ((v & 0x001F) == 31) {
            v &= ~0x001F;
            v ^= 0x0400;
        } else {
            v++;
        }
    }

    //Shifts all 4 background shift registers left by 1, once per rendering dot.
    //Serial input is tied high (not low) on real hardware - AccuracyCoin "BG Serial
    //In" test 2 checks a shift brings in a 1, observable when rendering continues
    //without a fresh tile behind it.
    private void updateBgShifters() {
        bgShiftPatternLo = (bgShiftPatternLo << 1) | 1;
        bgShiftPatternHi = (bgShiftPatternHi << 1) | 1;
        bgShiftAttrLo = (bgShiftAttrLo << 1) & 0xFFFF;
        bgShiftAttrHi = (bgShiftAttrHi << 1) & 0xFFFF;
    }

    //Loads the just-fetched tile into the shift registers' low byte, every 8 dots.
    //Attribute shifters get a full all-0/all-1 byte per bit so the same per-dot mux
    //used for pattern data also works for palette selection.
    private void loadBgShifters() {
        bgShiftPatternLo = (bgShiftPatternLo & 0xFF00) | bgNextLsb;
        bgShiftPatternHi = (bgShiftPatternHi & 0xFF00) | bgNextMsb;
        bgShiftAttrLo = (bgShiftAttrLo & 0xFF00) | (((bgNextAttr & 0x01) != 0) ? 0xFF : 0x00);
        bgShiftAttrHi = (bgShiftAttrHi & 0xFF00) | (((bgNextAttr & 0x02) != 0) ? 0xFF : 0x00);
    }

    //Drives the real per-dot background fetch/shift pipeline, called from step() for
    //every dot of a rendering visible/pre-render scanline, before this dot's pixel is
    //composited (shift happens before sample, like real hardware).
    //
    //8-dot cadence over dots 1-256 (this scanline's 32 tiles) and 321-336 (prefetch of
    //next scanline's first 2 tiles): nametable byte (1-2), attribute byte (3-4),
    //pattern low (5-6), pattern high (7-8), shifters reloaded + coarse X incremented
    //at dot 8. Dots 337/339 do the two harmless dummy nametable fetches real hardware
    //performs at scanline end (result unused).
    //Each fetch is two dots: on the ALE dot the PPU drives the full address and an
    //external octal latch captures the low 8 bits (address/data share pins); on the
    //read dot only the upper 6 bits are re-driven and the byte comes back through that latch.
    //
    //Modelling this as one atomic "compute address, read it" hides what AccuracyCoin's
    //"Hybrid Addresses" and "ALE + Read" tests probe: a $2006 write landing between the
    //two dots splices a new high byte onto a stale latched low byte.
    private int fetchAleAddr(int phase) {
        switch (phase) {
            case 0: return 0x2000 | (v & 0x0FFF);
            case 1: return 0x23C0 | (v & 0x0C00) | ((v >> 4) & 0x38) | ((v >> 2) & 0x07);
            default: {
                int bgTableOffset = ((ppuctrl & 0x10) != 0) ? 0x1000 : 0x0000;
                int fineY = (v >> 12) & 0x07;
                return bgTableOffset + (bgNextTileId * 16) + fineY + (phase == 3 ? 8 : 0);
            }
        }
    }

    //One dot of the address bus: latches low byte on an ALE dot, or reads through the
    //latch on a read dot. bgFetchPhase says which of the four fetches this pair belongs to.
    //If a $2007 access asserts Read on this same dot, ALE and Read fight and the latch
    //keeps its old low byte instead of accepting the new one. The $2007 read is then
    //serviced through that stale byte, and the fetch's own read dot one dot later
    //addresses through it too - this is "ALE + Read": the low bit plane comes from an
    //address the fetch never asked for.
    private void aleDot(int phase) {
        if (ppuDataReadThisDot) {
            readBuffer = ppuRead((fetchAleAddr(phase) & 0x3F00) | octalLatch);
            ppuDataReadServiced = true;
            return;
        }
        octalLatch = fetchAleAddr(phase) & 0xFF;
    }

    private int readDot(int phase) {
        int addr = (fetchAleAddr(phase) & 0x3F00) | octalLatch;
        int data = ppuRead(addr) & 0xFF;
        //The read drives the shared pins, so the latch tracks the data byte until the
        //next ALE overwrites it - invisible in a normal cadence, decisive when ALE is suppressed.
        octalLatch = data;
        //If a $2007 state-machine read lands here without a simultaneous ALE, it just
        //rides along on whatever this fetch dot read - AccuracyCoin's "$2007 Stress
        //Test" documents this as the "stable read buffer" case.
        if (ppuDataReadThisDot) {
            readBuffer = (byte) data;
            ppuDataReadServiced = true;
        }
        return data;
    }

    //Sprite-fetch counterpart to aleDot/readDot - drives the same octal latch/ALE/Read
    //lines during dots 257-320, but the address doesn't come from bgNextTileId/v: the
    //first two dots of each slot are nametable reads through the current v (garbage -
    //real sprite tile ID comes from secondary OAM, but the bus is still driven and
    //observable to a $2007 read, per AccuracyCoin's "AT* reads are NT reads" note),
    //and the last two are the sprite pattern table's low/high address for that slot.
    //Takes a raw address since it's not one of fetchAleAddr's four cases.
    private void spriteAleDot(int addr) {
        if (ppuDataReadThisDot) {
            readBuffer = ppuRead((addr & 0x3F00) | octalLatch);
            ppuDataReadServiced = true;
            return;
        }
        octalLatch = addr & 0xFF;
    }

    private void spriteReadDot(int addr) {
        int a = (addr & 0x3F00) | octalLatch;
        int data = ppuRead(a) & 0xFF;
        octalLatch = data;
        //See readDot's comment - same shared pins, so a $2007 read landing here
        //without ALE rides along the same way.
        if (ppuDataReadThisDot) {
            readBuffer = (byte) data;
            ppuDataReadServiced = true;
        }
    }

    //Address bus value for a sprite slot's pattern-table dots (phases 4-7) - same math
    //fetchSpriteSlot uses to load the shift registers, computed independently since
    //this runs mid-slot (dots 261-264) while fetchSpriteSlot runs once at slot end.
    //An unused/out-of-range ($FF) slot still drives a real address on real hardware; row
    //is clamped rather than special-cased so this always returns a valid address.
    private int spritePatternFetchAddr(int slot, boolean highPlane) {
        int base = slot * 4;
        int y = secondaryOam[base] & 0xFF;
        int tileIndex = secondaryOam[base + 1] & 0xFF;
        int attr = secondaryOam[base + 2] & 0xFF;
        boolean tall = (ppuctrl & 0x20) != 0;
        int spriteHeight = tall ? 16 : 8;
        int spritePatternBase = ((ppuctrl & 0x08) != 0) ? 0x1000 : 0x0000;
        boolean flipV = (attr & 0x80) != 0;
        int patternTableOffset = tall ? (((tileIndex & 0x01) != 0) ? 0x1000 : 0x0000) : spritePatternBase;
        int tileNumber = tall ? (tileIndex & 0xFE) : tileIndex;
        int row = (scanline & 0xFF) - y;
        if (row < 0 || row >= spriteHeight) row = flipV ? 0 : spriteHeight - 1;
        int rowInTile = flipV ? (spriteHeight - 1 - row) : row;
        int tileOffset;
        if (tall) {
            int half = rowInTile / 8;
            int rowWithinHalf = rowInTile % 8;
            tileOffset = patternTableOffset + ((tileNumber + half) * 16) + rowWithinHalf;
        } else {
            tileOffset = patternTableOffset + (tileNumber * 16) + rowInTile;
        }
        return tileOffset + (highPlane ? 8 : 0);
    }

    //Drives the ALE/Read latch cadence for the sprite-fetch window (dots 257-320),
    //alongside bgFetchAndShiftStep - both compete for the same latch/Read hijack a
    //mistimed $2007 access can trigger (see spriteAleDot's comment).
    private void spriteFetchAleStep(int cycle) {
        if (cycle < 257 || cycle > 320) return;
        int slot = (cycle - 257) / 8;
        switch ((cycle - 257) % 8) {
            case 0: spriteAleDot(fetchAleAddr(0)); break;
            case 1: spriteReadDot(fetchAleAddr(0)); break;
            case 2: spriteAleDot(fetchAleAddr(0)); break;
            case 3: spriteReadDot(fetchAleAddr(0)); break;
            case 4: spriteAleDot(spritePatternFetchAddr(slot, false)); break;
            case 5: spriteReadDot(spritePatternFetchAddr(slot, false)); break;
            case 6: spriteAleDot(spritePatternFetchAddr(slot, true)); break;
            case 7: spriteReadDot(spritePatternFetchAddr(slot, true)); break;
        }
    }

    //Shifting, reloading and fetching each run on their own window rather than one
    //shared window - real hardware: fetches occupy 1-256/321-336 (first ALE at dot 1),
    //shifters run one dot behind (2-257/322-337), reload lands right after each group
    //completes (9,17,...257, plus 329/337). A single "dots 2-257" window used to cost
    //the fetch group its dot-1 ALE, leaving the scanline's first read using a stale latch.
    private void bgFetchAndShiftStep(int cycle) {
        if ((cycle >= 2 && cycle <= 257) || (cycle >= 322 && cycle <= 337)) updateBgShifters();
        if (((cycle - 1) % 8) == 0 && ((cycle >= 9 && cycle <= 257) || cycle == 329 || cycle == 337)) {
            loadBgShifters();
        }
        if ((cycle >= 1 && cycle <= 256) || (cycle >= 321 && cycle <= 336)) {
            switch ((cycle - 1) % 8) {
                case 0:
                    aleDot(0);
                    break;
                case 1:
                    bgNextTileId = readDot(0);
                    break;
                case 2:
                    aleDot(1);
                    break;
                case 3: {
                    int attr = readDot(1);
                    if ((v & 0x0040) != 0) attr >>= 4; //coarse Y bit 1 selects bottom half of attribute byte
                    if ((v & 0x0002) != 0) attr >>= 2; //coarse X bit 1 selects right half
                    bgNextAttr = attr & 0x03;
                    break;
                }
                case 4:
                    aleDot(2);
                    break;
                case 5:
                    bgNextLsb = readDot(2);
                    break;
                case 6:
                    aleDot(3);
                    break;
                case 7:
                    bgNextMsb = readDot(3);
                    incrementScrollX();
                    break;
            }
        } else if (cycle == 337 || cycle == 339) {
            //Harmless dummy nametable fetches at scanline end. Result discarded, but
            //they still drive ALE/address bus and mark the octal latch.
            aleDot(0);
        } else if (cycle == 338 || cycle == 340) {
            bgNextTileId = readDot(0);
        }
    }

    public int getWorldScrollX() {
        int base = ((v & 0x0400) != 0) ? 256 : 0;
        return (base + ((v & 0x001F) * 8) + fineX) & 0x1FF; //mod 512 (world width)
    }

    //Same idea vertically: bit 11 selects world half, coarse Y = tile steps, fine Y = sub-tile offset.
    public int getWorldScrollY() {
        int base = ((v & 0x0800) != 0) ? 240 : 0;
        int coarseY = (v >> 5) & 0x1F;
        int fineY = (v >> 12) & 0x07;
        return (base + coarseY * 8 + fineY) % 480; //mod 480 (world height)
    }

    //$2007 auto-increment after a CPU read/write. With rendering off it's the usual
    //+1/+32 (PPUCTRL bit 2). With rendering on during a visible/pre-render scanline,
    //$2007 doesn't own the address bus - the renderer does - so this instead runs the
    //renderer's own coarse-X and Y increments. AccuracyCoin "$2007 Read W/ Rendering"
    //test 2 checks the vertical increment happens here.
    private void incrementVramAddressAfterCpuAccess() {
        boolean renderingEnabled = (ppumask & 0x18) != 0;
        boolean visibleOrPrerender = scanline <= 239 || scanline == 261;
        if (renderingEnabled && visibleOrPrerender) {
            incrementScrollX();
            incrementY();
        } else {
            v += ((ppuctrl & 0x04) != 0) ? 32 : 1;
        }
        //v wraps at $8000 (15-bit), not $4000 - masking to $3FFF would clear fine-Y's
        //high bit on vertical carry, which "$2007 Read W/ Rendering" expects (v += $1001).
        v &= 0x7FFF;
    }

    public byte cpuRead(int address) {
        switch (address) {
            case 0x2002: //PPUSTATUS - only bits 5-7 are real; the rest are open bus
                //Bit 7 and bits 6/5 sample at different points in this CPU cycle (see
                //the M2 phase model at top of file). Bit 7 latches when M2 rises; bits
                //6/5 read off the flip-flops when M2 falls, ~2 dots later - so a read
                //straddling the pre-render clear can see VBlank still set while sprite
                //flags are already gone. AccuracyCoin "$2002 Flag Timing" test 1 answer
                //key ($E0/$80/$80/$00) only comes out right with this skew.
                int statusBits = ppustatus & 0xE0;
                if (dotsUntilPreRenderFlagClear() <= M2_FALL_LOOKAHEAD_DOTS) statusBits &= ~0x60;
                byte status = (byte) (statusBits | (ppuDataBus & 0x1F));
                if (DEBUG_SPRITE0) System.out.printf("  [read $2002 -> %02x] scanline=%d cycle=%d%n", status & 0xFF, scanline, cycle);
                ppustatus &= ~0x80; //Clear VBlank bit on read
                nmiVblLevel = false;
                if (scanline == 241 && cycle == 0) suppressVblSet = true; //see suppressVblSet's declaration
                if (scanline == 241 && cycle == 1 && cpu != null) cpu.cancelPendingNMI(); //read races the flag-set tick: flag stands, but NMI is suppressed
                updateNmiLine(); //a read-cleared VBlank flag can also drop the NMI line
                addressLatch = false; //Reset address latch
                //Only bits 5-7 are driven by this read, so the decay clock isn't reset -
                //the lower 5 bits keep aging normally.
                ppuDataBus = status & 0xFF;
                return status;

            case 0x2004: { //OAMDATA
                //See oamBusValueAtCycle()/OAM_READ_LOOKAHEAD_DOTS: the CPU actually
                //samples a few dots further into this read than `cycle` shows here.
                int oamVal = oamBusValueAtCycle(OAM_READ_LOOKAHEAD_DOTS);
                ppuDataBus = oamVal;
                ppuDataBusDecay = PPU_BUS_DECAY_TICKS;
                return (byte) ppuDataBus;
            }

            case 0x2007: { //PPUDATA
                flushPendingV();
                clockA12(v);
                byte data = readBuffer;
                //Read buffer always loads from what a $1000-lower address would read
                //(the underlying nametable byte) - palette RAM has no such buffer; only
                //the CPU-visible read is unbuffered there.
                boolean delayedBuffer = (ppumask & 0x18) != 0 && (scanline <= 239 || scanline == 261);
                if (delayedBuffer) {
                    ppuDataReadAddr = v;
                    ppuDataReadDelay = PPUDATA_READ_DELAY_DOTS; //see PPUDATA_READ_DELAY_DOTS
                } else {
                    readBuffer = (v >= 0x3F00) ? (byte) nametables[mapNametableAddress((v - 0x1000) & 0x2FFF)] : ppuRead(v);
                }

                if (v >= 0x3F00) {
                    //Palette reads are unbuffered, upper 2 bits open bus. Greyscale
                    //(PPUMASK bit 0) masks the value on the read path without touching
                    //what's stored - see AccuracyCoin "Palette RAM Quirks" tests 6/7.
                    int pal = ppuRead(v) & 0x3F;
                    if ((ppumask & 0x01) != 0) pal &= 0x30;
                    data = (byte) (pal | (ppuDataBus & 0xC0));
                }

                //The renderer-driven increment (see incrementVramAddressAfterCpuAccess)
                //also rides the same delayed timing as the read-buffer fill, so
                //intervening fetch dots during the delay still see the old v. See ppuDataIncPending.
                if (delayedBuffer) {
                    ppuDataIncPending = true;
                } else {
                    incrementVramAddressAfterCpuAccess();
                }
                clockA12(v); //v drives the address bus continuously, so the increment itself can pulse A12
                ppuDataBus = data & 0xFF;
                ppuDataBusDecay = PPU_BUS_DECAY_TICKS;
                return data;
            }

            default: //Write-only registers ($2000/$2001/$2003/$2005/$2006) are open bus
                return (byte) ppuDataBus;
        }
    }

    public void cpuWrite(int address, byte data) {
        int val = data & 0xFF;
        ppuDataBus = val;
        ppuDataBusDecay = PPU_BUS_DECAY_TICKS; //every write fully drives (and re-charges) the bus
        switch (address) {
            case 0x2000: //PPUCTRL
                if (warmupDotsRemaining > 0) break; //ignored during power-on warm-up
                ppuctrl = val;
                t = (t & 0xF3FF) | ((val & 0x03) << 10); //nametable-select bits into t
                updateNmiLine(); //toggling bit 7 can raise (or drop) the NMI line immediately
                if (DEBUG_SPRITE0) System.out.printf("  [write $2000=%02x] scanline=%d cycle=%d%n", val, scanline, cycle);
                break;
            case 0x2001: //PPUMASK - not applied immediately; see PPUMASK_WRITE_DELAY_DOTS
                if (warmupDotsRemaining > 0) break; //ignored during power-on warm-up
                pendingMaskValue = val;
                pendingMaskDelay = PPUMASK_WRITE_DELAY_DOTS;
                break;
            case 0x2003: //OAMADDR
                oamaddr = val;
                break;
            case 0x2004: { //OAMDATA
                boolean renderingEnabled = (ppumask & 0x18) != 0;
                boolean visibleOrPrerender = scanline <= 239 || scanline == 261;
                if (renderingEnabled && visibleOrPrerender) {
                    //While rendering, sprite evaluation owns OAM, so a $2004 write never
                    //reaches it - it only bumps OAMADDR by 4 then realigns to a sprite
                    //boundary. AccuracyCoin "Address $2004 Behavior" tests 6/7/A.
                    oamaddr = ((oamaddr + 4) & 0xFF) & 0xFC;
                } else {
                    oam[oamaddr & 0xFF] = data;
                    oamaddr = (oamaddr + 1) & 0xFF;
                }
                break;
            }
            case 0x2005: //PPUSCROLL
                if (warmupDotsRemaining > 0) break; //ignored during power-on warm-up
                if (!addressLatch) {
                    scrollX = val;
                    t = (t & 0xFFE0) | ((val >> 3) & 0x1F); //coarse X
                    fineX = val & 0x07;
                    addressLatch = true;
                    if (DEBUG_SPRITE0) System.out.printf("  [write $2005 X=%02x] scanline=%d cycle=%d%n", val, scanline, cycle);
                } else {
                    scrollY = val;
                    t = (t & 0x8FFF) | ((val & 0x07) << 12); //fine Y
                    t = (t & 0xFC1F) | ((val & 0xF8) << 2);  //coarse Y
                    addressLatch = false;
                }
                break;
            case 0x2006: //PPUADDR
                if (warmupDotsRemaining > 0) break; //ignored during power-on warm-up
                flushPendingV();
                if (!addressLatch) {
                    t = (t & 0x00FF) | ((val & 0x3F) << 8);
                    addressLatch = true;
                } else {
                    t = (t & 0x7F00) | val;
                    //The second $2006 write copies t into v a few dots after the CPU
                    //issues it. During the fetch cadence a fetch's two dots straddle
                    //this: ALE latches the low byte from old v, and the read dot
                    //re-derives only the upper 6 bits from new v. AccuracyCoin "Hybrid
                    //Addresses" aims a write at that gap and checks the spliced address.
                    if ((ppumask & 0x18) != 0 && (scanline <= 239 || scanline == 261)) {
                        pendingVValue = t;
                        pendingVDelay = PPUADDR_WRITE_DELAY_DOTS;
                    } else {
                        v = t;
                        clockA12(v);
                    }
                    addressLatch = false;
                }
                break;
            case 0x2007: //PPUDATA
                flushPendingV();
                clockA12(v);
                ppuWrite(v, data);
                incrementVramAddressAfterCpuAccess();
                clockA12(v); //v drives the address bus continuously, so the increment itself can pulse A12
                break;
        }
    }

    //Non-mutating register peek for the memory inspector; skips the read side-effects
    //(VBlank clear, latch reset, auto-increment) real cpuRead triggers.
    public byte debugRead(int address) {
        switch (address & 0x0007) {
            case 0: return (byte) ppuctrl;
            case 1: return (byte) ppumask;
            case 2: return (byte) ppustatus;
            case 3: return (byte) oamaddr;
            case 4: return oam[oamaddr & 0xFF];
            case 5: return (byte) ppuscroll;
            case 6: return (byte) ppuaddr;
            case 7: return readBuffer;
            default: return 0;
        }
    }

    //Folds a $2000-$3EFF nametable address onto one of the 4 physical 1KB slots, per
    //the cartridge's mirroring mode - see Cartridge.MirrorMode. Vertical mirroring ties
    //$2000=$2800, $2400=$2C00 (horizontal-scrolling games); horizontal mirroring ties
    //$2000=$2400, $2800=$2C00 (vertical-scrolling games); single-screen pins all 4 to
    //one table; four-screen keeps all 4 distinct (cartridge supplies the extra 2KB,
    //modeled as the rest of this array).
    private int physicalNametable(int logicalTable) {
        switch (bus.getMirrorMode()) {
            case HORIZONTAL: return (logicalTable >> 1) & 0x01;
            case SINGLE_SCREEN_LOW: return 0;
            case SINGLE_SCREEN_HIGH: return 1;
            case FOUR_SCREEN: return logicalTable;
            case VERTICAL:
            default: return logicalTable & 0x01;
        }
    }

    public byte debugPeekNametable(int table, int offset) {
        return nametables[physicalNametable(table) * 1024 + (offset & 0x3FF)];
    }

    private int mapNametableAddress(int address) {
        int logicalTable = (address >> 10) & 0x03;
        int within = address & 0x03FF;
        return physicalNametable(logicalTable) * 1024 + within;
    }

    //Debug-only: formats every non-empty OAM entry (Y < $EF) as index/Y/tile/attr/X
    //for the Debug menu's "Dump OAM" item.
    public String dumpOamText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            int y = oam[i * 4] & 0xFF, tile = oam[i * 4 + 1] & 0xFF, attr = oam[i * 4 + 2] & 0xFF, x = oam[i * 4 + 3] & 0xFF;
            if (y >= 0xEF) continue;
            sb.append(String.format("spr[%2d] y=%3d tile=$%02X attr=$%02X (behindBG=%b flipH=%b flipV=%b pal=%d) x=%3d%n",
                    i, y, tile, attr, (attr & 0x20) != 0, (attr & 0x40) != 0, (attr & 0x80) != 0, attr & 0x03, x));
        }
        return sb.toString();
    }

    public byte ppuRead(int address) {
        address &= 0x3FFF;
        if (address <= 0x1FFF) {
            return bus.ppuReadCartridge(address);
        }
        if (address <= 0x3EFF) {
            return nametables[mapNametableAddress(address & 0x2FFF)];
        } else if (address >= 0x3F00 && address <= 0x3FFF) {
            int paletteAddr = address & 0x001F;
            if ((paletteAddr & 0x03) == 0) paletteAddr &= 0x000F; //Mirror base background colors
            return paletteRam[paletteAddr];
        }
        return 0;
    }

    public void ppuWrite(int address, byte data) {
        address &= 0x3FFF;
        if (address <= 0x1FFF) {
            //CHR space, not nametable space - must return here, or falling through
            //would also write nametable RAM, corrupting it whenever a $2007 write
            //targets CHR space (e.g. PPUADDR wrapping past $3FFF back to $0000-$1FFF).
            bus.ppuWriteCartridge(address, data);
            return;
        }
        if (address <= 0x3EFF) {
            nametables[mapNametableAddress(address & 0x2FFF)] = data;
        } else if (address >= 0x3F00 && address <= 0x3FFF) {
            int paletteAddr = address & 0x001F;
            if ((paletteAddr & 0x03) == 0) paletteAddr &= 0x000F;
            paletteRam[paletteAddr] = data;
        }
    }

    //Advances PPU clock by 1 tick (3 per CPU step, stepped 1:1 - see
    //NESEmulator.updateEmulationFrame), so nothing "later" in the frame has happened yet.
    //
    //Pixels are produced one dot at a time (cycle 1 = x=0 ... cycle 256 = x=255),
    //matching real hardware, rather than a whole scanline at once - needed for
    //sprite-0-hit: games like SMB time a mid-frame scroll write off exactly when the
    //hit fires (splitting the status bar from the playfield). Setting the hit
    //instantly at scanline start instead of its real x-position desyncs the split permanently.
    //
    //The v/t loopy copies below (cycle 257 horizontal, pre-render 280-304 vertical)
    //are what make that split work: a mid-scanline $2005/$2000 write only lands in t,
    //so it can't retroactively affect the row being drawn.
    private boolean pendingVblNmiEdge = false;

    //A $2001 write doesn't reach rendering on the CPU's issuing dot - the 6502
    //finishes its cycle first, then the PPU takes a couple more dots to propagate the
    //mask. AccuracyCoin "BG Serial In" and several "Stale" tests are built on this lag;
    //applying the write instantly made every such write land early.
    //4 dots: smallest value matching all known AccuracyCoin $2001-timing tests without
    //breaking previously-passing ones. Absorbs the rest of the CPU cycle plus the PPU's ~2-dot propagation.
    private static final int PPUMASK_WRITE_DELAY_DOTS = 4;
    private int pendingMaskValue = 0;
    private int pendingMaskDelay = 0;

    //Companion lag for the second $2006 write - see that case in cpuWrite().
    //3 dots: the remainder of the CPU cycle that issued the write.
    private static final int PPUADDR_WRITE_DELAY_DOTS = 3;
    private int pendingVValue = 0;
    private int pendingVDelay = 0;

    //Flushes an in-flight v write so any other v-dependent access sees it immediately;
    //the lag stays visible only to the fetch cadence it models.
    private void flushPendingV() {
        if (pendingVDelay > 0) {
            pendingVDelay = 0;
            v = pendingVValue;
            clockA12(v);
        }
    }

    private void applyPpuMask(int val) {
        boolean wasRendering = (ppumask & 0x18) != 0;
        ppumask = val;
        boolean nowRendering = (ppumask & 0x18) != 0;
        //Turning rendering off mid-scanline during sprite evaluation leaves the OAM
        //datapath mid-transfer. The current secondary-OAM address is latched as a
        //seed; the actual corruption happens later, once rendering resumes on a
        //visible/pre-render line - see AccuracyCoin "OAM Corruption" tests 2-4.
        if (wasRendering && !nowRendering && (scanline <= 239 || scanline == 261)) {
            oamCorruptionSeed = currentSecondaryOamAddr();
            oamCorruptionPending = true;
        }
    }

    public void step() {
        cycle++;

        if (warmupDotsRemaining > 0) warmupDotsRemaining--;

        if (pendingMaskDelay > 0 && --pendingMaskDelay == 0) applyPpuMask(pendingMaskValue);
        if (pendingVDelay > 0 && --pendingVDelay == 0) { v = pendingVValue; clockA12(v); }

        ppuDataReadThisDot = ppuDataReadDelay > 0 && --ppuDataReadDelay == 0;
        ppuDataReadServiced = false;

        if (ppuDataBusDecay > 0) {
            ppuDataBusDecay--;
            if (ppuDataBusDecay == 0) ppuDataBus = 0;
        }

        if (pendingVblNmiEdge) {
            pendingVblNmiEdge = false;
            updateNmiLine();
        }

        boolean renderingEnabled = (ppumask & 0x18) != 0;
        boolean visibleOrPrerender = scanline <= 239 || scanline == 261;

        maybePerformOamCorruption();

        //Runs before renderPixel below (shift-then-sample, matching real hardware) so
        //this dot's compositing sees the freshly-shifted state. Gated on
        //renderingEnabled (bg OR sprites), matching AccuracyCoin "Rendering Flag
        //Behavior" test 2 (shifters still run with only sprites shown).
        if (renderingEnabled && visibleOrPrerender) {
            bgFetchAndShiftStep(cycle);
            spriteFetchAleStep(cycle);
        }
        //If Read asserted on a dot the fetch cadence wasn't driving ALE on, nothing
        //hijacked it, so the buffer fills from the address actually requested.
        if (ppuDataReadThisDot && !ppuDataReadServiced) {
            int a = ppuDataReadAddr;
            readBuffer = (a >= 0x3F00) ? (byte) nametables[mapNametableAddress((a - 0x1000) & 0x2FFF)] : ppuRead(a);
        }
        if (ppuDataReadThisDot && ppuDataIncPending) {
            ppuDataIncPending = false;
            incrementVramAddressAfterCpuAccess();
        }
        //Clocked on pre-render too (state machines don't know which scanline they're
        //on) and with rendering off (free-running counters) - see clockSpriteUnits().
        if (visibleOrPrerender && cycle >= 1 && cycle <= 256) {
            clockSpriteUnits(renderingEnabled);
        } else {
            java.util.Arrays.fill(sprPixelOut, 0);
        }

        if (scanline <= 239) {
            if (cycle == 1) {
                prepareScanline(scanline);
            }
            if (cycle >= 1 && cycle <= 256) {
                renderPixel(scanline, cycle - 1);
            }
        }

        //v's Y bits increment scanline-by-scanline here (incrementY(), cycle 256),
        //matching real hardware - see incrementY() for why that matters for mid-frame
        //Y-scroll writes. The cycle-257 horizontal copy lets a mid-scanline X-scroll
        //write (e.g. SMB status-bar split) take effect next scanline; the pre-render
        //vertical copy re-establishes the per-frame Y baseline after VBlank housekeeping.
        if (renderingEnabled && visibleOrPrerender) {
            //Doing the real per-scanline v increment (instead of adding the scanline
            //number to whatever Y was last read) is what makes a mid-frame Y-scroll
            //write (e.g. SMB3 title screen split) land correctly.
            if (cycle == 256) incrementY();
            if (cycle == 257) {
                v = (v & ~0x041F) | (t & 0x041F); //horizontal bits: coarse X + NT-select X
            }
            if (scanline == 261 && cycle >= 280 && cycle <= 304) {
                v = (v & ~0x7BE0) | (t & 0x7BE0); //vertical bits: fine/coarse Y + NT-select Y
            }
            //MMC3's IRQ counter clocks on A12 rising edges from pattern-table fetch
            //addresses crossing into $1000-$1FFF. This PPU renders per-pixel, not
            //per-fetch, but the two points where the fetch address's pattern-table
            //half can change are known: sprite fetches for the next scanline (~dot
            //257, ppuctrl bit 3) and the next scanline's BG prefetch (~dot 321,
            //ppuctrl bit 4). Routing both through clockA12() only counts a rising edge
            //when the half actually changes, like real hardware.
            if (cycle == 257) {
                boolean newRenderA12 = ((ppuctrl & 0x08) != 0);
                if (newRenderA12 && !renderA12 && bus != null) bus.clockMapperIRQ();
                renderA12 = newRenderA12;
            }
            if (cycle == 321) {
                boolean newRenderA12 = ((ppuctrl & 0x10) != 0);
                if (newRenderA12 && !renderA12 && bus != null) bus.clockMapperIRQ();
                renderA12 = newRenderA12;
            }

            //Real dot-by-dot sprite evaluation (secondary OAM build, cycles 65-256) and
            //fetch (cycles 257-320) for the next scanline - see startSpriteEvaluation/
            //stepSpriteEvaluation/fetchSpriteSlot. Runs whenever bg OR sprites are
            //enabled - AccuracyCoin "Rendering Flag Behavior" test 3 and "Sprite
            //Overflow Behavior" test 4 depend on evaluation still happening with only bg enabled.
            //A $2004 read's lookahead (oamBusValueAtCycle) may already have started
            //this scanline's evaluation a dot early - only start here if not.
            if (cycle == 65 && evalStartedForScanline != scanline) {
                startSpriteEvaluation(scanline);
                evalStartedForScanline = scanline;
            }
            if (cycle >= 65 && cycle <= 255 && (cycle & 1) == 1) {
                //oamBusValueAtCycle() may have already run this step early on behalf of
                //a $2004 read - see evalStepsExecuted's comment.
                int expectedSteps = (cycle - 65) / 2 + 1;
                if (evalStepsExecuted < expectedSteps) {
                    stepSpriteEvaluation();
                    evalStepsExecuted++;
                }
            }
            //OAMADDR reads back as 0 continuously during the sprite-fetch window -
            //real hardware quirk AccuracyCoin's OAM-address tests rely on.
            if (cycle >= 257 && cycle <= 320) {
                oamaddr = 0;
                if (((cycle - 257) % 8) == 7) fetchSpriteSlot((cycle - 257) / 8);
            }
            //Dot 339 releases the sprite units back to "counting" for the next
            //scanline; if rendering is off here, a halted unit paints immediately once
            //rendering returns - see sprHalted's declaration.
            if (cycle == 339) java.util.Arrays.fill(sprHalted, false);
        }

        //Odd-frame dot skip: with rendering on, every other frame's pre-render line is
        //one dot short (dot 339 is last, jumps to dot 0 of scanline 0).
        //Sync_ToPreRenderDot324 (used by several AccuracyCoin tests) detects frame
        //parity, so without this the CPU/PPU alignment those tests expect is off by one dot.
        boolean skipLastDot = scanline == 261 && cycle == 340 && oddFrame && renderingEnabled;

        if (cycle >= 341 || skipLastDot) {
            cycle = 0;
            scanline++;
            if (scanline >= 262) {
                scanline = 0;
                oddFrame = !oddFrame;
            }
            //nmiVblLevel drops 1 dot before the CPU-visible $2002 bit - here, at
            //scanline 261 dot 0, one dot ahead of the dot-1 clear below.
            if (scanline == 261) {
                nmiVblLevel = false;
                updateNmiLine();
                //$2002 flags themselves are NOT cleared here - all three (VBlank,
                //sprite 0 hit, overflow) clear together on dot 1 below.
                //AccuracyCoin "$2002 Flag Timing" test 1 confirms one shared clear
                //point; the apparent skew in reads comes from M2 sampling skew in
                //cpuRead's $2002 case, not separate clear dots.
            }
        }

        //Trigger VBlank at Scanline 241, Cycle 1
        if (scanline == 241 && cycle == 1) {
            vblankStarted = true; //still marks "one frame elapsed" even when suppressed
            lastFrameHadHit = hitOccurredThisFrame;
            hitOccurredThisFrame = false;
            if (!suppressVblSet) {
                ppustatus |= 0x80; //Set VBlank flag
                nmiVblLevel = true;
                pendingVblNmiEdge = true; //NMI-line's own rising edge lags the $2002 flag by 1 dot
            }
            suppressVblSet = false;
        }

        if (scanline == 261 && cycle == 1) {
            ppustatus &= ~0x80;
            ppustatus &= ~0x40; //Clear sprite-0-hit here (not at VBlank start) - clearing
                                 //early hid it from the game before it could read it during
                                 //VBlank, and let a stale hit bleed into the next frame
                                 //(AccuracyCoin Sprite 0 Hit Behavior test C).
            ppustatus &= ~0x20; //Clear sprite overflow here too; previously only ever set,
                                 //never cleared, causing polls for it (e.g.
                                 //Sync_ToSpriteFlagsClearing) to spin forever.
        }
    }

    private static final int MAX_ROW_SPRITES = 8;

    //Real 2C02 sprite output: eight independent units, each with two 8-bit pattern
    //shift registers, a latched attribute byte, and a down-counter preloaded with X.
    //No "compare beam x against sprite x" exists in hardware - AccuracyCoin "Stale
    //Sprite Shift Regs" probes exactly this: toggling rendering off/on resumes a
    //half-drained shifter wherever the beam now is, which a positional compare can't reproduce.
    //
    //sprHalted is separate from "counter == 0": a unit counts down toward X, or is
    //halted (actively shifting its 8 pixels). Dot 339 of every rendering scanline
    //forces all units back to "counting"; if rendering is off then, they stay halted
    //and paint the instant rendering returns, regardless of X. Tests 5/6 of "Stale
    //Sprite Shift Regs" are built around that one dot.
    private final int[] sprShiftLo = new int[MAX_ROW_SPRITES];
    private final int[] sprShiftHi = new int[MAX_ROW_SPRITES];
    private final int[] sprAttrLatch = new int[MAX_ROW_SPRITES];
    private final int[] sprCounter = new int[MAX_ROW_SPRITES];
    private final boolean[] sprHalted = new boolean[MAX_ROW_SPRITES];
    private final boolean[] sprIsZero = new boolean[MAX_ROW_SPRITES];
    //This dot's output from each unit, produced by clockSpriteUnits() before
    //renderPixel composites it - kept separate since the registers have already
    //advanced by the time the pixel is resolved.
    private final int[] sprPixelOut = new int[MAX_ROW_SPRITES];

    //Real 2C02 secondary OAM (8x4 bytes) and the evaluation state machine filling it,
    //run dot-by-dot across cycles 65-256 (see stepSpriteEvaluation, called from
    //step()). This replaces a simpler "evaluate the whole row at once" approach so
    //that: (a) $2004 reads during 65-256 reflect the real in-progress buffer, (b) the
    //sprite-overflow "diagonal" bug is reproduced, and (c) OAMADDR misalignment quirks
    //are modeled instead of assuming a clean 4-byte boundary.
    //
    //Real chip has exactly one evaluation pointer - evalAddr - decided fresh every
    //step, rather than separate sprite/byte indices. evalCopyRemaining tracks bytes
    //left in the sprite currently being copied (0 = next byte is a fresh Y decision) -
    //this is what lets a misaligned starting OAMADDR "stay misaligned" through a copy
    //and self-realign on the next out-of-range decision (AccuracyCoin "Misaligned OAM Behavior").
    private final byte[] secondaryOam = new byte[32];
    private int evalAddr;
    private int evalCopyRemaining;
    private int evalSecCount;       //0-8: sprites committed into secondaryOam so far this evaluation
    private int evalSpritesScanned; //safety cutoff once all 64 primary sprites are decided
    private int evalLastReadValue;  //most recently read (attribute-masked) OAM byte - what $2004 reflects during 65-256
    private int evalTargetScanline; //scanline this evaluation is FOR (next scanline, or 0 during pre-render)
    private boolean evalActive;

    //"Sprite zero" is set only if the VERY FIRST object evaluated this scanline
    //(decided at cycle 66) was in range - not "whatever landed in slot 0". If that
    //first object is out of range, slot 0 is NOT sprite zero. A $2003 write before
    //evaluation starts can make a non-zero OAM index become "sprite zero" instead.
    //See AccuracyCoin "Arbitrary Sprite Zero" tests 1-2.
    private boolean evalFirstStep;
    private boolean evalSpriteZeroInRange;

    private boolean rowShowBackground;
    private boolean rowShowSprites;
    private boolean rowShowLeftBackground;
    private boolean rowShowLeftSprites;
    private int rowBackdropPaletteIdx;
    //Real 2C02 background pipeline: two 16-bit pattern shift registers and two 16-bit
    //attribute shift registers (each bit pre-expanded to all-0/all-1), shifted once
    //per dot (updateBgShifters) and reloaded every 8 dots (loadBgShifters). bgNext*
    //latches hold the tile currently being fetched until reload. Replaces the old
    //"cache a tile every 8 pixels" approximation with the real per-dot state machine -
    //what several AccuracyCoin tests (t Register Quirks, Stale BG Shift Registers, BG
    //Serial In, $2007 Stress Test, ALE + Read, Hybrid Addresses) directly probe.
    //Fetches still go through ppuRead/PPUCTRL, so MMC3 mid-scanline CHR/pattern-table
    //switches (SMB3 status bar) are still picked up per-tile, now at their real dot.
    private int bgNextTileId, bgNextAttr, bgNextLsb, bgNextMsb;
    private int bgShiftPatternLo, bgShiftPatternHi;
    private int bgShiftAttrLo, bgShiftAttrHi;

    //Latches this scanline's render state and evaluates sprites for the row up front
    //at cycle 1 (real hardware does this during the PREVIOUS scanline's 65-256, but
    //since nothing here reads OAM/PPUCTRL mid-evaluation, doing it up front is
    //equivalent and simpler). Pixel output and sprite-0-hit happen later in renderPixel.
    private void prepareScanline(int sy) {
        if (DEBUG_SPRITE0 && sy <= 35) {
            System.out.printf("prepareScanline sy=%d ppuctrl=%02x scrollX=%d scrollY=%d%n", sy, ppuctrl, scrollX, scrollY);
        }
        rowShowBackground = (ppumask & 0x08) != 0;
        rowShowSprites = (ppumask & 0x10) != 0;
        rowShowLeftBackground = (ppumask & 0x02) != 0;
        rowShowLeftSprites = (ppumask & 0x04) != 0;

        rowBackdropPaletteIdx = ppuRead(0x3F00) & 0x3F;
        //No sprite state copied here any more - the eight output units carry their
        //own state across scanline boundaries (see sprHalted), so re-seeding them
        //would destroy the staleness "Stale Sprite Shift Regs"/"Sprites On Scanline 0" test for.
    }

    //Starts a fresh sprite evaluation for the scanline after `sy` (or 0, if sy is
    //pre-render) - called at cycle 65. evalAddr begins at the CPU's current OAMADDR
    //(not forced to a 4-byte boundary) - see evalAddr's field comment.
    private void startSpriteEvaluation(int sy) {
        java.util.Arrays.fill(secondaryOam, (byte) 0xFF);
        evalAddr = oamaddr & 0xFF;
        evalCopyRemaining = 0;
        evalSecCount = 0;
        evalSpritesScanned = 0;
        evalStepsExecuted = 0;
        evalAddrTotalAdvance = 0;
        evalSecWriteFrozen = false;
        evalOverflowInitialized = false;
        evalOverflowState = 0;
        evalOverflowFreezeStep = 0;
        evalOverflowNWrapped = false;
        evalTargetScanline = (sy == 261) ? 0 : sy + 1;
        evalActive = true;
        evalFirstStep = true;
        evalSpriteZeroInRange = false;
    }

    //How many of the 96 evaluation steps (odd cycles 65-255) have run this scanline.
    //Normally trails `cycle` naturally, but oamBusValueAtCycle() can run steps early
    //(to answer "what would $2004 return a few dots from now"), so this counter stops
    //the real per-dot driver from double-running an already-executed step.
    private int evalStepsExecuted;

    //Which scanline startSpriteEvaluation has already run for - lets both the real
    //per-dot driver (cycle==65) and oamBusValueAtCycle's one-dot lookahead start a
    //scanline's evaluation exactly once, whichever gets there first.
    private int evalStartedForScanline = -1;

    //$2004's true read latency: the CPU's read cycle begins 1 dot after `cycle`, and
    //OAMDATA has no buffering (unlike $2007) - so the value sampled is a few dots
    //further along than `cycle` shows here. AccuracyCoin "$2004 Stress Test" reads
    //every dot of a scanline against a precomputed table that only lines up with this lookahead.
    private static final int OAM_READ_LOOKAHEAD_DOTS = 1;

    //Answers "what would $2004 return `aheadDots` dots from now" without side effects
    //a real read wouldn't have - except evaluation steps within the lookahead window
    //are actually executed early (see evalStepsExecuted), since they'll happen regardless.
    private int oamBusValueAtCycle(int aheadDots) {
        int target = cycle + aheadDots;
        int targetScanline = scanline;
        if (target > 340) {
            target -= 341;
            targetScanline++;
            if (targetScanline >= 262) targetScanline = 0;
        }
        boolean targetVisibleOrPrerender = targetScanline <= 239 || targetScanline == 261;
        boolean renderingEnabled = (ppumask & 0x18) != 0;
        if (!renderingEnabled || !targetVisibleOrPrerender || target < 0 || target > 340) {
            return plainOamRead();
        }
        if (target == 0) {
            //Dot 0: secondary OAM hasn't been reset yet (happens at dot 65), so the
            //buffer still shows slot 0's byte from two scanlines ago's fetch phase.
            return secondaryOam[0] & 0xFF;
        }
        //Dots 1-64: secondary-OAM clear (32 steps, 2 dots each writing $FF), bus shows
        //that constant. Dot 65 is step 1's own first dot, already run by the real
        //per-dot driver by the time it's sampled - only looked like part of the clear
        //phase before because that scenario's OAM[0] happened to be $FF (key 2, OAM[0]=$80, exposes the difference).
        if (target >= 1 && target <= 64) return 0xFF; //secondary-OAM clear glitch
        if (target >= 65 && target <= 256) {
            //target==65 is the only way this can run before the real driver's own
            //cycle==65 handler starts evaluation for this scanline - without this
            //guard, steps below would use evalAddr/evalStepsExecuted left over from
            //the previous scanline instead of a fresh reset.
            if (targetScanline == scanline && evalStartedForScanline != scanline) {
                startSpriteEvaluation(scanline);
                evalStartedForScanline = scanline;
            }
            //Run any steps the real driver hasn't reached yet, then credit them so it
            //doesn't repeat the work.
            int expectedSteps = Math.min(96, (Math.min(target, 255) - 65) / 2 + 1);
            while (evalStepsExecuted < expectedSteps) {
                stepSpriteEvaluation();
                evalStepsExecuted++;
            }
            //Once the pointer has lapped OAM within one scanline's window
            //(evalAddrTotalAdvance >= 256), evaluation and secondary-OAM-write fall
            //out of sync: a second read mid-step no longer echoes evalLastReadValue -
            //it instead reads secondary OAM's own current write address.
            //AccuracyCoin's "$2004 Stress Test" answer key (section 6) documents this:
            //"value, value" pairs become "value, <secondary-OAM byte>" once lapped.
            //After the pointer laps, the first dot of a step's pair (odd target,
            //freshly-executed step) still shows the real comparator read; the second
            //dot (even target, no new step) instead reads secondary OAM's write address.
            //The same fall-out-of-sync applies once 8 sprites are committed
            //(overflow-scan phase): secondary OAM's write pointer is done moving
            //(evalSecCount stays 8), so its address/byte are fixed for the rest of the
            //scanline even as the comparator keeps reading fresh bytes. AccuracyCoin
            //answer key 2 (more-than-8-in-range) shows a constant byte on every second
            //dot once the 8 real sprites are found.
            //evalSecCount is bumped to 8 the moment the 8th sprite is CONFIRMED
            //in-range, but its remaining 3 bytes are still a normal copy for a few more
            //steps - checking evalCopyRemaining>0 avoids freezing 4 steps too early
            //while those bytes are still being copied (AccuracyCoin key-2 table).
            if ((evalSecWriteFrozen || evalWasOverflowScanRead) && !evalLastStepWasMidCopy && (target & 1) == 0) {
                return secondaryOam[(evalSecCount * 4) & 0x1F] & 0xFF;
            }
            return evalLastReadValue;
        }
        if (target >= 257 && target <= 320) {
            int slot = (target - 257) / 8;
            int into = (target - 257) % 8;
            return secondaryOam[(slot * 4 + Math.min(3, into)) & 0x1F] & 0xFF;
        }
        //Dots 321-340: past the last fetch slot, address bus settles back on secondary
        //OAM's slot-0 byte (mirrors target==0 above) rather than primary OAM -
        //AccuracyCoin calls these "20 instances of $7F (index 0 into OAM2)".
        if (target >= 321 && target <= 340) {
            return secondaryOam[0] & 0xFF;
        }
        return plainOamRead();
    }

    //Attribute byte (3rd of each 4-byte entry) only physically stores bits 7-6-5
    //(flip V/H, priority) and 1-0 (palette) - bits 4-3-2 don't exist and read as 0.
    private int plainOamRead() {
        int val = oam[oamaddr & 0xFF] & 0xFF;
        if ((oamaddr & 0x03) == 2) val &= 0xE3;
        return val;
    }

    //Runs one 2-dot evaluation step (one primary-OAM byte decided), called on every
    //odd cycle 65-255 (96 steps total). Implements the real algorithm off a single
    //evalAddr pointer: an in-range sprite's remaining 3 bytes are copied by walking
    //evalAddr forward (which is what keeps a misaligned start misaligned through the
    //copy), an out-of-range Y realigns to the next 4-byte boundary via (addr+4)&0xFC,
    //and once 8 sprites are committed, evaluation switches to the buggy "overflow
    //scan" - every step advances evalAddr by a flat 5 (n-and-m-both-increment bug)
    //while still checking for/setting the overflow flag.
    //Advances the evaluation pointer, halting the WHOLE evaluation if it runs off the
    //end of OAM - only the overflow-scan phase actually reaches this: since it strides
    //by 5, wrapping to $00 would land on real sprite data and raise a bogus overflow
    //flag (AccuracyCoin "Sprite Overflow Behavior" test 3: 8 in-range sprites leave the flag clear).
    private void advanceEvalAddr(int delta) {
        int next = evalAddr + delta;
        if (next > 0xFF) evalActive = false;
        evalAddr = next & 0xFF;
        evalAddrTotalAdvance += delta;
    }

    //How far the evaluation pointer has moved this scanline, ignoring $FF wrap (keeps
    //counting past 256). Once past a full OAM pass (256), $2004 reads start
    //alternating primary-OAM value and secondary OAM's own address (see
    //oamBusValueAtCycle) - real hardware's evaluation/write circuits fall out of sync
    //once the pointer laps within one scanline's window.
    private int evalAddrTotalAdvance;

    //Set the moment evalAddrTotalAdvance first laps a full OAM pass (256) with fewer
    //than 8 sprites committed - from then on the speculative Y-copy write to secondary
    //OAM is suppressed, freezing secondaryOam[evalSecCount*4] at whatever it held at
    //the lap. The comparator keeps reading fresh bytes regardless; only the write
    //stops. AccuracyCoin's "$2004 Stress Test" answer key (section 6) shows this.
    private boolean evalSecWriteFrozen;

    //Whether the most recently EXECUTED step was a mid-sprite-copy continuation rather
    //than a fresh comparator check - must key off this, not the post-step
    //evalCopyRemaining value, since the copy step that brings it to 0 is still itself
    //a normal copy step (AccuracyCoin key-2: the 8th sprite's last copied byte appears
    //on both dots of its step; checking post-step value would freeze a dot early).
    private boolean evalLastStepWasMidCopy;

    //Whether the most recently executed step was a genuine overflow-scan candidate
    //check - secondary OAM was ALREADY full before this step, not the step that just
    //filled it. Only these get the frozen second-dot read; see stepSpriteEvaluation.
    private boolean evalWasOverflowScanRead;

    //Overflow-scan's n/m state machine - see stepSpriteEvaluation's `else` branch.
    //evalOverflowState: 0 = default buggy dual increment, 1 = 3-step "confirmation"
    //after the first in-range hit, 2 = final pinned mode (m stays 0, only n increments).
    private boolean evalOverflowInitialized;
    private int evalOverflowN, evalOverflowM, evalOverflowState, evalOverflowFreezeStep;

    //Set once evalOverflowN wraps past 63 back to 0 (a full OAM lap) - the overflow
    //flag must not spuriously raise on a second lap re-encountering already-committed
    //sprites' Y bytes. Real hardware's flag only latches on n's first pass, even
    //though the bus keeps cycling (per AccuracyCoin "$2004 Stress Test" key2).
    private boolean evalOverflowNWrapped;

    //Companion to advanceEvalAddr() for the earlier phases (mid-copy, out-of-range
    //realign) - the pointer keeps running the full 96 steps regardless of $FF wrap;
    //only overflow-scan's wrap halts early. AccuracyCoin "$2004 Stress Test" (key 1)
    //expects evalLastReadValue to keep changing well past the first wrap.
    private void advanceEvalAddrWrap(int delta) {
        evalAddr = (evalAddr + delta) & 0xFF;
        evalAddrTotalAdvance += delta;
    }

    //Pending OAM corruption armed by switching rendering off mid-scanline, and the
    //secondary OAM address latched at that moment. See cpuWrite's $2001 case.
    private boolean oamCorruptionPending = false;
    private int oamCorruptionSeed = 0;

    //Where the secondary OAM write pointer currently sits (0-$1F), reconstructed from
    //the dot: dots 1-64 clear (pointer advances every 2 dots), 65-256 evaluation
    //(tracks entry being filled, ceilinged to a multiple of 4), 257-320 sprite fetch
    //(advances on first three dots of each 8-dot slot, then holds).
    private int currentSecondaryOamAddr() {
        if (cycle >= 1 && cycle <= 64) return (cycle - 1) / 2;
        if (cycle >= 65 && cycle <= 256) return (evalSecCount * 4) & 0x1F;
        if (cycle >= 257 && cycle <= 320) {
            int slot = (cycle - 257) / 8;
            int into = (cycle - 257) % 8;
            return (slot * 4 + Math.min(3, into)) & 0x1F;
        }
        return 0;
    }

    private void maybePerformOamCorruption() {
        if (!oamCorruptionPending) return;
        if ((ppumask & 0x18) == 0) return;
        if (!(scanline <= 239 || scanline == 261)) return;
        oamCorruptionPending = false;
        int row = (oamCorruptionSeed & 0x1F) * 8;
        for (int i = 0; i < 8; i++) oam[(row + i) & 0xFF] = oam[i];
        secondaryOam[oamCorruptionSeed & 0x1F] = secondaryOam[0];
    }

    private void stepSpriteEvaluation() {
        if (!evalActive) return;
        boolean tall = (ppuctrl & 0x20) != 0;
        int spriteHeight = tall ? 16 : 8;

        //Captured before this step can bump evalSecCount to 8 - the step confirming
        //the 8th in-range sprite is still a fresh comparator read (its second dot
        //naturally echoes the value), not yet the frozen overflow-scan read that only
        //applies once secondary OAM was ALREADY full going in.
        boolean wasOverflowScanBeforeThisStep = evalCopyRemaining == 0 && evalSecCount >= 8;

        int m = evalAddr & 0x03;
        int val = oam[evalAddr & 0xFF] & 0xFF;
        if (m == 2) val &= 0xE3; //attribute byte: bits 2-4 don't physically exist
        evalLastReadValue = val;
        evalWasOverflowScanRead = wasOverflowScanBeforeThisStep;

        //Mid-copy is checked first, independent of evalSecCount: once an 8th sprite is
        //found in-range, evalSecCount jumps to 8 right away (so a 9th correctly enters
        //overflow-scan), but the 8th sprite itself still has 3 bytes left to copy
        //normally, not be misread as fresh overflow-scan checks.
        evalLastStepWasMidCopy = evalCopyRemaining > 0;
        if (evalCopyRemaining > 0) {
            //Destination offset within the secondary-OAM entry is SEQUENTIAL (1,2,3 as
            //evalCopyRemaining counts down), not evalAddr's physical low bits - they
            //only coincide on a 4-byte-aligned start; a misaligned OAMADDR diverges
            //them (using physical bits filed the tile byte into the attribute slot).
            //Note the bits-2-4 mask above DOES key off the physical address, so a
            //misaligned tile-byte read loses those bits - see AccuracyCoin "Arbitrary
            //Sprite Zero" test 3 (tile $E3 chosen to survive that mask).
            secondaryOam[(evalSecCount - 1) * 4 + (4 - evalCopyRemaining)] = (byte) val;
            advanceEvalAddrWrap(1);
            evalCopyRemaining--;
        } else if (evalSecCount < 8) {
            if (!evalSecWriteFrozen) secondaryOam[evalSecCount * 4] = (byte) val; //speculative Y copy; only meaningful if in range
            //OAM's Y byte is the sprite's top row MINUS 1 (Y=0 first displays on
            //scanline 1) - see fetchSpriteSlot's matching `y + 1`.
            int diff = evalTargetScanline - (val + 1);
            boolean inRange = diff >= 0 && diff < spriteHeight;
            evalSpritesScanned++;
            //Only the object decided on the very first step can ever be sprite zero -
            //see evalSpriteZeroInRange's declaration.
            if (evalFirstStep && inRange) evalSpriteZeroInRange = true;
            evalFirstStep = false;
            if (inRange) {
                evalCopyRemaining = 3;
                evalSecCount++;
                advanceEvalAddr(1);
            } else {
                //Out of range: skip to next object, realign to a 4-byte boundary. The
                //AND is only observable when OAMADDR was misaligned - see AccuracyCoin
                //"Misaligned OAM Behavior" test 2. Wraps rather than halting.
                evalAddr = (evalAddr + 4) & 0xFF & 0xFC;
                evalAddrTotalAdvance += 4;
            }
            if (evalAddrTotalAdvance >= 256 && evalSecCount < 8) evalSecWriteFrozen = true;
        } else {
            int diff = evalTargetScanline - (val + 1); //see the +1 note above
            boolean inRange = diff >= 0 && diff < spriteHeight;
            //Only honored on n's first lap - see evalOverflowNWrapped's comment.
            if (inRange && !evalOverflowNWrapped) ppustatus |= 0x20; //sprite overflow flag

            if (!evalOverflowInitialized) {
                //evalAddr already holds the real next candidate - the last in-range
                //copy's advanceEvalAddrWrap(1) left it there. m starts from evalAddr's
                //own low bits (not forced to 0) since a misaligned start stays
                //misaligned through evaluation - see AccuracyCoin "Misaligned OAM Behavior".
                evalOverflowN = (evalAddr >> 2) & 0x3F;
                evalOverflowM = evalAddr & 0x03;
                evalOverflowState = 0;
                evalOverflowInitialized = true;
            }
            //n and m both advancing unconditionally (m should stay 0 but doesn't) is
            //the default overflow-scan behavior (state 0). AccuracyCoin key-2 (the
            //>8-real-sprites case) shows the first in-range hit during overflow-scan
            //sends hardware through 3 more "confirmation" reads at m=1,2,0 with n
            //fixed, then settles into a pinned mode (state 2) where m stays 0 and only
            //n increments - hardware self-corrects to the non-buggy algorithm.
            //Captured empirically against the answer table, not independently verified
            //against hardware docs.
            int prevOverflowState = evalOverflowState;
            int nBeforeAdvance = evalOverflowN;
            if (prevOverflowState == 2) {
                evalOverflowN = (evalOverflowN + 1) & 0x3F;
                //evalOverflowM already 0 and stays that way.
            } else if (prevOverflowState == 1) {
                int[] confirmM = {1, 2, 0};
                evalOverflowM = confirmM[evalOverflowFreezeStep];
                evalOverflowFreezeStep++;
                if (evalOverflowFreezeStep == 3) evalOverflowState = 2;
                //evalOverflowN stays fixed for the whole confirmation sequence.
            } else {
                evalOverflowN = (evalOverflowN + 1) & 0x3F;
                evalOverflowM = (evalOverflowM + 1) & 0x03;
            }
            if (evalOverflowN < nBeforeAdvance) evalOverflowNWrapped = true;
            if (inRange && prevOverflowState == 0) {
                evalOverflowState = 1;
                evalOverflowFreezeStep = 0;
            }
            evalAddr = (evalOverflowN * 4 + evalOverflowM) & 0xFF;
            evalAddrTotalAdvance += 5;
        }
    }

    //Loads one sprite output unit from its secondary-OAM slot, at the last of each
    //slot's 8 fetch cycles (264,272,...,320) so PPUCTRL's tall-sprite/pattern-table
    //bits are sampled as late as real per-slot timing allows.
    //A slot whose Y is still $FF (unclaimed by evaluation) fetches fully transparent,
    //matching a real unused slot's harmless garbage fetch.
    private void fetchSpriteSlot(int slot) {
        int base = slot * 4;
        int y = secondaryOam[base] & 0xFF;
        int n = slot;
        sprIsZero[n] = (slot == 0) && (y != 0xFF) && evalSpriteZeroInRange;

        if (y == 0xFF) {
            sprCounter[n] = 0xFF;
            sprShiftLo[n] = 0;
            sprShiftHi[n] = 0;
            sprAttrLatch[n] = 0;
            return;
        }

        int tileIndex = secondaryOam[base + 1] & 0xFF;
        int attr = secondaryOam[base + 2] & 0xFF;
        int spriteX = secondaryOam[base + 3] & 0xFF;

        boolean tall = (ppuctrl & 0x20) != 0;
        int spriteHeight = tall ? 16 : 8;
        int spritePatternBase = ((ppuctrl & 0x08) != 0) ? 0x1000 : 0x0000;

        boolean flipV = (attr & 0x80) != 0;
        boolean flipH = (attr & 0x40) != 0;
        boolean behindBackground = (attr & 0x20) != 0;
        int paletteGroup = attr & 0x03;

        int patternTableOffset = tall ? (((tileIndex & 0x01) != 0) ? 0x1000 : 0x0000) : spritePatternBase;
        int tileNumber = tall ? (tileIndex & 0xFE) : tileIndex;

        //The fetch-phase comparator uses the PPU's raw 8-bit scanline counter, not
        //"the scanline evaluation was for" - they agree on visible scanlines but not
        //pre-render (261 & 255 = 5), so the fetch phase treats pre-render as scanline
        //5 and can pull pattern data for a stale entry "in range" of line 5. Those
        //registers survive into scanline 0 - the premise of AccuracyCoin "Sprites On
        //Scanline 0", whose test object paints only on row 5.
        int row = (scanline & 0xFF) - y;
        //The Y comparator keeps running through the fetch phase against whatever
        //height PPUCTRL selects NOW, not at evaluation time - so a $2000 write during
        //HBlank that drops 8x16 mode can leave an accepted object out of range by
        //fetch time, coming back blank instead of spilling into the next tile. See
        //AccuracyCoin "Suddenly Resize Sprite" tests 4/5.
        sprCounter[n] = spriteX;
        sprAttrLatch[n] = attr;
        if (row < 0 || row >= spriteHeight) {
            sprShiftLo[n] = 0;
            sprShiftHi[n] = 0;
            return;
        }
        int rowInTile = flipV ? (spriteHeight - 1 - row) : row;
        int tileOffset;
        if (tall) {
            int half = rowInTile / 8;
            int rowWithinHalf = rowInTile % 8;
            tileOffset = patternTableOffset + ((tileNumber + half) * 16) + rowWithinHalf;
        } else {
            tileOffset = patternTableOffset + (tileNumber * 16) + rowInTile;
        }

        //Horizontal flip is applied by the loading circuitry, not the output mux: the
        //shift register is filled in reverse bit order and shifts out of bit 7 as
        //usual. Doing it here (not at sample time) keeps a partially-drained flipped
        //shifter correct - it has no "column index" left to mirror.
        int lo = ppuRead(tileOffset) & 0xFF;
        int hi = ppuRead(tileOffset + 8) & 0xFF;
        sprShiftLo[n] = flipH ? reverseByte(lo) : lo;
        sprShiftHi[n] = flipH ? reverseByte(hi) : hi;
    }

    private static int reverseByte(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) r |= ((b >> i) & 1) << (7 - i);
        return r;
    }

    //One dot of the eight sprite output units, run before pixel compositing on every
    //rendering dot 1-256. A counting unit steps toward its X; a unit reaching zero
    //latches halted and shifts one pixel out per dot. Since both counter and shifter
    //only advance while rendering is on, disabling rendering freezes a unit exactly
    //where it stood, resuming from there when re-enabled.
    private void clockSpriteUnits(boolean renderingEnabled) {
        for (int i = 0; i < MAX_ROW_SPRITES; i++) {
            if (!sprHalted[i]) {
                if (sprCounter[i] != 0) { sprCounter[i]--; sprPixelOut[i] = 0; continue; }
                sprHalted[i] = true;
            }
            //Counters are free-running through forced blanking, so a sprite whose X is
            //passed while rendering is briefly off still comes out at that X (test 2).
            //Shifters are the half that stalls: a unit halted during F-Blank keeps all
            //8 pixels loaded and dumps them wherever the beam is when rendering
            //returns (test 3).
            if (!renderingEnabled) { sprPixelOut[i] = 0; continue; }
            int b0 = (sprShiftLo[i] >> 7) & 1;
            int b1 = (sprShiftHi[i] >> 7) & 1;
            sprPixelOut[i] = (b1 << 1) | b0;
            sprShiftLo[i] = (sprShiftLo[i] << 1) & 0xFF;
            sprShiftHi[i] = (sprShiftHi[i] << 1) & 0xFF;
        }
    }

    //Renders one pixel - background then sprites, in real priority order - and sets
    //sprite-0-hit right here if this dot is where sprite 0's opaque pixel genuinely
    //overlaps opaque background. Called once per dot for cycles 1-256, so the hit
    //flag becomes visible at the actual x-position (needed for mid-frame scroll
    //writes timed off the hit, e.g. SMB's status-bar split).
    private void renderPixel(int sy, int x) {
        //PPUMASK's show/left-mask bits are sampled live per dot, not latched once per
        //scanline - a $2001 write mid-scanline takes effect the very next dot.
        //AccuracyCoin "Rendering Flag Behavior" tests 2/3 depend on a hit being
        //evaluated with the just-written mask; latching at dot 1 broke this.
        rowShowBackground = (ppumask & 0x08) != 0;
        rowShowSprites = (ppumask & 0x10) != 0;
        rowShowLeftBackground = (ppumask & 0x02) != 0;
        rowShowLeftSprites = (ppumask & 0x04) != 0;

        int rgb;
        boolean bgOpaque;
        int debugWinner = -1;
        if (!rowShowBackground || (x < 8 && !rowShowLeftBackground)) {
            rgb = currentPaletteRgb(rowBackdropPaletteIdx);
            bgOpaque = false;
        } else {
            //Sampled from the real per-dot shift registers (updated just before this
            //call) rather than a world-space tile cache - fineX selects which of the
            //16 shifted bits is this dot's pixel, same mux real hardware uses.
            int bitMux = 0x8000 >> fineX;
            int bit0 = (bgShiftPatternLo & bitMux) != 0 ? 1 : 0;
            int bit1 = (bgShiftPatternHi & bitMux) != 0 ? 1 : 0;
            int pixelVal = (bit1 << 1) | bit0;

            int pal0 = (bgShiftAttrLo & bitMux) != 0 ? 1 : 0;
            int pal1 = (bgShiftAttrHi & bitMux) != 0 ? 1 : 0;
            int paletteGroup = (pal1 << 1) | pal0;

            int paletteIndex = (pixelVal == 0)
                    ? (ppuRead(0x3F00) & 0x3F)
                    : (ppuRead(0x3F00 + paletteGroup * 4 + pixelVal) & 0x3F);
            rgb = currentPaletteRgb(paletteIndex);
            bgOpaque = pixelVal != 0;
        }
        //rowShowSprites must still gate drawing/hit-testing even though
        //evaluation+fetch always run when rendering is enabled - AccuracyCoin
        //"Rendering Flag Behavior" test 3 and "Sprite Overflow Behavior" test 4 say
        //evaluation must still happen with only bg enabled, so the gate moves here.
        if (rowShowSprites && (x >= 8 || rowShowLeftSprites)) {
            //Real hardware resolves sprite-vs-sprite priority first - the topmost
            //(lowest index) opaque sprite wins outright - then decides
            //background-vs-that-sprite from its own priority bit. A lower sprite must
            //never show through just because the topmost lost to the background -
            //the "masking sprite" trick some games rely on (e.g. SMB3 stacks an
            //opaque, behind-background sprite over another sprite's part that needs hiding).
            //
            //Unit 0 is highest priority, so a forward scan finds the real winner
            //first. Sprite-0-hit is a separate comparator wired to unit 0's own shift
            //register, firing regardless of who wins the pixel.
            boolean topmostResolved = false;
            for (int n = 0; n < MAX_ROW_SPRITES; n++) {
                int pixelVal = sprPixelOut[n];
                if (pixelVal == 0) continue; //transparent

                //Real sprite-0-hit never fires at x=255, and only for whichever sprite
                //occupies secondary-OAM slot 0 - not necessarily primary OAM index 0
                //(AccuracyCoin "Arbitrary Sprite Zero" test 2).
                if (sprIsZero[n] && bgOpaque && x != 255) {
                    ppustatus |= 0x40;
                    hitOccurredThisFrame = true;
                    if (DEBUG_SPRITE0) System.out.printf("  [HIT SET] sy=%d x=%d%n", sy, x);
                }
                if (DEBUG_SPRITE0 && sprIsZero[n]) {
                    System.out.printf("sprite0 sy=%d x=%d pixelVal=%d bgHere=%b worldScrollX=%d%n", sy, x, pixelVal, bgOpaque, getWorldScrollX());
                }

                if (topmostResolved) continue; //already found the real winner; keep scanning only for sprite-0-hit
                topmostResolved = true;

                if ((sprAttrLatch[n] & 0x20) != 0 && bgOpaque) continue; //topmost sprite loses to opaque background - no lower sprite gets a turn

                int paletteIndex = ppuRead(0x3F10 + (sprAttrLatch[n] & 0x03) * 4 + pixelVal) & 0x3F;
                rgb = currentPaletteRgb(paletteIndex);
                debugWinner = sprIsZero[n] ? 0 : 1; //debug-only marker (0=slot-0, 1=other); not a real OAM index
            }
        }

        debugBgOpaque[sy * 256 + x] = bgOpaque;
        debugWinnerOam[sy * 256 + x] = debugWinner;

        if (display != null) display.setPixel(x, sy, rgb);
    }

    //Debug-only: reports what actually rendered at a pixel, from debugBgOpaque/
    //debugWinnerOam recorded live by renderPixel() (see those fields' comment for why
    //re-deriving this after the fact isn't trustworthy for MMC3 CHR-bank swaps).
    public String explainPixel(int x, int sy) {
        int idx = sy * 256 + x;
        boolean bgOpaque = debugBgOpaque[idx];
        int winner = debugWinnerOam[idx];
        return String.format("pixel (%d,%d): background %s; winner = %s%n", x, sy,
                bgOpaque ? "opaque" : "transparent/backdrop",
                winner < 0 ? "background" : ("OAM[" + winner + "]"));
    }

    //Debug-only: renders a small region as an ASCII grid from the same live-recorded
    //per-pixel data explainPixel() uses. '.' = transparent bg, '#' = opaque bg, hex
    //digit = low nibble of winning OAM index (matching digits trace one sprite's shape).
    public String explainGrid(int x0, int y0, int w, int h) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("grid x=%d..%d y=%d..%d ('.'=transparent bg, '#'=opaque bg, hex digit=winning OAM index low nibble):%n", x0, x0 + w - 1, y0, y0 + h - 1));
        for (int sy = y0; sy < y0 + h; sy++) {
            StringBuilder row = new StringBuilder();
            for (int x = x0; x < x0 + w; x++) {
                if (x < 0 || x >= 256 || sy < 0 || sy >= 240) { row.append(' '); continue; }
                int idx = sy * 256 + x;
                int winner = debugWinnerOam[idx];
                row.append(winner >= 0 ? Integer.toHexString(winner & 0xF).charAt(0) : (debugBgOpaque[idx] ? '#' : '.'));
            }
            sb.append(String.format("y=%3d: %s%n", sy, row));
        }
        return sb.toString();
    }
}
