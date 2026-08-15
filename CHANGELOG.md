# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/).

## [2.1.0] — 2026-08-15

### Añadido

- **Aviso opcional al activarse un perfil.** El interruptor está en las opciones de cada
  perfil, así que puedes avisar solo con los que te importan. Usa un canal propio y silencioso
  —sin sonido ni vibración—, porque una notificación que pita para anunciar que acaba de
  silenciarte el móvil sería absurda. El aviso se retira solo cuando el perfil termina.
  El permiso de notificaciones se pide **al activar el interruptor**, no al abrir el editor.
- **El historial dice qué stream falló.** El dato ya se guardaba y ninguna pantalla lo
  mostraba: «Android ignoró el cambio» sin decir cuál es inaccionable, y es justo la línea que
  hace útil el informe de alguien a quien no le puedes mirar el móvil.

### Corregido

- **La verificación de escritura era ciega al silenciado.** Se comprobaba el índice de volumen
  con `getStreamVolume` y nunca `isStreamMute`. Hay dispositivos donde un stream silenciado no
  devuelve 0 sino el nivel que restaurará al reactivarse; ahí, pedir silencio parecía una
  escritura ignorada y la app acusaba al teléfono de mentir cuando había obedecido.

## [2.0.0] — 2026-08-15

**La aplicación pasa a llamarse RitMute** (*ritmo* + *mute*), y su identificador es
`com.ritmute.app`. Número mayor porque, para Android, esto es una aplicación distinta.

### Por qué

El nombre era la última decisión abierta del proyecto, y publicar en Play la cierra para
siempre: el `applicationId` no se puede cambiar una vez la app está en la tienda. Los dos
candidatos anteriores se comprobaron y los dos fallaron.

- **Sonify** tiene al menos cuatro apps publicadas con ese nombre entre Google Play y la App
  Store. Imposible aparecer en una búsqueda.
- **SonoRitmo** no tenía ninguna app, pero `sonoritmo.com` es una empresa de audio profesional
  en activo, con tienda y presencia en redes. Una marca del mismo sector es justo el supuesto
  en el que prosperan las retiradas de aplicaciones.

**RitMute** no tiene presencia en ninguna tienda ni en la web.

### Qué implica para quien ya la tenga instalada

Android trata `com.ritmute.app` como una aplicación nueva. La anterior **no se actualiza a
esta**: aparecerán las dos y hay que desinstalar la vieja. Los perfiles no se conservan; si
quieres llevártelos, expórtalos desde Ajustes antes de desinstalar y vuelve a importarlos.

### Qué se ha cambiado

Todos los paquetes de `com.sonoritmo` a `com.ritmute`, las clases y recursos que llevaban el
nombre antiguo, el manifiesto, las reglas de ProGuard, la acción de la alarma, el esquema de
enlaces profundos, el nombre del fichero de base de datos, los artefactos del workflow, los
textos visibles en los dos idiomas y toda la documentación.

Sin cambios a propósito: el repositorio de GitHub sigue llamándose `Sonify`, y el certificado
de firma sigue diciendo `CN=SonoRitmo`. El titular del certificado no se muestra en ninguna
parte, y regenerarlo obligaría a desinstalar la app en cualquier dispositivo que ya la tenga.

## [1.5.1] — 2026-08-14

### Corregido

- El aviso de estado se formateaba la hora por su cuenta y seguía diciendo «Termina a las
  7:00» mientras el resto de la app ya decía `07:00`. Ahora hay un único formateador.

## [1.5.0] — 2026-08-14

Tres detalles vistos ya con la app corriendo en un emulador.

### Cambiado

- **Las horas se muestran con dos dígitos**: `07:00`, no `7:00`, que quedaba descuadrado bajo
  `23:00`. Se ensancha el patrón del idioma en vez de fijar `HH:mm`, así que un idioma de 12
  horas sigue viendo `07:00 AM` y no un reloj de 24.
- **El catálogo de iconos empieza plegado.** Extendido ocupaba cinco filas y empujaba los
  volúmenes fuera de la pantalla.
