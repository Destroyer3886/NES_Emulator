
public class CPU {
    //6502 Registers
    public int a = 0x00;    //Accumulator (8-bit)
    public int x = 0x00;    //X Register (8-bit)
    public int y = 0x00;    //Y Register (8-bit)
    public int sp = 0xFD;   //Stack Pointer (8-bit)
    public int pc = 0x0000; //Program Counter (16-bit)
    public int status = 0x24; //Status Register (8-bit)

    //Status Register Flags
    public static final int C = (1 << 0); //Carry
    public static final int Z = (1 << 1); //Zero
    public static final int I = (1 << 2); //Interrupt
    public static final int D = (1 << 3); //Decimal Mode (unused)
    public static final int B = (1 << 4); //Break
    public static final int U = (1 << 5); //Unused
    public static final int V = (1 << 6); //Overflow
    public static final int N = (1 << 7); //Negative

    private Bus bus;
    private int cycles = 0;
    public long totalCycles = 0;

    private int addrAbs = 0x0000;
    private int addrRel = 0x0000;
    private int fetched = 0x00;

    //Helper Interfaces for Execution Table
    @FunctionalInterface interface Instruction { void run(); }
    @FunctionalInterface interface AddrMode { int run(); }

    public static class Opcode {
        String name;
        Instruction operate;
        AddrMode addrmode;
        int cycles;
        public boolean isOfficial;

        public Opcode(String name, Instruction operate, AddrMode addrmode, int cycles, boolean isOfficial) {
            this.name = name;
            this.operate = operate;
            this.addrmode = addrmode;
            this.cycles = cycles;
            this.isOfficial = isOfficial;
        }
    }

    // Assign addressing modes to fixed references so they can be compared
    private final AddrMode modeIMP = this::IMP;
    private final AddrMode modeIMM = this::IMM;
    private final AddrMode modeZP0 = this::ZP0;
    private final AddrMode modeZPX = this::ZPX;
    private final AddrMode modeZPY = this::ZPY;
    private final AddrMode modeREL = this::REL;
    private final AddrMode modeABS = this::ABS;
    private final AddrMode modeABX = this::ABX;
    private final AddrMode modeABY = this::ABY;
    private final AddrMode modeIND = this::IND;
    private final AddrMode modeIZX = this::IZX;
    private final AddrMode modeIZY = this::IZY;

    public final Opcode[] lookupTable = new Opcode[256];

    public CPU() {
        buildOpcodeTable();
    }

    public void connectBus(Bus bus) {
        this.bus = bus;
    }

    //Bus helpers
    public byte read(int addr) { return bus.cpuRead(addr); }
    public void write(int addr, byte data) { bus.cpuWrite(addr, data); }

    //Flag Setters
    public void setFlag(int flag, boolean v) {
        if (v) status |= flag;
        else status &= ~flag;
    }

    public boolean getFlag(int flag) {
        return (status & flag) != 0;
    }

    public void reset() {
        int low = read(0xFFFC) & 0xFF;
        int high = read(0xFFFD) & 0xFF;
        pc = (high << 8) | low;

        a = 0; x = 0; y = 0; sp = 0xFD;
        status = 0x00 | U | I;

        addrAbs = 0; addrRel = 0; fetched = 0;
        cycles = 8;
    }

    public void nmi() {
        write(0x0100 + sp, (byte) ((pc >> 8) & 0xFF));
        sp = (sp - 1) & 0xFF;
        write(0x0100 + sp, (byte) (pc & 0xFF));
        sp = (sp - 1) & 0xFF;

        setFlag(B, false); setFlag(U, true); setFlag(I, true);
        write(0x0100 + sp, (byte) status);
        sp = (sp - 1) & 0xFF;

        int low = read(0xFFFA) & 0xFF;
        int high = read(0xFFFB) & 0xFF;
        pc = (high << 8) | low;
        cycles = 7;
    }

    public void step(StringBuilder traceBuffer, PPU ppu, boolean tracingEnabled) {
        if (cycles == 0) {
            if (tracingEnabled && traceBuffer != null) {
                traceBuffer.append(getTraceLine(ppu)).append("\n");
            }

            int opcode = read(pc) & 0xFF;
            pc++;

            Opcode op = lookupTable[opcode];
            cycles = op.cycles;

            int additionalCycle = op.addrmode.run();
            op.operate.run();

            cycles += additionalCycle;
        }
        cycles--;
        totalCycles++;
    }

    private int fetch() {
        if (lookupTable[read(pc - 1) & 0xFF].addrmode != modeIMP) {
            fetched = read(addrAbs) & 0xFF;
        }
        return fetched;
    }

