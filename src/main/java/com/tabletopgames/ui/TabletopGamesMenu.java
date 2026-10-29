package com.tabletopgames.ui;

import cn.nukkit.Player;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.game.TabletopGamesBoard;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * 五子棋棋盘菜单：根据当前座位、选色与规则动态生成。
 *
 * <p>状态机：
 * <ul>
 *     <li>无人入座：选择执白 / 选择执黑 +「设置每局金额 - 当前：n」（默认 0 = 免费局）
 *     + 更改规则 + 关于规则 +（仅 OP 可见的）全局配置</li>
 *     <li>已选单人对战、等待选色（本人）：选择执白 / 选择执黑 + 退出棋局（此时已占座，对他人视为满员）</li>
 *     <li>一人入座（本人）：每局金额 = 0（免费局）时才提供三个等级的单人对战 + 退出棋局</li>
 *     <li>一人入座（其他人）：加入对方棋色（若该座位已选单人对战则视为满员）</li>
 *     <li>两人入座（先加入者）：开始棋局（金额 &gt; 0 时按该金额双方各扣款开局）
 *     +（免费局且休闲模式且棋盘已有棋子且未分胜负时才有的）继承棋局
 *     + 退出棋局（人机对战时中间还有「退出单人对战（等级）」：只移除机器人座位回到 1 人状态）</li>
 *     <li>两人入座（后加入者）：退出棋局</li>
 *     <li>对局进行中：不弹菜单（Swap2 开局选择阶段除外，见 {@link #openSwapDecision}）</li>
 * </ul>
 *
 * <p>「更改规则」「设置每局金额」「关于规则」都只在「0 人入座」时出现，且只有先打开菜单的人能操作
 * （占用锁见 {@link TabletopGamesBoard#tryAcquireMenu}）。尾部顺序固定为：设置每局金额 → 更改规则 → 关于规则。
 *
 * <p>0 人入座的菜单是独占的：表单弹出后无法从服务端强制收回，因此除了打开时校验占用权，
 * 每个按钮动作执行前还会用 {@link TabletopGamesBoard#holdsMenu} 再校验一次并续期，
 * 保证超时或被他人接管后，旧表单上的点击不再生效。
 *
 * <p>有人入座或离座时，{@link TabletopGamesManager#refreshSeatMenus} 会刷新所有在座玩家的表单：
 * 先 {@link #closeCurrentForm(Player)} 关掉客户端上的旧表单，再延后一 tick 弹出对应状态的表单。
 * Nukkit 在客户端已打开表单时不会发送新表单，所以「先关再弹」是自动切换的关键。
 * 等待中的一方无需点击棋盘就会自动换到对应状态的界面：
 * <ul>
 *     <li>1 人 -> 2 人：换成「开始棋局 / 等待对方开始」</li>
 *     <li>2 人 -> 1 人：换回「1 人可开局」界面（先加入者退出时，后加入者即成为先加入者）</li>
 * </ul>
 * 右键点击棋盘同样会先关旧表单再弹新菜单，因此表单开着也能刷新成最新状态。
 *
 * <p>占用者超时（{@code menuLockSeconds}，默认 30 秒）或已下线时，
 * 由 {@link TabletopGamesBoard#tickMenuHolder()} 主动关闭其客户端上的表单并释放占用，
 * 避免「开着表单走人」把菜单一直占住。超时兜底仅作用于「0 人入座」的表单；
 * 一旦有人入座（含已选单人对战），菜单不再加锁，也不再有超时兜底。
 */
public final class TabletopGamesMenu {

    private static final String[] AI_LEVELS = {"入门", "进阶", "大师"};

    private TabletopGamesMenu() {
    }

    /**
     * 关闭玩家当前打开的表单（若有）。
     * <p>
     * Nukkit 的 {@link Player#showFormWindow} 在客户端仍有表单打开时（内部 {@code formOpen} 标志）会直接
     * 返回 -1 且不发送任何数据，表现为「点了没反应、表单弹不出来」。所以每次弹出新表单前必须先关掉旧表单，
     * 让客户端用新表单顶掉旧表单。
     * <p>
     * {@code formWindows} 是服务端对「客户端已打开表单」的登记表（成功弹出才写入，收到响应或关闭时移除），
     * 为空说明没有表单开着，此时不需要发包。关闭表单不会触发旧表单的响应回调，因此不会误触发
     * 「释放菜单占用」这类副作用。
     */
    public static void closeCurrentForm(Player player) {
        if (!player.formWindows.isEmpty()) {
            player.closeFormWindows();
        }
    }

    public static void open(Player player, TabletopGamesBoard board) {
        if (board.isRunning()) {
            return;
        }
        // 玩家可能还开着上一次的菜单（例如等待中的旧状态）：先关掉，本次右键才能刷新成当前状态
        closeCurrentForm(player);
        UUID id = player.getUniqueId();
        int myColor = board.seatColor(id);
        boolean pendingMine = board.isPendingAiSeat(id);
        // 0 人入座时菜单独占：同一时间只允许一人操作，避免两人同时抢座位/改设置
        if (myColor == 0 && !pendingMine && board.seatCount() == 0 && !board.tryAcquireMenu(player)) {
            player.sendTip("§c有人正在操作该棋盘，请稍候。");
            return;
        }
        Simple form = new Simple("五子棋 [Beta]", board.statusText(player));
        if (pendingMine) {
            // 已选单人对战、等待选色：此处只决定自己执黑/执白，机器人随后坐到对面
            buildPendingColor(form, player, board);
        } else if (myColor != 0) {
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

    /**
     * 从子表单返回上级菜单（关闭规则表单、设置每局金额等）。
     * <p>
     * 只有仍持有「0 人入座」菜单占用权时才重新弹出：自动关闭（超时/下线）会清空占用，
     * 此时即便旧表单的关闭回调被触发，也不会把菜单又弹回来。
     */
    public static void reopen(Player player, TabletopGamesBoard board) {
        if (board.seatCount() == 0 && !board.holdsMenu(player)) {
            return;
        }
        open(player, board);
    }

    /**
     * 0 人入座菜单的动作级校验：表单弹出后无法从服务端强制收回，超时或被他人接管后旧表单仍在客户端上，
     * 因此每个按钮动作执行前都要再校验一次占用权，保证失效后点击不再生效。
     */
    private static boolean checkMenu(Player player, TabletopGamesBoard board) {
        if (board.holdsMenu(player)) {
            return true;
        }
        player.sendTip("§c该菜单已失效或被他人接管，请重新点击棋盘。");
        return false;
    }

    /** 已入座玩家看到的菜单。 */
    private static void buildSeated(Simple form, Player player, TabletopGamesBoard board) {
        UUID id = player.getUniqueId();
        if (board.seatCount() >= 2) {
            if (board.isFirstJoiner(id)) {
                addStartButtons(form, player, board);
                // 人机对战开始前：可只退出机器人，回到 1 人入座状态再选其他等级；连续表单，不关闭窗口
                if (board.isAiGame()) {
                    form.add("退出单人对战（" + TabletopGamesBoard.aiLevelName(board.getAiLevel()) + "）", () -> {
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

    /** 未入座玩家看到的菜单。 */
    private static void buildVisitor(Simple form, Player player, TabletopGamesBoard board) {
        int count = board.seatCount();
        if (count == 0) {
            addColorButtons(form, player, board);
            addBetAmountButton(form, player, board);
            // 尾部依次为：更改规则、关于规则、（OP 可见）全局配置
            addRuleButton(form, player, board);
            addRuleInfoButton(form, player, board);
            addSettingsButton(form, player, board);
        } else if (count == 1) {
            // 对方已选单人对战：座位实际已被玩家 + 机器人占满，不再允许他人加入
            if (board.hasPendingAi()) {
                player.sendTip("§c对方正在进行单人对战，座位已满。");
                return;
            }
            if (board.isColorTaken(TabletopGamesBoard.WHITE)) {
                form.add("加入执黑方", () -> joinColor(player, board, TabletopGamesBoard.BLACK));
            } else {
                form.add("加入执白方", () -> joinColor(player, board, TabletopGamesBoard.WHITE));
            }
        } else {
            player.sendTip("§c座位已满（2 人），无法加入。");
        }
    }

    /**
     * 已选择单人对战、等待选色的玩家菜单。
     * <p>
     * 该玩家在点「单人对战」时已占座，因此这里没有 0 人菜单的占用锁与超时兜底；
     * 选色只决定自己执黑还是执白，机器人随后自动坐到对面。
     */
    private static void buildPendingColor(Simple form, Player player, TabletopGamesBoard board) {
        form.add("随机选择", () -> {
            int color = ThreadLocalRandom.current().nextBoolean() ? TabletopGamesBoard.BLACK : TabletopGamesBoard.WHITE;
            if (board.chooseAiColor(player, color)) {
                board.reopenMenu(player);
            }
        });
        form.add("选择执白方", () -> {
            if (board.chooseAiColor(player, TabletopGamesBoard.WHITE)) {
                board.reopenMenu(player);
            }
        });
        form.add("选择执黑方", () -> {
            if (board.chooseAiColor(player, TabletopGamesBoard.BLACK)) {
                board.reopenMenu(player);
            }
        });
        form.add("退出棋局", () -> board.leave(player.getUniqueId()));
    }

    /**
     * 「开始棋局」相关按钮。统一黑棋先手，因此不再提供先后手选择。
     * <p>
     * 预设金额 &gt; 0 时：点击「开始棋局」直接按该金额双方各扣款并开局，「继承棋局」不出现。
     * 金额 = 0（免费局）时：正常开局。
     */
    private static void addStartButtons(Simple form, Player player, TabletopGamesBoard board) {
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
        // 继承棋局：仅休闲模式提供（Swap2 开局规则复杂，不做继承），
        // 且棋盘上需已有棋子、上一局未分胜负，避免空盘继承或重复获胜/领奖
        if (board.effectiveRule() == TabletopGamesBoard.RULE_CASUAL
                && board.hasStones() && !board.isFinished()) {
            form.add("开始棋局（继承棋局）", board::resume);
        }
    }

    /**
     * 「设置每局金额」入口：仅「0 人入座」时出现。金额 = 双方各自扣款额，填 0 即免费局。
     * <p>
     * 经济插件是强依赖（plugin.yml 的 {@code depend}），缺失时本插件不会被加载，
     * 因此这里无需再判断经济插件是否可用。
     */
    private static void addBetAmountButton(Simple form, Player player, TabletopGamesBoard board) {
        form.add("设置每局金额 - 当前：" + money(board.getPresetBet()), () -> {
            if (checkMenu(player, board)) {
                board.later(() -> openPresetBetInput(player, board));
            }
        });
    }

    /** 更改规则入口：仅「0 人入座」时出现，排在尾部功能区首位。 */
    private static void addRuleButton(Simple form, Player player, TabletopGamesBoard board) {
        form.add("更改规则", () -> {
            if (checkMenu(player, board)) {
                board.later(() -> openRuleMenu(player, board));
            }
        });
    }

    /** 规则选择表单：只显示当前规则，详细说明见「关于规则」；选中后自动回到上一级菜单。 */
    private static void openRuleMenu(Player player, TabletopGamesBoard board) {
        Simple form = new Simple("五子棋 [Beta] · 更改规则",
                "§7当前规则：§f" + TabletopGamesBoard.ruleName(board.getRule()));
        form.add("休闲模式", () -> selectRule(player, board, TabletopGamesBoard.RULE_CASUAL));
        form.add("Swap2", () -> selectRule(player, board, TabletopGamesBoard.RULE_SWAP2));
        form.onClose(() -> board.reopenMenu(player));
        form.show(player);
    }

    /**
     * 「关于规则」入口：仅「0 人入座」时出现。
     * <p>
     * 点进去即视作离开「0 人入座」页面：立即释放菜单占用锁，其他人可正常操作棋盘；
     * 该表单也不再受超时兜底影响（占用锁已释放），关闭后不会返回上一级。
     */
    private static void addRuleInfoButton(Simple form, Player player, TabletopGamesBoard board) {
        form.add("关于规则", () -> {
            if (checkMenu(player, board)) {
                board.releaseMenu(player);
                board.later(() -> openRuleInfo(player, board));
            }
        });
    }

    /**
     * 「全局配置」入口：仅 OP 在「0 人入座」菜单可见，排在最后一行。
     * <p>
     * 点击后释放菜单占用锁并打开插件设置表单，关闭后不返回上一级（与「关于规则」一致）。
     */
    private static void addSettingsButton(Simple form, Player player, TabletopGamesBoard board) {
        if (!player.isOp()) {
            return;
        }
        form.add("全局配置", () -> {
            if (checkMenu(player, board)) {
                board.releaseMenu(player);
                board.later(() -> SettingsMenu.open(player, BoardKind.GOMOKU));
            }
        });
    }

    /** 「关于规则」表单：只展示说明文字，仅一个「退出」按钮，关闭后不返回上一级。 */
    private static void openRuleInfo(Player player, TabletopGamesBoard board) {
        Simple form = new Simple("五子棋 [Beta] · 关于规则",
                "§7当前规则：§f" + TabletopGamesBoard.ruleName(board.getRule()) + "\n\n"
                        + "§f【休闲模式】\n"
                        + "§7· 标准五子棋，黑先白后，一人一手；\n"
                        + "§7· 任意一方在横、竖、斜任一方向连成 5 子即胜；\n"
                        + "§7· 长连（6 子及以上）同样算胜。\n\n"
                        + "§f【Swap2】\n"
                        + "§7· 开局由「假先手」在盘面任意位置摆 3 子，顺序为先黑、再白、再黑；\n"
                        + "§7· 随后「假后手」三选一：继续执白 / 交换执黑 / 再落 2 子（先白后黑）；\n"
                        + "§7· 若选择再落 2 子，则由「假先手」二选一：继续执黑 / 交换执白；\n"
                        + "§7· 开局结束后回归一人一手，由白方落下一子；\n"
                        + "§7· 长连（6 子及以上）对双方都不算胜，对局继续。\n\n"
                        + "§f【角色与选色】\n"
                        + "§7· 选黑者 = 假先手（摆 3 子），选白者 = 假后手（做选择）；\n"
                        + "§7· 单人对战时你选白，则由机器人执黑先摆 3 子，由你来做选择。");
        // 仅一个「退出」按钮：关闭后不返回上一级（进入本表单时已释放 0 人菜单占用）
        form.add("退出", () -> {
        });
        form.show(player);
    }

    private static void selectRule(Player player, TabletopGamesBoard board, int rule) {
        if (!checkMenu(player, board)) {
            return;
        }
        board.setRule(rule);
        board.reopenMenu(player);
    }

    /**
     * Swap2 开局选择表单：假后手（阶段 2）或假先手（阶段 4）做出选择。
     * 误触关闭后不自动重开，玩家可再次点击棋盘重新打开（见 {@code TabletopGamesManager#useBoard}）。
     */
    public static void openSwapDecision(Player player, TabletopGamesBoard board) {
        int phase = board.getSwapPhase();
        if (phase != 2 && phase != 4) {
            return;
        }
        Simple form = new Simple("五子棋 [Beta] · Swap2 开局", board.swapPrompt());
        if (phase == 2) {
            form.add("继续执白", () -> applySwap(board, board::swapTakeWhite));
            form.add("交换执黑", () -> applySwap(board, board::swapTakeBlack));
            form.add("落下 2 子", () -> applySwap(board, board::swapPlaceTwo));
        } else {
            form.add("继续执黑", () -> applySwap(board, board::swapKeepBlack));
            form.add("交换执白", () -> applySwap(board, board::swapTakeWhiteFinal));
        }
        form.show(player);
    }

    private static void applySwap(TabletopGamesBoard board, BooleanSupplier action) {
        board.later(action::getAsBoolean);
    }

    /** 棋色选择按钮：选色后即入座，后续由「单人对战」或对手加入推进。 */
    private static void addColorButtons(Simple form, Player player, TabletopGamesBoard board) {
        form.add("选择执白方", () -> {
            if (checkMenu(player, board)) {
                joinColor(player, board, TabletopGamesBoard.WHITE);
            }
        });
        form.add("选择执黑方", () -> {
            if (checkMenu(player, board)) {
                joinColor(player, board, TabletopGamesBoard.BLACK);
            }
        });
    }

    /**
     * 入座按钮动作。
     * <p>
     * 入座成功后 {@link TabletopGamesBoard#join} 会统一刷新所有座位玩家的表单，
     * 因此这里不再自行重开（避免对同一玩家弹两次表单）：点击后菜单不关闭，
     * 直接跳转到对应的等待界面；失败（棋色被抢/座位已满）说明表单已过时，
     * 重开一次让玩家看到最新状态。
     */
    private static void joinColor(Player player, TabletopGamesBoard board, int color) {
        if (!board.join(player, color)) {
            board.reopenMenu(player);
        }
    }

    /**
     * 单人对战按钮。点击后玩家立即入座（占一个座位），未选色时暂存机器人等级，
     * 并立即重新弹出菜单，形成「选对手 → 选棋色 → 开始棋局」的连续表单。
     * <p>
     * 入座后该棋盘对其他人即为满员，不再受 0 人菜单的占用锁与超时兜底影响。
     */
    private static void addAiButtons(Simple form, Player player, TabletopGamesBoard board) {
        for (int i = 0; i < AI_LEVELS.length; i++) {
            final int level = i + 1;
            form.add("单人对战（" + AI_LEVELS[i] + "）", () -> {
                if (!checkMenu(player, board)) {
                    return;
                }
                board.chooseAi(player, level);
                // 成功进入下一步「选棋色」表单；失败说明表单已过时，同样重开让玩家看到当前状态
                board.reopenMenu(player);
            });
        }
    }

    /** 「每局金额」输入表单；直接关闭时回到棋盘菜单。 */
    private static void openPresetBetInput(Player player, TabletopGamesBoard board) {
        double min = board.getMinBet();
        double max = board.getMaxBet();
        new Custom("五子棋 [Beta] · 每局金额")
                .label("请输入本局双方各自下注的金额（0，或 " + money(min) + " ~ " + money(max)
                        + "，最多两位小数）\n"
                        + "· 0：免费局，不扣款；仅此时才能选择单人对战。\n"
                        + "· >0：开局时双方各扣除该金额；获胜方获得奖池扣除官方抽水后的奖金，平局则全额退还。")
                .input("每局金额", money(board.getPresetBet()))
                .submit("确认")
                .onSubmit(text -> board.later(() -> handlePresetBet(player, board, text)))
                .onClose(() -> board.reopenMenu(player))
                .show(player);
    }

    /** 校验并保存「每局金额」（仅 0 人入座、持有菜单占用权时可设；0 表示免费局）。 */
    private static void handlePresetBet(Player player, TabletopGamesBoard board, String text) {
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
    private static void promptRetry(Player player, TabletopGamesBoard board, String message) {
        Simple prompt = new Simple("五子棋 [Beta] · 提示", message);
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