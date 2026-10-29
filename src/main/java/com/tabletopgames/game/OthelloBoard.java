package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import com.tabletopgames.ai.OthelloAi;
import com.tabletopgames.board.BaseTabletopGamesBoardBlock;
import com.tabletopgames.board.OthelloBoardBlock;
import com.tabletopgames.economy.EconomyHook;
import com.tabletopgames.item.LocatorService;
import com.tabletopgames.entity.OthelloDiscEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个已放置的黑白棋（奥赛罗）棋盘实例：负责初始 4 子布局、把整盘棋局编码进单个棋子实体的属性，
 * 并管理完整对局（座位、回合、落子/翻转、胜负、残局继承）。
 * <p>
 * 规则以标准奥赛罗为准：黑先手；落子必须夹住对方棋子（横竖斜任一方向）；被夹棋子翻转；
 * 一方无合法落点则跳过回合；双方均无合法落点（或棋盘下满）时数子定胜负，子多者胜，相等为和。
 * <p>
 * <b>落子节奏</b>：落子后立即显示新棋子，等待 {@link #FLIP_DELAY_TICKS} 个 tick（0.5 秒）后
 * 把该翻转的棋子一次性翻转，然后才把回合交给对方。等待期间 {@link #busy} 置位，屏蔽一切输入。
 */
public class OthelloBoard {

    /** 棋盘边长。 */
    public static final int N = 8;
    /** 落点总数。 */
    public static final int CELLS = N * N;

    /** 机器人座位的哨兵 UUID（全 0），不对应任何真实玩家。 */
    public static final UUID AI_ID = new UUID(0L, 0L);

    /** 空格标记。 */
    public static final int EMPTY = 0;
    /** 黑棋（先手）。 */
    public static final int COLOR_BLACK = 1;
    /** 白棋。 */
    public static final int COLOR_WHITE = 2;

    /**
     * 棋盘方块顶面高度。
     * <p>
     * 棋盘分块几何体的立方体为 {@code origin [-8, 0, -8]}、{@code size [16, 2, 16]}，
     * 即板面从 y=0 铺到 y=2 模型单位（2/16 世界单位），与方块的碰撞箱一致。
     */
    public static final double BOARD_TOP = 2.0 / 16.0;

    /** 贴图像素 → 世界单位换算：棋盘贴图 256px 铺满 2x2 方块（2 世界单位），即 128px = 1 方块。 */
    private static final double PX_PER_UNIT = 128.0;
    /** 网格中心像素（256px 贴图的几何中心）。 */
    private static final double TEX_CENTER = 127.5;
    /** 首个格子中心像素。 */
    private static final double COL_X0 = 33.0;
    /** 格距像素。 */
    private static final double STEP_PX = 27.0;

    /** 落子音效（资源包 sound_definitions）。 */
    private static final String SND_PLACE = "tabletopgame.othello.place";
    /** 翻转音效。 */
    private static final String SND_FLIP = "tabletopgame.othello.flip";

    /** 落子后等待多少 tick（0.5 秒 = 10 tick）再执行翻转与换手。 */
    private static final int FLIP_DELAY_TICKS = 10;

    private static final int[] DR = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1, -1, 1, -1, 0, 1};

    /**
     * 标准初始局面：中央 2x2 交叉摆好 4 子，黑先手。
     * <p>
     * {@code row} 0 在棋盘远端（远离放置者），7 在近端；{@code col} 0 在左、7 在右。
     */
    private static final int[][] INITIAL = new int[N][N];

    static {
        INITIAL[3][3] = COLOR_WHITE;
        INITIAL[3][4] = COLOR_BLACK;
        INITIAL[4][3] = COLOR_BLACK;
        INITIAL[4][4] = COLOR_WHITE;
    }

    private final OthelloManager manager;
    private final Level level;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    /** 棋盘朝向：0=南、1=西、2=北、3=东。 */
    private final int rotation;

    /** 棋盘中心（构造时算好并复用）。只读，调用方不得修改。 */
    private final Vector3 center;

    /** 当前棋局：{@code [row][col]}，值为 0 空 / 1 黑 / 2 白。 */
    private final int[][] grid = new int[N][N];

    /** 渲染整盘棋子的唯一实体。 */
    private OthelloDiscEntity entity;

    // ==================== 对局状态 ====================

    /** 座位（按加入先后排序，index 0 为第一位加入者）。 */
    private final List<UUID> seats = new ArrayList<>(2);
    /** 各座位玩家选择的棋色：1 黑 / 2 白。 */
    private final Map<UUID, Integer> seatColors = new HashMap<>(2);
    /** 曾在该棋盘出现过的玩家名（UUID -> 名字），用于离线时仍能正确显示座位名。 */
    private final Map<UUID, String> knownNames = new LinkedHashMap<>(4);

    private boolean running;
    /** 当前该行棋的一方：黑先行。 */
    private int turn = COLOR_BLACK;
    /** 上一手落点格（{@code row*8+col+1}，0 = 无）。 */
    private int lastCell;
    /** 落子后等待翻转期间置位，屏蔽一切输入。 */
    private boolean busy;
    private UUID winner;
    private boolean draw;
    /** 是否开过局（决定菜单是否显示「继承残局」）。 */
    private boolean started;

    /** 机器人棋色：0 表示本局没有机器人。 */
    private int aiColor;
    /** 机器人难度：1 入门 / 2 进阶 / 3 大师。 */
    private int aiLevel;
    /** 已选择「单人对战」但尚未选色时暂存的机器人等级（0 表示无）。 */
    private int pendingAiLevel;
    /** 已选择「单人对战」但尚未选色的玩家。 */
    private UUID pendingAiSeat;
    /** 机器人是否正在思考，避免重复派发计算任务。 */
    private boolean aiThinking;

    /** 0 人入座时预设的「每局金额」（双方各自下注额；0 = 免费局，默认 0）。 */
    private double presetBet;
    /** 本局下注金额（未开局为 0）。 */
    private double betAmount;
    /** 已扣押注的座位 -> 金额；开局扣款时填入，结算/退还后清空。 */
    private final Map<UUID, Double> stakes = new HashMap<>(2);

    /** 0 人入座时正在操作菜单的玩家（同一时间只允许一人操作）。 */
    private UUID menuHolder;
    /** 菜单占用到期时间（毫秒时间戳）。 */
    private long menuHolderUntil;

    public OthelloBoard(OthelloManager manager, Level level, int baseX, int baseY, int baseZ, int rotation) {
        this.manager = manager;
        this.level = level;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.rotation = TabletopGamesBoard.normalizeRotation(rotation);
        // 与五子棋一致：2x2 区域中心 = base + 0.5 + 0.5 * (P + Q)
        this.center = new Vector3(
                baseX + 0.5 + 0.5 * (TabletopGamesBoard.axisPX(this.rotation) + TabletopGamesBoard.axisQX(this.rotation)),
                baseY,
                baseZ + 0.5 + 0.5 * (TabletopGamesBoard.axisPZ(this.rotation) + TabletopGamesBoard.axisQZ(this.rotation)));
        for (int r = 0; r < N; r++) {
            System.arraycopy(INITIAL[r], 0, grid[r], 0, N);
        }
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

    public String getKey() {
        return level.getName() + ":" + baseX + ":" + baseY + ":" + baseZ;
    }

    public Vector3 getCenter() {
        return center;
    }

    public boolean isRunning() {
        return running;
    }

    /** 当前该行棋的一方：1 黑 / 2 白。 */
    public int getTurn() {
        return turn;
    }

    /** 是否开过局（用于决定是否显示「继承残局」）。 */
    public boolean hasStarted() {
        return started;
    }

    public UUID getWinner() {
        return winner;
    }

    public boolean isDraw() {
        return draw;
    }

    /** 本局是否已分出胜负或和棋。 */
    public boolean isFinished() {
        return !running && (winner != null || draw);
    }

    /** 是否正处于落子后的翻转等待期。 */
    public boolean isBusy() {
        return busy;
    }

    public static String colorName(int color) {
        if (color == COLOR_BLACK) {
            return "黑方";
        }
        if (color == COLOR_WHITE) {
            return "白方";
        }
        return "";
    }

    private static int other(int color) {
        return color == COLOR_BLACK ? COLOR_WHITE : COLOR_BLACK;
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

    /** 玩家所选棋色：1 黑 / 2 白，未入座为 -1。 */
    public int seatColor(UUID uuid) {
        return seatColors.getOrDefault(uuid, -1);
    }

    /** 某棋色是否已被占用。 */
    public boolean isColorTaken(int color) {
        return seatColors.containsValue(color);
    }

    /** 是否为第一位加入者。 */
    public boolean isFirstJoiner(UUID uuid) {
        return !seats.isEmpty() && seats.get(0).equals(uuid);
    }

    /** 加入座位并选择棋色（1 黑 / 2 白）。 */
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
        if (color != COLOR_BLACK && color != COLOR_WHITE) {
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
                running = false;
                busy = false;
                aiThinking = false;
                winner = winnerId;
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

    // ==================== 菜单占用（0 人入座时独占） ====================

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

    /** 菜单占用兜底清理：持有者超时或下线时关闭其表单并释放占用。 */
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

    // ==================== 机器人 ====================

    public boolean isAiGame() {
        return aiColor == COLOR_BLACK || aiColor == COLOR_WHITE;
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

    public boolean isAiThinking() {
        return aiThinking;
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

    /** 当前是否轮到机器人行棋（翻转等待期内不派发）。 */
    public boolean isAiTurn() {
        return running && !busy && isAiGame() && turn == aiColor;
    }

    /** 机器人本次行棋的棋色。 */
    public int aiMoveColor() {
        return aiColor;
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
            seatAi(myColor == COLOR_BLACK ? COLOR_WHITE : COLOR_BLACK, level);
            tipTo(id, "§a对手已就位：" + aiName() + "（你执" + colorName(myColor) + "）。");
            return true;
        }
        if (!seats.contains(id)) {
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
        if (color != COLOR_BLACK && color != COLOR_WHITE) {
            return false;
        }
        if (seats.size() >= 2) {
            return false;
        }
        seatColors.put(id, color);
        knownNames.put(id, player.getName());
        seatAi(color == COLOR_BLACK ? COLOR_WHITE : COLOR_BLACK, pendingAiLevel);
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

    // ==================== 赌博模式 ====================

    /** 本局预设金额（双方各自下注额；0 = 免费局，未设置时为 0）。 */
    public double getPresetBet() {
        return presetBet;
    }

    /** 本局下注金额（未开局为 0）。 */
    public double getBetAmount() {
        return betAmount;
    }

    /** 每局可下注的最小金额（来自配置）。 */
    public double getMinBet() {
        return manager.config().getBetting().normalizedMinBet();
    }

    /** 每局可下注的最大金额（来自配置）。 */
    public double getMaxBet() {
        return manager.config().getBetting().normalizedMaxBet();
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
     * 按预设金额开局（赌博模式）：同时各扣该金额（余额已在入座时校验；扣款失败会回滚），最后开始对局（黑先先行）。
     *
     * @param bet 本局双方各自的扣款金额（两位小数，须 &gt; 0）
     * @return 成功返回 null；失败返回可直接展示的原因（已含玩家名）
     */
    public String startGambling(double bet) {
        if (running || seats.size() < 2 || isAiGame()) {
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
        start();
        for (UUID id : seats) {
            tipTo(id, "§6赌博模式：已从你的账户扣除下注 §e" + formatMoney(bet)
                    + "§6，本局奖池 §e" + formatMoney(bet * 2) + "§6。");
            if (EconomyHook.balance(id) <= 0) {
                tipTo(id, "§c你已破产：余额被扣至 0。下一局需先补充余额才能继续入座。");
            }
        }
        return null;
    }

    /**
     * 赌博结算：赢家获得奖池扣除官方抽水后的金额。未处于赌博对局时不做任何事。
     */
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

    /** 退还双方已扣的下注金额（和棋或对局中途终止时）。 */
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

    /** 开始对局（重摆初始 4 子、黑先手）。 */
    public void start() {
        if (running || seats.size() < 2) {
            return;
        }
        for (int r = 0; r < N; r++) {
            System.arraycopy(INITIAL[r], 0, grid[r], 0, N);
        }
        running = true;
        started = true;
        turn = COLOR_BLACK;
        lastCell = 0;
        busy = false;
        winner = null;
        draw = false;
        aiThinking = false;
        pendingAiLevel = 0;
        pendingAiSeat = null;
        writeAll(entity, true);
        manager.markDirty();
        manager.closeSeatMenus(this);
        broadcastTip("§e黑白棋对局开始！黑方（先手）落子。");
    }

    /**
     * 继承残局：保留当前局面与轮次，按「本来该谁走就谁走」继续。
     */
    public void inheritEndgame() {
        if (running || seats.size() < 2 || !started) {
            return;
        }
        running = true;
        busy = false;
        winner = null;
        draw = false;
        aiThinking = false;
        writeAll(entity, true);
        manager.markDirty();
        manager.closeSeatMenus(this);
        broadcastTip("§e已继承残局，" + colorName(turn) + "继续落子。");
    }

    /** 中途终止（有人退出 / 机器人无法行棋）：保留局面与轮次，便于之后「继承残局」。 */
    void abort(String reason) {
        running = false;
        busy = false;
        aiThinking = false;
        setHover(0);
        // 与上一手标记一致：中断后仍常驻显示当前回合方的可落点提示
        writeAll(entity, true);
        boolean refunded = refundStakes();
        manager.markDirty();
        broadcastTip("§c" + reason + (refunded ? "，下注金额已退还。" : ""));
    }

    // ==================== 落子 / 翻转 ====================

    /**
     * 计算玩家准星命中的棋盘格子（1 基索引，0 表示未命中）。
     * <p>
     * 行轴取 -Q（实体模型 Z 轴与世界 Z 轴方向相反，行方向取 Q 的反向），与资源包骨骼的
     * {@code disc_{r}_{c}} 坐标严格对应，改动任一侧都必须同步。
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
        double step = STEP_PX / PX_PER_UNIT;
        int col = (int) Math.round(a / step + 3.5);
        int row = (int) Math.round(b / step + 3.5);
        if (col < 0 || col >= N || row < 0 || row >= N) {
            return 0;
        }
        return row * N + col + 1;
    }

    /**
     * 本 tick 唯一需要看到准星方环的玩家：当前回合方。机器人回合或翻转等待期不需要，
     * 返回 null 表示当前不需要刷新。与 {@code manager.tick()} 的周期刷新共用。
     */
    public UUID hoverPlayerId() {
        if (!running || busy) {
            return null;
        }
        if (aiColor != 0 && aiColor == turn) {
            return null;
        }
        return seatOfColor(turn);
    }

    /** 刷新准星方环：只有当前回合方的玩家能移动它。 */
    public void updateHover(Player player) {
        UUID actor = hoverPlayerId();
        if (actor == null || !actor.equals(player.getUniqueId())) {
            return;
        }
        setHover(aimCell(player));
    }

    /** 写入准星瞄准格（0 = 隐藏方环）。 */
    private void setHover(int cell) {
        if (entity == null || entity.isClosed()) {
            return;
        }
        entity.setHover(cell);
    }

    /**
     * 玩家落子。
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
        if (busy) {
            return "§e正在翻转棋子，请稍候…";
        }
        int cell = aimCell(player);
        if (cell == 0) {
            return "§c请将准星对准棋盘上的格子。";
        }
        int row = (cell - 1) / N;
        int col = (cell - 1) % N;
        if (grid[row][col] != EMPTY) {
            return "§c该格已有棋子。";
        }
        int[] flips = flipsAt(grid, row, col, turn);
        if (flips.length == 0) {
            return "§c这里不能落子（无法夹住对方棋子）。";
        }
        placeDisc(row, col, turn, flips);
        return null;
    }

    /** 机器人落子：{@code {行, 列}}。 */
    public boolean aiApply(int[] mv) {
        if (mv == null || !running || busy || turn != aiColor) {
            return false;
        }
        int row = mv[0];
        int col = mv[1];
        if (row < 0 || row >= N || col < 0 || col >= N || grid[row][col] != EMPTY) {
            return false;
        }
        int[] flips = flipsAt(grid, row, col, aiColor);
        if (flips.length == 0) {
            return false;
        }
        placeDisc(row, col, aiColor, flips);
        return true;
    }

    /** 落子核心：先显示新子，等待 0.5 秒后再瞬间翻转并交回合。 */
    private void placeDisc(int row, int col, int color, int[] flips) {
        grid[row][col] = color;
        lastCell = row * N + col + 1;
        playBoardSound(SND_PLACE);
        busy = true;
        // 落子后立即收起准星方环（下一手由新回合方的准星事件重新定位）
        setHover(0);
        // 立即显示新落子，并隐藏可落点提示（此时不该再提示落点）
        writeAll(entity, false);
        manager.markDirty();
        manager.runLater(() -> finishFlip(color, flips), FLIP_DELAY_TICKS);
    }

    /** 0.5 秒后执行：翻转被夹棋子，然后交回合（对方无子可下则跳过或终局）。 */
    private void finishFlip(int color, int[] flips) {
        if (!running) {
            busy = false;
            return;
        }
        for (int idx : flips) {
            grid[idx / N][idx % N] = color;
        }
        if (flips.length > 0) {
            playBoardSound(SND_FLIP);
        }
        busy = false;

        int opp = other(color);
        if (hasMove(grid, opp)) {
            turn = opp;
        } else if (hasMove(grid, color)) {
            turn = color;
            broadcastTip("§e" + colorName(opp) + "无子可下，跳过回合。");
        } else {
            writeAll(entity, false);
            settle();
            return;
        }
        writeAll(entity, true);
        manager.markDirty();
        notifyTurn(color);
    }

    /** 终局：数子定胜负，子多者胜，相等为和。 */
    private void settle() {
        int black = 0;
        int white = 0;
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                if (grid[r][c] == COLOR_BLACK) {
                    black++;
                } else if (grid[r][c] == COLOR_WHITE) {
                    white++;
                }
            }
        }
        running = false;
        busy = false;
        aiThinking = false;
        setHover(0);
        manager.markDirty();
        broadcastTip("§e对局结束：黑 " + black + " : " + white + " 白。");
        if (black > white) {
            winner = seatOfColor(COLOR_BLACK);
            draw = false;
            settleWin(COLOR_BLACK, null);
            announceWin(COLOR_BLACK);
        } else if (white > black) {
            winner = seatOfColor(COLOR_WHITE);
            draw = false;
            settleWin(COLOR_WHITE, null);
            announceWin(COLOR_WHITE);
        } else {
            winner = null;
            draw = true;
            broadcastTip(refundStakes() ? "§e本局和棋，下注金额已退还。" : "§e本局和棋。");
        }
    }

    /** 换手提示（玩家对局双方各一条，人机对战只提示玩家）。 */
    private void notifyTurn(int moverColor) {
        UUID mover = seatOfColor(moverColor);
        if (isAiGame()) {
            if (!isAiSeat(mover)) {
                tipTo(mover, "§a落子成功，对手思考中…");
            }
            return;
        }
        UUID opponent = otherSeat(mover);
        tipTo(mover, "§a落子成功，轮到对手行棋");
        tipTo(opponent, "§a对手落子完成，轮到你行棋");
    }

    /** 胜负播报：玩家对局与战胜大师级机器人全服播报，其余只提示当事人。 */
    private void announceWin(int winColor) {
        UUID winnerSeat = seatOfColor(winColor);
        String msg = "§6" + nameOf(winnerSeat) + " 在黑白棋对战中获胜！";
        if (!isAiGame()) {
            announceToAll(msg);
            return;
        }
        boolean playerWon = winColor != aiColor;
        if (playerWon && aiLevel == 3) {
            announceToAll(msg);
            return;
        }
        UUID target = isAiSeat(winnerSeat) ? otherSeat(winnerSeat) : winnerSeat;
        tipTo(target, msg);
    }

    private static void announceToAll(String msg) {
        Server.getInstance().broadcastMessage(msg);
        for (Player p : Server.getInstance().getOnlinePlayers().values()) {
            p.sendTip(msg);
        }
    }

    /** 向棋盘半径内的所有玩家播放自定义音效（范围与玩家识别半径一致）。 */
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

    // ==================== 规则 ====================

    /** 在 (row,col) 落 {@code color} 子所能翻转的对方棋子下标（一维 {@code row*8+col}）。 */
    private static int[] flipsAt(int[][] g, int row, int col, int color) {
        int[] tmp = new int[CELLS];
        int n = collectFlips(g, row, col, color, tmp);
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /** 把可翻转的棋子下标写入 {@code out}，返回数量。 */
    private static int collectFlips(int[][] g, int row, int col, int color, int[] out) {
        if (g[row][col] != EMPTY) {
            return 0;
        }
        int opp = other(color);
        int n = 0;
        for (int d = 0; d < 8; d++) {
            int r = row + DR[d];
            int c = col + DC[d];
            int cnt = 0;
            while (r >= 0 && r < N && c >= 0 && c < N && g[r][c] == opp) {
                r += DR[d];
                c += DC[d];
                cnt++;
            }
            if (cnt > 0 && r >= 0 && r < N && c >= 0 && c < N && g[r][c] == color) {
                int fr = row + DR[d];
                int fc = col + DC[d];
                for (int k = 0; k < cnt; k++) {
                    out[n++] = fr * N + fc;
                    fr += DR[d];
                    fc += DC[d];
                }
            }
        }
        return n;
    }

    /** (row,col) 落 {@code color} 子是否能夹住至少一枚对方棋子。 */
    private static boolean canFlip(int[][] g, int row, int col, int color) {
        if (g[row][col] != EMPTY) {
            return false;
        }
        int opp = other(color);
        for (int d = 0; d < 8; d++) {
            int r = row + DR[d];
            int c = col + DC[d];
            int cnt = 0;
            while (r >= 0 && r < N && c >= 0 && c < N && g[r][c] == opp) {
                r += DR[d];
                c += DC[d];
                cnt++;
            }
            if (cnt > 0 && r >= 0 && r < N && c >= 0 && c < N && g[r][c] == color) {
                return true;
            }
        }
        return false;
    }

    /** {@code color} 是否有合法落点。 */
    private static boolean hasMove(int[][] g, int color) {
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                if (canFlip(g, r, c, color)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 导出盘面副本，供机器人异步计算。 */
    public int[][] copyGrid() {
        int[][] copy = new int[N][N];
        for (int r = 0; r < N; r++) {
            System.arraycopy(grid[r], 0, copy[r], 0, N);
        }
        return copy;
    }

    // ==================== 存档 ====================

    /** 导出盘面为 64 字符：{@code .} 表示空，{@code 1} 黑 / {@code 2} 白。 */
    public String exportGrid() {
        StringBuilder sb = new StringBuilder(CELLS);
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                int v = grid[r][c];
                sb.append(v == EMPTY ? '.' : (char) ('0' + v));
            }
        }
        return sb.toString();
    }

    /** 从存档恢复盘面与轮次（无盘面数据时保持初始布局）。 */
    public void importState(int savedTurn, boolean savedStarted, String savedGrid) {
        if (savedGrid != null && savedGrid.length() >= CELLS) {
            for (int r = 0; r < N; r++) {
                for (int c = 0; c < N; c++) {
                    char ch = savedGrid.charAt(r * N + c);
                    grid[r][c] = ch == '.' ? EMPTY : (ch - '0');
                }
            }
        }
        turn = savedTurn == COLOR_WHITE ? COLOR_WHITE : COLOR_BLACK;
        started = savedStarted;
    }

    // ==================== 渲染 ====================

    /** 把盘面编码为 8 行的三进制值（每行 0..6560）。 */
    private int[] encodeRows() {
        int[] rows = new int[N];
        for (int r = 0; r < N; r++) {
            int value = 0;
            int mul = 1;
            for (int c = 0; c < N; c++) {
                value += grid[r][c] * mul;
                mul *= 3;
            }
            rows[r] = value;
        }
        return rows;
    }

    /**
     * 把当前回合方的合法落点编码为 8 行的位掩码（未开局全 0）。
     * <p>
     * 只要开过局就始终计算（而非仅在对局进行中），使可落点提示与上一手标记一样，
     * 在中途有人退出、机器人无法落子等中断后仍常驻显示。
     */
    private int[] encodeCan() {
        int[] can = new int[N];
        if (!started) {
            return can;
        }
        for (int r = 0; r < N; r++) {
            int mask = 0;
            for (int c = 0; c < N; c++) {
                if (canFlip(grid, r, c, turn)) {
                    mask |= 1 << c;
                }
            }
            can[r] = mask;
        }
        return can;
    }

    /**
     * 写入整盘状态。
     *
     * @param includeCan 是否写入「当前回合可落点」提示；false 时清空提示
     */
    private void writeAll(OthelloDiscEntity piece, boolean includeCan) {
        if (piece == null || piece.isClosed()) {
            return;
        }
        piece.beginBatch();
        piece.setRows(encodeRows());
        piece.setCan(includeCan ? encodeCan() : new int[N]);
        piece.setLast(lastCell);
        piece.endBatch();
    }

    /** 生成绘制整盘棋子的实体（已生成则跳过）。 */
    public void spawnPieces() {
        if (entity != null && !entity.isClosed()) {
            return;
        }
        Entity created = Entity.createEntity(OthelloDiscEntity.IDENTIFIER,
                cn.nukkit.level.Position.fromObject(center, level));
        if (!(created instanceof OthelloDiscEntity piece)) {
            return;
        }
        entity = piece;
        piece.setBoard(this);
        piece.setRotation(rotation * 90.0, 0.0);
        writeAll(piece, true);
        piece.spawnToAll();
    }

    /** 棋子实体是否缺失（区块卸载会关闭非玩家实体，玩家回来时需重建）。 */
    public boolean isPiecesMissing() {
        return entity == null || entity.isClosed();
    }

    /** 是否有玩家在棋盘范围内。仅在棋子缺失需要重建时调用（低频）。 */
    public boolean hasPlayerInRange() {
        for (Player p : level.getPlayers().values()) {
            if (inRange(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集本 tick 需要自动离座的座位玩家（在线但已离开范围）。离线玩家由 PlayerQuitEvent 处理，
     * 这里跳过。只遍历座位（≤2），不再扫描整关卡的玩家表。
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

    /**
     * 座位/交互检测：以棋盘中心为圆心，水平方向 {@code playerRange} 格内视为在范围内。
     */
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
        busy = false;
        aiThinking = false;
        pendingAiLevel = 0;
        pendingAiSeat = null;
        menuHolder = null;
        refundStakes();
        seats.clear();
        seatColors.clear();
        knownNames.clear();
        if (entity != null && !entity.isClosed()) {
            entity.close();
        }
        entity = null;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                Vector3 p = new Vector3(
                        baseX + i * TabletopGamesBoard.axisPX(rotation) + j * TabletopGamesBoard.axisQX(rotation),
                        baseY,
                        baseZ + i * TabletopGamesBoard.axisPZ(rotation) + j * TabletopGamesBoard.axisQZ(rotation));
                if (level.getBlock(p) instanceof BaseTabletopGamesBoardBlock) {
                    level.setBlock(p, cn.nukkit.block.Block.get(cn.nukkit.block.Block.AIR), true);
                }
            }
        }
    }

    /** 该坐标是否属于本棋盘（2x2 区域）。 */
    public boolean contains(int x, int y, int z) {
        if (y != baseY) {
            return false;
        }
        int dx = x - baseX;
        int dz = z - baseZ;
        int u = dx * TabletopGamesBoard.axisPX(rotation) + dz * TabletopGamesBoard.axisPZ(rotation);
        int v = dx * TabletopGamesBoard.axisQX(rotation) + dz * TabletopGamesBoard.axisQZ(rotation);
        return u >= 0 && u < 2 && v >= 0 && v < 2;
    }

    // ==================== 菜单辅助 ====================

    /** 请求重新弹出本棋盘的菜单。 */
    public void reopenMenu(Player player) {
        manager.reopenMenu(player, this);
    }

    /** 延后一 tick 执行，避免在表单回调中直接弹窗造成客户端窗口冲突。 */
    public void later(Runnable action) {
        manager.runLater(action);
    }

    /** 表单正文：座位、状态、机器人等级。 */
    public String statusText(Player viewer) {
        StringBuilder sb = new StringBuilder();
        sb.append("§7座位：§f");
        if (seats.isEmpty()) {
            sb.append("空");
        } else {
            for (UUID id : seats) {
                int color = seatColor(id);
                sb.append(color == COLOR_BLACK ? "§0（黑）§f" : color == COLOR_WHITE ? "§f（白）§f" : "§e（待选）§f")
                        .append(nameOf(id)).append("  ");
            }
        }
        sb.append("\n§7状态：§f");
        if (running) {
            sb.append("对局中，轮到").append(colorName(turn)).append("§7。");
        } else if (winner != null) {
            sb.append("已结束，").append(nameOf(winner)).append(" 获胜。");
        } else if (draw) {
            sb.append("已结束，和棋。");
        } else {
            sb.append("未开始。");
        }
        if (isAiGame()) {
            sb.append("\n§7机器人：§f").append(aiName()).append("（")
                    .append(colorName(aiColor)).append("）");
        }
        if (!seats.isEmpty()) {
            sb.append("\n\n§e提示：可重新打开界面刷新当前状态");
        } else {
            sb.append("\n\n§7提示：可加入他人对局，或直接选择「单人对战」挑战机器人。");
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
        if (isAiSeat(id)) {
            return aiName();
        }
        Player p = Server.getInstance().getPlayer(id).orElse(null);
        if (p != null) {
            return p.getName();
        }
        String known = knownNames.get(id);
        return known != null ? known : id.toString().substring(0, 8);
    }

    /** 指定棋色对应的座位 UUID。 */
    private UUID seatOfColor(int color) {
        for (Map.Entry<UUID, Integer> entry : seatColors.entrySet()) {
            if (entry.getValue() == color) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 除给定座位外的另一个座位。 */
    private UUID otherSeat(UUID seat) {
        for (UUID id : seats) {
            if (!id.equals(seat)) {
                return id;
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

    /** 本棋盘所用的方块（用于重放）。 */
    public static OthelloBoardBlock newBlock(int position) {
        OthelloBoardBlock block = new OthelloBoardBlock();
        block.setPosition(position);
        return block;
    }
}
