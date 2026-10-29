# 代码结构与适用边界

本项目使用 Java 21、Eclipse Milo 1.1.6，提供命令行及 Swing 桌面连接入口，共用通信层。KEPServerEX 是服务器，Simulator 在服务器内部产生测试数据。

## 源码文件怎样分工

| 文件 | 职责 | 与其他模块的关系 |
| --- | --- | --- |
| `Main.java` | 解析命令、调用操作、显示错误及退出码 | 命令行适配层，调用配置、PKI、连接层 |
| `ClientSettings.java` | 读取并校验连接配置、解析相对路径、选择端点、写入门禁 | 统一保存一次连接所用的配置 |
| `PkiSettings.java` | 校验证书文件名、别名、DNS/IP SAN、组织及有效期 | 由 ClientSettings 加载，LocalPki 使用 |
| `LocalPki.java` | 生成/加载 PKCS12 身份、导出公开证书、核对指纹并导入信任 | 不生成模拟值，不执行业务读写 |
| `ServerTrust.java` | 选择人工预置信任或首次自动记录，并校验同端点证书一致性 | 不改变账号权限和写入门禁 |
| `PointDefinition.java` | 描述一个点，构造 NodeId，按 UA 类型解析写值并校验量程 | 点位别名与服务器真实地址之间的映射 |
| `UaServices.java` | 有期限的端点发现、逐页浏览、状态检查及 continuation point 清理 | 直接复用 Milo 的服务及传输 API |
| `UaConnection.java` | 建立认证会话，浏览、读取、受控写入及关闭 | 将配置、PKI、点表和 Milo SDK 串起来 |
| `DesktopMain.java` | 桌面启动、加载配置及本机外观 | IDEA“服务器客户端”入口 |
| `ServerWindow.java` | 服务器菜单、会话结果、断开及退出 | 持有认证会话，退出时释放资源 |
| `ServerConnectDialog.java` | 端点列表、地址、用户名、查询和连接 | 后台调用 UaServices.discover / UaConnection |
| `DesktopEndpoint.java` | 表单地址与所选端点转为一次连接配置 | 保留原 PKI，表单不写回磁盘，不开启写入 |
| `DesktopCredentials.java` | 完整认证成功后缓存本次运行的凭据 | 隔离服务器、用户及私钥库；断开保留，清除/退出时清零 |
| `PointBrowserDialog.java` | 设备树、扫描、勾选与保存的桌面入口 | 后台调用同一个已认证 UaConnection |
| `PointDiscovery.java` | 有界遍历、循环去重、停止与不完整结果提示 | 复用 UaServices 浏览及 UaConnection 属性读取 |
| `DeviceTagView.java` | 按设备显示直接标签，隐藏 KEP 内部节点 | 不修改通用浏览能力及其他厂商节点 |
| `DiscoveredPoint.java` | 服务器变量元数据及稳定节点身份 | 以 namespace URI 标识，不依赖易变的 namespace index |
| `PointCatalog.java` | 增量合并已选择点位、备份和原子保存 | 保留已有业务字段，检测外部修改，新点默认只读 |

`src/test/java` 是离线边界测试；`config/templates` 是公共模板；`config/local` 共享不含口令的本机模拟配置；`pki` 保存各人的身份及信任，不进入仓库；`pom.xml` 固定依赖并打包可运行 JAR。

## 桌面连接的调用顺序

`DesktopMain.main` → `ClientSettings.load` → `ServerWindow` → “服务器 / 连接” → `ServerConnectDialog`。

- “查询”：后台调用 `UaServices.discover`，将指定发现地址返回的端点显示到列表。
- “连接”：`DesktopEndpoint.connection` 生成本次配置，`DesktopCredentials.begin` 开始凭据尝试，再由后台调用 `new UaConnection(settings, attempt)`；成功后提交本次缓存。原身份加载、端点匹配、证书校验与用户认证继续执行。
- 成功后：主窗口持有 `UaConnection`，连接对话框关闭。只有构造函数完成认证后才显示成功。
- “断开”：后台调用 `UaConnection.close`，凭据缓存保留；退出时清除缓存并调用 `Stack.releaseSharedResources`。表单操作不写回连接配置，不调用点位值读写函数。
- “点位”：`browseNodes` 为设备树加载节点，`discoverPoints → PointDiscovery.scan → describePoint` 获取变量属性；`PointCatalog.save` 将勾选结果增量合并至配置指定点表。

