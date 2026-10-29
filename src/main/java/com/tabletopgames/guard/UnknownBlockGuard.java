package com.tabletopgames.guard;

import cn.nukkit.utils.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MOT 核心缺陷兜底：<b>区块里只要有一个「服务端认不出的方块」，整个区块就永远存不上、也发不出去</b>。
 *
 * <h2>症状</h2>
 * <pre>
 * [AsyncChunkThread] WARN  - Can not find legacyId! No runtime2legacy mapping for 1623
 * [AsyncChunkThread] ERROR - Async chunk serialization failed for chunk (-5, 19)
 * java.lang.IllegalArgumentException: Can not find legacyId for default runtimeId: 1623
 *     at BlockStateMapping.getLegacyId(BlockStateMapping.java:264)
 *     at StateBlockStorage.writeSizedNetworkPalette(StateBlockStorage.java:349)
 *     at NetworkChunkSerializer.serialize1_18_30(NetworkChunkSerializer.java:151)
 * </pre>
 * 玩家表现为「走到某片区域一直加载 / 区块是空的」，但服务器不崩、其它逻辑照常。
 *
 * <h2>为什么会这样</h2>
 * <ol>
 *   <li>LevelDB 的 subchunk 调色板存的是方块 state。存档里某个方块的标识符
 *       （旧插件残留的 {@code gomoku:board} / {@code ddz:physical_table_center} / {@code junqi:board} 等）
 *       现在没有任何插件注册 ⇒ 读档时匹配不到 ⇒ 被替换成<b>默认方块</b>
 *       {@code minecraft:info_update}（{@code leveldb_palette.nbt} 第 1623 项，{@code states={}}）。</li>
 *   <li>{@code minecraft:info_update} 在原版调色板里<b>没有 id / network_id / name_hash</b>，
 *       所以 {@code BlockPalette.runtimeIdToLegacy} 里没有它，{@code getLegacyFullId(1623)} 返回 -1。</li>
 *   <li>写盘时 {@code StateBlockStorage.writeSizedNetworkPalette} 必须给调色板每一项算出 legacy id，
 *       算不出就抛 {@code IllegalArgumentException}；异常在 {@code AsyncChunkThread} 里被吞掉，
 *       只打一条 ERROR ⇒ <b>服务器不崩，但这个区块永远序列化不出去</b>。
 *       调色板里的 info_update 条目不会因为方块被拆掉而消失，所以「把旧牌桌拆了」也不会自愈。</li>
 * </ol>
 *
 * <h2>本类做什么</h2>
 * 把 {@code BlockStateMapping} 的 legacy 映射器包一层动态代理：<b>任何查不到 legacy 的 state
 * 一律按空气（legacyId=0 / data=0）写出</b>，让区块能正常落盘与下发。正常的原版/自定义方块
 * 完全不受影响（只在 delegate 返回 -1 时才兜底）。
 * <p>
 * ⚠️ 代价：那些认不出的方块在<b>第一次保存后永久变成空气</b>（数据其实已经丢了，救不回来）。
 * 这是 MOT 核心层面的既有缺陷，插件侧只能在「区块永久卡死」和「未知方块消失」之间二选一。
 * 日志会限流提示，方便服主知道世界里还剩多少历史残留。
 *
 * <h2>为什么全走反射</h2>
 * 用户服的核心版本可能比插件的编译基线旧（本插件跑在 {@code vgit-3d50b20} 上，手上的是更新的
 * {@code e059e74}）。{@code BlockStateMapping} 的方法/字段在版本间会变，所以这里全部走反射，
 * 装不上就安静降级（打一条 WARN），绝不让插件因为核心版本差异在 onEnable 崩掉。
 *
 * <h2>安装时机</h2>
 * {@code onEnable()} 里装一次即可；世界加载、牌桌恢复、异步写盘都在其后。重复安装幂等。
 */
public final class UnknownBlockGuard {

    /** 兜底日志最小间隔（毫秒）—— 写盘是异步线程，一秒可能触发几十次。 */
    private static final long LOG_INTERVAL_MS = 10_000L;

    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicLong LAST_LOG_AT = new AtomicLong();
    private static volatile Logger logger;

