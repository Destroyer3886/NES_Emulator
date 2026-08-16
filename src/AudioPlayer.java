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
    private Thread playbackThread;
    private SourceDataLine line;

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
        if (line != null) {
            line.drain();
            line.stop();
            line.close();
            line = null;
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
            line.write(chunk, 0, count);
        }
    }

    //sample is the raw non-negative APU mixer output (roughly 0.0-1.0). Never blocks -
    //if the playback thread can't keep up, oldest queued audio is dropped rather than
    //stalling the caller (the emulation thread).
    public void writeSample(double sample) {
        if (!running) return;

        double filtered = sample - prevInput + HIGH_PASS_R * prevOutput;
        prevInput = sample;
        prevOutput = filtered;

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
