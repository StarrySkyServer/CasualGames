package com.tabletopgames.ai;

import com.tabletopgames.game.UnoDeck;

import java.util.List;
import java.util.Random;

/**
 * UNO 人机（<b>两档</b>：初级 / 高级）。评分模型移植自开源项目 AwSkies/uno_JavaML —— 该项目用遗传算法
 * 训出一组权重，对每张合法牌算分取最高（{@code as_UnoPlayer}）。
 *
 * <p>决策只有两种：打某张牌（野牌附带颜色）或摸牌。摸牌之后由牌桌逻辑决定是否打出刚摸到的牌，
 * 本类用 {@link #shouldPlayDrawn} 给出建议。
 *
 * <ul>
 *   <li>{@link #LEVEL_EASY} 初级：在合法牌里<b>随机</b>挑一张，选色也随机（纯陪练）。
 *       托管 / 离线代打也走这一档，见 {@code UnoTable}。</li>
 *   <li>{@link #LEVEL_MASTER} 高级：按权重打分并启用<b>攻击</b>项 —— 下家（或手牌最少者）快出完时
 *       优先甩 +4 / +2 / 跳过 / 反转，并避开「手牌最少那个人最近叫的颜色」；选色还会扣掉
 *       被对手用野牌叫过的颜色。</li>
 * </ul>
 */
public final class UnoAi {

    /*
     * 2026-10-22 用户要求：删掉「中级」这一档，只留初级 / 高级。所以档位重新编号为 0 / 1，
     * 高级从 2 变成 1 —— 牌桌的 bots[] 不落盘（见 UnoManager 存档），不存在旧值迁移问题。
     */
    public static final int LEVEL_EASY = 0;
    public static final int LEVEL_MASTER = 1;

    /**
     * 评分权重（12 个参数，语义见 AwSkies 的 {@code as_UnoPlayer}）。
     *
     * <p>{@link #BASELINE} = 该项目手调的基准值（其 {@code values/baseline.csv}）。三档共用同一张表：
     * 初级不用权重（纯随机），高级用满全部 12 项。
     */
    public record Weights(
            double baseNumberPoints,
            double numberValueCoefficient,
            double mostHeldColorPoints,
            double colorRatioCoefficient,
            double significantLeadRatio,
            double playColorDislikedByHighestPlayerPoints,
            double reversePoints,
            double skipPoints,
            double drawTwoPoints,
            double wildDrawFourPoints,
            double heldColorCoefficient,
            double calledColorPoints) {

        /** 手调基准值（AwSkies {@code baseline.csv}：1,1,1,4,0.5,1,5,5,6,10,3,1）。 */
        public static final Weights BASELINE =
                new Weights(1, 1, 1, 4, 0.5, 1, 5, 5, 6, 10, 3, 1);
    }

    /** 生效的权重表（三档共用；要换权重只改这一行）。 */
    private static final Weights W = Weights.BASELINE;

    /**
     * 出牌顺序上「后 3 家」的局面信息（下标 0 = 下家）。只有高级档会用到。
     *
     * @param handSizes    后 3 家手牌张数（未知 = -1）
     * @param calledColors 后 3 家最近一次叫的颜色（-1 = 没叫过）
     */
    public record Context(int[] handSizes, int[] calledColors) {

        /** 无对手信息（例如开局选色时）。 */
        public static final Context EMPTY =
                new Context(new int[] {-1, -1, -1}, new int[] {-1, -1, -1});
    }

    /** 出牌建议。 */
    public record Decision(boolean draw, int cardId, int chosenColor) {

        public static Decision drawCard() {
            return new Decision(true, -1, -1);
        }

        public static Decision play(int cardId, int color) {
            return new Decision(false, cardId, color);
        }
    }

    private final int level;
    private final Random random;

    public UnoAi(int level, Random random) {
        this.level = Math.max(LEVEL_EASY, Math.min(LEVEL_MASTER, level));
        this.random = random == null ? new Random() : random;
    }

    public int level() {
        return level;
    }

    public static String levelName(int level) {
        return level == LEVEL_EASY ? "初级" : "高级";
    }

    /**
     * 回合决策。
     *
     * @param hand     自己的手牌
     * @param playable {@link com.tabletopgames.game.UnoGame#playableCards(int)} 的结果
     * @param ctx      后 3 家局面信息（只有高级档使用；其余档可传 {@link Context#EMPTY}）
     */
    public Decision decide(List<Integer> hand, List<Integer> playable, Context ctx) {
        if (playable.isEmpty()) {
            return Decision.drawCard();
        }
        if (level == LEVEL_EASY) {
            return play(playable.get(random.nextInt(playable.size())), hand, ctx);
        }
        boolean aggressive = level == LEVEL_MASTER;
        int chosen = playable.get(0);
        double best = Double.NEGATIVE_INFINITY;
        for (int id : playable) {
            double points = cardPoints(id, hand, aggressive, ctx);
            if (points > best) {
                best = points;
                chosen = id;
            }
        }
        return play(chosen, hand, ctx);
    }

