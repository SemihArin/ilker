package com.ilker.opendrive.world;

import com.ilker.opendrive.gl.MeshBuilder;

/**
 * Turns a chunk coordinate into geometry: landscape, road surface with
 * markings, pavements, buildings, street furniture and vegetation.
 * Pure CPU work, safe to run on a background thread.
 */
public final class ChunkBuilder {

    private static final float ROAD_LIFT = Terrain.ROAD_SURFACE_LIFT;
    private static final float MARK_LIFT = 0.02f;
    private static final float KERB_HEIGHT = Terrain.KERB_HEIGHT;

    private ChunkBuilder() {
    }

    /** Small deterministic generator so a chunk always looks the same. */
    private static final class Rng {
        private int state;

        Rng(int cx, int cz, int salt) {
            state = cx * 73856093 ^ cz * 19349663 ^ salt * 83492791;
            if (state == 0) state = salt + 1;
            next();
            next();
        }

        int nextInt() {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            return state;
        }

        float next() {
            return (nextInt() & 0x7fffffff) / 2147483647f;
        }

        float range(float a, float b) {
            return a + (b - a) * next();
        }

        boolean chance(float p) {
            return next() < p;
        }
    }

    public static ChunkData generate(int cx, int cz) {
        ChunkData data = new ChunkData(cx, cz);
        MeshBuilder ground = new MeshBuilder();
        MeshBuilder objects = new MeshBuilder();

        float originX = cx * Terrain.CHUNK;
        float originZ = cz * Terrain.CHUNK;

        buildTerrain(ground, originX, originZ);
        buildRoads(ground, originX, originZ);
        buildBlockContents(objects, cx, cz, originX, originZ);

        data.groundIndexCount = ground.indexCount();
        if (data.groundIndexCount > 0) {
            data.groundVerts = ground.buildVertexBuffer();
            data.groundIndices = ground.buildIndexBuffer();
        }
        data.objectIndexCount = objects.indexCount();
        if (data.objectIndexCount > 0) {
            data.objectVerts = objects.buildVertexBuffer();
            data.objectIndices = objects.buildIndexBuffer();
        }

        // Real extents beat a guessed allowance: a tight bounding sphere is
        // what makes frustum culling worth doing.
        float minY = Math.min(ground.minY(), objects.isEmpty() ? ground.minY() : objects.minY());
        float maxY = Math.max(ground.maxY(), objects.isEmpty() ? ground.maxY() : objects.maxY());
        data.centerX = originX + Terrain.CHUNK * 0.5f;
        data.centerZ = originZ + Terrain.CHUNK * 0.5f;
        data.centerY = (minY + maxY) * 0.5f;
        float halfHeight = (maxY - minY) * 0.5f;
        float halfDiag = Terrain.CHUNK * 0.7072f;
        data.radius = (float) Math.sqrt(halfDiag * halfDiag + halfHeight * halfHeight);
        return data;
    }

    // ------------------------------------------------------------- landscape

