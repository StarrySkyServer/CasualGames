package com.tabletopgames.ui;

import cn.nukkit.Player;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.game.XiangqiBoard;

import java.util.Locale;
import java.util.UUID;

/**
 * 中国象棋棋盘菜单：根据当前座位与机器人状态动态生成。
 *
 * <p>状态机（与斗兽棋一致）：
 * <ul>
 *     <li>无人入座：选择执红 / 选择执黑 +「设置每局金额 - 当前：n」（默认 0 = 免费局）
 *     + 象棋规则 +（仅 OP 可见的）全局配置</li>
 *     <li>已选单人对战、等待选色（本人）：选择执红 / 选择执黑 + 退出棋局</li>
 *     <li>一人入座（本人）：每局金额 = 0（免费局）时才提供三个等级的单人对战 + 退出棋局</li>
 *     <li>一人入座（其他人）：加入对方棋色（若该座位已选单人对战则视为满员）</li>
 *     <li>两人入座（先加入者）：开始棋局（金额 &gt; 0 时按该金额双方各扣款开局）
 *     +（免费局且开过局未分胜负时的）继承残局
 *     + 退出棋局（人机对战时中间还有「退出单人对战（等级）」）</li>
 *     <li>两人入座（后加入者）：退出棋局</li>
 *     <li>对局进行中：不弹菜单</li>
 * </ul>
 *
 * <p>0 人入座的菜单是独占的：打开时校验占用权，每个按钮动作执行前再用
 * {@link XiangqiBoard#holdsMenu} 校验一次并续期，保证超时或被他人接管后旧表单不再生效。
 */
public final class XiangqiMenu {

    private static final String[] AI_LEVELS = {"入门", "进阶", "大师"};

    private XiangqiMenu() {
    }

    /** 关闭玩家当前打开的表单（若有）。 */
    public static void closeCurrentForm(Player player) {
        if (!player.formWindows.isEmpty()) {
            player.closeFormWindows();
        }
    }

    public static void open(Player player, XiangqiBoard board) {
        if (board.isRunning()) {
            return;
        }
        closeCurrentForm(player);
        UUID id = player.getUniqueId();
        int myColor = board.seatColor(id);
        boolean pendingMine = board.isPendingAiSeat(id);
        if (myColor < 0 && !pendingMine && board.seatCount() == 0 && !board.tryAcquireMenu(player)) {
            player.sendTip("§c有人正在操作该棋盘，请稍候。");
            return;
        }
        Simple form = new Simple("中国象棋 [Beta]", board.statusText(player));
        if (pendingMine) {
            buildPendingColor(form, player, board);
        } else if (myColor >= 0) {
            buildSeated(form, player, board);
        } else {
            buildVisitor(form, player, board);
        }
        if (form.buttonCount() == 0) {
            return;
        }
        if (board.seatCount() == 0) {
            form.onClose(() -> board.releaseMenu(player));
        }
        form.show(player);
    }

    /** 从子表单返回上级菜单：仅在仍持有「0 人入座」菜单占用权时重新弹出。 */
    public static void reopen(Player player, XiangqiBoard board) {
        if (board.seatCount() == 0 && !board.holdsMenu(player)) {
            return;
        }
        open(player, board);
    }

    /** 0 人入座菜单的动作级校验。 */
    private static boolean checkMenu(Player player, XiangqiBoard board) {
        if (board.holdsMenu(player)) {
            return true;
        }
        player.sendTip("§c该菜单已失效或被他人接管，请重新点击棋盘。");
        return false;
    }

    /** 已入座玩家看到的菜单。 */
    private static void buildSeated(Simple form, Player player, XiangqiBoard board) {
        UUID id = player.getUniqueId();
        if (board.seatCount() >= 2) {
            if (board.isFirstJoiner(id)) {
                addStartButtons(form, player, board);
                if (board.isAiGame()) {
                    form.add("退出单人对战（" + XiangqiBoard.aiLevelName(board.getAiLevel()) + "）", () -> {
                        board.exitAiBattle();
                        board.reopenMenu(player);
                    });
                }
            }
            form.add("退出棋局", () -> board.leave(id));
        } else {
            // 每局金额 > 0 时才与单人对战互斥：免费局（金额 0）仍提供单人对战入口
            if (board.getPresetBet() == 0) {
                addAiButtons(form, player, board);
            }
            form.add("退出棋局", () -> board.leave(id));
        }
    }

