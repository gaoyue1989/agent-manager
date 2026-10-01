package io.agentmanager.framework.support;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;

/**
 * 测试侧 schema 装配：按序执行 classpath:db/migration/V*.sql。
 *
 * <p>生产库的建表/补列/回填由 Flyway 在启动时执行（application.yml spring.flyway.*）；
 * Store 构造器已不再手工 DDL。真实 MySQL 的 *MySqlIT 走本类执行同一批迁移文件，
 * 保证测试 schema 与生产迁移链同源。
 *
 * <p>Flyway 靠历史表保证每个版本只执行一次；本工具没有历史表，改为容忍
 * 「表/列已存在」类错误（错误码 1050/1060）实现重复执行等价，其余异常照常抛出。
 * 简易分号切分即可：迁移文件均为单语句或独立语句，不含存储过程/分号字符串。
 */
public final class TestSchemaMigrator {

    /** MySQL「已存在」类错误：1050 = 表已存在，1060 = 列已存在（与 Flyway 单次执行语义等价的跳过口径） */
    private static final java.util.Set<Integer> ALREADY_EXISTS_CODES = java.util.Set.of(1050, 1060);

    private TestSchemaMigrator() {
    }

    /** 按版本号升序执行全部迁移（V1、V2…；已存在的表/列由语句本身保证幂等——V6 为 INSERT IGNORE） */
    public static void migrate(DataSource dataSource) throws Exception {
        var files = new ArrayList<String>();
        // 迁移目录在测试 classpath（target/classes）下是物理目录，直接枚举；
        // jar 内目录不可枚举，但测试环境不会打成 jar
        var dir = new java.io.File(TestSchemaMigrator.class.getResource("/db/migration").toURI());
        for (var f : dir.listFiles()) {
            if (f.isFile() && f.getName().endsWith(".sql")) {
                files.add(f.getName());
            }
        }
        // 按版本号数字排序（修 #58 low）：文件名字典序会让 V10 排在 V2 前——两位数版本
        // 合入时 *MySqlIT 将按错误顺序执行迁移，与「测试 schema 与生产迁移链同源」契约相悖
        files.sort(Comparator.comparingInt(TestSchemaMigrator::versionOf));
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            for (var name : files) {
                var sql = new String(TestSchemaMigrator.class
                    .getResourceAsStream("/db/migration/" + name).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                for (var statement : sql.split(";")) {
                    var trimmed = stripComments(statement);
                    if (!trimmed.isBlank()) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute(trimmed);
                        } catch (java.sql.SQLException e) {
                            if (!ALREADY_EXISTS_CODES.contains(e.getErrorCode())) {
                                throw e;
                            }
                        }
                    }
                }
            }
        }
    }

    /** 迁移文件名中的版本号（V&lt;N&gt;__ 前缀；不匹配的排最后兜底） */
    static int versionOf(String filename) {
        var m = java.util.regex.Pattern.compile("^V(\\d+)__").matcher(filename);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** 去掉行注释（-- 开头），避免注释里的中文分号/引号干扰语句切分 */
    private static String stripComments(String statement) {
        var sb = new StringBuilder();
        for (var line : statement.split("\n")) {
            var t = line.stripLeading();
            if (!t.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
