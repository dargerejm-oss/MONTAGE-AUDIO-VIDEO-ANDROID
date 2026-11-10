package com.jmdargere.montage;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlaySettings;

/**
 * Fondu : un voile noir qui s'efface au debut du clip et revient a la fin
 * (le clip precedent s'eteint, le suivant apparait).
 */
@UnstableApi
final class FadeOverlay extends BitmapOverlay {
    private final Bitmap black;
    private final long inUs;
    private final long outUs;
    private final long durUs;

    FadeOverlay(int w, int h, long inUs, long outUs, long durUs) {
        this.black = Bitmap.createBitmap(Math.max(2, w), Math.max(2, h), Bitmap.Config.ARGB_8888);
        this.black.eraseColor(Color.BLACK);
        this.inUs = inUs;
        this.outUs = outUs;
        this.durUs = durUs;
    }

    @Override
    public Bitmap getBitmap(long presentationTimeUs) {
        return black;
    }

    @Override
    public OverlaySettings getOverlaySettings(long t) {
        float a = 0f;
        if (inUs > 0 && t < inUs) {
            a = 1f - (float) t / (float) inUs;
        }
        if (outUs > 0 && t > durUs - outUs) {
            a = Math.max(a, (float) (t - (durUs - outUs)) / (float) outUs);
        }
        a = Math.max(0f, Math.min(1f, a));
        return new OverlaySettings.Builder().setAlphaScale(a).build();
    }
}
