//2A03 APU: pulse x2, triangle, noise, DMC and frame sequencer, mixed in getOutputSample()
//and downsampled to 44100Hz in clock(). Also models the DMC DMA/IRQ interaction with the CPU.
public class APU {

    private CPU cpu;
    public void connectCPU(CPU cpu) { this.cpu = cpu; }

    //For expansion audio: the cartridge's channels are summed into the mixer
    private Bus bus;
    public void connectBus(Bus bus) { this.bus = bus; }

    private AudioPlayer audioPlayer;
    public void connectAudioPlayer(AudioPlayer audioPlayer) { this.audioPlayer = audioPlayer; }

    //Downsamples ~1.789773MHz CPU clock to 44100Hz via box-filter averaging;
    //remainder tracked so the 40.6:1 ratio doesn't drift.
    private static final double CPU_HZ = 1789773.0;
    private static final double SAMPLE_HZ = 44100.0;
    //Output rate nudged a fraction of a percent around SAMPLE_HZ to hold the audio queue near
    //AudioPlayer.TARGET_QUEUE_SAMPLES; the emulator and sound card run off different clocks,
    //so without this the queue starves (dropouts) or overflows (dropped audio).
    private double outputSampleHz = SAMPLE_HZ;
    private static final double MAX_RATE_ADJUST = 0.01;
    private static final int RATE_CHECK_INTERVAL = 128; //output samples between checks
    private int samplesSinceRateCheck = 0;
    private double queuedAverage = -1;
    private double sampleCycleAccumulator = 0;
    private double outputAccum = 0;
    private int outputCount = 0;

    private static final int[] LENGTH_TABLE = {
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
    };
    private static final int[][] DUTY_TABLE = {
            {0, 1, 0, 0, 0, 0, 0, 0},
            {0, 1, 1, 0, 0, 0, 0, 0},
            {0, 1, 1, 1, 1, 0, 0, 0},
            {1, 0, 0, 1, 1, 1, 1, 1},
    };
    private static final int[] TRIANGLE_SEQUENCE = {
            15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };
    private static final int[] NOISE_PERIOD_TABLE = {
            4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
    };
    private static final int[] DMC_RATE_TABLE = {
            428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54
    };

    //-----------------------------------------------------------------
    // Envelope (shared shape used by both pulse channels and noise)
    //-----------------------------------------------------------------
    private class Envelope {
        boolean loop;
        boolean constantVolume;
        int volume; //also divider period
        boolean start;
        int divider;
        int decay;

        void writeControl(int val) {
            loop = (val & 0x20) != 0;
            constantVolume = (val & 0x10) != 0;
            volume = val & 0x0F;
        }

        void clock() {
            if (start) {
                start = false;
                decay = 15;
                divider = volume;
            } else if (divider == 0) {
                divider = volume;
                if (decay > 0) decay--;
                else if (loop) decay = 15;
            } else {
                divider--;
            }
        }

        int output() { return constantVolume ? volume : decay; }
    }

    //-----------------------------------------------------------------
    // Pulse channel
    //-----------------------------------------------------------------
    private class Pulse {
        final boolean isPulse1;
        Pulse(boolean isPulse1) { this.isPulse1 = isPulse1; }

        boolean enabled;
        int duty;
        boolean lengthHalt;
        final Envelope envelope = new Envelope();

        boolean sweepEnabled, sweepNegate, sweepReload;
        int sweepPeriod, sweepShift, sweepDivider;

        int timerPeriod, timerValue, sequencePos;
        int lengthCounter;

        void writeReg0(int val) {
            duty = (val >> 6) & 0x03;
            lengthHalt = (val & 0x20) != 0;
            envelope.writeControl(val);
        }

        void writeReg1(int val) { //sweep
            sweepEnabled = (val & 0x80) != 0;
            sweepPeriod = (val >> 4) & 0x07;
            sweepNegate = (val & 0x08) != 0;
            sweepShift = val & 0x07;
            sweepReload = true;
        }

        void writeReg2(int val) { timerPeriod = (timerPeriod & 0x0700) | val; }

        void writeReg3(int val) {
            timerPeriod = (timerPeriod & 0x00FF) | ((val & 0x07) << 8);
            if (enabled) lengthCounter = LENGTH_TABLE[(val >> 3) & 0x1F];
            envelope.start = true;
            //Hardware restarts the sequencer phase on $4003/$4007 writes; without this two
            //pulses on the same note (SMB/SMB3 timer countdown) can partially cancel.
            sequencePos = 0;
        }

        int targetPeriod() {
            int change = timerPeriod >> sweepShift;
            if (sweepNegate) change = isPulse1 ? -change - 1 : -change;
            return timerPeriod + change;
        }

