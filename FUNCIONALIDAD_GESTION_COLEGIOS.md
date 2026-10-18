# Gestión de colegios y permisos de administración

> Estado: **implementado y validado** (17/10/2026), sin desplegar todavía.
> Repositorios: backend `TallerIntegrador` (rama `develop`) y frontend `smart-assess` (rama `main`).

## 1. Qué hace

Una sola instalación de Semantika atiende ahora a **varios colegios**, y cada uno ve solo lo suyo:

- **Superadministrador (`SUPERADMIN`):** es el equipo que opera la plataforma. Crea colegios, les asigna su administrador principal y puede activarlos o desactivarlos. **No ve datos académicos.**
- **Administrador del colegio (`ADMIN`):** solo ve y gestiona su colegio: docentes, estudiantes, cursos, notas, uso, métricas y auditoría.
- **Miniadministradores:** son administradores con **solo algunos permisos**. Los crea un administrador que tenga el permiso de gestionar administradores.
- **Docentes y estudiantes:** funcionan igual que antes, siempre dentro de su colegio.

## 2. Permisos

| Permiso | Qué habilita | Endpoints |
|---|---|---|
| `CUENTAS_GESTIONAR` | Crear (individual o masivo), eliminar, bloquear y desbloquear docentes y estudiantes | `/admin/usuarios/**` (salvo cambiar contraseña) |
| `CONTRASENAS_RESTABLECER` | Restablecer contraseñas de docentes y estudiantes | `PUT /admin/usuarios/{id}/change-password` |
| `CURSOS_GESTIONAR` | Lo que el administrador hace sobre cursos, matrículas y co-docentes | `/cursos/**`, `/archivos/**`, banco de preguntas, curación, validación del juez |
| `NOTAS_VER` | Analíticas de rendimiento y exportación de notas | `/intentos/todos`, `/intentos/semana/{id}`, `/rendimiento/**` |
| `USO_VER` | Panel de uso de la plataforma | `/admin/uso` |
| `METRICAS_IA_VER` | Métricas de IA y su CSV | `/metricas/**` |
| `AUDITORIA_VER` | Registro de auditoría | `/admin/auditoria` |
| `ADMINISTRADORES_GESTIONAR` | Crear miniadministradores y asignarles permisos | `/admin/administradores/**` |

El **administrador principal** tiene los 8 permisos. Cada permiso llega a Spring Security como la authority `PERM_<NOMBRE>`.

## 3. Reglas de seguridad

1. **Aislamiento entre colegios:** nadie lee ni modifica datos de otro colegio. Pedir un recurso de otro colegio por su id devuelve **404**, igual que si no existiera, para no revelar que existe.
2. **Anti-escalada de privilegios:**
   - Nadie otorga **ni quita** un permiso que no tiene.
   - Nadie se modifica, bloquea ni elimina a sí mismo.
   - Los administradores principales solo los gestiona el SUPERADMIN.
   - Todo administrador tiene al menos un permiso.
3. **Los permisos se leen de la base en cada petición, no del token**, con un caché de 30 s que se invalida en cada cambio. Por eso:
   - Otorgar o quitar un permiso **rige en la siguiente petición**, sin volver a iniciar sesión.
   - **Bloquear o eliminar una cuenta, o desactivar un colegio, corta la sesión abierta**: la siguiente petición responde 401. Esto cumple la revocación de sesiones (RFS-08).
4. **401 frente a 403:**
   - **401** significa que no hay sesión válida (token ausente, vencido o revocado). Solo en ese caso el frontend cierra la sesión.
   - **403** significa que hay sesión pero falta el permiso. El frontend lo informa sin cerrar la sesión.
5. **Auditoría:** se registran la creación de colegios y administradores, los cambios de permisos (antes y después), los bloqueos y las eliminaciones. Cada fila guarda su colegio, y cada administrador ve solo la auditoría del suyo.

### Cómo se aplica el aislamiento (tres capas)
1. **Filtro explícito:** cada consulta del administrador filtra por su colegio (usuarios, intentos, uso, auditoría, métricas, validación del juez y archivos).
2. **`ColegioGuard`:** toda operación que busca un recurso por id verifica que sea del mismo colegio. Esto cubre `findById` y las consultas nativas, que el filtro de Hibernate no alcanza.
3. **Filtro de Hibernate `filtroColegio`:** sobre `Usuario` y `Curso`, se activa en cada petición y actúa como red de seguridad si alguna consulta JPQL olvidara filtrar.
4. **Prueba que impide regresiones:** `SinConsultasGlobalesTest` falla si alguien vuelve a escribir `findAll()` sobre usuarios, cursos o intentos sin justificarlo.

