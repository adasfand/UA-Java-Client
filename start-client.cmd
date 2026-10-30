@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "CLIENT_JAR=target\ua-java-client.jar"
if exist "app\ua-java-client.jar" set "CLIENT_JAR=app\ua-java-client.jar"
if not exist "%CLIENT_JAR%" (
  echo 未找到客户端程序包。请使用已构建的演示包，或先执行项目构建。
  pause
  exit /b 1
)
set "CLIENT_JAVA=javaw.exe"
if defined JAVA_HOME set "CLIENT_JAVA=%JAVA_HOME%\bin\javaw.exe"
if defined JAVA_HOME if not exist "%CLIENT_JAVA%" goto java_missing
if not defined JAVA_HOME where javaw.exe >nul 2>nul || goto java_missing
start "" "%CLIENT_JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%CLIENT_JAR%"
exit /b 0
:java_missing
echo 请安装 JDK 21，并设置 JAVA_HOME 或将 Java 加入 PATH 后重试。
pause
exit /b 1
endlocal
