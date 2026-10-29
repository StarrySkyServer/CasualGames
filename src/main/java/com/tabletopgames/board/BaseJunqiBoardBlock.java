package com.tabletopgames.board;

import cn.nukkit.block.custom.container.CustomBlockMeta;
import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemTool;
import cn.nukkit.math.AxisAlignedBB;
import cn.nukkit.math.SimpleAxisAlignedBB;

/**
 * 军旗棋盘方块基类。行为与其它棋盘一致：硬度 2、斧头更快、右键打开菜单；
 * 任意一块被破坏即整体收回（由 {@code JunqiManager#onBreak} 处理）。
 */
public abstract class BaseJunqiBoardBlock extends CustomBlockMeta {

    /** 棋盘顶面高度（2 像素），与客户端 {@code minecraft:collision_box} 一致。 */
    private static final double TOP = 2.0 / 16.0;

    protected BaseJunqiBoardBlock(String identifier, BlockProperties properties, int meta) {
        super(identifier, properties, meta);
    }

    public abstract void setPosition(int position);

    @Override
    public double getHardness() {
        return 2.0;
    }

    @Override
    public boolean isBreakable(Item item) {
        return true;
    }

    @Override
    public double getResistance() {
        return 15.0;
    }

    @Override
    public int getToolType() {
        return ItemTool.TYPE_AXE;
    }

    @Override
    public boolean canBeActivated() {
        return true;
    }

    @Override
    protected AxisAlignedBB recalculateBoundingBox() {
        return new SimpleAxisAlignedBB(this.x, this.y, this.z,
                this.x + 1.0D, this.y + TOP, this.z + 1.0D);
    }
}
