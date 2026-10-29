package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import com.tabletopgames.ai.TabletopGamesAi;
import com.tabletopgames.board.BaseTabletopGamesBoardBlock;
import com.tabletopgames.economy.EconomyHook;
import com.tabletopgames.item.LocatorService;
import com.tabletopgames.entity.TabletopGamesStoneEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个已放置的棋盘实例：负责座位、落子、胜负判定，并驱动棋子实体的渲染属性。
 */
public class TabletopGamesBoard {

    public static final int SIZE = 15;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    /** 机器人座位的哨兵 UUID（全 0），不对应任何真实玩家。 */
    public static final UUID AI_ID = new UUID(0L, 0L);

    /** 棋盘方块顶面高度（2 像素） */
    public static final double BOARD_TOP = 2.0 / 16.0;

    /** 规则：休闲（标准五子棋，五连即胜）。 */
    public static final int RULE_CASUAL = 0;
    /** 规则：Swap2（开局摆子 + 交换，长连不算胜）。 */
    public static final int RULE_SWAP2 = 1;

    /**
     * 棋盘朝向轴（单位向量，取值 -1/0/1）。
     * <p>
     * {@code AXIS_P*} 是棋盘本地 +X（列方向）在世界中的朝向，{@code AXIS_Q*} 是本地 +Z（行方向反向）在世界中的朝向；
     * 两者由放置时玩家视角决定：0=南（默认，未旋转）、1=西、2=北、3=东。
     * 棋盘 2x2/3x3 的区域即从放置点沿玩家左手边（P）与正前方（Q）延伸。
     */
    private static final int[] AXIS_PX = {1, 0, -1, 0};
    private static final int[] AXIS_PZ = {0, 1, 0, -1};
    private static final int[] AXIS_QX = {0, -1, 0, 1};
    private static final int[] AXIS_QZ = {1, 0, -1, 0};

    private static final int[] POW3 = new int[SIZE + 1];

    static {
        POW3[0] = 1;
        for (int i = 1; i <= SIZE; i++) {
            POW3[i] = POW3[i - 1] * 3;
        }
    }

    private final TabletopGamesManager manager;
    private final Level level;

    /** 落子音效名，定义在资源包 {@code sounds/sound_definitions.json}。 */
    private static final String SND_PLACE = "tabletopgame.gomoku.place";

    private final int baseX;
    private final int baseY;
    private final int baseZ;
    /** 棋盘边长（方块数）：2 = 标准 2x2 棋盘，3 = 中号 3x3 棋盘。网格始终为 15x15。 */
    private final int boardSize;

    /** 棋盘朝向：0=南、1=西、2=北、3=东。决定 2x2/3x3 区域相对玩家视角的延伸方向。 */
    private final int rotation;

    /** 棋盘中心（构造时算好并复用）。只读，调用方不得修改。 */
    private final Vector3 center;

    private TabletopGamesStoneEntity stoneEntity;

    /** 座位（按加入先后排序，index 0 为第一位加入者） */
    private final List<UUID> seats = new ArrayList<>(2);
    /** 各座位玩家选择的棋色 */
    private final Map<UUID, Integer> seatColors = new HashMap<>(2);
    /**
     * 曾在该棋盘出现过的玩家名（UUID -> 名字）。用于玩家离线后仍能正确显示座位名与「上一局胜者」，
     * 避免回退成 UUID 前缀（如 ff70ffc3）。入座与落子时刷新，随存档持久化。
     */
    private final Map<UUID, String> knownNames = new LinkedHashMap<>(4);
    private final int[][] grid = new int[SIZE][SIZE];

    /** 黑白已落子数：用于 O(1) 判满与判断先手，避免整盘扫描。 */
    private int blackStones;
    private int whiteStones;

    private boolean running;
    private int turn = BLACK;
    private int lastIndex;
    private int hoverIndex;
    private UUID winner;
    /** 上一局是否以「棋盘下满」平局收场（用于区分「未开始」与「已平局」）。 */
    private boolean draw;

    /**
     * 本棋盘状态是否自上次存档快照后发生过变更。存档只重建变脏棋盘的快照，
     * 未变的直接复用上次快照，避免每次落子都把所有棋盘重新编码一遍。
     */
    private boolean stateDirty = true;

    /** 机器人棋色（0 表示本局没有机器人）。 */
    private int aiColor;
    /** 机器人难度：1 入门 / 2 进阶 / 3 大师。 */
    private int aiLevel;
    /**
     * 玩家已选择「单人对战」但尚未选色时暂存的机器人等级（0 表示无）。
     * 该状态保存在棋盘上，中途关闭菜单再打开也不会丢失。
     */
    private int pendingAiLevel;
    /**
     * 已选择「单人对战」但尚未选色的玩家。该玩家在点「单人对战」时即已占座（棋色待定），
     * 因此对其他人而言棋盘已满员。
     */
    private UUID pendingAiSeat;
    /** 机器人是否正在思考，避免重复派发计算任务。 */
    private boolean aiThinking;

    /** 0 人入座时预设的「每局金额」（双方各自下注额；0 = 免费局，默认 0）。 */
    private double presetBet;
    /** 本局下注金额（未开局为 0）。 */
    private double betAmount;
    /** 已扣押注的座位 -> 金额；开局扣款时填入，结算/退还后清空。 */
    private final Map<UUID, Double> stakes = new HashMap<>(2);

    /** 棋盘规则（仅 0 人入座时可改）：RULE_CASUAL / RULE_SWAP2。 */
    private int rule = RULE_CASUAL;
    /** Swap2 开局阶段：0=非开局/已结束，1=假先手摆 3 子，2=假后手选择，3=假后手摆 2 子，4=假先手选择。 */
    private int swapPhase;
    /** 当前摆放阶段已落子数。 */
    private int swapPlaced;

    /** 0 人入座时正在操作菜单的玩家（同一时间只允许一人操作）。 */
    private UUID menuHolder;
    /** 菜单占用到期时间（毫秒时间戳）。 */
    private long menuHolderUntil;

    public TabletopGamesBoard(TabletopGamesManager manager, Level level, int baseX, int baseY, int baseZ,
                             int boardSize, int rotation) {
        this.manager = manager;
        this.level = level;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.boardSize = boardSize >= 4 ? 4 : boardSize == 3 ? 3 : 2;
        this.rotation = normalizeRotation(rotation);
        // 棋盘区域由 base 沿本地两轴展开：方块 (i,j) 的几何中心 = base + i*P + j*Q + (0.5, 0.5)。
        // 对全部 i,j 取平均得区域中心 = base + 0.5 + (boardSize-1)/2 * (P + Q)。
        // 棋子实体模型的网格以实体原点为中心（±0.875 格），必须落在区域中心才能与方块贴图对齐。
        // 旧式写法 base + (boardSize/2)*(P+Q) 只在 rotation=0（P/Q 均取正方向）时偶然相等，
        // 其余朝向会整体偏移一格（rotation=2 时恰好偏移一个对角线格）。
        double half = (this.boardSize - 1) / 2.0;
        this.center = new Vector3(
                baseX + 0.5 + half * (AXIS_PX[this.rotation] + AXIS_QX[this.rotation]),
                baseY,
                baseZ + 0.5 + half * (AXIS_PZ[this.rotation] + AXIS_QZ[this.rotation]));
    }

