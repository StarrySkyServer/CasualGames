package com.tabletopgames.game.poker;

/**
 * 德州扑克牌型评估：7 选 5，输出可比较的分值。
 *
 * <p>分值编码：{@code cat} 四位（0..8）+ 5 个 tiebreak 名次，每个 4 位（0..12），
 * 即 {@code score = ((((cat*16 + r1)*16 + r2)*16 + r3)*16 + r4)*16 + r5}。
 * 用 {@code long} 只是防止以后扩位，实际值远小于 2^31。</p>
 *
 * <p>牌型大小与常规一致：高牌 &lt; 一对 &lt; 两对 &lt; 三条 &lt; 顺子 &lt; 同花
 * &lt; 葫芦 &lt; 四条 &lt; 同花顺。A-5 的「轮子」按 5 计高牌，不能当 A 高。</p>
 */
public final class PokerHandEval {

    public static final int HIGH = 0;
    public static final int PAIR = 1;
    public static final int TWO_PAIR = 2;
    public static final int TRIPS = 3;
    public static final int STRAIGHT = 4;
    public static final int FLUSH = 5;
    public static final int FULL_HOUSE = 6;
    public static final int QUADS = 7;
    public static final int STRAIGHT_FLUSH = 8;

    private static final String[] NAMES = {
            "高牌", "一对", "两对", "三条", "顺子", "同花", "葫芦", "四条", "同花顺"};

    private PokerHandEval() {
    }

    public static String categoryName(long score) {
        int cat = (int) (score >>> 20);
        int a = (int) ((score >>> 16) & 0xF);
        int b = (int) ((score >>> 12) & 0xF);
        int c = (int) ((score >>> 8) & 0xF);
        int d = (int) ((score >>> 4) & 0xF);
        // ★ 2026-10-26 用户指定叫法（参考常见中文扑克术语）
        switch (cat) {
            case STRAIGHT_FLUSH:
                return a == 12 ? "皇家同花顺" : PokerCards.rankName(a) + "高同花顺";
            case QUADS:
                return "四条" + PokerCards.rankName(a) + "，带" + PokerCards.rankName(b);
            case FULL_HOUSE:
                return PokerCards.rankName(a) + "葫芦" + PokerCards.rankName(b);
            case FLUSH:
                return PokerCards.rankName(a) + "高同花";
            case STRAIGHT:
                return PokerCards.rankName(a) + "高顺子";
            case TRIPS:
                return "三条" + PokerCards.rankName(a) + "，带"
                        + PokerCards.rankName(b) + "、" + PokerCards.rankName(c);
            case TWO_PAIR:
                return PokerCards.rankName(a) + "和" + PokerCards.rankName(b) + "两对，带"
                        + PokerCards.rankName(c);
            case PAIR:
                return "一对" + PokerCards.rankName(a) + "，带"
                        + PokerCards.rankName(b) + "、" + PokerCards.rankName(c)
                        + "、" + PokerCards.rankName(d);
            case HIGH:
                return PokerCards.rankName(a) + "高";
            default:
                return NAMES[cat];
        }
    }

