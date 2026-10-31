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
foreach ($name in 'client.properties') {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('config/templates/' + $name)) -Destination (Join-Path $stage ('config/templates/' + $name))
}
@'
OPC UA 连接、读、写客户端

运行环境：JDK 21 和图形桌面。Windows 双击 start-client.cmd；Linux 在图形会话中运行 sh start-client.sh。
首次启动自动创建本机连接配置。从“服务器 → 连接…”打开连接窗口，“服务器地址”填写KEP端点列表中的一条URL，“目标服务器IP”填写目标IP或匹配的主机名。
目标IP替换服务器URL的主机部分，端口和路径来自URL；地址必须匹配服务器发布的端点及证书。
安全策略、应用URI、证书路径与信任方式在 config/local/client.properties 中部署配置，不显示配置管理页面。
首次没有身份时，在连接窗口点击“首次准备客户端证书…”，现在设置并确认新密码，程序用它保护即将生成的client.p12。无需已有证书密码。成功后显示两个文件路径，可打开目录；将公开的client.der交KEP信任并重新初始化。
填写KEP用户名及该用户密码，点击连接。已有身份在本次进程首次加载时输入创建client.p12时设置的密码。两类密码用途不同，均不写入配置。
连接后输入现场提供的完整UA NodeId，点击读取；写入填写目标NodeId及数值，点击写入。
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
