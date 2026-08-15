# 00 — Investigación y análisis competitivo

**Proyecto:** RitMute — gestor de perfiles de sonido por horarios y contexto para Android
**Fecha:** 12 de agosto de 2026
**Autor de esta fase:** Claude (investigación previa al equipo de desarrollo)
**Estado:** Cerrado — sirve de entrada para `01-ESPECIFICACIONES.md`

---

## 1. Objetivo de la investigación

Antes de escribir una sola línea de especificación, este documento responde a tres preguntas:

1. ¿Qué hacen realmente las apps que ya existen en esta categoría y cómo de bien lo hacen?
2. ¿Qué permite y qué prohíbe Android en 2026 respecto al control de audio y modos de interrupción?
3. ¿Dónde está el hueco real de mercado que justifica construir otra app más?

---

## 2. Panorama de la categoría

La categoría "perfiles de sonido programados" en Android se divide en cuatro familias claramente diferenciadas:

| Familia | Ejemplos | Enfoque | Punto débil dominante |
|---|---|---|---|
| **Programadores simples** | Volume Scheduler – Auto Silent, Sound Scheduler (Hexibits), Sound Profiles (A3) | Cambiar volúmenes a una hora fija | Fiabilidad, ausencia de rangos, sin DND |
| **Perfiles clásicos** | Sound Profile (Orion.Soft), Profile Scheduler | Perfiles nombrados + activación manual/horaria | UI anticuada, roto en Android moderno |
| **Automatización general** | Tasker, MacroDroid, Automate | Todo es posible, nada es sencillo | Curva de aprendizaje brutal |
| **Nativo del sistema** | Modos / No molestar de Android, Rutinas de Samsung/Pixel | Integrado, fiable | No controla volúmenes por stream de forma granular |

---

## 3. Análisis app por app

### 3.1 Volume Scheduler – Auto Silent (`com.bhanu.volumeschedulerpro`)

- **Tracción:** 50 000+ instalaciones, 3,5 ★ sobre ~1 130 reseñas. Es el líder de facto entre los programadores simples.
- **Funciones:** programación por hora, ajuste independiente de tono y notificaciones, modos Silencio/Normal/Vibración, posponer (snooze) el perfil con un toque.
- **Modelo:** gratis con notificación persistente; versión de pago para eliminarla.
- **Privacidad:** declara no recoger ni compartir datos. Es el mejor de la categoría en este aspecto.
- **Carencias documentadas en reseñas:**
  - **Imprecisión horaria**: "a veces los presets no coinciden con las horas".
  - **Fiabilidad intermitente**: "simplemente decide no funcionar".
  - **Sin copia de seguridad**: la configuración se pierde al cambiar de versión.
  - **Sin rangos horarios**: solo se define hora de inicio, no un intervalo con retorno automático.

> **Lectura para nosotros:** el líder de la categoría falla precisamente en lo básico —disparar a su hora y no perder la configuración—. Ese es nuestro campo de batalla.

### 3.2 Sound Scheduler: Audio Manager (`com.hexibits.profiler`)

- **Tracción:** 1 000+ instalaciones, 3,2 ★ (8 reseñas). Reciente pero sin tracción.
- **Funciones:** perfiles ilimitados, automatización por hora y por día de la semana, volúmenes separados de multimedia/tono/notificación, funciona sin conexión, Android 9+.
- **Modelo:** gratis con anuncios (banner discreto).
- **Privacidad — bandera roja:** recoge identificadores de dispositivo y actividad de la app, **los datos no se cifran**, **no se pueden eliminar** y pueden compartirse con terceros.
- **Carencias:** sin control de vibración (petición explícita en reseñas), sin DND, sin disparadores contextuales.

### 3.3 Sound Profiles (`com.a3.soundprofiles`)

- **Tracción:** 1 000+ instalaciones, 3,3 ★ (18 reseñas).
- **Funciones:** programación semanal/diaria/horaria de tono, alarma y multimedia; múltiples perfiles; conmutación automática por reglas.
- **Modelo:** gratis con anuncios; usuarios piden versión de pago sin anuncios.
- **Privacidad:** comparte identificadores con terceros, sin cifrado, sin borrado.
- **Carencias explícitas en reseñas:**
  - Sin disparadores de Bluetooth ni WiFi.
  - **Sin integración con No molestar ni excepciones de contactos.**
  - Obligación de definir hora de fin cuando no siempre tiene sentido.
  - **Límite de incremento de volumen** que impide fijar valores arbitrarios.
  - Usabilidad: pulsar el perfil debería activarlo, no abrir la edición.
  - Sin indicador visual del perfil activo.

