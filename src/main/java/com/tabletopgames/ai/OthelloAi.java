package com.tabletopgames.ai;

/**
 * 黑白棋（奥赛罗）机器人：负极大值 + Alpha-Beta 剪枝 + 位置权重表 + 迭代加深。
 * <p>
 * 三档难度按目标搜索深度区分（入门 2 层 / 进阶 4 层 / 大师 6 层），单步搜索最长 {@link #MAX_MILLIS} 毫秒：
 * 超时则丢弃未跑完的深度，使用上一层已完成的结果。为保证“机器人有思考感”，即使提前搜完也会补足到
 * {@link #MIN_MILLIS} 毫秒再返回。整个过程在异步线程中执行，不阻塞服务端主线程。
 * <p>
 * <b>线程安全</b>：全部状态都是方法内的局部变量或每次搜索新建的对象，
 * 唯一的静态可变字段 {@code nodes/deadline/timeUp} 被 {@code synchronized} 包住，
 * 因此多盘棋并发思考时串行执行，不会互相污染。
 */
public final class OthelloAi {

    /** 棋盘边长。 */
    private static final int N = 8;

    private static final int EMPTY = 0;
    private static final int BLACK = 1;
    private static final int WHITE = 2;

    /** 每档难度的目标搜索深度：入门 / 进阶 / 大师。 */
    private static final int[] LEVEL_DEPTH = {2, 4, 6};

    /** 单步搜索时间上限（毫秒）。 */
    private static final int MAX_MILLIS = 2500;

    /** 单步搜索时间下限（毫秒）：保证「机器人有思考感」。 */
    private static final int MIN_MILLIS = 1000;

    /**
     * 位置权重表（经典奥赛罗启发式）：角最高、角邻位最差、边次之。
     * 下标 = {@code row*8+col}。
     */
    private static final int[] WEIGHT = {
            120, -20, 20, 5, 5, 20, -20, 120,
            -20, -40, -5, -5, -5, -5, -40, -20,
            20, -5, 15, 3, 3, 15, -5, 20,
            5, -5, 3, 3, 3, 3, -5, 5,
            5, -5, 3, 3, 3, 3, -5, 5,
            20, -5, 15, 3, 3, 15, -5, 20,
            -20, -40, -5, -5, -5, -5, -40, -20,
            120, -20, 20, 5, 5, 20, -20, 120,
    };

