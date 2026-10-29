package com.tabletopgames.game.ddz;

import java.util.ArrayList;
import java.util.List;

/**
 * 斗地主结算（<b>与 UNO 同口径：对局结束才扣钱</b>）。
 *
 * <h2>口径（用户 2026-10-20 明确）</h2>
 * <ul>
 *   <li><b>UNO / 斗地主</b>：入座只校验余额门槛、不扣款；整局一分不动，
 *       结束时按输赢一次性结算。</li>
 *   <li><b>5 类棋</b>（五子棋/象棋/黑白棋/围棋/斗兽棋）：开局扣押金，结束结算。</li>
 * </ul>
 *
 * <p>所以本类只做<b>纯算术</b>（算账，不碰经济系统），实际扣款由
 * {@code DdzGame#settleEconomy} 执行：</p>
 * <pre>
 *   输家各扣 min(应付, 实际余额)  →  汇成奖池 pot
 *   抽水 commission = ceil(pot × cut / 100)
 *   赢家实得 = pot − commission
 * </pre>
 *
 * <h2>算式</h2>
 * <pre>
 *   应付   = -moneyDelta[seat]（负数取反；已含 底分 × 倍数 × 各家加倍因子）
 *   应得   =  moneyDelta[seat]（正数）
 *   抽水   = ceil(应得 × cut / 100)      ← 仅供前端展示，实扣以奖池算
 *   实收   = 应得 - 抽水
 * </pre>
 * 输家方向不抽水（抽水只从奖池扣，符合 UNO / 斗地主惯例）。
 * <p>
 * <b>不用模组的 {@code crAmount(e)} 对数映射</b>：那套是给模组三档
 * （180/900/4500）设计的，本插件四档是 0/50/800/10000，量级完全不同，
 * 硬套会让初级场（50）的抽水额算出 0 或负数。这里用线性。
 */
public final class DdzSettlement {

    private DdzSettlement() {
    }

    /** 单个座位的结算明细（供前端渲染三行）。 */
    public static final class Entry {
        /** 座位号 0..2。 */
        public final int seat;
        /** 输赢方向：正 = 收钱，负 = 付钱，0 = 平（免费场/机器人）。 */
        public final double delta;
        /** 应得金额（赢家 = delta；输家 0；平手 0）。 */
        public final double gross;
        /** 抽水额（只从赢家侧扣；其余为 0）。 */
        public final double rake;
        /** 实收金额 = gross - rake（赢家）；输家为 0。 */
        public final double net;

        Entry(int seat, double delta, double gross, double rake, double net) {
            this.seat = seat;
            this.delta = delta;
            this.gross = gross;
            this.rake = rake;
            this.net = net;
        }

        /** 是否赢家（前端用它决定显示三行还是显示扣款）。 */
        public boolean isWinner() {
            return net > 0.0;
        }

        /** 输家应付金额（正数；非输家为 0）。 */
        public double payable() {
            return delta < 0.0 ? -delta : 0.0;
        }

        @Override
        public String toString() {
            return "seat" + seat + " 应得=" + gross + " 抽水=" + rake + " 实收=" + net;
        }
    }

    /**
     * 按已完成的一局算明细（<b>不碰经济系统</b>，只算账）。
     * <p>
     * 真正加减余额由牌桌拿这些数字去调 {@code EconomyHook} —— 这样本类可脱机回归。
     *
     * @param round 已 FINISHED 的局
     * @param view  本局开局时快照的配置（底分/上限/本金/抽水）
     * @param human 每座位是否真人（false = 机器人或空座，不参与输赢）
     * @return 3 条明细
     */
    public static Entry[] settle(DdzRound round, DdzSettingsView view, boolean[] human) {
        Entry[] entries = new Entry[3];
        DdzRound.Result result = round.getResult();
        if (result == null) {
            for (int s = 0; s < 3; s++) {
                entries[s] = new Entry(s, 0, 0, 0, 0);
            }
            return entries;
        }
        double cutPercent = Math.max(0.0, Math.min(100.0, view.getCut()));
        boolean free = view.isFree();
        for (int s = 0; s < 3; s++) {
            boolean isHuman = human != null && s < human.length && human[s];
            double delta = result.moneyDelta[s];
            // 免费场 / 机器人 / 空座：不结算
            if (free || !isHuman || delta == 0.0) {
                entries[s] = new Entry(s, 0, 0, 0, 0);
                continue;
            }
            if (delta > 0.0) {
                double gross = round2(delta);
                double rake = Math.ceil(gross * cutPercent / 100.0 * 100.0) / 100.0;
                double net = round2(Math.max(0.0, gross - rake));
                entries[s] = new Entry(s, gross, gross, round2(rake), net);
            } else {
                // 输家：不抽水，只记应付
                entries[s] = new Entry(s, round2(delta), 0, 0, 0);
            }
        }
        return entries;
    }

    /**
     * 入座<b>门槛</b>金额（只用于校验余额，<b>不扣钱</b>）。
     *
     * <p>免费场返回 0 = 不设门槛。真正的扣款发生在
     * {@code DdzGame#settleEconomy}（对局结束时按输赢一次性结算）。</p>
     *
     * @return 门槛金额（0 = 免费场）
     */
    public static double stakeFor(DdzSettingsView view) {
        double v = view.getStake();
        return view.isFree() ? 0.0 : round2(Math.max(0.0, v));
    }

    /**
     * 抽水汇总：三家抽水之和（前端展示「本局抽水 N」）。
     * 余额不足被抽满时以实际抽水为准（{@link Entry#rake} 已按 gross 上限算）。
     */
    public static double totalRake(Entry[] entries) {
        double sum = 0.0;
        if (entries == null) {
            return 0.0;
        }
        for (Entry e : entries) {
            if (e != null) {
                sum += e.rake;
            }
        }
        return round2(sum);
    }

    /** 三条明细的中文摘要（用于日志 / 播报）。 */
    public static List<String> describe(Entry[] entries) {
        List<String> out = new ArrayList<>(3);
        if (entries == null) {
            return out;
        }
        for (Entry e : entries) {
            if (e == null) {
                continue;
            }
            if (e.isWinner()) {
                out.add("座位" + e.seat + "：应得 " + e.gross + "，抽水 −" + e.rake
                        + "，实收 " + e.net);
            } else if (e.payable() > 0.0) {
                out.add("座位" + e.seat + "：应付 " + e.payable());
            } else {
                out.add("座位" + e.seat + "：不参与结算");
            }
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}