package com.tabletopgames.game;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowCustom;
import com.tabletopgames.config.BoardKind;
import com.tabletopgames.config.TabletopGamesConfig;
import com.tabletopgames.config.TabletopGamesConfig.DdzTier;
import com.tabletopgames.game.ddz.DdzDifficulty;
import com.tabletopgames.ui.SettingsMenu;
import com.tabletopgames.ui.Simple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 斗地主牌桌菜单。
 *
 * <h2>三态</h2>
 * <ul>
 *   <li><b>未入座</b>：空位「坐X位」（只列空位）+「底分档位」（当前档高亮）+「斗地主规则」；
 *       全满时只给提示。</li>
 *   <li><b>已入座 · 等待</b>：桌主可见「加人机」「开始游戏 / 下一局」「清除人机」；
 *       所有人可见「托管 / 取消托管」+「退出牌桌」。非桌主的在座玩家只看到后两个。</li>
 *   <li><b>已入座 · 结算 · 没人退出</b>：「下一局」（= 投续局票，可取消）+「托管」+「退出牌桌」。</li>
 *   <li><b>已入座 · 结算 · 有人退出</b>：不再给「下一局」—— 退化成等待期的一套
 *       （桌主：加人机 / 开始游戏 / 清除人机），其余人只有托管 + 退出牌桌。</li>
 * </ul>
 *
 * <h2>⚠️ 与 UNO 菜单的三处差异</h2>
 * <ol>
 *   <li><b>没有「设置系数」</b>：斗地主移除了系数体系，底分只能在四档里选
 *       （免费 0 / 初级 50 / 高级 800 / 大师 10000），而这四档是<b>全局配置</b>，
 *       不是每桌可设 —— 菜单只读不写，写了会出现「两个人在同一桌选到不同底分」。</li>
 *   <li><b>没有菜单独占锁</b>：UNO 之所以要锁，是因为 0 人入座时要设每桌倍数。
 *       斗地主 0 人入座只有一个「坐下」动作，并发点两下只是第二次被「已被占了」挡掉，
 *       不需要额外锁（省掉一份状态与它的失效分支）。</li>
 *   <li><b>不自动重弹</b>：UNO 的 {@code refreshSeatMenus} 会在座位变化时给所有在座玩家
 *       重弹表单。斗地主<b>刻意不做</b>：结算后玩家可能正在看结算板，
 *       被强行关表单一无所知只会让人多点几下（UNO 那边 10-18 刚把自动弹窗去掉）。</li>
 * </ol>
 */
public final class DdzMenu {

    /** 正开着本桌菜单的玩家 → 牌桌（用于判断是否该关旧表单）。 */
    private static final Map<UUID, DdzGame> SHOWING = new HashMap<>();

    private DdzMenu() {
    }

    public static boolean isShowing(Player player, DdzGame table) {
        return player != null && SHOWING.get(player.getUniqueId()) == table;
    }

    public static void closeCurrentForm(Player player) {
        SHOWING.remove(player.getUniqueId());
        if (!player.formWindows.isEmpty()) {
            player.closeFormWindows();
        }
    }

    public static void open(Player player, DdzGame table) {
        if (player == null || table == null) {
            return;
        }
        closeCurrentForm(player);
        Simple form = new Simple("斗地主 [Beta]", table.statusText(player));
        int seat = table.seatOf(player.getUniqueId());
        if (seat >= 0) {
            buildSeated(form, player, table, seat);
            // 「手牌排序」**只给已入座的人**（用户 2026-10-25 第二次要求：旁观者/未入座的
            // 表单里不要这一项）。单按钮**点击即切换**（2026-10-25 第三次要求：不要二级菜单），
            // 按钮文字显示当前档，点一下翻到另一档并刷新表单。纯画面，对局中随时能改。
            TabletopGamesConfig.DdzHandSort cur = table.getHandSort();
            String sortLabel = cur == TabletopGamesConfig.DdzHandSort.BIG_LEFT
                    ? "手牌排序切换（大-小）"
                    : "手牌排序切换（小-大）";
            form.add(sortLabel, () -> {
                table.setHandSort(cur == TabletopGamesConfig.DdzHandSort.BIG_LEFT
                        ? TabletopGamesConfig.DdzHandSort.BIG_RIGHT
                        : TabletopGamesConfig.DdzHandSort.BIG_LEFT);
                open(player, table);
            });
        } else {
            buildVisitor(form, player, table);
        }
        if (form.buttonCount() == 0) {
            return;
        }
        UUID id = player.getUniqueId();
        form.onClose(() -> SHOWING.remove(id));
        SHOWING.put(id, table);
        form.show(player);
    }

