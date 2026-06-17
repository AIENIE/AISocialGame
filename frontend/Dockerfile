# build stage
FROM node:20-bullseye AS builder
WORKDIR /app
ENV NODE_ENV=development
COPY package.json pnpm-lock.yaml ./
RUN corepack enable && corepack prepare pnpm@10.30.0 --activate && pnpm install --frozen-lockfile --prod=false
COPY . .
RUN pnpm exec vite --version && pnpm build

# runtime stage
FROM node:20-alpine
WORKDIR /app
COPY --from=builder /app/dist ./dist
RUN npm install -g serve@14 --no-audit --no-fund
EXPOSE 11030
CMD ["serve", "-s", "dist", "-l", "tcp://0.0.0.0:11030"]
