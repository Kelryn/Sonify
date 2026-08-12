He leído ambos documentos. Aquí está el informe.

---

# Informe de UI/UX y Jetpack Compose — SonoRitmo

## Veredicto general

La especificación es sólida en dominio, motor y fiabilidad, pero **la capa de UI está subespecificada respecto al resto**: RF-40…RF-47 son siete líneas de tabla para lo que en realidad son 14 pantallas, 4 hojas modales y 2 superficies fuera de la app. Este informe cierra ese hueco.

Tres conclusiones principales:

1. **La tesis de producto es "fiabilidad + simplicidad", y eso obliga a una UI de tipo panel de control, no de tipo editor.** La pantalla principal debe responder en menos de un segundo a tres preguntas: *¿qué está sonando ahora?*, *¿por qué?*, *¿qué viene después?*. Ninguna app de la competencia responde a ninguna de las tres. Propongo un `ActiveStateBanner` persistente como elemento identitario de la app.

2. **Hay un conflicto real entre el modelo de datos y la queja de usabilidad de la competencia sobre la hora de fin obligatoria.** El documento 01 define `Schedule.endTime: LocalTime` como no nulo (§4.5) y a la vez el documento 00 (§3.3) señala como defecto explícito «obligación de definir hora de fin cuando no siempre tiene sentido». **Si mantenemos `endTime` no nulo, replicamos literalmente el defecto que decimos corregir.** Petición formal de cambio al responsable de dominio/datos, detallada en la última sección: `endTime: LocalTime?` con semántica «hasta que otro perfil tome el control» (open-ended). Es un cambio de una línea en el modelo y de dos ramas en el resolutor de conflictos, y es la diferencia entre corregir la queja o no.

3. **El gesto principal de la lista debe ser activar, no editar, y eso hay que decidirlo ahora** porque condiciona la anatomía de la tarjeta, la navegación (la edición pasa a ser secundaria y accesible desde un icono explícito) y la accesibilidad (acción por defecto de TalkBack). Lo especifico en detalle abajo.

Riesgos de UI que veo desde ya: (a) el editor de perfil tiene 7 sliders + 5 campos DND + 6 opciones, y si se presenta plano será un muro inusable — hay que estratificarlo en «básico / avanzado»; (b) el onboarding encadena tres pantallas del sistema que devuelven al usuario sin resultado inmediato, y sin un patrón de reentrada bien resuelto el usuario se pierde; (c) `minSdk 26` con Glance obliga a comprobar compatibilidad de `GlanceAppWidget` en API 26–30 (funciona, pero sin `RemoteViews` con contenido dinámico avanzado ni previsualizaciones — hay que usar `androidx.glance:glance-appwidget` con fallback de layout XML para el preview).

---

## Mapa de pantallas y navegación

Navigation Compose con **rutas type-safe** (`@Serializable`, Navigation 2.8+). Un único `NavHost` en `:app`, grafos anidados por feature.

```
RootNavHost  (start = RouteSplash)
│
├── RouteSplash                          Decide destino: onboarding completado? → Main : Onboarding.
│                                        Sin UI propia más allá del splash del sistema (< 500 ms, RNF-03).
│
├── OnboardingGraph                      Grafo anidado. Lineal, con salida a Main. popUpTo(inclusive) al salir.
│   ├── RouteWelcome                     Qué hace la app, 3 promesas, botón "Empezar".
│   ├── RoutePermissionDnd               ACCESS_NOTIFICATION_POLICY. Crítico.
│   ├── RoutePermissionExactAlarm        SCHEDULE_EXACT_ALARM (API 31+). Crítico, degradable.
│   ├── RoutePermissionNotifications     POST_NOTIFICATIONS (API 33+). Opcional.
│   ├── RouteBatteryGuidance             Condicional: solo si fabricante agresivo (RF-34). Opcional.
│   └── RouteFirstProfile                Elige plantilla inicial (RF-07) → crea perfil → Main.
│
└── MainGraph                            NavigationSuiteScaffold (bottom bar en compacto,
    │                                    rail en medio/expandido). Estado por pestaña preservado.
    │
    ├── RouteProfiles          [tab 1]   Pantalla de inicio. Banner de estado + lista de perfiles.
    ├── RouteTimeline          [tab 2]   Línea de tiempo semanal (RF-18) + lista de franjas.
    ├── RouteHistory           [tab 3]   Historial filtrable (RF-46, D6).
    ├── RouteSettings          [tab 4]   Ajustes, diagnóstico, copia de seguridad, acerca de.
    │
    ├── RouteProfileEditor(profileId: Long?)         Pantalla completa, fuera de las pestañas.
    │                                                 null = creación. Sub-secciones en la misma pantalla.
    ├── RouteScheduleEditor(profileId: Long,
    │                       scheduleId: Long?)        Pantalla completa.
    ├── RouteDiagnostics                              Panel de salud (RF-33). Deep-linkable.
    ├── RouteBackup                                   Exportar / importar JSON (RF-36, RF-37).
    ├── RouteAbout                                    Licencia GPLv3, versión, privacidad verificable.
    │
    └── Destinos modales (ModalBottomSheet, no rutas de pila salvo donde se indica):
        ├── SheetTemplatePicker                       Plantillas predefinidas (RF-07).
        ├── SheetActivateWithDuration(profileId)      30 min / 1 h / 2 h / hasta mañana / indefinida (RF-06).
        ├── SheetGlobalPause                          Pausa global con duración (RF-17, D10).
        ├── SheetProfilePreview(profileId)            Vista previa del efecto (RF-09).
        ├── SheetLogDetail(entryId)                   Detalle de una entrada de historial.
        └── SheetConflictExplain(instant)             "Por qué gana este perfil" desde la línea de tiempo.
```

### Reglas de navegación

- **Jerarquía de 2 niveles máximo** desde cualquier pestaña. Nada requiere tres saltos.
- **La edición nunca es el destino por defecto de un toque en la lista.** Se llega a `RouteProfileEditor` solo desde: icono explícito de edición en la tarjeta, menú de desbordamiento, o FAB de creación.
- **Deep links** (esquema `sonoritmo://`, sin `http`, coherente con la ausencia de `INTERNET`):
  - `sonoritmo://profiles` — desde widget y notificación.
  - `sonoritmo://profile/{id}` — desde widget al tocar un perfil concreto.
  - `sonoritmo://diagnostics` — desde la notificación de permiso faltante y desde el QS tile en estado `UNAVAILABLE`.
  - `sonoritmo://history?profileId={id}` — desde la notificación «se aplicó X» (RF-27, CU-07).
  - `sonoritmo://pause` — desde el QS tile de pausa.
- **Preservación de estado en rotación (RF-47):** `rememberSaveable` para estado local de UI (posición de scroll, pestaña de filtro, campos de texto no confirmados) y `SavedStateHandle` en ViewModels para parámetros de ruta y filtros. Los editores mantienen el borrador completo en `SavedStateHandle` para sobrevivir a muerte del proceso durante la ida a la pantalla de permisos del sistema.
- **Reentrada desde pantallas del sistema:** las llamadas a `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` y `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` no devuelven resultado. Hay que **reevaluar el estado del permiso en `ON_RESUME`** mediante un observador de ciclo de vida en cada pantalla que dependa de un permiso, no en `onActivityResult`.

---

## Especificación de cada pantalla

### 1. `RouteProfiles` — Inicio / Perfiles

**Propósito.** Responder en un vistazo a «qué está activo, por qué, y qué viene después», y permitir activar cualquier perfil en un solo toque.

**Componentes (de arriba abajo).**
- `TopAppBar` (`LargeTopAppBar` colapsable) con título «SonoRitmo», acción de pausa global (icono `pause_circle`, con badge si la pausa está activa) y desbordamiento (Ajustes, Diagnóstico).
- **`ActiveStateBanner`** — el componente identitario. Card `tonalElevation`, ancho completo, con:
  - Emoji + nombre del perfil activo, o «Sin perfil activo».
  - Línea de origen: «Programado 23:00–07:00» / «Activado a mano, quedan 47 min» / «Sin reglas activas ahora».
  - Línea de siguiente transición: «A las 07:00 vuelve a Trabajo».
  - Si hay pausa global: fondo con color semántico `paused`, texto «Reglas en pausa hasta las 18:30» y acción «Reanudar».
  - Barra de progreso lineal cuando hay activación temporal con fin conocido.
