import java.sql.*;
import java.util.*;

/**
 * 扩容迁移脚本：2 库 × 4 表 → 4 库 × 4 表（16 片）。
 *
 * <p>核心逻辑：
 * <ol>
 *   <li>遍历旧库（db0/db1）的 4 张 t_short_link_* 表，读取全部存量数据</li>
 *   <li>对每条数据，按<b>新路由</b>（db = h % 4, table = (h/4) % 4）计算目标库表</li>
 *   <li>写入新库（db0/db1/db2/db3）对应分片</li>
 *   <li>全量完成后做一致性校验（按新路由重读，比对条数和内容）</li>
 * </ol>
 *
 * <p>路由算法必须与 {@code sharding-4db.yml} 及 ShardingUtil 严格一致：
 * h = hashCode & 0x7fffffff；db = h % 4；table = (h / 4) % 4。
 *
 * <p>用法：java -cp mysql-connector.jar MigrationScript <mysqlUser> <mysqlPassword>
 */
public class MigrationScript {

    private static final String JDBC = "jdbc:mysql://127.0.0.1:3306/";
    private static final int OLD_DB = 2, NEW_DB = 4, TB = 4;

    private final Connection conn;

    public MigrationScript(String user, String pass) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        conn = DriverManager.getConnection(
                JDBC + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
                user, pass);
    }

    static int hash(String code) { return code.hashCode() & 0x7fffffff; }
    static int newDb(String code) { return hash(code) % NEW_DB; }
    static int newTable(String code) { return (hash(code) / NEW_DB) % TB; }

    /** 从旧库读全部短链 */
    List<Row> readAll() throws SQLException {
        List<Row> rows = new ArrayList<>();
        for (int db = 0; db < OLD_DB; db++) {
            for (int tb = 0; tb < TB; tb++) {
                String sql = "SELECT id, short_code, full_url FROM turbolink_db" + db + ".t_short_link_" + tb;
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
        }
        return rows;
    }

    /** 按新路由写入（幂等：目标已存在则跳过，兼容双写窗口已同步的数据） */
    void writeAll(List<Row> rows) throws SQLException {
        conn.setAutoCommit(false);
        int moved = 0, skipped = 0;
        try {
            for (Row r : rows) {
                int db = newDb(r.code);
                int tb = newTable(r.code);
                // 幂等：目标已存在（双写期间已写入）则跳过
                String sql = "INSERT INTO turbolink_db" + db + ".t_short_link_" + tb +
                        "(id, short_code, full_url, gid, user_id, status, del_flag) VALUES(?,?,?,'default',0,0,0) " +
                        "ON DUPLICATE KEY UPDATE full_url = full_url";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, r.id);
                    ps.setString(2, r.code);
                    ps.setString(3, r.url);
                    int affected = ps.executeUpdate();
                    if (affected == 1) moved++;
                    else skipped++;
                }
            }
            conn.commit();
            System.out.println("[迁移] 搬迁完成：新写入 " + moved + " 条，跳过(双写已存在) " + skipped + " 条");
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** 一致性校验：按新路由重读每条数据，确认存在且内容一致 */
    int verify(List<Row> rows) throws SQLException {
        int mismatch = 0;
        for (Row r : rows) {
            int db = newDb(r.code);
            int tb = newTable(r.code);
            String sql = "SELECT full_url FROM turbolink_db" + db + ".t_short_link_" + tb +
                    " WHERE id = ? AND short_code = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, r.id);
                ps.setString(2, r.code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (!rs.getString(1).equals(r.url)) mismatch++;
                    } else {
                        mismatch++;
                    }
                }
            }
        }
        return mismatch;
    }

    /**
     * 清理：删除旧库(db0/db1)中「不再属于新路由」的数据。
     * 搬迁完成后，h%4==2 的数据应只在 db2，h%4==3 的应只在 db3，
     * 若仍残留在 db0/db1 则为脏数据，需删除。
     */
    int cleanup() throws SQLException {
        int removed = 0;
        for (int db = 0; db < OLD_DB; db++) {
            for (int tb = 0; tb < TB; tb++) {
                // 读旧库该表的全部 short_code
                List<String> codes = new ArrayList<>();
                String readSql = "SELECT short_code FROM turbolink_db" + db + ".t_short_link_" + tb;
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(readSql)) {
                    while (rs.next()) codes.add(rs.getString(1));
                }
                // 删除不再属于新路由的
                for (String code : codes) {
                    if (newDb(code) != db || newTable(code) != tb) {
                        String delSql = "DELETE FROM turbolink_db" + db + ".t_short_link_" + tb +
                                " WHERE short_code = ?";
                        try (PreparedStatement ps = conn.prepareStatement(delSql)) {
                            ps.setString(1, code);
                            removed += ps.executeUpdate();
                        }
                    }
                }
            }
        }
        return removed;
    }

    public static void main(String[] args) throws Exception {
        String user = args.length > 0 ? args[0] : "root";
        String pass = args.length > 1 ? args[1] : "123456";
        MigrationScript script = new MigrationScript(user, pass);

        System.out.println("=== 阶段1: 读取旧库存量 ===");
        List<Row> rows = script.readAll();
        System.out.println("读取 " + rows.size() + " 条");

        System.out.println("=== 阶段2: 按新路由搬迁 ===");
        script.writeAll(rows);

        System.out.println("=== 阶段3: 一致性校验 ===");
        int mismatch = script.verify(rows);
        if (mismatch == 0) {
            System.out.println("[校验] 通过：所有 " + rows.size() + " 条数据在新库中一致");
        } else {
            System.out.println("[校验] 失败：发现 " + mismatch + " 条不一致");
        }

        System.out.println("=== 阶段4: 清理旧库残留（不再属于新路由的数据）===");
        int removed = script.cleanup();
        System.out.println("[清理] 删除旧库残留 " + removed + " 条");

        script.close();
    }

    void close() throws SQLException { conn.close(); }

    static class Row {
        long id; String code, url;
        Row(long id, String code, String url) { this.id = id; this.code = code; this.url = url; }
    }
}
