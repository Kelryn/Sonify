# 01 — Requisitos y especificaciones

**Proyecto:** RitMute
**Versión del documento:** 1.0
**Fecha:** 12 de agosto de 2026
**Entrada:** `00-INVESTIGACION.md`
**Destinatarios:** equipo de agentes especializados (arquitectura, audio, datos, scheduler, UI, QA)

---

## 1. Visión

> Una app Android que gestiona **todo** el sonido del teléfono mediante **perfiles** que se activan **solos, a su hora, siempre**.

Tres promesas, en este orden de importancia:

1. **Se dispara.** Si el usuario dice "silencio de 23:00 a 07:00", eso ocurre. Sin excusas, sin depender de que la app esté abierta.
2. **Es completo.** Un perfil controla los siete streams de audio, el modo de timbre, la vibración y el modo No molestar. No solo "el volumen".
3. **No cuesta nada entenderlo.** Los cinco casos de uso más comunes se configuran en menos de 30 segundos, con plantillas listas.

**Antipromesa explícita:** no somos Tasker. Si un caso de uso requiere lógica condicional arbitraria, no es nuestro problema.

---

## 2. Alcance de la v1.0

### 2.1 Dentro de alcance

| Área | Contenido |
|---|---|
| Perfiles | Crear, editar, duplicar, borrar, activar manualmente, priorizar |
| Audio | Los 7 streams + modo de timbre + vibración |
| DND | Filtro de interrupciones y (Android 15+) modos del sistema vía `AutomaticZenRule` |
| Programación | Franjas horarias con días de la semana, cruce de medianoche, excepciones por fecha |
| Fiabilidad | Alarmas exactas, reconciliación, watchdog, rearranque tras reinicio |
| Restauración | Volver al estado anterior al terminar la franja |
| Conflictos | Resolución determinista por prioridad |
| Acceso rápido | Quick Settings tile, widget, pausa global |
| Historial | Registro auditable de cada transición |
| Datos | Room local, exportación/importación JSON |
| UI | Compose Material 3, tema claro/oscuro/dinámico, español e inglés |
| Accesibilidad | TalkBack, tamaños de fuente, contraste, targets táctiles |

### 2.2 Fuera de alcance (v1)

- Volumen por aplicación (no hay API pública en Android).
- Disparadores por ubicación, WiFi, Bluetooth, NFC o calendario → **v1.1+**.
- Sincronización en la nube, cuentas de usuario, backend → **nunca**.
- Ecualizador o procesamiento de audio.
- iOS / wearOS.
- Anuncios, analítica, telemetría → **nunca**.

---

## 3. Personas y casos de uso

### P1 — Elena, 34, oficina

Quiere que el móvil esté en vibración de 9:00 a 18:00 de lunes a viernes, y en tono alto el resto. Le ha fallado dos apps porque "a veces no cambiaba".

### P2 — Marc, 22, estudiante

Silencio total de 23:30 a 07:30 todos los días, **pero la alarma al 100 %**. Ha tenido el susto de que una app le bajara también la alarma.

### P3 — Rocío, 45, guardias médicas

Necesita No molestar con excepción para llamadas repetidas y para tres contactos concretos, activo en franjas irregulares que cambia a mano.

### P4 — Dani, 29, cine y reuniones

Quiere un botón: "silencio 2 horas y luego vuelve solo a lo que había".

### Casos de uso

| ID | Caso | Persona |
|---|---|---|
| CU-01 | Crear un perfil desde una plantilla en < 30 s | P1, P2 |
| CU-02 | Programar una franja con días de la semana | P1 |
| CU-03 | Programar una franja que cruza medianoche | P2 |
| CU-04 | Activar un perfil manualmente y que se desactive solo | P4 |
| CU-05 | Pausar todas las reglas temporalmente | P4 |
| CU-06 | Activar No molestar con excepciones de contactos | P3 |
| CU-07 | Consultar por qué cambió el sonido a las 3 de la mañana | Todas |
| CU-08 | Exportar la configuración y restaurarla en otro móvil | Todas |
| CU-09 | Recuperar el estado correcto tras reiniciar el teléfono | Todas |
| CU-10 | Ver y resolver permisos faltantes desde un panel de diagnóstico | Todas |

---

## 4. Modelo de dominio

### 4.1 `SoundProfile`

```
id: Long
name: String
emoji: String?              // icono ligero, sin recursos gráficos
colorSeed: Int              // acento visual
enabled: Boolean
priority: Int               // 0..100, mayor gana en conflictos
volumes: VolumeSettings
ringerMode: RingerMode      // NORMAL | VIBRATE | SILENT | UNCHANGED
dnd: DndSettings
options: ProfileOptions
createdAt / updatedAt: Instant
```