- El estado vacío deja de ofrecer «Elegir plantilla»: el botón flotante que hay justo debajo
  abre exactamente el mismo selector.

## [1.4.0] — 2026-08-14

### Añadido

- **Catálogo de iconos para el perfil**, con 24 opciones más «Inicial». El ViewModel ya sabía
  cambiar el emoji desde la v1.0; simplemente no había ningún control que lo llamara.

### Cambiado

- **Las barras de volumen se mueven libremente de 0 a 100 %.** Estaban ancladas a los pasos
  reales del dispositivo para que un 30 % no volviera como 29 %; las muescas resultaban más
  molestas que ese redondeo, así que ahora la unidad es el punto porcentual.
- Un volumen sin tocar muestra **NA** junto a la barra, en vez de «Sin cambios». El lector de
  pantalla sigue diciendo «Sin cambios», porque NA no es una palabra.
- **El resumen del perfil es ahora una sola fila con los seis streams**, siempre los seis y
  siempre en el mismo orden: color fuerte si el perfil lo cambia, tachado si lo silencia, y
  atenuado si lo deja como está. Debajo, un par de filas por franja: los siete días como
  letras en círculo, marcados o no, y el horario. Sin texto adicional: un horario que cruza
  medianoche se lee `23:00 – 07:00`, sin la coletilla «(día siguiente)».
- En el menú del perfil, «Deshabilitar» (que impide que se active nunca) deja de llamarse
  igual que «Desactivar» (que apaga la activación en curso).

### Corregido

- **El domingo salía aplastado** en el selector de días: siete círculos de 48 dp necesitan
  336 dp y no caben en un móvil de 360 dp una vez descontados los márgenes. Ahora los siete
  reparten el ancho a partes iguales, con separación entre ellos, y el área táctil conserva
  sus 48 dp de alto.

## [1.3.0] — 2026-08-14

Segunda tanda de correcciones de uso real, toda en la lista y el editor de perfiles.

### Añadido

- **Desactivar un perfil activado a mano.** «Hasta que lo quite» se ofrecía sin que existiera
  en ninguna parte de la app la forma de quitarlo. Aparece en el aviso de estado y en el menú
  del perfil activo, y solo cuando la activación es manual: una programada volvería sola en
  la siguiente reconciliación.
- **Porcentaje a la derecha de cada barra de volumen**, con ancho fijo para que 5 %, 50 % y
  100 % terminen en la misma columna.

### Cambiado

- **La descripción de cada perfil pasa a ser iconos.** Antes era una frase que no cabía y se
  cortaba justo en la parte que distingue un perfil de otro. Ahora: un icono con su
  porcentaje por cada volumen y por el modo de timbre, debajo los días activos y debajo el
  horario, sin recortar y con todas las franjas, no solo la primera.

### Corregido

- El menú de los tres puntos se abría en la esquina superior izquierda de la tarjeta en vez
  de junto al botón, y con él se cortaba el texto de «Activar durante…».
- Las barras de volumen solo se dibujaban en los streams activados, así que cada fila tenía
  una altura distinta y las barras no quedaban alineadas entre sí. Ahora la barra está
  siempre, atenuada cuando el stream se deja como está.

## [1.2.0] — 2026-08-14

Correcciones de la primera revisión de la app en uso real.

### Añadido

- **Las horas de una franja se pueden editar.** Hasta ahora el editor mostraba el rango como
  texto y solo dejaba cambiar los días o borrar la franja; ahora el inicio y el fin abren un
  selector de hora. Mover un extremo deja el otro donde estaba y la duración es la que cede.
- Opción **Perfil en blanco** en el selector de plantillas, que abre el editor vacío.

### Cambiado

- El botón flotante de la lista pasa a ser un **+** y abre el selector de plantillas, con el
  perfil en blanco como primera opción.
- El botón de pausa se convierte en **play** cuando las reglas están en pausa y sirve para
  reanudarlas. Antes seguía diciendo «pausar» sin ofrecer vuelta atrás.
