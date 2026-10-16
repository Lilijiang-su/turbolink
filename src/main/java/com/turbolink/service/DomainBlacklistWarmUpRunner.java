package com.turbolink.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时加载域名黑名单到内存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainBlacklistWarmUpRunner implements ApplicationRunner {

    private final DomainBlacklistService blacklistService;

    @Override
    public void run(ApplicationArguments args) {
        blacklistService.reload();
    }
}
