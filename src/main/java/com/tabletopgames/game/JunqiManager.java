package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerInteractEntityEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Sound;
import cn.nukkit.math.BlockFace;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import com.tabletopgames.ai.junqi.JunqiAi;
import com.tabletopgames.board.JunqiBoardBlock;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.entity.JunqiPieceEntity;
import com.tabletopgames.item.JunqiBoardItem;
import com.tabletopgames.storage.BoardStorage;
import com.tabletopgames.storage.StartupReport;
import com.tabletopgames.storage.JunqiFormationDao;
import com.tabletopgames.storage.PlayerSettingsDao;
import com.tabletopgames.ui.JunqiMenu;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 军旗棋盘管理器：放置 / 破坏 / 右键交互 / 准星 / 周期任务 / 配置 / 存档。
 *
 * <p>棋盘是 3×3 方块（9 格，与五子棋 3×3 同规格：以点击方块为中心，区域原点回退 P+Q）。
 * 其中左侧 2×3 块是对局区（60 点位），右侧 1×3 块是淘汰棋子托盘。</p>
 */
public class JunqiManager implements Listener {

    private static final long PLACE_COOLDOWN_MS = 500L;
    private static final String STORAGE_KIND = "junqi";

    private final PluginBase plugin;
    private final TabletopGamesManager gameManager;
    /** 「我的布阵」持久化（SQLite）。 */
    private final JunqiFormationDao formations;
    /** 玩家个人设置（军旗敌方棋子倒置开关）。 */
    private final PlayerSettingsDao playerSettings;
    /** 开启「敌方棋子倒置」的玩家；只影响该玩家自己的视角。 */
    private final Set<UUID> flipEnemies = ConcurrentHashMap.newKeySet();
    private final Map<String, JunqiBoard> boards = new ConcurrentHashMap<>();
    private final Map<Level, List<JunqiBoard>> boardsByLevel = new ConcurrentHashMap<>();
    private final Map<UUID, Long> placeCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> hoverTick = new ConcurrentHashMap<>();

    private volatile boolean dirty;
    private volatile boolean loadPending = true;
    private volatile boolean restored;

    private final Map<String, Integer> boardIds = new ConcurrentHashMap<>();
    private int nextBoardId = 1;

    /** 观看者动画兜底重发节拍（每 tick 计 1，满 20 发一次 = 1 秒）。 */
    private int viewerRefresh;

    private final List<UUID> leftScratch = new ArrayList<>(2);

