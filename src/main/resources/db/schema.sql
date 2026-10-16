-- TurboLink 短链系统初始化脚本

CREATE DATABASE IF NOT EXISTS turbolink
  DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;

USE turbolink;

-- 短链映射表（生产环境按 short_code 分 2 库 × 4 表，此处为单表演示）
CREATE TABLE IF NOT EXISTS t_short_link (
  id           BIGINT UNSIGNED NOT NULL COMMENT '主键ID(发号器生成)',
  short_code   VARCHAR(16)     COLLATE utf8mb4_bin NOT NULL COMMENT '短码,如 aB3xK9(区分大小写)',
  full_url     VARCHAR(2048)   NOT NULL COMMENT '原始长链接',
  gid          VARCHAR(32)     NOT NULL DEFAULT 'default' COMMENT '分组标识',
  user_id      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建者,0=匿名',
  origin_code  VARCHAR(16)     COLLATE utf8mb4_bin DEFAULT NULL COMMENT '自定义短码(可空,区分大小写)',
  expire_time  DATETIME        DEFAULT NULL COMMENT '过期时间,NULL=永久',
  status       TINYINT         NOT NULL DEFAULT 0 COMMENT '0正常 1禁用',
  create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP,
  del_flag     TINYINT         NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_short_code (short_code),
  KEY idx_user_create (user_id, create_time),
  KEY idx_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短链映射表';

-- 号段发号表
CREATE TABLE IF NOT EXISTS t_sequence (
  biz_type    VARCHAR(32)     NOT NULL COMMENT '业务类型,如 short_link',
  max_id      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前已分配最大ID',
  step        INT             NOT NULL DEFAULT 1000 COMMENT '号段步长',
  version     BIGINT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (biz_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号段发号表';

INSERT INTO t_sequence(biz_type, max_id, step, version)
VALUES ('short_link', 0, 1000, 0)
ON DUPLICATE KEY UPDATE biz_type = biz_type;

-- 修复历史表：短码列必须区分大小写，否则 Base62 的 '1a' 与 '1A' 会被唯一索引误判为冲突
ALTER TABLE t_short_link
  MODIFY short_code VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT '短码(区分大小写)';

-- 短链访问统计表（由 MQ 消费者异步聚合写入）
CREATE TABLE IF NOT EXISTS t_link_access_stats (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  short_code  VARCHAR(16)     COLLATE utf8mb4_bin NOT NULL COMMENT '短码',
  access_date DATE            NOT NULL COMMENT '统计日期',
  access_hour TINYINT         NOT NULL DEFAULT 0 COMMENT '小时(0-23)',
  pv          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '访问次数',
  create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code_date_hour (short_code, access_date, access_hour)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短链访问统计表';