        boolean isMuted() { return timerPeriod < 8 || targetPeriod() > 0x7FF; }

        void clockTimer() {
            if (timerValue == 0) {
                timerValue = timerPeriod;
                sequencePos = (sequencePos - 1) & 7;
            } else {
                timerValue--;
            }
        }

        void clockEnvelope() { envelope.clock(); }

        void clockSweep() {
            int target = targetPeriod();
            if (sweepDivider == 0 && sweepEnabled && sweepShift > 0 && !isMuted()) {
                timerPeriod = target;
            }
            if (sweepDivider == 0 || sweepReload) {
                sweepDivider = sweepPeriod;
                sweepReload = false;
            } else {
                sweepDivider--;
            }
        }

        void clockLength() {
            if (!lengthHalt && lengthCounter > 0) lengthCounter--;
        }

        int output() {
            if (lengthCounter == 0 || isMuted()) return 0;
            return DUTY_TABLE[duty][sequencePos] * envelope.output();
        }
    }

    //-----------------------------------------------------------------
    // Triangle channel
    //-----------------------------------------------------------------
    private class Triangle {
        boolean enabled;
        boolean controlFlag; //also the length-counter halt flag
        int linearReloadValue;
        boolean linearReloadFlag;
        int linearCounter;

        int timerPeriod, timerValue, sequencePos;
        int lengthCounter;

        void writeReg0(int val) {
            controlFlag = (val & 0x80) != 0;
            linearReloadValue = val & 0x7F;
        }

        void writeTimerLo(int val) { timerPeriod = (timerPeriod & 0x0700) | val; }

        void writeReg3(int val) {
            timerPeriod = (timerPeriod & 0x00FF) | ((val & 0x07) << 8);
            if (enabled) lengthCounter = LENGTH_TABLE[(val >> 3) & 0x1F];
            linearReloadFlag = true;
        }

        void clockTimer() {
            if (timerValue == 0) {
                timerValue = timerPeriod;
                if (lengthCounter > 0 && linearCounter > 0) sequencePos = (sequencePos + 1) & 31;
            } else {
                timerValue--;
            }
        }

        void clockLinear() {
            if (linearReloadFlag) linearCounter = linearReloadValue;
            else if (linearCounter > 0) linearCounter--;
            if (!controlFlag) linearReloadFlag = false;
        }

        void clockLength() {
            if (!controlFlag && lengthCounter > 0) lengthCounter--;
        }

        int output() { return TRIANGLE_SEQUENCE[sequencePos]; }
    }

    //-----------------------------------------------------------------
    // Noise channel
    //-----------------------------------------------------------------
    private class Noise {
        boolean enabled;
        boolean lengthHalt;
        final Envelope envelope = new Envelope();

        boolean mode;
        int periodIndex;
        int timerValue;
        int shiftRegister = 1;
        int lengthCounter;

        void writeReg0(int val) {
            lengthHalt = (val & 0x20) != 0;
            envelope.writeControl(val);
        }

        void writeReg2(int val) {
            mode = (val & 0x80) != 0;
            periodIndex = val & 0x0F;
        }

        void writeReg3(int val) {
            if (enabled) lengthCounter = LENGTH_TABLE[(val >> 3) & 0x1F];
            envelope.start = true;
        }

        void clockTimer() {
            if (timerValue == 0) {
                //NOISE_PERIOD_TABLE is in CPU cycles but this timer runs on the divide-by-2 APU
                //clock, so halve it (period-1 reload convention); the raw value was an octave too low.
                timerValue = (NOISE_PERIOD_TABLE[periodIndex] >> 1) - 1;
                int feedback = (shiftRegister & 1) ^ ((shiftRegister >> (mode ? 6 : 1)) & 1);
                shiftRegister = (shiftRegister >> 1) | (feedback << 14);
            } else {
                timerValue--;
            }
        }

        void clockEnvelope() { envelope.clock(); }

        void clockLength() {
            if (!lengthHalt && lengthCounter > 0) lengthCounter--;
        }

        int output() {
            if (lengthCounter == 0 || (shiftRegister & 1) != 0) return 0;
            return envelope.output();
        }
    }

    //-----------------------------------------------------------------
    // DMC channel
    //-----------------------------------------------------------------
    private class Dmc {
        boolean irqEnable;
        boolean loop;
        int rateIndex;
        int outputLevel;
        int sampleAddress, sampleLength;
        int currentAddress, bytesRemaining;

        int timerValue;
        boolean bufferHasData;
        int bufferByte;
        boolean silence = true;
        int shiftRegister;
        int bitsRemaining = 8;

        boolean dmaPending;
        boolean irqFlag;

        //Cycles left until a pending restart reloads currentAddress/bytesRemaining; 0 = none pending.
        int enableDelay = 0;

