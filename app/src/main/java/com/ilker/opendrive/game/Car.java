package com.ilker.opendrive.game;

import android.opengl.Matrix;

import com.ilker.opendrive.world.Terrain;

/**
 * Arcade vehicle model. Longitudinal motion is a simple power/resistance
 * balance; cornering comes from a bicycle-model yaw rate plus a lateral
 * velocity that is only gradually scrubbed off — which is what lets the car
 * slide when the handbrake is pulled.
 */
public class Car {

    public CarSpec spec;

    public float x, y, z;
    public float yaw;            // radians, 0 = facing +Z
    public float vx, vz;         // world-space velocity
    public float forwardSpeed;   // signed, along the car's nose
    public float lateralSpeed;   // signed, to the car's right

    public float steerAngle;     // radians
    public float wheelSpin;      // radians
    public float visualPitch;    // radians, nose up positive
    public float visualRoll;     // radians, right side up positive

    public float engineLoad;     // 0..1, drives the audio and the rev counter
    public int gear = 1;

    public float distanceTravelled;
    public float topSpeedSeen;

    private float bodyPitch;
    private float bodyRoll;
    private float suspensionY;
    private float lastForwardSpeed;

    private final float[] model = new float[16];
    private final float[] wheelModel = new float[16];
    private final float[] scratch = new float[16];

    public void reset(CarSpec spec, float startX, float startZ, float startYaw) {
        this.spec = spec;
        this.x = startX;
        this.z = startZ;
        this.yaw = startYaw;
        this.y = Terrain.surfaceHeight(startX, startZ);
        this.suspensionY = this.y;
        vx = 0f;
        vz = 0f;
        forwardSpeed = 0f;
        lateralSpeed = 0f;
        steerAngle = 0f;
        wheelSpin = 0f;
        visualPitch = 0f;
        visualRoll = 0f;
        bodyPitch = 0f;
        bodyRoll = 0f;
        engineLoad = 0f;
        lastForwardSpeed = 0f;
    }

    /** Keeps the current position and momentum but swaps the vehicle. */
    public void changeSpec(CarSpec next) {
        this.spec = next;
        if (forwardSpeed > next.topSpeed) forwardSpeed = next.topSpeed;
    }

    public float speedKmh() {
        return Math.abs(forwardSpeed) * 3.6f;
    }

