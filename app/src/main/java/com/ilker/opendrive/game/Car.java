package com.ilker.opendrive.game;

import android.opengl.Matrix;

import com.ilker.opendrive.world.Obstacles;
import com.ilker.opendrive.world.Terrain;

/**
 * Arcade vehicle model.
 *
 * Cornering comes from a bicycle-model yaw rate plus a lateral velocity that
 * is scrubbed off gradually. What makes it feel like a car rather than a
 * sliding box is that the scrub rate collapses once the slip angle passes a
 * peak: below it the tyres bite, above it they let go and keep letting go
 * until the driver reduces the angle. That is what makes opposite lock work.
 *
 * Weight transfer feeds the same loop from the other end — lifting off loads
 * the front and rotates the car, standing on the power washes the nose wide.
 */
public class Car {

    public CarSpec spec;

    public float x, y, z;
    public float yaw;            // radians, 0 = facing +Z
    public float vx, vz;         // world-space velocity
    public float forwardSpeed;   // signed, along the car's nose
    public float lateralSpeed;   // signed, to the car's right

    public float steerAngle;     // radians, positive is right
    public float wheelSpin;      // radians, for the rolling animation
    public float visualPitch;    // radians, nose up positive
    public float visualRoll;     // radians, right side up positive

    /** Angle between where the car points and where it is actually going. */
    public float slipAngle;
    /** 0..1, how much the driven wheels are spinning up. */
    public float wheelspin;
    /** 0..1, how much the brakes have the wheels locked. */
    public float lockup;
    /** True while the tyres are audibly giving up. */
    public boolean sliding;

    public float driftNow;
    public float driftBest;
    public float driftTotal;
    private float driftGrace;

    /** Speed of the last impact in m/s, decaying; drives haptics and audio. */
    public float impact;

    public int gear = 1;
    public float distanceTravelled;
    public float topSpeedSeen;

    private float bodyPitch;
    private float bodyRoll;
    private float suspensionY;
    private float lastForwardSpeed;

    private final float[] model = new float[16];
    private final float[] wheelModel = new float[16];
    private final float[] push = new float[2];

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
        slipAngle = 0f;
        wheelspin = 0f;
        lockup = 0f;
        sliding = false;
        driftNow = 0f;
        driftGrace = 0f;
        impact = 0f;
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
        float absV = Math.abs(vLong);

        // ---- steering: less lock the faster you go, and it straightens up
        //      faster than it winds on, so a twitch does not upset the car
        float speedFrac = Math.min(1f, absV / (spec.topSpeed * 0.62f));
        float maxSteer = 0.62f - 0.46f * speedFrac;
        float targetSteer = c.steer * maxSteer;
        boolean winding = Math.abs(targetSteer) > Math.abs(steerAngle);
        float steerRate = spec.steerRate * (winding ? 3.2f : 5.6f);
        steerAngle += (targetSteer - steerAngle) * Math.min(1f, dt * steerRate);

        // ---- engine and brakes
        float ratio = absV / spec.topSpeed;
        float resist = spec.enginePower * 0.72f * ratio * ratio + 1.3f + (1f - surface) * 7f;
        float drive = c.throttle * spec.enginePower * (0.45f + 0.55f * surface);

        // Tyres can only lay down so much. In a low gear the engine easily
        // beats them, which is where wheelspin comes from.
        wheelspin = 0f;
        if (drive > 0f) {
            float traction = (2.5f + 6.5f * surface) + 6.0f * surface * Math.min(1f, absV / 16f);
            if (c.handbrake) traction *= 0.35f;
            if (drive > traction) {
                wheelspin = Math.min(1f, (drive - traction) / drive);
                drive = traction + (drive - traction) * 0.15f;
            }
        }

