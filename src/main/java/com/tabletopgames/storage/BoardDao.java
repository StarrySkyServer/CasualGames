package com.tabletopgames.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 棋盘 / 牌桌的<b>整份存档</b>（{@code board_state} 表）：一行 = 一个棋盘。
 *
 * <h3>为什么搬进数据库（2026-10-27 用户要求）</h3>
 * <p>以前七套棋盘各写各的文件（{@code data/<棋类>/0001.yml}），备份要拷一整个目录，
 * 删棋盘还要跟写盘线程抢顺序。现在与斗地主 / 德州扑克一致：<b>一个 .db 文件装全部</b>，
 * 写入走主线程同步 DAO（{@code saveExecutor} 那套异步写盘已删除 —— SQLite 是单连接，
 * 多线程写会出问题）。</p>
 *
 * <h3>存的是「文本」还是「字段」</h3>
 * <p>存的是<b>那一份 YAML 文本</b>（{@code data} 列），不是拆开的字段。这样：
 * ① 老存档可以原样搬进来，格式与注释一字不改；② 以后想加字段不用动表结构；
 * ③ 需要时随时能导回成文件给人看。解析仍然走 {@code BoardStorage.load(...)} 里的 snakeyaml。</p>
 *
 * <p>{@code level} / {@code table_key} 是给排查用的辅助列（世界名 / 牌桌主键），
 * 棋盘类只填 {@code level}，{@code table_key} 留空。</p>
 */
public final class BoardDao {

    private final Database db;

    public BoardDao(Database db) {
        this.db = db;
    }

    /** 某棋类现有的全部存档编号（升序）。数据库不可用时返回空表。 */
    public List<Integer> ids(String kind) {
        List<Integer> out = new ArrayList<>();
        if (!db.available() || kind == null) {
            return out;
        }
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT board_id FROM board_state WHERE kind = ? ORDER BY board_id")) {
                ps.setString(1, kind);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getInt(1));
                    }
                }
            } catch (SQLException e) {
                // 读失败就当没有：宁可这次不恢复，也不要把库里的行删掉
            }
        }
        return out;
    }

    /** 某棋类的存档条数（迁移前用来判断「库里是不是还没有这个棋类」）。 */
    public int count(String kind) {
        if (!db.available() || kind == null) {
            return 0;
        }
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT COUNT(*) FROM board_state WHERE kind = ?")) {
                ps.setString(1, kind);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    /** 读一份存档文本；没有这份或数据库不可用时返回 {@code null}。 */
    public String text(String kind, int id) {
        if (!db.available() || kind == null) {
            return null;
        }
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT data FROM board_state WHERE kind = ? AND board_id = ?")) {
                ps.setString(1, kind);
                ps.setInt(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (SQLException e) {
                return null;
            }
        }
    }

    /** 插入或更新一份存档；写成功返回 {@code true}。 */
    public boolean save(String kind, int id, String level, String text) {
        if (!db.available() || kind == null || text == null) {
            return false;
        }
        String sql = """
                INSERT INTO board_state (kind, board_id, table_key, level, data, updated_at)
                VALUES (?, ?, '', ?, ?, ?)
                ON CONFLICT(kind, board_id) DO UPDATE SET
                    level = excluded.level,
                    data = excluded.data,
                    updated_at = excluded.updated_at""";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, kind);
                ps.setInt(2, id);
                ps.setString(3, level == null ? "" : level);
                ps.setString(4, text);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    /** 删掉一份存档；没有这份也算成功（调用方只关心「现在没了」）。 */
    public boolean delete(String kind, int id) {
        if (!db.available() || kind == null) {
            return false;
        }
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "DELETE FROM board_state WHERE kind = ? AND board_id = ?")) {
                ps.setString(1, kind);
                ps.setInt(2, id);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                return false;
            }
        }
    }
}
