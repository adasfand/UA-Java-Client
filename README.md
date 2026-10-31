# Java OPC UA 客户端

使用Java 21、Swing和Eclipse Milo 1.1.6，提供三个可供上层调用的接口：`connect(...)`、`read(address)`、`write(address, value)`。先建立认证会话，再按现场提供的UA地址读取或写入。

桌面参照旧C#的独立服务器连接窗口：主窗口只显示读、写及结果；“服务器 → 连接…”打开小窗口，填写服务器地址、目标服务器IP和KEP账号密码。安全策略及证书路径从文件加载，不在普通连接窗口编辑。

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
2. 点击“服务器 → 连接…”。“服务器地址”复制KEP的“OPC UA 配置管理器 → 服务器端点”列表中的一条URL；“目标服务器IP”填写运行KEP那台电脑的IP或匹配的主机名。IP替换URL主机，端口和路径保留。
3. 首次没有身份时，点击“首次准备客户端证书…”，现在自行设置并确认一个新密码。程序用新密码保护即将生成的client.p12私钥库，不需要已有证书密码。成功提示显示两份文件的实际路径，可点击“打开证书目录”；公开的client.der不使用这个密码。
4. 将公开证书client.der导入KEP的“受信任的客户端”，重新初始化使配置生效；私钥库client.p12留在本机。生成证书不等于已连接服务器。
5. 在连接窗口填写KEP用户管理器中创建的用户名及密码，点击“连接”。已有身份在本次进程首次加载时提示输入创建client.p12时设置的密码；刚生成或已成功加载的同一身份可在进程内复用。只有认证会话成功才关闭连接窗口、启用读写。
6. 读取地址可先填标准服务器时间`ns=0;i=2258`，点击读取，应显示值、Good及源时间。随后使用现场确认的UA NodeId。
7. 写入填写目标NodeId及值，点击写入；Good表示服务器接受写服务。需要核对时再点读取，或通过KEP Quick Client观察。
8. 从“服务器 → 断开”关闭连接。退出时释放会话并清空进程内口令。修改代码后须重新构建、重新运行Main加载新版。

成功连接后最终URL及用户名写回同一份本机配置；密码不写入文件。修改目标地址或用户名会清空旧的服务器密码。双方证书信任由部署人员准备，客户端不能替服务器批准自身证书。

双向证书指KEP验证Java客户端身份、Java验证KEP服务器身份，私钥留在各自机器。当前模板采用first-use首次固定服务器证书；需要双方预先核准时按strict模式部署，先向KEP导入client.der，再向Java导入服务器公开证书。流程和验收说明见[API_GUIDE.md](API_GUIDE.md)第9节。

## 调用方式

```java
try (UaClient ua = new UaClient(Path.of("config/local/client.properties"))) {
    ua.connect(serverAddress, remoteHost, username, userPassword, keystorePassword);
    Object result = ua.read(address);
    ua.write(address, 12.5f);
}
```

完整参数、返回值、函数调用过程与部署要求见 [API_GUIDE.md](API_GUIDE.md)；操作者复测见 [UI_TEST.md](UI_TEST.md)。

地址由现场确认。例如`ns=0;i=2258`是标准服务器时间；`nsu=KEPServerEX;s=DemoChannel.DemoDevice.sim`只是本机示例。旧C#的DA ItemId/ProgID不是UA NodeId/endpoint，不自动猜测转换。

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
