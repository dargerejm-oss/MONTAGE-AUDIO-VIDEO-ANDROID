package com.jmdargere.montage;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;

/** Bords assombris (effet vignette). */
@UnstableApi
final class Vignette extends BitmapOverlay {
    private final Bitmap bmp;

    Vignette(int w, int h) {
        w = Math.max(2, w);
        h = Math.max(2, h);
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint();
        float r = (float) Math.hypot(w, h) / 2f;
        p.setShader(new RadialGradient(w / 2f, h / 2f, r,
                new int[]{0x00000000, 0x00000000, 0xAA000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
    }

    @Override
    public Bitmap getBitmap(long presentationTimeUs) {
        return bmp;
    }
}
