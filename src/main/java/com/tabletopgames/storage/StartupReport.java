package com.tabletopgames.storage;

import cn.nukkit.plugin.PluginBase;

/**
 * 启动期「数据恢复」日志汇总（2026-10-27 用户要求：控制台别刷一屏恢复提示）。
 *
 * <h3>为什么要有它</h3>
 * <p>九套棋牌各自恢复自己的存档，原来各打各的 info：{@code 已从数据库恢复 N 张斗地主牌桌} /
 * {@code 已从磁盘恢复 N 个围棋棋盘} …… 开服时用户会看到八九行，而且条数对排查没有帮助
 * （用户原话：「不需要弄那么多的提示」「不需要细化到啥」）。</p>
 *
 * <p>现在每个 manager 恢复完只调一次 {@link #report}，等全部到齐后由最后那个打印
 * <b>一行</b>：{@code 已从数据库恢复数据} / {@code 已从磁盘恢复数据} /
 * {@code 已从数据库与磁盘恢复数据}。具体条数降到 {@code debug} 级（默认不显示）。</p>
 *
 * <h3>到不齐怎么办</h3>
 * <p>某个 manager 万一抛异常没汇报，靠插件 tick 里的 {@link #tick} 兜底：等
 * {@link #GIVE_UP_CALLS} 次调用（每次 5 服务端 tick = 250 ms，共约 30 秒）后，
 * 只要<b>已经有人报过</b>就把现有结果打出来，避免永远不打印。</p>
 *
 * <p>⚠️ 单例状态是 {@code static}：插件 {@code onEnable} 里的 {@link #expect} 会重置，
 * 所以重载插件不会残留上一次的计数。</p>
 */
public final class StartupReport {

    /** 兜底宽限：多少轮 tick 之后即使没集齐也打印（120 × 250 ms ≈ 30 秒）。 */
    private static final int GIVE_UP_CALLS = 120;

    /** 还有几个 manager 没汇报。 */
    private static int pending;
    /** 汇总：从数据库恢复的条数。 */
    private static int fromDb;
    /** 汇总：从磁盘存档恢复的个数。 */
    private static int fromFile;
    /** 已经报过数的 manager 个数（决定兜底时打不打印）。 */
    private static int reported;
    /** 兜底计数（{@link #tick} 被调用的次数）。 */
    private static int calls;
    /** 是否已经打印过（打印后不再重复）。 */
    private static boolean printed;

    private StartupReport() {
    }

    /**
     * 开始一轮统计（插件 {@code onEnable} 调用一次）。
     *
     * @param managers 预期会汇报的 manager 个数
     */
    public static void expect(int managers) {
        pending = Math.max(0, managers);
        fromDb = 0;
        fromFile = 0;
        reported = 0;
        calls = 0;
        printed = false;
    }

    /**
     * 某个 manager 恢复完毕：报一次数（即使一个都没恢复也要报，否则凑不齐）。
     *
     * @param fromDb   从数据库恢复的条数
     * @param fromFile 从磁盘存档恢复的个数
     */
    public static void report(PluginBase plugin, int fromDb, int fromFile) {
        StartupReport.fromDb += Math.max(0, fromDb);
        StartupReport.fromFile += Math.max(0, fromFile);
        reported++;
        if (pending > 0) {
            pending--;
        }
        if (pending <= 0) {
            flush(plugin);
        }
    }

    /** 插件 tick 兜底（每 5 服务端 tick 一次）。 */
    public static void tick(PluginBase plugin) {
        if (printed || pending <= 0) {
            return;
        }
        calls++;
        // 一个都还没报过 ⇒ 世界大概还没加载完，继续等，不要打「没有数据」
        if (calls >= GIVE_UP_CALLS && reported > 0) {
            flush(plugin);
        }
    }

    private static void flush(PluginBase plugin) {
        if (printed) {
            return;
        }
        printed = true;
        if (plugin == null) {
            return;
        }
        String text;
        if (fromDb > 0 && fromFile > 0) {
            text = "已从数据库与磁盘恢复数据";
        } else if (fromDb > 0) {
            text = "已从数据库恢复数据";
        } else if (fromFile > 0) {
            text = "已从磁盘恢复数据";
        } else {
            // 全新服务器：没有可恢复的东西，不占控制台
            plugin.getLogger().debug("启动恢复：没有需要恢复的数据");
            return;
        }
        plugin.getLogger().info(text);
        plugin.getLogger().debug("启动恢复明细：数据库 " + fromDb + " 条，磁盘存档 " + fromFile + " 个");
    }
}