    /** 棋盘边长（方块数）：2 / 3 / 4。 */
    public int getBoardSize() {
        return boardSize;
    }

    /** 棋盘朝向：0=南、1=西、2=北、3=东（放置时按玩家视角确定）。 */
    public int getRotation() {
        return rotation;
    }

    public static int normalizeRotation(int rotation) {
        return ((rotation % 4) + 4) % 4;
    }

    public static int axisPX(int rotation) {
        return AXIS_PX[normalizeRotation(rotation)];
    }

    public static int axisPZ(int rotation) {
        return AXIS_PZ[normalizeRotation(rotation)];
    }

    public static int axisQX(int rotation) {
        return AXIS_QX[normalizeRotation(rotation)];
    }

    public static int axisQZ(int rotation) {
        return AXIS_QZ[normalizeRotation(rotation)];
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

    public String getKey() {
        return level.getName() + ":" + baseX + ":" + baseY + ":" + baseZ;
    }

    /** 棋盘中心。返回共享实例，调用方不得修改。 */
    public Vector3 getCenter() {
        return center;
    }

    public boolean isRunning() {
        return running;
    }

    /** 棋子实体是否缺失。区块卸载会关闭非玩家实体，需在玩家回到范围时重建。 */
    public boolean isEntityMissing() {
        return stoneEntity == null || stoneEntity.isClosed();
    }

    /**
     * 是否有玩家在棋盘范围内。仅在棋子实体缺失、需要判断是否重建时调用（低频），
     * 因此这里按需扫描本关卡玩家，命中即返回，不做每 tick 的全量维护。
     */
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
     * 本 tick 唯一需要看到高亮的玩家：普通回合为当前回合方，Swap2 摆放阶段为摆子方，
     * 选择阶段无人。返回 null 表示当前不需要刷新高亮。
     */
    public UUID hoverPlayerId() {
        if (!running) {
            return null;
        }
        if (swapPhase == 1 || swapPhase == 3) {
            return swapActor();
        }
        if (swapPhase == 2 || swapPhase == 4) {
            return null;
        }
        return seatOfColor(turn);
    }

    public List<UUID> getSeats() {
        return seats;
    }

    public boolean isSeated(UUID uuid) {
        return seats.contains(uuid);
    }

    public int seatCount() {
        return seats.size();
    }

    /** 标记本棋盘状态已变更：置位本棋盘脏标记，并通知管理器安排落盘。 */
    private void markDirty() {
        this.stateDirty = true;
        manager.markDirty();
    }

    /** 取出并清除「本棋盘是否需要重建存档快照」的标记。仅存档线程/主线程的 capture 调用。 */
    boolean consumeStateDirty() {
        boolean was = stateDirty;
        stateDirty = false;
        return was;
    }

    /** 玩家所选棋色：BLACK / WHITE，未入座为 0。 */
    public int seatColor(UUID uuid) {
        return seatColors.getOrDefault(uuid, 0);
    }

    /** 某棋色是否已被占用。 */
    public boolean isColorTaken(int color) {
        return seatColors.containsValue(color);
    }

    /** 是否为第一位加入者（用于决定开局先后手）。 */
    public boolean isFirstJoiner(UUID uuid) {
        return !seats.isEmpty() && seats.get(0).equals(uuid);
    }

    public static String colorName(int color) {
        return color == BLACK ? "黑棋" : "白棋";
    }

    public UUID getWinner() {
        return winner;
    }

    /** 上一局是否以平局收场。 */
    public boolean isDraw() {
        return draw;
    }

    /** 本局是否已分出胜负或平局（用于隐藏「继承棋局」等按钮，避免重复获胜/重复领奖）。 */
    public boolean isFinished() {
        return !running && (winner != null || draw);
    }

    /** 棋盘上是否已有棋子（用于决定是否显示「继承棋局」：空盘无可继承）。 */
    public boolean hasStones() {
        return blackStones + whiteStones > 0;
    }

    /** 本局预设金额（双方各自下注额；0 = 免费局，未设置时为 0）。 */
    public double getPresetBet() {
        return presetBet;
    }

    /** 本局下注金额（未开局为 0）。 */
    public double getBetAmount() {
        return betAmount;
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
        markDirty();
        player.sendTip("§7本局金额已设为 §e" + formatMoney(presetBet) + "§7"
                + (presetBet <= 0 ? "（免费局：不扣款）。" : "（开局时双方各扣除该金额）。"));
        return null;
    }

    /** 棋盘规则：RULE_CASUAL / RULE_SWAP2。 */
    public int getRule() {
        return rule;
    }

    /**
     * 设置规则：仅「0 人入座且未开局」时生效。
     *
     * @return 是否设置成功（含「原本就是该规则」）
     */
    public boolean setRule(int newRule) {
        if (seats.size() > 0 || running) {
            return false;
        }
        if (newRule != RULE_CASUAL && newRule != RULE_SWAP2) {
            return false;
        }
        if (rule == newRule) {
            return true;
        }
        rule = newRule;
        markDirty();
        return true;
    }

    public static String ruleName(int rule) {
        return rule == RULE_SWAP2 ? "Swap2" : "休闲";
    }

    /** 本局实际采用的规则：休闲与 Swap2 都支持，人机对战同样适用。 */
    public int effectiveRule() {
        return rule == RULE_SWAP2 ? RULE_SWAP2 : RULE_CASUAL;
    }

    /**
     * 尝试占用「0 人入座」时的菜单。同一时间只允许一人操作该棋盘，
     * 已入座或占用已超时则放行。
     *
     * @return 是否取得操作权
     */
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
        menuHolderUntil = now + menuLockMs();
        return true;
    }

    /** 释放菜单占用（关闭表单、入座、离线时调用）。 */
    public void releaseMenu(UUID id) {
        if (menuHolder != null && menuHolder.equals(id)) {
            menuHolder = null;
        }
    }

    public void releaseMenu(Player player) {
        releaseMenu(player.getUniqueId());
    }

