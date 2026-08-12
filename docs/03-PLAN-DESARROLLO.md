# 03 — Plan de desarrollo

**Proyecto:** SonoRitmo
**Fecha:** 12 de agosto de 2026
**Entradas:** `01-ESPECIFICACIONES.md` (enmendado por `02-REVISION-EQUIPO.md`)
**Estado:** aprobado por los seis roles del equipo

---

## 1. Restricción operativa que condiciona todo el plan

> El entorno de desarrollo **no tiene acceso a `dl.google.com` ni a `maven.google.com`**. No hay Android SDK y no se puede compilar localmente. Sí hay **Kotlin 2.1.20** (`/opt/kotlinc`) para verificar módulos de Kotlin puro.

Consecuencias de primer orden, que no son un inconveniente sino el eje del plan:

1. **GitHub Actions es el entorno de compilación autoritativo.** Si CI está rojo, la rama está rota.
2. **`:core:domain` es JVM puro y se verifica localmente con `kotlinc`.** Por eso concentra toda la lógica decidible del sistema. Esta decisión, tomada por otras razones (testabilidad), resulta ser también la que permite trabajar sin SDK.
3. **PR #0 no contiene lógica de negocio.** Es el esqueleto de compilación. No se escribe una línea de dominio hasta que esté verde.
4. Cada iteración fallida cuesta minutos de CI, no segundos. Eso justifica 7 módulos en lugar de 12 y justifica escribir el código con más cuidado del habitual.

---

## 2. Fases

| Fase | Nombre | Entregable | Responsable principal | Estado |
|---|---|---|---|---|
| **F0** | Esqueleto de compilación | Proyecto Gradle de 7 módulos, catálogo de versiones, CI verde | A1 Arquitecto | — |
| **F1** | Núcleo de dominio | `:core:domain` completo + suite de tests | A1 + A3 | — |
| **F2** | Persistencia | `:core:data`: Room 3, DAOs, repositorios, JSON | A4 Datos | — |
| **F3** | Capa de sistema | `:core:system`: audio, DND, scheduler, reconciliador | A2 + A3 | — |
| **F4** | Sistema de diseño e interfaz | `:core:ui`, `:feature:profiles`, `:feature:tools` | A5 UI/UX | — |
| **F5** | Ensamblaje | `:app`: manifiesto, DI, navegación, receivers, tile, widget | A1 + A5 | — |
| **F6** | QA | Revisión cruzada, tests, verificación estática, informe | A6 QA | — |
| **F7** | Publicación | README, LICENSE, CI, CHANGELOG, push a GitHub | A1 + A6 | — |

Las fases F2, F3 y F4 son **paralelizables** una vez cerrada F1, porque solo dependen de los contratos que F1 fija. Se asignan a agentes distintos con directorios disjuntos.

---

## 3. F0 — Esqueleto de compilación

**Objetivo:** que `./gradlew assembleDebug` funcione en un runner limpio con siete módulos vacíos.

### Trabajo

1. `settings.gradle.kts` con `repositoriesMode = FAIL_ON_PROJECT_REPOS` y los 7 módulos.
2. `gradle/libs.versions.toml` con las versiones exactas de §6 del documento 02.
3. `build.gradle.kts` raíz: `buildscript` que fija KGP 2.3.21 y KSP 2.3.11 por encima de las que arrastra AGP 9.
4. `gradle.properties`: `configuration-cache`, `parallel`, `caching`, `android.useAndroidX`, JVM args.
5. Wrapper de Gradle 9.4.1 commiteado.
6. `build.gradle.kts` de cada módulo. **Ninguno aplica `org.jetbrains.kotlin.android`** (AGP 9 lo integra).
7. `.github/workflows/ci.yml` con los jobs `static`, `unit` y `build`.
8. `.gitignore`, `LICENSE` (GPLv3), `README.md` inicial.

### Criterio de salida

- CI verde en los tres jobs.
- El guardián de pureza pasa (no hay `import android.` en `:core:domain`).
- El verificador de manifiesto pasa (no hay `INTERNET`).

