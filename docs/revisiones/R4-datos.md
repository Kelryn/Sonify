He leído ambos documentos. El repositorio solo contiene `docs/`, así que no hay código previo que condicione el diseño. Aquí va el informe.

---

# Informe de la capa de persistencia y datos — RitMute

## Veredicto general

La especificación está por encima de la media de un documento de fase 1: el modelo de porcentajes en lugar de pasos nativos (§4.2) es la decisión correcta y bien argumentada, la separación `:core:model` sin dependencias de Android habilita tests JVM rápidos, y el algoritmo de resolución de conflictos (§7.3) está lo bastante especificado como para implementarse tal cual.

Dicho eso, **el modelo de dominio de la §4 no es implementable como está** y tiene tres defectos que, si se codifican directamente, obligan a una migración de esquema dolorosa antes de la 1.0:

1. **El determinismo prometido en §7.3 depende de `profileId` autoincremental**, que no es estable entre dispositivos. Es decir: el criterio de desempate del núcleo del sistema se rompe exactamente en el escenario del CU-08 (exportar y restaurar en otro móvil). Dos móviles con la misma configuración pueden aplicar perfiles distintos. Es el defecto más grave del documento.
2. **`Schedule.endTime` con la regla "si `<=` startTime, cruza medianoche" es ambigua** (`start == end` no distingue entre 0 y 24 horas) y hace imposible expresar una franja de 24 h. Se arregla trivialmente con `startMinute` + `durationMinutes`.
3. **`AudioSnapshot` (§4.6) está sin definir** siendo la pieza de la que dependen RF-15 y RF-22. Y si se define reutilizando `VolumeSettings` (porcentajes), la restauración pierde información: el snapshot debe guardar **pasos nativos**, no porcentajes.

Además hay tres entidades que RF-06, RF-15 y RF-17 exigen persistir y que **no aparecen en la §4 en absoluto**: la activación manual vigente, la pausa global y el "perfil por defecto". Y hay una promesa que Android no permite cumplir: las **excepciones por contacto concreto** de la persona P3 / CU-06 no son expresables vía `NotificationManager.Policy` (detalle en el punto 12).

Nada de esto es irrecuperable — todo se corrige antes de escribir la primera entidad. Lo que sigue es la corrección concreta.

---

## Crítica del modelo de dominio

### 1. Identidad: `id: Long` no basta, hace falta `uuid` estable

**Problema.** `SoundProfile.id: Long` y `Schedule.id: Long` son claves locales autogeneradas. Al exportar/importar (CU-08, RF-36/37) los ids cambian. Esto rompe: (a) el desempate de §7.3 punto 4, (b) la posibilidad de re-importar sobre una instalación existente sin duplicar todo, (c) las referencias del historial a perfiles.

**Corrección.** Doble identidad, explícita:

```kotlin
@JvmInline value class ProfileId(val value: Long)   // rowid local, jamás sale de la app
data class SoundProfile(
    val id: ProfileId = ProfileId(0),
    val uuid: String,                                // UUID v4, identidad de negocio, viaja en el JSON
    ...
)
```

Regla: **el `id` no aparece nunca en el JSON de exportación ni en ningún criterio de ordenación observable por el usuario.** El `uuid` se genera en la creación y es inmutable de por vida (duplicar un perfil genera uno nuevo).

### 2. El desempate de §7.3 es no determinista entre dispositivos

**Problema.** "desempatar por `profileId` ascendente (determinismo)" da determinismo *dentro de un dispositivo*, no *entre dispositivos*. Tras un import, el orden de inserción decide qué perfil gana un solape. Contradice frontalmente el criterio de aceptación 6 ("ida y vuelta sin pérdida").

**Corrección.** Cadena de desempate íntegramente basada en datos estables:

1. `priority` descendente
2. duración de la franja ascendente (más específica gana)
3. `profile.createdAt` ascendente (el más antiguo gana)
4. `profile.uuid` comparado lexicográficamente (determinista y estable en cualquier dispositivo)

El paso 4 garantiza totalidad del orden sin recurrir jamás al rowid. Esto debe reflejarse en el documento de especificaciones, no solo en el código.

### 3. `Schedule`: `endTime` es ambiguo y no expresa 24 h

**Problema.** `endTime <= startTime ⇒ cruza medianoche` colapsa dos casos distintos en `start == end`: franja vacía o franja de día completo. Además obliga a aritmética modular en toda consulta SQL y en todo test.

**Corrección.**

```kotlin
data class Schedule(
    val id: ScheduleId = ScheduleId(0),
    val uuid: String,
    val profileUuid: String,
    val enabled: Boolean = true,
    val startMinuteOfDay: Int,     // 0..1439
    val durationMinutes: Int,      // 1..1440  (1440 = 24 h; 0 prohibido)
    val daysOfWeek: Set<DayOfWeek>,// no vacío; ISO-8601, MONDAY=1
    val priorityOverride: Int? = null,
    val label: String? = null,
)
val Schedule.endMinuteExclusive: Int get() = (startMinuteOfDay + durationMinutes) % 1440
val Schedule.crossesMidnight: Boolean get() = startMinuteOfDay + durationMinutes > 1440
```

Ventajas concretas: el cruce de medianoche deja de ser un caso especial (es simplemente `duration` que desborda), la duración para el desempate del punto 2 es un campo, no un cálculo, y la contención se calcula igual en Kotlin y en SQL. La UI sigue mostrando "23:00 – 07:00"; es solo la representación interna.

### 4. `daysOfWeek: Set<DayOfWeek>` debe persistirse como bitmask, no como texto

`Set<DayOfWeek>` es correcto en dominio. En la base de datos debe ser `days_mask INTEGER` con bit `1 shl (isoDayNumber - 1)`, rango válido `1..127`. Razón: permite filtrar en SQL (`WHERE (days_mask & :todayMask) != 0`) en lugar de traer todas las franjas a memoria en cada reconciliación (RF-31, cada 30 min), y hace que el invariante "no vacío" sea un `CHECK` de una línea. Guardarlo como `"MON,TUE,WED"` es el error clásico de esta categoría: no indexable, sensible a locale y frágil ante renombrados de enum.

### 5. Falta el modelo de vibración, que la §1 y la §2.1 prometen

La visión dice "los siete streams, el modo de timbre, **la vibración** y DND". La §2.1 lo repite. El modelo de la §4.1 solo tiene `ringerMode`. La vibración no está.

**Corrección.**

```kotlin
data class VibrationSettings(
    val vibrateWhenRinging: Boolean? = null,      // Settings.System.VIBRATE_WHEN_RINGING
    val vibrateOnNotification: Boolean? = null,
    val touchFeedback: Boolean? = null,           // Settings.System.HAPTIC_FEEDBACK_ENABLED
)
```

**Aviso que trasciende mi ámbito pero condiciona el modelo:** estos tres ajustes viven en `Settings.System` y escribirlos exige `WRITE_SETTINGS`, que es un permiso especial (`ACTION_MANAGE_WRITE_SETTINGS`) **no listado en la §8 de permisos**. O se añade a la tabla de permisos con su onboarding, o se recorta el alcance de vibración a lo que `AudioManager.setRingerMode(RINGER_MODE_VIBRATE)` permite y se corrige la §1. No dejéis los campos en el modelo sin resolver esta decisión: son columnas que quedarían siempre a `null`.

### 6. Dos formas distintas de decir "no tocar"

`VolumeSettings` usa `Int?` con `null = no tocar`; `ringerMode` usa un valor centinela `UNCHANGED` dentro del enum; `DndSettings.mode` también tiene `UNCHANGED`. Tres campos, dos convenciones.

**Corrección.** Una sola: **`null` significa "no tocar"** en todo el modelo. Eliminar `UNCHANGED` de `RingerMode` y de `DndMode`. Motivos: (a) en Room la nulabilidad es una propiedad de la columna verificable por el esquema, mientras que un centinela es una convención que hay que recordar; (b) `UNCHANGED` contamina todos los `when` exhaustivos del motor de audio con una rama que no es un modo; (c) en el JSON, `"ringerMode": null` se lee sin documentación, `"UNCHANGED"` no.

```kotlin
enum class RingerMode { NORMAL, VIBRATE, SILENT }   // el campo es RingerMode?
enum class DndMode { OFF, PRIORITY, ALARMS_ONLY, TOTAL_SILENCE }  // el campo es DndMode?
```

### 7. `ProfileOptions.gradualTransition` es redundante

`gradualTransition: Boolean` + `transitionSeconds: Int (0..60)` permiten el estado inconsistente `gradualTransition = true, transitionSeconds = 0`. Sobra el booleano: `transitionSeconds = 0` **es** "sin rampa". Un campo menos, un estado imposible menos, una columna menos que migrar.

### 8. Contradicción no resuelta entre `ringerMode` y `volumes.ring`

El modelo permite `ringerMode = NORMAL` con `volumes.ring = 0`, y `ringerMode = SILENT` con `volumes.ring = 80`. Son órdenes contradictorias, y peor: en Android el orden de aplicación cambia el resultado (poner `ring` a 0 fuerza de facto el modo vibración/silencio en muchos dispositivos, y `setRingerMode` reescribe el volumen de tono).

**Corrección**, a fijar en el dominio como invariante validado en el guardado y en la importación:

- Si `ringerMode == SILENT` o `VIBRATE` ⇒ `volumes.ring` se ignora y se normaliza a `null` al persistir.
- Si `ringerMode == NORMAL` ⇒ `volumes.ring == 0` es inválido; mínimo 1. La UI debe impedirlo y el importador corregirlo con una advertencia.

### 9. `DndSettings`: tipo colgante, campos que faltan y un identificador de sistema que hay que persistir

- `allowMessages: MessagePolicy` referencia un enum **que el documento nunca define**. Definirlo: `NONE | STARRED | CONTACTS | ANY`.
- Falta `allowConversations: ConversationPolicy` (`NONE | IMPORTANT | ANYONE`), disponible desde API 30 y parte de la política real.
- Falta `suppressedVisualEffects: Int` (bitmask de `NotificationManager.Policy.SUPPRESSED_EFFECT_*`): sin él no se puede pedir "no encender la pantalla de noche", que es justo el caso de Marc (P2).
- **Falta lo que el D3 de la investigación vende como diferenciador**: `ZenDeviceEffects` (escala de grises, atenuar fondo, suprimir ambient display). Si es el argumento de la Modes API, tiene que estar en el modelo.
- **Falta `zenRuleId: String?`.** `NotificationManager.addAutomaticZenRule()` devuelve un identificador de sistema. Sin persistirlo por perfil no se puede actualizar ni eliminar la regla después. Si un usuario borra un perfil con `useSystemMode = true` y no se guardó ese id, **queda un Modo huérfano en los ajustes del sistema, activándose para siempre, imposible de eliminar desde la app.** Es un fallo de integridad con consecuencias visibles fuera de la app.

