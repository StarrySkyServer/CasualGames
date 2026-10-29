package com.tabletopgames;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.custom.EntityManager;
import cn.nukkit.inventory.ShapedRecipe;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import com.tabletopgames.board.GoBoard13Block;
import com.tabletopgames.board.GoBoard19BigBlock;
import com.tabletopgames.board.GoBoard19Block;
import com.tabletopgames.board.GoBoard9Block;
import com.tabletopgames.board.DoushouqiBoardBlock;
import com.tabletopgames.board.DdzTableBlocks;
import com.tabletopgames.board.PokerTableBlocks;
import com.tabletopgames.board.JunqiBoardBlock;
import com.tabletopgames.board.OthelloBoardBlock;
import com.tabletopgames.board.TabletopGamesBoard3x3Block;
import com.tabletopgames.board.TabletopGamesBoardBlock;
import com.tabletopgames.board.UnoTableBlocks;
import com.tabletopgames.board.XiangqiBoardBlock;
import com.tabletopgames.command.TabletopGamesCommand;
import com.tabletopgames.entity.DoushouqiPieceEntity;
import com.tabletopgames.entity.DdzFlyCardEntity;
import com.tabletopgames.entity.DdzTableEntity;
import com.tabletopgames.entity.GoStoneEntity;
import com.tabletopgames.entity.JunqiPieceEntity;
import com.tabletopgames.entity.OthelloDiscEntity;
import com.tabletopgames.entity.TabletopGamesStoneEntity;
import com.tabletopgames.entity.UnoFlyCardEntity;
import com.tabletopgames.entity.UnoTableEntity;
import com.tabletopgames.entity.XiangqiPieceEntity;
import com.tabletopgames.game.DdzManager;
import com.tabletopgames.game.PokerManager;
import com.tabletopgames.game.DoushouqiManager;
import com.tabletopgames.game.GoManager;
import com.tabletopgames.game.JunqiManager;
import com.tabletopgames.game.OthelloManager;
import com.tabletopgames.game.TabletopGamesManager;
import com.tabletopgames.game.LocatorTitleService;
import com.tabletopgames.guard.UnknownBlockGuard;
import com.tabletopgames.game.UnoManager;
import com.tabletopgames.game.XiangqiManager;
import com.tabletopgames.item.DdzTableItem;
import com.tabletopgames.item.PokerTableItem;
import com.tabletopgames.item.DoushouqiBoardItem;
import com.tabletopgames.item.GoBoard13Item;
import com.tabletopgames.item.GoBoard19BigItem;
import com.tabletopgames.item.GoBoard19Item;
import com.tabletopgames.item.GoBoard9Item;
import com.tabletopgames.item.JunqiBoardItem;
import com.tabletopgames.item.LocatorGuard;
import com.tabletopgames.storage.Database;
import com.tabletopgames.storage.JunqiFormationDao;
import com.tabletopgames.storage.PlayerSettingsDao;
import com.tabletopgames.item.LocatorItem;
import com.tabletopgames.item.OthelloBoardItem;
import com.tabletopgames.item.TabletopGamesBoard3x3Item;
import com.tabletopgames.item.TabletopGamesBoardItem;
import com.tabletopgames.item.UnoTableItem;
import com.tabletopgames.item.UnoTableRecipe;
import com.tabletopgames.item.XiangqiBoardItem;

/**
 * 五子棋插件入口。
 * <p>
 * 自定义实体、方块、物品必须在 {@code onLoad()} 中注册：此时 CustomBlockManager 已初始化
 * 但注册表尚未关闭，且早于 {@code EntityProperty.buildPacket()}，属性才能同步到客户端。
 */
public class TabletopGamesPlugin extends PluginBase {

    private TabletopGamesManager manager;
    private XiangqiManager xiangqiManager;
    private OthelloManager othelloManager;
    private GoManager goManager;
    private DoushouqiManager doushouqiManager;
    private JunqiManager junqiManager;
    private UnoManager unoManager;
    private DdzManager ddzManager;
    /** 德州扑克牌桌管理器（与斗地主共用实体，mode=1）。 */
    private PokerManager pokerManager;