        //Implicit DMA Abort ("1-cycle DMA") grace window, counting down after a non-looping
        //sample's last byte; a $4015 re-enable reload landing while >0 fuses into the 1-cycle DMA.
        int naturalEndGraceCycles = 0;
        boolean sampleJustEndedNaturally() { return naturalEndGraceCycles > 0; }

        void writeReg0(int val) {
            irqEnable = (val & 0x80) != 0;
            loop = (val & 0x40) != 0;
            rateIndex = val & 0x0F;
            if (!irqEnable) { irqFlag = false; updateIrqLine(); }
            if (CPU.debugDma) System.err.println("[t="+(cpu!=null?cpu.totalCycles:-1)+"] writeReg0 val="+Integer.toHexString(val)+" loop="+loop+" rate="+rateIndex);
        }

        void writeDirectLoad(int val) { outputLevel = val & 0x7F; }
        void writeSampleAddress(int val) { sampleAddress = 0xC000 + (val * 64); }
        void writeSampleLength(int val) { sampleLength = (val * 16) + 1; }

        //$4015 DMC-enable restart takes effect 3 CPU cycles later on real hardware
        //(AccuracyCoin DMC tests L/M/N). Gated on bytesRemaining==0, not a persistent
        //"ever enabled" latch.
        void writeEnableFlag(boolean enable) {
            if (CPU.debugDma) System.err.println("[t="+(cpu!=null?cpu.totalCycles:-1)+"] writeEnableFlag("+enable+") bytesRemaining="+bytesRemaining+" enableDelay="+enableDelay+" dmaPending="+dmaPending+" naturalEndGraceCycles="+naturalEndGraceCycles+" timerValue="+timerValue+" bitsRemaining="+bitsRemaining+" bufferHasData="+bufferHasData);
            if (!enable) {
                //Explicit DMA Abort: disabling 1-2 cycles before a reload DMA would fire
                //still starts a phantom DMA, aborted after one cycle (AccuracyCoin answer key).
                boolean reloadArmed = bitsRemaining == 0 && bufferHasData && bytesRemaining > 0;
                if (!dmaPending && reloadArmed && timerValue + 1 <= 2 && cpu != null) {
                    cpu.startAbortedDmcDma();
                }
                bytesRemaining = 0;
                enableDelay = 0;
                naturalEndGraceCycles = 0;
                //Notify CPU to truncate/drop an already-scheduled DMC DMA halt.
                if (cpu != null) cpu.cancelDmcDma();
            } else if (bytesRemaining == 0 && enableDelay == 0) {
                enableDelay = 2;
            }
        }

        //isReload: "load" DMA halts on a get cycle, "reload" DMA halts on a put cycle
        //(CPU.maybeStartDmcHalt() needs this to pick 2-vs-3 halt cycles).
        void requestDma(boolean isReload) {
            if (dmaPending || bytesRemaining == 0) return;
            dmaPending = true;
            if (cpu != null) cpu.requestDmcDma(currentAddress, isReload);
        }

        //Variant of requestDma() for the fused 1-cycle DMA case.
        void requestDma1Cycle() {
            if (dmaPending || bytesRemaining == 0) return;
            dmaPending = true;
            if (cpu != null) cpu.requestDmcDma1Cycle(currentAddress);
        }

        void dmaCompleted(int value) {
            dmaPending = false;
            //If disabled while in flight, bytesRemaining was forced to 0 - discard the
            //byte instead of committing it, else bytesRemaining-- wedges the channel.
            if (bytesRemaining == 0) return;
            bufferByte = value & 0xFF;
            bufferHasData = true;
            currentAddress = (currentAddress + 1) & 0xFFFF;
            if (currentAddress < 0x8000) currentAddress = 0x8000; //sample space wraps to $8000
            bytesRemaining--;
            if (bytesRemaining == 0) {
                if (loop) {
                    currentAddress = sampleAddress;
                    bytesRemaining = sampleLength;
                } else {
                    //Opens the grace window a fresh $4015 re-enable can fuse with.
                    naturalEndGraceCycles = 3;
                    //Implicit DMA Abort: same phantom 1-cycle DMA as the explicit case, triggered by
                    //a non-looping sample's last byte (AccuracyCoin "Implicit DMA Abort" keys 1/2).
                    //The 3-cycle offset is hardware noticing the stop at the output cycle, not the fetch.
                    if (bitsRemaining == 0 && timerValue + 1 <= 2) abortedDmaDelay = 3;
                    if (irqEnable) {
                        irqFlag = true;
                        updateIrqLine();
                    }
                }
            }
        }

        //Cycles left before phantom aborted DMA is asserted; 0 = none scheduled.
        int abortedDmaDelay = 0;

