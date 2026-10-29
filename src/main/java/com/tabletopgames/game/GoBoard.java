package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import com.tabletopgames.board.GoBoardBlock;
import com.tabletopgames.board.GoBoard13Block;
import com.tabletopgames.board.GoBoard19BigBlock;
import com.tabletopgames.board.GoBoard19Block;
import com.tabletopgames.board.GoBoard9Block;
import com.tabletopgames.economy.EconomyHook;
import com.tabletopgames.item.LocatorService;
import com.tabletopgames.entity.GoStoneEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个已放置的围棋棋盘实例：管理规则（气 / 提子 / 禁着点 / 单劫）、整盘渲染、对局流程与存档。
 * <p>
 * <b>渲染</b>：整盘棋子只由两个实体绘制（黑子实体 + 白子实体），二者共用同一份 361 骨骼几何体，
 * 靠贴图与动画区分颜色。棋盘状态以三进制打包进 {@code tabletopgame:g0..g24}（见 {@link GoStoneEntity}），
 * 9 路 / 13 路复用同一份几何体，由 {@code grid} 属性切换落点间距、{@code size} 属性切换整体缩放。
 * <p>
 * <b>规则</b>：黑先手交替落子于交叉点；无气的对方棋组立即提走；落子后自身无气且未提子为禁着点；
 * 单劫禁止立即回提（须先在别处走一手）。双方连续 Pass 两次自动数子结算（中国规则数子，黑贴 3¾ 子 = 7.5 目）。
 * 让子对局先摆 N 颗黑子，之后白方先行、不贴目。
 */
public class GoBoard {

    /** 空格。 */
    public static final int EMPTY = 0;
    /** 黑棋（正常对局的先手）。 */
    public static final int BLACK = 1;
    /** 白棋。 */
    public static final int WHITE = 2;

    /** 棋盘方块顶面高度（世界单位）：模型立方体从 y=0 铺到 y=2 模型单位。 */
    public static final double BOARD_TOP = 2.0 / 16.0;

    // 旧「GRID_RATIO = 0.9 统一系数」已废弃：各路数贴图线距不同，
    // 2026-10-16 对拍后 gridSpan() 直接按实测线距查表（见该方法注释）。

    /** 默认贴目（中国规则：黑贴 3¾ 子 = 7.5 目）。 */
    public static final double DEFAULT_KOMI = 7.5;

    /** 让子模式允许的让子数区间。 */
    public static final int MIN_HANDICAP = 2;
    public static final int MAX_HANDICAP = 9;

    private static final int[] DR = {-1, 1, 0, 0};
    private static final int[] DC = {0, 0, -1, 1};

    /** 3 的幂：{@code POW3[i] = 3^i}。 */
    private static final int[] POW3 = new int[16];

    static {
        POW3[0] = 1;
        for (int i = 1; i < POW3.length; i++) {
            POW3[i] = POW3[i - 1] * 3;
        }
    }

    /**
     * 同一局面（棋盘 + 轮次）出现达到该次数即判和，用于处理单劫规则无法拦截的循环：
     * 三劫循环、四劫循环、长生（长生劫）、双提二子等超劫（superko）情形。
     */
    private static final int REPEAT_LIMIT = 3;

    /** Zobrist 随机数：{@code [r*19+c][颜色-1]}，索引与棋盘路数无关，便于统一生成局面哈希。 */
    private static final long[][] ZOBRIST = new long[19 * 19][2];
    /** 轮次分量：{@code [BLACK]} / {@code [WHITE]}。 */
    private static final long[] SIDE_KEY = new long[3];

    static {
        java.util.Random rnd = new java.util.Random(0x9E3779B97F4A7C15L);
        for (int i = 0; i < ZOBRIST.length; i++) {
            ZOBRIST[i][0] = rnd.nextLong();
            ZOBRIST[i][1] = rnd.nextLong();
        }
        SIDE_KEY[BLACK] = rnd.nextLong();
        SIDE_KEY[WHITE] = rnd.nextLong();
    }

    private final GoManager manager;
    private final Level level;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    /** 棋盘朝向：0=南、1=西、2=北、3=东。 */
    private final int rotation;

    /** 路数：9 / 13 / 19。 */
    private final int n;
    /** 棋盘方块边长：2 / 3 / 4。 */
    private final int boardSize;
    /** 中心到边界骨骼编号的偏移：{@code (19 - n) / 2}。 */
    private final int offset;

    /** 棋盘中心（构造时算好并复用）。只读。 */
    private final Vector3 center;

    /** 当前棋局：{@code [row][col]}，值为 0 空 / 1 黑 / 2 白。 */
    private final int[][] grid;

    /** 渲染整盘棋子的单实体（每格黑白两根骨，按盘面三进制状态互斥显示）。 */
    private GoStoneEntity stoneEntity;

    // ==================== 对局状态 ====================

    private final List<UUID> seats = new ArrayList<>(2);
    private final Map<UUID, Integer> seatColors = new HashMap<>(2);
    private final Map<UUID, String> knownNames = new LinkedHashMap<>(4);

    private boolean running;
    /** 当前该行棋的一方。 */
    private int turn = BLACK;
    /** 上一手落点（骨骼编号 + 1，0 = 无）。 */
    private int lastAttr;
    /** 准星瞄准格（骨骼编号 + 1，0 = 不显示）。 */
    private int hoverAttr;
    /** 打劫禁着点（-1 表示无）。 */
    private int koRow = -1;
    private int koCol = -1;
    /**
     * 本局已出现过的局面（棋盘 + 轮次）计数，键为 Zobrist 哈希。
     * 单劫规则只能拦住「立即回提」，三劫循环 / 长生等循环需靠整盘局面重复来判和。
     */
    private final Map<Long, Integer> positionCounts = new HashMap<>(64);
    /** 连续 Pass 次数（落子后清零）。 */
    private int passes;
    /** 让子数（0 = 公平对局）。 */
    private int handicap;
    /** 贴目（让子对局为 0）。 */
    private double komi = DEFAULT_KOMI;
    /** 是否开过局（决定菜单是否显示「继承残局」）。 */
    private boolean started;
    private UUID winner;
    private boolean draw;

