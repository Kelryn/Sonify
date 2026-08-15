I have verified the environment empirically. Here is the report.

---

# Informe de QA y Estrategia de Calidad — RitMute v1.0

**Responsable:** QA y Calidad de Sistemas
**Fecha:** 12 de agosto de 2026
**Entradas:** `/home/claude/soundprofiles/docs/00-INVESTIGACION.md`, `/home/claude/soundprofiles/docs/01-ESPECIFICACIONES.md`
**Estado del repositorio en el momento de este análisis:** solo `docs/`, sin código, sin commits, sin remoto configurado.

---

## Veredicto general sobre la testeabilidad de las especificaciones

**Veredicto: testeable en su núcleo, no testeable en sus bordes. Aprobado con reservas y con trabajo de reescritura obligatorio antes de empezar a codificar.**

Lo bueno, y es mucho:

- La decisión arquitectónica de §7.1 —que `:core:model` y `:core:domain` **no dependan del SDK de Android**— es la mejor decisión de calidad del documento. Es lo que convierte la pieza más crítica del sistema (el resolutor de conflictos de §7.3 y el cálculo de la siguiente transición de §7.4) en código puro, determinista y verificable **incluso en este contenedor sin Android SDK**. He confirmado experimentalmente que esto funciona.
- El algoritmo de §7.3 está escrito como una secuencia ordenada de pasos con desempates explícitos. Eso es una especificación casi ejecutable: se traduce a tests casi línea por línea.
- La decisión de §7.4 de programar **una única alarma para la siguiente transición** simplifica enormemente el modelo de fallos, y a la vez concentra el riesgo: es un punto único de fallo silencioso que exige tests basados en propiedades, no solo ejemplos.
- Modelar el volumen en porcentaje (§4.2) elimina una clase entera de bugs de fragmentación e introduce otra (redondeo a 0 pasos) que es perfectamente testeable.
- RNF-05 (sin `INTERNET`) es el requisito no funcional mejor escrito del documento: es binario, automatizable en CI en una línea, y es además un argumento comercial. Ojalá todos fueran así.

Lo malo, y es serio:

1. **Los requisitos no funcionales están escritos como aspiraciones, no como criterios de aceptación.** RNF-01, RNF-02, RNF-03 y RNF-08 tienen números pero no tienen **método de medida, muestra, ni condiciones de contorno**. Un número sin protocolo de medida no es un criterio, es un deseo. Cuatro de doce RNF no se pueden declarar cumplidos ni incumplidos tal y como están.
2. **Hay al menos cuatro ambigüedades en el núcleo del dominio que harán que dos desarrolladores escriban dos implementaciones distintas y ambas "cumplan" la especificación.** El caso `startTime == endTime`, la disyuntiva "restaurar snapshot **o** perfil por defecto" de RF-15, el desempate incompleto de §7.3.4, y el "1 000 entradas **o** 30 días" de RF-38. Estas ambigüedades están precisamente en el camino crítico del producto. **Hay que cerrarlas antes de escribir la primera línea de `:core:domain`, no después.**
3. **RNF-06 pide ≥80 % de cobertura en `:core:scheduler`, pero `:core:scheduler` es un módulo Android** (`AlarmManager`, `WorkManager`, `BroadcastReceiver`). Tal y como está modularizado, ese objetivo obliga a Robolectric para todo y da una cobertura poco significativa. Es una contradicción entre §7.1 y RNF-06 que tiene una solución arquitectónica limpia (ver más abajo, punto 4 de la sección siguiente).
4. **Contradicción de alcance:** §2.1 lista "excepciones por fecha" **dentro** del alcance de la v1, mientras RF-19 la marca `Could (v1.1)` y `Schedule.skipHolidays` está anotado `// v1.1`. QA no puede saber si esa funcionalidad entra en la Definición de Hecho. Hay que resolverlo: mi recomendación es **fuera de v1**, y corregir §2.1.
5. **La promesa central del producto no tiene test.** "Se dispara. Siempre." es la promesa nº 1 de §1, y es exactamente lo que la competencia falla según §5 de la investigación. Sin embargo, no existe ningún requisito que defina cómo se demuestra esa fiabilidad en condiciones reales (Doze, pantalla apagada, OEM agresivo, 72 horas). RNF-01 mide precisión, no fiabilidad. **Son cosas distintas: una alarma que dispara con 3 segundos de desviación el 60 % de las veces cumple RNF-01 y destruye el producto.**

Consecuencia práctica: el riesgo de calidad de este proyecto **no está en el código puro** —ese lo vamos a cubrir bien y barato—. Está en el kilómetro final: la interacción con `AudioManager`/`NotificationManager` en dispositivos reales y la supervivencia de las alarmas en OEM agresivos. Y ese kilómetro final **no se puede automatizar ni en este contenedor ni en GitHub Actions**. Lo digo ahora y lo repito en la sección de riesgos asumidos.

---

## Requisitos no verificables tal y como están escritos

### 1. RNF-01 — Precisión de disparo: "≤ 60 s de desviación con permiso exacto concedido"

**Por qué no es verificable:** no define muestra, ni percentil, ni condiciones del dispositivo. ¿60 s en el peor caso o de media? ¿Con la pantalla encendida o en Doze profundo tras 6 horas? ¿En qué dispositivo? Además mide *precisión* pero no *fiabilidad*: un disparo que no ocurre nunca tiene desviación indefinida, no "mayor de 60 s", así que técnicamente no viola el requisito. Es un agujero lógico.

**Reformulación medible:**
> **RNF-01a (precisión).** Con `canScheduleExactAlarms() == true`, en un Pixel de referencia con Android 16 y la pantalla apagada durante ≥ 30 min antes del disparo, sobre una muestra de ≥ 50 transiciones programadas: el percentil 95 de `|t_aplicado − t_programado|` ≤ 60 s y el máximo ≤ 180 s.
> **RNF-01b (fiabilidad).** En la misma muestra, la tasa de transiciones aplicadas (o registradas como `SKIP` justificado) es ≥ 99 %. Toda transición no aplicada y no registrada cuenta como fallo.
> **RNF-01c (degradado).** Sin permiso exacto, con `setWindow(5 min)`, el percentil 95 ≤ 15 min y la tasa de aplicación ≥ 95 % en 24 h, contando la reconciliación de `WorkManager` como aplicación válida.
> **Medición:** el propio `ActivityLogEntry` (RF-27) registra `t_programado` y `t_aplicado`; un script exporta el historial y calcula los percentiles. El requisito se autoverifica con la funcionalidad D6, que ya está en alcance.

### 2. RNF-02 — Consumo: "impacto de batería < 1 %/día"

**Por qué no es verificable:** no existe forma práctica de aislar el 1 % de batería atribuible a una app sin un protocolo estricto, y el resultado depende del resto del sistema. Es un número que nadie va a medir y que, por tanto, nunca bloqueará una release: un criterio que no bloquea no es un criterio.

**Reformulación medible:**
> **RNF-02.** Verificable por *proxies* auditables en lugar de por porcentaje de batería:
> (a) el manifiesto **no** declara ningún `foregroundServiceType` de ejecución permanente y no hay `startForeground()` fuera de una transición puntual (< 30 s) — verificable por inspección estática del manifiesto y del código en CI;
> (b) el número de despertares del dispositivo atribuibles a la app en 24 h de reposo es ≤ 50 (48 ejecuciones del reconciliador de RF-31 + margen), medido con `adb shell dumpsys batterystats --charged <pkg>` y contrastado en Battery Historian;
> (c) ningún `WakeLock` se mantiene más de 10 s, verificable en `batterystats`.
> **Cuándo:** una vez por release candidate, manual, con acta en `docs/`.

### 3. RNF-03 — Arranque en frío: "< 500 ms hasta primer frame en gama media"

**Por qué no es verificable:** "gama media" no es un dispositivo. Y el número es, siendo francos, **irreal**: una app Compose + Hilt + Room rara vez baja de 500 ms de TTID en gama media en arranque en frío real. Un requisito que se va a incumplir sistemáticamente se acaba ignorando, y contamina la credibilidad del resto.

**Reformulación medible:**
> **RNF-03.** TTID (*time to initial display*) medido con Macrobenchmark, `StartupMode.COLD`, 10 iteraciones, descartando la primera: mediana ≤ 800 ms y P95 ≤ 1 200 ms en el dispositivo de referencia de gama media (definir uno concreto: p. ej. Pixel 6a con Android 16, o el AVD `Pixel 6 API 34` con perfil de CPU limitado si se ejecuta en CI). Se registra el valor en cada release para detectar regresiones; la regresión que bloquea es un **empeoramiento > 20 % respecto a la release anterior**, no el valor absoluto.

### 4. RNF-06 — Cobertura: "≥ 80 % en `:core:domain` y `:core:scheduler`"

**Por qué no es verificable (bien):** (a) no dice cobertura *de qué* —líneas, ramas, instrucciones— y las tres dan números muy distintos en Kotlin; (b) no dice qué se excluye (código generado por Room/Hilt/serialización, `data class` sintéticas), y sin exclusiones el porcentaje mide ruido; (c) **`:core:scheduler` es un módulo Android**, en contradicción con §7.1, así que su cobertura JVM real será baja o artificial vía Robolectric.

