package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowCustom;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.game.poker.PokerBotAi;
import com.tabletopgames.ui.SettingsMenu;
import com.tabletopgames.ui.Simple;

/**
 * 德州扑克牌桌菜单。
 *
 * <h2>★ 按钮顺序照抄 UNO / 斗地主（2026-10-26 用户截图「对比1/对比2」定案）</h2>
 * <ul>
 *   <li><b>结算页</b>（没退人）：「下一局」→「托管（系统代打）」→「退出牌桌」——<b>就这三根</b>，
 *       不许再堆加人机/规则/配置。</li>
 *   <li><b>结算页</b>（有人退）：退化成等待期按钮组 + 托管 + 退出牌桌。</li>
 *   <li><b>等待期</b>（桌主）：加人机 → 开始游戏 / 下一局 → 清除人机 → 退出牌桌。</li>
 *   <li><b>未入座</b>：入座：X位 → 牌桌配置（空桌）→ 德州扑克规则 → 全局配置（OP）。</li>
 * </ul>
 *
 * <p>「加人机」按场次给按钮（2026-10-26 用户："照斗地主"）：</p>
 * <table>
 *   <tr><th>场次</th><th>按钮</th><th>前置</th></tr>
 *   <tr><td>免费场</td><td>「加人机 · 初级」+「加人机 · 高级」</td><td>桌主 + 有空位</td></tr>
 *   <tr><td>新手场</td><td>单个「加人机」= <b>高级</b></td><td>桌主 + <b>已满 2 位真人</b>（防刷币）</td></tr>
 * </table>
 */
public final class PokerMenu {

    private PokerMenu() {
    }

    public static void open(Player player, PokerGame table) {
        if (player == null || table == null) {
            return;
        }
        Simple form = new Simple("德州扑克", table.statusText(player));
        int seat = table.seatOf(player);
        boolean owner = table.isOwner(player);
        PokerGame.Phase phase = table.getPhase();

        if (seat >= 0) {
            buildSeated(form, player, table, seat, owner, phase);
        } else {
            buildVisitor(form, player, table);
        }
        if (form.buttonCount() > 0) {
            form.show(player);
        }
    }

    // ------------------------------------------------------------------
    // 未入座
    // ------------------------------------------------------------------

    private static void buildVisitor(Simple form, Player player, PokerGame table) {
        int free = 0;
        for (int i = 0; i < PokerGame.SEATS; i++) {
            if (table.isSeatFree(i)) {
                free++;
                final int s = i;
                form.add("入座：" + PokerGame.seatLabel(s) + "位", () -> {
                    table.sit(player, s);
                    open(player, table);
                });
            }
        }
        if (free == 0) {
            player.sendTip("§c座位已满（" + PokerGame.SEATS + " 人），无法加入。");
            return;
        }
        // 「牌桌配置」只有 0 人入座时可调（与 UNO/斗地主同款：有人坐下场次就定死）
        if (table.occupiedSeats() == 0) {
            form.add("牌桌配置（经济场次）", () -> openConfig(player, table));
        }
        form.add("德州扑克规则", () -> showRules(player, table));
        if (player.isOp()) {
            form.add("全局配置", () -> SettingsMenu.open(player, BoardKind.POKER));
        }
    }

    // ------------------------------------------------------------------
    // 已入座
    // ------------------------------------------------------------------

    private static void buildSeated(Simple form, Player player, PokerGame table,
                                    int seat, boolean owner, PokerGame.Phase phase) {
        // 按钮顺序照 UNO/斗地主（2026-10-26 用户截图「对比1/对比2」）：
        //   结算页 = 下一局 → 托管 → 退出牌桌（就这三根，不许再堆别的）；
        //   等待期 = 加人机 → 开始游戏 → 清除人机 → 退出牌桌。
        if (phase == PokerGame.Phase.SETTLED) {
            if (!table.hasSeatVacated()) {
                boolean voted = table.hasVotedNext(seat);
                form.add(voted ? "已确认「下一局」（再点取消）" : "下一局", () -> {
                    String err = table.voteNext(player);
                    if (err != null) {
                        player.sendTip(err.startsWith("§") ? err : "§c" + err);
                    }
                    open(player, table);
                });
            } else if (owner) {
                buildOwnerWaitingButtons(form, player, table);
            }
        } else if (phase == PokerGame.Phase.WAITING) {
            if (owner) {
                buildOwnerWaitingButtons(form, player, table);
            }
        }
        // 「托管」只在开局后 / 结算提供（等待期没有可托管的东西）—— 与 UNO/斗地主同款
        if (phase != PokerGame.Phase.WAITING) {
            form.add(table.isTrusted(seat) ? "取消托管" : "托管（系统代打）", () -> {
                String err = table.toggleTrust(player);
                if (err != null) {
                    player.sendTip(err.startsWith("§") ? err : "§c" + err);
                }
                open(player, table);
            });
        }
        form.add("退出牌桌", () -> {
            table.leave(player);
            open(player, table);
        });
    }

    /**
     * 桌主在「等待期 / 有人退出的结算期」看到的按钮组：加人机 → 开始游戏 → 清除人机。
     *
     * <p>与斗地主 {@code buildOwnerWaitingButtons} 同构（2026-10-26 用户要求）。</p>
     */
    private static void buildOwnerWaitingButtons(Simple form, Player player, PokerGame table) {
        if (table.occupiedSeats() < PokerGame.SEATS) {
            addBotButtons(form, player, table);
        }
        if (table.canStart()) {
            form.add(table.waitingButtonLabel(), () -> {
                table.start();
                open(player, table);
            });
        }
        if (table.hasBot()) {
            form.add("清除人机", () -> {
                table.removeBots();
                player.sendTip("§a已清除全部人机。");
                open(player, table);
            });
        }
    }

