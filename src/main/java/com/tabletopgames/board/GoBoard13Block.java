package com.tabletopgames.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 围棋 13 路棋盘方块（2x2），对应资源包中的 {@code tabletopgame:go_board_13}。
 */
public class GoBoard13Block extends GoBoardBlock {

    public static final String IDENTIFIER = "tabletopgame:go_board_13";

    /** 0..15：{@code position = segment * 4 + rotation}。 */
    public static final IntBlockProperty POSITION =
            new IntBlockProperty("tabletopgame:position", true, 15, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    public GoBoard13Block() {
        this(0);
    }

    public GoBoard13Block(int meta) {
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
        return "围棋棋盘（13 路）";
    }

    public static void register() {
        BoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, quadGeometries(),
                "围棋棋盘（13 路）", "tabletopgame_go_board_top_13", GoBoard13Block::new);
    }
}
