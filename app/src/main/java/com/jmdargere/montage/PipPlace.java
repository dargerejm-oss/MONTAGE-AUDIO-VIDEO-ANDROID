package com.jmdargere.montage;

import android.graphics.Matrix;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;

/** Image dans l'image : reduit et place dans un coin (0 haut-gauche, 1 haut-droite, 2 bas-gauche, 3 bas-droite, 4 centre). */
@UnstableApi
final class PipPlace implements MatrixTransformation {
    private final float s;
    private final float tx;
    private final float ty;

    PipPlace(float scale, int pos) {
        this.s = Math.max(0.1f, Math.min(1f, scale));
        float room = Math.max(0f, 1f - s - 0.04f);
        if (pos == 4) {
            tx = 0f;
            ty = 0f;
        } else {
            tx = (pos % 2 == 0 ? -1f : 1f) * room;
            ty = (pos < 2 ? 1f : -1f) * room;
        }
    }

    @Override
    public Matrix getMatrix(long presentationTimeUs) {
        Matrix m = new Matrix();
        m.postScale(s, s);
        m.postTranslate(tx, ty);
        return m;
    }
}