    public void update(float dt, Controls c) {
        if (dt <= 0f) return;

        float fx = (float) Math.sin(yaw);
        float fz = (float) Math.cos(yaw);
        // The driver's right is forward x up. With +Y up and forward at
        // (sin, cos) that comes out as (-cos, sin) — NOT (cos, -sin), which is
        // the car's left and would turn the wheel the wrong way.
        float rightX = -(float) Math.cos(yaw);
        float rightZ = (float) Math.sin(yaw);

        float vLong = vx * fx + vz * fz;
        float vLat = vx * rightX + vz * rightZ;  // positive means sliding right

        float surface = Terrain.surfaceGrip(x, z);

        // ---- steering: less lock the faster you go
        float speedFrac = Math.min(1f, Math.abs(vLong) / (spec.topSpeed * 0.62f));
        float maxSteer = 0.62f - 0.46f * speedFrac;
        float targetSteer = c.steer * maxSteer;
        float steerBlend = Math.min(1f, dt * spec.steerRate * 3.2f);
        steerAngle += (targetSteer - steerAngle) * steerBlend;

        // ---- longitudinal forces
        float absV = Math.abs(vLong);
        float ratio = absV / spec.topSpeed;
        float resist = spec.enginePower * 0.72f * ratio * ratio + 1.3f + (1f - surface) * 7f;
        float drive = c.throttle * spec.enginePower * (0.45f + 0.55f * surface);

        if (c.brake > 0.01f) {
            if (vLong > 0.6f) {
                resist += c.brake * spec.brakePower;
                drive = 0f;
            } else {
                drive = -c.brake * spec.enginePower * 0.5f * surface;
            }
        }
        if (c.handbrake) {
            resist += 6f;
        }

        vLong += drive * dt;
        float scrub = resist * dt;
        if (vLong > 0f) {
            vLong = Math.max(0f, vLong - scrub);
        } else if (vLong < 0f) {
            vLong = Math.min(0f, vLong + scrub);
        }

        float maxForward = spec.topSpeed * (0.58f + 0.42f * surface);
        float maxReverse = spec.topSpeed * 0.24f;
        if (vLong > maxForward) vLong = maxForward;
        if (vLong < -maxReverse) vLong = -maxReverse;

        // ---- lateral grip; the handbrake is simply much less of it
        float gripRate = spec.grip * (0.5f + 0.5f * surface);
        if (c.handbrake) gripRate *= 0.16f;
        gripRate *= (1f - 0.28f * Math.min(1f, absV / spec.topSpeed));
        float latLoss = 1f - (float) Math.exp(-gripRate * dt);
        vLat -= vLat * latLoss;
        if (Math.abs(vLat) > spec.topSpeed * 0.7f) {
            vLat = Math.signum(vLat) * spec.topSpeed * 0.7f;
        }

        // ---- world velocity keeps its direction while the car rotates,
        //      which is what produces understeer and opposite lock
        vx = fx * vLong + rightX * vLat;
        vz = fz * vLong + rightZ * vLat;

        // steerAngle > 0 means turning right. Rotating the heading towards the
        // right vector means yaw has to decrease, since d(sin y, cos y)/dy
        // points to the car's left.
        float yawRate = (vLong / spec.wheelbase) * (float) Math.tan(steerAngle);
        if (c.handbrake) yawRate *= 1.4f;
        if (yawRate > 2.6f) yawRate = 2.6f;
        if (yawRate < -2.6f) yawRate = -2.6f;
        yaw -= yawRate * dt;
        if (yaw > Math.PI * 2) yaw -= (float) (Math.PI * 2);
        if (yaw < 0) yaw += (float) (Math.PI * 2);

        float dx = vx * dt;
        float dz = vz * dt;
        x += dx;
        z += dz;
        distanceTravelled += (float) Math.sqrt(dx * dx + dz * dz);

        forwardSpeed = vLong;
        lateralSpeed = vLat;
        if (speedKmh() > topSpeedSeen) topSpeedSeen = speedKmh();

        // ---- sit on the road, with a little suspension travel
        float target = Terrain.surfaceHeight(x, z);
        float follow = Math.min(1f, dt * 9f);
        suspensionY += (target - suspensionY) * follow;
        y = suspensionY;

        wheelSpin += (vLong / Math.max(0.1f, spec.wheelRadius)) * dt;
        if (wheelSpin > Math.PI * 2) wheelSpin -= (float) (Math.PI * 2);
        if (wheelSpin < 0) wheelSpin += (float) (Math.PI * 2);

        // ---- attitude: terrain slope plus weight transfer
        float halfBase = spec.wheelbase * 0.5f;
        float halfTrack = spec.track * 0.5f;
        float ahead = Terrain.surfaceHeight(x + fx * halfBase, z + fz * halfBase);
        float behind = Terrain.surfaceHeight(x - fx * halfBase, z - fz * halfBase);
        float rightH = Terrain.surfaceHeight(x + rightX * halfTrack, z + rightZ * halfTrack);
        float leftH = Terrain.surfaceHeight(x - rightX * halfTrack, z - rightZ * halfTrack);
        float slopePitch = (float) Math.atan2(ahead - behind, spec.wheelbase);   // + = nose up
        float slopeRoll = (float) Math.atan2(rightH - leftH, spec.track);        // + = right side up

        float longAccel = (vLong - lastForwardSpeed) / dt;
        lastForwardSpeed = vLong;
        // Accelerating lifts the nose; in a right-hand bend the body leans out
        // to the left, so the right-hand side comes up.
        float squat = clamp(longAccel * 0.009f, -0.075f, 0.075f);
        float lean = clamp(yawRate * vLong * 0.010f, -0.13f, 0.13f);

        float blend = Math.min(1f, dt * 7f);
        bodyPitch += (squat - bodyPitch) * blend;
        bodyRoll += (lean - bodyRoll) * blend;
        visualPitch = slopePitch + bodyPitch;
        visualRoll = slopeRoll + bodyRoll;

        float loadTarget = Math.min(1f, Math.abs(vLong) / spec.topSpeed * 0.75f + c.throttle * 0.35f);
        engineLoad += (loadTarget - engineLoad) * Math.min(1f, dt * 4f);
        gear = Math.max(1, Math.min(6, 1 + (int) (Math.abs(vLong) / (spec.topSpeed / 6f))));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public float forwardX() {
        return (float) Math.sin(yaw);
    }

    public float forwardZ() {
        return (float) Math.cos(yaw);
    }

    /** World-space direction of the driver's right hand. */
    public float rightX() {
        return -(float) Math.cos(yaw);
    }

    public float rightZ() {
        return (float) Math.sin(yaw);
    }

    /** Body transform, including the visual lean. */
    public float[] modelMatrix() {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.rotateM(model, 0, (float) Math.toDegrees(yaw), 0f, 1f, 0f);
        // Local +X is the car's left once the yaw is applied, so both of these
        // are negated to read as "nose up" and "right side up".
        Matrix.rotateM(model, 0, (float) -Math.toDegrees(visualPitch), 1f, 0f, 0f);
        Matrix.rotateM(model, 0, (float) -Math.toDegrees(visualRoll), 0f, 0f, 1f);
        return model;
    }

    /** Transform for one of the four wheels; index 0..3 = FL, FR, RL, RR. */
    public float[] wheelMatrix(int index) {
        boolean front = index < 2;
        float sideSign = (index == 0 || index == 2) ? -1f : 1f;
        float zOff = front ? spec.wheelbase * 0.5f : -spec.wheelbase * 0.5f;

        System.arraycopy(modelMatrix(), 0, scratch, 0, 16);
        System.arraycopy(scratch, 0, wheelModel, 0, 16);
        Matrix.translateM(wheelModel, 0, sideSign * spec.track * 0.5f, spec.wheelRadius, zOff);
        if (front) {
            Matrix.rotateM(wheelModel, 0, (float) -Math.toDegrees(steerAngle), 0f, 1f, 0f);
        }
        Matrix.rotateM(wheelModel, 0, (float) Math.toDegrees(wheelSpin), 1f, 0f, 0f);
        return wheelModel;
    }
}
