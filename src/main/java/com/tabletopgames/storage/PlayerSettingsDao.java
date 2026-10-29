package com.tabletopgames.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家个人设置（{@code player_settings} 表）。
 *
 * <p>目前只有一项：定位器准星开关。默认是<b>开</b>，所以这里存的是「主动关掉的人」——
 * 与 {@link com.tabletopgames.game.LocatorTitleService} 里的 {@code off} 集合同一个语义，
 * 数据库只是把这份集合持久化（重启后仍然记得谁关过）。</p>
 */
public final class PlayerSettingsDao {

    /** 定位器准星：默认开。 */
    private static final String KEY_LOCATOR_TITLE = "locator_title";

    private final Database db;

    public PlayerSettingsDao(Database db) {
        this.db = db;
    }

    /** 读出所有「关掉准星」的玩家。数据库不可用时返回空集（等价于全员默认开）。 */
    public Set<UUID> loadLocatorTitleOff() {
        Set<UUID> off = new HashSet<>();
        if (!db.available()) {
            return off;
        }
        String sql = "SELECT uuid FROM player_settings WHERE locator_title = 0";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        off.add(UUID.fromString(rs.getString("uuid")));
                    } catch (IllegalArgumentException ignored) {
                        // 脏数据（不是 UUID）直接跳过，不让一行坏数据卡住整表加载
                    }
                }
            } catch (SQLException e) {
                // 读失败就当全员默认开
            }
        }
        return off;
    }

    /** 写入某个玩家的准星开关状态（{@code on=true} 表示开着）。 */
    public void saveLocatorTitle(UUID playerId, boolean on) {
        if (!db.available()) {
            return;
        }
        String sql = """
                INSERT INTO player_settings (uuid, locator_title, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET locator_title = excluded.locator_title,
                                                updated_at    = excluded.updated_at""";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.setInt(2, on ? 1 : 0);
                ps.setLong(3, System.currentTimeMillis() / 1000L);
                ps.executeUpdate();
            } catch (SQLException e) {
                // 写失败不影响当前会话的内存态
            }
        }
    }

    /** 读出所有开启「军旗 · 敌方棋子倒置」的玩家。 */
    public Set<UUID> loadJunqiFlipEnemies() {
        Set<UUID> on = new HashSet<>();
        if (!db.available()) {
            return on;
        }
        String sql = "SELECT uuid FROM player_settings WHERE junqi_flip_enemy = 1";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        on.add(UUID.fromString(rs.getString("uuid")));
                    } catch (IllegalArgumentException ignored) {
                        // 脏数据跳过
                    }
                }
            } catch (SQLException ignored) {
                // 读失败按全员默认关处理
            }
        }
        return on;
    }

    /** 写入某个玩家的「敌方棋子倒置」开关。 */
    public void saveJunqiFlipEnemy(UUID playerId, boolean on) {
        if (!db.available()) {
            return;
        }
        String sql = """
                INSERT INTO player_settings (uuid, junqi_flip_enemy, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET junqi_flip_enemy = excluded.junqi_flip_enemy,
                                                updated_at       = excluded.updated_at""";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.setInt(2, on ? 1 : 0);
                ps.setLong(3, System.currentTimeMillis() / 1000L);
                ps.executeUpdate();
            } catch (SQLException e) {
                // 写失败只影响本次会话的持久化，内存态仍然生效
            }
        }
    }

    /** 调试/回归用：这张表是否存在（打开失败时为 false）。 */
    public boolean tableReady() {
        return db.available();
    }

    static String key() {
        return KEY_LOCATOR_TITLE;
    }
}
