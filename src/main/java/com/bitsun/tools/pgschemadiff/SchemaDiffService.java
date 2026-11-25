package com.bitsun.tools.pgschemadiff;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 比对两个 PostgreSQL 库（A、B）的表结构，找出 B 相对 A 缺少的表和字段，
 * 并按实例（schema）生成补全用的 SQL 文档：
 * 缺表 -&gt; CREATE TABLE（含主键、唯一约束、独立索引、序列、表/字段 comment）；
 * 缺字段 -&gt; ALTER TABLE ADD COLUMN（含字段 comment）。
 * 外键不还原。
 *
 * A、B 各自是"多实例"结构：同一个 database 下有多个 schema，每个 schema 是一个实例，
 * 每个实例下有名字相同的一套表。按 schema 名字把 A 的实例和 B 的实例一一对应，
 * 再按表名对应，比较字段（列名）差异。
 */
public class SchemaDiffService {

    public static class DbConfig {
        public String host;
        public int port = 5432;
        public String dbName;
        public String user;
        public String password;
        /** 默认不强制 SSL；只有目标库明确要求 SSL 时才勾选，否则会报 "server does not support SSL" */
        public boolean ssl = false;

        public String jdbcUrl() {
            String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
            if (ssl) {
                // sslmode=require 只加密不校验证书链，不需要额外导入云厂商的 CA 证书
                url += "?sslmode=require";
            }
            return url;
        }
    }

    public static class DiffResult {
        final List<String> missingSchemas = new ArrayList<>();
        final List<String[]> missingTables = new ArrayList<>();       // [schema, table]
        final List<Object[]> missingColumns = new ArrayList<>();      // [schema, table, column, ColumnInfo]
    }

    /** 序列的完整定义，来自 pg_sequences，用于给新表里的 serial/identity 字段原样重建序列 */
    public static class SequenceDef {
        String qualifiedName;
        boolean ownedByColumn;
        long startValue;
        long minValue;
        long maxValue;
        long incrementBy;
        long cacheSize;
        boolean cycle;
    }

    /** 缺失表除字段外还要还原的东西：主键、唯一约束、独立索引、序列 */
    public static class TableExtras {
        String primaryKeyName;
        String primaryKeyDef;
        final List<String[]> uniqueConstraints = new ArrayList<>(); // [name, def]
        final List<String> indexDefs = new ArrayList<>();
        final Map<String, SequenceDef> sequenceByColumn = new LinkedHashMap<>();
    }

