public class Bus {
    //2KB NES Internal RAM ($0000 - $07FF)
    private final byte[] ram = new byte[2048];

    private CPU cpu;
    private final PPU ppu;
    private final APU apu;
    private Cartridge cartridge;

    public Bus(PPU ppu, APU apu) {
        this.ppu = ppu;
        this.apu = apu;
        this.ppu.connectBus(this);
    }

    public void connectCPU(CPU cpu) {
        this.cpu = cpu;
    }

    public void insertCartridge(Cartridge cart) {
        this.cartridge = cart;
    }

    public void reset() {
        if (cpu != null) cpu.reset();
        if (ppu != null) ppu.reset();
    }

    public byte cpuRead(int address) {
        address &= 0xFFFF; //Mask to 16 bit

        //1. 2KB Internal RAM mirrored up to $1FFF
        if (address >= 0x0000 && address <= 0x1FFF) {
            return ram[address & 0x7FF];
        }
        //2. PPU Registers ($2000 - $3FFF) mirrored every 8 bytes
        else if (address >= 0x2000 && address <= 0x3FFF) {
            return ppu.cpuRead(0x2000 + (address & 0x0007));
        }
        //3. APU & Direct I/O Registers ($4000 - $4017)
        else if (address >= 0x4000 && address <= 0x4017) {
            return apu.cpuRead(address);
        }
        //4. Cartridge Space ($4020 - $FFFF)
        else if (address >= 0x4020 && address <= 0xFFFF) {
            if (cartridge != null) return cartridge.cpuRead(address);
        }
        return 0;
    }

    public void cpuWrite(int address, byte data) {
        address &= 0xFFFF;

        //1. 2KB Internal RAM mirrored up to $1FFF
        if (address >= 0x0000 && address <= 0x1FFF) {
            ram[address & 0x07FF] = data;
        }

        //2. PPU Registers ($2000 - $3FFF)
        else if (address >= 0x2000 && address <= 0x3FFF) {
            ppu.cpuWrite(0x2000 + (address & 0x0007), data);
        }

        //3. APU & Direct I/O Registers ($4000 - $4017)
        else if (address >= 0x4000 && address <= 0x4017) {
            apu.cpuWrite(address, data);
        }
        //4. Cartridge Space
        else if (address >= 0x4020 && address <= 0xFFFF) {
            if (cartridge != null) cartridge.cpuWrite(address, data);
        }
    }

    public byte ppuReadCartridge(int address) {
        if (cartridge != null) return cartridge.ppuRead(address);
        return 0;
    }

    public void ppuWriteCartridge(int address, byte data) {
        if (cartridge != null) cartridge.ppuWrite(address, data);
    }
}