package com.ilker.opendrive.ui;

import com.ilker.opendrive.game.Car;
import com.ilker.opendrive.game.Controls;
import com.ilker.opendrive.gl.HudProgram;
import com.ilker.opendrive.world.Terrain;

/**
 * Touch controls, instruments and minimap. Layout is driven off the screen
 * height so it lands sensibly on anything from a small phone to a tablet.
 */
public class Hud {

    public static final int BTN_CAMERA = 0;
    public static final int BTN_CAR = 1;
    public static final int BTN_LIGHTS = 2;
    public static final int BTN_TILT = 3;
    public static final int BTN_RESET = 4;
    private static final int BTN_COUNT = 5;

    private static final String[] BTN_LABEL = {"KAM", "ARAC", "ISIK", "EGIM", "SIFIR"};

    private int width;
    private int height;
    private float u;

    private float steerLx, steerLy, steerRx, steerRy, steerRad;
    private float gasX, gasY, gasRad;
    private float brakeX, brakeY, brakeRad;
    private float handX, handY, handRad;
    private float mapX, mapY, mapRad;
    private float dialX, dialY, dialRad;

    private final float[] btnX = new float[BTN_COUNT];
    private float btnY;
    private float btnW;
    private float btnH;

    private final boolean[] btnDown = new boolean[BTN_COUNT];
    private final boolean[] btnTapped = new boolean[BTN_COUNT];
    private final boolean[] btnNowDown = new boolean[BTN_COUNT];

    private boolean leftLit, rightLit, gasLit, brakeLit, handLit;

    private final float[] trafficScratch = new float[64];

    public void layout(int width, int height) {
        this.width = width;
        this.height = height;
        this.u = height / 100f;

        steerRad = 11.5f * u;
        steerLy = height - 14.5f * u;
        steerRy = steerLy;
        steerLx = 15f * u;
        steerRx = 41f * u;

        gasRad = 13f * u;
        gasX = width - 16f * u;
        gasY = height - 16f * u;

        brakeRad = 10.5f * u;
        brakeX = width - 43f * u;
        brakeY = height - 13.5f * u;

        handRad = 8f * u;
        handX = width - 18f * u;
        handY = height - 46f * u;

        mapRad = 13f * u;
        mapX = mapRad + 3f * u;
        mapY = mapRad + 3f * u;

        // The dial lives in the gap between the steering pair and the brake,
        // so on a squarer screen it shrinks rather than sliding underneath them.
        float dialRoom = Math.min(width * 0.5f - (steerRx + steerRad + 2f * u),
                (brakeX - brakeRad - 2f * u) - width * 0.5f);
        dialRad = Math.max(11.5f * u, Math.min(19f * u, dialRoom));
        dialX = width * 0.5f;
        dialY = height - 21f * u;

        btnW = 11.5f * u;
        btnH = 9f * u;
        btnY = 3f * u;
        float gap = 1.4f * u;
        float total = BTN_COUNT * btnW + (BTN_COUNT - 1) * gap;
        float startX = width - 3f * u - total;
        for (int i = 0; i < BTN_COUNT; i++) {
            btnX[i] = startX + i * (btnW + gap);
        }
    }

    private static boolean inCircle(float px, float py, float cx, float cy, float r) {
        float dx = px - cx;
        float dy = py - cy;
        return dx * dx + dy * dy <= r * r;
    }

    /**
     * Turns the live pointer list into control inputs. Tap-style buttons are
     * edge triggered; {@link #wasTapped(int)} reports them for this frame.
     */
    public void processInput(float[] px, float[] py, int count, Controls out,
                             float tiltSteer, boolean tiltEnabled) {
        out.clear();
        leftLit = false;
        rightLit = false;
        gasLit = false;
        brakeLit = false;
        handLit = false;

        java.util.Arrays.fill(btnNowDown, false);
        float steerInput = 0f;

        // Generous hit areas: the drawn circle plus a margin, because thumbs
        // drift while the car is moving.
        float grow = 1.35f;
        for (int i = 0; i < count; i++) {
            float x = px[i];
            float y = py[i];

            if (inCircle(x, y, steerLx, steerLy, steerRad * grow)) {
                steerInput -= 1f;
                leftLit = true;
            }
            if (inCircle(x, y, steerRx, steerRy, steerRad * grow)) {
                steerInput += 1f;
                rightLit = true;
            }
            if (inCircle(x, y, gasX, gasY, gasRad * grow)) {
                out.throttle = 1f;
                gasLit = true;
            }
            if (inCircle(x, y, brakeX, brakeY, brakeRad * grow)) {
                out.brake = 1f;
                brakeLit = true;
            }
            if (inCircle(x, y, handX, handY, handRad * grow)) {
                out.handbrake = true;
                handLit = true;
            }
            for (int bIdx = 0; bIdx < BTN_COUNT; bIdx++) {
                if (x >= btnX[bIdx] && x <= btnX[bIdx] + btnW
                        && y >= btnY && y <= btnY + btnH) {
                    btnNowDown[bIdx] = true;
                }
            }
        }

        for (int i = 0; i < BTN_COUNT; i++) {
            btnTapped[i] = btnNowDown[i] && !btnDown[i];
            btnDown[i] = btnNowDown[i];
        }

        if (tiltEnabled) {
            steerInput += tiltSteer;
        }
        if (steerInput > 1f) steerInput = 1f;
        if (steerInput < -1f) steerInput = -1f;
        out.steer = steerInput;
    }

