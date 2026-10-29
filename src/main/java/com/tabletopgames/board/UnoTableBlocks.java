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
import cn.nukkit.math.AxisAlignedBB;
import cn.nukkit.math.SimpleAxisAlignedBB;
import cn.nukkit.math.Vector3f;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * UNO 物理牌桌方块（3x3）：中心 1 块 + 边 4 块 + 角 4 块。
 * <p>
 * 每格各挂一张「本格切片」几何体（<b>必须</b>落在方块自身的 16x16x16 单位内，
 * 越界部分会被 Bedrock 客户端剔除而整块不可见）：整桌在几何空间里是 48x48x16，
 * 即「中间 42x42 高 15 的台面 + 外围 3 宽高 16 的边框」，按 3x3 切成 9 份——
 * 中心格只有台面切片，边格是「台面切片 + 一条边框」，角格是「台面 + 两条边框」。
 * <p>
 * 三个方块都带 {@code tabletopgame:uno_facing} 属性并生成 4 个 permutation，每个 facing 挂一张
 * <b>独立几何体、不做任何旋转</b>：贴图是「世界对齐」的（绿绒布 48x48 铺满整桌、木边框取自 x48+ 区域），
 * 若改用 [0,y,0] 旋转会连 UV 一起转，导致东/南/西的边块与四个角块采样的贴图区域随之错位
 * （木纹砖线被转竖、绿绒布花纹相位对不上）。
 * facing 取值：边块 0=北 1=西 2=南 3=东；角块 0=NE 1=NW 2=SW 3=SE。
 * <p>
 * <b>重要：Bedrock 方块 geometry 的局部 +X 与世界 -X 同向</b>（方块模型 X 轴是镜像的，
 * entity/attachable 模型没有这个特性）。也就是说几何体里写在西边的方块，游戏里会长在
 * 东边。因此 {@code models/blocks/uno/*.geo.json} 里所有 cube 的 x 都已<b>预先镜像</b>
 * （origin_x' = -(origin_x + size_x)），让客户端再镜像一次后正好落到预期位置。
 * 这些几何体由 {@code tools/uno/port_table_block.py} 从斗地主的已验证版本改名而来，
 * 新增/修改时必须沿用这条约定，否则木框会左右反。
 * <p>
 * UNO 牌桌与四棋棋盘的区别：牌桌是 1 格高的实心台子（碰撞箱 0..15，见 {@link #TOP}），
 * 棋盘只有 2 像素高；因此这里不复用 {@code BaseTabletopGamesBoardBlock}。
 */
public final class UnoTableBlocks {

    /**
     * 碰撞箱顶面高度（格）= 15/16，比整格低 1 像素。
     * <p>模型本来就是「外圈木框 16 高 + 中间台面 15 高」的一个坑：
     * 中心块整个是 {@code 16x15x16}，边块/角块是「台面 15 + 木框 16」。
     * 碰撞箱以前写的是整格 16，等于把那个坑填平了 —— 任何落到台面上的东西
     * （掉落物、实体、玩家）都会比画面高 1 像素，看着像悬空。压到 15 就贴合台面。</p>
     * <p>⚠️ 客户端 {@code minecraft:collision_box} 只支持<b>一个</b>箱体，没法表达
     * 边块/角块那种「台面 15 + 木框 16」的 L 形，所以木框顶那 1 像素是软的（能站进去 1 像素）。
     * 木框只有 3 像素宽且在桌子最外沿，看不出来；<b>台面必须准，木框可以软</b>。</p>
     */
    static final double TOP = 15.0D / 16.0D;

    public static final String CENTER = "tabletopgame:uno_physical_table_center";
    public static final String EDGE = "tabletopgame:uno_physical_table_edge";
    public static final String CORNER = "tabletopgame:uno_physical_table_corner";

    /** 顶面/侧面/底面共用的贴图名，由 terrain_texture.json 的 {@code tabletopgame_uno_physical_table} 登记。 */
    private static final String TEXTURE = "tabletopgame_uno_physical_table";

    /**
     * 边/角朝向：0=棱边朝北，1=朝西，2=朝南，3=朝东。
     * <p>
     * 三个方块类型各自持有独立实例（与四棋「每种棋盘一份自己的属性」一致），
     * 避免多个方块定义共享同一个属性对象时，客户端收到的 permutation 数据互相串味。
     */
    private static final IntBlockProperty CENTER_FACING = new IntBlockProperty("tabletopgame:uno_facing", true, 3, 0);
    private static final IntBlockProperty EDGE_FACING = new IntBlockProperty("tabletopgame:uno_facing", true, 3, 0);
    private static final IntBlockProperty CORNER_FACING = new IntBlockProperty("tabletopgame:uno_facing", true, 3, 0);

    private UnoTableBlocks() {
    }

    // ------------------------------------------------------------------
    // 方块类
    // ------------------------------------------------------------------

    /** 牌桌方块公共行为：硬度较高、木质音效、碰撞箱 = 台面高度（0..{@link #TOP}）。 */
    public abstract static class UnoTableBlock extends CustomBlockMeta {

        protected UnoTableBlock(String identifier, BlockProperties properties, int meta) {
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

        /**
         * 服务端碰撞箱与客户端 {@code minecraft:collision_box} 必须一致：都到 {@link #TOP}。
         * <p>MOT 的实体物理走 {@code CollisionHelper.getCollisionCubes → Block.addCollisionBoxesToList
         * → Block.getBoundingBox()}，也就是<b>这个方法</b>；只改客户端那个组件不改这里，
         * 服务端仍按整格算，会出现「客户端踩空 / 被弹起」的拉扯。</p>
         */
        @Override
        protected AxisAlignedBB recalculateBoundingBox() {
            return new SimpleAxisAlignedBB(this.x, this.y, this.z,
                    this.x + 1.0D, this.y + TOP, this.z + 1.0D);
        }
    }

    /** 中心块（几何对称，facing 形同虚设，仅用于统一走 permutation 通道）。 */
    public static class CenterBlock extends UnoTableBlock {

        private static final BlockProperties PROPERTIES = new BlockProperties(CENTER_FACING);

        public CenterBlock() {
            this(0);
        }

        public CenterBlock(int meta) {
            super(CENTER, PROPERTIES, meta);
        }

        @Override
        public String getName() {
            return "UNO 牌桌";
        }
    }

    /** 边块（棱边朝向由 facing 决定）。 */
    public static class EdgeBlock extends UnoTableBlock {

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
            return "UNO 牌桌（边）";
        }
    }

    /** 角块（模型棱边在北+东，即 NE 角；facing 旋转到四角）。 */
    public static class CornerBlock extends UnoTableBlock {

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
            return "UNO 牌桌（角）";
        }
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /** 注册三个牌桌方块（插件 onLoad 期调用）。 */
    public static void register() {
        registerOne(CENTER, new String[]{"geometry.tabletopgame.uno_physical_table_center"},
                "UNO 牌桌", CENTER_FACING, CenterBlock::new);
        registerOne(EDGE, new String[]{
                        "geometry.tabletopgame.uno_physical_table_edge_n",
                        "geometry.tabletopgame.uno_physical_table_edge_w",
                        "geometry.tabletopgame.uno_physical_table_edge_s",
                        "geometry.tabletopgame.uno_physical_table_edge_e"},
                "UNO 牌桌（边）", EDGE_FACING, EdgeBlock::new);
        registerOne(CORNER, new String[]{
                        "geometry.tabletopgame.uno_physical_table_corner_ne",
                        "geometry.tabletopgame.uno_physical_table_corner_nw",
                        "geometry.tabletopgame.uno_physical_table_corner_sw",
                        "geometry.tabletopgame.uno_physical_table_corner_se"},
                "UNO 牌桌（角）", CORNER_FACING, CornerBlock::new);
    }

    /**
     * @param geometries 按 facing 索引排列的几何体标识符；长度 1（中心对称块）时 4 个 facing 共用
     * @param facing     本方块类型自己的朝向属性（每个类型一份，不与其他类型共享）
     */
    private static void registerOne(String identifier, String[] geometries, String name,
                                    IntBlockProperty facing, BlockContainerFactory factory) {
        Materials materials = Materials.builder();
        materials.any(Materials.RenderMethod.OPAQUE, false, true, TEXTURE);
        materials.up(Materials.RenderMethod.OPAQUE, false, true, TEXTURE);

        CustomBlockDefinition.Builder builder = CustomBlockDefinition.builder(factory.create(0))
                .name(name)
                .breakTime(4.0)
                .blockTags("wood", "minecraft:is_axe_item_destructible")
                // 碰撞箱顶面 15/16：贴合模型里那个凹 1 像素的台面（见 TOP）。
                // 选箱仍是整格 16：牌桌 3x3 拼接，选箱不能内缩，否则准星落缝上选不中；
                // 高度留整格，木框顶那 1 像素也还能点中、镶边轮廓也还是完整的立方体。
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
                    // 消除软边阴影：自定义方块默认 light_dampening=15
                    .lightDampening(0)
                    .build();
            builder.permutation(new Permutation(component,
                    "query.block_property('" + facing.getName() + "') == " + f));
        }

        CustomBlockManager.get().registerCustomBlock(identifier,
                new BlockProperties(facing), builder.build(), factory);
    }
}
