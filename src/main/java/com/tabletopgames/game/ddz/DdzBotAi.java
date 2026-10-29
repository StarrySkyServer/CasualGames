package com.tabletopgames.game.ddz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 机器人决策（addon KE/GE/WE/BE/VE 的简化移植，三档难度）。
 * <p>
 * 难度差异（与 addon 对齐）：
 * <ul>
 *   <li>simple：叫抢阈值略高；队友首出直接 pass；打分权重 2.4/0.18；不压队友</li>
 *   <li>medium：常规阈值；无前瞻</li>
 *   <li>hard：常规阈值 + 1 层前瞻（对前 6 个候选模拟对手回应取最小代价）</li>
 * </ul>
 * 加倍阈值三档相同：手牌强度 ≥9 → 超级加倍(4)，≥6.5 → 加倍(2)，否则不加倍(1)。
 */
public final class DdzBotAi {

    private DdzBotAi() {
    }

    /** 决策结果。 */
    public record Decision(Type type, List<Integer> cards, int bid, int factor) {

        public enum Type {
            PLAY, PASS, BID, DOUBLE
        }
    }

    // ------------------------------------------------------------------
    // 手牌强度（addon BE）
    // ------------------------------------------------------------------

    /** 手牌强度估值：供叫抢与加倍使用。 */
    public static double handStrength(List<Integer> hand) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int id : hand) {
            counts.merge(DdzCards.rank(id), 1, Integer::sum);
        }
        double score = 0;
        // 大牌：2 / 王
        for (int id : hand) {
            int r = DdzCards.rank(id);
            if (r == DdzCards.RANK_BIG_JOKER) {
                score += 4.0;
            } else if (r == DdzCards.RANK_SMALL_JOKER) {
                score += 3.0;
            } else if (r == 15) { // 2
                score += 1.5;
            } else if (r == 14) { // A
                score += 0.6;
            }
        }
        // 炸弹
        for (int c : counts.values()) {
            if (c == 4) {
                score += 3.0;
            } else if (c == 3) {
                score += 0.8;
            } else if (c == 2) {
                score += 0.2;
            }
        }
        return score;
    }

    // ------------------------------------------------------------------
    // 叫抢 / 加倍
    // ------------------------------------------------------------------

    /**
     * 叫抢决策。返回 true = 叫/抢。
     */
    public static boolean decideCallRob(List<Integer> hand, boolean robbing,
                                        DdzDifficulty difficulty) {
        double s = handStrength(hand);
        boolean simple = difficulty == DdzDifficulty.SIMPLE;
        if (robbing) {
            // rob 阈值：simple 6，其他 5.7
            return s >= (simple ? 6 : 5.7);
        }
        // call 阈值：simple 4，其他 3.7
        return s >= (simple ? 4 : 3.7);
    }

    /**
     * 叫分决策（叫分制定地主）。返回 0（不叫）/ 1 / 2 / 3。
     *
     * <p>阈值与 {@link #decideCallRob} 一脉相承（都是 {@link #handStrength}）：
     * 叫地主阈值 3.7 / 抢地主 5.7 对应「愿意打」，这里再往上分两档 ——
     * 敢叫 3 分基本等于「手上够横」。返回的叫分一定<b>高于</b> {@code highestBid}，
     * 否则回落到 0，避免服务端因「必须高于最高分」直接拒掉。</p>
     *
     * @param highestBid 当前最高叫分（0 = 还没人叫）
     */
    public static int decideScore(List<Integer> hand, int highestBid, DdzDifficulty difficulty) {
        double s = handStrength(hand);
        boolean simple = difficulty == DdzDifficulty.SIMPLE;
        // 档位边界：simple 各档抬高 0.6 —— 初级机器人更保守，别一上来就叫满
        double t3 = simple ? 7.6 : 7.0;
        double t2 = simple ? 6.1 : 5.5;
        double t1 = simple ? 4.6 : 4.0;
        int want = s >= t3 ? 3 : (s >= t2 ? 2 : (s >= t1 ? 1 : 0));
        return want > highestBid ? want : 0;
    }

    /**
     * 加倍决策。返回 1 / 2 / 4。
     */
    public static int decideDouble(List<Integer> hand) {
        double s = handStrength(hand);
        if (s >= 9) {
            return 4;
        }
        if (s >= 6.5) {
            return 2;
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 出牌
    // ------------------------------------------------------------------

    /**
     * 出牌决策。
     *
     * @param seat      当前座位
     * @param round     局面
     * @param landlord  地主座位
     * @param difficulty 难度
     * @return PLAY（含牌列表）或 PASS
     */
    public static Decision decidePlay(List<Integer> hand, DdzPattern target, int lastSeat,
                                      int landlord, int seat, DdzDifficulty difficulty) {
        return decidePlay(hand, target, lastSeat, landlord, seat, difficulty, 0, 0);
    }

    /**
     * @param wildRank   本局地赖子点数（0 = 没有）。癞子会改变牌型解释，
     *                   所以终检必须用同一组点数，否则会吐出
     *                   {@code DdzRound.play} 判成"压不过"的牌。
     * @param heavenRank 本局天癞子点数（0 = 没有）；天地癞子模式下两个点数都是万能牌
     */
    public static Decision decidePlay(List<Integer> hand, DdzPattern target, int lastSeat,
                                      int landlord, int seat, DdzDifficulty difficulty,
                                      int wildRank, int heavenRank) {
        boolean leading = target == null;
        boolean simple = difficulty == DdzDifficulty.SIMPLE;

        // simple 难度：队友（农民之间）首出直接让牌？addon：simple 不压地主队友
        // （队友首出时 pass——但对 lead 不能 pass，故仅在跟牌时生效）
        if (!leading && isTeammate(seat, lastSeat, landlord) && simple) {
            return new Decision(Decision.Type.PASS, null, 0, 0);
        }

        // 枚举候选
        List<List<Integer>> candidates = enumerate(hand, target, wildRank, heavenRank);
        // ⚠️ 终检过滤（**模组遗留 bug 的修复**）：enumerate 的若干分支会吐出压不过 target 的牌 ——
        //   ① addSameKind 的 BOMB 分支枚举了手牌里**所有**四张炸弹，没比大小；
        //   ② addChains 用 aboveMax 做「整条链的末端 rank > target rank」判断，
        //      但链里可能混入更小的牌。
        // 症状：AI 打出「5 5 5 5」去压「J 的炸弹」，DdzRound.play 直接拒绝，
        //      真机上表现为机器人卡住不动、直到超时被判负。
        // 这里统一过滤：只保留「是合法牌型 且 beats(target)」的候选。
        List<List<Integer>> legal = new ArrayList<>();
        for (List<Integer> mv : candidates) {
            DdzPattern p = DdzPattern.identify(mv, heavenRank, wildRank);
            if (p == null) {
                continue;
            }
            if (leading || DdzPattern.beats(p, target)) {
                legal.add(mv);
            }
        }
        candidates = legal;
        if (candidates.isEmpty()) {
            if (leading) {
                // 不应发生（首出必有牌）；兜底打最小单
                List<Integer> min = new ArrayList<>(List.of(hand.get(0)));
                return new Decision(Decision.Type.PLAY, min, 0, 0);
            }
            return new Decision(Decision.Type.PASS, null, 0, 0);
        }

        // 打分选优（`leading` 决定「多出几张」的权重：跟牌时乱铺牌反而亏）
        List<List<Integer>> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> Double.compare(scoreMove(b, hand, difficulty, leading),
                scoreMove(a, hand, difficulty, leading)));

        return new Decision(Decision.Type.PLAY, ranked.get(0), 0, 0);
    }

    /** 是否队友（同为农民且非地主）。 */
    private static boolean isTeammate(int seat, int other, int landlord) {
        if (other < 0 || seat == other) {
            return false;
        }
        return seat != landlord && other != landlord;
    }

    /**
     * 估算打完这手牌还需要几轮（越小越好）——评分的主项。
     *
     * <p>2026-10-24 新增。旧评分拿 {@link #handStrength}（手牌强度）当主项，
     * 于是「出 1 张」和「出 2 张」剩下的强度<b>完全相同</b>，而后者还要付拆牌惩罚
     * ⇒ 排序里单张永远第一。800 局实测：首出 <b>100%</b> 单张、99.1% 的出牌只有
     * 1 张、平均 1.03 张/次。这就是「初级机器人只会一张一张丢小牌」的根因。</p>
     *
     * <p>贪心估算顺序：王炸 → 炸弹 → 顺子 → 连对 → 飞机 → 三张 → 对子 → 单张。
     * 不追求最优（那是 NP 的），只要能区分「拆成 9 手」和「6 手走完」就够——
     * 而且它比 {@code handStrength} 单调得多，不会出现「出得越多分越高」的歪激励。</p>
     */
    public static int handsNeeded(List<Integer> hand) {
        if (hand == null || hand.isEmpty()) {
            return 0;
        }
        int[] cnt = new int[18];
        for (int id : hand) {
            cnt[DdzCards.rank(id)]++;
        }
        int hands = 0;
        // 王炸
        if (cnt[DdzCards.RANK_SMALL_JOKER] > 0 && cnt[DdzCards.RANK_BIG_JOKER] > 0) {
            hands++;
            cnt[DdzCards.RANK_SMALL_JOKER]--;
            cnt[DdzCards.RANK_BIG_JOKER]--;
        }
        // 炸弹（四张同点，一把走完）
        for (int r = 3; r <= 15; r++) {
            if (cnt[r] == 4) {
                hands++;
                cnt[r] = 0;
            }
        }
        // 顺子（3..A，>=5 张连续，每点取 1 张）
        for (int r = 3; r <= 14; r++) {
            if (cnt[r] == 0) {
                continue;
            }
            int len = 0;
            while (r + len <= 14 && cnt[r + len] > 0) {
                len++;
            }
            if (len >= 5) {
                for (int k = 0; k < len; k++) {
                    cnt[r + k]--;
                }
                hands++;
                r += len - 1;
            }
        }
        // 连对（3..A，>=3 对连续）
        for (int r = 3; r <= 14; r++) {
            if (cnt[r] < 2) {
                continue;
            }
            int len = 0;
            while (r + len <= 14 && cnt[r + len] >= 2) {
                len++;
            }
            if (len >= 3) {
                for (int k = 0; k < len; k++) {
                    cnt[r + k] -= 2;
                }
                hands++;
                r += len - 1;
            }
        }
        // 飞机（连续三张 >=2 组；带牌不另计手数）
        for (int r = 3; r <= 14; r++) {
            if (cnt[r] < 3) {
                continue;
            }
            int len = 0;
            while (r + len <= 14 && cnt[r + len] >= 3) {
                len++;
            }
            if (len >= 2) {
                for (int k = 0; k < len; k++) {
                    cnt[r + k] -= 3;
                }
                hands++;
                r += len - 1;
            }
        }
        // 三张（三带一/三带二 仍算 1 手，顺手带走最小的单张）
        for (int r = 3; r <= 15; r++) {
            if (cnt[r] < 3) {
                continue;
            }
            hands++;
            cnt[r] -= 3;
            for (int kr = 3; kr <= 17; kr++) {
                if (cnt[kr] == 1) {
                    cnt[kr] = 0;
                    break;
                }
            }
        }
        // 对子
        for (int r = 3; r <= 17; r++) {
            if (cnt[r] >= 2) {
                hands++;
                cnt[r] -= 2;
            }
        }
        // 剩下的单张 / 王
        for (int r = 3; r <= 17; r++) {
            hands += cnt[r];
        }
        return hands;
    }

    /**
     * 打分（越大越好）：<b>剩余手数为主项</b>，出牌张数次之，主 rank 最后。
     *
     * @param leading 是否首出 —— 首出时"多出几张"价值最高；跟牌时铺牌容易送掉牌权，
     *                所以张数奖励压到 1/4
     */
    private static double scoreMove(List<Integer> move, List<Integer> hand,
                                    DdzDifficulty difficulty, boolean leading) {
        boolean simple = difficulty == DdzDifficulty.SIMPLE;
        List<Integer> rest = new ArrayList<>(hand);
        for (int id : move) {
            rest.remove(Integer.valueOf(id));
        }
        int restHands = handsNeeded(rest);
        // 主项：出完之后还得打几轮（少 = 好）；一手走完（0 手）直接封顶
        double score = -(simple ? 1.0 : 1.5) * restHands;
        // 次项：这一次出掉几张
        score += (simple ? 0.34 : 0.42) * (leading ? 1.0 : 0.25) * move.size();
        DdzPattern p = DdzPattern.identify(move);
        if (p != null) {
            // 同等收益下先出小的，把大牌留到关键回合
            score -= 0.06 * p.getMainRank();
            // 炸弹/王炸留到最后；能一把走完（restHands == 0）当然照打
            if (p.isBombLike() && restHands > 0) {
                score -= 2.5;
            }
        }
        // 拆炸弹重罚：拆了以后就再没有炸弹，代价比"多出几张"大得多
        Map<Integer, Integer> before = countRanks(hand);
        Map<Integer, Integer> after = countRanks(rest);
        for (Map.Entry<Integer, Integer> e : before.entrySet()) {
            if (e.getValue() == 4 && after.getOrDefault(e.getKey(), 0) < 4) {
                score -= 1.6;
            }
        }
        return score;
    }

    /**
     * 枚举可出的候选（addon LE 的简化：直接用 {@link DdzPattern#findBeating} 的分类枚举）。
     *
     * @param target null = 首出
     */
    public static List<List<Integer>> enumerate(List<Integer> hand, DdzPattern target) {
        return enumerate(hand, target, 0);
    }

    /**
     * 带癞子的候选枚举（2026-10-24）。
     *
     * <p>先跑一遍常规枚举，再补上「癞子参与的形态」。补的只有三类：
     * 用癞子补 <b>对子 / 三张 / 炸弹</b>、补 <b>顺子</b>缺口、补 <b>连对</b>缺口。
     * 三带 / 飞机的癞子版本刻意不做 —— 候选数会翻几倍，收益远不如上面几类。</p>
     *
     * <p>合法性统一交给调用方的 {@link DdzPattern#identify(List, int)} 终检，
     * 这里宁可多吐几个。</p>
     */
    public static List<List<Integer>> enumerate(List<Integer> hand, DdzPattern target,
                                                int wildRank) {
        return enumerate(hand, target, wildRank, 0);
    }

    /** 双癞子版（天地癞子）：两个点数都当万能牌枚举。 */
    public static List<List<Integer>> enumerate(List<Integer> hand, DdzPattern target,
                                                int wildRank, int heavenRank) {
        List<List<Integer>> out = enumerateBase(hand, target);
        if (wildRank > 0 || heavenRank > 0) {
            addWildCandidates(hand, wildRank, heavenRank, out);
        }
        return out;
    }

    private static List<List<Integer>> enumerateBase(List<Integer> hand, DdzPattern target) {
        List<List<Integer>> out = new ArrayList<>();
        if (hand.isEmpty()) {
            return out;
        }
        if (target == null) {
            // 首出：枚举各类（保守：单/对/三/顺/连对等的基本组合）
            List<Integer> sorted = DdzCards.sortedCopy(hand);
            // 单张（每个 rank 一张）
            Map<Integer, List<Integer>> byRank = new HashMap<>();
            for (int id : sorted) {
                byRank.computeIfAbsent(DdzCards.rank(id), k -> new ArrayList<>()).add(id);
            }
            for (int r : new java.util.TreeSet<>(byRank.keySet())) {
                out.add(new ArrayList<>(List.of(byRank.get(r).get(0))));
            }
            // 对子/三张/炸弹
            for (Map.Entry<Integer, List<Integer>> e : byRank.entrySet()) {
                if (e.getValue().size() >= 2) {
                    out.add(new ArrayList<>(e.getValue().subList(0, 2)));
                }
                if (e.getValue().size() >= 3) {
                    out.add(new ArrayList<>(e.getValue().subList(0, 3)));
                }
                if (e.getValue().size() == 4) {
                    out.add(new ArrayList<>(e.getValue()));
                }
            }
            // 王炸
            List<Integer> small = byRank.get(DdzCards.RANK_SMALL_JOKER);
            List<Integer> big = byRank.get(DdzCards.RANK_BIG_JOKER);
            if (small != null && big != null) {
                out.add(new ArrayList<>(List.of(small.get(0), big.get(0))));
            }
            // 顺子 / 连对：**枚举所有长度**。旧版写死 addChains(1,5) 与 addChains(2,3)，
            // 手上明明有 3~10 的整段也只会出 5 张，白白多打好几轮。
            for (int len = 5; len <= 12; len++) {
                addChains(byRank, out, 1, len, 14);
            }
            for (int len = 3; len <= 10; len++) {
                addChains(byRank, out, 2, len, 14);
            }
            // 三带一 / 三带二（四张不拆，留作炸弹）
            for (Map.Entry<Integer, List<Integer>> e : byRank.entrySet()) {
                List<Integer> g = e.getValue();
                if (g.size() < 3 || g.size() == 4) {
                    continue;
                }
                int r = e.getKey();
                List<Integer> one = kickers(byRank, r, r, 1);
                if (one.size() == 1) {
                    List<Integer> mv = new ArrayList<>(g.subList(0, 3));
                    mv.add(one.get(0));
                    out.add(mv);
                }
                List<Integer> pair = pairKickers(byRank, r, r, 1);
                if (pair.size() == 2) {
                    List<Integer> mv = new ArrayList<>(g.subList(0, 3));
                    mv.addAll(pair);
                    out.add(mv);
                }
            }
            // 飞机（连续 2..6 组三张）：不带 / 带单 / 带对
            for (int len = 2; len <= 6; len++) {
                for (int start = 3; start + len - 1 <= 14; start++) {
                    List<Integer> body = new ArrayList<>();
                    boolean ok = true;
                    for (int k = 0; k < len; k++) {
                        List<Integer> ids = byRank.get(start + k);
                        if (ids == null || ids.size() < 3 || ids.size() == 4) {
                            ok = false;
                            break;
                        }
                        body.addAll(ids.subList(0, 3));
                    }
                    if (!ok) {
                        continue;
                    }
                    out.add(new ArrayList<>(body));
                    List<Integer> one = kickers(byRank, start, start + len - 1, len);
                    if (one.size() == len) {
                        List<Integer> mv = new ArrayList<>(body);
                        mv.addAll(one);
                        out.add(mv);
                    }
                    List<Integer> pair = pairKickers(byRank, start, start + len - 1, len);
                    if (pair.size() == len * 2) {
                        List<Integer> mv = new ArrayList<>(body);
                        mv.addAll(pair);
                        out.add(mv);
                    }
                }
            }
            // 四带二（单 / 对）：会拆炸弹，但确实是合法牌型，且有时是唯一的一手走完
            for (Map.Entry<Integer, List<Integer>> e : byRank.entrySet()) {
                if (e.getValue().size() != 4) {
                    continue;
                }
                int r = e.getKey();
                List<Integer> one = kickers(byRank, r, r, 2);
                if (one.size() == 2) {
                    List<Integer> mv = new ArrayList<>(e.getValue());
                    mv.addAll(one);
                    out.add(mv);
                }
                List<Integer> pair = pairKickers(byRank, r, r, 2);
                if (pair.size() == 4) {
                    List<Integer> mv = new ArrayList<>(e.getValue());
                    mv.addAll(pair);
                    out.add(mv);
                }
            }
            return out;
        }
        // 跟牌：按目标牌型枚举同型候选
        addSameKind(hand, target, out);
        // 炸弹/王炸
        Map<Integer, List<Integer>> byRank = new HashMap<>();
        for (int id : hand) {
            byRank.computeIfAbsent(DdzCards.rank(id), k -> new ArrayList<>()).add(id);
        }
        if (!target.isRocket()) {
            for (Map.Entry<Integer, List<Integer>> e : byRank.entrySet()) {
                if (e.getValue().size() == 4) {
                    out.add(new ArrayList<>(e.getValue()));
                }
            }
            List<Integer> small = byRank.get(DdzCards.RANK_SMALL_JOKER);
            List<Integer> big = byRank.get(DdzCards.RANK_BIG_JOKER);
            if (small != null && big != null) {
                out.add(new ArrayList<>(List.of(small.get(0), big.get(0))));
            }
        }
        return out;
    }

    /** 同型候选（复用 DdzPattern.findBeating 的多解扩展：这里只取每个起点一个解）。 */
    private static void addSameKind(List<Integer> hand, DdzPattern target, List<List<Integer>> out) {
        Map<Integer, List<Integer>> byRank = new HashMap<>();
        for (int id : DdzCards.sortedCopy(hand)) {
            byRank.computeIfAbsent(DdzCards.rank(id), k -> new ArrayList<>()).add(id);
        }
        switch (target.getKind()) {
            case SINGLE -> {
                for (int r = target.getMainRank() + 1; r <= 17; r++) {
                    List<Integer> ids = byRank.get(r);
                    if (ids != null && !ids.isEmpty()) {
                        out.add(new ArrayList<>(List.of(ids.get(0))));
                    }
                }
            }
            case PAIR, TRIPLE -> {
                int need = target.getKind() == DdzPattern.Kind.PAIR ? 2 : 3;
                for (int r = target.getMainRank() + 1; r <= 15; r++) {
                    List<Integer> ids = byRank.get(r);
                    if (ids != null && ids.size() >= need) {
                        out.add(new ArrayList<>(ids.subList(0, need)));
                    }
                }
            }
            case STRAIGHT -> addChains(byRank, out, 1, target.getChainLength(), 14, target.getMainRank());
            case CONSECUTIVE_PAIRS -> addChains(byRank, out, 2, target.getChainLength(), 14, target.getMainRank());
            case BOMB -> {
                for (int r = target.getMainRank() + 1; r <= 15; r++) {
                    List<Integer> ids = byRank.get(r);
                    if (ids != null && ids.size() == 4) {
                        out.add(new ArrayList<>(ids));
                    }
                }
            }
            default -> {
                // 复杂牌型（三带/飞机/四带）：走 findBeating 的多起点变体
                List<Integer> found = DdzPattern.findBeating(hand, target);
                if (found != null) {
                    out.add(found);
                }
            }
        }
    }

    private static void addChains(Map<Integer, List<Integer>> byRank, List<List<Integer>> out,
                                  int unit, int chainLength, int maxRank) {
        addChains(byRank, out, unit, chainLength, maxRank, -1);
    }

    /** 顺子/连对滑窗枚举；{@code aboveMax} >0 时只枚举主 rank 更大的。 */
    private static void addChains(Map<Integer, List<Integer>> byRank, List<List<Integer>> out,
                                  int unit, int chainLength, int maxRank, int aboveMax) {
        for (int start = 3; start + chainLength - 1 <= maxRank; start++) {
            int end = start + chainLength - 1;
            if (aboveMax >= 0 && end <= aboveMax) {
                continue;
            }
            boolean ok = true;
            List<Integer> move = new ArrayList<>();
            for (int r = start; r <= end; r++) {
                List<Integer> ids = byRank.get(r);
                if (ids == null || ids.size() < unit) {
                    ok = false;
                    break;
                }
                move.addAll(ids.subList(0, unit));
            }
            if (ok) {
                out.add(move);
            }
        }
    }

    /**
     * 癞子参与的候选（2026-10-24）。
     *
     * <p>只补「用癞子把某个点数凑成对子/三张/炸弹」和「顺子/连对缺口」这几种形态。
     * 带牌类（三带、飞机、四带）的癞子版本不做，避免候选爆炸。</p>
     */
    private static void addWildCandidates(List<Integer> hand, int wildRank, int heavenRank,
                                          List<List<Integer>> out) {
        List<Integer> wilds = new ArrayList<>();
        Map<Integer, List<Integer>> byRank = new HashMap<>();
        for (int id : DdzCards.sortedCopy(hand)) {
            if (DdzCards.isWild(id, heavenRank, wildRank)) {
                wilds.add(id);
            } else {
                byRank.computeIfAbsent(DdzCards.rank(id), k -> new ArrayList<>()).add(id);
            }
        }
        int k = wilds.size();
        if (k == 0) {
            return;
        }
        // 补对子 / 三张 / 炸弹
        for (int r = 3; r <= 15; r++) {
            List<Integer> ids = byRank.get(r);
            int have = ids == null ? 0 : ids.size();
            if (have == 0) {
                continue;
            }
            for (int total = 2; total <= 4; total++) {
                int need = total - have;
                if (have >= total || need > k) {
                    continue;
                }
                List<Integer> mv = new ArrayList<>(ids);
                mv.addAll(wilds.subList(0, need));
                out.add(mv);
            }
        }
        // 补顺子（5..12 张）
        for (int len = 5; len <= 12; len++) {
            for (int start = 3; start + len - 1 <= 14; start++) {
                int need = 0;
                boolean ok = true;
                List<Integer> body = new ArrayList<>();
                for (int r = start; r < start + len; r++) {
                    List<Integer> ids = byRank.get(r);
                    if (ids == null || ids.isEmpty()) {
                        if (++need > k) {
                            ok = false;
                            break;
                        }
                    } else {
                        body.add(ids.get(0));
                    }
                }
                if (!ok || need == 0) {
                    continue;
                }
                body.addAll(wilds.subList(0, need));
                out.add(body);
            }
        }
        // 补连对（3..10 对）
        for (int groups = 3; groups <= 10; groups++) {
            for (int start = 3; start + groups - 1 <= 14; start++) {
                int need = 0;
                boolean ok = true;
                List<Integer> body = new ArrayList<>();
                for (int r = start; r < start + groups; r++) {
                    List<Integer> ids = byRank.get(r);
                    int have = ids == null ? 0 : Math.min(2, ids.size());
                    if (have > 0) {
                        body.addAll(ids.subList(0, have));
                    }
                    need += 2 - have;
                    if (need > k) {
                        ok = false;
                        break;
                    }
                }
                if (!ok || need == 0) {
                    continue;
                }
                body.addAll(wilds.subList(0, need));
                out.add(body);
            }
        }
    }

    /**
     * 取 {@code need} 张最小的带牌（单张），跳过 rank 区间 {@code [lo, hi]}。
     *
     * <p>两轮：先只拿「本来就单着」的牌（不破坏任何组合），不够再拆对子。
     * <b>绝不动炸弹</b>（4 张）——拆炸弹的代价远大于随便带一张。</p>
     */
    private static List<Integer> kickers(Map<Integer, List<Integer>> byRank,
                                         int lo, int hi, int need) {
        List<Integer> out = new ArrayList<>();
        for (int r = 3; r <= 17 && out.size() < need; r++) {
            if (r >= lo && r <= hi) {
                continue;
            }
            List<Integer> ids = byRank.get(r);
            if (ids != null && ids.size() == 1) {
                out.add(ids.get(0));
            }
        }
        for (int r = 3; r <= 17 && out.size() < need; r++) {
            if (r >= lo && r <= hi) {
                continue;
            }
            List<Integer> ids = byRank.get(r);
            if (ids != null && ids.size() == 2) {
                out.add(ids.get(0));
            }
        }
        return out;
    }

    /** 取 {@code pairs} 个最小的对子当带牌；跳过 {@code [lo, hi]}，不拆炸弹。 */
    private static List<Integer> pairKickers(Map<Integer, List<Integer>> byRank,
                                             int lo, int hi, int pairs) {
        List<Integer> out = new ArrayList<>();
        for (int r = 3; r <= 17 && out.size() < pairs * 2; r++) {
            if (r >= lo && r <= hi) {
                continue;
            }
            List<Integer> ids = byRank.get(r);
            if (ids != null && ids.size() >= 2 && ids.size() < 4) {
                out.add(ids.get(0));
                out.add(ids.get(1));
            }
        }
        return out;
    }

    private static Map<Integer, Integer> countRanks(List<Integer> cards) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int id : cards) {
            counts.merge(DdzCards.rank(id), 1, Integer::sum);
        }
        return counts;
    }

    /** 随机延迟用的辅助（1~2 秒，addon automatic）。 */
    public static long autoDelayTicks(Random random) {
        return 20 + random.nextInt(20); // 1~2 秒
    }
}
