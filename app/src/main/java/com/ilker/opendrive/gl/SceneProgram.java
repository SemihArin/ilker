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
            "uniform vec3 uHeadL;\n" +
            "uniform vec3 uHeadR;\n" +
            "uniform vec3 uHeadDir;\n" +
            "uniform float uHeadOn;\n" +
            "uniform vec4 uHeadShape;\n" +   // outer cos, inner cos, near, far
            "uniform float uHeadPower;\n" +
            "uniform float uNight;\n" +
            "varying vec3 vWorld;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vColor;\n" +
            "varying float vEmissive;\n" +
            "float beam(vec3 lamp, vec3 world, vec3 n) {\n" +
            "  vec3 d = world - lamp;\n" +
            "  float dist = length(d);\n" +
            "  vec3 dir = d / max(dist, 0.001);\n" +
            "  float cone = smoothstep(uHeadShape.x, uHeadShape.y, dot(dir, uHeadDir));\n" +
            "  float atten = 1.0 - smoothstep(uHeadShape.z, uHeadShape.w, dist);\n" +
            // Wrapped so the road ahead, which the beam only grazes, still
            // lights up instead of falling to nothing.
            "  float facing = 0.42 + 0.58 * max(dot(n, -dir), 0.0);\n" +
            "  return cone * atten * facing;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  float diff = max(dot(n, uSunDir), 0.0);\n" +
            "  float sky = 0.5 + 0.5 * n.y;\n" +
            "  vec3 lit = vColor * (uAmbient * sky + uSunColor * diff);\n" +
            // Two lamps, not one: a single central cone gives the car a
            // cyclops beam that never lights the verge it is turning towards.
            "  if (uHeadOn > 0.5) {\n" +
            "    float b = beam(uHeadL, vWorld, n) + beam(uHeadR, vWorld, n);\n" +
            "    lit += vColor * vec3(1.0, 0.95, 0.82) * b * uHeadPower;\n" +
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
    private int uCamPos, uHeadL, uHeadR, uHeadDir, uHeadOn, uHeadShape, uHeadPower, uNight;

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
        uHeadL = GLES20.glGetUniformLocation(program, "uHeadL");
        uHeadR = GLES20.glGetUniformLocation(program, "uHeadR");
        uHeadDir = GLES20.glGetUniformLocation(program, "uHeadDir");
        uHeadOn = GLES20.glGetUniformLocation(program, "uHeadOn");
        uHeadShape = GLES20.glGetUniformLocation(program, "uHeadShape");
        uHeadPower = GLES20.glGetUniformLocation(program, "uHeadPower");
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

    /**
     * Positions of the two lamps and the direction they point. Turning them
     * off is a separate flag rather than a zero power so the branch is cheap.
     */
    public void setHeadlights(boolean on,
                              float lx, float ly, float lz,
                              float rx, float ry, float rz,
                              float dx, float dy, float dz) {
        GLES20.glUniform1f(uHeadOn, on ? 1f : 0f);
        GLES20.glUniform3f(uHeadL, lx, ly, lz);
        GLES20.glUniform3f(uHeadR, rx, ry, rz);
        GLES20.glUniform3f(uHeadDir, dx, dy, dz);
    }

    /** Beam shape: cone edges as cosines, the range it fades over, and gain. */
    public void setBeam(float coneOuter, float coneInner, float near, float far, float power) {
        GLES20.glUniform4f(uHeadShape, coneOuter, coneInner, near, far);
        GLES20.glUniform1f(uHeadPower, power);
    }
}