### 10. `AudioSnapshot` (§4.6) está sin especificar y no puede usar porcentajes

Una frase ("estado completo del audio del sistema") no es un modelo, y es la base de RF-15/RF-22 y del CU-04 de Dani.

**Corrección.** El snapshot es un tipo **distinto** de `VolumeSettings` y guarda **pasos nativos más el máximo del dispositivo**:

```kotlin
data class StreamLevel(val steps: Int, val maxSteps: Int, val minSteps: Int)

data class AudioSnapshot(
    val capturedAt: Instant,
    val kind: SnapshotKind,                 // BASELINE | PRE_APPLY
    val ownerProfileUuid: String?,          // qué aplicación lo creó
    val levels: Map<AudioStream, StreamLevel>,
    val ringerMode: RingerMode,
    val interruptionFilter: Int,            // NotificationManager.INTERRUPTION_FILTER_*
    val vibrateWhenRinging: Boolean?,
    val policyBlob: String?,                // política DND serializada, opaca
)
```

Razón para no usar porcentajes: en un dispositivo de 7 pasos, `steps=3` → 42,857 % → se guarda 43 → se restaura `round(0,43×7) = 3`. Funciona por poco. Pero en cuanto se combina con `getStreamMinVolume` (API 28+, que es 1 para `STREAM_VOICE_CALL`) y con dispositivos de 15 pasos donde el usuario tenía un valor concreto, la restauración deja de ser idéntica. **Restaurar debe devolver exactamente lo que había, bit a bit.** Los porcentajes son para la configuración portable del usuario; los pasos nativos son para la restauración fiel. Son dos conceptos distintos que el documento fusiona.

### 11. Falta la semántica del ciclo de vida del snapshot (¿pila o línea base?)

El documento dice "restaurar el estado anterior" sin decidir si hay una pila de snapshots anidados o una única línea base. Con perfiles solapados y prioridades, una pila se corrompe en cuanto el proceso muere a mitad de transición (que es el escenario habitual en los OEM agresivos de §4.3 de la investigación).

**Decisión recomendada: una única línea base**, no una pila. Semántica: se captura un `BASELINE` **solo cuando se pasa de "ninguna automatización activa" a "alguna automatización activa"**, y se consume (y borra) cuando se vuelve a "ninguna activa". Las transiciones perfil→perfil no capturan nada. Esto encaja de forma natural con el diseño de "una sola alarma para la siguiente transición" de §7.4 y es reconciliable: si al arrancar hay `BASELINE` pero no hay ninguna franja activa, se restaura y se borra. Con una pila, ese mismo arranque es irrecuperable.

### 12. `CU-06` promete algo que Android no permite: excepciones por contacto concreto

P3 (Rocío) "necesita excepción para llamadas repetidas y para **tres contactos concretos**". `NotificationManager.Policy` solo admite `PRIORITY_SENDERS_ANY | CONTACTS | STARRED` para llamadas y mensajes. **No existe API para pasar una lista de contactos.** Además, leer contactos exigiría `READ_CONTACTS`, que contradice el D4 ("cero permisos innecesarios").

**Corrección para la capa de datos: no crear ninguna tabla `dnd_allowed_contacts`.** El modelo se queda en `allowCalls = STARRED`, y el producto resuelve el caso con un flujo de UI que lleva al usuario a marcar esos tres contactos como favoritos en la agenda del sistema (`ContactsContract.QuickContact` / intent de edición, sin permiso de lectura). Conviene corregir la redacción del CU-06 en el documento antes de que alguien implemente una tabla que no se puede aplicar.

### 13. No existen entidades para la activación manual ni para la pausa global

§7.3 pasos 1 y 2 consultan "pausa global activa" y "activación manual vigente". RF-06 y RF-17 los exigen. **No están en la §4.** Y son estado que debe sobrevivir a la muerte del proceso y al reinicio (RF-16, CU-09).

**Corrección.** Un agregado singleton persistido:

```kotlin
data class AutomationState(
    val globalPauseUntil: Instant?,          // null = sin pausa
    val manualProfileUuid: String?,          // activación manual vigente
    val manualUntil: Instant?,               // null con manualProfileUuid != null ⇒ indefinida
    val manualActivatedAt: Instant?,
    val appliedProfileUuid: String?,         // lo que está aplicado ahora (§7.3 paso 5)
    val appliedScheduleUuid: String?,
    val appliedAt: Instant?,
    val nextTransitionAt: Instant?,          // para el panel de diagnóstico RF-33
    val lastReconciliationAt: Instant?,
)
```

**Va en Room, no en DataStore.** Razón concreta: `manualProfileUuid` y `appliedProfileUuid` referencian perfiles; si el usuario borra el perfil manualmente activo, DataStore no tiene forma de enterarse y queda una referencia colgante que hace que la reconciliación intente aplicar un perfil inexistente. En Room es una FK con `ON DELETE SET NULL` dentro de la misma transacción del borrado. Regla general que propongo para el proyecto: **todo lo que participe en la resolución de conflictos vive en Room; DataStore es solo para preferencias de UI y onboarding**, porque DataStore y Room no comparten transacción.

### 14. Falta el "perfil por defecto" de RF-15

RF-15: "restaurar el estado anterior **o aplicar el perfil por defecto**". No hay campo en ninguna parte para ese perfil. Añadir `defaultProfileUuid: String?` a los ajustes, con política explícita si ese perfil se borra (pasa a `null` y se cae en "restaurar línea base"). Y una decisión de producto que la capa de datos necesita: `restoreOnExit` es por perfil, pero "perfil por defecto" es global — hay que definir cuál manda. Propuesta: `restoreOnExit = true` (línea base) tiene prioridad; el perfil por defecto solo se usa si `restoreOnExit = false` y existe.

### 15. `ActivityLogEntry`: `reason: String` libre rompe RF-42 y RF-46

`reason: String` en texto libre significa: mensajes escritos en código (prohibido por RF-42, "sin cadenas embebidas en código"), imposibles de traducir, e imposibles de filtrar de forma fiable (RF-46, "historial filtrable").

**Corrección.**

```kotlin
enum class LogReason {
    SCHEDULE_START, SCHEDULE_END, MANUAL_ACTIVATION, MANUAL_EXPIRED,
    GLOBAL_PAUSE_START, GLOBAL_PAUSE_END, BOOT_RECONCILE, WATCHDOG_RECONCILE,
    TIME_CHANGED, TIMEZONE_CHANGED, APP_UPDATED, PERMISSION_LOST, PERMISSION_GRANTED,
    SKIPPED_IN_CALL, SKIPPED_MEDIA_PLAYING, SECURITY_EXCEPTION, IMPORT_APPLIED,
}
data class ActivityLogEntry(
    val id: Long = 0,
    val timestamp: Instant,
    val zoneId: String,              // TimeZone del momento del suceso
    val utcOffsetSeconds: Int,
    val type: LogType,
    val reason: LogReason,
    val paramsJson: String?,         // {"stream":"RING","from":40,"to":0}
    val profileUuid: String?,
    val profileNameSnapshot: String?,// desnormalizado a propósito, ver punto 16
    val scheduleUuid: String?,
    val success: Boolean,
    val detail: String?,             // solo diagnóstico técnico, no traducible, p. ej. mensaje de excepción
)
```

La UI compone el texto con `stringResource(reason.labelRes, params)`. `detail` es lo único en texto libre y es explícitamente diagnóstico técnico (nombre de excepción), no visible como texto principal.

El `zoneId` + `utcOffsetSeconds` son necesarios para el CU-07 literal: "¿por qué cambió el sonido **a las 3 de la mañana**?". Si el usuario ha viajado o ha cambiado la hora (escenarios que RF-16 contempla explícitamente), un `Instant` a solas no permite reconstruir la hora local que el usuario vio.

### 16. El historial debe sobrevivir al borrado del perfil que lo generó

Si el historial referencia perfiles solo por FK y se borra un perfil, o bien el historial desaparece (CASCADE, se pierde la auditoría que es el D6) o queda mudo ("un perfil borró tu volumen"). Por eso `profileNameSnapshot` está desnormalizado a propósito, junto con la FK opcional. Es la única desnormalización que recomiendo en todo el esquema y está justificada: un registro de auditoría es inmutable y debe ser legible por sí mismo.

### 17. Campos menores que faltan

- `sortOrder: Int` en el perfil: sin él la lista se ordena por id o por nombre, y RF-01 (duplicar) más RF-08 sugieren orden controlado por el usuario.
- `templateKey: String?`: RF-07 define seis plantillas; sin marcar el origen no se puede ofrecer "restaurar plantilla" ni evitar re-crearlas en cada arranque.
- `Schedule` sin `label`: con "varias franjas por perfil" (RF-13) y la línea de tiempo (RF-18), distinguirlas visualmente exige un nombre opcional.
- `Profile.emoji: String?` necesita un límite validado (≤ 8 code points): en la importación es un vector trivial de inflar la base de datos y de romper la UI.
- `colorSeed: Int` debe documentar su formato (ARGB `0xFFRRGGBB`) o quedará ambiguo frente al color dinámico de RF-40.

---

## Esquema de base de datos propuesto

SQLite vía Room. Versión de esquema inicial `1`. Todos los instantes son `INTEGER` epoch-millis UTC. Todos los booleanos son `INTEGER 0/1`. Los enums se guardan como `TEXT` con códigos estables (ver converters).

### Tabla `profiles`

