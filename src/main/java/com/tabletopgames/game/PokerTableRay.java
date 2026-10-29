package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.math.Vector3;

/**
 * 德州扑克桌面按钮的射线拾取。
 *
 * <p>坐标系与斗地主同源：{@code local = ((x−ex)·16, (y−ey)·16, −(z−ez)·16)}。</p>
 *
 * <p>★ 按钮<b>不是平铺</b>：与 {@code ddz_table} 的 {@code button_slot*} 逐字同源 ——
 * 中心在 {@code d=25.5, y=-0.25}、整片绕横轴立起 {@code 65°}、朝向跟观察者
 * （{@code rotation = [65, v.kry, 0]}）。所以拾取必须用<b>斜矩形</b>求交
 * （{@link #rectHit}），照 {@code DdzTableRay} 的同名实现搬；用「投影到桌面再比 xz」
 * 那套平面判定会整体偏掉 —— 立起来后牌面在桌面上的投影只有 {@code 2.1·cos65°}。</p>
 *
 * <p>{@code rotation} 三处（模型生成器 / 这里的基向量 / 校验脚本）必须同时改，
 * 否则「看到的按钮」和「点到的按钮」不是同一个。</p>
 */
public final class PokerTableRay {

    /** 拾取距离上限（方块）。 */
    public static final double MAX_DIST = 8.0;
    /** 世界 → 牌桌局部的缩放（1 方块 = 16 局部单位）。 */
    public static final double SCALE = 16.0;
    /** 桌面（3x3 方块）顶面相对方块底座的抬高。 */
    public static final double TABLE_TOP = 0.9375;

    /** 按钮中心到桌心的距离（与 {@code build_model.BTN_D} 一致）。 */
    public static final double BUTTON_D = 25.5;
    /** 按钮中心高度（桌面为 0）—— 斗地主同值：比桌面低一点，边缘才不浮空。 */
    public static final double BUTTON_Y = -0.25;
    /**
     * 槽间距。按钮整排<b>按可见数量居中</b>（模型 {@code v.kbo{i}}），所以没有「起点 u」这个常量；
     * 槽 i 的横坐标 = {@code (i 前面的可见槽数 − (可见总数−1)/2) × −6.6} —— 负号让槽 0 落在最右。
     */
    public static final double BUTTON_STEP = 6.6;
    /** 按钮片半宽（贴图 6.2/2，另加「好点一些」的余量，同斗地主）。 */
    public static final double BUTTON_HALF_U = 3.3;
    /** 按钮片半高（贴图 2.1/2，另加余量）。 */
    public static final double BUTTON_HALF_V = 1.3;
    /** 立起角度（照抄斗地主手牌 / 按钮）。 */
    public static final double BUTTON_TILT = 65;
    /** 拾取余量（沿 U / V 各加，斗地主 {@code ZONE_PAD} 同量级）。 */
    private static final double PAD = 1.2;

    private static final double OG = Math.sin(Math.toRadians(BUTTON_TILT));
    private static final double SG = Math.cos(Math.toRadians(BUTTON_TILT));

    private PokerTableRay() {
    }

