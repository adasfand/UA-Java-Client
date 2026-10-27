# Java OPC UA 客户端

这是项目第一阶段的通信层，使用 Java 21、Maven 和 Eclipse Milo 1.1.6。提供中文命令行入口，后续桌面 UI 可以复用同一通信层。

当前功能：外部配置检查、独立发现地址、可配置客户端身份、按人工确认的指纹导入服务器证书、认证连接、逐页节点浏览、结构化读取，以及默认关闭的模拟点写入与回读。桌面界面、趋势分析、订阅和应用层连接状态/恢复策略尚未实现；Milo 自身提供保活和会话恢复机制。

整体调用逻辑、配置泛化能力与边界见 [CODE_GUIDE.md](CODE_GUIDE.md)。

尚待执行的验证见 [TODO.md](TODO.md)；本机界面操作见 [UI_TEST.md](UI_TEST.md)。

## 目录与环境

```text
src/main/java/cn/buct/ua/    Java 通信与命令行源码
src/test/java/cn/buct/ua/    写入门禁、类型与配置测试
config/templates/          可提交的配置模板，不含密码
config/local/              各人自己的配置，不提交
pki/                       各人自己的证书和私钥，不提交
.cache/                    Maven 依赖、发行包及临时文件，不提交
target/                    构建产物，不提交
run.ps1                    Windows 中文启动入口
```

以下命令均在项目根目录执行。通过本机环境变量 `JAVA_HOME` 选择 JDK 21；代码和脚本不绑定个人电脑的 JDK 安装位置。项目目录、缓存和产物采用相对路径，脚本以自身所在目录为基准解析。项目放在哪个盘，Maven 用户目录、依赖仓库和临时文件就保存在该项目目录下，不修改其他项目的全局 Maven 设置。

IDEA 打开本目录的 `pom.xml`，选择 JDK 21。Maven 仓库通过 `.mvn/maven.config` 指向项目下的 `.cache/maven-repository`。如 IDEA 的 Maven 设置覆盖了项目设置，请把 Local repository 手工指向这个目录；Importer 和 Runner 同样选择 JDK 21。IDEA 自己的全局索引、插件和操作系统缓存不由本项目脚本管理。

## 1. 构建

在项目目录打开 PowerShell：

```powershell
.\run.ps1 构建
```

首次需要联网下载固定版本依赖。生成 `target/ua-java-client.jar`。单独执行测试：

```powershell
.\run.ps1 测试
```

跨平台可使用 JDK 21 和 Maven：`./mvnw verify`。Windows 也可直接执行 `mvnw.cmd verify`，但若要把 Maven 发行包与临时目录也留在项目盘，优先使用上面的 PowerShell 入口。

## 2. 准备个人配置

```powershell
New-Item -ItemType Directory -Path config/local -Force
Copy-Item -LiteralPath config\templates\client.properties -Destination config\local\client.properties
.\run.ps1 检查配置
.\run.ps1 发现
```

这些命令不需要业务账号密码，也不写业务点位。发现成功只代表服务器返回了端点，不代表认证会话和业务读写通过。

模板匹配开发现场之前发现的 `Basic256 + SignAndEncrypt + username`。每个人都应先执行“发现”，再按自己服务器的实际策略配置；程序不会自动切换到 None 或自动信任陌生证书。旧策略仅作为当前本机环境的显式兼容配置，生产应以甲方提供的安全配置为准。

所有配置按 UTF-8 读取。配置中的相对路径以配置文件所在目录为基准，避免 IDEA 工作目录变化导致点表丢失。

## 3. 初始化客户端证书

```powershell
.\run.ps1 初始化证书
```

终端会隐藏输入证书库密码。它与 KEPServerEX 用户密码是两种不同的密码。已经生成的身份不会被自动覆盖，也不会每次启动重新生成。

- `pki/client.p12`：含私钥，只留本机。
- `pki/client.der`：可导入 KEPServerEX 的 OPC UA 配置管理器 → 受信任客户端。
- `application.uri` 必须与证书的 URI 一致。多人开发时，各人配置一个独立 URI 并各自生成身份，不共享私钥。

`pki.file`、`pki.alias` 可选择 PKI 目录内已有的 PKCS12/PFX 私钥身份；生成时的名称、组织、DNS/IP SAN、有效天数由 `pki.*` 配置。模板中的 localhost/回环地址仅用于本机示例，部署时按实际环境填写；已有证书不会因修改配置而自动更新。`client.der` 是公开导出文件名，不能用作私钥库文件名。

在终端中运行时，程序通过控制台隐藏输入；在 IDEA 中没有交互式控制台时，会弹出本机密码输入框。操作者自行输入并确认，密码只在当前进程中使用。也可从本机环境变量 `UA_KEYSTORE_PASSWORD` 和 `UA_PASSWORD` 获取密码；禁止将密码写入共享运行配置、配置模板、截图、提交或日志。

