package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.math.Vector3;
import com.tabletopgames.entity.DdzTableEntity;

/**
 * 斗地主牌桌局部射线拾取。
 *
 * <h2>数学与 UNO 完全同源，差别只有座位数与按钮槽序</h2>
 * 手牌的三个几何量（半径 23、倾角 65°、间距 {@code min(2.8, 35.2/(n-1))}）在模组的
 * {@code animation.tabletopgame.ddz.layout} 里写死，与 UNO 那套
 * {@code tools/uno/build_model.py} 的数值<b>逐个相等</b>；连牌面高度
 * （{@code 4.1486542 = 7.5/2·sin65° − 1 + 1.75}）和选中抬升
 * （{@code 0.9015856 = 2.13333·cos65°}、{@code 1.9334566 = 2.13333·sin65°}）都一样。
 * 所以本类可以直接照抄 {@code UnoTableRay} 的结构，只改三处：
 * <ol>
 *   <li><b>座位数 3</b>：{@link #OUT_X} / {@link #OUT_Z} 只有三项，且顺序不同 ——
 *       模组的座位 0 = 南（OUT=(0,−1)）、座位 1 = 北（OUT=(0,+1)）、座位 2 = 东（OUT=(+1,0)），
 *       没有西位。这个顺序由三处交叉验证：手牌区 z==1/2/3 的落点、地主徽记
 *       {@code landlord_marker} 的三个坐标、托管机器人 {@code trusteeship_pN} 的坐标。</li>
 *   <li><b>手牌只有一行</b>：斗地主最多 20 张（地主 17+3），而
 *       {@code min(2.8, 35.2/19) = 1.85} 已经小于卡宽 5 的可点宽度，
 *       模组的布局动画也<b>没有换行分支</b>（不像 UNO 有 {@code ROW_OUT}/{@code ROW_UP}）。
 *       本类因此不实现换行，遇到超长手牌按单行居中处理（与动画一致，不会错位）。</li>
 *   <li><b>按钮槽序相反</b>：模组 {@code v.button_offset_N = (累计非空 − (总数−1)/2) × −6.6}，
 *       偏移为负 ⇒ <b>槽 0 在最右</b>；UNO 是槽 0 在最左。照抄 UNO 的
 *       {@code BUTTON_GAP = +6.6} 会让整排按钮左右颠倒（点「出牌」变成点「提示」）。</li>
 * </ol>
 *
 * <h2>坐标系铁律</h2>
 * {@code OUT_*} 是<b>模型局部</b>向量，不是世界向量。牌桌实体 yaw=0 生成，
 * 世界 → 局部的换算是 {@code local = ((x−ex)·16, (y−ey)·16, −(z−ez)·16)}
 * —— X 轴不翻转、<b>Z 轴取反</b>。凡是拿 {@code OUT_Z} 算<b>世界</b>坐标的地方
 * （如入座归位 {@code standAtSeat}），必须写 {@code z = cz − OUT_Z·R}，
 * 否则南北两位整体对调（UNO 那边真机踩过）。
 *
 * <h2>命中面与半厚补偿</h2>
 * 卡牌立方体是 {@code origin=[-2.5, 0, -3.75], size=[5, 0.12, 7.5]} —— 枢轴在牌<b>底面</b>，
 * 可见卡心的几何中心还要沿牌面法线补 {@link #CARD_HALF_T}（卡厚 0.12 的一半），
 * 否则射线平面比真实卡面低 0.06，贴边点击会漏。
 * 按钮片是 {@code origin=[-3.1, 0, -1.05], size=[6.2, 0.008, 2.1]}，
 * 同样补半厚 {@link #BUTTON_HALF_T}。
 *
 * <h2>hover 取值</h2>
 * 见 {@link DdzTableEntity#hoveredCardSlot} / {@link #hoveredButtonSlot}：
 * 手牌 1..54、底牌堆 55、按钮 56..59、语音 60。
 * 手牌判定 = <b>可见条带裁剪</b>（每张牌只认自己露出的那条，最右一张认整张，
 * 见 {@link #inVisibleStrip}）；其余择优：t 小者胜 → 类别优先级 → 卡槽号大者。
 */
public final class DdzTableRay {

    /** 最大拾取距离（方块）。 */
    public static final double MAX_DIST = 8.0;

    /** 世界 → 牌桌局部的缩放（1 方块 = 16 局部单位）。 */
    public static final double SCALE = 16.0;

