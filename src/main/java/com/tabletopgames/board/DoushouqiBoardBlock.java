package com.tabletopgames.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 斗兽棋棋盘方块（2x2），对应资源包中的 tabletopgame:doushouqi_board。
 * <p>
 * 与象棋棋盘方块结构完全一致：{@code tabletopgame:position} = {@code segment * 4 + rotation}，
 * 四块几何体按 2x2 拼出整张 256x256 棋盘贴图。差别只在模型名（{@code doushouqi}）与顶面贴图名。
 */
public class DoushouqiBoardBlock extends BaseTabletopGamesBoardBlock {

    public static final String IDENTIFIER = "tabletopgame:doushouqi_board";

    /** 0..15：{@code position = segment * 4 + rotation}。segment 0..3 选择四分之一模型，rotation 0..3 为棋盘朝向。 */
    public static final IntBlockProperty POSITION =
            new IntBlockProperty("tabletopgame:position", true, 15, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    /** 四象限几何模型：索引 = segment（i + j*2），顺序与象棋一致。 */
    private static final String[] GEOMETRIES = {
            "geometry.tabletopgame.doushouqi_board_segment_11",
            "geometry.tabletopgame.doushouqi_board_segment_01",
            "geometry.tabletopgame.doushouqi_board_segment_10",
            "geometry.tabletopgame.doushouqi_board_segment_00",
    };

    public DoushouqiBoardBlock() {
        this(0);
    }

    public DoushouqiBoardBlock(int meta) {
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
        return "斗兽棋棋盘";
    }

    /**
     * 注册自定义方块。必须在 CustomBlockManager 关闭注册表之前调用（插件 onLoad / STARTUP）。
     */
    public static void register() {
        BoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, GEOMETRIES,
                "斗兽棋棋盘", "tabletopgame_doushouqi_board_top", DoushouqiBoardBlock::new);
    }
}