### 4.2 `VolumeSettings`

Un valor por stream, cada uno **opcional**: `null` significa "no tocar".

```
ring, music, alarm, notification, voiceCall, system, accessibility: Int?  // 0..100 (porcentaje)
```

**Decisión de diseño:** se almacena en **porcentaje 0–100**, no en pasos nativos. El número de pasos por stream varía por dispositivo (7, 15, 25, 30…). Guardar porcentaje hace la configuración portable entre móviles y arregla el "límite de incremento de volumen" que los usuarios critican en la competencia. La conversión a pasos se hace en el momento de aplicar:
`pasos = round(porcentaje / 100 * getStreamMaxVolume(stream))`, respetando `getStreamMinVolume` en API 28+.

### 4.3 `DndSettings`

```
mode: DndMode               // OFF | PRIORITY | ALARMS_ONLY | TOTAL_SILENCE | UNCHANGED
allowCalls: CallPolicy      // NONE | STARRED | CONTACTS | ANY
allowRepeatCallers: Boolean
allowMessages: MessagePolicy
allowAlarms / allowMedia / allowReminders / allowEvents: Boolean
useSystemMode: Boolean      // registrar como AutomaticZenRule en API 30+/35+
```

### 4.4 `ProfileOptions`

```
restoreOnExit: Boolean      // restaurar snapshot previo al terminar
gradualTransition: Boolean  // rampa de volumen
transitionSeconds: Int      // 0..60
skipDuringCall: Boolean     // no aplicar si hay llamada activa
skipIfMediaPlaying: Boolean // no bajar STREAM_MUSIC si hay reproducción
notifyOnApply: Boolean
```

### 4.5 `Schedule`

```
id: Long
profileId: Long
enabled: Boolean
startTime: LocalTime
endTime: LocalTime          // si <= startTime, cruza medianoche
daysOfWeek: Set<DayOfWeek>  // no vacío
skipHolidays: Boolean       // v1.1
```

Reglas invariantes:
- `daysOfWeek` no puede estar vacío.
- El día de la semana se evalúa sobre el **inicio** de la franja, no sobre el fin. Una franja de sábado 23:00–02:00 sigue activa el domingo a la 01:00.

### 4.6 `AudioSnapshot`

Estado completo del audio del sistema en un instante, usado para restaurar.

### 4.7 `ActivityLogEntry`

```
timestamp, type (APPLY|RESTORE|SKIP|ERROR|BOOT|PERMISSION), profileId?, scheduleId?,
reason: String, success: Boolean, detail: String?
```

---

## 5. Requisitos funcionales

### 5.1 Gestión de perfiles

| ID | Requisito | Prioridad |
|---|---|---|
| RF-01 | Crear, editar, duplicar y eliminar perfiles sin límite de número | Must |
| RF-02 | Cada stream configurable de forma independiente, con opción "no tocar" | Must |
| RF-03 | Modo de timbre configurable (normal/vibración/silencio/no tocar) | Must |
| RF-04 | Prioridad numérica por perfil para resolver solapes | Must |
| RF-05 | Activación manual inmediata desde la lista | Must |
| RF-06 | Activación manual con duración (30 min, 1 h, 2 h, hasta mañana, indefinida) | Must |
| RF-07 | Plantillas predefinidas: Noche, Trabajo, Reunión, Cine, Conducción, Fin de semana | Must |
| RF-08 | Indicador visual claro del perfil activo en toda la app | Must |
| RF-09 | Vista previa del efecto antes de guardar | Should |
| RF-10 | Detección automática de acoplamiento tono/notificación del dispositivo y ajuste de la UI | Must |

### 5.2 Programación

| ID | Requisito | Prioridad |
|---|---|---|
| RF-11 | Franjas con hora de inicio y fin, y días de la semana | Must |
| RF-12 | Soporte de franjas que cruzan medianoche | Must |
| RF-13 | Varias franjas por perfil | Must |
| RF-14 | Activar/desactivar una franja sin borrarla | Must |
| RF-15 | Al terminar la franja, restaurar el estado anterior o aplicar el perfil por defecto | Must |
| RF-16 | Reprogramación automática tras reinicio, cambio de hora, cambio de zona horaria y actualización de la app | Must |
| RF-17 | Pausa global de todas las reglas con duración configurable | Must |
| RF-18 | Vista de línea de tiempo semanal que muestra qué perfil está activo cada hora | Should |
| RF-19 | Excepciones por fecha concreta (vacaciones, festivos) | Could (v1.1) |

### 5.3 Motor de aplicación