**Reformulación medible, con cambio arquitectónico asociado:**
> **RNF-06.** JaCoCo sobre `testDebugUnitTest`, excluyendo clases generadas (`*_Factory`, `*_Impl`, `*$$serializer`, `*ComposableSingletons*`, `Hilt_*`, `*_HiltModules*`):
> — `:core:domain` ≥ **90 % de ramas** y ≥ 90 % de líneas (es código puro, determinista y crítico: 80 % es poco ambicioso aquí);
> — `:core:model` ≥ 80 % de líneas (validaciones e invariantes);
> — **nuevo `:core:scheduler-policy`** (Kotlin puro, sin Android): contiene `NextTransitionCalculator` y la política de degradación exacta→ventana → ≥ 90 % de ramas;
> — `:core:scheduler` (adaptadores Android: receivers, `AlarmManager`, `WorkManager`) queda **exento de umbral numérico** y se cubre con tests Robolectric/instrumentados de comportamiento, no de porcentaje.
> El *build* falla si cualquier umbral no se cumple (`jacocoTestCoverageVerification`).

Este cambio es la recomendación de calidad más importante de todo el informe: **saca la lógica de "cuándo hay que despertar" del módulo Android y ponla en Kotlin puro.** Con eso, la pieza más peligrosa del sistema se puede probar exhaustivamente y —dato clave— **se puede verificar localmente en este contenedor sin Android SDK**.

### 5. RNF-08 — Accesibilidad: "Contraste AA, targets ≥ 48 dp, todo etiquetado para TalkBack"

**Por qué no es verificable:** "todo etiquetado" es un universal no comprobable automáticamente al 100 % (un `contentDescription` presente pero inútil pasa cualquier automatismo), y "contraste AA" depende del tema dinámico de Material You, que **cambia con el fondo de pantalla del usuario** y por tanto no tiene un valor fijo que auditar.

**Reformulación medible:**
> **RNF-08a.** El Accessibility Test Framework (vía `AccessibilityChecks.enable()` en los tests instrumentados) no reporta **ningún** hallazgo de severidad `ERROR` en los 8 flujos principales. El *build* falla si aparece alguno.
> **RNF-08b.** Todo nodo Compose accionable expone `contentDescription` o `text` no vacío, verificado por un test que recorre el árbol de semántica de cada pantalla (`onAllNodes(hasClickAction())` → aserción sobre semántica).
> **RNF-08c.** Contraste AA verificado **sobre los temas claro y oscuro estáticos** (los de `:core:designsystem`), no sobre el dinámico; para el dinámico se documenta que se delega en los tonos garantizados por Material 3 y se comprueba manualmente con tres fondos de pantalla de prueba.
> **RNF-08d.** Auditoría manual con TalkBack de los 10 casos de uso CU-01…CU-10, una vez por release, con checklist firmada.

### 6. RNF-09 — Robustez: "ningún crash no controlado ante permisos denegados o SecurityException"

**Por qué no es verificable:** es una afirmación universal negativa sobre un espacio de estados infinito. No se puede demostrar; solo se puede refutar.

**Reformulación medible:**
> **RNF-09.** Existe una **matriz de denegación** con las 16 combinaciones de {`ACCESS_NOTIFICATION_POLICY` ✓/✗} × {`SCHEDULE_EXACT_ALARM` ✓/✗} × {`POST_NOTIFICATIONS` ✓/✗} × {`READ_PHONE_STATE` ✓/✗}. Para cada combinación existe un test instrumentado que ejecuta "aplicar perfil" y "programar franja" y verifica: (a) no hay excepción no capturada, (b) se escribe una entrada `ERROR` o `PERMISSION` en el historial con `success=false` y `reason` no vacío, (c) la UI muestra una acción correctiva. Adicionalmente, un test unitario con un doble de `NotificationManager` que **lanza `SecurityException` deliberadamente** en cada método verifica que el motor lo captura y lo registra.

### 7. §1 y CU-01 — "Los cinco casos de uso más comunes se configuran en menos de 30 segundos"

**Por qué no es verificable:** ¿quién lo configura? ¿un desarrollador que conoce la app o un usuario nuevo? Sin protocolo, es marketing.

**Reformulación medible:**
> **RU-01.** Test de usabilidad moderado con **5 participantes externos** que nunca han visto la app. Tarea: "haz que tu móvil esté en silencio de 23:00 a 07:00 con la alarma al máximo", partiendo de la app recién instalada y con los permisos ya concedidos. Criterio: **4 de 5 completan la tarea sin ayuda en ≤ 60 s**, medido desde el primer toque. Cronometrado y grabado. Si no se puede organizar antes de la v1, el requisito se degrada explícitamente a: "existen las 6 plantillas de RF-07 y cada una queda operativa con ≤ 3 toques desde la pantalla principal", que sí es verificable con un test de UI contando interacciones.

### 8. RF-15 — "Al terminar la franja, restaurar el estado anterior **o** aplicar el perfil por defecto"

**Por qué no es verificable:** la disyuntiva no está resuelta. Dos implementaciones opuestas cumplen literalmente el requisito. Es el peor tipo de ambigüedad: silenciosa, en el camino crítico, y con consecuencias visibles para el usuario (P2 se puede quedar sin alarma por esto).

**Reformulación medible:**
> **RF-15.** Al terminar una franja, el motor decide de forma determinista:
> 1. Si existe otra franja activa en ese instante → se aplica esa (no hay restauración).
> 2. Si no la hay y `ProfileOptions.restoreOnExit == true` **y** existe un `AudioSnapshot` válido tomado al entrar en esa franja → se restaura el snapshot.
> 3. Si no la hay y `restoreOnExit == false` **o** el snapshot no existe o está corrupto → se aplica el perfil marcado como *por defecto* si el usuario ha definido uno; si no ha definido ninguno, **no se hace nada** y se registra una entrada `SKIP` con `reason = "no_restore_target"`.
> Un snapshot se considera inválido si tiene más de 48 h o si su `deviceFingerprint` (número de pasos por stream) no coincide con el actual.

### 9. §4.5 — "`endTime`: si `<= startTime`, cruza medianoche"

**Por qué no es verificable:** el caso `startTime == endTime` está incluido en `<=` y es **intrínsecamente ambiguo**: puede significar franja de duración cero o franja de 24 horas. Es el caso límite exacto que revientan todas las apps de la competencia.

**Reformulación medible:**
> **§4.5.** `startTime == endTime` significa **franja de 24 horas** que empieza en `startTime` del día seleccionado. Se documenta en la UI con el literal "todo el día". La duración de la franja, usada para el desempate de §7.3.4, se define como:
> `duration = if (end > start) end − start else 24h − (start − end)`, y para `start == end` es exactamente `24h`.
> La validación rechaza franjas con `daysOfWeek` vacío (ya invariante) y no rechaza `start == end`.

### 10. §7.3, paso 4 — Desempate: "prioridad desc, luego franja más corta, luego `profileId` asc"

**Por qué no es verificable:** el desempate **no es total**. Dos franjas *del mismo perfil* (mismo `profileId`), con la misma prioridad y la misma duración, quedan empatadas y el resultado depende del orden de iteración de la base de datos. §7.3 promete determinismo y no lo entrega. También queda sin definir la duración para franjas que cruzan medianoche (resuelto en el punto 9).

**Reformulación medible:**
> **§7.3.4.** Orden total: `priority` desc → `duration` asc → `profileId` asc → **`scheduleId` asc**. Con esto la relación de orden es total y el resultado es único para cualquier conjunto de franjas. Se verifica con un test basado en propiedades: para cualquier permutación aleatoria de la lista de entrada, el ganador es el mismo (invariante de conmutatividad).

### 11. RF-31 — "`WorkManager` periódico (cada 30 min)"

**Por qué no es verificable:** confunde **configuración** con **comportamiento**. Se puede *solicitar* una periodicidad de 30 min, pero el sistema no la garantiza en Doze; puede pasar a ejecutarse cada varias horas. Verificar "cada 30 min" fallaría siempre y no por culpa de la app.

**Reformulación medible:**
> **RF-31a (configuración, automatizable).** Existe un `PeriodicWorkRequest` de 30 min, con `ExistingPeriodicWorkPolicy.KEEP`, sin restricciones de red, encolado en el arranque de la app. Verificable con `WorkManagerTestInitHelper` + `TestDriver`.
> **RF-31b (comportamiento, automatizable).** Al ejecutar el worker, si el perfil aplicado difiere del que el resolutor dice que debería estar activo en `now`, el worker lo corrige y escribe una entrada de historial con `reason = "reconcile"`. Verificable en test unitario del caso de uso, sin `WorkManager`.
> **RF-31c (campo).** En 24 h de dispositivo en reposo, el intervalo máximo observado entre dos reconciliaciones es ≤ 4 h. Manual, con acta.

### 12. RF-38 — "Historial limitado a 1 000 entradas **o** 30 días"

**Por qué no es verificable:** el "o" es ambiguo (¿el más restrictivo? ¿el más permisivo?), y no dice cuándo se purga.

**Reformulación medible:**
> **RF-38.** Se aplica **el más restrictivo de los dos**: tras cada escritura se eliminan las entradas con `timestamp < now − 30 días` y, si aún quedan más de 1 000, se eliminan las más antiguas hasta dejar exactamente 1 000. La purga se ejecuta en la misma transacción que la inserción. Se verifica con un test de DAO que inserta 1 500 entradas, la mitad con fecha antigua, y comprueba el estado final exacto.

### 13. RF-10 — "Detección automática de acoplamiento tono/notificación y ajuste de la UI"

**Por qué no es verificable en CI:** el acoplamiento es un comportamiento **del firmware del dispositivo**. Un emulador AOSP no reproduce el comportamiento de One UI o MIUI. No hay forma de verificar la detección real en GitHub Actions.

