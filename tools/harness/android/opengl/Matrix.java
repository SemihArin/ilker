package android.opengl;

/**
 * Desktop stand-in for the framework class, matching AOSP semantics
 * (column-major storage, in-place post-multiplication) so the game's real
 * source files can be exercised off-device.
 */
public class Matrix {

    private static final float[] temp = new float[32];

    public static void setIdentityM(float[] sm, int smOffset) {
        for (int i = 0; i < 16; i++) sm[smOffset + i] = 0f;
        for (int i = 0; i < 16; i += 5) sm[smOffset + i] = 1f;
    }

    public static void multiplyMM(float[] result, int resultOffset,
                                  float[] lhs, int lhsOffset, float[] rhs, int rhsOffset) {
        float[] out = new float[16];
        for (int i = 0; i < 4; i++) {        // column of rhs / result
            for (int j = 0; j < 4; j++) {    // row
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += lhs[lhsOffset + k * 4 + j] * rhs[rhsOffset + i * 4 + k];
                }
                out[i * 4 + j] = sum;
            }
        }
        System.arraycopy(out, 0, result, resultOffset, 16);
    }

    public static void multiplyMV(float[] resultVec, int resultVecOffset,
                                  float[] lhsMat, int lhsMatOffset,
                                  float[] rhsVec, int rhsVecOffset) {
        float[] out = new float[4];
        for (int j = 0; j < 4; j++) {
            float sum = 0f;
            for (int k = 0; k < 4; k++) {
                sum += lhsMat[lhsMatOffset + k * 4 + j] * rhsVec[rhsVecOffset + k];
            }
            out[j] = sum;
        }
        System.arraycopy(out, 0, resultVec, resultVecOffset, 4);
    }

    public static void translateM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int mi = mOffset + i;
            m[12 + mi] += m[mi] * x + m[4 + mi] * y + m[8 + mi] * z;
        }
    }

    public static void scaleM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int mi = mOffset + i;
            m[mi] *= x;
            m[4 + mi] *= y;
            m[8 + mi] *= z;
        }
    }

    public static void setRotateM(float[] rm, int rmOffset, float a, float x, float y, float z) {
        rm[rmOffset + 3] = 0;
        rm[rmOffset + 7] = 0;
        rm[rmOffset + 11] = 0;
        rm[rmOffset + 12] = 0;
        rm[rmOffset + 13] = 0;
        rm[rmOffset + 14] = 0;
        rm[rmOffset + 15] = 1;
        a *= (float) (Math.PI / 180.0f);
        float s = (float) Math.sin(a);
        float c = (float) Math.cos(a);
        if (1.0f == x && 0.0f == y && 0.0f == z) {
            rm[rmOffset + 5] = c; rm[rmOffset + 10] = c;
            rm[rmOffset + 6] = s; rm[rmOffset + 9] = -s;
            rm[rmOffset + 1] = 0; rm[rmOffset + 2] = 0;
            rm[rmOffset + 4] = 0; rm[rmOffset + 8] = 0;
            rm[rmOffset + 0] = 1;
        } else if (0.0f == x && 1.0f == y && 0.0f == z) {
            rm[rmOffset + 0] = c; rm[rmOffset + 10] = c;
            rm[rmOffset + 8] = s; rm[rmOffset + 2] = -s;
            rm[rmOffset + 1] = 0; rm[rmOffset + 4] = 0;
            rm[rmOffset + 6] = 0; rm[rmOffset + 9] = 0;
            rm[rmOffset + 5] = 1;
        } else if (0.0f == x && 0.0f == y && 1.0f == z) {
            rm[rmOffset + 0] = c; rm[rmOffset + 5] = c;
            rm[rmOffset + 1] = s; rm[rmOffset + 4] = -s;
            rm[rmOffset + 2] = 0; rm[rmOffset + 6] = 0;
            rm[rmOffset + 8] = 0; rm[rmOffset + 9] = 0;
            rm[rmOffset + 10] = 1;
        } else {
            float len = length(x, y, z);
            if (1.0f != len) {
                float recipLen = 1.0f / len;
                x *= recipLen; y *= recipLen; z *= recipLen;
            }
            float nc = 1.0f - c;
            float xy = x * y, yz = y * z, zx = z * x;
            float xs = x * s, ys = y * s, zs = z * s;
            rm[rmOffset + 0] = x * x * nc + c;
            rm[rmOffset + 4] = xy * nc - zs;
            rm[rmOffset + 8] = zx * nc + ys;
            rm[rmOffset + 1] = xy * nc + zs;
            rm[rmOffset + 5] = y * y * nc + c;
            rm[rmOffset + 9] = yz * nc - xs;
            rm[rmOffset + 2] = zx * nc - ys;
            rm[rmOffset + 6] = yz * nc + xs;
            rm[rmOffset + 10] = z * z * nc + c;
        }
    }

    public static void rotateM(float[] m, int mOffset, float a, float x, float y, float z) {
        synchronized (temp) {
            setRotateM(temp, 0, a, x, y, z);
            multiplyMM(temp, 16, m, mOffset, temp, 0);
            System.arraycopy(temp, 16, m, mOffset, 16);
        }
    }

    public static void orthoM(float[] m, int mOffset, float left, float right,
                              float bottom, float top, float near, float far) {
        float r_width = 1.0f / (right - left);
        float r_height = 1.0f / (top - bottom);
        float r_depth = 1.0f / (far - near);
        m[mOffset + 0] = 2.0f * r_width;
        m[mOffset + 1] = 0.0f;
        m[mOffset + 2] = 0.0f;
        m[mOffset + 3] = 0.0f;
        m[mOffset + 4] = 0.0f;
        m[mOffset + 5] = 2.0f * r_height;
        m[mOffset + 6] = 0.0f;
        m[mOffset + 7] = 0.0f;
        m[mOffset + 8] = 0.0f;
        m[mOffset + 9] = 0.0f;
        m[mOffset + 10] = -2.0f * r_depth;
        m[mOffset + 11] = 0.0f;
        m[mOffset + 12] = -(right + left) * r_width;
        m[mOffset + 13] = -(top + bottom) * r_height;
        m[mOffset + 14] = -(far + near) * r_depth;
        m[mOffset + 15] = 1.0f;
    }

    public static void frustumM(float[] m, int offset, float left, float right,
                                float bottom, float top, float near, float far) {
        float r_width = 1.0f / (right - left);
        float r_height = 1.0f / (top - bottom);
        float r_depth = 1.0f / (near - far);
        float x = 2.0f * (near * r_width);
        float y = 2.0f * (near * r_height);
        float A = (right + left) * r_width;
        float B = (top + bottom) * r_height;
        float C = (far + near) * r_depth;
        float D = 2.0f * (far * near * r_depth);
        m[offset + 0] = x;
        m[offset + 5] = y;
        m[offset + 8] = A;
        m[offset + 9] = B;
        m[offset + 10] = C;
        m[offset + 14] = D;
        m[offset + 11] = -1.0f;
        m[offset + 1] = 0.0f;
        m[offset + 2] = 0.0f;
        m[offset + 3] = 0.0f;
        m[offset + 4] = 0.0f;
        m[offset + 6] = 0.0f;
        m[offset + 7] = 0.0f;
        m[offset + 12] = 0.0f;
        m[offset + 13] = 0.0f;
        m[offset + 15] = 0.0f;
    }

    public static void setLookAtM(float[] rm, int rmOffset,
                                  float eyeX, float eyeY, float eyeZ,
                                  float centerX, float centerY, float centerZ,
                                  float upX, float upY, float upZ) {
        float fx = centerX - eyeX;
        float fy = centerY - eyeY;
        float fz = centerZ - eyeZ;
        float rlf = 1.0f / length(fx, fy, fz);
        fx *= rlf; fy *= rlf; fz *= rlf;

        float sx = fy * upZ - fz * upY;
        float sy = fz * upX - fx * upZ;
        float sz = fx * upY - fy * upX;
        float rls = 1.0f / length(sx, sy, sz);
        sx *= rls; sy *= rls; sz *= rls;

        float ux = sy * fz - sz * fy;
        float uy = sz * fx - sx * fz;
        float uz = sx * fy - sy * fx;

        rm[rmOffset + 0] = sx;
        rm[rmOffset + 1] = ux;
        rm[rmOffset + 2] = -fx;
        rm[rmOffset + 3] = 0.0f;
        rm[rmOffset + 4] = sy;
        rm[rmOffset + 5] = uy;
        rm[rmOffset + 6] = -fy;
        rm[rmOffset + 7] = 0.0f;
        rm[rmOffset + 8] = sz;
        rm[rmOffset + 9] = uz;
        rm[rmOffset + 10] = -fz;
        rm[rmOffset + 11] = 0.0f;
        rm[rmOffset + 12] = 0.0f;
        rm[rmOffset + 13] = 0.0f;
        rm[rmOffset + 14] = 0.0f;
        rm[rmOffset + 15] = 1.0f;
        translateM(rm, rmOffset, -eyeX, -eyeY, -eyeZ);
    }

    public static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}
