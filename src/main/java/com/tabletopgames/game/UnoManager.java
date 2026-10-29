package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
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
import cn.nukkit.math.BlockFace;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.game.TabletopGamesManager;
import com.tabletopgames.storage.BoardStorage;
import com.tabletopgames.storage.StartupReport;
import com.tabletopgames.board.UnoTableBlocks;
import com.tabletopgames.entity.UnoTableEntity;
import com.tabletopgames.item.LocatorItem;
import com.tabletopgames.item.UnoTableItem;
import com.tabletopgames.ui.UnoMenu;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理所有已放置的 UNO 牌桌：放置、破坏收回、菜单、交互、机器人派发与存档恢复。
 * <p>
 * 骨架对齐 {@link com.tabletopgames.game.XiangqiManager}：复用五子棋的全局配置
 * （玩家识别半径 / 菜单独占超时 / 赌博抽水比例）；存档按棋类分目录，一张牌桌一个文件
 * （{@code data/uno/<编号>.yml}）。
 * <p>
 * 与四棋的差别：UNO 牌桌是 <b>N 人（2..4）自由入座</b>的实时交互桌，没有轮次落子，
 * 因此不需要 {@code onMove} 落点高亮（准星 hover 逐表 tick 刷新即可），
 * 但需要把「破坏任意一格即整桌收回」与「右键牌桌 = 准星直接交互」都接过来。
 */
public class UnoManager implements Listener {

    /** 放置牌桌的冷却时间（毫秒）：一次点击可能触发多个交互事件，需去重。 */
    private static final long PLACE_COOLDOWN_MS = 500L;

    /** 存档目录名（{@code data/<本名>/}），一张牌桌一个 {@code 0001.yml}。 */
    private static final String STORAGE_KIND = "uno";

    private final PluginBase plugin;

    /** 插件实例（供牌桌在异步调度里取 scheduler 用）。 */
    public PluginBase getPlugin() {
        return plugin;
    }

    /** 复用五子棋的全局配置（同一个 config.yml，不重复加载配置）。 */
    private final TabletopGamesManager gameManager;

    private final Map<String, UnoTable> tables = new ConcurrentHashMap<>();

    /** 每张牌桌对应的存档编号（文件名 {@code 0001.yml}），键为牌桌坐标 key。 */
    private final Map<String, Integer> tableIds = new ConcurrentHashMap<>();

    /** 下一个可用的存档编号。 */
    private int nextTableId = 1;

    private final Map<UUID, Long> placeCooldown = new HashMap<>();

    /** 每个玩家最近一次「已处理」右键的服务器 tick（见 claimClick：同 tick 去重）。 */
    private final Map<UUID, Integer> clickTicks = new ConcurrentHashMap<>();

    /** 按关卡索引牌桌，右键命中与破坏时只需看玩家所在关卡。 */
    private final Map<Level, List<UnoTable>> tablesByLevel = new ConcurrentHashMap<>();

    /** 玩家 → 所在牌桌。 */
    private final Map<UUID, UnoTable> seatings = new ConcurrentHashMap<>();

    /** 牌桌增删后置位，下一次 tick 落盘，避免频繁写文件。 */
    private boolean dirty;

    /** 插件以 STARTUP 阶段启用，此时关卡尚未加载，需等关卡就绪后再恢复牌桌。 */
    private boolean loadPending;

    /** 首次恢复是否已完成。未完成前禁止写盘，避免用空数据覆盖存档。 */
    private boolean restored;