    // ---- 手牌（数值与模组 layout 动画 / UNO build_model 逐字同源） ----

    /** sin65°：卡牌上方向的 y 分量。 */
    public static final double OG = 0.90630778703665;
    /** cos65°：卡牌上方向的 xz 系数。 */
    public static final double SG = 0.42261826174069974;
    /** 手牌到牌桌中心的距离（模组 {@code v.z0==2?23:...}）。 */
    public static final double HAND_RADIUS = 23.0;
    /**
     * 手牌基础高度（模组 {@code pos[1] = 4.1486542013874375 + s·0.01 + sel·1.9334566}）。
     * <p>它等于 UNO 的 {@code 7.5/2·sin65° − 1 + 1.75}，两边一致。
     */
    public static final double HAND_Y = 4.1486542013874375;
    /** 每张牌沿卡牌上方向的逐张微抬（模组 {@code + v.s0*0.01}）。 */
    public static final double HAND_STEP_Y = 0.01;
    /** 选中牌抬升量（模组 {@code sel·1.9334566} 的 y 分量 = 2.13333·sin65°）。 */
    public static final double RAISE = 2.1333333333333333;
    /** 抬升的 xz 分量 = RAISE·cos65°（模组里沿 −OUT，即朝桌心）。 */
    public static final double RAISE_COS = 0.9015856250468255;
    /** 抬升的 y 分量 = RAISE·sin65°。 */
    public static final double RAISE_SIN = 1.933456612344853;
    /** 手牌横向排布的最大间距（模组 {@code >2.8?2.8:...}）。 */
    public static final double SPACING_MAX = 2.8;
    /** 手牌横向排布的跨度（间距 = 跨度 / (张数−1)）。 */
    public static final double SPACING_SPAN = 35.2;
    /** 手牌矩形半宽（沿手牌横轴，卡宽 5.25 的一半 —— 7:10 比例，见 2026-10-22 牌面 42×60 重做）。 */
    public static final double CARD_HALF_U = 2.625;
    /** 手牌矩形半高（沿卡牌上方向，卡长 7.5 的一半）。 */
    public static final double CARD_HALF_V = 3.75;
    /** 卡牌矩形半厚（卡厚 0.12 的一半）：枢轴在底面，可见卡心在枢轴 + 半厚×法线。 */
    public static final double CARD_HALF_T = 0.06;
    /** 手牌拾取余量（横向，两侧各加）——叠放扇形零余量极难瞄准，见 UnoTableRay 同名常量。 */
    public static final double CARD_PICK_PAD_U = 0.5;
    /** 手牌拾取余量（纵向，两侧各加）。 */
    public static final double CARD_PICK_PAD_V = 0.35;

    // ---- 按钮（模组 {@code button_slot*} 的位置常量） ----

    /** 按钮到牌桌中心的距离（模组 {@code -25.5} / {@code 25.5}）。 */
    public static final double BUTTON_RADIUS = 25.5;
    /** 按钮平面高度（模组 {@code -0.25}）。 */
    public static final double BUTTON_Y = -0.25;
    /**
     * 按钮槽间距的<b>带符号</b>值：模组 {@code (累计非空 − (总数−1)/2) × −6.6}，
     * 负号让<b>槽 0 落在最右</b>。这里直接连符号一起搬，别像 UNO 那样只搬 6.6。
     */
    public static final double BUTTON_GAP = -6.6;
    /** 按钮矩形半宽（贴图 {@code 6.2/2}，另加一点「好点一些」的余量）。 */
    public static final double BUTTON_HALF_U = 3.3;
    /** 按钮矩形半高（贴图 {@code 2.1/2}，另加余量）。 */
    public static final double BUTTON_HALF_V = 1.3;
    /** 按钮片半厚（贴图厚 0.008 —— 极薄，补偿可忽略，留 0.01 便于浮点稳定）。 */
    public static final double BUTTON_HALF_T = 0.01;

