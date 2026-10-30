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

已配置账号和凭据环境变量时，可简写 `ua.connect(serverAddress)`。传入非空地址时，连接和发现均使用本次地址，避免旧配置中的本机发现地址误用于另一服务器；serverAddress传null/空时使用文件中完整的连接和独立发现配置。

UA使用endpoint URL，旧C#的`Connect(IP, ProgID)`是OPC DA接口，不能原样套用。服务器地址中本来就包括主机/IP和端口，UA不需要再查询本地电脑名称。

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
ns=2;s=DemoChannel.DemoDevice.sim
nsu=KEPServerEX;s=DemoChannel.DemoDevice.sim
```

第一项是标准服务器时间，可用于首次读测试。后两项只是本机模拟点示例，现场必须给出实际地址。`nsu`按命名空间URI解析当前索引，避免不同服务器固定假定ns=2；`ns`使用现场给出的当前索引。也支持标准GUID/ByteString标识符。

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
    String address = "nsu=KEPServerEX;s=DemoChannel.DemoDevice.sim";
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

服务器侧通过KEP配置端点/安全策略、账号权限、应用证书及信任。代码不创建KEP用户、设备或标签。代码与部署参数均已外置，生产点表和目标国产系统仍需要现场实测。
