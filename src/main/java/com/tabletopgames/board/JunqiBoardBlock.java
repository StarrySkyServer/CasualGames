package com.tabletopgames.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 军旗棋盘方块（3×3 = 9 块），对应资源包中的 {@code tabletopgame:junqi_board}。
 *
 * <p>{@code tabletopgame:junqi_position} = {@code segment * 4 + rotation}（0..35）。
 * segment 按 {@code i + j*3}（i 沿 P 轴 3 格、j 沿 Q 轴 3 格），**每段有自己的 128×128 顶面贴图**
 * （{@code junqi_board_top_<segment>}，内容 = 原图 (i, j) 象限）。</p>
 *
 * <p>贴图左侧 2×3 块为对局区（60 点位），右侧 1×3 块为淘汰棋子托盘
 * （上下两组各 4 列 × 6 行）。</p>
 */
public class JunqiBoardBlock extends BaseJunqiBoardBlock {

    public static final String IDENTIFIER = "tabletopgame:junqi_board";

    /** 段数（3 列 × 3 行）。 */
    public static final int SEGMENTS = 9;

    public static final IntBlockProperty POSITION =
            new IntBlockProperty("tabletopgame:junqi_position", true, SEGMENTS * 4 - 1, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    /** 按 segment 索引的几何模型（9 个文件，内容只差标识符）。 */
    private static final String[] GEOMETRIES = new String[SEGMENTS];
    /** 按 segment 索引的顶面贴图（与原图象限 (i, j) 一一对应）。 */
    private static final String[] TOP_TEXTURES = new String[SEGMENTS];

    static {
        for (int s = 0; s < SEGMENTS; s++) {
            GEOMETRIES[s] = "geometry.tabletopgame.junqi_board_segment_" + s;
            TOP_TEXTURES[s] = "junqi_board_top_" + s;
        }
    }

    public JunqiBoardBlock() {
        this(0);
    }

    public JunqiBoardBlock(int meta) {
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
        return "军旗棋盘";
    }

    public static void register() {
        JunqiBoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, GEOMETRIES, TOP_TEXTURES,
                "军旗棋盘", JunqiBoardBlock::new);
    }
}