    public boolean wasTapped(int button) {
        return button >= 0 && button < BTN_COUNT && btnTapped[button];
    }

    // ------------------------------------------------------------------ draw

    public void draw(HudProgram g, Car car, com.ilker.opendrive.game.Traffic traffic,
                     boolean lightsOn, boolean tiltEnabled, float timeOfDay, int fps,
                     String message, float messageAlpha) {
        drawMinimap(g, car, traffic);
        drawDial(g, car);
        drawPedals(g);
        drawButtons(g, lightsOn, tiltEnabled);
        drawStats(g, car, timeOfDay, fps);
        if (messageAlpha > 0.01f && message != null) {
            float pixel = Math.max(2f, 0.9f * u);
            float w = PixelFont.width(message, pixel);
            g.rect(width * 0.5f - w * 0.5f - 2f * u, 13f * u, w + 4f * u, 7f * u,
                    0.04f, 0.05f, 0.08f, 0.62f * messageAlpha);
            PixelFont.drawCentered(g, message, width * 0.5f, 15f * u, pixel,
                    0.62f, 0.94f, 1f, messageAlpha);
        }
    }

    private void drawPedals(HudProgram g) {
        pedal(g, steerLx, steerLy, steerRad, leftLit, 0.35f, 0.72f, 0.95f);
        arrow(g, steerLx, steerLy, steerRad * 0.45f, true, leftLit);
        pedal(g, steerRx, steerRy, steerRad, rightLit, 0.35f, 0.72f, 0.95f);
        arrow(g, steerRx, steerRy, steerRad * 0.45f, false, rightLit);

        pedal(g, gasX, gasY, gasRad, gasLit, 0.34f, 0.92f, 0.52f);
        PixelFont.drawCentered(g, "GAZ", gasX, gasY - 1.6f * u, 0.85f * u,
                0.92f, 1f, 0.94f, gasLit ? 1f : 0.82f);

        pedal(g, brakeX, brakeY, brakeRad, brakeLit, 0.95f, 0.36f, 0.34f);
        PixelFont.drawCentered(g, "FREN", brakeX, brakeY - 1.6f * u, 0.8f * u,
                1f, 0.92f, 0.92f, brakeLit ? 1f : 0.82f);

        pedal(g, handX, handY, handRad, handLit, 0.98f, 0.72f, 0.20f);
        PixelFont.drawCentered(g, "EL FR", handX, handY - 1.4f * u, 0.7f * u,
                1f, 0.96f, 0.86f, handLit ? 1f : 0.82f);
    }

    private void pedal(HudProgram g, float cx, float cy, float r, boolean lit,
                       float cr, float cg, float cb) {
        g.circle(cx, cy, r, 28, 0.05f, 0.06f, 0.09f, lit ? 0.62f : 0.38f);
        g.ring(cx, cy, r - 0.9f * u, r, 30, 0f, (float) (Math.PI * 2),
                cr, cg, cb, lit ? 0.98f : 0.55f);
        if (lit) {
            g.circle(cx, cy, r - 1.2f * u, 26, cr, cg, cb, 0.22f);
        }
    }

    private void arrow(HudProgram g, float cx, float cy, float size, boolean left, boolean lit) {
        float a = lit ? 1f : 0.7f;
        float dir = left ? -1f : 1f;
        g.triangle(cx + dir * size, cy,
                cx - dir * size * 0.45f, cy - size * 0.85f,
                cx - dir * size * 0.45f, cy + size * 0.85f,
                0.75f, 0.93f, 1f, a);
    }

