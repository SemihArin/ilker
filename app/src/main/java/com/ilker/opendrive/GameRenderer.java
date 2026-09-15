package com.ilker.opendrive;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import com.ilker.opendrive.audio.EngineSound;
import com.ilker.opendrive.game.Car;
import com.ilker.opendrive.game.CarMesh;
import com.ilker.opendrive.game.CarModels;
import com.ilker.opendrive.game.CarSpec;
import com.ilker.opendrive.game.Controls;
import com.ilker.opendrive.game.SkidMarks;
import com.ilker.opendrive.game.Traffic;
import com.ilker.opendrive.gl.Frustum;
import com.ilker.opendrive.gl.HudProgram;
import com.ilker.opendrive.gl.Mesh;
import com.ilker.opendrive.gl.SceneProgram;
import com.ilker.opendrive.ui.Hud;
import com.ilker.opendrive.world.Obstacles;
import com.ilker.opendrive.world.Terrain;
import com.ilker.opendrive.world.World;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Drives the whole game: simulation, camera, lighting and drawing. */
public class GameRenderer implements GLSurfaceView.Renderer {

    private static final int MAX_POINTERS = 10;
    private static final float DAY_LENGTH_SECONDS = 420f;
    private static final float FAR_PLANE = 900f;

    private final SceneProgram scene = new SceneProgram();
    private final HudProgram hud2d = new HudProgram();
    private final Hud hud = new Hud();
    private final Frustum frustum = new Frustum();
    private final EngineSound engine = new EngineSound();