    /** 0 人入座时预设的「每局金额」（双方各自下注额；0 = 免费局，默认 0）。 */
    private double presetBet;
    private double betAmount;
    private final Map<UUID, Double> stakes = new HashMap<>(2);

    /** 机器人座位哨兵 UUID（全 0），不对应任何真实玩家。 */
    public static final UUID AI_ID = new UUID(0L, 0L);

    /** 大师档：只有「玩家战胜大师机器人」才值得全服播报（与其余三块棋盘同口径）。 */
    private static final int AI_LEVEL_MASTER = 3;

    /** 机器人棋色：0 表示本局无机器人。 */
    private int aiColor;
    /** 机器人难度：1 入门 / 2 进阶 / 3 大师。 */
    private int aiLevel;
    /** 已选择「单人对战」但尚未选色时暂存的机器人等级（0 表示无）。 */
    private int pendingAiLevel;
    /** 已选择「单人对战」但尚未选色的玩家。 */
    private UUID pendingAiSeat;
    /** 机器人是否正在思考，避免重复派发计算任务。 */
    private boolean aiThinking;

    /** 0 人入座时正在操作菜单的玩家。 */
    private UUID menuHolder;
    private long menuHolderUntil;

    // ==================== 规则计算缓冲 ====================

    private final boolean[][] visited;
    private final boolean[][] seen;
    private final ArrayDeque<int[]> stack = new ArrayDeque<>();
    private final List<int[]> groupBuf = new ArrayList<>();
    private final List<int[]> regionBuf = new ArrayList<>();

    public GoBoard(GoManager manager, Level level, int baseX, int baseY, int baseZ, int boardSize, int n, int rotation) {
        this.manager = manager;
        this.level = level;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.rotation = TabletopGamesBoard.normalizeRotation(rotation);
        this.boardSize = boardSize;
        this.n = n;
        this.offset = (19 - n) / 2;
        double half = (boardSize - 1) / 2.0;
        this.center = new Vector3(
                baseX + 0.5 + half * (TabletopGamesBoard.axisPX(this.rotation) + TabletopGamesBoard.axisQX(this.rotation)),
                baseY,
                baseZ + 0.5 + half * (TabletopGamesBoard.axisPZ(this.rotation) + TabletopGamesBoard.axisQZ(this.rotation)));
        this.grid = new int[n][n];
        this.visited = new boolean[n][n];
        this.seen = new boolean[n][n];
    }

