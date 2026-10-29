package com.tabletopgames.ui;

import cn.nukkit.Player;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.ui.Custom;
import com.tabletopgames.ui.Simple;
import com.tabletopgames.ai.UnoAi;
import com.tabletopgames.game.UnoTable;

import java.util.Locale;
import java.util.UUID;

/**
 * UNO 牌桌菜单：玩家在表单里<b>自选座位</b>（东/南/西/北 = P1..P4），
 * 也可以按三档补充 / 清除人机。
 *
 * <p>状态机（对应 {@link UnoTable.TablePhase}）：
 * <ul>
 *     <li>{@code WAITING} 未入座（0 人入座）：4 个「坐X位」按钮（只列空位）+「设置每局倍数 - 当前：n」
 *     （0 人入座时才能设置，设置期间持有单人菜单锁，设置完返回本表单）
 *     +「UNO 规则」+（仅 OP）「全局配置」；不再提供「加人机」三档（加人机走已入座菜单）；</li>
 *     <li>{@code WAITING} 已入座：「加人机」（<b>只有桌主可见</b>；免费局 k = 0 是三档选择，
 *     倍数 ≠ 0 的局只有一个「加人机」＝高级机器人，且要求已有 ≥ 2 位真人）+「开始游戏 / 下一局」
 *     （<b>人数 ≥ 2 且本人是桌主（最先加入者）才显示</b>，夹在「加人机 · 高级」与「清除人机」之间；
 *     <b>开过一局的牌桌按钮名改为「下一局」</b>——只在人事变动后的回退流程里出现）
 *     +「清除人机」（有机器人时，<b>同样只有桌主可见</b>）+「退出牌桌」；非桌主的在座玩家只看到「退出牌桌」；
 *     不再提供「设置倍数」（倍数只能在 0 人入座时设定）；</li>
 *     <li>{@code DEALER_DRAW} / {@code PLAYING}：不弹菜单（开局时已统一关闭），
 *     点牌桌直接走世界内按钮；</li>
 *     <li>{@code SETTLED}：「下一局」（= 确认续局；所有在线真人都确认后自动开局，
 *     赢家连庄、免定庄）+「托管 / 取消托管」+「退出牌桌」。</li>
 * </ul>
 *
 * <p>托管按钮只在对局开始后提供（需求 2026-10-16：入座阶段不显示）；每局结束自动取消托管，
 * 下一局需要重新点（见 {@code UnoTable#clearTrustOnSettle}）。
 *
 * <p>菜单不提供「刷新」按钮：入座 / 加人机 / 清除人机 / 离座都会经
 * {@link com.tabletopgames.game.UnoManager#refreshSeatMenus} 自动刷新在座玩家的表单，
 * 与四种棋盘（五子棋 / 象棋 / 黑白棋 / 围棋）同一套「有人入座即自动刷新」的机制。
 *
 * <p>0 人入座的菜单是独占的（{@code menuLockSeconds}，默认 30 秒）：打开时校验占用权，
 * 每个按钮动作执行前再用 {@link UnoTable#holdsMenu} 校验一次并续期，
 * 保证第二个人不能同时操作，超时或被接管后旧表单立即失效。
 */
public final class UnoMenu {

    /**
     * 人机档位名（下标即档位号：0 = {@link UnoAi#LEVEL_EASY}、1 = {@link UnoAi#LEVEL_MASTER}）。
     *
     * <p>2026-10-22 用户要求删掉「中级」，所以这里只有两项；下标必须与 UnoAi 的常量对齐，
     * 不能只删字符串 —— 否则点「高级」会加成初级。</p>
     */
    private static final String[] AI_LEVELS = {"初级", "高级"};

    /**
     * 当前<b>正开着本桌菜单</b>的玩家 → 牌桌。
     * <p>
     * 需求 2026-10-18：结算后不再自动弹窗（「下一局」由玩家自己点桌面方块 / 桌边按钮弹出），
     * 于是 {@link com.tabletopgames.game.UnoManager#refreshSeatMenus} 必须先知道谁的表单是开着的，
     * 只给这些人做「关掉旧表单 → 重弹新表单」的刷新；否则每次座位变化都会强行给所有人弹窗。
     */
    private static final java.util.Map<UUID, UnoTable> SHOWING = new java.util.HashMap<>();

    private UnoMenu() {
    }

    /** 该玩家此刻是否正开着<b>这张牌桌</b>的菜单（别的表单 / 别的牌桌都不算）。 */
    public static boolean isShowing(Player player, UnoTable table) {
        return player != null && SHOWING.get(player.getUniqueId()) == table;
    }

