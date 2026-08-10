FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# NEXT_PUBLIC_* precisa estar disponível em build-time — o Next embute o valor no
# bundle do cliente durante `next build`, setar só em runtime não tem efeito.
ARG NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
RUN addgroup -S cotacao && adduser -S cotacao -G cotacao
ENV NODE_ENV=production
COPY --from=build /app/public ./public
COPY --from=build --chown=cotacao:cotacao /app/.next/standalone ./
COPY --from=build --chown=cotacao:cotacao /app/.next/static ./.next/static
USER cotacao
EXPOSE 3000
ENV PORT=3000
CMD ["node", "server.js"]
