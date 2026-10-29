package com.tabletopgames.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 中号 3x3 棋盘方块，对应资源包中的 tabletopgame:board_3x3。
 * <p>
 * 由 3x3 = 9 个方块拼接而成，每个方块仍只占 1 格。棋盘贴图（256x256）按 3 等分切成 9 份，
 * 每份 85 像素（取 255/3，相邻两份在 85/170 处严丝合缝，仅丢掉最外圈 1 像素）。
 * <p>
 * 与 2x2 不同，3x3 的放置区域以点击方块为中心（见 {@code TabletopGamesManager#tryPlace}）。
 * <p>
 * 通过 {@code tabletopgame:position} 属性（0..35）选择对应的一份几何模型。
 * position = segment * 4 + rotation，segment = dx + dz * 3，其中 dx/dz 为该方块在 3x3 区域中的偏移。
 */
public class TabletopGamesBoard3x3Block extends BaseTabletopGamesBoardBlock {

    public static final String IDENTIFIER = "tabletopgame:board_3x3";

    /** 0..35：{@code position = segment * 4 + rotation}。segment 0..8 选择 9 等份贴图，rotation 0..3 为棋盘朝向。 */
    public static final IntBlockProperty POSITION =
            new IntBlockProperty("tabletopgame:position", true, 35, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    /** 9 份几何模型：索引 = segment，up 面 UV 偏移为 (dx*85, dz*85)。 */
    private static final String[] GEOMETRIES = new String[9];

    static {
        for (int i = 0; i < GEOMETRIES.length; i++) {
            GEOMETRIES[i] = "geometry.tabletopgame.board3_segment_" + i;
        }
    }

    public TabletopGamesBoard3x3Block() {
        this(0);
    }

    public TabletopGamesBoard3x3Block(int meta) {
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
        return "五子棋棋盘（3×3）";
    }

    /**
     * 注册自定义方块。必须在 CustomBlockManager 关闭注册表之前调用（插件 onLoad / STARTUP）。
     */
    public static void register() {
        BoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, GEOMETRIES,
                "五子棋棋盘（3×3）", TabletopGamesBoard3x3Block::new);
    }
}