package com.tabletopgames.ui;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.plugin.Plugin;
import com.tabletopgames.TabletopGamesPlugin;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.game.TabletopGamesManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 插件设置表单（仅 OP）：**每类棋 / 牌桌各自独立**。
 *
 * <h2>2026-10-19 改版（用户对照斗地主配置界面的两点要求）</h2>
 * <ul>
 *   <li><b>大部分数值用 {@link ElementSlider}</b>：左右拖动即可，和斗地主
 *       「每次出牌时间(秒):64」那种一致。
 *       <br>例外（要求能填任意值，滑块「按档走」做不到）：<b>抽水（百分比）</b>（要能填两位小数
 *       如 1.25）、棋类的<b>最小 / 最大下注金额</b>（1 ~ 10000000）、
 *       UNO 的<b>最小 / 最大倍数 k</b>。</li>
 *   <li><b>长说明挪进 ⓘ</b>：原来「第二次回合时间（秒，5~600，摸牌之后必须出牌/跳过）」
 *       这种超长标题会把滑块挤成一条缝；现在标题只留「第二次回合时间（秒）」，
 *       后面的解释全部塞进 {@code setTooltip(...)} —— 客户端会在那一行右侧画一个 ⓘ，
 *       鼠标/手指按住才展开，和斗地主全局配置里的效果一样。</li>
 * </ul>
 * 提交后立即生效并写回对应文件；滑块本身自带范围，输入框则在提交时校验
 * （格式 / 区间 / 跨字段一致性，例如最小下注不能大于最大下注）。
 */
public final class SettingsMenu {

    // ------------------------------------------------------------------
    // 表单元素下标（棋盘类 / UNO 各自一套）
    // ------------------------------------------------------------------
    private static final int IDX_HEAD = 0;
    private static final int IDX_RANGE = 1;
    private static final int IDX_LOCK = 2;
    private static final int IDX_CUT = 3;
    private static final int IDX_MIN_BET = 4;
    private static final int IDX_MAX_BET = 5;

    private static final int UIDX_HEAD = 0;
    private static final int UIDX_RANGE = 1;
    private static final int UIDX_LOCK = 2;
    private static final int UIDX_CUT = 3;
    private static final int UIDX_MIN_K = 4;
    private static final int UIDX_MAX_K = 5;
    private static final int UIDX_JOIN_MULT = 6;
    private static final int UIDX_TURN = 7;
    private static final int UIDX_TURN2 = 8;
    private static final int UIDX_CRAFTABLE = 9;

    // 斗地主页下标（**没有 UIDX_TURN2**：斗地主不存在「摸牌后再限时出牌」的两段时限）
    private static final int DIDX_HEAD = 0;
    private static final int DIDX_RANGE = 1;
    private static final int DIDX_LOCK = 2;
    private static final int DIDX_CUT = 3;
    private static final int DIDX_TIER = 4;
    private static final int DIDX_AI = 5;
    private static final int DIDX_JOIN_MULT = 6;
    private static final int DIDX_TURN = 7;
    private static final int DIDX_DEAL_STEP = 8;
    private static final int DIDX_MAX_MULT = 9;
    private static final int DIDX_CRAFTABLE = 10;

    // 德州扑克页下标（场次 tier 属于「每张桌子」，不进全局配置）
    private static final int PIDX_HEAD = 0;
    private static final int PIDX_RANGE = 1;
    private static final int PIDX_MIN_ENTRY = 2;
    private static final int PIDX_SB = 3;
    private static final int PIDX_BB = 4;
    private static final int PIDX_CUT = 5;
    private static final int PIDX_TURN = 6;
    private static final int PIDX_TURN2 = 7;

    // 军旗页下标（通用 5 项 + 军旗规则 3 项）
    private static final int JIDX_HEAD = 0;
    private static final int JIDX_RANGE = 1;
    private static final int JIDX_LOCK = 2;
    private static final int JIDX_CUT = 3;
    private static final int JIDX_MIN_BET = 4;
    private static final int JIDX_MAX_BET = 5;
    private static final int JIDX_CAMP = 6;
    private static final int JIDX_QUIET = 7;
    private static final int JIDX_FIRST = 8;

    /** 棋类「最小 / 最大下注金额」可填区间（用户 2026-10-19 指定）。 */
    private static final double BET_MIN = 1;
    private static final double BET_MAX = 10_000_000;
    /** UNO 倍数 k 的合法区间（用户 2026-10-19 指定）。 */
    private static final double K_MIN = 1;
    private static final double K_MAX = 10000;

    /** 棋类设置表单的提交草稿：校验失败后重开表单时用来回填（字符串保留玩家原样输入）。 */
    private record BoardDraft(double range, int lock, String cut, String minBet, String maxBet) {
    }

    /** 军旗设置表单的提交草稿：通用 5 项 + 3 个规则项。 */
    private record JunqiDraft(double range, int lock, String cut, String minBet, String maxBet,
                              boolean campDiagonal, String drawQuiet, int firstMoveIndex) {
    }

    /** UNO 设置表单的提交草稿：同上。 */
    private record UnoDraft(double range, int lock, String cut, String minK, String maxK,
                            double joinMult, int turn, int turn2, boolean craftable) {
    }

    /**
     * 斗地主设置表单的提交草稿：同上。
     *
     * <p>{@code tier} / {@code ai} 存的是<b>下拉框选项下标</b>而不是字符串 ——
     * 下拉框不可能提交非法值，存下标比存文本更省一次校验。</p>
     */
    private record DdzDraft(double range, int lock, String cut, int tier, int ai,
                            double joinMult, int turn, int dealStep, int maxMult,
                            boolean craftable) {
    }

    /** 德州扑克设置表单的提交草稿：全部按「原文本」存，校验失败时原样回填。 */
    private record PokerDraft(double range, String minEntry, String sb, String bb,
                              String cut, int turn, int turn2) {
    }

    private SettingsMenu() {
    }

    /**
     * 打开「全局配置」一级菜单（{@code /tabletopgames} 的入口）。
     *
     * <p>2026-10-22 用户要求：一级菜单只做一件事 —— 列出全部游戏，点哪个进哪个的配置。
     * 原来是直接跳五子棋，加一个游戏就要改命令，现在统一走这里。</p>
     */
    public static void open(Player player) {
        TabletopGamesManager manager = manager();
        if (manager == null) {
            player.sendTip("§c插件尚未就绪，暂时无法打开设置。");
            return;
        }
        if (!player.isOp()) {
            player.sendTip("§c只有 OP 可以修改插件设置。");
            return;
        }
        Simple form = new Simple("全局配置", "§7选一个游戏，进去改它自己的那一份配置。");
        for (BoardKind kind : BoardKind.values()) {
            form.add(kind.label(), () -> open(player, kind));
        }
        form.show(player);
    }

