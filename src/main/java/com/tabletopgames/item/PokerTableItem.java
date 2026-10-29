package com.tabletopgames.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 德州扑克牌桌放置物品：对准方块右键铺下 3x3 牌桌（规则与斗地主完全相同）。
 * <p>贴图 {@code tabletopgame_poker_table_icon} 由 {@code textures/item_texture.json} 登记，
 * 指向 {@code textures/poker/physical_table_item}（用户提供的红绒桌图标）。</p>
 */
public class PokerTableItem extends ItemCustom {

    public static final String IDENTIFIER = "tabletopgame:poker_table_item";

    public PokerTableItem() {
        super(IDENTIFIER, "德州扑克牌桌", "tabletopgame_poker_table_icon");
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