- **`PermissionAlertCard`** (condicional, solo si falta un permiso crítico): banner de advertencia con «Falta un permiso: las reglas pueden no aplicarse» y acción directa «Revisar».
- **Lista de perfiles** (`LazyColumn`, `key = profile.id`) de `ProfileCard`.
- `FloatingActionButton` extendido «Nuevo perfil» → `SheetTemplatePicker`.

**Anatomía y comportamiento de `ProfileCard` (crítico).**
- **Área principal de toque (≈80 % de la tarjeta) = activar/desactivar.** Un toque activa el perfil de inmediato, muestra `Snackbar` con «Perfil "Noche" activado» + acción «Deshacer» (5 s) + acción «Duración…» que abre `SheetActivateWithDuration`.
- **Pulsación larga** sobre el área principal = abre `SheetActivateWithDuration` directamente (activar con duración sin pasar por el snackbar).
- **Icono de edición explícito** (`IconButton`, lápiz, 48 dp) a la derecha → `RouteProfileEditor`.
- **Icono de desbordamiento** → Duplicar, Programar franja, Vista previa, Prioridad, Eliminar.
- **Indicador de activo (RF-08):** triple redundancia, no solo color — (a) borde/contenedor con `activeContainer`, (b) icono `check_circle` con etiqueta textual **«Activo»** en un `AssistChip`, (c) `stateDescription` de TalkBack. Nunca depender del color solo (contraste y daltonismo).
- **Indicador de programado**: chip secundario «L–V 9:00–18:00» o «Sin programar».
- Deslizar (swipe) NO se usa para borrar en v1 — demasiado destructivo y poco descubrible con TalkBack.

**Estados.**
- *Cargando:* 3 `ProfileCard` skeleton con `Modifier.shimmer` propio (sin librerías externas, RNF-04) durante > 150 ms; por debajo, nada (evita parpadeo).
- *Vacío:* `EmptyState` con ilustración vectorial ligera, título «Aún no tienes perfiles», cuerpo «Empieza con una plantilla: Noche, Trabajo o Reunión. Se configura en menos de 30 segundos.», botón primario «Elegir plantilla», botón texto «Crear desde cero».
- *Error:* fallo de lectura de Room → `ErrorState` con «No se pudo cargar tus perfiles» + «Reintentar». Nunca pantalla en blanco.
- *Degradado:* permiso de alarma exacta denegado → banner informativo persistente, no bloqueante: «Sin alarmas exactas los cambios pueden retrasarse hasta 5 minutos» + «Conceder».

**Acciones principales.** Activar perfil, pausar reglas, crear perfil.
**Secundarias.** Editar, duplicar, eliminar, vista previa, reordenar por prioridad, ir a diagnóstico.

---

### 2. `RouteProfileEditor` — Editor de perfil

**Propósito.** Configurar un perfil completo sin abrumar. **Estratificado**: lo que el 90 % necesita, arriba y visible; el resto, plegado.

**Componentes.**
- `TopAppBar` con navegación atrás (con confirmación si hay cambios sin guardar), título «Nuevo perfil» / nombre del perfil, acción «Guardar» (habilitada solo si válido y modificado).
- **Sección Identidad:** `OutlinedTextField` de nombre (con contador y validación de vacío), selector de emoji (`FlowRow` de emojis frecuentes + campo libre), selector de color semilla (fila de 8 muestras).
- **Sección Sonido (expandida por defecto):**
  - `RingerModeSelector` — `SegmentedButton` de 4 opciones: Normal / Vibración / Silencio / No tocar.
  - `VolumeSliderRow` por stream, en este orden: Tono, Notificación, Multimedia, Alarma. Cada fila: icono, etiqueta, `Switch` pequeño «No tocar» (o checkbox), `Slider` 0–100 con valor numérico visible y editable, y valor traducido a pasos del dispositivo en texto de apoyo («≈ 7 de 15»).
  - **Acoplamiento tono/notificación (RF-10):** si se detecta acoplado, se fusionan ambas filas en una sola etiquetada «Tono y notificaciones» con un `InfoBanner` explicativo: «En este dispositivo el tono y las notificaciones comparten volumen.» No se ocultan silenciosamente.
- **Sección Streams avanzados (plegada):** Llamada, Sistema, Accesibilidad.
- **Sección No molestar (plegada, expandida si `mode != OFF`):** selector de modo (Desactivado / Prioridad / Solo alarmas / Silencio total / No tocar), y al elegir Prioridad se despliegan: llamadas permitidas (Ninguna / Destacados / Contactos / Cualquiera), `Switch` «Llamadas repetidas», mensajes, y los cuatro `Switch` de alarmas/multimedia/recordatorios/eventos. `Switch` «Registrar como modo del sistema» (solo API ≥ 30, con explicación).
- **Sección Opciones (plegada):** los seis campos de `ProfileOptions`, cada uno con texto de apoyo en lenguaje llano. `transitionSeconds` solo visible si `gradualTransition` está activo.
- **Sección Prioridad:** `Slider` discreto 0–100 con etiquetas «Baja / Normal / Alta» y texto explicativo «Si dos perfiles coinciden, gana el de mayor prioridad».
- **Barra inferior:** «Vista previa» (secundaria, aplica 10 s y revierte) y «Guardar» (primaria).

**Estados.**
- *Cargando* (edición): esqueleto de formulario.
- *Vacío*: no aplica (siempre hay formulario; en creación se precarga la plantilla).
- *Error de guardado*: `Snackbar` con motivo y reintento; no se pierde el borrador.
- *Validación*: nombre vacío → error en el campo, botón guardar deshabilitado + `supportingText` «Ponle un nombre al perfil».
- *Permiso faltante*: si se selecciona Silencio/Vibración o DND sin `ACCESS_NOTIFICATION_POLICY`, aparece un `InfoBanner` en línea (no un diálogo bloqueante) con «Necesitamos acceso a No molestar para aplicar esto» + «Conceder». **Se permite guardar igualmente**; el fallo se registrará en el historial. Nunca bloquear la configuración por un permiso pendiente.

**Acciones principales.** Guardar, vista previa.
**Secundarias.** Descartar, duplicar, eliminar, programar franja (salta a `RouteScheduleEditor`).

---

### 3. `RouteScheduleEditor` — Editor de franja

**Propósito.** Definir cuándo se aplica un perfil. Es la pantalla donde se corrige la queja de la hora de fin obligatoria.

**Componentes.**
- `TimeRangeField`: dos botones grandes «Desde 23:00» / «Hasta 07:00», cada uno abre `TimePicker` de M3 (con entrada por teclado alternativa obligatoria para accesibilidad).
- **`Switch` «Sin hora de fin»** — al activarlo, el campo «Hasta» se sustituye por el texto «Hasta que otro perfil tome el control». **Esta es la corrección directa de la queja de la competencia.**
- `DayOfWeekPicker`: 7 `FilterChip` circulares (L M X J V S D) con atajos «Todos», «Entre semana», «Fin de semana». Validación: no puede quedar vacío.
- **`MidnightCrossNotice`**: cuando `endTime <= startTime`, aparece automáticamente un `InfoBanner`: «Esta franja cruza la medianoche: empieza el día seleccionado y termina al día siguiente.» Con ejemplo concreto usando el primer día marcado.
- **`SchedulePreviewStrip`**: barra horizontal de 24 h que muestra visualmente el tramo cubierto, y si hay solape con otra franja, la marca y explica quién ganaría según prioridad.
- `Switch` «Franja activa» (RF-14).

**Estados.** Cargando (edición) / error de guardado / conflicto detectado (advertencia no bloqueante con enlace a `SheetConflictExplain`) / inválido (sin días seleccionados).

**Acciones principales.** Guardar franja.
**Secundarias.** Eliminar franja, desactivar sin borrar, duplicar a otro perfil.

---

### 4. `RouteTimeline` — Línea de tiempo semanal

**Propósito.** RF-18. Que el usuario *vea* su semana y detecte huecos y solapes sin ejecutar nada.

**Componentes.**
- Cuadrícula 7 columnas (días) × 24 filas (horas), bloques coloreados con el `colorSeed` del perfil, scroll vertical sincronizado, cabecera de días fija.
- Línea de «ahora» sobre la cuadrícula.
- Leyenda de perfiles debajo, tocable para filtrar.
- Toque en un bloque → `SheetConflictExplain` con: qué perfil gana en ese tramo, qué otros compiten y por qué pierden (prioridad → duración → id, §7.3).
- Lista secundaria: todas las franjas agrupadas por perfil, con toggle rápido.