**Reformulación medible:**
> **RF-10a (lógica, automatizable).** La detección se implementa como una función pura `detectCoupling(ringBefore, notifBefore, ringAfter, notifAfter): CouplingState` sobre las lecturas de una sonda que modifica `STREAM_RING` y observa `STREAM_NOTIFICATION`. Esa función se testea al 100 % en `:core:domain` con todas las combinaciones, incluida la indeterminada (`UNKNOWN` cuando ambos ya coincidían antes de la sonda).
> **RF-10b (integración, automatizable).** Test instrumentado que verifica que, dado `CouplingState.COUPLED` inyectado, la UI del editor de perfiles muestra un único control y el aviso explicativo.
> **RF-10c (campo, manual).** Verificación en matriz de al menos 4 dispositivos reales (un Samsung One UI, un Xiaomi HyperOS, un Pixel AOSP y uno más), con acta.
> **Importante:** la sonda no debe ejecutarse durante una llamada ni con DND activo sin permiso, y debe restaurar los valores originales; eso sí es testeable.

### 14. RF-23 — "Transición gradual (rampa lineal en N segundos)"

**Por qué no es verificable:** no define el número de pasos intermedios, ni la tolerancia temporal, ni qué ocurre si llega otra transición a mitad de la rampa (caso real y probable), ni si la rampa sobrevive a que la app muera.

**Reformulación medible:**
> **RF-23.** La rampa se implementa como una función pura `rampSteps(from: Int, to: Int, seconds: Int): List<Pair<Long, Int>>` (offset en ms → valor en pasos) en `:core:domain`, con estas propiedades verificables: el primer elemento tiene offset 0 y el valor `from`; el último tiene offset ≤ `seconds*1000` y el valor exactamente `to`; la secuencia es monótona; nunca hay dos elementos consecutivos con el mismo valor; para `seconds == 0` devuelve un único elemento con `to`. **Una rampa en curso se cancela inmediatamente si llega una nueva aplicación**, y el nuevo destino se aplica desde el valor actual. La tolerancia de ejecución es ±500 ms por paso y no se verifica en CI.

### 15. RNF-04 — "Tamaño del APK < 8 MB"

**Por qué no es verificable como está:** no dice qué APK. El *debug* sin minificar de una app Compose + Hilt + Room superará los 8 MB con casi total seguridad, y el criterio de aceptación nº 1 solo exige `assembleDebug`.

**Reformulación medible:**
> **RNF-04.** El APK **universal de release, minificado y con recursos optimizados** (R8 completo, `shrinkResources`), sin firmar, mide < 8 MB. Se verifica en CI comparando `stat -c%s` contra el umbral; el *build* falla si se supera. El APK de debug queda explícitamente fuera del requisito.

### 16. RNF-12 — "El proyecto compila con `./gradlew assembleDebug` en un runner limpio"

**Por qué es insuficiente:** es verificable hoy, pero no garantiza reproducibilidad **en el tiempo**: sin fijar versiones, el mismo commit puede dejar de compilar en tres meses por una dependencia transitiva.

**Reformulación medible:**
> **RNF-12.** (a) `./gradlew assembleDebug` y `assembleRelease` completan en un runner limpio de GitHub Actions; (b) todas las versiones se declaran en `gradle/libs.versions.toml` sin rangos dinámicos ni `+`; (c) existen *dependency verification* y *lockfiles* de Gradle commiteados, y el *build* falla si el resolutor encuentra una versión no bloqueada; (d) el `gradle-wrapper.properties` fija la distribución con `distributionSha256Sum`.

### 17. Contradicción de alcance — Excepciones por fecha

**Problema:** §2.1 la incluye en v1; RF-19 la marca `Could (v1.1)`; `Schedule.skipHolidays` está comentado `// v1.1`.

**Resolución propuesta:** fuera de la v1. Corregir §2.1 eliminando "excepciones por fecha" de la fila de Programación. QA **no** escribirá casos de prueba para esta funcionalidad en la v1, y su ausencia no bloqueará la release.

### 18. Requisitos correctamente escritos (para contraste)

Merecen mención porque son el modelo a seguir: **RNF-05** (sin `INTERNET`, binario y automatizable), **RNF-07** (versiones de SDK, comprobables en el `build.gradle`), **RNF-10** (licencia, comprobable por fichero), **RF-38** una vez desambiguado, y todo el algoritmo de **§7.3** una vez cerrados los dos huecos. El resto de RF de la §5 son verificables como pasa/no pasa mediante los casos de prueba de la sección correspondiente.

---

## Estrategia de test por capas

| Capa | Tipo de test | Herramienta | Dónde se ejecuta | Qué cubre |
|---|---|---|---|---|
| `:core:model` | Unitario puro (JVM) | kotlin.test + JUnit 4/5 | **Local (kotlinc) + CI** | Invariantes del dominio: rangos 0–100 de `VolumeSettings`, `daysOfWeek` no vacío, `transitionSeconds` 0–60, `priority` 0–100, igualdad y copia de `data class` |
| `:core:domain` | Unitario puro + **basado en propiedades** | kotlin.test + JUnit; property-based a mano en local, Kotest/jqwik en CI | **Local (kotlinc) + CI** | **El núcleo**: resolución de conflictos §7.3, pertenencia de instante a franja con cruce de medianoche, cálculo de duración, orden total de desempate, `rampSteps`, conversión %→pasos, `detectCoupling`, políticas de restauración RF-15 |
| `:core:scheduler-policy` *(nuevo, Kotlin puro)* | Unitario + propiedades | kotlin.test + JUnit | **Local (kotlinc) + CI** | `nextTransition(t, schedules)`: la pieza de mayor riesgo del sistema. Invariantes de monotonía y no-omisión |
| `:core:data` — serialización | Unitario puro | kotlinx-serialization + JUnit | **Local (kotlinc, verificado) + CI** | Ida y vuelta JSON de RF-36/37, validación de `schemaVersion`, JSON corrupto, campos desconocidos, migración de formato |
| `:core:data` — Room | DAO + migraciones | Robolectric + `MigrationTestHelper` + `room.schemaLocation` | **CI (job unit)** | Consultas de DAO, purga de historial RF-38, migraciones versionadas RF-39 (cada migración con test de ida) |
| `:core:audio` | Unitario con dobles | MockK / interfaces propias + JUnit | **CI (job unit)**; parte de la lógica pura, local | Traducción a pasos con `getStreamMax/MinVolume` simulados, captura y restauración de `AudioSnapshot`, captura de `SecurityException`, reglas de cortesía (llamada activa, media sonando) |
| `:core:audio` | Instrumentado | AndroidX Test + emulador | **CI (job instrumented)** | Aplicación real sobre `AudioManager`/`NotificationManager` en emulador, `setInterruptionFilter`, `AutomaticZenRule` en API 35+ |
| `:core:scheduler` (adaptadores) | Unitario Android | Robolectric `ShadowAlarmManager`, `WorkManagerTestInitHelper` + `TestDriver` | **CI (job unit)** | Que se programa **una sola** alarma, elección exacta vs `setWindow`, receivers de `BOOT_COMPLETED`/`TIME_SET`/`TIMEZONE_CHANGED`/`MY_PACKAGE_REPLACED`/`ACTION_USER_PRESENT`, encolado del worker |
| `:feature:*` (UI) | UI de Compose | `createComposeRule` + Robolectric (JVM) | **CI (job unit)** | Estados de pantalla, renderizado de listas, indicador de perfil activo RF-08, filtros de historial RF-46 |
| `:feature:*` (UI) | UI instrumentada | `createAndroidComposeRule` + emulador | **CI (job instrumented)** | Navegación, preservación de estado en rotación RF-47, onboarding de permisos RF-43 |
| `:app` (E2E) | Humo end-to-end | AndroidX Test + Gradle Managed Devices | **CI (job instrumented, nightly)** | CU-01…CU-10 como recorridos completos |
| Quick Settings / Widget | Instrumentado | `TileService` test + Glance test | **CI (job instrumented)** | RF-44, RF-45: activar y pausar sin abrir la app |
| Accesibilidad | Automático + manual | Accessibility Test Framework; TalkBack manual | **CI (instrumented) + manual** | RNF-08a/b/c automatizables; RNF-08d manual por release |
| Estático — código | Lint de estilo y bugs | ktlint + detekt | **CI (job static)** | Estilo, complejidad ciclomática, `println`, `!!`, `GlobalScope`, catch genérico |
| Estático — Android | Android Lint | `lintRelease` | **CI (job static)** | `MissingTranslation` (RF-42), `NewApi`, uso de APIs restringidas, accesibilidad estática |
| Estático — arquitectura | Reglas propias | Regla de detekt o test que escanea fuentes | **Local + CI** | **Que `:core:model`, `:core:domain` y `:core:scheduler-policy` no contengan `import android.`** — la invariante que sostiene toda esta estrategia |
| Estático — manifiesto | Aserción sobre el manifiesto fusionado | Script + `aapt2 dump` | **CI (job build)** | RNF-05: ausencia de `INTERNET`; ausencia de permisos no declarados en §8 |
| Cobertura | Métrica | JaCoCo + `jacocoTestCoverageVerification` | **CI (job unit)** | Umbrales de RNF-06 reformulado |
| Tamaño | Métrica | Comparación de bytes del APK de release | **CI (job build)** | RNF-04 reformulado |
| Rendimiento | Benchmark | Macrobenchmark | **CI (nightly, emulador) — informativo** | RNF-03; bloquea solo por regresión relativa |
| Fiabilidad de campo | Manual, larga duración | Dispositivos reales + export del historial | **Manual, fuera de CI** | **RNF-01a/b/c y RF-34: la promesa nº 1 del producto.** No automatizable |
| Batería / OEM | Manual | `batterystats`, Battery Historian, matriz de fabricantes | **Manual, fuera de CI** | RNF-02, RF-34 |

