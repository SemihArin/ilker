package com.ilker.opendrive.gl;

import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Accumulates procedural geometry on any thread (no GL calls happen here).
 *
 * Vertex layout: position(3) normal(3) color(3) emissive(1) = 10 floats.
 * A model transform stack lets callers place primitives without doing the
 * matrix maths themselves.
 */
public class MeshBuilder {

    public static final int FLOATS_PER_VERTEX = 10;
    public static final int MAX_VERTICES = 65535;

    private float[] verts = new float[FLOATS_PER_VERTEX * 512];
    private int floatCount = 0;
    private int[] indices = new int[1024];
    private int indexCount = 0;

    private final float[] mat = new float[16];
    private final float[] stack = new float[16 * 24];
    private int stackTop = 0;

    private final float[] in4 = new float[4];
    private final float[] out4 = new float[4];

    private float minY = Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;

    public MeshBuilder() {
        Matrix.setIdentityM(mat, 0);
    }

    // ---------------------------------------------------------------- state

    public void reset() {
        floatCount = 0;
        indexCount = 0;
        stackTop = 0;
        minY = Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        Matrix.setIdentityM(mat, 0);
    }

    /** Vertical extent of everything emitted so far; used for culling bounds. */
    public float minY() {
        return isEmpty() ? 0f : minY;
    }

    public float maxY() {
        return isEmpty() ? 0f : maxY;
    }

    public int vertexCount() {
        return floatCount / FLOATS_PER_VERTEX;
    }

    public int indexCount() {
        return indexCount;
    }

    public boolean isEmpty() {
        return indexCount == 0;
    }

    public void identity() {
        Matrix.setIdentityM(mat, 0);
    }

    public void push() {
        System.arraycopy(mat, 0, stack, stackTop * 16, 16);
        stackTop++;
    }

    public void pop() {
        stackTop--;
        System.arraycopy(stack, stackTop * 16, mat, 0, 16);
    }

    public void translate(float x, float y, float z) {
        Matrix.translateM(mat, 0, x, y, z);
    }

    public void rotateX(float degrees) {
        Matrix.rotateM(mat, 0, degrees, 1f, 0f, 0f);
    }

    public void rotateY(float degrees) {
        Matrix.rotateM(mat, 0, degrees, 0f, 1f, 0f);
    }

    public void rotateZ(float degrees) {
        Matrix.rotateM(mat, 0, degrees, 0f, 0f, 1f);
    }

    public void scale(float sx, float sy, float sz) {
        Matrix.scaleM(mat, 0, sx, sy, sz);
    }

    // ------------------------------------------------------------- vertices

    public int vertex(float x, float y, float z,
                      float nx, float ny, float nz,
                      float r, float g, float b, float emissive) {
        in4[0] = x; in4[1] = y; in4[2] = z; in4[3] = 1f;
        Matrix.multiplyMV(out4, 0, mat, 0, in4, 0);
        float px = out4[0], py = out4[1], pz = out4[2];

        in4[0] = nx; in4[1] = ny; in4[2] = nz; in4[3] = 0f;
        Matrix.multiplyMV(out4, 0, mat, 0, in4, 0);
        float tx = out4[0], ty = out4[1], tz = out4[2];
        float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len > 1e-6f) {
            tx /= len; ty /= len; tz /= len;
        } else {
            tx = 0f; ty = 1f; tz = 0f;
        }

        if (floatCount + FLOATS_PER_VERTEX > verts.length) {
            float[] bigger = new float[Math.max(verts.length * 2, floatCount + FLOATS_PER_VERTEX)];
            System.arraycopy(verts, 0, bigger, 0, floatCount);
            verts = bigger;
        }
        if (py < minY) minY = py;
        if (py > maxY) maxY = py;

