# Cambios de seguridad: qué se hace y por qué

Registro de los cambios de endurecimiento de Semantika. Cada cambio indica el problema que resuelve, qué se modificó y en qué estado está.

| # | Cambio | Dónde | Estado |
|---|---|---|---|
| 0 | Exigir la contraseña temporal en el primer cambio de contraseña | Código (backend y frontend) | Hecho en código, pendiente de desplegar |
| 1 | Cambiar la contraseña inicial del superadmin | Aplicación | Pendiente |
| 2 | SSH solo con llave, fail2ban y actualizaciones automáticas | Servidor | Pendiente |
| 3 | Contraseña en MongoDB | Servidor | Pendiente |
| 4 | Respaldos cifrados y fuera del servidor | Servidor | Pendiente |

---

## 0. Contraseña temporal obligatoria en `/auth/setup-password`

**Problema.** El endpoint del primer cambio de contraseña es público, porque el usuario todavía no tiene sesión. Solo pedía el correo y la contraseña nueva. Cualquier persona que conociera el correo de una cuenta con `requires_password_setup = true` podía fijarle una contraseña y quedarse con la cuenta. Eso incluía al superadmin, que se crea con esa marca y controla todos los colegios, y a los alumnos, cuyos correos siguen un patrón fácil de adivinar.

**Cambio.**
- `AuthService.setupPassword` recibe ahora la contraseña temporal y la verifica con BCrypt antes de aceptar la nueva.
- Los intentos fallidos suman al mismo contador del login: al quinto, la cuenta se bloquea.
- Una cuenta bloqueada no puede cambiar su contraseña por esta vía.
- La contraseña nueva debe ser distinta de la temporal.
- `AuthController` devuelve 400 si la contraseña es incorrecta y 403 si la cuenta está bloqueada, en vez de un error genérico.
- Frontend: el login pasa la contraseña temporal a la pantalla de configuración en el estado de navegación, que vive en memoria y nunca en la URL. Si la página se recarga y ese estado se pierde, el formulario pide la contraseña temporal.
- Frontend: al terminar, el superadmin ahora va a `/superadmin`; antes terminaba en `/admin`.

**Pruebas.** `AuthServiceTest` incorpora 4 casos nuevos:
- sin contraseña temporal no cambia nada;
- al quinto intento fallido la cuenta se bloquea;
- con la temporal correcta la contraseña cambia;
- en una cuenta ya configurada no hace nada.

Resultado: 8 de 8 pruebas en verde.

**Despliegue.** El backend y el frontend deben subirse juntos. Con solo uno de los dos actualizado, el primer cambio de contraseña deja de funcionar.

**Norma.** Ley 29733, deber de seguridad del tratamiento. NTP-ISO/IEC 27001:2022, controles 5.17 (información de autenticación) y 8.5 (autenticación segura).

---

## 1. Contraseña inicial del superadmin

**Problema.** La cuenta se crea con la contraseña de la variable `SUPERADMIN_PASSWORD_INICIAL`. Esa contraseña queda escrita en el `.env` del servidor y es conocida por más de una persona.

**Cambio.** Con el cambio 0 desplegado y `VITE_EXIGIR_CAMBIO_CONTRASENA=true` en Vercel, el primer ingreso del superadmin obliga a definir una contraseña nueva. Después se recomienda borrar `SUPERADMIN_PASSWORD_INICIAL` del `.env`, porque la cuenta ya existe y la variable no vuelve a usarse.

**Norma.** ISO 27001, control 5.17.

---

## 2. Acceso SSH

**Problema.** El servidor acepta entrar por SSH con contraseña. Internet recibe intentos automáticos de adivinar contraseñas a toda hora, y no hay nada que bloquee a quien insiste. Los parches de seguridad del sistema dependen de que alguien los instale a mano.

**Cambio.**
- Entrada solo con llave `ed25519`. La llave privada nunca sale del equipo del administrador.
- `PasswordAuthentication no` y `PermitRootLogin prohibit-password`, en `/etc/ssh/sshd_config.d/00-semantika.conf`. Ese archivo se lee antes que el de cloud-init, así que no lo pisa.
- `fail2ban` con la jaula `sshd`: 5 intentos fallidos significan una hora de bloqueo para esa IP.
- `unattended-upgrades`, que instala automáticamente solo las actualizaciones de seguridad de Ubuntu.

**Precaución.** Antes de desactivar las contraseñas hay que comprobar el ingreso con la llave desde otra sesión. Si algo falla, se entra por la consola KVM del panel de OVH.

**Norma.** ISO 27001, controles 8.5 (autenticación segura), 8.8 (gestión de vulnerabilidades técnicas) y 8.16 (monitoreo).

---

## 3. Autenticación en MongoDB

**Problema.** MongoDB funciona sin usuario ni contraseña. No está expuesto a internet, pero cualquier proceso o contenedor del servidor puede leer y modificar sus datos: materiales, conversaciones con Aria y respaldos.

**Cambio.**
- Respaldo previo con `mongodump`.
- Un usuario `admin_semantika` (rol `root`), solo para mantenimiento.
- Un usuario `semantika_app` con `readWrite` únicamente sobre `colegio_mongo`. Es el mínimo privilegio que necesita la aplicación.
- MongoDB arranca con `--auth`.
- La URI de conexión del backend incluye el usuario de la aplicación. La contraseña vive solo en el `.env`.

No hace falta cambiar código: Spring toma las credenciales desde la URI.

**Norma.** ISO 27001, controles 5.15 (control de acceso), 8.2 (derechos de acceso privilegiado) y 8.3 (restricción del acceso a la información).

---

## 4. Respaldos cifrados y fuera del servidor

**Problema.** El respaldo de PostgreSQL (`pg_dump`) se guarda sin cifrar dentro de MongoDB, en el mismo servidor. Si el servidor se pierde, se pierden los datos y también sus respaldos. Y quien acceda al servidor puede leer el respaldo completo.

**Cambio (por implementar).** Un script nocturno que:
1. genera el respaldo de PostgreSQL y de MongoDB;
2. lo cifra con `age` usando una llave **pública**. La llave privada queda solo fuera del servidor, así que aun con el servidor comprometido los respaldos no se pueden abrir;
3. lo copia a un almacenamiento externo;
4. conserva 30 días de respaldos y borra los más antiguos.

**Norma.** ISO 27001, controles 8.13 (respaldo de la información) y 8.24 (uso de criptografía). Ley 29733, deber de seguridad.

---

## Fuera de este documento

Siguen pendientes y tienen su propio seguimiento:
- cifrado del disco de datos con LUKS;
- derechos ARCO (RFS-10);
- política de conservación de datos (RFS-12);
- consentimiento de los padres para menores de 14 años.
