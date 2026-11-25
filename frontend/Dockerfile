# syntax=docker/dockerfile:1

# ---- Build ----
# Runs on the builder's own CPU; the output is static files, served by the per-platform nginx stage.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app

# Install dependencies first so this layer is cached until the lockfile changes.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---- Serve ----
# Unprivileged image: nginx runs as a non-root user. Binding port 80 as non-root relies on the
# container default net.ipv4.ip_unprivileged_port_start=0 (Docker 20.10 and later).
FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime

COPY nginx-proxy.conf /etc/nginx/snippets/proxy.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:80/healthz || exit 1
