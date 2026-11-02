import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

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
    public long totalCycles = 0;

    //Like totalCycles but never reset by reset(): RESET doesn't resync the real DMC put/get
    //divider, so halt-cycle parity survives soft resets (AccuracyCoin Interrupt Flag Latency 9/A/B).
    private long dmcParityCycles = 0;

    //Floating data bus: last byte seen on a read or write; unmapped reads return it
    //("open bus").
    public int dataBus = 0x00;
    //Internal data bus latch (AccuracyCoin "Internal Data Bus" test): unlike dataBus,
    //updated by every CPU read/write including $4015, but never by a DMA's own read.
    public int internalDataBus = 0x00;
    public static boolean debugDma = false; //SCRATCH DEBUG - remove before finishing

    //Precise Tracing hook: one callback per bus access by the CPU core or a DMA riding on it.
    //Null in normal operation; must never mutate CPU state.
    public interface TraceListener {
        void onOpcodeFetch(int pc, int opcode);
        void onInterruptStart(boolean nmi);
        void onBusRead(int addr, int value, boolean dma);
        void onBusWrite(int addr, int value, boolean dma);
    }
    public TraceListener traceListener = null;

    //True while any DMA (OAM transfer, DMC halt/fetch, or a pending $4014 request) owns the
    //bus; microOps can be empty then without it being a real instruction boundary.
    public boolean isDmaActive() {
        return oamDmaActive || oamDmaStartPending || stallCycles > 0;
    }
    public boolean isOamDmaActive() { return oamDmaActive; }

    //Transient per-instruction state, valid once the effective address has been resolved
    private int addrAbs = 0x0000;
    private int fetched = 0x00;

    //Address the next queued microOp cycle will access; recorded at enqueue time.
    //maybeStartDmcHalt() uses this (not addrAbs, which can be stale/unset) to freeze
    //the address bus correctly when a DMC halt starts mid-instruction.
    private int pendingCycleAddr = 0x0000;

    //Enqueues a microOp cycle, recording its bus address for maybeStartDmcHalt().
    private void enqueue(int addr, Runnable r) {
        pendingCycleAddr = addr;
        microOps.add(r);
    }
    //Set when ABX/ABY/IZY indexing crosses a page - SHA/SHX/SHY/TAS use it to decide
    //if their write lands on addrAbs or a high-byte-corrupted address.
    private boolean pageCrossed = false;

    //Remaining bus/internal cycles for the in-flight instruction. Self-extending:
    //each cycle enqueues its own follow-up(s) at runtime.
    private final Deque<Runnable> microOps = new ArrayDeque<>();

    private boolean nmiPending = false;
    private boolean irqPending = false;
    //irqPending is the OR of every IRQ source. APU owns apuIrqLine; a mapper (e.g.
    //MMC3) owns mapperIrqLine - kept separate so one source can't clear the other's.
    private boolean apuIrqLine = false;
    private boolean mapperIrqLine = false;

    //Real hardware polls interrupt lines during an instruction's final cycle, not
    //after it completes - matters for CLI/SEI/PLP/RTI and the BRK/IRQ/NMI push.
    //pollInterrupts() latches the decision for step() to act on.
    private boolean latchedNmiPending = false;
    private boolean latchedIrqPending = false;
    private void pollInterrupts() {
        latchedNmiPending = nmiPending;
        latchedIrqPending = irqPending && !getFlag(I);
    }

    //Branch cycle-4 poll (page-cross case): ORs into the cycle-2 latch so an earlier-detected
    //interrupt stays sticky (AccuracyCoin error code E). Not for other multi-poll sequences.
    private void pollInterruptsBranchSecond() {
        boolean prevNmi = latchedNmiPending;
        boolean prevIrq = latchedIrqPending;
        pollInterrupts();
        latchedNmiPending |= prevNmi;
        latchedIrqPending |= prevIrq;
    }

    //Cycles the CPU is stalled for (e.g. OAM DMA). Consumed by step() before
    //interrupt polling/dispatch, matching real DMA cycle-stealing.
    private int stallCycles = 0;
    public void stall(int cycles) { stallCycles += cycles; }

    //Set only during an OAM DMA stall (never a DMC halt, which must not trigger an
    //early re-poll - see runStallCycle()).
    private boolean oamDmaStall = false;
    public void stallForOamDma(int cycles) {
        stallCycles += cycles;
        oamDmaStall = true;
    }

    private APU apu;
    public void connectAPU(APU apu) { this.apu = apu; }

    //DMC DMA can halt the CPU mid-instruction (OAM DMA only starts between instructions) and
    //piggybacks on stallCycles. RDY can only drop after a non-write cycle, so a request sets a
    //flag and the halt starts at the next eligible boundary with the address bus frozen.
    private boolean lastCycleWasWrite = false;
    private int lastBusAddress = 0;

    //True if the queued microOp will write; maybeStartDmcHalt() uses this (not lastCycleWasWrite)
    //to defer RDY going low across an upcoming write cycle (AccuracyCoin "DMA + $2007 Write").
    private boolean pendingOpIsWrite = false;

    private boolean dmcDmaPending = false;
    private int dmcDmaAddress = 0;
    //Reload DMA (halts on a put cycle) vs load DMA (halts on a get cycle) - see
    //APU.Dmc.requestDma(). Determines which halt-cycle parity is "ideal".
    private boolean dmcDmaIsReload = false;
    //SCRATCH DEBUG - remove before finishing: totalCycles/dmcParityCycles at request time.
    private long scratchReqTotalCycles = 0;
    private long scratchReqParityCycles = 0;

    //requestDmcDma() can arrive mid-tick before this tick's cpu.step(), but real RDY sampling
    //can't halt until the cycle after - so this mirrors dmcDmaPending one cycle late
    //(AccuracyCoin Interrupt Flag Latency test A).
    private boolean dmcDmaPendingVisible = false;

    private boolean dmcHalting = false;
    private int dmcHaltCyclesRemaining = 0;
    //Address the halt's dummy reads target - see maybeStartDmcHalt()'s assignment.
    private int dmcDummyReadAddr = 0;

    //---------------------------------------------------------------------
    // OAM DMA ($4014) - cycle-stepped state machine
    //---------------------------------------------------------------------
    //Stepped one cycle at a time so a DMC DMA can interleave: DMC wins get cycles, OAM wins put
    //cycles, and a dummy realignment cycle follows a stolen OAM get (nesdev "DMC DMA during OAM DMA").
    private boolean oamDmaActive = false;
    //$4014 asserted the request but a DMA can't start on a write cycle yet.
    private boolean oamDmaStartPending = false;
    private int oamDmaPage = 0;
    private int oamByteIndex = 0;      //0-255: which OAM byte is currently in flight
    private boolean oamGetDone = false; //true once the current byte's "get" has happened, awaiting its "put"
    private int oamLatch = 0;
    //Dummy cycles before OAM DMA's first real get: always >=1 (the "513 not 512"
    //quirk), 2 if start cycle phase doesn't align to a get slot ("514" case).
    private int oamAlignRemaining = 0;
    //Set for one cycle right after a DMC get steals an OAM get cycle: the put cycle
    //has nothing to write, so it dummy-reads the frozen address instead.
    private boolean oamRealignPending = false;
    //CPU address bus, frozen for OAM DMA's duration - always pc since OAM DMA only
    //starts between instructions.
    private int oamFrozenAddr = 0;
    //True once a DMC halt/alignment finishes while OAM DMA runs and its get is due -
    //steals the next OAM get cycle (never a put, since writes can't be interrupted).
    private boolean dmcGetPending = false;

    //Set for the first cycle after a DMC halt ends; SHA/SHX/SHY/TAS's pre-write dummy read uses
    //it for the "RDY drops 2 cycles before the write" case, which drops the high-byte-AND term
    //(AccuracyCoin Unofficial Instructions error 7).
    private boolean dmaHaltJustEnded = false;
    private boolean cycleFollowsDmcHalt = false;
    private boolean suppressHighByteCorruptionOnWrite = false;

    public void requestDmcDma(int address, boolean isReload) {
        dmcDmaPending = true;
        dmcDmaAddress = address;
        dmcDmaIsReload = isReload;
        scratchReqTotalCycles = totalCycles;
        scratchReqParityCycles = dmcParityCycles;
        if (debugDma) System.err.println("[SCHED t="+totalCycles+" parity="+dmcParityCycles+"] requestDmcDma addr="+Integer.toHexString(address)+" isReload="+isReload);
    }

    //Implicit DMA Abort ("1-cycle DMA"): when a 1-byte sample's last byte plays out just as a
    //$4015 reload finds the buffer empty, hardware skips halt/alignment and does a single get
    //(AccuracyCoin "Implicit DMA Abort", nesdev APU DMC page). Unlike requestDmcDma() it never
    //defers: if the next cycle is a write the DMA is dropped (TEST_ImplicitDMAAbort_Loop2).
    private boolean dmc1CyclePending = false;
    private int dmc1CycleAddress = 0;
    //Same same-tick deferral concern as dmcDmaPendingVisible - see its declaration.
    private boolean dmc1CyclePendingVisible = false;
    private boolean dmc1CycleGetPending = false;

    //Explicit DMA Abort: set by startAbortedDmcDma() for a scheduled-but-not-halting request;
    //the halt aborts after one cycle, or is dropped entirely if delayed by a write cycle
    //(DMA Info.txt "Explicit-stop aborted DMA").
    private boolean dmcDmaAbortPending = false;
    //Set when the halt just started (maybeStartDmcHalt()) is the aborted, 1-cycle
    //kind: no dummy/alignment/get, CPU resumes right after.
    private boolean dmcHaltAborted = false;

    public void requestDmcDma1Cycle(int address) {
        dmc1CyclePending = true;
        dmc1CycleAddress = address;
        if (debugDma) System.err.println("[t="+totalCycles+"] requestDmcDma1Cycle addr="+Integer.toHexString(address));
    }

    //TAS Maker greenzone checkpoint support. Only captured at an instruction boundary
    //(microOps empty), so a plain field copy suffices; State doesn't carry microOps.
    public static final class State {
        int a, x, y, sp, pc, status;
        long totalCycles;
        long dmcParityCycles;
        int dataBus;
        int internalDataBus;
        int addrAbs, fetched;
        boolean pageCrossed;
        boolean nmiPending, irqPending;
        boolean apuIrqLine, mapperIrqLine;
        boolean latchedNmiPending, latchedIrqPending;
        int stallCycles;
        boolean oamDmaStall;
        boolean lastCycleWasWrite;
        int lastBusAddress;
        boolean pendingOpIsWrite;
        boolean dmcDmaPending;
        int dmcDmaAddress;
        boolean dmcDmaIsReload;
        long scratchReqTotalCycles, scratchReqParityCycles;
        boolean dmcDmaPendingVisible;
        boolean dmcHalting;
        int dmcHaltCyclesRemaining;
        int dmcDummyReadAddr;
        boolean oamDmaActive;
        int oamDmaPage;
        int oamByteIndex;
        boolean oamGetDone;
        int oamLatch;
        int oamAlignRemaining;
        boolean oamRealignPending;
        int oamFrozenAddr;
        boolean dmcGetPending;
        boolean dmaHaltJustEnded;
        boolean cycleFollowsDmcHalt;
        boolean suppressHighByteCorruptionOnWrite;
        boolean dmc1CyclePending;
        int dmc1CycleAddress;
        boolean dmc1CyclePendingVisible;
        boolean dmc1CycleGetPending;
        boolean dmcDmaAbortPending;
        boolean dmcHaltAborted;
    }

    //Precondition: microOps.isEmpty().
    public State snapshot() {
        State s = new State();
        s.a = a; s.x = x; s.y = y; s.sp = sp; s.pc = pc; s.status = status;
        s.totalCycles = totalCycles;
        s.dmcParityCycles = dmcParityCycles;
        s.dataBus = dataBus;
        s.internalDataBus = internalDataBus;
        s.addrAbs = addrAbs; s.fetched = fetched;
        s.pageCrossed = pageCrossed;
        s.nmiPending = nmiPending; s.irqPending = irqPending;
        s.apuIrqLine = apuIrqLine; s.mapperIrqLine = mapperIrqLine;
        s.latchedNmiPending = latchedNmiPending; s.latchedIrqPending = latchedIrqPending;
        s.stallCycles = stallCycles;
        s.oamDmaStall = oamDmaStall;
        s.lastCycleWasWrite = lastCycleWasWrite;
        s.lastBusAddress = lastBusAddress;
        s.pendingOpIsWrite = pendingOpIsWrite;
        s.dmcDmaPending = dmcDmaPending;
        s.dmcDmaAddress = dmcDmaAddress;
        s.dmcDmaIsReload = dmcDmaIsReload;
        s.scratchReqTotalCycles = scratchReqTotalCycles; s.scratchReqParityCycles = scratchReqParityCycles;
        s.dmcDmaPendingVisible = dmcDmaPendingVisible;
        s.dmcHalting = dmcHalting;
        s.dmcHaltCyclesRemaining = dmcHaltCyclesRemaining;
        s.dmcDummyReadAddr = dmcDummyReadAddr;
        s.oamDmaActive = oamDmaActive;
        s.oamDmaPage = oamDmaPage;
        s.oamByteIndex = oamByteIndex;
        s.oamGetDone = oamGetDone;
        s.oamLatch = oamLatch;
        s.oamAlignRemaining = oamAlignRemaining;
        s.oamRealignPending = oamRealignPending;
        s.oamFrozenAddr = oamFrozenAddr;
        s.dmcGetPending = dmcGetPending;
        s.dmaHaltJustEnded = dmaHaltJustEnded;
        s.cycleFollowsDmcHalt = cycleFollowsDmcHalt;
        s.suppressHighByteCorruptionOnWrite = suppressHighByteCorruptionOnWrite;
        s.dmc1CyclePending = dmc1CyclePending;
        s.dmc1CycleAddress = dmc1CycleAddress;
        s.dmc1CyclePendingVisible = dmc1CyclePendingVisible;
        s.dmc1CycleGetPending = dmc1CycleGetPending;
        s.dmcDmaAbortPending = dmcDmaAbortPending;
        s.dmcHaltAborted = dmcHaltAborted;
        return s;
    }

    public void restore(State s) {
        a = s.a; x = s.x; y = s.y; sp = s.sp; pc = s.pc; status = s.status;
        totalCycles = s.totalCycles;
        dmcParityCycles = s.dmcParityCycles;
        dataBus = s.dataBus;
        internalDataBus = s.internalDataBus;
        addrAbs = s.addrAbs; fetched = s.fetched;
        pageCrossed = s.pageCrossed;
        nmiPending = s.nmiPending; irqPending = s.irqPending;
        apuIrqLine = s.apuIrqLine; mapperIrqLine = s.mapperIrqLine;
        latchedNmiPending = s.latchedNmiPending; latchedIrqPending = s.latchedIrqPending;
        stallCycles = s.stallCycles;
        oamDmaStall = s.oamDmaStall;
        lastCycleWasWrite = s.lastCycleWasWrite;
        lastBusAddress = s.lastBusAddress;
        pendingOpIsWrite = s.pendingOpIsWrite;
        dmcDmaPending = s.dmcDmaPending;
        dmcDmaAddress = s.dmcDmaAddress;
        dmcDmaIsReload = s.dmcDmaIsReload;
        scratchReqTotalCycles = s.scratchReqTotalCycles; scratchReqParityCycles = s.scratchReqParityCycles;
        dmcDmaPendingVisible = s.dmcDmaPendingVisible;
        dmcHalting = s.dmcHalting;
        dmcHaltCyclesRemaining = s.dmcHaltCyclesRemaining;
        dmcDummyReadAddr = s.dmcDummyReadAddr;
        oamDmaActive = s.oamDmaActive;
        oamDmaPage = s.oamDmaPage;
        oamByteIndex = s.oamByteIndex;
        oamGetDone = s.oamGetDone;
        oamLatch = s.oamLatch;
        oamAlignRemaining = s.oamAlignRemaining;
        oamRealignPending = s.oamRealignPending;
        oamFrozenAddr = s.oamFrozenAddr;
        dmcGetPending = s.dmcGetPending;
        dmaHaltJustEnded = s.dmaHaltJustEnded;
        cycleFollowsDmcHalt = s.cycleFollowsDmcHalt;
        suppressHighByteCorruptionOnWrite = s.suppressHighByteCorruptionOnWrite;
        dmc1CyclePending = s.dmc1CyclePending;
        dmc1CycleAddress = s.dmc1CycleAddress;
        dmc1CyclePendingVisible = s.dmc1CyclePendingVisible;
        dmc1CycleGetPending = s.dmc1CycleGetPending;
        dmcDmaAbortPending = s.dmcDmaAbortPending;
        dmcHaltAborted = s.dmcHaltAborted;
        microOps.clear();
    }

    //True when no instruction is in flight, so snapshot() is safe; an in-progress stall
    //(plain data, not a closure) doesn't block this.
    public boolean isAtInstructionBoundary() {
        return microOps.isEmpty();
    }

    //1-cycle DMA variant of maybeStartDmcHalt(): no halt/alignment - steal the next
    //free cycle and get, or drop entirely if it's a write.
    private void maybeStart1CycleDma() {
        if (!dmc1CyclePendingVisible) return;
        dmc1CyclePendingVisible = false;
        dmc1CyclePending = false;
        if (pendingOpIsWrite) {
            //Dropped silently; must also clear APU's dmaPending latch or the DMC
            //channel deadlocks believing a DMA is still in flight.
            if (apu != null) apu.dmcDma1CycleDropped();
            return;
        }
        dmc1CycleGetPending = true;
        stallCycles += 1;
    }

    //Explicit DMA Abort (from APU.Dmc.writeEnableFlag(false)): per DMA Info.txt, stopping
    //playback right before a reload DMA halts still starts the DMA but aborts it after one
    //cycle, or drops it if that cycle is a write. dmcHalting can't already be true here
    //since this only runs from a CPU write, which can't happen while halted.
    public void startAbortedDmcDma() {
        dmcDmaPending = true;
        dmcDmaPendingVisible = true;
        dmcDmaAddress = 0;
        dmcDmaIsReload = true;
        dmcDmaAbortPending = true;
        if (debugDma) System.err.println("[t="+totalCycles+"] startAbortedDmcDma");
    }

    public void cancelDmcDma() {
        if (debugDma) System.err.println("[t="+totalCycles+"] cancelDmcDma dmcDmaPending="+dmcDmaPending+" dmcDmaPendingVisible="+dmcDmaPendingVisible+" dmcHalting="+dmcHalting);
        //Nothing to do: a latched reload request isn't disturbed by a later $4015 disable;
        //genuine aborts are scheduled one APU cycle earlier via startAbortedDmcDma().
    }

    //Called from Bus.cpuWrite's $4014 handler. Starts the OAM DMA state machine;
    //runOamDmaCycle() drives the transfer one cycle at a time via stepInner().
    public void startOamDma(int page) {
        //A write while already in flight just updates the page, so INC $4014 (two writes) yields
        //one DMA using the second page (AccuracyCoin "INC $4014" tests 2/3). A DMA can't start
        //on a write cycle, so this only asserts the request.
        oamDmaPage = page;
        if (oamDmaActive || oamDmaStartPending) return;
        oamDmaStartPending = true;
        oamByteIndex = 0;
        oamGetDone = false;
        oamRealignPending = false;
        oamFrozenAddr = pc;
        //totalCycles isn't incremented for this cycle yet; matches the old flat-stall
        //parity formula (513 vs 514 cycles).
        oamAlignRemaining = (totalCycles % 2 == 0) ? 1 : 2;
    }

    //Runs one OAM DMA cycle, including any DMC halt/get riding along. See nesdev's
    //"DMC DMA during OAM DMA" page for the timing this implements.
    private void runOamDmaCycle() {
        //Safe to let a pending DMC request start halting here unconditionally: OAM
        //DMA keeps running untouched until the halt finishes and steals a cycle.
        if (!dmcHalting && !dmcGetPending && dmcDmaPendingVisible) {
            dmcDmaPendingVisible = false;
            dmcDmaPending = false;
            dmcHalting = true;
            dmcHaltCyclesRemaining = ((dmcParityCycles & 1) == 0) ? 2 : 3;
        }

        if (dmcHalting) {
            //Halt's dummy reads are satisfied by whatever OAM DMA is already doing
            //this cycle, so no separate read is needed.
            dmcHaltCyclesRemaining--;
            boolean haltJustFinished = dmcHaltCyclesRemaining <= 0;
            runOamPhaseCycle();
            //If OAM DMA just finished (256th byte), finishOamDma() already handed the
            //halt off to the standalone stall machinery - don't clobber that handoff.
            if (haltJustFinished && oamDmaActive) {
                dmcHalting = false;
                dmcGetPending = true;
            }
        } else if (dmcGetPending && oamAlignRemaining == 0 && !oamRealignPending && !oamGetDone) {
            //DMC's get is due and this cycle would've been one of OAM's get cycles - steal it.
            dmcGetPending = false;
            int value = dmaRead(dmcDmaAddress, oamFrozenAddr);
            if (apu != null) apu.dmcDmaCompleted(value);
            oamRealignPending = true;
        } else {
            //Nothing DMC-related, or a steal is due but this is an OAM put/alignment
            //cycle - OAM DMA takes priority; steal (if any) retries next cycle.
            runOamPhaseCycle();
        }

        totalCycles++;
        dmcParityCycles++;
        if (bus != null) bus.cpuCycle();
    }

    //Advances OAM DMA by one get/put/alignment cycle.
    private void runOamPhaseCycle() {
        if (oamAlignRemaining > 0) {
            oamAlignRemaining--;
            read(oamFrozenAddr); //dummy: OAM DMA hasn't started fetching bytes yet
            return;
        }
        if (oamRealignPending) {
            oamRealignPending = false;
            //Would be a put cycle, but the get was just stolen by DMC DMA - dummy
            //read of the frozen address instead; the byte's get retries next cycle.
            read(oamFrozenAddr);
            return;
        }
        if (!oamGetDone) {
            oamLatch = dmaRead((oamDmaPage << 8) + oamByteIndex, oamFrozenAddr);
            oamGetDone = true;
        } else {
            write(0x2004, (byte) oamLatch); //mirrors real hardware: each DMA byte behaves like an OAMDATA write
            oamGetDone = false;
            oamByteIndex++;
            if (oamByteIndex > 255) finishOamDma();
        }
    }

    //OAM DMA's 256 bytes are copied. Hands any DMC halt/get still in flight off to
    //the standalone stall machinery - see AccuracyCoin's "DMC DMA on last/second-to
    //-last OAM DMA put" cases.
    private void finishOamDma() {
        oamDmaActive = false;
        if (dmcHalting) {
            dmcDummyReadAddr = oamFrozenAddr;
            stallCycles = dmcHaltCyclesRemaining + 1; //+1 for the final "get" cycle itself
            oamDmaStall = true;
        } else if (dmcGetPending) {
            dmcGetPending = false;
            dmcHalting = true;
            dmcHaltCyclesRemaining = 0;
            dmcDummyReadAddr = oamFrozenAddr;
            stallCycles = 1;
            oamDmaStall = true;
        } else {
            //Nothing pending - resume fetching next opcode immediately.
            pollInterrupts();
        }
    }

    //Called once per cycle to decide whether a pending DMC request can start
    //halting now. Uses dmcDmaPendingVisible, not dmcDmaPending - see its declaration.
    private void maybeStartDmcHalt() {
        if (!dmcDmaPendingVisible) return;
        if (pendingOpIsWrite) {
            //Explicit DMA Abort: unlike an ordinary request (which waits indefinitely
            //for the next non-write cycle), an aborted request blocked by a write here
            //is dropped entirely, no halt - see DMA Info.txt "Explicit-stop aborted DMA".
            if (dmcDmaAbortPending) {
                dmcDmaPendingVisible = false;
                dmcDmaPending = false;
                dmcDmaAbortPending = false;
                if (apu != null) apu.dmcDmaAborted();
            }
            return;
        }
        //Load DMAs halt on a get cycle, so a request ready on a put cycle retries next cycle
        //(DMA Info.txt; AccuracyCoin "Delta Modulation Channel" tests L/M/N); reload DMAs take
        //whatever cycle they land on, and aborted DMAs skip this check.
        if (!dmcDmaIsReload && !dmcDmaAbortPending && (dmcParityCycles & 1) != 0) return;
        dmcDmaPendingVisible = false;
        dmcDmaPending = false;
        dmcHalting = true;
        dmaHaltJustEnded = true;
        //The address bus freezes at the CPU's next (not-yet-run) read target, so the halt's
        //dummy reads can clear $2002's VBlank flag or clock $4016 (AccuracyCoin DMA+$2002/
        //$2007/$4015/$4016 tests). That target is pc when microOps is empty, else pendingCycleAddr.
        dmcDummyReadAddr = microOps.isEmpty() ? pc : pendingCycleAddr;
        if (dmcDmaAbortPending) {
            //Explicit DMA Abort: halt can't be un-scheduled, but it's aborted after
            //this single cycle instead of the usual dummy/alignment/get sequence.
            dmcDmaAbortPending = false;
            dmcHaltAborted = true;
            dmcHaltCyclesRemaining = 0;
            stallCycles += 1;
        } else {
            //2 halt cycles, +1 alignment unless already on a natural read cycle; uses
            //dmcParityCycles (soft-reset safe). Load and reload DMAs halt on opposite cycle
            //types, so "aligned" parity flips between them (AccuracyCoin Explicit/Implicit
            //DMA Abort 16-phase sweep).
            boolean haltedOnGetCycle = (dmcParityCycles & 1) == 0;
            dmcHaltCyclesRemaining = haltedOnGetCycle ? 2 : 3;
            stallCycles += dmcHaltCyclesRemaining + 1; //+1 for the final "get" cycle itself
        }
        if (debugDma) System.err.println("[HALT t="+totalCycles+" parity="+dmcParityCycles+"] maybeStartDmcHalt gap="+(totalCycles-scratchReqTotalCycles)+" parityGap="+(dmcParityCycles-scratchReqParityCycles)+" isReload="+dmcDmaIsReload+" haltCycles="+dmcHaltCyclesRemaining+" aborted="+dmcHaltAborted);
        if (debugDma) System.err.println("[t="+totalCycles+"] maybeStartDmcHalt haltCycles="+dmcHaltCyclesRemaining+" aborted="+dmcHaltAborted+" dummyAddr="+Integer.toHexString(dmcDummyReadAddr));
    }

    //Functional interfaces for the three "shapes" of 6502 instruction semantics.
    //OTHER-type opcodes build their own bespoke cycle chains instead.
    @FunctionalInterface interface ReadOp  { void run(int value); }         //consumes a fetched byte
    @FunctionalInterface interface WriteOp { int run(); }                   //produces a byte to store
    @FunctionalInterface interface RmwOp   { int run(int value); }          //old byte in, new byte out

    public enum AddrMode { IMP, ACC, IMM, ZP0, ZPX, ZPY, REL, ABS, ABX, ABY, IND, IZX, IZY }
    public enum OpType { READ, WRITE, RMW, OTHER }

    public static class Opcode {
        String name;
        AddrMode mode;
        OpType type;
        boolean isOfficial;
        boolean highByteCorruption; //SHA/SHX/SHY/TAS: write lands on a corrupted address when indexing crosses a page

        ReadOp readOp;
        WriteOp writeOp;
        WriteOp plainWriteOp; //SHA/SHX/SHY/TAS: value without high-byte-AND, written if a DMC halt
                               //lands 2 cycles before the write (see cycleFollowsDmcHalt)
        RmwOp rmwOp;
        Runnable customFirst; //first micro-op of an OTHER-type instruction's self-extending chain
    }

    public final Opcode[] lookupTable = new Opcode[256];

    public CPU() {
        buildOpcodeTable();
    }

    public void connectBus(Bus bus) {
        this.bus = bus;
    }

    //Non-mutating read for the trace disassembly preview; must not touch dataBus/
    //lastBusAddress or trigger register side effects.
    private int peek(int addr) {
        return bus.debugRead(addr) & 0xFF;
    }

    //Bus helpers
    public byte read(int addr) {
        byte v = bus.cpuRead(addr);
        //$4015 reads update only the APU's internal latch, not the external data bus.
        if (addr != 0x4015) dataBus = v & 0xFF;
        //Unlike dataBus, every CPU-driven read (including $4015) updates this latch.
        internalDataBus = v & 0xFF;
        lastBusAddress = addr;
        if (traceListener != null) traceListener.onBusRead(addr & 0xFFFF, v & 0xFF, false);
        return v;
    }
    //DMA "get" read: the halted CPU's address bus stays frozen (oamFrozenAddr/dmcDummyReadAddr)
    //while the DMA drives dmaAddr, and the APU/IO chip-select ($4000-$401F, mirrored every $20)
    //decodes off the frozen bus only, so both can drive the bus at once. Resolved by seeding
    //the open-bus latch with the DMA target's value before the register decode (AccuracyCoin
    //"APU Register Activation"/"DMC DMA Bus Conflicts", nesdev "DMA open bus").
    private int dmaRead(int dmaAddr, int cpuAddressBus) {
        int result = dmaReadInner(dmaAddr, cpuAddressBus);
        if (traceListener != null) traceListener.onBusRead(dmaAddr & 0xFFFF, result & 0xFF, true);
        return result;
    }
    private int dmaReadInner(int dmaAddr, int cpuAddressBus) {
        boolean apuActive = (cpuAddressBus & 0xFFE0) == 0x4000;
        //Chip-select comes only from the frozen CPU bus, so a DMA target in $4000-$401F isn't
        //selected at all and must not go through bus.cpuRead (would trigger register side effects).
        if (!apuActive && (dmaAddr & 0xFFE0) == 0x4000) {
            int real = dataBus & 0xFF;
            lastBusAddress = dmaAddr;
            return real;
        }
        int real = bus.cpuRead(dmaAddr) & 0xFF;
        if (debugDma) System.err.println("[t="+totalCycles+"] dmaRead addr="+Integer.toHexString(dmaAddr)+" cpuAddrBus="+Integer.toHexString(cpuAddressBus)+" val="+Integer.toHexString(real));
        if (!apuActive) {
            dataBus = real;
            lastBusAddress = dmaAddr;
            return real;
        }
        dataBus = real;
        int mirrored = 0x4000 | (dmaAddr & 0x1F);
        int result = bus.cpuRead(mirrored) & 0xFF;
        //$4015 doesn't update the open-bus latch (same quirk as read()), but the
        //ROM/RAM driver on the bus at the same time still leaves it holding the target value.
        if (mirrored != 0x4015) dataBus = result;
        lastBusAddress = dmaAddr;
        return result;
    }
    public void write(int addr, byte data) {
        bus.cpuWrite(addr, data);
        dataBus = data & 0xFF;
        internalDataBus = data & 0xFF;
        lastBusAddress = addr;
        lastCycleWasWrite = true;
        if (debugDma && ((addr >= 0x50 && addr <= 0x5F) || addr == 0x4015)) System.err.println("[t="+totalCycles+"] write $"+Integer.toHexString(addr)+" = "+Integer.toHexString(data&0xFF));
        if (traceListener != null) traceListener.onBusWrite(addr & 0xFFFF, data & 0xFF, oamDmaActive);
    }

    //Flag Setters
    public void setFlag(int flag, boolean v) {
        if (v) status |= flag;
        else status &= ~flag;
    }

    public boolean getFlag(int flag) {
        return (status & flag) != 0;
    }

    //Power-on: A/X/Y cleared, S = $FD, P = $34 (I set; B isn't a stored bit here, it only
    //exists on stack copies) then the reset sequence. Per nesdev "CPU power up state".
    public void powerOn() {
        a = 0; x = 0; y = 0; sp = 0xFD;
        status = 0x00 | U | I;
        dataBus = 0;
        internalDataBus = 0;
        lastBusAddress = 0;
        resetInternal();
    }

    //Reset button: A/X/Y and the other flags are untouched, S drops by 3 (the reset
    //sequence's three suppressed pushes), I is set, then PC loads from $FFFC.
    public void reset() {
        sp = (sp - 3) & 0xFF;
        status |= I;
        resetInternal();
    }

    private void resetInternal() {
        int low = read(0xFFFC) & 0xFF;
        int high = read(0xFFFD) & 0xFF;
        pc = (high << 8) | low;

        addrAbs = 0; fetched = 0;
        microOps.clear();
        nmiPending = false;
        irqPending = false;
        apuIrqLine = false;
        mapperIrqLine = false;
        latchedNmiPending = false;
        latchedIrqPending = false;
        stallCycles = 0;
        oamDmaStall = false;
        dmcDmaPending = false;
        dmcDmaPendingVisible = false;
        dmc1CyclePending = false;
        dmc1CyclePendingVisible = false;
        dmc1CycleGetPending = false;
        dmcHalting = false;
        dmcHaltCyclesRemaining = 0;
        dmcDmaAbortPending = false;
        dmcHaltAborted = false;
        dmcDmaIsReload = false;
        lastCycleWasWrite = false;
        dmaHaltJustEnded = false;
        cycleFollowsDmcHalt = false;
        suppressHighByteCorruptionOnWrite = false;
        oamDmaActive = false;
        oamByteIndex = 0;
        oamGetDone = false;
        oamRealignPending = false;
        oamAlignRemaining = 0;
        dmcGetPending = false;
        totalCycles = 0;
    }

    //Edge-latched: PPU calls this once when it detects the VBlank NMI condition.
    public void raiseNMI() { nmiPending = true; }

    //PPU calls this when $2002 is read on the exact cycle VBlank was set, suppressing the NMI;
    //only cancels a still-pending edge, not one the CPU already latched.
    public void cancelPendingNMI() { nmiPending = false; }

    //Level-sensitive: held asserted for as long as any IRQ source (APU frame counter,
    //DMC) still wants it. The source clears its own line once its conditions are gone.
    public void raiseIRQ() { apuIrqLine = true; irqPending = apuIrqLine || mapperIrqLine; }
    public void clearIRQ() { apuIrqLine = false; irqPending = apuIrqLine || mapperIrqLine; }

    //Same contract as raiseIRQ()/clearIRQ(), but for mapper IRQ sources (e.g. MMC3) -
    //kept separate so mapper and APU can't clear each other's pending IRQ.
    public void raiseMapperIRQ() { mapperIrqLine = true; irqPending = apuIrqLine || mapperIrqLine; }
    public void clearMapperIRQ() { mapperIrqLine = false; irqPending = apuIrqLine || mapperIrqLine; }

    //Runs one clock cycle. Thin wrapper so every stepInner() exit path shares the same
    //dmcDmaPendingVisible snapshot below.
    public void step(StringBuilder traceBuffer, PPU ppu, boolean tracingEnabled) {
        stepInner(traceBuffer, ppu, tracingEnabled);
        //Snapshot pending state as of cycle end, for maybeStartDmcHalt()/
        //maybeStart1CycleDma() to consult next step() call.
        dmcDmaPendingVisible = dmcDmaPending;
        dmc1CyclePendingVisible = dmc1CyclePending;
    }

    private void stepInner(StringBuilder traceBuffer, PPU ppu, boolean tracingEnabled) {
        //Promote a pending $4014 request once off a write cycle (a DMA can't halt on one).
        if (oamDmaStartPending && !pendingOpIsWrite) {
            oamDmaStartPending = false;
            oamDmaActive = true;
        }

        if (oamDmaActive) {
            runOamDmaCycle();
            return;
        }

        if (stallCycles > 0) {
            runStallCycle();
            return;
        }

        maybeStartDmcHalt();
        maybeStart1CycleDma();
        if (stallCycles > 0) {
            runStallCycle();
            return;
        }

        lastCycleWasWrite = false;
        pendingOpIsWrite = false;
        cycleFollowsDmcHalt = dmaHaltJustEnded;
        dmaHaltJustEnded = false;

        if (microOps.isEmpty()) {
            if (latchedNmiPending) {
                latchedNmiPending = false;
                latchedIrqPending = false;
                nmiPending = false;
                if (tracingEnabled && traceBuffer != null) {
                    traceBuffer.append("=========  NMI  =========\n");
                }
                if (traceListener != null) traceListener.onInterruptStart(true);
                enqueueInterrupt(0xFFFA);
            } else if (latchedIrqPending) {
                latchedIrqPending = false;
                if (tracingEnabled && traceBuffer != null) {
                    traceBuffer.append("=========  IRQ  =========\n");
                }
                if (traceListener != null) traceListener.onInterruptStart(false);
                enqueueInterrupt(0xFFFE);
            } else {
                if (tracingEnabled && traceBuffer != null) {
                    traceBuffer.append(getTraceLine(ppu)).append("\n");
                }
                int opcodePc = pc;
                int opcode = read(pc) & 0xFF;
                if (traceListener != null) traceListener.onOpcodeFetch(opcodePc, opcode);
                pc = (pc + 1) & 0xFFFF;
                dispatch(opcode);
                totalCycles++;
                dmcParityCycles++;
                if (bus != null) bus.cpuCycle();
                return; //opcode fetch is the whole cycle; queued micro-ops run on later calls
            }
        }

        Runnable op = microOps.poll();
        if (op != null) op.run();
        totalCycles++;
        dmcParityCycles++;
        if (bus != null) bus.cpuCycle();
    }

    //Runs one cycle of an in-progress DMC DMA halt (or its final "get" fetch).
    //Shared by the top-of-step() early return and the "halt just started" path.
    private void runStallCycle() {
        stallCycles--;
        if (dmc1CycleGetPending) {
            dmc1CycleGetPending = false;
            //No halt/alignment for this variant - the stolen cycle itself does the get.
            int busAddr = microOps.isEmpty() ? pc : pendingCycleAddr;
            int value = dmaRead(dmc1CycleAddress, busAddr);
            if (apu != null) apu.dmcDmaCompleted(value);
        } else if (dmcHalting) {
            if (dmcHaltAborted) {
                //Explicit DMA Abort: this single cycle is the whole (aborted) DMA.
                dmcHalting = false;
                dmcHaltAborted = false;
                read(dmcDummyReadAddr);
                if (apu != null) apu.dmcDmaAborted();
            } else if (dmcHaltCyclesRemaining > 0) {
                dmcHaltCyclesRemaining--;
                read(dmcDummyReadAddr); //dummy read of the pending cycle's own target address
            } else {
                dmcHalting = false;
                int value = dmaRead(dmcDmaAddress, dmcDummyReadAddr); //the actual DMA "get" cycle
                if (apu != null) apu.dmcDmaCompleted(value);
            }
        }
        //Re-poll as RDY goes high after OAM DMA so an IRQ raised mid-DMA isn't missed; not after
        //a DMC halt, which would expose an IRQ one instruction early (AccuracyCoin Interrupt
        //Flag Latency test 8).
        if (stallCycles == 0 && oamDmaStall) {
            oamDmaStall = false;
            pollInterrupts();
        }
        totalCycles++;
        dmcParityCycles++;
        if (bus != null) bus.cpuCycle();
    }

    //---------------------------------------------------------------------
    // Instruction dispatch / microcode sequencing
    //---------------------------------------------------------------------

    private void dispatch(int opcode) {
        Opcode op = lookupTable[opcode];

        if (op.type == OpType.OTHER) {
            enqueue(pc, op.customFirst);
        } else if (op.mode == AddrMode.ACC) {
            enqueue(pc, () -> {
                pollInterrupts();
                read(pc); //dummy read, real hardware still reads the next byte
                a = op.rmwOp.run(a) & 0xFF;
            });
        } else if (op.mode == AddrMode.IMM) {
            enqueue(pc, () -> {
                pollInterrupts();
                fetched = read(pc) & 0xFF;
                pc = (pc + 1) & 0xFFFF;
                op.readOp.run(fetched);
            });
        } else {
            beginRWM(op);
        }
    }

    //Enqueues the address-calculation chain for READ/WRITE/RMW opcodes using
    //ZP0/ZPX/ZPY/ABS/ABX/ABY/IZX/IZY addressing; last closure calls enqueueFinal().
    private void beginRWM(Opcode op) {
        switch (op.mode) {
            case ZP0:
                enqueue(pc, () -> {
                    addrAbs = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    enqueueFinal(op);
                });
                break;

            case ZPX:
            case ZPY:
                enqueue(pc, () -> {
                    int zpBase = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    enqueue(zpBase, () -> {
                        read(zpBase); //dummy read at the unindexed zero-page address
                        int index = (op.mode == AddrMode.ZPX) ? x : y;
                        addrAbs = (zpBase + index) & 0xFF;
                        enqueueFinal(op);
                    });
                });
                break;

            case ABS:
                enqueue(pc, () -> {
                    int lo = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    enqueue(pc, () -> {
                        int hi = read(pc) & 0xFF;
                        pc = (pc + 1) & 0xFFFF;
                        addrAbs = (hi << 8) | lo;
                        enqueueFinal(op);
                    });
                });
                break;

            case ABX:
            case ABY:
                enqueue(pc, () -> {
                    int lo = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    enqueue(pc, () -> {
                        int hi = read(pc) & 0xFF;
                        pc = (pc + 1) & 0xFFFF;
                        int index = (op.mode == AddrMode.ABX) ? x : y;
                        int base = (hi << 8) | lo;
                        addrAbs = (base + index) & 0xFFFF;
                        boolean crossed = (base & 0xFF00) != (addrAbs & 0xFF00);
                        pageCrossed = crossed;
                        //2 cycles before the write cycle for WRITE-type ABX/ABY - see cycleFollowsDmcHalt.
                        if (op.type == OpType.READ && !crossed) {
                            enqueueFinal(op);
                        } else {
                            //WRITE/RMW always spend this cycle; READ only spends it on a page cross
                            int uncorrectedAddr = (base & 0xFF00) | (addrAbs & 0xFF);
                            enqueue(uncorrectedAddr, () -> {
                                //Where the DMC DMA's RDY drop lands for AccuracyCoin's SH*
                                //"RDY 2 cycles before write" sub-tests - see cycleFollowsDmcHalt.
                                if (op.highByteCorruption && cycleFollowsDmcHalt) suppressHighByteCorruptionOnWrite = true;
                                read(uncorrectedAddr);
                                enqueueFinal(op);
                            });
                        }
                    });
                });
                break;

            case IZX:
                enqueue(pc, () -> {
                    int zpBase = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    enqueue(zpBase, () -> {
                        read(zpBase); //dummy read before X is applied
                        int izxLo = (zpBase + x) & 0xFF;
                        enqueue(izxLo, () -> {
                            int lo = read((zpBase + x) & 0xFF) & 0xFF;
                            int izxHi = (zpBase + x + 1) & 0xFF;
                            enqueue(izxHi, () -> {
                                int hi = read((zpBase + x + 1) & 0xFF) & 0xFF;
                                addrAbs = (hi << 8) | lo;
                                enqueueFinal(op);
                            });
                        });
                    });
                });
                break;

            case IZY:
                enqueue(pc, () -> {
                    int zpBase = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    enqueue(zpBase, () -> {
                        int lo = read(zpBase) & 0xFF;
                        int izyHi = (zpBase + 1) & 0xFF;
                        enqueue(izyHi, () -> {
                            int hi = read((zpBase + 1) & 0xFF) & 0xFF;
                            int base = (hi << 8) | lo;
                            addrAbs = (base + y) & 0xFFFF;
                            boolean crossed = (base & 0xFF00) != (addrAbs & 0xFF00);
                            pageCrossed = crossed;
                            //2 cycles before the write cycle for WRITE-type IZY - see cycleFollowsDmcHalt.
                            if (op.type == OpType.READ && !crossed) {
                                enqueueFinal(op);
                            } else {
                                int uncorrectedAddr = (base & 0xFF00) | (addrAbs & 0xFF);
                                enqueue(uncorrectedAddr, () -> {
                                    //See the ABX/ABY case above.
                                    if (op.highByteCorruption && cycleFollowsDmcHalt) suppressHighByteCorruptionOnWrite = true;
                                    read(uncorrectedAddr);
                                    enqueueFinal(op);
                                });
                            }
                        });
                    });
                });
                break;

            default:
                throw new IllegalStateException("Unhandled addressing mode for READ/WRITE/RMW: " + op.mode);
        }
    }

    //Final cycle(s) once addrAbs is resolved: 1 for READ/WRITE, 3 for RMW (read, dummy
    //write-back of the old value, write the new value) per real 6502 bus timing.
    private void enqueueFinal(Opcode op) {
        switch (op.type) {
            case READ:
                enqueue(addrAbs, () -> {
                    pollInterrupts();
                    fetched = read(addrAbs) & 0xFF;
                    op.readOp.run(fetched);
                });
                break;

            case WRITE:
                enqueue(addrAbs, () -> {
                    pollInterrupts();
                    boolean suppress = suppressHighByteCorruptionOnWrite && op.highByteCorruption;
                    suppressHighByteCorruptionOnWrite = false;

                    int value = op.writeOp.run() & 0xFF;
                    int addr;
                    if (suppress) {
                        //SHA/SHX/SHY/TAS: DMC halt 2 cycles before write drops the
                        //high-byte-AND term - writes the plain register value instead.
                        value = op.plainWriteOp.run() & 0xFF;
                        addr = addrAbs;
                    } else {
                        //SHA/SHX/SHY/TAS: on page cross, the corrupted high byte also
                        //lands on the address bus, so the byte is stored there instead.
                        addr = (op.highByteCorruption && pageCrossed)
                                ? ((value & 0xFF) << 8) | (addrAbs & 0xFF)
                                : addrAbs;
                    }
                    write(addr, (byte) value);
                });
                pendingOpIsWrite = true;
                break;

            case RMW:
                enqueue(addrAbs, () -> {
                    fetched = read(addrAbs) & 0xFF;
                    enqueue(addrAbs, () -> {
                        write(addrAbs, (byte) fetched); //dummy write-back of the unmodified value
                        enqueue(addrAbs, () -> {
                            pollInterrupts();
                            int result = op.rmwOp.run(fetched) & 0xFF;
                            write(addrAbs, (byte) result);
                        });
                        pendingOpIsWrite = true;
                    });
                    pendingOpIsWrite = true;
                });
                break;

            default:
                throw new IllegalStateException("enqueueFinal called with OTHER opcode");
        }
    }

    //---------------------------------------------------------------------
    // Interrupts (NMI / IRQ / BRK) - all funnel through the same push+vector chain
    //---------------------------------------------------------------------

    //If an NMI becomes pending during BRK/IRQ's push cycles, it hijacks the sequence:
    //vector swaps from $FFFE to $FFFA. Consumes nmiPending so it can't also fire separately.
    private boolean pollAndConsumeNmi() {
        if (nmiPending) { nmiPending = false; return true; }
        return false;
    }

    //NMI/IRQ take 7 cycles: 2 internal, then the shared 5-cycle push+vector chain.
    private void enqueueInterrupt(int vector) {
        boolean hijackable = (vector == 0xFFFE); //only IRQ can be hijacked by NMI, not NMI by itself
        enqueue(pc, () -> {
            read(pc);
            enqueue(pc, () -> {
                read(pc);
                int[] vectorBox = {vector};
                if (hijackable && pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
                enqueue(0x0100 + sp, pushAndVectorFirst(vectorBox, false, hijackable));
                pendingOpIsWrite = true;
            });
        });
    }

    //5-cycle chain: push PCH, PCL, status, then fetch vector lo/hi. Polls precede each push
    //but not the vector fetch; the "push status" poll decides NMI hijacking and what's latched next.
    private Runnable pushAndVectorFirst(int[] vectorBox, boolean pushB, boolean hijackable) {
        return () -> {
            pollInterrupts();
            if (hijackable && pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
            write(0x0100 + sp, (byte) ((pc >> 8) & 0xFF));
            sp = (sp - 1) & 0xFF;
            enqueue(0x0100 + sp, () -> {
                pollInterrupts();
                if (hijackable && pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
                write(0x0100 + sp, (byte) (pc & 0xFF));
                sp = (sp - 1) & 0xFF;
                enqueue(0x0100 + sp, () -> {
                    if (hijackable && pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
                    write(0x0100 + sp, (byte) (status | U | (pushB ? B : 0)));
                    sp = (sp - 1) & 0xFF;
                    setFlag(I, true);
                    //Poll after I is set here (unlike other poll points) so a still-
                    //pending IRQ can't immediately re-fire after this sequence.
                    pollInterrupts();
                    enqueue(vectorBox[0], () -> {
                        int lo = read(vectorBox[0]) & 0xFF;
                        enqueue(vectorBox[0] + 1, () -> {
                            int hi = read(vectorBox[0] + 1) & 0xFF;
                            pc = (hi << 8) | lo;
                        });
                    });
                });
                pendingOpIsWrite = true;
            });
            pendingOpIsWrite = true;
        };
    }

    //---------------------------------------------------------------------
    // OTHER-type instruction builders
    //---------------------------------------------------------------------

    //Shared shape for implied-mode instructions: dummy read, then the effect.
    private Runnable implied(Runnable action) {
        return () -> {
            //Poll before action.run() - matters for CLI/SEI, which must see the old I flag.
            pollInterrupts();
            read(pc);
            action.run();
        };
    }

    //Branch poll timing: not-taken polls once before cycle 2; taken re-polls before
    //cycle 4 only if a page was crossed (cycle 4 exists); taken-not-crossed doesn't re-poll.
    private Runnable branch(BooleanSupplier cond) {
        return () -> {
            pollInterrupts(); //poll before cycle 2 (always)
            int rawOffset = read(pc) & 0xFF;
            pc = (pc + 1) & 0xFFFF;
            int signedOffset = (byte) rawOffset;

            if (!cond.getAsBoolean()) return; //not taken: 2 cycles total

            enqueue(pc, () -> {
                read(pc); //dummy read, branch is being taken - no poll here
                int oldPc = pc;
                int target = (pc + signedOffset) & 0xFFFF;
                pc = target;

                if ((oldPc & 0xFF00) != (target & 0xFF00)) {
                    int fixupAddr = (oldPc & 0xFF00) | (target & 0xFF);
                    enqueue(fixupAddr, () -> {
                        pollInterruptsBranchSecond(); //poll before cycle 4 (only exists if page crossed)
                        read(fixupAddr);
                    });
                }
            });
        };
    }

    private Runnable brk() {
        return () -> {
            //BRK polls before cycle 2 too; a pending NMI here can hijack it to the NMI vector.
            pollInterrupts();
            int[] vectorBox = {0xFFFE};
            if (pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
            read(pc); //padding byte, discarded
            pc = (pc + 1) & 0xFFFF;
            enqueue(0x0100 + sp, pushAndVectorFirst(vectorBox, true, true));
            pendingOpIsWrite = true;
        };
    }

    private Runnable jsr() {
        return () -> {
            int lo = read(pc) & 0xFF;
            pc = (pc + 1) & 0xFFFF;
            enqueue(0x0100 + sp, () -> {
                read(0x0100 + sp); //internal delay cycle
                enqueue(0x0100 + sp, () -> {
                    write(0x0100 + sp, (byte) ((pc >> 8) & 0xFF));
                    sp = (sp - 1) & 0xFF;
                    enqueue(0x0100 + sp, () -> {
                        write(0x0100 + sp, (byte) (pc & 0xFF));
                        sp = (sp - 1) & 0xFF;
                        enqueue(pc, () -> {
                            pollInterrupts();
                            int hi = read(pc) & 0xFF;
                            pc = (hi << 8) | lo;
                        });
                    });
                    pendingOpIsWrite = true;
                });
                pendingOpIsWrite = true;
            });
        };
    }

    private Runnable rts() {
        return () -> {
            read(pc);
            enqueue(0x0100 + sp, () -> {
                read(0x0100 + sp);
                //The next cycles increment sp before reading, so the enqueued address anticipates that.
                enqueue(0x0100 + ((sp + 1) & 0xFF), () -> {
                    sp = (sp + 1) & 0xFF;
                    int lo = read(0x0100 + sp) & 0xFF;
                    enqueue(0x0100 + ((sp + 1) & 0xFF), () -> {
                        sp = (sp + 1) & 0xFF;
                        int hi = read(0x0100 + sp) & 0xFF;
                        pc = (hi << 8) | lo;
                        enqueue(pc, () -> {
                            //Cycle 6: dummy read at the restored return address before PC increments.
                            read(pc);
                            pollInterrupts();
                            pc = (pc + 1) & 0xFFFF;
                        });
                    });
                });
            });
        };
    }

    private Runnable rti() {
        return () -> {
            read(pc);
            enqueue(0x0100 + sp, () -> {
                read(0x0100 + sp);
                //See rts(): these cycles increment sp before reading.
                enqueue(0x0100 + ((sp + 1) & 0xFF), () -> {
                    sp = (sp + 1) & 0xFF;
                    status = read(0x0100 + sp) & 0xFF;
                    status &= ~B;
                    status |= U;
                    enqueue(0x0100 + ((sp + 1) & 0xFF), () -> {
                        sp = (sp + 1) & 0xFF;
                        int lo = read(0x0100 + sp) & 0xFF;
                        enqueue(0x0100 + ((sp + 1) & 0xFF), () -> {
                            pollInterrupts();
                            sp = (sp + 1) & 0xFF;
                            int hi = read(0x0100 + sp) & 0xFF;
                            pc = (hi << 8) | lo;
                        });
                    });
                });
            });
        };
    }

    private Runnable pha() {
        return () -> {
            read(pc);
            enqueue(0x0100 + sp, () -> {
                pollInterrupts();
                write(0x0100 + sp, (byte) a);
                sp = (sp - 1) & 0xFF;
            });
            pendingOpIsWrite = true;
        };
    }

    private Runnable php() {
        return () -> {
            read(pc);
            enqueue(0x0100 + sp, () -> {
                pollInterrupts();
                write(0x0100 + sp, (byte) (status | B | U));
                sp = (sp - 1) & 0xFF;
            });
            pendingOpIsWrite = true;
        };
    }

    private Runnable pla() {
        return () -> {
            read(pc);
            enqueue(0x0100 + sp, () -> {
                read(0x0100 + sp);
                //See rts()'s comment: sp increments before this next read.
                enqueue(0x0100 + ((sp + 1) & 0xFF), () -> {
                    pollInterrupts();
                    sp = (sp + 1) & 0xFF;
                    a = read(0x0100 + sp) & 0xFF;
                    setFlag(Z, a == 0);
                    setFlag(N, (a & 0x80) != 0);
                });
            });
        };
    }

    private Runnable plp() {
        return () -> {
            read(pc);
            enqueue(0x0100 + sp, () -> {
                read(0x0100 + sp);
                //See rts()'s comment: sp increments before this next read.
                enqueue(0x0100 + ((sp + 1) & 0xFF), () -> {
                    //Poll before pulling status - must see the old I flag.
                    pollInterrupts();
                    sp = (sp + 1) & 0xFF;
                    status = read(0x0100 + sp) & 0xFF;
                    //B has no physical flip-flop; must not stay latched or it leaks
                    //into every later BRK/IRQ/NMI push until the next RTI.
                    status &= ~B;
                    setFlag(U, true);
                });
            });
        };
    }

    private Runnable jmpAbs() {
        return () -> {
            int lo = read(pc) & 0xFF;
            pc = (pc + 1) & 0xFFFF;
            enqueue(pc, () -> {
                pollInterrupts();
                int hi = read(pc) & 0xFF;
                pc = (pc + 1) & 0xFFFF;
                pc = (hi << 8) | lo;
            });
        };
    }

    private Runnable jmpInd() {
        return () -> {
            int ptrLo = read(pc) & 0xFF;
            pc = (pc + 1) & 0xFFFF;
            enqueue(pc, () -> {
                int ptrHi = read(pc) & 0xFF;
                pc = (pc + 1) & 0xFFFF;
                int ptr = (ptrHi << 8) | ptrLo;
                enqueue(ptr, () -> {
                    int lo = read(ptr) & 0xFF;
                    int hiAddr = ((ptr & 0x00FF) == 0x00FF) ? (ptr & 0xFF00) : (ptr + 1);
                    enqueue(hiAddr, () -> {
                        pollInterrupts();
                        int hi = read(hiAddr) & 0xFF;
                        pc = (hi << 8) | lo;
                    });
                });
            });
        };
    }

    //---------------------------------------------------------------------
    // Instruction semantics (pure register/flag logic - no bus access, no pc/addr math)
    //---------------------------------------------------------------------

    //--- READ ops ---
    private void ADC(int v) { int temp = a + v + (getFlag(C) ? 1 : 0); setFlag(V, (~(a ^ v) & (a ^ temp) & 0x80) != 0); setFlag(C, temp > 255); a = temp & 0xFF; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void AND(int v) { a &= v; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void BIT(int v) { setFlag(Z, (a & v) == 0); setFlag(N, (v & 0x80) != 0); setFlag(V, (v & 0x40) != 0); }
    private void CMP(int v) { int temp = (a - v) & 0xFF; setFlag(C, a >= v); setFlag(Z, temp == 0); setFlag(N, (temp & 0x80) != 0); }
    private void CPX(int v) { int temp = (x - v) & 0xFF; setFlag(C, x >= v); setFlag(Z, temp == 0); setFlag(N, (temp & 0x80) != 0); }
    private void CPY(int v) { int temp = (y - v) & 0xFF; setFlag(C, y >= v); setFlag(Z, temp == 0); setFlag(N, (temp & 0x80) != 0); }
    private void EOR(int v) { a ^= v; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void LDA(int v) { a = v; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void LDX(int v) { x = v; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }
    private void LDY(int v) { y = v; setFlag(Z, y == 0); setFlag(N, (y & 0x80) != 0); }
    private void ORA(int v) { a |= v; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void SBC(int v) { int val = v ^ 0x00FF; int temp = a + val + (getFlag(C) ? 1 : 0); setFlag(C, (temp & 0x100) != 0); setFlag(V, ((temp ^ a) & (temp ^ val) & 0x80) != 0); a = temp & 0xFF; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void NOPread(int v) { }

    //--- Unofficial READ ops ---
    private void LAX(int v) { a = v; x = v; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }
    private void LAS(int v) { int val = v & sp; a = val; x = val; sp = val; setFlag(Z, val == 0); setFlag(N, (val & 0x80) != 0); }
    private void ANC(int v) { a &= v; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); setFlag(C, getFlag(N)); }
    private void ALR(int v) { a &= v; setFlag(C, (a & 0x01) != 0); a = (a >> 1) & 0xFF; setFlag(Z, a == 0); setFlag(N, false); }
    private void ARR(int v) { a &= v; a = (a >> 1) | (getFlag(C) ? 0x80 : 0); setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); setFlag(C, (a & 0x40) != 0); setFlag(V, ((a & 0x40) ^ ((a & 0x20) << 1)) != 0); }
    private void AXS(int v) { int temp = (a & x) - v; setFlag(C, (a & x) >= v); x = temp & 0xFF; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }
    private void XAA(int v) { a = x & v; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }

    //--- WRITE ops ---
    private int STA() { return a; }
    private int STX() { return x; }
    private int STY() { return y; }
    private int SAX() { return a & x; }
    private int SHX() { int high = ((addrAbs - y) >> 8) & 0xFF; return x & (high + 1); }
    private int SHY() { int high = ((addrAbs - x) >> 8) & 0xFF; return y & (high + 1); }
    private int AHX() { int high = ((addrAbs - y) >> 8) & 0xFF; return a & x & (high + 1); }
    private int TAS() { sp = a & x; int high = ((addrAbs - y) >> 8) & 0xFF; return sp & (high + 1); }

    //Without "&(high+1)" - what SHX/SHY/AHX/TAS write instead if a DMC halt lands
    //2 cycles before the write (AccuracyCoin Unofficial Instructions error 7).
    private int SHX_plain() { return x; }
    private int SHY_plain() { return y; }
    private int AHX_plain() { return a & x; }
    private int TAS_plain() { return sp; } //TAS() already latched sp = a & x before this runs

    //--- RMW ops ---
    private int ASL(int v) { setFlag(C, (v & 0x80) != 0); int r = (v << 1) & 0xFF; setFlag(Z, r == 0); setFlag(N, (r & 0x80) != 0); return r; }
    private int LSR(int v) { setFlag(C, (v & 0x01) != 0); int r = (v >> 1) & 0xFF; setFlag(Z, r == 0); setFlag(N, false); return r; }
    private int ROL(int v) { int r = ((v << 1) | (getFlag(C) ? 1 : 0)) & 0xFF; setFlag(C, (v & 0x80) != 0); setFlag(Z, r == 0); setFlag(N, (r & 0x80) != 0); return r; }
    private int ROR(int v) { int r = ((v >> 1) | (getFlag(C) ? 0x80 : 0)) & 0xFF; setFlag(C, (v & 0x01) != 0); setFlag(Z, r == 0); setFlag(N, (r & 0x80) != 0); return r; }
    private int INC(int v) { int r = (v + 1) & 0xFF; setFlag(Z, r == 0); setFlag(N, (r & 0x80) != 0); return r; }
    private int DEC(int v) { int r = (v - 1) & 0xFF; setFlag(Z, r == 0); setFlag(N, (r & 0x80) != 0); return r; }

    //--- Unofficial RMW ops ---
    private int SLO(int v) { setFlag(C, (v & 0x80) != 0); int r = (v << 1) & 0xFF; a |= r; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); return r; }
    private int RLA(int v) { int r = ((v << 1) | (getFlag(C) ? 1 : 0)) & 0xFF; setFlag(C, (v & 0x80) != 0); a &= r; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); return r; }
    private int SRE(int v) { setFlag(C, (v & 0x01) != 0); int r = (v >> 1) & 0xFF; a ^= r; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); return r; }
    private int RRA(int v) {
        int r = ((v >> 1) | (getFlag(C) ? 0x80 : 0)) & 0xFF;
        boolean carryOut = (v & 0x01) != 0;
        setFlag(C, carryOut);
        int temp = a + r + (carryOut ? 1 : 0);
        setFlag(V, (~(a ^ r) & (a ^ temp) & 0x80) != 0);
        setFlag(C, temp > 255);
        a = temp & 0xFF;
        setFlag(Z, a == 0);
        setFlag(N, (a & 0x80) != 0);
        return r;
    }
    private int ISB(int v) {
        int r = (v + 1) & 0xFF;
        int val = r ^ 0x00FF;
        int temp = a + val + (getFlag(C) ? 1 : 0);
        setFlag(C, (temp & 0x100) != 0);
        setFlag(V, ((temp ^ a) & (temp ^ val) & 0x80) != 0);
        a = temp & 0xFF;
        setFlag(Z, a == 0);
        setFlag(N, (a & 0x80) != 0);
        return r;
    }
    private int DCP(int v) { int r = (v - 1) & 0xFF; int sub = (a - r) & 0xFF; setFlag(C, a >= r); setFlag(Z, sub == 0); setFlag(N, (sub & 0x80) != 0); return r; }

    //---------------------------------------------------------------------
    // Opcode table factories
    //---------------------------------------------------------------------

    private Opcode R(String name, AddrMode mode, ReadOp op, boolean official) {
        Opcode o = new Opcode();
        o.name = name; o.mode = mode; o.type = OpType.READ; o.isOfficial = official; o.readOp = op;
        return o;
    }

    private Opcode W(String name, AddrMode mode, WriteOp op, boolean official) {
        Opcode o = new Opcode();
        o.name = name; o.mode = mode; o.type = OpType.WRITE; o.isOfficial = official; o.writeOp = op;
        return o;
    }

    private Opcode M(String name, AddrMode mode, RmwOp op, boolean official) {
        Opcode o = new Opcode();
        o.name = name; o.mode = mode; o.type = OpType.RMW; o.isOfficial = official; o.rmwOp = op;
        return o;
    }

    private Opcode O(String name, AddrMode mode, Runnable customFirst, boolean official) {
        Opcode o = new Opcode();
        o.name = name; o.mode = mode; o.type = OpType.OTHER; o.isOfficial = official; o.customFirst = customFirst;
        return o;
    }

    //---------------------------------------------------------------------
    // Trace logger
    //---------------------------------------------------------------------

    public String getTraceLine(PPU ppu) {
        int opcode = peek(pc);
        Opcode op = lookupTable[opcode];

        int b1 = peek(pc + 1);
        int b2 = peek(pc + 2);

        String hexBytes = String.format("%02X", opcode);
        String operand = "";

        switch (op.mode) {
            case IMM: hexBytes += String.format(" %02X", b1); operand = String.format("#$%02X", b1); break;
            case ZP0: hexBytes += String.format(" %02X", b1); operand = String.format("$%02X", b1); break;
            case ZPX: hexBytes += String.format(" %02X", b1); operand = String.format("$%02X, X", b1); break;
            case ZPY: hexBytes += String.format(" %02X", b1); operand = String.format("$%02X, Y", b1); break;
            case ABS: hexBytes += String.format(" %02X %02X", b1, b2); operand = String.format("$%02X%02X", b2, b1); break;
            case ABX: hexBytes += String.format(" %02X %02X", b1, b2); operand = String.format("$%02X%02X, X", b2, b1); break;
            case ABY: hexBytes += String.format(" %02X %02X", b1, b2); operand = String.format("$%02X%02X, Y", b2, b1); break;
            case IND: hexBytes += String.format(" %02X %02X", b1, b2); operand = String.format("($%02X%02X)", b2, b1); break;
            case IZX: hexBytes += String.format(" %02X", b1); operand = String.format("($%02X, X)", b1); break;
            case IZY: hexBytes += String.format(" %02X", b1); operand = String.format("($%02X), Y", b1); break;
            case REL: hexBytes += String.format(" %02X", b1); operand = String.format("$%04X", (pc + 2 + (byte) b1) & 0xFFFF); break;
            default: break; //IMP/ACC take no operand bytes
        }

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

    //---------------------------------------------------------------------
    // Full 256-opcode table
    //---------------------------------------------------------------------

    private void buildOpcodeTable() {
        // 0x00 - 0x0F
        lookupTable[0x00] = O("BRK", AddrMode.IMP, brk(), true);
        lookupTable[0x01] = R("ORA", AddrMode.IZX, this::ORA, true);
        lookupTable[0x02] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x03] = M("SLO", AddrMode.IZX, this::SLO, false);
        lookupTable[0x04] = R("NOP", AddrMode.ZP0, this::NOPread, false);
        lookupTable[0x05] = R("ORA", AddrMode.ZP0, this::ORA, true);
        lookupTable[0x06] = M("ASL", AddrMode.ZP0, this::ASL, true);
        lookupTable[0x07] = M("SLO", AddrMode.ZP0, this::SLO, false);
        lookupTable[0x08] = O("PHP", AddrMode.IMP, php(), true);
        lookupTable[0x09] = R("ORA", AddrMode.IMM, this::ORA, true);
        lookupTable[0x0A] = M("ASL", AddrMode.ACC, this::ASL, true);
        lookupTable[0x0B] = R("ANC", AddrMode.IMM, this::ANC, false);
        lookupTable[0x0C] = R("NOP", AddrMode.ABS, this::NOPread, false);
        lookupTable[0x0D] = R("ORA", AddrMode.ABS, this::ORA, true);
        lookupTable[0x0E] = M("ASL", AddrMode.ABS, this::ASL, true);
        lookupTable[0x0F] = M("SLO", AddrMode.ABS, this::SLO, false);

        // 0x10 - 0x1F
        lookupTable[0x10] = O("BPL", AddrMode.REL, branch(() -> !getFlag(N)), true);
        lookupTable[0x11] = R("ORA", AddrMode.IZY, this::ORA, true);
        lookupTable[0x12] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x13] = M("SLO", AddrMode.IZY, this::SLO, false);
        lookupTable[0x14] = R("NOP", AddrMode.ZPX, this::NOPread, false);
        lookupTable[0x15] = R("ORA", AddrMode.ZPX, this::ORA, true);
        lookupTable[0x16] = M("ASL", AddrMode.ZPX, this::ASL, true);
        lookupTable[0x17] = M("SLO", AddrMode.ZPX, this::SLO, false);
        lookupTable[0x18] = O("CLC", AddrMode.IMP, implied(() -> setFlag(C, false)), true);
        lookupTable[0x19] = R("ORA", AddrMode.ABY, this::ORA, true);
        lookupTable[0x1A] = O("NOP", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x1B] = M("SLO", AddrMode.ABY, this::SLO, false);
        lookupTable[0x1C] = R("NOP", AddrMode.ABX, this::NOPread, false);
        lookupTable[0x1D] = R("ORA", AddrMode.ABX, this::ORA, true);
        lookupTable[0x1E] = M("ASL", AddrMode.ABX, this::ASL, true);
        lookupTable[0x1F] = M("SLO", AddrMode.ABX, this::SLO, false);

        // 0x20 - 0x2F
        lookupTable[0x20] = O("JSR", AddrMode.ABS, jsr(), true);
        lookupTable[0x21] = R("AND", AddrMode.IZX, this::AND, true);
        lookupTable[0x22] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x23] = M("RLA", AddrMode.IZX, this::RLA, false);
        lookupTable[0x24] = R("BIT", AddrMode.ZP0, this::BIT, true);
        lookupTable[0x25] = R("AND", AddrMode.ZP0, this::AND, true);
        lookupTable[0x26] = M("ROL", AddrMode.ZP0, this::ROL, true);
        lookupTable[0x27] = M("RLA", AddrMode.ZP0, this::RLA, false);
        lookupTable[0x28] = O("PLP", AddrMode.IMP, plp(), true);
        lookupTable[0x29] = R("AND", AddrMode.IMM, this::AND, true);
        lookupTable[0x2A] = M("ROL", AddrMode.ACC, this::ROL, true);
        lookupTable[0x2B] = R("ANC", AddrMode.IMM, this::ANC, false);
        lookupTable[0x2C] = R("BIT", AddrMode.ABS, this::BIT, true);
        lookupTable[0x2D] = R("AND", AddrMode.ABS, this::AND, true);
        lookupTable[0x2E] = M("ROL", AddrMode.ABS, this::ROL, true);
        lookupTable[0x2F] = M("RLA", AddrMode.ABS, this::RLA, false);

        // 0x30 - 0x3F
        lookupTable[0x30] = O("BMI", AddrMode.REL, branch(() -> getFlag(N)), true);
        lookupTable[0x31] = R("AND", AddrMode.IZY, this::AND, true);
        lookupTable[0x32] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x33] = M("RLA", AddrMode.IZY, this::RLA, false);
        lookupTable[0x34] = R("NOP", AddrMode.ZPX, this::NOPread, false);
        lookupTable[0x35] = R("AND", AddrMode.ZPX, this::AND, true);
        lookupTable[0x36] = M("ROL", AddrMode.ZPX, this::ROL, true);
        lookupTable[0x37] = M("RLA", AddrMode.ZPX, this::RLA, false);
        lookupTable[0x38] = O("SEC", AddrMode.IMP, implied(() -> setFlag(C, true)), true);
        lookupTable[0x39] = R("AND", AddrMode.ABY, this::AND, true);
        lookupTable[0x3A] = O("NOP", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x3B] = M("RLA", AddrMode.ABY, this::RLA, false);
        lookupTable[0x3C] = R("NOP", AddrMode.ABX, this::NOPread, false);
        lookupTable[0x3D] = R("AND", AddrMode.ABX, this::AND, true);
        lookupTable[0x3E] = M("ROL", AddrMode.ABX, this::ROL, true);
        lookupTable[0x3F] = M("RLA", AddrMode.ABX, this::RLA, false);

        // 0x40 - 0x4F
        lookupTable[0x40] = O("RTI", AddrMode.IMP, rti(), true);
        lookupTable[0x41] = R("EOR", AddrMode.IZX, this::EOR, true);
        lookupTable[0x42] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x43] = M("SRE", AddrMode.IZX, this::SRE, false);
        lookupTable[0x44] = R("NOP", AddrMode.ZP0, this::NOPread, false);
        lookupTable[0x45] = R("EOR", AddrMode.ZP0, this::EOR, true);
        lookupTable[0x46] = M("LSR", AddrMode.ZP0, this::LSR, true);
        lookupTable[0x47] = M("SRE", AddrMode.ZP0, this::SRE, false);
        lookupTable[0x48] = O("PHA", AddrMode.IMP, pha(), true);
        lookupTable[0x49] = R("EOR", AddrMode.IMM, this::EOR, true);
        lookupTable[0x4A] = M("LSR", AddrMode.ACC, this::LSR, true);
        lookupTable[0x4B] = R("ALR", AddrMode.IMM, this::ALR, false);
        lookupTable[0x4C] = O("JMP", AddrMode.ABS, jmpAbs(), true);
        lookupTable[0x4D] = R("EOR", AddrMode.ABS, this::EOR, true);
        lookupTable[0x4E] = M("LSR", AddrMode.ABS, this::LSR, true);
        lookupTable[0x4F] = M("SRE", AddrMode.ABS, this::SRE, false);

        // 0x50 - 0x5F
        lookupTable[0x50] = O("BVC", AddrMode.REL, branch(() -> !getFlag(V)), true);
        lookupTable[0x51] = R("EOR", AddrMode.IZY, this::EOR, true);
        lookupTable[0x52] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x53] = M("SRE", AddrMode.IZY, this::SRE, false);
        lookupTable[0x54] = R("NOP", AddrMode.ZPX, this::NOPread, false);
        lookupTable[0x55] = R("EOR", AddrMode.ZPX, this::EOR, true);
        lookupTable[0x56] = M("LSR", AddrMode.ZPX, this::LSR, true);
        lookupTable[0x57] = M("SRE", AddrMode.ZPX, this::SRE, false);
        lookupTable[0x58] = O("CLI", AddrMode.IMP, implied(() -> setFlag(I, false)), true);
        lookupTable[0x59] = R("EOR", AddrMode.ABY, this::EOR, true);
        lookupTable[0x5A] = O("NOP", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x5B] = M("SRE", AddrMode.ABY, this::SRE, false);
        lookupTable[0x5C] = R("NOP", AddrMode.ABX, this::NOPread, false);
        lookupTable[0x5D] = R("EOR", AddrMode.ABX, this::EOR, true);
        lookupTable[0x5E] = M("LSR", AddrMode.ABX, this::LSR, true);
        lookupTable[0x5F] = M("SRE", AddrMode.ABX, this::SRE, false);

        // 0x60 - 0x6F
        lookupTable[0x60] = O("RTS", AddrMode.IMP, rts(), true);
        lookupTable[0x61] = R("ADC", AddrMode.IZX, this::ADC, true);
        lookupTable[0x62] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x63] = M("RRA", AddrMode.IZX, this::RRA, false);
        lookupTable[0x64] = R("NOP", AddrMode.ZP0, this::NOPread, false);
        lookupTable[0x65] = R("ADC", AddrMode.ZP0, this::ADC, true);
        lookupTable[0x66] = M("ROR", AddrMode.ZP0, this::ROR, true);
        lookupTable[0x67] = M("RRA", AddrMode.ZP0, this::RRA, false);
        lookupTable[0x68] = O("PLA", AddrMode.IMP, pla(), true);
        lookupTable[0x69] = R("ADC", AddrMode.IMM, this::ADC, true);
        lookupTable[0x6A] = M("ROR", AddrMode.ACC, this::ROR, true);
        lookupTable[0x6B] = R("ARR", AddrMode.IMM, this::ARR, false);
        lookupTable[0x6C] = O("JMP", AddrMode.IND, jmpInd(), true);
        lookupTable[0x6D] = R("ADC", AddrMode.ABS, this::ADC, true);
        lookupTable[0x6E] = M("ROR", AddrMode.ABS, this::ROR, true);
        lookupTable[0x6F] = M("RRA", AddrMode.ABS, this::RRA, false);

        // 0x70 - 0x7F
        lookupTable[0x70] = O("BVS", AddrMode.REL, branch(this::getFlagV), true);
        lookupTable[0x71] = R("ADC", AddrMode.IZY, this::ADC, true);
        lookupTable[0x72] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x73] = M("RRA", AddrMode.IZY, this::RRA, false);
        lookupTable[0x74] = R("NOP", AddrMode.ZPX, this::NOPread, false);
        lookupTable[0x75] = R("ADC", AddrMode.ZPX, this::ADC, true);
        lookupTable[0x76] = M("ROR", AddrMode.ZPX, this::ROR, true);
        lookupTable[0x77] = M("RRA", AddrMode.ZPX, this::RRA, false);
        lookupTable[0x78] = O("SEI", AddrMode.IMP, implied(() -> setFlag(I, true)), true);
        lookupTable[0x79] = R("ADC", AddrMode.ABY, this::ADC, true);
        lookupTable[0x7A] = O("NOP", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x7B] = M("RRA", AddrMode.ABY, this::RRA, false);
        lookupTable[0x7C] = R("NOP", AddrMode.ABX, this::NOPread, false);
        lookupTable[0x7D] = R("ADC", AddrMode.ABX, this::ADC, true);
        lookupTable[0x7E] = M("ROR", AddrMode.ABX, this::ROR, true);
        lookupTable[0x7F] = M("RRA", AddrMode.ABX, this::RRA, false);

        // 0x80 - 0x8F
        lookupTable[0x80] = R("NOP", AddrMode.IMM, this::NOPread, false);
        lookupTable[0x81] = W("STA", AddrMode.IZX, this::STA, true);
        lookupTable[0x82] = R("NOP", AddrMode.IMM, this::NOPread, false);
        lookupTable[0x83] = W("SAX", AddrMode.IZX, this::SAX, false);
        lookupTable[0x84] = W("STY", AddrMode.ZP0, this::STY, true);
        lookupTable[0x85] = W("STA", AddrMode.ZP0, this::STA, true);
        lookupTable[0x86] = W("STX", AddrMode.ZP0, this::STX, true);
        lookupTable[0x87] = W("SAX", AddrMode.ZP0, this::SAX, false);
        lookupTable[0x88] = O("DEY", AddrMode.IMP, implied(() -> { y = (y - 1) & 0xFF; setFlag(Z, y == 0); setFlag(N, (y & 0x80) != 0); }), true);
        lookupTable[0x89] = R("NOP", AddrMode.IMM, this::NOPread, false);
        lookupTable[0x8A] = O("TXA", AddrMode.IMP, implied(() -> { a = x; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }), true);
        lookupTable[0x8B] = R("XAA", AddrMode.IMM, this::XAA, false);
        lookupTable[0x8C] = W("STY", AddrMode.ABS, this::STY, true);
        lookupTable[0x8D] = W("STA", AddrMode.ABS, this::STA, true);
        lookupTable[0x8E] = W("STX", AddrMode.ABS, this::STX, true);
        lookupTable[0x8F] = W("SAX", AddrMode.ABS, this::SAX, false);

        // 0x90 - 0x9F
        lookupTable[0x90] = O("BCC", AddrMode.REL, branch(() -> !getFlag(C)), true);
        lookupTable[0x91] = W("STA", AddrMode.IZY, this::STA, true);
        lookupTable[0x92] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0x93] = W("AHX", AddrMode.IZY, this::AHX, false);
        lookupTable[0x93].highByteCorruption = true;
        lookupTable[0x93].plainWriteOp = this::AHX_plain;
        lookupTable[0x94] = W("STY", AddrMode.ZPX, this::STY, true);
        lookupTable[0x95] = W("STA", AddrMode.ZPX, this::STA, true);
        lookupTable[0x96] = W("STX", AddrMode.ZPY, this::STX, true);
        lookupTable[0x97] = W("SAX", AddrMode.ZPY, this::SAX, false);
        lookupTable[0x98] = O("TYA", AddrMode.IMP, implied(() -> { a = y; setFlag(Z, a == 0); setFlag(N, (a & 0x80) != 0); }), true);
        lookupTable[0x99] = W("STA", AddrMode.ABY, this::STA, true);
        lookupTable[0x9A] = O("TXS", AddrMode.IMP, implied(() -> sp = x), true);
        lookupTable[0x9B] = W("TAS", AddrMode.ABY, this::TAS, false);
        lookupTable[0x9B].highByteCorruption = true;
        lookupTable[0x9B].plainWriteOp = this::TAS_plain;
        lookupTable[0x9C] = W("SHY", AddrMode.ABX, this::SHY, false);
        lookupTable[0x9C].highByteCorruption = true;
        lookupTable[0x9C].plainWriteOp = this::SHY_plain;
        lookupTable[0x9D] = W("STA", AddrMode.ABX, this::STA, true);
        lookupTable[0x9E] = W("SHX", AddrMode.ABY, this::SHX, false);
        lookupTable[0x9E].highByteCorruption = true;
        lookupTable[0x9E].plainWriteOp = this::SHX_plain;
        lookupTable[0x9F] = W("AHX", AddrMode.ABY, this::AHX, false);
        lookupTable[0x9F].highByteCorruption = true;
        lookupTable[0x9F].plainWriteOp = this::AHX_plain;

        // 0xA0 - 0xAF
        lookupTable[0xA0] = R("LDY", AddrMode.IMM, this::LDY, true);
        lookupTable[0xA1] = R("LDA", AddrMode.IZX, this::LDA, true);
        lookupTable[0xA2] = R("LDX", AddrMode.IMM, this::LDX, true);
        lookupTable[0xA3] = R("LAX", AddrMode.IZX, this::LAX, false);
        lookupTable[0xA4] = R("LDY", AddrMode.ZP0, this::LDY, true);
        lookupTable[0xA5] = R("LDA", AddrMode.ZP0, this::LDA, true);
        lookupTable[0xA6] = R("LDX", AddrMode.ZP0, this::LDX, true);
        lookupTable[0xA7] = R("LAX", AddrMode.ZP0, this::LAX, false);
        lookupTable[0xA8] = O("TAY", AddrMode.IMP, implied(() -> { y = a; setFlag(Z, y == 0); setFlag(N, (y & 0x80) != 0); }), true);
        lookupTable[0xA9] = R("LDA", AddrMode.IMM, this::LDA, true);
        lookupTable[0xAA] = O("TAX", AddrMode.IMP, implied(() -> { x = a; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }), true);
        lookupTable[0xAB] = R("LAX", AddrMode.IMM, this::LAX, false);
        lookupTable[0xAC] = R("LDY", AddrMode.ABS, this::LDY, true);
        lookupTable[0xAD] = R("LDA", AddrMode.ABS, this::LDA, true);
        lookupTable[0xAE] = R("LDX", AddrMode.ABS, this::LDX, true);
        lookupTable[0xAF] = R("LAX", AddrMode.ABS, this::LAX, false);

        // 0xB0 - 0xBF
        lookupTable[0xB0] = O("BCS", AddrMode.REL, branch(this::getFlagC), true);
        lookupTable[0xB1] = R("LDA", AddrMode.IZY, this::LDA, true);
        lookupTable[0xB2] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0xB3] = R("LAX", AddrMode.IZY, this::LAX, false);
        lookupTable[0xB4] = R("LDY", AddrMode.ZPX, this::LDY, true);
        lookupTable[0xB5] = R("LDA", AddrMode.ZPX, this::LDA, true);
        lookupTable[0xB6] = R("LDX", AddrMode.ZPY, this::LDX, true);
        lookupTable[0xB7] = R("LAX", AddrMode.ZPY, this::LAX, false);
        lookupTable[0xB8] = O("CLV", AddrMode.IMP, implied(() -> setFlag(V, false)), true);
        lookupTable[0xB9] = R("LDA", AddrMode.ABY, this::LDA, true);
        lookupTable[0xBA] = O("TSX", AddrMode.IMP, implied(() -> { x = sp; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }), true);
        lookupTable[0xBB] = R("LAS", AddrMode.ABY, this::LAS, false);
        lookupTable[0xBC] = R("LDY", AddrMode.ABX, this::LDY, true);
        lookupTable[0xBD] = R("LDA", AddrMode.ABX, this::LDA, true);
        lookupTable[0xBE] = R("LDX", AddrMode.ABY, this::LDX, true);
        lookupTable[0xBF] = R("LAX", AddrMode.ABY, this::LAX, false);

        // 0xC0 - 0xCF
        lookupTable[0xC0] = R("CPY", AddrMode.IMM, this::CPY, true);
        lookupTable[0xC1] = R("CMP", AddrMode.IZX, this::CMP, true);
        lookupTable[0xC2] = R("NOP", AddrMode.IMM, this::NOPread, false);
        lookupTable[0xC3] = M("DCP", AddrMode.IZX, this::DCP, false);
        lookupTable[0xC4] = R("CPY", AddrMode.ZP0, this::CPY, true);
        lookupTable[0xC5] = R("CMP", AddrMode.ZP0, this::CMP, true);
        lookupTable[0xC6] = M("DEC", AddrMode.ZP0, this::DEC, true);
        lookupTable[0xC7] = M("DCP", AddrMode.ZP0, this::DCP, false);
        lookupTable[0xC8] = O("INY", AddrMode.IMP, implied(() -> { y = (y + 1) & 0xFF; setFlag(Z, y == 0); setFlag(N, (y & 0x80) != 0); }), true);
        lookupTable[0xC9] = R("CMP", AddrMode.IMM, this::CMP, true);
        lookupTable[0xCA] = O("DEX", AddrMode.IMP, implied(() -> { x = (x - 1) & 0xFF; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }), true);
        lookupTable[0xCB] = R("AXS", AddrMode.IMM, this::AXS, false);
        lookupTable[0xCC] = R("CPY", AddrMode.ABS, this::CPY, true);
        lookupTable[0xCD] = R("CMP", AddrMode.ABS, this::CMP, true);
        lookupTable[0xCE] = M("DEC", AddrMode.ABS, this::DEC, true);
        lookupTable[0xCF] = M("DCP", AddrMode.ABS, this::DCP, false);

        // 0xD0 - 0xDF
        lookupTable[0xD0] = O("BNE", AddrMode.REL, branch(() -> !getFlag(Z)), true);
        lookupTable[0xD1] = R("CMP", AddrMode.IZY, this::CMP, true);
        lookupTable[0xD2] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0xD3] = M("DCP", AddrMode.IZY, this::DCP, false);
        lookupTable[0xD4] = R("NOP", AddrMode.ZPX, this::NOPread, false);
        lookupTable[0xD5] = R("CMP", AddrMode.ZPX, this::CMP, true);
        lookupTable[0xD6] = M("DEC", AddrMode.ZPX, this::DEC, true);
        lookupTable[0xD7] = M("DCP", AddrMode.ZPX, this::DCP, false);
        lookupTable[0xD8] = O("CLD", AddrMode.IMP, implied(() -> setFlag(D, false)), true);
        lookupTable[0xD9] = R("CMP", AddrMode.ABY, this::CMP, true);
        lookupTable[0xDA] = O("NOP", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0xDB] = M("DCP", AddrMode.ABY, this::DCP, false);
        lookupTable[0xDC] = R("NOP", AddrMode.ABX, this::NOPread, false);
        lookupTable[0xDD] = R("CMP", AddrMode.ABX, this::CMP, true);
        lookupTable[0xDE] = M("DEC", AddrMode.ABX, this::DEC, true);
        lookupTable[0xDF] = M("DCP", AddrMode.ABX, this::DCP, false);

        // 0xE0 - 0xEF
        lookupTable[0xE0] = R("CPX", AddrMode.IMM, this::CPX, true);
        lookupTable[0xE1] = R("SBC", AddrMode.IZX, this::SBC, true);
        lookupTable[0xE2] = R("NOP", AddrMode.IMM, this::NOPread, false);
        lookupTable[0xE3] = M("ISB", AddrMode.IZX, this::ISB, false);
        lookupTable[0xE4] = R("CPX", AddrMode.ZP0, this::CPX, true);
        lookupTable[0xE5] = R("SBC", AddrMode.ZP0, this::SBC, true);
        lookupTable[0xE6] = M("INC", AddrMode.ZP0, this::INC, true);
        lookupTable[0xE7] = M("ISB", AddrMode.ZP0, this::ISB, false);
        lookupTable[0xE8] = O("INX", AddrMode.IMP, implied(() -> { x = (x + 1) & 0xFF; setFlag(Z, x == 0); setFlag(N, (x & 0x80) != 0); }), true);
        lookupTable[0xE9] = R("SBC", AddrMode.IMM, this::SBC, true);
        lookupTable[0xEA] = O("NOP", AddrMode.IMP, implied(() -> {}), true);
        lookupTable[0xEB] = R("SBC", AddrMode.IMM, this::SBC, false);
        lookupTable[0xEC] = R("CPX", AddrMode.ABS, this::CPX, true);
        lookupTable[0xED] = R("SBC", AddrMode.ABS, this::SBC, true);
        lookupTable[0xEE] = M("INC", AddrMode.ABS, this::INC, true);
        lookupTable[0xEF] = M("ISB", AddrMode.ABS, this::ISB, false);

        // 0xF0 - 0xFF
        lookupTable[0xF0] = O("BEQ", AddrMode.REL, branch(this::getFlagZ), true);
        lookupTable[0xF1] = R("SBC", AddrMode.IZY, this::SBC, true);
        lookupTable[0xF2] = O("KIL", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0xF3] = M("ISB", AddrMode.IZY, this::ISB, false);
        lookupTable[0xF4] = R("NOP", AddrMode.ZPX, this::NOPread, false);
        lookupTable[0xF5] = R("SBC", AddrMode.ZPX, this::SBC, true);
        lookupTable[0xF6] = M("INC", AddrMode.ZPX, this::INC, true);
        lookupTable[0xF7] = M("ISB", AddrMode.ZPX, this::ISB, false);
        lookupTable[0xF8] = O("SED", AddrMode.IMP, implied(() -> setFlag(D, true)), true);
        lookupTable[0xF9] = R("SBC", AddrMode.ABY, this::SBC, true);
        lookupTable[0xFA] = O("NOP", AddrMode.IMP, implied(() -> {}), false);
        lookupTable[0xFB] = M("ISB", AddrMode.ABY, this::ISB, false);
        lookupTable[0xFC] = R("NOP", AddrMode.ABX, this::NOPread, false);
        lookupTable[0xFD] = R("SBC", AddrMode.ABX, this::SBC, true);
        lookupTable[0xFE] = M("INC", AddrMode.ABX, this::INC, true);
        lookupTable[0xFF] = M("ISB", AddrMode.ABX, this::ISB, false);
    }

    //Named predicates so branch() call sites read cleanly.
    private boolean getFlagC() { return getFlag(C); }
    private boolean getFlagZ() { return getFlag(Z); }
    private boolean getFlagV() { return getFlag(V); }
}