---

## 4. F1 — Núcleo de dominio (`:core:domain`)

Es la fase más importante del proyecto. Todo lo que decide algo vive aquí, es puro, es determinista y es verificable sin Android.

### 4.1 Modelos

| Archivo | Contenido |
|---|---|
| `model/Ids.kt` | `ProfileId`, `ScheduleId` como `@JvmInline value class`; los `uuid` como identidad de negocio |
| `model/AudioStream.kt` | Enum de 7 valores + `isWritable` (`ACCESSIBILITY` = false) |
| `model/VolumeSettings.kt` | Seis `Int?` en porcentaje 0..100. `null` = no tocar |
| `model/RingerMode.kt` | `NORMAL, VIBRATE, SILENT` (sin `UNCHANGED`) |
| `model/DndSettings.kt` | `DndMode { RELEASE, PRIORITY, ALARMS_ONLY, TOTAL_SILENCE }`, `CallPolicy`, `MessagePolicy`, `ConversationPolicy`, `suppressedVisualEffects` |
| `model/ProfileOptions.kt` | `restoreOnExit`, `transitionSeconds` (0..15), `skipDuringCall`, `skipIfMediaPlaying`, `notifyOnApply` |
| `model/SoundProfile.kt` | Agregado + `validate()` con los invariantes E-05 |
| `model/Schedule.kt` | `startMinuteOfDay` + `durationMinutes` + `daysMask` |
| `model/AudioSnapshot.kt` | `StreamLevel(steps, minSteps, maxSteps)`, `SnapshotKind { BASELINE }` |
| `model/AutomationState.kt` | Pausa global, activación manual, estado aplicado |
| `model/ActivityLog.kt` | `LogType`, `LogReason`, `ActivityLogEntry` |
| `model/SchedulingWorld.kt` | Instantánea inmutable: perfiles + franjas + estado + `ZoneId`. Único input de las funciones puras |
| `model/DesiredState.kt` | Resultado de la resolución: perfil ganador, origen y razón |

### 4.2 Lógica pura

| Archivo | Responsabilidad |
|---|---|
| `logic/ScheduleWindows.kt` | Expansión de una franja a instantes concretos en una fecha, con `resolveLocal(date, minute, zone, edge)` y la política DST de "preferir cobertura" |
| `logic/ConflictResolver.kt` | El algoritmo de §7.3, con la cadena de desempate estable de D-C6 |
| `logic/NextTransitionCalculator.kt` | Las cinco fuentes de §5.4 del doc 02 + fusión de transiciones a menos de 9 min + latido de 7 días |
| `logic/ReconciliationPlanner.kt` | `(desired, applied, snapshot) → ReconciliationPlan` con la política de línea base única (E-07) |
| `logic/VolumeMath.kt` | `percentToIndex` / `indexToPercent` respetando `minSteps` |
| `logic/Templates.kt` | Las seis plantillas de RF-07 |
| `port/Clock.kt`, `port/ZoneProvider.kt` | Interfaces inyectables. **Prohibido** `System.currentTimeMillis()` y `ZoneId.systemDefault()` fuera de aquí |

### 4.3 Tests (obligatorios antes de cerrar la fase)

Los 36 casos límite del informe de A3 y los casos 🟢 del informe de A6. Bloques mínimos:

1. Cruce de medianoche y días de la semana (6 casos).
2. Horario de verano en `Europe/Madrid` y `America/Santiago` (7 casos).
3. Cambio de zona horaria y de hora del sistema (5 casos).
4. Alarma única, latido, fusión, idempotencia, level-triggered (7 casos).
5. Activación manual y pausa global como fuentes de transición (4 casos).
6. Snapshot y restauración, incluido snapshot rancio (3 casos).
7. Resolución de conflictos: prioridad, duración, `createdAt`, `uuid` (4 casos).

### Criterio de salida

- `kotlinc` compila el módulo **localmente** sin errores ni warnings.
- Los tests pasan en CI con ≥ 90 % de cobertura de ramas.
- Cero `import android.`.

---

## 5. F2 — Persistencia (`:core:data`)

