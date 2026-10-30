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
foreach ($name in 'client.properties') {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('config/templates/' + $name)) -Destination (Join-Path $stage ('config/templates/' + $name))
}
@'
OPC UA 连接、读、写客户端

运行环境：JDK 21 和图形桌面。Windows 双击 start-client.cmd；Linux 在图形会话中运行 sh start-client.sh。
首次启动自动创建本机连接配置，在主窗口输入服务器地址/IP、必要账号和口令，点击连接。
安全策略、应用URI、证书路径与信任方式在 config/local/client.properties 中部署配置，不显示配置管理页面。
缺少身份时首次连接会生成客户端证书；请将 client.der 交服务器信任后再次连接。已有身份直接复用。
连接后输入现场提供的完整UA NodeId，点击读取；写入填写目标NodeId及数值，点击写入。
写入成功表示服务器接受写服务，不自动回读、重试或回滚；可另用读取按钮核对。
参数统一保存在 config/local；pki 为本机生成的身份材料，不随演示包分发。
函数调用与参数说明见 API_GUIDE.md。现场和国产系统兼容性仍需实际验收。
'@ | Set-Content -LiteralPath (Join-Path $stage 'README.txt') -Encoding UTF8
Copy-Item -LiteralPath (Join-Path $projectRoot 'API_GUIDE.md') -Destination (Join-Path $stage 'API_GUIDE.md')
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
