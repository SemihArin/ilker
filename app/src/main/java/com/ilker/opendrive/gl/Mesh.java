package com.ilker.opendrive.gl;

import android.opengl.GLES20;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** A vertex/index buffer pair living on the GPU. */
public class Mesh {

    private int vbo = 0;
    private int ibo = 0;
    private int indexCount = 0;

    private Mesh() {
    }

    /** Uploads geometry; must be called on the GL thread. */
    public static Mesh upload(FloatBuffer vertices, ShortBuffer indices, int indexCount) {
        if (indexCount <= 0) return null;
        Mesh mesh = new Mesh();
        int[] ids = new int[2];
        GLES20.glGenBuffers(2, ids, 0);
        mesh.vbo = ids[0];
        mesh.ibo = ids[1];
        mesh.indexCount = indexCount;

        vertices.position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.capacity() * 4, vertices, GLES20.GL_STATIC_DRAW);

        indices.position(0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.capacity() * 2, indices, GLES20.GL_STATIC_DRAW);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        return mesh;
    }

    public static Mesh fromBuilder(MeshBuilder builder) {
        if (builder.isEmpty()) return null;
        return upload(builder.buildVertexBuffer(), builder.buildIndexBuffer(), builder.indexCount());
    }

    public int getIndexCount() {
        return indexCount;
    }

    public void draw(SceneProgram program) {
        if (indexCount <= 0) return;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        program.bindAttributes();
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0);
    }

    public void dispose() {
        if (vbo != 0) {
            GLES20.glDeleteBuffers(2, new int[]{vbo, ibo}, 0);
            vbo = 0;
            ibo = 0;
            indexCount = 0;
        }
    }
}
