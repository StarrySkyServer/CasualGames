package com.tabletopgames.game.ddz;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 一局斗地主的纯逻辑状态机（addon 回合状态的移植，不依赖 Nukkit）。
 * <p>
 * 阶段流转：
 * <pre>
 * BIDDING(叫地主 call / 抢地主 rob  **或**  叫分 1/2/3) → DOUBLING → PLAYING → FINISHED
 *        └─ 三家都不叫 / 都不叫分 → REDEAL（重新发牌，回到 BIDDING）
 * </pre>
 *
 * <p><b>两种定地主方式</b>（每桌可配，见 {@code DdzBidMode}）：</p>
 * <ul>
 *   <li><b>叫抢制</b>（{@link BidStage#CALLING} → {@link BidStage#ROBBING}）：
 *       第一个叫的人进入抢阶段，其余两家依次抢；倍数 = 2^抢中次数。</li>
 *   <li><b>叫分制</b>（{@link BidStage#SCORING}）：三家依次叫 0/1/2/3 分，
 *       叫分必须高于当前最高分；有人叫到 3 分立刻定地主；三家都不叫 → 重发。
 *       倍数 = 最高叫分（1~3）。</li>
 * </ul>
 * 关键规则（与 addon 对齐）：
 * <ul>
 *   <li>叫抢制：三家依次「叫地主 / 不叫」；第一个叫的人进入抢阶段，其余两家
 *       依次「抢地主 / 不抢」；三家都不叫 → 重发；倍数 = 2^抢中次数。</li>
 *   <li>地主确定后立即拿 3 张底牌，进入加倍（每人 1/2/4）。</li>
 *   <li>出牌：地主首出；连续两家不出回到上一手出牌者自由出；炸弹/王炸 ×2；
 *       春天（两农民 0 出牌）×2。</li>
 * </ul>
 * <p>
 * <b>★ 与模组的两处刻意差异（用户 10-19 决定）：</b>
 * <ol>
 *   <li><b>不做反春</b>（模组未实现，用户明确「模组没实现的先不做」）：
 *       {@link Result#counterSpring} 恒为 {@code false}，不产生额外倍数。</li>
 *   <li><b>倍数有硬上限</b>（{@code config.getMaxMultiplier()}，默认 1024）：
 *       每次翻倍后立刻截断，防止「叫抢 2^n × 多枚炸弹」指数炸档。</li>
 * </ol>
 * 本类只管状态与合法性，超时/Bot/音效/实体由 {@code DdzTable} 驱动。
 */
public class DdzRound {

    public enum Phase {
        BIDDING, DOUBLING, PLAYING, FINISHED, REDEAL
    }

    /** 叫地主子阶段（BIDDING 内）。 */
    public enum BidStage {
        /** 叫地主：三家依次表态，第一个叫的人进入抢阶段。 */
        CALLING,
        /** 抢地主：其余两家依次表态，最后一个抢的人当地主。 */
        ROBBING,
        /** 叫分：三家依次叫 0/1/2/3 分，最高分当地主（与叫抢制互斥）。 */
        SCORING
    }

    public static final class Result {
        public int winnerSeat;
        /** {@code landlord} 或 {@code peasant}。 */
        public String winnerSide;
        public int multiplier;
        public boolean spring;
        /**
         * 反春标记。<b>本插件恒为 false</b> —— 模组未实现反春，用户决定不做。
         * 保留字段是为了将来要加时不用改结算与前端的数据结构。
         */
        public boolean counterSpring;
        /** 每座位的金额变化（正 = 收钱，负 = 输钱；已含加倍因子，免费场全 0）。 */
        public double[] moneyDelta;
    }

    private final DdzSettingsView config;
    private final Random random;

    private Phase phase = Phase.BIDDING;
    private BidStage bidStage = BidStage.CALLING;

    /** 每座位手牌（升序）。 */
    private final List<Integer>[] hands = new List[3];
    private List<Integer> bottomCards;

    private int landlord = -1;
    private int currentSeat;
    /** 叫地主起点（每局随机 0..2）。 */
    private int firstSeat;

    // 叫抢制
    private int bidActionCount = 0;
    private int callCandidate = -1;
    private int robActionCount = 0;
    private boolean[] bidDone = new boolean[3];

    // 叫分制（2026-10-24）
    /** true = 叫分制（不叫 / 1分 / 2分 / 3分），false = 模组的叫抢制。 */
    private final boolean scoreBidding;
    /** 当前最高叫分（0 = 还没人叫）。 */
    private int highestBid = 0;
    /** 本局是否开启癞子玩法（地赖子）。 */
    private final boolean wildEnabled;
    /** 本局是否叠加「天癞子」（天地癞子模式）。 */
    private final boolean heavenWild;
    /**
     * 天癞子点数（3..15）；0 = 未开启或还没发牌。
     *
     * <p>★ 2026-10-25 用户要求：天癞子在**发牌前**就抽定，所以整局（含叫牌阶段）
     * 都是固定的，客户端从发牌那一刻就能在底牌区右侧看到它。</p>
     */
    private int heavenRank = 0;
    /** 本局地赖子点数（3..15）；0 = 还没定（叫牌阶段）或未开启。 */
    private int wildRank = 0;
    /** 当前最高叫分的座位（−1 = 还没人叫）。 */
    private int highestBidSeat = -1;

    // 加倍
    private final Integer[] doubleFactors = {null, null, null};

    // 出牌
    private DdzPattern lastPlay;
    private int lastPlaySeat = -1;
    private int consecutivePasses = 0;
    private final int[] playCounts = {0, 0, 0};
    /**
     * 每张牌「被打出时当成的点数」（0 = 没被替换 / 还没打出）。
     *
     * <p>★ 2026-10-26 用户：手里的癞子 3 打出去后牌面还是「金 3」，看不出当了几。
     * 出牌区要把牌面换成「金 &lt;当的点数&gt;」、左下角标灰色原点数 —— 数据来源就是这里。</p>
     */
    private final int[] wildAsOf = new int[DdzCards.COUNT];
    private int multiplier = 1;
    private Result result;

    /** 发牌历史（用于 debug/日志）。 */
    private String lastDealInfo = "";

    public DdzRound(DdzSettingsView config, Random random) {
        this(config, random, false, false);
    }

    /**
     * @param scoreBidding true = 叫分制（1/2/3 分），false = 叫抢制（模组默认）
     */
    public DdzRound(DdzSettingsView config, Random random, boolean scoreBidding) {
        this(config, random, scoreBidding, false);
    }

    /**
     * @param scoreBidding true = 叫分制（1/2/3 分），false = 叫抢制（模组默认）
     * @param wildEnabled  true = 癞子玩法：<b>地主确定之后</b>随机抽一个点数当癞子
     */
    public DdzRound(DdzSettingsView config, Random random, boolean scoreBidding,
                    boolean wildEnabled) {
        this(config, random, scoreBidding, wildEnabled, false);
    }

    /**
     * @param scoreBidding true = 叫分制（1/2/3 分），false = 叫抢制（模组默认）
     * @param wildEnabled  true = 地赖子玩法：<b>地主确定之后</b>随机抽一个点数当地赖子
     * @param heavenWild   true = 天地癞子：<b>发牌前</b>先抽一个天癞子，
     *                     定地主后再抽一个**不同于天癞子**的地赖子
     */
    @SuppressWarnings("unchecked")
    public DdzRound(DdzSettingsView config, Random random, boolean scoreBidding,
                    boolean wildEnabled, boolean heavenWild) {
        this.config = config;
        this.random = random;
        this.scoreBidding = scoreBidding;
        this.wildEnabled = wildEnabled || heavenWild;
        this.heavenWild = heavenWild;
    }

    // ------------------------------------------------------------------
    // 开局
    // ------------------------------------------------------------------

    /** 发牌并进入叫地主阶段。返回是否成功（重发场景也返回 true，状态已重置）。 */
    public boolean deal() {
        // ★ 天癞子：**发牌前**抽定（用户 2026-10-25 指定）。
        //   抽在 3..15（3 到 2）之间，不含大小王 —— 癞子不能当王。
        heavenRank = heavenWild ? 3 + random.nextInt(13) : 0;
        List<Integer> deck = DdzCards.deal(DdzCards.DealMode.CLASSIC, random);
        DdzCards.HandDeal deal = DdzCards.split(deck);
        hands[0] = deal.seat0();
        hands[1] = deal.seat1();
        hands[2] = deal.seat2();
        bottomCards = deal.bottomCards();
        landlord = -1;
        bidActionCount = 0;
        callCandidate = -1;
        robActionCount = 0;
        bidDone = new boolean[3];
        doubleFactors[0] = doubleFactors[1] = doubleFactors[2] = null;
        lastPlay = null;
        lastPlaySeat = -1;
        consecutivePasses = 0;
        playCounts[0] = playCounts[1] = playCounts[2] = 0;
        // 新一局：上一局的「癞子当值」必须清掉，否则同点数的牌一发下来就顶着旧标记
        java.util.Arrays.fill(wildAsOf, 0);
        multiplier = 1;
        result = null;
        highestBid = 0;
        highestBidSeat = -1;
        wildRank = 0;
        firstSeat = random.nextInt(3);
        currentSeat = firstSeat;
        phase = Phase.BIDDING;
        bidStage = scoreBidding ? BidStage.SCORING : BidStage.CALLING;
        lastDealInfo = "firstSeat=" + firstSeat + (scoreBidding ? " scoreMode" : "");
        return true;
    }

    public Phase getPhase() {
        return phase;
    }

    public BidStage getBidStage() {
        return bidStage;
    }

    /** 本局是否叫分制。 */
    public boolean isScoreBidding() {
        return scoreBidding;
    }

    /** 本局是否开启癞子玩法。 */
    public boolean isWildEnabled() {
        return wildEnabled;
    }

    /** 本局是否叠加天癞子（天地癞子模式）。 */
    public boolean isHeavenWildEnabled() {
        return heavenWild;
    }

    /**
     * 天癞子点数（3=3 … 15=2）；{@code 0} = 未开启。
     * <p>发牌前就抽定，客户端用 {@code tabletopgame:playing_card_wild_heaven} 属性接收。</p>
     */
    public int getHeavenRank() {
        return heavenRank;
    }

    /**
     * 本局地赖子点数（3=3 … 15=2）；{@code 0} = 未开启，或还没定地主。
     * <p>客户端用 {@code tabletopgame:playing_card_wild} 属性接收，决定哪 4 张牌换成金版。</p>
     */
    public int getWildRank() {
        return wildRank;
    }

    /**
     * 天癞子那 4 张牌「被打出时当成的点数」（5 位一张 × 4 = 20 位）。
     *
     * <p>2026-10-26：出牌区要把癞子牌面换成「金 &lt;当的点数&gt;」、左下角标灰色原点数，
     * 所以要把 {@link #wildAsOf} 里属于天癞子点数的 4 张打成一份给客户端。</p>
     */
    public int getWildAsHeaven() {
        return packWildAs(heavenRank);
    }

    /** 地癞子那 4 张牌的当值，格式同 {@link #getWildAsHeaven()}。 */
    public int getWildAsEarth() {
        return packWildAs(wildRank);
    }

    /**
     * 单张牌「打出去时当成了几点」（0 = 没记，按它自己的点数）。
     *
     * <p>出牌区排序要用（2026-10-27 用户 #1：同点数的癞子排在正常牌左边）。</p>
     */
    public int getWildAs(int id) {
        return id >= 0 && id < wildAsOf.length ? wildAsOf[id] : 0;
    }

    private int packWildAs(int rank) {
        if (rank < 3 || rank > 15) {
            return 0;
        }
        int base = (rank - 3) * 4;
        int v = 0;
        for (int j = 0; j < 4; j++) {
            v |= (wildAsOf[base + j] & 31) << (5 * j);
        }
        return v;
    }

    /** 叫分制下当前最高叫分（0 = 还没人叫）；叫抢制恒为 0。 */
    public int getHighestBid() {
        return highestBid;
    }

    /** 叫分制下当前最高叫分的座位；−1 = 还没人叫。 */
    public int getHighestBidSeat() {
        return highestBidSeat;
    }

    public int getCurrentSeat() {
        return currentSeat;
    }

    public int getLandlord() {
        return landlord;
    }

    public int getMultiplier() {
        return multiplier;
    }

    public List<Integer> getHand(int seat) {
        return hands[seat];
    }

    public List<Integer> getBottomCards() {
        return bottomCards;
    }

    public DdzPattern getLastPlay() {
        return lastPlay;
    }

    public int getLastPlaySeat() {
        return lastPlaySeat;
    }

    public int getConsecutivePasses() {
        return consecutivePasses;
    }

    public int[] getPlayCounts() {
        return playCounts;
    }

    public Integer[] getDoubleFactors() {
        return doubleFactors;
    }

    public Result getResult() {
        return result;
    }

    public int getFirstSeat() {
        return firstSeat;
    }

    public String getLastDealInfo() {
        return lastDealInfo;
    }

    /** 当前轮到的座位是否必须出牌（首出不能不出）。 */
    public boolean isLeading() {
        return lastPlay == null;
    }

    // ------------------------------------------------------------------
    // 叫抢制（叫地主 → 抢地主）
    // ------------------------------------------------------------------

    /**
     * 叫地主（call/rob 模式第一阶段）。
     *
     * @return null = 成功
     */
    public String callLandlord(int seat, boolean call) {
        if (phase != Phase.BIDDING) {
            return "现在不是叫地主阶段";
        }
        if (bidStage != BidStage.CALLING) {
            return "当前不是叫地主阶段";
        }
        if (seat != currentSeat) {
            return "还没轮到你进行叫地主操作";
        }
        bidDone[seat] = true;
        bidActionCount++;
        if (call) {
            callCandidate = seat;
            bidStage = BidStage.ROBBING;
            // 抢阶段从叫地主者的逆时针下家开始；跳过叫牌阶段已说「不叫」的人。
            // 如果两个人都已经「不叫」，没人有资格抢 ⇒ 发起者直接当地主。
            int next = firstEligibleRobber(nextSeat(seat));
            if (next < 0) {
                becomeLandlord(callCandidate, robCountToMultiplier(0));
            } else {
                currentSeat = next;
            }
            return null;
        }
        if (bidActionCount >= 3) {
            // 三家都不叫 → 重发
            phase = Phase.REDEAL;
            return null;
        }
        currentSeat = nextSeat(currentSeat);
        return null;
    }

    /**
     * 抢地主。
     *
     * @param rob true=抢，false=不抢
     * @return null = 成功
     */
    public String robLandlord(int seat, boolean rob) {
        if (phase != Phase.BIDDING || bidStage != BidStage.ROBBING) {
            return "当前不能抢地主";
        }
        if (seat != currentSeat) {
            return "还没轮到你进行叫地主操作";
        }
        bidDone[seat] = true;
        bidActionCount++;
        if (rob) {
            robActionCount++;
            callCandidate = seat; // 最后抢的人是地主候选
        }
        // 需要三人全部行动（叫者 + 两个被轮到的；叫牌阶段已「不叫」的人不参与）
        if (bidActionCount >= 3) {
            becomeLandlord(callCandidate, robCountToMultiplier(robActionCount));
            return null;
        }
        int next = firstEligibleRobber(nextSeat(currentSeat));
        if (next < 0) {
            // 有资格抢的人已经走完 ⇒ 最后抢到的（或发起者）当地主
            becomeLandlord(callCandidate, robCountToMultiplier(robActionCount));
            return null;
        }
        currentSeat = next;
        return null;
    }

    /**
     * 叫分（{@link BidStage#SCORING}）。
     *
     * <p>规则：按逆时针依次表态，叫分必须<b>严格高于</b>当前最高分；不叫记 0。
     * 有人叫到 3 分（满分）立刻定地主，不等其他人；三家都表态完则最高分当地主；
     * 三家都不叫 → {@link Phase#REDEAL}。倍数 = 最高叫分。</p>
     *
     * @param seat  座位 0..2（必须轮到）
     * @param score 0 = 不叫；1/2/3 = 叫分
     * @return null = 成功
     */
    public String bidScore(int seat, int score) {
        if (phase != Phase.BIDDING) {
            return "现在不是叫分阶段";
        }
        if (bidStage != BidStage.SCORING) {
            return "本局不是叫分定地主";
        }
        if (seat != currentSeat) {
            return "还没轮到你叫分";
        }
        if (score < 0 || score > 3) {
            return "叫分只能是 0（不叫）/ 1 / 2 / 3";
        }
        if (score != 0 && score <= highestBid) {
            return "叫分必须高于当前最高分（§e" + highestBid + "§c 分）";
        }
        bidActionCount++;
        if (score > highestBid) {
            highestBid = score;
            highestBidSeat = seat;
        }
        if (highestBid >= 3) {
            // 满分封顶：不等其他人表态，直接定地主
            becomeLandlord(highestBidSeat, highestBid);
            return null;
        }
        if (bidActionCount >= 3) {
            if (highestBidSeat < 0) {
                phase = Phase.REDEAL;
                return null;
            }
            becomeLandlord(highestBidSeat, highestBid);
            return null;
        }
        currentSeat = nextSeat(currentSeat);
        return null;
    }

    /** 地主确定：并入手牌、进入加倍阶段。 */
    private void becomeLandlord(int seat, int bidMultiplier) {
        this.landlord = seat;
        this.multiplier = Math.min(bidMultiplier, config.getMaxMultiplier());
        // 癞子玩法：**地主确定之后**才抽点数（用户 2026-10-24 指定的时机）。
        // 抽在 3..15（3 到 2）之间，不含大小王 —— 癞子不能当王。
        if (wildEnabled) {
            // 天地癞子：地赖子不能和天癞子同点数（否则等于只有一种癞子，
            // 桌上摆两张一模一样的展示牌纯属误导）。
            int r = 3 + random.nextInt(heavenRank > 0 ? 12 : 13);
            if (heavenRank > 0 && r >= heavenRank) {
                r++;
            }
            this.wildRank = r;
        }
        List<Integer> merged = new ArrayList<>(hands[seat]);
        merged.addAll(bottomCards);
        DdzCards.sortAscending(merged);
        hands[seat] = merged;
        phase = Phase.DOUBLING;
        currentSeat = seat;
        doubleFactors[0] = doubleFactors[1] = doubleFactors[2] = null;
    }

    // ------------------------------------------------------------------
    // 加倍
    // ------------------------------------------------------------------

    /**
     * 选择加倍。factor 1=不加倍 2=加倍 4=超级加倍。
     *
     * @return null = 成功
     */
    public String chooseDouble(int seat, int factor) {
        if (phase != Phase.DOUBLING) {
            return "现在是加倍阶段，请先选择不加倍、加倍或超级加倍";
        }
        if (seat != currentSeat) {
            return "还没轮到你选择加倍";
        }
        if (factor != 1 && factor != 2 && factor != 4) {
            return "操作失败，请检查当前回合和所选牌型";
        }
        doubleFactors[seat] = factor;
        // 下一个没给 factor 的座位（逆时针，与叫牌 / 出牌同向）
        int s = currentSeat;
        for (int e = 0; e < 2; e++) {
            s = nextSeat(s);
            if (doubleFactors[s] == null) {
                currentSeat = s;
                return null;
            }
        }
        // 全部完成 → 进入出牌，地主首出
        phase = Phase.PLAYING;
        currentSeat = landlord;
        lastPlay = null;
        lastPlaySeat = -1;
        consecutivePasses = 0;
        return null;
    }

    /** 加倍阶段当前座位的 factor 是否已给（用于 AI/超时默认）。 */
    public boolean hasDoubleFactor(int seat) {
        return doubleFactors[seat] != null;
    }

    // ------------------------------------------------------------------
    // 出牌
    // ------------------------------------------------------------------

    /**
     * 出一手牌。
     *
     * @param cards 选中的牌 id
     * @return null = 成功
     */
    public String play(int seat, List<Integer> cards) {
        return play(seat, cards, null);
    }

    /**
     * 出牌（可指定牌型）。
     *
     * <p>2026-10-27 用户 #2：带癞子的一手牌可能有多种解释，玩家在表单里挑完之后
     * 要按<b>挑中的那一种</b>结算（能不能压过上一手、当值标记、播报全看它），
     * 所以这里多一个 {@code forced} 参数；{@code null} = 跟以前一样自己识别。</p>
     */
    public String play(int seat, List<Integer> cards, DdzPattern forced) {
        if (phase != Phase.PLAYING) {
            return "本局已经结束";
        }
        if (seat != currentSeat) {
            return "还没轮到你出牌";
        }
        if (cards == null || cards.isEmpty()) {
            return "请先选择至少一张牌";
        }
        // 归属校验
        List<Integer> hand = hands[seat];
        List<Integer> remaining = new ArrayList<>(hand);
        for (int id : cards) {
            if (!remaining.remove(Integer.valueOf(id))) {
                return "选中的牌中有牌已经不在你的手牌里，请重新选择";
            }
        }
        DdzPattern pattern = forced != null
                ? forced
                : DdzPattern.identify(cards, heavenRank, wildRank);
        if (pattern == null) {
            if (heavenRank > 0) {
                return "所选牌不构成合法的斗地主牌型"
                        + "（天癞子 / 地赖子都可当 3~2 的任意点数，不能当王）";
            }
            return wildRank > 0
                    ? "所选牌不构成合法的斗地主牌型（地赖子可当 3~2 的任意点数，不能当王）"
                    : "所选牌不构成合法的斗地主牌型";
        }
        if (pattern.getCardCount() != cards.size()) {
            return "牌型和所选牌对不上，请重新选择";
        }
        if (lastPlay != null && !DdzPattern.beats(pattern, lastPlay)) {
            return "这手牌压不过上一手";
        }
        // 打出
        hands[seat] = remaining;
        playCounts[seat]++;
        lastPlay = pattern;
        lastPlaySeat = seat;
        consecutivePasses = 0;
        // ★ 记下「这手牌里每张癞子当成了哪个点数」（出牌区的视觉标记用）。
        //   带牌这类点数任意的位置会被 assignWildRanks 判成「当自己」，不会记录。
        for (java.util.Map.Entry<Integer, Integer> e
                : DdzPattern.assignWildRanks(cards, heavenRank, wildRank, pattern).entrySet()) {
            wildAsOf[e.getKey()] = e.getValue();
        }

        // 炸弹/王炸 ×2
        boolean bombLike = pattern.isBombLike();

        // 胜负判定
        if (remaining.isEmpty()) {
            finishRound(seat, bombLike);
            return null;
        }
        if (bombLike) {
            doubleMultiplier();
        }
        currentSeat = nextSeat(currentSeat);
        return null;
    }

    /**
     * 抢中次数 → 倍数（{@code 2^robCount}），并按配置上限截断。
     * <p>
     * 不用 {@code 1 << n}：三家局robCount ≤ 2 不会溢出，但截断必须在这里做，
     * 而 {@code 1 << n} 在 n 大时会先溢出成负数再被截断，得不到正确结果。
     */
    private int robCountToMultiplier(int robCount) {
        long v = 1L;
        for (int i = 0; i < Math.max(0, robCount); i++) {
            v *= 2L;
            if (v >= config.getMaxMultiplier()) {
                return config.getMaxMultiplier();
            }
        }
        return (int) Math.min(v, config.getMaxMultiplier());
    }

    /**
     * 不出。
     *
     * @return null = 成功
     */
    public String pass(int seat) {
        if (phase != Phase.PLAYING) {
            return "本局已经结束";
        }
        if (seat != currentSeat) {
            return "还没轮到你出牌";
        }
        if (lastPlay == null) {
            return "你是本轮首出，不能选择不出";
        }
        consecutivePasses++;
        if (consecutivePasses >= 2) {
            // 回到上一手出牌者自由出
            currentSeat = lastPlaySeat;
            lastPlay = null;
            lastPlaySeat = -1;
            consecutivePasses = 0;
            return null;
        }
        currentSeat = nextSeat(currentSeat);
        return null;
    }

    /** 结算胜负（含春天/反春）。 */
    private void finishRound(int winnerSeat, boolean bombLike) {
        // 春天：地主独赢 = 两农民一次牌都没出
        // ★ 反春（地主只出过首手）**不做** —— 模组未实现，用户明确「模组没实现的先不做」
        boolean spring = false;
        if (winnerSeat == landlord) {
            spring = true;
            for (int s = 0; s < 3; s++) {
                if (s != landlord && playCounts[s] != 0) {
                    spring = false;
                    break;
                }
            }
        }
        if (spring) {
            doubleMultiplier();
        }
        if (bombLike) {
            doubleMultiplier();
        }

        String winnerSide = winnerSeat == landlord ? "landlord" : "peasant";
        // 金额变化：线性公式（用户 10-19 定，不用模组 crAmount 对数映射）
        //   unit = baseScore × multiplier × factor[输家] × factor[赢家]
        // 免费场（baseScore 0）⇒ 全 0，不结算
        double[] delta = new double[3];
        int landlordFactor = doubleFactors[landlord] != null ? doubleFactors[landlord] : 1;
        for (int s = 0; s < 3; s++) {
            if (s == landlord) {
                continue;
            }
            int peasantFactor = doubleFactors[s] != null ? doubleFactors[s] : 1;
            double unit = config.getBaseScore() * multiplier * landlordFactor * peasantFactor;
            if ("landlord".equals(winnerSide)) {
                delta[landlord] += unit;
                delta[s] -= unit;
            } else {
                delta[landlord] -= unit;
                delta[s] += unit;
            }
        }
        Result r = new Result();
        r.winnerSeat = winnerSeat;
        r.winnerSide = winnerSide;
        r.multiplier = multiplier;
        r.spring = spring;
        r.counterSpring = false;
        r.moneyDelta = delta;
        this.result = r;
        this.phase = Phase.FINISHED;
    }

    /** 倍数翻倍并按配置上限截断（防「2^n × 多枚炸弹」指数炸档）。 */
    private void doubleMultiplier() {
        long next = (long) multiplier * 2L;
        int cap = config.getMaxMultiplier();
        multiplier = (int) Math.min(next, cap);
    }

    /**
     * 逆时针下家。
     *
     * <p>座位号是模组定的 <b>0=南、1=北、2=东</b>。俯视桌面（北上东右）时，
     * 南 → 东 → 北 才是逆时针，即 {@code (seat + 2) % 3}；
     * 原来的 {@code (seat + 1) % 3}（南 → 北 → 东）是<b>顺时针</b>，
     * 真机表现为「叫牌 / 加倍 / 出牌全都反着走」（用户 2026-10-22 反馈）。</p>
     */
    private static int nextSeat(int seat) {
        return (seat + 2) % 3;
    }

    /**
     * 从 {@code from} 起逆时针找第一个<b>还没表态</b>的座位（可抢地主）。
     * <p>叫牌阶段已经选过「不叫」的座位 {@code bidDone=true}，按规则不能抢；
     * 返回 -1 表示没有有资格的抢家（此时发起者直接当地主）。</p>
     */
    private int firstEligibleRobber(int from) {
        int s = from;
        for (int i = 0; i < 3; i++) {
            if (!bidDone[s]) {
                return s;
            }
            s = nextSeat(s);
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 超时默认动作（addon oD）
    // ------------------------------------------------------------------

    /**
     * 当前座位的超时默认动作。
     *
     * @return 语义动作：BIDDING 阶段返回空 list 表示「过」，PLAYING 阶段返回应打出的牌
     * （首出打最小单张，跟牌返回 null = 不出）
     */
    public List<Integer> defaultAction(int seat) {
        if (phase == Phase.BIDDING) {
            return List.of(); // 不叫 / 不抢
        }
        if (phase == Phase.DOUBLING) {
            return List.of(); // factor=1
        }
        if (phase == Phase.PLAYING) {
            if (lastPlay == null) {
                // 首出：打最小牌
                List<Integer> hand = hands[seat];
                if (hand.isEmpty()) {
                    return List.of();
                }
                int minId = hand.get(0);
                for (int id : hand) {
                    if (DdzCards.rank(id) < DdzCards.rank(minId)) {
                        minId = id;
                    }
                }
                return new ArrayList<>(List.of(minId));
            }
            return null; // 跟牌默认不出
        }
        return List.of();
    }
}
