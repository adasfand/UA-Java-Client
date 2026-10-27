# Java OPC UA 客户端

这是项目第一阶段的通信层，使用 Java 21、Maven 和 Eclipse Milo 1.1.6。提供中文命令行入口，后续桌面 UI 可以复用同一通信层。

当前功能：外部配置检查、端点发现、客户端证书生成、按人工确认的指纹导入服务器证书、认证连接、节点浏览、读取，以及默认关闭的模拟点写入与回读。暂未实现桌面界面、趋势分析、订阅和断线重连，也不包含工艺控制程序。

## 目录与环境

```text
src/main/java/cn/buct/ua/    Java 通信与命令行源码
src/test/java/cn/buct/ua/    写入门禁、类型与配置测试
配置模板/                  可提交的中文配置模板，不含密码
本机配置/                  各人自己的配置，不提交
证书/                      各人自己的证书和私钥，不提交
.缓存/                     Maven 依赖、发行包及临时文件，不提交
target/                    构建产物，不提交
运行.ps1                   Windows 中文启动入口
```

本机工程放在 `E:\BUCT\UA\UA-Java-Client`，JDK 放在 `E:\Java\JDK21`。脚本把 Maven 用户目录、依赖仓库、构建临时文件和程序临时文件放在项目所在盘，不修改其他项目的全局 Maven 设置。项目移到别的电脑时，设置本机 `JAVA_HOME` 即可。

IDEA 打开本目录的 `pom.xml`，选择 JDK 21。Maven 仓库通过 `.mvn/maven.config` 指向项目下的 `.缓存/Maven仓库`。如 IDEA 的 Maven 设置覆盖了项目设置，请把 Local repository 手工指向这个目录；Importer 和 Runner 同样选择 JDK 21。IDEA 自己的全局索引、插件和操作系统缓存不由本项目脚本管理。

## 1. 构建

在项目目录打开 PowerShell：

```powershell
.\运行.ps1 构建
```

首次需要联网下载固定版本依赖。生成 `target/ua-java-client.jar`。单独执行测试：

```powershell
.\运行.ps1 测试
```

跨平台可使用 JDK 21 和 Maven：`./mvnw verify`。Windows 也可直接执行 `mvnw.cmd verify`，但若要把 Maven 发行包与临时目录也留在项目盘，优先使用上面的 PowerShell 入口。

## 2. 准备个人配置

```powershell
New-Item -ItemType Directory -Path 本机配置 -Force
Copy-Item -LiteralPath 配置模板\客户端.properties -Destination 本机配置\客户端.properties
.\运行.ps1 检查配置
.\运行.ps1 发现
```

这些命令不需要业务账号密码，也不写业务点位。发现成功只代表服务器返回了端点，不代表认证会话和业务读写通过。

模板匹配开发现场之前发现的 `Basic256 + SignAndEncrypt + username`。每个人都应先执行“发现”，再按自己服务器的实际策略配置；程序不会自动切换到 None 或自动信任陌生证书。旧策略仅作为当前本机环境的显式兼容配置，生产应以甲方提供的安全配置为准。

所有配置按 UTF-8 读取。配置中的相对路径以配置文件所在目录为基准，避免 IDEA 工作目录变化导致点表丢失。

## 3. 初始化客户端证书

```powershell
.\运行.ps1 初始化证书
```

终端会隐藏输入证书库密码。它与 KEPServerEX 用户密码是两种不同的密码。已经生成的身份不会被自动覆盖，也不会每次启动重新生成。

- `证书/客户端.p12`：含私钥，只留本机。
- `证书/客户端公开证书.der`：可导入 KEPServerEX 的 OPC UA 配置管理器 → 受信任客户端。
- `application.uri` 必须与证书的 URI 一致。多人开发时，各人配置一个独立 URI 并各自生成身份，不共享私钥。

如果在 IDEA 运行时没有交互式控制台，程序会要求从本机环境变量 `UA_KEYSTORE_PASSWORD` 和 `UA_PASSWORD` 获取密码。只在本机设置，禁止写入命令参数、配置模板、截图、提交或日志。

## 4. 人工核验并信任服务器

从 KEPServerEX 配置管理器导出服务器公开证书，在服务器证书界面独立核对 SHA-256 指纹，再执行：

