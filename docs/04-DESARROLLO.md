# 04 — Desarrollo

**Proyecto:** RitMute
**Fecha:** 12 de agosto de 2026
**Entrada:** `03-PLAN-DESARROLLO.md`
**Estado:** fases F0–F5 completadas

---

## 1. Resumen

| | |
|---|---|
| Módulos Gradle | 7 |
| Ficheros Kotlin | 113 |
| Líneas de Kotlin | ~14 800 |
| Idiomas de interfaz | 2 (es, en), 210 cadenas por idioma |
| Permisos declarados | 6 |
| Permisos de runtime | 1 (`POST_NOTIFICATIONS`) |
| Verificación local ejecutada | 107 comprobaciones, 0 fallos |

---

## 2. Decisión de versiones

El documento 02 fijó un stack basado en AGP 9.2 / Kotlin 2.3.21 / Room 3. Al empezar a
escribir el proyecto se tomó una decisión distinta, y conviene dejarla explicada porque
contradice una decisión vinculante previa.

**Lo que se hizo:** AGP 8.10.1, Gradle 8.14.3, Kotlin 2.1.20, KSP 2.1.20-2.0.1, Room 2.7.1
(grupo `androidx.room`), Hilt 2.56.2, Compose BOM 2025.04.01. `compileSdk`, `targetSdk` y
`minSdk` se mantienen en 36 / 36 / 26, que es lo que exige Play.

**Por qué:**

1. **No se puede compilar en el entorno.** Sin acceso a `maven.google.com`, cada versión es
   una apuesta que solo se resuelve en CI. Un stack que el equipo puede razonar con
   confianza vale más que uno más moderno del que no se puede verificar nada.
2. **El compilador local es Kotlin 2.1.20.** Fijar esa misma versión significa que
   `:core:domain` —la pieza que concentra toda la lógica— se compila y se **ejecuta** aquí
   con el compilador exacto que usará CI. Esa verificación real pesa más que estar al día.
3. **AGP 9 introduce Kotlin integrado y Room cambia de grupo Maven.** Dos migraciones
   simultáneas, a ciegas, en el primer build de un proyecto nuevo, es la peor manera
   posible de gastar iteraciones de CI.

**Qué queda pendiente:** el salto a AGP 9 + Room 3 es trabajo de v1.1, en un PR aislado y
con CI verde de referencia. Está registrado en `06-PUBLICACION.md`.

El ingeniero de build verificó artefacto por artefacto que **las 24 dependencias del
catálogo existen y son mutuamente compatibles**, y que AGP 8.10 soporta `compileSdk 36`.

---

## 3. F0 — Esqueleto

- `settings.gradle.kts` con `FAIL_ON_PROJECT_REPOS` y siete módulos.
- `gradle/libs.versions.toml` como única fuente de versiones; ningún `build.gradle.kts`
  contiene una versión escrita a mano.
- Wrapper de Gradle 8.14.3 commiteado (jar incluido).
- `.github/workflows/ci.yml` con cuatro trabajos: `static`, `selfcheck`, `unit`, `build`.

---

## 4. F1 — Núcleo de dominio

21 ficheros, Kotlin JVM puro, sin una sola dependencia. Es la pieza que justifica toda la
arquitectura.

### Lo que vive aquí

| Archivo | Responsabilidad |
|---|---|
| `logic/ScheduleWindows.kt` | Conversión de horas locales recurrentes a instantes absolutos, con la política DST escrita a mano |
| `logic/ConflictResolver.kt` | Qué perfil debe estar activo en un instante. Función total y pura |
| `logic/NextTransitionCalculator.kt` | Cuándo despertar. Cinco fuentes, fusión de transiciones próximas, latido de horizonte |
| `logic/ReconciliationPlanner.kt` | Qué hacer, dada la diferencia entre lo deseado y lo aplicado. Política de línea base única |
| `logic/VolumeMath.kt` | Porcentaje ↔ paso nativo, respetando el mínimo del dispositivo |
| `logic/Templates.kt` | Las seis plantillas |
| `model/*` | 14 ficheros de modelo, todos inmutables |
| `port/Ports.kt` | `TimeSource`, `ZoneProvider`, `UuidGenerator` |

### Verificación local

Como el contenedor no puede resolver JUnit ni Truth, se escribió
`tools/selfcheck/DomainSelfCheck.kt`: **un arnés de pruebas sin dependencias**, compilable
y ejecutable con `kotlinc` a secas.

```
$ kotlinc $(find core/domain/src/main -name '*.kt') tools/selfcheck/DomainSelfCheck.kt \
    -include-runtime -d selfcheck.jar && java -jar selfcheck.jar
RitMute :core:domain self-check
  checks run : 107
  failures   : 0
  OK
```

Esto no es un sustituto de la suite de JUnit —que también existe y corre en CI— sino la
red que permitió detectar en local, y no en un runner, que la aritmética de horario de
verano estaba bien. Las 107 comprobaciones cubren cruce de medianoche, huecos y solapes de
DST en `Europe/Madrid`, resolución de conflictos, las cinco fuentes de transición, el ciclo
de vida del snapshot, la conversión de volumen y las seis plantillas.

