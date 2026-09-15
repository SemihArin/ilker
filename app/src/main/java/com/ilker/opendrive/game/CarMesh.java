package com.ilker.opendrive.game;

import com.ilker.opendrive.gl.MeshBuilder;

/**
 * Builds a vehicle body out of two stacked tapered solids plus trim. Nothing
 * is loaded from disk — every car in the game comes out of these numbers.
 */
public final class CarMesh {

    private CarMesh() {
    }

    /** Plan-view silhouette: a chamfered octagon that reads as a car from above. */
    private static float[] plan(float halfWidth, float halfLength,
                                float noseNarrow, float tailNarrow, float shrink) {
        float hw = halfWidth * shrink;
        float hl = halfLength * shrink;
        return new float[]{
                -hw * noseNarrow, hl,
                hw * noseNarrow, hl,
                hw, hl * 0.52f,
                hw, -hl * 0.58f,
                hw * tailNarrow, -hl,
                -hw * tailNarrow, -hl,
                -hw, -hl * 0.58f,
                -hw, hl * 0.52f
        };
    }

    private static float[] rect(float x0, float z0, float x1, float z1) {
        return new float[]{x0, z0, x1, z0, x1, z1, x0, z1};
    }

    public static MeshBuilder buildBody(CarSpec spec, float r, float g, float b) {
        MeshBuilder mb = new MeshBuilder();

        float hw = spec.width * 0.5f;
        float hl = spec.length * 0.5f;
        float dark = 0.10f;

        // --- fake contact shadow, so the car never looks like it is hovering
        mb.flatQuad(
                -hw * 1.06f, 0.045f, -hl * 1.02f,
                hw * 1.06f, 0.045f, -hl * 1.02f,
                hw * 1.06f, 0.045f, hl * 1.02f,
                -hw * 1.06f, 0.045f, hl * 1.02f,
                0.09f, 0.10f, 0.11f, 0f);

        // --- sills and main bodywork
        float[] lower = plan(hw, hl, spec.noseNarrow, spec.tailNarrow, 0.90f);
        float[] belt = plan(hw, hl, spec.noseNarrow, spec.tailNarrow, 1.0f);
        mb.prism(lower, spec.sillY * 0.55f, lower, spec.sillY, dark, dark, dark + 0.01f, 0f, false, false);
        mb.prism(lower, spec.sillY, belt, spec.beltY, r, g, b, 0f, true, true);

        // --- greenhouse
        float zFront = spec.cabinFront * hl;
        float zRear = spec.cabinRear * hl;
        float cabinLen = zFront - zRear;
        if (cabinLen > 0.4f) {
            float rise = Math.max(0.05f, spec.roofY - spec.beltY);
            float windscreen = Math.min(cabinLen * 0.34f, rise * 1.5f);
            float backlight = Math.min(cabinLen * 0.22f, rise * 0.9f);
            float roofFront = zFront - windscreen + spec.roofShiftZ;
            float roofRear = zRear + backlight + spec.roofShiftZ;
            if (roofFront - roofRear < 0.45f) {
                float mid = (roofFront + roofRear) * 0.5f;
                roofFront = mid + 0.225f;
                roofRear = mid - 0.225f;
            }

            float cw = hw * 0.93f;
            float rw = cw - spec.roofInset;
            if (rw < cw * 0.45f) rw = cw * 0.45f;

            float[] cabinBase = rect(-cw, zRear, cw, zFront);
            float[] roofPlan = rect(-rw, roofRear, rw, roofFront);

            // Glass is dark and slightly blue; it picks up the sky term in the shader.
            mb.prism(cabinBase, spec.beltY, roofPlan, spec.roofY,
                    0.13f, 0.16f, 0.21f, 0f, false, false);
            // Body-coloured roof panel on top of the glass.
            mb.prism(roofPlan, spec.roofY - 0.06f, roofPlan, spec.roofY,
                    r * 0.95f, g * 0.95f, b * 0.95f, 0f, false, true);

            // Wing mirrors.
            for (int s = -1; s <= 1; s += 2) {
                mb.box(s * (hw + 0.10f), spec.beltY + 0.06f, zFront - 0.12f,
                        0.20f, 0.10f, 0.26f, r * 0.9f, g * 0.9f, b * 0.9f, 0f);
            }

            if (spec.roofRack) {
                for (int s = -1; s <= 1; s += 2) {
                    mb.box(s * rw * 0.72f, spec.roofY + 0.06f, (roofFront + roofRear) * 0.5f,
                            0.07f, 0.07f, (roofFront - roofRear) * 0.86f,
                            0.22f, 0.23f, 0.25f, 0f);
                }
            }
        }

        // --- open load bed
        if (spec.pickupBed) {
            float bedFront = zRear + 0.05f;
            float bedRear = -hl * 0.97f;
            float bw = hw * 0.95f;
            float wallH = 0.46f;
            float floorY = spec.beltY - 0.30f;
            mb.box(0f, floorY, (bedFront + bedRear) * 0.5f,
                    bw * 2f, 0.08f, bedFront - bedRear, dark + 0.06f, dark + 0.06f, dark + 0.07f, 0f);
            for (int s = -1; s <= 1; s += 2) {
                mb.box(s * bw, floorY + wallH * 0.5f, (bedFront + bedRear) * 0.5f,
                        0.09f, wallH, bedFront - bedRear, r, g, b, 0f);
            }
            mb.box(0f, floorY + wallH * 0.5f, bedRear, bw * 2f, wallH, 0.09f, r, g, b, 0f);
        }

        // --- lamp housings. The lit lenses live in separate meshes so the
        //     brake lights can come on without rebuilding the whole car.
        float lightY = lampHeight(spec);
        for (int s = -1; s <= 1; s += 2) {
            mb.box(s * hw * spec.noseNarrow * 0.62f, lightY, hl * 0.965f,
                    hw * spec.noseNarrow * 0.52f, 0.15f, 0.10f,
                    0.60f, 0.62f, 0.65f, 0f);
            mb.box(s * hw * spec.tailNarrow * 0.64f, lightY + 0.04f, -hl * 0.965f,
                    hw * spec.tailNarrow * 0.50f, 0.13f, 0.09f,
                    0.33f, 0.08f, 0.08f, 0f);
        }

        // --- grille and bumpers
        mb.box(0f, lightY - 0.14f, hl * 0.97f, hw * spec.noseNarrow * 1.05f, 0.16f, 0.10f,
                dark, dark, dark + 0.01f, 0f);
        mb.box(0f, spec.sillY + 0.10f, hl * 0.95f, hw * 1.55f, 0.20f, 0.14f,
                0.17f, 0.17f, 0.18f, 0f);
        mb.box(0f, spec.sillY + 0.10f, -hl * 0.95f, hw * 1.55f, 0.20f, 0.14f,
                0.17f, 0.17f, 0.18f, 0f);

        // --- exhaust
        mb.push();
        mb.translate(hw * 0.45f, spec.sillY + 0.06f, -hl * 0.99f);
        mb.rotateX(90f);
        mb.cylinder(0f, 0f, 0f, 0.055f, 0.065f, 0.22f, 6, 0.24f, 0.25f, 0.26f, 0f);
        mb.pop();

        // --- rear wing
        if (spec.spoiler) {
            float wingZ = -hl * 0.86f;
            float wingY = spec.beltY + 0.26f;
            for (int s = -1; s <= 1; s += 2) {
                mb.box(s * hw * 0.62f, spec.beltY + 0.13f, wingZ, 0.07f, 0.26f, 0.16f,
                        dark + 0.05f, dark + 0.05f, dark + 0.06f, 0f);
            }
            mb.taperedBox(0f, 0f, wingZ, hw * 1.62f, 0.30f, hw * 1.62f, 0.22f, 0f, -0.05f,
                    wingY, wingY + 0.07f, r * 0.85f, g * 0.85f, b * 0.85f, 0f);
        }

        return mb;
    }