    //Addressing Modes
    private int IMP() { fetched = a; return 0; }
    private int IMM() { addrAbs = pc++; return 0; }
    private int ZP0() { addrAbs = read(pc++) & 0xFF; return 0; }
    private int ZPX() { addrAbs = (read(pc++) + x) & 0xFF; return 0; }
    private int ZPY() { addrAbs = (read(pc++) + y) & 0xFF; return 0; }
    private int REL() {
        addrRel = read(pc++);
        if ((addrRel & 0x80) != 0) addrRel |= 0xFF00; //Sign extension
        return 0;
    }
    private int ABS() {
        int low = read(pc++) & 0xFF;
        int high = read(pc++) & 0xFF;
        addrAbs = (high << 8) | low;
        return 0;
    }
    private int ABX() {
        int low = read(pc++) & 0xFF;
        int high = read(pc++) & 0xFF;
        addrAbs = ((high << 8) | low) + x;
        return ((addrAbs & 0xFF00) != (high << 8)) ? 1 : 0;
    }
    private int ABY() {
        int low = read(pc++) & 0xFF;
        int high = read(pc++) & 0xFF;
        addrAbs = ((high << 8) | low) + y;
        return ((addrAbs & 0xFF00) != (high << 8)) ? 1 : 0;
    }
    private int IND() {
        int ptrLow = read(pc++) & 0xFF;
        int ptrHigh = read(pc++) & 0xFF;
        int ptr = (ptrHigh << 8) | ptrLow;
        //Page boundary wrap simulation
        if (ptrLow == 0x00FF) {
            addrAbs = ((read(ptr & 0xFF00) & 0xFF) << 8) | (read(ptr) & 0xFF);
        } else {
            addrAbs = ((read(ptr + 1) & 0xFF) << 8) | (read(ptr) & 0xFF);
        }
        return 0;
    }
    private int IZX() {
        int t = read(pc++) & 0xFF;
        int low = read((t + x) & 0xFF) & 0xFF;
        int high = read((t + x + 1) & 0xFF) & 0xFF;
        addrAbs = (high << 8) | low;
        return 0;
    }
    private int IZY() {
        int t = read(pc++) & 0xFF;
        int low = read(t & 0xFF) & 0xFF;
        int high = read((t + 1) & 0xFF) & 0xFF;
        addrAbs = ((high << 8) | low) + y;
        return ((addrAbs & 0xFF00) != (high << 8)) ? 1 : 0;
    }

