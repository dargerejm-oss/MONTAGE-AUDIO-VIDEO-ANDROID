package com.jmdargere.montage;

import android.graphics.Matrix;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;

/** Zoom lent (et petit deplacement) sur toute la duree d'une photo. */
@UnstableApi
final class KenBurns implements MatrixTransformation {
    private final long durUs;
    private final long offUs;
    private final boolean zoomIn;
    private final float dirX;
    private final float dirY;

    KenBurns(long durUs, int index, long offUs) {
        this.durUs = Math.max(1, durUs);
        this.offUs = offUs;
        this.zoomIn = index % 2 == 0;
        int k = index % 4;
        this.dirX = (k == 0 || k == 3) ? 1f : -1f;
        this.dirY = (k == 0 || k == 1) ? 1f : -1f;
    }

    @Override
    public Matrix getMatrix(long presentationTimeUs) {
        float p = Math.max(0f, Math.min(1f, (float) (presentationTimeUs + offUs) / (float) durUs));
        if (!zoomIn) {
            p = 1f - p;
        }
        float s = 1f + 0.18f * p;
        float pan = (s - 1f) * 0.6f;
        Matrix m = new Matrix();
        m.postScale(s, s);
        m.postTranslate(dirX * pan, dirY * pan);
        return m;
    }
}