```powershell
.\运行.ps1 信任服务器 本机配置\客户端.properties '服务器证书完整路径.der' '核对过的64位SHA256十六进制值'
```

程序会比较指纹，不符则拒绝导入。不要把发现服务返回的证书未经核对直接自动信任。

客户端严格检查证书有效期、信任、ApplicationUri、主机名等，并开启会话端点校验。当前旧版 KEPServerEX 的证书曾显示 SHA1 签名、负序列号，以及缺少 `127.0.0.1` IP SAN；这可能影响现代客户端连接。遇到错误应按状态码核实和修正服务端证书/连接主机，不能通过“信任所有证书”处理。

## 5. 浏览和读取

双方证书准备好、账号密码保存后：

```powershell
.\运行.ps1 浏览
.\运行.ps1 浏览 本机配置\客户端.properties 'ns=0;i=85'
.\运行.ps1 读取
.\运行.ps1 读取 本机配置\客户端.properties serverTime
```

模板中的 `serverTime` 是标准服务器时间节点，用于首次读取，不是生产点位。读取输出包含值、StatusCode、源时间和服务时间；任一点质量不是 Good，命令返回非零退出码，不会打印“全部读取成功”。

添加模拟点时：

1. 浏览实际节点，核实 NodeId、DataType 和权限。
2. 从“浏览”输出的命名空间表中取得该索引对应的 URI。
3. 复制点表模板到 `本机配置/点位.properties`，按模板填写并修改客户端配置中的 `nodes.file`。
4. `nodes` 中列出要使用的别名。当前支持数字 `i` 和字符串 `s` 标识符；不把旧 DA ItemId 自动拼成 UA NodeId。

程序连接后按 URI 解析命名空间索引，不假定每台服务器都是 `ns=2`。

## 6. 受控模拟写入

首版写操作仅用于**本机模拟器上经确认可写的标量测试点**，不用于生产工艺控制。

需要同时满足：

- 客户端配置 `environment=simulation`、`writes.enabled=true`。
- endpoint 是明确的本机回环地址。
- 点表中该点 `writable=true`，数值点提供有效 `minimum`、`maximum`。
- 服务器实际 DataType 与配置一致，ValueRank 为标量，当前用户具有写权限。
- 命令携带 `--确认`。

```powershell
.\运行.ps1 写入 本机配置\客户端.properties testValue 12.5 --确认
```

写前读取质量必须为 Good。程序只写一次，核对逐点写状态，然后读取回值；不重试、不自动回滚，也不把多个写入当原子事务。读回与请求值不一致时返回失败，不能据此断言写操作完全没有影响。

目前支持 Boolean、SByte、Byte、Int16、UInt16、Int32、UInt32、Int64、UInt64、Float、Double、String 标量写入。Float/Double 限有限值；整数拒绝小数和溢出。首版要求模拟静态值精确回读，因此不适用于会被工艺逻辑立即改变的瞬时命令或动态点。

## 协作与 GitHub

Git 根目录仅为本项目，不包含上级“裂解炉2”、安装包或甲方生产资料。公开仓库仅提交源码、模板和文档，不提交本机配置、用户名密码、证书私钥、Maven 缓存和构建产物。

你在 GitHub 创建空的公开仓库后，将本地项目关联并推送：

```powershell
git remote add origin https://github.com/adasfand/UA-Java-Client.git
git push -u origin main
```

个人仓库通过 Settings → Collaborators 邀请同学/开发者，对方接受后可以推送修改；公开可见本身不代表所有访客都能直接改仓库。建议各人创建功能分支，通过 Pull Request 合并。

## 当前验收边界

具体构建和本机运行结果记录在 `验证记录.md`。源码具备连接读写入口，不代表生产接口、用户密码和完整工艺逻辑已经验证。本项目第一阶段先完成通信，后续再实现 JavaFX/Swing 桌面 UI。

主要参考：

- [Eclipse Milo v1.1.6](https://github.com/eclipse-milo/milo/tree/v1.1.6)
- [厂商 UA 端点说明](https://support.ptc.com/help/kepware/features/en/kepware/features/OPCUACONFIGMNGR/server-endpoints.html)
- [GitHub 个人仓库协作者权限](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/permission-levels-for-a-personal-account-repository)
