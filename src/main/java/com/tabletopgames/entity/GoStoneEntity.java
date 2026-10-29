package com.tabletopgames.entity;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.custom.CustomEntity;
import cn.nukkit.entity.custom.EntityDefinition;
import cn.nukkit.entity.data.Vector3fEntityData;
import cn.nukkit.entity.data.property.EntityProperty;
import cn.nukkit.entity.data.property.IntEntityProperty;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.nbt.tag.CompoundTag;
import com.tabletopgames.game.GoBoard;

/**
 * 围棋棋子渲染实体（单实体，对应资源包 {@code tabletopgame:go_stone}）。
 * <p>
 * 采用「一个实体绘制整盘」：19x19 = 361 个交叉点的状态通过「三进制打包」同步给客户端。
 * 落点按 19 路网格线性编号 {@code k = boneRow * 19 + boneCol}，属性 {@code g[K]}（K = k / 15）
 * 存放 15 个连续编号的三进制位，第 {@code o = k % 15} 位的权重为 {@code 3^o}。
 * 单个属性最大 {@code 3^15 - 1 = 14348906 < 2^24}，25 个属性共 375 个槽位，覆盖全部 361 个落点。
 * <p>
 * 几何体每格有<b>两根骨骼</b>：{@code cell_black_RR_CC} 采样贴图黑子色块、
 * {@code cell_white_RR_CC} 采样白子色块；动画按格子状态互斥缩放（{@code ==1} 只显示黑骨、
 * {@code ==2} 只显示白骨），因此一个实体就能同时渲染黑白双方，不再需要黑白两个实体类型。
 * 9 路 / 13 路棋盘复用同一份几何体，由动画按 {@code grid} 属性切换落点间距与棋子尺寸。
 * <p>
 * 该实体只负责渲染：无碰撞、无阴影、不可攻击、不可被 /kill 清除，
 * 收回棋盘的唯一途径是破坏棋盘方块（由 {@link GoBoard#remove()} 直接 close）。
 */