当前桌面范围是服务器连接、点位发现与选择保存；原有命令行读写能力继续保留。这里使用 Swing 和 Milo，不依赖原 C# DLL 或 Windows COM。具体国产系统及 CPU 架构尚未提供，跨系统运行尚待实机验证。

## 一次读取的调用顺序

以 IDEA“05 读取点位”为例，它最终执行 Main 的“读取”命令：

1. `Main` 调用 `ClientSettings.load`，读取服务器地址、策略、身份方式、PKI 位置和点表路径。路径以配置文件目录为基准；公开构造器与文件入口都校验安全不变量。
2. `ClientSettings.points` 读取点表，创建 `PointDefinition`。程序中的 `testRegister` 是逻辑别名，服务器地址来自它的 namespace URI 与 identifier。
3. `UaConnection` 加载现有客户端身份及信任库；证书库密码默认用终端隐藏输入或本机密码框取得，也可以注入凭据提供器。
4. `UaServices.discover` 通过 `endpoint.discoveryUrl` 发现端点；留空时使用 `endpoint.url`。发现阶段不发送用户密码。
5. `selectEndpoint` 明确匹配配置的连接地址、安全策略、模式及用户身份，不自动改写地址或降级。
6. `ServerTrust` 选择服务器证书信任方式，取得用户凭据，创建 Milo 客户端，校验服务端证书、ApplicationUri、主机名与会话端点，并建立认证会话。只有此步完成才显示“已建立认证会话”。
7. 根据服务器实际 NamespaceArray，把点表的 namespace URI 解析为当前 namespace index，再构造 NodeId。
8. `readValues` 调用 Milo，返回 `ReadResult(alias, nodeId, DataValue)`；其中 DataValue 保留原始类型、质量和时间戳。
9. CLI 的 `read` 负责打印结果，并在任一点非 Good 时失败。桌面 UI 可调用 `readValues`，展示成功值及失败点的质量。
10. 操作结束后关闭会话、传输和信任库监听，并清零本连接保存的用户密码字节。

发现返回端点只是服务器可访问；认证会话成功才代表连接建立。读到 Good 进一步证明点位访问正常。

## 证书为什么单独管理

首次生成身份时，LocalPki 使用 Milo 生成 RSA 2048 密钥及 SHA-256 自签名应用证书。Application URI 来自应用配置，SAN、有效期等来自 PkiSettings；不会每次启动重新生成。

默认身份文件 `client.p12` 含私钥，公开证书 `client.der` 用于交给服务器信任。现有 PKCS12/PFX 也可放入配置的 PKI 目录，通过 `pki.file` 与 `pki.alias` 选择；该别名必须包含私钥，证书 Application URI 必须与应用配置一致，当前假定私钥口令与证书库口令一致。

发现信息可能包含证书链，指纹统一计算首张应用证书的 DER，避免“整段证书链摘要”与“单张证书摘要”混淆。`security.serverTrust=strict` 要求人工核对公开指纹后导入；未配置该选项时也沿用此行为。

模板及本机演示使用 `security.serverTrust=first-use`，省去客户端手工导入服务器证书：只接受所选端点的证书，通过 Milo 属性校验后按端点保存至 `pki/server-pins/`，以后连接核对同一证书。记录使用进程间锁和原子写入；锁冲突失败返回，不无限等待。同一端点换证会拒绝连接，已有人工信任库中的其他证书也不能绕过这个约束。

首次使用信任不能独立确认首次服务器的真实来源。记录可能在用户认证完成前保存；它不代表登录成功。首次记录与人工信任库互相独立，不会修改原人工信任库、客户端身份或 KEP 服务器身份。

