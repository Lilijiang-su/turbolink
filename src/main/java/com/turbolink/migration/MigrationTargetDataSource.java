package com.turbolink.migration;

import com.turbolink.util.ShardingUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 扩容目标数据源（4 库 × 4 表 = 16 片）。
 *
 * <p>用 ShardingSphereDriver 的 URL 方式独立构建，与主数据源（2 库）并存。
 * 双写迁移期间，写请求同时落到旧库（主数据源）和新库（本数据源）。
 *
 * <p>采用<b>惰性初始化 + 容错</b>：首次双写时才真正建连，连接失败只影响双写、
 * 不影响应用主链路启动（迁移是旁路，绝不能让主业务被它拖垮）。
 *
 * <p>新库路由算法见 {@code sharding-4db.yml}：db = h % 4, table = (h / 4) % 4。
 */
@Slf4j
@Component
public class MigrationTargetDataSource {

    private static final String URL_4DB =
            "jdbc:shardingsphere:classpath:sharding-4db.yml?placeholder-type=environment";

    private volatile Connection connection;

    /** 惰性获取连接，首次调用才初始化 */
    private Connection getConnection() {
        if (connection == null) {
            synchronized (this) {
                if (connection == null) {
                    try {
                        Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver");
                        connection = DriverManager.getConnection(URL_4DB);
                        log.info("[迁移] 扩容目标数据源(4库)已就绪");
                    } catch (Exception e) {
                        log.error("[迁移] 扩容目标数据源初始化失败", e);
                        throw new IllegalStateException("扩容目标数据源初始化失败", e);
                    }
                }
            }
        }
        return connection;
    }

    /**
     * 向新库写入一条短链。
     * SQL 不带库表名，由 ShardingSphere 按新路由自动定位到 16 片之一。
     *
     * <p><b>关键边界</b>：双写时若新旧路由落在同一物理库表，主库已插入该数据，
     * 再写会导致主键冲突。因此先判断「新旧库表是否相同」，相同则跳过二次写入。
     * 这正是扩容双写迁移最经典的坑。
     *
     * @return 影响行数（跳过的返回 0）
     */
    public synchronized int insertShortLink(long id, String shortCode, String fullUrl) {
        // 新旧路由同库同表 → 主库已写入正确位置，跳过避免主键冲突
        if (ShardingUtil.dbIndex(shortCode) == ShardingUtil.newDbIndex(shortCode)
                && ShardingUtil.tableIndex(shortCode) == ShardingUtil.newTableIndex(shortCode)) {
            log.debug("[迁移] 新旧路由相同，跳过双写 shortCode={}", shortCode);
            return 0;
        }
        String sql = "INSERT INTO t_short_link(id, short_code, full_url, gid, user_id, status, del_flag) " +
                "VALUES(?, ?, ?, 'default', 0, 0, 0)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, shortCode);
            ps.setString(3, fullUrl);
            return ps.executeUpdate();
        } catch (Exception e) {
            log.error("[迁移] 新库写入失败 shortCode={}", shortCode, e);
            throw new RuntimeException("新库写入失败", e);
        }
    }

    /**
     * 从新库（4库16片）读短码对应的长链。
     * 由 ShardingSphere 按新路由自动定位到正确分片。
     *
     * @return fullUrl；不存在返回 null
     */
    public String queryFullUrl(String shortCode) {
        String sql = "SELECT full_url FROM t_short_link WHERE short_code = ? AND del_flag = 0 AND status = 0";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, shortCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            log.error("[迁移] 新库读取失败 shortCode={}", shortCode, e);
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
    }
}
