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
import com.tabletopgames.game.OthelloBoard;

/**
 * 黑白棋（奥赛罗）棋子渲染实体，对应资源包中的 {@code tabletopgame:othello_disc}。
 * <p>
 * 采用「一个实体绘制整盘」：8x8 共 64 个落点的棋子状态通过每行 1 个三进制属性同步给客户端。
 * 每行 8 格按 base-3 打包：单格 0=空、1=黑、2=白，行值 = Σ cell[c]·3^c（0..6560）。
 * 客户端 {@code pre_animation} 解码出 {@code v.rN}（第 N 格的行余数），动画据此决定该格骨骼的显隐与 180° 翻转。
 * <p>
 * 可落点提示用 8 个位掩码属性 {@code tabletopgame:ocan_0..7}（每行 1 个，bit c 表示该格是否可落子），
 * 上一手落点用 {@code tabletopgame:olast}（{@code row*8+col+1}，0 表示无）。
 * <p>
 * 该实体只负责渲染：无碰撞、无阴影、不可攻击、不可被 /kill 清除，
 * 收回棋盘的唯一途径是破坏棋盘方块（由 {@link OthelloBoard#remove()} 直接 close）。
 */
public class OthelloDiscEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "tabletopgame:othello_disc";

    /** 棋盘边长（8）。 */
    public static final int N = 8;

    /** 单行三进制编码的最大值：{@code 3^8 - 1 = 6560}。 */
    public static final int ROW_MAX = 6560;

    /** 单行可落点位掩码的最大值：{@code 2^8 - 1 = 255}。 */
    public static final int CAN_MAX = 255;

    /** 上一手落点的最大值：{@code 8*8 = 64}。 */
    public static final int LAST_MAX = N * N;

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 与其它棋子同理：基岩版客户端的软边阴影取自实体定义里的碰撞箱，
            // 自定义实体在 Nukkit 里无法直接声明碰撞箱，只能继承一个不绘制阴影的原版实体运行时标识符。
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(OthelloDiscEntity.class)
            .build();

    /** 第 {@code row} 行的三进制状态属性名。 */
    public static String rowName(int row) {
        return "tabletopgame:orow_" + row;
    }

    /** 第 {@code row} 行的可落点掩码属性名。 */
    public static String canName(int row) {
        return "tabletopgame:ocan_" + row;
    }

    /** 上一手落点属性名（{@code row*8+col+1}，0 = 无）。 */
    public static final String P_LAST = "tabletopgame:olast";

    /** 准星瞄准格属性名（{@code row*8+col+1}，0 = 未瞄准/不显示）。 */
    public static final String P_HOVER = "tabletopgame:ohover";

    /**
     * 注册客户端侧实体属性定义。必须在任何实体生成前调用（插件 onLoad）。
     */
    public static void registerProperties() {
        for (int row = 0; row < N; row++) {
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(rowName(row), 0, ROW_MAX, 0));
        }
        for (int row = 0; row < N; row++) {
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(canName(row), 0, CAN_MAX, 0));
        }
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_LAST, 0, LAST_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_HOVER, 0, LAST_MAX, 0));
    }

    public OthelloDiscEntity(FullChunk chunk, CompoundTag nbt) {
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

    private OthelloBoard board;

    public void setBoard(OthelloBoard board) {
        this.board = board;
    }

    public OthelloBoard getBoard() {
        return board;
    }

    /** 批量提交标记：置位期间所有属性变更只改本地值，不发包。 */
    private boolean batching;

    /**
     * 开始批量提交属性。整盘 17 个属性开局/变盘若逐条同步会连发多个元数据包，
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

    /** 批量写入 8 行的三进制状态（每行 0..6560）。 */
    public void setRows(int[] rows) {
        for (int row = 0; row < N; row++) {
            if (this.setIntEntityProperty(rowName(row), rows[row])) {
                this.sync();
            }
        }
    }

    /** 批量写入 8 行的可落点位掩码（每行 0..255）。 */
    public void setCan(int[] can) {
        for (int row = 0; row < N; row++) {
            if (this.setIntEntityProperty(canName(row), can[row])) {
                this.sync();
            }
        }
    }

    /** 设置上一手落点格（0 = 无，否则 {@code row*8+col+1}）。 */
    public void setLast(int value) {
        if (this.setIntEntityProperty(P_LAST, value)) {
            this.sync();
        }
    }

    /** 设置准星瞄准格（0 = 不显示，否则 {@code row*8+col+1}）。 */
    public void setHover(int value) {
        if (this.setIntEntityProperty(P_HOVER, value)) {
            this.sync();
        }
    }

    /** 单条属性变更后的同步；处于批量模式时跳过，由 endBatch 统一发包。 */
    private void sync() {
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
