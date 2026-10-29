package com.tabletopgames.game.poker;

import java.util.Random;

/**
 * 52 张牌的基础表示。
 *
 * <p>牌 id = {@code suit * 13 + rank}：{@code suit} 0=♥ 1=♦ 2=♣ 3=♠，
 * {@code rank} 0=A 1=2 … 9=10 10=J 11=Q 12=K。这个顺序与图集
 * {@code textures/ddz/ddz_atlas.png} 里扑克分区的摆位一一对应
 * （{@code tools/poker/build_atlas.py}，UV 清单见 {@code tools/poker/poker_uv.json}），
 * 改一边必须改另一边，否则「发到手的牌」和「画出来的牌」不是同一张。</p>
 */
public final class PokerCards {

    public static final int DECK = 52;

    private static final String[] RANKS = {
            "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUITS = {"♥", "♦", "♣", "♠"};
    /** 每个花色的中文名（播报里不用，调试日志用）。 */
    private static final String[] SUIT_NAMES = {"红桃", "方块", "梅花", "黑桃"};

    private PokerCards() {
    }

    public static int suit(int id) {
        return id / 13;
    }

    public static int rank(int id) {
        return id % 13;
    }

    public static String rankName(int rank) {
        return RANKS[rank];
    }

    public static String suitSymbol(int suit) {
        return SUITS[suit];
    }

    /** 单张牌的可读形式，例如 {@code ♥A}、{@code ♠10}。 */
    public static String name(int id) {
        return SUITS[suit(id)] + RANKS[rank(id)];
    }

    /**
     * 带颜色的单张牌（2026-10-26 用户指定，当晚又调过一版）：
     * 红桃/方块 = §c（亮红），黑桃/梅花 = §j（下界合金深灰），点数 = §f（白）。
     */
    public static String coloredName(int id) {
        int s = suit(id);
        String color = (s == 0 || s == 1) ? "§c" : "§j";
        return color + SUITS[s] + "§f" + RANKS[rank(id)];
    }

    /** 带颜色的紧凑写法（结算播报用），牌间无空格。 */
    public static String coloredCompact(int[] cards, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count && i < cards.length; i++) {
            if (cards[i] < 0) {
                continue;
            }
            sb.append(coloredName(cards[i]));
        }
        return sb.toString();
    }

    public static String debugName(int id) {
        return SUIT_NAMES[suit(id)] + RANKS[rank(id)];
    }

    /** 洗一副新牌（Fisher-Yates）。 */
    public static int[] shuffled(Random rnd) {
        int[] deck = new int[DECK];
        for (int i = 0; i < DECK; i++) {
            deck[i] = i;
        }
        for (int i = DECK - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = deck[i];
            deck[i] = deck[j];
            deck[j] = t;
        }
        return deck;
    }

    /**
     * 紧凑写法，用于结算播报：{@code ♥5♠6♣7♦8♦9}（牌与牌之间不留空格）。
     * 用户 2026-10-25 指定的格式，别加空格。
     */
    public static String compact(int[] cards, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count && i < cards.length; i++) {
            if (cards[i] < 0) {
                continue;
            }
            sb.append(name(cards[i]));
        }
        return sb.toString();
    }

    /** 把若干张牌拼成 {@code ♥A ♠10} 这样的串（跳过 0 与负数）。 */
    public static String text(int[] cards, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count && i < cards.length; i++) {
            if (cards[i] < 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(name(cards[i]));
        }
        return sb.toString();
    }
}
