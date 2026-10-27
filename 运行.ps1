param(
    [string]$操作 = '帮助',
    [string]$配置 = '',
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$附加参数
)
$ErrorActionPreference = 'Stop'
$项目目录 = $PSScriptRoot
Push-Location -LiteralPath $项目目录
$原Maven用户目录 = $env:MAVEN_USER_HOME
try {
    foreach ($目录 in @('.缓存\临时', '.缓存\Maven用户')) {
        New-Item -ItemType Directory -Path (Join-Path $项目目录 $目录) -Force | Out-Null
    }
    $env:MAVEN_USER_HOME = Join-Path $项目目录 '.缓存\Maven用户'
    if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'E:\Java\JDK21\bin\java.exe')) {
        $env:JAVA_HOME = 'E:\Java\JDK21'
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        throw '请将 JAVA_HOME 指向本机 JDK 21（例如 E:\Java\JDK21），不要指向 IDEA 自带运行时。'
    }
    $临时目录 = Join-Path $项目目录 '.缓存\临时'
    if ($操作 -in @('构建', '测试')) {
        $原Maven选项 = $env:MAVEN_OPTS
        try {
            $env:MAVEN_OPTS = "$原Maven选项 `"-Djava.io.tmpdir=$临时目录`""
            $目标 = if ($操作 -eq '构建') { 'verify' } else { 'test' }
            & (Join-Path $项目目录 'mvnw.cmd') --batch-mode $目标
            if ($LASTEXITCODE -ne 0) { throw 'Maven 执行失败，详情见上方输出。' }
        } finally { $env:MAVEN_OPTS = $原Maven选项 }
        exit 0
    }
    $jar = Join-Path $项目目录 'target\ua-java-client.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw '请先运行：.\运行.ps1 构建' }
    $参数 = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', "-Djava.io.tmpdir=$临时目录", '-jar', $jar, $操作)
    if ($操作 -ne '帮助') {
        if (-not $配置) { $配置 = Join-Path $项目目录 '本机配置\客户端.properties' }
        $参数 += $配置
    }
    $参数 += $附加参数
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') @参数
    $退出码 = $LASTEXITCODE
} finally {
    $env:MAVEN_USER_HOME = $原Maven用户目录
    Pop-Location
}
exit $退出码
