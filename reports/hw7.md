# CS122 HW7：预写日志（实现进行中）

从 HW6 尚未验收提交 `ce6fe3539cd647040086ff07d4f044596af118ed` 建立 `edidada/hw7`；不是已完成实验。官方题目与本地 doc/lab7design.txt 要求事务原子性、WAL强制落盘、恢复和设计分析。继承的HW6也仍需独立最终CI，不能用HW7替代验收。

本次实现 beforeWriteDirtyPages：跳过日志和txnstate文件，检查每个待写数据页pageLSN，缺失时明确拒绝写出；取该批最大LSN，只调用一次forceWAL，调用成功后才允许BufferManager继续写数据页。当前forceWAL仍是占位，因此这只是已连接的调用顺序，**不代表已经满足WAL持久化规则**，事务默认仍未开启。

独立hw7.yml准备JDK17 Maven/Gradle、framework/parser/HW1～HW7及完整数据回归。本地没有编译或运行实验；工作流未取得通过证据。

剩余必需项：核对官方Lab7完整要求；实现forceWAL的记录末尾计算、跨日志文件同步和txnstate持久化顺序；补全WALManager的redo/undo、日志文件切换、回滚记录与事务链后退；审查事务启动/提交/回滚与缓冲页记录时机；真正开启事务并验证提交、回滚、进程崩溃恢复和重启事务ID；完成全部设计题。当前实现对无LSN数据页采取失败策略，TEST_FILE等非业务格式是否应排除须按实际日志覆盖明确决定，不能因测试便利静默放行未记录的业务数据页。

HW6运行41863122155仍queued，未重启；整个课程目标未完成，不增加验收条数。