**Estados.**
- *Vacío:* «Todavía no has programado nada» + «Programar una franja».
- *Cargando:* cuadrícula gris.
- *Error:* reintento.
- **Alerta de cobertura:** si hay solapes irresolubles o franjas huérfanas (perfil deshabilitado), banner de advertencia.

**Acciones.** Crear franja, activar/desactivar franja, ver explicación de conflicto.

---

### 5. `RouteHistory` — Historial

**Propósito.** D6 y CU-07: «¿por qué sonó así?». Es un argumento de venta, no una pantalla de depuración; el lenguaje debe ser humano.

**Componentes.**
- Fila de `FilterChip`: Todo / Aplicado / Restaurado / Omitido / Errores. Selector de rango de fechas.
- `LazyColumn` agrupada por día con cabeceras pegajosas («Hoy», «Ayer», fecha larga).
- `LogEntryRow`: icono semántico según tipo, hora en negrita, frase legible generada («A las 23:00 se aplicó **Noche** porque empezó la franja de lunes a domingo»), y para errores, el motivo en lenguaje llano («No se pudo silenciar: falta el permiso de No molestar») con acción «Arreglar» que lleva a `RouteDiagnostics`.
- Toque → `SheetLogDetail` con datos técnicos (streams aplicados, valores, `SecurityException` si la hubo) y botón «Copiar detalle» para informes de fallo.

**Estados.** Vacío («Aquí aparecerá cada cambio de sonido, con su motivo») / cargando (paginado con `LazyPagingItems` o `Flow` limitado a 1 000) / error.

**Acciones.** Filtrar, ver detalle, borrar historial (con confirmación), exportar historial a texto.

---

### 6. `RouteSettings` — Ajustes

**Propósito.** Configuración global y punto de entrada a diagnóstico y copia de seguridad.

**Secciones.**
- **Estado del sistema**: fila resumen con semáforo → `RouteDiagnostics`.
- **Apariencia**: tema (Claro / Oscuro / Sistema), `Switch` «Color dinámico» (solo API 31+), idioma de la app (Android 13+: abre `ACTION_APP_LOCALE_SETTINGS`; por debajo, selector interno con `AppCompatDelegate.setApplicationLocales`).
- **Comportamiento**: perfil por defecto al terminar una franja, notificar al aplicar, rampa de volumen global.
- **Datos**: exportar / importar JSON → `RouteBackup`. Purga de historial.
- **Accesos rápidos**: «Añadir mosaico a Ajustes rápidos» (API 33+ `requestAddTileService`), «Añadir widget» (API 26+ `requestPinAppWidget`).
- **Acerca de**: versión, GPLv3, código fuente, y una fila destacada **«Esta app no puede conectarse a internet»** con explicación verificable (RNF-05, D4). Es diferenciación de producto; merece estar en la UI.

**Estados.** Cargando de preferencias (instantáneo con DataStore, sin skeleton) / error de escritura → snackbar.

---

### 7. `RouteDiagnostics` — Panel de salud

**Propósito.** RF-33, CU-10. La pantalla que ninguna competidora tiene y que convierte «a veces no funciona» en «esto es lo que falta y así se arregla».

**Componentes.**
- **Cabecera de salud**: círculo con estado global — Correcto / Atención / Problema — y frase resumen («Todo listo: tus reglas se aplicarán a su hora»).
- Lista de `DiagnosticRow`, cada una con: icono de estado, título, explicación de una línea de la consecuencia real, y botón de acción directa.
  - Acceso a No molestar → «Conceder»
  - Alarmas exactas → «Conceder» / «Degradado a ventana de 5 min»
  - Notificaciones → «Permitir»
  - Optimización de batería → «Excluir SonoRitmo»
  - Guía del fabricante (RF-34) → «Ver pasos para Xiaomi» (contenido embebido, sin abrir navegador — no hay `INTERNET`)
  - Salud del programador: «Próxima transición: hoy a las 18:00», «Última comprobación: hace 12 min», con acción «Comprobar ahora» (fuerza reconciliación).
- Botón «Ejecutar prueba»: aplica y revierte un cambio inocuo y reporta si funcionó.

**Estados.** Todo correcto (estado celebratorio, breve) / con avisos / comprobando (por fila, no global).

---

### 8. `RouteBackup` — Copia de seguridad

**Propósito.** RF-36/37, D5, corrige el fallo 5 del mercado.

**Componentes.** Dos tarjetas grandes: «Exportar» (SAF `CreateDocument`, nombre sugerido `sonoritmo-YYYY-MM-DD.json`) e «Importar» (`OpenDocument`). Al importar: pantalla de confirmación previa con resumen («6 perfiles, 9 franjas — versión de esquema 1») y elección **Fusionar / Reemplazar**, con advertencia explícita en el caso destructivo.

**Estados.** Idle / procesando (progreso determinado) / éxito (resumen) / error de validación (mensaje concreto: versión incompatible, JSON malformado, campo desconocido).

---

### 9. Onboarding (5–6 pantallas)

Especificado con texto exacto en la sección «Flujo de onboarding de permisos».

---

### 10. Superficie: Quick Settings tile (RF-44, D9)

**Dos `TileService` separados**, porque mezclan dos intenciones distintas:

- **`QuickProfileTileService`** — «Perfil rápido». Alterna el perfil marcado como favorito (o el último activado).
  - Estados: `STATE_ACTIVE` (subtítulo = nombre del perfil, icono con el emoji renderizado no — usar icono vectorial monocromo; el emoji no se renderiza bien en tiles), `STATE_INACTIVE`, `STATE_UNAVAILABLE` cuando falta `ACCESS_NOTIFICATION_POLICY` (toque → deep link a diagnóstico vía `startActivityAndCollapse` con `PendingIntent`, obligatorio en API 34+).
  - Etiqueta: «SonoRitmo». Subtítulo (API 29+): nombre del perfil o «Sin perfil».
- **`PauseTileService`** — «Pausar reglas». Alterna la pausa global de 1 h por defecto; pulsación larga → app en `SheetGlobalPause`.

Actualización mediante `TileService.requestListeningState()` tras cada transición del motor. Ambos deben funcionar sin abrir la app (nada de `startActivity` en el camino feliz).

---

### 11. Superficie: Widget con Glance (RF-45, D9)

`GlanceAppWidget` con `SizeMode.Responsive` y tres puntos de ruptura:

- **Pequeño (2×1):** emoji + nombre del perfil activo + hora de la próxima transición. Toque → abre la app.
- **Mediano (4×1):** lo anterior + botón de pausa/reanudar.
- **Grande (4×2):** lo anterior + fila de hasta 4 perfiles favoritos activables directamente con `actionRunCallback`.

Detalles obligatorios:
- Estado leído vía `GlanceStateDefinition` propia respaldada por el repositorio; **nunca** consultas a Room en el hilo del widget sin `withContext`.
- Actualización con `GlanceAppWidgetManager.getGlanceIds()` + `update()` disparada por el mismo evento que actualiza el tile (una sola fuente: un `ActiveStateRepository`).
- Tema: `GlanceTheme.colors` con `dynamicThemeColorProviders` en API 31+ y paleta propia por debajo.
- Estado de error/permiso: si falta permiso crítico, el widget muestra «Falta un permiso» con toque a diagnóstico. Nunca mostrar un estado falsamente correcto.
- `previewImage` + `description` en `appwidget-provider` para el selector de widgets.

---

## Contratos de UiState y eventos por pantalla

Convenciones: un `data class` inmutable por pantalla, expuesto como `StateFlow` con `stateIn(WhileSubscribed(5_000))`; eventos de usuario como `sealed interface ...Event` con un único `onEvent(event)`; efectos de una sola vez como `Channel` → `Flow` de `sealed interface ...Effect`. Nada de `LiveData`, nada de estado mutable expuesto.

### Tipos compartidos

