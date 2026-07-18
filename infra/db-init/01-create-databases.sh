#!/bin/bash
# Runs once, on the first boot of an empty postgres volume.
# Two databases, each owned by its own non-super user. CONNECT is revoked from
# PUBLIC so a service pointed at the wrong database fails at permission time
# instead of never.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	CREATE USER kestra WITH PASSWORD '${KESTRA_DB_PASSWORD}';
	CREATE DATABASE kestra OWNER kestra;
	REVOKE CONNECT ON DATABASE kestra FROM PUBLIC;
	CREATE USER dataflow WITH PASSWORD '${DATAFLOW_DB_PASSWORD}';
	CREATE DATABASE dataflow OWNER dataflow;
	REVOKE CONNECT ON DATABASE dataflow FROM PUBLIC;
EOSQL