| Columna | Tipo | Null | Defecto | Notas |
|---|---|---|---|---|
| `id` | INTEGER | NO | — | PK, `AUTOINCREMENT` (impide reutilizar rowids de perfiles borrados) |
| `uuid` | TEXT | NO | — | UUID v4, identidad de negocio |
| `name` | TEXT | NO | — | 1..60 chars (validado en dominio) |
| `emoji` | TEXT | SÍ | NULL | ≤ 8 code points |
| `color_seed` | INTEGER | NO | — | ARGB |
| `enabled` | INTEGER | NO | 1 | |
| `priority` | INTEGER | NO | 50 | `CHECK (priority BETWEEN 0 AND 100)` |
| `sort_order` | INTEGER | NO | 0 | |
| `template_key` | TEXT | SÍ | NULL | `NIGHT`,`WORK`,`MEETING`,`CINEMA`,`DRIVING`,`WEEKEND` |
| `vol_ring` | INTEGER | SÍ | NULL | `CHECK (0..100)`; NULL = no tocar |
| `vol_music` | INTEGER | SÍ | NULL | idem |
| `vol_alarm` | INTEGER | SÍ | NULL | idem |
| `vol_notification` | INTEGER | SÍ | NULL | idem |
| `vol_voice_call` | INTEGER | SÍ | NULL | idem |
| `vol_system` | INTEGER | SÍ | NULL | idem |
| `vol_accessibility` | INTEGER | SÍ | NULL | idem |
| `ringer_mode` | TEXT | SÍ | NULL | `NORMAL`\|`VIBRATE`\|`SILENT`; NULL = no tocar |
| `vib_when_ringing` | INTEGER | SÍ | NULL | |
| `vib_on_notification` | INTEGER | SÍ | NULL | |
| `vib_touch_feedback` | INTEGER | SÍ | NULL | |
| `dnd_mode` | TEXT | SÍ | NULL | `OFF`\|`PRIORITY`\|`ALARMS_ONLY`\|`TOTAL_SILENCE`; NULL = no tocar |
| `dnd_allow_calls` | TEXT | NO | `'NONE'` | `NONE`\|`STARRED`\|`CONTACTS`\|`ANY` |
| `dnd_allow_messages` | TEXT | NO | `'NONE'` | idem |
| `dnd_allow_conversations` | TEXT | NO | `'NONE'` | `NONE`\|`IMPORTANT`\|`ANYONE` (API 30+) |
| `dnd_allow_repeat_callers` | INTEGER | NO | 0 | |
| `dnd_allow_alarms` | INTEGER | NO | 1 | |
| `dnd_allow_media` | INTEGER | NO | 1 | |
| `dnd_allow_reminders` | INTEGER | NO | 0 | |
| `dnd_allow_events` | INTEGER | NO | 0 | |
| `dnd_suppressed_visual_effects` | INTEGER | NO | 0 | bitmask `SUPPRESSED_EFFECT_*` |
| `dnd_use_system_mode` | INTEGER | NO | 0 | registrar como `AutomaticZenRule` |
| `dnd_zen_rule_id` | TEXT | SÍ | NULL | **estado de sistema, NO se exporta** |
| `dnd_effect_grayscale` | INTEGER | SÍ | NULL | `ZenDeviceEffects`, API 35+ |
| `dnd_effect_dim_wallpaper` | INTEGER | SÍ | NULL | idem |
| `dnd_effect_suppress_ambient` | INTEGER | SÍ | NULL | idem |
| `opt_restore_on_exit` | INTEGER | NO | 1 | |
| `opt_transition_seconds` | INTEGER | NO | 0 | `CHECK (0..60)`; 0 = sin rampa |
| `opt_skip_during_call` | INTEGER | NO | 1 | |
| `opt_skip_if_media_playing` | INTEGER | NO | 0 | |
| `opt_notify_on_apply` | INTEGER | NO | 0 | |
| `created_at` | INTEGER | NO | — | epoch ms UTC |
| `updated_at` | INTEGER | NO | — | epoch ms UTC |

**Índices**
- `UNIQUE INDEX idx_profiles_uuid ON profiles(uuid)`
- `INDEX idx_profiles_enabled_priority ON profiles(enabled, priority DESC)` — sirve a la consulta caliente de la reconciliación
- `INDEX idx_profiles_sort ON profiles(sort_order)`

Deliberadamente **no** hay índice único en `name`: RF-01 exige duplicar perfiles y forzar unicidad de nombre convertiría una operación de un toque en un diálogo.

### Tabla `schedules`

| Columna | Tipo | Null | Defecto | Notas |
|---|---|---|---|---|
| `id` | INTEGER | NO | — | PK AUTOINCREMENT |
| `uuid` | TEXT | NO | — | UUID v4 |
| `profile_id` | INTEGER | NO | — | FK → `profiles(id)` |
| `enabled` | INTEGER | NO | 1 | RF-14 |
| `start_minute` | INTEGER | NO | — | `CHECK (0..1439)` |
| `duration_minutes` | INTEGER | NO | — | `CHECK (1..1440)` |
| `days_mask` | INTEGER | NO | — | `CHECK (1..127)`, bit0 = lunes (ISO) |
| `priority_override` | INTEGER | SÍ | NULL | `CHECK (0..100)`; NULL = usar la del perfil |
| `label` | TEXT | SÍ | NULL | |
| `created_at` | INTEGER | NO | — | |
| `updated_at` | INTEGER | NO | — | |

**Claves foráneas**
- `profile_id → profiles(id) ON DELETE CASCADE ON UPDATE CASCADE`. Justificación: una franja sin perfil no tiene ningún significado; no es un dato que merezca conservarse huérfano. CASCADE es lo correcto aquí y es el único CASCADE del esquema.

**Índices**
- `UNIQUE INDEX idx_schedules_uuid ON schedules(uuid)`
- `INDEX idx_schedules_profile ON schedules(profile_id)` — obligatorio: Room emite un warning de compilación si una columna FK no está indexada, y sin él el CASCADE hace un full scan
- `INDEX idx_schedules_enabled_days ON schedules(enabled, days_mask)`

### Tabla `activity_log`

| Columna | Tipo | Null | Defecto | Notas |
|---|---|---|---|---|
| `id` | INTEGER | NO | — | PK AUTOINCREMENT; **es el criterio de orden canónico**, no el timestamp |
| `timestamp_utc` | INTEGER | NO | — | epoch ms |
| `zone_id` | TEXT | NO | — | p. ej. `Europe/Madrid` |
| `utc_offset_seconds` | INTEGER | NO | — | desfase vigente en ese instante |
| `type` | TEXT | NO | — | `APPLY`\|`RESTORE`\|`SKIP`\|`ERROR`\|`BOOT`\|`PERMISSION` |
| `reason` | TEXT | NO | — | código de `LogReason` |
| `params_json` | TEXT | SÍ | NULL | JSON plano, ≤ 2 KB |
| `profile_id` | INTEGER | SÍ | NULL | FK → `profiles(id)` |
| `profile_uuid` | TEXT | SÍ | NULL | desnormalizado, sobrevive al borrado |
| `profile_name` | TEXT | SÍ | NULL | desnormalizado, sobrevive al borrado |
| `schedule_id` | INTEGER | SÍ | NULL | FK → `schedules(id)` |
| `success` | INTEGER | NO | — | |
| `detail` | TEXT | SÍ | NULL | diagnóstico técnico, ≤ 1 KB |

**Claves foráneas**
- `profile_id → profiles(id) ON DELETE SET NULL` — el historial es un libro de auditoría inmutable; borrar un perfil no puede reescribir el pasado. Las columnas desnormalizadas mantienen la legibilidad.
- `schedule_id → schedules(id) ON DELETE SET NULL` — igual.

**Índices**
- `INDEX idx_log_time ON activity_log(timestamp_utc DESC)`
- `INDEX idx_log_type_time ON activity_log(type, timestamp_utc DESC)` — RF-46, filtrado
- `INDEX idx_log_profile ON activity_log(profile_id)` — requerido por la FK
- `INDEX idx_log_schedule ON activity_log(schedule_id)` — requerido por la FK

**Ordenación**: siempre `ORDER BY id DESC`, nunca `ORDER BY timestamp_utc DESC` a solas. Si el usuario retrasa el reloj (evento contemplado en RF-16), el timestamp deja de ser monótono y el historial se desordena; el `id` sí es monótono.

### Tabla `audio_snapshots`

| Columna | Tipo | Null | Defecto | Notas |
|---|---|---|---|---|
| `id` | INTEGER | NO | — | PK (no autoincrement; ver más abajo) |
| `kind` | TEXT | NO | — | `BASELINE`\|`PRE_APPLY` |
| `captured_at_utc` | INTEGER | NO | — | |
| `owner_profile_id` | INTEGER | SÍ | NULL | FK → `profiles(id) ON DELETE SET NULL` |
| `ring_steps` / `ring_max` / `ring_min` | INTEGER ×3 | NO | — | pasos nativos |
| `music_steps` / `music_max` / `music_min` | INTEGER ×3 | NO | — | |
| `alarm_steps` / `alarm_max` / `alarm_min` | INTEGER ×3 | NO | — | |
| `notification_steps` / `_max` / `_min` | INTEGER ×3 | NO | — | |
| `voice_call_steps` / `_max` / `_min` | INTEGER ×3 | NO | — | |
| `system_steps` / `_max` / `_min` | INTEGER ×3 | NO | — | |
| `accessibility_steps` / `_max` / `_min` | INTEGER ×3 | SÍ | NULL | puede no existir en algunos OEM |
| `ringer_mode` | TEXT | NO | — | |
| `interruption_filter` | INTEGER | NO | — | `INTERRUPTION_FILTER_*` |
| `vibrate_when_ringing` | INTEGER | SÍ | NULL | |
| `policy_blob` | TEXT | SÍ | NULL | política DND serializada, opaca |
| `device_fingerprint` | TEXT | NO | — | `Build.MODEL` + escalas; invalida el snapshot si cambia |

La línea base es **una única fila con `id = 0`**. Room no soporta `CHECK` en anotaciones, así que el invariante de fila única se garantiza en el DAO: `@Insert(onConflict = REPLACE)` con `id` fijado a `0` y una única consulta de lectura `WHERE id = 0`. Es más simple y más verificable que un índice parcial, que Room no puede declarar ni validar.

`device_fingerprint` es la salvaguarda contra el caso de restaurar una copia en otro móvil: si las escalas no coinciden, el snapshot se descarta y se cae a "no restaurar" registrándolo en el historial, en lugar de aplicar 25 pasos en un dispositivo de 7.

### Tabla `automation_state`

Fila única, `id = 0`.

