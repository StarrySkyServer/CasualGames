package com.tabletopgames.ui;

import cn.nukkit.Player;
import cn.nukkit.Server;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.game.GoBoard;

import java.util.Locale;
import java.util.UUID;

/**
 * 围棋棋盘菜单：根据当前座位与对局状态动态生成。
 *
 * <p>状态机（与象棋一致）：
 * <ul>
 *     <li>无人入座：选择执黑 / 选择执白 +「设置每局金额 - 当前：n」（默认 0 = 免费局）
 *     + 围棋规则 +（仅 OP 可见的）全局配置</li>
 *     <li>已选单人对战、等待选色（本人）：选择执黑 / 选择执白 + 退出棋局</li>
 *     <li>一人入座（本人）：每局金额 = 0（免费局）时才提供三个等级的单人对战 + 退出棋局</li>
 *     <li>一人入座（其他人）：加入空缺的那个棋色</li>
 *     <li>两人入座（先加入者）：开始棋局（金额 &gt; 0 时按该金额双方各扣款开局）/ 让子开局…
 *     +（免费局且开过局未分胜负时的）继承残局
 *     + 退出棋局（人机对战时中间还有「退出单人对战（等级）」）</li>
 *     <li>两人入座（后加入者）：退出棋局</li>
 *     <li>对局进行中（点击已有棋子的交点弹出）：停一手（Pass，仅当前回合方）/ 求和 / 认输</li>
 * </ul>
 *
 * <p>0 人入座的菜单是独占的：打开时校验占用权，每个按钮动作执行前再用
 * {@link GoBoard#holdsMenu} 校验一次并续期，保证超时或被他人接管后旧表单不再生效。
 */
public final class GoMenu {

    private static final String[] AI_LEVELS = {"入门", "进阶", "大师"};

    private GoMenu() {
    }

    /** 关闭玩家当前打开的表单（若有）。 */
    public static void closeCurrentForm(Player player) {
        if (!player.formWindows.isEmpty()) {
            player.closeFormWindows();
        }
    }

