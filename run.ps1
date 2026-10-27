param(
    [string]$Action = '帮助',
    [string]$Config = '',
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$ExtraArgs
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = $PSScriptRoot
Push-Location -LiteralPath $projectRoot
$previousMavenHome = $env:MAVEN_USER_HOME
try {
    foreach ($directory in @('.cache\tmp', '.cache\maven-user')) {
        New-Item -ItemType Directory -Path (Join-Path $projectRoot $directory) -Force | Out-Null
    }
    $env:MAVEN_USER_HOME = Join-Path $projectRoot '.cache\maven-user'
    if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'E:\Java\JDK21\bin\java.exe')) {
        $env:JAVA_HOME = 'E:\Java\JDK21'
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        throw '请将 JAVA_HOME 指向本机 JDK 21（例如 E:\Java\JDK21），不要指向 IDEA 自带运行时。'
    }
    $tempDirectory = Join-Path $projectRoot '.cache\tmp'
    if ($Action -in @('构建', '测试')) {
        $previousMavenOptions = $env:MAVEN_OPTS
        try {
            $env:MAVEN_OPTS = "$previousMavenOptions `"-Djava.io.tmpdir=$tempDirectory`""
            $goal = if ($Action -eq '构建') { 'verify' } else { 'test' }
            & (Join-Path $projectRoot 'mvnw.cmd') --batch-mode $goal
            if ($LASTEXITCODE -ne 0) { throw 'Maven 执行失败，详情见上方输出。' }
        } finally { $env:MAVEN_OPTS = $previousMavenOptions }
        exit 0
    }
    $jar = Join-Path $projectRoot 'target\ua-java-client.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw '请先运行：.\run.ps1 构建' }
    $javaArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', "-Djava.io.tmpdir=$tempDirectory", '-jar', $jar, $Action)
    if ($Action -ne '帮助') {
        if (-not $Config) { $Config = Join-Path $projectRoot 'config\local\client.properties' }
        $javaArgs += $Config
    }
    $javaArgs += $ExtraArgs
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') @javaArgs
    $exitCode = $LASTEXITCODE
} finally {
    $env:MAVEN_USER_HOME = $previousMavenHome
    Pop-Location
}
exit $exitCode