        void clockTimer() {
            if (abortedDmaDelay > 0 && --abortedDmaDelay == 0 && cpu != null) cpu.startAbortedDmcDma();
            if (enableDelay > 0) {
                enableDelay--;
                if (enableDelay == 0) {
                    currentAddress = sampleAddress;
                    bytesRemaining = sampleLength;
                    if (CPU.debugDma) System.err.println("[t="+(cpu!=null?cpu.totalCycles:-1)+"] enableDelay reload bufferHasData="+bufferHasData+" naturalEndGraceCycles="+naturalEndGraceCycles);
                    if (!bufferHasData) {
                        //Within the natural-end grace window: fuse into the 1-cycle DMA.
                        //Otherwise an ordinary fresh DMA.
                        if (sampleJustEndedNaturally()) {
                            naturalEndGraceCycles = 0;
                            requestDma1Cycle();
                        } else {
                            requestDma(false); //load DMA
                        }
                    }
                }
            }

            if (timerValue == 0) {
                //DMC_RATE_TABLE entries are the exact cycle interval (unlike Pulse/Triangle's
                //period-1 convention), so subtract 1 here or every clock runs 1 cycle long.
                timerValue = DMC_RATE_TABLE[rateIndex] - 1;

                if (bitsRemaining == 0) {
                    bitsRemaining = 8;
                    if (bufferHasData) {
                        shiftRegister = bufferByte;
                        bufferHasData = false;
                        silence = false;
                        if (bytesRemaining > 0) requestDma(true); //reload DMA
                    } else {
                        silence = true;
                    }
                }

                if (!silence) {
                    if ((shiftRegister & 1) != 0) { if (outputLevel <= 125) outputLevel += 2; }
                    else { if (outputLevel >= 2) outputLevel -= 2; }
                }
                shiftRegister >>= 1;
                bitsRemaining--;
            } else {
                timerValue--;
            }

            //Let the grace window expire on its own if unused above.
            if (naturalEndGraceCycles > 0) naturalEndGraceCycles--;
        }
    }

    //TAS Maker greenzone checkpoint support; one static holder class per channel.
    public static final class EnvelopeState {
        boolean loop, constantVolume;
        int volume;
        boolean start;
        int divider, decay;
    }
    private static EnvelopeState snapshotEnvelope(Envelope e) {
        EnvelopeState s = new EnvelopeState();
        s.loop = e.loop; s.constantVolume = e.constantVolume; s.volume = e.volume;
        s.start = e.start; s.divider = e.divider; s.decay = e.decay;
        return s;
    }
    private static void restoreEnvelope(Envelope e, EnvelopeState s) {
        e.loop = s.loop; e.constantVolume = s.constantVolume; e.volume = s.volume;
        e.start = s.start; e.divider = s.divider; e.decay = s.decay;
    }

    public static final class PulseState {
        boolean enabled;
        int duty;
        boolean lengthHalt;
        EnvelopeState envelope;
        boolean sweepEnabled, sweepNegate, sweepReload;
        int sweepPeriod, sweepShift, sweepDivider;
        int timerPeriod, timerValue, sequencePos;
        int lengthCounter;
    }
    private static PulseState snapshotPulse(Pulse p) {
        PulseState s = new PulseState();
        s.enabled = p.enabled; s.duty = p.duty; s.lengthHalt = p.lengthHalt;
        s.envelope = snapshotEnvelope(p.envelope);
        s.sweepEnabled = p.sweepEnabled; s.sweepNegate = p.sweepNegate; s.sweepReload = p.sweepReload;
        s.sweepPeriod = p.sweepPeriod; s.sweepShift = p.sweepShift; s.sweepDivider = p.sweepDivider;
        s.timerPeriod = p.timerPeriod; s.timerValue = p.timerValue; s.sequencePos = p.sequencePos;
        s.lengthCounter = p.lengthCounter;
        return s;
    }
    private static void restorePulse(Pulse p, PulseState s) {
        p.enabled = s.enabled; p.duty = s.duty; p.lengthHalt = s.lengthHalt;
        restoreEnvelope(p.envelope, s.envelope);
        p.sweepEnabled = s.sweepEnabled; p.sweepNegate = s.sweepNegate; p.sweepReload = s.sweepReload;
        p.sweepPeriod = s.sweepPeriod; p.sweepShift = s.sweepShift; p.sweepDivider = s.sweepDivider;
        p.timerPeriod = s.timerPeriod; p.timerValue = s.timerValue; p.sequencePos = s.sequencePos;
        p.lengthCounter = s.lengthCounter;
    }

