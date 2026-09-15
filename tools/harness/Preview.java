import com.ilker.opendrive.game.Car;
import com.ilker.opendrive.game.CarMesh;
import com.ilker.opendrive.game.CarSpec;
import com.ilker.opendrive.game.Controls;
import com.ilker.opendrive.gl.MeshBuilder;
import com.ilker.opendrive.world.ChunkBuilder;
import com.ilker.opendrive.world.ChunkData;
import com.ilker.opendrive.world.Terrain;
import android.opengl.Matrix;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Software rasteriser that mirrors the game's shader maths, so the world can
 * be eyeballed without a device. Not part of the app — a development tool.
 */
public class Preview {

    static int W = 960, H = 540;
    static float[] color = new float[W * H * 3];
    static float[] depth = new float[W * H];

    /** Re-targets the rasteriser at a different screen shape. */
    static void resize(int w, int h) {
        W = w;
        H = h;
        color = new float[W * H * 3];
        depth = new float[W * H];
    }

    static float[] sunDir = new float[3];
    static float[] sunColor = new float[3];
    static float[] ambient = new float[3];
    static float[] fog = new float[3];
    static float[] zenith = new float[3];
    static float camX, camY, camZ;
    static float fogDensity = 0.0042f;
    static float night;
    static boolean headlights;
    static float headLX, headLY, headLZ, headRX, headRY, headRZ;
    static float headDX, headDY, headDZ;
    static float beamOuter = 0.825f, beamInner = 0.968f, beamNear = 5f, beamFar = 70f, beamPower = 3.2f;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : ".");

        // A city street at midday.
        shot(out, "city-day.png", 0.40f, cityChunk(), 0);
        // The same spot at dusk with the lights on.
        shot(out, "city-night.png", 0.90f, cityChunk(), 0);
        // Open countryside.
        shot(out, "country-day.png", 0.35f, ruralChunk(), 3);
        // Every car in the garage, lined up.
        garage(out);
        System.out.println("previews written to " + out.getAbsolutePath());
    }

    static int[] cityChunk() {
        for (int i = -30; i < 30; i++) {
            for (int j = -30; j < 30; j++) {
                if (Terrain.urban(i * 100 + 50, j * 100 + 50) > 0.95f) return new int[]{i, j};
            }
        }
        return new int[]{0, 0};
    }

    static int[] ruralChunk() {
        for (int i = -30; i < 30; i++) {
            for (int j = -30; j < 30; j++) {
                if (Terrain.urban(i * 100 + 50, j * 100 + 50) < 0.02f) return new int[]{i, j};
            }
        }
        return new int[]{0, 0};
    }

    /** Set by {@link #scene}, so callers can draw the interface over it. */
    static Car lastCar;

    static void shot(File dir, String name, float timeOfDay, int[] chunk, int specIndex)
            throws Exception {
        lighting(timeOfDay);
        scene(chunk, specIndex);
        write(dir, name);
        System.out.printf("  %-18s chunk %d,%d  %d km/h%n", name, chunk[0], chunk[1],
                Math.round(lastCar.speedKmh()));
    }

    /** Renders the world and the player's car into the colour buffer. */
    static void scene(int[] chunk, int specIndex) {
        clear();

        float px = chunk[0] * Terrain.CHUNK - Terrain.LANE_OFFSET;
        float pz = chunk[1] * Terrain.CHUNK + 42f;

        Car car = new Car();
        car.reset(CarSpec.GARAGE[specIndex], px, pz, 0f);
        Controls c = new Controls();
        c.throttle = 1f;
        for (int i = 0; i < 260; i++) car.update(1f / 60f, c);

        float fx = car.forwardX(), fz = car.forwardZ();
        camX = car.x - fx * 7.4f;
        camY = car.y + 2.7f;
        camZ = car.z - fz * 7.4f;
        float lookX = car.x + fx * 5.5f, lookY = car.y + 1.15f, lookZ = car.z + fz * 5.5f;

        headlights = night > 0.42f;
        setHeadlights(car);

        float[] vp = camera(camX, camY, camZ, lookX, lookY, lookZ);
        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);

        int cx = (int) Math.floor(car.x / Terrain.CHUNK);
        int cz = (int) Math.floor(car.z / Terrain.CHUNK);
        for (int i = -4; i <= 4; i++) {
            for (int j = -4; j <= 4; j++) {
                ChunkData d = ChunkBuilder.generate(cx + i, cz + j);
                if (d.groundIndexCount > 0) mesh(d.groundVerts, d.groundIndices, d.groundIndexCount, vp, identity);
                if (d.objectIndexCount > 0) mesh(d.objectVerts, d.objectIndices, d.objectIndexCount, vp, identity);
            }
        }

        CarSpec spec = CarSpec.GARAGE[specIndex];
        MeshBuilder body = CarMesh.buildBody(spec, spec.colorR, spec.colorG, spec.colorB);
        mesh(body.buildVertexBuffer(), body.buildIndexBuffer(), body.indexCount(), vp, car.modelMatrix());
        MeshBuilder wheel = CarMesh.buildWheel(spec);
        FloatBuffer wv = wheel.buildVertexBuffer();
        ShortBuffer wi = wheel.buildIndexBuffer();
        for (int i = 0; i < 4; i++) {
            mesh(wv, wi, wheel.indexCount(), vp, car.wheelMatrix(i));
        }
        if (headlights) {
            for (int kind : new int[]{CarMesh.LAMP_HEAD, CarMesh.LAMP_TAIL}) {
                MeshBuilder lamp = CarMesh.buildLamp(spec, kind);
                mesh(lamp.buildVertexBuffer(), lamp.buildIndexBuffer(), lamp.indexCount(),
                        vp, car.modelMatrix());
            }
        }

        lastCar = car;
    }

    /** Mirrors GameRenderer: two lamps across the nose, aimed slightly down. */
    static void setHeadlights(Car car) {
        float fx = car.forwardX(), fz = car.forwardZ();
        float rx = car.rightX(), rz = car.rightZ();
        float noseX = car.x + fx * car.spec.length * 0.5f;
        float noseZ = car.z + fz * car.spec.length * 0.5f;
        float y = car.y + car.spec.beltY * 0.75f;
        float out = car.spec.width * 0.30f;
        headLX = noseX - rx * out; headLY = y; headLZ = noseZ - rz * out;
        headRX = noseX + rx * out; headRY = y; headRZ = noseZ + rz * out;
        float dy = -0.14f;
        float dl = (float) Math.sqrt(1f + dy * dy);
        headDX = fx / dl; headDY = dy / dl; headDZ = fz / dl;
    }

    static void garage(File dir) throws Exception {
        lighting(0.42f);
        clear();
        camX = 0f; camY = 3.4f; camZ = -16.5f;
        float[] vp = camera(camX, camY, camZ, 0f, 1.0f, 6f);
        headlights = false;

        float[] m = new float[16];
        for (int i = 0; i < CarSpec.GARAGE.length; i++) {
            CarSpec spec = CarSpec.GARAGE[i];
            // Four abreast in two rows: eight cars in three columns sat on
            // top of each other from this camera.
            float gx = ((i % 4) - 1.5f) * 5.6f;
            float gz = (i / 4) * 8.2f;
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, gx, 0f, gz);
            Matrix.rotateM(m, 0, 24f, 0f, 1f, 0f);

            MeshBuilder body = CarMesh.buildBody(spec, spec.colorR, spec.colorG, spec.colorB);
            mesh(body.buildVertexBuffer(), body.buildIndexBuffer(), body.indexCount(), vp, m);

            MeshBuilder wheel = CarMesh.buildWheel(spec);
            FloatBuffer wv = wheel.buildVertexBuffer();
            ShortBuffer wi = wheel.buildIndexBuffer();
            float[] wm = new float[16];
            for (int k = 0; k < 4; k++) {
                float side = (k == 0 || k == 2) ? -1f : 1f;
                float zo = (k < 2) ? spec.wheelbase * 0.5f : -spec.wheelbase * 0.5f;
                System.arraycopy(m, 0, wm, 0, 16);
                Matrix.translateM(wm, 0, side * spec.track * 0.5f, spec.wheelRadius, zo);
                mesh(wv, wi, wheel.indexCount(), vp, wm);
            }
        }
        // A patch of ground so the cars are not floating in the void.
        MeshBuilder floor = new MeshBuilder();
        for (int a = -6; a < 6; a++) {
            for (int b = -3; b < 6; b++) {
                float shade = 0.20f + 0.03f * ((a + b) & 1);
                floor.flatQuad(a * 4f, 0f, b * 4f, a * 4f + 4f, 0f, b * 4f,
                        a * 4f + 4f, 0f, b * 4f + 4f, a * 4f, 0f, b * 4f + 4f,
                        shade, shade, shade + 0.01f, 0f);
            }
        }
        float[] id = new float[16];
        Matrix.setIdentityM(id, 0);
        mesh(floor.buildVertexBuffer(), floor.buildIndexBuffer(), floor.indexCount(), vp, id);

        write(dir, "garage.png");
        System.out.println("  garage.png        " + CarSpec.GARAGE.length + " vehicles");
    }

    // ------------------------------------------------------------- pipeline

    static float[] camera(float ex, float ey, float ez, float tx, float ty, float tz) {
        float[] proj = new float[16], view = new float[16], vp = new float[16];
        float top = (float) (0.35 * Math.tan(Math.toRadians(62 * 0.5)));
        float right = top * (W / (float) H);
        Matrix.frustumM(proj, 0, -right, right, -top, top, 0.35f, 900f);
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        return vp;
    }

    static void clear() {
        for (int y = 0; y < H; y++) {
            float t = y / (float) H;
            float r, g, b;
            if (t < 0.55f) {
                float k = t / 0.55f;
                r = zenith[0] + (fog[0] - zenith[0]) * k;
                g = zenith[1] + (fog[1] - zenith[1]) * k;
                b = zenith[2] + (fog[2] - zenith[2]) * k;
            } else {
                float k = (t - 0.55f) / 0.45f;
                r = fog[0] * (1f - 0.18f * k);
                g = fog[1] * (1f - 0.18f * k);
                b = fog[2] * (1f - 0.15f * k);
            }
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 3;
                color[i] = r; color[i + 1] = g; color[i + 2] = b;
                depth[y * W + x] = Float.MAX_VALUE;
            }
        }
    }

    static void mesh(FloatBuffer v, ShortBuffer idx, int count, float[] vp, float[] model) {
        int stride = MeshBuilder.FLOATS_PER_VERTEX;
        float[] mvp = new float[16];
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        float[] in = new float[4], clip = new float[4], world = new float[4];

        float[][] sx = new float[3][]; // screen x,y,depth
        float[][] sc = new float[3][]; // shaded colour

        for (int t = 0; t < count; t += 3) {
            boolean ok = true;
            for (int k = 0; k < 3; k++) {
                int id = idx.get(t + k) & 0xffff;
                in[0] = v.get(id * stride);
                in[1] = v.get(id * stride + 1);
                in[2] = v.get(id * stride + 2);
                in[3] = 1f;
                Matrix.multiplyMV(clip, 0, mvp, 0, in, 0);
                if (clip[3] < 0.2f) { ok = false; break; }
                Matrix.multiplyMV(world, 0, model, 0, in, 0);

                float ndcX = clip[0] / clip[3];
                float ndcY = clip[1] / clip[3];
                float ndcZ = clip[2] / clip[3];
                sx[k] = new float[]{(ndcX * 0.5f + 0.5f) * W, (1f - (ndcY * 0.5f + 0.5f)) * H, ndcZ};

                float nx = v.get(id * stride + 3), ny = v.get(id * stride + 4), nz = v.get(id * stride + 5);
                float wnx = model[0] * nx + model[4] * ny + model[8] * nz;
                float wny = model[1] * nx + model[5] * ny + model[9] * nz;
                float wnz = model[2] * nx + model[6] * ny + model[10] * nz;
                sc[k] = shade(world[0], world[1], world[2], wnx, wny, wnz,
                        v.get(id * stride + 6), v.get(id * stride + 7), v.get(id * stride + 8),
                        v.get(id * stride + 9));
            }
            if (!ok) continue;

            float area = (sx[1][0] - sx[0][0]) * (sx[2][1] - sx[0][1])
                    - (sx[2][0] - sx[0][0]) * (sx[1][1] - sx[0][1]);
            if (area >= 0f) continue; // back face, exactly as GL_CCW would cull it
            raster(sx, sc);
        }
    }

    static float[] shade(float wx, float wy, float wz, float nx, float ny, float nz,
                         float r, float g, float b, float emissive) {
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) { nx /= len; ny /= len; nz /= len; }
        float diff = Math.max(nx * sunDir[0] + ny * sunDir[1] + nz * sunDir[2], 0f);
        float sky = 0.5f + 0.5f * ny;
        float lr = r * (ambient[0] * sky + sunColor[0] * diff);
        float lg = g * (ambient[1] * sky + sunColor[1] * diff);
        float lb = b * (ambient[2] * sky + sunColor[2] * diff);

        if (headlights) {
            float k = (beam(headLX, headLY, headLZ, wx, wy, wz, nx, ny, nz)
                    + beam(headRX, headRY, headRZ, wx, wy, wz, nx, ny, nz)) * beamPower;
            lr += r * 1.00f * k; lg += g * 0.95f * k; lb += b * 0.82f * k;
        }

        float glow = emissive * (0.15f + 0.85f * night);
        if (glow > 1f) glow = 1f;
        lr += (r * 1.25f - lr) * glow;
        lg += (g * 1.25f - lg) * glow;
        lb += (b * 1.25f - lb) * glow;

        float dxc = wx - camX, dyc = wy - camY, dzc = wz - camZ;
        float cd = (float) Math.sqrt(dxc * dxc + dyc * dyc + dzc * dzc) * fogDensity;
        float f = 1f - (float) Math.exp(-cd * cd);
        if (f < 0f) f = 0f;
        if (f > 1f) f = 1f;
        return new float[]{
                lr + (fog[0] - lr) * f,
                lg + (fog[1] - lg) * f,
                lb + (fog[2] - lb) * f};
    }

    static float beam(float lx, float ly, float lz, float wx, float wy, float wz,
                      float nx, float ny, float nz) {
        float dx = wx - lx, dy = wy - ly, dz = wz - lz;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001f) return 0f;
        dx /= dist; dy /= dist; dz /= dist;
        float cone = smoothstep(beamOuter, beamInner, dx * headDX + dy * headDY + dz * headDZ);
        float atten = 1f - smoothstep(beamNear, beamFar, dist);
        float facing = 0.42f + 0.58f * Math.max(-(nx * dx + ny * dy + nz * dz), 0f);
        return cone * atten * facing;
    }

    static void raster(float[][] p, float[][] c) {
        int minX = Math.max(0, (int) Math.floor(Math.min(p[0][0], Math.min(p[1][0], p[2][0]))));
        int maxX = Math.min(W - 1, (int) Math.ceil(Math.max(p[0][0], Math.max(p[1][0], p[2][0]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(p[0][1], Math.min(p[1][1], p[2][1]))));
        int maxY = Math.min(H - 1, (int) Math.ceil(Math.max(p[0][1], Math.max(p[1][1], p[2][1]))));
        if (minX > maxX || minY > maxY) return;

        float x0 = p[0][0], y0 = p[0][1], x1 = p[1][0], y1 = p[1][1], x2 = p[2][0], y2 = p[2][1];
        float denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(denom) < 1e-9f) return;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float l0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom;
                float l1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom;
                float l2 = 1f - l0 - l1;
                if (l0 < 0f || l1 < 0f || l2 < 0f) continue;
                float z = l0 * p[0][2] + l1 * p[1][2] + l2 * p[2][2];
                int di = y * W + x;
                if (z >= depth[di]) continue;
                depth[di] = z;
                int ci = di * 3;
                color[ci] = l0 * c[0][0] + l1 * c[1][0] + l2 * c[2][0];
                color[ci + 1] = l0 * c[0][1] + l1 * c[1][1] + l2 * c[2][1];
                color[ci + 2] = l0 * c[0][2] + l1 * c[1][2] + l2 * c[2][2];
            }
        }
    }

    static void lighting(float timeOfDay) {
        double angle = (timeOfDay - 0.25) * Math.PI * 2.0;
        float elevation = (float) Math.sin(angle);
        float east = (float) Math.cos(angle);
        float dirY = Math.max(0.06f, elevation);
        float len = (float) Math.sqrt(east * east + dirY * dirY + 0.35f * 0.35f);
        sunDir[0] = east / len; sunDir[1] = dirY / len; sunDir[2] = 0.35f / len;

        float daylight = clamp(elevation * 2.4f + 0.20f, 0f, 1f);
        night = 1f - daylight;
        float golden = clamp(1f - Math.abs(elevation) * 3.4f, 0f, 1f) * daylight;

        sunColor[0] = lerp(0.08f, 1.02f, daylight) + golden * 0.18f;
        sunColor[1] = lerp(0.09f, 0.97f, daylight) - golden * 0.06f;
        sunColor[2] = lerp(0.16f, 0.88f, daylight) - golden * 0.22f;
        ambient[0] = lerp(0.10f, 0.42f, daylight);
        ambient[1] = lerp(0.12f, 0.46f, daylight);
        ambient[2] = lerp(0.20f, 0.56f, daylight);
        fog[0] = lerp(0.045f, 0.70f, daylight) + golden * 0.24f;
        fog[1] = lerp(0.055f, 0.80f, daylight) + golden * 0.05f;
        fog[2] = lerp(0.095f, 0.92f, daylight) - golden * 0.12f;
        zenith[0] = lerp(0.015f, 0.26f, daylight) + golden * 0.14f;
        zenith[1] = lerp(0.025f, 0.50f, daylight) + golden * 0.04f;
        zenith[2] = lerp(0.070f, 0.88f, daylight);
        for (int i = 0; i < 3; i++) {
            sunColor[i] = clamp(sunColor[i], 0f, 1.4f);
            fog[i] = clamp(fog[i], 0f, 1f);
            zenith[i] = clamp(zenith[i], 0f, 1f);
        }
    }

    static void write(File dir, String name) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 3;
                int r = (int) (clamp(color[i], 0f, 1f) * 255f);
                int g = (int) (clamp(color[i + 1], 0f, 1f) * 255f);
                int b = (int) (clamp(color[i + 2], 0f, 1f) * 255f);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", new File(dir, name));
    }

    static float smoothstep(float a, float b, float x) {
        float t = clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
