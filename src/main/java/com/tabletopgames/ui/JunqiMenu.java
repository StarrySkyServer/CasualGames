package com.tabletopgames.ui;

import cn.nukkit.Player;
import com.tabletopgames.ai.junqi.JunqiAi;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.game.JunqiBoard;
import com.tabletopgames.game.JunqiRules;
import com.tabletopgames.storage.JunqiFormationDao;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 军旗棋盘菜单：状态机与象棋/斗兽棋保持一致。
 *
 * <ul>
 *     <li><b>0 人入座</b>：选择执红 / 选择执黑 +「设置每局金额 - 当前：n」
 *     + 军旗规则 +（仅 OP 可见的）全局配置</li>
 *     <li><b>已选单人对战、等待选色（本人）</b>：选择执红方 / 选择执黑方 + 退出棋局</li>
 *     <li><b>1 人入座（本人）</b>：每局金额 = 0 时才提供三档单人对战 + 退出棋局</li>
 *     <li><b>1 人入座（其他人）</b>：加入对方棋色（对方已开单人对战则视为满员）</li>
 *     <li><b>2 人入座（先加入者）</b>：开始棋局
 *     +（开过局但未分胜负时的）继承残局
 *     +（人机局）退出单人对战 + 退出棋局</li>
 *     <li><b>2 人入座（后加入者）</b>：退出棋局</li>
 *     <li><b>摆棋阶段</b>：不自动弹，点棋盘空白处打开布阵菜单</li>
 *     <li><b>对局进行中</b>：不弹菜单</li>
 * </ul>
 *
 * <p>0 人入座的菜单是独占的：打开时校验占用权，每个按钮动作执行前再用
 * {@link JunqiBoard#holdsMenu} 校验一次并续期，保证超时或被他人接管后旧表单不再生效。</p>
 */
public final class JunqiMenu {

    private static final String[] AI_LEVELS = {"入门", "进阶", "大师"};
    /** 「我的布阵」每页条数。 */
    private static final int FORMATIONS_PER_PAGE = 10;

    private JunqiMenu() {
    }

    /** 关闭玩家当前打开的表单（若有）。 */
    public static void closeCurrentForm(Player player) {
        if (!player.formWindows.isEmpty()) {
            player.closeFormWindows();
        }
    }

    public static void open(Player player, JunqiBoard board) {
        UUID id = player.getUniqueId();
        int myColor = board.seatColor(id);
        if (board.getPhase() == JunqiBoard.Phase.PLAY) {
            if (myColor < 0) {
                return;
            }
            closeCurrentForm(player);
            Simple form = new Simple("军旗 [Beta]", board.statusText(player));
            buildPlaying(form, player, board);
            form.show(player);
            return;
        }
        closeCurrentForm(player);
        boolean pendingMine = board.isPendingAiSeat(id);
        if (myColor < 0 && !pendingMine && board.seatCount() == 0 && !board.tryAcquireMenu(player)) {
            player.sendTip("§c有人正在操作该棋盘，请稍候。");
            return;
        }
        Simple form = new Simple("军旗 [Beta]", board.statusText(player));
        if (board.getPhase() == JunqiBoard.Phase.SETUP) {
            if (myColor >= 0) {
                buildSetup(form, player, board, myColor);
            }
        } else if (pendingMine) {
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
    public static void reopen(Player player, JunqiBoard board) {
        if (board.seatCount() == 0 && !board.holdsMenu(player)) {
            return;
        }
        open(player, board);
    }

    /** 0 人入座菜单的动作级校验。 */
    private static boolean checkMenu(Player player, JunqiBoard board) {
        if (board.holdsMenu(player)) {
            return true;
        }
        player.sendTip("§c该菜单已失效或被他人接管，请重新点击棋盘。");
        return false;
    }

    // ---------------------------------------------------------------- 已入座

    private static void buildSeated(Simple form, Player player, JunqiBoard board) {
        UUID id = player.getUniqueId();
        if (board.playerCount() >= 2) {
            if (board.isFirstJoiner(id)) {
                addStartButtons(form, player, board);
                if (board.hasBot()) {
                    form.add("退出单人对战（" + JunqiAi.levelName(board.getBotLevel() - 1) + "）", () -> {
                        if (board.exitAiBattle()) {
                            board.reopenMenu(player);
                        }
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
     * <p>预设金额 &gt; 0 时点击「开始棋局」按该金额双方各扣款；免费局显示「继承残局」入口。</p>
     */
    private static void addStartButtons(Simple form, Player player, JunqiBoard board) {
        form.add("开始棋局", () -> {
            String error = board.startSetup(player);
            if (error != null) {
                player.sendTip("§c" + error);
                board.reopenMenu(player);
            }
        });
        if (board.getPresetBet() == 0 && board.hasStarted() && !board.isFinished()) {
            form.add("继承残局", () -> {
                String error = board.inheritEndgame(player);
                if (error != null) {
                    player.sendTip("§c" + error);
                    board.reopenMenu(player);
                }
            });
        }
    }

    /** 已选择单人对战、等待选色的玩家菜单。 */
    private static void buildPendingColor(Simple form, Player player, JunqiBoard board) {
        form.add("选择执红方", () -> {
            if (!board.chooseAiColor(player, JunqiBoard.SIDE_RED)) {
                board.reopenMenu(player);
            }
        });
        form.add("选择执黑方", () -> {
            if (!board.chooseAiColor(player, JunqiBoard.SIDE_BLACK)) {
                board.reopenMenu(player);
            }
        });
        form.add("退出棋局", () -> board.leave(player.getUniqueId()));
    }

    // ---------------------------------------------------------------- 未入座

    private static void buildVisitor(Simple form, Player player, JunqiBoard board) {
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
            int taken = board.seatColor(board.getSeats().get(0));
            int free = taken == JunqiBoard.SIDE_RED ? JunqiBoard.SIDE_BLACK : JunqiBoard.SIDE_RED;
            form.add("加入" + (free == JunqiBoard.SIDE_RED ? "执红方" : "执黑方"),
                    () -> joinColor(player, board, free));
        } else {
            player.sendTip("§c座位已满（2 人），无法加入。");
        }
    }

    /** 棋色选择按钮：选色后即入座。 */
    private static void addColorButtons(Simple form, Player player, JunqiBoard board) {
        form.add("选择执红方", () -> {
            if (checkMenu(player, board)) {
                joinColor(player, board, JunqiBoard.SIDE_RED);
            }
        });
        form.add("选择执黑方", () -> {
            if (checkMenu(player, board)) {
                joinColor(player, board, JunqiBoard.SIDE_BLACK);
            }
        });
    }

    /**
     * 入座按钮动作。{@link JunqiBoard#join} 会统一刷新所有座位玩家的表单，
     * 因此成功后不再自行重开；失败说明表单已过时，重开一次让玩家看到最新状态。
     */
    private static void joinColor(Player player, JunqiBoard board, int color) {
        if (!board.join(player, color)) {
            board.reopenMenu(player);
        }
    }

    /** 单人对战按钮。点击后玩家立即入座（占一个座位），未选色时暂存机器人等级。 */
    private static void addAiButtons(Simple form, Player player, JunqiBoard board) {
        for (int i = 0; i < AI_LEVELS.length; i++) {
            final int level = i + 1;
            form.add("单人对战（" + AI_LEVELS[i] + "）", () -> {
                if (!checkMenu(player, board)) {
                    return;
                }
                if (!board.chooseAi(player, level)) {
                    board.reopenMenu(player);
                }
            });
        }
    }

    /** 「设置每局金额」入口：仅「0 人入座」时出现；填 0 即免费局。 */
    private static void addBetAmountButton(Simple form, Player player, JunqiBoard board) {
        form.add("设置每局金额 - 当前：" + money(board.getPresetBet()), () -> {
            if (checkMenu(player, board)) {
                board.later(() -> openPresetBetInput(player, board));
            }
        });
    }

    /**
     * 「军旗规则」入口：仅「0 人入座」时出现。
     * 点进去即释放 0 人菜单占用，关闭后不返回上一级。
     */
    private static void addRuleInfoButton(Simple form, Player player, JunqiBoard board) {
        form.add("军旗规则", () -> {
            if (checkMenu(player, board)) {
                board.releaseMenu(player);
                board.later(() -> openRuleInfo(player, board));
            }
        });
    }

    /** 「全局配置」入口：仅 OP 在「0 人入座」菜单可见，排在最后一行。 */
    private static void addSettingsButton(Simple form, Player player, JunqiBoard board) {
        if (!player.isOp()) {
            return;
        }
        form.add("全局配置", () -> {
            if (checkMenu(player, board)) {
                board.releaseMenu(player);
                board.later(() -> SettingsMenu.open(player, BoardKind.JUNQI));
            }
        });
    }

    // ---------------------------------------------------------------- 对局中

    /** 对局中点棋盘空白处弹出的表单：投降 / 敌方棋子倒置。 */
    private static void buildPlaying(Simple form, Player player, JunqiBoard board) {
        form.add("投降", () -> board.later(() -> openSurrenderConfirm(player, board)));
        boolean on = board.getManager().flipEnemies(player.getUniqueId());
        form.add("敌方棋子倒置（当前：" + (on ? "开" : "关") + "）", () -> {
            board.getManager().toggleFlipEnemies(player);
            board.reopenMenu(player);
        });
    }

    private static void openSurrenderConfirm(Player player, JunqiBoard board) {
        Simple form = new Simple("军旗 [Beta] · 投降", "§c确认投降？本局将直接判对手获胜。");
        form.add("确认投降", () -> {
            String error = board.surrender(player);
            if (error != null) {
                player.sendTip("§c" + error);
                board.reopenMenu(player);
            } else {
                closeCurrentForm(player);
            }
        });
        form.add("取消", () -> board.reopenMenu(player));
        form.onClose(() -> board.reopenMenu(player));
        form.show(player);
    }

    // ---------------------------------------------------------------- 摆棋

    private static void buildSetup(Simple form, Player player, JunqiBoard board, int side) {
        UUID id = player.getUniqueId();
        if (board.isConfirmed(side)) {
            form.add("取消布阵", () -> confirm(player, board, false));
        } else {
            form.add("确认布阵", () -> confirm(player, board, true));
        }
        form.add("我的布阵", () -> board.later(() -> openFormationList(player, board, 0)));
        form.add("保存布阵", () -> board.later(() -> openSaveForm(player, board, null)));
        form.add("随机布阵", () -> randomize(player, board));
        form.add("退出棋局", () -> board.leave(id));
    }

    private static void confirm(Player player, JunqiBoard board, boolean value) {
        String error = board.setConfirmed(player, value);
        if (error != null) {
            player.sendTip("§c" + error);
            board.reopenMenu(player);
            return;
        }
        player.sendTip(value ? "§a已确认布阵。" : "§e已取消布阵，可以继续调整。");
        // 确认后不再弹窗：要看状态再点棋盘中间的空白
    }

    private static void randomize(Player player, JunqiBoard board) {
        int side = board.seatColor(player.getUniqueId());
        if (board.randomizeSide(side)) {
            player.sendTip("§a已随机布阵，可直接确认或继续微调。");
        }
        board.reopenMenu(player);
    }

    // ---------------------------------------------------------------- 保存 / 我的布阵

    private static void openSaveForm(Player player, JunqiBoard board, String preset) {
        new Custom("军旗 [Beta] · 保存布阵")
                .label("§7布阵名按 Windows 文件名规范：\n"
                        + "§7· 不能含  < > : \" / \\ | ? *  或控制字符；\n"
                        + "§7· 不能以空格或句点结尾；\n"
                        + "§7· 不能用 CON / PRN / AUX / NUL / COM1-9 / LPT1-9；\n"
                        + "§7· 最多 32 个字符；每人最多保存 " + JunqiFormationDao.MAX_PER_PLAYER + " 个。")
                .input("布阵名", "", preset == null ? "" : preset)
                .submit("提交")
                .onSubmit(name -> board.later(() -> handleSaveFormation(player, board, name)))
                .onClose(() -> board.reopenMenu(player))
                .show(player);
    }

    private static void handleSaveFormation(Player player, JunqiBoard board, String name) {
        UUID id = player.getUniqueId();
        int side = board.seatColor(id);
        if (side < 0 || board.getPhase() != JunqiBoard.Phase.SETUP) {
            board.reopenMenu(player);
            return;
        }
        String error = validateFormationName(name);
        JunqiFormationDao dao = board.getManager().formations();
        List<JunqiFormationDao.Formation> saved = dao.list(id);
        if (error == null && saved.size() >= JunqiFormationDao.MAX_PER_PLAYER) {
            error = "§c最多保存 " + JunqiFormationDao.MAX_PER_PLAYER + " 个布阵，请先删除一个。";
        }
        if (error == null) {
            for (JunqiFormationDao.Formation f : saved) {
                if (f.name().equalsIgnoreCase(name)) {
                    error = "§c已有同名布阵「" + f.name() + "」，换一个名字。";
                    break;
                }
            }
        }
        int[] layout = board.exportLayout(side);
        if (error == null && layout == null) {
            error = "§c当前布阵还没摆满 25 枚，无法保存。";
        }
        if (error != null) {
            promptSaveRetry(player, board, name, error);
            return;
        }
        if (!dao.save(id, name, joinLayout(layout))) {
            promptSaveRetry(player, board, name, "§c保存失败（数据库不可用）。");
            return;
        }
        player.sendTip("§a已保存布阵「" + name + "」，可在「我的布阵」中使用。");
        board.reopenMenu(player);
    }

    /** 保存失败：确认后回到输入表单，刚才的名字作为输入提示保留。 */
    private static void promptSaveRetry(Player player, JunqiBoard board, String preset, String message) {
        Simple prompt = new Simple("军旗 [Beta] · 提示", message + "\n\n§7点「返回重新填写」会保留刚才输入的名字。");
        prompt.add("返回重新填写", () -> board.later(() -> openSaveForm(player, board, preset)));
        prompt.onClose(() -> board.reopenMenu(player));
        prompt.show(player);
    }

    /** 「我的布阵」列表：每页 10 个，非末页有「下一页」，末页有多页时显示「第一页」。 */
    private static void openFormationList(Player player, JunqiBoard board, int page) {
        UUID id = player.getUniqueId();
        if (board.seatColor(id) < 0) {
            return;
        }
        List<JunqiFormationDao.Formation> all = board.getManager().formations().list(id);
        if (all.isEmpty()) {
            Simple empty = new Simple("军旗 [Beta] · 我的布阵",
                    "§7还没有保存过布阵。\n§7打开布阵菜单点「保存布阵」，即可保存当前阵容。");
            empty.add("返回", () -> board.reopenMenu(player));
            empty.onClose(() -> board.reopenMenu(player));
            empty.show(player);
            return;
        }
        int pages = (all.size() + FORMATIONS_PER_PAGE - 1) / FORMATIONS_PER_PAGE;
        int current = Math.max(0, Math.min(page, pages - 1));
        StringBuilder content = new StringBuilder();
        content.append("§7共 ").append(all.size()).append(" 个布阵，第 ")
                .append(current + 1).append("/").append(pages).append(" 页\n")
                .append("§7点名字查看阵容，可立即使用或删除。");
        Simple form = new Simple("军旗 [Beta] · 我的布阵", content.toString());
        int from = current * FORMATIONS_PER_PAGE;
        int to = Math.min(from + FORMATIONS_PER_PAGE, all.size());
        for (int i = from; i < to; i++) {
            JunqiFormationDao.Formation f = all.get(i);
            form.add("§e" + f.name(),
                    () -> board.later(() -> openFormationDetail(player, board, f.name(), current)));
        }
        if (current < pages - 1) {
            form.add("下一页", () -> board.later(() -> openFormationList(player, board, current + 1)));
        }
        if (current == pages - 1 && current > 0) {
            form.add("第一页", () -> board.later(() -> openFormationList(player, board, 0)));
        }
        form.add("返回", () -> board.reopenMenu(player));
        form.onClose(() -> board.reopenMenu(player));
        form.show(player);
    }

    private static void openFormationDetail(Player player, JunqiBoard board, String name, int page) {
        UUID id = player.getUniqueId();
        if (board.seatColor(id) < 0) {
            return;
        }
        JunqiFormationDao.Formation formation = board.getManager().formations().get(id, name);
        if (formation == null) {
            openFormationList(player, board, page);
            return;
        }
        int[] layout = parseLayout(formation.layout());
        Simple form = new Simple("军旗 [Beta] · " + name,
                layout == null ? "§c这条布阵数据已损坏，请删除后重新保存。"
                        : renderLayout(layout) + "\n§7使用后会替换你当前半场的布阵。");
        if (layout != null) {
            form.add("使用布阵", () -> useFormation(player, board, name, layout, page));
        }
        form.add("删除布阵", () -> board.later(() -> openDeleteFormation(player, board, name, page)));
        form.add("返回", () -> board.later(() -> openFormationList(player, board, page)));
        form.onClose(() -> board.later(() -> openFormationList(player, board, page)));
        form.show(player);
    }

    private static void useFormation(Player player, JunqiBoard board, String name, int[] layout, int page) {
        int side = board.seatColor(player.getUniqueId());
        if (side < 0) {
            return;
        }
        if (!board.applyLayout(side, layout)) {
            player.sendTip("§c现在不能使用该布阵：请先取消布阵确认，且当前必须处于摆棋阶段。");
            board.later(() -> openFormationDetail(player, board, name, page));
            return;
        }
        player.sendTip("§a已使用布阵「" + name + "」。");
        board.reopenMenu(player);
    }

    private static void openDeleteFormation(Player player, JunqiBoard board, String name, int page) {
        Simple form = new Simple("军旗 [Beta] · 删除布阵",
                "§c确认删除布阵「" + name + "」？\n§7删除后不可恢复。");
        form.add("确认删除", () -> {
            if (board.getManager().formations().delete(player.getUniqueId(), name)) {
                player.sendTip("§e已删除布阵「" + name + "」。");
                board.later(() -> openFormationList(player, board, page));
            } else {
                player.sendTip("§c删除失败（数据库不可用），请稍后再试。");
                board.later(() -> openFormationDetail(player, board, name, page));
            }
        });
        form.add("取消", () -> board.later(() -> openFormationDetail(player, board, name, page)));
        form.onClose(() -> board.later(() -> openFormationDetail(player, board, name, page)));
        form.show(player);
    }

    /** Windows 文件名规则；返回 null 表示合法。 */
    private static String validateFormationName(String name) {
        if (name == null || name.isEmpty()) {
            return "§c名字不能为空。";
        }
        if (name.length() > 32) {
            return "§c名字最多 32 个字符。";
        }
        if (name.equals(".") || name.equals("..")) {
            return "§c这个名字不能用。";
        }
        if (name.endsWith(".")) {
            return "§c名字不能以英文句点结尾。";
        }
        if (name.charAt(name.length() - 1) == ' ') {
            return "§c名字不能以空格结尾。";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                return "§c名字不能包含 < > : \" / \\ | ? * 或控制字符。";
            }
        }
        String base = name;
        int dot = base.indexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        if (base.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            return "§c这是 Windows 保留名（CON/PRN/AUX/NUL/COM1-9/LPT1-9），请换一个。";
        }
        return null;
    }

    private static String joinLayout(int[] layout) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < layout.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(layout[i]);
        }
        return sb.toString();
    }

    private static int[] parseLayout(String csv) {
        if (csv == null || csv.isEmpty()) {
            return null;
        }
        String[] parts = csv.split(",");
        if (parts.length != 25) {
            return null;
        }
        int[] out = new int[25];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
                if (out[i] < 0 || out[i] >= 30) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /**
     * 把「本位坐标」布阵画成 6 行文字（前线在上，红黑视角通用）。
     *
     * <p>每格都渲染成一个「两字宽」的词，词与词之间用一个空格隔开，例如
     * {@code 炸弹 行营 团长 行营 司令}，不再首尾相连挤成一串。
     * 空格子用「·」加一个空格占位，宽度与两字词相同，行与行才对得齐。</p>
     */
    private static String renderLayout(int[] layout) {
        int[] typeAt = new int[30];
        Arrays.fill(typeAt, -1);
        for (int slot = 0; slot < 25; slot++) {
            typeAt[layout[slot]] = JunqiRules.SIDE_TYPES[slot];
        }
        StringBuilder sb = new StringBuilder("§7前线在上；行营不可放子\n\n");
        for (int localRow = 5; localRow >= 0; localRow--) {
            sb.append("§8").append(localRow + 1).append("  §f");
            for (int col = 0; col < 5; col++) {
                if (col > 0) {
                    sb.append(' ');          // 词间留一个空格，一行一行才整齐
                }
                int cell = JunqiRules.cell(11 - localRow, col);   // 行营位置上下对称，按红方判定即通用
                if (JunqiRules.isCamp(cell)) {
                    sb.append("§2行营§f");
                    continue;
                }
                int type = typeAt[localRow * 5 + col];
                sb.append(type < 0 ? "§8· §f" : "§e" + JunqiRules.TYPE_CN[type] + "§f");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 规则 / 金额

    /** 「军旗规则」表单：只展示说明文字，仅一个「退出」按钮，关闭后不返回上一级。 */
    private static void openRuleInfo(Player player, JunqiBoard board) {
        Simple form = new Simple("军旗 [Beta] · 规则",
                "§f【开始前】\n"
                        + "§7· 两人入座后，先入座的人点「开始棋局」；\n"
                        + "§7· 进入摆棋阶段后，点棋盘中间空白处打开布阵菜单。\n\n"
                        + "§f【摆棋】\n"
                        + "§7· 双方各 25 枚，只能摆在自己半场 25 个非行营点；\n"
                        + "§7· 军旗必须进大本营；地雷只能放后两行；炸弹不能放第一排；\n"
                        + "§7· 开局会自动随机布阵；点已摆的子=选中，点另一枚=互换；\n"
                        + "§7· 「保存布阵」可把当前阵容存进「我的布阵」（每人最多 30 个）；\n"
                        + "§7· 两人都「确认布阵」后自动开局。\n\n"
                        + "§f【行棋】\n"
                        + "§7· 红先黑后；点己方棋子选中，再点落点；\n"
                        + "§7· 公路上一步一格；行营的斜线也能走；\n"
                        + "§7· 铁路上可沿直线走任意格（不能拐弯、不能越子）；\n"
                        + "§7· 工兵在铁路上可以任意拐弯；\n"
                        + "§7· 地雷、军旗不能动；进了大本营的棋子也不能再动。\n\n"
                        + "§f【吃子】\n"
                        + "§7· 司令 > 军长 > 师长 > 旅长 > 团长 > 营长 > 连长 > 排长 > 工兵；\n"
                        + "§7· 同级同归于尽；炸弹与任何棋子同归于尽；\n"
                        + "§7· 工兵可以挖地雷，其它棋子碰地雷会死（地雷留下）；\n"
                        + "§7· 行营里的棋子不能被吃。\n\n"
                        + "§f【单人对战】\n"
                        + "§7· 入门：随机走，偶尔吃看得见的子；\n"
                        + "§7· 进阶：一步贪心（子力+推进+护旗）；\n"
                        + "§7· 大师：三层 Alpha-Beta + 暗棋采样（最强档）。");
        form.add("退出", () -> {
        });
        form.show(player);
    }

    /** 「每局金额」输入表单；直接关闭时回到棋盘菜单。 */
    private static void openPresetBetInput(Player player, JunqiBoard board) {
        new Custom("军旗 [Beta] · 每局金额")
                .label("请输入本局双方各自下注的金额（0，或 " + money(board.getMinBet()) + " ~ "
                        + money(board.getMaxBet()) + "，最多两位小数）\n"
                        + "· 0：免费局，不扣款；仅此时才能选择单人对战。\n"
                        + "· >0：开局时双方各扣除该金额；获胜方获得奖池扣除官方抽水后的奖金，和棋则全额退还。")
                .input("每局金额", money(board.getPresetBet()))
                .submit("确认")
                .onSubmit(text -> board.later(() -> handlePresetBet(player, board, text)))
                .onClose(() -> board.reopenMenu(player))
                .show(player);
    }

    private static void handlePresetBet(Player player, JunqiBoard board, String text) {
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
    private static void promptRetry(Player player, JunqiBoard board, String message) {
        Simple prompt = new Simple("军旗 [Beta] · 提示", message);
        prompt.add("返回重新输入", () -> board.later(() -> openPresetBetInput(player, board)));
        prompt.onClose(() -> board.reopenMenu(player));
        prompt.show(player);
    }

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
