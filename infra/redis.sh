#!/bin/sh
set -eu
umask 077
file=/tmp/regional-redis.conf
cat > "$file" <<CONF
bind 0.0.0.0
protected-mode yes
requirepass $REGION_PASSWORD
masterauth $REGION_PASSWORD
dir /data
appendonly yes
cluster-enabled yes
cluster-config-file nodes.conf
cluster-node-timeout 5000
cluster-announce-hostname $NODE_NAME
cluster-preferred-endpoint-type hostname
CONF
if [ "$(id -u)" = 0 ]; then chown redis:redis "$file"; fi
exec /usr/local/bin/docker-entrypoint.sh redis-server "$file"
