package com.tabletopgames.game.ddz;

/**
 * {@link DdzRound} 与结算逻辑需要的配置<b>视图</b>（只 4 个标量）。
 * <p>
 * 为什么不直接传 {@code TabletopGamesConfig.DdzSettings}：
 * {@code DdzRound} 是纯逻辑类、要能脱机回归（{@code tools/ddz/DdzBidCheck.java}），
 * 不能拖进 Okaeri 的配置类（那条链会把 snakeyaml 一起拖进来）。
 * <p>
 * 每局开局时由牌桌从配置节快照一次，之后本局内不再改 —— 避免玩家中途改配置
 * 让已扣的本金与底分对不上。
 */
public final class DdzSettingsView {

    /** 档位底分（免费场 0 / 初级 50 / 高级 800 / 大师 10000）。 */
    private final double baseScore;
    /** 倍数上限（叫抢 2^n + 炸弹/春天 ×2 会指数增长，靠它截断）。 */
    private final int maxMultiplier;
    /** 入座<b>门槛</b>金额（= 底分 × 门槛系数；免费场 0）。只用于校验余额，不扣钱。 */
    private final double stake;
    /** 抽水百分比（0~100）。 */
    private final double cut;

    public DdzSettingsView(double baseScore, int maxMultiplier, double stake, double cut) {
        this.baseScore = baseScore;
        this.maxMultiplier = maxMultiplier;
        this.stake = stake;
        this.cut = cut;
    }

    public double getBaseScore() {
        return baseScore;
    }

    public int getMaxMultiplier() {
        return maxMultiplier;
    }

    public double getStake() {
        return stake;
    }

    public double getCut() {
        return cut;
    }

    /** 免费场（底分 0）：不扣钱、不结算。 */
    public boolean isFree() {
        return baseScore <= 0.0;
    }
}