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
import cn.nukkit.network.protocol.AnimateEntityPacket;
import com.tabletopgames.game.JunqiBoard;

import java.util.ArrayList;
import java.util.List;

/**
 * 军旗棋子渲染实体，对应资源包中的 {@code tabletopgame:junqi_piece}。
 *
 * <p>与象棋/斗兽棋一样「一个实体画整盘」：50 个槽位（红 25 + 黑 25）通过 17 个整型属性同步，
 * 每槽 8 bit（0=空，1..60=棋盘点位，65..124=已亮明点位，128..151=淘汰格），
 * 3 槽打包进 1 个属性（进制 256，24 bit 恰好等于 16777215，客户端 float 可精确表示）。</p>
 *
 * <p>额外 3 个标记属性：{@code m0 = selR + selB*64}、{@code m1 = curR + curB*64}、
 * {@code m2 = from + last*64}，以及 {@code turn}（决定虚线颜色）。共 <b>21 个属性</b>。</p>
 *
 * <p><b>逐观看者</b>：属性是全局的，但「自己看正面 / 对方看单色 / 黄框只有本人可见」因人而异。
 * 做法照抄斗地主：三段只写变量的动画，用 {@link AnimateEntityPacket} 只发给指定玩家，
 * 客户端各自算自己的 {@code v.me}。</p>
 */
public class JunqiPieceEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "tabletopgame:junqi_piece";

    /** 槽位总数：红 25 + 黑 25。 */
    public static final int SLOTS = 50;
    /** 每槽位数（值 0..255）。 */
    public static final int SLOT_BITS = 8;
    /** 每个属性装的槽数。 */
    public static final int SLOTS_PER_PROPERTY = 3;
    /** 属性个数：ceil(50/3) = 17。 */
    public static final int SLOT_PROPERTIES = (SLOTS + SLOTS_PER_PROPERTY - 1) / SLOTS_PER_PROPERTY;
    private static final int SLOT_RADIX = 1 << SLOT_BITS;
    /** 已亮明位（棋盘槽值 = cell | 0x40）。 */
    public static final int REVEAL_BIT = 0x40;
    /** 淘汰格槽值基数：128 + 托盘 index（0..23）。 */
    public static final int TRAY_BASE = 128;

    /** 标记位域上限：cell(0..60) + 64*cell。 */
    private static final int CELL_MAX = 60;
    private static final int MARK_MAX = 64 * 64 - 1;

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 与其它棋子一样：客户端软阴影取自实体定义的碰撞箱，自定义实体只能继承一个不画阴影的原版实体
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(JunqiPieceEntity.class)
            .build();

    public static String slotName(int index) {
        return "tabletopgame:junqi_s" + index;
    }

    /** 某个打包属性能表示的最大值（末组只有 2 槽）。 */
    public static int slotGroupMax(int index) {
        int slots = Math.min(SLOTS_PER_PROPERTY, SLOTS - index * SLOTS_PER_PROPERTY);
        int max = 1;
        for (int i = 0; i < slots; i++) {
            max *= SLOT_RADIX;
        }
        return max - 1;
    }

    public static final String P_M0 = "tabletopgame:junqi_m0";
    public static final String P_M1 = "tabletopgame:junqi_m1";
    public static final String P_M2 = "tabletopgame:junqi_m2";
    public static final String P_TURN = "tabletopgame:junqi_turn";

    public static void registerProperties() {
        for (int i = 0; i < SLOT_PROPERTIES; i++) {
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(slotName(i), 0, slotGroupMax(i), 0));
        }
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_M0, 0, MARK_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_M1, 0, MARK_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_M2, 0, MARK_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_TURN, 0, 1, 0));
    }

    // ---------------------------------------------------------------- 观看者动画
    public static final String ANIM_VIEWER_CONTROLLER = "tabletopgame:junqi_viewer";
    public static final String[] VIEWER_ANIMS = {
            "animation.tabletopgame.junqi.viewer_red",
            "animation.tabletopgame.junqi.viewer_black",
            "animation.tabletopgame.junqi.viewer_spec",
            "animation.tabletopgame.junqi.viewer_red_flip",
            "animation.tabletopgame.junqi.viewer_black_flip",
            "animation.tabletopgame.junqi.viewer_spec_flip"};

    public JunqiPieceEntity(FullChunk chunk, CompoundTag nbt) {
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

    /** 命中箱压到极小：右键/挖掘穿透实体直接命中棋盘方块。 */
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

    @Override
    public boolean attack(EntityDamageEvent source) {
        return false;
    }

    @Override
    public void kill() {
    }

    private JunqiBoard board;

    public void setBoard(JunqiBoard board) {
        this.board = board;
    }

    public JunqiBoard getBoard() {
        return board;
    }

    // ---------------------------------------------------------------- 批量提交
    private boolean batching;
    /** 诊断用：属性写入返回 false 说明实体属性没有初始化成功。 */
    private boolean propertyWriteFailed;

    public boolean hasPropertyWriteFailed() {
        return propertyWriteFailed;
    }

    public void beginBatch() {
        this.batching = true;
    }

    public void endBatch() {
        this.batching = false;
        this.syncProperties();
    }

    /**
     * 写入 50 个槽位。{@code cells[slot]} = 0 表示未上场，否则 = 点位（1..60）| 亮明位。
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
            } else {
                this.propertyWriteFailed = true;
            }
        }
    }

    public void setMarkers(int selCell, int curCell, int fromCell, int lastCell,
                           int selCellB, int curCellB) {
        int m0 = clampCell(selCell) + 64 * clampCell(selCellB);
        int m1 = clampCell(curCell) + 64 * clampCell(curCellB);
        int m2 = clampCell(fromCell) + 64 * clampCell(lastCell);
        if (this.setIntEntityProperty(P_M0, m0)) {
            this.sync();
        }
        if (this.setIntEntityProperty(P_M1, m1)) {
            this.sync();
        }
        if (this.setIntEntityProperty(P_M2, m2)) {
            this.sync();
        }
    }

    public void setTurn(int turn) {
        if (this.setIntEntityProperty(P_TURN, turn & 1)) {
            this.sync();
        }
    }

    private static int clampCell(int value) {
        return Math.max(0, Math.min(CELL_MAX, value));
    }

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

    /**
     * 给指定玩家发送「观看者身份」动画：0 红 / 1 黑 / 2 旁观（自身不参与对局时）。
     *
     * <p>属性是全局的，但这条动画只发给一个人，客户端在本地算 {@code v.me}，
     * 于是每个人看到的正面/单色/黄框都不一样。</p>
     */
    public void sendViewerAnimation(List<Player> viewers, int viewer, boolean flipEnemies) {
        if (viewers.isEmpty() || this.isClosed()) {
            return;
        }
        int index = Math.max(0, Math.min(2, viewer)) + (flipEnemies ? 3 : 0);
        AnimateEntityPacket.Animation anim = AnimateEntityPacket.Animation.builder()
                .animation(VIEWER_ANIMS[index])
                .controller(ANIM_VIEWER_CONTROLLER)
                .stopExpression("0")
                .blendOutTime(0f)
                .build();
        List<Entity> self = new ArrayList<>(1);
        self.add(this);
        Entity.playAnimationOnEntities(anim, self, viewers);
    }
}
