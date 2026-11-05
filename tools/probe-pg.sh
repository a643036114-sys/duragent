#!/bin/bash
{
  echo "=== postgres container env ==="
  docker inspect postgres --format '{{range .Config.Env}}{{println .}}{{end}}' 2>&1
  echo "=== postgres ports ==="
  docker port postgres 2>&1
  echo "=== bigrag-postgres env ==="
  docker inspect bigrag-postgres --format '{{range .Config.Env}}{{println .}}{{end}}' 2>&1 | grep -Ei 'postgres_|password' 
  echo "=== bigrag-postgres ports ==="
  docker port bigrag-postgres 2>&1
  echo "=== databases on :5432 ==="
  docker exec postgres psql -U postgres -c '\l' 2>&1 | head -30
} > /tmp/duragent-probe2.out 2>&1
