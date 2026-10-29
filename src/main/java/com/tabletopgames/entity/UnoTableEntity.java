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
import com.tabletopgames.game.UnoTable;

/**
 * UNO 牌桌渲染实体，对应资源包中的 {@code tabletopgame:uno_table}。
 * <p>
 * 「一个实体承载整桌」：四家手牌、中央抽牌堆、弃牌堆顶牌、四个按钮、回合箭头、庄家徽记、
 * 当前颜色圆盘、出牌方向箭头，全部通过 <b>24 个整型属性</b>同步给客户端；
 * 客户端 {@code pre_animation} 解码后由 {@code animation.tabletopgame.uno.layout} 驱动
 * 382 根骨骼（card_0..107 + highlight_0..107 + 每槽一根 fake 牌背 108 根 + 按钮/标记等）。
 * <p>
 * 与斗地主不同：本实体<b>不再配一个独立的 anchor 实体</b>——锚点在斗地主里只用来兜底
 * 「右键命中实体」，而这里直接用本实体（{@link #setTable}）承担该职责，少一个实体定义、
 * 少一份资源、少一个可能不同步的状态。
 * <p>
 * <b>属性编码（必须与资源包 {@code entity/uno/uno_table.entity.json} 的 pre_animation 完全一致）</b>：
 * <ul>
 *   <li>{@code tabletopgame:uno_cards_0..15}（0..2097151，21 位）：每属性装 7 个卡槽，
 *       槽 N 占 3 位，{@code prop = N/7}、{@code shift = 3*(N%7)}。
 *       区值：0=隐藏，1..4=第 1..4 座的手牌区（只对该座本人可见），5=弃牌堆顶</li>
 *   <li>{@code tabletopgame:uno_sel}（0..268435455，28 位 = 4×7 位）：每 viewer 7 位，
 *       值 0=无选中；{@code slot+1}=第 slot 个手牌槽被选中（1..108）</li>
 *   <li>{@code tabletopgame:uno_hover}（0..268435455，28 位 = 4×7 位）：每 viewer 7 位，
 *       取值见 {@link #HOVER_NONE} / {@link #HOVER_BTN_BASE}</li>
 *   <li>{@code tabletopgame:uno_table}（0..131071，17 位）：位域见 {@link #ACTOR_SHIFT} 等</li>
 *   <li>{@code tabletopgame:uno_controls}（0..65535）：4 个按钮 × 4 位，值见 {@link Controls}</li>
 *   <li>{@code tabletopgame:uno_pile}（0..255）：中央抽牌堆可见的「?」牌张数（客户端钳到 7，见 UnoTable.PILE_LAYERS）</li>
 *   <li>{@code tabletopgame:uno_deal}（0..255）：逐张发牌进度。手牌区内第 {@code s} 张
 *       （{@code s} 为该座位区内的序号）在 {@code s < uno_deal} 时才显示；非发牌阶段写
 *       {@link #DEAL_DONE} 表示「全部可见」</li>
 *   <li>{@code tabletopgame:uno_seats}（0..255）：每座位 2 位，0 正常 / 1 托管 / 2 离线托管</li>
 *   <li>{@code tabletopgame:uno_fly}（0..3）：出牌飞行的触发信号，见 {@link #P_FLY}</li>
 * </ul>
 * <p>
 * <b>为什么每 viewer 是 7 位而不是 6 位</b>：手牌槽位可达 107，hover 值 = slot+1 最大 108，
 * 6 位（0..63）装不下，会让槽位 ≥63 的手牌永远无法高亮；且 6 位时按钮基准 120 也放不下。
 * 7 位上限 127，恰好容下 1..108（手牌）与 120..123（按钮）且互不重叠。
 * <p>
 * 实体本身只负责渲染：无碰撞、无重力、不可攻击；随牌桌创建，随牌桌销毁。
 */
