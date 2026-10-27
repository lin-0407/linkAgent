# link-agent-frontend

This template should help get you started developing with Vue 3 in Vite.

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
  - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
  - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
  - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
  - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

## Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### 布局与样式改动的构建检查

```sh
npm run build-only
```

项目协作规则禁止运行 `npm run type-check`；`npm run build` 会间接运行该命令，因此本项目的页面改动使用 `build-only`。本地预览仍使用 `npm run dev`，验证结束后关闭自行启动的服务。

当前页面结构与设计参考见 [B 站风格前端改版](../docs/develop/B站风格前端改版说明.md)。

### 更新 Docker 中的页面

`localhost:8088` 提供的是前端镜像里的静态文件，修改源码或本机生成 `dist` 都不会自动更新该容器。在仓库根目录执行：

```sh
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --no-deps --build frontend
```

该命令只重建前端；后端和数据库沿用当前运行实例。完成后刷新 `http://localhost:8088`，根路径应进入 `/creator`，桌面左侧显示工作台导航。单纯执行 `docker compose restart frontend` 仍然会使用旧镜像。

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```
