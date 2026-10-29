package com.tabletopgames.game.ddz;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 出牌区（桌面上打出的那一排）的排列顺序（2026-10-27 用户 #1 / #3）。
 *
 * <h3>规则（用户原话）</h3>
 * <blockquote>
 * 「不要把出牌的那个癞子跑到最右边去…… 癞子在同点数内部统一在左边…… 总结就是把癞子看成
 * 内部顺序大的牌。」<br>
 * 「牌桌上的那个排列也（跟手牌排序开关）同步就行。」
 * </blockquote>
 *
 * <p>所以排序键 = <b>(当值, 癞子优先, 牌 id)</b>：</p>
 * <ol>
 *   <li>先按<b>当值</b>排（癞子算它当成的点数，不是它自己的点数）；</li>
 *   <li>同点数时把癞子当成<b>内部更大</b>（升序在后、降序在前）；</li>
 *   <li>剩下的按牌 id（同点数里就是花色顺序，反正牌面一样）；</li>
 *   <li>方向：{@code 小→大} 用本方法的 {@code asc=true}；{@code 大→小} 由客户端把整排
 *       镜像（{@code v.sN = 张数-1-sN}），等价于把上面这个<b>严格全序</b>整个倒过来。</li>
 * </ol>
 *
 * <p>用户给的例子：{@code 3带2} 打 {@code 55588}，其中两张是「癞子当 5」、一张是
 * 「癞子当 8」，小→大时顺序必须是
 * <b>癞5 癞5 正5 癞8 正8</b>（真机回归见 {@code tools/ddz/DdzPlayOrderCheck.java}）。</p>
 */
public final class DdzPlayOrder {

    private DdzPlayOrder() {
    }

    /**
     * @param cards  本手打出的牌 id（顺序无所谓）
     * @param asRank 牌 id → 当值；返回 {@code <=0} 表示「没记，当它自己」
     * @param isWild 牌 id 是否癞子（天 / 地两个点数都算）
     * @param asc    {@code true} = 小→大（大→小时当值倒序）
     */
    public static List<Integer> sorted(List<Integer> cards, ToIntFunction<Integer> asRank,
                                       Predicate<Integer> isWild, boolean asc) {
        List<Integer> out = new ArrayList<>();
        if (cards == null) {
            return out;
        }
        for (int id : cards) {
            if (DdzCards.isValid(id)) {
                out.add(id);
            }
        }
        out.sort((a, b) -> {
            int ra = rankOf(a, asRank);
            int rb = rankOf(b, asRank);
            if (ra != rb) {
                return asc ? Integer.compare(ra, rb) : Integer.compare(rb, ra);
            }
            boolean wa = isWild != null && isWild.test(a);
            boolean wb = isWild != null && isWild.test(b);
            if (wa != wb) {
                // 同点数：癞子算「内部更大」⇒ 升序排在同点数**后面**、降序排在**前面**。
                // ⚠️ 这条必须跟着方向翻：出牌区现在是「服务端只算升序 + 客户端整体镜像」，
                //    镜像会把「升序里在后」翻成「降序里在前」——正是用户要的
                //    55588 ⇒ 癞8 正8 癞5 癞5 正5。
                return asc ? (wa ? 1 : -1) : (wa ? -1 : 1);
            }
            return Integer.compare(a, b);
        });
        return out;
    }

    private static int rankOf(int id, ToIntFunction<Integer> asRank) {
        if (asRank != null) {
            int as = asRank.applyAsInt(id);
            if (as > 0) {
                return as;
            }
        }
        return DdzCards.rank(id);
    }

