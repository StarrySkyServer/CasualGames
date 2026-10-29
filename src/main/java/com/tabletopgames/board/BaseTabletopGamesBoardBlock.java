package com.tabletopgames.board;

import cn.nukkit.block.custom.container.CustomBlockMeta;
import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemTool;
import cn.nukkit.math.AxisAlignedBB;
import cn.nukkit.math.SimpleAxisAlignedBB;

/**
 * 五子棋棋盘方块基类。
 * <p>
 * 所有尺寸的棋盘共享同一套行为：方块性质与橡木板一致（硬度 2、斧头挖掘更快），
 * 但任意一处被破坏成功即视作整体收回（由 {@code TabletopGamesManager#onBreak} 处理）；
 * 右键打开菜单，与其它方块一致。
 */
public abstract class BaseTabletopGamesBoardBlock extends CustomBlockMeta {

    /** 棋盘顶面高度（方块数），与客户端 {@code minecraft:collision_box} 的 2 像素一致。 */
    private static final double TOP = 2.0 / 16.0;

    protected BaseTabletopGamesBoardBlock(String identifier, BlockProperties properties, int meta) {
        super(identifier, properties, meta);
    }

    /**
     * 设置方块状态：{@code segment * 4 + rotation}。
     * segment 决定显示棋盘贴图的哪一份，rotation 为棋盘朝向（让方块模型跟随棋盘一起旋转）。
     */
    public abstract void setPosition(int position);

    /** 硬度与橡木板一致（2），配合斧头挖掘更快。 */
    @Override
    public double getHardness() {
        return 2.0;
    }

    @Override
    public boolean isBreakable(Item item) {
        return true;
    }

    /** 抗爆性与橡木板一致。 */
    @Override
    public double getResistance() {
        return 15.0;
    }

    /** 用斧头挖掘更快（同橡木板）。 */
    @Override
    public int getToolType() {
        return ItemTool.TYPE_AXE;
    }

    @Override
    public boolean canBeActivated() {
        return true;
    }

    /**
     * 服务端碰撞箱与客户端 {@code minecraft:collision_box} 保持一致：只有 2 像素高。
     * <p>
     * 默认实现返回整格（1 格高），非玩家实体（动物等）站到棋盘上时会被抬高一格；
     * 玩家位置由客户端按方块定义里的碰撞箱预测，所以不受影响。
     */
    @Override
    protected AxisAlignedBB recalculateBoundingBox() {
        return new SimpleAxisAlignedBB(this.x, this.y, this.z,
                this.x + 1.0D, this.y + TOP, this.z + 1.0D);
    }
}