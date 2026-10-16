package com.turbolink.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.turbolink.entity.DomainBlacklist;
import com.turbolink.mapper.DomainBlacklistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 域名黑名单校验。
 *
 * <p>短链常被用于传播恶意/钓鱼链接，创建短链前需校验目标域名是否在黑名单。
 * 黑名单数据量小，采用「DB 全量加载到内存 + 定时可刷新」的简化策略，
 * 校验走内存 Set，O(1) 判断，不增加主链路 DB 压力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainBlacklistService {

    private final DomainBlacklistMapper mapper;

    /** 内存黑名单集合，启动时全量加载 */
    private volatile Set<String> blacklist = ConcurrentHashMap.newKeySet();

    /** 判断 URL 的域名是否在黑名单 */
    public boolean isBlocked(String url) {
        String domain = extractDomain(url);
        return domain != null && blacklist.contains(domain.toLowerCase());
    }

    /** 提取 URL 的域名（不含端口），非法 URL 返回 null */
    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** 全量重载（供管理接口或定时任务调用） */
    public void reload() {
        Set<String> fresh = ConcurrentHashMap.newKeySet();
        mapper.selectList(
                Wrappers.<DomainBlacklist>lambdaQuery()
                        .eq(DomainBlacklist::getDelFlag, 0))
                .forEach(b -> fresh.add(b.getDomain().toLowerCase()));
        this.blacklist = fresh;
        log.info("[黑名单] 重载完成，共 {} 个域名", fresh.size());
    }

    /** 当前黑名单数量 */
    public int size() {
        return blacklist.size();
    }
}
