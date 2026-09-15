package com.ilker.opendrive.game;

import android.opengl.Matrix;

import com.ilker.opendrive.gl.Frustum;
import com.ilker.opendrive.gl.Mesh;
import com.ilker.opendrive.gl.SceneProgram;
import com.ilker.opendrive.world.Terrain;

import java.util.Random;

/**
 * Ambient traffic. Cars follow the road grid from junction to junction,
 * keeping right and picking a new direction at each one, which is enough to
 * make the city feel inhabited without any real pathfinding.
 */
public class Traffic {

    /** Unit steps between junctions, indexed so that (d + 1) & 3 turns right. */
    private static final int[][] DIR = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private static final int COUNT = 14;
    private static final float DESPAWN_RANGE = 340f;
    private static final float SPAWN_MIN = 90f;
    private static final float SPAWN_MAX = 240f;
    private static final float WHEEL_RANGE = 70f;

    private static final class Unit {
        float x, z, y, yaw;
        float speed;        // cruising speed it would like to hold
        float current;      // what it is actually doing
        boolean braking;
        float wheelSpin;
        float pitch, roll;
        int specIndex;
        int variant;
        int nodeI, nodeJ;
        int dir;
        float targetX, targetZ;
        boolean active;
    }

    private final Unit[] units = new Unit[COUNT];
    private final Random random = new Random(20260915L);
    private final float[] model = new float[16];
    private final float[] wheelModel = new float[16];

    public Traffic() {
        for (int i = 0; i < COUNT; i++) {
            units[i] = new Unit();
        }
    }

    public void reset() {
        for (Unit u : units) {
            u.active = false;
        }
    }

    public void update(float dt, Car player) {
        for (Unit u : units) {
            if (!u.active) {
                spawn(u, player);
                continue;
            }

            float dxp = u.x - player.x;
            float dzp = u.z - player.z;
            if (dxp * dxp + dzp * dzp > DESPAWN_RANGE * DESPAWN_RANGE) {
                u.active = false;
                continue;
            }

            float tx = u.targetX - u.x;
            float tz = u.targetZ - u.z;
            float dist = (float) Math.sqrt(tx * tx + tz * tz);
            if (dist < 5f) {
                advanceNode(u);
                tx = u.targetX - u.x;
                tz = u.targetZ - u.z;
                dist = Math.max(0.001f, (float) Math.sqrt(tx * tx + tz * tz));
            }

            float wantYaw = (float) Math.atan2(tx, tz);
            float delta = wrapAngle(wantYaw - u.yaw);
            float maxTurn = 2.2f * dt;
            if (delta > maxTurn) delta = maxTurn;
            if (delta < -maxTurn) delta = -maxTurn;
            u.yaw = wrapAngle(u.yaw + delta);

            // Ease off through the corners, and actually slow down for the
            // player rather than driving through the back of them.
            float target = u.speed;
            float ahead = (player.x - u.x) * (float) Math.sin(u.yaw)
                    + (player.z - u.z) * (float) Math.cos(u.yaw);
            float beside = Math.abs((player.x - u.x) * -(float) Math.cos(u.yaw)
                    + (player.z - u.z) * (float) Math.sin(u.yaw));
            if (ahead > 0f && ahead < 22f && beside < 3.6f) {
                float room = Math.max(0f, ahead - 6f);
                target = Math.min(target, room * 0.9f);
            }
            // Braking is quicker than getting back on the power.
            float change = target - u.current;
            float limit = (change < 0f ? 16f : 5f) * dt;
            u.current += Math.max(-limit, Math.min(limit, change));
            if (u.current < 0f) u.current = 0f;
            u.braking = change < -0.6f;

            float corner = 1f - Math.min(0.55f, Math.abs(delta / Math.max(dt, 0.0001f)) * 0.30f);
            float move = u.current * corner * dt;
            u.x += (float) Math.sin(u.yaw) * move;
            u.z += (float) Math.cos(u.yaw) * move;
            u.y = Terrain.surfaceHeight(u.x, u.z);
            u.wheelSpin += (u.current / 0.35f) * dt;
            if (u.wheelSpin > Math.PI * 2) u.wheelSpin -= (float) (Math.PI * 2);

            float fx = (float) Math.sin(u.yaw);
            float fz = (float) Math.cos(u.yaw);
            float rightX = -fz;
            float rightZ = fx;
            float noseH = Terrain.surfaceHeight(u.x + fx * 1.4f, u.z + fz * 1.4f);
            float behind = Terrain.surfaceHeight(u.x - fx * 1.4f, u.z - fz * 1.4f);
            float right = Terrain.surfaceHeight(u.x + rightX * 0.8f, u.z + rightZ * 0.8f);
            float left = Terrain.surfaceHeight(u.x - rightX * 0.8f, u.z - rightZ * 0.8f);
            u.pitch = (float) Math.atan2(noseH - behind, 2.8f);
            u.roll = (float) Math.atan2(right - left, 1.6f);

            collide(u, player);
        }
    }

