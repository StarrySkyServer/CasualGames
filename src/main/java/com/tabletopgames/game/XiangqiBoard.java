package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import com.tabletopgames.ai.XiangqiAi;
import com.tabletopgames.board.BaseTabletopGamesBoardBlock;
import com.tabletopgames.board.XiangqiBoardBlock;
import com.tabletopgames.economy.EconomyHook;
import com.tabletopgames.item.LocatorService;
import com.tabletopgames.entity.XiangqiPieceEntity;
import com.tabletopgames.ai.xiangqi.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个已放置的中国象棋棋盘实例：负责初始 32 子布局、把整盘棋局编码进单个棋子实体的属性，
 * 并管理完整对局（座位、回合、选子/落子、胜负、残局继承）。
 * <p>
 * 走法合法性与终局判定以 {@link Position}（移植自 xqwlight）为唯一权威。
 */
public class XiangqiBoard {

    /** 棋盘列数（竖线 9 条）。 */
    public static final int COLS = 9;
    /** 棋盘行数（横线 10 条）。 */
    public static final int ROWS = 10;

    /** 机器人座位的哨兵 UUID（全 0），不对应任何真实玩家。 */
    public static final UUID AI_ID = new UUID(0L, 0L);

    /** 因将军限制（走后被将军 / 将帅照面 / 正被将军而该步不解将）导致走子被拒时的提示。 */
    private static final String MSG_CHECK = "§c无法移动，我方被将军了！";

    /** 自定义步法音效名，定义在资源包 {@code sounds/sound_definitions.json}。 */
    private static final String SND_MOVE = "tabletopgame.xiangqi.move";
    private static final String SND_CAPTURE = "tabletopgame.xiangqi.capture";
    private static final String SND_CHECK = "tabletopgame.xiangqi.check";

    /**
     * 棋盘方块顶面高度。
     * <p>
     * 棋盘分块几何体 {@code xiangqi_board_segment_*} 的立方体为 {@code origin [-8, 0, -8]}、
     * {@code size [16, 2, 16]}，即板面从 y=0 铺到 y=2 模型单位（2/16 世界单位），与方块的碰撞箱一致。
     * 棋子几何体的骨骼以 2 模型单位作为底面基准，所以单个棋子实体就生成在棋盘方块高度上。
     */
    public static final double BOARD_TOP = 2.0 / 16.0;

    // ==================== 贴图网格 → 世界坐标 ====================
    // 贴图 xiangqi_board_surface.png 为 256x256，铺满 2x2 方块：
    //   2x2 方块 = 32 模型单位 = 2 世界单位 → 128 px = 1 世界单位。
    // 实测网格线像素中心（含外框内侧的第一条线；2026-10-16 对拍取实测均值相位）：
    //   竖线 x = 22.5 + 25.875 * col   (col 0..8，22.5 与 229.5 为最左/最右竖线像素中心)
    //   横线 y = 13.5 + 25.3333 * row  (row 0..9，13.5 与 241.5 为最上/最下横线像素中心)
    // 两轴格距不同（象棋网格非正方形）。
    // 棋子几何体（tabletopgame_xiangqi_piece.geo.json）每个骨骼的枢轴就落在这些交点上，
    // 由下面的 cellToWorld 换算而来，两端必须保持一致。
    private static final double PX_PER_UNIT = 128.0;
    private static final double TEX_CENTER = 128.0;
    // 2026-10-16 网格对拍：实测线位相位比旧值整体偏 -0.5px（列 22.5..229.5、行 13.5..241.5），
    // 旧 23.0/14.0 会让选框与棋子在盘面中部偏出 1~1.4px。取实测均值相位。
    private static final double COL_X0 = 22.5;
    private static final double COL_STEP_PX = 25.875;
    private static final double ROW_Y0 = 13.5;
    private static final double ROW_STEP_PX = (241.5 - 13.5) / 9.0;