### 3.4 Sound Profile / Orion.Soft (`Orion.Soft`)

- Veterano de la categoría con planificador propio y perfiles con reglas. Interfaz heredada de la era pre-Material, y arrastra incompatibilidades con las restricciones modernas de DND y servicios en segundo plano.
- Sigue siendo la referencia en **riqueza de opciones por perfil**, y de ahí conviene copiar el modelo mental: un perfil es un conjunto completo de estado de audio, no solo un número.

### 3.5 Tasker / MacroDroid / Automate

- **Potencia máxima**: cualquier disparador (hora, ubicación, WiFi, Bluetooth, NFC, evento de calendario, app en primer plano) y cualquier acción.
- **Coste real**: configurar "silencio en el trabajo de 9 a 18 salvo llamadas de mi pareja" en Tasker requiere entender perfiles, contextos, tareas y variables. MacroDroid lo simplifica pero sigue siendo un editor de macros.
- Tasker incluso llegó a ser retirado temporalmente de Play Store por políticas de permisos, lo que ilustra el riesgo regulatorio de la automatización genérica.

> **Lectura para nosotros:** no competimos con Tasker en potencia. Competimos en que **el 90 % de los casos de uso reales se configuren en menos de 30 segundos**.

### 3.6 Modos / No molestar nativo (Android 15–16)

- Desde Android 15 el sistema expone la **Modes API** (`AutomaticZenRule` + `ZenDeviceEffects`), que permite a apps de terceros **crear modos propios del sistema** con efectos de dispositivo (escala de grises, atenuar fondo, etc.). Android 16 amplía y rediseña la experiencia de Modos.
- Es fiable porque lo ejecuta el sistema, pero **no ajusta volúmenes por stream**: un Modo puede silenciar notificaciones, no puede bajar multimedia al 30 % y subir la alarma al 100 %.

> **Lectura para nosotros:** la Modes API no es competencia, es **una palanca**. Registrar nuestros perfiles como `AutomaticZenRule` nos da fiabilidad de sistema para la parte DND, y nosotros añadimos encima el control de volúmenes que el sistema no ofrece.

### 3.7 Referentes de código abierto

- **Timed Silence** (`de.felixnuesse.timedsilence`, F-Droid, GPL): silencia el teléfono por horarios y por eventos de calendario. Es el referente FOSS más cercano y demuestra que el modelo "franjas horarias + calendario" tiene demanda.
- **GMailley/VolumeScheduler**: implementación sencilla de referencia sobre `AlarmManager` + `AudioManager`.
- **CliffracerMerchant/SoundAura**: buen ejemplo moderno de arquitectura Compose + servicio de audio en Kotlin, útil como referencia de estructura de proyecto y de gestión del foco de audio.

---

## 4. Restricciones técnicas de Android en 2026

Este es el apartado donde mueren la mayoría de las apps de la categoría. Resumen de lo que la plataforma permite hoy:

### 4.1 Control de volumen

- `AudioManager.setStreamVolume()` cubre `STREAM_RING`, `STREAM_MUSIC`, `STREAM_ALARM`, `STREAM_NOTIFICATION`, `STREAM_VOICE_CALL`, `STREAM_SYSTEM` y `STREAM_ACCESSIBILITY`.
- En muchos dispositivos (y por defecto en AOSP) **tono y notificación están acoplados**: cambiar uno cambia el otro. Hay que detectarlo en tiempo de ejecución y ajustar la UI, no asumirlo.
- **Bajar el volumen de tono a 0, o poner el modo en SILENT/VIBRATE, lanza `SecurityException` si No molestar está activo y la app no tiene `ACCESS_NOTIFICATION_POLICY`.** Es la causa número uno de los fallos "a veces no funciona" de la competencia.
- **No existe API pública de volumen por aplicación.** Lo que ofrecen Samsung One UI o apps como "Ultimate Sound Control" se apoya en funciones de OEM o en capturas de audio con limitaciones. Descartado para v1; se documenta como no objetivo.

