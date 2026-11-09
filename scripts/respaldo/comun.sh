#!/usr/bin/env bash
# Funciones compartidas por respaldar.sh y restaurar-replica.sh.

registrar() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
fallar() { registrar "ERROR: $*" >&2; exit 1; }

# La configuracion se busca en este orden: variable SEMANTIKA_RESPALDO_ENV, /etc/semantika/respaldo.env
# y respaldo.env junto al script. Trae secretos: debe tener permisos 600 y no se versiona.
cargar_configuracion() {
    local dir="$1" archivo
    for archivo in "${SEMANTIKA_RESPALDO_ENV:-}" /etc/semantika/respaldo.env "$dir/respaldo.env"; do
        if [[ -n "$archivo" && -f "$archivo" ]]; then
            # shellcheck disable=SC1090
            source "$archivo"
            registrar "Configuracion: $archivo"
            break
        fi
    done

    : "${RESPALDO_DIR:?Falta RESPALDO_DIR en la configuracion}"
    : "${CLAVE_ARCHIVO:?Falta CLAVE_ARCHIVO en la configuracion}"
    : "${PG_USUARIO:=postgres}"
    : "${PG_BASE:=colegio_db}"
    : "${MONGO_BASE:=colegio_mongo}"
    # backups_bd guarda los pg_dump del backend: incluirla duplicaria PostgreSQL en cada copia.
    : "${MONGO_EXCLUIR:=backups_bd}"
    : "${RETENCION_DIAS:=30}"
    : "${RETENCION_MENSUAL_DIAS:=730}"
    : "${CURL_IMAGEN:=curlimages/curl:8.10.1}"
    : "${QDRANT_API_KEY:=}"
    : "${RCLONE_DESTINO:=}"

    [[ -r "$CLAVE_ARCHIVO" ]] || fallar "No se puede leer la clave de cifrado: $CLAVE_ARCHIVO"
    [[ -s "$CLAVE_ARCHIVO" ]] || fallar "La clave de cifrado esta vacia: $CLAVE_ARCHIVO"
}

requerir() {
    local c
    for c in "$@"; do
        command -v "$c" >/dev/null 2>&1 || fallar "Falta el comando: $c"
    done
}

# Qdrant no publica su API fuera del servidor: curl corre en un contenedor auxiliar que comparte
# la red del contenedor. La clave va por stdin para que no aparezca en la lista de procesos.
qdrant_api() {
    local contenedor="$1" clave="$2"; shift 2
    if [[ -n "$clave" ]]; then
        printf 'header = "api-key: %s"\n' "$clave" |
            docker run --rm -i --network "container:$contenedor" "$CURL_IMAGEN" -fsS -K - "$@"
    else
        docker run --rm --network "container:$contenedor" "$CURL_IMAGEN" -fsS "$@"
    fi
}

qdrant_colecciones() {
    qdrant_api "$1" "$2" http://localhost:6333/collections |
        grep -o '"name":"[^"]*"' | cut -d'"' -f4 | sort || true
}

qdrant_puntos() {
    qdrant_api "$1" "$2" -X POST -H 'Content-Type: application/json' -d '{"exact":true}' \
        "http://localhost:6333/collections/$3/points/count" | grep -o '"count":[0-9]*' | cut -d: -f2
}

# Conteos por tabla y coleccion, en formato almacen<TAB>nombre<TAB>cantidad, ordenados.
conteos_postgres() {
    local contenedor="$1" usuario="$2" base="$3" tabla n tablas
    # La lista se lee completa antes del bucle: un docker exec dentro de un while se comeria la
    # entrada estandar y el manifiesto saldria con una sola tabla.
    tablas=$(docker exec "$contenedor" psql -U "$usuario" -d "$base" -At -c \
        "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1" | tr -d '\r')
    [[ -n "$tablas" ]] || fallar "PostgreSQL no devolvio ninguna tabla"
    for tabla in $tablas; do
        n=$(docker exec "$contenedor" psql -U "$usuario" -d "$base" -At -c "SELECT count(*) FROM public.\"$tabla\"" | tr -d '\r')
        printf 'postgres\t%s\t%s\n' "$tabla" "$n"
    done
}

# El script entra por stdin y la URI va dentro de el, nunca como argumento de un proceso.
conteos_mongo() {
    local contenedor="$1" uri="$2" excluir="$3"
    docker exec -i "$contenedor" sh -c 'umask 077; cat > /tmp/.conteo.js; mongosh --nodb --quiet /tmp/.conteo.js; r=$?; rm -f /tmp/.conteo.js; exit $r' <<EOF | tr -d '\r' | sort
const d = connect("$uri");
d.getCollectionNames().filter(c => c !== "$excluir" && !c.startsWith("system.")).forEach(c => print("mongo\t" + c + "\t" + d.getCollection(c).countDocuments({})));
EOF
}

conteos_qdrant() {
    local contenedor="$1" clave="$2" c
    for c in $(qdrant_colecciones "$contenedor" "$clave"); do
        printf 'qdrant\t%s\t%s\n' "$c" "$(qdrant_puntos "$contenedor" "$clave" "$c")"
    done
}

# gpg con un directorio propio: en cron no hay agente ni sesion de usuario. Va en /tmp porque
# el socket de su agente no admite rutas largas.
gnupg_temporal() { mktemp -d /tmp/sk-gpg.XXXXXX; }

gnupg_borrar() {
    gpgconf --homedir "$1" --kill gpg-agent >/dev/null 2>&1 || true
    rm -rf "$1"
}

gpg_lote() {
    local homedir="$1"; shift
    gpg --homedir "$homedir" --batch --yes --quiet --pinentry-mode loopback --passphrase-file "$CLAVE_ARCHIVO" "$@"
}
