package com.tabletopgames.ai.xiangqi.pengjiu;

/**
 * 基准测试用替身：仅保留引擎核心所需的名字表，避开原版 UI 的 AWT/Swing 依赖。
 * 原版 @see com.tabletopgames.ai.xiangqi.pengjiu.ChessBoardMain（已从测试副本中剔除）。
 */
public class ChessBoardMain {

    public static final String[] chessName = new String[]{
        "  ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        "BK", "BR", "BR", "BN", "BN", "BC", "BC", "BB", "BB", "BA", "BA", "BP", "BP", "BP", "BP", "BP",
        "RK", "RR", "RR", "RN", "RN", "RC", "RC", "RB", "RB", "RA", "RA", "RP", "RP", "RP", "RP", "RP",
    };
}