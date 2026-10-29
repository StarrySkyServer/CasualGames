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
 * 军旗棋盘方块注册辅助（3 列 × 3 行 = 9 段，**每段一张 128×128 独立贴图**）。
 *
 * <p>{@code position = segment * 4 + rotation}。每个 permutation 带自己的 {@link Materials}
 * （各自的顶面贴图）+ 几何模型 + 朝向旋转。</p>
 *
 * <p>⚠️ 不要退回「一张 256×384 大图切 6 个 UV 象限」：方块贴图会被拼进地形图集，
 * 非正方形尺寸在客户端采样不对（2026-10-22 真机整盘花纹错位）。</p>
 */
final class JunqiBoardBlocks {

    private JunqiBoardBlocks() {
    }

    /**
     * @param geometries  按 segment 索引的几何模型标识符
     * @param topTextures 按 segment 索引的顶面贴图名（须已在 terrain_texture.json 登记）
     */
    static void register(String identifier, IntBlockProperty position, BlockProperties properties,
                         String[] geometries, String[] topTextures, String name,
                         BlockContainerFactory factory) {
        if (geometries.length != topTextures.length) {
            throw new IllegalArgumentException("geometries/topTextures 数量不一致");
        }
        CustomBlockDefinition.Builder builder = CustomBlockDefinition.builder(factory.create(0))
                .name(name)
                .breakTime(2.0)
                .blockTags("wood", "minecraft:is_axe_item_destructible")
                // 碰撞箱/选轮箱：整格宽 + 2 像素高（贴合棋盘模型，避免相邻块之间出现"选不到"的缝）
                .collisionBox(new Vector3f(-8f, 0f, -8f), new Vector3f(16f, 2f, 16f))
                .selectionBox(new Vector3f(-8f, 0f, -8f), new Vector3f(16f, 2f, 16f))
                .creativeCategory(CreativeItemCategory.CONSTRUCTION)
                .registerCreativeItem(false)
                .geometry(new Geometry(geometries[0]))
                .materials(materialsFor(topTextures[0]));

        for (int segment = 0; segment < geometries.length; segment++) {
            Materials materials = materialsFor(topTextures[segment]);
            // yRot = 180 - 90*rotation：几何 UV 与棋盘纹理整体差 180°，每块自转抵消；
            // 再叠棋盘朝向（顺时针 90°/档，与 Bedrock 的 +Y 旋转方向相反）。
            for (int rotation = 0; rotation < 4; rotation++) {
                int yRot = ((180 - 90 * rotation) % 360 + 360) % 360;
                Component component = Component.builder()
                        .geometry(new Geometry(geometries[segment]))
                        .materialInstances(materials)
                        .rotation(new Vector3f(0f, yRot, 0f))
                        .lightDampening(0)
                        .build();
                builder.permutation(new Permutation(component,
                        "query.block_property('" + position.getName() + "') == " + (segment * 4 + rotation)));
            }
        }

        CustomBlockManager.get().registerCustomBlock(identifier, properties, builder.build(), factory);
    }

    private static Materials materialsFor(String topTexture) {
        Materials materials = Materials.builder();
        materials.any(Materials.RenderMethod.OPAQUE, false, true, topTexture);
        materials.up(Materials.RenderMethod.OPAQUE, false, true, topTexture);
        // 侧面/底面：几何里以 material_instance "wood" 引用
        materials.process("wood", false, true, "opaque", "junqi_board_side");
        return materials;
    }
}
