#!/usr/bin/env bash
# Respaldo completo y cifrado de PostgreSQL, MongoDB y Qdrant.
#
# Deja en RESPALDO_DIR un archivo semantika_<fecha>.tar.gpg y su .sha256. Dentro va un manifiesto
# con los conteos por tabla, que restaurar-replica.sh compara para demostrar que la copia sirve.
# Si algo falla, no queda ningun archivo a medias ni datos sin cifrar, y sale con codigo distinto de 0.
#
# Cron sugerido (despues del pg_dump de las 02:00 del backend):
#   0 3 * * * /opt/semantika/scripts/respaldo/respaldar.sh >> /var/log/semantika-respaldo.log 2>&1
set -Eeuo pipefail
umask 077

DIR_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=comun.sh
source "$DIR_SCRIPT/comun.sh"
cargar_configuracion "$DIR_SCRIPT"
requerir docker gpg tar sha256sum

: "${PG_CONTENEDOR:?Falta PG_CONTENEDOR}"
: "${MONGO_CONTENEDOR:?Falta MONGO_CONTENEDOR}"
: "${MONGO_URI:?Falta MONGO_URI}"
: "${QDRANT_CONTENEDOR:?Falta QDRANT_CONTENEDOR}"

mkdir -p "$RESPALDO_DIR"

# mkdir es atomico y, a diferencia de flock, existe en cualquier sistema.
CANDADO="$RESPALDO_DIR/.en-curso"
mkdir "$CANDADO" 2>/dev/null || fallar "Ya hay un respaldo en curso (si no es asi, borra $CANDADO)"

FECHA="$(date +%Y%m%d_%H%M%S)"
TRABAJO="$(mktemp -d "$RESPALDO_DIR/.tmp.XXXXXX")"
DATOS="$TRABAJO/datos"
GNUPG="$(gnupg_temporal)"
mkdir -p "$DATOS/qdrant"

NOMBRE="semantika_$FECHA.tar.gpg"
DESTINO="$RESPALDO_DIR/$NOMBRE"

limpiar() {
    local codigo=$?
    gnupg_borrar "$GNUPG"
    rm -rf "$TRABAJO"
    rmdir "$CANDADO" 2>/dev/null || true
    if [[ $codigo -ne 0 ]]; then
        rm -f "$DESTINO" "$DESTINO.sha256"
        registrar "RESPALDO FALLIDO (codigo $codigo)" >&2
    fi
}
trap limpiar EXIT

INICIO=$(date +%s)
registrar "Inicio del respaldo $FECHA"

registrar "PostgreSQL: pg_dump"
docker exec "$PG_CONTENEDOR" pg_dump -U "$PG_USUARIO" -d "$PG_BASE" --format=custom --no-owner > "$DATOS/postgres.dump"
[[ -s "$DATOS/postgres.dump" ]] || fallar "El dump de PostgreSQL esta vacio"

registrar "MongoDB: mongodump"
docker exec -i "$MONGO_CONTENEDOR" sh -c 'umask 077; cat > /tmp/.respaldo.yaml; mongodump --config=/tmp/.respaldo.yaml --db="$0" --excludeCollection="$1" --gzip --archive --quiet; r=$?; rm -f /tmp/.respaldo.yaml; exit $r' \
    "$MONGO_BASE" "$MONGO_EXCLUIR" <<< "uri: \"$MONGO_URI\"" > "$DATOS/mongo.archive.gz"
[[ -s "$DATOS/mongo.archive.gz" ]] || fallar "El dump de MongoDB esta vacio"

