package com.tabletopgames.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 五子棋机器人引擎，提供三个等级的落子决策：
 * <ul>
 *     <li>1 入门：启发式评分 + 25% 随机噪声，只识别眼前的成五 / 活四；</li>
 *     <li>2 进阶：Minimax 深度 2，配合候选点排序与束宽裁剪；</li>
 *     <li>3 大师：迭代加深 α-β 搜索（最大深度 5），带时间上限、成五/冲四识别与走法排序；
 *     根节点对「同分/近似同分」候选做随机扰动，避免固定套路被反复利用。</li>
 * </ul>
 * 分档与评分表完全对齐 gomoku-ai-collection 中 CaroGame 的三档实现（easy / medium / hard），
 * 评估函数天然支持「恰好 5 连才算胜、长连不算」的 Overline 规则，因此可直接用于 Swap2。
 *
 * <p>引擎对传入的棋盘副本做原地试算并即时还原，调用方无需关心内部状态。
 */
public final class TabletopGamesAi {

    private static final int SIZE = 15;
    private static final int EMPTY = 0;
    private static final int BLACK = 1;
    private static final int WHITE = 2;

    // ── 棋型索引 ─────────────────────────────────────────────────────────────
    private static final int WIN = 0;
    private static final int OPEN_4 = 1;
    private static final int BLOCKED_4 = 2;
    private static final int OPEN_3 = 3;
    private static final int BLOCKED_3 = 4;
    private static final int OPEN_2 = 5;
    private static final int BLOCKED_2 = 6;
    private static final int OPEN_1 = 7;
    private static final int BLOCKED_1 = 8;
    private static final int OVERLINE = 9;
    private static final int NONE = -1;

    // ── 三档评分表（越靠前的棋型越强） ────────────────────────────────────────
    private static final long[] HARD_SCORES = {
            1000000000L, 100000000L, 1000000L, 10000000L, 10000L,
            100000L, 100L, 10L, 1L, -50000000L};
    private static final long[] MEDIUM_SCORES = {
            1000000000L, 10000000L, 5000000L, 10000L, 1000L,
            100L, 10L, 2L, 1L, -5000000L};
    private static final long[] EASY_SCORES = {
            1000000000L, 10000000L, 5000000L, 10L, 5L,
            1L, 0L, 0L, 0L, -1000000L};

    /** 入门档的“眼前威胁”阈值：达到活四分即不再随机。 */
    private static final long EASY_THREAT = EASY_SCORES[OPEN_4];

    private static final int[][] DIRS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    private static final long INF = 1_000_000_000_000_000L;

    private static final int MEDIUM_MAX_DEPTH = 2;
    private static final int MEDIUM_BEAM = 12;

    private static final int HARD_MAX_DEPTH = 5;
    /** 大师档单次思考的时间上限（1 秒），超时后返回上一层已完成搜索的最优解。 */
    private static final long HARD_TIME_LIMIT_NS = 1_000_000_000L;
    /**
     * 大师档根节点的随机扰动幅度。
     * <p>
     * 远小于棋型档位差（OPEN_3 = 10000），因此不会把明显更差的一手抬成最优；
     * 只用于打散「同分 / 近似同分」的多个好点，消除原版完全确定性带来的固定套路问题。
     */
    private static final int ROOT_JITTER = 1000;

    private static final Random RANDOM = new Random();

    private TabletopGamesAi() {
    }

    /**
     * 计算机器人的落点。
     *
     * @param board   棋盘副本（0 空 / 1 黑 / 2 白）
     * @param aiColor 机器人棋色
     * @param level   难度等级（1 入门 / 2 进阶 / 3 大师）
     * @return {row, col}，无可落点时返回 {@code null}
     */
    public static int[] bestMove(int[][] board, int aiColor, int level) {
        int playerColor = aiColor == BLACK ? WHITE : BLACK;
        switch (level) {
            case 1:
                return easyMove(board, aiColor, playerColor);
            case 2:
                return mediumMove(board, aiColor, playerColor);
            default:
                return hardMove(board, aiColor, playerColor);
        }
    }

    // ── 入门：启发式 + 随机噪声 ──────────────────────────────────────────────

