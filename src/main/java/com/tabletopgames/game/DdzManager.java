package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.math.BlockFace;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerInteractEntityEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Sound;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import com.tabletopgames.board.DdzTableBlocks;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.game.ddz.DdzDifficulty;
import com.tabletopgames.entity.DdzTableEntity;
import com.tabletopgames.item.DdzTableItem;
import com.tabletopgames.storage.Database;
import com.tabletopgames.storage.DdzTableDao;
import com.tabletopgames.storage.StartupReport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 斗地主的<b>事件接线层</b>：把 Nukkit 的交互 / 生命周期事件转给 {@link DdzGame}，
 * 另外负责<b>牌桌本体（3x3 方块 + 位置 + 每桌场次）的存档与恢复</b>。
 *
 * <h2>为什么逻辑全在 DdzGame、这里只做转发</h2>
 * 斗地主的牌桌状态是「静态注册表 + 每桌自持」的（{@code DdzGame.TABLES}），
 * 不像 UNO 还要一层 {@code UnoManager} 的座次索引。牌局状态不落盘（重启即重开），
 * 但<b>桌上的 3x3 方块必须活过重启</b>——见下面的存档说明。
 *
 * <h2>存档（2026-10-23 修「重启后方块消失」）</h2>
 * 之前 {@code shutdown()} 直接 {@code DdzGame.destroy()}，而 destroy 的语义是「拆桌」，
 * 会把 3x3 方块写成空气；启动时又没有任何恢复逻辑 ⇒ 每次重启牌桌凭空消失。
 * 现在与 UNO 同款：
 * <ol>
 *   <li>放置 / 改场次时把「坐标 + 朝向 + 场次 + 难度」写进 SQLite 的 {@code ddz_table_config}；</li>
 *   <li>启动后等关卡就绪，按坐标<b>重放一遍方块</b>再重建牌桌
 *       （自定义方块运行期 id 重启后可能重新分配，光留方块不可靠）；</li>
 *   <li>关服只收实体、<b>不清方块</b>——万一存档/数据库出问题，世界里的桌子也还在。</li>
 * </ol>
 *
 * <h2>与 UNO 侧刻意保持一致的四处</h2>
 * <ol>
 *   <li>{@code ignoreCancelled = true} <b>不</b>给 {@link #onInteract}（出生点保护会让
 *       非 OP 玩家在出生点附近收不到回调 ⇒ 表现成「只有 OP 能点牌桌」），但给
 *       {@link #onBreak}（被取消时不该介入，否则整桌会被误收）。</li>
 *   <li>{@link #claimClick()} 同 tick 去重：一次右键会派发「方块 + 空中 + 命中实体」三条事件，
 *       全放行会把一次点击当成两次（选中 → 立刻取消 = 看起来没反应）。</li>
 *   <li>空中右键只处理悬空的手牌 / 按钮，<b>不</b>开菜单。</li>
 *   <li>放置区域 = 点击方块的<b>对侧邻格</b>回退 1 格（与 UNO 同），不是「以点击方块为中心」。</li>
 * </ol>
 */
public final class DdzManager implements Listener {

    /** 放置冷却（毫秒）：一次点击可能派发多个交互事件。 */
    private static final long PLACE_COOLDOWN_MS = 250L;

    /** 每桌场次/难度变化的巡检间隔（tick 次数，5 tick 一次 ⇒ 20 次 = 5 秒）。 */
    private static final int CONFIG_SYNC_CALLS = 20;

    private final PluginBase plugin;
    private final TabletopGamesManager gameManager;

    /** 牌桌位置与每桌配置的持久化（SQLite）。 */
    private final DdzTableDao dao;

    /** 玩家 → 本 tick 已处理过点击（{@link #claimClick} 用）。 */
    private final Map<UUID, Integer> clickTicks = new HashMap<>();

    /** 玩家 → 上次放置时刻（{@link #canPlace} 用）。 */
    private final Map<UUID, Long> placeCooldown = new HashMap<>();

    /** 上次落库时每桌的「场次|难度」，用于识别变化后再写（避免每 tick 都写库）。 */
    private final Map<String, String> savedSig = new HashMap<>();

    /** 等关卡加载完再恢复（插件是 STARTUP 阶段启用，此时世界还没就绪）。 */
    private boolean loadPending;
    /** 首次恢复是否完成。未完成前的写库一律跳过，避免用空数据盖掉存档。 */
    private boolean restored;

    /** 巡检计数（{@link #CONFIG_SYNC_CALLS}）。 */
    private int syncCalls;

    public DdzManager(PluginBase plugin, TabletopGamesManager gameManager, Database database) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.dao = new DdzTableDao(database);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    /** 请求在关卡加载完成后恢复牌桌（由插件 onEnable 调用）。 */
    public void requestLoad() {
        this.loadPending = true;
    }

    /**
     * 从数据库恢复牌桌：按坐标重放 3x3 方块，再用保存的场次/难度重建牌桌对象。
     *
     * <p>世界没加载的跳过并记日志——不删行，下次启动还有机会恢复。</p>
     */
    private void load() {
        List<DdzTableDao.Site> sites = dao.loadAll();
        int n = 0;
        for (DdzTableDao.Site s : sites) {
            Level level = Server.getInstance().getLevelByName(s.level());
            if (level == null) {
                plugin.getLogger().warning("斗地主牌桌所在世界未加载，跳过：" + s.level() + " " + s.key());
                continue;
            }
            // 先重放方块：自定义方块 id 重启后可能重新分配，光靠世界里的旧方块不可靠
            placeBlocks(level, s.x(), s.y(), s.z());
            DdzGame table = DdzGame.create(gameManager, level, s.x(), s.y(), s.z(), s.rotation());
            table.restoreConfig(TabletopGamesConfig.DdzTier.fromConfig(s.tier()),
                    DdzDifficulty.fromConfig(s.difficulty()),
                    TabletopGamesConfig.DdzBidMode.fromConfig(s.bidMode()),
                    TabletopGamesConfig.DdzWildMode.fromConfig(s.wildMode()),
                    TabletopGamesConfig.DdzHandSort.fromConfig(s.handSort()));
            savedSig.put(table.getKey(), signature(table));
            n++;
        }
        restored = true;
        if (n == 0 && !sites.isEmpty()) {
            plugin.getLogger().warning("读到 " + sites.size() + " 条斗地主牌桌存档，但一条都没恢复（世界未加载？）");
        }
        // 2026-10-27：恢复日志统一由 StartupReport 汇总成一行（用户要求控制台别刷屏）
        StartupReport.report(plugin, n, 0);
    }

    private static String signature(DdzGame table) {
        return table.getTier().name() + "|" + table.getDifficulty().name()
                + "|" + table.getBidMode().name() + "|" + table.getWildMode().name()
                + "|" + table.getHandSort().name();
    }

    /** 把一张牌桌的坐标与配置写库（放置 / 改场次 / 关服兜底都走这里）。 */
    private void persist(DdzGame table) {
        if (!restored || table == null) {
            return;
        }
        dao.save(new DdzTableDao.Site(
                table.getKey(), table.getLevel().getName(),
                table.getBaseX(), table.getBaseY(), table.getBaseZ(), table.getRotation(),
                table.getTier().name(), table.getDifficulty().name(),
                table.getBidMode().name(),
                table.getWildMode().name(),
                table.getHandSort().name()));
        savedSig.put(table.getKey(), signature(table));
    }

    /** 场次 / 难度变了就补写一次（最多滞后 {@link #CONFIG_SYNC_CALLS} × 5 tick）。 */
    private void syncConfigs() {
        if (!restored) {
            return;
        }
        for (DdzGame table : DdzGame.all()) {
            if (!signature(table).equals(savedSig.get(table.getKey()))) {
                persist(table);
            }
        }
    }

    /**
     * 关服兜底：把所有牌桌同步写库。
     *
     * <p>不清理方块——方块由下次启动的 {@link #load()} 按坐标重放；万一库写失败，
     * 世界里的桌子也还在，不至于又变成「重启就消失」。</p>
     */
    public void saveNow() {
        for (DdzGame table : DdzGame.all()) {
            DdzTableDao.Site site = new DdzTableDao.Site(
                    table.getKey(), table.getLevel().getName(),
                    table.getBaseX(), table.getBaseY(), table.getBaseZ(), table.getRotation(),
                    table.getTier().name(), table.getDifficulty().name(),
                    table.getBidMode().name(),
                    table.getWildMode().name(),
                    table.getHandSort().name());
            dao.save(site);
            savedSig.put(table.getKey(), signature(table));
        }
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    /** 每 5 tick 驱动所有斗地主牌桌（发牌 / 机器人 / 时限 / 飞牌回收）。 */
    public void tick() {
        if (loadPending && !Server.getInstance().getLevels().isEmpty()) {
            loadPending = false;
            load();
        }
        if (++syncCalls >= CONFIG_SYNC_CALLS) {
            syncCalls = 0;
            syncConfigs();
        }
        for (DdzGame t : DdzGame.all()) {
            try {
                t.tick();
            } catch (RuntimeException e) {
                // 单桌异常不能带塌整轮 tick（否则所有牌桌一起卡住）
                plugin.getLogger().error("斗地主牌桌 " + t.getKey() + " tick 异常", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 右键
    // ------------------------------------------------------------------

    /**
     * 右键：点牌桌方块 / 放置斗地主牌桌物品 / 空中点悬空手牌。
     *
     * <p>⚠️ <b>不能</b>加 {@code ignoreCancelled = true}：基岩触屏（默认「点击即交互」）
     * 点牌桌走 {@code Level#useItemOn}，该路径会在派发事件<b>之前</b>因出生点保护对非 OP
     * 玩家 {@code setCancelled(true)}。若忽略已取消事件，非 OP 玩家在出生点附近就完全收不到
     * 回调，表现成「只有 OP 能打开表单」。</p>
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        // 定位器「蹲下+使用」的准星开关：这次右键已被 LocatorTitleService 吞掉，
        // 不能再当作出牌 / 叫牌 / 放置牌桌（判据见 isSwallowed 的注释）。
        if (LocatorTitleService.isSwallowed(event.getPlayer())) {
            return;
        }
        if (event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_AIR) {
            onInteractAir(event);
            return;
        }
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        Block clicked = event.getBlock();
        boolean isTableBlock = clicked instanceof DdzTableBlocks.DdzTableBlock;
        DdzGame table = isTableBlock
                ? findTable(player.getLevel(),
                        clicked.getFloorX(), clicked.getFloorY(), clicked.getFloorZ())
                : null;

        if (isTableBlock) {
            event.setCancelled(true);
            if (table != null && claimClick(player)) {
                table.useBoard(player, true);
            }
            return;
        }
        if (event.getItem() instanceof DdzTableItem) {
            if (event.isCancelled() || !canPlace(player)) {
                event.setCancelled(true);
                return;
            }
            tryPlace(player, event);
        }
    }

    /**
     * 空中右键：准星落在方块之外，但玩家可能正瞄着悬空的手牌 / 桌面按钮。
     *
     * <p>只在「已入座 + 就在自己那张桌旁边 + 射线真的命中了」时才处理并吞掉事件。
     * 三个条件缺一不可：未命中则什么都不做 —— 空中右键平时（吃东西、用道具）有自己的语义。</p>
     */
    private void onInteractAir(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        DdzGame table = DdzGame.of(player);
        if (table == null || !table.inUseRange(player)) {
            return;
        }
        if (!claimClick(player)) {
            return;
        }
        if (table.useBoardAir(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * 同一次右键只处理一遍。
     *
     * <p>一次右键可能同时派发好几条事件 ——「右键方块」+「右键空中」+「命中实体」，
     * 而<b>手持定位器</b>时客户端还会额外发一条「使用物品」事务（收到后再派发一条
     * {@code RIGHT_CLICK_AIR}）。若都放行，同一次点击会被当成两次：先选中某张牌、
     * 紧接着又被「再点一次」取消，表现就是「点了没反应」。</p>
     *
     * @return true = 这次右键还没被处理过（调用方可以继续处理）
     */
    private boolean claimClick(Player player) {
        int now = Server.getInstance().getTick();
        Integer last = clickTicks.get(player.getUniqueId());
        if (last != null && last == now) {
            return false;
        }
        clickTicks.put(player.getUniqueId(), now);
        return true;
    }

    private boolean canPlace(Player player) {
        long now = System.currentTimeMillis();
        Long last = placeCooldown.get(player.getUniqueId());
        if (last != null && now - last < PLACE_COOLDOWN_MS) {
            return false;
        }
        placeCooldown.put(player.getUniqueId(), now);
        return true;
    }

    // ------------------------------------------------------------------
    // 放置 / 回收
    // ------------------------------------------------------------------

    /** 铺设 3x3 牌桌（区域以点击方块的<b>对侧邻格</b>为中心回退 1 格，与 UNO 同规则）。 */
    private void tryPlace(Player player, PlayerInteractEvent event) {
        BlockFace face = event.getFace();
        if (face == null) {
            return;
        }
        Vector3 target = event.getBlock().getSide(face);
        int bx = target.getFloorX() - 1;
        int by = target.getFloorY();
        int bz = target.getFloorZ() - 1;
        Level level = player.getLevel();

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Block b = level.getBlock(bx + i, by, bz + j);
                if (!b.isAir() && !b.canBeReplaced()) {
                    player.sendTip("§c空间不足，铺设牌桌需要 3x3 空地。");
                    return;
                }
            }
        }
        for (DdzGame t : DdzGame.all()) {
            if (t.getLevel() != level || t.getBaseY() != by) {
                continue;
            }
            if (bx < t.getBaseX() + 3 && t.getBaseX() < bx + 3
                    && bz < t.getBaseZ() + 3 && t.getBaseZ() < bz + 3) {
                player.sendTip("§c这里已经有牌桌了。");
                return;
            }
        }

        event.setCancelled(true);
        int rotation = (int) Math.round(player.getYaw() / 90.0) & 3;
        placeBlocks(level, bx, by, bz);
        level.addSound(new Vector3(bx + 1.5, by + 0.5, bz + 1.5), Sound.DIG_WOOD, 1.0f, 0.8f);

        DdzGame table = DdzGame.create(this.gameManager, level, bx, by, bz, rotation);
        persist(table);          // 立刻落库：放完就重启也不会丢

        if (!player.isCreative()) {
            Item hand = player.getInventory().getItemInHand();
            if (hand.getCount() <= 1) {
                player.getInventory().setItemInHand(Item.get(Item.AIR));
            } else {
                hand.setCount(hand.getCount() - 1);
                player.getInventory().setItemInHand(hand);
            }
        }
        player.sendTip("§a已铺设斗地主牌桌，点击牌桌即可打开菜单入座。");
    }

    /**
     * 铺 3x3：中心 1 块 + 边 4 块 + 角 4 块。
     *
     * <p>facing 规则与资源包 {@code models/blocks/ddz/*.geo.json} 配套：模型零度棱边朝北
     * ⇒ 北边=0 西边=1 南边=2 东边=3；角模型零度棱边为 NE ⇒ f 旋转后 NE=0 NW=1 SW=2 SE=3。
     * 改动必须同步资源包里的 X 预镜像约定。</p>
     */
    private void placeBlocks(Level level, int bx, int by, int bz) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3 pos = new Vector3(bx + i, by, bz + j);
                if (i == 1 && j == 1) {
                    level.setBlock(pos, new DdzTableBlocks.CenterBlock(), true);
                } else if (i == 1) {
                    DdzTableBlocks.EdgeBlock edge = new DdzTableBlocks.EdgeBlock();
                    edge.setFacing(j == 0 ? 0 : 2);
                    level.setBlock(pos, edge, true);
                } else if (j == 1) {
                    DdzTableBlocks.EdgeBlock edge = new DdzTableBlocks.EdgeBlock();
                    edge.setFacing(i == 0 ? 1 : 3);
                    level.setBlock(pos, edge, true);
                } else {
                    DdzTableBlocks.CornerBlock corner = new DdzTableBlocks.CornerBlock();
                    corner.setFacing(i == 2 ? (j == 0 ? 0 : 3) : (j == 0 ? 1 : 2));
                    level.setBlock(pos, corner, true);
                }
            }
        }
    }

    /**
     * 牌桌方块可像木板一样被挖掘，任意一处破坏成功即视作整体收回。
     *
     * <p>{@code ignoreCancelled = true}：出生点保护下事件已取消时不介入，避免整桌被误收。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block instanceof DdzTableBlocks.DdzTableBlock)) {
            return;
        }
        // 先确认方块确实登记在某张桌上：登记丢失（已被收回 / 存档被删）时它是「孤儿方块」，
        // 此时放行、按普通方块破坏；若先取消再 return，方块会永久残留且玩家无法清理。
        DdzGame table = findTable(block.getLevel(),
                block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Level level = block.getLevel();
        level.addSound(
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.5, block.getFloorZ() + 0.5),
                Sound.DIG_WOOD, 1.0f, 1.0f);
        Vector3 dropAt = new Vector3(block.getFloorX() + 0.5,
                block.getFloorY() + 0.25, block.getFloorZ() + 0.5);
        boolean drop = !player.isCreative();
        // 延迟一 tick 回收：破坏事件此刻还在处理中，立刻改方块会与破坏流程打架。
        // ⚠️ 方块清理由 {@link DdzGame#destroy()} 统一做（与 UNO 同款），这里不重复清 ——
        //    两处都清会在 destroy 之后又写一次空气，白做功还可能与掉落时序打架。
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            // 去重：同一 tick 两名玩家各挖一块时，会排进两个延迟任务，
            // 第二个必须在这里退出 —— 否则 destroy() 跑两次、掉落两个牌桌物品。
            // （其余六种都用「boards.remove(...) == null」/「tables.containsKey」做同一件事）
            if (!DdzGame.all().contains(table)) {
                return;
            }
            table.destroy();
            dao.delete(table.getKey());
            savedSig.remove(table.getKey());
            if (drop) {
                level.dropItem(dropAt, new DdzTableItem());
            }
        }, 1);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 右键命中牌桌实体：吞掉事件并把准星交给牌桌做射线判定。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (LocatorTitleService.isSwallowed(player)) {
            return;
        }
        DdzGame table = null;
        if (event.getEntity() instanceof DdzTableEntity entity) {
            table = entity.getGame();
        }
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        if (!claimClick(player)) {
            return;
        }
        table.useBoard(player, true);
    }

    /**
     * 复活后让所有牌桌「忘掉」这个玩家，下一轮重新发一遍视角动画。
     *
     * <p>客户端复活会重建附近的实体，之前收到的 {@code AnimateEntityPacket} 状态随之丢失；
     * 而旁观者动画现在是<b>差量</b>发送（只发给新进入半径的人），不主动作废的话，
     * 复活后的旁观者会一直停留在默认姿态 —— 也就是<b>直接看见别人的手牌</b>。
     * 坐着的玩家不受影响（他们每一轮都重发）。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        for (DdzGame t : DdzGame.all()) {
            t.forgetViewer(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        DdzGame table = DdzGame.of(event.getPlayer());
        if (table != null) {
            table.onQuit(event.getPlayer());
        }
        UUID id = event.getPlayer().getUniqueId();
        clickTicks.remove(id);
        placeCooldown.remove(id);
    }

    /**
     * 重连回座：座位保留、断线托管，玩家回来自动收回操作权。
     *
     * <p>与 UNO 侧同款，但<b>不发消息</b>：斗地主一局可能已经打到中局，
     * 弹「你已回到牌桌」意义不大，牌桌状态栏本身就是唯一事实来源。</p>
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        DdzGame table = DdzGame.of(event.getPlayer());
        if (table != null) {
            table.reattach(event.getPlayer());
        }
    }

    /** 按坐标命中牌桌（3x3 区域 + 同关卡）。 */
    private DdzGame findTable(Level level, int x, int y, int z) {
        for (DdzGame t : DdzGame.all()) {
            if (t.getLevel() == level && t.contains(x, y, z)) {
                return t;
            }
        }
        return null;
    }

    /** 供命令层查询玩家所在牌桌。 */
    public DdzGame tableOf(Player player) {
        return DdzGame.of(player);
    }

    /** 关服清理（无存档要写）。 */
    public void shutdown() {
        // 先同步写库，再收实体；**不清方块**（见类注释「存档」一节）
        saveNow();
        DdzGame.shutdownAll();
        clickTicks.clear();
        placeCooldown.clear();
        savedSig.clear();
    }

    /** 供命令层在指定位置发一张牌桌。 */
    public DdzGame giveTable(Player player, Position pos, int rotation) {
        return DdzGame.create(this.gameManager, player.getLevel(),
                pos.getFloorX(), pos.getFloorY(), pos.getFloorZ(), rotation);
    }
}