| Columna | Tipo | Null | Notas |
|---|---|---|---|
| `id` | INTEGER | NO | PK, siempre 0 |
| `global_pause_until_utc` | INTEGER | SÍ | RF-17 |
| `manual_profile_id` | INTEGER | SÍ | FK → `profiles(id) ON DELETE SET NULL` |
| `manual_until_utc` | INTEGER | SÍ | NULL con perfil no nulo = indefinida |
| `manual_activated_at_utc` | INTEGER | SÍ | |
| `applied_profile_id` | INTEGER | SÍ | FK → `profiles(id) ON DELETE SET NULL` |
| `applied_schedule_id` | INTEGER | SÍ | FK → `schedules(id) ON DELETE SET NULL` |
| `applied_at_utc` | INTEGER | SÍ | |
| `next_transition_at_utc` | INTEGER | SÍ | RF-33, panel de diagnóstico |
| `last_reconciliation_at_utc` | INTEGER | SÍ | RF-31/32 |
| `schema_touched_at` | INTEGER | NO | control interno |

Índices en `manual_profile_id`, `applied_profile_id`, `applied_schedule_id` (exigidos por las FK).

### Tabla `schedule_date_exceptions` (llega en la versión 2, v1.1 — RF-19)

| Columna | Tipo | Null | Notas |
|---|---|---|---|
| `id` | INTEGER | NO | PK |
| `schedule_id` | INTEGER | SÍ | FK → `schedules(id) ON DELETE CASCADE`; NULL = excepción global |
| `date_epoch_day` | INTEGER | NO | `LocalDate.toEpochDay()` |
| `kind` | TEXT | NO | `SKIP` \| `FORCE` |
| `note` | TEXT | SÍ | |

`UNIQUE INDEX ON (schedule_id, date_epoch_day)`. **No la creéis en la versión 1**: una tabla vacía sin uso es deuda, y su creación es el mejor primer ejercicio de migración real (ver más abajo).

### Configuración de la base

- `PRAGMA foreign_keys = ON` — Room lo activa por conexión, pero conviene un test que lo verifique porque es la garantía de todo el esquema.
- WAL activado (por defecto en Room desde API 16 con `setJournalMode(WRITE_AHEAD_LOGGING)`); ver la nota sobre Auto Backup en riesgos.
- `PRAGMA auto_vacuum = INCREMENTAL` y `incremental_vacuum` tras cada purga del historial: sin ello, el fichero crece a la marca de agua y nunca se reduce, y el historial es la tabla que más rota.
- Un solo proceso. Si algún día el widget o el tile se declaran con `android:process`, hace falta `enableMultiInstanceInvalidation()` o los `Flow` dejan de emitir en silencio.

---

## Entidades Room y converters necesarios

Principio rector: **las entidades solo contienen tipos primitivos y `String`**. Los `TypeConverter` se reducen al mínimo imprescindible, porque un converter es código que corre en cada lectura y que no participa en la validación de esquema de Room — es la fuente número uno de migraciones que "validan" pero devuelven datos corruptos. La conversión a tipos de dominio se hace en mappers explícitos y testeables.

```kotlin
// :core:data/database/entity/ProfileEntity.kt

@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["enabled", "priority"]),
        Index(value = ["sort_order"]),
    ],
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "emoji") val emoji: String?,
    @ColumnInfo(name = "color_seed") val colorSeed: Int,
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean,
    @ColumnInfo(name = "priority", defaultValue = "50") val priority: Int,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int,
    @ColumnInfo(name = "template_key") val templateKey: String?,
    @Embedded(prefix = "vol_") val volumes: VolumeColumns,
    @ColumnInfo(name = "ringer_mode") val ringerMode: String?,
    @Embedded(prefix = "vib_") val vibration: VibrationColumns,
    @Embedded(prefix = "dnd_") val dnd: DndColumns,
    @Embedded(prefix = "opt_") val options: OptionColumns,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

data class VolumeColumns(
    @ColumnInfo(name = "ring") val ring: Int?,
    @ColumnInfo(name = "music") val music: Int?,
    @ColumnInfo(name = "alarm") val alarm: Int?,
    @ColumnInfo(name = "notification") val notification: Int?,
    @ColumnInfo(name = "voice_call") val voiceCall: Int?,
    @ColumnInfo(name = "system") val system: Int?,
    @ColumnInfo(name = "accessibility") val accessibility: Int?,
)

data class VibrationColumns(
    @ColumnInfo(name = "when_ringing") val whenRinging: Boolean?,
    @ColumnInfo(name = "on_notification") val onNotification: Boolean?,
    @ColumnInfo(name = "touch_feedback") val touchFeedback: Boolean?,
)

data class DndColumns(
    @ColumnInfo(name = "mode") val mode: String?,
    @ColumnInfo(name = "allow_calls", defaultValue = "'NONE'") val allowCalls: String,
    @ColumnInfo(name = "allow_messages", defaultValue = "'NONE'") val allowMessages: String,
    @ColumnInfo(name = "allow_conversations", defaultValue = "'NONE'") val allowConversations: String,
    @ColumnInfo(name = "allow_repeat_callers", defaultValue = "0") val allowRepeatCallers: Boolean,
    @ColumnInfo(name = "allow_alarms", defaultValue = "1") val allowAlarms: Boolean,
    @ColumnInfo(name = "allow_media", defaultValue = "1") val allowMedia: Boolean,
    @ColumnInfo(name = "allow_reminders", defaultValue = "0") val allowReminders: Boolean,
    @ColumnInfo(name = "allow_events", defaultValue = "0") val allowEvents: Boolean,
    @ColumnInfo(name = "suppressed_visual_effects", defaultValue = "0") val suppressedVisualEffects: Int,
    @ColumnInfo(name = "use_system_mode", defaultValue = "0") val useSystemMode: Boolean,
    @ColumnInfo(name = "zen_rule_id") val zenRuleId: String?,
    @ColumnInfo(name = "effect_grayscale") val effectGrayscale: Boolean?,
    @ColumnInfo(name = "effect_dim_wallpaper") val effectDimWallpaper: Boolean?,
    @ColumnInfo(name = "effect_suppress_ambient") val effectSuppressAmbient: Boolean?,
)

data class OptionColumns(
    @ColumnInfo(name = "restore_on_exit", defaultValue = "1") val restoreOnExit: Boolean,
    @ColumnInfo(name = "transition_seconds", defaultValue = "0") val transitionSeconds: Int,
    @ColumnInfo(name = "skip_during_call", defaultValue = "1") val skipDuringCall: Boolean,
    @ColumnInfo(name = "skip_if_media_playing", defaultValue = "0") val skipIfMediaPlaying: Boolean,
    @ColumnInfo(name = "notify_on_apply", defaultValue = "0") val notifyOnApply: Boolean,
)
```

```kotlin
@Entity(
    tableName = "schedules",
    foreignKeys = [ForeignKey(
        entity = ProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profile_id"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["profile_id"]),
        Index(value = ["enabled", "days_mask"]),
    ],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean,
    @ColumnInfo(name = "start_minute") val startMinute: Int,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    @ColumnInfo(name = "days_mask") val daysMask: Int,
    @ColumnInfo(name = "priority_override") val priorityOverride: Int?,
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

data class ProfileWithSchedules(
    @Embedded val profile: ProfileEntity,
    @Relation(parentColumn = "id", entityColumn = "profile_id")
    val schedules: List<ScheduleEntity>,
)
```

```kotlin
@Entity(
    tableName = "activity_log",
    foreignKeys = [
        ForeignKey(ProfileEntity::class, ["id"], ["profile_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(ScheduleEntity::class, ["id"], ["schedule_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["timestamp_utc"]),
        Index(value = ["type", "timestamp_utc"]),
        Index(value = ["profile_id"]),
        Index(value = ["schedule_id"]),
    ],
)
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "timestamp_utc") val timestampUtc: Long,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "utc_offset_seconds") val utcOffsetSeconds: Int,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "params_json") val paramsJson: String?,
    @ColumnInfo(name = "profile_id") val profileId: Long?,
    @ColumnInfo(name = "profile_uuid") val profileUuid: String?,
    @ColumnInfo(name = "profile_name") val profileName: String?,
    @ColumnInfo(name = "schedule_id") val scheduleId: Long?,
    @ColumnInfo(name = "success") val success: Boolean,
    @ColumnInfo(name = "detail") val detail: String?,
)
```

```kotlin
@Entity(tableName = "audio_snapshots")
data class AudioSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long = 0L,   // 0 = BASELINE única
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "captured_at_utc") val capturedAtUtc: Long,
    @ColumnInfo(name = "owner_profile_id") val ownerProfileId: Long?,
    @ColumnInfo(name = "levels_json") val levelsJson: String,   // ver nota
    @ColumnInfo(name = "ringer_mode") val ringerMode: String,
    @ColumnInfo(name = "interruption_filter") val interruptionFilter: Int,
    @ColumnInfo(name = "vibrate_when_ringing") val vibrateWhenRinging: Boolean?,
    @ColumnInfo(name = "policy_blob") val policyBlob: String?,
    @ColumnInfo(name = "device_fingerprint") val deviceFingerprint: String,
)
```

> Nota sobre `levels_json`: es la **única** excepción que acepto a la regla "columnas planas". Los 21 campos de pasos/máximos/mínimos son un blob opaco que nunca se consulta ni se filtra por SQL, solo se lee entero para restaurar. Serializarlo con kotlinx.serialization en una columna es más simple de migrar (si mañana Android añade un stream, es un cambio en el DTO con `ignoreUnknownKeys`, no una migración de esquema) y no cuesta nada en rendimiento. Si el equipo prefiere consistencia total, la variante de 21 columnas del apartado anterior también es correcta; lo que **no** es aceptable es guardar porcentajes.

```kotlin
@Entity(
    tableName = "automation_state",
    foreignKeys = [
        ForeignKey(ProfileEntity::class, ["id"], ["manual_profile_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(ProfileEntity::class, ["id"], ["applied_profile_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(ScheduleEntity::class, ["id"], ["applied_schedule_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("manual_profile_id"), Index("applied_profile_id"), Index("applied_schedule_id")],
)
data class AutomationStateEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "global_pause_until_utc") val globalPauseUntilUtc: Long?,
    @ColumnInfo(name = "manual_profile_id") val manualProfileId: Long?,
    @ColumnInfo(name = "manual_until_utc") val manualUntilUtc: Long?,
    @ColumnInfo(name = "manual_activated_at_utc") val manualActivatedAtUtc: Long?,
    @ColumnInfo(name = "applied_profile_id") val appliedProfileId: Long?,
    @ColumnInfo(name = "applied_schedule_id") val appliedScheduleId: Long?,
    @ColumnInfo(name = "applied_at_utc") val appliedAtUtc: Long?,
    @ColumnInfo(name = "next_transition_at_utc") val nextTransitionAtUtc: Long?,
    @ColumnInfo(name = "last_reconciliation_at_utc") val lastReconciliationAtUtc: Long?,
)
```

