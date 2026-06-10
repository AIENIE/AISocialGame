# build stage
FROM node:20-bullseye AS builder
WORKDIR /app
ENV NODE_ENV=development
COPY package.json pnpm-lock.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile --prod=false
COPY . .
RUN pnpm exec vite --version && pnpm build

# runtime stage
FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html
COPY --from=builder /app/dist ./
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 11030
CMD ["nginx","-g","daemon off;"]
