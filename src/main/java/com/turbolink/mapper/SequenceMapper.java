package com.turbolink.mapper;

import com.turbolink.entity.Sequence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SequenceMapper {

    @Select("SELECT biz_type, max_id, step, version, update_time " +
            "FROM t_sequence WHERE biz_type = #{bizType}")
    Sequence selectByBizType(@Param("bizType") String bizType);

    /** 乐观锁更新：版本号一致才更新，返回影响行数 */
    @Update("UPDATE t_sequence SET max_id = max_id + step, version = version + 1 " +
            "WHERE biz_type = #{bizType} AND version = #{version}")
    int updateMaxId(@Param("bizType") String bizType, @Param("version") Long version);
}
