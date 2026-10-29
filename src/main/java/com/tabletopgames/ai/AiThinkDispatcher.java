package com.tabletopgames.ai;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 统一的「AI 思考」调度：**主线程取快照 → 后台线程算 → 回主线程落子**。
 *
 * <h2>为什么所有 AI 都必须走这里（2026-10-22 用户要求）</h2>
 * Nukkit 的主线程是一个 50 ms 的循环，玩家的移动、方块、实体、全部牌桌的 tick 都串在这一条线程上。
 * 任何一次几十毫秒以上的计算都会把那一个 tick 撑长 —— 表现为**整服卡顿**（不是"这张桌子卡"）：
 * 别人挖方块没反应、生物不动、其它牌桌的倒计时一起顺延；撑到秒级还会被 watchdog 判无响应。
 *
 * <p>⚠️ 最容易踩的坑：{@code scheduleDelayedTask} 和 {@code scheduleTask(..., false)}
 * <b>仍然是主线程</b>，只是延后触发。真正离开主线程只有
 * {@code scheduleTask(plugin, task, true)}（Nukkit 异步池）或自建的 Executor。</p>
 *
 * <h2>两档执行器</h2>
 * <ul>
 *   <li>{@link #light}：微秒级的决策（UNO / 斗地主初级），直接用 Nukkit 异步池，
 *       与六个棋类 {@code scheduleAiMove} 同一套，不引入新基础设施；</li>
 *   <li>{@link #heavy}：秒级的搜索（斗地主高级推演），走<b>自建 2 线程池</b> ——
 *       秒级任务不能占公共异步池，否则会把区块加载 / 存盘这些活儿挤掉。</li>
 * </ul>
 *
 * <h2>调用方必须做的事</h2>
 * <ol>
 *   <li>传进来的 {@code think} <b>只能碰传值快照</b>（深拷贝的手牌 / 局面），不许引用
 *       {@code DdzRound} / {@code UnoGame} 这类活对象 —— 它们在主线程上随时会被改；</li>
 *   <li>{@code apply} 里必须校验「思考令牌」：局面变了就丢弃这次结果，等下一轮 tick 重新提交。
 *       这个类不替调用方做校验，因为"什么算变了"每个棋牌定义不同。</li>
 * </ol>
 */
public final class AiThinkDispatcher {

    /** 重活线程数：6 张桌同时轮到高级机器人时也不互相抢核，再多只会让每个都变慢。 */
    private static final int HEAVY_THREADS = 2;

    /** 重活队列上限：超了直接拒绝，调用方回落到初级决策。 */
    private static final int HEAVY_QUEUE_CAP = 8;

    private static final AtomicInteger PENDING = new AtomicInteger();

    private static ExecutorService heavy;

    private AiThinkDispatcher() {
    }

    /** 轻量决策：微秒级，走 Nukkit 异步池。 */
    public static <T> void light(Plugin plugin, Supplier<T> think, Consumer<T> apply) {
        Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
            T result = null;
            try {
                result = think.get();
            } catch (Throwable t) {
                plugin.getLogger().error("[TabletopGames] AI(light) 计算异常，按「无结果」回落", t);
            }
            final T r = result;
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> apply.accept(r), false);
        }, true);
    }

    /**
     * 重活决策：秒级搜索，走自建 2 线程池。
     *
     * @return false = 队列已满（调用方应立刻回落到初级决策，别让回合停住）
     */
    public static <T> boolean heavy(Plugin plugin, Supplier<T> think, Consumer<T> apply) {
        if (PENDING.get() >= HEAVY_QUEUE_CAP) {
            return false;
        }
        PENDING.incrementAndGet();
        try {
            pool().execute(() -> {
                T result = null;
                try {
                    result = think.get();
                } catch (Throwable t) {
                    plugin.getLogger().error("[TabletopGames] AI(heavy) 计算异常，按「无结果」回落", t);
                } finally {
                    PENDING.decrementAndGet();
                }
                final T r = result;
                Server.getInstance().getScheduler().scheduleTask(plugin, () -> apply.accept(r), false);
            });
            return true;
        } catch (RuntimeException e) {
            PENDING.decrementAndGet();
            plugin.getLogger().error("[TabletopGames] AI(heavy) 提交失败", e);
            return false;
        }
    }

    private static synchronized ExecutorService pool() {
        if (heavy == null || heavy.isShutdown()) {
            ThreadFactory factory = task -> {
                Thread thread = new Thread(task, "TabletopGames-AI");
                thread.setDaemon(true);
                return thread;
            };
            heavy = Executors.newFixedThreadPool(HEAVY_THREADS, factory);
        }
        return heavy;
    }

    /** 插件卸载时调用：立刻放掉线程。 */
    public static synchronized void shutdown() {
        if (heavy != null) {
            heavy.shutdownNow();
            heavy = null;
        }
    }
}
