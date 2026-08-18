import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

//Takes the APU's raw mixer output (see APU.getOutputSample) and pushes it to the
//system's default audio device. The NES DAC output only ever swings positive (it's
//AC-coupled to the amplifier by an RC filter on real hardware), so a one-pole
//high-pass filter here does the same job in software - without it, every channel
//would just sound like a volume level rather than a waveform.
//
//writeSample() is called from the emulation thread once per output sample and must
//never block: SourceDataLine.write() blocks whenever the device's internal buffer is
//full, and blocking there would stall the CPU/PPU emulation itself (this is what used
//to cause multi-second freezes whenever emulation got even slightly ahead of
//real-time audio playback). Instead, samples are pushed into a small ring buffer and
//a dedicated playback thread drains it into the line, so any blocking only ever
//happens off the emulation thread.
public class AudioPlayer {

    private static final float SAMPLE_RATE = 44100f;
    private static final double HIGH_PASS_R = 0.996;
    private static final int RING_CAPACITY_BYTES = 16384; //~185ms at 44100Hz/16-bit mono

    private final byte[] ring = new byte[RING_CAPACITY_BYTES];
    private final Object ringLock = new Object();
    private int writePos = 0;
    private int readPos = 0;
    private int available = 0; //bytes currently queued for playback

    private volatile boolean running = false;
    //When true, writeSample still runs the high-pass filter (so audio doesn't click
    //when unmuted mid-stream) but drops the sample instead of queuing it. Used by the
    //TAS Maker to silence replay of frames that are just being re-derived (seeking,
    //scrubbing, already-validated frames) rather than actually played back.
    private volatile boolean muted = false;
    private Thread playbackThread;
    private SourceDataLine line;
    //Guards every direct call into the SourceDataLine (write/stop/flush/start) so
    //flush() can't race the playback thread's write() - see flush()'s comment for why
    //that race is exactly what caused audio to stack up and loop on Windows.
    private final Object lineLock = new Object();

    private double prevInput = 0.0;
    private double prevOutput = 0.0;

    public synchronized void start() {
        if (running) return;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, 4096);
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
                if (running) line.write(chunk, 0, count);
            }
        }
    }

    //sample is the raw non-negative APU mixer output (roughly 0.0-1.0). Never blocks -
    //if the playback thread can't keep up, oldest queued audio is dropped rather than
    //stalling the caller (the emulation thread).
    //Muting alone only stops NEW samples from being queued - anything already sitting
    //in the ring buffer keeps draining out at real-device speed regardless. That's
    //fine for a brief mute, but the TAS Maker can generate many frames' worth of audio
    //in a tight, non-real-time burst (a fast scroll, a long backward replay) - without
    //also dropping whatever's already queued, that backlog keeps playing back (sounding
    //like everything is "stacking up" and repeating) long after the emulator has moved
    //on, and the growing backlog is itself extra work outside the emulation thread.
    //So going from unmuted to muted always flushes the queue too - nothing queued
    //while unmuted is expected to still matter once muted.
    public void setMuted(boolean muted) {
        boolean wasMuted = this.muted;
        this.muted = muted;
        if (muted && !wasMuted) flush();
    }

    //Discards whatever's currently queued for playback. Clearing the software ring
    //buffer alone isn't enough - any bytes already handed to line.write() are sitting
    //in the SourceDataLine's own internal/device buffer, outside our control, and
    //clearing the ring does nothing to them. On Windows, once that leftover audio
    //finishes playing, some DirectSound-backed lines don't just go silent when
    //starved of new data - they repeat the last buffer they were given, which is
    //exactly what made audio "stack up" and loop forever after a burst of quick
    //scrubbing. SourceDataLine.flush() is documented to give predictable results only
    //while stopped, so bracket it with stop()/start() rather than calling it on a
    //running line - and hold lineLock throughout so this can't interleave with the
    //playback thread's own line.write() call.
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