    //Instructions
    private void ADC() { fetch(); int temp = a + fetched + (getFlag(C) ? 1 : 0); setFlag(C, temp > 255); setFlag(Z, (temp & 0xFF) == 0); setFlag(V, (~(a ^ fetched) & (a ^ temp) & 0x0080) != 0); setFlag(N, (temp & 0x80) != 0); a = temp & 0xFF; }
    private void AND() { fetch(); a &= fetched; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void ASL() { fetch(); int temp = fetched << 1; setFlag(C, (temp & 0xFF00) > 0); setFlag(Z, (temp & 0x00FF) == 0); setFlag(N, (temp & 0x80) != 0); if (lookupTable[read(pc - 1) & 0xFF].addrmode == modeIMP) a = temp & 0xFF; else write(addrAbs, (byte) (temp & 0xFF)); }
    private void BCC() { if (!getFlag(C)) { cycles++; addrAbs = pc + addrRel; if ((pc & 0xFF00) != (addrAbs & 0xFF00)) cycles++; pc = addrAbs; } }
    private void BCS() { if (getFlag(C))  { cycles++; addrAbs = pc + addrRel; if ((pc & 0xFF00) != (addrAbs & 0xFF00)) cycles++; pc = addrAbs; } }
    private void BEQ() { if (getFlag(Z))  { cycles++; addrAbs = pc + addrRel; if ((pc & 0xFF00) != (addrAbs & 0xFF00)) cycles++; pc = addrAbs; } }
    private void BIT() { fetch(); setFlag(Z, (a & fetched) == 0); setFlag(N, (fetched & (1 << 7)) != 0); setFlag(V, (fetched & (1 << 6)) != 0); }
    private void BMI() { if (getFlag(N))  { cycles++; addrAbs = pc + addrRel; if ((pc & 0xFF00) != (addrAbs & 0xFF00)) cycles++; pc = addrAbs; } }
    private void BNE() { if (!getFlag(Z)) { cycles++; addrAbs = pc + addrRel; if ((pc & 0xFF00) != (addrAbs & 0xFF00)) cycles++; pc = addrAbs; } }
    private void BPL() { if (!getFlag(N)) { cycles++; addrAbs = pc + addrRel; if ((pc & 0xFF00) != (addrAbs & 0xFF00)) cycles++; pc = addrAbs; } }
    private void BRK() { pc++; setFlag(I, true); write(0x0100 + sp--, (byte) ((pc >> 8) & 0xFF)); write(0x0100 + sp--, (byte) (pc & 0xFF)); setFlag(B, true); write(0x0100 + sp--, (byte) status); setFlag(B, false); pc = (read(0xFFFF) << 8) | read(0xFFFE); }
    private void BVC() { if (!getFlag(V)) { cycles++; addrAbs = pc + addrRel; if ((pc & 0xFF00) != (addrAbs & 0xFF00)) cycles++; pc = addrAbs; } }
    private void BVS() { if (getFlag(V))  { cycles++; addrAbs = pc + addrRel; if ((pc & 0xFF00) != (addrAbs & 0xFF00)) cycles++; pc = addrAbs; } }
    private void CLC() { setFlag(C, false); }
    private void CLD() { setFlag(D, false); }
    private void CLI() { setFlag(I, false); }
    private void CLV() { setFlag(V, false); }
    private void CMP() { fetch(); int temp = (a - fetched) & 0xFF; setFlag(C, a >= fetched); setFlag(Z, temp == 0); setFlag(N, (temp & 0x80) != 0); }
    private void CPX() { fetch(); int temp = (x - fetched) & 0xFF; setFlag(C, x >= fetched); setFlag(Z, temp == 0); setFlag(N, (temp & 0x80) != 0); }
    private void CPY() { fetch(); int temp = (y - fetched) & 0xFF; setFlag(C, y >= fetched); setFlag(Z, temp == 0); setFlag(N, (temp & 0x80) != 0); }
    private void DEC() { fetch(); int temp = (fetched - 1) & 0xFF; write(addrAbs, (byte) temp); setFlag(Z, temp == 0); setFlag(N, (temp & 0x80) != 0); }
    private void DEX() { x = (x - 1) & 0xFF; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }
    private void DEY() { y = (y - 1) & 0xFF; setFlag(Z, y == 0); setFlag(N, (y & 0x80) != 0); }
    private void EOR() { fetch(); a ^= fetched; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void INC() { fetch(); int temp = (fetched + 1) & 0xFF; write(addrAbs, (byte) temp); setFlag(Z, temp == 0); setFlag(N, (temp & 0x80) != 0); }
    private void INX() { x = (x + 1) & 0xFF; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }
    private void INY() { y = (y + 1) & 0xFF; setFlag(Z, y == 0); setFlag(N, (y & 0x80) != 0); }
    private void JMP() { pc = addrAbs; }
    private void JSR() { pc--; write(0x0100 + sp--, (byte) ((pc >> 8) & 0xFF)); write(0x0100 + sp--, (byte) (pc & 0xFF)); pc = addrAbs; }
    private void LDA() { fetch(); a = fetched; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void LDX() { fetch(); x = fetched; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }
    private void LDY() { fetch(); y = fetched; setFlag(Z, y == 0); setFlag(N, (y & 0x80) != 0); }
    private void LSR() { fetch(); setFlag(C, (fetched & 0x01) != 0); int temp = (fetched >> 1) & 0xFF; setFlag(Z, temp == 0); setFlag(N, false); if (lookupTable[read(pc - 1) & 0xFF].addrmode == modeIMP) a = temp; else write(addrAbs, (byte) temp); }
    private void NOP() { }
    private void ORA() { fetch(); a |= fetched; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void PHA() { write(0x0100 + sp--, (byte) a); }
    private void PHP() { write(0x0100 + sp--, (byte) (status | B | U)); setFlag(B, false); setFlag(U, false); }
    private void PLA() { sp++; a = read(0x0100 + sp) & 0xFF; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void PLP() { sp++; status = read(0x0100 + sp) & 0xFF; setFlag(U, true); }
    private void ROL() { fetch(); int temp = (fetched << 1) | (getFlag(C) ? 1 : 0); setFlag(C, (temp & 0x0100) != 0); temp &= 0xFF; setFlag(Z, temp == 0); setFlag(N, (temp & 0x80) != 0); if (lookupTable[read(pc - 1) & 0xFF].addrmode == modeIMP) a = temp; else write(addrAbs, (byte) temp); }
    private void ROR() { fetch(); int temp = (getFlag(C) ? 0x80 : 0x00) | (fetched >> 1); setFlag(C, (fetched & 0x01) != 0); setFlag(Z, (temp & 0xFF) == 0); setFlag(N, (temp & 0x80) != 0); if (lookupTable[read(pc - 1) & 0xFF].addrmode == modeIMP) a = temp; else write(addrAbs, (byte) temp); }
    private void RTI() { sp++; status = read(0x0100 + sp) & 0xFF; status &= ~B; status &= ~U; sp++; pc = read(0x0100 + sp) & 0xFF; sp++; pc |= (read(0x0100 + sp) & 0xFF) << 8; }
    private void RTS() { sp++; pc = read(0x0100 + sp) & 0xFF; sp++; pc |= (read(0x0100 + sp) & 0xFF) << 8; pc++; }
    private void SBC() { fetch(); int val = fetched ^ 0x00FF; int temp = a + val + (getFlag(C) ? 1 : 0); setFlag(C, (temp & 0x0100) != 0); setFlag(Z, (temp & 0xFF) == 0); setFlag(V, ((temp ^ a) & (temp ^ val) & 0x0080) != 0); setFlag(N, (temp & 0x80) != 0); a = temp & 0xFF; }
    private void SEC() { setFlag(C, true); }
    private void SED() { setFlag(D, true); }
    private void SEI() { setFlag(I, true); }
    private void STA() { write(addrAbs, (byte) a); }
    private void STX() { write(addrAbs, (byte) x); }
    private void STY() { write(addrAbs, (byte) y); }
    private void TAX() { x = a; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }
    private void TAY() { y = a; setFlag(Z, y == 0); setFlag(N, (y & 0x80) != 0); }
    private void TSX() { x = sp; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }
    private void TXA() { a = x; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void TXS() { sp = x; }
    private void TYA() { a = y; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }

    //Unofficial Opcodes
    private void AHX() { int high = ((addrAbs - y) >> 8) & 0xFF; write(addrAbs, (byte) (a & x & (high + 1))); }
    private void ALR() { fetch(); a &= fetched; setFlag(C, (a & 0x01) != 0); a = (a >> 1) & 0xFF; setFlag(Z, a == 0); setFlag(N, false); }
    private void ANC() { fetch(); a &= fetched; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); setFlag(C, getFlag(N)); }
    private void ARR() { fetch(); a &= fetched; a = (a >> 1) | (getFlag(C) ? 0x80 : 0); setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); setFlag(C, (a & 0x40) != 0); setFlag(V, ((a & 0x40) ^ ((a & 0x20) << 1)) != 0); }
    private void AXS() { fetch(); int temp = (a & x) - fetched; setFlag(C, (a & x) >= fetched); x = temp & 0xFF; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }
    private void DCP() { fetch(); int temp = (fetched - 1) & 0xFF; write(addrAbs, (byte) temp); int sub = (a - temp) & 0xFF; setFlag(C, a >= temp); setFlag(Z, sub == 0); setFlag(N, (sub & 0x80) != 0); }
    private void ISB() { fetch(); int temp = (fetched + 1) & 0xFF; write(addrAbs, (byte) temp); fetched = temp; SBC(); }
    private void KIL() { }
    private void LAS() { fetch(); int val = fetched & sp; a = val; x = val; sp = val; setFlag(Z, val == 0); setFlag(N, (val & 0x80) != 0); }
    private void LAX() { fetch(); a = fetched; x = fetched; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void RLA() { fetch(); int temp = (fetched << 1) | (getFlag(C) ? 1 : 0); setFlag(C, (temp & 0x0100) != 0); temp &= 0xFF; write(addrAbs, (byte) temp); a &= temp; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void RRA() { fetch(); int temp = (getFlag(C) ? 0x80 : 0x00) | (fetched >> 1); setFlag(C, (fetched & 0x01) != 0); write(addrAbs, (byte) temp); fetched = temp; ADC(); }
    private void SAX() { write(addrAbs, (byte) (a & x)); }
    private void SHX() { int high = ((addrAbs - y) >> 8) & 0xFF; write(addrAbs, (byte) (x & (high + 1))); }
    private void SHY() { int high = ((addrAbs - x) >> 8) & 0xFF; write(addrAbs, (byte) (y & (high + 1))); }
    private void SLO() { fetch(); setFlag(C, (fetched & 0x80) != 0); int temp = (fetched << 1) & 0xFF; write(addrAbs, (byte) temp); a |= temp; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void SRE() { fetch(); setFlag(C, (fetched & 0x01) != 0); int temp = (fetched >> 1) & 0xFF; write(addrAbs, (byte) temp); a ^= temp; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void TAS() { sp = a & x; int high = ((addrAbs - y) >> 8) & 0xFF; write(addrAbs, (byte) (sp & (high + 1))); }
    private void XAA() { fetch(); a = x & fetched; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }


    //Creates a trace debug log line
    public String getTraceLine(PPU ppu) {
        int opcode = read(pc) & 0xFF;
        Opcode op = lookupTable[opcode];

        int b1 = read(pc + 1) & 0xFF;
        int b2 = read(pc + 2) & 0xFF;

        String hexBytes = String.format("%02X", opcode);
        String operand = "";

        if (op.addrmode == modeIMM) { hexBytes += String.format(" %02X", b1); operand = String.format("#$%02X", b1); }
        else if (op.addrmode == modeZP0) { hexBytes += String.format(" %02X", b1); operand = String.format("$%02X", b1); }
        else if (op.addrmode == modeZPX) { hexBytes += String.format(" %02X", b1); operand = String.format("$%02X, X", b1); }
        else if (op.addrmode == modeZPY) { hexBytes += String.format(" %02X", b1); operand = String.format("$%02X, Y", b1); }
        else if (op.addrmode == modeABS) { hexBytes += String.format(" %02X %02X", b1, b2); operand = String.format("$%02X%02X", b2, b1); }
        else if (op.addrmode == modeABX) { hexBytes += String.format(" %02X %02X", b1, b2); operand = String.format("$%02X%02X, X", b2, b1); }
        else if (op.addrmode == modeABY) { hexBytes += String.format(" %02X %02X", b1, b2); operand = String.format("$%02X%02X, Y", b2, b1); }
        else if (op.addrmode == modeREL) { hexBytes += String.format(" %02X", b1); operand = String.format("$%04X", (pc + 2 + (byte) b1) & 0xFFFF); }

        String disasm = String.format("%s %s", op.name, operand).trim();

        //Flag string: n v u b d i z c (Uppercase = Set, Lowercase = Clear)
        String flagStr = "" +
                (getFlag(N) ? "N" : "n") +
                (getFlag(V) ? "V" : "v") +
                (getFlag(U) ? "U" : "u") +
                (getFlag(B) ? "B" : "b") +
                (getFlag(D) ? "D" : "d") +
                (getFlag(I) ? "I" : "i") +
                (getFlag(Z) ? "Z" : "z") +
                (getFlag(C) ? "C" : "c");

        return String.format("%04X:    %-10s  %-15s  A = $%02X    X = $%02X    Y = $%02X    SP = $%02X    P = $%02X    %s    Cycle = %d    PPU Cycle = %d",
                pc, hexBytes, disasm, a, x, y, sp, status, flagStr, totalCycles, ppu.cycle);
    }

    //Full 256 Opcode Construction Table
    private void buildOpcodeTable() {
        // 0x00 - 0x0F
        lookupTable[0x00] = new Opcode("BRK", this::BRK, modeIMM, 7, true);
        lookupTable[0x01] = new Opcode("ORA", this::ORA, modeIZX, 6, true);
        lookupTable[0x02] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0x03] = new Opcode("SLO", this::SLO, modeIZX, 8, false);
        lookupTable[0x04] = new Opcode("NOP", this::NOP, modeZP0, 3, false);
        lookupTable[0x05] = new Opcode("ORA", this::ORA, modeZP0, 3, true);
        lookupTable[0x06] = new Opcode("ASL", this::ASL, modeZP0, 5, true);
        lookupTable[0x07] = new Opcode("SLO", this::SLO, modeZP0, 5, false);
        lookupTable[0x08] = new Opcode("PHP", this::PHP, modeIMP, 3, true);
        lookupTable[0x09] = new Opcode("ORA", this::ORA, modeIMM, 2, true);
        lookupTable[0x0A] = new Opcode("ASL", this::ASL, modeIMP, 2, true);
        lookupTable[0x0B] = new Opcode("ANC", this::ANC, modeIMM, 2, false);
        lookupTable[0x0C] = new Opcode("NOP", this::NOP, modeABS, 4, false);
        lookupTable[0x0D] = new Opcode("ORA", this::ORA, modeABS, 4, true);
        lookupTable[0x0E] = new Opcode("ASL", this::ASL, modeABS, 6, true);
        lookupTable[0x0F] = new Opcode("SLO", this::SLO, modeABS, 6, false);

        // 0x10 - 0x1F
        lookupTable[0x10] = new Opcode("BPL", this::BPL, modeREL, 2, true);
        lookupTable[0x11] = new Opcode("ORA", this::ORA, modeIZY, 5, true);
        lookupTable[0x12] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0x13] = new Opcode("SLO", this::SLO, modeIZY, 8, false);
        lookupTable[0x14] = new Opcode("NOP", this::NOP, modeZPX, 4, false);
        lookupTable[0x15] = new Opcode("ORA", this::ORA, modeZPX, 4, true);
        lookupTable[0x16] = new Opcode("ASL", this::ASL, modeZPX, 6, true);
        lookupTable[0x17] = new Opcode("SLO", this::SLO, modeZPX, 6, false);
        lookupTable[0x18] = new Opcode("CLC", this::CLC, modeIMP, 2, true);
        lookupTable[0x19] = new Opcode("ORA", this::ORA, modeABY, 4, true);
        lookupTable[0x1A] = new Opcode("NOP", this::NOP, modeIMP, 2, false);
        lookupTable[0x1B] = new Opcode("SLO", this::SLO, modeABY, 7, false);
        lookupTable[0x1C] = new Opcode("NOP", this::NOP, modeABX, 4, false);
        lookupTable[0x1D] = new Opcode("ORA", this::ORA, modeABX, 4, true);
        lookupTable[0x1E] = new Opcode("ASL", this::ASL, modeABX, 7, true);
        lookupTable[0x1F] = new Opcode("SLO", this::SLO, modeABX, 7, false);

        // 0x20 - 0x2F
        lookupTable[0x20] = new Opcode("JSR", this::JSR, modeABS, 6, true);
        lookupTable[0x21] = new Opcode("AND", this::AND, modeIZX, 6, true);
        lookupTable[0x22] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0x23] = new Opcode("RLA", this::RLA, modeIZX, 8, false);
        lookupTable[0x24] = new Opcode("BIT", this::BIT, modeZP0, 3, true);
        lookupTable[0x25] = new Opcode("AND", this::AND, modeZP0, 3, true);
        lookupTable[0x26] = new Opcode("ROL", this::ROL, modeZP0, 5, true);
        lookupTable[0x27] = new Opcode("RLA", this::RLA, modeZP0, 5, false);
        lookupTable[0x28] = new Opcode("PLP", this::PLP, modeIMP, 4, true);
        lookupTable[0x29] = new Opcode("AND", this::AND, modeIMM, 2, true);
        lookupTable[0x2A] = new Opcode("ROL", this::ROL, modeIMP, 2, true);
        lookupTable[0x2B] = new Opcode("ANC", this::ANC, modeIMM, 2, false);
        lookupTable[0x2C] = new Opcode("BIT", this::BIT, modeABS, 4, true);
        lookupTable[0x2D] = new Opcode("AND", this::AND, modeABS, 4, true);
        lookupTable[0x2E] = new Opcode("ROL", this::ROL, modeABS, 6, true);
        lookupTable[0x2F] = new Opcode("RLA", this::RLA, modeABS, 6, false);

        // 0x30 - 0x3F
        lookupTable[0x30] = new Opcode("BMI", this::BMI, modeREL, 2, true);
        lookupTable[0x31] = new Opcode("AND", this::AND, modeIZY, 5, true);
        lookupTable[0x32] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0x33] = new Opcode("RLA", this::RLA, modeIZY, 8, false);
        lookupTable[0x34] = new Opcode("NOP", this::NOP, modeZPX, 4, false);
        lookupTable[0x35] = new Opcode("AND", this::AND, modeZPX, 4, true);
        lookupTable[0x36] = new Opcode("ROL", this::ROL, modeZPX, 6, true);
        lookupTable[0x37] = new Opcode("RLA", this::RLA, modeZPX, 6, false);
        lookupTable[0x38] = new Opcode("SEC", this::SEC, modeIMP, 2, true);
        lookupTable[0x39] = new Opcode("AND", this::AND, modeABY, 4, true);
        lookupTable[0x3A] = new Opcode("NOP", this::NOP, modeIMP, 2, false);
        lookupTable[0x3B] = new Opcode("RLA", this::RLA, modeABY, 7, false);
        lookupTable[0x3C] = new Opcode("NOP", this::NOP, modeABX, 4, false);
        lookupTable[0x3D] = new Opcode("AND", this::AND, modeABX, 4, true);
        lookupTable[0x3E] = new Opcode("ROL", this::ROL, modeABX, 7, true);
        lookupTable[0x3F] = new Opcode("RLA", this::RLA, modeABX, 7, false);

        // 0x40 - 0x4F
        lookupTable[0x40] = new Opcode("RTI", this::RTI, modeIMP, 6, true);
        lookupTable[0x41] = new Opcode("EOR", this::EOR, modeIZX, 6, true);
        lookupTable[0x42] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0x43] = new Opcode("SRE", this::SRE, modeIZX, 8, false);
        lookupTable[0x44] = new Opcode("NOP", this::NOP, modeZP0, 3, false);
        lookupTable[0x45] = new Opcode("EOR", this::EOR, modeZP0, 3, true);
        lookupTable[0x46] = new Opcode("LSR", this::LSR, modeZP0, 5, true);
        lookupTable[0x47] = new Opcode("SRE", this::SRE, modeZP0, 5, false);
        lookupTable[0x48] = new Opcode("PHA", this::PHA, modeIMP, 3, true);
        lookupTable[0x49] = new Opcode("EOR", this::EOR, modeIMM, 2, true);
        lookupTable[0x4A] = new Opcode("LSR", this::LSR, modeIMP, 2, true);
        lookupTable[0x4B] = new Opcode("ALR", this::ALR, modeIMM, 2, false);
        lookupTable[0x4C] = new Opcode("JMP", this::JMP, modeABS, 3, true);
        lookupTable[0x4D] = new Opcode("EOR", this::EOR, modeABS, 4, true);
        lookupTable[0x4E] = new Opcode("LSR", this::LSR, modeABS, 6, true);
        lookupTable[0x4F] = new Opcode("SRE", this::SRE, modeABS, 6, false);

        // 0x50 - 0x5F
        lookupTable[0x50] = new Opcode("BVC", this::BVC, modeREL, 2, true);
        lookupTable[0x51] = new Opcode("EOR", this::EOR, modeIZY, 5, true);
        lookupTable[0x52] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0x53] = new Opcode("SRE", this::SRE, modeIZY, 8, false);
        lookupTable[0x54] = new Opcode("NOP", this::NOP, modeZPX, 4, false);
        lookupTable[0x55] = new Opcode("EOR", this::EOR, modeZPX, 4, true);
        lookupTable[0x56] = new Opcode("LSR", this::LSR, modeZPX, 6, true);
        lookupTable[0x57] = new Opcode("SRE", this::SRE, modeZPX, 6, false);
        lookupTable[0x58] = new Opcode("CLI", this::CLI, modeIMP, 2, true);
        lookupTable[0x59] = new Opcode("EOR", this::EOR, modeABY, 4, true);
        lookupTable[0x5A] = new Opcode("NOP", this::NOP, modeIMP, 2, false);
        lookupTable[0x5B] = new Opcode("SRE", this::SRE, modeABY, 7, false);
        lookupTable[0x5C] = new Opcode("NOP", this::NOP, modeABX, 4, false);
        lookupTable[0x5D] = new Opcode("EOR", this::EOR, modeABX, 4, true);
        lookupTable[0x5E] = new Opcode("LSR", this::LSR, modeABX, 7, true);
        lookupTable[0x5F] = new Opcode("SRE", this::SRE, modeABX, 7, false);

        // 0x60 - 0x6F
        lookupTable[0x60] = new Opcode("RTS", this::RTS, modeIMP, 6, true);
        lookupTable[0x61] = new Opcode("ADC", this::ADC, modeIZX, 6, true);
        lookupTable[0x62] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0x63] = new Opcode("RRA", this::RRA, modeIZX, 8, false);
        lookupTable[0x64] = new Opcode("NOP", this::NOP, modeZP0, 3, false);
        lookupTable[0x65] = new Opcode("ADC", this::ADC, modeZP0, 3, true);
        lookupTable[0x66] = new Opcode("ROR", this::ROR, modeZP0, 5, true);
        lookupTable[0x67] = new Opcode("RRA", this::RRA, modeZP0, 5, false);
        lookupTable[0x68] = new Opcode("PLA", this::PLA, modeIMP, 4, true);
        lookupTable[0x69] = new Opcode("ADC", this::ADC, modeIMM, 2, true);
        lookupTable[0x6A] = new Opcode("ROR", this::ROR, modeIMP, 2, true);
        lookupTable[0x6B] = new Opcode("ARR", this::ARR, modeIMM, 2, false);
        lookupTable[0x6C] = new Opcode("JMP", this::JMP, modeIND, 5, true);
        lookupTable[0x6D] = new Opcode("ADC", this::ADC, modeABS, 4, true);
        lookupTable[0x6E] = new Opcode("ROR", this::ROR, modeABS, 6, true);
        lookupTable[0x6F] = new Opcode("RRA", this::RRA, modeABS, 6, false);

        // 0x70 - 0x7F
        lookupTable[0x70] = new Opcode("BVS", this::BVS, modeREL, 2, true);
        lookupTable[0x71] = new Opcode("ADC", this::ADC, modeIZY, 5, true);
        lookupTable[0x72] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0x73] = new Opcode("RRA", this::RRA, modeIZY, 8, false);
        lookupTable[0x74] = new Opcode("NOP", this::NOP, modeZPX, 4, false);
        lookupTable[0x75] = new Opcode("ADC", this::ADC, modeZPX, 4, true);
        lookupTable[0x76] = new Opcode("ROR", this::ROR, modeZPX, 6, true);
        lookupTable[0x77] = new Opcode("RRA", this::RRA, modeZPX, 6, false);
        lookupTable[0x78] = new Opcode("SEI", this::SEI, modeIMP, 2, true);
        lookupTable[0x79] = new Opcode("ADC", this::ADC, modeABY, 4, true);
        lookupTable[0x7A] = new Opcode("NOP", this::NOP, modeIMP, 2, false);
        lookupTable[0x7B] = new Opcode("RRA", this::RRA, modeABY, 7, false);
        lookupTable[0x7C] = new Opcode("NOP", this::NOP, modeABX, 4, false);
        lookupTable[0x7D] = new Opcode("ADC", this::ADC, modeABX, 4, true);
        lookupTable[0x7E] = new Opcode("ROR", this::ROR, modeABX, 7, true);
        lookupTable[0x7F] = new Opcode("RRA", this::RRA, modeABX, 7, false);

        // 0x80 - 0x8F
        lookupTable[0x80] = new Opcode("NOP", this::NOP, modeIMM, 2, false);
        lookupTable[0x81] = new Opcode("STA", this::STA, modeIZX, 6, true);
        lookupTable[0x82] = new Opcode("NOP", this::NOP, modeIMM, 2, false);
        lookupTable[0x83] = new Opcode("SAX", this::SAX, modeIZX, 6, false);
        lookupTable[0x84] = new Opcode("STY", this::STY, modeZP0, 3, true);
        lookupTable[0x85] = new Opcode("STA", this::STA, modeZP0, 3, true);
        lookupTable[0x86] = new Opcode("STX", this::STX, modeZP0, 3, true);
        lookupTable[0x87] = new Opcode("SAX", this::SAX, modeZP0, 3, false);
        lookupTable[0x88] = new Opcode("DEY", this::DEY, modeIMP, 2, true);
        lookupTable[0x89] = new Opcode("NOP", this::NOP, modeIMM, 2, false);
        lookupTable[0x8A] = new Opcode("TXA", this::TXA, modeIMP, 2, true);
        lookupTable[0x8B] = new Opcode("XAA", this::XAA, modeIMM, 2, false);
        lookupTable[0x8C] = new Opcode("STY", this::STY, modeABS, 4, true);
        lookupTable[0x8D] = new Opcode("STA", this::STA, modeABS, 4, true);
        lookupTable[0x8E] = new Opcode("STX", this::STX, modeABS, 4, true);
        lookupTable[0x8F] = new Opcode("SAX", this::SAX, modeABS, 4, false);

        // 0x90 - 0x9F
        lookupTable[0x90] = new Opcode("BCC", this::BCC, modeREL, 2, true);
        lookupTable[0x91] = new Opcode("STA", this::STA, modeIZY, 6, true);
        lookupTable[0x92] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0x93] = new Opcode("AHX", this::AHX, modeIZY, 6, false);
        lookupTable[0x94] = new Opcode("STY", this::STY, modeZPX, 4, true);
        lookupTable[0x95] = new Opcode("STA", this::STA, modeZPX, 4, true);
        lookupTable[0x96] = new Opcode("STX", this::STX, modeZPY, 4, true);
        lookupTable[0x97] = new Opcode("SAX", this::SAX, modeZPY, 4, false);
        lookupTable[0x98] = new Opcode("TYA", this::TYA, modeIMP, 2, true);
        lookupTable[0x99] = new Opcode("STA", this::STA, modeABY, 5, true);
        lookupTable[0x9A] = new Opcode("TXS", this::TXS, modeIMP, 2, true);
        lookupTable[0x9B] = new Opcode("TAS", this::TAS, modeABY, 5, false);
        lookupTable[0x9C] = new Opcode("SHY", this::SHY, modeABX, 5, false);
        lookupTable[0x9D] = new Opcode("STA", this::STA, modeABX, 5, true);
        lookupTable[0x9E] = new Opcode("SHX", this::SHX, modeABY, 5, false);
        lookupTable[0x9F] = new Opcode("AHX", this::AHX, modeABY, 5, false);

