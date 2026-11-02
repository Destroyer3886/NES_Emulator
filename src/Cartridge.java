import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

//A loaded .nes image: parses the iNES/NES 2.0 header, sizes ROM/RAM the way the real board
//populates them, picks the Mapper, and fronts it for the Bus/PPU/debuggers.
public class Cartridge {
    //How the four logical 1KB nametables fold onto physical VRAM; many boards change it at
    //runtime via a register write, so it lives per-cartridge.
    public enum MirrorMode { HORIZONTAL, VERTICAL, SINGLE_SCREEN_LOW, SINGLE_SCREEN_HIGH, FOUR_SCREEN }

    final byte[] prgRom;
    final byte[] chrMem;      //CHR ROM, or CHR RAM when chrIsRam
    final boolean chrIsRam;
    final byte[] prgRam;      //work/save RAM at $6000-$7FFF (and wherever the board banks it)
    final boolean battery;
    final int mapperID;
    final int submapper;
    final boolean nes20;
    final boolean fourScreen;
    private final boolean headerMirrorHorizontal;

    private final Mapper mapper;
    private CPU cpu;

    public Cartridge(File file) throws IOException {
        byte[] header = new byte[16];
        try (FileInputStream fis = new FileInputStream(file)) {
            if (fis.read(header) != 16) throw new IOException("Invalid iNES ROM file (header too short)");
            if (header[0] != 'N' || header[1] != 'E' || header[2] != 'S' || header[3] != 0x1A) {
                throw new IOException("Not a valid iNES ROM file header");
            }

            int flags6 = header[6] & 0xFF;
            int flags7 = header[7] & 0xFF;
            nes20 = (flags7 & 0x0C) == 0x08;
            //iNES 1.0 files written by old tools often carry junk in bytes 7-15
            //("DiskDude!"); the convention is to trust only the low mapper nibble then.
            boolean archaic = !nes20 && (header[12] != 0 || header[13] != 0 || header[14] != 0 || header[15] != 0);

            int mapper = (flags6 >> 4);
            int sub = 0;
            long prgSize, chrSize;
            int prgRamSize, chrRamSize;
            if (nes20) {
                mapper |= (flags7 & 0xF0) | ((header[8] & 0x0F) << 8);
                sub = (header[8] & 0xF0) >> 4;
                prgSize = nes20Size(header[4] & 0xFF, header[9] & 0x0F, 16384);
                chrSize = nes20Size(header[5] & 0xFF, (header[9] & 0xF0) >> 4, 8192);
                int prgRamShift = header[10] & 0x0F, prgNvShift = (header[10] & 0xF0) >> 4;
                int chrRamShift = header[11] & 0x0F, chrNvShift = (header[11] & 0xF0) >> 4;
                prgRamSize = Math.max(prgRamShift == 0 ? 0 : (64 << prgRamShift), prgNvShift == 0 ? 0 : (64 << prgNvShift));
                chrRamSize = Math.max(chrRamShift == 0 ? 0 : (64 << chrRamShift), chrNvShift == 0 ? 0 : (64 << chrNvShift));
            } else {
                if (!archaic) mapper |= (flags7 & 0xF0);
                prgSize = (long) (header[4] & 0xFF) * 16384;
                chrSize = (long) (header[5] & 0xFF) * 8192;
                prgRamSize = -1; //decided per board below
                chrRamSize = -1;
            }
            mapperID = mapper;
            submapper = sub;
            battery = (flags6 & 0x02) != 0;
            fourScreen = (flags6 & 0x08) != 0;
            headerMirrorHorizontal = (flags6 & 0x01) == 0;

            if (!isSupported(mapperID)) {
                throw new IOException("Unsupported mapper " + mapperID + " - supported boards: "
                        + "NROM, SxROM/MMC1, UxROM, UN1ROM, CNROM, TxROM/MMC3, HKROM/MMC6, TxSROM, TQROM, DxROM, "
                        + "ExROM/MMC5, AxROM, PxROM/MMC2, FxROM/MMC4, CPROM, BNROM/NINA-001, GxROM, NES-EVENT");
            }

            if ((flags6 & 0x04) != 0) fis.skip(512); //trainer

            prgRom = new byte[(int) Math.max(prgSize, 16384)];
            readFully(fis, prgRom, (int) Math.min(prgSize, prgRom.length));

            if (chrSize == 0) {
                chrIsRam = true;
                if (chrRamSize <= 0) chrRamSize = defaultChrRamSize(mapperID);
                chrMem = new byte[chrRamSize];
            } else {
                chrIsRam = false;
                chrMem = new byte[(int) chrSize];
                readFully(fis, chrMem, chrMem.length);
            }

            if (prgRamSize < 0) {
                //iNES 1.0: byte 8 is the PRG RAM size in 8KB units, but 0 historically
                //meant "8KB if the board has any", so fall back to what the board carries.
                int b8 = archaic ? 0 : (header[8] & 0xFF);
                prgRamSize = b8 != 0 ? b8 * 8192 : defaultPrgRamSize(mapperID, battery, chrIsRam);
            }
            prgRam = new byte[prgRamSize];
        }

        mapper = createMapper(mapperID, submapper);
        mapper.init(this);
        mapper.reset();
    }

