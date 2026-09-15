package com.ilker.opendrive.gl;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Immediate-mode 2D renderer for the interface: coloured triangles in screen
 * space, streamed from a single client-side buffer once per frame.
 */
public class HudProgram {

    private static final String VERTEX_SRC =
            "uniform mat4 uProj;\n" +
            "attribute vec2 aPos;\n" +
            "attribute vec4 aColor;\n" +
            "varying vec4 vColor;\n" +
            "void main() {\n" +
            "  vColor = aColor;\n" +
            "  gl_Position = uProj * vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SRC =
            "precision mediump float;\n" +
            "varying vec4 vColor;\n" +
            "void main() {\n" +
            "  gl_FragColor = vColor;\n" +
            "}\n";

    private static final int FLOATS_PER_VERTEX = 6;
    private static final int MAX_VERTICES = 48000;

    private int program;
    private int aPos, aColor, uProj;

    private final float[] scratch = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private int used = 0;
    private FloatBuffer buffer;

    private final float[] proj = new float[16];

    public void create() {
        program = ShaderUtil.buildProgram(VERTEX_SRC, FRAGMENT_SRC);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aColor = GLES20.glGetAttribLocation(program, "aColor");
        uProj = GLES20.glGetUniformLocation(program, "uProj");
        ByteBuffer bb = ByteBuffer.allocateDirect(scratch.length * 4).order(ByteOrder.nativeOrder());
        buffer = bb.asFloatBuffer();
    }

    /** Sets up an orthographic projection with the origin at the top-left. */
    public void setViewport(int width, int height) {
        android.opengl.Matrix.orthoM(proj, 0, 0f, width, height, 0f, -1f, 1f);
    }

    public void begin() {
        used = 0;
    }

    private void vertex(float x, float y, float r, float g, float b, float a) {
        if (used + FLOATS_PER_VERTEX > scratch.length) return;
        scratch[used] = x;
        scratch[used + 1] = y;
        scratch[used + 2] = r;
        scratch[used + 3] = g;
        scratch[used + 4] = b;
        scratch[used + 5] = a;
        used += FLOATS_PER_VERTEX;
    }

    public void triangle(float x0, float y0, float x1, float y1, float x2, float y2,
                         float r, float g, float b, float a) {
        vertex(x0, y0, r, g, b, a);
        vertex(x1, y1, r, g, b, a);
        vertex(x2, y2, r, g, b, a);
    }

    /** Vertical gradient, top colour to bottom colour. */
    public void gradientRect(float x, float y, float w, float h,
                             float r0, float g0, float b0, float a0,
                             float r1, float g1, float b1, float a1) {
        vertex(x, y, r0, g0, b0, a0);
        vertex(x + w, y, r0, g0, b0, a0);
        vertex(x + w, y + h, r1, g1, b1, a1);
        vertex(x, y, r0, g0, b0, a0);
        vertex(x + w, y + h, r1, g1, b1, a1);
        vertex(x, y + h, r1, g1, b1, a1);
    }

    public void rect(float x, float y, float w, float h, float r, float g, float b, float a) {
        triangle(x, y, x + w, y, x + w, y + h, r, g, b, a);
        triangle(x, y, x + w, y + h, x, y + h, r, g, b, a);
    }

    /** Rectangle rotated about its own centre. */
    public void rotatedRect(float cx, float cy, float w, float h, float angle,
                            float r, float g, float b, float a) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        float hw = w * 0.5f, hh = h * 0.5f;
        float[] xs = {-hw, hw, hw, -hw};
        float[] ys = {-hh, -hh, hh, hh};
        float[] px = new float[4];
        float[] py = new float[4];
        for (int i = 0; i < 4; i++) {
            px[i] = cx + xs[i] * c - ys[i] * s;
            py[i] = cy + xs[i] * s + ys[i] * c;
        }
        triangle(px[0], py[0], px[1], py[1], px[2], py[2], r, g, b, a);
        triangle(px[0], py[0], px[2], py[2], px[3], py[3], r, g, b, a);
    }

    public void line(float x0, float y0, float x1, float y1, float thickness,
                     float r, float g, float b, float a) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return;
        float nx = -dy / len * thickness * 0.5f;
        float ny = dx / len * thickness * 0.5f;
        triangle(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, r, g, b, a);
        triangle(x0 + nx, y0 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, r, g, b, a);
    }

    public void circle(float cx, float cy, float radius, int segments,
                       float r, float g, float b, float a) {
        if (segments < 3) segments = 3;
        float prevX = cx + radius, prevY = cy;
        for (int i = 1; i <= segments; i++) {
            double ang = (Math.PI * 2.0 * i) / segments;
            float x = cx + (float) Math.cos(ang) * radius;
            float y = cy + (float) Math.sin(ang) * radius;
            triangle(cx, cy, prevX, prevY, x, y, r, g, b, a);
            prevX = x;
            prevY = y;
        }
    }

    public void ring(float cx, float cy, float inner, float outer, int segments,
                     float startAngle, float sweep, float r, float g, float b, float a) {
        if (segments < 1) segments = 1;
        for (int i = 0; i < segments; i++) {
            float a0 = startAngle + sweep * i / segments;
            float a1 = startAngle + sweep * (i + 1) / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float ix0 = cx + c0 * inner, iy0 = cy + s0 * inner;
            float ox0 = cx + c0 * outer, oy0 = cy + s0 * outer;
            float ix1 = cx + c1 * inner, iy1 = cy + s1 * inner;
            float ox1 = cx + c1 * outer, oy1 = cy + s1 * outer;
            triangle(ix0, iy0, ox0, oy0, ox1, oy1, r, g, b, a);
            triangle(ix0, iy0, ox1, oy1, ix1, iy1, r, g, b, a);
        }
    }

    public void end() {
        if (used == 0) return;
        // Never submit a partial triangle if the buffer filled up mid-shape.
        int vertices = (used / FLOATS_PER_VERTEX) / 3 * 3;
        if (vertices == 0) return;

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uProj, 1, false, proj, 0);

        buffer.position(0);
        buffer.put(scratch, 0, used);
        buffer.position(0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

        buffer.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, buffer);
        buffer.position(2);
        GLES20.glEnableVertexAttribArray(aColor);
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, buffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertices);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aColor);
        buffer.position(0);
    }
}
