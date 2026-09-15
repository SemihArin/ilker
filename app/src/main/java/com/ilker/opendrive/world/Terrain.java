package com.ilker.opendrive.world;

/**
 * The deterministic definition of the world. Everything — terrain height,
 * biome, city density, where the roads run — is a pure function of world
 * coordinates, so chunks can be generated in any order, on any thread, and
 * always agree with each other.
 */
public final class Terrain {

    /** Side length of one chunk / city block, in metres. */
    public static final float CHUNK = 100f;
    /** Spacing of terrain mesh vertices. CHUNK must be a whole multiple of it. */
    public static final float GRID = 6.25f;
    /** Terrain mesh cells along one chunk edge. */
    public static final int CELLS = (int) (CHUNK / GRID);

    /** Half width of the asphalt. */
    public static final float ROAD_HALF = 7.5f;
    /** Half width including kerb and pavement. */
    public static final float PAVEMENT_HALF = 10.5f;
    /** Centre of a driving lane, measured from the road centreline. */
    public static final float LANE_OFFSET = 3.6f;

    /** How far the tarmac sits above the raw landscape. */
    public static final float ROAD_SURFACE_LIFT = 0.16f;
    /** Extra step up onto the kerb. */
    public static final float KERB_HEIGHT = 0.14f;

    private Terrain() {
    }

