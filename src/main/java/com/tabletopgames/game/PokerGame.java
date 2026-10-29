package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.player.PlayerTeleportEvent;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.AnimateEntityPacket;
import com.tabletopgames.ai.AiThinkDispatcher;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.economy.EconomyHook;
import com.tabletopgames.entity.DdzTableEntity;
import com.tabletopgames.game.poker.PokerBotAi;
import com.tabletopgames.game.poker.PokerCards;
import com.tabletopgames.game.poker.PokerHandEval;
import com.tabletopgames.game.poker.PokerPot;
import com.tabletopgames.game.poker.PokerRound;
import com.tabletopgames.item.LocatorService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 德州扑克单桌运行时（复用 {@code tabletopgame:ddz_table} 实体，模式位 = 1）。
 *
 * <h2>经济模型（2026-10-25 用户定）</h2>
 * <ul>
 *   <li><b>每局筹码固定</b>：开局每家 {@code minEntry}（最低入场，默认 1000），局间不累积；</li>
 *   <li><b>下注即时扣金币</b>：盲注 / 跟注 / 加注 / 全下每一步都从余额扣，
 *       底池 = 本局扣款总和；结算只做「底池 − 抽水 → 赢家」；</li>
 *   <li>免费场同一套数值，但不碰 EconomyAPI。</li>
 * </ul>
 *
 * <h2>渲染</h2>
 * 牌桌属性由 {@link #sync()} 一次性打包写完（{@code beginBatch/endBatch}），
 * 客户端 {@code animation.tabletopgame.poker.layout} 解码后摆牌、摆按钮、摆筹码。
 * 视角动画按座位逐人发 {@code viewer_pN}，旁观者发 {@code hidden}。
 */
public final class PokerGame {

    public static final int SEATS = 4;

    /**
     * 座位方向：**模型局部**向量（从桌心指向该座位）。
     *
     * <p>★ 三处必须严格一致：本表、{@code tools/poker/build_model.py} 的
     * {@code SEAT_BASIS} 的 N 分量、{@code pre_animation} 的 {@code v.knx/v.knz}。
     * 改一处必须三处一起改，否则「看到的牌」和「算出来的站位」不是同一侧。</p>
     *
     * <p>★ 局部 z 与世界 z 反号（实体 yaw=0），所以世界站位必须写
     * {@code z = center.z - OUT_Z·R} —— 写成 {@code +} 会让南北两位整体对调，
     * 而手牌仍渲染在模型那一侧 ⇒ <b>自己的牌全跑到对面</b>（UNO/斗地主真机踩过）。</p>
     */
    public static final int[] OUT_X = {0, 1, 0, -1};
    public static final int[] OUT_Z = {-1, 0, 1, 0};

    // ---------------- 入座归位（同 UNO/斗地主：传送到站位 + 转向桌心） ----------------

    /** 站位半径：往桌外退 3.0 格，站桌沿外看牌，不挡手牌也不挡按钮。 */
    private static final double SEAT_STAND_RADIUS = 3.0;
    /** 站位视线看向桌心上方 0.6 格（手牌 / 公牌区域中心）。 */
    private static final double SEAT_FACE_TARGET_Y = 0.6;
    /** 站位被方块挡住时向上找空位的最大格数。 */
    private static final int SEAT_SEEK_UP = 3;

    private static final Map<String, PokerGame> ALL = new LinkedHashMap<>();
    private static final Random RND = new Random();

    private static final int VIEWER_RANGE = 40;
    private static final int SPECTATOR_RANGE = 32;

    public enum Phase {
        WAITING, PREFLOP, FLOP, TURN, RIVER, SHOWDOWN, SETTLED;

        public int code() {
            return ordinal();
        }
    }

    private final TabletopGamesManager gameManager;
    private final Level level;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final int rotation;
    private final String key;
    private final Vector3 center;

    private DdzTableEntity entity;

    private final Player[] players = new Player[SEATS];
    private final String[] names = new String[SEATS];
    private final boolean[] bot = new boolean[SEATS];
    private final int[] botLevel = new int[SEATS];
    private final boolean[] offline = new boolean[SEATS];
    /** 每座位是否托管（玩家在座但交给系统代打）。照斗地主 {@code trusted[]}。 */
    private final boolean[] trusted = new boolean[SEATS];

    private PokerRound round;
    private final int[][] hole = new int[SEATS][2];
    private final int[] board = new int[5];
    private int boardCount;
    private int[] deck = new int[0];
    private int deckPos;

    private Phase phase = Phase.WAITING;
    private int dealer;
    private int handNo;
    private boolean showdown;
    private int pot;
    private int lastWinner = -1;

    private TabletopGamesConfig.PokerTier tier = TabletopGamesConfig.PokerTier.FREE;
    private int minEntry;

    /**
     * 桌主：第一个入座的人。只有他能开始牌局 / 加人机 / 改场次，
     * 免得路人进来就把人机加满或者直接开局。
     * 他离开座位就顺位交给下一个真人；全走了就置空，下一个入座的人接管。
     */
    private UUID ownerId;
    private String ownerName;

    private long turnDeadline;
    private long raiseDeadline;
    private boolean raiseOpen;
    private long botAt;

    private boolean destroyed;
    private int viewerRound;
    private final UUID[] viewerSentTo = new UUID[SEATS];
    private boolean forceViewer = true;

    /** 每个座位的准星值：0 = 没指着按钮，{@code HOVER_BUTTON_BASE + 槽号}（56..60）= 指着某根。 */
    private final int[] hoverVal = new int[SEATS];
    /** 4 个座位各 6 位、打包后的 {@code playing_card_hover}（改前一直写 0，所以从没高亮过）。 */
    private int hoverPacked;

    /**
     * 结算期「下一局」投票（按座位，可取消）。
     *
     * <p>2026-10-26 用户要求：不再「结算完自动开下一局」，改成和斗地主 / UNO 一样
     * —— 结算页停住，在座的人各自点「下一局」，<b>全员确认才开</b>。</p>
     */
    private final boolean[] nextVote = new boolean[SEATS];

    /**
     * 本局开局之后有没有人离席 / 断线（与 UNO 的 {@code seatVacated} 同口径）。
     *
     * <p>一旦有人走，续局链就断了：这时不给「下一局」，只能等坐齐后由桌主点「开始游戏」。</p>
     */
    private boolean seatVacated;

    private PokerGame(TabletopGamesManager gm, Level level, int bx, int by, int bz, int rotation) {
        this.gameManager = gm;
        this.level = level;
        this.baseX = bx;
        this.baseY = by;
        this.baseZ = bz;
        this.rotation = rotation & 3;
        this.key = bx + "," + by + "," + bz + "," + this.rotation;
        this.center = new Vector3(bx + 1.5, by + 0.9375, bz + 1.5);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public static PokerGame create(TabletopGamesManager gm, Level level, int bx, int by, int bz, int rotation) {
        PokerGame g = new PokerGame(gm, level, bx, by, bz, rotation);
        g.minEntry = gm == null ? 1000 : gm.pokerConfig().normalizedMinEntry();
        g.spawnEntity();
        ALL.put(g.key, g);
        g.sync();
        return g;
    }

    public static Collection<PokerGame> all() {
        return ALL.values();
    }

    public static PokerGame of(Player p) {
        if (p == null) {
            return null;
        }
        for (PokerGame g : ALL.values()) {
            if (g.seatOf(p.getUniqueId()) >= 0) {
                return g;
            }
        }
        return null;
    }

    public static void shutdownAll() {
        for (PokerGame g : new ArrayList<>(ALL.values())) {
            g.detach();
        }
        ALL.clear();
    }

    private void spawnEntity() {
        Entity created = Entity.createEntity(DdzTableEntity.IDENTIFIER,
                Position.fromObject(new Vector3(center.x, center.y, center.z), level));
        if (!(created instanceof DdzTableEntity e)) {
            Server.getInstance().getLogger().error("德州扑克牌桌实体创建失败：" + key);
            return;
        }
        this.entity = e;
        e.setTableMode(1);
        e.setPokerTable(this);
        // 先把模式位写进属性再 spawn：否则首帧客户端还在用斗地主几何，
        // 52 张牌会全部叠在桌心闪一下。
        e.setPokerTableValue(1 << 11);
        e.spawnToAll();
    }

    /** 拆桌：清实体 + 清 3x3 方块。 */
    public void destroy() {
        destroyed = true;
        com.tabletopgames.board.PokerTableBlocks.clear(level, baseX, baseY, baseZ);
        detach();
        ALL.remove(key);
    }

    private void detach() {
        if (entity != null && !entity.isClosed()) {
            entity.close();
        }
        entity = null;
    }

    public boolean contains(int x, int y, int z) {
        return y == baseY
                && x >= baseX && x < baseX + 3 && z >= baseZ && z < baseZ + 3;
    }

    public boolean inUseRange(Player p) {
        return p.getLevel() == level && p.distanceSquared(center) <= VIEWER_RANGE * VIEWER_RANGE;
    }

    // ------------------------------------------------------------------
    // 座位
    // ------------------------------------------------------------------

    public int seatOf(UUID id) {
        for (int i = 0; i < SEATS; i++) {
            if (players[i] != null && players[i].getUniqueId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public int seatOf(Player p) {
        return p == null ? -1 : seatOf(p.getUniqueId());
    }

    public boolean occupied(int seat) {
        return seat >= 0 && seat < SEATS && (players[seat] != null || bot[seat]);
    }

    public int occupiedSeats() {
        int n = 0;
        for (int i = 0; i < SEATS; i++) {
            if (occupied(i)) {
                n++;
            }
        }
        return n;
    }

    public int humanSeats() {
        int n = 0;
        for (int i = 0; i < SEATS; i++) {
            if (players[i] != null && !bot[i]) {
                n++;
            }
        }
        return n;
    }

    public boolean hasBot() {
        // ★ 人机不占 Player 槽位（players[i] 恒为 null），只看 bot 标记。
        for (int i = 0; i < SEATS; i++) {
            if (bot[i]) {
                return true;
            }
        }
        return false;
    }

    /** 该座位是否托管（玩家在座但交给系统代打）。照斗地主 {@code isTrusted}。 */
    public boolean isTrusted(int seat) {
        return seat >= 0 && seat < SEATS && trusted[seat];
    }

    /** 切换<b>自己</b>座位的托管（菜单入口）。照斗地主 {@code toggleTrust}。 */
    public String toggleTrust(Player p) {
        int seat = seatOf(p);
        if (seat < 0) {
            return "§c你还没入座。";
        }
        if (!isPlaying() && phase != Phase.SETTLED) {
            return "§c等待期没有可托管的东西。";
        }
        trusted[seat] = !trusted[seat];
        if (trusted[seat]) {
            // 开托管时如果是自己的回合，立刻补 botAt（斗地主 1209 注释同款问题）
            if (round != null && round.actor == seat && botAt == 0) {
                botAt = System.currentTimeMillis() + 700 + RND.nextInt(700);
            }
            p.sendTip("§a已开启托管（系统代打）。");
        } else {
            p.sendTip("§7已取消托管。");
        }
        sync();
        return null;
    }

    /** 结算时自动取消托管（UNO/斗地主 {@code clearTrustOnSettle} 同款）。 */
    private void clearTrustOnSettle() {
        for (int s = 0; s < SEATS; s++) {
            if (trusted[s]) {
                trusted[s] = false;
                Player p = players[s];
                if (p != null && p.isOnline()) {
                    p.sendTip("§7已自动取消托管。");
                }
            }
        }
    }

    public boolean isPlaying() {
        return phase != Phase.WAITING && phase != Phase.SETTLED;
    }

    public boolean sit(Player p, int seat) {
        if (!isSeatFree(seat)) {
            return false;
        }
        if (isPlaying()) {
            p.sendTip("§c牌局进行中，等这一局结束再入座。");
            return false;
        }
        if (tier == TabletopGamesConfig.PokerTier.NEWBIE
                && EconomyHook.balance(p.getUniqueId()) < minEntry) {
            p.sendTip("§c新手场需要至少 " + minEntry + " 金币才能入座。");
            return false;
        }
        players[seat] = p;
        names[seat] = p.getName();
        bot[seat] = false;
        offline[seat] = false;
        // 结算页有人入座 = 人事变动：清掉已有的投票，改由桌主「开始游戏」重开。
        // 不清的话新人只要不点「下一局」，全桌就永远开不了（他自己也可能压根没看到提示）。
        if (phase == Phase.SETTLED) {
            seatVacated = true;
            java.util.Arrays.fill(nextVote, false);
        }
        // 入座归位：不传送的话「人站在北边、却坐了南位」，手牌会出现在桌子对面
        // （手牌是按座位钉死的绝对坐标，不会跟着玩家站的位置走）。
        String err = standAtSeat(p, seat);
        if (err != null) {
            players[seat] = null;
            names[seat] = null;
            p.sendTip("§c" + err);
            return false;
        }
        if (ownerId == null) {
            ownerId = p.getUniqueId();
            ownerName = p.getName();
            p.sendTip("§a你成为本桌桌主（可以开始牌局 / 加人机 / 改场次）。");
        }
        forceViewer = true;
        p.sendTip("§a已坐到" + seatLabel(seat) + "位。");
        // 入座即发定位器（2026-10-27）：手牌 / 桌面按钮都悬空在台面上，只有「手持定位器
        // 右键」这条链路客户端才会稳定上报（见 LocatorItem / LocatorService）。
        LocatorService.give(p);
        sync();
        return true;
    }

    /**
     * 入座归位：摆到座位外侧的站位点并转向桌心（与 UNO / 斗地主同一套）。
     *
     * <p>★ 坐标系铁律：{@code z = center.z - OUT_Z·R}（局部 z 与世界 z 反号）。
     * 写成 {@code +} 会让南北两位整体对调，而手牌仍然渲染在模型指定的那一侧
     * ⇒ 玩家自己的牌全跑到对面（UNO 真机踩过）。</p>
     *
     * @return null = 成功，否则是被方块挡住之类的原因
     */
    public String standAtSeat(Player p, int seat) {
        if (p == null || seat < 0 || seat >= SEATS) {
            return "座位不合法。";
        }
        double x = center.x + OUT_X[seat] * SEAT_STAND_RADIUS;
        double z = center.z - OUT_Z[seat] * SEAT_STAND_RADIUS;
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

    public void leave(Player p) {
        int seat = seatOf(p);
        if (seat < 0) {
            return;
        }
        if (isPlaying() && round != null && !round.folded[seat]) {
            if (seat == round.actor) {
                apply(seat, PokerRound.Action.FOLD, 0);
            } else {
                round.act(seat, PokerRound.Action.FOLD, 0);
                if (round.streetDone()) {
                    advanceStreet();
                }
            }
        }
        // 本局还没结束就走人 ⇒ 续局链断了：结算页不再给「下一局」，等桌主重开
        if (phase != Phase.WAITING) {
            seatVacated = true;
        }
        nextVote[seat] = false;
        players[seat] = null;
        names[seat] = null;
        bot[seat] = false;
        offline[seat] = false;
        trusted[seat] = false;
        viewerSentTo[seat] = null;
        forceViewer = true;
        handoverOwner();
        // ★ 2026-10-26 用户 #3：免费局 1 真人 + 机器人，真人一走立马清空座位
        if (tier == TabletopGamesConfig.PokerTier.FREE && humanSeats() == 0 && hasBot()) {
            clearBotsNow("真人玩家已退出，清理人机座位。");
        }
        // 离座收定位器（2026-10-27）：此处 players[seat] 已清空，判据看到的是「已不在座」。
        LocatorService.release(p);
        sync();
    }

    public boolean addBot(int level) {
        if (isPlaying() || humanSeats() < minHumans()) {
            return false;
        }
        // ★ 必须用 occupied()：人机不写 players[]，只靠 players[i] == null 找空位
        //   会让每次「加人机」都抢到自己刚占的那个座位（第二个人机覆盖第一个人机，
        //   occupiedSeats() 永远停在 2 ⇒ canStart() 永远 false ⇒ 开不了局）。
        for (int i = 0; i < SEATS; i++) {
            if (!occupied(i)) {
                int idx = botCount() + 1;   // 先算序号：置位后再数会把自己算进去
                bot[i] = true;
                botLevel[i] = level;
                names[i] = "机器人" + idx;
                sync();
                return true;
            }
        }
        return false;
    }

    private int botCount() {
        int n = 0;
        for (int i = 0; i < SEATS; i++) {
            if (bot[i]) {
                n++;
            }
        }
        return n;
    }

    public void removeBots() {
        if (isPlaying()) {
            return;
        }
        // 结算页清人机 = 人事变动，续局链断（与斗地主「清除人机也算有人退出」同口径）
        if (phase == Phase.SETTLED) {
            seatVacated = true;
        }
        for (int i = 0; i < SEATS; i++) {
            if (bot[i]) {
                bot[i] = false;
                names[i] = null;
            }
        }
        sync();
    }

    /**
     * 立即清空人机座位并广播（免费局真人退出时用，2026-10-26 用户 #3）。
     * 与 {@link #removeBots()} 不同：不清 {@code seatVacated}（离人已经设了）。
     */
    private void clearBotsNow(String reason) {
        for (int i = 0; i < SEATS; i++) {
            if (bot[i]) {
                bot[i] = false;
                names[i] = null;
            }
        }
        // 回到等待态（对局中断 → 清桌后直接等下一个人）
        if (phase != Phase.WAITING) {
            round = null;
            phase = Phase.WAITING;
            showdown = false;
        }
        if (reason != null) {
            broadcast("§e[德州] §7" + reason);
        }
    }

    public static String seatLabel(int seat) {
        return switch (seat) {
            case 0 -> "南";
            case 1 -> "东";
            case 2 -> "北";
            default -> "西";
        };
    }

    // ------------------------------------------------------------------
    // 开局
    // ------------------------------------------------------------------

    public boolean canStart() {
        if (isPlaying() || occupiedSeats() < 3) {
            return false;
        }
        if (humanSeats() < minHumans()) {
            return false;
        }
        for (int i = 0; i < SEATS; i++) {
            if (players[i] != null && !bot[i] && tier == TabletopGamesConfig.PokerTier.NEWBIE
                    && EconomyHook.balance(players[i].getUniqueId()) < minEntry) {
                return false;
            }
        }
        return true;
    }

    public void start() {
        if (!canStart()) {
            return;
        }
        // 续局时轮转庄家（第一局固定 seat0 起手）；原来的自动续局在 afterSettle 里做，
        // 改成手动「下一局」后统一挪到这里，免得两条路径各转一次。
        if (handNo > 0) {
            dealer = (dealer + 1) % SEATS;
        }
        java.util.Arrays.fill(nextVote, false);
        seatVacated = false;
        java.util.Arrays.fill(trusted, false);
        handNo++;
        round = new PokerRound(SEATS, minEntry);
        // 空座位必须先标成「已弃牌」：alive()/streetDone()/nextActor() 只认 folded，
        // 不标的话空位永远「没行动、下注额不齐」，一条街永远结束不了。
        for (int i = 0; i < SEATS; i++) {
            if (!occupied(i)) {
                round.folded[i] = true;
            }
        }
        pot = 0;
        boardCount = 0;
        showdown = false;
        lastWinner = -1;
        for (int i = 0; i < SEATS; i++) {
            hole[i][0] = -1;
            hole[i][1] = -1;
        }
        java.util.Arrays.fill(board, -1);

        deck = PokerCards.shuffled(RND);
        deckPos = 0;
        for (int k = 0; k < 2; k++) {
            for (int s = 0; s < SEATS; s++) {
                if (occupied(s)) {
                    hole[s][k] = deck[deckPos++];
                }
            }
        }

        // ★ 小盲、大盲必须**按座位顺序**取相邻两个活人：
        //   先算 (dealer+1)/(dealer+2) 再各自 nextOccupied，在有空座时会撞成同一个人
        //   （dealer=2、seat3 空 → sb=bb=seat0），大小盲合并，底池少一半。
        int sb = nextOccupied(dealer + 1);
        int bb = nextOccupied(sb + 1);
        int bbAmount = gameManager == null ? 10 : gameManager.pokerConfig().getBigBlind();
        int sbAmount = Math.max(1, bbAmount / 2);
        round.postBlind(sb, sbAmount);
        round.postBlind(bb, bbAmount);
        charge(sb, Math.min(sbAmount, minEntry));
        charge(bb, Math.min(bbAmount, minEntry));
        round.currentBet = bbAmount;
        round.minRaise = bbAmount;
        pot = round.pot;

        phase = Phase.PREFLOP;
        int first = nextOccupied(bb + 1);
        round.actor = first;
        beginTurn();
        broadcast("§e[德州] §f第 " + handNo + " 局开始，庄家 " + nameOf(dealer)
                + "，盲注 " + sbAmount + "/" + bbAmount);
        sync();
    }

    private int nextOccupied(int from) {
        for (int k = 0; k < SEATS; k++) {
            int s = (from + k) % SEATS;
            if (occupied(s) && !round.folded[s]) {
                return s;
            }
        }
        return from % SEATS;
    }

    // ------------------------------------------------------------------
    // 行动
    // ------------------------------------------------------------------

    private void beginTurn() {
        if (round.alive() <= 1) {
            finish();
            return;
        }
        if (round.actionable() <= 1 && round.streetDone()) {
            advanceStreet();
            return;
        }
        int a = round.actor;
        if (round.folded[a] || round.allin[a]) {
            int n = round.nextActor(a);
            if (n < 0) {
                advanceStreet();
                return;
            }
            round.actor = n;
        }
        turnDeadline = System.currentTimeMillis() + turnSeconds() * 1000L;
        raiseOpen = false;
        botAt = (bot[round.actor] || trusted[round.actor])
                ? System.currentTimeMillis() + 700 + RND.nextInt(700) : 0;
        sync();
    }

    private void thinkBot() {
        final int seat = round.actor;
        final int token = handNo * 100 + seat;
        final int level = botLevel[seat] == PokerBotAi.HARD ? PokerBotAi.HARD : PokerBotAi.EASY;
        final int[] myHole = {hole[seat][0], hole[seat][1]};
        final int[] boardCopy = board.clone();
        final int bc = boardCount;
        final int p = round.pot;
        final int call = round.toCall(seat);
        final int cb = round.currentBet;
        final int stack = round.stack[seat];
        final int alive = round.alive();

        AiThinkDispatcher.light(plugin(), () -> PokerBotAi.decide(level, myHole, boardCopy, bc,
                        p, call, cb, stack, alive, new Random()),
                move -> {
                    if (move == null || phase == Phase.WAITING || phase == Phase.SETTLED) {
                        return;
                    }
                    if (handNo * 100 + round.actor != token || (!bot[seat] && !trusted[seat])) {
                        return;
                    }
                    apply(seat, move.action(), move.amount());
                });
    }

    /** 玩家点桌面按钮。0..4 = 动作按钮；5 = 结算「下一局」；6 = 结算「退出牌桌」。 */
    public void clickButton(Player p, int index) {
        // ★ 结算按钮（2026-10-26 用户 #2）：不走 action 通道
        if (index == 5) {
            int seat = seatOf(p);
            if (seat >= 0 && phase == Phase.SETTLED && !seatVacated) {
                String err = voteNext(p);
                if (err != null) {
                    p.sendTip(err.startsWith("§") ? err : "§c" + err);
                }
            }
            return;
        }
        if (index == 6) {
            leave(p);
            return;
        }
        int seat = seatOf(p);
        if (seat < 0 || !isPlaying() || round == null || seat != round.actor) {
            return;
        }
        if (raiseOpen) {
            // 表单开着时，点桌面相当于重新打开表单
            PokerMenu.openRaise(p, this);
            return;
        }
        switch (index) {
            case 0 -> apply(seat, PokerRound.Action.FOLD, 0);
            case 1 -> {
                if (round.canCheck(seat)) {
                    apply(seat, PokerRound.Action.CHECK, 0);
                }
            }
            case 2 -> {
                if (!round.canCheck(seat)) {
                    apply(seat, PokerRound.Action.CALL, 0);
                }
            }
            case 3 -> {
                if (round.stack[seat] > round.toCall(seat)) {
                    openRaise(seat);
                }
            }
            case 4 -> apply(seat, PokerRound.Action.ALLIN, 0);
            default -> {
            }
        }
    }

    private void openRaise(int seat) {
        Player p = players[seat];
        if (p == null) {
            return;
        }
        raiseOpen = true;
        raiseDeadline = System.currentTimeMillis() + secondTurnSeconds() * 1000L;
        turnDeadline = raiseDeadline;
        PokerMenu.openRaise(p, this);
    }

    /** 玩家在滑块表单里提交。 */
    public void submitRaise(Player p, int target) {
        int seat = seatOf(p);
        if (seat < 0 || !isPlaying() || round == null || seat != round.actor) {
            return;
        }
        raiseOpen = false;
        int lo = round.minRaiseTo(seat);
        int hi = round.maxRaiseTo(seat);
        int v = Math.max(lo, Math.min(hi, target));
        if (v >= hi) {
            apply(seat, PokerRound.Action.ALLIN, 0);
        } else {
            apply(seat, PokerRound.Action.RAISE, v);
        }
    }

    /** 关闭加注表单（不提交）。 */
    public void cancelRaise(Player p) {
        int seat = seatOf(p);
        if (seat >= 0 && round != null && seat == round.actor) {
            raiseOpen = false;
        }
    }

    public void apply(int seat, PokerRound.Action action, int amount) {
        if (round == null || phase == Phase.WAITING || phase == Phase.SETTLED) {
            return;
        }
        if (seat != round.actor) {
            return;
        }
        if (action == PokerRound.Action.RAISE) {
            amount = Math.max(round.minRaiseTo(seat), Math.min(round.maxRaiseTo(seat), amount));
        }
        int pay = switch (action) {
            case FOLD, CHECK -> 0;
            case CALL -> Math.min(round.toCall(seat), round.stack[seat]);
            case RAISE -> Math.min(amount - round.bet[seat], round.stack[seat]);
            case ALLIN -> round.stack[seat];
        };
        if (pay > 0 && !charge(seat, pay)) {
            action = PokerRound.Action.FOLD;
            amount = 0;
        }
        boolean ok = round.act(seat, action, amount);
        if (!ok) {
            return;
        }
        pot = round.pot;
        raiseOpen = false;
        // ★ 2026-10-26 用户要求：每一步行动都 broadcastTip，让全场清楚别人干了什么
        String actDesc = switch (action) {
            case FOLD -> "弃牌";
            case CHECK -> "过牌";
            case CALL -> "跟注 " + pay;
            case RAISE -> "加注到 " + amount;
            case ALLIN -> "全下 " + pay;
        };
        tipAll("§e[德州] §f" + nameOf(seat) + " §7" + actDesc
                + " §8| 底池 " + pot);
        if (round.alive() <= 1) {
            finish();
            return;
        }
        if (round.streetDone()) {
            advanceStreet();
            return;
        }
        int n = round.nextActor(seat);
        if (n < 0) {
            advanceStreet();
            return;
        }
        round.actor = n;
        beginTurn();
    }

    private void advanceStreet() {
        if (round.alive() <= 1) {
            finish();
            return;
        }
        // 所有人都全下 → 直接发完剩下的公共牌
        if (round.actionable() <= 1) {
            dealToRiver();
            finish();
            return;
        }
        switch (phase) {
            case PREFLOP -> {
                board[0] = deck[deckPos++];
                board[1] = deck[deckPos++];
                board[2] = deck[deckPos++];
                boardCount = 3;
                phase = Phase.FLOP;
            }
            case FLOP -> {
                board[3] = deck[deckPos++];
                boardCount = 4;
                phase = Phase.TURN;
            }
            case TURN -> {
                board[4] = deck[deckPos++];
                boardCount = 5;
                phase = Phase.RIVER;
            }
            default -> {
                finish();
                return;
            }
        }
        round.startStreet(dealer);
        round.currentBet = 0;
        round.minRaise = gameManager == null ? 10 : gameManager.pokerConfig().getBigBlind();
        // 翻牌后从庄家左手第一个还能行动的人开始
        int first = round.nextActor(dealer);
        round.actor = first < 0 ? 0 : first;
        beginTurn();
        sync();
    }

    private void dealToRiver() {
        while (boardCount < 5) {
            board[boardCount++] = deck[deckPos++];
        }
        phase = Phase.RIVER;
    }

    // ------------------------------------------------------------------
    // 结算
    // ------------------------------------------------------------------

    private void finish() {
        if (round == null) {
            return;
        }
        showdown = round.alive() > 1;
        phase = showdown ? Phase.SHOWDOWN : Phase.SETTLED;

        List<PokerPot.Pot> pots = PokerPot.build(round.total, round.folded);
        int[] payout = new int[SEATS];
        int totalRake = 0;

        int alive = 0;
        int aliveSeat = -1;
        for (int i = 0; i < SEATS; i++) {
            if (occupied(i) && !round.folded[i]) {
                alive++;
                aliveSeat = i;
            }
        }
        if (alive <= 1) {
            int rake = rakeOf(round.pot);
            totalRake = rake;
            int win = round.pot - rake;
            if (aliveSeat >= 0) {
                payout[aliveSeat] = win;
                payWinner(aliveSeat, win);
                lastWinner = aliveSeat;
                broadcast("§e[德州] §f" + nameOf(aliveSeat) + " §a赢得底池 " + win
                        + " §7（其他人全部弃牌）" + (rake > 0 ? " §7抽水 " + rake : ""));
            }
            enterSettled();
            sync();
            return;
        }

        for (PokerPot.Pot potPart : pots) {
            if (potPart.seats().length == 0) {
                continue;
            }
            // 每池找最好的手牌
            long best = -1;
            List<Integer> winners = new ArrayList<>();
            for (int s : potPart.seats()) {
                long sc = handScore(s);
                if (sc > best) {
                    best = sc;
                    winners.clear();
                    winners.add(s);
                } else if (sc == best) {
                    winners.add(s);
                }
            }
            if (winners.isEmpty()) {
                continue;
            }
            int rake = rakeOf(potPart.amount());
            totalRake += rake;
            int share = (potPart.amount() - rake) / winners.size();
            int rem = (potPart.amount() - rake) % winners.size();
            for (int i = 0; i < winners.size(); i++) {
                payout[winners.get(i)] += share + (i == 0 ? rem : 0);
            }
        }

        int[] detail = new int[SEATS];
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < SEATS; s++) {
            if (payout[s] <= 0) {
                continue;
            }
            detail[s] = payout[s];
            payWinner(s, payout[s]);
            if (lastWinner < 0) {
                lastWinner = s;
            }
            if (sb.length() > 0) {
                sb.append("§7 / ");
            }
            sb.append("§f").append(nameOf(s)).append(" §a+").append(payout[s]);
        }
        broadcast("§e[德州] §f" + sb + (totalRake > 0 ? " §7(抽水 " + totalRake + ")" : ""));

        // 逐家播报牌型（人机也播；弃牌也播）—— 2026-10-26 用户指定格式与配色
        // ★ 2026-10-26 补：弃牌的玩家**也要算出他能连成 5 张的最大牌**并给出牌型叫法，
        //   只在末尾加 §7（弃牌）——「玩家名 | 手牌 ♥5♠6 | ♥5♠6♣7♦8♦9 - 9高顺子 （弃牌）」。
        //   公牌不足 5 张时（提前结束）算不出牌型，退化成只播手牌 + （弃牌）。
        for (int s = 0; s < SEATS; s++) {
            if (!occupied(s)) {
                continue;
            }
            boolean folded = round.folded[s];
            String txt = "§e[德州] §f" + nameOf(s) + " §f| §f手牌 "
                    + PokerCards.coloredCompact(hole[s], 2);
            PokerHandEval.Result r = bestOf(s);
            if (r != null) {
                txt += " §f| " + PokerCards.coloredCompact(r.cards(), 5)
                        + " §f- §f" + r.category();
            }
            if (folded) {
                txt += " §7（弃牌）";
            } else if (payout[s] > 0) {
                txt += " §a+" + payout[s];
            }
            broadcast(txt);
        }

        enterSettled();
        sync();
    }

    /**
     * 结算落地：<b>停在结算页</b>，不再自动开下一局。
     *
     * <p>2026-10-26 用户要求与斗地主 / UNO 对齐：牌局打完就停住，牌面留在桌上给人看，
     * 由在座的每个人在菜单里点「下一局」；<b>全员确认才开</b>。有人中途离席时不给续局，
     * 只提示等桌主重开（{@link #seatVacated}）。</p>
     */
    private void enterSettled() {
        phase = Phase.SETTLED;
        java.util.Arrays.fill(nextVote, false);
        clearTrustOnSettle();
        tipAll(seatVacated
                ? "§7本局座位有变动，不再续局：坐齐后由桌主点「开始游戏」。"
                : "§7点桌面，在菜单里点「下一局」继续（全员确认后开牌）。");
    }

    /** 给在座的真人发一条 tip（人机 / 离线跳过）。 */
    private void tipAll(String msg) {
        for (int s = 0; s < SEATS; s++) {
            Player p = players[s];
            if (p != null && p.isOnline()) {
                p.sendTip(msg);
            }
        }
    }

    // ------------------------------------------------------------------
    // 下一局投票
    // ------------------------------------------------------------------

    /** 结算页按钮文案（与斗地主 {@code waitingButtonLabel()} 同一判据）。 */
    public String waitingButtonLabel() {
        return handNo > 0 && !seatVacated ? "下一局" : "开始游戏";
    }

    /** 本局开局后有人离席（续局链已断）。 */
    public boolean hasSeatVacated() {
        return seatVacated;
    }

    public boolean hasVotedNext(int seat) {
        return seat >= 0 && seat < SEATS && nextVote[seat];
    }

    /** 需要投票的人数：在座的真人（人机不用投，它们随时开）。 */
    public int nextVoteNeeded() {
        int n = 0;
        for (int s = 0; s < SEATS; s++) {
            Player p = players[s];
            if (p != null && p.isOnline()) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    /** 已投票人数：只算「在座 + 在线」的真人，投完就掉线的不该把门槛顶住。 */
    public int nextVoteDone() {
        int n = 0;
        for (int s = 0; s < SEATS; s++) {
            Player p = players[s];
            if (nextVote[s] && p != null && p.isOnline()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 投「下一局」票（<b>可取消</b>，菜单上写「再点取消」）。
     *
     * @return null = 成功；否则是拒绝原因
     */
    public String voteNext(Player p) {
        int s = seatOf(p);
        if (s < 0) {
            return "你不在座位上。";
        }
        if (phase != Phase.SETTLED) {
            return "现在不是结算阶段。";
        }
        if (seatVacated) {
            return "本局座位有变动，不能续局：坐齐后由桌主点「开始游戏」。";
        }
        nextVote[s] = !nextVote[s];
        if (nextVote[s] && nextVoteDone() >= nextVoteNeeded()) {
            startNextHand();
        }
        return null;
    }

    /** 全员确认 → 直接开下一局；开不了（余额不足 / 人不够）就退回等待并说明。 */
    private void startNextHand() {
        java.util.Arrays.fill(nextVote, false);
        if (!canStart()) {
            phase = Phase.WAITING;
            round = null;
            showdown = false;
            sync();
            broadcast("§c无法续局（人数不足或余额不够门票）§7，请处理后点「开始游戏」。");
            return;
        }
        start();
    }

    private long handScore(int seat) {
        PokerHandEval.Result r = bestOf(seat);
        return r == null ? -1 : r.score();
    }

    private PokerHandEval.Result bestOf(int seat) {
        int[] all = new int[2 + boardCount];
        int n = 0;
        if (hole[seat][0] >= 0) {
            all[n++] = hole[seat][0];
        }
        if (hole[seat][1] >= 0) {
            all[n++] = hole[seat][1];
        }
        for (int i = 0; i < boardCount; i++) {
            all[n++] = board[i];
        }
        if (n < 5) {
            return null;
        }
        int[] trimmed = new int[n];
        System.arraycopy(all, 0, trimmed, 0, n);
        return PokerHandEval.best(trimmed, n);
    }

    /**
     * 全局配置改了之后同步「每局筹码」。
     *
     * <p>只在<b>空桌</b>时刷新 —— 有人坐着（或在打）就不动它的筹码，免得打到一半规则变了。
     * 场次（tier）是每张桌子自己的，不在这里改。</p>
     */
    public void refreshConfig() {
        if (destroyed || phase != Phase.WAITING || occupiedSeats() > 0) {
            return;
        }
        int next = gameManager == null ? 1000 : gameManager.pokerConfig().normalizedMinEntry();
        if (next != minEntry) {
            minEntry = next;
            sync();
        }
    }

    /** 当前阶段（菜单要据此决定给「开始游戏」还是「下一局」）。 */
    public Phase getPhase() {
        return phase;
    }

    /** 当前场次的抽水百分比（免费场恒 0）。 */
    public double cutPercent() {
        if (tier != TabletopGamesConfig.PokerTier.NEWBIE) {
            return 0;
        }
        return gameManager == null ? 1.0 : gameManager.pokerConfig().getCut();
    }

    private int rakeOf(int amount) {
        if (tier != TabletopGamesConfig.PokerTier.NEWBIE) {
            return 0;
        }
        double cut = gameManager == null ? 1.0 : gameManager.pokerConfig().getCut();
        return (int) Math.floor(amount * cut / 100.0);
    }

    private void payWinner(int seat, int amount) {
        if (tier == TabletopGamesConfig.PokerTier.FREE || amount <= 0) {
            return;
        }
        Player p = players[seat];
        if (p != null) {
            EconomyHook.add(p.getUniqueId(), amount);
        }
    }

    private boolean charge(int seat, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (tier == TabletopGamesConfig.PokerTier.FREE) {
            return true;
        }
        Player p = players[seat];
        if (p == null) {
            return true;            // 人机：不扣真钱
        }
        return EconomyHook.reduce(p.getUniqueId(), amount);
    }


    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    public void tick() {
        if (destroyed) {
            return;
        }
        if (entity == null || entity.isClosed()) {
            spawnEntity();
            sync();
        }
        // 结算页人走光了：别把牌一直摊在桌上，回到等待态（下一个坐下的人重新开始）
        if (phase == Phase.SETTLED && occupiedSeats() == 0) {
            phase = Phase.WAITING;
            round = null;
            showdown = false;
            java.util.Arrays.fill(nextVote, false);
            seatVacated = false;
            sync();
            return;
        }
        long now = System.currentTimeMillis();
        if (isPlaying() && round != null) {
            if (now >= turnDeadline) {
                int seat = round.actor;
                if (raiseOpen) {
                    // 加注表单开着 → 超时按最小加注（不够就全下）
                    raiseOpen = false;
                    int lo = round.minRaiseTo(seat);
                    if (lo >= round.maxRaiseTo(seat)) {
                        apply(seat, PokerRound.Action.ALLIN, 0);
                    } else {
                        apply(seat, PokerRound.Action.RAISE, lo);
                    }
                } else if (round.canCheck(seat)) {
                    apply(seat, PokerRound.Action.CHECK, 0);
                } else {
                    apply(seat, PokerRound.Action.FOLD, 0);
                }
            } else if ((bot[round.actor] || trusted[round.actor]) && botAt > 0 && now >= botAt) {
                botAt = 0;
                thinkBot();
            }
        }
        if (++viewerRound % 4 == 0) {
            refreshViewers();
        }
        // 准星高亮每轮都算：tick 是 250 ms 一次，再除以 4 会有一秒的延迟感。
        // 代价只是「在座真人」几条射线求交，且属性只在变化时才写。
        updateHover();
    }

    /**
     * 准星高亮：每 4 tick 对在座的真人各投一条射线，指着哪根按钮就把它点亮（绿色 + 放大 6%）。
     *
     * <p>编码照抄斗地主：{@code playing_card_hover} 每 6 位一个视角，值 {@code 56+槽号}
     * （{@link DdzTableEntity#HOVER_BUTTON_BASE}），0 = 没指着。客户端在 pre_animation 里按
     * {@code v.viewer_mode} 取自己那 6 位。只在<b>变化时</b>写属性，免得每 tick 都发一遍。</p>
     */
    private void updateHover() {
        if (entity == null || entity.isClosed()) {
            return;
        }
        int packed = 0;
        for (int s = SEATS - 1; s >= 0; s--) {
            Player p = players[s];
            int v = 0;
            if (p != null && p.isOnline()) {
                int slot = PokerTableRay.hitButton(p, this);
                if (slot >= 0) {
                    v = DdzTableEntity.HOVER_BUTTON_BASE + slot;
                }
            }
            hoverVal[s] = v;
            packed = (packed << 6) | (v & 0x3F);
        }
        if (packed != hoverPacked) {
            hoverPacked = packed;
            entity.setPokerHover(packed);
        }
    }

    private void refreshViewers() {
        if (entity == null || entity.isClosed()) {
            return;
        }
        boolean resend = forceViewer || viewerRound % 32 == 0;
        for (int s = 0; s < SEATS; s++) {
            Player p = players[s];
            if (p == null || !p.isOnline()) {
                viewerSentTo[s] = null;
                continue;
            }
            if (resend || !p.getUniqueId().equals(viewerSentTo[s])) {
                sendAnim("animation.tabletopgame.poker.viewer_p" + s, List.of(p));
                viewerSentTo[s] = p.getUniqueId();
            }
        }
        List<Player> fresh = null;
        for (Player p : level.getPlayers().values()) {
            if (p.distanceSquared(center) > SPECTATOR_RANGE * SPECTATOR_RANGE || seatOf(p) >= 0) {
                continue;
            }
            if (fresh == null) {
                fresh = new ArrayList<>(4);
            }
            fresh.add(p);
        }
        if (fresh != null && resend) {
            sendAnim("animation.tabletopgame.poker.hidden", fresh);
        }
        forceViewer = false;
    }

    private void sendAnim(String animation, Collection<Player> targets) {
        if (entity == null || entity.isClosed() || targets.isEmpty()) {
            return;
        }
        AnimateEntityPacket.Animation anim = AnimateEntityPacket.Animation.builder()
                .animation(animation)
                .controller("tabletopgame:poker_viewer")
                .stopExpression("0")
                .blendOutTime(0f)
                .build();
        Entity.playAnimationOnEntities(anim, List.<Entity>of(entity), targets);
    }

    // ------------------------------------------------------------------
    // 属性同步
    // ------------------------------------------------------------------

    public void sync() {
        if (entity == null || entity.isClosed()) {
            return;
        }
        int[] slot = new int[13];
        for (int s = 0; s < SEATS; s++) {
            if (round != null && !round.folded[s]) {
                slot[s * 2] = hole[s][0] >= 0 ? hole[s][0] + 1 : 0;
                slot[s * 2 + 1] = hole[s][1] >= 0 ? hole[s][1] + 1 : 0;
            }
        }
        for (int i = 0; i < 5; i++) {
            if (i < boardCount && board[i] >= 0) {
                slot[8 + i] = board[i] + 1;
            }
        }
        int[] sp = new int[5];
        for (int s = 0; s < 13; s++) {
            sp[s / 3] |= (slot[s] & 0x3F) << (6 * (s % 3));
        }

        int[] b = new int[SEATS];
        int foldMask = 0;
        int allinMask = 0;
        int actedMask = 0;
        if (round != null) {
            for (int s = 0; s < SEATS; s++) {
                b[s] = round.bet[s];
                foldMask |= (round.folded[s] ? 1 : 0) << s;
                allinMask |= (round.allin[s] ? 1 : 0) << s;
                actedMask |= (round.acted[s] ? 1 : 0) << s;
            }
        }

        entity.beginBatch();
        for (int i = 0; i < 5; i++) {
            entity.setPokerSlots(i, sp[i]);
        }
        entity.setPokerSlots(5, b[0] | (b[1] << 12));
        entity.setPokerSlots(6, b[2]);
        entity.setPokerSlots(7, 0);
        entity.setPokerSelected(0, (b[3] & 0xFFF) | (foldMask << 12));
        entity.setPokerSelected(1, (allinMask & 0xF) | ((boardCount & 0x7) << 4) | (actedMask << 7));
        entity.setPokerSelected(2, 0);
        entity.setPokerTableValue(1 << 11
                | (phase.code() & 0x7) << 12
                | (dealer & 0x3) << 15
                | ((round == null ? 0 : round.actor) & 0x3) << 17
                | ((showdown ? 1 : 0) << 19));
        entity.setPokerBottom(Math.min(262143, Math.max(0, pot)));
        entity.setPokerControls(controls());
        entity.setPokerSeats(seatStates());
        entity.setPokerHover(hoverPacked);
        entity.endBatch();
    }

    /** 5 个动作按钮（低 20 位）+ 2 个结算按钮（bit20-21 next, bit22-23 exit）的动作码。 */
    private int controls() {
        int[] code = controlCodes();
        int v = 0;
        for (int i = 0; i < 5; i++) {
            v |= (code[i] & 0xF) << (4 * i);
        }
        // 结算按钮码：只在 action 按钮全 0 时才给（等待/结算）
        int[] sc = settleControlCodes();
        v |= (sc[0] & 0x3) << 20;
        v |= (sc[1] & 0x3) << 22;
        return v;
    }

    /**
     * 结算按钮码（下一局 / 退出牌桌）：0 不出现 / 1 可用。
     * 与 action 按钮互斥 —— action 按钮占位时结算按钮不出现。
     */
    public int[] settleControlCodes() {
        int[] code = new int[2];
        // 对局中不出现
        if (isPlaying() && phase != Phase.SHOWDOWN) {
            return code;
        }
        // 「下一局」：结算期且没退人
        if (phase == Phase.SETTLED && !seatVacated) {
            code[0] = 1;
        }
        // 「退出牌桌」：只要坐了就给
        code[1] = occupiedSeats() > 0 ? 1 : 0;
        return code;
    }

    /**
     * 桌面按钮的当前动作码（顺序 fold / check / call / raise / allin）。
     *
     * <p><b>0 = 这根按钮不出现</b>，1 = 可用。2026-10-26 用户要求「别五个一起摆着，分不清什么时候
     * 能点哪个」—— 斗地主就是这么做的：不适用的按钮整根藏掉（{@code v.button_N==0} 时 scale=0），
     * 而不是灰着占位。所以这里只发 0/1，绝不发 2。</p>
     */
    public int[] controlCodes() {
        int[] code = new int[5];
        // SHOWDOWN 也属于 isPlaying，但摊牌阶段已经没有操作可做，按钮必须藏起来
        if (!isPlaying() || round == null || phase == Phase.SHOWDOWN) {
            return code;
        }
        int seat = round.actor;
        if (players[seat] == null) {
            return code;
        }
        int toCall = round.toCall(seat);
        int stack = round.stack[seat];
        boolean canAct = stack > 0;
        code[0] = canAct ? 1 : 0;                                       // 弃牌：轮到你就能弃
        code[1] = (canAct && toCall == 0) ? 1 : 0;                      // 过牌：没人下注才有
        code[2] = (canAct && toCall > 0) ? 1 : 0;                       // 跟注：有人下注才有
        int[] rb = raiseBounds();
        code[3] = (canAct && rb[1] > rb[0]) ? 1 : 0;                    // 加注：加得动才有
        code[4] = canAct ? 1 : 0;                                       // 全下
        return code;
    }

    /**
     * 桌上现在有没有按钮。
     *
     * <p>射线拾取必须先用它挡一道：摊牌阶段 {@code apply()} 并不拒绝（它只挡 WAITING / SETTLED），
     * 按钮虽然被 {@code scale=0} 藏起来了，射线却还能命中 ⇒ 会「点空气弃牌」。</p>
     */
    public boolean buttonsVisible() {
        return controls() != 0;
    }

    private int seatStates() {
        int v = 0;
        for (int s = 0; s < SEATS; s++) {
            int st = offline[s] ? 2 : 0;
            v |= st << (2 * s);
        }
        return v;
    }

    // ------------------------------------------------------------------
    // 表单 / 查询
    // ------------------------------------------------------------------

    public void useBoard(Player p) {
        PokerMenu.open(p, this);
    }

    public int[] raiseBounds() {
        if (round == null) {
            return new int[]{0, 0};
        }
        int seat = round.actor;
        return new int[]{round.minRaiseTo(seat), round.maxRaiseTo(seat)};
    }

    public String statusText(Player p) {
        StringBuilder sb = new StringBuilder();
        sb.append("§7场次 §e").append(tier.label())
                .append(" §7| 阶段 §f").append(phaseName())
                .append(" §7| 底池 §e").append(pot)
                .append(" §7| 已入座 §e").append(occupiedSeats()).append("§7/").append(SEATS)
                .append(" §7（开局需 §f3§7 家起，含 §f").append(minHumans()).append("§7 真人）")
                .append('\n');
        if (ownerName != null) {
            sb.append("§7桌主 §f").append(ownerName);
            if (!isOwner(p)) {
                sb.append(" §8（开始 / 加人机由桌主操作）");
            }
            sb.append('\n');
        }
        // 结算页：把「怎么往下走」写在最上面（不再自动续局，2026-10-26）
        if (phase == Phase.SETTLED) {
            if (seatVacated) {
                sb.append("§e本局座位有变动，续局链已断：坐齐后由桌主点「开始游戏」。\n");
            } else {
                sb.append("§e本局已结算：点「下一局」继续，全员确认后开牌（已确认 §f")
                        .append(nextVoteDone()).append("§e/").append(nextVoteNeeded())
                        .append("§e）。\n");
            }
        }
        // 开不了局时把「缺什么」直接写出来，别让桌主对着没有「开始」按钮的表单猜。
        if (!isPlaying() && isOwner(p)) {
            int needSeat = Math.max(0, 3 - occupiedSeats());
            int needHuman = Math.max(0, minHumans() - humanSeats());
            if (needSeat > 0) {
                sb.append("§e还差 ").append(needSeat).append(" 家才能开局（点下面的「加人机」补）。\n");
            } else if (needHuman > 0) {
                sb.append("§e本场次至少要 ").append(minHumans()).append(" 个真人，还差 ")
                        .append(needHuman).append(" 人。\n");
            }
        }
        for (int s = 0; s < SEATS; s++) {
            sb.append("§7").append(seatLabel(s)).append("：");
            if (players[s] == null && !bot[s]) {
                sb.append("§8空");
            } else {
                sb.append("§f").append(nameOf(s));
                if (bot[s]) {
                    sb.append(" §7(人机·").append(botLevel[s] == PokerBotAi.HARD ? "高级" : "初级").append(")");
                } else if (trusted[s]) {
                    sb.append(" §8(托管)");
                }
                if (round != null) {
                    if (round.folded[s]) {
                        sb.append(" §8已弃牌");
                    } else {
                        sb.append(" §7[§f").append(round.stack[s]).append("§7]");
                        if (round.bet[s] > 0) {
                            sb.append(" §7下注 §e").append(round.bet[s]);
                        }
                    }
                }
            }
            if (s % 2 == 1) {
                sb.append('\n');
            } else {
                sb.append(" §8| ");
            }
        }
        return sb.toString();
    }

    private String phaseName() {
        return switch (phase) {
            case WAITING -> "等待中";
            case PREFLOP -> "翻牌前";
            case FLOP -> "翻牌圈";
            case TURN -> "转牌圈";
            case RIVER -> "河牌圈";
            case SHOWDOWN -> "摊牌";
            case SETTLED -> "结算";
        };
    }

    public String nameOf(int seat) {
        if (names[seat] != null) {
            return names[seat];
        }
        return players[seat] == null ? "空位" : players[seat].getName();
    }

    public void broadcast(String msg) {
        for (int s = 0; s < SEATS; s++) {
            if (players[s] != null && players[s].isOnline()) {
                players[s].sendMessage(msg);
            }
        }
    }

    public void onQuit(Player p) {
        int seat = seatOf(p);
        if (seat < 0) {
            return;
        }
        offline[seat] = true;
        // ★ 2026-10-26 用户 #5：掉线也立即弃掉他的回合（非免费局两个真人之一走人时，
        //   后面还有他的回合不许卡住 —— 和 leave() 同款自动弃牌）
        if (isPlaying() && round != null && !round.folded[seat]) {
            if (seat == round.actor) {
                apply(seat, PokerRound.Action.FOLD, 0);
            } else {
                round.act(seat, PokerRound.Action.FOLD, 0);
                if (round.streetDone()) {
                    advanceStreet();
                }
            }
        }
        // 掉线也算退出：他回来之前不该把「下一局」的门槛顶住
        if (phase != Phase.WAITING) {
            nextVote[seat] = false;
            seatVacated = true;
        }
        // ★ 2026-10-26 用户 #3：免费局 1 真人 + 机器人，真人一走立马清空座位（UNO/斗地主同款）
        if (tier == TabletopGamesConfig.PokerTier.FREE && humanSeats() == 0 && hasBot()) {
            clearBotsNow("真人玩家已退出，清理人机座位。");
        }
        sync();
    }

    public void reattach(Player p) {
        int seat = seatOf(p);
        if (seat < 0) {
            return;
        }
        players[seat] = p;
        offline[seat] = false;
        forceViewer = true;
        sync();
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    public TabletopGamesConfig.PokerTier getTier() {
        return tier;
    }

    public void setTier(TabletopGamesConfig.PokerTier t) {
        if (isPlaying() || t == null) {
            return;
        }
        this.tier = t;
    }

    public int getMinEntry() {
        return minEntry;
    }

    public void restore(int minEntry, TabletopGamesConfig.PokerTier tier) {
        this.minEntry = Math.max(100, minEntry);
        this.tier = tier == null ? TabletopGamesConfig.PokerTier.FREE : tier;
    }

    private int turnSeconds() {
        return gameManager == null ? 20 : gameManager.pokerConfig().normalizedTurnSeconds();
    }

    private int secondTurnSeconds() {
        return gameManager == null ? 15 : gameManager.pokerConfig().normalizedSecondTurnSeconds();
    }

    private cn.nukkit.plugin.Plugin plugin() {
        return gameManager == null ? null : gameManager.getPlugin();
    }

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

    public int getRotation() {
        return rotation;
    }

    public Vector3 getCenter() {
        return center;
    }

    public DdzTableEntity getEntity() {
        return entity;
    }

    public PokerRound getRound() {
        return round;
    }

    // ------------------------------------------------------------------
    // 菜单 / 拾取用的小查询
    // ------------------------------------------------------------------

    public boolean isSeatFree(int seat) {
        return seat >= 0 && seat < SEATS && players[seat] == null && !bot[seat];
    }

    /**
     * 开局 / 加人机需要的最少真人数。
     *
     * <p>免费场是假筹码，一个人带两三个人机就能开（用户 2026-10-25 要求）；
     * 新手场动真钱，必须两个真人，避免「自己跟人机对赌」变成变相刷钱。</p>
     */
    public int minHumans() {
        return tier == TabletopGamesConfig.PokerTier.NEWBIE ? 2 : 1;
    }

    public boolean canAddBot() {
        return !isPlaying() && humanSeats() >= minHumans() && occupiedSeats() < SEATS;
    }

    public boolean isOwner(Player p) {
        return p != null && ownerId != null && ownerId.equals(p.getUniqueId());
    }

    public String ownerName() {
        return ownerName;
    }

    /** 桌主离开 / 掉线时顺位交给下一个真人。 */
    private void handoverOwner() {
        if (ownerId == null) {
            return;
        }
        boolean stillHere = false;
        for (int i = 0; i < SEATS; i++) {
            if (players[i] != null && ownerId.equals(players[i].getUniqueId())) {
                stillHere = true;
                break;
            }
        }
        if (stillHere) {
            return;
        }
        ownerId = null;
        ownerName = null;
        for (int i = 0; i < SEATS; i++) {
            if (players[i] != null && !bot[i]) {
                ownerId = players[i].getUniqueId();
                ownerName = players[i].getName();
                return;
            }
        }
    }

    public int toCallOfActor() {
        return round == null ? 0 : round.toCall(round.actor);
    }

    public int potNow() {
        return pot;
    }

    /** 滑块默认值：半个底池，夹在 [minRaise, max] 之间。 */
    public int suggestRaise() {
        if (round == null) {
            return 0;
        }
        int seat = round.actor;
        int target = round.currentBet + Math.max(round.minRaise, pot / 2);
        return Math.max(round.minRaiseTo(seat), Math.min(round.maxRaiseTo(seat), target));
    }

    /** 客户端复活 / 重进半径时作废视角动画标记（管理器的 onRespawn 调）。 */
    public void forgetViewer(java.util.UUID id) {
        for (int s = 0; s < SEATS; s++) {
            if (id.equals(viewerSentTo[s])) {
                viewerSentTo[s] = null;
            }
        }
        forceViewer = true;
    }
}