    /**
     * 每座位 outward（中心→座位，模型局部 xz 单位向量）。
     * <p>顺序由模组资源交叉验证，<b>不要按"东南西北"直觉重排</b>：
     * <pre>
     *   座位 0 = 南 OUT=(0,−1)  手牌区 z==1  落点 (hr, y, −23)
     *   座位 1 = 北 OUT=(0,+1)  手牌区 z==2  落点 (−hr, y, +23)
     *   座位 2 = 东 OUT=(+1,0)  手牌区 z==3  落点 (23, y, hr)
     * </pre>
     * 地主徽记与托管机器人坐标也一致（(−16, −21.415) / (16, 21.415) / (21.415, −16)）。
     */
    public static final int[] OUT_X = {0, 0, 1};
    public static final int[] OUT_Z = {-1, 1, 0};

    /** 择优比较的平局容差。 */
    private static final double EPS = 1e-6;

    /** 类别优先级：按钮。 */
    private static final int PRI_BUTTON = 1;
    /** 类别优先级：手牌。 */
    private static final int PRI_CARD = 2;

    private DdzTableRay() {
    }

    /**
     * 命中结果。
     *
     * @param hover hover 值（{@link DdzTableEntity#HOVER_NONE} = 未命中）
     * @param t     命中距离（方块，仅 hover≠0 时有效）
     * @param u     命中点在矩形 U 轴上的坐标（模型单位，仅 hover≠0 时有效）
     * @param v     命中点在矩形 V 轴上的坐标（模型单位，仅 hover≠0 时有效）
     * @param index 手牌命中的「区内展示序号」（按钮命中 = −1）
     */
    public record Hit(int hover, double t, double u, double v, int index) {
    }

    /** 未命中。 */
    public static Hit miss() {
        return new Hit(DdzTableEntity.HOVER_NONE, -1, 0, 0, -1);
    }

    /** 交互区判定余量（模型单位，1 方块 = 16 单位；1.2 ≈ 0.075 格）。 */
    public static final double ZONE_PAD = 1.2;

    /**
     * 准星是否落在「手牌 + 桌面按钮」这<b>一整片</b>区域上 —— 只判定区域，不判定具体是哪张牌 / 哪个按钮。
     *
     * <p>手牌是扇形叠放的，牌与牌之间有缝；按钮之间也有缝。{@link #raycast} 是逐张精确求交的，
     * 落在缝里就返回「未命中」，于是 {@code useBoard} 当成「点空了桌子」而弹出配置表单 ——
     * 用户原话：「我不点按钮的时候点的话会弹出表单」。这里把整排牌、整排按钮各用一个
     * <b>大矩形</b>包起来再判一次：落在这一带就吞掉点击，只有这一带<b>之外</b>的桌面才开菜单。</p>
     */
    public static boolean hitsInteractiveZone(Player player, double ex, double ey, double ez,
                                              int viewerSeat, int actorSeat,
                                              int[] ownHand, int[] buttons) {
        if (viewerSeat < 0 || viewerSeat >= DdzTableEntity.SEATS) {
            return false;
        }
        Vector3 eye = player.getEyePosition();
        Vector3 dir = player.getDirectionVector();
        return hitsInteractiveZone(eye.x, eye.y, eye.z, dir.x, dir.y, dir.z,
                ex, ey, ez, viewerSeat, actorSeat, ownHand, buttons);
    }

