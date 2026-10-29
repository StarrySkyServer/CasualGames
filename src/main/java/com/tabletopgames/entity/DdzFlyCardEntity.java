package com.tabletopgames.entity;

import cn.nukkit.entity.Entity;
import cn.nukkit.entity.custom.CustomEntity;
import cn.nukkit.entity.custom.EntityDefinition;
import cn.nukkit.entity.data.Vector3fEntityData;
import cn.nukkit.entity.data.property.EntityProperty;
import cn.nukkit.entity.data.property.IntEntityProperty;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.nbt.tag.CompoundTag;

/**
 * 斗地主「发牌飞牌」实体，对应资源包中的 {@code tabletopgame:ddz_fly}。
 *
 * <h2>为什么是独立实体（沿用 UNO 的结论）</h2>
 * UNO 那边把飞入寄生在牌桌实体里连试三次（1.22.53 / 54 / 56），真机全部表现为
 * 「整排牌塌到桌心」。复盘后确认牌桌实体已贴着客户端规模上限，挪成独立实体后
 * 牌桌侧零改动、风险归零（见 {@link UnoFlyCardEntity} 与 MEMORY「塌陷红线」）。
 * 斗地主的牌桌是模组那份 292 骨骼 / 303 条 pre_animation，同样没有余量可挥霍，
 * 所以<b>连牌桌 geo 与动画都逐字节照抄模组，一个字都不改</b>
 * （见 {@code tools/ddz/port_table.py}）。
 *
 * <h2>它怎么工作</h2>
 * 服务端只在 spawn 前写 <b>5 个小整数</b>：目标座位 {@code fj}、发牌序号 {@code fq}
 * （决定起飞时刻）、以及三家手牌总数 {@code h0c..h2c}（落点的居中量要用它）。
 * 落点由客户端现算，表达式与牌桌 {@code v.pNsp} / {@code v.hrN} <b>逐字同源</b>
 * （模组 {@code pre_animation} 第 233~235 行），因此落位那一帧不会跳。
 * 飞行进度走 {@code q.life_time} 自走时钟 —— <b>飞行途中一个包都不用发</b>。
 *
 * <h2>发牌节奏</h2>
 * 每 {@link #DEAL_STEP_SECONDS}=0.2 秒起飞一张、{@link #FLY_SECONDS}=0.22 秒飞完 ⇒
 * 稳态同时约 1 张在飞。用户明确要求「比 UNO 快一倍」（UNO 0.375 秒/张）⇒
 * 整局发完 17 张 = 16×0.2 + 0.22 = <b>3.42 秒</b>（地主 20 张 ≈ 4.02 秒）。
 *
 * <p>正反两面都贴牌背：还没揭晓的牌客户端根本不知道是什么，贴牌面就是泄牌。</p>
 */
public class DdzFlyCardEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "tabletopgame:ddz_fly";

    /** 座位数（斗地主三家）。 */
    public static final int SEATS = 3;

    /** 每家手牌上限（地主 20 = 17 + 3 张底牌，超出时按 17 算间距）。 */
    public static final int MAX_HAND = 17;

    /** 每张牌的起飞间隔（秒）。必须与资源包 {@code ddz_fly} 的 {@code DEAL_STEP_S} 一致。 */
    public static final double DEAL_STEP_SECONDS = 0.2;

    /** 单张飞行时长（秒）。必须与资源包 {@code ddz_fly} 的 {@code FLY_T} 一致。 */
    public static final double FLY_SECONDS = 0.22;

    /**
     * 实体存活时长（tick）。= 起飞延迟（{@code seq × DEAL_STEP_SECONDS}）+ 飞行 {@link #FLY_SECONDS}，
     * 再向上取整多留一点余量。用 {@code MAX_HAND - 1 = 16} 算最大延迟
     * ⇒ 16×0.2 + 0.22 = 3.42 秒 → 69 tick。
     */
    public static final int LIFE_TICKS = (int) Math.ceil(
            ((MAX_HAND - 1) * DEAL_STEP_SECONDS + FLY_SECONDS) * 20.0) + 1;

    /** 目标座位（0..2），决定用哪套落点公式与朝向。 */
    public static final String P_SEAT = "tabletopgame:ddz_fly_seat";
    /** 发牌序号（0..16）—— 客户端用它算起飞时刻 {@code seq * DEAL_STEP_SECONDS}（= 0.2）。 */
    public static final String P_SEQ = "tabletopgame:ddz_fly_seq";
    /** 三家手牌总数，客户端算落点居中量用（与牌桌读的是同一个数）。 */
    private static final String[] P_HAND = {
            "tabletopgame:ddz_fly_h0",
            "tabletopgame:ddz_fly_h1",
            "tabletopgame:ddz_fly_h2",
    };

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 与牌桌 / 捕获框同理：继承不画阴影的 armor_stand 运行时标识符。
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(DdzFlyCardEntity.class)
            .build();

    /**
     * 注册客户端侧实体属性定义（与 {@code entity/ddz/ddz_fly.entity.json} 一致）。
     * 必须在任何实体生成前调用（插件 onLoad）。共 5 个，远小于 32 的上限。
     */
    public static void registerProperties() {
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SEAT, 0, SEATS - 1, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SEQ, 0, 255, 0));
        for (String name : P_HAND) {
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(name, 0, 255, 0));
        }
    }

    public DdzFlyCardEntity(FullChunk chunk, CompoundTag nbt) {
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

    /** 碰撞箱做到极小：飞过去不该挡住任何点击（手牌拾取靠射线，不靠碰撞箱）。 */
    @Override
    public float getHeight() {
        return 0.01f;
    }

    @Override
    public float getWidth() {
        return 0.01f;
    }

    @Override
    public float getLength() {
        return 0.01f;
    }

    /**
     * 写入飞行目标。必须在 {@code spawnToAll()} <b>之前</b>调用 ——
     * 实体一生成，客户端就会按当时的属性开始插值。
     *
     * @param seat      目标座位（0..2）
     * @param seq       发牌序号（0 起），决定起飞时刻 {@code seq × DEAL_STEP_SECONDS}（= 0.2）秒
     * @param handSizes 三家当前手牌张数（长度 ≥ 3），客户端算居中量用
     */
    public void setTarget(int seat, int seq, int[] handSizes) {
        setIntEntityProperty(P_SEAT, Math.max(0, seat) % SEATS);
        setIntEntityProperty(P_SEQ, Math.max(0, seq) & 255);
        for (int j = 0; j < SEATS; j++) {
            int n = (handSizes != null && j < handSizes.length) ? Math.max(0, handSizes[j]) : 0;
            setIntEntityProperty(P_HAND[j], Math.min(255, n));
        }
    }

    @Override
    protected void initEntity() {
        super.initEntity();
        // 无重力、不移动、不受击 —— 它只是一张"从牌堆滑到手牌"的牌背，位置全由动画算。
        this.setImmobile(true);
        this.setDataFlag(DATA_FLAGS, DATA_FLAG_GRAVITY, false);
        this.setDataFlag(DATA_FLAGS, DATA_FLAG_HAS_COLLISION, false);
        this.setNameTagVisible(false);
        this.setNameTagAlwaysVisible(false);
        this.setCanBeSavedWithChunk(false);
        this.setHealth(1);
        this.setMaxHealth(1);
        // 碰撞箱 0.001：飞过去时不该挡住任何点击（手牌拾取靠射线，不靠碰撞箱）。
        this.setDataProperty(new Vector3fEntityData(DATA_COLLISION_BOX, 0.001f, 0.001f, 0.001f));
    }
}
