package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 中号棋盘放置物品，手持后对准方块右键即可放下 3x3 棋盘（区域以点击方块为中心）。
 * <p>
 * 与其它尺寸共用物品贴图与手持 3D 模型，仅放置尺寸与显示名不同（以「3×3」区分）。
 */
public class TabletopGamesBoard3x3Item extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:board_3x3_item";

    public TabletopGamesBoard3x3Item() {
        super(IDENTIFIER, "五子棋棋盘（3×3）", "tabletopgame_board_icon");
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