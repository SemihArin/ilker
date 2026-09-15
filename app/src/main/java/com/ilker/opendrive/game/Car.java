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
 * Longitudinally the engine drives through a real gearbox — a torque curve
 * multiplied by the gear the car happens to be in — so first gear overwhelms
 * the tyres, sixth barely pulls, and the revs the audio hears are the revs the
 * engine is actually turning.
 *
 * Vertically the body is not glued to the ground: it carries a vertical
 * velocity, so a kerb throws it into the air and gravity brings it back down
 * onto a sprung body that settles afterwards. Gravity also acts along the
 * slope, which is what finally makes hills mean something.
 */
public class Car {

    private static final float GRAVITY = 9.81f;

    /**
     * Gear ratios, as a multiplier on drive force. The top gear is chosen so
     * that full throttle at the rev limiter exactly balances aerodynamic drag
     * at the quoted top speed; the rest space out from there.
     */
    private static final float[] GEAR_RATIO = {2.90f, 2.05f, 1.55f, 1.24f, 1.02f, 0.84f};
    private static final float SHIFT_TIME = 0.16f;
    private static final float IDLE_REVS = 0.12f;
    /** Rolling resistance. Small — it is tyres on tarmac, not a handbrake. */
    private static final float ROLLING = 0.35f;

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

    /** 0..1 of the rev range; 1 is the limiter. */
    public float engineRevs = IDLE_REVS;
    /** Displayed gear, 1..6. */
    public int gear = 1;
    public boolean inReverse;
    public boolean shifting;

    /** True while all four wheels are off the ground. */
    public boolean airborne;
    public float airTime;
    /** Vertical speed of the last touchdown, for haptics and the camera. */
    public float landing;

    public float driftNow;
    public float driftBest;
    public float driftTotal;
    private float driftGrace;

    /** Speed of the last impact in m/s, decaying; drives haptics and audio. */
    public float impact;

    public float distanceTravelled;
    public float topSpeedSeen;

    private int gearIndex;
    private float shiftTimer;
    private float bodyPitch;
    private float bodyRoll;
    private float bodyY;         // sprung body height, what gets drawn
    private float bodyVelY;      // suspension travel speed
    private float wheelY;        // where the wheels are
    private float velY;          // vertical speed of the chassis
    private float lastGround;
    private float lastForwardSpeed;

    private final float[] model = new float[16];
    private final float[] wheelModel = new float[16];
    private final float[] push = new float[2];

