#!/bin/bash
# Rehearses the staging measurements against a local server, so the tool is known to work first.
export WRANGLER_SEND_METRICS=false
node test/serve.mjs 8798 > /dev/null 2>&1 &
SERVER=$!
for i in $(seq 1 100); do curl -s -m1 http://127.0.0.1:8798/healthz > /dev/null && break; sleep 0.5; done
export AURALIS_MEASURE_URL=ws://127.0.0.1:8798 AURALIS_MEASURE_TOKEN=staging:local-secret:rehearsal
rm -f measure-results.jsonl
node tools/measure.mjs check
node tools/measure.mjs messages 500
node tools/measure.mjs to-limit 200
node tools/measure.mjs connects 10
node tools/measure.mjs idle 1
AURALIS_MEASURE_TOKEN=staging:wrong:rehearsal node tools/measure.mjs check 2>&1 | tail -1
AURALIS_MEASURE_URL=ws://example.com node tools/measure.mjs check 2>&1 | tail -1
taskkill //PID $SERVER //T //F > /dev/null 2>&1 || kill $SERVER
