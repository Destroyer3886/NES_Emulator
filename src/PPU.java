public class PPU {

    public static boolean DEBUG_SPRITE0 = false;

    //How many PPU dots from right now until the pre-render line's flag-clear point
    //(scanline 261, dot 0), or a large number if that point has already passed this
    //frame. Used only by the $2002 read path to model the ~2-dot sampling skew
    //between the latched VBlank flag and the unlatched sprite flags.
    private int dotsUntilPreRenderFlagClear() {
        if (scanline == 261 && cycle == 0) return 0;
        if (scanline == 260) return 341 - cycle;
        return Integer.MAX_VALUE;
    }

    //Debug-only: records, live as each pixel actually renders, whether the
    //background was opaque there and which OAM index (if any) won the pixel - see
    //renderPixel()'s recording line at the bottom and explainGrid()'s use of these.
    //MMC3 games routinely swap CHR banks mid-frame (e.g. SMB3's status-bar split),
    //so re-deriving a paused frame's content from scratch afterward would use
    //whatever CHR bank happened to be selected LAST, not the bank each scanline
    //actually used when it rendered - silently producing wrong (often blank)
    //pattern data for every row except the last one written. Recording the real,
    //historically-correct decision at the moment it's made avoids that entirely.
    private final boolean[] debugBgOpaque = new boolean[256 * 240];
    private final int[] debugWinnerOam = new int[256 * 240]; //-1 = background/backdrop won

    // Standard NES System Palette (64 Colors)
    public static final int[] NES_PALETTE = {
            0x7C7C7C, 0x002492, 0x0000DB, 0x6800D4, 0x7C00A0, 0x6C0040, 0x5C0000, 0x501800,
            0x3C2400, 0x004000, 0x003C00, 0x00382C, 0x002C7C, 0x000000, 0x000000, 0x000000,
            0xBCBCBC, 0x0073F8, 0x0050F8, 0x6B00F8, 0x9B00F8, 0xAE00B8, 0xB7004C, 0x980000,
            0x703800, 0x006C00, 0x006C00, 0x006000, 0x00501C, 0x000000, 0x000000, 0x000000,
            0xF8F8F8, 0x3CBCFC, 0x6888FC, 0x9878FC, 0xD878FC, 0xF878F8, 0xF85898, 0xF87858,
            0xF8A000, 0x00B800, 0x00F800, 0x00F878, 0x00F8D8, 0x000000, 0x000000, 0x000000,
            0xF8F8F8, 0xA4E4FC, 0xB8B8FC, 0xD8B8FC, 0xF8B8FC, 0xF8B8F8, 0xF8A4C0, 0xF0D0B0,
            0xFCE0A8, 0xB8F8B8, 0xB8F8D8, 0x00F8F8, 0x00D8F8, 0x000000, 0x000000, 0x000000
    };

    //PPU Internal Memory. Sized for the four-screen case (4 independent 1KB
    //nametables) even though the real PPU only has 2KB of on-board VRAM for the
    //common (non four-screen) mirroring modes - mapNametableAddress() below decides
    //how many of the 4 physical 1KB slots are actually distinct for the cartridge's
    //current mirror mode, and cartridges that need all 4 (four-screen mapper 4
    //boards) just use the other 2KB this array already has spare.
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

    //Internal scroll/address latches, modeled the way real hardware actually does it
    //("loopy" registers) rather than as raw PPUSCROLL bytes. v is the current VRAM
    //address (also what $2007 reads/writes through) and doubles as the scroll
    //position; t is the staging register that $2000/$2005/$2006 writes accumulate
    //into; x is fine X scroll; w is the shared write toggle both $2005 and $2006 use.
    //Each register is 15 bits: yyy NN YYYYY XXXXX (fine Y, nametable select, coarse Y,
    //coarse X). Modeling it this way - instead of independent scrollX/scrollY bytes -
    //is what makes mid-frame scroll-split tricks (SMB's status bar) come out right:
    //v only picks up t's horizontal bits at cycle 257 of the *previous* scanline and
    //its vertical bits during the pre-render line, exactly matching when real hardware
    //latches a new scroll into the active picture, rather than applying every $2005
    //write immediately.
    private boolean addressLatch = false; //w
    private int v = 0x0000;
    private int t = 0x0000;

    //True A12 line state, tracked for CPU-side PPU-bus accesses ($2006 address-set,
    //$2007 read/write) that move the address bus outside of the per-pixel renderer's
    //own scanline approximation below. MMC3's IRQ counter is clocked on A12's 0->1
    //rising edge; blargg's mmc3_irq_tests exercise this directly by toggling $2006 to
    //move the VRAM address across the $1000 boundary with rendering off, which the
    //once-per-scanline approximation (see step()'s cycle==260 site) can't reproduce.
    private boolean a12 = false;

    //Separate A12 state for the rendering-fetch approximation (see step()'s
    //cycle==257/321 sites) so a game/test that leaves rendering enabled while also
    //directly manipulating the PPU address bus via $2006/$2007 doesn't have the two
    //mechanisms fight over one shared "current A12 level" - each infers A12 from a
    //different, unrelated signal (actual bus address vs. which pattern-table half
    //ppuctrl currently selects), so conflating them produced spurious edges (traced
    //via mmc3_irq_tests: a manual test loop re-reading a fixed $2007 address kept
    //getting extra clocks every time the rendering approximation reset the shared
    //state back to "low" on an unrelated scanline boundary).
    private boolean renderA12 = false;

    //Called with the actual PPU address bus value on every access outside of
    //rendering (see call sites in cpuWrite/cpuRead below). Clocks the mapper IRQ
    //counter on A12's low-to-high transition, matching real hardware.
    private void clockA12(int address) {
        boolean newA12 = (address & 0x1000) != 0;
        if (newA12 && !a12 && bus != null) {
            bus.clockMapperIRQ();
        }
        a12 = newA12;
    }
    private int fineX = 0;
    private byte readBuffer = 0x00;

    //Floating PPU data bus ($2xxx <-> CPU): every register access, read or write,
    //leaves its value latched here. Reading a write-only register (or the unused low
    //5 bits of $2002) returns this instead of a fixed 0, matching real hardware.
    private int ppuDataBus = 0x00;
    //Real hardware's PPU data bus is a capacitor that slowly discharges once nothing
    //is actively driving it, decaying to 0 within well under 1 second if left alone -
    //AccuracyCoin's "PPU Register Open Bus" test 5 checks it decays before that.
    //~5,369,318 PPU dots/second on NTSC (341*262*60.0988); any write to ppuDataBus
    //resets this back to a comfortably-under-1-second budget.
    private static final int PPU_BUS_DECAY_TICKS = 4_500_000;
    private int ppuDataBusDecay = PPU_BUS_DECAY_TICKS;

    //Raw PPUSCROLL byte values, kept only for the DEBUG_SPRITE0 trace printouts.
    private int scrollX = 0;
    private int scrollY = 0;

    public int cycle = 0;
    public int scanline = 0;

    //Debug-only: whether sprite-0-hit fired at any point during the frame that just
    //ended, captured right before ppustatus's hit bit is cleared for the new frame
    //(see step()). Exists because sampling ppustatus's live bit right after VBlank
    //starts is meaningless - it's already been cleared for the *next* frame by then.
    public boolean lastFrameHadHit = false;
    private boolean hitOccurredThisFrame = false;

    //Set for one PPU tick at the start of VBlank (scanline 241, cycle 1 - the same
    //moment raiseNMI() fires). The main loop polls this to bound each "frame" of CPU
    //execution instead of running a fixed CPU-cycle count, so it can never drift out
    //of phase with the PPU's own continuous scanline/cycle counters - a fixed count
    //doesn't evenly divide a real NTSC frame (89341/89342 PPU dots), so it used to
    //slowly desync NMI timing from the CPU's notion of "one frame".
    //
    //Bounding on VBlank-start rather than the scanline-262 wrap also matches how NES
    //games structure their own main loop (they treat NMI as "start of frame" and
    //spend most of the frame - the visible scanlines - between one NMI and the next,
    //hitting their "wait for VBlank" poll only right before the next NMI). Slicing the
    //trace log the same way means a frame's trace starts right after NMI, instead of
    //starting mid-VBlank and showing the wait-loop exit near the very end of the slice.
    //The real NMI line is level-sensitive AND(VBlank flag, PPUCTRL bit 7) - the CPU's
    //own NMI input is edge-sensitive on that line's 0->1 transition. Tracking only
    //"did VBlank just start AND was NMI already enabled at that instant" (as this
    //code used to) misses every other way that same transition can happen: enabling
    //NMI via a $2000 write while the VBlank flag is already set (AccuracyCoin's "NMI
    //Control" test 3), or disabling and re-enabling within the same VBlank (test 7).
    //nmiLine mirrors the AND's last known value; updateNmiLine() re-evaluates it
    //(and fires cpu.raiseNMI() on a 0->1 edge) from every place that can change either
    //of its two inputs: VBlank set/clear (below) and PPUCTRL writes (cpuWrite).
    private boolean nmiLine = false;
    //Mirrors ppustatus's VBlank bit for NMI-line purposes only, but clears 1 PPU dot
    //earlier than the CPU-visible $2002 bit (see the scanline==261,cycle==0 site below) -
    //empirically matches AccuracyCoin's "NMI at VBlank End" expected results, which
    //transition 1 dot earlier than "VBlank End"'s own $2002-read-based table despite
    //both being calibrated to the same ~2273-cycle target.
    private boolean nmiVblLevel = false;
    private void updateNmiLine() {
        boolean newLine = nmiVblLevel && ((ppuctrl & 0x80) != 0);
        if (newLine && !nmiLine && cpu != null) cpu.raiseNMI();
        nmiLine = newLine;
    }

    //Set when $2002 is read on the exact PPU cycle immediately preceding the one
    //that would set the VBlank flag (scanline 241, cycle 0 - the CPU's own read
    //always happens before this iteration's PPU ticks run, so "read here" means
    //the read landed 1 PPU cycle ahead of the flag-set tick). Per AccuracyCoin's
    //"VBlank Beginning" test (case A=4) and "NMI Suppression" test, a read that
    //precisely straddles the flag-set tick this way suppresses the flag from being
    //set at all for the rest of this VBlank - and since the NMI line is derived from
    //the flag, that also means no NMI fires this frame.
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

    //Full power-on reset: everything reset() clears, plus VRAM/OAM/palette RAM and
    //the read buffer/data bus - a real NES reset button leaves those alone, but the
    //TAS Maker's "replay from power-on" seeking needs every repeated replay of the
    //same input log to land on the exact same state, which requires starting from a
    //truly blank PPU rather than whatever VRAM a previous replay happened to leave behind.
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
    }

    //TAS Maker greenzone checkpoint support - see CPU.State's comment for why this is
    //only ever captured/restored at a CPU instruction boundary. The row* fields are
    //transient per-scanline rendering scratch (recomputed at the start of each
    //scanline before use), so they're technically redundant to snapshot, but they're
    //cheap (8-element arrays) and capturing them anyway avoids relying on that being
    //true forever as rendering code changes.
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
        boolean nmiLine, nmiVblLevel, suppressVblSet, vblankStarted, pendingVblNmiEdge;
        int[] rowSpriteX, rowSpritePlane0, rowSpritePlane1, rowSpritePaletteGroup;
        boolean[] rowSpriteFlipH, rowSpriteBehind, rowSpriteIsZero;
        int rowSpriteCount;
        boolean rowShowBackground, rowShowSprites, rowShowLeftBackground, rowShowLeftSprites;
        int rowBackdropRgb;
        int bgNextTileId, bgNextAttr, bgNextLsb, bgNextMsb;
        int bgShiftPatternLo, bgShiftPatternHi, bgShiftAttrLo, bgShiftAttrHi;
        byte[] secondaryOam;
        int evalAddr, evalCopyRemaining, evalSecCount, evalSpritesScanned, evalLastReadValue, evalTargetScanline;
        boolean evalActive;
        boolean[] nextSpriteIsZero, nextSpriteFlipH, nextSpriteBehind;
        int[] nextSpriteX, nextSpritePlane0, nextSpritePlane1, nextSpritePaletteGroup;
        int nextSpriteCount;
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
        s.nmiLine = nmiLine; s.nmiVblLevel = nmiVblLevel; s.suppressVblSet = suppressVblSet;
        s.vblankStarted = vblankStarted; s.pendingVblNmiEdge = pendingVblNmiEdge;
        s.rowSpriteX = rowSpriteX.clone();
        s.rowSpritePlane0 = rowSpritePlane0.clone();
        s.rowSpritePlane1 = rowSpritePlane1.clone();
        s.rowSpritePaletteGroup = rowSpritePaletteGroup.clone();
        s.rowSpriteFlipH = rowSpriteFlipH.clone();
        s.rowSpriteBehind = rowSpriteBehind.clone();
        s.rowSpriteIsZero = rowSpriteIsZero.clone();
        s.rowSpriteCount = rowSpriteCount;
        s.rowShowBackground = rowShowBackground; s.rowShowSprites = rowShowSprites;
        s.rowShowLeftBackground = rowShowLeftBackground; s.rowShowLeftSprites = rowShowLeftSprites;
        s.rowBackdropRgb = rowBackdropRgb;
        s.bgNextTileId = bgNextTileId; s.bgNextAttr = bgNextAttr;
        s.bgNextLsb = bgNextLsb; s.bgNextMsb = bgNextMsb;
        s.bgShiftPatternLo = bgShiftPatternLo; s.bgShiftPatternHi = bgShiftPatternHi;
        s.bgShiftAttrLo = bgShiftAttrLo; s.bgShiftAttrHi = bgShiftAttrHi;
        s.secondaryOam = secondaryOam.clone();
        s.evalAddr = evalAddr; s.evalCopyRemaining = evalCopyRemaining; s.evalSecCount = evalSecCount;
        s.evalSpritesScanned = evalSpritesScanned; s.evalLastReadValue = evalLastReadValue;
        s.evalTargetScanline = evalTargetScanline; s.evalActive = evalActive;
        s.nextSpriteIsZero = nextSpriteIsZero.clone();
        s.nextSpriteX = nextSpriteX.clone();
        s.nextSpritePlane0 = nextSpritePlane0.clone();
        s.nextSpritePlane1 = nextSpritePlane1.clone();
        s.nextSpriteFlipH = nextSpriteFlipH.clone();
        s.nextSpriteBehind = nextSpriteBehind.clone();
        s.nextSpritePaletteGroup = nextSpritePaletteGroup.clone();
        s.nextSpriteCount = nextSpriteCount;
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
        nmiLine = s.nmiLine; nmiVblLevel = s.nmiVblLevel; suppressVblSet = s.suppressVblSet;
        vblankStarted = s.vblankStarted; pendingVblNmiEdge = s.pendingVblNmiEdge;
        System.arraycopy(s.rowSpriteX, 0, rowSpriteX, 0, rowSpriteX.length);
        System.arraycopy(s.rowSpritePlane0, 0, rowSpritePlane0, 0, rowSpritePlane0.length);
        System.arraycopy(s.rowSpritePlane1, 0, rowSpritePlane1, 0, rowSpritePlane1.length);
        System.arraycopy(s.rowSpritePaletteGroup, 0, rowSpritePaletteGroup, 0, rowSpritePaletteGroup.length);
        System.arraycopy(s.rowSpriteFlipH, 0, rowSpriteFlipH, 0, rowSpriteFlipH.length);
        System.arraycopy(s.rowSpriteBehind, 0, rowSpriteBehind, 0, rowSpriteBehind.length);
        System.arraycopy(s.rowSpriteIsZero, 0, rowSpriteIsZero, 0, rowSpriteIsZero.length);
        rowSpriteCount = s.rowSpriteCount;
        rowShowBackground = s.rowShowBackground; rowShowSprites = s.rowShowSprites;
        rowShowLeftBackground = s.rowShowLeftBackground; rowShowLeftSprites = s.rowShowLeftSprites;
        rowBackdropRgb = s.rowBackdropRgb;
        bgNextTileId = s.bgNextTileId; bgNextAttr = s.bgNextAttr;
        bgNextLsb = s.bgNextLsb; bgNextMsb = s.bgNextMsb;
        bgShiftPatternLo = s.bgShiftPatternLo; bgShiftPatternHi = s.bgShiftPatternHi;
        bgShiftAttrLo = s.bgShiftAttrLo; bgShiftAttrHi = s.bgShiftAttrHi;
        System.arraycopy(s.secondaryOam, 0, secondaryOam, 0, secondaryOam.length);
        evalAddr = s.evalAddr; evalCopyRemaining = s.evalCopyRemaining; evalSecCount = s.evalSecCount;
        evalSpritesScanned = s.evalSpritesScanned; evalLastReadValue = s.evalLastReadValue;
        evalTargetScanline = s.evalTargetScanline; evalActive = s.evalActive;
        System.arraycopy(s.nextSpriteIsZero, 0, nextSpriteIsZero, 0, nextSpriteIsZero.length);
        System.arraycopy(s.nextSpriteX, 0, nextSpriteX, 0, nextSpriteX.length);
        System.arraycopy(s.nextSpritePlane0, 0, nextSpritePlane0, 0, nextSpritePlane0.length);
        System.arraycopy(s.nextSpritePlane1, 0, nextSpritePlane1, 0, nextSpritePlane1.length);
        System.arraycopy(s.nextSpriteFlipH, 0, nextSpriteFlipH, 0, nextSpriteFlipH.length);
        System.arraycopy(s.nextSpriteBehind, 0, nextSpriteBehind, 0, nextSpriteBehind.length);
        System.arraycopy(s.nextSpritePaletteGroup, 0, nextSpritePaletteGroup, 0, nextSpritePaletteGroup.length);
        nextSpriteCount = s.nextSpriteCount;
    }

    //World-space (512x480, matching NESDisplayPanel's tiled-nametable buffer) scroll
    //position derived from the current v register + fine X: bit 10 of v is the
    //horizontal nametable-select bit (which of the 2 world halves scrolling starts
    //from), coarse X (bits 0-4) counts 8px tile steps within that half, fine X is the
    //sub-tile pixel offset.
    //Standard "loopy" Y-increment, run once per rendering-enabled scanline at dot 256
    //(see step()'s call site). Fine Y counts 0-7 within a tile row; when it wraps,
    //coarse Y advances, and coarse Y wrapping past the last real nametable row (29)
    //flips which physical nametable half v's Y side points at (bit 11) instead of
    //just wrapping numerically - coarse Y values 30-31 are the "attribute table"
    //rows some games briefly scroll into, which wrap without flipping the half.
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

    //Coarse-X companion to incrementY() - run once every 8 dots (cycle%8==0) during
    //the background fetch cadence, matching real hardware's per-tile horizontal step.
    //Coarse X (bits 0-4) wrapping past 31 flips which physical nametable half v's X
    //side points at (bit 10), same idea as incrementY()'s bit-11 flip.
    private void incrementScrollX() {
        if ((v & 0x001F) == 31) {
            v &= ~0x001F;
            v ^= 0x0400;
        } else {
            v++;
        }
    }

    //Shifts all 4 background shift registers left by 1, called once per rendering
    //dot (see bgFetchAndShiftStep). Real hardware's BG pattern shift registers have
    //their serial input tied high, not low - AccuracyCoin's "BG Serial In" test 2
    //checks exactly this: shifting should bring a 1 into bit 0, not a 0 - which only
    //becomes observable when rendering continues without fresh tile data behind it
    //(e.g. carefully timed $2001 writes that skip a reload).
    private void updateBgShifters() {
        bgShiftPatternLo = (bgShiftPatternLo << 1) | 1;
        bgShiftPatternHi = (bgShiftPatternHi << 1) | 1;
        bgShiftAttrLo = (bgShiftAttrLo << 1) & 0xFFFF;
        bgShiftAttrHi = (bgShiftAttrHi << 1) & 0xFFFF;
    }

    //Loads the just-fetched tile (bgNext*) into the low byte of each shift register -
    //called every 8 dots, right as a new tile's high pattern byte fetch completes
    //(see bgFetchAndShiftStep's case 0). The attribute shifters get a full byte of
    //all-0s or all-1s per bit (rather than the raw 2-bit group) so the same per-dot
    //bitMux extraction used for the pattern shifters also works for palette selection.
    private void loadBgShifters() {
        bgShiftPatternLo = (bgShiftPatternLo & 0xFF00) | bgNextLsb;
        bgShiftPatternHi = (bgShiftPatternHi & 0xFF00) | bgNextMsb;
        bgShiftAttrLo = (bgShiftAttrLo & 0xFF00) | (((bgNextAttr & 0x01) != 0) ? 0xFF : 0x00);
        bgShiftAttrHi = (bgShiftAttrHi & 0xFF00) | (((bgNextAttr & 0x02) != 0) ? 0xFF : 0x00);
    }

    //Drives the real per-dot background fetch/shift pipeline - called from step()
    //for every dot of a rendering-enabled visible/pre-render scanline, BEFORE that
    //dot's pixel is composited (renderPixel reads the shifters after this runs, so
    //it sees this dot's own shift, not the previous dot's - matching real hardware's
    //"shift, then sample" order within a single dot).
    //
    //Fetches happen in the standard 8-dot cadence during dots 1-256 (this scanline's
    //32 visible tiles) and 321-336 (prefetching the first 2 tiles of the NEXT
    //scanline, already latched into v by the dot-257 horizontal copy / dot-280-304
    //vertical copy): nametable byte (dots 1-2 of the group), attribute byte (3-4),
    //pattern low byte (5-6), pattern high byte (7-8), with the shifters reloaded and
    //coarse X incremented right after the high byte fetch (dot 8 of the group). Dots
    //337 and 339 perform the two harmless "dummy" nametable fetches real hardware
    //does at the end of every scanline (their result is never used - the very next
    //real fetch, at dot 1 of the next scanline, overwrites bgNextTileId again).
    private void bgFetchAndShiftStep(int cycle) {
        if ((cycle >= 2 && cycle <= 257) || (cycle >= 321 && cycle <= 337)) {
            updateBgShifters();
            switch ((cycle - 1) % 8) {
                case 0:
                    loadBgShifters();
                    bgNextTileId = ppuRead(0x2000 | (v & 0x0FFF)) & 0xFF;
                    break;
                case 2: {
                    int attrAddr = 0x23C0 | (v & 0x0C00) | ((v >> 4) & 0x38) | ((v >> 2) & 0x07);
                    int attr = ppuRead(attrAddr) & 0xFF;
                    if ((v & 0x0040) != 0) attr >>= 4; //coarse Y bit 1 selects the bottom half of the attribute byte
                    if ((v & 0x0002) != 0) attr >>= 2; //coarse X bit 1 selects the right half
                    bgNextAttr = attr & 0x03;
                    break;
                }
                case 4: {
                    int bgTableOffset = ((ppuctrl & 0x10) != 0) ? 0x1000 : 0x0000;
                    int fineY = (v >> 12) & 0x07;
                    bgNextLsb = ppuRead(bgTableOffset + (bgNextTileId * 16) + fineY) & 0xFF;
                    break;
                }
                case 6: {
                    int bgTableOffset = ((ppuctrl & 0x10) != 0) ? 0x1000 : 0x0000;
                    int fineY = (v >> 12) & 0x07;
                    bgNextMsb = ppuRead(bgTableOffset + (bgNextTileId * 16) + fineY + 8) & 0xFF;
                    break;
                }
                case 7:
                    incrementScrollX();
                    break;
            }
        } else if (cycle == 339) {
            bgNextTileId = ppuRead(0x2000 | (v & 0x0FFF)) & 0xFF;
        }
    }

    public int getWorldScrollX() {
        int base = ((v & 0x0400) != 0) ? 256 : 0;
        return (base + ((v & 0x001F) * 8) + fineX) & 0x1FF; //mod 512 (world width)
    }

    //Same idea vertically: bit 11 of v selects the world half, coarse Y (bits 5-9)
    //counts 8px tile steps, fine Y (bits 12-14) is the sub-tile pixel offset.
    public int getWorldScrollY() {
        int base = ((v & 0x0800) != 0) ? 240 : 0;
        int coarseY = (v >> 5) & 0x1F;
        int fineY = (v >> 12) & 0x07;
        return (base + coarseY * 8 + fineY) % 480; //mod 480 (world height)
    }

    //The $2007 auto-increment after a CPU read/write of PPUDATA. With rendering
    //disabled this is the familiar "+1 or +32 depending on PPUCTRL bit 2". But when
    //rendering is *enabled* on a visible/pre-render scanline, $2007 doesn't own the
    //address bus - the rendering pipeline does - and the increment instead runs the
    //renderer's own two increment circuits simultaneously: the coarse-X step and the
    //Y step. AccuracyCoin's "$2007 Read W/ Rendering" test 2 checks exactly that the
    //vertical increment happens here.
    private void incrementVramAddressAfterCpuAccess() {
        boolean renderingEnabled = (ppumask & 0x18) != 0;
        boolean visibleOrPrerender = scanline <= 239 || scanline == 261;
        if (renderingEnabled && visibleOrPrerender) {
            incrementScrollX();
            incrementY();
        } else {
            v += ((ppuctrl & 0x04) != 0) ? 32 : 1;
        }
        //v is a 15-bit register, so it wraps at $8000 - NOT at $4000. Masking to
        //$3FFF here would silently clear fine-Y's high bit (bit 14) every time the
        //vertical increment carried into it, which is exactly the bit the
        //"$2007 Read W/ Rendering" test observes (it expects v += $1001).
        v &= 0x7FFF;
    }

    public byte cpuRead(int address) {
        switch (address) {
            case 0x2002: //PPUSTATUS - only bits 5-7 are real; the rest are open bus
                //A $2002 read latches the VBlank flag at the START of the read (when M2
                //goes high), but bits 5 and 6 are NOT latched - the CPU samples them at
                //the END of the read, when M2 goes low, roughly 1.875 PPU dots later.
                //So a read that straddles the pre-render clear point sees VBlank still
                //set while the sprite flags have already gone. See AccuracyCoin
                //"$2002 Flag Timing" test 1, whose whole point is this skew.
                int statusBits = ppustatus & 0xE0;
                //NOTE: this models only the sprite-flag half of the skew. Fully passing
                //"$2002 Flag Timing" test 1 would additionally require a read reported
                //at scanline 261 dot 0 to see VBlank already cleared - but "VBlank End"
                //requires that same read to see it still set. The two are
                //distinguishable only by sub-dot M2 phase, which this emulator's
                //"run a whole CPU instruction, then 3 PPU dots" interleaving does not
                //resolve, so test 1 is left failing rather than regressing VBlank End.
                if (dotsUntilPreRenderFlagClear() <= 2) statusBits &= ~0x60;
                byte status = (byte) (statusBits | (ppuDataBus & 0x1F));
                if (DEBUG_SPRITE0) System.out.printf("  [read $2002 -> %02x] scanline=%d cycle=%d%n", status & 0xFF, scanline, cycle);
                ppustatus &= ~0x80; //Clear VBlank bit on read
                nmiVblLevel = false;
                if (scanline == 241 && cycle == 0) suppressVblSet = true; //see suppressVblSet's declaration
                if (scanline == 241 && cycle == 1 && cpu != null) cpu.cancelPendingNMI(); //read races the flag-set tick: flag stands, but NMI is suppressed
                updateNmiLine(); //a read-cleared VBlank flag can also drop the NMI line
                addressLatch = false; //Reset address latch
                //Only bits 5-7 of the bus are actually driven by this read (see this
                //case's own comment) - the decay clock isn't reset here, since the
                //lower 5 bits (whatever they currently are) keep aging normally.
                ppuDataBus = status & 0xFF;
                return status;

            case 0x2004: { //OAMDATA
                int oamVal;
                boolean renderingEnabled = (ppumask & 0x18) != 0;
                boolean visibleOrPrerender = scanline <= 239 || scanline == 261;
                if (renderingEnabled && visibleOrPrerender && cycle >= 1 && cycle <= 320) {
                    //During active sprite evaluation/fetch, $2004 doesn't read real
                    //OAM through OAMADDR at all - see AccuracyCoin's "Address $2004
                    //Behavior" (cycles 1-64 and 257-320 always read $FF) and "$2004
                    //Stress Test" (cycles 65-256 read the live evaluation buffer,
                    //i.e. whatever byte stepSpriteEvaluation most recently looked at).
                    if (cycle <= 64) oamVal = 0xFF;
                    else if (cycle <= 256) oamVal = evalLastReadValue;
                    else oamVal = 0xFF;
                } else {
                    oamVal = oam[oamaddr & 0xFF] & 0xFF;
                    //The 3rd byte of each 4-byte OAM entry (the attribute byte) only
                    //physically stores bits 7-6-5 (flip V/H, priority) and 1-0 (palette) -
                    //bits 4-3-2 don't exist in hardware and always read back as 0.
                    if ((oamaddr & 0x03) == 2) oamVal &= 0xE3;
                }
                ppuDataBus = oamVal;
                ppuDataBusDecay = PPU_BUS_DECAY_TICKS;
                return (byte) ppuDataBus;
            }

            case 0x2007: { //PPUDATA
                clockA12(v);
                byte data = readBuffer;
                //The internal read buffer is always loaded from what would be at this
                //VRAM address if it weren't palette RAM - i.e. the underlying nametable
                //byte a $1000-lower address would read (palette RAM has no such buffer
                //of its own; only the CPU-visible read is unbuffered/immediate there).
                readBuffer = (v >= 0x3F00) ? (byte) nametables[mapNametableAddress((v - 0x1000) & 0x2FFF)] : ppuRead(v);

                if (v >= 0x3F00) {
                    //Palette reads are unbuffered, upper 2 bits open bus. Greyscale
                    //(PPUMASK bit 0) is applied on the *read path* out of palette RAM,
                    //so it masks the value the CPU sees down to the grey column
                    //($x0/$x10/$x20/$x30) without affecting what's stored - see
                    //AccuracyCoin "Palette RAM Quirks" tests 6 and 7.
                    int pal = ppuRead(v) & 0x3F;
                    if ((ppumask & 0x01) != 0) pal &= 0x30;
                    data = (byte) (pal | (ppuDataBus & 0xC0));
                }

                incrementVramAddressAfterCpuAccess();
                clockA12(v); //v drives the address bus continuously, so the auto-increment itself can pulse A12
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
                ppuctrl = val;
                t = (t & 0xF3FF) | ((val & 0x03) << 10); //nametable-select bits into t
                updateNmiLine(); //toggling bit 7 can raise (or drop) the NMI line immediately
                if (DEBUG_SPRITE0) System.out.printf("  [write $2000=%02x] scanline=%d cycle=%d%n", val, scanline, cycle);
                break;
            case 0x2001: { //PPUMASK
                boolean wasRendering = (ppumask & 0x18) != 0;
                ppumask = val;
                boolean nowRendering = (ppumask & 0x18) != 0;
                //Turning rendering off partway through a scanline that runs sprite
                //evaluation leaves the OAM/secondary-OAM datapath mid-transfer. The
                //current secondary OAM address is latched as a "seed"; the actual
                //corruption doesn't happen until rendering is switched back on and a
                //PPU cycle elapses on a visible or pre-render line, at which point OAM
                //row 0 is copied over row `seed`. See AccuracyCoin "OAM Corruption"
                //tests 2-4 (3 and 4 check that it is NOT immediate at either edge).
                if (wasRendering && !nowRendering && (scanline <= 239 || scanline == 261)) {
                    oamCorruptionSeed = currentSecondaryOamAddr();
                    oamCorruptionPending = true;
                }
                break;
            }
            case 0x2003: //OAMADDR
                oamaddr = val;
                break;
            case 0x2004: { //OAMDATA
                boolean renderingEnabled = (ppumask & 0x18) != 0;
                boolean visibleOrPrerender = scanline <= 239 || scanline == 261;
                if (renderingEnabled && visibleOrPrerender) {
                    //While rendering is active the sprite-evaluation hardware owns
                    //OAM, so a CPU write to $2004 never reaches OAM at all. What it
                    //does do is bump OAMADDR by 4 and then realign it to a sprite
                    //boundary. See AccuracyCoin "Address $2004 Behavior" tests 6
                    //(increments by 4), 7 (doesn't write OAM) and A (the AND $FC).
                    oamaddr = ((oamaddr + 4) & 0xFF) & 0xFC;
                } else {
                    oam[oamaddr & 0xFF] = data;
                    oamaddr = (oamaddr + 1) & 0xFF;
                }
                break;
            }
            case 0x2005: //PPUSCROLL
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
                if (!addressLatch) {
                    t = (t & 0x00FF) | ((val & 0x3F) << 8);
                    addressLatch = true;
                } else {
                    t = (t & 0x7F00) | val;
                    v = t;
                    clockA12(v);
                    addressLatch = false;
                }
                break;
            case 0x2007: //PPUDATA
                clockA12(v);
                ppuWrite(v, data);
                incrementVramAddressAfterCpuAccess();
                clockA12(v); //v drives the address bus continuously, so the auto-increment itself can pulse A12
                break;
        }
    }

    //Non-mutating register peek for the memory inspector; avoids the read side-effects
    //(VBlank clear, address latch reset, OAMDATA/PPUDATA auto-increment) that cpuRead triggers.
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

    //Folds a $2000-$3EFF nametable address onto one of the 4 physical 1KB slots in
    //`nametables`, per the cartridge's current mirroring mode - see Cartridge.MirrorMode.
    //Matches how real PPU/cartridge wiring ties nametable-select address lines
    //together: vertical mirroring ties $2000=$2800 and $2400=$2C00 (mirrored
    //left-right, i.e. horizontal scrolling games like SMB); horizontal mirroring
    //ties $2000=$2400 and $2800=$2C00 (mirrored top-bottom, vertical scrolling
    //games); single-screen pins all 4 to one physical table; four-screen keeps all
    //4 distinct (requires the cartridge to provide the extra 2KB of VRAM itself,
    //modeled here as just using the rest of this array).
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

    //Debug-only: formats every non-empty OAM entry (Y < $EF, matching the same
    //cutoff used elsewhere to skip parked/off-screen slots) as index/Y/tile/attr/X,
    //for the Debug menu's "Dump OAM" item - lets a user report exactly what's in
    //OAM at the moment a rendering bug is visible, without needing a debugger.
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
            //CHR space (pattern tables) - not nametable space. This must return here:
            //falling through into the address<=0x3EFF branch below would ALSO write
            //this same byte into nametable RAM (0x1FFF <= 0x3EFF), corrupting whatever
            //nametable tile mapNametableAddress(address & 0x2FFF) happens to land on
            //every time a $2007 write targets CHR space - e.g. whenever PPUADDR wraps
            //past $3FFF back down into $0000-$1FFF during a long streamed VRAM update.
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

    //Advances PPU clock by 1 tick (3 PPU ticks per 1 CPU step). CPU and PPU are
    //stepped 1:1 alongside each other (see NESEmulator.updateEmulationFrame), so at
    //any point here the CPU has executed exactly up through this instant - nothing
    //from "later" in the frame has happened yet.
    //
    //Visible pixels are produced one dot at a time (cycle 1 = pixel x=0 ... cycle 256
    //= pixel x=255), matching real hardware's own cycle/scanline timing, instead of
    //computing an entire scanline in one shot. This matters specifically for
    //sprite-0-hit: games like SMB poll PPUSTATUS bit 6 and time a mid-frame $2005/$2000
    //scroll write off *when* the hit fires within the scanline (it's used to split the
    //status bar from the scrolling playfield). If the hit were set instantly at the
    //start of the scanline instead of at the real x-position of the overlap, that write
    //lands too early, desyncs the split permanently, and the CPU's poll loop for the
    //next hit spins forever since the HUD sprite no longer overlaps opaque background.
    //
    //The v/t loopy-register copies below (cycle 257 horizontal, pre-render-line
    //280-304 vertical) are what makes that split land correctly: a mid-scanline
    //$2005/$2000 write only lands in t, so it can't retroactively affect the row
    //currently being drawn - it only becomes visible in v (and therefore in
    //getWorldScrollX/Y) once the real hardware moment for latching it arrives.
    private boolean pendingVblNmiEdge = false;

    public void step() {
        cycle++;

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

        //Runs BEFORE renderPixel below (real hardware shifts, then samples, within
        //the same dot) so that a visible scanline's pixel compositing reads this
        //dot's own freshly-shifted register state rather than the previous dot's -
        //see bgFetchAndShiftStep's own comment. Gated on renderingEnabled (bg OR
        //sprites), not just the background-visible bit, matching AccuracyCoin's
        //"Rendering Flag Behavior" test 2 (shifters still run with only sprites shown).
        if (renderingEnabled && visibleOrPrerender) {
            bgFetchAndShiftStep(cycle);
        }

        if (scanline <= 239) {
            if (cycle == 1) {
                prepareScanline(scanline);
            }
            if (cycle >= 1 && cycle <= 256) {
                renderPixel(scanline, cycle - 1);
            }
        }

        //v's Y bits ARE incremented scanline-by-scanline here (incrementY(), at cycle
        //256), matching real hardware - see incrementY()'s own comment for why that
        //matters for mid-frame $2006/$2005 Y-scroll writes. The horizontal copy at
        //cycle 257 similarly lets a mid-scanline $2005/$2000 X-scroll write - e.g.
        //SMB's status-bar split - take effect starting the next scanline; the vertical
        //copy only needs to happen once per frame, at the pre-render line, to
        //(re)establish that per-frame Y baseline after the CPU's own $2006/$2007 VRAM
        //housekeeping writes during VBlank have been moving v around for unrelated
        //reasons.
        if (renderingEnabled && visibleOrPrerender) {
            //Real hardware increments v's Y portion (fine Y, carrying into coarse Y,
            //carrying into a nametable-half toggle) once per scanline at dot 256 - the
            //same "yyy NN YYYYY XXXXX" v register described above, not a separate
            //derived value. Doing this for real (instead of the old approach of just
            //adding the scanline number to whatever Y prepareScanline last read out of
            //v) is what makes a mid-frame $2006/$2005 Y-scroll write - e.g. the SMB3
            //title screen's curtain/floor split - land correctly: real hardware treats
            //such a write as "the Y position starting at this scanline", and every
            //following scanline increments from there. Adding the absolute scanline
            //number instead (the old approach) only happened to work when v's Y bits
            //were set once during VBlank and never touched again mid-frame.
            if (cycle == 256) incrementY();
            if (cycle == 257) {
                v = (v & ~0x041F) | (t & 0x041F); //horizontal bits: coarse X + NT-select X
            }
            if (scanline == 261 && cycle >= 280 && cycle <= 304) {
                v = (v & ~0x7BE0) | (t & 0x7BE0); //vertical bits: fine/coarse Y + NT-select Y
            }
            //MMC3's IRQ counter is really clocked by rising edges of the PPU address
            //bus's A12 line, which happen when a pattern-table fetch address crosses
            //into $1000-$1FFF. This PPU renders per-pixel rather than per-dot-fetch
            //(see renderPixel's own comment), so the individual per-tile fetch
            //addresses aren't available, but the two points in the scanline where the
            //fetch address's pattern-table half can change are known: sprite pattern
            //fetches for the *next* scanline begin around dot 257 (using ppuctrl bit 3's
            //sprite pattern table half), and the following scanline's own background
            //prefetch resumes around dot 321 (using ppuctrl bit 4's BG pattern table
            //half). Routing both through clockA12() - the same shared A12 state that
            //$2006/$2007 CPU accesses update (see clockA12's own comment) - means a
            //rising edge is only counted when the half actually changes, exactly like
            //real hardware, instead of unconditionally clocking once per scanline
            //regardless of whether A12 was already high.
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

            //Real dot-by-dot sprite evaluation (secondary OAM build, cycles 65-256)
            //and fetch (pattern data for up to 8 slots, cycles 257-320) for whatever
            //scanline comes next - see startSpriteEvaluation/stepSpriteEvaluation/
            //fetchSpriteSlot's own comments. Evaluation runs whenever EITHER
            //background or sprites are enabled (renderingEnabled, i.e. ppumask&0x18),
            //not just when sprites are - AccuracyCoin's "Rendering Flag Behavior"
            //test 3 and "Sprite Overflow Behavior" test 4 both depend on evaluation
            //(and therefore the overflow flag) still happening with only the
            //background enabled.
            if (cycle == 65) startSpriteEvaluation(scanline);
            if (cycle >= 65 && cycle <= 255 && (cycle & 1) == 1) stepSpriteEvaluation();
            //OAMADDR reads back as 0 continuously during the sprite-fetch window,
            //regardless of what the CPU last wrote there - real hardware quirk that
            //AccuracyCoin's OAM-address tests rely on.
            if (cycle >= 257 && cycle <= 320) {
                oamaddr = 0;
                //nextSpriteCount is normally reset by startSpriteEvaluation at cycle
                //65, but rendering can toggle on mid-scanline (e.g. via a $2001 write
                //between 65 and 257) without that having run - resetting again here,
                //right before the first fetch of the window, keeps a stale count from
                //a previous scanline from overflowing the 8-slot arrays.
                if (cycle == 257) nextSpriteCount = 0;
                if (((cycle - 257) % 8) == 7) fetchSpriteSlot((cycle - 257) / 8);
            }
        }

        if (cycle >= 341) {
            cycle = 0;
            scanline++;
            if (scanline >= 262) {
                scanline = 0;
            }
            //nmiVblLevel drops 1 PPU dot before the CPU-visible $2002 bit does (see
            //nmiVblLevel's declaration) - right here, at scanline 261 dot 0, one dot
            //ahead of the dot-1 clear below.
            if (scanline == 261) {
                nmiVblLevel = false;
                updateNmiLine();
                //All three $2002 flags - VBlank, sprite 0 hit, and sprite overflow -
                //are cleared together on this single dot (dot 0 of the pre-render
                //line). AccuracyCoin's "$2002 Flag Timing" test 1 confirms they share
                //one clear point; the apparent skew between VBlank and the sprite
                //flags in a read's result comes purely from the read's own sampling
                //skew, modeled in cpuRead's $2002 case, not from separate clear dots.
                ppustatus &= ~0x60;
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
                pendingVblNmiEdge = true; //the NMI-line's own rising edge lags the $2002-visible flag by 1 ppu dot
            }
            suppressVblSet = false;
        }

        if (scanline == 261 && cycle == 1) {
            ppustatus &= ~0x80;
            ppustatus &= ~0x40; //Clear sprite-0-hit for the upcoming frame - real hardware clears
                                 //VBlank, sprite-0-hit, and sprite overflow together here, NOT at
                                 //scanline 241 (VBlank start) - clearing the hit flag that early was
                                 //hiding it from the game before it ever got a chance to read it during
                                 //VBlank, and (per AccuracyCoin's Sprite 0 Hit Behavior test C) let a
                                 //stale hit from one frame bleed as a false positive into the next.
            ppustatus &= ~0x20; //Clear sprite overflow flag for the upcoming frame - real hardware
                                 //clears VBlank, sprite-0-hit, and sprite overflow together here; this
                                 //flag was previously only ever set, never cleared, causing anything
                                 //that polls for it to go low (e.g. Sync_ToSpriteFlagsClearing in
                                 //AccuracyCoin's $2004 Stress Test) to spin forever.
        }
    }

    //Per-scanline sprite state, populated at cycle 1 (prepareScanline, from whatever
    //the PREVIOUS scanline's real dot-by-dot evaluation/fetch produced - see
    //nextSprite* below) and then consumed dot-by-dot by renderPixel as the beam
    //actually advances across the row.
    private static final int MAX_ROW_SPRITES = 8;
    private final boolean[] rowSpriteIsZero = new boolean[MAX_ROW_SPRITES]; //true iff this slot is secondary-OAM slot 0
    private final int[] rowSpriteX = new int[MAX_ROW_SPRITES];
    private final int[] rowSpritePlane0 = new int[MAX_ROW_SPRITES];
    private final int[] rowSpritePlane1 = new int[MAX_ROW_SPRITES];
    private final boolean[] rowSpriteFlipH = new boolean[MAX_ROW_SPRITES];
    private final boolean[] rowSpriteBehind = new boolean[MAX_ROW_SPRITES];
    private final int[] rowSpritePaletteGroup = new int[MAX_ROW_SPRITES];
    private int rowSpriteCount = 0;

    //Real 2C02 secondary OAM (8 sprites x 4 bytes) and the sprite-evaluation state
    //machine that fills it, run dot-by-dot across cycles 65-256 of every rendering-
    //enabled visible/pre-render scanline (see stepSpriteEvaluation, called from
    //step()). This replaces a simpler "evaluate the whole row at once" approach so
    //that: (a) $2004 reads during 65-256 can reflect the real in-progress evaluation
    //buffer value (see cpuRead's OAMDATA case), (b) the well-known sprite-overflow
    //"diagonal" hardware bug is reproduced instead of a clean count-and-stop, and
    //(c) OAMADDR misalignment quirks (starting evaluation mid-sprite) are modeled
    //instead of always assuming evaluation starts cleanly on a 4-byte boundary.
    //
    //Unlike the old model (which just walked the 64 primary-OAM entries as an
    //int[8] of indices), the real chip has exactly one evaluation pointer -
    //evalAddr, an 8-bit OAM byte address - and the byte at evalAddr is read/decided
    //on every step. evalCopyRemaining tracks "how many more bytes of the sprite
    //currently being copied are left" (0 means the next byte read is treated as a
    //fresh Y-coordinate decision, not a mid-copy continuation) - matching real
    //hardware's actual logic instead of separately tracked n/m sprite/byte indices,
    //which is what lets a misaligned starting OAMADDR "stay misaligned" through an
    //in-range sprite's copy and then self-realign on the next out-of-range decision,
    //exactly like AccuracyCoin's "Misaligned OAM Behavior" test expects.
    private final byte[] secondaryOam = new byte[32];
    private int evalAddr;
    private int evalCopyRemaining;
    private int evalSecCount;       //0-8: sprites committed into secondaryOam so far this evaluation
    private int evalSpritesScanned; //safety cutoff once all 64 primary sprites have been decided on, so a scanline with few sprite matches doesn't spin evaluation state forever
    private int evalLastReadValue;  //the most recently read (and attribute-masked) OAM byte - what $2004 reflects during cycles 65-256
    private int evalTargetScanline; //the scanline this evaluation's results are FOR (next scanline, or 0 during pre-render)
    private boolean evalActive;

    //"Sprite zero" is not "whatever ended up in secondary OAM slot 0" - it is set by a
    //dedicated flag that the hardware raises only if the VERY FIRST object evaluated
    //this scanline (the one decided on PPU cycle 66) turned out to be in range. If
    //that first object is out of range and some later object becomes slot 0 instead,
    //slot 0 is NOT sprite zero and cannot trigger a sprite zero hit. Conversely, a
    //write to $2003 before evaluation starts makes evaluation begin at a non-zero
    //OAM index, and whatever object it lands on first becomes "sprite zero" even
    //though it isn't OAM index 0. See AccuracyCoin "Arbitrary Sprite Zero" tests 1-2.
    private boolean evalFirstStep;
    private boolean evalSpriteZeroInRange;
    private boolean nextSpriteZeroPresent;

    //Results of the evaluation+fetch that ran during the PREVIOUS scanline's cycles
    //65-320, swapped into rowSprite*/rowSpriteCount at the start of THIS scanline
    //(see prepareScanline). Built incrementally: stepSpriteEvaluation fills
    //secondaryOam across 65-256, then fetchSpriteSlot reads pattern data out of it
    //into these arrays across 257-320 (8 cycles per slot), sampling PPUCTRL at each
    //slot's own fetch cycle rather than once for the whole scanline - so a
    //mid-fetch-window $2000 write (AccuracyCoin's "Suddenly Resize Sprite" tests)
    //can actually change which slots see tall vs. short sprites.
    private final boolean[] nextSpriteIsZero = new boolean[MAX_ROW_SPRITES];
    private final int[] nextSpriteX = new int[MAX_ROW_SPRITES];
    private final int[] nextSpritePlane0 = new int[MAX_ROW_SPRITES];
    private final int[] nextSpritePlane1 = new int[MAX_ROW_SPRITES];
    private final boolean[] nextSpriteFlipH = new boolean[MAX_ROW_SPRITES];
    private final boolean[] nextSpriteBehind = new boolean[MAX_ROW_SPRITES];
    private final int[] nextSpritePaletteGroup = new int[MAX_ROW_SPRITES];
    private int nextSpriteCount = 0;
    private boolean rowShowBackground;
    private boolean rowShowSprites;
    private boolean rowShowLeftBackground;
    private boolean rowShowLeftSprites;
    private int rowBackdropRgb;
    //Real 2C02 background pipeline: two 16-bit pattern shift registers (low/high
    //bitplane) and two 16-bit attribute shift registers (each bit pre-expanded to
    //either all-0s or all-1s for the tile it came from), shifted left once per dot
    //during rendering (see updateBgShifters) and reloaded with the next tile's data
    //in their low byte every 8 dots (see loadBgShifters). bgNext* latches hold the
    //tile currently being fetched (nametable byte -> attribute byte -> pattern low
    //-> pattern high, 2 dots each, driven by bgFetchAndShiftStep) until that reload
    //moment. This replaces the old "cache a whole tile once per 8 pixels, keyed off
    //world-space X" approximation with the real per-dot state machine - which is
    //what several AccuracyCoin tests (t Register Quirks, Stale BG Shift Registers,
    //BG Serial In, $2007 Stress Test, ALE + Read, Hybrid Addresses...) directly probe.
    //Fetches still go through ppuRead/PPUCTRL exactly like before, so MMC3 CHR-bank/
    //pattern-table-bit mid-scanline switches (e.g. SMB3's status-bar split) are still
    //picked up per-tile - now even more precisely, since the low/high pattern bytes
    //are fetched at their own real dot (cycle%8==5/7) rather than at "first pixel of
    //an 8px block".
    private int bgNextTileId, bgNextAttr, bgNextLsb, bgNextMsb;
    private int bgShiftPatternLo, bgShiftPatternHi;
    private int bgShiftAttrLo, bgShiftAttrHi;

    //Latches this scanline's render-affecting PPU state and evaluates sprites for the
    //row (real hardware does this during cycles 65-256 of the *previous* scanline, but
    //since nothing here reads OAM/PPUCTRL mid-evaluation, doing it up front at cycle 1
    //of this scanline is behaviorally equivalent and simpler). Actual pixel output and
    //the sprite-0-hit comparison happen later, dot-by-dot, in renderPixel.
    private void prepareScanline(int sy) {
        if (DEBUG_SPRITE0 && sy <= 35) {
            System.out.printf("prepareScanline sy=%d ppuctrl=%02x scrollX=%d scrollY=%d%n", sy, ppuctrl, scrollX, scrollY);
        }
        rowShowBackground = (ppumask & 0x08) != 0;
        rowShowSprites = (ppumask & 0x10) != 0;
        rowShowLeftBackground = (ppumask & 0x02) != 0;
        rowShowLeftSprites = (ppumask & 0x04) != 0;

        rowBackdropRgb = NES_PALETTE[ppuRead(0x3F00) & 0x3F];

        //This scanline's sprites were already evaluated+fetched dot-by-dot during
        //the PREVIOUS scanline's cycles 65-320 (see stepSpriteEvaluation/
        //fetchSpriteSlot, driven from step()) - just adopt those results now. Stored
        //highest-fetch-order-first (slot 7 first, if present) so that iterating
        //n=count-1 down to 0 in renderPixel visits slot 0 (real priority winner, and
        //the only slot that can ever be "sprite zero") last, matching the old
        //array's "lower index composited on top" convention.
        rowSpriteCount = 0;
        for (int slot = MAX_ROW_SPRITES - 1; slot >= 0; slot--) {
            if (slot >= nextSpriteCount) continue;
            int n = rowSpriteCount++;
            rowSpriteIsZero[n] = nextSpriteIsZero[slot];
            rowSpriteX[n] = nextSpriteX[slot];
            rowSpritePlane0[n] = nextSpritePlane0[slot];
            rowSpritePlane1[n] = nextSpritePlane1[slot];
            rowSpriteFlipH[n] = nextSpriteFlipH[slot];
            rowSpriteBehind[n] = nextSpriteBehind[slot];
            rowSpritePaletteGroup[n] = nextSpritePaletteGroup[slot];
        }
    }

    //Starts a fresh sprite evaluation for whatever scanline follows `sy` (or
    //scanline 0, if sy is the pre-render line) - called once at cycle 65 of every
    //rendering-enabled visible/pre-render scanline. evalAddr begins at the CPU's
    //current OAMADDR (not forced to a 4-byte boundary) - see this class's evalAddr
    //field comment for why that's what makes OAM-misalignment quirks come out right.
    private void startSpriteEvaluation(int sy) {
        java.util.Arrays.fill(secondaryOam, (byte) 0xFF);
        evalAddr = oamaddr & 0xFF;
        evalCopyRemaining = 0;
        evalSecCount = 0;
        evalSpritesScanned = 0;
        evalTargetScanline = (sy == 261) ? 0 : sy + 1;
        evalActive = true;
        evalFirstStep = true;
        evalSpriteZeroInRange = false;
        nextSpriteCount = 0;
    }

    //Runs one 2-PPU-cycle evaluation step (one primary-OAM byte decided) - called on
    //every odd cycle from 65 to 255 inclusive (96 steps total, matching real
    //hardware's throughput for this 192-cycle window). Implements the real 2C02
    //algorithm off a single evalAddr pointer rather than independent sprite/byte
    //indices: an in-range sprite's remaining 3 bytes are just copied by walking
    //evalAddr forward one byte at a time (which is what lets a misaligned starting
    //address stay misaligned through the copy), an out-of-range Y realigns evalAddr
    //to the next 4-byte boundary via (addr+4)&0xFC, and once 8 sprites are already
    //committed, evaluation switches to the buggy "overflow scan" - every remaining
    //step just advances evalAddr by a flat 5 (the well-known n-and-m-both-increment
    //hardware bug) while still checking for (and setting) the overflow flag.
    //Advances the single evaluation pointer, ending the whole evaluation if it runs
    //off the end of OAM. Sprite evaluation stops when the OAM address wraps past $FF -
    //it does NOT roll around to $00 and start re-reading the low sprites. Letting it
    //wrap made the post-full "overflow scan" (which strides by 5 and so lands on
    //non-Y bytes) come back around into real sprite data and raise a bogus sprite
    //overflow flag - AccuracyCoin "Sprite Overflow Behavior" test 3 checks exactly
    //that 8 in-range sprites leave the flag clear.
    private void advanceEvalAddr(int delta) {
        int next = evalAddr + delta;
        if (next > 0xFF) evalActive = false;
        evalAddr = next & 0xFF;
    }

    //Pending OAM corruption armed by switching rendering off mid-scanline, and the
    //secondary OAM address that was latched at that moment. See cpuWrite's $2001 case.
    private boolean oamCorruptionPending = false;
    private int oamCorruptionSeed = 0;

    //Where the secondary OAM write pointer currently sits (0-$1F), reconstructed from
    //the dot. Dots 1-64 are the secondary-OAM clear, which walks the pointer one step
    //every two dots. Dots 65-256 are evaluation, where the pointer tracks the entry
    //being filled - real hardware "ceilings" a corruption seed taken here to a
    //multiple of 4, which falls out of using the committed-sprite count. Dots 257-320
    //are the sprite fetch, whose 8-dot loop advances the pointer on its first three
    //dots and then holds.
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

        int m = evalAddr & 0x03;
        int val = oam[evalAddr & 0xFF] & 0xFF;
        if (m == 2) val &= 0xE3; //attribute byte: bits 2-4 don't physically exist
        evalLastReadValue = val;

        //Mid-copy continuation is checked FIRST, independent of evalSecCount: the
        //moment an 8th sprite is found in-range, evalSecCount is bumped to 8 right
        //away (see the `inRange` branch below) so that a 9th sprite would correctly
        //enter overflow-scan mode - but the 8th sprite ITSELF still has 3 more bytes
        //left to copy at that point, and those must still go through the normal
        //byte-copy path, not be misread as fresh overflow-scan Y-checks.
        if (evalCopyRemaining > 0) {
            //The destination offset within the secondary-OAM entry is SEQUENTIAL
            //(1, 2, then 3 as evalCopyRemaining counts 3, 2, 1) - it is not the
            //physical evalAddr's low bits. Those two coincide only when evaluation
            //started on a 4-byte boundary; with a misaligned OAMADDR they diverge, and
            //using the physical low bits filed the tile byte into the attribute slot.
            //Note the bits-2-to-4 mask above DOES key off the physical address, which
            //is why a misaligned read of a tile byte loses those bits - see
            //AccuracyCoin "Arbitrary Sprite Zero" test 3, which picks tile $E3
            //precisely so it survives that mask.
            secondaryOam[(evalSecCount - 1) * 4 + (4 - evalCopyRemaining)] = (byte) val;
            advanceEvalAddr(1);
            evalCopyRemaining--;
        } else if (evalSecCount < 8) {
            secondaryOam[evalSecCount * 4] = (byte) val; //speculative Y copy; only meaningful if in range
            //OAM's Y byte is the sprite's top row MINUS 1 (a sprite at Y=0 first
            //displays on scanline 1, not 0 - see fetchSpriteSlot's matching `y + 1`),
            //so the in-range check has to compare against val+1, not val itself.
            int diff = evalTargetScanline - (val + 1);
            boolean inRange = diff >= 0 && diff < spriteHeight;
            evalSpritesScanned++;
            //Only the object decided on the very first evaluation step can ever be
            //sprite zero - see evalSpriteZeroInRange's declaration.
            if (evalFirstStep && inRange) evalSpriteZeroInRange = true;
            evalFirstStep = false;
            if (inRange) {
                evalCopyRemaining = 3;
                evalSecCount++;
                advanceEvalAddr(1);
            } else {
                //Out of range: skip to the next object and realign to a 4-byte
                //boundary. The AND is only observable when OAMADDR was misaligned -
                //see AccuracyCoin "Misaligned OAM Behavior" test 2.
                if (evalAddr + 4 > 0xFF) evalActive = false;
                evalAddr = (evalAddr + 4) & 0xFC;
            }
        } else {
            int diff = evalTargetScanline - (val + 1); //see the +1 note above
            boolean inRange = diff >= 0 && diff < spriteHeight;
            if (inRange) ppustatus |= 0x20; //sprite overflow flag
            advanceEvalAddr(5); //the hardware bug: n AND m both advance, unconditionally
        }
    }

    //Reads pattern data for one of the (up to) 8 secondary-OAM slots into
    //nextSprite*, called at the LAST of each slot's 8 fetch cycles (264, 272, ...,
    //320) so that PPUCTRL's tall-sprite/pattern-table bits are sampled as late as
    //real per-slot fetch timing allows, rather than once for the whole scanline.
    //A slot whose Y byte is still $FF (never claimed by evaluation - either fewer
    //than 8 sprites matched, or evaluation hadn't reached it) fetches as fully
    //transparent, matching a real "unused" secondary OAM slot's harmless garbage fetch.
    private void fetchSpriteSlot(int slot) {
        int base = slot * 4;
        int y = secondaryOam[base] & 0xFF;
        //Indexed directly by slot (0-7), NOT a separately-incremented counter - all
        //8 slots are always fetched in order, so slot doubles as "how many fetched
        //so far" without needing a second piece of state that could drift out of
        //sync (and overflow the 8-element arrays) if this scanline's evaluation
        //window never ran (e.g. rendering toggled on only partway through 65-320).
        int n = slot;
        if (n >= nextSpriteCount) nextSpriteCount = n + 1;
        nextSpriteIsZero[n] = (slot == 0) && (y != 0xFF) && evalSpriteZeroInRange;

        if (y == 0xFF) {
            nextSpriteX[n] = 0xFF;
            nextSpritePlane0[n] = 0;
            nextSpritePlane1[n] = 0;
            nextSpriteFlipH[n] = false;
            nextSpriteBehind[n] = false;
            nextSpritePaletteGroup[n] = 0;
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

        int row = evalTargetScanline - (y + 1);
        //The sprite's Y comparator keeps running through the fetch phase, against
        //whatever height PPUCTRL selects *now* - not the height that was in effect
        //back during evaluation. So a $2000 write at the start of HBlank that drops
        //8x16 mode leaves an object that evaluation accepted (row 8-15) out of range
        //by the time its slot is fetched, and the fetch comes back blank rather than
        //spilling into the next tile's pattern data. See AccuracyCoin "Suddenly
        //Resize Sprite" test 4 (and test 5, which writes after this slot's own fetch
        //cycle and so must still see the data).
        if (row < 0 || row >= spriteHeight) {
            nextSpriteX[n] = spriteX;
            nextSpritePlane0[n] = 0;
            nextSpritePlane1[n] = 0;
            nextSpriteFlipH[n] = flipH;
            nextSpriteBehind[n] = behindBackground;
            nextSpritePaletteGroup[n] = paletteGroup;
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

        nextSpriteX[n] = spriteX;
        nextSpritePlane0[n] = ppuRead(tileOffset) & 0xFF;
        nextSpritePlane1[n] = ppuRead(tileOffset + 8) & 0xFF;
        nextSpriteFlipH[n] = flipH;
        nextSpriteBehind[n] = behindBackground;
        nextSpritePaletteGroup[n] = paletteGroup;
    }

    //Renders one pixel of the current scanline - background then sprites, in real
    //priority order - and, if this dot is where sprite 0's opaque pixel genuinely
    //overlaps an opaque background pixel, sets the sprite-0-hit flag right here. Called
    //once per PPU cycle for cycles 1-256 of each visible scanline (see step()), so the
    //flag becomes visible to the CPU at the actual x-position of the hit rather than
    //instantly at the start of the scanline - this is what lets games that time a
    //mid-frame scroll write off the hit's position (e.g. SMB's status-bar split) work.
    private void renderPixel(int sy, int x) {
        //PPUMASK's show/left-mask bits are sampled LIVE, at each dot, not latched once
        //per scanline. Real hardware has no per-scanline latch for them: a $2001 write
        //partway across a scanline takes effect on the very next dot. Several
        //AccuracyCoin tests turn rendering on only a handful of dots before a sprite
        //zero hit and depend on that hit being evaluated with the just-written mask
        //("Rendering Flag Behavior" tests 2 and 3 in particular). Latching these at
        //dot 1 made every such mid-scanline write invisible for the rest of the row.
        rowShowBackground = (ppumask & 0x08) != 0;
        rowShowSprites = (ppumask & 0x10) != 0;
        rowShowLeftBackground = (ppumask & 0x02) != 0;
        rowShowLeftSprites = (ppumask & 0x04) != 0;

        int rgb;
        boolean bgOpaque;
        int debugWinner = -1;
        if (!rowShowBackground || (x < 8 && !rowShowLeftBackground)) {
            rgb = rowBackdropRgb;
            bgOpaque = false;
        } else {
            //Sampled from the real per-dot shift registers (see bgFetchAndShiftStep/
            //updateBgShifters, run for this exact dot just before renderPixel is
            //called) rather than a world-space tile-cache lookup - fineX selects which
            //of the 16 shifted-in bits is "this dot's pixel", the same mux real
            //hardware wires straight from the shift register outputs.
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
            rgb = NES_PALETTE[paletteIndex];
            bgOpaque = pixelVal != 0;
        }
        //rowShowSprites must still gate actual sprite drawing/hit-testing even though
        //evaluation+fetch now always run when rendering is enabled at all (see
        //startSpriteEvaluation's comment) - the OLD code got this for free because
        //evaluation itself used to bail out early when sprites were disabled, but
        //that's exactly the behavior AccuracyCoin's "Rendering Flag Behavior" test 3
        //and "Sprite Overflow Behavior" test 4 say is wrong (evaluation/overflow must
        //still happen with only the background enabled) - so the gate has to move
        //here, to drawing/hit-testing specifically, instead.
        if (rowShowSprites && rowSpriteCount > 0 && (x >= 8 || rowShowLeftSprites)) {
            //Real hardware resolves sprite-vs-sprite priority FIRST - the single
            //topmost (lowest OAM index) sprite with an opaque pixel here wins outright,
            //full stop - and only THEN decides background-vs-that-one-sprite from its
            //own priority bit. A lower-priority sprite underneath must never become
            //visible just because the topmost one lost to the background: that's
            //exactly the "masking sprite" trick some games rely on (e.g. SMB3 stacks
            //an opaque, behind-background sprite directly over another sprite's part
            //that needs hiding - the masking sprite need not itself ever be visible,
            //its mere presence as the topmost opaque sprite is what blocks the sprite
            //underneath, letting the opaque background show through instead).
            //
            //rowSprite* arrays are stored lowest-priority-first (see prepareScanline's
            //own comment), so n=rowSpriteCount-1 is the topmost (OAM index 0, if
            //matched, is always last) - iterate highest priority to lowest so the
            //first opaque hit is the real winner, and stop resolving color after it.
            //Sprite-0-hit, however, is a separate hardware comparator that fires off
            //sprite 0's own opacity vs background opacity regardless of whether some
            //other sprite is drawn on top of it - so that check still runs for every
            //opaque sprite, not just the topmost.
            boolean topmostResolved = false;
            for (int n = rowSpriteCount - 1; n >= 0; n--) {
                int col = x - rowSpriteX[n];
                if (col < 0 || col > 7) continue;

                int bitIndex = rowSpriteFlipH[n] ? col : (7 - col);
                int bit0 = (rowSpritePlane0[n] >> bitIndex) & 0x01;
                int bit1 = (rowSpritePlane1[n] >> bitIndex) & 0x01;
                int pixelVal = (bit1 << 1) | bit0;
                if (pixelVal == 0) continue; //transparent

                //Real sprite-0-hit: never fires at x=255 (hardware quirk), and only
                //against whichever sprite occupies secondary-OAM slot 0 - NOT
                //necessarily primary OAM index 0. Real hardware's hit comparator is
                //wired to slot 0's own shift register, so if evaluation started from
                //a misaligned OAMADDR and some other OAM index happened to land in
                //slot 0 first, THAT sprite triggers hits instead (AccuracyCoin's
                //"Arbitrary Sprite Zero" test 2).
                if (rowSpriteIsZero[n] && bgOpaque && x != 255) {
                    ppustatus |= 0x40;
                    hitOccurredThisFrame = true;
                    if (DEBUG_SPRITE0) System.out.printf("  [HIT SET] sy=%d x=%d%n", sy, x);
                }
                if (DEBUG_SPRITE0 && rowSpriteIsZero[n]) {
                    System.out.printf("sprite0 sy=%d x=%d pixelVal=%d bgHere=%b worldScrollX=%d%n", sy, x, pixelVal, bgOpaque, getWorldScrollX());
                }

                if (topmostResolved) continue; //already found the real winner above; keep scanning only for sprite-0-hit
                topmostResolved = true;

                if (rowSpriteBehind[n] && bgOpaque) continue; //topmost sprite loses to opaque background - background wins, no lower sprite gets a turn

                int paletteIndex = ppuRead(0x3F10 + rowSpritePaletteGroup[n] * 4 + pixelVal) & 0x3F;
                rgb = NES_PALETTE[paletteIndex];
                debugWinner = rowSpriteIsZero[n] ? 0 : 1; //debug-only marker (0=slot-0/"sprite zero", 1=other); no longer a real OAM index
            }
        }

        debugBgOpaque[sy * 256 + x] = bgOpaque;
        debugWinnerOam[sy * 256 + x] = debugWinner;

        if (display != null) display.setPixel(x, sy, rgb);
    }

    //Debug-only: reports exactly what actually rendered at a given screen pixel -
    //read from debugBgOpaque/debugWinnerOam, which renderPixel() records live as it
    //genuinely renders each dot (see those fields' comment for why re-deriving this
    //after the fact, from a paused frame's final register state, isn't trustworthy
    //for an MMC3 game that swaps CHR banks mid-frame).
    public String explainPixel(int x, int sy) {
        int idx = sy * 256 + x;
        boolean bgOpaque = debugBgOpaque[idx];
        int winner = debugWinnerOam[idx];
        return String.format("pixel (%d,%d): background %s; winner = %s%n", x, sy,
                bgOpaque ? "opaque" : "transparent/backdrop",
                winner < 0 ? "background" : ("OAM[" + winner + "]"));
    }

    //Debug-only: renders a small rectangular region as an ASCII grid, one character
    //per pixel, from the same live-recorded per-pixel data explainPixel() uses -
    //so the actual shape of what won each pixel can be seen at a glance. '.' =
    //transparent background won (backdrop showing), '#' = opaque background tile
    //won, and a hex digit = the low nibble of the OAM index of the sprite that won
    //that pixel (matching digits trace out one sprite's visible shape).
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