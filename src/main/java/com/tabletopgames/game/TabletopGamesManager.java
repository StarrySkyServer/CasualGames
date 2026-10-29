package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.command.CommandSender;
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
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.yaml.snakeyaml.YamlSnakeYamlConfigurer;
import com.tabletopgames.ai.TabletopGamesAi;
import com.tabletopgames.board.BaseTabletopGamesBoardBlock;
import com.tabletopgames.board.DoushouqiBoardBlock;
import com.tabletopgames.board.GoBoardBlock;
import com.tabletopgames.board.OthelloBoardBlock;
import com.tabletopgames.board.TabletopGamesBoard3x3Block;
import com.tabletopgames.board.TabletopGamesBoardBlock;
import com.tabletopgames.board.XiangqiBoardBlock;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.entity.TabletopGamesStoneEntity;
import com.tabletopgames.item.TabletopGamesBoard3x3Item;
import com.tabletopgames.item.TabletopGamesBoardItem;
import com.tabletopgames.storage.BoardStorage;
import com.tabletopgames.storage.StartupReport;
import com.tabletopgames.ui.TabletopGamesMenu;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理所有已放置的棋盘，处理放置、交互、范围检测与清理。
 */
public class TabletopGamesManager implements Listener {

    /** 放置棋盘的冷却时间（毫秒）：一次点击可能触发多个交互事件，需去重 */
    private static final long PLACE_COOLDOWN_MS = 500L;

    /** 机器人“思考”停顿，让落子有节奏感（tick 数，20 tick ≈ 1 秒）。 */
    private static final int AI_THINK_DELAY_TICKS = 10;

    /** 存档目录名（{@code data/<本名>/}），一个棋盘一个 {@code 0001.yml}。 */
    private static final String STORAGE_KIND = "gomoku";

    private final PluginBase plugin;

    /** 插件实例（供牌桌在异步调度里取 scheduler / logger 用）。 */
    public PluginBase getPlugin() {
        return plugin;
    }
    private final Map<String, TabletopGamesBoard> boards = new HashMap<>();
    private final Map<UUID, Long> placeCooldown = new HashMap<>();

    /** 每个棋盘对应的存档编号（文件名 {@code 0001.yml}），键为棋盘坐标 key。 */
    private final Map<String, Integer> boardIds = new HashMap<>();

    /** 下一个可用的存档编号。 */
    private int nextBoardId = 1;

    /**
     * 按关卡索引棋盘：准星/范围相关的高频遍历只需看玩家所在关卡，避免跨世界空转。
     * 与 {@link #boards} 同步维护（放置、破坏、读档三处）。
     */
    private final Map<Level, List<TabletopGamesBoard>> boardsByLevel = new HashMap<>();

    /**
     * 每名玩家最近一次刷新高亮的服务器 tick。同一 tick 内 onMove 与周期任务会重复触发，
     * 用此去重，保证同一 tick 每人只算一次。
     */
    private final Map<UUID, Integer> hoverTick = new HashMap<>();

    /** {@link TabletopGamesBoard#collectLeftPlayers(List)} 的输出缓冲，复用避免每 tick 分配。 */
    private final List<UUID> leftScratch = new ArrayList<>(4);

    /**
     * 每棋盘上一次生成的存档快照。棋盘未变更时直接复用，只有变脏的棋盘才重新读取字段与编码落子行，
     * 避免「一步棋就重编码全部棋盘」。仅主线程访问（capture 在主线程）。
     */
    private final Map<String, BoardSnapshot> snapshotCache = new HashMap<>();

    /** 棋盘状态发生变更后置位，下一次 tick 落盘，避免频繁写文件。 */
    private boolean dirty;

    /** 插件以 STARTUP 阶段启用，此时关卡尚未加载，需等关卡就绪后再恢复棋盘。 */
    private boolean loadPending;

    /** 首次恢复是否已完成。未完成前禁止写盘，避免用空数据覆盖存档。 */
    private boolean restored;

    /**
     * 插件全局配置（全插件唯一一份 {@code config.yml}，内部按棋类 / 牌桌分节）。
     * 始终非空：未加载前使用字段默认值。
     */
    private TabletopGamesConfig config = new TabletopGamesConfig();

    /** 配置变更回调，由插件入口注册（见 {@link #setConfigListener(Runnable)}）。 */
    private Runnable configListener;

    /**
     * 各棋类的玩家识别半径缓存。{@code inRange} 在准星/范围检测里被高频调用，这里缓存成基本类型字段，
     * 避免每次都穿过 config 的访问器；加载 / 重载 / 表单保存时刷新。
     */
    private final Map<BoardKind, Double> playerRanges = new EnumMap<>(BoardKind.class);


    public TabletopGamesManager(PluginBase plugin) {
        this.plugin = plugin;
        refreshPlayerRanges();
    }

    /** 五子棋（插件本体）配置。历史签名：等价于 {@code configOf(BoardKind.GOMOKU)}。 */
    public TabletopGamesConfig.GameSettings getConfig() {
        return configOf(BoardKind.GOMOKU);
    }

    /** 指定棋类的配置节（config.yml 内的对应小节）。UNO 字段集不同，请用 {@link #unoConfig()}。 */
    public TabletopGamesConfig.GameSettings configOf(BoardKind kind) {
        return config.section(kind);
    }

    /** UNO 牌桌配置节（config.yml 内的 {@code uno} 小节）。 */
    public TabletopGamesConfig.UnoSettings unoConfig() {
        return config.getUno();
    }

    /**
     * 斗地主牌桌配置节（config.yml 内的 {@code ddz} 小节）。
     *
     * <p>与 {@link #unoConfig()} 同构：牌桌侧<b>不能</b>用 {@code plugin.getConfig()}
     * （那是 Nukkit 原生配置，返回类型根本没有 {@code getDdz()}）。
     */
    public TabletopGamesConfig.DdzSettings ddzConfig() {
        return config.getDdz();
    }

    /** 德州扑克配置节（config.yml 内的 {@code poker} 小节）。 */
    public TabletopGamesConfig.PokerSettings pokerConfig() {
        return config.getPoker();
    }

    /** 军旗配置节（config.yml 内的 {@code junqi} 小节）。 */
    public TabletopGamesConfig.JunqiSettings junqiConfig() {
        return config.getJunqi();
    }

    /** 五子棋的玩家识别半径（缓存值）。历史签名：等价于 {@code playerRangeOf(BoardKind.GOMOKU)}。 */
    public double playerRange() {
        return playerRangeOf(BoardKind.GOMOKU);
    }

