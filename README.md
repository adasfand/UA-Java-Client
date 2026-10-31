# Java OPC UA 客户端

使用Java 21、Swing和Eclipse Milo 1.1.6，提供三个可供上层调用的接口：`connect(...)`、`read(address)`、`write(address, value)`。先建立认证会话，再按现场提供的UA地址读取或写入。

通过“服务器 → 连接服务器…”打开连接小窗口，保留旧版的查询、连接、取消、一键连接、实际连接五个按钮。三个连接入口共用同一个认证函数，不再弹出额外口令框。主窗口只保留读写及结果；安全策略、证书路径从配置加载。

## 阅读代码的顺序

生产源码共10个Java文件。初次阅读只需先看这3个：

| 顺序 | 文件 | 只需先弄懂的内容 |
| --- | --- | --- |
| 1 | Main.java | 启动窗口；提供部署用的配置和证书命令 |
| 2 | UaClient.java | 给上层调用的connect、read、write和close |
| 3 | ServerWindow.java | 界面收集参数，调用同一套UaClient接口 |

其余文件是内部支撑：UaConnection负责Milo通信，ClientSettings和ClientConfiguration负责配置，LocalPki和ServerTrust负责证书，UaAddress和ScalarValue负责地址及类型处理。测试源码用于回归验证；target、dist和.cache是构建产物及缓存，不是需要逐个理解的业务代码。

## 运行

Windows双击 `start-client.cmd`，或在IDEA打开`Main.java`，点击`main`方法左侧的绿色运行按钮。运行参数留空，工作目录为项目根目录；共享配置“服务器客户端”也使用同一`Main`入口。机器需要JDK21，可用JAVA_HOME或PATH选择；Java工具和依赖不绑定个人电脑绝对路径。

构建和测试：

```powershell
.\run.ps1 构建
.\run.ps1 测试
.\package-client.ps1
```

构建生成`target/ua-java-client.jar`；打包生成`dist/ua-java-client.zip`。分发包完整解压后双击同名启动器即可，不需要IDEA。Linux提供`start-client.sh`，目标国产系统和CPU架构仍待实际验收。

## 使用顺序

1. 运行无参数Main，或双击启动器。缺少本机配置时从模板自动创建，已有配置保留。
2. 点击“服务器 → 连接服务器…”。服务器地址填写KEP服务器端点中的完整URL，远程IP填写目标电脑IP/主机名；连接时用远程IP替换URL主机，保留端口与路径。“查询”可列出该目标发布的端点地址，但不是连接前置步骤。
3. 首次没有身份时，在“本机私钥库密码”和“确认新密码”设置相同新密码，点击“首次生成客户端证书”。程序用它保护即将生成的client.p12；结果区显示两份文件路径。公开的client.der不使用这个密码，已有身份不覆盖生成。
4. 将公开证书client.der导入KEP的“受信任的客户端”，重新初始化使配置生效；私钥库client.p12留在本机。生成证书不等于已连接服务器。
5. 在连接窗口填写KEP用户名、用户密码，以及创建client.p12时设置的本机私钥库密码，点击“连接”。认证成功后关闭连接窗口、启用读写，不另弹口令窗口。同次运行内输入保留；退出清空，密码不写入文件。
6. “命名空间”填`ns=0`，“节点标识”填`i=2258`，点击读取，应显示服务器时间、Good及源时间；该节点不用于写测试。
7. 改填现场确认可写节点的命名空间、节点标识及写入值，点击写入；Good表示服务器接受写服务。读写共用这两项，核对时直接点读取，或通过KEP Quick Client观察。
8. 点击“服务器 → 断开”关闭连接。退出时释放会话并清空进程内口令。修改代码后须重新构建、重新运行Main加载新版。

## 连接窗口的五个按钮

| 按钮 | 功能 |
| --- | --- |
| 查询 | 查询目标UA服务器发布的端点URL，选择后填入服务器地址；不登录、不改证书信任 |
| 连接 | 使用当前输入或选中的服务器地址及远程IP连接 |
| 取消 | 关闭连接窗口，不建立连接、不退出主程序 |
| 一键连接 | 使用`connection.quickUrl`预设服务器地址和当前远程IP，不用先查询；预设留空时用`endpoint.url` |
| 实际连接 | 使用`connection.actualUrl`中的完整现场地址，包含目标主机、端口和路径，不受界面地址/IP覆盖 |

快捷预设在同一份`config/local/client.properties`中部署；模板已提供上述两个可选键。现场地址未提供时不要猜测，实际连接会明确提示缺少配置。三个连接按钮使用相同账号、安全策略及身份，现场策略不同时需先修改部署配置。已有本机配置不会自动覆盖或加入虚构现场地址。首次缺少身份时，另外显示一次性证书准备控件。

成功连接后最终URL及用户名写回同一份本机配置；密码不写入文件。修改目标地址或用户名会清空旧的服务器密码。双方证书信任由部署人员准备，客户端不能替服务器批准自身证书。