### Converters

Room ≥ 2.4 convierte enums automáticamente usando `Enum.name`, lo que **acopla el esquema de la base de datos a identificadores de Kotlin**: renombrar `ALARMS_ONLY` a `ONLY_ALARMS` corrompe silenciosamente los datos existentes sin que ninguna migración lo detecte, porque el esquema no cambia. Por eso las entidades declaran los enums como `String` y la conversión a código estable se hace en el mapper, con un test que congela los códigos:

```kotlin
// :core:data/database/Codecs.kt  — no son TypeConverters de Room, son mappers puros y testeados
internal object Codecs {
    fun ringerToCode(mode: RingerMode?): String? = mode?.let {
        when (it) { RingerMode.NORMAL -> "NORMAL"; RingerMode.VIBRATE -> "VIBRATE"; RingerMode.SILENT -> "SILENT" }
    }
    fun ringerFromCode(code: String?): RingerMode? = when (code) {
        null -> null; "NORMAL" -> RingerMode.NORMAL; "VIBRATE" -> RingerMode.VIBRATE
        "SILENT" -> RingerMode.SILENT; else -> null   // dato desconocido ⇒ "no tocar", nunca crash
    }
    fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or (1 shl (d.isoDayNumber - 1)) }
    fun daysFromMask(mask: Int): Set<DayOfWeek> =
        DayOfWeek.entries.filter { (mask and (1 shl (it.isoDayNumber - 1))) != 0 }.toSet()
}
```

Los únicos `@TypeConverter` reales que necesita la base son cero si se aplica el principio anterior. Si el equipo prefiere tipos ricos en las entidades, el mínimo indispensable sería:

```kotlin
object RoomConverters {
    @TypeConverter fun instantToLong(v: Instant?): Long? = v?.toEpochMilliseconds()
    @TypeConverter fun longToInstant(v: Long?): Instant? = v?.let { Instant.fromEpochMilliseconds(it) }
    @TypeConverter fun daysToInt(v: Set<DayOfWeek>?): Int? = v?.let(Codecs::daysToMask)
    @TypeConverter fun intToDays(v: Int?): Set<DayOfWeek>? = v?.let(Codecs::daysFromMask)
}
```

Y la base:

```kotlin
@Database(
    entities = [ProfileEntity::class, ScheduleEntity::class, ActivityLogEntity::class,
                AudioSnapshotEntity::class, AutomationStateEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class RitMuteDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun snapshotDao(): AudioSnapshotDao
    abstract fun automationStateDao(): AutomationStateDao
}
```

### Consultas DAO que conviene fijar ahora

```kotlin
// Conjunto candidato para la reconciliación: filtra en SQL, no en memoria.
@Query("""
    SELECT s.* FROM schedules s
    INNER JOIN profiles p ON p.id = s.profile_id
    WHERE s.enabled = 1 AND p.enabled = 1 AND (s.days_mask & :dayMask) != 0
""")
suspend fun candidatesForDayMask(dayMask: Int): List<ScheduleEntity>
```

```kotlin
// Purga del historial (RF-38). Dos sentencias, ambas O(log n) gracias a los índices.
@Query("DELETE FROM activity_log WHERE timestamp_utc < :cutoffUtc")
suspend fun purgeOlderThan(cutoffUtc: Long): Int

@Query("""
    DELETE FROM activity_log
    WHERE id <= COALESCE(
        (SELECT id FROM activity_log ORDER BY id DESC LIMIT 1 OFFSET :keepCount), -1)
""")
suspend fun purgeBeyondCount(keepCount: Int): Int
```

Nótese que evita el antipatrón `WHERE id NOT IN (SELECT ... LIMIT 1000)`, que es cuadrático. La política de RF-38 ("1 000 entradas **o** 30 días") es ambigua; la interpretación que propongo y que hay que fijar en el documento: **se conserva lo que cumpla ambas condiciones** — más nuevo que 30 días **y** entre las 1 000 más recientes. Es la lectura conservadora en tamaño y la que evita que un mes tranquilo llene la base con reconciliaciones.

---

## Contrato de repositorios

Convenciones: todo `suspend` corre en `Dispatchers.IO` inyectado; todo `Flow` es frío, viene de Room y ya está en el hilo correcto; los errores esperables se devuelven como `Result`/sealed, no como excepción.

```kotlin
// :core:domain/repository/ProfileRepository.kt

interface ProfileRepository {
    fun observeAll(): Flow<List<SoundProfile>>
    fun observeEnabled(): Flow<List<SoundProfile>>
    fun observeById(id: ProfileId): Flow<SoundProfile?>
    fun observeWithSchedules(): Flow<List<ProfileWithSchedules>>

    suspend fun getById(id: ProfileId): SoundProfile?
    suspend fun getByUuid(uuid: String): SoundProfile?
    suspend fun count(): Int

    /** Inserta o actualiza. Valida invariantes de dominio; devuelve el id resultante. */
    suspend fun upsert(profile: SoundProfile): Result<ProfileId>

    /** Copia perfil y todas sus franjas con uuids nuevos, en una sola transacción. */
    suspend fun duplicate(id: ProfileId, newName: String): Result<ProfileId>

    suspend fun setEnabled(id: ProfileId, enabled: Boolean)
    suspend fun reorder(orderedIds: List<ProfileId>)

    /**
     * Borra el perfil y, en cascada, sus franjas.
     * Devuelve el estado colateral que el caso de uso debe resolver fuera de la transacción:
     * la regla de sistema a eliminar y si el perfil borrado era el aplicado o el manual.
     */
    suspend fun delete(id: ProfileId): Result<ProfileDeletion>
}

data class ProfileDeletion(
    val deletedUuid: String,
    val orphanedZenRuleId: String?,
    val wasApplied: Boolean,
    val wasManualOverride: Boolean,
)
```

```kotlin
// :core:domain/repository/ScheduleRepository.kt

interface ScheduleRepository {
    fun observeByProfile(profileId: ProfileId): Flow<List<Schedule>>
    fun observeAllEnabled(): Flow<List<Schedule>>

    suspend fun getById(id: ScheduleId): Schedule?
    suspend fun upsert(schedule: Schedule): Result<ScheduleId>
    suspend fun setEnabled(id: ScheduleId, enabled: Boolean)
    suspend fun delete(id: ScheduleId)

    /** Reemplaza atómicamente el conjunto de franjas de un perfil (edición completa). */
    suspend fun replaceForProfile(profileId: ProfileId, schedules: List<Schedule>): Result<Unit>

    /** Solo las franjas que pueden estar activas en ese día ISO. Filtrado en SQL. */
    suspend fun candidatesForDay(day: DayOfWeek): List<ResolvableSchedule>
}

/** Vista desnormalizada que el resolutor de conflictos necesita, sin volver a la base. */
data class ResolvableSchedule(
    val schedule: Schedule,
    val profileUuid: String,
    val profileId: ProfileId,
    val profileCreatedAt: Instant,
    val effectivePriority: Int,   // priorityOverride ?: profile.priority
)
```

```kotlin
// :core:domain/repository/AutomationStateRepository.kt

interface AutomationStateRepository {
    fun observe(): Flow<AutomationState>
    suspend fun get(): AutomationState

    suspend fun startGlobalPause(until: Instant?)          // null = indefinida
    suspend fun clearGlobalPause()

    suspend fun setManualOverride(profileId: ProfileId, until: Instant?)
    suspend fun clearManualOverride()

    suspend fun recordApplied(profileId: ProfileId?, scheduleId: ScheduleId?, at: Instant)
    suspend fun recordNextTransition(at: Instant?)
    suspend fun recordReconciliation(at: Instant)
}
```

```kotlin
// :core:domain/repository/AudioBaselineRepository.kt

interface AudioBaselineRepository {
    fun observeBaseline(): Flow<AudioSnapshot?>
    suspend fun getBaseline(): AudioSnapshot?

    /** No sobreescribe si ya existe: la línea base se captura una sola vez por ciclo. */
    suspend fun captureBaselineIfAbsent(snapshot: AudioSnapshot): Boolean

    /** Lee y borra en una transacción, para que un fallo posterior no la restaure dos veces. */
    suspend fun consumeBaseline(): AudioSnapshot?

    suspend fun clearBaseline()
}
```

```kotlin
// :core:domain/repository/ActivityLogRepository.kt

interface ActivityLogRepository {
    /** Paginado: el historial puede tener 1 000 filas y RF-46 pide filtros. */
    fun pagingSource(filter: LogFilter): PagingSource<Int, ActivityLogEntry>
    fun observeRecent(limit: Int = 20): Flow<List<ActivityLogEntry>>

    suspend fun log(entry: ActivityLogEntry)
    /** Escritura en lote para las transiciones que generan varias líneas. */
    suspend fun logAll(entries: List<ActivityLogEntry>)

    suspend fun purge(policy: RetentionPolicy): PurgeResult
    suspend fun clearAll()
    /** Exportación aparte del backup de configuración; ver el apartado de JSON. */
    suspend fun exportTo(sink: okio.BufferedSink, filter: LogFilter): Result<Int>
}

data class LogFilter(
    val types: Set<LogType> = emptySet(),      // vacío = todos
    val profileId: ProfileId? = null,
    val onlyFailures: Boolean = false,
    val since: Instant? = null,
)
data class RetentionPolicy(val maxEntries: Int = 1_000, val maxAge: Duration = 30.days)
data class PurgeResult(val deletedByAge: Int, val deletedByCount: Int)
```

```kotlin
// :core:domain/repository/SettingsRepository.kt  (DataStore, no Room)

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setDefaultProfileUuid(uuid: String?)
    suspend fun setRetentionPolicy(policy: RetentionPolicy)
    suspend fun markOnboardingCompleted()
    suspend fun setRingNotificationCoupled(coupled: Boolean, deviceFingerprint: String) // RF-10
    suspend fun recordExport(at: Instant)
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultProfileUuid: String? = null,
    val retention: RetentionPolicy = RetentionPolicy(),
    val onboardingCompleted: Boolean = false,
    val ringNotificationCoupled: Boolean? = null,
    val coupledDetectionFingerprint: String? = null,
    val lastExportAt: Instant? = null,
)
```

