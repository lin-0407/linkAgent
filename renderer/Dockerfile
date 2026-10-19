FROM python:3.12-slim-bookworm
WORKDIR /opt/renderer
ENV PYTHONDONTWRITEBYTECODE=1 OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
# 字体由系统包提供并保留其许可证，不把作者电脑上的商业字体复制进镜像。
ARG APT_DEBIAN_MIRROR=http://deb.debian.org
RUN if [ "$APT_DEBIAN_MIRROR" != "http://deb.debian.org" ]; then \
        sed -i "s|http://deb.debian.org|${APT_DEBIAN_MIRROR}|g" /etc/apt/sources.list.d/debian.sources; \
    fi \
    && apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=30 update \
    && apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=30 install -y --no-install-recommends ffmpeg fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY linkagent_renderer ./linkagent_renderer
COPY tests ./tests
ENTRYPOINT ["python", "-m", "linkagent_renderer"]