        // 0xA0 - 0xAF
        lookupTable[0xA0] = new Opcode("LDY", this::LDY, modeIMM, 2, true);
        lookupTable[0xA1] = new Opcode("LDA", this::LDA, modeIZX, 6, true);
        lookupTable[0xA2] = new Opcode("LDX", this::LDX, modeIMM, 2, true);
        lookupTable[0xA3] = new Opcode("LAX", this::LAX, modeIZX, 6, false);
        lookupTable[0xA4] = new Opcode("LDY", this::LDY, modeZP0, 3, true);
        lookupTable[0xA5] = new Opcode("LDA", this::LDA, modeZP0, 3, true);
        lookupTable[0xA6] = new Opcode("LDX", this::LDX, modeZP0, 3, true);
        lookupTable[0xA7] = new Opcode("LAX", this::LAX, modeZP0, 3, false);
        lookupTable[0xA8] = new Opcode("TAY", this::TAY, modeIMP, 2, true);
        lookupTable[0xA9] = new Opcode("LDA", this::LDA, modeIMM, 2, true);
        lookupTable[0xAA] = new Opcode("TAX", this::TAX, modeIMP, 2, true);
        lookupTable[0xAB] = new Opcode("LAX", this::LAX, modeIMM, 2, false);
        lookupTable[0xAC] = new Opcode("LDY", this::LDY, modeABS, 4, true);
        lookupTable[0xAD] = new Opcode("LDA", this::LDA, modeABS, 4, true);
        lookupTable[0xAE] = new Opcode("LDX", this::LDX, modeABS, 4, true);
        lookupTable[0xAF] = new Opcode("LAX", this::LAX, modeABS, 4, false);

