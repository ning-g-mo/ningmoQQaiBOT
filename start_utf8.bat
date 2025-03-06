@echo off
chcp 65001
echo 正在启动柠檬AI机器人...

:: 设置Java选项和系统属性
set JAVA_OPTS=-Xmx512M -Dlog.level=INFO -Dlog.project.level=DEBUG -Dfile.encoding=UTF-8

:: 运行机器人
java %JAVA_OPTS% -jar ningmo-ai-bot-1.0.0-jar-with-dependencies.jar

echo 柠檬AI机器人已退出。
pause 