    public void reset(CarSpec spec, float startX, float startZ, float startYaw) {
        this.spec = spec;
        this.x = startX;
        this.z = startZ;
        this.yaw = startYaw;
        float ground = Terrain.surfaceHeight(startX, startZ);
        this.wheelY = ground;
        this.bodyY = ground;
        this.y = ground;
        this.lastGround = ground;
        bodyVelY = 0f;
        velY = 0f;
        airborne = false;
        airTime = 0f;
        landing = 0f;
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
        gearIndex = 0;
        gear = 1;
        inReverse = false;
        shifting = false;
        shiftTimer = 0f;
        engineRevs = IDLE_REVS;
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

        // ---- how the ground lies under the car, needed before the engine so
        //      gravity along the slope can be part of the same step
        float halfBase = spec.wheelbase * 0.5f;
        float halfTrack = spec.track * 0.5f;
        float ahead = Terrain.surfaceHeight(x + fx * halfBase, z + fz * halfBase);
        float behind = Terrain.surfaceHeight(x - fx * halfBase, z - fz * halfBase);
        float rightH = Terrain.surfaceHeight(x + rightX * halfTrack, z + rightZ * halfTrack);
        float leftH = Terrain.surfaceHeight(x - rightX * halfTrack, z - rightZ * halfTrack);
        float slopePitch = (float) Math.atan2(ahead - behind, spec.wheelbase);   // + = nose up
        float slopeRoll = (float) Math.atan2(rightH - leftH, spec.track);        // + = right side up

        // ---- steering: less lock the faster you go, and it straightens up
        //      faster than it winds on, so a twitch does not upset the car
        float speedFrac = Math.min(1f, absV / (spec.topSpeed * 0.62f));
        float maxSteer = 0.62f - 0.46f * speedFrac;
        float targetSteer = c.steer * maxSteer;
        boolean winding = Math.abs(targetSteer) > Math.abs(steerAngle);
        float steerRate = spec.steerRate * (winding ? 3.2f : 5.6f);
        steerAngle += (targetSteer - steerAngle) * Math.min(1f, dt * steerRate);

        // ---- gearbox and engine
        updateGearbox(dt, vLong, c);

        float speedRatio = absV / spec.topSpeed;
        // Resistance has to fade out as the car stops, or it acts as static
        // friction and a parked car sits on a hill instead of rolling down it.
        float creep = Math.min(1f, absV / 1.5f);
        float resist = aeroDrag() * speedRatio * speedRatio
                + ROLLING * (0.15f + 0.85f * creep)
                + (1f - surface) * 7f * creep;
        float drive = 0f;

        if (airborne) {
            // Wheels in the air do nothing at all, either way.
            resist = aeroDrag() * 0.76f * speedRatio * speedRatio;
        } else {
            if (inReverse) {
                // With only two pedals the brake has to be the reverse
                // throttle, and the throttle has to be the brake — which is
                // what every player expects from a phone driving game.
                drive = -c.brake * spec.enginePower * 0.5f * surface * GEAR_RATIO[0] * 0.5f;
                if (c.throttle > 0.01f) {
                    if (vLong < -0.6f) {
                        resist += c.throttle * spec.brakePower * 0.8f;
                    } else {
                        inReverse = false;
                    }
                }
                if (c.brake < 0.05f) resist += engineBraking(GEAR_RATIO[0], creep);
            } else {
                if (!shifting) {
                    drive = c.throttle * spec.enginePower * torqueAt(engineRevs)
                            * GEAR_RATIO[gearIndex] * (0.45f + 0.55f * surface);
                }
                if (c.throttle < 0.05f) {
                    resist += engineBraking(GEAR_RATIO[gearIndex], creep);
                }
            }

            // Tyres can only lay down so much. First gear beats them easily,
            // which is where wheelspin comes from.
            wheelspin = 0f;
            float pull = Math.abs(drive);
            if (pull > 0f) {
                // What the tyres can take. Low enough that a powerful car in
                // first gear overwhelms them and a small hatchback does not.
                float traction = ((1.6f + 4.0f * surface) + 5.0f * surface * Math.min(1f, absV / 18f))
                        * (spec.grip / 13f);
                if (c.handbrake) traction *= 0.35f;
                if (pull > traction) {
                    wheelspin = Math.min(1f, (pull - traction) / pull);
                    float capped = traction + (pull - traction) * 0.15f;
                    drive = Math.signum(drive) * capped;
                }
            }

            lockup = 0f;
            if (c.brake > 0.01f && !inReverse) {
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
                    // Stopped with the brake still down: select reverse.
                    inReverse = true;
                }
            }
            if (c.handbrake) {
                resist += 6f;
            }

            // Gravity down the slope. Uphill drags, downhill pulls you along,
            // and a parked car rolls back unless the handbrake is on.
            vLong -= GRAVITY * (float) Math.sin(slopePitch) * dt;
        }

        vLong += drive * dt;
        float scrub = resist * dt;
        if (vLong > 0f) {
            vLong = Math.max(0f, vLong - scrub);
        } else if (vLong < 0f) {
            vLong = Math.min(0f, vLong + scrub);
        }
        if (c.handbrake && !airborne && Math.abs(vLong) < 1.2f && c.throttle < 0.05f) {
            vLong = 0f;   // the handbrake holds the car on a hill
        }
        if (inReverse && vLong > 0.4f) {
            inReverse = false;
        }

        float maxForward = spec.topSpeed * (0.58f + 0.42f * surface);
        float maxReverse = spec.topSpeed * 0.15f;
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
        if (airborne) gripRate = 0f;
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
        if (airborne) yawRate *= 0.25f;   // a little air steering, no more
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
        landing = Math.max(0f, landing - dt * 20f);

        updateVertical(dt);

        // Spinning wheels race ahead; locked ones stop dead.
        float wheelSurfaceSpeed = vLong * (1f - lockup) + wheelspin * 14f;
        wheelSpin += (wheelSurfaceSpeed / Math.max(0.1f, spec.wheelRadius)) * dt;
        if (wheelSpin > Math.PI * 2) wheelSpin -= (float) (Math.PI * 2);
        if (wheelSpin < 0) wheelSpin += (float) (Math.PI * 2);

        // ---- attitude: accelerating lifts the nose; in a right-hand bend the
        //      body leans out to the left, so the right-hand side comes up.
        float squat = clamp(longAccel * 0.009f, -0.075f, 0.075f);
        float lean = clamp(yawRate * vLong * 0.010f, -0.13f, 0.13f);

        float blend = Math.min(1f, dt * 7f);
        bodyPitch += (squat - bodyPitch) * blend;
        bodyRoll += (lean - bodyRoll) * blend;

