package com.tabletopgames.ai.doushouqi;

import java.util.Random;

/**
 * 斗兽棋机器人：自写规则引擎 + 迭代加深 alpha-beta 搜索。
 * <p>
 * 三档难度按目标搜索层数区分（入门 2 层 / 进阶 4 层 / 大师 6 层），单步最长
 * {@link #MAX_MILLIS} 毫秒：某层没跑完就退回上一层已完成的着法。为保证「有思考感」，
 * 即使提前搜完也会补足到 {@link #MIN_MILLIS} 毫秒再返回。
 * <p>
 * <b>留获胜空间</b>：大师档在非必胜局面下有小概率（{@link #BLUNDER_RATE}）选择评分接近的
 * 次优着，使其实力大致相当于 90% 的人类水平，而不是不可战胜。
 * <p>
 * 每次调用都新建独立的 {@link Search}（含自己的棋盘副本与着法缓冲），因此多盘棋可并发思考。
 */
public final class DoushouqiAi {

    /** 每档难度的目标搜索层数：入门 / 进阶 / 大师。 */
    private static final int[] LEVEL_DEPTH = {2, 4, 6};

    /** 单步搜索时间上限（毫秒）：超过则丢弃未跑完的层，使用上一层结果。 */
    private static final int MAX_MILLIS = 4000;

    /** 单步最短思考时间（毫秒）：即使提前搜完也要等满，让落子有节奏感。 */
    private static final int MIN_MILLIS = 1000;

    /** 大师档「留获胜空间」的次优着触发概率。 */
    private static final double BLUNDER_RATE = 0.15;

    /** 搜索缓冲按层分配的最大层数（层数上限 + 1）。 */
    private static final int MAX_PLY = 24;

    private DoushouqiAi() {
    }