| ID | Requisito | Prioridad |
|---|---|---|
| RF-20 | Aplicar volúmenes por stream traduciendo porcentaje a pasos del dispositivo | Must |
| RF-21 | Verificar `ACCESS_NOTIFICATION_POLICY` antes de operaciones que lo requieran y registrar el fallo si falta | Must |
| RF-22 | Capturar snapshot del estado antes de aplicar, para poder restaurar | Must |
| RF-23 | Transición gradual opcional (rampa lineal en N segundos) | Should |
| RF-24 | Omitir la aplicación durante una llamada activa si así se configura | Should |
| RF-25 | No reducir `STREAM_MUSIC` si hay reproducción activa y así se configura | Should |
| RF-26 | Aplicar DND vía `setInterruptionFilter` y, en API ≥ 30, vía `AutomaticZenRule` si el perfil lo pide | Must |
| RF-27 | Toda aplicación, omisión o error queda registrada en el historial | Must |

### 5.4 Fiabilidad

| ID | Requisito | Prioridad |
|---|---|---|
| RF-28 | Usar `setExactAndAllowWhileIdle` cuando `canScheduleExactAlarms()` sea true | Must |
| RF-29 | Degradar a `setWindow` con ventana de 5 min si no hay permiso exacto, avisando al usuario | Must |
| RF-30 | Escuchar `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` y reprogramar | Must |
| RF-31 | `WorkManager` periódico (cada 30 min) como reconciliador: comprueba qué perfil debería estar activo y corrige | Must |
| RF-32 | Reconciliación en cada apertura de la app y en `ACTION_USER_PRESENT` | Must |
| RF-33 | Panel de diagnóstico que enumera permisos faltantes, optimización de batería y salud del scheduler, con acciones directas | Must |
| RF-34 | Detección de fabricante agresivo con la batería y guía específica (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Samsung) | Should |

### 5.5 Datos

| ID | Requisito | Prioridad |
|---|---|---|
| RF-35 | Persistencia local con Room; preferencias con DataStore | Must |
| RF-36 | Exportar toda la configuración a JSON legible | Must |
| RF-37 | Importar JSON con validación de versión y de esquema | Must |
| RF-38 | Historial limitado a 1 000 entradas o 30 días, con purga automática | Must |
| RF-39 | Migraciones de Room versionadas y probadas | Must |

### 5.6 Interfaz

| ID | Requisito | Prioridad |
|---|---|---|
| RF-40 | Material 3 con color dinámico en Android 12+ | Must |
| RF-41 | Tema claro, oscuro y automático | Must |
| RF-42 | Español e inglés completos, sin cadenas embebidas en código | Must |
| RF-43 | Onboarding que explica y solicita cada permiso con su porqué | Must |
| RF-44 | Quick Settings tile para activar/pausar | Must |
| RF-45 | Widget de pantalla de inicio con el perfil activo y acceso rápido | Should |
| RF-46 | Pantalla de historial filtrable | Must |
| RF-47 | Navegación con Navigation Compose y estado preservado en rotación | Must |

---

## 6. Requisitos no funcionales

| ID | Requisito | Criterio medible |
|---|---|---|
| RNF-01 | Precisión de disparo | ≤ 60 s de desviación con permiso exacto concedido |
| RNF-02 | Consumo | Sin servicio en primer plano permanente; impacto de batería < 1 %/día |
| RNF-03 | Arranque en frío | < 500 ms hasta primer frame en gama media |
| RNF-04 | Tamaño del APK | < 8 MB |
| RNF-05 | Privacidad | Sin permiso `INTERNET`. Verificable en el manifiesto |
| RNF-06 | Cobertura de tests | ≥ 80 % en módulos `:core:domain` y `:core:scheduler` |
| RNF-07 | Compatibilidad | `minSdk 26`, `targetSdk 36`, `compileSdk 36` |
| RNF-08 | Accesibilidad | Contraste AA, targets ≥ 48 dp, todo etiquetado para TalkBack |
| RNF-09 | Robustez | Ningún crash no controlado ante permisos denegados o `SecurityException` |
| RNF-10 | Licencia | GPLv3 (coherente con el ecosistema FOSS de la categoría) |
| RNF-11 | Idioma del código | Código, comentarios y commits en inglés; documentación de proyecto en español |
| RNF-12 | Reproducibilidad | El proyecto compila con `./gradlew assembleDebug` en un runner limpio de GitHub Actions |

---

## 7. Arquitectura objetivo

### 7.1 Modularización

```
:app                    → Application, MainActivity, navegación, DI raíz
:core:model             → modelos de dominio puros (Kotlin, sin Android)
:core:domain            → casos de uso, resolución de conflictos, cálculo de franjas
:core:data              → Room, DataStore, repositorios, import/export
:core:audio             → AudioManager, NotificationManager, snapshots, rampas
:core:scheduler         → AlarmManager, WorkManager, receivers, reconciliación
:core:designsystem      → tema, tipografía, componentes Compose reutilizables
:feature:profiles       → lista y edición de perfiles
:feature:schedule       → franjas y línea de tiempo
:feature:history        → historial de actividad
:feature:settings       → ajustes, diagnóstico, import/export
:feature:onboarding     → permisos y bienvenida
```