    private volatile World world; // created on the GL thread, released from the UI thread
    private Traffic traffic;
    private final Obstacles obstacles = new Obstacles();
    private final SkidMarks skid = new SkidMarks();
    private CarModels carModels;
    private final Car car = new Car();
    private final Controls controls = new Controls();

    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] viewProj = new float[16];
    private final float[] mvp = new float[16];
    private final float[] identity = new float[16];

    private final float[] sunDir = new float[3];
    private final float[] sunColor = new float[3];
    private final float[] ambient = new float[3];
    private final float[] fogColor = new float[3];
    private final float[] zenith = new float[3];

    private int viewWidth = 1;
    private int viewHeight = 1;

    private float camX, camY, camZ;
    private float camLookX, camLookY, camLookZ;
    private boolean camInitialised;
    private int cameraMode;

    private int specIndex;
    private int lightMode;       // 0 auto, 1 dipped, 2 main, 3 off
    private float camShake;
    private float shakeTime;
    private boolean wasAirborne;
    private final Vibrator vibrator;
    private boolean tiltEnabled;
    private float timeOfDay = 0.34f;
    private float nightFactor;

    private long lastFrameNanos;
    private float fpsAccum;
    private int fpsFrames;
    private int fps;

    private String message = "SUR VE KESFET";
    private float messageAlpha = 1f;
    private float messageHold = 5f;

    // Touch state, written on the UI thread and copied on the GL thread.
    private final Object touchLock = new Object();
    private final float[] touchX = new float[MAX_POINTERS];
    private final float[] touchY = new float[MAX_POINTERS];
    private int touchCount;
    private final float[] localTouchX = new float[MAX_POINTERS];
    private final float[] localTouchY = new float[MAX_POINTERS];

    private static final String[] LIGHT_LABEL = {
            "FARLAR OTOMATIK", "KISA FAR", "UZUN FAR", "FARLAR KAPALI"};

    public GameRenderer(Context context) {
        Vibrator v = null;
        try {
            v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && !v.hasVibrator()) v = null;
        } catch (Throwable ignored) {
            // A device without a motor is not a reason to fail to start.
        }
        vibrator = v;
    }

    private volatile float tiltSteer;
    private volatile float tiltNeutral;
    private volatile boolean tiltNeedsCalibration = true;
    private volatile boolean paused;

    public void setTouches(float[] xs, float[] ys, int count) {
        synchronized (touchLock) {
            touchCount = Math.min(count, MAX_POINTERS);
            System.arraycopy(xs, 0, touchX, 0, touchCount);
            System.arraycopy(ys, 0, touchY, 0, touchCount);
        }
    }

    /**
     * Raw screen-space tilt from the accelerometer. The first sample after
     * tilt steering is switched on becomes the neutral hold, so the player can
     * sit however they like.
     */
    public void setTiltRaw(float raw) {
        if (tiltNeedsCalibration) {
            tiltNeutral = raw;
            tiltNeedsCalibration = false;
        }
        float value = (raw - tiltNeutral) / 4.2f;
        float dead = 0.10f;
        if (value > dead) value -= dead;
        else if (value < -dead) value += dead;
        else value = 0f;
        if (value > 1f) value = 1f;
        if (value < -1f) value = -1f;
        tiltSteer = value;
    }

    public void onPause() {
        paused = true;
        engine.stop();
    }

    public void onResume() {
        paused = false;
        lastFrameNanos = 0L;
        engine.start();
    }

    public void release() {
        engine.stop();
        World w = world;
        if (w != null) w.shutdown();
    }

    /** A short buzz proportional to how hard the car hit something. */
    private void thump(float speed) {
        if (vibrator == null || speed < 1.5f) return;
        int ms = (int) Math.min(90f, 18f + speed * 4f);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int amplitude = (int) Math.min(255f, 90f + speed * 9f);
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        Matrix.setIdentityM(identity, 0);

        scene.create();
        hud2d.create();

        if (world != null) {
            world.disposeAll();
            world.shutdown();
        }
        world = new World();
        if (carModels != null) carModels.dispose();
        carModels = new CarModels();
        carModels.create();
        traffic = new Traffic();
        traffic.reset();
        skid.dispose();
        skid.create();

        // Start in the right-hand lane of the road running along the Z axis.
        car.reset(CarSpec.GARAGE[specIndex], -Terrain.LANE_OFFSET, 18f, 0f);
        camInitialised = false;

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glFrontFace(GLES20.GL_CCW);

        engine.start();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewWidth = Math.max(1, width);
        viewHeight = Math.max(1, height);
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        hud2d.setViewport(viewWidth, viewHeight);
        hud.layout(viewWidth, viewHeight);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        float dt = tick();
        if (!paused) {
            simulate(dt);
        }
        render(dt);
    }

    private float tick() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1f / 60f;
        }
        float dt = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        if (dt < 0f) dt = 0f;
        if (dt > 0.05f) dt = 0.05f; // never let one long frame teleport the car
        fpsAccum += dt;
        fpsFrames++;
        if (fpsAccum >= 0.5f) {
            fps = Math.round(fpsFrames / fpsAccum);
            fpsAccum = 0f;
            fpsFrames = 0;
        }
        return dt;
    }

    // -------------------------------------------------------------- simulate

    private void simulate(float dt) {
        int count;
        synchronized (touchLock) {
            count = touchCount;
            System.arraycopy(touchX, 0, localTouchX, 0, count);
            System.arraycopy(touchY, 0, localTouchY, 0, count);
        }

        hud.processInput(localTouchX, localTouchY, count, controls, tiltSteer, tiltEnabled);
        handleButtons();

        car.update(dt, controls);
        obstacles.refresh(car.x, car.z);
        float bump = car.resolveObstacles(obstacles);
        if (bump > 1.5f) thump(bump);
        if (wasAirborne && !car.airborne) thump(car.landing * 0.8f);
        wasAirborne = car.airborne;
        skid.follow(car);
        traffic.update(dt, car);
        world.update(car.x, car.z);

        timeOfDay += dt / DAY_LENGTH_SECONDS;
        if (timeOfDay >= 1f) timeOfDay -= 1f;

        updateCamera(dt);

        // The engine note comes straight off the gearbox now, so the shifts
        // you hear are the shifts the car is actually making.
        float load = Math.max(controls.throttle, Math.min(1f, Math.abs(car.forwardSpeed) / 14f));
        float squeal = Math.max(car.wheelspin,
                Math.max(car.lockup * 0.9f,
                        Math.min(1f, Math.max(0f, Math.abs(car.slipAngle) - 0.14f) * 4.5f)));
        if (Math.abs(car.forwardSpeed) < 1.5f && car.wheelspin < 0.05f) squeal = 0f;
        if (car.airborne) {
            squeal = 0f;
            load = controls.throttle;
        }
        engine.setState(car.engineRevs, load);
        engine.setSlip(squeal);

        if (messageHold > 0f) {
            messageHold -= dt;
        } else if (messageAlpha > 0f) {
            messageAlpha -= dt * 0.8f;
            if (messageAlpha < 0f) messageAlpha = 0f;
        }
    }

    private void handleButtons() {
        if (hud.wasTapped(Hud.BTN_CAMERA)) {
            cameraMode = (cameraMode + 1) % 3;
            camInitialised = false;
            showMessage(cameraMode == 0 ? "KAMERA: TAKIP"
                    : (cameraMode == 1 ? "KAMERA: KAPUT" : "KAMERA: GENIS"));
        }
        if (hud.wasTapped(Hud.BTN_CAR)) {
            specIndex = (specIndex + 1) % CarSpec.GARAGE.length;
            car.changeSpec(CarSpec.GARAGE[specIndex]);
            showMessage(CarSpec.GARAGE[specIndex].name + "  "
                    + CarSpec.GARAGE[specIndex].topSpeedKmh() + " KM/S");
        }
        if (hud.wasTapped(Hud.BTN_LIGHTS)) {
            lightMode = (lightMode + 1) % LIGHT_LABEL.length;
            showMessage(LIGHT_LABEL[lightMode]);
        }
        if (hud.wasTapped(Hud.BTN_TILT)) {
            tiltEnabled = !tiltEnabled;
            if (tiltEnabled) {
                tiltNeedsCalibration = true; // hold the phone as you mean to drive
                tiltSteer = 0f;
            }
            showMessage(tiltEnabled ? "EGIMLE DIREKSIYON ACIK" : "EGIM KAPALI");
        }
        if (hud.wasTapped(Hud.BTN_RESET)) {
            car.reset(CarSpec.GARAGE[specIndex], -Terrain.LANE_OFFSET,
                    Math.round(car.z / Terrain.CHUNK) * Terrain.CHUNK + 18f, 0f);
            camInitialised = false;
            skid.clear();
            showMessage("YOLA GERI DONULDU");
        }
    }

    private void showMessage(String text) {
        message = text;
        messageAlpha = 1f;
        messageHold = 2.2f;
    }

    private void updateCamera(float dt) {
        float fx = car.forwardX();
        float fz = car.forwardZ();
        float speedFrac = Math.min(1f, Math.abs(car.forwardSpeed) / car.spec.topSpeed);

        float wantX, wantY, wantZ;
        float follow;

        if (cameraMode == 1) {
            // Bonnet cam sits rigidly on the car.
            wantX = car.x + fx * (car.spec.length * 0.12f);
            wantY = car.y + car.spec.beltY + 0.34f;
            wantZ = car.z + fz * (car.spec.length * 0.12f);
            follow = 1f;
            camLookX = wantX + fx * 30f;
            camLookY = wantY - 1.6f;
            camLookZ = wantZ + fz * 30f;
        } else {
            float distance = (cameraMode == 2 ? 11.5f : 6.3f) + speedFrac * 2.6f;
            float lift = (cameraMode == 2 ? 5.0f : 2.45f) + speedFrac * 0.5f;
            wantX = car.x - fx * distance;
            wantY = car.y + lift;
            wantZ = car.z - fz * distance;
            follow = 1f - (float) Math.exp(-dt * 7.5f);
            camLookX = car.x + fx * 5.5f;
            camLookY = car.y + 1.15f;
            camLookZ = car.z + fz * 5.5f;
        }

        if (!camInitialised) {
            camX = wantX;
            camY = wantY;
            camZ = wantZ;
            camInitialised = true;
        } else {
            camX += (wantX - camX) * follow;
            camY += (wantY - camY) * follow;
            camZ += (wantZ - camZ) * follow;
        }

        // Never let the camera sink into a hill.
        float floor = Terrain.surfaceHeight(camX, camZ) + 0.9f;
        if (camY < floor) camY = floor;

        // Speed, rough ground and impacts all shake the view a little. The
        // bonnet camera gets more of it — it is bolted to the car, after all.
        float rough = speedFrac * speedFrac * 0.30f
                + (1f - Terrain.surfaceGrip(car.x, car.z)) * 0.55f * Math.min(1f, speedFrac * 2.5f)
                + Math.min(1f, car.impact * 0.10f)
                + Math.min(1f, car.landing * 0.09f);
        camShake += (rough - camShake) * Math.min(1f, dt * 9f);
        shakeTime += dt;
        float amount = camShake * (cameraMode == 1 ? 0.16f : 0.10f);
        if (amount > 0.002f) {
            camX += (float) Math.sin(shakeTime * 37.1f) * amount;
            camY += (float) Math.sin(shakeTime * 51.7f) * amount * 1.3f;
            camZ += (float) Math.sin(shakeTime * 43.3f) * amount;
        }
    }

    // ---------------------------------------------------------------- render

    private void render(float dt) {
        computeLighting();

        GLES20.glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        drawSky();

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_CULL_FACE);

        float speedFrac = Math.min(1f, Math.abs(car.forwardSpeed) / car.spec.topSpeed);
        float fov = 62f + speedFrac * 13f;
        float aspect = viewWidth / (float) viewHeight;
        perspective(projection, fov, aspect, 0.35f, FAR_PLANE);
        Matrix.setLookAtM(view, 0, camX, camY, camZ, camLookX, camLookY, camLookZ, 0f, 1f, 0f);
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0);
        frustum.set(viewProj);

        scene.use();
        scene.setLighting(sunDir, sunColor, ambient, fogColor, 0.0042f, nightFactor);
        scene.setCamera(camX, camY, camZ);

        boolean mainBeam = lightMode == 2;
        boolean lightsOn = lightMode == 1 || mainBeam
                || (lightMode == 0 && nightFactor > 0.42f);
        if (mainBeam) {
            scene.setBeam(0.80f, 0.955f, 5f, 135f, 3.4f);
        } else {
            scene.setBeam(0.825f, 0.968f, 5f, 70f, 3.2f);
        }

        float fwdX = car.forwardX();
        float fwdZ = car.forwardZ();
        float sideX = car.rightX();
        float sideZ = car.rightZ();
        float noseX = car.x + fwdX * (car.spec.length * 0.5f);
        float noseZ = car.z + fwdZ * (car.spec.length * 0.5f);
        float lampY = car.y + car.spec.beltY * 0.75f;
        float lampOut = car.spec.width * 0.30f;
        float dy = mainBeam ? -0.09f : -0.14f;
        float dLen = (float) Math.sqrt(1f + dy * dy);
        scene.setHeadlights(lightsOn,
                noseX - sideX * lampOut, lampY, noseZ - sideZ * lampOut,
                noseX + sideX * lampOut, lampY, noseZ + sideZ * lampOut,
                fwdX / dLen, dy / dLen, fwdZ / dLen);

        world.draw(scene, frustum, viewProj, identity);
        scene.setMatrices(viewProj, identity);
        skid.draw(scene);
        drawPlayerCar(lightsOn);
        traffic.draw(scene, carModels, frustum, viewProj, mvp, camX, camZ, lightsOn);
        scene.disableAttributes();

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        hud2d.begin();
        hud.draw(hud2d, car, traffic, lightMode, tiltEnabled, timeOfDay, fps, message, messageAlpha);
        hud2d.end();
    }

    private void drawSky() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glDisable(GLES20.GL_BLEND);
        hud2d.begin();
        hud2d.gradientRect(0f, 0f, viewWidth, viewHeight * 0.55f,
                zenith[0], zenith[1], zenith[2], 1f,
                fogColor[0], fogColor[1], fogColor[2], 1f);
        hud2d.gradientRect(0f, viewHeight * 0.55f, viewWidth, viewHeight * 0.45f,
                fogColor[0], fogColor[1], fogColor[2], 1f,
                fogColor[0] * 0.82f, fogColor[1] * 0.82f, fogColor[2] * 0.85f, 1f);
        hud2d.end();
        GLES20.glDepthMask(true);
    }

    private void drawPlayerCar(boolean lightsOn) {
        float[] model = car.modelMatrix();
        Mesh body = carModels.body(specIndex, 0);
        if (body != null) {
            Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0);
            scene.setMatrices(mvp, model);
            body.draw(scene);
        }

        // Lit lenses are separate meshes, so the car can show what it is doing.
        // In reverse the pedals swap roles, so the lamps have to follow.
        boolean reversing = car.inReverse;
        boolean braking = reversing
                ? (controls.throttle > 0.5f && car.forwardSpeed < -0.3f)
                : controls.brake > 0.5f;
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0);
        scene.setMatrices(mvp, model);
        if (lightsOn) drawLamp(CarMesh.LAMP_HEAD);
        if (braking) {
            drawLamp(CarMesh.LAMP_BRAKE);
        } else if (lightsOn) {
            drawLamp(CarMesh.LAMP_TAIL);
        }
        if (reversing) drawLamp(CarMesh.LAMP_REVERSE);
        Mesh wheel = carModels.wheel(specIndex);
        if (wheel != null) {
            for (int i = 0; i < 4; i++) {
                float[] wm = car.wheelMatrix(i);
                Matrix.multiplyMM(mvp, 0, viewProj, 0, wm, 0);
                scene.setMatrices(mvp, wm);
                wheel.draw(scene);
            }
        }
    }

    private void drawLamp(int kind) {
        Mesh lamp = carModels.lamp(specIndex, kind);
        if (lamp != null) lamp.draw(scene);
    }

    /** Sun position, light colours and fog for the current time of day. */
    private void computeLighting() {
        double angle = (timeOfDay - 0.25) * Math.PI * 2.0;
        float elevation = (float) Math.sin(angle);
        float east = (float) Math.cos(angle);

        float dirY = Math.max(0.06f, elevation);
        float len = (float) Math.sqrt(east * east + dirY * dirY + 0.35f * 0.35f);
        sunDir[0] = east / len;
        sunDir[1] = dirY / len;
        sunDir[2] = 0.35f / len;

        float daylight = clamp(elevation * 2.4f + 0.20f, 0f, 1f);
        nightFactor = 1f - daylight;
        float golden = clamp(1f - Math.abs(elevation) * 3.4f, 0f, 1f) * daylight;

        sunColor[0] = lerp(0.08f, 1.02f, daylight) + golden * 0.18f;
        sunColor[1] = lerp(0.09f, 0.97f, daylight) - golden * 0.06f;
        sunColor[2] = lerp(0.16f, 0.88f, daylight) - golden * 0.22f;

        ambient[0] = lerp(0.10f, 0.42f, daylight);
        ambient[1] = lerp(0.12f, 0.46f, daylight);
        ambient[2] = lerp(0.20f, 0.56f, daylight);

        fogColor[0] = lerp(0.045f, 0.70f, daylight) + golden * 0.24f;
        fogColor[1] = lerp(0.055f, 0.80f, daylight) + golden * 0.05f;
        fogColor[2] = lerp(0.095f, 0.92f, daylight) - golden * 0.12f;

        zenith[0] = lerp(0.015f, 0.26f, daylight) + golden * 0.14f;
        zenith[1] = lerp(0.025f, 0.50f, daylight) + golden * 0.04f;
        zenith[2] = lerp(0.070f, 0.88f, daylight);

        for (int i = 0; i < 3; i++) {
            sunColor[i] = clamp(sunColor[i], 0f, 1.4f);
            fogColor[i] = clamp(fogColor[i], 0f, 1f);
            zenith[i] = clamp(zenith[i], 0f, 1f);
        }
    }

    private static void perspective(float[] out, float fovDegrees, float aspect,
                                    float near, float far) {
        float top = (float) (near * Math.tan(Math.toRadians(fovDegrees * 0.5)));
        float right = top * aspect;
        Matrix.frustumM(out, 0, -right, right, -top, top, near, far);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