    // ------------------------------------------------------------------

    private static void buildVisitor(Simple form, Player player, DdzGame table) {
        int free = 0;
        for (int s = 0; s < 3; s++) {
            if (table.isOccupied(s) || table.isBot(s)) {
                continue;
            }
            free++;
            final int seat = s;
            form.add("坐" + DdzGame.seatLabel(seat), () -> {
                String err = table.join(player, seat);
                if (err != null) {
                    player.sendTip(err.startsWith("§") ? err : "§c" + err);
                    open(player, table);
                } else {
                    // 入座成功 → 自动弹新表单（UNO 同款，2026-10-20）
                    open(player, table);
                }
            });
        }
        if (free == 0) {
            player.sendTip("§c座位已满（3 人），无法加入。");
            return;
        }
        // 「牌桌配置」放在规则上面（用户指定位置），但**只有 0 人入座时可调**
        // —— 与 UNO 的「设置每局倍数」同款：一旦有人坐下，场次就定死。
        if (table.occupiedSeats() == 0) {
            form.add("牌桌配置", () -> openTableConfig(player, table));
        }
        form.add("斗地主规则", () -> showRules(player));
        // 「全局配置」与 UNO 同款：**0 人入座/未入座时**就能开（OP），不必先坐下
        if (player.isOp()) {
            form.add("全局配置", () -> SettingsMenu.open(player, BoardKind.DDZ));
        }
    }

    /**
     * 「加人机」按钮：按场次决定给几个、给什么档（2026-10-22 用户要求）。
     *
     * <table>
     *   <tr><th>场次</th><th>按钮</th><th>前置</th></tr>
     *   <tr><td>免费场</td><td>「加人机 · 初级」+「加人机 · 高级」</td><td>桌主 + 有空位</td></tr>
     *   <tr><td>新手场 / 高级场</td><td>单个「加人机」= <b>高级</b></td><td>桌主 + <b>已满 2 位真人</b></td></tr>
     *   <tr><td>大师场</td><td>不显示</td><td>只许 3 位真人（{@code addBot} 里还有第二道防线）</td></tr>
     * </table>
     *
     * <p>「≥2 真人」是防刷币：1 真人 + 1 人机的结构里，真人可以从机器人身上搬钱结算；
     * 免费场底分为 0、没有钱可搬，所以不设门槛。与 UNO「免费局三档 / 倍数≠0 只给高级且">
     * 2 真人」是同一条规则。</p>
     */
    private static void addBotButtons(Simple form, Player player, DdzGame table) {
        DdzTier tier = table.getTier();
        if (tier == DdzTier.MASTER) {
            return;
        }
        if (tier == DdzTier.FREE) {
            addBotButton(form, player, table, "加人机 · 初级", DdzDifficulty.SIMPLE);
            addBotButton(form, player, table, "加人机 · 高级", DdzDifficulty.ADVANCED);
            return;
        }
        // 新手场 / 高级场：至少 2 位真人才能补人机
        if (table.humanCount() < 2) {
            return;
        }
        addBotButton(form, player, table, "加人机", DdzDifficulty.ADVANCED);
    }

    /** 单个「加人机」按钮：按指定档位补一个，加完重开表单（UNO 同款）。 */
    private static void addBotButton(Simple form, Player player, DdzGame table,
                                     String label, DdzDifficulty botTier) {
        form.add(label, () -> {
            String err = table.addBot(botTier);
            if (err != null) {
                player.sendTip("§c" + err);
            }
            open(player, table);
        });
    }

