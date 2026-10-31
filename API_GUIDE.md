# 连接、读取、写入接口说明

公开入口：`cn.buct.ua.UaClient`。Java 21，复用 Eclipse Milo 1.1.6；一个实例持有一个已认证会话，所有读取和写入都复用该会话。函数不打开窗口，不依赖点表，也不需要先浏览或在UI添加地址。

## 1. 连接

```java
UaClient ua = new UaClient(Path.of("config/local/client.properties"));
ua.connect(serverAddress, username, userPassword, keystorePassword);
```

| 参数 | Java类型 | 作用 |
| --- | --- | --- |
| configFile | Path，构造器参数 | 安全配置文件，仅初始化客户端时指定一次 |
| serverAddress | String | 完整 `opc.tcp://主机:端口/路径`，也接受IP/主机名；只传IP时沿用配置中的端口和路径 |
| username | String | 服务器账号；null时使用配置中的用户名；anonymous身份不使用账号密码 |
| userPassword | char[] | 服务器账号密码；null/空数组时从identity.passwordEnv指定的环境变量取值 |
| keystorePassword | char[] | 客户端私钥库口令；null/空数组时从pki.passwordEnv指定的环境变量取值 |

返回void；连接失败抛异常，不能继续读写。口令参数在内部复制，调用者仍负责清零自己的数组。不会把密码保存到文件。安全策略、模式、Application URI、证书文件/别名、信任方式和超时从配置加载，不需要每次读写传入。

`isConnected()`表示连接对象已经建立且尚未主动关闭，不是实时网络探测；网络故障由实际请求及SDK会话恢复结果体现。

已配置账号和凭据环境变量时，可简写 `ua.connect(serverAddress)`。传入非空地址时，连接和发现均使用本次地址，避免旧配置中的本机发现地址误用于另一服务器；serverAddress和remoteHost均传null/空时使用文件中完整的连接和独立发现配置。

推荐使用上面的四参数接口；安全配置部署完毕、凭据通过环境变量准备时也可只传serverAddress。为兼容已有调用方，仍保留`connect(serverAddress, remoteHost)`和五参数重载；remoteHost为String，用于替换URL的目标服务器主机，不是客户端绑定IP。只要传入地址或远程主机，发现与连接都会使用合成后的最终URL；两者都未提供时，保留文件中的独立发现配置。

桌面通过服务器菜单打开连接小窗口，提供查询、连接、取消、一键连接、实际连接五个按钮。输入服务器URL、远程IP、账号及两类密码，三个连接按钮根据地址来源选择目标，统一调用`UaClient.connect`，不再弹出额外密码框。成功后保存最终endpoint URL。Java代码不固定现场IP；改变目标IP不会跳过端点匹配和证书主机名/IP校验。

UA使用endpoint URL，旧C#的`Connect(IP, ProgID)`是OPC DA接口，不能原样套用。服务器地址中本来就包括主机/IP和端口，UA不需要再查询本地电脑名称。

旧工程`ServerConnect.cs`的查询先通过DNS取得目标主机名并列出DA服务器；普通连接使用该主机及选中的ProgID；“一键连接”使用已查到的HostName和固定ProgID；“实际连接”则固定IP和ProgID。之后调用`Srv.GetStatus`，主程序创建`SyncIOGroup(Srv)`供读写。Java按UA协议适配：查询GetEndpoints；普通连接用输入；一键连接用预设URL和当前远程IP；实际连接用完整现场URL；取消仅关闭窗口。IP和URL均来自输入或配置，三个入口共用认证会话，不复制旧DA硬编码值，也不要求先做DNS查询。

快捷预设键为`connection.quickUrl`（留空用`endpoint.url`）和`connection.actualUrl`（无默认现场地址，必须显式提供完整URL）。`ClientSettings.connectionTarget`统一选择目标，保持安全策略和证书配置不变；查询复用`UaConnection.discover`，不加载私钥、不认证账号、不固定服务器证书。窗口只列出不同的URL，连接时仍核对策略、模式、身份类型和证书。