    public static final class TriangleState {
        boolean enabled, controlFlag;
        int linearReloadValue;
        boolean linearReloadFlag;
        int linearCounter;
        int timerPeriod, timerValue, sequencePos;
        int lengthCounter;
    }
    private static TriangleState snapshotTriangle(Triangle t) {
        TriangleState s = new TriangleState();
        s.enabled = t.enabled; s.controlFlag = t.controlFlag;
        s.linearReloadValue = t.linearReloadValue; s.linearReloadFlag = t.linearReloadFlag;
        s.linearCounter = t.linearCounter;
        s.timerPeriod = t.timerPeriod; s.timerValue = t.timerValue; s.sequencePos = t.sequencePos;
        s.lengthCounter = t.lengthCounter;
        return s;
    }
    private static void restoreTriangle(Triangle t, TriangleState s) {
        t.enabled = s.enabled; t.controlFlag = s.controlFlag;
        t.linearReloadValue = s.linearReloadValue; t.linearReloadFlag = s.linearReloadFlag;
        t.linearCounter = s.linearCounter;
        t.timerPeriod = s.timerPeriod; t.timerValue = s.timerValue; t.sequencePos = s.sequencePos;
        t.lengthCounter = s.lengthCounter;
    }

    public static final class NoiseState {
        boolean enabled, lengthHalt;
        EnvelopeState envelope;
        boolean mode;
        int periodIndex, timerValue, shiftRegister, lengthCounter;
    }
    private static NoiseState snapshotNoise(Noise n) {
        NoiseState s = new NoiseState();
        s.enabled = n.enabled; s.lengthHalt = n.lengthHalt;
        s.envelope = snapshotEnvelope(n.envelope);
        s.mode = n.mode;
        s.periodIndex = n.periodIndex; s.timerValue = n.timerValue;
        s.shiftRegister = n.shiftRegister; s.lengthCounter = n.lengthCounter;
        return s;
    }
    private static void restoreNoise(Noise n, NoiseState s) {
        n.enabled = s.enabled; n.lengthHalt = s.lengthHalt;
        restoreEnvelope(n.envelope, s.envelope);
        n.mode = s.mode;
        n.periodIndex = s.periodIndex; n.timerValue = s.timerValue;
        n.shiftRegister = s.shiftRegister; n.lengthCounter = s.lengthCounter;
    }

    public static final class DmcState {
        boolean irqEnable, loop;
        int rateIndex, outputLevel;
        int sampleAddress, sampleLength;
        int currentAddress, bytesRemaining;
        int timerValue;
        boolean bufferHasData;
        int bufferByte;
        boolean silence;
        int shiftRegister, bitsRemaining;
        boolean dmaPending, irqFlag;
        int enableDelay;
        int naturalEndGraceCycles;
    }
    private static DmcState snapshotDmc(Dmc d) {
        DmcState s = new DmcState();
        s.irqEnable = d.irqEnable; s.loop = d.loop;
        s.rateIndex = d.rateIndex; s.outputLevel = d.outputLevel;
        s.sampleAddress = d.sampleAddress; s.sampleLength = d.sampleLength;
        s.currentAddress = d.currentAddress; s.bytesRemaining = d.bytesRemaining;
        s.timerValue = d.timerValue;
        s.bufferHasData = d.bufferHasData;
        s.bufferByte = d.bufferByte;
        s.silence = d.silence;
        s.shiftRegister = d.shiftRegister; s.bitsRemaining = d.bitsRemaining;
        s.dmaPending = d.dmaPending; s.irqFlag = d.irqFlag;
        s.enableDelay = d.enableDelay;
        s.naturalEndGraceCycles = d.naturalEndGraceCycles;
        return s;
    }
    private static void restoreDmc(Dmc d, DmcState s) {
        d.irqEnable = s.irqEnable; d.loop = s.loop;
        d.rateIndex = s.rateIndex; d.outputLevel = s.outputLevel;
        d.sampleAddress = s.sampleAddress; d.sampleLength = s.sampleLength;
        d.currentAddress = s.currentAddress; d.bytesRemaining = s.bytesRemaining;
        d.timerValue = s.timerValue;
        d.bufferHasData = s.bufferHasData;
        d.bufferByte = s.bufferByte;
        d.silence = s.silence;
        d.shiftRegister = s.shiftRegister; d.bitsRemaining = s.bitsRemaining;
        d.dmaPending = s.dmaPending; d.irqFlag = s.irqFlag;
        d.enableDelay = s.enableDelay;
        d.naturalEndGraceCycles = s.naturalEndGraceCycles;
    }

    public static final class State {
        double sampleCycleAccumulator, outputAccum;
        int outputCount;
        boolean fiveStepMode, irqInhibit, frameIrqFlag;
        int frameCycle, resetDelay;
        boolean apuCycleToggle;
        PulseState pulse1, pulse2;
        TriangleState triangle;
        NoiseState noise;
        DmcState dmc;
    }

