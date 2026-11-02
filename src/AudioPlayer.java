import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

//Pushes the APU's raw mixer output (see APU.getOutputSample) to the default audio device through a one-pole
//high-pass filter, mirroring the RC coupling that turns the NES DAC's positive-only output into a waveform.
//writeSample() runs on the emulation thread and must never block, so samples go into a ring buffer that a
//dedicated playback thread drains into the SourceDataLine (blocking there used to freeze emulation for seconds).
public class AudioPlayer {

    private static final float SAMPLE_RATE = 44100f;
    private static final double HIGH_PASS_R = 0.996;
    private static final int RING_CAPACITY_BYTES = 32768; //~370ms at 44100Hz/16-bit mono
    //Device-side buffer. Bigger than the queue we aim to keep so line.write() only
    //blocks (and the ring only fills) when emulation genuinely runs ahead.
    private static final int LINE_BUFFER_BYTES = 16384;
    //Audio to keep queued (ring + device), ~60ms: enough to ride out a late frame or GC pause without feeling laggy.
    //The APU steers its output rate toward this (APU.adjustOutputRate) and playbackLoop primes the device to it after an underrun.
    public static final int TARGET_QUEUE_SAMPLES = 2646;
    private static final byte[] PRIME_SILENCE = new byte[TARGET_QUEUE_SAMPLES * 2];

    private final byte[] ring = new byte[RING_CAPACITY_BYTES];
    private final Object ringLock = new Object();
    private int writePos = 0;
    private int readPos = 0;
    private int available = 0; //bytes currently queued for playback

    private volatile boolean running = false;
    //When true, writeSample still runs the high-pass filter (no click on unmute) but drops the sample.
    //The TAS Maker uses this to silence frames that are only being re-derived (seeking, scrubbing).
    private volatile boolean muted = false;
    private Thread playbackThread;
    private volatile SourceDataLine line;
    //Guards every direct SourceDataLine call so flush() can't race the playback thread's write() - see flush().
    private final Object lineLock = new Object();

    //Times the device ran completely dry (includes the deliberate prime after
    //start/flush). Diagnostic only.
    private volatile int underruns = 0;
    public int getUnderrunCount() { return underruns; }

    private double prevInput = 0.0;
    private double prevOutput = 0.0;

    public synchronized void start() {
        if (running) return;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, LINE_BUFFER_BYTES);
            line.start();
        } catch (LineUnavailableException e) {
            System.err.println("Audio device unavailable, running without sound: " + e.getMessage());
            line = null;
            return;
        }

        running = true;
        writePos = 0;
        readPos = 0;
        available = 0;
        playbackThread = new Thread(this::playbackLoop, "NES-Audio");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        synchronized (ringLock) { ringLock.notifyAll(); }
        try {
            if (playbackThread != null) playbackThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lineLock) {
            if (line != null) {
                line.drain();
                line.stop();
                line.close();
                line = null;
            }
        }
    }

    //Runs on its own thread: blocks on the ring buffer (not the emulation thread) and
    //on the audio device, batching drained bytes into one write() call at a time.
    private void playbackLoop() {
        byte[] chunk = new byte[4096];
        while (running) {
            int count;
            synchronized (ringLock) {
                while (available == 0 && running) {
                    try { ringLock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (!running) return;
                count = Math.min(available, chunk.length);
                for (int i = 0; i < count; i++) {
                    chunk[i] = ring[readPos];
                    readPos = (readPos + 1) % ring.length;
                }
                available -= count;
                ringLock.notifyAll();
            }
            synchronized (lineLock) {
                if (!running) return;
                //Device fully drained (first write after start/flush, or a real underrun): prime with silence
                //so playback resumes with headroom instead of at zero latency where every hiccup is an audible gap.
                if (line.available() >= line.getBufferSize()) {
                    underruns++;
                    line.write(PRIME_SILENCE, 0, PRIME_SILENCE.length);
                }
                line.write(chunk, 0, count);
            }
        }
    }

    //Samples queued but not yet played (ring + device), or -1 with no usable line. Deliberately avoids lineLock:
    //the playback thread may hold it inside a blocking write(), and the emulation thread must never wait on that.
    public int getQueuedSamples() {
        SourceDataLine l = line;
        if (!running || l == null) return -1;
        int inLine;
        try {
            inLine = l.getBufferSize() - l.available();
        } catch (RuntimeException e) {
            return -1;
        }
        if (inLine < 0) inLine = 0;
        int inRing;
        synchronized (ringLock) { inRing = available; }
        return (inRing + inLine) / 2;
    }

    //sample is the raw non-negative APU mixer output (~0.0-1.0); never blocks, dropping the oldest queued audio instead.
    //Going unmuted->muted also flushes the queue: the TAS Maker can burst many frames of audio while scrubbing, and
    //without the flush that backlog keeps playing ("stacking up") long after the emulator has moved on.
    public void setMuted(boolean muted) {
        boolean wasMuted = this.muted;
        this.muted = muted;
        if (muted && !wasMuted) flush();
    }

    //Discards everything queued, including bytes already inside the SourceDataLine's own buffer - on Windows some
    //DirectSound lines repeat their last buffer when starved, which made audio loop forever after quick scrubbing.
    //SourceDataLine.flush() is only predictable while stopped, so it's bracketed with stop()/start() under lineLock.
    public void flush() {
        synchronized (ringLock) {
            available = 0;
            readPos = writePos;
            ringLock.notifyAll();
        }
        synchronized (lineLock) {
            if (line != null) {
                line.stop();
                line.flush();
                line.start();
            }
        }
    }

    public void writeSample(double sample) {
        if (!running) return;

        double filtered = sample - prevInput + HIGH_PASS_R * prevOutput;
        prevInput = sample;
        prevOutput = filtered;

        if (muted) return;

        int pcm = (int) Math.round(filtered * 32767.0 * 2.0);
        if (pcm > 32767) pcm = 32767;
        if (pcm < -32768) pcm = -32768;

        byte low = (byte) (pcm & 0xFF);
        byte high = (byte) ((pcm >> 8) & 0xFF);

        synchronized (ringLock) {
            if (available + 2 > ring.length) {
                //Buffer overrun (emulation running ahead of playback) - drop the
                //oldest sample instead of blocking or growing unbounded.
                readPos = (readPos + 2) % ring.length;
                available -= 2;
            }
            ring[writePos] = low;
            writePos = (writePos + 1) % ring.length;
            ring[writePos] = high;
            writePos = (writePos + 1) % ring.length;
            available += 2;
            ringLock.notifyAll();
        }
    }
}
