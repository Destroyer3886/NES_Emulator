public class PPU {

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

    //Internal latches and VRAM address counters
    private boolean addressLatch = false;
    private int vramAddress = 0x0000;
    private int tempAddress = 0x0000;
    private byte readBuffer = 0x00;

    public int cycle = 0;
    public int scanline = 0;

    private Bus bus;
    private CPU cpu;

    public void connectBus(Bus bus) { this.bus = bus; }
    public void connectCPU(CPU cpu) { this.cpu = cpu; }

    public void reset() {
        ppuctrl = 0; ppumask = 0; ppustatus = 0;
        oamaddr = 0; ppuaddr = 0; ppudata = 0;
        addressLatch = false; vramAddress = 0; tempAddress = 0;
        cycle = 0; scanline = 0;
    }

    public byte cpuRead(int address) {
        switch (address) {
            case 0x2002: //PPUSTATUS
                byte status = (byte) (ppustatus & 0xE0);
                ppustatus &= ~0x80; //Clear VBlank bit on read
                addressLatch = false; //Reset address latch
                return status;

            case 0x2004: //OAMDATA
                return oam[oamaddr & 0xFF];

            case 0x2007: //PPUDATA
                byte data = readBuffer;
                readBuffer = ppuRead(vramAddress);

                if (vramAddress >= 0x3F00) data = readBuffer; //Palette reads are unbuffered

                vramAddress += ((ppuctrl & 0x04) != 0) ? 32 : 1;
                vramAddress &= 0x3FFF;
                return data;

            default:
                return 0;
        }
    }

    public void cpuWrite(int address, byte data) {
        int val = data & 0xFF;
        switch (address) {
            case 0x2000: //PPUCTRL
                ppuctrl = val;
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
            case 0x2006: //PPUADDR
                if (!addressLatch) {
                    tempAddress = (tempAddress & 0x00FF) | ((val & 0x3F) << 8);
                    addressLatch = true;
                } else {
                    tempAddress = (tempAddress & 0x7F00) | val;
                    vramAddress = tempAddress;
                    addressLatch = false;
                }
                break;
            case 0x2007: //PPUDATA
                ppuWrite(vramAddress, data);
                vramAddress += ((ppuctrl & 0x04) != 0) ? 32 : 1;
                vramAddress &= 0x3FFF;
                break;
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

    //Advances PPU clock by 1 tick (3 PPU ticks per 1 CPU step)

    public void step() {
        cycle++;
        if (cycle >= 341) {
            cycle = 0;
            scanline++;
            if (scanline >= 261) {
                scanline = 0;
            }
        }

        //Trigger VBlank at Scanline 241, Cycle 1
        if (scanline == 241 && cycle == 1) {
            ppustatus |= 0x80; //Set VBlank flag
            if ((ppuctrl & 0x80) != 0 && cpu != null) {
                cpu.nmi(); //Trigger CPU NMI Interrupt
            }
        }

        if (scanline == 261 && cycle == 1) {
            ppustatus &=  ~0x80;
        }
    }

    //Render background tiles into the 512x480 virtual screen space
    public void renderToDisplay(NESDisplayPanel display) {
        int bgTableOffset = ((ppuctrl & 0x10) != 0) ? 0x1000 : 0x0000;

        for (int ntY = 0; ntY < 2; ntY++) {
            for (int ntX = 0; ntX < 2; ntX++) {
                int nametableIndex = (ntY * 2 + ntX) * 1024;
                int startWorldX = ntX * 256;
                int startWorldY = ntY * 240;

                for (int tileY = 0; tileY < 30; tileY++) {
                    for (int tileX = 0; tileX < 32; tileX++) {
                        int tileID = nametables[(nametableIndex + tileY * 32 + tileX) & 0x07FF] & 0xFF;
                        int tileOffset = bgTableOffset + (tileID * 16);

                        //Decode 8x8 CHR tile bitplanes
                        for (int py = 0; py < 8; py++) {
                            byte plane0 = ppuRead(tileOffset + py);
                            byte plane1 = ppuRead(tileOffset + py + 8);

                            for (int px = 0; px < 8; px++) {
                                int bit0 = (plane0 >> (7 - px)) & 0x01;
                                int bit1 = (plane1 >> (7 - px)) & 0x01;
                                int pixelVal = (bit1 << 1) | bit0; //Pixel color index (0-3)

                                int paletteIndex = ppuRead(0x3F00 + pixelVal) & 0x3F;
                                int rgb = NES_PALETTE[paletteIndex];

                                display.setWorldPixel(startWorldX + tileX * 8 + px, startWorldY + tileY * 8 + py, rgb);
                            }
                        }
                    }
                }
            }
        }
    }
}