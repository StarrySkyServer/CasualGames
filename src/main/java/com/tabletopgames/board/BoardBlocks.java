package com.tabletopgames.board;

import cn.nukkit.block.custom.CustomBlockDefinition;
import cn.nukkit.block.custom.CustomBlockManager;
import cn.nukkit.block.custom.container.BlockContainerFactory;
import cn.nukkit.block.custom.container.data.Component;
import cn.nukkit.block.custom.container.data.Geometry;
import cn.nukkit.block.custom.container.data.Materials;
import cn.nukkit.block.custom.container.data.Permutation;
import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;
import cn.nukkit.math.Vector3f;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 棋盘方块注册辅助。
 * <p>
 * 2x2 / 3x3 两种棋盘共用同一套客户端定义：顶面用 {@code tabletopgame_board_top}、侧面用
 * {@code tabletopgame_board_side}，并通过 {@code tabletopgame:position} 属性在 permutation 中切换几何模型。
 * 差异只有几何模型数量与 position 的取值范围，因此抽出此方法复用。
 */
final class BoardBlocks {

    private BoardBlocks() {
    }

    /**
     * @param identifier 方块标识符
     * @param position   position 属性（取值范围决定 permutation 数量）
     * @param properties 方块属性集
     * @param geometries 按 position 索引排列的几何模型标识符
     * @param name       方块显示名
     * @param factory    方块工厂
     */
    static void register(String identifier, IntBlockProperty position, BlockProperties properties,
                         String[] geometries, String name, BlockContainerFactory factory) {
        register(identifier, position, properties, geometries, name, "tabletopgame_board_top", factory);
    }

    /**
     * 同 {@link #register(String, IntBlockProperty, BlockProperties, String[], String, BlockContainerFactory)}，
     * 但可指定顶面贴图名（象棋棋盘用 {@code tabletopgame_xiangqi_board_top}，侧面/底面仍共用五子棋的边框贴图）。
     *
     * @param topTexture 顶面贴图名（须已在 terrain_texture.json 中登记）
     */
    static void register(String identifier, IntBlockProperty position, BlockProperties properties,
                         String[] geometries, String name, String topTexture, BlockContainerFactory factory) {
        Materials materials = Materials.builder();
        materials.any(Materials.RenderMethod.OPAQUE, false, true, topTexture);
        materials.up(Materials.RenderMethod.OPAQUE, false, true, topTexture);
        // 侧面 / 底面使用边框贴图：几何模型里以 material_instance "wood" 引用
        materials.process("wood", false, true, "opaque", "tabletopgame_board_side");

        CustomBlockDefinition.Builder builder = CustomBlockDefinition.builder(factory.create(0))
                .name(name)
                // 客户端挖掘时间与橡木板一致；服务端按 getHardness/getToolType 计算（两者取较小值）。
                // 打上 wood 标签，斧头的 minecraft:digger 才会对其加速。
                .breakTime(2.0)
                .blockTags("wood", "minecraft:is_axe_item_destructible")
                .collisionBox(new Vector3f(-8f, 0f, -8f), new Vector3f(16f, 2f, 16f))
                // 选轮箱必须整格宽（±8）：棋盘由 2x2（3x3 同理）多块拼成，
                // 若像原来那样四周内缩 0.4 模型单位，相邻两块之间会留下约 0.05 格的窄缝，
                // 准星落在这条缝上时客户端判定没有方块命中，表现为「选不到」。
                // 高度取 2（= 0.125 格）与碰撞箱一致，贴合棋盘模型。
                .selectionBox(new Vector3f(-8f, 0f, -8f), new Vector3f(16f, 2f, 16f))
                .creativeCategory(CreativeItemCategory.CONSTRUCTION)
                .registerCreativeItem(false)
                .geometry(new Geometry(geometries[0]))
                .materials(materials);

        // position = segment * 4 + rotation。rotation 为棋盘朝向（0=南/1=西/2=北/3=东）：
        // 方块模型必须跟着棋盘本地坐标系一起转，否则每块的贴图相对棋盘网格会转错。
        // Bedrock 的 [0, y, 0] 旋转：+90° 把模型正前方从北转向西（北→西，逆时针）；
        // 而棋盘朝向每档是顺时针 90°（南→西→北→东），两者方向相反，故朝向项取 -90 * rotation。
        // 另外几何体的 UV 与棋盘纹理相比整体差 180°（贴图上下颠倒），需要每个小块再自转 180° 抵消，
        // 因此最终 yRot = 180 - 90 * rotation（归一化到 0..360）。
        for (int segment = 0; segment < geometries.length; segment++) {
            for (int rotation = 0; rotation < 4; rotation++) {
                int yRot = ((180 - 90 * rotation) % 360 + 360) % 360;
                Component component = Component.builder()
                        .geometry(new Geometry(geometries[segment]))
                        .materialInstances(materials)
                        .rotation(new Vector3f(0f, yRot, 0f))
                        // 消除软边阴影：自定义方块默认 light_dampening=15
                        .lightDampening(0)
                        .build();
                builder.permutation(new Permutation(component,
                        "query.block_property('" + position.getName() + "') == " + (segment * 4 + rotation)));
            }
        }

        CustomBlockManager.get().registerCustomBlock(identifier, properties, builder.build(), factory);
    }
}