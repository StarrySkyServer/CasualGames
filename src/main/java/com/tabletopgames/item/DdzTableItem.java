package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 斗地主牌桌放置物品：手持后对准方块右键即可铺下 3x3 物理牌桌
 * （区域以点击方块的对侧邻格为中心回退 1 格，与 UNO 同规则）。
 * <p>
 * 与 {@link UnoTableItem} 同构：单堆叠、建筑分类、贴图 {@code tabletopgame_ddz_table_icon}
 * （由 {@code textures/item_texture.json} 登记，指向
 * {@code textures/ddz/physical_table_item}，逐字节取自斗地主模组原资源）。
 */
public class DdzTableItem extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:ddz_table_item";

    public DdzTableItem() {
        super(IDENTIFIER, "斗地主牌桌", "tabletopgame_ddz_table_icon");
    }

    @Override
    public CustomItemDefinition getDefinition() {
        return CustomItemDefinition
                .simpleBuilder(this, CreativeItemCategory.ITEMS)
                .build();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }
}