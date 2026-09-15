package com.ilker.opendrive.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Synthesised engine note — a few sawtooth harmonics plus intake noise, with
 * the pitch tracking revs. No sample files, so nothing to ship and nothing to
 * decode.
 */
public class EngineSound {

    private static final int SAMPLE_RATE = 22050;
    private static final int CHUNK = 512;

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;

    private volatile float revs = 0.12f;   // 0..1
    private volatile float load = 0f;      // 0..1
    private volatile float volume = 0.55f;

    private double phase1, phase2, phase3;
    private float noiseState;
    private int noiseSeed = 12345;

    @SuppressWarnings("deprecation")
    public synchronized void start() {
        if (running) return;
        try {
            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = SAMPLE_RATE / 4;
            int bufSize = Math.max(minBuf, CHUNK * 8);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufSize, AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                return;
            }
            track.play();
        } catch (Throwable t) {
            track = null;
            return;
        }

        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                render();
            }
        }, "engine-audio");
        thread.setDaemon(true);
        thread.start();
    }

    public void setState(float revs, float load) {
        this.revs = clamp(revs, 0f, 1f);
        this.load = clamp(load, 0f, 1f);
    }

    public void setVolume(float v) {
        this.volume = clamp(v, 0f, 1f);
    }

    private void render() {
        short[] buffer = new short[CHUNK];
        float smoothedRevs = revs;
        while (running) {
            AudioTrack t = track;
            if (t == null) break;
            float targetRevs = revs;
            float amp = volume * (0.30f + 0.55f * load);

            for (int i = 0; i < CHUNK; i++) {
                smoothedRevs += (targetRevs - smoothedRevs) * 0.0016f;
                double baseHz = 34.0 + smoothedRevs * 175.0;
                double step1 = baseHz / SAMPLE_RATE;
                phase1 += step1;
                phase2 += step1 * 2.0;
                phase3 += step1 * 3.02;
                if (phase1 > 1.0) phase1 -= 1.0;
                if (phase2 > 1.0) phase2 -= 1.0;
                if (phase3 > 1.0) phase3 -= 1.0;

                float s1 = (float) (phase1 * 2.0 - 1.0);
                float s2 = (float) (phase2 * 2.0 - 1.0);
                float s3 = (float) (phase3 * 2.0 - 1.0);

                noiseSeed = noiseSeed * 1103515245 + 12345;
                float white = ((noiseSeed >> 16) & 0x7fff) / 16384f - 1f;
                noiseState += (white - noiseState) * 0.22f;

                float sample = s1 * 0.55f + s2 * 0.26f + s3 * 0.14f
                        + noiseState * (0.05f + 0.10f * smoothedRevs);
                sample *= amp;
                // Soft clip keeps it warm rather than crunchy.
                if (sample > 1f) sample = 1f;
                if (sample < -1f) sample = -1f;
                sample = sample - (sample * sample * sample) / 3f;
                buffer[i] = (short) (sample * 22000f);
            }

            try {
                t.write(buffer, 0, CHUNK);
            } catch (Throwable e) {
                break;
            }
        }
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        AudioTrack a = track;
        track = null;
        if (a != null) {
            try {
                a.stop();
            } catch (Throwable ignored) {
            }
            try {
                a.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