**Reparto aproximado del esfuerzo:** 60 % en las tres primeras filas (Kotlin puro, barato, rápido, alto valor), 25 % en CI Android, 15 % en manual de campo. La pirámide está deliberadamente inclinada hacia abajo porque es donde el entorno nos permite ser eficientes.

---

## Plan de verificación local sin Android SDK

He verificado experimentalmente qué se puede hacer en este contenedor. Los resultados son mejores de lo esperado.

### Inventario real del entorno (comprobado, no supuesto)

| Recurso | Ruta | Estado |
|---|---|---|
| Compilador Kotlin 2.1.20 | `/opt/kotlinc/bin/kotlinc` | ✅ funciona (JRE 21.0.10) |
| Aserciones `kotlin.test` | `/opt/kotlinc/lib/kotlin-test.jar` | ✅ **funcionan incluso sin JUnit**, dentro de un `main()` |
| Puente kotlin-test ↔ JUnit | `/opt/kotlinc/lib/kotlin-test-junit.jar` | ✅ presente |
| **JUnit 4.13.2 + hamcrest 1.3** | `/opt/gradle-8.14.3/lib/` | ✅ **ejecuté `@Test` reales con `org.junit.runner.JUnitCore`: `OK (2 tests)`** |
| **kotlinx-serialization** core + json 1.6.2 | `/opt/gradle-8.14.3/lib/` | ✅ **ida y vuelta JSON verificada** con `-Xplugin=.../kotlin-serialization-compiler-plugin.jar` |
| kotlinx-coroutines-core | `/opt/kotlinc/lib/kotlinx-coroutines-core-jvm.jar` | ✅ presente (sin `coroutines-test`: sin tiempo virtual, usar `runBlocking`) |
| `java.time` | JDK 21 | ✅ y **relevante**: con `minSdk 26`, `java.time` está en Android nativamente, sin *desugaring* |
| Gradle 8.14.3 | `/opt/gradle/bin/gradle` | ⚠️ presente pero **inútil aquí** |
| Kotlin Gradle Plugin / AGP | — | ❌ no están en caché |
| maven.google.com | — | ❌ inalcanzable (código de respuesta `000`) |
| repo1.maven.org (Maven Central) | — | ❌ **también inalcanzable** (`000`) |
| Android SDK / `android.jar` | — | ❌ inexistente, `ANDROID_HOME` vacío |

**Conclusión operativa nº 1:** no solo falta el SDK de Android; **falta Maven Central**. Por tanto **Gradle no sirve para nada en local**, ni siquiera para un módulo JVM puro, porque no puede descargar el Kotlin Gradle Plugin. Toda la verificación local debe hacerse **invocando `kotlinc` directamente**, con las bibliotecas que ya están en disco. Cualquier plan que asuma "pues corro `gradle test` en los módulos puros" es inviable y hay que descartarlo desde ya.

**Conclusión operativa nº 2 (decisión de diseño que QA solicita formalmente):** dado que `minSdk = 26` incluye `java.time`, **`:core:model` y `:core:domain` deben usar `java.time` (`LocalTime`, `DayOfWeek`, `Instant`, `ZoneId`) y no `kotlinx-datetime`**. Motivo de calidad: `kotlinx-datetime` no está disponible offline, lo que dejaría el núcleo del dominio **sin verificación local**. Con `java.time`, el 100 % del núcleo se compila y se prueba en este contenedor.

### Qué SÍ se puede comprobar en local

1. **Compilación completa de `:core:model`, `:core:domain` y `:core:scheduler-policy`**, incluidos errores de exhaustividad de `when` sobre `sealed` (verificado: el compilador los detecta), tipos nulos, y firmas.
2. **Ejecución de la suite de tests unitarios real de esos módulos** con JUnit 4 (verificado funcionando). Esto cubre **la totalidad del algoritmo §7.3, el cálculo de la siguiente transición §7.4, el cruce de medianoche, las prioridades, la conversión porcentaje→pasos, las rampas y las políticas de restauración**. Es decir: **entre el 60 % y el 70 % del riesgo lógico del producto se puede eliminar sin salir de este contenedor.**
3. **Ida y vuelta de import/export JSON (RF-36/RF-37)** con kotlinx-serialization, incluidos JSON corruptos, versiones de esquema incompatibles y campos desconocidos (verificado).
4. **Tests basados en propiedades escritos a mano** (bucles con `kotlin.random.Random` semilla fija) sobre el resolutor: no hace falta ninguna biblioteca externa y son enormemente eficaces para el cálculo de transiciones.
5. **Verificación de la invariante arquitectónica**: un `grep` sobre las fuentes de los módulos puros que falla si aparece `import android.`. Es la barrera que impide que alguien "solo por un momentito" meta una dependencia de Android en el núcleo y nos deje ciegos en local.
6. **Disciplina del compilador replicando los flags de CI**: verifiqué que `-Werror` y `-Xexplicit-api=strict` funcionan. Deben usarse en local **con los mismos valores que en CI** para que el verde local signifique algo.

### Qué NO se puede comprobar en local (y no hay que fingir que sí)

- Nada que toque `android.jar`: `AudioManager`, `NotificationManager`, `AlarmManager`, `AudioSnapshot` real, receivers, `AutomaticZenRule`.
- Room: entidades, DAO, migraciones (RF-39). Requiere KSP y artefactos no descargables.
- Hilt: grafo de DI, que solo falla en tiempo de compilación con el procesador.
- Jetpack Compose: aunque `compose-compiler-plugin.jar` está en `/opt/kotlinc/lib`, **falta todo el runtime de Compose**, así que no compila nada de UI.
- Robolectric, tests instrumentados, emuladores.
- Cobertura JaCoCo (no hay jar disponible), detekt, ktlint, Android Lint.
- El propio RNF-12 (`./gradlew assembleDebug`): **solo demostrable en CI**.

### Procedimiento concreto propuesto

Un script `scripts/verify-local.sh` en el repositorio, que es la **puerta previa a cada push** y que no requiere red:

1. Falla si `:core:model`, `:core:domain` o `:core:scheduler-policy` contienen `import android.`.
2. Compila las fuentes de esos tres módulos con `kotlinc`, con `-Werror`, `-Xexplicit-api=strict` y `-jvm-target` idéntico al de CI, produciendo `build/local/main`.
3. Compila las fuentes de test de esos módulos contra JUnit 4 (`/opt/gradle-8.14.3/lib/junit-4.13.2.jar`), `kotlin-test.jar` y `kotlin-test-junit.jar`.
4. Ejecuta `org.junit.runner.JUnitCore` sobre la lista de clases de test descubiertas por nombre (`*Test.class`).
5. Compila y ejecuta aparte el módulo de serialización con el plugin de kotlinx-serialization y los jars 1.6.2, para la ida y vuelta JSON.
6. Devuelve código de salida distinto de cero a la primera rojo.

**Advertencia de calidad que debe quedar escrita en el propio script:** las versiones de biblioteca de este entorno (kotlinx-serialization 1.6.2, JUnit 4.13.2) **no son las que se usarán en el proyecto**; sirven para validar *lógica*, no para fijar versiones. El `libs.versions.toml` manda, y la autoridad última sobre "compila y pasa" es **siempre GitHub Actions**. El verde local es un filtro rápido, no una garantía.

---

## Diseño del pipeline de CI en GitHub Actions

**Principio rector:** CI es el **entorno de compilación autoritativo** (así lo reconoce ya §9 del documento de especificaciones). Si CI está rojo, la rama está rota, sin excepciones ni "es que en mi máquina...".

### Fichero `.github/workflows/ci.yml`

Disparadores: `push` a `main`, `pull_request` a `main`, `workflow_dispatch`. Un `concurrency` group por rama con `cancel-in-progress: true` para no quemar minutos. Un `nightly.yml` aparte con `schedule` para lo caro.

**Configuración común a todos los jobs:** `actions/checkout@v4`; `actions/setup-java@v4` con `distribution: temurin`, `java-version: 21`, `cache: gradle`; `gradle/actions/setup-gradle@v4` con caché de solo lectura en PR y escritura solo en `main` (evita envenenar la caché desde un fork); `--no-daemon`, `--stacktrace`, configuration cache activada; `timeout-minutes` en cada job.

#### Job 1 — `static` (~4 min)

- `./gradlew ktlintCheck detekt`
- `./gradlew lintRelease` — con `lintOptions { abortOnError true }` y `MissingTranslation` elevado a error (cubre RF-42).
- **Guardián de pureza**: paso que falla si aparece `import android.` en `core/model`, `core/domain` o `core/scheduler-policy`. Es el paso más barato y más valioso del pipeline: protege la posibilidad misma de verificar en local.
- Verificación de que existen `README.md`, `LICENSE` (GPLv3), `CONTRIBUTING.md` y `docs/00`…`docs/06` (criterios de aceptación 7 y 8).

#### Job 2 — `unit` (~8 min, depende de `static`)

- `./gradlew testDebugUnitTest`
- `./gradlew jacocoTestReport jacocoTestCoverageVerification` con los umbrales de RNF-06 reformulado (90 % ramas en `:core:domain` y `:core:scheduler-policy`, 80 % líneas en `:core:model`).
- `actions/upload-artifact` con los informes HTML de tests y cobertura, **con `if: always()`** para poder diagnosticar cuando está rojo.
- Publicación del resumen de tests en el *check* del PR (`dorny/test-reporter` o equivalente).

#### Job 3 — `build` (~10 min, en paralelo con `unit`)