### Brechas cerradas de paso (RFS-03)
Ahora los endpoints de cursos que reciben un `courseId` verifican pertenencia: el docente debe ser titular o co-docente, el estudiante debe estar matriculado y el administrador debe ser del mismo colegio. Antes no lo hacían:
- listar y crear semanas
- editar y eliminar el curso
- matricular y retirar estudiantes
- listar alumnos
- portada
- `/cursos/docente/{id}`, que dejaba ver los cursos de otro docente cambiando el id

Además:
- Un docente solo busca y matricula estudiantes de su colegio.
- Un co-docente debe ser del mismo colegio.

## 4. Cambios en la base de datos

Hibernate (`ddl-auto=update`) crea las tablas y columnas nuevas al arrancar. La migración de datos la hace `MigracionColegios`, que es **idempotente**: correrla de nuevo no cambia nada.

| Objeto | Cambio |
|---|---|
| `colegio` (tabla nueva) | `id`, `nombre` (único), `activo`, `creado_en` |
| `usuario` | + `colegio_id` (FK; NULL solo para SUPERADMIN), + `creado_por_id`, + `admin_principal` |
| `usuario_permiso` (tabla nueva) | `usuario_id`, `permiso`: los permisos de cada ADMIN |
| `curso` | + `colegio_id` (FK) |
| `registro_auditoria` | + `colegio_id` |
| `muestra_validacion` | + `colegio_id` |
| CHECK `usuario_rol_check`, `usuario_permiso_permiso_check` | **Se eliminan.** Hibernate 6 los fija con los valores del enum y no los actualiza, así que el rol nuevo `SUPERADMIN` fallaría al guardarse. El enum de Java sigue restringiendo los valores. |

**Lo que hace la migración en el primer arranque:**
1. Crea el colegio inicial, con el nombre de `COLEGIO_INICIAL` (por defecto "Colegio Privado Andrés Avelino Cáceres").
2. Le asigna **todos** los usuarios y cursos existentes y la auditoría anterior.
3. Convierte a los ADMIN existentes en **principales con los 8 permisos**, de modo que nadie pierde el acceso que tenía.
4. Si se definen `SUPERADMIN_CORREO` y `SUPERADMIN_PASSWORD_INICIAL`, crea esa cuenta SUPERADMIN con cambio de contraseña obligatorio. Nunca convierte una cuenta existente.

### Variables de entorno nuevas (opcionales)
| Variable | Uso |
|---|---|
| `SUPERADMIN_CORREO` | Correo de la cuenta superadministradora que se crea al arrancar |
| `SUPERADMIN_PASSWORD_INICIAL` | Su contraseña inicial (mínimo 8 caracteres). Después de crearla, quítala del `.env`. |
| `COLEGIO_INICIAL` | Nombre del colegio al que se asignan los datos existentes |

## 5. API nueva

| Método y ruta | Quién | Qué hace |
|---|---|---|
| `GET /superadmin/colegios` | SUPERADMIN | Lista los colegios con sus conteos de cuentas |
| `POST /superadmin/colegios` | SUPERADMIN | Crea un colegio y su administrador principal |
| `POST /superadmin/colegios/{id}/administrador` | SUPERADMIN | Agrega otro administrador principal |
| `PUT /superadmin/colegios/{id}/estado` | SUPERADMIN | Activa o desactiva un colegio (`{activo}`) |
| `GET /admin/administradores/permisos` | ADMIN | Catálogo de permisos con su descripción |
| `GET /admin/administradores` | `ADMINISTRADORES_GESTIONAR` | Administradores del colegio |
| `POST /admin/administradores` | `ADMINISTRADORES_GESTIONAR` | Crea un miniadministrador |
| `PUT /admin/administradores/{id}/permisos` | `ADMINISTRADORES_GESTIONAR` | Cambia sus permisos |
| `PUT /admin/administradores/{id}/toggle-block` | `ADMINISTRADORES_GESTIONAR` | Lo bloquea o desbloquea |
| `DELETE /admin/administradores/{id}` | `ADMINISTRADORES_GESTIONAR` | Lo elimina |
| `GET /auth/me` | Cualquiera con sesión | Rol, colegio y permisos **vigentes** |

El login (`POST /auth/login`) ahora también devuelve `colegio`, `permisos` y `adminPrincipal`, y rechaza con 403 a los usuarios de un colegio desactivado.

## 6. Frontend
- **Menú del administrador:** muestra solo las secciones permitidas y el nombre del colegio. Se actualiza con `/auth/me` sin volver a iniciar sesión.
- **Rutas:** cada ruta de `/admin/*` exige su permiso (`RequierePermiso`). Si un miniadministrador entra por la URL a una sección ajena, se le lleva a la primera que sí puede ver.
- **Panel de Usuarios:** los botones de crear, bloquear, eliminar y cambiar contraseña, las analíticas y "Descargar notas" aparecen según los permisos. Cada consulta se hace solo si hay permiso, para no provocar un 403.
- **Pantalla nueva Administradores** (`/admin/administradores`): permite crear miniadministradores con casillas de permisos (las que no tiene quien opera aparecen deshabilitadas), editar permisos, bloquear y eliminar.
- **Pantalla nueva Colegios** (`/superadmin`): permite crear colegios con su principal, agregar principales y activar o desactivar colegios.
- **Cliente HTTP:** cierra la sesión solo ante un 401, ya no ante un 403.

