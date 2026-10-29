package com.tabletopgames.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 军旗（二国陆战棋）规则引擎：**不依赖 Nukkit**，可脱离服务端跑回归。
 *
 * <p>棋盘 5 列 × 12 行 = 60 个点（{@code cell = row * 5 + col + 1}，行 0 在棋盘远端）。
 * 红方半场 = 行 6..11（cell 31..60），黑方 = 行 0..5（cell 1..30）。</p>
 *
 * <p>棋子编码：{@code code = side * 16 + type}，{@code -1} 表示空格。这样一张 {@code int[60]}
 * 就能表达整盘（谁的子 + 什么子），存档/机器人快照都直接用。</p>
 *
 * <p>点位属性（与贴图/几何逐位一致，见 {@code 文档/军旗/军旗实现方案.md} §2.3）：
 * 行营 10 个、大本营 4 个、铁路 39 段、行营斜线 32 条。</p>
 */
public final class JunqiRules {

    public static final int COLS = 5;
    public static final int ROWS = 12;
    public static final int CELLS = COLS * ROWS;

    public static final int EMPTY = -1;

    // ---------------------------------------------------------------- 棋子类型
    public static final int MARSHAL = 0;
    public static final int GENERAL = 1;
    public static final int MAJOR_GENERAL = 2;
    public static final int BRIGADIER = 3;
    public static final int COLONEL = 4;
    public static final int MAJOR = 5;
    public static final int CAPTAIN = 6;
    public static final int LIEUTENANT = 7;
    public static final int ENGINEER = 8;
    public static final int BOMB = 9;
    public static final int MINE = 10;
    public static final int FLAG = 11;

    public static final String[] TYPE_KEYS = {
            "marshal", "general", "major_general", "brigadier", "colonel", "major",
            "captain", "lieutenant", "engineer", "bomb", "mine", "flag"};

    public static final String[] TYPE_CN = {
            "司令", "军长", "师长", "旅长", "团长", "营长", "连长", "排长", "工兵", "炸弹", "地雷", "军旗"};