    /** 指定棋类缓存的玩家识别半径。 */
    public double playerRangeOf(BoardKind kind) {
        Double r = playerRanges.get(kind);
        return r != null ? r : 7.0;
    }

    /** 插件全局配置文件（插件数据目录下的 {@code config.yml}）。 */
    private File configFile() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录：" + folder.getPath());
        }
        return new File(folder, "config.yml");
    }

    /**
     * 按当前配置刷新各棋类的玩家识别半径缓存。
     *
     * <p>⚠️ 取半径必须走 {@link TabletopGamesConfig#playerRangeOf(BoardKind)} ——
     * 初版这里写的是「UNO 特判、其余走 {@code config.section(kind)}」，
     * 而斗地主（DDZ）同样用独立字段集，于是 {@code section(DDZ)} 直接抛异常，
     * 插件在 onEnable 就崩了（物品能看见、右键全无反应）。</p>
     */
    private void refreshPlayerRanges() {
        for (BoardKind kind : BoardKind.values()) {
            this.playerRanges.put(kind, config.playerRangeOf(kind));
        }
    }

    /**
     * 启动时同步加载全局配置（单文件 {@code config.yml}）。
     * <p>
     * 文件很小，且必须保证配置在首个 tick（范围检测）前就已可用，
     * 因此这里不异步；{@code /tabletopgame reload} 才走异步。
     */
    public void loadConfig() {
        this.config = readOrRebuild(TabletopGamesConfig.class);
        refreshPlayerRanges();
        notifyConfigChanged();
    }

    /**
     * 配置变更回调（加载 / 重载 / 表单保存后触发）。
     * <p>用于同步「依赖配置的副作用」——目前是 UNO 牌桌是否注册合成配方，
     * 让 {@code unoTableCraftable} 改完 {@code /tabletopgame reload} 就能生效，无需重启。
     */
    public void setConfigListener(Runnable listener) {
        this.configListener = listener;
    }

    private void notifyConfigChanged() {
        if (configListener != null) {
            configListener.run();
        }
    }

    /** 读取全局配置；损坏（非法 YAML / 手工改坏）时备份并按默认值重建。 */
    private <T extends OkaeriConfig> T readOrRebuild(Class<T> type) {
        File file = configFile();
        try {
            return doReadConfig(type, file);
        } catch (Exception failure) {
            // 不能让一个坏文件把整个插件拖垮：备份坏文件 → 按默认值重建 → 继续启动。
            if (file.exists()) {
                File backup = new File(file.getParentFile(),
                        "config.broken-" + System.currentTimeMillis() + ".yml");
                if (file.renameTo(backup)) {
                    plugin.getLogger().warning("原配置已备份为 " + backup.getName()
                            + "，无需再手动删除配置文件。");
                }
            }
            plugin.getLogger().warning("配置读取失败，已按默认值重建：" + failure.getMessage());
            try {
                return doReadConfig(type, file);
            } catch (Exception second) {
                plugin.getLogger().error("配置重建仍然失败，请检查插件数据目录写入权限。", second);
                throw new IllegalStateException("配置读取失败且无法重建", second);
            }
        }
    }

    private static <T extends OkaeriConfig> T doReadConfig(Class<T> type, File file) {
        return ConfigManager.create(type, it -> {
            it.withConfigurer(new YamlSnakeYamlConfigurer());
            it.withBindFile(file);
            it.saveDefaults();
            it.load(true);
        });
    }

    /**
     * 异步重载全局配置（单文件 {@code config.yml}）：文件读取与 YAML 解析放到线程池，
     * 成功后才回主线程替换引用并反馈结果（失败则保持现状）。
     */
    public void reloadConfig(CommandSender feedback) {
        Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
            TabletopGamesConfig loaded = null;
            String error = null;
            try {
                loaded = readOrRebuild(TabletopGamesConfig.class);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final TabletopGamesConfig reloaded = loaded;
            final String failure = error;
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                if (failure != null) {
                    feedback.sendMessage("§c配置重载失败：" + failure);
                    plugin.getLogger().warning("配置重载失败：" + failure);
                    return;
                }
                this.config = reloaded;
                refreshPlayerRanges();
                notifyConfigChanged();
                feedback.sendMessage("§a配置已重载（config.yml，各类独立）：");
                for (BoardKind kind : BoardKind.values()) {
                    if (kind.isBoard()) {
                        TabletopGamesConfig.GameSettings c = configOf(kind);
                        feedback.sendMessage(String.format(Locale.ROOT,
                                "§7· %s：半径 %s 格 / 菜单锁 %d 秒 / 抽水 %s%% / 下注 %s ~ %s",
                                kind.label(), c.getPlayerRange(), c.getMenuLockSeconds(),
                                c.getBetting().normalizedCut(),
                                c.getBetting().normalizedMinBet(), c.getBetting().normalizedMaxBet()));
                    } else if (kind == BoardKind.DDZ) {
                        // ⚠️ 必须单独分支：初版这里只写了「不是棋类就按 UNO 显示」，
                        //    于是斗地主那一行打印的是 UNO 的半径/抽水/倍数 k —— 全部张冠李戴。
                        TabletopGamesConfig.DdzSettings d = ddzConfig();
                        feedback.sendMessage(String.format(Locale.ROOT,
                                "§7· %s：半径 %s 格 / 菜单锁 %d 秒 / 抽水 %s%%"
                                        + " / 底分 %s / 倍数上限 %d / 回合 %d 秒 / 牌桌%s",
                                kind.label(), d.getPlayerRange(), d.getMenuLockSeconds(),
                                d.normalizedCut(), d.baseScore(), d.normalizedMaxMultiplier(),
                                d.normalizedTurnSeconds(),
                                d.isCraftable() ? "可合成" : "不可合成"));
                    } else {
                        TabletopGamesConfig.UnoSettings u = unoConfig();
                        feedback.sendMessage(String.format(Locale.ROOT,
                                "§7· %s：半径 %s 格 / 菜单锁 %d 秒 / 抽水 %s%% / 倍数 k %s ~ %s"
                                        + " / 回合 %d + %d 秒（未摸牌 + 摸牌后） / 牌桌%s",
                                kind.label(), u.getPlayerRange(), u.getMenuLockSeconds(),
                                u.normalizedCut(),
                                u.normalizedMinCoefficient(),
                                u.normalizedMaxCoefficient(),
                                u.normalizedTurnSeconds(),
                                u.normalizedSecondTurnSeconds(),
                                u.isCraftable() ? "可合成" : "不可合成"));
                    }
                }
                plugin.getLogger().info("配置已重载（config.yml，各类独立）");
            }, false);
        }, true);
    }

    /**
     * 应用某个棋类的设置表单（五子棋 / 中国象棋 / 黑白棋 / 围棋 / 斗兽棋）：
     * 更新 config.yml 内对应小节、刷新半径缓存并写回，实时生效。
     * <p>数值合法性已由表单侧校验，这里再做一次边界钳制兜底。
     */
    public void applyBoardSettings(BoardKind kind, double playerRange, int menuLockSeconds,
                                   double cut, double minBet, double maxBet) {
        TabletopGamesConfig.GameSettings c = configOf(kind);
        c.setPlayerRange(Math.max(0.5, playerRange));
        c.setMenuLockSeconds(Math.max(1, menuLockSeconds));
        TabletopGamesConfig.GameSettings.Betting betting = c.getBetting();
        betting.setCut(Math.max(0.0, Math.min(100.0, cut)));
        betting.setMinBet(Math.max(0.0, minBet));
        betting.setMaxBet(Math.max(Math.max(0.0, maxBet), Math.max(0.0, minBet)));
        this.playerRanges.put(kind, c.getPlayerRange());
        config.save();
        notifyConfigChanged();
    }

    /**
     * 应用 UNO 设置表单：更新 config.yml 内的 {@code uno} 小节并写回，实时生效。
     * <p>{@code turnSeconds} = 第一次回合时间（未摸牌），{@code secondTurnSeconds} = 第二次
     * 回合时间（摸牌后必须出牌/跳过），两者都钳制到 5~600 秒。
     * <p>{@code joinBalanceMultiplier} = 入座门槛系数：实际门槛 = 本系数 × 倍数 k（负数归零）。
     * <p>除了写盘，还会触发配置变更回调（{@code syncUnoRecipe} 同步配方 +
     * {@code UnoManager#onConfigReloaded()} 让在座牌桌立即按新回合时间重算时限）。
     */
    public void applyUnoSettings(double playerRange, int menuLockSeconds, double cut,
                                 double joinBalanceMultiplier,
                                 double minCoefficient, double maxCoefficient,
                                 boolean craftable, int turnSeconds, int secondTurnSeconds) {
        TabletopGamesConfig.UnoSettings c = config.getUno();
        c.setPlayerRange(Math.max(0.5, playerRange));
        c.setMenuLockSeconds(Math.max(1, menuLockSeconds));
        c.setCut(Math.max(0.0, Math.min(100.0, cut)));
        c.setJoinBalanceMultiplier(Math.max(0.0, joinBalanceMultiplier));
        c.setMinCoefficient(Math.max(0.0, Math.floor(minCoefficient)));
        c.setMaxCoefficient(Math.max(Math.floor(maxCoefficient), c.getMinCoefficient()));
        c.setCraftable(craftable);
        c.setTurnSeconds(Math.max(5, Math.min(600, turnSeconds)));
        c.setSecondTurnSeconds(Math.max(5, Math.min(600, secondTurnSeconds)));
        this.playerRanges.put(BoardKind.UNO, c.getPlayerRange());
        config.save();
        notifyConfigChanged();
    }

    /**
     * 应用斗地主设置表单：更新 config.yml 内的 {@code ddz} 小节并写回，实时生效。
     *
     * <h2>与 {@link #applyUnoSettings} 的三处差异</h2>
     * <ol>
     *   <li><b>没有「第二回合时间」</b>：斗地主每次轮到的动作都是「出牌 / 不出」，
     *       不存在 UNO 那种「先摸牌、再限时出牌」的两段时限，只有一个 {@code turnSeconds}。</li>
     *   <li><b>没有倍数 k 区间</b>：改成 4 档固定底分（{@link TabletopGamesConfig.DdzTier}），
     *       输赢幅度交给「倍数」（叫抢 2^n、加倍 ×2/×4、炸弹 ×2、春天 ×2），
     *       由 {@code maxMultiplier} 封顶，不再叠一层系数体系。</li>
     *   <li><b>抽水是两段式</b>：入座时已实扣本金（{@code DdzSettings#stakeAmount()}），
     *       这里只改结算阶段的抽水比例，不重复扣。</li>
     * </ol>
     *
     * @param tier  档位（传字符串而非枚举：表单给的是玩家填的原样文本，非法值由
     *              {@code normalizedTier()} 兜底回落，不在这里判错）
     */
    public void applyDdzSettings(double playerRange, int menuLockSeconds, double cut,
                                 double joinBalanceMultiplier, String tier, String aiDifficulty,
                                 boolean craftable, int turnSeconds, int dealStepMillis,
                                 int maxMultiplier) {
        TabletopGamesConfig.DdzSettings c = config.getDdz();
        c.setPlayerRange(Math.max(0.5, playerRange));
        c.setMenuLockSeconds(Math.max(1, menuLockSeconds));
        c.setCut(Math.max(0.0, Math.min(100.0, cut)));
        c.setJoinBalanceMultiplier(Math.max(0.0, joinBalanceMultiplier));
        c.setTier(tier == null ? "" : tier.trim().toLowerCase(java.util.Locale.ROOT));
        c.setAiDifficulty(aiDifficulty == null ? "" : aiDifficulty.trim().toLowerCase(java.util.Locale.ROOT));
        c.setCraftable(craftable);
        c.setTurnSeconds(Math.max(5, Math.min(600, turnSeconds)));
        c.setDealStepMillis(Math.max(20, Math.min(200, dealStepMillis)));
        // 上限做成 2 的幂：叫抢 2^n 与炸弹/春天 ×2 都是整倍，只允许玩家在幂次上选
        int m = Math.max(16, Math.min(65536, maxMultiplier));
        c.setMaxMultiplier(Integer.highestOneBit(m));
        config.save();
        notifyConfigChanged();
    }

    /** 应用德州扑克的全局默认值（新建牌桌按它初始化；空桌会跟着刷新）。 */
    public void applyPokerSettings(double playerRange, int minEntry, int smallBlind,
                                   int bigBlind, double cut, int turnSeconds,
                                   int secondTurnSeconds) {
        TabletopGamesConfig.PokerSettings c = config.getPoker();
        c.setPlayerRange(Math.max(0.5, playerRange));
        c.setMinEntry(Math.max(100, Math.min(262143, minEntry)));
        c.setSmallBlind(Math.max(1, Math.min(1000, smallBlind)));
        c.setBigBlind(Math.max(smallBlind * 2, Math.min(2000, bigBlind)));
        c.setCut(Math.max(0.0, Math.min(100.0, cut)));
        c.setTurnSeconds(Math.max(5, Math.min(600, turnSeconds)));
        c.setSecondTurnSeconds(Math.max(5, Math.min(600, secondTurnSeconds)));
        config.save();
        notifyConfigChanged();
    }

    /**
     * 应用军旗的规则项（{@code junqi.rules}）：行营斜线 / 无吃子判和手数 / 先手。
     * <p>通用的半径 / 菜单锁 / 下注仍走 {@link #applyBoardSettings(BoardKind, double, int, double, double, double)}。
     */
    public void applyJunqiRules(boolean campDiagonal, int drawQuietMoves, String firstMove) {
        TabletopGamesConfig.JunqiSettings.Rules r = junqiConfig().getRules();
        r.setCampDiagonal(campDiagonal);
        r.setDrawQuietMoves(Math.max(0, drawQuietMoves));
        r.setFirstMove(firstMove == null ? "red" : firstMove.trim().toLowerCase(Locale.ROOT));
        config.save();
    }

    /** 标记棋盘状态已变更，等待下一次 tick 统一保存。 */
    public void markDirty() {
        this.dirty = true;
    }

    /** 请求在关卡加载完成后恢复磁盘上的棋盘。 */
    public void requestLoad() {
        this.loadPending = true;
    }

    public TabletopGamesBoard findBoard(Level level, int x, int y, int z) {
        for (TabletopGamesBoard board : boards.values()) {
            if (board.getLevel() == level && board.contains(x, y, z)) {
                return board;
            }
        }
        return null;
    }

    /** 把棋盘登记进关卡索引（放置、读档后调用）。 */
    private void indexBoard(TabletopGamesBoard board) {
        boardsByLevel.computeIfAbsent(board.getLevel(), k -> new ArrayList<>(4)).add(board);
    }

    /** 把棋盘从关卡索引移除（破坏后调用）。 */
    private void unindexBoard(TabletopGamesBoard board) {
        List<TabletopGamesBoard> list = boardsByLevel.get(board.getLevel());
        if (list != null) {
            list.remove(board);
            if (list.isEmpty()) {
                boardsByLevel.remove(board.getLevel());
            }
        }
    }

    /**
     * 注意：这里刻意不设置 {@code ignoreCancelled = true}。
     * <p>
     * 基岩触屏（默认“点击即交互”模式）点击棋盘会走 {@code Level#useItemOn}，而该路径会
     * 在派发事件之前因“出生点保护”对非 OP 玩家调用 {@code setCancelled(true)}
     * （见 {@code Level#isInSpawnRadius}，默认半径 10）。若监听器忽略已取消事件，
     * 非 OP 玩家在出生点附近点击棋盘就完全收不到回调，表现为“只有 OP 才能打开表单”。
     * <p>
     * 因此改为接收已取消事件，并在内部区分：打开菜单不受影响；放置棋盘仍遵守取消逻辑。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        // 定位器「蹲下+使用」的准星开关：这次右键已被 LocatorTitleService 吞掉，不能再开棋盘菜单。
        // 判据不能用 event.isCancelled()（本类故意接收出生点保护取消掉的事件）。
        if (LocatorTitleService.isSwallowed(player)) {
            return;
        }
        Item item = event.getItem();
        Block clicked = event.getBlock();
        PlayerInteractEvent.Action action = event.getAction();

        // 右键：PC 鼠标右键，或触屏对可交互方块的使用。用于放置棋盘或打开棋盘菜单。
        if (action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            if (item instanceof TabletopGamesBoardItem || item instanceof TabletopGamesBoard3x3Item) {
                // 放置棋盘会真正改动世界，仍遵守服务端取消（如出生点保护）
                if (event.isCancelled() || !canPlace(player)) {
                    event.setCancelled(true);
                    return;
                }
                int size = item instanceof TabletopGamesBoard3x3Item ? 3 : 2;
                tryPlace(player, event, clicked, size);
                return;
            }
            if (clicked instanceof BaseTabletopGamesBoardBlock) {
                useBoardAt(player, event, clicked);
            }
            return;
        }

        // 左键不触发棋盘菜单：仅右键（PC 右键 / 触屏“使用”交互）打开，避免与挖掘及出生点保护冲突。
    }

    /** 命中棋盘方块时打开菜单或落子。 */
    private void useBoardAt(Player player, PlayerInteractEvent event, Block clicked) {
        TabletopGamesBoard board = findBoard(player.getLevel(),
                clicked.getFloorX(), clicked.getFloorY(), clicked.getFloorZ());
        if (board != null) {
            event.setCancelled(true);
            useBoard(player, board);
        }
    }

    /**
     * 兜底处理：若客户端仍把右键命中到棋子实体（例如命中箱同步未生效），
     * 同样按“点击棋盘”处理——对局中落子，否则弹出菜单。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getEntity() instanceof TabletopGamesStoneEntity stone)) {
            return;
        }
        TabletopGamesBoard board = stone.getBoard();
        if (board == null) {
            return;
        }
        event.setCancelled(true);
        useBoard(event.getPlayer(), board);
    }

    /** 同一玩家的放置冷却检查，首次通过后记录时间戳。 */
    private boolean canPlace(Player player) {
        long now = System.currentTimeMillis();
        Long last = placeCooldown.get(player.getUniqueId());
        if (last != null && now - last < PLACE_COOLDOWN_MS) {
            return false;
        }
        placeCooldown.put(player.getUniqueId(), now);
        return true;
    }

    private void tryPlace(Player player, PlayerInteractEvent event, Block clicked, int size) {
        BlockFace face = event.getFace();
        if (face == null) {
            return;
        }
        Vector3 target = clicked.getSide(face);
        int bx = target.getFloorX();
        int by = target.getFloorY();
        int bz = target.getFloorZ();
        Level level = player.getLevel();

        // 区域按玩家视角确定：从放置点沿玩家左手边（P）与正前方（Q）延伸
        int rotation = rotationOf(player);
        int px = TabletopGamesBoard.axisPX(rotation);
        int pz = TabletopGamesBoard.axisPZ(rotation);
        int qx = TabletopGamesBoard.axisQX(rotation);
        int qz = TabletopGamesBoard.axisQZ(rotation);

        // 3x3 以点击方块为中心：区域原点回退半格（P+Q），使区域中心格 (1,1) 正好落在放置点上，
        // 其余尺寸仍以放置点为原点沿 P/Q 正向延伸。
        if (size == 3) {
            bx -= px + qx;
            bz -= pz + qz;
        }

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                Block b = level.getBlock(bx + i * px + j * qx, by, bz + i * pz + j * qz);
                if (!b.isAir() && !b.canBeReplaced()) {
                    player.sendTip("§c空间不足，放置棋盘需要 " + size + "x" + size + " 空地。");
                    return;
                }
            }
        }
        for (TabletopGamesBoard board : boards.values()) {
            if (board.getLevel() == level && board.getBaseY() == by
                    && overlaps(board, bx, bz, size, rotation)) {
                player.sendTip("§c这里已经有棋盘了。");
                return;
            }
        }

        event.setCancelled(true);
        placeBlocks(level, bx, by, bz, size, rotation);

        // 放置音效：与橡木木板一致（Bedrock 放置方块用的就是 dig.wood，音调略低）
        double half = (size - 1) / 2.0;
        level.addSound(new Vector3(
                bx + half * (px + qx) + 0.5,
                by + 0.5,
                bz + half * (pz + qz) + 0.5), Sound.DIG_WOOD, 1.0f, 0.8f);

        TabletopGamesBoard board = new TabletopGamesBoard(this, level, bx, by, bz, size, rotation);
        board.spawnEntity();
        boards.put(board.getKey(), board);
        indexBoard(board);
        markDirty();

        // 创造模式放置不消耗物品
        if (!player.isCreative()) {
            Item hand = player.getInventory().getItemInHand();
            if (hand.getCount() <= 1) {
                player.getInventory().setItemInHand(Item.get(Item.AIR));
            } else {
                hand.setCount(hand.getCount() - 1);
                player.getInventory().setItemInHand(hand);
            }
        }
        player.sendTip("§a已放置" + sizeLabel(size) + "五子棋棋盘。右键棋盘可打开菜单。");
    }

    /** 棋盘尺寸标签：标准 2x2 不带前缀，3x3 以「3x3 」区分。 */
    private static String sizeLabel(int size) {
        return size == 3 ? "3x3 " : "";
    }

    /** 按玩家朝向取最近的 90° 朝向：0=南、1=西、2=北、3=东。 */
    private static int rotationOf(Player player) {
        return TabletopGamesBoard.normalizeRotation((int) Math.round(player.getYaw() / 90.0));
    }

    /** 按边长与朝向铺设棋盘方块，position = i + j * size。 */
    private void placeBlocks(Level level, int bx, int by, int bz, int size, int rotation) {
        int px = TabletopGamesBoard.axisPX(rotation);
        int pz = TabletopGamesBoard.axisPZ(rotation);
        int qx = TabletopGamesBoard.axisQX(rotation);
        int qz = TabletopGamesBoard.axisQZ(rotation);
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                BaseTabletopGamesBoardBlock block = size == 3 ? new TabletopGamesBoard3x3Block()
                        : new TabletopGamesBoardBlock();
                // position = segment * 4 + rotation：segment 选择贴图象限，rotation 让方块模型跟随棋盘朝向
                block.setPosition((i + j * size) * 4 + rotation);
                level.setBlock(new Vector3(bx + i * px + j * qx, by, bz + i * pz + j * qz), block, true);
            }
        }
    }

    /** 两个棋盘的水平区域是否重叠（允许尺寸/朝向不同）。 */
    private boolean overlaps(TabletopGamesBoard board, int bx, int bz, int size, int rotation) {
        int[] a = footprintBounds(board.getBaseX(), board.getBaseZ(), board.getBoardSize(), board.getRotation());
        int[] b = footprintBounds(bx, bz, size, rotation);
        return a[0] <= b[1] && b[0] <= a[1] && a[2] <= b[3] && b[2] <= a[3];
    }

    /** 计算棋盘区域在世界中的包围盒：{minX, maxX, minZ, maxZ}。 */
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

    private void useBoard(Player player, TabletopGamesBoard board) {
        if (board.isRunning()) {
            // Swap2 开局选择阶段：只有需要选择的一方点击棋盘时才弹出选择表单
            if (board.needsSwapDecision()) {
                UUID seat = board.swapDecisionSeat();
                if (seat != null && seat.equals(player.getUniqueId())) {
                    TabletopGamesMenu.openSwapDecision(player, board);
                } else {
                    player.sendTip("§e请等待对手完成 Swap2 开局选择。");
                }
                return;
            }
            boolean ended = board.place(player, board.aimCell(player));
            // 摆子后若轮到某方做开局选择，自动弹出选择表单（关闭后可再次点击棋盘重开）
            if (board.needsSwapDecision()) {
                openSwapDecisionFor(board);
            } else if (ended) {
                reopenForSeats(board);
            }
        } else {
            TabletopGamesMenu.open(player, board);
        }
    }

    /** 为需要做 Swap2 开局选择的一方弹出选择表单。 */
    private void openSwapDecisionFor(TabletopGamesBoard board) {
        UUID seat = board.swapDecisionSeat();
        if (seat == null) {
            return;
        }
        Player decider = Server.getInstance().getPlayer(seat).orElse(null);
        if (decider != null) {
            runLater(() -> TabletopGamesMenu.openSwapDecision(decider, board));
        }
    }

    private void reopenForSeats(TabletopGamesBoard board) {
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null) {
                Server.getInstance().getScheduler().scheduleDelayedTask(plugin,
                        () -> TabletopGamesMenu.open(p, board), 1);
            }
        }
    }

    /** 延迟一 tick 重新弹出指定玩家的棋盘菜单，供人机对战的分步表单使用。 */
    public void reopenMenu(Player player, TabletopGamesBoard board) {
        runLater(() -> TabletopGamesMenu.reopen(player, board));
    }

    /**
     * 座位变化（有人加入/退出）后刷新所有在座玩家的棋盘菜单。
     * <p>
     * 表单弹出后无法从服务端直接替换——{@link Player#showFormWindow} 在 {@code formOpen} 为 true 时
     * 会直接返回 -1 且不发送任何数据，客户端上仍是旧表单。因此刷新分两步：
     * <ol>
     *     <li>{@link TabletopGamesMenu#closeCurrentForm} 让客户端真正关闭当前表单（同时清掉服务端登记）</li>
     *     <li>延后一 tick 重新弹出对应状态的表单，借客户端「新表单顶掉旧表单」完成自动切换</li>
     * </ol>
     * 关闭表单不会触发旧表单的关闭回调，因此不会误触发「释放菜单占用」之类的副作用。
     * <p>
     * 效果：有人加入时，等待中的一方自动从「1 人等待」换成「开始棋局 / 等待对方开始」；
     * 有人退出时，另一方自动换回「1 人可开局」界面，无需手动点击棋盘。
     */
    public void refreshSeatMenus(TabletopGamesBoard board) {
        if (board.isRunning()) {
            return;
        }
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p == null) {
                continue;
            }
            TabletopGamesMenu.closeCurrentForm(p);
            reopenMenu(p, board);
        }
    }

    /**
     * 关闭所有在座玩家的棋盘表单。
     * <p>
     * 开局时使用：对局开始后「等待对方开始 / 开始棋局」的表单已经没有意义，
     * 而 Nukkit 在客户端仍开着表单时不会再发送新表单，不主动关掉的话，
     * 后入座的一方会一直对着「等待对方开始棋局」的界面直到手动关闭。
     */
    public void closeSeatMenus(TabletopGamesBoard board) {
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null) {
                TabletopGamesMenu.closeCurrentForm(p);
            }
        }
    }

    /** 延后一 tick 在主线程执行，避免在表单回调中直接弹窗造成客户端窗口冲突。 */
    public void runLater(Runnable action) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, action, 1);
    }

    /**
     * 棋盘方块可像橡木板一样被挖掘，但任意一处破坏成功即视作整体收回：
     * 取消单块破坏，改为延迟一 tick 移除整块棋盘，并按普通方块逻辑把棋盘物品掉落在原地
     * （需玩家自行拾取；创造模式不掉落）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block instanceof BaseTabletopGamesBoardBlock)) {
            return;
        }
        // 象棋棋盘由 XiangqiManager 独立处理（整体收回）：这里提前返回，
        // 避免抢先取消事件、播两次音效，以及两个监听器 ignoreCancelled 造成的互相跳过
        if (block instanceof XiangqiBoardBlock) {
            return;
        }
        // 黑白棋棋盘同理，由 OthelloManager 独立处理
        if (block instanceof OthelloBoardBlock) {
            return;
        }
        // 围棋棋盘同理，由 GoManager 独立处理
        if (block instanceof GoBoardBlock) {
            return;
        }
        // 斗兽棋棋盘同理，由 DoushouqiManager 独立处理
        if (block instanceof DoushouqiBoardBlock) {
            return;
        }
        // 先确认方块确实登记在某个棋盘上：登记丢失（存档被删 / 已收回）时它属于「孤儿方块」，
        // 此时放行、按普通方块破坏；若先取消再 return，方块会永久残留且玩家无法清理。
        TabletopGamesBoard board = findBoard(block.getLevel(),
                block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (board == null) {
            return;
        }
        event.setCancelled(true);
        // 破坏事件被取消，服务端不会广播破坏特效，这里补上橡木板的破坏音效
        block.getLevel().addSound(
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.5, block.getFloorZ() + 0.5),
                Sound.DIG_WOOD, 1.0f, 1.0f);
        Player player = event.getPlayer();
        Level level = block.getLevel();
        Vector3 dropAt = new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.25, block.getFloorZ() + 0.5);
        boolean drop = !player.isCreative();
        int boardSize = board.getBoardSize();
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (boards.remove(board.getKey()) == null) {
                return;
            }
            unindexBoard(board);
            snapshotCache.remove(board.getKey());
            deleteBoardFile(takeId(board.getKey()), board.getKey());
            board.remove();
            markDirty();
            if (drop) {
                Item dropItem = boardSize == 3 ? new TabletopGamesBoard3x3Item()
                        : new TabletopGamesBoardItem();
                level.dropItem(dropAt, dropItem);
            }
        }, 1);
    }

    /** 玩家是否坐在本类任意一个棋盘上（定位器准星「只在座位上显示」的判据，2026-10-22）。 */
    public boolean isSeated(UUID id) {
        for (TabletopGamesBoard board : boards.values()) {
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
        for (TabletopGamesBoard board : boards.values()) {
            if (board.isSeated(id)) {
                board.leave(id);
            } else {
                // 未入座但正占用「0 人入座」菜单的玩家离线：立即释放，避免他人被锁到超时
                board.releaseMenu(id);
            }
        }
    }

    /**
     * 准星移动时刷新落点高亮。落子前只有当前回合的玩家会看到高亮。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        updateHover(event.getPlayer());
    }

    /**
     * 同一 tick 内只允许刷新一次高亮：onMove（高频）与周期任务（4Hz）会对同一玩家重复触发，
     * 用服务器 tick 去重，避免同一 tick 内算两遍。返回 true 表示本次调用获得刷新权。
     */
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
        // 先按关卡过滤：玩家所在关卡没有棋盘时直接返回，连去重都不必做
        List<TabletopGamesBoard> list = boardsByLevel.get(player.getLevel());
        if (list == null || list.isEmpty()) {
            return;
        }
        if (!claimHover(player)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            TabletopGamesBoard board = list.get(i);
            if (board.isRunning() && board.inRange(player)) {
                board.updateHover(player);
            }
        }
    }

    /**
     * 周期任务：范围检测（离开自动离座）。菜单不再随进入范围自动弹出，需右键棋盘打开。
     */
    public void tick() {
        if (loadPending && !Server.getInstance().getLevels().isEmpty()) {
            loadPending = false;
            load();
        }
        if (dirty) {
            dirty = false;
            save();
        }
        // 直接遍历：tick 期间只做只读计算与「离座」这类不改动 boards 结构的操作
        // （破坏棋盘走 scheduleDelayedTask，在另一次任务里执行），无需每 tick 拷贝一份列表
        for (TabletopGamesBoard board : boards.values()) {
            // 高亮：只处理本棋盘当前该看高亮的那一名玩家（O(1) 查座位），不再遍历整关卡玩家表；
            // 与 onMove 共用 claimHover 去重，同一 tick 内不会重复计算
            UUID hoverId = board.hoverPlayerId();
            if (hoverId != null) {
                Player hover = Server.getInstance().getPlayer(hoverId).orElse(null);
                if (hover != null && board.inRange(hover) && claimHover(hover)) {
                    board.updateHover(hover);
                }
            }
            // 离座：只检查座位（≤2），不再扫描整关卡的玩家表
            board.collectLeftPlayers(leftScratch);
            for (int i = 0; i < leftScratch.size(); i++) {
                UUID id = leftScratch.get(i);
                board.leave(id);
                Player p = Server.getInstance().getPlayer(id).orElse(null);
                if (p != null) {
                    p.sendTip("§e你已离开棋盘范围，自动退出棋局。");
                }
            }
            // 兜底：菜单持有者超时或离开范围时，自动关闭其表单并释放占用
            board.tickMenuHolder();
            // 区块卸载会关闭非玩家实体（Nukkit 的 BaseFullChunk.unload），玩家回到范围后按需重建，
            // 否则重新进入区块会看到棋盘但棋子全部消失；重建时一并恢复棋子/最后一手/高亮。
            // 仅在实体确实缺失时才按需扫描本关卡玩家，平时不维护玩家集合
            if (board.isEntityMissing() && board.hasPlayerInRange()) {
                board.spawnEntity();
            }
            // 轮到机器人落子：停顿片刻后异步计算落点，避免阻塞主线程
            if (board.isAiPlaceTurn() && board.beginThinking()) {
                scheduleAiMove(board);
            } else if (board.needsAiSwapDecision() && board.beginThinking()) {
                scheduleAiDecision(board);
            }
        }
    }

    /**
     * 派发一次机器人落子：先延迟制造“思考”停顿，再在线程池中计算，最后回到主线程落子。
     */
    private void scheduleAiMove(TabletopGamesBoard board) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            int[][] snapshot = board.copyGrid();
            int aiColor = board.aiMoveColor();
            int level = board.getAiLevel();
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                int[] move = TabletopGamesAi.bestMove(snapshot, aiColor, level);
                Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                    board.finishThinking();
                    if (move != null && board.aiPlace(move[0], move[1])) {
                        reopenForSeats(board);
                    } else if (board.needsSwapDecision()) {
                        // 机器人落子后轮到对方做 Swap2 开局选择（对方是真人时才弹表单）
                        openSwapDecisionFor(board);
                    }
                }, false);
            }, true);
        }, AI_THINK_DELAY_TICKS);
    }

    /** 派发一次机器人 Swap2 开局选择：短暂停顿后直接在主线程决策。 */
    private void scheduleAiDecision(TabletopGamesBoard board) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            board.finishThinking();
            board.aiSwapDecide();
        }, AI_THINK_DELAY_TICKS);
    }

    /** 取（必要时分配）棋盘对应的存档编号。仅主线程调用。 */
    private int idFor(TabletopGamesBoard board) {
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
            plugin.getLogger().warning("删除棋盘存档失败：" + id + "（" + key + "）");
        }
    }

    /**
     * 主线程捕获的棋盘原始快照：只拷贝值（坐标、状态、座位、落子行编码），
     * 不持有 {@link TabletopGamesBoard} 引用，因此可以安全地交给异步线程做字符串格式化。
     */
    private static final class BoardSnapshot {
        final int id;
        final String level;
        final int x;
        final int y;
        final int z;
        final int boardSize;
        final boolean running;
        final int turn;
        final UUID winner;
        final int aiLevel;
        final int aiColor;
        final double betAmount;
        final double presetAmount;
        final int rule;
        final int swapPhase;
        final int swapPlaced;
        final int rotation;
        final List<UUID> seats;
        final List<Integer> colors;
        final Map<UUID, String> names;
        final int[] rows;

        BoardSnapshot(int id, TabletopGamesBoard board) {
            this.id = id;
            this.level = board.getLevel().getName();
            this.x = board.getBaseX();
            this.y = board.getBaseY();
            this.z = board.getBaseZ();
            this.boardSize = board.getBoardSize();
            this.running = board.isRunning();
            this.turn = board.getTurn();
            this.winner = board.getWinner();
            this.aiLevel = board.getAiLevel();
            this.aiColor = board.getAiColor();
            this.betAmount = board.getBetAmount();
            this.presetAmount = board.getPresetBet();
            this.rule = board.getRule();
            this.swapPhase = board.getSwapPhase();
            this.swapPlaced = board.getSwapPlaced();
            this.rotation = board.getRotation();
            this.seats = new ArrayList<>(board.getSeats());
            this.colors = new ArrayList<>(seats.size());
            for (UUID seatId : seats) {
                this.colors.add(board.seatColor(seatId));
            }
            this.names = board.copyKnownNames();
            this.rows = board.exportRows();
        }
    }

    /**
     * 在主线程把当前所有棋盘拷贝成不可变快照。
     * <p>
     * 只做字段读取与行编码（每行 15 次乘加），字符串拼接与 UUID 格式化留到异步线程，
     * 尽量缩短主线程在存档上的停留时间。
     * <p>
     * 只有自上次快照后发生过变更（{@link TabletopGamesBoard#consumeStateDirty()}）的棋盘才重新读取字段、
     * 重编码落子行；未变更的棋盘直接复用 {@link #snapshotCache} 里的上一份快照。快照本身不可变
     * （座位/名字/落子行都是拷贝），因此可安全地在多次落盘与异步格式化之间共享。
     */
    private List<BoardSnapshot> capture() {
        List<BoardSnapshot> snapshots = new ArrayList<>(boards.size());
        for (TabletopGamesBoard board : boards.values()) {
            boolean changed = board.consumeStateDirty();
            String key = board.getKey();
            BoardSnapshot cached = snapshotCache.get(key);
            if (cached == null || changed) {
                cached = new BoardSnapshot(idFor(board), board);
                snapshotCache.put(key, cached);
            }
            snapshots.add(cached);
        }
        return snapshots;
    }

    /** 把一个棋盘快照拼成存档文件文本（纯计算，可在异步线程执行）。 */
    private static String document(BoardSnapshot s) {
        BoardStorage.Document doc = new BoardStorage.Document(
                "五子棋棋盘存档（由插件自动生成；手改后请保证 YAML 合法）",
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
        board.put("size", s.boardSize);
        board.put("rows", intsOf(s.rows));
        doc.section("棋盘规格：size 为边长（2 = 标准 15x15，3 = 大棋盘）；rows 为逐行落子编码（每行按 3 进制表示该行 15 格）",
                "board", board);

        Map<String, Object> game = new LinkedHashMap<>();
        game.put("state", s.running ? "playing" : (s.winner != null ? "finished" : "waiting"));
        game.put("turn", s.turn);
        game.put("winner", s.winner == null ? "" : s.winner.toString());
        game.put("rule", s.rule == TabletopGamesBoard.RULE_SWAP2 ? "swap2" : "casual");
        Map<String, Object> swap = new LinkedHashMap<>();
        swap.put("phase", s.swapPhase);
        swap.put("placed", s.swapPlaced);
        game.put("swap", swap);
        doc.section("对局进度：state = waiting(未开局) / playing(对局中) / finished(已结束)；turn = 轮到哪一方（0 黑 / 1 白）；"
                + "winner = 获胜方 UUID（未结束为空）；rule = casual(休闲) / swap2；swap 为 Swap2 开局阶段与已落子数",
                "game", game);

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("level", s.aiLevel);
        ai.put("color", s.aiColor);
        doc.section("人机设置：level 为机器人强度（0 = 无人机）；color 为机器人执子（0 黑 / 1 白）",
                "ai", ai);

        Map<String, Object> bet = new LinkedHashMap<>();
        bet.put("amount", s.betAmount);
        bet.put("preset", s.presetAmount);
        doc.section("下注：amount = 本局双方各自已扣押的金额；preset = 预先设置的每局金额（0 = 免费局）",
                "bet", bet);

        List<Map<String, Object>> players = new ArrayList<>(s.seats.size());
        for (int i = 0; i < s.seats.size(); i++) {
            UUID id = s.seats.get(i);
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("uuid", id.toString());
            player.put("name", s.names.getOrDefault(id, ""));
            player.put("color", s.colors.get(i));
            players.add(player);
        }
        doc.section("座位：按入座顺序排列；uuid 为玩家唯一 id，name 为入座时的名字（展示用），color 为执子（0 黑 / 1 白）",
                "players", players);

        return doc.build();
    }

    private static List<Integer> intsOf(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    public void save() {
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
                plugin.getLogger().warning("保存棋盘数据失败：" + STORAGE_KIND + " #" + s.id);
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
                plugin.getLogger().warning("读取棋盘存档失败（YAML 解析失败或文件不可读）：" + STORAGE_KIND + " #" + id);
            }
        }
        return saved;
    }
    private void restoreBoard(SavedBoard data) {
        Level level = Server.getInstance().getLevelByName(data.level);
        if (level == null) {
            plugin.getLogger().warning("棋盘所在世界未加载，跳过：" + data.level);
            return;
        }
        // 4x4 棋盘已下线：旧存档里残留的该尺寸棋盘直接跳过，避免恢复出无方块可用的空棋盘
        if (data.boardSize != 2 && data.boardSize != 3) {
            plugin.getLogger().warning("跳过不再支持的棋盘尺寸 " + data.boardSize + "：" + data.level
                    + " (" + data.x + ", " + data.y + ", " + data.z + ")");
            return;
        }
        TabletopGamesBoard board = new TabletopGamesBoard(this, level, data.x, data.y, data.z, data.boardSize, data.rotation);
        board.restore(data.seats, data.colors, data.names,
                data.rows != null ? data.rows : new int[TabletopGamesBoard.SIZE],
                data.running, data.turn, data.winner, data.betAmount, data.presetAmount,
                data.rule, data.swapPhase, data.swapPlaced);
        board.restoreAi(data.aiLevel, data.aiColor);
        // 自定义方块 id 重启后可能重新分配，这里按坐标重放一遍，确保棋盘方块仍然有效
        placeBlocks(level, data.x, data.y, data.z, data.boardSize, data.rotation);
        board.spawnEntity();
        boards.put(board.getKey(), board);
        indexBoard(board);
        // 沿用在存档里占用的编号，并保证后续新棋盘不会与之冲突
        int id = data.id > 0 ? data.id : nextBoardId;
        boardIds.put(board.getKey(), id);
        if (id >= nextBoardId) {
            nextBoardId = id + 1;
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 磁盘上的一条棋盘记录（一个文件一个棋盘）。 */
    private static final class SavedBoard {
        final int id;
        final String level;
        final int x;
        final int y;
        final int z;
        final boolean running;
        final int turn;
        final UUID winner;
        final int aiLevel;
        final int aiColor;
        final int boardSize;
        final double betAmount;
        final double presetAmount;
        final int rule;
        final int swapPhase;
        final int swapPlaced;
        final int rotation;
        final List<UUID> seats = new ArrayList<>();
        final Map<UUID, Integer> colors = new HashMap<>();
        final Map<UUID, String> names = new LinkedHashMap<>();
        final int[] rows;

        /**
         * 按 {@code position / board / game / ai / bet / players} 分节读取。
         * 字段语义见 {@link #document(BoardSnapshot)} 生成的注释。
         */
        SavedBoard(int id, Map<?, ?> map) {
            this.id = id;

            Map<?, ?> position = BoardStorage.mapValue(map, "position");
            this.level = BoardStorage.stringValue(position, "world", "");
            this.x = BoardStorage.intValue(position, "x", 0);
            this.y = BoardStorage.intValue(position, "y", 0);
            this.z = BoardStorage.intValue(position, "z", 0);
            this.rotation = BoardStorage.intValue(position, "rotation", 0);

            Map<?, ?> board = BoardStorage.mapValue(map, "board");
            this.boardSize = BoardStorage.intValue(board, "size", 2);
            this.rows = new int[TabletopGamesBoard.SIZE];
            if (board.get("rows") instanceof List<?> rowList) {
                for (int i = 0; i < this.rows.length && i < rowList.size(); i++) {
                    Object value = rowList.get(i);
                    this.rows[i] = value instanceof Number number ? number.intValue() : 0;
                }
            }

            Map<?, ?> game = BoardStorage.mapValue(map, "game");
            String state = BoardStorage.stringValue(game, "state", "waiting");
            this.running = "playing".equals(state);
            this.winner = "finished".equals(state)
                    ? parseUuid(BoardStorage.stringValue(game, "winner", ""))
                    : null;
            this.turn = BoardStorage.intValue(game, "turn", TabletopGamesBoard.BLACK);
            this.rule = "swap2".equals(BoardStorage.stringValue(game, "rule", "casual"))
                    ? TabletopGamesBoard.RULE_SWAP2 : TabletopGamesBoard.RULE_CASUAL;
            Map<?, ?> swap = BoardStorage.mapValue(game, "swap");
            this.swapPhase = BoardStorage.intValue(swap, "phase", 0);
            this.swapPlaced = BoardStorage.intValue(swap, "placed", 0);

            Map<?, ?> ai = BoardStorage.mapValue(map, "ai");
            this.aiLevel = BoardStorage.intValue(ai, "level", 0);
            this.aiColor = BoardStorage.intValue(ai, "color", 0);

            Map<?, ?> bet = BoardStorage.mapValue(map, "bet");
            this.betAmount = BoardStorage.doubleValue(bet, "amount", 0);
            this.presetAmount = BoardStorage.doubleValue(bet, "preset", 0);

            if (map.get("players") instanceof List<?> playerList) {
                for (Object item : playerList) {
                    if (item instanceof Map<?, ?> player) {
                        UUID id2 = parseUuid(BoardStorage.stringValue(player, "uuid", ""));
                        if (id2 != null) {
                            this.seats.add(id2);
                            this.colors.put(id2,
                                    BoardStorage.intValue(player, "color", TabletopGamesBoard.BLACK));
                            this.names.put(id2, BoardStorage.stringValue(player, "name", ""));
                        }
                    }
                }
            }
        }
    }
}