    private static final int[] DR = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1, -1, 1, -1, 0, 1};

    /** 搜索过程共享的可变状态，整体加锁串行使用。 */
    private static long deadline;
    private static int nodes;
    private static boolean timeUp;

    private OthelloAi() {
    }

    /**
     * 求一步最优着法。
     *
     * @param grid  {@code [行][列]}，值为 0 空 / 1 黑 / 2 白
     * @param color 走子方：1 黑 / 2 白
     * @param level 难度：1 入门 / 2 进阶 / 3 大师（越界按 3 处理）
     * @return {@code {行, 列}}；无合法着法时返回 {@code null}
     */
    public static int[] bestMove(int[][] grid, int color, int level) {
        long start = System.currentTimeMillis();
        int[] move = compute(grid, color, level);
        // 「思考感」补足：睡眠放在同步块之外，避免多盘棋同时思考时等待互相叠加
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed < MIN_MILLIS) {
            try {
                Thread.sleep(MIN_MILLIS - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return move;
    }

    /** 实际搜索：静态共享状态 {@code nodes/deadline/timeUp} 需串行使用，故整体同步。 */
    private static synchronized int[] compute(int[][] grid, int color, int level) {
        int lv = level < 1 ? 1 : Math.min(level, LEVEL_DEPTH.length);
        int maxDepth = LEVEL_DEPTH[lv - 1];
        int[] board = new int[N * N];
        for (int r = 0; r < N; r++) {
            System.arraycopy(grid[r], 0, board, r * N, N);
        }

        nodes = 0;
        timeUp = false;
        deadline = System.currentTimeMillis() + MAX_MILLIS;

        int best = -1;
        // 逐层加深：某层被打断则沿用上一层结果
        for (int depth = 2; depth <= maxDepth; depth += 2) {
            int[] result = searchRoot(board, color, depth);
            if (result == null) {
                break;
            }
            if (result[0] >= 0) {
                best = result[0];
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
        }
        if (best < 0) {
            return null;
        }
        return new int[]{best / N, best % N};
    }

    /**
     * 根节点搜索。
     *
     * @return {@code {着法, 分数}}；超时返回 {@code null}；无着可走返回 {@code {-1, 0}}
     */
    private static int[] searchRoot(int[] board, int color, int depth) {
        int[] moves = legalMoves(board, color);
        if (moves.length == 0) {
            return new int[]{-1, 0};
        }
        orderMoves(moves);
        int alpha = Integer.MIN_VALUE;
        int bestMove = moves[0];
        int bestScore = Integer.MIN_VALUE;
        for (int mv : moves) {
            int[] next = board.clone();
            applyMove(next, mv, color);
            int score = -negamax(next, other(color), depth - 1, Integer.MIN_VALUE, -alpha);
            if (timeUp) {
                return null;
            }
            if (score > bestScore) {
                bestScore = score;
                bestMove = mv;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return new int[]{bestMove, bestScore};
    }

    /** 负极大值搜索：返回从 {@code color} 视角评估的分数。 */
    private static int negamax(int[] board, int color, int depth, int alpha, int beta) {
        if ((++nodes & 1023) == 0 && System.currentTimeMillis() >= deadline) {
            timeUp = true;
        }
        if (timeUp) {
            return 0;
        }
        int[] moves = legalMoves(board, color);
        if (moves.length == 0) {
            // 本方无子可下：若对方也无子可下则终局，否则跳过回合
            if (!hasMove(board, other(color))) {
                return evaluate(board, color);
            }
            return -negamax(board, other(color), depth, -beta, -alpha);
        }
        if (depth <= 0) {
            return evaluate(board, color);
        }
        orderMoves(moves);
        int best = Integer.MIN_VALUE;
        for (int mv : moves) {
            int[] next = board.clone();
            applyMove(next, mv, color);
            int score = -negamax(next, other(color), depth - 1, -beta, -alpha);
            if (timeUp) {
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

    /** 从 {@code color} 视角评估盘面：位置权重差 + 行动力差。 */
    private static int evaluate(int[] board, int color) {
        int opp = other(color);
        int mine = 0;
        int theirs = 0;
        for (int i = 0; i < N * N; i++) {
            int v = board[i];
            if (v == color) {
                mine += WEIGHT[i];
            } else if (v == opp) {
                theirs += WEIGHT[i];
            }
        }
        int mobility = countMoves(board, color) - countMoves(board, opp);
        return (mine - theirs) + mobility * 6;
    }

    /** 枚举当前方的全部合法着法（一维下标）。 */
    private static int[] legalMoves(int[] board, int color) {
        int[] out = new int[N * N];
        int n = 0;
        for (int i = 0; i < N * N; i++) {
            if (board[i] == EMPTY && flipCount(board, i, color) > 0) {
                out[n++] = i;
            }
        }
        int[] result = new int[n];
        System.arraycopy(out, 0, result, 0, n);
        return result;
    }

    /** 当前方是否有合法着法（拿到第一个即返回，避免枚举全部）。 */
    private static boolean hasMove(int[] board, int color) {
        for (int i = 0; i < N * N; i++) {
            if (board[i] == EMPTY && flipCount(board, i, color) > 0) {
                return true;
            }
        }
        return false;
    }

    private static int countMoves(int[] board, int color) {
        int n = 0;
        for (int i = 0; i < N * N; i++) {
            if (board[i] == EMPTY && flipCount(board, i, color) > 0) {
                n++;
            }
        }
        return n;
    }

    /** 在 {@code idx} 落 {@code color} 子所能翻转的对方棋子数量。 */
    private static int flipCount(int[] board, int idx, int color) {
        if (board[idx] != EMPTY) {
            return 0;
        }
        int opp = other(color);
        int row = idx / N;
        int col = idx % N;
        int total = 0;
        for (int d = 0; d < 8; d++) {
            int r = row + DR[d];
            int c = col + DC[d];
            int cnt = 0;
            while (r >= 0 && r < N && c >= 0 && c < N && board[r * N + c] == opp) {
                r += DR[d];
                c += DC[d];
                cnt++;
            }
            if (cnt > 0 && r >= 0 && r < N && c >= 0 && c < N && board[r * N + c] == color) {
                total += cnt;
            }
        }
        return total;
    }

    /** 在 {@code idx} 落子并就地翻转被夹住的棋子。 */
    private static void applyMove(int[] board, int idx, int color) {
        board[idx] = color;
        int opp = other(color);
        int row = idx / N;
        int col = idx % N;
        for (int d = 0; d < 8; d++) {
            int r = row + DR[d];
            int c = col + DC[d];
            int cnt = 0;
            while (r >= 0 && r < N && c >= 0 && c < N && board[r * N + c] == opp) {
                r += DR[d];
                c += DC[d];
                cnt++;
            }
            if (cnt > 0 && r >= 0 && r < N && c >= 0 && c < N && board[r * N + c] == color) {
                int fr = row + DR[d];
                int fc = col + DC[d];
                for (int k = 0; k < cnt; k++) {
                    board[fr * N + fc] = color;
                    fr += DR[d];
                    fc += DC[d];
                }
            }
        }
    }

    /** 按位置权重从高到低排序，提高剪枝效率。 */
    private static void orderMoves(int[] moves) {
        for (int i = 1; i < moves.length; i++) {
            int value = moves[i];
            int key = WEIGHT[value];
            int j = i - 1;
            while (j >= 0 && WEIGHT[moves[j]] < key) {
                moves[j + 1] = moves[j];
                j--;
            }
            moves[j + 1] = value;
        }
    }

    private static int other(int color) {
        return color == BLACK ? WHITE : BLACK;
    }
}
