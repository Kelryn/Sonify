# 02 — Constitución del equipo y revisión de las especificaciones

**Proyecto:** RitMute
**Fecha:** 12 de agosto de 2026
**Entrada:** `01-ESPECIFICACIONES.md`
**Salida:** especificaciones enmendadas (v1.1) + entrada para `03-PLAN-DESARROLLO.md`

---

## 1. El equipo

| Rol | Ámbito | Informe completo |
|---|---|---|
| **A1 — Arquitecto Android sénior** | Modularización, stack, versiones, decisiones transversales | inline en este documento |
| **A2 — Especialista en audio y APIs de sistema** | AudioManager, NotificationManager, DND, Modes API | inline en este documento |
| **A3 — Especialista en trabajo en segundo plano** | AlarmManager, Doze, buckets, WorkManager, receivers, OEM | inline en este documento |
| **A4 — Especialista en persistencia y datos** | Modelo de dominio, Room, repositorios, JSON | [`revisiones/R4-datos.md`](revisiones/R4-datos.md) |
| **A5 — Especialista en UI/UX y Compose** | Navegación, pantallas, sistema de diseño, onboarding, a11y | [`revisiones/R5-uiux.md`](revisiones/R5-uiux.md) |
| **A6 — Responsable de QA y calidad de sistemas** | Testabilidad, CI, casos de prueba, definición de Hecho | [`revisiones/R6-qa.md`](revisiones/R6-qa.md) |

Los seis leyeron `00-INVESTIGACION.md` y `01-ESPECIFICACIONES.md` y verificaron sus conclusiones contra documentación oficial de Android y, en el caso de A2, contra código fuente de AOSP.

---

## 2. Veredicto conjunto

Las especificaciones son sólidas en visión y en identificación del problema de mercado, y **defectuosas en cinco puntos que habrían roto la v1**. Ninguno de los cinco se habría descubierto compilando: son errores de contrato con la plataforma, no errores de sintaxis.

> **Hallazgo transversal más importante:** las tres promesas del producto se apoyan en APIs que Android ha ido cerrando entre 2024 y 2026. La app sigue siendo viable, pero **por caminos distintos a los descritos** en el documento original.

---

## 3. Hallazgos críticos y decisiones vinculantes

### C1 — El DND ya no se controla con `setInterruptionFilter` (A2, A1)

**Realidad verificada.** Desde Android 15, una app con `targetSdk ≥ 35` **no puede cambiar el estado global de No molestar**. Las llamadas a `setInterruptionFilter` y `setNotificationPolicy` se convierten en una `AutomaticZenRule` *implícita*, y la política efectiva del dispositivo se calcula con el esquema **"gana la más restrictiva"**.

Consecuencias que rompen el diseño original:

- `INTERRUPTION_FILTER_ALL` solo desactiva **nuestra propia** regla. **No podemos apagar el DND** si lo activó el usuario, Bedtime o el modo Conducción.
- La restauración simétrica del filtro de interrupción (RF-15 + RF-22) es imposible: `getInterruptionFilter()` lee el estado efectivo, pero no hay forma de escribirlo de vuelta.

> **Decisión D-C1.**
> 1. Se elimina `DndSettings.useSystemMode`. La ruta se decide **por versión del dispositivo**, no por preferencia: API ≥ 29 → `AutomaticZenRule` propia; API 26–28 → `setInterruptionFilter` (ahí sí es global).
> 2. `DndMode.OFF` se renombra a `DndMode.RELEASE` — "libera mi regla", no "apaga No molestar".
> 3. El filtro de interrupción entra en el snapshot **solo como dato de diagnóstico**, nunca como valor restaurable.
> 4. Se compara `getNotificationPolicy()` (intención) con `getConsolidatedNotificationPolicy()` (efectiva, API 30+) y se avisa al usuario cuando otra regla nos está anulando. Esto convierte una limitación en el diferenciador D6.
> 5. La Modes API deja de ser "una palanca opcional" (D3) y pasa a ser **infraestructura obligatoria**.