    /** 关闭玩家当前打开的表单（若有）。 */
    public static void closeCurrentForm(Player player) {
        SHOWING.remove(player.getUniqueId());
        if (!player.formWindows.isEmpty()) {
            player.closeFormWindows();
        }
    }

    public static void open(Player player, UnoTable table) {
        closeCurrentForm(player);
        UUID id = player.getUniqueId();
        int mySeat = table.seatOf(id);
        // 0 人入座时菜单独占
        if (mySeat < 0 && table.occupiedSeats() == 0 && !table.tryAcquireMenu(player)) {
            player.sendTip("§c有人正在操作该牌桌，请稍候。");
            return;
        }
        Simple form = new Simple("UNO [Beta]", table.statusText(player));
        if (mySeat >= 0) {
            buildSeated(form, player, table);
        } else {
            buildVisitor(form, player, table);
        }
        if (form.buttonCount() == 0) {
            return;
        }
        // 关闭回调统一在这里注册：既要释放「0 人入座」的菜单占用权，也要把 SHOWING 里的记录
        // 抹掉（玩家按 ESC 关掉表单后，refreshSeatMenus 就不该再给他重弹 —— 需求 2026-10-18）。
        form.onClose(() -> {
            SHOWING.remove(id);
            if (table.occupiedSeats() == 0) {
                table.releaseMenu(player);
            }
        });
        SHOWING.put(id, table);
        form.show(player);
    }

    /** 从子表单返回上级菜单：仅在仍持有「0 人入座」菜单占用权时重新弹出。 */
    public static void reopen(Player player, UnoTable table) {
        if (table.occupiedSeats() == 0 && !table.holdsMenu(player)) {
            return;
        }
        open(player, table);
    }

    /** 0 人入座菜单的动作级校验。 */
    private static boolean checkMenu(Player player, UnoTable table) {
        if (table.holdsMenu(player)) {
            return true;
        }
        player.sendTip("§c该菜单已失效或被他人接管，请重新点击牌桌。");
        return false;
    }

    // ------------------------------------------------------------------
    // 未入座
    // ------------------------------------------------------------------

    private static void buildVisitor(Simple form, Player player, UnoTable table) {
        if (table.occupiedSeats() >= 4) {
            player.sendTip("§c座位已满（4 人），无法加入。");
            return;
        }
        for (int s = 0; s < 4; s++) {
            if (table.isOccupied(s)) {
                continue;
            }
            final int seat = s;
            form.add("坐" + UnoTable.seatLabel(seat), () -> {
                if (!checkMenu(player, table)) {
                    return;
                }
                String err = table.join(player, seat);
                if (err != null) {
                    player.sendTip("§c" + err);
                    table.laterOpen(player);
                }
            });
        }
        // 0 人入座：本表单不再提供「加人机」三档（需求 2026-10-16；加人机走已入座菜单），
        // 只留「设置倍数」——且只在 0 人入座时可设，设置期间持有单人菜单锁，设置完返回本表单。
        if (table.occupiedSeats() == 0) {
            form.add("设置每局倍数 - 当前：" + fmt(table.getCoefficient()), () -> {
                if (checkMenu(player, table)) {
                    table.laterOpen(() -> openCoefficientInput(player, table));
                }
            });
        }
        // 需求 2026-10-18：增删人机只留给桌主，访客菜单不再提供（见 addBotButtons / addClearBotsButton）。
        addRuleButton(form, player, table);
        addSettingsButton(form, player, table);
    }

    // ------------------------------------------------------------------
    // 已入座
    // ------------------------------------------------------------------