    private static int[] easyMove(int[][] b, int bot, int player) {
        long maxScore = Long.MIN_VALUE;
        List<int[]> best = new ArrayList<>();
        List<int[]> valid = new ArrayList<>();

        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] != EMPTY || !hasNeighbor(b, r, c, 2)) {
                    continue;
                }
                valid.add(new int[]{r, c});
                long attack = evaluatePosition(b, r, c, bot, EASY_SCORES, false, false);
                long defense = evaluatePosition(b, r, c, player, EASY_SCORES, false, false);
                long total = attack + defense;
                if (total > maxScore) {
                    maxScore = total;
                    best.clear();
                    best.add(new int[]{r, c});
                } else if (total == maxScore) {
                    best.add(new int[]{r, c});
                }
            }
        }

        if (valid.isEmpty()) {
            return centerOrFirst(b);
        }
        // 没有眼前威胁时，有一定概率放弃最优解随机落子，模拟“新手”失误
        if (maxScore < EASY_THREAT && RANDOM.nextInt(100) < 25) {
            return valid.get(RANDOM.nextInt(valid.size()));
        }
        if (!best.isEmpty()) {
            return best.get(RANDOM.nextInt(best.size()));
        }
        return centerOrFirst(b);
    }

    // ── 进阶：Minimax 深度 2 ────────────────────────────────────────────────

    private static int[] mediumMove(int[][] b, int bot, int player) {
        List<Move> moves = generate(b, bot, player, MEDIUM_SCORES, false, 2, MEDIUM_BEAM);
        if (moves.isEmpty()) {
            return centerOrFirst(b);
        }
        // 优先级 1：自己一步成五
        for (Move m : moves) {
            b[m.row][m.col] = bot;
            boolean win = evaluatePosition(b, m.row, m.col, bot, MEDIUM_SCORES, false, false)
                    >= MEDIUM_SCORES[WIN];
            b[m.row][m.col] = EMPTY;
            if (win) {
                return new int[]{m.row, m.col};
            }
        }
        // 优先级 2：挡住对手一步成五
        for (Move m : moves) {
            b[m.row][m.col] = player;
            boolean win = evaluatePosition(b, m.row, m.col, player, MEDIUM_SCORES, false, false)
                    >= MEDIUM_SCORES[WIN];
            b[m.row][m.col] = EMPTY;
            if (win) {
                return new int[]{m.row, m.col};
            }
        }
        // 优先级 3：Minimax 深度 2
        long bestScore = -INF;
        int[] bestMove = {moves.get(0).row, moves.get(0).col};
        for (Move m : moves) {
            b[m.row][m.col] = bot;
            long score = minimaxMedium(b, MEDIUM_MAX_DEPTH - 1, false, bot, player);
            b[m.row][m.col] = EMPTY;
            if (score > bestScore) {
                bestScore = score;
                bestMove = new int[]{m.row, m.col};
            }
        }
        return bestMove;
    }

    private static long minimaxMedium(int[][] b, int depth, boolean maximizing, int bot, int player) {
        if (depth == 0) {
            return evaluateBoardMedium(b, bot, player);
        }
        List<Move> moves = generate(b, bot, player, MEDIUM_SCORES, false, 2, MEDIUM_BEAM);
        if (moves.isEmpty()) {
            return evaluateBoardMedium(b, bot, player);
        }

        if (maximizing) {
            long best = -INF;
            for (Move m : moves) {
                b[m.row][m.col] = bot;
                long eval;
                if (evaluatePosition(b, m.row, m.col, bot, MEDIUM_SCORES, false, false)
                        >= MEDIUM_SCORES[WIN]) {
                    eval = INF / 2 + depth;
                } else {
                    eval = minimaxMedium(b, depth - 1, false, bot, player);
                }
                b[m.row][m.col] = EMPTY;
                best = Math.max(best, eval);
            }
            return best;
        }

        long best = INF;
        for (Move m : moves) {
            b[m.row][m.col] = player;
            long eval;
            if (evaluatePosition(b, m.row, m.col, player, MEDIUM_SCORES, false, false)
                    >= MEDIUM_SCORES[WIN]) {
                eval = -INF / 2 - depth;
            } else {
                eval = minimaxMedium(b, depth - 1, true, bot, player);
            }
            b[m.row][m.col] = EMPTY;
            best = Math.min(best, eval);
        }
        return best;
    }

    private static long evaluateBoardMedium(int[][] b, int bot, int player) {
        long total = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] == bot) {
                    long score = evaluatePosition(b, r, c, bot, MEDIUM_SCORES, false, false);
                    if (score >= MEDIUM_SCORES[WIN]) {
                        return INF / 2;
                    }
                    total += score;
                } else if (b[r][c] == player) {
                    long score = evaluatePosition(b, r, c, player, MEDIUM_SCORES, false, false);
                    if (score >= MEDIUM_SCORES[WIN]) {
                        return -INF / 2;
                    }
                    total -= score;
                }
            }
        }
        return total;
    }

    // ── 大师：迭代加深 α-β ─────────────────────────────────────────────────

    private static int[] hardMove(int[][] b, int bot, int player) {
        long start = System.nanoTime();

        int[] opening = openingMove(b, bot);
        if (opening != null) {
            return opening;
        }
        int[] win = immediateMove(b, bot);
        if (win != null) {
            return win;
        }
        int[] block = immediateMove(b, player);
        if (block != null) {
            return block;
        }

        List<Move> rootMoves = generateHard(b, bot, player, HARD_MAX_DEPTH, true);
        if (rootMoves.isEmpty()) {
            return centerOrFirst(b);
        }

        int[] best = {rootMoves.get(0).row, rootMoves.get(0).col};
        long[] finalScores = null;
        for (int targetDepth = 1; targetDepth <= HARD_MAX_DEPTH; targetDepth++) {
            long iterBest = -INF;
            int[] iterMove = null;
            long[] scores = new long[rootMoves.size()];
            long alpha = -INF;
            boolean timedOut = false;

            for (int i = 0; i < rootMoves.size(); i++) {
                if (System.nanoTime() - start > HARD_TIME_LIMIT_NS) {
                    timedOut = true;
                    break;
                }
                Move m = rootMoves.get(i);
                b[m.row][m.col] = bot;
                long score = alphaBeta(b, targetDepth - 1, alpha, INF, false, bot, player, start);
                b[m.row][m.col] = EMPTY;
                if (System.nanoTime() - start > HARD_TIME_LIMIT_NS) {
                    timedOut = true;
                    break;
                }
                scores[i] = score;
                if (score > iterBest) {
                    iterBest = score;
                    iterMove = new int[]{m.row, m.col};
                }
                alpha = Math.max(alpha, score);
            }

            if (timedOut) {
                break;
            }
            if (iterMove != null) {
                best = iterMove;
                finalScores = scores;
            }
        }
        return pickNearBest(rootMoves, finalScores, best);
    }

    /**
     * 从最后一层「完整搜索完成」的结果里挑选落点。
     * <p>
     * 给每个候选的最终分加一个极小的随机扰动再取最大：扰动幅度（{@link #ROOT_JITTER}）
     * 远小于棋型档位差，因此强手不会被削弱，但同分 / 近似同分的多个好点会被随机化，
     * 原版「同一局面永远走同一手」的确定性被打破。
     */
    private static int[] pickNearBest(List<Move> rootMoves, long[] scores, int[] fallback) {
        if (scores == null || scores.length == 0) {
            return fallback;
        }
        long bestJittered = Long.MIN_VALUE;
        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            long jittered = scores[i] + RANDOM.nextInt(ROOT_JITTER);
            if (jittered > bestJittered) {
                bestJittered = jittered;
                candidates.clear();
                candidates.add(new int[]{rootMoves.get(i).row, rootMoves.get(i).col});
            } else if (jittered == bestJittered) {
                candidates.add(new int[]{rootMoves.get(i).row, rootMoves.get(i).col});
            }
        }
        return candidates.isEmpty()
                ? fallback
                : candidates.get(RANDOM.nextInt(candidates.size()));
    }

    private static long alphaBeta(int[][] b, int depth, long alpha, long beta,
                                  boolean maximizing, int bot, int player, long start) {
        if (System.nanoTime() - start > HARD_TIME_LIMIT_NS) {
            return 0;
        }
        if (depth == 0) {
            return evaluateBoardHard(b, bot, player);
        }
        List<Move> moves = generateHard(b, bot, player, depth, false);
        if (moves.isEmpty()) {
            return evaluateBoardHard(b, bot, player);
        }

        if (maximizing) {
            long best = -INF;
            for (Move m : moves) {
                b[m.row][m.col] = bot;
                long score;
                if (evaluatePosition(b, m.row, m.col, bot, HARD_SCORES, true, false)
                        >= HARD_SCORES[WIN]) {
                    score = INF / 2 + depth;
                } else {
                    score = alphaBeta(b, depth - 1, alpha, beta, false, bot, player, start);
                }
                b[m.row][m.col] = EMPTY;
                if (System.nanoTime() - start > HARD_TIME_LIMIT_NS) {
                    return 0;
                }
                best = Math.max(best, score);
                alpha = Math.max(alpha, score);
                if (beta <= alpha) {
                    break;
                }
            }
            return best;
        }

        long best = INF;
        for (Move m : moves) {
            b[m.row][m.col] = player;
            long score;
            if (evaluatePosition(b, m.row, m.col, player, HARD_SCORES, true, false)
                    >= HARD_SCORES[WIN]) {
                score = -INF / 2 - depth;
            } else {
                score = alphaBeta(b, depth - 1, alpha, beta, true, bot, player, start);
            }
            b[m.row][m.col] = EMPTY;
            if (System.nanoTime() - start > HARD_TIME_LIMIT_NS) {
                return 0;
            }
            best = Math.min(best, score);
            beta = Math.min(beta, score);
            if (beta <= alpha) {
                break;
            }
        }
        return best;
    }

    private static long evaluateBoardHard(int[][] b, int bot, int player) {
        long total = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] == bot) {
                    long score = evaluatePosition(b, r, c, bot, HARD_SCORES, true, true);
                    if (score >= HARD_SCORES[WIN]) {
                        return INF / 2;
                    }
                    total += score;
                } else if (b[r][c] == player) {
                    long score = evaluatePosition(b, r, c, player, HARD_SCORES, true, true);
                    if (score >= HARD_SCORES[WIN]) {
                        return -INF / 2;
                    }
                    total -= score * 3 / 2;
                }
            }
        }
        return total;
    }

    /** 开局应对：执白且盘面仅一子时，贴着对手落子。 */
    private static int[] openingMove(int[][] b, int bot) {
        if (bot != WHITE || countPieces(b) != 1) {
            return null;
        }
        int enemyRow = -1;
        int enemyCol = -1;
        outer:
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] != EMPTY) {
                    enemyRow = r;
                    enemyCol = c;
                    break outer;
                }
            }
        }
        if (enemyRow < 0) {
            return null;
        }
        int[][] offsets = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}, {-1, 0}, {0, -1}, {0, 1}, {1, 0}};
        List<int[]> valid = new ArrayList<>(offsets.length);
        for (int[] o : offsets) {
            int r = enemyRow + o[0];
            int c = enemyCol + o[1];
            if (inside(r, c) && b[r][c] == EMPTY) {
                valid.add(new int[]{r, c});
            }
        }
        return valid.isEmpty() ? null : valid.get(RANDOM.nextInt(valid.size()));
    }

    /** 找出能让指定一方立刻成五的落点。 */
    private static int[] immediateMove(int[][] b, int piece) {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] != EMPTY) {
                    continue;
                }
                b[r][c] = piece;
                long score = evaluatePosition(b, r, c, piece, HARD_SCORES, true, false);
                b[r][c] = EMPTY;
                if (score >= HARD_SCORES[WIN]) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    // ── 候选点生成 ──────────────────────────────────────────────────────────

    /** 生成候选点并按即时评分降序排列，截断到束宽。 */
    private static List<Move> generate(int[][] b, int bot, int player, long[] scores,
                                       boolean allowGap, int radius, int beam) {
        List<Move> moves = new ArrayList<>();
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] != EMPTY || !hasNeighbor(b, r, c, radius)) {
                    continue;
                }
                long attack = evaluatePosition(b, r, c, bot, scores, allowGap, false);
                long defense = evaluatePosition(b, r, c, player, scores, allowGap, false);
                moves.add(new Move(r, c, attack + defense));
            }
        }
        moves.sort((x, y) -> Long.compare(y.score, x.score));
        if (moves.size() > beam) {
            return new ArrayList<>(moves.subList(0, beam));
        }
        return moves;
    }

    /** 大师档候选点生成：搜索半径随棋子数收缩，根节点加强防守权重。 */
    private static List<Move> generateHard(int[][] b, int bot, int player, int depth, boolean rootDepth) {
        int pieceCount = countPieces(b);
        int radius = pieceCount <= 6 ? 3 : (pieceCount <= 20 ? 2 : 1);

        List<Move> moves = new ArrayList<>();
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] != EMPTY || !hasNeighbor(b, r, c, radius)) {
                    continue;
                }
                long attack = evaluatePosition(b, r, c, bot, HARD_SCORES, true, false);
                long defense = evaluatePosition(b, r, c, player, HARD_SCORES, true, false);
                long score = attack + defense;
                if (rootDepth && pieceCount <= 20) {
                    score = attack + defense * 2;
                }
                moves.add(new Move(r, c, score));
            }
        }
        moves.sort((x, y) -> Long.compare(y.score, x.score));

        int beam;
        if (depth >= 4) {
            beam = 15;
        } else if (depth >= 2) {
            beam = 20;
        } else {
            beam = 25;
        }
        if (moves.size() > beam) {
            return new ArrayList<>(moves.subList(0, beam));
        }
        return moves;
    }

    // ── 棋型评估 ────────────────────────────────────────────────────────────

    /**
     * 评估在 (row, col) 落子后、指定方向上的棋型总分。
     *
     * @param allowGap  是否允许“隔一子”的棋型（大师档开启）
     * @param boardEval 是否为全盘评估：跳过被前子覆盖的重复计数
     */
    private static long evaluatePosition(int[][] b, int row, int col, int piece,
                                         long[] scores, boolean allowGap, boolean boardEval) {
        long total = 0;
        for (int[] d : DIRS) {
            int dr = d[0];
            int dc = d[1];
            if (boardEval) {
                int prevRow = row - dr;
                int prevCol = col - dc;
                if (inside(prevRow, prevCol) && b[prevRow][prevCol] == piece) {
                    continue;
                }
                int prev2Row = row - 2 * dr;
                int prev2Col = col - 2 * dc;
                if (inside(prevRow, prevCol) && b[prevRow][prevCol] == EMPTY
                        && inside(prev2Row, prev2Col) && b[prev2Row][prev2Col] == piece) {
                    continue;
                }
            }
            LinePattern pattern = analyzeLine(b, row, col, piece, dr, dc, allowGap);
            if (pattern.type != NONE) {
                total += scores[pattern.type];
            }
        }
        return total;
    }

    private static LinePattern analyzeLine(int[][] b, int row, int col, int piece,
                                           int dr, int dc, boolean allowGap) {
        LinePattern pattern = new LinePattern();
        scanSide(b, row, col, piece, dr, dc, allowGap, pattern);
        scanSide(b, row, col, piece, -dr, -dc, allowGap, pattern);
        pattern.type = classify(pattern.count, pattern.blockedEnds, pattern.gaps);
        return pattern;
    }

    private static void scanSide(int[][] b, int row, int col, int piece,
                                 int dr, int dc, boolean allowGap, LinePattern pattern) {
        boolean usedGap = false;
        for (int step = 1; ; step++) {
            int nr = row + step * dr;
            int nc = col + step * dc;
            if (!inside(nr, nc)) {
                pattern.blockedEnds++;
                return;
            }
            if (b[nr][nc] == piece) {
                pattern.count++;
                continue;
            }
            if (b[nr][nc] != EMPTY) {
                pattern.blockedEnds++;
                return;
            }
            int afterRow = nr + dr;
            int afterCol = nc + dc;
            if (allowGap && !usedGap && inside(afterRow, afterCol) && b[afterRow][afterCol] == piece) {
                usedGap = true;
                pattern.gaps++;
                continue;
            }
            return;
        }
    }

    private static int classify(int count, int blocks, int gaps) {
        if (blocks >= 2) {
            return NONE;
        }
        if (gaps > 0) {
            if (count >= 5) {
                count = 4;
            }
        } else {
            if (count > 5) {
                return OVERLINE;
            }
            if (count == 5) {
                return WIN;
            }
        }
        if (blocks == 0) {
            switch (count) {
                case 4: return OPEN_4;
                case 3: return OPEN_3;
                case 2: return OPEN_2;
                case 1: return OPEN_1;
                default: return NONE;
            }
        }
        switch (count) {
            case 4: return BLOCKED_4;
            case 3: return BLOCKED_3;
            case 2: return BLOCKED_2;
            case 1: return BLOCKED_1;
            default: return NONE;
        }
    }

    // ── 工具方法 ────────────────────────────────────────────────────────────

    private static boolean inside(int r, int c) {
        return r >= 0 && r < SIZE && c >= 0 && c < SIZE;
    }

    private static boolean hasNeighbor(int[][] b, int row, int col, int radius) {
        for (int dr = -radius; dr <= radius; dr++) {
            for (int dc = -radius; dc <= radius; dc++) {
                int nr = row + dr;
                int nc = col + dc;
                if (inside(nr, nc) && b[nr][nc] != EMPTY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countPieces(int[][] b) {
        int count = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] != EMPTY) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int[] centerOrFirst(int[][] b) {
        int center = SIZE / 2;
        if (b[center][center] == EMPTY) {
            return new int[]{center, center};
        }
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] == EMPTY) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    // ── Swap2 开局决策 ──────────────────────────────────────────────────────

    /**
     * 机器人做出 Swap2 开局选择。开局盘面很稀疏，无法直接套用搜索，这里改用
     * 「双方每子平均棋型分」比较形势：明显偏黑就换黑、明显偏白就执白，势均则再落 2 子。
     *
     * @param board 棋盘副本
     * @param level 难度等级（1 入门 / 2 进阶 / 3 大师）
     * @param phase 阶段：2 = 假后手选择，4 = 假先手选择
     * @return 阶段 2：0=继续执白 / 1=交换执黑 / 2=落下 2 子；阶段 4：0=继续执黑 / 1=交换执白
     */
    public static int swapDecision(int[][] board, int level, int phase) {
        int options = phase == 2 ? 3 : 2;
        // 入门档高概率乱选、进阶档偶尔乱选，大师档几乎总按评估决策
        int randomChance = level == 1 ? 40 : (level == 2 ? 15 : 5);
        if (RANDOM.nextInt(100) < randomChance) {
            return RANDOM.nextInt(options);
        }
        long blackAvg = averagePatternScore(board, BLACK);
        long whiteAvg = averagePatternScore(board, WHITE);
        if (phase == 2) {
            if (blackAvg * 4 > whiteAvg * 5) {
                return 1; // 黑棋形势明显更好 → 交换执黑
            }
            if (whiteAvg * 4 > blackAvg * 5) {
                return 0; // 白棋更好 → 继续执白
            }
            return 2;     // 势均力敌 → 再落 2 子
        }
        return whiteAvg * 4 > blackAvg * 5 ? 1 : 0;
    }

    /** 某一方「每子平均棋型分」，用于在稀疏开局盘面上比较双方形势。 */
    private static long averagePatternScore(int[][] b, int piece) {
        long total = 0;
        int count = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (b[r][c] == piece) {
                    total += evaluatePosition(b, r, c, piece, HARD_SCORES, true, true);
                    count++;
                }
            }
        }
        return count == 0 ? 0 : total / count;
    }

    /** 一条线上的棋型统计。 */
    private static final class LinePattern {
        int count = 1;
        int blockedEnds;
        int gaps;
        int type = NONE;
    }

    /** 候选落点及其即时评分。 */
    private static final class Move {
        final int row;
        final int col;
        final long score;

        Move(int row, int col, long score) {
            this.row = row;
            this.col = col;
            this.score = score;
        }
    }
}