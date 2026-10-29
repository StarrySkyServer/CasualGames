package com.tabletopgames.board;

import cn.nukkit.block.custom.properties.BlockProperties;

/**
 * 围棋棋盘方块基类，四种规格共用。
 * <p>
 * 9 路 / 13 路用 2x2 方块拼成（复用五子棋的方块几何体，只换顶面贴图）；
 * 19 路有 3x3 与 4x4 两种规格。各规格的结构与五子棋棋盘一致：
 * {@code tabletopgame:position} = {@code segment * 4 + rotation}，
 * segment 选择拼图分块，rotation 为棋盘朝向（让方块模型跟随棋盘一起旋转）。
 */
public abstract class GoBoardBlock extends BaseTabletopGamesBoardBlock {

    /** 2x2 分块几何体：索引 = segment = i + j * 2，与五子棋 / 黑白棋共用同一套。 */
    private static final String[] QUAD_GEOMETRIES = {
            "geometry.tabletopgame.board_segment_11",
            "geometry.tabletopgame.board_segment_01",
            "geometry.tabletopgame.board_segment_10",
            "geometry.tabletopgame.board_segment_00",
    };

    protected GoBoardBlock(String identifier, BlockProperties properties, int meta) {
        super(identifier, properties, meta);
    }

    /** 2x2 分块几何体（只读，可跨规格共享）。 */
    protected static String[] quadGeometries() {
        return QUAD_GEOMETRIES;
    }

    public abstract int getPosition();

    @Override
    public abstract void setPosition(int position);
}