El trabajo `selfcheck` de CI ejecuta exactamente lo mismo, para que las dos vías no puedan
divergir.

---

## 5. F2 — Persistencia

Room 2.7.1 con esquema exportado, cinco tablas y las decisiones del informe R4:

- Doble identidad: `id` local que nunca sale de la app, `uuid` estable que viaja en el JSON.
- `days_mask` como entero indexado, filtrable en SQL.
- Enums persistidos por **código estable**, nunca por `ordinal`.
- Historial que sobrevive al borrado del perfil que lo generó, vía `profile_name_snapshot`
  desnormalizado a propósito.
- `AutomationState` en Room, no en DataStore, con claves foráneas `ON DELETE SET NULL`.
- Exportación e importación JSON con `schemaVersion`, dos modos (fusionar / reemplazar) e
  informe de correcciones y rechazos.

---

## 6. F3 — Capa de sistema

Adaptadores finos sobre `AudioManager`, `NotificationManager`, `AlarmManager` y
`WorkManager`. Ni una decisión temporal: todas se delegan a `:core:domain`.

Las tres piezas que definen el módulo:

1. **Verificación tras escribir.** Cada `setStreamVolume` y cada `setRingerMode` se relee.
   Si el valor no coincide, se devuelve `SilentlyIgnored` y se registra. Es la única defensa
   posible contra `STREAM_ACCESSIBILITY`, contra `isVolumeFixed()`, contra las capas OEM que
   revierten el modo de timbre y contra el *background audio hardening* de Android 17.
2. **Nada de excepciones de plataforma hacia arriba.** `Applied | Clamped | Refused |
   SilentlyIgnored`, tipado. Solo se lanza ante errores del programador.
3. **`SchedulerCoordinator` como punto de entrada único**, serializado con un `Mutex`,
   reprogramando antes de aplicar y otra vez en un `finally`.

---

## 7. F4 y F5 — Interfaz y ensamblaje

- `:core:ui` con tema Material 3 (claro, oscuro, dinámico), colores **semánticos**
  independientes del acento —porque el color dinámico lo controla el usuario— y componentes
  que siempre acompañan el color de un icono y un texto.
- `ActiveStateBanner` como elemento identitario: responde a *qué suena*, *por qué* y *qué
  viene después*.
- **Tocar un perfil lo activa.** La edición está detrás de un icono explícito. Es la
  respuesta directa a la queja de usabilidad más repetida de la competencia.
- `:app` con cinco receivers, servicio en primer plano efímero, mosaico de Ajustes Rápidos
  y widget Glance.

### Decisiones de UI que merecen mención

- El slider de volumen se ancla a los **pasos reales del dispositivo**, para que «30 %» no
  reaparezca como «29 %» después de guardar.
- `STREAM_ACCESSIBILITY` aparece en la lista, deshabilitado y **con su explicación**, en
  lugar de desaparecer como si fuera un olvido.
- La sección de No molestar avisa por escrito de que la app puede activarlo pero no
  desactivar un modo que puso otro.

---

## 8. Desviaciones respecto al plan

| Decisión del plan | Qué se hizo | Por qué |
|---|---|---|
| Stack AGP 9.2 / Kotlin 2.3.21 / Room 3 | AGP 8.10.1 / Kotlin 2.1.20 / Room 2.7.1 | Ver §2. Verificabilidad local por encima de novedad |
| Detección de *force stop* con `ApplicationStartInfo` (API 35+) | Inferencia desde el propio registro de salud | Funciona en **todas** las versiones soportadas, no solo en API 35+ |
| Umbral de cobertura del 90 % desde el principio | Puerta inicial al 40 %, subida progresiva | Un umbral que nadie puede cumplir es un umbral que todo el mundo aprende a ignorar. La lógica está cubierta además por las 107 comprobaciones del self-check |
| Línea de tiempo semanal (RF-18, *Should*) | No implementada | Recorte consciente de v1; el resto de RF *Must* está completo |
| Onboarding como grafo de 6 pantallas | Diagnóstico accionable desde el primer arranque | Cubre la misma necesidad con menos superficie; el onboarding guiado pasa a v1.1 |

---

## 9. Direct Boot: limitación declarada

`BootReceiver` escucha `LOCKED_BOOT_COMPLETED` y está marcado `directBootAware`, pero la
base de datos vive en almacenamiento cifrado por credenciales. Antes del desbloqueo, la
lectura del mundo falla y el coordinador programa el latido.

Esto es **una mitigación parcial, no completa**. La versión completa necesita una copia
mínima del plan en almacenamiento protegido por dispositivo. Está registrado como trabajo
de v1.1 en `06-PUBLICACION.md`, y hasta entonces figura como limitación conocida en el
`CHANGELOG`, no como un olvido.