项目的 `.run/` 提供共享 IDEA 运行配置：`01 配置检查`、`02 端点发现`、`03 初始化客户端证书`、`04 浏览节点`、`05 读取点位`、`06 浏览模拟点`，以及需单独准备本机测试配置的 `07 模拟写入42并回读`。它们不保存密码，使用项目 JDK、相对路径和 Maven 模块依赖。UI 联调步骤与前置检查见 [UI_TEST.md](UI_TEST.md)。

## 4. 人工核验并信任服务器

从 KEPServerEX 配置管理器导出服务器公开证书，在服务器证书界面独立核对 SHA-256 指纹，再执行：

```powershell
.\run.ps1 信任服务器 config\local\client.properties 'config/local/server.der' '<SHA256>'
```

将示例中的证书路径和 `<SHA256>` 替换为实际文件路径及核对过的 64 位十六进制指纹。程序会比较指纹，不符则拒绝导入。不要把发现服务返回的证书未经核对直接自动信任。

客户端严格检查证书有效期、信任、ApplicationUri、主机名等，并开启会话端点校验。当前旧版 KEPServerEX 的证书曾显示 SHA1 签名、负序列号，以及缺少 `127.0.0.1` IP SAN；这可能影响现代客户端连接。遇到错误应按状态码核实和修正服务端证书/连接主机，不能通过“信任所有证书”处理。

## 5. 浏览和读取

双方证书准备好、账号密码保存后：

```powershell
.\run.ps1 浏览
.\run.ps1 浏览 config\local\client.properties 'ns=0;i=85'
.\run.ps1 浏览 config\local\client.properties 'ns=0;i=85' 3
.\run.ps1 读取
.\run.ps1 读取 config\local\client.properties serverTime
```

模板中的 `serverTime` 是标准服务器时间节点，用于首次读取，不是生产点位。读取输出包含值、StatusCode、源时间和服务时间；任一点质量不是 Good，命令返回非零退出码，不会打印“全部读取成功”。

浏览默认一层，可显式指定 1—3 层；多层模式输出变量 DataType、ValueRank 和当前用户的读写属性，最多处理 500 个引用。从 Objects 出发时只递归应用命名空间中的对象，避免展开整个标准服务器子树。达到上限会返回失败，提示指定更具体的根节点。可写属性不代表已确认适合测试写入，还需核对 Simulator 地址与静态存储性质。

添加模拟点时：

1. 浏览实际节点，核实 NodeId、DataType 和权限。
2. 从“浏览”输出的命名空间表中取得该索引对应的 URI。
3. 复制点表模板到 `config/local/nodes.properties`，按模板填写并修改客户端配置中的 `nodes.file`。
4. `nodes` 中列出要使用的别名。支持数字 `i`、字符串 `s`、GUID `g`、Base64 ByteString `b` 标识符；不把旧 DA ItemId 自动拼成 UA NodeId。

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
.\run.ps1 写入 config\local\client.properties testValue 12.5 --确认
```

写前读取质量必须为 Good。程序只写一次，核对逐点写状态，然后读取回值；不重试、不自动回滚，也不把多个写入当原子事务。读回与请求值不一致时返回失败，不能据此断言写操作完全没有影响。

目前支持 Boolean、SByte、Byte、Int16、UInt16、Int32、UInt32、Int64、UInt64、Float、Double、String 标量写入。Float/Double 限有限值；整数拒绝小数和溢出。首版要求模拟静态值精确回读，因此不适用于会被工艺逻辑立即改变的瞬时命令或动态点。

## 协作与 GitHub

Git 根目录仅为本项目，不包含上级“裂解炉2”、安装包或甲方生产资料。公开仓库仅提交源码、模板和文档，不提交本机配置、用户名密码、证书私钥、Maven 缓存和构建产物。

首次关联远程仓库并推送（只需执行一次）：

```powershell
git remote add origin https://github.com/adasfand/UA-Java-Client.git
git push -u origin main
```

后续使用 `git pull --ff-only` 获取更新，提交后执行 `git push`。本机配置、PKI、IDE 设置、构建缓存、日志、诊断转储和临时文件由 `.gitignore` 排除；配置模板和 Maven Wrapper 保留在仓库中。

个人仓库通过 Settings → Collaborators 邀请同学/开发者，对方接受后可以推送修改；公开可见本身不代表所有访客都能直接改仓库。建议各人创建功能分支，通过 Pull Request 合并。

## 当前验收边界

具体构建和本机运行结果记录在 `VERIFICATION.md`。源码具备连接读写入口，不代表生产接口、用户密码和完整工艺逻辑已经验证。本项目第一阶段先完成通信，后续再实现 JavaFX/Swing 桌面 UI。

主要参考：

- [Eclipse Milo v1.1.6](https://github.com/eclipse-milo/milo/tree/v1.1.6)
- [厂商 UA 端点说明](https://support.ptc.com/help/kepware/features/en/kepware/features/OPCUACONFIGMNGR/server-endpoints.html)
- [GitHub 个人仓库协作者权限](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/permission-levels-for-a-personal-account-repository)