public class UnoTableEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "tabletopgame:uno_table";

    /** 卡槽总数：108 张牌（0..107）。 */
    public static final int CARD_SLOTS = 108;
    /** 每个 cards 属性装 7 个卡槽，每个 3 bit。 */
    public static final int SLOTS_PER_PROPERTY = 7;
    /** cards 属性个数：ceil(108/7) = 16。 */
    public static final int CARD_PROPERTIES = (CARD_SLOTS + SLOTS_PER_PROPERTY - 1) / SLOTS_PER_PROPERTY;
    /** 卡槽区值上限（0..7）。 */
    public static final int CARD_ZONE_MAX = 7;

    /** 区值：隐藏。 */
    public static final int ZONE_HIDDEN = 0;
    /** 区值：第 1 座（P1）手牌区；第 k 座为 {@code ZONE_HAND_BASE + k - 1}。 */
    public static final int ZONE_HAND_BASE = 1;
    /** 区值：弃牌堆顶（全场可见）。 */
    public static final int ZONE_DISCARD = 5;

    /** {@code tabletopgame:uno_cards_0..15}。 */
    public static String cardsName(int index) {
        return "tabletopgame:uno_cards_" + index;
    }

    public static final String P_SEL = "tabletopgame:uno_sel";
    public static final String P_HOVER = "tabletopgame:uno_hover";
    public static final String P_TABLE = "tabletopgame:uno_table";
    public static final String P_CONTROLS = "tabletopgame:uno_controls";
    public static final String P_PILE = "tabletopgame:uno_pile";
    public static final String P_DEAL = "tabletopgame:uno_deal";
    /**
     * {@code tabletopgame:uno_seats}（0..63）：每座位 2 位，控制「托管机器人」骨骼的显隐。
     * <pre>
     *   值 0 = 正常（不画机器人）
     *      1 = 托管（玩家在线但交给系统代打）
     *      2 = 离线托管（断线，由系统代打）
     * </pre>
     * 机器人本体由 {@code geometry.tabletopgame.uno_table} 的 {@code bot_N / bot_head_N}
     * 骨骼绘制，位置在 {@code pre_animation} 里按「本方第一行最左边那张牌」算出。
     */
    public static final String P_SEATS = "tabletopgame:uno_seats";
    /** 每座位占位数。 */
    public static final int SEAT_BITS = 2;
    /** 全部座位的取值上限（4 座 × 2 bit = 8 位）。 */
    public static final int SEATS_MAX = (1 << (SEAT_BITS * 4)) - 1;   // 255

    /**
     * {@code tabletopgame:uno_fly}（0..3）：**一次飞行动画**的触发信号。
     * <pre>
     *   bit 0-1：模式 —— 0 = 手牌 → 出牌区（正常出牌）
     *                    1 = 牌堆 → 出牌区（发完牌后翻出的起始牌；定庄比大小那四张也走它）
     *                    2 = 牌堆 → 摸牌者的手牌区（摸牌）
     *                    3 = 保留
     *   bit 2-3：座位（0..3）—— 这次飞行属于谁；模式 1 的定庄整批由 show_all 决定，不用它
     *   bit 4  ：翻转位 —— 服务端**每触发一次就取反**，客户端只靠"值变了"认出新的一次飞行
     * </pre>
     * 客户端用「值变化」把飞行进度清零，然后自己按 {@code q.delta_time} 往前走
     * （见 build_table 的 FLY_* 说明 —— 斗地主的选择弹跳动画用的也是 `q.delta_time` 这套
     * 逐帧积分，同一个客户端已验证可用）。
     * <p>
     * <b>为什么要翻转位</b>：连续两次出牌若写入同一个值，客户端认不出"新的一次"，
     * 第二张牌就不会飞。翻转位保证相邻两次触发一定不同。
     */
    public static final String P_FLY = "tabletopgame:uno_fly";
    /** fly 属性的取值上限（5 位：2 模式 + 2 座位 + 1 翻转）。 */
    public static final int FLY_MAX = 3;

    // ------------------------------------------------------------------
    // 每 viewer 7 位的准星 / 选中编码
    // ------------------------------------------------------------------

    /** 每 viewer 占用位数。 */
    public static final int VIEWER_BITS = 7;
    /** 单个 viewer 的取值上限。 */
    public static final int VIEWER_MAX = (1 << VIEWER_BITS) - 1;   // 127
    /** 整属性的取值上限（4 viewer）。 */
    public static final int VIEWER_PACKED_MAX = (1 << (VIEWER_BITS * 4)) - 1;   // 268435455

    /** 准星：无。 */
    public static final int HOVER_NONE = 0;
    /** 准星：手牌槽 slot 的值为 {@code HOVER_CARD_BASE + slot}（1..108）。 */
    public static final int HOVER_CARD_BASE = 1;
    /** 准星：按钮槽 k 的值为 {@code HOVER_BTN_BASE + k}（120..123）。 */
    public static final int HOVER_BTN_BASE = 120;

    /** 手牌槽 slot 的准星值。 */
    public static int hoverCard(int slot) {
        return HOVER_CARD_BASE + slot;
    }

    /** 按钮槽 k 的准星值。 */
    public static int hoverButton(int slot) {
        return HOVER_BTN_BASE + slot;
    }

    /** 由「槽位准星值」反查被悬停的手牌槽；不是手牌则返回 -1。 */
    public static int hoveredCardSlot(int hoverValue) {
        int slot = hoverValue - HOVER_CARD_BASE;
        return slot >= 0 && slot < CARD_SLOTS ? slot : -1;
    }

    /** 由「槽位准星值」反查被悬停的按钮槽；不是按钮则返回 -1。 */
    public static int hoveredButtonSlot(int hoverValue) {
        int slot = hoverValue - HOVER_BTN_BASE;
        return slot >= 0 && slot <= 3 ? slot : -1;
    }

    // ------------------------------------------------------------------
    // table 属性位域
    // ------------------------------------------------------------------

    /** 庄家座位 + 1（0 = 未定庄）；3 位。 */
    public static final int DEALER_SHIFT = 0;
    /** 当前行动座位 + 1（0 = 无人行动）；3 位。 */
    public static final int ACTOR_SHIFT = 3;
    /** 按钮对哪个 viewer 可见：目标座位 + 1，{@link #BTN_ALL} = 全员；3 位。 */
    public static final int BTN_SHIFT = 6;
    /** 按钮全员可见的取值。 */
    public static final int BTN_ALL = 5;
    /** 当前颜色（0..3，对应红/黄/绿/蓝）；2 位。 */
    public static final int ACOLOR_SHIFT = 9;
    /** 出牌方向：0 = 顺时针，1 = 逆时针；1 位。 */
    public static final int DIR_SHIFT = 11;
    /**
     * 中央那张牌（弃牌堆顶）是哪个座位打出的：座位 + 1（0 = 未知）；3 位。
     * <p>
     * 客户端据此把这张牌摆到「出牌者自己的出牌区」（{@code OUT*PLAY_R}）而非桌子正中，
     * 于是自己出的牌在自己这一侧、对手出的牌贴在对面那一侧 —— 与斗地主的观感一致。
     */
    public static final int DSEAT_SHIFT = 12;
    /**
     * 是否处于「定庄比大小展示」：1 = 展示中；1 位。
     * <p>
     * 展示期间四家的比大小牌都挂在**各自座位的手牌区**（区值 1..4），客户端据此把
     * 这些牌平铺到「该座位自己的出牌区」并让所有人都看得见（见 build_table 的 card_N）。
     * 这样一张牌都不占额外卡槽、也不用新增区值。
     */
    public static final int SHOW_SHIFT = 15;
    /**
     * 「起始颜色是否已确定」：1 = 颜色圆盘可以显示；1 位（bit 16）。
     * <p>
     * 用户反馈（2026-10-16）：「发牌过程就已经知道要出什么颜色了，不应该是发完牌之后
     * 才抽颜色的吗」—— 规则上起始牌在发完 7 张后才翻开，颜色标识必须等到那一刻才出现。
     * 服务端在 {@code activeColor >= 0 且发牌动画结束（dealShown>=255）} 时才置 1：
     * WAITING（无对局）/ DEALER_DRAW（未发牌）/ 发牌动画中 / 起始野牌待选色 都是 0。
     */
    public static final int ACOLOR_OK_SHIFT = 16;
    /**
     * 是否处于「本局已结束」：1 = SETTLED；1 位（bit 17）。
     * <p>
     * 结算后把**别人的手牌**公开给自己看（需求 2026-10-18）：客户端据此把每个非本座手牌
     * 绕「牌底边（牌与按钮的交界处）」的竖轴翻 180°，牌面朝向自己、倾斜方向镜像；
     * 同时隐藏对手那叠 "?" 牌背。纯客户端渲染，不改变任何牌数据，也不影响别人操作。
     */
    public static final int REVEAL_SHIFT = 17;
    /** table 属性取值上限（位 0..17）。 */
    public static final int TABLE_MAX = (1 << (REVEAL_SHIFT + 1)) - 1;   // 262143

    /** 打包 {@code tabletopgame:uno_table}。各参数均为「座位 + 1」，无则传 0。 */
    public static int tableState(int dealer, int actor, int btnOwner, int acolor, int direction) {
        return tableState(dealer, actor, btnOwner, acolor, direction, 0, 0, 0);
    }

    /** 打包 {@code tabletopgame:uno_table}（含弃牌堆顶的出牌座位）。 */
    public static int tableState(int dealer, int actor, int btnOwner, int acolor, int direction,
                                 int discardSeat) {
        return tableState(dealer, actor, btnOwner, acolor, direction, discardSeat, 0, 0);
    }

    /** 打包 {@code tabletopgame:uno_table}（含弃牌堆顶的出牌座位 + 定庄展示位）。 */
    public static int tableState(int dealer, int actor, int btnOwner, int acolor, int direction,
                                 int discardSeat, int showing) {
        return tableState(dealer, actor, btnOwner, acolor, direction, discardSeat, showing, 0);
    }

    /** 打包 {@code tabletopgame:uno_table}（全字段，含 bit16「起始色已确定」/ bit17「结算公开」）。 */
    public static int tableState(int dealer, int actor, int btnOwner, int acolor, int direction,
                                 int discardSeat, int showing, int colorOk, int reveal) {
        return ((dealer & 7) << DEALER_SHIFT)
                | ((actor & 7) << ACTOR_SHIFT)
                | ((btnOwner & 7) << BTN_SHIFT)
                | ((acolor & 3) << ACOLOR_SHIFT)
                | ((direction & 1) << DIR_SHIFT)
                | ((discardSeat & 7) << DSEAT_SHIFT)
                | ((showing & 1) << SHOW_SHIFT)
                | ((colorOk & 1) << ACOLOR_OK_SHIFT)
                | ((reveal & 1) << REVEAL_SHIFT);
    }

    /** 打包 {@code tabletopgame:uno_table}（全字段，bit17 结算公开固定 0）。 */
    public static int tableState(int dealer, int actor, int btnOwner, int acolor, int direction,
                                 int discardSeat, int showing, int colorOk) {
        return tableState(dealer, actor, btnOwner, acolor, direction, discardSeat, showing, colorOk, 0);
    }

    // ------------------------------------------------------------------
    // controls 属性：按钮功能码
    // ------------------------------------------------------------------

    /**
     * 按钮功能码（写入 {@code tabletopgame:uno_controls} 的每个 4 位槽，0 = 空槽）。
     * <p>
     * 按钮组按「屏幕从左到右」书写，数组下标即槽下标，且<b>下标 0 显示在最左</b>
     * —— 该左右关系由 {@code tools/uno/probe_buttons.py} 用真实座位帧几何实测确认
     * （四座的 slot0..3 横向坐标恒为 -9.9/-3.3/+3.3/+9.9）；它与资源生成脚本
     * {@code tools/uno/uno_assets.py} 的 {@code BUTTON_GROUPS} 一一对应，改动必须同步。
     * <pre>
     *   BTN_PLAY   = [提示, 抽牌, 出牌]            （默认出牌阶段）
     *   BTN_DRAWN  = [出牌, 跳过]                  （抽牌后：抽到的牌可出则可直接出，也可跳过）
     *   BTN_SETTLE = [退出, 下一局]                （一局结束的结算按钮）
     *   BTN_COLOR  = [红, 黄, 绿, 蓝]              （打出野牌后选色）
     * </pre>
     */
    public static final class Controls {
        /** 出牌（含「打完抽到的牌」）。 */
        public static final int PLAY = 1;
        /** 抽牌。 */
        public static final int DRAW = 2;
        /** 跳过（抽牌后不出）。 */
        public static final int SKIP = 3;
        /** 提示：自动选中一张可出的牌。 */
        public static final int HINT = 4;
        /** 下一局。 */
        public static final int NEXT = 5;
        /** 退出（离开牌桌）。 */
        public static final int EXIT = 6;
        /** 选色：红。 */
        public static final int C_RED = 7;
        /** 选色：黄。 */
        public static final int C_YELLOW = 8;
        /** 选色：绿。 */
        public static final int C_GREEN = 9;
        /** 选色：蓝。 */
        public static final int C_BLUE = 10;

        private Controls() {
        }
    }

    /** 默认出牌阶段按钮组（屏幕从左到右）。 */
    public static final int[] BTN_PLAY = {
            Controls.HINT, Controls.DRAW, Controls.PLAY};
    /** 抽牌后按钮组（屏幕从左到右）。 */
    public static final int[] BTN_DRAWN = {Controls.PLAY, Controls.SKIP};
    /** 结算按钮组（屏幕从左到右）。 */
    public static final int[] BTN_SETTLE = {Controls.EXIT, Controls.NEXT};
    /** 选色按钮组（屏幕从左到右，与牌面颜色顺序一致）。 */
    public static final int[] BTN_COLOR = {
            Controls.C_RED, Controls.C_YELLOW, Controls.C_GREEN, Controls.C_BLUE};

    /** 空按钮组。 */
    public static final int[] BTN_NONE = new int[0];

    /** 把「从左到右」的功能码数组打包成 {@code uno_controls} 属性值。 */
    public static int packControls(int[] codes) {
        int value = 0;
        for (int slot = 0; slot < codes.length && slot < 4; slot++) {
            value |= (codes[slot] & 15) << (4 * slot);
        }
        return value;
    }

    /** 取出 {@code uno_controls} 中第 slot 个按钮的功能码（0 = 空槽）。 */
    public static int controlAt(int value, int slot) {
        return (value >>> (4 * slot)) & 15;
    }

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 与棋子实体同理：基岩版客户端的软边阴影取自实体定义的碰撞箱，
            // 自定义实体继承不绘制阴影的 armor_stand 运行时标识符。
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(UnoTableEntity.class)
            .build();

    /**
     * 注册客户端侧实体属性定义（与 {@code entity/uno/uno_table.entity.json} 一致）。
     * 必须在任何实体生成前调用（插件 onLoad）。
     * <p>
     * 基岩版每个实体类型最多 32 个 Entity Property，这里 24 个，留有余量。
     */
    public static void registerProperties() {
        for (int i = 0; i < CARD_PROPERTIES; i++) {
            // 7 槽 × 3 bit = 21 bit，最大 2^21-1 = 2097151
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(cardsName(i), 0, 2097151, 0));
        }
        // 4 viewer × 7 bit = 28 bit，最大 2^28-1 = 268435455
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SEL, 0, VIEWER_PACKED_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_HOVER, 0, VIEWER_PACKED_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_TABLE, 0, TABLE_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_CONTROLS, 0, 65535, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_PILE, 0, 255, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_DEAL, 0, 255, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SEATS, 0, SEATS_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_FLY, 0, FLY_MAX, 0));
    }

    public UnoTableEntity(FullChunk chunk, CompoundTag nbt) {
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

    /** 命中箱极小值：不遮挡牌桌方块与射线拾取。 */
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

    /** 渲染实体不可被攻击。 */
    @Override
    public boolean attack(EntityDamageEvent source) {
        return false;
    }

    /** 禁止一切击杀途径；正常收回走 {@code close()}。 */
    @Override
    public void kill() {
    }

    // ------------------------------------------------------------------
    // 牌桌反向引用（代替斗地主的 anchor 实体）
    // ------------------------------------------------------------------

    private UnoTable table;

    public void setTable(UnoTable table) {
        this.table = table;
    }

    public UnoTable getTable() {
        return table;
    }

    // ------------------------------------------------------------------
    // 属性写入
    // ------------------------------------------------------------------

    /** 108 个卡槽的当前区值缓存（Nukkit 无属性读取 API，写入前在此拼装）。 */
    private final int[] cardZoneCache = new int[CARD_SLOTS];

    /** 4 个 viewer 的准星值缓存（属性不可读，写入时重算整属性）。 */
    private final int[] hoverCache = new int[4];

    /** 4 个 viewer 的选中槽缓存（存槽位 0..107，-1 = 未选中）。 */
    private final int[] selCache = {-1, -1, -1, -1};

    /** 批量提交标记：置位期间所有属性变更只改本地值，不发包。 */
    private boolean batching;

    /** 开始批量提交属性（多个属性整批只发一个元数据包）。 */
    public void beginBatch() {
        this.batching = true;
    }

    /** 结束批量提交并一次性同步全部变更。 */
    public void endBatch() {
        this.batching = false;
        this.syncProperties();
    }

    /**
     * 写入单个卡槽区值（0=隐藏，1..4=第 N 座手牌区，5=弃牌堆顶）。
     *
     * @param slot 卡槽 0..107
     * @param zone 区值 0..7
     */
    public void setCardZone(int slot, int zone) {
        cardZoneCache[slot] = zone & CARD_ZONE_MAX;
        flushCardProperties();
        syncAfterWrite();
    }

    /** 批量写入全部 108 个卡槽（{@code zones[k]} 为槽 k 的区值）。 */
    public void setCardZones(int[] zones) {
        System.arraycopy(zones, 0, cardZoneCache, 0, CARD_SLOTS);
        flushCardProperties();
        syncAfterWrite();
    }

    /** 由缓存重算 16 个 cards 属性值。 */
    private void flushCardProperties() {
        for (int prop = 0; prop < CARD_PROPERTIES; prop++) {
            int value = 0;
            for (int i = SLOTS_PER_PROPERTY - 1; i >= 0; i--) {
                int slot = prop * SLOTS_PER_PROPERTY + i;
                if (slot >= CARD_SLOTS) {
                    continue;
                }
                value = (value << 3) | cardZoneCache[slot];
            }
            setIntEntityProperty(cardsName(prop), value);
        }
    }

    /**
     * 写入某 viewer 的选中手牌槽。
     *
     * @param seat 0..3
     * @param slot 手牌槽 0..107；负数表示取消选中
     */
    public void setSelected(int seat, int slot) {
        selCache[seat] = slot;
        setIntEntityProperty(P_SEL, packViewerValues(selCache, true));
        syncAfterWrite();
    }

    /**
     * 写入某 viewer 的准星值。
     *
     * @param seat  0..3
     * @param value {@link #HOVER_NONE} / {@link #hoverCard} / {@link #hoverButton}
     */
    public void setHover(int seat, int value) {
        hoverCache[seat] = value & VIEWER_MAX;
        setIntEntityProperty(P_HOVER, packViewerValues(hoverCache, false));
        syncAfterWrite();
    }

    /** 把 4 个 viewer 的值打包成一个整属性（每 viewer {@link #VIEWER_BITS} 位）。 */
    private static int packViewerValues(int[] values, boolean slotEncoded) {
        int packed = 0;
        for (int seat = 0; seat < 4; seat++) {
            int v;
            if (slotEncoded) {
                v = values[seat] < 0 ? HOVER_NONE : HOVER_CARD_BASE + values[seat];
            } else {
                v = values[seat];
            }
            packed |= (v & VIEWER_MAX) << (VIEWER_BITS * seat);
        }
        return packed;
    }

    /** 写入 {@code uno_table}（庄家 / 行动方 / 按钮归属 / 当前颜色 / 方向）。 */
    public void setTableState(int value) {
        setIntEntityProperty(P_TABLE, value & TABLE_MAX);
        syncAfterWrite();
    }

    /** 写入 {@code uno_controls}（4 按钮 × 4 位）。 */
    public void setControls(int value) {
        setIntEntityProperty(P_CONTROLS, value & 65535);
        syncAfterWrite();
    }

    /** 写入 {@code uno_pile}（中央抽牌堆可见张数）。 */
    public void setPile(int count) {
        setIntEntityProperty(P_PILE, count & 255);
        syncAfterWrite();
    }

    /** 逐张发牌已完成：{@code uno_deal} 取该值表示「手牌全部可见」。 */
    public static final int DEAL_DONE = 255;

    /**
     * 写入 {@code uno_deal}（逐张发牌进度）。
     *
     * @param count 已亮出的张数（每个座位区内各亮 {@code count} 张）；{@link #DEAL_DONE} 表示已完成
     */
    public void setDeal(int count) {
        setIntEntityProperty(P_DEAL, count & 255);
        syncAfterWrite();
    }

    /**
     * 写入 {@code uno_seats}（每座位 2 位：0 正常 / 1 托管 / 2 离线托管）。
     *
     * @param value 打包值，见 {@link #P_SEATS}
     */
    public void setSeats(int value) {
        setIntEntityProperty(P_SEATS, value & SEATS_MAX);
        syncAfterWrite();
    }

    /** 最近一次写入的 {@code uno_fly} 值（见 {@link #P_FLY}）。 */
    private int fly;

    /**
     * 写入 {@code uno_fly}：触发/续期一次「出牌飞行」。
     *
     * @param value 见 {@link #P_FLY}（bit0 = 来源，bit1 = 翻转位）；同一轮里两次触发必须不同
     */
    public void setFly(int value) {
        this.fly = value & FLY_MAX;
        setIntEntityProperty(P_FLY, this.fly);
        syncAfterWrite();
    }

    public int getFly() {
        return fly;
    }

    /** 单条属性变更后的同步；批量模式下跳过，由 {@link #endBatch()} 统一发包。 */
    private void syncAfterWrite() {
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