    /**
     * 「加人机」按钮：按场次决定给几个、给什么档（2026-10-26 用户："照斗地主"）。
     *
     * <ul>
     *   <li>免费场：「加人机 · 初级」+「加人机 · 高级」两个（与斗地主免费场同款）；</li>
     *   <li>新手场：单个「加人机」= 高级，且 canAddBot() 内部已要求 ≥2 真人（防刷币）。</li>
     * </ul>
     */
    private static void addBotButtons(Simple form, Player player, PokerGame table) {
        TabletopGamesConfig.PokerTier tier = table.getTier();
        if (tier == TabletopGamesConfig.PokerTier.FREE) {
            addBotButton(form, player, table, "加人机 · 初级", PokerBotAi.EASY);
            addBotButton(form, player, table, "加人机 · 高级", PokerBotAi.HARD);
            return;
        }
        // 新手场：单个「加人机」= 高级（canAddBot 里已挡「真人不足」）
        if (table.humanSeats() < 2) {
            return;
        }
        addBotButton(form, player, table, "加人机", PokerBotAi.HARD);
    }

    /** 单个「加人机」按钮：按指定档位补一个，加完重开表单（UNO/斗地主同款）。 */
    private static void addBotButton(Simple form, Player player, PokerGame table,
                                     String label, int level) {
        if (!table.canAddBot()) {
            return;
        }
        form.add(label, () -> {
            if (table.addBot(level)) {
                player.sendTip("§a已加入人机（" + table.occupiedSeats() + "/"
                        + PokerGame.SEATS + " 家）");
            } else {
                player.sendTip("§c加不了人机（座位满 / 真人不足 / 对局进行中）。");
            }
            open(player, table);
        });
    }

    // ------------------------------------------------------------------
    // 子表单
    // ------------------------------------------------------------------

    private static void openConfig(Player player, PokerGame table) {
        Simple form = new Simple("德州扑克 · 经济场次",
                "§7当前：§e" + table.getTier().label() + "\n\n"
                        + "§7免费场：假筹码，不扣钱、不抽水；§f1 个真人 + 人机§7 就能开局。\n"
                        + "§7新手场：筹码 : 金币 = §e1 : 1§7，每一步下注即时扣，赢家先抽 "
                        + table.cutPercent() + "% 再进账；§f必须 2 个真人§7 才能开局。\n"
                        + "§7每局筹码固定 = 最低入场 §e" + table.getMinEntry() + "§7。");
        for (TabletopGamesConfig.PokerTier t : TabletopGamesConfig.PokerTier.values()) {
            form.add((t == table.getTier() ? "§a✔ " : "") + t.label(), () -> {
                table.setTier(t);
                open(player, table);
            });
        }
        form.show(player);
    }

    /** 规则说明（与斗地主的「斗地主规则」同一入口）。 */
    private static void showRules(Player player, PokerGame table) {
        Simple form = new Simple("德州扑克 · 规则",
                "§7每人 2 张底牌 + 桌面 5 张公共牌，用任意 7 张里最好的 5 张比大小。\n"
                        + "§7牌型从大到小：§f皇家同花顺 > 同花顺 > 四条 > 葫芦 > 同花\n"
                        + "§f> 顺子 > 三条 > 两对 > 一对 > 高牌\n"
                        + "§7（同牌型比点数，再相同则平分底池）\n\n"
                        + "§e流程§7：小盲/大盲强制下注 → 翻牌前 → 翻牌 3 张 → 转牌 1 张 → 河牌 1 张"
                        + " → 摊牌比大小。\n"
                        + "§7每人面前只会出现§f当前能做§7的按钮：弃牌 / 过牌 / 跟注 / 加注 / 全下。\n"
                        + "§7把准星移到按钮上会变绿——点它就是执行。点「加注」会弹滑块填金额。\n\n"
                        + "§e筹码§7：每局开始所有人的筹码都重置成「最低入场」，局间不累积；"
                        + "下注是即时扣的。\n"
                        + "§e抽水§7：从底池扣（默认 1%），赢家少拿；免费场不抽水。\n"
                        + "§e全下与边池§7：筹码不够跟注时可以全下，多出来的部分进边池，"
                        + "只有投入够的人能分。\n\n"
                        + "§e结算§7：牌局打完会停住，点「下一局」继续；"
                        + "§f在座所有人确认后才开下一局§7，有人离席则由桌主点「开始游戏」重开。");
        form.add("返回牌桌", () -> open(player, table));
        form.show(player);
    }

    /** 加注滑块表单（点桌面「加注」时弹）。 */
    public static void openRaise(Player player, PokerGame table) {
        int[] bounds = table.raiseBounds();
        int lo = bounds[0];
        int hi = bounds[1];
        if (hi <= lo) {
            return;
        }
        int step = Math.max(1, (hi - lo) / 20);
        int def = Math.min(hi, Math.max(lo, table.suggestRaise()));
        FormWindowCustom form = new FormWindowCustom("加注到");
        form.addElement(new ElementLabel(
                "§7需跟 " + table.toCallOfActor() + " §7| 最小加注到 §e" + lo
                        + " §7| 最多 §e" + hi + " §7| 底池 §e" + table.potNow()));
        form.addElement(new ElementSlider("加注金额", lo, hi, step, def));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed()) {
                table.cancelRaise(player);
                return;
            }
            int v = Math.round(form.getResponse().getSliderResponse(1));
            table.submitRaise(player, v);
        }));
        player.showFormWindow(form);
    }
}