        if (airborne) {
            // In the air the car follows its own trajectory, nose up as it
            // climbs and down as it falls.
            float flightPitch = (float) Math.atan2(velY, Math.max(4f, Math.abs(vLong)));
            visualPitch += (flightPitch - visualPitch) * Math.min(1f, dt * 3.5f);
            visualRoll += (bodyRoll - visualRoll) * Math.min(1f, dt * 2.5f);
        } else {
            visualPitch = slopePitch + bodyPitch;
            visualRoll = slopeRoll + bodyRoll;
        }
    }

    /**
     * Engine braking: strong in a low gear at high revs, almost nothing at
     * idle. Tying it to revs rather than to the gear alone is what lets a car
     * roll away down a slope instead of being pinned by its own drivetrain.
     */
    private float engineBraking(float ratio, float creep) {
        return 0.9f * ratio * engineRevs * creep;
    }

    /**
     * Aerodynamic drag, scaled so that every car actually reaches the top
     * speed on its spec sheet. A fixed coefficient would leave the low-powered
     * ones short: rolling resistance is a fifth of the pickup's budget and
     * barely a tenth of the GT's.
     */
    private float aeroDrag() {
        float atLimiter = spec.enginePower * torqueAt(1f) * GEAR_RATIO[GEAR_RATIO.length - 1];
        return Math.max(0.2f, atLimiter * 0.90f - ROLLING);
    }

    /**
     * Torque as a fraction of peak, against a fraction of the rev range.
     * Soft off idle, strongest around two thirds up, tailing off into the
     * limiter — which is what gives each gear a shape you can hear.
     */
    private static float torqueAt(float revs) {
        float t = clamp(revs, 0f, 1.08f);
        return (0.55f + 0.75f * t - 0.62f * t * t) / 0.777f;
    }

    private void updateGearbox(float dt, float vLong, Controls c) {
        if (shiftTimer > 0f) {
            shiftTimer -= dt;
            if (shiftTimer <= 0f) shifting = false;
        }

        // Revs follow the driven wheels, so spinning them up flares the engine.
        float wheelSpeed = Math.abs(vLong) + wheelspin * 12f;
        float ratioSpan = GEAR_RATIO[inReverse ? 0 : gearIndex] / GEAR_RATIO[GEAR_RATIO.length - 1];
        float target = (wheelSpeed / spec.topSpeed) * ratioSpan;
        if (target < IDLE_REVS) {
            target = IDLE_REVS + c.throttle * 0.18f;   // blipping in neutral
        }
        engineRevs += (Math.min(target, 1.06f) - engineRevs) * Math.min(1f, dt * 9f);

        if (inReverse || shifting) {
            gear = 1;
            return;
        }

        if (engineRevs > 0.97f && gearIndex < GEAR_RATIO.length - 1) {
            gearIndex++;
            shifting = true;
            shiftTimer = SHIFT_TIME;
        } else if (engineRevs < 0.42f && gearIndex > 0) {
            gearIndex--;
            shifting = true;
            shiftTimer = SHIFT_TIME * 0.6f;
        }
        gear = gearIndex + 1;
    }

    /**
     * Vertical motion. The chassis holds a vertical speed rather than being
     * pinned to the surface, so a ramp throws it and a kerb drops it; the body
     * then rides on a spring that settles after the landing.
     */
    private void updateVertical(float dt) {
        float ground = Terrain.surfaceHeight(x, z);

        if (airborne) {
            velY -= GRAVITY * dt;
            wheelY += velY * dt;
            airTime += dt;
            if (wheelY <= ground || airTime > 4f) {
                landing = Math.max(landing, Math.max(0f, -velY));
                wheelY = ground;
                airborne = false;
                airTime = 0f;
                bodyVelY -= landing * 0.5f;          // suspension compresses
                impact = Math.max(impact, landing * 0.45f);
                // A heavy landing scrubs speed off.
                float loss = Math.min(0.35f, landing * 0.012f);
                vx *= (1f - loss);
                vz *= (1f - loss);
                forwardSpeed = vx * forwardX() + vz * forwardZ();
                velY = 0f;
            }
        } else {
            // The vertical speed staying on the surface would imply.
            float glued = (ground - lastGround) / dt;
            if (glued < velY - GRAVITY * dt - 1.2f) {
                // The ground fell away faster than gravity can follow, or a
                // ramp is still throwing us upward: we are flying.
                airborne = true;
                airTime = 0f;
            } else {
                velY = glued;
                wheelY = ground;
            }
        }
        lastGround = ground;

        // Sprung body: stiff enough to stay planted, loose enough to settle.
        float error = wheelY - bodyY;
        bodyVelY += (error * 190f - bodyVelY * 21f) * dt;
        bodyY += bodyVelY * dt;
        if (bodyY < wheelY - 0.35f) {
            bodyY = wheelY - 0.35f;
            bodyVelY = 0f;
        }
        y = bodyY;
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

    /** How far the body is riding above its wheels, for the camera. */
    public float suspensionTravel() {
        return bodyY - wheelY;
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
        // The wheels stay with the ground while the body rides the spring.
        Matrix.translateM(wheelModel, 0, sideSign * spec.track * 0.5f,
                spec.wheelRadius - suspensionTravel(), zOff);
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
