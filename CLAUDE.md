# SonoRitmo — contexto para Claude Code

App Android (Kotlin + Compose) de perfiles de sonido programados. Repositorio:
`github.com/Kelryn/Sonify`.

Este archivo existe porque el proyecto se escribió entero en un entorno **sin SDK de
Android y sin acceso a Maven**, así que nunca se ha compilado. Todo lo que sigue es lo que
necesitas saber para continuar sin repetir decisiones ya tomadas ni deshacer las que
sostienen el diseño.

---

## 1. Estado actual y tarea inmediata

| | |
|---|---|
| Código | 113 archivos Kotlin, ~14.800 líneas, 7 módulos Gradle |
| Compilado alguna vez | **No.** El primer `assembleDebug` real es el de CI |
| Verificado de verdad | `:core:domain` — 107 aserciones ejecutadas, 0 fallos |
| Auditoría de QA | 11 defectos encontrados y corregidos antes del push |

**Tu primera tarea es poner el CI en verde.** Es razonable esperar errores de tipos en las
fronteras entre módulos; la auditoría estática ya encontró cuatro y no hay motivo para creer
que no quede alguno. Lo que *no* deberían aparecer son problemas de arquitectura.

```bash
./gradlew assembleDebug          # el que importa
./gradlew testDebugUnitTest      # tests JVM
./gradlew lintRelease            # MissingTranslation está elevado a error
```

Si tienes el SDK de Android instalado, compila en local antes de empujar: cada iteración
contra CI cuesta minutos.

---

## 2. Invariantes que no se tocan

Estas cinco decisiones sostienen el diseño. Si algo choca con ellas, el problema casi
siempre está en el código nuevo, no en la regla.

**1. `:core:domain` no importa nada de `android.`**
Es Kotlin JVM puro y ahí vive *toda* la lógica que decide algo. Es lo que hace verificable
el núcleo sin emulador. La CI rompe el build si aparece un `import android.`.

**2. La alarma no lleva instrucciones, solo dice «reevalúa ahora».**
El estado deseado es una función pura del instante. Por eso una alarma perdida, duplicada,
adelantada o retrasada es inofensiva. Si alguna vez metes datos en los extras del `Intent`,
rompes la propiedad que hace seguro todo el diseño de alarma única.

**3. Ninguna decisión temporal en `:core:system`.**
Ese módulo son adaptadores. Todo cálculo de tiempo se delega a `:core:domain`. Si una clase
de `system` decide *cuándo* pasa algo, está en el módulo equivocado.

**4. Toda escritura de audio se verifica releyendo.**
El peor fallo de esta plataforma es el silencioso: `STREAM_ACCESSIBILITY`, dispositivos con
`isVolumeFixed`, capas OEM que revierten el modo de timbre y el *background audio hardening*
de Android 17 no lanzan nada — simplemente no hacen nada. La relectura es la única defensa.

**5. Sin permiso `INTERNET`, y la CI lo comprueba.**
La promesa de privacidad es verificable o no vale nada. Lo mismo con `READ_PHONE_STATE`
(se usa `AudioManager.getMode()`), `USE_EXACT_ALARM` (la política de Play lo reserva a apps
de calendario) y `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (motivo habitual de rechazo).

---

## 3. Mapa del proyecto

```
:core:domain      Kotlin puro. ConflictResolver, NextTransitionCalculator,
                  ReconciliationPlanner, ScheduleWindows, VolumeMath, Templates.
:core:data        Room, DataStore, repositorios, import/export JSON.
:core:system      AudioManager, NotificationManager, AutomaticZenRule, AlarmManager,
                  WorkManager, diagnóstico y guía por fabricante.
