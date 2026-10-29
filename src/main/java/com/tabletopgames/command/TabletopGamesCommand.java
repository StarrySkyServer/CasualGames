package com.tabletopgames.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import com.tabletopgames.TabletopGamesPlugin;
import com.tabletopgames.ui.SettingsMenu;

/**
 * {@code /tabletopgames} 命令：打开全局配置、重载配置。
 *
 * <pre>
 *   /tabletopgames                 → 打开「全局配置」一级菜单（OP）
 *   /tabletopgames settings        → 同上
 *   /tabletopgames reload          → 重载配置（OP 或控制台）
 * </pre>
 *
 * <p>2026-10-24：原来的 {@code /tabletopgames uno play|settle|off} 手牌极限张数展示台已移除
 * （用户「不需要测试指令了」；备份见仓库外 {@code _scratch/_UnoShowcase.java.bak}）。
 */
public final class TabletopGamesCommand {

    private final TabletopGamesPlugin plugin;

    public TabletopGamesCommand(TabletopGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "";
        String root = command.getName().toLowerCase(java.util.Locale.ROOT);

        // 无参 与 settings 等价：直接进「全局配置」一级菜单
        if (sub.isEmpty() || "settings".equals(sub)) {
            if (!sender.isOp()) {
                sender.sendMessage("§c你没有权限使用该命令。");
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c设置表单只能在游戏内由 OP 玩家打开。");
                return true;
            }
            SettingsMenu.open(player);
            return true;
        }

        if ("reload".equals(sub)) {
            if (!sender.isOp()) {
                sender.sendMessage("§c你没有权限使用该命令。");
                return true;
            }
            sender.sendMessage("§7正在重载配置…");
            plugin.getManager().reloadConfig(sender);
            return true;
        }

        sender.sendMessage("§e用法：§f/" + root + " §7（打开全局配置）§8·§f /" + root
                + " reload §7（重载配置）");
        return true;
    }
}
