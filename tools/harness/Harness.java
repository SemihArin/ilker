import com.ilker.opendrive.game.Car;
import com.ilker.opendrive.game.CarMesh;
import com.ilker.opendrive.game.CarSpec;
import com.ilker.opendrive.game.Controls;
import com.ilker.opendrive.gl.Frustum;
import com.ilker.opendrive.gl.MeshBuilder;
import com.ilker.opendrive.world.ChunkBuilder;
import com.ilker.opendrive.world.ChunkData;
import com.ilker.opendrive.world.Terrain;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Off-device checks against the game's real source files. */
public class Harness {

    static int failures = 0;
    static int checks = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        terrain();
        chunks();
        carMeshes();
        physics();
        frustum();

        System.out.println();
        System.out.println(failures == 0
                ? ("ALL " + checks + " CHECKS PASSED")
                : (failures + " OF " + checks + " CHECKS FAILED"));
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    static void terrain() {
        System.out.println("[terrain]");
        boolean finite = true;
        float maxStep = 0f;
        float prev = Terrain.height(-500f, 0f);
        for (float x = -500f; x <= 500f; x += 0.5f) {
            float h = Terrain.height(x, 37f);
            if (Float.isNaN(h) || Float.isInfinite(h)) finite = false;
            maxStep = Math.max(maxStep, Math.abs(h - prev));
            prev = h;
        }
        check(finite, "terrain height is finite everywhere");
        check(maxStep < 0.6f, "terrain is continuous (max step over 0.5m was " + maxStep + ")");

        // Determinism: the same coordinate must always give the same answer.
        check(Terrain.height(1234.5f, -876.25f) == Terrain.height(1234.5f, -876.25f),
                "terrain height is deterministic");

        // The drawn surface must agree with the mesh grid corners exactly.
        float gx = 12 * Terrain.GRID, gz = -7 * Terrain.GRID;
        check(Math.abs(Terrain.height(gx, gz) - Terrain.rawHeight(gx, gz)) < 1e-3f,
                "drawn height matches raw height at mesh vertices");

        // Road surface sits above the landscape and the kerb step is bounded.
        float worstStep = 0f;
        for (float d = 0f; d < 30f; d += 0.25f) {
            float a = Terrain.surfaceHeight(d, 400f);
            float b = Terrain.surfaceHeight(d + 0.25f, 400f);
            if (Float.isNaN(a)) { check(false, "surfaceHeight NaN"); break; }
            worstStep = Math.max(worstStep, Math.abs(a - b));
        }
        check(worstStep < 0.25f, "kerbs are a bump not a wall (worst 0.25m step: " + worstStep + ")");

        check(Terrain.surfaceHeight(0f, 60f) > Terrain.height(0f, 60f),
                "tarmac sits above bare ground");
        check(Terrain.CELLS * Terrain.GRID == Terrain.CHUNK, "chunk divides evenly into cells");

        float gripRoad = Terrain.surfaceGrip(0f, 50f);
        float gripGrass = Terrain.surfaceGrip(40f, 50f);
        check(gripRoad > gripGrass, "tarmac grips better than grass");
    }

    // ------------------------------------------------------------------

    static void chunks() {
        System.out.println("[chunks]");
        // Includes chunks far from the origin, where float precision is
        // tightest and winding decisions are easiest to get wrong.
        int[][] coords = {{0, 0}, {1, 0}, {-3, 2}, {12, 7}, {-40, 55}, {103, -87}, {7, 7},
                {512, 512}, {-900, 130}};
        int maxVerts = 0;
        int totalTris = 0;
        int upsetGround = 0;
        int upsetObjects = 0;

        for (int[] c : coords) {
            ChunkData d;
            try {
                d = ChunkBuilder.generate(c[0], c[1]);
            } catch (Throwable t) {
                check(false, "chunk " + c[0] + "," + c[1] + " threw " + t);
                continue;
            }
            check(d.groundIndexCount > 0, "chunk " + c[0] + "," + c[1] + " has ground geometry");
            check(d.radius > 0f && !Float.isNaN(d.radius), "chunk bounding radius is sane");

            maxVerts = Math.max(maxVerts, verify(d.groundVerts, d.groundIndices, d.groundIndexCount,
                    "ground " + c[0] + "," + c[1]));
            totalTris += d.groundIndexCount / 3;
            upsetGround += countMisfacing(d.groundVerts, d.groundIndices, d.groundIndexCount, true);

            if (d.objectIndexCount > 0) {
                maxVerts = Math.max(maxVerts, verify(d.objectVerts, d.objectIndices,
                        d.objectIndexCount, "objects " + c[0] + "," + c[1]));
                totalTris += d.objectIndexCount / 3;
                upsetObjects += countMisfacing(d.objectVerts, d.objectIndices, d.objectIndexCount, false);
            }
        }

        check(maxVerts <= MeshBuilder.MAX_VERTICES,
                "meshes stay inside the 16-bit index limit (worst " + maxVerts + ")");
        check(upsetGround == 0, "every ground triangle faces the sky (" + upsetGround + " did not)");
        check(upsetObjects == 0, "every object triangle faces outwards (" + upsetObjects + " did not)");
        System.out.println("  " + (totalTris / coords.length) + " triangles per chunk on average, "
                + maxVerts + " vertices in the largest mesh");

        // Determinism across threads: the same chunk twice must match.
        ChunkData a = ChunkBuilder.generate(5, -9);
        ChunkData b = ChunkBuilder.generate(5, -9);
        boolean same = a.groundIndexCount == b.groundIndexCount
                && a.objectIndexCount == b.objectIndexCount;
        if (same) {
            a.groundVerts.position(0);
            b.groundVerts.position(0);
            for (int i = 0; i < a.groundVerts.capacity(); i++) {
                if (a.groundVerts.get(i) != b.groundVerts.get(i)) { same = false; break; }
            }
        }
        check(same, "chunk generation is deterministic");
    }

