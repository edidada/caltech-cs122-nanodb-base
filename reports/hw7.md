# CS122 HW7：预写日志实验报告

依据 [Caltech Winter2019 Lab7](https://courses.cms.caltech.edu/cs122/assignments/lab7.html)，使用官方历史 NanoDB 框架，分支 `edidada/hw7`、独立工作流 `.github/workflows/hw7.yml`，JDK17 Maven/Gradle。最终验收 SHA、完整 Actions 状态和原始 XML 核查摘要统一保存在[课程验收记录](https://github.com/edidada/langnote/blob/edidada/csdiy-labs-2026/courses/lab-reports/CS122-HW7.md)及相邻 CS122-HW7-CI.json；本报告描述实现、验证方法与限制。所有 Java 编译和实验在 GitHub Actions 执行，本地仅源码、JSON/XML 和 Git 静态检查。

## 运行配置与记录时机

按官方 Step1，默认 enableTransactions=true、flushAfterCmd=false。官方允许 HW7 关闭索引，本分支 enableIndexes/createIndexesOnKeys=false；BTree尚未支持WAL，启用索引不构成索引事务一致性保证。历史HW1～HW6回归及stores成本实验显式保留事务关闭、索引开启和命令后刷盘配置，其断言、负载和测试组均保留。专用HW7测试不强制覆盖这三个默认设置，启动后检查事务管理器存在以及刷盘/索引关闭。

HeapTupleFile/Manager 及 HW1 FreeSpaceMap 对数据页、头页、统计和空闲空间树修改调用 logDBPageWrite。事务第一次写入时延迟生成 START；更新记录保存旧/新字节及事务previous LSN，发布pageLSN并同步旧页镜像。修改页在日志生成期间保持pin，不能在尚未记录时被逐出。

## 强制日志与缓冲写回

forceWAL 计算包含recordSize的排他末尾，拒绝强制超过已生成日志的边界；逐文件、页区间写出并同步WAL后，才持久化txnstate.nextLSN。事务状态发布异常恢复内存旧边界。恢复完成时，即使日志末尾未变，仅firstLSN前移也保存状态。跨10 MiB日志旋转在下一文件头保存上一文件精确逻辑末尾，不用整页填充后的文件大小代替。

beforeWriteDirtyPages 对heap数据页取最大pageLSN，强制成功后才允许写数据；heap脏页缺失LSN明确报错。WAL/state/test格式不参与该规则。BufferManager 的容量逐出、flushDBFile及flushAll均在观察者完成前保留待写脏页于cache，让嵌套force仍能看到脏WAL页；成功写回后才移除/失效，失败保留页面供重试。

写回期间允许最多256 KiB的有界临时缓冲，用于WAL/txnstate加载及旧页镜像，避免默认1 MiB缓存满时递归逐出同一批脏页。普通分配仍受原缓存上限约束；没有扩大测试配置缓存，也没有缩减生产10 MiB阈值或跨文件负载。

## 回滚与恢复

显式ROLLBACK沿事务previous链后退，到START停止。UPDATE应用undo并写redo-only补偿，补偿链接指向undo-next；更新会话lastLSN、pageLSN及旧镜像，完成后写ABORT。已经补偿的记录沿链跳过，不重复撤销。

Redo按日志顺序重放更新和补偿并跟踪未完成事务。Undo使用物理反向遍历与每事务undo-next，只撤销仍需撤销的记录，生成补偿及ABORT。恢复期间使用显式事务ID而非当前会话状态；结束时先强制WAL，再同步全部数据，最后推进firstLSN。reader在finally释放，校验分段边界、后退链与记录尾部；合法零分段redo不访问干净页不存在的旧镜像。

事务状态保存nextTxnID；运行时检查在正常关闭重启后分配的ID严格大于关闭前最后使用的ID，覆盖无数据修改但已消耗ID的事务。设计题 A1～A4、B1～B4、C1 见 doc/lab7design.txt。

## 测试与数据

工作流执行 framework/parser/HW1～HW7，预期270项（前置261项加以下9项），实际总数、失败/错误/跳过及完整测试名以最终XML核查为准：

- 无变化连续forceWAL的存储页写计数不增加；提交、两次回滚、后续提交、重启完整行值与首次事务ID不复用。
- 单文件提交后不主动刷数据即Runtime.halt(73)，重启redo；未提交数据先writeAll(true)后halt，重启undo。
- 跨文件redo/undo：一个事务写入7000行、每行1600字符，检查第二个WAL文件存在。提交模式重启逐条核对7000个id和完整payload；未提交模式强制数据落盘后退出，重启应撤销全部7000行并保留之前提交的两行。都再正常关闭重启核对，不称作“恢复中再次崩溃”。子进程180秒超时，退出码必须73。
- 64 KiB缓存实际容量逐出和显式flush，检查脏WAL页仍可发现，真实文件管理器的WAL保存/同步先于heap数据保存；观察者注入一次失败时不写heap，脏页仍在cache，随后可重试。测试用spy观察真实IO并显式注入错误，不把故障模拟称作外部磁盘实测。

完整stores-28K仍运行13条查询，核对四表51/254/2000/24932行及全结果SHA256；成本实验采用HW5模型，不用旧成本单位推导加速比。完整查询产物随工作流保存。

## 已复现并修复的问题

42015127871发现受检WALFileException不符合原存储API，校验改用DataFormatException。42015255513执行264项、三项在redo同步干净页旧镜像时失败；修复后862eea8的42016106554双构建264项及13查询完整成功。

2691c15的42017726686执行266项，两个跨文件测试在默认缓存中因txnstate写回递归分配失败；由此修复有界写回缓冲，并审查到脏WAL页过早移出cache可能破坏写入顺序。后续加入实际写入顺序、显式flush与故障重试测试。所有中间失败均保留，不能以旧264项绿灯替代最终270项验收。

## 限制

范围为官方heap WAL实验的公开自学验证，没有学校私有grader或正式课程评分。DDL不支持回滚，BTree事务、并发锁顺序的形式证明、日志文件号65535→0循环复用和事务ID耗尽策略未完成；截断/损坏日志的完整记录预验证、恢复过程中再次崩溃故障注入也没有声称通过。上述边界不由正常重复重启测试推出。

官方Step3的true no-op要求复核：仅在firstLSN或nextTxnID变化时额外保存txnstate，无变化的force立即返回；保存基线仅在同步成功后更新，并释放本次加载的状态页pin。新增真实存储写计数检查，恢复边界和无写事务ID仍由既有重启用例验证。此前269项基线完整通过，不替代本次270项最终复验。