双向证书指KEP验证Java客户端身份、Java验证KEP服务器身份，私钥留在各自机器。当前模板采用first-use首次固定服务器证书；需要双方预先核准时按strict模式部署，先向KEP导入client.der，再向Java导入服务器公开证书。流程和验收说明见[API_GUIDE.md](API_GUIDE.md)第9节。

## 调用方式

```java
try (UaClient ua = new UaClient(Path.of("config/local/client.properties"))) {
    ua.connect(serverAddress, username, userPassword, keystorePassword);
    Object result = ua.read(address);
    ua.write(address, 12.5f);
}
```

完整参数、返回值、函数调用过程与部署要求见 [API_GUIDE.md](API_GUIDE.md)；操作者复测见 [UI_TEST.md](UI_TEST.md)。

## 读写地址填什么

填写服务器已经提供的**完整OPC UA NodeId**，由现场或服务器管理员提供，也可使用UA客户端浏览节点取得。它与连接地址不同：

UI将NodeId分成两栏填写，类型前缀也要保留；两栏之间的分号由程序补上：

| UI输入框 | 填写示例 | 含义 |
| --- | --- | --- |
| 命名空间 | `nsu=KEPServerEX` | 指定服务器中的命名空间URI；也可用现场提供的`ns=数字`索引 |
| 节点标识 | `s=WriteDemo.Device1.sim` | `s=`表示字符串标识，本例为通道.设备.标签；数字标识使用`i=数字` |

以上合成为`nsu=KEPServerEX;s=WriteDemo.Device1.sim`，传给同一个`read(address)`或`write(address,value)`接口。

| 用途 | 示例 | 从哪里取得 |
| --- | --- | --- |
| 连接服务器 | `opc.tcp://127.0.0.1:49320` | KEP的服务器端点列表 |
| 读写具体标签 | `nsu=KEPServerEX;s=WriteDemo.Device1.sim` | 现场提供或UA节点浏览确认 |
| 驱动访问模拟设备 | `K0001` | KEP标签的地址属性；不能直接填入Java读写框 |

示例中的`KEPServerEX`是命名空间URI，`WriteDemo.Device1.sim`是本机标签的节点标识，对应通道WriteDemo、设备Device1、标签sim。其他服务器以实际NodeId为准；不要直接套用示例或固定假定命名空间索引为2。旧C#的DA ItemId/ProgID也不能直接当作UA NodeId/endpoint。

## 配置与目录

| 位置 | 内容 |
| --- | --- |
| config/templates/client.properties | 公开安全配置模板 |
| config/local/client.properties | 本机配置，不提交 |
| pki/ | 本机应用身份和信任材料，不提交；位置由配置指定 |
| src/main/java/cn/buct/ua/UaClient.java | 对上层公开的连接、读、写封装 |
| src/main/java/cn/buct/ua/UaConnection.java | 复用Milo的认证会话和服务调用 |
| src/main/java/cn/buct/ua/ServerWindow.java | 最简桌面，统一调用公开封装 |
| .cache/、target/、dist/ | 工具缓存、构建和分发包，不提交 |

配置以UTF-8读取，相对路径以配置文件目录为基准。默认私钥库为`pki/client.p12`，公开证书为`pki/client.der`。不要把私钥或密码提交到Git。

## 验证范围

当前公开接口不依赖点表、画面浏览或本机模拟开关，允许连接配置所指定的服务器；服务端账号必须具备对应读写权限。基本标量写入按服务端类型转换，整数拒绝截断/溢出，浮点拒绝非有限值及非零下溢。

自动测试用于验证地址解析、连接参数、会话生命周期、数据质量、写入类型转换、PKI与配置保存等逻辑。运行`run.ps1 构建`执行编译、测试和打包；自动测试结果不代表真实服务器联调通过。本轮未操作实际UI，本机UI、现场服务器、双机网络和国产系统兼容性待验。旧C#工程保持原样。

命令行仅保留桌面、检查配置、初始化证书和信任服务器入口；读写通过桌面或公开Java接口调用。定时采集、近期数据数组、Excel及工艺算法不属于本次三个接口和最简测试界面的交付范围。

## 双机待办

- [ ] 电脑A运行Java客户端，电脑B运行KEPServerEX，验证A连接并读取B的数据。

先完成本机UI复测，再执行双机测试。执行前取得B的实际UA端点、端口、安全策略和账号，确认网络可达及双方公开证书的信任关系；私钥分别留在各自机器。先读取标准服务器时间并检查Good，再读取B提供的实际模拟节点，记录端点、值、质量和源时间。本机通过不代表远程或现场通过。

## 协作要点

- 文件和目录使用英文名称，中文用于提示及必要注释；项目路径使用相对路径，配置内路径以配置文件目录为基准。
- 遵循`.editorconfig`；Java使用google-java-format的AOSP风格。提交前运行`run.ps1 构建`并检查暂存差异，说明自动测试和真实联调各自的结果。
- `config/local/`、`pki/`、`docs/`、缓存和构建产物由Git忽略；只共享配置模板，口令及私钥不提交。
- 上层统一调用`UaClient`，网络操作放在UI后台线程。写请求不自动重放，不在连接后隐式写值；读取失败或非Good不能当作正常值用于计算。
