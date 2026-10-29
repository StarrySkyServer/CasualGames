package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.math.Vector3;
import com.tabletopgames.entity.UnoTableEntity;

/**
 * UNO 牌桌局部射线拾取（与斗地主 {@code TableRay} 同一套已实测可用的数学）。
 * <p>
 * <b>坐标系</b>：牌桌实体以 yaw=0 生成于 3x3 中心，世界 → 模型的换算是
 * {@code local = ((x-ex)*16, (y-ey)*16, -(z-ez)*16)}（唯一的额外操作是 z 取反，
 * 源自 Bedrock 实体在 yaw=0 时模型 +z 指向世界北）。方向向量同比例缩放。
 * 射线参数 t 以方块为单位，最远 {@link #MAX_DIST} 格。
 * <p>
 * <b>所有几何常量必须与 {@code tools/uno/build_model.py} 一致</b>，否则「看得见但点不到」：
 * 手牌扇形 pitch 65°、手牌半径 23、按钮半径 25.5、按钮槽距 -6.6、
 * 选中牌沿卡牌上方向抬 2.1333…、第二行手牌向外推 1.8 并抬高 7.0。
 * 这些常量由 {@code tools/uno/verify_ray.py} 与布局动画逐项对拍。
 * <p>
 * <b>牌面矩形</b>：每张牌用一个矩形近似——U 轴 = 手牌横轴（半宽 2.5），
 * V 轴 = 卡牌上方向 {@code -OUT*sin65° + y*cos65°}（半高 3.75）。
 * 选中抬升沿 +V 方向，因此「点击已经抬起来的牌」也能命中。
 * <p>
 * 矩形在真实卡面外还各加了一圈拾取余量（{@link #CARD_PICK_PAD_U} /
 * {@link #CARD_PICK_PAD_V}），与按钮一直以来的「好点一些」余量对齐 —— 手牌是重叠扇形，
 * 零余量会让每张牌只剩约 56% 的宽度可点，越靠里的行越难瞄。
 * <p>
 * <b>半厚补偿</b>：布局动画给的是<b>骨骼枢轴</b>，而卡牌立方体是
 * {@code origin.y = 0..0.12}，即枢轴位于牌的<b>底面</b>。可见卡面的几何中心还要沿
 * 牌面法线 {@code (OUT*sin65°, cos65°, OUT*sin65°)} 再偏 {@link #CARD_HALF_T}，
 * 否则射线平面会比真实卡面低 0.06，与 {@code tools/uno/verify_ray.py} 对不上。
 * <p>
 * hover 取值见 {@link UnoTableEntity#hoverCard} / {@link UnoTableEntity#hoverButton}。
 * 手牌判定 = <b>可见条带裁剪</b>（每张牌只认自己露出的那条，行内最后一张认整张），
 * 其余择优：t 更小者胜；|Δt| ≤ 1e-6 时类别优先级 手牌(2) &gt; 按钮(1)，同类取槽位大者。
 */
public final class UnoTableRay {

    /** 最大拾取距离（方块）。 */
    public static final double MAX_DIST = 8.0;

    /** 世界 → 牌桌局部的缩放（1 方块 = 16 局部单位）。 */
    public static final double SCALE = 16.0;

