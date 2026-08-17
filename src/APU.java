//Full 2A03 APU: 2 pulse channels, triangle, noise, DMC, and the frame sequencer that
//drives their length counters/envelopes/sweep/linear counter. getOutputSample() mixes
//them down per the standard non-linear NES formulas; clock() downsamples that to
//44100Hz and pushes it to the connected AudioPlayer (see connectAudioPlayer). Every
//register and the DMC's DMA/IRQ interaction with the CPU is modeled.
public class APU {

    private CPU cpu;
    public void connectCPU(CPU cpu) { this.cpu = cpu; }

    private AudioPlayer audioPlayer;
    public void connectAudioPlayer(AudioPlayer audioPlayer) { this.audioPlayer = audioPlayer; }

    //Downsamples the ~1.789773MHz CPU/APU clock down to the audio device's 44100Hz by
    //averaging every mixer sample produced between two output samples (a cheap
    //box-filter anti-alias) and emitting one output sample whenever enough CPU cycles
    //have accumulated - tracked with a running remainder so the 40.6:1 ratio doesn't
    //drift over time.
    private static final double CPU_HZ = 1789773.0;
    private static final double SAMPLE_HZ = 44100.0;
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
        int volume; //also doubles as the envelope's own divider period
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
                timerValue = NOISE_PERIOD_TABLE[periodIndex];
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

        //Cycles left until a pending restart (see writeEnableFlag()) actually reloads
        //currentAddress/bytesRemaining; 0 means no restart is pending.
        int enableDelay = 0;

        void writeReg0(int val) {
            irqEnable = (val & 0x80) != 0;
            loop = (val & 0x40) != 0;
            rateIndex = val & 0x0F;
            if (!irqEnable) { irqFlag = false; updateIrqLine(); }
        }

        void writeDirectLoad(int val) { outputLevel = val & 0x7F; }
        void writeSampleAddress(int val) { sampleAddress = 0xC000 + (val * 64); }
        void writeSampleLength(int val) { sampleLength = (val * 16) + 1; }

        //$4015's DMC-enable bit doesn't restart a stopped sample the instant it's
        //written - real hardware's restart takes effect 3 CPU cycles later (see
        //AccuracyCoin's Delta Modulation Channel tests L/M/N: "The Delta Modulation
        //Channel will be enabled in 3 CPU cycles"). The gating condition for whether
        //a write restarts anything at all is bytesRemaining==0 - true both right after
        //an explicit disable AND right after a non-looping sample plays out its last
        //byte and fires its IRQ (dmaCompleted() leaves bytesRemaining at 0 in that
        //case too) - NOT a separate persistent "was this channel ever enabled" latch;
        //DMASync_TheGoodOne (and every AccuracyCoin test built on it) re-triggers this
        //exact restart path many times over a run, each time relying on whatever
        //sample was previously looping/playing having already reached bytesRemaining
        //0 by then. While enableDelay counts down, bytesRemaining stays 0, so the
        //ordinary "if (bytesRemaining>0) requestDma()" checks in clockTimer() below
        //already retry every cycle for free once it's reloaded - no extra state needed.
        void writeEnableFlag(boolean enable) {
            if (!enable) {
                bytesRemaining = 0;
                enableDelay = 0;
                //Explicit DMA Abort: disabling the channel while a DMA request is still
                //pending or mid-halt on the CPU side must cancel it there too, not just
                //here - see CPU.cancelDmcDma().
                if (cpu != null) cpu.cancelDmcDma();
            } else if (bytesRemaining == 0 && enableDelay == 0) {
                enableDelay = 3;
            }
        }

        void requestDma() {
            if (dmaPending || bytesRemaining == 0) return;
            dmaPending = true;
            if (cpu != null) cpu.requestDmcDma(currentAddress);
        }