    private UnknownBlockGuard() {
    }

    /**
     * 安装兜底（幂等、不抛异常）。
     *
     * @return true 表示本次真的装上了
     */
    public static boolean install(Logger log) {
        logger = log;
        try {
            Class<?> mappingClass = Class.forName("cn.nukkit.level.format.leveldb.BlockStateMapping");
            Object mapping = mappingClass.getMethod("get").invoke(null);
            Field field = mappingClass.getDeclaredField("legacyMapper");
            field.setAccessible(true);
            Object delegate = field.get(mapping);
            if (delegate == null) {
                warn("核心还没有 legacy 映射器，跳过未知方块兜底（不影响正常方块）");
                return false;
            }
            Class<?> mapperInterface = Class.forName("cn.nukkit.level.format.leveldb.LegacyStateMapper");
            if (!mapperInterface.isInstance(delegate)) {
                warn("核心的 legacy 映射器类型不认识（" + delegate.getClass().getName() + "），跳过未知方块兜底");
                return false;
            }
            if (delegate instanceof Proxy proxy && Proxy.getInvocationHandler(proxy) instanceof GuardHandler) {
                return false;
            }
            Object proxy = Proxy.newProxyInstance(
                    mapperInterface.getClassLoader(),
                    new Class<?>[]{mapperInterface},
                    new GuardHandler(delegate));
            field.set(mapping, proxy);
            return true;
        } catch (Throwable t) {
            warn("装未知方块兜底失败（核心版本差异），已跳过：" + t);
            return false;
        }
    }

    /**
     * 核心是否真的缺默认方块（{@code minecraft:info_update}）的 legacy 映射。
     * <p>用于启动日志说清「这次到底是不是踩到了这个坑」。全部反射，绝不抛。</p>
     */
    public static boolean isCoreMissingDefaultLegacy() {
        try {
            Class<?> mappingClass = Class.forName("cn.nukkit.level.format.leveldb.BlockStateMapping");
            Object mapping = mappingClass.getMethod("get").invoke(null);
            int defaultRuntimeId = (Integer) mappingClass.getMethod("getDefaultRuntimeId").invoke(mapping);
            Field field = mappingClass.getDeclaredField("legacyMapper");
            field.setAccessible(true);
            Object delegate = field.get(mapping);
            if (delegate instanceof Proxy proxy && Proxy.getInvocationHandler(proxy) instanceof GuardHandler guard) {
                delegate = guard.delegate;
            }
            if (delegate == null) {
                return false;
            }
            Method method = delegate.getClass().getMethod("runtimeToLegacyId", int.class);
            method.setAccessible(true);
            return (Integer) method.invoke(delegate, defaultRuntimeId) == -1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 已兜底的次数（每次写盘、每个未知 state 各算一次）。 */
    public static long fallbackCount() {
        return FALLBACKS.get();
    }

    private static void warn(String message) {
        Logger log = logger;
        if (log != null) {
            log.warning("[TabletopGames] " + message);
        }
    }

    /** 未知 state 一律当作空气（legacy full id = 0）。 */
    private static int fallback(int runtimeId) {
        long total = FALLBACKS.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = LAST_LOG_AT.get();
        if (now - last >= LOG_INTERVAL_MS && LAST_LOG_AT.compareAndSet(last, now)) {
            warn("区块里有服务端认不出的方块（runtimeId=" + runtimeId
                    + "，多为旧版本客户端留下的原版方块，见 LegacyStateRestorer），"
                    + "已按空气写入以免整个区块存不上；累计 " + total + " 次。");
        }
        return 0;
    }

    private static final class GuardHandler implements InvocationHandler {

        private final Object delegate;

        private GuardHandler(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                result = method.invoke(this.delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            String name = method.getName();
            if ((name.equals("runtimeToLegacyId")
                    || name.equals("runtimeToLegacyData")
                    || name.equals("runtimeToFullId"))
                    && result instanceof Integer value && value == -1) {
                int runtimeId = args != null && args.length > 0 && args[0] instanceof Integer id ? id : -1;
                return fallback(runtimeId);
            }
            return result;
        }
    }
}
