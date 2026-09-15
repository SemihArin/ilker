package com.ilker.opendrive.game;

/**
 * The garage. Every vehicle is described purely by numbers — proportions for
 * the procedural body builder and a handful of handling figures — so adding a
 * new one never needs an art asset.
 *
 * Local axes: +X right, +Y up, +Z forward. The origin sits on the road surface
 * between the wheels.
 */
public class CarSpec {

    public final String name;

    // Body proportions, in metres.
    public final float length;
    public final float width;
    public final float sillY;        // bottom of the visible bodywork
    public final float beltY;        // top of the doors / bonnet line
    public final float roofY;        // top of the greenhouse
    public final float noseNarrow;   // 0..1, how much the nose pinches in
    public final float tailNarrow;
    public final float cabinFront;   // cabin extent forward of centre, as a fraction of half-length
    public final float cabinRear;
    public final float roofInset;    // how far the roof pulls in from the belt line
    public final float roofShiftZ;   // positive pushes the roof rearward

    public final float wheelRadius;
    public final float wheelWidth;
    public final float wheelbase;    // front-to-rear axle distance
    public final float track;        // left-to-right wheel distance

    public final boolean spoiler;
    public final boolean pickupBed;
    public final boolean roofRack;

    // Handling.
    public final float topSpeed;     // m/s
    public final float enginePower;  // drive force at peak torque in a 1:1 gear
    public final float brakePower;   // m/s^2
    public final float grip;         // lateral recovery rate
    public final float steerRate;

    public final float colorR, colorG, colorB;

    private CarSpec(String name, float length, float width, float sillY, float beltY, float roofY,
                    float noseNarrow, float tailNarrow, float cabinFront, float cabinRear,
                    float roofInset, float roofShiftZ,
                    float wheelRadius, float wheelWidth, float wheelbase, float track,
                    boolean spoiler, boolean pickupBed, boolean roofRack,
                    float topSpeed, float enginePower, float brakePower, float grip, float steerRate,
                    float colorR, float colorG, float colorB) {
        this.name = name;
        this.length = length;
        this.width = width;
        this.sillY = sillY;
        this.beltY = beltY;
        this.roofY = roofY;
        this.noseNarrow = noseNarrow;
        this.tailNarrow = tailNarrow;
        this.cabinFront = cabinFront;
        this.cabinRear = cabinRear;
        this.roofInset = roofInset;
        this.roofShiftZ = roofShiftZ;
        this.wheelRadius = wheelRadius;
        this.wheelWidth = wheelWidth;
        this.wheelbase = wheelbase;
        this.track = track;
        this.spoiler = spoiler;
        this.pickupBed = pickupBed;
        this.roofRack = roofRack;
        this.topSpeed = topSpeed;
        this.enginePower = enginePower;
        this.brakePower = brakePower;
        this.grip = grip;
        this.steerRate = steerRate;
        this.colorR = colorR;
        this.colorG = colorG;
        this.colorB = colorB;
    }

    public static final CarSpec[] GARAGE = {
            // Low, wide and fast.
            new CarSpec("SIMSEK GT", 4.35f, 1.92f, 0.30f, 0.86f, 1.20f,
                    0.66f, 0.74f, 0.16f, -0.62f, 0.26f, -0.18f,
                    0.33f, 0.26f, 2.58f, 1.60f,
                    true, false, false,
                    77f, 4.10f, 19f, 15.5f, 2.6f,
                    0.86f, 0.13f, 0.14f),

            // Tall, planted, unbothered by kerbs.
            new CarSpec("KARTAL SUV", 4.72f, 1.98f, 0.44f, 1.30f, 1.86f,
                    0.80f, 0.86f, 0.30f, -0.70f, 0.16f, -0.05f,
                    0.40f, 0.30f, 2.82f, 1.66f,
                    false, false, true,
                    58f, 2.00f, 15f, 14.0f, 2.2f,
                    0.16f, 0.32f, 0.52f),

            // Short wheelbase, flicks through junctions.
            new CarSpec("MINIK HATCH", 3.72f, 1.72f, 0.36f, 1.02f, 1.52f,
                    0.78f, 0.80f, 0.26f, -0.80f, 0.20f, -0.12f,
                    0.31f, 0.22f, 2.35f, 1.48f,
                    false, false, false,
                    47f, 1.90f, 14f, 15.0f, 3.0f,
                    0.96f, 0.72f, 0.10f),

            // Long bonnet, loose back end — the drift machine.
            new CarSpec("KAS MUSCLE", 4.92f, 2.00f, 0.32f, 0.98f, 1.40f,
                    0.74f, 0.78f, -0.02f, -0.74f, 0.24f, -0.26f,
                    0.36f, 0.31f, 2.92f, 1.70f,
                    true, false, false,
                    71f, 3.10f, 14f, 9.5f, 2.4f,
                    0.10f, 0.11f, 0.14f),

            // Open bed, heavy, surprisingly happy off the tarmac.
            new CarSpec("YUK PICKUP", 5.25f, 2.02f, 0.46f, 1.22f, 1.82f,
                    0.82f, 0.94f, 0.06f, -0.52f, 0.18f, -0.04f,
                    0.40f, 0.32f, 3.15f, 1.70f,
                    false, true, false,
                    51f, 1.75f, 13f, 12.5f, 2.0f,
                    0.90f, 0.90f, 0.88f),

            // The sensible one.
            new CarSpec("KLASIK SEDAN", 4.62f, 1.86f, 0.34f, 1.00f, 1.48f,
                    0.74f, 0.78f, 0.20f, -0.66f, 0.22f, -0.10f,
                    0.34f, 0.25f, 2.72f, 1.58f,
                    false, false, false,
                    60f, 2.20f, 15f, 14.5f, 2.4f,
                    0.24f, 0.55f, 0.36f),
    };

    /** Speedometer-friendly top speed. */
    public int topSpeedKmh() {
        return Math.round(topSpeed * 3.6f);
    }
}
