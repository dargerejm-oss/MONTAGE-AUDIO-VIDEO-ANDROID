package com.jmdargere.montage;

import android.graphics.Matrix;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;

/** Entree de l'element suivant par-dessus le precedent : 0 glisse depuis la droite, 1 depuis le bas, 2 zoom. */
@UnstableApi
final class SlideIn implements MatrixTransformation {
    private final long durUs;
    private final int mode;

    SlideIn(long durUs, int mode) {
        this.durUs = Math.max(1, durUs);
        this.mode = mode;
    }

    @Override
    public Matrix getMatrix(long presentationTimeUs) {
        float p = Math.max(0f, Math.min(1f, (float) presentationTimeUs / (float) durUs));
        p = p * p * (3f - 2f * p);
        Matrix m = new Matrix();
        if (mode == 0) {
            m.postTranslate(2f * (1f - p), 0f);
        } else if (mode == 1) {
            m.postTranslate(0f, -2f * (1f - p));
        } else {
            float s = Math.max(0.02f, p);
            m.postScale(s, s);
        }
        return m;
    }
}