    /**
     * 定位器准星开关（蹲下 + 使用键 → broadcastTitle）。
     * 见 {@link com.tabletopgames.game.LocatorTitleService}：只在座位上显示。
     */
    private LocatorTitleService locatorTitle;

    /** {@link #sweepLocators()} 的计数（每 40 次 tickAll ≈ 10 秒扫一次定位器残留）。 */
    private int locatorSweepCalls;

    /** SQLite 数据库（2026-10-22 引入，与 Territory 同选型）：玩家设置 / 牌桌配置 / 棋盘数据。 */
    private Database database;
    private PlayerSettingsDao playerSettings;
    private JunqiFormationDao junqiFormations;

    /** 已注册的 UNO 牌桌合成配方（null = 当前未注册）；由 {@link #syncUnoRecipe()} 维护。 */
    private ShapedRecipe unoRecipe;

    /** /tabletopgames 命令处理器（命令逻辑抽到 {@link com.tabletopgames.command.TabletopGamesCommand}）。 */
    private TabletopGamesCommand commandHandler;

    /** 当前插件主管理器（含全局配置），供设置表单等入口获取。 */
    public TabletopGamesManager getManager() {
        return manager;
    }

    /** 斗地主管理器（命令层发牌桌 / 查牌桌用）。 */
    public DdzManager getDdzManager() {
        return ddzManager;
    }

    public PokerManager getPokerManager() {
        return pokerManager;
    }

    /**
     * 这个玩家是否还坐在某张棋桌 / 牌桌上（{@code LocatorService} 回收定位器时的唯一判据）。
     *
     * <p>⚠️ 与 {@code LocatorTitleService.isSeated} 是同一条口径，别只改一边：
     * 那边决定「准星显不显示」，这边决定「定位器收不收」。</p>
     */
    public boolean isSeatedAnywhere(cn.nukkit.Player player) {
        if (player == null) {
            return false;
        }
        java.util.UUID id = player.getUniqueId();
        return (manager != null && manager.isSeated(id))
                || (xiangqiManager != null && xiangqiManager.isSeated(id))
                || (othelloManager != null && othelloManager.isSeated(id))
                || (goManager != null && goManager.isSeated(id))
                || (doushouqiManager != null && doushouqiManager.isSeated(id))
                || (junqiManager != null && junqiManager.isSeated(id))
                || (unoManager != null && unoManager.getTableOf(id) != null)
                || com.tabletopgames.game.DdzGame.of(player) != null
                || com.tabletopgames.game.PokerGame.of(player) != null;
    }

