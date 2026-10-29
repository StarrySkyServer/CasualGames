package com.tabletopgames.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 黑白棋（奥赛罗）棋盘方块（2x2），对应资源包中的 tabletopgame:othello_board。
 * <p>
 * 与五子棋 2x2 棋盘方块结构完全一致：{@code tabletopgame:position} = {@code segment * 4 + rotation}，
 * 四块几何体按 2x2 拼出整张 256x256 棋盘贴图。差别只在顶面贴图名（{@code tabletopgame_othello_board_top}）。
 */
public class OthelloBoardBlock extends BaseTabletopGamesBoardBlock {

    public static final String IDENTIFIER = "tabletopgame:othello_board";

    /** 0..15：{@code position = segment * 4 + rotation}。segment 0..3 选择四分之一模型，rotation 0..3 为棋盘朝向。 */
    public static final IntBlockProperty POSITION =
            new IntBlockProperty("tabletopgame:position", true, 15, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    /** 四象限几何模型：索引 = segment（i + j*2），与五子棋共用同一套棋盘分块几何体。 */
    private static final String[] GEOMETRIES = {
            "geometry.tabletopgame.board_segment_11",
            "geometry.tabletopgame.board_segment_01",
            "geometry.tabletopgame.board_segment_10",
            "geometry.tabletopgame.board_segment_00",
    };

    public OthelloBoardBlock() {
        this(0);
    }

    public OthelloBoardBlock(int meta) {
        super(IDENTIFIER, PROPERTIES, meta);
    }

    public int getPosition() {
        return this.getIntValue(POSITION);
    }

    @Override
    public void setPosition(int position) {
        this.setIntValue(POSITION, position);
    }

    @Override
    public String getName() {
        return "黑白棋棋盘";
    }

    /**
     * 注册自定义方块。必须在 CustomBlockManager 关闭注册表之前调用（插件 onLoad / STARTUP）。
     */
    public static void register() {
        BoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, GEOMETRIES,
                "黑白棋棋盘", "tabletopgame_othello_board_top", OthelloBoardBlock::new);
    }
}
