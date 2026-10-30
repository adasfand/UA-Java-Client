# Java OPC UA 客户端

使用Java 21、Swing和Eclipse Milo 1.1.6，提供三个可供上层调用的接口：`connect(...)`、`read(address)`、`write(address, value)`。先建立认证会话，再按现场提供的UA地址读取或写入。

桌面仅保留服务器地址/IP、必要账号和口令、连接/断开、读地址与结果、写地址与值、状态行。安全策略和证书路径从文件加载，用户不需要先查询端点或在画面添加点位。

## 运行

Windows双击 `start-client.cmd`，或在IDEA运行“服务器客户端”/无参数`Main.main`。IDEA工作目录为项目根目录。机器需要JDK21，可用JAVA_HOME或PATH选择；Java工具和依赖不绑定个人电脑绝对路径。

构建和测试：

```powershell
.\run.ps1 构建
.\run.ps1 测试
.\package-client.ps1
```

构建生成`target/ua-java-client.jar`；打包生成`dist/ua-java-client.zip`。分发包完整解压后双击同名启动器即可，不需要IDEA。Linux提供`start-client.sh`，目标国产系统和CPU架构仍待实际验收。

## 使用顺序

1. 部署人员按服务器实际参数准备 `config/local/client.properties`。首次桌面启动会从模板自动创建该文件，不覆盖已有配置。
2. 在主窗口输入完整服务器URL，例如`opc.tcp://127.0.0.1:49320`。也可只输入IP/电脑名，此时使用配置中的端口与路径。
3. 填写账号密码和客户端证书口令，点击“连接”。密码只在本次进程内使用；字段为空时从配置指定的本机环境变量取值。
4. 已有身份直接连接。没有身份时首次点击会生成客户端证书，状态提示将`client.der`交服务器信任；完成后再次点击连接。客户端不能替服务器批准自身证书。
5. 输入现场提供的UA NodeId，点击“读取”，查看值、质量与源时间。
6. 输入写目标NodeId和数值，点击“写入”。返回Good表示服务器接受写服务；需要读回时另点读取。
7. 断开或退出时释放会话。修改代码后须重新构建并重启旧进程。

成功连接后仅地址及用户名写回同一份本机配置；密码不保存。策略、身份类型、证书及信任设置由部署配置控制，不在普通画面显示。

## 调用方式

```java
try (UaClient ua = new UaClient(Path.of("config/local/client.properties"))) {
    ua.connect(endpoint, username, userPassword, keystorePassword);
    Object result = ua.read(address);
    ua.write(address, 12.5f);
}
```

完整参数、返回值、部署要求及可复制示例见 [API_GUIDE.md](API_GUIDE.md)；函数调用过程见 [CODE_GUIDE.md](CODE_GUIDE.md)；操作者复测见 [UI_TEST.md](UI_TEST.md)。

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
| docs/ | 本机历史联调资料，不提交 |

配置以UTF-8读取，相对路径以配置文件目录为基准。默认私钥库为`pki/client.p12`，公开证书为`pki/client.der`。不要把私钥或密码提交到Git。

## 验证范围

当前公开接口不依赖点表、画面浏览或本机模拟开关，允许连接配置所指定的服务器；服务端账号必须具备对应读写权限。基本标量写入按服务端类型转换，整数拒绝截断/溢出，浮点拒绝非有限值及非零下溢。

本轮完成代码级和自动测试验证，未操作实际UI；现场服务器、双机网络和国产系统兼容性仍须验收。历史真实通信结果和本轮验证区分记录在 [VERIFICATION.md](VERIFICATION.md)。旧C#工程没有修改。

工程中原有命令行诊断与点表工具保留兼容，均不属于新桌面的操作前置条件。定时采集、近期数据数组、Excel及工艺算法不属于本次三个接口和最简测试界面的交付范围。