        int index = floatCount / FLOATS_PER_VERTEX;
        verts[floatCount] = px;
        verts[floatCount + 1] = py;
        verts[floatCount + 2] = pz;
        verts[floatCount + 3] = tx;
        verts[floatCount + 4] = ty;
        verts[floatCount + 5] = tz;
        verts[floatCount + 6] = r;
        verts[floatCount + 7] = g;
        verts[floatCount + 8] = b;
        verts[floatCount + 9] = emissive;
        floatCount += FLOATS_PER_VERTEX;
        return index;
    }

    public void triangle(int a, int b, int c) {
        if (indexCount + 3 > indices.length) {
            int[] bigger = new int[Math.max(indices.length * 2, indexCount + 3)];
            System.arraycopy(indices, 0, bigger, 0, indexCount);
            indices = bigger;
        }
        indices[indexCount] = a;
        indices[indexCount + 1] = b;
        indices[indexCount + 2] = c;
        indexCount += 3;
    }

    public void quad(int a, int b, int c, int d) {
        triangle(a, b, c);
        triangle(a, c, d);
    }

    // ------------------------------------------------------------ primitives

    /**
     * Solid built between two horizontal outlines. Both outlines hold the same
     * number of (x, z) pairs; winding is derived from the geometry so callers
     * never have to think about which way round they listed the points.
     */
    public void prism(float[] bottomXZ, float y0, float[] topXZ, float y1,
                      float r, float g, float b, float emissive,
                      boolean capBottom, boolean capTop) {
        int n = bottomXZ.length / 2;
        if (n < 3 || topXZ.length / 2 != n) return;

        float cx = 0f, cz = 0f;
        for (int i = 0; i < n; i++) {
            cx += bottomXZ[i * 2];
            cz += bottomXZ[i * 2 + 1];
        }
        cx /= n;
        cz /= n;

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float b0x = bottomXZ[i * 2], b0z = bottomXZ[i * 2 + 1];
            float b1x = bottomXZ[j * 2], b1z = bottomXZ[j * 2 + 1];
            float t0x = topXZ[i * 2], t0z = topXZ[i * 2 + 1];
            float t1x = topXZ[j * 2], t1z = topXZ[j * 2 + 1];

            float ex = b1x - b0x, ez = b1z - b0z;
            // Geometric normal of triangle (b0, b1, t1) is proportional to this.
            float nx = -ez, nz = ex;
            float nl = (float) Math.sqrt(nx * nx + nz * nz);
            if (nl < 1e-6f) continue;
            nx /= nl; nz /= nl;

            float mx = (b0x + b1x) * 0.5f - cx;
            float mz = (b0z + b1z) * 0.5f - cz;
            boolean flip = (nx * mx + nz * mz) < 0f;
            float onx = flip ? -nx : nx;
            float onz = flip ? -nz : nz;

            int vb0 = vertex(b0x, y0, b0z, onx, 0f, onz, r, g, b, emissive);
            int vb1 = vertex(b1x, y0, b1z, onx, 0f, onz, r, g, b, emissive);
            int vt1 = vertex(t1x, y1, t1z, onx, 0f, onz, r, g, b, emissive);
            int vt0 = vertex(t0x, y1, t0z, onx, 0f, onz, r, g, b, emissive);

            if (flip) {
                quad(vb0, vt0, vt1, vb1);
            } else {
                quad(vb0, vb1, vt1, vt0);
            }
        }

        if (capTop) cap(topXZ, y1, true, r, g, b, emissive);
        if (capBottom) cap(bottomXZ, y0, false, r, g, b, emissive);
    }

    private void cap(float[] outlineXZ, float y, boolean up,
                     float r, float g, float b, float emissive) {
        int n = outlineXZ.length / 2;
        if (n < 3) return;

        // Shoelace in the XZ plane tells us the listed orientation. It has to
        // be taken relative to the outline's own centre: a kilometre from the
        // origin the products of raw world coordinates are far larger than the
        // area being measured, and the sign drowns in float rounding.
        float ox = 0f, oz = 0f;
        for (int i = 0; i < n; i++) {
            ox += outlineXZ[i * 2];
            oz += outlineXZ[i * 2 + 1];
        }
        ox /= n;
        oz /= n;

        float area2 = 0f;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float xi = outlineXZ[i * 2] - ox, zi = outlineXZ[i * 2 + 1] - oz;
            float xj = outlineXZ[j * 2] - ox, zj = outlineXZ[j * 2 + 1] - oz;
            area2 += xi * zj - xj * zi;
        }
        // A fan listed with area2 < 0 has triangles whose normal points at +Y.
        boolean reverse = up ? (area2 > 0f) : (area2 < 0f);

        float ny = up ? 1f : -1f;
        int[] ring = new int[n];
        for (int i = 0; i < n; i++) {
            ring[i] = vertex(outlineXZ[i * 2], y, outlineXZ[i * 2 + 1], 0f, ny, 0f, r, g, b, emissive);
        }
        for (int i = 1; i < n - 1; i++) {
            if (reverse) {
                triangle(ring[0], ring[i + 1], ring[i]);
            } else {
                triangle(ring[0], ring[i], ring[i + 1]);
            }
        }
    }

    /** Axis-aligned box centred on (cx, cy, cz). */
    public void box(float cx, float cy, float cz, float sx, float sy, float sz,
                    float r, float g, float b, float emissive) {
        float hx = sx * 0.5f, hz = sz * 0.5f;
        float[] outline = {
                cx - hx, cz - hz,
                cx + hx, cz - hz,
                cx + hx, cz + hz,
                cx - hx, cz + hz
        };
        prism(outline, cy - sy * 0.5f, outline, cy + sy * 0.5f, r, g, b, emissive, true, true);
    }

    /**
     * Box whose top face can be inset and shifted — the workhorse for car
     * bodies, roofs and tapered building crowns.
     */
    public void taperedBox(float cx, float cy, float cz,
                           float sxBottom, float szBottom,
                           float sxTop, float szTop,
                           float topOffsetX, float topOffsetZ,
                           float y0, float y1,
                           float r, float g, float b, float emissive) {
        float hxb = sxBottom * 0.5f, hzb = szBottom * 0.5f;
        float hxt = sxTop * 0.5f, hzt = szTop * 0.5f;
        float tx = cx + topOffsetX, tz = cz + topOffsetZ;
        float[] bottom = {
                cx - hxb, cz - hzb,
                cx + hxb, cz - hzb,
                cx + hxb, cz + hzb,
                cx - hxb, cz + hzb
        };
        float[] top = {
                tx - hxt, tz - hzt,
                tx + hxt, tz - hzt,
                tx + hxt, tz + hzt,
                tx - hxt, tz + hzt
        };
        prism(bottom, y0 + cy, top, y1 + cy, r, g, b, emissive, true, true);
    }

    /** Vertical cylinder (or cone when one radius is zero). */
    public void cylinder(float cx, float cy, float cz, float rBottom, float rTop,
                         float height, int sides, float r, float g, float b, float emissive) {
        if (sides < 3) sides = 3;
        float[] bottom = new float[sides * 2];
        float[] top = new float[sides * 2];
        float rb = Math.max(rBottom, 1e-4f);
        float rt = Math.max(rTop, 1e-4f);
        for (int i = 0; i < sides; i++) {
            double a = (Math.PI * 2.0 * i) / sides;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            bottom[i * 2] = cx + ca * rb;
            bottom[i * 2 + 1] = cz + sa * rb;
            top[i * 2] = cx + ca * rt;
            top[i * 2 + 1] = cz + sa * rt;
        }
        prism(bottom, cy, top, cy + height, r, g, b, emissive, true, true);
    }

    /** Flat horizontal quad, used for road surfaces and markings. */
    public void flatQuad(float x0, float y0, float z0,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float x3, float y3, float z3,
                         float r, float g, float b, float emissive) {
        float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0;
        float wx = x3 - x0, wy = y3 - y0, wz = z3 - z0;
        float nx = uy * wz - uz * wy;
        float ny = uz * wx - ux * wz;
        float nz = ux * wy - uy * wx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) {
            nx = 0f; ny = 1f; nz = 0f;
        } else {
            nx /= len; ny /= len; nz /= len;
        }
        // Keep the quad facing the sky, flipping the winding as well as the
        // normal so that back-face culling does not eat it.
        boolean reversed = ny < 0f;
        if (reversed) {
            nx = -nx; ny = -ny; nz = -nz;
        }
        int a = vertex(x0, y0, z0, nx, ny, nz, r, g, b, emissive);
        int bIdx = vertex(x1, y1, z1, nx, ny, nz, r, g, b, emissive);
        int c = vertex(x2, y2, z2, nx, ny, nz, r, g, b, emissive);
        int d = vertex(x3, y3, z3, nx, ny, nz, r, g, b, emissive);
        if (reversed) {
            quad(a, d, c, bIdx);
        } else {
            quad(a, bIdx, c, d);
        }
    }

    /** Wheel: a cylinder lying on its side, spinning about the X axis. */
    public void wheel(float cx, float cy, float cz, float radius, float width,
                      int sides, float tr, float tg, float tb, float hr, float hg, float hb) {
        push();
        translate(cx, cy, cz);
        rotateZ(90f);
        // After the rotation the cylinder axis runs along world X.
        cylinder(0f, -width * 0.5f, 0f, radius, radius, width, sides, tr, tg, tb, 0f);
        cylinder(0f, -width * 0.5f - 0.005f, 0f, radius * 0.55f, radius * 0.55f,
                width + 0.01f, sides, hr, hg, hb, 0f);
        pop();
    }

    // --------------------------------------------------------------- buffers

    public FloatBuffer buildVertexBuffer() {
        ByteBuffer bb = ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(verts, 0, floatCount);
        fb.position(0);
        return fb;
    }

    public ShortBuffer buildIndexBuffer() {
        ByteBuffer bb = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder());
        ShortBuffer sb = bb.asShortBuffer();
        for (int i = 0; i < indexCount; i++) {
            sb.put((short) indices[i]);
        }
        sb.position(0);
        return sb;
    }
}