更换配置中的 SAN 不会改动已经存在的证书。证书到期或更换身份时，需要重新执行部署和信任流程。

## 泛化已经覆盖什么

| 变化情况 | 当前处理 |
| --- | --- |
| 换 IP、端口或连接路径 | 修改 `endpoint.url` |
| 发现服务在另一个地址或 `/discovery` | 修改 `endpoint.discoveryUrl`，连接目标仍明确配置 |
| 换服务器安全策略 | 修改策略配置，必须是服务器实际提供的策略 |
| 换用户名或采用匿名身份 | 修改 `identity.type` 和用户名；是否可用由端点决定 |
| 换证书文件/别名、生成不同 SAN | 修改 `pki.*`；已有身份仍执行有效期和 URI 校验 |
| 点名不同、namespace index 改变 | 修改点表；运行时按 URI 解析 index |
| 数字、字符串、GUID、ByteString NodeId | 支持 `i`、`s`、`g`、`b`；`b` 使用 Base64 |
| 服务器返回浏览分页 | 逐页检查状态，设置页大小及引用预算，失败或超限释放已知 continuation point |
| 无效节点或无权限 | 不再把非 Good 浏览状态当作“空节点成功” |
| 后续接桌面 UI | 注入凭据提供器，使用结构化读取结果，从后台线程调用 |

每台服务器可使用不同的配置文件、点表及 PKI 目录。连接层没有依赖具体 KEP 通道名；本机的中文通道和点名只出现在被 Git 忽略的本机点表中。

## 当前仍保留的明确边界

- 仅支持 `opc.tcp`、`SignAndEncrypt`，用户身份为 username/anonymous。没有实现 X509 用户身份、OAuth、HTTPS/WebSocket 等其他组合；应用证书与用户证书是不同概念。
- 不自动处理 NAT 或把服务器返回的内网地址改成外网地址。若服务器广告地址不可达，需要显式配置或由服务器管理员修正；不会因此关闭证书检查。
- 命令行是单次操作；桌面主窗口、定期采集、订阅、连接状态展示和应用层恢复策略尚未实现。Milo 自身已有保活和会话恢复机制，本项目不会再启动另一套竞争的重连循环。
- 同一连接按顺序使用，不保证多线程并发读写和关闭安全。UI 应将其交给后台工作线程，不能在界面线程中同步连接；也不能调用会执行 `System.exit` 的 `Main.main`。
- 写入仅对本机 simulation、显式白名单、量程与确认参数开放，当前只支持声明的标量类型。尚未将其推广为生产控制接口，也不支持通用数组/结构体写入。
- 写入只使用当前已有会话，不等待新会话；传输等待超时会取消持有的原始 future 并请求关闭。已经发出的写请求仍可能结果未知，不自动重试；写 Good 后回读失败会明确说明“已接受但当前值未确认”。
- 读取、属性查询和逐页浏览都有等待期限。发现及分页调用还会为清理最多额外等待 5 秒；多个阶段/节点串行执行时，不能把单阶段超时理解为整个任务的总时长。
- 当前尚未通过国产 OS/CPU 真机验收、多厂商服务器矩阵、长期断网恢复和生产点表验证。支持配置变化不等于这些场景都已实测。

## 测试应怎样看

原有 7 项测试主要验证写入门禁、数据类型和范围。泛化补充覆盖配置不变量、端点选择、GUID/ByteString、PKCS12 参数、密码数组清零、证书链指纹、浏览分页、错误状态和超时清理。首次信任补充 8 项测试，覆盖首次记录与复用、strict 隔离、主机名/URI 错误、证书变化、发现与会话证书一致性、端点隔离和配置错误，当前共 28 项。

离线测试不代替真实服务器认证。本轮已通过本机真实认证、三个结构化读值、根节点浏览、错误节点检查及一次模拟写入回读；凭据由操作者在独立窗口输入。完整记录见 `VERIFICATION.md`。这仍不等于远程网络、多厂商、生产控制或国产系统均已验收。