```kotlin
// :core:domain/repository/BootCacheRepository.kt
// Almacenamiento cifrado por dispositivo (createDeviceProtectedStorageContext),
// legible ANTES de que el usuario desbloquee el teléfono. Ver riesgo 9.

interface BootCacheRepository {
    suspend fun writeUpcomingTransitions(transitions: List<PlannedTransition>)
    suspend fun readUpcomingTransitions(): List<PlannedTransition>
    suspend fun clear()
}

data class PlannedTransition(val atUtcMillis: Long, val profileUuid: String?, val isEnd: Boolean)
```

```kotlin
// :core:domain/repository/BackupRepository.kt

interface BackupRepository {
    suspend fun export(sink: okio.BufferedSink, options: ExportOptions = ExportOptions()): Result<ExportSummary>
    /** Solo lee y valida; no toca la base. Alimenta la pantalla de previsualización. */
    suspend fun inspect(source: okio.BufferedSource): Result<BackupPreview>
    suspend fun import(source: okio.BufferedSource, mode: ImportMode): Result<ImportSummary>
}

data class ExportOptions(val includeSettings: Boolean = true, val prettyPrint: Boolean = true)
data class ExportSummary(val profiles: Int, val schedules: Int, val bytes: Long)

data class BackupPreview(
    val formatVersion: Int,
    val exportedAt: Instant,
    val appVersionName: String?,
    val profiles: List<ProfilePreview>,
    val warnings: List<ImportWarning>,
)
data class ProfilePreview(val uuid: String, val name: String, val emoji: String?, val schedules: Int, val collidesWithExisting: Boolean)

enum class ImportMode {
    REPLACE_ALL,   // borra todo y sustituye; el JSON es la verdad
    MERGE,         // por uuid: actualiza el existente si el importado es más nuevo, inserta el resto
    ADD_AS_COPY,   // regenera todos los uuids; nunca toca lo que ya hay
}

data class ImportSummary(
    val inserted: Int, val updated: Int, val skipped: Int,
    val warnings: List<ImportWarning>, val undoToken: String?,
)

sealed interface ImportError {
    data object NotJson : ImportError
    data class UnsupportedVersion(val found: Int, val supported: IntRange) : ImportError
    data class SchemaViolation(val path: String, val message: String) : ImportError
    data class TooLarge(val bytes: Long, val limit: Long) : ImportError
    data class DuplicateUuid(val uuid: String) : ImportError
    data object Empty : ImportError
    data class Unknown(val cause: Throwable) : ImportError
}

sealed interface ImportWarning {
    data class UnknownEnumValue(val path: String, val raw: String, val fallback: String) : ImportWarning
    data class ValueClamped(val path: String, val raw: String, val applied: String) : ImportWarning
    data class ContradictoryRingerAndVolume(val profileUuid: String) : ImportWarning
    data class RenamedOnCollision(val uuid: String, val newName: String) : ImportWarning
}
```

Nótese `inspect()` separado de `import()`: importar a ciegas un fichero que borra la configuración del usuario es inaceptable, y el CU-08 se juega ahí la confianza. La pantalla muestra qué va a entrar antes de tocar nada.

---

## Formato del JSON de exportación

Decisiones de forma, y por qué:

- **Las franjas van anidadas dentro de su perfil**, no en una lista plana con `profileUuid`. Así es estructuralmente imposible que exista una franja huérfana en el fichero, y la importación selectiva ("solo el perfil Noche") es un filtro sobre el array raíz.
- **`formatVersion` es un entero monótono, independiente de la versión de Room y de la versión de la app.** Fusionarlos es un error clásico: la base cambia por razones internas que no afectan al fichero.
- **`encodeDefaults = true` y `explicitNulls = true`.** El fichero es más verboso, pero RF-36 pide "JSON **legible**": un `null` explícito documenta "no tocar" sin necesidad de consultar nada. Y hace el round-trip idempotente byte a byte, que es lo que hay que testear.
- **`ignoreUnknownKeys = true`, `isLenient = false`.** Lo primero da compatibilidad hacia adelante gratis; lo segundo evita que un fichero malformado se acepte silenciosamente.
- **No se exportan**: `id` (rowid), `dnd_zen_rule_id` (identificador del sistema local), el historial, los snapshots ni `automation_state`. Todos son estado local, y exportar el `zenRuleId` haría que dos móviles creyeran ser dueños de la misma regla de sistema.
- **El historial se exporta aparte** (`exportTo` de `ActivityLogRepository`), porque no es configuración, multiplica el tamaño del fichero y contiene marcas temporales de hábitos que el usuario no espera compartir al pasarle su configuración a alguien.

```json
{
  "formatVersion": 1,
  "exportedAt": "2026-08-12T21:14:03Z",
  "generator": {
    "app": "RitMute",
    "versionName": "1.0.0",
    "versionCode": 100
  },
  "settings": {
    "themeMode": "SYSTEM",
    "dynamicColor": true,
    "defaultProfileUuid": "9f1c2b7e-4d3a-4c19-9a51-0b7e6d2f1a44",
    "retention": { "maxEntries": 1000, "maxAgeDays": 30 }
  },
  "profiles": [
    {
      "uuid": "3a6f0f92-8c41-4f5e-b0d2-7c9a1e5f3b21",
      "name": "Noche",
      "emoji": "🌙",
      "colorSeed": 4283215696,
      "enabled": true,
      "priority": 80,
      "sortOrder": 0,
      "templateKey": "NIGHT",
      "createdAt": "2026-07-02T18:22:11Z",
      "updatedAt": "2026-08-01T09:03:40Z",
      "volumes": {
        "ring": 0,
        "music": null,
        "alarm": 100,
        "notification": 0,
        "voiceCall": null,
        "system": 0,
        "accessibility": null
      },
      "ringerMode": "SILENT",
      "vibration": {
        "whenRinging": false,
        "onNotification": false,
        "touchFeedback": null
      },
      "dnd": {
        "mode": "PRIORITY",
        "allowCalls": "STARRED",
        "allowMessages": "NONE",
        "allowConversations": "NONE",
        "allowRepeatCallers": true,
        "allowAlarms": true,
        "allowMedia": false,
        "allowReminders": false,
        "allowEvents": false,
        "suppressedVisualEffects": ["SCREEN_ON", "AMBIENT", "PEEK"],
        "useSystemMode": true,
        "deviceEffects": {
          "grayscale": true,
          "dimWallpaper": true,
          "suppressAmbientDisplay": true
        }
      },
      "options": {
        "restoreOnExit": true,
        "transitionSeconds": 10,
        "skipDuringCall": true,
        "skipIfMediaPlaying": true,
        "notifyOnApply": false
      },
      "schedules": [
        {
          "uuid": "b21d7c04-5f8e-42a7-9c31-1de4a70f8c55",
          "enabled": true,
          "label": "Entre semana",
          "startTime": "23:30",
          "durationMinutes": 480,
          "daysOfWeek": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "SUNDAY"],
          "priorityOverride": null
        },
        {
          "uuid": "c7e01a3d-9b44-4f10-8e6b-2af5c8d31097",
          "enabled": true,
          "label": "Fin de semana",
          "startTime": "01:00",
          "durationMinutes": 570,
          "daysOfWeek": ["FRIDAY", "SATURDAY"],
          "priorityOverride": 85
        }
      ]
    },
    {
      "uuid": "9f1c2b7e-4d3a-4c19-9a51-0b7e6d2f1a44",
      "name": "Trabajo",
      "emoji": "💼",
      "colorSeed": 4278239141,
      "enabled": true,
      "priority": 50,
      "sortOrder": 1,
      "templateKey": "WORK",
      "createdAt": "2026-07-02T18:24:55Z",
      "updatedAt": "2026-07-02T18:24:55Z",
      "volumes": {
        "ring": 35,
        "music": null,
        "alarm": 100,
        "notification": 35,
        "voiceCall": null,
        "system": 10,
        "accessibility": null
      },
      "ringerMode": "VIBRATE",
      "vibration": {
        "whenRinging": true,
        "onNotification": true,
        "touchFeedback": null
      },
      "dnd": {
        "mode": null,
        "allowCalls": "ANY",
        "allowMessages": "CONTACTS",
        "allowConversations": "IMPORTANT",
        "allowRepeatCallers": true,
        "allowAlarms": true,
        "allowMedia": true,
        "allowReminders": true,
        "allowEvents": true,
        "suppressedVisualEffects": [],
        "useSystemMode": false,
        "deviceEffects": {
          "grayscale": null,
          "dimWallpaper": null,
          "suppressAmbientDisplay": null
        }
      },
      "options": {
        "restoreOnExit": false,
        "transitionSeconds": 0,
        "skipDuringCall": true,
        "skipIfMediaPlaying": false,
        "notifyOnApply": false
      },
      "schedules": [
        {
          "uuid": "6d0b8f11-3c27-4b93-a0f6-58e2c19d7b40",
          "enabled": true,
          "label": null,
          "startTime": "09:00",
          "durationMinutes": 540,
          "daysOfWeek": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
          "priorityOverride": null
        }
      ]
    }
  ],
  "integrity": {
    "profileCount": 2,
    "scheduleCount": 3,
    "sha256": "1f0c8a53b6d1e4a97c2f0b8d4e5a6371c9d0e2f4a8b7c6d5e4f3a2b1c0d9e8f7"
  }
}
```

Detalles que merecen comentario:

- `startTime` se exporta como `"HH:mm"` legible aunque internamente sea `startMinuteOfDay`. El fichero es para humanos (RF-36); la representación interna es para SQL. El mapper es trivial y está testeado en ambos sentidos.
- `durationMinutes` en lugar de `endTime` hace que `"01:00" + 570` (fin de semana, hasta las 10:30) no necesite ninguna nota sobre medianoche.
- `suppressedVisualEffects` se exporta como **array de nombres**, no como el bitmask entero. El entero es un detalle de la API de Android que no debería filtrarse a un formato que pretende sobrevivir a versiones de plataforma.
- `integrity.sha256` es opcional: se calcula sobre el objeto raíz sin el propio bloque `integrity`, serializado en forma canónica (claves ordenadas, sin espacios). Si está presente y no cuadra, se avisa pero **no se bloquea la importación** — un usuario que edita el JSON a mano es un caso legítimo en una app GPLv3, y bloquearlo sería hostil. Los contadores sí sirven para detectar truncamientos.

