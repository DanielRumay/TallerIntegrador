#!/usr/bin/env bash
# Restaura un respaldo en una replica aislada y demuestra que la copia sirve.
#
#   restaurar-replica.sh <archivo.tar.gpg> [--conservar]
#
# La replica usa las mismas imagenes que produccion, en una red de Docker sin salida a internet y
# sin puertos publicados. NUNCA escribe en los contenedores de produccion: solo crea y borra
# recursos propios llamados replica-<id>. Al terminar compara cada conteo con el manifiesto y
# escribe un acta con el tiempo medido. Con --conservar la replica queda encendida para una
# auditoria o un reclamo; sin el, se destruye.
set -Eeuo pipefail
umask 077

DIR_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=comun.sh
source "$DIR_SCRIPT/comun.sh"

[[ $# -ge 1 ]] || { echo "Uso: $0 <archivo.tar.gpg> [--conservar]" >&2; exit 2; }
ARCHIVO="$1"
CONSERVAR=false
[[ "${2:-}" == "--conservar" ]] && CONSERVAR=true

cargar_configuracion "$DIR_SCRIPT"
requerir docker gpg tar sha256sum awk
[[ -f "$ARCHIVO" ]] || fallar "No existe el archivo: $ARCHIVO"

ID="$(date +%Y%m%d%H%M%S)"
RED="replica-$ID"
PG="replica-pg-$ID"
MONGO="replica-mongo-$ID"
QDRANT="replica-qdrant-$ID"
mkdir -p "$RESPALDO_DIR/actas"
TRABAJO="$(mktemp -d "$RESPALDO_DIR/.restaura.XXXXXX")"
DATOS="$TRABAJO/datos"
GNUPG="$(gnupg_temporal)"
mkdir -p "$DATOS"

destruir_replica() {
    docker rm -fv "$PG" "$MONGO" "$QDRANT" >/dev/null 2>&1 || true
    docker network rm "$RED" >/dev/null 2>&1 || true
}

limpiar() {
    local codigo=$?
    gnupg_borrar "$GNUPG"
    rm -rf "$TRABAJO"
    if [[ "$CONSERVAR" == true && $codigo -eq 0 ]]; then
        registrar "Replica conservada. Para destruirla: docker rm -fv $PG $MONGO $QDRANT && docker network rm $RED"
    else
        destruir_replica
    fi
    [[ $codigo -eq 0 ]] || registrar "RESTAURACION FALLIDA (codigo $codigo)" >&2
}
trap limpiar EXIT

esperar() {
    local que="$1" i; shift
    for i in $(seq 1 90); do
        if "$@" >/dev/null 2>&1; then return 0; fi
        sleep 2
    done
    fallar "$que no arranco a tiempo"
}

INICIO=$(date +%s)

registrar "Paso 1: integridad"
if [[ -f "$ARCHIVO.sha256" ]]; then
    (cd "$(dirname "$ARCHIVO")" && sha256sum -c --status "$(basename "$ARCHIVO").sha256") ||
        fallar "La suma SHA-256 no coincide: el archivo esta danado o fue alterado"
    INTEGRIDAD="SHA-256 correcta"
else
    INTEGRIDAD="sin archivo .sha256"
    registrar "ADVERTENCIA: no hay archivo .sha256; solo se valida al descifrar"
fi

registrar "Paso 2: descifrado en zona temporal"
gpg_lote "$GNUPG" --decrypt "$ARCHIVO" | tar -C "$DATOS" -xf - ||
    fallar "No se pudo descifrar (clave incorrecta o archivo danado)"
# shellcheck disable=SC1091
source "$DATOS/metadatos.env"

registrar "Paso 3: replica aislada ($PG_IMAGEN, $MONGO_IMAGEN, $QDRANT_IMAGEN)"
docker network create --internal "$RED" >/dev/null
CLAVE_PG="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')"
docker run -d --name "$PG" --network "$RED" -e POSTGRES_USER="$PG_USUARIO" -e POSTGRES_PASSWORD="$CLAVE_PG" -e POSTGRES_DB="$PG_BASE" "$PG_IMAGEN" >/dev/null
docker run -d --name "$MONGO" --network "$RED" "$MONGO_IMAGEN" >/dev/null
docker run -d --name "$QDRANT" --network "$RED" "$QDRANT_IMAGEN" >/dev/null
esperar PostgreSQL docker exec "$PG" pg_isready -h 127.0.0.1 -U "$PG_USUARIO" -d "$PG_BASE"
esperar MongoDB docker exec "$MONGO" mongosh --quiet --eval 'db.runCommand({ping:1})'
esperar Qdrant docker run --rm --network "container:$QDRANT" "$CURL_IMAGEN" -fsS http://localhost:6333/readyz
ARRANQUE=$(date +%s)

registrar "Paso 4: restauracion"
docker exec -i "$PG" pg_restore -U "$PG_USUARIO" -d "$PG_BASE" --no-owner --no-privileges --exit-on-error < "$DATOS/postgres.dump"
docker exec -i "$MONGO" mongorestore --gzip --archive --quiet < "$DATOS/mongo.archive.gz"
for snapshot in "$DATOS"/qdrant/*.snapshot; do
    [[ -e "$snapshot" ]] || continue
    coleccion="$(basename "$snapshot" .snapshot)"
    docker run --rm -i --network "container:$QDRANT" "$CURL_IMAGEN" -fsS -X POST \
        "http://localhost:6333/collections/$coleccion/snapshots/upload?priority=snapshot" \
        -F "snapshot=@-;filename=$coleccion.snapshot" < "$snapshot" >/dev/null
done

registrar "Paso 5: comparacion con el manifiesto"
{
    conteos_postgres "$PG" "$PG_USUARIO" "$PG_BASE"
    conteos_mongo "$MONGO" "mongodb://localhost:27017/$MONGO_BASE" "$MONGO_EXCLUIR"
    conteos_qdrant "$QDRANT" ""
} > "$DATOS/replica.tsv"

FIN=$(date +%s)
DIFERENCIAS=$(awk -F'\t' '
    NR == FNR { esperado[$1 FS $2] = $3; next }
    { visto[$1 FS $2] = 1; if (!(($1 FS $2) in esperado) || esperado[$1 FS $2] != $3) print $1 " " $2 ": esperado " (($1 FS $2) in esperado ? esperado[$1 FS $2] : "ausente") ", obtenido " $3 }
    END { for (k in esperado) if (!(k in visto)) { split(k, p, FS); print p[1] " " p[2] ": esperado " esperado[k] ", ausente en la replica" } }
' "$DATOS/manifiesto.tsv" "$DATOS/replica.tsv")

TOTAL_OBJETOS=$(wc -l < "$DATOS/manifiesto.tsv" | tr -d ' ')
TOTAL_FILAS=$(awk -F'\t' '{ s += $3 } END { print s + 0 }' "$DATOS/manifiesto.tsv")
if [[ -z "$DIFERENCIAS" ]]; then RESULTADO="CORRECTO"; else RESULTADO="CON DIFERENCIAS"; fi

ACTA="$RESPALDO_DIR/actas/acta_restauracion_$ID.md"
{
    echo "# Acta de restauracion $ID"
    echo
    echo "- Copia: $(basename "$ARCHIVO") (tomada el $FECHA)"
    echo "- Integridad: $INTEGRIDAD"
    echo "- Imagenes: $PG_IMAGEN, $MONGO_IMAGEN, $QDRANT_IMAGEN"
    echo "- Tablas, colecciones e indices comparados: $TOTAL_OBJETOS"
    echo "- Registros comparados: $TOTAL_FILAS"
    echo "- Tiempo de arranque de la replica: $((ARRANQUE - INICIO)) s"
    echo "- Tiempo de restauracion y comparacion: $((FIN - ARRANQUE)) s"
    echo "- Tiempo total medido: $((FIN - INICIO)) s"
    echo "- Resultado: $RESULTADO"
    if [[ -n "$DIFERENCIAS" ]]; then
        echo
        echo "## Diferencias"
        echo
        echo "$DIFERENCIAS" | sed 's/^/- /'
    fi
} > "$ACTA"

registrar "Acta: $ACTA"
registrar "RESULTADO: $RESULTADO ($TOTAL_OBJETOS objetos, $TOTAL_FILAS registros, $((FIN - INICIO)) s)"
[[ "$RESULTADO" == "CORRECTO" ]] || { echo "$DIFERENCIAS" >&2; exit 3; }