    @Override
    public void onLoad() {
        TabletopGamesStoneEntity.registerProperties();
        EntityManager.get().registerDefinition(TabletopGamesStoneEntity.DEF);
        XiangqiPieceEntity.registerProperties();
        EntityManager.get().registerDefinition(XiangqiPieceEntity.DEF);
        DoushouqiPieceEntity.registerProperties();
        EntityManager.get().registerDefinition(DoushouqiPieceEntity.DEF);
        // 军旗（二国陆战棋）：50 槽位打包成 17 个整型属性 + 3 个标记 + turn
        JunqiPieceEntity.registerProperties();
        EntityManager.get().registerDefinition(JunqiPieceEntity.DEF);
        OthelloDiscEntity.registerProperties();
        EntityManager.get().registerDefinition(OthelloDiscEntity.DEF);
        GoStoneEntity.registerProperties();
        EntityManager.get().registerDefinition(GoStoneEntity.DEF);
        // UNO 牌桌：一个实体承载整桌（21 个整型属性），必须在任何实体生成前注册
        UnoTableEntity.registerProperties();
        EntityManager.get().registerDefinition(UnoTableEntity.DEF);
        // 飞行牌实体：发牌/摸牌/定庄的"飞入"由它承担。**必须独立于牌桌** ——
        // 牌桌 geo 已 398 骨骼、pre_animation 已 292 条，再加就撞客户端上限（真机整排牌塌到桌心）。
        UnoFlyCardEntity.registerProperties();
        EntityManager.get().registerDefinition(UnoFlyCardEntity.DEF);
        // 斗地主发牌飞牌实体：同上，独立于牌桌（牌桌是模组那份 292 骨骼/303 条，没余量）
        DdzFlyCardEntity.registerProperties();
        EntityManager.get().registerDefinition(DdzFlyCardEntity.DEF);
        // 斗地主牌桌：15 个整型属性，位打包见 DdzTableEntity 类注释
        DdzTableEntity.registerProperties();
        EntityManager.get().registerDefinition(DdzTableEntity.DEF);
        TabletopGamesBoardBlock.register();
        TabletopGamesBoard3x3Block.register();
        XiangqiBoardBlock.register();
        DoushouqiBoardBlock.register();
        // 军旗：3×3 = 9 段 × 4 朝向，每段独立几何与 128×128 顶面贴图
        JunqiBoardBlock.register();
        OthelloBoardBlock.register();
        GoBoard9Block.register();
        GoBoard13Block.register();
        GoBoard19Block.register();
        GoBoard19BigBlock.register();
        UnoTableBlocks.register();
        DdzTableBlocks.register();
        // 德州扑克：3 个方块复用斗地主 9 个几何，只换红绒贴图
        PokerTableBlocks.register();
        Item.registerCustomItem(TabletopGamesBoardItem.class);
        Item.registerCustomItem(TabletopGamesBoard3x3Item.class);
        Item.registerCustomItem(XiangqiBoardItem.class);
        Item.registerCustomItem(DoushouqiBoardItem.class);
        Item.registerCustomItem(JunqiBoardItem.class);
        Item.registerCustomItem(OthelloBoardItem.class);
        Item.registerCustomItem(GoBoard9Item.class);
        Item.registerCustomItem(GoBoard13Item.class);
        Item.registerCustomItem(GoBoard19Item.class);
        Item.registerCustomItem(GoBoard19BigItem.class);
        Item.registerCustomItem(UnoTableItem.class);
        Item.registerCustomItem(DdzTableItem.class);
        Item.registerCustomItem(PokerTableItem.class);
        // 「定位器」：UNO 牌桌的持握道具，手持它右键能保证服务端收到信号（见 LocatorItem）
        Item.registerCustomItem(LocatorItem.class);

        // ⚠️ **不要再调 Item.addCreativeItem**（2026-10-22 用户反馈「凭空多出一个斗地主牌桌」）：
        // 每个物品的 CustomItemDefinition 都声明了 menu_category = ITEMS，客户端会**自动**
        // 把它们收进创造栏；再手动 addCreativeItem 就变成两份，而手动那份是空壳
        // （拿到背包点不动 / 放置无反应）。所以创造栏只靠物品定义，这里只做注册。
        // 2026-10-27 用户要求「控制台别刷那么多」：清单降到 debug，info 只留一句
        getLogger().info("已注册自定义物品、实体与方块");
        getLogger().debug("实体 / 方块 / 物品清单：" + TabletopGamesStoneEntity.IDENTIFIER
                + " / " + XiangqiPieceEntity.IDENTIFIER
                + " / " + DoushouqiPieceEntity.IDENTIFIER
                + " / " + OthelloDiscEntity.IDENTIFIER
                + " / " + GoStoneEntity.IDENTIFIER
                + " / " + UnoTableEntity.IDENTIFIER
                + " / " + UnoFlyCardEntity.IDENTIFIER
                + " / " + DdzFlyCardEntity.IDENTIFIER
                + " / " + DdzTableEntity.IDENTIFIER
                + "、道具 " + LocatorItem.IDENTIFIER
                + "、方块 " + TabletopGamesBoardBlock.IDENTIFIER + " / " + TabletopGamesBoard3x3Block.IDENTIFIER
                + " / " + XiangqiBoardBlock.IDENTIFIER
                + " / " + DoushouqiBoardBlock.IDENTIFIER
                + " / " + OthelloBoardBlock.IDENTIFIER
                + " / " + GoBoard9Block.IDENTIFIER
                + " / " + GoBoard13Block.IDENTIFIER
                + " / " + GoBoard19Block.IDENTIFIER
                + " / " + GoBoard19BigBlock.IDENTIFIER
                + " / " + UnoTableBlocks.CENTER
                + " / " + DdzTableBlocks.CENTER
                + "、物品 " + TabletopGamesBoardItem.IDENTIFIER + " / " + TabletopGamesBoard3x3Item.IDENTIFIER
                + " / " + XiangqiBoardItem.IDENTIFIER
                + " / " + DoushouqiBoardItem.IDENTIFIER
                + " / " + OthelloBoardItem.IDENTIFIER
                + " / " + GoBoard9Item.IDENTIFIER
                + " / " + GoBoard13Item.IDENTIFIER
                + " / " + GoBoard19Item.IDENTIFIER
                + " / " + GoBoard19BigItem.IDENTIFIER
                + " / " + UnoTableItem.IDENTIFIER
                + " / " + DdzTableItem.IDENTIFIER);
    }