### Política de compatibilidad

| Tipo de cambio | ¿Sube `formatVersion`? | Comportamiento |
|---|---|---|
| Añadir un campo opcional con defecto | No | Versiones antiguas lo ignoran (`ignoreUnknownKeys`); versiones nuevas aplican el defecto |
| Añadir un valor a un enum | No | El lector antiguo cae al valor de reserva y emite `UnknownEnumValue` |
| Renombrar o eliminar un campo | Sí | Requiere un paso de migración de JSON |
| Cambiar el significado o la unidad de un campo | Sí | Idem, obligatoriamente |
| Cambiar la estructura (anidar/desanidar) | Sí | Idem |

Reglas duras:

1. **`formatVersion` mayor que el soportado ⇒ rechazo explícito** con `UnsupportedVersion`, y mensaje al usuario del tipo "este fichero se creó con una versión más reciente de RitMute". Nunca intentar leerlo "a ver si cuela".
2. **`formatVersion` menor ⇒ siempre se acepta**, pasando por una cadena de transformaciones sobre `JsonObject` **antes** de decodificar a DTO. Nunca se mantienen DTOs de versiones antiguas.

```kotlin
internal interface JsonUpgrade { val from: Int; val to: Int; fun apply(root: JsonObject): JsonObject }

internal object BackupUpgrades {
    private val chain = listOf(Upgrade1to2, Upgrade2to3)   // ordenada
    const val CURRENT = 3
    fun upgrade(root: JsonObject): JsonObject {
        var version = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: throw MissingVersion
        var current = root
        while (version < CURRENT) {
            val step = chain.first { it.from == version }
            current = step.apply(current); version = step.to
        }
        return current
    }
}
```

3. **Los valores de enum nunca se serializan por el nombre de Kotlin**: cada constante lleva `@SerialName("…")` con un código congelado, y hay un test de "golden file" que falla si alguien renombra una constante.
4. Se mantiene en `androidTest/assets` un **fichero de ejemplo por cada `formatVersion` publicado**, y un test que los importa todos y verifica que el resultado es el esperado. Es la única forma de que la compatibilidad hacia atrás no se rompa por accidente dos versiones después.

---

## Estrategia de migraciones y de tests de migración

### Configuración base

```kotlin
// build.gradle.kts de :core:data
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
android {
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}
```

Los ficheros `schemas/…/1.json`, `2.json`… **se commitean**. Un `schemas/` no versionado convierte cualquier migración futura en adivinanza, y es el fallo por el que la competencia "pierde la configuración al cambiar de versión" (§3.1 de la investigación).

### Reglas

1. **Nunca `fallbackToDestructiveMigration()` en release.** Se permite exclusivamente `fallbackToDestructiveMigrationOnDowngrade()`, y solo porque un downgrade implica que el usuario ha instalado un APK antiguo a mano; incluso ahí conviene exportar antes a un JSON de rescate en `cacheDir`.
2. **`AutoMigration` sí, pero solo para lo trivial**: añadir columna con defecto, añadir tabla, `@RenameColumn`, `@DeleteColumn` con `AutoMigrationSpec`. En cuanto haya que *transformar valores* (rellenar una columna nueva a partir de otras, cambiar unidades), es `Migration` manual. La automática moverá los datos sin convertirlos y el resultado valida pero está mal.
3. **Cada migración es un `object` con nombre, testeado, y jamás se edita una vez publicada.** Si está mal, se corrige con una migración `N → N+1` nueva.
4. Todas las migraciones se registran en una lista única, y hay un test que verifica que la lista cubre todos los saltos consecutivos de `1` a `version`.
5. **Las migraciones no llaman a código de aplicación.** Solo SQL sobre `SupportSQLiteDatabase`. Una migración que invoca un mapper de Kotlin se rompe el día que se refactoriza el mapper, y ese día ya nadie recuerda por qué.

### Ejemplo 1 — migración aditiva (v1 → v2, tabla de excepciones por fecha, RF-19)

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `schedule_date_exceptions` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `schedule_id` INTEGER,
              `date_epoch_day` INTEGER NOT NULL,
              `kind` TEXT NOT NULL,
              `note` TEXT,
              FOREIGN KEY(`schedule_id`) REFERENCES `schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_date_exceptions_schedule_id` ON `schedule_date_exceptions` (`schedule_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sde_schedule_date` ON `schedule_date_exceptions` (`schedule_id`, `date_epoch_day`)")
    }
}
```

El SQL debe copiarse **literalmente** del `2.json` generado por Room (campo `createSql`), incluidos los `ON UPDATE NO ACTION`. Escribirlo a mano produce diferencias que `validateMigration` detecta y que cuestan una tarde.

### Ejemplo 2 — migración semántica (por si se envía v1 con `end_minute`)

Si finalmente se implementa `endTime` tal como está en la especificación y luego se corrige a `durationMinutes`, esta es la migración exacta, y es la razón por la que conviene corregir el modelo **antes** de la 1.0:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE schedules ADD COLUMN duration_minutes INTEGER NOT NULL DEFAULT 1")
        // end <= start ⇒ cruza medianoche; end == start se resuelve como 24 h, decisión documentada
        db.execSQL("""
            UPDATE schedules SET duration_minutes = CASE
                WHEN end_minute > start_minute THEN end_minute - start_minute
                ELSE end_minute + 1440 - start_minute
            END
        """.trimIndent())
        db.execSQL("UPDATE schedules SET duration_minutes = 1440 WHERE duration_minutes = 0")
        // SQLite < 3.35 no tiene DROP COLUMN: reconstrucción de tabla
        db.execSQL("CREATE TABLE schedules_new ( … )")   // copia literal del 3.json
        db.execSQL("""
            INSERT INTO schedules_new (id, uuid, profile_id, enabled, start_minute, duration_minutes,
                                       days_mask, priority_override, label, created_at, updated_at)
            SELECT id, uuid, profile_id, enabled, start_minute, duration_minutes,
                   days_mask, priority_override, label, created_at, updated_at FROM schedules
        """.trimIndent())
        db.execSQL("DROP TABLE schedules")
        db.execSQL("ALTER TABLE schedules_new RENAME TO schedules")
        db.execSQL("CREATE UNIQUE INDEX `index_schedules_uuid` ON schedules (`uuid`)")
        db.execSQL("CREATE INDEX `index_schedules_profile_id` ON schedules (`profile_id`)")
        db.execSQL("CREATE INDEX `index_schedules_enabled_days_mask` ON schedules (`enabled`, `days_mask`)")
    }
}
```

Punto crítico frecuentemente olvidado: en una reconstrucción de tabla con claves foráneas hay que envolver el bloque con `PRAGMA foreign_keys = OFF` … `PRAGMA foreign_key_check` … `ON`. Room ejecuta las migraciones dentro de una transacción y `PRAGMA foreign_keys` no puede cambiarse dentro de una transacción; la vía correcta es sobrescribir `onOpen`/usar el `Migration` con `RoomDatabase.Builder.setJournalMode` adecuado, o —más simple y lo que recomiendo— **preferir siempre migraciones aditivas y dejar la columna vieja muerta** antes que reconstruir una tabla con FKs entrantes. Otra razón más para fijar el modelo ahora.

### Tests de migración