    /**
     * 桌主在「等待期 / 有人退出的结算期」看到的开局按钮组：加人机 → 开始游戏 → 清除人机。
     *
     * <p>两个阶段共用同一套（需求 2026-10-24）：结算期一旦有人退出，
     * {@link DdzGame#hasSeatVacated()} 就为真、续局投票不再提供，只能回到
     * 「凑齐 3 家 → 开始游戏」的老路，按钮自然要和等待期一模一样。</p>
     */
    private static void buildOwnerWaitingButtons(Simple form, Player player, DdzGame table) {
        // 满 3 家就不该再出现「加人机」（否则点了必然被拒：已经在座 3 人）
        if (table.seatedCount() < 3) {
            addBotButtons(form, player, table);
        }
        // 斗地主必须 3 家（真人 + 机器人合计）；不足时按钮不出现，避免点了被拒
        if (table.seatedCount() >= 3) {
            // 本局没人退出时这里叫「下一局」，否则叫「开始游戏」（DdzGame#waitingButtonLabel）
            form.add(table.waitingButtonLabel(), () -> {
                String err = table.startRound();
                if (err != null) {
                    player.sendTip("§c" + err);
                }
            });
        }
        if (table.hasBots()) {
            form.add("清除人机", () -> {
                String err = table.clearBots();
                if (err != null) {
                    player.sendTip("§c" + err);
                }
            });
        }
    }

    private static void buildSeated(Simple form, Player player, DdzGame table, int seat) {
        DdzGame.TablePhase phase = table.getPhase();
        boolean owner = table.isFirstJoiner(player.getUniqueId());

        if (phase == DdzGame.TablePhase.SETTLED) {
            // 结算页的「下一局」只在「本局没有任何人退出」时给（需求 2026-10-24，UNO 同款）：
            // 有人离席 / 断线 / 走出半径 / 被清掉人机 ⇒ 续局链断了，改走「开始游戏」，
            // 否则玩家会看到「下一局」却永远开不起来（3 家都坐不齐）。
            if (!table.hasSeatVacated()) {
                boolean voted = table.hasVotedNext(seat);
                form.add(voted ? "已确认「下一局」（再点取消）" : "下一局", () -> {
                    String err = table.voteNext(player);
                    if (err != null) {
                        player.sendTip("§c" + err);
                    }
                });
            } else if (owner) {
                // 有人退过：与等待期完全同一套按钮（加人机补齐 → 开始游戏 / 清除人机）
                buildOwnerWaitingButtons(form, player, table);
            }
        } else if (phase == DdzGame.TablePhase.WAITING) {
            if (owner) {
                buildOwnerWaitingButtons(form, player, table);
            }
        }
        // 托管按钮只在开局后提供（等待期没有可托管的东西）
        if (phase != DdzGame.TablePhase.WAITING) {
            form.add(table.isTrusted(seat) ? "取消托管" : "托管（系统代打）", () -> {
                String err = table.toggleTrust(player);
                if (err != null) {
                    player.sendTip("§c" + err);
                }
            });
        }
        // 「牌桌配置」不在这里：与 UNO 同款，只允许「0 人入座」时改（人一坐下场次就定死）。
        // 「全局配置」同理，只放在访客菜单，OP 无需先坐下。
        form.add("退出牌桌", () -> table.leave(player));
    }

