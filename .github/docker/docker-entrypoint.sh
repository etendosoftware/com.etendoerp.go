#!/bin/bash
set -e

# ─────────────────────────────────────────────
# Environment variables with defaults
# ─────────────────────────────────────────────
BBDD_HOST="${BBDD_HOST:-localhost}"
BBDD_PORT="${BBDD_PORT:-5432}"
BBDD_SID="${BBDD_SID:-etendo}"
BBDD_SYSTEMUSER="${BBDD_SYSTEMUSER:-postgres}"
BBDD_SYSTEMPASSWORD="${BBDD_SYSTEMPASSWORD:-EtendoStaging2026}"
BBDD_SYSTEMPASSWORD="${BBDD_SYSTEMPASSWORD:-syspass}"
BBDD_USER="${BBDD_USER:-tad}"
BBDD_PASSWORD="${BBDD_PASSWORD:-tad}"
CONTEXT_URL="${CONTEXT_URL:-http://localhost:8080/etendo}"
ATTACH_PATH="${ATTACH_PATH:-/opt/openbravo/attachments}"

# ─────────────────────────────────────────────
# Wait for PostgreSQL
# ─────────────────────────────────────────────
echo ">>> Waiting for PostgreSQL at ${BBDD_HOST}:${BBDD_PORT}..."
until PGPASSWORD="$BBDD_SYSTEMPASSWORD" pg_isready -h "$BBDD_HOST" -p "$BBDD_PORT" -U "$BBDD_SYSTEMUSER" -q; do
    echo "    PostgreSQL not ready yet, retrying in 3s..."
    sleep 3
done
echo ">>> PostgreSQL is ready."

# ─────────────────────────────────────────────
# Inject openbravo.properties into expanded WAR
# ─────────────────────────────────────────────
PROPS_FILE="$CATALINA_HOME/webapps/etendo/WEB-INF/classes/openbravo.properties"
echo ">>> Writing openbravo.properties..."

cat > "$PROPS_FILE" << EOF
dateFormat.js=%d-%m-%Y
dateFormat.sql=DD-MM-YYYY
dateFormat.java=dd-MM-yyyy
dateTimeFormat.java=dd-MM-yyyy HH:mm:ss
dateTimeFormat.sql=DD-MM-YYYY HH24:MI:SS

web.url=@actual_url_context@/web
context.url=${CONTEXT_URL}
attach.path=${ATTACH_PATH}
context.name=etendo
source.path=/opt/etendo
deploy.mode=war

bbdd.rdbms=POSTGRE
bbdd.driver=org.postgresql.Driver
bbdd.url=jdbc:postgresql://${BBDD_HOST}:${BBDD_PORT}
bbdd.sid=${BBDD_SID}
bbdd.systemUser=${BBDD_SYSTEMUSER}
bbdd.systemPassword=${BBDD_SYSTEMPASSWORD}
bbdd.user=${BBDD_USER}
bbdd.password=${BBDD_PASSWORD}
bbdd.sessionConfig=select update_dateFormat('DD-MM-YYYY')

db.externalPoolClassName=org.openbravo.apachejdbcconnectionpool.JdbcExternalConnectionPool
db.pool.initialSize=1
db.pool.minIdle=5
db.pool.maxActive=100
db.pool.timeBetweenEvictionRunsMillis=60000
db.pool.minEvictableIdleTimeMillis=120000
db.pool.removeAbandoned=false
db.pool.testOnBorrow=true
db.pool.testWhileIdle=false
db.pool.testOnReturn=false
db.pool.validationQuery=SELECT 1 FROM DUAL
db.pool.validationInterval=30000
db.pool.jmxEnabled=false

tomcat.manager.url=http://localhost:8080/manager
tomcat.manager.username=admin
tomcat.manager.password=admin

minimizeJSandCSS=yes
sqlc.queryExecutionStrategy=optimized
authentication.class=
validate.model=false
isMinorVersion=false
safe.mode=false
strict.template.application=false
allow.root=true
test.environment=false
background.policy=default
login.trial.delay.increment=0.2
login.trial.delay.max=3
login.trial.user.lock=0
redis.host=
redis.yaml=
EOF

echo ">>> openbravo.properties written."

# ─────────────────────────────────────────────
# Start Tomcat in foreground
# ─────────────────────────────────────────────
echo ">>> Starting Tomcat on port 8080..."

# Redirigir logs de Etendo (log4j) a stdout para que aparezcan en CloudWatch
ETENDO_LOG="$CATALINA_HOME/logs/openbravo.log"
touch "$ETENDO_LOG" 2>/dev/null || true
tail -F "$ETENDO_LOG" 2>/dev/null &

exec "$CATALINA_HOME/bin/catalina.sh" run
