package com.ilker.opendrive.game;

import android.opengl.GLES20;

import com.ilker.opendrive.gl.MeshBuilder;
import com.ilker.opendrive.gl.SceneProgram;
import com.ilker.opendrive.world.Terrain;

/**
 * Rubber left on the road by sliding or spinning tyres.
 *
 * A fixed ring of quads lives in one GPU buffer that is filled once and then
 * only ever patched a quad at a time, so laying down a trail never costs an
 * allocation or a full re-upload.
 */
public class SkidMarks {

    private static final int MAX_MARKS = 260;
    private static final int FLOATS = MeshBuilder.FLOATS_PER_VERTEX;
    private static final int VERTS_PER_MARK = 4;
    private static final int FLOATS_PER_MARK = VERTS_PER_MARK * FLOATS;
    /** How far a wheel travels before it lays down the next segment. */
    private static final float SPACING = 0.38f;

    private final float[] quad = new float[FLOATS_PER_MARK];
    private java.nio.FloatBuffer staging;
    private int vbo;
    private int ibo;
    private int live;      // marks written so far, capped at MAX_MARKS
    private int head;      // next ring slot

    // One pen per wheel: where it last touched down, and whether it was down.
    private final float[] penX = new float[4];
    private final float[] penZ = new float[4];
    private final boolean[] penDown = new boolean[4];
    private final float[] wheel = new float[2];

    public void create() {
        java.nio.ByteBuffer vb = java.nio.ByteBuffer
                .allocateDirect(MAX_MARKS * FLOATS_PER_MARK * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        java.nio.ByteBuffer sb = java.nio.ByteBuffer
                .allocateDirect(FLOATS_PER_MARK * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        staging = sb.asFloatBuffer();

        // Indices never change: two triangles per quad, for every slot.
        java.nio.ByteBuffer ibb = java.nio.ByteBuffer
                .allocateDirect(MAX_MARKS * 6 * 2)
                .order(java.nio.ByteOrder.nativeOrder());
        java.nio.ShortBuffer ib = ibb.asShortBuffer();
        for (int m = 0; m < MAX_MARKS; m++) {
            int base = m * VERTS_PER_MARK;
            ib.put((short) base);
            ib.put((short) (base + 1));
            ib.put((short) (base + 2));
            ib.put((short) base);
            ib.put((short) (base + 2));
            ib.put((short) (base + 3));
        }
        ib.position(0);

        int[] ids = new int[2];
        GLES20.glGenBuffers(2, ids, 0);
        vbo = ids[0];
        ibo = ids[1];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vb.capacity(), vb, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibb.capacity(), ib,
                GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        clear();
    }

    public void clear() {
        live = 0;
        head = 0;
        for (int i = 0; i < 4; i++) {
            penDown[i] = false;
        }
    }

    /**
     * Follows the car's wheels and lays rubber under the ones that are losing
     * traction. Call once a frame, on the GL thread.
     */
    public void follow(Car car) {
        if (vbo == 0 || car.spec == null) return;
        float speed = Math.abs(car.forwardSpeed);
        float slip = Math.abs(car.slipAngle);

        // Rear tyres mark when the back steps out or the power overwhelms
        // them; fronts only when the brakes have them locked.
        float rearMark = Math.max(car.wheelspin, slip > 0.18f ? Math.min(1f, (slip - 0.18f) * 3.4f) : 0f);
        float frontMark = car.lockup > 0.45f ? Math.min(1f, (car.lockup - 0.45f) * 2.6f) : 0f;
        if (speed < 2.2f) {
            rearMark = car.wheelspin;
            frontMark = 0f;
        }

        float halfWidth = car.spec.wheelWidth * 0.55f;
        for (int i = 0; i < 4; i++) {
            float strength = (i < 2) ? frontMark : rearMark;
            if (strength <= 0.05f) {
                penDown[i] = false;
                continue;
            }
            car.wheelGroundPosition(i, wheel);
            if (!penDown[i]) {
                penDown[i] = true;
                penX[i] = wheel[0];
                penZ[i] = wheel[1];
                continue;
            }
            float dx = wheel[0] - penX[i];
            float dz = wheel[1] - penZ[i];
            if (dx * dx + dz * dz < SPACING * SPACING) continue;
            addSegment(penX[i], penZ[i], wheel[0], wheel[1], halfWidth, strength);
            penX[i] = wheel[0];
            penZ[i] = wheel[1];
        }
    }

    private void addSegment(float x0, float z0, float x1, float z1,
                            float halfWidth, float strength) {
        float dx = x1 - x0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4f) return;
        float px = -dz / len * halfWidth;
        float pz = dx / len * halfWidth;

        float shade = 0.055f - 0.022f * Math.min(1f, strength);
        float lift = 0.025f;

        write(0, x0 + px, z0 + pz, shade, lift);
        write(1, x1 + px, z1 + pz, shade, lift);
        write(2, x1 - px, z1 - pz, shade, lift);
        write(3, x0 - px, z0 - pz, shade, lift);

        staging.position(0);
        staging.put(quad, 0, FLOATS_PER_MARK);
        staging.position(0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, head * FLOATS_PER_MARK * 4,
                FLOATS_PER_MARK * 4, staging);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        head = (head + 1) % MAX_MARKS;
        if (live < MAX_MARKS) live++;
    }

    /** Fills one corner of the quad, draped on whatever surface is underneath. */
    private void write(int corner, float x, float z, float shade, float lift) {
        int o = corner * FLOATS;
        quad[o] = x;
        quad[o + 1] = Terrain.surfaceHeight(x, z) + lift;
        quad[o + 2] = z;
        quad[o + 3] = 0f;
        quad[o + 4] = 1f;
        quad[o + 5] = 0f;
        quad[o + 6] = shade;
        quad[o + 7] = shade;
        quad[o + 8] = shade + 0.004f;
        quad[o + 9] = 0f;
    }

    public void draw(SceneProgram program) {
        if (vbo == 0 || live == 0) return;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        program.bindAttributes();
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, live * 6, GLES20.GL_UNSIGNED_SHORT, 0);
    }

    public void dispose() {
        if (vbo != 0) {
            GLES20.glDeleteBuffers(2, new int[]{vbo, ibo}, 0);
            vbo = 0;
            ibo = 0;
        }
        live = 0;
        head = 0;
    }
}
