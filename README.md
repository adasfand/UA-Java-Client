# Java OPC UA 客户端

项目使用 Java 21、Maven 和 Eclipse Milo 1.1.6，提供中文命令行入口和 Swing 桌面服务器连接窗口，共用同一通信层。

当前桌面功能覆盖第一部分的本机通信演示：首次准备配置、证书初始化与复用、服务器查询/认证连接/断开、同次运行内凭据复用、按设备浏览选点、点表保存、读取值/质量/时间、点位写授权与范围配置，以及一次模拟写入和回读。连续采集、近期缓存、Excel 存储、趋势分析和应用层连接恢复策略尚未实现，Milo 自身提供保活和会话恢复机制。

整体调用逻辑、配置泛化能力与边界见 [CODE_GUIDE.md](CODE_GUIDE.md)。

尚待执行的验证见 [TODO.md](TODO.md)；本机界面操作见 [UI_TEST.md](UI_TEST.md)。

## 桌面服务器客户端

已构建项目在 Windows 双击 **start-client.cmd** 即可打开桌面，不需要启动 IDEA。也可解压 `dist/ua-java-client.zip`，在解压后的目录双击同名启动器；需要 JDK 21，可通过 JAVA_HOME 或 PATH 选择。Linux 提供 `start-client.sh`，须在目标系统另行验证。

在 IDEA 选择运行配置 **服务器客户端**，或无参数运行 `cn.buct.ua.Main.main()` / `DesktopMain.main()`，也会打开同一桌面。IDEA 工作目录应为项目根目录；启动器自动切换到自身目录。默认读取 `config/local/client.properties`，缺少时通过确认框从 `config/templates` 创建本机连接配置和点表，不覆盖个人文件。显式命令参数仍使用原命令行接口；查看帮助用 `--help`。

也可先构建后运行：

```powershell
.\run.ps1 构建
.\run.ps1 桌面
```

1. 主窗口点击 **服务器 → 连接…**，打开与旧 C# 连接窗口布局接近的对话框。
2. 确认服务器地址；修改“远程 IP / 主机”只替换地址中的主机部分，保留端口与路径。初始值来自配置文件。
3. 点击 **查询**，列表显示该发现地址返回的 UA 端点及安全策略。查询成功只表示服务器响应了发现请求。
4. 选择与服务器实际配置匹配的端点，填写 KEPServerEX 用户名，点击 **连接**。连接前会把地址、发现地址、所选策略及用户名保存到当前配置文件；不先查询时，使用配置文件指定的安全策略和身份类型。也可只点击 **保存配置**，不发起登录。
5. 首次依次输入已有证书库密码和服务器用户密码。完整认证成功后，凭据仅缓存于本次桌面进程内，同一服务器/用户重连不再询问；服务端仍执行认证。匿名身份只需证书库密码。
6. 认证成功后，连接窗口关闭，主窗口显示“会话已建立：认证成功”及地址、策略、用户名，并打开点位浏览窗口。会话保持到 **服务器 → 断开** 或退出客户端。此文本表示最近一次建立会话的结果，不是实时网络探测。
7. 失败时保留连接窗口并提示原因，允许修正地址、用户名或选择后重试。查询、连接、断开在后台执行；操作期间禁用重复提交，可在密码窗口取消认证。

### 界面读取和写入

关闭点位选择窗口后，主窗口显示当前点表。点击 **读取全部** 或选择一行后点击 **读取所选**，查看值、质量和源时间；显示的值是最近一次请求的结果，不是连续采集。

对本机静态 Simulator 测试点，选择 **点位设置…**，勾选应用写授权并填写数值范围，保存到同一份点表。再勾选 **允许本机模拟写入**，输入值并点击 **写入并回读**，核对确认框中的服务器、点位和数值。程序复用当前已认证会话，检查最新配置及服务端类型/权限，写一次后回读；只有写状态和读回质量均为 Good 且值匹配才显示完成。失败不会自动重试或回滚。

演示结束后关闭总开关，并在点位设置中取消写授权。UI 的开关和范围均写回当前 `config/local` 文件，保存前备份、检查外部修改并原子替换；不需要手工编辑文件。

