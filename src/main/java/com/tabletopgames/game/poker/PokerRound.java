package com.tabletopgames.game.poker;

/**
 * 一条街的下注状态机（不含牌、不含玩家对象，方便脱机测试）。
 *
 * <p>规则要点：</p>
 * <ul>
 *   <li>最小加注 = 上一次加注幅度（翻牌前初始为大盲）。</li>
 *   <li>筹码不足最小加注时只能全下。</li>
 *   <li>一条街结束 = 所有未弃牌、未全下的玩家下注额相等且都行动过。</li>
 * </ul>
 */
public final class PokerRound {

    public enum Action {
        FOLD, CHECK, CALL, RAISE, ALLIN
    }

    public final int seats;
    public final int[] stack;
    public final int[] bet;
    public final int[] total;
    public final boolean[] folded;
    public final boolean[] allin;
    public final boolean[] acted;

    public int currentBet;
    public int minRaise;
    public int actor;
    public int pot;

    public PokerRound(int seats) {
        this(seats, 0);
    }

    public PokerRound(int seats, int stackEach) {
        this.seats = seats;
        this.stack = new int[seats];
        this.bet = new int[seats];
        this.total = new int[seats];
        this.folded = new boolean[seats];
        this.allin = new boolean[seats];
        this.acted = new boolean[seats];
        for (int i = 0; i < seats; i++) {
            this.stack[i] = stackEach;
        }
    }

    public int toCall(int seat) {
        return Math.max(0, currentBet - bet[seat]);
    }

    public boolean canCheck(int seat) {
        return toCall(seat) == 0;
    }

    /** 该座位最小可以「加到」多少（本街总下注额）。 */
    public int minRaiseTo(int seat) {
        return Math.min(bet[seat] + stack[seat], currentBet + minRaise);
    }

    public int maxRaiseTo(int seat) {
        return bet[seat] + stack[seat];
    }

    /**
     * 执行一个动作。
     *
     * @return true = 合法并已生效；false = 非法（调用方应忽略）
     */
    public boolean act(int seat, Action action, int amount) {
        if (seat < 0 || seat >= seats || folded[seat] || allin[seat]) {
            return false;
        }
        switch (action) {
            case FOLD -> folded[seat] = true;
            case CHECK -> {
                if (toCall(seat) > 0) {
                    return false;
                }
            }
            case CALL -> {
                int pay = Math.min(toCall(seat), stack[seat]);
                pay(seat, pay);
            }
            case RAISE -> {
                int target = amount;
                if (target <= currentBet) {
                    return false;
                }
                int pay = Math.min(target - bet[seat], stack[seat]);
                if (pay <= 0) {
                    return false;
                }
                int newBet = bet[seat] + pay;
                boolean allIn = pay == stack[seat];
                if (newBet < currentBet + minRaise && !allIn) {
                    return false;
                }
                int inc = newBet - currentBet;
                if (inc >= minRaise) {
                    minRaise = inc;
                }
                currentBet = Math.max(currentBet, newBet);
                pay(seat, pay);
            }
            case ALLIN -> {
                int pay = stack[seat];
                if (pay <= 0) {
                    return false;
                }
                int newBet = bet[seat] + pay;
                int inc = newBet - currentBet;
                if (inc >= minRaise) {
                    minRaise = inc;
                }
                currentBet = Math.max(currentBet, newBet);
                pay(seat, pay);
            }
            default -> {
                return false;
            }
        }
        acted[seat] = true;
        return true;
    }

    private void pay(int seat, int amount) {
        if (amount <= 0) {
            return;
        }
        stack[seat] -= amount;
        bet[seat] += amount;
        total[seat] += amount;
        pot += amount;
        if (stack[seat] <= 0) {
            stack[seat] = 0;
            allin[seat] = true;
        }
    }

    /** 还在牌局里的座位数（未弃牌）。 */
    public int alive() {
        int n = 0;
        for (int i = 0; i < seats; i++) {
            if (!folded[i]) {
                n++;
            }
        }
        return n;
    }

    /** 还能继续下注的座位数（未弃牌且未全下）。 */
    public int actionable() {
        int n = 0;
        for (int i = 0; i < seats; i++) {
            if (!folded[i] && !allin[i]) {
                n++;
            }
        }
        return n;
    }

    /** 从 {@code from} 之后找下一个还能行动的人；没有返回 -1。 */
    public int nextActor(int from) {
        for (int k = 1; k <= seats; k++) {
            int s = (from + k) % seats;
            if (s == from) {
                break;
            }
            if (!folded[s] && !allin[s]) {
                return s;
            }
        }
        return -1;
    }

    /**
     * 本条街是否下注完毕。
     * <p>只剩一个未弃牌的人时也算结束（由调用方判「直接结算」）。</p>
     */
    public boolean streetDone() {
        if (alive() <= 1) {
            return true;
        }
        int live = 0;
        for (int i = 0; i < seats; i++) {
            if (folded[i] || allin[i]) {
                continue;
            }
            live++;
            if (!acted[i] || bet[i] != currentBet) {
                return false;
            }
        }
        return live > 0;
    }

    /** 进入下一条街：清本街下注，从庄家左手第一个还能行动的人开始。 */
    public int startStreet(int dealer) {
        currentBet = 0;
        minRaise = 10;
        for (int i = 0; i < seats; i++) {
            bet[i] = 0;
            acted[i] = false;
        }
        int first = nextActor(dealer);
        actor = first < 0 ? 0 : first;
        return actor;
    }

    /** 发盲注：扣钱但不算「已行动」。 */
    public void postBlind(int seat, int amount) {
        int pay = Math.min(amount, stack[seat]);
        pay(seat, pay);
        acted[seat] = false;
    }
}