```kotlin
// :core:designsystem / :feature:common

@Immutable
sealed interface UiText {
    data class Raw(val value: String) : UiText
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText
}

@Immutable
data class ProfileUi(
    val id: Long,
    val name: String,
    val emoji: String?,
    val colorSeed: Int,
    val enabled: Boolean,
    val priority: Int,
    val isActive: Boolean,
    val activationSource: ActivationSource,   // SCHEDULE | MANUAL | NONE
    val scheduleSummary: UiText?,             // "L–V · 9:00–18:00" | null si no programado
    val volumeSummary: UiText,                // "Tono 0 % · Multimedia 30 % · Alarma 100 %"
    val hasDnd: Boolean,
)

@Immutable
sealed interface ActiveState {
    data object None : ActiveState
    data class Paused(val until: Instant?, val remaining: Duration?) : ActiveState
    data class Manual(val profile: ProfileUi, val until: Instant?, val remaining: Duration?) : ActiveState
    data class Scheduled(val profile: ProfileUi, val windowStart: LocalTime, val windowEnd: LocalTime?) : ActiveState
}

@Immutable
data class NextTransitionUi(val at: Instant, val profileName: String?, val isRestore: Boolean)

@Immutable
data class PermissionStatusUi(
    val dndAccess: Boolean,
    val exactAlarms: ExactAlarmStatus,   // GRANTED | DENIED | NOT_REQUIRED
    val notifications: Boolean,
    val batteryOptimized: Boolean,
) {
    val hasCriticalIssue: Boolean get() = !dndAccess || exactAlarms == ExactAlarmStatus.DENIED
}

@Immutable
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Success<T>(val data: T) : LoadState<T>
    data class Error(val message: UiText, val retryable: Boolean = true) : LoadState<Nothing>
}
```

### `RouteProfiles`

```kotlin
@Immutable
data class ProfilesUiState(
    val isLoading: Boolean = true,
    val profiles: List<ProfileUi> = emptyList(),
    val activeState: ActiveState = ActiveState.None,
    val nextTransition: NextTransitionUi? = null,
    val permissions: PermissionStatusUi? = null,
    val error: UiText? = null,
) {
    val isEmpty: Boolean get() = !isLoading && profiles.isEmpty() && error == null
    val showPermissionBanner: Boolean get() = permissions?.hasCriticalIssue == true
}

sealed interface ProfilesEvent {
    data class ProfileClicked(val id: Long) : ProfilesEvent          // ACTIVA, no edita
    data class ProfileLongPressed(val id: Long) : ProfilesEvent      // abre duración
    data class EditClicked(val id: Long) : ProfilesEvent
    data class DuplicateClicked(val id: Long) : ProfilesEvent
    data class DeleteRequested(val id: Long) : ProfilesEvent
    data class DeleteConfirmed(val id: Long) : ProfilesEvent
    data class ActivateWithDuration(val id: Long, val duration: ActivationDuration) : ProfilesEvent
    data object UndoLastActivation : ProfilesEvent
    data object DeactivateCurrent : ProfilesEvent
    data object PauseClicked : ProfilesEvent
    data class PauseConfirmed(val duration: PauseDuration) : ProfilesEvent
    data object ResumeRules : ProfilesEvent
    data object CreateProfileClicked : ProfilesEvent
    data object PermissionBannerClicked : ProfilesEvent
    data object Retry : ProfilesEvent
}

sealed interface ProfilesEffect {
    data class NavigateToEditor(val profileId: Long?) : ProfilesEffect
    data object NavigateToDiagnostics : ProfilesEffect
    data class ShowSnackbar(val message: UiText, val action: SnackbarAction?) : ProfilesEffect
    data class ShowActivationSheet(val profileId: Long) : ProfilesEffect
    data object ShowTemplatePicker : ProfilesEffect
    data object ShowPauseSheet : ProfilesEffect
}

enum class ActivationDuration { MIN_30, HOUR_1, HOUR_2, UNTIL_TOMORROW, INDEFINITE }
enum class PauseDuration { HOUR_1, HOUR_4, UNTIL_TOMORROW, INDEFINITE }
```

### `RouteProfileEditor`

```kotlin
@Immutable
data class ProfileEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = false,
    val name: String = "",
    val nameError: UiText? = null,
    val emoji: String? = null,
    val colorSeed: Int = 0,
    val priority: Int = 50,
    val ringerMode: RingerMode = RingerMode.UNCHANGED,
    val volumes: List<VolumeRowUi> = emptyList(),
    val ringNotificationLinked: Boolean = false,   // RF-10, detectado en runtime
    val dnd: DndEditorUi = DndEditorUi(),
    val options: OptionsEditorUi = OptionsEditorUi(),
    val expandedSections: Set<EditorSection> = setOf(EditorSection.SOUND),
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val needsDndPermission: Boolean = false,       // pide silencio/DND sin permiso
    val supportsSystemZenRule: Boolean = false,    // API >= 30
    val error: UiText? = null,
) {
    val canSave: Boolean get() = name.isNotBlank() && nameError == null && !isSaving
}

@Immutable
data class VolumeRowUi(
    val stream: AudioStream,
    val label: UiText,
    val enabled: Boolean,          // false = "no tocar" (null en el modelo)
    val percent: Int,              // 0..100
    val deviceStepsLabel: UiText,  // "≈ 7 de 15"
    val isLinkedPair: Boolean = false,
)

@Immutable
data class DndEditorUi(
    val mode: DndMode = DndMode.UNCHANGED,
    val allowCalls: CallPolicy = CallPolicy.NONE,
    val allowRepeatCallers: Boolean = false,
    val allowMessages: MessagePolicy = MessagePolicy.NONE,
    val allowAlarms: Boolean = true,
    val allowMedia: Boolean = true,
    val allowReminders: Boolean = false,
    val allowEvents: Boolean = false,
    val useSystemMode: Boolean = false,
)

@Immutable
data class OptionsEditorUi(
    val restoreOnExit: Boolean = true,
    val gradualTransition: Boolean = false,
    val transitionSeconds: Int = 5,
    val skipDuringCall: Boolean = true,
    val skipIfMediaPlaying: Boolean = false,
    val notifyOnApply: Boolean = false,
)

enum class EditorSection { IDENTITY, SOUND, ADVANCED_STREAMS, DND, OPTIONS, PRIORITY }

sealed interface ProfileEditorEvent {
    data class NameChanged(val value: String) : ProfileEditorEvent
    data class EmojiChanged(val value: String?) : ProfileEditorEvent
    data class ColorChanged(val seed: Int) : ProfileEditorEvent
    data class PriorityChanged(val value: Int) : ProfileEditorEvent
    data class RingerModeChanged(val mode: RingerMode) : ProfileEditorEvent
    data class VolumeToggled(val stream: AudioStream, val enabled: Boolean) : ProfileEditorEvent
    data class VolumeChanged(val stream: AudioStream, val percent: Int) : ProfileEditorEvent
    data class DndChanged(val dnd: DndEditorUi) : ProfileEditorEvent
    data class OptionsChanged(val options: OptionsEditorUi) : ProfileEditorEvent
    data class SectionToggled(val section: EditorSection) : ProfileEditorEvent
    data object GrantDndPermissionClicked : ProfileEditorEvent
    data object PreviewClicked : ProfileEditorEvent
    data object SaveClicked : ProfileEditorEvent
    data object BackClicked : ProfileEditorEvent
    data object DiscardConfirmed : ProfileEditorEvent
    data object PermissionStateRechecked : ProfileEditorEvent   // en ON_RESUME
}

sealed interface ProfileEditorEffect {
    data object NavigateBack : ProfileEditorEffect
    data object ShowDiscardDialog : ProfileEditorEffect
    data object OpenDndSettings : ProfileEditorEffect
    data class ShowSnackbar(val message: UiText) : ProfileEditorEffect
    data class ShowPreviewSheet(val profileId: Long) : ProfileEditorEffect
}
```

### `RouteScheduleEditor`

