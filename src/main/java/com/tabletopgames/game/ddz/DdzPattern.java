package com.tabletopgames.game.ddz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 牌型识别与比较（addon {@code Ia/Ga/Wa} 的移植）。
 * <p>
 * 牌型一览（kind 与 addon 对齐）：
 * <pre>
 * single            单张
 * pair              对子
 * triple            三张
 * triple_with_single 三带一（4 张）
 * triple_with_pair  三带二（5 张）
 * straight          顺子（≥5 张连续单，≤A 不含 2/王）
 * consecutive_pairs 连对（≥3 对连续）
 * airplane          飞机不带（≥2 组连续三张）
 * airplane_with_singles 飞机带单（每组三张带 1 单，总长 4n）
 * airplane_with_pairs   飞机带对（每组三张带 1 对，总长 5n）
 * four_with_two_singles 四带二单（6 张，含四张）
 * four_with_two_pairs   四带两对（8 张）
 * bomb              炸弹（四张同点）
 * rocket             王炸（双王）
 * </pre>
 * 比较规则：rocket > bomb > 同 kind 且同结构（cardCount + chainLength）比 mainRank。
 */
public final class DdzPattern {

    public enum Kind {
        SINGLE, PAIR, TRIPLE, TRIPLE_WITH_SINGLE, TRIPLE_WITH_PAIR,
        STRAIGHT, CONSECUTIVE_PAIRS,
        AIRPLANE, AIRPLANE_WITH_SINGLES, AIRPLANE_WITH_PAIRS,
        FOUR_WITH_TWO_SINGLES, FOUR_WITH_TWO_PAIRS,
        BOMB, ROCKET
    }

    private final Kind kind;
    /** 参与牌型的牌总数。 */
    private final int cardCount;
    /** 顺子/连对/飞机的链长（连续组数）；其余为 1。 */
    private final int chainLength;
    /** 主 rank（顺子=最大 rank，带牌=三张/四张的 rank，单对=其 rank）。 */
    private final int mainRank;

    private DdzPattern(Kind kind, int cardCount, int chainLength, int mainRank) {
        this.kind = kind;
        this.cardCount = cardCount;
        this.chainLength = chainLength;
        this.mainRank = mainRank;
    }

    public Kind getKind() {
        return kind;
    }

    public int getCardCount() {
        return cardCount;
    }

    public int getChainLength() {
        return chainLength;
    }

    public int getMainRank() {
        return mainRank;
    }

    public boolean isBomb() {
        return kind == Kind.BOMB;
    }

    public boolean isRocket() {
        return kind == Kind.ROCKET;
    }

    /** 炸弹类（炸弹或王炸）：可压一切非炸弹牌型。 */
    public boolean isBombLike() {
        return kind == Kind.BOMB || kind == Kind.ROCKET;
    }

    // ------------------------------------------------------------------
    // rank 统计辅助
    // ------------------------------------------------------------------

