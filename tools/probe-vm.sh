#!/bin/bash
{
  echo "HOSTNAME=$(hostname)"
  echo "IPS=$(hostname -I)"
  echo "--- docker service ---"
  systemctl is-active docker 2>&1
  echo "--- docker containers ---"
  docker ps -a --format '{{.Names}} | {{.Image}} | {{.Status}}' 2>&1
  echo "--- docker compose? ---"
  docker compose version 2>&1 || docker-compose --version 2>&1
  echo "--- postgres service ---"
  systemctl is-active postgresql 2>&1
  echo "--- psql ---"
  psql -V 2>&1
  echo "--- listening ports ---"
  ss -lntp 2>&1 | grep -E '5432|2375|8080' || true
  echo "--- disk ---"
  df -h / 2>&1 | tail -1
} > /tmp/duragent-probe.out 2>&1