        lockup = 0f;
        if (c.brake > 0.01f) {
            if (vLong > 0.6f) {
                float demand = c.brake * spec.brakePower;
                float maxBrake = 9.5f * surface;
                if (demand > maxBrake) {
                    lockup = Math.min(1f, (demand - maxBrake) / demand);
                    demand = maxBrake + (demand - maxBrake) * 0.25f;
                }
                resist += demand;
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

        float longAccel = (vLong - lastForwardSpeed) / dt;
        lastForwardSpeed = vLong;

        // ---- lateral grip, with a breakaway past the peak slip angle
        slipAngle = (float) Math.atan2(vLat, absV + 1.2f);
        float slip = Math.abs(slipAngle);
        float peak = 0.15f;
        float bite = slip <= peak ? 1f : Math.max(0.30f, 1f - (slip - peak) * 1.9f);

        float gripRate = spec.grip * (0.5f + 0.5f * surface) * bite;
        if (c.handbrake) gripRate *= 0.16f;
        gripRate *= (1f - 0.28f * Math.min(1f, absV / spec.topSpeed));
        gripRate *= (1f - 0.45f * wheelspin);
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
        // Locked wheels do not steer, and weight transfer decides whether the
        // car tucks in or washes wide.
        float transfer = clamp(longAccel / 9f, -1f, 1f);
        yawRate *= (1f - 0.65f * lockup) * (1f - 0.20f * transfer);
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

        sliding = (slip > 0.16f && absV > 5f) || wheelspin > 0.15f || lockup > 0.3f;
        updateDrift(dt, slip, absV);

        impact = Math.max(0f, impact - dt * 14f);

        // ---- sit on the road, with a little suspension travel
        float target = Terrain.surfaceHeight(x, z);
        float follow = Math.min(1f, dt * 9f);
        suspensionY += (target - suspensionY) * follow;
        y = suspensionY;

        // Spinning wheels race ahead; locked ones stop dead.
        float wheelSurfaceSpeed = vLong * (1f - lockup) + wheelspin * 14f;
        wheelSpin += (wheelSurfaceSpeed / Math.max(0.1f, spec.wheelRadius)) * dt;
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

        // Accelerating lifts the nose; in a right-hand bend the body leans out
        // to the left, so the right-hand side comes up.
        float squat = clamp(longAccel * 0.009f, -0.075f, 0.075f);
        float lean = clamp(yawRate * vLong * 0.010f, -0.13f, 0.13f);

        float blend = Math.min(1f, dt * 7f);
        bodyPitch += (squat - bodyPitch) * blend;
        bodyRoll += (lean - bodyRoll) * blend;
        visualPitch = slopePitch + bodyPitch;
        visualRoll = slopeRoll + bodyRoll;

        gear = Math.max(1, Math.min(6, 1 + (int) (absV / (spec.topSpeed / 6f))));
    }

    /** A slide banks its points once the car has been straight for a moment. */
    private void updateDrift(float dt, float slip, float absV) {
        if (slip > 0.20f && absV > 7f) {
            driftNow += absV * slip * dt * 8f;
            driftGrace = 0.7f;
        } else if (driftGrace > 0f) {
            driftGrace -= dt;
        } else if (driftNow > 0f) {
            driftTotal += driftNow;
            if (driftNow > driftBest) driftBest = driftNow;
            driftNow = 0f;
        }
    }

    /**
     * Pushes the car out of any wall, tree or lamp post it has driven into.
     * Two sample circles along the body mean a glancing blow on the nose spins
     * the car rather than stopping it dead. Returns the impact speed.
     */
    public float resolveObstacles(Obstacles obstacles) {
        if (spec == null) return 0f;
        float fx = forwardX();
        float fz = forwardZ();
        float offset = spec.length * 0.26f;
        float radius = spec.width * 0.52f;

        push[0] = 0f;
        push[1] = 0f;
        float yawImpulse = 0f;
        boolean hit = false;

        for (int end = -1; end <= 1; end += 2) {
            float rx = fx * offset * end;
            float rz = fz * offset * end;
            float beforeX = push[0];
            float beforeZ = push[1];
            if (obstacles.pushOut(x + rx, z + rz, radius, push)) {
                hit = true;
                float px = push[0] - beforeX;
                float pz = push[1] - beforeZ;
                // (r x F) about +Y, which is the direction yaw increases in.
                yawImpulse += rz * px - rx * pz;
            }
        }
        if (!hit) return 0f;

        x += push[0];
        z += push[1];

        float len = (float) Math.sqrt(push[0] * push[0] + push[1] * push[1]);
        float into = 0f;
        if (len > 1e-5f) {
            float nx = push[0] / len;
            float nz = push[1] / len;
            into = -(vx * nx + vz * nz);
            if (into > 0f) {
                // Remove the speed heading into the wall, plus a little back.
                vx += nx * into * 1.22f;
                vz += nz * into * 1.22f;
            }
        }
        vx *= 0.86f;
        vz *= 0.86f;
        yaw += clamp(yawImpulse * 0.22f, -0.18f, 0.18f);

        forwardSpeed = vx * forwardX() + vz * forwardZ();
        lateralSpeed = vx * rightX() + vz * rightZ();
        if (into > impact) impact = into;
        return into;
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

        System.arraycopy(modelMatrix(), 0, wheelModel, 0, 16);
        Matrix.translateM(wheelModel, 0, sideSign * spec.track * 0.5f, spec.wheelRadius, zOff);
        if (front) {
            Matrix.rotateM(wheelModel, 0, (float) -Math.toDegrees(steerAngle), 0f, 1f, 0f);
        }
        Matrix.rotateM(wheelModel, 0, (float) Math.toDegrees(wheelSpin), 1f, 0f, 0f);
        return wheelModel;
    }

    /** Ground contact point of a wheel (0..3 = FL, FR, RL, RR), for rubber. */
    public void wheelGroundPosition(int index, float[] out) {
        float fx = forwardX(), fz = forwardZ();
        float rx = rightX(), rz = rightZ();
        float along = (index < 2 ? 1f : -1f) * spec.wheelbase * 0.5f;
        float lateral = ((index == 0 || index == 2) ? -1f : 1f) * spec.track * 0.5f;
        out[0] = x + fx * along + rx * lateral;
        out[1] = z + fz * along + rz * lateral;
    }
}