    /** 纯数学入口（供脱机回归用，见 {@link #raycast} 的同名重载说明）。 */
    public static boolean hitsInteractiveZone(double eyeX, double eyeY, double eyeZ,
                                              double dirX, double dirY, double dirZ,
                                              double ex, double ey, double ez,
                                              int viewerSeat, int actorSeat,
                                              int[] ownHand, int[] buttons) {
        if (viewerSeat < 0 || viewerSeat >= DdzTableEntity.SEATS) {
            return false;
        }
        double ox = (eyeX - ex) * SCALE;
        double oy = (eyeY - ey) * SCALE;
        double oz = -(eyeZ - ez) * SCALE;
        double dx = dirX * SCALE;
        double dy = dirY * SCALE;
        double dz = -dirZ * SCALE;

        double tx = OUT_X[viewerSeat];
        double tz = OUT_Z[viewerSeat];
        double ax = -tz;
        double az = tx;
        double upx = -tx * SG;
        double upy = OG;
        double upz = -tz * SG;

        // ---- 手牌整片（斗地主手牌只有一行） ----
        if (ownHand != null && ownHand.length > 0) {
            int count = ownHand.length;
            double spacing = count <= 1 ? 0 : Math.min(SPACING_MAX, SPACING_SPAN / (count - 1));
            double halfU = (count - 1) / 2.0 * spacing + CARD_HALF_U + ZONE_PAD;
            if (rectHit(ox, oy, oz, dx, dy, dz,
                    tx * HAND_RADIUS, HAND_Y, tz * HAND_RADIUS,
                    ax, 0, az, halfU,
                    upx, upy, upz, CARD_HALF_V + ZONE_PAD) != null) {
                return true;
            }
        }

        // ---- 按钮整排 ----
        if (buttons != null && buttons.length > 0 && viewerSeat == actorSeat) {
            int len = Math.min(buttons.length, DdzTableEntity.BUTTON_SLOTS);
            int count = 0;
            for (int k = 0; k < len; k++) {
                if (buttons[k] != 0) {
                    count++;
                }
            }
            if (count > 0) {
                // BUTTON_GAP 是负的（槽 0 在最右），取绝对值当半跨
                double halfU = (count - 1) / 2.0 * Math.abs(BUTTON_GAP) + BUTTON_HALF_U + ZONE_PAD;
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
     * @param viewerSeat  玩家座位 0..2（手牌只测自己的）
     * @param actorSeat   当前可操作座位（−1 = 无）；只有 viewer==actor 时按钮才参与
     * @param ownHand     该 viewer 的手牌卡槽（按展示顺序，可为 null / 空）
     * @param buttons     当前可见按钮的功能码（按槽位顺序 0..3；null 或空 = 无按钮）
     * @param selected    该 viewer 的手牌选中位掩码（bit N = 第 N 张被选中）
     */
    public static Hit raycast(Player player, double ex, double ey, double ez,
                              int viewerSeat, int actorSeat,
                              int[] ownHand, int[] buttons, int selected) {
        return raycast(player, ex, ey, ez, viewerSeat, actorSeat, ownHand, buttons,
                selected, false);
    }

    /** 同上，带「手牌是否反向排列」（{@code true} = 最大在左）。 */
    public static Hit raycast(Player player, double ex, double ey, double ez,
                              int viewerSeat, int actorSeat,
                              int[] ownHand, int[] buttons, int selected,
                              boolean handFlipped) {
        Vector3 eye = player.getEyePosition();
        Vector3 dir = player.getDirectionVector();
        return raycast(eye.x, eye.y, eye.z, dir.x, dir.y, dir.z,
                ex, ey, ez, viewerSeat, actorSeat, ownHand, buttons, selected, handFlipped);
    }

    /**
     * 纯数学入口：直接给世界空间的眼点与方向向量。
     *
     * <p>{@link #raycast(Player, double, double, double, int, int, int[], int[], int)}
     * 只负责取这两个向量，其余数学全在这里 —— 这样 {@code tools/ddz/DdzRayCheck}
     * 能脱机跑完整套回归，不必构造 {@code Player}（它的构造函数要 {@code Level}，
     * 而 {@code getEyePosition} 又不保证可覆写）。
     *
     * @param eyeX/eyeY/eyeZ 世界空间眼点
     * @param dirX/dirY/dirZ 世界空间单位视线方向
     */
    public static Hit raycast(double eyeX, double eyeY, double eyeZ,
                              double dirX, double dirY, double dirZ,
                              double ex, double ey, double ez,
                              int viewerSeat, int actorSeat,
                              int[] ownHand, int[] buttons, int selected) {
        return raycast(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, ex, ey, ez,
                viewerSeat, actorSeat, ownHand, buttons, selected, false);
    }

    /**
     * 同上，额外给出<b>手牌是否反向排列</b>（{@code big → small}，最大在左）。
     *
     * <p>客户端 {@code card_NN} 的左右位置由手牌展示序号 {@code v.dN} 决定：
     * 反序时 {@code v.dN = 张数-1-卡槽序号}。拾取必须跟着镜像，否则「点到的牌」和
     * 「看到的牌」会错位（2026-10-25 加的手牌排序设置）。</p>
     *
     * <p>★ 叠放方向<b>不随镜像改变</b>：镜像只换位置与叠高序号，仍然是「右压左」，
     * 所以每张牌露出的都是<b>左边那条</b>（牌面点数的位置就在左上角）——
     * 可见条带规则可以原样按「视觉序号 p」套用。</p>
     */
    public static Hit raycast(double eyeX, double eyeY, double eyeZ,
                              double dirX, double dirY, double dirZ,
                              double ex, double ey, double ez,
                              int viewerSeat, int actorSeat,
                              int[] ownHand, int[] buttons, int selected,
                              boolean handFlipped) {
        if (viewerSeat < 0 || viewerSeat >= DdzTableEntity.SEATS) {
            return miss();
        }
        // 世界 → 局部（yaw=0：只缩放 + z 取反）；视点即相机，不加任何人为偏移
        double ox = (eyeX - ex) * SCALE;
        double oy = (eyeY - ey) * SCALE;
        double oz = -(eyeZ - ez) * SCALE;
        double dx = dirX * SCALE;
        double dy = dirY * SCALE;
        double dz = -dirZ * SCALE;

        double tx = OUT_X[viewerSeat];
        double tz = OUT_Z[viewerSeat];
        // handAxis（横向）：(-OUT_Z, OUT_X)。模组里槽号越大越靠左，
        // 所以这里的横向偏移与 UNO 的符号天然相反（见按钮段）。
        double ax = -tz;
        double az = tx;
        // 卡牌上方向：朝玩家一侧（−OUT）倾斜 65°
        double upx = -tx * SG;
        double upy = OG;
        double upz = -tz * SG;
        // 牌面法线：水平面上朝外、抬高 25°（= 90°−65°），与 up 正交
        double fnx = tx * OG;
        double fny = SG;
        double fnz = tz * OG;

        int bestHover = DdzTableEntity.HOVER_NONE;
        double bestT = 0;
        double bestU = 0;
        double bestV = 0;
        int bestPri = -1;
        int bestSlot = -1;
        int bestIndex = -1;

        // ---- 手牌（只测自己的） ----
        if (ownHand != null && ownHand.length > 0) {
            int count = ownHand.length;
            double spacing = count <= 1 ? 0 : Math.min(SPACING_MAX, SPACING_SPAN / (count - 1));
            double centerOffset = (count - 1) / 2.0 * spacing;
            for (int idx = 0; idx < count; idx++) {
                // 视觉序号：反序时第 idx 张摆到「张数-1-idx」的位置（与客户端 v.dN 同源）
                int p = handFlipped ? count - 1 - idx : idx;
                double o = p * spacing - centerOffset;
                int slot = ownHand[idx];
                boolean lifted = ((selected >>> idx) & 1) != 0;
                // 选中抬升沿 −OUT（朝桌心）0.9015856、沿 +y 1.9334566，与模组 sel 分支逐字一致
                double outLen = HAND_RADIUS - (lifted ? RAISE_COS : 0);
                double cx = tx * outLen + ax * o + fnx * CARD_HALF_T;
                double cy = HAND_Y + p * HAND_STEP_Y
                        + (lifted ? RAISE_SIN : 0) + fny * CARD_HALF_T;
                double cz = tz * outLen + az * o + fnz * CARD_HALF_T;
                Isect isect = rectHit(ox, oy, oz, dx, dy, dz,
                        cx, cy, cz, ax, 0, az, CARD_HALF_U + CARD_PICK_PAD_U,
                        upx, upy, upz, CARD_HALF_V + CARD_PICK_PAD_V);
                if (isect == null || !inVisibleStrip(isect.u, p, count, spacing)) {
                    continue;
                }
                if (better(isect.t, PRI_CARD, slot, bestT, bestPri, bestSlot)) {
                    bestHover = DdzTableEntity.HOVER_CARD_BASE + slot;
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
            int len = Math.min(buttons.length, DdzTableEntity.BUTTON_SLOTS);
            int count = 0;
            for (int k = 0; k < len; k++) {
                if (buttons[k] != 0) {
                    count++;
                }
            }
            int acc = 0;
            for (int k = 0; k < len; k++) {
                if (buttons[k] == 0) {
                    continue;
                }
                // ⚠️ 模组 offset = (累计非空 − (总数−1)/2) × (−6.6)，槽 0 在最右。
                //    offset 已经是**局部横向的有符号位移**，直接乘 AX 即可。
                double o = (acc - (count - 1) / 2.0) * BUTTON_GAP;
                // 按钮片枢轴也在底面（origin.y=0, 厚 0.008），沿法线补半厚让命中面贴合可见面
                double bx = tx * BUTTON_RADIUS + ax * o + fnx * BUTTON_HALF_T;
                double bz = tz * BUTTON_RADIUS + az * o + fnz * BUTTON_HALF_T;
                double by = BUTTON_Y + fny * BUTTON_HALF_T;
                // V 轴用按钮斜面的「上方向」（−OUT·sin65°, cos65°），保证命中面与按钮片贴合
                Isect isect = rectHit(ox, oy, oz, dx, dy, dz,
                        bx, by, bz,
                        ax, 0, az, BUTTON_HALF_U,
                        -tx * SG, OG, -tz * SG, BUTTON_HALF_V);
                if (isect != null && better(isect.t, PRI_BUTTON, k, bestT, bestPri, bestSlot)) {
                    bestHover = DdzTableEntity.HOVER_BUTTON_BASE + k;
                    bestT = isect.t;
                    bestU = isect.u;
                    bestV = isect.v;
                    bestPri = PRI_BUTTON;
                    bestSlot = k;
                }
                acc++;
            }
        }

        return bestHover == DdzTableEntity.HOVER_NONE
                ? miss()
                : new Hit(bestHover, bestT, bestU, bestV, bestIndex);
    }

    /**
     * 当前按钮组排布后的「第 k 个非空槽」在横向上的偏移。
     * <p>供主控类复算按钮世界坐标（例如提示框定位）时用，避免两处各写一遍公式。
     *
     * @param buttons 按槽位顺序的动作码（0 = 空）
     * @param k       槽号 0..3
     * @return 非空槽的累计序号 −（总数−1）/2 再乘 {@link #BUTTON_GAP}；槽为空返回 0
     */
    public static double buttonOffset(int[] buttons, int k) {
        if (buttons == null || k < 0 || k >= Math.min(buttons.length, DdzTableEntity.BUTTON_SLOTS)) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < Math.min(buttons.length, DdzTableEntity.BUTTON_SLOTS); i++) {
            if (buttons[i] != 0) {
                count++;
            }
        }
        if (buttons[k] == 0) {
            return 0;
        }
        int acc = 0;
        for (int i = 0; i < k; i++) {
            if (buttons[i] != 0) {
                acc++;
            }
        }
        return (acc - (count - 1) / 2.0) * BUTTON_GAP;
    }

    /** 手牌第 count 张在给定张数下的横向间距（与模组 {@code v.pNsp} 同源）。 */
    public static double handSpacing(int count) {
        return count <= 1 ? 0 : Math.min(SPACING_MAX, SPACING_SPAN / (count - 1));
    }

    /**
     * 命中点的横向坐标 u 是否落在该牌的<b>可见条带</b>内（与 UNO 同规则）。
     *
     * <p><b>规则（用户 10-24 两次真机截图圈定）</b>：手牌扇形「右压左」叠放
     * （逐张 0.01 的 y 微抬让更右的牌画在上面，最右一张完整可见），所以每张牌的
     * 可选区域 = 它<b>露出来的那条</b>：左缘是自己的左缘 {@code -CARD_HALF_U}，
     * 右缘被右邻裁掉 = 右邻的左缘 {@code spacing - CARD_HALF_U}；
     * 只有<b>最右一张</b>（右侧无遮挡）认整张牌。余量只加在整排的最左缘 / 最右缘
     * （那里没有邻居；条带边界严丝合缝，中间加余量只会把点击送给错误的邻牌）。</p>
     *
     * <p>同一水平点只会被一张牌接受，接受集合天然唯一，无须再靠 |u| 或 t 仲裁。
     * ⚠️ 历史教训：|u| 主判据以牌心为中心，而可见条并不以牌心为中心（20 张时
     * 牌心本身都被右邻遮住），会让每张牌的选中区偏移半条以上；t 主判据在整牌
     * 矩形下永远选中右邻。两种写法 10-24 都在真机上翻过车。</p>
     */
    private static boolean inVisibleStrip(double u, int idx, int count, double spacing) {
        double uLo = idx == 0 ? -CARD_HALF_U - CARD_PICK_PAD_U : -CARD_HALF_U;
        double uHi = idx == count - 1 ? CARD_HALF_U + CARD_PICK_PAD_U : spacing - CARD_HALF_U;
        return u >= uLo && u <= uHi;
    }

    /** 择优：t 严格更小胜；平局比类别优先级（手牌 &gt; 按钮），同类比槽位。 */
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
     * @param ux/uy/uz 矩形 U 轴（单位向量），halfU 为半宽
     * @param vx/vy/vz 矩形 V 轴（单位向量），halfV 为半高
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
}