    private static void buildSeated(Simple form, Player player, UnoTable table) {
        UnoTable.TablePhase phase = table.getPhase();
        if (phase == UnoTable.TablePhase.WAITING) {
            addBotButtons(form, player, table);
            // 「开始游戏 / 下一局」夹在「加人机 · 高级」与「清除人机」中间（需求原话）；
            // 座位满时没有加人机按钮，于是它自然落在「清除人机」上面。
            // 2026-10-17 需求：只有「上一局完整打完、且期间没人退出（清机器人也算）」才叫
            // 「下一局」，否则叫「开始游戏」（判据见 UnoTable#waitingButtonLabel）。
            if (table.occupiedSeats() >= 2 && table.isFirstJoiner(player.getUniqueId())) {
                form.add(table.waitingButtonLabel(), () -> {
                    String err = table.startRound();
                    if (err != null) {
                        player.sendTip("§c" + err);
                    }
                });
            }
            addClearBotsButton(form, player, table);
            // 「设置倍数」只在 0 人入座表单里（需求 2026-10-16）：入座后不再提供。
        } else if (phase == UnoTable.TablePhase.SETTLED) {
            // 结算页的「下一局」= 确认续局（需求 2026-10-16）：所有在线真人确认后自动开局
            // （赢家连庄、免定庄）；再点一次可取消确认。世界内按钮同此入口。
            boolean voted = table.hasVotedNext(table.seatOf(player.getUniqueId()));
            form.add(voted ? "已确认「下一局」（再点取消）" : "下一局", () -> {
                String err = table.voteNext(player);
                if (err != null) {
                    player.sendTip("§c" + err);
                }
            });
        }
        // 托管按钮只在对局开始后提供（需求 2026-10-16「入座的那个阶段不要有」）：
        // WAITING 不显示；每局结束会自动取消托管，下一局需要重新点（UnoTable#clearTrustOnSettle）。
        if (phase != UnoTable.TablePhase.WAITING) {
            form.add(table.isTrusted(table.seatOf(player.getUniqueId())) ? "取消托管" : "托管（系统代打）",
                    () -> {
                        String err = table.toggleTrust(player);
                        if (err != null) {
                            player.sendTip("§c" + err);
                        }
                    });
        }
        form.add("退出牌桌", () -> table.leave(player));
        // 不再提供「刷新」按钮：座位变化（入座 / 加人机 / 清除人机 / 离座）时
        // UnoManager#refreshSeatMenus 会自动关旧表单再重弹，与四种棋盘同一套机制。
    }

    // ------------------------------------------------------------------
    // 共用按钮
    // ------------------------------------------------------------------

    /**
     * 「加人机」：每点一次往下一个空位补一个机器人。
     * <p><b>只有桌主</b>能看到和操作（需求 2026-10-18）。
     * <p>2026-10-19 需求：<b>倍数 ≠ 0 的有钱局也能补</b>，但只在「已有 ≥ 2 位真人」时出现，
     * 且只给一个「加人机」按钮 —— 默认加的是<b>高级</b>机器人，但按钮不对外说明档位
     * （这种局里机器人名字也一律只叫「机器人n」，不暴露档位）。免费局仍是原来的三档选择。
     */
    private static void addBotButtons(Simple form, Player player, UnoTable table) {
        if (!table.isFirstJoiner(player.getUniqueId())) {
            return;
        }
        if (table.occupiedSeats() >= 4) {
            return;
        }
        if (table.getCoefficient() == 0) {
            for (int i = 0; i < AI_LEVELS.length; i++) {
                final int level = i;
                form.add("加人机 · " + AI_LEVELS[i], () -> {
                    String err = table.addBot(player, level);
                    if (err != null) {
                        player.sendTip("§c" + err);
                    }
                });
            }
            return;
        }
        // 倍数 ≠ 0：真人不足 2 位时连按钮都不出现（防「1 真人 + 人机」凭空刷币）。
        if (table.humanCount() < 2) {
            return;
        }
        form.add("加人机", () -> {
            String err = table.addBot(player, UnoAi.LEVEL_MASTER);
            if (err != null) {
                player.sendTip("§c" + err);
            }
        });
    }

    /** 「清除人机」：同样<b>只有桌主</b>能操作（需求 2026-10-18）。 */
    private static void addClearBotsButton(Simple form, Player player, UnoTable table) {
        if (!table.hasBots() || !table.isFirstJoiner(player.getUniqueId())) {
            return;
        }
        form.add("清除人机", () -> {
            String err = table.clearBots(player);
            if (err != null) {
                player.sendTip("§c" + err);
            }
        });
    }

    /**
     * 「UNO 规则」入口：仅「0 人入座」时出现。
     * <p>点进去即视作离开「0 人入座」页面：立即释放菜单占用锁，其他人可正常操作牌桌。
     */
    private static void addRuleButton(Simple form, Player player, UnoTable table) {
        form.add("UNO 规则", () -> {
            if (checkMenu(player, table)) {
                table.releaseMenu(player);
                table.laterOpen(() -> openRule(player, table));
            }
        });
    }

    /** 「全局配置」入口：仅 OP 在「0 人入座」菜单可见。 */
    private static void addSettingsButton(Simple form, Player player, UnoTable table) {
        if (!player.isOp()) {
            return;
        }
        form.add("全局配置", () -> {
            if (checkMenu(player, table)) {
                table.releaseMenu(player);
                table.laterOpen(() -> SettingsMenu.open(player, BoardKind.UNO));
            }
        });
    }

    // ------------------------------------------------------------------
    // 子表单
    // ------------------------------------------------------------------