    // ----------------------------------------------------------------- noise

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= (h >>> 16);
        return (h & 0x7fffffff) / 2147483647f;
    }

    public static float rand(int x, int y, int seed) {
        return hash(x, y, seed);
    }

    private static float valueNoise(float x, float y, int seed) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        float xf = x - xi;
        float yf = y - yi;
        float u = xf * xf * (3f - 2f * xf);
        float v = yf * yf * (3f - 2f * yf);
        float a = hash(xi, yi, seed);
        float b = hash(xi + 1, yi, seed);
        float c = hash(xi, yi + 1, seed);
        float d = hash(xi + 1, yi + 1, seed);
        float top = a + (b - a) * u;
        float bottom = c + (d - c) * u;
        return top + (bottom - top) * v;
    }

    private static float fbm(float x, float y, int seed, int octaves) {
        float sum = 0f;
        float amp = 0.5f;
        float norm = 0f;
        for (int i = 0; i < octaves; i++) {
            sum += valueNoise(x, y, seed + i * 17) * amp;
            norm += amp;
            x *= 2.03f;
            y *= 2.03f;
            amp *= 0.5f;
        }
        return sum / norm;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = (x - edge0) / (edge1 - edge0);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }

    // ----------------------------------------------------------------- biome

    /** 0 = open countryside, 1 = dense city centre. */
    public static float urban(float x, float z) {
        float n = fbm(x * 0.00062f, z * 0.00062f, 101, 3);
        return smoothstep(0.46f, 0.66f, n);
    }

    /** 0 = lush green, 1 = dry sandy scrubland. */
    public static float dryness(float x, float z) {
        return smoothstep(0.40f, 0.72f, fbm(x * 0.00031f, z * 0.00031f, 555, 2));
    }

    // ---------------------------------------------------------------- height

    /** Height of the underlying landscape, before mesh quantisation. */
    public static float rawHeight(float x, float z) {
        float h = (fbm(x * 0.00135f, z * 0.00135f, 7, 4) - 0.5f) * 30f;
        h += (fbm(x * 0.0062f, z * 0.0062f, 23, 2) - 0.5f) * 3.4f;
        // Towns were built on the flat ground, so flatten as the city takes over.
        float u = urban(x, z);
        h *= (1f - 0.78f * u);
        return h;
    }

    /**
     * Height of the terrain <em>as drawn</em>. The mesh is a grid of flat
     * quads, so anything that has to sit exactly on the ground — roads, kerbs,
     * trees, wheels — must sample the same bilinear interpolation rather than
     * {@link #rawHeight}.
     */
    public static float height(float x, float z) {
        float gx = x / GRID;
        float gz = z / GRID;
        int x0 = (int) Math.floor(gx);
        int z0 = (int) Math.floor(gz);
        float fx = gx - x0;
        float fz = gz - z0;
        float h00 = rawHeight(x0 * GRID, z0 * GRID);
        float h10 = rawHeight((x0 + 1) * GRID, z0 * GRID);
        float h01 = rawHeight(x0 * GRID, (z0 + 1) * GRID);
        float h11 = rawHeight((x0 + 1) * GRID, (z0 + 1) * GRID);
        float top = h00 + (h10 - h00) * fx;
        float bottom = h01 + (h11 - h01) * fx;
        return top + (bottom - top) * fz;
    }

    /** Surface normal of the drawn terrain, by central difference. */
    public static void normal(float x, float z, float[] out) {
        float d = 1.2f;
        float hl = height(x - d, z);
        float hr = height(x + d, z);
        float hd = height(x, z - d);
        float hu = height(x, z + d);
        float nx = hl - hr;
        float ny = 2f * d;
        float nz = hd - hu;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
    }

    // ----------------------------------------------------------------- roads

    /** Signed distance to the nearest road centreline (always >= 0). */
    public static float distanceToRoadCentre(float x, float z) {
        float dx = Math.abs(x - Math.round(x / CHUNK) * CHUNK);
        float dz = Math.abs(z - Math.round(z / CHUNK) * CHUNK);
        return Math.min(dx, dz);
    }

    public static boolean onAsphalt(float x, float z) {
        return distanceToRoadCentre(x, z) <= ROAD_HALF;
    }

    /**
     * Height of whatever a wheel would actually rest on: tarmac, kerb or bare
     * ground. Transitions are smoothed so that mounting a pavement feels like
     * a bump rather than a teleport.
     */
    public static float surfaceHeight(float x, float z) {
        float base = height(x, z);
        float d = distanceToRoadCentre(x, z);
        if (d <= ROAD_HALF) {
            return base + ROAD_SURFACE_LIFT;
        }
        boolean paved = urban(x, z) > 0.22f;
        if (!paved) {
            float t = smoothstep(ROAD_HALF, ROAD_HALF + 2.5f, d);
            return base + ROAD_SURFACE_LIFT * (1f - t);
        }
        if (d <= PAVEMENT_HALF) {
            float t = smoothstep(ROAD_HALF, ROAD_HALF + 0.6f, d);
            return base + ROAD_SURFACE_LIFT + KERB_HEIGHT * t;
        }
        float t = smoothstep(PAVEMENT_HALF, PAVEMENT_HALF + 1.6f, d);
        return base + (ROAD_SURFACE_LIFT + KERB_HEIGHT) * (1f - t);
    }

    /** Rolling resistance multiplier of the surface under a given point. */
    public static float surfaceGrip(float x, float z) {
        float d = distanceToRoadCentre(x, z);
        if (d <= ROAD_HALF) return 1f;
        float t = smoothstep(ROAD_HALF, ROAD_HALF + 4f, d);
        return 1f - 0.34f * t; // grass and gravel are slower and looser
    }

    public static boolean onPavementOrRoad(float x, float z) {
        return distanceToRoadCentre(x, z) <= PAVEMENT_HALF;
    }

    /** Ground colour of the open landscape at this point. */
    public static void groundColor(float x, float z, float slope, float[] out) {
        float dry = dryness(x, z);
        float patch = fbm(x * 0.045f, z * 0.045f, 313, 2);
        float urbanMix = urban(x, z);

        float gr = 0.26f + patch * 0.10f;
        float gg = 0.42f + patch * 0.13f;
        float gb = 0.19f + patch * 0.07f;

        float sr = 0.63f, sg = 0.55f, sb = 0.35f;
        float r = gr + (sr - gr) * dry;
        float g = gg + (sg - gg) * dry;
        float b = gb + (sb - gb) * dry;

        // Mown, slightly greyer verges where the city takes over.
        r = r + (0.30f - r) * urbanMix * 0.45f;
        g = g + (0.38f - g) * urbanMix * 0.45f;
        b = b + (0.24f - b) * urbanMix * 0.45f;

        // Bare rock shows through on the steep faces.
        float rock = smoothstep(0.34f, 0.62f, slope);
        r = r + (0.42f - r) * rock;
        g = g + (0.40f - g) * rock;
        b = b + (0.38f - b) * rock;

        out[0] = r;
        out[1] = g;
        out[2] = b;
    }
}