- `./gradlew assembleDebug` → satisface el criterio de aceptación nº 1 y RNF-12.
- `./gradlew assembleRelease` con minificación.
- **Verificación del manifiesto (RNF-05)**: fusiona el manifiesto y falla si contiene `android.permission.INTERNET`; falla también si aparece cualquier permiso que no esté en la lista blanca de §8. Es un *check* obligatorio: es una promesa pública del producto.
- **Verificación de tamaño (RNF-04)**: falla si el APK universal de release supera 8 388 608 bytes. Publica el tamaño en el resumen del job para ver la tendencia.
- Sube el APK de debug como artefacto.

#### Job 4 — `instrumented` (~25 min, matriz)

- `reactivecircus/android-emulator-runner@v2` con matriz de API **26** (mínima, la que más rompe), **31** (color dinámico, comportamiento de alarmas exactas de Android 12) y **36** (objetivo, Modes API).
- Caché de AVD (`actions/cache` sobre `~/.android/avd`) — sin esto, cada ejecución pierde 5–8 minutos creando la imagen.
- Arranque con `-no-window -no-snapshot-save -noaudio -no-boot-anim -gpu swiftshader_indirect`.
- Concesión previa de permisos con `adb shell pm grant` y `adb shell cmd notification allow_listener` para los escenarios "con permiso"; los escenarios "sin permiso" se ejecutan revocándolos explícitamente.
- `./gradlew connectedDebugAndroidTest`.
- Habilita `AccessibilityChecks` (RNF-08a).
- **En PR se ejecuta solo API 36** para no eternizar el ciclo; la matriz completa se ejecuta en `main` y en el nocturno. Es un compromiso consciente entre cobertura y velocidad de revisión.

#### Job 5 — `nightly` (workflow separado)

- Matriz completa de emuladores, incluida API 26 con `AutomaticZenRule` ausente.
- Macrobenchmark de RNF-03, publicando la métrica y comparándola con la anterior; **avisa** por regresión > 20 %, no bloquea (los benchmarks en emulador de CI son ruidosos y bloquear con ellos genera desconfianza en el pipeline).
- Suite E2E completa de CU-01…CU-10.

#### Job 6 — `release` (con `tag`)

- Todo lo anterior + firma + generación del AAB + verificación de que el `versionCode` se incrementó + adjuntado del JSON de exportación de ejemplo.

### Qué hace fallar el build (lista cerrada y explícita)

| # | Condición | Job | ¿Bloquea el merge? |
|---|---|---|---|
| 1 | Cualquier test unitario en rojo | `unit` | **Sí** |
| 2 | Cualquier test instrumentado en rojo | `instrumented` | **Sí** |
| 3 | Cobertura por debajo del umbral de RNF-06 | `unit` | **Sí** |
| 4 | `import android.` en un módulo puro | `static` | **Sí** |
| 5 | `INTERNET` u otro permiso fuera de la lista blanca de §8 | `build` | **Sí** |
| 6 | APK de release > 8 MB | `build` | **Sí** |
| 7 | Hallazgo de Android Lint de severidad `Error`, incluida `MissingTranslation` | `static` | **Sí** |
| 8 | Hallazgo de detekt o ktlint | `static` | **Sí** |
| 9 | Warning de compilación de Kotlin (`-Werror` activo) | todos | **Sí** |
| 10 | Hallazgo de accesibilidad de severidad `ERROR` | `instrumented` | **Sí** |
| 11 | Fallo de `assembleDebug` o `assembleRelease` | `build` | **Sí** |
| 12 | Resolución de una dependencia no bloqueada / verificación de firmas fallida | todos | **Sí** |
| 13 | Falta un fichero obligatorio del repositorio (`LICENSE`, `docs/0X`) | `static` | **Sí** |
| 14 | Regresión de rendimiento > 20 % en Macrobenchmark | `nightly` | No (avisa) |
| 15 | Fallo de un emulador de la matriz secundaria en un PR | `instrumented` | No (se revisa en `main`) |

**Protección de rama `main`:** *checks* obligatorios `static`, `unit`, `build` e `instrumented (API 36)`; revisión de al menos una persona; prohibido el *push* directo; historial lineal.

---

## Casos de prueba críticos

Formato: **ID | Área | Capa donde se prueba | Dado / Cuando / Entonces.**
Los marcados con 🟢 son ejecutables **en este contenedor sin Android SDK**; los 🔵 requieren CI con Android; los 🟠 son manuales de campo.

### Resolución de conflictos

**CP-01 — Prioridad mayor gana** 🟢 `:core:domain`
**Dado** dos franjas activas en `t` = martes 10:00, la del perfil "Trabajo" (prioridad 50) y la del perfil "Reunión" (prioridad 80),
**Cuando** se invoca el resolutor para `t`,
**Entonces** el ganador es "Reunión" y el motivo registrado indica que ganó por prioridad.

**CP-02 — Empate de prioridad: gana la franja más corta** 🟢 `:core:domain`
**Dado** dos franjas activas en `t` con la misma prioridad 50, una de 09:00–18:00 y otra de 10:00–11:00,
**Cuando** se resuelve `t` = 10:30,
**Entonces** gana la de 10:00–11:00 por ser más específica.

**CP-03 — Empate total: desempate determinista y estable** 🟢 `:core:domain`
**Dado** dos franjas con la misma prioridad, la misma duración y perfiles distintos,
**Cuando** se resuelve el mismo instante **100 veces con la lista de entrada barajada aleatoriamente en cada iteración**,
**Entonces** el ganador es idéntico en las 100 iteraciones y es el de menor `profileId`; y si el `profileId` también coincide, el de menor `scheduleId`.

**CP-04 — Perfil deshabilitado no compite** 🟢 `:core:domain`
**Dado** una franja habilitada cuyo perfil tiene `enabled = false`, solapando con otra franja de prioridad menor cuyo perfil está habilitado,
**Cuando** se resuelve el instante,
**Entonces** gana la de prioridad menor, y la franja del perfil deshabilitado no aparece entre las candidatas.

**CP-05 — Franja deshabilitada no compite (RF-14)** 🟢 `:core:domain`
**Dado** un perfil habilitado con dos franjas, una de ellas con `enabled = false` y de mayor prioridad efectiva,
**Cuando** se resuelve un instante contenido en ambas,
**Entonces** gana la franja habilitada, y la deshabilitada sigue existiendo en la base de datos.

**CP-06 — Sin ninguna franja activa** 🟢 `:core:domain`
**Dado** un conjunto de franjas ninguna de las cuales contiene `t`, y ningún perfil aplicado previamente,
**Cuando** se resuelve `t`,
**Entonces** el resultado es "sin ganador" y no se genera ninguna orden de aplicación (no debe aplicarse un perfil por defecto si no había nada activo antes).

**CP-07 — Salida de franja con restauración** 🟢 `:core:domain` + 🔵 `:core:audio`
**Dado** que el perfil "Noche" está aplicado con `restoreOnExit = true` y existe un snapshot válido tomado al entrar,
**Cuando** llega el instante de fin de la franja y no hay otra franja activa,
**Entonces** se emite una orden de restauración del snapshot, y se registra una entrada `RESTORE` con `success = true`.

**CP-08 — Encadenamiento de franjas sin restauración intermedia** 🟢 `:core:domain`
**Dado** la franja A de 09:00–13:00 y la franja B de 13:00–18:00, ambas del mismo perfil o de perfiles distintos,
**Cuando** se resuelve `t` = 13:00,
**Entonces** gana B, **no se emite ninguna restauración** al snapshot previo a A, y el snapshot original se conserva por si al terminar B hubiera que restaurarlo.

### Cruce de medianoche

**CP-09 — Franja nocturna clásica dentro del mismo día** 🟢 `:core:domain`
**Dado** la franja 23:00–07:00 con `daysOfWeek = {SÁBADO}`,
**Cuando** se evalúa el sábado a las 23:30,
**Entonces** la franja está activa.

**CP-10 — Franja nocturna continuada al día siguiente (regla de §4.5)** 🟢 `:core:domain`
**Dado** la misma franja 23:00–07:00 con `daysOfWeek = {SÁBADO}`,
**Cuando** se evalúa el **domingo a la 01:00**,
**Entonces** la franja **está activa**, porque el día de la semana se evalúa sobre el inicio de la franja y no sobre el instante consultado.

**CP-11 — El domingo no arrastra a la madrugada del lunes** 🟢 `:core:domain`
**Dado** la misma franja con `daysOfWeek = {SÁBADO}`,
**Cuando** se evalúa el **domingo a las 23:30**,
**Entonces** la franja **no** está activa (el domingo no es día de inicio).

**CP-12 — Límites cerrado/abierto** 🟢 `:core:domain`
**Dado** la franja 23:00–07:00 del sábado,
**Cuando** se evalúa exactamente a las 23:00:00 del sábado y exactamente a las 07:00:00 del domingo,
**Entonces** a las 23:00:00 está activa (inicio inclusivo) y a las 07:00:00 **no** está activa (fin exclusivo), sin ambigüedad ni solape con la franja siguiente.

**CP-13 — `startTime == endTime` significa 24 horas** 🟢 `:core:domain`
**Dado** la franja 08:00–08:00 con `daysOfWeek = {LUNES}`,
**Cuando** se evalúa el lunes a las 08:00, el lunes a las 23:59 y el martes a las 07:59,
**Entonces** está activa en los tres casos; y **no** lo está el martes a las 08:00.

**CP-14 — Solape de dos franjas que cruzan medianoche** 🟢 `:core:domain`
**Dado** la franja A sábado 22:00–06:00 (prioridad 50) y la franja B domingo 00:00–08:00 (prioridad 60),
**Cuando** se evalúa el domingo a las 02:00,
**Entonces** ambas están activas y gana B por prioridad, de forma determinista.

