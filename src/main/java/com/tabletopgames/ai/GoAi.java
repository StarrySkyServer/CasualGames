package com.tabletopgames.ai;

import java.util.Arrays;
import java.util.Random;

/**
 * 围棋机器人：纯 Java 实现的 UCT（蒙特卡洛树搜索 / MCTS），不依赖任何外部神经网络权重。
 * <p>
 * 三档难度按「访问次数预算 + 探索常数 + 失误率」区分：
 * <ul>
 *     <li>1 入门：访问 20~80 次，探索常数偏大（噪声大），约 30% 概率随机落子，模拟新手的低级失误；</li>
 *     <li>2 进阶：访问 300~1000 次，中等探索与约 10% 失误，基础死活与局部对杀较为可靠；</li>
 *     <li>3 大师：访问 3000~8000 次，低探索与约 2% 失误，局部几乎不漏。</li>
 * </ul>
 * <b>时限</b>：单步搜索最长 {@link #MAX_MILLIS} 毫秒，到时立即改用当前已搜出的最优着法；
 * 为保证「机器人有思考感」，即使提前搜完也会补足到 {@link #MIN_MILLIS} 毫秒再返回。
 * 整个过程在异步线程中执行，不阻塞服务端主线程。
 * <p>
 * <b>评估</b>：模拟终局按中国规则数子（含贴目），并把「子数差」经 {@code tanh} 映射成 0~1 的连续评分，
 * 而非二值胜负 —— 贴目下随机模拟的结果常恒定偏向一方，二值信号会使 UCT 失去区分度。
 * 此外，随机模拟会把局部对杀的得失重新打散，纯统计的 MCTS 对「明显提子 / 救活」不敏感，
 * 故在根节点叠加一层 {@link #chooseTactic 战术判断}，按档位概率直接采用提子或救活着法：
 * 入门常看错对杀、大师几乎不漏。
 * <p>
 * <b>线程安全</b>：一次 {@link #bestMove} 调用内部新建一个 {@link Engine}，所有可变状态都在该对象内，
 * 因此多盘棋并发思考时互不干扰。
 */
public final class GoAi {

    /** 棋盘标记。 */
    private static final byte EMPTY = 0;
    private static final byte BLACK = 1;
    private static final byte WHITE = 2;

    /** 三档的访问次数下限 / 上限。 */
    private static final int[] MIN_VISITS = {20, 300, 3000};
    private static final int[] MAX_VISITS = {80, 1000, 8000};

    /** UCT 探索常数：入门档最大（更随机），大师档最小（更贪心）。 */
    private static final double[] EXPLORE = {1.6, 1.4, 1.2};

    /** 三档的「失误」概率：直接随机挑一手合法落点，模拟不同水平的偶发错着。 */
    private static final double[] BLUNDER = {0.30, 0.10, 0.02};

    /**
     * 三档采用「提子 / 救活」着法的概率：入门常看错对杀、大师几乎不漏。
     * 由于随机模拟会把局部结果重新打散，纯统计的 MCTS 对明显提子不敏感，
     * 因此在根节点叠加一层战术判断，并由该概率控制各档的战术可靠性。
     */
    private static final double[] TACTIC_PROB = {0.25, 0.75, 0.98};

    /** 触发「提子」所需的最少提子数（按档位递减，低档只吃大块）。 */
    private static final int[] MIN_CAP = {3, 1, 1};

    /** 触发「救活」所需的最少救活子数（低档不轻易救小块）。 */
    private static final int[] MIN_SAVE = {4, 2, 1};

    /** 单步搜索时间上限（毫秒）。 */
    private static final int MAX_MILLIS = 3000;

    /** 单步搜索时间下限（毫秒）：保证「机器人有思考感」。 */
    private static final int MIN_MILLIS = 1000;

    private static final Random RANDOM = new Random();

    /** 扩展节点纳入候选的邻域范围：仅考虑紧邻棋子的空点，控制分支数量。 */
    private static final int[][] DIRS8 = {
            {-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}
    };

    private GoAi() {
    }

