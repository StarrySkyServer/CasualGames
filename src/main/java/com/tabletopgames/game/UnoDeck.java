package com.tabletopgames.game;

import java.util.ArrayList;
import java.util.List;

/**
 * UNO 牌组（Mattel 官方 108 张）与牌面元数据。
 * <p>
 * 牌 id 0..107 是**物理牌身份**，牌桌上每张牌占一个固定卡槽（几何体骨骼 / 实体属性分区），
 * 因此同一张牌面可能出现两次（例如两张「红 5」占两个不同卡槽）。
 * <p>
 * 组成（每色 25 张 × 4 色 = 100，再加 8 张野牌）：
 * <pre>
 *   0        ×1
 *   1..9     ×2   （18 张）
 *   跳过     ×2
 *   反转     ×2
 *   +2       ×2
 * </pre>
 * 野牌：Wild ×4、Wild+4 ×4。
 * <p>
 * 图集格（{@link #face(int)}，与用户提供的 {@code preview_full_deck.png} 14×4=56 格一致）：
 * <pre>
 *   0..51  四色（红→黄→绿→蓝），每色 [0..9, 跳过, 反转, +2]
 *   52     Wild
 *   53     Wild+4
 *   54     牌背
 *   55     "?"（中央牌堆面，防偷看）
 * </pre>
 */
public final class UnoDeck {

    // ---------------- 颜色 ----------------
    public static final int COLOR_RED = 0;
    public static final int COLOR_YELLOW = 1;
    public static final int COLOR_GREEN = 2;
    public static final int COLOR_BLUE = 3;
    /** 无颜色的野牌（牌面仍是黑底彩色）。 */
    public static final int COLOR_WILD = 4;
    /** 实体颜色种类数（选色按钮个数）。 */
    public static final int COLOR_COUNT = 4;

    // ---------------- 牌型 ----------------
    public static final int KIND_NUMBER = 0;
    public static final int KIND_SKIP = 1;
    public static final int KIND_REVERSE = 2;
    public static final int KIND_DRAW_TWO = 3;
    public static final int KIND_WILD = 4;
    public static final int KIND_WILD_DRAW_FOUR = 5;

    /** 牌堆总张数。 */
    public static final int SIZE = 108;
    /** 每人起手张数。 */
    public static final int HAND_SIZE = 7;
    /** 支持的座位数区间。 */
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 4;

    // ---------------- 图集格 ----------------
    public static final int FACE_WILD = 52;
    public static final int FACE_WILD_DRAW_FOUR = 53;
    public static final int FACE_BACK = 54;
    public static final int FACE_UNKNOWN = 55;
    /** 图集格总数（56）。 */
    public static final int FACE_COUNT = 56;

    private static final int[] COLOR = new int[SIZE];
    private static final int[] KIND = new int[SIZE];
    private static final int[] NUMBER = new int[SIZE];
    private static final int[] FACE = new int[SIZE];
    private static final int[] POINTS = new int[SIZE];

    static {
        int id = 0;
        for (int c = 0; c < COLOR_COUNT; c++) {
            id = add(id, c, KIND_NUMBER, 0);
            for (int n = 1; n <= 9; n++) {
                id = add(id, c, KIND_NUMBER, n);
                id = add(id, c, KIND_NUMBER, n);
            }
            id = add(id, c, KIND_SKIP, -1);
            id = add(id, c, KIND_SKIP, -1);
            id = add(id, c, KIND_REVERSE, -1);
            id = add(id, c, KIND_REVERSE, -1);
            id = add(id, c, KIND_DRAW_TWO, -1);
            id = add(id, c, KIND_DRAW_TWO, -1);
        }
        for (int k = 0; k < 4; k++) {
            id = add(id, COLOR_WILD, KIND_WILD, -1);
        }
        for (int k = 0; k < 4; k++) {
            id = add(id, COLOR_WILD, KIND_WILD_DRAW_FOUR, -1);
        }
        if (id != SIZE) {
            throw new IllegalStateException("UNO 牌组张数错误：" + id);
        }
    }

