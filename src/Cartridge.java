import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class Cartridge {
    private byte[] prgMemory;
    private byte[] chrMemory;

    private int mapperID = 0;
    private int prgBanks = 0;
    private int chrBanks = 0;
    private boolean mirrorHorizontal = false;

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
            mirrorHorizontal = (flags6 & 0x01) == 0;

            //Skip trainer if present (512 bytes)
            if ((flags6 & 0x04) != 0) {
                fis.skip(512);
            }

            //Read PRG ROM
            prgMemory = new byte[prgBanks * 16384];
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

    public byte cpuRead(int address) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            int mappedAddr = address - 0x8000;
            if (prgBanks == 1) { //16 PRG ROM mirrored ($8000-$BFFF and $C000-$FFFF)
                mappedAddr &= 0x3FFF;
            }
            return prgMemory[mappedAddr];
        }
            return 0;
    }

    public void cpuWrite(int address, byte data) {
        //NROM PRGROM is read-only
    }

    public byte ppuRead(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            return chrMemory[address & 0x1FFF];
        }
        return 0;
    }

    public void ppuWrite(int address, byte data) {
        if (chrBanks == 0 && address >= 0x0000 && address <= 0x1FFF) {
            chrMemory[address & 0x1FFF] = data; //Write to CHR RAM
        }
    }

    public boolean isMirrorHorizontal() { return mirrorHorizontal; }
    public int getMapperID() { return mapperID; }
}