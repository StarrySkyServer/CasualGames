package com.tabletopgames.item;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.inventory.CraftItemEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerDropItemEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 开局发放的「定位器」的守门人（2026-10-22 用户要求）。
 *
 * <p>发放出去的定位器带 {@link Item.ItemLockMode#LOCK_IN_INVENTORY} + {@code KeepOnDeath}：
 * 客户端会画黄色小锁、背包内可以自由移动，但**不能丢出去**、**不能当合成材料**、
 * **死亡不掉**。创造栏里那一份不带这些标签，所以外观与行为都是普通的。</p>
 *
 * <h3>为什么除了 NBT 还要拦事件</h3>
 * {@code ItemLock} 的「锁在背包」是<b>客户端</b>的 UI 行为（拖不出去）。服务端能不能真拦住，
 * 取决于核心有没有实现。为了不把「能不能丢」赌在 MOT 的实现上，这里在服务端再补三道：
 * <ol>
 *   <li>{@link PlayerDropItemEvent}：丢了就取消；</li>
 *   <li>{@link CraftItemEvent}：带锁的定位器当材料就取消；</li>
 *   <li>死亡：把带 {@code KeepOnDeath} 的定位器从背包收进暂存，重生时放回
 *       （核心不认这个 NBT 时也能保住）。</li>
 * </ol>
 */
public final class LocatorGuard implements Listener {

    /** 死亡时暂存的「死亡不掉落」定位器：玩家 UUID → 物品。 */
    private final Map<UUID, List<Item>> kept = new HashMap<>();

    /** 是不是「发放版」定位器（创造栏拿到的没有锁，不算）。判据唯一实现在 {@link LocatorItem#isIssued}。 */
    private static boolean isIssued(Item item) {
        return LocatorItem.isIssued(item);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!isIssued(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendTip("§c定位器是牌桌发的，不能丢");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        for (Item input : event.getInput()) {
            if (isIssued(input)) {
                event.setCancelled(true);
                event.getPlayer().sendTip("§c定位器不能当合成材料");
                return;
            }
        }
    }

    /**
     * 死亡：把带 KeepOnDeath 的定位器收起来。
     * <p>放在 MONITOR 阶段、并直接从背包格子里清掉，掉落列表里就不会再有它；
     * 重生时原样放回。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player == null) {
            return;
        }
        List<Item> keep = new ArrayList<>(1);
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            Item item = player.getInventory().getItem(slot);
            if (isIssued(item) && item.getNamedTag() != null
                    && item.getNamedTag().contains("KeepOnDeath")) {
                keep.add(item.clone());
                player.getInventory().clear(slot);
            }
        }
        if (!keep.isEmpty()) {
            kept.put(player.getUniqueId(), keep);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        List<Item> keep = kept.remove(event.getPlayer().getUniqueId());
        if (keep == null) {
            return;
        }
        for (Item item : keep) {
            event.getPlayer().getInventory().addItem(item);
        }
    }
}
