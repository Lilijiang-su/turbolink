package com.turbolink.controller;

import com.turbolink.migration.MigrationCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 扩容迁移控制接口（运维用）。
 *
 * <p>流程：enableDualWrite → 跑迁移脚本搬存量 → 校验 → enableReadNew → disableDualWrite
 */
@RestController
@RequestMapping("/api/migration")
@RequiredArgsConstructor
public class MigrationController {

    private final MigrationCoordinator coordinator;

    @GetMapping("/phase")
    public Map<String, Object> phase() {
        return Map.of(
                "phase", coordinator.phase(),
                "dualWrite", coordinator.isDualWrite(),
                "readNew", coordinator.isReadNew());
    }

    @PostMapping("/dual-write/on")
    public Map<String, String> enableDualWrite() {
        coordinator.enableDualWrite();
        return Map.of("status", "dual-write on");
    }

    @PostMapping("/dual-write/off")
    public Map<String, String> disableDualWrite() {
        coordinator.disableDualWrite();
        return Map.of("status", "dual-write off");
    }

    @PostMapping("/read-new/on")
    public Map<String, String> enableReadNew() {
        coordinator.enableReadNew();
        return Map.of("status", "read-new on");
    }

    @PostMapping("/read-new/off")
    public Map<String, String> disableReadNew() {
        coordinator.disableReadNew();
        return Map.of("status", "read-new off");
    }
}
