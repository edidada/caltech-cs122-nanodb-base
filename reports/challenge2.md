# CS122 Challenge2：TPC-H查询（实现中）

独立分支edidada/challenge2和challenge2.yml，基于已验收Challenge1。当前尚未完成，不计入完成数。

已固定electrum/tpch-dbgen镜像提交32f1c1b92d1664dba542e927d23d86ffa57aa253的22份原始功能查询模板，来源与SHA256见schemas/tpch/query-source.json。它们不是Caltech发放的参数化实例，也不是本人执行结果。原始参数和控制标记仍待实例化。

实现标准SUBSTRING(value FROM start FOR length)及逗号形式，Unicode码点计数、一基起点、NULL和边界处理，拒绝负长度和非整数位置。SQL端到端测试待Actions执行。

剩余完整22条实例化、CASE/EXTRACT/视图等、独立结果对照、逐查询超时及全解析/规划/执行矩阵。保留所有前置回归。全部Java构建和SQL运行仅在Actions执行，本地仅静态审查。

已新增全部22条固定参数实例化：去除dbgen控制指令，按:n保留结果上限，Q15保留完整view建立/查询/删除，不删除不支持语法。参数清单明确为自学固定配置而非TPC认证随机流；Q11使用0.0001/SF。manifest记录每条原模板哈希、实例SQL哈希、参数和上限，状态仍为未执行。

## 本轮执行矩阵实现，尚未远端验证

新增Challenge2Query.java与challenge2_matrix.py：先复用Challenge1固定输入、全特性导入与独立内容验收，再从同一ANALYZE后数据复制单个临时目录，每条独立JVM、120秒超时。stage.txt在启动/解析/规划/执行/关闭前写入，外部终止仍可定位阶段；SQL、计划、完整结果JSONL、异常栈和耗时逐条保留。22条都进入矩阵，失败不省略。Q15完整DDL在独立副本执行，不污染后续查询。

新增独立DuckDB 1.4.1对照入口（版本已核实PyPI存在，但尚未安装/运行）：直接从固定官方INSERT文本按原表类型构造独立数据库；每查询独立子进程120秒，分别记录oracle执行状态。双方成功才比较完整有序行，保留重复与NULL；数值相对误差1e-7、绝对误差1e-5，字符串精确匹配。排序同值行可能保守地报不匹配，不隐藏该限制。只将match列为结果通过，不把执行器退出0或查询执行成功当正确性验收。

Actions保存完整矩阵及每查询产物，预留120分钟上限覆盖最坏22条双引擎超时和完整前置回归。当前源码/AST静态检查通过；Java编译、DuckDB加载、查询实际结果均未执行，发布权限限制仍需解决。CASE/EXTRACT/视图及日期/interval语法等功能仍需根据真实矩阵补齐，不能把当前执行框架视为完成Challenge2。