    private static void readFully(FileInputStream fis, byte[] dst, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = fis.read(dst, off, len - off);
            if (n < 0) break; //truncated image: leave the rest zero (like an unprogrammed ROM)
            off += n;
        }
    }

    //NES 2.0 ROM sizes: plain unit count, or (when the MSB nibble is $F) the
    //exponent-multiplier form 2^E * (MM*2+1).
    private static long nes20Size(int lsb, int msb, int unit) {
        if (msb == 0x0F) {
            int exp = lsb >> 2, mul = (lsb & 3) * 2 + 1;
            return (1L << exp) * mul;
        }
        return (long) ((msb << 8) | lsb) * unit;
    }

    private static boolean isSupported(int id) {
        switch (id) {
            case 0: case 1: case 2: case 3: case 4: case 5: case 7: case 9: case 10: case 13:
            case 34: case 66: case 94: case 105: case 118: case 119: case 155: case 180: case 185: case 206:
                return true;
            default: return false;
        }
    }

    private static int defaultChrRamSize(int mapperID) {
        return mapperID == 13 ? 16384 : 8192; //CPROM carries 16KB; every other RAM board 8KB
    }

    //What the real board family carries when the header doesn't say
    private static int defaultPrgRamSize(int mapperID, boolean battery, boolean chrIsRam) {
        switch (mapperID) {
            case 0: return battery ? 8192 : 0;           //Family BASIC is the only NROM with RAM
            case 1: case 155: case 105: return 8192;     //SNROM/SKROM/SOROM-class default; NES 2.0 says when it's 32KB (SXROM)
            case 4: case 118: return 8192;               //TKROM/TSROM/TLSROM; TGROM/TLROM leave it empty but 8KB is harmless
            case 5: return 65536;                        //ExROM: without a header size, give every bank select something to hit
            case 10: return 8192;                        //FxROM
            case 34: return chrIsRam ? 0 : 8192;         //BNROM: none; NINA-001: 8KB
            default: return 0;
        }
    }

    private static Mapper createMapper(int id, int sub) {
        switch (id) {
            case 0: return new MapperNROM();
            case 1: case 155: return new MapperMMC1(id == 155);
            case 2: case 94: case 180: return new MapperUxROM(id);
            case 3: case 185: return new MapperCNROM(id == 185);
            case 4: case 118: case 119: case 206: return new MapperMMC3(id);
            case 5: return new MapperMMC5();
            case 7: return new MapperAxROM();
            case 9: case 10: return new MapperMMC2(id == 10);
            case 13: return new MapperCPROM();
            case 34: return new MapperBNROM();
            case 66: return new MapperGxROM();
            case 105: return new MapperEvent();
            default: throw new IllegalArgumentException("mapper " + id);
        }
    }

    MirrorMode headerMirroring() {
        return fourScreen ? MirrorMode.FOUR_SCREEN : (headerMirrorHorizontal ? MirrorMode.HORIZONTAL : MirrorMode.VERTICAL);
    }

    public void connectCPU(CPU cpu) {
        this.cpu = cpu;
        mapper.connectCPU(cpu);
    }

    //---- CPU bus ($4020-$FFFF). -1 = open bus. --------------------------------------
    public int cpuRead(int address) { return mapper.cpuRead(address & 0xFFFF); }
    public int cpuPeek(int address) { return mapper.cpuPeek(address & 0xFFFF); }
    public void cpuWrite(int address, int data) { mapper.cpuWrite(address & 0xFFFF, data & 0xFF); }
    public void cpuBusSnoop(int address, int data) { mapper.cpuBusSnoop(address & 0xFFFF, data & 0xFF); }
    public void cpuClock() { mapper.cpuClock(); }

    //---- PPU bus -------------------------------------------------------------------
    public byte ppuRead(int address) { return (byte) mapper.chrRead(address & 0x1FFF); }
    public byte ppuPeek(int address) { return (byte) mapper.chrPeek(address & 0x1FFF); }
    public void ppuWrite(int address, byte data) { mapper.chrWrite(address & 0x1FFF, data & 0xFF); }
    public byte ntRead(int address, byte[] ciram) { return (byte) mapper.ntRead(address & 0x2FFF, ciram); }
    public byte ntPeek(int address, byte[] ciram) { return (byte) mapper.ntPeek(address & 0x2FFF, ciram); }
    public void ntWrite(int address, byte data, byte[] ciram) { mapper.ntWrite(address & 0x2FFF, data & 0xFF, ciram); }

    //Called on every real or approximated PPU A12 rising edge: PPU.clockA12() tracks true edges
    //for $2006/$2007 accesses, step()'s cycle 257/321 sites approximate it per scanline.
    public void clockMapperIRQ() { mapper.ppuA12Rise(); }

    public double audioOutput() { return mapper.audioOutput(); }

    public boolean isMirrorHorizontal() { return headerMirrorHorizontal; }
    public MirrorMode getMirrorMode() { return mapper.mirrorMode(); }
    public int getMapperID() { return mapperID; }
    public int getSubmapper() { return submapper; }
    public String getBoardName() { return mapper.name(); }
    public boolean hasChrRam() { return chrIsRam; }
    public boolean hasPrgRam() { return prgRam.length > 0; }
    public int getPrgRamSize() { return prgRam.length; }

    //Precise Tracing bank display: the 8KB PRG bank behind each of the four CPU slots and the
    //1KB CHR bank behind each of the eight PPU slots.
    public int getPrgBankCount8k() { return Math.max(prgRom.length / 8192, 1); }
    public int getChrBankCount1k() { return Math.max(chrMem.length / 1024, 1); }
    public int[] debugPrgBanks() { return mapper.debugPrgBanks(); }
    public int[] debugChrBanks() { return mapper.debugChrBanks(); }
    public String[] debugPrgNotes() { return mapper.debugPrgNotes(); }
    public String describeWrite(int address, int data) { return mapper.describeWrite(address & 0xFFFF, data & 0xFF); }
    public String describeState() { return mapper.describeState(); }

    public String describeHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("Mapper ").append(mapperID);
        if (nes20) sb.append('.').append(submapper);
        sb.append(" (").append(mapper.name()).append(")");
        sb.append(nes20 ? ", NES 2.0 header" : ", iNES header");
        sb.append(String.format("%nPRG ROM %dKB, CHR %s %dKB, PRG RAM %dKB%s%s",
                prgRom.length / 1024, chrIsRam ? "RAM" : "ROM", chrMem.length / 1024, prgRam.length / 1024,
                battery ? " (battery)" : "", fourScreen ? ", four-screen VRAM" : ""));
        return sb.toString();
    }

    //Back to power-on (CHR RAM, PRG RAM, mapper registers); used by TAS Maker
    //replay-from-power-on so repeated replays of one input log are deterministic.
    public void hardReset() {
        if (chrIsRam) java.util.Arrays.fill(chrMem, (byte) 0);
        Bus.fillPowerOnPattern(prgRam); //same SRAM boot pattern as the console's work RAM
        mapper.reset();
    }

    //TAS Maker greenzone checkpoint: ROM and header never change, so only CHR RAM, PRG RAM
    //and the mapper's own serialized registers are captured.
    public static final class State {
        byte[] chrRam, prgRam, mapper;
    }

    public State snapshot() {
        State s = new State();
        s.chrRam = chrIsRam ? chrMem.clone() : null;
        s.prgRam = prgRam.clone();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bos);
            mapper.saveState(out);
            out.flush();
            s.mapper = bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    public void restore(State s) {
        if (chrIsRam && s.chrRam != null) System.arraycopy(s.chrRam, 0, chrMem, 0, chrMem.length);
        System.arraycopy(s.prgRam, 0, prgRam, 0, prgRam.length);
        try {
            mapper.loadState(new DataInputStream(new ByteArrayInputStream(s.mapper)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
