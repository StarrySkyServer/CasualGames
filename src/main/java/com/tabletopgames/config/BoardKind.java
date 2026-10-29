package com.tabletopgames.config;

/**
 * 棋类 / 牌桌的种类。
 * <p>
 * 全局配置（{@code config.yml}）内部按本枚举分节：五子棋 / 中国象棋 / 黑白棋 / 围棋 / 斗兽棋
 * 各节共用 {@link TabletopGamesConfig.GameSettings} 的字段集，
 * UNO 牌桌字段集不同（{@link TabletopGamesConfig.UnoSettings}）。
 * 棋盘 / 牌桌各自读自己那一节 —— 改一类的设置不会影响其它类。
 * <p>
 * 存档目录也按本枚举分文件夹：{@code data/gomoku/}、{@code data/xiangqi/}…（见 {@link #folder()}）。
 */
public enum BoardKind {

    /** 五子棋（插件本体）。 */
    GOMOKU("五子棋", "gomoku"),

    /** 中国象棋。 */
    XIANGQI("中国象棋", "xiangqi"),

    /** 黑白棋（奥赛罗）。 */
    OTHELLO("黑白棋", "othello"),

    /** 围棋（9 / 13 / 19 路共用一份设置与同一个存档目录）。 */
    GO("围棋", "go"),

    /** 斗兽棋。 */
    DOUSHUQI("斗兽棋", "doushouqi"),

    /** 军旗（二国陆战棋）。 */
    JUNQI("军旗", "junqi"),

    /** UNO 牌桌（配置节 = {@link TabletopGamesConfig.UnoSettings}）。 */
    UNO("UNO 牌桌", "uno"),

    /**
     * 斗地主牌桌（配置节 = {@link TabletopGamesConfig.DdzSettings}）。
     *
     * <p>与 {@link #UNO} 同构：独立字段集（档位底分取代倍数 k、无第二回合时间），
     * 存档走 {@code data/ddz/}。
     */
    DDZ("斗地主", "ddz"),

    /** 德州扑克（配置节 = {@link TabletopGamesConfig.PokerSettings}）。 */
    POKER("德州扑克", "poker");

    private final String label;
    private final String folder;

    BoardKind(String label, String folder) {
        this.label = label;
        this.folder = folder;
    }

    /** 中文显示名（表单标题 / 反馈文案用）。 */
    public String label() {
        return label;
    }

    /** 本类的存档目录名（插件数据目录下的 {@code data/<folder>/}）。 */
    public String folder() {
        return folder;
    }

    /** 是否为「棋类」（非 UNO / 斗地主）：棋类共用 {@link TabletopGamesConfig.GameSettings} 的字段集。 */
    public boolean isBoard() {
        return this != UNO && this != DDZ && this != POKER;
    }

    /** 是否为「牌桌」（UNO / 斗地主）：牌桌各有独立字段集。 */
    public boolean isCardTable() {
        return this == UNO || this == DDZ || this == POKER;
    }
}
