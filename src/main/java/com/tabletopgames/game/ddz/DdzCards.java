package com.tabletopgames.game.ddz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 54 张牌的编码与发牌（与 addon 一致）。
 * <p>
 * 牌 id：0..51 为 {@code rank = 3 + id/4}（3..15，3最小…2最大），id 52 = 小王(16)，id 53 = 大王(17)。
 * 花色 {@code suit = id % 4}（0 梅花 1 黑桃 2 红心 3 方块，仅用于贴图 UV，规则无关）。
 */
public final class DdzCards {

    /** 洗牌模式（照模组 zc 的三种）。 */
    public enum DealMode {
        /** 标准 Fisher-Yates 全随机（默认）。 */
        CLASSIC,
        /** 分组洗牌 + 24 次随机交换：同点数的 4 张仍有机会凑在一起。 */
        JOYFUL_CLASSIC,
        /** 分组洗牌 + 6 次随机交换：接近不洗。 */
        NO_SHUFFLE
    }

    public static final int COUNT = 54;
    /** 小王 rank。 */
    public static final int RANK_SMALL_JOKER = 16;
    /** 大王 rank。 */
    public static final int RANK_BIG_JOKER = 17;

    private DdzCards() {
    }

    /** 牌 id → 点数 rank（3..15，16 小王，17 大王）。 */
    public static int rank(int id) {
        if (id == 52) {
            return RANK_SMALL_JOKER;
        }
        if (id == 53) {
            return RANK_BIG_JOKER;
        }
        return 3 + id / 4;
    }

    /**
     * 这张牌在本局是不是癞子（点数 == 癞子点数）。
     *
     * @param wildRank 癞子点数 3..15；{@code <= 0} 表示本局没有癞子
     */
    public static boolean isWild(int id, int wildRank) {
        return wildRank > 0 && isValid(id) && rank(id) == wildRank;
    }

    /**
     * 双癞子（天地癞子）：两个点数都算癞子。
     *
     * <p>2026-10-25 新增。天癞子在发牌前抽、地癞子在定地主后抽，两者都是万能牌，
     * 所以「是不是癞子」必须同时看两个点数 —— 单点版本的 {@link #isWild(int, int)}
     * 保留给「只有地赖子」的旧模式，行为不变。</p>
     *
     * @param a 天癞子点数（0 = 没有）
     * @param b 地赖子点数（0 = 没有）
     */
    public static boolean isWild(int id, int a, int b) {
        if (!isValid(id)) {
            return false;
        }
        int r = rank(id);
        return (a > 0 && r == a) || (b > 0 && r == b);
    }

    /** 合法牌 id 校验。 */
    public static boolean isValid(int id) {
        return id >= 0 && id < COUNT;
    }

    /** 升序排序（按 rank，同 rank 按 id）。 */
    public static void sortAscending(List<Integer> cards) {
        Collections.sort(cards, (a, b) -> {
            int ra = rank(a);
            int rb = rank(b);
            return ra != rb ? Integer.compare(ra, rb) : Integer.compare(a, b);
        });
    }

    /** 返回升序副本。 */
    public static List<Integer> sortedCopy(List<Integer> cards) {
        List<Integer> copy = new ArrayList<>(cards);
        sortAscending(copy);
        return copy;
    }

    /**
     * 标准 Fisher-Yates 洗牌（dealMode = classic）。
     */
    public static void shuffleClassic(List<Integer> deck, Random random) {
        for (int i = deck.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = deck.get(i);
            deck.set(i, deck.get(j));
            deck.set(j, tmp);
        }
    }

    /**
     * 分组洗牌（dealMode = joyful_classic / no_shuffle 共用，addon zc）：
     * 13 组同点数 4 张 + 大小王牌组 → 洗组 → 展平 → 循环移位 → 随机交换 t 次。
     *
     * @param exchanges 交换次数：joyful_classic = 24，no_shuffle = 6
     */
    public static void shuffleGrouped(List<Integer> deck, int exchanges, Random random) {
        List<List<Integer>> groups = new ArrayList<>();
        for (int g = 0; g < 13; g++) {
            List<Integer> group = new ArrayList<>();
            for (int k = 0; k < 4; k++) {
                group.add(g * 4 + k);
            }
            groups.add(group);
        }
        groups.add(new ArrayList<>(List.of(52, 53)));
        // 洗组
        for (int i = groups.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            List<Integer> tmp = groups.get(i);
            groups.set(i, groups.get(j));
            groups.set(j, tmp);
        }
        deck.clear();
        for (List<Integer> group : groups) {
            deck.addAll(group);
        }
        // 循环移位
        int rotate = random.nextInt(deck.size());
        Collections.rotate(deck, rotate);
        // 随机交换
        for (int i = 0; i < exchanges; i++) {
            int a = random.nextInt(deck.size());
            int b = random.nextInt(deck.size());
            int tmp = deck.get(a);
            deck.set(a, deck.get(b));
            deck.set(b, tmp);
        }
    }

    /**
     * 按模式洗一副完整牌。
     *
     * @return 54 张的新牌组
     */
    public static List<Integer> deal(DealMode mode, Random random) {
        List<Integer> deck = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            deck.add(i);
        }
        switch (mode) {
            case JOYFUL_CLASSIC -> shuffleGrouped(deck, 24, random);
            case NO_SHUFFLE -> shuffleGrouped(deck, 6, random);
            default -> shuffleClassic(deck, random);
        }
        return deck;
    }

    /**
     * 分牌（addon Uc）：前 17 张给座位 0，次 17 给座位 1，再次 17 给座位 2，
     * 最后 3 张为底牌。每手牌内部升序。
     */
    public static HandDeal split(List<Integer> deck) {
        List<Integer> h0 = sortedCopy(deck.subList(0, 17));
        List<Integer> h1 = sortedCopy(deck.subList(17, 34));
        List<Integer> h2 = sortedCopy(deck.subList(34, 51));
        List<Integer> bottom = sortedCopy(deck.subList(51, 54));
        return new HandDeal(h0, h1, h2, bottom);
    }

    /** 分牌结果。 */
    public record HandDeal(List<Integer> seat0, List<Integer> seat1, List<Integer> seat2,
                           List<Integer> bottomCards) {

        public List<Integer> hand(int seat) {
            return switch (seat) {
                case 0 -> seat0;
                case 1 -> seat1;
                default -> seat2;
            };
        }
    }
}
