package com.tabletopgames.guard;

import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.level.ChunkLoadEvent;
import cn.nukkit.event.level.LevelLoadEvent;
import cn.nukkit.utils.Logger;
import org.cloudburstmc.nbt.NbtMap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「旧版本客户端留下的原版方块」按同名映射回当前调色板，避免它们被当成 {@code minecraft:info_update}。
 *
 * <h2>问题复现链（已逐字节码确认）</h2>
 * <ol>
 *   <li>无插件时：{@code BlockStateMapping} 在静态初始化里调
 *       {@code NukkitLegacyMapper.registerStates}，把 jar 内 {@code leveldb_palette.nbt} 的
 *       <b>22091 个 state（所有历史版本并集）</b>全部注册。旧区块里的方块都能查到。</li>
 *   <li>插件注册了自定义方块 ⇒ 核心 {@code CustomBlockManager.closeRegistry()} 会
 *       {@code BlockStateMapping.clearMapping()}，然后<b>只按当前特性版本（1.26）的
 *       {@code vanilla_palette_*.nbt}（15846 项）重新注册</b>，再追加自定义方块。</li>
 *   <li>差集 ≈ 6245 项 = 1.21.30 / 1.21.120 / 网易版等<b>旧协议独有的 state</b>。
 *       旧区块读到它们 → {@code getStateUnsafe(nbt)} 返回 null →
 *       {@code getUpdatedOrCustom} 现场造一个 {@code custom=true, runtimeId=默认方块(info_update,1623)}
 *       的临时快照（原始 NBT 还留着，但 runtimeId 是 default 的）。</li>
 *   <li>写盘时 {@code getLegacyId(1623)} → {@code runtimeIdToLegacy} 没有 info_update → -1 →
 *       抛异常 → 整个区块序列化失败。</li>
 * </ol>
 * ⇒ 这就解释了「<b>卸载插件就正常</b>」：不重建 palette，22091 项还在，旧方块查得到。
 * 也解释了「<b>那些区块根本没放过自定义方块</b>」：它们本来就是旧版本的原版方块。
 *
 * <h2>本类做什么</h2>
 * 在 {@code closeRegistry} 之后（世界加载事件）把缺的历史 state 补回 {@code paletteMap}：
 * 用<b>同名方块在当前调色板里的默认状态</b>作为映射目标。这样：
 * <ul>
 *   <li>读档走正常路径（不再是 custom/1623 快照）；</li>
 *   <li>写盘用它自己的 runtimeId，legacy 算得出来，区块能存；</li>
 *   <li>方块显示成「同名的当前方块」（旧版本独有的属性会丢，但方块本体还在，不是空气）。</li>
 * </ul>
 * 名字已被游戏彻底移除的方块（如某些节日限定）无处可映射，会落到 {@link UnknownBlockGuard} 兜底。
 *
 * <h2>为什么用反射</h2>
 * 用户服核心版本（{@code vgit-3d50b20}）比插件编译基线旧，{@code BlockStateMapping} 的字段/方法
 * 在版本之间会变；全部反射 + 失败降级，绝不让插件在 onEnable/事件里崩掉。
 */
public final class LegacyStateRestorer implements Listener {

    private final Logger logger;
    private volatile boolean done;

    /** 排查用：-Dtabletopgames.restoreLegacy=true 时强制补（默认只在核心确实缺 info_update legacy 时才补）。 */
    private static final boolean FORCE = Boolean.getBoolean("tabletopgames.restoreLegacy");

    public LegacyStateRestorer(Logger logger) {
        this.logger = logger;
    }

    @EventHandler
    public void onLevelLoad(LevelLoadEvent event) {
        this.restore();
    }

    /** 保险：万一 LevelLoadEvent 早于调色板重建，第一次区块加载时再补一次。 */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!this.done) {
            this.restore();
        }
    }

    public void restore() {
        if (this.done) {
            return;
        }
        // 只有「默认方块都算不出 legacy」的核心才需要补（新版核心自己已把 info_update 兜成 0）
        if (!FORCE && !UnknownBlockGuard.isCoreMissingDefaultLegacy()) {
            this.done = true;
            return;
        }
        long started = System.currentTimeMillis();
        try {
            Class<?> mappingClass = Class.forName("cn.nukkit.level.format.leveldb.BlockStateMapping");
            Object mapping = mappingClass.getMethod("get").invoke(null);

            Field field = mappingClass.getDeclaredField("paletteMap");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Object, Object> paletteMap = (Map<Object, Object>) field.get(mapping);
            if (paletteMap.isEmpty()) {
                this.done = true;
                return;
            }

            // name -> 当前调色板里该方块「states 最少」的那个状态（即默认状态）
            Map<String, Object> byName = new HashMap<>(4096);
            for (Object snapshot : paletteMap.values()) {
                String name = nameOf(snapshot);
                if (name == null) {
                    continue;
                }
                byName.merge(name, snapshot, (a, b) -> stateCount(a) <= stateCount(b) ? a : b);
            }

            @SuppressWarnings("unchecked")
            List<NbtMap> history = (List<NbtMap>) Class
                    .forName("cn.nukkit.level.format.leveldb.NukkitLegacyMapper")
                    .getMethod("loadBlockPalette")
                    .invoke(null);
            Method update = mappingClass.getMethod("updateVanillaState", NbtMap.class);

            int added = 0;
            int removed = 0;
            for (NbtMap old : history) {
                NbtMap updated;
                try {
                    updated = (NbtMap) update.invoke(mapping, old);
                } catch (Throwable ignored) {
                    continue;
                }
                if (updated == null || paletteMap.containsKey(updated)) {
                    continue;
                }
                Object same = byName.get(updated.getString("name"));
                if (same == null) {
                    removed++;
                    continue;
                }
                paletteMap.put(updated, same);
                added++;
            }

            // 补成功（或本来就不缺）才收工；否则留给下一次 ChunkLoad 重试
            this.done = added > 0 || paletteMap.size() >= 20000;
            this.logger.info("旧版本方块兼容：补回 " + added
                    + " 个历史 state（按同名映射到当前方块），" + removed
                    + " 个方块名已从游戏移除；palette 现有 " + paletteMap.size()
                    + " 项，耗时 " + (System.currentTimeMillis() - started) + "ms。");
        } catch (Throwable t) {
            this.logger.warning("旧版本方块兼容补丁执行失败（下次区块加载会重试）：" + t);
        }
    }

    private static String nameOf(Object snapshot) {
        try {
            Object state = snapshot.getClass().getMethod("getVanillaState").invoke(snapshot);
            return (String) state.getClass().getMethod("getString", String.class).invoke(state, "name");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int stateCount(Object snapshot) {
        try {
            Object state = snapshot.getClass().getMethod("getVanillaState").invoke(snapshot);
            Object states = state.getClass().getMethod("getCompound", String.class).invoke(state, "states");
            return (Integer) states.getClass().getMethod("size").invoke(states);
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }
}