只提供IP时，仍须事先知道服务器的端口和可选路径，不能从IP自动推断。输入地址应与服务器发布的endpoint和证书主机名/IP SAN相符；账号和安全策略也必须与服务器实际配置匹配。

## 2. 读取

```java
Object value = ua.read(address);
double number = ((Number) value).doubleValue(); // 已确认是数值点时
```

唯一参数address为String，必须是现场确认的OPC UA NodeId。返回服务器Value的原始Java/Milo值，例如Integer、Float、Double、Boolean、String或无符号Number包装。不能把所有返回值强转为Double。合法的空值可能返回null；结果质量不是Good则抛异常，不能把失败当作0或旧值参与计算。

上层需要质量和时间戳时使用 `DataValue data = ua.readData(address)`。默认以maxAge=0读取Value，要求服务器提供最新可用值，但并不保证绕过KEP自己的采集周期；没有新增Java应用内存缓存。

旧C#使用`OPC_DS_CACHE`读取DA服务器缓存，再将结果写入Data字典。它不是让Java必须从自己的数组或内存缓存取数。上层计算、定时调用和数据保存由调用方负责。

## 3. 写入

```java
StatusCode status = ua.write(address, 35);      // int参数
StatusCode status2 = ua.write(floatAddress, 12.5f); // float参数
```

| 参数 | Java类型 | 作用 |
| --- | --- | --- |
| address | String | 服务器已有的目标UA NodeId |
| value | Object | Java Number、Boolean、String；界面数值文本也可传入 |

函数读取服务端DataType/ValueRank/当前用户权限，将值转换为对应类型，写一次后返回服务端StatusCode。支持Boolean、SByte、Byte、Int16、UInt16、Int32、UInt32、Int64、UInt64、Float、Double和String标量。整数小数、溢出、NaN/Infinity和非零浮点下溢被拒绝；不支持数组、结构体或自定义类型。String保留原文本空格。

当前通用接口不要求点表别名、模拟环境开关、本机地址、逐点范围或先读后写；服务器必须允许该账号写入。它也不强制回读，因此写Only点和会被工艺逻辑改变的动态点不因无法读取或回读不同而被误判。

Good表示服务器接受了写服务，不保证数值永远不变或现场物理动作已完成。超时会请求断开且不自动重放，结果可能未知，应由上层核对。确需读回时明确再调用read(address)。

## 4. 地址怎么传

```text
ns=0;i=2258
ns=2;s=WriteDemo.Device1.sim
nsu=KEPServerEX;s=WriteDemo.Device1.sim
```

第一项是标准服务器时间，可用于首次读测试。后两项只是本机模拟点示例，现场必须给出实际地址。`nsu`按命名空间URI解析当前索引，避免不同服务器固定假定ns=2；`ns`使用现场给出的当前索引。也支持标准GUID/ByteString标识符。

桌面把地址拆成两栏：“命名空间”填`nsu=KEPServerEX`或现场给出的`ns=数字`；“节点标识”填`s=WriteDemo.Device1.sim`，数字标识则填`i=数字`。程序用分号拼接，校验后传给同一套读写接口；公开API仍只接收完整address字符串，不增加调用参数。

地址由现场/服务器管理员提供，或使用UA客户端浏览节点取得。示例的`KEPServerEX`为命名空间URI，`WriteDemo.Device1.sim`为节点标识，对应KEP通道、设备、标签。服务器endpoint URL只用于连接，KEP标签属性中的`K0001`只用于驱动访问模拟设备，这两者均不能直接替代读写NodeId。

旧DA ItemId如`A151TT_06102A/ALM1/PV.CV`不能直接认定为UA地址。应向现场索取它对应的namespace URI/index与identifier，不自动补上`ns=2;s=`。不需要在Java UI新建服务器点位。

## 5. 完整调用示例