### C2 — Los "siete streams" son seis (A2)

**Realidad verificada en AOSP** (`AudioService.setStreamVolume`):

- `STREAM_ACCESSIBILITY` exige `CHANGE_ACCESSIBILITY_VOLUME`, permiso de firma inaccesible para una app de Play. La llamada **retorna sin hacer nada y sin lanzar excepción**.
- `STREAM_VOICE_CALL` a índice 0 exige `MODIFY_PHONE_STATE`. Mismo fallo silencioso.

> **Decisión D-C2.** Seis streams escribibles: `RING, NOTIFICATION, MUSIC, ALARM, SYSTEM, VOICE_CALL`. `ACCESSIBILITY` se conserva **solo como lectura** para el snapshot y el historial, y se marca `UNSUPPORTED` en la UI con explicación. `VOICE_CALL` se acota por abajo a `getStreamMinVolume`. Se corrige la promesa 2 de la visión.

### C3 — El modo degradado descrito no funciona (A3)

**Realidad verificada.** RF-29 decía "degradar a `setWindow` con ventana de 5 min". Dos errores:

1. Para `targetSdk ≥ 31`, la ventana mínima real de `setWindow()` es **10 minutos**; valores menores se recortan en silencio.
2. **`setWindow()` no atraviesa Doze.** Se aplaza a la siguiente ventana de mantenimiento, que de madrugada puede tardar horas — es decir, **el modo degradado fallaría precisamente en el caso de uso estrella** ("silencio de 23:00 a 07:00").

> **Decisión D-C3.** El modo degradado es **`setAndAllowWhileIdle()`** (inexacto pero perfora Doze, límite de 1 disparo cada ~9 min). `setWindow` se elimina del proyecto. RNF-01 se reformula con dos criterios (ver §4).
>
> Se añade un **tercer nivel opt-in**: `setAlarmClock()`, exento de cuotas de bucket y de Doze, ofrecido como "modo de máxima fiabilidad" desde el panel de diagnóstico, advirtiendo del icono de despertador en la barra de estado.

### C4 — `ACTION_USER_PRESENT` no se puede declarar en el manifiesto (A3)

**Realidad verificada.** No está en la lista de excepciones a las restricciones de *broadcasts* implícitos de Android 8.0. Un receiver del manifiesto **nunca lo recibirá** en API 26+. RF-32, tal y como estaba escrito, era inejecutable.

> **Decisión D-C4.** Se sustituye por puntos de reconciliación que sí existen y que además son mejores:
> - `TileService.onStartListening()` — se invoca cada vez que el usuario despliega Ajustes Rápidos. Es el mejor sustituto que existe.
> - `AppWidgetProvider.onUpdate()`.
> - `Application.onCreate()` y `Activity.onStart()`.
> - Opcionalmente, registro en runtime con `RECEIVER_NOT_EXPORTED` como bonus, nunca como garantía.

### C5 — El widget y el tile no son adornos, son fiabilidad (A3)

**Realidad verificada.** En Android 15+, un *force stop* (del usuario o de un gestor OEM) **cancela todos los `PendingIntent` de la app, desactiva sus widgets y bloquea la recuperación** hasta que el usuario interactúa. Ni `BOOT_COMPLETED`, ni `WorkManager`, ni la alarma la rescatan. Además, **una app con un widget activo está exenta del bucket `Restricted`**, que es el más letal (1 alarma al día).

> **Decisión D-C5.** RF-44 (Quick Settings tile) y RF-45 (widget) **suben a Must** y se reclasifican como componentes de fiabilidad. RF-34 (guía por fabricante) también sube a **Must**.

### C6 — El determinismo del algoritmo de conflictos era falso entre dispositivos (A4)

**Problema.** Desempatar por `profileId` ascendente da determinismo *dentro de un dispositivo*, no *entre dispositivos*: tras un import (CU-08), el orden de inserción decide quién gana un solape. Contradice el criterio de aceptación 6.