    /**
     * 求一步最优着法。
     *
     * @param grid  {@code [行][列]}，值为棋子编码（0..15，红 0..7 / 黑 8..15），{@code -1} 表示空格
     * @param color 走子方：0 = 红、1 = 黑
     * @param level 难度：1 = 入门、2 = 进阶、3 = 大师（越界按 3 处理）
     * @return {@code {起点行, 起点列, 终点行, 终点列}}；无着可走时返回 {@code null}
     */
    public static int[] bestMove(int[][] grid, int color, int level) {
        int level1 = level < 1 ? 1 : Math.min(level, LEVEL_DEPTH.length);
        long start = System.currentTimeMillis();
        Search search = new Search(grid, color, LEVEL_DEPTH[level1 - 1],
                start + MAX_MILLIS, level1 == 3);
        int[] move = search.think();
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed < MIN_MILLIS) {
            try {
                Thread.sleep(MIN_MILLIS - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (move == null) {
            return null;
        }
        return new int[]{
                move[0] / DoushouqiRules.COLS, move[0] % DoushouqiRules.COLS,
                move[1] / DoushouqiRules.COLS, move[1] % DoushouqiRules.COLS,
        };
    }

    /** 一次搜索的全部可变状态（棋盘副本、着法缓冲、随机源）。 */
    private static final class Search {

        /** 棋子类型估值（分）：象 狮 虎 豹 狗 狼 猫 鼠。 */
        private static final int[] VALUE = {900, 850, 800, 600, 400, 500, 300, 450};

        /** 胜/负分阈值。 */
        private static final int WIN = 900_000;

        /** 无限大（搜索边界）。 */
        private static final int INF = 1_500_000;

        /** 单个局面的最大着法数（每着 2 个 int，含余量）。 */
        private static final int BUF = 400;

        /**
         * 各层共用的着法缓冲：{@code moves[ply][index]}，每着占 2 个 int（from、to）。
         * 第 0 层留给根节点，{@code negamax} 从第 1 层起用。
         */
        private final int[][] moves = new int[MAX_PLY + 1][BUF];

        /** 各层着法的排序分，与 {@link #moves} 平行。 */
        private final int[][] scores = new int[MAX_PLY + 1][BUF];

        private final int[] board = new int[DoushouqiRules.CELLS];

        private final int side;
        private final int maxDepth;
        private final long deadline;
        private final boolean casual;
        private final Random random = new Random();
        private boolean timeout;

        Search(int[][] grid, int side, int maxDepth, long deadline, boolean casual) {
            this.side = side;
            this.maxDepth = maxDepth;
            this.deadline = deadline;
            this.casual = casual;
            for (int r = 0; r < DoushouqiRules.ROWS; r++) {
                System.arraycopy(grid[r], 0, board, r * DoushouqiRules.COLS, DoushouqiRules.COLS);
            }
        }

        /**
         * 迭代加深：逐层搜索，超时则沿用上一层已完成的最佳着法。
         * <p>
         * 根着法只在开始时生成一次，之后按上一层评分排序以改善剪枝；返回的结果即
         * {@code moves[0] / scores[0]} 的第 0 组（次优着位于第 1 组）。
         */
        int[] think() {
            int[] rootMoves = moves[0];
            int[] rootScores = scores[0];
            int rootCount = DoushouqiRules.generate(board, side, rootMoves);
            if (rootCount == 0 || DoushouqiRules.winnerAt(board) >= 0) {
                return null;
            }
            for (int i = 0; i < rootCount; i++) {
                rootScores[i] = moveScore(rootMoves[i * 2], rootMoves[i * 2 + 1], side);
            }
            sort(rootMoves, rootScores, rootCount);

            for (int depth = 2; depth <= maxDepth; depth += 2) {
                timeout = false;
                int[] trial = new int[rootCount];
                boolean complete = true;
                int alpha = -INF;
                for (int i = 0; i < rootCount; i++) {
                    int from = rootMoves[i * 2];
                    int to = rootMoves[i * 2 + 1];
                    int captured = board[to];
                    board[to] = board[from];
                    board[from] = DoushouqiRules.EMPTY;
                    int score;
                    if (to == DoushouqiRules.den(1 - side)) {
                        score = WIN - 1;
                    } else {
                        score = -negamax(1 - side, depth - 1, -INF, -alpha, 1);
                    }
                    // 撤销着法时先用暂存值写回原格，再恢复被吃子
                    board[from] = board[to];
                    board[to] = captured;
                    if (timeout) {
                        complete = false;
                        break;
                    }
                    trial[i] = score;
                    if (score > alpha) {
                        alpha = score;
                    }
                }
                if (!complete) {
                    break;
                }
                System.arraycopy(trial, 0, rootScores, 0, rootCount);
                // 根着法按本层评分降序重排，供下一层剪枝与最终选着使用
                sort(rootMoves, rootScores, rootCount);
                if (rootScores[0] >= WIN) {
                    break;
                }
            }
            // 留获胜空间：大师档在非必胜且次优着评分接近时，小概率改走次优着
            if (rootCount > 1 && casual && rootScores[0] < WIN
                    && random.nextDouble() < BLUNDER_RATE
                    && rootScores[1] >= rootScores[0] - 150) {
                return new int[]{rootMoves[2], rootMoves[3]};
            }
            return new int[]{rootMoves[0], rootMoves[1]};
        }

        /** alpha-beta 负极大值搜索，返回当前行棋方视角的分数。 */
        private int negamax(int moveSide, int depth, int alpha, int beta, int ply) {
            if ((ply & 3) == 0 && System.currentTimeMillis() >= deadline) {
                timeout = true;
                return 0;
            }
            int[] buf = moves[ply];
            int[] order = scores[ply];
            int count = DoushouqiRules.generate(board, moveSide, buf);
            if (count == 0) {
                // 无子可动即负（含已被吃光的情形）。
                return -WIN + ply;
            }
            if (depth <= 0) {
                return evaluate(moveSide);
            }
            for (int i = 0; i < count; i++) {
                order[i] = moveScore(buf[i * 2], buf[i * 2 + 1], moveSide);
            }
            sort(buf, order, count);
            int best = -INF;
            for (int i = 0; i < count; i++) {
                int from = buf[i * 2];
                int to = buf[i * 2 + 1];
                int captured = board[to];
                board[to] = board[from];
                board[from] = DoushouqiRules.EMPTY;
                int score;
                if (to == DoushouqiRules.den(1 - moveSide)) {
                    score = WIN - ply;          // 进对方兽穴 = 立刻获胜
                } else {
                    score = -negamax(1 - moveSide, depth - 1, -beta, -alpha, ply + 1);
                }
                board[from] = board[to];
                board[to] = captured;
                if (timeout) {
                    return 0;
                }
                if (score > best) {
                    best = score;
                }
                if (score > alpha) {
                    alpha = score;
                }
                if (alpha >= beta) {
                    break;
                }
            }
            return best;
        }

        /** 着法排序分：进取胜着最高，其次按「吃子价值 − 自身价值/8」，再按推进度。 */
        private int moveScore(int from, int to, int moveSide) {
            if (to == DoushouqiRules.den(1 - moveSide)) {
                return 1_000_000;
            }
            int victim = board[to];
            int attacker = board[from];
            int score = 0;
            if (victim != DoushouqiRules.EMPTY) {
                int def = DoushouqiRules.defense(victim, to);
                score = 100_000 + (def > 0 ? VALUE[DoushouqiRules.typeOf(victim)] * 8 : 900_000)
                        - VALUE[DoushouqiRules.typeOf(attacker)] / 8;
            }
            score += advance(moveSide, from, to);
            return score;
        }

        /** 「向对方兽穴靠近」的增量：红方行号减小为推进，黑方行号增大为推进。 */
        private static int advance(int moveSide, int from, int to) {
            int fromRow = from / DoushouqiRules.COLS;
            int toRow = to / DoushouqiRules.COLS;
            int delta = moveSide == DoushouqiRules.COLOR_RED ? fromRow - toRow : toRow - fromRow;
            return delta * 12;
        }

        /** 按排序分降序做插入排序，着法与评分两数组同步重排（着法数很少，插入排序最省开销）。 */
        private static void sort(int[] buf, int[] order, int count) {
            for (int i = 1; i < count; i++) {
                int s = order[i];
                int f = buf[i * 2];
                int t = buf[i * 2 + 1];
                int j = i - 1;
                while (j >= 0 && order[j] < s) {
                    order[j + 1] = order[j];
                    buf[(j + 1) * 2] = buf[j * 2];
                    buf[(j + 1) * 2 + 1] = buf[j * 2 + 1];
                    j--;
                }
                order[j + 1] = s;
                buf[(j + 1) * 2] = f;
                buf[(j + 1) * 2 + 1] = t;
            }
        }

        /**
         * 局面评估（{@code moveSide} 视角，单位约等于「分」）。
         * <p>
         * 主要项：子力价值（陷阱中的棋子价值大幅缩水）、向对方兽穴推进、逼近对方兽穴、
         * 鼠在河中的安全性。
         */
        private int evaluate(int moveSide) {
            int score = 0;
            int enemyDen = DoushouqiRules.den(1 - moveSide);
            int enemyDenRow = enemyDen / DoushouqiRules.COLS;
            int enemyDenCol = enemyDen % DoushouqiRules.COLS;
            for (int cell = 0; cell < DoushouqiRules.CELLS; cell++) {
                int tex = board[cell];
                if (tex == DoushouqiRules.EMPTY) {
                    continue;
                }
                int color = DoushouqiRules.colorOf(tex);
                int type = DoushouqiRules.typeOf(tex);
                int row = cell / DoushouqiRules.COLS;
                int col = cell % DoushouqiRules.COLS;
                int worth = VALUE[type];
                // 站在对方陷阱内：随时可能被任意子吃掉，估值按 1/6 计。
                if (DoushouqiRules.isTrapOf(1 - color, cell)) {
                    worth = worth / 6;
                }
                // 推进度：红方向上方（行减小）、黑方向下方（行增大）。
                worth += (color == DoushouqiRules.COLOR_RED ? DoushouqiRules.ROWS - 1 - row : row) * 6;
                // 逼近对方兽穴（曼哈顿距离 ≤ 3 时开始加分），己方子加得更多以示威胁。
                int dist = Math.abs(row - enemyDenRow) + Math.abs(col - enemyDenCol);
                if (dist <= 3) {
                    worth += (4 - dist) * (color == moveSide ? 30 : 25);
                }
                // 鼠在河中相对安全（陆上棋子吃不到它），略加分。
                if (type == DoushouqiRules.RAT && DoushouqiRules.isRiver(cell)) {
                    worth += 40;
                }
                score += color == moveSide ? worth : -worth;
            }
            return score;
        }
    }
}