registrar "Qdrant: instantaneas por coleccion"
for coleccion in $(qdrant_colecciones "$QDRANT_CONTENEDOR" "$QDRANT_API_KEY"); do
    snapshot=$(qdrant_api "$QDRANT_CONTENEDOR" "$QDRANT_API_KEY" -X POST "http://localhost:6333/collections/$coleccion/snapshots" |
        grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    [[ -n "$snapshot" ]] || fallar "Qdrant no creo la instantanea de $coleccion"
    qdrant_api "$QDRANT_CONTENEDOR" "$QDRANT_API_KEY" "http://localhost:6333/collections/$coleccion/snapshots/$snapshot" > "$DATOS/qdrant/$coleccion.snapshot"
    # Las instantaneas ocupan disco dentro del contenedor: ya estan en la copia.
    qdrant_api "$QDRANT_CONTENEDOR" "$QDRANT_API_KEY" -X DELETE "http://localhost:6333/collections/$coleccion/snapshots/$snapshot" >/dev/null
    [[ -s "$DATOS/qdrant/$coleccion.snapshot" ]] || fallar "La instantanea de $coleccion esta vacia"
done

registrar "Manifiesto de conteos"
{
    conteos_postgres "$PG_CONTENEDOR" "$PG_USUARIO" "$PG_BASE"
    conteos_mongo "$MONGO_CONTENEDOR" "$MONGO_URI" "$MONGO_EXCLUIR"
    conteos_qdrant "$QDRANT_CONTENEDOR" "$QDRANT_API_KEY"
} > "$DATOS/manifiesto.tsv"

# La replica debe levantarse con exactamente las mismas imagenes que produccion.
{
    echo "FECHA=$FECHA"
    echo "PG_USUARIO=$PG_USUARIO"
    echo "PG_BASE=$PG_BASE"
    echo "MONGO_BASE=$MONGO_BASE"
    echo "MONGO_EXCLUIR=$MONGO_EXCLUIR"
    echo "PG_IMAGEN=$(docker inspect --format '{{.Config.Image}}' "$PG_CONTENEDOR")"
    echo "MONGO_IMAGEN=$(docker inspect --format '{{.Config.Image}}' "$MONGO_CONTENEDOR")"
    echo "QDRANT_IMAGEN=$(docker inspect --format '{{.Config.Image}}' "$QDRANT_CONTENEDOR")"
} > "$DATOS/metadatos.env"

registrar "Cifrado AES-256"
tar -C "$DATOS" -cf - . | gpg_lote "$GNUPG" --symmetric --cipher-algo AES256 --compress-algo none --output "$TRABAJO/$NOMBRE"

# Se comprueba que el archivo cifrado se puede abrir y leer ANTES de darlo por bueno.
registrar "Verificacion del archivo cifrado"
gpg_lote "$GNUPG" --decrypt "$TRABAJO/$NOMBRE" | tar -tf - > "$TRABAJO/contenido.txt" ||
    fallar "El archivo cifrado no se pudo verificar"
grep -q 'manifiesto.tsv' "$TRABAJO/contenido.txt" || fallar "El archivo cifrado no tiene manifiesto"

mv "$TRABAJO/$NOMBRE" "$DESTINO"
(cd "$RESPALDO_DIR" && sha256sum "$NOMBRE" > "$NOMBRE.sha256")

if [[ -n "$RCLONE_DESTINO" ]]; then
    requerir rclone
    registrar "Copia externa: $RCLONE_DESTINO"
    rclone copy "$DESTINO" "$RCLONE_DESTINO"
    rclone copy "$DESTINO.sha256" "$RCLONE_DESTINO"
    rclone check "$RESPALDO_DIR" "$RCLONE_DESTINO" --one-way --include "$NOMBRE" ||
        fallar "La copia externa no coincide con la local"
fi

# Se conserva RETENCION_DIAS; la copia del dia 1 de cada mes, RETENCION_MENSUAL_DIAS.
registrar "Retencion"
find "$RESPALDO_DIR" -maxdepth 1 -type f \( -name 'semantika_*.tar.gpg' -o -name 'semantika_*.tar.gpg.sha256' \) \
    ! -name 'semantika_??????01_*' -mtime +"$RETENCION_DIAS" -delete
find "$RESPALDO_DIR" -maxdepth 1 -type f \( -name 'semantika_??????01_*.tar.gpg' -o -name 'semantika_??????01_*.tar.gpg.sha256' \) \
    -mtime +"$RETENCION_MENSUAL_DIAS" -delete

registrar "RESPALDO CORRECTO: $DESTINO ($(du -h "$DESTINO" | cut -f1), $(( $(date +%s) - INICIO )) s)"
