# RitMute

**Tu móvil suena como debe, cuando debe.**

Gestor de perfiles de sonido para Android: define estados de audio completos —los seis
streams, el modo de timbre y No molestar— y deja que se apliquen solos, a su hora, siempre.

[![CI](https://github.com/OWNER/ritmute/actions/workflows/ci.yml/badge.svg)](https://github.com/OWNER/ritmute/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

---

## Por qué otra app de perfiles de sonido

Hay varias en Play Store. Las analizamos todas antes de escribir una línea
([docs/00](docs/00-INVESTIGACION.md)), y el resultado fue incómodo: la líder de la categoría
tiene 50 000 instalaciones y 3,5 ★, y sus reseñas se quejan de lo mismo una y otra vez.

> «A veces los presets no coinciden con las horas.»
> «Simplemente decide no funcionar.»
> «Se pierde la configuración al cambiar de versión.»

El problema de esta categoría **no es que falten funciones**. Es que las que hay no se
disparan. Ninguna trata la programación exacta como un problema serio de ingeniería.

RitMute lo hace.

## Qué hace diferente

| | Lo habitual en la categoría | RitMute |
|---|---|---|
| **Fiabilidad** | Una alarma por regla, sin recuperación | Una sola alarma para la siguiente transición, reconciliación en cada arranque, vigilante horario, recuperación antes del desbloqueo tras reiniciar |
| **Franjas** | «A las 23:00 pon silencio» y otra regla para volver | Franjas reales con inicio y fin, cruce de medianoche y restauración automática del estado anterior |
| **No molestar** | Ausente o roto en Android moderno | `AutomaticZenRule` propia, con aviso explícito cuando otro modo del sistema anula el nuestro |
| **Privacidad** | Identificadores compartidos con terceros, sin cifrar, sin poder borrarlos | **Sin permiso de `INTERNET`.** Sin anuncios, sin analítica, sin cuentas. Compruébalo en el manifiesto |
| **Copia de seguridad** | Inexistente | Exportación e importación JSON, ida y vuelta sin pérdida |
| **Transparencia** | Cero | Historial de cada cambio, con la regla que lo causó y si funcionó |
| **Conflictos** | Indefinido | Resolución determinista por prioridad, **idéntica en cualquier dispositivo** |

## Estado

**v1.0.0** — primera versión pública.

La compilación autoritativa es GitHub Actions. El proyecto se desarrolló sin acceso al SDK
de Android en el entorno local, lo que forzó una decisión que resultó ser buena por sí
misma: **todo lo que decide algo vive en `:core:domain`, que es Kotlin puro**, verificable
con `kotlinc` y sin un emulador delante.

```
$ java -jar selfcheck.jar
RitMute :core:domain self-check
  checks run : 107
  failures   : 0
  OK
```

## Arquitectura

```
:app              Application, MainActivity, navegación, receivers, tile, widget, DI raíz
:core:domain      Kotlin JVM PURO. Modelos, resolución de conflictos, cálculo de la
                  siguiente transición, política de instantáneas. Sin Android.
:core:data        Room, DataStore, repositorios, import/export JSON
:core:system      AudioManager, NotificationManager, AutomaticZenRule, AlarmManager,
                  WorkManager. Adaptadores finos: ni una decisión temporal aquí.
:core:ui          Tema Material 3, tipografía, componentes Compose comunes
:feature:profiles Lista y editor de perfiles, franjas, plantillas
:feature:tools    Historial, ajustes, diagnóstico, copia de seguridad
```

Dos reglas que la CI verifica en cada push:

1. **`:core:domain` no puede importar nada de `android.`** Un solo `import android.` rompe
   la compilación. Es el paso más barato y más valioso del pipeline.
2. **`INTERNET` no puede aparecer en ningún manifiesto.** La promesa de privacidad es
   comprobable o no es una promesa.

### La decisión que sostiene todo lo demás

> **La alarma no lleva información sobre qué hacer. Solo dice «reevalúa ahora».**

El estado deseado es una función pura del instante. Por eso una alarma perdida, duplicada,
adelantada o retrasada **es inofensiva**: la respuesta se recalcula desde cero cada vez. Sin
esa propiedad, el diseño de alarma única sería frágil; con ella, es el más robusto posible
dentro de las cuotas que Android impone a las apps que el usuario no abre a diario.

## Lo que Android no permite, y que aquí se dice en voz alta

Buena parte del trabajo de diseño consistió en descubrir que las APIs que esta categoría
necesita se han ido cerrando entre 2024 y 2026. En lugar de fingir que no ha pasado:

- **No podemos apagar No molestar.** Desde Android 15 una app con `targetSdk ≥ 35` no puede
  cambiar el estado global; solo aportar una regla, y gana siempre la más restrictiva. La
  app puede activarlo, no desactivar el modo que puso otro. La UI lo dice.
- **El volumen de accesibilidad no se puede escribir.** Requiere un permiso de firma. La
  llamada no falla: **no hace nada, en silencio**. Aparece en la app como no soportado, con
  su explicación, en vez de desaparecer como si fuera un olvido.
- **No hay volumen por aplicación.** No existe API pública. Está declarado como no objetivo.
- **Contra un *force stop* no hay defensa técnica.** Lo que sí hay es detectarlo y decírtelo:
  «tu móvil detuvo RitMute; entre X e Y no se aplicó ninguna regla».

## Permisos

| Permiso | Para qué |
|---|---|
| `ACCESS_NOTIFICATION_POLICY` | Silenciar el timbre y fijar modos. Sin él, Android lanza `SecurityException` |
| `SCHEDULE_EXACT_ALARM` | Disparar a la hora. Si lo deniegas, la app degrada a alarmas inexactas y te avisa |
| `POST_NOTIFICATIONS` | Avisos opcionales. Único permiso de runtime |
| `RECEIVE_BOOT_COMPLETED` | Recuperarse tras reiniciar |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Servicio efímero (<10 s) que aplica el perfil |
| `VIBRATE` | Modo vibración |

Y no: `INTERNET`, `READ_PHONE_STATE`, `WRITE_SETTINGS`, `USE_EXACT_ALARM`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Cada ausencia está justificada en el manifiesto.

## Compilar

```bash
git clone https://github.com/OWNER/ritmute.git
cd ritmute
./gradlew assembleDebug
```

Requiere JDK 17+ y el SDK de Android 36. Verificación del núcleo sin SDK de Android:

```bash
kotlinc $(find core/domain/src/main -name '*.kt') tools/selfcheck/DomainSelfCheck.kt \
  -include-runtime -d selfcheck.jar && java -jar selfcheck.jar
```

## Compatibilidad

- **minSdk 26** (Android 8.0) · **targetSdk 36** (Android 16)
- Español e inglés completos, con selector de idioma por app en Android 13+
- Tema claro, oscuro y color dinámico

## Documentación

El proyecto se documentó por fases, y la memoria completa está en el repositorio:

| | |
|---|---|
| [00 — Investigación](docs/00-INVESTIGACION.md) | Análisis competitivo y restricciones reales de la plataforma |
| [01 — Especificaciones](docs/01-ESPECIFICACIONES.md) | Requisitos funcionales y no funcionales |
| [02 — Revisión del equipo](docs/02-REVISION-EQUIPO.md) | Los seis fallos que habrían roto la v1, y las decisiones vinculantes |
| [03 — Plan de desarrollo](docs/03-PLAN-DESARROLLO.md) | Fases, contratos y criterios de salida |
| [04 — Desarrollo](docs/04-DESARROLLO.md) | Lo que se construyó y qué se decidió por el camino |
| [05 — QA](docs/05-QA.md) | Estrategia de calidad y resultados |
| [06 — Publicación](docs/06-PUBLICACION.md) | Estado de la v1.0 y trabajo pendiente |

## Licencia

GPLv3. Ver [LICENSE](LICENSE).
