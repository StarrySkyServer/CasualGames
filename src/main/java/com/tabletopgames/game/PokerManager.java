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
import cn.nukkit.level.Position;
import cn.nukkit.level.Sound;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import com.tabletopgames.board.PokerTableBlocks;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.entity.DdzTableEntity;
import com.tabletopgames.item.PokerTableItem;
import com.tabletopgames.storage.Database;
import com.tabletopgames.storage.PokerTableDao;
import com.tabletopgames.storage.StartupReport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 德州扑克牌桌管理器：事件接线 + 存档恢复 + tick 驱动。
 *
 * <p>结构与 {@link DdzManager} 同构（3x3 铺设、按坐标恢复、关服只收实体不清方块），
 * 但牌桌状态简单得多：不存筹码、不存牌局，重启时正在打的那局直接作废。</p>
 */
public final class PokerManager implements Listener {

    private static final long PLACE_COOLDOWN_MS = 250L;
    private static final int LOAD_RETRY_TICKS = 20;

    private final PluginBase plugin;
    private final TabletopGamesManager gameManager;
    private final PokerTableDao dao;

    private final Map<UUID, Integer> clickTicks = new HashMap<>();
    private final Map<UUID, Long> placeCooldown = new HashMap<>();
    private final Map<String, String> savedSig = new HashMap<>();

    private boolean loadPending;
    private boolean restored;