    public State snapshot() {
        State s = new State();
        s.sampleCycleAccumulator = sampleCycleAccumulator;
        s.outputAccum = outputAccum;
        s.outputCount = outputCount;
        s.fiveStepMode = fiveStepMode; s.irqInhibit = irqInhibit; s.frameIrqFlag = frameIrqFlag;
        s.frameCycle = frameCycle; s.resetDelay = resetDelay;
        s.apuCycleToggle = apuCycleToggle;
        s.pulse1 = snapshotPulse(pulse1);
        s.pulse2 = snapshotPulse(pulse2);
        s.triangle = snapshotTriangle(triangle);
        s.noise = snapshotNoise(noise);
        s.dmc = snapshotDmc(dmc);
        return s;
    }

    public void restore(State s) {
        sampleCycleAccumulator = s.sampleCycleAccumulator;
        outputAccum = s.outputAccum;
        outputCount = s.outputCount;
        fiveStepMode = s.fiveStepMode; irqInhibit = s.irqInhibit; frameIrqFlag = s.frameIrqFlag;
        frameCycle = s.frameCycle; resetDelay = s.resetDelay;
        apuCycleToggle = s.apuCycleToggle;
        restorePulse(pulse1, s.pulse1);
        restorePulse(pulse2, s.pulse2);
        restoreTriangle(triangle, s.triangle);
        restoreNoise(noise, s.noise);
        restoreDmc(dmc, s.dmc);
    }

    private Pulse pulse1 = new Pulse(true);
    private Pulse pulse2 = new Pulse(false);
    private Triangle triangle = new Triangle();
    private Noise noise = new Noise();
    private Dmc dmc = new Dmc();

    //Full power-on reset. Only used by TAS Maker's replay-from-power-on seeking.
    public void reset() {
        pulse1 = new Pulse(true);
        pulse2 = new Pulse(false);
        triangle = new Triangle();
        noise = new Noise();
        dmc = new Dmc();
        fiveStepMode = false;
        irqInhibit = false;
        frameIrqFlag = false;
        frameIrqClearAfterCycle = -1;
        frameCycle = 0;
        resetDelay = 0;
        apuCycleToggle = false;
        sampleCycleAccumulator = 0;
        outputAccum = 0;
        outputCount = 0;
    }

    //Reset button (nesdev "CPU power up state", after reset): registers keep their values but
    //$4015 acts as if written $00, the triangle sequencer restarts at step 0, DMC output keeps
    //only bit 0 and the frame counter restarts in its current mode (2A03E/G behaviour).
    public void softReset() {
        pulse1.enabled = false; pulse1.lengthCounter = 0;
        pulse2.enabled = false; pulse2.lengthCounter = 0;
        triangle.enabled = false; triangle.lengthCounter = 0;
        triangle.sequencePos = 0;
        noise.enabled = false; noise.lengthCounter = 0;
        dmc.writeEnableFlag(false);
        dmc.irqFlag = false;
        dmc.outputLevel &= 1;
        frameIrqFlag = false;
        frameIrqClearAfterCycle = -1;
        frameIrqAssertPending = false;
        frameCycle = 0;
        resetDelay = 0;
        apuCycleToggle = false;
        updateIrqLine();
    }

    //-----------------------------------------------------------------
    // Frame sequencer
    //-----------------------------------------------------------------
    private boolean fiveStepMode;
    private boolean irqInhibit;
    private boolean frameIrqFlag;
    private int frameCycle = 0;
    private int resetDelay = 0;
    private boolean apuCycleToggle = false;

    private void clockQuarterFrame() {
        pulse1.clockEnvelope();
        pulse2.clockEnvelope();
        noise.clockEnvelope();
        triangle.clockLinear();
    }

    private void clockHalfFrame() {
        pulse1.clockLength();
        pulse2.clockLength();
        triangle.clockLength();
        noise.clockLength();
        pulse1.clockSweep();
        pulse2.clockSweep();
    }

    private void updateIrqLine() {
        if (cpu == null) return;
        //IRQ line only pulled low when not suppressed, even though the $4015 flag
        //itself can be set while suppressed.
        if ((frameIrqFlag && !irqInhibit) || dmc.irqFlag) cpu.raiseIRQ();
        else cpu.clearIRQ();
    }

    //CPU cycle of a pending $4015 read's frame-IRQ-flag clear; -1 = none pending.
    private long frameIrqClearAfterCycle = -1;

    //IRQ level detector follows the $4015 flag one CPU cycle behind (AccuracyCoin
    //"Frame Counter IRQ" tests N/O).
    private boolean frameIrqAssertPending = false;