    @Override
    public void onEnable() {
        // MOT 核心缺陷修复（2026-10-25）：注册自定义方块会让核心重建 palette，旧版本的原版 state
        // 可能被丢掉 ⇒ 旧区块读成 minecraft:info_update(1623) ⇒ 写盘算不出 legacyId ⇒ 整个区块永久卡。
        // 上游已在 2026-10-20 的 bf3aa38 修掉（remapRuntimeIds 先复制再切换）。
        // 下面两件都是「只在老核心上才动」的自适应补丁：
        //   LegacyStateRestorer —— 世界加载时把缺的历史 state 按同名方块补回（主力）
        //   UnknownBlockGuard   —— 连名字都没了的方块按空气写，避免区块锁死（最后防线）
        boolean coreMissingDefaultLegacy = UnknownBlockGuard.isCoreMissingDefaultLegacy();
        if (coreMissingDefaultLegacy) {
            // ⚠️ 只在真缺时才装：代理会给每次 runtimeToLegacyId 加一层反射，
            // 新核心（bf3aa38 之后）本来就不缺，装上纯属白付开销。
            UnknownBlockGuard.install(getLogger());
            getLogger().warning("[TabletopGames] 核心缺少 minecraft:info_update 的 legacy 映射（MOT 老版本缺陷，"
                    + "10-20 的 bf3aa38 已修），已启用两块兼容：历史 state 按同名补回 + 未知方块按空气兜底，"
                    + "避免整个区块永久卡死。");
        }
        getServer().getPluginManager().registerEvents(new com.tabletopgames.guard.LegacyStateRestorer(getLogger()), this);

        // 数据库先就绪：后面的 manager / 个人设置都从它读（打不开也不阻塞插件加载）
        this.database = new Database(this);
        this.database.open();
        // 七套棋盘的存档也走这个库（board_state 表）：2026-10-27 从 data/<棋类>/*.yml 搬过来，
        // 必须在任何 manager 的 load() 之前绑定（老文件会在第一次读取时自动导入）
        com.tabletopgames.storage.BoardStorage.bind(this, this.database);
        this.playerSettings = new PlayerSettingsDao(this.database);
        this.junqiFormations = new JunqiFormationDao(this.database);

        this.manager = new TabletopGamesManager(this);
        this.commandHandler = new TabletopGamesCommand(this);
        // 配置变更后（reload / 表单保存）：同步 UNO 牌桌配方 + 让在座牌桌按新回合时间重算时限。
        // ⚠️ 本回调注册在 loadConfig() 之前：首次加载时 unoManager 尚未创建，故回调里判空。
        this.manager.setConfigListener(() -> {
            syncUnoRecipe();
            if (this.unoManager != null) {
                this.unoManager.onConfigReloaded();
            }
            if (this.pokerManager != null) {
                this.pokerManager.onConfigReloaded();
            }
        });
        // 先加载配置，保证后续 tick 的范围检测能读到玩家识别半径（加载完即触发一次回调）
        this.manager.loadConfig();
        getServer().getPluginManager().registerEvents(this.manager, this);
        // 插件在 STARTUP 阶段启用，此时关卡还没加载，等关卡就绪后由 tick 恢复已保存的棋盘
        this.manager.requestLoad();

        // 中国象棋：配置见全插件唯一的 config.yml（xiangqi 小节），存档在 data/xiangqi/<编号>.yml
        this.xiangqiManager = new XiangqiManager(this, this.manager);
        getServer().getPluginManager().registerEvents(this.xiangqiManager, this);
        this.xiangqiManager.requestLoad();

        // 黑白棋（奥赛罗）：配置见 config.yml（othello 小节），存档在 data/othello/<编号>.yml
        this.othelloManager = new OthelloManager(this, this.manager);
        getServer().getPluginManager().registerEvents(this.othelloManager, this);
        this.othelloManager.requestLoad();

        // 围棋：配置见 config.yml（go 小节），存档在 data/go/<编号>.yml
        this.goManager = new GoManager(this, this.manager);
        getServer().getPluginManager().registerEvents(this.goManager, this);
        this.goManager.requestLoad();

        // 斗兽棋：配置见 config.yml（doushouqi 小节），存档在 data/doushouqi/<编号>.yml
        this.doushouqiManager = new DoushouqiManager(this, this.manager);
        getServer().getPluginManager().registerEvents(this.doushouqiManager, this);
        this.doushouqiManager.requestLoad();

        // 军旗：配置见 config.yml（junqi 小节），存档在 data/junqi/<编号>.yml
        this.junqiManager = new JunqiManager(this, this.manager, this.junqiFormations, this.playerSettings);
        getServer().getPluginManager().registerEvents(this.junqiManager, this);
        this.junqiManager.requestLoad();

        // UNO：配置见 config.yml（uno 小节：半径 / 菜单锁 / 抽水 / 倍数 k 区间 / 回合时间），
        // 存档在 data/uno/<编号>.yml
        this.unoManager = new UnoManager(this, this.manager);
        getServer().getPluginManager().registerEvents(this.unoManager, this);
        this.unoManager.requestLoad();

        // 斗地主：配置见 config.yml（ddz 小节：半径 / 菜单锁 / 抽水 / 4 档底分 / 倍数上限 / 时限 / 发牌节奏）。
        // **牌局状态不落盘**（重启即重开），但**牌桌本体（3x3 方块 + 位置 + 场次）要活过重启**：
        // 存在 SQLite 的 ddz_table_config，世界就绪后由 requestLoad → load() 按坐标重放方块。
        // （2026-10-23 修：之前关服走 destroy() 把方块写成空气、启动又不恢复 ⇒ 重启后牌桌消失）
        this.ddzManager = new DdzManager(this, this.manager, this.database);
        getServer().getPluginManager().registerEvents(this.ddzManager, this);
        this.ddzManager.requestLoad();

        // 德州扑克：与斗地主共用 tabletopgame:ddz_table 实体（mode=1），
        // 牌桌本体（3x3 方块 + 场次）存 poker_table_config，启动按坐标重放。
        this.pokerManager = new PokerManager(this, this.manager, this.database);
        getServer().getPluginManager().registerEvents(this.pokerManager, this);
        this.pokerManager.requestLoad();

        // 定位器准星开关（2026-10-22）：蹲下 + 使用键切换，开启后<b>只在座位上</b>显示
        // 一条 broadcastTitle（主 "§f" / 副 "+\n\n\n"）。放在最后创建：要用到上面六个 manager。
        this.locatorTitle = new LocatorTitleService(this.manager, this.xiangqiManager,
                this.othelloManager, this.goManager, this.doushouqiManager, this.junqiManager,
                this.unoManager, this.playerSettings);
        getServer().getPluginManager().registerEvents(this.locatorTitle, this);

        // 定位器发放 / 回收（2026-10-27 用户要求）：棋类与德州扑克入座发一把、离座收走。
        // 「离座」时要判断玩家是不是还坐在别的桌上（同时坐两桌很常见），判据只有插件知道
        // —— 它认得全部 manager，所以在这里注册（见 LocatorService.setSeatCheck）。
        com.tabletopgames.item.LocatorService.setSeatCheck(this::isSeatedAnywhere);

        // 定位器守门人（2026-10-22）：开局那批带 ItemLock + KeepOnDeath 的定位器
        // 不能丢 / 不能合成 / 死亡不掉落，见 LocatorGuard。
        getServer().getPluginManager().registerEvents(new LocatorGuard(), this);

        // 统一调度：七套棋牌共用一个 repeating task，见 tickAll 的注释
        // 九个 manager（五子棋 / 象棋 / 黑白棋 / 围棋 / 斗兽棋 / 军旗 / UNO / 斗地主 / 扑克）
        // 稍后各自恢复存档，恢复完只在这里汇总成一行日志（见 StartupReport）
        com.tabletopgames.storage.StartupReport.expect(9);
        getServer().getScheduler().scheduleRepeatingTask(this, this::tickAll, 5);
        Banner.print(this);
    }