        // 0xB0 - 0xBF
        lookupTable[0xB0] = new Opcode("BCS", this::BCS, modeREL, 2, true);
        lookupTable[0xB1] = new Opcode("LDA", this::LDA, modeIZY, 5, true);
        lookupTable[0xB2] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0xB3] = new Opcode("LAX", this::LAX, modeIZY, 5, false);
        lookupTable[0xB4] = new Opcode("LDY", this::LDY, modeZPX, 4, true);
        lookupTable[0xB5] = new Opcode("LDA", this::LDA, modeZPX, 4, true);
        lookupTable[0xB6] = new Opcode("LDX", this::LDX, modeZPY, 4, true);
        lookupTable[0xB7] = new Opcode("LAX", this::LAX, modeZPY, 4, false);
        lookupTable[0xB8] = new Opcode("CLV", this::CLV, modeIMP, 2, true);
        lookupTable[0xB9] = new Opcode("LDA", this::LDA, modeABY, 4, true);
        lookupTable[0xBA] = new Opcode("TSX", this::TSX, modeIMP, 2, true);
        lookupTable[0xBB] = new Opcode("LAS", this::LAS, modeABY, 4, false);
        lookupTable[0xBC] = new Opcode("LDY", this::LDY, modeABX, 4, true);
        lookupTable[0xBD] = new Opcode("LDA", this::LDA, modeABX, 4, true);
        lookupTable[0xBE] = new Opcode("LDX", this::LDX, modeABY, 4, true);
        lookupTable[0xBF] = new Opcode("LAX", this::LAX, modeABY, 4, false);

        // 0xC0 - 0xCF
        lookupTable[0xC0] = new Opcode("CPY", this::CPY, modeIMM, 2, true);
        lookupTable[0xC1] = new Opcode("CMP", this::CMP, modeIZX, 6, true);
        lookupTable[0xC2] = new Opcode("NOP", this::NOP, modeIMM, 2, false);
        lookupTable[0xC3] = new Opcode("DCP", this::DCP, modeIZX, 8, false);
        lookupTable[0xC4] = new Opcode("CPY", this::CPY, modeZP0, 3, true);
        lookupTable[0xC5] = new Opcode("CMP", this::CMP, modeZP0, 3, true);
        lookupTable[0xC6] = new Opcode("DEC", this::DEC, modeZP0, 5, true);
        lookupTable[0xC7] = new Opcode("DCP", this::DCP, modeZP0, 5, false);
        lookupTable[0xC8] = new Opcode("INY", this::INY, modeIMP, 2, true);
        lookupTable[0xC9] = new Opcode("CMP", this::CMP, modeIMM, 2, true);
        lookupTable[0xCA] = new Opcode("DEX", this::DEX, modeIMP, 2, true);
        lookupTable[0xCB] = new Opcode("AXS", this::AXS, modeIMM, 2, false);
        lookupTable[0xCC] = new Opcode("CPY", this::CPY, modeABS, 4, true);
        lookupTable[0xCD] = new Opcode("CMP", this::CMP, modeABS, 4, true);
        lookupTable[0xCE] = new Opcode("DEC", this::DEC, modeABS, 6, true);
        lookupTable[0xCF] = new Opcode("DCP", this::DCP, modeABS, 6, false);

        // 0xD0 - 0xDF
        lookupTable[0xD0] = new Opcode("BNE", this::BNE, modeREL, 2, true);
        lookupTable[0xD1] = new Opcode("CMP", this::CMP, modeIZY, 5, true);
        lookupTable[0xD2] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0xD3] = new Opcode("DCP", this::DCP, modeIZY, 8, false);
        lookupTable[0xD4] = new Opcode("NOP", this::NOP, modeZPX, 4, false);
        lookupTable[0xD5] = new Opcode("CMP", this::CMP, modeZPX, 4, true);
        lookupTable[0xD6] = new Opcode("DEC", this::DEC, modeZPX, 6, true);
        lookupTable[0xD7] = new Opcode("DCP", this::DCP, modeZPX, 6, false);
        lookupTable[0xD8] = new Opcode("CLD", this::CLD, modeIMP, 2, true);
        lookupTable[0xD9] = new Opcode("CMP", this::CMP, modeABY, 4, true);
        lookupTable[0xDA] = new Opcode("NOP", this::NOP, modeIMP, 2, false);
        lookupTable[0xDB] = new Opcode("DCP", this::DCP, modeABY, 7, false);
        lookupTable[0xDC] = new Opcode("NOP", this::NOP, modeABX, 4, false);
        lookupTable[0xDD] = new Opcode("CMP", this::CMP, modeABX, 4, true);
        lookupTable[0xDE] = new Opcode("DEC", this::DEC, modeABX, 7, true);
        lookupTable[0xDF] = new Opcode("DCP", this::DCP, modeABX, 7, false);

        // 0xE0 - 0xEF
        lookupTable[0xE0] = new Opcode("CPX", this::CPX, modeIMM, 2, true);
        lookupTable[0xE1] = new Opcode("SBC", this::SBC, modeIZX, 6, true);
        lookupTable[0xE2] = new Opcode("NOP", this::NOP, modeIMM, 2, false);
        lookupTable[0xE3] = new Opcode("ISB", this::ISB, modeIZX, 8, false);
        lookupTable[0xE4] = new Opcode("CPX", this::CPX, modeZP0, 3, true);
        lookupTable[0xE5] = new Opcode("SBC", this::SBC, modeZP0, 3, true);
        lookupTable[0xE6] = new Opcode("INC", this::INC, modeZP0, 5, true);
        lookupTable[0xE7] = new Opcode("ISB", this::ISB, modeZP0, 5, false);
        lookupTable[0xE8] = new Opcode("INX", this::INX, modeIMP, 2, true);
        lookupTable[0xE9] = new Opcode("SBC", this::SBC, modeIMM, 2, true);
        lookupTable[0xEA] = new Opcode("NOP", this::NOP, modeIMP, 2, true);
        lookupTable[0xEB] = new Opcode("SBC", this::SBC, modeIMM, 2, false);
        lookupTable[0xEC] = new Opcode("CPX", this::CPX, modeABS, 4, true);
        lookupTable[0xED] = new Opcode("SBC", this::SBC, modeABS, 4, true);
        lookupTable[0xEE] = new Opcode("INC", this::INC, modeABS, 6, true);
        lookupTable[0xEF] = new Opcode("ISB", this::ISB, modeABS, 6, false);

        // 0xF0 - 0xFF
        lookupTable[0xF0] = new Opcode("BEQ", this::BEQ, modeREL, 2, true);
        lookupTable[0xF1] = new Opcode("SBC", this::SBC, modeIZY, 5, true);
        lookupTable[0xF2] = new Opcode("KIL", this::KIL, modeIMP, 2, false);
        lookupTable[0xF3] = new Opcode("ISB", this::ISB, modeIZY, 8, false);
        lookupTable[0xF4] = new Opcode("NOP", this::NOP, modeZPX, 4, false);
        lookupTable[0xF5] = new Opcode("SBC", this::SBC, modeZPX, 4, true);
        lookupTable[0xF6] = new Opcode("INC", this::INC, modeZPX, 6, true);
        lookupTable[0xF7] = new Opcode("ISB", this::ISB, modeZPX, 6, false);
        lookupTable[0xF8] = new Opcode("SED", this::SED, modeIMP, 2, true);
        lookupTable[0xF9] = new Opcode("SBC", this::SBC, modeABY, 4, true);
        lookupTable[0xFA] = new Opcode("NOP", this::NOP, modeIMP, 2, false);
        lookupTable[0xFB] = new Opcode("ISB", this::ISB, modeABY, 7, false);
        lookupTable[0xFC] = new Opcode("NOP", this::NOP, modeABX, 4, false);
        lookupTable[0xFD] = new Opcode("SBC", this::SBC, modeABX, 4, true);
        lookupTable[0xFE] = new Opcode("INC", this::INC, modeABX, 7, true);
        lookupTable[0xFF] = new Opcode("ISB", this::ISB, modeABX, 7, false);
    }
}