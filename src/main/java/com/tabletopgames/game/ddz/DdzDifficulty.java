package com.tabletopgames.game.ddz;

/**
 * 机器人难度（<b>两档</b>：初级 / 高级）。
 *
 * <p>2026-10-22 用户要求：只保留初级与高级，<b>托管 / 离线 / away 代打一律走初级</b>
 * （免得人一走反而打出比本人更强的战力）。原来的 {@code MEDIUM} 档已删除，
 * 配置里的 {@code medium} / {@code hard} 一律迁移到 {@link #ADVANCED}。</p>
 *
 * <ul>
 *   <li>{@link #SIMPLE}：权重打分 + 规则（{@link DdzBotAi}）。微秒级、纯算术、公平（只看自己手牌）。</li>
 *   <li>{@link #ADVANCED}：蒙特卡洛推演（{@link DdzRolloutAi}）—— 对每种候选出法，
 *       按对手剩余张数随机采样若干套手牌，各打一条贪心线到终局，取胜率最高的一手。
 *       毫秒~秒级，必须走 {@code AiThinkDispatcher.heavy}。</li>
 * </ul>
 *
 * <p>⚠️ 两个档位的决策函数都已改成<b>纯函数</b>（只吃传值快照，不碰 {@code DdzRound}），
 * 这是它们能被搬到后台线程的前提。</p>
 */
public enum DdzDifficulty {

    SIMPLE("初级"),
    ADVANCED("高级");

    private final String label;

    DdzDifficulty(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 从配置字符串解析；老值迁移：{@code medium} / {@code hard} / {@code advanced} 一律 → {@link #ADVANCED}，
     * 其它非法值也回落 {@link #ADVANCED}（与旧版「非法回落 medium」等价）。
     */
    public static DdzDifficulty fromConfig(String raw) {
        if (raw != null) {
            String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if ("simple".equals(t)) {
                return SIMPLE;
            }
        }
        return ADVANCED;
    }
}
