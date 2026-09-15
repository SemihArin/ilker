package com.ilker.opendrive.world;

import com.ilker.opendrive.gl.Frustum;
import com.ilker.opendrive.gl.SceneProgram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Streams chunks around the player. Generation runs on worker threads; only
 * the buffer upload and the draw calls touch the GL thread.
 */
public class World {

    /** Chunks kept loaded in each direction. */
    public static final int RADIUS = 4;
    private static final int DROP_RADIUS = RADIUS + 1;
    private static final int UPLOADS_PER_FRAME = 2;

    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final HashSet<Long> inFlight = new HashSet<>();
    private final ConcurrentLinkedQueue<ChunkData> ready = new ConcurrentLinkedQueue<>();
    private final ExecutorService pool;

    private int lastCx = Integer.MIN_VALUE;
    private int lastCz = Integer.MIN_VALUE;
    private int rescanCountdown;
    private volatile boolean shuttingDown = false;

    private int drawnChunks;

    public World() {
        pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "chunk-gen");
                t.setPriority(Thread.MIN_PRIORITY + 2);
                t.setDaemon(true);
                return t;
            }
        });
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    public static int chunkCoord(float world) {
        return (int) Math.floor(world / Terrain.CHUNK);
    }

    /** Call once per frame on the GL thread. */
    public void update(float playerX, float playerZ) {
        int pcx = chunkCoord(playerX);
        int pcz = chunkCoord(playerZ);

        if (pcx != lastCx || pcz != lastCz) {
            lastCx = pcx;
            lastCz = pcz;
            requestAround(pcx, pcz);
            dropDistant(pcx, pcz);
            rescanCountdown = 90;
        } else if (--rescanCountdown <= 0) {
            // Safety net: a chunk whose result arrived while it was out of
            // range was thrown away, and only a re-scan will ask for it again.
            rescanCountdown = 90;
            requestAround(pcx, pcz);
        }

        int uploaded = 0;
        while (uploaded < UPLOADS_PER_FRAME) {
            ChunkData data = ready.poll();
            if (data == null) break;
            long k = key(data.cx, data.cz);
            inFlight.remove(k);
            if (Math.abs(data.cx - pcx) > DROP_RADIUS || Math.abs(data.cz - pcz) > DROP_RADIUS) {
                continue; // wandered off before it finished; throw it away
            }
            if (!chunks.containsKey(k)) {
                chunks.put(k, new Chunk(data));
                uploaded++;
            }
        }
    }

    private void requestAround(final int pcx, final int pcz) {
        List<int[]> wanted = new ArrayList<>();
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long k = key(cx, cz);
                if (chunks.containsKey(k) || inFlight.contains(k)) continue;
                wanted.add(new int[]{cx, cz, dx * dx + dz * dz});
            }
        }
        Collections.sort(wanted, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Integer.compare(a[2], b[2]);
            }
        });
        for (int[] w : wanted) {
            final int cx = w[0];
            final int cz = w[1];
            inFlight.add(key(cx, cz));
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (shuttingDown) return;
                        try {
                            ready.add(ChunkBuilder.generate(cx, cz));
                        } catch (Throwable ignored) {
                            // A failed chunk simply stays empty rather than
                            // taking the render thread down with it.
                        }
                    }
                });
            } catch (RuntimeException rejected) {
                inFlight.remove(key(cx, cz));
            }
        }
    }

    private void dropDistant(int pcx, int pcz) {
        Iterator<Map.Entry<Long, Chunk>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            Chunk c = it.next().getValue();
            if (Math.abs(c.cx - pcx) > DROP_RADIUS || Math.abs(c.cz - pcz) > DROP_RADIUS) {
                c.dispose();
                it.remove();
            }
        }
    }

    public void draw(SceneProgram program, Frustum frustum, float[] viewProj, float[] identity) {
        drawnChunks = 0;
        program.setMatrices(viewProj, identity);
        for (Chunk c : chunks.values()) {
            if (!frustum.sphereVisible(c.centerX, c.centerY, c.centerZ, c.radius)) continue;
            c.draw(program);
            drawnChunks++;
        }
    }

    public int getDrawnChunks() {
        return drawnChunks;
    }

    public int getLoadedChunks() {
        return chunks.size();
    }

    public boolean isBusy() {
        return !inFlight.isEmpty();
    }

    /** Frees GPU buffers; call on the GL thread when the surface goes away. */
    public void disposeAll() {
        for (Chunk c : chunks.values()) {
            c.dispose();
        }
        chunks.clear();
        ready.clear();
        inFlight.clear();
        lastCx = Integer.MIN_VALUE;
        lastCz = Integer.MIN_VALUE;
    }

    public void shutdown() {
        shuttingDown = true;
        pool.shutdownNow();
        try {
            pool.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