    public Level getLevel() {
        return level;
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

    public int getN() {
        return n;
    }

    public int getBoardSize() {
        return boardSize;
    }

    public String getKey() {
        return level.getName() + ":" + baseX + ":" + baseY + ":" + baseZ;
    }

    public Vector3 getCenter() {
        return center;
    }

    public boolean isRunning() {
        return running;
    }

    public int getTurn() {
        return turn;
    }

    public boolean hasStarted() {
        return started;
    }

    public int getHandicap() {
        return handicap;
    }

    /** 连续 Pass 次数（存档用）。 */
    public int getPasses() {
        return passes;
    }

    public double getKomi() {
        return komi;
    }

    public UUID getWinner() {
        return winner;
    }

    public boolean isDraw() {
        return draw;
    }

    public boolean isFinished() {
        return !running && (winner != null || draw);
    }

    /** 该交点是否已有棋子。 */
    public boolean isOccupied(int row, int col) {
        return row >= 0 && row < n && col >= 0 && col < n && grid[row][col] != EMPTY;
    }

    public static String colorName(int color) {
        if (color == BLACK) {
            return "黑方";
        }
        if (color == WHITE) {
            return "白方";
        }
        return "";
    }

    private static int other(int color) {
        return color == BLACK ? WHITE : BLACK;
    }

    // ==================== 座位 ====================

    public List<UUID> getSeats() {
        return seats;
    }

    public boolean isSeated(UUID uuid) {
        return seats.contains(uuid);
    }

    public int seatCount() {
        return seats.size();
    }

    public int seatColor(UUID uuid) {
        return seatColors.getOrDefault(uuid, -1);
    }

    public boolean isColorTaken(int color) {
        return seatColors.containsValue(color);
    }

    public boolean isFirstJoiner(UUID uuid) {
        return !seats.isEmpty() && seats.get(0).equals(uuid);
    }

    /** 除给定座位外的另一个座位。 */
    public UUID opponentOf(UUID seat) {
        for (UUID id : seats) {
            if (!id.equals(seat)) {
                return id;
            }
        }
        return null;
    }

    public boolean join(Player player, int color) {
        UUID id = player.getUniqueId();
        if (seats.contains(id)) {
            tipTo(id, "§e你已在座位上。");
            return false;
        }
        if (seats.size() >= 2 || pendingAiLevel > 0 || isAiGame()) {
            tipTo(id, "§c座位已满（最多 2 人）。");
            return false;
        }
        if (color != BLACK && color != WHITE) {
            return false;
        }
        if (seatColors.containsValue(color)) {
            tipTo(id, "§c该棋色已被选择。");
            return false;
        }
        // 经济门槛（2026-10-18 用户要求：与 UNO 统一，只在入座时检查一次）：
        // 预设金额 > 0 时必须能负担得起本局自己那份下注，否则拒绝入座；免费局（预设 0）不校验。
        // 开局时不再重复校验，靠扣款失败回滚兜底。
        if (presetBet > 0) {
            double bal = EconomyHook.balance(id);
            if (bal < presetBet) {
                tipTo(id, "§c本局金额为 " + formatMoney(presetBet)
                        + "，入座要求余额大于等于该金额（你当前 " + formatMoney(bal) + "）。");
                return false;
            }
        }
        seats.add(id);
        seatColors.put(id, color);
        knownNames.put(id, player.getName());
        releaseMenu(id);
        manager.markDirty();
        tipTo(id, "§a你已加入对局（" + colorName(color) + "）。");
        manager.refreshSeatMenus(this);
        LocatorService.give(player);
        return true;
    }

    public boolean leave(UUID uuid) {
        int i = seats.indexOf(uuid);
        if (i < 0) {
            return false;
        }
        // 赌博对局中途有人退出：不退注，直接判仍在座的一方获胜并结算
        boolean gamblingForfeit = running && !stakes.isEmpty();
        String leaverName = nameOf(uuid);
        seats.remove(i);
        seatColors.remove(uuid);
        releaseMenu(uuid);
        if (uuid.equals(pendingAiSeat)) {
            pendingAiLevel = 0;
            pendingAiSeat = null;
        }
        // 人机对战中玩家退出，机器人一并撤离，避免留下无主的机器人座位
        if (isAiGame()) {
            seats.remove(AI_ID);
            seatColors.remove(AI_ID);
            aiColor = 0;
            aiLevel = 0;
            aiThinking = false;
        }
        manager.markDirty();
        if (running) {
            if (gamblingForfeit && !seats.isEmpty()) {
                UUID winnerId = seats.get(0);
                winner = winnerId;
                draw = false;
                running = false;
                setHover(0);
                writeAll(false);
                settleWin(seatColor(winnerId),
                        "§c" + leaverName + " 中途退出，判定 " + nameOf(winnerId) + " 获胜！");
            } else {
                abort("有玩家退出，本局结束（可稍后「继承残局」继续）。");
            }
        }
        if (!seats.isEmpty()) {
            manager.refreshSeatMenus(this);
        }
        LocatorService.release(uuid);
        return true;
    }

    // ==================== 菜单占用 ====================

    public boolean tryAcquireMenu(Player player) {
        if (seats.size() > 0) {
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
        if (seats.size() > 0) {
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
        if (seats.size() > 0) {
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

    // ==================== 赌博模式 ====================

    /** 本局预设金额（双方各自下注额；0 = 免费局，未设置时为 0）。 */
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

    /** 本棋盘是否开放赌博模式：13 路 / 19 路对局耗时过长，仅 9 路支持。 */
    public boolean supportsGambling() {
        return n != 13 && n != 19;
    }

    /**
     * 预设「每局金额」：仅「0 人入座」且持有菜单占用权时可设，有人入座后锁定不可改。
     * <p>合法值 = {@code 0}（免费局，不扣款），或 {@code [最小金额, 最大金额]} 区间内、至多两位小数。
     * 金额 &gt; 0 时不再提供单人对战入口（见各棋盘菜单）。
     *
     * @return null 表示成功，否则为拒绝原因
     */
    public String setPresetBet(Player player, double amount) {
        if (seats.size() > 0) {
            return "有人入座后不能再改每局金额（需在 0 人入座时设置）。";
        }
        if (!holdsMenu(player)) {
            return "你没有该棋盘的菜单操作权，请重新点击棋盘。";
        }
        if (!supportsGambling()) {
            return "本棋盘不支持赌博（仅 9 路对局可下注）。";
        }
        if (amount < 0) {
            return "金额不能为负数。";
        }
        if (amount != 0) {
            double min = getMinBet();
            double max = getMaxBet();
            if (amount < min || amount > max) {
                return "金额只能是 0（免费局），或 " + formatMoney(min) + " ~ " + formatMoney(max) + " 之间。";
            }
        }
        presetBet = amount;
        manager.markDirty();
        player.sendTip("§7本局金额已设为 §e" + formatMoney(presetBet) + "§7"
                + (presetBet <= 0 ? "（免费局：不扣款）。" : "（开局时双方各扣除该金额）。"));
        return null;
    }

    /**
     * 按预设金额开局（赌博模式）：同时各扣该金额（余额已在入座时校验；扣款失败会回滚），最后开始对局（黑先行）。
     *
     * @param bet 本局双方各自的扣款金额（两位小数，须 &gt; 0）
     * @return 成功返回 null；失败返回可直接展示的原因（已含玩家名）
     */
    public String startGambling(double bet) {
        if (running || seats.size() < 2 || isAiGame() || !supportsGambling()) {
            return "当前无法开始赌博对局。";
        }
        if (bet <= 0) {
            return "下注金额必须大于 0。";
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
        start(0);
        for (UUID id : seats) {
            tipTo(id, "§6赌博模式：已从你的账户扣除下注 §e" + formatMoney(bet)
                    + "§6，本局奖池 §e" + formatMoney(bet * 2) + "§6。");
            if (EconomyHook.balance(id) <= 0) {
                tipTo(id, "§c你已破产：余额被扣至 0。下一局需先补充余额才能继续入座。");
            }
        }
        return null;
    }

    private void settleWin(int winColor, String reason) {
        if (stakes.isEmpty()) {
            return;
        }
        double cut = manager.config().getBetting().normalizedCut();
        double pot = betAmount * 2;
        double commission = round2(pot * cut / 100.0);
        double prize = round2(pot - commission);
        UUID winnerId = seatOfColor(winColor);
        if (winnerId != null && prize > 0) {
            EconomyHook.add(winnerId, prize);
        }
        stakes.clear();
        betAmount = 0;
        broadcastTip((reason == null ? "" : reason)
                + "§6赌博结算：奖池 §e" + formatMoney(pot) + "§6，官方抽水 §e"
                + formatMoney(commission) + "§6（" + cut + "%），赢家获得 §e"
                + formatMoney(prize) + "§6。");
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

    private static String formatMoney(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    // ==================== 对局流程 ====================

    /** 开始公平对局（不贴子、黑先手）。 */
    public void start() {
        start(0);
    }

    /**
     * 开始对局。
     *
     * @param handicapCount 让子数（0 = 公平对局，黑贴 7.5 目、黑先手；2~9 = 先摆相应数量黑子，白先手、不贴目）
     */
    public void start(int handicapCount) {
        if (running || seats.size() < 2) {
            return;
        }
        clearGrid();
        handicap = Math.max(0, Math.min(MAX_HANDICAP, handicapCount));
        if (handicap == 1) {
            handicap = 0;
        }
        komi = handicap >= MIN_HANDICAP ? 0.0 : DEFAULT_KOMI;
        if (handicap >= MIN_HANDICAP) {
            placeHandicap(handicap);
        }
        turn = handicap >= MIN_HANDICAP ? WHITE : BLACK;
        running = true;
        started = true;
        passes = 0;
        koRow = -1;
        koCol = -1;
        lastAttr = 0;
        hoverAttr = 0;
        winner = null;
        draw = false;
        resetPositionHistory();
        writeAll(true);
        manager.markDirty();
        manager.closeSeatMenus(this);
        if (handicap >= MIN_HANDICAP) {
            broadcastTip("§e围棋对局开始！黑方已摆 " + handicap + " 子，白方先行（不贴目）。");
        } else {
            broadcastTip("§e围棋对局开始！黑方先行（黑贴 3¾ 子 = 7.5 目）。");
        }
    }

    /** 继承残局：保留当前局面与轮次继续。 */
    public void inheritEndgame() {
        if (running || seats.size() < 2 || !started) {
            return;
        }
        running = true;
        winner = null;
        draw = false;
        passes = 0;
        resetPositionHistory();
        writeAll(true);
        manager.markDirty();
        manager.closeSeatMenus(this);
        broadcastTip("§e已继承残局，" + colorName(turn) + "继续落子。");
    }

    /** 中途终止（有人退出）：保留局面与轮次，便于之后「继承残局」。 */
    void abort(String reason) {
        running = false;
        setHover(0);
        writeAll(false);
        boolean refunded = refundStakes();
        manager.markDirty();
        broadcastTip("§c" + reason + (refunded ? "，下注金额已退还。" : ""));
    }

    // ==================== 落子 / Pass / 认输 ====================

    /**
     * 计算玩家准星命中的交叉点（1 基索引，0 表示未命中）。
     * <p>
     * 与资源包骨骼坐标严格对应：列轴 = P，行轴 = -Q（实体模型 Z 轴与世界 Z 轴方向相反）。
     */
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
        double planeY = center.y + BOARD_TOP;
        double t = (planeY - eyeY) / dir.y;
        if (t <= 0 || t > 12) {
            return 0;
        }
        double hitX = eyeX + dir.x * t;
        double hitZ = eyeZ + dir.z * t;
        double dxc = hitX - center.x;
        double dzc = hitZ - center.z;
        int p = rotation;
        double a = dxc * TabletopGamesBoard.axisPX(p) + dzc * TabletopGamesBoard.axisPZ(p);
        double b = -(dxc * TabletopGamesBoard.axisQX(p) + dzc * TabletopGamesBoard.axisQZ(p));
        double step = lineStep();
        int half = (n - 1) / 2;
        int col = (int) Math.round(a / step) + half;
        int row = (int) Math.round(b / step) + half;
        if (col < 0 || col >= n || row < 0 || row >= n) {
            return 0;
        }
        return row * n + col + 1;
    }

    /** 网格跨越长度（方块数）。 */
    private double gridSpan() {
        // 2026-10-16 网格对拍（以贴图实测线距为准，旧常量 0.9 会让边缘交点偏出 ±8px）：
        //   9 路：线距 26.875px（256px=2 格）→ span = 8×0.209961 = 1.6796875
        //   13 路：线距 18px → span = 12×0.140625 = 1.6875
        //   19 路：线距 19px（384px=3 或 4 格，128px/格）→ span = boardSize×0.890625
        if (n == 9) {
            return 1.6796875;
        }
        if (n == 13) {
            return 1.6875;
        }
        return boardSize * 0.890625;
    }

    /** 相邻交叉点的世界距离。 */
    private double lineStep() {
        return gridSpan() / (n - 1);
    }

    /** 本 tick 需要看到准星高亮的玩家：当前回合方（对局中且未结束）。 */
    public UUID hoverPlayerId() {
        if (!running) {
            return null;
        }
        return seatOfColor(turn);
    }

    public void updateHover(Player player) {
        UUID actor = hoverPlayerId();
        if (actor == null || !actor.equals(player.getUniqueId())) {
            return;
        }
        int cell = aimCell(player);
        if (cell == 0) {
            setHover(0);
            return;
        }
        // 黄框骨骼编号必须与棋子骨骼同源（boneK 含 (19-n)/2 的居中偏移），
        // 不能直接用 aimCell 的 n 路基索引——资源包里 hover_marker 是按 19 路解码的：
        //   position = (mod(v.hover-1,19)-9, 0, floor((v.hover-1)/19)-9)
        // 9 路时 aimCell 给的是 row*9+col+1，整块黄框会偏出 (offset,offset) 格。
        setHover(boneK((cell - 1) / n, (cell - 1) % n) + 1);
    }

    /** 写入准星瞄准格（骨骼编号 + 1，0 = 隐藏）。只更新黑子实体的该属性。 */
    private void setHover(int attr) {
        if (hoverAttr == attr) {
            return;
        }
        hoverAttr = attr;
        if (stoneEntity != null && !stoneEntity.isClosed()) {
            stoneEntity.setHover(attr);
        }
    }

    /**
     * 玩家在准星命中的交点落子。
     *
     * @return 需要提示给玩家的文案；成功或静默时返回 {@code null}
     */
    public String place(Player player) {
        if (!running) {
            return null;
        }
        UUID id = player.getUniqueId();
        if (!seats.contains(id)) {
            return "§c你不是本局玩家。";
        }
        if (seatColor(id) != turn) {
            return "§e请等待对手落子。";
        }
        int cell = aimCell(player);
        if (cell == 0) {
            return "§c请将准星对准棋盘上的交叉点。";
        }
        int row = (cell - 1) / n;
        int col = (cell - 1) % n;
        if (grid[row][col] != EMPTY) {
            return "§c该交点已有棋子。";
        }
        if (!isLegal(row, col, turn)) {
            return koRow == row && koCol == col ? "§c打劫：不能立即回提，请先在别处走一手。"
                    : "§c禁着点：落子后自身无气且无法提子。";
        }
        applyMove(row, col, turn);
        return null;
    }

    /** 停一手（Pass）：放弃本次落子权。 */
    public String pass(Player player) {
        if (!running) {
            return null;
        }
        UUID id = player.getUniqueId();
        if (!seats.contains(id)) {
            return "§c你不是本局玩家。";
        }
        if (seatColor(id) != turn) {
            return "§e请等待对手落子。";
        }
        performPass(turn);
        return null;
    }

    /** 停一手核心：双方连续两次停一手即数子结算。 */
    private void performPass(int color) {
        passes++;
        setHover(0);
        if (passes >= 2) {
            writeAll(false);
            settle();
            return;
        }
        turn = other(color);
        if (recordPosition()) {
            return;
        }
        writeAll(false);
        manager.markDirty();
        broadcastTip("§e" + colorName(color) + "停一手。");
        notifyTurn();
    }

    // ==================== 局面重复判和 ====================

    /** 当前局面的 Zobrist 哈希（棋盘 + 轮次）。 */
    private long positionKey() {
        long h = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int v = grid[r][c];
                if (v != EMPTY) {
                    h ^= ZOBRIST[r * 19 + c][v - 1];
                }
            }
        }
        return h ^ SIDE_KEY[turn];
    }

    /** 清空并重新记录当前局面（开局 / 继承残局 / 读档时调用）。 */
    private void resetPositionHistory() {
        positionCounts.clear();
        recordPosition();
    }

    /**
     * 记录一次当前局面；当同一局面（含轮次）出现次数达到 {@link #REPEAT_LIMIT} 时判和。
     * <p>
     * 单劫规则只能拦住「立即回提」，而三劫 / 四劫循环、长生（长生劫）、双提二子等
     * 循环中每一手单看都合法，只有整盘局面（含轮次）复现才能识别，故在每次落子 / 停一手后记录。
     *
     * @return true 表示已判和、对局结束
     */
    private boolean recordPosition() {
        int count = positionCounts.merge(positionKey(), 1, Integer::sum);
        if (count >= REPEAT_LIMIT) {
            declareRepetitionDraw();
            return true;
        }
        return false;
    }

    /** 因局面反复出现（三劫 / 四劫循环、长生、双提二子等）判和。 */
    private void declareRepetitionDraw() {
        running = false;
        setHover(0);
        writeAll(false);
        winner = null;
        draw = true;
        manager.markDirty();
        boolean refunded = refundStakes();
        broadcastTip("§e局面重复出现 " + REPEAT_LIMIT
                + " 次（三劫 / 四劫循环、长生、双提二子等），本局判和。"
                + (refunded ? "下注金额已退还。" : ""));
    }

    /** 认输。 */
    public String resign(Player player) {
        if (!running) {
            return null;
        }
        UUID id = player.getUniqueId();
        if (!seats.contains(id)) {
            return "§c你不是本局玩家。";
        }
        int loserColor = seatColor(id);
        int winColor = other(loserColor);
        running = false;
        setHover(0);
        writeAll(false);
        manager.markDirty();
        winner = seatOfColor(winColor);
        draw = false;
        broadcastTip("§e" + nameOf(id) + " 认输。");
        settleWin(winColor, null);
        announceWin(winColor);
        return null;
    }

    /** 接受对方求和：本局以和棋结束。 */
    public void acceptDraw() {
        if (!running) {
            return;
        }
        running = false;
        setHover(0);
        writeAll(false);
        winner = null;
        draw = true;
        manager.markDirty();
        broadcastTip(refundStakes() ? "§e双方同意和棋，下注金额已退还。" : "§e双方同意和棋。");
    }

    // ==================== 机器人 ====================

    public boolean isAiGame() {
        return aiColor == BLACK || aiColor == WHITE;
    }

    public int getAiColor() {
        return aiColor;
    }

    public int getAiLevel() {
        return aiLevel;
    }

    public static boolean isAiSeat(UUID id) {
        return AI_ID.equals(id);
    }

    public static String aiLevelName(int level) {
        switch (level) {
            case 1:
                return "入门";
            case 2:
                return "进阶";
            case 3:
                return "大师";
            default:
                return "";
        }
    }

    public String aiName() {
        return aiLevelName(aiLevel) + "机器人";
    }

    public boolean hasPendingAi() {
        return pendingAiLevel > 0;
    }

    public boolean isPendingAiSeat(UUID id) {
        return pendingAiLevel > 0 && id != null && id.equals(pendingAiSeat);
    }

    /** 选择单人对战：已选色则机器人立刻坐到对面，未选色则先占座等待选色。 */
    public boolean chooseAi(Player player, int level) {
        if (running || level < 1 || level > 3) {
            return false;
        }
        UUID id = player.getUniqueId();
        boolean seated = seats.contains(id);
        if (seated ? seats.size() > 1 : seats.size() >= 2) {
            tipTo(id, "§c座位已满，无法开始单人对战。");
            return false;
        }
        int myColor = seatColor(id);
        if (myColor != -1) {
            seatAi(myColor == BLACK ? WHITE : BLACK, level);
            tipTo(id, "§a对手已就位：" + aiName() + "（你执" + colorName(myColor) + "）。");
            return true;
        }
        if (!seated) {
            seats.add(id);
        }
        knownNames.put(id, player.getName());
        pendingAiLevel = level;
        pendingAiSeat = id;
        releaseMenu(id);
        manager.markDirty();
        player.sendTip("§a已选择" + aiLevelName(level) + "机器人，请选择你的棋色。");
        return true;
    }

    /** 已选单人对战、等待选色的玩家选择棋色。 */
    public boolean chooseAiColor(Player player, int color) {
        UUID id = player.getUniqueId();
        if (pendingAiLevel == 0 || !id.equals(pendingAiSeat)) {
            return false;
        }
        if (color != BLACK && color != WHITE || seats.size() >= 2) {
            return false;
        }
        seatColors.put(id, color);
        knownNames.put(id, player.getName());
        seatAi(color == BLACK ? WHITE : BLACK, pendingAiLevel);
        player.sendTip("§a对手已就位：" + aiName() + "（你执" + colorName(color) + "）。");
        return true;
    }

    private void seatAi(int color, int level) {
        seats.remove(AI_ID);
        seatColors.remove(AI_ID);
        seats.add(AI_ID);
        seatColors.put(AI_ID, color);
        aiColor = color;
        aiLevel = level;
        pendingAiLevel = 0;
        pendingAiSeat = null;
        manager.markDirty();
    }

    /** 退出单人对战：只移除机器人座位，玩家保持在座。 */
    public boolean exitAiBattle() {
        if (running || !isAiGame()) {
            return false;
        }
        seats.remove(AI_ID);
        seatColors.remove(AI_ID);
        aiColor = 0;
        aiLevel = 0;
        aiThinking = false;
        manager.markDirty();
        return true;
    }

    /** 申请一次机器人思考（已有任务在跑时返回 false）。 */
    public boolean beginThinking() {
        if (aiThinking) {
            return false;
        }
        aiThinking = true;
        return true;
    }

    public void finishThinking() {
        aiThinking = false;
    }

    /** 当前是否轮到机器人行棋。 */
    public boolean isAiTurn() {
        return running && isAiGame() && turn == aiColor;
    }

    /** 导出棋盘快照（一维，长度 {@code n*n}）供机器人异步计算。 */
    public int[] copyGrid() {
        int[] copy = new int[n * n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                copy[r * n + c] = grid[r][c];
            }
        }
        return copy;
    }

    /** 打劫禁着点的一维下标（-1 表示无）。 */
    public int koIndex() {
        return koRow < 0 ? -1 : koRow * n + koCol;
    }

    /** 机器人落子：{@code {行, 列}}；传入 {@code null} 表示停一手（Pass）。 */
    public boolean aiApply(int[] mv) {
        if (!running || turn != aiColor) {
            return false;
        }
        if (mv == null) {
            performPass(aiColor);
            return true;
        }
        int row = mv[0];
        int col = mv[1];
        if (row < 0 || row >= n || col < 0 || col >= n || grid[row][col] != EMPTY) {
            return false;
        }
        if (!isLegal(row, col, aiColor)) {
            return false;
        }
        applyMove(row, col, aiColor);
        return true;
    }

    /** 落子核心：提子、更新劫争点、记最后一手、刷新整盘。 */
    private void applyMove(int row, int col, int color) {
        grid[row][col] = color;
        int opp = other(color);
        int captured = 0;
        int capRow = -1;
        int capCol = -1;
        for (int d = 0; d < 4; d++) {
            int r = row + DR[d];
            int c = col + DC[d];
            if (!inBounds(r, c) || grid[r][c] != opp) {
                continue;
            }
            if (hasLiberty(r, c)) {
                continue;
            }
            int size = groupBuf.size();
            if (size == 1) {
                capRow = groupBuf.get(0)[0];
                capCol = groupBuf.get(0)[1];
            }
            for (int i = 0; i < size; i++) {
                int[] p = groupBuf.get(i);
                grid[p[0]][p[1]] = EMPTY;
            }
            captured += size;
        }
        int ownSize = hasLiberty(row, col) ? groupBuf.size() : 0;
        if (captured == 1 && ownSize == 1) {
            koRow = capRow;
            koCol = capCol;
        } else {
            koRow = -1;
            koCol = -1;
        }
        passes = 0;
        lastAttr = boneK(row, col) + 1;
        playBoardSound(captured > 0 ? "tabletopgame.othello.flip" : "tabletopgame.gomoku.place");
        setHover(0);
        turn = other(color);
        if (recordPosition()) {
            return;
        }
        writeAll(true);
        manager.markDirty();
        if (captured > 0) {
            broadcastTip("§e" + colorName(color) + "提子 " + captured + " 枚。");
        }
        notifyTurn();
    }

    private void notifyTurn() {
        UUID mover = seatOfColor(other(turn));
        UUID opponent = seatOfColor(turn);
        if (mover != null) {
            tipTo(mover, "§a落子成功，轮到对手行棋");
        }
        if (opponent != null) {
            tipTo(opponent, "§a对手落子完成，轮到你行棋");
        }
    }

    /** 数子结算（中国规则）：棋子数 + 单色围空，白方另加贴目，子多者胜。 */
    private void settle() {
        int[] score = countScore();
        double black = score[0];
        double white = score[1] + komi;
        running = false;
        setHover(0);
        manager.markDirty();
        writeAll(false);
        StringBuilder sb = new StringBuilder("§e对局结束（数子）：黑 " + score[0] + " 目");
        if (komi > 0) {
            sb.append("（子 " + score[1] + " + 贴目 " + komi + "）");
        }
        sb.append(" : 白 ").append(String.format(java.util.Locale.ROOT, "%.1f", white)).append(" 目。");
        broadcastTip(sb.toString());
        if (black > white) {
            winner = seatOfColor(BLACK);
            draw = false;
            settleWin(BLACK, null);
            announceWin(BLACK);
        } else if (white > black) {
            winner = seatOfColor(WHITE);
            draw = false;
            settleWin(WHITE, null);
            announceWin(WHITE);
        } else {
            winner = null;
            draw = true;
            broadcastTip(refundStakes() ? "§e本局和棋，下注金额已退还。" : "§e本局和棋。");
        }
    }

    /** 统计双方目数：{@code [黑, 白]}（白方不含贴目）。 */
    private int[] countScore() {
        int black = 0;
        int white = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (grid[r][c] == BLACK) {
                    black++;
                } else if (grid[r][c] == WHITE) {
                    white++;
                }
            }
        }
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (grid[r][c] != EMPTY || seen[r][c]) {
                    continue;
                }
                regionBuf.clear();
                int border = 0;
                stack.clear();
                stack.push(new int[]{r, c});
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    int y = p[0];
                    int x = p[1];
                    if (seen[y][x]) {
                        continue;
                    }
                    seen[y][x] = true;
                    regionBuf.add(p);
                    for (int d = 0; d < 4; d++) {
                        int ny = y + DR[d];
                        int nx = x + DC[d];
                        if (!inBounds(ny, nx)) {
                            continue;
                        }
                        int v = grid[ny][nx];
                        if (v == EMPTY) {
                            if (!seen[ny][nx]) {
                                stack.push(new int[]{ny, nx});
                            }
                        } else if (v == BLACK) {
                            border |= 1;
                        } else {
                            border |= 2;
                        }
                    }
                }
                if (border == 1) {
                    black += regionBuf.size();
                } else if (border == 2) {
                    white += regionBuf.size();
                }
            }
        }
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                seen[r][c] = false;
            }
        }
        return new int[]{black, white};
    }

    /**
     * 胜负播报。
     * <p>
     * 玩家对局与「战胜大师级机器人」全服播报（聊天栏广播 + 全服提示条）；
     * 战胜入门/进阶机器人、或机器人获胜，只给当事玩家发提示条 —— 与五子棋 / 象棋 /
     * 黑白棋三块棋盘的 {@code announceWin} 口径完全一致，不要在这里无条件 {@code broadcastMessage}。
     */
    private void announceWin(int winColor) {
        UUID winnerSeat = seatOfColor(winColor);
        String msg = "§6" + nameOf(winnerSeat) + " 在围棋对战中获胜！";
        if (!isAiGame()) {
            announceToAll(msg);
            return;
        }
        boolean playerWon = winColor != aiColor;
        if (playerWon && aiLevel == AI_LEVEL_MASTER) {
            announceToAll(msg);
            return;
        }
        // 其余结果（战胜入门/进阶机器人，或机器人落败给玩家之前的任何情况）：只提示当事人
        UUID human = seatOfColor(other(aiColor));
        tipTo(human != null ? human : winnerSeat, msg);
    }

    /** 全服播报：聊天栏广播 + 全服提示条，两种提示同时发出。 */
    private void announceToAll(String msg) {
        Server.getInstance().broadcastMessage(msg);
        for (Player p : Server.getInstance().getOnlinePlayers().values()) {
            p.sendTip(msg);
        }
    }

    private void playBoardSound(String sound) {
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

    // ==================== 围棋规则 ====================

    private boolean inBounds(int r, int c) {
        return r >= 0 && r < n && c >= 0 && c < n;
    }

    /** 该落点是否合法（不自杀、不违反单劫）。调用前后盘面不变。 */
    private boolean isLegal(int row, int col, int color) {
        if (grid[row][col] != EMPTY) {
            return false;
        }
        if (koRow == row && koCol == col) {
            return false;
        }
        grid[row][col] = color;
        boolean captures = false;
        int opp = other(color);
        for (int d = 0; d < 4 && !captures; d++) {
            int r = row + DR[d];
            int c = col + DC[d];
            if (inBounds(r, c) && grid[r][c] == opp && !hasLiberty(r, c)) {
                captures = true;
            }
        }
        boolean legal = captures || hasLiberty(row, col);
        grid[row][col] = EMPTY;
        return legal;
    }

    /**
     * 判断 {@code (r,c)} 所在同色棋组是否还有气，棋组成员同时写入 {@link #groupBuf}。
     */
    private boolean hasLiberty(int r, int c) {
        groupBuf.clear();
        int color = grid[r][c];
        int libs = 0;
        stack.clear();
        stack.push(new int[]{r, c});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int y = p[0];
            int x = p[1];
            if (visited[y][x]) {
                continue;
            }
            visited[y][x] = true;
            groupBuf.add(p);
            for (int d = 0; d < 4; d++) {
                int ny = y + DR[d];
                int nx = x + DC[d];
                if (!inBounds(ny, nx)) {
                    continue;
                }
                int v = grid[ny][nx];
                if (v == EMPTY) {
                    libs++;
                } else if (v == color && !visited[ny][nx]) {
                    stack.push(new int[]{ny, nx});
                }
            }
        }
        for (int i = 0; i < groupBuf.size(); i++) {
            int[] p = groupBuf.get(i);
            visited[p[0]][p[1]] = false;
        }
        return libs > 0;
    }

    // ==================== 让子 ====================

    private void clearGrid() {
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                grid[r][c] = EMPTY;
            }
        }
    }

    /** 按标准星位摆放让子（星位不合规的小棋盘退化为最接近的对称点）。 */
    private void placeHandicap(int count) {
        int low = n >= 13 ? 3 : 2;
        int high = n - 1 - low;
        int mid = (n - 1) / 2;
        int[][] corners = {{high, low}, {low, high}, {low, low}, {high, high}};
        int[][] sides = {{low, mid}, {high, mid}, {mid, low}, {mid, high}};
        List<int[]> points = new ArrayList<>(9);
        for (int i = 0; i < 4; i++) {
            points.add(corners[i]);
        }
        if (count == 5 || count == 7 || count == 9) {
            points.add(new int[]{mid, mid});
            for (int i = 0; i < count - 5; i++) {
                points.add(sides[i]);
            }
        } else if (count >= 6) {
            for (int i = 0; i < count - 4; i++) {
                points.add(sides[i % 4]);
            }
        }
        for (int[] p : points) {
            if (p[0] >= 0 && p[0] < n && p[1] >= 0 && p[1] < n) {
                grid[p[0]][p[1]] = BLACK;
            }
        }
    }

    // ==================== 渲染 ====================

    /** 骨骼编号：与资源包中 {@code cell_{RR}_{CC}} 的下标一致。 */
    private int boneK(int row, int col) {
        return (row + offset) * 19 + (col + offset);
    }

    /** 把盘面编码为 25 个三进制打包属性。 */
    private int[] encodeG() {
        int[] g = new int[GoStoneEntity.PROP_COUNT];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int v = grid[r][c];
                if (v == EMPTY) {
                    continue;
                }
                int k = boneK(r, c);
                g[k / 15] += v * POW3[k % 15];
            }
        }
        return g;
    }

    /**
     * 写入整盘状态。
     *
     * @param includeHover 是否保留准星高亮
     */
    private void writeAll(boolean includeHover) {
        int[] g = encodeG();
        writeEntity(g, lastAttr, includeHover ? hoverAttr : 0);
    }

    private void writeEntity(int[] g, int last, int hover) {
        if (stoneEntity == null || stoneEntity.isClosed()) {
            return;
        }
        stoneEntity.beginBatch();
        stoneEntity.setBoardSize(boardSize);
        stoneEntity.setGrid(n);
        for (int i = 0; i < g.length; i++) {
            stoneEntity.setG(i, g[i]);
        }
        stoneEntity.setLast(last);
        stoneEntity.setHover(hover);
        stoneEntity.endBatch();
    }

    /** 生成绘制整盘棋子的实体（已存在则跳过）。 */
    public void spawnPieces() {
        if (stoneEntity == null || stoneEntity.isClosed()) {
            Entity created = Entity.createEntity(GoStoneEntity.IDENTIFIER, Position.fromObject(center, level));
            if (created instanceof GoStoneEntity stone) {
                stoneEntity = stone;
                stone.setBoard(this);
            }
        }
        if (stoneEntity == null) {
            return;
        }
        stoneEntity.setRotation(rotation * 90.0, 0.0);
        stoneEntity.setBoardSize(boardSize);
        stoneEntity.setGrid(n);
        stoneEntity.spawnToAll();
        writeAll(true);
    }

    public boolean isPiecesMissing() {
        return stoneEntity == null || stoneEntity.isClosed();
    }

    public boolean hasPlayerInRange() {
        for (Player p : level.getPlayers().values()) {
            if (inRange(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集本 tick 需要自动离座的座位玩家（在线但已离开范围）。
     *
     * @param out 输出缓冲，方法内会先清空
     */
    public void collectLeftPlayers(List<UUID> out) {
        out.clear();
        for (int i = 0; i < seats.size(); i++) {
            UUID id = seats.get(i);
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null && !inRange(p)) {
                out.add(id);
            }
        }
    }

    public boolean inRange(Player player) {
        if (player.getLevel() != this.level) {
            return false;
        }
        double range = manager.playerRange();
        return Math.abs(player.x - center.x) <= range
                && Math.abs(player.z - center.z) <= range
                && Math.abs(player.y - center.y) <= 12.0;
    }

    /** 销毁棋子实体与棋盘方块。 */
    public void remove() {
        running = false;
        menuHolder = null;
        refundStakes();
        seats.clear();
        seatColors.clear();
        knownNames.clear();
        if (stoneEntity != null && !stoneEntity.isClosed()) {
            stoneEntity.close();
        }
        stoneEntity = null;
        for (int i = 0; i < boardSize; i++) {
            for (int j = 0; j < boardSize; j++) {
                Vector3 p = new Vector3(
                        baseX + i * TabletopGamesBoard.axisPX(rotation) + j * TabletopGamesBoard.axisQX(rotation),
                        baseY,
                        baseZ + i * TabletopGamesBoard.axisPZ(rotation) + j * TabletopGamesBoard.axisQZ(rotation));
                if (level.getBlock(p) instanceof GoBoardBlock) {
                    level.setBlock(p, cn.nukkit.block.Block.get(cn.nukkit.block.Block.AIR), true);
                }
            }
        }
    }

    /** 该坐标是否属于本棋盘（boardSize x boardSize 区域）。 */
    public boolean contains(int x, int y, int z) {
        if (y != baseY) {
            return false;
        }
        int dx = x - baseX;
        int dz = z - baseZ;
        int u = dx * TabletopGamesBoard.axisPX(rotation) + dz * TabletopGamesBoard.axisPZ(rotation);
        int v = dx * TabletopGamesBoard.axisQX(rotation) + dz * TabletopGamesBoard.axisQZ(rotation);
        return u >= 0 && u < boardSize && v >= 0 && v < boardSize;
    }

    // ==================== 存档 ====================

    /** 导出盘面：{@code .} 空、{@code 1} 黑、{@code 2} 白，长度 {@code n*n}。 */
    public String exportGrid() {
        StringBuilder sb = new StringBuilder(n * n);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int v = grid[r][c];
                sb.append(v == EMPTY ? '.' : (char) ('0' + v));
            }
        }
        return sb.toString();
    }

    /** 从存档恢复盘面与对局状态。 */
    public void importState(int savedTurn, boolean savedStarted, double savedKomi, int savedHandicap,
                            int savedPasses, String savedGrid) {
        clearGrid();
        if (savedGrid != null && savedGrid.length() >= n * n) {
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    char ch = savedGrid.charAt(r * n + c);
                    grid[r][c] = ch == '.' ? EMPTY : (ch - '0');
                }
            }
        }
        turn = savedTurn == WHITE ? WHITE : BLACK;
        started = savedStarted;
        komi = savedKomi;
        handicap = savedHandicap;
        passes = savedPasses;
        resetPositionHistory();
    }

    // ==================== 菜单辅助 ====================

    public void reopenMenu(Player player) {
        manager.reopenMenu(player, this);
    }

    public void later(Runnable action) {
        manager.runLater(action);
    }

    /** 表单正文：座位、状态、让子与贴目。 */
    public String statusText(Player viewer) {
        StringBuilder sb = new StringBuilder();
        sb.append("§7路数：§f").append(n).append(" 路（").append(boardSize).append("×").append(boardSize).append(" 方块）");
        sb.append("\n§7座位：§f");
        if (seats.isEmpty()) {
            sb.append("空");
        } else {
            for (UUID id : seats) {
                int color = seatColor(id);
                sb.append(color == BLACK ? "§0（黑）§f" : "§f（白）§f").append(nameOf(id)).append("  ");
            }
        }
        sb.append("\n§7状态：§f");
        if (running) {
            sb.append("对局中，轮到").append(colorName(turn));
            if (handicap >= MIN_HANDICAP) {
                sb.append("（让 ").append(handicap).append(" 子，不贴目）");
            } else {
                sb.append("（黑贴 7.5 目）");
            }
            sb.append("§7。");
        } else if (winner != null) {
            sb.append("已结束，").append(nameOf(winner)).append(" 获胜。");
        } else if (draw) {
            sb.append("已结束，和棋。");
        } else {
            sb.append("未开始。");
        }
        if (!seats.isEmpty()) {
            sb.append("\n\n§e提示：可重新打开界面刷新当前状态");
        } else {
            sb.append("\n\n§7提示：可加入他人对局。");
        }
        return sb.toString();
    }

    public String seatName(UUID id) {
        return nameOf(id);
    }

    public Map<UUID, String> copyKnownNames() {
        return new LinkedHashMap<>(knownNames);
    }

    private String nameOf(UUID id) {
        if (id == null) {
            return "对手";
        }
        if (AI_ID.equals(id)) {
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

    /** 生成一个该路数 / 边长规格的棋盘方块（存档重放与放置共用）。 */
    public static GoBoardBlock newBlock(int n, int boardSize) {
        if (n == 9) {
            return new GoBoard9Block();
        }
        if (n == 13) {
            return new GoBoard13Block();
        }
        return boardSize >= 4 ? new GoBoard19BigBlock() : new GoBoard19Block();
    }
}
