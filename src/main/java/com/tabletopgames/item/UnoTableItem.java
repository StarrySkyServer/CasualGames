package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * UNO 牌桌放置物品：手持后对准方块右键即可铺下 3x3 物理牌桌（区域以点击方块为中心）。
 * <p>
 * 与四棋棋盘物品同构：单堆叠、建筑分类、贴图 {@code tabletopgame_uno_table_icon}
 * （由 item_texture.json 登记，指向 {@code textures/uno/physical_table_item}）。
 */
public class UnoTableItem extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:uno_table_item";

    public UnoTableItem() {
        super(IDENTIFIER, "UNO 牌桌", "tabletopgame_uno_table_icon");
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
