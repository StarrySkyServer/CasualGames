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
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Sound;
import cn.nukkit.math.BlockFace;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import com.tabletopgames.ai.GoAi;
import com.tabletopgames.board.GoBoardBlock;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.entity.GoStoneEntity;
import com.tabletopgames.item.GoBoard13Item;
import com.tabletopgames.item.GoBoard19BigItem;
import com.tabletopgames.item.GoBoard19Item;
import com.tabletopgames.item.GoBoard9Item;
import com.tabletopgames.storage.BoardStorage;
import com.tabletopgames.storage.StartupReport;
import com.tabletopgames.ui.GoMenu;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 管理所有已放置的围棋棋盘：放置、破坏收回、菜单、落子交互、准星高亮与存档恢复。
 * <p>
 * 骨架对齐 {@link OthelloManager}；存档按棋类分目录，一个棋盘一个文件（{@code data/go/<编号>.yml}）。
 * 四种棋盘规格由物品决定：9 路 / 13 路用 2x2 方块，19 路用 3x3 或 4x4 方块。
 */
public class GoManager implements Listener {

    /** 放置棋盘的冷却时间（毫秒）：一次点击可能触发多个交互事件，需去重。 */
    private static final long PLACE_COOLDOWN_MS = 500L;

    /** 存档目录名（{@code data/<本名>/}），一个棋盘一个 {@code 0001.yml}。 */
    private static final String STORAGE_KIND = "go";

    private final PluginBase plugin;
    private final TabletopGamesManager gameManager;

    private final Map<String, GoBoard> boards = new HashMap<>();

    /** 每个棋盘对应的存档编号（文件名 {@code 0001.yml}），键为棋盘坐标 key。 */
    private final Map<String, Integer> boardIds = new HashMap<>();

    /** 下一个可用的存档编号。 */
    private int nextBoardId = 1;

    private final Map<UUID, Long> placeCooldown = new HashMap<>();
    private final Map<Level, List<GoBoard>> boardsByLevel = new HashMap<>();
    private final List<UUID> leftScratch = new ArrayList<>(4);
    private final Map<UUID, Integer> hoverTick = new HashMap<>();

    private boolean dirty;
    private boolean loadPending;
    private boolean restored;


    public GoManager(PluginBase plugin, TabletopGamesManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    public double playerRange() {
        return gameManager.playerRangeOf(BoardKind.GO);
    }

    public TabletopGamesConfig.GameSettings config() {
        return gameManager.configOf(BoardKind.GO);
    }

    long menuLockMs() {
        return gameManager.configOf(BoardKind.GO).getMenuLockSeconds() * 1000L;
    }

    public void requestLoad() {
        this.loadPending = true;
    }

    void markDirty() {
        this.dirty = true;
    }

    private void indexBoard(GoBoard board) {
        boardsByLevel.computeIfAbsent(board.getLevel(), k -> new ArrayList<>(4)).add(board);
    }

    private void unindexBoard(GoBoard board) {
        List<GoBoard> list = boardsByLevel.get(board.getLevel());
        if (list != null) {
            list.remove(board);
            if (list.isEmpty()) {
                boardsByLevel.remove(board.getLevel());
            }
        }
    }

    private GoBoard findBoard(Level level, int x, int y, int z) {
        List<GoBoard> list = boardsByLevel.get(level);
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.size(); i++) {
            GoBoard board = list.get(i);
            if (board.contains(x, y, z)) {
                return board;
            }
        }
        return null;
    }