> **Decisión D-C6.** Doble identidad: `id: Long` local (nunca sale de la app) + `uuid` estable que viaja en el JSON. Cadena de desempate íntegramente estable:
> `priority` desc → duración de franja asc → `profile.createdAt` asc → `profile.uuid` lexicográfico.

---

## 4. Otras enmiendas aceptadas

### 4.1 Modelo de dominio (A4)

| # | Enmienda |
|---|---|
| E-01 | `Schedule` pasa de `startTime`/`endTime` a **`startMinuteOfDay` (0..1439) + `durationMinutes` (1..1440)**. El cruce de medianoche deja de ser un caso especial: es una duración que desborda. Elimina la ambigüedad de `start == end` (¿0 h o 24 h?) y permite expresar 24 h. |
| E-02 | `daysOfWeek` se persiste como **bitmask** `INTEGER` (bit `1 shl (iso-1)`, rango 1..127), no como texto. Permite filtrar en SQL y validar "no vacío" con un `CHECK`. |
| E-03 | **Una sola convención para "no tocar": `null`.** Se eliminan los centinelas `UNCHANGED` de `RingerMode` y `DndMode`. |
| E-04 | Se elimina `gradualTransition: Boolean`; `transitionSeconds = 0` **es** "sin rampa". |
| E-05 | Invariante validado: `ringerMode ∈ {SILENT, VIBRATE}` ⇒ `volumes.ring` se normaliza a `null`. `ringerMode == NORMAL` ⇒ `volumes.ring == 0` es inválido (mínimo 1). |
| E-06 | `AudioSnapshot` guarda **pasos nativos** (`steps`, `minSteps`, `maxSteps`), no porcentajes. Los porcentajes son para la configuración portable; los pasos, para la restauración fiel. |
| E-07 | **Una sola línea base, no una pila de snapshots.** Se captura `BASELINE` solo en `ninguna automatización → alguna`, y se consume en `alguna → ninguna`. Las transiciones perfil→perfil no capturan nada. Una pila se corrompe si el proceso muere a mitad de transición, que es el escenario habitual. |
| E-08 | Nuevas entidades que faltaban por completo: **`AutomationState`** (pausa global, activación manual, estado aplicado, próxima transición) persistida en **Room, no en DataStore** — participa en la resolución de conflictos y necesita integridad referencial. |
| E-09 | `ActivityLogEntry.reason` pasa de `String` libre a **enum `LogReason`** + `paramsJson`. Un texto libre sería intraducible (viola RF-42) e infiltrable (viola RF-46). Se añaden `zoneId` y `utcOffsetSeconds` para poder reconstruir la hora local que el usuario vio. |
| E-10 | `profileNameSnapshot` desnormalizado en el historial: un registro de auditoría debe ser legible aunque se borre el perfil que lo generó. |
| E-11 | Se persiste `zenRuleId` por perfil. Sin él, borrar un perfil deja un **Modo huérfano en los ajustes del sistema, imposible de eliminar desde la app**. |
| E-12 | Campos que faltaban: `sortOrder`, `templateKey`, `Schedule.label`, límite de 8 code points en `emoji`, formato ARGB documentado en `colorSeed`. |

### 4.2 Ámbito recortado o corregido

| # | Enmienda |
|---|---|
| E-13 | **CU-06 se corrige.** `NotificationManager.Policy` solo admite `ANY \| CONTACTS \| STARRED`; **no existe API para una lista de contactos concretos**. Se resuelve llevando al usuario a marcar esos contactos como favoritos en la agenda del sistema, sin `READ_CONTACTS`. |
| E-14 | **La vibración deja de ser un eje del perfil.** `Settings.System.VIBRATE_WHEN_RINGING` está deprecado, exige `WRITE_SETTINGS` (acceso especial) y su propio javadoc dice que las apps no deben aplicarlo. El único control legítimo es `RINGER_MODE_VIBRATE`, que ya forma parte de `ringerMode`. Se corrige la §2.1 de las especificaciones. |
| E-15 | **`READ_PHONE_STATE` se elimina.** `AudioManager.getMode()` no requiere permiso y cubre *más* casos (VoIP incluido) que `TelephonyManager`. `isMusicActive()` para RF-25. La app queda con **`POST_NOTIFICATIONS` como único permiso runtime**. |
| E-16 | **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` se elimina del manifiesto.** Es motivo habitual de rechazo en Play. Se usa `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, que abre la lista del sistema sin declarar nada. |
| E-17 | `AutomaticZenRule` tiene un **límite de 100 reglas por paquete**. Creación perezosa (solo perfiles con DND), barrido de huérfanas al arrancar, tope defensivo en 90. |
| E-18 | Se usa `configurationActivity`, **nunca `ConditionProviderService`** (evita `BIND_CONDITION_PROVIDER_SERVICE`, otro acceso especial). |