    public PokerManager(PluginBase plugin, TabletopGamesManager gameManager, Database database) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.dao = new PokerTableDao(database);
    }

    public void requestLoad() {
        this.loadPending = true;
    }

    private void load() {
        List<PokerTableDao.Site> sites = dao.loadAll();
        int n = 0;
        for (PokerTableDao.Site s : sites) {
            Level level = Server.getInstance().getLevelByName(s.level());
            if (level == null) {
                plugin.getLogger().warning("德州扑克牌桌所在世界未加载，跳过：" + s.level() + " " + s.key());
                continue;
            }
            placeBlocks(level, s.x(), s.y(), s.z());
            PokerGame table = PokerGame.create(gameManager, level, s.x(), s.y(), s.z(), s.rotation());
            table.restore(s.minEntry(), TabletopGamesConfig.PokerTier.fromConfig(s.tier()));
            savedSig.put(table.getKey(), signature(table));
            n++;
        }
        restored = true;
        // 2026-10-27：恢复日志统一由 StartupReport 汇总成一行（用户要求控制台别刷屏）
        StartupReport.report(plugin, n, 0);
    }

    private static String signature(PokerGame g) {
        return g.getTier().name() + "|" + g.getMinEntry();
    }

    private void persist(PokerGame table) {
        if (!restored || table == null) {
            return;
        }
        dao.save(new PokerTableDao.Site(table.getKey(), table.getLevel().getName(),
                table.getBaseX(), table.getBaseY(), table.getBaseZ(), table.getRotation(),
                table.getTier().name(), table.getMinEntry()));
        savedSig.put(table.getKey(), signature(table));
    }

    public void tick() {
        if (loadPending && !Server.getInstance().getLevels().isEmpty()) {
            loadPending = false;
            load();
        }
        if (restored) {
            for (PokerGame t : PokerGame.all()) {
                String sig = signature(t);
                if (!sig.equals(savedSig.get(t.getKey()))) {
                    persist(t);
                }
                try {
                    t.tick();
                } catch (RuntimeException e) {
                    plugin.getLogger().error("德州扑克牌桌 " + t.getKey() + " tick 异常", e);
                }
            }
        } else {
            for (PokerGame t : PokerGame.all()) {
                t.tick();
            }
        }
    }

    /** 全局配置保存后：让空桌按新的「每局筹码」刷新（有人的桌子不动）。 */
    public void onConfigReloaded() {
        for (PokerGame t : PokerGame.all()) {
            try {
                t.refreshConfig();
            } catch (RuntimeException e) {
                plugin.getLogger().error("德州扑克刷新配置失败：" + t.getKey(), e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (LocatorTitleService.isSwallowed(event.getPlayer())) {
            return;
        }
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        Block clicked = event.getBlock();
        if (clicked instanceof PokerTableBlocks.PokerTableBlock) {
            event.setCancelled(true);
            PokerGame table = findTable(player.getLevel(),
                    clicked.getFloorX(), clicked.getFloorY(), clicked.getFloorZ());
            if (table == null || !claimClick(player)) {
                return;
            }
            int btn = PokerTableRay.hitButton(player, table);
            if (btn >= 0) {
                table.clickButton(player, btn);
            } else {
                table.useBoard(player);
            }
            return;
        }
        if (event.getItem() instanceof PokerTableItem) {
            if (event.isCancelled() || !canPlace(player)) {
                event.setCancelled(true);
                return;
            }
            tryPlace(player, event);
        }
    }

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
        for (PokerGame t : PokerGame.all()) {
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
        PokerGame table = PokerGame.create(gameManager, level, bx, by, bz, rotation);
        persist(table);
        if (!player.isCreative()) {
            Item hand = player.getInventory().getItemInHand();
            if (hand.getCount() <= 1) {
                player.getInventory().setItemInHand(Item.get(Item.AIR));
            } else {
                hand.setCount(hand.getCount() - 1);
                player.getInventory().setItemInHand(hand);
            }
        }
        player.sendTip("§a已铺设德州扑克牌桌，点击牌桌即可入座。");
    }

    private void placeBlocks(Level level, int bx, int by, int bz) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3 pos = new Vector3(bx + i, by, bz + j);
                if (i == 1 && j == 1) {
                    level.setBlock(pos, new PokerTableBlocks.CenterBlock(), true);
                } else if (i == 1) {
                    PokerTableBlocks.EdgeBlock edge = new PokerTableBlocks.EdgeBlock();
                    edge.setFacing(j == 0 ? 0 : 2);
                    level.setBlock(pos, edge, true);
                } else if (j == 1) {
                    PokerTableBlocks.EdgeBlock edge = new PokerTableBlocks.EdgeBlock();
                    edge.setFacing(i == 0 ? 1 : 3);
                    level.setBlock(pos, edge, true);
                } else {
                    PokerTableBlocks.CornerBlock corner = new PokerTableBlocks.CornerBlock();
                    corner.setFacing(i == 2 ? (j == 0 ? 0 : 3) : (j == 0 ? 1 : 2));
                    level.setBlock(pos, corner, true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block instanceof PokerTableBlocks.PokerTableBlock)) {
            return;
        }
        PokerGame table = findTable(block.getLevel(),
                block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Level level = block.getLevel();
        Vector3 dropAt = new Vector3(block.getFloorX() + 0.5,
                block.getFloorY() + 0.25, block.getFloorZ() + 0.5);
        boolean drop = !player.isCreative();
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (!PokerGame.all().contains(table)) {
                return;
            }
            table.destroy();
            dao.delete(table.getKey());
            savedSig.remove(table.getKey());
            if (drop) {
                level.dropItem(dropAt, new PokerTableItem());
            }
        }, 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (LocatorTitleService.isSwallowed(player)) {
            return;
        }
        PokerGame table = null;
        if (event.getEntity() instanceof DdzTableEntity entity) {
            table = entity.getPokerTable();
        }
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        if (!claimClick(player)) {
            return;
        }
        table.useBoard(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PokerGame table = PokerGame.of(event.getPlayer());
        if (table != null) {
            table.onQuit(event.getPlayer());
        }
        UUID id = event.getPlayer().getUniqueId();
        clickTicks.remove(id);
        placeCooldown.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PokerGame table = PokerGame.of(event.getPlayer());
        if (table != null) {
            table.reattach(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (PokerGame t : PokerGame.all()) {
            t.forgetViewer(id);
        }
    }

    private PokerGame findTable(Level level, int x, int y, int z) {
        for (PokerGame t : PokerGame.all()) {
            if (t.getLevel() == level && t.contains(x, y, z)) {
                return t;
            }
        }
        return null;
    }

    public PokerGame tableOf(Player player) {
        return PokerGame.of(player);
    }

    public void saveNow() {
        for (PokerGame t : PokerGame.all()) {
            dao.save(new PokerTableDao.Site(t.getKey(), t.getLevel().getName(),
                    t.getBaseX(), t.getBaseY(), t.getBaseZ(), t.getRotation(),
                    t.getTier().name(), t.getMinEntry()));
            savedSig.put(t.getKey(), signature(t));
        }
    }

    public void shutdown() {
        saveNow();
        PokerGame.shutdownAll();
        clickTicks.clear();
        placeCooldown.clear();
        savedSig.clear();
    }

    public PokerGame giveTable(Player player, Position pos, int rotation) {
        return PokerGame.create(gameManager, player.getLevel(),
                pos.getFloorX(), pos.getFloorY(), pos.getFloorZ(), rotation);
    }
}
