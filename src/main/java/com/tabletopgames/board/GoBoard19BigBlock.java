package com.tabletopgames.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 围棋 19 路大棋盘方块（4x4），对应资源包中的 {@code tabletopgame:go_board_19_4x4}。
 * <p>
 * 384x384 的顶面贴图按 4 等分切成 16 份（每份 96 像素），segment = i + j * 4。
 */
public class GoBoard19BigBlock extends GoBoardBlock {

    public static final String IDENTIFIER = "tabletopgame:go_board_19_4x4";

    /** 0..63：{@code position = segment * 4 + rotation}。 */
    public static final IntBlockProperty POSITION =
            new IntBlockProperty("tabletopgame:position", true, 63, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    private static final String[] GEOMETRIES = new String[16];

    static {
        for (int i = 0; i < GEOMETRIES.length; i++) {
            GEOMETRIES[i] = "geometry.tabletopgame.go_board19_4x4_segment_" + i;
        }
    }

    public GoBoard19BigBlock() {
        this(0);
    }

    public GoBoard19BigBlock(int meta) {
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
        return "围棋棋盘（19 路 · 4×4）";
    }

    public static void register() {
        BoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, GEOMETRIES,
                "围棋棋盘（19 路 · 4×4）", "tabletopgame_go_board_top_19", GoBoard19BigBlock::new);
    }
}
