# CS122 HW5：高级子查询实验报告

依据 [Caltech Winter2019 Lab5](https://courses.cms.caltech.edu/cs122/assignments/lab5.html)，分支 `edidada/hw5`。代码基线 `bda39cc061e685e9ced61f67f63863fe5c2a567b` 已通过 [Actions 41852074922](https://github.com/edidada/caltech-cs122-nanodb-base/actions/runs/41852074922)：JDK17 Maven/Gradle 各244项测试，零失败、零跳过，包含全部 framework/parser/HW1～HW5；完整 stores-28K 的13条查询执行成功。学校私有评分及正式提交未执行。以下实现范围与实际测试相对应，不宣称完整 SQL 标准兼容。

## 规划与相关求值

ExpressionPlanner 实现 ExpressionProcessor，为当前私有表达式内每个 SubqueryOperator 复用现有 Planner 递归生成计划，不混入子查询内部遍历。类、字段及方法均有 Javadoc。共享 AbstractPlannerImpl 保存当前词法 SELECT，递归返回时用 finally 恢复；SELECT/HAVING 在聚合提取前规划，WHERE 在扫描/过滤条件构造时规划，CostBasedJoinPlanner 的私有合取项走相同路径。GROUP BY、ORDER BY 使用独立无状态遍历拒绝子查询；JOIN ON 内嵌子查询也明确拒绝，属于官方允许的限制。

每个子计划通过独立环境桥挂到全部物理节点；每次 Scalar/IN/EXISTS 求值前把桥的父环境替换成当前表达式求值环境。不累计历史父环境，不保留旧外部行；嵌套调用通过环境链访问祖父层，局部别名优先。相关列保留词法外层类型元数据，投影不把缺失本地列的 -1 下标当作有效统计位置。

AST 类型推断为非 wildcard 表达式加入已解析外层列的类型，本地 FROM schema 和 SELECT * 展开仍只含本地列。实测覆盖无 FROM 相关算术投影 SELECT a.k,(SELECT a.k+1)，以及 SELECT/WHERE/HAVING、相关 MAX、EXISTS/NOT EXISTS、IN/NOT IN、祖父层引用和别名遮蔽。

## 执行合同和异常

IN/NOT IN 使用 SQL 三值比较，明确匹配优先于 UNKNOWN；NULL 不与 NULL 判为相等；空集合始终得出明确真假，多列某一列明确不等则该行不匹配。列数不等报错。提前匹配、读完、列数异常和读取失败均释放已取出行并清理子计划。

标量子查询每次重新执行，先复制第一行列值再推进，以适配复用 tuple 对象的节点。保留框架和原测试约定的“恰好一行一列”：零行、多行、多列报 ExpressionException，经 SQL 命令包装为 ExecutionException 并保留 cause；这不同于标准 SQL 标量零行返回 NULL，不掩盖该兼容限制。EXISTS 的正常提前返回和读取异常均清理资源。

IN 的 equals/hashCode 区分取反，duplicate 深复制左操作数；BooleanOperator 改为深复制各合取项，避免规划一个布尔树副本时给原 AST 的子查询挂计划。实测包含原 AST 保持不变、相同 AST 重复规划得到独立计划、当前环境逐行重新绑定。子查询 SelectClause 仍采用框架的共享只读引用约定，不声称实现完整 AST 深克隆。

## 成本模型

ExpressionCostCalculator 对每个表达式节点计1 CPU，遇到已规划子查询累计全部 CPU、块I/O、大寻道；不重复遍历内部表达式。未规划子查询报错。当前没有结果缓存，相关和非相关查询均按预计调用次数计费，完整子计划费用是保守估算，不预测短路或 EXISTS/IN 提前终止。

扫描按原始表行数、过滤和投影按输入行数、连接谓词按候选行对数计费，保留原基础节点开销。磁盘费用乘调用次数后向上取整；零次调用不增加费用。无 FROM 投影复制基础成本，重复 prepare 重新计算而不累加历史成本。

成本测试覆盖表达式复杂度、子查询费用、分数行I/O取整、零行、成本防御副本、过滤/投影重复 prepare、无 FROM 投影及未规划子查询拒绝；SQL 测试在同一新模型下比较语法顺序与优化计划，优化计划成本更低。HW3 数字断言及 HW4 的独立24顺序标量枚举同步适配表达式节点费用，历史测试组没有删减。

## 实际数据和验收范围

完整数据 states/cities/stores/employees 分别51/254/2000/24932行；员工 manager 列的空值4259和NDV11754保留。全部13查询与既有 HW3 结果完整哈希一致：join 两种写法74行，Oregon 各变体7行，范围变体179行。表/条件顺序变体核心连接计划一致。全部 SQL、EXPLAIN、行数、成本、时长、哈希及代码/CI来源见 [hw5-results.json](hw5-results.json)。

新模型 Oregon CPU 为61,473.418；它不能直接与旧 HW3/HW4 模型数字推导加速比。HW5 工作流显式使用 --updated-expression-costs，保留完整数据与结果检查，仅替换跨模型CPU比较为同一模型下规划器比较。

首轮远端检查实际修复了受检异常漏声明、普通表叶误用连接接口、BooleanOperator 浅克隆污染 AST 和 SQL 表达式异常包装。Maven 与 Gradle 的最终244项均已通过；报告提交后还需核对最终 HEAD 的完整 CI。所有 Java 编译和实验均在 Actions，本地只进行源码/JSON/XML审查、Python AST 和 git diff --check。

官方要求的朴素子查询、相关环境、成本及设计题已有实现和公开验证。可选去相关、半连接、反连接和物化缓存未实现，不计额外完成。该公开自测不替代学校私有 grader。

此前 CLI OAuth workflow 权限和 langnote 账户额度限制均有真实报错；GitHub 插件恢复后已发布本分支，NanoDB 为公开仓库且实际运行成功，不能再把这些历史障碍写成当前 NanoDB 无法验收的原因。