### 4.2 No molestar

- `ACCESS_NOTIFICATION_POLICY` se concede desde una pantalla del sistema (`ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`), no con un diálogo de permiso normal. Requiere onboarding explicado.
- `NotificationManager.setInterruptionFilter()` para los modos clásicos; `AutomaticZenRule` para modos gestionados por el sistema.

### 4.3 Programación temporal — el punto crítico

- **Android 12+**: `SCHEDULE_EXACT_ALARM`.
- **Android 13+**: `USE_EXACT_ALARM` se concede automáticamente en la instalación, **pero la política de Google Play lo reserva a apps de calendario y despertador**. Una app de perfiles de sonido **no cualifica**, así que debemos usar `SCHEDULE_EXACT_ALARM`.
- **Android 14+**: `SCHEDULE_EXACT_ALARM` está **denegado por defecto** en instalaciones nuevas. Hay que pedirlo con `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` y comprobar `canScheduleExactAlarms()` antes de cada programación.
- Hay que escuchar `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` y **degradar con elegancia** a `setWindow()` / `setAndAllowWhileIdle()` si el usuario deniega.
- `WorkManager` no sirve como mecanismo principal: mínimo 15 minutos y sin garantía de precisión. Sí sirve como **red de seguridad periódica** para reconciliar el estado.
- Los OEM agresivos con la batería (Xiaomi, Huawei, Oppo, OnePlus, Samsung en menor medida) matan alarmas. Necesitamos: exclusión de optimización de batería opcional, reprogramación en `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` y `TIME_SET`/`TIMEZONE_CHANGED`, y un **reconciliador** que al abrir la app detecte transiciones perdidas y las aplique.

### 4.4 Otros

- **Android 13+**: `POST_NOTIFICATIONS` en tiempo de ejecución.
- **Android 15+**: restricciones adicionales sobre servicios en primer plano y tipos obligatorios.
- **Google Play exige target API 36 (Android 16) desde el 31 de agosto de 2026.** Nacemos ya con `targetSdk = 36`.

---

## 5. Síntesis: qué falla en el mercado

Ordenado por impacto sobre el usuario:

1. **Fiabilidad.** El problema no es que falten funciones, es que las que hay no se disparan. Ninguna app de la categoría trata la programación exacta como un problema de ingeniería serio.
2. **No hay rangos, solo instantes.** Casi todas programan "a las 23:00 pon silencio" y obligan a crear una segunda regla para volver. Nadie modela "de 23:00 a 07:00, y al terminar restaura lo que había".
3. **Ausencia de DND real.** Bajar el volumen no es lo mismo que no ser molestado. La integración con No molertar y con excepciones de contactos es la petición más repetida y la menos atendida.
4. **Privacidad pobre.** Dos de las tres apps analizadas comparten identificadores con terceros, sin cifrado y sin posibilidad de borrado. Para una app que solo necesita relojes y volúmenes, esto es indefendible.
5. **Sin copia de seguridad.** Perder toda la configuración al reinstalar es habitual.
6. **Sin transparencia.** El usuario no sabe por qué cambió su volumen ni qué regla lo hizo. Cero trazabilidad.
7. **Transiciones bruscas.** Nadie ofrece rampas suaves de volumen ni respeta que estés en una llamada o reproduciendo música.

---

## 6. Oportunidades diferenciales para RitMute

Cada una responde directamente a un fallo del apartado anterior.

