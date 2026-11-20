#!/usr/bin/env python3
"""Exercises the running Compose stack from the repository root. Use synthetic data only."""
import json
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4


def compose(*args):
    return subprocess.check_output(["docker", "compose", *args], text=True, timeout=90).strip()


def sql(host, database, statement):
    return compose("exec", "-T", host, "psql", "-XAtq", "-v", "ON_ERROR_STOP=1",
                   "-U", "postgres", "-d", database, "-c", statement)


def http(path, data=None):
    body = json.dumps(data).encode() if data is not None else None
    request = Request(BASE + path, data=body, method="PUT" if body else "GET",
                      headers={"Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=10) as response:
            return response.status, json.load(response), response.headers
    except HTTPError as err:
        return err.code, None, err.headers


def eventually(description, test, timeout=180):
    end = time.monotonic() + timeout
    last = "condition was false"
    while time.monotonic() < end:
        try:
            if test():
                print("PASS", description, flush=True)
                return
        except (URLError, TimeoutError, ConnectionError, subprocess.SubprocessError) as err:
            last = str(err)
        time.sleep(1)
    raise AssertionError(f"{description}: {last}")

BASE = "http://127.0.0.1:8081"
sku = "demo-" + uuid4().hex[:12]
eventually("catalog API ready", lambda: http("/actuator/health/readiness")[0] == 200)


def matches(path, value, source):
    code, item, headers = http(path)
    return code == 200 and item["name"] == value and headers.get("X-Read-Source") == source


for index in (0, 1):
    tenant = 42 + index
    path = f"/tenants/{tenant}/products/{sku}"
    code, item, _ = http(path, {"name": "first"})
    assert code == 200 and item["version"] == 1
    assert matches(path + "?consistency=strong", "first", "primary")
    query = f"SELECT count(*) FROM product WHERE tenant={tenant} AND sku='{sku}'"
    assert sql(f"shard{index}-primary", "catalog", query) == "1"
    assert sql(f"shard{1-index}-primary", "catalog", query) == "0"
    assert sql(f"shard{index}-replica", "catalog", "SELECT pg_is_in_recovery()") == "t"
    eventually(f"shard {index} physically replicated", lambda: sql(f"shard{index}-replica", "catalog", query) == "1")
    for region in ("EAST", "WEST"):
        eventually(f"tenant {tenant} reached {region}", lambda: matches(path + "?region=" + region, "first", "redis-" + region))

# One region loses all its nodes. EAST must advance and the committed write must survive.
west = ["west-0", "west-1", "west-2"]
compose("stop", *west)
try:
    code, item, _ = http(path, {"name": "second"})
    assert code == 200 and item["version"] == 2
    assert matches(path + "?consistency=strong", "second", "primary")
    eventually("healthy region advances", lambda: matches(path + "?region=EAST", "second", "redis-EAST"))
    eventually("west reads its PostgreSQL replica during outage",
               lambda: matches(path + "?region=WEST", "second", "replica"))
    assert sql("shard1-primary", "catalog", f"SELECT count(*) FROM cache_job WHERE sku='{sku}' AND region='WEST'") == "1"
finally:
    compose("start", *west)
try:
    eventually("recovered region catches up", lambda: matches(path + "?region=WEST", "second", "redis-WEST"))
except AssertionError:
    for node in west:
        print(node, compose("exec", "-T", node, "sh", "-c",
                            'REDISCLI_AUTH="$REGION_PASSWORD" redis-cli cluster nodes'))
    print(sql("shard1-primary", "catalog",
              f"SELECT region,version,due_at,attempts FROM cache_job WHERE sku='{sku}'"))
    raise
print("PASS independent regional retry and replica fallback")