    private void drawButtons(HudProgram g, boolean lightsOn, boolean tiltEnabled) {
        for (int i = 0; i < BTN_COUNT; i++) {
            boolean active = (i == BTN_LIGHTS && lightsOn) || (i == BTN_TILT && tiltEnabled);
            boolean held = btnDown[i];
            float alpha = held ? 0.80f : 0.42f;
            g.rect(btnX[i], btnY, btnW, btnH, 0.05f, 0.06f, 0.09f, alpha);
            float br = active ? 0.30f : 0.42f;
            float bg = active ? 0.95f : 0.60f;
            float bb = active ? 0.72f : 0.78f;
            border(g, btnX[i], btnY, btnW, btnH, 0.35f * u, br, bg, bb, active ? 0.95f : 0.55f);
            PixelFont.drawCentered(g, BTN_LABEL[i], btnX[i] + btnW * 0.5f,
                    btnY + btnH * 0.5f - 1.25f * u, 0.8f * u,
                    active ? 0.65f : 0.85f, active ? 1f : 0.92f, active ? 0.85f : 0.96f, 0.95f);
        }
    }

    private void border(HudProgram g, float x, float y, float w, float h, float t,
                        float r, float gg, float b, float a) {
        g.rect(x, y, w, t, r, gg, b, a);
        g.rect(x, y + h - t, w, t, r, gg, b, a);
        g.rect(x, y, t, h, r, gg, b, a);
        g.rect(x + w - t, y, t, h, r, gg, b, a);
    }

    // ------------------------------------------------------------------ dial

    private void drawDial(HudProgram g, Car car) {
        float start = (float) Math.toRadians(135.0);
        float sweep = (float) Math.toRadians(270.0);
        float inner = dialRad - 2.6f * u;

        g.ring(dialX, dialY, inner - 0.4f * u, dialRad, 48, start, sweep,
                0.05f, 0.07f, 0.10f, 0.55f);

        float max = Math.max(60f, car.spec.topSpeedKmh());
        float frac = Math.min(1f, car.speedKmh() / max);
        int segs = Math.max(1, Math.round(48 * frac));
        if (frac > 0.001f) {
            float r = 0.30f + 0.68f * frac;
            float gr = 0.92f - 0.55f * frac;
            float b = 1f - 0.82f * frac;
            g.ring(dialX, dialY, inner, dialRad - 0.35f * u, segs, start, sweep * frac,
                    r, gr, b, 0.95f);
        }

        // Tick marks every tenth of the scale.
        for (int i = 0; i <= 10; i++) {
            float ang = start + sweep * i / 10f;
            float c = (float) Math.cos(ang);
            float s = (float) Math.sin(ang);
            float len = (i % 5 == 0) ? 2.4f * u : 1.4f * u;
            g.line(dialX + c * (inner - 0.6f * u), dialY + s * (inner - 0.6f * u),
                    dialX + c * (inner - 0.6f * u - len), dialY + s * (inner - 0.6f * u - len),
                    (i % 5 == 0) ? 0.55f * u : 0.32f * u,
                    0.55f, 0.72f, 0.85f, 0.75f);
        }

        // Needle.
        float ang = start + sweep * frac;
        float nc = (float) Math.cos(ang);
        float ns = (float) Math.sin(ang);
        g.line(dialX - nc * 2.5f * u, dialY - ns * 2.5f * u,
                dialX + nc * (inner - 1.2f * u), dialY + ns * (inner - 1.2f * u),
                0.7f * u, 1f, 0.42f, 0.32f, 0.95f);
        g.circle(dialX, dialY, 1.5f * u, 16, 0.85f, 0.88f, 0.95f, 0.9f);

        // Digital readout.
        int kmh = Math.round(car.speedKmh());
        float digitW = 4.4f * u;
        float digitH = 7.6f * u;
        PixelFont.sevenSegmentNumber(g, kmh, 3,
                dialX + digitW * 1.9f, dialY - 10.6f * u, digitW, digitH, 0.85f * u,
                0.80f, 0.95f, 1f, 0.98f,
                0.30f, 0.40f, 0.48f, 0.16f);
        PixelFont.drawCentered(g, "KM/S", dialX, dialY - 1.4f * u, 0.85f * u,
                0.55f, 0.75f, 0.88f, 0.85f);

        String gear = car.forwardSpeed < -0.4f ? "R" : (Math.abs(car.forwardSpeed) < 0.4f ? "N" : ("D" + car.gear));
        PixelFont.drawCentered(g, gear, dialX, dialY + 2.4f * u, 1.5f * u,
                0.50f, 0.95f, 0.78f, 0.95f);
        PixelFont.drawCentered(g, car.spec.name, dialX, dialY + 10.0f * u, 0.95f * u,
                0.62f, 0.78f, 0.92f, 0.80f);
    }

    // --------------------------------------------------------------- minimap

