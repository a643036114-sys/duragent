#!/bin/bash
{
  if ! docker exec postgres psql -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='duragent'" | grep -q 1; then
    echo "creating database duragent"
    docker exec postgres psql -U postgres -c "CREATE DATABASE duragent"
  else
    echo "database duragent already exists"
  fi
  echo "--- databases ---"
  docker exec postgres psql -U postgres -tAc "SELECT datname FROM pg_database WHERE datistemplate = false"
  echo "--- pg_hba tail ---"
  docker exec postgres bash -c "tail -5 /var/lib/postgresql/data/pg_hba.conf"
} > /tmp/duragent-createdb.out 2>&1