| # | Diferenciador | Responde a |
|---|---|---|
| **D1** | **Motor de programación de grado industrial**: alarmas exactas con degradación explícita, reconciliación al arranque y un *watchdog* que detecta y repara transiciones perdidas. | Fallo 1 |
| **D2** | **Perfiles con franjas horarias reales** (inicio–fin, cruce de medianoche, días de la semana) y **restauración automática** del estado anterior al salir de la franja. | Fallo 2 |
| **D3** | **Integración nativa con la Modes API** (`AutomaticZenRule`): cada perfil puede registrar un Modo del sistema, con la fiabilidad que eso implica, más excepciones de contactos y apps. | Fallo 3 |
| **D4** | **Privacidad por diseño**: cero red, cero analítica, cero anuncios, cero permisos innecesarios. Todo local. Código abierto verificable. | Fallo 4 |
| **D5** | **Copia de seguridad y restauración en JSON** exportable, más soporte de Android Backup. | Fallo 5 |
| **D6** | **Registro de actividad ("¿por qué sonó así?")**: historial legible de cada cambio, qué regla lo provocó y si tuvo éxito, con diagnóstico de permisos. | Fallo 6 |
| **D7** | **Transiciones inteligentes**: rampa gradual de volumen y reglas de cortesía (no cambiar durante una llamada activa; opción de no bajar multimedia si hay reproducción en curso). | Fallo 7 |
| **D8** | **Resolución de conflictos por prioridad**: cuando dos perfiles solapan, gana el de mayor prioridad de forma determinista y explicada, no por azar. | Transversal |
| **D9** | **Acceso en un toque**: mosaico de Ajustes Rápidos, widget y accesos directos para activar/pausar perfiles sin abrir la app. | Usabilidad |
| **D10** | **Modo "pausa temporal"** de todo el sistema de reglas (1 h / hasta mañana), pensado para cines, viajes o reuniones imprevistas. | Usabilidad |

---

## 7. Decisiones tomadas a partir de la investigación

- **Plataforma:** Android nativo, Kotlin + Jetpack Compose. iOS queda descartado: no permite el control de volúmenes por stream ni la programación de perfiles.
- **`minSdk = 26`** (Android 8.0): cubre >98 % del parque y es donde `setStreamVolume` con `STREAM_ACCESSIBILITY` y las APIs de canales de notificación están disponibles.
- **`targetSdk = 36`** (Android 16), obligatorio en Play desde el 31/08/2026.
- **Sin control de volumen por aplicación** en v1: no hay API pública. Se documenta como no objetivo explícito.
- **Sin `USE_EXACT_ALARM`**: no cualificamos según la política de Play. Vamos con `SCHEDULE_EXACT_ALARM` y degradación elegante.
- **Sin backend, sin cuentas, sin red.** El `AndroidManifest` no declarará `INTERNET`. Es un argumento de venta comprobable.

---

## Fuentes

- [Volume Scheduler – Auto Silent (Google Play)](https://play.google.com/store/apps/details?id=com.bhanu.volumeschedulerpro&hl=en_US)
- [Sound Scheduler: Audio Manager (Google Play)](https://play.google.com/store/apps/details?id=com.hexibits.profiler)
- [Sound Profiles (Google Play)](https://play.google.com/store/apps/details?id=com.a3.soundprofiles)
- [Sound Profile / Orion.Soft (Google Play)](https://play.google.com/store/apps/details?id=Orion.Soft&hl=en_US)
- [Sound Scheduler & Profiler — Hexibits](https://hexibits.com/sound-scheduler)
- [Schedule exact alarms are denied by default — Android Developers](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)
- [Schedule alarms — Android Developers](https://developer.android.com/develop/background-work/services/alarms)
- [Features and APIs, Android 15 — Android Developers](https://developer.android.com/about/versions/15/features)
- [Features and APIs, Android 16 — Android Developers](https://developer.android.com/about/versions/16/features)
- [ZenDeviceEffects — Android Developers](https://developer.android.com/reference/android/service/notification/ZenDeviceEffects)
- [Behavior changes: apps targeting Android 15+ — Android Developers](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Timed Silence — F-Droid](https://f-droid.org/en/packages/de.felixnuesse.timedsilence/)
- [Timed Silence — It's FOSS](https://news.itsfoss.com/timed-silence/)
- [GMailley/VolumeScheduler — GitHub](https://github.com/GMailley/VolumeScheduler)
- [CliffracerMerchant/SoundAura — GitHub](https://github.com/CliffracerMerchant/SoundAura)
- [Google Play requiere Android 16 (API 36) desde el 31/08/2026](https://dev.to/dainyjose/google-play-requires-android-16-api-level-36-by-august-31-2026-react-native-migration-guide-1d51)
- [Android 16 Migration Guide (API 36)](https://meisteritsystems.com/news/android-16-migration-guide-developer-checklist-api-36/)
