package com.tabletopgames.ai.doushouqi;

/**
 * 斗兽棋（Jungle Chess / 斗兽棋）纯规则引擎：走法生成、吃子判定、胜负判定。
 * <p>
 * 棋盘 7 列 × 9 行，索引 {@code cell = row * 7 + col}（0..62）。棋子编码
 * {@code tex = color * 8 + type}（0..15），{@code color} 0 = 红（下方、先手）、1 = 黑（上方）；
 * {@code type} 0..7 = 象 狮 虎 豹 狗 狼 猫 鼠。空格为 {@link #EMPTY} = -1。
 * <p>
 * 规则要点（标准斗兽棋）：
 * <ul>
 *     <li>战斗力 象8 狮7 虎6 豹5 狼4 狗3 猫2 鼠1；攻击力 ≥ 对方防御力即可吃子（同级互吃）；</li>
 *     <li>防御力默认等于战斗力，**唯一例外**是站在对方陷阱里的棋子防御力归 0（可被任意子吃）；</li>
 *     <li>鼠可下水，且水陆之间不能互吃（鼠参与吃子时要求起点与终点同属水/陆）；</li>
 *     <li>鼠可吃象、象不可吃鼠（鼠防御力归 0 时除外）；</li>
 *     <li>狮、虎可跳河：沿方向连续河格全部为空才可跳过，落到对岸第一个陆格；</li>
 *     <li>任何子都不得进入己方兽穴；进入对方兽穴即获胜。</li>
 * </ul>
 */
public final class DoushouqiRules {

    /** 行数。 */
    public static final int ROWS = 9;
    /** 列数。 */
    public static final int COLS = 7;
    /** 格数（7 × 9）。 */
    public static final int CELLS = ROWS * COLS;

    /** 空格标记（与 {@code DoushouqiBoard.EMPTY} 一致）。 */
    public static final int EMPTY = -1;

    /** 红方（下方、先手）。 */
    public static final int COLOR_RED = 0;
    /** 黑方（上方）。 */
    public static final int COLOR_BLACK = 1;

    // 棋子类型编码（与贴图素材 棋子.png 的行列分组一致）。
    public static final int ELEPHANT = 0;
    public static final int LION = 1;
    public static final int TIGER = 2;
    public static final int LEOPARD = 3;
    public static final int DOG = 4;
    public static final int WOLF = 5;
    public static final int CAT = 6;
    public static final int RAT = 7;

    /** 各类型战斗力，下标为类型编码：象8 狮7 虎6 豹5 狗3 狼4 猫2 鼠1。 */
    public static final int[] RANK = {8, 7, 6, 5, 3, 4, 2, 1};

    /** 四个正交方向：上（行 -1）、下（行 +1）、左（列 -1）、右（列 +1）。 */
    private static final int[] DIR_ROW = {-1, 1, 0, 0};
    private static final int[] DIR_COL = {0, 0, -1, 1};

    /** 河（水域）标记表：行 3/4/5 的列 1/2 与 4/5。 */
    private static final boolean[] RIVER = new boolean[CELLS];

    static {
        for (int r = 3; r <= 5; r++) {
            RIVER[r * COLS + 1] = true;
            RIVER[r * COLS + 2] = true;
            RIVER[r * COLS + 4] = true;
            RIVER[r * COLS + 5] = true;
        }
    }

    private DoushouqiRules() {
    }

    /** 棋子编码：{@code color * 8 + type}。 */
    public static int tex(int color, int type) {
        return color * 8 + type;
    }

    /** 棋子编码 → 棋色。 */
    public static int colorOf(int tex) {
        return tex >> 3;
    }

    /** 棋子编码 → 类型。 */
    public static int typeOf(int tex) {
        return tex & 7;
    }

    /** 该格是否属于河（水域）。 */
    public static boolean isRiver(int cell) {
        return RIVER[cell];
    }

    /** 某方的兽穴格：红方 59（r8c3），黑方 3（r0c3）。 */
    public static int den(int color) {
        return color == COLOR_RED ? 59 : 3;
    }

    /** 该格是否为 {@code color} 方的陷阱（本方的三个陷阱之一）。 */
    public static boolean isTrapOf(int color, int cell) {
        if (color == COLOR_RED) {
            return cell == 52 || cell == 58 || cell == 60;
        }
        return cell == 2 || cell == 4 || cell == 10;
    }

    /**
     * 棋子防御力：站在**对方**陷阱里的棋子防御力归 0，其余情况等于战斗力。
     * <p>
     * 站在自己陷阱里的棋子防御力不受影响（仍是正常战斗力）。
     */
    public static int defense(int tex, int cell) {
        if (isTrapOf(1 - colorOf(tex), cell)) {
            return 0;
        }
        return RANK[typeOf(tex)];
    }

