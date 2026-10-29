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
import com.tabletopgames.game.XiangqiBoard;

/**
 * 中国象棋棋子渲染实体，对应资源包中的 {@code tabletopgame:xiangqi_piece}。
 * <p>
 * 采用「一个实体绘制整盘」：棋盘 90 个落点的棋子状态通过 32 个「槽位」同步给客户端。
 * 每方最多 16 子（车2 马2 象2 士2 帅1 炮2 兵5），故红 16 + 黑 16 = 32 个槽位，
 * 每根骨骼携带一个棋种的模型，其落点由动画 {@code position} 驱动——这样资源包只需
 * 32 根棋子骨骼（而非每个落点一根）。
 * <p>
 * 槽位值 {@code 0} 表示空槽，否则为 {@code row*9+col+1}（1..90）。为控制属性数量，
 * 每 3 个槽位（各占 7 bit）打包进 1 个整型属性 {@code tabletopgame:xqsN}，共 11 个属性，
 * 单属性最大 2^21-1，远小于 2^24，避免客户端浮点精度问题。
 * 客户端 {@code pre_animation} 解码出 {@code v.s0..v.s31}，动画据此定位并缩放对应骨骼。
 * <p>
 * 该实体只负责渲染：无碰撞、无阴影、不可攻击、不可被 /kill 清除，
 * 收回棋盘的唯一途径是破坏棋盘方块（由 {@link XiangqiBoard#remove()} 直接 close）。
 */
public class XiangqiPieceEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "tabletopgame:xiangqi_piece";

    /** 槽位总数：红 16 + 黑 16（每方 车2 马2 象2 士2 帅1 炮2 兵5）。 */
    public static final int SLOTS = 32;
    /** 单个槽位占用的位数（槽值 0..90，7 bit 足够）。 */
    public static final int SLOT_BITS = 7;
    /** 一个整型属性打包的槽位数。 */
    public static final int SLOTS_PER_PROPERTY = 3;
    /** 打包后的属性个数：{@code ceil(32/3) = 11}。 */
    public static final int SLOT_PROPERTIES = (SLOTS + SLOTS_PER_PROPERTY - 1) / SLOTS_PER_PROPERTY;
    /** 单个槽位的进制（2^7 = 128）。 */
    private static final int SLOT_RADIX = 1 << SLOT_BITS;

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 与五子棋棋子同理：基岩版客户端的软边阴影取自实体定义里的碰撞箱，
            // 自定义实体在 Nukkit 里无法直接声明碰撞箱，只能继承一个不绘制阴影的原版实体运行时标识符。
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(XiangqiPieceEntity.class)
            .build();

    /** 槽位属性名：{@code tabletopgame:xqsN}（N 为打包序号 0..10）。 */
    public static String slotName(int index) {
        return "tabletopgame:xqs" + index;
    }

    /** 某个打包属性能表示的最大值（末组可能不足 3 个槽位）。 */
    public static int slotGroupMax(int index) {
        int slots = Math.min(SLOTS_PER_PROPERTY, SLOTS - index * SLOTS_PER_PROPERTY);
        int max = 1;
        for (int i = 0; i < slots; i++) {
            max *= SLOT_RADIX;
        }
        return max - 1;
    }

    /** 选中格属性：{@code 0} = 选棋状态（未选中），否则为 {@code row*9+col+1}。 */
    public static final String P_SEL = "tabletopgame:xqsel";
    /** 光标格属性：{@code 0} = 无光标，否则为 {@code row*9+col+1}。 */
    public static final String P_CUR = "tabletopgame:xqcur";
    /** 上一手起点属性：{@code 0} = 无，否则为 {@code row*9+col+1}（棋子移走后留下的空位）。 */
    public static final String P_FROM = "tabletopgame:xqfrom";
    /** 上一手落点属性：{@code 0} = 无，否则为 {@code row*9+col+1}（走子方常驻虚线标记的位置）。 */
    public static final String P_LAST = "tabletopgame:xqlast";
    /** 当前行棋方属性：{@code 0} = 红，{@code 1} = 黑（决定两个虚线标记分别绑定哪一方）。 */
    public static final String P_TURN = "tabletopgame:xqturn";

    /** 单个落点属性的最大值：{@code 9*9+8+1 = 90}。 */
    private static final int CELL_MAX = XiangqiBoard.ROWS * XiangqiBoard.COLS;

    /**
     * 注册客户端侧实体属性定义。必须在任何实体生成前调用（插件 onLoad）。
     */
    public static void registerProperties() {
        for (int i = 0; i < SLOT_PROPERTIES; i++) {
            EntityProperty.register(IDENTIFIER,
                    new IntEntityProperty(slotName(i), 0, slotGroupMax(i), 0));
        }
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SEL, 0, CELL_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_CUR, 0, CELL_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_FROM, 0, CELL_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_LAST, 0, CELL_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_TURN, 0, 1, 0));
    }

    public XiangqiPieceEntity(FullChunk chunk, CompoundTag nbt) {
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

    private XiangqiBoard board;

    public void setBoard(XiangqiBoard board) {
        this.board = board;
    }

    public XiangqiBoard getBoard() {
        return board;
    }

    /** 批量提交标记：置位期间所有属性变更只改本地值，不发包。 */
    private boolean batching;

    /**
     * 开始批量提交属性。棋子槽位 + 5 个标记共 16 个属性，开局/变盘若逐条同步会连发多个元数据包，
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
     * 批量写入 32 个槽位（{@code cells[k]} = 0 表示空槽，否则 {@code row*9+col+1}）。
     * 每 3 个槽位（各 7 bit）打包进 1 个整型属性。
     */
    public void setSlots(int[] cells) {
        for (int group = 0; group < SLOT_PROPERTIES; group++) {
            int value = 0;
            for (int i = SLOTS_PER_PROPERTY - 1; i >= 0; i--) {
                int slot = group * SLOTS_PER_PROPERTY + i;
                if (slot >= SLOTS) {
                    continue;
                }
                value = value * SLOT_RADIX + cells[slot];
            }
            if (this.setIntEntityProperty(slotName(group), value)) {
                this.sync();
            }
        }
    }

    /** 设置选中格（0 = 选棋状态）。 */
    public void setSel(int value) {
        if (this.setIntEntityProperty(P_SEL, value)) {
            this.sync();
        }
    }

    /** 设置光标格（0 = 无光标）。 */
    public void setCur(int value) {
        if (this.setIntEntityProperty(P_CUR, value)) {
            this.sync();
        }
    }

    /** 设置上一手起点格（0 = 无）。 */
    public void setFrom(int value) {
        if (this.setIntEntityProperty(P_FROM, value)) {
            this.sync();
        }
    }

    /** 设置上一手落点格（0 = 无）。 */
    public void setLast(int value) {
        if (this.setIntEntityProperty(P_LAST, value)) {
            this.sync();
        }
    }

    /** 设置当前行棋方（0 = 红 / 1 = 黑）。 */
    public void setTurn(int value) {
        if (this.setIntEntityProperty(P_TURN, value)) {
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
