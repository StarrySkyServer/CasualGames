package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.player.PlayerTeleportEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.level.Sound;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.AnimateEntityPacket;
import com.tabletopgames.economy.EconomyHook;
import com.tabletopgames.board.DdzTableBlocks;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.entity.DdzFlyCardEntity;
import com.tabletopgames.entity.DdzTableEntity;
import com.tabletopgames.ai.AiThinkDispatcher;
import com.tabletopgames.game.ddz.DdzBotAi;
import com.tabletopgames.game.ddz.DdzCards;
import com.tabletopgames.game.ddz.DdzDifficulty;
import com.tabletopgames.game.ddz.DdzPattern;
import com.tabletopgames.game.ddz.DdzRolloutAi;
import com.tabletopgames.game.ddz.DdzRound;
import com.tabletopgames.game.ddz.DdzSettingsView;
import com.tabletopgames.game.ddz.DdzSettlement;
import com.tabletopgames.item.LocatorItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 斗地主牌桌运行时（对标 {@code UnoTable}，但三家 + 叫抢制 + 按名次结算）。
 *
 * <h2>职责</h2>
 * 座位管理、发牌（走独立飞牌实体）、叫抢 / 加倍 / 出牌三个阶段的推进、按钮与手牌交互、
 * 托管与时限、结算（结束时一次性扣 + 抽水）、续局投票、视角动画与准星同步。
 *
 * <h2>⚠️ 坐标系铁律（UNO 真机踩过南北镜像事故）</h2>
 * {@link DdzTableRay#OUT_X} / {@link #OUT_Z} 是**模型局部**向量：客户端实体 yaw=0 时
 * 局部 +z 对应世界 −z（X 不翻、<b>Z 翻</b>）。凡是拿它算**世界**坐标的地方
 * （{@link #standAtSeat}）必须写 {@code z = center.z - OUT_Z·R}。
 * 斗地主的座位顺序是 <b>0=南 1=北 2=东</b>（模组资源定的，没有西位）。
 *
 * <h2>⚠️ 卡槽号 ≡ 牌 id</h2>
 * 客户端 54 个 {@code card_NN} 的牌面贴图是写死的（{@code card_00} 取图集 {@code [0,0]}…），
 * 即「卡槽 NN 永远显示第 NN 张牌」。所以只能把「牌 id = X」放在「卡槽 X」，
 * 否则看到的牌面与真实手牌不符（真机症状：看到 4 个 3 却打不出炸弹）。
 * 同一张牌换阶段只换区值，卡槽号永不变；横向排列由客户端的 {@code v.sN} 决定。
 *
 * <h2>与 UNO 的刻意差异</h2>
 * <ol>
 *   <li><b>无系数体系</b>：四档底分（0/50/800/10000）固定，不可自定义；
 *       UNO 是「倍数 k × 手牌分」。</li>
 *   <li><b>按钮槽序相反</b>：模组槽 0 在最右（{@link DdzTableRay#BUTTON_GAP} 为负）。</li>
 *   <li><b>结算按名次而非手牌分</b>：地主的 {@code moneyDelta} 是 ±2×unit、农民 ∓unit。</li>
 * </ol>
 *
 * <p><b>经济口径与 UNO 完全一致</b>（用户 10-20 明确）：入座只校验余额门槛、
 * <b>不扣钱</b>；整局一分不动，到 {@link #settleEconomy} 才按输赢一次性结算 ——
 * 扣输家（到余额 0 为止）→ 汇奖池 → 抽水 → 给赢家。5 类棋才是「开局扣押金」。</p>
 */
public final class DdzGame {

    /** 牌桌级阶段（在 {@link DdzRound.Phase} 之外补两个桌级状态）。 */
    public enum TablePhase {
        /** 等待入座 / 点开始。 */
        WAITING,
        /** 发牌动画进行中（此时禁止一切操作）。 */
        DEALING,
        /** 对局进行中（叫抢 / 加倍 / 出牌由 {@link DdzRound} 管）。 */
        PLAYING,
        /** 结算展示中。 */
        SETTLED
    }

    // ---------------- 常量 ----------------

    /** 每秒表 tick 数（与 UNO 一致：5 游戏 tick 一次 tick）。 */
    private static final int TICKS_PER_SECOND = 4;
    /** 机器人 / 托管思考固定 2 秒。 */
    private static final int BOT_THINK_TICKS = 2 * TICKS_PER_SECOND;
    /** 视角动画刷新间隔（表 tick）。 */
    private static final int VIEWER_REFRESH = 10;
    /** 准星刷新间隔（表 tick）。 */
    private static final int HOVER_INTERVAL = 2;
    /** 旁观者隐藏牌面的广播半径（方块）。 */
    private static final double SPECTATOR_RANGE = 16.0;
    /**
     * 已经收到过 `hidden` 动画的旁观者。
     *
     * <p>2026-10-21：原来每 {@link #VIEWER_REFRESH} 轮都把同一条动画<b>重发</b>给半径内所有旁观者，
     * T 张桌 × P 个玩家就是 T×P 个包 —— 而客户端收到 AnimateEntityPacket 后状态会一直保留，
     * 重发纯属浪费。改成差量：只发给「这一轮新进入半径」的人，离开半径就从集合里移除
     * （再进来算新进入，会重新发）。扫描频率<b>不变</b>，所以「新旁观者多久能收到」还是
     * 原来的 2.5 秒，隐私窗口没有变大。</p>
     */
    private final java.util.Set<UUID> spectatorSent = new java.util.HashSet<>();
    /** 本轮在场的旁观者（复用，避免每轮 new 一个 Set）。 */
    private final java.util.Set<UUID> spectatorNow = new java.util.HashSet<>();
    /**
     * 坐着的玩家多久重发一次视角动画（单位 = 刷新轮次）。
     *
     * <p>视角动画是 `loop: true` 且只负责把 {@code v.viewer_mode} 写成自己的座位号，
     * 客户端收到后会一直保留 ⇒ **不需要每轮重发**。原来的写法每 2.5 秒给每个在座玩家
     * 重发一次，纯浪费。现在只在「首次 / 失效标记 / 每 8 轮（20 秒）兜底」时发。</p>
     */
    private static final int SEATED_RESEND_ROUNDS = 8;
    private int viewerRound;
    /** 每个座位最后一次收到视角动画的玩家；换人就必须重发（新入座的人不能等 20 秒）。 */
    private final UUID[] viewerSentTo = new UUID[3];
    /** 下一轮强制重发在座玩家的视角动画（座位变更 / 玩家复活 / 实体重建时置位）。 */
    private boolean forceViewerResend = true;
    /** 入座归位半径（格）。 */
    private static final double SEAT_STAND_RADIUS = 3.0;
    /** 站位视线俯角目标高度（格）。 */
    private static final double SEAT_FACE_TARGET_Y = 0.6;
    /** 站位被挡时向上找空位的最大格数。 */
    private static final int SEAT_SEEK_UP = 3;
    /** 结算展示时长（秒）。只影响播报节奏，**不影响收档**（见 {@link #tickSettled}）。 */
    private static final int SETTLE_TICKS = 8 * TICKS_PER_SECOND;

    // ---- 高级档推演参数（2026-10-22）----
    /** 首出的计算截止：实测该档最坏 5 s，取 4 s 覆盖绝大多数，剩下的用「当前最优」交卷。 */
    private static final int ADVANCED_LEAD_DEADLINE_MS = 4000;
    /** 跟牌的计算截止：实测 avg 79 ms / max 624 ms，1.5 s 足够。 */
    private static final int ADVANCED_FOLLOW_DEADLINE_MS = 1500;
    /** 每个候选采样几套对手手牌（单线推演很便宜，所以给得比 ratel 那套足）。 */
    private static final int ADVANCED_SAMPLES = 24;
    /** 「在算」超过这个 tick 数（5 秒）就强制放开闸门，防止线程异常导致回合永久卡住。 */
    private static final int AI_THINK_STALL_TICKS = 20;

    /**
     * 思考令牌：每次提交 AI 计算自增。计算回到主线程时若令牌已变（换人 / 重开 / 结算），
     * 这次结果直接丢弃 —— 绝不能在旧局面上落子。
     */
    private long thinkToken;
    /**
     * 「正在算」闸门。
     *
     * <p>异步结果要等下一个 tick 才回主线程，而这期间 {@code tickPlaying} 会再次调用
     * {@code autoAction}。没有这道闸门就会出现：每 tick 提交一次、上一份结果因令牌变了被丢弃，
     * 结果永远落不了子（回合卡死）。</p>
     */
    private boolean aiThinking;
    /** 已经在算的 tick 数；超过 {@link #AI_THINK_STALL_TICKS} 就强制放开闸门重新提交。 */
    private int aiThinkingTicks;
    /** 动作栏同一文本的重发间隔（表 tick）：2 秒，刚好赶在客户端淡出之前。 */
    private static final int TIP_REPEAT_TICKS = 2 * TICKS_PER_SECOND;
    /** 三家都不叫时的最大重发次数（超过就轮空，避免死循环刷屏）。 */
    private static final int MAX_REDEAL = 3;

    /** 座位中文名，索引即座位号（0=南 1=北 2=东）。 */
    public static final String[] SEAT_LABEL = {"南（P1）", "北（P2）", "东（P3）"};

    private static final int[] EMPTY_HAND = new int[0];
    private static final int[] EMPTY_BUTTONS = new int[0];

    /** 桌注册表：位置 key → 牌桌。key 形如 {@code "x,y,z,rot"}。 */
    private static final Map<String, DdzGame> TABLES = new LinkedHashMap<>();

    // ---------------- 基础字段 ----------------

    /**
     * 插件管理器（拿 {@code ddz} 配置节的唯一入口）。
     *
     * <p>⚠️ 这里<b>不能</b>存 {@code Plugin} 再调 {@code plugin.getConfig()} ——
     * 那是 Nukkit 原生配置，返回类型没有 {@code getDdz()}；与 UNO 侧
     * {@code UnoTable.manager} 同构，统一走 {@link TabletopGamesManager#ddzConfig()}。
     */
    private final TabletopGamesManager manager;
    private final Level level;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final int rotation;
    private final String key;
    private final Random random = new Random();

    /** 3x3 中心（世界坐标）。 */
    private final Vector3 center;
    /** 渲染实体原点（桌面顶面中心），射线与局部坐标基准。 */
    private final Vector3 origin;

    private DdzTableEntity visual;
    private TablePhase phase = TablePhase.WAITING;
    private DdzRound round;
    /** 本局开局时的配置快照（中途改配置不影响本局已定的底分/倍数上限/抽水）。 */
    private DdzSettingsView view;
    private DdzDifficulty difficulty = DdzDifficulty.ADVANCED;

    /**
     * 本桌的<b>经济场次</b>（免费 / 新手 / 高级 / 大师）。
     *
     * <p>⚠️ 用户 2026-10-20 要求：场次是<b>每张牌桌</b>可选的（像模组的「牌桌配置 → 经济场次」），
     * 不再是全局配置里的一个值。2026-10-22 用户再次确认：<b>新牌桌一律默认「免费场」</b>，
     * 不再读 {@code config.yml} 的 {@code ddz.tier}（线上那份是历史值 basic，会把默认盖掉）。</p>
     */
    private TabletopGamesConfig.DdzTier tier = TabletopGamesConfig.DdzTier.FREE;

    /**
     * 本桌的<b>定地主方式</b>（叫抢制 / 叫分制，用户 2026-10-24 要求）。
     *
     * <p>与场次同款：「牌桌配置」里改，改完<b>下一局</b>生效（本局的叫牌按钮组
     * 在 {@code startDeal} 那一刻就按它定了，中途改只影响下一局）。
     * 存在 {@code ddz_table_config.bid_mode}。</p>
     */
    private TabletopGamesConfig.DdzBidMode bidMode = TabletopGamesConfig.DdzBidMode.CALL_ROB;

    /**
     * 本桌的<b>玩法模式</b>（用户 2026-10-24 / 10-25 要求）。
     *
     * <p>三档：{@code OFF} 普通 / {@code WILD} 地赖子 / {@code HEAVEN} 天地癞子。
     * 与定地主方式同款：「玩法模式」里改，改完<b>下一局</b>生效
     * （本局的癞子点数在 {@code deal()} / {@code becomeLandlord()} 那一刻就抽定了）。
     * 存在 {@code ddz_table_config.wild_mode}（存枚举名，新增档位不用迁移）。</p>
     */
    private TabletopGamesConfig.DdzWildMode wildMode = TabletopGamesConfig.DdzWildMode.OFF;

    /**
     * 本桌的<b>手牌左右排序</b>（2026-10-25 用户要求）。
     *
     * <p>{@code BIG_LEFT}（默认，最大在左）/ {@code BIG_RIGHT}（最大在右，模组原版）。
     * 与场次 / 定地主方式 / 玩法模式不同，<b>它随时可改、立刻生效</b>——
     * 只影响画面，不参与任何规则或结算（{@link #setHandSort} 没有阶段守卫）。
     * 存在 {@code ddz_table_config.hand_sort}（存枚举名，老存档空串 ⇒ 大→小）。</p>
     */
    private TabletopGamesConfig.DdzHandSort handSort = TabletopGamesConfig.DdzHandSort.BIG_LEFT;

    /** 本局底分（快照自本桌场次）。 */
    private double baseScore;
    /** 机器人思考剩余 tick；0 = 不在思考。 */
    private int botTicks;
    /** 人类回合时限（表 tick）。 */
    private int actionLimit;
    /** 本回合已消耗的 tick。 */
    private int actionUsed;
    /**
     * 加倍阶段<b>共用</b>计时起点（表 tick 已消耗）。
     *
     * <p>用户 2026-10-20 要求：加倍阶段是<b>一个倒计时三家共用</b>，不是每家一份。
     * 阶段机是串行的（每家依次 {@code chooseDouble}），但计时必须从进入 DOUBLING
     * 那一刻连续走 —— {@link #beginTurn} 在 DOUBLING 分支不重置 {@link #actionUsed}。</p>
     */
    private int doublingUsed;
    /** 加倍阶段是否已起表（false = 还没进入 DOUBLING，或已出阶段）。 */
    private boolean doublingTiming;
    /** 每座位「加倍阶段已选完」标记（用于「自己搞定后不显示别人倒计时」）。 */
    private final boolean[] doubled = new boolean[3];
    /** 视角动画刷新计数。 */
    private int viewerTick;
    /** 准星刷新计数。 */
    private int hoverTick;
    /** 结算展示剩余 tick。 */
    private int settleTicks;
    /** 重发计数。 */
    private int redealCount;
    /** 属性脏标记。 */
    private boolean dirty;

    // ---------------- 座位 ----------------

    /** 每座位人类成员 UUID（null = 空位或机器人）。 */
    private final UUID[] humans = new UUID[3];
    /** 每座位在线玩家（null = 离线 / 机器人）。 */
    private final Player[] online = new Player[3];
    /** 每座位机器人难度（null = 不是机器人）。 */
    private final DdzDifficulty[] bots = new DdzDifficulty[3];
    /** 每座位显示名。 */
    private final String[] names = new String[3];
    /** 每座位是否托管（玩家在座但交给系统）。 */
    private final boolean[] trusted = new boolean[3];
    /** 每座位是否「中途离席、座位保留」。 */
    private final boolean[] away = new boolean[3];
    /** 每座位手牌选中位掩码（bit N = 第 N 张被选中；斗地主按展示序号选，不按卡槽）。 */
    private final int[] selected = new int[3];
    /** 每座位准星值。 */
    private final int[] hover = new int[3];
    // ---------------- 卡槽映射 ----------------

    /**
     * 每家<b>当前这一手</b>打出的牌 id（null = 没出 / 刚 pass）。
     *
     * <p>为什么要在牌桌侧再存一份：{@code round.play()} 成功后这些牌已经从手牌里删掉了，
     * 于是 {@link #syncTableZones()} 在重算卡槽时<b>找不到它们</b> —— 必须在独立的一份
     * 记录里把它们重新分配到出牌区。</p>
     */
    private final List<Integer>[] playedCards = new List[3];

    /**
     * 发牌动画剩余表 tick（{@link TablePhase#DEALING} 的倒计时）。
     *
     * <p>⚠️ 这里刻意<b>不是</b>「逐张推进的进度」：斗地主三家共发 51 张，而服务端一次
     * tick 就是 250ms —— 若按「每 tick 亮一张」走要 12.75 秒，比配置的 3.42 秒慢得多
     * （初版就踩了这个坑：{@code step*TICKS_PER_SECOND/1000} 在 50ms 时算成 0.2，
     * 被钳到 1，于是每 tick 只发一张）。真正的节奏归客户端的 {@code seq×0.2} 自走时钟
     * （{@link com.tabletopgames.entity.DdzFlyCardEntity#DEAL_STEP_SECONDS}），
     * 服务端只负责「等它飞完」。</p>
     */
    private int dealTicks = 1;
    /**
     * 底牌<b>已并入地主手牌</b>（定地主那一刻置 true）。
     *
     * <p>为什么不是「已翻开」：客户端 {@code card_NN} 在 {@code v.zN==7} 时
     * 硬编码 {@code rotation X = 180°}（整张牌翻面）⇒ <b>zone 7 永远显示牌背</b>，
     * 它天生就是「盖着的牌堆」。所以底牌在叫地主阶段就该摆上桌（盖着），
     * 定地主时这 3 张并入地主手牌、从牌堆消失（自然变成牌面，因为进了手牌区）。</p>
     *
     * <p>⚠️ 初版语义写反了（定地主之后才摆牌堆），结果是：叫地主阶段没有牌堆；
     * 而底牌并入地主手牌后又被单独摆一次 ⇒ 同一批牌被分配两次卡槽。</p>
     */
    private boolean bottomTaken;

    /** 续局投票：每座位是否已投。 */
    private final boolean[] nextVote = new boolean[3];
    /** 结算板文本（null = 不显示）。 */
    private String settleText;
    /** 动作栏提示（倒计时 / 结算）。 */
    private String countdownText;
    /** 事件提示占位剩余 tick。 */
    private int eventTipHold;
    private static final int EVENT_TIP_HOLD_TICKS = 2 * TICKS_PER_SECOND;

    /** 结算公开手牌：客户端 {@code debug_real} 把 {@code v.viewer_mode} 设成 3（手牌竖着翻开展示牌面）。 */
    private static final String ANIM_DEBUG_REAL = "animation.tabletopgame.ddz.debug_real";


    /** 在飞的飞牌实体 → 存活 tick。 */
    private final Map<DdzFlyCardEntity, Integer> flyAges = new LinkedHashMap<>();

    public DdzGame(TabletopGamesManager manager, Level level, int x, int y, int z, int rotation) {
        this.manager = manager;
        this.level = level;
        this.baseX = x;
        this.baseY = y;
        this.baseZ = z;
        this.rotation = rotation;
        this.key = tableKey(x, y, z, rotation);
        // 2026-10-22 用户要求：**新牌桌固定默认免费场**，不看 config.yml（线上还是 basic）。
        // 之后再在菜单「牌桌配置 → 经济场次」里改。
        this.tier = TabletopGamesConfig.DdzTier.FREE;
        this.bidMode = TabletopGamesConfig.DdzBidMode.CALL_ROB;
        // ⚠️ base 是 3x3 的**最小角**（placeBlocks 铺 x..x+2 / z..z+2），
        // 所以桌心必须 +1.5。初版这里写的是 +0.5（照搬了 UNO 的「base = 桌心」语义），
        // 结果整张牌桌实体（手牌 / 按钮 / 飞牌）全部**偏移一格**、朝左上（−x/−z）—— 真机一眼可见。
        this.center = new Vector3(x + 1.5, y + 0.5, z + 1.5);
        this.origin = new Vector3(x + 1.5, y + 15.0 / 16.0, z + 1.5);
    }

    public static String tableKey(int x, int y, int z, int rotation) {
        return x + "," + y + "," + z + "," + rotation;
    }

    public static DdzGame at(int x, int y, int z, int rotation) {
        return TABLES.get(tableKey(x, y, z, rotation));
    }

    /** 该 3x3 区域内是否已有牌桌（任意朝向）。 */
    public static DdzGame overlapping(int x, int y, int z) {
        for (DdzGame t : TABLES.values()) {
            if (Math.abs(t.baseX - x) <= 1 && t.baseY == y && Math.abs(t.baseZ - z) <= 1) {
                return t;
            }
        }
        return null;
    }

    public static Collection<DdzGame> all() {
        return TABLES.values();
    }

    /** 铺一张牌桌（3x3）。 */
    public static DdzGame create(TabletopGamesManager manager, Level level, int x, int y, int z, int rotation) {
        String k = tableKey(x, y, z, rotation);
        DdzGame exist = TABLES.get(k);
        if (exist != null) {
            return exist;
        }
        DdzGame t = new DdzGame(manager, level, x, y, z, rotation);
        TABLES.put(k, t);
        t.spawnEntity();
        return t;
    }

    /** 该坐标是否落在本桌 3x3 内。 */
    /**
     * 这个方块坐标是否属于本桌。
     *
     * <p>⚠️ base 是 3x3 的<b>最小角</b>，范围是 {@code base..base+2}（与
     * {@code DdzManager.placeBlocks} 一致）。初版这里写成
     * {@code Math.abs(x - baseX) <= 1}（= 以 base 为**中心** ±1），
     * 于是最外那一列/行（{@code base+2}）不属于任何桌 ⇒ 破坏它时
     * {@code findTable} 返回 null ⇒ 方块被当普通方块挖掉、其它 8 块留着（桌子不拆），
     * 点它也打不开菜单。</p>
     */
    public boolean contains(int x, int y, int z) {
        return x >= baseX && x < baseX + 3
                && z >= baseZ && z < baseZ + 3
                && y == baseY;
    }

    /** 玩家识别半径的兜底值（配置未加载时用，与 {@code DdzSettings.playerRange} 默认值一致）。 */
    private static final double DEFAULT_PLAYER_RANGE = 7.0;

    /** 玩家是否在识别半径内（**只算水平距离** —— 玩家跳一下就掉出范围显然不合理）。 */
    public boolean inRange(Player p) {
        if (p == null) {
            return false;
        }
        var cfg = ddzConfig();
        double r = cfg == null ? DEFAULT_PLAYER_RANGE : cfg.getPlayerRange();
        double dx = p.getX() - center.x;
        double dz = p.getZ() - center.z;
        return dx * dx + dz * dz <= r * r;
    }

    // ------------------------------------------------------------------
    // 座位访问
    // ------------------------------------------------------------------

    public String getKey() {
        return key;
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

    /** 牌桌朝向（0~3，玩家 yaw/90 取整）。 */
    public int getRotation() {
        return rotation;
    }

    public Vector3 getCenter() {
        return center;
    }

    public Vector3 getOrigin() {
        return origin;
    }

    public TablePhase getPhase() {
        return phase;
    }

    public DdzRound getRound() {
        return round;
    }

    public double getBaseScore() {
        return baseScore;
    }

    public static String seatLabel(int seat) {
        return (seat >= 0 && seat < SEAT_LABEL.length) ? SEAT_LABEL[seat] : "?";
    }

    public boolean isOccupied(int seat) {
        return seat >= 0 && seat < 3 && humans[seat] != null;
    }

    /** 在座真人数（不含机器人）；用于「桌上还有没有人在看」这类判断。 */
    public int occupiedSeats() {
        int n = 0;
        for (int s = 0; s < 3; s++) {
            if (humans[s] != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * 在座总数（真人 + 机器人）。
     *
     * <p>⚠️ 开局判据必须用它，不能用 {@link #occupiedSeats()}：那个只数真人，
     * 「1 真人 + 2 人机」会算成 1 ⇒ 拒绝开局；而放宽到 ≥2 又会让 2 人局真的开起来，
     * 第三家没人出牌直接卡死到超时。斗地主<b>必须是 3 家</b>。</p>
     */
    public int seatedCount() {
        int n = 0;
        for (int s = 0; s < 3; s++) {
            if (humans[s] != null || bots[s] != null) {
                n++;
            }
        }
        return n;
    }

    public boolean isBot(int seat) {
        return seat >= 0 && seat < 3 && bots[seat] != null;
    }

    public boolean hasBots() {
        for (int s = 0; s < 3; s++) {
            if (bots[s] != null) {
                return true;
            }
        }
        return false;
    }

    public int seatOf(UUID id) {
        for (int s = 0; s < 3; s++) {
            if (humans[s] != null && humans[s].equals(id)) {
                return s;
            }
        }
        return -1;
    }

    public boolean isSeated(UUID id) {
        return seatOf(id) >= 0;
    }

    public Player onlineAt(int seat) {
        return (seat >= 0 && seat < 3) ? online[seat] : null;
    }

    public String seatName(int seat) {
        return (seat >= 0 && seat < 3 && names[seat] != null) ? names[seat] : seatLabel(seat);
    }

    public boolean isTrusted(int seat) {
        return seat >= 0 && seat < 3 && trusted[seat];
    }

    public boolean isAway(int seat) {
        return seat >= 0 && seat < 3 && away[seat];
    }

    /** 该座位是否由系统代打（机器人 / 托管 / 离线 / 中途离席）。 */
    public boolean autoPlayed(int seat) {
        return seat >= 0 && seat < 3
                && (bots[seat] != null || online[seat] == null || trusted[seat] || away[seat]);
    }

    /** 在座真人数量（在线、非机器人、非托管）。 */
    public int humanCount() {
        int n = 0;
        for (int s = 0; s < 3; s++) {
            if (humans[s] != null && online[s] != null && !trusted[s] && !away[s]) {
                n++;
            }
        }
        return n;
    }

    /** 最早入座的真人（= 桌主，只有他能开局 / 加人机）。 */
    public boolean isFirstJoiner(UUID id) {
        for (int s = 0; s < 3; s++) {
            if (humans[s] != null) {
                return humans[s].equals(id);
            }
        }
        return false;
    }

    /**
     * 等待期 / 无人退出的结算期，开局按钮的文案：<b>打过一局且本局没人退出</b>才叫「下一局」，
     * 否则叫「开始游戏」（需求 2026-10-24，与 UNO {@code waitingButtonLabel()} 同一判据）。
     */
    public String waitingButtonLabel() {
        return roundNumber > 0 && !seatVacated ? "下一局" : "开始游戏";
    }

    /**
     * 自本局开局以来是否有人<b>退出</b>（与 UNO 的 {@code seatVacated} 同口径）：
     * 主动离席 / 断线 / 走出识别半径（对局中表现为「系统代打」，座位仍保留）/ 手动清除人机
     * 都算退出；「入座 / 补人机」只是占位，<b>不算</b>。
     *
     * <p>每局开局清零，两个用途：
     * <ul>
     *   <li>{@link #waitingButtonLabel()}：没人退才叫「下一局」；</li>
     *   <li>{@link #hasSeatVacated()}：结算页只给「下一局」投票按钮，有人退就改走「开始游戏」。</li>
     * </ul>
     * 判据来自用户 2026-10-24：「只有对局结束、没有任何玩家退出的时候才有下一局」。
     */
    private boolean seatVacated;

    /** 本局是否有人退出过（{@code DdzMenu} 结算页据此决定给不给「下一局」）。 */
    public boolean hasSeatVacated() {
        return seatVacated;
    }

    /**
     * 记一次「有人退出」：置位，并在<b>结算期</b>立刻把结算板的投票行换掉。
     *
     * <p>不刷新的话板子上还留着「继续投票 2/3（点桌面投票）」，而按钮其实已经没了 ——
     * 玩家会以为还能投票。清掉票数并改成「不再续局」的说明，是唯一诚实的状态。</p>
     */
    private void markSeatVacated() {
        seatVacated = true;
        if (phase != TablePhase.SETTLED) {
            return;
        }
        java.util.Arrays.fill(nextVote, false);
        if (settleBase != null) {
            settleText = settleBase + "\n§c本局有人退出，不再续局：三家坐齐后由桌主点「"
                    + waitingButtonLabel() + "」。";
            forceTip(settleText);
        }
    }

    private int roundNumber;

    /**
     * 阶段的中文名（表单里给玩家看的，别直接输出枚举名 {@code BIDDING/DOUBLING}）。
     */
    private String phaseLabel() {
        if (round == null) {
            return phase == TablePhase.WAITING ? "等待开局" : "—";
        }
        switch (round.getPhase()) {
            case BIDDING:
                switch (round.getBidStage()) {
                    case SCORING:
                        return "叫分";
                    case ROBBING:
                        return "抢地主";
                    default:
                        return "叫地主";
                }
            case DOUBLING:
                return "选加倍";
            case PLAYING:
                return "出牌";
            case FINISHED:
                return "已结束";
            case REDEAL:
                return "重新发牌";
            default:
                return "—";
        }
    }

    /** 菜单顶部的状态摘要（底分 / 座位 / 阶段 / 地主 / 倍数）。 */
    public String statusText(Player viewer) {
        var cfg = ddzConfig();
        // 底分跟**本桌场次**走（不是全局默认档位）；对局中显示本局快照值。
        double shownBase = (phase == TablePhase.WAITING || phase == TablePhase.SETTLED)
                ? baseScoreOfTier() : baseScore;
        double shownCut = view != null ? view.getCut()
                : (cfg != null ? cfg.normalizedCut() : 0.0);
        StringBuilder sb = new StringBuilder();
        sb.append("§7场次 §e").append(getTier().label())
                .append(" §7| §7底分 §e").append(fmtNum(shownBase))
                .append(" §7| §7抽水 §e").append(fmtNum(shownCut)).append("%");
        sb.append("\n§7座位：");
        for (int s = 0; s < 3; s++) {
            if (humans[s] != null) {
                sb.append("§f").append(seatName(s)).append(trusted[s] ? "§8(托管)" : "");
            } else if (bots[s] != null) {
                sb.append("§8").append(seatName(s));
            } else {
                sb.append("§8空");
            }
            if (s < 2) {
                sb.append(" §7| ");
            }
        }
        sb.append("\n§7阶段：§f").append(phaseLabel());
        if (round != null && round.getBidStage() == DdzRound.BidStage.SCORING
                && round.getPhase() == DdzRound.Phase.BIDDING) {
            sb.append(" §7最高叫分 §e")
                    .append(round.getHighestBid() <= 0 ? "—" : (round.getHighestBid() + " 分"));
        }
        if (round != null) {
            sb.append(" §7手牌 §f").append(round.getHand(0).size())
                    .append("§7/§f").append(round.getHand(1).size())
                    .append("§7/§f").append(round.getHand(2).size());
        }
        if (round != null && round.getLandlord() >= 0) {
            sb.append(" §7地主 §e").append(seatName(round.getLandlord()));
            sb.append(" §7倍数 §e×").append(round.getMultiplier());
        }
        return sb.toString();
    }

    private static String fmtNum(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }


    // ------------------------------------------------------------------
    // 实体
    // ------------------------------------------------------------------

    /**
     * 生成（或在被意外清除后重建）牌桌渲染实体。
     *
     * <p>⚠️ 位置必须是 {@link #origin}（**桌面顶面** = {@code baseY + 15/16}），
     * 不是 {@link #center}（方块中心 = {@code baseY}）。斗地主方块 geo 的 y 范围是
     * 0..15（单位 1/16 格），顶面就在 15/16；实体自身 geo 只有 −0.1..3.0 那一薄层，
     * 放在 center 会让整个牌桌**下沉近一格**、手牌与按钮全部埋进桌面里。</p>
     *
     * <p>同理 {@code DdzTableRay} 也是以 origin 为局部坐标原点 —— 两者必须同源，
     * 否则「看得见的位置」与「点得到的范围」差 0.9375 格（UNO 侧 {@code spawnEntity}
     * 用的也是 origin）。</p>
     */
    private void spawnEntity() {
        if (visual != null && !visual.isClosed()) {
            return;
        }
        Entity created = Entity.createEntity(DdzTableEntity.IDENTIFIER,
                Position.fromObject(origin, level));
        if (!(created instanceof DdzTableEntity v)) {
            return;
        }
        visual = v;
        v.setGame(this);
        v.spawnToAll();
        // 实体重建 ⇒ 客户端丢掉了旧的动画状态，旁观者集合必须作废、下一轮整批重发
        // （否则差量发送会认为"已经发过了"，旁观者就能直接看到别人手牌）。
        spectatorSent.clear();
        forceViewerResend = true;
    }

    /** 销毁整桌（含实体、飞行牌与 3x3 方块）。 */
    public void destroy() {
        TABLES.remove(key);
        recycleAllFlyCards();
        for (int s = 0; s < 3; s++) {
            if (online[s] != null) {
                takeLocator(online[s]);
            }
            humans[s] = null;
            online[s] = null;
            bots[s] = null;
        }
        if (visual != null) {
            visual.close();
            visual = null;
        }
        // 方块也一起收：拆桌只清实体会在世界里留下 9 个「孤儿方块」，
        // 它们既查不到所属牌桌（onBreak 会放行当普通方块），也挡着重新铺桌。
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3 p = new Vector3(baseX + i, baseY, baseZ + j);
                if (level.getBlock(p) instanceof DdzTableBlocks.DdzTableBlock) {
                    level.setBlock(p, Block.get(Block.AIR), true);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    public void tick() {
        spawnEntity();
        if (visual == null || visual.isClosed()) {
            return;
        }
        tickOutOfRange();
        tickBackInRange();
        if (eventTipHold > 0 && --eventTipHold == 0) {
            countdownText = null;
        }
        switch (phase) {
            case DEALING -> tickDealing();
            case PLAYING -> tickPlaying();
            case SETTLED -> tickSettled();
            default -> {
            }
        }
        recycleFlyCards();
        if (++hoverTick >= HOVER_INTERVAL) {
            hoverTick = 0;
            updateHover();
        }
        if (++viewerTick >= VIEWER_REFRESH) {
            viewerTick = 0;
            refreshViewers();
        }
        if (aiThinking && ++aiThinkingTicks > AI_THINK_STALL_TICKS) {
            // 计算线程异常 / 结果丢失：放开闸门，让下一轮 tick 重新提交
            aiThinking = false;
            aiThinkingTicks = 0;
            manager.getPlugin().getLogger().warning("[TabletopGames] AI 思考超时，重新提交：seat="
                    + actorSeat());
        }
        if (dirty) {
            dirty = false;
            sync();
        }
    }

    /**
     * 让本桌「忘掉」某个玩家已收过视角动画这件事 —— 下一轮 refreshViewers 会重新发。
     *
     * <p>调用时机：玩家复活（客户端重建实体 ⇒ 动画状态丢失）、或实体被重建。
     * 差量发送必须配一个作废入口，否则这条链会退化成「永远不再发送」。</p>
     */
    void forgetViewer(UUID id) {
        spectatorSent.remove(id);
        forceViewerResend = true;
    }

    /** 走出识别半径 → 视为中途退出（座位保留，系统代打）。 */
    private void tickOutOfRange() {
        var rangeCfg = ddzConfig();
        double r = rangeCfg == null ? DEFAULT_PLAYER_RANGE : rangeCfg.getPlayerRange();
        for (int s = 0; s < 3; s++) {
            Player p = online[s];
            if (humans[s] == null || p == null || !p.isOnline() || away[s]) {
                continue;
            }
            double dx = p.getX() - center.x;
            double dz = p.getZ() - center.z;
            if (dx * dx + dz * dz > r * r) {
                handOverToSystem(s);
            }
        }
    }

    // ------------------------------------------------------------------
    // 发牌
    // ------------------------------------------------------------------

    /**
     * 开一局：洗牌 → 逐张飞入 → 进入叫地主。
     * @return null = 成功，否则为拒绝原因
     */
    public String startRound() {
        // 斗地主固定 3 家：2 人局会让第三家永远没人出牌，直接卡死到超时
        if (seatedCount() < 3) {
            return "斗地主需要坐满 3 家才能开局（缺的位置可以用「加人机」补上）。";
        }
        if (getTier() == TabletopGamesConfig.DdzTier.MASTER && humanCount() < 3) {
            // 大师场底分 10000，一旦能用机器人补位就有巨大的刷币空间 ⇒ 只许 3 真人
            return "大师场只能 3 位真人开局，不能用机器人补位。";
        }
        if (phase == TablePhase.PLAYING || phase == TablePhase.DEALING) {
            return "本局还在进行中。";
        }
        // 开局不扣钱：入座只校验门槛，全部扣款在结算（settleEconomy）
        var cfg = ddzConfig();
        baseScore = baseScoreOfTier();
        difficulty = cfg == null ? DdzDifficulty.ADVANCED : DdzDifficulty.fromConfig(cfg.normalizedAiDifficulty().name());
        view = new DdzSettingsView(baseScore,
                cfg == null ? 1024 : cfg.normalizedMaxMultiplier(), stakeOfTier(),
                cfg == null ? 1.0 : cfg.normalizedCut());

        redealCount = 0;
        roundNumber++;
        // 新一局重新开始计「有没有人退出」：上一局谁走了不影响这一局的按钮名
        seatVacated = false;
        bottomTaken = false;
        settleText = null;
        settleBase = null;
        java.util.Arrays.fill(nextVote, false);
        // 开局不扣钱：斗地主与 UNO 同口径，**全部扣款发生在结算**（settleEconomy）。
        // 入座只做门槛校验（checkJoinStake），续局也无需再校验 —— 结算时按实际余额扣。
        startDeal();
        return null;
    }

    /**
     * 洗牌 + 建立卡槽映射，进入发牌动画。
     */
    private void startDeal() {
        clearTable();
        round = new DdzRound(view, random,
                bidMode == TabletopGamesConfig.DdzBidMode.SCORE,
                wildMode != TabletopGamesConfig.DdzWildMode.OFF,
                wildMode == TabletopGamesConfig.DdzWildMode.HEAVEN);
        round.deal();
        if (round.getHeavenRank() > 0) {
            // 天癞子是**发牌前**就抽定的，所以在发牌这一刻播 —— 叫牌阶段玩家就能
            // 在底牌区左边看到它（见 wildshow_/heavenshow_ 展示骨骼）。
            broadcastEvent("§6本局天癞子：§e" + rankText(round.getHeavenRank())
                    + " §7（该点数的牌换成金色牌面，可当 3~2 任意点数，不能当王）");
        }
        var stepCfg = ddzConfig();
        int step = stepCfg == null ? 50 : stepCfg.normalizedDealStepMillis();
        // 客户端飞牌总时长：每座位 seq×0.2 起飞 + 0.22 飞行 ⇒ 16×0.2+0.22 = 3.42 秒
        // （三家**并行**，不是串行 —— 每个座位的 seq 都从 0 起）。
        double flyTotal = (DdzFlyCardEntity.MAX_HAND - 1) * DdzFlyCardEntity.DEAL_STEP_SECONDS
                + DdzFlyCardEntity.FLY_SECONDS;
        // 配置项只决定「多等一会儿」：调大 = 牌摆好后再停几拍（给玩家看清），
        // 调小也不会早于飞牌总时长 —— 早放开操作会让飞牌还在空中就能点牌。
        double wanted = Math.max(flyTotal, step * DdzFlyCardEntity.MAX_HAND / 1000.0);
        dealTicks = (int) Math.max(1L, Math.round(wanted * TICKS_PER_SECOND));
        phase = TablePhase.DEALING;
        java.util.Arrays.fill(selected, 0);
        java.util.Arrays.fill(hover, 0);
        java.util.Arrays.fill(doubled, false);
        doublingTiming = false;
        doublingUsed = 0;
        botTicks = 0;
        actionUsed = 0;
        // 真牌一次性全上桌 + 飞牌一次性全生成：
        // 落点公式与真牌逐字同源 ⇒ 每一张都是「飞到位那一帧正好是它」，中途没有空缺；
        // 飞完客户端把 scale 置 0 自己消失（v.fgo），服务端全程零包。
        syncTableZones();
        spawnDealFlights();
        // 发牌「牌逐渐增加、中心对称往外扩展」（用户 2026-10-20）：
        // 播模组移植的 deal_reveal（168 骨骼，卡槽按 q.anim_time/0.2 依次出现，
        // 位置公式同步按当前张数居中展开）。走独立 controller，不顶掉 viewer 动画。
        sendActionAnim("animation.tabletopgame.ddz.deal_reveal",
                "tabletopgame:ddz_deal", 3.5f);
        broadcastEvent("§e正在发牌…");
        flush();
        // 立即播一次视角动画：tick 里的刷新是 5 秒一次，开局那一刻 viewer_mode 还是 -1
        // （旁观视角）⇒ 玩家会看不到自己的牌面、按钮也全消失，等几秒才"自己好了"。
        refreshViewers();
    }

    /** 发牌推进：只等飞牌飞完，然后把操作权交给叫地主。 */
    private void tickDealing() {
        if (round == null) {
            phase = TablePhase.WAITING;
            return;
        }
        if (--dealTicks > 0) {
            return;
        }
        // 飞牌已全部落地（客户端那边 scale 已归 0），实体可以收了
        recycleAllFlyCards();
        phase = TablePhase.PLAYING;
        bottomTaken = false;
        beginTurn();
    }

    /**
     * 把全部该上桌的牌重新摆一遍（三家手牌 + 已翻开的底牌）。
     *
     * <p>⚠️ 手牌必须<b>按 {@code round.getHand(s)} 的顺序递增分配卡槽</b>：客户端的
     * {@code v.sN} 是「该卡槽在本区内出现的先后」，而 {@code getHand} 已按牌面大小排好，
     * 所以槽号递增 = 屏幕上的手牌由小到大。乱序分配会让那排牌看起来是乱的。</p>
     *
     * <p>卡槽号在全桌 54 个槽里唯一，三家手牌区 + 底牌区互不重叠。</p>
     */
    /**
     * 把全部该上桌的牌重新摆一遍（三家手牌 + 出牌区 + 底牌堆）。
     *
     * <h2>⚠️ 卡槽号必须等于牌 id</h2>
     * 客户端 54 个 {@code card_NN} 的<b>牌面贴图是写死的</b>：{@code card_00} 取图集
     * {@code [0,0]}、{@code card_01} 取 {@code [160,0]} …… 即「卡槽 NN 永远显示第 NN 张牌」。
     * 所以只能把「牌 id = X」放在「卡槽 X」，否则<b>看到的牌面与真实手牌完全不符</b> ——
     * 初版按手牌顺序分配卡槽，真机症状是「明明看到手里 4 个 3，却打不出炸弹」。
     *
     * <p>由此 {@code 牌 id → 卡槽号} 恒等，本类不需要映射表；同一张牌换阶段只是**换区值**。</p>
     */
    private void syncTableZones() {
        if (round == null) {
            return;
        }
        int[] zones = new int[DdzTableEntity.CARD_SLOTS];
        // 手牌：**卡槽号 ≡ 牌 id**（不是顺序分配！）
        for (int s = 0; s < 3; s++) {
            for (int id : round.getHand(s)) {
                if (id >= 0 && id < zones.length) {
                    zones[id] = DdzTableEntity.ZONE_HAND_BASE + s;
                }
            }
        }
        // 出牌区：每家当前这一手打出的牌（已从手牌里删掉，只能在这里补上）
        for (int s = 0; s < 3; s++) {
            List<Integer> pc = playedCards[s];
            if (pc == null || pc.isEmpty()) {
                continue;
            }
            for (int id : pc) {
                if (id >= 0 && id < zones.length) {
                    zones[id] = DdzTableEntity.ZONE_PLAY_BASE + s;
                }
            }
        }
        // 底牌堆：封盘阶段（还没发给地主）盖着摆在 z=−11，3 张叠高
        if (!bottomTaken) {
            for (int id : round.getBottomCards()) {
                if (id >= 0 && id < zones.length) {
                    zones[id] = DdzTableEntity.ZONE_BOTTOM;
                }
            }
        }
        if (visual != null && !visual.isClosed()) {
            visual.setCardZones(zones);
            int[] ws = packWildSort();
            visual.setWildSort(ws[0], ws[1]);
        }
    }

    // ------------------------------------------------------------------
    // 癞子排序（手牌 / 出牌区里都算最大）
    // ------------------------------------------------------------------

    /**
     * 把「癞子要从第 i 格挪到第 j 格」打成 {@code playing_card_wild_sort}（2 档 × 14 位）。
     *
     * <p>客户端的区内序号 {@code v.sN} 是按<b>卡槽号（= 牌 id）</b>数出来的，
     * 而癞子的「大小」是它<b>当成的点数</b> —— 真机症状：连对 QQKKAA 打出去后
     * 出牌区排成 KQQKAA（当 K 的那张癞子还占着 3 的格子）。这里下发一次插入信息，
     * 客户端只做 ±1 的平移 + 自己那档直接跳到 j。</p>
     *
     * @return 打包值（0 = 不需要挪）
     */
    private int[] packWildSort() {
        int[] out = new int[]{0, 0};
        if (round == null) {
            return out;
        }
        int entries = 0;
        // ⚠️ 只打包<b>出牌区</b>：手牌那排「癞子挪到最后」是客户端自己数出来的
        //    （按 v.wb1..wb7 每区癞子张数算，见 entity pre_animation），两边都算会叠加。
        for (int s = 0; s < 3 && entries < WILD_SORT_SLOTS; s++) {
            List<Integer> pc = playedCards[s];
            if (pc == null || pc.isEmpty()) {
                continue;
            }
            entries = packWildZone(out, entries, s + 4, pc);
        }
        return out;
    }

    /** 一次最多下发几条插入指令（2 个属性 × 每属性 2 档）。 */
    private static final int WILD_SORT_SLOTS = 4;

    /**
     * 把一个区里的插档指令写进打包值。
     *
     * <p>2026-10-27 用户 #1：出牌区的顺序必须按<b>当值</b>排，而且<b>同点数的癞子要排在
     * 正常牌左边</b>（「癞子看成内部顺序大的牌」）。真机症状：手里 7 当 5 打出去，
     * 它跑到整排最右边（因为老实现把「非癞子 → 癞子」当成了目标顺序）。</p>
     *
     * <p>目标顺序 = 按 {@code (当值, 癞子优先, 牌id)} <b>升序</b>（小→大）算。
     * 「大→小」不再靠重排 —— 那要挪一整排，几个档根本放不下；改成客户端整体镜像
     * {@code v.sN = 区内张数-1-v.sN}（跟手牌那条 {@code v.dN} 同一套口径）。</p>
     *
     * <p>⚠️ 指令是<b>按位置</b>生效、且客户端<b>顺序执行</b>：{@code {from,to}} 由
     * {@link com.tabletopgames.game.ddz.DdzPlayOrder#moves} 用「最长上升子序列」挑出最少的几张，
     * 不会撞位。</p>
     *
     * <p>⚠️⚠️ 打包<b>每档 12 位</b>（区码 2 + from 5 + to 5），一个属性 2 档 ⇒ 最高 2^24−1。
     * Molang 的 {@code q.property()} 是 float32，只能精确到 2^24；老编码 2 档 × 14 位一旦
     * 同时用上，低档就被浮点抹平（区码变 0 ⇒ 整档失效），出牌区因此整排乱掉。</p>
     *
     * @return 写完之后一共用掉几档
     */
    private int packWildZone(int[] out, int entries, int zone, List<Integer> cards) {
        if (cards == null || cards.size() < 2 || entries >= WILD_SORT_SLOTS) {
            return entries;
        }
        List<Integer> byId = new ArrayList<>();
        for (int id : cards) {
            if (DdzCards.isValid(id)) {
                byId.add(id);
            }
        }
        java.util.Collections.sort(byId);
        int n = byId.size();
        if (n < 2) {
            return entries;
        }
        // 目标顺序见 DdzPlayOrder：当值升序 + 同点数癞子在前（方向由客户端镜像，见方法注释）
        List<Integer> want = com.tabletopgames.game.ddz.DdzPlayOrder.sorted(
                byId, this::playSortRank, this::isWildCard, true);
        for (int[] mv : com.tabletopgames.game.ddz.DdzPlayOrder.moves(
                byId, want, WILD_SORT_SLOTS - entries)) {
            int slot = entries;
            // 区码：出牌区 4/5/6 → 1/2/3（0 = 该档不用）
            int value = ((zone - 3) & 3)
                    | ((mv[0] & 31) << 2)
                    | ((mv[1] & 31) << 7);
            out[slot / 2] |= value << ((slot % 2) * 12);
            entries++;
        }
        return entries;
    }

    /**
     * 出牌区排序用的「点数」：癞子按<b>它当成的点数</b>，正常牌按自己的点数。
     *
     * <p>当值没被 {@code assignWildRanks} 记下来的（单出 / 当带牌）就当它自己，
     * 也就是「不挪位」。</p>
     */
    private int playSortRank(int id) {
        if (round != null) {
            int as = round.getWildAs(id);
            if (as > 0) {
                return as;
            }
        }
        return DdzCards.rank(id);
    }

    /**
     * 地主确定 → 收下三张底牌（「地主发牌动作」）。
     *
     * <p>与模组同一套：先把 3 张底牌 id 写进 {@code playing_card_bottom} 属性，再把区值切到地主手牌
     * （{@code bottomTaken = true}），最后播两条一次性动画 ——
     * {@code landlord_reveal}（徽记弹出）和 {@code bottom_take}（底牌飞到地主手里）。
     * 两条各走独立的 controller 槽，互不替换。</p>
     *
     * <p>⚠️ 这两条动画只动<b>已有的</b> {@code card_NN} / {@code fake_pN_17..19} 骨骼：
     * 不加骨骼、不加 {@code pre_animation} 条数，所以不会触碰
     * 规模锁（{@code tools/ddz/verify_res.py} 现在是 419/312，见该文件 B 段注释）。</p>
     */
    private void takeBottomCards() {
        if (round == null || bottomTaken) {
            return;
        }
        bottomTaken = true;
        syncTableZones();
        if (visual != null && !visual.isClosed()) {
            List<Integer> bottom = round.getBottomCards();
            if (bottom != null && bottom.size() == 3) {
                visual.setBottomTake(bottom.get(0), bottom.get(1), bottom.get(2));
            }
        }
        sendActionAnim("animation.tabletopgame.ddz.landlord_reveal",
                "tabletopgame:ddz_landlord", 0.9f);
        sendActionAnim("animation.tabletopgame.ddz.bottom_take",
                "tabletopgame:playing_card_bottom", 0.5f);
        if (round.isWildEnabled() && round.getWildRank() > 0) {
            // 地赖子是定地主那一刻才抽的，只能在这里播 —— 玩法模式里也写了这条规则
            broadcastEvent("§6本局地赖子：§e" + rankText(round.getWildRank())
                    + " §7（该点数的牌换成金色牌面，可当 3~2 任意点数，不能当王）");
        }
    }

    /** 点数 → 牌面文字（3..10 / J / Q / K / A / 2 / 大小王）。 */
    private static String rankText(int rank) {
        switch (rank) {
            case 11: return "J";
            case 12: return "Q";
            case 13: return "K";
            case 14: return "A";
            case 15: return "2";
            case DdzCards.RANK_SMALL_JOKER: return "小王";
            case DdzCards.RANK_BIG_JOKER: return "大王";
            default: return String.valueOf(rank);
        }
    }

    /** 清空桌面（出牌区 + 底牌标记 + 全部区值归零）。 */
    private void clearTable() {
        for (int s = 0; s < 3; s++) {
            playedCards[s] = null;
        }
        bottomTaken = false;
        if (visual != null && !visual.isClosed()) {
            visual.setCardZones(new int[DdzTableEntity.CARD_SLOTS]);
            // 清掉底牌复刻展示：否则新一局发牌期会残留上一局的三张底牌（西侧展示位）
            visual.setBottomTake(0, 0, 0);
        }
    }

    /** 为「已发出的第 shown 张」生成飞入实体。 */
    private void spawnDealFlights() {
        recycleAllFlyCards();
        if (round == null || visual == null) {
            return;
        }
        int[] sizes = handSizes();
        for (int s = 0; s < 3; s++) {
            // ⚠️ seq 必须是**座位内序号**（0 起），不是全局发牌序号：
            // 客户端拿它算横向落点 `v.fo = (v.fq - (v.fc-1)/2) * v.fsp`，
            // 同时用它算起飞时刻 `v.ft0 = 0.2 * v.fq`。
            // 初版这里用了一个跨座位递增的 seq，结果座位 1 的牌落点偏移到
            // (17 - 8) × 2.2 ≈ 19.8 格、座位 2 更远，整排直接飞出桌面。
            int n = Math.min(sizes[s], DdzFlyCardEntity.MAX_HAND);
            for (int seq = 0; seq < n; seq++) {
                spawnFly(s, seq, sizes);
            }
        }
    }

    /** 三家当前手牌张数（飞牌实体按它算居中落点，必须与真牌渲染一致）。 */
    private int[] handSizes() {
        int[] n = new int[3];
        if (round != null) {
            for (int s = 0; s < 3; s++) {
                n[s] = round.getHand(s).size();
            }
        }
        return n;
    }

    /**
     * 生成一张飞入牌（贴牌背，落点由客户端动画按 {@code handSizes} 算，与手牌区同源）。
     *
     * <p>⚠️ 属性必须在 {@code spawnToAll()} 之前写完 —— 实体一生成客户端就开始按旧值插值。
     */
    private void spawnFly(int seat, int seq, int[] handSizes) {
        try {
            Position p = Position.fromObject(new Vector3(
                    center.x, origin.y, center.z), level);
            Entity created = Entity.createEntity(DdzFlyCardEntity.IDENTIFIER, p);
            if (!(created instanceof DdzFlyCardEntity fly)) {
                return;
            }
            fly.setTarget(seat, seq, handSizes);
            fly.spawnToAll();
            flyAges.put(fly, 0);
        } catch (RuntimeException ignored) {
            // 飞行实体只是观感层，生成失败不该让整局卡住
        }
    }

    private void recycleFlyCards() {
        if (flyAges.isEmpty()) {
            return;
        }
        // ⚠️ 不能写 `++e.getValue()`：Map.Entry.getValue() 返回的是 Integer 值而非变量，
        //    自增会编译失败。用迭代器显式 setValue（UNO 侧同款写法）。
        Iterator<Map.Entry<DdzFlyCardEntity, Integer>> it = flyAges.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<DdzFlyCardEntity, Integer> e = it.next();
            DdzFlyCardEntity f = e.getKey();
            if (f.isClosed()) {
                it.remove();
                continue;
            }
            int age = e.getValue() + 1;
            if (age > flyLifeTicks()) {
                f.close();
                it.remove();
            } else {
                e.setValue(age);
            }
        }
    }

    private void recycleAllFlyCards() {
        for (DdzFlyCardEntity f : new ArrayList<>(flyAges.keySet())) {
            if (!f.isClosed()) {
                f.close();
            }
        }
        flyAges.clear();
    }

    /**
     * 飞牌实体存活时长（**表 tick**）。
     *
     * <p>直接用 {@link DdzFlyCardEntity#LIFE_TICKS}：它是按「客户端 20 tick/秒」算出来的
     * 69，在斗地主的表（4 tick/秒）里等于 17.25 秒 —— 比飞牌实际需要的 3.42 秒宽裕得多。
     * 这里刻意<b>不</b>换算成表 tick：飞牌飞完后客户端就把 {@code v.fgo} 置 0 自己不可见了，
     * 服务端只是晚一点回收实体；多留几秒零成本，少留则会**提前关掉还在空中的牌**。
     * （初版这里按「最多 20 张」硬编码成 1.27 秒 → 8 表 tick = 2 秒，
     * 但三家共 51 张、每座 seq 到 16 就是 3.42 秒，叠上生成延迟很容易被截断。）</p>
     */
    private int flyLifeTicks() {
        return DdzFlyCardEntity.LIFE_TICKS;
    }

    // ------------------------------------------------------------------
    // 回合推进
    // ------------------------------------------------------------------

    /** 当前该行动的座位；−1 = 无（结算 / 等待）。 */
    public int actorSeat() {
        return round == null ? -1 : round.getCurrentSeat();
    }

    /** 叫抢 / 加倍 / 出牌阶段的通用推进：时限、托管代打、换人。 */
    private void tickPlaying() {
        if (round == null) {
            return;
        }
        DdzRound.Phase rp = round.getPhase();
        if (rp == DdzRound.Phase.FINISHED) {
            doFinish();
            return;
        }
        if (rp == DdzRound.Phase.REDEAL) {
            if (++redealCount > MAX_REDEAL) {
                phase = TablePhase.WAITING;
                round = null;
                clearTable();
                broadcast("§c连续无人叫地主，本局作废，请重新开始。");
                return;
            }
            broadcast("§7三家都不叫，重新发牌…");
            startDeal();
            return;
        }
        int actor = round.getCurrentSeat();
        if (actor < 0) {
            return;
        }
        if (autoPlayed(actor)) {
            // ⚠️ 必须先把 botTicks 补正：真人回合中途开托管时 botTicks 是 0，
            //    旧写法 `botTicks > 0 && --botTicks <= 0` 恒 false ⇒ **永远不行动**（真机卡死）。
            if (botTicks <= 0) {
                botTicks = BOT_THINK_TICKS;
                return;
            }
            if (--botTicks <= 0) {
                autoAction(actor);
                // 兜底：万一 autoAction 没能推进状态（AI 找不到合法动作等），给它
                // 一个完整的思考周期再来一次 —— 否则 botTicks 已经 ≤0，会**每 tick 重试**，
                // 日志与广播都会被刷屏。
                if (round != null && round.getCurrentSeat() == actor) {
                    botTicks = BOT_THINK_TICKS;
                }
            }
            return;
        }
        // 人类时限
        if (actionLimit > 0) {
            ++actionUsed;
            if (doublingTiming) {
                // 加倍阶段：三家共用一份计时，把累计值写回 doublingUsed
                doublingUsed = actionUsed;
            }
            // 倒计时必须每 tick 刷一遍（setCountdown 内部按「文本是否变化」去重，
            // 秒数没跳时不会发包）。初版只在 beginTurn 设了一次，玩家看到的是
            // 永远停在「20s」的死数字。
            setCountdown(countdownFor(actor));
            if (actionUsed >= actionLimit) {
                timeoutAction(actor);
                // 兜底：超时处理没推进状态时重置计时，别每 tick 重试刷屏
                if (round != null && round.getCurrentSeat() == actor) {
                    if (doublingTiming) {
                        // 加倍阶段超时默认不加倍后会换人，共用计时**不重置**
                        doublingUsed = actionUsed;
                    } else {
                        actionUsed = 0;
                    }
                }
            }
        }
    }

    /** 新回合：重置时限与倒计时。 */
    private void beginTurn() {
        DdzRound.Phase rp = round == null ? null : round.getPhase();
        if (rp == DdzRound.Phase.DOUBLING) {
            // ★ 加倍阶段是**共用**一个倒计时（用户 2026-10-20）：进入 DOUBLING 那一刻
            //   起表一次，之后换人**不重置** actionUsed/actionLimit。
            if (!doublingTiming) {
                doublingTiming = true;
                doublingUsed = 0;
            }
            actionLimit = computeActionLimit();
            actionUsed = doublingUsed;
        } else {
            doublingTiming = false;
            actionUsed = 0;
            actionLimit = computeActionLimit();
        }
        // 换人 ⇒ 上一轮的「有没有能压过」结论不再适用
        invalidateBeatCache();
        int actor = actorSeat();
        botTicks = autoPlayed(actor) ? BOT_THINK_TICKS : 0;
        // 地主确定后收下底牌（含「底牌飞到地主手里」动作）
        if (round != null && round.getLandlord() >= 0 && !bottomTaken) {
            takeBottomCards();
        }
        if (actor >= 0) {
            setCountdown(countdownFor(actor));
        }
        flush();
    }

    private int computeActionLimit() {
        var cfg = ddzConfig();
        int sec = cfg == null ? 20 : cfg.normalizedTurnSeconds();
        return sec * TICKS_PER_SECOND;
    }

    /**
     * 当前动作栏文本。
     *
     * <p>用户 2026-10-20：加倍阶段<b>默认只显示自己的时间</b>；自己已选完、别人还没
     * 选完时<b>不显示别人的倒计时</b>（显示「等待其他玩家…」）。出牌阶段不变。</p>
     */
    private String countdownFor(int actor) {
        if (actor < 0) {
            return null;
        }
        int left = Math.max(0, actionLimit - actionUsed);
        int sec = (left + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
        // UNO 三行格式（2026-10-20）：轮到 / 剩余 N 秒 / 当前倍数
        // 配色对齐 UNO tickCountdown：≤3s 红、≤10s 黄、>10s 绿
        String timeColor = sec <= 3 ? "§c§l" : (sec <= 10 ? "§e" : "§a");
        String who = seatName(actor);
        DdzRound.Phase rp = round == null ? null : round.getPhase();
        String third;
        if (rp == DdzRound.Phase.DOUBLING) {
            if (doubled[actor]) {
                return "§7等待其他玩家选择加倍…";
            }
            third = "§7请选加倍";
        } else {
            int mult = round == null ? 1 : round.getMultiplier();
            third = "§7当前倍数：§e×" + mult;
        }
        return "§e轮到 §f" + who + "\n§7剩余 " + timeColor + sec + "§7 秒\n" + third;
    }

    /** tip 重发节流：MOT 的 {@code sendTip} 会被客户端淡出，所以同一条文本也要周期重发。 */
    private int tipRepeat;

    /**
     * 把 <b>动作栏</b> 置为给定文本（{@code null} = 清空）。
     *
     * <p>MOT 的 FloatingTextParticle 就是盔甲架，客户端会自己淡出 ⇒ 同一条文本
     * 也必须隔一段时间重发一次，否则结算板/倒计时会凭空消失。
     * 文本变化时立刻发；没变化则每 {@link #TIP_REPEAT_TICKS} 表 tick 补发一次。</p>
     */
    private void setCountdown(String text) {
        // 插播报期间让位：否则下一个 tick 的倒计时刷新会立刻把「XX 出了顺子5张」顶掉。
        // hold 结束时 tick() 会把 countdownText 置 null，下一拍自然补发当前倒计时。
        if (eventTipHold > 0) {
            return;
        }
        boolean changed = text == null ? countdownText != null : !text.equals(countdownText);
        if (changed) {
            countdownText = text;
            tipRepeat = 0;
            pushTip();
            return;
        }
        if (text != null && ++tipRepeat >= TIP_REPEAT_TICKS) {
            tipRepeat = 0;
            pushTip();
        }
    }

    /**
     * 无条件把动作栏置为给定文本（**绕过** {@link #setCountdown} 的
     * {@code eventTipHold} 挡板与 {@code tipRepeat} 节流）。
     *
     * <p>给<b>结算板</b>用：UNO 的 {@code showSettleTip()} 是每秒无条件
     * {@code broadcastTip(...)}，斗地主这边必须同口径 —— 初版结算板走
     * {@code setCountdown}，被 {@code eventTipHold}（2 秒）+ {@code tipRepeat}
     * （2 秒）叠成「约 10 秒才显示一次」，用户 2026-10-20 明确要求 1 秒 1 次。</p>
     */
    private void forceTip(String text) {
        countdownText = text;
        tipRepeat = 0;
        pushTip();
    }

    private void pushTip() {
        for (Player p : viewers()) {
            p.sendTip(countdownText == null ? "" : countdownText);
        }
    }

    // ------------------------------------------------------------------
    // 按钮动作
    // ------------------------------------------------------------------

    /** 当前全局按钮组（按槽 0..3 顺序，0 = 空槽）。 */
    private int[] globalButtons() {
        // 结算期：屏幕上常驻「退出 / 下一局」两枚贴图按钮，三家都看得到、都能点
        // （照 UNO 的 BTN_SETTLE，用户 2026-10-24 要求）
        if (phase == TablePhase.SETTLED) {
            return settleButtons();
        }
        if (round == null || phase != TablePhase.PLAYING) {
            return EMPTY_BUTTONS;
        }
        int actor = actorSeat();
        if (actor < 0) {
            return EMPTY_BUTTONS;
        }
        return buttonsFor(actor);
    }

    /**
     * 结算期的桌面按钮组（槽 0 = 屏幕右、槽 1 = 屏幕左；与 UNO {@code BTN_SETTLE}
     * 的「左退出、右下一局」同序）。
     *
     * <p>本局<b>有人退出</b>（{@link #hasSeatVacated()}）时只留「退出」——
     * 续局链已断，{@code voteNext} 会直接拒绝，留个按不动的按钮只会骗人；
     * 这时只能等三家坐齐、由桌主从菜单点「开始游戏」。</p>
     */
    private int[] settleButtons() {
        int exit = DdzTableEntity.Controls.EXIT.code();
        if (hasSeatVacated()) {
            return new int[]{0, exit, 0, 0};
        }
        return new int[]{DdzTableEntity.Controls.NEXT.code(), exit, 0, 0};
    }

    /**
     * 叫分制下的叫分按钮组。
     *
     * <p>只列<b>高于当前最高分</b>的档位 + 「不叫」，并且必须占<b>连续的低槽</b>：
     * 客户端的 {@code v.button_offset_N} 数的是「前面有几个非空槽」，不是槽号，
     * 中间留空槽会让两个按钮叠在同一点上。</p>
     *
     * <pre>
     *   最高分 0 → [3分, 2分, 1分, 不叫]（槽 0..3）
     *   最高分 1 → [3分, 2分, 不叫]      （槽 0..2）
     *   最高分 2 → [3分, 不叫]           （槽 0..1）
     *   最高分 3 → 已经定地主了，不该再问
     * </pre>
     */
    private int[] scoreBidButtons(int highest) {
        int decline = DdzTableEntity.Controls.DECLINE_CALL.code();
        int b3 = DdzTableEntity.Controls.BID_3.code();
        int b2 = DdzTableEntity.Controls.BID_2.code();
        int b1 = DdzTableEntity.Controls.BID_1.code();
        if (highest >= 3) {
            return EMPTY_BUTTONS;
        }
        if (highest == 2) {
            return new int[]{b3, decline, 0, 0};
        }
        if (highest == 1) {
            return new int[]{b3, b2, decline, 0};
        }
        return new int[]{b3, b2, b1, decline};
    }

    /**
     * 当前阶段的按钮组。
     * <p>
     * <b>槽序按模组</b>：槽 0 在最右且放主动作。
     * <pre>
     *   叫地主：[叫地主, 不叫]                 抢地主：[抢地主, 不抢]
     *   加倍  ：[超级加倍, 加倍, 不加倍]        出牌  ：[出牌, 不出/压不过, 提示, 取消]
     * </pre>
     */
    private int[] buttonsFor(int seat) {
        if (seat < 0 || seat >= 3) {
            return EMPTY_BUTTONS;
        }
        // 结算期是「全员同一套」，不属于任何一位行动方
        if (phase == TablePhase.SETTLED) {
            return settleButtons();
        }
        if (round == null || seat != actorSeat()) {
            return EMPTY_BUTTONS;
        }
        DdzRound.Phase rp = round.getPhase();
        if (rp == DdzRound.Phase.BIDDING) {
            if (round.getBidStage() == DdzRound.BidStage.SCORING) {
                return scoreBidButtons(round.getHighestBid());
            }
            return round.getBidStage() == DdzRound.BidStage.CALLING
                    ? new int[]{DdzTableEntity.Controls.CALL_LANDLORD.code(),
                    DdzTableEntity.Controls.DECLINE_CALL.code(), 0, 0}
                    : new int[]{DdzTableEntity.Controls.ROB_LANDLORD.code(),
                    DdzTableEntity.Controls.DECLINE_ROB.code(), 0, 0};
        }
        if (rp == DdzRound.Phase.DOUBLING) {
            return new int[]{DdzTableEntity.Controls.SUPER_DOUBLE.code(),
                    DdzTableEntity.Controls.DOUBLE.code(),
                    DdzTableEntity.Controls.NO_DOUBLE.code(), 0};
        }
        if (rp == DdzRound.Phase.PLAYING) {
            int[] g = new int[4];
            g[0] = DdzTableEntity.Controls.PLAY.code();
            if (round.isLeading()) {
                // 首出不能不出 ⇒ 槽 1 留**空槽**。
                // ⚠️ 别在这里塞 HINT：槽 2 本来就是提示，两个「提示」会并排出现，
                //    而且空槽会被客户端的居中算法自动跳过（button_offset 只数非空槽）。
                g[1] = 0;
            } else if (hasBeat(seat)) {
                g[1] = DdzTableEntity.Controls.PASS.code();
            } else {
                g[1] = DdzTableEntity.Controls.CANNOT_BEAT.code();
            }
            g[2] = DdzTableEntity.Controls.HINT.code();
            g[3] = DdzTableEntity.Controls.CLEAR_SELECTION.code();
            return g;
        }
        return EMPTY_BUTTONS;
    }

    // 「有没有能压过上一手的牌」的缓存。★ 必须缓存：这个判断要枚举全部可出牌型，
    //   而 buttonsFor 会被 sync() / 拾取 / 按钮点击在同一个 tick 里反复调用。
    private int beatCacheSeat = -1;
    private DdzPattern beatCachePlay;
    private boolean beatCacheValue;

    /**
     * 该座位当前有没有能压过上一手的牌（决定槽 1 显示「不出」还是「压不过」）。
     *
     * <p>⚠️ 两者在服务端<b>行为完全一样</b>（都是 {@code round.pass}），差别只是文案 ——
     * 所以这里判错的代价只是提示词不准，绝不能因为判错而让玩家点不动。</p>
     *
     * <p>⚠️ 初版这里把返回值当「能不能不出」用，却返回 {@code options.isEmpty()}，
     * 于是<b>压得过时显示「压不过」、压不过时显示「不出」</b>，正好反了。</p>
     */
    private boolean hasBeat(int seat) {
        if (round == null || round.getLastPlay() == null) {
            return false;
        }
        DdzPattern last = round.getLastPlay();
        if (beatCacheSeat == seat && beatCachePlay == last) {
            return beatCacheValue;
        }
        // DdzBotAi.enumerate 已在内部终检 beats(target)（修过「吐出压不过的牌」那个 bug）
        beatCacheValue = !DdzBotAi.enumerate(round.getHand(seat), last,
                round.getWildRank(), round.getHeavenRank()).isEmpty();
        beatCacheSeat = seat;
        beatCachePlay = last;
        return beatCacheValue;
    }

    /** 清掉压牌判断缓存（换人 / 出牌 / 重新发牌后必须调用）。 */
    private void invalidateBeatCache() {
        beatCacheSeat = -1;
        beatCachePlay = null;
    }

    /** 按钮点击。 */
    private void pressButton(Player p, int seat, int slot) {
        int[] group = buttonsFor(seat);
        if (slot < 0 || slot >= group.length || group[slot] == 0) {
            return;
        }
        DdzTableEntity.Controls c = DdzTableEntity.Controls.of(group[slot]);
        p.getLevel().addSound(p.getPosition(), Sound.RANDOM_CLICK, 1.0f, 1.0f, p);
        switch (c) {
            // 结算期的两枚桌面按钮（照 UNO：左「退出」右「下一局」）
            case EXIT -> leave(p);
            case NEXT -> {
                String err = voteNext(p);
                if (err != null) {
                    p.sendTip("§c" + err);
                }
            }
            // 叫分制：1/2/3 分（按钮组只给高于当前最高分的档）
            case BID_1 -> doBid(p, seat, 1);
            case BID_2 -> doBid(p, seat, 2);
            case BID_3 -> doBid(p, seat, 3);
            case CALL_LANDLORD -> doCallRob(p, seat, true, false);
            // 「不叫」在两种定地主方式下共用同一块贴图与同一个码，按阶段分流
            case DECLINE_CALL -> doDeclineCall(p, seat);
            case ROB_LANDLORD -> doCallRob(p, seat, true, true);
            case DECLINE_ROB -> doCallRob(p, seat, false, true);
            case SUPER_DOUBLE -> doDouble(p, seat, 4);
            case DOUBLE -> doDouble(p, seat, 2);
            case NO_DOUBLE -> doDouble(p, seat, 1);
            case PLAY -> doPlayButton(p, seat);
            case PASS -> doPass(p, seat);
            case HINT -> doHint(p, seat);
            case CLEAR_SELECTION -> {
                selected[seat] = 0;
                flush();
            }
            // ⚠️ 「压不过」必须**直接执行不出**：压不过时的唯一合法选择就是 pass，
            //    若只弹一句提示，玩家会点不动、一直卡到超时（初版就是这么写的）。
            case CANNOT_BEAT -> {
                p.sendTip("§7这一手压不过，自动不出。");
                doPass(p, seat);
            }
            default -> {
            }
        }
    }

    /**
     * 「不叫」：叫抢制下是「不参与叫地主」，叫分制下是「本轮叫 0 分」。
     *
     * <p>两者共用同一张贴图骨骼与同一个动作码（{@code DECLINE_CALL}，码 2），
     * 所以这里必须按 {@code getBidStage()} 分流 —— 直接调 {@code callLandlord}
     * 在叫分制下会被回合拒掉（"当前不是叫地主阶段"），按钮就变成点了没反应。</p>
     */
    private void doDeclineCall(Player p, int seat) {
        if (round != null && round.getBidStage() == DdzRound.BidStage.SCORING) {
            doBid(p, seat, 0);
            return;
        }
        doCallRob(p, seat, false, false);
    }

    /**
     * 叫分（0 = 不叫 / 1 / 2 / 3 分）。
     *
     * <p>3 分是满分，叫到就立刻定地主（回合内部不再等其他人）。</p>
     */
    private void doBid(Player p, int seat, int score) {
        if (round == null) {
            return;
        }
        String err = round.bidScore(seat, score);
        if (err != null) {
            p.sendTip("§c" + err);
            return;
        }
        broadcastEvent("§f" + seatName(seat) + " §7" + (score == 0 ? "不叫" : "叫 " + score + " 分"));
        afterStep();
    }

    private void doCallRob(Player p, int seat, boolean yes, boolean robbing) {
        if (round == null) {
            return;
        }
        String err = robbing ? round.robLandlord(seat, yes) : round.callLandlord(seat, yes);
        if (err != null) {
            p.sendTip("§c" + err);
            return;
        }
        String verb = robbing ? (yes ? "抢地主" : "不抢") : (yes ? "叫地主" : "不叫");
        broadcastEvent("§f" + seatName(seat) + " §7" + verb);
        afterStep();
    }

    private void doDouble(Player p, int seat, int factor) {
        if (round == null) {
            return;
        }
        String err = round.chooseDouble(seat, factor);
        if (err != null) {
            p.sendTip("§c" + err);
            return;
        }
        // 标记该座位已选完 ⇒ 倒计时切换成「等待其他玩家…」
        doubled[seat] = true;
        String label = factor == 4 ? "超级加倍 ×4" : (factor == 2 ? "加倍 ×2" : "不加倍");
        broadcastEvent("§f" + seatName(seat) + " §7" + label);
        afterStep();
    }

    /** 「出牌」：把当前选中的牌打出去（带癞子且能解释成多种牌型时先弹表单）。 */
    private void doPlayButton(Player p, int seat) {
        if (round == null) {
            return;
        }
        if (seat != actorSeat()) {
            p.sendTip("§e还没轮到你。");
            return;
        }
        List<Integer> cards = selectedCards(seat);
        if (cards.isEmpty()) {
            p.sendTip("§e请先点击手牌选择要出的牌。");
            return;
        }
        // 2026-10-27 用户 #2：同一手牌能解释成几种牌型时，让玩家自己挑（QQ 斗地主同款）。
        // 只留「压得过上一手」的那些；恰好只剩一种就当没歧义，直接出。
        List<DdzPattern> all = DdzPattern.identifyAll(cards, round.getHeavenRank(),
                round.getWildRank());
        List<DdzPattern> usable = new ArrayList<>();
        for (DdzPattern pat : all) {
            if (round.getLastPlay() == null || DdzPattern.beats(pat, round.getLastPlay())) {
                usable.add(pat);
            }
        }
        if (usable.size() > 1) {
            showPatternChoice(p, seat, cards, usable);
            return;
        }
        applyPlay(seat, cards, usable.size() == 1 ? usable.get(0) : null);
    }

    /**
     * 「选择牌型」表单（2026-10-27 用户 #2）。
     *
     * <p>按钮文案照玩家给的样例：「连对 - 66554433」「飞机 - 44433365」。
     * 点哪个就按哪种牌型出牌；不点 / 关掉表单 = 这手牌先不出，可以重新选牌。</p>
     */
    private void showPatternChoice(Player p, int seat, List<Integer> cards,
                                   List<DdzPattern> options) {
        boolean asc = getHandSort() != TabletopGamesConfig.DdzHandSort.BIG_LEFT;
        com.tabletopgames.ui.Simple form = new com.tabletopgames.ui.Simple(
                "§l选择牌型",
                "§7这手牌有 §f" + options.size() + " §7种打法，点一个确认出牌。");
        for (DdzPattern pat : options) {
            String seq = DdzPattern.displaySequence(cards, round.getHeavenRank(),
                    round.getWildRank(), pat, asc);
            form.add("§f" + pat.shortLabel() + " §7- §e" + seq, () -> {
                if (round == null || seat != actorSeat()) {
                    p.sendTip("§e已经轮不到你出牌了。");
                    return;
                }
                applyPlay(seat, cards, pat);
            });
        }
        form.show(p);
    }

    private void applyPlay(int seat, List<Integer> cards) {
        applyPlay(seat, cards, null);
    }

    /**
     * @param forced 玩家在「选择牌型」表单里挑中的牌型；{@code null} = 自动识别
     */
    private void applyPlay(int seat, List<Integer> cards, DdzPattern forced) {
        String err = round.play(seat, cards, forced);
        if (err != null) {
            for (Player p : viewers()) {
                p.sendTip("§c" + err);
            }
            return;
        }
        selected[seat] = 0;
        DdzPattern played = forced != null
                ? forced
                : DdzPattern.identify(cards, round.getHeavenRank(), round.getWildRank());
        String label = played == null ? "牌" : played.label();
        broadcastEvent("§f" + seatName(seat) + " §7出了 §e" + label);
        moveCardsToPlayZone(seat, cards);
        afterStep();
    }

    private void doPass(Player p, int seat) {
        if (round == null) {
            return;
        }
        String err = round.pass(seat);
        if (err != null) {
            p.sendTip("§c" + err);
            return;
        }
        broadcastEvent("§f" + seatName(seat) + " §7不出");
        playedCards[seat] = null;
        selected[seat] = 0;
        afterStep();
    }

    /** 「提示」：高亮一手能压过的牌（选不满就一直提示下一手）。 */
    private void doHint(Player p, int seat) {
        if (round == null) {
            return;
        }
        List<List<Integer>> options = DdzBotAi.enumerate(round.getHand(seat),
                round.getLastPlay(), round.getWildRank(), round.getHeavenRank());
        if (options.isEmpty()) {
            p.sendTip("§7没有能压过上一手的牌。");
            return;
        }
        // 与当前选择比较：若当前这手合法就清空，否则选第一手
        List<Integer> cur = selectedCards(seat);
        DdzPattern curPattern = DdzPattern.identify(cur, round.getHeavenRank(),
                round.getWildRank());
        if (!cur.isEmpty() && curPattern != null
                && (round.getLastPlay() == null
                || DdzPattern.beats(curPattern, round.getLastPlay()))) {
            selected[seat] = 0;
            flush();
            return;
        }
        selected[seat] = maskOf(seat, options.get(0));
        flush();
    }

    /** 超时：走默认动作。 */
    private void timeoutAction(int seat) {
        Player p = online[seat];
        if (round == null) {
            return;
        }
        List<Integer> def = round.defaultAction(seat);
        DdzRound.Phase rp = round.getPhase();
        if (rp == DdzRound.Phase.BIDDING) {
            boolean scoring = round.getBidStage() == DdzRound.BidStage.SCORING;
            boolean robbing = round.getBidStage() == DdzRound.BidStage.ROBBING;
            String err = scoring ? round.bidScore(seat, 0)
                    : (robbing ? round.robLandlord(seat, false) : round.callLandlord(seat, false));
            if (err == null) {
                broadcastEvent("§7" + seatName(seat) + " 超时，自动"
                        + (robbing ? "不抢" : "不叫"));
                afterStep();
                return;
            }
        } else if (rp == DdzRound.Phase.DOUBLING) {
            if (round.chooseDouble(seat, 1) == null) {
                doubled[seat] = true;
                broadcastEvent("§7" + seatName(seat) + " 超时，自动不加倍");
                afterStep();
                return;
            }
        } else if (rp == DdzRound.Phase.PLAYING) {
            if (def != null && !def.isEmpty()) {
                if (round.play(seat, def) == null) {
                    selected[seat] = 0;
                    moveCardsToPlayZone(seat, def);
                    broadcastEvent("§7" + seatName(seat) + " 超时，自动出牌");
                    afterStep();
                    return;
                }
            } else if (round.getLastPlay() != null && round.pass(seat) == null) {
                        playedCards[seat] = null;
                selected[seat] = 0;
                broadcastEvent("§7" + seatName(seat) + " 超时，自动不出");
                afterStep();
                return;
            }
        }
        if (p != null) {
            p.sendTip("§e操作超时，已自动处理。");
        }
    }

    /** 系统代打（机器人 / 托管 / 离线）。 */
    private void autoAction(int seat) {
        if (round == null || aiThinking) {
            return;
        }
        aiThinking = true;
        aiThinkingTicks = 0;
        DdzRound.Phase rp = round.getPhase();
        // 托管 / 离线 / away 代打一律走初级（2026-10-22 用户要求：人走了不该反而变强）
        DdzDifficulty lv = bots[seat] != null ? bots[seat] : DdzDifficulty.SIMPLE;

        if (rp == DdzRound.Phase.BIDDING) {
            final List<Integer> hand = new ArrayList<>(round.getHand(seat));
            final long token = ++thinkToken;
            if (round.getBidStage() == DdzRound.BidStage.SCORING) {
                // 叫分制：返回 0..3，且保证高于当前最高分（否则服务端会拒）
                final int highest = round.getHighestBid();
                AiThinkDispatcher.light(manager.getPlugin(),
                        () -> DdzBotAi.decideScore(hand, highest, lv),
                        score -> {
                            aiThinking = false;
                            if (token != thinkToken || round == null) {
                                return;
                            }
                            int v = score == null ? 0 : score;
                            if (round.bidScore(seat, v) != null && v != 0) {
                                // 兜底：万一算出非法的分（配置改过 / 阈值不对），退成不叫
                                round.bidScore(seat, 0);
                            }
                            afterStep();
                        });
                return;
            }
            final boolean robbing = round.getBidStage() == DdzRound.BidStage.ROBBING;
            AiThinkDispatcher.light(manager.getPlugin(),
                    () -> DdzBotAi.decideCallRob(hand, robbing, lv),
                    yes -> {
                        aiThinking = false;
                        if (token != thinkToken || round == null) {
                            return;
                        }
                        boolean v = Boolean.TRUE.equals(yes);
                        String err = robbing ? round.robLandlord(seat, v) : round.callLandlord(seat, v);
                        if (err != null) {
                            return;
                        }
                        afterStep();
                    });
            return;
        }
        if (rp == DdzRound.Phase.DOUBLING) {
            final List<Integer> hand = new ArrayList<>(round.getHand(seat));
            final long token = ++thinkToken;
            AiThinkDispatcher.light(manager.getPlugin(),
                    () -> DdzBotAi.decideDouble(hand),
                    f -> {
                        aiThinking = false;
                        if (token != thinkToken || round == null) {
                            return;
                        }
                        int factor = f == null ? 1 : f;
                        if (round.chooseDouble(seat, factor) == null) {
                            doubled[seat] = true;
                            afterStep();
                        }
                    });
            return;
        }
        if (rp != DdzRound.Phase.PLAYING) {
            return;
        }

        // 出牌：所有决策都在后台线程算（初级走 Nukkit 异步池，高级走自建 2 线程池）
        final List<Integer> hand = new ArrayList<>(round.getHand(seat));
        final DdzPattern target = round.getLastPlay();
        final int lastSeat = round.getLastPlaySeat();
        final int landlord = round.getLandlord();
        final boolean leading = target == null;
        final long token = ++thinkToken;

        if (lv == DdzDifficulty.ADVANCED) {
            final List<Integer> unknown = unknownCards(seat);
            final int[] sizes = {round.getHand(0).size(), round.getHand(1).size(),
                    round.getHand(2).size()};
            final long deadline = System.nanoTime()
                    + (leading ? ADVANCED_LEAD_DEADLINE_MS : ADVANCED_FOLLOW_DEADLINE_MS) * 1_000_000L;
            final java.util.Random rnd = new java.util.Random();
            boolean queued = AiThinkDispatcher.heavy(manager.getPlugin(),
                    () -> DdzRolloutAi.bestPlay(hand, target, lastSeat, seat, landlord, unknown,
                            sizes, deadline, ADVANCED_SAMPLES, rnd,
                            round.getWildRank(), round.getHeavenRank()),
                    cards -> {
                        aiThinking = false;
                        if (token != thinkToken || round == null) {
                            return;
                        }
                        applyAiPlay(seat, cards, leading);
                    });
            if (queued) {
                return;
            }
            // 队列满 ⇒ 立刻回落初级，不能让回合停住
            manager.getPlugin().getLogger().warning("[TabletopGames] 高级机器人队列已满，本手回落初级");
        }

        AiThinkDispatcher.light(manager.getPlugin(),
                () -> DdzBotAi.decidePlay(hand, target, lastSeat, landlord, seat,
                        DdzDifficulty.SIMPLE, round.getWildRank(), round.getHeavenRank()),
                d -> {
                    aiThinking = false;
                    if (token != thinkToken || round == null) {
                        return;
                    }
                    applyDecision(seat, d);
                });
    }

    /**
     * 「我还没见过的牌」= 另外两家的手牌并集。
     *
     * <p>这是<b>公开信息</b>（等价于「54 − 我的手牌 − 已打出」），高级档只是拿它随机分配给两家，
     * 并不知道谁有哪张 —— 这就是它和 ratel 原版「直接偷看两家手牌」的区别。</p>
     */
    private List<Integer> unknownCards(int seat) {
        List<Integer> out = new ArrayList<>(34);
        if (round == null) {
            return out;
        }
        for (int s = 0; s < 3; s++) {
            if (s != seat) {
                out.addAll(round.getHand(s));
            }
        }
        return out;
    }

    /** 落子：初级 / 高级共用（Decision 版本）。 */
    private void applyDecision(int seat, DdzBotAi.Decision d) {
        if (d == null) {
            return;
        }
        switch (d.type()) {
            case PLAY -> {
                if (round.play(seat, d.cards()) == null) {
                    selected[seat] = 0;
                    moveCardsToPlayZone(seat, d.cards());
                    afterStep();
                }
            }
            case PASS -> {
                if (round.pass(seat) == null) {
                    playedCards[seat] = null;
                    selected[seat] = 0;
                    afterStep();
                }
            }
            default -> afterStep();
        }
    }

    /**
     * 落子：高级档的推演结果（只是一个牌列表）。
     *
     * <p>三道兜底：算崩了返回 null、首出却给空、以及 {@code round.play} 被拒 —— 都不允许
     * 让回合停住，否则玩家看到的是「机器人卡死到超时」。</p>
     */
    private void applyAiPlay(int seat, List<Integer> cards, boolean leading) {
        if (cards != null && !cards.isEmpty() && round.play(seat, cards) == null) {
            selected[seat] = 0;
            moveCardsToPlayZone(seat, cards);
            afterStep();
            return;
        }
        if (!leading && round.pass(seat) == null) {
            playedCards[seat] = null;
            selected[seat] = 0;
            afterStep();
            return;
        }
        // 首出不能过：打最小的一张把回合推走
        List<Integer> hand = round.getHand(seat);
        if (!hand.isEmpty()) {
            List<Integer> min = List.of(DdzCards.sortedCopy(hand).get(0));
            if (round.play(seat, min) == null) {
                selected[seat] = 0;
                moveCardsToPlayZone(seat, min);
                afterStep();
                return;
            }
        }
        // 真的走不动了（不该发生）：交给超时兜底，避免死循环
        manager.getPlugin().getLogger().warning("[TabletopGames] 机器人无法推进回合：seat=" + seat);
    }

    /** 一步动作结束后统一收尾（推进 / 换人 / 结算）。 */
    private void afterStep() {
        aiThinking = false;
        aiThinkingTicks = 0;
        if (round == null) {
            return;
        }
        // 手牌 / 上一手 / 行动方都可能刚变，压牌判断缓存立即作废
        invalidateBeatCache();
        if (round.getPhase() == DdzRound.Phase.FINISHED) {
            doFinish();
            return;
        }
        if (round.getPhase() == DdzRound.Phase.REDEAL) {
            tickPlaying();
            return;
        }
        // 新一轮（两家都不出之后 lastPlay 归 null）：桌面必须清空，
        // 否则上一轮的牌会一直留在出牌区，看起来像"三手同时摆着"
        if (round.getPhase() == DdzRound.Phase.PLAYING && round.isLeading()) {
            java.util.Arrays.fill(playedCards, null);
        }
        // 地主刚定 → 收下底牌（含「底牌飞到地主手里」动作）
        if (round.getLandlord() >= 0 && !bottomTaken) {
            takeBottomCards();
        }
        beginTurn();
    }

    // ------------------------------------------------------------------
    // 出牌区
    // ------------------------------------------------------------------

    /**
     * 记下该座位这一手打出的牌并重排桌面。
     *
     * <p>⚠️ 顺序不能反过来：必须<b>先记 {@link #playedCards}</b> 再 {@link #syncTableZones()}。
     * 初版是先 sync 再逐个查 {@code cardSlotOf[id]}，可这时牌已经不在手牌里、
     * 卡槽全是 −1 ⇒ 打出的牌一张都上不了桌；更糟的是它随后用一个**全新的空 zones**
     * 调 {@code setCardZones}，把刚算好的手牌区一起抹掉了（整排手牌消失）。</p>
     */
    private void moveCardsToPlayZone(int seat, List<Integer> cards) {
        playedCards[seat] = new ArrayList<>(cards);
        syncTableZones();
        // 出牌飞行（2026-10-20）：播模组移植的 play_flight（54 骨骼，按 button_owner
        // 位域判行动座位 + 卡片区号，从手牌位置飞到出牌区）。位域读法已从模组的
        // bits4-5 改成我们的 bits2-3（button_owner = 座位+1，映射恰好一致）。
        // ⚠️ 必须在 flush()/sync() 之前发包：sync 会更新 button_owner 到下一家，
        //    客户端的动画 Molang 读的是当前属性值，晚发会读到错误的行动座位。
        sendActionAnim("animation.tabletopgame.ddz.play_flight",
                "tabletopgame:ddz_play", 0.32f);
    }

    /**
     * 某座位手牌的卡槽数组（<b>按卡槽号升序 = 展示顺序</b>）。
     *
     * <p>⚠️ 必须排序后再返回：客户端 {@code v.sN} 是「该卡槽在本区内出现的先后」，
     * 即<b>卡槽号升序</b>，而 {@link #round#getHand} 按牌面大小排。射线按数组下标算
     * 牌位，若这里给牌面序，算出的位置就与屏幕上那张牌对不上 —— 真机症状是
     * 「准星指着这张，高亮却打在旁边那张」。选中位掩码 {@code selected[]} 也按这里的
     * 下标编码，所以 {@link #selectedCards}/{@link #maskOf} 都必须以本方法为准。</p>
     */
    private int[] ownHandArray(int seat) {
        if (round == null || seat < 0 || seat > 2) {
            return EMPTY_HAND;
        }
        // 卡槽号 ≡ 牌 id，所以「升序 id 数组」就是「按卡槽号升序的卡槽数组」
        List<Integer> hand = round.getHand(seat);
        int[] out = new int[hand.size()];
        int n = 0;
        for (int id : hand) {
            if (id >= 0) {
                out[n++] = id;
            }
        }
        out = java.util.Arrays.copyOf(out, n);
        java.util.Arrays.sort(out);
        // 2026-10-26 用户：癞子算<b>最大</b>（排在 2 后面），癞子之间按 id 升序。
        // 客户端 v.sN 是按卡槽号数的，所以必须在服务端把数组本身按同一顺序给出，
        // 否则射线算出的牌与屏幕上那张对不上（同 v.dN 镜像那次踩过的坑）。
        return sortWildLast(out);
    }

    /** 把一个升序 id 数组重排成「非癞子（id 升序）→ 癞子（id 升序）」。 */
    private int[] sortWildLast(int[] ids) {
        int n = ids.length;
        int[] out = new int[n];
        int k = 0;
        for (int id : ids) {
            if (!isWildCard(id)) {
                out[k++] = id;
            }
        }
        for (int id : ids) {
            if (isWildCard(id)) {
                out[k++] = id;
            }
        }
        return k == n ? out : ids;
    }

    /** 这张牌在本局算不算癞子（天 / 地两个点数都算）。 */
    private boolean isWildCard(int id) {
        return round != null && DdzCards.isWild(id, round.getHeavenRank(), round.getWildRank());
    }

    /** 当前选中的牌 id（位掩码下标 = {@link #ownHandArray} 的下标，即展示序号）。 */
    private List<Integer> selectedCards(int seat) {
        List<Integer> out = new ArrayList<>();
        if (round == null) {
            return out;
        }
        int[] hand = ownHandArray(seat);
        int mask = selected[seat];
        // 上限 32（int 位掩码），不是 18：地主拿底牌后有 20 张
        for (int i = 0; i < hand.length && i < 32; i++) {
            if (((mask >>> i) & 1) != 0) {
                out.add(hand[i]);
            }
        }
        return out;
    }

    /**
     * 把「一组牌 id」转成选中位掩码（bit N = 手牌第 N 张被选中）。
     * <p>
     * ⚠️ 必须按<b>展示序号</b>而不是卡槽号：客户端 {@code v.selN} 读的是
     * 「该卡槽在区内是第几个」，服务端掩码的第 N 位也必须是同一个 N，
     * 否则高亮会亮在别的牌上（看着像随机高亮）。
     */
    private int maskOf(int seat, List<Integer> cards) {
        if (round == null || cards == null || cards.isEmpty()) {
            return 0;
        }
        int[] hand = ownHandArray(seat);
        int mask = 0;
        for (Integer id : cards) {
            int idx = -1;
            for (int i = 0; i < hand.length; i++) {
                if (hand[i] == id) {
                    idx = i;
                    break;
                }
            }
            // 上限 32（int 位掩码）：地主 20 张，卡 18 会让「提示」选中的第 19/20 张丢失
            if (idx >= 0 && idx < 32) {
                mask |= (1 << idx);
            }
        }
        return mask;
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /**
     * 使用牌桌（手持定位器时触发）。
     *
     * @param allowMenu 未命中时是否弹菜单
     * @return 是否吃掉了这次点击
     */
    public boolean useBoard(Player p, boolean allowMenu) {
        UUID id = p.getUniqueId();
        int s = seatOf(id);
        if (s < 0) {
            if (allowMenu) {
                DdzMenu.open(p, this);
            }
            return false;
        }
        if (online[s] == null || !online[s].isOnline()) {
            reattach(p);
        }
        if (phase == TablePhase.SETTLED) {
            // 结算期：桌面上常驻「退出 / 下一局」两枚贴图按钮（2026-10-24 照 UNO 搬来），
            // 先判这两枚；点空处仍然开菜单（菜单里还有托管 / 开始游戏等）。
            // ⚠️ 初版没有按钮骨骼，只能"点桌面就开菜单"；别把这里改回无条件开菜单，
            //    否则按钮永远点不到（事件先被菜单吃掉）。
            int[] hand0 = ownHandArray(s);
            int[] btns = settleButtons();
            DdzTableRay.Hit hit0 = DdzTableRay.raycast(p, origin.x, origin.y, origin.z,
                    s, s, hand0, btns, 0, handFlipped());
            int btn0 = DdzTableEntity.hoveredButtonSlot(hit0.hover());
            if (btn0 >= 0) {
                pressButton(p, s, btn0);
                return true;
            }
            if (allowMenu) {
                DdzMenu.open(p, this);
            }
            // 空中右键（allowMenu=false）不该被吞：那里什么也没做，放行才是对的
            return allowMenu;
        }
        if (phase != TablePhase.PLAYING) {
            if (allowMenu) {
                DdzMenu.open(p, this);
            }
            return false;
        }
        int[] hand = ownHandArray(s);
        // 结算期的按钮「人人可点」⇒ 把 viewer 自己当成操作方传下去（射线只认
        // viewerSeat == actorSeat）；其余阶段仍按行动方门控。
        boolean act = phase == TablePhase.SETTLED || (s == actorSeat() && !autoPlayed(s));
        int[] buttons = act ? globalButtons() : EMPTY_BUTTONS;
        int actor = act ? s : actorSeat();
        DdzTableRay.Hit hit = DdzTableRay.raycast(p, origin.x, origin.y, origin.z,
                s, actor, hand, buttons, selected[s], handFlipped());
        int hv = hit.hover();
        if (hv == DdzTableEntity.HOVER_NONE) {
            // 2026-10-23 用户要求：牌与牌、按钮与按钮之间都有缝，缝里点一下以前会弹出配置表单。
            // 现在整片「手牌 + 按钮」都算交互区，落在里面就吞掉点击、什么都不做；
            // 只有这片区域**之外**的桌面才开菜单。
            if (allowMenu && !DdzTableRay.hitsInteractiveZone(p, origin.x, origin.y, origin.z,
                    s, actor, hand, buttons)) {
                DdzMenu.open(p, this);
            }
            return false;
        }
        int slot = DdzTableEntity.hoveredCardSlot(hv);
        if (slot >= 0) {
            selectCard(p, s, slot);
            return true;
        }
        int btn = DdzTableEntity.hoveredButtonSlot(hv);
        if (btn >= 0) {
            pressButton(p, s, btn);
            return true;
        }
        return false;
    }

    /**
     * 是否正「用着」这张桌：已入座 + 玩家仍在这张桌的识别半径内。
     *
     * <p>空中右键（悬空手牌 / 桌面按钮）的前置条件，与 UNO 的 {@code inUseRange} 同构。
     * 未入座的人看桌面按钮没有意义（按钮只对行动方可见），所以必须先过这一关。</p>
     */
    public boolean inUseRange(Player p) {
        return p != null && seatOf(p.getUniqueId()) >= 0 && inRange(p);
    }

    /**
     * 空中右键：准星落在方块之外，但玩家正瞄着悬空的手牌或桌面按钮。
     *
     * <p>与 {@link #useBoard} 的差别只有一处：<b>没命中也不开菜单</b>。
     * 空中右键平时（吃东西、用道具、别的插件的物品）有自己的语义，不能乱拦 ——
     * 只有真的射线命中手牌 / 按钮才吞掉事件。</p>
     *
     * @return true = 已命中并处理（调用方应 {@code setCancelled(true)}）
     */
    public boolean useBoardAir(Player p) {
        if (!inUseRange(p)) {
            return false;
        }
        return useBoard(p, false);
    }

    /**
     * 准星此刻是否压在**手牌或按钮**上（只看不点，2026-10-22）。
     *
     * <p>定位器「蹲下 + 使用」切换准星时要避开这些位置：用户原话「触发到了我们牌、
     * 按钮区域投影位置的也不算」。射线参数与 {@link #useBoard(Player, boolean)} 完全一致，
     * 但只返回命中与否，<b>不执行任何动作</b>。</p>
     */
    public boolean hasTarget(Player p) {
        int s = seatOf(p.getUniqueId());
        if (s < 0 || phase != TablePhase.PLAYING || !inUseRange(p)) {
            return false;
        }
        int[] hand = ownHandArray(s);
        // 结算期的按钮「人人可点」⇒ 把 viewer 自己当成操作方传下去（射线只认
        // viewerSeat == actorSeat）；其余阶段仍按行动方门控。
        boolean act = phase == TablePhase.SETTLED || (s == actorSeat() && !autoPlayed(s));
        int[] buttons = act ? globalButtons() : EMPTY_BUTTONS;
        int actor = act ? s : actorSeat();
        DdzTableRay.Hit hit = DdzTableRay.raycast(p, origin.x, origin.y, origin.z,
                s, actor, hand, buttons, selected[s], handFlipped());
        return hit.hover() != DdzTableEntity.HOVER_NONE;
    }

    /** 点击手牌：切换选中（按<b>卡槽</b>找展示序号）。 */
    private void selectCard(Player p, int seat, int cardSlot) {
        if (round == null || phase != TablePhase.PLAYING) {
            return;
        }
        // ⚠️ 不判回合（2026-10-21 用户要求）：点手牌只是切换「提起」这个本地选中态，
        //    不改任何游戏状态，所以别人回合也该能点 —— 提前把准备出的牌抬起来，
        //    轮到自己直接点「出牌」。真正的出牌 / 不出 / 提示仍然由 pressButton 判回合。
        int[] slots = ownHandArray(seat);
        int idx = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == cardSlot) {
                idx = i;
                break;
            }
        }
        // ⚠️ 上限是 32（int 位掩码的位数），不是 18：
        //    地主拿 3 张底牌后有 **20 张**手牌，卡 18 会让第 19、20 张永远点不中。
        //    （客户端 54 位选中状态现在是按卡槽存的，20 张完全装得下。）
        if (idx < 0 || idx >= 32) {
            return;
        }
        selected[seat] ^= (1 << idx);
        flush();
    }

    /** 结算。 */
    private void doFinish() {
        if (round == null || round.getResult() == null) {
            return;
        }
        phase = TablePhase.SETTLED;
        settleTicks = SETTLE_TICKS;
        // 对局结束立即取消托管（UNO clearTrustOnSettle 同款，2026-10-20）：
        // 不等下一局，结算当帧清零 + 通知，玩家看到的是「已自动取消托管」。
        for (int s = 0; s < 3; s++) {
            if (trusted[s]) {
                trusted[s] = false;
                Player p = online[s];
                if (p != null && p.isOnline()) {
                    p.sendTip("§7已自动取消托管。");
                }
            }
        }
        boolean[] human = new boolean[3];
        for (int s = 0; s < 3; s++) {
            human[s] = humans[s] != null && bots[s] == null;
        }
        DdzSettlement.Entry[] entries = DdzSettlement.settle(round, view, human);
        // 扣款与结算摘要都在这一个方法里（唯一扣钱点）
        String ecoText = settleEconomy(entries);
        settleText = buildSettleText(entries, ecoText);
        // 投票行每次重算都基于这一份原文，避免越拼越长（见 refreshNextVoteLine）
        settleBase = settleText;
        java.util.Arrays.fill(nextVote, false);
        DdzRound.Result r = round.getResult();
        String side = "landlord".equals(r.winnerSide) ? "地主" : "农民";
        broadcastEvent("§6本局结束：§f" + seatName(r.winnerSeat) + " §7是本局" + side
                + (r.spring ? " §e（春天 ×2）" : "") + " §7倍数 §e×" + r.multiplier);
        // 结算公开手牌（UNO 同款 v.reveal 原理）：sync() 在 phase==SETTLED 时置
        // table bit4 (REVEAL) ⇒ 客户端所有手牌**竖着翻开展示牌面**，位置不动。
        // 不再播 debug_real 动画（旧方案 viewer_mode=3 会被下一局 refreshViewers 覆盖）。
        // 下一局 startDeal → phase=DEALING → sync() 自动清 reveal。
        syncTableZones();
        flush();
    }

    /**
     * 结算扣款（<b>唯一</b>扣钱的地方），与 {@code UnoTable} 完全同口径：
     * <pre>
     *   输家各扣 min(应付, 实际余额)  →  汇成奖池 pot
     *   抽水 commission = ceil(pot × cut / 100)
     *   赢家实得 = pot − commission
     * </pre>
     *
     * <p>⚠️ 用户 10-20 明确：<b>UNO 与斗地主都是对局结束才扣</b>（只有 5 类棋是开局扣押金）。
     * 所以入座/开局都不扣钱，全靠这里一次结清；余额不足时「扣到 0 为止」，绝不出现负余额。</p>
     *
     * <p>机器人：余额无限，应扣额<b>全额进奖池</b>（没有账户可扣），与 UNO 的
     * {@code settleEco} 一致。机器人赢则奖金不入账。</p>
     *
     * @return 结算摘要（写进结算板）；null = 免费场 / 尚无可结算数据
     */
    private String settleEconomy(DdzSettlement.Entry[] entries) {
        if (view == null || view.isFree() || round == null || round.getResult() == null) {
            return null;
        }
        int winner = round.getResult().winnerSeat;
        double cut = view.getCut();
        double pot = 0.0;
        for (DdzSettlement.Entry e : entries) {
            if (e == null || e.seat >= 3 || e.seat == winner || e.payable() <= 0.0) {
                continue;
            }
            double owed = e.payable();
            UUID id = humans[e.seat];
            if (id == null) {
                pot += owed;
                continue;
            }
            double paid = Math.min(owed, Math.max(0.0, EconomyHook.balance(id)));
            if (paid <= 0.0) {
                Player loser = online[e.seat];
                if (loser != null && loser.isOnline()) {
                    loser.sendTip("§c余额为 0，本局不再扣款。");
                }
                continue;
            }
            if (!EconomyHook.reduce(id, paid)) {
                continue;
            }
            pot += paid;
            if (paid < owed) {
                Player loser = online[e.seat];
                if (loser != null && loser.isOnline()) {
                    loser.sendTip("§c余额不足，本局只扣到 §e" + fmtNum(paid) + " §c。");
                }
            }
        }
        pot = round2(pot);
        if (pot <= 0.0) {
            return "§7本局无人有款可扣，奖池为空，赢家未获得奖金。";
        }
        double commission = round2(Math.ceil(pot * cut / 100.0 * 100.0) / 100.0);
        double prize = round2(Math.max(0.0, pot - commission));
        StringBuilder sb = new StringBuilder();
        sb.append("§6奖池 §e").append(fmtNum(pot))
                .append(" §6· 抽水 §e").append(fmtNum(commission))
                .append("§6（").append(fmtNum(cut)).append("%）");
        if (humans[winner] != null) {
            EconomyHook.add(humans[winner], prize);
            sb.append(" §6· 赢家实得 §e").append(fmtNum(prize));
        } else {
            sb.append(" §7· 人机赢，奖金不入账");
        }
        return sb.toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 结算板文本（显示在动作栏）。
     *
     * @param entries 各家的<b>理论</b>输赢（来自 round 的零和 moneyDelta）
     * @param ecoText 实际结算摘要（奖池 / 抽水 / 实得），由 {@link #settleEconomy} 生成；
     *                null = 免费场
     */
    private String buildSettleText(DdzSettlement.Entry[] entries, String ecoText) {
        DdzRound.Result r = round.getResult();
        StringBuilder sb = new StringBuilder();
        sb.append("§6本局结算 §7| §e×").append(r.multiplier);
        if (r.spring) {
            sb.append(" §c春天");
        }
        sb.append(" §7| §f").append(seatName(r.winnerSeat))
                .append(" §a").append("landlord".equals(r.winnerSide) ? "地主胜" : "农民胜");
        for (int s = 0; s < 3; s++) {
            if (s == r.winnerSeat) {
                continue;
            }
            DdzSettlement.Entry e = entries[s];
            sb.append('\n').append("§f").append(seatName(s));
            if (e.payable() > 0.0) {
                sb.append(" §c应付 ").append(fmtNum(e.payable()));
            } else {
                sb.append(" §7不参与（人机/免费）");
            }
        }
        sb.append('\n').append(ecoText == null ? "§7免费场，不进行金钱结算。" : ecoText);
        return sb.toString();
    }

    /**
     * 结算期推进。
     *
     * <p>⚠️ <b>不自动收档</b>（与 UNO 的 10-16 终案同口径）：结算后必须由玩家投票
     * 「下一局」或自己退出，服务端不会到点清场。初版这里是 {@code --settleTicks<=0 →
     * resetToWaiting()}，而 {@code resetToWaiting} 会清空全部座位 —— 玩家还在桌边站着
     * 却已经不在座位上了，等于把「不自动收档」做成了「8 秒后强制踢人」。</p>
     *
     * <p>唯一的收档条件是<b>桌上真的没人了</b>（所有人都退出 / 全是机器人且桌主走了），
     * 此时清掉实体与方块，免得留一张空桌占资源。</p>
     */
    private void tickSettled() {
        // 每秒无条件重发一次结算板（UNO showSettleTip 同款）：走 forceTip 绕过
        // setCountdown 的 eventTipHold / tipRepeat 节流，否则会被叠成 ~10 秒一次。
        // 新走进视距 / 重连的玩家也能在下一秒收到。
        if (settleTicks % TICKS_PER_SECOND == 0) {
            forceTip(settleText);
        }
        settleTicks++;
        if (settleTicks == TICKS_PER_SECOND * 8) {
            // 展示时间到：结算板继续留着，但提醒一次怎么往下走
            // （有人退出过就没有「下一局」这条路了，提示换成等桌主重开）
            String tip = seatVacated
                    ? "§7本局有人退出，不再续局：三家坐齐后由桌主点「开始游戏」。"
                    : "§7点桌面，在菜单里确认「下一局」；也可以直接退出牌桌。";
            for (Player p : viewers()) {
                p.sendTip(tip);
            }
        }
        if (occupiedSeats() == 0) {
            resetToWaiting();
        }
    }

    /**
     * 投「下一局」票（<b>可取消</b>）。
     *
     * <p>菜单上的文案是「已确认（再点取消）」，所以这里必须是 toggle ——
     * 初版无条件置 true，玩家点了就再也退不掉，只能等别人全确认或直接退出牌桌。</p>
     */
    public String voteNext(Player p) {
        int s = seatOf(p.getUniqueId());
        if (s < 0) {
            return "你不在座位上。";
        }
        if (phase != TablePhase.SETTLED) {
            return "现在不是结算阶段。";
        }
        if (seatVacated) {
            // 本局有人退出（离席 / 断线 / 走出半径 / 清人机）⇒ 续局链已断。
            // 与 UNO 一致：这时不给「下一局」，坐齐后由桌主点「开始游戏」。
            return "本局有人退出，不能续局：等三家坐齐后点「开始游戏」。";
        }
        nextVote[s] = !nextVote[s];
        refreshNextVoteLine();
        // 只有「刚投下确认票」才可能触发开局；取消时绝不开局
        if (nextVote[s] && allNextVotesIn()) {
            startNextRound();
        }
        return null;
    }

    public boolean hasVotedNext(int seat) {
        return seat >= 0 && seat < 3 && nextVote[seat];
    }

    private int nextVoteNeeded() {
        int n = 0;
        for (int s = 0; s < 3; s++) {
            if (humans[s] != null && online[s] != null && online[s].isOnline()) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    /**
     * 已投票的座位数。
     *
     * <p>只算「在座 + 在线」的真人：玩家投完票就下线（或退出牌桌清了座位）时，
     * {@code nextVote[s]} 还留着 true，若按裸标记计数会让门槛虚高、永远开不了下一局。</p>
     */
    private int nextVoteDone() {
        int n = 0;
        for (int s = 0; s < 3; s++) {
            if (nextVote[s] && humans[s] != null && online[s] != null && online[s].isOnline()) {
                n++;
            }
        }
        return n;
    }

    private boolean allNextVotesIn() {
        if (nextVoteDone() < nextVoteNeeded()) {
            return false;
        }
        // 人事变动（有人跑了）→ 重新算门槛
        return nextVoteDone() >= 1;
    }

    /** 结算文本的原始版本：投票行会被反复重算，必须基于它而不是上一次的结果。 */
    private String settleBase;

    /**
     * 刷新「继续投票 N/M」那一行。
     *
     * <p>⚠️ 必须从 {@link #settleBase} 重新拼，不能 {@code settleText += ...} ——
     * 那样每投一次票（含取消）就多堆一行，投三次之后结算板直接刷屏。</p>
     */
    private void refreshNextVoteLine() {
        if (settleBase == null) {
            return;
        }
        settleText = settleBase + "\n§7继续投票 §f" + nextVoteDone() + "/" + nextVoteNeeded()
                + " §7（点桌面投票，再点取消）";
        forceTip(settleText);
    }

    /**
     * 全员确认「下一局」→ <b>直接开</b>。
     *
     * <p>初版这里只是回 {@code WAITING} 再播一句「桌主点开始游戏」—— 那投票就白投了
     * （跟直接退出再开始没区别），全员的确认动作应该等价于开局。
     * 若开场校验失败（有人余额不够付门票），退回 WAITING 并说明原因。</p>
     */
    private void startNextRound() {
        settleText = null;
        settleBase = null;
        countdownText = null;
        // 托管已在 doFinish() 结算当帧清零（UNO 同口径），这里只兜底防漏
        for (int s = 0; s < 3; s++) {
            trusted[s] = false;
        }
        java.util.Arrays.fill(nextVote, false);
        String err = startRound();
        if (err != null) {
            phase = TablePhase.WAITING;
            round = null;
            clearTable();
            broadcast("§c" + err + "§7 请处理后点「开始游戏」。");
            flush();
        }
    }

    /** 回等待态（清托管、收定位器、腾空位）。 */
    private void resetToWaiting() {
        settleText = null;
        settleBase = null;
        countdownText = null;
        phase = TablePhase.WAITING;
        round = null;
        clearTable();
        recycleAllFlyCards();
        for (int s = 0; s < 3; s++) {
            trusted[s] = false;
            if (online[s] != null) {
                takeLocator(online[s]);
            }
            humans[s] = null;
            online[s] = null;
            bots[s] = null;
            names[s] = null;
            away[s] = false;
        }
        syncSeats();
        flush();
    }

    // ------------------------------------------------------------------
    // 座位操作
    // ------------------------------------------------------------------

    /**
     * 入座归位：摆到站位点并转向棋心。
     * <p>
     * ⚠️ 坐标系铁律：{@code z = center.z - OUT_Z·R}（Z 轴反号）。写成 {@code +} 会让
     * 南北两位整体对调，而 viewer 那一套（手牌 / 按钮）仍渲染在模型指定的一侧 ⇒
     * 玩家自己的牌全跑到对面（UNO 真机踩过）。
     *
     * @return null = 成功，否则为原因
     */
    public String standAtSeat(Player p, int seat) {
        if (p == null || seat < 0 || seat > 2) {
            return "座位不合法。";
        }
        double x = center.x + DdzTableRay.OUT_X[seat] * SEAT_STAND_RADIUS;
        double z = center.z - DdzTableRay.OUT_Z[seat] * SEAT_STAND_RADIUS;
        int standY = Integer.MIN_VALUE;
        for (int dy = 0; dy <= SEAT_SEEK_UP; dy++) {
            if (isStandable(x, baseY + dy, z)) {
                standY = baseY + dy;
                break;
            }
        }
        if (standY == Integer.MIN_VALUE) {
            return seatLabel(seat) + " 那边的站位被方块挡住了。";
        }
        double dx = center.x - x;
        double dz = center.z - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double horiz = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
        double eyeY = standY + p.getEyeHeight();
        float pitch = (float) Math.toDegrees(Math.atan2(baseY + SEAT_FACE_TARGET_Y - eyeY, horiz));
        p.teleport(new cn.nukkit.level.Location(x, standY, z, yaw, pitch, level),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setRotation(yaw, pitch);
        return null;
    }

    private boolean isStandable(double x, int y, double z) {
        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        return !level.getBlock(fx, y, fz).isSolid() && !level.getBlock(fx, y + 1, fz).isSolid();
    }

    /**
     * 入座。
     * @return null = 成功，否则为拒绝原因
     */
    public String join(Player p, int seat) {
        if (seat < 0 || seat > 2) {
            return "座位不合法。";
        }
        UUID id = p.getUniqueId();
        int existing = seatOf(id);
        if (existing == seat) {
            reattach(p);
            return null;
        }
        if (existing >= 0) {
            return "你已经坐在" + seatLabel(existing) + "了，请先退出。";
        }
        if (humans[seat] != null) {
            return seatLabel(seat) + " 已经被占了。";
        }
        String stakeErr = checkJoinStake(p);
        if (stakeErr != null) {
            return stakeErr;
        }
        humans[seat] = id;
        online[seat] = p;
        bots[seat] = null;
        trusted[seat] = false;
        away[seat] = false;
        names[seat] = p.getName();
        selected[seat] = 0;
        hover[seat] = 0;
        String err = standAtSeat(p, seat);
        if (err != null) {
            humans[seat] = null;
            online[seat] = null;
            names[seat] = null;
            return err;
        }
        // 定位器：**入座即发**（2026-10-27 用户统一口径，与棋类 / UNO / 德州扑克一致）。
        // 等待期虽然没有悬空的牌，但「手持定位器的使用信号」也是准星叠层的触发条件，
        // 早发一把能让玩家一坐下就看到十字准星，不必等开局。
        ensureLocator(p);
        syncSeats();
        flush();
        refreshViewers();
        broadcast("§f" + p.getName() + " §7坐上了" + seatLabel(seat));
        return null;
    }

    /**
     * 本桌场次的底分（免费场 0）。
     *
     * <p>⚠️ 不要用 {@code ddzConfig().baseScore()} —— 那是<b>全局默认档位</b>，
     * 每张桌可以在「牌桌配置」里各选各的场次。</p>
     */
    public double baseScoreOfTier() {
        return tier == null ? 0.0 : tier.baseScore();
    }

    /**
     * 本桌的入座门槛 = 本桌底分 × 全局「入座门槛系数」（免费场 0）。
     * <p>只用于校验余额，不扣钱（钱在对局结束时才结算）。
     */
    public double stakeOfTier() {
        double base = baseScoreOfTier();
        if (base <= 0.0) {
            return 0.0;
        }
        var cfg = ddzConfig();
        double mult = cfg == null ? 10.0 : cfg.normalizedJoinBalanceMultiplier();
        return Math.round(base * mult * 100.0) / 100.0;
    }

    /** 本桌当前场次。 */
    public TabletopGamesConfig.DdzTier getTier() {
        return tier == null ? TabletopGamesConfig.DdzTier.FREE : tier;
    }

    /** 本桌默认机器人难度（加人机时 {@code null} 就用它）。 */
    public DdzDifficulty getDifficulty() {
        return difficulty == null ? DdzDifficulty.ADVANCED : difficulty;
    }

    /**
     * 从存档恢复每桌配置（{@code DdzManager#load()} 重建牌桌后调用）。
     *
     * <p>构造函数故意固定 {@code FREE}（2026-10-22 用户要求「新牌桌默认免费场」），
     * 所以恢复不能指望构造参数，必须走这里。</p>
     */
    public void restoreConfig(TabletopGamesConfig.DdzTier tier, DdzDifficulty diff) {
        restoreConfig(tier, diff, null);
    }

    /** 同上，带「定地主方式」（{@code null} / 空串 = 老存档 ⇒ 叫抢制）。 */
    public void restoreConfig(TabletopGamesConfig.DdzTier tier, DdzDifficulty diff,
                              TabletopGamesConfig.DdzBidMode mode) {
        restoreConfig(tier, diff, mode, TabletopGamesConfig.DdzWildMode.OFF, null);
    }

    /** 同上，再带「癞子玩法」（{@code null} = 老存档 ⇒ 关闭）。 */
    public void restoreConfig(TabletopGamesConfig.DdzTier tier, DdzDifficulty diff,
                              TabletopGamesConfig.DdzBidMode mode,
                              TabletopGamesConfig.DdzWildMode wild,
                              TabletopGamesConfig.DdzHandSort sort) {
        this.tier = tier == null ? TabletopGamesConfig.DdzTier.FREE : tier;
        this.difficulty = diff == null ? DdzDifficulty.ADVANCED : diff;
        this.bidMode = mode == null ? TabletopGamesConfig.DdzBidMode.CALL_ROB : mode;
        this.wildMode = wild == null ? TabletopGamesConfig.DdzWildMode.OFF : wild;
        this.handSort = sort == null ? TabletopGamesConfig.DdzHandSort.BIG_LEFT : sort;
    }

    /** 本桌的定地主方式。 */
    public TabletopGamesConfig.DdzBidMode getBidMode() {
        return bidMode == null ? TabletopGamesConfig.DdzBidMode.CALL_ROB : bidMode;
    }

    /**
     * 改本桌的定地主方式（<b>下一局生效</b>）。
     *
     * <p>对局中禁止改：本局的叫牌阶段机已经按旧方式建好了（{@code DdzRound} 里
     * {@code scoreBidding} 是 final），中途换会让「不叫 / 1分」两套按钮对不上状态。</p>
     *
     * @return null = 成功
     */
    public String setBidMode(TabletopGamesConfig.DdzBidMode next) {
        if (next == null) {
            return "定地主方式不合法。";
        }
        if (phase == TablePhase.PLAYING || phase == TablePhase.DEALING) {
            return "对局进行中，打完这一局才能改定地主方式。";
        }
        if (next == getBidMode()) {
            return null;
        }
        TabletopGamesConfig.DdzBidMode old = getBidMode();
        bidMode = next;
        broadcast("§f" + old.label() + " §7→ §f" + next.label() + "§7（下一局生效）");
        flush();
        return null;
    }

    /** 本桌的玩法模式。 */
    public TabletopGamesConfig.DdzWildMode getWildMode() {
        return wildMode == null ? TabletopGamesConfig.DdzWildMode.OFF : wildMode;
    }

    /** 本桌是否开启癞子玩法（地赖子 / 天地癞子都算开启）。 */
    public boolean isWildEnabled() {
        return getWildMode() != TabletopGamesConfig.DdzWildMode.OFF;
    }

    /**
     * 改玩法模式（<b>下一局生效</b>）。
     *
     * <p>对局中禁止改：本局的 {@code DdzRound.wildEnabled} 是 final，
     * 且癞子点数在发牌 / 定地主那一刻就抽定了，中途换会让桌面上的金版牌与规则对不上。</p>
     *
     * @return null = 成功
     */
    public String setWildMode(TabletopGamesConfig.DdzWildMode next) {
        if (next == null) {
            return "玩法模式不合法。";
        }
        if (phase == TablePhase.PLAYING || phase == TablePhase.DEALING) {
            return "对局进行中，打完这一局才能改玩法模式。";
        }
        if (next == getWildMode()) {
            return null;
        }
        TabletopGamesConfig.DdzWildMode old = getWildMode();
        wildMode = next;
        broadcast("§f" + old.label() + " §7→ §f" + next.label() + "§7（下一局生效）");
        flush();
        return null;
    }

    /**
     * 兼容入口：{@code true} = 天地癞子，{@code false} = 普通。
     *
     * <p>⚠️ 2026-10-27 取消了「地癞子」档，所以 {@code true} 现在开的是天地癞子。</p>
     */
    public String setWildEnabled(boolean next) {
        return setWildMode(next ? TabletopGamesConfig.DdzWildMode.HEAVEN
                : TabletopGamesConfig.DdzWildMode.OFF);
    }

    /** 手牌是否反序（{@code true} = 最大在左），拾取器要跟着镜像。 */
    private boolean handFlipped() {
        return getHandSort() == TabletopGamesConfig.DdzHandSort.BIG_LEFT;
    }

    /** 本桌的手牌左右排序。 */
    public TabletopGamesConfig.DdzHandSort getHandSort() {
        return handSort == null ? TabletopGamesConfig.DdzHandSort.BIG_LEFT : handSort;
    }

    /**
     * 改手牌左右排序（<b>立刻生效</b>，对局中也能改）。
     *
     * <p>与 {@link #setBidMode} / {@link #setWildMode} 不同，这里<b>没有阶段守卫</b>：
     * 排序纯粹是画面，客户端按属性重排，不影响叫牌状态机、癞子点数与任何账目。</p>
     *
     * @return null = 成功
     */
    public String setHandSort(TabletopGamesConfig.DdzHandSort next) {
        if (next == null) {
            return "手牌排序不合法。";
        }
        if (next == getHandSort()) {
            return null;
        }
        TabletopGamesConfig.DdzHandSort old = getHandSort();
        handSort = next;
        broadcast("§f手牌排序 §7" + old.label() + " §7→ §f" + next.label());
        flush();
        return null;
    }

    /**
     * 改本桌场次（下一局生效）。
     *
     * <p>对局中禁止改：本局的门槛与结算金额都已按旧场次快照，中途改会让账目对不上。</p>
     *
     * @return null = 成功
     */
    public String setTier(TabletopGamesConfig.DdzTier next) {
        if (next == null) {
            return "场次不合法。";
        }
        if (phase == TablePhase.PLAYING || phase == TablePhase.DEALING) {
            return "对局进行中，打完这一局才能改场次。";
        }
        if (next == tier) {
            return null;
        }
        TabletopGamesConfig.DdzTier old = tier;
        tier = next;
        broadcast("§f" + old.label() + " §7→ §f" + next.label()
                + "§7（底分 " + fmtNum(next.baseScore()) + "，下一局生效）");
        flush();
        return null;
    }

    /**
     * 入座余额校验：只查余额够不够门槛，<b>不扣钱</b>。
     *
     * <p>⚠️ 初版按方案 §5.2 做成了「入座即扣本金」的两段式，但用户 10-20 明确纠正：
     * <b>UNO 与斗地主都是对局结束才扣</b>，只有 5 类棋是开局扣押金。</p>
     *
     * <p>所以这里与 {@code UnoTable#checkJoinStake} 完全同口径 —— 只校验
     * {@code 余额 ≥ 门槛}（门槛 = 底分 × 入座门槛系数），真正的扣款全部发生在
     * {@link #settleEconomy}：输家按实际余额扣、汇成奖池、扣抽水后给赢家。</p>
     *
     * @return null = 通过
     */
    private String checkJoinStake(Player p) {
        // 门槛取**本桌场次**的（不是全局默认档位）
        double need = stakeOfTier();
        if (need <= 0.0) {
            return null;
        }
        double bal = Math.max(0.0, EconomyHook.balance(p.getUniqueId()));
        if (bal < need) {
            return "§c本桌是「" + getTier().label() + "」：入座要求余额 ≥ §e" + fmtNum(need)
                    + " §c，你当前 §e" + fmtNum(bal) + "§c。";
        }
        return null;
    }

    /**
     * 放一个机器人到第一个空位（菜单入口）。
     * <p>斗地主只有三家且机器人不分档位（难度统一读全局配置），所以不需要 UNO 那种
     * 「挑一个空位 + 选档位」的两段选择。
     *
     * @return null = 成功，否则为拒绝原因
     */
    /**
     * 加一个机器人。
     *
     * @param botTier 档位；null = 用本桌配置的默认档（{@link #difficulty}）
     */
    public String addBot(DdzDifficulty botTier) {
        // 大师场不允许人机（2026-10-22 用户要求）。UI 已经不显示按钮，这里是第二道防线：
        // 命令 / 菜单缓存 / 其它入口绕过 UI 时也不会漏。
        if (getTier() == TabletopGamesConfig.DdzTier.MASTER) {
            return "大师场只能 3 位真人，不能添加机器人。";
        }
        int seat = -1;
        for (int s = 0; s < 3; s++) {
            if (humans[s] == null && bots[s] == null) {
                seat = s;
                break;
            }
        }
        if (seat < 0) {
            return "没有空位了。";
        }
        bots[seat] = botTier == null ? difficulty : botTier;
        names[seat] = "机器人" + (botSerial++);
        trusted[seat] = false;
        syncSeats();
        flush();
        return null;
    }

    private int botSerial = 1;

    /** 清掉所有机器人。 */
    public String clearBots() {
        boolean any = false;
        for (int s = 0; s < 3; s++) {
            if (bots[s] != null) {
                bots[s] = null;
                names[s] = null;
                any = true;
            }
        }
        if (!any) {
            return "桌上没有机器人。";
        }
        // 手动清除人机也算「有人退出」（UNO 同款）：续局链断了，按钮回到「开始游戏」
        markSeatVacated();
        syncSeats();
        flush();
        return null;
    }

    /** 切换<b>自己</b>座位的托管（菜单入口）。 */
    public String toggleTrust(Player p) {
        return toggleTrust(p, seatOf(p == null ? null : p.getUniqueId()));
    }

    /** 切换某座位的托管。 */
    public String toggleTrust(Player p, int seat) {
        if (seat < 0 || seat > 2 || humans[seat] == null) {
            return "那个座位没有真人。";
        }
        // 与 UNO 同款：结算页也可以切换托管，供下一局直接沿用（不再拒绝 SETTLED）。
        trusted[seat] = !trusted[seat];
        if (trusted[seat]) {
            // 正轮到他 ⇒ 立刻把思考计时切成机器人的 2 秒，否则要等原有的人类时限
            if (round != null && phase == TablePhase.PLAYING && seat == round.getCurrentSeat()) {
                botTicks = BOT_THINK_TICKS;
            }
            p.sendTip("§7已开启托管，系统会在 2 秒后替你行动。");
        } else {
            // 关托管时把他剩余的人类时限还回去，避免「刚关就超时」
            if (round != null && phase == TablePhase.PLAYING && seat == round.getCurrentSeat()) {
                botTicks = 0;
                actionUsed = 0;
                actionLimit = computeActionLimit();
            }
            p.sendTip("§7已关闭托管。");
        }
        syncSeats();
        flush();
        return null;
    }

    /** 退位。 */
    public void leave(Player p) {
        int s = seatOf(p.getUniqueId());
        if (s < 0) {
            // 座位已经不在了 ⇒ 走统一回收（还坐在别的棋桌上就不收）
            releaseLocator(p);
            return;
        }
        if (phase == TablePhase.PLAYING || phase == TablePhase.DEALING) {
            handOverToSystem(s);
            return;
        }
        humans[s] = null;
        online[s] = null;
        bots[s] = null;
        names[s] = null;
        away[s] = false;
        trusted[s] = false;
        // 座位已清 ⇒ 统一回收（只清「带标签」的那把）
        releaseLocator(p);
        // 主动离席 = 退出本局：结算页不再给「下一局」（下一局等于换人重开）
        markSeatVacated();
        // 座位上一个真人都没有了 ⇒ 补位机器人没有意义，直接清掉（用户 2026-10-20 要求）
        clearBotsIfNoHumans();
        syncSeats();
        flush();
    }

    /**
     * 若在座真人为 0，则清空所有补位机器人。
     * <p>机器人是给「人不够 3 家」时补位的；真人全走光后留着它们只会让这张桌子
     * 永远显示「3 人已满」、别人坐不进来。</p>
     */
    private void clearBotsIfNoHumans() {
        if (occupiedSeats() > 0) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            bots[i] = null;
        }
    }

    /**
     * 「走出识别半径被系统代打」的玩家**回到范围内**时自动接管座位。
     *
     * <p>⚠️ 没有这一步的话 `away` 会一直 true：玩家哪怕回桌边打开菜单关掉托管，
     * `autoPlayed` 依旧成立（away 分支），系统照样代打 —— 真机表现为
     * 「我明明退出托管了，还是有自动托管的操作」。</p>
     */
    private void tickBackInRange() {
        var rangeCfg = ddzConfig();
        double r = rangeCfg == null ? DEFAULT_PLAYER_RANGE : rangeCfg.getPlayerRange();
        for (int s = 0; s < 3; s++) {
            if (!away[s] || humans[s] == null || trusted[s]) {
                continue;   // 只有「离线代打且没开托管」的才自动收回；开了托管的保持托管
            }
            Player p = online[s];
            if (p == null || !p.isOnline()) {
                continue;
            }
            double dx = p.getX() - center.x;
            double dz = p.getZ() - center.z;
            if (dx * dx + dz * dz <= r * r) {
                away[s] = false;
                // 回到桌边 = 重新接管座位：定位器要**补发**（离席时被收走了，
                // 否则这一局他点不动手牌）。以前这里误写成 takeLocator，人会越打越没准星。
                ensureLocator(p);
                broadcast("§f" + p.getName() + " §7回到了桌边，重新接管自己的手牌。");
                syncSeats();
                flush();
            }
        }
    }

    /**
     * 中途离席：座位上还有别的真人时，座位保留给本人、系统代打；
     * <b>离席后桌上再无真人（只剩机器人 / 空位）→ 直接终止本局并清桌</b>
     * （需求 2026-10-22，与 UNO 同款）。
     */
    private void handOverToSystem(int seat) {
        if (humans[seat] == null) {
            return;
        }
        away[seat] = true;
        // 离席 / 断线 / 走出识别半径 = 玩家已经不在牌桌上（UNO 同款）：本局不再提供「下一局」。
        // 座位仍为他保留（对局中由系统代打），只是续局按钮要回到「开始游戏」。
        markSeatVacated();
        if (online[seat] != null) {
            takeLocator(online[seat]);
        }
        if (!otherHumanExists(seat)) {
            abortBotsOnlyTable();
            return;
        }
        syncSeats();
        flush();
    }

    /** 除 {@code except} 外，座位上是否还有真人（含离席但保留座位的）。 */
    private boolean otherHumanExists(int except) {
        for (int s = 0; s < 3; s++) {
            if (s != except && humans[s] != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 桌上再无真人时终止本局（需求 2026-10-22）：收飞牌、清手牌/出牌区、清机器人、
     * 腾空全部座位，回到「空牌桌等待」状态。模仿 UNO 的 {@code abortBotsOnlyTable}。
     *
     * <p>不结算经济：本局没有打完，按「中途终止」处理（斗地主的钱只在结算时按输赢扣，
     * 入座与开局都不扣，所以不需要退款）。</p>
     */
    private void abortBotsOnlyTable() {
        recycleAllFlyCards();
        round = null;
        phase = TablePhase.WAITING;
        bottomTaken = false;
        dealTicks = 1;
        botTicks = 0;
        actionUsed = 0;
        actionLimit = 0;
        doublingUsed = 0;
        doublingTiming = false;
        redealCount = 0;
        settleTicks = 0;
        settleText = null;
        settleBase = null;
        java.util.Arrays.fill(doubled, false);
        java.util.Arrays.fill(nextVote, false);
        java.util.Arrays.fill(selected, 0);
        java.util.Arrays.fill(hover, 0);
        clearTable();
        for (int s = 0; s < 3; s++) {
            humans[s] = null;
            online[s] = null;
            bots[s] = null;
            names[s] = null;
            trusted[s] = false;
            away[s] = false;
        }
        syncSeats();
        flush();
        broadcastEvent("§7牌桌上已无真人，本局终止：人机与座位全部清除。");
        refreshViewers();
    }

    /** 玩家回来 → 接管自己的座位。 */
    public void reattach(Player p) {
        int s = seatOf(p.getUniqueId());
        if (s < 0) {
            return;
        }
        online[s] = p;
        away[s] = false;
        names[s] = p.getName();
        // 回到座位上 = 再次入座 ⇒ 补发定位器（2026-10-27 统一口径：入座就发，不分阶段）
        ensureLocator(p);
        syncSeats();
        flush();
    }

    /**
     * 玩家断线。
     *
     * <p>对局中（DEALING/PLAYING）保留座位并交给系统代打，等他重连；
     * 等待期 / 结算期断线则清座位。<b>不涉及任何退款</b> —— 斗地主的钱只在
     * 结算时按输赢扣（{@link #settleEconomy}），入座与开局都不扣，
     * 所以没有「扣了要还」这类分支需要维护。</p>
     */
    public void onQuit(Player p) {
        int s = seatOf(p.getUniqueId());
        if (s < 0) {
            return;
        }
        online[s] = null;
        if (phase == TablePhase.PLAYING || phase == TablePhase.DEALING) {
            away[s] = true;
            // 断线后桌上再无真人（只剩机器人 / 空位）→ 本局直接终止、清桌（与 UNO 同款）
            if (!otherHumanExists(s)) {
                abortBotsOnlyTable();
                return;
            }
        } else {
            humans[s] = null;
            names[s] = null;
            bots[s] = null;
            away[s] = false;
            trusted[s] = false;
            // 等待 / 结算期断线 = 座位真正腾空 = 退出本局
            markSeatVacated();
            clearBotsIfNoHumans();
        }
        syncSeats();
        flush();
    }

    /** 同步托管机器人显隐。 */
    /**
     * 同步「托管 / 离线」表现（{@code playing_card_seats} 每个座位 2 位）。
     *
     * <p>0 = 正常（不显示小人）/ 1 = 托管 / 2 = 离线托管。</p>
     *
     * <p>⚠️ <b>补位的机器人玩家不是托管</b>：那一栏是「系统替你打」的标记，
     * 用 {@code addBot} 加进来的 AI 是独立的第三方玩家，显示成托管会让所有真人
     * 都带上小人。初版把 {@code bots[s] != null} 直接当成 1，真机整桌都是小机器人。</p>
     */
    private void syncSeats() {
        int[] per = new int[3];
        for (int s = 0; s < 3; s++) {
            if (humans[s] != null) {
                per[s] = online[s] == null ? 2 : (trusted[s] ? 1 : 0);
            }
            // 机器人补位 / 空座：0（无托管标记）
        }
        if (visual != null && !visual.isClosed()) {
            visual.setSeatVisuals(DdzTableEntity.packSeats(per));
        }
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    /** 全量属性同步。 */
    private void sync() {
        if (visual == null || visual.isClosed()) {
            return;
        }
        visual.beginBatch();
        // 选中是**按卡槽号**存的一份全局状态（客户端 v.sel0..53），
        // 所以要把「每座位的展示序号位掩码」换算成卡槽号再写。
        // ⚠️ 别按座位直接塞进 playing_card_selected_k：座位 1 的卡槽从 17 左右开始，
        //    而 playing_card_selected_1 的 bit 0 对应卡槽 18 ⇒ 会整体错位。
        boolean[] selSlots = new boolean[DdzTableEntity.CARD_SLOTS];
        for (int s = 0; s < 3; s++) {
            int[] hand = ownHandArray(s);
            for (int idx = 0; idx < hand.length && idx < 32; idx++) {
                if (((selected[s] >>> idx) & 1) == 0) {
                    continue;
                }
                int slot = hand[idx];
                if (slot >= 0 && slot < selSlots.length) {
                    selSlots[slot] = true;
                }
            }
        }
        visual.setSelectedSlots(selSlots);
        for (int s = 0; s < 3; s++) {
            visual.setHover(s, hover[s]);
        }
        visual.setControls(DdzTableEntity.packControls(controlsAsArray(globalButtons())));
        visual.setLandlord(round == null ? -1 : round.getLandlord());
        // 癞子点数：地赖子在叫牌阶段是 0（还不该翻金版），定地主那一刻由 DdzRound 抽好；
        // 天癞子发牌前就有值。两个一起下发 —— 与「地主收底牌」动画同帧，观感对得上。
        visual.setWildRanks(round == null ? 0 : round.getHeavenRank(),
                round == null ? 0 : round.getWildRank());
        // 癞子「当值」：出牌区的癞子牌要换成「金 <当的点数>」+ 左下灰原点数。
        // 打出去那一刻才由 DdzRound.play() 分配好，没打的牌恒 0（不标记）。
        visual.setWildAs(round == null ? 0 : round.getWildAsHeaven(),
                round == null ? 0 : round.getWildAsEarth());
        // 手牌左右排序（纯画面）：属性写 1 = 大→小（最大在左）
        visual.setHandSort(getHandSort().code());
        // 回合标记（黄色三角，UNO 同款）：只要处于对局中（叫地主 / 抢地主 / 加倍 / 出牌）
        // 就标出当前行动方；等待 / 发牌 / 结算阶段灭掉。
        visual.setActor(phase == TablePhase.PLAYING ? actorSeat() : -1);
        visual.setReveal(phase == TablePhase.SETTLED);
        int owner = buttonOwner();
        visual.setButtonOwner(owner);
        visual.setButtonsVisibleToAll(owner < 0
                && (phase == TablePhase.PLAYING || phase == TablePhase.SETTLED)
                && buttonsVisibleToAll());
        syncSeats();
        visual.endBatch();
    }

    /** 把动作码数组转成枚举 varargs（0 → NONE）。 */
    private static DdzTableEntity.Controls[] controlsAsArray(int[] codes) {
        DdzTableEntity.Controls[] out = new DdzTableEntity.Controls[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (i < codes.length && codes[i] != 0)
                    ? DdzTableEntity.Controls.of(codes[i]) : DdzTableEntity.Controls.NONE;
        }
        return out;
    }

    /** 按钮可见归属座位；−1 = 全场可见。 */
    private int buttonOwner() {
        if (phase != TablePhase.PLAYING) {
            return -1;
        }
        int actor = actorSeat();
        return actor >= 0 ? actor : -1;
    }

    /** 是否全场可见按钮（结算期或无人行动）。 */
    private boolean buttonsVisibleToAll() {
        return round == null || actorSeat() < 0;
    }

    private void flush() {
        dirty = false;
        sync();
    }

    /** 收集在座在线玩家。 */
    private Collection<Player> viewers() {
        List<Player> out = new ArrayList<>(3);
        for (int s = 0; s < 3; s++) {
            if (online[s] != null && online[s].isOnline()) {
                out.add(online[s]);
            }
        }
        return out;
    }

    private void broadcast(String msg) {
        for (Player p : viewers()) {
            p.sendMessage("§b[斗地主] " + msg);
        }
    }

    /**
     * 事件提示：占住动作栏 {@link #EVENT_TIP_HOLD_TICKS}，期间倒计时不重发，
     * 否则会被每秒刷新的倒计时顶掉（UNO 踩过）。
     */
    private void broadcastEvent(String msg) {
        eventTipHold = EVENT_TIP_HOLD_TICKS;
        for (Player p : viewers()) {
            p.sendTip(msg);
        }
    }

    // ------------------------------------------------------------------
    // 准星 / 视角
    // ------------------------------------------------------------------

    private void updateHover() {
        for (int s = 0; s < 3; s++) {
            Player p = online[s];
            if (p == null || !p.isOnline()) {
                if (hover[s] != DdzTableEntity.HOVER_NONE) {
                    hover[s] = DdzTableEntity.HOVER_NONE;
                    dirty = true;
                }
                continue;
            }
            // 结算期按钮全场可点（与 useBoard / hasTarget 同口径）
            boolean act = phase == TablePhase.SETTLED || (s == actorSeat() && !autoPlayed(s));
            int[] hand = ownHandArray(s);
            int[] buttons = act ? globalButtons() : EMPTY_BUTTONS;
            int actor = act ? s : actorSeat();
            DdzTableRay.Hit hit = DdzTableRay.raycast(p, origin.x, origin.y, origin.z,
                    s, actor, hand, buttons, selected[s], handFlipped());
            if (hit.hover() != hover[s]) {
                hover[s] = hit.hover();
                dirty = true;
            }
        }
    }

    /**
     * 视角动画：入座者播 {@code viewer_pN}，附近非入座者播 {@code hidden}。
     * <p>
     * ⚠️ <b>{@code v.viewer_mode} 不是属性</b>，客户端全靠这三条一次性动画里的
     * {@code v.viewer_mode=N;return 1;} 赋值（见 {@code initialize} 里初值 −1）。
     * 不播 ⇒ viewer_mode 一直是 −1 ⇒ 按钮/手牌那一整套按 −1 分支渲染 = 全错。
     * 这三条共用同一个 controller 标签，才能保证切换视角时旧的被新的一把顶掉。
     */
    private void refreshViewers() {
        if (visual == null || visual.isClosed()) {
            spectatorSent.clear();
            return;
        }
        spectatorNow.clear();
        if (occupiedSeats() == 0) {
            // 空桌：清掉记录，下一局有人坐下时按「新旁观者」重新发一遍
            spectatorSent.clear();
            return;
        }
        // ⚠️ 不要在这里用 debug_real 做「结算公开手牌」：viewer_mode=3 会在下一局开局后
        //    残留（客户端动画覆盖不及时），出现「自己的牌是牌背、别人的是牌面」的错位。
        //    结算公开改用 **zone 平摊**（见 doFinish）：手牌挪进出牌区 = 平放 + 牌面朝上。
        boolean resendSeated = forceViewerResend || viewerRound % SEATED_RESEND_ROUNDS == 0;
        forceViewerResend = false;
        viewerRound++;
        for (int s = 0; s < 3; s++) {
            Player p = online[s];
            if (p == null || !p.isOnline()) {
                viewerSentTo[s] = null;
                continue;
            }
            if (resendSeated || !p.getUniqueId().equals(viewerSentTo[s])) {
                sendAnim("animation.tabletopgame.ddz.viewer_p" + s, List.of(p));
                viewerSentTo[s] = p.getUniqueId();
            }
        }
        List<Player> fresh = null;
        for (Player p : level.getPlayers().values()) {
            if (p.distanceSquared(center) > SPECTATOR_RANGE * SPECTATOR_RANGE) {
                continue;
            }
            UUID id = p.getUniqueId();
            if (isSeated(id)) {
                continue;
            }
            spectatorNow.add(id);
            if (!spectatorSent.contains(id)) {
                if (fresh == null) {
                    fresh = new ArrayList<>(4);
                }
                fresh.add(p);
            }
        }
        if (fresh != null) {
            sendAnim("animation.tabletopgame.ddz.hidden", fresh);
        }
        spectatorSent.clear();
        spectatorSent.addAll(spectatorNow);
    }

    private void sendAnim(String animation, Collection<Player> players) {
        if (visual == null || visual.isClosed() || players.isEmpty()) {
            return;
        }
        AnimateEntityPacket.Animation anim = AnimateEntityPacket.Animation.builder()
                .animation(animation)
                .controller("tabletopgame:ddz_viewer")
                .stopExpression("0")
                .blendOutTime(0f)
                .build();
        Entity.playAnimationOnEntities(anim, List.<Entity>of(visual), players);
    }

    /**
     * 播<b>一次性动作动画</b>（发牌展开 / 出牌飞行 / 收底牌）。
     *
     * <p>与 {@link #sendAnim} 的唯一差别：走独立的 controller 槽，不会把 {@code viewer_pN}
     * 顶掉（同一 controller 里旧动画会被新的一把替换）。</p>
     *
     * @param animation  动画全名（如 {@code animation.tabletopgame.ddz.deal_reveal}）
     * @param controller 播放槽名。<b>每条动作动画用各自的槽</b>（模仿模组）——
     *                   同一槽里新的一把会替换旧的，发牌 / 收底牌 / 出牌就可能互相打断；
     *                   不同槽可以同时叠着播（例如地主徽记弹出 + 底牌飞入）。
     *                   槽名不需要在资源包里声明，客户端按名字分槽。
     * @param stopAfter  播多久后停（秒）；到点后 blend 回 layout
     */
    private void sendActionAnim(String animation, String controller, float stopAfter) {
        if (visual == null || visual.isClosed()) {
            return;
        }
        List<Player> targets = new ArrayList<>(viewers());
        for (Player p : level.getPlayers().values()) {
            if (p.distanceSquared(center) <= SPECTATOR_RANGE * SPECTATOR_RANGE
                    && !isSeated(p.getUniqueId())) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        AnimateEntityPacket.Animation anim = AnimateEntityPacket.Animation.builder()
                .animation(animation)
                .controller(controller)
                .stopExpression("q.anim_time > " + stopAfter)
                .blendOutTime(0.12f)
                .build();
        Entity.playAnimationOnEntities(anim, List.<Entity>of(visual), targets);
    }

    // ------------------------------------------------------------------
    // 定位器
    // ------------------------------------------------------------------

    /** 发定位器（手持它「使用」= 一次射线判定信号，见 MEMORY「交互链路」）。入座即发。 */
    private void ensureLocator(Player p) {
        // 实现统一在 LocatorService（棋类 / 德州扑克也走它），这里只保留调用点语义。
        com.tabletopgames.item.LocatorService.give(p);
    }

    /** 离座清定位器：只清「带标签」的那把；还坐在别的棋桌上就不收。 */
    private void releaseLocator(Player p) {
        com.tabletopgames.item.LocatorService.release(p);
    }

    /**
     * 强制收走定位器（不看别桌）。
     *
     * <p>⚠️ 这些路径<b>不能</b>用 {@link #releaseLocator}：
     * <ul>
     *   <li>走远 / 断线：只置 {@code away} 标记、<b>座位还在</b>，用「还坐着就不收」会永远收不掉；</li>
     *   <li>拆桌 / 整桌重置：座位数据还在（或马上被清），此刻判据不可信。</li>
     * </ul>
     */
    private void takeLocator(Player p) {
        com.tabletopgames.item.LocatorService.take(p);
    }

    // ------------------------------------------------------------------

    private com.tabletopgames.config.TabletopGamesConfig.DdzSettings ddzConfig() {
        if (manager == null) {
            return null;
        }
        return manager.ddzConfig();
    }

    /**
     * 服务器停服时清理：<b>只收实体与引用，不动方块</b>。
     *
     * <p>2026-10-23 事故：原来的实现直接 {@link #destroy()}，而 destroy 的语义是「拆桌」，
     * 会把 3x3 方块写成空气；启动时又没有任何恢复逻辑 ⇒ 用户每次重启都发现牌桌方块消失。
     * 现在牌桌位置存在数据库里（{@code ddz_table_config}），
     * {@code DdzManager#load()} 会在世界就绪后按坐标重放方块；
     * 这里保留方块，即使存档/库出问题也不会把世界里的桌子抹掉。</p>
     */
    public static void shutdownAll() {
        for (DdzGame t : new ArrayList<>(TABLES.values())) {
            t.detach();
        }
        TABLES.clear();
    }

    /** 关服专用：摘掉实体与注册表条目，<b>不碰方块、不回收物品</b>。 */
    private void detach() {
        TABLES.remove(key);
        recycleAllFlyCards();
        if (visual != null) {
            visual.close();
            visual = null;
        }
        for (int s = 0; s < 3; s++) {
            humans[s] = null;
            online[s] = null;
            bots[s] = null;
        }
    }

    /** 供命令 / 菜单查询：玩家所在牌桌。 */
    public static DdzGame of(Player p) {
        if (p == null) {
            return null;
        }
        for (DdzGame t : TABLES.values()) {
            if (t.isSeated(p.getUniqueId())) {
                return t;
            }
        }
        return null;
    }
}