Los módulos `:core:model` y `:core:domain` **no dependen del SDK de Android**. Esto es deliberado: permite tests JVM rápidos y verificación de sintaxis fuera de un entorno Android.

### 7.2 Patrones

- **MVVM + UDF**: ViewModel expone `StateFlow<UiState>`, la UI emite eventos.
- **Repository** sobre Room/DataStore.
- **Use cases** como clases de una sola responsabilidad en `:core:domain`.
- **DI con Hilt**.
- **Corrutinas + Flow** en toda la capa asíncrona.

### 7.3 Algoritmo de resolución de conflictos (núcleo del sistema)

Dado un instante `t`:

1. Si hay **pausa global** activa y `t < pausaHasta` → no aplicar nada.
2. Si hay **activación manual** vigente → gana siempre sobre lo programado.
3. Reunir todas las franjas habilitadas cuyo perfil esté habilitado y que contengan `t`.
4. Ordenar por `priority` descendente; desempatar por franja más corta (más específica); desempatar por `profileId` ascendente (determinismo).
5. Si hay ganador y difiere del perfil actualmente aplicado → aplicar.
6. Si no hay ninguna franja activa y había una activa antes → restaurar snapshot o aplicar el perfil por defecto.

Este algoritmo vive en `:core:domain`, es puro y es la pieza con mayor cobertura de tests.

### 7.4 Cálculo de la siguiente transición

El scheduler no programa "una alarma por franja". Programa **una única alarma** para el **siguiente instante de cambio** del sistema completo (sea un inicio o un fin de franja). Al dispararse: aplica, recalcula y reprograma. Ventajas: una sola alarma pendiente, resistente a límites del sistema, trivial de reconciliar.

---

## 8. Permisos

| Permiso | Cuándo | Cómo se pide |
|---|---|---|
| `ACCESS_NOTIFICATION_POLICY` | Siempre (necesario para silencio/vibración y DND) | Pantalla del sistema, explicado en onboarding |
| `SCHEDULE_EXACT_ALARM` | Android 12+ | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, con degradación si se deniega |
| `POST_NOTIFICATIONS` | Android 13+ | Runtime, opcional (solo para avisos de aplicación) |
| `RECEIVE_BOOT_COMPLETED` | Siempre | Normal, sin diálogo |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Opcional | Solo si el diagnóstico detecta problema |
| `READ_PHONE_STATE` | Solo si se usa "omitir durante llamada" | Runtime, opcional |
| ~~`INTERNET`~~ | **Nunca** | — |

---

## 9. Riesgos y mitigaciones

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Denegación de alarma exacta | Alto | Degradación a `setWindow` + reconciliador WorkManager + aviso claro |
| OEM que mata procesos | Alto | Guía por fabricante, exclusión de batería, reconciliación al desbloquear |
| `SecurityException` en DND | Alto | Comprobación previa en cada operación, registro y aviso accionable |
| Acoplamiento tono/notificación por dispositivo | Medio | Detección en runtime, UI adaptativa, documentado |
| Fragmentación de pasos de volumen | Medio | Modelo en porcentaje |
| Política de Play sobre alarmas exactas | Medio | No usar `USE_EXACT_ALARM`; justificación de `SCHEDULE_EXACT_ALARM` en la ficha |
| No poder compilar en el entorno de desarrollo actual | Medio | CI de GitHub Actions como entorno de compilación autoritativo |

---

## 10. Criterios de aceptación de la v1.0

1. El proyecto compila en CI limpio y produce un APK de debug.
2. Los tests unitarios de `:core:domain` y `:core:scheduler` pasan con ≥ 80 % de cobertura.
3. El algoritmo de resolución de conflictos está cubierto para: solape, cruce de medianoche, prioridad igual, pausa global, activación manual y ausencia de franjas.
4. El manifiesto no contiene `INTERNET`.
5. Existe onboarding funcional para los tres permisos críticos.
6. Existe exportación e importación JSON con ida y vuelta sin pérdida.
7. README, LICENSE, CONTRIBUTING y CI están en el repositorio.
8. Toda la documentación de fases (`docs/00`…`docs/06`) está completa.

---

## 11. Nombre y identidad

- **Nombre:** RitMute
- **Package:** `com.ritmute.app`
- **Tagline:** "Tu móvil suena como debe, cuando debe."
- **Licencia:** GPLv3
