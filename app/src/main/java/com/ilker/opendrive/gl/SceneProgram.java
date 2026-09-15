package com.ilker.opendrive.gl;

import android.opengl.GLES20;

/**
 * The single shader used for the whole 3D scene: one directional sun, ambient
 * sky fill, exponential fog, an emissive channel for windows and lamps, and a
 * pair of headlight cones for night driving.
 */
public class SceneProgram {

    private static final String VERTEX_SRC =
            "uniform mat4 uMvp;\n" +
            "uniform mat4 uModel;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNormal;\n" +
            "attribute vec3 aColor;\n" +
            "attribute float aEmissive;\n" +
            "varying vec3 vWorld;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vColor;\n" +
            "varying float vEmissive;\n" +
            "void main() {\n" +
            "  vec4 world = uModel * vec4(aPos, 1.0);\n" +
            "  vWorld = world.xyz;\n" +
            "  mat3 rot = mat3(uModel[0].xyz, uModel[1].xyz, uModel[2].xyz);\n" +
            "  vNormal = rot * aNormal;\n" +
            "  vColor = aColor;\n" +
            "  vEmissive = aEmissive;\n" +
            "  gl_Position = uMvp * vec4(aPos, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SRC =
            "precision mediump float;\n" +
            "uniform vec3 uSunDir;\n" +
            "uniform vec3 uSunColor;\n" +
            "uniform vec3 uAmbient;\n" +
            "uniform vec3 uFogColor;\n" +
            "uniform float uFogDensity;\n" +
            "uniform vec3 uCamPos;\n" +
            "uniform vec3 uHeadPos;\n" +
            "uniform vec3 uHeadDir;\n" +
            "uniform float uHeadOn;\n" +
            "uniform float uNight;\n" +
            "varying vec3 vWorld;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vColor;\n" +
            "varying float vEmissive;\n" +
            "void main() {\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  float diff = max(dot(n, uSunDir), 0.0);\n" +
            "  float sky = 0.5 + 0.5 * n.y;\n" +
            "  vec3 lit = vColor * (uAmbient * sky + uSunColor * diff);\n" +
            "  if (uHeadOn > 0.5) {\n" +
            "    vec3 d = vWorld - uHeadPos;\n" +
            "    float dist = length(d);\n" +
            "    vec3 dir = d / max(dist, 0.001);\n" +
            "    float cone = smoothstep(0.86, 0.972, dot(dir, uHeadDir));\n" +
            "    float atten = 1.0 - smoothstep(5.0, 65.0, dist);\n" +
            // Wrapped so the road ahead, which the beam only grazes, still
            // lights up instead of falling to nothing.
            "    float facing = 0.42 + 0.58 * max(dot(n, -dir), 0.0);\n" +
            "    lit += vColor * vec3(1.0, 0.95, 0.82) * cone * atten * facing * 5.5;\n" +
            "  }\n" +
            "  float glow = vEmissive * mix(0.15, 1.0, uNight);\n" +
            "  lit = mix(lit, vColor * 1.25, clamp(glow, 0.0, 1.0));\n" +
            "  float camDist = length(vWorld - uCamPos) * uFogDensity;\n" +
            "  float fog = clamp(1.0 - exp(-camDist * camDist), 0.0, 1.0);\n" +
            "  gl_FragColor = vec4(mix(lit, uFogColor, fog), 1.0);\n" +
            "}\n";

    private static final int STRIDE = MeshBuilder.FLOATS_PER_VERTEX * 4;

    private int program;
    private int aPos, aNormal, aColor, aEmissive;
    private int uMvp, uModel, uSunDir, uSunColor, uAmbient, uFogColor, uFogDensity;
    private int uCamPos, uHeadPos, uHeadDir, uHeadOn, uNight;

    public void create() {
        program = ShaderUtil.buildProgram(VERTEX_SRC, FRAGMENT_SRC);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aNormal = GLES20.glGetAttribLocation(program, "aNormal");
        aColor = GLES20.glGetAttribLocation(program, "aColor");
        aEmissive = GLES20.glGetAttribLocation(program, "aEmissive");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uModel = GLES20.glGetUniformLocation(program, "uModel");
        uSunDir = GLES20.glGetUniformLocation(program, "uSunDir");
        uSunColor = GLES20.glGetUniformLocation(program, "uSunColor");
        uAmbient = GLES20.glGetUniformLocation(program, "uAmbient");
        uFogColor = GLES20.glGetUniformLocation(program, "uFogColor");
        uFogDensity = GLES20.glGetUniformLocation(program, "uFogDensity");
        uCamPos = GLES20.glGetUniformLocation(program, "uCamPos");
        uHeadPos = GLES20.glGetUniformLocation(program, "uHeadPos");
        uHeadDir = GLES20.glGetUniformLocation(program, "uHeadDir");
        uHeadOn = GLES20.glGetUniformLocation(program, "uHeadOn");
        uNight = GLES20.glGetUniformLocation(program, "uNight");
    }

    public void use() {
        GLES20.glUseProgram(program);
    }

    public void bindAttributes() {
        attribute(aPos, 3, 0);
        attribute(aNormal, 3, 12);
        attribute(aColor, 3, 24);
        attribute(aEmissive, 1, 36);
    }

    private static void attribute(int location, int size, int offset) {
        if (location < 0) return; // optimised out of the linked program
        GLES20.glEnableVertexAttribArray(location);
        GLES20.glVertexAttribPointer(location, size, GLES20.GL_FLOAT, false, STRIDE, offset);
    }

    /**
     * ES 2.0 has no vertex array objects, so leaving these enabled would leak
     * into the interface pass.
     */
    public void disableAttributes() {
        if (aPos >= 0) GLES20.glDisableVertexAttribArray(aPos);
        if (aNormal >= 0) GLES20.glDisableVertexAttribArray(aNormal);
        if (aColor >= 0) GLES20.glDisableVertexAttribArray(aColor);
        if (aEmissive >= 0) GLES20.glDisableVertexAttribArray(aEmissive);
    }

    public void setMatrices(float[] mvp, float[] model) {
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
    }

    public void setLighting(float[] sunDir, float[] sunColor, float[] ambient,
                            float[] fogColor, float fogDensity, float night) {
        GLES20.glUniform3f(uSunDir, sunDir[0], sunDir[1], sunDir[2]);
        GLES20.glUniform3f(uSunColor, sunColor[0], sunColor[1], sunColor[2]);
        GLES20.glUniform3f(uAmbient, ambient[0], ambient[1], ambient[2]);
        GLES20.glUniform3f(uFogColor, fogColor[0], fogColor[1], fogColor[2]);
        GLES20.glUniform1f(uFogDensity, fogDensity);
        GLES20.glUniform1f(uNight, night);
    }

    public void setCamera(float x, float y, float z) {
        GLES20.glUniform3f(uCamPos, x, y, z);
    }

    public void setHeadlights(boolean on, float px, float py, float pz,
                              float dx, float dy, float dz) {
        GLES20.glUniform1f(uHeadOn, on ? 1f : 0f);
        GLES20.glUniform3f(uHeadPos, px, py, pz);
        GLES20.glUniform3f(uHeadDir, dx, dy, dz);
    }
}
