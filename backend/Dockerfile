# 这里采用多阶段构建，是为了让演示镜像里只保留运行时依赖，避免把 Maven 构建缓存一并带进去。
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# 依赖描述文件单独成层，源码变化时可以继续复用已经下载好的 Maven 依赖。
COPY backend/pom.xml .

RUN mvn -B -DskipTests dependency:go-offline

COPY backend/src ./src

RUN mvn -DskipTests package

# 运行时只保留 JRE，这样镜像更小，启动也更快。
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/target/linkAgent-0.0.1-SNAPSHOT.jar /app/app.jar
COPY scripts/ /app/scripts/

# 采集脚本仅使用 Python 标准库；创建 python 别名以兼容 Java 侧既有固定命令。
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8080

# -Xmx512m 限制最大堆内存，避免在 2C4G 服务器上挤占 MySQL/Redis 的可用内存。
# -XX:+UseSerialGC 在 2 核机器上比并行 GC 更省资源。
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-XX:+UseSerialGC", "-jar", "/app/app.jar"]
