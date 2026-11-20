#!/bin/sh
set -eu
export REDISCLI_AUTH="$REGION_PASSWORD"
for n in 0 1 2; do
  until redis-cli -h "$REGION-$n" ping 2>/dev/null | grep -q PONG; do sleep 2; done
done
# Use numeric addresses for CLUSTER MEET; clients subsequently discover the announced DNS names.
address() {
  getent hosts "$1" | awk 'NR == 1 {print $1 ":6379"}'
}
if ! redis-cli -h "$REGION-0" cluster info | grep -q 'cluster_slots_assigned:16384'; then
  redis-cli --cluster create "$(address "$REGION-0")" "$(address "$REGION-1")" "$(address "$REGION-2")" \
    --cluster-replicas 0 --cluster-yes
fi
for n in 0 1 2; do
  until redis-cli -h "$REGION-$n" cluster info | grep -q 'cluster_state:ok'; do sleep 1; done
done
