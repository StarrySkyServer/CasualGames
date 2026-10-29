package com.tabletopgames;

import cn.nukkit.plugin.PluginBase;

/**
 * 控制台启动横幅（2026-10-27 用户要求：参考 Territory 插件的大字提示）。
 *
 * <p>纯 ASCII，不依赖颜色代码 —— 服务端控制台有的是 GBK，带 {@code §} 的彩色行经
 * {@code getLogger()} 出去只会变成乱码方框。</p>
 *
 * <p>字体手工拼的是 figlet 的 {@code standard} 字型（与 Territory 那份同一套），
 * 行高 6：前 5 行是字身，最后一行只有 {@code p} 的降部。每行已经 {@code rstrip}，
 * 尾部空格不影响显示，改字时注意<b>逐行对齐</b>（拼错一个空格整行就歪）。</p>
 */
public final class Banner {

    /** 横幅正文，逐行原样打印（不要随便重排：这是一张字符画）。 */
    private static final String[] ART = {
            " _____         _      _         _                   ____",
            "|_   _|  __ _ | |__  | |  ___  | |_   ___   _ __   / ___|  __ _  _ __ ___      ___   ___",
            "  | |   / _` || '_ \\ | | / _ \\ | __| / _ \\ | '_ \\ | |  _  / _` || '_ ` _ \\    / _ \\ / __|",
            "  | |  | (_| || |_) || ||  __/ | |_ | (_) || |_) || |_| || (_| || | | | | |  |  __/ \\__ \\",
            "  |_|   \\__,_||_.__/ |_| \\___|  \\__| \\___/ | .__/  \\____| \\__,_||_| |_| |_|   \\___| |___/",
            "                                           |_|",
    };

    private Banner() {
    }

    /** 打印横幅 + 一行版本信息（插件 {@code onEnable} 末尾调用）。 */
    public static void print(PluginBase plugin) {
        if (plugin == null) {
            return;
        }
        for (String line : ART) {
            plugin.getLogger().info(line);
        }
        plugin.getLogger().info("TabletopGames " + plugin.getDescription().getVersion()
                + " 已启用 · 五子棋 / 中国象棋 / 黑白棋 / 围棋 / 斗兽棋 / 军旗 / UNO / 斗地主 / 德州扑克");
    }
}
