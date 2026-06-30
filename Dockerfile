# ---- Build Stage ----
FROM node:22-alpine AS builder

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- Runtime Stage ----
FROM node:22-alpine

WORKDIR /app

# Copy dependency manifests and install production + vite (needed at import time)
COPY package.json package-lock.json ./
RUN npm ci

# Copy built frontend and server source
COPY --from=builder /app/dist ./dist
COPY server.ts ./

# Create the data directory for master .kdbx files
RUN mkdir -p /app/data

ENV NODE_ENV=production

EXPOSE 3000

# tsx is installed as a dependency; use it to run the TypeScript server directly
CMD ["npx", "tsx", "server.ts"]
