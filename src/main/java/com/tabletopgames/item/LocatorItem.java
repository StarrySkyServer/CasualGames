package com.tabletopgames.item;

import cn.nukkit.item.Item;
import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 「定位器」（{@code tabletopgame:locator}）：UNO 牌桌的持握道具，思路完全来自斗地主的激光笔
 * （{@code Doudizhu/src/main/java/top/doudizhu/item/CursorItem.java}）。
 *
 * <h3>为什么需要它</h3>
 * 手牌是<b>悬空</b>在台面上方的（第一行底边离台面 0.26 格，第二行起更高），
 * 而基岩客户端的准星只认「方块」和「实体」—— 两样都没碰着时，客户端<b>不一定</b>会把
 * 右键交互发给服务端，于是「瞄着牌点没反应」。而**手持物品**右键时，客户端一定会发
 * 「使用物品」事务（MOT 的 {@code UseItemProcessor_v113} 收到后固定派发
 * {@code PlayerInteractEvent(RIGHT_CLICK_AIR)}），服务端就一定能拿到这次信号，
 * 再用自己的射线判定去决定点到了哪张牌 / 哪个按钮。
 *
 * <h3>行为约定</h3>
 * <ul>
 *   <li>本身**没有任何用途**（不是食物也不是工具）—— 右键不会在世界里留下任何东西；</li>
 *   <li>手持它右键牌桌：与空手一样（命中手牌/按钮就操作，没命中就开菜单）；</li>
 *   <li>手持它且准星落在牌上时，沿视线在命中点放一颗**白色光点**
 *       （{@code tabletopgame:locator_dot}，2026-10-15 起为白色）；</li>
 *   <li><b>入座即发、离座即收</b>（2026-10-27 用户统一口径，全部棋类 / UNO / 斗地主 /
 *       德州扑克一致）：见 {@link LocatorService}。</li>
 * </ul>
 *
 * <h3>「带标签」的那一把</h3>
 * 只有 {@link #issued()} 造出来的才算「牌桌发的」：它带
 * <b>{@code minecraft:item_lock=1}（锁在背包）+ {@code KeepOnDeath=1}</b> +
 * <b>{@code display.Lore = §9潜行后可使用开关</b>。
 * <b>创造物品栏里拿出来的那一份没有任何标签</b>，外观是普通的，
 * 且<b>不参与任何检测</b>（既不算「已有一把」，离座也不会被清掉、不会重复发）。
 * 判据唯一实现见 {@link #isIssued(Item)}。
 *
 * 资源侧（由 {@code tools/uno/build_locator.py} 生成）：模型
 * {@code models/entity/uno/locator.geo.json} + 手持附加物 {@code attachables/uno/locator.json}
 * + 贴图 {@code textures/uno/locator.png} + 图标键 {@link #ICON}。
 */
public class LocatorItem extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:locator";

    /** 背包图标在 {@code textures/item_texture.json} 里的键名。 */
    public static final String ICON = "tabletopgame_locator";

    /** 发放版定位器的 Lore（2026-10-27 用户指定）：背包里第二行蓝字提示。 */
    public static final String LORE = "§9潜行后可使用开关";

    public LocatorItem() {
        super(IDENTIFIER, "定位器", ICON);
    }

    /**
     * 牌桌发放用的定位器：带「锁在背包 + 死亡不掉落 + Lore 提示」的 NBT
     * （2026-10-22 / 2026-10-27 用户要求）。
     *
     * <ul>
     *   <li>{@link ItemLockMode#LOCK_IN_INVENTORY}：客户端在物品角上画<b>黄色小锁</b>，
     *       并且不能在背包界面里丢出去 / 拖到快捷栏外；<b>背包内部仍可自由移动</b>
     *       （这一点与 {@code LOCK_IN_SLOT} 不同，后者会钉死格子）。</li>
     *   <li>{@code KeepOnDeath=1}：死亡时保留在背包里。</li>
     *   <li>{@code display.Lore = [}{@value #LORE}{@code ]}：背包里显示「§9潜行后可使用开关」。</li>
     * </ul>
     *
     * <p>创造栏里那一份（{@link #LocatorItem()}）<b>不带</b>这些标签，所以外观是正常的——
     * 用户原话：「游戏中的物品角标表现是黄色的（如果是创造物品栏里面获得的就正常的）」。</p>
     */
    public static Item issued() {
        Item item = new LocatorItem();
        item.setItemLockMode(Item.ItemLockMode.LOCK_IN_INVENTORY);
        item.getOrCreateNamedTag().putByte("KeepOnDeath", 1);
        item.setLore(LORE);
        return item;
    }

    /**
     * 这一把是不是<b>牌桌发的</b>（「带标签」的那把）。
     *
     * <p>判据 = 是定位器，并且带{@code 锁在背包}或{@code 死亡不掉落}任意一个标记。
     * 用「或」而不是「与」：万一某个路径把其中一个标签吃掉，另一条还能认出来，
     * 免得判成「没有 → 再发一把」，越堆越多。</p>
     *
     * <p><b>创造物品栏拿出来的两样都没有 ⇒ 恒为 {@code false}</b>，也就是不参与任何
     * 判重 / 回收 / 清扫（用户 2026-10-27：「从创造物品栏拿出来的不算，不计入任何检测范围」）。</p>
     */
    public static boolean isIssued(Item item) {
        if (!(item instanceof LocatorItem)) {
            return false;
        }
        if (item.getItemLockMode() != Item.ItemLockMode.NONE) {
            return true;
        }
        return item.getNamedTag() != null && item.getNamedTag().contains("KeepOnDeath");
    }

    @Override
    public CustomItemDefinition getDefinition() {
        return CustomItemDefinition
                .simpleBuilder(this, CreativeItemCategory.ITEMS)
                .build();
    }

    /** 每人只需一把，不叠加。 */
    @Override
    public int getMaxStackSize() {
        return 1;
    }
}