    /** 查这个库里所有非系统 schema 的名字，供界面上"查询实例"用 */
    public List<String> fetchSchemaNames(DbConfig config) throws SQLException {
        List<String> schemas = new ArrayList<>();
        String sql = "SELECT schema_name FROM information_schema.schemata "
                + "WHERE schema_name NOT IN ('pg_catalog', 'information_schema') "
                + "AND schema_name NOT LIKE 'pg_toast%' "
                + "AND schema_name NOT LIKE 'pg_temp%' "
                + "ORDER BY schema_name";
        try (Connection conn = DriverManager.getConnection(config.jdbcUrl(), config.user, config.password);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                schemas.add(rs.getString("schema_name"));
            }
        }
        return schemas;
    }

    /** schema -> table -> column -> ColumnInfo（含字段 comment） */
    public Map<String, Map<String, Map<String, ColumnInfo>>> fetchColumns(DbConfig config, List<String> schemaNames) throws SQLException {
        return fetchColumns(config, schemaNames, null);
    }

    /** schema -> table -> column -> ColumnInfo（含字段 comment），logger 非空时会把跳过的权限问题报出来 */
    public Map<String, Map<String, Map<String, ColumnInfo>>> fetchColumns(DbConfig config, List<String> schemaNames,
                                                                           Consumer<String> logger) throws SQLException {
        Map<String, Map<String, Map<String, ColumnInfo>>> result = new LinkedHashMap<>();

        StringBuilder sql = new StringBuilder(
                "SELECT table_schema, table_name, column_name, data_type, udt_name, "
                        + "character_maximum_length, numeric_precision, numeric_scale, is_nullable, column_default, "
                        + "identity_generation, identity_start, identity_minimum, identity_maximum, "
                        + "identity_increment, identity_cycle, seqstart, seqmin, seqmax, seqincrement, seqcache, seqcycle "
                        + "FROM information_schema.columns "
                        + "LEFT JOIN pg_catalog.pg_sequence ON seqrelid = CASE WHEN is_identity = 'YES' "
                        + "THEN pg_get_serial_sequence(format('%I.%I', table_schema, table_name), column_name)::regclass END "
                        + "WHERE table_schema NOT IN ('pg_catalog', 'information_schema') "
                        + "AND table_schema NOT LIKE 'pg_toast%' "
                        + "AND table_schema NOT LIKE 'pg_temp%'");
        appendSchemaFilter(sql, "table_schema", schemaNames);
        sql.append(" ORDER BY table_schema, table_name, ordinal_position");

        try (Connection conn = DriverManager.getConnection(config.jdbcUrl(), config.user, config.password)) {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bindSchemaFilter(ps, 1, schemaNames);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String schema = rs.getString("table_schema");
                        String table = rs.getString("table_name");
                        String column = rs.getString("column_name");
                        String dataType = rs.getString("data_type");
                        String udtName = rs.getString("udt_name");

                        int charLenRaw = rs.getInt("character_maximum_length");
                        Integer charLen = rs.wasNull() ? null : charLenRaw;
                        int numPrecisionRaw = rs.getInt("numeric_precision");
                        Integer numPrecision = rs.wasNull() ? null : numPrecisionRaw;
                        int numScaleRaw = rs.getInt("numeric_scale");
                        Integer numScale = rs.wasNull() ? null : numScaleRaw;
                        String isNullable = rs.getString("is_nullable");

                        ColumnInfo info = new ColumnInfo(dataType, udtName, charLen, numPrecision, numScale, isNullable);
                        info.setDefaultExpression(rs.getString("column_default"));
                        String identityGeneration = rs.getString("identity_generation");
                        if (identityGeneration != null) {
                            if (rs.getString("seqstart") == null && logger != null) {
                                logger.accept("[警告] " + schema + "." + table + "." + column
                                        + " 未查到底层 identity 序列，将使用能读取的列元数据，缺失的参数由目标库采用默认值");
                            }
                            info.setIdentityClause(buildIdentityClause(rs, identityGeneration));
                        }
                        result.computeIfAbsent(schema, k -> new LinkedHashMap<>())
                                .computeIfAbsent(table, k -> new LinkedHashMap<>())
                                .put(column, info);
                    }
                }
            }

            // information_schema 里没有 comment，要另外查 pg_catalog 再合并进 ColumnInfo。
            // pg_catalog 不像 information_schema 那样自动按权限过滤，schema 范围只能收窄到
            // 上面已经确认能读出列的那些（result.keySet()），不能用调用方传进来的原始过滤条件
            // （很可能是空=全部，会扫到没有 USAGE 权限的 schema 导致整条查询报错）。
            try {
                Map<String, Map<String, Map<String, String>>> columnComments =
                        fetchColumnComments(conn, new ArrayList<>(result.keySet()));
                for (Map.Entry<String, Map<String, Map<String, ColumnInfo>>> schemaEntry : result.entrySet()) {
                    Map<String, Map<String, String>> schemaComments = columnComments.get(schemaEntry.getKey());
                    if (schemaComments == null) {
                        continue;
                    }
                    for (Map.Entry<String, Map<String, ColumnInfo>> tableEntry : schemaEntry.getValue().entrySet()) {
                        Map<String, String> tableComments = schemaComments.get(tableEntry.getKey());
                        if (tableComments == null) {
                            continue;
                        }
                        for (Map.Entry<String, ColumnInfo> columnEntry : tableEntry.getValue().entrySet()) {
                            columnEntry.getValue().setComment(tableComments.get(columnEntry.getKey()));
                        }
                    }
                }
            } catch (SQLException e) {
                if (logger != null) {
                    logger.accept("[警告] 读取字段 comment 失败，本次跳过 comment: " + e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * schema -> table -> comment，用于给缺失的表整体生成 COMMENT ON TABLE。
     * schemaNames 建议传"已经确认能访问的 schema 列表"（比如 fetchColumns 结果的 keySet()），
     * 而不是用户填的原始过滤条件——pg_catalog 不像 information_schema 会自动按权限过滤，
     * 传了没权限的 schema 会导致整条查询失败；这里额外兜底捕获，失败就返回目前查到的部分结果。
     */
    public Map<String, Map<String, String>> fetchTableComments(DbConfig config, List<String> schemaNames,
                                                                 Consumer<String> logger) throws SQLException {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(config.jdbcUrl(), config.user, config.password)) {
            StringBuilder sql = new StringBuilder(
                    "SELECT n.nspname AS table_schema, c.relname AS table_name, d.description AS comment "
                            + "FROM pg_catalog.pg_class c "
                            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                            + "LEFT JOIN pg_catalog.pg_description d ON d.objoid = c.oid AND d.objsubid = 0 "
                            + "WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') "
                            + "AND n.nspname NOT LIKE 'pg_toast%' "
                            + "AND n.nspname NOT LIKE 'pg_temp%' "
                            + "AND d.description IS NOT NULL");
            appendSchemaFilter(sql, "n.nspname", schemaNames);

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bindSchemaFilter(ps, 1, schemaNames);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getString("table_schema"), k -> new LinkedHashMap<>())
                                .put(rs.getString("table_name"), rs.getString("comment"));
                    }
                }
            } catch (SQLException e) {
                if (logger != null) {
                    logger.accept("[警告] 读取表 comment 失败，本次跳过表 comment: " + e.getMessage());
                }
            }
        }
        return result;
    }

    /** schema -> table -> column -> comment，只查有 comment 的字段（走 pg_catalog，不受 information_schema 限制） */
    private Map<String, Map<String, Map<String, String>>> fetchColumnComments(Connection conn, List<String> schemaNames) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT n.nspname AS table_schema, c.relname AS table_name, a.attname AS column_name, "
                        + "d.description AS comment "
                        + "FROM pg_catalog.pg_attribute a "
                        + "JOIN pg_catalog.pg_class c ON c.oid = a.attrelid "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                        + "JOIN pg_catalog.pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum "
                        + "WHERE a.attnum > 0 AND NOT a.attisdropped "
                        + "AND n.nspname NOT IN ('pg_catalog', 'information_schema') "
                        + "AND n.nspname NOT LIKE 'pg_toast%' "
                        + "AND n.nspname NOT LIKE 'pg_temp%'");
        appendSchemaFilter(sql, "n.nspname", schemaNames);

        Map<String, Map<String, Map<String, String>>> result = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindSchemaFilter(ps, 1, schemaNames);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.computeIfAbsent(rs.getString("table_schema"), k -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getString("table_name"), k -> new LinkedHashMap<>())
                            .put(rs.getString("column_name"), rs.getString("comment"));
                }
            }
        }
        return result;
    }

    /**
     * 查一张表的主键/唯一约束/独立索引/序列定义，全部用 PG 自带函数（pg_get_constraintdef、
     * pg_indexes.indexdef、pg_get_serial_sequence）原样读出来，而不是自己按列拼，
     * 这样复合键、部分唯一索引之类的写法也不会被拼错。只在处理"B 库完全缺失"的表时才调用。
     * 三块（约束/索引/序列）分开兜底，某一块因为权限问题查不出来，不影响其它块和基本的 CREATE TABLE。
     */
    public TableExtras fetchTableExtras(Connection conn, String schema, String table, Consumer<String> logger) {
        TableExtras extras = new TableExtras();
        Set<String> constraintNames = new HashSet<>();

        String constraintSql = "SELECT con.conname, con.contype, pg_get_constraintdef(con.oid) AS condef "
                + "FROM pg_constraint con "
                + "JOIN pg_class rel ON rel.oid = con.conrelid "
                + "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace "
                + "WHERE con.contype IN ('p','u') AND nsp.nspname = ? AND rel.relname = ?";
        try (PreparedStatement ps = conn.prepareStatement(constraintSql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("conname");
                    String type = rs.getString("contype");
                    String def = rs.getString("condef");
                    constraintNames.add(name);
                    if ("p".equals(type)) {
                        extras.primaryKeyName = name;
                        extras.primaryKeyDef = def;
                    } else {
                        extras.uniqueConstraints.add(new String[]{name, def});
                    }
                }
            }
        } catch (SQLException e) {
            if (logger != null) {
                logger.accept("[警告] " + schema + "." + table + " 读取主键/唯一约束失败，跳过: " + e.getMessage());
            }
        }

        // 独立索引：排除主键/唯一约束自带的索引（名字相同），避免重复建
        String indexSql = "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?";
        try (PreparedStatement ps = conn.prepareStatement(indexSql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("indexname");
                    if (!constraintNames.contains(indexName)) {
                        extras.indexDefs.add(rs.getString("indexdef"));
                    }
                }
            }
        } catch (SQLException e) {
            if (logger != null) {
                logger.accept("[警告] " + schema + "." + table + " 读取索引失败，跳过: " + e.getMessage());
            }
        }

        // 普通 nextval 默认值通过 pg_attrdef 的依赖定位序列，包括没有 OWNED BY 的序列。
        // Identity 序列由 identity 列定义创建。
        String seqSql = "SELECT a.attname, pg_get_expr(ad.adbin, ad.adrelid) AS default_expr, "
                + "owned.sequence_oid, referenced.refobjid AS default_sequence_oid "
                + "FROM pg_attribute a "
                + "JOIN pg_class rel ON rel.oid = a.attrelid "
                + "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace "
                + "LEFT JOIN pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum "
                + "LEFT JOIN LATERAL (SELECT pg_get_serial_sequence(format('%I.%I', nsp.nspname, rel.relname), "
                + "a.attname)::regclass::oid AS sequence_oid) owned ON true "
                + "LEFT JOIN pg_depend referenced ON referenced.classid = 'pg_attrdef'::regclass "
                + "AND referenced.objid = ad.oid AND referenced.refclassid = 'pg_class'::regclass "
                + "AND EXISTS (SELECT 1 FROM pg_sequence WHERE seqrelid = referenced.refobjid) "
                + "WHERE nsp.nspname = ? AND rel.relname = ? AND a.attnum > 0 AND NOT a.attisdropped AND a.attidentity = ''";
        try (PreparedStatement ps = conn.prepareStatement(seqSql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String column = rs.getString("attname");
                    String ownedOid = rs.getString("sequence_oid");
                    if (extractSequenceFromDefault(rs.getString("default_expr")) == null) {
                        continue;
                    }
                    String sequenceOid = rs.getString("default_sequence_oid");
                    if (sequenceOid == null) {
                        sequenceOid = ownedOid;
                    }
                    SequenceDef seqDef = sequenceOid == null ? null : fetchSequenceDef(conn, sequenceOid);
                    if (seqDef != null) {
                        seqDef.ownedByColumn = sequenceOid.equals(ownedOid);
                        extras.sequenceByColumn.put(column, seqDef);
                    } else if (logger != null) {
                        logger.accept("[警告] " + schema + "." + table + "." + column
                                + " 未找到默认值引用的序列，执行 SQL 前需确认目标库已创建该序列");
                    }
                }
            }
        } catch (SQLException e) {
            if (logger != null) {
                logger.accept("[警告] " + schema + "." + table + " 读取序列失败，跳过: " + e.getMessage());
            }
        }

        return extras;
    }

    private static final java.util.regex.Pattern NEXTVAL_PATTERN =
            java.util.regex.Pattern.compile("(?:pg_catalog\\.)?nextval\\('([^']+)'(?:::regclass)?\\)");

    /** 从列的 DEFAULT 表达式文本（如 nextval('schema.seq_name'::regclass)）里提取序列名 */
    private static String extractSequenceFromDefault(String defaultExpr) {
        if (defaultExpr == null) {
            return null;
        }
        java.util.regex.Matcher m = NEXTVAL_PATTERN.matcher(defaultExpr);
        return m.matches() ? m.group(1) : null;
    }

    private SequenceDef fetchSequenceDef(Connection conn, String sequenceOid) throws SQLException {
        String sql = "SELECT n.nspname, c.relname, seqstart, seqmin, seqmax, seqincrement, seqcache, seqcycle "
                + "FROM pg_sequence s JOIN pg_class c ON c.oid = s.seqrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE s.seqrelid = ?::oid";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sequenceOid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    SequenceDef def = new SequenceDef();
                    def.qualifiedName = quoteIdent(rs.getString("nspname")) + "." + quoteIdent(rs.getString("relname"));
                    def.startValue = rs.getLong("seqstart");
                    def.minValue = rs.getLong("seqmin");
                    def.maxValue = rs.getLong("seqmax");
                    def.incrementBy = rs.getLong("seqincrement");
                    def.cacheSize = rs.getLong("seqcache");
                    def.cycle = rs.getBoolean("seqcycle");
                    return def;
                }
            }
        }
        return null;
    }

    private static String buildIdentityClause(ResultSet rs, String generation) throws SQLException {
        List<String> options = new ArrayList<>();
        boolean hasSequence = rs.getString("seqstart") != null;
        addIdentityOption(options, "INCREMENT BY ", rs.getString(hasSequence ? "seqincrement" : "identity_increment"));
        addIdentityOption(options, "MINVALUE ", rs.getString(hasSequence ? "seqmin" : "identity_minimum"));
        addIdentityOption(options, "MAXVALUE ", rs.getString(hasSequence ? "seqmax" : "identity_maximum"));
        addIdentityOption(options, "START WITH ", rs.getString(hasSequence ? "seqstart" : "identity_start"));
        if (hasSequence) {
            addIdentityOption(options, "CACHE ", rs.getString("seqcache"));
            options.add(rs.getBoolean("seqcycle") ? "CYCLE" : "NO CYCLE");
        } else {
            String cycle = rs.getString("identity_cycle");
            if ("YES".equals(cycle) || "NO".equals(cycle)) {
                options.add("YES".equals(cycle) ? "CYCLE" : "NO CYCLE");
            }
        }
        String clause = " GENERATED " + generation + " AS IDENTITY";
        return options.isEmpty() ? clause : clause + " (" + String.join(" ", options) + ")";
    }

    private static void addIdentityOption(List<String> options, String prefix, String value) {
        if (value != null) {
            options.add(prefix + Long.parseLong(value));
        }
    }

    private void appendSchemaFilter(StringBuilder sql, String column, List<String> schemaNames) {
        if (schemaNames == null || schemaNames.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        for (int i = 0; i < schemaNames.size(); i++) {
            if (i > 0) {
                sql.append(",");
            }
            sql.append("?");
        }
        sql.append(")");
    }

    private void bindSchemaFilter(PreparedStatement ps, int startIndex, List<String> schemaNames) throws SQLException {
        if (schemaNames == null || schemaNames.isEmpty()) {
            return;
        }
        for (int i = 0; i < schemaNames.size(); i++) {
            ps.setString(startIndex + i, schemaNames.get(i));
        }
    }

    /** 把 information_schema 里的类型信息拼成建表用的类型声明，如 varchar(64)、numeric(10,2) */
    public static String formatPgType(ColumnInfo info) {
        String dataType = info.getDataType();
        String udtName = info.getUdtName();

        if ("ARRAY".equals(dataType)) {
            String base = (udtName != null && udtName.startsWith("_")) ? udtName.substring(1) : udtName;
            return base + "[]";
        }
        if ("USER-DEFINED".equals(dataType)) {
            return udtName;
        }
        if (("character varying".equals(dataType) || "character".equals(dataType)) && info.getCharLen() != null) {
            String shortName = "character varying".equals(dataType) ? "varchar" : "char";
            return shortName + "(" + info.getCharLen() + ")";
        }
        if (("numeric".equals(dataType) || "decimal".equals(dataType)) && info.getNumPrecision() != null) {
            if (info.getNumScale() != null && info.getNumScale() != 0) {
                return "numeric(" + info.getNumPrecision() + "," + info.getNumScale() + ")";
            }
            return "numeric(" + info.getNumPrecision() + ")";
        }
        return dataType;
    }

    public DiffResult diff(Map<String, Map<String, Map<String, ColumnInfo>>> aData,
                            Map<String, Map<String, Map<String, ColumnInfo>>> bData,
                            Consumer<String> logger) {
        DiffResult result = new DiffResult();

        for (Map.Entry<String, Map<String, Map<String, ColumnInfo>>> schemaEntry : aData.entrySet()) {
            String schema = schemaEntry.getKey();
            if (!bData.containsKey(schema)) {
                result.missingSchemas.add(schema);
                continue;
            }
            Map<String, Map<String, ColumnInfo>> bTables = bData.get(schema);
            for (Map.Entry<String, Map<String, ColumnInfo>> tableEntry : schemaEntry.getValue().entrySet()) {
                String table = tableEntry.getKey();
                if (logger != null) {
                    logger.accept("[比对中] " + schema + "." + table);
                }
                if (!bTables.containsKey(table)) {
                    result.missingTables.add(new String[]{schema, table});
                    continue;
                }
                Map<String, ColumnInfo> bColumns = bTables.get(table);
                for (Map.Entry<String, ColumnInfo> columnEntry : tableEntry.getValue().entrySet()) {
                    String column = columnEntry.getKey();
                    if (!bColumns.containsKey(column)) {
                        result.missingColumns.add(new Object[]{schema, table, column, columnEntry.getValue()});
                    }
                }
            }
        }
        return result;
    }

    /** 按 schema 生成差异文档（没有差异的 schema 不生成文件），返回实际生成的文件数 */
    public int generateReports(DbConfig aConfig,
                                Map<String, Map<String, Map<String, ColumnInfo>>> aData,
                                DiffResult diffResult,
                                Map<String, Map<String, String>> tableComments,
                                String outputDir,
                                Consumer<String> logger) throws IOException, SQLException {
        Files.createDirectories(Paths.get(outputDir));

        Set<String> missingSchemaSet = new HashSet<>(diffResult.missingSchemas);

        Map<String, List<String>> tablesMissingBySchema = new LinkedHashMap<>();
        for (String[] pair : diffResult.missingTables) {
            tablesMissingBySchema.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
        }

        Map<String, List<Object[]>> columnsMissingBySchema = new LinkedHashMap<>();
        for (Object[] row : diffResult.missingColumns) {
            String schema = (String) row[0];
            columnsMissingBySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(row);
        }

        // 只有真的有"整表缺失"时才连一次 A 库，去补主键/索引/序列这些细节
        Connection extrasConn = null;
        try {
            if (!diffResult.missingTables.isEmpty()) {
                extrasConn = DriverManager.getConnection(aConfig.jdbcUrl(), aConfig.user, aConfig.password);
            }

            int generated = 0;
            for (String schema : new TreeSet<>(aData.keySet())) {
                boolean schemaMissing = missingSchemaSet.contains(schema);
                List<String> missingTables = tablesMissingBySchema.getOrDefault(schema, List.of());
                List<Object[]> missingColumns = columnsMissingBySchema.getOrDefault(schema, List.of());

                boolean hasDiff = schemaMissing || !missingTables.isEmpty() || !missingColumns.isEmpty();
                if (!hasDiff) {
                    if (logger != null) {
                        logger.accept("[跳过] " + schema + " 无差异");
                    }
                    continue;
                }

                Map<String, Map<String, ColumnInfo>> schemaTables = aData.get(schema);
                Map<String, String> schemaTableComments = tableComments.getOrDefault(schema, Map.of());
                String content = buildSchemaReport(schema, schemaMissing, missingTables, missingColumns,
                        schemaTables, schemaTableComments, extrasConn, logger);
                Path path = Paths.get(outputDir, schema + ".sql");
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    writer.write(content);
                }
                generated++;
                if (logger != null) {
                    logger.accept("[已生成] " + path);
                }
            }
            return generated;
        } finally {
            if (extrasConn != null) {
                extrasConn.close();
            }
        }
    }

    private String buildSchemaReport(String schema, boolean schemaMissing,
                                      List<String> missingTables, List<Object[]> missingColumns,
                                      Map<String, Map<String, ColumnInfo>> schemaTables,
                                      Map<String, String> tableComments,
                                      Connection extrasConn, Consumer<String> logger) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- schema: ").append(schema).append("\n");

        if (schemaMissing) {
            sb.append("-- B 库中不存在这个实例（schema），需要先 CREATE SCHEMA ")
                    .append(schema).append(" 并建好全部表，未生成后续 DDL\n");
            return sb.toString();
        }

        if (!missingTables.isEmpty()) {
            sb.append("-- 以下表在 B 该实例下完全不存在，补全到 B 库的建表 SQL：\n");
            for (String table : missingTables) {
                Map<String, ColumnInfo> columns = schemaTables.get(table);
                TableExtras extras = fetchTableExtras(extrasConn, schema, table, logger);
                sb.append(buildCreateTableSql(schema, table, columns, extras, tableComments.get(table)));
            }
        }

        if (!missingColumns.isEmpty()) {
            sb.append("-- 以下是 B 中已存在的表缺少的字段，补全到 B 库的 SQL：\n");
            String lastTable = null;
            for (Object[] row : missingColumns) {
                String table = (String) row[1];
                String column = (String) row[2];
                ColumnInfo info = (ColumnInfo) row[3];
                if (!table.equals(lastTable)) {
                    sb.append("-- ").append(schema).append(".").append(table).append("\n");
                    lastTable = table;
                }
                sb.append("ALTER TABLE ").append(quoteIdent(schema)).append(".").append(quoteIdent(table))
                        .append(" ADD COLUMN ").append(quoteIdent(column)).append(" ").append(formatPgType(info));
                if (info.getIdentityClause() != null) {
                    sb.append(info.getIdentityClause()).append(" NOT NULL");
                } else if (info.getDefaultExpression() != null) {
                    sb.append(" DEFAULT ").append(info.getDefaultExpression());
                }
                sb.append(";\n");
                if (info.getComment() != null) {
                    sb.append("COMMENT ON COLUMN ").append(quoteIdent(schema)).append(".").append(quoteIdent(table))
                            .append(".").append(quoteIdent(column))
                            .append(" IS '").append(escapeSqlLiteral(info.getComment())).append("';\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 用 A 库该表的字段清单 + 主键/唯一约束/索引/序列/comment 拼一份完整的建表 SQL。
     * 外键没有还原（用户只要求主键、索引、序列、唯一约束）。
     */
    private String buildCreateTableSql(String schema, String table, Map<String, ColumnInfo> columns,
                                        TableExtras extras, String tableComment) {
        String qualifiedTable = quoteIdent(schema) + "." + quoteIdent(table);
        StringBuilder sb = new StringBuilder();
        sb.append("-- ").append(schema).append(".").append(table).append("（B 库完全缺失此表）\n");

        for (Map.Entry<String, SequenceDef> entry : extras.sequenceByColumn.entrySet()) {
            SequenceDef seq = entry.getValue();
            sb.append("CREATE SEQUENCE IF NOT EXISTS ").append(seq.qualifiedName)
                    .append(" INCREMENT BY ").append(seq.incrementBy)
                    .append(" MINVALUE ").append(seq.minValue)
                    .append(" MAXVALUE ").append(seq.maxValue)
                    .append(" START WITH ").append(seq.startValue)
                    .append(" CACHE ").append(seq.cacheSize)
                    .append(seq.cycle ? " CYCLE" : " NO CYCLE")
                    .append(";\n");
        }

        sb.append("CREATE TABLE ").append(qualifiedTable).append(" (\n");
        List<String> columnLines = new ArrayList<>();
        for (Map.Entry<String, ColumnInfo> entry : columns.entrySet()) {
            String column = entry.getKey();
            ColumnInfo info = entry.getValue();
            StringBuilder line = new StringBuilder("    ").append(quoteIdent(column)).append(" ").append(formatPgType(info));
            SequenceDef seq = extras.sequenceByColumn.get(column);
            if (info.getIdentityClause() != null) {
                line.append(info.getIdentityClause());
            } else if (seq != null) {
                line.append(" DEFAULT nextval('").append(escapeSqlLiteral(seq.qualifiedName)).append("'::regclass)");
            } else if (info.getDefaultExpression() != null) {
                line.append(" DEFAULT ").append(info.getDefaultExpression());
            }
            if ("NO".equals(info.getIsNullable())) {
                line.append(" NOT NULL");
            }
            columnLines.add(line.toString());
        }
        sb.append(String.join(",\n", columnLines));
        sb.append("\n);\n");

        for (Map.Entry<String, SequenceDef> entry : extras.sequenceByColumn.entrySet()) {
            if (!entry.getValue().ownedByColumn) {
                continue;
            }
            sb.append("ALTER SEQUENCE ").append(entry.getValue().qualifiedName)
                    .append(" OWNED BY ").append(qualifiedTable).append(".").append(quoteIdent(entry.getKey())).append(";\n");
        }

        if (extras.primaryKeyDef != null) {
            sb.append("ALTER TABLE ").append(qualifiedTable)
                    .append(" ADD CONSTRAINT ").append(quoteIdent(extras.primaryKeyName))
                    .append(" ").append(extras.primaryKeyDef).append(";\n");
        }
        for (String[] uq : extras.uniqueConstraints) {
            sb.append("ALTER TABLE ").append(qualifiedTable)
                    .append(" ADD CONSTRAINT ").append(quoteIdent(uq[0])).append(" ").append(uq[1]).append(";\n");
        }
        for (String indexDef : extras.indexDefs) {
            sb.append(indexDef).append(";\n");
        }

        if (tableComment != null) {
            sb.append("COMMENT ON TABLE ").append(qualifiedTable)
                    .append(" IS '").append(escapeSqlLiteral(tableComment)).append("';\n");
        }
        for (Map.Entry<String, ColumnInfo> entry : columns.entrySet()) {
            String comment = entry.getValue().getComment();
            if (comment != null) {
                sb.append("COMMENT ON COLUMN ").append(qualifiedTable).append(".").append(quoteIdent(entry.getKey()))
                        .append(" IS '").append(escapeSqlLiteral(comment)).append("';\n");
            }
        }

        return sb.toString();
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident + "\"";
    }

    private static String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
