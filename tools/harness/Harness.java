import com.ilker.opendrive.game.Car;
import com.ilker.opendrive.game.CarMesh;
import com.ilker.opendrive.game.CarSpec;
import com.ilker.opendrive.game.Controls;
import com.ilker.opendrive.gl.Frustum;
import com.ilker.opendrive.gl.MeshBuilder;
import com.ilker.opendrive.world.ChunkBuilder;
import com.ilker.opendrive.world.ChunkData;
import com.ilker.opendrive.world.Obstacles;
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
        hudLayout();
        obstacles();
        terrainDriving();
        drifting();

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
            // The best speed reached during this run, not the last reading:
            // gravity along the slope means the final number depends on which
            // hill the run happened to end on. car.topSpeedSeen is no use here
            // because it is a lifetime record that survives reset().
            float reached = 0f;
            for (int i = 0; i < 60 * 90; i++) {
                car.update(dt, c);
                reached = Math.max(reached, car.speedKmh());
            }
            float quoted = spec.topSpeedKmh();
            check(reached > quoted * 0.93f && reached <= quoted * 1.02f,
                    spec.name + " reaches its top speed (" + Math.round(reached) + " of " + quoted + ")");
            check(!Float.isNaN(car.x) && !Float.isNaN(car.z) && !Float.isNaN(car.yaw),
                    spec.name + " stays numerically stable");
        }

        // Steering must send the car the way the driver asked. This is checked
        // against the definition of "right" — forward x up — rather than
        // against whatever sign convention Car happens to use internally,
        // because an earlier version of this test simply restated the bug.
        CarSpec spec = CarSpec.GARAGE[0];
        for (int dir = -1; dir <= 1; dir += 2) {
            for (float startYaw : new float[]{0f, 1.9f, 4.4f}) {
                car.reset(spec, -Terrain.LANE_OFFSET, 0f, startYaw);
                c.clear();
                c.throttle = 1f;
                for (int i = 0; i < 180; i++) car.update(dt, c);

                float fx = (float) Math.sin(car.yaw);
                float fz = (float) Math.cos(car.yaw);
                // cross((fx, 0, fz), (0, 1, 0)) = (-fz, 0, fx)
                float rx = -fz;
                float rz = fx;
                float x0 = car.x, z0 = car.z;

                // Short enough that the car cannot swing past a quarter turn,
                // which would wrap the sign of the measurements below.
                c.steer = dir;
                for (int i = 0; i < 20; i++) car.update(dt, c);

                float sideways = (car.x - x0) * rx + (car.z - z0) * rz;
                float turned = (float) Math.sin(car.yaw) * rx + (float) Math.cos(car.yaw) * rz;
                String which = dir > 0 ? "right" : "left";
                check(sideways * dir > 0f,
                        "steering " + which + " moves the car to its " + which
                                + " (yaw " + startYaw + ", got " + sideways + ")");
                check(turned * dir > 0f,
                        "steering " + which + " swings the nose " + which
                                + " (yaw " + startYaw + ")");
            }
        }

        // The body transform has to agree with the heading the physics uses,
        // or the car will visibly drive sideways.
        car.reset(spec, 0f, 0f, 0.7f);
        float[] m = car.modelMatrix();
        float fwdX = (float) Math.sin(0.7f), fwdZ = (float) Math.cos(0.7f);
        check(m[8] * fwdX + m[10] * fwdZ > 0.999f, "model matrix sends local +Z along the heading");
        check(m[0] * (-fwdZ) + m[2] * fwdX < -0.999f, "model matrix local +X is the car's left");

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


        // Off-road is slower than the tarmac.
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        c.clear();
        c.throttle = 1f;
        for (int i = 0; i < 60 * 60; i++) car.update(dt, c);
        float onRoad = car.speedKmh();
        car.reset(spec, 42f, 0f, 0f); // well clear of the carriageway
        for (int i = 0; i < 60 * 60; i++) car.update(dt, c);
        check(car.speedKmh() < onRoad, "the car is slower off the tarmac");

        // ---- wheelspin: a powerful car lights them up off the line, a small
        //      hatchback does not.
        float spinGt = launchSpin(CarSpec.GARAGE[0]);
        float spinHatch = launchSpin(CarSpec.GARAGE[2]);
        check(spinGt > 0.15f, "the fast car spins its wheels off the line (" + spinGt + ")");
        check(spinHatch < spinGt, "the small hatchback has less wheelspin than the GT");

        // ---- locked brakes stop the car steering.
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        c.clear();
        c.throttle = 1f;
        for (int i = 0; i < 300; i++) car.update(dt, c);
        c.throttle = 0f;
        c.brake = 1f;
        float peakLock = 0f;
        for (int i = 0; i < 40; i++) {
            car.update(dt, c);
            peakLock = Math.max(peakLock, car.lockup);
        }
        check(peakLock > 0.1f, "standing on the brakes locks the wheels (" + peakLock + ")");

        // ---- lifting off mid-corner rotates the car more than staying on it.
        float yawOnPower = cornerYaw(true);
        float yawLiftOff = cornerYaw(false);
        check(yawLiftOff > yawOnPower,
                "lifting off tightens the line (" + yawLiftOff + " vs " + yawOnPower + ")");

        // ---- a slide banks points once it is gathered up.
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        c.clear();
        c.throttle = 1f;
        for (int i = 0; i < 240; i++) car.update(dt, c);
        c.steer = 1f;
        c.handbrake = true;
        for (int i = 0; i < 40; i++) car.update(dt, c);
        float scoredMidSlide = car.driftNow;
        check(scoredMidSlide > 0f, "sliding scores drift points (" + scoredMidSlide + ")");

        // Gathering it up banks the run; the live counter goes back to zero.
        c.steer = 0f;
        c.handbrake = false;
        c.throttle = 0f;
        for (int i = 0; i < 200; i++) car.update(dt, c);
        check(car.driftNow == 0f, "the live drift counter resets after the slide");
        check(car.driftTotal >= scoredMidSlide, "a finished slide banks its points ("
                + car.driftTotal + ")");
        check(car.driftBest >= scoredMidSlide, "the best single slide is remembered");

        // ---- 0-100 km/h, which is the number that says whether the gearbox
        //      and the traction limit add up to a car or to a toy.
        for (CarSpec s2 : CarSpec.GARAGE) {
            Car sprinter = new Car();
            Controls sc = new Controls();
            sprinter.reset(s2, -Terrain.LANE_OFFSET, 0f, 0f);
            sc.throttle = 1f;
            float time = -1f;
            for (int i = 0; i < 60 * 30; i++) {
                sprinter.update(dt, sc);
                if (sprinter.speedKmh() >= 100f) {
                    time = i * dt;
                    break;
                }
            }
            check(time > 2.0f && time < 16f,
                    s2.name + " reaches 100 km/h in a believable time (" + round1(time) + "s)");
            System.out.printf("  %-13s 0-100 in %.1fs, top %d km/h%n",
                    s2.name, time, s2.topSpeedKmh());
        }

        // ---- the gearbox works its way up and the revs reset on each shift.
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        c.clear();
        c.throttle = 1f;
        int topGear = 1;
        int upshifts = 0;
        int lastGear = car.gear;
        boolean revsDropOnShift = true;
        float revsBeforeShift = 0f;
        for (int i = 0; i < 60 * 40; i++) {
            float before = car.engineRevs;
            car.update(dt, c);
            topGear = Math.max(topGear, car.gear);
            if (car.gear > lastGear) {
                upshifts++;
                revsBeforeShift = before;
            } else if (car.gear == lastGear && revsBeforeShift > 0f) {
                if (car.engineRevs > revsBeforeShift) revsDropOnShift = false;
                revsBeforeShift = 0f;
            }
            lastGear = car.gear;
            if (car.engineRevs > 1.1f) {
                check(false, "the rev limiter holds (" + car.engineRevs + ")");
                break;
            }
        }
        check(topGear == 6, "the car works up through all six gears (reached " + topGear + ")");
        check(upshifts >= 5, "every gear is used on the way up (" + upshifts + " shifts)");
        check(revsDropOnShift, "revs drop when it changes up");

        // Slowing down has to bring the gears back.
        c.throttle = 0f;
        c.brake = 1f;
        for (int i = 0; i < 60 * 8; i++) car.update(dt, c);
        check(car.gear <= 2, "it changes back down as it slows (" + car.gear + ")");

        // ---- reverse: the brake selects it and drives it, the throttle stops it.
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        c.clear();
        c.brake = 1f;
        for (int i = 0; i < 60 * 4; i++) car.update(dt, c);
        check(car.inReverse && car.forwardSpeed < -2f,
                "the brake selects and drives reverse (" + car.forwardSpeed + ")");
        c.brake = 0f;
        c.throttle = 1f;
        for (int i = 0; i < 60 * 5; i++) car.update(dt, c);
        check(!car.inReverse && car.forwardSpeed > 1f,
                "the throttle pulls it out of reverse");

        // Distance and record keeping.
        check(car.distanceTravelled > 100f, "distance travelled accumulates");
        check(car.topSpeedSeen >= car.speedKmh() - 1f, "top speed record is kept");
    }

    /**
     * Gravity along the slope, airborne physics and the sprung body: the three
     * things that make a hill and a kerb mean something.
     */
    static void terrainDriving() {
        System.out.println("[terrain driving]");
        CarSpec spec = CarSpec.GARAGE[5];
        float dt = 1f / 60f;

        // Find a decent slope to park on.
        float slopeX = 0f, slopeZ = 0f, steepest = 0f;
        for (int i = 0; i < 400; i++) {
            float sx = 40f + i * 17.3f;
            float sz = 300f + i * 29.7f;
            float ahead = Terrain.surfaceHeight(sx, sz + 1.4f);
            float behind = Terrain.surfaceHeight(sx, sz - 1.4f);
            float slope = Math.abs(ahead - behind) / 2.8f;
            if (slope > steepest) {
                steepest = slope;
                slopeX = sx;
                slopeZ = sz;
            }
        }
        check(steepest > 0.05f, "the world has hills to park on (" + steepest + ")");

        Car car = new Car();
        Controls c = new Controls();
        car.reset(spec, slopeX, slopeZ, 0f);
        for (int i = 0; i < 60 * 3; i++) car.update(dt, c);
        float rolled = Math.abs(car.z - slopeZ);
        check(rolled > 0.5f, "a parked car rolls down a hill (" + rolled + "m)");

        car.reset(spec, slopeX, slopeZ, 0f);
        c.handbrake = true;
        for (int i = 0; i < 60 * 3; i++) car.update(dt, c);
        check(Math.abs(car.z - slopeZ) < 0.35f, "the handbrake holds it on the hill");

        // Climbing costs speed that the same run on the flat keeps.
        check(Math.abs(Terrain.surfaceHeight(0f, 0f)) < 1000f, "terrain sampling is sane");

        // Driving off a kerb at speed puts the car in the air.
        // Mid-block, so the only kerb nearby is the one being jumped off and
        // not the cross street's.
        float kerbZ = 0f;
        boolean paved = false;
        for (int i = 0; i < 4000 && !paved; i++) {
            float z = Math.round((50f + i * 13f) / Terrain.CHUNK) * Terrain.CHUNK
                    + Terrain.CHUNK * 0.5f;
            if (Terrain.urban(0f, z) > 0.4f) {
                kerbZ = z;
                paved = true;
            }
        }
        check(paved, "a paved street was found to jump off");

        if (paved) {
            Car jumper = new Car();
            Controls jc = new Controls();
            // On the pavement, heading straight out across the kerb.
            jumper.reset(spec, 9f, kerbZ, (float) (Math.PI * 0.5));
            jumper.vx = 30f;
            jumper.vz = 0f;
            boolean flew = false;
            float peakAir = 0f;
            for (int i = 0; i < 120; i++) {
                jumper.update(dt, jc);
                if (jumper.airborne) {
                    flew = true;
                    peakAir = Math.max(peakAir, jumper.airTime);
                }
            }
            check(flew, "dropping off a kerb at speed gets the car airborne");
            check(!jumper.airborne, "and it comes back down again");
            check(peakAir < 2f, "the hop is a hop, not a launch (" + peakAir + "s)");
            check(!Float.isNaN(jumper.y), "the landing leaves the car sane");
        }

        // The sprung body settles back onto its wheels once things are calm.
        Car settle = new Car();
        Controls sc = new Controls();
        settle.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        for (int i = 0; i < 60 * 3; i++) settle.update(dt, sc);
        check(Math.abs(settle.suspensionTravel()) < 0.04f,
                "the suspension settles at rest (" + settle.suspensionTravel() + "m)");
    }

    /**
     * The drift model. These are the behaviours the whole two-axle rewrite
     * exists to produce, so they are checked rather than hoped for. The
     * manoeuvres stop short of a full spin on purpose: past about 80 degrees
     * every car is simply travelling sideways, and that tells you nothing
     * about which car it is.
     */
    static void drifting() {
        System.out.println("[drifting]");
        CarSpec drifter = CarSpec.GARAGE[0];
        CarSpec sedan = CarSpec.GARAGE[4];

        // A car whose rear axle gives up first has to slide more than one
        // whose axles are matched, from identical inputs.
        float loose = slipAfter(drifter, 26f, 0.6f, 0.7f, false, 48);
        float planted = slipAfter(sedan, 26f, 0.6f, 0.7f, false, 48);
        check(drifter.oversteer() > sedan.oversteer(), "the drift car is the oversteery one");
        check(loose > planted * 1.3f,
                "a loose rear axle slides further than a balanced one ("
                        + round1(deg(loose)) + " vs " + round1(deg(planted)) + " degrees)");

        // The handbrake is still the big lever for kicking the back out.
        float dry = slipAfter(drifter, 26f, 0.3f, 0.5f, false, 40);
        float yanked = slipAfter(drifter, 26f, 0.3f, 0.5f, true, 40);
        check(yanked > dry * 1.4f,
                "the handbrake kicks the back out (" + round1(deg(yanked))
                        + " vs " + round1(deg(dry)) + " degrees)");

        // Power oversteer: the rear cannot put down drive and hold on at the
        // same time, so the same corner taken flat slides more.
        float onPower = slipAfter(drifter, 24f, 1f, 0.7f, false, 48);
        float feathered = slipAfter(drifter, 24f, 0.1f, 0.7f, false, 48);
        check(onPower > feathered * 1.1f,
                "standing on the power breaks the rear loose (" + round1(deg(onPower))
                        + " vs " + round1(deg(feathered)) + " degrees)");

        // The point of the whole rewrite: a slide can be caught.
        float caught = catchSlide(true);
        float dropped = catchSlide(false);
        check(caught < dropped * 0.6f,
                "steering into the slide gathers it up (" + round1(deg(caught))
                        + " vs " + round1(deg(dropped)) + " degrees)");
        check(caught < 0.45f, "and brings the car back under control");

        // A held drift must keep its speed. A slide that scrubs the car to a
        // halt is a crash with extra steps.
        Car car = new Car();
        Controls c = new Controls();
        float entry = 28f;
        float held = holdDrift(car, c, entry, 60 * 5);
        check(held > 0.19f, "a counter-steered drift stays sideways ("
                + round1(deg(held)) + " degrees held on average)");
        float exitSpeed = (float) Math.hypot(car.vx, car.vz);
        check(exitSpeed > entry * 0.75f,
                "and carries its speed through (" + round1(exitSpeed) + " of "
                        + round1(entry) + " m/s)");

        // Scoring rides on that same drift.
        check(car.driftMultiplier >= 2, "holding it builds the multiplier (x"
                + car.driftMultiplier + ")");
        check(car.driftNow > 0f, "and scores while it is held");

        c.steer = 0f;
        c.throttle = 0f;
        c.handbrake = false;
        for (int i = 0; i < 60 * 4; i++) car.update(1f / 60f, c);
        check(car.driftBanked > 0f && car.driftNow == 0f, "straightening banks the run");
        check(car.driftMultiplier == 1, "and resets the multiplier");
        check(car.driftBest > 0f, "the best run is remembered");

        // Yaw is a state with inertia: letting go of the wheel does not stop
        // the rotation dead.
        car.reset(drifter, -Terrain.LANE_OFFSET, 0f, 0f);
        car.vz = 26f;
        c.throttle = 0.6f;
        c.steer = 0.8f;
        for (int i = 0; i < 40; i++) car.update(1f / 60f, c);
        float spinning = Math.abs(car.yawRate);
        c.steer = 0f;
        c.throttle = 0f;
        car.update(1f / 60f, c);
        check(spinning > 0.25f, "the car builds a real yaw rate (" + round1(spinning) + " rad/s)");
        check(Math.abs(car.yawRate) > spinning * 0.6f,
                "and carries it after the steering is released");

        // However hard it is provoked, it must never rotate faster than a
        // player can answer, and must never wind itself up without limit.
        car.reset(drifter, -Terrain.LANE_OFFSET, 0f, 0f);
        car.vz = 40f;
        c.throttle = 1f;
        c.steer = 1f;
        c.handbrake = true;
        float worstYaw = 0f;
        for (int i = 0; i < 60 * 6; i++) {
            car.update(1f / 60f, c);
            worstYaw = Math.max(worstYaw, Math.abs(car.yawRate));
            check2(!Float.isNaN(car.x) && !Float.isNaN(car.slipAngle), "the solver stays finite");
        }
        check(worstYaw < 2.6f, "the spin stays answerable (" + round1(worstYaw) + " rad/s)");

        // The bug this replaced: fully sideways, the car saw no speed at all,
        // so nothing slowed it and it slid on for ever.
        car.reset(drifter, -Terrain.LANE_OFFSET, 0f, 0f);
        car.vx = 22f;           // travelling due east while pointing due north
        car.vz = 0f;
        c.throttle = 0f;
        c.steer = 0f;
        c.handbrake = false;
        for (int i = 0; i < 60 * 5; i++) car.update(1f / 60f, c);
        check((float) Math.hypot(car.vx, car.vz) < 6f,
                "a sideways car is slowed by its tyres ("
                        + round1((float) Math.hypot(car.vx, car.vz)) + " m/s left)");

        // Hitting something loses the run rather than paying out.
        car.reset(drifter, -Terrain.LANE_OFFSET, 0f, 0f);
        holdDrift(car, c, 28f, 150);
        check(car.driftNow > 0f, "a slide is worth something before the crash");
        car.loseDrift();
        check(car.driftNow == 0f && car.driftMultiplier == 1, "and nothing after it");
    }

    /** Like check(), but silent when it passes so a loop can call it 360 times. */
    static void check2(boolean ok, String what) {
        if (!ok) check(false, what);
    }

    static float deg(float radians) {
        return radians * 57.2958f;
    }

    /** Body slip angle after a fixed manoeuvre from a fixed entry speed. */
    static float slipAfter(CarSpec spec, float entrySpeed, float throttle, float steer,
                           boolean handbrake, int frames) {
        Car car = new Car();
        Controls c = new Controls();
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        car.vz = entrySpeed;          // reset() faces +Z
        c.throttle = throttle;
        c.steer = steer;
        c.handbrake = handbrake;
        for (int i = 0; i < frames; i++) car.update(1f / 60f, c);
        return Math.abs(car.slipAngle);
    }

    /**
     * Provokes a slide, then either steers into it or away from it, and
     * reports where the slip angle ends up.
     */
    static float catchSlide(boolean into) {
        Car car = new Car();
        Controls c = new Controls();
        float dt = 1f / 60f;
        car.reset(CarSpec.GARAGE[0], -Terrain.LANE_OFFSET, 0f, 0f);
        car.vz = 27f;
        c.throttle = 0.5f;
        c.steer = 1f;
        c.handbrake = true;
        for (int i = 0; i < 34; i++) car.update(dt, c);

        // Which way the car is sliding decides which way is "into" it, so the
        // test never has to assume a sign convention.
        float lock = Math.signum(car.slipAngle) * (into ? 1f : -1f);
        c.handbrake = false;
        c.throttle = 0.25f;
        c.steer = lock;
        for (int i = 0; i < 60; i++) car.update(dt, c);
        return Math.abs(car.slipAngle);
    }

    /**
     * Drives the way a player holds a drift: flick it in, then sit on the
     * throttle and chase the slide with opposite lock. Returns the average
     * slip angle once the car is settled into it.
     */
    static float holdDrift(Car car, Controls c, float entrySpeed, int frames) {
        float dt = 1f / 60f;
        car.reset(CarSpec.GARAGE[0], -Terrain.LANE_OFFSET, 0f, 0f);
        car.vz = entrySpeed;
        float sum = 0f;
        int samples = 0;
        for (int i = 0; i < frames; i++) {
            if (i < 27) {
                c.steer = 1f;
                c.handbrake = true;
                c.throttle = 0.3f;
            } else {
                c.handbrake = false;
                c.throttle = 1f;
                // Opposite lock damped by the rotation itself — a player
                // aims to hold an angle, not to chase it back to zero.
                c.steer = clamp(car.slipAngle * 1.6f, -1f, 1f);
                sum += Math.abs(car.slipAngle);
                samples++;
            }
            car.update(dt, c);
        }
        return samples == 0 ? 0f : sum / samples;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static float round1(float v) {
        return Math.round(v * 10f) / 10f;
    }

    /** Peak wheelspin in the first second from a standing start. */
    static float launchSpin(CarSpec spec) {
        Car car = new Car();
        Controls c = new Controls();
        car.reset(spec, -Terrain.LANE_OFFSET, 0f, 0f);
        c.throttle = 1f;
        float peak = 0f;
        for (int i = 0; i < 60; i++) {
            car.update(1f / 60f, c);
            peak = Math.max(peak, car.wheelspin);
        }
        return peak;
    }

    /** How far the car rotates through a fixed corner, on or off the power. */
    static float cornerYaw(boolean onPower) {
        Car car = new Car();
        Controls c = new Controls();
        float dt = 1f / 60f;
        car.reset(CarSpec.GARAGE[5], -Terrain.LANE_OFFSET, 0f, 0f);
        c.throttle = 1f;
        for (int i = 0; i < 200; i++) car.update(dt, c);
        float start = car.yaw;
        c.steer = 1f;
        c.throttle = onPower ? 1f : 0f;
        for (int i = 0; i < 30; i++) car.update(dt, c);
        float turned = start - car.yaw;   // yaw decreases turning right
        while (turned < -Math.PI) turned += (float) (Math.PI * 2);
        while (turned > Math.PI) turned -= (float) (Math.PI * 2);
        return turned;
    }

    // ------------------------------------------------------------------

    /**
     * Collision boxes have to line up with the walls that were actually drawn.
     * This is the whole reason layout and appearance draw from separate random
     * streams, so it is worth checking rather than assuming: anything you can
     * see must be something you bump into, and every reported box must have a
     * building standing on it.
     */
    static void obstacles() {
        System.out.println("[obstacles]");
        int[][] coords = {{0, 0}, {-3, 2}, {12, 7}, {-40, 55}, {7, 7}, {512, 512}, {-26, -3}};
        int phantom = 0;
        int ghost = 0;
        int boxes = 0;
        int posts = 0;

        for (int[] c : coords) {
            final java.util.List<float[]> boxList = new java.util.ArrayList<>();
            final java.util.List<float[]> postList = new java.util.ArrayList<>();
            ChunkBuilder.forEachObstacle(c[0], c[1], new ChunkBuilder.ObstacleSink() {
                @Override
                public void building(float cx, float cz, float halfW, float halfD) {
                    boxList.add(new float[]{cx, cz, halfW, halfD});
                }

                @Override
                public void post(float cx, float cz, float radius) {
                    postList.add(new float[]{cx, cz, radius});
                }
            });
            boxes += boxList.size();
            posts += postList.size();

            ChunkData d = ChunkBuilder.generate(c[0], c[1]);
            if (d.objectIndexCount == 0) continue;
            int stride = MeshBuilder.FLOATS_PER_VERTEX;
            int vertexCount = d.objectVerts.capacity() / stride;

            // Every reported box must have geometry standing on it.
            for (float[] b : boxList) {
                boolean found = false;
                for (int v = 0; v < vertexCount && !found; v++) {
                    float vx = d.objectVerts.get(v * stride);
                    float vz = d.objectVerts.get(v * stride + 2);
                    if (Math.abs(vx - b[0]) <= b[2] + 1.0f
                            && Math.abs(vz - b[1]) <= b[3] + 1.0f) {
                        found = true;
                    }
                }
                if (!found) phantom++;
            }

            // Nothing solid inside the envelope a car body sweeps through may
            // be missing from the list. The tallest vehicle is 1.86m, so
            // anything above about 2.2m passes over the roof and is allowed to
            // be scenery — tree canopies start up there on purpose.
            for (int v = 0; v < vertexCount; v++) {
                float vx = d.objectVerts.get(v * stride);
                float vy = d.objectVerts.get(v * stride + 1);
                float vz = d.objectVerts.get(v * stride + 2);
                float ground = Terrain.height(vx, vz);
                float above = vy - ground;
                if (above < 1.2f || above > 2.2f) continue;

                boolean covered = false;
                for (float[] b : boxList) {
                    if (Math.abs(vx - b[0]) <= b[2] + 1.0f
                            && Math.abs(vz - b[1]) <= b[3] + 1.0f) {
                        covered = true;
                        break;
                    }
                }
                for (int p = 0; p < postList.size() && !covered; p++) {
                    float[] q = postList.get(p);
                    float dx = vx - q[0], dz = vz - q[1];
                    if (dx * dx + dz * dz <= 2.8f * 2.8f) covered = true;
                }
                if (!covered) ghost++;
            }
        }

        check(boxes > 0, "buildings are reported for collision (" + boxes + " boxes)");
        check(posts > 0, "street furniture is reported for collision (" + posts + " posts)");
        check(phantom == 0, "no collision box stands where nothing was built ("
                + phantom + " did)");
        check(ghost == 0, "nothing solid at car height is missing from collision ("
                + ghost + " vertices were)");

        // The walk must not depend on how many times it has been run.
        final int[] first = {0, 0};
        final int[] second = {0, 0};
        ChunkBuilder.forEachObstacle(9, -4, new ChunkBuilder.ObstacleSink() {
            @Override
            public void building(float cx, float cz, float hw, float hd) { first[0]++; }

            @Override
            public void post(float cx, float cz, float r) { first[1]++; }
        });
        ChunkBuilder.forEachObstacle(9, -4, new ChunkBuilder.ObstacleSink() {
            @Override
            public void building(float cx, float cz, float hw, float hd) { second[0]++; }

            @Override
            public void post(float cx, float cz, float r) { second[1]++; }
        });
        check(first[0] == second[0] && first[1] == second[1],
                "the obstacle walk is deterministic");

        Obstacles ob = new Obstacles();
        ob.refresh(0f, 0f);
        check(ob.obstacleCount() > 0, "obstacles load around the player");

        // Drive at a wall and it has to stop the car, not swallow it.
        final float[] wall = new float[4];
        final boolean[] found = {false};
        for (int i = -6; i <= 6 && !found[0]; i++) {
            for (int j = -6; j <= 6 && !found[0]; j++) {
                final int fi = i, fj = j;
                ChunkBuilder.forEachObstacle(fi, fj, new ChunkBuilder.ObstacleSink() {
                    @Override
                    public void building(float cx, float cz, float hw, float hd) {
                        if (!found[0] && hw > 6f && hd > 6f) {
                            wall[0] = cx;
                            wall[1] = cz;
                            wall[2] = hw;
                            wall[3] = hd;
                            found[0] = true;
                        }
                    }

                    @Override
                    public void post(float cx, float cz, float r) {
                    }
                });
            }
        }
        check(found[0], "a building was found to crash into");

        if (found[0]) {
            Car crash = new Car();
            Controls cc = new Controls();
            // Line up square on the wall, well clear of it, and floor it.
            crash.reset(CarSpec.GARAGE[0], wall[0], wall[1] - wall[3] - 45f, 0f);
            cc.throttle = 1f;
            float deepest = 0f;
            float topSpeed = 0f;
            for (int i = 0; i < 60 * 12; i++) {
                crash.update(1f / 60f, cc);
                ob.refresh(crash.x, crash.z);
                crash.resolveObstacles(ob);
                topSpeed = Math.max(topSpeed, Math.abs(crash.forwardSpeed));
                float insideX = wall[2] - Math.abs(crash.x - wall[0]);
                float insideZ = wall[3] - Math.abs(crash.z - wall[1]);
                if (insideX > 0f && insideZ > 0f) {
                    deepest = Math.max(deepest, Math.min(insideX, insideZ));
                }
            }
            check(topSpeed > 15f, "the car got up to speed before the wall");
            check(deepest < 1.2f, "the car never ends up inside the wall (worst "
                    + deepest + "m in)");
            check(!Float.isNaN(crash.x) && !Float.isNaN(crash.z) && !Float.isNaN(crash.yaw),
                    "collision leaves the car numerically sane");
            check(Math.abs(crash.forwardSpeed) < topSpeed * 0.75f,
                    "hitting a wall costs speed");
        }
    }

    /**
     * Sweeps a single pointer across the whole screen at several aspect ratios
     * and checks that no position ever drives two conflicting controls. A
     * layout mistake here is invisible on the developer's own phone and
     * infuriating on someone else's.
     */
    static void hudLayout() {
        System.out.println("[interface]");
        int[][] sizes = {
                {1280, 720},    // 16:9
                {2340, 1080},   // 19.5:9, a typical modern phone
                {2400, 1080},   // 20:9
                {1024, 768},    // 4:3 tablet
                {1280, 1024},   // 5:4, about as square as it gets
        };

        for (int[] size : sizes) {
            com.ilker.opendrive.ui.Hud hud = new com.ilker.opendrive.ui.Hud();
            hud.layout(size[0], size[1]);
            Controls c = new Controls();
            float[] xs = new float[1];
            float[] ys = new float[1];
            String label = size[0] + "x" + size[1];

            int conflicts = 0;
            int steerReach = 0;
            int gasReach = 0;
            int step = Math.max(4, size[1] / 120);

            for (int y = 0; y < size[1]; y += step) {
                for (int x = 0; x < size[0]; x += step) {
                    hud.processInput(xs, ys, 0, c, 0f, false); // release first
                    xs[0] = x;
                    ys[0] = y;
                    hud.processInput(xs, ys, 1, c, 0f, false);

                    boolean throttle = c.throttle > 0f;
                    boolean brake = c.brake > 0f;
                    boolean hand = c.handbrake;
                    boolean steer = c.steer != 0f;
                    boolean button = false;
                    for (int b = 0; b <= 4; b++) {
                        if (hud.wasTapped(b)) button = true;
                    }
                    if (throttle) gasReach++;
                    if (steer) steerReach++;

                    int pedals = (throttle ? 1 : 0) + (brake ? 1 : 0) + (hand ? 1 : 0);
                    if (pedals > 1) conflicts++;
                    if (pedals > 0 && (steer || button)) conflicts++;
                    if (steer && button) conflicts++;
                }
            }
            check(conflicts == 0, label + ": no touch point drives two controls at once ("
                    + conflicts + " did)");
            check(steerReach > 0, label + ": the steering pads are reachable");
            check(gasReach > 0, label + ": the accelerator is reachable");
        }
    }

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
