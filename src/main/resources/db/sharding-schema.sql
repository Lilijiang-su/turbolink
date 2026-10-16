-- TurboLink 分库分表 DDL（ShardingSphere 2 库 × 4 表 = 8 片）
-- 分片键：short_code（跳转最高频，按短码路由可直接定位单库单表）
-- t_sequence 是 SINGLE 单表只放 db0；t_short_link 和 t_link_access_stats 分片

-- 短链表字段定义（供两库复用）
-- short_code 必须 utf8mb4_bin 区分大小写（Base62 含大小写混合）

-- ============ 库 0 ============
CREATE DATABASE IF NOT EXISTS turbolink_db0 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE turbolink_db0;

CREATE TABLE IF NOT EXISTS t_sequence (
  biz_type    VARCHAR(32)     NOT NULL,
  max_id      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  step        INT             NOT NULL DEFAULT 1000,
  version     BIGINT          NOT NULL DEFAULT 0,
  update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (biz_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO t_sequence(biz_type, max_id, step, version)
VALUES ('short_link', 0, 1000, 0)
ON DUPLICATE KEY UPDATE biz_type = biz_type;

CREATE TABLE IF NOT EXISTS t_short_link_0 (
  id BIGINT UNSIGNED NOT NULL, short_code VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  full_url VARCHAR(2048) NOT NULL, gid VARCHAR(32) NOT NULL DEFAULT 'default',
  user_id BIGINT UNSIGNED NOT NULL DEFAULT 0, origin_code VARCHAR(16) COLLATE utf8mb4_bin DEFAULT NULL,
  expire_time DATETIME DEFAULT NULL, status TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  del_flag TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_short_code (short_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS t_short_link_1 LIKE t_short_link_0;
CREATE TABLE IF NOT EXISTS t_short_link_2 LIKE t_short_link_0;
CREATE TABLE IF NOT EXISTS t_short_link_3 LIKE t_short_link_0;

CREATE TABLE IF NOT EXISTS t_link_access_stats_0 (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  short_code VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  access_date DATE NOT NULL, access_hour TINYINT NOT NULL DEFAULT 0,
  pv BIGINT UNSIGNED NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_code_date_hour (short_code, access_date, access_hour)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS t_link_access_stats_1 LIKE t_link_access_stats_0;
CREATE TABLE IF NOT EXISTS t_link_access_stats_2 LIKE t_link_access_stats_0;
CREATE TABLE IF NOT EXISTS t_link_access_stats_3 LIKE t_link_access_stats_0;

-- ============ 库 1 ============
CREATE DATABASE IF NOT EXISTS turbolink_db1 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE turbolink_db1;

CREATE TABLE IF NOT EXISTS t_short_link_0 LIKE turbolink_db0.t_short_link_0;
CREATE TABLE IF NOT EXISTS t_short_link_1 LIKE turbolink_db0.t_short_link_0;
CREATE TABLE IF NOT EXISTS t_short_link_2 LIKE turbolink_db0.t_short_link_0;
CREATE TABLE IF NOT EXISTS t_short_link_3 LIKE turbolink_db0.t_short_link_0;

CREATE TABLE IF NOT EXISTS t_link_access_stats_0 LIKE turbolink_db0.t_link_access_stats_0;
CREATE TABLE IF NOT EXISTS t_link_access_stats_1 LIKE turbolink_db0.t_link_access_stats_0;
CREATE TABLE IF NOT EXISTS t_link_access_stats_2 LIKE turbolink_db0.t_link_access_stats_0;
CREATE TABLE IF NOT EXISTS t_link_access_stats_3 LIKE turbolink_db0.t_link_access_stats_0;
