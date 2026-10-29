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
import com.tabletopgames.game.TabletopGamesBoard;

/**
 * 五子棋棋子渲染实体（单实体，对应资源包 {@code tabletopgame:stone}）。
 * <p>
 * 采用「一个实体绘制整盘」：棋盘 225 个落点的棋子状态通过「按行三进制编码」同步给客户端。
 * 每行 15 格，每格取值 0=空 / 1=黑 / 2=白，整行编码为 {@code Σ cell[c]*3^c}，最大
 * {@code 3^15-1 = 14348906 < 2^24}，避免客户端浮点精度问题。15 个行属性 + 边长 + 准星 + 最后一手
 * = 18 个属性，低于基岩版「每个实体类型最多 32 个 Entity Property」的上限。
 * <p>
 * 几何体每格有<b>两根骨骼</b>：{@code cell_black_RR_CC} 采样贴图黑子色块、
 * {@code cell_white_RR_CC} 采样白子色块；动画按格子状态互斥缩放（{@code ==1} 只显示黑骨、
 * {@code ==2} 只显示白骨），因此一个实体就能同时渲染黑白双方，不再需要黑白两个实体类型。
 * <p>
 * 该实体只负责渲染：无碰撞、无阴影、不可攻击、不可被 /kill 清除，
 * 收回棋盘的唯一途径是破坏棋盘方块（由 {@link TabletopGamesBoard#remove()} 直接 close）。
 */
public class TabletopGamesStoneEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "tabletopgame:stone";

    /** 棋盘边长（网格始终为 15x15）。 */
    public static final int SIZE = 15;

    /** 单行三进制编码的最大值：{@code 3^15-1}。 */
    public static final int MAX_ROW_VALUE = 14348906;

    /** 棋盘边长属性（2 = 标准，3 = 中号，4 = 大号）。 */
    public static final String P_SIZE = "tabletopgame:size";
    /** 准星命中的网格交点（{@code 0} = 无高亮，否则为 {@code row*15+col+1}）。 */
    public static final String P_HOVER = "tabletopgame:hover";
    /** 最后一手落点（{@code 0} = 无，否则为 {@code row*15+col+1}）。 */
    public static final String P_LAST = "tabletopgame:last";

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 基岩版客户端的软边阴影取自实体定义里的碰撞箱，自定义实体在 Nukkit 里无法直接声明，
            // 只能继承一个不绘制阴影的原版实体运行时标识符（armor_stand 明确禁用实体阴影）。
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(TabletopGamesStoneEntity.class)
            .build();

    /** 行编码属性名：{@code tabletopgame:row_R}（R = 0..14）。 */
    public static String rowName(int row) {
        return "tabletopgame:row_" + row;
    }

    /**
     * 注册客户端侧实体属性定义。必须在任何实体生成前调用（插件 onLoad）。
     * 15 个行编码 + 边长 + 准星 + 最后一手 = 18 个。
     */
    public static void registerProperties() {
        for (int r = 0; r < SIZE; r++) {
            EntityProperty.register(IDENTIFIER,
                    new IntEntityProperty(rowName(r), 0, MAX_ROW_VALUE, 0));
        }
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SIZE, 0, 4, 2));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_HOVER, 0, SIZE * SIZE, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_LAST, 0, SIZE * SIZE, 0));
    }

    public TabletopGamesStoneEntity(FullChunk chunk, CompoundTag nbt) {
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
        // 再同步一个极小碰撞箱，双保险：即便某些版本按 metadata 绘制阴影/命中箱也能消掉
        this.setDataProperty(new Vector3fEntityData(DATA_COLLISION_BOX, 0.001f, 0.001f, 0.001f));
        this.setIntEntityProperty(P_SIZE, 2);
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

    private TabletopGamesBoard board;

    public void setBoard(TabletopGamesBoard board) {
        this.board = board;
    }

    public TabletopGamesBoard getBoard() {
        return board;
    }

    /** 批量提交标记：置位期间所有属性变更只改本地值，不发包。 */
    private boolean batching;

    /**
     * 开始批量提交属性。整盘刷新会同时改动 15 个行属性，若逐条同步会连发十几个元数据包，
     * 用 beginBatch/endBatch 包住后整批只发一个。
     */
    public void beginBatch() {
        this.batching = true;
    }

    /** 结束批量提交并一次性同步全部变更。 */
    public void endBatch() {
        this.batching = false;
        this.syncProperties();
    }

    /**
     * 写入某一行的三进制编码（{@code value = Σ cell[c]*3^c}，cell 取值 0/1/2）。
     */
    public void setRow(int row, int value) {
        if (this.setIntEntityProperty(rowName(row), value)) {
            this.sync();
        }
    }

    /** 设置棋盘边长（2 = 标准，3 = 中号，4 = 大号），客户端据此缩放整个网格。 */
    public void setBoardSize(int size) {
        int value = size >= 4 ? 4 : size == 3 ? 3 : 2;
        if (this.setIntEntityProperty(P_SIZE, value)) {
            this.sync();
        }
    }

    /** 准星命中的网格交点（{@code row*15+col+1}，0 = 无）。 */
    public void setHover(int index) {
        if (this.setIntEntityProperty(P_HOVER, index)) {
            this.sync();
        }
    }

    /** 最后一手落点（{@code row*15+col+1}，0 = 无）。 */
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
