package lol.zym.zymbot.control;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Direct look and key control for the moments Baritone isn't steering (mining, eating, fighting). */
public final class Controls {
    private Controls() {}

    private static boolean useHeld, sneakHeld, forwardHeld, jumpHeld;

    /** Turn towards a point, at most {@code maxDeg} per tick so it looks like a person turning. */
    public static boolean lookAt(Vec3 target, float maxDeg) {
        LocalPlayer p = Minecraft.getInstance().player;
        Vec3 eye = p.getEyePosition();
        Vec3 d = target.subtract(eye);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG);
        float dy = Mth.wrapDegrees(yaw - p.getYRot());
        float dp = pitch - p.getXRot();
        float sy = Mth.clamp(dy, -maxDeg, maxDeg);
        float sp = Mth.clamp(dp, -maxDeg, maxDeg);
        p.setYRot(p.getYRot() + sy);
        p.setXRot(Mth.clamp(p.getXRot() + sp, -90f, 90f));
        p.yHeadRot = p.getYRot();
        return Math.abs(dy - sy) < 1f && Math.abs(dp - sp) < 1f;
    }

    public static boolean lookAt(Vec3 target) {
        return lookAt(target, 35f);
    }

    public static void use(boolean down) {
        useHeld = down;
        set(Minecraft.getInstance().options.keyUse, down);
    }

    public static void sneak(boolean down) {
        sneakHeld = down;
        set(Minecraft.getInstance().options.keyShift, down);
    }

    public static void forward(boolean down) {
        forwardHeld = down;
        set(Minecraft.getInstance().options.keyUp, down);
    }

    public static void jump(boolean down) {
        jumpHeld = down;
        set(Minecraft.getInstance().options.keyJump, down);
    }

    private static void set(KeyMapping key, boolean down) {
        key.setDown(down);
    }

    /** Let go of every key the bot pressed. */
    public static void releaseAll() {
        if (useHeld) use(false);
        if (sneakHeld) sneak(false);
        if (forwardHeld) forward(false);
        if (jumpHeld) jump(false);
    }
}
