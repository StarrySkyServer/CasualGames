package com.tabletopgames.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 军旗「我的布阵」（{@code junqi_formation} 表）。
 *
 * <p>每个玩家最多 {@link #MAX_PER_PLAYER} 个布阵，名字即菜单按钮名，所以同一玩家内唯一。
 * {@code layout} 存 25 个「本位坐标」（{@code localRow * 5 + col}，红黑通用），
 * 由 {@code JunqiBoard.exportLayout / applyLayout} 负责转换。</p>
 */
public final class JunqiFormationDao {

    /** 每人最多保存的布阵数。 */
    public static final int MAX_PER_PLAYER = 30;

    private final Database db;

    public JunqiFormationDao(Database db) {
        this.db = db;
    }

    /** 一条布阵：名字 + 25 个逗号分隔的本位坐标。 */
    public record Formation(String name, String layout) {
    }

    /** 某玩家的全部布阵，按名字排序（最多 30 条，菜单自己分页）。数据库不可用时返回空表。 */
    public List<Formation> list(UUID playerId) {
        List<Formation> out = new ArrayList<>();
        if (!db.available()) {
            return out;
        }
        String sql = "SELECT name, layout FROM junqi_formation WHERE uuid = ? ORDER BY name";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Formation(rs.getString("name"), rs.getString("layout")));
                    }
                }
            } catch (SQLException ignored) {
                // 读失败按「没有布阵」处理，不影响对局
            }
        }
        return out;
    }

    /** 取一条布阵；没有返回 {@code null}。 */
    public Formation get(UUID playerId, String name) {
        if (!db.available()) {
            return null;
        }
        String sql = "SELECT layout FROM junqi_formation WHERE uuid = ? AND name = ?";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Formation(name, rs.getString("layout"));
                    }
                }
            } catch (SQLException ignored) {
                // 同上
            }
        }
        return null;
    }

    /** 新增或覆盖同名布阵。 */
    public boolean save(UUID playerId, String name, String layout) {
        if (!db.available()) {
            return false;
        }
        String sql = """
                INSERT INTO junqi_formation (uuid, name, layout, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid, name) DO UPDATE SET layout     = excluded.layout,
                                                       updated_at = excluded.updated_at""";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.setString(2, name);
                ps.setString(3, layout);
                ps.setLong(4, System.currentTimeMillis() / 1000L);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    /** 删除一条布阵；不存在也算成功。 */
    public boolean delete(UUID playerId, String name) {
        if (!db.available()) {
            return false;
        }
        String sql = "DELETE FROM junqi_formation WHERE uuid = ? AND name = ?";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.setString(2, name);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                return false;
            }
        }
    }
}