    /**
     * 求一步最优着法。
     *
     * @param grid    棋盘快照（一维，长度 {@code n*n}），值为 0 空 / 1 黑 / 2 白
     * @param n       路数（9 / 13 / 19）
     * @param toMove  轮到行棋的一方（1 黑 / 2 白），也即机器人自身的棋色
     * @param level   难度：1 入门 / 2 进阶 / 3 大师（越界按 3 处理）
     * @param koPoint 打劫禁着点的下标（{@code -1} 表示无）
     * @param passes  进入本手前当前的连续停一手次数（0 / 1）
     * @param komi    白方贴目
     * @return {@code {行, 列}}；选择停一手（Pass）时返回 {@code null}
     */
    public static int[] bestMove(int[] grid, int n, int toMove, int level, int koPoint, int passes, double komi) {
        long start = System.currentTimeMillis();
        int lv = level < 1 ? 1 : Math.min(level, 3);
        int[] move = chooseTactic(grid, n, toMove, koPoint, passes, lv);
        if (move == null) {
            move = compute(grid, n, toMove, lv, koPoint, passes, komi, start + MAX_MILLIS);
        }
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

    /**
     * 根节点战术判断：按档位概率决定是否直接采用「提子」或「救活」着法。
     * <p>
     * 返回 {@code null} 表示本手未触发战术（交由 MCTS 决策）。
     * 提子优先于救活；两者都取收益（被提/被救子数）最大的着法。
     */
    private static int[] chooseTactic(int[] grid, int n, int toMove, int koPoint, int passes, int lv) {
        if (RANDOM.nextDouble() >= TACTIC_PROB[lv - 1]) {
            return null;
        }
        int minCap = MIN_CAP[lv - 1];
        int minSave = MIN_SAVE[lv - 1];
        Engine eng = new Engine(n, (byte) toMove, 0);
        byte[] b = new byte[n * n];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) grid[i];
        }
        int[] moves = eng.legalMoves(b, toMove, koPoint, passes);
        int capMove = -1;
        int cap = 0;
        int saveMove = -1;
        int save = 0;
        for (int mv : moves) {
            if (mv == eng.passIndex) {
                continue;
            }
            int c = eng.captureBonus(b, mv, toMove);
            if (c > cap) {
                cap = c;
                capMove = mv;
            }
            if (c == 0) {
                int s = eng.escapeBonus(b, mv, toMove);
                if (s > save) {
                    save = s;
                    saveMove = mv;
                }
            }
        }
        if (cap >= minCap && capMove >= 0) {
            return eng.toCoord(capMove);
        }
        if (save >= minSave && saveMove >= 0) {
            return eng.toCoord(saveMove);
        }
        return null;
    }

    private static int[] compute(int[] grid, int n, int toMove, int level, int koPoint, int passes,
                                 double komi, long deadline) {
        int lv = level < 1 ? 1 : Math.min(level, 3);
        int span = MAX_VISITS[lv - 1] - MIN_VISITS[lv - 1];
        int budget = MIN_VISITS[lv - 1] + (span <= 0 ? 0 : RANDOM.nextInt(span + 1));
        double c = EXPLORE[lv - 1];

        Engine eng = new Engine(n, (byte) toMove, komi);
        byte[] rootBoard = new byte[n * n];
        for (int i = 0; i < rootBoard.length; i++) {
            rootBoard[i] = (byte) grid[i];
        }
        Node root = new Node(rootBoard, toMove, -1, koPoint, passes, null);
        root.untried = eng.legalMoves(rootBoard, toMove, koPoint, passes);
        root.untriedCount = root.untried.length;
        if (root.untriedCount == 0) {
            return null;
        }
        shuffle(root.untried, root.untriedCount);

        int visits = 0;
        while (visits < budget && System.currentTimeMillis() < deadline) {
            Node node = root;
            // 1. 选择：已完全展开且非终局的节点，按 UCB1 一路向下
            while (node.untriedCount == 0 && !node.children.isEmpty()) {
                node = node.selectChild(c);
            }
            // 2. 扩展：取一个未试过的着法，展开成子节点
            if (node.untriedCount > 0) {
                int mv = node.untried[--node.untriedCount];
                Node child = eng.expand(node, mv);
                if (child != null) {
                    node.children.add(child);
                    node = child;
                }
            }
            // 3. 模拟：从该节点随机走到底，按中国规则数子评估形势
            double reward = eng.playout(node.board, node.toMove, node.koPoint, node.passes);
            // 4. 回溯：把评估回传，评价值以「走入该节点的一方」视角累计
            Node cur = node;
            while (cur != null) {
                cur.visits++;
                cur.wins += other(cur.toMove) == toMove ? reward : 1.0 - reward;
                cur = cur.parent;
            }
            visits++;
        }
        return pickMove(eng, root, lv);
    }

    /** 从根节点挑选落点：多数访问 + 按档位注入随机噪声。 */
    private static int[] pickMove(Engine eng, Node root, int level) {
        if (root.children.isEmpty()) {
            return null;
        }
        // 低级失误：直接随机挑一个合法非停一手的落点
        if (RANDOM.nextDouble() < BLUNDER[level - 1]) {
            int[] legal = eng.legalMoves(root.board, root.toMove, root.koPoint, root.passes);
            int count = 0;
            for (int mv : legal) {
                if (mv != eng.passIndex) {
                    count++;
                }
            }
            if (count > 0) {
                int pick = RANDOM.nextInt(count);
                for (int mv : legal) {
                    if (mv == eng.passIndex) {
                        continue;
                    }
                    if (pick-- == 0) {
                        return mv == eng.passIndex ? null : eng.toCoord(mv);
                    }
                }
            }
        }
        // 稳健着法：访问次数最多的子节点；并列时随机取其一，避免固定套路
        int bestVisits = -1;
        int ties = 0;
        Node best = null;
        for (Node child : root.children) {
            if (child.visits > bestVisits) {
                bestVisits = child.visits;
                best = child;
                ties = 1;
            } else if (child.visits == bestVisits && RANDOM.nextInt(++ties) == 0) {
                best = child;
            }
        }
        if (best == null || best.move == eng.passIndex) {
            return null;
        }
        return eng.toCoord(best.move);
    }

    private static void shuffle(int[] array, int length) {
        for (int i = length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            int tmp = array[i];
            array[i] = array[j];
            array[j] = tmp;
        }
    }

    private static int other(int color) {
        return color == BLACK ? WHITE : BLACK;
    }

    /** 搜索树节点：持有该节点对应的盘面快照与统计量。 */
    private static final class Node {
        final byte[] board;
        /** 轮到行棋的一方。 */
        final int toMove;
        /** 走入本节点的着法（下标或停一手），根节点为 -1。 */
        final int move;
        /** 走入本节点后的打劫禁着点（-1 表示无）。 */
        final int koPoint;
        /** 走入本节点后的连续停一手次数。 */
        final int passes;
        final Node parent;

        final java.util.ArrayList<Node> children = new java.util.ArrayList<>();

        int[] untried;
        int untriedCount;
        double wins;
        int visits;

        Node(byte[] board, int toMove, int move, int koPoint, int passes, Node parent) {
            this.board = board;
            this.toMove = toMove;
            this.move = move;
            this.koPoint = koPoint;
            this.passes = passes;
            this.parent = parent;
        }

        /** UCB1 选择：利用（胜率）+ 探索。 */
        Node selectChild(double c) {
            double logParent = Math.log(visits + 1);
            Node best = null;
            double bestValue = Double.NEGATIVE_INFINITY;
            for (Node child : children) {
                double value;
                if (child.visits == 0) {
                    value = Double.POSITIVE_INFINITY;
                } else {
                    value = child.wins / child.visits + c * Math.sqrt(logParent / child.visits);
                }
                if (value > bestValue) {
                    bestValue = value;
                    best = child;
                }
            }
            return best == null ? this : best;
        }
    }

    /**
     * 单次搜索使用的棋盘引擎：封装邻居表、盘面副本与规则计算所需的临时缓冲。
     * 所有可变状态都在本对象内，保证并发安全。
     */
    private static final class Engine {
        final int n;
        final int size;
        final int passIndex;
        final int aiColor;
        final double komi;

        /** 每个交点的相邻交点下标（十字方向）。 */
        final int[][] nb;

        final int[] stack;
        final int[] visited;
        int stamp;

        /** 数气用的去重缓冲（与 {@link #visited} 分开，避免互相覆盖）。 */
        final int[] libVisited;
        int libStamp;

        final int[] regionStack;
        final int[] regionVisited;
        int regionStamp;

        /** 候选落点列表（用于随机模拟），配合 {@code inCand} 去重。 */
        final int[] cand;
        final boolean[] inCand;
        int candCount;

        /** 最近一次 {@link #playInPlace} 产生的打劫禁着点（-1 表示无）。 */
        int lastKo;

        Engine(int n, byte aiColor, double komi) {
            this.n = n;
            this.size = n * n;
            this.passIndex = size;
            this.aiColor = aiColor;
            this.komi = komi;
            this.nb = buildNeighbors(n);
            this.stack = new int[size];
            this.visited = new int[size];
            this.libVisited = new int[size];
            this.regionStack = new int[size];
            this.regionVisited = new int[size];
            this.cand = new int[size];
            this.inCand = new boolean[size];
        }

        private static int[][] buildNeighbors(int n) {
            int size = n * n;
            int[][] table = new int[size][];
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    int idx = r * n + c;
                    int count = 0;
                    for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                        int nr = r + d[0];
                        int nc = c + d[1];
                        if (nr >= 0 && nr < n && nc >= 0 && nc < n) {
                            count++;
                        }
                    }
                    int[] out = new int[count];
                    int k = 0;
                    if (r > 0) {
                        out[k++] = idx - n;
                    }
                    if (r < n - 1) {
                        out[k++] = idx + n;
                    }
                    if (c > 0) {
                        out[k++] = idx - 1;
                    }
                    if (c < n - 1) {
                        out[k++] = idx + 1;
                    }
                    table[idx] = out;
                }
            }
            return table;
        }

        int[] toCoord(int move) {
            return new int[]{move / n, move % n};
        }

        /**
         * 生成候选着法（贴近棋子的空点 + 可选的停一手）。
         * <p>
         * 停一手只在以下情形纳入候选，避免机器人在开局就无谓地弃权：
         * 已无其它合法着法、对手刚停过一手（{@code passes >= 1}，此时收手可结束对局）、
         * 或盘面已过半（{@code 棋子数 * 2 >= 交点总数}，进入收官阶段）。
         */
        int[] legalMoves(byte[] b, int color, int koPoint, int passes) {
            int[] buf = new int[size + 1];
            int count = 0;
            int stones = 0;
            for (int i = 0; i < size; i++) {
                if (b[i] != EMPTY) {
                    stones++;
                }
            }
            if (stones == 0) {
                buf[count++] = (n / 2) * n + (n / 2);
            } else {
                for (int i = 0; i < size; i++) {
                    if (b[i] != EMPTY || !hasStoneNeighbor(b, i)) {
                        continue;
                    }
                    if (isLegalMove(b, i, color, koPoint)) {
                        buf[count++] = i;
                    }
                }
            }
            if (count == 0 || passes >= 1 || stones * 2 >= size) {
                buf[count++] = passIndex;
            }
            return Arrays.copyOf(buf, count);
        }

        private boolean hasStoneNeighbor(byte[] b, int idx) {
            int r = idx / n;
            int c = idx % n;
            for (int[] d : DIRS8) {
                int nr = r + d[0];
                int nc = c + d[1];
                if (nr >= 0 && nr < n && nc >= 0 && nc < n && b[nr * n + nc] != EMPTY) {
                    return true;
                }
            }
            return false;
        }

        /** 该落点是否合法（不自杀、不违反单劫）。调用前后盘面不变。 */
        private boolean isLegalMove(byte[] b, int idx, int color, int koPoint) {
            if (b[idx] != EMPTY || idx == koPoint) {
                return false;
            }
            b[idx] = (byte) color;
            boolean captures = false;
            int opp = other(color);
            for (int r : nb[idx]) {
                if (b[r] == opp && !hasLiberty(b, r)) {
                    captures = true;
                    break;
                }
            }
            boolean legal = captures || hasLiberty(b, idx);
            b[idx] = EMPTY;
            return legal;
        }

        /** {@code idx} 所在同色棋组是否还有气。 */
        private boolean hasLiberty(byte[] b, int idx) {
            byte color = b[idx];
            stamp++;
            int top = 0;
            stack[top++] = idx;
            visited[idx] = stamp;
            while (top > 0) {
                int q = stack[--top];
                for (int r : nb[q]) {
                    byte v = b[r];
                    if (v == EMPTY) {
                        return true;
                    }
                    if (v == color && visited[r] != stamp) {
                        visited[r] = stamp;
                        stack[top++] = r;
                    }
                }
            }
            return false;
        }

        /** 提走 {@code idx} 所在棋组并返回其棋子数（调用前须确认其无气）。 */
        private int captureGroup(byte[] b, int idx, byte color) {
            stamp++;
            int top = 0;
            stack[top++] = idx;
            visited[idx] = stamp;
            int size = 0;
            while (top > 0) {
                int q = stack[--top];
                b[q] = EMPTY;
                size++;
                for (int r : nb[q]) {
                    if (b[r] == color && visited[r] != stamp) {
                        visited[r] = stamp;
                        stack[top++] = r;
                    }
                }
            }
            return size;
        }

        /** {@code idx} 所在同色棋组的棋子数。 */
        private int groupSize(byte[] b, int idx, byte color) {
            stamp++;
            int top = 0;
            stack[top++] = idx;
            visited[idx] = stamp;
            int size = 0;
            while (top > 0) {
                int q = stack[--top];
                size++;
                for (int r : nb[q]) {
                    if (b[r] == color && visited[r] != stamp) {
                        visited[r] = stamp;
                        stack[top++] = r;
                    }
                }
            }
            return size;
        }

        /** 在 {@code parent} 上展开着法 {@code mv}；非法着法返回 {@code null}。 */
        Node expand(Node parent, int mv) {
            byte[] childBoard = parent.board.clone();
            int childKo;
            int childPasses;
            if (mv == passIndex) {
                childKo = -1;
                childPasses = parent.passes + 1;
            } else {
                if (!playInPlace(childBoard, mv, parent.toMove, parent.koPoint)) {
                    return null;
                }
                childKo = lastKo;
                childPasses = 0;
            }
            Node child = new Node(childBoard, other(parent.toMove), mv, childKo, childPasses, parent);
            if (childPasses < 2) {
                child.untried = legalMoves(childBoard, child.toMove, childKo, childPasses);
                child.untriedCount = child.untried.length;
                shuffle(child.untried, child.untriedCount);
            }
            return child;
        }

        /**
         * 就地落子：提子、判定禁着点与单劫。
         *
         * @return 是否合法；合法时 {@link #lastKo} 为新的打劫禁着点
         */
        private boolean playInPlace(byte[] b, int idx, int color, int koPoint) {
            if (b[idx] != EMPTY || idx == koPoint) {
                return false;
            }
            b[idx] = (byte) color;
            int opp = other(color);
            int capCount = 0;
            int capPos = -1;
            boolean single = false;
            for (int r : nb[idx]) {
                if (b[r] == opp && !hasLiberty(b, r)) {
                    int captured = captureGroup(b, r, (byte) opp);
                    capCount += captured;
                    if (captured == 1) {
                        single = true;
                        capPos = r;
                    }
                }
            }
            if (capCount == 0 && !hasLiberty(b, idx)) {
                b[idx] = EMPTY;
                return false;
            }
            lastKo = -1;
            if (single && groupSize(b, idx, (byte) color) == 1) {
                lastKo = capPos;
            }
            return true;
        }

        /**
         * 随机模拟到终局并返回「以机器人视角的评分奖励」（0~1 的连续值）。
         * <p>
         * 采用连续奖励而非二值胜负：贴目 7.5 目下，随机 rollout 的结果往往恒定偏向一方，
         * 若用二值胜负，所有着法的期望取胜值会趋同、UCT 失去区分度。改用
         * {@code 0.5 + 0.5 * tanh(margin / SCALE)} 后，局势差距的大小也能进入搜索信号。
         */
        double playout(byte[] board, int toMove, int koPoint, int passes) {
            byte[] b = board.clone();
            if (passes >= 2) {
                return winsFor(b);
            }
            int color = toMove;
            int ko = koPoint;
            int passCount = passes;
            seedCandidates(b);
            if (candCount == 0) {
                addCandidate((n / 2) * n + (n / 2));
            }
            int moves = 0;
            int cap = size * 2;
            while (passCount < 2 && moves < cap) {
                int chosen = selectPlayoutMove(b, color, ko);
                if (chosen < 0) {
                    passCount++;
                    ko = -1;
                    color = other(color);
                    continue;
                }
                passCount = 0;
                moves++;
                ko = lastKo;
                addCandidatesAround(b, chosen);
                color = other(color);
            }
            return winsFor(b);
        }

        /**
         * 模拟阶段的选点：在候选点里随机抽样，优先选择「提吃对方（尤其打吃）」或「救活己方」的点，
         * 其余情况保持随机。这样能让浅层模拟也体现出基本的死活与对杀直觉。
         *
         * @return 选中的交点下标；{@code -1} 表示停一手
         */
        private int selectPlayoutMove(byte[] b, int color, int ko) {
            if (candCount == 0) {
                return -1;
            }
            int best = -1;
            int bestScore = Integer.MIN_VALUE;
            int attempts = 0;
            while (attempts++ < 12 && candCount > 0) {
                int k = RANDOM.nextInt(candCount);
                int idx = cand[k];
                if (b[idx] != EMPTY) {
                    removeCandidate(k);
                    continue;
                }
                if (idx == ko || !isLegalMove(b, idx, color, ko)) {
                    continue;
                }
                int score = captureBonus(b, idx, color) * 12
                        + escapeBonus(b, idx, color) * 4
                        + RANDOM.nextInt(4);
                if (score > bestScore) {
                    bestScore = score;
                    best = idx;
                }
                if (bestScore >= 48) {
                    break;
                }
            }
            if (best >= 0 && playInPlace(b, best, color, ko)) {
                return best;
            }
            return -1;
        }

        /** 落在 {@code idx} 可提走的对方棋子总数（对方棋组仅剩这一口气时）。 */
        private int captureBonus(byte[] b, int idx, int color) {
            int opp = other(color);
            int total = 0;
            for (int r : nb[idx]) {
                if (b[r] == opp && libertyCount(b, r) == 1) {
                    total += groupSize(b, r, (byte) opp);
                }
            }
            return total;
        }

        /** 落在 {@code idx} 可救活的己方棋子总数（己方棋组仅剩这一口气时）。 */
        private int escapeBonus(byte[] b, int idx, int color) {
            int total = 0;
            for (int r : nb[idx]) {
                if (b[r] == color && libertyCount(b, r) == 1) {
                    total += groupSize(b, r, (byte) color);
                }
            }
            return total;
        }

        /** {@code idx} 所在同色棋组的气数（只数到 2 即提前返回，用于快速判断是否打吃）。 */
        private int libertyCount(byte[] b, int idx) {
            byte color = b[idx];
            stamp++;
            int top = 0;
            stack[top++] = idx;
            visited[idx] = stamp;
            int count = 0;
            libStamp++;
            while (top > 0) {
                int q = stack[--top];
                for (int r : nb[q]) {
                    byte v = b[r];
                    if (v == EMPTY) {
                        if (libVisited[r] != libStamp) {
                            libVisited[r] = libStamp;
                            if (++count >= 2) {
                                return count;
                            }
                        }
                    } else if (v == color && visited[r] != stamp) {
                        visited[r] = stamp;
                        stack[top++] = r;
                    }
                }
            }
            return count;
        }

        /** 以现有棋子邻域初始化候选落点。 */
        private void seedCandidates(byte[] b) {
            candCount = 0;
            Arrays.fill(inCand, false);
            for (int i = 0; i < size; i++) {
                if (b[i] != EMPTY) {
                    addCandidatesAround(b, i);
                }
            }
        }

        /** 落子（或提子）后把其周围的空点补进候选列表。 */
        private void addCandidatesAround(byte[] b, int idx) {
            for (int r : nb[idx]) {
                if (b[r] == EMPTY && !inCand[r]) {
                    addCandidate(r);
                }
            }
        }

        private void addCandidate(int idx) {
            if (!inCand[idx] && candCount < cand.length) {
                inCand[idx] = true;
                cand[candCount++] = idx;
            }
        }

        private void removeCandidate(int k) {
            inCand[cand[k]] = false;
            cand[k] = cand[--candCount];
        }

        /**
         * 中国规则数子（棋子数 + 单色围空 + 白方贴目），把形势差映射成「机器人视角」的 0~1 连续评分。
         * <p>
         * {@code margin} 为机器人一方的子数优势（含贴目），{@code SCALE} 随棋盘增大而增大，
         * 保证 9 / 13 / 19 路下 tanh 的斜率大致相当（约 6~11 子对应明显的分差信号）。
         */
        private double winsFor(byte[] b) {
            int black = 0;
            int white = 0;
            for (int i = 0; i < size; i++) {
                byte v = b[i];
                if (v == BLACK) {
                    black++;
                } else if (v == WHITE) {
                    white++;
                }
            }
            regionStamp++;
            for (int i = 0; i < size; i++) {
                if (b[i] != EMPTY || regionVisited[i] == regionStamp) {
                    continue;
                }
                int top = 0;
                regionStack[top++] = i;
                regionVisited[i] = regionStamp;
                int count = 0;
                int border = 0;
                while (top > 0) {
                    int q = regionStack[--top];
                    count++;
                    for (int r : nb[q]) {
                        byte v = b[r];
                        if (v == EMPTY) {
                            if (regionVisited[r] != regionStamp) {
                                regionVisited[r] = regionStamp;
                                regionStack[top++] = r;
                            }
                        } else if (v == BLACK) {
                            border |= 1;
                        } else {
                            border |= 2;
                        }
                    }
                }
                if (border == 1) {
                    black += count;
                } else if (border == 2) {
                    white += count;
                }
            }
            double margin = aiColor == BLACK ? (black - (white + komi)) : ((white + komi) - black);
            double scale = Math.max(6.0, n);
            return 0.5 + 0.5 * Math.tanh(margin / scale);
        }
    }
}