```java
import cn.buct.ua.UaClient;
import java.nio.file.Path;
import java.util.Arrays;

char[] accountPassword = /* 从本机UI或凭据来源取得 */ new char[0];
char[] certificatePassword = /* 从本机UI或凭据来源取得 */ new char[0];
try (UaClient ua = new UaClient(Path.of("config/local/client.properties"))) {
    ua.connect("opc.tcp://127.0.0.1:49320", "现场账号",
            accountPassword, certificatePassword);
    String address = "nsu=KEPServerEX;s=WriteDemo.Device1.sim";
    double current = ((Number) ua.read(address)).doubleValue();
    System.out.println(current);
    // 仅对已确认可写的目标执行；业务地址可在调用方常量或配置中提供。
    ua.write(address, 35);
} finally {
    Arrays.fill(accountPassword, '\0');
    Arrays.fill(certificatePassword, '\0');
}
```

以上密码数组为空时使用环境变量；没有环境变量就会明确失败，不会弹窗。业务程序按自身方式提供实际数组。函数为阻塞调用，桌面在SwingWorker后台线程中调用，不放到事件线程。

## 6. 一次性部署参数

文件：`config/local/client.properties`，模板在`config/templates/client.properties`；相对路径以配置文件目录为基准。

| 配置 | 用途 |
| --- | --- |
| endpoint.url / endpoint.discoveryUrl | 默认UA服务地址和可选独立发现地址 |
| security.policy / security.mode | 与服务器端点匹配；目前支持明确的非None策略及SignAndEncrypt |
| security.serverTrust | strict预置信任或first-use首次固定服务器证书 |
| identity.type / identity.username | username或anonymous及默认账号 |
| identity.passwordEnv | 账号密码的本机环境变量名，不是密码明文 |
| application.uri / application.name | Java应用身份，URI必须与证书一致 |
| pki.directory / pki.file / pki.alias | 本机私钥库目录、文件名和私钥别名 |
| pki.passwordEnv | 私钥库口令的环境变量名 |
| request.timeoutMs | 请求超时 |
| pki.organization / pki.dnsNames / pki.ipAddresses / pki.validityDays | 首次生成证书的参数 |

已有有效身份直接加载。没有身份时可调用`ua.initializeCertificate(keystorePassword)`，返回公开证书路径；把client.der交给KEP的“受信任的客户端”导入后再连接。该辅助函数不会连接服务器或自动批准信任。client.p12含私钥，分别留在各自电脑。

服务器侧通过KEP配置端点/安全策略、账号权限、应用证书及信任。代码不创建KEP用户、设备或标签。现场地址、安全参数和目标国产系统仍需要实测。

## 7. 本项目的函数调用过程

桌面入口为`Main.main(无参数)` → `Main.launchDesktop` → `ServerWindow`。连接小窗口的三个连接按钮经`ClientSettings.connectionTarget`选择地址后，统一调用四参数接口。命名空间与节点标识经`UaAddress.fromParts`组合为读写共用的完整NodeId，写入另填值。在`SwingWorker`后台线程调用同一套公开接口，结果与错误显示在页面内。

| 操作 | 调用过程 | 结果处理 |
| --- | --- | --- |
| 连接 | `UaClient.connect` → `ClientSettings.load/withConnection` → `UaConnection` | 加载安全配置及客户端身份，内部发现并匹配端点，校验服务器证书，建立认证会话；失败抛异常 |
| 读取 | `UaClient.read` → `readData` → `UaConnection.readAddress` → Milo `readValuesAsync` | 按当前命名空间解析地址，读取Value、质量和时间；公开接口确认Good后返回 |
| 写入 | `UaClient.write` → `UaConnection.writeAddress` → `ScalarValue.convert` → `writeOnce` | 读取节点类型与权限，转换目标类型，提交一次写服务并核对服务及逐点状态 |
| 断开 | `UaClient.close` → `UaConnection.close` | 清除会话引用和连接持有的凭据，释放会话、传输与信任库资源 |

连接中，`LocalPki.load`打开已有私钥库；`UaConnection.discover`执行内部端点发现；`ClientSettings.selectEndpoint`按配置的URL、安全策略、模式和身份类型选择端点；`ServerTrust.create`实施strict或first-use校验；Milo `connectAsync`建立会话。发现成功并不等于用户认证成功。

