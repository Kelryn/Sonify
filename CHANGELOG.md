# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/).

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