### 4.3 Requisitos reformulados para ser medibles (A6)

| Original | Reformulado |
|---|---|
| RNF-01 "≤ 60 s de desviación" | "≤ 60 s **con permiso exacto concedido y ≥ 9 min desde la transición anterior**; en modo degradado, *best effort* con desviación típica ≤ 15 min y sin garantía" |
| RNF-02 "batería < 1 %/día" | "Sin FGS permanente; se permite un FGS efímero (< 10 s) por transición. **Presupuesto de wakelock < 5 min/día**, verificable en Android Vitals" |
| RNF-03 "< 500 ms" | Objetivo **no bloqueante**, medido en nightly con Macrobenchmark; avisa por regresión > 20 % |
| RNF-04 "APK < 8 MB" | Medido sobre el **APK de release minificado**; el CI construye también `assembleRelease` |
| RNF-06 "≥ 80 % en `:core:domain` y `:core:scheduler`" | "**≥ 90 % de ramas en `:core:domain`**". `:core:system` es adaptadores puros sin lógica y no tiene objetivo de cobertura |
| RF-31 "WorkManager cada 30 min" | **Cada 1 h con flex de 15 min**, sin constraints, renombrado de "reconciliador" a **watchdog** (repara deriva, no garantiza precisión). Las cuotas por bucket hacen irreal cualquier cosa más frecuente, y Android 16 las endurece |

---

## 5. Decisiones arquitectónicas vinculantes

### 5.1 Modularización: 7 módulos, no 12 (A1)

La justificación habitual de la modularización fina (builds incrementales rápidos en local) **no aplica**: no hay build local, y cada módulo es una oportunidad más de fallo en un ciclo de feedback que se mide en minutos de CI.

```
:app              Application, MainActivity, NavHost, DI raíz, TileService,
                  widget Glance, todos los receivers, onboarding.
:core:domain      Kotlin JVM PURO. Modelos, casos de uso, ConflictResolver,
                  NextTransitionCalculator, política de snapshot.
                  Sin Android. Sin Hilt. Sin Room.
:core:data        Room 3, DataStore, repositorios, import/export JSON, mappers.
:core:system      AudioController, DndController, ZenRuleRegistrar,
                  AlarmScheduler, Reconciler, FGS. Adaptadores finos.
:core:ui          Tema M3, tipografía, componentes Compose comunes.
:feature:profiles Lista, editor de perfiles, franjas, plantillas, línea de tiempo.
:feature:tools    Historial, ajustes, diagnóstico, copia de seguridad.
```

Reglas verificadas por un test de arquitectura, no por buena voluntad:

- `:core:domain` no depende de nada. Un paso de CI **falla si aparece `import android.`** en ese módulo. Es el paso más barato y más valioso del pipeline.
- `:core:system` **no depende de `:core:data`**: el reconciliador recibe los datos, no los busca.
- Los `:feature:*` nunca dependen entre sí.

Nota: con `minSdk = 26`, `java.time` está disponible de forma nativa, así que `:core:domain` es JVM puro **sin core library desugaring**.

### 5.2 Invariante arquitectónico central: *level-triggered* (A3)

> **La alarma no lleva información sobre qué transición ejecutar. Es únicamente una señal de "reevalúa ahora".**

