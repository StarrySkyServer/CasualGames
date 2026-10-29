package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 象棋棋盘放置物品，手持后对准方块右键即可放下 2x2 棋盘并摆好初始棋局。
 */
public class XiangqiBoardItem extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:xiangqi_board_item";

    public XiangqiBoardItem() {
        super(IDENTIFIER, "中国象棋棋盘", "tabletopgame_xiangqi_board_icon");
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
