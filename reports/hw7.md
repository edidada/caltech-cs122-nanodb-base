# CS122 HW7：预写日志（实现进行中）

从 HW6 尚未验收提交 `ce6fe3539cd647040086ff07d4f044596af118ed` 建立 `edidada/hw7`；不是已完成实验。官方题目与本地 doc/lab7design.txt 要求事务原子性、WAL强制落盘、恢复和设计分析。继承的HW6也仍需独立最终CI，不能用HW7替代验收。

本次实现 beforeWriteDirtyPages：跳过日志和txnstate文件，检查每个待写数据页pageLSN，缺失时明确拒绝写出；取该批最大LSN，只调用一次forceWAL，调用成功后才允许BufferManager继续写数据页。当前forceWAL仍是占位，因此这只是已连接的调用顺序，**不代表已经满足WAL持久化规则**，事务默认仍未开启。

独立hw7.yml准备JDK17 Maven/Gradle、framework/parser/HW1～HW7及完整数据回归。本地没有编译或运行实验；工作流未取得通过证据。

剩余必需项：核对官方Lab7完整要求；实现forceWAL的记录末尾计算、跨日志文件同步和txnstate持久化顺序；补全WALManager的redo/undo、日志文件切换、回滚记录与事务链后退；审查事务启动/提交/回滚与缓冲页记录时机；真正开启事务并验证提交、回滚、进程崩溃恢复和重启事务ID；完成全部设计题。当前实现对无LSN数据页采取失败策略，TEST_FILE等非业务格式是否应排除须按实际日志覆盖明确决定，不能因测试便利静默放行未记录的业务数据页。

HW6运行41863122155仍queued，未重启；整个课程目标未完成，不增加验收条数。

forceWAL第一阶段实现（未验证）：用记录偏移+recordSize计算排他末尾，拒绝超过已生成日志，按文件及页范围writeDBFile(sync=true)，最后才持久化txnstate.nextLSN；持久化异常恢复内存中的旧边界。恰好切换到下一文件首记录时无需打开尚未创建的新文件。当前LSN比较器按文件号排序，65535→0回绕尚未支持；跨线程原子性/缓冲锁顺序也未证明，不能据此宣称并发或崩溃持久化已验收。事务默认仍关闭；redo/undo和日志切换仍有占位。

显式ROLLBACK事务链实现（未执行）：检查记录事务ID与类型，START终止，UPDATE读取前一LSN及页面修改前字节、恢复页面并生成redo-only补偿记录；补偿链接指向undo-next，更新会话lastLSN、pageLSN和页面旧镜像，再沿原previous继续。redo-only直接沿链跳过，拒绝不后退的链或非预期记录。最后写ABORT。编号回绕尚未支持；启动恢复performRedo/performUndo仍未实现，不能把显式回滚实现当作崩溃恢复完成。

启动redo扫描实现（未执行）：识别START/COMMIT/ABORT/UPDATE/redo-only，累计事务最大ID和未完成事务；重放UPDATE/补偿数据到页面、更新pageLSN和旧镜像，校验尾部起始偏移与类型字节，推进排他下一LSN，完成事务从集合移除。重放采取重复写入绝对字节方式，不跳过可能缺少持久pageLSN的页。恢复undo仍占位，异常关闭reader及日志截断防护仍需补强；该阶段不构成恢复验收。

恢复undo第一阶段实现（未执行）：保留物理日志逆序扫描，并为每个未提交事务单独维护undoNext游标；只处理游标命中的记录，redo-only沿其链接跳过已补偿的原始更新，UPDATE恢复旧字节并写补偿，START写ABORT并移除未完成事务。恢复范围耗尽但仍有未完成事务时明确报错，不把部分回滚当成功。reader正常结束关闭，异常finally仍需统一补齐。尚无崩溃重复恢复证据。

日志切换实现（未执行）：三种记录写入共用advanceNextLSN，达到文件阈值时立即创建下一日志头并存前一文件的精确记录末尾（不使用含页填充的物理文件长度）。forceWAL在排他边界恰好为下一文件首记录时也同步存在的头页，保证反向扫描可读取前一文件末尾。首个新日志的previous-end为0。编号回绕和异常清理仍待处理，未声称日志切换通过。