UI 与当前配置文件共用一份参数：未连接时打开 **服务器 → 连接配置…**，可编辑连接、账号、应用、PKI、浏览和点表路径；相对路径按原样保存，以配置文件所在目录为基准。连接窗口的 **从文件重载** 可回显手工修改；每次重新打开连接窗口也会重新读取文件。保存会校验参数、清理被更新键的重复定义、备份原文件并原子替换，检测到外部修改时拒绝覆盖。更改应用 URI 或证书生成参数不会自动重签已有证书；点表条目继续由点位窗口管理。

密码只在运行时输入，文件中的 passwordEnv 项是环境变量名称。凭据按证书库实际路径/内容、应用身份隔离，用户口令还按端点、用户名和安全策略隔离。认证失败不会缓存本次新输入；失败重连会失效该用户口令以便重新输入。**服务器 → 清除本次记住的凭据** 会清空缓存，退出也会清除；清除不会改变当前已连接会话使用的凭据。重启客户端后仍需输入，不提供跨进程保存密码。

原 C# 使用 OPC DA 枚举/ProgID；服务器窗口的“查询”对指定发现地址执行 OPC UA GetEndpoints，不是局域网扫描，也不是点位扫描。原两个写死服务器的快捷按钮合并为一个连接操作。当前通信层仍要求非 None 策略、SignAndEncrypt 及匹配的身份类型。

### 点位自动发现、选择与保存

1. 认证后自动打开 **点位浏览与选择**，也可从 **点位 → 浏览并选择点位…** 打开。首次仅加载“连接性”设备树，右侧等待选择设备，不扫描全服务器。
2. 左侧展开通道并选择设备/标签组，右侧自动列出其直接配置的标签；布局与 KEP 配置窗口相近。服务端新增设备后点击 **刷新设备**，新增标签后点击 **刷新标签**。默认隐藏 KEP 的内部服务、系统/统计分支及动态地址模板，不读取变量值或写入服务器。
3. 右侧勾选需要纳入点表的可读变量，点击 **保存勾选点位**。也可“勾选可读点”；扫描不自动将所有发现点纳入业务采集。
4. 保存目标为当前连接配置中 `nodes.file` 对应的点表。已有点按 namespace URI + 标识符类型/值识别，保留别名、写授权、范围及其他原始属性；新点生成唯一英文代称，默认 `writable=false`，服务端可写属性仅记录为元数据。
5. 原点表修改前自动生成同目录 `catalog-backup-*.bak`，使用临时文件及原子替换；同名变量可有不同节点，重复扫描/保存不会重复追加。检测到点表被外部修改会拒绝覆盖，点击“重载点表”后重新选择保存。
6. 表格只显示选择、标记名称、数据类型、配置代称；悬停可查看节点标识。子标签组在左侧继续展开，不把其他设备或组的标签混入当前列表。权限、数量限额和超时导致结果不完整时仍明确提示，关闭窗口会先等待当前请求清理。

可在连接配置中调整 `browse.maxReferences`（默认 500）和 `browse.timeoutMs`（默认 60000）。设备视图只读取所选分支的直接标签；通用通信层仍保留完整有界浏览接口。KEP 内部视图过滤依据其保留的下划线标识，不应用于其他厂商的自定义命名空间。未知/数组类型可以只读保存，不自动启用写入。业务意义、采样周期及计算规则仍由项目配置决定。

本功能不添加定时采集、近期数组、Excel 存储或工艺自动控制。

## 目录与环境

