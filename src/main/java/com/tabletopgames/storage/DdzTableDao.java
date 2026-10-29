package com.tabletopgames.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 斗地主牌桌的「位置 + 每桌配置」（{@code ddz_table_config} 表）。
 *
 * <h3>为什么需要它</h3>
 * 2026-10-23 用户反馈：<b>每次重启服务器，斗地主牌桌的 3x3 方块都消失</b>。
 * 根因不是存档坏了，而是从来没存过 —— 关服 {@code DdzManager.shutdown()} 直接
 * {@code DdzGame.destroy()}，而 {@code destroy()} 的语义是「拆桌」，会把 3x3 方块写成空气；
 * 重启后又没有任何恢复逻辑，所以方块一去不回。（UNO 没这个问题，因为它关服也清方块，
 * 但启动时会 {@code restoreTable()} 按坐标重放一遍。）
 *
 * <h3>为什么启动时必须「重放方块」而不只是恢复数据</h3>
 * 自定义方块的运行期 id 由注册顺序决定，MOT 重启后可能重新分配；只留着世界里的旧方块，
 * 有机会变成「认不出的方块」。所以与 UNO 同款：按存档坐标再 {@code setBlock} 一次。
 *
 * <p>一行 = 一张牌桌：{@code table_key} 是 {@code x,y,z,rotation} 拼出来的稳定主键。</p>
 */
public final class DdzTableDao {

    /**
     * 一张牌桌的存档记录（纯数据，读盘后由主线程拿去重建方块与实体）。
     *
     * @param key        主键，{@code x,y,z,rotation}
     * @param level      世界名
     * @param x          3x3 基座<b>最小角</b> 的 x
     * @param tier       场次（free / basic / advanced / master）
     * @param difficulty 默认机器人难度（simple / advanced）
     * @param bidMode    定地主的方式（call_rob / score；空串 = 老存档，按叫抢制处理）
     * @param wildMode   玩法模式（off / wild / heaven；空串 = 老存档，按普通处理）
     * @param handSort   手牌左右排序（big_left / big_right；空串 = 老存档，按大→小处理）
     */
    public record Site(String key, String level, int x, int y, int z, int rotation,
                       String tier, String difficulty, String bidMode, String wildMode,
                       String handSort) {
    }

    private final Database db;

    public DdzTableDao(Database db) {
        this.db = db;
    }

    /**
     * 读出所有牌桌。数据库不可用时返回空表（等价于「这次没有牌桌要恢复」）。
     *
     * <p>{@code level = ''} 的行是历史遗留的「只存配置、没存坐标」的记录，跳过不恢复。</p>
     */
    public List<Site> loadAll() {
        List<Site> out = new ArrayList<>();
        if (!db.available()) {
            return out;
        }
        String sql = """
                SELECT table_key, level, x, y, z, rotation, tier, difficulty, bid_mode, wild_mode,
                       hand_sort
                FROM ddz_table_config WHERE level IS NOT NULL AND level <> ''""";
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
                            rs.getString("difficulty"),
                            rs.getString("bid_mode"),
                            rs.getString("wild_mode"),
                            rs.getString("hand_sort")));
                }
            } catch (SQLException e) {
                // 读失败就当没有牌桌：宁可这次不恢复，也不要把库里的行删掉
            }
        }
        return out;
    }

    /** 插入或更新一张牌桌。 */
    public void save(Site site) {
        if (!db.available() || site == null) {
            return;
        }
        String sql = """
                INSERT INTO ddz_table_config
                    (table_key, tier, difficulty, bid_mode, wild_mode, hand_sort,
                     level, x, y, z, rotation, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(table_key) DO UPDATE SET
                    tier = excluded.tier,
                    difficulty = excluded.difficulty,
                    bid_mode = excluded.bid_mode,
                    wild_mode = excluded.wild_mode,
                    hand_sort = excluded.hand_sort,
                    level = excluded.level,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    rotation = excluded.rotation,
                    updated_at = excluded.updated_at""";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, site.key());
                ps.setString(2, site.tier());
                ps.setString(3, site.difficulty());
                ps.setString(4, site.bidMode() == null ? "" : site.bidMode());
                ps.setString(5, site.wildMode() == null ? "" : site.wildMode());
                ps.setString(6, site.handSort() == null ? "" : site.handSort());
                ps.setString(7, site.level());
                ps.setInt(8, site.x());
                ps.setInt(9, site.y());
                ps.setInt(10, site.z());
                ps.setInt(11, site.rotation());
                ps.setLong(12, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                // 写失败只影响下次重启能不能恢复，本次运行照常
            }
        }
    }

    /** 删掉一张牌桌（玩家把桌挖掉时）。 */
    public void delete(String key) {
        if (!db.available() || key == null) {
            return;
        }
        synchronized (db) {
            try (PreparedStatement ps = db.connection()
                    .prepareStatement("DELETE FROM ddz_table_config WHERE table_key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            } catch (SQLException e) {
                // 同上：删不掉最多留下一条恢复后会重合的行
            }
        }
    }
}