La lógica es siempre: *calcula el estado deseado en `now` — que es una función pura de `t` — y converge hacia él.* Con esa propiedad, una alarma perdida, duplicada, adelantada o retrasada **es inofensiva**, y no hace falta reproducir transiciones perdidas. Esto es lo que hace viable la estrategia de alarma única, y **no estaba escrito en ningún sitio**.

Corolario: no se meten extras significativos en el `Intent`. La igualdad de `PendingIntent` los ignora, y es un generador clásico de datos rancios.

### 5.3 Flujo de reconciliación

```
reconcile(trigger)                          [Mutex global de proceso]
  1. now = clock.now(); world = repo.loadSchedulingWorld()
  2. REPROGRAMAR PRIMERO   next = nextTransition(world, now, horizonte 7 días)
                           alarmScheduler.schedule(next)
  3. desired = resolveDesiredState(world, now)
  4. plan = planReconciliation(desired, applied, snapshot)
  5. audioApplier.execute(plan)   → historial
  6. finally: REPROGRAMAR OTRA VEZ si procede
```

Los pasos 2 y 6 son la mitigación del fallo más probable en producción: una excepción entre "aplicar" y "reprogramar" rompería la cadena **para siempre**.

### 5.4 `nextTransition` tiene cuatro fuentes, no una (A1, A3)

El cálculo original solo miraba bordes de franja. Debe considerar:

1. Bordes de inicio/fin de franja.
2. **Expiración de la activación manual** (RF-06).
3. **Expiración de la pausa global** (RF-17).
4. **Transiciones de la regla de zona horaria** (DST).
5. **Latido de horizonte (7 días)**: nunca devolver `null`, o la app deja de existir para el sistema y cae a `Restricted` sin recuperación.

> Sin la fuente 2, un perfil activado "2 horas" **nunca se desactivaría solo** si no hay ningún borde de franja en esas 2 horas. Es el fallo de la promesa central en el caso de uso más simple (CU-04, persona P4).

### 5.5 Política de horario de verano (A3)

Elegida y documentada como decisión de producto — **"preferir cobertura"**, para que una franja nunca resulte más corta de lo que el usuario pidió:

- **Hueco** (primavera, hora local inexistente) → colapsar al instante del salto.
- **Solape** (otoño, hora local duplicada) → el **inicio** usa la primera ocurrencia; el **fin**, la segunda.

No puede ser un efecto colateral de `ZonedDateTime.of()`, que hace lo contrario.

### 5.6 Verificación tras escribir (A2)

Los fallos silenciosos son la peor categoría: `STREAM_ACCESSIBILITY`, `VOICE_CALL` a 0, `isVolumeFixed()`, capas OEM que revierten `setRingerMode`, y el *background audio hardening* de Android 17.

> **Decisión.** **Toda escritura de audio se verifica releyendo.** `apply()` compara el valor post-escritura y devuelve `SilentlyIgnored` si difiere. Es la única defensa que cubre todos esos casos a la vez — y alimenta directamente el diferenciador D6.

### 5.7 El módulo de audio no propaga excepciones de plataforma

Toda negativa esperada del sistema (falta de permiso, DND activo, stream no soportado, volumen fijo, restricción de segundo plano) se convierte en un **valor de retorno tipado** (`Applied | Clamped | Refused | SilentlyIgnored`). Solo se lanza ante errores del programador. Esto es lo que hace RNF-09 demostrable con tests en lugar de confiarlo a `try/catch` dispersos.

---

## 6. Stack fijado (A1)

Versiones reales verificadas a 12/08/2026. **No se toca ninguna hasta la v1.0**; cualquier bump va en un PR aislado.

