package com.turbolink.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_sequence")
public class Sequence {

    /** 业务类型，如 short_link */
    @TableId
    private String bizType;

    /** 当前已分配的最大 ID */
    private Long maxId;

    /** 号段步长 */
    private Integer step;

    /** 乐观锁版本号 */
    private Long version;

    private LocalDateTime updateTime;
}
