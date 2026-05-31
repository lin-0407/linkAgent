# 前端构建阶段只负责把 Vue 打成静态文件，运行阶段交给 Nginx，方便演示环境直接提供同源访问。
FROM node:22-bookworm-slim AS build

WORKDIR /app

# 先拷贝依赖锁文件，是为了让 npm ci 只在依赖变化时重新安装。
COPY package*.json ./

RUN npm ci

COPY . .

RUN npm run build

# 静态站点用 Nginx 承载，既能直接服务 SPA，又能顺手把 /api 转发给后端。
FROM nginx:1.27-alpine

COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
