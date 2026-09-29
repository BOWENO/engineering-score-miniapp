FROM node:20-alpine AS web-build
WORKDIR /workspace
COPY admin-web/package*.json ./
RUN npm ci
COPY admin-web/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=web-build /workspace/dist /usr/share/nginx/html
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
