package com.jmdargere.montage;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.RgbMatrix;

/** Filtres de couleur : noir et blanc, sepia, chaud, froid, vintage. */
@UnstableApi
final class ColorFx implements RgbMatrix {
    private final float[] m;

    ColorFx(float[] colMajor) {
        this.m = colMajor;
    }

    static float[] forName(String f) {
        if (f == null) {
            return null;
        }
        switch (f) {
            case "bw":
                return rows(.299f, .587f, .114f, .299f, .587f, .114f, .299f, .587f, .114f);
            case "sepia":
                return rows(.393f, .769f, .189f, .349f, .686f, .168f, .272f, .534f, .131f);
            case "vintage": {
                float[] s = {.393f, .769f, .189f, .349f, .686f, .168f, .272f, .534f, .131f};
                float[] id = {1, 0, 0, 0, 1, 0, 0, 0, 1};
                float[] r = new float[9];
                for (int i = 0; i < 9; i++) {
                    r[i] = 0.6f * s[i] + 0.4f * id[i];
                }
                return rows(r);
            }
            case "warm":
                return rows(1.1f, 0, 0, 0, 1f, 0, 0, 0, .9f);
            case "cool":
                return rows(.9f, 0, 0, 0, 1f, 0, 0, 0, 1.1f);
            default:
                return null;
        }
    }

    /** 9 valeurs (matrice 3x3 ligne par ligne) -> matrice 4x4 en colonnes. */
    private static float[] rows(float... r) {
        float[] o = new float[16];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                o[col * 4 + row] = r[row * 3 + col];
            }
        }
        o[15] = 1f;
        return o;
    }

    @Override
    public float[] getMatrix(long presentationTimeUs, boolean useHdr) {
        return m;
    }
}