    /**
     * 统一调度：一个 repeating task（5 服务端 tick = 250 ms）驱动全部棋牌。
     *
     * <p>原来七个 manager 各注册一个 task，同一帧里连着进来七次调度器回调；合并成一个之后：
     * ① 调度器只维护一个任务；② 以后要做「分级周期」（对局 250ms / 无人 5s）只需改这里两行；
     * ③ 顺序固定（棋盘 → 棋类 → 牌类），排查「谁把主线程吃了」不用再猜顺序。</p>
     *
     * <p>⚠️ 每个 manager <b>单独</b> try/catch：任何一个抛异常都不能让其余棋牌整轮停下。
     * 2026-10-20 就踩过「新增斗地主后 section 抛异常 ⇒ onEnable 半途而废 ⇒ 六套 manager 全没注册」。
     * 斗地主那边自己包了一层（要打牌桌 key），这里再包一层不重复。</p>
     *
     * <p>⚠️ <b>周期是服务端 tick，不是游戏 tick</b>：250 ms 一轮，与各 manager 内部的
     * {@code TICKS_PER_SECOND = 4} 是同一件事的两种说法。改周期前先读
     * {@code 文档/性能优化方案_2026-10-21.md} 第 7 节 —— 直接把 5 改成 20 会把发牌、hover、
     * 旁观者动画全部拉长四倍，而且不报错。</p>
     */
    private void tickAll() {
        try {
            this.manager.tick();
        } catch (RuntimeException e) {
            getLogger().error("五子棋/四子棋 tick 异常", e);
        }
        try {
            this.xiangqiManager.tick();
        } catch (RuntimeException e) {
            getLogger().error("中国象棋 tick 异常", e);
        }
        try {
            this.othelloManager.tick();
        } catch (RuntimeException e) {
            getLogger().error("黑白棋 tick 异常", e);
        }
        try {
            this.goManager.tick();
        } catch (RuntimeException e) {
            getLogger().error("围棋 tick 异常", e);
        }
        try {
            this.doushouqiManager.tick();
        } catch (RuntimeException e) {
            getLogger().error("斗兽棋 tick 异常", e);
        }
        try {
            this.junqiManager.tick();
        } catch (RuntimeException e) {
            getLogger().error("军旗 tick 异常", e);
        }
        try {
            this.unoManager.tick();
        } catch (RuntimeException e) {
            getLogger().error("UNO tick 异常", e);
        }
        try {
            this.ddzManager.tick();
        } catch (RuntimeException e) {
            getLogger().error("斗地主 tick 异常", e);
        }
        try {
            this.pokerManager.tick();
        } catch (RuntimeException e) {
            getLogger().error("德州扑克 tick 异常", e);
        }
        try {
            // 定位器准星：每 4 次调用（20 tick = 1 秒）重发一次标题，防淡出
            if (this.locatorTitle != null) {
                this.locatorTitle.tick();
            }
        } catch (RuntimeException e) {
            getLogger().error("定位器准星 tick 异常", e);
        }
        // 定位器残留清理：每 40 次调用（200 tick = 10 秒）扫一遍
        if (++this.locatorSweepCalls >= 40) {
            this.locatorSweepCalls = 0;
            try {
                this.sweepLocators();
            } catch (RuntimeException e) {
                getLogger().error("定位器回收 tick 异常", e);
            }
        }
        // 启动恢复日志的兜底（九个 manager 有一个卡住没汇报时也要打出那一行）
        try {
            com.tabletopgames.storage.StartupReport.tick(this);
        } catch (RuntimeException e) {
            getLogger().error("启动恢复汇总 tick 异常", e);
        }
    }

