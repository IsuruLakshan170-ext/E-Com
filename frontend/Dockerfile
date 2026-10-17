# Build: docker build -t ecom-frontend ./frontend    (needs network for npm; not run in this environment)
FROM node:20.17-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
# Use the lockfile once one exists (generate it with the first `npm install` and commit it).
RUN if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi
COPY . .
RUN npm run build

# Unprivileged nginx (listens on 8080, runs as a non-root user)
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
