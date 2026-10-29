package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.player.PlayerTeleportEvent;
import cn.nukkit.level.Level;
import cn.nukkit.level.Sound;
import cn.nukkit.level.Location;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.AnimateEntityPacket;
import com.tabletopgames.economy.EconomyHook;
import com.tabletopgames.ai.AiThinkDispatcher;
import com.tabletopgames.ai.UnoAi;
import com.tabletopgames.board.UnoTableBlocks;
import com.tabletopgames.entity.UnoFlyCardEntity;
import com.tabletopgames.entity.UnoTableEntity;
import com.tabletopgames.entity.UnoTableEntity.Controls;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 一张已放置的 UNO 牌桌（3x3 物理牌桌 + 单个渲染实体）。
 *
 * <h2>渲染</h2>
 * 整桌状态由 {@link UnoTableEntity} 的 <b>24 个整型属性</b>同步给客户端，客户端
 * {@code pre_animation} 解码后由 {@code animation.tabletopgame.uno.layout} 驱动 398 根骨骼；
 * 选牌 / 按钮通过 {@link UnoTableRay} 对玩家视线做射线拾取（与斗地主同一套已实测的数学）。
 * 与斗地主的差别：<b>没有激光笔</b>，右键牌桌即按准星命中直接交互。
 *
 * <h2>节拍</h2>
 * 管理器每 5 游戏 tick 调一次 {@link #tick()}（每秒 4 次）：
 * <ul>
 *   <li>{@code WAITING}：等待入座 / 点按钮开局（≥2 座位占用即可）。按钮名有两档：上一局
 *       <b>完整打完且期间没人退出</b>（清机器人也算退出）才叫「下一局」，否则叫「开始游戏」
 *       （见 {@link #waitingButtonLabel()}）；</li>
 *   <li>{@code DEALER_DRAW}：<b>自动</b>各家同时各抽一张比大小定庄，一张消息一起播报，
 *       这些牌各摆在自己那侧出牌区展示 2 秒后消失并开始发牌；并列最大时只让并列的几位重抽
 *       （点数小者淘汰）；</li>
 *   <li>{@code PLAYING}：人来点世界内按钮，机器人 / 离线托管按延迟自动行动；</li>
 *   <li>{@code SETTLED}：展示结算板且<b>不再自动收档</b>（2026-10-16 需求）：桌上玩家 / 人机
 *       无变动时停在这里，等所有在线真人点「下一局」（世界按钮 / 表单按钮）后自动续局
 *       —— 上一局赢家连庄、免定庄直接发牌；一旦有变动（入座 / 离座 / 增删人机）立即收档
 *       退回 {@code WAITING}。</li>
 * </ul>
 *
 * <h2>座位与局内编号</h2>
 * 牌桌座位 0..3 = <b>东(南)西(北) = P1..P4</b>，已按顺时针排列（与 {@code OUT_X/OUT_Z}、
 * 动画 {@code SEAT_YAW} 一致），玩家可自选座位。而 {@link UnoGame} 只认 0..n-1 的连续编号，
 * 因此开局时按<b>座位升序</b>建立双向映射 {@link #gameOfSeat}/{@link #seatOfGame}：
 * 升序即顺时针序，保证「顺时针轮转」在自由选座下依然成立（如占 {0,2,3} → 局内 0↔座0、1↔座2、2↔座3）。
 *
 * <h2>手牌槽与卡槽</h2>
 * 实体属性里每张牌占一个<b>卡槽 = 牌 id</b>（0..107）。客户端 pre_animation 扫描卡槽升序
 * 统计每个区（座位）内的序号，因此<b>展示顺序恒为卡槽升序</b>；{@link UnoDeck#sortHand}
 * 的排序键（色 → 牌型 → 数字）恰好等价于牌 id 升序，两者天然一致 —— 即
 * 「手牌列表第 i 张」就是「区内第 i 张」，射线与选中/高亮值都以牌 id 直接编码，无需换算序号。
 *
 * <h2>经济</h2>
 * 开局不动账；结算按官方记分「Σ(其余各家剩余手牌分) × k」，<b>k 由第一个入座者设定</b>（默认 1），
 * 赢家实得扣除官方抽水后的部分。机器人不参与金钱往来（否则人机局等于无风险刷币）。
 */
public class UnoTable {

    /** 「能点到这张桌子」的距离上限（方块，玩家到桌心的水平距离）；基岩交互距离约 5.5。 */
    private static final double USE_RANGE = 6.0;

    /** 管理器每 5 游戏 tick 调一次本表 tick → 每秒 4 次。 */
    public static final int TICKS_PER_SECOND = 4;

    /**
     * 结算阶段不再自动收档（2026-10-16 需求）：停留结算页，等所有在线真人点「下一局」。
     * （旧行为「展示 20 秒超时退回 WAITING」已删除。）
     */

    /** 定庄阶段每张牌的间隔（0.5 秒）。 */
    private static final int DEALER_DRAW_DELAY = 2;
    /** 定庄比大小的牌展示时长（2 秒 = 8 表 tick）。 */
    private static final int DEALER_SHOW_TICKS = 8;
    /**
     * 定庄「从牌堆翻出」那一趟飞行的停留时长（表 tick）：与飞牌实体模式 1 的动画时长
     * （{@link UnoFlyCardEntity#FLY_SECONDS_DEALER} 秒）对齐 —— 飞完的下一拍才让真牌出现，
     * 中间不留空档（需求 2026-10-19：定庄改用「发完牌翻开起始牌」那套动画）。
     */
    private static final int DEALER_FLY_TICKS =
            (int) Math.ceil(UnoFlyCardEntity.FLY_SECONDS_DEALER * TICKS_PER_SECOND);
    /**
     * 并列重抽前「桌面已清空」的停顿（1 秒，需求 2026-10-18）：展示 2 秒结束后先把
     * 上一轮的牌全部收走，让桌面空 1 秒，再开始下一轮抽牌。
     */
    private static final int DEALER_REDRAW_PAUSE = TICKS_PER_SECOND;
    /** 机器人 / 托管思考固定 2 秒（8 表 tick），让出牌节奏更像真人。 */
    private static final int BOT_THINK_TICKS = 8;
    // 人类回合超时不再写死：读 UNO 配置的两档回合时间，见 computeActionLimit()。
    // 2026-10-16 需求：「第一次回合时间」默认 20 秒（还没摸牌）；「第二次回合时间」默认 15 秒
    // （摸牌之后必须出牌/跳过），两项都在「全局配置」表单里。

    /** 视角动画刷新间隔（表 tick）。 */
    private static final int VIEWER_REFRESH = 20;
    /** 准星高亮刷新间隔（表 tick）。 */
    private static final int HOVER_INTERVAL = 2;
    /** 旁观者隐藏牌面的广播半径（方块）。 */
    private static final double SPECTATOR_RANGE = 16.0;
    /**
     * 已经收到过 `hidden` 动画的旁观者（与斗地主 {@code DdzGame} 同款差量逻辑）。
     *
     * <p>客户端收到 AnimateEntityPacket 后动画状态会一直保留，所以每轮重发给同一批人
     * 是纯浪费（T 桌 × P 玩家个包）。改成只发给「本轮新进入半径」的人；扫描频率不变，
     * 所以「新旁观者多久收到」仍是原来的 {@link #VIEWER_REFRESH} × 250 ms。</p>
     */
    private final java.util.Set<UUID> spectatorSent = new java.util.HashSet<>();
    /** 本轮在场的旁观者（复用，避免每轮 new 一个 Set）。 */
    private final java.util.Set<UUID> spectatorNow = new java.util.HashSet<>();
    /** 在座玩家视角动画的重发间隔（刷新轮次）：8 轮 × 5 秒 = 40 秒兜底一次，理由见 DdzGame 同名字段。 */
    private static final int SEATED_RESEND_ROUNDS = 8;
    private int viewerRound;
    /** 每个座位最后一次收到视角动画的玩家；换人就必须重发（新入座的人不能等兜底周期）。 */
    private final UUID[] viewerSentTo = new UUID[4];
    private boolean forceViewerResend = true;

    /** 倍数 k 上限（整数）。0 表示免费局。 */
    public static final double MAX_COEFFICIENT = 1000.0;

    /** UNO 喊声（资源包 sound_definitions 中的 {@code tabletopgame.uno.call}）。 */
    private static final String SND_UNO_CALL = "tabletopgame.uno.call";

    /** 空按钮组。 */
    private static final int[] EMPTY_BUTTONS = new int[0];
    /** 空手牌。 */
    private static final int[] EMPTY_HAND = new int[0];

    /** 座位中文名，索引即座位号（0=东 1=南 2=西 3=北）。 */
    private static final String[] SEAT_LABEL = {"东（P1）", "南（P2）", "西（P3）", "北（P4）"};

    // ---------------- 入座归位（传送到站位 + 转向棋心）----------------

    /** 站位半径：座位棋心向桌外退 3.0 格（棋盘外隔 1 格，看得全又不挡牌）。 */
    private static final double SEAT_STAND_RADIUS = 3.0;
    /** 站位视线俯角：看向高度 ≈ 桌心上方 0.6 格（牌面区域中心）。 */
    private static final double SEAT_FACE_TARGET_Y = 0.6;
    /** 站位被方块挡住时向上找空位的最大格数。 */
    private static final int SEAT_SEEK_UP = 3;

    /** 牌桌级阶段。 */
    public enum TablePhase {
        /** 等待入座 / 点开始。 */
        WAITING,
        /** 抽牌比大小定庄（自动）。 */
        DEALER_DRAW,
        /** 对局进行中。 */
        PLAYING,
        /** 结算展示中。 */
        SETTLED
    }

    private final UnoManager manager;
    private final Level level;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final int rotation;
    private final String key;
    private final Random random = new Random();

    /** 3x3 中心（世界坐标）。 */
    private final Vector3 center;
    /** 渲染实体原点：桌面顶面（15/16）中心，射线与局部坐标基准。 */
    private final Vector3 origin;

    private UnoTableEntity visual;

    /**
     * 动作栏提示文本（倒计时 / 结算展示，2026-10-16 改用 {@code sendTip} 动作栏）。
     * <p>
     * 悬浮字路线已彻底退役：实体 name tag 不常亮；{@code FloatingTextParticle} = 盔甲架
     * （默认 0.75 倍字大、且真机会看见盔甲架本体）。动作栏无实体、可换行、每秒重发即常驻。
     * <p>
     * {@code null} = 不显示；内容没变就不重复发包。
     */
    private String countdownText;

    /**
     * 事件提示占位（2026-10-16）：摸牌 / UNO / 超时 / 入座等事件临时占用动作栏约 2 秒，
     * 期间倒计时与结算板暂停重发 —— 否则事件文案活不过 1 秒就被每秒刷新的倒计时顶掉。
     * 由 {@link #broadcastEventTip} 置位、中央 {@link #tick()} 里递减。
     */
    private int eventTipHold;
    /**
     * 事件提示占位时长 = 2 秒。注意本表每 5 游戏 tick 才 tick 一次（{@code TICKS_PER_SECOND} = 4），
     * 所以必须按秒 × {@link #TICKS_PER_SECOND} 折算 —— 早先硬写 40「当成 2 秒」，实际占了 10 秒，
     * 导致事件播报后下一家的倒计时被压掉 10 秒（用户 2026-10-17 反馈「只剩最后 10 秒才显示」）。
     */
    private static final int EVENT_TIP_HOLD_TICKS = 2 * TICKS_PER_SECOND;

    /** 结算展示文本行（0 = 标题，1..4 = 各座位明细，5 = 经济结算说明，6 = 下一局确认进度）；null = 未显示。 */
    private final String[] settleText = new String[7];
    /** 经济结算快照：每座位实扣额（免费局 / 机器人胜 = 全 0），见 {@link #settleEconomy()}。 */
    private final double[] settlePaid = new double[4];
    /** 结算板每座行尾注（扣到 0 / 无款可扣 / 扣款失败）；null = 无。 */
    private final String[] settleNote = new String[4];
    /** 结算板第 6 行：经济结算说明；null = 不显示。 */
    private String settleEcoText;
    /** 经济结算快照：赢家税后实得（免费局 = 0）。 */
    private double settlePrize;

    /**
     * 本局开始时间（毫秒）；0 = 尚未开局。
     * <p>需求 2026-10-22：后台计时，对局过程中不显示，只在结算板上展示总时长。</p>
     */
    private long roundStartedAt;
    /** 本局最终时长（秒）；{@link #doFinish()} 时定格。 */
    private int roundSeconds;
    /**
     * 时间倍率 n = 对局分钟数（<b>至少 1</b>，不足 1 分钟按 1 分钟算）。
     * <p>需求 2026-10-22：结算时总倍率再乘上 n —— 打得越久，输赢金额越高。</p>
     */
    private int timeFactor = 1;

    // ---------------- 座位 ----------------
    /** 每座位人类成员 UUID（null = 空位或机器人）。 */
    private final UUID[] humans = new UUID[4];
    /** 每座位在线玩家（null = 离线或机器人）。 */
    private final Player[] online = new Player[4];
    /** 每座位机器人档位（{@link UnoAi#LEVEL_EASY}..{@link UnoAi#LEVEL_MASTER}；-1 = 不是机器人）。 */
    private final int[] bots = {-1, -1, -1, -1};
    /**
     * 在座机器人的<b>加入顺序</b>（座位号，先加的排前面）—— 只用来给名字编号
     * 「机器人1 / 机器人2 …」。座位号已不是机器人的陈旧记录由
     * {@link #refreshBotNames()} 剔除，所以各清除点不必手工维护。
     */
    private final List<Integer> botOrder = new ArrayList<>(4);
    /** 每座位显示名（离线后仍可播报）。 */
    private final String[] names = new String[4];
    /**
     * 每座位「中途离席、座位为本人保留」标记（需求 2026-10-17）。
     * <p>
     * 与托管（{@link #trusted}）的区别：托管是玩家在座、自愿把出牌权交给系统；
     * 这里是人已经离桌（主动退出 / 断线 / 走出识别半径），但本局还没结束 ——
     * 座位不腾给任何人，由系统代打，本人随时回来即可接管（见 {@link #reattach}）。
     */
    private final boolean[] away = {false, false, false, false};
    /**
     * 入座顺序（桌主顺位）。需求 2026-10-17：「桌主交给第二个加入的玩家」——
     * 所以桌主不是「座位序号最小的人」，而是入座顺序里最靠前、仍在座且未离席的那个。
     * 重连 / 回桌的玩家视为「重新入座」，排到队尾。
     */
    private final List<UUID> joinOrder = new ArrayList<>(4);
    /** 第一个入座的人类（负责设定倍数 k）。 */
    private UUID firstJoiner;

    // ---------------- 对局状态 ----------------
    private TablePhase phase = TablePhase.WAITING;
    private UnoGame game;
    /** 局内编号（0..gamePlayers-1）→ 牌桌座位。 */
    private final int[] seatOfGame = new int[4];
    /** 牌桌座位 → 局内编号；-1 = 未参与本局。 */
    private final int[] gameOfSeat = {-1, -1, -1, -1};
    private int gamePlayers;

    /** 每座位已选中的牌 id（-1 = 未选中）。 */
    private final int[] selected = {-1, -1, -1, -1};
    /**
     * 每座位「选中已锁定」（摸牌后强制选中摸到的那张，直到出牌或跳过才解锁）。
     * <p>
     * 需求原话：「这个牌不能收回，只能等你选择出牌或者跳过之后，这个牌才正常地收回不突出。
     * 然后这时候应该不能碰其他的来控它的突出和不突出。」因此锁定期间点别的手牌不改选中。
     */
    private final boolean[] selectLocked = {false, false, false, false};
    /** 思考令牌：提交 AI 计算后局面若变了（换人/结算），回来的结果直接丢弃。 */
    private long thinkToken;
    /**
     * 「正在算」闸门：异步结果要等下一个 tick 才回主线程，期间 tick 会再次调用 autoAction。
     * 没有它就会「每 tick 提交一次、上一份结果被令牌作废」⇒ 永远落不了子。
     */
    private boolean aiThinking;
    private int aiThinkingTicks;
    /** 闸门最长持续 tick 数（20 × 250 ms = 5 秒），超了强制放开重投。 */
    private static final int AI_THINK_STALL_TICKS = 20;
    /** 每座位「已打出野牌、等待选色」的牌 id（-1 = 无）。 */
    private final int[] pendingWild = {-1, -1, -1, -1};
    /** 每座位准星值。 */
    private final int[] hover = new int[4];
    /** 每座位「提示」循环下标。 */
    private final int[] hintIndex = new int[4];

    /**
     * 每座位是否「托管」：玩家本人还在座（{@link #humans} / {@link #online} 都有效），
     * 但把出牌权交给系统代打（菜单里的「托管（系统代打）」按钮切换）。
     * <p>
     * 与「机器人」（{@link #bots}，占的是空位）和「离线托管」（{@code online == null}）区分开：
     * 三者在客户端都用同一个机器人模型表示，见 {@link #seatsState()}。
     */
    private final boolean[] trusted = {false, false, false, false};

    /**
     * 每座位「已确认下一局」：结算期的全员续局用（需求 2026-10-16）。
     * <p>所有<b>在线</b>真人都确认后自动开下一局；人事 / 人机变动会收档并清空
     * （见 {@link #voteNext(Player)} / {@link #rosterChangedDuringSettle()}）。
     */
    private final boolean[] nextVote = {false, false, false, false};

    /**
     * 上一局赢家的牌桌座位（-1 = 无人出完）。用于「无人事变动的续局免定庄」：
     * 下一局直接由赢家连庄、立刻发牌（见 {@link #startRound(boolean)}）。
     * 每局结束时在 {@link #doFinish()} 里刷新。
     */
    private int lastWinnerSeat = -1;

    /**
     * 本局是否发生过「人事 / 人机变动」（入座 / 离座 / 增删人机）。每局开局清零，
     * 续局时若置位则不再连庄、回退到正常定庄（需求 2026-10-16）。
     */
    private boolean rosterChanged;

    /**
     * 自本局开局以来是否有人<b>腾出座位</b>（需求 2026-10-17）—— 离席 / 手动清除机器人 /
     * 走出识别半径 / 断线代打都算「退出」；「入座 / 补人机」是占位，不算。
     * <p>每局开局清零，只看它决定 WAITING 阶段按钮叫「下一局」还是「开始游戏」
     * （见 {@link #waitingButtonLabel()}）。
     */
    private boolean seatVacated;

    /** 0 人入座时的菜单持有者与到期时间。 */
    private UUID menuHolder;
    private long menuHolderUntil;

    /** 每分倍数 k（由第一入座者设定）。默认 <b>0 = 免费局</b>：不扣钱、不抽水。 */
    private double coefficient = 0.0;

    private int tableTick;
    private int hoverTick;
    private int viewerTick;
    private int settleTicks;
    private int actionTicks;
    private int actionLimit;
    private int dealerIdx;
    /**
     * 定庄子状态：0 = 还没抽牌；1 = 比大小展示中（{@link #dealerShowTicks} 倒计时）；
     * 2 = 庄家已定（本 tick 已发牌开局）；3 = 并列后桌面已清空、等 1 秒再重抽
     * （{@link #dealerPauseTicks} 倒计时）；4 = 抽完牌、牌正**从牌堆翻出**飞向各家出牌区
     * （{@link #dealerFlyTicks} 倒计时；期间真牌隐身、播报也押后到翻出那一刻）。
     */
    private int dealerStep;
    /** 展示倒计时（表 tick）。 */
    private int dealerShowTicks;
    /** 翻出飞行倒计时（表 tick），见 {@link #dealerStep} 的 4。 */
    private int dealerFlyTicks;
    /** 并列重抽前的清空停顿倒计时（表 tick）。 */
    private int dealerPauseTicks;
    /** 本轮比大小是否并列（1 = 并列，展示满 2 秒后只让并列的几位重抽）。 */
    private int dealerTie;
    /**
     * 本桌累计开局次数。<b>仅内部计数</b>：2026-10-18 起不再写进任何提示（开局 / 结算都不带局数），
     * 只用来判断 WAITING 阶段按钮叫「下一局」还是「开始游戏」（见 {@link #waitingButtonLabel()}）。
     * <p>不落盘：重启后归零（{@link UnoManager} 只持久化坐标 / 朝向 / 倍数）。
     */
    private int roundNumber;

    /**
     * 逐张发牌进度：每个座位区内已亮出的张数；{@link UnoTableEntity#DEAL_DONE} = 已完成。
     * <p>
     * 需求「复刻斗地主那种牌一张张出来，而不是一下子出来」：发牌本身是瞬时的（服务端一次
     * {@code deal()}），但客户端只在 {@code v.sN < uno_deal} 时显示第 N 张 —— 服务端逐 tick
     * 递增这个值，四家的第 k 张同时亮出，观感与真实发牌一致（一圈一张）。
     */
    private int dealShown = UnoTableEntity.DEAL_DONE;

    /**
     * 出牌飞行序号：每触发一次就把 bit1 取反（客户端只认「{@code uno_fly} 值变了」）。
     * 翻转位保证相邻两次出牌写入的值一定不同 —— 写同一个值客户端认不出新的一次。
     */
    private int flySeq;

    /** 本 tick 同步给客户端的 {@code uno_fly} 值：bit0-1 模式 / bit2-3 座位 / bit4 翻转位。 */
    private int flyValue;
    /** 本轮发牌需要亮到多少张（= 开局手牌张数）。 */
    private int dealTarget;
    /**
     * 本局发牌时每个座位的「出现顺序」—— 该座位手牌那 7 张的一个随机排列。
     * <p>
     * 需求 2026-10-19：手牌本身是服务端一次算好、并已按牌 id 升序排好的，但客户端摆位/揭晓
     * 都是按牌 id 升序来的 —— 照原序出，牌面必然「红→黄→绿→蓝」，一眼看出"先算好再发"。
     * 这里把「先出现哪一张」打乱：每 tick 只把这 7 张里的前 {@code dealShown} 张摆上台，
     * 于是每张新冒出来的牌颜色都是随机的。{@code null} = 非发牌期间（直接按整手牌摆）。
     */
    private final int[][] dealOrder = new int[4][];
    /** 距离下一张亮出的表 tick 计数。 */
    private int dealTick;
    /** 每张牌之间的间隔（表 tick；1 表 tick ≈ 50ms）。 */
    private static final int DEAL_INTERVAL = 3;
    /** {@link #tickDealing} 里每 tick 累加的步长（2 = 每 1.5 表 tick 亮一张，发牌耗时减半）。 */
    private static final int DEAL_STEP = 2;
    private int lastKey = -1;
    /**
     * 在飞的飞行牌实体 → 已存在的 tick 数（发牌/摸牌/定庄的"飞入"用，独立实体）。
     *
     * <p>用 {@link LinkedHashMap} 而不是并发 map：只在本类的 tick 单线程里读写。
     * 每个实体只活 {@link UnoFlyCardEntity#FLY_TICKS}（0.22 秒），
     * 由 {@link #recycleFlyCards()} 到期回收 —— 飞行途中**不发任何包**，
     * 进度完全走客户端 {@code q.life_time} 自走时钟。</p>
     */
    private final Map<UnoFlyCardEntity, Integer> flyAges = new LinkedHashMap<>();
    private boolean settledFlag;
    /** 连续「行动未推进状态」的计数（自愈兜底用）。 */
    private int stallCount;

    /** 有属性 / 状态变化待同步（tick 末尾统一发包）。 */
    private boolean dirty = true;

    public UnoTable(UnoManager manager, Level level,
                    int baseX, int baseY, int baseZ, int rotation) {
        this.manager = manager;
        this.level = level;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.rotation = rotation;
        this.key = level.getName() + ":" + baseX + ":" + baseY + ":" + baseZ;
        this.center = new Vector3(baseX + 1.5, baseY + 0.5, baseZ + 1.5);
        this.origin = new Vector3(baseX + 1.5, baseY + 15.0 / 16.0, baseZ + 1.5);
        spawnEntity();
    }

    // ==================================================================
    // 基本访问
    // ==================================================================

    public String getKey() {
        return key;
    }

    public Level getLevel() {
        return level;
    }

    public Vector3 getCenter() {
        return center;
    }

    public Vector3 getOrigin() {
        return origin;
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

    public TablePhase getPhase() {
        return phase;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    /** 本桌倍数 k（{@code 0} = 免费局，不扣钱、不抽水）。 */
    public double getCoefficient() {
        return coefficient;
    }

    /** 座位中文名（0=东 1=南 2=西 3=北）。 */
    public static String seatLabel(int seat) {
        return seat >= 0 && seat < 4 ? SEAT_LABEL[seat] : "—";
    }

    /** 该坐标是否属于本牌桌（3x3 区域）。 */
    public boolean contains(int x, int y, int z) {
        return x >= baseX && x < baseX + 3
                && z >= baseZ && z < baseZ + 3
                && y == baseY;
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

    /**
     * 玩家是否近到「能点到这张桌子」：用于右键空中时挑选目标牌桌。
     * <p>
     * 与 {@link #inRange} 的区别：那个是「能看到桌上信息」的展示半径（配置项，可能很大），
     * 这个是基岩的交互距离（约 5.5 格）留一点余量，避免隔着半条街右键空中就误判成点桌子。
     */
    public boolean inUseRange(Player player) {
        if (player.getLevel() != this.level) {
            return false;
        }
        double dx = player.x - center.x;
        double dz = player.z - center.z;
        return dx * dx + dz * dz <= USE_RANGE * USE_RANGE
                && Math.abs(player.y - center.y) <= 8.0;
    }

    /** 座位是否被占用（人类或机器人）。 */
    public boolean isOccupied(int seat) {
        return humans[seat] != null || bots[seat] >= 0;
    }

    public int occupiedSeats() {
        int n = 0;
        for (int s = 0; s < 4; s++) {
            if (isOccupied(s)) {
                n++;
            }
        }
        return n;
    }

    public boolean isBot(int seat) {
        return bots[seat] >= 0;
    }

    /** 玩家所在座位；-1 = 不在座。 */
    public int seatOf(UUID id) {
        if (id == null) {
            return -1;
        }
        for (int s = 0; s < 4; s++) {
            if (id.equals(humans[s])) {
                return s;
            }
        }
        return -1;
    }

    public boolean isSeated(UUID id) {
        return seatOf(id) >= 0;
    }

    /** 是否本桌成员（含机器人占位的座位号）。 */
    public List<Integer> occupiedSeatList() {
        List<Integer> out = new ArrayList<>(4);
        for (int s = 0; s < 4; s++) {
            if (isOccupied(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 座位显示名。 */
    public String seatName(int seat) {
        if (names[seat] != null) {
            return names[seat];
        }
        return "座位" + (seat + 1);
    }

    /** 该座位当前的在线玩家（null = 离线 / 空位 / 机器人）。 */
    public Player onlineAt(int seat) {
        return seat >= 0 && seat < 4 ? online[seat] : null;
    }

    /** 「0 人入座」菜单持有者（可能尚未入座）。 */
    public UUID menuHolderId() {
        return menuHolder;
    }

    /** 是否为第一个入座者（负责设定倍数 k）。 */
    public boolean isFirstJoiner(UUID id) {
        return firstJoiner != null && firstJoiner.equals(id);
    }

    /** 是否已有一个「机器人」占位（供菜单显示「清除人机」）。 */
    public boolean hasBots() {
        for (int s = 0; s < 4; s++) {
            if (bots[s] >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(double v) {
        // k 现在是整数，金额基本也是整数：整数就不显示 ".00"，看起来清爽
        if (v == Math.rint(v) && Math.abs(v) < 1.0E15) {
            return String.format(Locale.ROOT, "%,d", (long) Math.rint(v));
        }
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    /** 对局时长文案：{@code n 分 m 秒}（结算板 / 经济说明共用）。 */
    private String minutesSecondsText() {
        return (roundSeconds / 60) + " 分 " + (roundSeconds % 60) + " 秒";
    }

    // ==================================================================
    // 菜单占用（0 人入座时独占）
    // ==================================================================

    /** 0 人入座时抢占菜单；已有人入座则永远返回 true。 */
    public boolean tryAcquireMenu(Player player) {
        if (occupiedSeats() > 0) {
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

    /** 是否仍持有「0 人入座」菜单占用权（持有即在用并续期）。 */
    public boolean holdsMenu(Player player) {
        if (occupiedSeats() > 0) {
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
        if (occupiedSeats() > 0) {
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

    // ==================================================================
    // 实体生成 / 销毁
    // ==================================================================

    private void spawnEntity() {
        Entity e = Entity.createEntity(UnoTableEntity.IDENTIFIER, Position.fromObject(origin, level));
        if (e instanceof UnoTableEntity t) {
            visual = t;
            t.setTable(this);
            t.spawnToAll();
            dirty = true;
        }
    }

    /** 区块重载后按需重建实体（实体 {@code setCanBeSavedWithChunk(false)}）。 */
    private void ensureEntities() {
        if (visual != null && !visual.isClosed()) {
            return;
        }
        if (!level.isChunkLoaded((baseX + 1) >> 4, (baseZ + 1) >> 4)) {
            return;
        }
        // 实体重建 ⇒ 客户端那边旧的 AnimateEntityPacket 状态没了，必须整批重发，
        // 否则旁观者会看到未加动画的默认姿态（= 直接看见别人手牌）。
        spectatorSent.clear();
        forceViewerResend = true;
        spawnEntity();
        refreshViewers();
        dirty = true;
    }

    /** 销毁牌桌：实体、9 个方块、座位与注册全部回收。 */
    public void destroy() {
        for (int s = 0; s < 4; s++) {
            if (humans[s] != null) {
                manager.unseat(humans[s]);
            }
            // 牌桌被拆了 → 把在座玩家的定位器一并收回，别把道具留在他们背包里
            if (online[s] != null && online[s].isOnline()) {
                manager.takeLocator(online[s]);
            }
        }
        game = null;
        phase = TablePhase.WAITING;
        menuHolder = null;
        if (visual != null && !visual.isClosed()) {
            visual.close();
        }
        visual = null;
        // 拆桌时把还在飞的牌一并收掉，别在世界里留 0.22 秒的孤儿实体
        for (UnoFlyCardEntity f : flyAges.keySet()) {
            if (f != null && !f.isClosed()) {
                f.close();
            }
        }
        flyAges.clear();
        countdownText = null;
        closeSettleBoard();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3 p = new Vector3(baseX + i, baseY, baseZ + j);
                if (level.getBlock(p) instanceof UnoTableBlocks.UnoTableBlock) {
                    level.setBlock(p, Block.get(Block.AIR), true);
                }
            }
        }
        manager.unregisterTable(key);
    }

    // ==================================================================
    // 主循环
    // ==================================================================

    public void tick() {
        tableTick++;
        // 真玩家走出识别半径 → 视为中途退出对局（与四棋的 collectLeftPlayers 同一口径）
        tickOutOfRange();
        ensureEntities();
        if (visual == null || visual.isClosed()) {
            return;
        }
        tickDealing();
        // 回收飞完的飞行牌（0.22 秒后 close）。放在 tickDealing 之后 ——
        // 同一 tick 里刚生成的实体至少存在一帧，不会"生下来就被回收"。
        recycleFlyCards();
        // 事件提示占位递减：到期后把 countdownText 置空，让倒计时 / 结算板重发自然接管
        if (eventTipHold > 0 && --eventTipHold == 0) {
            countdownText = null;
        }
        switch (phase) {
            case DEALER_DRAW -> tickDealer();
            case PLAYING -> tickPlaying();
            case SETTLED -> tickSettled();
            default -> {
            }
        }
        // 动作栏倒计时只在「有人正在思考出什么牌」的那段时间显示；其余阶段一律清掉
        // （结算期的文案由 showSettleTip 自管：它把 countdownText 静默置空，这里的清除是 no-op）
        if (phase != TablePhase.PLAYING || dealing()) {
            setCountdownText(null);
        }
        if (++hoverTick >= HOVER_INTERVAL) {
            hoverTick = 0;
            updateHover();
        }
        if (++viewerTick >= VIEWER_REFRESH) {
            viewerTick = 0;
            refreshViewers();
        }
        if (aiThinking && ++aiThinkingTicks > AI_THINK_STALL_TICKS) {
            aiThinking = false;
            aiThinkingTicks = 0;
            pluginLog("AI 思考超时，重新提交（seat=" + actorSeat() + "）");
        }
        if (dirty) {
            dirty = false;
            sync();
        }
    }

    /**
     * 在座真玩家的存在性检查：走出识别半径 / 已经离线 → 视为离开
     * （需求 2026-10-17「半径超出了也自动视为退出」，配置项 {@code uno.player-range}，默认 7 格）。
     *
     * <p>2026-10-22 补上<b>离线兜底</b>：不能只依赖 {@code PlayerQuitEvent} ——
     * 事件漏送（插件重载 / 异常）时 {@code online[s]} 会一直挂着那个已经离线的 Player，
     * 座位永远占着。这里每 tick 只查 4 个座位，开销恒定（不扫描整关卡玩家表）。</p>
     *
     * <p>对局中离席走 {@link #handOverToSystem}（座位为本人保留、系统代打，局末自动腾空）；
     * 等待 / 结算阶段直接腾位。</p>
     */
    private void tickOutOfRange() {
        for (int s = 0; s < 4; s++) {
            if (humans[s] == null || away[s]) {
                continue;
            }
            Player p = online[s];
            // 离线兜底：online 指针为 null，或指向一个已经离线的 Player，都按断线处理。
            if (p == null || !p.isOnline()) {
                if (inGame()) {
                    handOverToSystem(s, "断线");
                    if (!inGame()) {
                        return; // 牌桌已被终止（桌上只剩机器人）
                    }
                } else {
                    leaveSeat(s);
                }
                continue;
            }
            if (inRange(p)) {
                continue;
            }
            if (inGame()) {
                handOverToSystem(s, "走出了牌桌范围");
                if (!inGame()) {
                    return; // 牌桌已被终止（桌上只剩机器人）
                }
            } else {
                leaveSeat(s);
            }
        }
    }

    // ==================================================================
    // 定庄（自动）
    // ==================================================================

    /**
     * 定庄阶段：本轮在赛的几家**同时**各抽一张，同一条消息一起播报，这些牌展示 2 秒后结算。
     * <ul>
     *   <li>抽完立刻 {@link #announceDealerDraw()} 播报（"同时提示"）；</li>
     *   <li>展示期间客户端把这些牌平铺在各自座位的出牌区（{@link UnoTableEntity#SHOW_SHIFT}）；</li>
     *   <li>满 2 秒后：并列 → 先把桌上所有牌收走（桌面空掉）、播报并列、停 1 秒
     *       （{@link #DEALER_REDRAW_PAUSE}），再开始下一轮，且只让<b>并列最大的那几位</b>重抽
     *       （点数小的淘汰，见 {@link UnoGame#isDealerContender(int)}）；否则播报庄家并
     *       {@link #startPlay()} 发牌（发牌前这些牌就已经从状态里清掉了，不会和发到手里的牌撞脸）。</li>
     * </ul>
     */
    private void tickDealer() {
        if (game == null || game.phase() != UnoGame.PHASE_DEALER_DRAW) {
            return;
        }
        if (dealerStep == 0) {
            int r = game.drawAllForDealer();
            if (r < 0) {
                return;
            }
            dealerIdx = gamePlayers;
            dealerTie = r == UnoGame.DRAW_TIE_REDRAW ? 1 : 0;
            // 需求 2026-10-19：定庄改用「发完牌翻开起始牌」那套动画 —— 本轮在赛各家各飞一张
            // 「从牌堆翻出」的牌背（飞牌实体模式 1），飞完才亮真牌、才播报（先播报等于提前泄底）。
            spawnDealerFlights();
            dealerStep = 4;
            dealerFlyTicks = 0;
            dirty = true;
            return;
        }
        if (dealerStep == 4) {
            if (++dealerFlyTicks < DEALER_FLY_TICKS) {
                return;
            }
            dealerFlyTicks = 0;
            announceDealerDraw();
            dealerStep = 1;
            dealerShowTicks = 0;
            dirty = true;
            return;
        }
        if (dealerStep == 3) {
            // 并列后：桌面已经清空，停 1 秒再开下一轮（让「收牌」看得见）
            if (++dealerPauseTicks < DEALER_REDRAW_PAUSE) {
                return;
            }
            dealerStep = 0;
            return;
        }
        if (dealerStep != 1) {
            return;
        }
        if (++dealerShowTicks < DEALER_SHOW_TICKS) {
            return;
        }
        dealerShowTicks = 0;
        if (dealerTie == 1) {
            // 展示满 2 秒先把**桌上所有牌**收走（含并列者的、含被淘汰者的），桌面立刻空掉，
            // 再播报并列并停 1 秒，之后才开始下一轮抽牌（需求 2026-10-18：
            // 「第二轮抽之前，就把第一轮的四张牌都清了」）。
            game.clearDealerDraw();
            broadcast("§e最大点数并列：" + contenderNames() + " 重新抽牌比大小（第 "
                    + (game.dealerDrawRound() + 1) + " 轮）。");
            dealerStep = 3;
            dealerPauseTicks = 0;
        } else {
            int dealerSeat = seatOfGame[game.dealerSeat()];
            broadcast("§6" + seatName(dealerSeat) + " §7成为庄家，先手为其左手边（顺时针下一位）。");
            dealerStep = 2;
            startPlay();
        }
        dirty = true;
    }

    /** 仍在定庄比大小的座位名（"东（P1）"…），用顿号连接。 */
    private String contenderNames() {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < gamePlayers; g++) {
            if (!game.isDealerContender(g)) {
                continue;
            }
            int seat = seatOfGame[g];
            if (seat < 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(seatName(seat));
        }
        return sb.toString();
    }

    /**
     * 定庄抽完牌的一条播报：本轮在赛各家的牌 + 比大小点数挤在一行里（需求「同时提示」）。
     * <p>并列重抽时被淘汰的座位不再出现在这条播报里（它们本轮没有牌）。
     */
    private void announceDealerDraw() {
        StringBuilder sb = new StringBuilder("§f定庄比大小");
        int round = game.dealerDrawRound();
        if (round > 1) {
            sb.append("（第 ").append(round).append(" 轮）");
        }
        sb.append("：");
        boolean first = true;
        for (int g = 0; g < gamePlayers; g++) {
            int seat = seatOfGame[g];
            int card = game.dealerDraw(g);
            if (card < 0) {
                continue;
            }
            sb.append(first ? " " : " §7| ");
            first = false;
            sb.append("§f").append(seatName(seat))
                    .append(" §e").append(UnoDeck.name(card))
                    .append(" §7(").append(UnoGame.dealerPower(card)).append(")");
        }
        broadcast(sb.toString());
    }

    /** 定庄是否正在展示本轮在赛各家的比大小牌（客户端据此把它们摆到各自出牌区）。 */
    private boolean dealerShowing() {
        return phase == TablePhase.DEALER_DRAW && dealerStep == 1;
    }

    /** 定庄完成 → 洗牌发牌 → 进入对局。 */
    private void startPlay() {
        String err = game.deal();
        if (err != null) {
            broadcastEventTip("§c发牌失败：" + err);
            resetToWaiting();
            return;
        }
        phase = TablePhase.PLAYING;
        selected[0] = selected[1] = selected[2] = selected[3] = -1;
        pendingWild[0] = pendingWild[1] = pendingWild[2] = pendingWild[3] = -1;
        selectLocked[0] = selectLocked[1] = selectLocked[2] = selectLocked[3] = false;
        lastKey = -1;
        actionTicks = 0;
        actionLimit = computeActionLimit();
        // 逐张发牌：先把所有手牌摆好但隐藏，再逐 tick 亮出（见 tickDealing）
        dealTarget = 0;
        for (int s = 0; s < 4; s++) {
            int g = gameOfSeat[s];
            if (g >= 0) {
                dealTarget = Math.max(dealTarget, game.handSize(g));
            }
        }
        dealShown = dealTarget > 0 ? 0 : UnoTableEntity.DEAL_DONE;
        dealTick = 0;
        // 方案 B（需求 2026-10-19）：把每家的 7 张牌「谁先出现」打乱。
        // 手牌已经按牌 id 升序排好（红→黄→绿→蓝），照原序出会一眼看出"先算好再发"。
        // 这里给每家一个随机出现顺序，发牌时按这个顺序逐张摆上台，于是每张新出现的牌
        // 颜色都是随机的（见 spawnDealFlights / sync）。
        for (int s = 0; s < 4; s++) {
            int g = gameOfSeat[s];
            if (g < 0) {
                dealOrder[s] = null;
                continue;
            }
            List<Integer> order = new ArrayList<>(game.hand(g));
            Collections.shuffle(order, random);
            int[] arr = new int[order.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = order.get(i);
            }
            dealOrder[s] = arr;
        }
        int actor = actorSeat();
        // 需求 2026-10-18：开局提示统一为「对局开始…」，不再带「第 N 局」这类数字
        // （与五子棋 / 象棋等的「XX对局开始！」同一口径）。
        broadcast("§a对局开始！先出手的是 " + seatName(actor) + "。");
        if (game.isStartColorPending()) {
            // 起始牌是野牌：还要提示先手先选色，这条走动作栏，免得跟上面那条挤在一起
            broadcastEventTip("§e起始牌是野牌，" + seatName(actor) + " 请先点四种颜色之一。");
        }
        dirty = true;
    }

    /**
     * 逐张发牌推进（需求「复刻斗地主那种牌一张张出来」）。
     * <p>
     * 发牌期间不解释任何操作：按钮隐藏、操作方视为「不可行动」，避免玩家把还没出现的牌
     * 点掉。发完牌后把思考计时清零，让玩家从「看全手牌」那刻才开始计时。
     */
    private void tickDealing() {
        if (dealShown >= UnoTableEntity.DEAL_DONE) {
            return;
        }
        dealTick += DEAL_STEP;
        if (dealTick < DEAL_INTERVAL) {
            return;
        }
        dealTick = 0;
        // ⚠️ 顺序很关键：**先起飞、后亮真牌**。
        // 客户端真牌的显隐门是 `v.s{n} < v.deal_n`（见 build_table.card_layout_expr），
        // 也就是 deal_n 涨到 k+1 的那一刻第 k 张才出现。所以此刻 spawn 飞行牌、
        // 紧接着 dealShown++ 让真牌显示 —— 飞行牌从牌堆飞向的正是"真牌将要出现的位置"，
        // 落位时两者重合，真机看到的是"一张牌背飞过来，落到位变成手牌"。
        // **牌桌侧一个字节都不用改**，这就是走独立实体的好处。
        spawnDealFlights(dealShown);
        dealShown++;
        // 多走一拍（> 而不是 >=）再收尾：最后一张牌也要有 FLY_DEAL_T 的飞入时间，
        // 否则它一出现 deal_n 就跳到 255，会「啪」地瞬移到手里（客户端按
        // (deal_n-1-s)+dat/FLY_DEAL_T 算进度，deal_n 直接给 255 等于进度满格）。
        if (dealShown >= dealTarget) {
            dealShown = UnoTableEntity.DEAL_DONE;
            actionTicks = 0;
            // 发牌一结束，中央那张起始牌就"从牌堆翻出来"飞向庄家面前（见 triggerFlight）
            triggerFlight(true);
        }
        dirty = true;
    }

    /**
     * 为刚发出的第 {@code shown} 张（四家同时）各生成一个飞行牌实体。
     *
     * <p>每个实体只活 {@link UnoFlyCardEntity#FLY_TICKS} 个 tick（0.22 秒），
     * 由牌桌的 tick 里统一回收 —— 见 {@link #recycleFlyCards()}。飞行途中**不发包**，
     * 进度走客户端 {@code q.life_time} 自走时钟。</p>
     */
    private void spawnDealFlights(int shown) {
        int dealt = shown + 1;
        int[] sizes = {dealt, dealt, dealt, dealt};
        for (int s = 0; s < 4; s++) {
            int[] order = dealOrder[s];
            if (gameOfSeat[s] < 0 || order == null || shown >= order.length) {
                continue;
            }
            // ⚠️⚠️ 落点**必须**与该牌在手牌里的真实序号脱钩（2026-10-24 用户指出）：
            // 手牌按牌 id 升序排列，真实序号 = 这张牌的颜色/大小信息，旁观者只看飞牌
            // 消失在手牌哪个位置，就能反推这家摸到的是什么颜色 —— 这是透视漏洞。
            // 所以发牌与摸牌**统一落到当前手牌的最右端**（slot = 张数−1）：
            // 落点只随「手牌张数」变化，而张数是对手看得见的公开信息，不泄露牌面。
            // （代价：飞牌消失点与真牌按 id 排出的位置不重合，落位瞬间会微跳一下 ——
            //   这是刻意换来的，别改回去。）
            spawnFlyCard(s, dealt - 1, sizes, UnoFlyCardEntity.MODE_SLIDE);
        }
    }

    /**
     * 定庄比大小：给**本轮抽到牌的每家**各飞一张「从牌堆翻出」的牌背（飞牌实体模式 1），
     * 落点是该座位自己的出牌区中心 —— 就是「发完牌翻开起始牌」那套观感（需求 2026-10-19）。
     * <p>第一轮四家都抽了牌 ⇒ 四个动画一起出；并列重抽轮只给仍在抽牌的几家飞。</p>
     */
    private void spawnDealerFlights() {
        int[] sizes = handSizes();
        for (int g = 0; g < gamePlayers; g++) {
            int seat = seatOfGame[g];
            // ⚠️ 判定用「本轮有没有抽到牌」（dealerDraw >= 0），**不能**用 isDealerContender：
            // drawAllForDealer() 返回前 evaluateDealerDraw() 已经把点数不是最大的座位
            // dealerContender 置 false 了 —— 第一轮会只剩最大的那一家，看上去就只有一张牌有动画。
            // 第一轮四家都抽了牌 ⇒ 四个翻牌动画一起出；并列重抽轮只给仍在抽牌的几家飞。
            if (seat < 0 || game.dealerDraw(g) < 0) {
                continue;
            }
            // 区内序号 / 手牌张数在定庄阶段都没意义（模式 1 不用它们），传 0 即可。
            spawnFlyCard(seat, 0, sizes, UnoFlyCardEntity.MODE_DEALER);
        }
    }

    /** 四家当前手牌张数（飞行牌落点的居中量要用，与牌桌 card_N 读的是同一个数）。 */
    private int[] handSizes() {
        int[] sizes = new int[4];
        for (int s = 0; s < 4; s++) {
            int g = gameOfSeat[s];
            sizes[s] = g >= 0 ? game.handSize(g) : 0;
        }
        return sizes;
    }

    /**
     * 摸牌的飞入动画：贴台面平推的牌背飞向「这张牌落在手牌里的那个位置」。
     * <p>
     * 需求 2026-10-19：摸牌也走「发牌」那套飞入。人类点「抽牌」走 {@link #doDraw}，
     * 机器人 / 托管 / 离线代打走 {@link #autoAction}、人类超时代打走 {@link #forceSafeAction}
     * —— <b>这四条摸牌路径共用一个入口</b>，否则只有人类自己那一下能看到动画，其余三家没有。
     * <p>
     * 必须在 {@code pass()} <b>之前</b>调用：过牌会 {@code beginTurn()} 把 {@code drawnCard} 清掉。
     */
    private void spawnDrawFlight(int seat) {
        int g = gameOfSeat[seat];
        int drawn = game.drawnCard();
        if (g < 0 || drawn < 0) {
            return;
        }
        // ⚠️ 需求 2026-10-19：飞牌落点**不能**用这张牌在手牌里的真实编号。
        // 手牌按牌 id 升序排（红→黄→绿→蓝，从左到右），真实编号 = 这张牌的颜色/大小信息，
        // 旁观者只看飞牌落在哪个位置就能反推摸到的是什么颜色 —— 这是透视漏洞。
        // 所以固定落到**最右端**（朝右边的牌）：落点只随「手牌张数」变化，而张数是对手
        // 看得见的公开信息（桌面上的牌背张数），不泄露牌面。
        int slot = Math.max(0, game.handSize(g) - 1);
        spawnFlyCard(seat, slot, handSizes(), UnoFlyCardEntity.MODE_SLIDE);
    }

    /**
     * 生成一个飞行牌实体（发牌 / 摸牌 / 定庄共用入口）。
     *
     * @param seat   座位（决定落点公式与朝向）
     * @param slot   该座位手牌区内的序号（0 起）
     * @param sizes  四家当前手牌张数，落点居中量要用
     * @param mode   {@link UnoFlyCardEntity#MODE_SLIDE} 或 {@link UnoFlyCardEntity#MODE_DEALER}
     */
    private void spawnFlyCard(int seat, int slot, int[] sizes, int mode) {
        try {
            // ⚠️ 必须生成在 `origin`（桌面顶面 15/16），**不是 `center`**：
            // 飞行牌的动画表达式与牌桌模型共用同一套「模型单位」（1 单位 = 1/16 格），
            // 起点 1.52 / 落点 4.15 这些 y 值都是相对**牌桌实体原点**的。
            // 用 center（baseY+0.5）会把整张牌压低 7 个单位 ≈ 0.44 格 —— 整段飞行都在
            // 实心台面方块**内部**，真机上就是「什么都看不见」（2026-10-19 定位）。
            Entity created = Entity.createEntity(UnoFlyCardEntity.IDENTIFIER,
                    Position.fromObject(origin, level));
            if (!(created instanceof UnoFlyCardEntity fly)) {
                return;
            }
            // ⚠️ 必须在 spawnToAll() 之前写属性：客户端一收到实体就按当时的属性开始插值，
            // 之后再改就来不及了（客户端一收到实体就按当时的属性开始插值）。
            fly.setTarget(seat, slot, sizes, mode);
            fly.spawnToAll();
            flyAges.put(fly, 0);
        } catch (RuntimeException ignored) {
            // 实体生成失败不该影响对局：没有飞入动画而已，牌照发
        }
    }

    /** 回收到期的飞行牌实体（每个 tick 调一次，见 {@link #tickDealing} 之前的 for 循环）。 */
    private void recycleFlyCards() {
        if (flyAges.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UnoFlyCardEntity, Integer>> it = flyAges.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UnoFlyCardEntity, Integer> e = it.next();
            int age = e.getValue() + 1;
            if (age >= UnoFlyCardEntity.FLY_TICKS) {
                e.getKey().close();
                it.remove();
            } else {
                e.setValue(age);
            }
        }
    }

    /** 是否仍在逐张发牌。 */
    private boolean dealing() {
        return dealShown < UnoTableEntity.DEAL_DONE;
    }

    /**
     * 触发一次飞行动画（客户端自带 0.2 秒，见资源包 build_table 的 FLY_* 说明）。
     * <p>
     * 服务端不做动画 —— 只把 {@code uno_fly} 的**值变一下**（bit4 翻转），客户端检测到变化就把
     * 飞行进度清零、记下起点，然后按 {@code q.delta_time} 自己往前走：
     * <ul>
     *   <li>{@code mode=0}：出牌座位的手牌中心 → 他的出牌区，同时 65°→0° 放平；</li>
     *   <li>{@code mode=1}：中央牌堆顶 → 出牌区（发完牌后翻出的起始牌，以及定庄比大小的四张；
     *       后者由 {@code show_all} 决定落点是「各自座位的出牌区」），rx 180°→0° 露一圈牌背；</li>
     *   <li>{@code mode=2}：中央牌堆顶 → 摸牌者手牌区的新卡槽（摸牌），0°→65° 立起来。</li>
     * </ul>
     *
     * @param fromPile {@code true} = 从牌堆翻出的起始牌；{@code false} = 正常出牌
     */
    private void triggerFlight(boolean fromPile) {
        flyValue = ((flySeq++ & 1) << 1) | (fromPile ? 1 : 0);
        dirty = true;
    }

    // ==================================================================
    // 出牌 / 摸牌
    // ==================================================================

    /** 当前行动座位（牌桌座位）；无则 -1。 */
    public int actorSeat() {
        if (game == null) {
            return -1;
        }
        if (phase == TablePhase.PLAYING) {
            int g = game.currentSeat();
            return g >= 0 && g < gamePlayers ? seatOfGame[g] : -1;
        }
        // 定庄是四家同时抽，没有「轮到谁」，所以不点回合箭头（旧版是轮流抽才有的）
        return -1;
    }

    /** 该座位现在可否点按钮（结算阶段人人可点「下一局 / 退出」）。 */
    private boolean canAct(int seat) {
        if (seat < 0 || seat > 3) {
            return false;
        }
        if (phase == TablePhase.SETTLED) {
            return humans[seat] != null;
        }
        if (dealing()) {
            // 发牌动画期间不给任何人操作权（也不显示按钮），避免点到还没亮出来的牌
            return false;
        }
        return seat == actorSeat();
    }

    private int stateKey() {
        if (game == null) {
            return -1;
        }
        // ⚠️ 2026-10-19 需求：这里**不能**再把「进入野牌选色」当成一次状态变化。
        // 旧版把 bit7 置 1，于是点「出牌」选中万能牌 / +4 的那一刻计时清零重算 ——
        // 倒计时是群发到动作栏的，对面盯着看就会发现「同一个人的倒计时又跳回满格」，
        // 从而推断出他出的是这两种牌。现在选色与这一回合**共用剩余时间**（不重置），
        // 超时由 {@link #timeoutAction} 替他选色出牌。
        // 同理「离开选色」（出牌成功）走 applyPlay→afterAction，那里本来就会重算计时。
        return (game.currentSeat() & 7)
                | ((game.hasDrawn() ? 1 : 0) << 3)
                | ((game.isStartColorPending() ? 1 : 0) << 4)
                | ((game.phase() == UnoGame.PHASE_FINISHED ? 1 : 0) << 5)
                | ((phase == TablePhase.SETTLED ? 1 : 0) << 6);
    }

    private int computeActionLimit() {
        if (phase == TablePhase.DEALER_DRAW) {
            return DEALER_DRAW_DELAY;
        }
        if (phase != TablePhase.PLAYING) {
            return Integer.MAX_VALUE / 4;
        }
        int actor = actorSeat();
        if (actor < 0) {
            return Integer.MAX_VALUE / 4;
        }
        if (autoPlayed(actor)) {
            return BOT_THINK_TICKS;
        }
        // 人类回合时限读 UNO 配置的两档（均 5~600 秒，2026-10-16 需求）：
        //   · 第一次回合时间：还没摸牌时的「出牌 / 摸牌」时限（默认 20 秒）；
        //   · 第二次回合时间：已经摸牌、必须「出牌 / 跳过」的时限（默认 15 秒）。
        // 摸牌会让 stateKey() 变化 ⇒ tickPlaying 里计时清零并按本档重算。
        // 改配置立即对新回合生效（已进行中的计时由 UnoManager#onConfigReloaded 实时续算）。
        int seconds = game.hasDrawn()
                ? manager.config().normalizedSecondTurnSeconds()
                : manager.config().normalizedTurnSeconds();
        return seconds * TICKS_PER_SECOND;
    }

    /**
     * 配置（回合时间等）重载后重算当前行动的计时上限 —— 由
     * {@link UnoManager#onConfigReloaded()} 遍历在座牌桌调用，免除「等下一回合」。
     * 只在对局进行中、且有明确行动方时重算；其余阶段沿用原值（避免动 DEALER_DRAW 的展示节奏）。
     */
    void reevaluateActionLimit() {
        if (phase == TablePhase.PLAYING && actorSeat() >= 0) {
            actionLimit = computeActionLimit();
        }
    }

    /**
     * 该座位是否由系统代打：机器人占位 / 本人开了托管 / 断线离线。
     * <p>
     * 三种情况共用同一套「机器人节奏」（思考 {@link #BOT_THINK_TICKS} 后自动行动），
     * 差别只在客户端要不要画托管机器人（见 {@link #seatsState()}）。
     */
    private boolean autoPlayed(int seat) {
        return bots[seat] >= 0 || trusted[seat] || online[seat] == null;
    }

    private void tickPlaying() {
        if (dealing()) {
            // 发牌动画没放完不许出牌（需求：机器人也得等发完牌再动，不然牌还在往外发就先出了一手）
            return;
        }
        int actor = actorSeat();
        if (actor < 0) {
            return;
        }
        int key = stateKey();
        if (key != lastKey) {
            lastKey = key;
            actionTicks = 0;
            actionLimit = computeActionLimit();
            stallCount = 0;
        }
        tickCountdown(actor);
        if (++actionTicks < actionLimit) {
            return;
        }
        actionTicks = 0;
        if (autoPlayed(actor)) {
            // 托管 / 离线代打固定走「初级」档（2026-10-17 用户决策：与初级机器人同水平，
            // 免得人一走反而打出强战力）。机器人占位则用自己选定的档位。
            boolean wasThinking = aiThinking;
            autoAction(actor, bots[actor] >= 0 ? bots[actor] : UnoAi.LEVEL_EASY);
            if (!wasThinking) {
                // 这一手刚交给后台线程（AI 异步化 2026-10-22）：结果要等下一拍才回主线程，
                // 本拍「状态没变」是必然的，不能记成「没推进」。
                // 旧写法在这里就 ++stallCount，而计数器又不会随正常换人清零 ⇒ 三手机器人
                // 之后必然攒满 3，于是「明明能出牌却强行摸牌 / 过」。
                return;
            }
        } else {
            timeoutAction(actor);
        }
        // 兜底：若这一手没能推进状态（AI 真卡死 / 线程异常），连续 3 个思考周期后强制
        // 「摸牌 / 过」，把「静默卡死全桌」变成「自愈 + 控制台留痕」。
        if (phase == TablePhase.PLAYING && stateKey() == key) {
            if (++stallCount >= 3) {
                stallCount = 0;
                pluginLog("行动未推进状态，强制兜底：seat=" + actor
                        + " actor=" + actorSeat() + " key=" + key);
                forceSafeAction(actor);
            }
        } else {
            stallCount = 0;
        }
    }

    /**
     * 出牌倒计时：把「轮到谁、还剩几秒」发到**动作栏**（{@code sendTip}，准星下方提示条）。
     * <p>
     * 演进：第一版 action bar → 用户要"悬浮窗固定在方块上面" ⇒ name tag 悬浮字
     * （{@code FloatingTextParticle}）→ 实锤它是盔甲架：字大不可缩、真机能看见盔甲架本体
     * （2026-10-16 截图）⇒ 按用户指示回到动作栏，定时重发防淡出。
     * <p>
     * 三条规则不变：
     * <ul>
     *   <li>机器人 / 托管 / 离线座位**不显示**（固定 2 秒就出手，秒级倒计时只会闪）；</li>
     *   <li>换人 / 抽牌 / 进入选色时（{@code stateKey} 变化 → 计时清零）数字自然跟着变；</li>
     *   <li>颜色分段（2026-10-16 用户统一口径）：&gt;10 秒绿、4~10 秒黄、≤3 秒红色加粗；</li>
     * </ul>
     */
    private void tickCountdown(int actor) {
        if (eventTipHold > 0) {
            // 事件提示（出牌 / 摸牌 / UNO…）正占着动作栏：暂不刷新倒计时，防被瞬秒顶掉
            return;
        }
        if (autoPlayed(actor)) {
            setCountdownText(null);
            return;
        }
        int remain = Math.max(0,
                (actionLimit - actionTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
        // 配色（2026-10-16 统一口径）：≤3 秒红加粗 / 3~10 秒黄 / >10 秒绿
        String color = remain <= 3 ? "§c§l" : (remain <= 10 ? "§e" : "§a");
        // 名字完整显示：之前截 12 字符会把 "Mysterious him" 剪成 "Mysterious h"（用户反馈）
        String who = seatName(actor);
        setCountdownText("§e轮到 " + who + "\n§7剩余 " + color + remain + "§7 秒");
    }

    /**
     * 给桌上所有在线玩家发动作栏（{@code sendTip} = actionbar，准星下方提示条）。
     * 动作栏会淡出，常驻靠每秒重发（倒计时每秒变、结算板 tickSettled 里定时重发）。
     * <p>
     * 空文本<b>直接跳过</b>（2026-10-18 用户决策）：以前「清空动作栏」是 {@code sendTip("")}，
     * 客户端会把它渲染成一条空白提示（收结算板开局时、倒计时结束时都会各闪一下）。
     * 现在不再推空串，让旧内容自然淡出即可，代价是旧文字会残留几秒。
     */
    private void broadcastTip(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int s = 0; s < 4; s++) {
            Player p = online[s];
            if (p != null && p.isOnline()) {
                p.sendTip(text);
            }
        }
    }

    /**
     * 给桌上所有在线玩家（除某个座位）发动作栏 —— 用于「本人已有更具体的私聊动作栏文案」
     * 的场合（如摸牌：本人看「你摸到了 X」，其他人看「XX 摸了一张牌」）。
     */
    private void broadcastTipExcept(int exceptSeat, String text) {
        for (int s = 0; s < 4; s++) {
            Player p = online[s];
            if (s != exceptSeat && p != null && p.isOnline()) {
                p.sendTip(text);
            }
        }
    }

    /**
     * 事件提示（2026-10-16 播报迁移）：出牌 / 摸牌 / UNO / 超时 / 断线等事件走这里。
     * <p>
     * 与 {@link #broadcastTip} 的区别：会短暂「占住」动作栏约 {@link #EVENT_TIP_HOLD_TICKS}
     * tick，期间倒计时（每秒刷新）与结算板（每秒重发）暂停，事件文案不会被瞬秒顶掉；
     * 到期后倒计时 / 结算板自然接管。
     */
    private void broadcastEventTip(String text) {
        holdEventTip();
        broadcastTip(text);
    }

    /** 事件提示占位：仅供「群发与本人文案不同」的场合（如摸牌），置位后自行发送各自文案。 */
    private void holdEventTip() {
        eventTipHold = EVENT_TIP_HOLD_TICKS;
        countdownText = null;   // 事件期间倒计时停发；到期后 tickCountdown 自动重发
    }

    /** 设置动作栏提示（null = 清除）；内容没变不重发。 */
    private void setCountdownText(String text) {
        String next = (text == null || text.isEmpty()) ? null : text;
        if (next == null ? countdownText == null : next.equals(countdownText)) {
            return;
        }
        countdownText = next;
        broadcastTip(next == null ? "" : next);
    }

    /** 人类超时：摸一张后直接过（避免离席卡死整桌）。 */
    private void timeoutAction(int seat) {
        // 选色超时（需求 2026-10-19）：选色现在与回合共用剩余时间，所以「想到最后才点出牌」
        // 很容易直接撞上时限。这时替他选一个颜色、把这张野牌打出去，而不是另摸一张牌
        // —— 旧兜底会在选色态下摸牌，等于把万能牌留在手里、还白送一张牌，明显不合理。
        if (pendingWild[seat] >= 0) {
            int g = gameOfSeat[seat];
            int card = pendingWild[seat];
            if (g < 0) {
                return;
            }
            int color = new UnoAi(UnoAi.LEVEL_MASTER, random)
                    .chooseColor(game.hand(g), contextFor(g));
            Player p = online[seat];
            if (p != null) {
                p.sendTip("§e思考超时，已自动替你选了颜色并出牌。");
            }
            broadcastEventTip("§7" + seatName(seat) + " §7思考超时，系统自动选色代打。");
            // silent=false：失败时会提示原因并解开选色态（applyPlay 内部收尾，不能重复 afterAction）
            applyPlay(seat, card, color, false);
            return;
        }
        if (!forceSafeAction(seat)) {
            return;
        }
        Player p = online[seat];
        if (p != null) {
            p.sendTip("§e思考超时，已自动代打。");
        }
        broadcastEventTip("§7" + seatName(seat) + " §7思考超时，系统自动代打。");
        afterAction();
    }

    /**
     * 一定能让回合有进展的安全动作：先手选色 → 红；已摸牌 → 过；否则摸一张再考虑过。
     *
     * @return true = 确实执行了动作（调用方负责 {@link #afterAction()}）
     */
    private boolean forceSafeAction(int seat) {
        int g = gameOfSeat[seat];
        if (game == null || g < 0) {
            return false;
        }
        if (game.isStartColorPending()) {
            return game.chooseStartColor(g, UnoDeck.COLOR_RED) == null;
        }
        if (game.hasDrawn()) {
            return game.pass(g) == null;
        }
        if (game.draw(g) != null) {
            return false;
        }
        // 人类超时代打：摸牌同样要有飞入动画（需求 2026-10-19）；要在 pass() 之前调用。
        spawnDrawFlight(seat);
        if (game.phase() == UnoGame.PHASE_PLAYING && game.hasDrawn()) {
            game.pass(g);
        }
        return true;
    }

    /** 机器人 / 托管行动。 */
    private void autoAction(int seat, int level) {
        if (aiThinking) {
            return;
        }
        aiThinking = true;
        aiThinkingTicks = 0;
        int g = gameOfSeat[seat];
        // 所有决策都在后台线程算（2026-10-22）：主线程只负责取快照 + 落子。
        // UNO 的 AI 是微秒级，走 Nukkit 异步池即可（与六个棋类同款）。
        final List<Integer> hand = new ArrayList<>(game.hand(g));
        final long token = ++thinkToken;
        final java.util.Random rnd = new java.util.Random();

        if (game.isStartColorPending()) {
            // 起始牌是野牌时的选色：此时还没有人叫过色，用空局面
            AiThinkDispatcher.light(manager.getPlugin(),
                    () -> new UnoAi(level, rnd).chooseColor(hand, UnoAi.Context.EMPTY),
                    color -> {
                        aiThinking = false;
                        if (token != thinkToken) {
                            return;
                        }
                        if (game.chooseStartColor(g, color) == null) {
                            afterAction();
                        }
                    });
            return;
        }

        if (game.hasDrawn()) {
            final int drawn = game.drawnCard();
            final List<Integer> playable = new ArrayList<>(game.playableCards(g));
            final UnoAi.Context ctx = contextFor(g);
            AiThinkDispatcher.light(manager.getPlugin(),
                    () -> {
                        UnoAi ai = new UnoAi(level, rnd);
                        boolean legal = playable.contains(drawn);
                        boolean play = ai.shouldPlayDrawn(drawn, hand, legal);
                        int color = play && UnoDeck.isWild(drawn) ? ai.chooseColor(hand, ctx) : -1;
                        return new int[]{play ? 1 : 0, color};
                    },
                    r -> {
                        aiThinking = false;
                        if (token != thinkToken) {
                            return;
                        }
                        if (r != null && r[0] == 1) {
                            applyPlay(seat, drawn, r[1], true);
                        } else if (game.pass(g) == null) {
                            broadcastEventTip("§7" + seatName(seat) + " §7摸牌后不出。");
                            afterAction();
                        }
                    });
            return;
        }

        final List<Integer> playable = new ArrayList<>(game.playableCards(g));
        final UnoAi.Context ctx = contextFor(g);
        AiThinkDispatcher.light(manager.getPlugin(),
                () -> new UnoAi(level, rnd).decide(hand, playable, ctx),
                d -> {
                    aiThinking = false;
                    if (token != thinkToken || d == null) {
                        return;
                    }
                    if (d.draw()) {
                        if (game.draw(g) == null && game.phase() == UnoGame.PHASE_PLAYING) {
                            // 需求 2026-10-19：机器人 / 托管 / 离线代打摸牌也要有飞入动画
                            //（人类走 doDraw，之前这条路径漏了 —— 现场表现就是「其他三家摸牌没动画」）。
                            spawnDrawFlight(seat);
                            broadcastEventTip("§7" + seatName(seat) + " §7摸了一张牌。");
                        }
                        afterAction();
                    } else {
                        applyPlay(seat, d.cardId(), d.chosenColor(), true);
                    }
                });
    }

    /**
     * 出牌顺序上「后 3 家」的局面信息（高级档判断对手威胁用）。
     * 下标 0 = 下家，依次沿当前出牌方向数过去；P2 局只有 1 个对手，其余填 -1。
     */
    private UnoAi.Context contextFor(int g) {
        int[] sizes = new int[] {-1, -1, -1};
        int[] colors = new int[] {-1, -1, -1};
        for (int k = 0; k < 3 && k + 1 < gamePlayers; k++) {
            int gi = Math.floorMod(g + game.direction() * (k + 1), gamePlayers);
            sizes[k] = game.handSize(gi);
            colors[k] = game.lastCalledColor(gi);
        }
        return new UnoAi.Context(sizes, colors);
    }

    /**
     * 出牌统一入口（人类 / 机器人共用）。
     *
     * @param silent 机器人 / 托管时为 true，播报措辞与失败兜底不同
     */
    private void applyPlay(int seat, int cardId, int chosenColor, boolean silent) {
        int g = gameOfSeat[seat];
        String err = game.play(g, cardId, chosenColor);
        if (err != null) {
            if (!silent) {
                Player p = online[seat];
                // 被拒时一定要解开「野牌待选色」：选色态的按钮组只有四个颜色，
                // 没有抽牌 / 跳过，留着它就等于把玩家锁死在选色界面里。
                if (pendingWild[seat] >= 0) {
                    pendingWild[seat] = -1;
                    flush();
                }
                if (p != null) {
                    p.sendTip("§c" + err);
                }
                return;
            }
            // 机器人给不出合法牌（例如规则边界）：降级为「摸一张 / 过」，绝不递归重试
            pluginLog("机器人出牌被拒（seat=" + seat + " card=" + cardId + "）：" + err);
            if (game.hasDrawn()) {
                game.pass(g);
            } else {
                String derr = game.draw(g);
                if (derr == null) {
                    // 摸牌飞入同理（需求 2026-10-19）；必须在 pass() 之前，过牌会清掉 drawnCard。
                    spawnDrawFlight(seat);
                }
                if (derr == null && game.phase() == UnoGame.PHASE_PLAYING && game.hasDrawn()) {
                    game.pass(g);
                }
            }
            afterAction();
            return;
        }
        selected[seat] = -1;
        pendingWild[seat] = -1;
        // 「XX 出了什么牌」不再播报（2026-10-17 用户要求去掉）：牌面与定色由桌面出牌区直接显示。
        // 这张牌从出牌者的手牌中心飞到他的出牌区（与 zone 变更同一批同步发出去）
        triggerFlight(false);
        // 只剩 1 张 → 自动喊 UNO（这条不是「出了什么牌」，保留）
        if (game.handSize(g) == 1) {
            playSound(SND_UNO_CALL);
            broadcastEventTip("§c§l" + seatName(seat) + " 喊了 UNO！只剩最后一张！");
        }
        afterAction();
    }

    /** 机器人异常只在控制台留痕，不刷屏玩家聊天。 */
    private void pluginLog(String message) {
        Server.getInstance().getLogger().warning("[UNO] " + message);
    }

    /** 任意成功动作后的统一收尾：结算检测 + 计时重置 + 同步。 */
    private void afterAction() {
        aiThinking = false;
        aiThinkingTicks = 0;
        // 「没推进」计数只针对「当前这一手」，换人就该归零。
        // 异步化之后状态不会在同一拍里变，若不在这里清零就会跨回合累加（见 tickPlaying）。
        stallCount = 0;
        if (game.phase() == UnoGame.PHASE_FINISHED) {
            doFinish();
            return;
        }
        pendingWild[0] = pendingWild[1] = pendingWild[2] = pendingWild[3] = -1;
        // 换人即解锁：摸牌锁定只在「仍是当前行动方」时有意义。
        // 摸牌本身不推进回合（actorSeat() 仍是本人）→ 锁定保留；出牌/跳过/超时代打都会
        // 推进回合 → 本人已不是行动方 → 锁定自动解除，不会把下一轮的选牌也一起锁掉。
        int act = actorSeat();
        for (int s = 0; s < 4; s++) {
            if (s != act) {
                selectLocked[s] = false;
            }
        }
        lastKey = stateKey();
        actionTicks = 0;
        actionLimit = computeActionLimit();
        dirty = true;
    }

    /**
     * 结算：经济结算 → 结算板（动作栏）→ 进入 SETTLED。
     * <p>
     * 播报迁移（2026-10-16）：「本局结束」与战报摘要不再进聊天栏，全部并进结算板
     * （标题 = 本局结束·X获胜，逐座行 = 剩X张/分数/实扣额，末行 = 经济结算说明）。
     */
    private void doFinish() {
        if (settledFlag) {
            return;
        }
        settledFlag = true;
        // 对局计时定格：秒数用于结算板；分钟数（至少 1）作为时间倍率乘进总倍率
        long elapsedMs = roundStartedAt > 0
                ? Math.max(0L, System.currentTimeMillis() - roundStartedAt) : 0L;
        roundSeconds = (int) (elapsedMs / 1000L);
        timeFactor = Math.max(1, roundSeconds / 60);
        settleEconomy();
        showSettleBoard();
        phase = TablePhase.SETTLED;
        settleTicks = 0;
        selected[0] = selected[1] = selected[2] = selected[3] = -1;
        // 记录本局赢家座位：无人员变动的续局里由它连庄、免定庄（见 startRound(boolean)）
        lastWinnerSeat = game != null && game.winnerSeat() >= 0 ? seatOfGame[game.winnerSeat()] : -1;
        // 需求 2026-10-16：每局结束自动退出托管（下一局要重新点），并清空「下一局」确认
        clearTrustOnSettle();
        java.util.Arrays.fill(nextVote, false);
        dirty = true;
        refreshViewers();
        // 需求 2026-10-17：本局有玩家中途离席（座位一直被保留）→ 经济结算照常走完
        // （k > 0 才动账，见 settleEconomy），但不进结算页：离席者的空位直接腾空，
        // 回到「等待桌主开始」的准备阶段 —— 桌面上不会出现「下一局 / 退出」按钮。
        if (hasAwaySeats()) {
            vacateAwaySeats();
            StringBuilder sb = new StringBuilder(settleText[0] == null ? "" : settleText[0]);
            if (settleText[5] != null) {
                sb.append('\n').append(settleText[5]);
            }
            broadcast(sb.toString());
            resetToWaiting();
            broadcastEventTip("§7本局结束，离席玩家的空位已留空，等待桌主点「"
                    + waitingButtonLabel() + "」。");
            return;
        }
        if (humanCount() == 0) {
            // 桌上只剩机器人（真人都已离座 / 转为代打后离开）：没人能点「下一局」，直接收档
            resetToWaiting();
            return;
        }
        // 结算界面（需求 2026-10-18 / 2026-10-20）：**不自动弹表单**。
        // ⚠️ 连 refreshSeatMenus 都不调：Simple.process() 点按钮时 onClose 不执行 ⇒
        // SHOWING 残留条目会被 refreshSeatMenus 命中，导致结算表单自动弹出（bug）。
        // 玩家自己点桌面方块 / 桌边按钮打开菜单（下一局 / 托管 / 退出）。
    }

    /**
     * 经济结算。
     * <p>
     * 规则：
     * <ol>
     *     <li>每个输家应扣「剩余手牌分 × k」；</li>
     *     <li><b>扣到零为止</b>：应扣额大于余额时只扣余额（{@code 实扣 = min(应扣, 余额)}），余额不会变负；</li>
     *     <li>把各家<b>实扣额</b>累加成奖池；</li>
     *     <li>对奖池<b>整体</b>抽水（{@code gambling.cut %}）；</li>
     *     <li>税后金额全部给赢家。</li>
     * </ol>
     * <p>2026-10-19 需求：倍数 ≠ 0 的局里机器人也要出钱 —— 它按<b>无限余额</b>全额付「剩余手牌分 × k」
     * （这笔钱是凭空产生的，人类赢家因此能从机器人身上拿钱；「倍数非 0 时必须 2 位真人」
     * 这条限制见 {@link #addBot}）。机器人赢了同样照给不误，但<b>不做任何储存</b>（不入账）。
     * 免费局（{@code k == 0}）应扣恒为 0，直接跳过。
     * <p>
     * 播报迁移（2026-10-16）：本方法的说明与金额明细全部并入结算板
     * （{@link #settleEcoText} + 每座 {@link #settleNote}），不再发聊天栏 / 私聊消息。
     */
    /**
     * 和局原因文案（{@code game.winnerSeat() < 0} 时用）。
     * <p>两种成因见 {@link UnoGame#endReason()}：有人手牌达到 {@link UnoGame#HAND_LIMIT} 张 / 牌堆耗尽。
     */
    private static String drawReasonText(UnoGame g) {
        if (g == null) {
            return "本局无人出完";
        }
        return switch (g.endReason()) {
            // 不写座位号：这里是 game 座位，结算板上的 P1..P4 是牌桌座位，
            // 两者顺序不同（见 seatOfGame），超限的那个人由 settleNote 按牌桌座位标出来。
            case UnoGame.END_HAND_OVERFLOW -> "有人手牌达到 " + UnoGame.HAND_LIMIT + " 张";
            case UnoGame.END_PILE_EMPTY -> "牌堆耗尽（弃牌堆只剩最后一张）";
            default -> "本局无人出完";
        };
    }

    private void settleEconomy() {
        java.util.Arrays.fill(settlePaid, 0);
        java.util.Arrays.fill(settleNote, null);
        settlePrize = 0;
        settleEcoText = null;
        // ⚠️ 和局判定必须排在「免费局」之前：免费局的说明会提前 return，
        // 那样收费/免费两种桌子的和局原因就显示不出来（超限的那个座位也标不上）。
        int winnerGame = game.winnerSeat();
        if (winnerGame < 0) {
            // 和局（2026-10-24 新增两种成因）：一律**一分钱不动**，只说明原因。
            // ① 有人手牌达到 UnoGame.HAND_LIMIT（40）张 —— 一到就收，不算谁赢；
            //    但「出牌人出空手牌」的胜利优先（见 UnoGame.doPlay 的 +2/+4 分支），那种情况 winnerSeat >= 0。
            // ② 牌堆耗尽 —— 摸牌堆空、弃牌堆只剩刚打出的那一张，没得洗了。
            int ms = game.overflowSeat() >= 0 ? seatOfGame[game.overflowSeat()] : -1;
            if (ms >= 0) {
                settleNote[ms] = "（手牌达到 " + UnoGame.HAND_LIMIT + " 张 · 判和）";
            }
            settleEcoText = "§7" + drawReasonText(game) + "，本局判和，不进行金钱结算。";
            return;
        }
        double coef = getCoefficient();
        if (coef <= 0) {
            settleEcoText = "§7本桌倍数为 0（免费局），本局不进行金钱结算。";
            return;
        }
        int winnerSeat = seatOfGame[winnerGame];
        double cut = manager.config().normalizedCut();
        double pot = 0;
        for (int s = 0; s < 4; s++) {
            if (s == winnerSeat) {
                continue;
            }
            int g = gameOfSeat[s];
            if (g < 0) {
                continue;
            }
            double owed = game.handPoints(g) * coef * timeFactor;
            UUID id = humans[s];
            if (id == null) {
                // 机器人：无限余额，应扣额全额进奖池（不查余额、没有账户可扣）。
                double paid = Math.round(owed * 100.0) / 100.0;
                if (paid <= 0) {
                    continue;
                }
                settlePaid[s] = paid;
                pot += paid;
                settleNote[s] = "（人机·余额无限）";
                continue;
            }
            // 「扣到零为止」：应扣大于余额时只扣余额，绝不出现负数余额
            double paid = Math.min(owed, Math.max(0.0, EconomyHook.balance(id)));
            if (paid <= 0) {
                if (owed > 0) {
                    settleNote[s] = "（余额为 0 · 已破产）";
                    notifyBankrupt(online[s]);
                }
                continue;
            }
            if (!EconomyHook.reduce(id, paid)) {
                settleNote[s] = "（扣款失败，未计奖池）";
                continue;
            }
            settlePaid[s] = paid;
            pot += paid;
            if (EconomyHook.balance(id) <= 0) {
                settleNote[s] = "（余额已扣到 0 · 已破产）";
                notifyBankrupt(online[s]);
            } else if (paid < owed) {
                settleNote[s] = "（余额不足，未扣满）";
            }
        }
        if (pot <= 0) {
            settleEcoText = "§7本局无人有款可扣，奖池为空，赢家未获得奖金。";
            return;
        }
        double commission = Math.round(pot * cut / 100.0 * 100.0) / 100.0;
        double prize = Math.round((pot - commission) * 100.0) / 100.0;
        settlePrize = prize;
        String detail = "§6倍数 §e" + fmt(coef) + "§6 × 时间倍率 §e" + timeFactor
                + "§6（" + minutesSecondsText() + "） · 实扣合计 §e" + fmt(pot)
                + "§6 · 抽水 §e" + fmt(commission) + "§6（" + fmt(cut) + "%） · "
                + seatName(winnerSeat);
        if (humans[winnerSeat] != null) {
            EconomyHook.add(humans[winnerSeat], prize);
            settleEcoText = detail + "§6 实得 §e" + fmt(prize) + "§6。";
        } else {
            // 需求 2026-10-19：机器人赢 = 照算奖金，但不入账（机器人没有账户）。
            settleEcoText = detail + "§6 应得 §e" + fmt(prize) + "§7（人机不收钱，不入账）。";
        }
    }

    /**
     * 破产提示（2026-10-18 需求）：某玩家余额被扣到 0 时，单独私聊提醒他。
     * <p>
     * 本桌是「扣到 0 破产为止」的结算，破产后入座会被门槛拦下，因此这里只负责告知，
     * 不做任何强制离桌。
     */
    private void notifyBankrupt(Player p) {
        if (p != null && p.isOnline()) {
            p.sendMessage("§c[UNO] 你已破产：余额被扣至 0。下一局需先补充余额才能继续入座（本桌倍数为 "
                    + fmt(getCoefficient()) + "）。");
        }
    }

    // ==================================================================
    // 结算 / 重开
    // ==================================================================

    /**
     * 结算展示板（动作栏版，2026-10-16）：一条「§l§e第N局 · X获胜」标题 + 每座位一行明细，
     * 拼成多行动作栏发给桌上玩家，直到离开结算阶段才收。
     * <p>
     * 悬浮字路线已退役：{@code FloatingTextParticle} = 盔甲架（字大不可缩、真机能看见
     * 盔甲架本体）。按用户指示改用 {@code broadcastActionBar}（{@code sendTip}）：
     * 无实体、UI 字号天然小、定时重发即常驻。
     */
    private void showSettleBoard() {
        int wg = game != null ? game.winnerSeat() : -1;
        int winnerSeat = wg >= 0 ? seatOfGame[wg] : -1;
        // 赢家总得分 = Σ其余各家剩余手牌分（官方记分）
        int winnerPts = 0;
        for (int s = 0; s < 4; s++) {
            if (s != winnerSeat && gameOfSeat[s] >= 0) {
                winnerPts += game.handPoints(gameOfSeat[s]);
            }
        }
        boolean paid = getCoefficient() > 0;
        settleText[0] = winnerSeat >= 0
                ? "§l§e本局结束 · " + seatOccupantLabel(winnerSeat) + "获胜"
                : "§l§e本局结束 · 和局§r §7（" + drawReasonText(game) + "）";
        for (int s = 0; s < 4; s++) {
            int g = gameOfSeat[s];
            if (g < 0) {
                settleText[s + 1] = null;
                continue;
            }
            int pts = game.handPoints(g);
            if (s == winnerSeat) {
                settleText[s + 1] = "§a§lP" + (s + 1) + "·" + seatOccupantLabel(s)
                        + " §e+" + winnerPts + "分"
                        + (paid && settlePrize > 0 ? " → ¥" + fmt(settlePrize) : "");
            } else {
                settleText[s + 1] = "§fP" + (s + 1) + "·" + seatOccupantLabel(s)
                        + " §7剩" + game.handSize(g) + "张 §c-" + pts + "分"
                        + (paid ? " → ¥" + fmt(settlePaid[s]) : "")
                        + (settleNote[s] != null ? "§7" + settleNote[s] : "");
            }
        }
        // 第 6 行：经济结算说明（免费局 / 抽水 / 实得…）+ 本局总时长
        // 需求 2026-10-22：「对局时间：n 分 m 秒」只在结算时展示，对局中不占动作栏。
        String timeLine = "§7对局时间：§e" + minutesSecondsText();
        settleText[5] = (settleEcoText == null || settleEcoText.isEmpty())
                ? timeLine
                : settleEcoText + "\n" + timeLine;
        showSettleTip();
    }

    /**
     * 汇总 {@link #settleText} 刷到动作栏。顺带把 {@link #countdownText} 静默置空，
     * 否则主循环「非思考期摘倒计时」会把刚发的结算文案误清。
     */
    private void showSettleTip() {
        countdownText = null;
        refreshNextVoteLine();
        StringBuilder sb = new StringBuilder();
        for (String line : settleText) {
            if (line == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        broadcastTip(sb.length() == 0 ? "" : sb.toString());
    }

    /** 收起结算展示板（下一局 / 退回等待 / 拆桌时）。 */
    private void closeSettleBoard() {
        java.util.Arrays.fill(settleText, null);
        countdownText = null;
        broadcastTip("");
    }

    private void tickSettled() {
        // 每秒重发一次结算板（TICKS_PER_SECOND = 4 表 tick）：动作栏会淡出，定时重发才常驻；
        // 新走进视距 / 重连的玩家也能在下一秒收到。
        // 事件提示占位期间暂停重发，避免把「已收档 / 补充人机」等事件文案顶掉。
        if (eventTipHold <= 0 && settleTicks % TICKS_PER_SECOND == 0) {
            showSettleTip();
        }
        settleTicks++;
        // 需求 2026-10-16：结算不再自动收档 —— 无人事变动时停在这里，等所有在线真人点
        // 「下一局」（世界按钮 / 表单按钮）后自动续局；一旦有变动由 rosterChangedDuringSettle()
        // 立即收档退回 WAITING。
    }

    private void resetToWaiting() {
        phase = TablePhase.WAITING;
        closeSettleBoard();
        game = null;
        gamePlayers = 0;
        java.util.Arrays.fill(gameOfSeat, -1);
        java.util.Arrays.fill(nextVote, false);
        selected[0] = selected[1] = selected[2] = selected[3] = -1;
        pendingWild[0] = pendingWild[1] = pendingWild[2] = pendingWild[3] = -1;
        clearHover();
        lastKey = -1;
        settledFlag = false;
        broadcastEventTip("§7本局已收档，等待在座玩家点「" + waitingButtonLabel() + "」开新局。");
        refreshViewers();
        dirty = true;
        manager.markDirty();
        manager.refreshSeatMenus(this);
    }

    // ==================================================================
    // 续局：全员「下一局」确认（需求 2026-10-16）
    // ==================================================================

    /**
     * 在座真人总数（含离线；离线只影响「需要谁确认」，见 {@link #awaitingNextVote(int)}）。
     * <p>供菜单判断「倍数 ≠ 0 时能不能补人机」（需求 2026-10-19：至少 2 位真人才放行）。
     */
    public int humanCount() {
        int n = 0;
        for (int s = 0; s < 4; s++) {
            if (humans[s] != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * 结算期「下一局」需要等待确认的座位 = 在座且<b>在线</b>的真人。
     * <p>离线玩家由系统代打（与对局内一致），不阻塞续局；全员离线时没人能确认，
     * 由 {@link #allNextVotesIn()} 的「至少 1 人」条件兜底（等重连再确认）。
     */
    private boolean awaitingNextVote(int s) {
        return humans[s] != null && online[s] != null;
    }

    /** 需要等待确认的座位数。 */
    private int nextVoteNeeded() {
        int n = 0;
        for (int s = 0; s < 4; s++) {
            if (awaitingNextVote(s)) {
                n++;
            }
        }
        return n;
    }

    /** 已确认的座位数（只统计仍需等待的座位）。 */
    private int nextVoteDone() {
        int n = 0;
        for (int s = 0; s < 4; s++) {
            if (awaitingNextVote(s) && nextVote[s]) {
                n++;
            }
        }
        return n;
    }

    /** 所有在座在线真人都确认过了（且至少有一人）——满足即自动开下一局。 */
    private boolean allNextVotesIn() {
        int need = nextVoteNeeded();
        return need > 0 && nextVoteDone() >= need;
    }

    /** 该座位是否已确认「下一局」（表单按钮显示用）。 */
    public boolean hasVotedNext(int seat) {
        return seat >= 0 && seat < 4 && nextVote[seat];
    }

    /**
     * 确认 / 取消「下一局」（世界内按钮与表单按钮共用入口）。
     * <p>
     * 需求 2026-10-16：一局结束后不再自动收档；桌上玩家 / 人机无变动时，所有在线真人
     * 都点过「下一局」后自动续局（赢家连庄、免定庄，见 {@link #startRound(boolean)}）；
     * 再点一次可取消刚才的确认。人事 / 人机变动会收档退回等待（见 {@link #rosterChangedDuringSettle()}）。
     *
     * @return null 表示已受理，否则为拒绝原因
     */
    public String voteNext(Player p) {
        if (phase != TablePhase.SETTLED) {
            return "当前不在结算阶段。";
        }
        int seat = seatOf(p.getUniqueId());
        if (seat < 0) {
            return "你不在牌桌上。";
        }
        nextVote[seat] = !nextVote[seat];
        if (allNextVotesIn()) {
            startNextRound();
            return null;
        }
        broadcastEventTip("§7" + seatName(seat)
                + (nextVote[seat] ? " 已确认「下一局」" : " 取消了确认")
                + "§7（" + nextVoteDone() + "/" + nextVoteNeeded() + "）。");
        manager.refreshSeatMenus(this);
        return null;
    }

    /** 全员确认后自动开下一局：无人员变动 → 赢家连庄、免定庄（见 {@link #startRound(boolean)}）。 */
    private void startNextRound() {
        String err = startRound(true);
        if (err != null) {
            broadcastEventTip("§c无法开始下一局：" + err);
            resetToWaiting();
        }
    }

    /**
     * 结算阶段发生「人事 / 人机变动」（入座 / 离座 / 增删人机）：需求 2026-10-16 ——
     * 一旦有变动就弃用「全员点下一局」的续局流程，收档退回 {@code WAITING}，
     * 由在座玩家点「下一局」直接开新局（回到原流程）。
     */
    private void rosterChangedDuringSettle() {
        if (phase == TablePhase.SETTLED) {
            resetToWaiting();
        }
    }

    /** 每局结束自动退出托管（需求 2026-10-16）：托管只在本局内有效，下一局要重新开启。 */
    private void clearTrustOnSettle() {
        for (int s = 0; s < 4; s++) {
            if (!trusted[s]) {
                continue;
            }
            trusted[s] = false;
            Player p = online[s];
            if (p != null && p.isOnline()) {
                p.sendTip("§7本局结束，已自动取消托管（下一局需要重新开启）。");
            }
        }
    }

    /**
     * 结算板最后一行（{@link #settleText}[6]）：「下一局」确认进度。
     * <p>每次重发前重算（见 {@link #showSettleTip()}），所以投票 / 掉线 / 重连都会反映到下一帧。
     */
    private void refreshNextVoteLine() {
        if (phase != TablePhase.SETTLED) {
            settleText[6] = null;
            return;
        }
        int need = nextVoteNeeded();
        if (need == 0) {
            settleText[6] = "§7所有真人都已离线 · 重连后可点「下一局」继续（人机变动则自动收档）。";
            return;
        }
        StringBuilder sb = new StringBuilder("§7「下一局」确认 §e")
                .append(nextVoteDone()).append('/').append(need).append("§7：");
        for (int s = 0; s < 4; s++) {
            if (!awaitingNextVote(s)) {
                continue;
            }
            sb.append(" §fP").append(s + 1).append(nextVote[s] ? "§a✓" : "§c…");
        }
        sb.append("§7（点桌面方块开菜单确认）");
        settleText[6] = sb.toString();
    }

    /** 清空全部座位的准星高亮。 */
    private void clearHover() {
        hover[0] = hover[1] = hover[2] = hover[3] = UnoTableEntity.HOVER_NONE;
    }

    /**
     * 开一局（含自动定庄）。
     *
     * @return null 表示已开始，否则为拒绝原因
     */
    public String startRound() {
        return startRound(false);
    }

    /**
     * 开一局。
     *
     * @param inheritDealer {@code true} = 「无人事变动的续局」（全员点「下一局」触发）：
     *                      上一局赢家直接当庄家、<b>免定庄</b>，发完牌就开打（需求 2026-10-16）；
     *                      赢家缺失（上一局无人出完 / 赢家席位已空）或本局有过人员 / 人机变动时
     *                      自动回退到正常定庄流程。
     * @return null 表示已开始，否则为拒绝原因
     */
    public String startRound(boolean inheritDealer) {
        if (phase == TablePhase.DEALER_DRAW || phase == TablePhase.PLAYING) {
            return "本局正在进行中。";
        }
        if (occupiedSeats() < 2) {
            return "至少需要 2 个座位（玩家或人机）才能开局。";
        }
        buildMapping();
        closeSettleBoard();
        game = new UnoGame(gamePlayers, random);
        roundNumber++;
        phase = TablePhase.DEALER_DRAW;
        // 对局计时从这里开始（定庄 → 发牌 → 出牌 → 结算），中途不显示
        roundStartedAt = System.currentTimeMillis();
        roundSeconds = 0;
        timeFactor = 1;
        dealerIdx = 0;
        dealerStep = 0;
        dealerShowTicks = 0;
        dealerFlyTicks = 0;
        dealerPauseTicks = 0;
        dealerTie = 0;
        settledFlag = false;
        selected[0] = selected[1] = selected[2] = selected[3] = -1;
        pendingWild[0] = pendingWild[1] = pendingWild[2] = pendingWild[3] = -1;
        lastKey = -1;
        java.util.Arrays.fill(nextVote, false);
        // 上一局期间的人员 / 人机变动在这里消费掉：本局起重新统计
        boolean rosterWasChanged = rosterChanged;
        rosterChanged = false;
        // 按钮取名判据同样在开局清零：本局期间只要没人腾出座位，下一局仍叫「下一局」
        seatVacated = false;
        // 定位器已经在**入座**时发了（2026-10-27 统一口径），开局不再重复发；
        // 这里只兜一次底：万一玩家入座时背包满了没发成，开局这一刻补上。
        for (int s = 0; s < 4; s++) {
            Player p = online[s];
            if (p != null && p.isOnline()) {
                manager.ensureLocator(p);
            }
        }
        // 「无人事变动的续局」：上一局赢家连庄，免定庄、直接发牌（需求 2026-10-16）。
        if (inheritDealer) {
            int winnerGame = lastWinnerSeat >= 0 ? gameOfSeat[lastWinnerSeat] : -1;
            if (rosterWasChanged) {
                broadcast("§7牌桌有人员 / 人机变动，本局重新定庄。");
            } else if (!game.setDealer(winnerGame)) {
                broadcast("§7上一局判和（或赢家席位已空），本局重新定庄。");
            } else {
                broadcast("§6上局赢家 " + seatName(lastWinnerSeat) + " 连庄：本局免定庄，直接发牌。");
                startPlay();
                manager.closeSeatMenus(this);
                dirty = true;
                return null;
            }
        }
        broadcast("§e对局开始！先定庄：四家同时各抽一张牌比大小，展示 2 秒。");
        manager.closeSeatMenus(this);
        dirty = true;
        return null;
    }

    /**
     * 入座时的经济门槛（2026-10-16 前移到「入座」；2026-10-18 起门槛改为「系数 × 倍数」）。
     * <p>
     * 倍数 k 只在 <b>0 人入座</b>时才能设置（见 {@link #setCoefficient}），之后每个玩家
     * 入座时本桌倍数已经确定：{@code getCoefficient() > 0} 时要求该玩家余额 ≥
     * {@code 入座门槛系数 × 倍数}（系数见 config.yml 的 {@code uno.joinBalanceMultiplier}，
     * 默认 10），不足则<b>拒绝入座</b>；免费局（倍数 0）不校验。
     * <p>
     * 开局前不再做余额兜底（2026-10-18 用户要求）：本桌是「扣到 0 破产为止」的结算，
     * 入座后即便余额被花掉，靠结算时按实际余额扣款即可，无需再拦一次开局。
     *
     * @return null 表示通过，否则为拒绝原因
     */
    private String checkJoinStake(Player p) {
        double coef = getCoefficient();
        if (coef <= 0) {
            return null;
        }
        double need = manager.config().normalizedJoinBalanceMultiplier() * coef;
        double bal = Math.max(0.0, EconomyHook.balance(p.getUniqueId()));
        if (bal < need) {
            return "本桌倍数 " + fmt(coef) + "，入座要求余额大于等于 " + fmt(need)
                    + "（你当前 " + fmt(bal) + "）。";
        }
        return null;
    }

    /** 按座位升序建立「局内编号 ↔ 牌桌座位」映射（升序即顺时针序）。 */
    private void buildMapping() {
        java.util.Arrays.fill(gameOfSeat, -1);
        gamePlayers = 0;
        for (int s = 0; s < 4; s++) {
            if (isOccupied(s)) {
                gameOfSeat[s] = gamePlayers;
                seatOfGame[gamePlayers] = s;
                gamePlayers++;
            }
        }
    }

    // ==================================================================
    // 属性同步
    // ==================================================================

    /** 全量属性同步（批量一次发包）。 */
    private void sync() {
        if (visual == null || visual.isClosed()) {
            return;
        }
        visual.beginBatch();

        int[] zones = new int[UnoTableEntity.CARD_SLOTS];
        if (game != null) {
            for (int s = 0; s < 4; s++) {
                int g = gameOfSeat[s];
                if (g < 0) {
                    continue;
                }
                int[] order = dealOrder[s];
                if (dealShown >= UnoTableEntity.DEAL_DONE || order == null) {
                    for (int id : game.hand(g)) {
                        zones[id] = UnoTableEntity.ZONE_HAND_BASE + s;
                    }
                } else {
                    // 方案 B（需求 2026-10-19）：发牌期间只把「已经出现」的前 dealShown 张摆上台
                    // （顺序见 startPlay 里的随机 dealOrder），其余先不上台。这样每个座位那排牌的
                    // **张数**从 1 涨到 7，而客户端按「区内张数」居中，所以手牌会边发边撑开 ——
                    // 用户已确认接受这个副作用，换来的是"每张新出现的牌颜色随机"。
                    int k = Math.min(dealShown, order.length);
                    for (int i = 0; i < k; i++) {
                        zones[order[i]] = UnoTableEntity.ZONE_HAND_BASE + s;
                    }
                }
            }
            // 定庄展示：本轮在赛各家比大小的牌**各挂在对应座位的手牌区**（区值 1..4）+
            // table 的展示位 = 1；客户端在展示态把它们平铺到「该座位自己的出牌区」
            // （谁抽到的牌就摆在哪一侧，见 build_table 的 card_N 与 UnoTableEntity.SHOW_SHIFT）。
            // 展示只用 2 秒；并列重抽时这些牌会一直留到下一轮开始才被清掉（在赛者重抽新牌、
            // 淘汰者再没有牌），所以并列那一轮牌桌上是有牌的；发牌时 deal() 才把记录一并清掉
            // （它们可能被洗回牌堆发到玩家手上）。
            if (dealerShowing()) {
                for (int g = 0; g < gamePlayers; g++) {
                    int card = game.dealerDraw(g);
                    int seat = seatOfGame[g];
                    if (card >= 0 && seat >= 0) {
                        zones[card] = UnoTableEntity.ZONE_HAND_BASE + seat;
                    }
                }
            }
            // 台面上**永远保留上一张真正打出的牌**：弃牌堆顶 = 当前有效牌，从翻起始牌到本局结束
            // 一直存在（跳过 / 摸牌不出都不换牌，只有真的出牌才被下一张顶掉）。
            int top = game.topCard();
            if (top >= 0) {
                zones[top] = UnoTableEntity.ZONE_DISCARD;
            }
        }
        visual.setCardZones(zones);

        for (int s = 0; s < 4; s++) {
            if (selected[s] >= 0 && !ownsCard(s, selected[s])) {
                selected[s] = -1;
            }
            visual.setSelected(s, selected[s]);
            visual.setHover(s, hover[s]);
        }
        visual.setTableState(tableState());
        visual.setControls(UnoTableEntity.packControls(globalButtons()));
        visual.setPile(pileCount());
        visual.setDeal(dealShown);
        visual.setSeats(seatsState());
        visual.setFly(flyValue);
        visual.endBatch();
    }

    /**
     * 打包 {@code uno_seats}：每座位 2 位，0 = 正常 / 1 = 托管 / 2 = 离线托管。
     * <p>
     * 客户端据此决定要不要画那个站在手牌旁边的小机器人（几何体的 {@code bot_N} 骨骼）。
     * 机器人本身由玩家占位，所以这里只反映「谁来代打」，不表示座位被机器人占用；
     * 断线（{@code online == null}）同样由系统代打，所以也画机器人。
     */
    private int seatsState() {
        int value = 0;
        for (int s = 0; s < 4; s++) {
            int code = 0;
            if (humans[s] != null) {
                // 离线一律算「离线托管」（与 autoPlayed 的判定一致），在座才看托管开关
                code = online[s] == null ? 2 : (trusted[s] ? 1 : 0);
            }
            value |= (code & 3) << (UnoTableEntity.SEAT_BITS * s);
        }
        return value;
    }

    private boolean ownsCard(int seat, int cardId) {
        int g = gameOfSeat[seat];
        return g >= 0 && game != null && game.hand(g).contains(cardId);
    }

    /** 牌堆最多画几张（与资源包 build_model.PILE_MAX 同一个数）。 */
    public static final int PILE_LAYERS = 7;

    private int pileCount() {
        if (game == null) {
            return 0;
        }
        // 2026-10-19 用户需求：「多于 7 张只显示 7 张，少于 7 张按实际数量显示」——
        // 之前是写死 5（连剩几张都藏起来），现在按真实张数封顶 7。
        return Math.min(PILE_LAYERS, game.drawPileSize());
    }

    /** 当前全局按钮组（同一时刻只有行动方看得到）。 */
    private int[] globalButtons() {
        if (phase == TablePhase.SETTLED) {
            return UnoTableEntity.BTN_SETTLE;
        }
        int actor = actorSeat();
        if (actor < 0 || phase != TablePhase.PLAYING || dealing()) {
            return UnoTableEntity.BTN_NONE;
        }
        return buttonsFor(actor);
    }

    /** 指定座位的按钮组。 */
    private int[] buttonsFor(int seat) {
        if (phase == TablePhase.SETTLED) {
            return UnoTableEntity.BTN_SETTLE;
        }
        if (phase != TablePhase.PLAYING || game == null || dealing()) {
            return UnoTableEntity.BTN_NONE;
        }
        if (game.isStartColorPending() || pendingWild[seat] >= 0) {
            return UnoTableEntity.BTN_COLOR;
        }
        return game.hasDrawn() ? UnoTableEntity.BTN_DRAWN : UnoTableEntity.BTN_PLAY;
    }

    private int tableState() {
        int dealer = 0;
        int actor = 0;
        int acolor = 0;
        int dir = 0;
        // 中央那张牌是谁打出的 —— 客户端据此把它摆到那个座位自己的出牌区
        // （自己出的牌在自己这一侧、对手的牌贴对面那侧），见 build_model.PLAY_R。
        int dseat = 0;
        int colorOk = 0;
        if (game != null) {
            if (game.dealerSeat() >= 0) {
                dealer = seatOfGame[game.dealerSeat()] + 1;
            }
            int a = actorSeat();
            if (a >= 0) {
                actor = a + 1;
            }
            acolor = Math.max(0, game.activeColor());
            // 颜色标识只在「发牌动画结束」后才允许显示（需求 2026-10-16）：起始牌是发完 7 张
            // 后才从牌堆翻开的，发牌过程中不得提前泄露起始颜色；起始野牌即便发完也还没
            // 定色（activeColor=-1），等玩家选色后自然亮起。
            colorOk = (game.activeColor() >= 0 && dealShown >= UnoTableEntity.DEAL_DONE) ? 1 : 0;
            dir = game.direction() > 0 ? 0 : 1;
            int lp = game.lastPlaySeat();
            if (lp >= 0 && lp < gamePlayers) {
                dseat = seatOfGame[lp] + 1;
            } else if (game.dealerSeat() >= 0) {
                // 还没人出牌 → 台面上那张是**起始牌**（发完 7 张后从牌堆翻开的那张），
                // 需求：它摆在**庄家面前**。之后第一次出牌就会把它顶掉。
                dseat = seatOfGame[game.dealerSeat()] + 1;
            }
        }
        int owner = phase == TablePhase.SETTLED
                ? UnoTableEntity.BTN_ALL
                : actor;
        // bit17：结算后把别人的手牌公开给自己看（纯客户端渲染，见 build_table.card_layout_expr）
        int reveal = phase == TablePhase.SETTLED ? 1 : 0;
        return UnoTableEntity.tableState(dealer, actor, owner, acolor, dir, dseat,
                dealerShowing() ? 1 : 0, colorOk, reveal);
    }

    // ==================================================================
    // 射线 hover
    // ==================================================================

    /**
     * 以本桌基准做一次射线拾取：能行动的座位给「自己的手牌 + 当前按钮组」，
     * 否则退化为「当前行动方的牌面（不可点）+ 空按钮组」。
     *
     * @return 命中（含 hover 槽位 / 命中点牌面局部坐标 (u, v) / 手牌区内展示序号）
     */
    private UnoTableRay.Hit raycast(Player p, int seat) {
        boolean act = canAct(seat);
        int[] hand = ownHandArray(seat);
        int[] buttons = act ? globalButtons() : EMPTY_BUTTONS;
        int actor = act ? seat : actorSeat();
        return UnoTableRay.raycast(p, origin.x, origin.y, origin.z,
                seat, actor, hand, buttons, selected[seat]);
    }

    /** 每 2 表 tick 重算各座位准星值。 */
    private void updateHover() {
        for (int s = 0; s < 4; s++) {
            Player p = online[s];
            if (p == null || !p.isOnline()) {
                if (hover[s] != UnoTableEntity.HOVER_NONE) {
                    hover[s] = UnoTableEntity.HOVER_NONE;
                    dirty = true;
                }
                continue;
            }
            UnoTableRay.Hit hit = raycast(p, s);
            if (hit.hover() != hover[s]) {
                hover[s] = hit.hover();
                dirty = true;
            }
        }
    }

    /** 某座位手牌的卡槽数组（升序 = 展示顺序）。 */
    private int[] ownHandArray(int seat) {
        int g = gameOfSeat[seat];
        if (game == null || g < 0) {
            return EMPTY_HAND;
        }
        List<Integer> hand = game.hand(g);
        int[] out = new int[hand.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = hand.get(i);
        }
        return out;
    }

    // ==================================================================
    // 动画 / 声音 / 消息
    // ==================================================================

    private Collection<Player> viewers() {
        List<Player> out = new ArrayList<>(4);
        for (int s = 0; s < 4; s++) {
            if (online[s] != null && online[s].isOnline()) {
                out.add(online[s]);
            }
        }
        return out;
    }

    private void sendAnim(String animation, Collection<Player> players) {
        if (visual == null || visual.isClosed() || players.isEmpty()) {
            return;
        }
        // controller 只是一个「同类动画互相顶替」的分组标签（Bedrock 默认值 __runtime_controller）：
        // viewer_p0..p3 与 hidden 共用同一标签，才能保证切换视角时旧的被新的一把顶掉。
        AnimateEntityPacket.Animation anim = AnimateEntityPacket.Animation.builder()
                .animation(animation)
                .controller("tabletopgame:uno_viewer")
                .stopExpression("0")
                .blendOutTime(0f)
                .build();
        Entity.playAnimationOnEntities(anim, List.<Entity>of(visual), players);
    }

    /** 入座者看自家视角（viewer_pN），附近非入座者隐藏牌面（hidden）。 */
    /**
     * 让本桌「忘掉」某个玩家已收过视角动画这件事 —— 下一轮 refreshViewers 会重新发。
     * 调用时机：玩家复活、实体重建（客户端会丢 AnimateEntityPacket 状态）。
     */
    void forgetViewer(UUID id) {
        spectatorSent.remove(id);
        forceViewerResend = true;
    }

    private void refreshViewers() {
        if (visual == null || visual.isClosed()) {
            spectatorSent.clear();
            return;
        }
        spectatorNow.clear();
        // 空桌（无人在座）没有牌面需要隐藏、也没有观战者需要同步，
        // 直接跳过 —— 避免每轮遍历整关卡玩家表 + 空发动画包
        if (occupiedSeats() == 0) {
            spectatorSent.clear();
            return;
        }
        boolean resendSeated = forceViewerResend || viewerRound % SEATED_RESEND_ROUNDS == 0;
        forceViewerResend = false;
        viewerRound++;
        for (int s = 0; s < 4; s++) {
            Player p = online[s];
            if (p == null || !p.isOnline()) {
                viewerSentTo[s] = null;
                continue;
            }
            if (resendSeated || !p.getUniqueId().equals(viewerSentTo[s])) {
                sendAnim("animation.tabletopgame.uno.viewer_p" + s, List.of(p));
                viewerSentTo[s] = p.getUniqueId();
            }
        }
        // 差量发送（理由见 spectatorSent 的注释）：只发给本轮新进入半径的旁观者。
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
            sendAnim("animation.tabletopgame.uno.hidden", fresh);
        }
        spectatorSent.clear();
        spectatorSent.addAll(spectatorNow);
    }

    /** 自定义音效：只放给在座在线玩家。 */
    private void playSound(String sound) {
        Collection<Player> listeners = viewers();
        if (!listeners.isEmpty()) {
            level.addSound(center, sound, listeners.toArray(new Player[0]));
        }
    }

    private void broadcast(String message) {
        for (Player p : viewers()) {
            p.sendMessage("§b[UNO] " + message);
        }
    }

    // ==================================================================
    // 玩家交互
    // ==================================================================

    /**
     * 入座归位：把玩家摆到该座位的站位点，并转过身来面向棋心。
     * <p>
     * 复刻行为包 {@code D:\CodeProject\Gobang\斗地主\bp} 的做法（脚本里的 {@code Hg()}：
     * {@code player.teleport(棋心 + outward × 距离, {facingLocation: 棋心})}）。
     * 差别只有两点：距离换成 {@link #SEAT_STAND_RADIUS}（棋盘外隔 1 格），
     * 朝向因为没有 {@code facingLocation} 参数，就直接算成 yaw / pitch。
     *
     * @return null 表示已归位，否则是没能归位的原因
     */
    public String standAtSeat(Player p, int seat) {
        if (p == null || seat < 0 || seat > 3) {
            return "座位不合法。";
        }
        // ⚠️⚠️ 坐标系铁律（2026-10-19 南北镜像事故）：**OUT_* 是模型局部向量，不是世界向量。**
        // 客户端实体的局部 z 与世界 z 反号（UnoTableRay.raycast 里 `oz = -(eye.z - ez)` 就是这一步），
        // 所以「局部 +z」= 世界 −z。X 轴不翻转，Z 轴翻 —— 于是直接把 OUT_Z 当世界坐标用，
        // 只会把**南北两位整体对调**（东/西照样正常，非常容易误判成"渲染写错了"）。
        // 现象：玩家选了北位却被传到南边，而 viewer_mode 那一套（手牌/颜色盘/方向箭头/按钮）
        // 仍渲染在模型指定的北侧 ⇒ 自己的牌、按钮、展示区全跑到对面（用户真机截图）。
        // 这里显式做一次翻转，与 build_model/render_base 的 apex() 约定严格一致。
        double x = center.x + UnoTableRay.OUT_X[seat] * SEAT_STAND_RADIUS;
        double z = center.z - UnoTableRay.OUT_Z[seat] * SEAT_STAND_RADIUS;

        // 站位那一列可能是墙或别的方块：往上找第一处「脚 + 头都空」的高度，
        // 既不挖方块，也不把玩家塞进方块里。
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

        // 朝向棋心：yaw 与行为包 dB() 同一个约定 atan2(-dx, dz)；
        // 把 yaw/pitch 塞进 Location 再 teleport，传送包就会带上朝向。
        double dx = center.x - x;
        double dz = center.z - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double horiz = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
        double eyeY = standY + p.getEyeHeight();
        float pitch = (float) Math.toDegrees(
                Math.atan2(baseY + SEAT_FACE_TARGET_Y - eyeY, horiz));

        p.teleport(new Location(x, standY, z, yaw, pitch, level),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setRotation(yaw, pitch);
        return null;
    }

    /** 该处能否站人：脚（y）与头（y+1）两格都不是实心方块。 */
    private boolean isStandable(double x, int y, double z) {
        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        return !level.getBlock(fx, y, fz).isSolid()
                && !level.getBlock(fx, y + 1, fz).isSolid();
    }

    /**
     * 入座指定座位。
     *
     * @return null 表示成功，否则为拒绝原因
     */
    public String join(Player p, int seat) {
        if (seat < 0 || seat > 3) {
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
        if (manager.getTableOf(id) != null) {
            return "你已坐在其他牌桌上，请先退出那张牌桌。";
        }
        if (isOccupied(seat)) {
            return seatLabel(seat) + "已经有人了，请换一个座位。";
        }
        // 经济门槛前移到入座（2026-10-16 用户要求）：倍数 k 只在 0 人入座时才能设置，
        // 之后每个玩家入座时本桌倍数就已确定 —— 余额不足直接拒绝入座。
        String stake = checkJoinStake(p);
        if (stake != null) {
            return stake;
        }
        humans[seat] = id;
        online[seat] = p;
        bots[seat] = -1;
        names[seat] = p.getName();
        away[seat] = false;
        // 入座顺序 = 桌主顺位（需求 2026-10-17：第一个人走了交给「第二个加入的玩家」）
        if (!joinOrder.contains(id)) {
            joinOrder.add(id);
        }
        rosterChanged = true;
        if (firstJoiner == null) {
            firstJoiner = id;
        }
        manager.seat(id, this);
        releaseMenu(id);
        // 入座即归位：传送到本座位的站位点（棋心朝外 3 格）并转向棋心。
        String standErr = standAtSeat(p, seat);
        if (standErr != null) {
            p.sendTip("§e已入座，但没能归位：" + standErr);
        }
        manager.markDirty();
        refreshViewers();
        dirty = true;
        // 入座即发定位器（2026-10-27 用户统一口径，与棋类 / 斗地主 / 德州扑克一致）：
        // 不再等开局。等待期虽然没有悬空的牌，但准星叠层也要求「手持定位器」，早发一把
        // 玩家一坐下就有十字准星。幂等 + 背包满不发，见 LocatorService.give。
        manager.ensureLocator(p);
        manager.refreshSeatMenus(this);
        // 结算期入座 = 人事变动：收档退回等待（需求 2026-10-16）
        rosterChangedDuringSettle();
        return null;
    }

    /**
     * 补充一个机器人到下一个空位。<b>只有桌主</b>能操作（需求 2026-10-18）。
     *
     * <p>2026-10-19 需求：倍数 ≠ 0 的有钱局也允许补人机，但必须先凑够 <b>2 位真人</b>——
     * 否则「1 真人 + 机器人」能靠结算从机器人身上凭空拿钱（刷币）。免费局（人机局）不设此限。
     * 机器人一律视为<b>无限金币</b>：不查入座门槛，结算时按无限余额全额出钱
     * （见 {@link #settleEconomy()}）。</p>
     *
     * @return null 表示成功，否则为拒绝原因
     */
    public String addBot(Player p, int level) {
        String hostErr = requireHost(p);
        if (hostErr != null) {
            return hostErr;
        }
        if (level < UnoAi.LEVEL_EASY || level > UnoAi.LEVEL_MASTER) {
            return "机器人档位不合法。";
        }
        if (getCoefficient() != 0 && humanCount() < 2) {
            return "倍数非 0 的牌桌需要至少 2 位真人玩家才能补充人机。";
        }
        if (phase == TablePhase.DEALER_DRAW || phase == TablePhase.PLAYING) {
            return "对局进行中，无法补充人机。";
        }
        int empty = -1;
        for (int s = 0; s < 4; s++) {
            if (!isOccupied(s)) {
                empty = s;
                break;
            }
        }
        if (empty < 0) {
            return "座位已满（4 人），无法再补充人机。";
        }
        bots[empty] = level;
        botOrder.add(empty);
        refreshBotNames();
        rosterChanged = true;
        manager.markDirty();
        broadcastEventTip("§7" + seatLabel(empty) + " 补充了 " + names[empty] + "。");
        dirty = true;
        manager.refreshSeatMenus(this);
        // 结算期增删人机 = 变动：收档退回等待（需求 2026-10-16）
        rosterChangedDuringSettle();
        return null;
    }

    /**
     * 按<b>加入顺序</b>给在座机器人重排名字（需求 2026-10-19）。
     * <p>
     * 免费局（倍数 0）带档位前缀：「初级机器人1 / 高级机器人2」；
     * 倍数 ≠ 0 的局<b>不暴露档位</b>，只叫「机器人1 / 机器人2」。
     * 谁退出谁的编号就让给后面的人，桌上永远是从 1 开始的连续编号。
     */
    private void refreshBotNames() {
        botOrder.removeIf(s -> bots[s] < 0);
        int n = 0;
        for (int s : botOrder) {
            n++;
            names[s] = botLabel(bots[s], n);
        }
    }

    /** 机器人显示名：免费局带档位前缀，倍数 ≠ 0 的局只留编号。 */
    private String botLabel(int level, int n) {
        return getCoefficient() == 0
                ? UnoAi.levelName(level) + "机器人" + n
                : "机器人" + n;
    }

    /** 清除全部机器人（对局中不允许）。<b>只有桌主</b>能操作（需求 2026-10-18）。 */
    public String clearBots(Player p) {
        String hostErr = requireHost(p);
        if (hostErr != null) {
            return hostErr;
        }
        if (phase == TablePhase.DEALER_DRAW || phase == TablePhase.PLAYING) {
            return "对局进行中，无法清除人机。";
        }
        if (!hasBots()) {
            return "当前没有机器人。";
        }
        for (int s = 0; s < 4; s++) {
            if (bots[s] >= 0) {
                bots[s] = -1;
                names[s] = null;
            }
        }
        refreshBotNames();
        rosterChanged = true;
        seatVacated = true;
        manager.markDirty();
        broadcastEventTip("§7已清除全部人机。");
        dirty = true;
        manager.refreshSeatMenus(this);
        // 结算期增删人机 = 变动：收档退回等待（需求 2026-10-16）
        rosterChangedDuringSettle();
        return null;
    }

    /**
     * 增删人机要求操作者是桌主（{@link #firstJoiner}：入座顺序里最靠前、仍在座且未离席的那个）
     * —— 需求 2026-10-18：「加机器人那些，只能留给桌主操作」。
     *
     * @return null = 通过；否则为拒绝原因
     */
    private String requireHost(Player p) {
        if (p == null || !isFirstJoiner(p.getUniqueId())) {
            return "只有桌主（第一个入座者）可以增删人机。";
        }
        return null;
    }

    /**
     * 真人退座后座位上再无真人时，把机器人占位一并清掉（需求 2026-10-18）：
     * 无人牌桌不该留着人机，直接变回一张空桌。
     */
    private void dropAllBots() {
        dropAllBots("§7牌桌上已无真人，人机全部清除。");
    }

    /**
     * 清掉全部机器人并播报指定文案（{@code tip} = null 则不播报）。
     * <p>需求 2026-10-19：倍数 ≠ 0 的局里真人一旦只剩 1 位也要清全部人机 ——
     * 复用同一个入口，只是换一句文案。
     */
    private void dropAllBots(String tip) {
        boolean any = false;
        for (int s = 0; s < 4; s++) {
            if (bots[s] >= 0) {
                bots[s] = -1;
                names[s] = null;
                any = true;
            }
        }
        refreshBotNames();
        if (any && tip != null) {
            broadcastEventTip(tip);
        }
    }

    /**
     * 设定每局倍数 k。只能在「0 人入座」时由持有单人菜单锁的玩家设定（需求 2026-10-16）；
     * 入座后不可再改。合法值 = {@code 0}（免费局）或 UNO 配置「最小倍数 k ~ 最大倍数 k」
     * 区间内的整数（默认 10~1000，见 {@link #coefficientRange()}）。
     */
    public String setCoefficient(Player p, double k) {
        if (occupiedSeats() > 0) {
            return "有人入座后不能再改倍数（当前倍数 " + fmt(getCoefficient())
                    + "，只有 0 人入座时才能设置）。";
        }
        if (!holdsMenu(p)) {
            return "你没有该牌桌的菜单操作权，请重新点击牌桌。";
        }
        if (phase == TablePhase.DEALER_DRAW || phase == TablePhase.PLAYING) {
            return "对局进行中，无法修改倍数。";
        }
        double[] range = coefficientRange();
        if (k != 0 && (k < range[0] || k > range[1])) {
            return "倍数只能是 0（免费局），或 " + (long) range[0] + " ~ " + (long) range[1]
                    + " 之间的整数（区间可在「全局配置」里调整）。";
        }
        if (k != Math.floor(k)) {
            return "倍数只能是整数。";
        }
        coefficient = k;
        manager.markDirty();
        p.sendTip("§7本桌倍数已设为 §e" + fmt(coefficient) + "§7"
                + (coefficient <= 0 ? "（免费局：不扣钱、不抽水）。" : "（赢家按各家剩余手牌分收取）。"));
        // 倍数会显示在各家菜单正文里，改完立即刷新所有在座玩家的表单
        manager.refreshSeatMenus(this);
        return null;
    }

    /**
     * 本桌可设置的倍数区间 {@code [min, max]}：读 UNO 配置的「最小/最大倍数 k」，
     * 再按硬上限 {@link #MAX_COEFFICIENT} 与下界 0 钳制；区间非法（min &gt; max）时取 min。
     */
    public double[] coefficientRange() {
        double lo = Math.max(0.0, Math.min(MAX_COEFFICIENT, manager.config().normalizedMinCoefficient()));
        double hi = Math.max(0.0, Math.min(MAX_COEFFICIENT, manager.config().normalizedMaxCoefficient()));
        if (hi < lo) {
            hi = lo;
        }
        return new double[]{lo, hi};
    }

    /** 读档时恢复倍数 k（无权限校验，仅供管理器调用）。 */
    public void restoreCoefficient(double k) {
        if (k >= 0 && k <= MAX_COEFFICIENT) {
            coefficient = Math.floor(k);
        }
    }

    /** 关闭所有在座玩家的牌桌表单（开局 / 结算切换时用）。 */
    public void closeAllMenus() {
        manager.closeSeatMenus(this);
    }

    /** 延后一 tick 重新弹出本牌桌菜单（避免在表单回调里直接弹窗造成客户端窗口冲突）。 */
    public void laterOpen(Player player) {
        manager.reopenMenu(player, this);
    }

    /** 延后一 tick 执行一段动作。 */
    public void laterOpen(Runnable action) {
        manager.runLater(action);
    }

    /** 断线重连 / 托管中回归 / 离席后回桌：恢复在线并接管本人座位。 */
    public boolean reattach(Player p) {
        int s = seatOf(p.getUniqueId());
        if (s < 0 || online[s] != null) {
            return false;
        }
        online[s] = p;
        // 人回来了就自动取消托管：否则玩家会「明明在线却被系统代打」，与斗地主插件一致。
        // 中途离席（away）的座位同理：回来即「退出托管模式」，接着打本局（需求 2026-10-17）。
        boolean returned = away[s];
        boolean wasTrusted = trusted[s];
        trusted[s] = false;
        away[s] = false;
        if (returned || wasTrusted) {
            p.sendTip(returned ? "§a已回到牌桌，托管结束，接着打本局。" : "§7已自动取消托管。");
        }
        if (returned) {
            // 按「重新入座」排到桌主顺位队尾（本人的离席期间可能已让位）
            joinOrder.remove(p.getUniqueId());
            joinOrder.add(p.getUniqueId());
            broadcastEventTip("§7" + seatName(s) + " 已回到牌桌，接管本局。");
        }
        manager.seat(p.getUniqueId(), this);
        // 回到座位 = 再次入座 ⇒ 补发定位器（2026-10-27 统一口径：入座就发，不分阶段）
        manager.ensureLocator(p);
        refreshViewers();
        dirty = true;
        return true;
    }

    /** 是否处于「一局之内」（定庄中 / 对局中）。 */
    private boolean inGame() {
        return phase == TablePhase.DEALER_DRAW || phase == TablePhase.PLAYING;
    }

    /** 该座位是否开着托管（玩家在座，但出牌权交给系统）。 */
    public boolean isTrusted(int seat) {
        return seat >= 0 && seat < 4 && trusted[seat];
    }

    /**
     * 切换本座位的托管：开启后轮到本人时由系统按机器人逻辑自动出牌。
     * <p>
     * 需求原话：「增加机器人托管，也就是像斗地主插件一样点击棋桌，可以选择托管按钮」。
     * 托管期间玩家仍在座（不腾位、不断线也不丢座位），客户端会在他的手牌左边
     * 画出那个小机器人（{@code uno_seats} 属性）；
     * 随时再点一次「取消托管」即可自己接着打（断线重连也会自动取消）。
     *
     * @return null 表示成功，否则为拒绝原因
     */
    public String toggleTrust(Player p) {
        int seat = seatOf(p.getUniqueId());
        if (seat < 0) {
            return "你不在牌桌上。";
        }
        trusted[seat] = !trusted[seat];
        broadcastEventTip("§7" + seatName(seat)
                + (trusted[seat] ? " §7开启了托管，轮到本人时由系统代打。" : " §7取消了托管。"));
        // 托管切换会改变本回合的「思考节奏」（人类第一/二次回合时间 ↔ 机器人固定 2 秒），
        // 这里<b>只换时限上限，绝不重置已经走过的计时</b>。
        // 2026-10-18 修 bug：原先这里会把 actionTicks 清零，玩家只要「托管 → 2 秒内取消托管」
        // 就能把回合时间刷新回满，反复横跳即可无限拖延。改成只改 actionLimit 后：
        //   · 回合刚开就托管（已走 0 秒）→ 机器人仍会等满 2 秒；
        //   · 已经思考超过 2 秒再托管 → 机器人立刻代打（2 秒额度已被人类用掉，合理）；
        //   · 取消托管时剩余时间 = 人类时限 − 已走时间，不会被白送回去。
        if (phase == TablePhase.PLAYING && seat == actorSeat()) {
            actionLimit = computeActionLimit();
            lastKey = stateKey();
        }
        dirty = true;
        flush();
        manager.refreshSeatMenus(this);
        return null;
    }

    /**
     * 主动退出牌桌。
     * <p>
     * 对局进行中退出（需求 2026-10-17）：座位<b>为本人保留</b>、剩余牌局由系统托管代打，
     * 本局结束前回到牌桌即可接着打（别人顶替不了这个位置）；本局结束后座位才真正留空
     * （见 {@link #doFinish()}）。
     */
    public void leave(Player p) {
        int s = seatOf(p.getUniqueId());
        if (s < 0) {
            p.sendTip("§e你不在牌桌上。");
            return;
        }
        if (inGame()) {
            handOverToSystem(s, "退出了对局");
            // 离桌即回收（需求：「结束对局的话，自动回收」；只动定位器，不碰别的物品）——
            // 回桌时由 reattach 再补一把
            manager.takeLocator(p);
            p.sendTip("§e已退出牌桌：本局座位仍为你保留，回来点一下牌桌即可接着打。");
            return;
        }
        leaveSeat(s);
        manager.takeLocator(p);
        p.sendTip("§e已退出牌桌。");
    }

    /**
     * 断线 / 直接退出游戏。
     *
     * <p>与五类棋同一口径（需求 2026-10-22）：<b>不在对局中就直接腾位</b> ——
     * 等待、结算阶段都算，结算期按「人事变动」收档退回等待；
     * 只有 {@link #inGame()}（定庄 / 对局中）才保留座位、交给系统托管到本局结束
     * （中途退出保座是 2026-10-17 的需求，局末 {@link #vacateAwaySeats()} 会自动腾空）。</p>
     *
     * <p>⚠️ 这里曾经对 SETTLED 只做 {@code online[s] = null}：座位既没标记离席、也没腾空，
     * 下一局会带着一个「幽灵真人座位」继续，别人永远坐不进来。现在统一走
     * {@link #leaveSeat(int)}。</p>
     */
    public void onDisconnect(Player p) {
        int s = seatOf(p.getUniqueId());
        if (s < 0) {
            return;
        }
        if (inGame()) {
            handOverToSystem(s, "断线");
            return;
        }
        leaveSeat(s);
    }

    /**
     * 对局进行中真玩家离席（主动退出 / 断线 / 走出识别半径）：座位为本人保留、系统代打。
     * <p>
     * 三种入口共用这一处（需求 2026-10-17）：
     * <ul>
     *   <li>座位<b>不腾给任何人</b>（{@code humans} 保留原 UUID，别人 {@code join} 会被拒），
     *       本人回桌即 {@link #reattach} 接管；</li>
     *   <li>离席者若是桌主，桌主交给「第二个加入的玩家」（见 {@link #promoteHost()}）；</li>
     *   <li>若离席后场上再无其他真人（其余座位都是机器人），本局直接终止：
     *       清人机、清座位，变回空牌桌（见 {@link #abortBotsOnlyTable()}）。</li>
     * </ul>
     */
    private void handOverToSystem(int seat, String reason) {
        if (humans[seat] == null || away[seat]) {
            return;
        }
        UUID id = humans[seat];
        Player leaver = online[seat];
        String who = seatName(seat);
        away[seat] = true;
        trusted[seat] = false;
        online[seat] = null;
        if (id.equals(firstJoiner)) {
            promoteHost();
        }
        rosterChanged = true;
        seatVacated = true;
        manager.markDirty();
        dirty = true;
        broadcastEventTip("§7" + who + " " + reason + "，本局剩余部分由系统托管"
                + "（座位为本人保留，回桌即可接管）。");
        refreshViewers();
        manager.refreshSeatMenus(this);
        if (!hasOtherHuman(seat)) {
            // 只剩机器人，没人能接着打：直接终止本局，腾空牌桌
            if (leaver != null && leaver.isOnline()) {
                manager.takeLocator(leaver);
            }
            abortBotsOnlyTable();
        }
    }

    /** 除 {@code except} 外，座位上是否还有别的真人（含「离席但保留座位」的）。 */
    private boolean hasOtherHuman(int except) {
        for (int s = 0; s < 4; s++) {
            if (s != except && humans[s] != null) {
                return true;
            }
        }
        return false;
    }

    /** 是否有人中途离席、座位还在为本人保留。 */
    private boolean hasAwaySeats() {
        for (int s = 0; s < 4; s++) {
            if (away[s]) {
                return true;
            }
        }
        return false;
    }

    /** 桌主让位：交给「第二个加入的玩家」（入座顺序里仍在座、未离席的下一位）。 */
    private void promoteHost() {
        joinOrder.removeIf(id -> !isPresent(id));
        firstJoiner = joinOrder.isEmpty() ? null : joinOrder.get(0);
    }

    /** 该玩家是否「在座且未离席」（离席者不算：位子留着，人不在）。 */
    private boolean isPresent(UUID id) {
        int s = seatOf(id);
        return s >= 0 && !away[s];
    }

    /** 中途离席者保留的座位彻底腾空（本局结束 / 牌桌终止时调用，需求 2026-10-17）。 */
    private void vacateAwaySeats() {
        for (int s = 0; s < 4; s++) {
            if (!away[s]) {
                continue;
            }
            UUID id = humans[s];
            if (id != null) {
                manager.unseat(id);
                joinOrder.remove(id);
                Player p = Server.getInstance().getPlayer(id).orElse(null);
                if (p != null) {
                    manager.takeLocator(p);
                }
            }
            humans[s] = null;
            online[s] = null;
            bots[s] = -1;
            names[s] = null;
            trusted[s] = false;
            away[s] = false;
        }
        if (firstJoiner != null && !isPresent(firstJoiner)) {
            promoteHost();
        }
    }

    /**
     * 桌上再无真人时终止本局（需求 2026-10-17）：清除人机、腾空全部座位，
     * 变回一张「空座位」的牌桌。
     */
    private void abortBotsOnlyTable() {
        List<UUID> ids = new ArrayList<>(4);
        for (int s = 0; s < 4; s++) {
            if (humans[s] != null) {
                ids.add(humans[s]);
            }
        }
        for (UUID id : ids) {
            manager.unseat(id);
        }
        closeSettleBoard();
        game = null;
        gamePlayers = 0;
        java.util.Arrays.fill(gameOfSeat, -1);
        java.util.Arrays.fill(selected, -1);
        java.util.Arrays.fill(pendingWild, -1);
        java.util.Arrays.fill(selectLocked, false);
        java.util.Arrays.fill(nextVote, false);
        java.util.Arrays.fill(settlePaid, 0);
        for (int s = 0; s < 4; s++) {
            humans[s] = null;
            online[s] = null;
            bots[s] = -1;
            names[s] = null;
            trusted[s] = false;
            away[s] = false;
        }
        joinOrder.clear();
        firstJoiner = null;
        lastWinnerSeat = -1;
        // 全员离桌 = 座位全都空了：下次开局按「开始游戏」算（局数照常继承）
        seatVacated = true;
        settledFlag = false;
        dealerStep = 0;
        dealerShowTicks = 0;
        dealerFlyTicks = 0;
        dealerPauseTicks = 0;
        dealerTie = 0;
        clearHover();
        menuHolder = null;
        phase = TablePhase.WAITING;
        broadcastEventTip("§7牌桌上已无真人，本局终止：人机与座位全部清除。");
        refreshViewers();
        dirty = true;
        manager.markDirty();
        manager.refreshSeatMenus(this);
    }

    /** 腾出座位：等待 / 结算阶段彻底腾空（对局中的离席走 {@link #handOverToSystem}）。 */
    private void leaveSeat(int seat) {
        rosterChanged = true;
        seatVacated = true;
        UUID id = humans[seat];
        if (id != null) {
            manager.unseat(id);
            joinOrder.remove(id);
        }
        trusted[seat] = false;
        away[seat] = false;
        humans[seat] = null;
        online[seat] = null;
        bots[seat] = -1;
        names[seat] = null;
        if (id != null && id.equals(firstJoiner)) {
            promoteHost();
        }
        // 需求 2026-10-18：真人这么一走，座位上再没有真人（只剩机器人占位）→ 人机一并清掉，变回空桌。
        if (humanCount() == 0) {
            dropAllBots();
        } else if (getCoefficient() != 0 && humanCount() <= 1
                && phase != TablePhase.PLAYING && phase != TablePhase.DEALER_DRAW) {
            // 需求 2026-10-19：倍数 ≠ 0 的局里真人退到只剩 1 位 → 人机一并清掉。
            // 否则「1 真人 + 机器人」在本局结算时能从机器人身上凭空拿钱（防刷币）。
            // 只在对局开始前（等待 / 结算页）生效；对局中的离席走 handOverToSystem，不会到这里。
            dropAllBots("§7真人只剩 1 位，人机已全部清除（倍数非 0 时不允许 1 位真人带人机）。");
        }
        if (occupiedSeats() == 0) {
            hover[0] = hover[1] = hover[2] = hover[3] = UnoTableEntity.HOVER_NONE;
            menuHolder = null;
        }
        manager.markDirty();
        refreshViewers();
        dirty = true;
        manager.refreshSeatMenus(this);
        // 结算期的离座 = 人事变动：弃用全员确认流程，收档退回等待（需求 2026-10-16）
        rosterChangedDuringSettle();
    }

    /** 点击牌桌（未入座）打开菜单。 */
    public void openMenu(Player p) {
        manager.openMenu(p, this);
    }

    /**
     * 右键牌桌的交互分派：先按准星命中手牌 / 按钮，未命中则打开菜单。
     * <p>与斗地主一致，但<b>不需要手持激光笔</b>。
     */
    public void useBoard(Player p) {
        useBoard(p, true);
    }

    /** 右键「空中」（准星没打中方块）：只认手牌 / 按钮命中，未命中不弹菜单。 */
    public boolean useBoardAir(Player p) {
        return useBoard(p, false);
    }

    /**
     * 准星此刻是否压在**手牌或桌面按钮**上（只看不点，2026-10-22）。
     *
     * <p>定位器「蹲下 + 使用」切换准星时要避开这些位置：用户原话「触发到了我们牌、
     * 按钮区域投影位置的也不算」。这里与 {@link #useBoard(Player, boolean)} 用同一套射线参数，
     * 但只返回命中与否，<b>不执行任何动作</b>（不改选中态、不按键、不开菜单）。</p>
     */
    public boolean hasTarget(Player p) {
        int s = seatOf(p.getUniqueId());
        if (s < 0) {
            return false;
        }
        if (phase == TablePhase.WAITING || phase == TablePhase.DEALER_DRAW) {
            return false;
        }
        if (phase == TablePhase.SETTLED) {
            UnoTableRay.Hit hit = UnoTableRay.raycast(p, origin.x, origin.y, origin.z,
                    s, s, EMPTY_HAND, UnoTableEntity.BTN_SETTLE, -1);
            return UnoTableEntity.hoveredButtonSlot(hit.hover()) >= 0;
        }
        int[] hand = ownHandArray(s);
        boolean act = canAct(s);
        int[] buttons = act ? globalButtons() : EMPTY_BUTTONS;
        int actor = act ? s : actorSeat();
        UnoTableRay.Hit hit = UnoTableRay.raycast(p, origin.x, origin.y, origin.z,
                s, actor, hand, buttons, selected[s]);
        return hit.hover() != UnoTableEntity.HOVER_NONE;
    }

    /**
     * 右键牌桌的交互分派。
     *
     * @param allowMenu 没命中手牌 / 按钮时是否打开菜单。
     *                  <p><b>为什么需要这个开关</b>：手牌是<b>悬空</b>在台面上方的（第一行底边
     *                  离台面 0.26 格，第二行起更高），而基岩客户端的准星只会去命中<b>方块</b>
     *                  —— 瞄第二行及以上的手牌时准星打不到牌桌方块，客户端就只发来
     *                  「右键空中」（{@code RIGHT_CLICK_AIR}）事件。这类事件必须接受
     *                  （否则第二行手牌点不动），但它的准星本来就落在牌桌附近，
     *                  未命中时再弹菜单会变成误触，所以空中路径传 {@code false}。
     * @return 是否吃掉了这次点击（命中了手牌或按钮）
     */
    public boolean useBoard(Player p, boolean allowMenu) {
        UUID id = p.getUniqueId();
        int s = seatOf(id);
        if (s < 0) {
            if (allowMenu) {
                openMenu(p);
            }
            return false;
        }
        if (online[s] == null) {
            reattach(p);
        }
        if (phase == TablePhase.WAITING || phase == TablePhase.DEALER_DRAW) {
            if (allowMenu) {
                openMenu(p);
            }
            return false;
        }
        if (phase == TablePhase.SETTLED) {
            // 结算阶段按钮不依赖准星：直接打开菜单更稳（客户端按钮只在桌面上，点击易漏）
            int[] group = UnoTableEntity.BTN_SETTLE;
            int[] hand = EMPTY_HAND;
            int actor = s;
            UnoTableRay.Hit hit = UnoTableRay.raycast(p, origin.x, origin.y, origin.z,
                    s, actor, hand, group, -1);
            int btn = UnoTableEntity.hoveredButtonSlot(hit.hover());
            if (btn >= 0) {
                pressButton(p, s, btn);
                return true;
            }
            // 结算按钮那一带也算「交互区」：落在按钮之间的缝里不要弹菜单（2026-10-23）。
            if (allowMenu && !UnoTableRay.hitsInteractiveZone(p, origin.x, origin.y, origin.z,
                    s, actor, hand, group)) {
                openMenu(p);
            }
            return false;
        }
        int[] hand = ownHandArray(s);
        boolean act = canAct(s);
        int[] buttons = act ? globalButtons() : EMPTY_BUTTONS;
        int actor = act ? s : actorSeat();
        UnoTableRay.Hit hit = UnoTableRay.raycast(p, origin.x, origin.y, origin.z,
                s, actor, hand, buttons, selected[s]);
        int hv = hit.hover();
        if (hv == UnoTableEntity.HOVER_NONE) {
            // 2026-10-23 用户要求：牌与牌、按钮与按钮之间都有缝，缝里点一下以前会弹出配置表单
            // （「我不点按钮的时候点的话会弹出表单」）。现在整片「手牌 + 按钮」都算交互区，
            // 落在里面就吞掉点击、什么都不做；只有这片区域**之外**的桌面才开菜单。
            if (allowMenu && !UnoTableRay.hitsInteractiveZone(p, origin.x, origin.y, origin.z,
                    s, actor, hand, buttons)) {
                openMenu(p);
            }
            return false;
        }
        int card = UnoTableEntity.hoveredCardSlot(hv);
        if (card >= 0) {
            selectCard(p, s, card);
            return true;
        }
        int btn = UnoTableEntity.hoveredButtonSlot(hv);
        if (btn >= 0) {
            pressButton(p, s, btn);
            return true;
        }
        return false;
    }

    /** 点击手牌：切换选中（同槽再点取消）。 */
    private void selectCard(Player p, int seat, int cardId) {
        if (phase != TablePhase.PLAYING) {
            return;
        }
        // ⚠️ 这里**故意不判回合**（2026-10-21 用户要求）：手牌「提起」（抬起来）只是本地
        //    选中态，没有任何游戏效果，任何回合都该能操作 —— 别人回合提前把要出的牌抬起来，
        //    轮到自己是点「出牌」就行。真正改状态的入口（出牌 / 抽牌 / 跳过 / 提示）仍然
        //    逐条判回合，见 pressButton。
        //    唯一的例外是摸牌后：那张牌是**强制选中**的（selectLocked，见下），
        //    不能顶掉也不能取消，只能走「出牌」或「跳过」，跳过时自动收回 —— 用户原话
        //    「固定提起抽到的牌，选择跳过之后自动收回」。
        if (pendingWild[seat] >= 0) {
            // 逃生口：选色态只有四个颜色按钮，点手牌视为「取消这次出牌」。
            // 没有这条，任何意外（例如校验漏网）都会让玩家锁死在选色界面。
            pendingWild[seat] = -1;
            p.sendTip("§7已取消选色，重新点「出牌」可以再选一次。");
            flush();
        }
        if (selectLocked[seat]) {
            // 摸牌后的那张牌是强制选中的：别人（和别的牌）都不能把它「顶掉」，
            // 只能走「出牌」或「跳过」两条路，避免从高亮消失推断出摸到了什么牌。
            p.sendTip("§e请先点「出牌」或「跳过」。");
            return;
        }
        if (game.isStartColorPending()) {
            p.sendTip("§e请先选一个颜色。");
            return;
        }
        selected[seat] = selected[seat] == cardId ? -1 : cardId;
        flush();
    }

    /** 立即发包：选牌 / 按钮这类交互反馈要跟手，不能等到 tick 末尾。 */
    private void flush() {
        dirty = false;
        sync();
    }

    /** 按钮点击（index = 槽位下标）。 */
    private void pressButton(Player p, int seat, int index) {
        int[] group = buttonsFor(seat);
        if (index < 0 || index >= group.length || group[index] == 0) {
            return;
        }
        // 桌面按钮是服务端自己画的，没有 UI 自带的点击音，这里补一个原版「点击」音效
        // （{@code random.click}，与箱子/拉杆按钮同一个）。只放给点的人听，避免四个人都响。
        // 用原版音效 + 服务端 addSound 即可，无需资源包提供任何东西（2026-10-18 需求）。
        p.getLevel().addSound(p.getPosition(), Sound.RANDOM_CLICK, 1.0f, 1.0f, p);
        int code = group[index];
        switch (code) {
            case Controls.PLAY -> doPlayButton(p, seat);
            case Controls.DRAW -> doDraw(p, seat);
            case Controls.SKIP -> doSkip(p, seat);
            case Controls.HINT -> doHint(p, seat);
            case Controls.NEXT -> doNext(p);
            case Controls.EXIT -> leave(p);
            case Controls.C_RED, Controls.C_YELLOW, Controls.C_GREEN, Controls.C_BLUE ->
                    doChooseColor(p, seat, code - Controls.C_RED);
            default -> {
            }
        }
    }

    /** 「出牌」：野牌先切到选色按钮组。 */
    private void doPlayButton(Player p, int seat) {
        if (seat != actorSeat()) {
            p.sendTip("§e还没轮到你。");
            return;
        }
        int card = game.hasDrawn() ? game.drawnCard() : selected[seat];
        if (card < 0) {
            p.sendTip("§e请先点击手牌选择要出的牌。");
            return;
        }
        if (UnoDeck.isWild(card)) {
            // 必须在进入选色态**之前**判定这张牌到底能不能出：选色态的按钮组只有
            // 四个颜色，没有「抽牌 / 跳过」，一旦进来又出不了（典型：
            // 手里还有当前颜色的牌却打「+4 王牌」），玩家就被锁死在这里，只能等超时代打。
            String why = game.checkPlay(gameOfSeat[seat], card);
            if (why != null) {
                p.sendTip("§c" + why);
                return;
            }
            pendingWild[seat] = card;
            selected[seat] = card;
            p.sendTip("§e野牌需要先选一个颜色。");
            flush();
            return;
        }
        applyPlay(seat, card, -1, false);
    }

    /** 「选色」。 */
    private void doChooseColor(Player p, int seat, int color) {
        if (color < 0 || color >= UnoDeck.COLOR_COUNT) {
            return;
        }
        int g = gameOfSeat[seat];
        if (game.isStartColorPending()) {
            String err = game.chooseStartColor(g, color);
            if (err != null) {
                p.sendTip("§c" + err);
                return;
            }
            broadcastEventTip("§f" + seatName(seat) + " §7把起始颜色定为 §e"
                    + UnoDeck.colorName(color));
            afterAction();
            return;
        }
        int card = pendingWild[seat];
        if (card < 0) {
            p.sendTip("§e选色已失效，请重新点「出牌」。");
            return;
        }
        // 两次点击之间局面可能变了（超时代打 / 回合被推进），再校验一次；
        // 不通过就**解除选色态**，绝不能把玩家留在只有颜色按钮的界面里。
        String why = game.checkPlay(g, card);
        if (why != null) {
            pendingWild[seat] = -1;
            p.sendTip("§c" + why + "（已取消出牌）");
            flush();
            return;
        }
        applyPlay(seat, card, color, false);
    }

    /** 「抽牌」。 */
    private void doDraw(Player p, int seat) {
        if (seat != actorSeat()) {
            p.sendTip("§e还没轮到你。");
            return;
        }
        if (game.hasDrawn()) {
            p.sendTip("§e这一回合已经摸过牌了。");
            return;
        }
        String err = game.draw(gameOfSeat[seat]);
        if (err != null) {
            p.sendTip("§c" + err);
            return;
        }
        int drawn = game.phase() == UnoGame.PHASE_PLAYING ? game.drawnCard() : -1;
        if (drawn >= 0) {
            // 需求 2026-10-19：摸牌也走「发牌」那套飞入（贴台面平推，落点 = 这张牌在手牌里的位置）。
            // 真牌这一拍已经落在手上（牌桌侧的显示门改不得，见 card_N 的历史事故），所以观众看到的是
            // 「手牌里多出一张，同时一张牌背从牌堆贴着台面滑向它、触到木框就消失」。
            spawnDrawFlight(seat);
            // 2026-10-23：取消「摸牌后 0.5 秒按钮不响应」。用户实测那 0.5 秒真正的副作用是
            // 「想点按钮却点空了、然后弹出配置表单」，所以那把锁已整段删除，改为在
            // useBoard 里用 hitsInteractiveZone 把「牌/按钮那一带」整体吞掉（见 UnoTableRay）。
            // 需求：摸到的那张牌必须「突出」并且**锁住**——玩家一眼就知道摸到的是哪张，
            // 而且高亮不会被准星扫过别的牌带走；只有「出牌 / 跳过」才解除（见 selectLocked）。
            selected[seat] = drawn;
            selectLocked[seat] = true;
            // 本人先看到「摸到了什么」，其他人看「XX 摸了一张牌」：同 tick 两条会在本人那里
            // 互相顶掉，所以群发时跳过本人、本人在最后收到更具体的私聊动作栏。
            holdEventTip();
            broadcastTipExcept(seat, "§7" + seatName(seat) + " §7摸了一张牌。");
            p.sendTip("§7你摸到了 §e" + UnoDeck.name(drawn) + "§7：可以「出牌」或「跳过」。");
        }
        afterAction();
    }

    /** 「跳过」：摸牌后不出。 */
    private void doSkip(Player p, int seat) {
        if (seat != actorSeat()) {
            p.sendTip("§e还没轮到你。");
            return;
        }
        String err = game.pass(gameOfSeat[seat]);
        if (err != null) {
            p.sendTip("§c" + err);
            return;
        }
        selectLocked[seat] = false;
        selected[seat] = -1;
        broadcastEventTip("§7" + seatName(seat) + " §7摸牌后不出。");
        afterAction();
    }

    /** 「提示」：自动选中一张可出的牌（多次点击循环）。 */
    private void doHint(Player p, int seat) {
        if (seat != actorSeat()) {
            p.sendTip("§e还没轮到你。");
            return;
        }
        int g = gameOfSeat[seat];
        List<Integer> playable = game.playableCards(g);
        if (playable.isEmpty()) {
            p.sendTip("§7没有能出的牌，请「抽牌」。");
            return;
        }
        int pick = playable.get(hintIndex[seat] % playable.size());
        hintIndex[seat]++;
        selected[seat] = pick;
        p.sendTip("§7已选中 §e" + UnoDeck.name(pick) + "§7，点「出牌」打出。");
        flush();
    }

    /**
     * 「下一局」（世界内按钮）：确认续局；所有在线真人都确认后自动开下一局。
     * 与表单里的「下一局」同走 {@link #voteNext(Player)}（需求 2026-10-16）。
     */
    private void doNext(Player p) {
        String err = voteNext(p);
        if (err != null) {
            p.sendTip("§c" + err);
        }
    }

    // ==================================================================
    // 菜单展示
    // ==================================================================

    /** 表单正文：座位、倍数、桌主。 */
    public String statusText(Player viewer) {
        StringBuilder sb = new StringBuilder();
        // 用户 2026-10-16 指定的精简格式：只保留座位状态 / 倍数 / 桌主，
        // 其余（免费局说明、设定者、余额、开局要求提示）一律去掉。
        sb.append("§l座位状态\n");
        String[] dir = {"东侧", "南侧", "西侧", "北侧"};
        for (int s = 0; s < 4; s++) {
            sb.append("§rP").append(s + 1).append(": ")
                    .append(seatOccupantLabel(s)).append(" - ").append(dir[s]).append('\n');
        }
        // 去掉前导空格，与「座位状态」对齐（用户 2026-10-16）
        sb.append("§l当前倍数：§r").append(fmt(getCoefficient())).append('\n');
        sb.append("§l当前桌主：§r");
        if (firstJoiner != null) {
            sb.append(manager.nameOf(firstJoiner));
        } else {
            sb.append("无");
        }
        if (phase == TablePhase.SETTLED) {
            // 「下一局」全员确认进度（需求 2026-10-16）：所有在线真人都点过才自动开下一局
            sb.append('\n').append("§l下一局确认：§r")
                    .append(nextVoteDone()).append('/').append(nextVoteNeeded());
            StringBuilder wait = new StringBuilder();
            for (int s = 0; s < 4; s++) {
                if (awaitingNextVote(s) && !nextVote[s]) {
                    if (wait.length() > 0) {
                        wait.append('、');
                    }
                    wait.append(seatOccupantLabel(s));
                }
            }
            if (wait.length() > 0) {
                sb.append("§7（等待 ").append(wait).append(" 确认）");
            } else if (nextVoteNeeded() == 0) {
                sb.append("§7（暂无在线真人）");
            }
        }
        return sb.toString();
    }

    /** 某座位占用者名（供新表单「P1: 玩家名 - 东侧」）：空闲→「空闲」，否则 = 玩家名 / 机器人名。 */
    public String seatOccupantLabel(int seat) {
        if (humans[seat] == null && bots[seat] < 0) {
            return "空闲";
        }
        return names[seat] != null ? names[seat] : "机器人";
    }

    /** 某座位的展示标签（供菜单）。 */
    public String seatSlotLabel(int seat) {
        if (bots[seat] >= 0) {
            return "§e" + seatName(seat) + " §8(人机)";
        }
        if (humans[seat] == null) {
            return "§8空位";
        }
        if (online[seat] == null) {
            return "§f" + seatName(seat) + " §8(离线·托管)";
        }
        if (trusted[seat]) {
            return "§f" + seatName(seat) + " §8(托管)";
        }
        return "§f" + seatName(seat);
    }

    /**
     * WAITING 阶段「开始游戏 / 下一局」的按钮名（需求 2026-10-17）。
     * <p>只有「上一局完整打完、且期间没人腾出座位（离席 / 手动清除机器人都算退出）」
     * 才叫「下一局」，否则一律叫「开始游戏」。
     * <p>局数 {@link #roundNumber} 与它无关，2026-10-18 起也不再把局数写进任何提示里。
     */
    public String waitingButtonLabel() {
        return roundNumber > 0 && !seatVacated ? "下一局" : "开始游戏";
    }

    /** 阶段中文名。 */
    public String stageLabel() {
        return switch (phase) {
            case WAITING -> occupiedSeats() >= 2
                    ? "等待点「" + waitingButtonLabel() + "」"
                    : "等待入座";
            case DEALER_DRAW -> "定庄中（四家同时抽牌比大小，展示 2 秒）";
            case PLAYING -> {
                int a = actorSeat();
                yield game != null && game.isStartColorPending()
                        ? "等待 " + seatName(a) + " 选起始颜色"
                        : "对局中，轮到 " + seatName(a)
                        + "（剩余牌堆 " + game.drawPileSize() + " 张）";
            }
            case SETTLED -> "本局结束，等待所有在线真人点「下一局」（全部确认后自动续局）";
        };
    }
}
