package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 围棋 19 路棋盘放置物品（3x3 方块）。
 */
public class GoBoard19Item extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:go_board_19_item";

    public GoBoard19Item() {
        super(IDENTIFIER, "围棋棋盘（19 路 · 3×3）", "tabletopgame_go_board_icon_19");
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
