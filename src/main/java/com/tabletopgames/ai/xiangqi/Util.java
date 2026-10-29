package com.tabletopgames.ai.xiangqi;

/**
 * 工具方法（移植自 xqwlight 的 {@code Util.java}，仅保留运行期真正用到的部分）。
 * <p>
 * {@link RC4} 用于生成 {@link Position} 的 Zobrist 随机表，{@link #shellSort} 供 {@link Search} 排序着法。
 */
public final class Util {

    private Util() {
    }

    /** RC4 伪随机流，仅用于生成 Zobrist 表。 */
    public static class RC4 {
        private final int[] state = new int[256];
        private int x, y;

        public RC4(byte[] key) {
            for (int i = 0; i < 256; i++) {
                state[i] = i;
            }
            int j = 0;
            for (int i = 0; i < 256; i++) {
                j = (j + state[i] + key[i % key.length]) & 0xff;
                swap(i, j);
            }
        }

        private void swap(int i, int j) {
            int t = state[i];
            state[i] = state[j];
            state[j] = t;
        }

        public int nextByte() {
            x = (x + 1) & 0xff;
            y = (y + state[x]) & 0xff;
            swap(x, y);
            return state[(state[x] + state[y]) & 0xff];
        }

        public int nextLong() {
            return nextByte() + (nextByte() << 8) + (nextByte() << 16) + (nextByte() << 24);
        }
    }

    private static final int[] SHELL_STEP = {0, 1, 4, 13, 40, 121, 364, 1093};

    /** 按 {@code vls} 从大到小对 {@code mvs} 做希尔排序（同步交换）。 */
    public static void shellSort(int[] mvs, int[] vls, int from, int to) {
        int stepLevel = 1;
        while (SHELL_STEP[stepLevel] < to - from) {
            stepLevel++;
        }
        stepLevel--;
        while (stepLevel > 0) {
            int step = SHELL_STEP[stepLevel];
            for (int i = from + step; i < to; i++) {
                int mvBest = mvs[i];
                int vlBest = vls[i];
                int j = i - step;
                while (j >= from && vlBest > vls[j]) {
                    mvs[j + step] = mvs[j];
                    vls[j + step] = vls[j];
                    j -= step;
                }
                mvs[j + step] = mvBest;
                vls[j + step] = vlBest;
            }
            stepLevel--;
        }
    }
}