        void dmaCompleted(int value) {
            dmaPending = false;
            //If the channel was disabled (writeEnableFlag(false)) while this DMA was
            //already in flight, bytesRemaining was forced to 0 out from under it - the
            //fetched byte must be discarded rather than committed to the buffer/address
            //counter, otherwise bytesRemaining-- below goes negative and permanently
            //wedges the channel (writeEnableFlag(true)'s restart condition never sees
            //bytesRemaining==0 again). See CPU.cancelDmcDma() for the matching CPU-side
            //abort of the DMA's own halt cycles.
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
                } else if (irqEnable) {
                    irqFlag = true;
                    updateIrqLine();
                }
            }
        }

        void clockTimer() {
            //Runs every CPU cycle regardless of timerValue's phase - see
            //writeEnableFlag() for why this delay exists and why no extra retry
            //state is needed beyond the ordinary requestDma() calls below.
            if (enableDelay > 0) {
                enableDelay--;
                if (enableDelay == 0) {
                    currentAddress = sampleAddress;
                    bytesRemaining = sampleLength;
                    if (!bufferHasData) requestDma();
                }
            }

            if (timerValue == 0) {
                //Unlike Pulse/Triangle's timerPeriod (a raw register value that's already
                //"hardware period - 1" by convention, so this same reload-then-count-to-zero
                //shape correctly yields timerPeriod+1 real cycles), DMC_RATE_TABLE's entries
                //ARE the exact CPU-cycle interval between output clocks. Reloading with the
                //raw table value here would make every DMC output clock take one cycle too
                //long, which is invisible in most tests (they leave slack around the DMA) but
                //fatal to AccuracyCoin's Interrupt Flag Latency test 8, which needs the DMC's
                //sample-end DMA/IRQ to land on one exact CPU cycle.
                timerValue = DMC_RATE_TABLE[rateIndex] - 1;

                if (bitsRemaining == 0) {
                    bitsRemaining = 8;
                    if (bufferHasData) {
                        shiftRegister = bufferByte;
                        bufferHasData = false;
                        silence = false;
                        if (bytesRemaining > 0) requestDma();
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
        }
    }

    private Pulse pulse1 = new Pulse(true);
    private Pulse pulse2 = new Pulse(false);
    private Triangle triangle = new Triangle();
    private Noise noise = new Noise();
    private Dmc dmc = new Dmc();

    //Full power-on reset: re-creates every channel from scratch (clearing timers,
    //envelopes, length counters, the DMC's DMA/IRQ state, etc.) and re-zeroes the
    //frame sequencer and audio downsampler. Only used by the TAS Maker's "replay
    //from power-on" seeking - normal gameplay never calls this, since a real NES
    //reset button doesn't touch the APU this thoroughly.
    public void reset() {
        pulse1 = new Pulse(true);
        pulse2 = new Pulse(false);
        triangle = new Triangle();
        noise = new Noise();
        dmc = new Dmc();
        fiveStepMode = false;
        irqInhibit = false;
        frameIrqFlag = false;
        frameCycle = 0;
        resetDelay = 0;
        apuCycleToggle = false;
        sampleCycleAccumulator = 0;
        outputAccum = 0;
        outputCount = 0;
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
        if (frameIrqFlag || dmc.irqFlag) cpu.raiseIRQ();
        else cpu.clearIRQ();
    }

    //Advances every part of the APU by one CPU cycle.
    public void clock() {
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

        //Triangle and DMC are clocked directly off the CPU clock; only pulse/noise use
        //the divide-by-2 "APU cycle" (their period tables are already halved to
        //account for it, DMC's and triangle's are not).
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
        sampleCycleAccumulator += SAMPLE_HZ;
        if (sampleCycleAccumulator >= CPU_HZ) {
            sampleCycleAccumulator -= CPU_HZ;
            if (audioPlayer != null) audioPlayer.writeSample(outputAccum / outputCount);
            outputAccum = 0;
            outputCount = 0;
        }
    }

    private void stepFrameSequencer() {
        if (!fiveStepMode) {
            switch (frameCycle) {
                case 7457: clockQuarterFrame(); break;
                case 14913: clockQuarterFrame(); clockHalfFrame(); break;
                case 22371: clockQuarterFrame(); break;
                case 29828:
                    if (!irqInhibit) { frameIrqFlag = true; updateIrqLine(); }
                    break;
                case 29829:
                    clockQuarterFrame();
                    clockHalfFrame();
                    if (!irqInhibit) { frameIrqFlag = true; updateIrqLine(); }
                    break;
                case 29830:
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
    //Called by CPU.cancelDmcDma() when an in-flight DMA gets explicitly aborted before
    //reaching its "get" cycle - clears the pending-request latch WITHOUT touching the
    //buffer/address/length state that dmaCompleted() would (there's no fetched byte to
    //commit). Without this, dmaPending would stay stuck true forever, permanently
    //deadlocking every future requestDma() call (which no-ops while dmaPending is set).
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
            //Bit 5 is open bus - it isn't driven by anything in this register.
            val = (val & ~0x20) | ((cpu != null ? cpu.dataBus : 0) & 0x20);

            //Reading $4015 clears the frame IRQ flag (but not the DMC IRQ flag).
            frameIrqFlag = false;
            updateIrqLine();
            return (byte) val;
        }
        //Every other APU register is write-only; CPU.read() is responsible for not
        //letting this echo actually update the external data bus for $4015, and for
        //every other address the caller already gets true open bus from Bus.cpuRead's
        //fallthrough (this method is only reached for $4000-$4015 - see Bus.cpuRead).
        return (byte) (cpu != null ? cpu.dataBus : 0);
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
                if (irqInhibit) { frameIrqFlag = false; updateIrqLine(); }
                //The sequencer reset takes effect 3 CPU cycles later if this write
                //landed on an odd CPU cycle, 4 cycles later if even.
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

        return pulseOut + tndOut;
    }
}