    /**
     * 牌桌配置表单：两个下拉（2026-10-24 用户要求）。
     *
     * <ol>
     *   <li><b>经济场次</b>（2026-10-20 加）：四档固定底分；</li>
     *   <li><b>确定地主的方式</b>（2026-10-24 加）：叫地主/抢地主，或 叫分（不叫/1/2/3分）。</li>
     * </ol>
     *
     * <p>两个都是 {@link ElementDropdown}（能展开更多选项的那种），与模组的
     * 「牌桌配置」同款控件。<b>说明文字全部走 {@code setTooltip(...)}</b>
     * ——就是全局配置里那种点右侧 ⓘ 才展开的提示，不再在表单里堆一大段
     * {@link ElementLabel}（用户 2026-10-24：「用带提示的组件，把原先下面那些内容都放进去」）。</p>
     */
    private static void openTableConfig(Player player, DdzGame table) {
        // 双保险：按钮已经在 occupiedSeats()==0 时才出现，这里再拦一次
        if (table.occupiedSeats() > 0) {
            player.sendTip("§c本桌已经有人入座，配置不能再改（等所有人离开后可调）。");
            return;
        }
        TabletopGamesConfig.DdzTier current = table.getTier();
        TabletopGamesConfig.DdzBidMode mode = table.getBidMode();
        TabletopGamesConfig.DdzWildMode wildMode = table.getWildMode();
        FormWindowCustom form = new FormWindowCustom("斗地主 [Beta] · 玩法模式");
        form.addElement(new ElementLabel(
                "§7本桌当前场次：§e" + current.label()
                        + "§7（底分 " + trimNum(table.baseScoreOfTier()) + "）"
                        + "\n§7定地主方式：§e" + mode.label()
                        + "\n§7玩法模式：§e" + wildMode.label()));

        List<String> tierOptions = java.util.Arrays.asList(TabletopGamesConfig.DdzTier.labels());
        ElementDropdown tierDrop = new ElementDropdown("经济场次", tierOptions, current.ordinal());
        tierDrop.setTooltip(
                "§e经济场次（决定底分）\n"
                        + "§7· 免费场：底分 0，不涉及金币\n"
                        + "§7· 新手场：底分 50\n"
                        + "§7· 高级场：底分 800\n"
                        + "§7· 大师场：底分 10000\n"
                        + "§7入座门槛 = 底分 × 全局门槛系数。");
        form.addElement(tierDrop);

        List<String> modeOptions = java.util.Arrays.asList(TabletopGamesConfig.DdzBidMode.labels());
        ElementDropdown modeDrop = new ElementDropdown("选地主模式", modeOptions, mode.ordinal());
        modeDrop.setTooltip(
                "§e选地主模式\n"
                        + "§7· 叫地主 / 抢地主：模组原版。三家依次「叫地主 / 不叫」，"
                        + "第一个叫的人进入抢阶段，其余两家「抢地主 / 不抢」；"
                        + "倍数 = 2 的「抢中次数」次方。\n"
                        + "§7· 叫分（1/2/3分）：三家依次叫「不叫 / 1分 / 2分 / 3分」，"
                        + "后叫的必须高于当前最高分；有人叫到 3 分立刻定地主；"
                        + "三家都不叫则重新发牌。倍数 = 最终叫分。");
        form.addElement(modeDrop);

        List<String> wildOptions = java.util.Arrays.asList(TabletopGamesConfig.DdzWildMode.labels());
        ElementDropdown wildDrop = new ElementDropdown("玩法模式", wildOptions,
                wildMode.ordinal());
        wildDrop.setTooltip(
                "§e玩法模式\n"
                        + "§7· 普通：标准斗地主，没有癞子。\n"
                        + "§7· 地赖子：§f地主确定之后§7随机抽一个点数（3~2，不含大小王），"
                        + "该点数的 4 张牌变成癞子，牌面换成金色。\n"
                        + "§7· 天地癞子：§f发牌前§7先抽一个§e天癞子§7（叫牌阶段就能看到），"
                        + "地主确定后再抽一个§e地赖子§7（两者点数不会相同），"
                        + "两个点数都是癞子，桌面上会并排摆在底牌旁边。\n"
                        + "§7癞子可以当 3~2 的任意点数使用：能补对子、三张、炸弹、顺子、"
                        + "连对、飞机、四带二。\n"
                        + "§7限制：癞子§f不能当大小王§7（造不出王炸）；"
                        + "§f单出一张癞子仍按它原来的点数§7算（点数是 3，单出就是 3）。");
        form.addElement(wildDrop);

        final TabletopGamesConfig.DdzWildMode wildNow = wildMode;
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed()) {
                return;
            }
            // ⚠️ Nukkit 的 FormResponseData 只暴露 getElementContent()（选中项的**文本**）、
            //    没有 getSelectedOptionIndex()，所以只能拿文本反查下标。
            TabletopGamesConfig.DdzTier nextTier = pick(tierOptions,
                    dropdownText(form, 1), current, TabletopGamesConfig.DdzTier::byOrdinal);
            TabletopGamesConfig.DdzBidMode nextMode = pick(modeOptions,
                    dropdownText(form, 2), mode, TabletopGamesConfig.DdzBidMode::byOrdinal);
            TabletopGamesConfig.DdzWildMode nextWild = pick(wildOptions,
                    dropdownText(form, 3), wildNow,
                    TabletopGamesConfig.DdzWildMode::byOrdinal);