```text
src/main/java/cn/buct/ua/    Java 通信、命令行与桌面连接界面源码
src/test/java/cn/buct/ua/    写入门禁、类型与配置测试
config/templates/          可提交的配置模板，不含密码
config/local/              本机连接配置、点表与 KEP 演示工程，不提交
docs/                      本机演示说明与截图，不提交
pki/                       各人自己的证书和私钥，不提交
.cache/                    Maven 依赖、发行包及临时文件，不提交
target/                    构建产物，不提交
dist/                      可解压演示包，不提交
start-client.cmd           Windows 双击桌面入口
start-client.sh            Linux 桌面启动入口
package-client.ps1         从已构建 JAR 生成纯净演示包
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

## 2. 准备连接配置

`config/local/` 整个目录不提交。桌面首次运行会提示从模板创建本机配置；已有文件保持不变。通过“服务器 → 连接配置…”修改服务器地址、策略和用户名，保存会写回同一份本地文件；点位通过浏览选择后保存。客户端身份由各人在本机生成，密码在运行时输入，写入默认关闭。命令行使用者也可手动按下面方式复制模板。

```powershell
New-Item -ItemType Directory -Path config/local -Force
foreach ($name in 'client.properties', 'nodes.properties') {
    $target = Join-Path config/local $name
    if (-not (Test-Path -LiteralPath $target)) {
        Copy-Item -LiteralPath (Join-Path config/templates $name) -Destination $target
    }
}
```

上述复制会保留已有个人配置。

```powershell
.\run.ps1 检查配置
.\run.ps1 发现
```

可以在运行参数中指定另一份连接配置；`config/local/` 中的配置都被忽略。当前每份连接配置只保存一个使用中的用户名，UI 修改后下次连接生效。KEPServerEX 用户组及其权限由服务器管理，新增组本身不要求新增客户端文件；更换登录账号时在连接窗口改用户名并输入该账号密码即可。目前没有多账号配置列表或自动同步服务器用户组的功能。不要将密码写入配置文件。

这些命令不需要业务账号密码，也不写业务点位。发现成功只代表服务器返回了端点，不代表认证会话和业务读写通过。

模板匹配开发现场之前发现的 `Basic256 + SignAndEncrypt + username`。每个人都应先执行“发现”，再按自己服务器的实际策略配置；程序不会自动切换到 None，服务器信任方式由 `security.serverTrust` 明确选择。旧策略仅作为当前本机环境的显式兼容配置，生产应以甲方提供的安全配置为准。

所有配置按 UTF-8 读取。配置中的相对路径以配置文件所在目录为基准，避免 IDEA 工作目录变化导致点表丢失。

运行参数统一保存在 `config/`：`config/templates/` 提供模板，`config/local/client.properties` 保存本机连接参数，`config/local/nodes.properties` 保存点位。桌面连接表单读写当前连接配置，点位窗口读写其 `nodes.file` 指定的点表；不另维护演示连接配置。默认桌面入口使用上述本机文件，也允许在启动参数中显式指定另一份配置。查询、连接和浏览还会与服务器通信，不只是操作本地文件。

## 3. 初始化客户端证书

桌面操作：**证书 → 初始化客户端证书…**。没有身份时在窗口设置证书库密码并生成；已经存在时提示复用，不重新生成。首次点击连接而缺少身份时也会提示进入初始化。**导出客户端公开证书…** 可从已有私钥身份重新导出 `client.der`；**打开证书目录** 便于在 KEP 中选择该文件。

把 `client.der` 导入 KEP 的“受信任的客户端”仍由服务器管理工具完成。Java 不会创建 KEP 用户组、修改服务器权限或自动批准自身证书。strict 模式还可通过 **证书 → 导入服务器公开证书…** 选择文件并输入独立核对的指纹；当前本机 first-use 模式无需此项。

命令行入口仍保留：

```powershell
.\run.ps1 初始化证书
```

终端会隐藏输入证书库密码。它与 KEPServerEX 用户密码是两种不同的密码。已经生成的身份不会被自动覆盖，也不会每次启动重新生成。

- `pki/client.p12`：含私钥，只留本机。
- `pki/client.der`：可导入 KEPServerEX 的 OPC UA 配置管理器 → 受信任客户端。
- `application.uri` 必须与证书的 URI 一致。多人开发时，各人配置一个独立 URI 并各自生成身份，不共享私钥。

PKI 是生成或保存的身份材料，其位置由 `config/local/client.properties` 的 `pki.directory` 指定。执行“初始化证书”时，项目的 `Main → LocalPki.initialize` 调用 Milo 生成客户端密钥和自签名应用证书，并写入 client.p12、client.der；正常连接调用 `LocalPki.load` 读取已有身份。`server-pins/` 则由 `ServerTrust` 保存服务器提供的公开证书，用来核对后续连接，并非由客户端签发服务器证书。该目录不是另一套连接参数文件，不需要随切换账号重新生成。

`pki.file`、`pki.alias` 可选择 PKI 目录内已有的 PKCS12/PFX 私钥身份；生成时的名称、组织、DNS/IP SAN、有效天数由 `pki.*` 配置。模板中的 localhost/回环地址仅用于本机示例，部署时按实际环境填写；已有证书不会因修改配置而自动更新。`client.der` 是公开导出文件名，不能用作私钥库文件名。

在终端中运行时，程序通过控制台隐藏输入；在 IDEA 中没有交互式控制台时，会弹出本机密码输入框。操作者自行输入并确认，密码只在当前进程中使用。也可从本机环境变量 `UA_KEYSTORE_PASSWORD` 和 `UA_PASSWORD` 获取密码；禁止将密码写入共享运行配置、配置模板、截图、提交或日志。

项目的 `.run/` 提供 IDEA 运行入口：配置检查、端点发现、初始化客户端证书、浏览节点、读取点位、浏览模拟点，以及模拟写入42并回读。它们不保存密码，使用项目 JDK、相对路径和 Maven 模块依赖。写入前必须核对当前配置、目标别名和写入门禁；入口存在不等于已经允许写入。UI 联调步骤与前置检查见 [UI_TEST.md](UI_TEST.md)。

## 4. 服务器证书信任

当前模板和本机演示配置使用：

```properties
security.serverTrust=first-use
```

运行浏览或读取时，程序接受选定端点首次提供的应用证书，通过有效期、主机名、ApplicationUri 等 Milo 校验后，将公开证书按端点记录到 `pki/server-pins/`。以后同一端点必须使用相同证书，发生变化时停止连接，不自动覆盖。只运行“发现”不会保存记录。无需先手动导出、导入服务器证书；KEPServerEX 仍须信任 Java 的 `client.der`，用户登录也保持不变。

这是首次使用信任（TOFU），首次服务器来源由操作者确认的地址及网络环境保证，程序不能独立证明首次接收到的证书属于预期服务器。它不是取消证书验证。证书记录在用户密码验证成功之前可能已经保存，不表示登录或读写通过。

需要预先人工确认服务器身份时，设置 `security.serverTrust=strict`，再按下面方式导入。旧配置未声明该字段时仍默认为 strict；已有人工信任记录和首次记录互相独立，切换模式不会自动迁移信任。

从 KEPServerEX 配置管理器导出服务器公开证书，在服务器证书界面独立核对 SHA-256 指纹，再执行：

```powershell
.\run.ps1 信任服务器 config\local\client.properties 'pki/server-export.der' '<SHA256>'
```

strict 模式下，先将实际服务器公开证书导出到示例位置，或替换为自己的文件路径；将 `<SHA256>` 替换为独立核对过的 64 位十六进制指纹。程序会比较指纹，不符则拒绝导入。仓库不附带本机服务器证书。

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
3. 桌面用户在点位窗口勾选并保存即可；命令行用户可按模板填写当前配置中 `nodes.file` 对应的文件。
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

Git 根目录仅为本项目，不包含上级“裂解炉2”、安装包或甲方生产资料。公开仓库提交源码、配置模板和根目录通用说明；`config/local/`、`docs/`、密码、证书私钥、Maven 缓存和构建产物不提交。

首次关联远程仓库并推送（只需执行一次）：

```powershell
git remote add origin https://github.com/adasfand/UA-Java-Client.git
git push -u origin main
```

后续使用 `git pull --ff-only` 获取更新，提交后执行 `git push`。`config/local/` 和 `docs/` 整个目录由 `.gitignore` 排除；团队共享默认值修改 `config/templates/`。已被跟踪的文件需要停止跟踪后忽略规则才会对其生效。

个人仓库通过 Settings → Collaborators 邀请同学/开发者，对方接受后可以推送修改；公开可见本身不代表所有访客都能直接改仓库。建议各人创建功能分支，通过 Pull Request 合并。

## 当前验收边界

具体构建和本机运行结果记录在 `VERIFICATION.md`。本机已通过 UI 登录、选点保存、读取、模拟写入回读和独立服务器查看器核对。第一部分桌面演示具备完整操作入口；生产点表、安全策略、跨电脑网络及国产系统实机兼容性仍需现场验收。KEP 用户和设备仍在服务器管理工具中创建。

主要参考：

- [Eclipse Milo v1.1.6](https://github.com/eclipse-milo/milo/tree/v1.1.6)
- [厂商 UA 端点说明](https://support.ptc.com/help/kepware/features/en/kepware/features/OPCUACONFIGMNGR/server-endpoints.html)
- [GitHub 个人仓库协作者权限](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/permission-levels-for-a-personal-account-repository)
