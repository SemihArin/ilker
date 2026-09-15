package com.ilker.opendrive.ui;

import com.ilker.opendrive.game.Car;
import com.ilker.opendrive.game.Controls;
import com.ilker.opendrive.game.Traffic;
import com.ilker.opendrive.gl.HudProgram;
import com.ilker.opendrive.world.Terrain;

/**
 * Touch controls and instruments.
 *
 * Two rules drive the layout: nothing sits in the middle of the screen, which
 * is where the player is actually looking, and every control is a thumb-sized
 * target in the corner a thumb can reach. Everything is measured in hundredths
 * of the screen height so it lands the same on a small phone and a tablet.
 */
public class Hud {

    public static final int BTN_CAMERA = 0;
    public static final int BTN_CAR = 1;
    public static final int BTN_LIGHTS = 2;
    public static final int BTN_TILT = 3;
    public static final int BTN_RESET = 4;
    private static final int BTN_COUNT = 5;

    private static final String[] BTN_LABEL = {"KAM", "ARAC", "ISIK", "EGIM", "SIFIR"};

    // Palette.
    private static final float[] PANEL = {0.035f, 0.045f, 0.07f, 0.72f};
    private static final float[] EDGE = {0.30f, 0.46f, 0.56f, 0.55f};
    private static final float[] ACCENT = {0.36f, 0.84f, 0.98f};
    private static final float[] TEXT = {0.88f, 0.94f, 0.97f};
    private static final float[] TEXT_DIM = {0.54f, 0.66f, 0.74f};
    private static final float[] GAS = {0.32f, 0.90f, 0.56f};
    private static final float[] BRAKE = {1.00f, 0.38f, 0.36f};
    private static final float[] HAND = {1.00f, 0.72f, 0.24f};

    private int width;
    private int height;
    private float u;

    // Steering pads.
    private float padY, padH, padW, padLeftX, padRightX, steerSplit;
    // Pedals.
    private float gasX, gasY, gasR;
    private float brakeX, brakeY, brakeR;
    private float handX, handY, handR;
    // Instruments.
    private float mapX, mapY, mapR;
    private float statX, statY, statW, statH;
    private float dashX, dashY, dashW, dashH;
    // Buttons.
    private final float[] btnX = new float[BTN_COUNT];
    private float btnY, btnW, btnH;

    private final boolean[] btnDown = new boolean[BTN_COUNT];
    private final boolean[] btnTapped = new boolean[BTN_COUNT];
    private final boolean[] btnNowDown = new boolean[BTN_COUNT];

    private boolean leftLit, rightLit, gasLit, brakeLit, handLit;
    private float steerShown;
    private float steerPressure;
    private float driftShown;
    private float driftAngleShown;

    private final float[] trafficScratch = new float[64];

    public void layout(int width, int height) {
        this.width = width;
        this.height = height;
        this.u = height / 100f;

        float margin = 3f * u;

        // --- steering, bottom left
        padH = 26f * u;
        padW = 21f * u;
        padY = height - margin - padH;
        padLeftX = margin;
        padRightX = margin + padW + 2f * u;
        steerSplit = (padLeftX + padRightX + padW) * 0.5f;

        // --- pedals, bottom right
        gasR = 14f * u;
        gasX = width - margin - gasR;
        gasY = height - margin - gasR;

        brakeR = 11f * u;
        brakeX = gasX - gasR - brakeR - 3f * u;
        brakeY = height - margin - brakeR - 1f * u;

        handR = 8.5f * u;
        handX = brakeX;
        handY = brakeY - brakeR - handR - 5f * u;

        // --- minimap and stats, top left
        mapR = 13f * u;
        mapX = margin + mapR;
        mapY = margin + mapR;

        statX = margin;
        statY = mapY + mapR + 2.5f * u;
        statW = 52f * u;
        statH = 19f * u;

        // --- buttons, top right
        btnW = 15f * u;
        btnH = 14f * u;
        btnY = margin;
        float gap = 1.5f * u;
        float strip = BTN_COUNT * btnW + (BTN_COUNT - 1) * gap;
        float startX = width - margin - strip;
        for (int i = 0; i < BTN_COUNT; i++) {
            btnX[i] = startX + i * (btnW + gap);
        }

        // --- speed panel, centred in whatever gap the controls leave
        dashW = 41f * u;
        dashH = 18.5f * u;
        float free0 = padRightX + padW;
        float free1 = brakeX - brakeR;
        if (free1 - free0 >= dashW + 5f * u) {
            // Normal case: centred in the gap the controls leave along the bottom.
            dashY = height - margin - dashH;
            dashX = (free0 + free1) * 0.5f - dashW * 0.5f;
        } else {
            // Squarer screen: no room along the bottom, so sit above the pads.
            dashX = margin;
            dashY = padY - dashH - 1f * u;
        }
    }

