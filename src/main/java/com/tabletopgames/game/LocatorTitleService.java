package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import com.tabletopgames.item.LocatorItem;
import com.tabletopgames.storage.PlayerSettingsDao;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「定位器」的第二功能：<b>蹲下 + 使用键</b>开关屏幕中央的十字准星（2026-10-22 用户要求）。
 *
 * <p>这是 2026-10-15 那版「定位器激光开关」（{@code UnoManager.toggleLaser}，见提交
 * {@code 9926b09}）的复活：手势、默认开启、10 tick 防抖、{@code sendTip} 回报全部照旧，
 * 只是被开关的东西从「牌面上的激光点」换成了「一条 broadcastTitle」。</p>
 *
 * <h3>显示什么</h3>
 * {@code Server.broadcastTitle}：主标题 {@code §f}（只有颜色码，等于不显示文字）、
 * 副标题 {@code +\n\n\n}（一个加号 + 三个换行，把加号顶到屏幕正中）。
 * 收件人收窄成「本人」一个，所以效果是纯客户端的准星叠层，不会骚扰别人。
 *
 * <h3>三条铁律</h3>
 * <ol>
 *   <li><b>手持定位器 + 在座位上，才显示</b>：开关默认是开的，但 {@link #holdsLocator} 或
 *       {@link #isSeated} 有一个不成立就一条标题都不发，还会主动 {@link Player#clearTitle()}
 *       把已经画上去的擦掉。用户原话：「即使开了，触发机制也只能是，玩家待在某一棋盘的座位上。
 *       其他情况不会有显示」，2026-10-22 又补了一条「手持定位器才触发」。</li>
 *   <li><b>手势必须吞掉</b>：蹲下右键同时也是「出牌 / 放置牌桌 / 打开棋盘菜单」的手势，
 *       不拦就会「开准星的同时把那一手牌也打了」。监听器登记在 {@link EventPriority#LOWEST}
 *       （先于各 manager 的 {@code HIGH}）；除 {@code setCancelled} 之外还要给 manager 一个
 *       {@link #isSwallowed} 标记 —— 因为各 manager <b>故意</b>接收「已取消」事件
 *       （出生点保护会先把非 OP 的交互取消），不能拿 {@code event.isCancelled()} 当判据。</li>
 *   <li><b>10 tick 防抖</b>：一次右键会派发「方块 + 空中 + 实体」多条事件，触屏按住「使用」
 *       还会连发事务。窗口内只认第一次（常量与旧版 {@code LASER_TOGGLE_COOLDOWN} 一致）。</li>
 * </ol>
 *
 * <h3>为什么用标题画准星</h3>
 * 基岩原版准星在深色桌面上几乎看不见，而棋牌全靠准星瞄准落点 / 手牌。标题层不受方块遮挡，
 * 也不会像 {@code FloatingTextParticle} 那样生成盔甲架实体，是最便宜的做法。
 *
 * <p>⚠️ 标题会淡出，必须定时重发续命：{@link #STAY} = 60 tick（3 秒），
 * {@link #REFRESH_EVERY_CALLS} = 4 次 {@code tickAll} 调用 = 20 tick（**1 秒**），
 * 重发间隔小于停留时间 ⇒ 不闪。</p>
 */
public final class LocatorTitleService implements Listener {

    /** 主标题：只有颜色码，等于不显示文字（十字靠副标题画）。 */
    private static final String TITLE = "§f";

    /** 副标题：一个加号 + 三个换行，让加号落在屏幕中央而不是标题下方。 */
    private static final String SUBTITLE = "+\n\n\n";

    /** 淡入 / 停留 / 淡出（服务端 tick，20 = 1 秒）。停留给足，靠定时续期而不是靠停留。 */
    private static final int FADE_IN = 0;
    private static final int STAY = 60;
    private static final int FADE_OUT = 0;

    /**
     * 续期间隔：{@code tickAll} 每 5 tick 调一次，4 次 = 20 tick = **1 秒**（< {@link #STAY} = 3 秒）。
     * <p>用户 2026-10-22 明确要求「一秒间隔」。入座 / 拿起定位器的那一下不等续期，
     * 见 {@link #tick()}：第一次显示立刻发、失去资格立刻擦。</p>
     */
    private static final int REFRESH_EVERY_CALLS = 4;

    /**
     * 切换防抖窗口（tick）：触屏按住「使用」键可能连发事务，窗口内只认一次。
     * 与旧版 {@code UnoManager.LASER_TOGGLE_COOLDOWN} 保持一致（10 tick = 0.5 秒）。
     */
    private static final int TOGGLE_COOLDOWN = 10;

    /**
     * 默认开关状态 = <b>开</b>（与旧版定位器激光一致）。
     * 所以这里存的是「主动关掉的人」而不是「打开的人」—— 新玩家、新会话一律是开的。
     */
    private final Set<UUID> off = new HashSet<>();

    /**
     * 玩家 → 本 tick 已被「蹲下 + 使用定位器」吞掉。
     * <p>
     * 故意做成 static：各 manager 手里没有本服务的引用（它们在构造本服务之前就创建了），
     * 而这件事本身就是「本 tick 全世界只处理一次」的语义，挂在实例上反而绕。
     * 插件重载 = 新 ClassLoader，静态表自然重置。
     */
    private static final Map<UUID, Integer> SWALLOWED = new HashMap<>();

    private final TabletopGamesManager boards;
    private final XiangqiManager xiangqi;
    private final OthelloManager othello;
    private final GoManager go;
    private final DoushouqiManager doushouqi;
    private final JunqiManager junqi;
    private final UnoManager uno;

    /** 玩家设置（定位器开关持久化到 SQLite）。 */
    private final PlayerSettingsDao settings;

    /** 上一轮真的发过标题的人：用于「不再满足条件时擦一次」，避免每次刷新都发 clearTitle。 */
    private final Set<UUID> showing = new HashSet<>();

    /** 手势防抖：玩家 → 上次切换的服务端 tick。 */
    private final Map<UUID, Integer> toggleTicks = new HashMap<>();

    /** {@code tickAll} 的调用计数（决定哪一轮真正重发标题）。 */
    private int calls;

    public LocatorTitleService(TabletopGamesManager boards, XiangqiManager xiangqi,
                               OthelloManager othello, GoManager go,
                               DoushouqiManager doushouqi, JunqiManager junqi,
                               UnoManager uno,
                               PlayerSettingsDao settings) {
        this.boards = boards;
        this.xiangqi = xiangqi;
        this.othello = othello;
        this.go = go;
        this.doushouqi = doushouqi;
        this.junqi = junqi;
        this.uno = uno;
        this.settings = settings;
        // 从数据库恢复「谁关过准星」——重启后仍然记得（2026-10-22 用户要求入库）
        this.off.addAll(settings.loadLocatorTitleOff());
    }

    // ------------------------------------------------------------------
    // 手势：蹲下 + 使用键
    // ------------------------------------------------------------------

    /**
     * 右键**空气**才可能是切换手势。
     *
     * <p>用户 2026-10-22：「蹲下点到空气时候才控制开关，点到方块不算，触发到了我们牌、
     * 按钮区域投影位置的也不算」。所以这里只认 {@code RIGHT_CLICK_AIR}：</p>
     * <ul>
     *   <li>点到方块 / 点到实体 —— 直接放行，交给牌桌 / 棋盘处理；</li>
     *   <li>空气但准星压在牌或按钮的<b>投影位置</b> —— {@link #hitsTable} 为真，同样放行。</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        if (!isToggleGesture(player, event.getItem())) {
            return;
        }
        if (hitsTable(player)) {
            return;
        }
        event.setCancelled(true);
        toggle(player);
    }

    /**
     * 准星是否压在牌 / 按钮的投影位置上（只看不点）。
     * <p>只问「已入座且就在桌边」的那张桌；没入座 / 不在范围内一律 false。</p>
     */
    private boolean hitsTable(Player player) {
        UnoTable table = uno.getTableOf(player.getUniqueId());
        if (table != null && table.hasTarget(player)) {
            return true;
        }
        DdzGame game = DdzGame.of(player);
        return game != null && game.hasTarget(player);
    }

    /**
     * 手势判据：<b>蹲下</b> + <b>主手是定位器</b>。
     * <p>
     * 蹲下是玩家明确表达「我要改设置」，正常瞄准落点不会蹲着，所以不会误触。
     * 手持物品按「使用」时客户端一定会发「使用物品」事务（MOT 的 {@code UseItemProcessor_v113}
     * 收到后固定派发 {@code PlayerInteractEvent}），所以任何地方都能收到，不依赖准星打没打中方块。
     */
    private boolean isToggleGesture(Player player, Item eventItem) {
        if (!player.isSneaking()) {
            return false;
        }
        Item held = eventItem != null && !eventItem.isNull()
                ? eventItem
                : player.getInventory().getItemInHand();
        return held instanceof LocatorItem;
    }

    private void toggle(Player player) {
        UUID id = player.getUniqueId();
        int now = Server.getInstance().getTick();
        Integer last = toggleTicks.get(id);
        if (last != null && now - last < TOGGLE_COOLDOWN) {
            return;
        }
        toggleTicks.put(id, now);
        // 告诉各 manager：这次右键是手势，别拿去出牌 / 开菜单 / 落子
        SWALLOWED.put(id, now);

        if (isEnabled(id)) {
            off.add(id);
            settings.saveLocatorTitle(id, false);
            // 关掉立刻擦：不等 tick 里的 showing 回收，避免残影留到下一次刷新
            player.clearTitle();
            showing.remove(id);
            player.sendTip("§7十字准星 §7| §8已关闭");
            return;
        }
        off.remove(id);
        settings.saveLocatorTitle(id, true);
        // 没入座时按用户要求不显示，这里如实提示，避免「开了却看不到」被当成没生效
        player.sendTip(isSeated(player) ? "§a十字准星 §7| §a已开启" : "§e十字准星 §7| §e已开启（入座后显示）");
    }

    // ------------------------------------------------------------------
    // 显示：每 5 tick 被 tickAll 调一次
    // ------------------------------------------------------------------

    /**
     * 由 {@code TabletopGamesPlugin.tickAll()} 每 5 tick 调用一次。
     * <p>
     * 每轮都判一次「开关是开的 + <b>手持定位器</b> + 正坐在某个座位上」，但<b>只在两种时候真的发标题</b>：
     * ① 刚满足条件（入座 / 拿起定位器）→ 立刻出现，不用等续期；
     * ② 到了续期点（每 1 秒）→ 防淡出。条件一旦不成立就 {@link Player#clearTitle()}
     * 立刻擦掉（只擦一次），所以<b>离座 / 换手持物品 / 关开关都不会留残影</b>。
     */
    public void tick() {
        calls++;
        boolean refresh = calls % REFRESH_EVERY_CALLS == 0;
        for (Player player : Server.getInstance().getOnlinePlayers().values()) {
            UUID id = player.getUniqueId();
            if (!isEnabled(id) || !holdsLocator(player) || !isSeated(player)) {
                if (showing.remove(id)) {
                    player.clearTitle();
                }
                continue;
            }
            if (refresh || !showing.contains(id)) {
                // ⚠️ 收件人收窄成一个人：不要真的广播给全服
                Server.getInstance().broadcastTitle(
                        TITLE, SUBTITLE, FADE_IN, STAY, FADE_OUT,
                        Collections.singletonList(player));
                showing.add(id);
            }
        }
    }

    /** 主手必须拿着定位器（2026-10-22 用户要求：手持定位器才触发显示）。 */
    private boolean holdsLocator(Player player) {
        return player.getInventory().getItemInHand() instanceof LocatorItem;
    }

    /**
     * 是否坐在某个棋类 / 牌类的座位上（五棋走各自的 manager，UNO 查座次索引，
     * 斗地主用 {@code DdzGame.of}）。这是「其他情况不会有显示」的唯一判据。
     */
    private boolean isSeated(Player player) {
        UUID id = player.getUniqueId();
        return boards.isSeated(id)
                || xiangqi.isSeated(id)
                || othello.isSeated(id)
                || go.isSeated(id)
                || doushouqi.isSeated(id)
                || junqi.isSeated(id)
                || uno.getTableOf(id) != null
                || DdzGame.of(player) != null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // ⚠️ 不动 off：那是持久化的个人设置（数据库里有行），退出登录不该清掉
        showing.remove(id);
        toggleTicks.remove(id);
        SWALLOWED.remove(id);
    }

    // ------------------------------------------------------------------
    // 给各 manager 用的查询
    // ------------------------------------------------------------------

    /**
     * 这次右键是不是已经被「蹲下 + 使用定位器」的手势吞掉了。
     *
     * <p>各 manager 的 {@code onInteract} 开头都要问一句：它们<b>故意</b>不忽略「已取消」事件
     * （出生点保护会先把非 OP 的交互取消掉），所以不能拿 {@code event.isCancelled()} 当判据，
     * 否则会把「非 OP 在出生点附近开菜单」一起干掉。</p>
     */
    public static boolean isSwallowed(Player player) {
        if (player == null) {
            return false;
        }
        Integer tick = SWALLOWED.get(player.getUniqueId());
        return tick != null && tick == Server.getInstance().getTick();
    }

    /** 准星开关是否打开（默认开，只有主动关掉的人才是 false）。 */
    public boolean isEnabled(UUID id) {
        return !off.contains(id);
    }
}
