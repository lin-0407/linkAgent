# 这里采用多阶段构建，是为了让演示镜像里只保留运行时依赖，避免把 Maven 构建缓存一并带进去。
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# 依赖描述文件单独成层，源码变化时可以继续复用已经下载好的 Maven 依赖。
COPY backend/pom.xml .

RUN mvn -B -DskipTests dependency:go-offline

COPY backend/src ./src

# 打完包立刻用 Spring Boot 的 tools 模式展开成「瘦 jar + lib/」。
# 为什么必须展开：ai.djl 的 Platform.detectPlatform 要读 native/lib/tokenizers.properties 的真实文件 URL，
# 读不到 Spring Boot 嵌套 jar（jar:nested:...）里的那一份，会抛
# ExceptionInInitializerError: No tokenizers version found in property file；
# 之后每次加载都变成 NoClassDefFoundError，表现为流式接口只发 session 事件然后静默挂住。
RUN mvn -DskipTests package \
    && java -Djarmode=tools -jar target/linkAgent-0.0.1-SNAPSHOT.jar extract --destination target/extracted

# 运行时只保留 JRE，这样镜像更小，启动也更快。
# 为什么显式带 -noble：不带后缀的 tag 会漂到 Ubuntu 开发版（实测漂到了 resolute），
# 开发版的 universe 源在第三方镜像上缺失（aliyun 返回 404），会导致 apt 安装 ffmpeg 直接失败。
# noble = Ubuntu 24.04 LTS，源齐全、长期可用。
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

# 只拷展开后的布局：app.jar 是瘦 jar（含应用类），依赖都以普通 jar 文件躺在 lib/ 下，
# 这样 DJL 拿到的 native 资源是 jar:file: 而不是 jar:nested:，原生库才能正常定位和解压。
COPY --from=build /workspace/target/extracted/lib/ /app/lib/
COPY --from=build /workspace/target/extracted/linkAgent-0.0.1-SNAPSHOT.jar /app/app.jar
COPY scripts/ /app/scripts/

# apt 源可切换：默认仍是官方源，线上行为不变；国内网络直连 archive.ubuntu.com 不稳定时
# （实测出现过 apt 重试 39 分钟仍失败），用 build arg APT_UBUNTU_MIRROR 换成国内镜像即可。
ARG APT_UBUNTU_MIRROR=http://archive.ubuntu.com

# 采集脚本仅使用 Python 标准库；创建 python 别名以兼容 Java 侧既有固定命令。
# 阶段 7 媒体探测依赖 ffprobe，Debian 的 ffmpeg 包会同时提供 ffmpeg 和 ffprobe。
# Acquire::Retries / Timeout：上游源抖动时默认重试不够，实测出现过 39 分钟连接失败后整步报错。
RUN if [ "$APT_UBUNTU_MIRROR" != "http://archive.ubuntu.com" ]; then \
        sed -i "s|http://archive.ubuntu.com|${APT_UBUNTU_MIRROR}|g; s|http://security.ubuntu.com|${APT_UBUNTU_MIRROR}|g" \
            /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true; \
    fi \
    && apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=30 update \
    && apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=30 install -y --no-install-recommends python3 ffmpeg \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8080

# -Xmx512m 限制最大堆内存，避免在 2C4G 服务器上挤占 MySQL/Redis 的可用内存。
# -XX:+UseSerialGC 在 2 核机器上比并行 GC 更省资源。
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-XX:+UseSerialGC", "-jar", "/app/app.jar"]
