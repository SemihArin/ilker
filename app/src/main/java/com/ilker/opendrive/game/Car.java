package com.ilker.opendrive.game;

import android.opengl.Matrix;

import com.ilker.opendrive.world.Obstacles;
import com.ilker.opendrive.world.Terrain;

/**
 * Arcade vehicle model: a two-axle tyre model with real yaw inertia.
 *
 * Cornering is a bicycle model driven by axle load. Each axle carries a share
 * of the car's weight, load moves between them under acceleration and braking,
 * and what an axle can do sideways is that load times the tyre's friction. Its
 * slip angle feeds a curve that rises to a peak and eases off after it, and the
 * difference between the two axles' forces is a torque about the car's centre,
 * so yaw rate is a state with inertia rather than a function of the wheel.
 *
 * Deriving grip from load rather than from a pair of hand-set numbers is what
 * makes the car behave: a car is neutral when both axles run the same tyre,
 * because the load that decides the force also decides the stiffness that
 * resists it. Understeer is then one small deliberate bias, not an accident of
 * two figures somebody tuned.
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
    /** Sub-steps for the lateral solver; the tyres are stiff at low speed. */
    private static final int LATERAL_STEPS = 4;
    /** A ceiling on rotation. Loose enough that ordinary driving never meets it. */
    private static final float YAW_MAX = 3.4f;

    // ---- the tyre. One curve, shared by both axles, scaled by the load each
    //      of them happens to be carrying.

    /** Magic-formula shape: how far the curve falls away past its peak. */
    private static final float TYRE_SHAPE = 1.45f;
    /** Magic-formula stiffness: how sharply it climbs to that peak. */
    private static final float TYRE_STIFF = 11.5f;
    /** Where the peak lands, radians. Follows from the two above. */
    private static final float PEAK_SLIP = 0.163f;

    /**
     * How much steering is offered past the angle a limit-speed corner needs.
     * Enough that full input reaches the tyre's peak, little enough that it
     * does not sail past it into understeer.
     */
    private static final float STEER_MARGIN = 0.45f;

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
    /** Slip angle at each axle. */
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
        // reacts to speed (drag, rolling resistance, traction) has to see it
        // that way or a car turned fully sideways meets no resistance at all
        // and slides for ever.
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

        // ---- steering. What the player is asking for with the wheel is a
        //      corner, not an angle, so that is what the input means here: at
        //      half deflection the car pulls half the cornering force it has,
        //      at any speed. Mapping the thumb straight onto a steering angle
        //      instead makes the middle of the travel useless — half a turn
        //      already asks for more than the tyres have, so the car corners
        //      the same from half input to full and the wheel feels like a
        //      switch.
        //
        //      A steady turn at a given force needs an angle of about
        //      (force * wheelbase / speed squared), which is most of the lock
        //      when parking and a couple of degrees on a motorway. The tyres
        //      also need some slip of their own to make that force, and they
        //      need most of it only near the limit — hence the squared term.
        float demand = Math.abs(c.steer);
        float capability = spec.grip * surface * GRAVITY;
        float geometric = demand * capability * spec.wheelbase
                / Math.max(absV * absV, 1f);
        float slipNeeded = PEAK_SLIP * STEER_MARGIN * demand * demand;
        float maxSteer = Math.min(spec.steerLock, geometric + slipNeeded);

        // If the car does get out of shape, the lock comes back so it can be
        // steered straight again.
        float recover = spec.steerLock * 0.8f * Math.min(1f, Math.abs(slipAngle) / 0.5f);
        maxSteer = Math.max(maxSteer, Math.min(spec.steerLock, demand * recover));

        float targetSteer = Math.signum(c.steer) * maxSteer;

        // Winding lock on is slower than letting it come back, the way a wheel
        // is heavier into a corner than out of one.
        boolean winding = Math.abs(targetSteer) > Math.abs(steerAngle);
        float steerSpeed = spec.steerRate * (winding ? 3.4f : 5.2f);
        steerAngle += (targetSteer - steerAngle) * Math.min(1f, dt * steerSpeed);
        effectiveSteer = clamp(steerAngle, -spec.steerLock, spec.steerLock);

        // ---- gearbox and engine
        updateGearbox(dt, vLong, c);

        float speedRatio = absV / spec.topSpeed;
        // Resistance has to fade out as the car stops, or it acts as static
        // friction and a parked car sits on a hill instead of rolling down it.
        float creep = Math.min(1f, absV / 1.5f);
        // What the rear tyres can put down lengthways. The lateral solver needs
        // the same number for its friction circle, so it is worked out here.
        float traction = 8f;
        // Braking effort kept apart from the rest of the resistance, because
        // load transfer follows the pedals, not the air.
        float brakeAccel = 0f;
        // Everything that slows the car through its tyres is gathered here and
        // capped by what the tyres hold, because brakes, engine braking and
        // rolling drag all pass through the same contact patches. Air is the
        // one that does not, so it is added afterwards and is not capped.
        float aero = aeroDrag() * speedRatio * speedRatio;
        float resist = ROLLING * (0.15f + 0.85f * creep)
                + (1f - surface) * 7f * creep;
        float drive = 0f;

        if (airborne) {
            // Wheels in the air do nothing at all, either way.
            resist = 0f;
            aero = aeroDrag() * 0.76f * speedRatio * speedRatio;
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
            {
                // What the tyres can take. Low enough that a powerful car in
                // first gear overwhelms them and a small hatchback does not.
                // What the driven tyres can put down. The drive layout is not
                // modelled, so the driven axle is taken as the rear with the
                // squat that hard acceleration puts on it.
                float driveLoad = clamp((1f - spec.frontWeight) + 0.14f, 0.45f, 0.70f);
                traction = spec.grip * surface * GRAVITY * driveLoad;
                // The handbrake locks the rear, so there is nothing there to
                // drive the car forward with.
                if (c.handbrake) traction *= 0.35f;
                if (pull > traction && pull > 0f) {
                    wheelspin = Math.min(1f, (pull - traction) / pull);
                    float capped = traction + (pull - traction) * 0.15f;
                    drive = Math.signum(drive) * capped;
                }
            }

            lockup = 0f;
            if (c.brake > 0.01f && !inReverse) {
                if (vLong > 0.6f) {
                    float pedal = c.brake * spec.brakePower;
                    // Brakes cannot beat the tyres. Past this the wheels lock,
                    // and a locked tyre slides — which stops the car slightly
                    // less well than one still turning, never better.
                    float maxBrake = spec.grip * GRAVITY * surface;
                    if (pedal > maxBrake) {
                        lockup = Math.min(1f, (pedal - maxBrake) / pedal);
                        pedal = maxBrake * (1f - 0.12f * lockup);
                    }
                    resist += pedal;
                    brakeAccel = pedal;
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

        if (!airborne) {
            resist = Math.min(resist, spec.grip * GRAVITY * surface);
        }
        resist += aero;

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

        // ---- lateral dynamics: a bicycle model fed by axle load.
        float axleFront = spec.wheelbase * (1f - spec.frontWeight);
        float axleRear = spec.wheelbase * spec.frontWeight;
        // Yaw inertia as a radius of gyration, which for a car is a little
        // under half the wheelbase.
        float izz = 0.22f * spec.wheelbase * spec.wheelbase;

        // Weight moves because the driver asked it to. Deriving this from net
        // acceleration instead makes aerodynamic drag alone pitch the car onto
        // its nose at motorway speed, unloading the rear until the mildest
        // steering input spins it — which is not a corner anyone can take.
        float transfer = clamp((drive - brakeAccel) / GRAVITY
                * (spec.cgHeight / spec.wheelbase), -0.30f, 0.30f);
        float loadF = clamp(spec.frontWeight - transfer, 0.14f, 0.86f);
        float loadR = 1f - loadF;

        // What each axle can do sideways: the load it carries times what the
        // tyre can hold. The balance figure is the car's one deliberate bias —
        // a front that lets go a little before the rear, so it runs wide
        // rather than swapping ends.
        float muF = (spec.grip - spec.balance) * surface;
        float muR = (spec.grip + spec.balance) * surface;

        // Friction circle: grip spent driving the car forward is not available
        // to turn it. Bounded well short of zero, because a tyre at its
        // longitudinal limit still corners, and a model that says otherwise
        // makes full throttle mid-corner a spin.
        float longShare = Math.min(1f, Math.abs(drive) / Math.max(1f, traction));
        float circle = (float) Math.sqrt(Math.max(0.72f, 1f - longShare * longShare));
        float spinLoss = 1f - 0.12f * wheelspin;

        float peakF = muF * loadF * GRAVITY * circle * spinLoss;
        float peakR = muR * loadR * GRAVITY * circle * spinLoss;
        if (lockup > 0f) {
            peakF *= 1f - 0.60f * lockup;
            peakR *= 1f - 0.60f * lockup;
        }
        if (airborne) {
            peakF = 0f;
            peakR = 0f;
        }

        // Below walking pace the bicycle model divides by almost nothing, so
        // parking falls back on plain steering geometry.
        float dynamic = clamp((absV - 1.0f) / 3.5f, 0f, 1f);

        float h = dt / LATERAL_STEPS;
        for (int step = 0; step < LATERAL_STEPS; step++) {
            float uRef = Math.max(Math.abs(vLong), 2.0f);
            slipFront = (float) Math.atan2(vLat + yawRate * axleFront, uRef) - effectiveSteer;
            slipRear = (float) Math.atan2(vLat - yawRate * axleRear, uRef);

            float fyF = -tyreForce(slipFront, peakF) * dynamic;
            float fyR = -tyreForce(slipRear, peakR) * dynamic;

            vLat += (fyF + fyR) * h;
            yawRate += ((axleFront * fyF - axleRear * fyR) / izz) * h;

            float kinematic = (vLong / spec.wheelbase) * (float) Math.tan(effectiveSteer);
            yawRate += (kinematic - yawRate) * (1f - dynamic) * Math.min(1f, h * 14f);

            if (yawRate > YAW_MAX) yawRate = YAW_MAX;
            if (yawRate < -YAW_MAX) yawRate = -YAW_MAX;

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
    /**
     * One tyre curve for both axles: it climbs to a peak at {@link #PEAK_SLIP}
     * and eases off after it, so a tyre asked for more than it has gives back
     * less, not more. The peak passed in is what that axle's load allows.
     */
    private static float tyreForce(float slip, float peak) {
        return peak * (float) Math.sin(TYRE_SHAPE * Math.atan(TYRE_STIFF * slip));
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
