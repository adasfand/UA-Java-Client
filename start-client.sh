#!/usr/bin/env sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
client_jar=target/ua-java-client.jar
if [ -f app/ua-java-client.jar ]; then client_jar=app/ua-java-client.jar; fi
client_java=java
if [ -n "${JAVA_HOME:-}" ]; then client_java="$JAVA_HOME/bin/java"; fi
exec "$client_java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$client_jar"
