package com.ilker.opendrive.world;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** CPU-side result of generating one chunk, ready for upload on the GL thread. */
public class ChunkData {

    public final int cx;
    public final int cz;

    public FloatBuffer groundVerts;
    public ShortBuffer groundIndices;
    public int groundIndexCount;

    public FloatBuffer objectVerts;
    public ShortBuffer objectIndices;
    public int objectIndexCount;

    public float centerX;
    public float centerY;
    public float centerZ;
    public float radius;

    public ChunkData(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }
}
