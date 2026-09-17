# Next.js 16 standalone BFF for Cloud Run. The BFF needs a Node server (NOT the
# Flutter nginx-static pattern). Firebase web config + SS_API_BASE_URL are read at
# RUNTIME (set on the Cloud Run service), so the build needs no app env.

# ── deps ──
FROM node:22-bookworm-slim AS deps
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci

# ── build ──
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ── runtime ──
FROM node:22-bookworm-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0
# Cloud Run injects PORT (default 8080); Next standalone honors PORT + HOSTNAME.
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
EXPOSE 8080
CMD ["node", "server.js"]
