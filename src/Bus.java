public class Bus {
    //2KB NES Internal RAM ($0000 - $07FF)
    private final byte[] ram = new byte[2048];

    private CPU cpu;
    private final PPU ppu;
    private final APU apu;
    private Cartridge cartridge;

    //Standard NES controller shift registers ($4016/$4017). Bit order shifted out
    //LSB-first: A, B, Select, Start, Up, Down, Left, Right.
    private int controller1 = 0; //live button bitmask, set by the UI
    private int controller2 = 0;
    private int controller1Shift = 0;
    private int controller2Shift = 0;
    private boolean controllerStrobe = false;

    //Set by any $4016 write that strobes the controllers (see cpuWrite); consumed
    //once per frame by the TAS Maker via consumeStrobedThisFrame() to tell lag
    //frames (never strobed) from normal ones.
    private boolean strobedThisFrame = false;
    public boolean consumeStrobedThisFrame() {
        boolean result = strobedThisFrame;
        strobedThisFrame = false;
        return result;
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

    //Full power-on reset (CPU/PPU/APU/cartridge CHR RAM, plus this Bus's own
    //controller shift-register state) rather than the soft-reset reset() above.
    //Used only by the TAS Maker's replay-from-power-on seeking, where every replay
    //of the same recorded input log must land on exactly the same state.
    public void hardReset() {
        if (cpu != null) cpu.reset();
        if (ppu != null) ppu.hardReset();
        if (apu != null) apu.reset();
        if (cartridge != null) cartridge.hardReset();
        controller1 = 0;
        controller2 = 0;
        controller1Shift = 0;
        controller2Shift = 0;
        strobedThisFrame = false;
        controllerStrobe = false;
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
        //3. Controller Port 1 ($4016): shift out one button bit per read, LSB first.
        //   Only bit 0 is driven by the controller; the upper 3 bits are open bus.
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
        //4. Controller Port 2 ($4017)
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
        //5. APU & Direct I/O Registers ($4000 - $4015)
        else if (address >= 0x4000 && address <= 0x4015) {
            return apu.cpuRead(address);
        }
        //6. Cartridge PRG ROM ($8000 - $FFFF). NROM has nothing mapped below $8000
        //   (no PRG RAM), so $4018-$7FFF all fall through to open bus below.
        else if (address >= 0x8000 && address <= 0xFFFF) {
            if (cartridge != null) return cartridge.cpuRead(address);
        }
        //Anything else (e.g. $4018-$7FFF, or cartridge space with no mapping) is open
        //bus: the floating CPU data bus, not a fixed value.
        return (byte) cpuOpenBus();
    }

    //Value the CPU's internal data bus is currently holding - what an "open bus" read
    //returns, since nothing actually drives the bus for that address.
    private int cpuOpenBus() {
        return cpu != null ? cpu.dataBus : 0;
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

        //3. OAM DMA ($4014): copies one 256-byte page into PPU OAM, stalling the CPU
        else if (address == 0x4014) {
            oamDma(data & 0xFF);
        }
        //4. Controller Strobe ($4016): while high, both shift registers continuously
        //   reload from the live button state; the falling edge latches them for shifting.
        else if (address == 0x4016) {
            //Any write with bit 0 set strobes the controllers (matches AccuracyCoin's
            //"Controller Strobing" test 2) - recorded here regardless of the previous
            //strobe state so the TAS Maker can tell whether a frame ever read input at
            //all, i.e. whether it was a "lag frame" the game didn't act on.
            if ((data & 0x01) != 0) strobedThisFrame = true;
            controllerStrobe = (data & 0x01) != 0;
            if (controllerStrobe) {
                controller1Shift = controller1;
                controller2Shift = controller2;
            }
        }
        //5. APU & Direct I/O Registers ($4000 - $4015, $4017 frame counter)
        else if (address >= 0x4000 && address <= 0x4017) {
            apu.cpuWrite(address, data);
        }
        //6. Cartridge Space
        else if (address >= 0x4020 && address <= 0xFFFF) {
            if (cartridge != null) cartridge.cpuWrite(address, data);
        }
    }

    //Non-mutating read across the full $0000-$FFFF CPU address space, for the memory inspector.
    //Mirrors cpuRead's address decoding but never triggers register read side-effects.
    public byte debugRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x0000 && address <= 0x1FFF) {
            return ram[address & 0x07FF];
        } else if (address >= 0x2000 && address <= 0x3FFF) {
            return ppu.debugRead(0x2000 + (address & 0x0007));
        } else if (address == 0x4014) {
            return 0; //write-only OAMDMA latch
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

    //OAM DMA is now a genuine cycle-stepped state machine living on CPU (see
    //CPU.startOamDma()/runOamDmaCycle()) rather than a synchronous bulk copy - this
    //lets a DMC DMA request interleave with it mid-transfer, per the nesdev wiki's
    //"DMC DMA during OAM DMA" page (AccuracyCoin's "DMC DMA + OAM DMA", "Explicit
    //DMA Abort" and "Implicit DMA Abort" tests specifically exercise this).
    private void oamDma(int page) {
        if (cpu != null) cpu.startOamDma(page);
    }

    //TAS Maker greenzone checkpoint support: one composite snapshot of the whole
    //machine (CPU + PPU + APU + Bus's own RAM/controller-shift state + cartridge CHR
    //RAM), so the TAS Maker only has to hold one object per checkpointed frame. Only
    //ever captured at a CPU instruction boundary - see CPU.State's comment for why.
    public static final class EmulatorState {
        CPU.State cpu;
        PPU.State ppu;
        APU.State apu;
        byte[] ram;
        byte[] chr;
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
        s.chr = cartridge != null ? cartridge.snapshotChr() : null;
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
        if (cartridge != null && s.chr != null) cartridge.restoreChr(s.chr);
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
}