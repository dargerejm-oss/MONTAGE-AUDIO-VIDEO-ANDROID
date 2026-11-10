package com.jmdargere.montage;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;

/** Volume, apparition / disparition en fondu, equilibre gauche-droite, graves et aigus. */
@UnstableApi
final class AudioFx extends BaseAudioProcessor {
    private final float gain;
    private final float pan;
    private final long inUs;
    private final long outUs;
    private final long totalUs;
    private final float bass;
    private final float treble;
    private int sr = 44100;
    private int ch = 2;
    private float aB = 0.03f;
    private float aT = 0.3f;
    private final float[] lpB = new float[8];
    private final float[] lpT = new float[8];
    private long frames = 0;
    private int chIdx = 0;

    AudioFx(float gain, float pan, long inUs, long outUs, long totalUs, float bassDb, float trebleDb) {
        this.gain = gain;
        this.pan = Math.max(-1f, Math.min(1f, pan));
        this.inUs = inUs;
        this.outUs = outUs;
        this.totalUs = totalUs;
        this.bass = (float) Math.pow(10.0, bassDb / 20.0) - 1f;
        this.treble = (float) Math.pow(10.0, trebleDb / 20.0) - 1f;
    }

    @Override
    public AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat f)
            throws AudioProcessor.UnhandledAudioFormatException {
        if (f.encoding != C.ENCODING_PCM_16BIT) {
            throw new AudioProcessor.UnhandledAudioFormatException(f);
        }
        sr = f.sampleRate;
        ch = Math.max(1, Math.min(8, f.channelCount));
        aB = 1f - (float) Math.exp(-2.0 * Math.PI * 200.0 / sr);
        aT = 1f - (float) Math.exp(-2.0 * Math.PI * 3000.0 / sr);
        return f;
    }

    @Override
    public void queueInput(ByteBuffer in) {
        int n = in.remaining();
        ByteBuffer out = replaceOutputBuffer(n);
        float g = gain;
        boolean eq = Math.abs(bass) > 0.01f || Math.abs(treble) > 0.01f;
        while (in.remaining() >= 2) {
            if (chIdx == 0) {
                double tUs = frames * 1000000.0 / sr;
                g = gain;
                if (inUs > 0 && tUs < inUs) {
                    g *= (float) (tUs / inUs);
                }
                if (outUs > 0 && totalUs > 0 && tUs > totalUs - outUs) {
                    g *= (float) Math.max(0.0, (totalUs - tUs) / outUs);
                }
            }
            float x = in.getShort();
            if (eq) {
                lpB[chIdx] += aB * (x - lpB[chIdx]);
                lpT[chIdx] += aT * (x - lpT[chIdx]);
                x = x + bass * lpB[chIdx] + treble * (x - lpT[chIdx]);
            }
            x *= g;
            if (ch == 2) {
                if (chIdx == 0 && pan > 0f) {
                    x *= 1f - pan;
                } else if (chIdx == 1 && pan < 0f) {
                    x *= 1f + pan;
                }
            }
            if (x > 32767f) {
                x = 32767f;
            } else if (x < -32768f) {
                x = -32768f;
            }
            out.putShort((short) x);
            chIdx++;
            if (chIdx >= ch) {
                chIdx = 0;
                frames++;
            }
        }
        out.flip();
    }
}