public class GoStoneEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "tabletopgame:go_stone";

    /** 打包属性的个数（25 × 15 = 375 槽，覆盖 361 个落点）。 */
    public static final int PROP_COUNT = 25;

    /** 单个打包属性的最大值：{@code 3^15 - 1}。 */
    public static final int MAX_G = 14348906;

    /** 棋盘方块边长属性（2 / 3 / 4）。 */
    public static final String P_SIZE = "tabletopgame:size";
    /** 路数属性（9 / 13 / 19），决定动画用哪套落点间距。 */
    public static final String P_GRID = "tabletopgame:grid";
    /** 准星命中的交叉点（{@code 0} = 无高亮，否则为骨骼编号 + 1）。 */
    public static final String P_HOVER = "tabletopgame:hover";
    /** 最后一手落点（{@code 0} = 无，否则为骨骼编号 + 1）。 */
    public static final String P_LAST = "tabletopgame:last";

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 基岩版客户端的软边阴影取自实体定义里的碰撞箱，自定义实体在 Nukkit 里无法直接声明，
            // 只能继承一个不绘制阴影的原版实体运行时标识符（armor_stand 明确禁用实体阴影）。
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(GoStoneEntity.class)
            .build();

    /** 打包属性名：{@code tabletopgame:gK}（K = 0..24）。 */
    public static String gName(int index) {
        return "tabletopgame:g" + index;
    }

    /**
     * 注册客户端侧实体属性定义。必须在任何实体生成前调用（插件 onLoad）。
     * 25 个打包属性 + 边长 + 路数 + 准星 + 最后一手 = 29 个，低于基岩版 32 个的上限。
     */
    public static void registerProperties() {
        for (int i = 0; i < PROP_COUNT; i++) {
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(gName(i), 0, MAX_G, 0));
        }
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SIZE, 0, 4, 2));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_GRID, 0, 19, 9));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_HOVER, 0, 361, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_LAST, 0, 361, 0));
    }

    public GoStoneEntity(FullChunk chunk, CompoundTag nbt) {
        super(chunk, nbt);
    }

    @Override
    public EntityDefinition getEntityDefinition() {
        return DEF;
    }

    @Override
    public int getNetworkId() {
        return DEF.getRuntimeId();
    }

    /**
     * 命中箱设为极小值，避免遮挡棋盘方块点击（右键菜单、左键破坏都会穿过实体命中方块）。
     * 棋子模型的渲染由资源包几何体按实体坐标绘制，与命中箱大小无关。
     */
    @Override
    public float getHeight() {
        return 0.001f;
    }

    @Override
    public float getWidth() {
        return 0.001f;
    }

    @Override
    public float getLength() {
        return 0.001f;
    }

    @Override
    protected void initEntity() {
        super.initEntity();
        this.setImmobile(true);
        this.setDataFlag(DATA_FLAGS, DATA_FLAG_GRAVITY, false);
        this.setDataFlag(DATA_FLAGS, DATA_FLAG_HAS_COLLISION, false);
        this.setNameTagVisible(false);
        this.setNameTagAlwaysVisible(false);
        this.setCanBeSavedWithChunk(false);
        this.setDataProperty(new Vector3fEntityData(DATA_COLLISION_BOX, 0.001f, 0.001f, 0.001f));
        this.setIntEntityProperty(P_SIZE, 2);
        this.setIntEntityProperty(P_GRID, 9);
    }

    @Override
    public boolean canCollide() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    /** 棋子仅用于渲染，不参与破坏：攻击无效，收回只能靠破坏棋盘方块。 */
    @Override
    public boolean attack(EntityDamageEvent source) {
        return false;
    }

    /** 禁止一切击杀途径（如 {@code /kill @e}）；正常收回走 {@code close()}，不经过此方法。 */
    @Override
    public void kill() {
    }

    private GoBoard board;

    public void setBoard(GoBoard board) {
        this.board = board;
    }

    public GoBoard getBoard() {
        return board;
    }

    /** 批量提交标记：置位期间所有属性变更只改本地值，不发包。 */
    private boolean batching;

    /** 开始批量提交属性，整盘刷新只发一个元数据包。 */
    public void beginBatch() {
        this.batching = true;
    }

    /** 结束批量提交并一次性同步全部变更。 */
    public void endBatch() {
        this.batching = false;
        this.syncProperties();
    }

    /** 写入第 {@code index} 个打包属性。 */
    public void setG(int index, int value) {
        if (this.setIntEntityProperty(gName(index), value)) {
            this.sync();
        }
    }

    /** 设置棋盘方块边长（2 / 3 / 4），客户端据此缩放整个网格。 */
    public void setBoardSize(int size) {
        int value = size >= 4 ? 4 : size == 3 ? 3 : 2;
        if (this.setIntEntityProperty(P_SIZE, value)) {
            this.sync();
        }
    }

    /** 设置路数（9 / 13 / 19），客户端据此选择落点间距与棋子尺寸。 */
    public void setGrid(int grid) {
        if (this.setIntEntityProperty(P_GRID, grid)) {
            this.sync();
        }
    }

    /** 准星命中的交叉点（骨骼编号 + 1，0 = 无）。 */
    public void setHover(int index) {
        if (this.setIntEntityProperty(P_HOVER, index)) {
            this.sync();
        }
    }

    /** 最后一手落点（骨骼编号 + 1，0 = 无）。 */
    public void setLast(int index) {
        if (this.setIntEntityProperty(P_LAST, index)) {
            this.sync();
        }
    }

    /** 单条属性变更后的同步；处于批量模式时跳过，由 endBatch 统一发包。 */
    protected void sync() {
        if (this.batching) {
            return;
        }
        this.syncProperties();
    }

    public void syncProperties() {
        if (this.getViewers().isEmpty()) {
            return;
        }
        this.sendData(this.getViewers().values().toArray(new Player[0]));
    }
}
