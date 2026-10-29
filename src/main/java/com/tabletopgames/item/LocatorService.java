package com.tabletopgames.item;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 「定位器」的发放 / 回收统一入口（2026-10-27 用户要求：全部棋类 + UNO + 斗地主 + 德州扑克
 * 用同一套口径）。
 *
 * <h3>为什么需要统一</h3>
 * 定位器不是玩具：手牌 / 棋盘上的落点都<b>悬空</b>在台面上方，而基岩客户端的准星只认方块和
 * 实体，两样都没碰着时客户端<b>不一定</b>把右键交互发给服务端（见 {@link LocatorItem}）。
 * 另外「十字准星」叠层也要求<b>手持定位器 + 坐在座位上</b>（见
 * {@code LocatorTitleService}）—— 没发定位器，玩家就既看不到准星、也点不稳落点。
 *
 * <h3>唯一口径（2026-10-27 用户定稿）</h3>
 * <ul>
 *   <li><b>入座就发</b>（{@code join} / {@code sit}），不再区分「开局前 / 开局后」——
 *       以前斗地主、UNO 是开局才发，德州扑克是入座就发，用户要求全部改成入座就发；</li>
 *   <li><b>离座就收</b>（{@code leave} / 退桌 / 拆桌），只收「带标签」的那把；</li>
 *   <li><b>只认「带标签」的</b>（{@link LocatorItem#isIssued(Item)}）：创造物品栏里拿出来的
 *       那一份两样标签都没有，<b>不参与任何检测</b> —— 既不算「已经有一把」（会照常发），
 *       离座也不会被清掉。</li>
 * </ul>
 *
 * <h3>另外三条约定</h3>
 * <ul>
 *   <li>{@link #give(Player)} <b>幂等</b>：背包里已经有一把<b>带标签的</b>就不再发（否则反复
 *       入座会越堆越多）；<b>背包满了也不发</b>（不往地上丢，等下次入座再补）。</li>
 *   <li>{@link #release(Player)} <b>只在「不再坐任何座位」时才收</b>：玩家可能同时坐在两桌
 *       （比如棋盘 + 牌桌），A 桌离座不能把 B 桌要用的定位器一起收走。</li>
 *   <li>{@link #take(Player)} 无条件清空<b>带标签的</b>定位器，<b>绝不碰玩家别的东西</b>。</li>
 * </ul>
 */
public final class LocatorService {

    /**
     * 「这个玩家是否还坐在某张棋桌 / 牌桌上」的判据。
     *
     * <p>由插件在 {@code onEnable} 里注册（它认得全部 manager）。默认「没坐」——
     * 万一注册漏了，最坏也只是多收一把，不会出现「离座了定位器还在」。</p>
     */
    private static Predicate<Player> seatCheck = player -> false;

    private LocatorService() {
    }

    /** 由 {@code TabletopGamesPlugin} 启动时注册座位判据。 */
    public static void setSeatCheck(Predicate<Player> check) {
        seatCheck = check == null ? player -> false : check;
    }

    /**
     * 背包里有没有<b>带标签的</b>定位器（任何槽位，含副手）。
     *
     * <p>创造物品栏拿出来的那份不算 —— 用户 2026-10-27：「从创造物品栏拿出来的不算，
     * 不计入任何检测范围」。</p>
     */
    public static boolean has(Player player) {
        return findIssued(player) != null;
    }

    /** 背包里第一把「带标签」的定位器所在的槽位；没有就返回 {@code null}。 */
    private static Map.Entry<Integer, Item> findIssued(Player player) {
        if (player == null) {
            return null;
        }
        for (Map.Entry<Integer, Item> entry : player.getInventory().getContents().entrySet()) {
            if (LocatorItem.isIssued(entry.getValue())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 发一把定位器。
     *
     * <ul>
     *   <li>背包里已经有一把<b>带标签的</b> ⇒ 不重复发（幂等）；顺手把「老版本发的、
     *       那时还没有 Lore」的提示补上，免得同名物品新旧两种外观；</li>
     *   <li>背包满了 ⇒ 什么都不做（{@code addItem} 把放不下的原样返回，不会掉在地上），
     *       下次入座 / 清扫时再自然补上。</li>
     * </ul>
     */
    public static void give(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Map.Entry<Integer, Item> existing = findIssued(player);
        if (existing != null) {
            // 已经有了：只在缺 Lore 时补一次（升级前发的那些）
            Item held = existing.getValue();
            if (held.getLore().length == 0) {
                held.setLore(LocatorItem.LORE);
                player.getInventory().setItem(existing.getKey(), held);
                player.getInventory().sendContents(player);
            }
            return;
        }
        // addItem 把放不下的原样**返回**（不会丢到地上）；maxStack=1 所以要么整把进包、要么原样退回
        Item[] left = player.getInventory().addItem(LocatorItem.issued());
        if (left.length > 0) {
            return;   // 背包已满：这次不发，下次入座 / 清扫再补
        }
        // 补一次整包同步：自建物品的槽位增量包偶尔会被客户端忽略，
        // 表现是「已经发了，手上却还是空的」。
        player.getInventory().sendContents(player);
    }

    /**
     * 离座后回收：<b>还坐在别的座位上就不收</b>。
     *
     * <p>必须在「座位已经从这个棋盘删掉之后」调用，否则判据会看到自己还在座。</p>
     */
    public static void release(Player player) {
        if (player == null) {
            return;
        }
        if (seatCheck.test(player)) {
            return;
        }
        take(player);
    }

    /** {@link #release(Player)} 的 UUID 版：离线 / 找不到人就直接跳过。 */
    public static void release(UUID id) {
        if (id == null) {
            return;
        }
        Player player = Server.getInstance().getPlayer(id).orElse(null);
        release(player);
    }

    /**
     * 无条件清空背包里的<b>带标签的</b>定位器（离座 / 拆桌 / 定时清扫都走这里）。
     *
     * <p>只动 {@link LocatorItem#isIssued(Item)} 认得的那些，玩家自己的东西、
     * 以及创造栏拿出来那份普通定位器，一律不碰。</p>
     */
    public static void take(Player player) {
        if (player == null) {
            return;
        }
        Map<Integer, Item> contents = player.getInventory().getContents();
        List<Integer> hit = new ArrayList<>();
        for (Map.Entry<Integer, Item> entry : contents.entrySet()) {
            if (LocatorItem.isIssued(entry.getValue())) {
                hit.add(entry.getKey());
            }
        }
        if (hit.isEmpty()) {
            return;
        }
        for (int slot : hit) {
            player.getInventory().clear(slot);
        }
        // 收完补一次整包同步（UNO 那边一直这么做）：清槽的增量包偶尔会被客户端忽略，
        // 表现为「已经收了，手上还举着一把」。离线时跳过，避免发包给没人收的会话。
        if (player.isOnline()) {
            player.getInventory().sendContents(player);
        }
    }
}
