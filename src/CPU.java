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

    //Real hardware's DMC "put"/"get" cycle alternation is driven by a clock divider
    //that free-runs from power-on and is NEVER resynced by the RESET line - only a
    //true power cycle restarts it. totalCycles, by contrast, gets zeroed by reset()
    //(a soft CPU reset - see Bus.reset(), used by both "load ROM" and the "Reset" menu
    //item), which does NOT also reset APU (its apuCycleToggle free-runs the same way
    //real hardware's divider does). If maybeStartDmcHalt() used totalCycles&1 for the
    //2-vs-3 halt-cycle alignment decision, a soft reset partway through a run would
    //desync it from the APU's own parity by however many cycles had elapsed before the
    //reset - producing exactly the "sometimes crashes, sometimes fails error A"
    //nondeterminism seen in AccuracyCoin's Interrupt Flag Latency tests 9/A/B (whose
    //DMC DMA halt lands mid-branch-instruction and must fall on the correct alignment
    //to avoid an extra/missing poll). This counter mirrors totalCycles's increments
    //exactly but is deliberately never touched by reset(), so it stays correctly
    //synced with the APU's free-running parity across any number of soft resets.
    private long dmcParityCycles = 0;

    //Floating CPU data bus: latches the last byte value that actually appeared on the
    //bus, from either a read or a write. Reads from unmapped address ranges return
    //this latched value instead of a fixed 0, matching real hardware's "open bus".
    public int dataBus = 0x00;
    public static boolean debugDma = false; //SCRATCH DEBUG - remove before finishing

    //Transient per-instruction state, valid once the effective address has been resolved
    private int addrAbs = 0x0000;
    private int fetched = 0x00;
    //Set whenever ABX/ABY/IZY indexing crosses a page boundary - SHA/SHX/SHY/TAS read
    //this to decide whether their write lands on addrAbs or a high-byte-corrupted address.
    private boolean pageCrossed = false;

    //Queue of remaining bus/internal cycles for the instruction currently in flight.
    //Each entry represents exactly one real clock cycle. Cycles enqueue their own
    //follow-up cycle(s) at runtime (a self-extending microcode sequencer), which is
    //how runtime-only outcomes like page-crossing and branch-taken are handled without
    //any lookahead.
    private final Deque<Runnable> microOps = new ArrayDeque<>();

    private boolean nmiPending = false;
    private boolean irqPending = false;

    //Real 6502 hardware polls interrupt lines DURING an instruction - before its final
    //cycle, using whatever flag/pending state existed at that point - not "after the
    //instruction is completely done". That distinction only matters for instructions
    //that change the I flag or the pending-interrupt state on their own final cycle
    //(CLI/SEI/PLP/RTI, and BRK/IRQ/NMI's own push sequence), but those are exactly the
    //cases AccuracyCoin's interrupt-latency tests exercise. pollInterrupts() snapshots
    //the decision; the top of step() acts on that latched snapshot rather than
    //re-checking nmiPending/irqPending fresh once the instruction has fully finished.
    private boolean latchedNmiPending = false;
    private boolean latchedIrqPending = false;
    private void pollInterrupts() {
        latchedNmiPending = nmiPending;
        latchedIrqPending = irqPending && !getFlag(I);
    }

    //Branch-only variant for the cycle-4 poll (the one that only exists after a page
    //crossing): ORs the fresh sample into whatever the cycle-2 poll already latched,
    //instead of overwriting it. Real hardware's branch logic is the one documented
    //exception where an interrupt detected on the earlier poll stays sticky even if
    //the line was already cleared by the time of the later poll - see AccuracyCoin's
    //Interrupt Flag Latency error code E. This must NOT be used for other multi-poll
    //sequences (e.g. the BRK/IRQ/NMI push chain), where hijacking is decided by
    //whichever poll happened last, not by OR-ing every poll in the sequence together
    //(error code A/error code 2 territory).
    private void pollInterruptsBranchSecond() {
        boolean prevNmi = latchedNmiPending;
        boolean prevIrq = latchedIrqPending;
        pollInterrupts();
        latchedNmiPending |= prevNmi;
        latchedIrqPending |= prevIrq;
    }

    //Cycles the CPU is stalled for (e.g. OAM DMA). Consumed one at a time by step(),
    //ahead of any interrupt polling or instruction dispatch, matching real hardware's
    //DMA cycle-stealing.
    private int stallCycles = 0;
    public void stall(int cycles) { stallCycles += cycles; }

    //Set for the duration of an OAM DMA's stall only (never for a DMC DMA halt, which
    //stalls mid-instruction and must NOT trigger an early re-poll - see the comment on
    //oamDmaStall's use in runStallCycle()).
    private boolean oamDmaStall = false;
    public void stallForOamDma(int cycles) {
        stallCycles += cycles;
        oamDmaStall = true;
    }

    private APU apu;
    public void connectAPU(APU apu) { this.apu = apu; }

    //DMC DMA: unlike OAM DMA (which only ever starts between instructions, right after
    //the $4014 write that triggers it), the DMC's sample timer can expire in the middle
    //of whatever instruction is currently running - real hardware halts the CPU (pulls
    //RDY low) for a few cycles wherever it happens to be, then resumes exactly where it
    //left off. Piggybacking on stallCycles gets that "pause mid-instruction" behavior
    //for free, since microOps just sits untouched while stallCycles counts down.
    //
    //RDY can only go low following a cycle that wasn't a write (a write can't be
    //interrupted), so a request just sets a flag; the halt itself starts on the next
    //cycle boundary where the CPU is free to stop. Once halted, the address bus is
    //frozen at whatever it last held - so the halt/alignment cycles are dummy reads of
    //that same address, not of wherever the interrupted instruction would've read next.
    private boolean lastCycleWasWrite = false;
    private int lastBusAddress = 0;

    //True whenever the ONE microOp currently sitting in the queue (self-extending
    //sequencer, so there's never more than one pending at a time) will itself perform
    //a write when it runs. Set by every write-producing enqueue site (enqueueFinal's
    //WRITE/RMW cases, PHA/PHP, JSR/BRK/interrupt push cycles) right after they call
    //microOps.add(); reset to false at the top of every stepInner() call so it always
    //reflects only the cycle about to run. maybeStartDmcHalt() uses this (NOT
    //lastCycleWasWrite, which reflects the cycle that just finished) to decide whether
    //RDY is allowed to go low for the upcoming cycle - a write cycle always completes
    //uninterrupted, so a DMC DMA request pending right before one must wait 1 more
    //cycle. See AccuracyCoin's "DMA + $2007 Write" test for a case that specifically
    //exercises this deferral.
    private boolean pendingOpIsWrite = false;

    private boolean dmcDmaPending = false;
    private int dmcDmaAddress = 0;
    //Whether the pending request is a "reload" DMA (scheduled to halt on a put cycle)
    //as opposed to a "load" DMA (scheduled to halt on a get cycle) - see
    //APU.Dmc.requestDma()'s comment. maybeStartDmcHalt() needs this to know which of
    //the two halt-cycle-count parities is the "ideal, no extra delay" one.
    private boolean dmcDmaIsReload = false;
    //SCRATCH DEBUG - remove before finishing: totalCycles/dmcParityCycles at the moment
    //requestDmcDma() was last called, for the scheduling-to-halt gap logging below.
    private long scratchReqTotalCycles = 0;
    private long scratchReqParityCycles = 0;

    //requestDmcDma() can be called mid-tick, from APU.clock() - which NESEmulator's
    //main loop always calls BEFORE this same tick's cpu.step(). A request that shows
    //up this way must NOT be able to halt THIS tick (the one whose op hasn't run
    //yet): real hardware's RDY line is sampled at the start of each CPU cycle using
    //whatever was asserted as of the END of the previous cycle, so the earliest a
    //request can actually halt anything is the cycle AFTER the one it became known
    //in. maybeStartDmcHalt() reads dmcDmaPendingVisible (updated once per step(),
    //after that cycle's own op has already run) instead of dmcDmaPending directly, so
    //a same-tick request is deferred exactly one cycle before it's eligible to halt.
    //Without this, AccuracyCoin's Interrupt Flag Latency test A (a DMC DMA landing
    //mid-branch-instruction) sees the IRQ one cycle too early - the halt swallows
    //part of the branch's own operand-read cycle instead of only the cycle after it.
    private boolean dmcDmaPendingVisible = false;

    private boolean dmcHalting = false;
    private int dmcHaltCyclesRemaining = 0;
    //The address the halt's dummy reads target - see maybeStartDmcHalt()'s assignment.
    private int dmcDummyReadAddr = 0;

    //---------------------------------------------------------------------
    // OAM DMA ($4014) - cycle-stepped state machine
    //---------------------------------------------------------------------
    //Unlike the old "do the whole 256-byte copy synchronously then flat-stall"
    //approach, OAM DMA needs to be steppable one bus cycle at a time so a DMC DMA
    //request can interleave with it mid-transfer - see the nesdev wiki's "DMC DMA
    //during OAM DMA" page and the extensive comment in AccuracyCoin.asm right
    //before TEST_DMCDMAPlusOAMDMA for the exact priority rules this implements:
    //DMC DMA takes priority on "get" (read) cycles, OAM DMA takes priority on
    //"put" (write) cycles, and OAM DMA needs one dummy "realignment" cycle after a
    //DMC get steals one of its own get cycles.
    private boolean oamDmaActive = false;
    private int oamDmaPage = 0;
    private int oamByteIndex = 0;      //0-255: which OAM byte is currently in flight
    private boolean oamGetDone = false; //true once the current byte's "get" has happened, awaiting its "put"
    private int oamLatch = 0;
    //Dummy cycles to burn before OAM DMA's very first real "get". Real hardware
    //always needs at least 1 (the well-known "513 cycles, not 512" quirk); a
    //second is needed if the transfer starts on a CPU cycle whose phase doesn't
    //already line up with a "get" slot (the "514 cycles" case) - see startOamDma().
    private int oamAlignRemaining = 0;
    //Set for exactly one cycle immediately after a DMC DMA get steals one of OAM
    //DMA's get cycles: the put cycle that would have written the stolen byte has
    //nothing to write yet, so it performs a dummy read of the frozen CPU address
    //instead (real hardware: "it just reads from the current 6502 address bus").
    private boolean oamRealignPending = false;
    //The CPU's own address bus, frozen for the duration of OAM DMA (and any DMC
    //DMA riding along with it) - same concept as dmcDummyReadAddr for the
    //standalone-halt case, but OAM DMA always starts between instructions (right
    //after the $4014 write), so it's simply pc at that point.
    private int oamFrozenAddr = 0;
    //True once a DMC DMA's halt/alignment cycles have fully elapsed while OAM DMA
    //was running, and its real "get" cycle is now due - it steals the next cycle
    //that would otherwise have been one of OAM DMA's own "get" cycles (never a
    //"put": a write can't be interrupted, so OAM's put just proceeds normally and
    //the steal is retried the following cycle).
    private boolean dmcGetPending = false;

    //Set for exactly one cycle: whether the microOp about to run is the first "real"
    //(non-stall) cycle following a completed DMC DMA halt. SHA/SHX/SHY/TAS's dummy
    //read immediately before their write cycle checks this to detect the "RDY line
    //goes low 2 cycles before the write cycle" case (see AccuracyCoin's Unofficial
    //Instructions error code 7): on real hardware, halting there drops the write's
    //high-byte-AND term entirely rather than just delaying the write.
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

    //Implicit DMA Abort ("1-cycle DMA"): when a non-looping 1-byte DMC sample plays
    //its last byte out (bufferHasData goes false, bytesRemaining hits 0) at the exact
    //moment a fresh $4015 re-enable's 3-cycle reload delay (APU.Dmc.enableDelay)
    //finishes and finds the buffer still empty, real hardware doesn't run the usual
    //2-3 cycle halt + 1 get sequence. Because the sample-ended condition and the
    //fresh reload are colliding on the very same "would the memory reader want a
    //byte" check inside the DMC's own circuitry, the halt/alignment cycles - whose
    //entire purpose is to synchronize the CPU's RDY sampling with a freshly-asserted
    //request - are skipped: the request and the CPU's readiness are already in sync,
    //so all that's left is the single "get" bus cycle itself. See AccuracyCoin's
    //"Implicit DMA Abort" test (TEST_ImplicitDMAAbort, its own comment: "This results
    //in a 1-cycle DMA") and the nesdev wiki's APU DMC page.
    //
    //Unlike requestDmcDma()/maybeStartDmcHalt() (which defer indefinitely across
    //write cycles - a write always completes uninterrupted, but the request just
    //waits for the next non-write cycle), this variant does NOT defer: if the very
    //next cycle would be a write, the 1-cycle DMA is simply dropped and never
    //happens at all. This matches AccuracyCoin's own comment ("Unlike regular DMAs,
    //that just get delayed by write cycles, this 1-cycle DMA will NOT occur if it
    //would happen on a write cycle") and is exercised by TEST_ImplicitDMAAbort_Loop2,
    //which deliberately lands this case on a JSR's PC-push write cycle.
    private boolean dmc1CyclePending = false;
    private int dmc1CycleAddress = 0;
    //Same same-tick deferral concern as dmcDmaPendingVisible - see its declaration.
    private boolean dmc1CyclePendingVisible = false;
    private boolean dmc1CycleGetPending = false;

    //Explicit DMA Abort: set by cancelDmcDma() when a DMC DMA request is already
    //scheduled (dmcDmaPending/dmcDmaPendingVisible) but hasn't started halting yet.
    //See cancelDmcDma()'s comment for why "already halting" can't actually happen here,
    //and maybeStartDmcHalt()'s handling of this flag for the two real-hardware outcomes:
    //the halt still occurs but is aborted after a single cycle, UNLESS the halt itself
    //is delayed by a write cycle, in which case the whole thing is dropped with no halt
    //at all (see DMA Info.txt's "Explicit-stop aborted DMA" example).
    private boolean dmcDmaAbortPending = false;
    //Set when the halt that just started (see maybeStartDmcHalt()) is the aborted,
    //1-cycle-only kind: no dummy/alignment cycles, no "get" fetch - just the halt
    //cycle itself, then the CPU resumes immediately.
    private boolean dmcHaltAborted = false;

    public void requestDmcDma1Cycle(int address) {
        dmc1CyclePending = true;
        dmc1CycleAddress = address;
        if (debugDma) System.err.println("[t="+totalCycles+"] requestDmcDma1Cycle addr="+Integer.toHexString(address));
    }

    //TAS Maker greenzone checkpoint support. Only ever captured when microOps is
    //empty (see NESEmulator's checkpoint-capture loop, which drains to an
    //instruction boundary before calling snapshot()) - that's what makes a plain
    //field copy sufficient here despite microOps itself being a queue of
    //non-serializable closures: at a boundary there's nothing in flight to lose,
    //so State simply doesn't carry microOps at all, and restore() just clears it
    //(defensively - it's always already empty when this is called).
    public static final class State {
        int a, x, y, sp, pc, status;
        long totalCycles;
        long dmcParityCycles;
        int dataBus;
        int addrAbs, fetched;
        boolean pageCrossed;
        boolean nmiPending, irqPending;
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

    //Precondition: microOps.isEmpty() - see State's comment.
    public State snapshot() {
        State s = new State();
        s.a = a; s.x = x; s.y = y; s.sp = sp; s.pc = pc; s.status = status;
        s.totalCycles = totalCycles;
        s.dmcParityCycles = dmcParityCycles;
        s.dataBus = dataBus;
        s.addrAbs = addrAbs; s.fetched = fetched;
        s.pageCrossed = pageCrossed;
        s.nmiPending = nmiPending; s.irqPending = irqPending;
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
        addrAbs = s.addrAbs; fetched = s.fetched;
        pageCrossed = s.pageCrossed;
        nmiPending = s.nmiPending; irqPending = s.irqPending;
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

    //True when there's no in-flight instruction (no pending microOps closures) to
    //disturb, so it's safe to snapshot() right now. stallCycles (OAM DMA / DMC halt)
    //is plain data, not a closure, so a stall in progress does NOT block snapshotting -
    //requiring it to also be 0 would mean waiting out up to ~513 OAM DMA cycles instead
    //of the few needed to drain microOps, which is exactly what checkpoint capture is
    //trying to avoid.
    public boolean isAtInstructionBoundary() {
        return microOps.isEmpty();
    }

    //Mirrors maybeStartDmcHalt()'s shape but for the 1-cycle DMA variant: no halt,
    //no alignment - either the very next cycle is free (steal it, do the get, done)
    //or it's a write (drop the request entirely, no retry).
    private void maybeStart1CycleDma() {
        if (!dmc1CyclePendingVisible) return;
        dmc1CyclePendingVisible = false;
        dmc1CyclePending = false;
        if (pendingOpIsWrite) {
            //Dropped silently - see requestDmcDma1Cycle()'s comment. Must tell the
            //APU side too, so it clears its own dmaPending latch (see
            //APU.dmcDma1CycleDropped()) - otherwise the DMC channel deadlocks,
            //believing a DMA is forever in flight and refusing to ever request
            //another one.
            if (apu != null) apu.dmcDma1CycleDropped();
            return;
        }
        dmc1CycleGetPending = true;
        stallCycles += 1;
    }

    //Explicit DMA Abort: called from APU.Dmc.writeEnableFlag(false) whenever the DMC
    //channel gets disabled, regardless of whether a DMA was actually requested (a no-op
    //otherwise). Per DMA Info.txt's "Bugs" section: when sample playback stops (here,
    //always explicitly - via this very $4015 write) during the APU cycle before a
    //reload DMA would schedule to halt, the DMA still starts (it was already
    //requested/scheduled - the disabling write can't un-assert that), but is aborted
    //after a single cycle instead of running its normal 3/4-cycle halt+dummy+align+get
    //sequence. If the halt itself is delayed by a write cycle, the aborted DMA doesn't
    //happen at all.
    //
    //Note dmcHalting can never already be true here: cancelDmcDma() is only ever called
    //synchronously from a normal (non-halted) CPU write instruction executing $4015 -
    //while the CPU is halted for a DMC DMA, no CPU-issued write can occur at all. So the
    //only state this needs to touch is the still-pending (not yet halting) request.
    //
    //An earlier version of this method was a total no-op, reasoning (incorrectly, per
    //AccuracyCoin's "Explicit DMA Abort" answer key) that the request/halt/get always ran
    //to completion unaffected by disabling mid-flight.
    public void cancelDmcDma() {
        if (debugDma) System.err.println("[t="+totalCycles+"] cancelDmcDma dmcDmaPending="+dmcDmaPending+" dmcDmaPendingVisible="+dmcDmaPendingVisible+" dmcHalting="+dmcHalting);
        if (dmcDmaPending || dmcDmaPendingVisible) {
            dmcDmaAbortPending = true;
        }
    }

    //Called from Bus.cpuWrite's $4014 handler. Starts the OAM DMA state machine;
    //the actual byte-by-byte transfer happens one cycle at a time via
    //runOamDmaCycle(), driven from stepInner() on subsequent step() calls.
    public void startOamDma(int page) {
        oamDmaActive = true;
        oamDmaPage = page;
        oamByteIndex = 0;
        oamGetDone = false;
        oamRealignPending = false;
        oamFrozenAddr = pc;
        //totalCycles hasn't been incremented for the cycle this write is happening
        //on yet (that happens at the bottom of stepInner(), after this write's
        //microOp closure returns) - matching the parity the old flat-stall formula
        //(cpu.totalCycles % 2 == 0 ? 513 : 514) used.
        oamAlignRemaining = (totalCycles % 2 == 0) ? 1 : 2;
    }

    //Runs exactly one OAM DMA cycle, including any DMC DMA halt/get riding along
    //with it. See the nesdev wiki's "DMC DMA during OAM DMA" page for the
    //authoritative timing diagrams this implements.
    private void runOamDmaCycle() {
        //Unlike the standalone case (maybeStartDmcHalt(), which must avoid
        //interrupting a write-producing microOp), it's always safe to let a
        //pending DMC DMA request start halting here: while the halt is running,
        //OAM DMA just keeps going untouched (see below) - nothing is actually
        //interrupted until the halt finishes and steals a cycle.
        if (!dmcHalting && !dmcGetPending && dmcDmaPendingVisible) {
            dmcDmaPendingVisible = false;
            dmcDmaPending = false;
            dmcHalting = true;
            dmcHaltCyclesRemaining = ((dmcParityCycles & 1) == 0) ? 2 : 3;
        }

        if (dmcHalting) {
            //"if the DMC DMA is halted, the OAM DMA keeps going" - the halt's
            //dummy reads are satisfied for free by whatever OAM DMA is already
            //doing this cycle (its own get/put, or its own alignment dummy read),
            //so it doesn't perform any separate read of its own.
            dmcHaltCyclesRemaining--;
            boolean haltJustFinished = dmcHaltCyclesRemaining <= 0;
            runOamPhaseCycle();
            //If runOamPhaseCycle() just finished OAM DMA (its 256th byte's put), it
            //already handed dmcHalting/dmcHaltCyclesRemaining off to the standalone
            //stall machinery via finishOamDma() - don't clobber that handoff by also
            //flipping to dmcGetPending here (oamDmaActive is now false, and nothing
            //in the standalone path ever checks dmcGetPending, so doing so would
            //permanently strand the DMC DMA mid-flight).
            if (haltJustFinished && oamDmaActive) {
                dmcHalting = false;
                dmcGetPending = true;
            }
        } else if (dmcGetPending && oamAlignRemaining == 0 && !oamRealignPending && !oamGetDone) {
            //The halt has finished and the DMC's real "get" is due, and this cycle
            //would otherwise have been one of OAM DMA's own "get" cycles - steal it.
            dmcGetPending = false;
            int value = dmaRead(dmcDmaAddress, oamFrozenAddr);
            if (apu != null) apu.dmcDmaCompleted(value);
            oamRealignPending = true;
        } else {
            //Either nothing DMC-related is going on, or a steal is due but this
            //cycle is one of OAM's "put" cycles (or its own alignment) - OAM DMA
            //takes priority here, and the steal (if any) is retried next cycle.
            runOamPhaseCycle();
        }

        totalCycles++;
        dmcParityCycles++;
    }

    //Advances OAM DMA by exactly one of its own get/put/alignment cycles.
    private void runOamPhaseCycle() {
        if (oamAlignRemaining > 0) {
            oamAlignRemaining--;
            read(oamFrozenAddr); //dummy: OAM DMA hasn't started fetching bytes yet
            return;
        }
        if (oamRealignPending) {
            oamRealignPending = false;
            //This would otherwise be a "put" cycle, but OAM DMA has no freshly
            //fetched byte to write - its get was just stolen by a DMC DMA. Real
            //hardware performs a plain dummy read of the frozen address bus here
            //instead of the $2004 write; the byte's get is retried next cycle.
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

    //OAM DMA's 256 bytes have all been copied. Hands any DMC DMA halt/get still in
    //flight off to the ordinary (non-OAM) stall machinery, so it finishes exactly
    //as it would have if it had started while the CPU was merely stalled rather
    //than mid-OAM-DMA - see AccuracyCoin's "DMC DMA on last/second-to-last OAM DMA
    //put" cases (nesdev wiki), which cost 3 and 1 extra cycles respectively purely
    //as a byproduct of this handoff.
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
            //Nothing left pending - CPU resumes fetching the next opcode
            //immediately, same as the tail of runStallCycle() for a plain OAM DMA
            //with no DMC DMA overlap at all.
            pollInterrupts();
        }
    }

    //Called once per cycle, right after that cycle finished, to decide whether a
    //pending DMC DMA request can start halting the CPU now. Uses dmcDmaPendingVisible,
    //NOT dmcDmaPending directly - see dmcDmaPendingVisible's declaration for why.
    private void maybeStartDmcHalt() {
        if (!dmcDmaPendingVisible) return;
        if (pendingOpIsWrite) {
            //Explicit DMA Abort: unlike an ordinary DMC DMA request (which just waits
            //indefinitely for the next non-write cycle), an aborted request that's still
            //blocked by a write cycle here doesn't get to try again next cycle - it's
            //dropped entirely, with no halt at all. See cancelDmcDma()'s comment and
            //DMA Info.txt's "Explicit-stop aborted DMA" example.
            if (dmcDmaAbortPending) {
                dmcDmaPendingVisible = false;
                dmcDmaPending = false;
                dmcDmaAbortPending = false;
                if (apu != null) apu.dmcDmaAborted();
            }
            return;
        }
        dmcDmaPendingVisible = false;
        dmcDmaPending = false;
        dmcHalting = true;
        dmaHaltJustEnded = true;
        //Real hardware's address bus is combinational: the address for the CPU's next
        //(not-yet-executed) read cycle is already being driven onto the bus before RDY
        //is even sampled, so a halt starting here freezes the bus at THAT pending
        //target - not at the address of whatever the previous (already-completed)
        //cycle read. The dummy reads during the halt are therefore re-reads of the
        //pending cycle's own target address, which is exactly why they're able to
        //(for example) clear $2002's VBlank flag, clock $4016, or increment the PPU's
        //'v' register: AccuracyCoin's DMA+$2002/$2007/$4015/$4016 tests specifically
        //sync the DMA to land right before such a read.
        //microOps empty means the next thing is an opcode fetch (address = pc);
        //otherwise a READ/WRITE/RMW instruction's effective address has already been
        //resolved into addrAbs by the addressing-mode cycle that just ran (each
        //beginRWM() case computes addrAbs and enqueues the final read/write/rmw cycle
        //in the same CPU cycle), so addrAbs is exactly the pending cycle's target.
        dmcDummyReadAddr = microOps.isEmpty() ? pc : addrAbs;
        if (dmcDmaAbortPending) {
            //Explicit DMA Abort: the halt happens (it can't be un-scheduled at this
            //point), but instead of the usual dummy/alignment cycles plus a final get,
            //it's aborted after this single halt cycle - see cancelDmcDma()'s comment.
            dmcDmaAbortPending = false;
            dmcHaltAborted = true;
            dmcHaltCyclesRemaining = 0;
            stallCycles += 1;
        } else {
            //2 halt cycles always; a 3rd "alignment" cycle is needed unless the halt
            //happens to start already aligned to what would've been a natural read cycle.
            //Uses dmcParityCycles, NOT totalCycles - see its declaration for why (soft
            //reset desync).
            //
            //Load and reload DMAs are scheduled to halt on OPPOSITE cycle types (get vs
            //put - see DMA Info.txt's DMC DMA section and APU.Dmc.requestDma()'s
            //comment), so which dmcParityCycles parity counts as "landed on the ideal,
            //no-extra-alignment cycle" flips between them. Using a single fixed parity
            //mapping for both (as an earlier version of this code did) gives reload DMAs
            //the wrong halt-cycle count whenever overall CPU/APU cycle parity differs
            //from whatever a load DMA happened to use - exactly the "alternating with
            //cycle parity" bug AccuracyCoin's "Explicit/Implicit DMA Abort" tests
            //exposed (their 16-phase sweep hits both parities on purpose).
            boolean idealParity = (dmcParityCycles & 1) == 0;
            boolean landedOnIdeal = idealParity == dmcDmaIsReload;
            dmcHaltCyclesRemaining = landedOnIdeal ? 2 : 3;
            stallCycles += dmcHaltCyclesRemaining + 1; //+1 for the final "get" cycle itself
        }
        if (debugDma) System.err.println("[HALT t="+totalCycles+" parity="+dmcParityCycles+"] maybeStartDmcHalt gap="+(totalCycles-scratchReqTotalCycles)+" parityGap="+(dmcParityCycles-scratchReqParityCycles)+" isReload="+dmcDmaIsReload+" haltCycles="+dmcHaltCyclesRemaining+" aborted="+dmcHaltAborted);
        if (debugDma) System.err.println("[t="+totalCycles+"] maybeStartDmcHalt haltCycles="+dmcHaltCyclesRemaining+" aborted="+dmcHaltAborted+" dummyAddr="+Integer.toHexString(dmcDummyReadAddr));
    }

    //Functional interfaces for the three "shapes" of 6502 instruction semantics.
    //OTHER-type opcodes (branches, stack ops, jumps, interrupts, implied register ops)
    //build their own bespoke cycle chains instead of using one of these.
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
        WriteOp plainWriteOp; //SHA/SHX/SHY/TAS only: the value with the high-byte-AND term left out -
                               //what actually gets written if a DMC DMA halts the CPU 2 cycles before
                               //the write cycle (see CPU.java's cycleFollowsDmcHalt handling)
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

    //Non-mutating read, for the trace logger's disassembly preview only. Unlike read(),
    //this must NOT touch dataBus/lastBusAddress or trigger register side effects (e.g.
    //clearing $2002's VBlank flag, shifting the $4016/$4017 controller registers, or
    //clearing $4015's status) - simply having tracing enabled must not change what the
    //game actually does.
    private int peek(int addr) {
        return bus.debugRead(addr) & 0xFF;
    }

    //Bus helpers
    public byte read(int addr) {
        byte v = bus.cpuRead(addr);
        //$4015 (APU status) is wired so its read only updates the APU's own internal
        //latch, not the CPU's external data bus - real hardware quirk, not a general
        //open-bus exception.
        if (addr != 0x4015) dataBus = v & 0xFF;
        lastBusAddress = addr;
        return v;
    }
    //Runs a DMA "get" read (OAM DMA's own byte fetch, or DMC DMA's sample byte fetch).
    //Unlike a normal CPU read, a DMA's actual target address and the 6502 core's own
    //address bus are two different things: the core is halted during DMA, so its
    //address bus stays frozen wherever it last was (cpuAddressBus - oamFrozenAddr for
    //OAM DMA, dmcDummyReadAddr for a standalone DMC DMA halt), while the DMA controller
    //drives the real target address (dmaAddr) onto the shared physical bus. The APU/IO
    //register chip-select for $4000-$401F is decoded straight off the 6502's own address
    //bus lines (A5-A15), NOT off the DMA's target address - and since only those 11 lines
    //are checked, the registers are effectively mirrored every $20 bytes across the WHOLE
    //address space. So if the frozen CPU address happens to fall in $4000-$401F while the
    //DMA reads some unrelated address, the DMA's real target (ROM/RAM) and the phantom
    //APU/IO decode both try to drive the shared bus at once - a genuine wired conflict.
    //Real hardware resolves it per-bit: whichever decoder actively drives a given bit
    //wins, and any bit neither decoder drives floats at whatever the OTHER decoder put
    //there. We get that for free by seeding the CPU's open-bus latch with the DMA's real
    //target value before reading through the register decode - see Bus.cpuRead's
    //$4016/$4017 handling and APU.cpuRead's $4015 handling, which both already fall back
    //to the open-bus latch for any bit they don't actively drive.
    //See AccuracyCoin's "APU Register Activation" and "DMC DMA Bus Conflicts" tests, and
    //the nesdev wiki's "DMA open bus" / "DMC DMA bus conflict" pages.
    private int dmaRead(int dmaAddr, int cpuAddressBus) {
        int real = bus.cpuRead(dmaAddr) & 0xFF;
        if (debugDma) System.err.println("[t="+totalCycles+"] dmaRead addr="+Integer.toHexString(dmaAddr)+" cpuAddrBus="+Integer.toHexString(cpuAddressBus)+" val="+Integer.toHexString(real));
        if ((cpuAddressBus & 0xFFE0) != 0x4000) {
            dataBus = real;
            lastBusAddress = dmaAddr;
            return real;
        }
        dataBus = real;
        int mirrored = 0x4000 | (dmaAddr & 0x1F);
        int result = bus.cpuRead(mirrored) & 0xFF;
        //$4015's read value never actually updates the CPU's external open-bus latch
        //(same quirk read() honors for a direct LDA $4015) - but here the ROM/RAM driver
        //is also on the bus at the same time, so the latch still ends up holding that
        //real target value rather than being left completely untouched.
        if (mirrored != 0x4015) dataBus = result;
        lastBusAddress = dmaAddr;
        return result;
    }
    public void write(int addr, byte data) {
        bus.cpuWrite(addr, data);
        dataBus = data & 0xFF;
        lastBusAddress = addr;
        lastCycleWasWrite = true;
        if (debugDma && ((addr >= 0x50 && addr <= 0x5F) || addr == 0x4015)) System.err.println("[t="+totalCycles+"] write $"+Integer.toHexString(addr)+" = "+Integer.toHexString(data&0xFF));
    }

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

        addrAbs = 0; fetched = 0;
        microOps.clear();
        nmiPending = false;
        irqPending = false;
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

    //PPU calls this when $2002 is read at the exact PPU cycle the VBlank flag was
    //set (see PPU.cpuRead's $2002 case) - this precisely-timed read race suppresses
    //the NMI for this VBlank without affecting the flag itself (which the read's own
    //caller already returns/clears normally). Only cancels a still-pending edge - if
    //the CPU has already latched/consumed it (mid interrupt sequence), it's too late.
    public void cancelPendingNMI() { nmiPending = false; }

    //Level-sensitive: held asserted for as long as any IRQ source (APU frame counter,
    //DMC) still wants it. The source is responsible for calling clearIRQ() only once
    //none of its own conditions are asserted anymore.
    public void raiseIRQ() { irqPending = true; }
    public void clearIRQ() { irqPending = false; }

    //Runs exactly one clock cycle. Matches the CPU-clock-per-call contract the rest
    //of the emulator already assumes (NESEmulator calls this once per real CPU cycle).
    //Thin wrapper so every exit path from stepInner() passes through the same
    //dmcDmaPendingVisible snapshot below, regardless of which of stepInner()'s several
    //return points was taken.
    public void step(StringBuilder traceBuffer, PPU ppu, boolean tracingEnabled) {
        stepInner(traceBuffer, ppu, tracingEnabled);
        //Snapshot dmcDmaPending as of the END of this cycle, for maybeStartDmcHalt()
        //to consult on the NEXT step() call - see dmcDmaPendingVisible's declaration.
        dmcDmaPendingVisible = dmcDmaPending;
        //Same snapshot dance for the 1-cycle DMA variant - see dmc1CyclePendingVisible's
        //declaration.
        dmc1CyclePendingVisible = dmc1CyclePending;
    }

    private void stepInner(StringBuilder traceBuffer, PPU ppu, boolean tracingEnabled) {
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
                enqueueInterrupt(0xFFFA);
            } else if (latchedIrqPending) {
                latchedIrqPending = false;
                enqueueInterrupt(0xFFFE);
            } else {
                if (tracingEnabled && traceBuffer != null) {
                    traceBuffer.append(getTraceLine(ppu)).append("\n");
                }
                int opcode = read(pc) & 0xFF;
                pc = (pc + 1) & 0xFFFF;
                dispatch(opcode);
                totalCycles++;
                dmcParityCycles++;
                return; //opcode fetch is itself the whole of this cycle; the micro-ops
                        //it just queued each get their own cycle on subsequent calls
            }
        }

        Runnable op = microOps.poll();
        if (op != null) op.run();
        totalCycles++;
        dmcParityCycles++;
    }

    //Runs one cycle of an in-progress DMC DMA halt (or its final "get" fetch cycle).
    //Factored out so both the top-of-step() early return and the "a halt just started
    //this cycle" path (see maybeStartDmcHalt()'s call site in step()) share it.
    private void runStallCycle() {
        stallCycles--;
        if (dmc1CycleGetPending) {
            dmc1CycleGetPending = false;
            //No halt/alignment cycles for this variant - the stolen cycle itself
            //performs the get. The frozen address bus for the (nonexistent) dummy
            //reads is whatever the pending cycle's own target would have been - see
            //maybeStartDmcHalt()'s identical microOps.isEmpty()?pc:addrAbs logic.
            int busAddr = microOps.isEmpty() ? pc : addrAbs;
            int value = dmaRead(dmc1CycleAddress, busAddr);
            if (apu != null) apu.dmcDmaCompleted(value);
        } else if (dmcHalting) {
            if (dmcHaltAborted) {
                //Explicit DMA Abort: this single cycle IS the whole (aborted) DMA - no
                //dummy/alignment cycles, no "get" fetch. See maybeStartDmcHalt()'s
                //dmcDmaAbortPending handling.
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
        //Real hardware re-checks the interrupt lines as it resumes fetching the next
        //opcode right after RDY goes high again from an OAM DMA. Without this,
        //latchedIrqPending/latchedNmiPending would still hold whatever was polled
        //before the DMA even started, silently delaying recognition of any IRQ that
        //became pending mid-DMA until the following instruction's own poll point.
        //Scoped to OAM DMA only (oamDmaStall) - a DMC DMA halt stalls mid-instruction,
        //and re-polling as soon as IT ends would make an IRQ visible one instruction
        //too early, since the interrupted instruction hasn't reached its own natural
        //poll point yet (see AccuracyCoin's Interrupt Flag Latency test 8).
        if (stallCycles == 0 && oamDmaStall) {
            oamDmaStall = false;
            pollInterrupts();
        }
        totalCycles++;
        dmcParityCycles++;
    }

    //---------------------------------------------------------------------
    // Instruction dispatch / microcode sequencing
    //---------------------------------------------------------------------

    private void dispatch(int opcode) {
        Opcode op = lookupTable[opcode];

        if (op.type == OpType.OTHER) {
            microOps.add(op.customFirst);
        } else if (op.mode == AddrMode.ACC) {
            microOps.add(() -> {
                pollInterrupts();
                read(pc); //dummy read, real hardware still reads the next byte
                a = op.rmwOp.run(a) & 0xFF;
            });
        } else if (op.mode == AddrMode.IMM) {
            microOps.add(() -> {
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
    //ZP0/ZPX/ZPY/ABS/ABX/ABY/IZX/IZY addressing. Each closure resolves one more
    //piece of the effective address and enqueues the next; the last one calls
    //enqueueFinal() which performs the actual read/write/rmw cycle(s).
    private void beginRWM(Opcode op) {
        switch (op.mode) {
            case ZP0:
                microOps.add(() -> {
                    addrAbs = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    enqueueFinal(op);
                });
                break;

            case ZPX:
            case ZPY:
                microOps.add(() -> {
                    int zpBase = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    microOps.add(() -> {
                        read(zpBase); //dummy read at the unindexed zero-page address
                        int index = (op.mode == AddrMode.ZPX) ? x : y;
                        addrAbs = (zpBase + index) & 0xFF;
                        enqueueFinal(op);
                    });
                });
                break;

            case ABS:
                microOps.add(() -> {
                    int lo = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    microOps.add(() -> {
                        int hi = read(pc) & 0xFF;
                        pc = (pc + 1) & 0xFFFF;
                        addrAbs = (hi << 8) | lo;
                        enqueueFinal(op);
                    });
                });
                break;

            case ABX:
            case ABY:
                microOps.add(() -> {
                    int lo = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    microOps.add(() -> {
                        int hi = read(pc) & 0xFF;
                        pc = (pc + 1) & 0xFFFF;
                        int index = (op.mode == AddrMode.ABX) ? x : y;
                        int base = (hi << 8) | lo;
                        addrAbs = (base + index) & 0xFFFF;
                        boolean crossed = (base & 0xFF00) != (addrAbs & 0xFF00);
                        pageCrossed = crossed;
                        //This cycle is exactly 2 cycles before the write cycle for
                        //WRITE-type ABX/ABY instructions - see the comment on
                        //cycleFollowsDmcHalt for why that's the cycle that matters.
                        if (op.highByteCorruption && cycleFollowsDmcHalt) suppressHighByteCorruptionOnWrite = true;

                        if (op.type == OpType.READ && !crossed) {
                            enqueueFinal(op);
                        } else {
                            //WRITE/RMW always spend this cycle; READ only spends it on a page cross
                            microOps.add(() -> {
                                read((base & 0xFF00) | (addrAbs & 0xFF));
                                enqueueFinal(op);
                            });
                        }
                    });
                });
                break;

            case IZX:
                microOps.add(() -> {
                    int zpBase = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    microOps.add(() -> {
                        read(zpBase); //dummy read before X is applied
                        microOps.add(() -> {
                            int lo = read((zpBase + x) & 0xFF) & 0xFF;
                            microOps.add(() -> {
                                int hi = read((zpBase + x + 1) & 0xFF) & 0xFF;
                                addrAbs = (hi << 8) | lo;
                                enqueueFinal(op);
                            });
                        });
                    });
                });
                break;

            case IZY:
                microOps.add(() -> {
                    int zpBase = read(pc) & 0xFF;
                    pc = (pc + 1) & 0xFFFF;
                    microOps.add(() -> {
                        int lo = read(zpBase) & 0xFF;
                        microOps.add(() -> {
                            int hi = read((zpBase + 1) & 0xFF) & 0xFF;
                            int base = (hi << 8) | lo;
                            addrAbs = (base + y) & 0xFFFF;
                            boolean crossed = (base & 0xFF00) != (addrAbs & 0xFF00);
                            pageCrossed = crossed;
                            //This cycle is exactly 2 cycles before the write cycle for
                            //WRITE-type IZY instructions - see the comment on
                            //cycleFollowsDmcHalt for why that's the cycle that matters.
                            if (op.highByteCorruption && cycleFollowsDmcHalt) suppressHighByteCorruptionOnWrite = true;

                            if (op.type == OpType.READ && !crossed) {
                                enqueueFinal(op);
                            } else {
                                microOps.add(() -> {
                                    read((base & 0xFF00) | (addrAbs & 0xFF));
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

    //Final cycle(s) once addrAbs is resolved: 1 cycle for READ/WRITE, 3 for RMW
    //(read old value, dummy-write it back unmodified, write the new value) -
    //matching real 6502 read-modify-write bus timing.
    private void enqueueFinal(Opcode op) {
        switch (op.type) {
            case READ:
                microOps.add(() -> {
                    pollInterrupts();
                    fetched = read(addrAbs) & 0xFF;
                    op.readOp.run(fetched);
                });
                break;

            case WRITE:
                microOps.add(() -> {
                    pollInterrupts();
                    boolean suppress = suppressHighByteCorruptionOnWrite && op.highByteCorruption;
                    suppressHighByteCorruptionOnWrite = false;

                    int value = op.writeOp.run() & 0xFF;
                    int addr;
                    if (suppress) {
                        //SHA/SHX/SHY/TAS: a DMC DMA halted the CPU 2 cycles before this
                        //write cycle, which drops the high-byte-AND term entirely - the
                        //instruction just writes the plain register value to the
                        //unmodified effective address, as if it were a normal STA/STX/STY.
                        value = op.plainWriteOp.run() & 0xFF;
                        addr = addrAbs;
                    } else {
                        //SHA/SHX/SHY/TAS: when indexing crossed a page, the corrupted high
                        //byte that got ANDed into the written value also lands on the
                        //address bus, so the byte is actually stored at that address instead
                        //of the "correct" effective address.
                        addr = (op.highByteCorruption && pageCrossed)
                                ? ((value & 0xFF) << 8) | (addrAbs & 0xFF)
                                : addrAbs;
                    }
                    write(addr, (byte) value);
                });
                pendingOpIsWrite = true;
                break;

            case RMW:
                microOps.add(() -> {
                    fetched = read(addrAbs) & 0xFF;
                    microOps.add(() -> {
                        write(addrAbs, (byte) fetched); //dummy write-back of the unmodified value
                        microOps.add(() -> {
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

    //If an NMI becomes pending during BRK/IRQ's own push cycles, it hijacks the
    //sequence in progress: the vector fetched at the end swaps from $FFFE to $FFFA,
    //rather than waiting for a separate NMI sequence afterward. Consumes nmiPending
    //so the hijacked sequence doesn't ALSO trigger a standalone NMI right after.
    private boolean pollAndConsumeNmi() {
        if (nmiPending) { nmiPending = false; return true; }
        return false;
    }

    //NMI/IRQ take 7 cycles total: 2 internal cycles (no opcode was fetched), then
    //the shared 5-cycle push+vector chain.
    private void enqueueInterrupt(int vector) {
        boolean hijackable = (vector == 0xFFFE); //only IRQ can be hijacked by NMI, not NMI by itself
        microOps.add(() -> {
            read(pc);
            microOps.add(() -> {
                read(pc);
                int[] vectorBox = {vector};
                if (hijackable && pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
                microOps.add(pushAndVectorFirst(vectorBox, false, hijackable));
                pendingOpIsWrite = true;
            });
        });
    }

    //5-cycle self-extending chain: push PCH, push PCL, push status, fetch vector lo, fetch vector hi.
    //Per AccuracyCoin's own research (see TEST_IFlagLatency comments), BRK/IRQ/NMI poll
    //for interrupts before each of the 3 push cycles, but NOT before the final 2 (the
    //vector fetch) - so whatever was last polled by the "push status" cycle is what
    //decides both (a) whether this sequence gets hijacked to the NMI vector, and
    //(b) what's latched for the instruction that runs once this sequence completes.
    private Runnable pushAndVectorFirst(int[] vectorBox, boolean pushB, boolean hijackable) {
        return () -> {
            pollInterrupts();
            if (hijackable && pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
            write(0x0100 + sp, (byte) ((pc >> 8) & 0xFF));
            sp = (sp - 1) & 0xFF;
            microOps.add(() -> {
                pollInterrupts();
                if (hijackable && pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
                write(0x0100 + sp, (byte) (pc & 0xFF));
                sp = (sp - 1) & 0xFF;
                microOps.add(() -> {
                    if (hijackable && pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
                    write(0x0100 + sp, (byte) (status | U | (pushB ? B : 0)));
                    sp = (sp - 1) & 0xFF;
                    setFlag(I, true);
                    //Poll AFTER I gets set here (unlike the other poll points, which
                    //poll before their cycle's own effect): by the time this cycle
                    //ends, I is already true, so a still-pending IRQ must NOT look
                    //like it can immediately re-fire right after this sequence.
                    pollInterrupts();
                    microOps.add(() -> {
                        int lo = read(vectorBox[0]) & 0xFF;
                        microOps.add(() -> {
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

    //Shared shape for simple implied-mode instructions: one dummy read of the next
    //byte (which real hardware always performs), then the register/flag effect.
    private Runnable implied(Runnable action) {
        return () -> {
            //Poll BEFORE action.run() - real hardware polls before this final cycle's
            //own effects apply, which matters for CLI/SEI: their poll must see the OLD
            //I flag, not the one this same cycle is about to set/clear.
            pollInterrupts();
            read(pc);
            action.run();
        };
    }

    //Branches are the one documented exception to "poll before the final cycle":
    //a not-taken (2-cycle) branch polls once, before cycle 2. A taken branch polls
    //again before cycle 4 - but ONLY if a page boundary was crossed (i.e. only if
    //cycle 4 actually exists); a taken-not-crossed (3-cycle) branch does NOT re-poll
    //before its cycle 3, so the cycle-2 poll's result is what still applies.
    private Runnable branch(BooleanSupplier cond) {
        return () -> {
            pollInterrupts(); //poll before cycle 2 (always)
            int rawOffset = read(pc) & 0xFF;
            pc = (pc + 1) & 0xFFFF;
            int signedOffset = (byte) rawOffset;

            if (!cond.getAsBoolean()) return; //not taken: 2 cycles total

            microOps.add(() -> {
                read(pc); //dummy read, branch is being taken - no poll here
                int oldPc = pc;
                int target = (pc + signedOffset) & 0xFFFF;
                pc = target;

                if ((oldPc & 0xFF00) != (target & 0xFF00)) {
                    microOps.add(() -> {
                        pollInterruptsBranchSecond(); //poll before cycle 4 (only exists if page crossed)
                        read((oldPc & 0xFF00) | (target & 0xFF));
                    });
                }
            });
        };
    }

    private Runnable brk() {
        return () -> {
            //BRK polls before cycle 2 too (its own padding-byte read) - an NMI already
            //pending here can also hijack this BRK to the NMI vector.
            pollInterrupts();
            int[] vectorBox = {0xFFFE};
            if (pollAndConsumeNmi()) vectorBox[0] = 0xFFFA;
            read(pc); //padding byte, discarded
            pc = (pc + 1) & 0xFFFF;
            microOps.add(pushAndVectorFirst(vectorBox, true, true));
            pendingOpIsWrite = true;
        };
    }

    private Runnable jsr() {
        return () -> {
            int lo = read(pc) & 0xFF;
            pc = (pc + 1) & 0xFFFF;
            microOps.add(() -> {
                read(0x0100 + sp); //internal delay cycle
                microOps.add(() -> {
                    write(0x0100 + sp, (byte) ((pc >> 8) & 0xFF));
                    sp = (sp - 1) & 0xFF;
                    microOps.add(() -> {
                        write(0x0100 + sp, (byte) (pc & 0xFF));
                        sp = (sp - 1) & 0xFF;
                        microOps.add(() -> {
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
            microOps.add(() -> {
                read(0x0100 + sp);
                microOps.add(() -> {
                    sp = (sp + 1) & 0xFF;
                    int lo = read(0x0100 + sp) & 0xFF;
                    microOps.add(() -> {
                        sp = (sp + 1) & 0xFF;
                        int hi = read(0x0100 + sp) & 0xFF;
                        pc = (hi << 8) | lo;
                        microOps.add(() -> {
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
            microOps.add(() -> {
                read(0x0100 + sp);
                microOps.add(() -> {
                    sp = (sp + 1) & 0xFF;
                    status = read(0x0100 + sp) & 0xFF;
                    status &= ~B;
                    status |= U;
                    microOps.add(() -> {
                        sp = (sp + 1) & 0xFF;
                        int lo = read(0x0100 + sp) & 0xFF;
                        microOps.add(() -> {
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
            microOps.add(() -> {
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
            microOps.add(() -> {
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
            microOps.add(() -> {
                read(0x0100 + sp);
                microOps.add(() -> {
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
            microOps.add(() -> {
                read(0x0100 + sp);
                microOps.add(() -> {
                    //Poll BEFORE pulling status off the stack - real hardware polls
                    //using the OLD I flag, before PLP's own write of the new one lands.
                    pollInterrupts();
                    sp = (sp + 1) & 0xFF;
                    status = read(0x0100 + sp) & 0xFF;
                    //The B flag has no physical flip-flop in the status register - it
                    //only exists as a bit synthesized when status gets pushed (PHP: 1,
                    //BRK: 1, IRQ/NMI: 0). Pulling a byte with bit 4 set must NOT leave
                    //it latched in `status`, or it'll leak into every later BRK/IRQ/NMI
                    //push until the next RTI (RTI already clears it below).
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
            microOps.add(() -> {
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
            microOps.add(() -> {
                int ptrHi = read(pc) & 0xFF;
                pc = (pc + 1) & 0xFFFF;
                int ptr = (ptrHi << 8) | ptrLo;
                microOps.add(() -> {
                    int lo = read(ptr) & 0xFF;
                    microOps.add(() -> {
                        pollInterrupts();
                        int hiAddr = ((ptr & 0x00FF) == 0x00FF) ? (ptr & 0xFF00) : (ptr + 1);
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

    //Same registers, but without the "&(high+1)" term - what SHX/SHY/AHX/TAS actually
    //write instead if a DMC DMA halts the CPU 2 cycles before the write cycle (verified
    //against AccuracyCoin's Unofficial Instructions error code 7 test cases).
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

    //Small named predicates so branch() call sites read cleanly for the four flags
    //that don't need negation.
    private boolean getFlagC() { return getFlag(C); }
    private boolean getFlagZ() { return getFlag(Z); }
    private boolean getFlagV() { return getFlag(V); }
}