            String err = table.setTier(nextTier);
            if (err != null) {
                player.sendTip("§c" + err);
            }
            String err2 = table.setBidMode(nextMode);
            if (err2 != null) {
                player.sendTip("§c" + err2);
            }
            String err3 = table.setWildMode(nextWild);
            if (err3 != null) {
                player.sendTip("§c" + err3);
            }
            if (err == null && err2 == null && err3 == null) {
                player.sendTip("§a本桌配置已保存：场次 §e" + nextTier.label()
                        + "§a（底分 " + trimNum(nextTier.baseScore()) + "），定地主 §e"
                        + nextMode.label() + "§a，玩法 §e" + nextWild.label()
                        + "§a（下一局生效）。");
            }
        }));
        player.showFormWindow(form);
    }

    /** 读下拉框选中项的文本；结构不符时返回 null。 */
    private static String dropdownText(FormWindowCustom form, int index) {
        try {
            return form.getResponse().getDropdownResponse(index).getElementContent();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 文本 → 枚举：按选项列表反查下标再取枚举；对不上就保持原值。
     *
     * <p>不能直接把文本当枚举名（下拉里显示的是中文 label）。</p>
     */
    private static <T> T pick(List<String> options, String text, T fallback,
                              java.util.function.IntFunction<T> byOrdinal) {
        if (text != null) {
            int i = options.indexOf(text);
            if (i >= 0) {
                return byOrdinal.apply(i);
            }
        }
        return fallback;
    }

    /** 去掉金额的小数尾巴（50 / 800 / 10000 而不是 50.0）。 */
    private static String trimNum(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    private static void showRules(Player player) {
        player.sendMessage("§b[斗地主规则]");
        player.sendMessage("§7· 三家。定地主有两种方式（玩法模式里选）：");
        player.sendMessage("§7  ① 叫地主／抢地主：叫到的人进入抢阶段，倍数 = 2^抢中次数；");
        player.sendMessage("§7  ② 叫分：不叫 / 1分 / 2分 / 3分，最高分当地主，倍数 = 叫分。");
        player.sendMessage("§7· 地主拿 3 张底牌，共 20 张。");
        player.sendMessage("§7· 定地主后每人选一次加倍：不加倍 ×1 / 加倍 ×2 / 超级加倍 ×4。");
        player.sendMessage("§7· 牌型：单张、对子、三张、三带一/二、顺子（≥5 连）、连对（≥3 连）、");
        player.sendMessage("§7  飞机（带单/带对）、四带二/两对、炸弹、王炸。");
        player.sendMessage("§7· 跟牌必须同牌型且更大；炸弹／王炸可压任何牌；一轮两家不出即上一手自由出。");
        player.sendMessage("§7· 倍数 = 抢地主次数的 2 的幂 × 每家加倍因子，炸弹与春天再 ×2（有上限）。");
        player.sendMessage("§7· 结算：unit = 底分 × 倍数 × 地主因子 × 该农民因子；");
        player.sendMessage("§7  入座先扣「底分 × 入座门槛系数」，结算时再从赢家应得里按抽水比例扣。");
    }
}
