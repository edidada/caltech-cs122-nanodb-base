# CS122 HW4：连接顺序优化（实现待远端验证）

依据 [Caltech Winter2019 Assignment 4](https://courses.cms.caltech.edu/cs122/assignments/lab4.html)，从已验收 HW3 `10e9f6903b65f41a338dc026dea70abda56335f9` 建立 `edidada/hw4`。官方题目要求公共规划器基类、左深动态规划、最早可用谓词下推、外连接语义保护、兼容原有分组/聚合/排序，以及完整 stores-28K EXPLAIN 实验。本文件记录当前实现，**尚不登记为完成实验**。

## 已实现代码

`AbstractPlannerImpl` 提取原 SimplePlanner 的公共 SQL 翻译，不复制聚合、HAVING、投影、排序、LIMIT/OFFSET；SimplePlanner 保留语法顺序作为差分参考。CostBasedJoinPlanner 只替换 FROM/WHERE 规划，默认配置切换至它；旧 HW3 的固定成本公式测试显式指定 SimplePlanner，避免把优化后的树与原树公式混比。

优化器收集普通 INNER/CROSS 区域的叶和 ON/WHERE 合取项，按稳定顺序枚举。叶以独立序号和 BitSet 表示，避免结构相同的自连接节点被集合合并。每层从 n 叶最优候选追加一个右侧叶，只保留相同子集下 CPU 估算最小的左深计划；同成本使用确定性顺序。单叶可执行条件尽早下推，新连接只附加此前未消费且当前可执行的条件。所有剩余条件保留为最终过滤，不能丢失。

物理重排后的列位置投影恢复原 FROM schema，保护 SELECT * 的值顺序、列名和限定名。派生表单独递归规划，作为当前区域黑盒。外连接在父区域视为整体：LEFT 的上层 WHERE 只能进入保留的左侧，RIGHT 对称；ON 中仅依赖非保留侧的条件可以过滤该输入。跨侧或不能安全移动的条件留在原语义位置；OR 不拆成独立合取项。NATURAL/USING 的合并列连接保守保留公共翻译路径，未声称对其内部全部做重排优化。

该最优性只针对左深搜索空间、最早过滤启发式和 HW3 近似 CPU 模型，不是全局 SQL 最优。尚未实现等值传递推理、interesting orders、索引路径或跨派生表下推。现有 prepare 会递归准备子树，候选准备存在重复工作，报告不将它宣称为局部常数开销。

## 验证准备和当前状态

独立 `.github/workflows/hw4.yml` 为 JDK17 Maven/Gradle，组为 framework/parser/HW1～HW4，并运行完整 stores-28K。新增差分用例比较 SimplePlanner 与优化器的全行多重集合、schema、表顺序、重复/NULL键、左右外连接 WHERE/ON 区别、派生表、聚合、OR、不相连输入和自连接；这些用例**尚未执行**。

`scripts/hw4_experiments.py` 复用完整数据驱动，增加 Oregon 表/条件顺序及 ON/WHERE 变体，准备检查核心连接计划一致、完整结果哈希一致，并与已保存 HW3 查询哈希及成本比较。不能用已通过的 HW3 结果代替 HW4 的执行结果。[设计题](../doc/lab4design.txt) 的实现说明已填写，C1～C4 实际 EXPLAIN 待执行后补入。

发布时 GitHub 拒绝当前 OAuth App 新增工作流，明确报错缺少 `workflow` scope；现有 edidada SSH 身份也认证失败。本机仅编辑、Python AST 与 `git diff --check` 静态检查，未本地编译/测试，未捏造远端 CI。需要恢复已有 GitHub 登录的 workflow 权限后，继续发布、读取失败日志并修复，直至最终 SHA 的双构建及完整数据实验完成。

课程总表仍保留 HW4 未完成，不增加实验验收计数。学校私有评分及正式课程提交不在已完成范围。
