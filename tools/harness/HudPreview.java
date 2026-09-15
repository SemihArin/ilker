import com.ilker.opendrive.game.Car;
import com.ilker.opendrive.game.CarSpec;
import com.ilker.opendrive.game.Controls;
import com.ilker.opendrive.game.Traffic;
import com.ilker.opendrive.gl.HudProgram;
import com.ilker.opendrive.ui.Hud;
import com.ilker.opendrive.world.Terrain;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Runs the real Hud code and rasterises the triangles it produces over a
 * rendered frame, so the interface can be looked at without a device.
 * Development tool; not part of the app.
 */
public class HudPreview {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "docs");

        frame(dir, "hud-day.png", 0.40f, Preview.cityChunk(), 0, false);
        frame(dir, "hud-night.png", 0.92f, Preview.cityChunk(), 3, true);
        frame(dir, "hud-country.png", 0.32f, Preview.ruralChunk(), 1, false);
        bare(dir, "hud-bare.png");
        // A squarer screen, where the controls leave no room along the bottom.
        Preview.resize(720, 540);
        frame(dir, "hud-narrow.png", 0.40f, Preview.cityChunk(), 5, false);
        Preview.resize(960, 540);
        System.out.println("hud previews written to " + dir.getAbsolutePath());
    }

    /** The interface alone on a flat ground, so every element is unmissable. */
    static void bare(File dir, String name) throws Exception {
        for (int i = 0; i < Preview.color.length; i += 3) {
            Preview.color[i] = 0.32f;
            Preview.color[i + 1] = 0.34f;
            Preview.color[i + 2] = 0.38f;
        }
        Car car = new Car();
        Controls c = new Controls();
        car.reset(CarSpec.GARAGE[0], -Terrain.LANE_OFFSET, 0f, 0f);
        c.throttle = 1f;
        for (int i = 0; i < 240; i++) car.update(1f / 60f, c);

        Traffic traffic = new Traffic();
        for (int i = 0; i < 300; i++) traffic.update(1f / 60f, car);

        Hud hud = new Hud();
        hud.layout(Preview.W, Preview.H);
        HudProgram g = new HudProgram();
        float[] xs = {Preview.W * 0.05f, Preview.W * 0.93f};
        float[] ys = {Preview.H * 0.86f, Preview.H * 0.84f};
        for (int warm = 0; warm < 45; warm++) {
            hud.processInput(xs, ys, 2, c, 0f, false);
            g.begin();
            hud.draw(g, car, traffic, 2, true, 0.45f, 60, "KISA FAR", 1f);
        }
        g.begin();
        hud.draw(g, car, traffic, 2, true, 0.45f, 60, "KISA FAR", 1f);
        rasterise(g);
        Preview.write(dir, name);
        System.out.printf("  %-18s %d hud triangles%n", name, triangles(g));
    }

    static void frame(File dir, String name, float timeOfDay, int[] chunk,
                      int specIndex, boolean pressed) throws Exception {
        Preview.lighting(timeOfDay);
        Preview.scene(chunk, specIndex);
        Car car = Preview.lastCar;

        Traffic traffic = new Traffic();
        Controls c = new Controls();
        for (int i = 0; i < 400; i++) traffic.update(1f / 60f, car);

        Hud hud = new Hud();
        hud.layout(Preview.W, Preview.H);
        HudProgram g = new HudProgram();

        // Pretend a thumb is on the accelerator and the right-hand steer pad,
        // so the pressed states are visible too.
        if (pressed) {
            float[] xs = new float[]{Preview.W * 0.14f, Preview.W * 0.93f};
            float[] ys = new float[]{Preview.H * 0.86f, Preview.H * 0.84f};
            hud.processInput(xs, ys, 2, c, 0f, false);
        } else {
            hud.processInput(new float[0], new float[0], 0, c, 0f, false);
        }

        // The steering readout eases in over about a second, so run the
        // interface for a moment before capturing a frame.
        int mode = timeOfDay > 0.8f ? 2 : 0;
        for (int warm = 0; warm < 45; warm++) {
            g.begin();
            hud.draw(g, car, traffic, mode, false, timeOfDay, 60, "SUR VE KESFET", 1f);
        }

        g.begin();
        hud.draw(g, car, traffic, mode, false, timeOfDay, 60, "SUR VE KESFET", 1f);
        rasterise(g);

        Preview.write(dir, name);
        System.out.printf("  %-18s %d hud triangles%n", name, triangles(g));
    }

    static float[] data(HudProgram g) throws Exception {
        Field f = HudProgram.class.getDeclaredField("scratch");
        f.setAccessible(true);
        return (float[]) f.get(g);
    }

    static int used(HudProgram g) throws Exception {
        Field f = HudProgram.class.getDeclaredField("used");
        f.setAccessible(true);
        return (Integer) f.get(g);
    }

    static int triangles(HudProgram g) throws Exception {
        return used(g) / 6 / 3;
    }

    /** Alpha-blended 2D triangles straight onto Preview's colour buffer. */
    static void rasterise(HudProgram g) throws Exception {
        float[] v = data(g);
        int n = used(g) / 6;
        for (int t = 0; t + 2 < n; t += 3) {
            float x0 = v[t * 6], y0 = v[t * 6 + 1];
            float x1 = v[(t + 1) * 6], y1 = v[(t + 1) * 6 + 1];
            float x2 = v[(t + 2) * 6], y2 = v[(t + 2) * 6 + 1];
            float r = v[t * 6 + 2], gg = v[t * 6 + 3], b = v[t * 6 + 4], a = v[t * 6 + 5];
            float r1 = v[(t + 1) * 6 + 2], g1 = v[(t + 1) * 6 + 3], b1 = v[(t + 1) * 6 + 4], a1 = v[(t + 1) * 6 + 5];
            float r2 = v[(t + 2) * 6 + 2], g2 = v[(t + 2) * 6 + 3], b2 = v[(t + 2) * 6 + 4], a2 = v[(t + 2) * 6 + 5];

            int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
            int maxX = Math.min(Preview.W - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
            int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
            int maxY = Math.min(Preview.H - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
            float denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
            if (Math.abs(denom) < 1e-9f) continue;

            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    float px = x + 0.5f, py = y + 0.5f;
                    float l0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom;
                    float l1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom;
                    float l2 = 1f - l0 - l1;
                    if (l0 < 0f || l1 < 0f || l2 < 0f) continue;
                    float ca = l0 * a + l1 * a1 + l2 * a2;
                    if (ca <= 0.002f) continue;
                    float cr = l0 * r + l1 * r1 + l2 * r2;
                    float cg = l0 * gg + l1 * g1 + l2 * g2;
                    float cb = l0 * b + l1 * b1 + l2 * b2;
                    int i = (y * Preview.W + x) * 3;
                    Preview.color[i] += (cr - Preview.color[i]) * ca;
                    Preview.color[i + 1] += (cg - Preview.color[i + 1]) * ca;
                    Preview.color[i + 2] += (cb - Preview.color[i + 2]) * ca;
                }
            }
        }
    }
}