    /**
     * 注意：与五子棋一致，这里刻意不设置 {@code ignoreCancelled = true}。
     * <p>
     * 基岩触屏点击会走 {@code Level#useItemOn}，该路径可能在派发事件前因出生点保护
     * 对非 OP 玩家调用 {@code setCancelled(true)}；忽略已取消事件会导致出生点附近
     * 完全收不到回调。放置棋盘仍遵守取消逻辑（内部再判断）。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        // 定位器「蹲下+使用」的准星开关：这次右键已被 LocatorTitleService 吞掉，不能再开棋盘菜单。
        if (LocatorTitleService.isSwallowed(player)) {
            return;
        }
        int[] spec = specOf(event.getItem());
        if (spec != null) {
            if (event.isCancelled() || !canPlace(player)) {
                event.setCancelled(true);
                return;
            }
            tryPlace(player, event, spec[0], spec[1]);
            return;
        }
        Block clicked = event.getBlock();
        if (clicked instanceof GoBoardBlock) {
            event.setCancelled(true);
            GoBoard board = findBoard(player.getLevel(),
                    clicked.getFloorX(), clicked.getFloorY(), clicked.getFloorZ());
            if (board != null) {
                useBoard(player, board);
            }
        }
    }

    /** 棋盘物品 → {@code {路数, 方块边长}}；非围棋物品返回 null。 */
    private static int[] specOf(Item item) {
        if (item instanceof GoBoard9Item) {
            return new int[]{9, 2};
        }
        if (item instanceof GoBoard13Item) {
            return new int[]{13, 2};
        }
        if (item instanceof GoBoard19Item) {
            return new int[]{19, 3};
        }
        if (item instanceof GoBoard19BigItem) {
            return new int[]{19, 4};
        }
        return null;
    }

    /** 命中棋盘方块：未开局弹菜单，对局中落子；点中已有棋子的交点时弹对局菜单。 */
    private void useBoard(Player player, GoBoard board) {
        if (!board.isRunning()) {
            GoMenu.open(player, board);
            return;
        }
        int cell = board.aimCell(player);
        if (cell == 0) {
            player.sendTip("§c请将准星对准棋盘上的交叉点。");
            return;
        }
        int n = board.getN();
        if (board.isOccupied((cell - 1) / n, (cell - 1) % n)) {
            GoMenu.openInGame(player, board);
            return;
        }
        String tip = board.place(player);
        if (tip != null) {
            player.sendTip(tip);
        }
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

    private void tryPlace(Player player, PlayerInteractEvent event, int n, int size) {
        BlockFace face = event.getFace();
        if (face == null) {
            return;
        }
        Vector3 target = event.getBlock().getSide(face);
        int bx = target.getFloorX();
        int by = target.getFloorY();
        int bz = target.getFloorZ();
        Level level = player.getLevel();

        int rotation = TabletopGamesBoard.normalizeRotation((int) Math.round(player.getYaw() / 90.0));
        int px = TabletopGamesBoard.axisPX(rotation);
        int pz = TabletopGamesBoard.axisPZ(rotation);
        int qx = TabletopGamesBoard.axisQX(rotation);
        int qz = TabletopGamesBoard.axisQZ(rotation);

        // 奇数边长以点击方块为中心：区域原点回退 (size-1)/2 格；偶数取下取整，行为与五子棋一致
        int back = (size - 1) / 2;
        bx -= back * px + back * qx;
        bz -= back * pz + back * qz;

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                Block b = level.getBlock(bx + i * px + j * qx, by, bz + i * pz + j * qz);
                if (!b.isAir() && !b.canBeReplaced()) {
                    player.sendTip("§c空间不足，放置棋盘需要 " + size + "x" + size + " 空地。");
                    return;
                }
            }
        }
        List<GoBoard> sameLevel = boardsByLevel.get(level);
        if (sameLevel != null) {
            for (int i = 0; i < sameLevel.size(); i++) {
                GoBoard board = sameLevel.get(i);
                if (board.getBaseY() == by && overlaps(board, bx, bz, size, rotation)) {
                    player.sendTip("§c这里已经有棋盘了。");
                    return;
                }
            }
        }

        event.setCancelled(true);
        placeBlocks(level, bx, by, bz, size, n, rotation);

        double half = (size - 1) / 2.0;
        level.addSound(new Vector3(
                bx + half * (px + qx) + 0.5,
                by + 0.5,
                bz + half * (pz + qz) + 0.5), Sound.DIG_WOOD, 1.0f, 0.8f);