    /**
     * 定位器残留清理（2026-10-27）。
     *
     * <p>正常路径是「离座时 {@code join/leave} 里当场收」，但有几条路走不到那里：
     * 棋盘被拆（{@code remove()} 不清 seats）、玩家断线（回包时人已离线，改不了背包）、
     * 被别的插件传送走。这些情况下定位器会一直躺在背包里 —— 这里每 10 秒兜一次底：
     * <b>不坐在任何座位上就收走</b>。判据和 {@code LocatorService.release} 是同一条。</p>
     */
    private void sweepLocators() {
        for (cn.nukkit.Player player : getServer().getOnlinePlayers().values()) {
            if (isSeatedAnywhere(player)) {
                continue;
            }
            // take 内部：背包里没有定位器会直接返回，不会白发包
            com.tabletopgames.item.LocatorService.take(player);
        }
    }

    /**
     * 按配置同步「UNO 牌桌物品是否可合成」（配置项 = {@code craftable}，默认 false）。
     * <p>
     * false（默认）时保证工作台里没有任何 UNO 牌桌配方，牌桌只能由 OP 用
     * {@code /tabletopgame uno} 发放；改为 true 后（UNO 菜单「全局配置」表单或编辑
     * config.yml 的 uno 小节再 {@code /tabletopgame reload}）即为 3 个青色地毯 +
     * 3 个深色橡木原木 + 3 个橡木板。
     * <p>
     * 内部记录已注册的那个 {@link ShapedRecipe} 实例，因此本方法可反复调用而不会重复注册
     * （{@link cn.nukkit.inventory.CraftingManager#registerRecipe} 不是幂等的）。
     */
    private void syncUnoRecipe() {
        boolean want = this.manager != null
                && this.manager.unoConfig() != null
                && this.manager.unoConfig().isCraftable();
        if (want == (this.unoRecipe != null)) {
            return;
        }
        if (want) {
            this.unoRecipe = UnoTableRecipe.create();
            getServer().getCraftingManager().registerRecipe(this.unoRecipe);
            getLogger().info("已注册 UNO 牌桌合成配方（craftable=true，青色地毯版）。");
        } else {
            boolean removed = getServer().getCraftingManager().unregisterRecipe(this.unoRecipe);
            this.unoRecipe = null;
            getLogger().info("已注销 UNO 牌桌合成配方（craftable=false，removed=" + removed + "）。");
        }
    }

    @Override
    public void onDisable() {
        // 先停 AI 线程池：卸载后还跑着的推演会往一个已卸载的插件里回调
        com.tabletopgames.ai.AiThinkDispatcher.shutdown();
        if (this.manager != null) {
            // 关服兜底：同步等待写盘完成
            this.manager.saveNow();
        }
        if (this.xiangqiManager != null) {
            this.xiangqiManager.saveNow();
        }
        if (this.othelloManager != null) {
            this.othelloManager.saveNow();
        }
        if (this.goManager != null) {
            this.goManager.saveNow();
        }
        if (this.doushouqiManager != null) {
            this.doushouqiManager.saveNow();
        }
        if (this.junqiManager != null) {
            this.junqiManager.shutdown();
        }
        if (this.unoManager != null) {
            this.unoManager.saveNow();
        }
        if (this.ddzManager != null) {
            this.ddzManager.shutdown();
        }
        if (this.pokerManager != null) {
            this.pokerManager.shutdown();
        }
        if (this.database != null) {
            this.database.close();
        }
        getLogger().info("TabletopGamesPlugin 已关闭");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return this.commandHandler.onCommand(sender, command, label, args);
    }
}