**CP-15 — La siguiente transición nunca se salta un cambio (propiedad)** 🟢 `:core:scheduler-policy`
**Dado** un conjunto generado aleatoriamente de entre 1 y 20 franjas (con semilla fija, 1 000 iteraciones), incluidas franjas que cruzan medianoche,
**Cuando** se calcula `nextTransition(t)` para un `t` aleatorio,
**Entonces** se cumple que `nextTransition(t) > t` **estrictamente** (nunca igual, para evitar tormentas de alarmas), y que **no existe ningún instante entre `t` y `nextTransition(t)` en el que el resultado del resolutor cambie**.

**CP-16 — Sin franjas no se programa alarma** 🟢 `:core:scheduler-policy`
**Dado** que no hay ninguna franja habilitada,
**Cuando** se calcula la siguiente transición,
**Entonces** el resultado es "ninguna" y el scheduler cancela cualquier alarma pendiente en lugar de programar una a un instante arbitrario.

### Prioridades

**CP-17 — Prioridad fuera de rango es rechazada** 🟢 `:core:model`
**Dado** un intento de crear un `SoundProfile` con `priority = 101` o `priority = −1`,
**Cuando** se construye el objeto o se valida,
**Entonces** se rechaza con un error de validación explícito y el perfil no se persiste.

**CP-18 — Cambiar la prioridad recalcula el estado en caliente** 🟢 `:core:domain` + 🔵 integración
**Dado** dos perfiles solapados donde actualmente gana A,
**Cuando** el usuario eleva la prioridad de B por encima de la de A,
**Entonces** el resolutor devuelve B en la siguiente evaluación, se aplica B sin esperar a la próxima alarma, y se registra la transición con motivo "cambio de configuración".

### Pausa global

**CP-19 — La pausa global tiene precedencia sobre todo** 🟢 `:core:domain`
**Dado** una franja activa y una activación manual vigente simultáneamente, y una pausa global hasta `t + 1 h`,
**Cuando** se resuelve cualquier instante anterior a `t + 1 h`,
**Entonces** el resultado es "no aplicar nada" (paso 1 de §7.3 gana a los pasos 2 y 3) y se registra un `SKIP` con motivo "pausa global".

**CP-20 — Al expirar la pausa se recupera el estado correcto** 🟢 `:core:domain` + 🔵
**Dado** una pausa global que expira a las 15:00 y una franja "Trabajo" vigente de 09:00 a 18:00,
**Cuando** llega el instante 15:00,
**Entonces** se aplica "Trabajo" inmediatamente sin esperar a las 18:00, y el historial refleja la aplicación con motivo "fin de pausa".

**CP-21 — Pausa "hasta mañana" y su instante exacto** 🟢 `:core:domain`
**Dado** que el usuario activa la pausa "hasta mañana" a las 22:30 del martes,
**Cuando** se calcula el fin de la pausa,
**Entonces** es el miércoles a las 00:00 hora local (no "24 horas después"), y la siguiente transición programada es exactamente ese instante.

**CP-22 — La pausa sobrevive al reinicio** 🔵 `:core:scheduler` + `:core:data`
**Dado** una pausa global activa hasta dentro de 2 horas,
**Cuando** el dispositivo se reinicia,
**Entonces** tras `BOOT_COMPLETED` la pausa sigue vigente con el mismo instante de fin, no se aplica ningún perfil, y la alarma de fin de pausa queda reprogramada.

### Activación manual

**CP-23 — La activación manual gana a lo programado** 🟢 `:core:domain`
**Dado** la franja "Trabajo" activa y sin pausa global,
**Cuando** el usuario activa manualmente "Cine" con duración de 2 h,
**Entonces** se aplica "Cine" y se mantiene aplicado aunque el resolutor programado siga indicando "Trabajo".

**CP-24 — Al expirar la activación manual se vuelve a lo programado** 🟢 `:core:domain` + 🔵
**Dado** "Cine" activado manualmente durante 2 h a las 16:00, con la franja "Trabajo" vigente de 09:00 a 18:00,
**Cuando** llega las 18:00... y también cuando llegan las 18:00 con la manual expirando a las 18:00 exactas,
**Entonces** a las 18:00 la activación manual ya no está vigente **y** la franja "Trabajo" tampoco: se ejecuta la política de restauración de RF-15 una sola vez, sin doble aplicación ni parpadeo.

**CP-25 — Activación manual expirando en medio de una franja** 🟢 `:core:domain`
**Dado** "Cine" manual de 16:00 a 18:00 y la franja "Trabajo" de 09:00 a 20:00,
**Cuando** llegan las 18:00,
**Entonces** se aplica "Trabajo" (no se restaura el snapshot previo a "Cine"), porque hay una franja programada activa que tiene precedencia sobre la restauración.

**CP-26 — Activación manual indefinida** 🟢 `:core:domain`
**Dado** un perfil activado manualmente con duración "indefinida",
**Cuando** pasan 48 horas y se cruzan varios inicios y fines de franja,
**Entonces** el perfil manual sigue aplicado, no se programa ninguna alarma de expiración por su causa, y la UI muestra de forma persistente que hay una activación manual vigente con acción de cancelarla.

**CP-27 — Pulsar el perfil lo activa, no lo edita (aprendizaje de la competencia)** 🔵 `:feature:profiles`
**Dado** la lista de perfiles,
**Cuando** el usuario toca un perfil,
**Entonces** el perfil se activa y aparece el indicador de perfil activo (RF-08); la edición se abre con un gesto distinto y claramente diferenciado.

### Permisos denegados

**CP-28 — Silencio sin `ACCESS_NOTIFICATION_POLICY` no rompe la app** 🔵 `:core:audio`
**Dado** un perfil con `ringerMode = SILENT` y el permiso `ACCESS_NOTIFICATION_POLICY` no concedido, con DND activo en el sistema,
**Cuando** el motor intenta aplicar el perfil,
**Entonces** se captura la `SecurityException`, **no hay crash**, se aplican los volúmenes de los streams que sí son aplicables, se escribe una entrada `ERROR` con `success = false` y motivo identificable, y la UI ofrece un acceso directo a la pantalla del sistema que concede el permiso.

**CP-29 — Sin alarma exacta se degrada a ventana (RF-29)** 🔵 `:core:scheduler` + 🟢 política
**Dado** `canScheduleExactAlarms() == false`,
**Cuando** se programa la siguiente transición,
**Entonces** se usa `setWindow` con ventana de 5 minutos en lugar de `setExactAndAllowWhileIdle`, se muestra un aviso no bloqueante al usuario, y el panel de diagnóstico marca el problema con una acción correctiva.

**CP-30 — Recuperación del permiso exacto en caliente (RF-30)** 🔵 `:core:scheduler`
**Dado** la app funcionando en modo degradado,
**Cuando** el usuario concede `SCHEDULE_EXACT_ALARM` y el sistema emite `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`,
**Entonces** la app cancela la alarma inexacta, reprograma con `setExactAndAllowWhileIdle`, registra el cambio en el historial y actualiza el diagnóstico, **sin necesidad de que el usuario abra la app**.

**CP-31 — Matriz completa de denegaciones sin crash (RNF-09)** 🔵 `:app`
**Dado** cada una de las 16 combinaciones de concesión/denegación de los cuatro permisos de §8,
**Cuando** se ejecutan las operaciones "aplicar perfil" y "programar franja",
**Entonces** en ninguna combinación se produce una excepción no capturada, y en todas queda una entrada de historial que explica qué no se pudo hacer y por qué.

**CP-32 — "Omitir durante llamada" sin `READ_PHONE_STATE`** 🔵 `:core:audio`
**Dado** un perfil con `skipDuringCall = true` y el permiso `READ_PHONE_STATE` denegado,
**Cuando** llega el momento de aplicar,
**Entonces** la app **aplica el perfil** (no puede saber si hay llamada, y el comportamiento por defecto debe ser cumplir la promesa principal), registra la limitación en el historial, y la UI advierte de que la opción no es efectiva sin ese permiso.

### Reinicio

**CP-33 — Reprogramación tras `BOOT_COMPLETED` (CU-09, RF-16)** 🔵 `:core:scheduler`
**Dado** una franja "Noche" de 23:00 a 07:00 y un dispositivo que se apaga a las 22:00 y arranca a las 23:30,
**Cuando** se recibe `BOOT_COMPLETED`,
**Entonces** la reconciliación detecta que "Noche" debería estar activo, lo aplica inmediatamente, programa la alarma para las 07:00, y escribe una entrada `BOOT` en el historial.

**CP-34 — Transición perdida con el dispositivo apagado** 🔵 integración
**Dado** el mismo escenario pero con apagado a las 22:00 y arranque a las 08:00 (la franja empezó **y terminó** con el móvil apagado),
**Cuando** arranca el sistema,
**Entonces** **no** se aplica "Noche", el estado resultante es el correspondiente a las 08:00, y el historial registra explícitamente la transición perdida para que el usuario pueda entender qué pasó (D6).

**CP-35 — Actualización de la app reprograma (RF-16)** 🔵 `:core:scheduler`
**Dado** una alarma pendiente,
**Cuando** se recibe `MY_PACKAGE_REPLACED`,
**Entonces** la alarma se reprograma (Android las descarta al actualizar) y se registra el evento.

**CP-36 — Reconciliación al desbloquear (RF-32)** 🔵 `:core:scheduler`
**Dado** que el sistema mató el proceso y se perdió una transición hace 40 minutos,
**Cuando** el usuario desbloquea el dispositivo y se emite `ACTION_USER_PRESENT`,
**Entonces** la reconciliación detecta la discrepancia, aplica el perfil correcto y lo registra con motivo "reconcile".

### Cambio de hora

