package com.tabletopgames.item;

import cn.nukkit.block.BlockCarpet;
import cn.nukkit.block.BlockPlanks;
import cn.nukkit.block.BlockWood2;
import cn.nukkit.inventory.ShapedRecipe;
import cn.nukkit.item.Item;
import cn.nukkit.utils.DyeColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * UNO 牌桌物品的合成配方（3x3 工作台铺满 9 格）。
 *
 * <pre>
 * 青色地毯     青色地毯     青色地毯
 * 深色橡木原木 深色橡木原木 深色橡木原木
 * 橡木板       橡木板       橡木板
 * </pre>
 *
 * <p>沿用斗地主牌桌的骨架配方，只把最上面一行的 3 个绿地毯换成<b>青色地毯</b>，
 * 其余两行不变，避免与斗地主牌桌（绿地毯版）撞配方。
 *
 * <p>是否注册由配置项 {@code unoTableCraftable} 控制：默认 false —— 不注册任何配方，
 * UNO 牌桌只能由 OP 用 {@code /tabletopgame uno} 发放；配置开启后才可合成。
 */
public final class UnoTableRecipe {

    /** 配方 id：与斗地主牌桌的 {@code ddz:physical_table_item} 区分开，避免撞 id。 */
    public static final String RECIPE_ID = "tabletopgame:uno_table_item";

    private UnoTableRecipe() {
    }

    /** 按当前配方造一个全新的 {@link ShapedRecipe}（可反复调用，每次都是新实例）。 */
    public static ShapedRecipe create() {
        Map<Character, Item> ingredients = new HashMap<>();
        ingredients.put('C', new BlockCarpet(DyeColor.CYAN).toItem());
        ingredients.put('L', new BlockWood2(BlockWood2.DARK_OAK).toItem());
        ingredients.put('P', new BlockPlanks(BlockPlanks.OAK).toItem());
        String[] shape = {"CCC", "LLL", "PPP"};
        return new ShapedRecipe(RECIPE_ID, 1, new UnoTableItem(), shape, ingredients, new ArrayList<>());
    }
}
