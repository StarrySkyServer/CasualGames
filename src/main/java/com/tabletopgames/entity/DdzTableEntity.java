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
import com.tabletopgames.game.DdzGame;

/**
 * 斗地主牌桌渲染实体，对应资源包中的 {@code tabletopgame:ddz_table}。
 *
 * <h2>它承载什么</h2>
 * 「一个实体承载整桌」：三家手牌、三家出牌区、底牌堆、地主徽记与农民草帽、托管机器人、
 * 全部按钮（叫地主 / 不叫 / 抢地主 / 不抢 / 加倍 ×3 / 出牌 / 过牌 / 提示 / 清选择），
 * 全部通过 <b>16 个整型属性</b>同步；客户端 {@code pre_animation} 解码后由
 * {@code animation.tabletopgame.ddz.layout} 驱动 292 根骨骼。
 *
 * <h2>⚠️ 资源是逐字节从模组搬来的，不要"顺手优化"</h2>
 * 几何体（292 骨骼）与动画（303 条 pre_animation）直接取自模组
 * {@code table.geo.json} + 5 个 animation 文件，只改了 identifier 与属性名前缀
 * （见 {@code tools/ddz/port_table.py}）。UNO 那边实测过七次：牌桌实体一旦被改动到
 * 某个规模，真机就表现为「整排牌塌到桌心」，而离线渲染器完全看不出来
 * （见 MEMORY「塌陷红线尚未确定」）。{@code tools/ddz/verify_res.py} 把
 * 292/303 这两个数字锁死了。
 *
 * <h2>属性编码（必须与 {@code entity/ddz/ddz_table.entity.json} 的 pre_animation 完全一致）</h2>
 * <ul>
 *   <li>{@code tabletopgame:playing_card_slots_0..7}（0..2097151，21 位）：每属性装 7 个卡槽，
 *       槽 N 占 3 位，{@code prop = N/7}、{@code shift = 3*(N%7)}。54 个卡槽 = 8×7 − 2。</li>
 *   <li>{@code tabletopgame:playing_card_selected_0..2}（0..262143，18 位 ×3 = <b>54 位</b>）：
 *       <b>按卡槽号</b>索引的选中位，<b>不是</b>「每 viewer 一份」——
 *       客户端的 {@code v.sel0..17} 读 {@code pk_sel0}、{@code v.sel18..35} 读
 *       {@code pk_sel1}、{@code v.sel36..53} 读 {@code pk_sel2}，
 *       与 {@code card_0..53} 的编号严格对应。写入口是
 *       {@link #setSelectedSlots(boolean[])}。</li>
 *   <li>{@code tabletopgame:playing_card_hover}（0..262143，18 位 = 3×6 位）：每 viewer 6 位，
 *       取值见 {@link Hover}。</li>
 *   <li>{@code tabletopgame:ddz_table}（0..511，9 位）：地主座位 / 按钮归属 / 全场可见，
 *       位域见 {@link TableBits}。<b>两个座位类位域都是「座位 + 1」编码</b>，
 *       原因见 {@link #LANDLORD_SHIFT}。</li>
 *   <li>{@code tabletopgame:playing_card_controls}（0..65535，16 位 = 4 按钮 × 4 位），值见 {@link Controls}。</li>
 *   <li>{@code tabletopgame:playing_card_seats}（0..63，6 位 = 3 座位 × 2 位）：托管机器人显隐。</li>
 *   <li>{@code tabletopgame:playing_card_bottom}（0..262143，18 位 = 3 张 × 6 位）：地主收底牌动画
 *       要用的 3 张底牌 id（{@link #setBottomTake}）。</li>
 * </ul>
 *
 * <p><b>为什么 hover 要按 viewer 分开</b>：三个人看到的准星完全不同，
 * 打包在一个属性里各占 6 位，靠 {@code pre_animation} 的
 * {@code math.mod(math.floor(v/64^r), 64)} 拆开。
 * <b>选中则相反</b>：它描述的是「这张牌被选起来了」，与谁在看无关，
 * 所以按卡槽号全局存一份（3 个属性拼成 54 位）。</p>
 *
 * <p>实体本身只负责渲染：无碰撞、无重力、不可攻击；随牌桌创建，随牌桌销毁。</p>
 */