        GoBoard board = new GoBoard(this, level, bx, by, bz, size, n, rotation);
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
        player.sendTip("§a已放置 " + n + " 路围棋棋盘。");
    }

    /** 按朝向铺设 size x size 棋盘方块，position = (i + j * size) * 4 + rotation。 */
    private void placeBlocks(Level level, int bx, int by, int bz, int size, int n, int rotation) {
        int px = TabletopGamesBoard.axisPX(rotation);
        int pz = TabletopGamesBoard.axisPZ(rotation);
        int qx = TabletopGamesBoard.axisQX(rotation);
        int qz = TabletopGamesBoard.axisQZ(rotation);
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                GoBoardBlock block = GoBoard.newBlock(n, size);
                block.setPosition((i + j * size) * 4 + rotation);
                level.setBlock(new Vector3(bx + i * px + j * qx, by, bz + i * pz + j * qz), block, true);
            }
        }
    }

    private static boolean overlaps(GoBoard board, int bx, int bz, int size, int rotation) {
        int[] a = footprintBounds(board.getBaseX(), board.getBaseZ(), board.getBoardSize(), board.getRotation());
        int[] b = footprintBounds(bx, bz, size, rotation);
        return a[0] <= b[1] && b[0] <= a[1] && a[2] <= b[3] && b[2] <= a[3];
    }

    private static int[] footprintBounds(int bx, int bz, int size, int rotation) {
        int px = TabletopGamesBoard.axisPX(rotation);
        int pz = TabletopGamesBoard.axisPZ(rotation);
        int qx = TabletopGamesBoard.axisQX(rotation);
        int qz = TabletopGamesBoard.axisQZ(rotation);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
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

    /**
     * 棋盘方块可像橡木板一样被挖掘，但任意一处破坏成功即视作整体收回。
     * <p>
     * {@link TabletopGamesManager#onBreak} 对 {@link GoBoardBlock} 已提前返回，
     * 不会抢先取消事件或重复播放音效。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block instanceof GoBoardBlock)) {
            return;
        }
        // 先确认方块确实登记在某个棋盘上：登记丢失（存档被删 / 已收回）时它属于「孤儿方块」，
        // 此时放行、按普通方块破坏；若先取消再 return，方块会永久残留且玩家无法清理。
        GoBoard board = findBoard(block.getLevel(),
                block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (board == null) {
            return;
        }
        event.setCancelled(true);
        Level level = block.getLevel();
        level.addSound(
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.5, block.getFloorZ() + 0.5),
                Sound.DIG_WOOD, 1.0f, 1.0f);
        retract(event.getPlayer(), board,
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.25, block.getFloorZ() + 0.5));
    }

    /** 右键命中棋子实体时吞掉事件：棋子没有交互，避免右键被实体抢走。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getEntity() instanceof GoStoneEntity) {
            event.setCancelled(true);
        }
    }

    /** 整体收回一个棋盘：延迟一 tick 移除棋子与方块，并按需掉落棋盘物品。 */
    private void retract(Player player, GoBoard board, Vector3 dropAt) {
        boolean drop = !player.isCreative();
        Level level = board.getLevel();
        int n = board.getN();
        int size = board.getBoardSize();
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (boards.remove(board.getKey()) == null) {
                return;
            }
            unindexBoard(board);
            deleteBoardFile(takeId(board.getKey()), board.getKey());
            board.remove();
            markDirty();
            if (drop) {
                level.dropItem(dropAt, dropItemFor(n, size));
            }
        }, 1);
    }

    private static Item dropItemFor(int n, int size) {
        if (n == 9) {
            return new GoBoard9Item();
        }
        if (n == 13) {
            return new GoBoard13Item();
        }
        return size >= 4 ? new GoBoard19BigItem() : new GoBoard19Item();
    }

    /** 玩家是否坐在本类任意一个棋盘上（定位器准星「只在座位上显示」的判据，2026-10-22）。 */
    public boolean isSeated(UUID id) {
        for (GoBoard board : boards.values()) {
            if (board.isSeated(id)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        placeCooldown.remove(id);
        hoverTick.remove(id);
        for (GoBoard board : boards.values()) {
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
        List<GoBoard> list = boardsByLevel.get(player.getLevel());
        if (list == null || list.isEmpty()) {
            return;
        }
        if (!claimHover(player)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            GoBoard board = list.get(i);
            if (board.isRunning() && board.inRange(player)) {
                board.updateHover(player);
            }
        }
    }

    public void runLater(Runnable action) {
        runLater(action, 1);
    }

    public void runLater(Runnable action, int ticks) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, action, ticks);
    }

    public void reopenMenu(Player player, GoBoard board) {
        runLater(() -> GoMenu.reopen(player, board));
    }

    public void refreshSeatMenus(GoBoard board) {
        if (board.isRunning()) {
            return;
        }
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p == null) {
                continue;
            }
            GoMenu.closeCurrentForm(p);
            reopenMenu(p, board);
        }
    }

    public void closeSeatMenus(GoBoard board) {
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null) {
                GoMenu.closeCurrentForm(p);
            }
        }
    }

    /** 周期任务：关卡就绪后恢复存档；棋子实体缺失且玩家回到范围时按需重建。 */
    public void tick() {
        if (loadPending && !Server.getInstance().getLevels().isEmpty()) {
            loadPending = false;
            load();
        }
        if (dirty) {
            dirty = false;
            save();
        }
        for (Map.Entry<Level, List<GoBoard>> entry : boardsByLevel.entrySet()) {
            boolean levelEmpty = entry.getKey().getPlayers().isEmpty();
            List<GoBoard> levelBoards = entry.getValue();
            for (int b = 0; b < levelBoards.size(); b++) {
                GoBoard board = levelBoards.get(b);
                if (!levelEmpty) {
                    if (board.isPiecesMissing() && board.hasPlayerInRange()) {
                        board.spawnPieces();
                    }
                    UUID hoverId = board.hoverPlayerId();
                    if (hoverId != null) {
                        Player hover = Server.getInstance().getPlayer(hoverId).orElse(null);
                        if (hover != null && board.inRange(hover) && claimHover(hover)) {
                            board.updateHover(hover);
                        }
                    }
                }
                board.collectLeftPlayers(leftScratch);
                for (int i = 0; i < leftScratch.size(); i++) {
                    UUID id = leftScratch.get(i);
                    board.leave(id);
                    Player p = Server.getInstance().getPlayer(id).orElse(null);
                    if (p != null) {
                        p.sendTip("§e你已离开棋盘范围，自动退出棋局。");
                    }
                }
                board.tickMenuHolder();
                // 轮到机器人：异步计算落点，避免阻塞主线程
                if (!levelEmpty && board.isAiTurn() && board.beginThinking()) {
                    scheduleAiMove(board);
                }
            }
        }
    }

    /** 派发一次机器人落子：在线程池中异步计算落点，再回到主线程落子。 */
    private void scheduleAiMove(GoBoard board) {
        int[] snapshot = board.copyGrid();
        int n = board.getN();
        int toMove = board.getTurn();
        int level = board.getAiLevel();
        int ko = board.koIndex();
        int passes = board.getPasses();
        double komi = board.getKomi();
        Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
            int[] move = GoAi.bestMove(snapshot, n, toMove, level, ko, passes, komi);
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                board.finishThinking();
                // 兜底：机器人着法被拒时不能再派发，否则本轮会无限重算并把线程池打满
                if (!board.aiApply(move)) {
                    board.abort("机器人无法落子，本局结束（可稍后「继承残局」继续）。");
                }
            }, false);
        }, true);
    }

    /** 取（必要时分配）棋盘对应的存档编号。仅主线程调用。 */
    private int idFor(GoBoard board) {
        Integer id = boardIds.get(board.getKey());
        if (id == null) {
            id = nextBoardId++;
            boardIds.put(board.getKey(), id);
        }
        return id;
    }

    /** 取出并释放已移除棋盘的存档编号；从未落盘过时返回 0。 */
    private int takeId(String key) {
        Integer id = boardIds.remove(key);
        return id != null ? id : 0;
    }

    /** 删除某个棋盘的存档（主线程同步写库，与斗地主 / 德州扑克同一套）。 */
    private void deleteBoardFile(int id, String key) {
        if (id <= 0) {
            return;
        }
        if (!BoardStorage.delete(STORAGE_KIND, id)) {
            plugin.getLogger().warning("删除围棋棋盘存档失败：" + id + "（" + key + "）");
        }
    }

    /**
     * 主线程捕获的棋盘原始快照：只拷贝值（坐标、朝向、规格、对局参数、局面编码），
     * 不持有 {@link GoBoard} 引用，因此可安全地交给异步线程做字符串格式化。
     */
    private static final class BoardSnapshot {
        final int id;
        final String level;
        final int x;
        final int y;
        final int z;
        final int rotation;
        final int n;
        final int size;
        final int turn;
        final boolean started;
        final double komi;
        final int handicap;
        final int passes;
        final String grid;

        BoardSnapshot(int id, GoBoard board) {
            this.id = id;
            this.level = board.getLevel().getName();
            this.x = board.getBaseX();
            this.y = board.getBaseY();
            this.z = board.getBaseZ();
            this.rotation = board.getRotation();
            this.n = board.getN();
            this.size = board.getBoardSize();
            this.turn = board.getTurn();
            this.started = board.hasStarted();
            this.komi = board.getKomi();
            this.handicap = board.getHandicap();
            this.passes = board.getPasses();
            this.grid = board.exportGrid();
        }
    }

    /** 在主线程把当前所有棋盘拷贝成不可变快照。 */
    private List<BoardSnapshot> capture() {
        List<BoardSnapshot> snapshots = new ArrayList<>(boards.size());
        for (GoBoard board : boards.values()) {
            snapshots.add(new BoardSnapshot(idFor(board), board));
        }
        return snapshots;
    }

    /** 把一个棋盘快照拼成存档文件文本（纯计算，可在异步线程执行）。 */
    private static String document(BoardSnapshot s) {
        BoardStorage.Document doc = new BoardStorage.Document(
                "围棋棋盘存档（由插件自动生成；手改后请保证 YAML 合法）",
                "恢复棋盘只依赖本文件，字段含义见各小节上方的注释。");

        doc.scalar("存档编号：与文件名一致，由插件分配，请勿手改", "id",
                String.format(Locale.ROOT, "%04d", s.id));

        Map<String, Object> position = new LinkedHashMap<>();
        position.put("world", s.level);
        position.put("x", s.x);
        position.put("y", s.y);
        position.put("z", s.z);
        position.put("rotation", s.rotation);
        doc.section("摆放位置：world 为所在世界名；(x, y, z) 为棋盘基准方块坐标；rotation 为朝向（0~3，玩家视角）",
                "position", position);

        Map<String, Object> board = new LinkedHashMap<>();
        board.put("n", s.n);
        board.put("size", s.size);
        board.put("grid", s.grid);
        doc.section("棋盘规格与局面：n = 路数（9 / 13 / 19）；size = 方块边长（2 = 2x2，3/4 = 19 路大棋盘）；"
                + "grid 为逐点落子编码（'.' 空 / '1' 黑 / '2' 白，按行拼接）",
                "board", board);

        Map<String, Object> game = new LinkedHashMap<>();
        game.put("state", s.started ? "playing" : "waiting");
        game.put("turn", s.turn);
        game.put("komi", s.komi);
        game.put("handicap", s.handicap);
        game.put("passes", s.passes);
        doc.section("对局进度：state = waiting(未开局) / playing(对局中)；turn = 轮到哪一方（1 黑 / 2 白）；"
                + "komi = 贴目；handicap = 让子数；passes = 连续虚手次数（双方各一次即终局数子）",
                "game", game);

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

    /** 逐份写库（主线程同步；单份失败只记日志，不影响其余棋盘）。 */
    private void writeAll(List<BoardSnapshot> snapshots) {
        for (BoardSnapshot s : snapshots) {
            if (!BoardStorage.write(STORAGE_KIND, s.id, document(s), s.level)) {
                plugin.getLogger().warning("保存围棋棋盘数据失败：" + STORAGE_KIND + " #" + s.id);
            }
        }
    }

    /**
     * 启动时从数据库恢复棋盘：重建棋盘对象、按坐标重新放置方块并生成棋子实体。
     *
     * <p>2026-10-27 起存档在 SQLite（{@code board_state}），读写都是主线程同步 —— 与斗地主 /
     * 德州扑克一致。放置方块、生成实体本来就要求主线程，所以不必再切线程。</p>
     */
    public void load() {
        List<SavedBoard> saved = parse();
        for (SavedBoard data : saved) {
            restoreBoard(data);
        }
        restored = true;
        // 2026-10-27：恢复日志统一由 StartupReport 汇总成一行（用户要求控制台别刷屏）
        StartupReport.report(plugin, 0, saved.size());
    }

    /** 读出本棋类全部存档并解析成纯数据（主线程，量很小）。编号即 {@code board_state.board_id}。 */
    private List<SavedBoard> parse() {
        List<SavedBoard> saved = new ArrayList<>();
        for (int id : BoardStorage.ids(STORAGE_KIND)) {
            Object data = BoardStorage.load(STORAGE_KIND, id);
            if (data instanceof Map<?, ?> map) {
                saved.add(new SavedBoard(id, map));
            } else {
                plugin.getLogger().warning("读取围棋棋盘存档失败（YAML 解析失败或文件不可读）：" + STORAGE_KIND + " #" + id);
            }
        }
        return saved;
    }
    private void restoreBoard(SavedBoard data) {
        Level level = Server.getInstance().getLevelByName(data.level);
        if (level == null) {
            plugin.getLogger().warning("围棋棋盘所在世界未加载，跳过：" + data.level);
            return;
        }
        // 自定义方块 id 重启后可能重新分配，这里按坐标重放一遍，确保棋盘方块仍然有效
        placeBlocks(level, data.x, data.y, data.z, data.size, data.n, data.rotation);
        GoBoard board = new GoBoard(this, level, data.x, data.y, data.z, data.size, data.n, data.rotation);
        board.importState(data.turn, data.started, data.komi, data.handicap, data.passes, data.grid);
        board.spawnPieces();
        boards.put(board.getKey(), board);
        indexBoard(board);
        // 沿用在存档里占用的编号，并保证后续新棋盘不会与之冲突
        int id = data.id > 0 ? data.id : nextBoardId;
        boardIds.put(board.getKey(), id);
        if (id >= nextBoardId) {
            nextBoardId = id + 1;
        }
    }

    /** 磁盘上的一条棋盘记录（一个文件一个棋盘）。 */
    private static final class SavedBoard {
        final int id;
        final String level;
        final int x;
        final int y;
        final int z;
        final int rotation;
        final int n;
        final int size;
        final int turn;
        final boolean started;
        final double komi;
        final int handicap;
        final int passes;
        final String grid;

        /** 按 {@code position / board / game} 分节读取，字段语义见 {@link #document(BoardSnapshot)} 生成的注释。 */
        SavedBoard(int id, Map<?, ?> map) {
            this.id = id;

            Map<?, ?> position = BoardStorage.mapValue(map, "position");
            this.level = BoardStorage.stringValue(position, "world", "");
            this.x = BoardStorage.intValue(position, "x", 0);
            this.y = BoardStorage.intValue(position, "y", 0);
            this.z = BoardStorage.intValue(position, "z", 0);
            this.rotation = BoardStorage.intValue(position, "rotation", 0);

            Map<?, ?> board = BoardStorage.mapValue(map, "board");
            this.n = BoardStorage.intValue(board, "n", 9);
            this.size = BoardStorage.intValue(board, "size", 2);
            this.grid = BoardStorage.stringValue(board, "grid", "");

            Map<?, ?> game = BoardStorage.mapValue(map, "game");
            this.started = "playing".equals(BoardStorage.stringValue(game, "state", "waiting"));
            this.turn = BoardStorage.intValue(game, "turn", GoBoard.BLACK);
            this.komi = BoardStorage.doubleValue(game, "komi", GoBoard.DEFAULT_KOMI);
            this.handicap = BoardStorage.intValue(game, "handicap", 0);
            this.passes = BoardStorage.intValue(game, "passes", 0);
        }
    }
}
