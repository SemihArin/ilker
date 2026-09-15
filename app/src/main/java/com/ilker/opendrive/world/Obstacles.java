package com.ilker.opendrive.world;

/**
 * The solid things near the player, kept as a flat list so collision is a
 * handful of array passes rather than a walk through the world generator.
 *
 * The list is rebuilt from {@link ChunkBuilder#forEachObstacle} whenever the
 * player crosses into a new chunk, which means the boxes always agree with the
 * walls that were actually drawn.
 */
public class Obstacles implements ChunkBuilder.ObstacleSink {

    /** Chunks kept either side of the player. One is ample: obstacles only
     *  matter within a couple of metres, and a chunk is a hundred. */
    private static final int RADIUS = 1;

    private float[] boxes = new float[256 * 4];   // cx, cz, halfW, halfD
    private int boxCount;
    private float[] posts = new float[512 * 3];   // cx, cz, radius
    private int postCount;

    private int centreI = Integer.MIN_VALUE;
    private int centreJ = Integer.MIN_VALUE;

    /** Call once a frame; rebuilds only when the player changes chunk. */
    public void refresh(float x, float z) {
        int i = (int) Math.floor(x / Terrain.CHUNK);
        int j = (int) Math.floor(z / Terrain.CHUNK);
        if (i == centreI && j == centreJ) return;
        centreI = i;
        centreJ = j;
        boxCount = 0;
        postCount = 0;
        for (int dj = -RADIUS; dj <= RADIUS; dj++) {
            for (int di = -RADIUS; di <= RADIUS; di++) {
                ChunkBuilder.forEachObstacle(i + di, j + dj, this);
            }
        }
    }

    @Override
    public void building(float cx, float cz, float halfW, float halfD) {
        if (boxCount * 4 + 4 > boxes.length) {
            float[] bigger = new float[boxes.length * 2];
            System.arraycopy(boxes, 0, bigger, 0, boxes.length);
            boxes = bigger;
        }
        int o = boxCount * 4;
        boxes[o] = cx;
        boxes[o + 1] = cz;
        boxes[o + 2] = halfW;
        boxes[o + 3] = halfD;
        boxCount++;
    }

    @Override
    public void post(float cx, float cz, float radius) {
        if (postCount * 3 + 3 > posts.length) {
            float[] bigger = new float[posts.length * 2];
            System.arraycopy(posts, 0, bigger, 0, posts.length);
            posts = bigger;
        }
        int o = postCount * 3;
        posts[o] = cx;
        posts[o + 1] = cz;
        posts[o + 2] = radius;
        postCount++;
    }

    public int obstacleCount() {
        return boxCount + postCount;
    }

    /**
     * Pushes a circle out of anything it overlaps, accumulating the correction
     * into push[0], push[1]. Returns true if it touched something.
     */
    public boolean pushOut(float px, float pz, float radius, float[] push) {
        boolean hit = false;

        for (int b = 0; b < boxCount; b++) {
            int o = b * 4;
            float cx = boxes[o], cz = boxes[o + 1], hw = boxes[o + 2], hd = boxes[o + 3];
            if (px < cx - hw - radius || px > cx + hw + radius
                    || pz < cz - hd - radius || pz > cz + hd + radius) {
                continue;
            }
            float qx = clamp(px, cx - hw, cx + hw);
            float qz = clamp(pz, cz - hd, cz + hd);
            float dx = px - qx;
            float dz = pz - qz;
            float d2 = dx * dx + dz * dz;
            if (d2 > radius * radius) continue;

            if (d2 > 1e-5f) {
                float d = (float) Math.sqrt(d2);
                float k = (radius - d) / d;
                push[0] += dx * k;
                push[1] += dz * k;
            } else {
                // Centre is inside the wall; leave by the nearest face.
                float left = px - (cx - hw);
                float right = (cx + hw) - px;
                float back = pz - (cz - hd);
                float front = (cz + hd) - pz;
                float best = Math.min(Math.min(left, right), Math.min(back, front));
                if (best == left) push[0] -= left + radius;
                else if (best == right) push[0] += right + radius;
                else if (best == back) push[1] -= back + radius;
                else push[1] += front + radius;
            }
            hit = true;
        }

        for (int p = 0; p < postCount; p++) {
            int o = p * 3;
            float cx = posts[o], cz = posts[o + 1], pr = posts[o + 2];
            float reach = radius + pr;
            float dx = px - cx;
            float dz = pz - cz;
            float d2 = dx * dx + dz * dz;
            if (d2 > reach * reach) continue;
            float d = (float) Math.sqrt(d2);
            if (d > 1e-4f) {
                float k = (reach - d) / d;
                push[0] += dx * k;
                push[1] += dz * k;
            } else {
                push[0] += reach;
            }
            hit = true;
        }
        return hit;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