- Esquema Room 3 versión 1: `profiles`, `schedules`, `activity_log`, `audio_snapshots`, `automation_state`.
- Índices, FK y `ON DELETE` explícitos; `days_mask` con `CHECK (days_mask BETWEEN 1 AND 127)`.
- Converters de enums a códigos `TEXT` estables (nunca `ordinal`).
- DAOs con consultas fijadas de antemano (filtro de franjas por máscara de día en SQL).
- Repositorios con `Flow` y mappers entidad ↔ dominio en ambos sentidos.
- `schemaLocation` exportado y **commiteado** (requisito de RF-39).
- Import/export JSON con `kotlinx.serialization`, campo `schemaVersion` obligatorio y política de compatibilidad documentada.
- DataStore **solo** para preferencias de UI y estado de onboarding.

**Criterio de salida:** ida y vuelta de exportación/importación sin pérdida, verificada con test.

---

## 6. F3 — Capa de sistema (`:core:system`)

### Audio

- `VolumeController` con `apply()` que **verifica releyendo** y devuelve `AudioOpResult` tipado.
- `RingerController` con comprobación previa de `isNotificationPolicyAccessGranted()` y `try/catch (SecurityException)` como red.
- Orden de aplicación determinista (objetivo `NORMAL` → primero ringer, luego volúmenes; objetivo `SILENT`/`VIBRATE` → primero streams no aliados, ringer al final).
- `AudioCapabilities` con `isVolumeFixed`, soporte por stream y heurística no destructiva de acoplamiento tono/notificación.
- Flags siempre `0`: nunca `FLAG_SHOW_UI` ni `FLAG_PLAY_SOUND`.

### DND

- `ZenRuleRegistrar` (API ≥ 29) con `configurationActivity`, id estable, tope de 90 reglas, barrido de huérfanas.
- `DndController` que enruta por versión y expone `isOverriddenByStricterRule()`.

### Scheduler

- `AlarmScheduler` con tres niveles: `ALARM_CLOCK` (opt-in) → `setExactAndAllowWhileIdle` → `setAndAllowWhileIdle`.
- Un único `requestCode` constante, `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`, `RTC_WAKEUP`, **sin extras significativos**.
- `SchedulerCoordinator` como único punto de entrada, serializado con `Mutex`, implementando el flujo de §5.3 del doc 02.
- `WatchdogWorker` periódico de 1 h con flex de 15 min, sin constraints.
- `SchedulerHealthRepository` en DataStore con `nextScheduledAt`, `repairsCount`, `mode`, `lastForceStopDetectedAt`.

**Criterio de salida:** ninguna función de este módulo toma una decisión temporal; todas delegan en `:core:domain`.

---

## 7. F4 — Interfaz (`:core:ui`, `:feature:profiles`, `:feature:tools`)

### Principio rector

La pantalla principal responde en menos de un segundo a tres preguntas que **ninguna app de la competencia responde**:

> ¿Qué está sonando ahora? · ¿Por qué? · ¿Qué viene después?

De ahí el `ActiveStateBanner` persistente como elemento identitario.

### Pantallas de la v1

| Ruta | Contenido |
|---|---|
| `RouteProfiles` | Banner de estado + lista de perfiles. **Tocar activa, no edita** (corrige la queja de la competencia). Edición desde icono explícito |
| `RouteProfileEditor` | Volúmenes por stream, modo de timbre, DND, opciones, franjas |
| `RouteScheduleEditor` | Hora de inicio, duración, días de la semana |
| `RouteTimeline` | Línea de tiempo semanal con el perfil activo por hora |
| `RouteHistory` | Historial filtrable, con texto compuesto desde `LogReason` |
| `RouteSettings` | Ajustes, acceso a diagnóstico y copia de seguridad |
| `RouteDiagnostics` | Panel de salud: permisos, bucket, alarma pendiente, `repairsCount`, guía por fabricante |
| `RouteBackup` | Exportar/importar JSON |
| Onboarding | Bienvenida → DND → Alarma exacta → Notificaciones → Batería (condicional) → Primer perfil |