首次缺少身份时，页面显示“确认新密码”和“首次生成客户端证书”。在本机私钥库密码及确认栏设置相同的新密码，调用`UaClient.initializeCertificate` → `LocalPki.initialize`生成身份；不需要已有证书密码，不依赖目标地址或KEP账号，也不建立会话。成功后结果区显示实际文件位置及KEP信任说明。已有身份不再显示生成和确认控件，直接填原私钥库密码连接。口令保留在本次窗口的密码框内供重连使用，临时数组在请求结束后清零，退出清空输入，不保存到配置。连接成功后通过`ClientConfiguration`保存实际地址、发现地址和用户名；保存失败与会话连接结果分别提示。保存使用连接前的配置快照，外部修改时拒绝覆盖。

客户端主动连接KEPServerEX，在同一会话内双向交换读写请求与结果。Java程序不提供服务器监听或供KEP反向登录的接口。

| 文件 | 职责 |
| --- | --- |
| UaClient.java | 对上层公开connect/read/write，管理单个会话 |
| UaConnection.java | Milo端点发现、认证连接、按地址读写与资源释放 |
| UaAddress.java | NodeId解析与命名空间URI映射 |
| ScalarValue.java | 将输入转换为服务端要求的基本标量类型 |
| ClientSettings.java、ClientConfiguration.java | 配置首次准备、读取及地址合成、证书参数校验和UI保存；保留相对路径并检测外部修改 |
| LocalPki.java、ServerTrust.java | 客户端身份生成/加载与服务器信任；证书参数集中在ClientSettings内部 |
| Main.java、ServerWindow.java | 统一启动入口、部署命令及最简操作窗口 |

## 8. 部署命令与验证边界

`run.ps1`保留以下入口，配置文件路径为可选参数，缺省使用`config/local/client.properties`。直接运行JAR的部署命令时需显式提供配置路径：

```powershell
.\run.ps1 桌面
.\run.ps1 检查配置
.\run.ps1 初始化证书
.\run.ps1 信任服务器 config/local/client.properties server.cer 已独立核对的SHA256
```

`检查配置`只校验参数，不登录服务器。`初始化证书`生成客户端身份，拒绝覆盖已有身份；`信任服务器`用于strict模式，导入前须独立核对服务器公开证书指纹。内部端点发现由连接函数完成，没有要求操作者单独运行发现或浏览命令。

自动测试检查封装、配置、安全校验和类型处理逻辑；本轮未执行实际UI操作。本机UI请按[UI_TEST.md](UI_TEST.md)复测，双机通信、现场策略/地址和国产系统部署待实际验收。

## 9. 双向应用证书认证与验收

### 首次设置密码与加载已有身份

| 状态 | 用户操作 | 程序操作 |
| --- | --- | --- |
| 尚无本机客户端身份 | 现在自行设置并确认一个新密码 | 生成密钥对及客户端证书，用新密码保存client.p12，并导出client.der |
| 已有client.p12 | 输入创建该文件时设置的密码 | 本机打开私钥库，加载原密钥及证书，不重新生成身份 |

密码保护的是包含私钥的PKCS12文件，不是公开证书本身。client.der没有上述密码，不需要把client.p12或私钥库密码交给KEP。KEP用户名/密码是另外一组凭据，由KEP用户管理器维护，用于用户认证和权限判断。UI在连接窗口输入私钥库密码；首次设置时多一个确认栏，不再弹出额外口令窗口。

client.p12和client.der都属于同一个Java客户端身份；KEP服务器另有自己独立的身份和证书。双向验证涉及这两端应用身份，不能以生成了两个客户端文件作为已经完成双向认证的证据。

### 双向验证的两个方向