    /** 组装出牌建议：野牌自动带上选好的颜色。 */
    private Decision play(int cardId, List<Integer> hand, Context ctx) {
        return Decision.play(cardId, UnoDeck.isWild(cardId) ? chooseColor(hand, ctx) : -1);
    }

    /**
     * 单张牌的倾向分（越高越优先），对应 AwSkies {@code as_UnoPlayer.play} 的打分段。
     *
     * @param aggressive 是否启用「攻击」项（高级档）
     */
    private double cardPoints(int id, List<Integer> hand, boolean aggressive, Context ctx) {
        int kind = UnoDeck.kind(id);
        boolean wild = UnoDeck.isWild(id);
        int size = Math.max(1, hand.size());
        int[] counts = colorCounts(hand);

        // ---- 被动：优先清掉高数字、优先消化手里多的颜色 ----
        double points = 0;
        if (kind == UnoDeck.KIND_NUMBER) {
            points += W.baseNumberPoints() + W.numberValueCoefficient() * (UnoDeck.number(id) / 9.0);
        }
        if (!wild) {
            int color = UnoDeck.color(id);
            points += W.colorRatioCoefficient() * (counts[color] / (double) size);
            if (color == argMax(counts)) {
                points += W.mostHeldColorPoints();
            }
        }
        if (!aggressive) {
            return points;
        }

        // ---- 攻击：只在对手构成威胁时才给攻击牌加分 ----
        int[] up = ctx.handSizes();
        int[] called = ctx.calledColors();
        int threat = -1;
        for (int i = 0; i < up.length; i++) {
            if (up[i] >= 0 && (threat < 0 || up[i] < up[threat])) {
                threat = i;
            }
        }
        if (threat < 0) {
            return points;              // 没有任何对手信息（例如 P2 局以外仍未知）→ 不启用攻击项
        }
        // 手牌最少的那个人（最接近获胜）快出完了，或者领先我们一半以上 → 打一张他叫色之外的牌
        if ((up[threat] < 4 || up[threat] / (double) size < W.significantLeadRatio())
                && !wild && UnoDeck.color(id) != called[threat]) {
            points += W.playColorDislikedByHighestPlayerPoints();
        }
        // 下家构成威胁 → 攻击牌优先（+4 > +2 > 跳过 > 反转 的权重由权重表给出）
        if (up[0] >= 0 && (up[0] / (double) size < W.significantLeadRatio() || up[0] < 4)) {
            points += switch (kind) {
                case UnoDeck.KIND_REVERSE -> W.reversePoints();
                case UnoDeck.KIND_SKIP -> W.skipPoints();
                case UnoDeck.KIND_DRAW_TWO -> W.drawTwoPoints();
                case UnoDeck.KIND_WILD_DRAW_FOUR -> W.wildDrawFourPoints();
                default -> 0;
            };
        }
        return points;
    }

    /**
     * 野牌选色，对应 AwSkies {@code as_UnoPlayer.callColor}：持色比例高者优先，
     * 被对手最近叫过的颜色扣分。初级档纯随机。
     */
    public int chooseColor(List<Integer> hand, Context ctx) {
        int colors = UnoDeck.COLOR_COUNT;
        if (level == LEVEL_EASY) {
            return random.nextInt(colors);
        }
        int size = Math.max(1, hand.size());
        int[] counts = colorCounts(hand);
        double[] points = new double[colors];
        int total = 0;
        for (int c = 0; c < colors; c++) {
            points[c] = W.heldColorCoefficient() * (counts[c] / (double) size);
            total += counts[c];
        }
        for (int c : ctx.calledColors()) {
            if (c >= 0 && c < colors) {
                points[c] -= W.calledColorPoints();
            }
        }
        if (total == 0) {
            return random.nextInt(colors);   // 手牌全是野牌 → 没有偏好，随机
        }
        int best = 0;
        for (int c = 1; c < colors; c++) {
            if (points[c] > points[best]) {
                best = c;
            }
        }
        return best;
    }

    /**
     * 摸到牌之后要不要立刻打出。
     * <p>初级档有牌就打；高级档会把野牌留到后面（手牌还多时）。
     */
    public boolean shouldPlayDrawn(int drawnCard, List<Integer> hand, boolean legal) {
        if (!legal || drawnCard < 0) {
            return false;
        }
        if (level == LEVEL_EASY) {
            return true;
        }
        if (UnoDeck.isWild(drawnCard) && hand.size() > 3) {
            return false;
        }
        return true;
    }

    /** 各色张数（不含野牌）：下标 = 颜色 0..3。 */
    private static int[] colorCounts(List<Integer> hand) {
        int[] counts = new int[UnoDeck.COLOR_COUNT];
        for (int id : hand) {
            if (!UnoDeck.isWild(id)) {
                counts[UnoDeck.color(id)]++;
            }
        }
        return counts;
    }

    /** 最大值下标（并列取更靠前者）。 */
    private static int argMax(int[] arr) {
        int index = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[index]) {
                index = i;
            }
        }
        return index;
    }
}
