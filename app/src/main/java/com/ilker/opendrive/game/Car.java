package com.ilker.opendrive.game;

import android.opengl.Matrix;

import com.ilker.opendrive.world.Obstacles;
import com.ilker.opendrive.world.Terrain;

/**
 * Arcade vehicle model, built around drifting.
 *
 * Cornering is a two-axle model: each axle gets its own slip angle, its own
 * lateral force from a tyre curve that peaks and then eases off, and the
 * difference between the two is a torque about the car's centre. The yaw rate
 * is therefore a state with inertia, not a function of the steering angle —
 * which is what lets the back step out, carry round, and be caught on opposite
 * lock. A car whose rear tyres give up before its fronts oversteers; loading
 * the rear axle with power steals the grip it was using to hold the line.
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
    /** How much opposite lock the car winds on by itself, 0..1. */
    private static final float COUNTER_STEER_HELP = 0.55f;
    /** Sub-steps for the lateral solver; the tyres are stiff at low speed. */
    private static final int LATERAL_STEPS = 4;
    /** How fast the car can rotate, rad/s. Low enough to leave a thumb time. */
    private static final float YAW_MAX = 2.35f;
    /** Rotation decays on its own, so a slide settles instead of running away. */
    private static final float YAW_DAMPING = 0.20f;

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
    /** Slip angle at each axle; the rear one is what a drift is made of. */
    public float slipFront;
    public float slipRear;
    /** Rate of rotation, positive turning right. A real state, with inertia. */
    public float yawRate;
    /** Steering actually at the wheels, including the counter-steer help. */
    public float effectiveSteer;
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
    /** Builds the longer a slide is held; resets when it ends. */
    public int driftMultiplier = 1;
    /** Points from the slide just banked, for the interface to flash. */
    public float driftBanked;
    public float driftBankedTimer;
    private float driftHeld;
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
        driftHeld = 0f;
        driftMultiplier = 1;
        driftBanked = 0f;
        driftBankedTimer = 0f;
        yawRate = 0f;
        slipFront = 0f;
        slipRear = 0f;
        effectiveSteer = 0f;
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
        // How fast the car is travelling — not how fast it is travelling
        // forwards. Sideways at 80 km/h is still 80 km/h, and everything that
        // reacts to speed (drag, rolling resistance, traction, the drift
        // scoring) has to see it that way or a car turned fully sideways meets
        // no resistance at all and slides for ever.
        float absV = (float) Math.sqrt(vLong * vLong + vLat * vLat);

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

        // ---- steering. Plenty of lock is kept at speed: a drift is held on
        //      opposite lock, and a car that runs out of it just spins.
        float speedFrac = Math.min(1f, absV / (spec.topSpeed * 0.62f));
        float maxSteer = spec.steerLock * (1f - 0.30f * speedFrac);
        float targetSteer = c.steer * maxSteer;

        // Correcting a slide has to be quicker than provoking one, and a thumb
        // cannot move as fast as a wheel — so the car winds on opposite lock
        // of its own accord, backing off as the player takes over.
        float catchDir = Math.signum(slipAngle);
        boolean correcting = catchDir != 0f && Math.signum(c.steer) == catchDir;
        boolean winding = Math.abs(targetSteer) > Math.abs(steerAngle);
        float steerSpeed = spec.steerRate * (correcting ? 6.2f : (winding ? 3.4f : 5.2f));
        steerAngle += (targetSteer - steerAngle) * Math.min(1f, dt * steerSpeed);

        float assistGain = COUNTER_STEER_HELP * (1f - Math.min(1f, Math.abs(c.steer) * 1.15f));
        float assist = clamp(slipAngle * assistGain, -0.40f, 0.40f);
        effectiveSteer = clamp(steerAngle + assist, -spec.steerLock, spec.steerLock);

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
                        * (spec.gripRear / 4.6f);
                // A tyre already sliding sideways has far less left to put the
                // power down with. This closes the loop that makes a drift
                // something you hold on the throttle rather than something
                // that happens to you: sliding spins the rear up, spinning
                // rear keeps it sliding, and lifting ends it.
                traction *= 1f - 0.55f * Math.min(1f, Math.abs(slipAngle) / 0.55f);
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

        // Drag and rolling resistance pull against the direction of travel, so
        // they have to be taken off both components. Scaling them together
        // also keeps the old guarantee that resistance can stop the car but
        // never drag it backwards.
        float travelling = (float) Math.sqrt(vLong * vLong + vLat * vLat);
        if (travelling > 1e-4f) {
            float take = Math.min(1f, (resist * dt) / travelling);
            vLong -= vLong * take;
            vLat -= vLat * take;
        }
        if (c.handbrake && !airborne && travelling < 1.2f && c.throttle < 0.05f) {
            vLong = 0f;   // the handbrake holds the car on a hill
            vLat = 0f;
            yawRate = 0f;
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

        // ---- lateral dynamics: two axles, a tyre curve, real yaw inertia.
        //      The yaw rate is a state now rather than a function of the
        //      steering angle, so the car carries rotation and has to be
        //      caught — which is the whole difference between a car that
        //      drifts and a car that merely slides.
        float axleFront = spec.wheelbase * (1f - spec.frontWeight);
        float axleRear = spec.wheelbase * spec.frontWeight;
        float izz = spec.wheelbase * spec.wheelbase * 0.30f;

        float loadShift = clamp(longAccel / 8f, -1f, 1f);
        float gripF = spec.gripFront * surface * (1f - 0.24f * loadShift);
        float gripR = spec.gripRear * surface * (1f + 0.24f * loadShift);

        // Friction circle: whatever the rear axle spends driving, it cannot
        // spend gripping. This is where power oversteer comes from.
        float longShare = Math.min(1f, Math.abs(drive) / Math.max(0.5f, spec.gripRear * surface));
        gripR *= (float) Math.sqrt(Math.max(0.06f, 1f - longShare * longShare));
        // Spinning tyres have almost nothing left sideways. This is the lever
        // the player actually holds a drift with: the throttle.
        gripR *= 1f - 0.55f * wheelspin;
        if (c.handbrake) gripR *= 0.15f;
        if (lockup > 0f) {
            gripF *= 1f - 0.60f * lockup;
            gripR *= 1f - 0.60f * lockup;
        }
        if (airborne) {
            gripF = 0f;
            gripR = 0f;
        }

        // Crawling and reverse fall back to steering geometry, so parking stays
        // predictable and the solver never divides by nothing.
        float forwardness = clamp((absV - 1.5f) / 6f, 0f, 1f);

        float h = dt / LATERAL_STEPS;
        for (int step = 0; step < LATERAL_STEPS; step++) {
            float uRef = Math.max(Math.abs(vLong), 2.4f);
            slipFront = (float) Math.atan2(vLat + yawRate * axleFront, uRef) - effectiveSteer;
            slipRear = (float) Math.atan2(vLat - yawRate * axleRear, uRef);

            float fyF = -tyreForce(slipFront, gripF) * forwardness;
            float fyR = -tyreForce(slipRear, gripR) * forwardness;

            vLat += (fyF + fyR) * h;
            yawRate += ((axleFront * fyF - axleRear * fyR) / izz) * h;

            float kinematic = (vLong / spec.wheelbase) * (float) Math.tan(effectiveSteer);
            yawRate += (kinematic - yawRate) * (1f - forwardness) * Math.min(1f, h * 14f);

            // Rotation bleeds off on its own. Without this the car spins up to
            // the limit and stays there, which on a phone is unrecoverable:
            // by the time a thumb has moved, the car is backwards.
            yawRate -= yawRate * YAW_DAMPING * h;
            if (yawRate > YAW_MAX) yawRate = YAW_MAX;
            if (yawRate < -YAW_MAX) yawRate = -YAW_MAX;

            // Turning the body also turns the frame the velocity is measured
            // in, and that rotation is what carries the car round the corner.
            float turn = yawRate * h;
            float nu = vLong + vLat * turn;
            float nv = vLat - vLong * turn;
            vLong = nu;
            vLat = nv;
            yaw -= turn;
        }
        if (yaw > Math.PI * 2) yaw -= (float) (Math.PI * 2);
        if (yaw < 0) yaw += (float) (Math.PI * 2);

        if (Math.abs(vLat) > spec.topSpeed * 0.85f) {
            vLat = Math.signum(vLat) * spec.topSpeed * 0.85f;
        }
        slipAngle = absV > 1.2f
                ? (float) Math.atan2(vLat, Math.max(Math.abs(vLong), 0.7f))
                : 0f;

        // Recompose the world velocity onto the heading the step ended on.
        float endFx = (float) Math.sin(yaw);
        float endFz = (float) Math.cos(yaw);
        vx = endFx * vLong - (float) Math.cos(yaw) * vLat;
        vz = endFz * vLong + (float) Math.sin(yaw) * vLat;

        float dx = vx * dt;
        float dz = vz * dt;
        x += dx;
        z += dz;
        distanceTravelled += (float) Math.sqrt(dx * dx + dz * dz);

        forwardSpeed = vLong;
        lateralSpeed = vLat;
        if (speedKmh() > topSpeedSeen) topSpeedSeen = speedKmh();

        float slip = Math.abs(slipAngle);
        float travelSpeed = (float) Math.sqrt(vLong * vLong + vLat * vLat);
        sliding = (slip > 0.16f && travelSpeed > 5f) || wheelspin > 0.15f || lockup > 0.3f;
        updateDrift(dt, slip, travelSpeed);

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
     * Lateral force from one axle against its slip angle: a magic-formula
     * shape that climbs to a peak around ten degrees and then eases off
     * instead of collapsing. The gentle tail is what makes a big slip angle
     * something a driver can hold rather than a cliff to fall off.
     */
    private static float tyreForce(float slip, float peak) {
        return peak * (float) Math.sin(1.55 * Math.atan(9.0 * slip));
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

    /**
     * Drift scoring: angle times speed, multiplied by how long the slide has
     * been held, banked when the car comes straight again — and lost entirely
     * if it hits something, which is what makes a long one worth holding.
     */
    private void updateDrift(float dt, float slip, float absV) {
        driftBankedTimer = Math.max(0f, driftBankedTimer - dt);

        boolean drifting = slip > 0.21f && absV > 6f && !airborne;
        if (drifting) {
            driftHeld += dt;
            float degrees = slip * 57.2958f;
            driftNow += degrees * (absV * 3.6f) * 0.016f * driftMultiplier * dt;
            driftGrace = 0.9f;
        } else if (driftGrace > 0f) {
            driftGrace -= dt;
            // Swapping from one slide to the next takes the car through
            // straight ahead. That transition is the skilful part of a run,
            // so the clock keeps going: breaking the chain there would punish
            // exactly the thing worth rewarding.
            driftHeld += dt;
        } else if (driftNow > 0f) {
            bankDrift();
        }
        driftMultiplier = Math.min(5, 1 + (int) (driftHeld / 2.0f));
    }

    private void bankDrift() {
        driftTotal += driftNow;
        if (driftNow > driftBest) driftBest = driftNow;
        driftBanked = driftNow;
        driftBankedTimer = 2.4f;
        driftNow = 0f;
        driftHeld = 0f;
        driftMultiplier = 1;
    }

    /** A hard enough knock loses whatever the current slide had earned. */
    public void loseDrift() {
        driftNow = 0f;
        driftHeld = 0f;
        driftGrace = 0f;
        driftMultiplier = 1;
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
        yawRate *= 0.5f;
        if (into > impact) impact = into;
        if (into > 3f) loseDrift();
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
