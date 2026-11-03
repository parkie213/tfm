# Build the browser app, then serve it locally with nginx.
FROM node:26-alpine AS build
WORKDIR /app
ARG VITE_BACKEND_URL=http://localhost:8088
ENV VITE_BACKEND_URL=$VITE_BACKEND_URL
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