    /** 5 张牌的分值。 */
    public static long score5(int[] c) {
        int[] rc = new int[13];
        int[] sc = new int[4];
        for (int id : c) {
            rc[PokerCards.rank(id)]++;
            sc[PokerCards.suit(id)]++;
        }
        boolean flush = false;
        for (int n : sc) {
            if (n == 5) {
                flush = true;
            }
        }

        int quad = -1;
        int trips = -1;
        int pairHi = -1;
        int pairLo = -1;
        for (int r = 12; r >= 0; r--) {
            if (rc[r] == 4) {
                quad = r;
            } else if (rc[r] == 3) {
                if (trips < 0) {
                    trips = r;
                }
            } else if (rc[r] == 2) {
                if (pairHi < 0) {
                    pairHi = r;
                } else if (pairLo < 0) {
                    pairLo = r;
                }
            }
        }

        int straightHigh = straightHigh(rc);

        if (flush && straightHigh >= 0) {
            return pack(STRAIGHT_FLUSH, straightHigh, 0, 0, 0, 0);
        }
        if (quad >= 0) {
            int kicker = -1;
            for (int r = 12; r >= 0; r--) {
                if (r != quad && rc[r] > 0) {
                    kicker = r;
                    break;
                }
            }
            return pack(QUADS, quad, kicker, 0, 0, 0);
        }
        if (trips >= 0 && pairHi >= 0) {
            return pack(FULL_HOUSE, trips, pairHi, 0, 0, 0);
        }
        if (flush) {
            int[] top = topRanks(rc, 5);
            return pack(FLUSH, top[0], top[1], top[2], top[3], top[4]);
        }
        if (straightHigh >= 0) {
            return pack(STRAIGHT, straightHigh, 0, 0, 0, 0);
        }
        if (trips >= 0) {
            int[] k = kickers(rc, trips, -1, 2);
            return pack(TRIPS, trips, k[0], k[1], 0, 0);
        }
        if (pairHi >= 0 && pairLo >= 0) {
            int[] k = kickers(rc, pairHi, pairLo, 1);
            return pack(TWO_PAIR, pairHi, pairLo, k[0], 0, 0);
        }
        if (pairHi >= 0) {
            int[] k = kickers(rc, pairHi, -1, 3);
            return pack(PAIR, pairHi, k[0], k[1], k[2], 0);
        }
        int[] top = topRanks(rc, 5);
        return pack(HIGH, top[0], top[1], top[2], top[3], top[4]);
    }

    /** 7 张（或 5..7 张）里挑最好的 5 张。 */
    public static Result best(int[] cards, int count) {
        long bestScore = -1;
        int[] bestCards = new int[5];
        int[] idx = new int[5];
        if (count == 5) {
            long s = score5(cards);
            return new Result(s, cards.clone());
        }
        // 组合枚举（count<=7，最多 21 组）
        for (int a = 0; a < count - 4; a++) {
            for (int b = a + 1; b < count - 3; b++) {
                for (int c = b + 1; c < count - 2; c++) {
                    for (int d = c + 1; d < count - 1; d++) {
                        for (int e = d + 1; e < count; e++) {
                            idx[0] = cards[a];
                            idx[1] = cards[b];
                            idx[2] = cards[c];
                            idx[3] = cards[d];
                            idx[4] = cards[e];
                            long s = score5(idx);
                            if (s > bestScore) {
                                bestScore = s;
                                System.arraycopy(idx, 0, bestCards, 0, 5);
                            }
                        }
                    }
                }
            }
        }
        return new Result(bestScore, bestCards);
    }

    /** 评估结果：分值与构成它的 5 张牌。 */
    public record Result(long score, int[] cards) {
        public String category() {
            return categoryName(score);
        }
    }

    // ------------------------------------------------------------------

    private static long pack(int cat, int a, int b, int c, int d, int e) {
        return (((((long) cat * 16 + a) * 16 + b) * 16 + c) * 16 + d) * 16 + e;
    }

    /** 顺子高张的下标（A-5 轮子返回 3=5）；无顺子返回 -1。 */
    private static int straightHigh(int[] rc) {
        for (int hi = 12; hi >= 4; hi--) {
            boolean ok = true;
            for (int k = 0; k < 5; k++) {
                if (rc[hi - k] == 0) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return hi;
            }
        }
        if (rc[12] > 0 && rc[0] > 0 && rc[1] > 0 && rc[2] > 0 && rc[3] > 0) {
            return 3;
        }
        return -1;
    }

    private static int[] topRanks(int[] rc, int n) {
        int[] out = new int[n];
        int k = 0;
        for (int r = 12; r >= 0 && k < n; r--) {
            for (int i = 0; i < rc[r] && k < n; i++) {
                out[k++] = r;
            }
        }
        return out;
    }

    private static int[] kickers(int[] rc, int skip1, int skip2, int n) {
        int[] out = new int[n];
        int k = 0;
        for (int r = 12; r >= 0 && k < n; r--) {
            if (r == skip1 || r == skip2) {
                continue;
            }
            if (rc[r] > 0) {
                out[k++] = r;
            }
        }
        return out;
    }
}
