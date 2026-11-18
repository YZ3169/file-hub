# ---- 构建阶段：只编后端，前端产物已经提交在 src/main/resources/static ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先只拷 pom 拉依赖，改代码时能命中这层缓存
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S filehub && adduser -S -G filehub -h /app filehub \
    && mkdir -p /data && chown -R filehub:filehub /data /app
WORKDIR /app
COPY --from=build /workspace/target/file-hub-1.0.0.jar /app/app.jar

ENV FILE_HUB_ROOT=/data \
    SERVER_PORT=8081 \
    TZ=Asia/Shanghai

USER filehub
EXPOSE 8081
VOLUME ["/data"]

# 用 sh -c 是为了让 JAVA_OPTS 里的参数能被展开
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
