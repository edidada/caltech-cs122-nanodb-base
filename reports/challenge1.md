# CS122 Challenge1：TPC-H 全特性导入实验报告

依据[官方 Winter2019 Challenge1](https://courses.cms.caltech.edu/cs122/assignments/challenge1.html)，独立分支 `edidada/challenge1`、工作流 `.github/workflows/challenge1.yml`。最终提交、完整CI状态、实际数据及计时统一保存于[课程验收记录](https://github.com/edidada/langnote/blob/edidada/csdiy-labs-2026/courses/lab-reports/CS122-Challenge1.md)和相邻机器证据。本报告描述实现和验证口径；公开自学记录不等于学校评分或TPC认证。

## 官方输入与规模

使用官方 make-tpch.sql 及 s-0.01.tbz，固定大小、归档和各SQL文件SHA256见 scripts/challenge1-input-manifest.json。八表依次region5、nation25、part2000、supplier100、partsupp8000、customer1500、orders15000、lineitem60175，共86805行。选择官方提供的SF0.01规模，SF0.1和SF1未验证。

首轮42037762226已经通过Maven/Gradle各274项及完整stores回归，输入下载因官方站证书链缺issuer失败。下载器只对此特定错误、固定官方URL回退，写入前硬校验预先核实的大小/SHA256；其他错误不降级，也不宣称该回退路径完成TLS认证。Oracle另行解析固定INSERT文本、CSV引号及Decimal标准化，生成各表排序行SHA256与customer分组计数，独立于NanoDB输出。

## BTree事务与约束查找

DBPage记录修改版本及最后WAL镜像版本。BTree页在最后一个pin释放前写日志；提交/回滚前再遍历缓存补齐仍受pin保护的索引变化，确保COMMIT或undo之前已有对应日志。写出观察者处理显式FLUSH并核查索引pageLSN。普通逐出不允许未记录的索引页进入写回，避免在另一个WAL记录尚未组装完时递归追加记录。恢复和补偿沿用HW7页日志格式，旧页镜像同步后重置版本。

该设计针对现有单写者模型，不提供多事务并发隔离。DDL回滚和物理文件截断没有新增保证。主键/外键存在性检查优先选择列顺序完全匹配的索引，用键前缀查找并释放返回行；无索引时保留原扫描回退，NULL遵循原SQL相等条件语义。

## 严格缓存和加载策略

总池固定10485760字节，包含256 KiB内部写回预留。普通分配最多9.75 MiB，写回及旧页镜像也不能使BufferManager分配超过10 MiB。只读启动属性nanodb.pagecache.writebackReserve在任何页加载和恢复前生效；报告取加载实例与重启实例的实际分配峰值，Python验收断言不超限。

事务、自动索引及主外键约束全部开启，flushAfterCmd=false。每表一个显式事务，共8次数据提交；每条INSERT仍检查约束、更新索引并生成WAL。相较逐行自动提交，该策略减少COMMIT同步次数，不关闭任何特性，也不声明逐行提交的崩溃语义。

## 测试与完整实验

工作流保留framework/parser/HW1～HW7并加入六项Challenge1集成检查，预期两套各276项，实际执行数量/失败/错误/跳过以最终XML为准：

- 自动主键和二级索引的插入、更新、删除、回滚、关闭及重启，逐条核对表值、完整索引键和FilePointer，并检查索引总数/VERIFY。
- 主键重复、NULL主键和不存在外键拒绝。
- 两项独立JVM Runtime.halt(79)测试：已提交索引变化redo、已强制落盘但未提交索引变化undo；保留400行基线，检查提交后1600行或撤销后400行，重复启动核对。
- NUMERIC负数、字节边界及完整15字节有符号范围的写入/物理关闭重开检查，超范围明确拒绝。
- 64 KiB严格缓存用例，将写回scratch也包含在总预算内，制造真实容量逐出并断言峰值不超限。

完整实验固定全开配置，流式执行八表全部SQL。计时分别记录加载（含启动/建表）、ANALYZE、关闭、重启；验证阶段在计时之外。重启后检查每表完整行哈希、SQL COUNT、持久化统计行数、customer按nation分组、每个索引的完整键/行指针、总数和VERIFY，并记录datafiles目录总字节数。结果还记录实际开关、缓存预算/峰值、内部预留及8次数据提交。

源码执行入口 scripts/challenge1.py 与 scripts/Challenge1.java 可复用，CI YAML只调用构建和实验脚本。完整stores-28K十三查询继续作为回归。所有Java编译、数据库运行和性能实验仅在GitHub Actions执行，本地只做源码、Python AST、JSON/XML及Git静态核查。

## 验收边界

只有固定八表全部导入、统计、关闭、恢复、独立内容核对以及最终提交的完整CI通过，才记录完成。没有以关闭事务、索引或约束的阶段结果替代。学校私有grader、课程竞赛提交、官方TPC性能认证没有执行；日志编号循环复用、事务ID耗尽、并发隔离和恢复中再次崩溃仍需另行验证。Challenge2查询实验仍待实现。

独立内容oracle复现：运行42039755789完整导入并重启，八表计数/统计/索引检查通过，但supplier全行哈希不一致。DBPage.writeNumeric把较短负数的BigInteger编码零扩展成15字节，已改为符号扩展；保留原始oracle哈希并新增有符号NUMERIC持久化边界回归，不以正确行数或相同引擎结果替代内容正确性。