    // ----------------------------------------------------------------- input

    /** Distance to a circle's centre as a fraction of its radius. */
    private static float reach(float px, float py, float cx, float cy, float r) {
        float dx = px - cx;
        float dy = py - cy;
        return (float) Math.sqrt(dx * dx + dy * dy) / Math.max(0.001f, r);
    }

    private static boolean inRect(float px, float py, float x, float y, float w, float h,
                                  float grow) {
        return px >= x - grow && px <= x + w + grow && py >= y - grow && py <= y + h + grow;
    }

    /**
     * Turns the live pointer list into control inputs. The hit areas are
     * deliberately larger than the shapes drawn: thumbs drift while the car is
     * moving, and losing the throttle mid-corner is worse than an overlap.
     */
    public void processInput(float[] px, float[] py, int count, Controls out,
                             float tiltSteer, boolean tiltEnabled) {
        out.clear();
        leftLit = false;
        rightLit = false;
        gasLit = false;
        brakeLit = false;
        handLit = false;
        steerPressure = 0f;

        java.util.Arrays.fill(btnNowDown, false);
        float steerInput = 0f;
        float grow = 2.5f * u;

        for (int i = 0; i < count; i++) {
            float x = px[i];
            float y = py[i];

            // One zone split down the middle rather than two grown rectangles:
            // overlapping hit areas would cancel each other out in the seam.
            // How far out the thumb sits decides how much lock, so a small
            // correction on a straight no longer means full opposite lock.
            if (x <= padRightX + padW + grow && y >= padY - grow) {
                float span = (padRightX + padW) - steerSplit;
                float offset = (x - steerSplit) / Math.max(1f, span);
                float amount = 0.45f + 0.55f * Math.min(1f, Math.abs(offset) / 0.85f);
                if (offset < 0f) {
                    steerInput -= amount;
                    leftLit = true;
                    steerPressure = amount;
                } else {
                    steerInput += amount;
                    rightLit = true;
                    steerPressure = amount;
                }
            }
            // Nearest pedal wins. Growing three circles independently would
            // make them overlap, and a touch in the seam would fire two at
            // once — brake and throttle together, which feels broken.
            int pedal = -1;
            float best = Float.MAX_VALUE;
            float dGas = reach(x, y, gasX, gasY, gasR + grow);
            float dBrake = reach(x, y, brakeX, brakeY, brakeR + grow);
            float dHand = reach(x, y, handX, handY, handR + grow);
            if (dGas < best) { best = dGas; pedal = 0; }
            if (dBrake < best) { best = dBrake; pedal = 1; }
            if (dHand < best) { best = dHand; pedal = 2; }
            if (best <= 1f) {
                if (pedal == 0) {
                    out.throttle = 1f;
                    gasLit = true;
                } else if (pedal == 1) {
                    out.brake = 1f;
                    brakeLit = true;
                } else {
                    out.handbrake = true;
                    handLit = true;
                }
            }
            for (int b = 0; b < BTN_COUNT; b++) {
                if (inRect(x, y, btnX[b], btnY, btnW, btnH, 0.8f * u)) {
                    btnNowDown[b] = true;
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

    public void draw(HudProgram g, Car car, Traffic traffic,
                     int lightMode, boolean tiltEnabled, float timeOfDay, int fps,
                     String message, float messageAlpha) {
        steerShown += (car.steerAngle / 0.62f - steerShown) * 0.35f;

        drawMinimap(g, car, traffic);
        drawStats(g, car, timeOfDay, fps);
        drawButtons(g, lightMode, tiltEnabled);
        drawDash(g, car);
        drawDrift(g, car);
        drawSteering(g);
        drawPedals(g);

        if (messageAlpha > 0.01f && message != null && !message.isEmpty()) {
            // Fixed slot below the button strip and clear of the minimap. The
            // speed panel moves around depending on the screen shape, so
            // hanging the message off it lands in the instruments on a tablet.
            float left = mapX + mapR + 2f * u;
            float right = width - 3f * u;
            float band = right - left;
            float glyphs = Math.max(1, message.length() * 6 - 1);
            float pixel = Math.min(0.58f * u, (band - 6f * u) / glyphs);
            float w = PixelFont.width(message, pixel);
            float cx = (left + right) * 0.5f;
            float h = PixelFont.height(pixel) + 3.2f * u;
            float by = btnY + btnH + 2.2f * u;
            g.roundedRect(cx - w * 0.5f - 2.6f * u, by, w + 5.2f * u, h, 1.6f * u,
                    PANEL[0], PANEL[1], PANEL[2], 0.80f * messageAlpha);
            PixelFont.drawCentered(g, message, cx, by + 1.6f * u, pixel,
                    ACCENT[0], ACCENT[1], ACCENT[2], messageAlpha);
        }
    }

    private void panel(HudProgram g, float x, float y, float w, float h, float radius,
                       float alpha) {
        g.roundedRect(x, y, w, h, radius, PANEL[0], PANEL[1], PANEL[2], alpha);
        g.roundedRectOutline(x, y, w, h, radius, 0.3f * u,
                EDGE[0], EDGE[1], EDGE[2], EDGE[3]);
    }

    // -------------------------------------------------------------- steering

    private void drawSteering(HudProgram g) {
        steerPad(g, padLeftX, true, leftLit);
        steerPad(g, padRightX, false, rightLit);

        // A hairline under the pads showing where the wheels actually are.
        float cx = (padLeftX + padRightX + padW) * 0.5f;
        float barW = padW * 2f + 2f * u;
        float barY = padY - 2.6f * u;
        g.roundedRect(cx - barW * 0.5f, barY, barW, 1.2f * u, 0.6f * u,
                PANEL[0], PANEL[1], PANEL[2], 0.6f);
        float knob = clamp(steerShown, -1f, 1f) * (barW * 0.5f - 2f * u);
        g.roundedRect(cx + knob - 2f * u, barY - 0.5f * u, 4f * u, 2.2f * u, 1.1f * u,
                ACCENT[0], ACCENT[1], ACCENT[2], 0.9f);
    }

    private void steerPad(HudProgram g, float x, boolean left, boolean lit) {
        float radius = 3.5f * u;
        g.roundedRect(x, padY, padW, padH, radius,
                PANEL[0], PANEL[1], PANEL[2], lit ? 0.80f : 0.52f);
        if (lit) {
            g.roundedRect(x, padY, padW, padH, radius,
                    ACCENT[0], ACCENT[1], ACCENT[2], 0.26f);
        }
        g.roundedRectOutline(x, padY, padW, padH, radius, lit ? 0.5f * u : 0.3f * u,
                ACCENT[0], ACCENT[1], ACCENT[2], lit ? 0.95f : 0.45f);

        float cx = x + padW * 0.5f;
        float cy = padY + padH * 0.5f;
        float s = padW * 0.26f;
        float dir = left ? -1f : 1f;
        float a = lit ? 0.55f + 0.45f * steerPressure : 0.72f;
        // A chevron pair reads as a direction far better than one triangle.
        for (int i = 0; i < 2; i++) {
            float off = (i == 0 ? -0.42f : 0.42f) * s;
            chevron(g, cx + dir * off, cy, s, dir, a);
        }
    }

    private void chevron(HudProgram g, float cx, float cy, float s, float dir, float alpha) {
        float t = s * 0.34f;
        // Two thick strokes meeting at the tip.
        g.line(cx - dir * s * 0.45f, cy - s, cx + dir * s * 0.45f, cy, t,
                ACCENT[0], ACCENT[1], ACCENT[2], alpha);
        g.line(cx + dir * s * 0.45f, cy, cx - dir * s * 0.45f, cy + s, t,
                ACCENT[0], ACCENT[1], ACCENT[2], alpha);
    }

    // ---------------------------------------------------------------- pedals

    private void drawPedals(HudProgram g) {
        pedal(g, gasX, gasY, gasR, gasLit, GAS, "GAZ");
        pedal(g, brakeX, brakeY, brakeR, brakeLit, BRAKE, "FREN");
        pedal(g, handX, handY, handR, handLit, HAND, "EL FR");
    }

    private void pedal(HudProgram g, float cx, float cy, float r, boolean lit,
                       float[] col, String label) {
        g.circle(cx, cy, r, 34, PANEL[0], PANEL[1], PANEL[2], lit ? 0.85f : 0.55f);
        if (lit) {
            g.circle(cx, cy, r - 0.8f * u, 32, col[0], col[1], col[2], 0.30f);
        }
        g.ring(cx, cy, r - (lit ? 0.9f : 0.55f) * u, r, 36, 0f, (float) (Math.PI * 2),
                col[0], col[1], col[2], lit ? 1f : 0.55f);
        // Size the caption from the circle rather than guessing, so a longer
        // word never spills outside the button it belongs to.
        float pixel = Math.min(1.15f * u, (r * 1.45f) / Math.max(1, label.length() * 6 - 1));
        PixelFont.drawCentered(g, label, cx, cy - PixelFont.height(pixel) * 0.5f, pixel,
                lit ? 1f : TEXT[0], lit ? 1f : TEXT[1], lit ? 1f : TEXT[2], lit ? 1f : 0.88f);
    }

    // --------------------------------------------------------------- buttons

    private void drawButtons(HudProgram g, int lightMode, boolean tiltEnabled) {
        for (int i = 0; i < BTN_COUNT; i++) {
            boolean on = (i == BTN_LIGHTS && (lightMode == 1 || lightMode == 2))
                    || (i == BTN_TILT && tiltEnabled);
            boolean held = btnDown[i];
            float radius = 2.6f * u;

            g.roundedRect(btnX[i], btnY, btnW, btnH, radius,
                    PANEL[0], PANEL[1], PANEL[2], held ? 0.85f : 0.55f);
            if (on || held) {
                g.roundedRect(btnX[i], btnY, btnW, btnH, radius,
                        ACCENT[0], ACCENT[1], ACCENT[2], held ? 0.30f : 0.18f);
            }
            g.roundedRectOutline(btnX[i], btnY, btnW, btnH, radius,
                    (on || held) ? 0.45f * u : 0.28f * u,
                    ACCENT[0], ACCENT[1], ACCENT[2], (on || held) ? 0.95f : 0.40f);

            float cx = btnX[i] + btnW * 0.5f;
            float cy = btnY + btnH * 0.40f;
            float s = btnW * 0.30f;
            float ia = on ? 1f : 0.85f;
            float ir = on ? ACCENT[0] : TEXT[0];
            float ig = on ? ACCENT[1] : TEXT[1];
            float ib = on ? ACCENT[2] : TEXT[2];
            switch (i) {
                case BTN_CAMERA: iconCamera(g, cx, cy, s, ir, ig, ib, ia); break;
                case BTN_CAR: iconCar(g, cx, cy, s, ir, ig, ib, ia); break;
                case BTN_LIGHTS: iconLight(g, cx, cy, s, ir, ig, ib, ia, lightMode == 2); break;
                case BTN_TILT: iconTilt(g, cx, cy, s, ir, ig, ib, ia); break;
                default: iconReset(g, cx, cy, s, ir, ig, ib, ia); break;
            }

            float pixel = 0.5f * u;
            PixelFont.drawCentered(g, BTN_LABEL[i], cx, btnY + btnH - 4.4f * u, pixel,
                    on ? ACCENT[0] : TEXT_DIM[0], on ? ACCENT[1] : TEXT_DIM[1],
                    on ? ACCENT[2] : TEXT_DIM[2], 0.95f);
        }
    }

    private void iconCamera(HudProgram g, float cx, float cy, float s,
                            float r, float gg, float b, float a) {
        g.roundedRect(cx - s * 0.30f, cy - s * 0.95f, s * 0.6f, s * 0.3f, s * 0.1f, r, gg, b, a);
        g.roundedRect(cx - s, cy - s * 0.7f, s * 2f, s * 1.5f, s * 0.25f, r, gg, b, a);
        g.circle(cx, cy, s * 0.46f, 16, PANEL[0], PANEL[1], PANEL[2], 0.95f);
        g.ring(cx, cy, s * 0.30f, s * 0.46f, 18, 0f, (float) (Math.PI * 2), r, gg, b, a);
    }

    private void iconCar(HudProgram g, float cx, float cy, float s,
                         float r, float gg, float b, float a) {
        g.roundedRect(cx - s, cy - s * 0.12f, s * 2f, s * 0.85f, s * 0.22f, r, gg, b, a);
        // Cabin: a squat trapezoid sitting on the body.
        g.triangle(cx - s * 0.62f, cy - s * 0.12f, cx - s * 0.34f, cy - s * 0.78f,
                cx + s * 0.34f, cy - s * 0.78f, r, gg, b, a);
        g.triangle(cx - s * 0.62f, cy - s * 0.12f, cx + s * 0.34f, cy - s * 0.78f,
                cx + s * 0.62f, cy - s * 0.12f, r, gg, b, a);
        g.circle(cx - s * 0.55f, cy + s * 0.78f, s * 0.28f, 12, r, gg, b, a);
        g.circle(cx + s * 0.55f, cy + s * 0.78f, s * 0.28f, 12, r, gg, b, a);
    }

    private void iconLight(HudProgram g, float cx, float cy, float s,
                           float r, float gg, float b, float a, boolean mainBeam) {
        float half = (float) Math.PI * 0.5f;
        g.pie(cx - s * 0.25f, cy, s * 0.85f, half, (float) Math.PI, 12, r, gg, b, a);
        g.rect(cx - s * 0.60f, cy - s * 0.85f, s * 0.35f, s * 1.7f, r, gg, b, a);
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * s * 0.55f;
            // Dipped beams angle down, main beams fire straight ahead.
            float drop = mainBeam ? 0f : s * 0.22f;
            g.line(cx + s * 0.45f, y, cx + s * 1.10f, y + drop, s * 0.20f, r, gg, b, a);
        }
    }

    private void iconTilt(HudProgram g, float cx, float cy, float s,
                          float r, float gg, float b, float a) {
        g.rotatedRect(cx, cy, s * 1.8f, s * 1.0f, 0.42f, r, gg, b, a);
        g.rotatedRect(cx, cy, s * 1.3f, s * 0.5f, 0.42f, PANEL[0], PANEL[1], PANEL[2], 0.9f);
        g.line(cx - s * 1.25f, cy + s * 0.85f, cx + s * 1.25f, cy - s * 0.30f, s * 0.14f,
                r, gg, b, a * 0.6f);
    }

    private void iconReset(HudProgram g, float cx, float cy, float s,
                           float r, float gg, float b, float a) {
        g.ring(cx, cy, s * 0.52f, s * 0.82f, 20, -2.2f, 4.9f, r, gg, b, a);
        float tipX = cx + (float) Math.cos(-2.2f) * s * 0.67f;
        float tipY = cy + (float) Math.sin(-2.2f) * s * 0.67f;
        g.triangle(tipX - s * 0.45f, tipY - s * 0.10f,
                tipX + s * 0.18f, tipY - s * 0.48f,
                tipX + s * 0.22f, tipY + s * 0.28f, r, gg, b, a);
    }

    // ------------------------------------------------------------- speed dash

    private void drawDash(HudProgram g, Car car) {
        panel(g, dashX, dashY, dashW, dashH, 2.8f * u, PANEL[3]);

        float pad = 3f * u;
        float max = Math.max(60f, car.spec.topSpeedKmh());
        float frac = clamp(car.speedKmh() / max, 0f, 1f);

        // Speed bar across the top of the panel: accent until the car is near
        // its limit, then red, which is easier to read at a glance than a
        // colour that drifts continuously.
        float barX = dashX + pad;
        float barW = dashW - pad * 2f;
        float barY = dashY + 2.2f * u;
        float barH = 1.5f * u;
        g.roundedRect(barX, barY, barW, barH, barH * 0.5f, 0.09f, 0.12f, 0.16f, 0.9f);
        if (frac > 0.005f) {
            float hot = clamp((frac - 0.82f) / 0.18f, 0f, 1f);
            float cr = ACCENT[0] + (1.00f - ACCENT[0]) * hot;
            float cg = ACCENT[1] + (0.36f - ACCENT[1]) * hot;
            float cb = ACCENT[2] + (0.28f - ACCENT[2]) * hot;
            g.roundedRect(barX, barY, Math.max(barH, barW * frac), barH, barH * 0.5f,
                    cr, cg, cb, 1f);
        }

        float digitH = 9f * u;
        float digitW = 5f * u;
        float digitsY = dashY + 5.2f * u;
        float digitsRight = dashX + pad + PixelFont.numberWidth(3, digitW);
        PixelFont.sevenSegmentNumber(g, Math.round(car.speedKmh()), 3,
                digitsRight, digitsY, digitW, digitH, 0.95f * u,
                TEXT[0], TEXT[1], TEXT[2], 1f,
                0.20f, 0.27f, 0.33f, 0.35f);

        // Gear badge above, unit below — never on top of one another.
        float colX = digitsRight + 2.6f * u;
        String gear = car.inReverse ? "R"
                : (Math.abs(car.forwardSpeed) < 0.4f ? "N" : ("D" + car.gear));
        float gearPixel = 0.78f * u;
        float gearW = PixelFont.width(gear, gearPixel) + 2.6f * u;
        float gearH = PixelFont.height(gearPixel) + 1.8f * u;
        g.roundedRect(colX, digitsY, gearW, gearH, 0.8f * u,
                ACCENT[0] * 0.32f, ACCENT[1] * 0.32f, ACCENT[2] * 0.34f, 0.9f);
        PixelFont.drawCentered(g, gear, colX + gearW * 0.5f, digitsY + 0.9f * u, gearPixel,
                ACCENT[0], ACCENT[1], ACCENT[2], 1f);

        PixelFont.draw(g, "KM/S", colX, digitsY + gearH + 0.8f * u, 0.58f * u,
                TEXT_DIM[0], TEXT_DIM[1], TEXT_DIM[2], 0.95f);
    }

    /** Live drift score, shown only while the car is actually sideways. */
    private void drawDrift(HudProgram g, Car car) {
        // The meter stays up for a moment after a run banks, so the player
        // actually sees what the slide was worth.
        boolean live = car.driftNow > 1f;
        float target = (live || car.driftBankedTimer > 0f) ? 1f : 0f;
        driftShown += (target - driftShown) * 0.12f;
        if (driftShown < 0.02f) return;

        float shownAngle = Math.abs(car.slipAngle) * 57.2958f;
        driftAngleShown += (shownAngle - driftAngleShown) * 0.25f;

        // Top centre, under the notification slot: clear of the minimap, clear
        // of the stats, and clear of the car — which is the point.
        float w = 42f * u;
        float h = 20f * u;
        float left = mapX + mapR + 2f * u;
        float cx = (left + width - 3f * u) * 0.5f;
        float x = cx - w * 0.5f;
        float y = btnY + btnH + 11.8f * u;
        float a = driftShown;
        float pad = 2.6f * u;

        g.roundedRect(x, y, w, h, 2f * u, PANEL[0], PANEL[1], PANEL[2], 0.82f * a);
        g.roundedRectOutline(x, y, w, h, 2f * u, 0.3f * u,
                HAND[0], HAND[1], HAND[2], 0.9f * a);

        PixelFont.draw(g, "DRIFT", x + pad, y + 1.8f * u, 0.46f * u,
                HAND[0], HAND[1], HAND[2], a * 0.9f);

        // The multiplier is the reason to keep holding the slide, so it is
        // always on show — dim at one, lit and solid once it is climbing.
        String mult = "X" + car.driftMultiplier;
        float mw = PixelFont.width(mult, 0.75f * u);
        float chipW = mw + 2.8f * u;
        float chipX = x + w - chipW - pad;
        if (car.driftMultiplier > 1) {
            float glow = 0.5f + 0.1f * car.driftMultiplier;
            g.roundedRect(chipX, y + 1.2f * u, chipW, 6.4f * u, 1.3f * u,
                    HAND[0] * glow, HAND[1] * glow, HAND[2] * glow, 0.9f * a);
            PixelFont.draw(g, mult, chipX + 1.4f * u, y + 2.4f * u, 0.75f * u,
                    0.06f, 0.05f, 0.04f, a);
        } else {
            PixelFont.draw(g, mult, chipX + 1.4f * u, y + 2.4f * u, 0.75f * u,
                    TEXT_DIM[0], TEXT_DIM[1], TEXT_DIM[2], 0.7f * a);
        }

        // Score big on the left, the live angle small on the right.
        PixelFont.draw(g, Integer.toString(Math.round(car.driftNow)),
                x + pad, y + 8.0f * u, 0.9f * u, TEXT[0], TEXT[1], TEXT[2], a);
        PixelFont.drawRight(g, Math.round(driftAngleShown) + " ACI", x + w - pad,
                y + 9.4f * u, 0.5f * u, TEXT_DIM[0], TEXT_DIM[1], TEXT_DIM[2], 0.9f * a);

        // Angle bar along the bottom. The tick marks where the tyres stop
        // gripping and start paying — past it is where the points are.
        float barX = x + pad;
        float barW = w - 2f * pad;
        float barY = y + h - 3.8f * u;
        float full = 70f;
        g.roundedRect(barX, barY, barW, 1.8f * u, 0.9f * u,
                TEXT_DIM[0] * 0.3f, TEXT_DIM[1] * 0.3f, TEXT_DIM[2] * 0.3f, 0.85f * a);
        float fill = Math.min(1f, driftAngleShown / full);
        // Amber while it builds, red once the slide is deeper than it is worth.
        float hot = Math.min(1f, Math.max(0f, (driftAngleShown - 45f) / 25f));
        if (fill > 0.02f) {
            g.roundedRect(barX, barY, Math.max(2f * u, barW * fill), 1.8f * u, 0.9f * u,
                    HAND[0], HAND[1] * (1f - 0.55f * hot), HAND[2] * (1f - 0.8f * hot), a);
        }
        float tick = barX + barW * (12f / full);    // the scoring threshold
        g.rect(tick, barY - 0.7f * u, 0.36f * u, 3.2f * u,
                TEXT[0], TEXT[1], TEXT[2], 0.6f * a);

        // What the run just paid, flashed above the panel as it banks.
        if (car.driftBankedTimer > 0f) {
            float fade = Math.min(1f, car.driftBankedTimer / 0.6f);
            PixelFont.drawCentered(g, "+" + Math.round(car.driftBanked), cx,
                    y + h + 1.6f * u, 1.0f * u, ACCENT[0], ACCENT[1], ACCENT[2], fade * a);
        }
    }

    // --------------------------------------------------------------- minimap

    private void drawMinimap(HudProgram g, Car car, Traffic traffic) {
        float viewRange = 230f;
        float scale = mapR / viewRange;

        g.circle(mapX, mapY, mapR, 44, PANEL[0], PANEL[1], PANEL[2], 0.78f);

        float cosY = (float) Math.cos(car.yaw);
        float sinY = (float) Math.sin(car.yaw);

        int range = (int) Math.ceil(viewRange / Terrain.CHUNK) + 1;
        int baseI = Math.round(car.x / Terrain.CHUNK);
        int baseJ = Math.round(car.z / Terrain.CHUNK);
        for (int k = -range; k <= range; k++) {
            gridLine(g, car, cosY, sinY, scale, (baseI + k) * Terrain.CHUNK, true, viewRange);
            gridLine(g, car, cosY, sinY, scale, (baseJ + k) * Terrain.CHUNK, false, viewRange);
        }

        int n = traffic.collectPositions(trafficScratch);
        float limit = (mapR - 1.2f * u) * (mapR - 1.2f * u);
        for (int i = 0; i < n; i++) {
            float dx = trafficScratch[i * 2] - car.x;
            float dz = trafficScratch[i * 2 + 1] - car.z;
            float fwd = dx * sinY + dz * cosY;
            float rgt = -dx * cosY + dz * sinY;
            float sx = mapX + rgt * scale;
            float sy = mapY - fwd * scale;
            float ddx = sx - mapX, ddy = sy - mapY;
            if (ddx * ddx + ddy * ddy > limit) continue;
            g.circle(sx, sy, 0.9f * u, 8, 1f, 0.76f, 0.26f, 0.95f);
        }

        // The player is always dead centre, pointing up.
        g.triangle(mapX, mapY - 2.4f * u,
                mapX - 1.7f * u, mapY + 2.0f * u,
                mapX + 1.7f * u, mapY + 2.0f * u,
                ACCENT[0], ACCENT[1], ACCENT[2], 1f);
        g.ring(mapX, mapY, mapR - 0.45f * u, mapR, 44, 0f, (float) (Math.PI * 2),
                EDGE[0], EDGE[1], EDGE[2], 0.8f);
    }

    private void gridLine(HudProgram g, Car car, float cosY, float sinY, float scale,
                          float coord, boolean vertical, float viewRange) {
        int steps = 20;
        float half = viewRange * 1.1f;
        float other0 = (vertical ? car.z : car.x) - half;
        float prevSx = 0f, prevSy = 0f;
        boolean prevInside = false;
        float limit = (mapR - 0.8f * u) * (mapR - 0.8f * u);

        for (int i = 0; i <= steps; i++) {
            float other = other0 + 2f * half * i / steps;
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
                g.line(prevSx, prevSy, sx, sy, 1.3f * u, 0.26f, 0.42f, 0.50f, 0.9f);
            }
            prevSx = sx;
            prevSy = sy;
            prevInside = inside;
        }
    }

    // ----------------------------------------------------------------- stats

    private void drawStats(HudProgram g, Car car, float timeOfDay, int fps) {
        panel(g, statX, statY, statW, statH, 2.2f * u, PANEL[3]);

        float pixel = 0.54f * u;
        float small = 0.46f * u;
        float x = statX + 2.2f * u;
        float y = statY + 2.2f * u;
        float lineH = 4f * u;

        PixelFont.draw(g, car.spec.name, x, y, pixel, ACCENT[0], ACCENT[1], ACCENT[2], 1f);
        PixelFont.draw(g, "YOL " + format1(car.distanceTravelled / 1000f) + " KM",
                x, y + lineH, pixel, TEXT[0], TEXT[1], TEXT[2], 0.95f);
        PixelFont.draw(g, "REKOR " + Math.round(car.topSpeedSeen) + " KM/S",
                x, y + lineH * 2f, pixel, TEXT_DIM[0], TEXT_DIM[1], TEXT_DIM[2], 0.95f);

        int totalMinutes = (int) (timeOfDay * 1440f) % 1440;
        String clock = pad2(totalMinutes / 60) + ":" + pad2(totalMinutes % 60);
        PixelFont.draw(g, clock, x, y + lineH * 3f, small,
                TEXT_DIM[0], TEXT_DIM[1], TEXT_DIM[2], 0.8f);
        PixelFont.drawRight(g, fps + " FPS", statX + statW - 2.2f * u, y + lineH * 3f, small,
                TEXT_DIM[0], TEXT_DIM[1], TEXT_DIM[2], 0.65f);
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

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