    private static void buildTerrain(MeshBuilder mb, float originX, float originZ) {
        int n = Terrain.CELLS;
        int[] ring = new int[(n + 1) * (n + 1)];
        float[] normal = new float[3];
        float[] color = new float[3];

        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                float x = originX + i * Terrain.GRID;
                float z = originZ + j * Terrain.GRID;
                float y = Terrain.height(x, z);

                Terrain.normal(x, z, normal);
                float slope = 1f - normal[1];
                Terrain.groundColor(x, z, slope, color);

                // Bare earth under whatever the road actually covers, so the
                // seam never flashes green. Only out to the edge of the built
                // surface — any wider and the countryside gets a dark halo.
                float d = Terrain.distanceToRoadCentre(x, z);
                float covered = Terrain.urban(x, z) > 0.22f
                        ? Terrain.PAVEMENT_HALF : Terrain.ROAD_HALF;
                if (d <= covered) {
                    color[0] += (0.23f - color[0]);
                    color[1] += (0.21f - color[1]);
                    color[2] += (0.17f - color[2]);
                }

                ring[j * (n + 1) + i] = mb.vertex(x, y, z,
                        normal[0], normal[1], normal[2],
                        color[0], color[1], color[2], 0f);
            }
        }

        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = ring[j * (n + 1) + i];
                int b = ring[j * (n + 1) + i + 1];
                int c = ring[(j + 1) * (n + 1) + i + 1];
                int d = ring[(j + 1) * (n + 1) + i];
                // Viewed from above with +Y up this winding faces the sky.
                mb.triangle(a, d, c);
                mb.triangle(a, c, b);
            }
        }
    }

    // ----------------------------------------------------------------- roads

    private static void buildRoads(MeshBuilder mb, float originX, float originZ) {
        float half = Terrain.ROAD_HALF;
        float lo = half;
        float hi = Terrain.CHUNK - half;

        // Carriageway running along +Z at the chunk's west edge.
        roadStrip(mb, originX, originZ + lo, originX, originZ + hi, true);
        // Carriageway running along +X at the chunk's south edge.
        roadStrip(mb, originX + lo, originZ, originX + hi, originZ, false);
        // The junction in the corner, owned by exactly one chunk.
        junction(mb, originX, originZ);

        float urban = Terrain.urban(originX + Terrain.CHUNK * 0.5f, originZ + Terrain.CHUNK * 0.5f);
        if (urban > 0.22f) {
            pavement(mb, originX, originZ + lo, originX, originZ + hi, true);
            pavement(mb, originX + lo, originZ, originX + hi, originZ, false);
        }
    }

    /** Asphalt plus lane markings between two points along a grid line. */
    private static void roadStrip(MeshBuilder mb, float x0, float z0, float x1, float z1,
                                  boolean alongZ) {
        float length = alongZ ? (z1 - z0) : (x1 - x0);
        if (length <= 0.01f) return;
        int steps = Math.max(2, Math.round(length / 5f));
        int lanes = 4;
        float half = Terrain.ROAD_HALF;

        for (int s = 0; s < steps; s++) {
            float t0 = s / (float) steps;
            float t1 = (s + 1) / (float) steps;
            float a0 = alongZ ? (z0 + length * t0) : (x0 + length * t0);
            float a1 = alongZ ? (z0 + length * t1) : (x0 + length * t1);

            for (int w = 0; w < lanes; w++) {
                float o0 = -half + (2f * half) * w / lanes;
                float o1 = -half + (2f * half) * (w + 1) / lanes;
                float shade = 0.085f + 0.012f * ((s + w) & 1);
                if (alongZ) {
                    quadOnGround(mb, x0 + o0, a0, x0 + o1, a0, x0 + o1, a1, x0 + o0, a1,
                            shade, shade, shade + 0.008f, 0f, ROAD_LIFT);
                } else {
                    quadOnGround(mb, a0, z0 + o0, a1, z0 + o0, a1, z0 + o1, a0, z0 + o1,
                            shade, shade, shade + 0.008f, 0f, ROAD_LIFT);
                }
            }
        }

        // Dashed centreline.
        int dashes = Math.max(1, Math.round(length / 9f));
        for (int d = 0; d < dashes; d++) {
            float ds = (d + 0.18f) / dashes;
            float de = (d + 0.62f) / dashes;
            float a0 = alongZ ? (z0 + length * ds) : (x0 + length * ds);
            float a1 = alongZ ? (z0 + length * de) : (x0 + length * de);
            if (alongZ) {
                quadOnGround(mb, x0 - 0.16f, a0, x0 + 0.16f, a0, x0 + 0.16f, a1, x0 - 0.16f, a1,
                        0.74f, 0.71f, 0.55f, 0.08f, ROAD_LIFT + MARK_LIFT);
            } else {
                quadOnGround(mb, a0, z0 - 0.16f, a1, z0 - 0.16f, a1, z0 + 0.16f, a0, z0 + 0.16f,
                        0.74f, 0.71f, 0.55f, 0.08f, ROAD_LIFT + MARK_LIFT);
            }
        }

        // Continuous edge lines.
        for (int side = 0; side < 2; side++) {
            float o = (side == 0 ? -1f : 1f) * (half - 0.45f);
            int seg = Math.max(2, Math.round(length / 8f));
            for (int s = 0; s < seg; s++) {
                float a0 = alongZ ? (z0 + length * s / seg) : (x0 + length * s / seg);
                float a1 = alongZ ? (z0 + length * (s + 1) / seg) : (x0 + length * (s + 1) / seg);
                if (alongZ) {
                    quadOnGround(mb, x0 + o - 0.11f, a0, x0 + o + 0.11f, a0,
                            x0 + o + 0.11f, a1, x0 + o - 0.11f, a1,
                            0.70f, 0.68f, 0.62f, 0.06f, ROAD_LIFT + MARK_LIFT);
                } else {
                    quadOnGround(mb, a0, z0 + o - 0.11f, a1, z0 + o - 0.11f,
                            a1, z0 + o + 0.11f, a0, z0 + o + 0.11f,
                            0.70f, 0.68f, 0.62f, 0.06f, ROAD_LIFT + MARK_LIFT);
                }
            }
        }
    }

    private static void junction(MeshBuilder mb, float jx, float jz) {
        float half = Terrain.ROAD_HALF;
        int n = 3;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                float ax = jx - half + 2f * half * i / n;
                float bx = jx - half + 2f * half * (i + 1) / n;
                float az = jz - half + 2f * half * j / n;
                float bz = jz - half + 2f * half * (j + 1) / n;
                float shade = 0.090f + 0.010f * ((i + j) & 1);
                quadOnGround(mb, ax, az, bx, az, bx, bz, ax, bz,
                        shade, shade, shade + 0.008f, 0f, ROAD_LIFT);
            }
        }
    }

    private static void pavement(MeshBuilder mb, float x0, float z0, float x1, float z1,
                                 boolean alongZ) {
        float length = alongZ ? (z1 - z0) : (x1 - x0);
        if (length <= 0.01f) return;
        int steps = Math.max(2, Math.round(length / 8f));
        float inner = Terrain.ROAD_HALF;
        float outer = Terrain.PAVEMENT_HALF;

        for (int side = 0; side < 2; side++) {
            float sign = (side == 0) ? -1f : 1f;
            for (int s = 0; s < steps; s++) {
                float a0 = alongZ ? (z0 + length * s / steps) : (x0 + length * s / steps);
                float a1 = alongZ ? (z0 + length * (s + 1) / steps) : (x0 + length * (s + 1) / steps);
                float oi = sign * inner;
                float oo = sign * outer;
                float lift = ROAD_LIFT + KERB_HEIGHT;
                float c = 0.44f + 0.02f * (s & 1);
                if (alongZ) {
                    quadOnGround(mb, x0 + oi, a0, x0 + oo, a0, x0 + oo, a1, x0 + oi, a1,
                            c, c, c * 0.97f, 0f, lift);
                } else {
                    quadOnGround(mb, a0, z0 + oi, a1, z0 + oi, a1, z0 + oo, a0, z0 + oo,
                            c, c, c * 0.97f, 0f, lift);
                }
            }
        }
    }

    /** Emits a quad draped over the terrain at a fixed vertical offset. */
    private static void quadOnGround(MeshBuilder mb,
                                     float x0, float z0, float x1, float z1,
                                     float x2, float z2, float x3, float z3,
                                     float r, float g, float b, float emissive, float lift) {
        mb.flatQuad(
                x0, Terrain.height(x0, z0) + lift, z0,
                x1, Terrain.height(x1, z1) + lift, z1,
                x2, Terrain.height(x2, z2) + lift, z2,
                x3, Terrain.height(x3, z3) + lift, z3,
                r, g, b, emissive);
    }

    // ---------------------------------------------------------- block filler

    private static void buildBlockContents(MeshBuilder mb, int cx, int cz,
                                           float originX, float originZ) {
        Rng rng = new Rng(cx, cz, 4242);
        float centreX = originX + Terrain.CHUNK * 0.5f;
        float centreZ = originZ + Terrain.CHUNK * 0.5f;
        float urban = Terrain.urban(centreX, centreZ);

        float margin = Terrain.PAVEMENT_HALF + 3f;
        float lo = margin;
        float hi = Terrain.CHUNK - margin;

        if (urban > 0.55f) {
            streetLights(mb, originX, originZ, 26f);
            int div = rng.chance(0.45f) ? 2 : 3;
            fillPlots(mb, rng, originX, originZ, lo, hi, div, urban, true);
        } else if (urban > 0.22f) {
            streetLights(mb, originX, originZ, 38f);
            fillPlots(mb, rng, originX, originZ, lo, hi, 2, urban, false);
            scatterTrees(mb, rng, originX, originZ, lo, hi, 4);
        } else {
            if (rng.chance(0.30f)) {
                fillPlots(mb, rng, originX, originZ, lo, hi, 1, urban, false);
            }
            scatterTrees(mb, rng, originX, originZ, lo, hi, 16);
            scatterRocks(mb, rng, originX, originZ, lo, hi, 5);
        }
    }

    private static void fillPlots(MeshBuilder mb, Rng rng, float originX, float originZ,
                                  float lo, float hi, int div, float urban, boolean tower) {
        float span = (hi - lo) / div;
        for (int i = 0; i < div; i++) {
            for (int j = 0; j < div; j++) {
                if (!tower && !rng.chance(0.75f)) continue;
                if (tower && !rng.chance(0.88f)) continue;

                float plotCx = originX + lo + span * (i + 0.5f);
                float plotCz = originZ + lo + span * (j + 0.5f);
                float maxFoot = span * 0.82f;
                float w = rng.range(maxFoot * 0.55f, maxFoot);
                float d = rng.range(maxFoot * 0.55f, maxFoot);

                if (tower) {
                    tower(mb, rng, plotCx, plotCz, w, d, urban);
                } else {
                    house(mb, rng, plotCx, plotCz, Math.min(w, 16f), Math.min(d, 13f));
                }
            }
        }
    }

    private static float plotBase(float cx, float cz, float w, float d) {
        float hw = w * 0.5f, hd = d * 0.5f;
        float a = Terrain.height(cx - hw, cz - hd);
        float b = Terrain.height(cx + hw, cz - hd);
        float c = Terrain.height(cx + hw, cz + hd);
        float e = Terrain.height(cx - hw, cz + hd);
        return Math.min(Math.min(a, b), Math.min(c, e));
    }

    private static void tower(MeshBuilder mb, Rng rng, float cx, float cz,
                              float w, float d, float urban) {
        float base = plotBase(cx, cz, w, d) - 1.2f;
        int floors = (int) rng.range(4f, 6f + urban * 22f);
        float floorHeight = rng.range(3.1f, 3.9f);
        float height = floors * floorHeight;

        float tint = rng.range(0.30f, 0.62f);
        float warm = rng.range(-0.05f, 0.08f);
        float r = tint + warm;
        float g = tint;
        float b = tint - warm * 0.5f + 0.03f;

        float topW = w * rng.range(0.82f, 1.0f);
        float topD = d * rng.range(0.82f, 1.0f);
        mb.taperedBox(cx, 0f, cz, w, d, topW, topD, 0f, 0f, base, base + height, r, g, b, 0f);

        // Glazing bands, one per floor, lit at random after dark.
        float wallOut = 0.06f;
        for (int f = 1; f < floors; f++) {
            float y = base + f * floorHeight;
            float bandH = floorHeight * 0.44f;
            float t = f / (float) floors;
            float fw = w + (topW - w) * t + wallOut;
            float fd = d + (topD - d) * t + wallOut;
            float lit = rng.chance(0.58f) ? rng.range(0.55f, 1f) : 0.08f;
            float gr = 0.30f + lit * 0.62f;
            float gg = 0.36f + lit * 0.56f;
            float gb = 0.44f + lit * 0.34f;
            mb.taperedBox(cx, 0f, cz, fw, fd, fw, fd, 0f, 0f,
                    y, y + bandH, gr, gg, gb, lit);
        }

        // Roof plant and a warning light on the tall ones.
        float roofW = topW * rng.range(0.3f, 0.5f);
        mb.box(cx + rng.range(-w * 0.15f, w * 0.15f), base + height + 1.1f,
                cz + rng.range(-d * 0.15f, d * 0.15f),
                roofW, 2.2f, roofW * 0.8f, r * 0.8f, g * 0.8f, b * 0.8f, 0f);
        if (height > 45f) {
            mb.box(cx, base + height + 2.8f, cz, 0.5f, 0.5f, 0.5f, 1f, 0.15f, 0.12f, 1f);
        }
    }

    private static void house(MeshBuilder mb, Rng rng, float cx, float cz, float w, float d) {
        float base = plotBase(cx, cz, w, d) - 0.7f;
        float wallH = rng.range(2.9f, 4.4f);
        int floors = rng.chance(0.35f) ? 2 : 1;
        float height = wallH * floors;

        float r = rng.range(0.52f, 0.86f);
        float g = r * rng.range(0.80f, 0.98f);
        float b = r * rng.range(0.66f, 0.92f);
        mb.box(cx, base + height * 0.5f, cz, w, height, d, r, g, b, 0f);

        // Hip roof: rectangle at the eaves narrowing to a ridge.
        float hw = w * 0.54f, hd = d * 0.54f;
        float ridge = Math.max(w, d) * 0.18f;
        float[] eaves = {cx - hw, cz - hd, cx + hw, cz - hd, cx + hw, cz + hd, cx - hw, cz + hd};
        float[] top;
        if (w >= d) {
            top = new float[]{cx - hw * 0.45f, cz - 0.28f, cx + hw * 0.45f, cz - 0.28f,
                    cx + hw * 0.45f, cz + 0.28f, cx - hw * 0.45f, cz + 0.28f};
        } else {
            top = new float[]{cx - 0.28f, cz - hd * 0.45f, cx + 0.28f, cz - hd * 0.45f,
                    cx + 0.28f, cz + hd * 0.45f, cx - 0.28f, cz + hd * 0.45f};
        }
        float rr = rng.range(0.28f, 0.52f);
        mb.prism(eaves, base + height, top, base + height + ridge + 1.1f,
                rr, rr * 0.48f, rr * 0.38f, 0f, false, true);

        // Windows and a door.
        float lit = rng.chance(0.5f) ? rng.range(0.5f, 1f) : 0.06f;
        for (int f = 0; f < floors; f++) {
            float y = base + wallH * f + wallH * 0.58f;
            mb.taperedBox(cx, 0f, cz, w + 0.06f, d * 0.52f, w + 0.06f, d * 0.52f, 0f, 0f,
                    y - 0.45f, y + 0.45f,
                    0.32f + lit * 0.6f, 0.38f + lit * 0.52f, 0.46f + lit * 0.3f, lit);
        }
        mb.box(cx, base + 1.0f, cz + d * 0.5f + 0.03f, 0.95f, 2.0f, 0.08f,
                rr * 0.7f, rr * 0.4f, rr * 0.3f, 0f);
    }

    private static void streetLights(MeshBuilder mb, float originX, float originZ, float spacing) {
        int count = Math.max(1, Math.round(Terrain.CHUNK / spacing));
        float off = Terrain.PAVEMENT_HALF - 1.0f;
        for (int i = 0; i < count; i++) {
            float t = (i + 0.5f) / count;
            // One lamp on each of the chunk's two road edges.
            lamp(mb, originX + off, originZ + Terrain.CHUNK * t, true);
            lamp(mb, originX + Terrain.CHUNK * t, originZ + off, false);
        }
    }

    private static void lamp(MeshBuilder mb, float x, float z, boolean armTowardsMinusX) {
        float y = Terrain.height(x, z);
        float poleH = 6.4f;
        mb.cylinder(x, y, z, 0.13f, 0.10f, poleH, 6, 0.30f, 0.31f, 0.33f, 0f);
        float armLen = 1.7f;
        float dir = armTowardsMinusX ? -1f : 1f;
        if (armTowardsMinusX) {
            mb.box(x + dir * armLen * 0.5f, y + poleH, z, armLen, 0.14f, 0.14f, 0.30f, 0.31f, 0.33f, 0f);
            mb.box(x + dir * armLen, y + poleH - 0.18f, z, 0.85f, 0.22f, 0.36f, 1f, 0.93f, 0.72f, 1f);
        } else {
            mb.box(x, y + poleH, z + dir * armLen * 0.5f, 0.14f, 0.14f, armLen, 0.30f, 0.31f, 0.33f, 0f);
            mb.box(x, y + poleH - 0.18f, z + dir * armLen, 0.36f, 0.22f, 0.85f, 1f, 0.93f, 0.72f, 1f);
        }
    }

    private static void scatterTrees(MeshBuilder mb, Rng rng, float originX, float originZ,
                                     float lo, float hi, int count) {
        for (int i = 0; i < count; i++) {
            float x = originX + rng.range(lo - 6f, hi + 6f);
            float z = originZ + rng.range(lo - 6f, hi + 6f);
            if (Terrain.distanceToRoadCentre(x, z) < Terrain.PAVEMENT_HALF + 1.5f) continue;
            tree(mb, rng, x, z);
        }
    }

    private static void tree(MeshBuilder mb, Rng rng, float x, float z) {
        float y = Terrain.height(x, z) - 0.2f;
        float scale = rng.range(0.75f, 1.55f);
        float trunkH = 2.3f * scale;
        float trunkR = 0.20f * scale;
        mb.cylinder(x, y, z, trunkR * 1.25f, trunkR, trunkH, 5,
                0.27f, 0.19f, 0.13f, 0f);

        float leafR = rng.range(1.5f, 2.3f) * scale;
        float shade = rng.range(0.0f, 1f);
        float lr = 0.13f + shade * 0.12f;
        float lg = 0.34f + shade * 0.17f;
        float lb = 0.14f + shade * 0.08f;

        if (rng.chance(0.35f)) {
            // Conifer: stacked cones.
            float h = 4.2f * scale;
            mb.cylinder(x, y + trunkH * 0.6f, z, leafR, leafR * 0.55f, h * 0.55f, 7, lr, lg, lb, 0f);
            mb.cylinder(x, y + trunkH * 0.6f + h * 0.45f, z, leafR * 0.72f, 0.02f, h * 0.7f, 7,
                    lr * 1.1f, lg * 1.1f, lb * 1.1f, 0f);
        } else {
            // Broadleaf: a squat bipyramid reads as a canopy at distance.
            float h = 3.4f * scale;
            mb.cylinder(x, y + trunkH, z, leafR * 0.45f, leafR, h * 0.4f, 7, lr, lg, lb, 0f);
            mb.cylinder(x, y + trunkH + h * 0.4f, z, leafR, 0.05f, h * 0.75f, 7,
                    lr * 1.12f, lg * 1.12f, lb * 1.12f, 0f);
        }
    }

    private static void scatterRocks(MeshBuilder mb, Rng rng, float originX, float originZ,
                                     float lo, float hi, int count) {
        for (int i = 0; i < count; i++) {
            float x = originX + rng.range(lo - 8f, hi + 8f);
            float z = originZ + rng.range(lo - 8f, hi + 8f);
            if (Terrain.distanceToRoadCentre(x, z) < Terrain.PAVEMENT_HALF + 2f) continue;
            float y = Terrain.height(x, z) - 0.3f;
            float s = rng.range(0.5f, 1.9f);
            float grey = rng.range(0.34f, 0.52f);
            mb.push();
            mb.translate(x, y, z);
            mb.rotateY(rng.range(0f, 360f));
            mb.cylinder(0f, 0f, 0f, s, s * 0.55f, s * rng.range(0.6f, 1.1f), 6,
                    grey, grey * 0.98f, grey * 0.92f, 0f);
            mb.pop();
        }
    }
}
