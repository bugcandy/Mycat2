package io.mycat;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLListExpr;
import com.alibaba.druid.sql.ast.expr.SQLNumberExpr;
import com.alibaba.druid.sql.ast.expr.SQLNumericLiteralExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import io.mycat.calcite.CodeExecuterContext;
import io.mycat.calcite.DrdsRunnerHelper;
import io.mycat.calcite.MycatHint;
import io.mycat.calcite.logical.MycatView;
import io.mycat.calcite.spm.ParamHolder;
import io.mycat.calcite.spm.QueryPlanner;
import io.mycat.util.NameMap;
import lombok.Getter;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.curator.shaded.com.google.common.collect.ImmutableMap;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;


public class DrdsSqlWithParams extends DrdsSql {
    private final List<Object> params;
    private final List<String> aliasList;
    private final static Logger log = LoggerFactory.getLogger(DrdsSqlWithParams.class);

    private Long timeout;

    public DrdsSqlWithParams(String parameterizedSqlStatement,
                             List<Object> params,
                             boolean complex,
                             List<SqlTypeName> typeNames,
                             List<String> aliasList,
                             List<MycatHint> hints) {
        super(parameterizedSqlStatement, complex, typeNames, hints);
        this.params = params;
        this.aliasList = aliasList;

        for (MycatHint hint : getHints()) {
            for (MycatHint.Function function : hint.getFunctions()) {
                if ("EXECUTE_TIMEOUT".equalsIgnoreCase(function.getName())) {
                    MycatHint.Argument argument = function.getArguments().get(0);
                    SQLNumericLiteralExpr value = (SQLNumericLiteralExpr) argument.getValue();
                    timeout = value.getNumber().longValue();
                }
            }
        }
    }

    public List<Object> getParams() {
        return params;
    }

    public List<String> getAliasList() {
        return aliasList;
    }

