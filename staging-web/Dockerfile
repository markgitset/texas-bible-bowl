# Static staging frontend: nginx serving the pre-assembled Hugo site + web app.
# tools/deploy-staging.sh web builds public/ before running fly deploy.
FROM nginx:1.27-alpine-slim
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY public/ /usr/share/nginx/html/
