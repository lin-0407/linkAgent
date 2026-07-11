# 采集脚本容器路径与 Provider 不匹配修复

## 现象

调用 `POST /api/knowledge/reference-videos/fetch-import` 时，后端返回 500，日志出现 `java.nio.file.ProviderMismatchException`。

## 根因

后端镜像的构建上下文原先限制为 `backend/`，运行层也只复制了应用 JAR。因此项目根目录的 `scripts/` 没有进入容器，`/app/scripts` 下找不到采集脚本。

项目根探测在本地工作目录查找失败后继续检查类加载位置。可执行 Spring Boot JAR 的类加载位置可能属于 JAR 文件系统，但脚本相对路径由默认文件系统创建。对两个不同 Provider 的 `Path` 直接调用 `resolve(Path)` 会抛出 `ProviderMismatchException`，使本应返回的“脚本不存在”业务错误变成未处理的 500。

## 修复

- Docker Compose 的后端构建上下文改为仓库根目录，并使用 `backend/Dockerfile`。
- 运行镜像复制 `scripts/` 到 `/app/scripts/`，安装 Python 3 并保留 `python` 命令别名。
- 两个采集服务均改为由基准路径通过 `resolve(String)` 解析相对路径，保证路径始终由同一 Provider 创建。
- 增加 ZIP 文件系统回归测试，覆盖 fat JAR 路径与默认文件系统相对路径组合的场景。

## 验证要点

重新构建并部署后，触发单 BV 案例采集应不再出现 `ProviderMismatchException`。容器内应存在 `/app/scripts/bilibili_reference_fetcher.py`，并且 `python --version` 能正常返回 Python 3 版本。
