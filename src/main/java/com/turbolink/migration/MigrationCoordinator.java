package com.turbolink.migration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 双写迁移协调器：控制迁移各阶段的开关。
 *
 * <p>迁移五阶段（对应架构图 4-B）：
 * <ol>
 *   <li>单写旧库（dualWrite=false, readNew=false）—— 迁移前常态</li>
 *   <li>双写（dualWrite=true）—— 新旧同时写，新数据开始同步到新库</li>
 *   <li>存量搬迁 —— 由迁移脚本把历史数据按新路由搬过去</li>
 *   <li>数据校验 —— 比对源/目标一致性</li>
 *   <li>切换读 + 关旧写（readNew=true, dualWrite=false）—— 完成迁移</li>
 * </ol>
 *
 * <p>开关用内存原子布尔 + 可运行时热切换，通过 controller 暴露给运维操作。
 */
@Slf4j
@Component
public class MigrationCoordinator {

    /** 是否双写（同时写旧库和新库） */
    private final AtomicBoolean dualWrite = new AtomicBoolean(false);

    /** 读是否切到新库 */
    private final AtomicBoolean readNew = new AtomicBoolean(false);

    public boolean isDualWrite() {
        return dualWrite.get();
    }

    public boolean isReadNew() {
        return readNew.get();
    }

    public void enableDualWrite() {
        dualWrite.set(true);
        log.info("[迁移] 开启双写");
    }

    public void disableDualWrite() {
        dualWrite.set(false);
        log.info("[迁移] 关闭双写");
    }

    public void enableReadNew() {
        readNew.set(true);
        log.info("[迁移] 读流量切到新库");
    }

    public void disableReadNew() {
        readNew.set(false);
        log.info("[迁移] 读流量切回旧库");
    }

    /** 当前阶段描述，用于监控/日志 */
    public String phase() {
        if (readNew.get()) {
            return dualWrite.get() ? "迁移中(双写+读新)" : "迁移完成(读新)";
        }
        return dualWrite.get() ? "迁移中(双写)" : "常态(单写旧库)";
    }
}
