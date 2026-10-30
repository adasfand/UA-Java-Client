# 当前代码调用流程

桌面入口：`Main.main(无参数)` → `DesktopMain.main` → `ServerWindow`。桌面只收集参数和显示结果，网络操作放在SwingWorker后台线程，所有连接读写调用公开的`UaClient`。

## 连接

`UaClient.connect(address, username, userPassword, keystorePassword)`：

1. `ClientSettings.load(configFile)`读取安全参数。`DesktopEndpoint`解析完整endpoint或将IP/主机替换到配置端口与路径；传入新地址时，同步更新本次内存中的发现地址，不沿用另一服务器的旧发现地址。
2. 创建`UaConnection`，传入无需窗口的凭据提供器。临时口令副本最终清零，调用者数组保持不变。
3. `LocalPki.load`打开已有客户端身份；`UaServices.discover`在内部查询endpoint，`ClientSettings.selectEndpoint`按策略、模式及身份类型选择。不要求操作者先点击查询。
4. `ServerTrust.create`选择strict或first-use证书校验；创建Milo客户端配置和用户身份，`client.connectAsync()`完成认证会话。
5. 成功后Facade持有同一连接。UI仅将实际地址、发现地址和用户名写回当前配置；连接成功但保存失败会分别提示。

首次缺少身份时，`UaClient.initializeCertificate`调用原有`LocalPki.initialize`生成身份。此辅助过程不建立会话、不自动批准服务器信任；UI提示部署人员完成信任后再次连接。

## 读取

`UaClient.read(address)` → `readData(address)` → `UaConnection.readAddress(address)`：

1. 检查已有连接。
2. `UaAddress`复用Milo ExpandedNodeId解析地址，按当前NamespaceTable解析nsu；不猜测旧DA ItemId。
3. Milo `readValuesAsync(maxAge=0, TimestampsToReturn.Both, ...)`读取一次Value。
4. Facade检查质量Good，再返回原始Java/Milo值；质量异常抛出UaException，不向上层返回假0或旧值。`readData`保留质量和时间供UI使用。

旧C#的`OPC_DS_CACHE`是DA服务器缓存，不是Java Data字典。这里没有新增应用层缓存、计时器或历史数组。

## 写入

`UaClient.write(address, value)` → `UaConnection.writeAddress` → `ScalarValue.convert` → `writeOnce`：

1. 检查连接，解析现场地址。
2. 读取DataType、ValueRank、UserAccessLevel，核对基本标量和当前账号写权限。
3. 根据服务端实际类型将Number/Boolean/String转换成Milo正确Variant类型，检查整数精确转换、数值范围和浮点有限性。
4. 使用当前已激活会话发送一个WriteRequest，核对唯一逐点状态；超时取消原始future并请求关闭，不自动重放。
5. 返回StatusCode。新接口没有写前读Value及强制回读，能用于仅写Value或动态点；Good只表示服务器接受写服务。上层需要核对值时另调read。

旧`writeValue(PointDefinition, ...)`仍供原模拟CLI使用，其本机/点表/范围和精确回读约束不影响新Facade。

## 主要文件

| 文件 | 职责 |
| --- | --- |
| UaClient.java | 公开connect/read/write、会话状态和close |
| UaAddress.java | NodeId解析及命名空间URI映射 |
| ScalarValue.java | 服务端类型到Java/Milo写入值的转换 |
| UaConnection.java | 原有Milo会话、浏览及新的按地址读写服务 |
| ClientSettings.java | 安全配置读取及校验 |
| LocalPki.java、PkiSettings.java、ServerTrust.java | 本机身份生成/加载与服务器证书校验 |
| ServerWindow.java、DesktopMain.java | 最简中文桌面与入口 |
| ClientConfiguration.java | UI地址/用户名安全保存，保留注释、相对路径和外部修改检查 |

参数类型、返回值、异常和示例详见 [API_GUIDE.md](API_GUIDE.md)。客户端主动连接KEPServerEX，同一会话中请求/结果双向通信；Java不提供服务器监听或反向登录接口。KEP端点、账号权限、通道及标签在KEP管理工具配置，本仓库不包含KEP服务器源码。