    /**
     * 攻方 {@code attackerTex} 从 {@code from} 走到 {@code to} 吃守方 {@code defenderTex} 是否合法。
     */
    public static boolean canCapture(int attackerTex, int from, int defenderTex, int to) {
        int at = typeOf(attackerTex);
        int dt = typeOf(defenderTex);
        int def = defense(defenderTex, to);
        // 鼠相关的水陆互吃限制：起点与终点必须同属水/陆。
        if ((at == RAT || dt == RAT) && isRiver(from) != isRiver(to)) {
            return false;
        }
        // 鼠吃象 / 鼠吃鼠 / 吃陷阱中的任意子（防御力 0）；鼠吃不动其余（战斗力 1 只够吃鼠）。
        if (at == RAT) {
            return dt == ELEPHANT || dt == RAT || def == 0;
        }
        // 象不能吃鼠（鼠防御力归 0 时除外）。
        if (dt == RAT && def != 0 && at == ELEPHANT) {
            return false;
        }
        return RANK[at] >= def;
    }

    /**
     * 生成 {@code color} 方的全部合法着法，写入 {@code out}（每着占两个元素：from、to）。
     *
     * @return 生成的着法数（{@code out} 需至少 {@code 2 * 200} 长）
     */
    public static int generate(int[] board, int color, int[] out) {
        int n = 0;
        int ownDen = den(color);
        for (int cell = 0; cell < CELLS; cell++) {
            int tex = board[cell];
            if (tex == EMPTY || colorOf(tex) != color) {
                continue;
            }
            int type = typeOf(tex);
            int row = cell / COLS;
            int col = cell % COLS;
            for (int d = 0; d < 4; d++) {
                int nr = row + DIR_ROW[d];
                int nc = col + DIR_COL[d];
                if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) {
                    continue;
                }
                int to = nr * COLS + nc;
                if (isRiver(to)) {
                    if (type == LION || type == TIGER) {
                        // 跳河：沿方向扫描连续河格，全部为空才能跳过，落到第一个非河格。
                        int r2 = nr;
                        int c2 = nc;
                        boolean blocked = false;
                        while (r2 >= 0 && r2 < ROWS && c2 >= 0 && c2 < COLS && isRiver(r2 * COLS + c2)) {
                            if (board[r2 * COLS + c2] != EMPTY) {
                                blocked = true;
                                break;
                            }
                            r2 += DIR_ROW[d];
                            c2 += DIR_COL[d];
                        }
                        if (blocked || r2 < 0 || r2 >= ROWS || c2 < 0 || c2 >= COLS) {
                            continue;
                        }
                        to = r2 * COLS + c2;
                    } else if (type != RAT) {
                        // 除鼠（可下水）与狮虎（跳河）外，其余棋子不得入水。
                        continue;
                    }
                }
                if (to == ownDen) {
                    // 任何子都不得进入己方兽穴。
                    continue;
                }
                int target = board[to];
                if (target == EMPTY) {
                    out[n++] = cell;
                    out[n++] = to;
                } else if (colorOf(target) != color && canCapture(tex, cell, target, to)) {
                    out[n++] = cell;
                    out[n++] = to;
                }
            }
        }
        return n / 2;
    }

    /**
     * {@code color} 方从 {@code from} 走到 {@code to} 是否为一步合法着法（含吃子判定）。
     * <p>
     * 直接复用 {@link #generate} 的完整规则（跳河、入水、兽穴、陷阱等），
     * 供棋盘单步校验使用；每次调用自建临时缓冲，着法数极少的场景下开销可忽略。
     */
    public static boolean isLegal(int[] board, int color, int from, int to) {
        int[] buf = new int[CELLS * 8 + 16];
        int n = generate(board, color, buf);
        for (int i = 0; i < n; i++) {
            if (buf[i * 2] == from && buf[i * 2 + 1] == to) {
                return true;
            }
        }
        return false;
    }

    /** 是否有一方已把棋子走入对方兽穴；有则返回胜方棋色，否则返回 -1。 */
    public static int winnerAt(int[] board) {
        for (int cell = 0; cell < CELLS; cell++) {
            int tex = board[cell];
            if (tex != EMPTY && cell == den(1 - colorOf(tex))) {
                return colorOf(tex);
            }
        }
        return -1;
    }

    /** 某方是否还有棋子（无子判负）。 */
    public static boolean hasPiece(int[] board, int color) {
        for (int cell = 0; cell < CELLS; cell++) {
            int tex = board[cell];
            if (tex != EMPTY && colorOf(tex) == color) {
                return true;
            }
        }
        return false;
    }
}
