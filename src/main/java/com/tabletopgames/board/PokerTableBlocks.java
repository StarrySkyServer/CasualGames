package com.tabletopgames.board;

import cn.nukkit.block.custom.CustomBlockDefinition;
import cn.nukkit.block.custom.CustomBlockManager;
import cn.nukkit.block.custom.container.BlockContainerFactory;
import cn.nukkit.block.custom.container.CustomBlockMeta;
import cn.nukkit.block.custom.container.data.Component;
import cn.nukkit.block.custom.container.data.Geometry;
import cn.nukkit.block.custom.container.data.Materials;
import cn.nukkit.block.custom.container.data.Permutation;
import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;
import cn.nukkit.item.ItemTool;
import cn.nukkit.level.Level;
import cn.nukkit.math.AxisAlignedBB;
import cn.nukkit.math.SimpleAxisAlignedBB;
import cn.nukkit.math.Vector3;
import cn.nukkit.math.Vector3f;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 德州扑克物理牌桌（3x3）：中心 1 + 边 4 + 角 4。
 *
 * <p>与 {@link DdzTableBlocks} <b>完全同构</b>，只有三处不同：方块 identifier、
 * facing 属性名（{@code tabletopgame:poker_facing}）、贴图名
 * （{@code tabletopgame_poker_physical_table}）。<b>几何体直接复用斗地主的 9 个 geo</b>，
 * 不新建模型 —— 这是用户「桌子模型照搬、只换贴图」的要求。</p>
 */
public final class PokerTableBlocks {

    static final double TOP = 15.0D / 16.0D;

    public static final String CENTER = "tabletopgame:poker_physical_table_center";
    public static final String EDGE = "tabletopgame:poker_physical_table_edge";
    public static final String CORNER = "tabletopgame:poker_physical_table_corner";

    private static final String TEXTURE = "tabletopgame_poker_physical_table";

    private static final IntBlockProperty CENTER_FACING =
            new IntBlockProperty("tabletopgame:poker_facing", true, 3, 0);
    private static final IntBlockProperty EDGE_FACING =
            new IntBlockProperty("tabletopgame:poker_facing", true, 3, 0);
    private static final IntBlockProperty CORNER_FACING =
            new IntBlockProperty("tabletopgame:poker_facing", true, 3, 0);

    private PokerTableBlocks() {
    }

    public abstract static class PokerTableBlock extends CustomBlockMeta {

        protected PokerTableBlock(String identifier, BlockProperties properties, int meta) {
            super(identifier, properties, meta);
        }

        @Override
        public double getHardness() {
            return 4.0;
        }

        @Override
        public boolean isBreakable(cn.nukkit.item.Item item) {
            return true;
        }

        @Override
        public double getResistance() {
            return 20.0;
        }

        @Override
        public int getToolType() {
            return ItemTool.TYPE_AXE;
        }

        @Override
        public boolean canBeActivated() {
            return true;
        }

        @Override
        protected AxisAlignedBB recalculateBoundingBox() {
            return new SimpleAxisAlignedBB(this.x, this.y, this.z,
                    this.x + 1.0D, this.y + TOP, this.z + 1.0D);
        }
    }

    public static class CenterBlock extends PokerTableBlock {
        private static final BlockProperties PROPERTIES = new BlockProperties(CENTER_FACING);

        public CenterBlock() {
            this(0);
        }

        public CenterBlock(int meta) {
            super(CENTER, PROPERTIES, meta);
        }

        @Override
        public String getName() {
            return "德州扑克牌桌";
        }
    }

    public static class EdgeBlock extends PokerTableBlock {
        private static final BlockProperties PROPERTIES = new BlockProperties(EDGE_FACING);

        public EdgeBlock() {
            this(0);
        }

        public EdgeBlock(int meta) {
            super(EDGE, PROPERTIES, meta);
        }

        public int getFacing() {
            return this.getIntValue(EDGE_FACING);
        }

        public void setFacing(int facing) {
            this.setIntValue(EDGE_FACING, facing & 3);
        }

        @Override
        public String getName() {
            return "德州扑克牌桌（边）";
        }
    }

    public static class CornerBlock extends PokerTableBlock {
        private static final BlockProperties PROPERTIES = new BlockProperties(CORNER_FACING);

        public CornerBlock() {
            this(0);
        }

        public CornerBlock(int meta) {
            super(CORNER, PROPERTIES, meta);
        }

        public int getFacing() {
            return this.getIntValue(CORNER_FACING);
        }

        public void setFacing(int facing) {
            this.setIntValue(CORNER_FACING, facing & 3);
        }

        @Override
        public String getName() {
            return "德州扑克牌桌（角）";
        }
    }

    public static void register() {
        registerOne(CENTER, new String[]{"geometry.tabletopgame.ddz_physical_table_center"},
                "德州扑克牌桌", CENTER_FACING, CenterBlock::new);
        registerOne(EDGE, new String[]{
                        "geometry.tabletopgame.ddz_physical_table_edge_n",
                        "geometry.tabletopgame.ddz_physical_table_edge_w",
                        "geometry.tabletopgame.ddz_physical_table_edge_s",
                        "geometry.tabletopgame.ddz_physical_table_edge_e"},
                "德州扑克牌桌（边）", EDGE_FACING, EdgeBlock::new);
        registerOne(CORNER, new String[]{
                        "geometry.tabletopgame.ddz_physical_table_corner_ne",
                        "geometry.tabletopgame.ddz_physical_table_corner_nw",
                        "geometry.tabletopgame.ddz_physical_table_corner_sw",
                        "geometry.tabletopgame.ddz_physical_table_corner_se"},
                "德州扑克牌桌（角）", CORNER_FACING, CornerBlock::new);
    }

    private static void registerOne(String identifier, String[] geometries, String name,
                                    IntBlockProperty facing, BlockContainerFactory factory) {
        Materials materials = Materials.builder();
        materials.any(Materials.RenderMethod.OPAQUE, false, true, TEXTURE);
        materials.up(Materials.RenderMethod.OPAQUE, false, true, TEXTURE);

        CustomBlockDefinition.Builder builder = CustomBlockDefinition.builder(factory.create(0))
                .name(name)
                .breakTime(4.0)
                .blockTags("wood", "minecraft:is_axe_item_destructible")
                .collisionBox(new Vector3f(-8f, 0f, -8f), new Vector3f(16f, 15f, 16f))
                .selectionBox(new Vector3f(-8f, 0f, -8f), new Vector3f(16f, 16f, 16f))
                .creativeCategory(CreativeItemCategory.CONSTRUCTION)
                .registerCreativeItem(false)
                .geometry(new Geometry(geometries[0]))
                .materials(materials);

        for (int f = 0; f < 4; f++) {
            String geometry = geometries.length == 1 ? geometries[0] : geometries[f];
            Component component = Component.builder()
                    .geometry(new Geometry(geometry))
                    .materialInstances(materials)
                    .rotation(new Vector3f(0f, 0f, 0f))
                    .lightDampening(0)
                    .build();
            builder.permutation(new Permutation(component,
                    "query.block_property('" + facing.getName() + "') == " + f));
        }
        CustomBlockManager.get().registerCustomBlock(identifier,
                new BlockProperties(facing), builder.build(), factory);
    }

    /** 把 3x3 区域写回空气（拆桌 / 重建前清理）。 */
    public static void clear(Level level, int bx, int by, int bz) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (level.getBlock(bx + i, by, bz + j) instanceof PokerTableBlock) {
                    level.setBlock(new Vector3(bx + i, by, bz + j),
                            new cn.nukkit.block.BlockAir(), true);
                }
            }
        }
    }
}
