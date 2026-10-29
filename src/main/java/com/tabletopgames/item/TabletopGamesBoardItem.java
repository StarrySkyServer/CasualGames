package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 棋盘放置物品，手持后对准方块右键即可放下 2x2 棋盘。
 */
public class TabletopGamesBoardItem extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:board_item";

    public TabletopGamesBoardItem() {
        super(IDENTIFIER, "五子棋棋盘", "tabletopgame_board_icon");
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