    private static float lampHeight(CarSpec spec) {
        return spec.beltY - Math.min(0.22f, (spec.beltY - spec.sillY) * 0.35f);
    }

    public static final int LAMP_HEAD = 0;
    public static final int LAMP_TAIL = 1;
    public static final int LAMP_BRAKE = 2;
    public static final int LAMP_REVERSE = 3;
    public static final int LAMP_KINDS = 4;

    /**
     * A lit lens, sitting a couple of centimetres proud of its housing so it
     * reads as glowing rather than fighting with it for the same pixels.
     */
    public static MeshBuilder buildLamp(CarSpec spec, int kind) {
        MeshBuilder mb = new MeshBuilder();
        float hw = spec.width * 0.5f;
        float hl = spec.length * 0.5f;
        float y = lampHeight(spec);
        float front = hl * 0.965f + 0.035f;
        float rear = -hl * 0.965f - 0.035f;

        for (int s = -1; s <= 1; s += 2) {
            switch (kind) {
                case LAMP_HEAD:
                    mb.box(s * hw * spec.noseNarrow * 0.62f, y, front,
                            hw * spec.noseNarrow * 0.54f, 0.17f, 0.08f,
                            1f, 0.97f, 0.86f, 1f);
                    break;
                case LAMP_TAIL:
                    mb.box(s * hw * spec.tailNarrow * 0.64f, y + 0.04f, rear,
                            hw * spec.tailNarrow * 0.52f, 0.15f, 0.08f,
                            0.62f, 0.05f, 0.04f, 1f);
                    break;
                case LAMP_BRAKE:
                    mb.box(s * hw * spec.tailNarrow * 0.64f, y + 0.04f, rear,
                            hw * spec.tailNarrow * 0.58f, 0.19f, 0.09f,
                            1f, 0.09f, 0.05f, 1f);
                    break;
                default:
                    mb.box(s * hw * spec.tailNarrow * 0.26f, y - 0.06f, rear,
                            hw * spec.tailNarrow * 0.20f, 0.11f, 0.08f,
                            1f, 1f, 0.94f, 1f);
                    break;
            }
        }
        return mb;
    }

    /** One wheel, centred on the origin with its axle along X. */
    public static MeshBuilder buildWheel(CarSpec spec) {
        MeshBuilder mb = new MeshBuilder();
        mb.wheel(0f, 0f, 0f, spec.wheelRadius, spec.wheelWidth, 14,
                0.07f, 0.07f, 0.08f, 0.62f, 0.63f, 0.66f);
        // A spoke cross so the wheel visibly spins.
        for (int i = 0; i < 2; i++) {
            mb.push();
            mb.rotateX(i * 90f);
            mb.box(0f, 0f, 0f, spec.wheelWidth * 1.02f, spec.wheelRadius * 0.95f, 0.055f,
                    0.50f, 0.51f, 0.54f, 0f);
            mb.pop();
        }
        return mb;
    }
}