```kotlin
@Immutable
data class ScheduleEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = false,
    val profileName: String = "",
    val startTime: LocalTime = LocalTime.of(23, 0),
    val endTime: LocalTime? = LocalTime.of(7, 0),   // null = sin hora de fin
    val openEnded: Boolean = false,
    val daysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val enabled: Boolean = true,
    val crossesMidnight: Boolean = false,
    val daysError: UiText? = null,
    val overlaps: List<OverlapWarningUi> = emptyList(),
    val previewSegments: List<TimelineSegmentUi> = emptyList(),
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
) {
    val canSave: Boolean get() = daysOfWeek.isNotEmpty() && !isSaving
}

@Immutable
data class OverlapWarningUi(
    val otherProfileName: String,
    val otherPriority: Int,
    val thisWins: Boolean,
    val explanation: UiText,
)

sealed interface ScheduleEditorEvent {
    data class StartTimeChanged(val time: LocalTime) : ScheduleEditorEvent
    data class EndTimeChanged(val time: LocalTime) : ScheduleEditorEvent
    data class OpenEndedToggled(val enabled: Boolean) : ScheduleEditorEvent
    data class DayToggled(val day: DayOfWeek) : ScheduleEditorEvent
    data class DayPresetSelected(val preset: DayPreset) : ScheduleEditorEvent  // ALL, WEEKDAYS, WEEKEND
    data class EnabledToggled(val enabled: Boolean) : ScheduleEditorEvent
    data object SaveClicked : ScheduleEditorEvent
    data object DeleteRequested : ScheduleEditorEvent
    data object DeleteConfirmed : ScheduleEditorEvent
    data class OverlapClicked(val index: Int) : ScheduleEditorEvent
}
```

### `RouteTimeline`

```kotlin
@Immutable
data class TimelineUiState(
    val isLoading: Boolean = true,
    val segments: List<TimelineSegmentUi> = emptyList(),
    val legend: List<TimelineLegendItemUi> = emptyList(),
    val schedulesByProfile: List<ProfileSchedulesUi> = emptyList(),
    val nowMarker: TimeMarkerUi? = null,
    val highlightedProfileId: Long? = null,
    val hasOrphanSchedules: Boolean = false,
    val error: UiText? = null,
) { val isEmpty: Boolean get() = !isLoading && segments.isEmpty() && error == null }

@Immutable
data class TimelineSegmentUi(
    val profileId: Long,
    val profileName: String,
    val colorSeed: Int,
    val day: DayOfWeek,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val isWinner: Boolean,          // pierde el conflicto → se dibuja atenuado y rayado
)

sealed interface TimelineEvent {
    data class SegmentClicked(val segment: TimelineSegmentUi) : TimelineEvent
    data class LegendClicked(val profileId: Long) : TimelineEvent
    data class ScheduleToggled(val scheduleId: Long, val enabled: Boolean) : TimelineEvent
    data class CreateScheduleClicked(val profileId: Long?) : TimelineEvent
    data object Retry : TimelineEvent
}
```

### `RouteHistory`

```kotlin
@Immutable
data class HistoryUiState(
    val isLoading: Boolean = true,
    val filter: HistoryFilter = HistoryFilter.ALL,
    val dateRange: ClosedRange<LocalDate>? = null,
    val groups: List<HistoryDayGroupUi> = emptyList(),
    val error: UiText? = null,
) { val isEmpty: Boolean get() = !isLoading && groups.isEmpty() && error == null }

@Immutable
data class HistoryDayGroupUi(val header: UiText, val entries: List<HistoryEntryUi>)

@Immutable
data class HistoryEntryUi(
    val id: Long,
    val time: LocalTime,
    val type: LogType,               // APPLY | RESTORE | SKIP | ERROR | BOOT | PERMISSION
    val headline: UiText,            // "Se aplicó Noche"
    val reason: UiText,              // "Empezó la franja de 23:00 a 07:00"
    val success: Boolean,
    val fixAction: HistoryFixAction?, // GRANT_DND, GRANT_EXACT_ALARM, BATTERY, null
)

enum class HistoryFilter { ALL, APPLIED, RESTORED, SKIPPED, ERRORS }

sealed interface HistoryEvent {
    data class FilterChanged(val filter: HistoryFilter) : HistoryEvent
    data class DateRangeChanged(val range: ClosedRange<LocalDate>?) : HistoryEvent
    data class EntryClicked(val id: Long) : HistoryEvent
    data class FixActionClicked(val action: HistoryFixAction) : HistoryEvent
    data object ClearHistoryRequested : HistoryEvent
    data object ClearHistoryConfirmed : HistoryEvent
    data object Retry : HistoryEvent
}
```

### `RouteDiagnostics`

```kotlin
@Immutable
data class DiagnosticsUiState(
    val overall: HealthLevel = HealthLevel.CHECKING,   // OK | WARNING | PROBLEM | CHECKING
    val summary: UiText = UiText.Res(R.string.diag_checking),
    val items: List<DiagnosticItemUi> = emptyList(),
    val schedulerHealth: SchedulerHealthUi? = null,
    val manufacturerGuide: ManufacturerGuideUi? = null,
    val isRunningSelfTest: Boolean = false,
    val selfTestResult: SelfTestResultUi? = null,
)

@Immutable
data class DiagnosticItemUi(
    val id: DiagnosticId,           // DND_ACCESS, EXACT_ALARM, NOTIFICATIONS, BATTERY, BOOT_RECEIVER
    val title: UiText,
    val status: HealthLevel,
    val consequence: UiText,        // qué se rompe si falta, en lenguaje llano
    val actionLabel: UiText?,
    val isCritical: Boolean,
)

@Immutable
data class SchedulerHealthUi(
    val nextTransitionAt: Instant?,
    val nextTransitionLabel: UiText,
    val lastReconciliationAt: Instant?,
    val pendingAlarmExists: Boolean,
    val usingExactAlarms: Boolean,
)

sealed interface DiagnosticsEvent {
    data class ActionClicked(val id: DiagnosticId) : DiagnosticsEvent
    data object RefreshRequested : DiagnosticsEvent
    data object RunSelfTest : DiagnosticsEvent
    data object ForceReconcile : DiagnosticsEvent
    data object ManufacturerGuideClicked : DiagnosticsEvent
    data object Resumed : DiagnosticsEvent               // reevalúa permisos al volver del sistema
}
```

### Onboarding

```kotlin
@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val totalSteps: Int = 4,
    val currentIndex: Int = 0,
    val dndGranted: Boolean = false,
    val exactAlarmStatus: ExactAlarmStatus = ExactAlarmStatus.NOT_REQUIRED,
    val notificationsGranted: Boolean = false,
    val needsBatteryStep: Boolean = false,
    val manufacturer: String? = null,
    val wasDeniedOnce: Map<OnboardingStep, Boolean> = emptyMap(),
    val templates: List<TemplateUi> = emptyList(),
    val selectedTemplateId: String? = null,
)

enum class OnboardingStep { WELCOME, DND, EXACT_ALARM, NOTIFICATIONS, BATTERY, FIRST_PROFILE }

sealed interface OnboardingEvent {
    data object PrimaryActionClicked : OnboardingEvent      // conceder / continuar
    data object SkipClicked : OnboardingEvent
    data object BackClicked : OnboardingEvent
    data class TemplateSelected(val id: String) : OnboardingEvent
    data object FinishClicked : OnboardingEvent
    data object Resumed : OnboardingEvent                   // reevalúa tras volver del sistema
}

sealed interface OnboardingEffect {
    data object OpenDndSettings : OnboardingEffect
    data object OpenExactAlarmSettings : OnboardingEffect
    data object RequestNotificationPermission : OnboardingEffect
    data object OpenBatterySettings : OnboardingEffect
    data object NavigateToMain : OnboardingEffect
}
```

### Estado compartido (banner activo, tile y widget)

```kotlin
// :core:data — fuente única de verdad para app, tile y widget
interface ActiveStateRepository {
    val activeState: Flow<ActiveState>
    val nextTransition: Flow<NextTransitionUi?>
    suspend fun refreshSurfaces()   // requestListeningState + Glance updateAll
}
```

---

## Sistema de diseño

Módulo `:core:designsystem`. Material 3 (`androidx.compose.material3`), color dinámico en API 31+ (RF-40), paleta propia por debajo.

### Colores semánticos

M3 no cubre los estados que necesitamos (activo, programado, pausado, aviso, éxito). Se añade un **esquema extendido** vía `CompositionLocal`, no colores sueltos:

```kotlin
@Immutable
data class SonoColors(
    val active: Color,            val onActive: Color,
    val activeContainer: Color,   val onActiveContainer: Color,
    val scheduled: Color,         val scheduledContainer: Color,
    val paused: Color,            val pausedContainer: Color,
    val warning: Color,           val onWarning: Color,
    val warningContainer: Color,  val onWarningContainer: Color,
    val success: Color,           val successContainer: Color,
    val streamRing: Color, val streamMusic: Color, val streamAlarm: Color,
    val streamNotification: Color, val streamCall: Color,
    val timelineGrid: Color, val timelineNow: Color,
)
val LocalSonoColors = staticCompositionLocalOf { lightSonoColors() }
```