    /**
     * 「开始棋局」相关按钮。
     * <p>
     * 预设金额 &gt; 0 时：点击「开始棋局」直接按该金额双方各扣款并开局，「继承残局」不出现。
     * 金额 = 0（免费局）时：正常开局。
     */
    private static void addStartButtons(Simple form, Player player, XiangqiBoard board) {
        double bet = board.getPresetBet();
        if (bet > 0) {
            form.add("开始棋局", () -> {
                String err = board.startGambling(bet);
                if (err != null) {
                    player.sendTip("§c" + err);
                }
            });
            return;
        }
        form.add("开始棋局", board::start);
        if (board.hasStarted() && !board.isFinished()) {
            form.add("继承残局", board::inheritEndgame);
        }
    }

    /** 未入座玩家看到的菜单。 */
    private static void buildVisitor(Simple form, Player player, XiangqiBoard board) {
        int count = board.seatCount();
        if (count == 0) {
            addColorButtons(form, player, board);
            addBetAmountButton(form, player, board);
            addRuleInfoButton(form, player, board);
            addSettingsButton(form, player, board);
        } else if (count == 1) {
            if (board.hasPendingAi()) {
                player.sendTip("§c对方正在进行单人对战，座位已满。");
                return;
            }
            if (board.isColorTaken(XiangqiBoard.COLOR_RED)) {
                form.add("加入执黑方", () -> joinColor(player, board, XiangqiBoard.COLOR_BLACK));
            } else {
                form.add("加入执红方", () -> joinColor(player, board, XiangqiBoard.COLOR_RED));
            }
        } else {
            player.sendTip("§c座位已满（2 人），无法加入。");
        }
    }

    /** 已选择单人对战、等待选色的玩家菜单。 */
    private static void buildPendingColor(Simple form, Player player, XiangqiBoard board) {
        form.add("选择执红方", () -> {
            if (board.chooseAiColor(player, XiangqiBoard.COLOR_RED)) {
                board.reopenMenu(player);
            }
        });
        form.add("选择执黑方", () -> {
            if (board.chooseAiColor(player, XiangqiBoard.COLOR_BLACK)) {
                board.reopenMenu(player);
            }
        });
        form.add("退出棋局", () -> board.leave(player.getUniqueId()));
    }

    /** 棋色选择按钮：选色后即入座。 */
    private static void addColorButtons(Simple form, Player player, XiangqiBoard board) {
        form.add("选择执红方", () -> {
            if (checkMenu(player, board)) {
                joinColor(player, board, XiangqiBoard.COLOR_RED);
            }
        });
        form.add("选择执黑方", () -> {
            if (checkMenu(player, board)) {
                joinColor(player, board, XiangqiBoard.COLOR_BLACK);
            }
        });
    }

    /**
     * 入座按钮动作。{@link XiangqiBoard#join} 会统一刷新所有座位玩家的表单，
     * 因此成功后不再自行重开；失败说明表单已过时，重开一次让玩家看到最新状态。
     */
    private static void joinColor(Player player, XiangqiBoard board, int color) {
        if (!board.join(player, color)) {
            board.reopenMenu(player);
        }
    }

    /** 单人对战按钮。点击后玩家立即入座（占一个座位），未选色时暂存机器人等级。 */
    private static void addAiButtons(Simple form, Player player, XiangqiBoard board) {
        for (int i = 0; i < AI_LEVELS.length; i++) {
            final int level = i + 1;
            form.add("单人对战（" + AI_LEVELS[i] + "）", () -> {
                if (!checkMenu(player, board)) {
                    return;
                }
                board.chooseAi(player, level);
                board.reopenMenu(player);
            });
        }
    }

    /**
     * 「设置每局金额」入口：仅「0 人入座」时出现。金额 = 双方各自扣款额，填 0 即免费局。
     * <p>
     * 经济插件是强依赖（plugin.yml 的 {@code depend}），缺失时本插件不会被加载，
     * 因此这里无需再判断经济插件是否可用。
     */
    private static void addBetAmountButton(Simple form, Player player, XiangqiBoard board) {
        form.add("设置每局金额 - 当前：" + money(board.getPresetBet()), () -> {
            if (checkMenu(player, board)) {
                board.later(() -> openPresetBetInput(player, board));
            }
        });
    }

    /**
     * 「象棋规则」入口：仅「0 人入座」时出现。
     * <p>
     * 点进去即视作离开「0 人入座」页面：立即释放菜单占用锁，其他人可正常操作棋盘；
     * 该表单也不再受超时兜底影响（占用锁已释放），关闭后不会返回上一级。
     */
    private static void addRuleInfoButton(Simple form, Player player, XiangqiBoard board) {
        form.add("象棋规则", () -> {
            if (checkMenu(player, board)) {
                board.releaseMenu(player);
                board.later(() -> openRuleInfo(player, board));
            }
        });
    }

