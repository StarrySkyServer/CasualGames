package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 军旗棋盘放置物品：手持后对准方块右键，放下 3×3 棋盘（两人入座后开始摆棋）。
 */
public class JunqiBoardItem extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:junqi_board_item";

    public JunqiBoardItem() {
        super(IDENTIFIER, "军旗棋盘", "junqi_board_icon");
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
