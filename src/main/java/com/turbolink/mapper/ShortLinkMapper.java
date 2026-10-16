package com.turbolink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.turbolink.entity.ShortLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLink> {

    /**
     * 取全部有效短码，供启动时预热布隆过滤器。
     *
     * <p>注意：此查询无分片键，ShardingSphere 会广播到全部 8 片再合并结果。
     * 适用于当前数据量（数万级）；生产环境大数据量应改为逐片游标遍历。
     */
    @Select("SELECT short_code FROM t_short_link WHERE del_flag = 0")
    List<String> selectAllCodes();
}
