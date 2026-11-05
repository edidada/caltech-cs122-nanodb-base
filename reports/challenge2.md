# CS122 Challenge2：TPC-H查询（实现中）

独立分支edidada/challenge2和challenge2.yml，基于已验收Challenge1。当前尚未完成，不计入完成数。

已固定electrum/tpch-dbgen镜像提交32f1c1b92d1664dba542e927d23d86ffa57aa253的22份原始功能查询模板，来源与SHA256见schemas/tpch/query-source.json。它们不是Caltech发放的参数化实例，也不是本人执行结果。原始参数和控制标记仍待实例化。

实现标准SUBSTRING(value FROM start FOR length)及逗号形式，Unicode码点计数、一基起点、NULL和边界处理，拒绝负长度和非整数位置。SQL端到端测试待Actions执行。

剩余完整22条实例化、CASE/EXTRACT/视图等、独立结果对照、逐查询超时及全解析/规划/执行矩阵。保留所有前置回归。全部Java构建和SQL运行仅在Actions执行，本地仅静态审查。
