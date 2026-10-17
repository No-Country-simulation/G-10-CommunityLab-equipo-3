# --- Build: compile the Angular app ---
FROM node:22-alpine AS build
WORKDIR /app

# Dependencies first so this layer is cached while only sources change
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# --- Runtime: nginx serves the static app and proxies /api to the backend ---
FROM nginx:1.29-alpine

# Backend the /api requests (REST + SSE streams) are forwarded to
ENV BACKEND_URL=http://host.docker.internal:8080

# The nginx image renders templates/*.template with envsubst on startup
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html

EXPOSE 80
