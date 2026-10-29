package com.tabletopgames.game.poker;

import java.util.Random;

/**
 * 扑克机器人，两档。
 *
 * <ul>
 *   <li>{@link #EASY}：<b>与「托管」完全同一套</b>——只看起手牌/成牌档位 + 固定频率，
 *       不做蒙特卡洛。玩家掉线后由系统代打时也走这一档，保证「初级 ≈ 托管」。</li>
 *   <li>{@link #HARD}：蒙特卡洛胜率 + 底池赔率 + 位置感，会诈唬。</li>
 * </ul>
 *
 * <p>入参只带「自己能看到的信息」：自己的两张牌、公共牌、底池、跟注额、剩余筹码、
 * 还在牌局里的人数；<b>绝不传对手底牌</b>。</p>
 */
public final class PokerBotAi {

    public static final int EASY = 0;
    public static final int HARD = 1;

    /** 蒙特卡洛次数（高级档）。200 次在 4 人桌约 1~2ms，走异步线程更无压力。 */
    private static final int ITERATIONS = 200;

    public record Move(PokerRound.Action action, int amount) {
        public static Move of(PokerRound.Action a) {
            return new Move(a, 0);
        }
    }

    private PokerBotAi() {
    }

    public static Move decide(int level, int[] hole, int[] board, int boardCount,
                              int pot, int toCall, int currentBet, int stack,
                              int alive, Random rnd) {
        if (stack <= 0) {
            return Move.of(PokerRound.Action.CHECK);
        }
        double equity = level == HARD
                ? equity(hole, board, boardCount, alive, rnd)
                : heuristic(hole, board, boardCount);

        if (toCall <= 0) {
            if (equity > 0.72) {
                int raise = Math.max(10, (int) (pot * 0.7));
                return new Move(PokerRound.Action.RAISE, clamp(currentBet + raise, currentBet, stack));
            }
            if (equity > 0.52 && rnd.nextDouble() < 0.55) {
                int raise = Math.max(10, (int) (pot * 0.4));
                return new Move(PokerRound.Action.RAISE, clamp(currentBet + raise, currentBet, stack));
            }
            return Move.of(PokerRound.Action.CHECK);
        }

        double potOdds = (double) toCall / Math.max(1, pot + toCall);
        if (equity > potOdds + 0.18 && equity > 0.5) {
            int raise = Math.max(10, (int) ((pot + toCall) * 0.6));
            return new Move(PokerRound.Action.RAISE, clamp(currentBet + raise, currentBet, stack));
        }
        if (equity > potOdds + 0.01) {
            return Move.of(PokerRound.Action.CALL);
        }
        // 极小注（≤ 1/10 池）时用便宜的价格看牌，避免被小盲偷池
        if (toCall <= Math.max(10, pot / 10)) {
            return Move.of(PokerRound.Action.CALL);
        }
        return Move.of(PokerRound.Action.FOLD);
    }

    private static int clamp(int target, int currentBet, int stack) {
        return Math.max(currentBet + 10, Math.min(target, stack));
    }

    // ------------------------------------------------------------------
    // 初级：与托管同款
    // ------------------------------------------------------------------

    private static double heuristic(int[] hole, int[] board, int boardCount) {
        int r0 = PokerCards.rank(hole[0]);
        int r1 = PokerCards.rank(hole[1]);
        boolean pair = r0 == r1;
        boolean suited = PokerCards.suit(hole[0]) == PokerCards.suit(hole[1]);
        boolean high0 = r0 >= 8;      // 10 以上
        boolean high1 = r1 >= 8;

        double pre;
        if (pair) {
            pre = 0.52 + r0 * 0.02;
        } else if (high0 && high1) {
            pre = 0.50 + (suited ? 0.03 : 0.0);
        } else if (high0 || high1) {
            pre = 0.38 + (suited ? 0.02 : 0.0);
        } else {
            pre = 0.26;
        }
        if (boardCount < 3) {
            return Math.min(0.95, pre);
        }
        int[] all = new int[2 + boardCount];
        all[0] = hole[0];
        all[1] = hole[1];
        System.arraycopy(board, 0, all, 2, boardCount);
        PokerHandEval.Result r = PokerHandEval.best(all, all.length);
        int cat = (int) (r.score() >>> 20);
        double post = 0.18 + cat * 0.11;
        // 手里有对子时略微加权（避免「公共牌一对就当自己有牌」）
        if (pair) {
            post += 0.05;
        }
        return Math.min(0.97, Math.max(pre, post));
    }

    // ------------------------------------------------------------------
    // 高级：蒙特卡洛
    // ------------------------------------------------------------------

    private static double equity(int[] hole, int[] board, int boardCount, int alive, Random rnd) {
        int opponents = Math.max(1, alive - 1);
        boolean[] used = new boolean[PokerCards.DECK];
        used[hole[0]] = true;
        used[hole[1]] = true;
        for (int i = 0; i < boardCount; i++) {
            used[board[i]] = true;
        }
        int[] pool = new int[PokerCards.DECK];
        int n = 0;
        for (int id = 0; id < PokerCards.DECK; id++) {
            if (!used[id]) {
                pool[n++] = id;
            }
        }

        int[] my = new int[7];
        int[] boardFull = new int[5];
        System.arraycopy(board, 0, boardFull, 0, boardCount);
        int wins = 0;
        int ties = 0;

        for (int it = 0; it < ITERATIONS; it++) {
            // 局部洗牌：只洗前 (5-boardCount) + 2*opponents 张
            int need = (5 - boardCount) + 2 * opponents;
            for (int k = 0; k < need; k++) {
                int j = k + rnd.nextInt(n - k);
                int t = pool[k];
                pool[k] = pool[j];
                pool[j] = t;
            }
            System.arraycopy(board, 0, boardFull, 0, boardCount);
            for (int k = 0; k < 5 - boardCount; k++) {
                boardFull[boardCount + k] = pool[k];
            }
            my[0] = hole[0];
            my[1] = hole[1];
            System.arraycopy(boardFull, 0, my, 2, 5);
            long mine = PokerHandEval.best(my, 7).score();

            long bestOther = -1;
            int base = 5 - boardCount;
            for (int o = 0; o < opponents; o++) {
                int c0 = pool[base + o * 2];
                int c1 = pool[base + o * 2 + 1];
                int[] other = new int[7];
                other[0] = c0;
                other[1] = c1;
                System.arraycopy(boardFull, 0, other, 2, 5);
                long s = PokerHandEval.best(other, 7).score();
                if (s > bestOther) {
                    bestOther = s;
                }
            }
            if (mine > bestOther) {
                wins++;
            } else if (mine == bestOther) {
                ties++;
            }
        }
        return (wins + ties * 0.5) / ITERATIONS;
    }
}