    // ---- 手牌 ----
    /** sin65°：卡牌上方向的 y 分量。 */
    public static final double OG = 0.90630778703665;
    /** cos65°：卡牌上方向的 xz 系数。 */
    public static final double SG = 0.42261826174069974;
    /** 手牌展示整体抬升量（与布局动画 {@code HAND_Y} 同步）。 */
    public static final double HAND_LIFT = 1.75;
    /** 手牌原点高度 CG = 7.5/2*sin65° - 1 + HAND_LIFT。 */
    public static final double HAND_Y = 7.5 / 2 * OG - 1 + HAND_LIFT;
    /** 手牌到牌桌中心的距离。 */
    public static final double HAND_RADIUS = 23.0;
    /** 选中牌抬升量。 */
    public static final double RAISE = 2.1333333333333333;
    /** 抬升的 xz 分量 = RAISE*cos65°。 */
    public static final double RAISE_COS = RAISE * SG;
    /** 抬升的 y 分量 = RAISE*sin65°。 */
    public static final double RAISE_SIN = RAISE * OG;
    /** 每行最多摆多少张手牌，超出则沿斜线换下一行。 */
    public static final int ROW_SIZE = 20;
    /** 一张牌的高度（沿卡牌上方向）。 */
    public static final double CARD_H = 7.5;
    /**
     * 换行的水平位移，沿 {@code -OUT}（朝桌心）。
     *
     * <p>换行方向 = 卡牌自身的「上方向」= {@code -OUT*SG + y*OG}，行距取一个牌高，
     * 所以下一行的底边恰好贴住上一行的顶边（"起点衔接上一行弹起后的最高点"），
     * 第三行及以后每一行继续沿同一条 65° 斜线往上叠。
     */
    public static final double ROW_OUT = CARD_H * SG;
    /** 换行的垂直位移（同上，与 {@link #ROW_OUT} 合成 65° 斜线）。 */
    public static final double ROW_UP = CARD_H * OG;
    /** 手牌横向排布的最大间距。 */
    public static final double SPACING_MAX = 2.8;
    /** 手牌横向排布的跨度（间距 = 跨度 / (张数-1)，再钳到最大值）。 */
    public static final double SPACING_SPAN = 35.2;
    /** 手牌矩形半宽（沿手牌横轴，卡宽 5 的一半）。 */
    public static final double CARD_HALF_U = 2.5;
    /** 手牌矩形半高（沿卡牌上方向，卡高的一半）。 */
    public static final double CARD_HALF_V = CARD_H / 2;
    /** 卡牌矩形半厚（卡厚 0.12 的一半）：骨骼枢轴在底面，可见卡心在枢轴 + 半厚×法线。 */
    public static final double CARD_HALF_T = 0.06;
    /**
     * 手牌拾取余量（横向，两侧各加）。
     * <p>
     * 按钮的拾取矩形一直有约 +50% 的「好点一些」余量（{@link #BUTTON_HALF_U} 3.25 对
     * 真实半宽 3.1、{@link #BUTTON_HALF_V} 1.6 对真实半深 1.05），而手牌以前是<b>零余量</b>
     * —— 手牌是 2.8 间距叠放的扇形，一张牌只有约 56% 宽度露在外面，零余量下极难瞄准，
     * 越靠里的行（被上一行挡住更多、视线越掠）越明显，这就是玩家反馈的
     * 「第二行好难选中，感觉要跟视线垂直才能选中」。加一点余量即可与按钮手感对齐。
     */
    public static final double CARD_PICK_PAD_U = 0.5;
    /** 手牌拾取余量（纵向，两侧各加）。 */
    public static final double CARD_PICK_PAD_V = 0.35;

    // ---- 按钮 ----
    /** 按钮到牌桌中心的距离。 */
    public static final double BUTTON_RADIUS = 25.5;
    /** 按钮平面高度。 */
    public static final double BUTTON_Y = -0.25;
    /**
     * 按钮槽间距。AX 轴指向「玩家的右手边」，故取正值时槽 0 落在玩家的<b>左手边</b>，
     * 与需求「取消 提示 抽牌 出牌（屏幕从左到右）」一致；与资源生成器同号，务必同步修改。
     */
    public static final double BUTTON_GAP = 6.6;
    /** 按钮矩形半宽。 */
    public static final double BUTTON_HALF_U = 3.25;
    /** 按钮矩形半高。 */
    public static final double BUTTON_HALF_V = 1.6;

    /** 每座位 outward（中心→座位，模型局部 xz 单位向量）。座位 0=东 1=南 2=西 3=北。 */
    public static final int[] OUT_X = {1, 0, -1, 0};
    public static final int[] OUT_Z = {0, -1, 0, 1};

    /** 择优比较的平局容差。 */
    private static final double EPS = 1e-6;

