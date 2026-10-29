package com.tabletopgames.game.poker;

import java.util.ArrayList;
import java.util.List;

/**
 * 底池拆分：主池 + 边池。
 *
 * <p>规则：按每人本局<b>总投入</b>分层，每一层由「投入不少于该层的未弃牌玩家」争夺。
 * 弃牌玩家的钱仍留在池里，但不参与分配 —— 这正是边池存在的意义。</p>
 */
public final class PokerPot {

    /**
     * 一个池。
     *
     * @param amount 金额
     * @param seats  有资格争夺的座位（未弃牌且投入 ≥ 该层）
     */
    public record Pot(int amount, int[] seats) {
    }

    private PokerPot() {
    }

    /**
     * @param total  每座位本局总投入
     * @param folded 每座位是否已弃牌
     */
    public static List<Pot> build(int[] total, boolean[] folded) {
        List<Pot> out = new ArrayList<>();
        int n = total.length;
        boolean[] levelDone = new boolean[n];
        int prev = 0;
        while (true) {
            int level = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!levelDone[i] && total[i] > prev) {
                    level = Math.min(level, total[i]);
                }
            }
            if (level == Integer.MAX_VALUE) {
                break;
            }
            int amount = 0;
            List<Integer> eligible = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int add = Math.min(total[i], level) - Math.min(total[i], prev);
                if (add > 0) {
                    amount += add;
                }
                if (!folded[i] && total[i] >= level) {
                    eligible.add(i);
                }
                if (total[i] <= level) {
                    levelDone[i] = true;
                }
            }
            if (amount > 0) {
                if (eligible.isEmpty()) {
                    // 无人有资格（例如唯一投入这一层的人已经弃牌）：并进上一个池，
                    // 保证「池总额 == 所有人投入之和」，钱不会凭空消失。
                    if (!out.isEmpty()) {
                        Pot last = out.remove(out.size() - 1);
                        out.add(new Pot(last.amount() + amount, last.seats()));
                    }
                } else {
                    int[] seats = new int[eligible.size()];
                    for (int k = 0; k < seats.length; k++) {
                        seats[k] = eligible.get(k);
                    }
                    out.add(new Pot(amount, seats));
                }
            }
            prev = level;
        }
        return out;
    }

    /** 未被任何池认领的零头（理论上为 0，用于自检）。 */
    public static int sum(List<Pot> pots) {
        int s = 0;
        for (Pot p : pots) {
            s += p.amount();
        }
        return s;
    }
}
