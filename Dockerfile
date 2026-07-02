# ---- Stage 1: Build Frontend ----
FROM node:22-alpine AS frontend-builder

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- Stage 2: Build Rust Backend ----
FROM rust:1-alpine AS backend-builder

RUN apk add --no-cache musl-dev

WORKDIR /app
COPY backend/ ./
RUN cargo build --release

# ---- Stage 3: Runtime ----
FROM alpine:3.20

WORKDIR /app

COPY --from=backend-builder /app/target/release/backend ./backend
COPY --from=frontend-builder /app/dist ./dist

RUN mkdir -p /app/data

EXPOSE 3000

CMD ["./backend"]
