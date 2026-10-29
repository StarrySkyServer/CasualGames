package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 围棋 9 路棋盘放置物品（2x2 方块）。
 */
public class GoBoard9Item extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:go_board_9_item";

    public GoBoard9Item() {
        super(IDENTIFIER, "围棋棋盘（9 路）", "tabletopgame_go_board_icon_9");
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
