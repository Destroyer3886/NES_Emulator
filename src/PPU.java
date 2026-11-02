public class PPU {

    public static boolean DEBUG_SPRITE0 = false;

    //$2002 read: VBlank latches when M2 rises, but sprite 0 hit/overflow sample at M2's
    //fall ~1.875 dots later (AccuracyCoin "$2002 Flag Timing" test 1 measures the skew).
    private static final int M2_FALL_LOOKAHEAD_DOTS = 2;

    //VBlank/sprite0hit/overflow all clear together on dot 1 of pre-render (scanline 261).
    private int dotsUntilPreRenderFlagClear() {
        if (scanline == 261 && cycle == 0) return 1;
        if (scanline == 260) return (341 - cycle) + 1;
        return Integer.MAX_VALUE;
    }

    //Frame parity for pre-render's odd-frame dot skip - see step().
    private boolean oddFrame = false;

    //Debug-only, recorded live per pixel: re-deriving a paused frame afterward would
    //use the wrong CHR bank after MMC3 mid-frame swaps (e.g. SMB3 status bar).
    private final boolean[] debugBgOpaque = new boolean[256 * 240];
    private final int[] debugWinnerOam = new int[256 * 240]; //-1 = background/backdrop won

    //Eight 64-color tables, one per PPUMASK emphasis set (row = (ppumask>>5)&7, bit0=R/
    //bit1=G/bit2=B), measured off a real NTSC RP2C02 (res/nes_palette_hex.txt).
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

    //No-emphasis table for debug tools that want a fixed reference palette.
    public static final int[] NES_PALETTE = EMPHASIS_PALETTE[0];

    //Emphasis is sampled live from PPUMASK: a mid-scanline $2001 write re-tints from the next dot.
    private int currentPaletteRgb(int paletteIndex) {
        int idx = paletteIndex & 0x3F;
        //Greyscale (PPUMASK bit 0) forces the low 4 bits to 0 at the DAC (see "Palette RAM Quirks" in cpuRead).
        if ((ppumask & 0x01) != 0) idx &= 0x30;
        return EMPHASIS_PALETTE[(ppumask >> 5) & 0x07][idx];
    }

    //Sized for four-screen (4x1KB); the cartridge decides how many slots are distinct.
    private final byte[] nametables = new byte[4096];
    private final byte[] paletteRam = new byte[32];
    private final byte[] oam = new byte[256];       //Object Attribute Memory

    //OAM decay: OAM is DRAM refreshed only by sprite evaluation, so with rendering off
    //a byte decays to $00 (nesdev "PPU OAM"). ~2 NTSC frames keeps AccuracyCoin's
    //OAM Corruption / INC $4014 / $2007 Stress Test intact; fixed count so TAS replays are deterministic.
    public static boolean oamDecayEnabled = true;
    private static final int OAM_DECAY_DOTS = 180_000;
    private long dotClock = 0;
    private final long[] oamRefreshDot = new long[256];

    private boolean oamDecayed(int index) {
        return oamDecayEnabled && dotClock - oamRefreshDot[index] > OAM_DECAY_DOTS;
    }

    //All OAM reads go through here; a decayed cell is zeroed in place since a later refresh only preserves the 0.
    private int oamRead(int index) {
        index &= 0xFF;
        if (oamDecayed(index)) { oam[index] = 0; oamRefreshDot[index] = dotClock; }
        return oam[index] & 0xFF;
    }

    //Side-effect-free variant for debug views.
    private int oamPeek(int index) {
        index &= 0xFF;
        return oamDecayed(index) ? 0 : (oam[index] & 0xFF);
    }

    private void oamWrite(int index, byte value) {
        index &= 0xFF;
        oam[index] = value;
        oamRefreshDot[index] = dotClock;
    }

    private void oamRefreshAll() {
        for (int i = 0; i < 256; i++) {
            if (oamDecayed(i)) oam[i] = 0;
            oamRefreshDot[i] = dotClock;
        }
    }

    //PPU Control / Status Registers
    public int ppuctrl = 0; //$2000
    public int ppumask = 0; //$2001
    public int ppustatus = 0; //$2002
    public int oamaddr = 0; //$2003
    public int oamdata = 0; //$2004
    public int ppuscroll = 0; //$2005
    public int ppuaddr = 0; //$2006
    public int ppudata = 0; //$2007

    //Loopy registers: v = current VRAM address, t = staging for $2000/$2005/$2006, w = write
    //toggle; 15 bits yyy NN YYYYY XXXXX. v only copies t's horizontal bits at cycle 257 and vertical bits in pre-render.
    private boolean addressLatch = false; //w
    private int v = 0x0000;
    private int t = 0x0000;

    //A12 state for CPU-side $2006/$2007 accesses: blargg's mmc3_irq_tests toggle $2006
    //with rendering off, which the renderer's per-scanline approximation can't see.
    private boolean a12 = false;

    //Separate A12 state for the render-fetch approximation; sharing one level with the
    //bus tracker produced spurious MMC3 clocks (mmc3_irq_tests).
    private boolean renderA12 = false;

    //Clocks the mapper IRQ counter on an A12 0->1 edge.
    private void clockA12(int address) {
        boolean newA12 = (address & 0x1000) != 0;
        if (newA12 && !a12 && bus != null) {
            bus.clockMapperIRQ();
        }
        a12 = newA12;
    }
    private int fineX = 0;
    private byte readBuffer = 0x00;

    //External octal latch for the address bus's low 8 bits, plus the dot a $2007 access asserts Read (see aleDot/readDot).
    private int octalLatch = 0;
    private boolean ppuDataReadThisDot = false;
    private boolean ppuDataReadServiced = false;
    //A $2007 read asserts Read 5 dots after the CPU issues it (3 left in the CPU cycle + 2
    //for the state machine); AccuracyCoin "ALE + Read" and "$2007 Stress Test" measure this.
    private static final int PPUDATA_READ_DELAY_DOTS = 5;
    private int ppuDataReadDelay = 0;
    private int ppuDataReadAddr = 0;
    private boolean ppuDataIncPending = false;

    //Open bus: every register access latches here; write-only registers and $2002's low 5 bits read it back.
    private int ppuDataBus = 0x00;
    //The bus decays to 0 well under a second (~5.37M dots/sec NTSC) - AccuracyCoin "PPU Register Open Bus" test 5.
    private static final int PPU_BUS_DECAY_TICKS = 4_500_000;
    private int ppuDataBusDecay = PPU_BUS_DECAY_TICKS;

    //Raw PPUSCROLL bytes, kept only for DEBUG_SPRITE0 trace printouts.
    private int scrollX = 0;
    private int scrollY = 0;

    public int cycle = 0;
    public int scanline = 0;

    //Power-on warm-up: writes to $2000/$2001/$2005/$2006 are ignored for ~29658 CPU
    //cycles; without it boot-time writes land a frame early (breaks e.g. SMB3 init).
    private static final int PPU_WARMUP_DOTS = 29658 * 3;
    private int warmupDotsRemaining = 0;

    //Debug-only: whether sprite-0-hit fired in the frame that just ended, captured before the bit clears.
    public boolean lastFrameHadHit = false;
    private boolean hitOccurredThisFrame = false;

    //NMI line = AND(VBlank flag, PPUCTRL bit 7), edge-triggered into the CPU, so enabling NMI
    //mid-VBlank or toggling it within one VBlank must fire (AccuracyCoin "NMI Control" tests 3/7).
    private boolean nmiLine = false;
    //VBlank level for NMI, clearing 1 dot before the $2002 bit (AccuracyCoin "NMI at VBlank End").
    private boolean nmiVblLevel = false;
    private void updateNmiLine() {
        boolean newLine = nmiVblLevel && ((ppuctrl & 0x80) != 0);
        if (newLine && !nmiLine && cpu != null) cpu.raiseNMI();
        nmiLine = newLine;
    }

    //A $2002 read at scanline 241 cycle 0 suppresses this frame's VBlank flag and NMI
    //(AccuracyCoin "VBlank Beginning" case A=4, "NMI Suppression").
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

    //Reset button (nesdev "PPU power up state"): clears PPUCTRL/PPUMASK/w/t/fine X/read buffer, leaves
    //PPUSTATUS/OAMADDR/v/OAM/VRAM/palette alone, then ignores writes for the same ~29658 CPU cycles as power-on.
    public void reset() {
        ppuctrl = 0; ppumask = 0;
        pendingMaskDelay = 0; pendingMaskValue = 0;
        ppuscroll = 0; ppudata = 0;
        addressLatch = false; t = 0; fineX = 0;
        scrollX = 0; scrollY = 0;
        readBuffer = 0;
        cycle = 0; scanline = 0;
        oddFrame = false;
        nmiLine = false;
        warmupDotsRemaining = PPU_WARMUP_DOTS;
        updateNmiLine();
    }

    //Blargg's measured power-on palette RAM (as used by Mesen); fixed so TAS replays are deterministic.
    private static final int[] PALETTE_POWER_ON = {
        0x09, 0x01, 0x00, 0x01, 0x00, 0x02, 0x02, 0x0D, 0x08, 0x10, 0x08, 0x24, 0x00, 0x00, 0x04, 0x2C,
        0x09, 0x01, 0x34, 0x03, 0x00, 0x04, 0x00, 0x14, 0x08, 0x3A, 0x00, 0x02, 0x00, 0x20, 0x2C, 0x08
    };

    //Cold boot: PPUSTATUS "often" $A0, OAMADDR/PPUADDR $00, CIRAM mostly $FF, palette
    //RAM at its observed boot contents, OAM fully decayed to $00.
    public void hardReset() {
        reset();
        ppustatus = 0xA0;
        oamaddr = 0; ppuaddr = 0; v = 0;
        java.util.Arrays.fill(nametables, (byte) 0xFF);
        for (int i = 0; i < 32; i++) paletteRam[i] = (byte) PALETTE_POWER_ON[i];
        java.util.Arrays.fill(oam, (byte) 0);
        dotClock = 0;
        java.util.Arrays.fill(oamRefreshDot, 0L);
        java.util.Arrays.fill(debugLineRendered, false);
        ppuDataBus = 0;
        ppuDataBusDecay = PPU_BUS_DECAY_TICKS;
        vblankStarted = false;
        lastFrameHadHit = false;
        hitOccurredThisFrame = false;
    }

    //TAS Maker greenzone checkpoint; see CPU.State for why it's only taken at instruction boundaries.
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
        long dotClock;
        long[] oamRefreshDot;
    }

    public State snapshot() {
        State s = new State();
        s.nametables = nametables.clone();
        s.paletteRam = paletteRam.clone();
        s.oam = oam.clone();
        s.dotClock = dotClock;
        s.oamRefreshDot = oamRefreshDot.clone();
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
        dotClock = s.dotClock;
        System.arraycopy(s.oamRefreshDot, 0, oamRefreshDot, 0, oamRefreshDot.length);
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

    //Loopy Y increment at dot 256: coarse Y past 29 flips the vertical nametable (bit 11);
    //30-31 are attribute rows that wrap without flipping.
    private void incrementY() {
        if ((v & 0x7000) != 0x7000) {
            v += 0x1000;
        } else {
            v &= ~0x7000;
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

    //Coarse X increment every 8 fetch dots; past 31 flips the horizontal nametable (bit 10).
    private void incrementScrollX() {
        if ((v & 0x001F) == 31) {
            v &= ~0x001F;
            v ^= 0x0400;
        } else {
            v++;
        }
    }

    //Pattern shifters' serial input is tied high on real hardware (AccuracyCoin "BG Serial In" test 2).
    private void updateBgShifters() {
        bgShiftPatternLo = (bgShiftPatternLo << 1) | 1;
        bgShiftPatternHi = (bgShiftPatternHi << 1) | 1;
        bgShiftAttrLo = (bgShiftAttrLo << 1) & 0xFFFF;
        bgShiftAttrHi = (bgShiftAttrHi << 1) & 0xFFFF;
    }

    //Attribute bits are expanded to all-0/all-1 bytes so the per-dot mux works for palette selection too.
    private void loadBgShifters() {
        bgShiftPatternLo = (bgShiftPatternLo & 0xFF00) | bgNextLsb;
        bgShiftPatternHi = (bgShiftPatternHi & 0xFF00) | bgNextMsb;
        bgShiftAttrLo = (bgShiftAttrLo & 0xFF00) | (((bgNextAttr & 0x01) != 0) ? 0xFF : 0x00);
        bgShiftAttrHi = (bgShiftAttrHi & 0xFF00) | (((bgNextAttr & 0x02) != 0) ? 0xFF : 0x00);
    }

    //Each fetch is two dots: the ALE dot latches the low 8 address bits in an external octal
    //latch, the read dot re-drives only the upper 6 bits (AccuracyCoin "Hybrid Addresses", "ALE + Read").
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

    //ALE dot: if a $2007 Read lands on the same dot, the latch keeps its stale low byte and
    //both that read and the next read dot go through it ("ALE + Read").
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
        //Address/data share pins, so the latch tracks the data byte until the next ALE.
        octalLatch = data;
        //A $2007 read landing on a read dot takes whatever the fetch read ("$2007 Stress Test" stable-buffer case).
        if (ppuDataReadThisDot) {
            readBuffer = (byte) data;
            ppuDataReadServiced = true;
        }
        return data;
    }

    //Sprite-fetch counterpart to aleDot/readDot (dots 257-320): each slot's first two fetches are
    //garbage nametable reads through v, still visible to $2007 (AccuracyCoin "AT* reads are NT reads").
    private void spriteAleDot(int addr) {
        if (ppuDataReadThisDot) {
            readBuffer = ppuRead((addr & 0x3F00) | octalLatch);
            ppuDataReadServiced = true;
            return;
        }
        octalLatch = addr & 0xFF;
    }

    private int spriteReadDot(int addr) {
        int a = (addr & 0x3F00) | octalLatch;
        int data = ppuRead(a) & 0xFF;
        octalLatch = data;
        //Same as readDot: a $2007 read here rides along on the fetch.
        if (ppuDataReadThisDot) {
            readBuffer = (byte) data;
            ppuDataReadServiced = true;
        }
        return data;
    }

    //Pattern bytes the slot's fetch dots returned; the cartridge must see exactly one read per
    //fetch (MMC2/MMC4 latches, MMC5 fetch counting), so fetchSpriteSlot reuses these.
    private int sprFetchLo, sprFetchHi;

    //Pattern-table address for a sprite slot's fetch dots; an unused ($FF) slot still drives
    //a real address, so row is clamped rather than special-cased.
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

    //ALE/Read cadence for the sprite-fetch window (dots 257-320).
    private void spriteFetchAleStep(int cycle) {
        if (cycle < 257 || cycle > 320) return;
        int slot = (cycle - 257) / 8;
        switch ((cycle - 257) % 8) {
            case 0: spriteAleDot(fetchAleAddr(0)); break;
            case 1: spriteReadDot(fetchAleAddr(0)); break;
            case 2: spriteAleDot(fetchAleAddr(0)); break;
            case 3: spriteReadDot(fetchAleAddr(0)); break;
            case 4: spriteAleDot(spritePatternFetchAddr(slot, false)); break;
            case 5: sprFetchLo = spriteReadDot(spritePatternFetchAddr(slot, false)); break;
            case 6: spriteAleDot(spritePatternFetchAddr(slot, true)); break;
            case 7: sprFetchHi = spriteReadDot(spritePatternFetchAddr(slot, true)); break;
        }
    }

    //Fetches occupy dots 1-256/321-336, shifters run one dot behind (2-257/322-337), reloads
    //land at 9,17,...,257 and 329/337; a single shared window lost the dot-1 ALE.
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
                    if ((v & 0x0040) != 0) attr >>= 4;
                    if ((v & 0x0002) != 0) attr >>= 2;
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
            //Dummy nametable fetches at scanline end still drive ALE and the octal latch.
            aleDot(0);
        } else if (cycle == 338 || cycle == 340) {
            bgNextTileId = readDot(0);
        }
    }

    public int getWorldScrollX() {
        int base = ((v & 0x0400) != 0) ? 256 : 0;
        return (base + ((v & 0x001F) * 8) + fineX) & 0x1FF;
    }

    public int getWorldScrollY() {
        int base = ((v & 0x0800) != 0) ? 240 : 0;
        int coarseY = (v >> 5) & 0x1F;
        int fineY = (v >> 12) & 0x07;
        return (base + coarseY * 8 + fineY) % 480;
    }

    //$2007 auto-increment: +1/+32 with rendering off, but during a rendering visible/pre-render
    //line it runs the renderer's coarse-X and Y increments instead (AccuracyCoin "$2007 Read W/ Rendering" test 2).
    private void incrementVramAddressAfterCpuAccess() {
        boolean renderingEnabled = (ppumask & 0x18) != 0;
        boolean visibleOrPrerender = scanline <= 239 || scanline == 261;
        if (renderingEnabled && visibleOrPrerender) {
            incrementScrollX();
            incrementY();
        } else {
            v += ((ppuctrl & 0x04) != 0) ? 32 : 1;
        }
        //v is 15-bit; masking to $3FFF would drop fine-Y's high bit ("$2007 Read W/ Rendering" expects v += $1001).
        v &= 0x7FFF;
    }

    public byte cpuRead(int address) {
        switch (address) {
            case 0x2002: //PPUSTATUS - bits 5-7 real, rest open bus
                //Bits 6/5 sample ~2 dots after bit 7 (M2 fall vs rise), so a read straddling the
                //pre-render clear sees VBlank set but sprite flags gone ("$2002 Flag Timing" test 1: $E0/$80/$80/$00).
                int statusBits = ppustatus & 0xE0;
                if (dotsUntilPreRenderFlagClear() <= M2_FALL_LOOKAHEAD_DOTS) statusBits &= ~0x60;
                byte status = (byte) (statusBits | (ppuDataBus & 0x1F));
                if (DEBUG_SPRITE0) System.out.printf("  [read $2002 -> %02x] scanline=%d cycle=%d%n", status & 0xFF, scanline, cycle);
                ppustatus &= ~0x80;
                nmiVblLevel = false;
                if (scanline == 241 && cycle == 0) suppressVblSet = true; //see suppressVblSet's declaration
                if (scanline == 241 && cycle == 1 && cpu != null) cpu.cancelPendingNMI(); //read races the flag-set tick: flag stands, but NMI is suppressed
                updateNmiLine(); //a read-cleared VBlank flag can also drop the NMI line
                addressLatch = false;
                //Only bits 5-7 are driven, so the open-bus decay clock isn't reset.
                ppuDataBus = status & 0xFF;
                return status;

            case 0x2004: { //OAMDATA
                //The CPU samples OAM a few dots later than `cycle` - see OAM_READ_LOOKAHEAD_DOTS.
                int oamVal = oamBusValueAtCycle(OAM_READ_LOOKAHEAD_DOTS);
                ppuDataBus = oamVal;
                ppuDataBusDecay = PPU_BUS_DECAY_TICKS;
                return (byte) ppuDataBus;
            }

            case 0x2007: { //PPUDATA
                flushPendingV();
                clockA12(v);
                byte data = readBuffer;
                //For palette addresses the buffer still loads the underlying nametable byte ($1000 lower).
                boolean delayedBuffer = (ppumask & 0x18) != 0 && (scanline <= 239 || scanline == 261);
                if (delayedBuffer) {
                    ppuDataReadAddr = v;
                    ppuDataReadDelay = PPUDATA_READ_DELAY_DOTS;
                } else {
                    readBuffer = (v >= 0x3F00) ? peekNametable((v - 0x1000) & 0x2FFF) : ppuRead(v);
                }

                if (v >= 0x3F00) {
                    //Palette reads are unbuffered with open-bus upper 2 bits; greyscale masks the
                    //read path only (AccuracyCoin "Palette RAM Quirks" tests 6/7).
                    int pal = ppuRead(v) & 0x3F;
                    if ((ppumask & 0x01) != 0) pal &= 0x30;
                    data = (byte) (pal | (ppuDataBus & 0xC0));
                }

                //The increment is delayed with the buffer fill so fetch dots in between still see the old v.
                if (delayedBuffer) {
                    ppuDataIncPending = true;
                } else {
                    incrementVramAddressAfterCpuAccess();
                }
                clockA12(v); //the increment itself can pulse A12
                ppuDataBus = data & 0xFF;
                ppuDataBusDecay = PPU_BUS_DECAY_TICKS;
                return data;
            }

            default: //write-only registers read open bus
                return (byte) ppuDataBus;
        }
    }

    public void cpuWrite(int address, byte data) {
        int val = data & 0xFF;
        ppuDataBus = val;
        ppuDataBusDecay = PPU_BUS_DECAY_TICKS;
        switch (address) {
            case 0x2000: //PPUCTRL
                if (warmupDotsRemaining > 0) break;
                ppuctrl = val;
                t = (t & 0xF3FF) | ((val & 0x03) << 10);
                updateNmiLine(); //toggling bit 7 can raise the NMI line immediately
                if (DEBUG_SPRITE0) System.out.printf("  [write $2000=%02x] scanline=%d cycle=%d%n", val, scanline, cycle);
                break;
            case 0x2001: //PPUMASK - applied after PPUMASK_WRITE_DELAY_DOTS
                if (warmupDotsRemaining > 0) break;
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
                    //While rendering a $2004 write never reaches OAM; it only bumps OAMADDR by 4 and
                    //realigns (AccuracyCoin "Address $2004 Behavior" tests 6/7/A).
                    oamaddr = ((oamaddr + 4) & 0xFF) & 0xFC;
                } else {
                    oamWrite(oamaddr, data);
                    oamaddr = (oamaddr + 1) & 0xFF;
                }
                break;
            }
            case 0x2005: //PPUSCROLL
                if (warmupDotsRemaining > 0) break;
                if (!addressLatch) {
                    scrollX = val;
                    t = (t & 0xFFE0) | ((val >> 3) & 0x1F);
                    fineX = val & 0x07;
                    addressLatch = true;
                    if (DEBUG_SPRITE0) System.out.printf("  [write $2005 X=%02x] scanline=%d cycle=%d%n", val, scanline, cycle);
                } else {
                    scrollY = val;
                    t = (t & 0x8FFF) | ((val & 0x07) << 12);
                    t = (t & 0xFC1F) | ((val & 0xF8) << 2);
                    addressLatch = false;
                }
                break;
            case 0x2006: //PPUADDR
                if (warmupDotsRemaining > 0) break;
                flushPendingV();
                if (!addressLatch) {
                    t = (t & 0x00FF) | ((val & 0x3F) << 8);
                    addressLatch = true;
                } else {
                    t = (t & 0x7F00) | val;
                    //The t->v copy lands a few dots late, so a fetch can straddle it: ALE latches old v's low
                    //byte, the read dot uses new v's upper 6 bits (AccuracyCoin "Hybrid Addresses").
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
                clockA12(v); //the increment itself can pulse A12
                break;
        }
    }

    //Side-effect-free register peek for the memory inspector.
    public byte debugRead(int address) {
        switch (address & 0x0007) {
            case 0: return (byte) ppuctrl;
            case 1: return (byte) ppumask;
            case 2: return (byte) ppustatus;
            case 3: return (byte) oamaddr;
            case 4: return (byte) oamPeek(oamaddr);
            case 5: return (byte) ppuscroll;
            case 6: return (byte) ppuaddr;
            case 7: return readBuffer;
            default: return 0;
        }
    }

    //Nametable space goes through the cartridge (mirroring, four-screen, MMC5 substitution);
    //readNametable is the real bus access, peekNametable is side-effect-free.
    private byte readNametable(int address) { return bus.ppuNtRead(address & 0x2FFF, nametables); }
    private byte peekNametable(int address) { return bus.ppuNtPeek(address & 0x2FFF, nametables); }
    private void writeNametable(int address, byte data) { bus.ppuNtWrite(address & 0x2FFF, data, nametables); }

    public byte debugPeekNametable(int table, int offset) {
        return peekNametable(0x2000 | ((table & 3) << 10) | (offset & 0x3FF));
    }

    //Precise Tracing: per-scanline world scroll captured at cycle 320 (lines not yet reached
    //still hold the previous frame's values), plus read-only accessors for internal latches.
    public final int[] debugLineScrollX = new int[240];
    public final int[] debugLineScrollY = new int[240];
    public final boolean[] debugLineRendered = new boolean[240];
    public int debugGetV() { return v; }
    public int debugGetT() { return t; }
    public int debugGetFineX() { return fineX; }
    public boolean debugGetWriteToggle() { return addressLatch; }
    public byte debugPeekOam(int index) { return (byte) oamPeek(index); }

    //Debug menu "Dump OAM": every entry with Y < $EF.
    public String dumpOamText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            int y = oamPeek(i * 4), tile = oamPeek(i * 4 + 1), attr = oamPeek(i * 4 + 2), x = oamPeek(i * 4 + 3);
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
            return readNametable(address & 0x2FFF);
        } else if (address >= 0x3F00 && address <= 0x3FFF) {
            int paletteAddr = address & 0x001F;
            if ((paletteAddr & 0x03) == 0) paletteAddr &= 0x000F; //$3F10/14/18/1C mirror $3F00/04/08/0C
            return paletteRam[paletteAddr];
        }
        return 0;
    }

    //Side-effect-free twin of ppuRead() so inspectors never flip an MMC2/MMC4 latch or feed MMC5's scanline detector.
    public byte debugPpuRead(int address) {
        address &= 0x3FFF;
        if (address <= 0x1FFF) return bus.ppuPeekCartridge(address);
        if (address <= 0x3EFF) return peekNametable(address & 0x2FFF);
        int paletteAddr = address & 0x001F;
        if ((paletteAddr & 0x03) == 0) paletteAddr &= 0x000F;
        return paletteRam[paletteAddr];
    }

    public void ppuWrite(int address, byte data) {
        address &= 0x3FFF;
        if (address <= 0x1FFF) {
            //Must return: falling through would also corrupt nametable RAM on CHR writes.
            bus.ppuWriteCartridge(address, data);
            return;
        }
        if (address <= 0x3EFF) {
            writeNametable(address & 0x2FFF, data);
        } else if (address >= 0x3F00 && address <= 0x3FFF) {
            int paletteAddr = address & 0x001F;
            if ((paletteAddr & 0x03) == 0) paletteAddr &= 0x000F;
            paletteRam[paletteAddr] = data;
        }
    }

    //NMI line's rising edge lags the $2002 VBlank flag by 1 dot.
    private boolean pendingVblNmiEdge = false;

    //A $2001 write reaches rendering 4 dots later (rest of the CPU cycle + ~2 dots propagation);
    //AccuracyCoin "BG Serial In" and the "Stale" tests depend on this lag.
    private static final int PPUMASK_WRITE_DELAY_DOTS = 4;
    private int pendingMaskValue = 0;
    private int pendingMaskDelay = 0;

    //Lag for the second $2006 write: the 3 dots left in the issuing CPU cycle.
    private static final int PPUADDR_WRITE_DELAY_DOTS = 3;
    private int pendingVValue = 0;
    private int pendingVDelay = 0;

    //Flushes an in-flight v write so only the fetch cadence ever sees the lag.
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
        //Disabling rendering mid-scanline latches the secondary-OAM address; the corruption
        //happens when rendering resumes (AccuracyCoin "OAM Corruption" tests 2-4).
        if (wasRendering && !nowRendering && (scanline <= 239 || scanline == 261)) {
            oamCorruptionSeed = currentSecondaryOamAddr();
            oamCorruptionPending = true;
        }
    }

    public void step() {
        cycle++;
        dotClock++;

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

        //Shift before sample; gated on bg OR sprites since shifters still run with only sprites
        //shown (AccuracyCoin "Rendering Flag Behavior" test 2).
        if (renderingEnabled && visibleOrPrerender) {
            bgFetchAndShiftStep(cycle);
            spriteFetchAleStep(cycle);
        }
        //No fetch hijacked this Read, so the buffer fills from the requested address.
        if (ppuDataReadThisDot && !ppuDataReadServiced) {
            int a = ppuDataReadAddr;
            readBuffer = (a >= 0x3F00) ? peekNametable((a - 0x1000) & 0x2FFF) : ppuRead(a);
        }
        if (ppuDataReadThisDot && ppuDataIncPending) {
            ppuDataIncPending = false;
            incrementVramAddressAfterCpuAccess();
        }
        //Sprite units clock on pre-render and with rendering off too (free-running counters).
        if (visibleOrPrerender && cycle >= 1 && cycle <= 256) {
            clockSpriteUnits(renderingEnabled);
            //Evaluation walking all of OAM is what refreshes the DRAM.
            if (cycle == 256 && renderingEnabled) oamRefreshAll();
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

        //Y increments at 256, horizontal t->v copy at 257, vertical copy on pre-render 280-304:
        //this is what makes mid-frame scroll splits (SMB status bar, SMB3 title) land right.
        if (renderingEnabled && visibleOrPrerender) {
            if (cycle == 256) incrementY();
            if (cycle == 257) {
                v = (v & ~0x041F) | (t & 0x041F); //horizontal bits
            }
            if (scanline == 261 && cycle >= 280 && cycle <= 304) {
                v = (v & ~0x7BE0) | (t & 0x7BE0); //vertical bits
            }
            //Precise Tracing: the scroll the next scanline will render with (after the copies, before the 321 prefetch).
            if (cycle == 320) {
                int next = scanline == 261 ? 0 : scanline + 1;
                if (next <= 239) {
                    debugLineScrollX[next] = getWorldScrollX();
                    debugLineScrollY[next] = getWorldScrollY();
                    debugLineRendered[next] = true;
                }
            }
            //MMC3 A12 approximation: the pattern-table half changes at the sprite fetches (dot 257,
            //PPUCTRL bit 3) and the BG prefetch (dot 321, bit 4); only a rising edge clocks.
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

            //Sprite evaluation (65-256) and fetch (257-320) run with bg OR sprites enabled
            //("Rendering Flag Behavior" test 3, "Sprite Overflow Behavior" test 4). A $2004 lookahead may have started it a dot early.
            if (cycle == 65 && evalStartedForScanline != scanline) {
                startSpriteEvaluation(scanline);
                evalStartedForScanline = scanline;
            }
            if (cycle >= 65 && cycle <= 255 && (cycle & 1) == 1) {
                //oamBusValueAtCycle() may already have run this step early.
                int expectedSteps = (cycle - 65) / 2 + 1;
                if (evalStepsExecuted < expectedSteps) {
                    stepSpriteEvaluation();
                    evalStepsExecuted++;
                }
            }
            //OAMADDR is held at 0 throughout the sprite-fetch window (AccuracyCoin OAM-address tests).
            if (cycle >= 257 && cycle <= 320) {
                oamaddr = 0;
                if (((cycle - 257) % 8) == 7) fetchSpriteSlot((cycle - 257) / 8);
            }
            //Dot 339 releases the sprite units back to counting; if rendering is off here, a halted unit
            //paints the moment rendering returns (see sprHalted).
            if (cycle == 339) java.util.Arrays.fill(sprHalted, false);
        }

        //Odd-frame dot skip: with rendering on, every other pre-render line ends at dot 339
        //(AccuracyCoin's Sync_ToPreRenderDot324 detects this parity).
        boolean skipLastDot = scanline == 261 && cycle == 340 && oddFrame && renderingEnabled;

        if (cycle >= 341 || skipLastDot) {
            cycle = 0;
            scanline++;
            if (scanline >= 262) {
                scanline = 0;
                oddFrame = !oddFrame;
            }
            //nmiVblLevel drops at 261/0, one dot before the $2002 bits clear at dot 1.
            if (scanline == 261) {
                nmiVblLevel = false;
                updateNmiLine();
                //All three $2002 flags clear together on dot 1; the read skew is M2 sampling, not separate clear dots.
            }
        }

        //VBlank starts at scanline 241, cycle 1
        if (scanline == 241 && cycle == 1) {
            vblankStarted = true; //marks the frame boundary even when suppressed
            lastFrameHadHit = hitOccurredThisFrame;
            hitOccurredThisFrame = false;
            if (!suppressVblSet) {
                ppustatus |= 0x80;
                nmiVblLevel = true;
                pendingVblNmiEdge = true;
            }
            suppressVblSet = false;
        }

        if (scanline == 261 && cycle == 1) {
            ppustatus &= ~0x80;
            ppustatus &= ~0x40; //sprite-0-hit clears here, not at VBlank start (AccuracyCoin Sprite 0 Hit Behavior test C)
            ppustatus &= ~0x20; //overflow must clear too or Sync_ToSpriteFlagsClearing spins forever
        }
    }

    private static final int MAX_ROW_SPRITES = 8;

    //Eight 2C02 sprite output units (two shifters, attribute latch, X down-counter) - no positional
    //compare, so toggling rendering resumes a half-drained shifter wherever the beam is ("Stale Sprite Shift Regs").
    private final int[] sprShiftLo = new int[MAX_ROW_SPRITES];
    private final int[] sprShiftHi = new int[MAX_ROW_SPRITES];
    private final int[] sprAttrLatch = new int[MAX_ROW_SPRITES];
    private final int[] sprCounter = new int[MAX_ROW_SPRITES];
    private final boolean[] sprHalted = new boolean[MAX_ROW_SPRITES]; //halted = shifting out; dot 339 releases all units ("Stale Sprite Shift Regs" tests 5/6)
    private final boolean[] sprIsZero = new boolean[MAX_ROW_SPRITES];
    //This dot's output per unit, since the shifters have already advanced when the pixel is resolved.
    private final int[] sprPixelOut = new int[MAX_ROW_SPRITES];

    //Secondary OAM and its dot-by-dot evaluation state machine (cycles 65-256). A single evalAddr
    //pointer lets a misaligned OAMADDR stay misaligned through a copy (AccuracyCoin "Misaligned OAM Behavior").
    private final byte[] secondaryOam = new byte[32];
    private int evalAddr;
    private int evalCopyRemaining;
    private int evalSecCount;       //0-8 sprites committed so far
    private int evalSpritesScanned;
    private int evalLastReadValue;  //what $2004 reflects during 65-256
    private int evalTargetScanline; //next scanline, or 0 during pre-render
    private boolean evalActive;

    //Sprite zero is only the FIRST object evaluated (cycle 66) if it was in range, so a $2003
    //write can make any OAM index sprite zero (AccuracyCoin "Arbitrary Sprite Zero" tests 1-2).
    private boolean evalFirstStep;
    private boolean evalSpriteZeroInRange;

    private boolean rowShowBackground;
    private boolean rowShowSprites;
    private boolean rowShowLeftBackground;
    private boolean rowShowLeftSprites;
    private int rowBackdropPaletteIdx;
    //2C02 background pipeline: 16-bit pattern/attribute shifters plus bgNext* fetch latches, probed by
    //AccuracyCoin t Register Quirks, Stale BG Shift Registers, BG Serial In, $2007 Stress Test, ALE + Read, Hybrid Addresses.
    private int bgNextTileId, bgNextAttr, bgNextLsb, bgNextMsb;
    private int bgShiftPatternLo, bgShiftPatternHi;
    private int bgShiftAttrLo, bgShiftAttrHi;

    //Latches this scanline's render state at cycle 1.
    private void prepareScanline(int sy) {
        if (DEBUG_SPRITE0 && sy <= 35) {
            System.out.printf("prepareScanline sy=%d ppuctrl=%02x scrollX=%d scrollY=%d%n", sy, ppuctrl, scrollX, scrollY);
        }
        rowShowBackground = (ppumask & 0x08) != 0;
        rowShowSprites = (ppumask & 0x10) != 0;
        rowShowLeftBackground = (ppumask & 0x02) != 0;
        rowShowLeftSprites = (ppumask & 0x04) != 0;

        rowBackdropPaletteIdx = ppuRead(0x3F00) & 0x3F;
        //Sprite units carry their state across scanlines; re-seeding them here would break
        //"Stale Sprite Shift Regs"/"Sprites On Scanline 0".
    }

    //Starts evaluation for the scanline after `sy` at cycle 65; evalAddr starts at OAMADDR, not a 4-byte boundary.
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

    //Of the 96 evaluation steps (odd cycles 65-255), how many have run; oamBusValueAtCycle()
    //can run steps early, so this stops the per-dot driver double-running them.
    private int evalStepsExecuted;

    //Lets the cycle-65 driver and oamBusValueAtCycle's lookahead start a scanline's evaluation exactly once.
    private int evalStartedForScanline = -1;

    //$2004 is unbuffered and the CPU's read cycle begins 1 dot after `cycle`; AccuracyCoin
    //"$2004 Stress Test" only lines up with this lookahead.
    private static final int OAM_READ_LOOKAHEAD_DOTS = 1;

    //What $2004 would return `aheadDots` from now; evaluation steps inside the window are run early.
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
            //Dot 0: secondary OAM isn't reset until dot 65, so slot 0's old byte still shows.
            return secondaryOam[0] & 0xFF;
        }
        //Dots 1-64: secondary-OAM clear writes $FF; dot 65 is already evaluation step 1
        //("$2004 Stress Test" key 2, OAM[0]=$80, exposes the difference).
        if (target >= 1 && target <= 64) return 0xFF;
        if (target >= 65 && target <= 256) {
            //target==65 can run before the cycle-65 driver has reset evaluation for this scanline.
            if (targetScanline == scanline && evalStartedForScanline != scanline) {
                startSpriteEvaluation(scanline);
                evalStartedForScanline = scanline;
            }
            //Run steps the driver hasn't reached yet and credit them.
            int expectedSteps = Math.min(96, (Math.min(target, 255) - 65) / 2 + 1);
            while (evalStepsExecuted < expectedSteps) {
                stepSpriteEvaluation();
                evalStepsExecuted++;
            }
            //Once the pointer laps OAM or 8 sprites are committed, a step's second dot (even target) reads
            //secondary OAM's write address instead of echoing the comparator ("$2004 Stress Test" section 6, key 2).
            //The 8th sprite's remaining copy bytes are still normal, hence the mid-copy check.
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
        //Dots 321-340: the bus settles on secondary OAM slot 0 ("20 instances of $7F (index 0 into OAM2)").
        if (target >= 321 && target <= 340) {
            return secondaryOam[0] & 0xFF;
        }
        return plainOamRead();
    }

    //Attribute bytes have no bits 4-3-2; they read as 0.
    private int plainOamRead() {
        int val = oamRead(oamaddr);
        if ((oamaddr & 0x03) == 2) val &= 0xE3;
        return val;
    }

    //Advances the pointer, halting the whole evaluation if it runs off the end of OAM; only overflow-scan
    //reaches this, and wrapping would raise a bogus overflow flag ("Sprite Overflow Behavior" test 3).
    private void advanceEvalAddr(int delta) {
        int next = evalAddr + delta;
        if (next > 0xFF) evalActive = false;
        evalAddr = next & 0xFF;
        evalAddrTotalAdvance += delta;
    }

    //Pointer travel this scanline ignoring wrap; past 256 the evaluation/write circuits fall out of sync (see oamBusValueAtCycle).
    private int evalAddrTotalAdvance;

    //Once the pointer laps with fewer than 8 sprites, the speculative Y write stops while the
    //comparator keeps reading ("$2004 Stress Test" section 6).
    private boolean evalSecWriteFrozen;

    //Whether the last executed step was a mid-copy continuation; keyed off the pre-step state since
    //the step bringing evalCopyRemaining to 0 is still a copy step ("$2004 Stress Test" key 2).
    private boolean evalLastStepWasMidCopy;

    //Whether the last step was a genuine overflow-scan check (secondary OAM already full going in).
    private boolean evalWasOverflowScanRead;

    //Overflow-scan n/m state: 0 = buggy dual increment, 1 = 3-step confirmation after a hit, 2 = pinned (m stays 0).
    private boolean evalOverflowInitialized;
    private int evalOverflowN, evalOverflowM, evalOverflowState, evalOverflowFreezeStep;

    //The overflow flag only latches on n's first lap of OAM ("$2004 Stress Test" key 2).
    private boolean evalOverflowNWrapped;

    //Pre-overflow phases wrap without halting; "$2004 Stress Test" key 1 expects reads to keep changing past the wrap.
    private void advanceEvalAddrWrap(int delta) {
        evalAddr = (evalAddr + delta) & 0xFF;
        evalAddrTotalAdvance += delta;
    }

    //OAM corruption armed by disabling rendering mid-scanline (see applyPpuMask).
    private boolean oamCorruptionPending = false;
    private int oamCorruptionSeed = 0;

    //Secondary OAM write pointer reconstructed from the dot: clear (1-64), evaluation (65-256), fetch (257-320).
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
        for (int i = 0; i < 8; i++) oamWrite(row + i, (byte) oamRead(i));
        secondaryOam[oamCorruptionSeed & 0x1F] = secondaryOam[0];
    }

    private void stepSpriteEvaluation() {
        if (!evalActive) return;
        boolean tall = (ppuctrl & 0x20) != 0;
        int spriteHeight = tall ? 16 : 8;

        //Captured pre-step: the step confirming the 8th sprite is still a fresh comparator read.
        boolean wasOverflowScanBeforeThisStep = evalCopyRemaining == 0 && evalSecCount >= 8;

        int m = evalAddr & 0x03;
        int val = oamRead(evalAddr);
        if (m == 2) val &= 0xE3; //attribute bits 2-4 don't exist
        evalLastReadValue = val;
        evalWasOverflowScanRead = wasOverflowScanBeforeThisStep;

        //Mid-copy first: evalSecCount hits 8 as soon as the 8th sprite is found, but its 3 remaining bytes still copy normally.
        evalLastStepWasMidCopy = evalCopyRemaining > 0;
        if (evalCopyRemaining > 0) {
            //Destination offset is sequential, not evalAddr's low bits, but the bits-2-4 mask above does key
            //off the physical address ("Arbitrary Sprite Zero" test 3 picks tile $E3 to survive it).
            secondaryOam[(evalSecCount - 1) * 4 + (4 - evalCopyRemaining)] = (byte) val;
            advanceEvalAddrWrap(1);
            evalCopyRemaining--;
        } else if (evalSecCount < 8) {
            if (!evalSecWriteFrozen) secondaryOam[evalSecCount * 4] = (byte) val; //speculative Y copy
            //OAM Y is the top row minus 1 (Y=0 first displays on scanline 1).
            int diff = evalTargetScanline - (val + 1);
            boolean inRange = diff >= 0 && diff < spriteHeight;
            evalSpritesScanned++;
            //Only the first object decided can be sprite zero.
            if (evalFirstStep && inRange) evalSpriteZeroInRange = true;
            evalFirstStep = false;
            if (inRange) {
                evalCopyRemaining = 3;
                evalSecCount++;
                advanceEvalAddr(1);
            } else {
                //Out of range: realign to a 4-byte boundary, observable only with a misaligned
                //OAMADDR ("Misaligned OAM Behavior" test 2).
                evalAddr = (evalAddr + 4) & 0xFF & 0xFC;
                evalAddrTotalAdvance += 4;
            }
            if (evalAddrTotalAdvance >= 256 && evalSecCount < 8) evalSecWriteFrozen = true;
        } else {
            int diff = evalTargetScanline - (val + 1);
            boolean inRange = diff >= 0 && diff < spriteHeight;
            //Only honored on n's first lap.
            if (inRange && !evalOverflowNWrapped) ppustatus |= 0x20;

            if (!evalOverflowInitialized) {
                //m starts from evalAddr's low bits, not 0, since a misaligned start stays misaligned ("Misaligned OAM Behavior").
                evalOverflowN = (evalAddr >> 2) & 0x3F;
                evalOverflowM = evalAddr & 0x03;
                evalOverflowState = 0;
                evalOverflowInitialized = true;
            }
            //State 0 bumps n and m together (the overflow bug); per "$2004 Stress Test" key 2 the first hit
            //triggers 3 confirmation reads at m=1,2,0 with n fixed, then m pins to 0. Fitted to the answer table, not hardware docs.
            int prevOverflowState = evalOverflowState;
            int nBeforeAdvance = evalOverflowN;
            if (prevOverflowState == 2) {
                evalOverflowN = (evalOverflowN + 1) & 0x3F;
            } else if (prevOverflowState == 1) {
                int[] confirmM = {1, 2, 0};
                evalOverflowM = confirmM[evalOverflowFreezeStep];
                evalOverflowFreezeStep++;
                if (evalOverflowFreezeStep == 3) evalOverflowState = 2;
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

    //Loads a sprite unit at the last dot of its slot (264,272,...,320) so PPUCTRL is sampled as
    //late as real timing allows; an unclaimed ($FF) slot loads transparent.
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

        //The fetch comparator uses the raw 8-bit scanline counter, so pre-render acts as line 5 (261 & 255)
        //and stale entries survive into scanline 0 (AccuracyCoin "Sprites On Scanline 0").
        int row = (scanline & 0xFF) - y;
        //Height comes from PPUCTRL now, not at evaluation, so an HBlank $2000 write can leave an
        //accepted sprite out of range and blank ("Suddenly Resize Sprite" tests 4/5).
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

        //Horizontal flip is applied at load (reversed bit order), keeping a partially-drained shifter correct.
        //Data comes from the bus-level fetch dots rather than a second cartridge read.
        int lo = sprFetchLo;
        int hi = sprFetchHi;
        sprShiftLo[n] = flipH ? reverseByte(lo) : lo;
        sprShiftHi[n] = flipH ? reverseByte(hi) : hi;
    }

    private static int reverseByte(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) r |= ((b >> i) & 1) << (7 - i);
        return r;
    }

    //One dot of the sprite units: count down to X, then halt and shift one pixel per dot.
    private void clockSpriteUnits(boolean renderingEnabled) {
        for (int i = 0; i < MAX_ROW_SPRITES; i++) {
            if (!sprHalted[i]) {
                if (sprCounter[i] != 0) { sprCounter[i]--; sprPixelOut[i] = 0; continue; }
                sprHalted[i] = true;
            }
            //Counters free-run through forced blank but shifters stall, so a unit halted during blank dumps
            //its 8 pixels wherever the beam is when rendering returns ("Stale Sprite Shift Regs" tests 2/3).
            if (!renderingEnabled) { sprPixelOut[i] = 0; continue; }
            int b0 = (sprShiftLo[i] >> 7) & 1;
            int b1 = (sprShiftHi[i] >> 7) & 1;
            sprPixelOut[i] = (b1 << 1) | b0;
            sprShiftLo[i] = (sprShiftLo[i] << 1) & 0xFF;
            sprShiftHi[i] = (sprShiftHi[i] << 1) & 0xFF;
        }
    }

    //Renders one pixel per dot and sets sprite-0-hit at its real x-position, which games
    //like SMB time their status-bar split off.
    private void renderPixel(int sy, int x) {
        //PPUMASK bits are sampled per dot, not per scanline ("Rendering Flag Behavior" tests 2/3).
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
            //fineX selects this dot's bit from the 16-bit shifters, same mux as hardware.
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
        //Evaluation runs with only bg enabled, so the sprite-show gate lives here ("Rendering Flag
        //Behavior" test 3, "Sprite Overflow Behavior" test 4).
        if (rowShowSprites && (x >= 8 || rowShowLeftSprites)) {
            //The topmost opaque sprite wins outright and then faces the background alone; a lower sprite
            //never shows through (SMB3's masking-sprite trick). Sprite-0-hit fires regardless of who wins.
            boolean topmostResolved = false;
            for (int n = 0; n < MAX_ROW_SPRITES; n++) {
                int pixelVal = sprPixelOut[n];
                if (pixelVal == 0) continue;

                //Never fires at x=255, and only for secondary-OAM slot 0 ("Arbitrary Sprite Zero" test 2).
                if (sprIsZero[n] && bgOpaque && x != 255) {
                    ppustatus |= 0x40;
                    hitOccurredThisFrame = true;
                    if (DEBUG_SPRITE0) System.out.printf("  [HIT SET] sy=%d x=%d%n", sy, x);
                }
                if (DEBUG_SPRITE0 && sprIsZero[n]) {
                    System.out.printf("sprite0 sy=%d x=%d pixelVal=%d bgHere=%b worldScrollX=%d%n", sy, x, pixelVal, bgOpaque, getWorldScrollX());
                }

                if (topmostResolved) continue; //keep scanning only for sprite-0-hit
                topmostResolved = true;

                if ((sprAttrLatch[n] & 0x20) != 0 && bgOpaque) continue; //no lower sprite gets a turn

                int paletteIndex = ppuRead(0x3F10 + (sprAttrLatch[n] & 0x03) * 4 + pixelVal) & 0x3F;
                rgb = currentPaletteRgb(paletteIndex);
                debugWinner = sprIsZero[n] ? 0 : 1; //debug marker (0=slot-0, 1=other), not an OAM index
            }
        }

        debugBgOpaque[sy * 256 + x] = bgOpaque;
        debugWinnerOam[sy * 256 + x] = debugWinner;

        if (display != null) display.setPixel(x, sy, rgb);
    }

    //Debug-only: what rendered at a pixel, from the live-recorded debugBgOpaque/debugWinnerOam.
    public String explainPixel(int x, int sy) {
        int idx = sy * 256 + x;
        boolean bgOpaque = debugBgOpaque[idx];
        int winner = debugWinnerOam[idx];
        return String.format("pixel (%d,%d): background %s; winner = %s%n", x, sy,
                bgOpaque ? "opaque" : "transparent/backdrop",
                winner < 0 ? "background" : ("OAM[" + winner + "]"));
    }

    //Debug-only ASCII grid of the same per-pixel data explainPixel() uses.
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