    private void collide(Unit u, Car player) {
        float dx = player.x - u.x;
        float dz = player.z - u.z;
        float d2 = dx * dx + dz * dz;
        float limit = 3.2f;
        if (d2 > limit * limit || d2 < 1e-5f) return;

        float d = (float) Math.sqrt(d2);
        float nx = dx / d;
        float nz = dz / d;
        float push = (limit - d) * 0.6f;
        player.x += nx * push;
        player.z += nz * push;
        u.x -= nx * push * 0.5f;
        u.z -= nz * push * 0.5f;

        // Scrub off the component of the player's speed heading into the shunt.
        float into = player.vx * -nx + player.vz * -nz;
        if (into > 0f) {
            player.vx += nx * into * 1.15f;
            player.vz += nz * into * 1.15f;
            float fx = player.forwardX();
            float fz = player.forwardZ();
            player.forwardSpeed = player.vx * fx + player.vz * fz;
        }
        u.speed *= 0.55f;
        u.current *= 0.55f;
    }

    private void advanceNode(Unit u) {
        u.nodeI += DIR[u.dir][0];
        u.nodeJ += DIR[u.dir][1];
        float roll = random.nextFloat();
        if (roll > 0.62f) {
            u.dir = (roll > 0.81f) ? ((u.dir + 1) & 3) : ((u.dir + 3) & 3);
        }
        setTarget(u);
    }

    private void setTarget(Unit u) {
        int rightDir = (u.dir + 1) & 3;
        int nextI = u.nodeI + DIR[u.dir][0];
        int nextJ = u.nodeJ + DIR[u.dir][1];
        u.targetX = nextI * Terrain.CHUNK + DIR[rightDir][0] * Terrain.LANE_OFFSET;
        u.targetZ = nextJ * Terrain.CHUNK + DIR[rightDir][1] * Terrain.LANE_OFFSET;
    }

    private void spawn(Unit u, Car player) {
        double angle = random.nextFloat() * Math.PI * 2.0;
        float range = SPAWN_MIN + random.nextFloat() * (SPAWN_MAX - SPAWN_MIN);
        float px = player.x + (float) Math.cos(angle) * range;
        float pz = player.z + (float) Math.sin(angle) * range;

        // Snap onto the nearest junction of the road grid.
        u.nodeI = Math.round(px / Terrain.CHUNK);
        u.nodeJ = Math.round(pz / Terrain.CHUNK);
        u.dir = random.nextInt(4);

        int rightDir = (u.dir + 1) & 3;
        u.x = u.nodeI * Terrain.CHUNK + DIR[rightDir][0] * Terrain.LANE_OFFSET;
        u.z = u.nodeJ * Terrain.CHUNK + DIR[rightDir][1] * Terrain.LANE_OFFSET;

        float dx = u.x - player.x;
        float dz = u.z - player.z;
        if (dx * dx + dz * dz < SPAWN_MIN * SPAWN_MIN * 0.36f) {
            return; // too close to pop in; try again next frame
        }

        u.y = Terrain.surfaceHeight(u.x, u.z);
        u.yaw = (float) Math.atan2(DIR[u.dir][0], DIR[u.dir][1]);
        u.specIndex = random.nextInt(CarSpec.GARAGE.length);
        u.variant = 1 + random.nextInt(CarModels.VARIANTS - 1);
        u.speed = 11f + random.nextFloat() * 10f;
        u.current = u.speed;
        u.braking = false;
        u.pitch = 0f;
        u.roll = 0f;
        setTarget(u);
        u.active = true;
    }

