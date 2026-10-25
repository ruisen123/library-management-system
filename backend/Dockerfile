# LibMS 后端镜像（多阶段：Maven 构建 → JRE 运行）
# 构建上下文 = backend/，见 deploy/docker/docker-compose.yml

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 先只拷 pom 与源码目录，避免 target/ 参与构建（.dockerignore 已排除 target）
COPY pom.xml ./
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
# curl 仅用于容器健康检查；同时以非 root 运行
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 libms
WORKDIR /app
COPY --from=build /build/target/libms-backend-1.0.0.jar app.jar
# logback 会把日志写到 LOG_PATH（默认工作目录下的 logs/）；非 root 用户在 /app 无写权限
# 会让应用启动即失败，因此在切换用户前预建可写日志目录
RUN mkdir -p /app/logs && chown -R libms:libms /app
USER libms
EXPOSE 8080
ENV TZ=Asia/Shanghai
ENV LOGGING_FILE_PATH=/app/logs
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