**CP-37 — Cambio manual de hora hacia adelante** 🔵 `:core:scheduler` + 🟢 política
**Dado** una alarma programada para las 23:00 y la hora actual 22:00,
**Cuando** el usuario cambia manualmente la hora del dispositivo a las 23:30 y el sistema emite `TIME_SET`,
**Entonces** la app recalcula, detecta que la transición de las 23:00 ya ha pasado, aplica el perfil correspondiente y reprograma la siguiente alarma.

**CP-38 — Cambio de zona horaria** 🔵 `:core:scheduler` + 🟢 política
**Dado** un usuario en Madrid con la franja "Noche" 23:00–07:00 que viaja a Nueva York,
**Cuando** el dispositivo cambia de zona y emite `TIMEZONE_CHANGED`,
**Entonces** las franjas se interpretan en **hora local nueva** (23:00 hora de Nueva York) y no en la antigua, y todas las alarmas se recalculan.

**CP-39 — Cambio de horario de verano: la noche de 23 horas** 🟢 `:core:domain` + `:core:scheduler-policy`
**Dado** la franja 01:00–03:00 y la madrugada del último domingo de marzo en `Europe/Madrid`, en la que las 02:00 no existen (el reloj salta de 02:00 a 03:00),
**Cuando** se calcula la siguiente transición y la pertenencia,
**Entonces** el cálculo no lanza excepción, la franja se considera activa desde la 01:00 hasta el salto, y la transición de fin se resuelve a un instante real existente (nunca a una hora local inexistente).

**CP-40 — Cambio de horario de invierno: la noche de 25 horas** 🟢 `:core:domain`
**Dado** la franja 02:00–03:00 y la madrugada del último domingo de octubre en `Europe/Madrid`, en la que las 02:30 **ocurre dos veces**,
**Cuando** se calcula la transición,
**Entonces** el perfil se aplica **una sola vez** (se elige de forma documentada el primer offset) y no se produce una doble aplicación ni un bucle de reprogramación.

### Import / export

**CP-41 — Ida y vuelta sin pérdida (CU-08, criterio de aceptación 6)** 🟢 `:core:data` (serialización)
**Dado** una configuración con 5 perfiles (con emojis, todos los streams incluidos algunos a `null`, DND con excepciones y opciones completas) y 8 franjas incluidas dos que cruzan medianoche,
**Cuando** se exporta a JSON y se reimporta sobre una base de datos vacía,
**Entonces** el modelo resultante es **igual** al original campo a campo, incluidos los `null` de "no tocar", que deben distinguirse de `0`.

**CP-42 — Distinción crítica entre `null` y `0`** 🟢 `:core:data`
**Dado** un perfil con `music = null` (no tocar) y `ring = 0` (silenciar),
**Cuando** se serializa y se deserializa,
**Entonces** `music` sigue siendo `null` y `ring` sigue siendo `0`; el JSON no omite `ring` ni convierte `null` en `0`. *(Este es el bug que rompería la promesa de P2: la alarma a 100 y el resto a 0.)*

**CP-43 — Versión de esquema futura se rechaza con elegancia** 🟢 `:core:data`
**Dado** un JSON con `schemaVersion = 99`,
**Cuando** se importa,
**Entonces** la importación se rechaza con un mensaje comprensible ("copia creada con una versión más reciente de la app"), **no se modifica nada** de la configuración existente, y no hay crash.

**CP-44 — JSON corrupto o truncado** 🟢 `:core:data`
**Dado** un fichero truncado a la mitad, un fichero vacío y un fichero que no es JSON,
**Cuando** se intenta importar cada uno,
**Entonces** en los tres casos se muestra un error controlado, la configuración previa permanece intacta (importación atómica: todo o nada) y se registra el intento.

**CP-45 — Importación con datos inválidos semánticamente** 🟢 `:core:data` + `:core:model`
**Dado** un JSON sintácticamente válido pero con `priority = 500`, un volumen de `150`, y una franja con `daysOfWeek` vacío,
**Cuando** se importa,
**Entonces** se rechaza la importación completa señalando qué registros son inválidos, sin importar parcialmente.

**CP-46 — Portabilidad entre dispositivos con distinto número de pasos** 🟢 `:core:domain` + 🔵
**Dado** una configuración exportada en un dispositivo con 15 pasos de tono y `ring = 60 %`,
**Cuando** se importa en un dispositivo con 7 pasos,
**Entonces** el valor almacenado sigue siendo 60 % y al aplicarlo se traduce a `round(0,6 × 7) = 4` pasos.

### Errores de audio

**CP-47 — Conversión de porcentaje a pasos, incluidos los bordes** 🟢 `:core:domain`
**Dado** un stream con `max = 7` y `min = 0`,
**Cuando** se convierten los porcentajes 0, 1, 7, 50, 99 y 100,
**Entonces** 0 → 0 pasos, 100 → 7 pasos, 50 → 4 pasos (redondeo al alza documentado), y **cualquier porcentaje mayor que 0 nunca produce 0 pasos** (se eleva al mínimo audible), porque silenciar sin querer es un fallo de producto grave.

**CP-48 — Respeto de `getStreamMinVolume` en API 28+ con fallback en API 26–27** 🔵 `:core:audio`
**Dado** un stream con `min = 1` (típico de `STREAM_VOICE_CALL`) y un objetivo de 0 %,
**Cuando** se aplica en API 28+,
**Entonces** el valor final es 1, no 0; y en API 26–27, donde la API no existe, se usa 0 sin lanzar `NoSuchMethodError`.

**CP-49 — `SecurityException` en mitad de la aplicación de varios streams** 🔵 `:core:audio`
**Dado** un perfil que fija los 7 streams y un `AudioManager` que lanza `SecurityException` al aplicar el cuarto,
**Cuando** se aplica el perfil,
**Entonces** los tres primeros streams quedan aplicados, se intentan los tres restantes, no hay crash, y el historial registra **una entrada de error que identifica exactamente qué streams fallaron**, no un mensaje genérico.

**CP-50 — No bajar multimedia si hay reproducción (RF-25)** 🔵 `:core:audio`
**Dado** un perfil con `skipIfMediaPlaying = true` que fija `music = 20 %`, y música reproduciéndose con el volumen actual al 80 %,
**Cuando** se aplica el perfil,
**Entonces** `STREAM_MUSIC` **no se modifica**, el resto de streams sí se aplican, y se registra un `SKIP` parcial explicando el motivo. *(Y el caso inverso: si el perfil sube el volumen de música, sí se aplica, porque la regla es "no reducir", no "no tocar".)*

**CP-51 — Omitir durante llamada activa (RF-24)** 🔵 `:core:audio`
**Dado** un perfil con `skipDuringCall = true` y una llamada en curso,
**Cuando** llega el instante de aplicación,
**Entonces** no se aplica nada, se registra un `SKIP` con motivo "llamada activa", y **se reprograma un reintento para el fin de la llamada**, de modo que el perfil acabe aplicándose (omitir no puede significar perder la transición).

**CP-52 — Rampa gradual interrumpida por una nueva transición (RF-23)** 🟢 lógica + 🔵 ejecución
**Dado** una rampa de 30 s en curso desde el 80 % hasta el 20 %, con la rampa a mitad de recorrido (≈50 %),
**Cuando** se dispara una nueva aplicación que exige el 100 %,
**Entonces** la rampa antigua se cancela sin aplicar más pasos suyos, la nueva parte del valor actual real (≈50 %) y termina exactamente en 100 %, sin que quede ningún trabajo en segundo plano huérfano.

**CP-53 — El snapshot no corrompe la alarma de P2** 🔵 `:core:audio`
**Dado** el perfil "Noche" con `alarm = 100 %` y todo lo demás a 0,
**Cuando** se aplica y a las 07:00 se restaura el snapshot previo,
**Entonces** durante toda la franja el volumen de alarma es el máximo del dispositivo, y `RingerMode = SILENT` **no** afecta a `STREAM_ALARM`. *(Es literalmente el susto que P2 describe en §3 de la especificación: si este test falla, el producto no sale.)*

**CP-54 — Acoplamiento tono/notificación detectado (RF-10)** 🟢 función pura + 🟠 campo
**Dado** un dispositivo en el que escribir `STREAM_RING` modifica también `STREAM_NOTIFICATION`,
**Cuando** se ejecuta la sonda de detección,
**Entonces** el estado detectado es `COUPLED`, la UI del editor muestra un control único con explicación, y la sonda deja los volúmenes exactamente como estaban antes de ejecutarse.

**CP-55 — Purga del historial (RF-38)** 🔵 `:core:data`
**Dado** un historial con 1 500 entradas, de las cuales 400 tienen más de 30 días,
**Cuando** se inserta una entrada nueva,
**Entonces** las 400 antiguas se eliminan y de las restantes se conservan exactamente las 1 000 más recientes.

**CP-56 — "¿Por qué sonó así a las 3 de la mañana?" (CU-07)** 🔵 `:feature:history`
**Dado** un historial con aplicaciones, restauraciones, omisiones y errores de las últimas 24 h,
**Cuando** el usuario filtra por el intervalo 02:00–04:00,
**Entonces** ve las entradas de ese intervalo con el perfil, la franja y el motivo legibles, y cada entrada indica si tuvo éxito.

**Resumen de cobertura:** 56 casos; **28 de ellos (50 %) son ejecutables íntegramente en este contenedor sin Android SDK**, lo que confirma la viabilidad de la estrategia.

---

## Definición de "Hecho" para la v1

Una funcionalidad está **Hecha** cuando cumple **todas** estas condiciones. Sin excepciones parciales ni "lo cerramos y lo arreglamos luego".