Reglas:
- **Activo** deriva de `primaryContainer` cuando hay color dinámico, para no chocar con el tema del sistema.
- **Aviso (warning)** es ámbar y debe pasar contraste 4.5:1 sobre su contenedor en claro y oscuro. Verificar los dos temas, no solo el claro.
- **Color por perfil (`colorSeed`)**: se usa como acento decorativo (borde, punto, bloque de la línea de tiempo), **nunca como fondo de texto**, para no romper el contraste. Paleta cerrada de 8 semillas validadas, no color libre.
- Modo oscuro: contenedores con `surfaceContainerHigh`, no negro puro (OLED opcional en ajustes, v1.1).

### Tipografía

Fuente del sistema (RNF-04, sin fuentes empaquetadas). Escala M3 estándar más dos roles propios:

| Rol | Uso | Base |
|---|---|---|
| `displaySmall` | Hora grande en el editor de franja y en el widget grande | M3 |
| `headlineSmall` | Título de pantalla en `LargeTopAppBar` | M3 |
| `titleMedium` | Nombre de perfil en tarjeta | M3 |
| `bodyMedium` | Cuerpo, explicaciones de permisos | M3 |
| `labelLarge` | Botones, chips | M3 |
| `labelSmall` | Metadatos: hora en historial, «≈ 7 de 15» | M3 |
| **`timeDisplay`** (propio) | Horas en la línea de tiempo | `bodyMedium` con `FontFeatureSetting("tnum")` — **cifras tabulares obligatorias** para que las horas no bailen |
| **`monoDetail`** (propio) | Detalle técnico de log, JSON de importación | `FontFamily.Monospace`, `bodySmall` |

Nunca fijar `lineHeight` en `sp` sin permitir escalado; nada de `TextUnit` en `dp`.

### Espaciados y formas

```kotlin
object SonoSpacing {
    val xs = 4.dp; val s = 8.dp; val m = 12.dp
    val l = 16.dp; val xl = 24.dp; val xxl = 32.dp
    val screenHorizontal = 16.dp
    val listItemVertical = 12.dp
    val minTouchTarget = 48.dp
}
```
Formas: `extraSmall` 4 dp (chips), `small` 8 dp, `medium` 12 dp (tarjetas de perfil), `large` 16 dp (banner de estado), `extraLarge` 28 dp (hojas modales). Elevación: preferir `tonalElevation` sobre sombras.

### Componentes reutilizables a construir

Todos con `@Preview` en claro/oscuro/fuente 200 %, y `@PreviewScreenSizes` en los de pantalla.

| Componente | Descripción | Notas críticas |
|---|---|---|
| `ActiveStateBanner` | Estado global: perfil activo, origen, próxima transición, pausa | El componente identitario; live region para TalkBack |
| `ProfileCard` | Tarjeta de perfil con activación al toque | Área de toque separada de la de edición; `stateDescription` |
| `ActiveBadge` | Chip «Activo» con icono + texto | Nunca solo color |
| `VolumeSliderRow` | Slider + toggle «no tocar» + traducción a pasos | `Slider` con `stateDescription` en porcentaje y pasos de 5 con teclado |
| `RingerModeSelector` | `SingleChoiceSegmentedButtonRow` de 4 opciones | Se adapta si el dispositivo acopla streams |
| `DayOfWeekPicker` | 7 chips + presets | Etiquetas completas para TalkBack («lunes»), abreviadas en pantalla |
| `TimeRangeField` | Inicio/fin + «sin hora de fin» | `TimePicker` con entrada de teclado obligatoria |
| `WeekTimelineGrid` | Cuadrícula 7×24 con bloques | `Canvas`/`Layout` propio; semántica alternativa en lista para TalkBack |
| `PermissionCard` | Tarjeta de permiso en onboarding y diagnóstico | Un solo componente para ambos contextos |
| `DiagnosticRow` | Fila con estado + acción | Icono + texto de estado |
| `InfoBanner` / `WarningBanner` | Avisos en línea, no bloqueantes | Con acción opcional |
| `EmptyState` | Icono, título, cuerpo, acción primaria y secundaria | Reutilizado en 5 pantallas |
| `ErrorState` | Con reintento | Mensaje humano, nunca stack trace |
| `LoadingSkeleton` | Placeholder shimmer propio | Sin dependencias externas |
| `DurationChooser` | Lista de duraciones + «indefinida» | Compartido por activación manual y pausa global |
| `EmojiPicker` | Rejilla de emojis frecuentes + búsqueda | Sin recursos gráficos (§4.1 del doc 01) |
| `ColorSeedPicker` | 8 muestras con marca de selección accesible | Marca visual además del color |
| `PriorityStepper` | Slider discreto con etiquetas semánticas | «Baja/Normal/Alta» sobre el número |
| `SectionCard` | Sección plegable del editor | Estado expandido en `rememberSaveable` |
| `SonoScaffold` | Scaffold con snackbar host, insets y top bar comunes | Un único punto para `WindowInsets` (edge-to-edge obligatorio en API 35+) |
| `GlanceProfileRow` / `GlanceStateHeader` | Equivalentes Glance (no comparten código con Compose) | Duplicación consciente: Glance no admite composables de M3 |

---

## Flujo de onboarding de permisos

Principios: **un permiso por pantalla**, siempre explicando *la consecuencia real de no concederlo* (no la API), con salida posible en todos salvo el bienvenida, y **reevaluación en `ON_RESUME`** porque las pantallas del sistema no devuelven resultado. Nada de pedir todo de golpe al arrancar. Indicador de progreso «Paso 2 de 4» arriba.

### Paso 0 — Bienvenida (`RouteWelcome`)

> **Título:** Bienvenido a SonoRitmo
> **Cuerpo:** Tu móvil suena como debe, cuando debe.
> Crea perfiles de sonido y deja que se activen solos: silencio por la noche, vibración en el trabajo, alarma siempre al máximo.
> **Tres líneas con icono:**
> · Se dispara a su hora, aunque la app esté cerrada.
> · Controla volumen, timbre, vibración y No molestar.
> · Sin internet, sin cuentas, sin anuncios. Todo se queda en tu móvil.
> **Botón primario:** Empezar
> **Enlace secundario:** Ya sé cómo funciona, ir directo a la app

*(El enlace secundario salta a `RouteFirstProfile`, pero los permisos siguen apareciendo en el diagnóstico.)*

### Paso 1 — Acceso a No molestar (`RoutePermissionDnd`) · **crítico**

> **Encabezado:** Paso 1 de 4
> **Título:** Permiso para silenciar el teléfono
> **Cuerpo:** Android exige un permiso especial para que una app pueda poner el móvil en silencio, en vibración o activar el modo No molestar.
> **Sin este permiso, SonoRitmo no podrá silenciar tu teléfono ni activar No molestar: tus perfiles fallarán a la hora de la verdad.**
> **Nota en tarjeta informativa:** Se concede desde una pantalla de Ajustes de Android. Busca «SonoRitmo» en la lista y actívalo. Después vuelve aquí con el botón atrás.
> **Botón primario:** Ir a Ajustes y conceder
> **Botón texto:** Ahora no

**Estado concedido (al volver):**
> ✓ **Permiso concedido.** Ya puedes silenciar y usar No molestar.
> **Botón primario:** Continuar

**Estado tras denegar («Ahora no» o volver sin conceder):**
> ⚠ **Sin este permiso, los perfiles con silencio, vibración o No molestar no se aplicarán.** Podrás concederlo más tarde en Ajustes → Estado del sistema.
> **Botón primario:** Reintentar · **Botón texto:** Continuar de todos modos

### Paso 2 — Alarmas exactas (`RoutePermissionExactAlarm`) · **crítico, degradable** · solo API 31+

> **Encabezado:** Paso 2 de 4
> **Título:** Permiso para cambiar el sonido a la hora exacta
> **Cuerpo:** Para que tu perfil de noche empiece a las 23:00 y no a las 23:07, SonoRitmo necesita programar alarmas exactas.
> Es el permiso que marca la diferencia entre una app que funciona y una que «a veces no salta».
> **Nota:** No usamos este permiso para despertarte ni para enviarte nada: solo para cambiar el sonido en el momento exacto que tú has programado.
> **Botón primario:** Permitir alarmas exactas
> **Botón texto:** Prefiero no dar este permiso

