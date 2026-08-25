import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class Cartridge {
    //How the 4 logical 1KB nametables ($2000/$2400/$2800/$2C00) fold onto the
    //physical VRAM the PPU actually has. MMC3 (and similar mappers) can change this
    //at any time via a mapper register write, which is why it lives per-cartridge
    //instead of being a fixed fact decided once at load time.
    public enum MirrorMode { HORIZONTAL, VERTICAL, SINGLE_SCREEN_LOW, SINGLE_SCREEN_HIGH, FOUR_SCREEN }

    private byte[] prgMemory;
    private byte[] chrMemory;
    private final byte[] prgRam = new byte[8192]; //$6000-$7FFF, used by mapper 4 boards with battery/work RAM

    private int mapperID = 0;
    private int prgBanks = 0; //16KB units, from the iNES header
    private int chrBanks = 0; //8KB units, from the iNES header
    private boolean headerMirrorHorizontal = false;
    private boolean fourScreen = false;
    private MirrorMode mirrorMode = MirrorMode.VERTICAL;

    private CPU cpu;
    public void connectCPU(CPU cpu) { this.cpu = cpu; }

    //---- Mapper 4 (MMC3) register state -----------------------------------------
    //$8000: low 3 bits pick which of R0-R7 the next $8001 write targets, bit 6
    //picks the PRG bank-swap layout, bit 7 picks the CHR bank-swap layout.
    private int bankSelect = 0;
    private final int[] bankRegs = new int[8]; //R0-R7, raw values as last written
    private boolean prgRamEnabled = true;
    private boolean prgRamWriteProtect = false;
    private int irqLatch = 0;
    private int irqCounter = 0;
    private boolean irqReloadFlag = false;
    private boolean irqEnabled = false;

    public Cartridge(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[16];
            if (fis.read(header) != 16) {
                throw new IOException("Invalid iNES ROM file (header too short)");
            }

            //Verify "NES<EOF>" signature
            if (header[0] != 'N' || header[1] != 'E' || header[2] != 'S' || header[3] != 0x1A) {
                throw new IOException("Not a valid iNES ROM file header");
            }

            prgBanks = header[4] & 0xFF; //16KB units
            chrBanks = header[5] & 0xFF; //8KB units

            int flags6 = header[6] & 0xFF;
            int flags7 = header[7] & 0xFF;

            mapperID = (flags7 & 0xF0) | (flags6 >> 4);
            headerMirrorHorizontal = (flags6 & 0x01) == 0;
            fourScreen = (flags6 & 0x08) != 0;

            if (mapperID != 0 && mapperID != 4) {
                throw new IOException("Unsupported mapper " + mapperID + " (only NROM/mapper 0 and MMC3/mapper 4 are supported)");
            }

            mirrorMode = fourScreen ? MirrorMode.FOUR_SCREEN
                    : (headerMirrorHorizontal ? MirrorMode.HORIZONTAL : MirrorMode.VERTICAL);

            //Skip trainer if present (512 bytes)
            if ((flags6 & 0x04) != 0) {
                fis.skip(512);
            }

            //Read PRG ROM
            prgMemory = new byte[Math.max(prgBanks, 1) * 16384];
            fis.read(prgMemory);

            //Read CHR ROM or allocate CHR RAM
            if (chrBanks == 0) {
                chrMemory = new byte[8192];
            } else {
                chrMemory = new byte[chrBanks * 8192];
                fis.read(chrMemory);
            }
        }
    }

    private boolean isMmc3() { return mapperID == 4; }

    public byte cpuRead(int address) {
        if (isMmc3() && address >= 0x6000 && address <= 0x7FFF) {
            return prgRamEnabled ? prgRam[address & 0x1FFF] : 0;
        }
        if (address >= 0x8000 && address <= 0xFFFF) {
            if (isMmc3()) {
                return prgMemory[prgIndex(address)];
            }
            int mappedAddr = address - 0x8000;
            if (prgBanks == 1) { //16 PRG ROM mirrored ($8000-$BFFF and $C000-$FFFF)
                mappedAddr &= 0x3FFF;
            }
            return prgMemory[mappedAddr];
        }
        return 0;
    }

    public void cpuWrite(int address, byte data) {
        if (!isMmc3()) return; //NROM PRG ROM is read-only, and has no PRG RAM

        if (address >= 0x6000 && address <= 0x7FFF) {
            if (prgRamEnabled && !prgRamWriteProtect) prgRam[address & 0x1FFF] = data;
            return;
        }
        if (address < 0x8000 || address > 0xFFFF) return;

        int val = data & 0xFF;
        switch (address & 0xE001) {
            case 0x8000:
                bankSelect = val;
                break;
            case 0x8001:
                bankRegs[bankSelect & 0x07] = val;
                break;
            case 0xA000:
                if (!fourScreen) mirrorMode = (val & 0x01) != 0 ? MirrorMode.HORIZONTAL : MirrorMode.VERTICAL;
                break;
            case 0xA001:
                prgRamEnabled = (val & 0x80) != 0;
                prgRamWriteProtect = (val & 0x40) != 0;
                break;
            case 0xC000:
                irqLatch = val;
                break;
            case 0xC001:
                irqReloadFlag = true;
                break;
            case 0xE000:
                irqEnabled = false;
                if (cpu != null) cpu.clearMapperIRQ(); //writing here also acknowledges any pending IRQ
                break;
            case 0xE001:
                irqEnabled = true;
                break;
        }
    }

    //MMC3 8KB PRG bank layout: $A000-$BFFF is always R7; one of $8000/$C000 is R6
    //and the other is fixed to the second-to-last bank (selected by bankSelect bit
    //6); $E000-$FFFF is always fixed to the very last bank.
    private int prgIndex(int address) {
        int prg8kCount = Math.max(prgBanks, 1) * 2;
        int offset = address - 0x8000;
        int slot = offset >> 13;
        int within = offset & 0x1FFF;
        boolean swapMode = (bankSelect & 0x40) != 0;
        int last = prg8kCount - 1;
        int bank;
        switch (slot) {
            case 0: bank = swapMode ? (last - 1) : (bankRegs[6] & 0x3F); break;
            case 1: bank = bankRegs[7] & 0x3F; break;
            case 2: bank = swapMode ? (bankRegs[6] & 0x3F) : (last - 1); break;
            default: bank = last; break;
        }
        bank = ((bank % prg8kCount) + prg8kCount) % prg8kCount;
        return bank * 8192 + within;
    }

    //MMC3 CHR bank layout: two 2KB windows (R0, R1) and four 1KB windows (R2-R5),
    //with bankSelect bit 7 swapping which half of the $0000-$1FFF space gets the
    //2KB windows vs the 1KB windows.
    private int chrIndex(int address) {
        int chr1kCount = Math.max(chrMemory.length / 1024, 1);
        address &= 0x1FFF;
        int seg = address >> 10; //0-7
        if ((bankSelect & 0x80) != 0) seg ^= 0x04;
        int oneKBank;
        switch (seg) {
            case 0: oneKBank = bankRegs[0] & 0xFE; break;
            case 1: oneKBank = (bankRegs[0] & 0xFE) + 1; break;
            case 2: oneKBank = bankRegs[1] & 0xFE; break;
            case 3: oneKBank = (bankRegs[1] & 0xFE) + 1; break;
            case 4: oneKBank = bankRegs[2]; break;
            case 5: oneKBank = bankRegs[3]; break;
            case 6: oneKBank = bankRegs[4]; break;
            default: oneKBank = bankRegs[5]; break;
        }
        oneKBank = ((oneKBank % chr1kCount) + chr1kCount) % chr1kCount;
        return oneKBank * 1024 + (address & 0x3FF);
    }

    public byte ppuRead(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            int idx = isMmc3() ? chrIndex(address) : (address & 0x1FFF);
            return chrMemory[idx];
        }
        return 0;
    }

    public void ppuWrite(int address, byte data) {
        if (chrBanks != 0 || address < 0x0000 || address > 0x1FFF) return; //CHR ROM is read-only
        int idx = isMmc3() ? chrIndex(address) : (address & 0x1FFF);
        chrMemory[idx] = data; //Write to CHR RAM
    }

    //Called on every real or approximated MMC3 A12 rising edge - see PPU's clockA12()
    //(true edge tracking for CPU-side $2006/$2007 accesses) and step()'s cycle==257/321
    //sites (a per-scanline approximation of A12 during actual rendering, since this PPU
    //renders per-pixel rather than modeling every individual per-dot pattern fetch).
    public void clockMapperIRQ() {
        if (!isMmc3()) return;
        if (irqCounter == 0 || irqReloadFlag) {
            irqCounter = irqLatch;
            irqReloadFlag = false;
        } else {
            irqCounter--;
        }
        if (irqCounter == 0 && irqEnabled && cpu != null) {
            cpu.raiseMapperIRQ();
        }
    }

    public boolean isMirrorHorizontal() { return headerMirrorHorizontal; }
    public MirrorMode getMirrorMode() { return mirrorMode; }
    public int getMapperID() { return mapperID; }

    //Clears CHR RAM (if the cartridge has any) and PRG RAM, and resets mapper
    //register state, back to power-on zeroes. Used only by the TAS Maker's
    //replay-from-power-on seeking, so repeated replays of the same input log are
    //fully deterministic instead of depending on whatever a previous replay left in
    //RAM/mapper state.
    public void hardReset() {
        if (chrBanks == 0) java.util.Arrays.fill(chrMemory, (byte) 0);
        java.util.Arrays.fill(prgRam, (byte) 0);
        bankSelect = 0;
        java.util.Arrays.fill(bankRegs, 0);
        prgRamEnabled = true;
        prgRamWriteProtect = false;
        irqLatch = 0;
        irqCounter = 0;
        irqReloadFlag = false;
        irqEnabled = false;
        mirrorMode = fourScreen ? MirrorMode.FOUR_SCREEN
                : (headerMirrorHorizontal ? MirrorMode.HORIZONTAL : MirrorMode.VERTICAL);
    }

    //TAS Maker greenzone checkpoint support. prgMemory/mapperID/prgBanks/chrBanks/
    //headerMirrorHorizontal/fourScreen are never mutated after load, so only CHR RAM,
    //PRG RAM, and the mapper's own register state (bank selects, IRQ counter,
    //current mirror mode) need capturing.
    public static final class State {
        byte[] chr, prgRam;
        int bankSelect;
        int[] bankRegs;
        boolean prgRamEnabled, prgRamWriteProtect;
        int irqLatch, irqCounter;
        boolean irqReloadFlag, irqEnabled;
        MirrorMode mirrorMode;
    }

    public State snapshot() {
        State s = new State();
        s.chr = chrMemory.clone();
        s.prgRam = prgRam.clone();
        s.bankSelect = bankSelect;
        s.bankRegs = bankRegs.clone();
        s.prgRamEnabled = prgRamEnabled;
        s.prgRamWriteProtect = prgRamWriteProtect;
        s.irqLatch = irqLatch;
        s.irqCounter = irqCounter;
        s.irqReloadFlag = irqReloadFlag;
        s.irqEnabled = irqEnabled;
        s.mirrorMode = mirrorMode;
        return s;
    }

    public void restore(State s) {
        System.arraycopy(s.chr, 0, chrMemory, 0, chrMemory.length);
        System.arraycopy(s.prgRam, 0, prgRam, 0, prgRam.length);
        bankSelect = s.bankSelect;
        System.arraycopy(s.bankRegs, 0, bankRegs, 0, bankRegs.length);
        prgRamEnabled = s.prgRamEnabled;
        prgRamWriteProtect = s.prgRamWriteProtect;
        irqLatch = s.irqLatch;
        irqCounter = s.irqCounter;
        irqReloadFlag = s.irqReloadFlag;
        irqEnabled = s.irqEnabled;
        mirrorMode = s.mirrorMode;
    }
}