    public Optional<List<Map<String, Partition>>> getHintDataNodeFilter() {
        for (MycatHint hint : this.getHints()) {
            for (MycatHint.Function hintFunction : hint.getFunctions()) {
                if ("SCAN".equalsIgnoreCase(hintFunction.getName())) {
                    List<MycatHint.Argument> arguments = hintFunction.getArguments();
                    Map<String, List<String>> collect = arguments.stream().collect(Collectors.toMap(m -> SQLUtils.toSQLString(m.getName()), v -> Arrays.stream(SQLUtils.normalize(SQLUtils.toSQLString((v.getValue())).replace("'", "")
                            .replace("(", "")
                            .replace(")", "")
                            .trim()).split(",")).map(i -> SQLUtils.normalize(i)).collect(Collectors.toList())));
                    NameMap<List<String>> nameMap = NameMap.immutableCopyOf(collect);
                    List<String> condition = new LinkedList<>(Optional.ofNullable(nameMap.get("CONDITION", false)).orElse(Collections.emptyList()));
                    List<String> logicalTables = new LinkedList<>(Optional.ofNullable(nameMap.get("TABLE", false)).orElse(Collections.emptyList()));
                    List<List<String>> physicalTables = new LinkedList<>(Optional.ofNullable(nameMap.get("PARTITION", false)).orElse(Collections.emptyList()).stream().map(i -> Arrays.asList(i.split(","))).collect(Collectors.toList()));
                    if (!physicalTables.isEmpty()) {
                        for (MycatHint.Argument argument : arguments) {
                            if ("PARTITION".equals(argument.getName().toString())) {
                                if (argument.getValue() instanceof SQLListExpr) {
                                    physicalTables.clear();
                                    SQLListExpr argumentValue = (SQLListExpr) argument.getValue();
                                    for (Object o : argumentValue.getItems()) {
                                        ArrayList<String> objects = new ArrayList<>();
                                        physicalTables.add(objects);
                                        for (String s : Arrays.asList(SQLUtils.normalize(o.toString()).split(","))) {
                                            objects.add(SQLUtils.normalize(s));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HashSet<String> targets = new HashSet<>(Optional.ofNullable(nameMap.get("TARGET", false)).orElse(Collections.emptyList()));
                    Predicate<Map<String, Partition>> targetFilter = targets.isEmpty() ? (u) -> true : new Predicate<Map<String, Partition>>() {
                        @Override
                        public boolean test(Map<String, Partition> stringPartitionMap) {
                            String targetName = stringPartitionMap.values().iterator().next().getTargetName();
                            boolean contains = targets.contains(targetName);
                            return contains;
                        }
                    };
                    SQLStatement parameterizedStatement = this.getParameterizedStatement();

                    Detector detector = new Detector();
                    parameterizedStatement.accept(detector);
                    if (logicalTables.isEmpty()) {
                        logicalTables.addAll(detector.tableHandlerMap.keySet());
                    }
                    if (condition.isEmpty()) {
                        condition.add("true");
                    }
                    if (!detector.isSharding) {
                        List<Partition> partitions = Collections.emptyList();
                        List<Partition> newPartitions = Collections.emptyList();
                        if (!logicalTables.isEmpty()) {
                            if (!condition.isEmpty()) {
                                String sql = "select * from " + logicalTables.get(0) + "where " + condition;
                                QueryPlanner queryPlanner = MetaClusterCurrent.wrapper(QueryPlanner.class);
                                DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(sql, null);
                                CodeExecuterContext codeExecuterContext = queryPlanner.innerComputeMinCostCodeExecuterContext(drdsSqlWithParams);

                                List<Map<String, Partition>> collect2 = codeExecuterContext.getRelContext()
                                        .values()
                                        .stream()
                                        .flatMap(i -> AsyncMycatDataContextImpl.getSqlMap(codeExecuterContext.getConstantMap(), i.getRelNode(), drdsSqlWithParams, Optional.empty()).stream())
                                        .filter(targetFilter)
                                        .collect(Collectors.toList());
                                return Optional.of(collect2);
                            } else if (!physicalTables.isEmpty()) {
                                newPartitions = physicalTables.get(0).stream().map(dataNodeParser()).collect(Collectors.toList());
                            }
                            if (!targets.isEmpty()) {
                                newPartitions = partitions.stream().filter(dataNode -> {
                                    return targets.contains(dataNode.getTargetName());
                                }).collect(Collectors.toList());
                            }
                            List<Partition> finalNewPartitions = newPartitions;
                            List<Map<String, Partition>> maps = finalNewPartitions.stream().map(i -> ImmutableMap.of(logicalTables.get(0), i)).collect(Collectors.toList());
                            return Optional.of(maps);
                        }
                    } else {

                        Map<String, List<Partition>> logicalPhysicalMap = new HashMap<>();
                        int physicalTableCount = 0;
                        if (!physicalTables.isEmpty()) {
                            int size = logicalTables.size();

                            for (int i = 0; i < size; i++) {
                                String alias = logicalTables.get(i);
                                List<Partition> phyTableList = physicalTables.get(i).stream().map(dataNodeParser()).collect(Collectors.toList());
                                physicalTableCount += phyTableList.size();
                                alias = detector.tableHandlerMap.get(alias).getUniqueName();
                                List<Partition> partitions = logicalPhysicalMap.computeIfAbsent(alias, s -> new ArrayList<>());
                                partitions.addAll(phyTableList);
                            }

                        }

                        if (!logicalTables.isEmpty() && !condition.isEmpty()) {
                            String where = condition.get(0);
                            String sql;
                            Map<String, TableHandler> aliasTableMap = detector.tableHandlerMap;
                            StringJoiner sb = new StringJoiner(" join ");
                            for (Map.Entry<String, TableHandler> e : aliasTableMap.entrySet()) {
                                TableHandler table = e.getValue();
                                String schemaName = table.getSchemaName();
                                String tableName = table.getTableName();
                                sb.add(schemaName + "." + tableName + " " + e.getKey());
                            }
                            if (aliasTableMap.size() == 1) {
                                sql = "select *  from " + sb.toString() + " where " + where;
                            } else {
                                sql = "select *  from " + sb.toString() + " on " + where;
                            }

                            DrdsSqlWithParams hintSql = DrdsRunnerHelper.preParse(sql, null);

                            QueryPlanner planner = MetaClusterCurrent.wrapper(QueryPlanner.class);
                            ParamHolder paramHolder = ParamHolder.CURRENT_THREAD_LOCAL.get();
                            paramHolder.setData(hintSql.getParams(), hintSql.getTypeNames());
                            try {
                                CodeExecuterContext codeExecuterContext = planner.innerComputeMinCostCodeExecuterContext(hintSql);

                                List<Map<String, Partition>> collect2 = codeExecuterContext.getRelContext()
                                        .values()
                                        .stream()
                                        .flatMap(i -> AsyncMycatDataContextImpl.getSqlMap(codeExecuterContext.getConstantMap(), i.getRelNode(), hintSql, Optional.empty()).stream())
                                        .filter(targetFilter)
                                        .collect(Collectors.toList());
                                //逻辑优化
                                if (!logicalPhysicalMap.isEmpty()) {
                                    int index = 0;
                                    for (Map<String, Partition> stringPartitionMap : new ArrayList<>(collect2)) {
                                        for (Map.Entry<String, Partition> entry : new ArrayList<>(stringPartitionMap.entrySet())) {
                                            List<Partition> partitions = logicalPhysicalMap.get(entry.getKey());
                                            if (partitions == null) continue;
                                            if (index >= partitions.size()) {
                                                collect2.remove(stringPartitionMap);
                                                continue;
                                            }
                                            entry.setValue(partitions.get(index));
                                        }
                                        ++index;
                                    }
                                }
                                return Optional.of(collect2);

                            } catch (Exception e) {
                                log.error("", e);
                            } finally {
                                paramHolder.clear();
                            }
                        }

                    }
                }

            }
        }
        return Optional.empty();
    }

    @NotNull
    public static Function<String, BackendTableInfo> dataNodeParser() {
        return s -> {
            String[] s1 = s.split("_");
            if (s1.length >= 3) {
                return new BackendTableInfo(s1[0], s1[1], String.join("_", Arrays.asList(s1).subList(2, s1.length)));
            }
            if (s1.length == 2) {
                return new BackendTableInfo(null, s1[0], s1[1]);
            }
            return new BackendTableInfo(null, null, s1[0]);
        };
    }

    static class Detector extends MySqlASTVisitorAdapter {
        boolean isSharding;
        Map<String, TableHandler> tableHandlerMap = new HashMap<>();
        MetadataManager metadataManager = MetaClusterCurrent.wrapper(MetadataManager.class);

        @Override
        public boolean visit(SQLExprTableSource x) {
            String tableName = x.getTableName();
            if (tableName != null) {
                TableHandler table = metadataManager.getTable(SQLUtils.normalize(x.getSchema()), SQLUtils.normalize(tableName));
                isSharding = table.getType() == LogicTableType.SHARDING;
                String alias = x.computeAlias();
                if (alias == null) {
                    alias = table.getTableName();
                } else {
                    alias = SQLUtils.normalize(alias);
                }
                tableHandlerMap.put(alias, table);
            }
            return false;
        }
    }

    public Optional<Long> getTimeout() {
        return Optional.ofNullable(timeout);
    }

}
