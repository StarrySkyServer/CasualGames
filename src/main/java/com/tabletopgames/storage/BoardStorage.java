package com.tabletopgames.storage;

import cn.nukkit.plugin.PluginBase;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 棋盘存档读写助手：一个棋盘一份 YAML 文本，按棋类分门别类存进数据库（{@code board_state} 表）。
 *
 * <h3>2026-10-27 改动：文件 → 数据库</h3>
 * <p>以前是 {@code <插件数据目录>/data/<棋类>/<4 位编号>.yml}，一个棋盘一个文件。现在统一进
 * SQLite，与斗地主 / 德州扑克一条路子：</p>
 * <ul>
 *   <li>存档内容<b>仍是那份 YAML 文本</b>（连注释一起），只是存在 {@code board_state.data} 里；</li>
 *   <li>读写在<b>主线程同步</b>完成（{@link BoardDao}）。原来那套 {@code saveExecutor} 异步写盘已删：
 *       SQLite 是单连接，多线程写会出问题；而这些存档每份才几 KB、5 秒才写一次，主线程毫无压力；</li>
 *   <li>老版本留在磁盘上的 {@code data/<棋类>/*.yml} 会在<b>第一次读取该棋类时自动导入</b>，
 *       导入成功后原目录改名成 {@code <棋类>.migrated}（不删，留着你确认）。</li>
 * </ul>
 *
 * <p>⚠️ 必须先 {@link #bind} 再调用其它方法（插件 {@code onEnable} 里做完）。没绑定时所有方法都退化：
 * {@code ids()} 返回空表、{@code write()} 返回 false —— 等价于「这次不落库」，不会崩。</p>
 *
 * <p>{@link Yaml} 实例非线程安全，因此每次调用都新建一个（存档很小，开销可忽略）。
 * 运行期的 snakeyaml 由 Nukkit-MOT 内置提供（见 pom 中 okaeri-configs-yaml-snakeyaml 依赖注释）。</p>
 */
public final class BoardStorage {

    /** 数据库句柄（{@link #bind} 里给）。 */
    private static BoardDao dao;
    /** 插件实例（只用来打迁移日志）。 */
    private static PluginBase plugin;
    /** 已经检查过迁移的棋类（每个进程只查一次）。 */
    private static final Set<String> MIGRATED = new HashSet<>();

    private BoardStorage() {
    }

    /** 插件启动时绑定数据库（必须在任何 {@code ids/load/write/delete} 之前）。 */
    public static void bind(PluginBase plugin, Database database) {
        BoardStorage.plugin = plugin;
        boolean ok = database != null && database.available();
        BoardStorage.dao = ok ? new BoardDao(database) : null;
        MIGRATED.clear();
        if (!ok && plugin != null) {
            // 只在这里喊一次：数据库打不开时下面所有 write 都当成功，否则每 5 秒刷一条警告
            plugin.getLogger().warning("数据库不可用：本次运行不保存棋盘 / 牌桌存档。");
        }
    }

    // ------------------------------------------------------------------
    // 存档（数据库）
    // ------------------------------------------------------------------

    /**
     * 某棋类现有的全部存档编号（升序）。
     *
     * <p>第一次调用会做一次性迁移：库里这个棋类一条都没有、磁盘上却有老存档 ⇒ 全部导入。</p>
     */
    public static List<Integer> ids(String kind) {
        migrateIfNeeded(kind);
        return dao == null ? new ArrayList<>() : dao.ids(kind);
    }

    /** 读一份存档并解析成 Map（YAML 文本 → 对象）；没有或解析失败返回 {@code null}。 */
    public static Object load(String kind, int id) {
        String text = dao == null ? null : dao.text(kind, id);
        if (text == null) {
            return null;
        }
        try {
            return new Yaml().load(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 写一份存档。
     *
     * @param level 所在世界名（辅助列，排查用；可为 {@code null}）
     * @return 写成功返回 {@code true}；<b>数据库没就绪时也返回 {@code true}</b>
     *         （等价于「这次不落库」，不能每 5 秒刷一条警告，见 {@link #bind}）
     */
    public static boolean write(String kind, int id, String text, String level) {
        return dao == null || dao.save(kind, id, level, text);
    }

    /** 删一份存档；没有这份也算成功。 */
    public static boolean delete(String kind, int id) {
        return dao == null || dao.delete(kind, id);
    }

    // ------------------------------------------------------------------
    // 一次性迁移：data/<棋类>/*.yml → board_state
    // ------------------------------------------------------------------

    /**
     * 老版本磁盘存档的导入（幂等，每个棋类每个进程只跑一次）。
     *
     * <p>只在「库里这个棋类没有任何行」时才导，避免把用户已经删掉的棋盘又搬回来。
     * 导入成功后把目录改名为 {@code <棋类>.migrated} —— 不删，出问题还能手工比对。</p>
     */
    private static void migrateIfNeeded(String kind) {
        if (dao == null || kind == null || !MIGRATED.add(kind)) {
            return;
        }
        if (dao.count(kind) > 0) {
            return;   // 库里已经有数据（新存档 or 早就迁过），绝不用旧文件覆盖
        }
        File dir = legacyFolder(kind);
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        List<File> files = legacyFiles(dir);
        if (files.isEmpty()) {
            return;
        }
        int n = 0;
        for (File file : files) {
            int id = idOf(file);
            String text = readText(file);
            if (id <= 0 || text == null) {
                continue;
            }
            String level = "";
            try {
                Object data = new Yaml().load(text);
                if (data instanceof Map<?, ?> map) {
                    level = stringValue(mapValue(map, "position"), "world", "");
                }
            } catch (RuntimeException ignored) {
                // 解析不了也照样搬：原文进库，人工还能救
            }
            if (dao.save(kind, id, level, text)) {
                n++;
            }
        }
        if (n <= 0) {
            return;
        }
        File backup = new File(dir.getParentFile(), kind + ".migrated");
        boolean renamed = !backup.exists() && dir.renameTo(backup);
        if (plugin != null) {
            plugin.getLogger().info("已把 " + n + " 份 " + kind + " 存档迁进数据库"
                    + (renamed ? "（原目录改名为 " + backup.getName() + "）"
                    : "（原目录改名失败，确认无误后请手动删掉 data/" + kind + "）"));
        }
    }

    /** 老版本存档目录 {@code <插件数据目录>/data/<棋类>/}（不存在返回 {@code null}，不创建）。 */
    private static File legacyFolder(String kind) {
        if (plugin == null) {
            return null;
        }
        return new File(new File(plugin.getDataFolder(), "data"), kind);
    }

    private static List<File> legacyFiles(File dir) {
        File[] found = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (found == null || found.length == 0) {
            return new ArrayList<>();
        }
        List<File> list = new ArrayList<>(List.of(found));
        list.sort(Comparator.comparing(File::getName));
        return list;
    }

    private static int idOf(File file) {
        String name = file.getName();
        if (!name.endsWith(".yml")) {
            return 0;
        }
        try {
            return Integer.parseInt(name.substring(0, name.length() - 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readText(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // YAML 文本
    // ------------------------------------------------------------------

    /** 把数据结构序列化为 YAML 文本（块状风格，UTF-8 由调用方写库时指定）。 */
    public static String dump(Object data) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(data);
    }

    /**
     * 单个棋盘存档文本的拼装器。
     * <p>
     * snakeyaml 无法在映射内部插入注释，因此这里按「小节」分别 dump 再拼接：
     * 头部一段说明注释，之后每一节先写小节注释，再写「键: 值」。
     */
    public static final class Document {

        private final StringBuilder text = new StringBuilder();

        public Document(String title, String... notes) {
            appendComment(title);
            for (String note : notes) {
                appendComment(note);
            }
            text.append('\n');
        }

        /** 追加一节：{@code 小节注释} + {@code 键:} 下挂的对象（dump 成块状 YAML，自动缩进 2 格）。 */
        public Document section(String comment, String key, Object value) {
            appendComment(comment);
            text.append(dump(Collections.singletonMap(key, value))).append('\n');
            return this;
        }

        /** 追加一个标量节（值原样写出、不加引号，如 {@code id: 0001}）。 */
        public Document scalar(String comment, String key, String raw) {
            appendComment(comment);
            text.append(key).append(": ").append(raw).append("\n\n");
            return this;
        }

        public String build() {
            return text.toString();
        }

        /** 注释可多行（用 {@code \n} 分隔）；每行加 {@code "# "} 前缀。 */
        private void appendComment(String comment) {
            if (comment == null || comment.isEmpty()) {
                return;
            }
            try (Stream<String> lines = comment.lines()) {
                lines.forEach(line -> text.append("# ").append(line).append('\n'));
            }
        }
    }

    // ------------------------------------------------------------------
    // 取值工具
    // ------------------------------------------------------------------

    /** 从 YAML 映射读子映射；缺失或类型不符返回空映射。 */
    public static Map<?, ?> mapValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Map<?, ?> sub ? sub : Collections.emptyMap();
    }

    /** 从 YAML 映射读字符串，缺失或类型不符返回默认值。 */
    public static String stringValue(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 从 YAML 映射读整数（Number 与数字字符串均可），缺失或无法解析返回默认值。 */
    public static int intValue(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 从 YAML 映射读小数（Number 与数字字符串均可），缺失或无法解析返回默认值。 */
    public static double doubleValue(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 从 YAML 映射读布尔（仅接受 Boolean），缺失返回默认值。 */
    public static boolean boolValue(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }
}