    /** Index bounds plus NaN sweep; returns the vertex count. */
    static int verify(FloatBuffer verts, ShortBuffer idx, int indexCount, String label) {
        int stride = MeshBuilder.FLOATS_PER_VERTEX;
        int vertexCount = verts.capacity() / stride;
        check(indexCount % 3 == 0, label + ": index count is a whole number of triangles");
        for (int i = 0; i < verts.capacity(); i++) {
            float v = verts.get(i);
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                check(false, label + ": vertex data contains NaN/Inf at " + i);
                break;
            }
        }
        for (int i = 0; i < indexCount; i++) {
            int index = idx.get(i) & 0xffff;
            if (index >= vertexCount) {
                check(false, label + ": index " + index + " out of range " + vertexCount);
                break;
            }
        }
        // Normals must be unit length or the lighting goes wrong.
        for (int v = 0; v < vertexCount; v += Math.max(1, vertexCount / 200)) {
            float nx = verts.get(v * stride + 3);
            float ny = verts.get(v * stride + 4);
            float nz = verts.get(v * stride + 5);
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (Math.abs(len - 1f) > 0.02f) {
                check(false, label + ": normal is not unit length (" + len + ")");
                break;
            }
        }
        return vertexCount;
    }

    /**
     * Counts triangles whose winding disagrees with their own stored normals —
     * exactly the geometry that back-face culling would swallow.
     */
    static int countMisfacing(FloatBuffer verts, ShortBuffer idx, int indexCount, boolean mustPointUp) {
        int stride = MeshBuilder.FLOATS_PER_VERTEX;
        int bad = 0;
        for (int t = 0; t < indexCount; t += 3) {
            int i0 = idx.get(t) & 0xffff;
            int i1 = idx.get(t + 1) & 0xffff;
            int i2 = idx.get(t + 2) & 0xffff;

            float ax = verts.get(i0 * stride), ay = verts.get(i0 * stride + 1), az = verts.get(i0 * stride + 2);
            float bx = verts.get(i1 * stride), by = verts.get(i1 * stride + 1), bz = verts.get(i1 * stride + 2);
            float cx = verts.get(i2 * stride), cy = verts.get(i2 * stride + 1), cz = verts.get(i2 * stride + 2);

            float ux = bx - ax, uy = by - ay, uz = bz - az;
            float wx = cx - ax, wy = cy - ay, wz = cz - az;
            float nx = uy * wz - uz * wy;
            float ny = uz * wx - ux * wz;
            float nz = ux * wy - uy * wx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-7f) continue; // degenerate sliver, culled either way

            nx /= len; ny /= len; nz /= len;

            float sx = 0f, sy = 0f, sz = 0f;
            int[] ids = {i0, i1, i2};
            for (int id : ids) {
                sx += verts.get(id * stride + 3);
                sy += verts.get(id * stride + 4);
                sz += verts.get(id * stride + 5);
            }
            if (nx * sx + ny * sy + nz * sz < 0f) {
                bad++;
                continue;
            }
            if (mustPointUp && ny < 0.05f) bad++;
        }
        return bad;
    }

    // ------------------------------------------------------------------

    static void carMeshes() {
        System.out.println("[vehicles]");
        for (CarSpec spec : CarSpec.GARAGE) {
            MeshBuilder body = CarMesh.buildBody(spec, spec.colorR, spec.colorG, spec.colorB);
            MeshBuilder wheel = CarMesh.buildWheel(spec);
            check(!body.isEmpty(), spec.name + " body has geometry");
            check(!wheel.isEmpty(), spec.name + " wheel has geometry");
            check(body.vertexCount() < MeshBuilder.MAX_VERTICES, spec.name + " body fits the index limit");

            int bad = countMisfacing(body.buildVertexBuffer(), body.buildIndexBuffer(),
                    body.indexCount(), false);
            check(bad == 0, spec.name + " body winding is consistent (" + bad + " bad triangles)");

            check(spec.roofY > spec.beltY && spec.beltY > spec.sillY, spec.name + " body lines are ordered");
            check(spec.wheelbase < spec.length && spec.track < spec.width,
                    spec.name + " wheels sit inside the bodywork");
            check(spec.topSpeed > 10f && spec.enginePower > 1f, spec.name + " has usable performance");
        }
    }

    // ------------------------------------------------------------------

    static void physics() {
        System.out.println("[physics]");
        Car car = new Car();
        Controls c = new Controls();
        float dt = 1f / 60f;

        // Flat out in a straight line should approach the quoted top speed.
        for (CarSpec spec : CarSpec.GARAGE) {
            car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
            c.clear();
            c.throttle = 1f;
            for (int i = 0; i < 60 * 90; i++) car.update(dt, c);
            float reached = car.speedKmh();
            float quoted = spec.topSpeedKmh();
            check(reached > quoted * 0.90f && reached <= quoted * 1.02f,
                    spec.name + " reaches its top speed (" + Math.round(reached) + " of " + quoted + ")");
            check(!Float.isNaN(car.x) && !Float.isNaN(car.z) && !Float.isNaN(car.yaw),
                    spec.name + " stays numerically stable");
        }

        // Steering right must yaw right and actually move the car sideways.
        CarSpec spec = CarSpec.GARAGE[0];
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        c.clear();
        c.throttle = 1f;
        for (int i = 0; i < 180; i++) car.update(dt, c);
        float startX = car.x;
        float startYaw = car.yaw;
        c.steer = 1f;
        for (int i = 0; i < 120; i++) car.update(dt, c);
        check(car.yaw > startYaw, "steering right increases yaw");
        check(car.x > startX, "steering right moves the car to +X from a +Z heading");

        // Braking stops the car, then reverses it.
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        c.clear();
        c.throttle = 1f;
        for (int i = 0; i < 300; i++) car.update(dt, c);
        float fast = car.forwardSpeed;
        c.throttle = 0f;
        c.brake = 1f;
        for (int i = 0; i < 60 * 8; i++) car.update(dt, c);
        check(fast > 20f, "car got up to speed before braking");
        check(car.forwardSpeed < -1f, "holding the brake at a standstill selects reverse");

        // The handbrake should break traction: more lateral slip for the same input.
        float gripSlip = cornerSlip(false);
        float slideSlip = cornerSlip(true);
        check(slideSlip > gripSlip * 1.5f,
                "handbrake produces a slide (" + gripSlip + " vs " + slideSlip + ")");

        // Off-road is slower than the tarmac.
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        c.clear();
        c.throttle = 1f;
        for (int i = 0; i < 60 * 60; i++) car.update(dt, c);
        float onRoad = car.speedKmh();
        car.reset(spec, 42f, 0f, 0f); // well clear of the carriageway
        for (int i = 0; i < 60 * 60; i++) car.update(dt, c);
        check(car.speedKmh() < onRoad, "the car is slower off the tarmac");

        // Distance and record keeping.
        check(car.distanceTravelled > 100f, "distance travelled accumulates");
        check(car.topSpeedSeen >= car.speedKmh() - 1f, "top speed record is kept");
    }

    static float cornerSlip(boolean handbrake) {
        Car car = new Car();
        Controls c = new Controls();
        float dt = 1f / 60f;
        car.reset(CarSpec.GARAGE[0], -Terrain.LANE_OFFSET, 0f, 0f);
        c.throttle = 1f;
        for (int i = 0; i < 240; i++) car.update(dt, c);
        c.steer = 1f;
        c.handbrake = handbrake;
        float peak = 0f;
        for (int i = 0; i < 90; i++) {
            car.update(dt, c);
            peak = Math.max(peak, Math.abs(car.lateralSpeed));
        }
        return peak;
    }

    // ------------------------------------------------------------------

    static void frustum() {
        System.out.println("[culling]");
        float[] proj = new float[16];
        float[] view = new float[16];
        float[] vp = new float[16];
        android.opengl.Matrix.frustumM(proj, 0, -0.5f, 0.5f, -0.3f, 0.3f, 0.5f, 800f);
        // Eye at the origin looking down +Z.
        android.opengl.Matrix.setLookAtM(view, 0, 0f, 0f, 0f, 0f, 0f, 10f, 0f, 1f, 0f);
        android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        Frustum f = new Frustum();
        f.set(vp);
        check(f.sphereVisible(0f, 0f, 50f, 5f), "a sphere straight ahead is visible");
        check(!f.sphereVisible(0f, 0f, -50f, 5f), "a sphere behind the camera is culled");
        check(!f.sphereVisible(0f, 0f, 2000f, 5f), "a sphere beyond the far plane is culled");
        check(f.sphereVisible(0f, 0f, -2f, 60f), "a big sphere around the camera stays visible");
        check(!f.sphereVisible(400f, 0f, 50f, 5f), "a sphere far off to the side is culled");
    }
}
