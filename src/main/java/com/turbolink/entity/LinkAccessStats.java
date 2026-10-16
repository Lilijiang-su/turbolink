package com.turbolink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_link_access_stats")
public class LinkAccessStats {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shortCode;

    private LocalDate accessDate;

    /** 小时 0-23 */
    private Integer accessHour;

    private Long pv;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