    private static float wrapAngle(float a) {
        while (a > Math.PI) a -= (float) (Math.PI * 2);
        while (a < -Math.PI) a += (float) (Math.PI * 2);
        return a;
    }

    public void draw(SceneProgram program, CarModels models, Frustum frustum,
                     float[] viewProj, float[] mvpScratch, float camX, float camZ,
                     boolean night) {
        for (Unit u : units) {
            if (!u.active) continue;
            if (!frustum.sphereVisible(u.x, u.y + 1f, u.z, 4.2f)) continue;

            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, u.x, u.y, u.z);
            Matrix.rotateM(model, 0, (float) Math.toDegrees(u.yaw), 0f, 1f, 0f);
            Matrix.rotateM(model, 0, (float) -Math.toDegrees(u.pitch), 1f, 0f, 0f);
            Matrix.rotateM(model, 0, (float) -Math.toDegrees(u.roll), 0f, 0f, 1f);
            Matrix.multiplyMM(mvpScratch, 0, viewProj, 0, model, 0);
            program.setMatrices(mvpScratch, model);

            Mesh body = models.body(u.specIndex, u.variant);
            if (body != null) body.draw(program);

            if (night) {
                // Headlights and tail lamps turn the night city into traffic
                // rather than a row of dark boxes.
                Mesh head = models.lamp(u.specIndex, CarMesh.LAMP_HEAD);
                if (head != null) head.draw(program);
            }
            if (u.braking) {
                // Brake lights show in daylight too — that is the point of them.
                Mesh stop = models.lamp(u.specIndex, CarMesh.LAMP_BRAKE);
                if (stop != null) stop.draw(program);
            } else if (night) {
                Mesh tail = models.lamp(u.specIndex, CarMesh.LAMP_TAIL);
                if (tail != null) tail.draw(program);
            }

            float dx = u.x - camX;
            float dz = u.z - camZ;
            if (dx * dx + dz * dz > WHEEL_RANGE * WHEEL_RANGE) continue;

            Mesh wheel = models.wheel(u.specIndex);
            if (wheel == null) continue;
            CarSpec spec = CarSpec.GARAGE[u.specIndex];
            for (int i = 0; i < 4; i++) {
                float sideSign = (i == 0 || i == 2) ? -1f : 1f;
                float zOff = (i < 2) ? spec.wheelbase * 0.5f : -spec.wheelbase * 0.5f;
                System.arraycopy(model, 0, wheelModel, 0, 16);
                Matrix.translateM(wheelModel, 0, sideSign * spec.track * 0.5f,
                        spec.wheelRadius, zOff);
                Matrix.rotateM(wheelModel, 0, (float) Math.toDegrees(u.wheelSpin), 1f, 0f, 0f);
                Matrix.multiplyMM(mvpScratch, 0, viewProj, 0, wheelModel, 0);
                program.setMatrices(mvpScratch, wheelModel);
                wheel.draw(program);
            }
        }
    }

    /** Positions for the minimap, packed as x,z pairs; returns how many are live. */
    public int collectPositions(float[] out) {
        int n = 0;
        for (Unit u : units) {
            if (!u.active) continue;
            if (n * 2 + 1 >= out.length) break;
            out[n * 2] = u.x;
            out[n * 2 + 1] = u.z;
            n++;
        }
        return n;
    }
}