    /** 类别优先级：按钮。 */
    private static final int PRI_BUTTON = 1;
    /** 类别优先级：手牌。 */
    private static final int PRI_CARD = 2;

    private UnoTableRay() {
    }

    /**
     * 命中结果。
     *
     * @param hover hover 值（{@link UnoTableEntity#HOVER_NONE} = 未命中）
     * @param t     命中距离（方块，仅 hover≠0 时有效）
     * @param u     命中点在矩形 U 轴上的坐标（模型单位，仅 hover≠0 时有效）
     * @param v     命中点在矩形 V 轴上的坐标（模型单位，仅 hover≠0 时有效）
     * @param index 手牌命中的「区内展示序号」（按钮命中 = -1）
     */
    public record Hit(int hover, double t, double u, double v, int index) {
    }

    /** 未命中。 */
    public static Hit miss() {
        return new Hit(UnoTableEntity.HOVER_NONE, -1, 0, 0, -1);
    }

    /** 交互区判定余量（模型单位，1 方块 = 16 单位；1.2 ≈ 0.075 格）。 */
    public static final double ZONE_PAD = 1.2;

    /**
     * 准星是否落在「手牌 + 桌面按钮」这<b>一整片</b>区域上 —— 只判定区域，不判定具体是哪张牌 / 哪个按钮。
     *
     * <p><b>为什么需要它</b>：手牌是扇形叠放的，牌与牌之间有缝；按钮之间也有缝。
     * {@link #raycast} 是逐张牌 / 逐个按钮精确求交的，落在缝里就返回「未命中」，
     * 于是 {@code useBoard} 会当成「点空了桌子」而弹出配置表单 ——
     * 用户原话：「我不点按钮的时候点的话会弹出表单」。
     * 这里把整排牌、整排按钮各用一个<b>大矩形</b>包起来再判一次：
     * 落在这一带就吞掉点击（什么都不做），只有这一带<b>之外</b>的桌面才算「点桌子开菜单」。</p>
     *
     * <p>矩形不是随便放大的：手牌换行方向恰好沿卡牌自身的 V 轴（上方向），
     * 所以整片手牌可以用<b>一个</b>矩形精确覆盖（U 取最宽一行、V 取总行高），
     * 牌与牌之间的空隙天然包含在内。</p>
     */
    public static boolean hitsInteractiveZone(Player player, double ex, double ey, double ez,
                                              int viewerSeat, int actorSeat,
                                              int[] ownHand, int[] buttons) {
        if (viewerSeat < 0 || viewerSeat > 3) {
            return false;
        }
        Vector3 eye = player.getEyePosition();
        Vector3 dir = player.getDirectionVector();
        double ox = (eye.x - ex) * SCALE;
        double oy = (eye.y - ey) * SCALE;
        double oz = -(eye.z - ez) * SCALE;
        double dx = dir.x * SCALE;
        double dy = dir.y * SCALE;
        double dz = -dir.z * SCALE;

        double tx = OUT_X[viewerSeat];
        double tz = OUT_Z[viewerSeat];
        double ax = -tz;
        double az = tx;
        double upx = -tx * SG;
        double upy = OG;
        double upz = -tz * SG;

        // ---- 手牌整片 ----
        if (ownHand != null && ownHand.length > 0) {
            int count = ownHand.length;
            int rows = (count - 1) / ROW_SIZE + 1;
            int widest = Math.min(ROW_SIZE, count);
            double spacing = widest <= 1 ? 0 : Math.min(SPACING_MAX, SPACING_SPAN / (widest - 1));
            double halfU = (widest - 1) / 2.0 * spacing + CARD_HALF_U + ZONE_PAD;
            double halfV = rows * CARD_H / 2 + ZONE_PAD;
            double midRow = (rows - 1) / 2.0;
            double outLen = HAND_RADIUS - midRow * ROW_OUT;
            double cy = HAND_Y + midRow * ROW_UP + midRow * ROW_SIZE * 0.01;
            if (rectHit(ox, oy, oz, dx, dy, dz,
                    tx * outLen, cy, tz * outLen,
                    ax, 0, az, halfU,
                    upx, upy, upz, halfV) != null) {
                return true;
            }
        }

        // ---- 按钮整排 ----
        if (buttons != null && buttons.length > 0 && viewerSeat == actorSeat) {
            int len = Math.min(buttons.length, 4);
            int count = 0;
            for (int k = 0; k < len; k++) {
                if (buttons[k] != 0) {
                    count++;
                }
            }
            if (count > 0) {
                double halfU = (count - 1) / 2.0 * BUTTON_GAP + BUTTON_HALF_U + ZONE_PAD;
                if (rectHit(ox, oy, oz, dx, dy, dz,
                        tx * BUTTON_RADIUS, BUTTON_Y, tz * BUTTON_RADIUS,
                        ax, 0, az, halfU,
                        -tx * SG, OG, -tz * SG, BUTTON_HALF_V + ZONE_PAD) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从玩家视线对牌桌做射线拾取。
     *
     * @param player      发起玩家
     * @param ex/ey/ez    牌桌实体原点（世界坐标，即桌面顶面中心）
     * @param viewerSeat  玩家座位 0..3（手牌只测自己的）
     * @param actorSeat   当前可操作座位（-1 = 无）；只有 viewer==actor 时按钮才参与
     * @param ownHand     该 viewer 的手牌卡槽（按展示顺序，可为 null / 空）
     * @param buttons     当前可见按钮的功能码（按槽位顺序，长度 ≤ 4；null 或空 = 无按钮）
     * @param selectedSlot 该 viewer 已选中的手牌槽（-1 = 无）
     */
    public static Hit raycast(Player player, double ex, double ey, double ez,
                              int viewerSeat, int actorSeat,
                              int[] ownHand, int[] buttons, int selectedSlot) {
        if (viewerSeat < 0 || viewerSeat > 3) {
            return miss();
        }
        Vector3 eye = player.getEyePosition();
        Vector3 dir = player.getDirectionVector();
        // 世界 → 局部（yaw=0：只缩放 + z 取反）；视点即相机，不加任何人为偏移
        double ox = (eye.x - ex) * SCALE;
        double oy = (eye.y - ey) * SCALE;
        double oz = -(eye.z - ez) * SCALE;
        double dx = dir.x * SCALE;
        double dy = dir.y * SCALE;
        double dz = -dir.z * SCALE;

        int seat = viewerSeat;
        double tx = OUT_X[seat];
        double tz = OUT_Z[seat];
        // handAxis（横向，与 build_model.AX_* 一致）：(-OUT_Z, OUT_X)
        double ax = -tz;
        double az = tx;
        // 卡牌上方向：朝玩家一侧（-OUT）倾斜 65°
        double upx = -tx * SG;
        double upy = OG;
        double upz = -tz * SG;
        // 牌面法线：水平面上朝外、抬高 25°（= 90°-65°），与卡面垂直且与 up 正交
        double fnx = tx * OG;
        double fny = SG;
        double fnz = tz * OG;

        int bestHover = UnoTableEntity.HOVER_NONE;
        double bestT = 0;
        double bestU = 0;
        double bestV = 0;
        int bestPri = -1;
        int bestSlot = -1;
        int bestIndex = -1;

        // ---- 手牌（只测自己的） ----
        if (ownHand != null && ownHand.length > 0) {
            int count = ownHand.length;
            for (int idx = 0; idx < count; idx++) {
                // 行号不写死：第三行及以后由 floor(idx/ROW_SIZE) 自然得到
                int row = idx / ROW_SIZE;
                int inRow = idx - row * ROW_SIZE;
                int rowCount = Math.min(ROW_SIZE, count - row * ROW_SIZE);
                double spacing = rowCount <= 1 ? 0 : Math.min(SPACING_MAX, SPACING_SPAN / (rowCount - 1));
                double o = (inRow - (rowCount - 1) / 2.0) * spacing;
                int slot = ownHand[idx];
                boolean lifted = slot == selectedSlot;
                // 换行位移沿卡牌上方向（朝桌心 + 向上），与选中抬升同一个方向
                double outLen = HAND_RADIUS - row * ROW_OUT - (lifted ? RAISE_COS : 0);
                // 枢轴在底面 → 再沿牌面法线补半厚，命中平面才与可见卡面重合
                double cx = tx * outLen + ax * o + fnx * CARD_HALF_T;
                // 逐张抬升按**行内序号**累加：用全局序号会让每行多抬 0.01*ROW_SIZE，
                // 行距就不再等于一个牌高（动画侧同）。
                double cy = HAND_Y + row * ROW_UP + 0.01 * inRow
                        + (lifted ? RAISE_SIN : 0) + fny * CARD_HALF_T;
                double cz = tz * outLen + az * o + fnz * CARD_HALF_T;
                Isect isect = rectHit(ox, oy, oz, dx, dy, dz,
                        cx, cy, cz, ax, 0, az, CARD_HALF_U + CARD_PICK_PAD_U,
                        upx, upy, upz, CARD_HALF_V + CARD_PICK_PAD_V);
                if (isect == null || !inVisibleStrip(isect.u, inRow, rowCount, spacing)) {
                    continue;
                }
                if (better(isect.t, PRI_CARD, slot, bestT, bestPri, bestSlot)) {
                    bestHover = UnoTableEntity.hoverCard(slot);
                    bestT = isect.t;
                    bestU = isect.u;
                    bestV = isect.v;
                    bestPri = PRI_CARD;
                    bestSlot = slot;
                    bestIndex = idx;
                }
            }
        }

        // ---- 按钮（仅当前操作方可点） ----
        if (buttons != null && buttons.length > 0 && viewerSeat == actorSeat) {
            int len = Math.min(buttons.length, 4);
            int count = 0;
            for (int k = 0; k < len; k++) {
                if (buttons[k] != 0) {
                    count++;
                }
            }
            double bx = tx * BUTTON_RADIUS;
            double bz = tz * BUTTON_RADIUS;
            int acc = 0;
            for (int k = 0; k < len; k++) {
                if (buttons[k] == 0) {
                    continue;
                }
                double o = (acc - (count - 1) / 2.0) * BUTTON_GAP;
                // V 轴用按钮斜面的「上方向」(-OUT*SG, +OG, -OUT*SG)（与卡牌/按钮片的 65° 倾斜
                // 一致），保证命中面与按钮斜面严格贴合。
                Isect isect = rectHit(ox, oy, oz, dx, dy, dz,
                        bx + ax * o, BUTTON_Y, bz + az * o,
                        ax, 0, az, BUTTON_HALF_U,
                        -tx * SG, OG, -tz * SG, BUTTON_HALF_V);
                if (isect != null && better(isect.t, PRI_BUTTON, k, bestT, bestPri, bestSlot)) {
                    bestHover = UnoTableEntity.hoverButton(k);
                    bestT = isect.t;
                    bestU = isect.u;
                    bestV = isect.v;
                    bestPri = PRI_BUTTON;
                    bestSlot = k;
                }
                acc++;
            }
        }

        return bestHover == UnoTableEntity.HOVER_NONE
                ? miss()
                : new Hit(bestHover, bestT, bestU, bestV, bestIndex);
    }

    /**
     * 命中点的横向坐标 u 是否落在该牌的<b>可见条带</b>内。
     *
     * <p><b>规则（用户 10-24 两次真机截图圈定）</b>：手牌扇形是「右压左」叠放的
     * （逐张 0.01 的 y 微抬让更右的牌画在上面，最右一张完整可见），所以
     * 每张牌的可选区域 = 它<b>露出来的那条</b>：左缘是自己的左缘
     * {@code -CARD_HALF_U}，右缘被右邻裁掉 = 右邻的左缘 {@code spacing - CARD_HALF_U}；
     * 只有<b>行内最后一张</b>（右侧无遮挡）认整张牌。
     * 余量只加在整排的最左缘 / 最右缘（那里没有邻居，条带边界本来就严丝合缝，
     * 中间加余量只会把点击送给错误的邻牌）。</p>
     *
     * <p>条带在行与行之间互不重叠（换行沿 V 轴），同一水平点只会被一张牌接受，
     * 因此接受集合天然唯一，无须再靠 |u| 或 t 在同排内仲裁。</p>
     *
     * <p>⚠️ 历史教训：别用「命中点离牌心多近（|u|）」当主判据 —— 条的可见部分
     * 并不以牌心为中心（窄间距下牌心本身都被右邻遮住），|u| 会让每张牌的选中区
     * 整体偏移半条以上；也别用 t 当主判据 —— 逐张 y 微抬让「更右 ⇒ t 更小」，
     * 整牌矩形下永远选中右邻。两种写法 10-24 都在真机上翻过车。</p>
     */
    private static boolean inVisibleStrip(double u, int inRow, int rowCount, double spacing) {
        double uLo = inRow == 0 ? -CARD_HALF_U - CARD_PICK_PAD_U : -CARD_HALF_U;
        double uHi = inRow == rowCount - 1 ? CARD_HALF_U + CARD_PICK_PAD_U : spacing - CARD_HALF_U;
        return u >= uLo && u <= uHi;
    }

    /** 择优：t 严格更小胜；平局比类别优先级，同类比槽位。 */
    private static boolean better(double t, int pri, int slot,
                                  double bestT, int bestPri, int bestSlot) {
        if (bestT <= 0) {
            return true;
        }
        if (t < bestT - EPS) {
            return true;
        }
        if (Math.abs(t - bestT) <= EPS) {
            return pri > bestPri || (pri == bestPri && slot > bestSlot);
        }
        return false;
    }

    /** 射线 ∩ 矩形的完整结果（t 为方块距离，u/v 为矩形局部坐标，模型单位）。 */
    private record Isect(double t, double u, double v) {
    }

    /**
     * 射线与矩形求交。
     *
     * @param ux/uy/uz   矩形 U 轴（单位向量），halfU 为半宽
     * @param vx/vy/vz   矩形 V 轴（单位向量），halfV 为半高
     * @return 命中（含局部坐标 u/v），未命中返回 {@code null}
     */
    private static Isect rectHit(double ox, double oy, double oz,
                                 double dx, double dy, double dz,
                                 double cx, double cy, double cz,
                                 double ux, double uy, double uz, double halfU,
                                 double vx, double vy, double vz, double halfV) {
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double denom = dx * nx + dy * ny + dz * nz;
        if (Math.abs(denom) < 1e-8) {
            return null;
        }
        double t = ((cx - ox) * nx + (cy - oy) * ny + (cz - oz) * nz) / denom;
        if (t <= 0 || t > MAX_DIST) {
            return null;
        }
        double px = ox + dx * t - cx;
        double py = oy + dy * t - cy;
        double pz = oz + dz * t - cz;
        double u = px * ux + py * uy + pz * uz;
        double v = px * vx + py * vy + pz * vz;
        if (Math.abs(u) > halfU || Math.abs(v) > halfV) {
            return null;
        }
        return new Isect(t, u, v);
    }

    // ------------------------------------------------------------------
    // 供 UnoTable 复用的布局辅助（避免表逻辑重算一遍同样的公式）
    // ------------------------------------------------------------------

    /** 手牌第 idx 张在给定行里的横向间距。 */
    public static double handSpacing(int count, int row) {
        int rowCount = rowSize(count, row);
        return rowCount <= 1 ? 0 : Math.min(SPACING_MAX, SPACING_SPAN / (rowCount - 1));
    }

    /** 手牌第 row 行的张数（第 0 行起，行数不设上限）。 */
    public static int rowSize(int count, int row) {
        return Math.max(0, Math.min(ROW_SIZE, count - row * ROW_SIZE));
    }
}
