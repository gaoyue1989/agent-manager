package io.agentmanager.framework.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TestSchemaMigrator 版本序（修 #58 low）：文件名字典序会让 V10 排在 V2 前，两位数
 * 版本合入时 *MySqlIT 将按错误顺序执行迁移——必须按 V&lt;N&gt;__ 的数字排序。
 */
class TestSchemaMigratorVersionOrderTest {

    @Test
    void versionOrderShouldSurviveTwoDigitVersions() {
        assertTrue(TestSchemaMigrator.versionOf("V2__2cd55f9_agui_interrupt.sql")
            < TestSchemaMigrator.versionOf("V10__whatever.sql"), "V2 必须先于 V10");
        assertEquals(1, TestSchemaMigrator.versionOf("V1__baseline_e91d1f0.sql"));
        assertEquals(8, TestSchemaMigrator.versionOf("V8__remote_task_registry.sql"));
    }

    @Test
    void unparseableNamesShouldSortLast() {
        var v = TestSchemaMigrator.versionOf("readme.txt");
        assertTrue(v >= Integer.MAX_VALUE - 1, "非迁移文件兜底排最后");
    }
}