    public UnoManager(PluginBase plugin, TabletopGamesManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    // ------------------------------------------------------------------
    // 配置 / 工具
    // ------------------------------------------------------------------

    /** 玩家识别半径。 */
    public double playerRange() {
        return gameManager.playerRangeOf(BoardKind.UNO);
    }

    /** UNO 配置节（config.yml 内的 {@code uno} 小节），供牌桌读取抽水比例 / 倍数额度 / 回合时间。 */
    public TabletopGamesConfig.UnoSettings config() {
        return gameManager.unoConfig();
    }

    /** 「0 人入座」菜单占用超时（毫秒），读 UNO 自己的 {@code menuLockSeconds}。 */
    long menuLockMs() {
        return gameManager.unoConfig().getMenuLockSeconds() * 1000L;
    }

    /** 玩家显示名（离线后从牌桌记录里取，最后才回退到 UUID 前缀）。 */
    public String nameOf(UUID id) {
        if (id == null) {
            return "—";
        }
        Player p = Server.getInstance().getPlayer(id).orElse(null);
        if (p != null) {
            return p.getName();
        }
        UnoTable table = seatings.get(id);
        if (table != null) {
            int seat = table.seatOf(id);
            if (seat >= 0) {
                return table.seatName(seat);
            }
        }
        return id.toString().substring(0, 8);
    }

    /** 延后一 tick 在主线程执行，避免在表单回调中直接弹窗造成客户端窗口冲突。 */
    public void runLater(Runnable action) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, action, 1);
    }

    /** 延迟一 tick 重新弹出指定玩家的牌桌菜单。 */
    public void reopenMenu(Player player, UnoTable table) {
        runLater(() -> UnoMenu.reopen(player, table));
    }

    /** 打开牌桌菜单。 */
    public void openMenu(Player player, UnoTable table) {
        UnoMenu.open(player, table);
    }

    /**
     * 刷新在座玩家的牌桌菜单：先关掉客户端上的旧表单，再延后一 tick 重弹，
     * 借客户端「新表单顶掉旧表单」完成自动切换。
     *
     * <p>⚠️ 2026-10-18 需求变更：<b>只刷新已经开着本桌菜单的人</b>，不再主动弹窗。
     * 结算（{@code SETTLED}）后「下一局」改由玩家自己点<b>桌面方块 / 桌边按钮</b>打开
     * （见 {@code UnoTable#doNext}），所以这里必须先问 {@link UnoMenu#isShowing}；
     * 否则 {@code doFinish()} 一调用就会给所有人强行弹一张表单。</p>
     */
    public void refreshSeatMenus(UnoTable table) {
        if (table.getPhase() == UnoTable.TablePhase.PLAYING
                || table.getPhase() == UnoTable.TablePhase.DEALER_DRAW) {
            return;
        }
        for (UUID id : seatedIds(table)) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p == null) {
                continue;
            }
            if (!UnoMenu.isShowing(p, table)) {
                continue;                       // 没开着菜单 → 不弹（需求 2026-10-18）
            }
            UnoMenu.closeCurrentForm(p);
            reopenMenu(p, table);
        }
    }

    /** 开局时关闭所有在座玩家的牌桌表单。 */
    public void closeSeatMenus(UnoTable table) {
        for (UUID id : seatedIds(table)) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null) {
                UnoMenu.closeCurrentForm(p);
            }
        }
    }

    private List<UUID> seatedIds(UnoTable table) {
        List<UUID> out = new ArrayList<>(4);
        for (int s = 0; s < 4; s++) {
            Player p = table.onlineAt(s);
            if (p != null) {
                out.add(p.getUniqueId());
            }
        }
        if (out.isEmpty()) {
            UUID owner = table.menuHolderId();
            if (owner != null) {
                out.add(owner);
            }
        }
        return out;
    }

    /**
     * 确保背包里有一把「定位器」（没有<b>带标签的</b>才发）。
     * <p>
     * 2026-10-15 需求：「开局，如果玩家背包没有这个东西自动发放，结束对局的话，自动回收」；
     * 2026-10-27 用户改成<b>入座就发</b>（与棋类 / 斗地主 / 德州扑克统一口径）——
     * 发放入口是 {@code UnoTable.join()} / {@code reattach()}，回收见 {@link #takeLocator(Player)}。
     * 真实现全在 {@code LocatorService}。
     */
    public void ensureLocator(Player player) {
        com.tabletopgames.item.LocatorService.give(player);
    }

    /**
     * 回收「定位器」：清掉背包里所有<b>带标签</b>的定位器（离桌 / 牌桌被拆除 / 老玩家残留 时调用）。
     * <p>只动 {@link LocatorItem#isIssued} 认得的那些，创造栏拿出来那份和玩家的其他东西一律不碰。
     */
    public void takeLocator(Player player) {
        com.tabletopgames.item.LocatorService.take(player);
    }

    /** 玩家入座登记（由牌桌调用）。 */
    public void seat(UUID playerId, UnoTable table) {
        seatings.put(playerId, table);
    }

    /** 玩家离座登记（由牌桌调用）。 */
    public void unseat(UUID playerId) {
        seatings.remove(playerId);
    }

    public UnoTable getTableOf(UUID playerId) {
        return seatings.get(playerId);
    }

    /** 注册牌桌。 */
    public void registerTable(String key, UnoTable table) {
        tables.put(key, table);
        tablesByLevel.computeIfAbsent(table.getLevel(), k -> new ArrayList<>(4)).add(table);
    }

    /** 注销牌桌。 */
    public void unregisterTable(String key) {
        UnoTable table = tables.remove(key);
        if (table == null) {
            return;
        }
        List<UnoTable> list = tablesByLevel.get(table.getLevel());
        if (list != null) {
            list.remove(table);
            if (list.isEmpty()) {
                tablesByLevel.remove(table.getLevel());
            }
        }
    }

    public Map<String, UnoTable> getTables() {
        return tables;
    }

    /** 标记存档已变更，等待下一次 tick 统一保存。包级可见，供同包的 {@link UnoTable} 调用。 */
    void markDirty() {
        this.dirty = true;
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    public void tick() {
        if (loadPending && !Server.getInstance().getLevels().isEmpty()) {
            loadPending = false;
            load();
        }
        if (dirty) {
            dirty = false;
            save();
        }
        if (tables.isEmpty()) {
            return;
        }
        for (UnoTable table : tables.values()) {
            table.tick();
        }
    }

    /**
     * UNO 配置（回合时间 / 倍数额度…）重载后由 {@link TabletopGamesManager} 的配置变更回调触发：
     * 让每张在座牌桌立即用新的回合时长重算行动的计时上限（不必等下一回合）。
     */
    public void onConfigReloaded() {
        for (UnoTable table : tables.values()) {
            table.reevaluateActionLimit();
        }
    }

    /** 关服：销毁所有牌桌（实体 close），不结算。 */
    public void shutdown() {
        for (UnoTable table : tables.values()) {
            table.destroy();
        }
        tables.clear();
        tablesByLevel.clear();
        seatings.clear();
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /**
     * 复活后让所有牌桌「忘掉」这个玩家，下一轮重新发一遍视角动画。
     *
     * <p>客户端复活会重建附近实体，之前收到的 {@code AnimateEntityPacket} 状态随之丢失；
     * 旁观者动画现在是<b>差量</b>发送（只发给新进入半径的人），不主动作废的话，
     * 复活后的旁观者会一直停在默认姿态 —— 也就是<b>直接看见别人的手牌</b>。
     * 与 {@code DdzManager.onRespawn} 同款。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        for (UnoTable table : tables.values()) {
            table.forgetViewer(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        placeCooldown.remove(player.getUniqueId());
        clickTicks.remove(player.getUniqueId());
        UnoTable table = seatings.get(player.getUniqueId());
        if (table != null) {
            table.onDisconnect(player);
        }
        for (UnoTable other : tables.values()) {
            // 未入座但正占用「0 人入座」菜单的玩家离线：立即释放，避免他人被锁到超时
            other.releaseMenu(player.getUniqueId());
        }
    }

    /**
     * 上线兜底：没坐在任何牌桌上却带着「定位器」（上次被强踢 / 服务器重启前留下的）→ 收回。
     * <p>入座者不动：他们的定位器由牌桌在开局时补发、离桌时回收。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UnoTable table = seatings.get(player.getUniqueId());
        if (table == null) {
            takeLocator(player);
            return;
        }
        // 牌桌还替本人留着座位（托管 / 中途离席保留）→ 回来即接管本局（需求 2026-10-17）
        table.reattach(player);
    }

    /**
     * 右键：命中牌桌方块 → 入座则准星直接交互（未入座弹菜单）；
     * 手持牌桌物品点非牌桌方块 → 铺设 3x3 建桌。
     * <p>
     * 刻意不设置 {@code ignoreCancelled = true}：基岩触屏点击会走
     * {@code Level#useItemOn}，该路径可能因出生点保护在派发事件前取消事件，
     * 忽略已取消事件会导致出生点附近完全收不到回调。
     * <p>
     * <b>为什么还要收「右键空中」</b>：手牌是悬空在台面上方的（第一行底边离台面 0.26 格，
     * 第二行起更高），基岩客户端的准星只认<b>方块</b>——瞄第二行及以上的牌时打不到牌桌方块，
     * 客户端就只发 {@code RIGHT_CLICK_AIR}。以前只处理方块事件，于是「第二行的牌能选中
     * （悬停高亮是服务端自己算的）但点击没反应，要站高一格才能点中」（用户原话）。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        // 定位器「蹲下+使用」的准星开关：这次右键已被 LocatorTitleService 吞掉，
        // 不能再当作出牌 / 摸牌 / 放置牌桌（判据见 isSwallowed 的注释）。
        if (LocatorTitleService.isSwallowed(event.getPlayer())) {
            return;
        }
        PlayerInteractEvent.Action action = event.getAction();
        if (action == PlayerInteractEvent.Action.RIGHT_CLICK_AIR) {
            onInteractAir(event);
            return;
        }
        if (action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        Block clicked = event.getBlock();
        boolean isTableBlock = clicked instanceof UnoTableBlocks.UnoTableBlock;
        UnoTable table = isTableBlock
                ? findTable(player.getLevel(),
                        clicked.getFloorX(), clicked.getFloorY(), clicked.getFloorZ())
                : null;

        if (isTableBlock) {
            event.setCancelled(true);
            if (table != null && claimClick(player)) {
                table.useBoard(player);
            }
            return;
        }
        if (event.getItem() instanceof UnoTableItem) {
            if (event.isCancelled() || !canPlace(player)) {
                event.setCancelled(true);
                return;
            }
            tryPlace(player, event);
        }
    }

    /**
     * 同一次右键只处理一遍。
     * <p>
     * 一次右键可能同时派发好几条事件 —— 「右键方块」+「右键空中」+「命中实体」，
     * 而**手持定位器**时客户端还会额外发一条「使用物品」事务（MOT 的
     * {@code UseItemProcessor_v113} 收到后会再派发一条 {@code RIGHT_CLICK_AIR}）。
     * 若都放行，同一次点击会被当成两次：先选中某张牌、紧接着又被"再点一次"取消，
     * 表现就是「点了没反应」。
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

    /**
     * 右键空中：准星落在方块之外，但玩家可能正瞄着悬空的手牌 / 桌面按钮。
     * <p>
     * 只在「已入座 + 就在自己那张桌子旁边 + 射线真的命中了手牌或按钮」时才处理并吞掉事件。
     * 三个条件缺一不可：没入座的人看桌面按钮也没意义（按钮只对行动方可见），
     * 未命中则什么都不做 —— 空中右键平时（吃东西、用道具、别的插件的物品）有自己的语义，不能乱拦。
     */
    private void onInteractAir(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UnoTable table = seatings.get(player.getUniqueId());

        if (table == null || !table.inUseRange(player)) {
            return;
        }
        // 同一次右键可能派发多条事件（见 claimClick：手持定位器时「方块 + 空中 + 实体」
        // 三路都会来），只认第一条，否则会「选中 → 立刻取消选中」，看起来完全没反应。
        if (!claimClick(player)) {
            return;
        }
        if (table.useBoardAir(player)) {
            event.setCancelled(true);
        }
    }

    /** 放置冷却去重：一次点击可能派发多个交互事件。 */
    private boolean canPlace(Player player) {
        long now = System.currentTimeMillis();
        Long last = placeCooldown.get(player.getUniqueId());
        if (last != null && now - last < PLACE_COOLDOWN_MS) {
            return false;
        }
        placeCooldown.put(player.getUniqueId(), now);
        return true;
    }

    /** 铺设 3x3 牌桌（区域以点击方块的对侧邻格为中心回退 1 格）。 */
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
        for (UnoTable t : tables.values()) {
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

        UnoTable table = new UnoTable(this, level, bx, by, bz, rotation);
        registerTable(table.getKey(), table);
        markDirty();

        if (!player.isCreative()) {
            Item hand = player.getInventory().getItemInHand();
            if (hand.getCount() <= 1) {
                player.getInventory().setItemInHand(Item.get(Item.AIR));
            } else {
                hand.setCount(hand.getCount() - 1);
                player.getInventory().setItemInHand(hand);
            }
        }
        player.sendTip("§a已铺设 UNO 牌桌，点击牌桌即可打开菜单入座。");
    }

    /**
     * 铺 3x3：中心 1 块 + 边 4 块 + 角 4 块。
     * <p>
     * facing 由几何位置决定（模型零度棱边朝北）：北边=0 西边=1 南边=2 东边=3；
     * 角模型零度棱边为 NE（北+东）→ f 旋转后 NE=0 NW=1 SW=2 SE=3。
     * <b>与资源包 {@code models/blocks/uno/*.geo.json} 的 X 预镜像约定配套</b>，改动必须同步。
     */
    private void placeBlocks(Level level, int bx, int by, int bz) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3 pos = new Vector3(bx + i, by, bz + j);
                if (i == 1 && j == 1) {
                    level.setBlock(pos, new UnoTableBlocks.CenterBlock(), true);
                } else if (i == 1) {
                    UnoTableBlocks.EdgeBlock edge = new UnoTableBlocks.EdgeBlock();
                    edge.setFacing(j == 0 ? 0 : 2);
                    level.setBlock(pos, edge, true);
                } else if (j == 1) {
                    UnoTableBlocks.EdgeBlock edge = new UnoTableBlocks.EdgeBlock();
                    edge.setFacing(i == 0 ? 1 : 3);
                    level.setBlock(pos, edge, true);
                } else {
                    UnoTableBlocks.CornerBlock corner = new UnoTableBlocks.CornerBlock();
                    corner.setFacing(i == 2 ? (j == 0 ? 0 : 3) : (j == 0 ? 1 : 2));
                    level.setBlock(pos, corner, true);
                }
            }
        }
    }

    /** 按坐标命中牌桌（3x3 区域 + 同关卡）。 */
    private UnoTable findTable(Level level, int x, int y, int z) {
        List<UnoTable> list = tablesByLevel.get(level);
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.size(); i++) {
            UnoTable table = list.get(i);
            if (table.contains(x, y, z)) {
                return table;
            }
        }
        return null;
    }

    /**
     * 牌桌方块可像木板一样被挖掘，但任意一处破坏成功即视作整体收回。
     * {@code ignoreCancelled = true}：出生点保护下事件已取消时不介入，避免整桌被误收。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block instanceof UnoTableBlocks.UnoTableBlock)) {
            return;
        }
        // 先确认方块确实登记在某个牌桌上：登记丢失（存档被删 / 已收回）时它属于「孤儿方块」，
        // 此时放行、按普通方块破坏；若先取消再 return，方块会永久残留且玩家无法清理。
        UnoTable table = findTable(block.getLevel(),
                block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        Level level = block.getLevel();
        level.addSound(
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.5, block.getFloorZ() + 0.5),
                Sound.DIG_WOOD, 1.0f, 1.0f);
        retract(event.getPlayer(), table,
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.25, block.getFloorZ() + 0.5));
    }

    /** 整体收回牌桌：延迟一 tick 回收实体与方块，并按需掉落牌桌物品。 */
    private void retract(Player player, UnoTable table, Vector3 dropAt) {
        boolean drop = !player.isCreative();
        Level level = table.getLevel();
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (!tables.containsKey(table.getKey())) {
                return;
            }
            deleteBoardFile(takeId(table.getKey()), table.getKey());
            table.destroy();
            markDirty();
            if (drop) {
                level.dropItem(dropAt, new UnoTableItem());
            }
        }, 1);
    }

    /**
     * 右键命中牌桌实体时：吞掉事件并把准星交给牌桌做射线判定。
     * <p>
     * <b>现状（2026-10-15）</b>：牌桌实体的碰撞箱是默认 0.001（用户要求把捕获框
     * <b>恢复原样</b>），准星实际不会再命中它 —— 悬空手牌的点击由「定位器」持握道具
     * 承担（手持物品的"使用"事务一定会上报服务端）。这里保留分派逻辑：日后若有实体
     * 重新带上真实碰撞箱，右键仍会走同一条 {@link UnoTable#useBoard(cn.nukkit.Player)}
     * 路径（命中手牌/按钮就操作，没命中就开菜单）。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (LocatorTitleService.isSwallowed(player)) {
            return;
        }
        if (!(event.getEntity() instanceof UnoTableEntity entity)) {
            return;
        }
        UnoTable table = entity.getTable();
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        if (!claimClick(player)) {
            return;
        }
        table.useBoard(player);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    /** 取（必要时分配）牌桌对应的存档编号。仅主线程调用。 */
    private int idFor(UnoTable table) {
        Integer id = tableIds.get(table.getKey());
        if (id == null) {
            id = nextTableId++;
            tableIds.put(table.getKey(), id);
        }
        return id;
    }

    /** 取出并释放已移除牌桌的存档编号；从未落盘过时返回 0。 */
    private int takeId(String key) {
        Integer id = tableIds.remove(key);
        return id != null ? id : 0;
    }

    /** 删除某张牌桌的存档（主线程同步写库，与斗地主 / 德州扑克同一套）。 */
    private void deleteBoardFile(int id, String key) {
        if (id <= 0) {
            return;
        }
        if (!BoardStorage.delete(STORAGE_KIND, id)) {
            plugin.getLogger().warning("删除 UNO 牌桌存档失败：" + id + "（" + key + "）");
        }
    }

    /**
     * 主线程捕获的牌桌原始快照：只拷贝值（坐标、朝向、倍数 k），
     * 不持有 {@link UnoTable} 引用，因此可安全地交给异步线程做字符串格式化。
     */
    private static final class TableSnapshot {
        final int id;
        final String level;
        final int x;
        final int y;
        final int z;
        final int rotation;
        final double coefficient;

        TableSnapshot(int id, UnoTable table) {
            this.id = id;
            this.level = table.getLevel().getName();
            this.x = table.getBaseX();
            this.y = table.getBaseY();
            this.z = table.getBaseZ();
            this.rotation = table.getRotation();
            this.coefficient = table.getCoefficient();
        }
    }

    /** 在主线程把当前所有牌桌拷贝成不可变快照。 */
    private List<TableSnapshot> capture() {
        List<TableSnapshot> snapshots = new ArrayList<>(tables.size());
        for (UnoTable table : tables.values()) {
            snapshots.add(new TableSnapshot(idFor(table), table));
        }
        return snapshots;
    }

    /** 把一张牌桌快照拼成存档文件文本（纯计算，可在异步线程执行）。 */
    private static String document(TableSnapshot s) {
        BoardStorage.Document doc = new BoardStorage.Document(
                "UNO 牌桌存档（由插件自动生成；手改后请保证 YAML 合法）",
                "恢复牌桌只依赖本文件，字段含义见各小节上方的注释。");

        doc.scalar("存档编号：与文件名一致，由插件分配，请勿手改", "id",
                String.format(Locale.ROOT, "%04d", s.id));

        Map<String, Object> position = new LinkedHashMap<>();
        position.put("world", s.level);
        position.put("x", s.x);
        position.put("y", s.y);
        position.put("z", s.z);
        position.put("rotation", s.rotation);
        doc.section("摆放位置：world 为所在世界名；(x, y, z) 为牌桌基准方块坐标；rotation 为朝向（0~3，玩家视角）",
                "position", position);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("coefficient", s.coefficient);
        doc.section("牌桌设置：coefficient = 结算倍数 k（0 = 免费局）", "settings", settings);

        return doc.build();
    }

    private void save() {
        if (!restored) {
            return;
        }
        writeAll(capture());
    }

    /** 关服兜底：同步写库（主线程，写完才返回）。 */
    public void saveNow() {
        if (!restored) {
            return;
        }
        writeAll(capture());
    }

    /** 逐张牌桌写库（主线程同步；单张失败只记日志，不影响其余牌桌）。 */
    private void writeAll(List<TableSnapshot> snapshots) {
        for (TableSnapshot s : snapshots) {
            if (!BoardStorage.write(STORAGE_KIND, s.id, document(s), s.level)) {
                plugin.getLogger().warning("保存 UNO 牌桌数据失败：" + STORAGE_KIND + " #" + s.id);
            }
        }
    }

    /** 请求在关卡加载完成后恢复磁盘上的牌桌。 */
    public void requestLoad() {
        this.loadPending = true;
    }

    /**
     * 从数据库恢复牌桌：重建牌桌对象、按坐标重放方块并生成实体。
     *
     * <p>2026-10-27 起存档在 SQLite（{@code board_state}），读写都是主线程同步 —— 与斗地主 /
     * 德州扑克一致，不再走异步线程读文件。</p>
     */
    public void load() {
        List<SavedTable> saved = parse();
        for (SavedTable record : saved) {
            restoreTable(record);
        }
        restored = true;
        // 2026-10-27：恢复日志统一由 StartupReport 汇总成一行（用户要求控制台别刷屏）
        StartupReport.report(plugin, 0, saved.size());
    }

    /** 读出全部牌桌存档并解析成纯数据（主线程，量很小）。编号即 {@code board_state.board_id}。 */
    private List<SavedTable> parse() {
        List<SavedTable> saved = new ArrayList<>();
        for (int id : BoardStorage.ids(STORAGE_KIND)) {
            Object data = BoardStorage.load(STORAGE_KIND, id);
            if (data instanceof Map<?, ?> map) {
                saved.add(new SavedTable(id, map));
            } else {
                plugin.getLogger().warning("读取 UNO 牌桌存档失败（YAML 解析失败或文件不可读）：" + STORAGE_KIND + " #" + id);
            }
        }
        return saved;
    }
    private void restoreTable(SavedTable record) {
        Level level = Server.getInstance().getLevelByName(record.level);
        if (level == null) {
            plugin.getLogger().warning("UNO 牌桌所在世界未加载，跳过：" + record.level);
            return;
        }
        // 自定义方块 id 重启后可能重新分配，这里按坐标重放一遍，确保牌桌方块仍然有效
        placeBlocks(level, record.x, record.y, record.z);
        UnoTable table = new UnoTable(this, level, record.x, record.y, record.z, record.rotation);
        table.restoreCoefficient(record.coefficient);
        registerTable(table.getKey(), table);
        // 沿用在存档里占用的编号，并保证后续新牌桌不会与之冲突
        int id = record.id > 0 ? record.id : nextTableId;
        tableIds.put(table.getKey(), id);
        if (id >= nextTableId) {
            nextTableId = id + 1;
        }
    }

    /** 磁盘上的一条牌桌记录（一个文件一张牌桌）。 */
    private static final class SavedTable {
        final int id;
        final String level;
        final int x;
        final int y;
        final int z;
        final int rotation;
        final double coefficient;

        /** 按 {@code position / settings} 分节读取，字段语义见 {@link #document(TableSnapshot)} 生成的注释。 */
        SavedTable(int id, Map<?, ?> map) {
            this.id = id;
            Map<?, ?> position = BoardStorage.mapValue(map, "position");
            this.level = BoardStorage.stringValue(position, "world", "");
            this.x = BoardStorage.intValue(position, "x", 0);
            this.y = BoardStorage.intValue(position, "y", 0);
            this.z = BoardStorage.intValue(position, "z", 0);
            this.rotation = BoardStorage.intValue(position, "rotation", 0);
            this.coefficient = BoardStorage.doubleValue(BoardStorage.mapValue(map, "settings"),
                    "coefficient", 0.0);
        }
    }
}