- Las opciones de timbre y No molestar se reparten en filas de dos con el mismo ancho, en vez
  de una sola fila donde la última quedaba aplastada. Sus etiquetas se han acortado.

### Corregido

- El botón flotante se dibujaba **por debajo de la barra de pestañas**: el `NavHost` no
  consumía el hueco inferior del `Scaffold` de la aplicación.

## [1.1.0] — 2026-08-13

Primera versión instalada en un dispositivo real. La 1.0.0 nunca llegó a ejecutarse: la
verificación de esa versión fue estática, y esto es lo que apareció al usarla.

### Corregido

- **No se podía crear ningún perfil.** El editor rechaza los perfiles inválidos y calculaba
  la lista de problemas, pero la pantalla no la pintaba en ninguna parte. Un perfil recién
  creado incumple `PROFILE_CHANGES_NOTHING` por construcción, así que el primer guardado
  siempre fallaba y no ocurría nada visible. Ahora cada motivo se muestra con su explicación
  y se anuncia además por snackbar.
- **Las plantillas eran inalcanzables** en cuanto existía un perfil: el selector colgaba solo
  del estado vacío. Pasa a la barra superior, donde siempre está disponible.
- **Las plantillas se guardaban con el nombre del enum** (`NIGHT`, `WORK`) en lugar de su
  etiqueta traducida.
- Crear un perfil desde una plantilla no daba ninguna confirmación.
- Los botones de las hojas inferiores usaban `fillMaxSize`, con lo que cada uno reclamaba
  toda la altura disponible.
- La publicación del APK como *release* público en GitHub pasa a ser opcional en las
  ejecuciones manuales del workflow.

### Cambiado

- El APK que produce el workflow por defecto es el de *release*: el de depuración lo marca
  Play Protect como aplicación peligrosa y no se puede instalar sin pelearse con el sistema.

## [1.0.0] — 2026-08-12

Primera versión pública.

### Añadido

- Perfiles de sonido completos: seis streams de audio, modo de timbre y No molestar.
- Franjas horarias con días de la semana, cruce de medianoche y restauración automática.
- Resolución de conflictos determinista por prioridad, estable entre dispositivos.
- Motor de programación con una única alarma para la siguiente transición, degradación
  explícita cuando no hay permiso de alarma exacta, y vigilante horario.
- Recuperación tras reinicio, incluida la ruta previa al desbloqueo (`LOCKED_BOOT_COMPLETED`).
- Reacción a cambios de hora, de zona horaria y a actualizaciones de la app.
- Integración con No molestar mediante `AutomaticZenRule`, con aviso cuando otro modo del
  sistema anula el nuestro.
- Activación manual con duración y pausa global de todas las reglas.
- Mosaico de Ajustes Rápidos y widget de pantalla de inicio.
- Historial de actividad con la razón de cada cambio y si tuvo éxito.
- Panel de diagnóstico con permisos, estado del programador, guía por fabricante y modo de
  máxima fiabilidad opcional.
- Exportación e importación de la configuración en JSON.
- Seis plantillas: Noche, Trabajo, Reunión, Cine, Conducción, Fin de semana.
- Español e inglés completos, tema claro/oscuro y color dinámico.

### Decisiones destacadas

- Sin permiso de `INTERNET`. Sin anuncios, analítica ni cuentas.
- Sin `READ_PHONE_STATE`: la detección de llamada usa `AudioManager.getMode()`.
- Sin `USE_EXACT_ALARM`: la política de Play lo reserva a apps de calendario y despertador.

### Limitaciones conocidas

- La app puede activar No molestar, pero Android no permite a ninguna app desactivar un modo
  activado por otra.
- El volumen de accesibilidad es de solo lectura: escribirlo requiere un permiso de firma.
- No existe control de volumen por aplicación en Android; queda fuera de alcance.
- Un *force stop* del sistema o del usuario detiene toda automatización hasta que se vuelve
  a abrir la app. Se detecta y se informa, pero no se puede evitar.
