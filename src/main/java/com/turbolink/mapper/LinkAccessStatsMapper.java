package com.turbolink.mapper;

import com.turbolink.entity.LinkAccessStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface LinkAccessStatsMapper {

    /**
     * 按 (short_code, access_date, access_hour) 唯一键做原子 upsert：存在则 pv 累加，否则插入。
     * 用 INSERT ... ON DUPLICATE KEY UPDATE 保证并发下计数不丢失。
     */
    @Update("INSERT INTO t_link_access_stats(short_code, access_date, access_hour, pv) " +
            "VALUES(#{shortCode}, #{accessDate}, #{accessHour}, 1) " +
            "ON DUPLICATE KEY UPDATE pv = pv + 1")
    int upsertPv(@Param("shortCode") String shortCode,
                 @Param("accessDate") LocalDate accessDate,
                 @Param("accessHour") int accessHour);

    @org.apache.ibatis.annotations.Select(
            "SELECT COALESCE(SUM(pv), 0) FROM t_link_access_stats WHERE short_code = #{shortCode}")
    long sumPv(@Param("shortCode") String shortCode);
}
