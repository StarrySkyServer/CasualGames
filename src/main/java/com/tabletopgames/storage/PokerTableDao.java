package com.tabletopgames.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 德州扑克牌桌的「位置 + 配置」（{@code poker_table_config} 表）。
 *
 * <p>与 {@code DdzTableDao} 同构，只存坐标与场次/最低入场，<b>不存筹码</b> ——
 * 每局开局按 minEntry 重置，钱在下注时就进出金币了。</p>
 */
public final class PokerTableDao {

    public record Site(String key, String level, int x, int y, int z, int rotation,
                       String tier, int minEntry) {
    }

    private final Database db;

    public PokerTableDao(Database db) {
        this.db = db;
    }

    public List<Site> loadAll() {
        List<Site> out = new ArrayList<>();
        if (!db.available()) {
            return out;
        }
        String sql = """
                SELECT table_key, level, x, y, z, rotation, tier, min_entry
                FROM poker_table_config WHERE level IS NOT NULL AND level <> ''""";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Site(
                            rs.getString("table_key"),
                            rs.getString("level"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getInt("rotation"),
                            rs.getString("tier"),
                            rs.getInt("min_entry")));
                }
            } catch (SQLException e) {
                // 读失败当作没有牌桌：不删行，下次启动还有机会
            }
        }
        return out;
    }

    public void save(Site site) {
        if (!db.available() || site == null) {
            return;
        }
        String sql = """
                INSERT INTO poker_table_config
                    (table_key, level, x, y, z, rotation, tier, min_entry, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(table_key) DO UPDATE SET
                    level = excluded.level,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    rotation = excluded.rotation,
                    tier = excluded.tier,
                    min_entry = excluded.min_entry,
                    updated_at = excluded.updated_at""";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, site.key());
                ps.setString(2, site.level());
                ps.setInt(3, site.x());
                ps.setInt(4, site.y());
                ps.setInt(5, site.z());
                ps.setInt(6, site.rotation());
                ps.setString(7, site.tier());
                ps.setInt(8, site.minEntry());
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                // 写失败只影响重启恢复
            }
        }
    }

    public void delete(String key) {
        if (!db.available() || key == null) {
            return;
        }
        synchronized (db) {
            try (PreparedStatement ps = db.connection()
                    .prepareStatement("DELETE FROM poker_table_config WHERE table_key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            } catch (SQLException e) {
                // 同上
            }
        }
    }
}