    private void drawMinimap(HudProgram g, Car car, com.ilker.opendrive.game.Traffic traffic) {
        float viewRange = 230f;
        float scale = mapRad / viewRange;

        g.circle(mapX, mapY, mapRad + 0.7f * u, 40, 0.30f, 0.62f, 0.80f, 0.55f);
        g.circle(mapX, mapY, mapRad, 40, 0.04f, 0.06f, 0.09f, 0.78f);

        float cosY = (float) Math.cos(car.yaw);
        float sinY = (float) Math.sin(car.yaw);

        int range = (int) Math.ceil(viewRange / Terrain.CHUNK) + 1;
        int baseI = Math.round(car.x / Terrain.CHUNK);
        int baseJ = Math.round(car.z / Terrain.CHUNK);

        for (int k = -range; k <= range; k++) {
            gridLine(g, car, cosY, sinY, scale, (baseI + k) * Terrain.CHUNK, true, viewRange, baseJ);
            gridLine(g, car, cosY, sinY, scale, (baseJ + k) * Terrain.CHUNK, false, viewRange, baseI);
        }

        int n = traffic.collectPositions(trafficScratch);
        for (int i = 0; i < n; i++) {
            float dx = trafficScratch[i * 2] - car.x;
            float dz = trafficScratch[i * 2 + 1] - car.z;
            float fwd = dx * sinY + dz * cosY;
            float rgt = -dx * cosY + dz * sinY;
            float sx = mapX + rgt * scale;
            float sy = mapY - fwd * scale;
            float ddx = sx - mapX, ddy = sy - mapY;
            if (ddx * ddx + ddy * ddy > (mapRad - 0.8f * u) * (mapRad - 0.8f * u)) continue;
            g.circle(sx, sy, 0.85f * u, 8, 1f, 0.78f, 0.28f, 0.95f);
        }

        // The player always sits dead centre, pointing up.
        g.triangle(mapX, mapY - 2.2f * u,
                mapX - 1.5f * u, mapY + 1.8f * u,
                mapX + 1.5f * u, mapY + 1.8f * u,
                0.45f, 1f, 0.85f, 1f);
    }

    private void gridLine(HudProgram g, Car car, float cosY, float sinY, float scale,
                          float coord, boolean vertical, float viewRange, int otherBase) {
        int steps = 22;
        float half = viewRange * 1.1f;
        float other0 = (vertical ? car.z : car.x) - half;
        float prevSx = 0f, prevSy = 0f;
        boolean prevInside = false;
        float limit = (mapRad - 0.5f * u) * (mapRad - 0.5f * u);

        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float other = other0 + 2f * half * t;
            float wx = vertical ? coord : other;
            float wz = vertical ? other : coord;
            float dx = wx - car.x;
            float dz = wz - car.z;
            float fwd = dx * sinY + dz * cosY;
            float rgt = -dx * cosY + dz * sinY;
            float sx = mapX + rgt * scale;
            float sy = mapY - fwd * scale;
            float ddx = sx - mapX, ddy = sy - mapY;
            boolean inside = ddx * ddx + ddy * ddy <= limit;
            if (i > 0 && inside && prevInside) {
                g.line(prevSx, prevSy, sx, sy, 1.1f * u, 0.32f, 0.52f, 0.62f, 0.85f);
            }
            prevSx = sx;
            prevSy = sy;
            prevInside = inside;
        }
    }

    // ----------------------------------------------------------------- stats

    private void drawStats(HudProgram g, Car car, float timeOfDay, int fps) {
        // Tucked under the minimap, clear of the buttons on narrow screens.
        float pixel = 0.62f * u;
        float x = 4.5f * u;
        float y = mapY + mapRad + 3f * u;
        float lineH = 3.6f * u;

        String km = format1(car.distanceTravelled / 1000f);
        int hours = (int) (timeOfDay * 24f) % 24;
        int minutes = (int) ((timeOfDay * 24f - (int) (timeOfDay * 24f)) * 60f);

        g.rect(x - 1.5f * u, y - 1.5f * u, 48f * u, lineH * 3f + 1.5f * u,
                0.04f, 0.05f, 0.08f, 0.42f);
        PixelFont.draw(g, "MESAFE " + km + " KM", x, y, pixel, 0.60f, 0.85f, 0.95f, 0.92f);
        PixelFont.draw(g, "REKOR " + Math.round(car.topSpeedSeen) + " KM/S", x, y + lineH,
                pixel, 0.95f, 0.80f, 0.45f, 0.92f);
        PixelFont.draw(g, "SAAT " + pad2(hours) + ":" + pad2(minutes) + "   " + fps + " FPS",
                x, y + lineH * 2f, pixel, 0.55f, 0.70f, 0.82f, 0.85f);
    }

    private static String pad2(int v) {
        return v < 10 ? ("0" + v) : Integer.toString(v);
    }

    private static String format1(float v) {
        if (v < 0f) v = 0f;
        int whole = (int) v;
        int frac = (int) ((v - whole) * 10f);
        return whole + "." + frac;
    }
}
