#!/bin/bash
echo "正在启动宁默AI机器人..."

# 设置Java选项和系统属性
JAVA_OPTS="-Xmx512M -Dlog.level=INFO -Dlog.project.level=DEBUG"

# 运行机器人
java ${JAVA_OPTS} -jar ningmo-ai-bot-1.0.0-jar-with-dependencies.jar

echo "宁默AI机器人已退出。" 