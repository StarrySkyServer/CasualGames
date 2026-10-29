package com.tabletopgames.ai;

import com.tabletopgames.ai.xiangqi.Position;
import com.tabletopgames.ai.xiangqi.Search;

/**
 * 中国象棋机器人：把插件棋盘局面转成 xqwlight 引擎的 {@link Position} 后求最优着法。
 * <p>
 * 三档难度按目标搜索层数区分（入门 3 层 / 进阶 6 层 / 大师 9 层），单步搜索最长 {@link #MAX_MILLIS} 毫秒：
 * 某层没跑完就退回上一层已完成的着法。为保证“机器人有思考感”，即使提前搜完也会补足到
 * {@link #MIN_MILLIS} 毫秒再返回。整个过程在异步线程中执行，不阻塞服务端主线程。
 * <p>
 * <b>线程安全</b>：xqwlight 除只读的预计算表外没有可变静态状态，每次调用都新建独立的
 * {@link Position} 与 {@link Search}，因此多盘棋可并发思考，无需加锁。
 */
public final class XiangqiAi {

    /** 棋盘行数 / 列数，与 {@code XiangqiBoard} 保持一致。 */
    private static final int ROWS = 10;
    private static final int COLS = 9;

    /** 空格标记，与 {@code XiangqiBoard.EMPTY} 保持一致。 */
    private static final int EMPTY = -1;

    /** 每档难度的目标搜索层数：入门 / 进阶 / 大师。 */
    private static final int[] LEVEL_DEPTH = {3, 6, 9};

    /** 单步搜索时间上限（毫秒）：超过则丢弃未跑完的层，使用上一层结果。 */
    private static final int MAX_MILLIS = 4000;

    /** 单步最短思考时间（毫秒）：即使提前搜完也要等满，让落子有节奏感。 */
    private static final int MIN_MILLIS = 1000;

    /** 置换表级数（2 的幂次，条目数 = 2^level）。 */
    private static final int HASH_LEVEL = 16;

    /**
     * 插件棋子类型 → 引擎棋子类型。下标为贴图编号对 7 取模（0=车、1=马、2=象、3=士、4=帅、5=炮、6=兵）。
     */
    public static final int[] ENGINE_TYPE = {
            Position.PIECE_ROOK,
            Position.PIECE_KNIGHT,
            Position.PIECE_BISHOP,
            Position.PIECE_ADVISOR,
            Position.PIECE_KING,
            Position.PIECE_CANNON,
            Position.PIECE_PAWN,
    };

    private XiangqiAi() {
    }

    /**
     * 求一步最优着法。
     *
     * @param grid  {@code [棋盘行][列]}，值为贴图编号（0..6 红方、7..13 黑方），-1 表示空格
     * @param color 走子方：0 = 红方、1 = 黑方
     * @param level 难度：1 = 入门、2 = 进阶、3 = 大师（越界按 3 处理）
     * @return {@code {起点行, 起点列, 终点行, 终点列}}；无着可走时返回 {@code null}
     */
    public static int[] bestMove(int[][] grid, int color, int level) {
        int level1 = level < 1 ? 1 : Math.min(level, LEVEL_DEPTH.length);
        int depth = LEVEL_DEPTH[level1 - 1];
        long start = System.currentTimeMillis();

        int mv = search(grid, color, depth);

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed < MIN_MILLIS) {
            try {
                Thread.sleep(MIN_MILLIS - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mv <= 0) {
            return null;
        }
        int src = Position.SRC(mv);
        int dst = Position.DST(mv);
        return new int[]{
                (src >> 4) - Position.RANK_TOP, (src & 15) - Position.FILE_LEFT,
                (dst >> 4) - Position.RANK_TOP, (dst & 15) - Position.FILE_LEFT,
        };
    }

    /** 在独立局面/搜索器上跑一次迭代加深，返回引擎着法编码（0 表示无着可走）。 */
    private static int search(int[][] grid, int color, int depth) {
        Position pos = new Position();
        pos.clearBoard();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int tex = grid[row][col];
                if (tex == EMPTY) {
                    continue;
                }
                pos.addPiece(Position.COORD_XY(col + Position.FILE_LEFT, row + Position.RANK_TOP),
                        Position.SIDE_TAG(colorOf(tex)) + ENGINE_TYPE[tex % 7]);
            }
        }
        pos.sdPlayer = color;
        pos.setIrrev();
        return new Search(pos, HASH_LEVEL).searchMain(depth, MAX_MILLIS);
    }

    /** 贴图编号 → 棋色：红方贴图 0..6、黑方 7..13（与 {@code XiangqiBoard} 一致）。 */
    private static int colorOf(int tex) {
        return tex < 7 ? 0 : 1;
    }
}
