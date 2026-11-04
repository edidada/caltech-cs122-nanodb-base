# CS122 HW4：连接顺序优化实验报告

依据 [Caltech Winter2019 Assignment4](https://courses.cms.caltech.edu/cs122/assignments/lab4.html)，分支 `edidada/hw4` 从已验收 HW3 建立。代码提交 `8de9883547b6101735a322d723f82076d5bbb8b0` 已通过 [Actions 41851610298](https://github.com/edidada/caltech-cs122-nanodb-base/actions/runs/41851610298)：JDK17 Maven、Gradle 各221项测试，零失败、零跳过，并完成完整 stores-28K 的13条查询。学校私有评分和正式提交未执行。

## 实现与范围

AbstractPlannerImpl 共享 SQL 翻译、聚合提取、HAVING、投影、排序及 LIMIT；SimplePlanner 保留语法顺序，CostBasedJoinPlanner 替换 FROM/WHERE。优化器按叶序号和 BitSet 枚举左深计划，为每个子集保留 CPU 估算最小的候选；同成本采用确定性次序，自连接叶不会因结构相同合并。递归展开 AND，保留 OR/NOT；单叶及新连接可用条件尽早应用，剩余条件保留在最终过滤。

重排后按原叶身份和列偏移恢复公共列序；派生表与外连接作为父区域的不透明叶。LEFT 的上层 WHERE 仅进入保留左侧，RIGHT 对称；ON 中仅依赖非保留侧的条件可以过滤该输入。NATURAL/USING 保留共享语义翻译，不声称全部内部重排。最优性仅针对左深搜索空间、最早过滤和 HW3 近似成本模型；未实现等值传递、索引路径、interesting orders 或跨派生表下推。prepare 会重复准备子树，未声称局部常数开销。

## 实际验证

215项既有测试与6项 HW4 测试均在两种构建工具上执行。新增测试包含：三表来源顺序、ON/WHERE 变体及 SELECT * 列序；左右外连接和 NULL 过滤；派生聚合/HAVING、OR、非连通输入、自连接；合法的独立 USING 子连接；空输入、恒真/恒假 ON 与 WHERE 的11条查询；独立标量枚举四表24种顺序，比较最小核心连接 CPU。每种 SQL 差分比较完整行多重集合和 schema。

首轮真实 CI 暴露普通表叶误用 isOuterJoin 接口；现已先检查 isJoinExpr。此前设想的同名未限定合并列在 AST schema 阶段会被拒绝，不作为已复现故障；测试已改为分别合并 k、q 的合法查询。未修改官方测试以掩盖错误。

完整数据包含 states 51、cities 254、stores 2000、employees 24932 行，保留全部源数据而非玩具替代。员工 manager 列的空值/NDV、字符串边界及旧 HW3 完整结果哈希均检查通过。主要结果：

| 查询 | 实际行数 | 估算 CPU |
| --- | ---: | ---: |
| cities_5m | 1 | 254.0 |
| join_original | 74 | 906135.5 |
| join_rewritten | 74 | 906361.125 |
| oregon_original | 7 | 15534.36816406 |
| oregon_rewritten | 7 | 20520.73632813 |
| oregon_order_b | 7 | 15514.78027344 |
| oregon_order_c | 7 | 15534.36816406 |

Oregon 原查询相同成本模型下由 HW3 的78,742,016降至15,534.368；三种表/条件顺序得到相同核心连接计划，顶层列序恢复投影可能使总 CPU 略有不同。Oregon 结果均7行、完整哈希相同；原/派生 join 均74行且哈希相同。范围查询三种写法均179行。成本是模型估算，不能当作实测加速比；分布偏斜与谓词独立性误差仍然存在。

设计题 C1～C4 已填入远端实际 EXPLAIN。全部 SQL、估算/实测行数、运行秒数、计划树、哈希和代码/运行来源见 [hw4-results.json](hw4-results.json)。没有本地编译或运行实验。

## 发布和验收说明

此前 CLI OAuth 缺少 workflow scope；GitHub 插件通道恢复后已发布相同本地提交，并核对远端 SHA。公开 NanoDB 仓库的 Actions 实际可运行；langnote 的付款/额度错误不能推断所有仓库都无法运行。后续源码或文档提交仍需核对最终 HEAD 的完整 CI，不能将本节代码基线证明直接替代新提交验收。HW5 在独立分支继续实现，本报告不将其计为完成。
