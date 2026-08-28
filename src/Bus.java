public class Bus {
    //2KB Internal RAM ($0000-$07FF)
    private final byte[] ram = new byte[2048];

    private CPU cpu;
    private final PPU ppu;
    private final APU apu;
    private Cartridge cartridge;

    //Controller shift registers ($4016/$4017), LSB-first: A,B,Select,Start,Up,Down,Left,Right
    private int controller1 = 0; //live button bitmask from UI
    private int controller2 = 0;
    private int controller1Shift = 0;
    private int controller2Shift = 0;
    private boolean controllerStrobe = false;

    //Set by a $4016 strobe write; consumed each frame by TAS Maker to detect lag frames
    private boolean strobedThisFrame = false;
    public boolean consumeStrobedThisFrame() {
        boolean result = strobedThisFrame;
        strobedThisFrame = false;
        return result;
    }

    //Cycle the $4016 strobe line was last asserted; used to check if a "put" cycle occurred
    private long strobeHighSince = 0;

    private void latchControllers() {
        controller1Shift = controller1;
        controller2Shift = controller2;
    }

    public void setController1(int buttons) {
        controller1 = buttons & 0xFF;
        if (controllerStrobe) controller1Shift = controller1;
    }

    public void setController2(int buttons) {
        controller2 = buttons & 0xFF;
        if (controllerStrobe) controller2Shift = controller2;
    }

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

    //Full power-on reset (vs. soft reset() above); used by TAS Maker replay-from-power-on
    //so replays of the same input log land on identical state
    public void hardReset() {
        if (cpu != null) cpu.reset();
        if (ppu != null) ppu.hardReset();
        if (apu != null) apu.reset();
        if (cartridge != null) cartridge.hardReset();
        java.util.Arrays.fill(ram, (byte) 0);
        //Re-pin Memory Inspector "Freeze"s so power cycle doesn't drop them
        for (java.util.Map.Entry<Integer, Byte> entry : frozenRam.entrySet()) ram[entry.getKey()] = entry.getValue();
        controller1 = 0;
        controller2 = 0;
        controller1Shift = 0;
        controller2Shift = 0;
        strobedThisFrame = false;
        controllerStrobe = false;
    }

    public byte cpuRead(int address) {
        address &= 0xFFFF; //mask to 16-bit

        //1. Internal RAM, mirrored to $1FFF
        if (address >= 0x0000 && address <= 0x1FFF) {
            return ram[address & 0x7FF];
        }
        //2. PPU Registers, mirrored every 8 bytes
        else if (address >= 0x2000 && address <= 0x3FFF) {
            return ppu.cpuRead(0x2000 + (address & 0x0007));
        }
        //3. Controller Port 1: shift out one bit per read; upper 3 bits open bus
        else if (address == 0x4016) {
            int bit;
            if (controllerStrobe) {
                bit = controller1 & 0x01;
            } else {
                bit = controller1Shift & 0x01;
                controller1Shift = (controller1Shift >> 1) | 0x80;
            }
            return (byte) ((cpuOpenBus() & 0xE0) | bit);
        }
        //4. Controller Port 2
        else if (address == 0x4017) {
            int bit;
            if (controllerStrobe) {
                bit = controller2 & 0x01;
            } else {
                bit = controller2Shift & 0x01;
                controller2Shift = (controller2Shift >> 1) | 0x80;
            }
            return (byte) ((cpuOpenBus() & 0xE0) | bit);
        }
        //5. APU & Direct I/O Registers
        else if (address >= 0x4000 && address <= 0x4015) {
            return apu.cpuRead(address);
        }
        //6. Cartridge PRG RAM (MMC3 only) and PRG ROM; NROM has nothing here, falls to open bus
        else if (address >= 0x6000 && address <= 0x7FFF) {
            if (cartridge != null && cartridge.getMapperID() == 4) return cartridge.cpuRead(address);
        }
        else if (address >= 0x8000 && address <= 0xFFFF) {
            if (cartridge != null) return cartridge.cpuRead(address);
        }
        //Anything unmapped is open bus: the floating CPU data bus
        return (byte) cpuOpenBus();
    }

    //Current value of the CPU's floating data bus, returned for open-bus reads
    private int cpuOpenBus() {
        return cpu != null ? cpu.dataBus : 0;
    }

    public void cpuWrite(int address, byte data) {
        address &= 0xFFFF;

        //1. Internal RAM, mirrored to $1FFF
        if (address >= 0x0000 && address <= 0x1FFF) {
            int ramAddr = address & 0x07FF;
            //Frozen (Memory Inspector "Freeze") addresses ignore writes
            Byte frozenValue = frozenRam.get(ramAddr);
            ram[ramAddr] = frozenValue != null ? frozenValue : data;
        }

        //2. PPU Registers
        else if (address >= 0x2000 && address <= 0x3FFF) {
            ppu.cpuWrite(0x2000 + (address & 0x0007), data);
        }

        //3. OAM DMA: copies a 256-byte page into PPU OAM, stalling the CPU
        else if (address == 0x4014) {
            oamDma(data & 0xFF);
        }
        //4. Controller Strobe: while high, shift registers continuously reload from
        //   buttons; falling edge latches them
        else if (address == 0x4016) {
            //Any write with bit 0 set strobes controllers, regardless of prior state,
            //so TAS Maker can detect lag frames (AccuracyCoin "Controller Strobing" test 2)
            if ((data & 0x01) != 0) strobedThisFrame = true;
            boolean nowHigh = (data & 0x01) != 0;
            //The strobe line just holds parallel-load asserted; actual latch happens on
            //CPU "put" cycles during assertion. STA/STA holds ~6 cycles (always spans a
            //put, always latches); DEC holds exactly 1 cycle, so whether it latches
            //depends on put vs. get for that single cycle (AccuracyCoin tests 3 & 4).
            //totalCycles isn't incremented yet for this write's own cycle, so an even
            //value here means a put cycle.
            if (nowHigh && !controllerStrobe) {
                strobeHighSince = cpu.totalCycles;
                if ((cpu.totalCycles & 1L) == 0) latchControllers();
            } else if (!nowHigh && controllerStrobe) {
                //Falling edge: latch if any cycle in the asserted window was a put cycle
                long first = strobeHighSince, last = cpu.totalCycles - 1;
                if ((first & 1L) == 0 || last > first) latchControllers();
            }
            controllerStrobe = nowHigh;
        }
        //5. APU & Direct I/O Registers, incl. $4017 frame counter
        else if (address >= 0x4000 && address <= 0x4017) {
            apu.cpuWrite(address, data);
        }
        //6. Cartridge Space
        else if (address >= 0x4020 && address <= 0xFFFF) {
            if (cartridge != null) cartridge.cpuWrite(address, data);
        }
    }

    //Memory Inspector "Freeze"/"Override" support, keyed by mirrored RAM address
    private final java.util.Map<Integer, Byte> frozenRam = new java.util.HashMap<>();

    public void freezeRamAddress(int address, byte value) {
        int ramAddr = address & 0x07FF;
        frozenRam.put(ramAddr, value);
        ram[ramAddr] = value;
    }

    public void unfreezeRamAddress(int address) {
        frozenRam.remove(address & 0x07FF);
    }

    public boolean isRamAddressFrozen(int address) {
        return frozenRam.containsKey(address & 0x07FF);
    }

    //Forces a value into RAM (Memory Inspector "Override"); updates frozen value too
    public void overrideRamAddress(int address, byte value) {
        int ramAddr = address & 0x07FF;
        ram[ramAddr] = value;
        if (frozenRam.containsKey(ramAddr)) frozenRam.put(ramAddr, value);
    }

    //Non-mutating full-address-space read for the memory inspector; no register side-effects
    public byte debugRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x0000 && address <= 0x1FFF) {
            return ram[address & 0x07FF];
        } else if (address >= 0x2000 && address <= 0x3FFF) {
            return ppu.debugRead(0x2000 + (address & 0x0007));
        } else if (address == 0x4014) {
            return 0; //write-only latch
        } else if (address == 0x4016) {
            return (byte) (controllerStrobe ? (controller1 & 0x01) : (controller1Shift & 0x01));
        } else if (address == 0x4017) {
            return (byte) (controllerStrobe ? (controller2 & 0x01) : (controller2Shift & 0x01));
        } else if (address >= 0x4000 && address <= 0x4015) {
            return apu.cpuRead(address);
        } else if (address >= 0x4020 && address <= 0xFFFF) {
            if (cartridge != null) return cartridge.cpuRead(address);
        }
        return 0;
    }

    //Delegates to CPU's cycle-stepped DMA state machine so DMC DMA can interleave mid-transfer
    private void oamDma(int page) {
        if (cpu != null) cpu.startOamDma(page);
    }

    //TAS Maker greenzone checkpoint: full machine snapshot, one object per checkpointed frame
    public static final class EmulatorState {
        CPU.State cpu;
        PPU.State ppu;
        APU.State apu;
        byte[] ram;
        Cartridge.State cart;
        int controller1, controller2, controller1Shift, controller2Shift;
        boolean controllerStrobe;
        boolean strobedThisFrame;
    }

    public EmulatorState snapshot() {
        EmulatorState s = new EmulatorState();
        s.cpu = cpu.snapshot();
        s.ppu = ppu.snapshot();
        s.apu = apu.snapshot();
        s.ram = ram.clone();
        s.cart = cartridge != null ? cartridge.snapshot() : null;
        s.controller1 = controller1; s.controller2 = controller2;
        s.controller1Shift = controller1Shift; s.controller2Shift = controller2Shift;
        s.controllerStrobe = controllerStrobe;
        s.strobedThisFrame = strobedThisFrame;
        return s;
    }

    public void restore(EmulatorState s) {
        cpu.restore(s.cpu);
        ppu.restore(s.ppu);
        apu.restore(s.apu);
        System.arraycopy(s.ram, 0, ram, 0, ram.length);
        if (cartridge != null && s.cart != null) cartridge.restore(s.cart);
        controller1 = s.controller1; controller2 = s.controller2;
        controller1Shift = s.controller1Shift; controller2Shift = s.controller2Shift;
        controllerStrobe = s.controllerStrobe;
        strobedThisFrame = s.strobedThisFrame;
    }

    public byte ppuReadCartridge(int address) {
        if (cartridge != null) return cartridge.ppuRead(address);
        return 0;
    }

    public void ppuWriteCartridge(int address, byte data) {
        if (cartridge != null) cartridge.ppuWrite(address, data);
    }

    public Cartridge.MirrorMode getMirrorMode() {
        return cartridge != null ? cartridge.getMirrorMode() : Cartridge.MirrorMode.VERTICAL;
    }

    //Called once per scanline to drive a mapper's IRQ counter (currently MMC3 only)
    public void clockMapperIRQ() {
        if (cartridge != null) cartridge.clockMapperIRQ();
    }
}