    /**
     * 校验并续期「0 人入座」菜单占用权，供每个按钮动作在执行前调用。
     * <p>
     * 表单一旦弹出就无法从服务端强制收回，因此只在 {@link #tryAcquireMenu} 处校验是不够的：
     * 超时或被他人接管后，旧表单仍留在客户端上。这里在动作执行前再校验一次，
     * 保证「超时/被接管之后，原持有者点击旧表单不再生效」。
     *
     * @return 是否仍持有操作权；持有则顺带把占用续期，避免正在操作的人被超时踢掉
     */
    public boolean holdsMenu(Player player) {
        if (seats.size() > 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (menuHolder == null || !menuHolder.equals(id) || now >= menuHolderUntil) {
            return false;
        }
        menuHolderUntil = now + menuLockMs();
        return true;
    }

    /** 「0 人入座」菜单占用超时（毫秒），取自配置 {@code menuLockSeconds}（默认 30 秒）。 */
    private long menuLockMs() {
        return manager.getConfig().getMenuLockSeconds() * 1000L;
    }

    /**
     * 菜单占用的兜底清理（仅作用于「0 人入座」的表单）：持有者超时未操作时，
     * 自动关闭其客户端上的表单并释放占用，避免「开着表单走人」把菜单一直占住。
     * <p>
     * 不再做范围判定（打开表单时玩家无法移动，距离由玩家自己控制），
     * 下线判定改用 {@link Server#getPlayer(UUID)}：玩家离线后返回空；
     * 此外 {@link TabletopGamesManager#onQuit} 也会在退出事件里立即释放占用，二者互为兜底。
     * <p>
     * 由 {@link TabletopGamesManager#tick()} 每 tick 在主线程调用。
     */
    public void tickMenuHolder() {
        if (menuHolder == null) {
            return;
        }
        // 已有人入座：0 人菜单的占用锁不再需要（入座时也已释放），直接清空
        if (seats.size() > 0) {
            menuHolder = null;
            return;
        }
        Player holder = Server.getInstance().getPlayer(menuHolder).orElse(null);
        // 持有者已下线（或已被移除）：直接释放占用，让给下一位操作者
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

    // ==================== Swap2 开局 ====================

    /** Swap2 开局阶段。 */
    public int getSwapPhase() {
        return swapPhase;
    }

    /** 当前摆放阶段已落子数。 */
    public int getSwapPlaced() {
        return swapPlaced;
    }

    /** 是否处于 Swap2 开局流程中。 */
    public boolean isSwapOpening() {
        return swapPhase != 0;
    }

    /** 是否正在等待某一方做出 Swap2 开局选择（此时不能落子）。 */
    public boolean needsSwapDecision() {
        return swapPhase == 2 || swapPhase == 4;
    }

    /** 需要做出 Swap2 开局选择的座位；无则为 null。 */
    public UUID swapDecisionSeat() {
        if (swapPhase == 2) {
            return swapSecondSeat();
        }
        if (swapPhase == 4) {
            return swapFirstSeat();
        }
        return null;
    }

    /** Swap2 开局选择表单的说明文案。 */
    public String swapPrompt() {
        if (swapPhase == 2) {
            return "假先手已摆好 3 子（2 黑 1 白）。\n请选择…\n§e提示：可关闭界面查看棋盘，点击棋盘重新打开该界面。";
        }
        if (swapPhase == 4) {
            return "假后手已落下 2 子（1 白 1 黑）。\n请选择…\n§e提示：可关闭界面查看棋盘，点击棋盘重新打开该界面。";
        }
        return "";
    }

    /** 阶段 2 · 继续执白：假后手执白，白方落第 4 子后回归一人一手。 */
    public boolean swapTakeWhite() {
        if (swapPhase != 2) {
            return false;
        }
        assignColors(BLACK, WHITE);
        finishSwapOpening("假后手选择继续执白");
        return true;
    }

    /** 阶段 2 · 交换执黑：假后手改执黑，假先手执白落第 4 子后回归一人一手。 */
    public boolean swapTakeBlack() {
        if (swapPhase != 2) {
            return false;
        }
        assignColors(WHITE, BLACK);
        finishSwapOpening("假后手选择交换执黑，双方交换棋色");
        return true;
    }

    /** 阶段 2 · 落下 2 子：假后手再落 1 白 1 黑，随后由假先手选择。 */
    public boolean swapPlaceTwo() {
        if (swapPhase != 2) {
            return false;
        }
        swapPhase = 3;
        swapPlaced = 0;
        turn = WHITE;
        markDirty();
        broadcastTip("§e请假后手继续落下 2 子（先白后黑）。");
        return true;
    }

    /** 阶段 4 · 继续执黑：假先手保持执黑，白方落第 6 子后回归一人一手。 */
    public boolean swapKeepBlack() {
        if (swapPhase != 4) {
            return false;
        }
        assignColors(BLACK, WHITE);
        finishSwapOpening("假先手选择继续执黑");
        return true;
    }

    /** 阶段 4 · 交换执白：假先手改执白并落第 6 子，随后回归一人一手。 */
    public boolean swapTakeWhiteFinal() {
        if (swapPhase != 4) {
            return false;
        }
        assignColors(WHITE, BLACK);
        finishSwapOpening("假先手选择交换执白，双方交换棋色");
        return true;
    }

    /** 把假先手、假后手设为指定棋色。 */
    private void assignColors(int firstColor, int secondColor) {
        UUID first = swapFirstSeat();
        UUID second = swapSecondSeat();
        if (first != null) {
            seatColors.put(first, firstColor);
        }
        if (second != null) {
            seatColors.put(second, secondColor);
        }
        // Swap2 交换后机器人棋色可能改变，同步到 aiColor，否则后续换手判断会错
        if (isAiGame()) {
            int color = seatColor(AI_ID);
            if (color != 0) {
                aiColor = color;
            }
        }
    }

    /** 结束 Swap2 开局，进入正常一人一手，由白方落下一子。 */
    private void finishSwapOpening(String choice) {
        swapPhase = 0;
        swapPlaced = 0;
        turn = WHITE;
        markDirty();
        broadcastTip("§e" + choice + "，Swap2 开局完成，" + colorName(turn) + "落子。");
    }

    private UUID seatAt(int index) {
        return index >= 0 && index < seats.size() ? seats.get(index) : null;
    }

    /** Swap2 摆放阶段应由谁落子。 */
    private UUID swapActor() {
        if (swapPhase == 1) {
            return swapFirstSeat();
        }
        if (swapPhase == 3) {
            return swapSecondSeat();
        }
        return null;
    }

    /**
     * Swap2 假先手（开局执黑者，与休闲模式「黑先手」一致）。
     * <p>
     * 整个开局阶段（1–4）{@link #seatColors} 都还是入座时选的原始棋色——只有最后一步决策
     * 才调用 {@link #assignColors} 并立即结束开局，因此这里可以直接按棋色推导，无需额外持久化。
     */
    private UUID swapFirstSeat() {
        UUID black = seatOfColor(BLACK);
        return black != null ? black : seatAt(0);
    }

    /** Swap2 假后手（开局执白者）。 */
    private UUID swapSecondSeat() {
        UUID white = seatOfColor(WHITE);
        return white != null ? white : seatAt(1);
    }

    /** Swap2 摆放阶段下一子的颜色：阶段 1 为 黑-白-黑，阶段 3 为 白-黑。 */
    private int swapNextColor() {
        if (swapPhase == 1) {
            return swapPlaced == 1 ? WHITE : BLACK;
        }
        if (swapPhase == 3) {
            return swapPlaced == 0 ? WHITE : BLACK;
        }
        return turn;
    }

    /** 每局可下注的最小金额（来自配置）。 */
    public double getMinBet() {
        return manager.getConfig().getBetting().normalizedMinBet();
    }

    /** 每局可下注的最大金额（来自配置）。 */
    public double getMaxBet() {
        return manager.getConfig().getBetting().normalizedMaxBet();
    }

    /** 座位显示名（机器人返回等级名，离线玩家返回 UUID 前缀）。 */
    public String seatName(UUID id) {
        return nameOf(id);
    }

    /** 延后一 tick 执行，避免在表单回调中直接弹出下一个表单造成客户端窗口冲突。 */
    public void later(Runnable action) {
        manager.runLater(action);
    }

    /**
     * 按预设金额开局（赌博模式）：同时各扣该金额（余额已在入座时校验；扣款失败会回滚），最后开始对局（黑棋先手）。
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

    public int getTurn() {
        return turn;
    }

    /** 该 UUID 是否为机器人座位。 */
    public static boolean isAiSeat(UUID id) {
        return AI_ID.equals(id);
    }

    /** 本局是否为人机对战。 */
    public boolean isAiGame() {
        return aiColor == BLACK || aiColor == WHITE;
    }

    public int getAiColor() {
        return aiColor;
    }

    public int getAiLevel() {
        return aiLevel;
    }

    /** 当前是否轮到机器人行动（落子或做 Swap2 开局选择）。 */
    public boolean isAiTurn() {
        return isAiPlaceTurn() || needsAiSwapDecision();
    }

    /** 当前是否轮到机器人落子（含 Swap2 摆放阶段按固定棋色落子）。 */
    public boolean isAiPlaceTurn() {
        if (!running || !isAiGame()) {
            return false;
        }
        if (swapPhase == 1 || swapPhase == 3) {
            return AI_ID.equals(swapActor());
        }
        if (swapPhase == 2 || swapPhase == 4) {
            return false;
        }
        return turn == aiColor;
    }

    /** 当前是否轮到机器人做 Swap2 开局选择。 */
    public boolean needsAiSwapDecision() {
        return running && isAiGame() && needsSwapDecision() && AI_ID.equals(swapDecisionSeat());
    }

    /** 机器人本次落子的棋色：Swap2 摆放阶段按固定顺序（黑-白-黑 / 白-黑），正常对局为己方棋色。 */
    public int aiMoveColor() {
        if (swapPhase == 1 || swapPhase == 3) {
            return swapNextColor();
        }
        return aiColor;
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

    /** 机器人是否正在思考。 */
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

    /** 导出棋盘副本，供机器人异步计算，避免跨线程读取内部数组。 */
    public int[][] copyGrid() {
        int[][] copy = new int[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            System.arraycopy(grid[r], 0, copy[r], 0, SIZE);
        }
        return copy;
    }

    /** 是否已有人选择「单人对战」但尚未选色。 */
    public boolean hasPendingAi() {
        return pendingAiLevel > 0;
    }

    /** 该玩家是否已选择「单人对战」但尚未选色。 */
    public boolean isPendingAiSeat(UUID id) {
        return pendingAiLevel > 0 && id != null && id.equals(pendingAiSeat);
    }

    /**
     * 选择单人对战。
     * <p>
     * 玩家已选棋色时机器人立刻坐到对面；尚未选色时玩家先入座占位（棋色待定），
     * 等玩家选色后再让机器人坐到对面。状态保存在棋盘上，因此中途关闭菜单再打开仍停留在下一步。
     * <p>
     * 玩家一旦点选「单人对战」即视为入座，对其他人而言棋盘已满员。
     * <p>
     * 只有「自己是唯一座位」时才允许拉机器人入座：已入座者若旁边还有其他座位，
     * 说明对手已就位，此时机器人会挤进同一棋色，导致机器人与真人玩家能同时操作一方
     * （入座后未刷新的过期表单点击该按钮就会走到这里）。
     *
     * @return 棋盘状态是否发生变化（用于决定是否重新弹出菜单）
     */
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
        if (myColor != 0) {
            seatAi(myColor == BLACK ? WHITE : BLACK, level);
            tipTo(id, "§a对手已就位：" + aiName() + "（你执" + colorName(myColor) + "）。");
            return true;
        }
        // 未选色：先入座占位（棋色待选），避免他人插入
        if (!seats.contains(id)) {
            seats.add(id);
        }
        knownNames.put(id, player.getName());
        pendingAiLevel = level;
        pendingAiSeat = id;
        releaseMenu(id);
        markDirty();
        player.sendTip("§a已选择" + aiLevelName(level) + "机器人，请选择你的棋色。");
        return true;
    }

    /**
     * 已选单人对战、等待选色的玩家选择棋色：设定其棋色，并让机器人坐到对面。
     *
     * @return 是否成功（非待选色玩家或座位已满时返回 false）
     */
    public boolean chooseAiColor(Player player, int color) {
        UUID id = player.getUniqueId();
        if (pendingAiLevel == 0 || !id.equals(pendingAiSeat)) {
            return false;
        }
        if (color != BLACK && color != WHITE) {
            return false;
        }
        if (seats.size() >= 2) {
            return false;
        }
        seatColors.put(id, color);
        knownNames.put(id, player.getName());
        seatAi(color == BLACK ? WHITE : BLACK, pendingAiLevel);
        player.sendTip("§a对手已就位：" + aiName() + "（你执" + colorName(color) + "）。");
        return true;
    }

    /** 让机器人坐到指定棋色。 */
    private void seatAi(int color, int level) {
        seats.remove(AI_ID);
        seatColors.remove(AI_ID);
        seats.add(AI_ID);
        seatColors.put(AI_ID, color);
        aiColor = color;
        aiLevel = level;
        pendingAiLevel = 0;
        pendingAiSeat = null;
        markDirty();
    }

    /**
     * 退出单人对战：只移除机器人座位，玩家保持在座（棋色不变），回到「1 人入座」状态，
     * 之后可重新选择其他等级的机器人，或退出棋局。仅未开局的人机对局有效。
     *
     * @return 是否成功移除了机器人座位
     */
    public boolean exitAiBattle() {
        if (running || !isAiGame()) {
            return false;
        }
        seats.remove(AI_ID);
        seatColors.remove(AI_ID);
        aiColor = 0;
        aiLevel = 0;
        aiThinking = false;
        markDirty();
        return true;
    }

    /** 请求重新弹出本棋盘的菜单，供人机对战的分步表单使用。 */
    public void reopenMenu(Player player) {
        manager.reopenMenu(player, this);
    }

    /**
     * 应用机器人算出的落点。
     *
     * @return 落子后本局是否刚刚结束
     */
    public boolean aiPlace(int row, int col) {
        if (!running || !isAiGame()) {
            return false;
        }
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE || grid[row][col] != 0) {
            return false;
        }
        // Swap2 摆放阶段：机器人按固定棋色顺序落子；选择阶段必须先做决策，不能直接落子
        if (swapPhase == 1 || swapPhase == 3) {
            if (!AI_ID.equals(swapActor())) {
                return false;
            }
            return applyMove(row, col, swapNextColor(), aiName());
        }
        if (swapPhase == 2 || swapPhase == 4) {
            return false;
        }
        if (turn != aiColor) {
            return false;
        }
        return applyMove(row, col, aiColor, aiName());
    }

    /**
     * 机器人做出 Swap2 开局选择（由 {@link TabletopGamesAi#swapDecision} 给出选项）。
     *
     * @return 是否成功执行选择
     */
    public boolean aiSwapDecide() {
        if (!needsAiSwapDecision()) {
            return false;
        }
        int option = TabletopGamesAi.swapDecision(copyGrid(), aiLevel, swapPhase);
        if (swapPhase == 2) {
            switch (option) {
                case 1:
                    return swapTakeBlack();
                case 2:
                    return swapPlaceTwo();
                default:
                    return swapTakeWhite();
            }
        }
        return option == 1 ? swapTakeWhiteFinal() : swapKeepBlack();
    }

    /** 从磁盘恢复机器人设置。 */
    public void restoreAi(int level, int color) {
        if (color == BLACK || color == WHITE) {
            aiColor = color;
            aiLevel = level >= 1 && level <= 3 ? level : 1;
        }
    }

    /**
     * 加入座位并选择棋色。
     */
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
        // 入座后不再需要「0 人入座」菜单占用，立即释放
        releaseMenu(id);
        markDirty();
        tipTo(id, "§a你已加入对局（" + colorName(color) + "）。");
        // 座位变化后统一刷新所有座位玩家的表单：入座者直接跳转到等待界面，
        // 已在座的一方也会自动换成新状态，无需手动点击棋盘
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
        // 待选色的单人对战玩家离座：一并清除待选状态
        if (uuid.equals(pendingAiSeat)) {
            pendingAiLevel = 0;
            pendingAiSeat = null;
        }
        // Swap2 开局依赖双方就座，任一人离座即作废
        swapPhase = 0;
        swapPlaced = 0;
        // 人机对战中玩家退出，机器人一并撤离，避免留下无主的机器人座位
        if (isAiGame()) {
            seats.remove(AI_ID);
            seatColors.remove(AI_ID);
            aiColor = 0;
            aiLevel = 0;
            aiThinking = false;
        }
        markDirty();
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
                abort("有玩家退出，本局结束");
            }
        }
        // 座位变化后刷新仍在座玩家的表单：等待开局的一方会自动从「等待」回到「可开局」界面
        if (!seats.isEmpty()) {
            manager.refreshSeatMenus(this);
        }
        LocatorService.release(uuid);
        return true;
    }

    /**
     * 开始对局。统一由黑棋先手。
     */
    public void start() {
        if (running) {
            return;
        }
        if (seats.size() < 2) {
            return;
        }
        clearGrid();
        running = true;
        // 开局即关闭双方仍开着的表单：后入座的一方不再停留在「等待对方开始棋局」界面
        manager.closeSeatMenus(this);
        winner = null;
        draw = false;
        aiThinking = false;
        pendingAiLevel = 0;
        pendingAiSeat = null;
        setLast(0);
        setHover(0);
        swapPlaced = 0;
        if (effectiveRule() == RULE_SWAP2) {
            // Swap2：假先手先摆 3 子（黑-白-黑），摆完由假后手选择
            swapPhase = 1;
            turn = BLACK;
            markDirty();
            broadcastTip("§e五子棋对局开始！规则：Swap2。请假先手任意位置摆 3 子（2 黑 1 白）。");
            return;
        }
        swapPhase = 0;
        turn = BLACK;
        markDirty();
        broadcastTip("§e五子棋对局开始！" + colorName(turn) + "先手。");
    }

    /**
     * 继承棋盘上已有的棋子继续对局：不清空棋盘，先手由棋子数决定。
     * <p>
     * 黑棋先行，故黑白棋子数相等时轮到黑棋，黑棋多一子时轮到白棋。
     */
    public void resume() {
        if (running || seats.size() < 2) {
            return;
        }
        running = true;
        // 开局即关闭双方仍开着的表单：后入座的一方不再停留在「等待对方开始棋局」界面
        manager.closeSeatMenus(this);
        winner = null;
        draw = false;
        // 继承棋局视为正常一人一手，不再进入 Swap2 开局流程
        swapPhase = 0;
        swapPlaced = 0;
        turn = blackStones > whiteStones ? WHITE : BLACK;
        setHover(0);
        markDirty();
        broadcastTip("§e已继承棋局，" + colorName(turn) + "继续落子。");
    }

    private void abort(String reason) {
        running = false;
        aiThinking = false;
        swapPhase = 0;
        swapPlaced = 0;
        boolean refunded = refundStakes();
        broadcastTip("§c" + reason + (refunded ? "，下注金额已退还。" : ""));
    }

    private void clearGrid() {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                grid[r][c] = 0;
            }
        }
        blackStones = 0;
        whiteStones = 0;
        if (isEntityMissing()) {
            return;
        }
        // 15 个行属性合并成一次同步，避免清盘连发十几个元数据包
        beginBatch();
        pushRows();
        endBatch();
    }

    /**
     * 计算玩家准星命中的网格交点（1 基索引，0 表示未命中）。
     */
    public int aimCell(Player player) {
        if (player.getLevel() != this.level) {
            return 0;
        }
        Vector3 dir = player.getDirectionVector();
        if (Math.abs(dir.y) < 1e-6) {
            return 0;
        }
        Position pos = player.getPosition();
        double eyeX = pos.x;
        double eyeY = pos.y + player.getEyeHeight();
        double eyeZ = pos.z;

        double planeY = baseY + BOARD_TOP;
        double t = (planeY - eyeY) / dir.y;
        if (t <= 0 || t > 12) {
            return 0;
        }
        double hitX = eyeX + dir.x * t;
        double hitZ = eyeZ + dir.z * t;

        double step = lineStep();
        // 实体渲染时，网格交点 (col,row) 位于 center + step*((col-7)*P + (row-7)*(-Q))：
        //   列轴 = P；行轴 = -Q（实体模型 Z 轴与世界 Z 轴方向相反，行方向取 Q 的反向）。
        // 因此反解必须以棋盘中心为原点投影，而不是 base+margin（后者只在 rotation=0 时巧合成立，
        // 其余朝向会整体偏移若干格）。
        double dxc = hitX - center.x;
        double dzc = hitZ - center.z;
        double a = dxc * AXIS_PX[rotation] + dzc * AXIS_PZ[rotation];
        double b = -(dxc * AXIS_QX[rotation] + dzc * AXIS_QZ[rotation]);
        int half = (SIZE - 1) / 2;
        int col = (int) Math.round(a / step) + half;
        int row = (int) Math.round(b / step) + half;
        if (col < 0 || col >= SIZE || row < 0 || row >= SIZE) {
            return 0;
        }
        return row * SIZE + col + 1;
    }

    /** 15 条网格线跨越的长度（方块数）：标准棋盘 1.75，中号 2.625，大棋盘 3.5。 */
    private double gridSpan() {
        return boardSize * 0.875;
    }

    private double lineStep() {
        return gridSpan() / (SIZE - 1);
    }

    public void updateHover(Player player) {
        if (!running) {
            return;
        }
        // Swap2 摆放阶段：只有当前该摆子的一方能看到高亮
        if (swapPhase == 1 || swapPhase == 3) {
            UUID actor = swapActor();
            if (actor != null && actor.equals(player.getUniqueId())) {
                setHover(aimCell(player));
            }
            return;
        }
        // Swap2 选择阶段：等待弹窗选择，不显示高亮
        if (swapPhase == 2 || swapPhase == 4) {
            return;
        }
        int color = seatColor(player.getUniqueId());
        if (color == 0 || color != turn) {
            return;
        }
        setHover(aimCell(player));
    }

    /**
     * 尝试在准星命中的交点落子。
     *
     * @return 落子后本局是否刚刚结束（胜/负/和）
     */
    public boolean place(Player player, int index) {
        if (!running) {
            return false;
        }
        // 顺带记住名字，覆盖「旧存档恢复的座位」这类此前未记录过名字的情况
        String prevName = knownNames.put(player.getUniqueId(), player.getName());
        if (!player.getName().equals(prevName)) {
            markDirty();
        }
        if (index <= 0) {
            player.sendTip("§c请将准星对准棋盘上的网格交点。");
            return false;
        }
        int row = (index - 1) / SIZE;
        int col = (index - 1) % SIZE;
        if (grid[row][col] != 0) {
            player.sendTip("§c该位置已有棋子。");
            return false;
        }

        // Swap2 开局摆放阶段：由指定一方按固定棋色顺序落子（阶段 1 黑-白-黑，阶段 3 白-黑）
        if (swapPhase == 1 || swapPhase == 3) {
            UUID actor = swapActor();
            if (actor == null || !actor.equals(player.getUniqueId())) {
                return false;
            }
            return applyMove(row, col, swapNextColor(), player.getName());
        }
        // Swap2 开局选择阶段：需先通过弹窗选择，不能直接落子
        if (swapPhase == 2 || swapPhase == 4) {
            player.sendTip("§e请先在弹窗中完成 Swap2 开局选择。");
            return false;
        }

        int color = seatColor(player.getUniqueId());
        if (color == 0) {
            player.sendTip("§c你不在座位上，无法落子。");
            return false;
        }
        if (color != turn) {
            return false;
        }

        return applyMove(row, col, color, player.getName());
    }

    /**
     * 落子核心：写入棋盘、刷新实体、判定胜负并换手。玩家与机器人共用。
     *
     * @param actorName 落子者名称，用于播报
     * @return 本局是否结束
     */
    private boolean applyMove(int row, int col, int color, String actorName) {
        grid[row][col] = color;
        if (color == BLACK) {
            blackStones++;
        } else {
            whiteStones++;
        }
        markDirty();
        playBoardSound(SND_PLACE);

        boolean win = checkWin(row, col, color);
        boolean full = !win && isFull();

        // 本步的属性变更（行编码、最后一手、清除高亮）合并成一次同步，整步只发一个元数据包
        boolean hasEntity = !isEntityMissing();
        if (hasEntity) {
            beginBatch();
        }
        pushRows();
        setLast(row * SIZE + col + 1);
        setHover(0);
        if (hasEntity) {
            endBatch();
        }

        if (win) {
            running = false;
            winner = seatOfColor(color);
            settleWin(color, null);
            announceWin(color, actorName);
            return true;
        }
        if (full) {
            running = false;
            winner = null;
            draw = true;
            broadcastTip(refundStakes()
                    ? "§e棋盘已满，本局平局，下注金额已退还。"
                    : "§e棋盘已满，本局平局。");
            return true;
        }

        advanceTurn(color);
        notifyTurn(color);
        return false;
    }

    /**
     * 落子后的换手提示（仅正常一人一手阶段，Swap2 开局阶段由专门提示说明）。
     * <p>
     * 玩家对局：刚下完的一方收到「落子成功 轮到对手落子」，另一方收到「对手落子完成，轮到你行棋」。
     * 人机对战：只有玩家下完时提示「落子成功 机器人思考中…」，机器人下完不再提示。
     */
    private void notifyTurn(int moverColor) {
        if (swapPhase != 0) {
            return;
        }
        UUID mover = seatOfColor(moverColor);
        if (isAiGame()) {
            if (!isAiSeat(mover)) {
                tipTo(mover, "§c落子成功，机器人思考中…");
            }
            return;
        }
        UUID opponent = otherSeat(mover);
        tipTo(mover, "§c落子成功，轮到对手落子");
        tipTo(opponent, "§c对手落子完成，轮到你行棋");
    }

    /**
     * 换手。正常对局直接轮转棋色；Swap2 开局阶段按预设顺序推进，
     * 摆子阶段结束后进入对应的选择阶段。
     */
    private void advanceTurn(int color) {
        if (swapPhase == 1) {
            swapPlaced++;
            if (swapPlaced >= 3) {
                swapPhase = 2;
                turn = WHITE;
                markDirty();
                broadcastTip("§e假先手摆子完成，等待假后手选择。");
            } else {
                turn = swapNextColor();
            }
            return;
        }
        if (swapPhase == 3) {
            swapPlaced++;
            if (swapPlaced >= 2) {
                swapPhase = 4;
                turn = WHITE;
                markDirty();
                broadcastTip("§e假后手摆子完成，等待假先手选择。");
            } else {
                turn = swapNextColor();
            }
            return;
        }
        turn = color == BLACK ? WHITE : BLACK;
    }

    /**
     * 胜负播报。
     * <p>
     * 玩家对局与「战胜大师级机器人」全服播报：同时发聊天栏广播与全服提示条（两种提示）；
     * 战胜入门/进阶机器人、或机器人获胜，只给当事玩家发提示条。
     */
    private void announceWin(int color, String actorName) {
        String msg = "§6" + actorName + " 在与 " + opponentNameOf(color) + " 的五子棋对战中获胜！";
        if (!isAiGame()) {
            announceToAll(msg);
            return;
        }
        boolean playerWon = color != aiColor;
        if (playerWon && aiLevel == 3) {
            announceToAll(msg);
            return;
        }
        // 其余结果（战胜入门/进阶机器人，或机器人获胜）：只让本人看到
        UUID target = isAiSeat(winner) ? otherSeat(winner) : winner;
        tipTo(target, msg);
    }

    /** 全服播报：聊天栏广播 + 全服提示条，两种提示同时发出。 */
    private static void announceToAll(String msg) {
        Server.getInstance().broadcastMessage(msg);
        broadcastTipAll(msg);
    }

    private boolean isFull() {
        return blackStones + whiteStones >= SIZE * SIZE;
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
        double cut = manager.getConfig().getBetting().normalizedCut();
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
     * 退还双方已扣的下注金额（平局或对局中途终止时）。
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

    /**
     * 把棋盘状态按行编码下发给黑白两个棋子实体。
     * <p>
     * 每行 15 格、每格 0/1/2（0 空、1 黑、2 白）三进制打包：{@code Σ cell[c]*3^c}。
     * 黑白实体各自解码同一份行编码，黑实体据此显示黑子、白实体据此显示白子。
     */
    private void pushRows() {
        if (isEntityMissing()) {
            return;
        }
        int[] rows = exportRows();
        for (int r = 0; r < SIZE; r++) {
            stoneEntity.setRow(r, rows[r]);
        }
    }

    /** 批量提交包住整盘刷新：整批只发一个元数据包。 */
    private void beginBatch() {
        stoneEntity.beginBatch();
    }

    private void endBatch() {
        stoneEntity.endBatch();
    }

    private void setHover(int index) {
        if (hoverIndex == index) {
            return;
        }
        hoverIndex = index;
        if (stoneEntity != null && !stoneEntity.isClosed()) {
            stoneEntity.setHover(index);
        }
    }

    private void setLast(int index) {
        lastIndex = index;
        if (stoneEntity != null && !stoneEntity.isClosed()) {
            stoneEntity.setLast(index);
        }
    }

    private boolean checkWin(int row, int col, int color) {
        // Swap2 无禁手，但长连（6 子及以上）对双方都不算胜，对局继续
        boolean noOverline = effectiveRule() == RULE_SWAP2;
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            int count = 1;
            count += countDir(row, col, d[0], d[1], color);
            count += countDir(row, col, -d[0], -d[1], color);
            if (count == 5) {
                return true;
            }
            if (count > 5 && !noOverline) {
                return true;
            }
        }
        return false;
    }

    private int countDir(int row, int col, int dr, int dc, int color) {
        int count = 0;
        int r = row + dr;
        int c = col + dc;
        while (r >= 0 && r < SIZE && c >= 0 && c < SIZE && grid[r][c] == color) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
    }

    public void spawnEntity() {
        if (!isEntityMissing()) {
            return;
        }
        if (stoneEntity != null && !stoneEntity.isClosed()) {
            stoneEntity.close();
        }
        stoneEntity = null;
        Entity created = Entity.createEntity(TabletopGamesStoneEntity.IDENTIFIER,
                Position.fromObject(getCenter(), level));
        if (!(created instanceof TabletopGamesStoneEntity stone)) {
            return;
        }
        this.stoneEntity = stone;
        stone.setBoard(this);
        // 棋子实体整体随棋盘朝向旋转，使棋子落点与方块贴图网格对齐
        stone.setRotation(rotation * 90.0, 0.0);
        // 边长先设好，让生成包就带正确尺寸；此时还没有观察者，不会额外发包
        stone.setBoardSize(boardSize);
        stone.spawnToAll();
        // 15 个行属性合并成一次同步，开局只多发一个包
        stone.beginBatch();
        pushRows();
        // 重建时恢复「最后一手」与高亮标记，避免区块重载后标记丢失
        stone.setLast(lastIndex);
        stone.setHover(hoverIndex);
        stone.endBatch();
    }

    public void remove() {
        running = false;
        aiThinking = false;
        pendingAiLevel = 0;
        pendingAiSeat = null;
        swapPhase = 0;
        swapPlaced = 0;
        menuHolder = null;
        knownNames.clear();
        refundStakes();
        if (stoneEntity != null && !stoneEntity.isClosed()) {
            stoneEntity.close();
        }
        stoneEntity = null;
        for (int i = 0; i < boardSize; i++) {
            for (int j = 0; j < boardSize; j++) {
                Vector3 p = new Vector3(
                        baseX + i * AXIS_PX[rotation] + j * AXIS_QX[rotation], baseY,
                        baseZ + i * AXIS_PZ[rotation] + j * AXIS_QZ[rotation]);
                if (level.getBlock(p) instanceof BaseTabletopGamesBoardBlock) {
                    level.setBlock(p, cn.nukkit.block.Block.get(cn.nukkit.block.Block.AIR), true);
                }
            }
        }
    }

    /**
     * 导出每行的三进制编码（与实体属性同构），用于持久化落子状态。
     */
    public int[] exportRows() {
        int[] rows = new int[SIZE];
        for (int r = 0; r < SIZE; r++) {
            int value = 0;
            for (int c = 0; c < SIZE; c++) {
                value += grid[r][c] * POW3[c];
            }
            rows[r] = value;
        }
        return rows;
    }

    /**
     * 从磁盘数据恢复棋盘状态：座位、棋色、落子、对局进度与预设的每局金额。
     */
    public void restore(List<UUID> savedSeats, Map<UUID, Integer> savedColors,
                        Map<UUID, String> savedNames,
                        int[] rows, boolean savedRunning, int savedTurn, UUID savedWinner,
                        double savedBet, double savedPresetBet,
                        int savedRule, int savedSwapPhase, int savedSwapPlaced) {
        seats.clear();
        seats.addAll(savedSeats);
        seatColors.clear();
        seatColors.putAll(savedColors);
        knownNames.clear();
        if (savedNames != null) {
            knownNames.putAll(savedNames);
        }
        blackStones = 0;
        whiteStones = 0;
        for (int r = 0; r < SIZE; r++) {
            int value = r < rows.length ? rows[r] : 0;
            for (int c = 0; c < SIZE; c++) {
                int cell = value % 3;
                grid[r][c] = cell;
                if (cell == BLACK) {
                    blackStones++;
                } else if (cell == WHITE) {
                    whiteStones++;
                }
                value /= 3;
            }
        }
        running = savedRunning;
        turn = savedTurn == WHITE ? WHITE : BLACK;
        winner = savedWinner;
        // 存档不单独记录平局：未进行、无胜者且棋盘已满，即视为上一局平局
        draw = !running && winner == null && isFull();
        betAmount = savedBet > 0 ? savedBet : 0;
        presetBet = savedPresetBet > 0 ? savedPresetBet : 0;
        rule = savedRule == RULE_SWAP2 ? RULE_SWAP2 : RULE_CASUAL;
        // 未开局时不应残留 Swap2 开局阶段
        swapPhase = running ? savedSwapPhase : 0;
        swapPlaced = running ? savedSwapPlaced : 0;
        stakes.clear();
        // 重启前已扣押注：按座位重建，避免结算/退还时金额丢失
        if (running && betAmount > 0) {
            for (UUID id : seats) {
                stakes.put(id, betAmount);
            }
        }
    }

    /**
     * 判断给定方块坐标是否属于本棋盘。
     */
    public boolean contains(int x, int y, int z) {
        if (y != baseY) {
            return false;
        }
        int dx = x - baseX;
        int dz = z - baseZ;
        int u = dx * AXIS_PX[rotation] + dz * AXIS_PZ[rotation];
        int v = dx * AXIS_QX[rotation] + dz * AXIS_QZ[rotation];
        return u >= 0 && u < boardSize && v >= 0 && v < boardSize;
    }

    /**
     * 座位检测：以棋盘中心为圆心，水平方向 {@code playerRange} 格（默认 7）内视为在座。
     */
    public boolean inRange(Player player) {
        if (player.getLevel() != this.level) {
            return false;
        }
        double range = manager.playerRange();
        Vector3 c = center;
        return Math.abs(player.x - c.x) <= range
                && Math.abs(player.z - c.z) <= range
                && Math.abs(player.y - c.y) <= 12.0;
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

    /**
     * 表单正文。尾部提示按座位状态给出，因此需要传入查看者，具体规则见 {@link #appendStatusHint}。
     */
    public String statusText(Player viewer) {
        StringBuilder sb = new StringBuilder();
        sb.append("§7座位：§f");
        if (seats.isEmpty()) {
            sb.append("空");
        } else {
            for (UUID id : seats) {
                int color = seatColor(id);
                sb.append(color == BLACK ? "§e（黑）§f" : color == WHITE ? "§e（白）§f" : "§e（待选）§f")
                        .append(nameOf(id)).append("  ");
            }
        }
        sb.append("\n§7状态：§f");
        if (running) {
            sb.append("对局中，轮到").append(turn == BLACK ? "§0黑棋" : "§f白棋").append("§7。");
        } else if (winner != null) {
            sb.append("已结束，").append(nameOf(winner)).append(" 获胜。");
        } else if (draw) {
            sb.append("已结束，平局。");
        } else {
            sb.append("未开始。");
        }
        sb.append("\n§7规则：§f").append(ruleName(rule));
        appendStatusHint(sb, viewer);
        return sb.toString();
    }

    /**
     * 尾部提示行：
     * <ul>
     *     <li>0 人入座：玩法引导（加入他人对局 / 单人对战）</li>
     *     <li>1 人入座 / 两人（玩家）入座：提示可重新打开界面刷新当前状态</li>
     *     <li>两人入座中后加入的一方：额外提示等待对方开始棋局</li>
     *     <li>1 玩家 + 1 机器人的准备开始表单：不追加提示</li>
     * </ul>
     */
    private void appendStatusHint(StringBuilder sb, Player viewer) {
        if (seats.isEmpty()) {
            sb.append("\n\n§7提示：可加入他人对局，或直接选择「单人对战」挑战机器人。");
            return;
        }
        // 1 玩家 + 1 机器人的准备开始表单不需要刷新提示
        if (isAiGame()) {
            return;
        }
        if (seats.size() >= 2 && viewer != null && !isFirstJoiner(viewer.getUniqueId())) {
            sb.append("\n\n§e等待对方开始棋局");
        }
        sb.append("\n\n§e提示：可重新打开界面刷新当前状态");
    }

    /** 对局过程中的提示以「提示条（tip）」形式发给执棋的两位玩家。 */
    private void broadcastTip(String msg) {
        for (UUID id : seats) {
            tipTo(id, msg);
        }
    }

    /** 给单个玩家发送提示条（tip）；玩家离线或为机器人座位时忽略。 */
    private void tipTo(UUID id, String msg) {
        if (id == null) {
            return;
        }
        Player p = Server.getInstance().getPlayer(id).orElse(null);
        if (p != null) {
            p.sendTip(msg);
        }
    }

    /** 给全服在线玩家发送提示条（tip），用于需要全服可见的胜利播报。 */
    private static void broadcastTipAll(String msg) {
        for (Player p : Server.getInstance().getOnlinePlayers().values()) {
            p.sendTip(msg);
        }
    }

    /** 座位显示名：机器人返回其等级名，玩家离线时回退到曾记录的名字，最后才是 UUID 前缀。 */
    private String nameOf(UUID id) {
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

    /** 导出曾记录的玩家名副本（供存档线程格式化，避免跨线程读取内部 Map）。 */
    public Map<UUID, String> copyKnownNames() {
        return new LinkedHashMap<>(knownNames);
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

    /** 除给定座位外的另一个座位（用于人机对战中定位玩家）。 */
    private UUID otherSeat(UUID seat) {
        for (UUID id : seats) {
            if (!id.equals(seat)) {
                return id;
            }
        }
        return null;
    }

    /** 与指定棋色对阵的座位名称。 */
    private String opponentNameOf(int color) {
        for (Map.Entry<UUID, Integer> entry : seatColors.entrySet()) {
            if (entry.getValue() != color) {
                return nameOf(entry.getKey());
            }
        }
        return "对手";
    }
}