    public static void open(Player player, GoBoard board) {
        if (board.isRunning()) {
            openInGame(player, board);
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
        Simple form = new Simple("围棋 [Beta]", board.statusText(player));
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
    public static void reopen(Player player, GoBoard board) {
        if (board.seatCount() == 0 && !board.holdsMenu(player)) {
            return;
        }
        open(player, board);
    }

    /** 对局中点击已有棋子的交点弹出的菜单。 */
    public static void openInGame(Player player, GoBoard board) {
        if (!board.isRunning() || !board.isSeated(player.getUniqueId())) {
            player.sendTip("§c你不是本局玩家。");
            return;
        }
        closeCurrentForm(player);
        Simple form = new Simple("围棋 [Beta]", board.statusText(player));
        if (board.seatColor(player.getUniqueId()) == board.getTurn()) {
            form.add("停一手（Pass）", () -> {
                String tip = board.pass(player);
                if (tip != null) {
                    player.sendTip(tip);
                }
            });
        }
        form.add("求和", () -> offerDraw(player, board));
        form.add("认输", () -> {
            String tip = board.resign(player);
            if (tip != null) {
                player.sendTip(tip);
            }
        });
        form.add("关闭", () -> {
        });
        form.show(player);
    }

    /** 0 人入座菜单的动作级校验。 */
    private static boolean checkMenu(Player player, GoBoard board) {
        if (board.holdsMenu(player)) {
            return true;
        }
        player.sendTip("§c该菜单已失效或被他人接管，请重新点击棋盘。");
        return false;
    }

    /** 已入座玩家看到的菜单。 */
    private static void buildSeated(Simple form, Player player, GoBoard board) {
        UUID id = player.getUniqueId();
        if (board.seatCount() >= 2) {
            if (board.isFirstJoiner(id)) {
                addStartButtons(form, player, board);
                if (board.isAiGame()) {
                    form.add("退出单人对战（" + GoBoard.aiLevelName(board.getAiLevel()) + "）", () -> {
                        board.exitAiBattle();
                        board.reopenMenu(player);
                    });
                }
            }
        } else {
            // 每局金额 > 0 时才与单人对战互斥：免费局（金额 0）仍提供单人对战入口
            if (board.getPresetBet() == 0) {
                addAiButtons(form, player, board);
            }
        }
        form.add("退出棋局", () -> board.leave(id));
    }

    /**
     * 「开始棋局」相关按钮。
     * <p>
     * 预设金额 &gt; 0 时：点击「开始棋局」直接按该金额双方各扣款并开局，「让子开局」与「继承残局」不出现
     * （让子会改变双方胜率，与赌博下注不兼容）。金额 = 0（免费局）时：正常开局。
     */
    private static void addStartButtons(Simple form, Player player, GoBoard board) {
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
        form.add("让子开局…", () -> board.later(() -> openHandicapInput(player, board)));
        if (board.hasStarted() && !board.isFinished()) {
            form.add("继承残局", board::inheritEndgame);
        }
    }

    /** 未入座玩家看到的菜单。 */
    private static void buildVisitor(Simple form, Player player, GoBoard board) {
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
            if (board.isColorTaken(GoBoard.BLACK)) {
                form.add("加入执白方", () -> joinColor(player, board, GoBoard.WHITE));
            } else {
                form.add("加入执黑方", () -> joinColor(player, board, GoBoard.BLACK));
            }
        } else {
            player.sendTip("§c座位已满（2 人），无法加入。");
        }
    }

    /** 已选择单人对战、等待选色的玩家菜单。 */
    private static void buildPendingColor(Simple form, Player player, GoBoard board) {
        form.add("选择执黑方", () -> {
            if (board.chooseAiColor(player, GoBoard.BLACK)) {
                board.reopenMenu(player);
            }
        });
        form.add("选择执白方", () -> {
            if (board.chooseAiColor(player, GoBoard.WHITE)) {
                board.reopenMenu(player);
            }
        });
        form.add("退出棋局", () -> board.leave(player.getUniqueId()));
    }

    /** 单人对战按钮。点击后玩家立即入座（占一个座位），未选色时暂存机器人等级。 */
    private static void addAiButtons(Simple form, Player player, GoBoard board) {
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

    /** 棋色选择按钮：黑先手，故黑方排在前面。 */
    private static void addColorButtons(Simple form, Player player, GoBoard board) {
        form.add("选择执黑方", () -> {
            if (checkMenu(player, board)) {
                joinColor(player, board, GoBoard.BLACK);
            }
        });
        form.add("选择执白方", () -> {
            if (checkMenu(player, board)) {
                joinColor(player, board, GoBoard.WHITE);
            }
        });
    }

    /**
     * 入座按钮动作。{@link GoBoard#join} 会统一刷新所有座位玩家的表单，
     * 因此成功后不再自行重开；失败说明表单已过时，重开一次让玩家看到最新状态。
     */
    private static void joinColor(Player player, GoBoard board, int color) {
        if (!board.join(player, color)) {
            board.reopenMenu(player);
        }
    }

    /**
     * 「设置每局金额」入口：仅「0 人入座」时出现。金额 = 双方各自扣款额，填 0 即免费局。
     * <p>
     * 经济插件是强依赖（plugin.yml 的 {@code depend}），缺失时本插件不会被加载，
     * 因此这里无需再判断经济插件是否可用。
     */
    private static void addBetAmountButton(Simple form, Player player, GoBoard board) {
        // 仅 9 路对局开放赌博（13 / 19 路耗时过长，见 GoBoard#supportsGambling）
        if (!board.supportsGambling()) {
            return;
        }
        form.add("设置每局金额 - 当前：" + money(board.getPresetBet()), () -> {
            if (checkMenu(player, board)) {
                board.later(() -> openPresetBetInput(player, board));
            }
        });
    }

    /**
     * 「围棋规则」入口：仅「0 人入座」时出现。
     * <p>
     * 点进去即视作离开「0 人入座」页面：立即释放菜单占用锁，其他人可正常操作棋盘；
     * 该表单也不再受超时兜底影响（占用锁已释放），关闭后不会返回上一级。
     */
    private static void addRuleInfoButton(Simple form, Player player, GoBoard board) {
        form.add("围棋规则", () -> {
            if (checkMenu(player, board)) {
                board.releaseMenu(player);
                board.later(() -> openRuleInfo(player, board));
            }
        });
    }

    /**
     * 「全局配置」入口：仅 OP 在「0 人入座」菜单可见，排在最后一行。
     * <p>
     * 点击后释放菜单占用锁并打开插件设置表单，关闭后不返回上一级（与「围棋规则」一致）。
     */
    private static void addSettingsButton(Simple form, Player player, GoBoard board) {
        if (!player.isOp()) {
            return;
        }
        form.add("全局配置", () -> {
            if (checkMenu(player, board)) {
                board.releaseMenu(player);
                board.later(() -> SettingsMenu.open(player, BoardKind.GO));
            }
        });
    }

    /** 「围棋规则」表单：只展示说明文字，仅一个「退出」按钮。 */
    private static void openRuleInfo(Player player, GoBoard board) {
        Simple form = new Simple("围棋 [Beta] · 规则",
                "§f【开局】\n"
                        + "§7· 黑方先行，双方轮流把棋子落在棋盘的交叉点上；\n"
                        + "§7· 落子后棋子不再移动，也不可被吃回。\n\n"
                        + "§f【气与提子】\n"
                        + "§7· 棋子上下左右紧邻的空点称为「气」；\n"
                        + "§7· 一方棋块的气被全部堵住时，该棋块立即被提走（从盘上移除）。\n\n"
                        + "§f【禁着点与打劫】\n"
                        + "§7· 落子后自身无气、又不能提走对方棋子，这样的点是禁着点，不能落子；\n"
                        + "§7· 单劫：刚被提走一子的位置，不能立即回提，须先在别处走一手。\n\n"
                        + "§f【停一手与终局】\n"
                        + "§7· 「停一手（Pass）」表示放弃本次落子；\n"
                        + "§7· 双方连续两次停一手即终局，自动按中国规则数子：\n"
                        + "§7· 己方棋子数 + 己方围住的空点数，黑方另需贴给白方 7.5 目（3¾ 子），多者胜。\n\n"
                        + "§f【让子】\n"
                        + "§7· 让子开局先由黑方摆上指定数量的棋子，之后白方先行，且不贴目。");
        form.add("退出", () -> {
        });
        form.show(player);
    }

    /** 让子数输入表单；直接关闭时回到开始棋局菜单。 */
    private static void openHandicapInput(Player player, GoBoard board) {
        new Custom("围棋 [Beta] · 让子开局")
                .label("请输入让子数（" + GoBoard.MIN_HANDICAP + " ~ " + GoBoard.MAX_HANDICAP + "）。\n"
                        + "让子对局：黑方先摆上相应数量的棋子，之后由白方先行，且不贴目（贴目 0）。")
                .input("让子数", String.valueOf(GoBoard.MIN_HANDICAP))
                .submit("开始对局")
                .onSubmit(text -> board.later(() -> handleHandicap(player, board, text)))
                .onClose(() -> board.reopenMenu(player))
                .show(player);
    }

    /** 校验让子数并开局。 */
    private static void handleHandicap(Player player, GoBoard board, String text) {
        int count;
        try {
            count = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            promptHandicapRetry(player, board, "§c让子数必须是整数。");
            return;
        }
        if (count < GoBoard.MIN_HANDICAP || count > GoBoard.MAX_HANDICAP) {
            promptHandicapRetry(player, board, "§c让子数需在 " + GoBoard.MIN_HANDICAP
                    + " ~ " + GoBoard.MAX_HANDICAP + " 之间。");
            return;
        }
        if (board.seatCount() < 2) {
            promptHandicapRetry(player, board, "§c座位未满（需要 2 人）才能开始对局。");
            return;
        }
        board.start(count);
    }

    /** 让子数不合法提示：点击返回重新输入；直接关闭则回到开始棋局菜单。 */
    private static void promptHandicapRetry(Player player, GoBoard board, String message) {
        Simple prompt = new Simple("围棋 [Beta] · 提示", message);
        prompt.add("返回重新输入", () -> board.later(() -> openHandicapInput(player, board)));
        prompt.onClose(() -> board.reopenMenu(player));
        prompt.show(player);
    }

    /** 向对手推送求和表单。 */
    private static void offerDraw(Player player, GoBoard board) {
        UUID opponentId = board.opponentOf(player.getUniqueId());
        Player opponent = opponentId == null ? null : Server.getInstance().getPlayer(opponentId).orElse(null);
        if (opponent == null) {
            player.sendTip("§c对手不在线，无法求和。");
            return;
        }
        player.sendTip("§e已向对手提出和棋请求。");
        Simple form = new Simple("围棋 [Beta] · 求和", "§e" + player.getName() + " 请求和棋，是否同意？");
        form.add("同意", board::acceptDraw);
        form.add("拒绝", () -> {
            Player requester = Server.getInstance().getPlayer(player.getUniqueId()).orElse(null);
            if (requester != null) {
                requester.sendTip("§c对手拒绝了和棋请求。");
            }
        });
        form.show(opponent);
    }

    /** 「每局金额」输入表单；直接关闭时回到棋盘菜单。 */
    private static void openPresetBetInput(Player player, GoBoard board) {
        double min = board.getMinBet();
        double max = board.getMaxBet();
        new Custom("围棋 [Beta] · 每局金额")
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
    private static void handlePresetBet(Player player, GoBoard board, String text) {
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
    private static void promptRetry(Player player, GoBoard board, String message) {
        Simple prompt = new Simple("围棋 [Beta] · 提示", message);
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