    private static int add(int id, int color, int kind, int number) {
        COLOR[id] = color;
        KIND[id] = kind;
        NUMBER[id] = number;
        FACE[id] = face(color, kind, number);
        POINTS[id] = points(kind, number);
        return id + 1;
    }

    private static int face(int color, int kind, int number) {
        if (kind == KIND_WILD) {
            return FACE_WILD;
        }
        if (kind == KIND_WILD_DRAW_FOUR) {
            return FACE_WILD_DRAW_FOUR;
        }
        int base = color * 13;
        return switch (kind) {
            case KIND_NUMBER -> base + number;
            case KIND_SKIP -> base + 10;
            case KIND_REVERSE -> base + 11;
            default -> base + 12; // DRAW_TWO
        };
    }

    /**
     * 官方分值：数字牌 = 面值；跳过 / 反转 / +2 = 20；Wild / Wild+4 = 50。
     */
    private static int points(int kind, int number) {
        return switch (kind) {
            case KIND_NUMBER -> number;
            case KIND_SKIP, KIND_REVERSE, KIND_DRAW_TWO -> 20;
            default -> 50;
        };
    }

    private UnoDeck() {
    }

    /** 牌 id → 颜色（0..3 实色，4 = 野牌）。 */
    public static int color(int id) {
        return COLOR[id];
    }

    /** 牌 id → 牌型（{@code KIND_*}）。 */
    public static int kind(int id) {
        return KIND[id];
    }

    /** 牌 id → 数字（仅数字牌有效，其余为 -1）。 */
    public static int number(int id) {
        return NUMBER[id];
    }

    /** 牌 id → 图集格索引（0..55）。 */
    public static int face(int id) {
        return FACE[id];
    }

    /** 牌 id → 官方分值。 */
    public static int points(int id) {
        return POINTS[id];
    }

    /** 是否野牌（Wild / Wild+4）。 */
    public static boolean isWild(int id) {
        return COLOR[id] == COLOR_WILD;
    }

    /** 是否为 +2 / +4 这类「摸牌牌」。 */
    public static int drawAmount(int id) {
        return switch (KIND[id]) {
            case KIND_DRAW_TWO -> 2;
            case KIND_WILD_DRAW_FOUR -> 4;
            default -> 0;
        };
    }

    /** 颜色中文名（表单 / 聊天提示用）。 */
    public static String colorName(int color) {
        return switch (color) {
            case COLOR_RED -> "红";
            case COLOR_YELLOW -> "黄";
            case COLOR_GREEN -> "绿";
            case COLOR_BLUE -> "蓝";
            default -> "无";
        };
    }

    /** 牌的文本名（聊天提示用，如「红 5」「Wild+4」）。 */
    public static String name(int id) {
        int kind = KIND[id];
        if (kind == KIND_WILD) {
            return "Wild";
        }
        if (kind == KIND_WILD_DRAW_FOUR) {
            return "Wild+4";
        }
        String prefix = colorName(COLOR[id]);
        return switch (kind) {
            case KIND_NUMBER -> prefix + " " + NUMBER[id];
            case KIND_SKIP -> prefix + " 跳过";
            case KIND_REVERSE -> prefix + " 反转";
            default -> prefix + " +2";
        };
    }

    /** 一副全新的有序牌（0..107），调用方自行洗牌。 */
    public static List<Integer> freshDeck() {
        List<Integer> deck = new ArrayList<>(SIZE);
        for (int id = 0; id < SIZE; id++) {
            deck.add(id);
        }
        return deck;
    }

    /** 手牌升序排序：先按颜色，再按牌型，最后按数字（仅影响展示）。 */
    public static void sortHand(List<Integer> hand) {
        hand.sort((a, b) -> {
            int c = Integer.compare(COLOR[a], COLOR[b]);
            if (c != 0) {
                return c;
            }
            int k = Integer.compare(KIND[a], KIND[b]);
            if (k != 0) {
                return k;
            }
            return Integer.compare(NUMBER[a], NUMBER[b]);
        });
    }
}
