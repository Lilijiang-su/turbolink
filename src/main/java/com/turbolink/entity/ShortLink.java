package com.turbolink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_short_link")
public class ShortLink {

    /** 主键 ID，由号段发号器生成，非自增 */
    @TableId(type = IdType.INPUT)
    private Long id;

    private String shortCode;

    private String fullUrl;

    private String gid;

    private Long userId;

    /** 用户自定义短码，可空 */
    private String originCode;

    private LocalDateTime expireTime;

    /** 0 正常 1 禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 0 未删 1 已删 */
    private Integer delFlag;
}
