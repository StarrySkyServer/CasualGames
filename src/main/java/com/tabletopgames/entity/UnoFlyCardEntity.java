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
 * UNO「飞行牌」实体，对应资源包中的 {@code tabletopgame:uno_fly}。
 *
 * <h2>为什么它是独立实体</h2>
 * 飞入动画曾三次尝试寄生在牌桌实体里（1.22.53 / 54 / 56），真机全部表现为
 * 「整排牌塌到桌心、按钮与出牌展示区一起跑位」。复盘七次实测的真因不是写法，
 * 而是<b>牌桌实体已经贴着客户端的规模上限</b>：
 * <ul>
 *   <li>geo 骨骼数 ≥ 400 ⇒ 塌（1.22.56 加 fly_0..3 直接撞线）</li>
 *   <li>pre_animation ≥ 307 条 ⇒ 塌（1.22.53 / 54）</li>
 * </ul>
 * 与文件大小无关：986 KB 的 1.22.49 正常，848 KB 的 1.22.54 反而塌。
 * 当前牌桌是 398 骨骼 / 292 条，两条都在线下 —— 余量只剩 2 根骨骼、14 条语句。
 * 把飞入挪进独立实体后，<b>牌桌侧一个字节都不用改</b>，风险直接归零。
 *
 * <h2>它怎么工作</h2>
 * 服务端只在 spawn 前写六个小整数：座位 {@code fj}、区内序号 {@code fs}、
 * 以及四家手牌总数 {@code h0c..h3c}（落点的居中量要用它）。
 * 落点由客户端用 {@code slot_axes()}（与 {@code card_N} 静止位姿<b>同一份生成代码</b>）现算，
 * 飞行进度走 {@code q.life_time} 自走时钟 —— <b>飞行途中一个包都不用发</b>。
 * 到时间后服务端 {@code close()} 掉它。
 *
 * <p>正反两面都贴牌背：还没揭晓的牌客户端根本不知道是什么，贴牌面就是泄牌。</p>
 */
public class UnoFlyCardEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "tabletopgame:uno_fly";

    /** 飞行时长（秒）。必须与资源包 {@code uno_fly.animation.json} 里那个分母一致。 */
    public static final double FLY_SECONDS = 0.22;
    /** 飞行时长的 tick 数（1 tick = 0.05 秒，向上取整多留一点余量再销毁）。 */
    public static final int FLY_TICKS = (int) Math.ceil(FLY_SECONDS * 20.0) + 1;

    /**
     * 定庄「从牌堆翻出起始牌」那一档的飞行时长（秒）。必须与资源包
     * {@code uno_fly.animation.json} 里模式 1 的那个分母一致（{@code FLY_TIME_DEALER}）。
     */
    public static final double FLY_SECONDS_DEALER = 0.45;

    /**
     * 贴台面平推：牌背横着从牌堆滑向手牌位，触到木框内边就消失 —— 发牌 / 摸牌用。
     * 与资源包模式 0 对应（{@code v.fm==0} 分支）。
     */
    public static final int MODE_SLIDE = 0;
    /**
     * 从牌堆翻出：带抛物弧飞到该座位出牌区，落位即停 —— 定庄比大小用。
     * 与资源包模式 1 对应（{@code v.fm==1} 分支）。
     */
    public static final int MODE_DEALER = 1;

    /** 飞行模式（{@link #MODE_SLIDE} / {@link #MODE_DEALER}）。 */
    public static final String P_MODE = "tabletopgame:uno_fly_mode";

    /** 座位（0..3），决定用哪套落点公式与朝向。 */
    public static final String P_SEAT = "tabletopgame:uno_fly_seat";
    /** 手牌区内序号（0 起）—— 也就是"这是该座位的第几张牌"。 */
    public static final String P_SLOT = "tabletopgame:uno_fly_slot";
    /** 四家手牌总数，客户端算落点居中量用（与牌桌 card_N 读的是同一个数）。 */
    private static final String[] P_HAND = {
            "tabletopgame:uno_fly_h0",
            "tabletopgame:uno_fly_h1",
            "tabletopgame:uno_fly_h2",
            "tabletopgame:uno_fly_h3",
    };

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 与牌桌 / 捕获框同理：继承不画阴影的 armor_stand 运行时标识符。
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(UnoFlyCardEntity.class)
            .build();

    /**
     * 注册客户端侧实体属性定义（与 {@code entity/uno/uno_fly.entity.json} 一致）。
     * 必须在任何实体生成前调用（插件 onLoad）。共 7 个，远小于 32 的上限。
     */
    public static void registerProperties() {
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_MODE, 0, 1, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SEAT, 0, 3, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SLOT, 0, 255, 0));
        for (String name : P_HAND) {
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(name, 0, 255, 0));
        }
    }

    public UnoFlyCardEntity(FullChunk chunk, CompoundTag nbt) {
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
     * @param seat      座位（0..3），决定用哪套落点公式与朝向
     * @param slot      该座位手牌区内的序号（0 起）；模式 1（定庄）不用它
     * @param handSizes 四家当前手牌张数（长度 ≥ 4）；模式 1（定庄）不用它
     * @param mode      {@link #MODE_SLIDE}（发牌 / 摸牌）或 {@link #MODE_DEALER}（定庄）
     */
    public void setTarget(int seat, int slot, int[] handSizes, int mode) {
        setIntEntityProperty(P_MODE, mode == MODE_DEALER ? MODE_DEALER : MODE_SLIDE);
        setIntEntityProperty(P_SEAT, seat & 3);
        setIntEntityProperty(P_SLOT, Math.max(0, slot) & 255);
        for (int j = 0; j < 4; j++) {
            int n = (handSizes != null && j < handSizes.length) ? Math.max(0, handSizes[j]) : 0;
            setIntEntityProperty(P_HAND[j], Math.min(255, n));
        }
    }

    @Override
    protected void initEntity() {
        super.initEntity();
        // 无重力、不移动、不受击 —— 它只是一张"沿着弧线飞过去"的牌背，位置全由动画算。
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
