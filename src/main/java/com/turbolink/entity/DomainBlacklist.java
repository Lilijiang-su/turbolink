package com.turbolink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_domain_blacklist")
public class DomainBlacklist {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String domain;

    private String reason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 0 未删 1 已删 */
    private Integer delFlag;
}