## 7. Pruebas y validación

**Pruebas automatizadas:**
- Backend: **375 pruebas, 0 fallos**. El único error es `contextLoads`, que necesita las bases levantadas y ya fallaba antes.
- Pruebas nuevas del backend:
  - `ContextoUsuarioServiceTest` (5): revocación por bloqueo, baja o colegio desactivado, caché e invalidación, y permisos como authorities.
  - `ColegioGuardTest` (4): mismo colegio, otro colegio (404), SUPERADMIN y tareas internas.
  - `AdministradoresServiceTest` (10): anti-escalada (otorgar o quitar sin tener, sí mismo, principal, otro colegio) y auditoría.
  - `AdminUserServiceColegioTest` (5): listado, creación, operaciones sobre otro colegio y revocación al bloquear.
  - `SinConsultasGlobalesTest` (1): impide los `findAll()` globales.
  - Se actualizaron `AuthServiceTest`, `AccesoServiceTest` e `IntentosPorPertenenciaTest` al modelo con colegios.
- Frontend: **23 pruebas** (5 nuevas de permisos). El build termina bien y no aparecen errores nuevos de TypeScript.

**Validación de punta a punta** (backend real, bases temporales en Docker, dos colegios): **31 de 31 verificaciones**.
- **Superadmin:**
  - Inicia sesión.
  - Crea un colegio con su administrador principal.
  - Agrega un administrador principal a otro colegio.
  - No accede a datos académicos.
- **Aislamiento entre colegios:**
  - Cada administrador lista solo a sus usuarios.
  - Bloquear, cambiar la contraseña o eliminar a un usuario de otro colegio da 404.
  - Un correo repetido en otro colegio da 400, no 500.
- **Cursos:**
  - El docente busca y matricula solo a estudiantes de su colegio.
  - Otro docente recibe 403 y otro administrador recibe 404 sobre un curso ajeno.
  - Un docente no puede ver los cursos de otro cambiando el id.
- **Miniadministradores y revocación:**
  - El miniadministrador solo entra a sus secciones.
  - Un permiso otorgado o quitado rige en la siguiente petición.
  - Las reglas anti-escalada se cumplen.
  - Bloquear corta el token vigente (401).
- **Auditoría y uso:** la auditoría y el panel de uso de cada colegio no muestran nada del otro.
- **Desactivar un colegio:** cierra sus sesiones (401), impide el login (403) y no afecta al otro colegio.

**Migración sobre datos existentes:** se levantó la **versión anterior** del backend, se cargaron datos y luego se arrancó la versión nueva encima.
- Todos los usuarios, cursos y la auditoría quedaron asignados al colegio inicial.
- El admin antiguo quedó como principal con sus 8 permisos y entra normalmente.
- La restricción CHECK de rol desapareció.
- El segundo arranque no cambió nada.

**Recorrido visual:** se usaron el frontend y el backend reales con Playwright.
- El principal ve las 5 secciones.
- El miniadministrador con `USO_VER` solo ve "Uso" y, si entra a otra sección por la URL, se le redirige sin perder la sesión.
- El superadmin llega a su panel.
- Ninguna petición devolvió error.

## 8. Despliegue (fuera del horario de clase)
1. **Hacer un respaldo** antes de cualquier cambio: `pg_dump` de la base de producción.
2. **Backend:** hacer `git pull` y reconstruir; si quieres crear el superadmin en este paso, agrega `SUPERADMIN_CORREO` y `SUPERADMIN_PASSWORD_INICIAL` al `.env`. En el log debe aparecer `[COLEGIOS] Colegio inicial creado...`.
3. **Comprobar:** el administrador actual entra y ve lo mismo que antes, ahora con la pestaña "Administradores".
4. **Frontend:** hacer push para que Vercel publique. Despliega primero el backend: el frontend nuevo usa `/auth/me` y los permisos del login.
5. **Superadmin:** entra con la contraseña inicial, cámbiala y **quita `SUPERADMIN_PASSWORD_INICIAL` del `.env`**.

**Reversión:** restaura el respaldo y vuelve a la imagen anterior. Las columnas nuevas no molestan a la versión anterior si quedaran.

## 9. Limitaciones conocidas
- **Métricas de IA:** los eventos sin usuario asociado no se pueden atribuir a un colegio, así que no aparecen en las métricas de ningún administrador.
- **Contraseñas de administradores:** la de un administrador no se restablece desde la interfaz. Si un principal la olvida, el SUPERADMIN le agrega al colegio otro principal.
- **Cursos:** un curso pertenece para siempre al colegio de su docente titular; no se mueven cursos entre colegios.
