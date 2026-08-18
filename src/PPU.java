public class PPU {

    public static boolean DEBUG_SPRITE0 = false;

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

    //PPU Internal Memory
    private final byte[] nametables = new byte[2048]; //2KB VRAM
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
    private int fineX = 0;
    private byte readBuffer = 0x00;

    //Floating PPU data bus ($2xxx <-> CPU): every register access, read or write,
    //leaves its value latched here. Reading a write-only register (or the unused low
    //5 bits of $2002) returns this instead of a fixed 0, matching real hardware.
    private int ppuDataBus = 0x00;

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
        int scrollX, scrollY;
        int cycle, scanline;
        boolean lastFrameHadHit, hitOccurredThisFrame;
        boolean nmiLine, nmiVblLevel, suppressVblSet, vblankStarted, pendingVblNmiEdge;
        int[] rowSpriteX, rowSpritePlane0, rowSpritePlane1, rowSpritePaletteGroup;
        boolean[] rowSpriteFlipH, rowSpriteBehind;
        int rowSpriteCount;
        boolean rowShowBackground, rowShowSprites, rowShowLeftBackground, rowShowLeftSprites;
        int rowBackdropRgb, rowBgTableOffset, rowWorldScrollX, rowWorldScrollY;
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
        s.rowSpriteCount = rowSpriteCount;
        s.rowShowBackground = rowShowBackground; s.rowShowSprites = rowShowSprites;
        s.rowShowLeftBackground = rowShowLeftBackground; s.rowShowLeftSprites = rowShowLeftSprites;
        s.rowBackdropRgb = rowBackdropRgb; s.rowBgTableOffset = rowBgTableOffset;
        s.rowWorldScrollX = rowWorldScrollX; s.rowWorldScrollY = rowWorldScrollY;
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
        rowSpriteCount = s.rowSpriteCount;
        rowShowBackground = s.rowShowBackground; rowShowSprites = s.rowShowSprites;
        rowShowLeftBackground = s.rowShowLeftBackground; rowShowLeftSprites = s.rowShowLeftSprites;
        rowBackdropRgb = s.rowBackdropRgb; rowBgTableOffset = s.rowBgTableOffset;
        rowWorldScrollX = s.rowWorldScrollX; rowWorldScrollY = s.rowWorldScrollY;
    }

    //World-space (512x480, matching NESDisplayPanel's tiled-nametable buffer) scroll
    //position derived from the current v register + fine X: bit 10 of v is the
    //horizontal nametable-select bit (which of the 2 world halves scrolling starts
    //from), coarse X (bits 0-4) counts 8px tile steps within that half, fine X is the
    //sub-tile pixel offset.
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

    public byte cpuRead(int address) {
        switch (address) {
            case 0x2002: //PPUSTATUS - only bits 5-7 are real; the rest are open bus
                byte status = (byte) ((ppustatus & 0xE0) | (ppuDataBus & 0x1F));
                ppustatus &= ~0x80; //Clear VBlank bit on read
                nmiVblLevel = false;
                if (scanline == 241 && cycle == 0) suppressVblSet = true; //see suppressVblSet's declaration
                if (scanline == 241 && cycle == 1 && cpu != null) cpu.cancelPendingNMI(); //read races the flag-set tick: flag stands, but NMI is suppressed
                updateNmiLine(); //a read-cleared VBlank flag can also drop the NMI line
                addressLatch = false; //Reset address latch
                ppuDataBus = status & 0xFF;
                return status;

            case 0x2004: //OAMDATA
                ppuDataBus = oam[oamaddr & 0xFF] & 0xFF;
                return (byte) ppuDataBus;

            case 0x2007: { //PPUDATA
                byte data = readBuffer;
                readBuffer = ppuRead(v);

                if (v >= 0x3F00) data = (byte) ((readBuffer & 0x3F) | (ppuDataBus & 0xC0)); //Palette reads are unbuffered, upper 2 bits open bus

                v += ((ppuctrl & 0x04) != 0) ? 32 : 1;
                v &= 0x3FFF;
                ppuDataBus = data & 0xFF;
                return data;
            }

            default: //Write-only registers ($2000/$2001/$2003/$2005/$2006) are open bus
                return (byte) ppuDataBus;
        }
    }

    public void cpuWrite(int address, byte data) {
        int val = data & 0xFF;
        ppuDataBus = val;
        switch (address) {
            case 0x2000: //PPUCTRL
                ppuctrl = val;
                t = (t & 0xF3FF) | ((val & 0x03) << 10); //nametable-select bits into t
                updateNmiLine(); //toggling bit 7 can raise (or drop) the NMI line immediately
                if (DEBUG_SPRITE0) System.out.printf("  [write $2000=%02x] scanline=%d cycle=%d%n", val, scanline, cycle);
                break;
            case 0x2001: //PPUMASK
                ppumask = val;
                break;
            case 0x2003: //OAMADDR
                oamaddr = val;
                break;
            case 0x2004: //OAMDATA
                oam[oamaddr & 0xFF] = data;
                oamaddr = (oamaddr + 1) & 0xFF;
                break;
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
                    addressLatch = false;
                }
                break;
            case 0x2007: //PPUDATA
                ppuWrite(v, data);
                v += ((ppuctrl & 0x04) != 0) ? 32 : 1;
                v &= 0x3FFF;
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

    public byte ppuRead(int address) {
        address &= 0x3FFF;
        if (address <= 0x1FFF) {
            return bus.ppuReadCartridge(address);
        }
        if (address <= 0x3EFF) {
            return nametables[address & 0x07FF];
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
            bus.ppuWriteCartridge(address, data);
        }
        if (address <= 0x3EFF) {
            nametables[address & 0x07FF] = data;
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

        if (pendingVblNmiEdge) {
            pendingVblNmiEdge = false;
            updateNmiLine();
        }

        boolean renderingEnabled = (ppumask & 0x18) != 0;
        boolean visibleOrPrerender = scanline <= 239 || scanline == 261;

        if (scanline <= 239) {
            if (cycle == 1) {
                prepareScanline(scanline);
            }
            if (cycle >= 1 && cycle <= 256) {
                renderPixel(scanline, cycle - 1);
            }
        }

        //Note: unlike real hardware, v's Y bits are NOT incremented scanline-by-scanline
        //here - renderPixel/prepareScanline instead compute each row's world Y by adding
        //the current scanline number (sy) to the Y that getWorldScrollY() reads out of v
        //(see prepareScanline). Only the horizontal copy matters every scanline (it's
        //what lets a mid-frame $2005/$2000 X-scroll write - e.g. SMB's status-bar split -
        //take effect starting the next scanline); the vertical copy only needs to happen
        //once per frame, at the pre-render line, to (re)establish that per-frame Y
        //baseline after the CPU's own $2006/$2007 VRAM housekeeping writes during VBlank
        //have been moving v around for unrelated reasons.
        if (renderingEnabled && visibleOrPrerender) {
            if (cycle == 257) {
                v = (v & ~0x041F) | (t & 0x041F); //horizontal bits: coarse X + NT-select X
            }
            if (scanline == 261 && cycle >= 280 && cycle <= 304) {
                v = (v & ~0x7BE0) | (t & 0x7BE0); //vertical bits: fine/coarse Y + NT-select Y
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
            }
        }

        //Trigger VBlank at Scanline 241, Cycle 1
        if (scanline == 241 && cycle == 1) {
            vblankStarted = true; //still marks "one frame elapsed" even when suppressed
            lastFrameHadHit = hitOccurredThisFrame;
            hitOccurredThisFrame = false;
            ppustatus &= ~0x40; //Clear sprite-0-hit for the upcoming frame; renderScanline sets it live as the frame draws
            if (!suppressVblSet) {
                ppustatus |= 0x80; //Set VBlank flag
                nmiVblLevel = true;
                pendingVblNmiEdge = true; //the NMI-line's own rising edge lags the $2002-visible flag by 1 ppu dot
            }
            suppressVblSet = false;
        }

        if (scanline == 261 && cycle == 1) {
            ppustatus &= ~0x80;
        }
    }

    //Per-scanline sprite state, evaluated once at cycle 1 (prepareScanline) and then
    //consumed dot-by-dot by renderPixel as the beam actually advances across the row.
    private static final int MAX_ROW_SPRITES = 8;
    private final int[] rowSpriteIndex = new int[MAX_ROW_SPRITES];   //OAM index (0-63)
    private final int[] rowSpriteX = new int[MAX_ROW_SPRITES];
    private final int[] rowSpritePlane0 = new int[MAX_ROW_SPRITES];
    private final int[] rowSpritePlane1 = new int[MAX_ROW_SPRITES];
    private final boolean[] rowSpriteFlipH = new boolean[MAX_ROW_SPRITES];
    private final boolean[] rowSpriteBehind = new boolean[MAX_ROW_SPRITES];
    private final int[] rowSpritePaletteGroup = new int[MAX_ROW_SPRITES];
    private int rowSpriteCount = 0;
    private boolean rowShowBackground;
    private boolean rowShowSprites;
    private boolean rowShowLeftBackground;
    private boolean rowShowLeftSprites;
    private int rowBackdropRgb;
    private int rowBgTableOffset;
    private int rowWorldScrollX;
    private int rowWorldScrollY;

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
        rowBgTableOffset = ((ppuctrl & 0x10) != 0) ? 0x1000 : 0x0000;
        rowWorldScrollX = getWorldScrollX();
        rowWorldScrollY = getWorldScrollY();

        rowSpriteCount = 0;
        if (!rowShowSprites) return;

        boolean tall = (ppuctrl & 0x20) != 0;
        int spriteHeight = tall ? 16 : 8;
        int spritePatternBase = ((ppuctrl & 0x08) != 0) ? 0x1000 : 0x0000;

        //Real hardware evaluates OAM in index order and only has room for 8 sprites
        //per scanline (the rest are simply dropped, causing the "flicker" games rely on
        //when overcrowded) - matching that limit here keeps overcrowded scanlines from
        //rendering sprites hardware wouldn't have room for either.
        int[] matched = new int[8];
        int matchCount = 0;
        for (int i = 0; i < 64 && matchCount < 8; i++) {
            int spriteTop = (oam[i * 4] & 0xFF) + 1;
            if (sy >= spriteTop && sy < spriteTop + spriteHeight) {
                matched[matchCount++] = i;
            }
        }

        //Store in reverse of OAM-index order so index 0 ends up last in the row arrays
        //(i.e. composited on top) - lower OAM index wins on overlapping opaque pixels,
        //matching hardware priority.
        for (int m = matchCount - 1; m >= 0; m--) {
            int i = matched[m];
            int base = i * 4;
            int spriteTop = (oam[base] & 0xFF) + 1;
            int tileIndex = oam[base + 1] & 0xFF;
            int attr = oam[base + 2] & 0xFF;
            int spriteX = oam[base + 3] & 0xFF;

            boolean flipV = (attr & 0x80) != 0;
            boolean flipH = (attr & 0x40) != 0;
            boolean behindBackground = (attr & 0x20) != 0;
            int paletteGroup = attr & 0x03;

            int patternTableOffset = tall ? (((tileIndex & 0x01) != 0) ? 0x1000 : 0x0000) : spritePatternBase;
            int tileNumber = tall ? (tileIndex & 0xFE) : tileIndex;

            int row = sy - spriteTop;
            int rowInTile = flipV ? (spriteHeight - 1 - row) : row;
            int tileOffset;
            if (tall) {
                int half = rowInTile / 8;
                int rowWithinHalf = rowInTile % 8;
                tileOffset = patternTableOffset + ((tileNumber + half) * 16) + rowWithinHalf;
            } else {
                tileOffset = patternTableOffset + (tileNumber * 16) + rowInTile;
            }

            int n = rowSpriteCount++;
            rowSpriteIndex[n] = i;
            rowSpriteX[n] = spriteX;
            rowSpritePlane0[n] = ppuRead(tileOffset) & 0xFF;
            rowSpritePlane1[n] = ppuRead(tileOffset + 8) & 0xFF;
            rowSpriteFlipH[n] = flipH;
            rowSpriteBehind[n] = behindBackground;
            rowSpritePaletteGroup[n] = paletteGroup;
        }
    }

    //Renders one pixel of the current scanline - background then sprites, in real
    //priority order - and, if this dot is where sprite 0's opaque pixel genuinely
    //overlaps an opaque background pixel, sets the sprite-0-hit flag right here. Called
    //once per PPU cycle for cycles 1-256 of each visible scanline (see step()), so the
    //flag becomes visible to the CPU at the actual x-position of the hit rather than
    //instantly at the start of the scanline - this is what lets games that time a
    //mid-frame scroll write off the hit's position (e.g. SMB's status-bar split) work.
    private void renderPixel(int sy, int x) {
        int rgb;
        boolean bgOpaque;
        if (!rowShowBackground || (x < 8 && !rowShowLeftBackground)) {
            rgb = rowBackdropRgb;
            bgOpaque = false;
        } else {
            int worldX = (rowWorldScrollX + x) & 0x1FF;
            int worldY = (rowWorldScrollY + sy) % 480;
            int nametableIndex = ((worldY / 240) * 2 + (worldX / 256)) * 1024;
            int tileX = (worldX % 256) / 8;
            int tileY = (worldY % 240) / 8;
            int px = worldX % 8;
            int py = worldY % 8;

            int tileID = nametables[(nametableIndex + tileY * 32 + tileX) & 0x07FF] & 0xFF;
            int tileOffset = rowBgTableOffset + (tileID * 16);

            //Attribute table: one byte per 4x4-tile (32x32px) block, 2 bits per 2x2-tile quadrant
            int attrX = tileX / 4;
            int attrY = tileY / 4;
            int attrByte = nametables[(nametableIndex + 0x3C0 + attrY * 8 + attrX) & 0x07FF] & 0xFF;
            int shift = ((tileY % 4) / 2) * 4 + ((tileX % 4) / 2) * 2;
            int paletteGroup = (attrByte >> shift) & 0x03;

            byte plane0 = ppuRead(tileOffset + py);
            byte plane1 = ppuRead(tileOffset + py + 8);
            int bit0 = (plane0 >> (7 - px)) & 0x01;
            int bit1 = (plane1 >> (7 - px)) & 0x01;
            int pixelVal = (bit1 << 1) | bit0;

            int paletteIndex = (pixelVal == 0)
                    ? (ppuRead(0x3F00) & 0x3F)
                    : (ppuRead(0x3F00 + paletteGroup * 4 + pixelVal) & 0x3F);
            rgb = NES_PALETTE[paletteIndex];
            bgOpaque = pixelVal != 0;
        }
        if (rowSpriteCount > 0 && (x >= 8 || rowShowLeftSprites)) {
            for (int n = 0; n < rowSpriteCount; n++) {
                int col = x - rowSpriteX[n];
                if (col < 0 || col > 7) continue;

                int bitIndex = rowSpriteFlipH[n] ? col : (7 - col);
                int bit0 = (rowSpritePlane0[n] >> bitIndex) & 0x01;
                int bit1 = (rowSpritePlane1[n] >> bitIndex) & 0x01;
                int pixelVal = (bit1 << 1) | bit0;
                if (pixelVal == 0) continue; //transparent

                //Real sprite-0-hit: never fires at x=255 (hardware quirk), and only
                //when this is genuinely OAM slot 0 with both an opaque sprite pixel and
                //an opaque background pixel actually landing on the same dot.
                if (rowSpriteIndex[n] == 0 && bgOpaque && x != 255) {
                    ppustatus |= 0x40;
                    hitOccurredThisFrame = true;
                }
                if (DEBUG_SPRITE0 && rowSpriteIndex[n] == 0) {
                    System.out.printf("sprite0 sy=%d x=%d pixelVal=%d bgHere=%b worldScrollX=%d%n", sy, x, pixelVal, bgOpaque, getWorldScrollX());
                }

                if (rowSpriteBehind[n] && bgOpaque) continue;

                int paletteIndex = ppuRead(0x3F10 + rowSpritePaletteGroup[n] * 4 + pixelVal) & 0x3F;
                rgb = NES_PALETTE[paletteIndex];
            }
        }

        if (display != null) display.setPixel(x, sy, rgb);
    }
}