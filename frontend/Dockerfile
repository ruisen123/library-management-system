# LibMS 前端镜像（多阶段：Vite 构建 → Nginx 托管 + 反向代理 /api）
# 构建上下文 = frontend/，见 deploy/docker/docker-compose.yml

FROM node:20-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ENV VITE_API_BASE=/api
RUN npm run build

FROM nginx:1.27-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
