package com.ilker.opendrive.gl;

/** Six view-frustum planes extracted from a combined view-projection matrix. */
public class Frustum {

    private final float[][] planes = new float[6][4];

    /** m is column-major, as produced by android.opengl.Matrix. */
    public void set(float[] m) {
        // Rows of the matrix, remembering the column-major layout.
        float r0x = m[0], r0y = m[4], r0z = m[8], r0w = m[12];
        float r1x = m[1], r1y = m[5], r1z = m[9], r1w = m[13];
        float r2x = m[2], r2y = m[6], r2z = m[10], r2w = m[14];
        float r3x = m[3], r3y = m[7], r3z = m[11], r3w = m[15];

        set(0, r3x + r0x, r3y + r0y, r3z + r0z, r3w + r0w); // left
        set(1, r3x - r0x, r3y - r0y, r3z - r0z, r3w - r0w); // right
        set(2, r3x + r1x, r3y + r1y, r3z + r1z, r3w + r1w); // bottom
        set(3, r3x - r1x, r3y - r1y, r3z - r1z, r3w - r1w); // top
        set(4, r3x + r2x, r3y + r2y, r3z + r2z, r3w + r2w); // near
        set(5, r3x - r2x, r3y - r2y, r3z - r2z, r3w - r2w); // far
    }

    private void set(int i, float a, float b, float c, float d) {
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        if (len < 1e-8f) len = 1f;
        planes[i][0] = a / len;
        planes[i][1] = b / len;
        planes[i][2] = c / len;
        planes[i][3] = d / len;
    }

    public boolean sphereVisible(float x, float y, float z, float radius) {
        for (int i = 0; i < 6; i++) {
            float[] p = planes[i];
            if (p[0] * x + p[1] * y + p[2] * z + p[3] < -radius) {
                return false;
            }
        }
        return true;
    }
}
