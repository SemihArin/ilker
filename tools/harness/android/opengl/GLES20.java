package android.opengl;

import java.nio.Buffer;

/**
 * Do-nothing stand-in so the game's GL-facing classes compile off-device. The
 * harness only ever calls the parts of them that build geometry in plain Java;
 * nothing here is expected to do anything.
 */
public class GLES20 {

    public static final int GL_ARRAY_BUFFER = 0x8892;
    public static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    public static final int GL_STATIC_DRAW = 0x88E4;
    public static final int GL_TRIANGLES = 0x0004;
    public static final int GL_UNSIGNED_SHORT = 0x1403;
    public static final int GL_FLOAT = 0x1406;
    public static final int GL_VERTEX_SHADER = 0x8B31;
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;
    public static final int GL_DEPTH_TEST = 0x0B71;
    public static final int GL_CULL_FACE = 0x0B44;
    public static final int GL_BLEND = 0x0BE2;
    public static final int GL_BACK = 0x0405;
    public static final int GL_CCW = 0x0901;
    public static final int GL_LEQUAL = 0x0203;
    public static final int GL_SRC_ALPHA = 0x0302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    public static final int GL_COLOR_BUFFER_BIT = 0x4000;
    public static final int GL_DEPTH_BUFFER_BIT = 0x0100;

    public static void glGenBuffers(int n, int[] buffers, int offset) {
    }

    public static void glDeleteBuffers(int n, int[] buffers, int offset) {
    }

    public static void glBindBuffer(int target, int buffer) {
    }

    public static void glBufferData(int target, int size, Buffer data, int usage) {
    }

    public static void glDrawElements(int mode, int count, int type, int offset) {
    }

    public static void glDrawArrays(int mode, int first, int count) {
    }

    public static int glCreateShader(int type) {
        return 1;
    }

    public static void glShaderSource(int shader, String source) {
    }

    public static void glCompileShader(int shader) {
    }

    public static void glGetShaderiv(int shader, int pname, int[] params, int offset) {
        params[offset] = 1;
    }

    public static String glGetShaderInfoLog(int shader) {
        return "";
    }

    public static void glDeleteShader(int shader) {
    }

    public static int glCreateProgram() {
        return 1;
    }

    public static void glAttachShader(int program, int shader) {
    }

    public static void glLinkProgram(int program) {
    }

    public static void glGetProgramiv(int program, int pname, int[] params, int offset) {
        params[offset] = 1;
    }

    public static String glGetProgramInfoLog(int program) {
        return "";
    }

    public static void glDeleteProgram(int program) {
    }

    public static void glUseProgram(int program) {
    }

    public static int glGetAttribLocation(int program, String name) {
        return 0;
    }

    public static int glGetUniformLocation(int program, String name) {
        return 0;
    }

    public static void glEnableVertexAttribArray(int index) {
    }

    public static void glDisableVertexAttribArray(int index) {
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized,
                                             int stride, int offset) {
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized,
                                             int stride, Buffer ptr) {
    }

    public static void glUniformMatrix4fv(int location, int count, boolean transpose,
                                          float[] value, int offset) {
    }

    public static void glUniform3f(int location, float x, float y, float z) {
    }

    public static void glUniform1f(int location, float x) {
    }

    public static void glViewport(int x, int y, int width, int height) {
    }

    public static void glClear(int mask) {
    }

    public static void glClearColor(float r, float g, float b, float a) {
    }

    public static void glEnable(int cap) {
    }

    public static void glDisable(int cap) {
    }

    public static void glDepthFunc(int func) {
    }

    public static void glDepthMask(boolean flag) {
    }

    public static void glCullFace(int mode) {
    }

    public static void glFrontFace(int mode) {
    }

    public static void glBlendFunc(int src, int dst) {
    }
}