    public void clock() {
        //Deferred $4015 IRQ-flag clear, on the first "get" cycle after the read.
        if (frameIrqClearAfterCycle >= 0 && cpu != null
                && cpu.totalCycles > frameIrqClearAfterCycle && (cpu.totalCycles & 1L) == 0L) {
            frameIrqClearAfterCycle = -1;
            frameIrqFlag = false;
            updateIrqLine();
        }

        if (frameIrqAssertPending) {
            frameIrqAssertPending = false;
            updateIrqLine();
        }

        if (resetDelay > 0) {
            resetDelay--;
            if (resetDelay == 0) {
                frameCycle = 0;
                if (fiveStepMode) { clockQuarterFrame(); clockHalfFrame(); }
            }
        } else {
            frameCycle++;
            stepFrameSequencer();
        }

        //Triangle/DMC clocked off raw CPU clock; pulse/noise use divide-by-2 APU cycle.
        triangle.clockTimer();
        dmc.clockTimer();
        apuCycleToggle = !apuCycleToggle;
        if (apuCycleToggle) {
            pulse1.clockTimer();
            pulse2.clockTimer();
            noise.clockTimer();
        }

        outputAccum += getOutputSample();
        outputCount++;
        sampleCycleAccumulator += outputSampleHz;
        if (sampleCycleAccumulator >= CPU_HZ) {
            sampleCycleAccumulator -= CPU_HZ;
            if (audioPlayer != null) {
                audioPlayer.writeSample(outputAccum / outputCount);
                if (++samplesSinceRateCheck >= RATE_CHECK_INTERVAL) {
                    samplesSinceRateCheck = 0;
                    adjustOutputRate();
                }
            }
            outputAccum = 0;
            outputCount = 0;
        }
    }

    //Proportional control on a smoothed queue depth. Samples arrive in per-frame bursts,
    //so the raw depth swings by a frame's worth; the moving average keeps the rate
    //from wobbling with it (a 1% pitch shift is inaudible, a fast-moving one isn't).
    private void adjustOutputRate() {
        int queued = audioPlayer.getQueuedSamples();
        if (queued < 0) { outputSampleHz = SAMPLE_HZ; queuedAverage = -1; return; }
        if (queuedAverage < 0) queuedAverage = queued;
        else queuedAverage += (queued - queuedAverage) * 0.05;
        double target = AudioPlayer.TARGET_QUEUE_SAMPLES;
        double error = (target - queuedAverage) / target;
        if (error > 1) error = 1;
        if (error < -1) error = -1;
        outputSampleHz = SAMPLE_HZ * (1.0 + MAX_RATE_ADJUST * error);
    }

    private void stepFrameSequencer() {
        if (!fiveStepMode) {
            switch (frameCycle) {
                case 7457: clockQuarterFrame(); break;
                case 14913: clockQuarterFrame(); clockHalfFrame(); break;
                case 22371: clockQuarterFrame(); break;
                //Set-interrupt-flag signal spans 3 cycles: unconditional on the first
                //two, suppress flag decides on the third (AccuracyCoin tests I-L, F-G).
                case 29828:
                    frameIrqFlag = true;
                    frameIrqAssertPending = true;
                    break;
                case 29829:
                    clockQuarterFrame();
                    clockHalfFrame();
                    frameIrqFlag = true;
                    frameIrqAssertPending = true;
                    break;
                case 29830:
                    frameIrqFlag = !irqInhibit;
                    frameIrqAssertPending = true;
                    frameCycle = 0;
                    break;
                default: break;
            }
        } else {
            switch (frameCycle) {
                case 7457: clockQuarterFrame(); break;
                case 14913: clockQuarterFrame(); clockHalfFrame(); break;
                case 22371: clockQuarterFrame(); break;
                case 37281:
                    clockQuarterFrame();
                    clockHalfFrame();
                    break;
                case 37282:
                    frameCycle = 0;
                    break;
                default: break;
            }
        }
    }

    //-----------------------------------------------------------------
    // CPU-facing DMC DMA callback
    //-----------------------------------------------------------------
    public void dmcDmaCompleted(int value) { dmc.dmaCompleted(value); }

    //A pending 1-cycle DMA got dropped (next cycle was a write); clear dmaPending
    //since dmcDmaCompleted() will never be called for it.
    public void dmcDma1CycleDropped() { dmc.dmaPending = false; }

    //Same as dmcDma1CycleDropped() but for a DMA aborted by CPU.cancelDmcDma().
    public void dmcDmaAborted() { dmc.dmaPending = false; }

