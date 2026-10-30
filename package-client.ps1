param()
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$jar = Join-Path $projectRoot 'target/ua-java-client.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw '请先执行 .\run.ps1 构建。' }
# 每次从全新暂存目录打包，不从用户已经运行过的目录采集文件。
$stage = Join-Path $projectRoot ('.cache/package/' + [Guid]::NewGuid().ToString('N') + '/ua-java-client')
New-Item -ItemType Directory -Path (Join-Path $stage 'app'),(Join-Path $stage 'config/templates') -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'app/ua-java-client.jar')
foreach ($script in 'start-client.cmd','start-client.sh') {
    Copy-Item -LiteralPath (Join-Path $projectRoot $script) -Destination (Join-Path $stage $script)
}
foreach ($name in 'client.properties','nodes.properties') {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('config/templates/' + $name)) -Destination (Join-Path $stage ('config/templates/' + $name))
}
@'
OPC UA 第一部分桌面演示包

运行环境：JDK 21 和图形桌面。Windows 双击 start-client.cmd；Linux 在图形会话中运行 sh start-client.sh。
首次启动按提示创建本机配置，在“服务器 → 连接配置”填写实际地址、策略和用户名。
通过“证书”菜单初始化身份，将 client.der 导入 KEP 的受信任客户端后连接。
在点位窗口选择设备和标签保存；主窗口支持读取、点位写授权和范围设置、一次写入并回读。
模拟写入目前仅开放本机回环端点；演示结束关闭写入总开关及点位写授权。
参数统一保存在 config/local；pki 为本机生成的身份材料，不随演示包分发。
本包不包含持续采集、Excel、历史曲线或工艺控制；国产系统现场兼容性仍需在目标系统验收。
'@ | Set-Content -LiteralPath (Join-Path $stage 'README.txt') -Encoding UTF8
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
