# Respaldo y restauración de Semantika

| Archivo | Para qué |
|---|---|
| `respaldar.sh` | Copia PostgreSQL, MongoDB y Qdrant en un archivo cifrado con AES-256 y lo verifica |
| `restaurar-replica.sh` | Restaura una copia en una réplica aislada, compara los conteos y escribe un acta |
| `respaldo.env.example` | Plantilla de la configuración |

`restaurar-replica.sh` no toca producción: solo crea y borra contenedores `replica-<id>`, en una red sin internet y sin puertos publicados.

## Instalación en el servidor

Requiere `docker`, `gpg`, `tar` y `sha256sum`. La primera ejecución descarga la imagen `curlimages/curl`.

```bash
sudo mkdir -p /etc/semantika /var/backups/semantika
openssl rand -hex 32 | sudo tee /etc/semantika/respaldo.clave > /dev/null
sudo cp respaldo.env.example /etc/semantika/respaldo.env
sudo chmod 600 /etc/semantika/respaldo.env /etc/semantika/respaldo.clave
```

Edita `respaldo.env` con los datos reales. **Guarda una copia de `respaldo.clave` fuera del servidor:** sin ella, ningún respaldo se puede abrir.

## Uso

```bash
sudo bash respaldar.sh
sudo bash restaurar-replica.sh /var/backups/semantika/semantika_<FECHA>.tar.gpg
```

Con `--conservar`, la réplica queda encendida para una auditoría o un reclamo. El acta queda en `RESPALDO_DIR/actas/`.

Cron sugerido, después del `pg_dump` de las 02:00 del backend:

```bash
0 3 * * * /bin/bash /opt/semantika/scripts/respaldo/respaldar.sh >> /var/log/semantika-respaldo.log 2>&1
```

Para la copia fuera del servidor, llena `RCLONE_DESTINO` o apunta tu copia actual a `RESPALDO_DIR`.

## Conservación

30 días (`RETENCION_DIAS`). La copia del día 1 de cada mes, 24 meses (`RETENCION_MENSUAL_DIAS`). La colección `backups_bd` se excluye porque guarda los `pg_dump` del backend.

## Estado

Probado el 8 de noviembre de 2026 en un entorno local con datos ficticios: restauró 34 objetos y 395 registros en 40 segundos, y rechazó un archivo alterado, una clave incorrecta y un conteo adulterado. Falta repetirlo en Linux con una copia de producción. Se ejecuta fuera del horario de clases porque la réplica usa memoria extra.