    public JunqiManager(PluginBase plugin, TabletopGamesManager gameManager,
                        JunqiFormationDao formations, PlayerSettingsDao playerSettings) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.formations = formations;
        this.playerSettings = playerSettings;
        this.flipEnemies.addAll(playerSettings.loadJunqiFlipEnemies());
    }

    public PluginBase getPlugin() {
        return plugin;
    }

    /** 玩家保存的军旗布阵（菜单用）。 */
    public JunqiFormationDao formations() {
        return formations;
    }

    /** 该玩家是否开启「敌方棋子倒置」。 */
    public boolean flipEnemies(UUID id) {
        return flipEnemies.contains(id);
    }

    /** 切换「敌方棋子倒置」并落库，同时只给该玩家重发观看者动画。 */
    public boolean toggleFlipEnemies(Player player) {
        UUID id = player.getUniqueId();
        boolean on = !flipEnemies.contains(id);
        if (on) {
            flipEnemies.add(id);
        } else {
            flipEnemies.remove(id);
        }
        playerSettings.saveJunqiFlipEnemy(id, on);
        for (JunqiBoard board : boards.values()) {
            if (board.isSeated(id)) {
                board.sendViewers(true);
            }
        }
        player.sendTip(on ? "§a已开启敌方棋子倒置。" : "§e已关闭敌方棋子倒置。");
        return on;
    }

    /** 军旗配置节（config.yml 内的 {@code junqi} 小节）。 */
    public TabletopGamesConfig.JunqiSettings config() {
        return gameManager.junqiConfig();
    }

    public double playerRange() {
        return gameManager.playerRangeOf(BoardKind.JUNQI);
    }

    public long menuLockMs() {
        return config().getMenuLockSeconds() * 1000L;
    }

    // ==================== 索引 ====================

    public void markDirty() {
        dirty = true;
    }

    /** 玩家是否坐在本类任意一张军旗棋盘上（定位器准星「只在座位上显示」的判据）。 */
    public boolean isSeated(UUID id) {
        for (JunqiBoard board : boards.values()) {
            if (board.isSeated(id)) {
                return true;
            }
        }
        return false;
    }

    public void requestLoad() {
        loadPending = true;
    }

    private void indexBoard(JunqiBoard board) {
        boardsByLevel.computeIfAbsent(board.getLevel(), k -> new ArrayList<>()).add(board);
    }

    private void unindexBoard(JunqiBoard board) {
        List<JunqiBoard> list = boardsByLevel.get(board.getLevel());
        if (list != null) {
            list.remove(board);
        }
    }

    private JunqiBoard findBoard(Level level, int x, int y, int z) {
        List<JunqiBoard> list = boardsByLevel.get(level);
        if (list == null) {
            return null;
        }
        for (JunqiBoard board : list) {
            if (board.contains(x, y, z)) {
                return board;
            }
        }
        return null;
    }

    // ==================== 放置 ====================

    /**
     * 右键：手持军旗棋盘 → 放置；点棋盘方块 → 摆棋/对局交互。
     * <p>刻意不加 {@code ignoreCancelled}（出生点保护会先取消非 OP 的交互，加了就收不到）。</p>
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getItem() instanceof JunqiBoardItem) {
            if (event.isCancelled() || !canPlace(player)) {
                event.setCancelled(true);
                return;
            }
            tryPlace(player, event);
            return;
        }
        Block clicked = event.getBlock();
        if (clicked instanceof JunqiBoardBlock) {
            event.setCancelled(true);
            JunqiBoard board = findBoard(player.getLevel(),
                    clicked.getFloorX(), clicked.getFloorY(), clicked.getFloorZ());
            if (board != null) {
                useBoard(player, board);
            }
        }
    }

    private void useBoard(Player player, JunqiBoard board) {
        if (board.getPhase() == JunqiBoard.Phase.PLAY) {
            if (!board.isSeated(player.getUniqueId())) {
                player.sendTip("§c你不是本局玩家。");
                return;
            }
            // 落在格子之间的空白（等分命中区之外）当作「点了棋盘空白」：打开对局表单
            if (board.aimCell(player) == 0) {
                JunqiMenu.open(player, board);
                return;
            }
            String tip = board.select(player);
            if (tip != null) {
                player.sendTip(tip);
            }
            return;
        }
        if (board.getPhase() == JunqiBoard.Phase.SETUP) {
            // 摆棋阶段：选中 / 交换 / 移动都在世界里点；空白区域同样打开布阵表单
            if (board.isSeated(player.getUniqueId())) {
                int aim = board.aimCell(player);
                if (aim == 0) {
                    JunqiMenu.open(player, board);
                    return;
                }
                String tip = board.setupClick(player, aim);
                if (JunqiBoard.OPEN_MENU.equals(tip)) {
                    JunqiMenu.open(player, board);
                } else if (tip != null) {
                    player.sendTip(tip);
                }
                return;
            }
        }
        JunqiMenu.open(player, board);
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
        int bx = target.getFloorX();
        int by = target.getFloorY();
        int bz = target.getFloorZ();
        Level level = player.getLevel();

        int rotation = JunqiBoard.normalizeRotation((int) Math.round(player.getYaw() / 90.0));
        int px = axisPX(rotation);
        int pz = axisPZ(rotation);
        int qx = axisQX(rotation);
        int qz = axisQZ(rotation);

        // 3×3 以点击方块为中心：区域原点回退 P+Q，使中心格 (1,1) 正好落在放置点
        // （与五子棋 3×3 棋盘同一规则，见 TabletopGamesManager.tryPlace）
        bx -= px + qx;
        bz -= pz + qz;

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Block b = level.getBlock(bx + i * px + j * qx, by, bz + i * pz + j * qz);
                if (!b.isAir() && !b.canBeReplaced()) {
                    player.sendTip("§c空间不足，放置军旗棋盘需要 3x3 空地。");
                    return;
                }
            }
        }
        List<JunqiBoard> sameLevel = boardsByLevel.get(level);
        if (sameLevel != null) {
            for (JunqiBoard board : sameLevel) {
                if (board.getBaseY() == by && overlaps(board, bx, bz, rotation)) {
                    player.sendTip("§c这里已经有棋盘了。");
                    return;
                }
            }
        }

        event.setCancelled(true);
        placeBlocks(level, bx, by, bz, rotation);
        level.addSound(new Vector3(
                bx + 0.5 + 0.5 * px + 1.0 * qx,
                by + 0.5,
                bz + 0.5 + 0.5 * pz + 1.0 * qz), Sound.DIG_WOOD, 1.0f, 0.8f);

        JunqiBoard board = new JunqiBoard(this, level, bx, by, bz, rotation);
        board.spawnPieces();
        boards.put(board.getKey(), board);
        indexBoard(board);
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
        player.sendTip("§a已放置军旗棋盘（3x3，9 格）。两名玩家入座后开始摆棋。");
    }

    private void placeBlocks(Level level, int bx, int by, int bz, int rotation) {
        int px = axisPX(rotation);
        int pz = axisPZ(rotation);
        int qx = axisQX(rotation);
        int qz = axisQZ(rotation);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                JunqiBoardBlock block = new JunqiBoardBlock();
                block.setPosition((i + j * 3) * 4 + rotation);
                level.setBlock(new Vector3(bx + i * px + j * qx, by, bz + i * pz + j * qz), block, true);
            }
        }
    }

    private static boolean overlaps(JunqiBoard board, int bx, int bz, int rotation) {
        int[] a = footprintBounds(board.getBaseX(), board.getBaseZ(), board.getRotation());
        int[] b = footprintBounds(bx, bz, rotation);
        return a[0] <= b[1] && b[0] <= a[1] && a[2] <= b[3] && b[2] <= a[3];
    }

    private static int[] footprintBounds(int bx, int bz, int rotation) {
        int px = axisPX(rotation);
        int pz = axisPZ(rotation);
        int qx = axisQX(rotation);
        int qz = axisQZ(rotation);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                int wx = bx + i * px + j * qx;
                int wz = bz + i * pz + j * qz;
                minX = Math.min(minX, wx);
                maxX = Math.max(maxX, wx);
                minZ = Math.min(minZ, wz);
                maxZ = Math.max(maxZ, wz);
            }
        }
        return new int[]{minX, maxX, minZ, maxZ};
    }

    // ==================== 破坏 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block instanceof JunqiBoardBlock)) {
            return;
        }
        JunqiBoard board = findBoard(block.getLevel(),
                block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (board == null) {
            return;
        }
        event.setCancelled(true);
        Level level = block.getLevel();
        level.addSound(new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.5,
                block.getFloorZ() + 0.5), Sound.DIG_WOOD, 1.0f, 1.0f);
        retract(event.getPlayer(), board,
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.25, block.getFloorZ() + 0.5));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getEntity() instanceof JunqiPieceEntity) {
            event.setCancelled(true);
        }
    }

    private void retract(Player player, JunqiBoard board, Vector3 dropAt) {
        boolean drop = !player.isCreative();
        Level level = board.getLevel();
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (boards.remove(board.getKey()) == null) {
                return;
            }
            unindexBoard(board);
            deleteBoardFile(takeId(board.getKey()), board.getKey());
            board.remove();
            markDirty();
            if (drop) {
                level.dropItem(dropAt, new JunqiBoardItem());
            }
        }, 1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        placeCooldown.remove(id);
        hoverTick.remove(id);
        for (JunqiBoard board : boards.values()) {
            if (board.isSeated(id)) {
                board.leave(id);
            } else {
                board.releaseMenu(id);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        updateHover(event.getPlayer());
    }

    private boolean claimHover(Player player) {
        int now = Server.getInstance().getTick();
        Integer last = hoverTick.get(player.getUniqueId());
        if (last != null && last == now) {
            return false;
        }
        hoverTick.put(player.getUniqueId(), now);
        return true;
    }

    private void updateHover(Player player) {
        List<JunqiBoard> list = boardsByLevel.get(player.getLevel());
        if (list == null || list.isEmpty()) {
            return;
        }
        if (!claimHover(player)) {
            return;
        }
        for (JunqiBoard board : list) {
            if (board.inRange(player)) {
                board.updateHover(player);
            }
        }
    }

    // ==================== 周期任务 ====================

    public void tick() {
        if (loadPending && !Server.getInstance().getLevels().isEmpty()) {
            loadPending = false;
            load();
        }
        if (dirty) {
            dirty = false;
            save();
        }
        viewerRefresh++;
        boolean refreshViewers = viewerRefresh >= 4;
        if (refreshViewers) {
            viewerRefresh = 0;
        }
        for (JunqiBoard board : boards.values()) {
            if (board.isPiecesMissing() && board.hasPlayerInRange()) {
                board.spawnPieces();
            }
            if (refreshViewers && !board.isPiecesMissing()) {
                board.sendViewers(false);
            }
            // 准星：摆棋阶段两人都要高亮；对局阶段只处理行棋方
            if (board.getPhase() == JunqiBoard.Phase.SETUP) {
                for (Player p : board.getLevel().getPlayers().values()) {
                    if (board.isSeated(p.getUniqueId()) && board.inRange(p) && claimHover(p)) {
                        board.updateHover(p);
                    }
                }
            } else if (board.getPhase() == JunqiBoard.Phase.PLAY) {
                UUID hoverId = board.getTurn() == JunqiBoard.SIDE_RED
                        ? board.seatOfColorPublic(JunqiBoard.SIDE_RED)
                        : board.seatOfColorPublic(JunqiBoard.SIDE_BLACK);
                if (hoverId != null) {
                    Player hover = Server.getInstance().getPlayer(hoverId).orElse(null);
                    if (hover != null && board.inRange(hover) && claimHover(hover)) {
                        board.updateHover(hover);
                    }
                }
            }
            board.collectLeftPlayers(leftScratch);
            for (UUID id : leftScratch) {
                board.leave(id);
                Player p = Server.getInstance().getPlayer(id).orElse(null);
                if (p != null) {
                    p.sendTip("§e你已离开棋盘范围，自动退出棋局。");
                }
            }
            board.tickMenuHolder();
            if (board.isBotTurn() && !board.isBotThinking()) {
                scheduleBotMove(board);
            }
        }
    }

    // ==================== 菜单 ====================

    /**
     * 派发一步机器人决策：**快照在主线程取，搜索放异步线程，回主线程落子**。
     *
     * <p>大师档是「三层 Alpha-Beta × 6 次暗棋采样」，量级 ~30 万次评估、几十毫秒到几百毫秒，
     * 放主线程会让服务器卡一下；这里与其它棋的 {@code scheduleAiMove} 同一个套路。</p>
     */
    public void scheduleBotMove(JunqiBoard board) {
        if (!board.isBotTurn() || board.isBotThinking()) {
            return;
        }
        board.setBotThinking(true);
        final int side = board.getBotSeat();
        final int level = Math.max(0, Math.min(2, board.getBotLevel() - 1));
        final int[] grid = board.believedGrid(side);
        final int[] unseen = board.unseenCounts(side);
        Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
            int move;
            try {
                move = JunqiAi.chooseMove(grid, side, unseen, level, new java.util.Random());
            } catch (RuntimeException e) {
                plugin.getLogger().error("军旗机器人搜索异常", e);
                move = -1;
            }
            final int chosen = move;
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                board.setBotThinking(false);
                board.applyBotMove(side, chosen);
            }, false);
        }, true);
    }

    public void runLater(Runnable action) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, action, 1);
    }

    public void reopenMenu(Player player, JunqiBoard board) {
        runLater(() -> JunqiMenu.reopen(player, board));
    }

    public void refreshSeatMenus(JunqiBoard board) {
        // 摆棋/对局阶段不自动弹窗（否则每放一枚棋子就弹一次）；准备/结算阶段才刷新
        if (board.getPhase() == JunqiBoard.Phase.SETUP || board.getPhase() == JunqiBoard.Phase.PLAY) {
            return;
        }
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p == null) {
                continue;
            }
            JunqiMenu.closeCurrentForm(p);
            reopenMenu(p, board);
        }
    }

    public void closeSeatMenus(JunqiBoard board) {
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null) {
                JunqiMenu.closeCurrentForm(p);
            }
        }
    }

    // ==================== 存档 ====================

    private int idFor(JunqiBoard board) {
        Integer id = boardIds.get(board.getKey());
        if (id == null) {
            id = nextBoardId++;
            boardIds.put(board.getKey(), id);
        }
        return id;
    }

    private int takeId(String key) {
        Integer id = boardIds.remove(key);
        return id != null ? id : 0;
    }

    private void deleteBoardFile(int id, String key) {
        if (id <= 0) {
            return;
        }
        if (!BoardStorage.delete(STORAGE_KIND, id)) {
            plugin.getLogger().warning("删除军旗棋盘存档失败：" + id + "（" + key + "）");
        }
    }
    private static final class Snapshot {
        final int id;
        final String level;
        final int x;
        final int y;
        final int z;
        final int rotation;
        final String phase;
        final String turn;
        final String winner;
        final int quiet;
        final boolean redOk;
        final boolean blackOk;
        final List<Map<String, Object>> red;
        final List<Map<String, Object>> black;
        final List<Integer> redTray;
        final List<Integer> blackTray;
        final int from;
        final int last;
        final String lastSide;
        final List<UUID> seatIds;
        final List<Integer> seatColors;
        final List<String> seatNames;

        Snapshot(int id, JunqiBoard board) {
            this.id = id;
            this.level = board.getLevel().getName();
            this.x = board.getBaseX();
            this.y = board.getBaseY();
            this.z = board.getBaseZ();
            this.rotation = board.getRotation();
            this.phase = board.exportPhase();
            this.turn = board.exportTurn();
            this.winner = board.exportWinner();
            this.quiet = board.getQuietMoves();
            this.redOk = board.isConfirmed(JunqiBoard.SIDE_RED);
            this.blackOk = board.isConfirmed(JunqiBoard.SIDE_BLACK);
            this.red = board.exportSide(JunqiBoard.SIDE_RED);
            this.black = board.exportSide(JunqiBoard.SIDE_BLACK);
            this.redTray = board.exportTray(JunqiBoard.SIDE_RED);
            this.blackTray = board.exportTray(JunqiBoard.SIDE_BLACK);
            this.from = board.getFrom();
            this.last = board.getLast();
            this.lastSide = board.exportLastSide();
            this.seatIds = board.exportSeatIds();
            this.seatColors = board.exportSeatColors();
            this.seatNames = board.exportSeatNames();
        }
    }

    private List<Snapshot> capture() {
        List<Snapshot> out = new ArrayList<>(boards.size());
        for (JunqiBoard board : boards.values()) {
            out.add(new Snapshot(idFor(board), board));
        }
        return out;
    }

    private static String document(Snapshot s) {
        BoardStorage.Document doc = new BoardStorage.Document(
                "军旗棋盘存档（一个棋盘一个文件，文件名即编号）",
                "只保存影响棋盘的数据：坐标 / 朝向 / 阶段 / 轮次 / 双方 25 枚的位置 / 上一手标记",
                "点位编号 cell = row * 5 + col + 1（1..60，行 0 在远端）；红方半场 31..60");
        doc.scalar("存档编号（与文件名一致）", "id", String.format(Locale.ROOT, "%04d", s.id));
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("level", s.level);
        pos.put("x", s.x);
        pos.put("y", s.y);
        pos.put("z", s.z);
        pos.put("rotation", s.rotation);
        doc.section("棋盘位置与朝向", "position", pos);

        Map<String, Object> game = new LinkedHashMap<>();
        game.put("phase", s.phase);
        game.put("turn", s.turn);
        game.put("winner", s.winner);
        game.put("redConfirmed", s.redOk);
        game.put("blackConfirmed", s.blackOk);
        game.put("quietMoves", s.quiet);
        doc.section("对局状态", "game", game);

        doc.section("红方棋子（cell=点位，piece=类型；revealed=true 表示已亮明）", "red", s.red);
        doc.section("黑方棋子", "black", s.black);

        Map<String, Object> trays = new LinkedHashMap<>();
        trays.put("red", s.redTray);
        trays.put("black", s.blackTray);
        doc.section("淘汰棋子托盘（按托盘格 0..23 顺序列出的槽号 0..24；"
                + "红方托盘存黑子、黑方托盘存红子）", "trays", trays);

        Map<String, Object> markers = new LinkedHashMap<>();
        markers.put("from", s.from);
        markers.put("last", s.last);
        markers.put("lastSide", s.lastSide);
        doc.section("上一手标记", "markers", markers);

        List<Map<String, Object>> seats = new ArrayList<>(2);
        for (int i = 0; i < s.seatIds.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", s.seatIds.get(i).toString());
            row.put("color", s.seatColors.get(i) == JunqiBoard.SIDE_BLACK ? "black" : "red");
            row.put("name", s.seatNames.get(i));
            seats.add(row);
        }
        doc.section("座位（uuid / 棋色 / 名字；读档后原样恢复，掉线的人仍在座）", "seats", seats);
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
        writeAll(capture());
    }
    private void writeAll(List<Snapshot> snapshots) {
        for (Snapshot s : snapshots) {
            if (!BoardStorage.write(STORAGE_KIND, s.id, document(s), s.level)) {
                plugin.getLogger().warning("写入军旗棋盘存档失败：" + STORAGE_KIND + " #" + s.id);
            }
        }
    }
    private void load() {
        List<SavedBoard> saved = parse();
        for (SavedBoard data : saved) {
            restoreBoard(data);
        }
        restored = true;
        // 2026-10-27：恢复日志统一由 StartupReport 汇总成一行（用户要求控制台别刷屏）
        StartupReport.report(plugin, 0, saved.size());
    }
    private List<SavedBoard> parse() {
        List<SavedBoard> out = new ArrayList<>();
        for (int id : BoardStorage.ids(STORAGE_KIND)) {
            Object loaded = BoardStorage.load(STORAGE_KIND, id);
            if (loaded instanceof Map<?, ?> map) {
                out.add(new SavedBoard(id, map));
            }
        }
        return out;
    }
    @SuppressWarnings("unchecked")
    private void restoreBoard(SavedBoard data) {
        Map<?, ?> map = data.map;
        Map<?, ?> pos = BoardStorage.mapValue(map, "position");
        String levelName = BoardStorage.stringValue(pos, "level", "");
        Level level = Server.getInstance().getLevelByName(levelName);
        if (level == null) {
            plugin.getLogger().warning("军旗存档 " + data.id + " 的关卡不存在：" + levelName);
            return;
        }
        int x = BoardStorage.intValue(pos, "x", 0);
        int y = BoardStorage.intValue(pos, "y", 0);
        int z = BoardStorage.intValue(pos, "z", 0);
        int rotation = BoardStorage.intValue(pos, "rotation", 0);

        Map<?, ?> game = BoardStorage.mapValue(map, "game");
        Map<?, ?> markers = BoardStorage.mapValue(map, "markers");
        Map<?, ?> trays = BoardStorage.mapValue(map, "trays");

        int[] cells = new int[JunqiPieceEntity.SLOTS];
        String[] pieces = new String[JunqiPieceEntity.SLOTS];
        boolean[] revealed = new boolean[JunqiPieceEntity.SLOTS];
        readSide(map.get("red"), 0, cells, pieces, revealed);
        readSide(map.get("black"), 1, cells, pieces, revealed);
        for (int i = 0; i < JunqiPieceEntity.SLOTS; i++) {
            if (pieces[i] == null) {
                pieces[i] = JunqiRules.TYPE_KEYS[JunqiRules.SIDE_TYPES[i % 25]];
            }
        }

        JunqiBoard board = new JunqiBoard(this, level, x, y, z, rotation);
        // 先恢复座位，再 importState（里面会按座位数修正阶段）
        List<UUID> seatIds = new ArrayList<>(2);
        List<Integer> seatColors = new ArrayList<>(2);
        List<String> seatNames = new ArrayList<>(2);
        Object seatRaw = map.get("seats");
        if (seatRaw instanceof List<?> seatList) {
            for (Object item : seatList) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                try {
                    seatIds.add(UUID.fromString(BoardStorage.stringValue(row, "uuid", "")));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                seatColors.add("black".equals(BoardStorage.stringValue(row, "color", "red"))
                        ? JunqiBoard.SIDE_BLACK : JunqiBoard.SIDE_RED);
                seatNames.add(BoardStorage.stringValue(row, "name", ""));
            }
        }
        board.restoreSeats(seatIds, seatColors, seatNames);
        board.importState(cells, pieces, revealed,
                BoardStorage.stringValue(game, "turn", "red"),
                BoardStorage.stringValue(game, "phase", "setup"),
                BoardStorage.stringValue(game, "winner", "none"),
                BoardStorage.intValue(game, "quietMoves", 0),
                BoardStorage.intValue(markers, "from", 0),
                BoardStorage.intValue(markers, "last", 0),
                BoardStorage.stringValue(markers, "lastSide", "black"),
                BoardStorage.boolValue(game, "redConfirmed", false),
                BoardStorage.boolValue(game, "blackConfirmed", false),
                !"setup".equals(BoardStorage.stringValue(game, "phase", "setup")));
        board.restoreTray(JunqiBoard.SIDE_RED, readTray(trays, "red"));
        board.restoreTray(JunqiBoard.SIDE_BLACK, readTray(trays, "black"));
        board.spawnPieces();
        boards.put(board.getKey(), board);
        indexBoard(board);
        boardIds.put(board.getKey(), data.id);
        nextBoardId = Math.max(nextBoardId, data.id + 1);
    }

    /** 读托盘列表（槽号 0..24，按托盘格顺序）；缺失或格式不对按空托盘处理。 */
    private static List<Integer> readTray(Map<?, ?> trays, String key) {
        List<Integer> out = new ArrayList<>(JunqiBoard.TRAY_CELLS);
        Object raw = trays.get(key);
        if (raw instanceof Map<?, ?> wrap && wrap.get("list") instanceof List<?>) {
            raw = wrap.get("list");
        }
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Number number) {
                out.add(number.intValue());
            } else {
                try {
                    out.add(Integer.parseInt(String.valueOf(item)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private static void readSide(Object raw, int index,
                                 int[] cells, String[] pieces, boolean[] revealed) {
        // Document.section 把列表 dump 成 "red:\n- cell: .."，读回来直接是 List；
        // 兼容极端情况下的 {list: [...]} 包装
        if (raw instanceof Map<?, ?> wrap && wrap.get("list") instanceof List<?>) {
            raw = wrap.get("list");
        }
        if (!(raw instanceof List<?> list)) {
            return;
        }
        int cursor = index * 25;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            while (cursor < index * 25 + 25 && cells[cursor] != 0) {
                cursor++;
            }
            if (cursor >= index * 25 + 25) {
                break;
            }
            int cell = BoardStorage.intValue(row, "cell", 0);
            String piece = BoardStorage.stringValue(row, "piece", "");
            if (cell <= 0 || piece.isEmpty()) {
                continue;
            }
            cells[cursor] = cell;
            pieces[cursor] = piece;
            revealed[cursor] = BoardStorage.boolValue(row, "revealed", false);
            cursor++;
        }
    }

    private static final class SavedBoard {
        final int id;
        final Map<?, ?> map;

        SavedBoard(int id, Map<?, ?> map) {
            this.id = id;
            this.map = map;
        }
    }

    // ==================== 关闭 ====================

    public void shutdown() {
        try {
            saveNow();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("关闭时保存军旗棋盘失败：" + e.getMessage());
        }
    }

    // ==================== 朝向轴（与 TabletopGames 一致） ====================

    public static int axisPX(int rotation) {
        return new int[]{1, 0, -1, 0}[JunqiBoard.normalizeRotation(rotation)];
    }

    public static int axisPZ(int rotation) {
        return new int[]{0, 1, 0, -1}[JunqiBoard.normalizeRotation(rotation)];
    }

    public static int axisQX(int rotation) {
        return new int[]{0, -1, 0, 1}[JunqiBoard.normalizeRotation(rotation)];
    }

    public static int axisQZ(int rotation) {
        return new int[]{1, 0, -1, 0}[JunqiBoard.normalizeRotation(rotation)];
    }
}
