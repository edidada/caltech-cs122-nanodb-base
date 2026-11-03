# CS122 官方历史基线与构建准备

本仓库是 [Caltech 官方 CS122 Winter2019 NanoDB 基线](https://gitlab.caltech.edu/cs122-19wi/nanodb-base) 的 GitHub 镜像，保留上游完整历史，导入分支 master 的原始提交为 `366f2bbf4bb7a5e8658260c54c7981ac947d907e`。官方 [课程源码说明](https://courses.cms.caltech.edu/cs122/assignments/intro.html) 将它列为 nanodb-base 上游；当前匿名 GitLab API 可以读取和克隆，不必将团队私有仓库的登录要求泛化为基线也不公开。

站内另有 cs121-26wi/nanodb，属于 CS121 2026，未将不同课程的较新版本冒充本课。当前记录是2019历史实验框架，并非2026新版。代码含 HeapTupleFile 等明确 TODO/UnsupportedOperationException，各作业尚未实现；不是从社区已完成答案生成基线。

工作分支 `edidada/cs122-labs`。保留现有 Maven 单工程布局，设置 release17、补充等价 Gradle 构建，ANTLR生成器与运行库统一4.7.1；升级适配JDK17的编译器、Surefire、JaCoCo及Mockito，Log4j固定2.25.2。原始 pom 的 testFailureIgnore=true 改为 false，保留全部原始作业测试组作为默认值。基线CI显式仅选 framework/parser，不将排除的作业测试当成通过。

独立工作流 starter-build.yml 在 Actions 运行 Maven 和 Gradle 原始基础/解析器测试，后续每个作业另建实验分支、yml和报告。当前只验证构建起点和既有基础代码，不计本人完成任何CS122作业。生成关键词仍调用官方 Python 脚本。本地未编译或运行NanoDB。原始框架没有拆分服务器子模块，避免仅为形式上的多模块重排上游源码。