public class DdzTableEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "tabletopgame:ddz_table";

    /** 座位数（斗地主三家）。 */
    public static final int SEATS = 3;

    /** 卡槽总数：54 张牌。 */
    public static final int CARD_SLOTS = 54;
    /** 每个 cards 属性装 7 个卡槽，每个 3 bit。 */
    public static final int SLOTS_PER_PROPERTY = 7;
    /** cards 属性个数：ceil(54/7) = 8。 */
    public static final int CARD_PROPERTIES = (CARD_SLOTS + SLOTS_PER_PROPERTY - 1) / SLOTS_PER_PROPERTY;
    /** cards 属性的取值上限（7 槽 × 3 bit = 21 位）。 */
    public static final int CARDS_MAX = 2097151;
    /** 卡槽区值上限（0..7）。 */
    public static final int CARD_ZONE_MAX = 7;

    /** 区值：隐藏。 */
    public static final int ZONE_HIDDEN = 0;
    /** 区值：第 k 座手牌区（k=0..2）；座位 k 对应 {@code ZONE_HAND_BASE + k}。 */
    public static final int ZONE_HAND_BASE = 1;
    /** 区值：第 k 座出牌区；座位 k 对应 {@code ZONE_PLAY_BASE + k}。 */
    public static final int ZONE_PLAY_BASE = 4;
    /** 区值：底牌堆（3 张，全场可见）。 */
    public static final int ZONE_BOTTOM = 7;

    /** {@code tabletopgame:playing_card_slots_i}。 */
    public static String cardsName(int index) {
        return "tabletopgame:playing_card_slots_" + index;
    }

    /** {@code tabletopgame:playing_card_selected_k}（18 位掩码）。 */
    public static String selectedName(int viewer) {
        return "tabletopgame:playing_card_selected_" + (viewer % SEATS);
    }

    public static final String P_HOVER = "tabletopgame:playing_card_hover";
    public static final String P_TABLE = "tabletopgame:playing_card_table";
    public static final String P_CONTROLS = "tabletopgame:playing_card_controls";
    /**
     * {@code tabletopgame:playing_card_seats}（0..63）：每座位 2 位，控制「托管机器人」骨骼显隐。
     * <pre>
     *   值 0 = 正常（不画机器人）
     *      1 = 托管（玩家在线但交给系统代打）
     *      2 = 离线托管（断线，由系统代打）
     * </pre>
     */
    public static final String P_SEATS = "tabletopgame:playing_card_seats";
    /**
     * {@code tabletopgame:playing_card_bottom}（0..262143，18 位）：地主收底牌动画要用的 3 张底牌 id。
     * <pre>
     *   v = (id0 &amp; 63) | ((id1 &amp; 63) &lt;&lt; 6) | ((id2 &amp; 63) &lt;&lt; 12)
     * </pre>
     * 只被客户端 {@code bottom_take} 动画直接读取（不进 {@code pre_animation}），
     * 所以**不会**增加牌桌的 pre_animation 条数 / 骨骼数 —— 塌陷红线不受影响。
     */
    public static final String P_BOTTOM = "tabletopgame:playing_card_bottom";
    /** {@code playing_card_bottom} 的取值上限（3 × 6 位）。 */
    public static final int BOTTOM_MAX = 262143;
    /**
     * {@code tabletopgame:playing_card_wild}（0..17）：本局癞子点数。
     * <pre>
     *   0  = 本局无癞子（普通玩法，或叫牌阶段地主还没定）
     *   3..15 = 3 ~ 2 的某一点数，该点数的牌换成金版牌面
     *   16 = 小王 / 17 = 大王（预留，规则上不会出现）
     * </pre>
     * 客户端 54 根 {@code gold_NN} 骨骼的 {@code scale} 直接读它 ——
     * 只在 {@code layout} 动画里引用，不进 {@code pre_animation}。
     */
    public static final String P_WILD = "tabletopgame:playing_card_wild";
    /** {@code playing_card_wild} 的取值上限（最大点数是 17）。 */
    public static final int WILD_MAX = 17;
    /**
     * {@code tabletopgame:playing_card_wild_heaven}（0..17）：本局<b>天癞子</b>点数。
     *
     * <p>2026-10-25「天地癞子」：天癞子在<b>发牌前</b>就抽定，地赖子在定地主后抽，
     * 两者都是万能牌。取值含义与 {@link #P_WILD} 一致（0 = 无，3..15 = 点数）。</p>
     */
    public static final String P_WILD_HEAVEN = "tabletopgame:playing_card_wild_heaven";
    /**
     * {@code tabletopgame:playing_card_wild_as_h}（20 位）：本局<b>天癞子</b>那 4 张牌
     * 各自「被打出时当成了哪个点数」。
     *
     * <p>2026-10-26 用户：手里的癞子 3 打出去后牌面还是「金 3」，看不出当了几，
     * 只能反推牌型。所以出牌区把牌面换成「金 &lt;当的点数&gt;」、左下角标灰色原点数。</p>
     *
     * <pre>
     *   每张牌 5 位（0 = 还没打出 / 没被替换；3..15 = 当成的点数）
     *   bit  0..4  = 花色 0（♣）
     *   bit  5..9  = 花色 1（♠）
     *   bit 10..14 = 花色 2（♥）
     *   bit 15..19 = 花色 3（♦）
     * </pre>
     *
     * 牌 id = {@code (点数 - 3) * 4 + 花色}，所以客户端用
     * {@code playing_card_wild_heaven} 定位到 4 张牌即可。
     * 地癞子另用 {@link #P_WILD_AS_E}。
     */
    public static final String P_WILD_AS_H = "tabletopgame:playing_card_wild_as_h";
    /** {@code playing_card_wild_as_h} 同款，装<b>地癞子</b>那 4 张牌的当值。 */
    public static final String P_WILD_AS_E = "tabletopgame:playing_card_wild_as_e";
    /** {@code playing_card_wild_as_*} 的取值上限：4 张 × 5 位 = 20 位。 */
    public static final int WILD_AS_MAX = 1048575;
    /**
     * {@code tabletopgame:playing_card_wild_sort}（0..2^28-1）：癞子在<b>出牌区</b>
     * 里要挪到哪个槽（2026-10-26 用户要求：打 3 当 K，那张牌就得站在 K 的位置）。
     *
     * <p>为什么需要它：客户端的区内序号 {@code v.sN} 是<b>按卡槽号（= 牌 id）升序</b>
     * 数出来的，而癞子的「大小」不是它自己的点数 —— 打 3 当 K，牌面是金 K，位置却还在
     * 3 的位置上（真机：连对 QQKKAA 显示成 KQQKAA）。所以要单独下发一次
     * 「谁要从第 i 位移到第 j 位」的插入信息。</p>
     *
     * <p>⚠️ <b>手牌</b>那排不走这个属性：手牌只要「癞子一律排到最后」，客户端自己按
     * {@code v.wb1..wb7}（每区癞子张数）就能数出来，两边都算会叠加。</p>
     *
     * <pre>
     *   每一档 12 位，每个属性放 2 档（一共 2 个属性 = 4 档）：
     *     bit 0..1  = 区码（0 = 该档不用；1/2/3 = 出牌区 4/5/6）
     *     bit 2..6  = from（这张牌现在的区内序号 0..31）
     *     bit 7..11 = to  （它要挪到的位子 0..31）
     *   第二档整体左移 12 位；第二个属性放第 3、4 档。
     * </pre>
     *
     * <p>⚠️⚠️ <b>每个属性的取值必须 &lt; 2^24</b>（2026-10-27 真机踩坑）：Molang 的
     * {@code q.property()} 是 <b>float32</b>，只能精确表示 2^24 = 16777216 以内的整数。
     * 老编码是「2 档 × 14 位 = 最高 2^28」，只要两档同时用上，低 14 位就被浮点吃掉 ——
     * 第一档的区码被抹成 0（整档失效），第二档虽然还在，却只剩它一条乱挪，
     * 出牌区就整排错位（真机：9 10 J Q K A 显示成 9 A 10 J Q K）。</p>
     */
    public static final String P_WILD_SORT = "tabletopgame:playing_card_wild_sort";
    /** 第二个「癞子插入」属性：放第 3、4 档（同样是 2 档 × 12 位）。 */
    public static final String P_WILD_SORT2 = "tabletopgame:playing_card_wild_sort2";
    /** {@code playing_card_wild_sort*} 的取值上限（2 档 × 12 位 = 16777215，正好是 float32 的精确上界）。 */
    public static final int WILD_SORT_MAX = 16777215;
    /**
     * {@code tabletopgame:playing_card_hand_sort}（0..1）：手牌左右排序（2026-10-25 用户要求）。
     * <pre>
     *   0 = 小→大（最小在左，最大在右 —— 模组原版）
     *   1 = 大→小（最大在左 —— 新默认）
     * </pre>
     * 客户端 {@code pre_animation} 用它算出手牌展示序号 {@code v.dN}
     * （反序时 {@code v.dN = 本座位张数 - 1 - 卡槽序号}），
     * 只读一次属性、不增加骨骼 —— 塌陷红线不受影响。
     */
    public static final String P_HAND_SORT = "tabletopgame:playing_card_hand_sort";
    /** {@code playing_card_hand_sort} 的取值上限。 */
    public static final int HAND_SORT_MAX = 1;

    /** selected / hover / table 的取值上限。 */
    public static final int SELECTED_MAX = 262143;   // 18 位
    /**
     * 每个 {@code playing_card_selected_k} 属性装多少个卡槽的选中位。
     *
     * <p>3 个属性 × 18 位 = <b>54</b>，正好覆盖全部卡槽 —— 与客户端
     * {@code v.sel0..53} 的三段划分逐字一致（见 {@link #setSelectedSlots}）。</p>
     */
    public static final int SELECTED_BITS = 18;
    /**
     * 扑克需要 4 个视角 × 6 位 = 24 位，所以 {@code playing_card_hover} 的上限抬到 2^24-1。
     * 斗地主仍只写 18 位，解出来的值一个不变。
     */
    public static final int HOVER_MAX = 16777215;     // 24 位（扑克 4 视角）
    /** 扑克 {@code playing_card_hover} 的掩码。 */
    public static final int POKER_HOVER_MAX = 16777215;
    /**
     * bits 9..10 也要用（回合标记），所以上限从 511 抬到 2047。
     * 老客户端读到更大的值不会出错 —— 属性最大值本来就是随包下发的。
     */
    public static final int TABLE_MAX = 2047;         // 11 位（斗地主）
    /**
     * 扑克在 {@code playing_card_table} 上用 bit11..19（模式 + 阶段 + 庄家 + 行动方 + 摊牌），
     * 所以本属性注册上限抬到 2^20-1。斗地主写的值仍 ≤ 2047。
     */
    public static final int POKER_TABLE_MAX = 1048575;
    /**
     * {@code playing_card_controls} 的取值上限。
     * <p>
     * ⚠️ 2026-10-24 从 16 位（4 槽 × 4 位 = 65535）扩到 <b>20 位（4 槽 × 5 位 = 1048575）</b>：
     * 4 位的 16 个码已经用掉 12 个，只剩 9/14/15 三个空位，而新增的「退出 / 下一局 /
     * 1分 / 2分 / 3分」要 5 个动作码。位宽一改，客户端
     * {@code v.button_N} 的除数与掩码也必须同步（{@code 16^n} → {@code 32^n}、掩码 16 → 32），
     * 由 {@code tools/ddz/add_extra_buttons.py} 一次性改写；漏改会静默显示错按钮。
     */
    public static final int CONTROLS_MAX = 16777215;   // 24 位：低 20 位 action 按钮 + 高 4 位结算按钮
    public static final int SEATS_MAX = 63;           // 6 位（斗地主 3 座）
    /** 扑克 4 座 × 2 位 = 8 位。 */
    public static final int POKER_SEATS_MAX = 255;

    // ------------------------------------------------------------------
    // 准星编码（每 viewer 6 位）
    // ------------------------------------------------------------------

    /** 准星无命中。 */
    public static final int HOVER_NONE = 0;
    /** 准星命中第 {@code slot} 张手牌（0..53）⇒ {@code HOVER_CARD_BASE + slot}（1..54）。 */
    public static final int HOVER_CARD_BASE = 1;
    /** 准星命中底牌堆 ⇒ 55。 */
    public static final int HOVER_BOTTOM = 55;
    /** 准星命中第 {@code slot} 个按钮（0..3）⇒ {@code HOVER_BUTTON_BASE + slot}（56..59）。 */
    public static final int HOVER_BUTTON_BASE = 56;
    /** 准星命中语音按钮 ⇒ 60。 */
    public static final int HOVER_VOICE = 60;
    /** 每 viewer 占位数（6 位）。 */
    public static final int HOVER_BITS = 6;
    /** 每 viewer 的准星掩码（6 位）。 */
    public static final int HOVER_MASK = (1 << HOVER_BITS) - 1;

    /** 手牌高亮值 ⇒ 卡槽号（0..53）；非手牌返回 -1。 */
    public static int hoveredCardSlot(int hoverValue) {
        if (hoverValue < HOVER_CARD_BASE) {
            return -1;
        }
        int slot = hoverValue - HOVER_CARD_BASE;
        return slot < CARD_SLOTS ? slot : -1;
    }

    /** 准星值 ⇒ 按钮槽号（0..3）；非按钮返回 -1。 */
    public static int hoveredButtonSlot(int hoverValue) {
        if (hoverValue < HOVER_BUTTON_BASE) {
            return -1;
        }
        int slot = hoverValue - HOVER_BUTTON_BASE;
        return slot < BUTTON_SLOTS ? slot : -1;
    }

    /** 准星值是否命中底牌堆。 */
    public static boolean isBottomHover(int hoverValue) {
        return hoverValue == HOVER_BOTTOM;
    }

    // ------------------------------------------------------------------
    // table 位域（9 位）
    // ------------------------------------------------------------------

    /**
     * bits 0..1：地主座位，<b>取值 = 座位 + 1</b>（0 = 尚未定地主，1/2/3 = 座位 0/1/2）。
     * <p>
     * ⚠️ <b>为什么是 +1 而不是座位本身</b>：客户端 {@code landlord_marker} 骨骼靠
     * {@code math.mod(v.pk_table, 4)} 判三个徽记位置，{@code ==0} 时 scale=0（整个徽记隐藏）。
     * 座位 0 落在 {@code ==1} 分支（x=-16, z=-21.415，与手牌区 z=-23 同一侧），
     * 所以「座位 0」必须写成 1。直接写座位号会让座位 0 的徽记永远不显示、
     * 而「没有地主」反而会显示出一个徽记。
     * <p>
     * 三家的位置（与 {@code trusteeship_pN} / 手牌区一致）：
     * <pre>
     *   值 1 → 座位 0（南，OUT=(0,-1)）  值 2 → 座位 1（北，OUT=(0,+1)）
     *   值 3 → 座位 2（东，OUT=(+1,0)）
     * </pre>
     */
    public static final int LANDLORD_SHIFT = 0;
    public static final int LANDLORD_MASK = 0x3;
    /**
     * bits 2..3：按钮可见归属，<b>取值 = 座位 + 1</b>（0 = 谁都看不到）。
     * <p>
     * {@code buttons_visibility} 的判定是
     * {@code mod(floor(pk_table/4),4) == viewer_mode+1}，
     * 所以同样要 +1。全员可见走 {@link #VISIBLE_ALL}，不占这一段。
     */
    public static final int BUTTON_OWNER_SHIFT = 2;
    public static final int BUTTON_OWNER_MASK = 0x3 << 2;
    /**
     * bits 6..8：「按钮对全场可见」标志，值必须<b>正好是 6</b>。
     * <p>
     * {@code buttons_visibility} 判的是 {@code mod(floor(pk_table/64), 8) == 6} ——
     * 是等值比较而不是非零判断，写 7 / 1 都不生效。bit 5 资源未使用，留空。
     */
    public static final int VISIBLE_ALL_SHIFT = 6;
    public static final int VISIBLE_ALL_MASK = 0x7 << 6;
    /** 「按钮全场可见」写入 bits 6..8 的值（客户端等值比较 6，别改成 1 或 7）。 */
    public static final int VISIBLE_ALL = 6;

    /**
     * bit 4：结算公开手牌（1 = 所有手牌<b>竖着翻开展示牌面</b>，位置不动）。
     *
     * <p>与 UNO {@code v.reveal}（bit17）同一原理：纯客户端原地翻面，不播额外动画。
     * 客户端 {@code v.reveal=math.mod(math.floor(v.pk_table/16),2)}。
     * 结算时由 {@code DdzGame.doFinish()} 置 1，下一局 {@code startDeal()} 的
     * {@code sync()} 自动清 0（phase != SETTLED）。</p>
     */
    public static final int REVEAL_SHIFT = 4;
    public static final int REVEAL_MASK = 0x1 << 4;

    /**
     * bits 9..10：当前该谁行动，<b>取值 = 座位 + 1</b>（0 = 无）。
     *
     * <p>只驱动 client 侧三根 {@code turn_N} 骨骼（那个黄色三角▼，与 UNO 同一套贴图与坐标），
     * 不参与任何逻辑判定 —— 判回合仍然走 {@code DdzGame.actorSeat()}。
     * 客户端读法：{@code v.actor=math.mod(math.floor(v.pk_table/512),4)}。</p>
     *
     * <p>⚠️ bits 6..8 必须<b>正好等于 6</b>（{@code buttons_visibility} 是等值比较），
     * 所以新加的段只能从 bit 9 起，别往 5..8 里塞。</p>
     */
    public static final int ACTOR_SHIFT = 9;
    public static final int ACTOR_MASK = 0x3 << 9;
    /** 无人行动。 */
    public static final int ACTOR_NONE = 0;

    /** 按钮可见归属：没人（写 0 即可）。 */
    public static final int BUTTON_OWNER_NONE = 0;

    // ------------------------------------------------------------------
    // 按钮动作码（controls 每槽 4 位，1..15；0 = 空槽）
    // ------------------------------------------------------------------

    /**
     * 按钮动作码（controls 每槽 4 位，1..15；0 = 空槽）。
     *
     * <p><b>⚠️ 码值不是自定的，必须与 {@code animations/ddz/ddz_table.animation.json} 里
     * {@code button_label_slotN_*} 的判定逐字对齐</b> —— 每个按钮贴图骨骼都写死了自己的码，
     * 对不上就静默不显示（服务端按下按钮、客户端毫无反应，极难联想是码错了）。
     * {@code tools/ddz/verify_res.py} 会把这里每个码拿去 grep 动画文件。
     *
     * <p>⚠️ <b>同一个码可以在不同槽里表示不同动作</b>（例如「不叫」2 号码同时挂在槽 1/2/3，
     * 因为客户端的槽位偏移数的是「前面有几个非空槽」，可见按钮必须占连续低槽）；
     * 但<b>同一槽</b>里两个动作不能共用一个码，否则两张贴图会同时亮。</p>
     *
     * <p><b>槽号越大越靠左</b>（位置由 {@code v.button_offset_N} = {@code (累计非空数 - (总数-1)/2) × -6.6}
     * 决定，偏移为负）。模组四组按钮的槽位布局：
     * <pre>
     *   叫地主  = [叫地主, 不叫]              → 槽0=1, 槽1=2
     *   抢地主  = [抢地主, 不抢]              → 槽0=3, 槽1=4
     *   加倍    = [超级加倍, 加倍, 不加倍]   → 槽0=12, 槽1=11, 槽2=13
     *   出牌    = [出牌, 不出/压不过, 提示, 取消选牌]
     *                                        → 槽0=5, 槽1=6 或 10, 槽2=7, 槽3=8
     * </pre>
     * 即「主动作永远在槽 0（最右）」，这是模组的设计，别改成 UNO 那种左起排列。
     */
    public enum Controls {
        /** 空槽。 */
        NONE(0),
        /** 叫地主。 */
        CALL_LANDLORD(1),
        /** 不叫。 */
        DECLINE_CALL(2),
        /** 抢地主。 */
        ROB_LANDLORD(3),
        /** 不抢。 */
        DECLINE_ROB(4),
        /** 出牌（打出选中的牌）。 */
        PLAY(5),
        /** 不出（过牌）。 */
        PASS(6),
        /** 提示（高亮能出的牌）。 */
        HINT(7),
        /** 取消选牌。 */
        CLEAR_SELECTION(8),
        /**
         * 压不过（提示后的结论：这一手没有任何牌能大过）。
         * <p>与 {@link #PASS} <b>互斥占槽 1</b>：能过就显示「不出」，不能过就显示「压不过」，
         * 两者用同一张贴图位（模组 {@code button_label_slot1_cannot_beat}）。
         */
        CANNOT_BEAT(10),
        /** 加倍（×2）。 */
        DOUBLE(11),
        /** 超级加倍（×4）。 */
        SUPER_DOUBLE(12),
        /** 不加倍（×1）。 */
        NO_DOUBLE(13),
        /**
         * 退出牌桌（结算期的桌面按钮，照 UNO 搬来的贴图）。
         * <p>固定在槽 1（屏幕上靠左），与 UNO 的 {@code BTN_SETTLE = [退出, 下一局]} 同序。</p>
         */
        EXIT(9),
        /**
         * 下一局（结算期的桌面按钮，等价于菜单里的「下一局」投票）。
         * <p>固定在槽 0（屏幕上靠右）。本局有人退出时不出现（见
         * {@code DdzGame#hasSeatVacated()}）。</p>
         */
        NEXT(14),
        /** 叫分 3 分（叫分制定地主；槽 0）。 */
        BID_3(15),
        /** 叫分 2 分（槽 1）。 */
        BID_2(16),
        /** 叫分 1 分（槽 2）。 */
        BID_1(17);

        private final int code;

        Controls(int code) {
            this.code = code;
        }

        public int code() {
            return this.code;
        }

        /** 由动作码反查；未知码返回 {@link #NONE}。 */
        public static Controls of(int code) {
            for (Controls c : values()) {
                if (c.code == code) {
                    return c;
                }
            }
            return NONE;
        }
    }

    /** 按钮槽数。 */
    public static final int BUTTON_SLOTS = 4;
    /**
     * 每槽占位数（<b>5 位</b>，32 个动作码）。
     * <p>2026-10-24 从 4 位扩上来，原因见 {@link #CONTROLS_MAX}。</p>
     */
    public static final int BUTTON_BITS = 5;
    /**
     * 单个按钮槽的位掩码（{@code (1 << BUTTON_BITS) - 1}）。
     * <p>⚠️ 不要写死 {@code 0xF}：扩位时改了位移却没改掩码，槽 1 起会读到邻槽的
     * 高位——`DdzProtocolCheck` 的打包往返就是被这个抓出来的。</p>
     */
    public static final int BUTTON_MASK = (1 << BUTTON_BITS) - 1;

    // ------------------------------------------------------------------

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 继承不画阴影的 armor_stand：基岩版客户端的软边阴影取自实体定义的碰撞箱。
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(DdzTableEntity.class)
            .build();

    /**
     * 注册客户端侧实体属性定义（19 个，与 {@code entity/ddz/ddz_table.entity.json} 一致）。
     * 必须在任何实体生成前调用（插件 onLoad）。
     */
    public static void registerProperties() {
        for (int i = 0; i < CARD_PROPERTIES; i++) {
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(cardsName(i), 0, CARDS_MAX, 0));
        }
        for (int v = 0; v < SEATS; v++) {
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(selectedName(v), 0, SELECTED_MAX, 0));
        }
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_HOVER, 0, POKER_HOVER_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_TABLE, 0, POKER_TABLE_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_CONTROLS, 0, CONTROLS_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_SEATS, 0, POKER_SEATS_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_BOTTOM, 0, BOTTOM_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_WILD, 0, WILD_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_WILD_HEAVEN, 0, WILD_MAX, 0));
        // 癞子「当值」（每张牌 5 位 × 4 张 = 20 位）：天 / 地各一个属性
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_WILD_AS_H, 0, WILD_AS_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_WILD_AS_E, 0, WILD_AS_MAX, 0));
        // 癞子「按当值插入」的信息（每个属性 2 档 × 12 位，两个属性共 4 档）
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_WILD_SORT, 0, WILD_SORT_MAX, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_WILD_SORT2, 0, WILD_SORT_MAX, 0));
        // 默认 1 = 大→小（用户 2026-10-25 要求的新默认），首次 sync 前也不会闪回旧顺序。
        // ⚠️⚠️ IntEntityProperty 的参数顺序是 (name, defaultValue, maxValue, minValue)，
        //    **不是** (name, min, max, default)。写成 (0, 1, 1) 会把 min 设成 1 ⇒
        //    值被夹死在 [1,1]，设置切到「小→大」也永远读回 1（2026-10-25 真机踩过）。
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_HAND_SORT, 1, HAND_SORT_MAX, 0));
    }

    public DdzTableEntity(FullChunk chunk, CompoundTag nbt) {
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

    /** 命中箱极小：不遮挡牌桌方块，也不挡射线。 */
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
    // 牌桌反向引用
    // ------------------------------------------------------------------

    /**
     * 所属牌桌。
     *
     * <p>Nukkit 没有「实体 → 服务端对象」的反查表（UNO 侧同样靠这个字段），
     * 所以 {@code DdzManager.onInteractEntity} 拿到实体后靠它找回牌桌。</p>
     */
    private DdzGame game;

    public void setGame(DdzGame game) {
        this.game = game;
    }

    public DdzGame getGame() {
        return game;
    }

    /** 牌桌模式：0 = 斗地主，1 = 德州扑克（客户端按 table bit11 切几何/贴图）。 */
    private int tableMode;

    /** 德州扑克运行时（模式 1 时有效）。 */
    private com.tabletopgames.game.PokerGame poker;

    public void setTableMode(int mode) {
        this.tableMode = mode & 1;
    }

    public int getTableMode() {
        return tableMode;
    }

    public void setPokerTable(com.tabletopgames.game.PokerGame poker) {
        this.poker = poker;
    }

    public com.tabletopgames.game.PokerGame getPokerTable() {
        return poker;
    }

    // ------------------------------------------------------------------
    // 扑克属性写入（与客户端 pre_animation 的位域一一对应）
    // ------------------------------------------------------------------

    /** 写 {@code playing_card_slots_i}（21 位）。 */
    public void setPokerSlots(int index, int value) {
        setIntEntityProperty(cardsName(index), value & CARDS_MAX);
        syncAfterWrite();
    }

    /** 写 {@code playing_card_selected_k}（18 位）。 */
    public void setPokerSelected(int index, int value) {
        setIntEntityProperty(selectedName(index), value & SELECTED_MAX);
        syncAfterWrite();
    }

    /** 写 {@code playing_card_table}（模式位 + 阶段/庄家/行动方/摊牌）。 */
    public void setPokerTableValue(int value) {
        setIntEntityProperty(P_TABLE, value & POKER_TABLE_MAX);
        syncAfterWrite();
    }

    /** 写 {@code playing_card_controls}（5 槽 × 4 位）。 */
    public void setPokerControls(int value) {
        setIntEntityProperty(P_CONTROLS, value & CONTROLS_MAX);
        syncAfterWrite();
    }

    /** 写 {@code playing_card_seats}（4 座 × 2 位）。 */
    public void setPokerSeats(int value) {
        setIntEntityProperty(P_SEATS, value & POKER_SEATS_MAX);
        syncAfterWrite();
    }

    /** 写 {@code playing_card_bottom}（扑克用来装底池金额）。 */
    public void setPokerBottom(int value) {
        setIntEntityProperty(P_BOTTOM, value & BOTTOM_MAX);
        syncAfterWrite();
    }

    /** 写 {@code playing_card_hover}（扑克暂不使用，写 0 清空）。 */
    public void setPokerHover(int value) {
        setIntEntityProperty(P_HOVER, value & POKER_HOVER_MAX);
        syncAfterWrite();
    }

    // ------------------------------------------------------------------
    // 属性写入
    // ------------------------------------------------------------------

    /** 54 个卡槽的当前区值缓存。 */
    private final int[] cardZoneCache = new int[CARD_SLOTS];
    /** 3 个 viewer 的准星值缓存（属性不可读，写入时重算整属性）。 */
    private final int[] hoverCache = new int[SEATS];
    /** 当前下发的地赖子点数（属性不可读，写值相同就跳过发包）。 */
    private int wildCache = 0;
    /** 当前下发的天癞子点数。 */
    private int wildHeavenCache = 0;
    /** 当前下发的「天癞子 4 张的当值」（20 位打包）。 */
    private int wildAsHeavenCache = 0;
    /** 当前下发的「地癞子 4 张的当值」（20 位打包）。 */
    private int wildAsCache = 0;
    /** 当前下发的癞子插入排序（第 1、2 档）；初值 −1 保证第一次一定真的发一次包。 */
    private int wildSortCache = -1;
    /** 当前下发的癞子插入排序（第 3、4 档）。 */
    private int wildSort2Cache = -1;
    /**
     * 当前下发的手牌排序码（1 = 大→小）。
     *
     * <p>初值 <b>−1</b>（不是 1）：这样第一次 {@link #setHandSort(int)} 一定会真的写一次属性并
     * 发包，不会因为「默认值恰好等于缓存」而整个生命周期一个包都不发 ——
     * 属性 default 只在实体首次同步时生效，客户端一旦错过就永远读 0。</p>
     */
    private int handSortCache = -1;

    /** 批量提交标记：置位期间所有属性变更只改本地值，不发包。 */
    private boolean batching;

    /** 开始批量提交属性（整批属性只发一个元数据包）。 */
    public void beginBatch() {
        this.batching = true;
    }

    /** 结束批量提交并一次性同步全部变更。 */
    public void endBatch() {
        this.batching = false;
        this.syncProperties();
    }

    /**
     * 写入单个卡槽区值。
     * <p>
     * Nukkit 的 {@code Entity} 没有 {@code getIntEntityProperty}，所以这里用本地缓存
     * 记录每个卡槽的当前区值来拼装整属性。
     *
     * @param slot 卡槽 0..53
     * @param zone 区值 0..7（见 {@link #ZONE_HIDDEN} 等）
     */
    public void setCardZone(int slot, int zone) {
        if (slot < 0 || slot >= CARD_SLOTS) {
            return;
        }
        cardZoneCache[slot] = zone & CARD_ZONE_MAX;
        flushCardProperties();
        syncAfterWrite();
    }

    /** 批量写入全部 54 个卡槽（{@code zones[k]} 为槽 k 的区值）。 */
    public void setCardZones(int[] zones) {
        System.arraycopy(zones, 0, cardZoneCache, 0, CARD_SLOTS);
        flushCardProperties();
        syncAfterWrite();
    }

    /** 由缓存重算 8 个 cards 属性值。 */
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
     * 写入「哪些卡槽的牌被选中」。
     *
     * <p>⚠️ 这是<b>按卡槽号</b>的整体状态，不是「每个 viewer 一份」。客户端的对应关系是：</p>
     * <pre>
     *   v.sel0 ..  v.sel17  ←  playing_card_selected_0 的 bit 0..17   （卡槽 0..17）
     *   v.sel18 .. v.sel35  ←  playing_card_selected_1 的 bit 0..17   （卡槽 18..35）
     *   v.sel36 .. v.sel53  ←  playing_card_selected_2 的 bit 0..17   （卡槽 36..53）
     * </pre>
     *
     * <p>初版这里写成 {@code setSelected(viewerIndex, mask)}（把「座位 k 的手牌位掩码」
     * 直接塞进 {@code playing_card_selected_k}），语义完全错位：座位 1 的卡槽从 17 左右开始，
     * 而 {@code playing_card_selected_1} 的 bit 0 对应的是卡槽 18 ⇒ 座位 1 的选中整体错位一格、
     * 座位 2 错得更多，地主第 19/20 张更是超出 18 位压根表示不了。</p>
     *
     * @param selectedBySlot 长度 ≥ {@link #CARD_SLOTS}；下标 = 卡槽号，true = 该卡牌被选中
     */
    /**
     * 当前卡槽区值分布（诊断用，显示在菜单顶部）。
     *
     * <p>「三家手牌叠在一起」「对手手牌露牌面」这类问题只看截图无法区分
     * 「服务端 zone 分错」还是「客户端 viewer_mode 没生效」—— 把分布打出来一眼就能分辨。</p>
     */
    public String zoneSummary() {
        int[] c = new int[8];
        for (int i = 0; i < CARD_SLOTS; i++) {
            c[cardZoneCache[i] & 7]++;
        }
        return "§7区值：隐藏 " + c[0] + " / 南 " + c[1] + " / 北 " + c[2] + " / 东 " + c[3]
                + " / 出牌 " + c[4] + "+" + c[5] + "+" + c[6] + " / 底 " + c[7];
    }

    public void setSelectedSlots(boolean[] selectedBySlot) {
        int[] pack = new int[3];
        if (selectedBySlot != null) {
            for (int slot = 0; slot < CARD_SLOTS; slot++) {
                if (!selectedBySlot[slot]) {
                    continue;
                }
                int prop = slot / SELECTED_BITS;          // 0 / 1 / 2
                int bit = slot % SELECTED_BITS;           // 0..17
                if (prop < pack.length) {
                    pack[prop] |= 1 << bit;
                }
            }
        }
        for (int v = 0; v < 3; v++) {
            setIntEntityProperty(selectedName(v), pack[v] & SELECTED_MAX);
        }
        syncAfterWrite();
    }

    /**
     * 写入某 viewer 的准星。
     *
     * @param viewerIndex 0..2
     * @param value       该 viewer 的 6 位准星值（见 {@link Hover} 系列常量）
     */
    public void setHover(int viewerIndex, int value) {
        hoverCache[viewerIndex % SEATS] = value & ((1 << HOVER_BITS) - 1);
        int packed = 0;
        for (int r = SEATS - 1; r >= 0; r--) {
            packed = (packed << HOVER_BITS) | hoverCache[r];
        }
        setIntEntityProperty(P_HOVER, packed & HOVER_MAX);
        syncAfterWrite();
    }

    /** 写入 table 位域（地主座位 / 行动座位 / 按钮归属 / 结算 / 底牌翻开）。 */
    public void setTable(int value) {
        int v = value & TABLE_MAX;
        this.tableCache = v;
        setIntEntityProperty(P_TABLE, v);
        syncAfterWrite();
    }

    /** 读回本地缓存的 table 值（只反映本类写进去的内容）。 */
    public int readTable() {
        return this.tableCache;
    }

    /** 写入 controls（4 槽 × 4 位）。 */
    public void setControls(int value) {
        setIntEntityProperty(P_CONTROLS, value & CONTROLS_MAX);
        syncAfterWrite();
    }

    /** 写入 seats（每座位 2 位）。 */
    public void setSeatVisuals(int value) {
        setIntEntityProperty(P_SEATS, value & SEATS_MAX);
        syncAfterWrite();
    }

    /**
     * 写入地主收底牌动画用的 3 张底牌 id（{@code bottom_take} 动画按它认牌）。
     *
     * <p>编码与模组一致：{@code (id0 & 63) + ((id1 & 63) << 6) + ((id2 & 63) << 12)}。
     * 必须在播 {@code bottom_take} 动画<b>之前</b>写入 —— 动画一开跑，客户端就读这个属性。</p>
     */
    public void setBottomTake(int card0, int card1, int card2) {
        int v = (card0 & 63) + ((card1 & 63) << 6) + ((card2 & 63) << 12);
        setIntEntityProperty(P_BOTTOM, v & BOTTOM_MAX);
        syncAfterWrite();
    }

    /**
     * 写入本局地赖子点数（{@code 0} = 无癞子）。
     *
     * <p>客户端 {@code gold_NN} 骨骼的 {@code scale} 读这个属性 ——
     * 换局 / 叫牌 / 定地主时都会重写一次，写值相同时直接跳过发包。</p>
     *
     * @param rank 0..17；3..15 是正常点数，16/17 为大小王（规则上不用）
     */
    public void setWildRank(int rank) {
        setWildRanks(this.wildHeavenCache, rank);
    }

    /**
     * 一并写入<b>天癞子 + 地赖子</b>两个点数（2026-10-25「天地癞子」）。
     *
     * <p>两个值各带缓存，都没变就一个包都不发；只要有一个变了就整批提交一次
     * （{@link #syncAfterWrite()} 走批量合并，不会发两个元数据包）。</p>
     *
     * @param heavenRank 天癞子点数（0 = 无）
     * @param earthRank  地赖子点数（0 = 无）
     */
    public void setWildRanks(int heavenRank, int earthRank) {
        int h = Math.max(0, Math.min(WILD_MAX, heavenRank));
        int e = Math.max(0, Math.min(WILD_MAX, earthRank));
        boolean dirty = false;
        if (h != this.wildHeavenCache) {
            this.wildHeavenCache = h;
            setIntEntityProperty(P_WILD_HEAVEN, h);
            dirty = true;
        }
        if (e != this.wildCache) {
            this.wildCache = e;
            setIntEntityProperty(P_WILD, e);
            dirty = true;
        }
        if (dirty) {
            syncAfterWrite();
        }
    }

    /** 读回本地缓存的地赖子点数（只反映本类写进去的内容）。 */
    public int readWildRank() {
        return this.wildCache;
    }

    /** 读回本地缓存的天癞子点数。 */
    public int readHeavenRank() {
        return this.wildHeavenCache;
    }

    /**
     * 写入「癞子被打出时当成了哪个点数」（天 / 地各 4 张 × 5 位 = 20 位）。
     *
     * <p>客户端据此把出牌区的癞子牌面换成「金 &lt;当的点数&gt;」，
     * 左下角再叠一个灰色的原点数（2026-10-26 用户要求）。</p>
     *
     * <p>两个值各带缓存，都没变就不发包。</p>
     *
     * @param heavenPack 天癞子 4 张的当值（0 = 都还没打出）
     * @param earthPack  地癞子 4 张的当值
     */
    public void setWildAs(int heavenPack, int earthPack) {
        int h = Math.max(0, Math.min(WILD_AS_MAX, heavenPack));
        int e = Math.max(0, Math.min(WILD_AS_MAX, earthPack));
        boolean dirty = false;
        if (h != this.wildAsHeavenCache) {
            this.wildAsHeavenCache = h;
            setIntEntityProperty(P_WILD_AS_H, h);
            dirty = true;
        }
        if (e != this.wildAsCache) {
            this.wildAsCache = e;
            setIntEntityProperty(P_WILD_AS_E, e);
            dirty = true;
        }
        if (dirty) {
            syncAfterWrite();
        }
    }

    /** 读回本地缓存的「天癞子当值」打包值（调试用）。 */
    public int readWildAsHeaven() {
        return this.wildAsHeavenCache;
    }

    /**
     * 写入癞子的「插入排序」信息（{@code playing_card_wild_sort} / {@code _sort2}，
     * 每个属性 2 档 × 12 位，共 4 档）。
     *
     * <p>客户端 {@code pre_animation} 读它把牌的区内序号 {@code v.sN} 平移到位，
     * 再走一次方向镜像（跟手牌排序开关同步）⇒ 出牌区按当值排、同点数癞子在前。</p>
     *
     * <p>值没变就不发包；两个属性一起发（省一次 sync）。</p>
     *
     * @param pack  第 1、2 档（bit 0..11 / 12..23）
     * @param pack2 第 3、4 档（同上）
     */
    public void setWildSort(int pack, int pack2) {
        int v1 = Math.max(0, Math.min(WILD_SORT_MAX, pack));
        int v2 = Math.max(0, Math.min(WILD_SORT_MAX, pack2));
        if (v1 == this.wildSortCache && v2 == this.wildSort2Cache) {
            return;
        }
        this.wildSortCache = v1;
        this.wildSort2Cache = v2;
        setIntEntityProperty(P_WILD_SORT, v1);
        setIntEntityProperty(P_WILD_SORT2, v2);
        syncAfterWrite();
    }

    /**
     * 写入手牌左右排序码（{@code 0} = 小→大，{@code 1} = 大→小）。
     *
     * <p>纯画面属性，改完立刻重排；写值相同就跳过发包。</p>
     */
    public void setHandSort(int code) {
        int v = code <= 0 ? 0 : 1;
        if (v == this.handSortCache) {
            return;
        }
        this.handSortCache = v;
        setIntEntityProperty(P_HAND_SORT, v);
        syncAfterWrite();
    }

    /** 读回本地缓存的手牌排序码。 */
    public int readHandSort() {
        return this.handSortCache;
    }

    /** 单条属性变更后的同步；批量模式下跳过，由 {@link #endBatch()} 统一发包。 */
    private void syncAfterWrite() {
        if (this.batching) {
            return;
        }
        this.syncProperties();
    }

    /**
     * 一次性把全部属性发给客户端。
     * <p>用 {@code sendData} 而不是 despawn+spawn：后者会让整桌闪一下，
     * 而且模组那份 {@code syncProperties} 也是这么做的（已真机验证）。
     */
    public void syncProperties() {
        if (this.getViewers().isEmpty()) {
            return;
        }
        this.sendData(this.getViewers().values().toArray(new Player[0]));
    }

    // ------------------------------------------------------------------
    // table 位域辅助
    // ------------------------------------------------------------------

    /**
     * 写入「当前行动方」→ 驱动客户端那枚黄色三角回合标记。
     *
     * @param seat 座位 0..2；传 -1 表示当前无人行动（回合标记全部 scale=0）
     */
    public void setActor(int seat) {
        int v = readTable() & ~ACTOR_MASK;
        if (seat >= 0) {
            v |= ((seat + 1) & 0x3) << ACTOR_SHIFT;
        }
        // ⚠️ 必须走 setTable()（它同时更新 tableCache 并发包）。
        // 初版直接 setIntEntityProperty：缓存里没有 actor 位，紧接着的 setReveal /
        // setButtonOwner 都基于旧缓存重写整个属性 ⇒ actor 位被静默抹掉，
        // 真机表现就是「黄色三角永远不出现」。
        setTable(v);
    }

    /**
     * 写入地主座位。
     *
     * @param seat 座位 0..2；传 -1 表示尚未定地主（写 0，徽记 scale=0）
     */
    public void setLandlord(int seat) {
        int v = readTable() & ~LANDLORD_MASK;
        if (seat >= 0) {
            v |= ((seat + 1) & LANDLORD_MASK) << LANDLORD_SHIFT;
        }
        setTable(v);
    }

    /** 读回地主座位（0..2）；未定时返回 -1。 */
    public int readLandlord() {
        int raw = (readTable() & LANDLORD_MASK) >>> LANDLORD_SHIFT;
        return raw == 0 ? -1 : raw - 1;
    }

    /**
     * 写入按钮可见归属（只给该座位看）。
     *
     * @param owner 座位 0..2；传 -1 表示谁都看不到
     */
    public void setButtonOwner(int owner) {
        int v = readTable() & ~(BUTTON_OWNER_MASK | VISIBLE_ALL_MASK);
        if (owner >= 0) {
            v |= ((owner + 1) & 0x3) << BUTTON_OWNER_SHIFT;
        }
        setTable(v);
    }

    /** 读回按钮可见归属座位（0..2）；0（谁都不看）或全场可见时返回 -1。 */
    public int readButtonOwner() {
        if ((readTable() & VISIBLE_ALL_MASK) >>> VISIBLE_ALL_SHIFT == VISIBLE_ALL) {
            return -1;
        }
        int raw = (readTable() & BUTTON_OWNER_MASK) >>> BUTTON_OWNER_SHIFT;
        return raw == 0 ? -1 : raw - 1;
    }

    /**
     * 设置「按钮对全场可见」。
     *
     * @param all true = 全场可见（bits 6..8 写 6）；false = 改回只给 {@link #setButtonOwner} 那家看
     */
    public void setButtonsVisibleToAll(boolean all) {
        int v = readTable() & ~VISIBLE_ALL_MASK;
        if (all) {
            v |= VISIBLE_ALL << VISIBLE_ALL_SHIFT;
        }
        setTable(v);
    }

    /**
     * 设置「结算公开手牌」（竖着翻开，位置不动）。
     *
     * @param on true = 置 reveal 位；false = 清除
     */
    public void setReveal(boolean on) {
        int v = readTable() & ~REVEAL_MASK;
        if (on) {
            v |= 1 << REVEAL_SHIFT;
        }
        setTable(v);
    }

    /** 读回结算公开标志。 */
    public boolean readReveal() {
        return (readTable() & REVEAL_MASK) != 0;
    }

    /**
     * 本地缓存的 table 值。
     * <p>Nukkit 的 {@code setIntEntityProperty} 是 {@code final}，覆盖不了；
     * 而 {@code Entity} 也没有属性读取 API。所以每次写 table 时顺手在这里留一份 ——
     * 它只缓存**我们自己写进去**的值，因此与客户端必然一致。
     * <p>⚠️ 直接改 {@code tableCache} 的位域辅助方法（{@link #setLandlord} 等）走的是
     * {@link #setTable}，那才是唯一写入口 —— 别绕开它直接改缓存。
     */
    private int tableCache;

    // ------------------------------------------------------------------
    // controls 打包
    // ------------------------------------------------------------------

    /** 把 4 个按钮动作打包成 controls 值（槽 0 在屏幕**最右**，见 {@link Controls}）。 */
    public static int packControls(Controls... slots) {
        int v = 0;
        for (int i = slots.length - 1; i >= 0; i--) {
            Controls c = (slots[i] == null) ? Controls.NONE : slots[i];
            v = (v << BUTTON_BITS) | (c.code() & BUTTON_MASK);
        }
        return v & CONTROLS_MAX;
    }

    /** 取 controls 里第 {@code slot} 槽的动作码。 */
    public static int controlAt(int packed, int slot) {
        return (packed >> (BUTTON_BITS * slot)) & BUTTON_MASK;
    }

    /** 取 controls 里第 {@code slot} 槽的动作。 */
    public static Controls controlActionAt(int packed, int slot) {
        return Controls.of(controlAt(packed, slot));
    }

    /** 把 3 个座位的托管状态打包成 seats 值（每座 2 位：0 正常 / 1 托管 / 2 离线托管）。 */
    public static int packSeats(int[] perSeat) {
        int v = 0;
        for (int r = SEATS - 1; r >= 0; r--) {
            int s = (perSeat != null && r < perSeat.length) ? perSeat[r] : 0;
            v = (v << 2) | (s & 0x3);
        }
        return v & SEATS_MAX;
    }
}
