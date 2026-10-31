param()
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$jar = Join-Path $projectRoot 'target/ua-java-client.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw '请先执行 .\run.ps1 构建。' }
# 每次从全新暂存目录打包，不从用户已经运行过的目录采集文件。
$stagingParent = [IO.Path]::GetFullPath((Join-Path $projectRoot '.cache/package'))
$stagingRoot = Join-Path $stagingParent ([Guid]::NewGuid().ToString('N'))
$stage = Join-Path $stagingRoot 'ua-java-client'
try {
New-Item -ItemType Directory -Path (Join-Path $stage 'app'),(Join-Path $stage 'config/templates') -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'app/ua-java-client.jar')
foreach ($script in 'start-client.cmd','start-client.sh') {
    Copy-Item -LiteralPath (Join-Path $projectRoot $script) -Destination (Join-Path $stage $script)
}
foreach ($name in 'client.properties','device-addresses.xlsx') {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('config/templates/' + $name)) -Destination (Join-Path $stage ('config/templates/' + $name))
}
@'
OPC UA 连接、读、写客户端

运行环境：JDK 21 和图形桌面。Windows 双击 start-client.cmd；Linux 在图形会话中运行 sh start-client.sh。
首次启动自动创建本机连接配置。从“服务器 → 连接服务器…”打开小窗口，填写完整服务器URL和远程IP/主机名，地址须匹配端点及证书。
查询列出UA端点，不代表认证成功；连接使用输入值；取消只关闭窗口；一键连接使用connection.quickUrl（留空用endpoint.url）和当前远程IP；实际连接使用完整connection.actualUrl，未配置会明确提示。
安全策略、应用URI、证书路径与信任方式在 config/local/client.properties 中部署配置，不显示配置管理页面。
登录方式可选用户名/密码或匿名；匿名不传用户密码，但仍需要本机私钥库和双方应用证书。
在连接窗口点击“导入服务器证书…”，选择目标服务器导出的.der/.cer/.pem即导入。证书存入pki/trust-store/trusted/certs，并保存strict模式；实际双向证书校验在连接时进行，导入成功仅表示完成信任准备。
首次没有身份时，在本机私钥库密码及确认栏设置相同的新密码，点击“首次生成客户端证书”。生成后结果区显示两个文件路径；将公开的client.der交KEP信任并重新初始化。已有身份不会覆盖生成。
填写KEP用户名、用户密码、本机私钥库密码，点击连接，不另弹输入框。两类密码用途不同，均不写入配置；同次运行保留输入，退出清空。
连接后在主窗口分别填写“命名空间”和“节点标识”，点击读取；填写写入值后点击写入。读写共用两栏合成的地址，写入后可直接点读取核对。
主窗口可“导入Excel…”：首张表前两列为设备/变量名称、完整UA NodeId，可带表头。选择设备后自动带入地址，不自动读写。源表不改，规范副本存到config/local/device-addresses.xlsx，下次启动自动读取。
config/templates/device-addresses.xlsx是可编辑示例，实际地址必须向现场确认。
连接并导入表后点击“数据读取”，立即按整张表采集并默认每2秒更新内存Data；选择变量查看缓存值，再次点击停止。配置acquisition.intervalMs和acquisition.batchSize控制周期和批次，缺省2000毫秒和256点。失败点显示错误，不把旧值当作本轮结果。断开清空缓存；Excel仅保存变量名到UA地址的映射。
示例：命名空间填 nsu=KEPServerEX，节点标识填 s=WriteDemo.Device1.sim，程序用分号拼接为完整NodeId；实际以现场提供的节点为准。这里不填服务器URL或K0001驱动地址。
写入成功表示服务器接受写服务，不自动回读、重试或回滚；可另用读取按钮核对。
参数统一保存在 config/local；pki 为本机生成的身份材料，不随演示包分发。
函数调用与参数说明见 API_GUIDE.md。现场和国产系统兼容性仍需实际验收。
'@ | Set-Content -LiteralPath (Join-Path $stage 'README.txt') -Encoding UTF8
foreach ($document in 'API_GUIDE.md','UI_TEST.md') {
    Copy-Item -LiteralPath (Join-Path $projectRoot $document) -Destination (Join-Path $stage $document)
}
$distRoot = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $distRoot -Force | Out-Null
$archive = Join-Path $distRoot 'ua-java-client.zip'
Compress-Archive -LiteralPath $stage -DestinationPath $archive -Force
$demo = Join-Path $distRoot 'ua-java-client'
New-Item -ItemType Directory -Path $demo -Force | Out-Null
# 只更新程序、模板和启动器，保留演示目录已有的 local 和 pki。
Copy-Item -Path (Join-Path $stage '*') -Destination $demo -Recurse -Force
Write-Output ('演示目录：' + $demo)
Write-Output ('分发压缩包：' + $archive)

} finally {
    # 只清理本次生成的暂存目录，避免累积历次完整程序包。
    $resolvedStage = [IO.Path]::GetFullPath($stagingRoot)
    $expectedPrefix = $stagingParent + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedStage.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw '暂存目录超出打包缓存范围，未清理。'
    }
    if (Test-Path -LiteralPath $resolvedStage) {
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
