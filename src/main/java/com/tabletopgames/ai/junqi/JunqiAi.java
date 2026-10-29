package com.tabletopgames.ai.junqi;

import com.tabletopgames.game.JunqiRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 军旗机器人（三档：入门 / 进阶 / 大师）。
 *
 * <p>**纯逻辑、无 Nukkit 依赖**，走法与吃子全部走 {@link JunqiRules}，所以规则永远和人对局一致。</p>
 *
 * <p>子力价值表照抄外部模型（{@code 军棋AI模型-Java版/alphabeta-bot/Heuristic.getValueOfPiece}）：
 * 司令 2000、军长 1900、师长 1800、旅长 1700、团长 1600、营长 1500、连长 1400、排长 1300、
 * 工兵 1500、炸弹 1400、地雷 1200、军旗 20000。</p>
 *
 * <p><b>暗棋（不完全信息）</b>：机器人只能确定「自己人」和「已经亮明的敌子」；
 * 未亮明的敌子在搜索里用 {@link #UNKNOWN} 标出来，按「敌方剩余未见类型」随机采样补齐，
 * 大师档取多局采样的平均分。</p>
 */
public final class JunqiAi {

    public static final int LEVEL_EASY = 0;
    public static final int LEVEL_NORMAL = 1;
    public static final int LEVEL_MASTER = 2;

    public static final String[] LEVEL_NAMES = {"入门", "进阶", "大师"};

    /** 未知敌方棋子的类型码（真实类型只到 11；12..15 空闲）。 */
    public static final int UNKNOWN = 15;

    /** 子力价值（与外部模型一致）。 */
    private static final int[] VALUE = {
            2000, 1900, 1800, 1700, 1600, 1500, 1400, 1300, 1500, 1400, 1200, 20000};

    /** 大师档采样数。 */
    private static final int MASTER_SAMPLES = Integer.getInteger("junqi.samples", 6);
    /** 大师档搜索深度（层）。 */
    private static final int MASTER_DEPTH = 3;
    /** 进阶档：1 层贪心。 */
    private static final int NORMAL_DEPTH = 1;

    private JunqiAi() {
    }

    public static String levelName(int level) {
        return LEVEL_NAMES[Math.max(0, Math.min(2, level))];
    }

    /** 打包 from/to（1..60）。 */
    public static int pack(int from, int to) {
        return (from << 6) | to;
    }

    public static int from(int move) {
        return move >>> 6;
    }

    public static int to(int move) {
        return move & 0x3F;
    }

    /**
     * 选一步棋。
     *
     * @param grid         盘面（{@code grid[cell-1]}，敌方未亮明处为 {@link #UNKNOWN}）
     * @param side         自己执哪一方
     * @param unseenCounts 敌方「还没亮明」的各类型数量（下标 = 类型 0..11）
     * @param level        0 入门 / 1 进阶 / 2 大师
     * @return 打包后的着法；无棋可走返回 -1
     */
    public static int chooseMove(int[] grid, int side, int[] unseenCounts, int level, Random random) {
        List<Integer> moves = legalMoves(grid, side);
        if (moves.isEmpty()) {
            return -1;
        }
        switch (level) {
            case LEVEL_EASY:
                return chooseEasy(grid, side, moves, random);
            case LEVEL_NORMAL:
                return chooseSearch(grid, side, unseenCounts, moves, NORMAL_DEPTH, 1, random);
            default:
                return chooseSearch(grid, side, unseenCounts, moves, MASTER_DEPTH, MASTER_SAMPLES, random);
        }
    }

    /** 测试用：全信息、指定深度、单次采样（用来单独验证搜索本身）。 */
    public static int chooseMoveExact(int[] grid, int side, int depth, Random random) {
        List<Integer> moves = legalMoves(grid, side);
        if (moves.isEmpty()) {
            return -1;
        }
        return chooseSearch(grid, side, null, moves, depth, 1, random);
    }

    // ---------------------------------------------------------------- 入门
    private static int chooseEasy(int[] grid, int side, List<Integer> moves, Random random) {
        List<Integer> captures = new ArrayList<>();
        for (int move : moves) {
            int target = grid[to(move) - 1];
            if (target >= 0 && JunqiRules.sideOf(target) != side && JunqiRules.typeOf(target) != UNKNOWN) {
                captures.add(move);
            }
        }
        // 30% 概率随手吃一个（看得见的），其余纯随机
        if (!captures.isEmpty() && random.nextDouble() < 0.30) {
            return captures.get(random.nextInt(captures.size()));
        }
        return moves.get(random.nextInt(moves.size()));
    }

    // ---------------------------------------------------------------- 进阶 / 大师
    /**
     * 根节点选着法：对每个根着法做 depth 层搜索，采样 samples 次取平均。
     */
    private static int chooseSearch(int[] grid, int side, int[] unseenCounts,
                                    List<Integer> moves, int depth, int samples, Random random) {
        double[] total = new double[moves.size()];
        for (int s = 0; s < samples; s++) {
            int[] sample = fillUnknowns(grid, side, unseenCounts, random);
            for (int i = 0; i < moves.size(); i++) {
                int[] next = play(sample, side, moves.get(i));
                if (next == null) {
                    total[i] -= 50000;          // 非法（理论上不会）
                    continue;
                }
                // 搜索方永远从「自己的视角」评估
                total[i] += search(next, 1 - side, side, depth - 1, -1_000_000, 1_000_000);
            }
        }
        int best = 0;
        for (int i = 1; i < moves.size(); i++) {
            if (total[i] > total[best]) {
                best = i;
            }
        }
        return moves.get(best);
    }

    /**
     * 负极大值搜索（不带置换表：60 格、3 层完全够用）。
     *
     * @param root 评估视角（永远是机器人自己）
     */
    private static double search(int[] grid, int turn, int root, int depth, double alpha, double beta) {
        if (depth <= 0) {
            return evaluate(grid, root);
        }
        List<Integer> moves = legalMoves(grid, turn);
        if (moves.isEmpty()) {
            // 无子可动 = 输（对 root 而言）
            return turn == root ? -900000 : 900000;
        }
        // 走法排序：吃子优先、向前推进其次（提高剪枝效率）
        moves.sort((a, b) -> Double.compare(moveOrder(grid, turn, b), moveOrder(grid, turn, a)));
        // 根方取最大、对手取最小（评估永远从 root 视角）
        double best = turn == root ? -1_000_000 : 1_000_000;
        for (int move : moves) {
            int[] next = play(grid, turn, move);
            if (next == null) {
                continue;
            }
            double value = search(next, 1 - turn, root, depth - 1, alpha, beta);
            if (turn == root) {
                if (value > best) {
                    best = value;
                }
                alpha = Math.max(alpha, best);
            } else {
                if (value < best) {
                    best = value;
                }
                beta = Math.min(beta, best);
            }
            if (beta <= alpha) {
                break;
            }
        }
        return best;
    }

    private static double moveOrder(int[] grid, int turn, int move) {
        int target = grid[to(move) - 1];
        double score = 0;
        if (target >= 0 && JunqiRules.sideOf(target) != turn) {
            score += 1000 + valueOf(target);
        }
        // 向前推进（红方 row 递减、黑方 row 递增）
        int rowFrom = JunqiRules.rowOf(from(move));
        int rowTo = JunqiRules.rowOf(to(move));
        score += (turn == JunqiRules.SIDE_RED ? (rowFrom - rowTo) : (rowTo - rowFrom)) * 5.0;
        return score;
    }

    // ---------------------------------------------------------------- 盘面工具
    /** 合法着法（含吃子），规则完全复用 {@link JunqiRules}。 */
    public static List<Integer> legalMoves(int[] grid, int side) {
        int[] buf = new int[JunqiRules.CELLS];
        List<Integer> out = new ArrayList<>(64);
        for (int cell = 1; cell <= JunqiRules.CELLS; cell++) {
            int code = grid[cell - 1];
            if (code < 0 || JunqiRules.sideOf(code) != side) {
                continue;
            }
            int n = JunqiRules.generate(grid, cell, buf);
            for (int i = 0; i < n; i++) {
                out.add(pack(cell, buf[i]));
            }
        }
        return out;
    }

    /**
     * 走一步（含战斗结算），返回新盘面；非法返回 null。
     *
     * <p>未知守方按 {@code UNKNOWN} 处理：搜索里遇到未知时按「五五开的价值」估，
     * 具体由 {@link #resolveFight} 决定。</p>
     */
    private static int[] play(int[] grid, int side, int move) {
        int from = from(move);
        int to = to(move);
        int attacker = grid[from - 1];
        int defender = grid[to - 1];
        if (attacker < 0 || JunqiRules.sideOf(attacker) != side) {
            return null;
        }
        if (defender >= 0 && JunqiRules.sideOf(defender) == side) {
            return null;
        }
        int[] next = grid.clone();
        if (defender < 0) {
            next[to - 1] = attacker;
            next[from - 1] = -1;
            return next;
        }
        int result = resolveFight(JunqiRules.typeOf(attacker), JunqiRules.typeOf(defender));
        switch (result) {
            case JunqiRules.FIGHT_WIN, JunqiRules.FIGHT_FLAG -> {
                next[to - 1] = attacker;
                next[from - 1] = -1;
            }
            case JunqiRules.FIGHT_BOTH -> {
                next[from - 1] = -1;
                next[to - 1] = -1;
            }
            default -> next[from - 1] = -1;
        }
        return next;
    }

    /**
     * 未知守方的战斗结算：按「平均对手」估（工程兵挖雷、炸弹同归于尽仍按真实规则）。
     *
     * <p>这样机器人不会因为看不到对方类型而胡来：吃未知子按期望值算，越大的子越不敢乱碰。</p>
     */
    private static int resolveFight(int attacker, int defender) {
        if (defender != UNKNOWN) {
            return JunqiRules.fight(attacker, defender);
        }
        // 未知：把「被我吃」「同归于尽」「我被吃」按平均子力折成一个确定结果
        double win = 0;
        double even = 0;
        double lose = 0;
        for (int type = 0; type < 12; type++) {
            switch (JunqiRules.fight(attacker, type)) {
                case JunqiRules.FIGHT_WIN, JunqiRules.FIGHT_FLAG -> win += 1;
                case JunqiRules.FIGHT_BOTH -> even += 1;
                default -> lose += 1;
            }
        }
        // 用「相对收益」决定：赢面大按赢算，否则按同归于尽/输折中
        double score = win * 1.0 + even * 0.0 + lose * -1.0;
        if (score > 0.5) {
            return JunqiRules.FIGHT_WIN;
        }
        if (score < -0.5) {
            return JunqiRules.FIGHT_LOSE;
        }
        return JunqiRules.FIGHT_BOTH;
    }

    /** 随机补齐未知敌方棋子（按剩余未见类型的数量分布）。 */
    private static int[] fillUnknowns(int[] grid, int side, int[] unseenCounts, Random random) {
        List<Integer> pool = new ArrayList<>(25);
        if (unseenCounts != null) {
            for (int type = 0; type < 12; type++) {
                for (int i = 0; i < unseenCounts[type]; i++) {
                    pool.add(type);
                }
            }
        }
        java.util.Collections.shuffle(pool, random);
        int[] out = grid.clone();
        int cursor = 0;
        for (int i = 0; i < out.length; i++) {
            int code = out[i];
            if (code >= 0 && JunqiRules.sideOf(code) != side && JunqiRules.typeOf(code) == UNKNOWN) {
                int type = cursor < pool.size() ? pool.get(cursor++) : JunqiRules.CAPTAIN;
                out[i] = JunqiRules.code(JunqiRules.sideOf(code), type);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 评估
    private static double evaluate(int[] grid, int root) {
        double score = 0;
        for (int cell = 1; cell <= JunqiRules.CELLS; cell++) {
            int code = grid[cell - 1];
            if (code < 0) {
                continue;
            }
            int side = JunqiRules.sideOf(code);
            int type = JunqiRules.typeOf(code);
            double value = type == UNKNOWN ? 1500.0 : VALUE[type];
            // 位置分：越靠近对方底线越值钱；进了大本营扣分（不能动）
            int row = JunqiRules.rowOf(cell);
            double advance = side == JunqiRules.SIDE_RED ? (row - 6) : (5 - row);
            double positional = advance * 12.0;
            if (JunqiRules.isHq(cell)) {
                positional -= 120.0;
            }
            if (JunqiRules.isCamp(cell)) {
                positional += 40.0;
            }
            if (JunqiRules.isRail(cell)) {
                positional += 15.0;             // 铁路机动
            }
            // 逼近对方大本营（军旗最可能在那里）
            int targetRow = side == JunqiRules.SIDE_RED ? 0 : 11;
            positional -= Math.abs(row - targetRow) * 6.0;
            double signed = side == root ? (value + positional) : -(value + positional);
            score += signed;
        }
        return score;
    }

    /** 单枚棋子的价值（含未知的期望值）。 */
    private static double valueOf(int code) {
        int type = JunqiRules.typeOf(code);
        return type == UNKNOWN ? 1500.0 : VALUE[type];
    }
}