    //-----------------------------------------------------------------
    // Register interface
    //-----------------------------------------------------------------
    public byte cpuRead(int address) {
        if (address == 0x4015) {
            int val = 0;
            if (pulse1.lengthCounter > 0) val |= 0x01;
            if (pulse2.lengthCounter > 0) val |= 0x02;
            if (triangle.lengthCounter > 0) val |= 0x04;
            if (noise.lengthCounter > 0) val |= 0x08;
            if (dmc.bytesRemaining > 0) val |= 0x10;
            if (frameIrqFlag) val |= 0x40;
            if (dmc.irqFlag) val |= 0x80;
            //Bit 5 is open bus, sourced from the CPU's internal data bus latch (not
            //external) so a concurrent DMC DMA read doesn't leak into it.
            val = (val & ~0x20) | ((cpu != null ? cpu.internalDataBus : 0) & 0x20);

            //Reading $4015 clears the frame IRQ flag, deferred to the next "get" cycle
            //(AccuracyCoin "Frame Counter IRQ" tests 6/7, observable via double-read).
            if (frameIrqFlag) frameIrqClearAfterCycle = (cpu != null) ? cpu.totalCycles : -1;
            return (byte) val;
        }
        //Every other APU register is write-only; caller gets open bus (Bus.cpuRead).
        int ob = cpu != null ? cpu.dataBus : 0;
        if (CPU.debugDma && address == 0x4000) System.err.println("[t="+(cpu!=null?cpu.totalCycles:-1)+"] read $4000 -> "+Integer.toHexString(ob)+" pc="+(cpu!=null?Integer.toHexString(cpu.pc):"?"));
        return (byte) ob;
    }

    public void cpuWrite(int address, byte data) {
        int val = data & 0xFF;
        switch (address) {
            case 0x4000: pulse1.writeReg0(val); break;
            case 0x4001: pulse1.writeReg1(val); break;
            case 0x4002: pulse1.writeReg2(val); break;
            case 0x4003: pulse1.writeReg3(val); break;

            case 0x4004: pulse2.writeReg0(val); break;
            case 0x4005: pulse2.writeReg1(val); break;
            case 0x4006: pulse2.writeReg2(val); break;
            case 0x4007: pulse2.writeReg3(val); break;

            case 0x4008: triangle.writeReg0(val); break;
            case 0x4009: break; //unused
            case 0x400A: triangle.writeTimerLo(val); break;
            case 0x400B: triangle.writeReg3(val); break;

            case 0x400C: noise.writeReg0(val); break;
            case 0x400D: break; //unused
            case 0x400E: noise.writeReg2(val); break;
            case 0x400F: noise.writeReg3(val); break;

            case 0x4010: dmc.writeReg0(val); break;
            case 0x4011: dmc.writeDirectLoad(val); break;
            case 0x4012: dmc.writeSampleAddress(val); break;
            case 0x4013: dmc.writeSampleLength(val); break;

            case 0x4015: {
                pulse1.enabled = (val & 0x01) != 0;
                if (!pulse1.enabled) pulse1.lengthCounter = 0;
                pulse2.enabled = (val & 0x02) != 0;
                if (!pulse2.enabled) pulse2.lengthCounter = 0;
                triangle.enabled = (val & 0x04) != 0;
                if (!triangle.enabled) triangle.lengthCounter = 0;
                noise.enabled = (val & 0x08) != 0;
                if (!noise.enabled) noise.lengthCounter = 0;

                dmc.writeEnableFlag((val & 0x10) != 0);
                dmc.irqFlag = false;
                updateIrqLine();
                break;
            }

            case 0x4017: {
                fiveStepMode = (val & 0x80) != 0;
                irqInhibit = (val & 0x40) != 0;
                if (irqInhibit) { frameIrqClearAfterCycle = -1; frameIrqFlag = false; updateIrqLine(); }
                //Sequencer reset takes effect 3 cycles later on odd write, 4 on even.
                resetDelay = (cpu != null && (cpu.totalCycles & 1) != 0) ? 3 : 4;
                break;
            }

            default: break;
        }
    }

    //-----------------------------------------------------------------
    // Mixer - standard non-linear NES mixing formulas.
    //-----------------------------------------------------------------
    public double getOutputSample() {
        int p1 = pulse1.output();
        int p2 = pulse2.output();
        double pulseOut = (p1 + p2 == 0) ? 0.0 : 95.88 / ((8128.0 / (p1 + p2)) + 100.0);

        int t = triangle.output();
        int n = noise.output();
        int d = dmc.outputLevel;
        double tndOut = (t == 0 && n == 0 && d == 0) ? 0.0
                : 159.79 / (1.0 / ((t / 8227.0) + (n / 12241.0) + (d / 22638.0)) + 100.0);

        return pulseOut + tndOut + (bus != null ? bus.expansionAudio() : 0.0);
    }
}
