# build stage
FROM node:22.23.2-bookworm-slim@sha256:48e4b67d85f87bd551df43704e24d252f56cc5f8e9718841aace50f19948f0f9 AS builder
WORKDIR /app
ENV NODE_ENV=development
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN corepack enable && corepack prepare pnpm@11.22.0 --activate && pnpm install --frozen-lockfile --prod=false
COPY . .
RUN pnpm exec vite --version && pnpm build

# runtime stage
FROM nginxinc/nginx-unprivileged:alpine3.24@sha256:334d92979f15aaecd5dd50af5105e1230e2bb70765d45b1e2f964e7c5eda81c3
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 11030
USER nginx