    /** 棋子贴图编号：与资源包调色板 xiangqi_palette.png 中自上而下的 14 张图一一对应。 */
    public static final int TEX_RED_JU = 0;
    public static final int TEX_RED_MA = 1;
    public static final int TEX_RED_XIANG = 2;
    public static final int TEX_RED_SHI = 3;
    public static final int TEX_RED_SHUAI = 4;
    public static final int TEX_RED_PAO = 5;
    public static final int TEX_RED_BING = 6;
    public static final int TEX_BLACK_JU = 7;
    public static final int TEX_BLACK_MA = 8;
    public static final int TEX_BLACK_XIANG = 9;
    public static final int TEX_BLACK_SHI = 10;
    public static final int TEX_BLACK_JIANG = 11;
    public static final int TEX_BLACK_PAO = 12;
    public static final int TEX_BLACK_ZU = 13;

    /** 空格标记。 */
    private static final int EMPTY = -1;

    /** 红方（先手）棋色编码。 */
    public static final int COLOR_RED = 0;
    /** 黑方棋色编码。 */
    public static final int COLOR_BLACK = 1;

    // ==================== 客户端槽位布局 ====================
    // 与资源包骨骼 slot_{color}_{type}_{i} 的生成顺序严格一致：
    // 颜色优先（红后黑），颜色内按 车、马、象、士、帅、炮、兵 排列，同种按序号 0..n-1。
    // 棋子类型编码与贴图编号对 7 取模同序（0=车、1=马、2=象、3=士、4=帅、5=炮、6=兵）。
    /** 每种棋子（单色）的槽位数量，下标为棋子类型编码。 */
    private static final int[] TYPE_SLOTS = {2, 2, 2, 2, 1, 2, 5};
    /** 某棋种在单色 16 槽内的起始偏移。 */
    private static final int[] TYPE_OFFSET = buildTypeOffset();
    /** 单色槽位数：车2 马2 象2 士2 帅1 炮2 兵5 = 16。 */
    public static final int SLOTS_PER_COLOR = 16;

    private static int[] buildTypeOffset() {
        int[] out = new int[TYPE_SLOTS.length];
        int acc = 0;
        for (int i = 0; i < TYPE_SLOTS.length; i++) {
            out[i] = acc;
            acc += TYPE_SLOTS[i];
        }
        return out;
    }

    /** 棋子贴图编号 → 单色槽位起始下标（{@code color*16 + offset[type]}）。 */
    private static int slotStart(int tex) {
        return colorOf(tex) * SLOTS_PER_COLOR + TYPE_OFFSET[tex % 7];
    }

    /**
     * 标准象棋初始局面（红方在下、黑方在上），{@code [row][col]}，值为贴图编号，-1 表示空。
     * <p>
     * row 0 在棋盘远端（远离放置者），row 9 在近端；黑方占 0/2/3 行，红方占 6/7/9 行。
     */
    private static final int[][] INITIAL = new int[ROWS][COLS];