    /** 战斗力：司令 9 … 工兵 1；炸弹/地雷/军旗为 0（走特殊规则）。 */
    public static final int[] RANK = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 0, 0};

    /**
     * 每方 25 枚的类型顺序 —— **槽号就是这里的下标**（红 slot 0..24，黑 25..49），
     * 必须与资源包生成脚本的 {@code SLOT_TYPE} 逐位一致。
     */
    public static final int[] SIDE_TYPES = {
            MARSHAL, GENERAL, MAJOR_GENERAL, MAJOR_GENERAL, BRIGADIER, BRIGADIER,
            COLONEL, COLONEL, MAJOR, MAJOR, CAPTAIN, CAPTAIN, CAPTAIN,
            LIEUTENANT, LIEUTENANT, LIEUTENANT, ENGINEER, ENGINEER, ENGINEER,
            BOMB, BOMB, MINE, MINE, MINE, FLAG};

    public static final int SIDE_RED = 0;
    public static final int SIDE_BLACK = 1;

    // ---------------------------------------------------------------- 编码
    public static int code(int side, int type) {
        return (side << 4) | type;
    }

    public static int sideOf(int code) {
        return code >> 4;
    }

    public static int typeOf(int code) {
        return code & 0x0F;
    }

    // ---------------------------------------------------------------- 点位
    public static int cell(int row, int col) {
        return row * COLS + col + 1;
    }

    public static int rowOf(int cell) {
        return (cell - 1) / COLS;
    }

    public static int colOf(int cell) {
        return (cell - 1) % COLS;
    }

    /** 行营（圆圈）：每方 5 个，进营不可被吃。 */
    public static final int[] CAMPS = {12, 14, 18, 22, 24, 37, 39, 43, 47, 49};
    /** 大本营（旗形）：军旗必须放在其中之一；进入后不可再移动。 */
    public static final int[] HQS = {2, 4, 57, 59};

    public static boolean isCamp(int cell) {
        for (int c : CAMPS) {
            if (c == cell) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHq(int cell) {
        for (int c : HQS) {
            if (c == cell) {
                return true;
            }
        }
        return false;
    }

    /** 铁路点：横排 1/5/6/10 整行、竖列 0/4 整列，加中线 28↔33 两格。 */
    public static boolean isRail(int cell) {
        int row = rowOf(cell);
        int col = colOf(cell);
        return row == 1 || row == 5 || row == 6 || row == 10 || col == 0 || col == 4;
    }

    /** 是否属于自己的半场（红 6..11 / 黑 0..5）。 */
    public static boolean ownHalf(int cell, int side) {
        int row = rowOf(cell);
        return side == SIDE_RED ? row >= 6 : row <= 5;
    }

    // ---------------------------------------------------------------- 邻接表
    /** 普通步邻接（上下左右 + 行营斜线）。 */
    private static final int[][] NEI = new int[CELLS + 1][];
    /** 铁路相邻（只沿铁路段；中线 28-33 单独加）。 */
    private static final int[][] RAIL_NEI = new int[CELLS + 1][];
    /** 反向索引：cell -> 在 CAMPS/HQS 里的判定用位图。 */
    private static final boolean[] CAMP_MASK = new boolean[CELLS + 1];
    private static final boolean[] HQ_MASK = new boolean[CELLS + 1];
    private static final boolean[] RAIL_MASK = new boolean[CELLS + 1];

    static {
        for (int c : CAMPS) {
            CAMP_MASK[c] = true;
        }
        for (int c : HQS) {
            HQ_MASK[c] = true;
        }
        for (int cell = 1; cell <= CELLS; cell++) {
            RAIL_MASK[cell] = isRail(cell);
        }
        for (int cell = 1; cell <= CELLS; cell++) {
            int row = rowOf(cell);
            int col = colOf(cell);
            List<Integer> nei = new ArrayList<>(8);
            int[][] orth = {{row - 1, col}, {row + 1, col}, {row, col - 1}, {row, col + 1}};
            for (int[] rc : orth) {
                if (inside(rc[0], rc[1])) {
                    nei.add(cell(rc[0], rc[1]));
                }
            }
            // 斜线只连行营：任一端是行营才可走（棋盘上只画了行营的斜线）
            for (int dr = -1; dr <= 1; dr += 2) {
                for (int dc = -1; dc <= 1; dc += 2) {
                    int rr = row + dr;
                    int cc = col + dc;
                    if (!inside(rr, cc)) {
                        continue;
                    }
                    int to = cell(rr, cc);
                    if (CAMP_MASK[cell] || CAMP_MASK[to]) {
                        nei.add(to);
                    }
                }
            }
            NEI[cell] = toArray(nei);
        }
        // 铁路段：横排整行、竖列 0/4 整列 + 中线 28↔33
        for (int cell = 1; cell <= CELLS; cell++) {
            int row = rowOf(cell);
            int col = colOf(cell);
            List<Integer> nei = new ArrayList<>(4);
            if (row == 1 || row == 5 || row == 6 || row == 10) {
                if (col > 0) {
                    nei.add(cell(row, col - 1));
                }
                if (col < COLS - 1) {
                    nei.add(cell(row, col + 1));
                }
            }
            if (col == 0 || col == 4) {
                if (row > 0) {
                    nei.add(cell(row - 1, col));
                }
                if (row < ROWS - 1) {
                    nei.add(cell(row + 1, col));
                }
            }
            if (cell == 28) {
                nei.add(33);
            }
            if (cell == 33) {
                nei.add(28);
            }
            RAIL_NEI[cell] = toArray(nei);
        }
    }

    private static boolean inside(int row, int col) {
        return row >= 0 && row < ROWS && col >= 0 && col < COLS;
    }

    private static int[] toArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    public static int[] neighbours(int cell) {
        return NEI[cell];
    }

    public static int[] railNeighbours(int cell) {
        return RAIL_NEI[cell];
    }

    // ---------------------------------------------------------------- 摆棋限制
    /**
     * 摆棋时某类型能否放在某格。
     *
     * <ul>
     *     <li>只能放自己半场、且不能是行营；</li>
     *     <li>军旗：必须放两个大本营之一；</li>
     *     <li>地雷：只能放己方后两行（红 10/11、黑 0/1）；</li>
     *     <li>炸弹：不能放己方第一排（红 6、黑 5）。</li>
     * </ul>
     */
    public static boolean canPlace(int type, int cell, int side) {
        if (cell < 1 || cell > CELLS || !ownHalf(cell, side) || isCamp(cell)) {
            return false;
        }
        int row = rowOf(cell);
        switch (type) {
            case FLAG:
                return isHq(cell);
            case MINE:
                return side == SIDE_RED ? (row == 10 || row == 11) : (row == 0 || row == 1);
            case BOMB:
                return side == SIDE_RED ? row != 6 : row != 5;
            default:
                return true;
        }
    }

    /** 该棋子在当前格能否移动（地雷/军旗不动，大本营内的子不能动）。 */
    public static boolean canMoveFrom(int[] grid, int from) {
        int code = grid[from - 1];
        if (code == EMPTY) {
            return false;
        }
        int type = typeOf(code);
        return type != MINE && type != FLAG && !isHq(from);
    }

    // ---------------------------------------------------------------- 走法
    /**
     * 生成 {@code from} 的全部落点（含可吃子的敌方格），写入 {@code out}。
     *
     * @return 个数；{@code out} 不够大时会被截断（调用方给 {@code CELLS} 就够）
     */
    public static int generate(int[] grid, int from, int[] out) {
        if (from < 1 || from > CELLS || !canMoveFrom(grid, from)) {
            return 0;
        }
        int code = grid[from - 1];
        int side = sideOf(code);
        int type = typeOf(code);
        boolean[] seen = new boolean[CELLS + 1];
        int n = 0;

        // ① 普通一步（含行营斜线）
        for (int to : NEI[from]) {
            int target = grid[to - 1];
            if (target == EMPTY) {
                if (!seen[to]) {
                    seen[to] = true;
                    out[n++] = to;
                }
            } else if (sideOf(target) != side && !CAMP_MASK[to]) {
                // 行营里的子是「免伤」的，不能吃（也进不去，因为行营只能 1 枚）
                if (!seen[to]) {
                    seen[to] = true;
                    out[n++] = to;
                }
            }
        }
        if (!RAIL_MASK[from]) {
            return n;
        }

        // ② 铁路：非工兵 = 四个方向直行；工兵 = 铁路网上任意拐弯
        if (type == ENGINEER) {
            boolean[] visited = new boolean[CELLS + 1];
            int[] queue = new int[CELLS + 1];
            int head = 0;
            int tail = 0;
            queue[tail++] = from;
            visited[from] = true;
            while (head < tail) {
                int cur = queue[head++];
                for (int next : RAIL_NEI[cur]) {
                    if (visited[next]) {
                        continue;
                    }
                    visited[next] = true;
                    int target = grid[next - 1];
                    if (target == EMPTY) {
                        if (!seen[next]) {
                            seen[next] = true;
                            out[n++] = next;
                        }
                        queue[tail++] = next;
                    } else if (sideOf(target) != side && !CAMP_MASK[next] && !seen[next]) {
                        seen[next] = true;
                        out[n++] = next;      // 吃子后不能继续走
                    }
                }
            }
        } else {
            // 非工兵：只沿 4 个正方向直行（到路口也不能拐弯，这是「铁路直行」的关键）
            int row = rowOf(from);
            int col = colOf(from);
            int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] dir : dirs) {
                int r = row;
                int c = col;
                while (true) {
                    int nr = r + dir[0];
                    int nc = c + dir[1];
                    if (!inside(nr, nc)) {
                        break;
                    }
                    int next = cell(nr, nc);
                    if (!railAdjacent(cell(r, c), next)) {
                        break;
                    }
                    int target = grid[next - 1];
                    if (target != EMPTY) {
                        if (sideOf(target) != side && !CAMP_MASK[next] && !seen[next]) {
                            seen[next] = true;
                            out[n++] = next;
                        }
                        break;
                    }
                    if (!seen[next]) {
                        seen[next] = true;
                        out[n++] = next;
                    }
                    r = nr;
                    c = nc;
                }
            }
        }
        return n;
    }

    /** 两点之间是否是「一段铁路」（横/竖铁路行、col 0/4、或 28↔33 中线）。 */
    public static boolean railAdjacent(int a, int b) {
        int ra = rowOf(a);
        int ca = colOf(a);
        int rb = rowOf(b);
        int cb = colOf(b);
        if (ra == rb && Math.abs(ca - cb) == 1) {
            return ra == 1 || ra == 5 || ra == 6 || ra == 10;
        }
        if (ca == cb && Math.abs(ra - rb) == 1) {
            return ca == 0 || ca == 4;
        }
        return (a == 28 && b == 33) || (a == 33 && b == 28);
    }

    /** {@code from -> to} 是否是合法着法（含吃子）。 */
    public static boolean isLegal(int[] grid, int from, int to) {
        if (from < 1 || to < 1 || from > CELLS || to > CELLS || from == to) {
            return false;
        }
        int[] buf = new int[CELLS];
        int n = generate(grid, from, buf);
        for (int i = 0; i < n; i++) {
            if (buf[i] == to) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 战斗
    public static final int FIGHT_WIN = 0;
    public static final int FIGHT_LOSE = 1;
    public static final int FIGHT_BOTH = 2;
    public static final int FIGHT_FLAG = 3;

    /**
     * 战斗结算（不含行营免伤，调用方已保证 defender 不在行营）。
     *
     * @return {@link #FIGHT_WIN}（攻方进占）、{@link #FIGHT_LOSE}（攻方消失）、
     * {@link #FIGHT_BOTH}（同归于尽）、{@link #FIGHT_FLAG}（破旗，攻方胜且终局）
     */
    public static int fight(int attacker, int defender) {
        if (defender == FLAG) {
            return FIGHT_FLAG;
        }
        if (attacker == BOMB || defender == BOMB) {
            return FIGHT_BOTH;
        }
        if (defender == MINE) {
            return attacker == ENGINEER ? FIGHT_WIN : FIGHT_LOSE;
        }
        int a = RANK[attacker];
        int d = RANK[defender];
        if (a > d) {
            return FIGHT_WIN;
        }
        if (a == d) {
            return FIGHT_BOTH;
        }
        return FIGHT_LOSE;
    }

    // ---------------------------------------------------------------- 随机布阵
    /**
     * 随机生成一方的布阵。
     *
     * @return {@code int[25]}，下标 = 槽号（{@link #SIDE_TYPES}），值 = 点位
     */
    public static int[] randomLayout(int side, Random random) {
        List<Integer> free = new ArrayList<>(25);
        for (int row = 0; row < ROWS; row++) {
            if (side == SIDE_RED ? row < 6 : row > 5) {
                continue;
            }
            for (int col = 0; col < COLS; col++) {
                int cell = cell(row, col);
                if (!isCamp(cell)) {
                    free.add(cell);
                }
            }
        }
        int[] slots = new int[SIDE_TYPES.length];
        Arrays.fill(slots, 0);

        // 军旗：两个大本营里随机一个
        List<Integer> hqs = new ArrayList<>(2);
        for (int hq : HQS) {
            if (ownHalf(hq, side)) {
                hqs.add(hq);
            }
        }
        int flagCell = hqs.get(random.nextInt(hqs.size()));
        assign(slots, FLAG, flagCell);
        free.remove(Integer.valueOf(flagCell));

        // 地雷：后两行
        placeRandom(slots, MINE, 3, free, side, random);
        // 炸弹：非第一排
        placeRandom(slots, BOMB, 2, free, side, random);

        // 其余：洗牌后填满
        List<Integer> rest = new ArrayList<>(free);
        java.util.Collections.shuffle(rest, random);
        int cursor = 0;
        for (int slot = 0; slot < SIDE_TYPES.length; slot++) {
            int type = SIDE_TYPES[slot];
            if (type == FLAG || type == MINE || type == BOMB) {
                continue;
            }
            slots[slot] = rest.get(cursor++);
        }
        return slots;
    }

    private static void placeRandom(int[] slots, int type, int count, List<Integer> free,
                                    int side, Random random) {
        List<Integer> candidates = new ArrayList<>();
        for (int c : free) {
            if (canPlace(type, c, side)) {
                candidates.add(c);
            }
        }
        for (int i = 0; i < count && !candidates.isEmpty(); i++) {
            int pick = candidates.remove(random.nextInt(candidates.size()));
            assign(slots, type, pick);
            free.remove(Integer.valueOf(pick));
        }
    }

    /** 把某类型的第一个空槽填上点位（同类型多枚时依次占用）。 */
    private static void assign(int[] slots, int type, int cell) {
        for (int slot = 0; slot < SIDE_TYPES.length; slot++) {
            if (SIDE_TYPES[slot] == type && slots[slot] == 0) {
                slots[slot] = cell;
                return;
            }
        }
    }

    /** 类型键 -> 类型码；未知返回 -1。 */
    public static int typeOfKey(String key) {
        for (int i = 0; i < TYPE_KEYS.length; i++) {
            if (TYPE_KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private JunqiRules() {
    }
}
