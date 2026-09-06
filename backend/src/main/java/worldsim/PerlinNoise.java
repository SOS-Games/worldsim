package worldsim;

/**
 * 2D Perlin noise. Values are roughly in {@code [-1, 1]}.
 */
final class PerlinNoise {

    private final int[] perm = new int[512];

    PerlinNoise(long seed) {
        int[] source = new int[256];
        for (int i = 0; i < 256; i++) {
            source[i] = i;
        }
        long state = seed;
        for (int i = 255; i > 0; i--) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int j = (int) Long.remainderUnsigned(state >>> 32, i + 1);
            int tmp = source[i];
            source[i] = source[j];
            source[j] = tmp;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = source[i & 255];
        }
    }

    double eval(double x, double y) {
        int x0 = floor(x);
        int y0 = floor(y);
        double xf = x - x0;
        double yf = y - y0;
        int xi = x0 & 255;
        int yi = y0 & 255;
        double u = fade(xf);
        double v = fade(yf);
        int aa = perm[perm[xi] + yi];
        int ab = perm[perm[xi] + yi + 1];
        int ba = perm[perm[xi + 1] + yi];
        int bb = perm[perm[xi + 1] + yi + 1];
        double x1 = lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u);
        double x2 = lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u);
        return lerp(x1, x2, v);
    }

    /** Fractal Brownian motion scaled to {@code [0, 1]}. */
    double fbm01(double x, double y, int octaves) {
        double sum = 0;
        double amp = 1;
        double freq = 1;
        double norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * eval(x * freq, y * freq);
            norm += amp;
            amp *= 0.5;
            freq *= 2;
        }
        return (sum / norm + 1) * 0.5;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y) {
        return ((hash & 1) == 0 ? x : -x) + ((hash & 2) == 0 ? y : -y);
    }
}