:core:ui          Tema Material 3, componentes Compose comunes.
:feature:profiles Lista y editor de perfiles.
:feature:tools    Historial, ajustes, diagnóstico, copia de seguridad.
:app              Ensamblaje, navegación, receivers, tile, widget, DI raíz.
```

`:core:system` **no** depende de `:core:data`. El puente lo hace `:app` mediante
`SchedulingWorldSource` y `ReconciliationSink` (ver `app/di/AppModule.kt`).

`SchedulerCoordinator` es el único punto de entrada de la automatización. Reprograma la
alarma *antes* de aplicar y otra vez en un `finally`, porque una excepción entre «aplicar» y
«reprogramar» rompería la cadena de alarmas para siempre.

---

## 4. Verificación local sin SDK de Android

`tools/selfcheck/DomainSelfCheck.kt` es un arnés de pruebas **sin dependencias**: se compila
y se ejecuta con `kotlinc` a secas. Cubre cruce de medianoche, huecos y solapes de horario de
verano, resolución de conflictos, las cinco fuentes de transición, el ciclo de vida del
snapshot y la conversión de volumen.

```bash
kotlinc $(find core/domain/src/main -name '*.kt') tools/selfcheck/DomainSelfCheck.kt \
  -include-runtime -d selfcheck.jar && java -jar selfcheck.jar
```

Si tocas `:core:domain`, ejecútalo: es la red que detecta un error de DST en segundos en vez
de en un runner. La suite de JUnit en `core/domain/src/test/` cubre lo mismo y corre en CI —
mantén ambas alineadas.

---

## 5. Convenciones

- **Código, comentarios, KDoc y mensajes de commit en inglés.** Documentación de proyecto
  (`docs/`) y textos de usuario, en español e inglés.
- **Cero cadenas de usuario en código.** Todo a `strings.xml`, en los dos idiomas.
  `MissingTranslation` es error de lint, no aviso.
- Los comentarios explican **por qué**, no qué. Si el qué no se entiende, arregla el código.
- Un cambio de versión de dependencia va en un PR aislado, sin nada más.

---

## 6. Deuda declarada (no son olvidos)

Todo esto está razonado en `docs/05-QA.md` y `docs/06-PUBLICACION.md`:

- **Cobertura al 40 %**, objetivo 90 %. La puerta se bajó a propósito: un umbral que nadie
  puede cumplir es un umbral que todo el mundo aprende a ignorar.
- **`allWarningsAsErrors` desactivado** en `:core:domain` y
  `configuration-cache.problems=warn`. Reactívalos cuando haya un build verde de referencia.
- **Direct Boot mitigado a medias**: falta el plan mínimo en almacenamiento protegido por
  dispositivo.
- **Stack en AGP 8.10.1 / Kotlin 2.1.20 / Room 2.7.1**, no en AGP 9 / Room 3. La razón está
  en `docs/04-DESARROLLO.md` §2. La migración es trabajo de v1.1, en un PR aislado.
- **Sin tests instrumentados.** Toda `:core:system` está verificada por lectura, no por
  ejecución.

---

## 7. Nombre pendiente de decidir

El repositorio se llama **Sonify**; la app, el `applicationId` (`com.sonoritmo.app`) y todos
los paquetes dicen **SonoRitmo**. Conviven sin problema, pero el `applicationId` es
irreversible una vez publicado en Play. Si el usuario quiere unificarlo, hazlo **antes** de
cualquier publicación.

---

## 8. Documentación

La memoria completa del proyecto está en `docs/`, incluidos los seis informes de revisión
sin resumir en `docs/revisiones/`. Si vas a cambiar algo que parezca una decisión rara,
búscala primero ahí — casi todas tienen un motivo escrito.

| | |
|---|---|
| `docs/00-INVESTIGACION.md` | Análisis competitivo y restricciones reales de la plataforma |
| `docs/01-ESPECIFICACIONES.md` | Requisitos (enmendado por el 02) |
| `docs/02-REVISION-EQUIPO.md` | **El más importante.** Las decisiones vinculantes y por qué |
| `docs/03-PLAN-DESARROLLO.md` | Fases y criterios de salida |
| `docs/04-DESARROLLO.md` | Qué se construyó y qué se decidió por el camino |
| `docs/05-QA.md` | Los 11 defectos de la auditoría y cómo se corrigieron |
| `docs/06-PUBLICACION.md` | Estado de la v1.0 y trabajo pendiente |
| `docs/mockups/interfaz.html` | Las pantallas renderizadas desde el código |
