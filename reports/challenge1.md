# CS122 Challenge1：TPC-H 全特性导入（实现中）

依据官方 Winter2019 Challenge1，独立分支 edidada/challenge1 与工作流 challenge1.yml。基于已验收HW7 de497d6，当前新增内容尚未远端验证，不计入完成。

- 补BTree页WAL：页修改版本追踪，最后一次unpin前与提交/回滚边界记录待写索引变化；显式flush检查索引pageLSN。该实现针对现有单写者模型，不声明并发隔离。
- 主键/外键存在性检查优先使用同列顺序索引，缺失时保留原扫描回退；NULL语义沿用SQL相等条件。
- 四项集成测试：自动主键与二级索引的回滚/重启完整键与FilePointer核对、主外键拒绝、真实Runtime.halt后的redo/undo。
- 固定哈希官方SF0.01输入，10 MiB配置缓存，事务/索引/约束全开，关闭逐命令刷盘。完整八表86805行导入、ANALYZE、关闭及重启计时，记录datafiles大小；重启后核对全表独立CSV/Decimal oracle哈希、SQL COUNT、customer分组、全部索引条目与VERIFY。

保留HW1～HW7所有测试及完整stores查询；Maven和Gradle均在Actions执行，本地仅静态语法及源码检查。本挑战把256 KiB写回预留放入配置的10 MiB总池内部，普通分配最多9.75 MiB，写回连同旧页镜像也不能超过10 MiB；输出实际分配峰值并断言不超限。不作为官方TPC认证或课程竞赛排名。

最终目标为上述全特性完整导入，不以关闭事务、索引或约束的较小阶段代替。独立benchmark完整跑通、正确性核查及最终提交CI均待执行。课程Challenge2仍未实现。

首轮42037762226两套构建及stores回归通过，TPC-H下载因官方站证书缺issuer失败。下载器现在仅对固定官方输入在此特定证书错误时回退，写入前强制核对既定大小和SHA256，其他错误不降级；本次不宣称TLS认证成功。另增加包含写回临时空间的严格总池预留与峰值检查，待新SHA远端验证。