    /**
     * 把「当前顺序 → 目标顺序」压成尽量少的「第 from 位挪到第 to 位」指令
     * （2026-10-27 晚：修「出牌区整排乱掉」+ 把指令数压到最少）。
     *
     * <h3>怎么挑「最少几张要动」</h3>
     * <p>保留「相对顺序最长的那一串」（最长上升子序列）不动，剩下的才一张张插进去 ⇒
     * 指令数 = {@code 张数 − 最长上升子序列长度}，已经是理论最少。QQKKAA（癞子当 K）只要
     * <b>1 条</b>（把 Q、Q、K 那张癞子挪到 3 号位），老「从左往右逐位归位」要 3 条。</p>
     *
     * <h3>为什么还要试顺序</h3>
     * <p>插入是「按位置」的：把第 {@code from} 张插到第 {@code to} 位，会让 {@code from} 和
     * {@code to} 之间的牌整体挪一格。如果先插的位置夹在别人的 {@code from}/{@code to} 之间，
     * 已经摆好的牌会被带偏。所以这里把「动的那几张」按几种顺序各模拟一遍，<b>模拟结果
     * 正好等于目标顺序</b>才采用（最多 4 张 ⇒ 最多 24 种排列，代价可以忽略）；
     * 都不行就退回「从左往右逐位归位」——那个一定对，只是可能多用一条。</p>
     *
     * <h3>客户端的语义</h3>
     * <p>指令<b>顺序执行</b>：{@code sN == from ⇒ sN = to}、{@code from < sN ≤ to ⇒ sN-1}、
     * {@code to ≤ sN < from ⇒ sN+1}（等价于「把 from 那张抽出来插到 to」）。</p>
     *
     * @param cur   当前顺序（按牌 id 升序，即客户端的区内序号）
     * @param want  目标顺序
     * @param limit 最多几条（属性一共 4 档）
     * @return 每条 {@code {from, to}}；超出 {@code limit} 的部分不下发，剩下的牌保持原相对
     *         顺序（<b>不会重叠</b>，只是顺序不完全是目标顺序）
     */
    public static List<int[]> moves(List<Integer> cur, List<Integer> want, int limit) {
        List<int[]> out = new ArrayList<>();
        if (cur == null || want == null || cur.size() != want.size() || limit <= 0) {
            return out;
        }
        int n = want.size();
        if (n < 2) {
            return out;
        }
        int[] pos = new int[n];
        for (int i = 0; i < n; i++) {
            pos[i] = cur.indexOf(want.get(i));
            if (pos[i] < 0) {
                return out;   // 不是同一批牌，别赌
            }
        }
        // 最长上升子序列（n ≤ 32，直接 O(n²)）：这些牌不用动
        int[] best = new int[n];
        int[] prev = new int[n];
        int len = 0;
        int end = 0;
        for (int i = 0; i < n; i++) {
            best[i] = 1;
            prev[i] = -1;
            for (int j = 0; j < i; j++) {
                if (pos[j] < pos[i] && best[j] + 1 > best[i]) {
                    best[i] = best[j] + 1;
                    prev[i] = j;
                }
            }
            if (best[i] > len) {
                len = best[i];
                end = i;
            }
        }
        boolean[] keep = new boolean[n];
        for (int i = end; i >= 0; i = prev[i]) {
            keep[i] = true;
            if (prev[i] < 0) {
                break;
            }
        }
        List<Integer> movers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!keep[i]) {
                movers.add(want.get(i));
            }
        }
        if (movers.isEmpty()) {
            return out;
        }
        // 试几种插入顺序，谁能真的还原目标顺序就用谁
        List<List<Integer>> orders = new ArrayList<>();
        orders.add(new ArrayList<>(movers));
        List<Integer> rev = new ArrayList<>(movers);
        java.util.Collections.reverse(rev);
        if (!rev.equals(movers)) {
            orders.add(rev);
        }
        if (movers.size() <= 4) {
            permute(movers, 0, orders);
        }
        for (List<Integer> order : orders) {
            List<int[]> mv = new ArrayList<>();
            List<Integer> work = new ArrayList<>(cur);
            boolean broken = false;
            for (int card : order) {
                int to = want.indexOf(card);
                int from = work.indexOf(card);
                if (to < 0 || from < 0) {
                    broken = true;
                    break;
                }
                if (from != to) {
                    mv.add(new int[]{from, to});
                }
                work.remove(from);
                work.add(to, card);
            }
            if (!broken && work.equals(want)) {
                return mv.size() > limit ? new ArrayList<>(mv.subList(0, limit)) : mv;
            }
        }
        return greedy(cur, want, limit);
    }

    /** 兜底：从左往右逐位归位（一定能把顺序摆对，可能多用一两条）。 */
    private static List<int[]> greedy(List<Integer> cur, List<Integer> want, int limit) {
        List<int[]> out = new ArrayList<>();
        List<Integer> work = new ArrayList<>(cur);
        int n = work.size();
        for (int p = 0; p < n && out.size() < limit; p++) {
            if (work.get(p).intValue() == want.get(p).intValue()) {
                continue;
            }
            int q = work.indexOf(want.get(p));
            if (q <= p) {
                continue;
            }
            out.add(new int[]{q, p});
            work.add(p, work.remove(q));
        }
        return out;
    }

    /** 全排列（只给 ≤ 4 张用）。 */
    private static void permute(List<Integer> src, int k, List<List<Integer>> out) {
        if (k == src.size()) {
            out.add(new ArrayList<>(src));
            return;
        }
        for (int i = k; i < src.size(); i++) {
            java.util.Collections.swap(src, k, i);
            permute(src, k + 1, out);
            java.util.Collections.swap(src, k, i);
        }
    }
}