    /** 统计每个 rank 的张数。 */
    private static Map<Integer, Integer> countRanks(List<Integer> cards) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int id : cards) {
            counts.merge(DdzCards.rank(id), 1, Integer::sum);
        }
        return counts;
    }

    /** 统计值列表（各 rank 张数）按升序排列。 */
    private static List<Integer> sortedCounts(Map<Integer, Integer> counts) {
        List<Integer> list = new ArrayList<>(counts.values());
        list.sort(Integer::compareTo);
        return list;
    }

    /** rank 集合是否为从 fromStart 开始、长度 len 的连续段（不含 2/王）。 */
    private static boolean isChain(Map<Integer, Integer> counts, int expectedCount,
                                   int chainLength, int maxAllowedRank) {
        List<Integer> ranks = new ArrayList<>(counts.keySet());
        ranks.sort(Integer::compareTo);
        if (ranks.size() != chainLength) {
            return false;
        }
        int start = ranks.get(0);
        if (start + chainLength - 1 > maxAllowedRank) {
            return false;
        }
        for (int i = 0; i < chainLength; i++) {
            Integer c = counts.get(start + i);
            if (c == null || c != expectedCount) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 识别
    // ------------------------------------------------------------------

    /**
     * 识别一组牌的牌型；非法组合返回 {@code null}。
     *
     * @param cards 牌 id 列表（任意顺序）
     */
    public static DdzPattern identify(List<Integer> cards) {
        if (cards == null || cards.isEmpty() || cards.size() > DdzCards.COUNT) {
            return null;
        }
        // 去重校验：同 id 不允许出现两次
        if (cards.stream().distinct().count() != cards.size()) {
            return null;
        }
        for (int id : cards) {
            if (!DdzCards.isValid(id)) {
                return null;
            }
        }
        int n = cards.size();
        Map<Integer, Integer> counts = countRanks(cards);

        // 王炸：仅大小王两张
        if (n == 2 && counts.containsKey(DdzCards.RANK_SMALL_JOKER)
                && counts.containsKey(DdzCards.RANK_BIG_JOKER)) {
            return new DdzPattern(Kind.ROCKET, 2, 1, DdzCards.RANK_BIG_JOKER);
        }

        // 炸弹：单一 rank 四张
        if (n == 4 && counts.size() == 1) {
            return new DdzPattern(Kind.BOMB, 4, 1, counts.keySet().iterator().next());
        }

        // 单/对/三张
        if (n == 1) {
            return new DdzPattern(Kind.SINGLE, 1, 1, DdzCards.rank(cards.get(0)));
        }
        if (n == 2 && counts.size() == 1) {
            return new DdzPattern(Kind.PAIR, 2, 1, counts.keySet().iterator().next());
        }
        if (n == 3 && counts.size() == 1) {
            return new DdzPattern(Kind.TRIPLE, 3, 1, counts.keySet().iterator().next());
        }

        // 三带一 / 三带二
        if (n == 4) {
            // 含三张（排除已判定的炸弹）
            Integer triple = findRankWith(counts, 3);
            if (triple != null) {
                return new DdzPattern(Kind.TRIPLE_WITH_SINGLE, 4, 1, triple);
            }
        }
        if (n == 5) {
            Integer triple = findRankWith(counts, 3);
            if (triple != null) {
                // 剩余须为对子
                Map<Integer, Integer> rest = new HashMap<>(counts);
                rest.remove(triple);
                if (rest.size() == 1 && rest.values().iterator().next() == 2) {
                    return new DdzPattern(Kind.TRIPLE_WITH_PAIR, 5, 1, triple);
                }
            }
        }

        // 顺子：≥5 全单连续，最大 ≤ A(14)
        if (n >= 5 && counts.size() == n) {
            if (isChain(counts, 1, n, 14)) {
                int max = counts.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();
                return new DdzPattern(Kind.STRAIGHT, n, n, max);
            }
        }

        // 连对：≥6 偶数、全对、≥3 对连续，最大 ≤ A
        if (n >= 6 && n % 2 == 0) {
            int pairs = n / 2;
            if (counts.size() == pairs && allEqual(counts, 2)
                    && isChain(counts, 2, pairs, 14)) {
                int max = counts.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();
                return new DdzPattern(Kind.CONSECUTIVE_PAIRS, n, pairs, max);
            }
        }

        // 飞机（不带）：≥6 %3==0 全三张连续
        if (n >= 6 && n % 3 == 0) {
            int groups = n / 3;
            if (counts.size() == groups && allEqual(counts, 3)
                    && isChain(counts, 3, groups, 14)) {
                int max = counts.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();
                return new DdzPattern(Kind.AIRPLANE, n, groups, max);
            }
        }

        // 飞机带单：总长 4n（n≥2 组）
        if (n >= 8 && n % 4 == 0) {
            DdzPattern p = tryAirplane(counts, n, 4, Kind.AIRPLANE_WITH_SINGLES);
            if (p != null) {
                return p;
            }
        }
        // 飞机带对：总长 5n（n≥2 组）
        if (n >= 10 && n % 5 == 0) {
            DdzPattern p = tryAirplane(counts, n, 5, Kind.AIRPLANE_WITH_PAIRS);
            if (p != null) {
                return p;
            }
        }

        // 四带二单：6 张含四张
        if (n == 6) {
            Integer quad = findRankWith(counts, 4);
            if (quad != null) {
                return new DdzPattern(Kind.FOUR_WITH_TWO_SINGLES, 6, 1, quad);
            }
        }
        // 四带两对：8 张含四张 + 两对
        if (n == 8) {
            Integer quad = findRankWith(counts, 4);
            if (quad != null) {
                Map<Integer, Integer> rest = new HashMap<>(counts);
                rest.remove(quad);
                if (rest.size() == 2 && allEqual(rest, 2)) {
                    return new DdzPattern(Kind.FOUR_WITH_TWO_PAIRS, 8, 1, quad);
                }
            }
        }

        return null;
    }

    /**
     * 带癞子的牌型识别（2026-10-24 新增，第二种定地主方式配套的玩法）。
     *
     * <p>规则（用户确认）：</p>
     * <ul>
     *   <li>癞子可以当 <b>3~2 的任意点数</b>，<b>不能当大小王</b> —— 所以造不出王炸，
     *       也不会出现「癞子 + 大王」这种伪王炸；</li>
     *   <li>癞子<b>单出按原点数</b>算（癞子是 3，单出就是 3，不能当 2 用），
     *       避免「手上 4 张癞子 = 4 个 2」这种荒谬局面；</li>
     *   <li>一个牌型里想用几张癞子都行（最多 4 张）。</li>
     * </ul>
     *
     * <p><b>分支顺序与 {@link #identify(List)} 完全一致</b>（炸弹优先于三带一、三带二优先于顺子、
     * 飞机带单优先于四带两对……）。这一点不是形式主义：同一组牌在有癞子 / 没癞子时必须
     * 解释成同一个牌型，否则玩家会认为"判错了"。</p>
     *
     * <p>同一牌型有多种解释时取<b>主 rank 最大</b>的（对出牌者最有利）。</p>
     *
     * @param wildRank 癞子点数（3..15）；{@code <= 0} 表示这一局没有癞子
     */
    public static DdzPattern identify(List<Integer> cards, int wildRank) {
        return identify(cards, wildRank, 0);
    }

    /**
     * 双癞子识别（2026-10-25「天地癞子」）：{@code wildRank} = 天癞子，
     * {@code wildRank2} = 地赖子，两个点数都算万能牌。
     *
     * <p><b>为什么不是「认最大的那张癞子」</b>：两张癞子点数不同，手里同时握着
     * 天 5 和地 9 时，出「5 6 7 8 9」既可以把 5 当癞子补顺子、也可以把 9 当癞子，
     * 两种解释都合法且牌型相同 —— 数量才是本质，点数只影响「单出时算它自己」。
     * 所以这里只把两者都计入 {@code wild} 张数，其余分支一行没改。</p>
     *
     * @param wildRank  天癞子点数（0 = 没有）
     * @param wildRank2 地赖子点数（0 = 没有）
     */
    public static DdzPattern identify(List<Integer> cards, int wildRank, int wildRank2) {
        if (wildRank <= 0 && wildRank2 <= 0) {
            return identify(cards);
        }
        if (cards == null || cards.isEmpty() || cards.size() > DdzCards.COUNT) {
            return null;
        }
        if (cards.stream().distinct().count() != cards.size()) {
            return null;
        }
        int[] c = new int[18];
        int wild = 0;
        // 只有一张牌且它是癞子时，单出必须按**它自己的点数**结算。
        // 双癞子下不能再拿 wildRank 顶替（天 5 单出必须算 5，不是地 9）。
        int loneWildRank = 0;
        for (int id : cards) {
            if (!DdzCards.isValid(id)) {
                return null;
            }
            int r = DdzCards.rank(id);
            if (DdzCards.isWild(id, wildRank, wildRank2)) {
                wild++;
                if (cards.size() == 1) {
                    loneWildRank = r;
                }
            } else {
                c[r]++;
            }
        }
        if (wild == 0) {
            // 没有癞子参与 ⇒ 走原路径，行为逐字节一致（不打乱已有回归）
            return identify(cards);
        }
        return identifyWithWild(c, cards.size(), wild, loneWildRank);
    }

    /**
     * 算出**每张癞子牌实际被当成了哪个点数**（给「打出的癞子牌」做视觉标记）。
     *
     * <p>背景（2026-10-26 用户）：手里的癞子 3 打出去之后，牌面还是「金 3」，
     * 玩家看不出它到底当了几，只能靠自己反推牌型。所以在出牌区把牌面换成
     * 「金 &lt;当的点数&gt;」，左下角再标一个灰色的原点数。</p>
     *
     * <p>识别只关心「压不压得过」（只要主 rank），而这个方法是**逐张牌**的映射，
     * 所以单独一个函数、不塞进 {@link #identify}。</p>
     *
     * <p>规则（按牌型反推缺口，缺口从低到高依次分给癞子）：</p>
     * <ul>
     *   <li><b>单张</b>：癞子单出按原点数结算 ⇒ 不标。</li>
     *   <li><b>对子 / 三张 / 炸弹</b>（及三带二、四带二的主体）：全部当 {@code mainRank}。</li>
     *   <li><b>顺子 / 连对 / 飞机</b>：链内缺的 rank 从低到高依次分配。</li>
     *   <li><b>带牌</b>：单张带牌点数任意 ⇒ 当自己（不标）；带**对子**的牌型
     *       （三带二 / 四带两对 / 飞机带对）里，带牌区只剩一张真牌时，
     *       癞子补成那一对 ⇒ 当那张真牌的点数。</li>
     * </ul>
     *
     * <p>「当自己」的癞子不会出现在返回值里 —— 视觉上金版原点数就是对的，不必覆盖。</p>
     *
     * @param cards     本手打出的牌 id
     * @param wildRank  天癞子点数（0 = 没有）
     * @param wildRank2 地癞子点数（0 = 没有）
     * @param pattern   {@link #identify} 的结果（null 直接返回空）
     * @return 牌 id → 当的点数（只含真正改变了点数的癞子）
     */
    public static Map<Integer, Integer> assignWildRanks(List<Integer> cards, int wildRank,
                                                        int wildRank2, DdzPattern pattern) {
        Map<Integer, Integer> out = new HashMap<>();
        if (pattern == null || cards == null || cards.isEmpty()
                || (wildRank <= 0 && wildRank2 <= 0)) {
            return out;
        }
        List<Integer> wilds = new ArrayList<>();
        int[] cnt = new int[18];
        for (int id : cards) {
            if (DdzCards.isWild(id, wildRank, wildRank2)) {
                wilds.add(id);
            } else {
                cnt[DdzCards.rank(id)]++;
            }
        }
        if (wilds.isEmpty()) {
            return out;
        }
        wilds.sort(Integer::compareTo);

        int main = pattern.getMainRank();
        int chain = pattern.getChainLength();
        Kind kind = pattern.getKind();
        // 主体：每个 rank 需要几张
        LinkedHashMap<Integer, Integer> need = new LinkedHashMap<>();
        switch (kind) {
            case SINGLE:
                return out;
            case PAIR:
                need.put(main, 2);
                break;
            case TRIPLE:
            case TRIPLE_WITH_SINGLE:
            case TRIPLE_WITH_PAIR:
                need.put(main, 3);
                break;
            case BOMB:
            case FOUR_WITH_TWO_SINGLES:
            case FOUR_WITH_TWO_PAIRS:
                need.put(main, 4);
                break;
            case STRAIGHT:
                for (int i = 0; i < chain; i++) {
                    need.put(main - chain + 1 + i, 1);
                }
                break;
            case CONSECUTIVE_PAIRS:
                for (int i = 0; i < chain; i++) {
                    need.put(main - chain + 1 + i, 2);
                }
                break;
            case AIRPLANE:
            case AIRPLANE_WITH_SINGLES:
            case AIRPLANE_WITH_PAIRS:
                for (int i = 0; i < chain; i++) {
                    need.put(main - chain + 1 + i, 3);
                }
                break;
            default:
                return out;
        }
        // 带「对子」的牌型：带牌区里只剩 1 张真牌的 rank，可以用癞子补成一对
        if (kind == Kind.TRIPLE_WITH_PAIR || kind == Kind.FOUR_WITH_TWO_PAIRS
                || kind == Kind.AIRPLANE_WITH_PAIRS) {
            int[] left = cnt.clone();
            for (Map.Entry<Integer, Integer> e : need.entrySet()) {
                int r = e.getKey();
                left[r] -= Math.min(left[r], e.getValue());
            }
            int pairs = kind == Kind.TRIPLE_WITH_PAIR ? 1
                    : (kind == Kind.FOUR_WITH_TWO_PAIRS ? 2 : chain);
            for (int r = 3; r <= 15 && pairs > 0; r++) {
                if (left[r] == 1) {
                    need.put(r, 2);
                    pairs--;
                }
            }
        }
        // 缺口（need 已是升序插入 ⇒ gaps 天然升序）
        List<Integer> gaps = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : need.entrySet()) {
            int r = e.getKey();
            int want = e.getValue();
            int have = Math.min(cnt[r], want);
            for (int i = have; i < want; i++) {
                gaps.add(r);
            }
        }
        // ① 自己的点数正好在缺口里 ⇒ 当自己（视觉不用改，不记录）
        List<Integer> pending = new ArrayList<>();
        for (int id : wilds) {
            if (!gaps.remove(Integer.valueOf(DdzCards.rank(id)))) {
                pending.add(id);
            }
        }
        // ② 剩下的癞子依次补剩余缺口（升序）；补不到（带牌 / 没缺口）就当自己
        Collections.sort(gaps);
        for (int id : pending) {
            if (gaps.isEmpty()) {
                break;
            }
            int r = gaps.remove(0);
            if (r != DdzCards.rank(id)) {
                out.put(id, r);
            }
        }
        return out;
    }

    /**
     * 真正的癞子求解：{@code c} 是<b>非癞子</b>牌的 rank 计数，{@code wild} 是癞子张数。
     *
     * <p>不枚举"癞子当什么"（4 张癞子有 C(16,4)=1820 种分配，乘上每次决策的几十个候选
     * 会到十几万次识别，太慢），而是按牌型反向求解"需要补几张"。每种牌型 O(13) 次循环。</p>
     */
    private static DdzPattern identifyWithWild(int[] c, int n, int wild, int loneWildRank) {
        // 王炸：癞子不能当王 ⇒ 必须是两张真王（wild 此时必然为 0，留着防御）
        if (n == 2 && c[DdzCards.RANK_SMALL_JOKER] == 1
                && c[DdzCards.RANK_BIG_JOKER] == 1) {
            return new DdzPattern(Kind.ROCKET, 2, 1, DdzCards.RANK_BIG_JOKER);
        }
        // 炸弹：四张同点（癞子可补；王不能成炸）
        if (n == 4) {
            int r = maxRankFilled(c, wild, 4, 15);
            if (r > 0 && outsideZero(c, r, r)) {
                return new DdzPattern(Kind.BOMB, 4, 1, r);
            }
        }
        // 单张：癞子单出按原点数
        if (n == 1) {
            return new DdzPattern(Kind.SINGLE, 1, 1, wild == 1 ? loneWildRank : firstNonZero(c));
        }
        // 对子
        if (n == 2) {
            int r = maxRankFilled(c, wild, 2, 15);
            if (r > 0 && outsideZero(c, r, r)) {
                return new DdzPattern(Kind.PAIR, 2, 1, r);
            }
        }
        // 三张
        if (n == 3) {
            int r = maxRankFilled(c, wild, 3, 15);
            if (r > 0 && outsideZero(c, r, r)) {
                return new DdzPattern(Kind.TRIPLE, 3, 1, r);
            }
        }
        // 三带一（n=4，炸弹已在上面拦掉）
        if (n == 4) {
            int r = tripleRankWithSingle(c, wild);
            if (r > 0) {
                return new DdzPattern(Kind.TRIPLE_WITH_SINGLE, 4, 1, r);
            }
        }
        // 三带二（n=5）
        if (n == 5) {
            int r = tripleRankWithPair(c, wild);
            if (r > 0) {
                return new DdzPattern(Kind.TRIPLE_WITH_PAIR, 5, 1, r);
            }
        }
        // 顺子（3..A，>=5 张）
        if (n >= 5) {
            int r = straightTop(c, wild, n);
            if (r > 0) {
                return new DdzPattern(Kind.STRAIGHT, n, n, r);
            }
        }
        // 连对（>=3 对）
        if (n >= 6 && n % 2 == 0) {
            int r = chainTop(c, wild, n / 2, 2);
            if (r > 0) {
                return new DdzPattern(Kind.CONSECUTIVE_PAIRS, n, n / 2, r);
            }
        }
        // 飞机（不带，>=2 组）
        if (n >= 6 && n % 3 == 0) {
            int r = chainTop(c, wild, n / 3, 3);
            if (r > 0) {
                return new DdzPattern(Kind.AIRPLANE, n, n / 3, r);
            }
        }
        // 飞机带单（>=2 组）
        if (n >= 8 && n % 4 == 0) {
            int r = airplaneTop(c, wild, n / 4, 4);
            if (r > 0) {
                return new DdzPattern(Kind.AIRPLANE_WITH_SINGLES, n, n / 4, r);
            }
        }
        // 飞机带对（>=2 组）
        if (n >= 10 && n % 5 == 0) {
            int r = airplaneTop(c, wild, n / 5, 5);
            if (r > 0) {
                return new DdzPattern(Kind.AIRPLANE_WITH_PAIRS, n, n / 5, r);
            }
        }
        // 四带二单（n=6）
        if (n == 6) {
            int r = maxRankFilled(c, wild, 4, 15);
            if (r > 0) {
                return new DdzPattern(Kind.FOUR_WITH_TWO_SINGLES, 6, 1, r);
            }
        }
        // 四带两对（n=8）
        if (n == 8) {
            int r = quadWithTwoPairs(c, wild);
            if (r > 0) {
                return new DdzPattern(Kind.FOUR_WITH_TWO_PAIRS, 8, 1, r);
            }
        }
        return null;
    }

    /**
     * 用 {@code wild} 张癞子能把哪个 rank 补到 {@code need} 张，取<b>最大</b> rank；0 = 补不出。
     * 要求该 rank 现有的牌不超过 {@code need} 张（多出来的没处放）。
     */
    private static int maxRankFilled(int[] c, int wild, int need, int maxRank) {
        int best = 0;
        for (int r = 3; r <= maxRank; r++) {
            if (c[r] <= need && c[r] + wild >= need) {
                best = r;
            }
        }
        return best;
    }

    /** 三带一的主 rank（带牌从剩下的 1 张里取，一定凑得出来）。 */
    private static int tripleRankWithSingle(int[] c, int wild) {
        int best = 0;
        for (int r = 3; r <= 15; r++) {
            if (c[r] > 3) {
                continue;
            }
            if (3 - c[r] <= wild) {
                best = r;
            }
        }
        return best;
    }

    /** 三带二的主 rank：三张补齐后，剩下的 2 张还要能成对。 */
    private static int tripleRankWithPair(int[] c, int wild) {
        int best = 0;
        for (int r = 3; r <= 15; r++) {
            if (c[r] > 3) {
                continue;
            }
            int need = 3 - c[r];
            if (need > wild) {
                continue;
            }
            if (pairLeft(c, wild - need, r)) {
                best = r;
            }
        }
        return best;
    }

    /** 排除 {@code exclude} 后，剩下的牌 + {@code left} 张癞子能否<b>恰好</b>组成一对。 */
    private static boolean pairLeft(int[] c, int left, int exclude) {
        for (int s = 3; s <= 15; s++) {
            if (s == exclude || c[s] > 2) {
                continue;
            }
            if (c[s] + left < 2) {
                continue;
            }
            if (left - (2 - c[s]) != 0) {
                continue;   // 癞子必须正好用光
            }
            int other = 0;
            for (int t = 3; t <= 17; t++) {
                if (t != exclude && t != s) {
                    other += c[t];
                }
            }
            if (other == 0) {
                return true;
            }
        }
        return false;
    }

    /** 顺子：返回最大主 rank（牌面最大那张）；0 = 凑不出。 */
    private static int straightTop(int[] c, int wild, int len) {
        for (int start = 14 - len + 1; start >= 3; start--) {
            int need = 0;
            boolean ok = true;
            for (int r = start; r < start + len; r++) {
                if (c[r] > 1) {
                    ok = false;
                    break;
                }
                if (c[r] == 0) {
                    need++;
                }
            }
            if (!ok || need > wild) {
                continue;
            }
            if (!outsideZero(c, start, start + len - 1)) {
                continue;
            }
            return start + len - 1;
        }
        return 0;
    }

    /** 连对 / 飞机（不带）：每点 {@code unit} 张、连续 {@code groups} 组。返回最大主 rank。 */
    private static int chainTop(int[] c, int wild, int groups, int unit) {
        for (int start = 14 - groups + 1; start >= 3; start--) {
            int need = 0;
            boolean ok = true;
            for (int r = start; r < start + groups; r++) {
                if (c[r] > unit) {
                    ok = false;
                    break;
                }
                need += unit - c[r];
            }
            if (!ok || need > wild) {
                continue;
            }
            if (!outsideZero(c, start, start + groups - 1)) {
                continue;
            }
            return start + groups - 1;
        }
        return 0;
    }

    /**
     * 飞机带牌：主体 {@code groups} 组三张连续，翅膀 = {@code groups} 张单（unitSize=4）
     * 或 {@code groups} 对（unitSize=5）。返回最大主 rank。
     */
    private static int airplaneTop(int[] c, int wild, int groups, int unitSize) {
        for (int start = 14 - groups + 1; start >= 3; start--) {
            int need = 0;
            boolean ok = true;
            for (int r = start; r < start + groups; r++) {
                // 主体每点最多 3 张；4 张要拆开当翅膀的写法这里不支持，
                // 因为"4 张拆 3 + 1 张当翅膀"会让同一组牌有两种解释，容易误判
                if (c[r] > 3) {
                    ok = false;
                    break;
                }
                need += 3 - c[r];
            }
            if (!ok || need > wild) {
                continue;
            }
            int left = wild - need;
            if (unitSize == 4) {
                // 带单：剩下的张数固定是 groups（总数 4*groups），任意牌都行
                if (outsideTotal(c, start, start + groups - 1) + left == groups) {
                    return start + groups - 1;
                }
            } else if (canPairGroups(c, left, start, start + groups - 1, groups)) {
                return start + groups - 1;
            }
        }
        return 0;
    }

    /** 四带两对的主 rank。 */
    private static int quadWithTwoPairs(int[] c, int wild) {
        int best = 0;
        for (int r = 3; r <= 15; r++) {
            if (c[r] > 4) {
                continue;
            }
            int need = 4 - c[r];
            if (need > wild) {
                continue;
            }
            if (canPairGroups(c, wild - need, r, r, 2)) {
                best = r;
            }
        }
        return best;
    }

    /**
     * 排除 {@code [lo, hi]} 之后，剩下的牌 + {@code wildLeft} 张癞子能否正好组成 {@code pairs} 对。
     *
     * <p>王不参与：癞子不能当王，而大小王 rank 不同、又各自只有 1 张，
     * 所以只要剩余里有王就一定配不成对。</p>
     */
    private static boolean canPairGroups(int[] c, int wildLeft, int lo, int hi, int pairs) {
        if (c[DdzCards.RANK_SMALL_JOKER] > 0 || c[DdzCards.RANK_BIG_JOKER] > 0) {
            return false;
        }
        int odd = 0;
        int total = 0;
        for (int r = 3; r <= 15; r++) {
            if (r >= lo && r <= hi) {
                continue;
            }
            total += c[r];
            if (c[r] % 2 == 1) {
                odd++;
            }
        }
        if (total + wildLeft != pairs * 2) {
            return false;
        }
        if (odd > wildLeft) {
            return false;
        }
        return (wildLeft - odd) % 2 == 0;
    }

    /** {@code [lo, hi]} 之外的牌是否全为空。 */
    private static boolean outsideZero(int[] c, int lo, int hi) {
        for (int r = 3; r <= 17; r++) {
            if (r < lo || r > hi) {
                if (c[r] > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@code [lo, hi]} 之外的牌总数。 */
    private static int outsideTotal(int[] c, int lo, int hi) {
        int sum = 0;
        for (int r = 3; r <= 17; r++) {
            if (r < lo || r > hi) {
                sum += c[r];
            }
        }
        return sum;
    }

    /** 单张路径用：找出唯一那个非零 rank。 */
    private static int firstNonZero(int[] c) {
        for (int r = 3; r <= 17; r++) {
            if (c[r] > 0) {
                return r;
            }
        }
        return 0;
    }

    /**
     * 飞机带牌尝试：main 三张组必须连续，翅膀匹配指定形态。
     *
     * @param unitSize 4 = 带单，5 = 带对
     */
    private static DdzPattern tryAirplane(Map<Integer, Integer> counts, int total,
                                       int unitSize, Kind kind) {
        int groups = total / unitSize;
        if (groups < 2) {
            return null;
        }
        // 枚举作为「机身」的三张 rank（允许四张也算作三张参与？addon：全 count3 判不带；
        // 带牌模式机身要求恰好三张或四张中取三张。为兼容四张拆带，机身 rank 允许 count>=3）
        List<Integer> tripleRanks = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() >= 3 && e.getKey() <= 14) {
                tripleRanks.add(e.getKey());
            }
        }
        tripleRanks.sort(Integer::compareTo);
        // 找连续 groups 长的三张段
        for (int i = 0; i + groups <= tripleRanks.size(); i++) {
            int start = tripleRanks.get(i);
            if (start + groups - 1 > 14) {
                continue;
            }
            boolean contiguous = true;
            for (int g = 0; g < groups; g++) {
                if (!tripleRanks.contains(start + g)) {
                    contiguous = false;
                    break;
                }
            }
            if (!contiguous) {
                continue;
            }
            // 剩余牌构成翅膀校验
            Map<Integer, Integer> rest = new HashMap<>(counts);
            boolean ok = true;
            for (int g = 0; g < groups; g++) {
                int r = start + g;
                int c = rest.getOrDefault(r, 0);
                if (c == 3) {
                    rest.remove(r);
                } else if (c > 3) {
                    rest.put(r, c - 3); // 四张拆出三张，剩 1 张当翅膀
                } else {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            int restCount = rest.values().stream().mapToInt(Integer::intValue).sum();
            if (unitSize == 4) {
                // 翅膀 = groups 张单（任意牌，含对子拆开？标准斗地主：带单可带任意单，
                // addon airplane_with_singles：t=8/12/16…，n=t/4，机身 n 组 + n 张任意单）
                if (restCount == groups) {
                    return new DdzPattern(kind, total, groups, start + groups - 1);
                }
            } else {
                // 翅膀 = groups 对
                if (restCount == groups * 2 && allEqual(rest, 2)) {
                    return new DdzPattern(kind, total, groups, start + groups - 1);
                }
            }
        }
        return null;
    }

    private static Integer findRankWith(Map<Integer, Integer> counts, int n) {
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() == n) {
                return e.getKey();
            }
        }
        return null;
    }

    private static boolean allEqual(Map<Integer, Integer> counts, int value) {
        for (int v : counts.values()) {
            if (v != value) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 比较
    // ------------------------------------------------------------------

    /**
     * a 能否压过 b（addon {@code Ga}/{@code Wa}）。a 为 null 表示首出（任何合法牌型均可）。
     *
     * @return true = a 可以打出
     */
    public static boolean beats(DdzPattern a, DdzPattern b) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true; // 首出
        }
        if (a.isRocket()) {
            return true;
        }
        if (b.isRocket()) {
            return false;
        }
        if (a.isBomb() && !b.isBomb()) {
            return true;
        }
        if (b.isBomb() && !a.isBomb()) {
            return false;
        }
        // 同为炸弹或均为普通牌型：同结构比主 rank
        if (a.kind != b.kind || a.cardCount != b.cardCount || a.chainLength != b.chainLength) {
            return false;
        }
        return a.mainRank > b.mainRank;
    }

    /**
     * 手牌中是否存在能压过 {@code target} 的牌（供超时判定「压不过」用）。
     */
    public static boolean canBeatAny(List<Integer> hand, DdzPattern target) {
        // 枚举手牌所有子集不现实；这里用轻量代理：
        // target 为 null（首出）→ 有任意牌即可
        if (target == null) {
            return !hand.isEmpty();
        }
        // 王炸在手：压一切
        boolean hasSmall = false;
        boolean hasBig = false;
        for (int id : hand) {
            int r = DdzCards.rank(id);
            if (r == DdzCards.RANK_SMALL_JOKER) {
                hasSmall = true;
            }
            if (r == DdzCards.RANK_BIG_JOKER) {
                hasBig = true;
            }
        }
        if (hasSmall && hasBig) {
            return true;
        }
        // 炸弹在手且 target 非王炸
        if (!target.isRocket()) {
            Map<Integer, Integer> counts = countRanks(hand);
            for (int c : counts.values()) {
                if (c == 4) {
                    return true;
                }
            }
        }
        if (target.isBombLike()) {
            return false; // 只有王炸能压炸弹，前面已查
        }
        // 同型压制：按 target 结构在手牌中找
        return findBeating(hand, target) != null;
    }

    /**
     * 在手牌中找一手能压过 target 的牌（保守枚举：按牌型逐类构造）。
     * 供超时「压不过」判定与提示使用；找不到返回 null。
     */
    public static List<Integer> findBeating(List<Integer> hand, DdzPattern target) {
        if (target == null) {
            return hand.isEmpty() ? null : new ArrayList<>(hand.subList(0, 1));
        }
        List<Integer> sorted = DdzCards.sortedCopy(hand);
        Map<Integer, List<Integer>> byRank = new HashMap<>();
        for (int id : sorted) {
            byRank.computeIfAbsent(DdzCards.rank(id), k -> new ArrayList<>()).add(id);
        }

        // 王炸
        if (!target.isRocket()) {
            List<Integer> small = byRank.get(DdzCards.RANK_SMALL_JOKER);
            List<Integer> big = byRank.get(DdzCards.RANK_BIG_JOKER);
            if (small != null && big != null && target.isBombLike()) {
                // 只有炸弹需要王炸压
                return new ArrayList<>(List.of(small.get(0), big.get(0)));
            }
            // 炸弹压非炸弹
            if (!target.isBombLike()) {
                for (Map.Entry<Integer, List<Integer>> e : byRank.entrySet()) {
                    if (e.getValue().size() == 4) {
                        return new ArrayList<>(e.getValue());
                    }
                }
                if (small != null && big != null) {
                    return new ArrayList<>(List.of(small.get(0), big.get(0)));
                }
            }
        }

        List<Integer> result = switch (target.getKind()) {
            case SINGLE -> findSingle(byRank, target.getMainRank());
            case PAIR -> findN(byRank, target.getMainRank(), 2, 2);
            case TRIPLE -> findN(byRank, target.getMainRank(), 3, 3);
            case TRIPLE_WITH_SINGLE -> findTripleWith(byRank, target.getMainRank(), false);
            case TRIPLE_WITH_PAIR -> findTripleWith(byRank, target.getMainRank(), true);
            case STRAIGHT -> findStraight(byRank, target.getChainLength(), target.getMainRank(), 1);
            case CONSECUTIVE_PAIRS -> findStraight(byRank, target.getChainLength(), target.getMainRank(), 2);
            case AIRPLANE -> findAirplane(byRank, target.getChainLength(), target.getMainRank(), 0);
            case AIRPLANE_WITH_SINGLES -> findAirplane(byRank, target.getChainLength(), target.getMainRank(), 1);
            case AIRPLANE_WITH_PAIRS -> findAirplane(byRank, target.getChainLength(), target.getMainRank(), 2);
            case FOUR_WITH_TWO_SINGLES -> findFourWith(byRank, target.getMainRank(), false);
            case FOUR_WITH_TWO_PAIRS -> findFourWith(byRank, target.getMainRank(), true);
            default -> null;
        };
        return result;
    }

    private static List<Integer> findSingle(Map<Integer, List<Integer>> byRank, int above) {
        for (int r = above + 1; r <= DdzCards.RANK_BIG_JOKER; r++) {
            List<Integer> ids = byRank.get(r);
            if (ids != null && !ids.isEmpty()) {
                return new ArrayList<>(List.of(ids.get(0)));
            }
        }
        return null;
    }

    /** 找恰含 n 张 rank r 的组（可从更多张中取 n 张）。 */
    private static List<Integer> findN(Map<Integer, List<Integer>> byRank, int r, int need, int take) {
        List<Integer> ids = byRank.get(r);
        if (ids == null || ids.size() < need) {
            return null;
        }
        return new ArrayList<>(ids.subList(0, take));
    }

    private static List<Integer> findTripleWith(Map<Integer, List<Integer>> byRank,
                                                int tripleRank, boolean pairKicker) {
        // 找 > tripleRank 的三张（含四张拆）
        for (int r = tripleRank + 1; r <= 15; r++) {
            List<Integer> ids = byRank.get(r);
            if (ids == null || ids.size() < 3) {
                continue;
            }
            List<Integer> result = new ArrayList<>(ids.subList(0, 3));
            // 找翅膀
            if (pairKicker) {
                for (int kr = 3; kr <= 17; kr++) {
                    if (kr == r) {
                        continue;
                    }
                    List<Integer> k = byRank.get(kr);
                    if (k != null && k.size() >= 2) {
                        result.add(k.get(0));
                        result.add(k.get(1));
                        return result;
                    }
                }
            } else {
                for (int kr = 3; kr <= 17; kr++) {
                    if (kr == r) {
                        continue;
                    }
                    List<Integer> k = byRank.get(kr);
                    if (k != null && !k.isEmpty()) {
                        result.add(k.get(0));
                        return result;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 找顺子/连对。{@code unitSize}=1 顺子 / 2 连对；同长度、起点高于 {@code aboveMax}。
     */
    private static List<Integer> findStraight(Map<Integer, List<Integer>> byRank,
                                              int chainLength, int aboveMax, int unitSize) {
        for (int start = aboveMax - chainLength + 2; start + chainLength - 1 <= 14; start++) {
            if (start < 3) {
                continue;
            }
            int end = start + chainLength - 1;
            if (end > 14) {
                continue;
            }
            if (end <= aboveMax) {
                continue;
            }
            boolean ok = true;
            List<Integer> result = new ArrayList<>();
            for (int r = start; r <= end; r++) {
                List<Integer> ids = byRank.get(r);
                if (ids == null || ids.size() < unitSize) {
                    ok = false;
                    break;
                }
                result.addAll(ids.subList(0, unitSize));
            }
            if (ok) {
                return result;
            }
        }
        return null;
    }

    /**
     * 找飞机（含带翅膀）。{@code wings}=0 不带 / 1 带单 / 2 带对。
     * 同 chainLength、机身最高 rank > aboveMax。
     */
    private static List<Integer> findAirplane(Map<Integer, List<Integer>> byRank,
                                              int chainLength, int aboveMax, int wings) {
        for (int start = aboveMax - chainLength + 2; start + chainLength - 1 <= 14; start++) {
            if (start < 3) {
                continue;
            }
            int end = start + chainLength - 1;
            if (end > 14 || end <= aboveMax) {
                continue;
            }
            boolean ok = true;
            List<Integer> result = new ArrayList<>();
            for (int r = start; r <= end; r++) {
                List<Integer> ids = byRank.get(r);
                if (ids == null || ids.size() < 3) {
                    ok = false;
                    break;
                }
                result.addAll(ids.subList(0, 3));
            }
            if (!ok) {
                continue;
            }
            if (wings == 0) {
                return result;
            }
            // 收集翅膀候选（不重复使用机身牌）
            List<Integer> kickerPool = new ArrayList<>();
            for (Map.Entry<Integer, List<Integer>> e : byRank.entrySet()) {
                int r = e.getKey();
                if (r >= start && r <= end) {
                    // 机身 rank 多余的牌（如四张）也可当单翅膀
                    for (int i = 3; i < e.getValue().size(); i++) {
                        kickerPool.add(e.getValue().get(i));
                    }
                } else {
                    kickerPool.addAll(e.getValue());
                }
            }
            if (wings == 1) {
                if (kickerPool.size() >= chainLength) {
                    // 优先小牌当翅膀
                    kickerPool.sort((a, b) -> Integer.compare(DdzCards.rank(a), DdzCards.rank(b)));
                    result.addAll(kickerPool.subList(0, chainLength));
                    return result;
                }
            } else {
                // 带对：需要 chainLength 对
                Map<Integer, Integer> poolCounts = new HashMap<>();
                for (int id : kickerPool) {
                    poolCounts.merge(DdzCards.rank(id), 1, Integer::sum);
                }
                List<Integer> pairRanks = new ArrayList<>();
                for (Map.Entry<Integer, Integer> e : poolCounts.entrySet()) {
                    if (e.getValue() >= 2) {
                        pairRanks.add(e.getKey());
                    }
                }
                if (pairRanks.size() >= chainLength) {
                    pairRanks.sort(Integer::compareTo);
                    for (int i = 0; i < chainLength; i++) {
                        List<Integer> ids = byRank.get(pairRanks.get(i));
                        result.add(ids.get(0));
                        result.add(ids.get(1));
                    }
                    return result;
                }
            }
        }
        return null;
    }

    private static List<Integer> findFourWith(Map<Integer, List<Integer>> byRank,
                                              int quadRank, boolean pairKickers) {
        for (int r = quadRank + 1; r <= 15; r++) {
            List<Integer> ids = byRank.get(r);
            if (ids == null || ids.size() < 4) {
                continue;
            }
            List<Integer> result = new ArrayList<>(ids.subList(0, 4));
            if (pairKickers) {
                int pairsFound = 0;
                for (int kr = 3; kr <= 17 && pairsFound < 2; kr++) {
                    if (kr == r) {
                        continue;
                    }
                    List<Integer> k = byRank.get(kr);
                    if (k != null && k.size() >= 2) {
                        result.add(k.get(0));
                        result.add(k.get(1));
                        pairsFound++;
                    }
                }
                if (pairsFound == 2) {
                    return result;
                }
            } else {
                int singles = 0;
                for (int kr = 3; kr <= 17 && singles < 2; kr++) {
                    if (kr == r) {
                        continue;
                    }
                    List<Integer> k = byRank.get(kr);
                    if (k != null && !k.isEmpty()) {
                        result.add(k.get(0));
                        singles++;
                        if (k.size() >= 2) {
                            // 一张牌的对子贡献第二张单
                            result.add(k.get(1));
                            singles++;
                        }
                    }
                }
                if (singles >= 2) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 牌型的中文名（播报用）。
     * <p>
     * 带牌/链式牌型会带上张数（如「飞机带翅膀」8 张），单张/对子等直接给名字 ——
     * 播报里出现「STRAIGHT(5, chain=5, rank=8)」对玩家毫无意义。
     */
    public String label() {
        return switch (kind) {
            case SINGLE -> "单张";
            case PAIR -> "对子";
            case TRIPLE -> "三张";
            case TRIPLE_WITH_SINGLE -> "三带一";
            case TRIPLE_WITH_PAIR -> "三带二";
            case STRAIGHT -> "顺子" + cardCount + "张";
            case CONSECUTIVE_PAIRS -> "连对" + cardCount + "张";
            case AIRPLANE -> "飞机" + chainLength + "组";
            case AIRPLANE_WITH_SINGLES -> "飞机带单" + cardCount + "张";
            case AIRPLANE_WITH_PAIRS -> "飞机带对" + cardCount + "张";
            case FOUR_WITH_TWO_SINGLES -> "四带二";
            case FOUR_WITH_TWO_PAIRS -> "四带两对";
            case BOMB -> "炸弹";
            case ROCKET -> "王炸";
        };
    }

    /**
     * 表单里用的短名（不带张数后缀）：连对 / 飞机 / 三带二 …
     *
     * <p>2026-10-27 用户 #2 的「选择牌型」表单按钮就用它拼成
     * 「连对 - 66554433」这种样子。</p>
     */
    public String shortLabel() {
        return switch (kind) {
            case SINGLE -> "单张";
            case PAIR -> "对子";
            case TRIPLE -> "三张";
            case TRIPLE_WITH_SINGLE -> "三带一";
            case TRIPLE_WITH_PAIR -> "三带二";
            case STRAIGHT -> "顺子";
            case CONSECUTIVE_PAIRS -> "连对";
            case AIRPLANE -> "飞机";
            case AIRPLANE_WITH_SINGLES -> "飞机带单";
            case AIRPLANE_WITH_PAIRS -> "飞机带对";
            case FOUR_WITH_TWO_SINGLES -> "四带二";
            case FOUR_WITH_TWO_PAIRS -> "四带两对";
            case BOMB -> "炸弹";
            case ROCKET -> "王炸";
        };
    }

    /**
     * 同一手牌<b>所有</b>说得通的牌型（2026-10-27 用户 #2）。
     *
     * <p>背景：带癞子出牌时一手牌常常能解释成好几种牌型，比如
     * {@code 5 5 8 8 + 癞子} 既能当「三带二（三个 5）」也能当「三带二（三个 8）」，
     * 压不压得过上一手完全取决于选哪种。QQ 斗地主的做法是弹一个表单让玩家自己挑，
     * 这里照抄。</p>
     *
     * <p>实现分两步：① 先放 {@link #identify} 的原结果（保证「能出」这个判据只会有增无减）；
     * ② 再按每一类牌型的<b>可行性</b>逐个枚举，去重后一并给出。</p>
     *
     * @return 至少 1 条（{@link #identify} 判不出来的话返回空表）；无癞子时只会有 1 条
     */
    public static List<DdzPattern> identifyAll(List<Integer> cards, int heaven, int earth) {
        List<DdzPattern> out = new ArrayList<>();
        DdzPattern base = identify(cards, heaven, earth);
        if (base == null) {
            return out;
        }
        out.add(base);
        int n = cards.size();
        int[] c = new int[18];
        int w = 0;
        for (int id : cards) {
            int r = DdzCards.rank(id);
            if (DdzCards.isWild(id, heaven, earth)) {
                w++;
            } else {
                c[r]++;
            }
        }
        if (w == 0) {
            return out;   // 没有癞子 ⇒ 只有唯一解释
        }
        for (DdzPattern p : enumerate(c, w, n)) {
            boolean dup = false;
            for (DdzPattern q : out) {
                if (q.kind == p.kind && q.mainRank == p.mainRank
                        && q.chainLength == p.chainLength && q.cardCount == p.cardCount) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(p);
            }
        }
        // 强的排前面（炸弹 > 王炸之外的一律按主 rank 降序），表单看起来更符合直觉
        out.sort((a, b) -> {
            if (a.isBombLike() != b.isBombLike()) {
                return a.isBombLike() ? -1 : 1;
            }
            if (a.mainRank != b.mainRank) {
                return Integer.compare(b.mainRank, a.mainRank);
            }
            return a.kind.compareTo(b.kind);
        });
        return out;
    }

    /** 逐类枚举（都建立在 {@code c} = 非癞子的点数直方图、{@code w} = 癞子张数之上）。 */
    private static List<DdzPattern> enumerate(int[] c, int w, int n) {
        List<DdzPattern> out = new ArrayList<>();
        // 王炸：癞子不能当王
        if (n == 2 && c[DdzCards.RANK_SMALL_JOKER] == 1 && c[DdzCards.RANK_BIG_JOKER] == 1) {
            out.add(new DdzPattern(Kind.ROCKET, 2, 1, DdzCards.RANK_BIG_JOKER));
            return out;
        }
        if (n == 1) {
            return out;
        }
        // 对子 / 三张 / 炸弹：整手牌就是这一组
        if (n == 2 || n == 3 || n == 4) {
            Kind k = n == 2 ? Kind.PAIR : (n == 3 ? Kind.TRIPLE : Kind.BOMB);
            int[] body = new int[18];
            for (int r = 3; r <= 15; r++) {
                java.util.Arrays.fill(body, 0);
                body[r] = n;
                if (fills(c, body, w) && kickerCards(c, body, w) == 0) {
                    out.add(new DdzPattern(k, n, 1, r));
                }
            }
        }
        // 三带一（4 张）
        if (n == 4) {
            int[] body = new int[18];
            for (int r = 3; r <= 15; r++) {
                java.util.Arrays.fill(body, 0);
                body[r] = 3;
                if (fills(c, body, w) && kickerCards(c, body, w) == 1) {
                    out.add(new DdzPattern(Kind.TRIPLE_WITH_SINGLE, 4, 1, r));
                }
            }
        }
        // 三带二（5 张）
        if (n == 5) {
            for (int r = 3; r <= 15; r++) {
                int[] body = new int[18];
                body[r] = 3;
                if (fills(c, body, w) && kickerCards(c, body, w) == 2
                        && kickerPairs(c, body, w) >= 1) {
                    out.add(new DdzPattern(Kind.TRIPLE_WITH_PAIR, 5, 1, r));
                }
            }
        }
        // 四带二单（6 张）
        if (n == 6) {
            for (int r = 3; r <= 15; r++) {
                int[] body = new int[18];
                body[r] = 4;
                if (fills(c, body, w) && kickerCards(c, body, w) == 2) {
                    out.add(new DdzPattern(Kind.FOUR_WITH_TWO_SINGLES, 6, 1, r));
                }
            }
        }
        // 四带两对（8 张）
        if (n == 8) {
            for (int r = 3; r <= 15; r++) {
                int[] body = new int[18];
                body[r] = 4;
                if (fills(c, body, w) && kickerCards(c, body, w) == 4
                        && kickerPairs(c, body, w) >= 2) {
                    out.add(new DdzPattern(Kind.FOUR_WITH_TWO_PAIRS, 8, 1, r));
                }
            }
        }
        // 顺子（≥5 张，3..A）
        if (n >= 5) {
            for (int hi = 3 + n - 1; hi <= 14; hi++) {
                int lo = hi - n + 1;
                if (chain(c, lo, hi, 1, w) == 0) {
                    out.add(new DdzPattern(Kind.STRAIGHT, n, n, hi));
                }
            }
        }
        // 连对（≥3 对）
        if (n >= 6 && n % 2 == 0) {
            int m = n / 2;
            for (int hi = 3 + m - 1; hi <= 14; hi++) {
                int lo = hi - m + 1;
                if (chain(c, lo, hi, 2, w) == 0) {
                    out.add(new DdzPattern(Kind.CONSECUTIVE_PAIRS, n, m, hi));
                }
            }
        }
        // 飞机（不带，≥2 组）
        if (n >= 6 && n % 3 == 0) {
            int m = n / 3;
            for (int hi = 3 + m - 1; hi <= 14; hi++) {
                int lo = hi - m + 1;
                if (chain(c, lo, hi, 3, w) == 0) {
                    out.add(new DdzPattern(Kind.AIRPLANE, n, m, hi));
                }
            }
        }
        // 飞机带单（总长 4m）
        if (n >= 8 && n % 4 == 0) {
            int m = n / 4;
            for (int hi = 3 + m - 1; hi <= 14; hi++) {
                int[] body = new int[18];
                for (int r = hi - m + 1; r <= hi; r++) {
                    body[r] = 3;
                }
                if (fills(c, body, w) && kickerCards(c, body, w) == m) {
                    out.add(new DdzPattern(Kind.AIRPLANE_WITH_SINGLES, n, m, hi));
                }
            }
        }
        // 飞机带对（总长 5m）
        if (n >= 10 && n % 5 == 0) {
            int m = n / 5;
            for (int hi = 3 + m - 1; hi <= 14; hi++) {
                int[] body = new int[18];
                for (int r = hi - m + 1; r <= hi; r++) {
                    body[r] = 3;
                }
                if (fills(c, body, w) && kickerCards(c, body, w) == m * 2
                        && kickerPairs(c, body, w) >= m) {
                    out.add(new DdzPattern(Kind.AIRPLANE_WITH_PAIRS, n, m, hi));
                }
            }
        }
        return out;
    }

    /**
     * 主体（{@code body}）能不能凑出来（带牌部分另算）。
     *
     * <p>判据：主体内的点数不超过要求；缺口总数 ≤ 癞子数。主体外的牌交给带牌判据，
     * 所以这里<b>不</b>检查它们。</p>
     */
    private static boolean fills(int[] c, int[] body, int w) {
        int short_ = 0;
        for (int r = 0; r < c.length; r++) {
            if (body[r] <= 0) {
                continue;
            }
            if (c[r] > body[r]) {
                return false;
            }
            short_ += body[r] - c[r];
        }
        return short_ <= w;
    }

    /** 主体之外还剩几张牌（牌 + 没派上用场的癞子）。 */
    private static int kickerCards(int[] c, int[] body, int w) {
        int short_ = 0;
        int rest = 0;
        for (int r = 0; r < c.length; r++) {
            if (body[r] > 0) {
                short_ += body[r] - c[r];
            } else {
                rest += c[r];      // ⚠️ 只数主体外的牌，主体内的是 c[r]-body[r] < 0
            }
        }
        return rest + (w - short_);
    }

    /** 主体之外剩下的牌最多能凑出几对（癞子先补单张、再两两成对）。 */
    private static int kickerPairs(int[] c, int[] body, int w) {
        int short_ = 0;
        int pairs = 0;
        int singles = 0;
        for (int r = 0; r < c.length; r++) {
            if (body[r] > 0) {
                short_ += body[r] - c[r];
                continue;
            }
            int rest = c[r];
            pairs += rest / 2;
            if (rest % 2 == 1) {
                singles++;
            }
        }
        int free = w - short_;
        int use = Math.min(singles, free);
        pairs += use;
        free -= use;
        pairs += free / 2;
        return pairs;
    }

    /**
     * 连续链（顺子 / 连对 / 飞机）能否凑出来。
     *
     * @param need 每组要几张（1 / 2 / 3）
     * @return 需要几张癞子（0 表示不用癞子也能成）；{@code -1} = 不可能
     */
    private static int chain(int[] c, int lo, int hi, int need, int w) {
        int short_ = 0;
        for (int r = 3; r <= 17; r++) {
            boolean in = r >= lo && r <= hi;
            if (!in && c[r] > 0) {
                return -1;   // 链外还有牌，说明不是纯链
            }
            if (in) {
                if (c[r] > need) {
                    return -1;
                }
                short_ += need - c[r];
            }
        }
        return short_ <= w ? short_ : -1;
    }

    /**
     * 「选择牌型」表单里那串点数：{@code 66554433} 这种（不带花色）。
     *
     * @param asc {@code true} = 小→大（跟手牌排序开关同步，2026-10-27 用户 #3）
     */
    public static String displaySequence(List<Integer> cards, int heaven, int earth,
                                         DdzPattern p, boolean asc) {
        Map<Integer, Integer> as = assignWildRanks(cards, heaven, earth, p);
        List<Integer> ids = new ArrayList<>();
        for (int id : cards) {
            if (DdzCards.isValid(id)) {
                ids.add(id);
            }
        }
        // 复用出牌区那套唯一实现（同点数「癞子算内部更大」，方向跟着开关翻）
        ids = DdzPlayOrder.sorted(ids, id -> as.getOrDefault(id, 0),
                id -> DdzCards.isWild(id, heaven, earth), asc);
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            sb.append(rankName(as.getOrDefault(id, DdzCards.rank(id))));
        }
        return sb.toString();
    }

    /** 点数 → 牌面文字（3..10 / J / Q / K / A / 2 / 大小王）。 */
    public static String rankName(int rank) {
        switch (rank) {
            case 11: return "J";
            case 12: return "Q";
            case 13: return "K";
            case 14: return "A";
            case 15: return "2";
            case DdzCards.RANK_SMALL_JOKER: return "小王";
            case DdzCards.RANK_BIG_JOKER: return "大王";
            default: return String.valueOf(rank);
        }
    }

    @Override
    public String toString() {
        return kind + "(" + cardCount + ", chain=" + chainLength + ", rank=" + mainRank + ")";
    }
}