| Componente | Versión | Nota |
|---|---|---|
| Android Gradle Plugin | 9.2.0 | AGP 9 lleva Kotlin integrado: **eliminar `org.jetbrains.kotlin.android`** de todos los módulos |
| Gradle | 9.4.1 | Mínimo exigido por AGP 9.2 |
| JDK | Temurin 21 (runner) / toolchain 17 | |
| Kotlin | 2.3.21 | No usar 2.4.0: salió el 11/08/2026, cero rodaje |
| Compose Compiler Plugin | 2.3.21 | Debe coincidir con Kotlin |
| KSP | 2.3.11 | KSP2 ya desacoplado de la versión de Kotlin |
| Compose BOM | 2026.06.01 | |
| Room | **androidx.room3 3.0.1** | Ojo: Room cambió de grupo Maven. Exige KSP |
| Hilt | 2.60.1 | **Mínimo con AGP 9**; 2.59.x rompía la compilación |
| WorkManager | 2.11.0 | |
| DataStore | 1.2.0 | |
| Navigation Compose | 2.9.8 | No usar Navigation 3 (fuera de estable) |
| Glance | 1.1.1 | Única estable; si da guerra con compileSdk 36, el widget se recorta |
| kotlinx-serialization-json | 1.11.0 | |
| Kover | 0.9.9 | Preferible a JaCoCo con AGP 9 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | Play exige API 36 desde el 31/08/2026 |

Todo en `gradle/libs.versions.toml`, sin una sola versión escrita a mano en un `build.gradle.kts`.

---

## 7. Riesgos nuevos identificados por el equipo

| # | Riesgo | Mitigación |
|---|---|---|
| N1 | **Política de Play sobre wakelocks** (vigente desde marzo de 2026): superar ~2 h acumuladas en 24 h penaliza la visibilidad | Presupuesto < 5 min/día; watchdog cada 2 h en vez de cada 30 min |
| N2 | **Direct Boot**: `BOOT_COMPLETED` no llega hasta que el usuario desbloquea. Un reinicio a las 03:00 con desbloqueo a las 08:00 pierde **todas** las transiciones nocturnas | `LOCKED_BOOT_COMPLETED` + `directBootAware="true"` + plan mínimo en almacenamiento protegido por dispositivo |
| N3 | **Force stop**: no hay defensa técnica posible | Detectarlo con `ApplicationStartInfo.wasForceStopped()` (API 35+) y **explicarlo** en el diagnóstico: "tu móvil detuvo RitMute el X; entre X e Y las reglas no se aplicaron" |
| N4 | **Sin `INTERNET` no hay crash reporting** | `UncaughtExceptionHandler` a fichero local rotado + "compartir informe" vía `ACTION_SEND` |
| N5 | **Android Backup + Room con WAL = base corrupta** | `dataExtractionRules` excluyendo el fichero de Room; el backup serializa el JSON de exportación |
| N6 | **Android 17 (beta)**: *background audio hardening* ignora en silencio los cambios de volumen desde segundo plano | FGS efímero por transición + verificación tras escribir |
| N7 | **Rampa de volumen desde un receiver**: no cabe en los ~10 s de `onReceive` | `goAsync()` + FGS; rampa deshabilitada automáticamente en modo degradado |
| N8 | **Reglas Zen huérfanas** tras borrar perfiles o reimportar JSON | Ciclo de vida explícito + barrido al arrancar |
| N9 | **Marca y nombre de paquete** no verificados; irreversibles tras publicar | Verificar antes del primer commit |

---

## 8. Recorte de alcance acordado

Para que la v1.0 se termine y sea sólida, el equipo acuerda dejar fuera:

- **Excepciones por fecha** (RF-19) → v1.1, con la tabla ya prevista en la migración v1→v2.
- **Efectos de dispositivo de la Modes API** (`ZenDeviceEffects`) → v1.1, cosmético.
- **Sonda destructiva de acoplamiento tono/notificación** (RF-10 fase 2) → v1.0 usa solo la heurística no destructiva y un tercer estado `UNKNOWN`.
- **Macrobenchmark y Baseline Profile** → nightly, no bloqueante.

---

## 9. Estado de las especificaciones

`01-ESPECIFICACIONES.md` queda **enmendado por este documento**. En caso de conflicto entre ambos, **prevalece el 02**. El plan de desarrollo (`03`) se construye sobre las especificaciones ya enmendadas.
