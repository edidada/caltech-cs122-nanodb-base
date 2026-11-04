# CS122 HW2：SQL 翻译、连接与聚合

## 来源与环境

按 Caltech [Winter 2019 Assignment 2](https://courses.cms.caltech.edu/cs122/assignments/lab2.html) 实现，官方页面最后更新为 2019-01-29。上游为官方 `cs122-19wi/nanodb-base`，基线 `366f2bbf4bb7a5e8658260c54c7981ac947d907e`，不是其他课程 CS121 的 2026 代码，也未导入学生答案。

本实验从已验收的 HW1 `e0a20e3e78100e96e6a1d27ba4b24928cd3fd4ce` 建立 `edidada/hw2`，独立工作流为 `.github/workflows/hw2.yml`。保留官方 Maven 单模块以保持课程入口与包路径，提供 Gradle，均用 JDK17。本机仅编辑与静态检查，编译、测试在 GitHub Actions 完成。

## 实现及语义

`SimplePlanner` 继承并复用 `SimplestPlanner.makeSimpleSelect`，没有修改原来的 SimplestPlanner。默认规划器改为 SimplePlanner；UPDATE/DELETE 继续获得可修改的 PageTuple，而不是投影后的副本。

计划构造按 FROM → WHERE → GROUP/AGGREGATE → HAVING → SELECT → ORDER BY → LIMIT/OFFSET 进行。FROM 递归处理表、派生表和连接，别名使用 RenameNode；没有 FROM 时从一个空元组开始，所以 `SELECT 3+2 AS five` 产生一行，常量 WHERE 为假时产生零行。省略无用的 SELECT * 投影，不跨外连接推送 WHERE；ORDER BY 针对本版解析器已经解析好的结果 schema，因此可使用投影别名。

嵌套循环连接持有一个外侧 tuple，逐次推进内侧计划；匹配后返回复制好的 TupleLiteral，下一次调用继续该外侧行。每个右 tuple 在消费后释放，外侧 tuple 在内扫描结束、计划重启或清理时释放。左外连接只对完全未匹配的外侧行补一次 NULL；右外连接交换物理输入，并保持原 SELECT 输出顺序。标记回退通过重放到已记录的输出位置实现，时间较慢，但不要求子计划支持 mark。克隆活动节点不继承原节点拥有的 tuple pin。

AggregateProcessor 在 SELECT/HAVING 的私有表达式副本上提取聚合，使用稳定顺序的临时列，重复聚合复用同一项；WHERE、ON、GROUP BY 中的聚合，以及聚合嵌套聚合，抛出描述明确的 InvalidSQLException。没有原地改写 SelectClause，重复规划同一 AST 的结果和 AST 文本均受到测试。

分组键可为表达式，SELECT/HAVING 中对应表达式改为读取分组结果列。Hash 聚合在空输入、无 GROUP BY 时仍生成一个全局组，COUNT 返回 0，其余空聚合返回 NULL；有 GROUP BY 的空输入产生零行。DISTINCT 的去重集合按函数实例/分组独立复制，避免交错分组共享集合；解析器也正确选择 `SUM(DISTINCT a+b)` 等表达式的去重实现。

投影在计算复制结果后立即释放输入 tuple，包括表达式抛异常时；查询消费器失败时，计划清理还会释放连接保留的外侧行。Hash 聚合通过 finally 释放每个输入。没有依赖 SQL 命令结束时的强制 unpin 来掩盖泄漏。

## 加分项

`LimitOffsetNode` 位于排序之后，负责释放跳过的 tuple，将返回 tuple 的所有权交给消费者；达到限制时不继续读取，完整计划仍由 cleanup 释放资源。遵从官方 SelectClause 的已有契约：`limit=0` 表示无限制，而非部分 SQL 引擎中“返回零行”的含义。测试覆盖 LIMIT、OFFSET 单独使用、精确末尾、越界、空输入、排序和该 0 值约定。

NATURAL/USING 复用 FromClause 的共享列及结果 schema 规则，修正右侧列检查和无公共列的自然连接。嵌套合并键不能在父连接拼接 schema 中再按未限定名字查找；BoundColumnValue 将内部条件和投影绑定到已解析的左右 tuple/列位置；私有输入投影避免两个合并键在原始拼接 schema 中重复，输出投影恢复用户可见列名、限定名和顺序。LEFT/RIGHT USING 的未匹配输出仍保留正确一侧的共享键。

官方 NATURAL/USING 测试来自更早版本，期望标识符大写，而本次固定的 2019 解析器明确将未引用标识符转小写。包装类将期望标识符转小写后再调用原始 schema 断言。原文件保持不变；其中三项测试直接委托原方法，两表测试保留原数据并修正两处夹具错误：两个共同列应先输出 A,C，再输出 B,D（原期望行却是 A,B,C,D，与自己的 schema 断言矛盾）；USING(a,c) 应引用具有 c 列的 t2，而不是只有 a,b 的 t1，相应限定名也改为 T2.B。值、列顺序、表限定名断言均继续检查，未把原始版本中的错误查询当作应当通过的正常 SQL。此为明确的测试适配，不称为未经修改的原始测试全通过。

## 验证范围

- 原 framework/parser/HW1/HW2 测试，以及官方四项 NATURAL/USING 测试。
- 25 组左右数据集组合，包含空表、NULL、重复键和一对多/多对多，对 INNER、LEFT、RIGHT、逗号/WHERE 和 CROSS 共执行 125 条 SQL；另测派生表、别名、三表连接及外连接后的 WHERE。
- 表达式分组、多聚合算术、HAVING、排序别名、空聚合、跨分组 DISTINCT 及 DISTINCT 表达式、非法聚合位置与嵌套。
- 直接执行计划，在 SQL 兜底清理前检查全部页 pin：活动连接克隆、重启、标记回退、同 AST 多次规划、消费器异常和投影清理。
- 加分项的 LIMIT/OFFSET 边界、无共享列 NATURAL JOIN，以及 LEFT/RIGHT USING 共享键补 NULL 和错误右侧列诊断。

实现提交 `deed1418d2312ba8fe6dfc6dc653db798bd86897` 的 [Actions 41813672725](https://github.com/edidada/caltech-cs122-nanodb-base/actions/runs/41813672725) 完整成功。下载原始 XML 核实 Maven、Gradle **各207项，失败/错误/跳过均0**；包含前置 framework/parser/HW1，不是207条全新的HW2测试。四项适配后的NATURAL/USING、八项规划边界、三项生命周期及两项连接矩阵测试均实际执行。

完整类级数量、版本与夹具适配记录见 [机器证据](hw2-ci.json)。未将排除的后续 HW 测试算作通过。原设计问题已逐项回答于 [lab2design.txt](../doc/lab2design.txt)。

范围边界：没有实现 SELECT/WHERE 中的子查询、相关求值、FULL OUTER 或代价优化，这些不是本次规定的功能。SELECT DISTINCT（不同于聚合内部 DISTINCT）仍明确拒绝；本报告不声称支持完整 SQL 标准。学校私有评分、正式课程提交和 HW3～HW7/两项挑战尚未验收。