### Reglas

- Jerarquía de 2 niveles máximo.
- Estado de permisos reevaluado en `ON_RESUME` (las pantallas del sistema no devuelven resultado).
- Deep links `sonoritmo://` (nunca `http`, coherente con la ausencia de `INTERNET`).
- `rememberSaveable` + `SavedStateHandle` para sobrevivir a la muerte del proceso mientras el usuario está en los ajustes del sistema.
- Cero cadenas embebidas en código; español e inglés completos; `locales_config.xml`.

---

## 8. F5 — Ensamblaje (`:app`)

- `AndroidManifest.xml` con la lista blanca cerrada de permisos y **sin `INTERNET`**.
- Receivers: `TransitionAlarmReceiver` (privado), `BootReceiver` (`LOCKED_BOOT_COMPLETED` + `BOOT_COMPLETED` + variantes OEM, `directBootAware`), `TimeChangeReceiver`, `PackageReplacedReceiver`, `ExactAlarmPermissionReceiver`.
- `TileService` con reconciliación en `onStartListening()`.
- Widget Glance (recorte candidato si Glance 1.1.1 falla con compileSdk 36).
- FGS efímero para aplicar el perfil y la rampa.
- Hilt como DI, con contingencia declarada: si el plugin falla con AGP 9, se sustituye por un contenedor manual en lugar de depurar contra CI a ciegas.
- Edge-to-edge obligatorio y navegación sin `onBackPressed` (consecuencias de `targetSdk 36`).

---

## 9. F6 — QA

| Capa | Tipo de test | Dónde se ejecuta |
|---|---|---|
| `:core:domain` | Unitarios JVM + basados en propiedades | Local (`kotlinc`) y CI |
| `:core:data` | Unitarios + tests de migración de Room | CI |
| `:core:system` | Instrumentados con permisos concedidos y revocados | CI (emulador) |
| UI | Compose UI tests + `AccessibilityChecks` | CI (emulador) |
| Proyecto | Guardián de pureza, verificador de manifiesto, tamaño de APK, lint | CI (estático) |

**Lista cerrada de lo que rompe el build:** test en rojo, cobertura bajo umbral, `import android.` en módulo puro, permiso fuera de la lista blanca, APK de release > 8 MB, error de lint (incluida `MissingTranslation`), hallazgo de detekt/ktlint, warning de Kotlin (`-Werror`), fallo de `assembleDebug`/`assembleRelease`, falta un fichero obligatorio del repositorio.

---

## 10. F7 — Publicación

1. `README.md` con capturas conceptuales, argumento de privacidad verificable y matriz de compatibilidad.
2. `LICENSE` GPLv3.
3. `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, plantillas de issue y PR.
4. `CHANGELOG.md` con la v1.0.0.
5. `docs/00`…`docs/06` completos.
6. Historial de commits limpio y ordenado por fases.
7. Push al repositorio del usuario y creación del tag `v1.0.0`.

---

## 11. Orden de ejecución y dependencias

```
F0 ──► F1 ──┬──► F2 ──┐
            ├──► F3 ──┼──► F5 ──► F6 ──► F7
            └──► F4 ──┘
```

F2, F3 y F4 se ejecutan en paralelo por agentes distintos, sobre directorios disjuntos, contra los contratos congelados en F1.

---

## 12. Definición de "Hecho" para la v1.0

1. CI verde en `static`, `unit` y `build`.
2. `:core:domain` compila con `kotlinc` en local, sin warnings.
3. Cobertura de ramas ≥ 90 % en `:core:domain`.
4. Los 36 casos límite tienen test y pasan.
5. El manifiesto no contiene `INTERNET` y todos sus permisos están en la lista blanca.
6. Exportación → importación sin pérdida, verificada por test.
7. Ninguna función de `:core:system` toma decisiones temporales.
8. `docs/00`…`docs/06` completos y coherentes entre sí.
9. README, LICENSE, CONTRIBUTING y CHANGELOG presentes.
10. Repositorio publicado con tag `v1.0.0`.