    /** 打开指定类别的设置表单；非 OP 玩家直接拒绝。 */
    public static void open(Player player, BoardKind kind) {
        TabletopGamesManager manager = manager();
        if (manager == null) {
            player.sendTip("§c插件尚未就绪，暂时无法打开设置。");
            return;
        }
        if (!player.isOp()) {
            player.sendTip("§c只有 OP 可以修改插件设置。");
            return;
        }
        if (kind == BoardKind.UNO) {
            openUno(player, manager);
        } else if (kind == BoardKind.DDZ) {
            openDdz(player, manager);
        } else if (kind == BoardKind.POKER) {
            openPoker(player, manager);
        } else if (kind == BoardKind.JUNQI) {
            openJunqi(player, manager);
        } else {
            openBoard(player, manager, kind);
        }
    }

    /** 解析插件主管理器（表单入口来自各处菜单，统一从插件实例取）。 */
    private static TabletopGamesManager manager() {
        Plugin plugin = Server.getInstance().getPluginManager().getPlugin("TabletopGames");
        if (plugin instanceof TabletopGamesPlugin topp) {
            return topp.getManager();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 棋类（五子棋 / 中国象棋 / 黑白棋 / 围棋）
    // ------------------------------------------------------------------

    private static void openBoard(Player player, TabletopGamesManager manager, BoardKind kind) {
        openBoard(player, manager, kind, null);
    }

    /**
     * 打开棋类设置表单。
     *
     * @param draft 上一次提交但校验没过的值；非 null 时用它回填，玩家只需改错的那一项，
     *              为 null 时各项回填配置里的当前值
     */
    private static void openBoard(Player player, TabletopGamesManager manager, BoardKind kind,
                                  BoardDraft draft) {
        TabletopGamesConfig.GameSettings config = manager.configOf(kind);
        TabletopGamesConfig.GameSettings.Betting gambling = config.getBetting();
        double rangeValue = draft == null ? config.getPlayerRange() : draft.range();
        int lockValue = draft == null ? config.getMenuLockSeconds() : draft.lock();
        String cutValue = draft == null ? trim(gambling.normalizedCut()) : draft.cut();
        String minBetValue = draft == null ? trim(gambling.normalizedMinBet()) : draft.minBet();
        String maxBetValue = draft == null ? trim(gambling.normalizedMaxBet()) : draft.maxBet();
        FormWindowCustom form = new FormWindowCustom(kind.label() + " · 全局配置");
        form.addElement(new ElementLabel(
                "§7左右拖动滑块即可调整；点右侧 §eⓘ§7 看这一项到底管什么。改完点最下面的按钮保存。"));

        addSlider(form, "玩家识别半径（格）", 3, 10, 1,
                rangeValue,
                "§e玩家识别半径\n§7玩家站多远以内会被这张棋桌认出来（能打开菜单、能落子）。\n"
                        + "§7默认 7 格，范围 3 ~ 10。调小 = 必须贴着桌子；调大 = 隔老远也能操作，"
                        + "但容易误触别的桌子。");
        addSlider(form, "菜单锁定超时（秒）", 5, 120, 1,
                lockValue,
                "§e菜单锁定超时\n§7有人打开棋桌菜单后，如果既不操作也不关掉，超过这个秒数就自动关闭，\n"
                        + "§7把菜单让给别人。默认 30 秒，人多时别调太大。");
        addInput(form, "抽水（百分比）", cutValue,
                "§e抽水比例\n§7每局结算时，从赢家到手的那笔钱里扣掉的百分比，用于回收金币。\n"
                        + "§7默认 1%。填 0 = 不抽水，赢多少拿多少。范围 0 ~ 100，"
                        + "可填两位小数（如 1.25）。");
        addInput(form, "最小下注金额", minBetValue,
                "§e最小下注金额\n§7棋桌菜单里「设置每局金额」允许填写的最小值（填 0 = 免费局，不受此限）。\n"
                        + "§7默认 100，范围 1 ~ 10000000，最多两位小数。");
        addInput(form, "最大下注金额", maxBetValue,
                "§e最大下注金额\n§7「设置每局金额」允许填写的最大值。必须 ≥ 最小下注金额，否则保存会被拒绝。\n"
                        + "§7默认 1000000，范围 1 ~ 10000000，最多两位小数。");

        form.setSubmitButtonText("保存并生效");
        form.addHandler(FormResponseHandler.withoutPlayer(
                ignored -> processBoard(player, manager, kind, form)));
        player.showFormWindow(form);
    }

    /** 校验并应用棋类提交结果；不合理时先弹确认表单，再带着刚填的值重开设置表单。 */
    private static void processBoard(Player player, TabletopGamesManager manager,
                                     BoardKind kind, FormWindowCustom form) {
        if (form.wasClosed()) {
            return;
        }
        TabletopGamesConfig.GameSettings config = manager.configOf(kind);
        TabletopGamesConfig.GameSettings.Betting gambling = config.getBetting();
        double range = slider(form, IDX_RANGE, config.getPlayerRange());
        int lock = (int) Math.round(slider(form, IDX_LOCK, config.getMenuLockSeconds()));
        Double cut = number(form, IDX_CUT, gambling.normalizedCut());
        Double minBet = number(form, IDX_MIN_BET, gambling.normalizedMinBet());
        Double maxBet = number(form, IDX_MAX_BET, gambling.normalizedMaxBet());
        BoardDraft draft = new BoardDraft(range, lock,
                rawText(form, IDX_CUT), rawText(form, IDX_MIN_BET), rawText(form, IDX_MAX_BET));
        if (cut == null || minBet == null || maxBet == null) {
            retryBoard(player, manager, kind, draft,
                    "§c抽水 / 下注金额只能填数字（可带两位小数，如 1.25）。");
            return;
        }
        if (cut < 0 || cut > 100) {
            retryBoard(player, manager, kind, draft, "§c抽水需在 0 ~ 100 之间。");
            return;
        }
        if (minBet < BET_MIN || minBet > BET_MAX || maxBet < BET_MIN || maxBet > BET_MAX) {
            retryBoard(player, manager, kind, draft,
                    "§c下注金额需在 " + trim(BET_MIN) + " ~ " + trim(BET_MAX) + " 之间。");
            return;
        }
        if (maxBet < minBet) {
            retryBoard(player, manager, kind, draft, "§c最大下注不能小于最小下注，请重新调整。");
            return;
        }
        manager.applyBoardSettings(kind, range, lock, cut, minBet, maxBet);
        player.sendTip(String.format(Locale.ROOT,
                "§a%s设置已生效并保存：半径 %s 格；菜单锁定 %d 秒；抽水 %s%%，下注 %s ~ %s。",
                kind.label(), trim(range), lock, trim(cut), trim(minBet), trim(maxBet)));
    }

    // ------------------------------------------------------------------
    // 军旗
    // ------------------------------------------------------------------

    /** 先手下拉选项（下标顺序与 {@link #firstMoveKey} 一一对应）。 */
    private static final List<String> FIRST_MOVE_OPTIONS = List.of("红先", "黑先", "随机");

    private static String firstMoveKey(int index) {
        return index == 1 ? "black" : index == 2 ? "random" : "red";
    }

    private static int firstMoveIndex(String key) {
        if ("black".equals(key)) {
            return 1;
        }
        return "random".equals(key) ? 2 : 0;
    }

    private static void openJunqi(Player player, TabletopGamesManager manager) {
        openJunqi(player, manager, null);
    }

    /** 打开军旗设置表单（通用 5 项 + 规则 3 项）；{@code draft} 非 null 时用它回填。 */
    private static void openJunqi(Player player, TabletopGamesManager manager, JunqiDraft draft) {
        TabletopGamesConfig.JunqiSettings config = manager.junqiConfig();
        TabletopGamesConfig.JunqiSettings.Rules rules = config.getRules();
        TabletopGamesConfig.GameSettings.Betting gambling = config.getBetting();
        double rangeValue = draft == null ? config.getPlayerRange() : draft.range();
        int lockValue = draft == null ? config.getMenuLockSeconds() : draft.lock();
        String cutValue = draft == null ? trim(gambling.normalizedCut()) : draft.cut();
        String minBetValue = draft == null ? trim(gambling.normalizedMinBet()) : draft.minBet();
        String maxBetValue = draft == null ? trim(gambling.normalizedMaxBet()) : draft.maxBet();
        boolean campValue = draft == null ? rules.isCampDiagonal() : draft.campDiagonal();
        String quietValue = draft == null ? String.valueOf(rules.getDrawQuietMoves()) : draft.drawQuiet();
        int firstValue = draft == null ? firstMoveIndex(rules.getFirstMove()) : draft.firstMoveIndex();
        FormWindowCustom form = new FormWindowCustom(BoardKind.JUNQI.label() + " · 全局配置");
        form.addElement(new ElementLabel(
                "§7左右拖动滑块即可调整；点右侧 §eⓘ§7 看这一项到底管什么。改完点最下面的按钮保存。"));

        addSlider(form, "玩家识别半径（格）", 3, 10, 1,
                rangeValue,
                "§e玩家识别半径\n§7玩家站多远以内会被这张棋盘认出来（能打开菜单、能落子）。\n"
                        + "§7默认 7 格，范围 3 ~ 10。调小 = 必须贴着棋盘；调大 = 隔老远也能操作，"
                        + "但容易误触别的棋盘。");
        addSlider(form, "菜单锁定超时（秒）", 5, 120, 1,
                lockValue,
                "§e菜单锁定超时\n§7有人打开棋盘菜单后，如果既不操作也不关掉，超过这个秒数就自动关闭，\n"
                        + "§7把菜单让给别人。默认 30 秒，人多时别调太大。");
        addInput(form, "抽水（百分比）", cutValue,
                "§e抽水比例\n§7每局结算时，从赢家到手的那笔钱里扣掉的百分比，用于回收金币。\n"
                        + "§7默认 1%。填 0 = 不抽水，赢多少拿多少。范围 0 ~ 100，"
                        + "可填两位小数（如 1.25）。");
        addInput(form, "最小下注金额", minBetValue,
                "§e最小下注金额\n§7棋盘菜单里「设置每局金额」允许填写的最小值（填 0 = 免费局，不受此限）。\n"
                        + "§7范围 1 ~ 10000000，最多两位小数。");
        addInput(form, "最大下注金额", maxBetValue,
                "§e最大下注金额\n§7「设置每局金额」允许填写的最大值。必须 ≥ 最小下注金额，否则保存会被拒绝。\n"
                        + "§7范围 1 ~ 10000000，最多两位小数。");
        addToggle(form, "行营斜线可走", campValue,
                "§e行营斜线\n§7开启后，棋子可以从行营（画了斜线的格子）沿斜线进出。\n"
                        + "§7默认开启，与棋盘上的斜线一致。");
        addInput(form, "无吃子判和手数", quietValue,
                "§e无吃子判和\n§7双方连续这么多手都没有吃子时判和。默认 150，填 0 = 不判和。\n"
                        + "§7只能填非负整数。");
        addDropdown(form, "先手", FIRST_MOVE_OPTIONS, firstValue,
                "§e先手\n§7摆棋完成后谁先走：红先 / 黑先 / 随机。默认红先。");

        form.setSubmitButtonText("保存并生效");
        form.addHandler(FormResponseHandler.withoutPlayer(
                ignored -> processJunqi(player, manager, form)));
        player.showFormWindow(form);
    }

    /** 校验并应用军旗提交结果；不合理时先弹确认表单，再带着刚填的值重开设置表单。 */
    private static void processJunqi(Player player, TabletopGamesManager manager,
                                     FormWindowCustom form) {
        if (form.wasClosed()) {
            return;
        }
        TabletopGamesConfig.JunqiSettings config = manager.junqiConfig();
        TabletopGamesConfig.JunqiSettings.Rules rules = config.getRules();
        TabletopGamesConfig.GameSettings.Betting gambling = config.getBetting();
        double range = slider(form, JIDX_RANGE, config.getPlayerRange());
        int lock = (int) Math.round(slider(form, JIDX_LOCK, config.getMenuLockSeconds()));
        Double cut = number(form, JIDX_CUT, gambling.normalizedCut());
        Double minBet = number(form, JIDX_MIN_BET, gambling.normalizedMinBet());
        Double maxBet = number(form, JIDX_MAX_BET, gambling.normalizedMaxBet());
        boolean camp = toggle(form, JIDX_CAMP, rules.isCampDiagonal());
        Double quiet = number(form, JIDX_QUIET, rules.getDrawQuietMoves());
        int first = dropdown(form, JIDX_FIRST, firstMoveIndex(rules.getFirstMove()), FIRST_MOVE_OPTIONS);
        JunqiDraft draft = new JunqiDraft(range, lock,
                rawText(form, JIDX_CUT), rawText(form, JIDX_MIN_BET), rawText(form, JIDX_MAX_BET),
                camp, rawText(form, JIDX_QUIET), first);
        if (cut == null || minBet == null || maxBet == null) {
            retryJunqi(player, manager, draft,
                    "§c抽水 / 下注金额只能填数字（可带两位小数，如 1.25）。");
            return;
        }
        if (cut < 0 || cut > 100) {
            retryJunqi(player, manager, draft, "§c抽水需在 0 ~ 100 之间。");
            return;
        }
        if (minBet < BET_MIN || minBet > BET_MAX || maxBet < BET_MIN || maxBet > BET_MAX) {
            retryJunqi(player, manager, draft,
                    "§c下注金额需在 " + trim(BET_MIN) + " ~ " + trim(BET_MAX) + " 之间。");
            return;
        }
        if (maxBet < minBet) {
            retryJunqi(player, manager, draft, "§c最大下注不能小于最小下注，请重新调整。");
            return;
        }
        if (quiet == null || quiet < 0 || quiet > 100000 || Math.abs(quiet - Math.rint(quiet)) > 1e-6) {
            retryJunqi(player, manager, draft, "§c无吃子判和手数需填 0 ~ 100000 的整数（0 = 不判和）。");
            return;
        }
        manager.applyBoardSettings(BoardKind.JUNQI, range, lock, cut, minBet, maxBet);
        manager.applyJunqiRules(camp, (int) Math.rint(quiet), firstMoveKey(first));
        player.sendTip(String.format(Locale.ROOT,
                "§a%s设置已生效并保存：半径 %s 格；菜单锁定 %d 秒；抽水 %s%%，下注 %s ~ %s；"
                        + "行营斜线 %s，判和 %s 手，先手 %s。",
                BoardKind.JUNQI.label(), trim(range), lock, trim(cut), trim(minBet), trim(maxBet),
                camp ? "可走" : "不可走", (int) Math.rint(quiet), FIRST_MOVE_OPTIONS.get(first)));
    }

    /** 军旗的输入不合法确认表单（同 {@link #retryBoard}）。 */
    private static void retryJunqi(Player player, TabletopGamesManager manager,
                                   JunqiDraft draft, String message) {
        Simple prompt = new Simple(BoardKind.JUNQI.label() + " · 设置未生效", message);
        Runnable back = () -> manager.runLater(() -> openJunqi(player, manager, draft));
        prompt.add("返回重新填写", back);
        prompt.onClose(back);
        prompt.show(player);
    }

    // ------------------------------------------------------------------
    // UNO 牌桌
    // ------------------------------------------------------------------

    private static void openUno(Player player, TabletopGamesManager manager) {
        openUno(player, manager, null);
    }

    /** 打开 UNO 设置表单；{@code draft} 非 null 时用它回填（见 {@link #openBoard} 的说明）。 */
    private static void openUno(Player player, TabletopGamesManager manager, UnoDraft draft) {
        TabletopGamesConfig.UnoSettings config = manager.unoConfig();
        double rangeValue = draft == null ? config.getPlayerRange() : draft.range();
        int lockValue = draft == null ? config.getMenuLockSeconds() : draft.lock();
        String cutValue = draft == null ? trim(config.normalizedCut()) : draft.cut();
        String minKValue = draft == null ? trim(config.normalizedMinCoefficient()) : draft.minK();
        String maxKValue = draft == null ? trim(config.normalizedMaxCoefficient()) : draft.maxK();
        double joinValue = draft == null ? config.normalizedJoinBalanceMultiplier() : draft.joinMult();
        int turnValue = draft == null ? config.normalizedTurnSeconds() : draft.turn();
        int turn2Value = draft == null ? config.normalizedSecondTurnSeconds() : draft.turn2();
        boolean craftValue = draft == null ? config.isCraftable() : draft.craftable();
        FormWindowCustom form = new FormWindowCustom(BoardKind.UNO.label() + " · 全局配置");
        form.addElement(new ElementLabel(
                "§7左右拖动滑块即可调整；点右侧 §eⓘ§7 看这一项到底管什么。改完点最下面的按钮保存。"));

        addSlider(form, "玩家识别半径（格）", 3, 10, 1,
                rangeValue,
                "§e玩家识别半径\n§7玩家站多远以内会被这张牌桌认出来（能打开菜单、能出牌）。\n"
                        + "§7默认 7 格，范围 3 ~ 10。调小 = 必须贴着桌子；调大 = 隔老远也能操作，"
                        + "但容易误触别的桌子。");
        addSlider(form, "菜单锁定超时（秒）", 5, 120, 1,
                lockValue,
                "§e菜单锁定超时\n§7有人打开牌桌菜单后，如果既不操作也不关掉，超过这个秒数就自动关闭，\n"
                        + "§7把菜单让给别人。默认 30 秒，人多时别调太大。");
        // ⚠️ 2026-10-19：抽水与两个倍数 k 改回**填数字**（用户反馈滑块按档走不好用，
        // 想要多少填多少）；其余连续量仍用滑块。
        addInput(form, "抽水（百分比）", cutValue,
                "§e抽水比例\n§7每局结算时，从赢家到手的那笔钱里扣掉的百分比，用于回收金币。\n"
                        + "§7默认 1%。填 0 = 不抽水，赢多少拿多少。范围 0 ~ 100，"
                        + "可填两位小数（如 1.25）。");
        addInput(form, "最小倍数 k", minKValue,
                "§e最小倍数 k\n§7入座 / 开局允许的最小倍数。范围 1 ~ 10000。\n"
                        + "§7一张桌的倍数是「第一个人坐下时」定下来的，中途不能改。");
        addInput(form, "最大倍数 k", maxKValue,
                "§e最大倍数 k\n§7允许设置的上限。范围 1 ~ 10000，必须 ≥ 最小倍数。\n"
                        + "§7结算时：每家按手牌分 × k 计算，扣到余额为 0 为止。");
        addSlider(form, "入座门槛系数", 1, 100, 1,
                joinValue,
                "§e入座门槛系数\n§7入座要求的余额 = 这个系数 × 本桌倍数 k。默认 10。\n"
                        + "§7例：k=10、系数=10 ⇒ 至少要有 100 金币才能坐下。调小 = 更容易入座。\n"
                        + "§7这里改的是**系数**，不是门槛金额本身。");
        addSlider(form, "第一次回合时间（秒）", 5, 120, 1,
                turnValue,
                "§e第一次回合时间\n§7轮到你、还没摸牌时，最多能想多久（超时自动摸牌）。默认 20 秒。\n"
                        + "§7保存后从**下一次**出牌计时开始生效，不会打断正在倒计时的那一回合。");
        addSlider(form, "第二次回合时间（秒）", 5, 120, 1,
                turn2Value,
                "§e第二次回合时间\n§7你摸完牌之后，必须出牌 / 跳过的时间。默认 15 秒。\n"
                        + "§7摸到牌以后不能再干别的，所以这一档通常比第一次短。");
        addToggle(form, "是否能合成（牌桌物品可在工作台合成）",
                craftValue,
                "§e是否能合成\n§7关掉以后，玩家只能靠 OP 发放牌桌物品，工作台里合不出来。\n"
                        + "§7服务器想控制牌桌数量时把它关掉。");

        form.setSubmitButtonText("保存并生效");
        form.addHandler(FormResponseHandler.withoutPlayer(
                ignored -> processUno(player, manager, form)));
        player.showFormWindow(form);
    }

    /** 校验并应用 UNO 提交结果；不合理时先弹确认表单，再带着刚填的值重开设置表单。 */
    private static void processUno(Player player, TabletopGamesManager manager, FormWindowCustom form) {
        if (form.wasClosed()) {
            return;
        }
        TabletopGamesConfig.UnoSettings config = manager.unoConfig();
        double range = slider(form, UIDX_RANGE, config.getPlayerRange());
        int lock = (int) Math.round(slider(form, UIDX_LOCK, config.getMenuLockSeconds()));
        Double cut = number(form, UIDX_CUT, config.normalizedCut());
        Double minK = number(form, UIDX_MIN_K, config.normalizedMinCoefficient());
        Double maxK = number(form, UIDX_MAX_K, config.normalizedMaxCoefficient());
        double joinMult = slider(form, UIDX_JOIN_MULT, config.normalizedJoinBalanceMultiplier());
        int turn = (int) Math.round(slider(form, UIDX_TURN, config.normalizedTurnSeconds()));
        int turn2 = (int) Math.round(slider(form, UIDX_TURN2, config.normalizedSecondTurnSeconds()));
        boolean craftable = form.getResponse().getToggleResponse(UIDX_CRAFTABLE);
        UnoDraft draft = new UnoDraft(range, lock, rawText(form, UIDX_CUT), rawText(form, UIDX_MIN_K),
                rawText(form, UIDX_MAX_K), joinMult, turn, turn2, craftable);
        String error = null;
        if (cut == null || minK == null || maxK == null) {
            error = "§c抽水 / 最小倍数 / 最大倍数只能填数字。";
        } else if (cut < 0 || cut > 100) {
            error = "§c抽水需在 0 ~ 100 之间。";
        } else if (minK < K_MIN || minK > K_MAX) {
            error = "§c最小倍数需在 " + (long) K_MIN + " ~ " + (long) K_MAX + " 之间。";
        } else if (maxK < K_MIN || maxK > K_MAX) {
            error = "§c最大倍数需在 " + (long) K_MIN + " ~ " + (long) K_MAX + " 之间。";
        } else if (maxK < minK) {
            error = "§c最大倍数不能小于最小倍数。";
        } else if (joinMult < 1 || joinMult > 100) {
            error = "§c入座门槛系数需在 1 ~ 100 之间。";
        }
        if (error != null) {
            retryUno(player, manager, draft, error);
            return;
        }
        manager.applyUnoSettings(range, lock, cut, joinMult, minK, maxK, craftable, turn, turn2);
        player.sendTip(String.format(Locale.ROOT,
                "§aUNO 设置已生效并保存：半径 %s 格；菜单锁定 %d 秒；抽水 %s%%；"
                        + "倍数 k %s ~ %s；入座门槛 = 系数 %s × 倍数；回合 %d 秒（未摸牌）+ %d 秒（摸牌后）；牌桌%s。",
                trim(range), lock, trim(cut), trim(minK), trim(maxK), trim(joinMult),
                turn, turn2, craftable ? "可合成" : "不可合成"));
    }

    /**
     * 输入不合法：先弹一个确认表单（不直接把设置表单弹回去），
     * 点「返回重新填写」——或直接关掉它——都会带着刚才填的值重开设置表单，
     * 玩家只需改错的那一项。表单切换延后一 tick，避免客户端窗口冲突。
     */
    private static void retryBoard(Player player, TabletopGamesManager manager, BoardKind kind,
                                   BoardDraft draft, String message) {
        Simple prompt = new Simple(kind.label() + " · 设置未生效", message);
        Runnable back = () -> manager.runLater(() -> openBoard(player, manager, kind, draft));
        prompt.add("返回重新填写", back);
        prompt.onClose(back);
        prompt.show(player);
    }

    /** UNO 的输入不合法确认表单（同 {@link #retryBoard}）。 */
    private static void retryUno(Player player, TabletopGamesManager manager,
                                 UnoDraft draft, String message) {
        Simple prompt = new Simple(BoardKind.UNO.label() + " · 设置未生效", message);
        Runnable back = () -> manager.runLater(() -> openUno(player, manager, draft));
        prompt.add("返回重新填写", back);
        prompt.onClose(back);
        prompt.show(player);
    }

    // ------------------------------------------------------------------
    // 斗地主牌桌
    // ------------------------------------------------------------------

    /** 四档底分的下拉选项（与 {@link TabletopGamesConfig.DdzTier} 顺序严格一致，下标即枚举下标）。 */
    private static final List<String> TIER_OPTIONS = buildTierOptions();
    private static final List<String> AI_OPTIONS = List.of(
            TabletopGamesConfig.DdzAi.SIMPLE.label(),
            TabletopGamesConfig.DdzAi.MEDIUM.label(),
            TabletopGamesConfig.DdzAi.HARD.label());

    private static List<String> buildTierOptions() {
        List<String> out = new ArrayList<>(TabletopGamesConfig.DdzTier.values().length);
        for (TabletopGamesConfig.DdzTier t : TabletopGamesConfig.DdzTier.values()) {
            out.add(t.label() + "（底分 " + trim(t.baseScore()) + "）");
        }
        return List.copyOf(out);
    }

    private static void openDdz(Player player, TabletopGamesManager manager) {
        openDdz(player, manager, null);
    }

    /** 打开斗地主设置表单；{@code draft} 非 null 时用它回填（校验失败重开）。 */
    private static void openDdz(Player player, TabletopGamesManager manager, DdzDraft draft) {
        TabletopGamesConfig.DdzSettings config = manager.ddzConfig();
        double rangeValue = draft == null ? config.getPlayerRange() : draft.range();
        int lockValue = draft == null ? config.getMenuLockSeconds() : draft.lock();
        String cutValue = draft == null ? trim(config.normalizedCut()) : draft.cut();
        int tierValue = draft == null ? config.normalizedTier().ordinal() : draft.tier();
        int aiValue = draft == null ? config.normalizedAiDifficulty().ordinal() : draft.ai();
        double joinValue = draft == null ? config.normalizedJoinBalanceMultiplier() : draft.joinMult();
        int turnValue = draft == null ? config.normalizedTurnSeconds() : draft.turn();
        int dealValue = draft == null ? config.normalizedDealStepMillis() : draft.dealStep();
        int multValue = draft == null ? config.normalizedMaxMultiplier() : draft.maxMult();
        boolean craftValue = draft == null ? config.isCraftable() : draft.craftable();

        FormWindowCustom form = new FormWindowCustom(BoardKind.DDZ.label() + " · 全局配置");
        form.addElement(new ElementLabel(
                "§7斗地主移除了倍数 k，改成 4 档固定底分；左右拖动滑块，点 §eⓘ§7 看说明。"));

        addSlider(form, "玩家识别半径（格）", 3, 10, 1, rangeValue,
                "§e玩家识别半径\n§7玩家站多远以内会被这张牌桌认出来（能打开菜单、能出牌）。"
                        + "\n§7默认 7 格，范围 3 ~ 10。调小 = 必须贴着桌子；调大 = 隔老远也能操作。");
        addSlider(form, "菜单锁定超时（秒）", 5, 120, 1, lockValue,
                "§e菜单锁定超时\n§7有人打开牌桌菜单后既不操作也不关掉，超过这个秒数就自动关闭。\n§7默认 30 秒。");
        // 抽水要能填两位小数 ⇒ 填数字，与 UNO 同款处理（用户 2026-10-19 反馈滑块按档走不实用）
        addInput(form, "抽水（百分比）", cutValue,
                "§e抽水比例\n§7**两段式**：入座时先实扣「底分 × 入座门槛系数」，"
                        + "结算时再从赢家应得的钱里按本比例扣一次。\n"
                        + "§7默认 1%。填 0 = 不抽水。范围 0 ~ 100，可填两位小数（如 1.25）。");
        addDropdown(form, "档位（决定底分）", TIER_OPTIONS, tierValue,
                "§e档位 / 底分\n§7斗地主没有倍数 k，底分只能在这 4 档里选：\n"
                        + "§7免费场 0（不扣钱、不结算）/ 初级场 50 / 高级场 800 / 大师场 10000。\n"
                        + "§7输赢幅度由「倍数」决定：抢地主次数 2 的幂 × 每家加倍 ×2/×4，"
                        + "炸弹与春天再 ×2（有上限）—— 与档位正交。");
        addDropdown(form, "机器人难度", AI_OPTIONS, aiValue,
                "§e机器人难度\n§7简单：只打能赢的牌，不算牌。\n"
                        + "§7普通：会拆牌、跟小牌，偶有失误。\n§7困难：算牌，会保炸弹、卡大牌。");
        addSlider(form, "入座门槛系数", 1, 100, 1, joinValue,
                "§e入座门槛系数\n§7入座要求的余额 = 这个系数 × 本档底分。默认 10。\n"
                        + "§7例：初级场底分 50 + 系数 10 ⇒ 至少要有 500 金币才能坐下。\n"
                        + "§7这就是入座时**实扣**的本金；免费场（底分 0）不受限制、也不扣钱。");
        addSlider(form, "回合时间（秒）", 5, 120, 1, turnValue,
                "§e回合时间\n§7轮到你之后必须行动的时间（出牌 / 不出 / 叫抢 / 加倍都算）。"
                        + "默认 20 秒。\n§7机器人与托管固定 2 秒思考，不受本值影响。\n"
                        + "§7⚠️ 斗地主**没有第二回合时间**：UNO 那种「先摸牌、再限时出牌」的两段时限，"
                        + "在斗地主里不存在 —— 每次轮到你的动作都是二选一。");
        addSlider(form, "发牌节奏（毫秒/张）", 20, 200, 5, dealValue,
                "§e发牌节奏\n§7每张牌之间的间隔毫秒。默认 50。\n"
                        + "§7斗地主要发 17 张：50ms × 17 = 0.85 秒发完，动画仍逐张均匀展开。\n"
                        + "§7真机觉得乱就调到 70~80；调 20 会非常快。");
        addSlider(form, "倍数上限", 16, 65536, 16, multValue,
                "§e倍数上限\n§7叫地主 / 抢地主的 2 的幂，再叠每家的加倍 ×2/×4、"
                        + "炸弹与春天 ×2，会指数增长 —— 靠本值截断防炸档。\n"
                        + "§7默认 1024。范围 16 ~ 65536（按 16 的倍数拖，实际存 2 的幂）。");
        addToggle(form, "是否能合成（牌桌物品可在工作台合成）", craftValue,
                "§e是否能合成\n§7关掉以后，玩家只能靠 OP 发放牌桌物品，工作台里合不出来。");

        form.setSubmitButtonText("保存并生效");
        form.addHandler(FormResponseHandler.withoutPlayer(
                ignored -> processDdz(player, manager, form)));
        player.showFormWindow(form);
    }

    /** 校验并应用斗地主提交结果；不合理时先弹确认表单，再带着刚填的值重开设置表单。 */
    private static void processDdz(Player player, TabletopGamesManager manager, FormWindowCustom form) {
        if (form.wasClosed()) {
            return;
        }
        TabletopGamesConfig.DdzSettings config = manager.ddzConfig();
        double range = slider(form, DIDX_RANGE, config.getPlayerRange());
        int lock = (int) Math.round(slider(form, DIDX_LOCK, config.getMenuLockSeconds()));
        Double cut = number(form, DIDX_CUT, config.normalizedCut());
        int tier = dropdown(form, DIDX_TIER, config.normalizedTier().ordinal(), TIER_OPTIONS);
        int ai = dropdown(form, DIDX_AI, config.normalizedAiDifficulty().ordinal(), AI_OPTIONS);
        double joinMult = slider(form, DIDX_JOIN_MULT, config.normalizedJoinBalanceMultiplier());
        int turn = (int) Math.round(slider(form, DIDX_TURN, config.normalizedTurnSeconds()));
        int dealStep = (int) Math.round(slider(form, DIDX_DEAL_STEP, config.normalizedDealStepMillis()));
        int maxMult = (int) Math.round(slider(form, DIDX_MAX_MULT, config.normalizedMaxMultiplier()));
        boolean craftable = form.getResponse().getToggleResponse(DIDX_CRAFTABLE);
        DdzDraft draft = new DdzDraft(range, lock, rawText(form, DIDX_CUT), tier, ai,
                joinMult, turn, dealStep, maxMult, craftable);
        String error = null;
        if (cut == null) {
            error = "§c抽水只能填数字。";
        } else if (cut < 0 || cut > 100) {
            error = "§c抽水需在 0 ~ 100 之间。";
        } else if (joinMult < 1 || joinMult > 100) {
            error = "§c入座门槛系数需在 1 ~ 100 之间。";
        }
        if (error != null) {
            retryDdz(player, manager, draft, error);
            return;
        }
        manager.applyDdzSettings(range, lock, cut, joinMult,
                TabletopGamesConfig.DdzTier.values()[tier].name(),
                TabletopGamesConfig.DdzAi.values()[ai].name(),
                craftable, turn, dealStep, maxMult);
        player.sendTip(String.format(Locale.ROOT,
                "§a斗地主设置已生效并保存：半径 %s 格；菜单锁定 %d 秒；抽水 %s%%；"
                        + "档位 %s；机器人 %s；入座门槛 = 系数 %s × 底分；回合 %d 秒；发牌 %dms/张；"
                        + "倍数上限 %d；牌桌%s。",
                trim(range), lock, trim(cut),
                TabletopGamesConfig.DdzTier.values()[tier].label(),
                TabletopGamesConfig.DdzAi.values()[ai].label(),
                trim(joinMult), turn, dealStep,
                Integer.highestOneBit(Math.max(16, Math.min(65536, maxMult))),
                craftable ? "可合成" : "不可合成"));
    }

    /** 斗地主的输入不合法确认表单（同 {@link #retryBoard}）。 */
    private static void retryDdz(Player player, TabletopGamesManager manager,
                                 DdzDraft draft, String message) {
        Simple prompt = new Simple(BoardKind.DDZ.label() + " · 设置未生效", message);
        Runnable back = () -> manager.runLater(() -> openDdz(player, manager, draft));
        prompt.add("返回重新填写", back);
        prompt.onClose(back);
        prompt.show(player);
    }

    // ------------------------------------------------------------------
    // 德州扑克
    // ------------------------------------------------------------------

    /** 德州扑克页（没有草稿 = 首次打开）。 */
    private static void openPoker(Player player, TabletopGamesManager manager) {
        openPoker(player, manager, null);
    }

    private static void openPoker(Player player, TabletopGamesManager manager, PokerDraft draft) {
        TabletopGamesConfig.PokerSettings config = manager.pokerConfig();
        double rangeValue = draft == null ? config.getPlayerRange() : draft.range();
        String entryValue = draft == null
                ? String.valueOf(config.normalizedMinEntry()) : draft.minEntry();
        String sbValue = draft == null
                ? String.valueOf(config.normalizedSmallBlind()) : draft.sb();
        String bbValue = draft == null
                ? String.valueOf(config.normalizedBigBlind()) : draft.bb();
        String cutValue = draft == null ? trim(config.getCut()) : draft.cut();
        int turnValue = draft == null ? config.normalizedTurnSeconds() : draft.turn();
        int turn2Value = draft == null ? config.normalizedSecondTurnSeconds() : draft.turn2();

        FormWindowCustom form = new FormWindowCustom(BoardKind.POKER.label() + " · 全局配置");
        form.addElement(new ElementLabel(
                "§7这里是§e整服默认值§7：新建牌桌按它初始化；已有牌桌只在§e空桌§7时跟着刷新"
                        + "（有人坐着就不动它的筹码，免得打到一半规则变了）。\n"
                        + "§7场次（免费场 / 新手场）是§e每张桌子§7各自的，在牌桌菜单的「牌桌配置」里改。"));

        addSlider(form, "玩家识别半径（格）", 3, 10, 1, rangeValue,
                "§e玩家识别半径\n§7站多远以内还能操作这张桌子。默认 7 格，范围 3 ~ 10。");
        addInput(form, "最低入场 = 每局筹码", entryValue,
                "§e最低入场\n§7每局开始时所有人的筹码都重置成这个数（局间不累积），"
                        + "所以它同时是「单局最大亏损」。\n§7新手场入座门槛也按它算。默认 1000，"
                        + "范围 100 ~ 262143。");
        addInput(form, "小盲", sbValue,
                "§e小盲\n§7每局强制下注的起点。默认 5（1000 筹码 = 100 个大盲）。");
        addInput(form, "大盲", bbValue,
                "§e大盲\n§7通常 = 小盲 × 2，必须 ≥ 小盲 × 2。默认 10。");
        addInput(form, "抽水（百分比）", cutValue,
                "§e抽水\n§7从底池里扣，赢家少拿。默认 1（即 1%）。\n"
                        + "§7免费场不抽水，只有新手场按本值抽。可填两位小数。");
        addSlider(form, "第一回合时间（秒）", 5, 120, 1, turnValue,
                "§e第一回合时间\n§7轮到你之后的行动时限（弃牌 / 过牌 / 跟注 / 全下）。默认 20 秒。");
        addSlider(form, "第二次回合时间（秒）", 5, 120, 1, turn2Value,
                "§e第二次回合时间\n§7点「加注」拖滑块那段时间，超时按最小加注算。默认 15 秒。");

        form.setSubmitButtonText("保存并生效");
        form.addHandler(FormResponseHandler.withoutPlayer(
                ignored -> processPoker(player, manager, form)));
        player.showFormWindow(form);
    }

    /** 校验并应用德州扑克提交结果；不合理时先弹确认表单，再带着刚填的值重开。 */
    private static void processPoker(Player player, TabletopGamesManager manager,
                                     FormWindowCustom form) {
        if (form.wasClosed()) {
            return;
        }
        TabletopGamesConfig.PokerSettings config = manager.pokerConfig();
        double range = slider(form, PIDX_RANGE, config.getPlayerRange());
        Double entry = number(form, PIDX_MIN_ENTRY, config.normalizedMinEntry());
        Double sb = number(form, PIDX_SB, config.normalizedSmallBlind());
        Double bb = number(form, PIDX_BB, config.normalizedBigBlind());
        Double cut = number(form, PIDX_CUT, config.getCut());
        int turn = (int) Math.round(slider(form, PIDX_TURN, config.normalizedTurnSeconds()));
        int turn2 = (int) Math.round(slider(form, PIDX_TURN2, config.normalizedSecondTurnSeconds()));
        PokerDraft draft = new PokerDraft(range,
                rawText(form, PIDX_MIN_ENTRY), rawText(form, PIDX_SB),
                rawText(form, PIDX_BB), rawText(form, PIDX_CUT), turn, turn2);
        String error = null;
        if (entry == null || sb == null || bb == null || cut == null) {
            error = "§c筹码 / 盲注 / 抽水只能填数字。";
        } else if (entry < 100 || entry > 262143) {
            error = "§c最低入场需在 100 ~ 262143 之间。";
        } else if (sb < 1 || sb > 1000) {
            error = "§c小盲需在 1 ~ 1000 之间。";
        } else if (bb < sb * 2 || bb > 2000) {
            error = "§c大盲至少是小盲的 2 倍，且不超过 2000。";
        } else if (cut < 0 || cut > 100) {
            error = "§c抽水需在 0 ~ 100 之间。";
        }
        if (error != null) {
            retryPoker(player, manager, draft, error);
            return;
        }
        manager.applyPokerSettings(range, (int) Math.round(entry), (int) Math.round(sb),
                (int) Math.round(bb), cut, turn, turn2);
        player.sendTip(String.format(Locale.ROOT,
                "§a德州扑克设置已生效并保存：半径 %s 格；每局筹码 %d；盲注 %d/%d；抽水 %s%%；"
                        + "回合 %d 秒 / 加注 %d 秒。",
                trim(range), Math.round(entry), Math.round(sb), Math.round(bb), trim(cut),
                turn, turn2));
    }

    /** 德州扑克的输入不合法确认表单（同 {@link #retryBoard}）。 */
    private static void retryPoker(Player player, TabletopGamesManager manager,
                                   PokerDraft draft, String message) {
        Simple prompt = new Simple(BoardKind.POKER.label() + " · 设置未生效", message);
        Runnable back = () -> manager.runLater(() -> openPoker(player, manager, draft));
        prompt.add("返回重新填写", back);
        prompt.onClose(back);
        prompt.show(player);
    }

    // ------------------------------------------------------------------
    // 元素构造 / 读取
    // ------------------------------------------------------------------

    /** 加一个连续滑块；范围会自动扩到能装下当前值（手改过 config.yml 也不会被滑块吞掉）。 */
    private static void addSlider(FormWindowCustom form, String label,
                                  double min, double max, double step, double value, String tip) {
        double lo = Math.min(min, Math.floor(value));
        double hi = Math.max(max, Math.ceil(value));
        float v = (float) Math.max(lo, Math.min(hi, value));
        ElementSlider slider = new ElementSlider(label, (float) lo, (float) hi, (int) step, v);
        slider.setTooltip(tip);
        form.addElement(slider);
    }

    /**
     * 加一个**填数字**的输入框：抽水（要两位小数）、棋类的最小 / 最大下注金额、
     * UNO 的最小 / 最大倍数 k 都用它。
     *
     * <p>为什么这些项不用滑块：用户明确反馈「按档走」不实用 —— 档位表里没有 7，
     * 就只能被硬塞到 5 或 8；抽水、下注金额同理。填数字可以填任意值。</p>
     */
    private static void addInput(FormWindowCustom form, String label, String value, String tip) {
        ElementInput input = new ElementInput(label, value);
        input.setTooltip(tip);
        form.addElement(input);
    }

    /**
     * 读取指定下标的输入框并解析为数值；留空 / 读不到 = 保持当前值。
     *
     * @return null 表示玩家填的不是合法数字（要提示并重开表单）
     */
    private static Double number(FormWindowCustom form, int index, double fallback) {
        String text;
        try {
            text = form.getResponse().getInputResponse(index);
        } catch (RuntimeException e) {
            return fallback;
        }
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 读输入框的**原文**（不解析），用于把玩家刚填错的内容原样带回表单；
     * 读不到（表单结构不符）时返回空串。
     */
    private static String rawText(FormWindowCustom form, int index) {
        try {
            String text = form.getResponse().getInputResponse(index);
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void addToggle(FormWindowCustom form, String label, boolean value, String tip) {
        ElementToggle toggle = new ElementToggle(label, value);
        toggle.setTooltip(tip);
        form.addElement(toggle);
    }

    /** 读开关状态；读取失败时回落到当前值。 */
    private static boolean toggle(FormWindowCustom form, int index, boolean fallback) {
        try {
            return form.getResponse().getToggleResponse(index);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * 加一个下拉框：斗地主的<b>档位</b>（4 档固定底分）与<b>机器人难度</b>（3 档）用它。
     *
     * <p>为什么这两项不用滑块也不用填数字：它们是<b>离散枚举</b>，填数字要么得记英文
     * （{@code basic}/{@code advanced}），要么能填出非法值然后回落 —— 下拉框把非法值
     * 从源头消掉，且提交的是下标，不存在解析失败。</p>
     */
    private static void addDropdown(FormWindowCustom form, String label,
                                    List<String> options, int value, String tip) {
        int def = Math.max(0, Math.min(options.size() - 1, value));
        ElementDropdown drop = new ElementDropdown(label, options, def);
        drop.setTooltip(tip);
        form.addElement(drop);
    }

    /**
     * 读下拉框选中项的下标；读不到 / 文本对不上任何选项时回落到 {@code fallback}。
     *
     * <p>⚠️ Nukkit 的 {@code FormResponseData} 只暴露 {@code getElementContent()}（选中项的
     * <b>文本</b>）、<b>没有</b> {@code getSelectedOptionIndex()}，所以只能拿文本反查下标。
     * 兜底钳制到 {@code [0, optionCount)}：表单结构若被改过（改了选项数却忘了改下标常量），
     * 越界下标会让 {@code applyDdzSettings} 的 {@code values()[tier]} 越界抛异常，
     * 那样整个设置提交会静默失败、连提示都没有。</p>
     */
    private static int dropdown(FormWindowCustom form, int index, int fallback, List<String> options) {
        String text;
        try {
            text = form.getResponse().getDropdownResponse(index).getElementContent();
        } catch (RuntimeException e) {
            text = null;
        }
        if (text != null) {
            int hit = options.indexOf(text);
            if (hit >= 0) {
                return hit;
            }
        }
        return Math.max(0, Math.min(options.size() - 1, fallback));
    }

    /** 读连续滑块；越界或读取失败时回落到当前值。 */
    private static double slider(FormWindowCustom form, int index, double fallback) {
        try {
            return form.getResponse().getSliderResponse(index);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 整数显示：1000.0 → 1000，1.5 → 1.5。 */
    private static String trim(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-6) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