Cuatro niveles, todos obligatorios en CI:

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RitMuteDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    // 1. Esquema: ¿la migración produce la forma que Room espera?
    @Test fun migrate1To2_schemaMatches() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, /* validateDroppedTables = */ true, MIGRATION_1_2)
    }

    // 2. Datos: ¿los valores sobreviven Y se transforman correctamente?
    @Test fun migrate2To3_convertsEndTimeToDuration() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL("INSERT INTO schedules (id, uuid, profile_id, enabled, start_minute, end_minute, days_mask, created_at, updated_at) VALUES (1,'u1',1,1,1410,450,127,0,0)")   // 23:30–07:30
            db.execSQL("INSERT INTO schedules (id, uuid, profile_id, enabled, start_minute, end_minute, days_mask, created_at, updated_at) VALUES (2,'u2',1,1,540,1080,31,0,0)")    // 09:00–18:00
            db.execSQL("INSERT INTO schedules (id, uuid, profile_id, enabled, start_minute, end_minute, days_mask, created_at, updated_at) VALUES (3,'u3',1,1,0,0,127,0,0)")        // caso ambiguo
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)
        val db = openRoomWithAllMigrations()
        assertThat(db.scheduleDao().getById(1)!!.durationMinutes).isEqualTo(480)
        assertThat(db.scheduleDao().getById(2)!!.durationMinutes).isEqualTo(540)
        assertThat(db.scheduleDao().getById(3)!!.durationMinutes).isEqualTo(1440)
    }

    // 3. Recorrido completo: instalación de v1 que salta directamente a la última.
    @Test fun migrateAll_fromV1ToLatest() {
        helper.createDatabase(TEST_DB, 1).use { seedRealisticV1Data(it) }
        Room.databaseBuilder(context, RitMuteDatabase::class.java, TEST_DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build().apply { openHelper.writableDatabase; close() }
    }

    // 4. Cobertura: ninguna versión sin migración.
    @Test fun everyVersionHasAMigration() {
        val versions = ALL_MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        (1 until RitMuteDatabase.VERSION).forEach { v ->
            assertThat(versions).contains(v to v + 1)
        }
    }
}
```

Dos tests más que no son de migración pero cubren el mismo riesgo:

- **Round-trip de exportación/importación**: generar N perfiles con `kotlin.random` semillado (o property-based con Kotest), exportar, borrar la base, importar, y comparar el modelo de dominio campo a campo. Es el criterio de aceptación 6 hecho ejecutable. Debe incluir explícitamente perfiles con todos los campos a `null` y con todos los campos al máximo.
- **Ficheros dorados por versión**: `assets/backup/v1_sample.json`, `v2_sample.json`… importados en cada build. Detecta roturas de compatibilidad hacia atrás que ningún test generativo encuentra.

Sobre el coste de CI: `MigrationTestHelper` exige instrumentación, lo que en GitHub Actions significa emulador. Si eso pone en riesgo el RNF-12 (compilación reproducible en runner limpio), la alternativa es ejecutar estos mismos tests con Robolectric (`@RunWith(AndroidJUnit4::class)` + `@Config(sdk=[34])`), que soporta `MigrationTestHelper` con el SQLite empaquetado y corre en la JVM en segundos. Recomiendo Robolectric para el gate de PR y un job nocturno con emulador real como red de seguridad.

---

## Riesgos de integridad de datos

**1. Borrar un perfil que tiene franjas asociadas.**
Las franjas caen por `ON DELETE CASCADE`, que es lo correcto. Los tres efectos colaterales que el CASCADE **no** resuelve y que deben orquestarse en un `DeleteProfileUseCase`, en este orden: (a) leer y guardar `dnd_zen_rule_id` antes de borrar, y llamar a `removeAutomaticZenRule()` después de que la transacción confirme — si no, queda un Modo huérfano en los ajustes del sistema que el usuario no puede eliminar desde ninguna parte; (b) si el perfil era `applied_profile_id` o `manual_profile_id`, la FK los pone a `NULL` pero **el audio del teléfono sigue con la configuración de ese perfil**: hay que restaurar la línea base y reprogramar en la misma operación; (c) el historial conserva sus filas gracias a `SET NULL` más las columnas desnormalizadas. Test obligatorio: borrar el perfil activo con una franja en curso y verificar que el volumen vuelve a la línea base y que queda registro en el historial.

**2. Los borrados en cascada y la invalidación de `Flow`.**
Room implementa la observación con triggers por tabla, y un borrado en cascada solo dispara el trigger de la tabla hija si `PRAGMA recursive_triggers` está activo. Room lo activa al inicializar el `InvalidationTracker`, así que en principio funciona, pero es una dependencia implícita de comportamiento interno: merece un test explícito ("borro un perfil, el `Flow<List<Schedule>>` reemite una lista sin sus franjas"). Si algún día falla, la app mostrará franjas fantasma en la UI sin ningún error visible.

**3. Importar un JSON corrupto o malicioso.**
Vectores reales, con su mitigación:
- *Fichero enorme o bomba de anidamiento* desde el selector de documentos → límite duro de bytes leídos (p. ej. 5 MB) impuesto al leer del `InputStream`, antes de parsear, y rechazo con `TooLarge`. Nunca `readBytes()` sobre un `Uri` ajeno.
- *Cadenas absurdas* (`name` de 2 MB, `emoji` con 10 000 code points) → validación de longitud campo a campo con truncado y `ValueClamped`.
- *Valores fuera de rango* (`priority: 5000`, `durationMinutes: -3`, `daysOfWeek: []`) → validación de dominio previa a la inserción; `daysOfWeek` vacío hace la franja inaplicable y debe rechazarse o desactivarse, nunca insertarse.
- *Enums desconocidos* → serializador con reserva, nunca excepción; genera `UnknownEnumValue`.
- *UUIDs duplicados dentro del mismo fichero* → `DuplicateUuid`, rechazo del fichero completo.
- *Aplicación parcial* → **todo el import ocurre dentro de un único `withTransaction`**; si algo falla, no se ha tocado nada. Y antes de empezar, exportar automáticamente la configuración actual a `cacheDir/pre-import-backup.json` y devolver ese `undoToken` en `ImportSummary`, para ofrecer "deshacer" durante la sesión.
- Configuración de `Json`: `ignoreUnknownKeys = true`, `isLenient = false`, `allowStructuredMapKeys = false`, `coerceInputValues = false` (que coaccione silenciosamente es exactamente lo que no queremos: preferimos un aviso explícito).
- Tras un import correcto, es **obligatorio** invalidar el estado de automatización, recalcular la siguiente transición y reprogramar la alarma. Un import que deja la alarma antigua programada es un fallo de la promesa número 1.

**4. Prioridades duplicadas.**
Es el caso normal, no el excepcional: seis plantillas con prioridad por defecto empatan todas. La cadena de desempate debe ser total y estable (`priority` ↓, duración ↑, `createdAt` ↑, `uuid` lexicográfico) y estar cubierta por un test que compruebe explícitamente que **no depende del orden de inserción**: barajar la lista de entrada N veces y exigir el mismo ganador. Además, la UI debe mostrar el desempate ("gana Noche por ser más específica"), porque el D8 promete "de forma determinista **y explicada**".

**5. La línea base se pierde o se aplica dos veces.**
Si el proceso muere entre "restaurar el audio" y "borrar la línea base", al arrancar se restauraría de nuevo sobre un estado ya restaurado (idempotente, no grave) o sobre uno que el usuario ya cambió a mano (sí grave: le deshace su cambio). Por eso `consumeBaseline()` es una operación de lectura-y-borrado en una sola transacción, y por eso la línea base es única y no una pila. Añadir `device_fingerprint` para descartar snapshots capturados en otro dispositivo tras una restauración de Android Backup.

**6. Crecimiento y purga del historial.**
RF-27 exige registrar toda aplicación, omisión o error; RF-31 ejecuta un reconciliador cada 30 minutos. Si el reconciliador registra también los no-cambios, son 48 filas al día de puro ruido que en 21 días agotan el límite de 1 000 y **expulsan del historial los eventos que de verdad importan**, arruinando el CU-07. Regla: el reconciliador solo escribe cuando corrige algo, más un latido resumido como máximo una vez al día. Complementariamente, retención diferenciada: los `ERROR` y `PERMISSION` se conservan 90 días y quedan exentos del tope de 1 000 (o tienen un tope propio de 200). La purga corre en el mismo `Worker` diario, dentro de una transacción, seguida de `PRAGMA incremental_vacuum` para que el fichero se reduzca de verdad.

**7. Reloj no monótono.**
`TIME_SET` está contemplado en RF-16 para reprogramar, pero también afecta al almacenamiento: si el usuario retrasa el reloj, se insertan filas con `timestamp_utc` menor que las anteriores. De ahí que el orden canónico del historial sea `id DESC` y no el timestamp, y que `TIME_SET` genere una entrada propia (`LogReason.TIME_CHANGED`) para que el salto sea explicable en la pantalla de historial en vez de parecer un error.

**8. Cambio de zona horaria y horario de verano.**
Las horas son **hora de pared local**, sin excepción: "silencio a las 23:00" significa las 23:00 donde estés. Consecuencias que la capa de datos debe asumir: (a) al cambiar de zona, no se toca ni un dato, solo se recalcula la siguiente transición; (b) en la noche del cambio de horario, una franja 23:00–07:00 dura 7 u 9 horas, no 8, y los tests del cálculo de transiciones deben incluir ambos sentidos de la transición DST; (c) el historial necesita `zone_id` y `utc_offset_seconds` para poder mostrar la hora que el usuario realmente vio.

**9. Arranque directo (direct boot): el riesgo silencioso más caro.**
La base de datos de Room vive en almacenamiento cifrado por credencial y **no es legible hasta que el usuario desbloquea el teléfono**. Si el móvil se reinicia a las 03:00 y el usuario no lo desbloquea hasta las 07:30, un `BOOT_COMPLETED` que necesita leer Room no puede reprogramar nada, y el perfil nocturno de Marc (P2) no se restaura: durante horas el teléfono está en un estado imprevisto. Mitigación en la capa de datos: mantener un `BootCacheRepository` sobre `createDeviceProtectedStorageContext()` con las próximas ~10 transiciones precalculadas (solo instantes y uuid, ningún dato personal), un receptor registrado también para `LOCKED_BOOT_COMPLETED` con `android:directBootAware="true"`, y reconciliación completa contra Room en cuanto llegue el desbloqueo. Este caché se reescribe cada vez que cambia la programación.

**10. Android Auto Backup y WAL.**
Con WAL activo, respaldar los ficheros de `databases/` puede capturar un `-wal` inconsistente y restaurar una base corrupta o desfasada. Además, restaurar una base de otro dispositivo trae `zen_rule_id` que no existen y snapshots con escalas de volumen ajenas. Recomendación: **excluir `databases/` y `datastore/` de las reglas de backup** y respaldar en su lugar el JSON de exportación generado por la propia app (`BackupAgent` que produce el fichero en `onFullBackup`). Se ahorra toda la clase de fallos de restauración parcial y se reutiliza el mismo camino de código que ya hay que testear para el CU-08.

**11. DataStore y Room no comparten transacción.**
Cualquier estado que participe en la resolución de conflictos (pausa global, activación manual, perfil aplicado) tiene que estar en Room; si se reparte entre ambos, existe una ventana en la que la pausa se ha guardado pero la alarma aún no se ha reprogramado, y el resultado depende de qué proceso muera antes. Además: una única instancia de `DataStore` por fichero en todo el proceso (crear dos lanza `IllegalStateException`), y `ReplaceFileCorruptionHandler` configurado para que un fichero de preferencias corrupto no impida arrancar la app.

**12. Reutilización de rowids.**
Con `AUTOINCREMENT` (que es lo que Room genera para `autoGenerate = true`) SQLite no reutiliza ids de filas borradas. Es intencionado y hay que mantenerlo: sin él, borrar el perfil 7 y crear otro haría que el nuevo heredase el id 7, y aunque las FK del historial ya estén a `NULL`, cualquier referencia externa persistida (un `PendingIntent` con `requestCode` derivado del id, un id de notificación, un widget configurado) apuntaría al perfil equivocado. Por eso, además, **los `PendingIntent` y los widgets deben referenciar `uuid`, no `id`**.

**13. Escrituras en el hilo principal desde tile, widget y receptores.**
El Quick Settings tile (RF-44), el widget (RF-45) y los `BroadcastReceiver` de arranque acceden a la capa de datos. Nunca `allowMainThreadQueries()`; los receptores deben usar `goAsync()` con un `CoroutineScope` propio y un plazo por debajo de los 10 segundos, y ceder a `WorkManager` cualquier trabajo que pueda exceder ese margen. Un ANR en el receptor de `BOOT_COMPLETED` es, además, el peor momento posible para tenerlo.

**14. Cifrado de la base: decisión explícita, no omisión.**
Recomiendo **no** usar SQLCipher en la v1: añade varios MB al APK (contra el RNF-04, < 8 MB), rompe la portabilidad del backup y el dato protegido —volúmenes y horarios— ya está bajo el cifrado por credencial del dispositivo. Lo que sí conviene es dejarlo escrito en la documentación como decisión razonada, porque en una app que vende privacidad como diferenciador (D4) alguien preguntará.

agentId: a97d914eb598fd224 (use SendMessage with to: 'a97d914eb598fd224', summary: '<5-10 word recap>' to continue this agent)
<usage>subagent_tokens: 86793
tool_uses: 3
duration_ms: 631208</usage>