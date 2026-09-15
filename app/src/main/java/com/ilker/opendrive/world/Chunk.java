package com.ilker.opendrive.world;

import com.ilker.opendrive.gl.Mesh;
import com.ilker.opendrive.gl.SceneProgram;

/** One uploaded chunk: the ground it stands on and everything built on it. */
public class Chunk {

    public final int cx;
    public final int cz;
    public final float centerX;
    public final float centerY;
    public final float centerZ;
    public final float radius;

    private Mesh ground;
    private Mesh objects;

    public Chunk(ChunkData data) {
        this.cx = data.cx;
        this.cz = data.cz;
        this.centerX = data.centerX;
        this.centerY = data.centerY;
        this.centerZ = data.centerZ;
        this.radius = data.radius;
        if (data.groundIndexCount > 0) {
            ground = Mesh.upload(data.groundVerts, data.groundIndices, data.groundIndexCount);
        }
        if (data.objectIndexCount > 0) {
            objects = Mesh.upload(data.objectVerts, data.objectIndices, data.objectIndexCount);
        }
    }

    public void draw(SceneProgram program) {
        if (ground != null) ground.draw(program);
        if (objects != null) objects.draw(program);
    }

    public void dispose() {
        if (ground != null) {
            ground.dispose();
            ground = null;
        }
        if (objects != null) {
            objects.dispose();
            objects = null;
        }
    }
}
