package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 黑白棋棋盘放置物品，手持后对准方块右键即可放下 2x2 棋盘并摆好初始棋局。
 */
public class OthelloBoardItem extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:othello_board_item";

    public OthelloBoardItem() {
        super(IDENTIFIER, "黑白棋棋盘", "tabletopgame_othello_board_icon");
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
