#!/bin/bash
# ============================================
# 部署新闻RSS服务到腾讯云服务器 (124.220.15.120)
# 端口: 8891
# ============================================
set -e

APP_NAME="news-feed"
SERVER="tencent-server"
DEPLOY_DIR="/opt/${APP_NAME}"
JAR_NAME="${APP_NAME}.jar"
PORT=8891

echo "=== 1. 本地编译 ==="
cd "$(dirname "$0")"
mvn clean package -DskipTests -q
echo "✅ 编译完成"

echo "=== 2. 上传 JAR ==="
ssh "${SERVER}" "mkdir -p ${DEPLOY_DIR}/data"
scp "target/${JAR_NAME}" "${SERVER}:${DEPLOY_DIR}/${JAR_NAME}"
echo "✅ 上传完成"

echo "=== 3. 停止旧服务 ==="
ssh "${SERVER}" "if [ -f ${DEPLOY_DIR}/${APP_NAME}.pid ]; then
    kill \$(cat ${DEPLOY_DIR}/${APP_NAME}.pid) 2>/dev/null || true
    rm -f ${DEPLOY_DIR}/${APP_NAME}.pid
fi
# 检查端口是否还被占用
if ss -tlnp | grep -q ':${PORT} '; then
    fuser -k ${PORT}/tcp 2>/dev/null || true
fi
sleep 1"

echo "=== 4. 启动服务 ==="
ssh "${SERVER}" "export JAVA_HOME=/usr/local/jdk17 && \
nohup \${JAVA_HOME}/bin/java -jar ${DEPLOY_DIR}/${JAR_NAME} \
    --server.port=${PORT} \
    --spring.datasource.url=jdbc:h2:file:${DEPLOY_DIR}/data/newsfeed \
    > ${DEPLOY_DIR}/${APP_NAME}.log 2>&1 & \
echo \$! > ${DEPLOY_DIR}/${APP_NAME}.pid && \
echo '服务已启动, PID: ' \$(cat ${DEPLOY_DIR}/${APP_NAME}.pid)"

echo "=== 5. 等待启动 ==="
sleep 8
ssh "${SERVER}" "tail -5 ${DEPLOY_DIR}/${APP_NAME}.log"

echo ""
echo "=== 部署完成 ==="
echo "访问地址: http://124.220.15.120:${PORT}/feeds"
echo "登录: admin / admin123"
echo "查看日志: ssh ${SERVER} 'tail -f ${DEPLOY_DIR}/${APP_NAME}.log'"