**Estado concedido:**
> ✓ **Listo.** Tus cambios de sonido ocurrirán a la hora exacta.

**Estado denegado (degradación explícita, RF-29):**
> ⚠ **Modo aproximado activado.** SonoRitmo seguirá funcionando, pero los cambios pueden retrasarse hasta 5 minutos y el sistema puede aplazarlos si el móvil está en reposo.
> Puedes activarlo cuando quieras en Ajustes → Estado del sistema.
> **Botón primario:** Reintentar · **Botón texto:** Continuar así

### Paso 3 — Notificaciones (`RoutePermissionNotifications`) · **opcional** · solo API 33+

> **Encabezado:** Paso 3 de 4
> **Título:** Avisos cuando cambie tu sonido
> **Cuerpo:** Si lo permites, SonoRitmo te avisará cuando active un perfil y, sobre todo, cuando algo falle: un permiso retirado, un cambio que no se pudo aplicar.
> **Es opcional. La app funciona igual sin notificaciones, pero no podrá avisarte si algo va mal.**
> **Botón primario:** Permitir notificaciones
> **Botón texto:** No, gracias

**Estado denegado permanentemente (segunda denegación / «no volver a preguntar»):**
> Has bloqueado las notificaciones para SonoRitmo. Si cambias de idea, actívalas desde los ajustes de Android.
> **Botón primario:** Abrir ajustes de notificaciones · **Botón texto:** Continuar

### Paso 4 — Optimización de batería (`RouteBatteryGuidance`) · **condicional y opcional**

*Solo se muestra si `Build.MANUFACTURER` está en la lista agresiva (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Samsung) o si la app está bajo optimización.*

> **Encabezado:** Paso 4 de 4
> **Título:** Tu móvil {fabricante} puede cerrar SonoRitmo
> **Cuerpo:** Algunos fabricantes cierran las apps en segundo plano para ahorrar batería. Si eso ocurre, tus perfiles pueden no activarse.
> SonoRitmo consume menos del 1 % de batería al día: no tiene servicios permanentes ni se conecta a internet.
> **Botón primario:** Excluir del ahorro de batería
> **Botón secundario:** Ver los pasos para {fabricante}
> **Botón texto:** Saltar

*Contenido de «Ver los pasos» embebido en la app (no hay `INTERNET`), por ejemplo para Xiaomi:*
> **En tu Xiaomi:**
> 1. Ajustes → Aplicaciones → Administrar aplicaciones → SonoRitmo.
> 2. Activa «Inicio automático».
> 3. En «Ahorro de batería», elige «Sin restricciones».
> 4. En la pantalla de apps recientes, mantén pulsada SonoRitmo y toca el candado.

### Paso 5 — Primer perfil (`RouteFirstProfile`)

> **Título:** Elige tu primer perfil
> **Cuerpo:** Lo puedes cambiar todo después. Empezar con una plantilla es la forma más rápida.
> **Rejilla de plantillas (RF-07):**
> · 🌙 **Noche** — Silencio de 23:00 a 7:00, alarma al 100 %
> · 💼 **Trabajo** — Vibración de 9:00 a 18:00, de lunes a viernes
> · 🤝 **Reunión** — Silencio total con No molestar, activación manual
> · 🎬 **Cine** — Silencio 2 horas y vuelve solo
> · 🚗 **Conducción** — Multimedia alto, tono alto, notificaciones bajas
> · 🌞 **Fin de semana** — Tono alto, sábado y domingo
> · ➕ **Crear desde cero**
> **Botón primario:** Crear perfil
> **Botón texto:** Ahora no, lo haré yo

**Pantalla final (tras crear):**
> ✓ **Todo listo.** «Noche» se activará hoy a las 23:00. Puedes verlo y cambiarlo cuando quieras.
> **Botón primario:** Ir a mis perfiles

### Notas de implementación del onboarding

- Persistir `onboardingCompleted` y `onboardingVersion` en DataStore. Si en v1.1 se añade un permiso nuevo, se muestra solo ese paso, no todo el flujo.
- El botón atrás del sistema retrocede paso a paso; en el paso 0 sale de la app.
- **Nunca** mostrar una pantalla de permiso que ya está concedido: recalcular la lista de pasos al entrar.
- Todos los textos van a `strings.xml` con claves `onboarding_dnd_title`, `onboarding_dnd_body`, etc. Ninguna cadena literal en Kotlin (RF-42).

---

## Accesibilidad e i18n

### Accesibilidad (RNF-08) — checklist accionable

- [ ] **Objetivos táctiles ≥ 48 dp** en todo elemento interactivo. Los iconos de 24 dp se envuelven en `Modifier.minimumInteractiveComponentSize()` o `IconButton`.
- [ ] **`ProfileCard`**: la acción por defecto de TalkBack es *activar*, no *abrir*. Usar `Modifier.semantics { role = Role.Switch; stateDescription = if (isActive) "Activo" else "Inactivo" }` sobre el área principal, y `onClickLabel = "Activar perfil Noche"` en el `clickable`.
- [ ] **Acciones personalizadas** en la tarjeta (`customActions`) para Editar, Duplicar, Eliminar: evita que el usuario de lector de pantalla tenga que cazar iconos pequeños.
- [ ] **Sliders de volumen**: `stateDescription` = «Multimedia al 30 por ciento, aproximadamente 5 de 15», `steps` de 5 en 5 para que sea manejable con teclado/switch access, y valor también editable por texto.
- [ ] **`ActiveStateBanner`** marcado como `liveRegion = LiveRegionMode.Polite` para anunciar cambios de perfil sin robar el foco.
- [ ] **Contadores de pausa/activación**: NO anunciar cada segundo. Actualizar el texto visible cada segundo pero limitar la live region a cambios de minuto.
- [ ] **`WeekTimelineGrid`**: la cuadrícula gráfica es `clearAndSetSemantics {}` (invisible para TalkBack) y se ofrece **una lista equivalente accesible** debajo, no una descripción imposible de una rejilla.
- [ ] **Iconos decorativos** con `contentDescription = null`; iconos informativos siempre etiquetados.
- [ ] **Nunca color como único portador de información**: activo lleva icono + texto; conflicto perdido lleva trama además de opacidad; error lleva icono además de rojo.
- [ ] **Contraste AA (4.5:1 texto normal, 3:1 texto grande y componentes)** verificado en claro, oscuro y con color dinámico de varios fondos. El color dinámico puede degradar el contraste: comprobar con `Color.contrastRatio` en tests de UI.
- [ ] **Escalado de fuente hasta 200 %** sin truncar ni solapar: nada de `height` fijo en filas de lista; usar `IntrinsicSize` o `wrapContentHeight`.
- [ ] **Orden de foco** lógico en el editor (identidad → sonido → DND → opciones); `focusGroup()` en secciones plegables.
- [ ] **Diálogos y hojas**: título anunciado, foco inicial en el contenido, cierre con botón atrás y con acción explícita.
- [ ] **`TimePicker`** siempre con alternativa de entrada por teclado (`TimeInput`), obligatorio para usuarios que no pueden manipular el reloj analógico.
- [ ] **Animaciones**: respetar `Settings.Global.ANIMATOR_DURATION_SCALE == 0` y reducir/anular transiciones; nada de parpadeos > 3 Hz.
- [ ] **`testTag`** estable en los elementos que QA necesitará, con `testTagsAsResourceId = true` para pruebas UIAutomator.
- [ ] **Widget y tile**: `contentDescription` en cada botón Glance; el tile expone su estado mediante `subtitle` y `stateDescription` (API 30+).
- [ ] Prueba real con TalkBack activado en los flujos CU-01, CU-04, CU-05 y CU-10 antes de dar por cerrada la v1.

### i18n (RF-42) — checklist accionable

