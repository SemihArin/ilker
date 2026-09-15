package com.ilker.opendrive.game;

import com.ilker.opendrive.gl.Mesh;
import com.ilker.opendrive.gl.MeshBuilder;

/** GPU meshes for every vehicle in the garage, in a handful of paint colours. */
public class CarModels {

    /** Variant 0 is always the vehicle's own signature colour. */
    public static final float[][] PAINT = {
            {0f, 0f, 0f},              // placeholder, replaced per spec
            {0.88f, 0.88f, 0.90f},     // white
            {0.13f, 0.14f, 0.16f},     // graphite
            {0.16f, 0.30f, 0.62f},     // blue
            {0.72f, 0.16f, 0.14f},     // red
            {0.36f, 0.40f, 0.44f},     // silver
    };

    public static final int VARIANTS = PAINT.length;

    private final Mesh[] bodies = new Mesh[CarSpec.GARAGE.length * VARIANTS];
    private final Mesh[] wheels = new Mesh[CarSpec.GARAGE.length];
    private final Mesh[] lamps = new Mesh[CarSpec.GARAGE.length * CarMesh.LAMP_KINDS];

    /** Must run on the GL thread. */
    public void create() {
        for (int s = 0; s < CarSpec.GARAGE.length; s++) {
            CarSpec spec = CarSpec.GARAGE[s];
            for (int v = 0; v < VARIANTS; v++) {
                float r, g, b;
                if (v == 0) {
                    r = spec.colorR;
                    g = spec.colorG;
                    b = spec.colorB;
                } else {
                    r = PAINT[v][0];
                    g = PAINT[v][1];
                    b = PAINT[v][2];
                }
                MeshBuilder mb = CarMesh.buildBody(spec, r, g, b);
                bodies[s * VARIANTS + v] = Mesh.fromBuilder(mb);
            }
            wheels[s] = Mesh.fromBuilder(CarMesh.buildWheel(spec));
            for (int k = 0; k < CarMesh.LAMP_KINDS; k++) {
                lamps[s * CarMesh.LAMP_KINDS + k] = Mesh.fromBuilder(CarMesh.buildLamp(spec, k));
            }
        }
    }

    public Mesh body(int specIndex, int variant) {
        if (specIndex < 0 || specIndex >= CarSpec.GARAGE.length) return null;
        if (variant < 0 || variant >= VARIANTS) variant = 0;
        return bodies[specIndex * VARIANTS + variant];
    }

    public Mesh wheel(int specIndex) {
        if (specIndex < 0 || specIndex >= wheels.length) return null;
        return wheels[specIndex];
    }

    /** Lit lens mesh; kind is one of the CarMesh.LAMP_* constants. */
    public Mesh lamp(int specIndex, int kind) {
        if (specIndex < 0 || specIndex >= CarSpec.GARAGE.length) return null;
        if (kind < 0 || kind >= CarMesh.LAMP_KINDS) return null;
        return lamps[specIndex * CarMesh.LAMP_KINDS + kind];
    }

    public void dispose() {
        for (int i = 0; i < bodies.length; i++) {
            if (bodies[i] != null) {
                bodies[i].dispose();
                bodies[i] = null;
            }
        }
        for (int i = 0; i < wheels.length; i++) {
            if (wheels[i] != null) {
                wheels[i].dispose();
                wheels[i] = null;
            }
        }
        for (int i = 0; i < lamps.length; i++) {
            if (lamps[i] != null) {
                lamps[i].dispose();
                lamps[i] = null;
            }
        }
    }
}
