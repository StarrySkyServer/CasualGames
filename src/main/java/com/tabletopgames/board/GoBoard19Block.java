package com.tabletopgames.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 围棋 19 路棋盘方块（3x3），对应资源包中的 {@code tabletopgame:go_board_19_3x3}。
 * <p>
 * 384x384 的顶面贴图按 3 等分切成 9 份（每份 128 像素），segment = i + j * 3。
 */
public class GoBoard19Block extends GoBoardBlock {

    public static final String IDENTIFIER = "tabletopgame:go_board_19_3x3";

    /** 0..35：{@code position = segment * 4 + rotation}。 */
    public static final IntBlockProperty POSITION =
            new IntBlockProperty("tabletopgame:position", true, 35, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    private static final String[] GEOMETRIES = new String[9];

    static {
        for (int i = 0; i < GEOMETRIES.length; i++) {
            GEOMETRIES[i] = "geometry.tabletopgame.go_board19_segment_" + i;
        }
    }

    public GoBoard19Block() {
        this(0);
    }

    public GoBoard19Block(int meta) {
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
        return "围棋棋盘（19 路 · 3×3）";
    }

    public static void register() {
        BoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, GEOMETRIES,
                "围棋棋盘（19 路 · 3×3）", "tabletopgame_go_board_top_19", GoBoard19Block::new);
    }
}
