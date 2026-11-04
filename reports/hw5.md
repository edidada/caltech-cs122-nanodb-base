# CS122 HW5：高级子查询（实现进行中）

分支 `edidada/hw5`，从 HW4 未验收提交 `e8e6b01c6a6a4041d01c2af74aae65feb6169b14` 建立，**HW4 和 HW5 均不能登记为完成**。依据 [官方 Lab5](https://courses.cms.caltech.edu/cs122/assignments/lab5.html)；本次实际下载官方说明并核对要求，没有读取社区答案。

## 完整范围与当前证据

| 官方要求 | 当前实现 / 缺项 | 验收状态 |
| --- | --- | --- |
| ExpressionPlanner 实现 ExpressionProcessor，复用当前 Planner，完整 Javadoc | 尚未实现 | 未完成 |
| SELECT、WHERE、HAVING 内嵌子查询规划 | 现有公共规划器仍拒绝标量子查询；待接入 | 未完成 |
| GROUP BY、ORDER BY 子查询明确拒绝，检测不污染其他子句 | 待实现独立遍历及回归用例 | 未完成 |
| 相关求值及环境层级连接 | Environment 和 PlanNode 已有接口，尚未接入 | 未完成 |
| 子查询表达式成本及相关计划节点更新 | 尚未实现 | 未完成 |
| 设计题、原有 SQL 测试及历史实验回归 | hw5.yml 已准备 JDK17 Maven/Gradle，包含 framework/parser/HW1～HW5 及 HW4 完整数据实验；尚未执行 | 未验收 |

## 本次实现的运算符基础

IN 每次求值重新初始化子计划，逐行按照 SQL 三值逻辑比较：明确匹配优先于 UNKNOWN；NULL 与非空集合可能产生 UNKNOWN；空集合为 FALSE，NOT IN 对明确真假取反而不反转 UNKNOWN。多列比较中某一列明确不等即整行不等，即使其他列含 NULL。左右列数不等明确报错。提前找到匹配、读到末尾、列数异常或读取失败均清理子计划，取出的每行在 finally 中 unpin。

IN 的 equals/hashCode 现在区分 IN 和 NOT IN；duplicate 深复制每个左操作数，避免后续遍历重写克隆表达式时修改原表达式。子查询 AST 仍沿用框架的共享引用约定，计划隔离须在 ExpressionPlanner 中进一步处理，不能据此声称完整 AST 深复制。

标量子查询每次重新求值，不保留跨外部行缓存；先复制第一行列值再继续读取，避免可复用 tuple 对象被推进操作覆盖。保留本框架“恰好一行一列”的既有错误契约，零行仍报错；不宣称已经实现标准 SQL 的零行返回 NULL。成功、列数/行数错误、读取异常统一释放行和清理子计划。EXISTS 在正常提前返回和读取异常时均执行清理。

新增 `TestHw5SubqueryOperators` 使用可观察初始化/清理/行释放的合成输入，覆盖标量重新求值、零行/多行/多列错误，IN/NOT IN 的空集、NULL、多列和数字类型转换、提前匹配、宽度与读取异常，以及克隆重写隔离；EXISTS 覆盖空集、提前退出和读取异常。这些是执行层合同检查，不能代替 SQL 规划、相关环境和成本验证。

## 验证与发布限制

仅完成源码检查和 `git diff --check`，没有在本地编译或执行测试。新增工作流发布仍需已有登录的 workflow 权限，最新 langnote Actions 41849415306 又在分配 runner 前被账户付款/额度限制拒绝。未修改付费设置、未使用付费执行资源，未以旧实验 CI 替代本分支验证。

后续必须完成表中全部必需项，补全设计题，发布后读取实际失败日志并修复，直到最终 SHA 的 Maven、Gradle、SQL 集成、相关查询与成本实验均有真实结果。装饰性附加优化、去相关和物化缓存属于可选扩展，未实现，不计入完成情况。
