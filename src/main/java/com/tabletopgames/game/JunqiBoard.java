package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import com.tabletopgames.ai.junqi.JunqiAi;
import com.tabletopgames.board.BaseJunqiBoardBlock;
import com.tabletopgames.board.JunqiBoardBlock;
import com.tabletopgames.economy.EconomyHook;
import com.tabletopgames.item.LocatorService;
import com.tabletopgames.entity.JunqiPieceEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 一个已放置的军旗棋盘实例：摆棋阶段 + 对局阶段 + 渲染同步 + 座位/下注。
 *
 * <p>棋盘是 3×3 方块（左侧 2×3 块是对局区 32×48 模型单位，右侧 1×3 块是淘汰棋子托盘），
 * 60 个点位。红方半场 = 行 6..11（cell 31..60），
 * 黑方 = 行 0..5（放置者站在近端，因为资源包的棋盘贴图旋转了 180°，近端就是红方底线）。</p>
 *
 * <p>淘汰棋子：被吃掉的子放到吃子方的托盘（红方托盘在贴图下方组，存黑子；黑方托盘在上方组，
 * 存红子），按拥有者视角从左到右、从上到下依次占格，双方（及旁观者）都能看到。</p>
 *
 * <p>状态：{@link Phase#SETUP 摆棋} → {@link Phase#PLAY 对局} → {@link Phase#FINISHED 结束}。
 * 两人都「确认布阵」后自动进入 PLAY。</p>
 */
public class JunqiBoard {

    public static final int ROWS = JunqiRules.ROWS;
    public static final int COLS = JunqiRules.COLS;
    public static final int CELLS = JunqiRules.CELLS;
    public static final int SIDE_RED = JunqiRules.SIDE_RED;
    public static final int SIDE_BLACK = JunqiRules.SIDE_BLACK;

    /** 机器人座位的哨兵 UUID（本插件 v1 没有 AI，保留常量方便扩展）。 */
    public static final UUID AI_ID = new UUID(0L, 0L);

    /** 棋盘方块顶面高度（2 像素，与方块碰撞箱一致）。 */
    public static final double BOARD_TOP = 2.0 / 16.0;

    private static final double PX_PER_UNIT = 128.0;
    private static final double TEX_CX = 128.0;
    private static final double TEX_CY = 192.0;
    private static final double COL_X0 = 25.0;
    private static final double COL_STEP = 51.0;
    private static final double ROW_Y0 = 23.0;
    private static final double ROW_STEP = 27.0;
    /** 中线无人区比正常行距多出来的像素（5 模型单位）。 */
    private static final double ROW_GAP = 40.0;
    private static final double GAP_PY = 191.5;

    /** 棋盘朝向轴（与 TabletopGames 各棋一致）：0=南、1=西、2=北、3=东。 */
    private static final int[] AXIS_PX = {1, 0, -1, 0};
    private static final int[] AXIS_PZ = {0, 1, 0, -1};
    private static final int[] AXIS_QX = {0, -1, 0, 1};
    private static final int[] AXIS_QZ = {1, 0, -1, 0};

    public enum Phase {
        /** 已入座、还没点「开始棋局」（开始前菜单就在这个阶段）。 */
        READY,
        /** 摆棋阶段（两人可同时操作）。 */
        SETUP,
        /** 对局阶段。 */
        PLAY,
        /** 已结束（有赢家或和棋）。 */
        FINISHED
    }

    private final JunqiManager manager;
    private final Level level;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final int rotation;
    private final Vector3 center;

    /** 盘面：cell-1 -> 棋子编码（side*16+type），-1 为空。 */
    private final int[] grid = new int[CELLS];
    /** cell -> 槽号 + 1（0 = 无棋子）。 */
    private final int[] pieceAt = new int[CELLS + 1];
    /** 槽号 -> 点位（0 = 还没摆上场）。 */
    private final int[] slotCell = new int[JunqiPieceEntity.SLOTS];
    /** 槽号 -> 是否已亮明（吃过子）。 */
    private final boolean[] slotRevealed = new boolean[JunqiPieceEntity.SLOTS];
    /** 槽号 -> 淘汰格 index（0..23，-1 = 不在托盘）。 */
    private final int[] slotTray = new int[JunqiPieceEntity.SLOTS];
    /** 每方托盘已放数量：0 = 红方托盘（存黑子），1 = 黑方托盘（存红子）。 */
    private final int[] trayCount = new int[2];

    /** 每组托盘的格数：4 列 × 6 行。 */
    public static final int TRAY_CELLS = 24;

    private JunqiPieceEntity entity;

    // ==================== 座位 ====================
    private final List<UUID> seats = new ArrayList<>(2);
    private final Map<UUID, Integer> seatColors = new HashMap<>(2);
    private final Map<UUID, String> knownNames = new LinkedHashMap<>(4);

    // ==================== 阶段与状态 ====================
    private Phase phase = Phase.READY;
    private int turn = SIDE_RED;
    private boolean started;
    private boolean redConfirmed;
    private boolean blackConfirmed;
    private UUID winner;
    private boolean draw;
    private int quietMoves;
    private final Map<String, Integer> positionCounts = new HashMap<>();

    /** 连续无吃子判和的上限（0 = 不判）。 */
    private int maxQuietMoves = 150;

    // ==================== 交互状态 ====================
    /** 选中格（红/黑各一份，摆棋时两人可同时选中）。 */
    private int selR;
    private int selB;
    /** 准星格（逐观看者）。 */
    private int curR;
    private int curB;
    /** 上一手：起点 / 落点 / 走子方。 */
    private int from;
    private int last;
    private int lastSide = SIDE_BLACK;

    /** 机器人档位：0 = 没有机器人，1..3 = 入门/进阶/大师（见 {@link JunqiAi}）。 */
    private int botLevel;
    /** 机器人执哪一方（-1 = 没有）。 */
    private int botSeat = -1;
    /** 玩家点了「单人对战（x）」，等他选棋色。 */
    private int pendingBotLevel;
    private UUID pendingBotSeat;
    /** 机器人正在思考（避免重复派发）。 */
    private boolean botThinking;

    /** 点击返回哨兵：打开布阵表单（不是提示文案）。 */
    public static final String OPEN_MENU = "@open-menu@";

    /** 0 人入座时正在操作菜单的玩家。 */
    private UUID menuHolder;
    private long menuHolderUntil;

    // ==================== 下注 ====================
    private double presetBet;
    private double betAmount;
    private final Map<UUID, Double> stakes = new HashMap<>(2);

    private final Random random = new Random();

    public JunqiBoard(JunqiManager manager, Level level, int baseX, int baseY, int baseZ, int rotation) {
        this.manager = manager;
        this.level = level;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.rotation = normalizeRotation(rotation);
        this.center = new Vector3(
                baseX + 0.5 + 0.5 * AXIS_PX[this.rotation] + 1.0 * AXIS_QX[this.rotation],
                baseY,
                baseZ + 0.5 + 0.5 * AXIS_PZ[this.rotation] + 1.0 * AXIS_QZ[this.rotation]);
        java.util.Arrays.fill(grid, -1);
        java.util.Arrays.fill(pieceAt, 0);
        java.util.Arrays.fill(slotTray, -1);
    }

    // ==================== 基础访问 ====================
    public Level getLevel() {
        return level;
    }

    public JunqiManager getManager() {
        return manager;
    }

    public int getBaseX() {
        return baseX;
    }

    public int getBaseY() {
        return baseY;
    }

    public int getBaseZ() {
        return baseZ;
    }

    public int getRotation() {
        return rotation;
    }

    public String getKey() {
        return level.getName() + ":" + baseX + ":" + baseY + ":" + baseZ;
    }

    public Vector3 getCenter() {
        return center;
    }

    public Phase getPhase() {
        return phase;
    }

    public boolean isRunning() {
        return phase == Phase.PLAY;
    }

    public boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    public int getTurn() {
        return turn;
    }

    public UUID getWinner() {
        return winner;
    }

    public boolean isDraw() {
        return draw;
    }

    public boolean hasStarted() {
        return started;
    }

    public static String colorName(int color) {
        return color == SIDE_BLACK ? "黑方" : "红方";
    }

    public static int normalizeRotation(int value) {
        return ((value % 4) + 4) % 4;
    }

    // ==================== 座位 ====================
    public List<UUID> getSeats() {
        return seats;
    }

    public boolean isSeated(UUID id) {
        return seats.contains(id);
    }

    public int seatCount() {
        return seats.size();
    }

    public int seatColor(UUID id) {
        return seatColors.getOrDefault(id, -1);
    }

    public boolean isColorTaken(int color) {
        return seatColors.containsValue(color) || (hasBot() && botSeat == color);
    }

    /** 实际参战方数（含机器人）。 */
    public int playerCount() {
        return seats.size() + (hasBot() ? 1 : 0);
    }

    public boolean isFirstJoiner(UUID id) {
        return !seats.isEmpty() && seats.get(0).equals(id);
    }

    public boolean join(Player player, int color) {
        UUID id = player.getUniqueId();
        if (seats.contains(id)) {
            tipTo(id, "§e你已在座位上。");
            return false;
        }
        if (seats.size() >= 2) {
            tipTo(id, "§c座位已满（最多 2 人）。");
            return false;
        }
        if (color != SIDE_RED && color != SIDE_BLACK) {
            return false;
        }
        if (seatColors.containsValue(color)) {
            tipTo(id, "§c该棋色已被选择。");
            return false;
        }
        if (presetBet > 0 && EconomyHook.balance(id) < presetBet) {
            tipTo(id, "§c本局金额为 " + money(presetBet) + "，入座要求余额不低于该金额。");
            return false;
        }
        if (phase == Phase.FINISHED && seats.isEmpty()) {
            resetGameState();
        }
        seats.add(id);
        seatColors.put(id, color);
        knownNames.put(id, player.getName());
        releaseMenu(id);
        manager.markDirty();
        tipTo(id, "§a你已加入对局（" + colorName(color) + "）。");
        manager.refreshSeatMenus(this);
        sendViewers(true);
        LocatorService.give(player);
        return true;
    }

    public boolean leave(UUID id) {
        int index = seats.indexOf(id);
        if (index < 0) {
            return false;
        }
        String leaverName = nameOf(id);
        boolean wasRunning = phase == Phase.PLAY;
        int leavingSide = seatColors.getOrDefault(id, SIDE_RED);
        // 人机对战：人类退出时机器人一并撤离，避免留下无主的机器人座位
        if (botLevel > 0) {
            botLevel = 0;
            botSeat = -1;
            botThinking = false;
        }
        if (pendingBotSeat != null && pendingBotSeat.equals(id)) {
            pendingBotLevel = 0;
            pendingBotSeat = null;
        }
        seats.remove(index);
        seatColors.remove(id);
        knownNames.remove(id);
        releaseMenu(id);
        manager.markDirty();
        if (wasRunning) {
            if (!stakes.isEmpty() && !seats.isEmpty()) {
                // 赌博对局中途退出：不退注，直接判仍在座的一方获胜并按正常结算发钱
                UUID remaining = seats.get(0);
                winner = remaining;
                draw = false;
                phase = Phase.FINISHED;
                settleWin(seatColor(remaining));
                broadcastTip("§c" + leaverName + " 中途退出，判定 " + nameOf(remaining) + " 获胜！");
            } else {
                refundStakes();
                winner = null;
                draw = false;
                // 保留局面与轮次：两个座位重新坐满后可「继承残局」继续
                phase = Phase.READY;
                selR = selB = curR = curB = from = last = 0;
                broadcastTip("§c有玩家退出，本局结束（可稍后「继承残局」继续）。");
            }
            pushAll();
            sendViewers(true);
        } else if (phase == Phase.SETUP) {
            // 还没开局：清空他的半场，回到「准备」阶段
            resetSide(leavingSide);
            redConfirmed = false;
            blackConfirmed = false;
            phase = Phase.READY;
            pushAll();
            sendViewers(true);
        } else {
            // READY / FINISHED：局面原样保留（可能是一局等待「继承残局」的残局）
            sendViewers(true);
        }
        if (!seats.isEmpty()) {
            manager.refreshSeatMenus(this);
        }
        LocatorService.release(id);
        return true;
    }

    // ==================== 菜单占用 ====================
    public boolean tryAcquireMenu(Player player) {
        if (!seats.isEmpty()) {
            return true;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (menuHolder != null && !menuHolder.equals(id) && now < menuHolderUntil) {
            return false;
        }
        menuHolder = id;
        menuHolderUntil = now + manager.menuLockMs();
        return true;
    }

    public void releaseMenu(UUID id) {
        if (menuHolder != null && menuHolder.equals(id)) {
            menuHolder = null;
        }
    }

    public void releaseMenu(Player player) {
        releaseMenu(player.getUniqueId());
    }

    public boolean holdsMenu(Player player) {
        if (!seats.isEmpty()) {
            return true;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (menuHolder == null || !menuHolder.equals(id) || now >= menuHolderUntil) {
            return false;
        }
        menuHolderUntil = now + manager.menuLockMs();
        return true;
    }

    public void tickMenuHolder() {
        if (menuHolder == null) {
            return;
        }
        if (!seats.isEmpty()) {
            menuHolder = null;
            return;
        }
        Player holder = Server.getInstance().getPlayer(menuHolder).orElse(null);
        if (holder == null) {
            menuHolder = null;
            return;
        }
        if (System.currentTimeMillis() < menuHolderUntil) {
            return;
        }
        holder.closeFormWindows();
        holder.sendTip("§e菜单操作超时，已自动关闭。");
        menuHolder = null;
    }

    // ==================== 下注 ====================
    public double getPresetBet() {
        return presetBet;
    }

    public double getBetAmount() {
        return betAmount;
    }

    public double getMinBet() {
        return manager.config().getBetting().normalizedMinBet();
    }

    public double getMaxBet() {
        return manager.config().getBetting().normalizedMaxBet();
    }

    public String setPresetBet(Player player, double amount) {
        if (!seats.isEmpty()) {
            return "有人入座后不能再改每局金额（需在 0 人入座时设置）。";
        }
        if (!holdsMenu(player)) {
            return "你没有该棋盘的菜单操作权，请重新点击棋盘。";
        }
        if (amount < 0) {
            return "金额不能为负数。";
        }
        if (amount != 0) {
            double min = getMinBet();
            double max = getMaxBet();
            if (amount < min || amount > max) {
                return "金额只能是 0（免费局），或 " + money(min) + " ~ " + money(max) + " 之间。";
            }
        }
        presetBet = amount;
        manager.markDirty();
        player.sendTip("§7本局金额已设为 §e" + money(presetBet) + "§7"
                + (presetBet <= 0 ? "（免费局）。" : "（开局时双方各扣除该金额）。"));
        return null;
    }

    public String startGambling(double bet) {
        if (seats.size() < 2 || bet <= 0) {
            return "当前无法开始对局。";
        }
        for (UUID id : seats) {
            if (Server.getInstance().getPlayer(id).isEmpty()) {
                return "有玩家已离线，无法开始对局。";
            }
        }
        List<UUID> paid = new ArrayList<>(2);
        for (UUID id : seats) {
            if (EconomyHook.reduce(id, bet)) {
                paid.add(id);
            } else {
                for (UUID back : paid) {
                    EconomyHook.add(back, bet);
                }
                return nameOf(id) + " 扣款失败，请重试";
            }
        }
        stakes.clear();
        for (UUID id : seats) {
            stakes.put(id, bet);
        }
        betAmount = bet;
        for (UUID id : seats) {
            tipTo(id, "§6本局已从你的账户扣除 §e" + money(bet) + "§6，奖池 §e" + money(bet * 2) + "§6。");
        }
        return null;
    }

    private void settleWin(int winSide) {
        if (stakes.isEmpty()) {
            return;
        }
        double cut = manager.config().getBetting().normalizedCut();
        double pot = betAmount * 2;
        double commission = round2(pot * cut / 100.0);
        double prize = round2(pot - commission);
        UUID winnerId = seatOfColor(winSide);
        if (winnerId != null && prize > 0) {
            EconomyHook.add(winnerId, prize);
        }
        stakes.clear();
        betAmount = 0;
        broadcastTip("§6结算：奖池 §e" + money(pot) + "§6，抽水 §e" + money(commission)
                + "§6（" + cut + "%），赢家获得 §e" + money(prize) + "§6。");
    }

    private boolean refundStakes() {
        if (stakes.isEmpty()) {
            return false;
        }
        for (Map.Entry<UUID, Double> entry : stakes.entrySet()) {
            EconomyHook.add(entry.getKey(), entry.getValue());
        }
        stakes.clear();
        betAmount = 0;
        return true;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String money(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    // ==================== 摆棋 ====================

    /** 某方是否 25 枚全部摆好。 */
    public boolean isSideReady(int side) {
        for (int slot = side * 25; slot < side * 25 + 25; slot++) {
            if (slotCell[slot] == 0) {
                return false;
            }
        }
        return true;
    }

    public int placedCount(int side) {
        int count = 0;
        for (int slot = side * 25; slot < side * 25 + 25; slot++) {
            if (slotCell[slot] != 0) {
                count++;
            }
        }
        return count;
    }

    public boolean isConfirmed(int side) {
        return side == SIDE_RED ? redConfirmed : blackConfirmed;
    }

    /** 随机布阵（整体重排）。 */
    public boolean randomizeSide(int side) {
        if (phase != Phase.SETUP || isConfirmed(side)) {
            return false;
        }
        resetSide(side);
        int[] layout = JunqiRules.randomLayout(side, random);
        for (int i = 0; i < 25; i++) {
            int cell = layout[i];
            if (cell > 0) {
                placeSlot(side * 25 + i, cell);
            }
        }
        pushAll();
        manager.markDirty();
        return true;
    }

    /**
     * 当前布阵的「本位坐标」：与棋色无关，红黑都能用。
     *
     * <p>下标 = 槽号（{@link JunqiRules#SIDE_TYPES}），值 = {@code localRow * 5 + col}，0..29。
     * localRow 0 = 自己底线（红 11 行 / 黑 0 行），5 = 前线（红 6 行 / 黑 5 行）。</p>
     *
     * @return 25 个值；有子未摆（未满 25）时返回 {@code null}
     */
    public int[] exportLayout(int side) {
        int[] out = new int[25];
        for (int i = 0; i < 25; i++) {
            int cell = slotCell[side * 25 + i];
            if (cell == 0) {
                return null;
            }
            int row = JunqiRules.rowOf(cell);
            int localRow = side == SIDE_RED ? 11 - row : row;
            int localCol = JunqiRules.colOf(cell);
            if (localRow < 0 || localRow > 5 || localCol < 0 || localCol > 4) {
                return null;
            }
            out[i] = localRow * 5 + localCol;
        }
        return out;
    }

    /** 应用一份「本位坐标」布阵；只允许在摆棋阶段、本侧未确认时调用。 */
    public boolean applyLayout(int side, int[] layout) {
        if (phase != Phase.SETUP || isConfirmed(side) || layout == null || layout.length != 25) {
            return false;
        }
        int[] cells = new int[25];
        boolean[] used = new boolean[JunqiRules.CELLS + 1];
        for (int i = 0; i < 25; i++) {
            int local = layout[i];
            if (local < 0 || local >= 30) {
                return false;
            }
            int row = side == SIDE_RED ? 11 - local / 5 : local / 5;
            int cell = JunqiRules.cell(row, local % 5);
            int type = JunqiRules.SIDE_TYPES[i];
            if (used[cell] || !JunqiRules.canPlace(type, cell, side)) {
                return false;
            }
            used[cell] = true;
            cells[i] = cell;
        }
        resetSide(side);
        for (int i = 0; i < 25; i++) {
            placeSlot(side * 25 + i, cells[i]);
        }
        setSel(side, 0);
        pushAll();
        manager.markDirty();
        return true;
    }

    /** 确认 / 取消布阵；两人都确认后自动开局。 */
    public String setConfirmed(Player player, boolean confirmed) {
        if (phase != Phase.SETUP) {
            return "当前不在摆棋阶段。";
        }
        int side = seatColor(player.getUniqueId());
        if (side < 0) {
            return "你不在座位上。";
        }
        if (confirmed && !isSideReady(side)) {
            return "还没摆完（" + placedCount(side) + "/25）。";
        }
        if (side == SIDE_RED) {
            redConfirmed = confirmed;
        } else {
            blackConfirmed = confirmed;
        }
        manager.markDirty();
        if (confirmed) {
            setSel(side, 0);
        }
        if (redConfirmed && blackConfirmed && isSideReady(SIDE_RED) && isSideReady(SIDE_BLACK)) {
            startPlay();
        } else {
            manager.refreshSeatMenus(this);
        }
        return null;
    }

    /** 读档：恢复座位（uuid / 棋色 / 名字）。座位是影响能不能继续下棋的盘面数据，必须存。 */
    public void restoreSeats(java.util.List<UUID> ids, java.util.List<Integer> colors,
                             java.util.List<String> names) {
        seats.clear();
        seatColors.clear();
        knownNames.clear();
        for (int i = 0; i < ids.size() && i < 2; i++) {
            UUID id = ids.get(i);
            int color = i < colors.size() ? colors.get(i) : i;
            if (seatColors.containsValue(color)) {
                continue;
            }
            seats.add(id);
            seatColors.put(id, color);
            if (i < names.size()) {
                knownNames.put(id, names.get(i));
            }
        }
    }

    /** 导出席位（存档用）。 */
    public java.util.List<UUID> exportSeatIds() {
        return new java.util.ArrayList<>(seats);
    }

    public java.util.List<Integer> exportSeatColors() {
        java.util.List<Integer> out = new java.util.ArrayList<>(2);
        for (UUID id : seats) {
            out.add(seatColor(id));
        }
        return out;
    }

    public java.util.List<String> exportSeatNames() {
        java.util.List<String> out = new java.util.ArrayList<>(2);
        for (UUID id : seats) {
            out.add(nameOf(id));
        }
        return out;
    }

    /** 复位一局（重新开始前调用）。 */
    private void resetGameState() {
        resetSide(SIDE_RED);
        resetSide(SIDE_BLACK);
        redConfirmed = false;
        blackConfirmed = false;
        winner = null;
        draw = false;
        started = false;
        quietMoves = 0;
        positionCounts.clear();
        selR = selB = curR = curB = from = last = 0;
        botThinking = false;
        phase = Phase.READY;
        pushAll();
    }

    /**
     * 先入座的玩家点「开始棋局」：进入摆棋阶段。
     *
     * <p>机器人那一方直接随机布阵并确认；人类自己点棋盘打开布阵菜单。</p>
     */
    public String startSetup(Player player) {
        if (phase == Phase.SETUP) {
            return null;
        }
        if (phase != Phase.READY && phase != Phase.FINISHED) {
            return "当前无法开始棋局。";
        }
        if (playerCount() < 2) {
            return "需要两名玩家（或单人对战）才能开始棋局。";
        }
        if (player != null && !isFirstJoiner(player.getUniqueId())) {
            return "只有先入座的玩家可以开始棋局。";
        }
        resetSide(SIDE_RED);
        resetSide(SIDE_BLACK);
        redConfirmed = false;
        blackConfirmed = false;
        winner = null;
        draw = false;
        started = false;
        quietMoves = 0;
        positionCounts.clear();
        selR = selB = curR = curB = from = last = 0;
        phase = Phase.SETUP;
        // 下注：单人对战一律免费局（与另外五种棋的「人机不赌」一致）
        if (hasBot()) {
            if (presetBet > 0) {
                presetBet = 0;
                broadcastTip("§e单人对战为免费局，本局金额已清零。");
            }
        } else if (presetBet > 0) {
            String error = startGambling(presetBet);
            if (error != null) {
                phase = Phase.READY;
                return error;
            }
        }
        // 开局即自动随机布阵：两人一进来就是满 25 子，只需要确认或微调，不存在「没摆完」
        randomizeSide(SIDE_RED);
        randomizeSide(SIDE_BLACK);
        manager.markDirty();
        manager.closeSeatMenus(this);
        if (botLevel > 0 && botSeat >= 0) {
            if (botSeat == SIDE_RED) {
                redConfirmed = true;
            } else {
                blackConfirmed = true;
            }
            manager.markDirty();
        }
        pushAll();
        sendViewers(true);
        broadcastTip("§e摆棋阶段：已自动随机布阵，点棋盘可微调，或打开菜单确认 / 保存 / 换阵。");
        manager.refreshSeatMenus(this);
        return null;
    }

    /**
     * 继承残局：保留上一局中断时的局面与轮次，按「本来该谁走就谁走」继续。
     *
     * <p>只在「开过局但还没分出胜负」时可用（中途有人退出后回到 READY）。</p>
     */
    public String inheritEndgame(Player player) {
        if (phase != Phase.READY || !started || winner != null || draw) {
            return "没有可以继承的残局。";
        }
        if (playerCount() < 2) {
            return "需要两名玩家才能继承残局。";
        }
        if (player != null && !isFirstJoiner(player.getUniqueId())) {
            return "只有先入座的玩家可以继承残局。";
        }
        phase = Phase.PLAY;
        selR = 0;
        selB = 0;
        curR = 0;
        curB = 0;
        from = 0;
        last = 0;
        quietMoves = 0;
        positionCounts.clear();
        countPosition();
        pushAll();
        sendViewers(true);
        manager.markDirty();
        manager.closeSeatMenus(this);
        broadcastTip("§e已继承残局，" + colorName(turn) + "继续行棋。");
        return null;
    }

    /** 两人都确认后开局。 */
    private void startPlay() {
        phase = Phase.PLAY;
        started = true;
        turn = "black".equals(manager.config().getRules().getFirstMove()) ? SIDE_BLACK : SIDE_RED;
        if ("random".equals(manager.config().getRules().getFirstMove())) {
            turn = random.nextBoolean() ? SIDE_RED : SIDE_BLACK;
        }
        maxQuietMoves = manager.config().getRules().getDrawQuietMoves();
        selR = 0;
        selB = 0;
        curR = 0;
        curB = 0;
        from = 0;
        last = 0;
        quietMoves = 0;
        positionCounts.clear();
        countPosition();
        pushAll();
        sendViewers(true);
        manager.markDirty();
        manager.closeSeatMenus(this);
        broadcastTip("§e军旗对局开始！" + colorName(turn) + "先行。");
    }

    // ==================== 盘面基础操作 ====================

    private void placeSlot(int slot, int cell) {
        int side = slot / 25;
        int type = JunqiRules.SIDE_TYPES[slot % 25];
        grid[cell - 1] = JunqiRules.code(side, type);
        pieceAt[cell] = slot + 1;
        slotCell[slot] = cell;
    }

    private void removeSlot(int slot) {
        int cell = slotCell[slot];
        if (cell > 0) {
            grid[cell - 1] = -1;
            pieceAt[cell] = 0;
            slotCell[slot] = 0;
        }
    }

    /**
     * 被吃掉的子放进吃子方的淘汰托盘（按格 0..23 依次占位）。
     *
     * <p>托盘归属：红子（槽 0..24）进黑方托盘，黑子（槽 25..49）进红方托盘。
     * 每组 24 格，满了就不再放（军旗被吃时对局已结束，不影响结果）。</p>
     */
    private void addToTray(int slot) {
        int tray = slot < 25 ? 1 : 0;
        if (trayCount[tray] >= TRAY_CELLS) {
            return;
        }
        slotTray[slot] = trayCount[tray]++;
    }

    /** 清空某一方托盘（traySide：0 = 红方托盘存黑子，1 = 黑方托盘存红子）。 */
    private void clearTray(int traySide) {
        for (int i = 0; i < 25; i++) {
            slotTray[(1 - traySide) * 25 + i] = -1;
        }
        trayCount[traySide] = 0;
    }

    private void moveSlot(int slot, int to) {
        int fromCell = slotCell[slot];
        grid[fromCell - 1] = -1;
        pieceAt[fromCell] = 0;
        int side = slot / 25;
        int type = JunqiRules.SIDE_TYPES[slot % 25];
        grid[to - 1] = JunqiRules.code(side, type);
        pieceAt[to] = slot + 1;
        slotCell[slot] = to;
    }

    private void resetSide(int side) {
        for (int slot = side * 25; slot < side * 25 + 25; slot++) {
            removeSlot(slot);
            slotRevealed[slot] = false;
            slotTray[slot] = -1;
        }
        // 摆棋/重开一局时托盘一并清空（这两个动作都发生在还没有吃子的阶段）
        clearTray(0);
        clearTray(1);
    }

    private int slotAt(int cell) {
        int v = pieceAt[cell];
        return v == 0 ? -1 : v - 1;
    }

    // ==================== 摆棋交互 ====================

    /**
     * 摆棋阶段点击棋盘。
     *
     * @return 提示文案（null = 成功/静默）
     */
    public String setupClick(Player player, int cell) {
        if (phase != Phase.SETUP) {
            return null;
        }
        UUID id = player.getUniqueId();
        int side = seatColor(id);
        if (side < 0) {
            return "§c你不在座位上。";
        }
        if (isConfirmed(side)) {
            return "§e你已确认布阵，先点「取消布阵」再改。";
        }

        int slot = slotAt(cell);
        int selCell = side == SIDE_RED ? selR : selB;
        if (slot >= 0 && slot / 25 == side) {
            int type = JunqiRules.SIDE_TYPES[slot % 25];
            if (selCell == 0) {
                setSel(side, cell);
                pushMarkers();
                return null;
            }
            if (selCell == cell) {
                setSel(side, 0);
                pushMarkers();
                return null;
            }
            return swapCells(side, selCell, cell);
        }
        if (selCell == 0) {
            // 没选中：点空位 / 对方子 / 中间空白 → 打开布阵表单
            return OPEN_MENU;
        }
        // 选中 -> 空位：移动
        if (grid[cell - 1] != -1) {
            return "§c这里已经有子了。";
        }
        int selSlot = slotAt(selCell);
        int type = JunqiRules.SIDE_TYPES[selSlot % 25];
        if (!JunqiRules.canPlace(type, cell, side)) {
            return "§c" + JunqiRules.TYPE_CN[type] + "不能放在这里。";
        }
        moveSlot(selSlot, cell);
        setSel(side, 0);
        pushAll();
        manager.markDirty();
        return null;
    }

    private String swapCells(int side, int a, int b) {
        int slotA = slotAt(a);
        int slotB = slotAt(b);
        if (slotA < 0 || slotB < 0 || slotA / 25 != side || slotB / 25 != side) {
            return "§c只能和自己的棋子交换。";
        }
        int typeA = JunqiRules.SIDE_TYPES[slotA % 25];
        int typeB = JunqiRules.SIDE_TYPES[slotB % 25];
        if (!JunqiRules.canPlace(typeA, b, side)) {
            return "§c" + JunqiRules.TYPE_CN[typeA] + "不能放在那里。";
        }
        if (!JunqiRules.canPlace(typeB, a, side)) {
            return "§c" + JunqiRules.TYPE_CN[typeB] + "不能放在那里。";
        }
        removeSlot(slotA);
        removeSlot(slotB);
        placeSlot(slotA, b);
        placeSlot(slotB, a);
        setSel(side, 0);
        pushAll();
        manager.markDirty();
        return null;
    }

    private void setSel(int side, int cell) {
        if (side == SIDE_RED) {
            selR = cell;
        } else {
            selB = cell;
        }
    }

    // ==================== 对局交互 ====================

    /** 计算准星命中的点位（0 = 未命中）。 */
    public int aimCell(Player player) {
        if (player.getLevel() != this.level) {
            return 0;
        }
        Vector3 dir = player.getDirectionVector();
        if (Math.abs(dir.y) < 1e-9) {
            return 0;
        }
        double eyeX = player.x;
        double eyeY = player.y + player.getEyeHeight();
        double eyeZ = player.z;
        double planeY = baseY + BOARD_TOP;
        double t = (planeY - eyeY) / dir.y;
        if (t <= 0 || t > 12) {
            return 0;
        }
        double hitX = eyeX + dir.x * t;
        double hitZ = eyeZ + dir.z * t;
        double dxc = hitX - center.x;
        double dzc = hitZ - center.z;
        int p = rotation;
        double a = dxc * AXIS_PX[p] + dzc * AXIS_PZ[p];
        double b = dxc * AXIS_QX[p] + dzc * AXIS_QZ[p];
        double px = TEX_CX + PX_PER_UNIT * a;
        double py = TEX_CY - PX_PER_UNIT * b;
        int col = (int) Math.round((px - COL_X0) / COL_STEP);
        if (col < 0 || col >= COLS || px < 0 || px > 255) {
            return 0;
        }
        // 命中区按格子等分：每格中心 ±半格。半个格子之外（上下边缘、中线无人区、右侧托盘）
        // 一律返回 0，交给上层当作「点了棋盘空白」处理（打开表单），不再吸附到最近的棋子。
        if (Math.abs(px - (COL_X0 + col * COL_STEP)) > COL_STEP / 2.0) {
            return 0;
        }
        int row;
        if (py <= GAP_PY) {
            row = (int) Math.round((py - ROW_Y0) / ROW_STEP);
            row = Math.max(0, Math.min(5, row));
        } else {
            row = (int) Math.round((py - ROW_Y0 - ROW_GAP) / ROW_STEP);
            row = Math.max(6, Math.min(11, row));
        }
        double rowCenter = ROW_Y0 + row * ROW_STEP + (row >= 6 ? ROW_GAP : 0);
        if (Math.abs(py - rowCenter) > ROW_STEP / 2.0) {
            return 0;
        }
        return JunqiRules.cell(row, col);
    }

    /** 准星高亮（对局阶段只给行棋方；摆棋阶段两个人都可以）。 */
    public void updateHover(Player player) {
        UUID id = player.getUniqueId();
        int side = seatColor(id);
        if (side < 0) {
            return;
        }
        if (phase == Phase.PLAY && side != turn) {
            return;
        }
        if (phase == Phase.FINISHED) {
            return;
        }
        if (!inRange(player)) {
            return;
        }
        int aim = aimCell(player);
        int old = side == SIDE_RED ? curR : curB;
        if (aim != old) {
            if (side == SIDE_RED) {
                curR = aim;
            } else {
                curB = aim;
            }
            pushMarkers();
        }
    }

    /** 对局阶段点击棋盘：选子 / 落子 / 改选。 */
    public String select(Player player) {
        if (phase != Phase.PLAY) {
            return null;
        }
        UUID id = player.getUniqueId();
        if (!seats.contains(id)) {
            return "§c你不是本局玩家。";
        }
        int side = seatColor(id);
        if (side != turn) {
            return "§e请等待对手行棋。";
        }
        int cell = aimCell(player);
        if (cell == 0) {
            return "§c请将准星对准棋盘上的格子。";
        }
        int selCell = side == SIDE_RED ? selR : selB;
        int slot = slotAt(cell);
        if (selCell == 0) {
            if (slot < 0 || slot / 25 != side) {
                return "§c请先选择己方棋子。";
            }
            if (!JunqiRules.canMoveFrom(flat(), cell)) {
                return "§c" + JunqiRules.TYPE_CN[JunqiRules.SIDE_TYPES[slot % 25]] + "不能移动。";
            }
            setSel(side, cell);
            pushMarkers();
            return null;
        }
        if (slot >= 0 && slot / 25 == side) {
            // 点己方另一枚：改选；点回自己：取消
            if (selCell == cell) {
                setSel(side, 0);
            } else if (JunqiRules.canMoveFrom(flat(), cell)) {
                setSel(side, cell);
            } else {
                return "§c" + JunqiRules.TYPE_CN[JunqiRules.SIDE_TYPES[slot % 25]] + "不能移动。";
            }
            pushMarkers();
            return null;
        }
        if (!JunqiRules.isLegal(flat(), selCell, cell)) {
            return "§c这枚棋子走不到那里。";
        }
        return applyMove(side, selCell, cell);
    }

    /** 落子 / 吃子核心。 */
    private String applyMove(int side, int fromCell, int toCell) {
        int slot = slotAt(fromCell);
        int attackerType = JunqiRules.SIDE_TYPES[slot % 25];
        int defenderSlot = slotAt(toCell);
        if (defenderSlot >= 0 && JunqiRules.sideOf(grid[toCell - 1]) == side) {
            return "§c不能吃自己的棋子。";
        }
        boolean capture = defenderSlot >= 0;
        int defenderType = capture ? JunqiRules.typeOf(grid[toCell - 1]) : -1;
        int result = capture ? JunqiRules.fight(attackerType, defenderType) : JunqiRules.FIGHT_WIN;

        // 上一手标记
        from = fromCell;
        last = toCell;
        lastSide = side;
        setSel(side, 0);

        boolean flagCaptured = false;
        switch (result) {
            case JunqiRules.FIGHT_WIN -> {
                if (capture) {
                    addToTray(defenderSlot);     // 被吃的子进吃子方托盘
                    removeSlot(defenderSlot);
                }
                moveSlot(slot, toCell);
                if (capture) {
                    slotRevealed[slot] = true;   // 只有交过手才永久亮明；普通移动不暴露
                }
            }
            case JunqiRules.FIGHT_BOTH -> {
                addToTray(slot);                 // 同归于尽：双方各进对方托盘
                removeSlot(slot);
                if (capture) {
                    addToTray(defenderSlot);
                    removeSlot(defenderSlot);
                }
            }
            case JunqiRules.FIGHT_LOSE -> {
                addToTray(slot);                 // 攻方被吃 → 进守方托盘
                removeSlot(slot);
                if (capture) {
                    slotRevealed[defenderSlot] = true;   // 守方吃子 → 亮明
                }
            }
            case JunqiRules.FIGHT_FLAG -> {
                addToTray(defenderSlot);         // 军旗被吃 → 进攻方托盘
                removeSlot(defenderSlot);
                moveSlot(slot, toCell);
                flagCaptured = true;
            }
            default -> {
            }
        }
        quietMoves = capture ? 0 : quietMoves + 1;
        countPosition();
        pushAll();
        manager.markDirty();
        playSound(capture);

        if (flagCaptured) {
            finish(side);
            return null;
        }
        turn = 1 - side;
        pushTurn();
        if (!hasAnyPiece(turn) || !hasLegalMove(turn)) {
            finish(side);
            return null;
        }
        if (maxQuietMoves > 0 && quietMoves >= maxQuietMoves) {
            finishDraw();
            return null;
        }
        if (positionCounts.getOrDefault(positionKey(), 0) >= 3) {
            finishDraw();
            return null;
        }
        // 换手提示
        UUID mover = seatOfColor(side);
        UUID opponent = otherSeat(mover);
        String verb = capture ? "吃！" : "落子成功，";
        tipTo(mover, "§c" + verb + "轮到对手行棋");
        tipTo(opponent, "§c" + (capture ? "被吃！" : "对手落子完成，") + "轮到你行棋");
        if (botLevel > 0 && turn == botSeat) {
            manager.scheduleBotMove(this);
        }
        return null;
    }

    /** 对局中投降：对手直接获胜（赌博按正常结算）。 */
    public String surrender(Player player) {
        if (phase != Phase.PLAY) {
            return "当前不在对局中。";
        }
        UUID id = player.getUniqueId();
        int side = seatColor(id);
        if (side < 0) {
            return "你不在座位上。";
        }
        String loser = nameOf(id);
        finish(1 - side);
        broadcastTip("§c" + loser + " 投降，" + nameOf(winner) + " 获胜。");
        return null;
    }

    private void finish(int winSide) {
        phase = Phase.FINISHED;
        winner = isBotSide(winSide) ? AI_ID : seatOfColor(winSide);
        draw = false;
        settleWin(winSide);
        manager.markDirty();
        pushAll();
        sendViewers(true);
        String msg = "§6" + nameOf(winner) + " 在军旗对战中获胜！";
        Server.getInstance().broadcastMessage(msg);
        for (Player p : Server.getInstance().getOnlinePlayers().values()) {
            p.sendTip(msg);
        }
    }

    private void finishDraw() {
        phase = Phase.FINISHED;
        winner = null;
        draw = true;
        boolean refunded = refundStakes();
        manager.markDirty();
        pushAll();
        sendViewers(true);
        broadcastTip(refunded ? "§e本局和棋，下注金额已退还。" : "§e本局和棋。");
    }

    // ==================== 规则辅助 ====================

    /** 盘面压平成 {@code int[60]}（规则层用）。 */
    public int[] flat() {
        return grid.clone();
    }

    private boolean hasAnyPiece(int side) {
        for (int slot = side * 25; slot < side * 25 + 25; slot++) {
            if (slotCell[slot] != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLegalMove(int side) {
        int[] buf = new int[CELLS];
        int[] snapshot = flat();
        for (int cell = 1; cell <= CELLS; cell++) {
            int slot = slotAt(cell);
            if (slot < 0 || slot / 25 != side) {
                continue;
            }
            if (JunqiRules.generate(snapshot, cell, buf) > 0) {
                return true;
            }
        }
        return false;
    }

    private String positionKey() {
        StringBuilder sb = new StringBuilder(CELLS + 2);
        sb.append(turn).append('|');
        for (int i = 0; i < CELLS; i++) {
            int code = grid[i];
            sb.append(code < 0 ? '.' : Character.forDigit(code, 36));
        }
        return sb.toString();
    }

    private void countPosition() {
        positionCounts.merge(positionKey(), 1, Integer::sum);
    }

    // ==================== 存档 ====================

    /** 导出：每方 25 条 {cell, piece, revealed}（只写影响棋盘的字段）。 */
    public List<Map<String, Object>> exportSide(int side) {
        List<Map<String, Object>> out = new ArrayList<>(25);
        for (int i = 0; i < 25; i++) {
            int slot = side * 25 + i;
            if (slotCell[slot] == 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cell", slotCell[slot]);
            row.put("piece", JunqiRules.TYPE_KEYS[JunqiRules.SIDE_TYPES[i]]);
            if (slotRevealed[slot]) {
                row.put("revealed", true);
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 导出某一方托盘：按托盘格 0..23 的顺序列出槽号（0..24，本方视角的槽位下标）。
     *
     * <p>槽号本身就能还原棋子类型（{@link JunqiRules#SIDE_TYPES}），因此只存槽号即可。</p>
     */
    public List<Integer> exportTray(int side) {
        int[] order = new int[TRAY_CELLS];
        java.util.Arrays.fill(order, -1);
        for (int i = 0; i < 25; i++) {
            int slot = (1 - side) * 25 + i;
            int index = slotTray[slot];
            if (index >= 0 && index < TRAY_CELLS) {
                order[index] = i;
            }
        }
        List<Integer> out = new ArrayList<>(TRAY_CELLS);
        for (int value : order) {
            if (value >= 0) {
                out.add(value);
            }
        }
        return out;
    }

    /** 读档恢复某一方托盘（{@code indexes} 为槽号 0..24，按托盘格顺序）。 */
    public void restoreTray(int side, List<Integer> indexes) {
        clearTray(side);
        for (int position = 0; position < indexes.size() && position < TRAY_CELLS; position++) {
            int local = indexes.get(position);
            if (local < 0 || local >= 25) {
                continue;
            }
            int slot = (1 - side) * 25 + local;
            if (slotTray[slot] >= 0) {
                continue;
            }
            slotTray[slot] = trayCount[side];
            trayCount[side]++;
        }
    }

    /** 某方托盘已放数量（0 = 红方托盘存黑子，1 = 黑方托盘存红子）。 */
    public int getTrayCount(int side) {
        return trayCount[side];
    }

    public String exportTurn() {
        return turn == SIDE_BLACK ? "black" : "red";
    }

    public String exportPhase() {
        return switch (phase) {
            case READY -> "ready";
            case SETUP -> "setup";
            case PLAY -> "playing";
            case FINISHED -> "finished";
        };
    }

    public String exportWinner() {
        if (draw) {
            return "draw";
        }
        if (winner == null) {
            return "none";
        }
        int side = seatColor(winner);
        return side == SIDE_BLACK ? "black" : "red";
    }

    public int getLast() {
        return last;
    }

    public int getFrom() {
        return from;
    }

    public String exportLastSide() {
        return lastSide == SIDE_BLACK ? "black" : "red";
    }

    public int getQuietMoves() {
        return quietMoves;
    }

    /**
     * 从存档恢复。
     *
     * @param sideRows  每方 25 条（cell/piece/revealed）
     * @param savedTurn red/black
     * @param savedPhase setup/playing/finished
     */
    public void importState(int[] sideRows, String[] pieces, boolean[] revealed,
                            String savedTurn, String savedPhase, String savedWinner,
                            int savedQuiet, int savedFrom, int savedLast, String savedLastSide,
                            boolean redOk, boolean blackOk, boolean anyStarted) {
        java.util.Arrays.fill(grid, -1);
        java.util.Arrays.fill(pieceAt, 0);
        java.util.Arrays.fill(slotCell, 0);
        java.util.Arrays.fill(slotRevealed, false);
        java.util.Arrays.fill(slotTray, -1);
        java.util.Arrays.fill(trayCount, 0);
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < 25; i++) {
                int index = side * 25 + i;
                int type = JunqiRules.typeOfKey(pieces[index]);
                int cell = sideRows[index];
                if (type < 0 || JunqiRules.SIDE_TYPES[i] != type) {
                    // 类型与槽位不符（存档被改过）：按槽位类型放，忽略被改的那条
                    type = JunqiRules.SIDE_TYPES[i];
                }
                if (cell >= 1 && cell <= CELLS && grid[cell - 1] == -1) {
                    placeSlot(index, cell);
                    slotRevealed[index] = revealed[index];
                }
            }
        }
        turn = "black".equals(savedTurn) ? SIDE_BLACK : SIDE_RED;
        redConfirmed = redOk;
        blackConfirmed = blackOk;
        started = anyStarted;
        quietMoves = Math.max(0, savedQuiet);
        from = savedFrom;
        last = savedLast;
        lastSide = "black".equals(savedLastSide) ? SIDE_BLACK : SIDE_RED;
        if ("finished".equals(savedPhase)) {
            phase = Phase.FINISHED;
            if ("draw".equals(savedWinner)) {
                draw = true;
                winner = null;
            } else if ("red".equals(savedWinner) || "black".equals(savedWinner)) {
                winner = seatOfColor("black".equals(savedWinner) ? SIDE_BLACK : SIDE_RED);
            }
        } else if ("playing".equals(savedPhase)) {
            phase = Phase.PLAY;
        } else if ("ready".equals(savedPhase)) {
            phase = Phase.READY;
        } else {
            phase = Phase.SETUP;
        }
        // 座位是人运行时状态：读档时若还没恢复座位（管理器会紧接着补），先不下结论
        if (seats.size() < 2 && phase == Phase.PLAY) {
            phase = Phase.READY;
        }
        positionCounts.clear();
        countPosition();
    }

    // ==================== 渲染 ====================

    /** 按槽位重建实体属性（棋盘槽值 = cell | 亮明位；淘汰槽值 = 128 + 托盘 index）。 */
    private void pushSlots(JunqiPieceEntity piece) {
        int[] cells = new int[JunqiPieceEntity.SLOTS];
        for (int slot = 0; slot < JunqiPieceEntity.SLOTS; slot++) {
            int cell = slotCell[slot];
            if (cell > 0) {
                cells[slot] = cell | (slotRevealed[slot] ? JunqiPieceEntity.REVEAL_BIT : 0);
            } else if (slotTray[slot] >= 0) {
                cells[slot] = JunqiPieceEntity.TRAY_BASE + slotTray[slot];
            }
        }
        piece.setSlots(cells);
    }

    private void pushMarkers(JunqiPieceEntity piece) {
        piece.setMarkers(selR, curR, from, last, selB, curB);
        piece.setTurn(turn);
    }

    public void pushAll() {
        if (entity == null || entity.isClosed()) {
            return;
        }
        entity.beginBatch();
        pushSlots(entity);
        pushMarkers(entity);
        entity.endBatch();
    }

    public void pushMarkers() {
        if (entity == null || entity.isClosed()) {
            return;
        }
        entity.beginBatch();
        pushMarkers(entity);
        entity.endBatch();
    }

    /** 只推 turn（换手时用）。 */
    private void pushTurn() {
        if (entity == null || entity.isClosed()) {
            return;
        }
        entity.setTurn(turn);
    }

    public void spawnPieces() {
        if (entity != null && !entity.isClosed()) {
            return;
        }
        Entity created = Entity.createEntity(JunqiPieceEntity.IDENTIFIER,
                cn.nukkit.level.Position.fromObject(center, level));
        if (!(created instanceof JunqiPieceEntity piece)) {
            manager.getPlugin().getLogger().warning(
                    "军旗棋子实体创建失败：identifier=" + JunqiPieceEntity.IDENTIFIER + " center=" + center);
            return;
        }
        entity = piece;
        piece.setBoard(this);
        piece.setRotation(rotation * 90.0, 0.0);
        piece.beginBatch();
        pushSlots(piece);
        pushMarkers(piece);
        piece.endBatch();
        piece.spawnToAll();
        if (piece.hasPropertyWriteFailed()) {
            manager.getPlugin().getLogger().warning(
                    "军旗棋子实体属性未初始化：tabletopgame:junqi_piece 的属性未注册成功，客户端会看不到棋子");
        } else {
            manager.getPlugin().getLogger().info("军旗棋子实体已生成：id=" + piece.getId()
                    + " @" + center.x + "," + center.y + "," + center.z
                    + " 红/黑已摆=" + placedCount(SIDE_RED) + "/" + placedCount(SIDE_BLACK));
        }
        sendViewers(true);
    }

    public boolean isPiecesMissing() {
        return entity == null || entity.isClosed();
    }

    /**
     * 给半径内每个玩家重发「观看者身份」动画（红 / 黑 / 旁观）。
     *
     * @param force true = 无论是否发过都重发（入座、换手、新玩家进半径时用）
     */
    public void sendViewers(boolean force) {
        if (entity == null || entity.isClosed()) {
            return;
        }
        // 只有摆棋/对局阶段才把「玩家身份」发给客户端；READY/FINISHED 一律按旁观者处理，
        // 这样残局在「继承」之前对所有人都是空的，旁观者也永远看不到棋子（防出老千）。
        boolean active = phase == Phase.SETUP || phase == Phase.PLAY;
        List<Player> red = new ArrayList<>();
        List<Player> redFlip = new ArrayList<>();
        List<Player> black = new ArrayList<>();
        List<Player> blackFlip = new ArrayList<>();
        List<Player> spec = new ArrayList<>();
        for (Player p : level.getPlayers().values()) {
            if (!inRange(p)) {
                continue;
            }
            int side = active ? seatColor(p.getUniqueId()) : -1;
            boolean flip = side >= 0 && manager.flipEnemies(p.getUniqueId());
            if (side == SIDE_RED) {
                (flip ? redFlip : red).add(p);
            } else if (side == SIDE_BLACK) {
                (flip ? blackFlip : black).add(p);
            } else {
                spec.add(p);
            }
        }
        if (!red.isEmpty()) {
            entity.sendViewerAnimation(red, 0, false);
        }
        if (!redFlip.isEmpty()) {
            entity.sendViewerAnimation(redFlip, 0, true);
        }
        if (!black.isEmpty()) {
            entity.sendViewerAnimation(black, 1, false);
        }
        if (!blackFlip.isEmpty()) {
            entity.sendViewerAnimation(blackFlip, 1, true);
        }
        if (!spec.isEmpty()) {
            entity.sendViewerAnimation(spec, 2, false);
        }
    }

    private void playSound(boolean capture) {
        String sound = capture ? "tabletopgame.xiangqi.capture" : "tabletopgame.xiangqi.move";
        List<Player> listeners = new ArrayList<>();
        for (Player p : level.getPlayers().values()) {
            if (inRange(p)) {
                listeners.add(p);
            }
        }
        if (!listeners.isEmpty()) {
            level.addSound(center, sound, listeners.toArray(new Player[0]));
        }
    }

    // ==================== 范围 / 生命周期 ====================

    public boolean inRange(Player player) {
        if (player.getLevel() != this.level) {
            return false;
        }
        double range = manager.playerRange();
        return Math.abs(player.x - center.x) <= range
                && Math.abs(player.z - center.z) <= range
                && Math.abs(player.y - center.y) <= 12.0;
    }

    /** 是否有玩家在半径内（棋子缺失时用来决定要不要重建实体）。 */
    public boolean hasPlayerInRange() {
        for (Player p : level.getPlayers().values()) {
            if (inRange(p)) {
                return true;
            }
        }
        return false;
    }

    public void collectLeftPlayers(List<UUID> out) {
        out.clear();
        for (UUID id : seats) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null && !inRange(p)) {
                out.add(id);
            }
        }
    }

    public void remove() {
        refundStakes();
        seats.clear();
        seatColors.clear();
        knownNames.clear();
        positionCounts.clear();
        if (entity != null && !entity.isClosed()) {
            entity.close();
        }
        entity = null;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3 pos = new Vector3(
                        baseX + i * AXIS_PX[rotation] + j * AXIS_QX[rotation],
                        baseY,
                        baseZ + i * AXIS_PZ[rotation] + j * AXIS_QZ[rotation]);
                if (level.getBlock(pos) instanceof BaseJunqiBoardBlock) {
                    level.setBlock(pos, cn.nukkit.block.Block.get(cn.nukkit.block.Block.AIR), true);
                }
            }
        }
    }

    /** 该坐标是否属于本棋盘（3×3 区域）。 */
    public boolean contains(int x, int y, int z) {
        if (y != baseY) {
            return false;
        }
        int dx = x - baseX;
        int dz = z - baseZ;
        int u = dx * AXIS_PX[rotation] + dz * AXIS_PZ[rotation];
        int v = dx * AXIS_QX[rotation] + dz * AXIS_QZ[rotation];
        return u >= 0 && u < 3 && v >= 0 && v < 3;
    }

    public static JunqiBoardBlock newBlock(int position) {
        JunqiBoardBlock block = new JunqiBoardBlock();
        block.setPosition(position);
        return block;
    }

    /** 某方在某格的棋子类型；不是该方的子返回 -1（「我的布阵」表单用）。 */
    public int typeAt(int side, int cell) {
        int code = grid[cell - 1];
        if (code < 0 || JunqiRules.sideOf(code) != side) {
            return -1;
        }
        return JunqiRules.typeOf(code);
    }

    /** 某方的座位 UUID（管理器 tick 里查行棋方用）。 */
    public UUID seatOfColorPublic(int side) {
        return seatOfColor(side);
    }

    // ==================== 菜单辅助 ====================
    public void reopenMenu(Player player) {
        manager.reopenMenu(player, this);
    }

    public void later(Runnable action) {
        manager.runLater(action);
    }

    public String statusText(Player viewer) {
        StringBuilder sb = new StringBuilder();
        sb.append("§7座位：§f");
        if (seats.isEmpty() && !hasBot()) {
            sb.append("空");
        } else {
            for (UUID id : seats) {
                int color = seatColor(id);
                sb.append(color == SIDE_RED ? "§c（红）§f" : color == SIDE_BLACK ? "§0（黑）§f" : "§e（待选）§f")
                        .append(nameOf(id)).append("  ");
            }
            if (hasBot()) {
                sb.append(botSeat == SIDE_RED ? "§c（红）§f" : "§0（黑）§f").append(aiName());
            }
        }
        sb.append("\n§7状态：§f");
        switch (phase) {
            case PLAY -> sb.append("对局中，轮到")
                    .append(turn == SIDE_RED ? "§c红方" : "§0黑方").append("§7。");
            case SETUP -> {
                int mySide = viewer == null ? -1 : seatColor(viewer.getUniqueId());
                if (mySide < 0) {
                    sb.append("摆棋中。");
                } else if (isConfirmed(mySide)) {
                    sb.append("摆棋中（你已确认，等待对手）。");
                } else {
                    sb.append("摆棋中（已自动随机布阵，待你确认）。");
                }
            }
            case FINISHED -> sb.append(draw ? "已结束，和棋。"
                    : winner != null ? "已结束，" + nameOf(winner) + " 获胜。" : "已结束。");
            default -> sb.append("未开始。");
        }
        if (hasBot()) {
            sb.append("\n§7机器人：§f").append(aiName())
                    .append("（").append(colorName(botSeat)).append("）");
        }
        if (!seats.isEmpty() || hasBot()) {
            sb.append("\n\n§e提示：可重新打开界面刷新当前状态");
        } else {
            sb.append("\n\n§7提示：可加入他人对局，或直接选择「单人对战」挑战机器人。");
        }
        return sb.toString();
    }

    public String nameOfSeat(UUID id) {
        return nameOf(id);
    }

    private String nameOf(UUID id) {
        if (id == null) {
            return "对手";
        }
        if (AI_ID.equals(id) && hasBot()) {
            return aiName();
        }
        Player p = Server.getInstance().getPlayer(id).orElse(null);
        if (p != null) {
            return p.getName();
        }
        String known = knownNames.get(id);
        return known != null ? known : id.toString().substring(0, 8);
    }

    private UUID seatOfColor(int color) {
        for (Map.Entry<UUID, Integer> entry : seatColors.entrySet()) {
            if (entry.getValue() == color) {
                return entry.getKey();
            }
        }
        return null;
    }

    private UUID otherSeat(UUID seat) {
        for (UUID id : seats) {
            if (!id.equals(seat)) {
                return id;
            }
        }
        return null;
    }

    // ==================== 机器人 ====================
    public int getBotLevel() {
        return botLevel;
    }

    public int getBotSeat() {
        return botSeat;
    }

    public boolean hasBot() {
        return botLevel > 0 && botSeat >= 0;
    }

    public boolean isBotSide(int side) {
        return hasBot() && botSeat == side;
    }

    public boolean isBotTurn() {
        return phase == Phase.PLAY && hasBot() && turn == botSeat;
    }

    public boolean isBotThinking() {
        return botThinking;
    }

    public void setBotThinking(boolean value) {
        this.botThinking = value;
    }

    /** 机器人显示名（仅在 {@link #hasBot()} 为 true 时有效）。 */
    public String aiName() {
        return JunqiAi.levelName(botLevel - 1) + "机器人";
    }

    /**
     * 选择单人对战：已选色则机器人立刻坐到对面；未选色则先占一个座位，等玩家选色。
     *
     * <p>与象棋/斗兽棋一致：玩家点完人机按钮后立即入座，菜单切成「选择执红方/执黑方」。</p>
     */
    public boolean chooseAi(Player player, int level) {
        if (phase != Phase.READY && phase != Phase.FINISHED) {
            return false;
        }
        if (level < 1 || level > 3) {
            return false;
        }
        UUID id = player.getUniqueId();
        boolean seated = seats.contains(id);
        if (seated ? seats.size() > 1 : seats.size() >= 2) {
            tipTo(id, "§c座位已满，无法开始单人对战。");
            return false;
        }
        if (hasBot()) {
            tipTo(id, "§c已经在单人对战中。");
            return false;
        }
        int myColor = seatColor(id);
        if (myColor != -1) {
            // 已有棋色：机器人直接坐到对面
            botLevel = level;
            botSeat = 1 - myColor;
            botThinking = false;
            pendingBotLevel = 0;
            pendingBotSeat = null;
            manager.markDirty();
            tipTo(id, "§a对手已就位：" + aiName() + "（你执" + colorName(myColor) + "）。");
            manager.refreshSeatMenus(this);
            sendViewers(true);
            return true;
        }
        if (!seated) {
            seats.add(id);
        }
        knownNames.put(id, player.getName());
        pendingBotLevel = level;
        pendingBotSeat = id;
        releaseMenu(id);
        manager.markDirty();
        tipTo(id, "§a已选择" + JunqiAi.levelName(level - 1) + "机器人，请选择你的棋色。");
        manager.refreshSeatMenus(this);
        return true;
    }

    /** 已选单人对战、等待选色的玩家选择棋色。 */
    public boolean chooseAiColor(Player player, int color) {
        if (phase != Phase.READY && phase != Phase.FINISHED) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (pendingBotLevel <= 0 || pendingBotSeat == null || !pendingBotSeat.equals(id)) {
            return false;
        }
        if (color != SIDE_RED && color != SIDE_BLACK) {
            return false;
        }
        if (seatColors.containsValue(color)) {
            tipTo(id, "§c该棋色已被选择。");
            return false;
        }
        seatColors.put(id, color);
        knownNames.put(id, player.getName());
        botLevel = pendingBotLevel;
        botSeat = 1 - color;
        pendingBotLevel = 0;
        pendingBotSeat = null;
        releaseMenu(id);
        manager.markDirty();
        tipTo(id, "§a对手已就位：" + aiName() + "（你执" + colorName(color) + "）。");
        manager.refreshSeatMenus(this);
        sendViewers(true);
        return true;
    }

    public boolean isPendingAiSeat(UUID id) {
        return pendingBotLevel > 0 && pendingBotSeat != null && pendingBotSeat.equals(id);
    }

    public boolean hasPendingAi() {
        return pendingBotLevel > 0;
    }

    /** 退出单人对战：只移除机器人座位，玩家保持在座。 */
    public boolean exitAiBattle() {
        if (phase == Phase.PLAY || phase == Phase.SETUP || !hasBot()) {
            return false;
        }
        botLevel = 0;
        botSeat = -1;
        botThinking = false;
        manager.markDirty();
        sendViewers(true);
        return true;
    }

    /**
     * 机器人视角的盘面：自己人 + 已亮明的敌子是真类型，其余敌子是 {@link JunqiAi#UNKNOWN}。
     */
    public int[] believedGrid(int side) {
        int[] out = flat();
        for (int cell = 1; cell <= CELLS; cell++) {
            int code = out[cell - 1];
            if (code < 0 || JunqiRules.sideOf(code) == side) {
                continue;
            }
            int slot = slotAt(cell);
            if (slot >= 0 && slotRevealed[slot]) {
                continue;
            }
            out[cell - 1] = JunqiRules.code(JunqiRules.sideOf(code), JunqiAi.UNKNOWN);
        }
        return out;
    }

    /** 敌方「还没亮明」的各类型数量（下标 = 类型码 0..11）。 */
    public int[] unseenCounts(int side) {
        int[] counts = new int[12];
        int other = 1 - side;
        for (int i = 0; i < 25; i++) {
            int slot = other * 25 + i;
            if (slotCell[slot] != 0 && !slotRevealed[slot]) {
                counts[JunqiRules.SIDE_TYPES[i]]++;
            }
        }
        return counts;
    }

    /** 机器人落子（主线程调用，move 由 {@link JunqiAi#chooseMove} 算出）。 */
    public void applyBotMove(int side, int move) {
        if (phase != Phase.PLAY || !hasBot() || turn != botSeat || side != botSeat) {
            return;
        }
        if (move < 0) {
            finish(1 - side);
            return;
        }
        applyMove(side, JunqiAi.from(move), JunqiAi.to(move));
    }

    private void broadcastTip(String msg) {
        for (UUID id : seats) {
            tipTo(id, msg);
        }
    }

    private void tipTo(UUID id, String msg) {
        if (id == null) {
            return;
        }
        Player p = Server.getInstance().getPlayer(id).orElse(null);
        if (p != null) {
            p.sendTip(msg);
        }
    }
}