**Nivel de historia / funcionalidad individual:**

1. El código está en `main` mediante PR revisado por al menos una persona.
2. Todos los criterios de aceptación de su requisito (RF/RNF **en su versión reformulada**) están cubiertos por al menos un test automático, o justificados por escrito como no automatizables con su procedimiento manual documentado.
3. La lógica pura asociada vive en `:core:model`, `:core:domain` o `:core:scheduler-policy` y **pasa `scripts/verify-local.sh`**.
4. CI en verde en los cuatro jobs obligatorios.
5. Cobertura dentro de los umbrales de RNF-06 reformulado.
6. Cero warnings de compilación, cero hallazgos de detekt/ktlint, cero errores de Android Lint.
7. Todas las cadenas visibles están en `strings.xml` en **español e inglés** (RF-42).
8. Todo elemento interactivo nuevo tiene semántica de accesibilidad y target ≥ 48 dp.
9. Los fallos previsibles (permiso ausente, `SecurityException`, datos corruptos) escriben una entrada de historial accionable.
10. Comentarios, nombres y mensaje de commit en inglés (RNF-11).

**Nivel de release v1.0** — la lista de §10 de la especificación, corregida y ampliada:

11. `assembleDebug` **y** `assembleRelease` completan en un runner limpio (RNF-12 reformulado, con lockfiles).
12. Cobertura: `:core:domain` y `:core:scheduler-policy` ≥ 90 % de ramas; `:core:model` ≥ 80 % de líneas.
13. **Los 56 casos de prueba críticos de este informe están implementados y en verde** (los 🟠 con acta manual firmada).
14. El manifiesto fusionado no contiene `INTERNET` y no contiene ningún permiso fuera de la lista blanca de §8, verificado automáticamente.
15. APK universal de release minificado < 8 MB.
16. Onboarding funcional para `ACCESS_NOTIFICATION_POLICY`, `SCHEDULE_EXACT_ALARM` y `POST_NOTIFICATIONS`, cada uno con su explicación y su vía de recuperación si se deniega.
17. Ida y vuelta de exportación/importación JSON sin pérdida, incluida la distinción `null` vs `0` (CP-41, CP-42).
18. Suite instrumentada verde en emuladores API **26, 31 y 36**.
19. **Prueba de fiabilidad de campo:** en al menos **3 dispositivos reales de fabricantes distintos** (uno de ellos Xiaomi, Samsung u otro con gestión agresiva de batería), la app aplica ≥ 99 % de las transiciones programadas durante **72 horas continuas** de uso normal, con el historial exportado como evidencia (RNF-01b). **Este es el criterio que no se puede negociar: es la promesa nº 1 del producto y es lo que la competencia falla.**
20. Auditoría manual de TalkBack sobre CU-01…CU-10 completada.
21. Las 16 combinaciones de la matriz de permisos ejecutadas sin crash (RNF-09 reformulado).
22. Todas las ambigüedades de la sección "requisitos no verificables" de este informe están resueltas **en el documento `01-ESPECIFICACIONES.md`**, no solo en el código. Si el código y la especificación divergen, no está hecho.
23. `README.md`, `LICENSE` (GPLv3), `CONTRIBUTING.md`, `.github/workflows/` y `docs/00`…`docs/06` presentes y actualizados.
24. Cero *issues* abiertas de severidad crítica o alta.

---

## Riesgos de calidad que el proyecto está asumiendo

Estos riesgos **no se eliminan** con la estrategia propuesta. Se asumen conscientemente, y quiero que quede constancia de ello antes de empezar, no después del primer incidente.

**R1 — El kilómetro final no está cubierto por ninguna automatización. (Impacto: crítico. Probabilidad: alta.)**
La promesa central del producto —"se dispara, siempre"— depende de comportamientos de firmware que ni este contenedor ni GitHub Actions pueden reproducir: MIUI matando procesos, One UI reinterpretando el modo de timbre, OEM ignorando `setExactAndAllowWhileIdle`. Podemos tener el 100 % de la suite en verde y fallar exactamente donde falla la competencia. **Mitigación parcial:** el punto 19 de la Definición de Hecho (72 h en 3 dispositivos reales) y el historial exportable como instrumento de diagnóstico. **Riesgo residual: alto y permanente.** Este es *el* riesgo del proyecto.

**R2 — El emulador miente sobre el audio y el DND. (Impacto: alto. Probabilidad: alta.)**
Los emuladores de CI implementan `AudioManager` y `NotificationManager` de forma idealizada: sin acoplamiento tono/notificación, sin las peculiaridades de DND de cada OEM, sin audio real. Un test instrumentado verde en API 36 **no** demuestra que el audio funcione en un Xiaomi. Aceptamos que la capa `:core:audio` tiene cobertura automatizada de *contrato*, no de *comportamiento real*.

**R3 — Divergencia entre la verificación local y CI. (Impacto: medio. Probabilidad: media.)**
El `kotlinc` local usa versiones de biblioteca distintas (kotlinx-serialization 1.6.2, JUnit 4.13.2) de las que usará el proyecto, y no aplica la configuración de Gradle. Un verde local puede ser un falso positivo. **Mitigación:** replicar los flags del compilador y recordar en el propio script que la autoridad es CI. **Riesgo residual: bajo, pero requiere disciplina.**

**R4 — Erosión de la pureza del núcleo. (Impacto: alto si ocurre. Probabilidad: media.)**
Toda esta estrategia se sostiene sobre que `:core:model`, `:core:domain` y `:core:scheduler-policy` no dependan de Android. Basta con que alguien añada un `import android.util.Log` "para depurar" y perdemos la verificación local del núcleo. **Mitigación:** el guardián automático del job `static`, que es el paso más barato del pipeline. Si ese *check* se desactiva alguna vez, la estrategia colapsa.

**R5 — La cobertura como métrica engañosa. (Impacto: medio. Probabilidad: media.)**
90 % de ramas en `:core:domain` no significa que el algoritmo sea correcto; significa que se ejecutó. Los bugs de este dominio son de **casos límite temporales** (DST, `start == end`, medianoche del domingo), no de ramas sin ejecutar. **Mitigación:** los tests basados en propiedades de CP-15 y CP-03, que valen más que veinte puntos de cobertura. Asumimos que el número puede dar falsa tranquilidad.

**R6 — Tiempo de emulador en CI y presión para saltárselo. (Impacto: medio. Probabilidad: alta.)**
El job `instrumented` con matriz de 3 APIs ronda los 25 minutos. La tentación de convertirlo en no bloqueante para acelerar los PR es real y creciente. Ya lo hemos mitigado parcialmente (solo API 36 en PR), pero asumimos que los fallos específicos de API 26 se detectarán con retraso, en `main` o en el nocturno.

**R7 — Riesgo de plataforma fuera de nuestro control. (Impacto: alto. Probabilidad: media.)**
La política de Google Play sobre alarmas exactas ya retiró temporalmente a Tasker (§3.5 de la investigación). Nuestra ficha de Play puede ser rechazada por `SCHEDULE_EXACT_ALARM` pese a hacer todo correctamente, y `targetSdk 36` es obligatorio desde el 31/08/2026, **es decir, dentro de 19 días**. Ningún test detecta esto. Asumimos el riesgo regulatorio y preparamos la justificación de permisos como entregable de la release.

**R8 — Sin telemetría, somos ciegos en producción. (Impacto: medio. Probabilidad: alta.)**
La decisión D4 (cero red, cero analítica) es correcta y es un argumento de venta, pero tiene un coste de calidad honesto: **no nos enteraremos de un fallo masivo salvo por reseñas en Play**. No habrá crash reporting, ni métricas de fiabilidad agregadas. **Mitigación:** el historial exportable (D6) convierte al usuario en nuestro instrumento de diagnóstico —hay que hacer que exportarlo y adjuntarlo a un *issue* sea trivial—. Asumimos que el bucle de detección de fallos será lento.

**R9 — Ambigüedades de especificación cerradas por el código en vez de por el documento. (Impacto: medio. Probabilidad: alta si no se actúa ya.)**
Si el equipo empieza a implementar antes de resolver los 18 puntos de la segunda sección, las decisiones se tomarán implícitamente en el código y la especificación quedará obsoleta el primer día. **Esta es la única mitigación que depende enteramente de nosotros y hay que ejecutarla ahora**: cerrar RF-15, `start == end`, el desempate de §7.3.4, RF-38 y el alcance de RF-19 **antes** del primer commit de `:core:domain`.

**R10 — Rendimiento y batería sin verificación continua. (Impacto: bajo-medio. Probabilidad: alta.)**
RNF-02 y RNF-03 solo se comprobarán manualmente y por release. Las regresiones se acumularán silenciosamente entre mediciones. Es un compromiso aceptable para una v1, pero conviene reconocerlo.

---

**Recomendación final de QA, en una frase:** aprobar la especificación **condicionada** a cerrar los 18 puntos de la segunda sección —muy especialmente RF-15, `startTime == endTime`, el desempate de §7.3.4 y la extracción de `:core:scheduler-policy` a Kotlin puro— antes de escribir la primera línea de `:core:domain`; a partir de ahí, el 50 % del riesgo lógico se elimina sin salir de este contenedor y el resto se delega en un CI que ya está diseñado para bloquear lo que debe bloquear. Lo que no se puede delegar en ninguna máquina es el punto 19 de la Definición de Hecho: **72 horas en tres teléfonos de verdad.**

agentId: a73b7e774393e785a (use SendMessage with to: 'a73b7e774393e785a', summary: '<5-10 word recap>' to continue this agent)
<usage>subagent_tokens: 80081
tool_uses: 12
duration_ms: 591626</usage>