    /** @return 0..4 = 动作按钮；5 = 结算「下一局」；6 = 结算「退出牌桌」；-1 = 没命中 */
    public static int hitButton(Player player, PokerGame table) {
        int seat = table.seatOf(player);
        if (seat < 0) {
            return -1;
        }
        boolean actionVisible = table.buttonsVisible();
        int[] sc = table.settleControlCodes();
        boolean settleVisible = sc[0] != 0 || sc[1] != 0;
        if (!actionVisible && !settleVisible) {
            // 桌上没有按钮（等待 / 摊牌 / 不是行动方）就别吞点击，交给「点桌面开菜单」
            return -1;
        }
        Vector3 eye = player.getEyePosition();
        Vector3 dir = player.getDirectionVector();

        // 世界 → 局部（模型单位），与 DdzTableRay 同：z 反号
        double ox = (eye.x - (table.getBaseX() + 1.5)) * SCALE;
        double oy = (eye.y - (table.getBaseY() + TABLE_TOP)) * SCALE;
        double oz = -(eye.z - (table.getBaseZ() + 1.5)) * SCALE;
        double dx = dir.x * SCALE;
        double dy = dir.y * SCALE;
        double dz = -dir.z * SCALE;

        // ★ 与 tools/poker/build_model.py 的 SEAT_BASIS、pre_animation 的 k*r 严格一致：
        //   seat0=南(-z) R=(-1,0) N=(0,-1) / seat1=东(+x) / seat2=北(+z) / seat3=西(-x)
        //   改一处必须三处一起改，否则「看到的按钮」和「点到的按钮」不是同一个。
        double rx;
        double rz;
        double nx;
        double nz;
        switch (seat) {
            case 0 -> {
                rx = -1; rz = 0; nx = 0; nz = -1;
            }
            case 1 -> {
                rx = 0; rz = -1; nx = 1; nz = 0;
            }
            case 2 -> {
                rx = 1; rz = 0; nx = 0; nz = 1;
            }
            default -> {
                rx = 0; rz = 1; nx = -1; nz = 0;
            }
        }

        // 按钮是「按可见数量居中」的（模型里 v.kbo{i}，照抄斗地主 button_offset_N）：
        // 这里必须用同一套偏移，否则看到的位置和点到的位置会错开一整格。
        int[] codes = table.controlCodes();
        int count = 0;
        for (int c : codes) {
            if (c != 0) {
                count++;
            }
        }
        // ---- 动作按钮求交 ----
        if (actionVisible && count > 0) {
            double halfU = (count - 1) / 2.0 * BUTTON_STEP + BUTTON_HALF_U + PAD;
            double halfV = BUTTON_HALF_V + PAD;
            double hit = rectHit(ox, oy, oz, dx, dy, dz,
                    nx * BUTTON_D, BUTTON_Y, nz * BUTTON_D,
                    rx, 0, rz, halfU,
                    -nx * SG, OG, -nz * SG, halfV);
            if (!Double.isNaN(hit)) {
                int best = -1;
                int seen = 0;
                double bestDist = Double.MAX_VALUE;
                for (int i = 0; i < 5; i++) {
                    if (codes[i] == 0) {
                        continue;
                    }
                    double cu = (seen - (count - 1) / 2.0) * -BUTTON_STEP;
                    seen++;
                    double dist = Math.abs(hit - cu);
                    if (dist <= BUTTON_HALF_U + PAD && dist < bestDist) {
                        best = i;
                        bestDist = dist;
                    }
                }
                if (best >= 0) {
                    return best;
                }
            }
        }
        // ---- 结算按钮求交（下一局 / 退出牌桌） ----
        if (settleVisible) {
            int scCount = (sc[0] != 0 ? 1 : 0) + (sc[1] != 0 ? 1 : 0);
            if (scCount > 0) {
                double halfU = (scCount - 1) / 2.0 * BUTTON_STEP + BUTTON_HALF_U + PAD;
                double halfV = BUTTON_HALF_V + PAD;
                double hit = rectHit(ox, oy, oz, dx, dy, dz,
                        nx * BUTTON_D, BUTTON_Y, nz * BUTTON_D,
                        rx, 0, rz, halfU,
                        -nx * SG, OG, -nz * SG, halfV);
                if (!Double.isNaN(hit)) {
                    int best = -1;
                    int seen = 0;
                    double bestDist = Double.MAX_VALUE;
                    for (int i = 0; i < 2; i++) {
                        if (sc[i] == 0) {
                            continue;
                        }
                        double cu = (seen - (scCount - 1) / 2.0) * -BUTTON_STEP;
                        seen++;
                        double dist = Math.abs(hit - cu);
                        if (dist <= BUTTON_HALF_U + PAD && dist < bestDist) {
                            best = 5 + i;   // 5 = next, 6 = exit
                            bestDist = dist;
                        }
                    }
                    return best;
                }
            }
        }
        return -1;
    }

    /**
     * 射线 ∩ 斜矩形（照 {@code DdzTableRay.rectHit} 搬）。
     *
     * @return 命中点在 U 轴上的坐标（模型单位）；未命中返回 {@link Double#NaN}
     */
    private static double rectHit(double ox, double oy, double oz,
                                  double dx, double dy, double dz,
                                  double cx, double cy, double cz,
                                  double ux, double uy, double uz, double halfU,
                                  double vx, double vy, double vz, double halfV) {
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double denom = dx * nx + dy * ny + dz * nz;
        if (Math.abs(denom) < 1e-8) {
            return Double.NaN;
        }
        double t = ((cx - ox) * nx + (cy - oy) * ny + (cz - oz) * nz) / denom;
        if (t <= 0 || t > MAX_DIST) {
            return Double.NaN;
        }
        double px = ox + dx * t - cx;
        double py = oy + dy * t - cy;
        double pz = oz + dz * t - cz;
        double u = px * ux + py * uy + pz * uz;
        double v = px * vx + py * vy + pz * vz;
        if (Math.abs(u) > halfU || Math.abs(v) > halfV) {
            return Double.NaN;
        }
        return u;
    }
}