    static {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                INITIAL[r][c] = EMPTY;
            }
        }
        // 黑方底线：车马象士将士象马车
        int[] back = {TEX_BLACK_JU, TEX_BLACK_MA, TEX_BLACK_XIANG, TEX_BLACK_SHI, TEX_BLACK_JIANG,
                TEX_BLACK_SHI, TEX_BLACK_XIANG, TEX_BLACK_MA, TEX_BLACK_JU};
        for (int c = 0; c < COLS; c++) {
            INITIAL[0][c] = back[c];
            INITIAL[9][c] = back[c] - TEX_BLACK_JU + TEX_RED_JU;
        }
        // 炮
        INITIAL[2][1] = TEX_BLACK_PAO;
        INITIAL[2][7] = TEX_BLACK_PAO;
        INITIAL[7][1] = TEX_RED_PAO;
        INITIAL[7][7] = TEX_RED_PAO;
        // 兵 / 卒
        for (int c = 0; c < COLS; c += 2) {
            INITIAL[3][c] = TEX_BLACK_ZU;
            INITIAL[6][c] = TEX_RED_BING;
        }
    }

    private final XiangqiManager manager;
    private final Level level;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    /** 棋盘朝向：0=南、1=西、2=北、3=东。 */
    private final int rotation;

    /** 棋盘中心（构造时算好并复用）。只读，调用方不得修改。 */
    private final Vector3 center;

    /** 当前棋局：{@code [row][col]}，值为贴图编号，-1 表示空。 */
    private final int[][] grid = new int[ROWS][COLS];

    /** 渲染整盘棋子的唯一实体。 */
    private XiangqiPieceEntity entity;

    // ==================== 对局状态 ====================

    /** 座位（按加入先后排序，index 0 为第一位加入者）。 */
    private final List<UUID> seats = new ArrayList<>(2);
    /** 各座位玩家选择的棋色：0 红 / 1 黑。 */
    private final Map<UUID, Integer> seatColors = new HashMap<>(2);
    /** 曾在该棋盘出现过的玩家名（UUID -> 名字），用于离线时仍能正确显示座位名。 */
    private final Map<UUID, String> knownNames = new LinkedHashMap<>(4);

    private boolean running;
    /** 当前该行棋的一方：红先行。 */
    private int turn = COLOR_RED;
    /** 选中格（0 = 选棋状态，否则 {@code row*9+col+1}）。 */
    private int sel;
    /** 光标格（0 = 无）。 */
    private int cur;
    /** 上一手起点格（0 = 无）。 */
    private int from;
    /** 上一手落点格（0 = 无），用于走子方在对方回合期间常驻的虚线标记。 */
    private int lastDest;
    private UUID winner;
    private boolean draw;
    /** 是否开过局（决定菜单是否显示「继承残局」）。 */
    private boolean started;

    /** 权威局面（走法合法性与重复局面判定的唯一来源）。 */
    private Position engine;

    /** 机器人棋色：-1 表示本局没有机器人。 */
    private int aiColor = -1;
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

    public XiangqiBoard(XiangqiManager manager, Level level, int baseX, int baseY, int baseZ, int rotation) {
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
        for (int r = 0; r < ROWS; r++) {
            System.arraycopy(INITIAL[r], 0, grid[r], 0, COLS);
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

    /** 当前该行棋的一方：0 红 / 1 黑。 */
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

    public static String colorName(int color) {
        return color == COLOR_BLACK ? "黑方" : "红方";
    }

    /** 贴图编号 → 棋色：红方贴图 0..6、黑方 7..13。 */
    private static int colorOf(int tex) {
        return tex < 7 ? COLOR_RED : COLOR_BLACK;
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

    /** 玩家所选棋色：0 红 / 1 黑，未入座为 -1。 */
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

    /** 本 tick 需要刷新高亮的座位（当前回合方）；未开局或无座位时为 null。 */
    public UUID hoverPlayerId() {
        if (!running) {
            return null;
        }
        return seatOfColor(turn);
    }

    /** 加入座位并选择棋色（0 红 / 1 黑）。 */
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
        if (color != COLOR_RED && color != COLOR_BLACK) {
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
            aiColor = -1;
            aiLevel = 0;
            aiThinking = false;
        }
        manager.markDirty();
        if (running) {
            if (gamblingForfeit && !seats.isEmpty()) {
                // 中途退出判负：剩余一方直接获胜，奖池按正常结算发放
                UUID winnerId = seats.get(0);
                running = false;
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
        return aiColor == COLOR_RED || aiColor == COLOR_BLACK;
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

    /** 当前是否轮到机器人行棋。 */
    public boolean isAiTurn() {
        return running && isAiGame() && turn == aiColor;
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
            seatAi(myColor == COLOR_RED ? COLOR_BLACK : COLOR_RED, level);
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
        if (color != COLOR_RED && color != COLOR_BLACK) {
            return false;
        }
        if (seats.size() >= 2) {
            return false;
        }
        seatColors.put(id, color);
        knownNames.put(id, player.getName());
        seatAi(color == COLOR_RED ? COLOR_BLACK : COLOR_RED, pendingAiLevel);
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
        aiColor = -1;
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
     * 按预设金额开局（赌博模式）：同时各扣该金额（余额已在入座时校验；扣款失败会回滚），最后开始对局（红先先行）。
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
        // 同时扣押注；任一失败则回滚已扣部分
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
        // 双方各自看到被扣除了多少，并告知奖池；余额被扣光者额外提醒破产
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
     *
     * @param reason 结算前缀（如中途退出的判定说明），null 表示普通获胜
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

    /**
     * 退还双方已扣的下注金额（和棋或对局中途终止时）。
     *
     * @return 是否确实退还过（用于决定提示文案）
     */
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

    /** 保留两位小数（四舍五入）。 */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 金额展示：固定两位小数，避免本地化差异。 */
    private static String formatMoney(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    // ==================== 对局流程 ====================

    /** 开始对局（清盘、红先手）。 */
    public void start() {
        if (running || seats.size() < 2) {
            return;
        }
        for (int r = 0; r < ROWS; r++) {
            System.arraycopy(INITIAL[r], 0, grid[r], 0, COLS);
        }
        running = true;
        started = true;
        turn = COLOR_RED;
        sel = 0;
        cur = 0;
        from = 0;
        lastDest = 0;
        winner = null;
        draw = false;
        aiThinking = false;
        pendingAiLevel = 0;
        pendingAiSeat = null;
        engine = buildEngine();
        pushAll();
        manager.markDirty();
        manager.closeSeatMenus(this);
        broadcastTip("§e中国象棋对局开始！红方先行。");
    }

    /**
     * 继承残局：保留当前局面与轮次，按「本来该谁走就谁走」继续。
     */
    public void inheritEndgame() {
        if (running || seats.size() < 2 || !started) {
            return;
        }
        running = true;
        sel = 0;
        cur = 0;
        winner = null;
        draw = false;
        aiThinking = false;
        engine = buildEngine();
        pushMarkers();
        manager.markDirty();
        manager.closeSeatMenus(this);
        broadcastTip("§e已继承残局，" + colorName(turn) + "继续行棋。");
    }

    /** 中途终止（有人退出 / 机器人无法行棋）：保留局面与轮次，便于之后「继承残局」。 */
    void abort(String reason) {
        running = false;
        aiThinking = false;
        sel = 0;
        cur = 0;
        pushMarkers();
        boolean refunded = refundStakes();
        manager.markDirty();
        broadcastTip("§c" + reason + (refunded ? "，下注金额已退还。" : ""));
    }

    // ==================== 选子 / 落子 ====================

    /**
     * 计算玩家准星命中的棋盘交点（1 基索引，0 表示未命中）。
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
        // 列轴：骨骼 model.x 随 col 增大（贴图 v 轴方向），世界方向 = P，取正投影。
        // 行轴：棋子骨骼按实测贴图行坐标生成，model.z = 14.25 - 3.1667*row，
        //   而实体模型 +Z 对应世界 -Z，故 row 增大 ⇒ 世界沿 +Q 方向。
        //   五子棋骨骼的行方向恰好相反（model.z 随 row 增大），所以不能照抄它的取负号，
        //   否则整盘行号会上下镜像（准星上移高亮下移、只能选到对方半场的棋子）。
        double a = dxc * TabletopGamesBoard.axisPX(p) + dzc * TabletopGamesBoard.axisPZ(p);
        double b = dxc * TabletopGamesBoard.axisQX(p) + dzc * TabletopGamesBoard.axisQZ(p);
        int col = (int) Math.round((a * PX_PER_UNIT + TEX_CENTER - COL_X0) / COL_STEP_PX);
        int row = (int) Math.round((b * PX_PER_UNIT + TEX_CENTER - ROW_Y0) / ROW_STEP_PX);
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) {
            return 0;
        }
        return row * COLS + col + 1;
    }

    /** 刷新光标格：仅在轮到本人且本人入座、在范围内时更新。 */
    public void updateHover(Player player) {
        if (!running) {
            return;
        }
        int color = seatColor(player.getUniqueId());
        if (color < 0 || color != turn) {
            return;
        }
        if (!inRange(player)) {
            return;
        }
        int aim = aimCell(player);
        if (aim != cur) {
            cur = aim;
            pushMarkers();
        }
    }

    /**
     * 右键棋盘的选子/落子状态机。
     *
     * @return 需要提示给玩家的文案；成功或静默时返回 {@code null}
     */
    public String select(Player player) {
        if (!running) {
            return null;
        }
        UUID id = player.getUniqueId();
        if (!seats.contains(id)) {
            return "§c你不是本局玩家。";
        }
        if (seatColor(id) != turn) {
            return "§e请等待对手行棋。";
        }
        int cell = aimCell(player);
        if (cell == 0) {
            return "§c请将准星对准棋盘上的交叉点。";
        }
        int row = (cell - 1) / COLS;
        int col = (cell - 1) % COLS;
        int tex = grid[row][col];
        if (sel == 0) {
            if (tex != EMPTY && colorOf(tex) == turn) {
                sel = cell;
                cur = cell;
                pushMarkers();
            } else {
                return "§c请先选择己方棋子。";
            }
        } else {
            if (tex != EMPTY && colorOf(tex) == turn) {
                // 点自身棋子 → 回到选棋状态
                sel = 0;
                cur = cell;
                pushMarkers();
            } else {
                String error = playerMove(sel, cell);
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }

    /** 尝试走子（含走后自将/将帅照面的完整合法性判定），供机器人调用。成功返回 {@code true}。 */
    public boolean tryMove(int fromIdx, int toIdx) {
        return playerMove(fromIdx, toIdx) == null;
    }

    /**
     * 玩家走子，返回需要提示给玩家的文案（{@code null} = 成功）。
     * <p>
     * 失败分两类：受将军限制（走后会被将军、将帅照面，或当前正被将军而该步无法解将）时
     * 统一提示 {@link #MSG_CHECK}；其余几何形态非法（马蹩腿、炮翻山、象塞眼等）提示普通文案。
     */
    public String playerMove(int fromIdx, int toIdx) {
        if (!running || engine == null || fromIdx <= 0 || toIdx <= 0) {
            return "§c落点不合法。";
        }
        int fromRow = (fromIdx - 1) / COLS;
        int fromCol = (fromIdx - 1) % COLS;
        int toRow = (toIdx - 1) / COLS;
        int toCol = (toIdx - 1) % COLS;
        if (grid[fromRow][fromCol] == EMPTY) {
            return "§c落点不合法。";
        }
        int mv = Position.MOVE(
                Position.COORD_XY(fromCol + Position.FILE_LEFT, fromRow + Position.RANK_TOP),
                Position.COORD_XY(toCol + Position.FILE_LEFT, toRow + Position.RANK_TOP));
        if (!engine.legalMove(mv)) {
            // 正被将军时，「这一步不能解将」才是根本原因，给将军提示
            return engine.checked() ? MSG_CHECK : "§c落点不合法。";
        }
        if (!engine.makeMove(mv)) {
            // 几何合法，但走后自将 / 将帅照面（makeMove 内部已回滚）
            return MSG_CHECK;
        }
        applyMove(fromRow, fromCol, toRow, toCol);
        return null;
    }

    /** 落子核心：写盘面、刷新实体、判定终局并换手。玩家与机器人共用。 */
    private void applyMove(int fromRow, int fromCol, int toRow, int toCol) {
        int moverColor = colorOf(grid[fromRow][fromCol]);
        boolean capture = grid[toRow][toCol] != EMPTY;
        grid[toRow][toCol] = grid[fromRow][fromCol];
        grid[fromRow][fromCol] = EMPTY;
        sel = 0;
        cur = 0;
        from = fromRow * COLS + fromCol + 1;
        lastDest = toRow * COLS + toCol + 1;
        // 与引擎一致：吃子后重置半回合计数（同时截断重复局面历史）
        if (capture && engine != null) {
            engine.setIrrev();
        }
        // 走后轮到对方，故 checked() 判定的是「对手被将军」；将军优先于吃子
        boolean check = engine != null && engine.checked();
        playBoardSound(check ? SND_CHECK : (capture ? SND_CAPTURE : SND_MOVE));
        if (entity != null && !entity.isClosed()) {
            entity.beginBatch();
            pushPieces(entity);
            entity.setSel(0);
            entity.setCur(0);
            entity.setFrom(from);
            entity.setLast(lastDest);
            entity.setTurn(1 - moverColor);
            entity.endBatch();
        }
        manager.markDirty();

        turn = 1 - moverColor;
        if (engine.isMate()) {
            finish(moverColor);
            return;
        }
        int vlRep = engine.repStatus(3);
        if (vlRep > 0) {
            int vl = engine.repValue(vlRep);
            if (vl > Position.WIN_VALUE) {
                finish(1 - moverColor);
            } else if (vl < -Position.WIN_VALUE) {
                finish(moverColor);
            } else {
                finishDraw();
            }
            return;
        }
        if (engine.moveNum > 100) {
            finishDraw();
            return;
        }
        notifyTurn(moverColor, check, capture);
    }

    /** 机器人落子：把 {@code {起点行,起点列,终点行,终点列}} 应用到棋盘。 */
    public boolean aiApply(int[] mv) {
        if (mv == null || !running || turn != aiColor) {
            return false;
        }
        return tryMove(mv[0] * COLS + mv[1] + 1, mv[2] * COLS + mv[3] + 1);
    }

    private void finish(int winColor) {
        running = false;
        aiThinking = false;
        winner = seatOfColor(winColor);
        draw = false;
        settleWin(winColor, null);
        manager.markDirty();
        announceWin(winColor);
    }

    private void finishDraw() {
        running = false;
        aiThinking = false;
        winner = null;
        draw = true;
        manager.markDirty();
        broadcastTip(refundStakes() ? "§e本局和棋，下注金额已退还。" : "§e本局和棋。");
    }

    /**
     * 换手提示（玩家对局双方各一条，人机对战只提示玩家）。
     * <p>
     * 只替换提示的前半段：走后对手被将军时为「将军！」，吃到子时为「吃！」（将军优先），
     * 后半段（机器人思考中… / 轮到对手行棋 / 轮到你行棋）保持原样。
     */
    private void notifyTurn(int moverColor, boolean check, boolean capture) {
        UUID mover = seatOfColor(moverColor);
        String verb = check ? "将军！" : capture ? "吃！" : null;
        if (isAiGame()) {
            if (!isAiSeat(mover)) {
                tipTo(mover, "§c" + (verb != null ? verb : "落子成功，") + "机器人思考中…");
            }
            return;
        }
        UUID opponent = otherSeat(mover);
        tipTo(mover, "§c" + (verb != null ? verb : "落子成功，") + "轮到对手行棋");
        tipTo(opponent, "§c" + (verb != null ? verb : "对手落子完成，") + "轮到你行棋");
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

    /** 胜负播报：玩家对局与战胜大师级机器人全服播报，其余只提示当事人。 */
    private void announceWin(int winColor) {
        UUID winnerSeat = seatOfColor(winColor);
        String msg = "§6" + nameOf(winnerSeat) + " 在中国象棋对战中获胜！";
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

    // ==================== 引擎 ====================

    /** 依据当前盘面与轮次重建权威局面。 */
    private Position buildEngine() {
        Position pos = new Position();
        pos.clearBoard();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int tex = grid[row][col];
                if (tex == EMPTY) {
                    continue;
                }
                pos.addPiece(Position.COORD_XY(col + Position.FILE_LEFT, row + Position.RANK_TOP),
                        Position.SIDE_TAG(colorOf(tex)) + XiangqiAi.ENGINE_TYPE[tex % 7]);
            }
        }
        pos.sdPlayer = turn;
        pos.setIrrev();
        return pos;
    }

    /** 导出盘面副本，供机器人异步计算。 */
    public int[][] copyGrid() {
        int[][] copy = new int[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) {
            System.arraycopy(grid[r], 0, copy[r], 0, COLS);
        }
        return copy;
    }

    // ==================== 存档 ====================

    /** 导出盘面为 90 字符：{@code .} 表示空，其余为贴图编号的小写十六进制单字符。 */
    public String exportGrid() {
        StringBuilder sb = new StringBuilder(ROWS * COLS);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int tex = grid[r][c];
                sb.append(tex == EMPTY ? '.' : Character.forDigit(tex, 16));
            }
        }
        return sb.toString();
    }

    /** 从存档恢复盘面与轮次（无盘面数据时保持初始布局）。 */
    public void importState(int savedTurn, boolean savedStarted, String savedGrid) {
        if (savedGrid != null && savedGrid.length() >= ROWS * COLS) {
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    char ch = savedGrid.charAt(r * COLS + c);
                    grid[r][c] = ch == '.' ? EMPTY : Character.digit(ch, 16);
                }
            }
        }
        turn = savedTurn == COLOR_BLACK ? COLOR_BLACK : COLOR_RED;
        started = savedStarted;
        engine = buildEngine();
    }

    // ==================== 渲染 ====================

    /**
     * 落点 (col, row) 对应的世界坐标（棋盘网格交点）。
     * <p>
     * 列沿棋盘本地 +P 展开；行沿 -Q 展开——与棋子实体一致（实体模型 Z 轴与世界 Z 轴反向），
     * 因此贴图 v 增大对应世界 -Q 方向。
     * <p>
     * 该换算与 {@code tabletopgame_xiangqi_piece.geo.json} 里各骨骼的枢轴严格对应
     * （模型坐标 = 世界偏移 × 16），改动任一侧都必须同步。
     */
    public Vector3 cellToWorld(int col, int row) {
        int p = rotation;
        double a = (COL_X0 + COL_STEP_PX * col - TEX_CENTER) / PX_PER_UNIT;
        double b = (ROW_Y0 + ROW_STEP_PX * row - TEX_CENTER) / PX_PER_UNIT;
        return new Vector3(
                center.x + a * TabletopGamesBoard.axisPX(p) - b * TabletopGamesBoard.axisQX(p),
                center.y + BOARD_TOP,
                center.z + a * TabletopGamesBoard.axisPZ(p) - b * TabletopGamesBoard.axisQZ(p));
    }

    /**
     * 按 row-major 顺序枚举棋盘，把每枚棋子填入其棋种的下一个空槽位，写入客户端槽位属性。
     * <p>
     * 槽位分配是瞬时的：骨骼只反映「某棋种第 i 枚现在在哪一格」，不要求棋子与槽位长期绑定，
     * 因此每次整盘重算即可，无需维护增量状态。
     */
    private void pushPieces(XiangqiPieceEntity piece) {
        int[] cells = new int[XiangqiPieceEntity.SLOTS];
        int[] next = new int[TYPE_SLOTS.length * 2];
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int tex = grid[row][col];
                if (tex == EMPTY) {
                    continue;
                }
                int group = colorOf(tex) * TYPE_SLOTS.length + tex % 7;
                int idx = next[group]++;
                if (idx < TYPE_SLOTS[tex % 7]) {
                    cells[slotStart(tex) + idx] = row * COLS + col + 1;
                }
            }
        }
        piece.setSlots(cells);
    }

    /** 整盘属性 + 全部标记合并成一次同步。 */
    private void pushAll() {
        if (entity == null || entity.isClosed()) {
            return;
        }
        entity.beginBatch();
        pushPieces(entity);
        pushMarkers(entity);
        entity.endBatch();
    }

    /** 仅同步标记（选中 / 光标 / 上一手起点 / 上一手落点 / 行棋方）。 */
    private void pushMarkers() {
        if (entity == null || entity.isClosed()) {
            return;
        }
        entity.beginBatch();
        pushMarkers(entity);
        entity.endBatch();
    }

    /** 写入全部标记属性；调用方负责 batch 与 endBatch。 */
    private void pushMarkers(XiangqiPieceEntity piece) {
        piece.setSel(sel);
        piece.setCur(cur);
        piece.setFrom(from);
        piece.setLast(lastDest);
        piece.setTurn(turn);
    }

    /** 生成绘制整盘棋子的实体（已生成则跳过）。 */
    public void spawnPieces() {
        if (entity != null && !entity.isClosed()) {
            return;
        }
        Entity created = Entity.createEntity(XiangqiPieceEntity.IDENTIFIER,
                cn.nukkit.level.Position.fromObject(center, level));
        if (!(created instanceof XiangqiPieceEntity piece)) {
            return;
        }
        entity = piece;
        piece.setBoard(this);
        piece.setRotation(rotation * 90.0, 0.0);
        piece.beginBatch();
        pushPieces(piece);
        // 区块重载后一并恢复标记，避免选中/上一手标记丢失
        pushMarkers(piece);
        piece.endBatch();
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
        aiThinking = false;
        pendingAiLevel = 0;
        pendingAiSeat = null;
        menuHolder = null;
        refundStakes();
        seats.clear();
        seatColors.clear();
        knownNames.clear();
        engine = null;
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

    /** 本棋盘所用的方块（用于重放/掉落判断）。 */
    public static XiangqiBoardBlock newBlock(int position) {
        XiangqiBoardBlock block = new XiangqiBoardBlock();
        block.setPosition(position);
        return block;
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
                sb.append(color == COLOR_RED ? "§c（红）§f" : color == COLOR_BLACK ? "§0（黑）§f" : "§e（待选）§f")
                        .append(nameOf(id)).append("  ");
            }
        }
        sb.append("\n§7状态：§f");
        if (running) {
            sb.append("对局中，轮到").append(turn == COLOR_RED ? "§c红方" : "§0黑方").append("§7。");
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

    /** 座位显示名：机器人返回等级名，玩家离线时回退到曾记录的名字，最后才是 UUID 前缀。 */
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
}