双向应用证书认证是两端在同一OPC UA安全连接中互相验证应用身份；仍由Java主动发起连接，不要求KEP反向连接Java。双方应维护自己的信任列表，对未获信任的对端拒绝连接。[OPC Foundation规范](https://reference.opcfoundation.org/specs/OPC-10000-4/6)

| 验证方向 | 对端公开证书 | 信任位置及职责 |
| --- | --- | --- |
| KEP验证Java客户端 | Java生成的client.der | 导入KEP“受信任的客户端”；由KEP验证客户端身份及协议签名，Java私钥留在本机 |
| Java验证KEP服务器 | KEP“实例证书 → Server”导出的服务器公开证书 | strict模式导入Java信任库；Milo验证证书链、有效性、用途、服务器主机名/IP及Application URI，并在安全协议中验证签名 |

KEP对自签名客户端证书的信任通过其“受信任的客户端”管理。[PTC官方说明](https://support.ptc.com/help/kepware/features/en/kepware/features/OPCUACONFIGMNGR/trusted-clients.html)

代码中，UaConnection加载本机密钥、证书及证书链传给Milo，同时注册ServerTrust创建的服务器证书校验器，并开启Session端点一致性校验。KEP侧验证行为由服务器实现和现场配置决定，不能仅从Java源码推断现场已经正确启用。

### 当前信任方式及严格部署

| 配置 | 初次连接的信任来源 | 后续连接 |
| --- | --- | --- |
| security.serverTrust=strict | 连接前独立核对并导入服务器公开证书，或配置受信任签发者 | 继续完整校验证书；未知身份拒绝 |
| security.serverTrust=first-use | 首次接受选定端点证书并固定，未独立确认最初身份来源 | 校验证书属性及固定记录；证书变化拒绝 |

共享模板当前为first-use，不能把这种首次信任方式描述成“双方已提前人工核准”。如果验收要求预先批准双方身份，部署配置应采用strict；程序已支持该方式，普通操作页面不编辑安全策略。

strict部署步骤：

1. Java首次设置新密码并生成本机身份，将client.der交给KEP管理员导入受信任客户端，使服务器配置生效。
2. 从KEP实例证书的Server区域导出服务器公开证书，由部署人员独立核对其SHA-256；不导出服务器私钥给Java。
3. 在config/local/client.properties设置security.serverTrust=strict，通过现有部署命令导入服务器公开证书：

   ```powershell
   .\run.ps1 信任服务器 config/local/client.properties server.der 已独立核对的SHA256
   ```

   命令只导入公开证书，核对指纹并保存到pki.directory下的trust-store/trusted/certs。KEP配置管理器中的“受信任的服务器”管理的是KEP自身作为客户端时的信任，不会替本Java进程导入证书。

4. 在普通UI的服务器地址/IP栏填目标端点，再填KEP账号、用户密码和本机私钥库密码，点击连接。私钥库密码只在本机加载身份时使用，不是发给KEP的登录密码；双方应用身份校验通过后，KEP再认证用户并决定其读写权限。

### 验收与证据边界

| 情况 | 预期结果 |
| --- | --- |
| KEP未信任Java自签名客户端证书 | 服务器拒绝建立受信任会话 |
| strict模式Java未信任服务器证书 | 客户端拒绝连接 |
| 服务器证书过期、主机名/IP不匹配或Application URI不匹配 | 校验失败，不跳过检查 |
| 双方应用身份通过，但KEP用户名/密码错误 | 用户认证失败，读写不可用 |
| 双方证书及用户认证通过 | 连接成功；读取标准服务器时间得到Good，再验证实际点位读写权限 |

现有自动测试验证了strict未知证书拒绝、导入后接受、first-use固定及换证拒绝、主机名和Application URI不匹配拒绝。KEP服务器侧拒绝、真实网络及现场策略必须通过真实联调单独验证；本轮没有执行这些操作。

汇报可表述为：项目使用双方应用证书建立OPC UA安全会话，KEP验证Java身份，Java验证KEP身份，私钥分别保留本机；用户登录和点位权限是后续独立检查。已实现两端证书的配置与Java校验逻辑，正式预置信任采用strict，真实服务器的正反向拒绝测试按验收表记录，不以仅生成证书或发现端点作为通过依据。