    /**
     * 「全局配置」入口：仅 OP 在「0 人入座」菜单可见，排在最后一行。
     * <p>
     * 点击后释放菜单占用锁并打开插件设置表单，关闭后不返回上一级（与「象棋规则」一致）。
     */
    private static void addSettingsButton(Simple form, Player player, XiangqiBoard board) {
        if (!player.isOp()) {
            return;
        }
        form.add("全局配置", () -> {
            if (checkMenu(player, board)) {
                board.releaseMenu(player);
                board.later(() -> SettingsMenu.open(player, BoardKind.XIANGQI));
            }
        });
    }

    /** 「象棋规则」表单：只展示说明文字，仅一个「退出」按钮，关闭后不返回上一级。 */
    private static void openRuleInfo(Player player, XiangqiBoard board) {
        Simple form = new Simple("中国象棋 [Beta] · 规则",
                "§f【行棋】\n"
                        + "§7· 红先黑后，一人一手，交替行棋；\n"
                        + "§7· 点击棋盘交叉点：先选己方棋子，再点落点走子；\n"
                        + "§7· 点己方另一子可改选，点回自身则取消选择。\n\n"
                        + "§f【各子走法】\n"
                        + "§7· 车：横竖直线行走，路径上不得有子；\n"
                        + "§7· 马：走「日」字，蹩腿方向有子则不能走；\n"
                        + "§7· 象：走「田」字，塞眼有子则不能走，且不可过河；\n"
                        + "§7· 士：九宫内斜走一步；\n"
                        + "§7· 将/帅：九宫内直走一步；\n"
                        + "§7· 炮：不吃子时走法同车，吃子须隔且仅隔一枚棋子；\n"
                        + "§7· 兵/卒：过河前只可直走一步，过河后可横走一步，但不可后退。\n\n"
                        + "§f【将军与胜负】\n"
                        + "§7· 被将军时必须应将，不可自将（走后己方仍被将军），将帅不可照面；\n"
                        + "§7· 将死或困毙对方即获胜；\n"
                        + "§7· 和棋：同一局面三次重复，或连续 50 回合无吃子。");
        // 仅一个「退出」按钮：关闭后不返回上一级（进入本表单时已释放 0 人菜单占用）
        form.add("退出", () -> {
        });
        form.show(player);
    }

    /** 「每局金额」输入表单；直接关闭时回到棋盘菜单。 */
    private static void openPresetBetInput(Player player, XiangqiBoard board) {
        double min = board.getMinBet();
        double max = board.getMaxBet();
        new Custom("中国象棋 [Beta] · 每局金额")
                .label("请输入本局双方各自下注的金额（0，或 " + money(min) + " ~ " + money(max)
                        + "，最多两位小数）\n"
                        + "· 0：免费局，不扣款；仅此时才能选择单人对战。\n"
                        + "· >0：开局时双方各扣除该金额；获胜方获得奖池扣除官方抽水后的奖金，和棋则全额退还。")
                .input("每局金额", money(board.getPresetBet()))
                .submit("确认")
                .onSubmit(text -> board.later(() -> handlePresetBet(player, board, text)))
                .onClose(() -> board.reopenMenu(player))
                .show(player);
    }

    /** 校验并保存「每局金额」（仅 0 人入座、持有菜单占用权时可设；0 表示免费局）。 */
    private static void handlePresetBet(Player player, XiangqiBoard board, String text) {
        Double amount = parseAmount(text);
        if (amount == null) {
            promptRetry(player, board, "§c金额格式不正确：只能输入数字，且最多保留两位小数。");
            return;
        }
        String error = board.setPresetBet(player, amount);
        if (error != null) {
            promptRetry(player, board, "§c" + error);
            return;
        }
        board.reopenMenu(player);
    }

    /** 输入不合法提示：点击返回重新输入；直接关闭则回到棋盘菜单。 */
    private static void promptRetry(Player player, XiangqiBoard board, String message) {
        Simple prompt = new Simple("中国象棋 [Beta] · 提示", message);
        prompt.add("返回重新输入", () -> board.later(() -> openPresetBetInput(player, board)));
        prompt.onClose(() -> board.reopenMenu(player));
        prompt.show(player);
    }

    /** 解析金额：接受非负数且最多两位小数（0 = 免费局）。 */
    private static Double parseAmount(String text) {
        if (text == null || !text.matches("\\d+(\\.\\d{1,2})?")) {
            return null;
        }
        try {
            double value = Double.parseDouble(text);
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