- [ ] **Idioma base del recurso = inglés** (`values/strings.xml`), traducción en `values-es/strings.xml`. Coherente con RNF-11 (código en inglés) y con la distribución internacional. La documentación sigue en español.
- [ ] **Cero cadenas literales en Kotlin**, incluidos widget, tile, notificaciones, canales de notificación, mensajes de log de usuario y textos de accesibilidad. Activar `lint` con `HardcodedText` y `SetTextI18n` como *error*.
- [ ] **Plurales con `<plurals>`**, nunca `"$n perfiles"`. Casos: perfiles, franjas, minutos restantes, entradas de historial, días seleccionados.
- [ ] **Argumentos posicionales** (`%1$s`, `%2$d`) siempre, porque el orden cambia entre idiomas.
- [ ] **Horas y fechas con `DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)`** — nunca `"HH:mm"` fijo: el inglés usa AM/PM. El widget y el tile también.
- [ ] **Días de la semana** desde `DayOfWeek.getDisplayName(TextStyle.NARROW/FULL, locale)`, no de un array propio. El **primer día de la semana** viene de `WeekFields.of(locale).firstDayOfWeek` — lunes en España, domingo en EE. UU.; la línea de tiempo debe respetarlo.
- [ ] **Duraciones** («quedan 47 min», «1 h 30 min») mediante un formateador centralizado con plurales, no concatenación.
- [ ] **`localeConfig`** declarado en el manifiesto y **selector de idioma por app** (Android 13+ `ACTION_APP_LOCALE_SETTINGS`; `AppCompatDelegate.setApplicationLocales` por debajo).
- [ ] **Expansión de texto**: el español es ~20 % más largo que el inglés. Previsualizar los textos críticos (botones de onboarding, chips, tile) en ambos idiomas. El subtítulo del QS tile se trunca a ~20 caracteres: prever nombres cortos.
- [ ] **`start`/`end` en lugar de `left`/`right`** en todos los paddings y alineaciones, aunque es/en sean LTR — evita rehacer el trabajo si se añade árabe o hebreo.
- [ ] **Nombres de plantillas traducibles** pero el `templateId` estable e inglés (`night`, `work`, `meeting`…), para que la exportación JSON sea portable entre idiomas.
- [ ] **La exportación JSON no se traduce**: claves en inglés, valores de enum en inglés. Solo la UI se traduce.
- [ ] **Números**: porcentajes con `NumberFormat.getPercentInstance(locale)`.
- [ ] Revisión de traducción por hablante nativo del texto de onboarding en inglés: es el texto que decide si el usuario concede el permiso.

---

## Errores de UX que debemos evitar explícitamente

Cada punto está atado a una queja concreta del documento 00 y a una decisión de diseño concreta de este informe.

| # | Error de la competencia | Fuente | Decisión de diseño en SonoRitmo | Prueba de aceptación |
|---|---|---|---|---|
| **UX-01** | **Pulsar el perfil abre la edición en vez de activarlo** | 00 §3.3 | El toque en el cuerpo de `ProfileCard` **activa** el perfil de inmediato con snackbar de deshacer. La edición requiere un icono de lápiz explícito o el menú de desbordamiento. La pulsación larga activa con duración. | Un usuario nuevo activa un perfil en un solo toque, sin pasar por ningún formulario |
| **UX-02** | **No hay indicador visual del perfil activo** | 00 §3.3, RF-08 | Triple redundancia (contenedor de color + chip textual «Activo» + `stateDescription`) en la tarjeta, más `ActiveStateBanner` persistente en la pantalla de inicio, más QS tile, más widget. El estado activo se ve en cuatro superficies. | Con TalkBack y en escala de grises, el perfil activo sigue siendo identificable |
| **UX-03** | **Obligación de definir hora de fin** | 00 §3.3 | `Switch` «Sin hora de fin» en el editor de franja + duración «Indefinida» en la activación manual. **Requiere cambiar `Schedule.endTime` a `LocalTime?` en §4.5 del doc 01** — petición formal al responsable de dominio | Se puede crear una franja «desde las 23:00, sin hora de fin» y guardarla |
| **UX-04** | **Onboarding de permisos confuso** | 00 §5, RF-43 | Un permiso por pantalla, explicando la consecuencia y no la API, con estado de denegación siempre resuelto (nunca callejón sin salida), reevaluación en `ON_RESUME`, y un panel de diagnóstico permanente como segunda oportunidad | Tras denegar los tres permisos, la app sigue siendo usable y explica exactamente qué no funcionará |
| **UX-05** | **Límite artificial de incremento de volumen** | 00 §3.3 | Slider de 0–100 % continuo con entrada numérica directa; la conversión a pasos del dispositivo se muestra como información («≈ 7 de 15»), nunca como restricción | Se puede fijar cualquier porcentaje entre 0 y 100 en cualquier stream |
| **UX-06** | **El usuario no sabe por qué cambió su volumen** | 00 §5.6, D6 | Historial en lenguaje natural con la regla causante, accesible en dos toques desde el inicio, y `SheetConflictExplain` que explica quién gana un solape y por qué | CU-07 se resuelve sin salir de la app y sin jerga técnica |
| **UX-07** | **La configuración se pierde** | 00 §3.1, §5.5 | Pantalla de copia de seguridad de primer nivel, no escondida; Android Backup activado; confirmación explícita antes de una importación destructiva | Ida y vuelta JSON sin pérdida, verificable desde la UI |
| **UX-08** | **Fallos silenciosos («a veces decide no funcionar»)** | 00 §3.1, §5.1 | Ningún fallo es silencioso: banner en el inicio, entrada roja en el historial con acción «Arreglar», estado del tile a `UNAVAILABLE`, widget mostrando el problema | Si se revoca `ACCESS_NOTIFICATION_POLICY`, las cuatro superficies lo reflejan sin abrir la app |
| **UX-09** | **UI anticuada / muros de opciones** (Orion.Soft, Tasker) | 00 §3.4, §3.5 | Editor estratificado con secciones plegables: 4 sliders y un selector visibles por defecto, el resto plegado. Plantillas como camino por defecto de creación | CU-01: crear un perfil desde plantilla en menos de 30 s medidos con cronómetro |
| **UX-10** | **Diálogos de permiso al arrancar sin contexto** | 00 §4.2, patrón general | Ningún permiso se pide antes de explicarlo, y `POST_NOTIFICATIONS` se pide solo cuando aporta valor. El permiso de DND se puede posponer y el perfil se guarda igual | No aparece ningún diálogo del sistema en el primer segundo de uso |
| **UX-11** | **Acciones destructivas fáciles de disparar** | riesgo propio | Sin deslizar-para-borrar. Borrado siempre con diálogo de confirmación nombrando el perfil. Importación destructiva con doble confirmación | No se puede perder un perfil con un gesto accidental |
| **UX-12** | **Cambios sin retorno** | 00 §5.7, D7 | «Deshacer» de 5 s en toda activación manual; «Vista previa» de 10 s con reversión automática en el editor; restauración de snapshot al terminar la franja | Toda activación es reversible con un toque durante 5 s |

---

### Peticiones formales a otros responsables

1. **Dominio/Datos — `Schedule.endTime` debe ser nullable.** Doc 01 §4.5. Semántica de `null`: la franja aplica el perfil al llegar `startTime` y no programa fin propio; el perfil se mantiene hasta que otra franja de igual o mayor prioridad tome el control o hasta activación manual. Afecta a §7.3 (paso 6: no hay «fin de franja» que dispare restauración) y a §7.4 (el cálculo de la siguiente transición ignora estas franjas como origen de eventos de fin). Sin este cambio, la UI no puede corregir la queja de 00 §3.3.
2. **Dominio — exponer la explicación del conflicto.** El resolutor debe devolver no solo el ganador sino la lista de candidatos con el motivo de descarte (prioridad / duración / id), para alimentar `SheetConflictExplain` y `OverlapWarningUi`. Es un cambio de tipo de retorno, no de algoritmo.
3. **Audio — API de detección de acoplamiento tono/notificación** consultable desde la UI de forma síncrona y cacheada (RF-10), y **API de vista previa** que aplique y revierta un perfil en N segundos (RF-09).
4. **Scheduler — exponer `SchedulerHealth`** (próxima transición, existencia de alarma pendiente, última reconciliación, modo exacto o degradado) como `Flow`, para el panel de diagnóstico y el banner de estado.
5. **Datos — un único `ActiveStateRepository`** como fuente de verdad para app, QS tile y widget Glance, con un método de refresco de superficies. Sin esto, las tres superficies se desincronizarán.

agentId: a7c95c542355ebd8d (use SendMessage with to: 'a7c95c542355ebd8d', summary: '<5-10 word recap>' to continue this agent)
<usage>subagent_tokens: 66112
tool_uses: 3
duration_ms: 390315</usage>