    /** 「UNO 规则」表单：只展示说明文字，仅一个「退出」按钮，关闭后不返回上一级。 */
    private static void openRule(Player player, UnoTable table) {
        Simple form = new Simple("UNO [Beta] · 规则",
                "§f【目标】\n"
                        + "§7· 谁先打完手上的牌谁获胜，其余各家按剩余手牌计分；\n"
                        + "§7· 每人起手 7 张，中间牌堆与弃牌堆各一堆。\n\n"
                        + "§f【出牌】\n"
                        + "§7· 只能出与当前颜色相同、或与顶牌数字/功能相同的牌；\n"
                        + "§7· 无牌可出就摸一张，摸到的牌若能出可直接打出，也可以选择跳过；\n"
                        + "§7· 野牌（Wild / Wild+4）可随时打出，但需先指定颜色。\n\n"
                        + "§f【功能牌】\n"
                        + "§7· 跳过：下家停一轮；\n"
                        + "§7· 反转：出牌方向反向（2 人局等同跳过）；\n"
                        + "§7· +2：下家摸 2 张并停一轮；\n"
                        + "§7· Wild+4：指定颜色，下家摸 4 张并停一轮（手中还有当前颜色时不可出）。\n\n"
                        + "§f【座次与先手】\n"
                        + "§7· 座位东/南/西/北对应 P1/P2/P3/P4，可自选；\n"
                        + "§7· 开场每人抽一张比大小定庄（功能牌与野牌算 0，并列最大则重抽）；\n"
                        + "§7· 无人离座 / 无人机变动时：上一局赢家连庄，下一局不再定庄；\n"
                        + "§7· 庄家左手边先手，顺时针轮转。\n\n"
                        + "§f【UNO】\n"
                        + "§7· 出到只剩最后一张牌时自动喊 UNO；\n"
                        + "§f【结算】\n"
                        + "§7· 一局结束后不自动收档：所有真人玩家点「下一局」后自动续局；\n"
                        + "§7· 期间任何人入座 / 离座 / 增删人机，则收档回到等待阶段，由在座玩家点按钮开局；\n"
                        + "§7· 按钮名：上一局完整打完且没人退出（清机器人也算退出）才叫「下一局」，否则叫「开始游戏」；\n"
                        + "§7· 各家按剩余手牌分 × 本桌倍数 k 付给赢家；\n"
                        + "§7· k 只能填 0 或区间内的整数（默认 10~1000，可全局配置）；k = 0 为免费局，仅此时才能加人机；\n"
                        + "§7· 扣款「扣到零为止」：应付超过余额时只扣到 0，余额不会变负；\n"
                        + "§7· 各家§f实扣额§7先合计成奖池，再整体抽水，税后金额归赢家；\n"
                        + "§7· k > 0 时入座要求每位玩家余额大于等于「门槛系数 × k」（默认系数 10）。");
        form.add("退出", () -> {
        });
        form.show(player);
    }

    /** 倍数 k 输入表单；直接关闭时回到牌桌菜单。 */
    private static void openCoefficientInput(Player player, UnoTable table) {
        double[] range = table.coefficientRange();
        new Custom("UNO [Beta] · 倍数")
                .label("请输入本桌每局倍数 k（0，或 " + (long) range[0] + " ~ " + (long) range[1]
                        + " 的整数；区间可在「全局配置」里调整）\n"
                        + "· k = 0：免费局，不扣钱、也没有抽水；\n"
                        + "· k > 0：赢家收「其余各家剩余手牌分 × k」的实扣合计，再扣官方抽水。")
                .input("倍数 k", fmt(table.getCoefficient()))
                .submit("确认")
                .onSubmit(text -> table.laterOpen(() -> handleCoefficient(player, table, text)))
                .onClose(() -> table.laterOpen(player))
                .show(player);
    }

    private static void handleCoefficient(Player player, UnoTable table, String text) {
        Double k = parseAmount(text);
        if (k == null) {
            double[] range = table.coefficientRange();
            player.sendTip("§c倍数格式不正确：只能输入 0 或 " + (long) range[0] + " ~ "
                    + (long) range[1] + " 的整数。");
            table.laterOpen(player);
            return;
        }
        String err = table.setCoefficient(player, k);
        if (err != null) {
            player.sendTip("§c" + err);
        }
        table.laterOpen(player);
    }

    /** 解析倍数：只接受非负整数（不接受小数、符号）；上限由 {@link UnoTable#setCoefficient} 按配置区间校验。 */
    private static Double parseAmount(String text) {
        if (text == null || !text.trim().matches("\\d+")) {
            return null;
        }
        try {
            return (double) Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(double value) {
        return value == Math.rint(value)
                ? Long.toString((long) Math.rint(value))
                : String.format(Locale.ROOT, "%.2f", value);
    }
}
