package com.tabletopgames.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 标准 2x2 棋盘方块，对应资源包中的 tabletopgame:board。
 * <p>
 * 通过 {@code tabletopgame:position} 属性（0..3）选择 2x2 拼接中的四分之一模型。
 * position = dx + dz * 2，其中 dx/dz 为该方块在 2x2 区域中的偏移。
 */
public class TabletopGamesBoardBlock extends BaseTabletopGamesBoardBlock {

    public static final String IDENTIFIER = "tabletopgame:board";

    /** 0..15：{@code position = segment * 4 + rotation}。segment 0..3 选择四分之一模型，rotation 0..3 为棋盘朝向。 */
    public static final IntBlockProperty POSITION =
            new IntBlockProperty("tabletopgame:position", true, 15, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    /** 四象限几何模型：索引 = position，分别对应 NW / NE / SW / SE 纹理象限。 */
    private static final String[] GEOMETRIES = {
            "geometry.tabletopgame.board_segment_11",
            "geometry.tabletopgame.board_segment_01",
            "geometry.tabletopgame.board_segment_10",
            "geometry.tabletopgame.board_segment_00",
    };

    public TabletopGamesBoardBlock() {
        this(0);
    }

    public TabletopGamesBoardBlock(int meta) {
        super(IDENTIFIER, PROPERTIES, meta);
    }

    public int getPosition() {
        return this.getIntValue(POSITION);
    }

    public void setPosition(int position) {
        this.setIntValue(POSITION, position);
    }

    @Override
    public String getName() {
        return "五子棋棋盘";
    }

    /**
     * 注册自定义方块。必须在 CustomBlockManager 关闭注册表之前调用（插件 onLoad / STARTUP）。
     */
    public static void register() {
        BoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, GEOMETRIES,
                "五子棋棋盘", TabletopGamesBoardBlock::new);
    }
}